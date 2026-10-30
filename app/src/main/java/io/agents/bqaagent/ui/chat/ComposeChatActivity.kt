// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.ui.chat

import io.agents.bqaagent.AppCapabilityCoordinator
import io.agents.bqaagent.AppRequirement
import io.agents.bqaagent.ServiceBindingState
import io.agents.bqaagent.R
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
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
import android.app.KeyguardManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import io.agents.bqaagent.agent.TaskOcrExtractor
import io.agents.bqaagent.agent.OcrExtractionError
import io.agents.bqaagent.agent.VlmConfigRepository
import io.agents.bqaagent.utils.OcrImageHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    private var keyguardLock: KeyguardManager.KeyguardLock? = null

    // Microphone permission reques
    private val recordAudioLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            Toast.makeText(this, getString(R.string.chat_voice_perm_granted), Toast.LENGTH_SHORT)
                .show()
        } else {
            Toast.makeText(this, getString(R.string.chat_voice_perm_denied), Toast.LENGTH_SHORT).show()
        }
    }

    // Camera permission request for OCR
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchOcrCamera()
        } else {
            Toast.makeText(this, getString(R.string.ocr_camera_perm_denied), Toast.LENGTH_SHORT).show()
        }
    }

    // Camera launcher for OCR — takes a photo and saves to ocr_uploads
    private var ocrCameraFile: java.io.File? = null
    private val ocrCameraLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && ocrCameraFile != null && ocrCameraFile!!.exists()) {
            processOcrImage(android.net.Uri.fromFile(ocrCameraFile))
        } else {
            _ocrLoading.value = false
            ocrCameraFile?.delete()
            ocrCameraFile = null
        }
    }

    // Gallery picker for OCR
    private val ocrGalleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) {
            _ocrLoading.value = false
            return@registerForActivityResult
        }
        processOcrImage(uri)
    }

    // OCR state
    private val _ocrLoading = mutableStateOf(false)
    private val _ocrExtractedText = mutableStateOf<String?>(null)
    private val _showOcrSheet = mutableStateOf(false)
    private val _showMonitorGuide = mutableStateOf(false)
    private val activityScope = CoroutineScope(Dispatchers.Main)

    // Notification permission requested from the monitor permission guide. The sheet itself
    // polls capabilities every 800ms, so a grant is picked up without extra wiring here.
    private val monitorNotifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        XLog.i(TAG, "monitor guide notification permission result: granted=$granted")
    }

    private fun launchOcrCamera() {
        val ocrDir = getExternalFilesDir("ocr_uploads")
        if (ocrDir == null) {
            Toast.makeText(this, getString(R.string.ocr_storage_unavailable), Toast.LENGTH_SHORT).show()
            _ocrLoading.value = false
            return
        }
        try {
            if (!ocrDir.exists()) ocrDir.mkdirs()
            val file = java.io.File(ocrDir, "ocr_cam_${System.currentTimeMillis()}.jpg")
            ocrCameraFile = file
            val uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file
            )
            ocrCameraLauncher.launch(uri)
        } catch (e: Exception) {
            XLog.e(TAG, "launchOcrCamera failed", e)
            Toast.makeText(this, getString(R.string.ocr_camera_failed), Toast.LENGTH_SHORT).show()
            _ocrLoading.value = false
        }
    }

    private fun processOcrImage(uri: android.net.Uri) {
        _ocrLoading.value = true
        activityScope.launch {
            val result = withContext(Dispatchers.IO) {
                OcrImageHelper.prepareImage(uri, this@ComposeChatActivity)
            }
            val path = result.getOrNull()
            if (path == null) {
                Toast.makeText(this@ComposeChatActivity, getString(R.string.ocr_image_error), Toast.LENGTH_SHORT).show()
                _ocrLoading.value = false
                return@launch
            }

            val config = VlmConfigRepository.load()
            if (!config.isConfigured) {
                Toast.makeText(this@ComposeChatActivity, getString(R.string.ocr_vlm_not_configured), Toast.LENGTH_LONG).show()
                _ocrLoading.value = false
                return@launch
            }

            val extractionResult = TaskOcrExtractor.extractTaskFromImage(path, config)
            _ocrLoading.value = false
            extractionResult.fold(
                onSuccess = { extracted ->
                    _ocrExtractedText.value = extracted
                    withContext(Dispatchers.IO) {
                        OcrImageHelper.cleanupOldImages(this@ComposeChatActivity)
                    }
                },
                onFailure = { error ->
                    val msgRes = (error as? OcrExtractionError)?.userMessageRes ?: R.string.ocr_image_error
                    Toast.makeText(this@ComposeChatActivity, getString(msgRes), Toast.LENGTH_SHORT).show()
                },
            )
        }
    }

    // Image picker for user-assisted screenshot fallback. Validation/save/toast logic is shared with
    // the batch mode via UserImageUploadHelper so both behave identically; null == skip.
    private val userImagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        appViewModel.provideUserImage(
            io.agents.bqaagent.utils.UserImageUploadHelper.savePickedImage(this, uri)
        )
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val conversationStore by lazy { ConversationStore(this) }

    // Compose state — observed by ChatScreen
    private val _messages = mutableStateListOf<ChatMessage>()
    private val _modelStatus = mutableStateOf("No model loaded")
    private val _isLocalModelActive = mutableStateOf(ModelConfigRepository.isLocalActive())
    private val _usesExplicitInputModes =
        mutableStateOf(ModelConfigRepository.activeModelUsesExplicitInputModes())
    private val _permissionBannerText = mutableStateOf<String?>(null)
    private val _isAwaitingReply = mutableStateOf(false)
    private val _isTaskRunning = mutableStateOf(false)
    private val _isTaskStopping = mutableStateOf(false)
    private val _otherModeBusy = mutableStateOf(false)
    private val _batchOwnsWorkspace = mutableStateOf(false)
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
                // Same instance the Compose gate observes, so requestStopTask() actually locks the UI.
                isTaskStopping = _isTaskStopping,
            ),
            onPersistConversation = { saveChat() },
            onTaskSettled = { deferLocalChatBootstrapForAutoTask = false },
            onTaskTerminal = { sendExternalAutomationTerminalCallback(it) },
        )
    }

    private val activeTaskShellController by lazy {
        ActiveTaskShellController(appViewModel = appViewModel).apply {
            onTaskStopRequested = { taskFlowController.requestStopTask() }
        }
    }

    /**
     * Non-blocking capability banner text, or null to hide. Only definitive down states surface:
     * CONNECTING is a transient probe (15s cache staleness right after resume) and READY needs
     * no banner — mirroring the SettingsActivity updateTaskRecordingLabel lesson.
     */
    private fun computePermissionBannerText(): String? = when (AppCapabilityCoordinator.localAdbState(this)) {
        ServiceBindingState.DEGRADED -> getString(R.string.chat_banner_adb_disconnected)
        ServiceBindingState.DISABLED -> getString(R.string.chat_banner_adb_not_ready)
        else -> null
    }

    // Permission polling
    private val permHandler = Handler(Looper.getMainLooper())
    private val permPoller = object : Runnable {
        override fun run() {
            _permissionBannerText.value = computePermissionBannerText()
            permHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)

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
                    permissionBannerText = _permissionBannerText.value,
                    isAwaitingReply = _isAwaitingReply.value,
                    isTaskRunning = _isTaskRunning.value,
                    // Confirmed-stop gate: true until the orchestrator proves the loop has exited.
                    isTaskStopping = _isTaskStopping.value,
                    otherModeBusy = _otherModeBusy.value && _batchOwnsWorkspace.value,
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
                    onOpenBatch = {
                        io.agents.bqaagent.ui.batch.BatchActivity.start(this@ComposeChatActivity)
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
                            getString(R.string.chat_image_upload_soon),
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
                    // Voice permission request callback
                    onRequestRecordPermission = {
                        recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    showUserImageUpload = taskFlowController.showUserImageUpload,
                    onUploadImage = { userImagePickerLauncher.launch("image/*") },
                    onSkipImageUpload = { appViewModel.provideUserImage(null) },
                    sensitiveConfirmPrompt = taskFlowController.sensitiveConfirmPrompt,
                    onApproveSensitive = { appViewModel.resolveSensitiveConfirm(true) },
                    onDenySensitive = { appViewModel.resolveSensitiveConfirm(false) },
                    onOcrRequest = { _showOcrSheet.value = true },
                    ocrLoading = _ocrLoading.value,
                    ocrExtractedText = _ocrExtractedText.value,
                    onOcrConsumed = { _ocrExtractedText.value = null },
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

                if (_showOcrSheet.value) {
                    io.agents.bqaagent.ui.common.OcrSourceChooserSheet(
                        colors = composeColors,
                        onCamera = {
                            _showOcrSheet.value = false
                            if (androidx.core.content.ContextCompat.checkSelfPermission(
                                    this@ComposeChatActivity, Manifest.permission.CAMERA
                                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                            ) {
                                launchOcrCamera()
                            } else {
                                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                        },
                        onGallery = {
                            _showOcrSheet.value = false
                            ocrGalleryLauncher.launch("image/*")
                        },
                        onDismiss = { _showOcrSheet.value = false },
                    )
                }

                if (_showMonitorGuide.value) {
                    io.agents.bqaagent.ui.common.MonitorPermissionSheet(
                        colors = composeColors,
                        targetLabel = taskFlowController.pendingMonitorTarget?.displayLabel.orEmpty(),
                        onRequestNotificationPermission = {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                monitorNotifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        },
                        onOpenRequirement = { requirement ->
                            if (requirement == AppRequirement.NOTIFICATION_ACCESS) {
                                // Return to chat (not Settings) after the listener connects.
                                KVUtils.markPendingNotificationAccessReturnToChat()
                            }
                            AppCapabilityCoordinator.openSystemSettings(this@ComposeChatActivity, requirement)
                        },
                        onStart = {
                            _showMonitorGuide.value = false
                            if (!taskFlowController.resumePendingMonitorIfReady()) {
                                taskFlowController.clearPendingMonitor()
                            }
                        },
                        onDismiss = {
                            _showMonitorGuide.value = false
                            taskFlowController.clearPendingMonitor()
                        },
                    )
                }

                if (_ocrLoading.value) {
                    io.agents.bqaagent.ui.common.OcrLoadingOverlay(colors = composeColors)
                }

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

    /** Opens the non-blocking monitor permission guide (see MonitorPermissionSheet). */
    fun showMonitorPermissionGuide() {
        _showMonitorGuide.value = true
    }

    override fun onResume() {
        super.onResume()
        // Recording tail-off waits for this flag so the return-to-chat moment is captured.
        TaskRecordingCoordinator.onChatResumed()
        _permissionBannerText.value = computePermissionBannerText()
        // Breakpoint resume: returning from system settings with everything granted starts
        // the parked monitor immediately; otherwise the open sheet keeps polling.
        if (taskFlowController.resumePendingMonitorIfReady()) {
            _showMonitorGuide.value = false
        }
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
        // Authoritative "last mode" marker for cold-start restore; the top-most resumed screen wins.
        KVUtils.setLastUiMode(io.agents.bqaagent.ui.batch.BatchUiMode.CHAT)
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
        disableKeepAlive()
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

    internal fun enableKeepAlive() {
        runOnUiThread {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (keyguardLock == null) {
                val km = getSystemService(android.content.Context.KEYGUARD_SERVICE) as KeyguardManager
                keyguardLock = km.newKeyguardLock(TAG)
            }
            keyguardLock?.disableKeyguard()
            XLog.d(TAG, "Keep-alive enabled (FLAG_KEEP_SCREEN_ON + keyguard disabled)")
        }
    }

    internal fun disableKeepAlive() {
        runOnUiThread {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            keyguardLock?.reenableKeyguard()
            keyguardLock = null
            XLog.d(TAG, "Keep-alive disabled")
        }
    }

    init {
        lifecycleScope.launch {
            appViewModel.taskBusy.collect { busy ->
                _otherModeBusy.value = busy
                _batchOwnsWorkspace.value = appViewModel.ownerMode() ==
                        io.agents.bqaagent.TaskRunMode.BATCH
                // A foreign-mode run finishing must not leave the chat gate stuck.
                if (!busy && appViewModel.ownerMode() == io.agents.bqaagent.TaskRunMode.NONE) {
                    _isTaskStopping.value = false
                    _isTaskRunning.value = false
                }
            }
        }
    }
}
