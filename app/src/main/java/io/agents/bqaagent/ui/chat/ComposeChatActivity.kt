// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.ui.chat

import io.agents.bqaagent.AppCapabilityCoordinator
import io.agents.bqaagent.ServiceBindingState
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import io.agents.bqaagent.TaskEvent
import io.agents.bqaagent.adb.LocalAdbDeviceDriver
import io.agents.bqaagent.agent.llm.ModelConfigRepository
import io.agents.bqaagent.automation.ExternalAutomationContract
import io.agents.bqaagent.automation.ExternalAutomationEntrypoint
import io.agents.bqaagent.appViewModel
import io.agents.bqaagent.floating.FloatingCircleManager
import io.agents.bqaagent.recording.TaskRecordingCoordinator
import io.agents.bqaagent.ui.settings.LlmConfigActivity
import io.agents.bqaagent.ui.settings.SettingsActivity
import io.agents.bqaagent.utils.KVUtils
import io.agents.bqaagent.utils.XLog
import java.util.Locale
import java.util.concurrent.Executors

import android.Manifest
import androidx.activity.result.contract.ActivityResultContracts

/**
 * BQAAgent Chat Activity — Compose shell for the chat screen.
 *
 * Chat runtime ownership lives in [ChatSessionController].
 * This activity keeps lifecycle wiring, task flows, and sidebar/history UI state.
 */
class ComposeChatActivity : ComponentActivity() {

    companion object {
        private const val TAG = "ComposeChatActivity"
        private const val EXTRA_TASK = "task"
        private const val EXTRA_CHAT = "chat"
        private const val AUTOMATION_DUPLICATE_WINDOW_MS = 1_250L
    }

    // 麦克风权限申请
    private val recordAudioLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            Toast.makeText(this, "麦克风权限已授予，再次点击麦克风即可语音输入", Toast.LENGTH_SHORT)
                .show()
        } else {
            Toast.makeText(this, "麦克风权限被拒绝，无法使用语音输入", Toast.LENGTH_SHORT).show()
        }
    }

    // Image picker for user-assisted screenshot fallback
    private val userImagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) {
            appViewModel.provideUserImage(null)
            return@registerForActivityResult
        }
        var destFile: java.io.File? = null
        try {
            val mimeType = contentResolver.getType(uri)
            if (mimeType == null || !mimeType.startsWith("image/")) {
                Toast.makeText(
                    this,
                    "⚠️ Please select an image file (PNG, JPEG, WebP).",
                    Toast.LENGTH_LONG
                ).show()
                appViewModel.provideUserImage(null)
                return@registerForActivityResult
            }

            val fileSize = contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
            val maxSize = 20L * 1024 * 1024
            if (fileSize > maxSize) {
                val sizeMb = fileSize / (1024 * 1024)
                Toast.makeText(
                    this,
                    "⚠️ Image too large (${sizeMb}MB). Maximum is 20MB.",
                    Toast.LENGTH_LONG
                ).show()
                appViewModel.provideUserImage(null)
                return@registerForActivityResult
            }
            if (fileSize < 10L * 1024) {
                Toast.makeText(
                    this,
                    "⚠️ Image too small. Please upload a full-screen screenshot.",
                    Toast.LENGTH_LONG
                ).show()
                appViewModel.provideUserImage(null)
                return@registerForActivityResult
            }

            val uploadDir = getExternalFilesDir("user_uploads") ?: run {
                appViewModel.provideUserImage(null)
                return@registerForActivityResult
            }
            if (!uploadDir.exists()) uploadDir.mkdirs()
            val extension = when {
                mimeType.contains("jpeg") || mimeType.contains("jpg") -> "jpg"
                mimeType.contains("webp") -> "webp"
                mimeType.contains("png") -> "png"
                else -> "png"
            }
            destFile =
                java.io.File(uploadDir, "screenshot_${System.currentTimeMillis()}.$extension")
            contentResolver.openInputStream(uri)?.use { input ->
                destFile!!.outputStream().use { output -> input.copyTo(output) }
            }
            XLog.i(
                TAG,
                "User image saved to ${destFile!!.absolutePath} (${fileSize / 1024}KB, $mimeType)"
            )
            appViewModel.provideUserImage(destFile!!.absolutePath)
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to save user image", e)
            destFile?.delete()
            appViewModel.provideUserImage(null)
        }
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val conversationStore by lazy { ConversationStore(this) }

    // Compose state — observed by ChatScreen
    private val _messages = mutableStateListOf<ChatMessage>()
    private val _modelStatus = mutableStateOf("No model loaded")
    private val _isLocalModelActive = mutableStateOf(ModelConfigRepository.isLocalActive())
    private val _usesExplicitInputModes =
        mutableStateOf(ModelConfigRepository.activeModelUsesExplicitInputModes())
    private val _needsPermission = mutableStateOf(false)
    private val _isAwaitingReply = mutableStateOf(false)
    private val _isTaskRunning = mutableStateOf(false)
    private val _inputEnabled =
        mutableStateOf(true)    // False when model not ready (no task running)
    private val _conversations = mutableStateListOf<ChatHistoryManager.ConversationSummary>()
    private val _isDownloading = mutableStateOf(false)
    private val _downloadProgress = mutableStateOf(0)
    private val _voiceEnabled = mutableStateOf(KVUtils.isVoiceInputEnabled())

    // Session-level token tracking for chat mode
    private val _sessionTokens = mutableStateOf(0)
    private val _sessionCost = mutableStateOf(0.0)
    private var deferLocalChatBootstrapForAutoTask = false
    private var pendingExternalRequestId: String? = null
    private var pendingExternalReturnAction: String? = null
    private var pendingExternalReturnPackage: String? = null
    private var lastAutomationSignature: String? = null
    private var lastAutomationAtMs: Long = 0L
    private var lastSeenSkillCaptureMode: Boolean = KVUtils.isSkillCaptureModeEnabled()
    private val chatSessionController by lazy {
        ChatSessionController(
            activity = this,
            executor = executor,
            uiState = ChatSessionUiState(
                messages = _messages,
                modelStatus = _modelStatus,
                isAwaitingReply = _isAwaitingReply,
                inputEnabled = _inputEnabled,
                isDownloading = _isDownloading,
                downloadProgress = _downloadProgress,
                sessionTokens = _sessionTokens,
                sessionCost = _sessionCost,
            ),
            onPersistConversation = { saveChat() },
            onRefreshSidebarHistory = { refreshSidebarHistory() },
            isTaskRunning = { appViewModel.isTaskRunning() },
        )
    }

    private val taskFlowController by lazy {
        TaskFlowController(
            activity = this,
            executor = executor,
            appViewModel = appViewModel,
            chatSessionController = chatSessionController,
            currentConversationId = { conversationStore.currentConversationId },
            uiState = TaskFlowUiState(
                messages = _messages,
                modelStatus = _modelStatus,
                isAwaitingReply = _isAwaitingReply,
                isTaskRunning = _isTaskRunning,
            ),
            onPersistConversation = { saveChat() },
            onTaskSettled = { deferLocalChatBootstrapForAutoTask = false },
            onTaskTerminal = { sendExternalAutomationTerminalCallback(it) },
        )
    }

    private val activeTaskShellController by lazy {
        ActiveTaskShellController(appViewModel = appViewModel)
    }

    // Permission polling
    private val permHandler = Handler(Looper.getMainLooper())
    private val permPoller = object : Runnable {
        override fun run() {
            _needsPermission.value =
                AppCapabilityCoordinator.localAdbState(this@ComposeChatActivity) != ServiceBindingState.READY
            permHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)

        // Hide floating circle only when no task is running
        // (task running = keep floating pill visible for step/token status)
        try {
            if (!appViewModel.isTaskRunning()) {
                FloatingCircleManager.hide()
            } else {
                XLog.d(TAG, "onCreate: task running, keeping floating circle visible")
            }
        } catch (_: Exception) {
        }

        // Check for updates
        io.agents.bqaagent.utils.UpdateChecker.checkForUpdate(this)

        // Status bar color
        val themeColors = ThemeManager.getColors()
        window.statusBarColor = themeColors.toolbarBg

        // Build Compose colors from ThemeManager
        val composeColors = with(ThemeManager) { themeColors.toComposeColors() }

        setContent {
            val activeTasks by activeTaskShellController.activeTasks.collectAsState()
            val skillSaveOverlayState by taskFlowController.skillSaveOverlay

            Box(modifier = Modifier.fillMaxSize()) {
                ChatScreen(
                    messages = _messages.toList(),
                    modelStatus = _modelStatus.value,
                    needsPermission = _needsPermission.value,
                    isAwaitingReply = _isAwaitingReply.value,
                    isTaskRunning = _isTaskRunning.value,
                    inputEnabled = _inputEnabled.value,
                    isDownloading = _isDownloading.value,
                    downloadProgress = _downloadProgress.value,
                    isLocalModel = _isLocalModelActive.value,
                    usesExplicitInputModes = _usesExplicitInputModes.value,
                    sessionTokens = _sessionTokens.value,
                    sessionCost = _sessionCost.value,
                    onSendChat = { sendChat(it) },
                    onSendTask = { taskFlowController.sendTask(it) },
                    onSendUnified = { sendUnifiedInput(it) },
                    onStartMonitor = { target -> taskFlowController.startMonitor(target) },
                    onSendDirectMessage = { contact, app, message ->
                        taskFlowController.sendTask("send \"$message\" to $contact on $app")
                    },
                    onNewChat = { newChat() },
                    onOpenSettings = {
                        startActivity(
                            Intent(
                                this@ComposeChatActivity,
                                SettingsActivity::class.java
                            )
                        )
                    },
                    onOpenModels = {
                        startActivity(
                            Intent(
                                this@ComposeChatActivity,
                                LlmConfigActivity::class.java
                            )
                        )
                    },
                    onFixPermissions = {
                        startActivity(
                            Intent(
                                this@ComposeChatActivity,
                                SettingsActivity::class.java
                            )
                        )
                    },
                    onAttach = {
                        Toast.makeText(
                            this@ComposeChatActivity,
                            "Image upload coming soon",
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    conversations = _conversations.toList(),
                    onSelectConversation = { loadConversation(it) },
                    onDeleteConversation = { conv ->
                        val deleted = conversationStore.deleteConversation(conv)
                        XLog.i(
                            TAG,
                            "Delete conversation: ${conv.file.absolutePath} deleted=$deleted"
                        )
                        refreshSidebarHistory()
                    },
                    onRenameConversation = { conv, newName ->
                        val renamed = conversationStore.renameConversation(conv, newName)
                        XLog.i(
                            TAG,
                            "Rename conversation: '${conv.title}' → '$newName' renamed=$renamed"
                        )
                        refreshSidebarHistory()
                    },
                    activeTasks = activeTasks,
                    onStopTask = { contact ->
                        _isTaskRunning.value = appViewModel.isTaskRunning()
                        Toast.makeText(
                            this@ComposeChatActivity,
                            activeTaskShellController.stopTask(contact),
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    onStopAllTasks = {
                        _isAwaitingReply.value = false
                        _isTaskRunning.value = false
                        Toast.makeText(
                            this@ComposeChatActivity,
                            activeTaskShellController.stopAllTasks(),
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    onModelSwitch = { modelId, displayName -> switchModel(modelId, displayName) },
                    colors = composeColors,
                    voiceEnabled = _voiceEnabled.value,
                    // 新增语音权限申请回调
                    onRequestRecordPermission = {
                        recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    showUserImageUpload = taskFlowController.showUserImageUpload,
                    onUploadImage = { userImagePickerLauncher.launch("image/*") },
                    onSkipImageUpload = { appViewModel.provideUserImage(null) },
                    skillSaveState = skillSaveOverlayState,
                    onOpenSkillSaveDetail = { taskFlowController.showSkillSaveDetail() },
                    onDismissSkillSave = { taskFlowController.dismissSkillSave() },
                    onSkillSaveTitleChanged = { taskFlowController.updateSkillSaveTitle(it) },
                    onConfirmSkillSave = { taskFlowController.confirmSkillSave() },
                    onRetrySkillSave = { taskFlowController.retrySkillSave() },
                    onOpenRecording = { recordingId ->
                        io.agents.bqaagent.ui.recording.RecordingPlaybackActivity.start(
                            this@ComposeChatActivity, recordingId
                        )
                    },
                )

                SkillSaveOverlay(
                    state = if (skillSaveOverlayState is SkillSaveOverlayState.OfferSave) {
                        // OfferSave is rendered inline in the chat list footer, not as a modal.
                        SkillSaveOverlayState.Hidden
                    } else {
                        // ShowDetail / Saving / Success / Fail / SkillLimitReached / OfferReplay stay modal.
                        skillSaveOverlayState
                    },
                    colors = composeColors,
                    onOpenDetail = { taskFlowController.showSkillSaveDetail() },
                    onDismiss = { taskFlowController.dismissSkillSave() },
                    onTitleChanged = { taskFlowController.updateSkillSaveTitle(it) },
                    onConfirmSave = { taskFlowController.confirmSkillSave() },
                    onRetry = { taskFlowController.retrySkillSave() },
                    onOpenSkillManagement = { taskFlowController.openSkillManagementFromLimit() },
                )
            }
        }

        refreshSidebarHistory()

        // Restore last conversation if Activity was recreated (e.g., system killed it during a task)
        if (_messages.isEmpty()) {
            conversationStore.restoreLastConversation()?.let { restored ->
                syncSidebar(restored.conversations)
                if (restored.messages.isNotEmpty()) {
                    _messages.addAll(restored.messages)
                    XLog.i(
                        TAG,
                        "Restored ${restored.messages.size} messages from conversation ${restored.conversationId}"
                    )
                }
            }
        }

        deferLocalChatBootstrapForAutoTask = shouldDeferLocalChatBootstrap(intent)
        if (!deferLocalChatBootstrapForAutoTask) {
            chatSessionController.loadModelIfReady(
                conversationId = conversationStore.currentConversationId,
                visibleMessages = _messages.toList(),
            )
        }
        refreshModelModeState()

        // Release local LLM conversation before task starts so the agent can use the engine
        // (LiteRT-LM only supports 1 session at a time)
        appViewModel.onBeforeTask = {
            chatSessionController.releaseForTask()
        }

        // Debug: auto-trigger task from ADB intent
        // Usage: adb shell am start -n io.agents.bqaagent/.ui.chat.ComposeChatActivity --es task "open my camera"
        handleIntentAutomation(intent, initialDelayMs = 0)

    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deferLocalChatBootstrapForAutoTask = shouldDeferLocalChatBootstrap(intent)
        handleIntentAutomation(intent, initialDelayMs = 0)
    }

    override fun onResume() {
        super.onResume()
        // Recording tail-off waits for this flag so the return-to-chat moment is captured.
        TaskRecordingCoordinator.onChatResumed()
        _needsPermission.value =
            AppCapabilityCoordinator.localAdbState(this) != ServiceBindingState.READY
        refreshModelModeState()
        _isTaskRunning.value = appViewModel.isTaskRunning()
        _voiceEnabled.value = KVUtils.isVoiceInputEnabled()
        refreshSidebarHistory()
        notifySkillCaptureModeChangedIfAny()
        permHandler.removeCallbacks(permPoller)
        permHandler.postDelayed(permPoller, 1000)
        activeTaskShellController.onResume()
        if (!deferLocalChatBootstrapForAutoTask) {
            chatSessionController.onResume(
                conversationId = conversationStore.currentConversationId,
                visibleMessages = _messages.toList(),
            )
        }
    }

    override fun onPause() {
        super.onPause()
        TaskRecordingCoordinator.onChatPaused()
        saveChat()
        permHandler.removeCallbacks(permPoller)
        activeTaskShellController.onPause()
        chatSessionController.onPause(conversationStore.currentConversationId)
    }

    override fun onDestroy() {
        super.onDestroy()
        chatSessionController.onDestroy()
        executor.shutdown()
    }

    // ==================== CHAT ====================

    private fun sendChat(text: String) {
        chatSessionController.sendChat(text)
    }

    private fun sendUnifiedInput(text: String) {
        if (shouldRouteUnifiedInputToTask(text)) {
            taskFlowController.sendTask(text)
        } else {
            sendChat(text)
        }
    }

    /**
     * Minimal small-talk fast path. After normalization (lowercase, punctuation/whitespace stripped)
     * an exact match against this set routes to plain chat; everything else goes to the task pipeline.
     */
    private val smallTalkPhrases = setOf(
        "hi", "hello", "hey", "yo", "sup", "whatsup",
        "goodmorning", "goodafternoon", "goodevening", "goodnight",
        "howareyou", "thanks", "thankyou", "bye", "goodbye", "ok", "okay",
        "你好", "您好", "在吗", "在么", "在不在", "嗨", "哈喽",
        "早安", "早上好", "中午好", "下午好", "晚上好",
        "谢谢", "多谢", "再见", "拜拜"
    )

    private fun shouldRouteUnifiedInputToTask(text: String): Boolean {
        val normalized = text.trim().lowercase(Locale.ROOT)
            .replace(Regex("[\\p{Punct}\\p{Space}]+"), "")
        if (normalized.isEmpty()) return false
        // Route inversion: the unified entry is a task by default. The AgentLoop LLM distinguishes a
        // real device task from chat-only (finish status=not_a_task). Only an explicit small-talk
        // phrase takes the lightweight chat fast path, eliminating keyword false-negatives.
        return !smallTalkPhrases.contains(normalized)
    }

    private fun handleIntentAutomation(intent: Intent?, initialDelayMs: Long) {
        val taskText = intent?.getStringExtra(EXTRA_TASK)?.takeIf { it.isNotBlank() }
        val chatText = intent?.getStringExtra(EXTRA_CHAT)?.takeIf { it.isNotBlank() }
        val automationText = taskText ?: chatText ?: return
        val isTask = taskText != null
        val signature = "${if (isTask) "task" else "chat"}:${automationText.trim()}"
        val now = SystemClock.elapsedRealtime()
        if (signature == lastAutomationSignature && now - lastAutomationAtMs < AUTOMATION_DUPLICATE_WINDOW_MS) {
            XLog.w(TAG, "Ignoring duplicate automation intent: $automationText")
            ExternalAutomationContract.sendCallback(
                context = this,
                returnAction = intent?.getStringExtra(ExternalAutomationEntrypoint.EXTRA_EXTERNAL_RETURN_ACTION),
                requestId = intent?.getStringExtra(ExternalAutomationEntrypoint.EXTRA_EXTERNAL_REQUEST_ID),
                status = ExternalAutomationContract.STATUS_REJECTED,
                error = "Duplicate automation request ignored.",
                returnPackage = intent?.getStringExtra(ExternalAutomationEntrypoint.EXTRA_EXTERNAL_RETURN_PACKAGE),
                mode = if (isTask) ExternalAutomationContract.Mode.TASK else ExternalAutomationContract.Mode.CHAT,
            )
            return
        }
        lastAutomationSignature = signature
        lastAutomationAtMs = now
        captureExternalAutomationCallback(intent, isTask)
        LocalAdbDeviceDriver.invalidateScreenCache()

        XLog.i(
            TAG,
            if (isTask) "Auto-task from intent: $automationText" else "Auto-chat from intent: $automationText"
        )

        val handler = Handler(Looper.getMainLooper())
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (isTask) {
                    taskFlowController.sendTask(automationText)
                    return
                }
                if (!KVUtils.hasLlmConfig()) {
                    _messages.add(ChatMessage(ChatMessage.Role.USER, automationText))
                    _messages.add(
                        ChatMessage(
                            ChatMessage.Role.SYSTEM,
                            "Configure LLM in Settings first."
                        )
                    )
                    saveChat()
                    return
                }
                if (chatSessionController.isModelReady()) {
                    sendChat(automationText)
                } else {
                    handler.postDelayed(this, 1000)
                }
            }
        }, initialDelayMs)
    }

    private fun shouldDeferLocalChatBootstrap(intent: Intent?): Boolean {
        val taskText =
            intent?.getStringExtra(EXTRA_TASK)?.takeIf { it.isNotBlank() } ?: return false
        return taskText.isNotBlank() && ModelConfigRepository.isLocalActive()
    }

    private fun captureExternalAutomationCallback(intent: Intent?, isTask: Boolean) {
        pendingExternalRequestId = null
        pendingExternalReturnAction = null
        pendingExternalReturnPackage = null
        if (!isTask) return
        pendingExternalRequestId =
            intent?.getStringExtra(ExternalAutomationEntrypoint.EXTRA_EXTERNAL_REQUEST_ID)
        pendingExternalReturnAction =
            intent?.getStringExtra(ExternalAutomationEntrypoint.EXTRA_EXTERNAL_RETURN_ACTION)
        pendingExternalReturnPackage =
            intent?.getStringExtra(ExternalAutomationEntrypoint.EXTRA_EXTERNAL_RETURN_PACKAGE)
    }

    private fun sendExternalAutomationTerminalCallback(event: TaskEvent) {
        val returnAction = pendingExternalReturnAction ?: return
        val requestId = pendingExternalRequestId
        val returnPackage = pendingExternalReturnPackage
        val status: String
        var result: String? = null
        var error: String? = null
        when (event) {
            is TaskEvent.Completed -> {
                status = ExternalAutomationContract.STATUS_COMPLETED
                result = event.answer
            }

            is TaskEvent.Failed -> {
                status = ExternalAutomationContract.STATUS_FAILED
                error = event.error
            }

            is TaskEvent.Cancelled -> {
                status = ExternalAutomationContract.STATUS_CANCELLED
                error = "Task cancelled."
            }

            is TaskEvent.Stopped -> {
                status = ExternalAutomationContract.STATUS_STOPPED
                error = event.message
            }

            else -> return
        }
        ExternalAutomationContract.sendCallback(
            context = this,
            returnAction = returnAction,
            requestId = requestId,
            status = status,
            result = result,
            error = error,
            returnPackage = returnPackage,
            mode = ExternalAutomationContract.Mode.TASK,
        )
        pendingExternalRequestId = null
        pendingExternalReturnAction = null
        pendingExternalReturnPackage = null
    }

    private fun syncTaskAgentConfig() {
        if (!appViewModel.updateAgentConfig()) {
            XLog.w(TAG, "syncTaskAgentConfig: failed to update task agent config")
        }
    }

    private fun switchModel(modelId: String, displayName: String) {
        chatSessionController.switchModel(modelId, displayName)
        refreshModelModeState()
        if (modelId != "NONE") {
            syncTaskAgentConfig()
        }
        XLog.i(TAG, "Model switched to: $modelId ($displayName)")
    }

    private fun refreshModelModeState() {
        val snapshot = ModelConfigRepository.snapshot()
        _isLocalModelActive.value = snapshot.isLocalActive()
        _usesExplicitInputModes.value = snapshot.usesExplicitInputModes()
    }

    private fun newChat() {
        val session =
            conversationStore.startNewConversation(_messages, currentConversationModelName())
        syncSidebar(session.conversations)
        _messages.clear()
        _sessionTokens.value = 0
        _sessionCost.value = 0.0
        _isAwaitingReply.value = false
        _isTaskRunning.value = false
        taskFlowController.clearSkillSavePrompt()
        chatSessionController.startNewConversationRuntime()
    }

    private fun loadConversation(conv: ChatHistoryManager.ConversationSummary) {
        val session =
            conversationStore.openConversation(conv, _messages, currentConversationModelName())
        syncSidebar(session.conversations)
        _messages.clear()
        _messages.addAll(session.messages)
        _isAwaitingReply.value = false
        _isTaskRunning.value = false
        taskFlowController.clearSkillSavePrompt()
        chatSessionController.restoreConversationRuntime(session.conversationId, session.messages)
    }

    private fun saveChat() {
        syncSidebar(conversationStore.saveCurrent(_messages, currentConversationModelName()))
    }

    private fun notifySkillCaptureModeChangedIfAny() {
        val current = KVUtils.isSkillCaptureModeEnabled()
        if (current == lastSeenSkillCaptureMode) return
        lastSeenSkillCaptureMode = current
        if (current) {
            _messages.add(
                ChatMessage(
                    ChatMessage.Role.SYSTEM,
                    "Skill Capture Mode enabled: tasks run independently without chat history and can be saved as skills. Please describe each task completely in one message."
                )
            )
            saveChat()
        }
    }

    private fun refreshSidebarHistory() {
        syncSidebar(conversationStore.refreshSidebar())
    }

    private fun syncSidebar(convos: List<ChatHistoryManager.ConversationSummary>) {
        _conversations.clear()
        _conversations.addAll(convos)
    }

    private fun currentConversationModelName(): String {
        val config = ModelConfigRepository.snapshot()
        return if (config.isLocalActive()) {
            config.local.displayName.ifBlank {
                KVUtils.getLocalModelPath().substringAfterLast('/').substringBeforeLast('.')
            }
        } else {
            config.activeCloud.modelName
        }
    }
}
