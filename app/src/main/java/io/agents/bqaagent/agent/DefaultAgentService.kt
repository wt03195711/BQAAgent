// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.agent

import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import io.agents.bqaagent.ClawApplication
import io.agents.bqaagent.R
import io.agents.bqaagent.agent.langchain.LangChain4jToolBridge
import io.agents.bqaagent.agent.llm.LlmClient
import io.agents.bqaagent.agent.llm.LlmClientFactory
import io.agents.bqaagent.agent.llm.LlmTraceContext
import io.agents.bqaagent.agent.llm.LlmResponse
import io.agents.bqaagent.agent.llm.StreamingListener
import io.agents.bqaagent.adb.LocalAdbAutomation
import io.agents.bqaagent.adb.LocalAdbDeviceDriver
import io.agents.bqaagent.tool.ToolRegistry
import io.agents.bqaagent.tool.impl.GetScreenInfoTool
import io.agents.bqaagent.tool.ToolResult
import io.agents.bqaagent.utils.XLog
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.agent.tool.ToolSpecification
import java.io.File
import java.util.LinkedList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class DefaultAgentService : AgentService {

    companion object {
        private const val TAG = "AgentService"
        private val GSON = Gson()

        /**
         * Optimized system prompt for local LLMs.
         * Shorter than Cloud prompt but includes essential rules.
         * Task-only — chat is handled separately.
         */
        private const val LOCAL_TASK_PROMPT = """You are a phone assistant. Control the Android phone with the available tools and complete the user's task.

## Loop
1. Use the current screen already provided in the prompt when present.
2. Pick one tool call.
3. If an action result includes "Screen after action", use that snapshot for the next decision instead of calling get_screen_info again.
4. Call finish(summary="actual result") when done.

## Tool habits
- Open apps with open_app(package_name="Chrome" or package_name="com.android.chrome"). Do not call get_installed_apps just to open an app.
- For browser/web/search tasks, the first navigation action must be open_url(url="https://..."). Use a search URL when the user asks to search the web.
- Tap visible nodes with tap_node(node_id="n3") when possible; use tap coordinates only if node IDs are unavailable.
- Type with input_text. Press enter/back/home with system_key.
- Use scroll_to_find or find_and_tap for off-screen text.
- Use get_screen_info(mode="compact") by default. Use form/actionable/text for focused views. Use full only for debugging.

## Rules
- One tool call per turn.
- If the same approach fails 3 times, finish with the limitation.
- Summaries must include the actual data found, not just "I checked".
- For passwords and financial final actions, follow any later task-specific policy. If no task-specific policy allows the action, stop before entering sensitive credentials or finalizing financial actions. Never delete data."""

        /** Maximum number of retries on LLM API call failure */
        private const val MAX_API_RETRIES = 3
        /** Dead-loop detection: sliding window size */
        private const val LOOP_DETECT_WINDOW = 4

        /**
         * Opt-3: Action tools — after any of these execute we auto-attach a fresh
         * get_screen_info result so the LLM can see the updated UI without spending
         * an extra inference round (5 s) to call it manually.
         */
        private val ACTION_TOOLS = setOf(
            "phone_click_node", "phone_tap", "phone_swipe", "phone_long_press",
            "tap", "tap_node", "tap_visible_text", "long_press", "swipe", "scroll_to_find", "find_and_tap",
            "input_text", "input_amount", "bank_own_account_transfer", "type_text", "system_key", "open_app", "open_url",
            "dpad_up", "dpad_down", "dpad_left", "dpad_right", "dpad_center",
            "volume_up", "volume_down", "press_menu", "press_power",
            "clipboard", "send_file", "send_message", "make_call", "repeat_actions", "secure_keypad_input", "wait"
        )
        private val SKIP_AUTO_SCREEN_AFTER_ACTION = setOf("scroll_to_find", "bank_own_account_transfer")
        /** ms to wait for UI to settle before capturing screen after an action */
        private const val SCREEN_SETTLE_MS = 500L
        private const val UNUSABLE_SCREEN_PREFIX = "SCREEN_TREE_UNUSABLE"
        private const val SENSITIVE_HARD_TOKEN_LIMIT = 400_000
        private const val DUMP_ONLY_HARD_TOKEN_LIMIT = 180_000
        private const val BROWSER_HARD_TOKEN_LIMIT = 220_000
        private val DUMP_ONLY_FORBIDDEN_TOOLS = SensitiveAppPolicy.forbiddenTools
        private val CORE_AGENT_TOOLS = linkedSetOf(
            "get_screen_info",
            "find_node_info",
            "open_app",
            "open_url",
            "tap",
            "tap_node",
            "tap_visible_text",
            "long_press",
            "input_text",
            "input_amount",
            "secure_keypad_input",
            "system_key",
            "swipe",
            "scroll_to_find",
            "find_and_tap",
            "wait",
            "finish",
            "analyze_screen_visual",
        )
        private val SENSITIVE_AGENT_TOOLS = linkedSetOf(
            "get_screen_info",
            "find_node_info",
            "open_app",
            "open_url",
            "wait",
            "finish",
            "tap",
            "tap_node",
            "tap_visible_text",
            "swipe",
            "scroll_to_find",
            "find_and_tap",
            "system_key",
            "secure_keypad_input",
            "input_amount",
            "bank_own_account_transfer",
            "analyze_screen_visual",
        )

        /** Whether to write raw network request/response data to sandbox cache files for debugging */
        @JvmField
        var FILE_LOGGING_ENABLED = false
        @JvmField
        var FILE_LOGGING_CACHE_DIR: File? = null

        private fun isDumpOnlyRequest(request: String): Boolean {
            val lower = request.lowercase()
            return listOf(
                "dump-only",
                "dump only",
                "only adb dump",
                "adb dump only",
                "no screenshot",
                "without screenshot",
                "avoid screenshot",
                "do not use screenshot",
                "don't use screenshot",
                "dont use screenshot",
                "no ocr",
                "without ocr",
                "no accessibility",
                "without accessibility",
                "bank",
                "银行",
                "只能用 adb",
                "只能使用 adb",
                "只能用adb",
                "只能使用adb",
                "不能用截图",
                "不要截图",
                "禁止截图",
                "不能用 ocr",
                "不能用ocr",
                "不能用无障碍",
                "不要用无障碍"
            ).any { lower.contains(it) }
        }

        private fun isBrowserTask(request: String): Boolean {
            val lower = request.lowercase()
            return listOf(
                "chrome",
                "browser",
                "webpage",
                "website",
                "url",
                "http://",
                "https://",
                "网页",
                "网站",
                "浏览器"
            ).any { lower.contains(it) }
        }

        private fun isBrowserNavigationTask(request: String): Boolean {
            val lower = request.lowercase()
            return isBrowserTask(request) && hasAny(
                lower,
                "search",
                "website",
                "webpage",
                "web page",
                "url",
                "http://",
                "https://",
                "官网",
                "官方网站"
            )
        }

        private fun allowFullScreenInfo(request: String): Boolean {
            val lower = request.lowercase()
            return hasAny(lower, "full dump", "full tree", "full ui", "debug screen", "debug ui", "完整dump", "完整 dump")
        }

        private fun activeToolNamesFor(
            request: String,
            dumpOnlyTask: Boolean,
            browserTask: Boolean,
            sensitiveTask: Boolean,
        ): Set<String> {
            val lower = request.lowercase()
            val names = linkedSetOf<String>()

            val browserNavigationTask = isBrowserNavigationTask(request)
            if (browserNavigationTask) {
                names.addAll(listOf("get_screen_info", "open_url", "wait", "finish"))
                return names
            }
            if (sensitiveTask) {
                names.addAll(SENSITIVE_AGENT_TOOLS)
                return names
            }

            names.addAll(CORE_AGENT_TOOLS)
            if (browserTask || browserNavigationTask || hasAny(lower, "search web", "google", "官网", "官方网站", "website")) {
                names.add("open_url")
            }
            if (hasAny(lower, "installed apps", "list apps", "app list", "packages", "安装了哪些", "应用列表")) {
                names.add("get_installed_apps")
            }
            if (hasAny(lower, "battery", "wifi", "wi-fi", "storage", "bluetooth", "device info", "screen resolution", "电量", "存储", "蓝牙")) {
                names.add("get_device_info")
            }
            if (hasAny(lower, "notification", "notifications", "通知")) {
                names.add("get_notifications")
            }
            if (hasAny(lower, "clipboard", "copy to clipboard", "read my clipboard", "剪贴板")) {
                names.add("clipboard")
            }
            if (!dumpOnlyTask && hasAny(lower, "screenshot", "screencap", "截图", "截圖")) {
                names.add("take_screenshot")
            }
            if (!dumpOnlyTask && hasAny(lower, "send file", "share file", "upload file", "发送文件", "分享文件")) {
                names.add("send_file")
            }
            if (hasMessagingIntent(lower)) {
                names.add("send_message")
            }
            if (hasCallIntent(lower)) {
                names.add("make_call")
            }
            if (hasAny(lower, "repeat ", "loop ", "again and again", "重复")) {
                names.add("repeat_actions")
            }
            if (hasAny(lower, "knowledge base", "kb_", "todo", "待办")) {
                names.addAll(listOf("kb_write", "kb_read", "kb_search", "kb_append", "kb_add_todo"))
            }
            if (dumpOnlyTask || sensitiveTask) {
                names.removeAll(DUMP_ONLY_FORBIDDEN_TOOLS)
            }
            return names
        }

        private fun hasMessagingIntent(lower: String): Boolean {
            if (lower.contains("email")) return false
            return hasAny(lower, "send message", "text ", "sms", "whatsapp", "telegram", "发消息", "發信息", "短信")
        }

        private fun hasCallIntent(lower: String): Boolean {
            return hasAny(lower, "call ", "dial ", "phone ", "打电话", "打電話")
        }

        private fun hasAny(lower: String, vararg terms: String): Boolean {
            return terms.any { lower.contains(it) }
        }

        private fun buildDumpOnlyPromptSection(): String {
            return """

## Dump-only mode
- Use ADB UI-tree tools only. Visual-file and sharing tools are unavailable by policy.
- If get_screen_info returns SCREEN_TREE_UNUSABLE, perform at most one state-changing retry (wait/back/reopen). If the UI tree is still unusable, you may call analyze_screen_visual as a fallback (if available in your toolset) to get visual guidance. If that also fails or is unavailable, call finish and explain the limitation.
"""
        }

        private fun buildBrowserPromptSection(): String {
            return """

## Browser navigation
- For web pages, prefer open_url(url="https://...") over manually editing Chrome's address bar.
- For search tasks, open a deterministic search URL such as https://www.google.com/search?q=... when the user did not require a specific search engine UI.
- After opening a URL, use the compact screen snapshot attached to the action result before deciding the next step.
"""
        }

        private fun isUnusableScreen(data: String?): Boolean {
            return data?.startsWith(UNUSABLE_SCREEN_PREFIX) == true
        }

        private fun sensitiveAmountTerminalMessage(toolName: String, result: ToolResult, request: String): String? {
            if (toolName == "bank_own_account_transfer") {
                result.data?.takeIf { it.contains("COMPLETED:") }?.let { data ->
                    return "Task completed by bank transfer flow. $data"
                }
                result.data?.takeIf { it.contains("BLOCKED:") }?.let { data ->
                    return "Task stopped during bank transfer flow before unsafe final action. $data"
                }
                result.error?.takeIf { it.isNotBlank() }?.let { error ->
                    return "Task stopped during bank transfer flow before unsafe final action. $error"
                }
            }
            if (toolName != "input_amount") return null
            result.data?.takeIf { it.contains("BLOCKED:") }?.let { data ->
                if (SensitiveAppPolicy.allowsReceiveAmountInput(request) && !isReceiveSideAmountResult(data)) {
                    return null
                }
                return "Task stopped on the amount page before any final transaction action. $data"
            }
            result.error?.takeIf {
                it.contains("receive/credit amount", ignoreCase = true)
            }?.let { error ->
                return "Task stopped on the amount page before any final transaction action. $error"
            }
            return null
        }

        private fun isReceiveAmountField(field: String?): Boolean {
            val normalized = field
                ?.lowercase()
                ?.replace(" ", "")
                ?.replace("-", "")
                ?.trim()
                .orEmpty()
            return normalized in setOf("credit", "to", "receive", "destination")
        }

        private fun isReceiveSideAmountResult(data: String): Boolean {
            val targetSummary = data.substringBefore(". Amount state", data).lowercase()
            return listOf("credit", "receive", "入账金额", "入賬金額", "入帳金額")
                .any { targetSummary.contains(it) }
        }
    }

    private lateinit var config: AgentConfig
    private lateinit var llmClient: LlmClient
    private lateinit var toolSpecs: List<ToolSpecification>
    private var executor: ExecutorService? = null
    private val running = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private var taskFuture: java.util.concurrent.Future<*>? = null

    override fun initialize(config: AgentConfig) {
        this.config = config
        this.llmClient = LlmClientFactory.create(config)
        this.toolSpecs = LangChain4jToolBridge.buildToolSpecifications()
        this.executor = Executors.newSingleThreadExecutor()
        XLog.i(TAG, "Agent initialized: provider=${config.provider}, model=${config.modelName}, streaming=${config.streaming}")
    }

    override fun updateConfig(config: AgentConfig) {
        if (running.get()) {
            cancel()
            XLog.w(TAG, "Task was running during config update, cancelled")
        }
        executor?.shutdownNow()
        // Close old LlmClient before reinitializing to free engine memory
        if (::llmClient.isInitialized) {
            try {
                llmClient.close()
                XLog.i(TAG, "Old LlmClient closed before config update")
            } catch (e: Exception) {
                XLog.w(TAG, "Old LlmClient close error during config update", e)
            }
        }
        initialize(config)
        XLog.i(TAG, "Agent config updated, new model: ${config.modelName}")
    }

    override fun executeTask(userPrompt: String, callback: AgentCallback) {
        if (running.get()) {
            callback.onError(0, IllegalStateException("Agent is already running a task"), 0)
            return
        }

        running.set(true)
        cancelled.set(false)
        var terminalCallback: (() -> Unit)? = null

        val callbackProxy = object : AgentCallback {
            override fun onLoopStart(round: Int) = callback.onLoopStart(round)

            override fun onContent(round: Int, content: String) = callback.onContent(round, content)

            override fun onToolCall(round: Int, toolId: String, toolName: String, parameters: String) {
                callback.onToolCall(round, toolId, toolName, parameters)
            }

            override fun onToolResult(round: Int, toolId: String, toolName: String, parameters: String, result: ToolResult) {
                callback.onToolResult(round, toolId, toolName, parameters, result)
            }

            override fun onTokenUpdate(status: TokenMonitor.Status) = callback.onTokenUpdate(status)

            override fun onComplete(round: Int, finalAnswer: String, totalTokens: Int, modelName: String?) {
                terminalCallback = { callback.onComplete(round, finalAnswer, totalTokens, modelName) }
            }

            override fun onError(round: Int, error: Exception, totalTokens: Int) {
                terminalCallback = { callback.onError(round, error, totalTokens) }
            }

            override fun onSystemDialogBlocked(round: Int, totalTokens: Int) {
                terminalCallback = { callback.onSystemDialogBlocked(round, totalTokens) }
            }

            override fun onScreenshotBlocked(round: Int, intent: String, timeoutMs: Long): String? {
                return callback.onScreenshotBlocked(round, intent, timeoutMs)
            }
        }

        taskFuture = executor?.submit {
            try {
                runAgentLoop(userPrompt, callbackProxy)
            } catch (e: Exception) {
                if (terminalCallback == null) {
                    if (cancelled.get()) {
                        XLog.i(TAG, "Agent task cancelled (interrupted)")
                    } else {
                        XLog.e(TAG, "Agent execution error", e)
                        terminalCallback = { callback.onError(0, e, 0) }
                    }
                }
            } finally {
                // Close local engine BEFORE clearing running flag so the chat engine
                // reload (triggered by onComplete/onError) never overlaps with task engine.
                if (::llmClient.isInitialized) {
                    try {
                        llmClient.close()
                        XLog.i(TAG, "LlmClient closed after task completion")
                    } catch (e: Exception) {
                        XLog.w(TAG, "LlmClient close error after task", e)
                    }
                }
                running.set(false)
                val terminal = if (cancelled.get()) {
                    XLog.i(TAG, "Suppressing terminal callback after cancellation")
                    null
                } else {
                    terminalCallback
                }
                terminalCallback = null
                terminal?.invoke()
            }
        }
    }

    // ==================== Pre-flight Check ====================

    private fun preCheck(): String? {
        if (!LocalAdbAutomation.awaitReady(3_000L)) {
            XLog.w(TAG, "preCheck: Local ADB initial probe failed; attempting remembered reconnect")
            val reconnect = LocalAdbAutomation.reconnectRemembered(ClawApplication.instance, 8_000L)
            if (reconnect.isSuccess) {
                XLog.i(TAG, "preCheck: Local ADB reconnected from remembered pairing")
                return null
            }
            XLog.w(TAG, "preCheck: Local ADB remembered reconnect failed: ${reconnect.stderr.ifBlank { reconnect.stdout }}")
            return ClawApplication.instance.getString(R.string.agent_local_adb_not_ready)
        }
        return null
    }

    private fun buildActiveToolSpecs(
        request: String,
        dumpOnlyTask: Boolean,
        browserTask: Boolean,
        sensitiveTask: Boolean,
    ): List<ToolSpecification> {
        val activeNames = activeToolNamesFor(request, dumpOnlyTask, browserTask, sensitiveTask)
        val filtered = toolSpecs.filter { it.name() in activeNames }
        XLog.i(
            TAG,
            "runAgentLoop: active tool schema ${filtered.size}/${toolSpecs.size}: " +
                filtered.joinToString(", ") { it.name() }
        )
        return filtered
    }

    // ==================== Device Context ====================

    private fun buildDeviceContext(): String {
        val app = ClawApplication.instance
        val sb = StringBuilder()
        sb.append("\n\n## Device Info\n")
        sb.append("- Brand: ").append(Build.BRAND).append("\n")
        sb.append("- Model: ").append(Build.MODEL).append("\n")
        sb.append("- Android Version: ").append(Build.VERSION.RELEASE)
            .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")

        try {
            val wm = app
                .getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            sb.append("- Screen Resolution: ").append(dm.widthPixels).append("x").append(dm.heightPixels).append("\n")
        } catch (e: Exception) {
            XLog.w(TAG, "Failed to get display metrics", e)
        }

        sb.append("- Registered Tools: ").append(ToolRegistry.getAllTools().size).append("\n")

        val appName = try {
            val appInfo = app.packageManager.getApplicationInfo(app.packageName, 0)
            app.packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) { "BQAAgent" }
        sb.append("\n## This App Info\n")
        sb.append("- App Name: ").append(appName).append("\n")
        sb.append("- Package Name: ").append(app.packageName).append("\n")
        sb.append("- When the user refers to 'this app' or 'the app', they mean the app above.\n")

        return sb.toString()
    }

    // ==================== LLM Call (with retry) ====================

    private fun chatWithRetry(
        messages: List<ChatMessage>,
        callback: AgentCallback,
        iteration: Int,
        activeToolSpecs: List<ToolSpecification> = toolSpecs,
    ): LlmResponse {
        var lastException: Exception? = null
        for (attempt in 0 until MAX_API_RETRIES) {
            if (cancelled.get()) throw RuntimeException(ClawApplication.instance.getString(R.string.agent_task_cancelled))
            try {
                return if (config.streaming) {
                    val textBuilder = StringBuilder()
                    llmClient.chatStreaming(messages, activeToolSpecs, object : StreamingListener {
                        override fun onPartialText(token: String) {
                            textBuilder.append(token)
                            callback.onContent(iteration, token)
                        }
                        override fun onComplete(response: LlmResponse) {}
                        override fun onError(error: Throwable) {}
                    })
                } else {
                    llmClient.chat(messages, activeToolSpecs)
                }
            } catch (e: Exception) {
                lastException = e
                if (cancelled.get()) {
                    XLog.i(TAG, "LLM call stopped after cancellation: ${e.message ?: e.javaClass.simpleName}")
                    throw e
                }
                val msg = e.message ?: ""
                // Do not retry on token exhaustion or auth failure
                if (msg.contains("401") || msg.contains("403") || msg.contains("insufficient")) {
                    throw e
                }
                val delay = (Math.pow(2.0, attempt.toDouble()) * 1000).toLong()
                XLog.w(TAG, "LLM API call failed (attempt ${attempt + 1}/$MAX_API_RETRIES), retrying in ${delay}ms: $msg")
                try {
                    Thread.sleep(delay)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw e
                }
            }
        }
        throw lastException!!
    }

    // ==================== Dead Loop Detection ====================

    private data class RoundFingerprint(val screenHash: Int, val toolCall: String)

    private fun isStuckInLoop(history: LinkedList<RoundFingerprint>): Boolean {
        if (history.size < LOOP_DETECT_WINDOW) return false
        val first = history.first()
        return history.all { it == first }
    }

    // ==================== Context Compression ====================

    /** Protected zone: keep the most recent N rounds intact */
    private val KEEP_RECENT_ROUNDS = 3
    private val SENSITIVE_KEEP_RECENT_ROUNDS = 1

    /** Large-output observation tools → compressed placeholder */
    private val OBSERVATION_PLACEHOLDERS = mapOf(
        "get_screen_info" to "[screen info omitted]",
        "take_screenshot" to "[screenshot result omitted]",
        "find_node_info" to "[node find result omitted]",
        "get_installed_apps" to "[app list omitted]",
        "scroll_to_find" to "[scroll find result omitted]"
    )

    /**
     * Compress history messages before sending to save input tokens:
     * - get_screen_info: keep only the latest complete result globally
     * - Protected zone (most recent keepRecentRounds rounds): keep intact
     * - Outside protected zone: keep AI thinking as-is, compress tool results to a one-line summary
     */
    private fun compressHistoryForSend(
        messages: MutableList<ChatMessage>,
        keepRecentRounds: Int = KEEP_RECENT_ROUNDS,
    ) {
        // Count total characters before compression
        val charsBefore = messages.sumOf { msg ->
            when (msg) {
                is AiMessage -> (msg.text()?.length ?: 0) + (msg.toolExecutionRequests()?.sumOf { it.arguments()?.length ?: 0 } ?: 0)
                is ToolExecutionResultMessage -> msg.text().length
                is UserMessage -> msg.singleText().length
                is SystemMessage -> msg.text().length
                else -> 0
            }
        }
        val msgCountBefore = messages.size

        // 0. Special handling for get_screen_info: regardless of tier, keep only the latest complete result globally
        val screenPlaceholder = OBSERVATION_PLACEHOLDERS["get_screen_info"]!!
        val lastScreenIdx = messages.indexOfLast {
            it is ToolExecutionResultMessage && it.toolName() == "get_screen_info"
        }
        for (i in messages.indices) {
            val msg = messages[i]
            if (msg is ToolExecutionResultMessage
                && msg.toolName() == "get_screen_info"
                && i != lastScreenIdx
                && msg.text() != screenPlaceholder
            ) {
                messages[i] = ToolExecutionResultMessage.from(msg.id(), msg.toolName(), screenPlaceholder)
            }
        }

        // 1. Find indices of all AiMessages; each represents one round
        val aiIndices = messages.indices.filter { messages[it] is AiMessage }
        val protectedRounds = keepRecentRounds.coerceAtLeast(1)
        if (aiIndices.size <= protectedRounds) return

        val totalRounds = aiIndices.size

        for (roundIdx in aiIndices.indices) {
            val roundFromEnd = totalRounds - roundIdx
            if (roundFromEnd <= protectedRounds) break // protected zone

            val aiIndex = aiIndices[roundIdx]

            // Collect ToolExecutionResultMessage indices for this round
            var j = aiIndex + 1
            while (j < messages.size && messages[j] is ToolExecutionResultMessage) {
                compressToolResultMessage(messages, j)
                j++
            }
        }

        // Count total characters after compression
        val charsAfter = messages.sumOf { msg ->
            when (msg) {
                is AiMessage -> (msg.text()?.length ?: 0) + (msg.toolExecutionRequests()?.sumOf { it.arguments()?.length ?: 0 } ?: 0)
                is ToolExecutionResultMessage -> msg.text().length
                is UserMessage -> msg.singleText().length
                is SystemMessage -> msg.text().length
                else -> 0
            }
        }
        val saved = charsBefore - charsAfter
        if (saved > 0) {
            XLog.i(TAG, "Context compressed: ${charsBefore}→${charsAfter} chars, saved ${saved} chars (${saved * 100 / charsBefore}%), rounds=${aiIndices.size}")
        }
    }

    /** Compress Tool Result: use placeholder for observation tools, truncate summary for others */
    private fun compressToolResultMessage(messages: MutableList<ChatMessage>, index: Int) {
        val msg = messages[index] as ToolExecutionResultMessage
        val text = msg.text()
        if (text.length <= 100) return // already short enough, no need to compress

        val placeholder = OBSERVATION_PLACEHOLDERS[msg.toolName()]
        if (placeholder != null) {
            messages[index] = ToolExecutionResultMessage.from(msg.id(), msg.toolName(), placeholder)
            return
        }

        // Other tools: parse JSON to extract a summary
        val compressed = summarizeToolResult(text)
        messages[index] = ToolExecutionResultMessage.from(msg.id(), msg.toolName(), compressed)
    }

    /** Compress ToolResult JSON into a one-line summary */
    private fun summarizeToolResult(resultJson: String): String {
        return try {
            val mapType = object : TypeToken<Map<String, Any?>>() {}.type
            val map: Map<String, Any?> = GSON.fromJson(resultJson, mapType)
            val isSuccess = map["isSuccess"] as? Boolean ?: false
            if (isSuccess) {
                val data = map["data"]?.toString() ?: "ok"
                "✓ " + if (data.length > 80) data.take(80) + "..." else data
            } else {
                val error = map["error"]?.toString() ?: "failed"
                "✗ " + if (error.length > 80) error.take(80) + "..." else error
            }
        } catch (_: Exception) {
            if (resultJson.length > 80) resultJson.take(80) + "..." else resultJson
        }
    }

    // ==================== Main Execution Loop ====================

    private fun runAgentLoop(userPrompt: String, callback: AgentCallback) {
        // Pre-flight check
        preCheck()?.let {
            callback.onError(0, RuntimeException(it), 0)
            return
        }

        val parsedPrompt = TaskPromptEnvelope.parse(userPrompt)
        val rawUserRequest = parsedPrompt.currentRequest
        val sensitiveTask = SensitiveAppPolicy.isSensitiveTask(rawUserRequest)
        val dumpOnlyTask = isDumpOnlyRequest(rawUserRequest) || sensitiveTask
        val browserTask = isBrowserTask(rawUserRequest)
        val browserNavigationTask = isBrowserNavigationTask(rawUserRequest)
        LocalAdbDeviceDriver.invalidateScreenCache()

        // Build System Prompt — use optimized prompt for local LLM
        val basePrompt = if (config.provider == LlmProvider.LOCAL) {
            LOCAL_TASK_PROMPT
        } else {
            config.systemPrompt
        }
        val activeToolSpecs = buildActiveToolSpecs(rawUserRequest, dumpOnlyTask, browserTask, sensitiveTask)
        val activeToolNameSet = activeToolSpecs.map { it.name() }.toSet()

        val inAppSearchGuard = InAppSearchGuard.fromTask(rawUserRequest)
        val emailComposeGuard = EmailComposeGuard.fromTask(rawUserRequest)
        val directDeviceDataGuard = DirectDeviceDataGuard.fromTask(rawUserRequest)

        // For local LLM, inject matching playbook into system prompt
        val playbookSection = if (config.provider == LlmProvider.LOCAL || sensitiveTask) {
            val matched = PlaybookManager.match(rawUserRequest)
            if (matched != null) {
                XLog.i(TAG, "Playbook matched: ${matched.id} for '$rawUserRequest'")
                "\n\n## Playbook: ${matched.name}\nFollow these steps exactly:\n\n${matched.body}"
            } else ""
        } else ""

        val fullSystemPrompt = buildString {
            append(basePrompt)
            append(playbookSection)
            if (dumpOnlyTask) append(buildDumpOnlyPromptSection())
            if (sensitiveTask) append(SensitiveAppPolicy.buildPromptSection(rawUserRequest))
            if (browserTask) append(buildBrowserPromptSection())
            append(inAppSearchGuard.buildPromptSection())
            append(emailComposeGuard.buildPromptSection())
            append(directDeviceDataGuard.buildPromptSection())
            append(buildDeviceContext())
        }

        val messages = mutableListOf<ChatMessage>()
        messages.add(SystemMessage.from(fullSystemPrompt))

        val promptForModel = if (sensitiveTask) {
            XLog.i(TAG, "runAgentLoop: sensitive task uses current request only; chat history/background omitted")
            rawUserRequest
        } else if (parsedPrompt.hasChatHistory || parsedPrompt.hasBackgroundState) {
            buildString {
                append("You are continuing an existing chatroom. Use the provided context when the current request refers to earlier messages or asks about current background activity.\n\n")
                parsedPrompt.backgroundState?.trim()?.takeIf { it.isNotEmpty() }?.let { state ->
                    append("Current background status:\n")
                    append(state)
                    append("\n\n")
                }
                parsedPrompt.chatHistory?.trim()?.takeIf { it.isNotEmpty() }?.let { history ->
                    append("Chatroom so far:\n")
                    append(history)
                    append("\n\n")
                }
                append("Current user request:\n")
                append(rawUserRequest)
            }
        } else {
            rawUserRequest
        }

        // Opt-2: Pre-warm — only attach screen info for task-like prompts.
        // Chat/questions should NOT see screen data (it confuses the LLM into using tools).
        val lowerPrompt = rawUserRequest.lowercase()
        val looksLikeTask = lowerPrompt.contains("open ") || lowerPrompt.contains("send ") ||
            lowerPrompt.contains("tap ") || lowerPrompt.contains("search ") ||
            lowerPrompt.contains("play ") || lowerPrompt.contains("take ") ||
            lowerPrompt.contains("install ") || lowerPrompt.contains("click ") ||
            lowerPrompt.contains("go to ") || lowerPrompt.contains("navigate ") ||
            lowerPrompt.contains("turn on ") || lowerPrompt.contains("turn off ") ||
            lowerPrompt.contains("monitor ") || lowerPrompt.contains("close ") ||
            lowerPrompt.contains("swipe ") || lowerPrompt.contains("scroll ") ||
            lowerPrompt.contains("check ") || lowerPrompt.contains("compose ") ||
            lowerPrompt.contains("find ") || lowerPrompt.contains("screen") ||
            lowerPrompt.contains("notification") || lowerPrompt.contains("read my") ||
            lowerPrompt.contains("call ") || lowerPrompt.contains("dial ") ||
            sensitiveTask

        var prewarmedScreenData: String? = null
        var screenChangedSincePrewarm = false
        val enrichedPrompt = if (looksLikeTask && !browserNavigationTask) {
            try {
                val screenTool = ToolRegistry.getInstance().getTool("get_screen_info")
                if (screenTool != null) {
                    val screenResult = screenTool.execute(mapOf("mode" to "compact"))
                    if (screenResult.isSuccess && !screenResult.data.isNullOrBlank()) {
                        val candidateScreenData = screenResult.data
                        if (sensitiveTask && !SensitiveAppPolicy.isSensitiveAppScreen(candidateScreenData)) {
                            XLog.i(TAG, "runAgentLoop: skipping non-sensitive-app pre-warm screen for sensitive task (${candidateScreenData!!.length} chars)")
                            promptForModel
                        } else {
                            prewarmedScreenData = candidateScreenData
                        XLog.i(TAG, "runAgentLoop: pre-warm screen attached (${prewarmedScreenData!!.length} chars)")
                        SensitiveAppPolicy.missingCredentialTerminalMessage(prewarmedScreenData, rawUserRequest)?.let { terminal ->
                            XLog.i(TAG, "Sensitive missing-credential terminal from pre-warm screen")
                            callback.onComplete(0, terminal, 0, null)
                            return
                        }
                        "$promptForModel\n\nCurrent screen:\n$prewarmedScreenData\n\n" +
                            "Use this current screen snapshot for the first step. Do not call get_screen_info again before your first action unless the screen may have changed."
                        }
                    } else promptForModel
                } else promptForModel
            } catch (e: Exception) { promptForModel }
        } else {
            XLog.i(TAG, "runAgentLoop: ${if (browserNavigationTask) "browser navigation" else "chat-like"} prompt, skipping pre-warm screen")
            promptForModel
        }
        messages.add(UserMessage.from(enrichedPrompt))

        var iterations = 0
        var totalTokens = 0
        var actualModelName: String? = null  // Track the real model name from API response
        val maxIterations = config.maxIterations
        val loopHistory = LinkedList<RoundFingerprint>()
        var lastScreenHash = 0
        var previousScreenTexts: Set<String> = emptySet()
        val tokenMonitor = TokenMonitor(config.modelName)
        val stuckDetector = StuckDetector()
        val taskBudget = TaskBudget.fromSettings()
        var softLimitWarned = false
        var consecutiveNoToolCalls = 0
        var consecutiveUnusableScreens = 0
        var securePinAttempts = 0
        var sensitiveDeepLinkAttempts = 0
        var sensitiveBankBatchAttempted = false
        var iterationsSinceLastVlm = 0
        var vlmAttempted = false
        val traceTurnId = LlmTraceContext.newTurnId("agent", rawUserRequest)
        val tracePromptHash = LlmTraceContext.promptHash(rawUserRequest)

        fun runSensitiveBankBatch(batchParams: Map<String, Any>, reason: String): Boolean {
            if (!sensitiveTask || sensitiveBankBatchAttempted) return false
            sensitiveBankBatchAttempted = true
            val toolName = "bank_own_account_transfer"
            val displayName = ToolRegistry.getInstance().getDisplayName(toolName)
            val paramsString = GSON.toJson(batchParams)
            XLog.i(TAG, "Sensitive bank batch auto-run from $reason: $paramsString")
            callback.onToolCall(iterations, toolName, displayName, paramsString)
            val result = try {
                ToolRegistry.getInstance().executeTool(toolName, batchParams)
            } catch (e: Exception) {
                ToolResult.error("Bank transfer batch flow failed: ${e.message}")
            }
            callback.onToolResult(iterations, toolName, displayName, paramsString, result)
            val terminal = sensitiveAmountTerminalMessage(toolName, result, rawUserRequest)
                ?: result.error?.let { "Task stopped during bank transfer flow before unsafe final action. $it" }
                ?: result.data?.let { "Task stopped during bank transfer flow before unsafe final action. $it" }
                ?: "Task stopped during bank transfer flow before unsafe final action."
            callback.onComplete(iterations, terminal, totalTokens, actualModelName)
            return true
        }

        fun runSensitiveBankBatchIfReady(screenData: String?, reason: String): Boolean {
            val batchParams = SensitiveAppPolicy.bankTransferBatchParamsIfReady(screenData, rawUserRequest)
                ?: return false
            return runSensitiveBankBatch(batchParams, reason)
        }

        if (runSensitiveBankBatchIfReady(prewarmedScreenData, "prewarm")) {
            return
        }

        while (iterations < maxIterations && !cancelled.get()) {
            iterations++
            callback.onLoopStart(iterations)
            iterationsSinceLastVlm++

            // Compress history messages before sending to save tokens. Sensitive
            // app tasks can have very large ADB dumps, so keep only the latest
            // full observation in context and summarize older rounds.
            compressHistoryForSend(
                messages,
                keepRecentRounds = if (sensitiveTask) SENSITIVE_KEEP_RECENT_ROUNDS else KEEP_RECENT_ROUNDS
            )

            // Periodic VLM hint: when VLM is configured and LLM hasn't tried it
            // for several iterations, inject a conditional reminder.
            // Covers both ADB unusable and ADB data incomplete scenarios.
            if (VlmConfigRepository.isConfigured()
                && iterationsSinceLastVlm >= 4
                && !vlmAttempted
                && iterations >= 4
            ) {
                messages.add(UserMessage.from(
                    "[VLM HINT] If you cannot find the target element in the current ADB UI data, " +
                            "call analyze_screen_visual to get visual guidance. " +
                            "Do NOT guess or click elements that are not related to your task."
                ))
                XLog.i(TAG, "Periodic VLM hint injected (gap=$iterationsSinceLastVlm)")
            }

            // LLM call (with retry)
            val llmResponse: LlmResponse
            try {
                LlmTraceContext.set(
                    LlmTraceContext.Trace(
                        conversationId = "agent",
                        turnId = traceTurnId,
                        turnSource = "agent",
                        userPromptHash = tracePromptHash,
                    )
                )
                try {
                    llmResponse = chatWithRetry(messages, callback, iterations, activeToolSpecs)
                } finally {
                    LlmTraceContext.clear()
                }
            } catch (e: Exception) {
                if (cancelled.get()) {
                    XLog.i(TAG, "LLM call stopped after cancellation: ${e.message ?: e.javaClass.simpleName}")
                    return
                }
                XLog.e(TAG, "LLM API call failed after retries", e)
                callback.onError(iterations, RuntimeException(ClawApplication.instance.getString(R.string.agent_api_call_failed, e.message)), totalTokens)
                return
            }

            if (cancelled.get()) {
                callback.onComplete(iterations, ClawApplication.instance.getString(R.string.agent_task_cancel), totalTokens, actualModelName)
                return
            }

            // Capture actual model name from first API response
            if (actualModelName == null && !llmResponse.modelName.isNullOrEmpty()) {
                actualModelName = llmResponse.modelName
                XLog.d(TAG, "runAgentLoop: actual model from API = $actualModelName")
            }
            // Accumulate token usage
            llmResponse.tokenUsage?.totalTokenCount()?.let { totalTokens += it }
            tokenMonitor.record(
                step = iterations,
                inputTokens = llmResponse.tokenUsage?.inputTokenCount(),
                outputTokens = llmResponse.tokenUsage?.outputTokenCount(),
                totalTokenCount = llmResponse.tokenUsage?.totalTokenCount()
            )
            callback.onTokenUpdate(tokenMonitor.getStatus())

            // Budget check
            val tokenStatus = tokenMonitor.getStatus()
            when (taskBudget.check(tokenStatus.totalTokens, tokenStatus.estimatedCostUsd)) {
                TaskBudget.Status.HARD_LIMIT -> {
                    XLog.w(TAG, "Budget HARD LIMIT reached at step $iterations: ${tokenStatus.formattedTokens} (${tokenStatus.formattedCost})")
                    callback.onComplete(
                        iterations,
                        "Task stopped: budget limit reached (${tokenStatus.formattedTokens} tokens, ${tokenStatus.formattedCost}). " +
                        "Increase budget in Settings if needed.",
                        totalTokens,
                        actualModelName
                    )
                    return
                }
                TaskBudget.Status.SOFT_LIMIT -> {
                    if (!softLimitWarned) {
                        softLimitWarned = true
                        XLog.i(TAG, "Budget SOFT LIMIT at step $iterations: ${tokenStatus.formattedTokens}")
                        messages.add(UserMessage.from(
                            "[System Notice] You are using ${tokenStatus.formattedTokens} tokens (${tokenStatus.formattedCost}), " +
                            "approaching the budget limit. Finish the task efficiently. " +
                            "If you cannot complete it soon, call finish with a partial summary."
                        ))
                    }
                }
                TaskBudget.Status.OK -> { /* continue normally */ }
            }

            val localHardTokenLimit = when {
                sensitiveTask -> SENSITIVE_HARD_TOKEN_LIMIT
                dumpOnlyTask -> DUMP_ONLY_HARD_TOKEN_LIMIT
                browserTask -> BROWSER_HARD_TOKEN_LIMIT
                else -> Int.MAX_VALUE
            }
            if (tokenStatus.totalTokens >= localHardTokenLimit) {
                XLog.w(TAG, "Task safety token limit reached at step $iterations: ${tokenStatus.formattedTokens}")
                val taskType = when {
                    sensitiveTask -> "sensitive dump-only"
                    dumpOnlyTask -> "dump-only"
                    else -> "browser"
                }
                callback.onComplete(
                    iterations,
                    "Task stopped: safety token limit reached for this $taskType task " +
                        "(${tokenStatus.formattedTokens}). The agent was likely stuck; try a narrower instruction or use open_url for direct navigation.",
                    totalTokens,
                    actualModelName
                )
                return
            }

            // DEBUG: log raw LLM response for tool calling diagnosis
            XLog.i(TAG, "runAgentLoop iter=$iterations response.text=${llmResponse.text?.take(500)}")
            XLog.i(TAG, "runAgentLoop iter=$iterations hasToolCalls=${llmResponse.hasToolExecutionRequests()} toolCallCount=${llmResponse.toolExecutionRequests?.size ?: 0}")

            // Add AI message to history (must construct AiMessage)
            val aiMessage = if (llmResponse.hasToolExecutionRequests()) {
                if (llmResponse.text.isNullOrEmpty()) {
                    AiMessage.from(llmResponse.toolExecutionRequests)
                } else {
                    AiMessage.from(llmResponse.text, llmResponse.toolExecutionRequests)
                }
            } else {
                AiMessage.from(llmResponse.text ?: "")
            }
            messages.add(aiMessage)

            // Push thinking content in non-streaming mode
            if (!config.streaming && !llmResponse.text.isNullOrEmpty()) {
                val suppressHallucinatedCompletion =
                    !llmResponse.hasToolExecutionRequests() &&
                        (inAppSearchGuard.shouldBlockTextOnlyCompletion() ||
                            emailComposeGuard.shouldBlockTextOnlyCompletion())
                if (!suppressHallucinatedCompletion) {
                    callback.onContent(iterations, llmResponse.text)
                }
            }

            // No tool calls in this response — LLM chose to respond with text only.
            // Respect that. If there's text, it's the answer. Done.
            if (!llmResponse.hasToolExecutionRequests()) {
                val responseText = llmResponse.text ?: ""
                if (responseText.isNotEmpty()) {
                    if (inAppSearchGuard.shouldBlockTextOnlyCompletion()) {
                        val correction = inAppSearchGuard.buildCompletionCorrection()
                        XLog.i(TAG, "InAppSearchGuard blocked text-only completion for '$userPrompt'")
                        messages.add(UserMessage.from(correction))
                        continue
                    }
                    if (directDeviceDataGuard.shouldBlockTextOnlyCompletion()) {
                        val correction = directDeviceDataGuard.buildCompletionCorrection()
                        XLog.i(TAG, "DirectDeviceDataGuard blocked text-only completion for '$userPrompt'")
                        messages.add(UserMessage.from(correction))
                        continue
                    }
                    if (emailComposeGuard.shouldBlockTextOnlyCompletion()) {
                        val correction = emailComposeGuard.buildCompletionCorrection()
                        XLog.i(TAG, "EmailComposeGuard blocked text-only completion for '$userPrompt'")
                        messages.add(UserMessage.from(correction))
                        continue
                    }
                    XLog.i(TAG, "runAgentLoop: text-only response, completing")
                    callback.onComplete(iterations, responseText, totalTokens, actualModelName)
                    return
                }
                // Empty response with no tools — something went wrong, finish
                XLog.w(TAG, "runAgentLoop: empty response with no tools, finishing")
                callback.onComplete(iterations, ClawApplication.instance.getString(R.string.agent_task_completed), totalTokens, actualModelName)
                continue
            }

            // Reset counter when LLM does use tools
            consecutiveNoToolCalls = 0

            // Execute tool calls
            for (toolRequest in llmResponse.toolExecutionRequests) {
                if (cancelled.get()) {
                    callback.onComplete(iterations, ClawApplication.instance.getString(R.string.agent_task_cancel), totalTokens, actualModelName)
                    return
                }

                val toolName = toolRequest.name() ?: ""
                val displayName = ToolRegistry.getInstance().getDisplayName(toolName)
                val toolArgs = toolRequest.arguments() ?: "{}"

                if (toolName !in activeToolNameSet) {
                    val policyError = buildString {
                        append("Tool '").append(toolName).append("' is not available for this task. ")
                        append("Use one of: ").append(activeToolNameSet.joinToString(", ")).append(".")
                        if (browserTask) {
                            append(" For browser/search/page tasks, use open_url with the target URL or a search URL.")
                        }
                    }
                    val blockedResult = ToolResult.error(policyError)
                    XLog.w(TAG, "Active schema policy blocked tool: $toolName")
                    callback.onToolCall(iterations, toolName, displayName, toolArgs)
                    callback.onToolResult(iterations, toolName, displayName, "", blockedResult)
                    messages.add(ToolExecutionResultMessage.from(toolRequest, GSON.toJson(blockedResult)))
                    messages.add(UserMessage.from("[System Notice] $policyError"))
                    continue
                }

                // Parse parameters
                val mapType = object : TypeToken<Map<String, Any>>() {}.type
                var params: Map<String, Any>? = try {
                    GSON.fromJson(toolArgs, mapType)
                } catch (e: Exception) {
                    XLog.w(TAG, "Failed to parse tool args for $toolName: $toolArgs", e)
                    HashMap()
                }
                if (params == null) params = HashMap()

                if (toolName == "get_screen_info" &&
                    params["mode"]?.toString()?.equals("full", ignoreCase = true) == true &&
                    !allowFullScreenInfo(rawUserRequest)
                ) {
                    params = params.toMutableMap().apply { put("mode", "compact") }
                    XLog.i(TAG, "get_screen_info full mode downgraded to compact for non-debug task")
                }

                if (sensitiveTask && toolName == "secure_keypad_input") {
                    params = params.toMutableMap().apply { put("submit_after_entry", false) }
                    XLog.i(TAG, "Sensitive secure_keypad_input: submit_after_entry forced false")
                }

                if (sensitiveTask &&
                    toolName == "input_amount" &&
                    SensitiveAppPolicy.allowsReceiveAmountInput(rawUserRequest) &&
                    isReceiveAmountField(params["field"]?.toString())
                ) {
                    params = params.toMutableMap().apply { put("allow_receive_amount", true) }
                    XLog.i(TAG, "Sensitive input_amount: receive-side amount allowed by explicit request")
                }

                val sensitivePolicyError = if (sensitiveTask) {
                    SensitiveAppPolicy.blockedToolMessage(toolName, rawUserRequest)
                        ?: SensitiveAppPolicy.blockedOpenUrlMessage(toolName, params, rawUserRequest)
                        ?: SensitiveAppPolicy.blockedInputMessage(toolName, params, rawUserRequest)
                        ?: SensitiveAppPolicy.blockedCredentialSwitchMessage(toolName, params, rawUserRequest)
                        ?: SensitiveAppPolicy.blockedScrollMessage(toolName, params, rawUserRequest)
                        ?: SensitiveAppPolicy.blockedTapMessage(toolName, params, rawUserRequest)
                } else {
                    null
                }
                if (sensitivePolicyError != null) {
                    if (sensitivePolicyError.startsWith("Use bank_own_account_transfer")) {
                        val batchParams = SensitiveAppPolicy.bankTransferBatchParamsFromRequest(rawUserRequest)
                        if (batchParams != null && runSensitiveBankBatch(batchParams, "policy_block_$toolName")) {
                            return
                        }
                    }
                    val blockedResult = ToolResult.error(sensitivePolicyError)
                    XLog.w(TAG, "Sensitive app policy blocked $toolName: $sensitivePolicyError")
                    callback.onToolCall(iterations, toolName, displayName, toolArgs)
                    callback.onToolResult(iterations, toolName, displayName, params.toString(), blockedResult)
                    messages.add(ToolExecutionResultMessage.from(toolRequest, GSON.toJson(blockedResult)))
                    messages.add(UserMessage.from("[System Notice] $sensitivePolicyError"))
                    continue
                }

                if (dumpOnlyTask && toolName in DUMP_ONLY_FORBIDDEN_TOOLS) {
                    val policyError = "Tool '$toolName' is disabled for this dump-only task. Use get_screen_info / ADB dump tools only, or finish with the limitation."
                    val blockedResult = ToolResult.error(policyError)
                    XLog.w(TAG, "Dump-only policy blocked tool: $toolName")
                    callback.onToolCall(iterations, toolName, displayName, toolArgs)
                    callback.onToolResult(iterations, toolName, displayName, params.toString(), blockedResult)
                    messages.add(ToolExecutionResultMessage.from(toolRequest, GSON.toJson(blockedResult)))
                    messages.add(UserMessage.from("[System Notice] $policyError"))
                    continue
                }

                val blockedFinish = if (toolName == "finish") {
                    val directBlock = directDeviceDataGuard.maybeBlockFinish()
                    val guardNeedsScreen = directBlock == null &&
                        (inAppSearchGuard.shouldBlockTextOnlyCompletion() ||
                            emailComposeGuard.shouldBlockTextOnlyCompletion())
                    val screenInfo = if (guardNeedsScreen) {
                        try {
                            ToolRegistry.getInstance()
                                .getTool("get_screen_info")
                                ?.execute(mapOf("mode" to "compact"))
                                ?.takeIf { it.isSuccess }
                                ?.data
                        } catch (_: Exception) {
                            null
                        }
                    } else {
                        null
                    }
                    directBlock
                        ?: inAppSearchGuard.maybeBlockFinish(screenInfo)
                        ?: emailComposeGuard.maybeBlockFinish(screenInfo)
                } else null
                if (blockedFinish != null) {
                    val blockedResult = ToolResult.error(blockedFinish)
                    XLog.i(TAG, "Task guard blocked premature finish for '$userPrompt'")
                    callback.onToolCall(iterations, toolName, displayName, toolArgs)
                    callback.onToolResult(iterations, toolName, displayName, params.toString(), blockedResult)
                    messages.add(ToolExecutionResultMessage.from(toolRequest, GSON.toJson(blockedResult)))
                    messages.add(UserMessage.from(blockedFinish))
                    continue
                }

                callback.onToolCall(iterations, toolName, displayName, toolArgs)
                directDeviceDataGuard.recordToolAttempt(toolName)
                emailComposeGuard.recordToolAttempt(toolName)

                val result = if (
                    toolName == "get_screen_info" &&
                    !screenChangedSincePrewarm &&
                    !prewarmedScreenData.isNullOrBlank()
                ) {
                    XLog.i(TAG, "runAgentLoop: reusing pre-warm screen for first get_screen_info")
                    ToolResult.success(prewarmedScreenData)
                } else {
                    ToolRegistry.getInstance().executeTool(toolName, params)
                }
                val paramsString = if (params.isEmpty()) "" else params.toString()
                callback.onToolResult(iterations, toolName, displayName, paramsString, result)
                if (result.isSuccess) {
                    inAppSearchGuard.recordSuccessfulTool(toolName, params)
                    emailComposeGuard.recordSuccessfulTool(toolName)
                }
                if (toolName in ACTION_TOOLS) {
                    screenChangedSincePrewarm = true
                }
                if (toolName == "analyze_screen_visual") {
                    vlmAttempted = true
                    iterationsSinceLastVlm = 0
                    XLog.i(TAG, "VLM tool called, tracking reset")

                    // Detect SCREENSHOT_BLOCKED or SCREENSHOT_FAILED → request user image and analyze directly
                    val err = result.error ?: ""
                    val isScreenshotBlocked = !result.isSuccess && err.contains("SCREENSHOT_BLOCKED")
                    val isScreenshotFailed = !result.isSuccess && err.contains("SCREENSHOT_FAILED") && !err.contains("ADB is not ready")
                    if (isScreenshotBlocked || isScreenshotFailed) {
                        val vlmIntent = params["intent"]?.toString() ?: "visual guidance needed"
                        val failReason = if (isScreenshotBlocked) "SCREENSHOT_BLOCKED" else "SCREENSHOT_FAILED"
                        XLog.i(TAG, "$failReason detected, requesting user image upload")

                        val userImagePath = callback.onScreenshotBlocked(iterations, vlmIntent, 60_000L)

                        if (userImagePath != null) {
                            XLog.i(TAG, "User provided image: $userImagePath, executing analyze_user_image directly")
                            val userImageResult = ToolRegistry.getInstance().executeTool(
                                "analyze_user_image",
                                mapOf("image_path" to userImagePath, "intent" to vlmIntent)
                            )
                            callback.onToolResult(iterations, "analyze_user_image", "Analyze User Image",
                                "image_path=$userImagePath", userImageResult)

                            val resultJson = GSON.toJson(userImageResult)
                            if (!userImageResult.isSuccess || resultJson.contains("IMAGE_MISMATCH")) {
                                val reason = if (resultJson.contains("IMAGE_MISMATCH")) {
                                    "Task stopped: the uploaded image does not show a relevant Android app screen. " +
                                            "Please upload a correct screenshot of the target app."
                                } else {
                                    "Task stopped: failed to analyze the uploaded image. ${userImageResult.error ?: "Unknown error"}"
                                }
                                XLog.i(TAG, "User image analysis terminal: $reason")
                                callback.onComplete(iterations, reason, totalTokens, actualModelName)
                                return
                            }

                            messages.add(ToolExecutionResultMessage.from(toolRequest, resultJson))
                            continue
                        } else {
                            XLog.i(TAG, "User skipped image upload or timed out")
                        }
                    }
                }
                if (sensitiveTask && toolName == "secure_keypad_input") {
                    securePinAttempts++
                    XLog.i(TAG, "Sensitive secure PIN attempt $securePinAttempts result=${result.isSuccess}")
                }
                if (sensitiveTask && toolName == "open_url" && result.isSuccess &&
                    SensitiveAppPolicy.isAllowedSensitiveDeepLink(params["url"]?.toString().orEmpty(), rawUserRequest)
                ) {
                    sensitiveDeepLinkAttempts++
                    XLog.i(TAG, "Sensitive deep-link recovery attempt $sensitiveDeepLinkAttempts")
                }

                sensitiveAmountTerminalMessage(toolName, result, rawUserRequest)?.let { terminal ->
                    XLog.i(TAG, "Sensitive amount terminal after $toolName: ${terminal.take(180)}")
                    callback.onComplete(iterations, terminal, totalTokens, actualModelName)
                    return
                }

                var postToolNotice: String? = null
                if (toolName == "get_screen_info" && result.isSuccess && result.data != null) {
                    SensitiveAppPolicy.missingCredentialTerminalMessage(result.data, rawUserRequest)?.let { terminal ->
                        XLog.i(TAG, "Sensitive missing-credential terminal from get_screen_info")
                        callback.onComplete(iterations, terminal, totalTokens, actualModelName)
                        return
                    }
                    SensitiveAppPolicy.loginTimeoutTerminalMessage(result.data, rawUserRequest, securePinAttempts, sensitiveDeepLinkAttempts)?.let { terminal ->
                        XLog.i(TAG, "Sensitive login-timeout terminal from get_screen_info")
                        callback.onComplete(iterations, terminal, totalTokens, actualModelName)
                        return
                    }
                    SensitiveAppPolicy.loginTimeoutRecoveryNotice(result.data, rawUserRequest, securePinAttempts, sensitiveDeepLinkAttempts)?.let { notice ->
                        XLog.i(TAG, "Sensitive login-timeout recovery notice from get_screen_info")
                        postToolNotice = notice
                    }
                    SensitiveAppPolicy.repeatedPinTerminalMessage(result.data, rawUserRequest, securePinAttempts)?.let { terminal ->
                        XLog.i(TAG, "Sensitive repeated-PIN terminal from get_screen_info")
                        callback.onComplete(iterations, terminal, totalTokens, actualModelName)
                        return
                    }
                    if (runSensitiveBankBatchIfReady(result.data, "get_screen_info")) {
                        return
                    }
                    if (isUnusableScreen(result.data)) {
                        consecutiveUnusableScreens++
                        XLog.w(TAG, "get_screen_info returned unusable tree ($consecutiveUnusableScreens consecutive)")
                        if (consecutiveUnusableScreens >= 2) {
                            if (VlmConfigRepository.isConfigured()) {
                                postToolNotice =
                                    "[System Notice] The ADB UI tree has been unusable for 2 consecutive attempts. " +
                                            "You MUST call analyze_screen_visual now to get visual guidance. " +
                                            "If analyze_screen_visual also fails (e.g. SCREENSHOT_BLOCKED), inform the user and call finish."
                            } else {
                                callback.onComplete(
                                    iterations,
                                    "Task stopped: adb UI tree is unusable for the current screen. " +
                                            "The app may hide its UI from uiautomator dump, or the current window is not exposing nodes.",
                                    totalTokens,
                                    actualModelName
                                )
                                return
                            }
                        } else {
                            if (VlmConfigRepository.isConfigured()) {
                                postToolNotice =
                                    "[System Notice] The current ADB UI tree is unusable. " +
                                            "Call analyze_screen_visual now to get visual guidance for the next step. " +
                                            "Do NOT call get_screen_info again without first performing a state-changing action or using visual analysis."
                            } else {
                                postToolNotice =
                                    "[System Notice] The current ADB UI tree is unusable. Do not call get_screen_info again unless you first perform a state-changing action such as wait, back, or reopen the app."
                            }
                        }
                    } else {
                        consecutiveUnusableScreens = 0
                    }
                } else if (toolName in ACTION_TOOLS && result.isSuccess && toolName in SKIP_AUTO_SCREEN_AFTER_ACTION) {
                    consecutiveUnusableScreens = 0
                }

                // System dialog blocking detected → notify user and stop task
                if (!result.isSuccess && result.error == GetScreenInfoTool.SYSTEM_DIALOG_BLOCKED) {
                    XLog.w(TAG, "System dialog blocked, notifying user and stopping task")
                    callback.onSystemDialogBlocked(iterations, totalTokens)
                    return
                }

                // finish tool → task complete
                if (toolName == "finish" && result.isSuccess) {
                    val finishData = result.data
                    callback.onComplete(iterations, finishData ?: ClawApplication.instance.getString(R.string.agent_task_completed), totalTokens, actualModelName)
                    return
                }

                // Opt-3: Auto-attach fresh screen state after action tools.
                // LLM sees updated UI in the same tool result → can decide next step
                // immediately without spending an extra 5 s inference round on get_screen_info.
                val combinedResultData: String = if (toolName in ACTION_TOOLS && toolName !in SKIP_AUTO_SCREEN_AFTER_ACTION) {
                    try {
                        Thread.sleep(SCREEN_SETTLE_MS) // let UI animate/settle
                        val screenTool = ToolRegistry.getInstance().getTool("get_screen_info")
                        val screenAfter = screenTool?.execute(mapOf("mode" to "compact"))
                        if (screenAfter != null && screenAfter.isSuccess && !screenAfter.data.isNullOrBlank()) {
                            SensitiveAppPolicy.missingCredentialTerminalMessage(screenAfter.data, rawUserRequest)?.let { terminal ->
                                XLog.i(TAG, "Sensitive missing-credential terminal after $toolName")
                                callback.onComplete(iterations, terminal, totalTokens, actualModelName)
                                return
                            }
                            SensitiveAppPolicy.loginTimeoutTerminalMessage(screenAfter.data, rawUserRequest, securePinAttempts, sensitiveDeepLinkAttempts)?.let { terminal ->
                                XLog.i(TAG, "Sensitive login-timeout terminal after $toolName")
                                callback.onComplete(iterations, terminal, totalTokens, actualModelName)
                                return
                            }
                            SensitiveAppPolicy.loginTimeoutRecoveryNotice(screenAfter.data, rawUserRequest, securePinAttempts, sensitiveDeepLinkAttempts)?.let { notice ->
                                XLog.i(TAG, "Sensitive login-timeout recovery notice after $toolName")
                                postToolNotice = notice
                            }
                            SensitiveAppPolicy.repeatedPinTerminalMessage(screenAfter.data, rawUserRequest, securePinAttempts)?.let { terminal ->
                                XLog.i(TAG, "Sensitive repeated-PIN terminal after $toolName")
                                callback.onComplete(iterations, terminal, totalTokens, actualModelName)
                                return
                            }
                            if (runSensitiveBankBatchIfReady(screenAfter.data, "screen_after_$toolName")) {
                                return
                            }
                            if (isUnusableScreen(screenAfter.data)) {
                                consecutiveUnusableScreens++
                                XLog.w(TAG, "Screen after $toolName is unusable ($consecutiveUnusableScreens consecutive)")
                                if (consecutiveUnusableScreens >= 2) {
                                    if (VlmConfigRepository.isConfigured()) {
                                        postToolNotice =
                                            "[System Notice] The screen after this action is still an unusable ADB UI tree. " +
                                                    "You MUST call analyze_screen_visual now to get visual guidance. " +
                                                    "If analyze_screen_visual also fails (e.g. SCREENSHOT_BLOCKED), inform the user and call finish."
                                    } else if (dumpOnlyTask) {
                                        callback.onComplete(
                                            iterations,
                                            "Task stopped: adb UI tree is unusable for the current screen. " +
                                                    "The app may hide its UI from uiautomator dump, and screenshot/OCR tools are disabled for this task.",
                                            totalTokens,
                                            actualModelName
                                        )
                                        return
                                    }
                                }
                                if (postToolNotice == null) {
                                    postToolNotice = when {
                                        dumpOnlyTask ->
                                            "[System Notice] The screen after this action is still an unusable ADB UI tree. " +
                                                    "Do not switch to screenshot/OCR. Try one state-changing recovery action, or call finish with the limitation."
                                        VlmConfigRepository.isConfigured() ->
                                            "[System Notice] The screen after this action is still an unusable ADB UI tree. " +
                                                    "You MUST call analyze_screen_visual now to get visual guidance for the next step. " +
                                                    "If analyze_screen_visual also fails (e.g. SCREENSHOT_BLOCKED), inform the user and call finish."
                                        else ->
                                            "[System Notice] The screen after this action is still an unusable ADB UI tree. " +
                                                    "Try one state-changing recovery action, or call finish with the limitation if the app hides its UI tree."
                                    }
                                }
                            } else {
                                consecutiveUnusableScreens = 0
                            }
                            // Update lastScreenHash for loop detection
                            lastScreenHash = screenAfter.data!!.hashCode()
                            XLog.i(TAG, "Opt3: auto-attached screen after $toolName (${screenAfter.data!!.length} chars)")
                            // Screen diff: extract text lines and compare with previous
                            val currentTexts = screenAfter.data!!.lines()
                                .map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                            val added = currentTexts - previousScreenTexts
                            val removed = previousScreenTexts - currentTexts
                            previousScreenTexts = currentTexts
                            val diffSection = buildString {
                                if (added.isNotEmpty()) append("\nNew on screen: ${added.take(10).joinToString(", ")}")
                                if (removed.isNotEmpty()) append("\nGone from screen: ${removed.take(10).joinToString(", ")}")
                            }
                            val baseData = result.data ?: ""
                            val enrichedData = "$baseData\n\nScreen after action:\n${screenAfter.data}$diffSection"
                            val enriched = if (result.isSuccess) ToolResult.success(enrichedData)
                                           else ToolResult.error(result.error ?: "")
                            GSON.toJson(enriched)
                        } else {
                            XLog.w(TAG, "Opt3: get_screen_info failed after $toolName: ${screenAfter?.error}")
                            GSON.toJson(result)
                        }
                    } catch (e: Exception) {
                        XLog.w(TAG, "Opt3: exception fetching screen after $toolName", e)
                        GSON.toJson(result)
                    }
                } else {
                    // Record fingerprint for dead-loop detection (non-action tools path)
                    if (toolName == "get_screen_info" && result.isSuccess && result.data != null) {
                        lastScreenHash = result.data.hashCode()
                    }
                    GSON.toJson(result)
                }

                // For action tools the loop detection hash was already updated above;
                // for non-get_screen_info action tools also record the fingerprint.
                if (toolName in ACTION_TOOLS) {
                    loopHistory.addLast(RoundFingerprint(lastScreenHash, "$toolName:$toolArgs"))
                    if (loopHistory.size > LOOP_DETECT_WINDOW) loopHistory.removeFirst()
                } else if (toolName.isNotEmpty() && toolName != "get_screen_info") {
                    loopHistory.addLast(RoundFingerprint(lastScreenHash, "$toolName:$toolArgs"))
                    if (loopHistory.size > LOOP_DETECT_WINDOW) loopHistory.removeFirst()
                }

                // Add tool result to messages
                messages.add(ToolExecutionResultMessage.from(toolRequest, combinedResultData))
                if (postToolNotice != null) {
                    messages.add(UserMessage.from(postToolNotice))
                }
                XLog.d(TAG, "displayName:$displayName toolName:$toolName")
            }

            // Stuck detection (5-signal, 3-level recovery)
            val lastAction = llmResponse.toolExecutionRequests?.firstOrNull()?.let {
                "${it.name()}:${it.arguments()?.take(50)}"
            } ?: ""
            val screenDiffCount = (previousScreenTexts as? Set<*>)?.size ?: 0
            val toolError = llmResponse.toolExecutionRequests?.firstOrNull()?.let { req ->
                val result = ToolRegistry.getInstance().getTool(req.name() ?: "")
                null // error tracked per-tool above; simplified here
            }
            val detection = stuckDetector.record(lastAction, lastScreenHash, screenDiffCount, null)
            if (detection != null) {
                when (detection.level) {
                    StuckDetector.RecoveryLevel.AUTO_KILL -> {
                        XLog.w(TAG, "StuckDetector AUTO_KILL at iteration $iterations: ${detection.signal.description}")
                        val status = tokenMonitor.getStatus()
                        callback.onComplete(
                            iterations,
                            "Task stopped: agent was stuck (${detection.signal.description}). " +
                            "Used ${status.formattedTokens} tokens (${status.formattedCost}).",
                            totalTokens,
                            actualModelName
                        )
                        return
                    }
                    else -> {
                        XLog.w(TAG, "StuckDetector ${detection.level} at iteration $iterations: ${detection.signal.description}")
                        messages.add(UserMessage.from(detection.recoveryHint))
                        if (VlmConfigRepository.isConfigured()) {
                            messages.add(UserMessage.from(
                                "[System Hint] If the ADB UI data seems incomplete or you cannot find the target element, " +
                                        "you MUST call analyze_screen_visual now to get visual guidance for the next step."
                            ))
                        }
                    }
                }
            }
            XLog.d(TAG, "Round:$iterations total=$totalTokens thisRound=${llmResponse.tokenUsage?.totalTokenCount()}")
        }

        if (cancelled.get()) {
            callback.onComplete(iterations, ClawApplication.instance.getString(R.string.agent_task_cancel), totalTokens, actualModelName)
        } else {
            callback.onError(iterations, RuntimeException(ClawApplication.instance.getString(R.string.agent_max_iterations, maxIterations)), totalTokens)
        }
    }

    override fun cancel() {
        cancelled.set(true)
        if (config.provider == LlmProvider.LOCAL) {
            // LiteRT native sendMessage is not interrupt-safe; let the current round yield
            // naturally, then surface Task cancelled after the client closes cleanly.
            XLog.i(TAG, "cancel: LOCAL task marked cancelled; waiting for current LiteRT round to finish safely")
            return
        }
        // Cloud/network-backed tasks can be aborted safely via thread interruption.
        if (::llmClient.isInitialized) {
            try {
                llmClient.close()
            } catch (e: Exception) {
                XLog.w(TAG, "cancel: failed to close active LLM client", e)
            }
        }
        taskFuture?.cancel(true)
        XLog.i(TAG, "cancel: flag set + thread interrupted")
    }

    override fun shutdown() {
        cancel()
        executor?.shutdownNow()
        if (::llmClient.isInitialized) {
            try {
                llmClient.close()
                XLog.i(TAG, "LlmClient closed on shutdown")
            } catch (e: Exception) {
                XLog.w(TAG, "LlmClient close error on shutdown", e)
            }
        }
    }

    override fun isRunning(): Boolean = running.get()
}
