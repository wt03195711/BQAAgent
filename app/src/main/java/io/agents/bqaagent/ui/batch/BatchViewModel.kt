// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.ui.batch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.agents.bqaagent.AppCapabilityCoordinator
import io.agents.bqaagent.ClawApplication
import io.agents.bqaagent.R
import io.agents.bqaagent.ServiceBindingState
import io.agents.bqaagent.TaskStatus
import io.agents.bqaagent.agent.llm.ActiveModelMode
import io.agents.bqaagent.agent.llm.ModelConfigRepository
import io.agents.bqaagent.agent.skill.ExecutionTemplate
import io.agents.bqaagent.agent.skill.SkillDefinition
import io.agents.bqaagent.agent.skill.SkillStore
import io.agents.bqaagent.appViewModel
import io.agents.bqaagent.recording.TaskRecordingCoordinator
import io.agents.bqaagent.recording.TaskRecordingStore
import io.agents.bqaagent.ui.chat.ChatHistoryManager
import io.agents.bqaagent.ui.chat.ChatMessage
import io.agents.bqaagent.utils.KVUtils
import io.agents.bqaagent.utils.XLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * State holder for the batch screens (setup / precheck / run / history).
 *
 * It owns NO execution logic of its own — running a batch delegates entirely to [BatchTaskDriver],
 * which reuses the single-task pipeline via the shared [io.agents.bqaagent.TaskOrchestrator]. This
 * VM is the bridge between that driver and Compose: it holds the queue, the config, the
 * SkillCaptureMode gate, the precheck verdict, the live run snapshot, and the persisted batch
 * history (mode=BATCH conversations).
 *
 * SkillCaptureMode gating (mirrors the confirmed matrix, identical to chat):
 *  - OFF -> no skill library, no "add from Skill list", and BOTH skill toggles (autoSaveSkill,
 *    preferReplay) forced false; text tasks run plain AgentLoop. The whole record/replay/save
 *    pipeline is unavailable, exactly as in chat.
 */
class BatchViewModel : ViewModel() {

    companion object {
        private const val TAG = "BatchViewModel"
        /** Hard cap on tasks per batch; the workspace hides the "+" tile at this count. */
        const val MAX_TASKS = 30

        /**
         * Safety bound for the post-cancel teardown. After Cancel the driver asynchronously stops the
         * in-flight task, persists, and publishes its terminal snapshot (cancel grace + session release +
         * device/recording cleanup); the collector then folds results, persists, and releases the lock.
         * If that chain ever stalls, force-release after this bound so the UI can never stay frozen on
         * "Stopping…". Comfortably exceeds the driver's CANCEL_GRACE_MS + SESSION_RELEASE_TIMEOUT_MS.
         */
        private const val CANCEL_TEARDOWN_TIMEOUT_MS = 15_000L

        private fun newBatchConversationId(): String = "batch_${System.currentTimeMillis()}"
        /** Unique per-row id: a tight load loop can share one millisecond, so a timestamp is not enough. */
        private fun newQueueItemId(): String = "q_" + java.util.UUID.randomUUID().toString().take(8)

        /**
         * Conversation writes deliberately outlive the ViewModel. Switching to chat calls
         * Activity.finish(), which cancels viewModelScope and would drop an in-flight save — so a
         * just-made edit vanished on the next Batch open. A process-scoped supervisor keeps the write
         * running to completion. The mutex is shared for the same reason: it serializes writes even
         * across a VM recreated when the user returns.
         */
        private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val persistMutex = Mutex()
    }

    /** One queued task row before it is dispatched. */
    data class QueueItem(
        val id: String,
        val text: String,
        /** Short human label for the row (currently the task text). */
        val label: String,
        /** Last-known result (loaded from history, or folded in after a run); null = never run (NONE). */
        val result: BatchTaskView? = null,
        /** The task's persisted message span, kept across edits so untouched results survive a re-save. */
        val transcript: List<ChatMessage> = emptyList(),
    )

    private val appContext get() = ClawApplication.instance

    private val _queue = MutableStateFlow<List<QueueItem>>(emptyList())
    val queue: StateFlow<List<QueueItem>> = _queue.asStateFlow()

    private val _config = MutableStateFlow(BatchConfig())
    val config: StateFlow<BatchConfig> = _config.asStateFlow()

    private val _skillCaptureEnabled = MutableStateFlow(KVUtils.isSkillCaptureModeEnabled())
    val skillCaptureEnabled: StateFlow<Boolean> = _skillCaptureEnabled.asStateFlow()

    private val _precheck = MutableStateFlow(BatchPrecheck())
    val precheck: StateFlow<BatchPrecheck> = _precheck.asStateFlow()

    private val _runState = MutableStateFlow<BatchTaskDriver.BatchRunSnapshot?>(null)
    val runState: StateFlow<BatchTaskDriver.BatchRunSnapshot?> = _runState.asStateFlow()

    /**
     * Observable "a batch run currently owns the workspace" lock — the single source of truth for the
     * UI's `active` gate (drawer session switching + queue editing + settings). Unlike the old
     * `vm.isRunning || runState?.finished == false` (which mixed a NON-observable getter with the live
     * snapshot), this is a plain observable the collector drives, so Compose always recomposes when a run
     * starts or ends. It stays TRUE through a cancel's teardown and is cleared only once the driver has
     * stopped + persisted and the collector has folded the results back — i.e. "stop → save → then
     * unlock", never before, so editing/switching/starting can never cross-interfere with teardown.
     */
    private val _runActive = MutableStateFlow(false)
    val runActive: StateFlow<Boolean> = _runActive.asStateFlow()

    /**
     * True from the moment the user taps Cancel until teardown completes (driver stopped + data saved +
     * results folded). While true, [runActive] is still true (workspace locked) and the bottom button
     * shows a disabled "Stopping…" instead of "Cancel", so the user sees the stop/save is still in flight
     * and cannot start a conflicting run. Cleared by the state collector on finish, or by [cancelWatchdog]
     * as a safety net if teardown stalls.
     */
    private val _cancelling = MutableStateFlow(false)
    val cancelling: StateFlow<Boolean> = _cancelling.asStateFlow()

    /** Force-releases the lock if the post-cancel teardown ever stalls (CANCEL_TEARDOWN_TIMEOUT_MS). */
    private var cancelWatchdog: Job? = null

    // Forwarded from the live driver: the pending user-image upload prompt (null = no dialog).
    private val _userImagePrompt = MutableStateFlow<BatchUserImagePrompt?>(null)
    val userImagePrompt: StateFlow<BatchUserImagePrompt?> = _userImagePrompt.asStateFlow()

    // Forwarded from the live driver: the pending sensitive-confirm prompt (null = no dialog).
    private val _sensitiveConfirmPrompt = MutableStateFlow<BatchSensitiveConfirmPrompt?>(null)
    val sensitiveConfirmPrompt: StateFlow<BatchSensitiveConfirmPrompt?> = _sensitiveConfirmPrompt.asStateFlow()

    private var driver: BatchTaskDriver? = null

    /** Conversation this editable batch writes to; null until the first task is added or a session loads. */
    private var currentConversationId: String? = null

    val isRunning: Boolean get() = driver?.isRunning == true

    /** Re-read the SkillCaptureMode gate (call on resume; the switch lives in Settings). */
    fun refreshGate() {
        val enabled = KVUtils.isSkillCaptureModeEnabled()
        _skillCaptureEnabled.value = enabled
        if (!enabled && (_config.value.autoSaveSkill || _config.value.preferReplay)) {
            // Both skill toggles — B (auto-save) and C (prefer-replay) — are meaningless and hidden
            // while capture is off. Force them off AND persist, so re-enabling capture later shows a
            // consistent OFF default for both instead of resurrecting a stale pre-disable state.
            _config.value = _config.value.copy(autoSaveSkill = false, preferReplay = false)
            persistQueue()
        }
    }

    // ==================== Queue editing ====================

    /** Queue a task from plain text. Execution mode is NOT decided here — it is resolved at dispatch (JIT). */
    fun addTextTask(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        return enqueue(text = trimmed, label = trimmed)
    }

    /** Append a row (capped at [MAX_TASKS]) and persist the edited task list to the conversation. */
    private fun enqueue(text: String, label: String): Boolean {
        if (_queue.value.size >= MAX_TASKS) {
            XLog.w(TAG, "enqueue rejected — batch already has $MAX_TASKS tasks")
            return false
        }
        _queue.value = _queue.value + QueueItem(
            id = newQueueItemId(),
            text = text,
            label = label,
        )
        // Editing invalidates any just-finished run view; rows fall back to their own folded result.
        _runState.value = null
        persistQueue()
        return true
    }

    fun removeTaskAt(index: Int) {
        _queue.value = _queue.value.filterIndexed { i, _ -> i != index }
        _runState.value = null
        persistQueue()
    }

    /** Reset to a brand-new empty batch; forgets the conversation id and any live run snapshot. */
    fun clearQueue() {
        _queue.value = emptyList()
        _runState.value = null
        _config.value = BatchConfig()
        currentConversationId = null
    }

    fun updateConfig(transform: (BatchConfig) -> BatchConfig) {
        var next = transform(_config.value)
        // Hard gate: neither skill toggle (auto-save / prefer-replay) can be on while capture is off.
        if (!_skillCaptureEnabled.value) next = next.copy(autoSaveSkill = false, preferReplay = false)
        _config.value = next
        persistQueue()
    }

    // ==================== Skill library (gated) ====================

    /** Skills for the batch picker, in the SAME order as Skill Management (most recently updated first). */
    fun availableSkills(): List<SkillDefinition> =
        if (_skillCaptureEnabled.value) SkillStore.getAllSkills().sortedByDescending { it.updatedAtMs } else emptyList()

    fun templatesFor(skillId: String): List<ExecutionTemplate> =
        if (_skillCaptureEnabled.value) SkillStore.getTemplates(skillId) else emptyList()

    // ==================== Precheck ===================

    /** Runs the (blocking) ADB probe off the main thread and publishes the verdict. */
    fun runPrecheck() {
        val taskCount = _queue.value.size
        viewModelScope.launch(Dispatchers.IO) {
            val recordingEnabled = KVUtils.isTaskRecordingEnabled()
            // Single authoritative ADB verdict, now routed through the coordinator so batch, chat
            // banner and Settings derive the SAME ServiceBindingState from the SAME primitives — the
            // 8s blocking probe is preserved (a hard launch gate needs a deterministic answer, so we
            // deliberately do NOT downgrade to the cached localAdbState() read).
            val adbState = AppCapabilityCoordinator.probeLocalAdbState(8_000L)
            val adbConfigured = adbState != ServiceBindingState.DISABLED
            val adbReady = adbState == ServiceBindingState.READY

            val blocking = mutableListOf<String>()
            val warnings = mutableListOf<String>()

            when (adbState) {
                ServiceBindingState.DISABLED -> blocking += appContext.getString(R.string.batch_precheck_block_no_adb)
                ServiceBindingState.DEGRADED -> warnings += appContext.getString(R.string.batch_precheck_warn_adb_no_answer)
                else -> Unit
            }
            if (taskCount == 0) {
                blocking += appContext.getString(R.string.batch_precheck_block_no_task)
            }
            if (!ModelConfigRepository.hasUsableModel()) {
                blocking += appContext.getString(R.string.batch_precheck_block_no_model)
            }
            // Recording details are only computed/shown when the global recording switch is on.
            var p = BatchPrecheck(
                checkedAt = System.currentTimeMillis(),
                taskCount = taskCount,
                adbConfigured = adbConfigured,
                adbReady = adbReady,
                recordingEnabled = recordingEnabled,
                blocking = blocking,
                warnings = warnings,
            )
            if (recordingEnabled) {
                val free = TaskRecordingStore.freeSpaceBytes()
                val used = TaskRecordingStore.totalBytes()
                val max = TaskRecordingStore.maxTotalBytes()
                val hasRoom = TaskRecordingStore.hasRoomForRecording()
                val near = TaskRecordingStore.isNearCapacity()
                if (!hasRoom) {
                    warnings += appContext.getString(R.string.batch_precheck_warn_low_space)
                }
                if (near) {
                    warnings += appContext.getString(R.string.batch_precheck_warn_near_cap, TaskRecordingStore.formatBytes(max))
                }
                p = p.copy(
                    recordingReady = TaskRecordingCoordinator.isEnabled(),
                    diskFreeBytes = free,
                    diskFreeText = TaskRecordingStore.formatBytes(free),
                    hasRoomForRecording = hasRoom,
                    usedBytes = used,
                    usedText = TaskRecordingStore.formatBytes(used),
                    maxBytes = max,
                    maxText = TaskRecordingStore.formatBytes(max),
                    nearCapacity = near,
                )
            }
            // Skill-library capacity hint (only when auto-save / switch B is on). NON-BLOCKING warning.
            // At pre-check time we cannot know which tasks will actually save a skill — that is decided
            // just-in-time at dispatch — so we use the worst case: every task creates a brand-new skill.
            // If the current library plus this batch's task count could exceed MAX_SKILLS, warn the user
            // that some new skills may be abandoned at the cap.
            if (_config.value.autoSaveSkill && _skillCaptureEnabled.value) {
                val currentSkills = SkillStore.getAllSkills().size
                val max = SkillStore.MAX_SKILLS
                if (currentSkills + taskCount > max) {
                    warnings += appContext.getString(
                        R.string.batch_precheck_warn_skill_near_cap,
                        currentSkills, max, taskCount
                    )
                }
            }
            _precheck.value = p
        }
    }

    // ==================== Run lifecycle ====================

    fun startBatch(): Boolean {
        if (isRunning) return false
        if (appViewModel.isTaskRunning()) {
            XLog.w(TAG, "startBatch rejected — a chat task is running")
            return false
        }
        val items = _queue.value
        // A batch task carries no add-time execution state; the driver builds a fresh run-time
        // BatchRunContext from the live config at each dispatch.
        val inputs = items.map { BatchTaskDriver.BatchTaskInput(it.text) }
        if (inputs.isEmpty()) return false

        // Decision A: re-running a (possibly historical) batch OVERWRITES the same conversation, and each
        // task's prior result is reset — the driver rewrites the file from scratch as this run progresses.
        val conversationId = currentConversationId ?: newBatchConversationId().also { currentConversationId = it }
        // Re-run resets EVERY task to NONE: clear results/transcripts in memory and rewrite the
        // conversation to description-only right now, so a detail opened during the reset shows just the
        // task text (like a newly added task) instead of the previous run's steps. The driver overwrites
        // each task's span as it re-runs. This write is fast and lands well before the driver's first
        // persist (which only happens after the first task settles).
        _queue.value = items.map { it.copy(result = null, transcript = emptyList()) }
        persistQueue()
        launchDriver(
            conversationId, inputs, startIndex = 0,
            priorMessages = emptyList(), priorViews = emptyList(),
            preservedSpans = emptyList(), preservedViews = emptyList(),
        )
        XLog.i(TAG, "Batch started: ${inputs.size} task(s), conversation=$conversationId (overwrite)")
        return true
    }

    fun continueBatch(): Boolean {
        if (isRunning) return false
        if (appViewModel.isTaskRunning()) {
            XLog.w(TAG, "continueBatch rejected — a chat task is running")
            return false
        }
        val items = _queue.value
        if (items.isEmpty()) return false
        val resumeIndex = items.indexOfFirst { !it.result.isCompleted() }
        // Fall back to a full restart only when Continue would be identical to it: no unfinished task
        // at all (resumeIndex < 0), or an unfinished head with nothing completed to preserve
        // (resumeIndex == 0 && !hasCompleted). A resumeIndex == 0 WITH a later completed task still
        // takes the preserve path below, so its settled middle rows are re-adopted, never re-run.
        val hasCompleted = items.any { it.result.isCompleted() }
        if (resumeIndex < 0 || (resumeIndex == 0 && !hasCompleted)) return startBatch()
        val conversationId = currentConversationId ?: return startBatch()
        val summary = listBatchSessions().firstOrNull { it.id == conversationId } ?: return startBatch()
        val allSpans = groupTaskSpans(openSession(summary))
        val priorMessages = allSpans.take(resumeIndex).flatten()
        val priorViews = items.take(resumeIndex).mapIndexed { i, it ->
            it.result ?: BatchTaskView(seq = i + 1, taskText = it.text, phase = BatchTaskPhase.PENDING)
        }
        // Requirement 3: a "Continue" only re-runs the tasks that never settled. Any task at or after the
        // resume point that is ALREADY completed (SETTLED SUCCESS/FAILED) is preserved verbatim — the driver
        // re-adopts its prior transcript + result view instead of dispatching it again, so a succeeded task
        // sandwiched between unfinished ones (e.g. 1.ok 2.skipped 3.ok) is never re-run.
        val preservedSpans = items.mapIndexed { i, it ->
            if (i >= resumeIndex && it.result.isCompleted()) it.transcript else null
        }
        val preservedViews = items.mapIndexed { i, it ->
            if (i >= resumeIndex && it.result.isCompleted()) it.result else null
        }
        val inputs = items.map { BatchTaskDriver.BatchTaskInput(it.text) }
        _queue.value = items.mapIndexed { i, it -> if (i < resumeIndex) it else it.copy(result = null, transcript = emptyList()) }
        launchDriver(
            conversationId, inputs, startIndex = resumeIndex,
            priorMessages = priorMessages, priorViews = priorViews,
            preservedSpans = preservedSpans, preservedViews = preservedViews,
        )
        XLog.i(TAG, "Batch continued from #$resumeIndex: ${inputs.size - resumeIndex} task(s), conversation=$conversationId")
        return true
    }

    private fun launchDriver(
        conversationId: String,
        inputs: List<BatchTaskDriver.BatchTaskInput>,
        startIndex: Int,
        priorMessages: List<ChatMessage>,
        priorViews: List<BatchTaskView>,
        preservedSpans: List<List<ChatMessage>?>,
        preservedViews: List<BatchTaskView?>,
    ) {
        val cfg = _config.value.let { if (!_skillCaptureEnabled.value) it.copy(autoSaveSkill = false, preferReplay = false) else it }
        val newDriver = BatchTaskDriver(
            orchestrator = appViewModel.taskOrchestrator,
            appContext = appContext,
            conversationId = conversationId,
            modelName = activeModelName(),
            config = cfg,
            inputs = inputs,
            // Reuse the chat hook that releases the single-session local LLM before a task runs.
            onTaskWillStart = { appViewModel.onBeforeTask?.invoke() },
            startIndex = startIndex,
            priorMessages = priorMessages,
            priorViews = priorViews,
            preservedSpans = preservedSpans,
            preservedViews = preservedViews,
        )
        driver = newDriver
        _runActive.value = true
        _cancelling.value = false
        cancelWatchdog?.cancel()
        cancelWatchdog = null
        KVUtils.setLastUiMode(BatchUiMode.BATCH)

        // Mirror the driver's upload prompt into VM state so BatchRoot can show/hide the global dialog.
        viewModelScope.launch {
            newDriver.userImagePrompt.collect { _userImagePrompt.value = it }
        }

        // Mirror the driver's sensitive-confirm prompt for the global confirmation dialog.
        viewModelScope.launch {
            newDriver.sensitiveConfirmPrompt.collect { _sensitiveConfirmPrompt.value = it }
        }

        viewModelScope.launch {
            newDriver.state.collect { snapshot ->
                if (!snapshot.finished) {
                    _runState.value = snapshot
                    return@collect
                }
                // The run ended: reconcile the rows with the final snapshot, then drop the live snapshot
                // so rows fall back to their own folded result.
                //  - settled / skipped / timeout / abandoned tasks keep their result and adopt the
                //    persisted span, so their detail still shows steps + result and survives later edits;
                //  - tasks that never ran (still PENDING/RUNNING when the batch stopped or was cancelled)
                //    revert to NONE (result = null, transcript cleared) — pending/running only ever exist
                //    during a live run.
                // Guard: if the teardown watchdog force-released the lock and the user then switched
                // to a different batch, the old run's terminal snapshot must NOT clobber the newly
                // loaded queue. Only fold results back when we are still on the same conversation.
                if (currentConversationId == conversationId) {
                    val spans = withContext(Dispatchers.IO) {
                        runCatching {
                            listBatchSessions().firstOrNull { it.id == conversationId }
                                ?.let { groupTaskSpans(openSession(it)) }
                        }.getOrNull().orEmpty()
                    }
                    _queue.value = _queue.value.mapIndexed { i, q ->
                        val view = snapshot.tasks.getOrNull(i)?.takeIf {
                            it.phase != BatchTaskPhase.PENDING && it.phase != BatchTaskPhase.RUNNING
                        }
                        q.copy(
                            result = view,
                            transcript = if (view != null) (spans.getOrNull(i) ?: emptyList()) else emptyList(),
                        )
                    }
                    // Rewrite the conversation so every row is represented on disk: settled spans as-is
                    // and a description-only USER message for each NONE task. Without this, tasks that
                    // never ran would vanish on reopen (the driver only persists what it dispatched).
                    persistQueue()
                }
                _runState.value = null
                _runActive.value = false
                _cancelling.value = false
                cancelWatchdog?.cancel()
                cancelWatchdog = null
            }
        }
        newDriver.start()
    }

    fun cancelBatch() {
        val d = driver ?: return
        d.cancel()
        // Do NOT unlock here. Hold the workspace lock ("Stopping…") while the driver tears down: stop the
        // in-flight task, persist (the driver's finally), then let the state collector fold the results
        // back and persistQueue. Only when that completes does the collector clear runActive/cancelling
        // and re-enable the UI — the "stop → save → then unlock, no cross-interference" contract.
        _cancelling.value = true
        // Safety net: teardown is asynchronous. If it ever stalls, force-release after
        // CANCEL_TEARDOWN_TIMEOUT_MS so the workspace can never stay frozen on "Stopping…". The collector
        // cancels this watchdog the moment teardown truly completes.
        cancelWatchdog?.cancel()
        cancelWatchdog = viewModelScope.launch {
            delay(CANCEL_TEARDOWN_TIMEOUT_MS)
            if (_cancelling.value) {
                XLog.w(TAG, "Cancel teardown exceeded ${CANCEL_TEARDOWN_TIMEOUT_MS}ms — force-releasing the workspace lock")
                _cancelling.value = false
                _runActive.value = false
            }
        }
    }

    /**
     * Feed the user's picked screenshot (or null = skip) into the shared orchestrator latch. Batch and
     * chat unlock the exact same onScreenshotBlocked wait via AppViewModel.provideUserImage.
     */
    fun provideUserImage(imagePath: String?) {
        appViewModel.provideUserImage(imagePath)
    }

    /**
     * Feed the user's approve/deny decision into the shared orchestrator latch. Batch and chat
     * unlock the exact same sensitive-confirmation wait via AppViewModel.resolveSensitiveConfirm.
     */
    fun resolveSensitiveConfirm(approved: Boolean) {
        appViewModel.resolveSensitiveConfirm(approved)
    }

    private fun activeModelName(): String {
        val snap = ModelConfigRepository.snapshot()
        return if (snap.activeMode == ActiveModelMode.LOCAL) snap.local.displayName else snap.activeCloud.modelName
    }

    // ==================== History (mode=BATCH conversations) ====================

    fun listBatchSessions(): List<ChatHistoryManager.ConversationSummary> =
        ChatHistoryManager.listConversations(appContext, BatchUiMode.BATCH)

    fun openSession(summary: ChatHistoryManager.ConversationSummary): List<ChatMessage> =
        ChatHistoryManager.load(summary.file)

    fun deleteSession(summary: ChatHistoryManager.ConversationSummary): Boolean =
        ChatHistoryManager.delete(summary.file)

    fun renameSession(summary: ChatHistoryManager.ConversationSummary, newTitle: String): Boolean =
        ChatHistoryManager.rename(summary.file, newTitle)

    /** Derive the batch-list header (counts per status) from a persisted conversation. */
    fun deriveSessionView(summary: ChatHistoryManager.ConversationSummary): BatchSessionView {
        val messages = openSession(summary)
        val taskCount = messages.count { it.role == ChatMessage.Role.USER }
        val statusCounts = messages
            .filter { it.role == ChatMessage.Role.ASSISTANT && it.taskStatus != null }
            .groupingBy { it.taskStatus as TaskStatus }
            .eachCount()
        val settled = statusCounts.values.sum()
        return BatchSessionView(
            id = summary.id,
            title = summary.title,
            created = summary.created,
            updated = summary.file.lastModified(),
            taskCount = taskCount,
            lastRunAt = summary.file.lastModified(),
            statusCounts = statusCounts,
            pendingCount = (taskCount - settled).coerceAtLeast(0),
            config = _config.value,
        )
    }

    // ==================== Load / edit persistence (historical batches are editable) ====================

    /**
     * Load a saved batch into the editable workspace: rebuild the queue from the USER messages,
     * restore the saved config, and derive each task's last-known status + transcript
     * so untouched results survive later edits. Sets [currentConversationId] so edits and a re-run
     * overwrite this same conversation (decision A). Returns false when the file has no task rows.
     */
    fun loadSession(summary: ChatHistoryManager.ConversationSummary): Boolean {
        val spans = groupTaskSpans(openSession(summary))
        // Mirror chat's openConversation: opening a saved batch marks it most-recently-accessed so it
        // sorts to the top of the drawer (listConversations sorts by file lastModified, descending).
        ChatHistoryManager.touchAccess(summary.file)
        var cfg = ChatHistoryManager.readBatchConfig(summary.file) ?: BatchConfig()
        if (!_skillCaptureEnabled.value) cfg = cfg.copy(autoSaveSkill = false, preferReplay = false)
        if (spans.isEmpty()) {
            // An empty (Untitled) batch: adopt it so the workspace shows empty and later edits reuse the
            // same conversation id instead of orphaning a brand-new one.
            _queue.value = emptyList()
            _config.value = cfg
            _runState.value = null
            currentConversationId = summary.id
            return true
        }
        _queue.value = spans.mapIndexed { i, span ->
            val user = span.firstOrNull { it.role == ChatMessage.Role.USER }
            val text = user?.content.orEmpty()
            QueueItem(
                id = newQueueItemId(),
                text = text,
                label = text,
                result = deriveTaskView(span, i + 1),
                transcript = span,
            )
        }
        _config.value = cfg
        _runState.value = null
        currentConversationId = summary.id
        return true
    }

    /**
     * Entry restore: whenever Batch opens onto an EMPTY workspace — from the chat entry button or a
     * cold start (Splash routes here when the last UI mode was BATCH) — load the most-recently-saved
     * batch so the user lands on existing data instead of a blank new batch. A brand-new batch is shown
     * only when no saved batch exists yet. No-op when the workspace already has content (so rotation and
     * an explicit "New batch" are never clobbered). Runs off the main thread (file I/O).
     */
    fun restoreLastSession() {
        if (_queue.value.isNotEmpty() || currentConversationId != null) return
        viewModelScope.launch(Dispatchers.IO) {
            // listBatchSessions() is sorted newest-first (by file lastModified), so first() is exactly
            // the batch shown at the top of the drawer — the "first batch task" the user expects.
            val summary = listBatchSessions().firstOrNull() ?: return@launch
            loadSession(summary)
        }
    }

    /**
     * Persist the current editable queue to [currentConversationId] as a mode=BATCH conversation. Each
     * row reuses its loaded/ran transcript when present (untouched results preserved) or a synthesized
     * USER message when new. No-op while the queue is empty; writes are serialized by [persistMutex] and
     * always re-read the latest queue inside the lock so the final write wins.
     */
    fun persistQueue() {
        persistScope.launch {
            persistMutex.withLock {
                val items = _queue.value
                // An empty queue is persisted only when a conversation already exists, so deleting the
                // last task sticks (an "Untitled" empty batch, mirroring chat); a brand-new empty session
                // is never minted.
                val cid = if (items.isEmpty()) {
                    currentConversationId ?: return@withLock
                } else {
                    currentConversationId ?: newBatchConversationId().also { currentConversationId = it }
                }
                val cfg = _config.value.let { if (!_skillCaptureEnabled.value) it.copy(autoSaveSkill = false, preferReplay = false) else it }
                runCatching {
                    ChatHistoryManager.save(
                        context = appContext,
                        conversationId = cid,
                        messages = rebuildMessages(items),
                        model = activeModelName(),
                        mode = BatchUiMode.BATCH,
                        batchConfig = cfg,
                    )
                }.onFailure { XLog.w(TAG, "persistQueue failed: ${it.message}") }
            }
        }
    }

    /** The message span for one task in the current conversation, for the per-task detail drill-down. */
    fun taskMessages(taskIndex: Int): List<ChatMessage> {
        val cid = currentConversationId
        if (cid != null) {
            val summary = listBatchSessions().firstOrNull { it.id == cid }
            if (summary != null) {
                val span = groupTaskSpans(openSession(summary)).getOrNull(taskIndex)
                if (!span.isNullOrEmpty()) return span
            }
        }
        // No persisted span for this row yet (never run, mid-reset, or a live run that has not reached
        // it): fall back to the in-memory item so the detail still shows the task description, exactly
        // like a newly added task.
        val item = _queue.value.getOrNull(taskIndex) ?: return emptyList()
        if (item.transcript.isNotEmpty()) return item.transcript
        return listOf(
            ChatMessage(
                role = ChatMessage.Role.USER,
                content = item.text,
            )
        )
    }

    private fun rebuildMessages(items: List<QueueItem>): List<ChatMessage> {
        val out = mutableListOf<ChatMessage>()
        for (item in items) {
            if (item.transcript.isNotEmpty()) {
                out += item.transcript
            } else {
                out += ChatMessage(
                    role = ChatMessage.Role.USER,
                    content = item.text,
                )
            }
        }
        return out
    }

    /** Split a flat conversation into one message span per task, keyed by each USER message. */
    private fun groupTaskSpans(messages: List<ChatMessage>): List<List<ChatMessage>> {
        val spans = mutableListOf<MutableList<ChatMessage>>()
        for (msg in messages) {
            if (msg.role == ChatMessage.Role.USER) {
                spans.add(mutableListOf(msg))
            } else if (spans.isNotEmpty()) {
                spans.last().add(msg)
            }
        }
        return spans
    }

    /** Derive a row's last-known status from its persisted span; null when the task never settled. */
    private fun deriveTaskView(span: List<ChatMessage>, seq: Int): BatchTaskView? {
        val user = span.firstOrNull { it.role == ChatMessage.Role.USER } ?: return null
        val assistant = span.lastOrNull { it.role == ChatMessage.Role.ASSISTANT }
        val status = assistant?.taskStatus
        val systemText = span.filter { it.role == ChatMessage.Role.SYSTEM }.joinToString(" ") { it.content }
        val phase = when {
            status != null -> BatchTaskPhase.SETTLED
            "abandoned" in systemText -> BatchTaskPhase.ABANDONED
            "Timed out" in systemText -> BatchTaskPhase.HANG_TIMEOUT
            "Skipped" in systemText -> BatchTaskPhase.SKIPPED_ADB
            else -> return null
        }
        return BatchTaskView(
            seq = seq,
            taskText = user.content,
            phase = phase,
            taskStatus = status,
            reasonCode = assistant?.reasonCode,
            recordingId = assistant?.recordingId,
            resultSummary = assistant?.content?.take(200),
        )
    }
}