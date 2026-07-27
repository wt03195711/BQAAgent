// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.ui.chat

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.snapshots.SnapshotStateList
import io.agents.bqaagent.AppCapabilityCoordinator
import io.agents.bqaagent.AppViewModel
import io.agents.bqaagent.ServiceBindingState
import io.agents.bqaagent.TaskEvent
import io.agents.bqaagent.agent.DirectDeviceDataGuard
import io.agents.bqaagent.agent.PipelineRouter
import io.agents.bqaagent.agent.TaskPromptEnvelope
import io.agents.bqaagent.agent.llm.ModelConfigRepository
import io.agents.bqaagent.service.ForegroundService
import io.agents.bqaagent.service.AutoReplyManager
import io.agents.bqaagent.adb.LocalAdbAutomation
import io.agents.bqaagent.tool.ToolRegistry
import io.agents.bqaagent.ui.settings.SettingsActivity
import io.agents.bqaagent.utils.KVUtils
import io.agents.bqaagent.utils.XLog
import java.util.Locale
import java.util.concurrent.ExecutorService

data class TaskFlowUiState(
    val messages: SnapshotStateList<ChatMessage>,
    val modelStatus: MutableState<String>,
    val isAwaitingReply: MutableState<Boolean>,
    val isTaskRunning: MutableState<Boolean>,
    val showUserImageUpload: MutableState<Boolean> = androidx.compose.runtime.mutableStateOf(false),
    val screenshotBlockedIntent: MutableState<String> = androidx.compose.runtime.mutableStateOf(""),
)

/**
 * Owns task-mode send flow, typed TaskEvent rendering, and monitor start wiring.
 *
 * ComposeChatActivity keeps the shell; this controller keeps task-specific behavior.
 */
class TaskFlowController(
    private val activity: ComponentActivity,
    private val executor: ExecutorService,
    private val appViewModel: AppViewModel,
    private val chatSessionController: ChatSessionController,
    private val currentConversationId: () -> String,
    private val uiState: TaskFlowUiState,
    private val onPersistConversation: () -> Unit,
    private val onTaskSettled: (() -> Unit)? = null,
    private val onTaskTerminal: ((TaskEvent) -> Unit)? = null,
) {

    companion object {
        private const val TAG = "TaskFlowController"
    }

    val showUserImageUpload: Boolean get() = uiState.showUserImageUpload.value

    private var sendTaskRetryCount = 0
    private var lastMonitorStatusNote: String? = null
    private val pipelineRouter = PipelineRouter(activity)
    private var activeToolGroupIndex: Int? = null
    private val loopStartedAt = mutableMapOf<Int, Long>()
    private var lastTotalTokens = 0
    private var lastEstimatedCostUsd = 0.0

    fun sendTask(text: String) {
        if (appViewModel.isTaskRunning()) {
            addSystem("Another task is still running. Stop it first.")
            onTaskTerminal?.invoke(TaskEvent.Failed("Another task is still running. Stop it first."))
            return
        }

        if (ModelConfigRepository.snapshot().isLocalActive() && isLikelyMonitorRequest(text)) {
            addUser(text)
            addSystem("Local mode starts monitoring from the Background card. Open Background, choose the app/contact, then tap Start Monitoring.")
            onTaskTerminal?.invoke(TaskEvent.Failed("Local mode starts monitoring from the Background card."))
            return
        }

        DirectDeviceDataGuard.deterministicToolCall(text)?.let { directTool ->
            XLog.i(TAG, "sendTask: executing deterministic direct tool before Local ADB gates")
            executeDirectToolTask(text, directTool)
            return
        }

        when (AppCapabilityCoordinator.localAdbState(activity)) {
            ServiceBindingState.DISABLED -> {
                val directTool = DirectDeviceDataGuard.deterministicToolCall(text)
                if (directTool != null) {
                    XLog.i(TAG, "sendTask: executing non-interactive direct tool without Local ADB")
                    executeDirectToolTask(text, directTool)
                    return
                }
                if (canRunWithoutLocalAdb(text)) {
                    XLog.i(TAG, "sendTask: allowing non-interactive task without Local ADB")
                } else {
                Toast.makeText(activity, "Enable Wireless debugging and pair Local ADB to run tasks", Toast.LENGTH_LONG).show()
                addSystem("Task mode needs Local ADB. Opening Wireless debugging settings...")
                openSettings()
                sendTaskRetryCount = 0
                onTaskTerminal?.invoke(TaskEvent.Failed("Local ADB is required for this task."))
                return
                }
            }
            ServiceBindingState.CONNECTING -> {
                val directTool = DirectDeviceDataGuard.deterministicToolCall(text)
                if (directTool != null) {
                    XLog.i(TAG, "sendTask: executing non-interactive direct tool while Local ADB connects")
                    executeDirectToolTask(text, directTool)
                    return
                }
                if (canRunWithoutLocalAdb(text)) {
                    XLog.i(TAG, "sendTask: allowing non-interactive task while Local ADB connects")
                } else {
                if (sendTaskRetryCount >= 1) {
                    Toast.makeText(activity, "Local ADB did not connect. reconnect Local ADB and try again.", Toast.LENGTH_LONG).show()
                    addSystem("Local ADB didn't connect. reconnect Local ADB and grant permission.")
                    openSettings()
                    sendTaskRetryCount = 0
                    onTaskTerminal?.invoke(TaskEvent.Failed("Local ADB did not connect."))
                    return
                }
                sendTaskRetryCount++
                addSystem("Local ADB connecting, please wait...")
                executor.submit {
                    val connected = LocalAdbAutomation.awaitReady(5000)
                    activity.runOnUiThread {
                        if (connected) {
                            sendTask(text)
                        } else {
                            Toast.makeText(activity, "Local ADB didn't connect", Toast.LENGTH_LONG).show()
                            addSystem("Local ADB didn't connect. Enable Wireless debugging, pair Local ADB, then retry.")
                            sendTaskRetryCount = 0
                            onTaskTerminal?.invoke(TaskEvent.Failed("Local ADB did not connect."))
                        }
                    }
                }
                return
                }
            }
            ServiceBindingState.DEGRADED -> {
                val directTool = DirectDeviceDataGuard.deterministicToolCall(text)
                if (directTool != null) {
                    XLog.i(TAG, "sendTask: executing non-interactive direct tool while Local ADB is degraded")
                    executeDirectToolTask(text, directTool)
                    return
                }
                if (canRunWithoutLocalAdb(text)) {
                    XLog.i(TAG, "sendTask: allowing non-interactive task while Local ADB is degraded")
                } else {
                    Toast.makeText(activity, "Local ADB disconnected. reconnect Local ADB.", Toast.LENGTH_LONG).show()
                    addSystem("Local ADB disconnected. reconnect Local ADB and grant permission.")
                    openSettings()
                    sendTaskRetryCount = 0
                    onTaskTerminal?.invoke(TaskEvent.Failed("Local ADB is disconnected."))
                    return
                }
            }
            ServiceBindingState.READY -> Unit
        }
        sendTaskRetryCount = 0

        ensureNotificationPermission()
        uiState.isAwaitingReply.value = false
        uiState.isTaskRunning.value = true

        if (!KVUtils.hasLlmConfig()) {
            uiState.isTaskRunning.value = false
            Toast.makeText(activity, "Configure LLM in Settings first", Toast.LENGTH_LONG).show()
            onTaskTerminal?.invoke(TaskEvent.Failed("Configure LLM in Settings first."))
            return
        }

        val agentPromptOverride = buildAgentPromptOverride(text)
        resetStepTimeline()
        addUser(text)
        uiState.isAwaitingReply.value = true
        uiState.isTaskRunning.value = true
        XLog.i(TAG, "sendTask: isProcessing=TRUE")
        uiState.messages.add(ChatMessage(ChatMessage.Role.ASSISTANT, "..."))

        val taskId = "task_${System.currentTimeMillis()}"

        executor.submit {
            chatSessionController.prepareForTaskStart()

            activity.runOnUiThread {
                try {
                    appViewModel.startTask(text, taskId, agentPromptOverride = agentPromptOverride) { event ->
                        activity.runOnUiThread { handleTaskEvent(event) }
                    }
                } catch (e: Exception) {
                    XLog.e(TAG, "sendTask failed: ${e.message}", e)
                    addSystem("Error: ${e.message}")
                    cleanupAfterTask()
                }
            }
        }
    }

    private fun executeDirectToolTask(text: String, toolCall: DirectDeviceDataGuard.DeterministicToolCall) {
        ensureNotificationPermission()
        resetStepTimeline()
        addUser(text)
        uiState.isAwaitingReply.value = true
        uiState.isTaskRunning.value = true
        uiState.messages.add(ChatMessage(ChatMessage.Role.ASSISTANT, "..."))

        executor.submit {
            val displayName = ToolRegistry.getInstance().getDisplayName(toolCall.toolName)
            val startedAt = System.currentTimeMillis()
            activity.runOnUiThread {
                removeTypingIndicator()
                addRunningToolStep(displayName, startedAt)
            }
            try {
                val result = ToolRegistry.getInstance().executeTool(toolCall.toolName, toolCall.params)
                val completedAt = System.currentTimeMillis()
                activity.runOnUiThread {
                    completeToolStep(
                        toolName = displayName,
                        success = result.isSuccess,
                        detail = result.data ?: result.error ?: "",
                        completedAt = completedAt,
                    )
                    val answer = result.data ?: result.error ?: "Done."
                    replaceTypingIndicator(answer)
                    onTaskTerminal?.invoke(TaskEvent.Completed(answer))
                    cleanupAfterTask()
                }
            } catch (e: Exception) {
                XLog.e(TAG, "executeDirectToolTask failed: ${e.message}", e)
                val completedAt = System.currentTimeMillis()
                activity.runOnUiThread {
                    completeToolStep(
                        toolName = displayName,
                        success = false,
                        detail = e.message ?: "Direct tool failed",
                        completedAt = completedAt,
                    )
                    replaceTypingIndicator("Error: ${e.message}")
                    onTaskTerminal?.invoke(TaskEvent.Failed(e.message ?: "Direct tool failed"))
                    cleanupAfterTask()
                }
            }
        }
    }

    private fun canRunWithoutLocalAdb(text: String): Boolean {
        if (DirectDeviceDataGuard.matchesNonInteractiveDeviceDataTask(text)) {
            return true
        }
        return when (pipelineRouter.route(text)) {
            is PipelineRouter.Route.DirectIntent -> true
            else -> false
        }
    }

    fun handleMonitorTask(text: String) {
        val target = MonitorTargetParser.fromTaskText(text)
        if (target == null) {
            addUser(text)
            addSystem("Could not figure out who to monitor. Try: \"Monitor Mom on WhatsApp\"")
            return
        }

        startMonitor(target, typedInput = text)
    }

    fun startMonitor(target: MonitorTargetSpec, typedInput: String? = null) {
        val trimmedLabel = target.label.trim()
        if (trimmedLabel.isEmpty()) {
            addSystem("Could not figure out who to monitor. Try: \"Monitor Mom on WhatsApp\"")
            return
        }

        typedInput?.let { addUser(it) }
        val missing = AppCapabilityCoordinator.missingMonitorRequirements(activity)
        if (missing.isNotEmpty()) {
            Toast.makeText(
                activity,
                "Enable ${missing.joinToString(" & ") { it.label }} in Settings first",
                Toast.LENGTH_LONG
            ).show()
            openSettings()
            onTaskTerminal?.invoke(TaskEvent.Failed("Missing required permissions for monitoring."))
            return
        }

        val contact = trimmedLabel
        val app = target.app
        uiState.isAwaitingReply.value = false
        uiState.isTaskRunning.value = false
        addSystem("Setting up auto-reply for $contact on $app...")

        val autoReplyManager = AutoReplyManager.getInstance()
        autoReplyManager.addTarget(contact, app)
        autoReplyManager.setEnabled(true)
        XLog.i(TAG, "startMonitor: enabled auto-reply for '${target.displayLabel}'")

        Handler(Looper.getMainLooper()).postDelayed({
            uiState.isAwaitingReply.value = false
            uiState.isTaskRunning.value = false
            addSystem("Auto-reply is now active for ${target.displayLabel}.\nMonitoring in background. You can stop anytime from the bar above.")
            XLog.i(TAG, "startMonitor: monitor active, staying in BQAAgent")
        }, 1500)
    }

    private fun handleTaskEvent(event: TaskEvent) {
        try {
            when (event) {
                is TaskEvent.Completed -> {
                    replaceTypingIndicator(event.answer, event.modelName)
                    onTaskTerminal?.invoke(event)
                    cleanupAfterTask()
                    checkAutoReplyConfirmation()
                }
                is TaskEvent.Failed -> {
                    replaceTypingIndicator("Error: ${event.error}")
                    onTaskTerminal?.invoke(event)
                    cleanupAfterTask()
                }
                is TaskEvent.Cancelled -> {
                    removeTypingIndicator()
                    onTaskTerminal?.invoke(event)
                    cleanupAfterTask()
                }
                is TaskEvent.Blocked -> {
                    replaceTypingIndicator("Blocked by system dialog.")
                    onTaskTerminal?.invoke(event)
                    cleanupAfterTask()
                }
                is TaskEvent.ScreenshotBlocked -> {
                    uiState.isAwaitingReply.value = false
                    uiState.screenshotBlockedIntent.value = event.intent
                    uiState.showUserImageUpload.value = true
                    addSystem("⚠️ Unable to capture screen automatically. Please upload a screenshot to continue.")
                }
                is TaskEvent.UserImageProvided -> {
                    uiState.showUserImageUpload.value = false
                    uiState.screenshotBlockedIntent.value = ""
                    if (event.imagePath != null) {
                        addSystem("📷 Screenshot received, analyzing...")
                    } else {
                        addSystem("⏭️ Skipped screenshot upload.")
                    }
                }
                is TaskEvent.ToolAction -> {
                    uiState.isAwaitingReply.value = false
                    uiState.isTaskRunning.value = true
                    if (!event.toolName.contains("Finish", ignoreCase = true)) {
                        removeTypingIndicator()
                        addRunningToolStep(event.toolName, System.currentTimeMillis())
                    }
                }
                is TaskEvent.ToolResult -> {
                    uiState.isAwaitingReply.value = false
                    uiState.isTaskRunning.value = true
                    if (event.toolName.contains("Analyze User Image", ignoreCase = true)) {
                        val idx = uiState.messages.indexOfLast {
                            it.role == ChatMessage.Role.SYSTEM && it.content.contains("Screenshot received")
                        }
                        if (idx >= 0) {
                            uiState.messages[idx] = if (event.success) {
                                ChatMessage(ChatMessage.Role.SYSTEM, "✅ Screenshot analyzed successfully, continuing task...")
                            } else {
                                ChatMessage(ChatMessage.Role.SYSTEM, "❌ Screenshot analysis failed: ${event.detail.take(80)}")
                            }
                        }
                    }
                    if (!event.toolName.contains("Finish", ignoreCase = true)) {
                        completeToolStep(
                            toolName = event.toolName,
                            success = event.success,
                            detail = event.detail,
                            completedAt = System.currentTimeMillis(),
                        )
                    }
                }
                is TaskEvent.Response -> {
                    uiState.isAwaitingReply.value = false
                    replaceTypingIndicator(event.text)
                }
                is TaskEvent.Progress -> {
                    uiState.isAwaitingReply.value = false
                    uiState.isTaskRunning.value = true
                    addSystem(event.description)
                }
                is TaskEvent.LoopStart -> {
                    uiState.isAwaitingReply.value = false
                    uiState.isTaskRunning.value = true
                    loopStartedAt[event.round] = System.currentTimeMillis()
                }
                is TaskEvent.TokenUpdate -> {
                    uiState.isAwaitingReply.value = false
                    uiState.isTaskRunning.value = true
                    removeTypingIndicator()
                    addLlmStep(event, System.currentTimeMillis())
                }
                is TaskEvent.Thinking -> Unit
            }
        } catch (e: Exception) {
            XLog.w(TAG, "handleTaskEvent error", e)
        }
    }

    private fun replaceTypingIndicator(text: String, actualModelName: String? = null) {
        val modelTag = actualModelName
            ?: uiState.modelStatus.value.removePrefix("● ").split(" ·").firstOrNull()?.trim()
            ?: ""
        val idx = uiState.messages.indexOfLast { it.role == ChatMessage.Role.ASSISTANT && it.content == "..." }
        if (idx >= 0) {
            uiState.messages[idx] = ChatMessage(ChatMessage.Role.ASSISTANT, text, modelName = modelTag)
        } else {
            uiState.messages.add(ChatMessage(ChatMessage.Role.ASSISTANT, text, modelName = modelTag))
        }
        onPersistConversation()
    }

    private fun removeTypingIndicator() {
        val idx = uiState.messages.indexOfLast { it.role == ChatMessage.Role.ASSISTANT && it.content == "..." }
        if (idx >= 0) uiState.messages.removeAt(idx)
    }

    private fun cleanupAfterTask() {
        XLog.i(TAG, "cleanupAfterTask: isProcessing=FALSE")
        uiState.isAwaitingReply.value = false
        uiState.isTaskRunning.value = false
        appViewModel.clearTaskCallback()
        activeToolGroupIndex = null
        loopStartedAt.clear()
        onTaskSettled?.invoke()
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                chatSessionController.loadModelIfReady(
                    conversationId = currentConversationId(),
                    visibleMessages = uiState.messages.toList(),
                )
            } catch (e: Exception) {
                XLog.e(TAG, "cleanupAfterTask: loadModel error", e)
            }
        }, 500)
    }

    private fun checkAutoReplyConfirmation() {
        val autoReplyManager = AutoReplyManager.getInstance()
        if (!autoReplyManager.isEnabled) {
            lastMonitorStatusNote = null
            return
        }
        val contacts = autoReplyManager.monitoredContacts.joinToString(", ")
        if (contacts.isBlank()) {
            lastMonitorStatusNote = null
            return
        }
        val note = "Auto-reply active for $contacts.\nMonitoring in background. Stop from the bar above."
        if (note == lastMonitorStatusNote) return
        addSystem(note)
        lastMonitorStatusNote = note
        XLog.i(TAG, "checkAutoReplyConfirmation: monitor active, staying in BQAAgent")
    }

    private fun ensureNotificationPermission() {
        if (!AppCapabilityCoordinator.isNotificationPermissionGranted(activity)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }

    private fun addUser(text: String) {
        uiState.messages.add(ChatMessage(ChatMessage.Role.USER, text))
    }

    private fun addSystem(text: String) {
        uiState.messages.add(ChatMessage(ChatMessage.Role.SYSTEM, text))
    }

    private fun resetStepTimeline() {
        activeToolGroupIndex = null
        loopStartedAt.clear()
        lastTotalTokens = 0
        lastEstimatedCostUsd = 0.0
    }

    private fun addRunningToolStep(toolName: String, startedAt: Long) {
        updateToolGroup { steps ->
            steps + ToolStep(
                toolName = toolName,
                summary = "Running",
                success = false,
                startedAt = startedAt,
            )
        }
    }

    private fun completeToolStep(
        toolName: String,
        success: Boolean,
        detail: String,
        completedAt: Long,
    ) {
        updateToolGroup { steps ->
            val index = steps.indexOfLast {
                !it.isLlmCall && it.toolName == toolName && it.completedAt == null
            }
            val fallbackStartedAt = completedAt
            val summary = if (success) "Done" else detail.ifBlank { "Failed" }.take(120)
            if (index >= 0) {
                steps.mapIndexed { stepIndex, step ->
                    if (stepIndex == index) {
                        step.copy(
                            summary = summary,
                            success = success,
                            completedAt = completedAt,
                            durationMs = (completedAt - step.startedAt).coerceAtLeast(0L),
                        )
                    } else {
                        step
                    }
                }
            } else {
                steps + ToolStep(
                    toolName = toolName,
                    summary = summary,
                    success = success,
                    startedAt = fallbackStartedAt,
                    completedAt = completedAt,
                    durationMs = 0L,
                )
            }
        }
    }

    private fun addLlmStep(event: TaskEvent.TokenUpdate, completedAt: Long) {
        val startedAt = loopStartedAt[event.step] ?: completedAt
        val deltaTokens = if (event.totalTokens > 0) {
            (event.totalTokens - lastTotalTokens).coerceAtLeast(0)
        } else {
            0
        }
        val deltaCost = if (event.estimatedCostUsd > 0.0) {
            (event.estimatedCostUsd - lastEstimatedCostUsd).coerceAtLeast(0.0)
        } else {
            0.0
        }
        if (event.totalTokens > 0) lastTotalTokens = event.totalTokens
        if (event.estimatedCostUsd > 0.0) lastEstimatedCostUsd = event.estimatedCostUsd

        updateToolGroup { steps ->
            steps + ToolStep(
                toolName = "LLM Call",
                summary = "Reasoning step ${event.step}",
                success = true,
                startedAt = startedAt,
                completedAt = completedAt,
                durationMs = (completedAt - startedAt).coerceAtLeast(0L),
                tokenCount = deltaTokens.takeIf { it > 0 },
                tokenText = when {
                    deltaTokens > 0 -> formatTokenCount(deltaTokens)
                    event.formattedTokens.isNotBlank() -> event.formattedTokens
                    else -> null
                },
                costText = deltaCost.takeIf { it > 0.0 }?.let { formatCost(it) },
                isLlmCall = true,
            )
        }
    }

    private fun updateToolGroup(transform: (List<ToolStep>) -> List<ToolStep>) {
        val index = ensureToolGroupIndex()
        val existing = uiState.messages[index]
        val updatedSteps = transform(existing.toolSteps.orEmpty())
        uiState.messages[index] = existing.copy(
            content = buildToolGroupContent(updatedSteps),
            toolSteps = updatedSteps,
        )
        onPersistConversation()
    }

    private fun ensureToolGroupIndex(): Int {
        activeToolGroupIndex?.let { index ->
            if (index in uiState.messages.indices && uiState.messages[index].role == ChatMessage.Role.TOOL_GROUP) {
                return index
            }
        }
        val insertIndex = uiState.messages.size
        uiState.messages.add(
            ChatMessage(
                role = ChatMessage.Role.TOOL_GROUP,
                content = "",
                toolSteps = emptyList(),
            )
        )
        activeToolGroupIndex = insertIndex
        return insertIndex
    }

    private fun buildToolGroupContent(steps: List<ToolStep>): String {
        return steps.joinToString("\n") { step ->
            val status = if (step.success) "✓" else if (step.completedAt == null) "…" else "✕"
            val duration = step.durationMs?.toString().orEmpty()
            val completed = step.completedAt?.toString().orEmpty()
            val tokens = step.tokenCount?.toString().orEmpty()
            val tokenText = step.tokenText.orEmpty()
            val costText = step.costText.orEmpty()
            "- $status ${step.toolName} | started=${step.startedAt} | completed=$completed | durationMs=$duration | tokens=$tokens | tokenText=$tokenText | cost=$costText | llm=${step.isLlmCall} | ${step.summary}"
        }
    }

    private fun formatTokenCount(tokens: Int): String {
        return when {
            tokens < 1000 -> tokens.toString()
            tokens < 1_000_000 -> String.format(Locale.US, "%.1fK", tokens / 1000.0)
            else -> String.format(Locale.US, "%.1fM", tokens / 1_000_000.0)
        }
    }

    private fun formatCost(cost: Double): String {
        return if (cost < 0.01) {
            String.format(Locale.US, "$%.4f", cost)
        } else {
            String.format(Locale.US, "$%.2f", cost)
        }
    }

    private fun openSettings() {
        activity.startActivity(Intent(activity, SettingsActivity::class.java))
    }

    private fun buildAgentPromptOverride(rawTask: String): String? {
        if (ModelConfigRepository.snapshot().isLocalActive()) {
            return null
        }

        val historyLines = CloudContextHandoffFormatter.conversationLines(uiState.messages)
        val backgroundStatus = buildBackgroundStatusContext()

        return TaskPromptEnvelope.build(
            chatHistoryLines = historyLines,
            currentRequest = rawTask,
            backgroundState = backgroundStatus,
        )
    }

    private fun buildBackgroundStatusContext(): String? {
        val autoReplyManager = AutoReplyManager.getInstance()
        if (!autoReplyManager.isEnabled) return null

        val contacts = autoReplyManager.monitoredContacts.toList()
        if (contacts.isEmpty()) return null

        return buildString {
            append("Background monitor active for: ")
            append(contacts.joinToString(", "))
            append('.')
        }
    }

    private fun isLikelyMonitorRequest(text: String): Boolean {
        val lower = text.lowercase()
        val mentionsMonitor = lower.contains("monitor") ||
            lower.contains("auto-reply") ||
            lower.contains("auto reply") ||
            lower.contains("autoreply")
        val looksLikeWatchMessages = lower.contains("watch") &&
            (lower.contains("message") || lower.contains("messages") || lower.contains("reply"))
        return mentionsMonitor || looksLikeWatchMessages
    }
}
