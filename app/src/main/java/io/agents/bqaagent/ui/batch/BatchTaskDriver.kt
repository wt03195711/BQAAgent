// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.ui.batch

import android.content.Context
import io.agents.bqaagent.TaskEvent
import io.agents.bqaagent.TaskOrchestrator
import io.agents.bqaagent.TaskStatus
import io.agents.bqaagent.adb.LocalAdbAutomation
import io.agents.bqaagent.adb.LocalAdbDeviceDriver
import io.agents.bqaagent.agent.skill.SkillAutoSaver
import io.agents.bqaagent.agent.skill.SkillStore
import io.agents.bqaagent.channel.Channel
import io.agents.bqaagent.recording.RecordingPhase
import io.agents.bqaagent.recording.TaskRecordingCoordinator
import io.agents.bqaagent.ui.chat.ChatHistoryManager
import io.agents.bqaagent.ui.chat.ChatMessage
import io.agents.bqaagent.utils.KVUtils
import io.agents.bqaagent.utils.ScreenKeepAliveManager
import io.agents.bqaagent.utils.XLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Serial state machine that runs a whole batch of tasks on ONE background thread.
 *
 * It reuses the single-task pipeline verbatim: each task is dispatched through
 * [TaskOrchestrator.startNewTask] with a run-time [BatchRunContext] (built fresh from [config] each
 * dispatch, never persisted), and the driver only OBSERVES the resulting [TaskEvent] stream by
 * temporarily owning [TaskOrchestrator.taskEventCallback]. Terminal
 * events settle the task; the accumulated transcript is persisted as a single batch conversation via
 * [ChatHistoryManager.save] with mode=BATCH, so a batch round-trips to the exact same markdown a chat
 * run would produce.
 *
 * Batch-specific responsibilities layered on top of the shared pipeline:
 *  - heartbeat watchdog (12 min without any event -> cancel; session stuck -> ABANDONED, halt);
 *  - inter-task reset (force-stop the foreground target app + home) and idle interval;
 *  - stop-on-failure vs keep-going;
 *  - ADB circuit breaker (a task and all remaining ones become SKIPPED_ADB once adbd stops answering);
 *  - auto-save of the matched skill template (scenario 2 only) via [SkillAutoSaver];
 *  - recording-retention protection so a task's own save pass never prunes an earlier batch recording;
 *  - a self-held renewable screen wake lock, because a batch can far outlive AppViewModel's 10-min one.
 *
 * One instance == one batch run. Call [start] once; observe [state]; [cancel] to stop early.
 */
class BatchTaskDriver(
    private val orchestrator: TaskOrchestrator,
    private val appContext: Context,
    private val conversationId: String,
    private val modelName: String,
    private val config: BatchConfig,
    private val inputs: List<BatchTaskInput>,
    /** Optional hook run right before each dispatch, mirroring AppViewModel.onBeforeTask (e.g. release a
     *  single-session local LLM the chat UI may still hold). Invoked on the driver thread. */
    private val onTaskWillStart: (() -> Unit)? = null,
    private val startIndex: Int = 0,
    private val priorMessages: List<ChatMessage> = emptyList(),
    private val priorViews: List<BatchTaskView> = emptyList(),
    /** Spans of already-completed tasks at/after [startIndex], aligned with [inputs]; a non-null entry
     *  means "skip the re-run and re-adopt this transcript" (requirement 3: Continue only re-runs unfinished tasks). */
    private val preservedSpans: List<List<ChatMessage>?> = emptyList(),
    /** Saved views of those preserved tasks, aligned with [inputs]; seeds the snapshot and is adopted on skip. */
    private val preservedViews: List<BatchTaskView?> = emptyList(),
) {

    /** One queued task: just the instruction text. HOW it executes is decided at dispatch (JIT), never stored. */
    data class BatchTaskInput(
        val text: String,
    )

    /** Immutable progress snapshot for the live batch UI (batch 5). */
    data class BatchRunSnapshot(
        val conversationId: String,
        val running: Boolean,
        val finished: Boolean,
        val cancelled: Boolean,
        val halted: Boolean,
        val haltReason: String?,
        val currentIndex: Int,
        val total: Int,
        val tasks: List<BatchTaskView>,
    )

    companion object {
        private const val TAG = "BatchTaskDriver"
        /** No event for this long => treat the task as hung. */
        private const val HANG_TIMEOUT_MS = 12 * 60 * 1000L
        /** Driver-thread poll granularity for cancel + hang detection. */
        private const val POLL_MS = 1_000L
        /** Wait for the session lock to drop after a cancel before deciding ABANDONED. */
        private const val CANCEL_GRACE_MS = 60_000L
        /** Wait for the session lock to drop after a normal settle before the next dispatch. */
        private const val SESSION_RELEASE_TIMEOUT_MS = 5_000L
        /** adbd liveness probe before each task. */
        private const val ADB_PROBE_TIMEOUT_MS = 8_000L
        private const val WAKE_LOCK_TIMEOUT_MS = 5 * 60 * 1000L
        private const val WAKE_LOCK_RENEW_MS = 4 * 60 * 1000L
        private const val SESSION_POLL_MS = 200L
        /** Give the foreground switch a moment to land before the inter-task idle gap starts. */
        private const val RETURN_TO_APP_SETTLE_MS = 1_200L
        /** Bound on waiting for the previous task's async recording stop before arming the next task. */
        private const val RECORDER_IDLE_TIMEOUT_MS = 12_000L

        /**
         * The one live batch run, process-wide. The floating pill and any foreign stop entry need a
         * handle that survives Activity recreation — the ViewModel is activity-scoped, so before this
         * existed a stop request from outside the batch screen could only ever reach the current task
         * and the batch quietly moved on to the next one.
         */
        private val activeRef = java.util.concurrent.atomic.AtomicReference<BatchTaskDriver?>(null)

        @JvmStatic
        fun active(): BatchTaskDriver? = activeRef.get()
    }


    private val _state = MutableStateFlow(
        BatchRunSnapshot(
            conversationId = conversationId,
            running = false,
            finished = false,
            cancelled = false,
            halted = false,
            haltReason = null,
            currentIndex = -1,
            total = inputs.size,
            tasks = inputs.mapIndexed { i, input ->
                preservedViews.getOrNull(i)
                    ?: priorViews.getOrNull(i)
                    ?: BatchTaskView(seq = i + 1, taskText = input.text, phase = BatchTaskPhase.PENDING)
            },
        )
    )
    val state: StateFlow<BatchRunSnapshot> = _state.asStateFlow()

    // Pending user-image upload prompt for the batch UI (null = no dialog). Set on ScreenshotBlocked,
    // cleared on UserImageProvided / any terminal event. Observed by BatchViewModel -> BatchRoot.
    private val _userImagePrompt = MutableStateFlow<BatchUserImagePrompt?>(null)
    val userImagePrompt: StateFlow<BatchUserImagePrompt?> = _userImagePrompt.asStateFlow()

    // Pending sensitive-confirm prompt for the batch UI (null = no dialog). Set on
    // SensitiveConfirmRequest, cleared on SensitiveConfirmResult / any terminal event.
    private val _sensitiveConfirmPrompt = MutableStateFlow<BatchSensitiveConfirmPrompt?>(null)
    val sensitiveConfirmPrompt: StateFlow<BatchSensitiveConfirmPrompt?> = _sensitiveConfirmPrompt.asStateFlow()

    // In-flight transcript of the currently RUNNING task, published on every pipeline event so the
    // per-task detail screen can render steps LIVE. The disk conversation is only persisted once a
    // task settles, so without this flow the detail would show just the task text while running.
    // Cleared by the driver thread between tasks; the VM clears it when the run ends.
    private val _liveTaskTranscript = MutableStateFlow<List<ChatMessage>>(emptyList())
    val liveTaskTranscript: StateFlow<List<ChatMessage>> = _liveTaskTranscript.asStateFlow()

    private val masterMessages = mutableListOf<ChatMessage>()
    private val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
    private var halted = false
    private var haltReason: String? = null
    private var driverThread: Thread? = null

    // Per-task handoff between the pipeline callback thread and the driver thread.
    private var currentBuilder: BatchTranscriptBuilder? = null
    private val terminalEvent = AtomicReference<TaskEvent?>(null)
    private val lastEventAt = AtomicLong(0L)
    @Volatile private var taskLatch: CountDownLatch? = null
    private var lastWakeRenewAt = 0L

    val isRunning: Boolean get() = _state.value.running

    // ==================== Lifecycle ====================

    fun start() {
        if (_state.value.running || _state.value.finished) return
        if (activeRef.get()?.isRunning == true) {
            XLog.w(TAG, "Another batch run is already active; start refused")
            halt("Another batch run is in progress. Stop it first.")
            finish(cancelled = false)
            return
        }
        activeRef.set(this)
        driverThread = Thread({ runBatch() }, "batch-driver").apply { isDaemon = true; start() }
    }

    /** User-initiated stop: cancel the in-flight task (if any) and end the loop after it settles. */
    fun cancel() {
        if (cancelled.getAndSet(true)) {
            // Already cancelling: still re-fire the orchestrator intent so a pill tap and a batch
            // screen tap converge on the same stop request.
            runCatching { orchestrator.cancelCurrentTask("batch-nag") }
            return
        }
        XLog.i(TAG, "Batch cancel requested")
        runCatching { orchestrator.cancelCurrentTask("batch-ui") }
    }

    // ==================== Main loop ====================

    private fun runBatch() {
        if (inputs.isEmpty()) {
            finish(cancelled = false)
            return
        }
        if (orchestrator.isTaskRunning() || orchestrator.busyNow()) {
            XLog.w(TAG, "Another task is running or still stopping; batch aborted")
            halt("Another task is running. Stop it first.")
            finish(cancelled = false)
            return
        }

        acquireWakeLock()
        // Shield this batch's recordings from per-task retention pruning; cleared on settle.
        TaskRecordingCoordinator.setRetentionProtection(emptySet())
        orchestrator.taskEventCallback = ::onPipelineEvent
        masterMessages.addAll(priorMessages)
        publish(running = true, currentIndex = startIndex)

        try {
            for (index in startIndex until inputs.size) {
                if (cancelled.get()) break

                // Requirement 3: this task already settled in a prior run — re-adopt its transcript + view
                // and move on without dispatching it again (Continue only re-runs unfinished tasks).
                if (!preservedSpans.getOrNull(index).isNullOrEmpty()) {
                    appendPreserved(index)
                    continue
                }

                publish(running = true, currentIndex = index)

                // ADB circuit breaker: once adbd stops answering, this and all remaining tasks are skipped.
                if (!adbAlive()) {
                    XLog.w(TAG, "ADB offline at task #$index — skipping it and all remaining tasks")
                    for (rest in index until inputs.size) {
                        // A preserved (already-completed) task must keep its result, not become SKIPPED_ADB.
                        if (!preservedSpans.getOrNull(rest).isNullOrEmpty()) {
                            appendPreserved(rest)
                        } else {
                            appendSkipped(rest, "⏭️ Skipped — Local ADB is offline.")
                            updateView(rest, BatchTaskPhase.SKIPPED_ADB)
                        }
                    }
                    persist()
                    halt("Local ADB went offline. Remaining tasks were skipped.")
                    returnToBatchApp()
                    break
                }

                val outcome = runOneTask(index)
                persist()

                if (outcome == TaskOutcome.ABANDONED) {
                    // Scene preserved on purpose: do not navigate, reset, or clear protection.
                    halt("A task hung and could not be cancelled. Batch stopped, scene preserved.")
                    break
                }

                // A user cancel ends the WHOLE batch: there is no next task to prepare for and the user is
                // already watching the "Stopping…" teardown on the batch screen. Break BEFORE the inter-task
                // device reset / return-to-app so the finally below (persist + finish) runs promptly —
                // finish() publishes the terminal snapshot that lets the VM collector fold results, persist,
                // and release the workspace lock. Routing through resetDevice() first could stall that
                // release behind a slow (or hung) ADB force-stop.
                if (cancelled.get()) break

                val isLast = index == inputs.lastIndex
                // Inter-task reset (force-stop the target app) only matters between tasks.
                if (!isLast && config.resetBetweenTasks) resetDevice()
                // Always come home after a settled task so the user watches its status update in the
                // batch list, then waits here before the next task launches into the target app again.
                // BQAAgent runs on the same phone it drives, so the target app is foreground during the
                // AgentLoop; the orchestrator's autoReturnToChat is deliberately off for batch tasks
                // (autoReturnToChat = batchRun == null), so the driver owns this return.
                returnToBatchApp()

                if (outcome == TaskOutcome.SETTLED_FAILED && config.stopOnFailure) {
                    halt("Stopped on first failure (stopOnFailure is on).")
                    break
                }

                if (!isLast) {
                    sleepInterruptible(config.taskIntervalSeconds * 1000L)
                }
            }
        } catch (t: Throwable) {
            XLog.e(TAG, "Batch loop crashed: ${t.message}", t)
            halt("Batch crashed: ${t.message}")
        } finally {
            // Bounded wait so the batch only reports itself finished once the orchestrator is really
            // idle. On timeout the stall is logged and surfaced as a halt reason rather than a lie.
            if (!awaitOrchestratorIdle(CANCEL_GRACE_MS)) {
                XLog.w(TAG, "Orchestrator still busy at batch teardown")
                halt(if (haltReason != null) haltReason!! else "Background task did not stop cleanly.")
            }
            persist()
            finish(cancelled = cancelled.get())
        }
    }

    private enum class TaskOutcome { SETTLED_OK, SETTLED_FAILED, HANG_TIMEOUT, ABANDONED }

    private fun runOneTask(index: Int): TaskOutcome {
        val input = inputs[index]
        val messageId = "batch_" + UUID.randomUUID().toString().replace("-", "")
        val builder = BatchTranscriptBuilder()
        builder.beginUser(input.text)
        currentBuilder = builder
        terminalEvent.set(null)
        lastEventAt.set(System.currentTimeMillis())
        val latch = CountDownLatch(1)
        taskLatch = latch

        // The previous task's recording stops asynchronously (tail frames + segment finalize can take
        // several seconds). arm() is IGNORED while the recorder is not IDLE, so without this wait the
        // 2nd and later tasks would silently record nothing.
        awaitRecorderIdle(RECORDER_IDLE_TIMEOUT_MS)

        updateView(index, BatchTaskPhase.RUNNING, startedAt = System.currentTimeMillis())
        val startedAt = System.currentTimeMillis()

        try {
            onTaskWillStart?.invoke()
            orchestrator.startNewTask(
                channel = Channel.LOCAL,
                task = input.text,
                messageID = messageId,
                batchRun = BatchRunContext(
                    preferReplay = config.preferReplay,
                    autoSaveSkill = config.autoSaveSkill,
                ),
            )

            // Heartbeat watchdog: wake every POLL_MS to check cancel + hang; the latch fires on terminal.
            var hung = false
            while (!latch.await(POLL_MS, TimeUnit.MILLISECONDS)) {
                renewWakeLockIfNeeded()
                if (cancelled.get()) {
                    runCatching { orchestrator.cancelCurrentTask() }
                    // Fall through: the cancel fires a Cancelled terminal that trips the latch.
                    if (latch.await(CANCEL_GRACE_MS, TimeUnit.MILLISECONDS)) break
                    hung = true
                    break
                }
                if (System.currentTimeMillis() - lastEventAt.get() > HANG_TIMEOUT_MS) {
                    XLog.w(TAG, "Task #$index hung (> ${HANG_TIMEOUT_MS / 60000} min with no event) — cancelling")
                    runCatching { orchestrator.cancelCurrentTask() }
                    if (latch.await(CANCEL_GRACE_MS, TimeUnit.MILLISECONDS)) {
                        hung = true // cancelled cleanly after the hang
                    } else {
                        // Session never released even after cancel => ABANDONED, preserve scene.
                        awaitSessionRelease(CANCEL_GRACE_MS)
                        if (orchestrator.isTaskRunning()) {
                            masterMessages.addAll(builder.transcript)
                            masterMessages.add(ChatMessage(ChatMessage.Role.SYSTEM, "⛔ Task hung and could not be cancelled — batch abandoned, scene preserved."))
                            updateView(index, BatchTaskPhase.ABANDONED, endedAt = System.currentTimeMillis())
                            return TaskOutcome.ABANDONED
                        }
                        hung = true
                    }
                    break
                }
            }

            val terminal = terminalEvent.get()
            val recordingId = TaskRecordingCoordinator.currentTaskRecordingId()
            val endedAt = System.currentTimeMillis()

            if (terminal == null) {
                // No terminal captured (e.g. hung then cancelled but event raced): record as hang.
                masterMessages.addAll(builder.transcript)
                masterMessages.add(ChatMessage(ChatMessage.Role.SYSTEM, "⏹️ Timed out after ${HANG_TIMEOUT_MS / 60000} minutes with no activity."))
                updateView(index, BatchTaskPhase.HANG_TIMEOUT, endedAt = endedAt, durationMs = endedAt - startedAt)
                awaitSessionRelease(SESSION_RELEASE_TIMEOUT_MS)
                return if (hung) TaskOutcome.HANG_TIMEOUT else TaskOutcome.HANG_TIMEOUT
            } else {
                // Hand the full transcript (terminal bubble included) to the live flow BEFORE the
                // between-tasks clear below, so an open detail screen never flashes back to partial
                // content in the settle gap.
                _liveTaskTranscript.value = builder.transcript
            }

            val resolved = builder.appendTerminal(terminal)
            masterMessages.addAll(builder.transcript)
            if (hung) {
                masterMessages.add(ChatMessage(ChatMessage.Role.SYSTEM, "⏹️ Timed out after ${HANG_TIMEOUT_MS / 60000} minutes; cancelled by watchdog."))
            }

            // Auto-save: a Completed event carrying skillSaveData (an AgentLoop UPDATE_SKILL or NEW_SKILL
            // run). Gated by auto-save (switch B) and SkillCaptureMode (switch A); new-vs-update is decided
            // by SkillAutoSaver from whether a skill was matched, not here.
            if (terminal is TaskEvent.Completed && terminal.skillSaveData != null &&
                config.autoSaveSkill && KVUtils.isSkillCaptureModeEnabled()
            ) {
                val saveData = terminal.skillSaveData
                // Cap guard: a NEW skill (no matched skill) would grow the library, so abandon it at the
                // cap — an unattended batch can never exceed MAX_SKILLS. Template updates are never capped.
                if (saveData.matchedSkill == null && SkillStore.getAllSkills().size >= SkillStore.MAX_SKILLS) {
                    XLog.w(TAG, "Skill cap (${SkillStore.MAX_SKILLS}) reached — abandoning new-skill auto-save")
                    masterMessages.add(
                        ChatMessage(
                            ChatMessage.Role.SYSTEM,
                            "⚠️ Skill limit (${SkillStore.MAX_SKILLS}) reached — new skill not saved"
                        )
                    )
                } else {
                    val title = SkillAutoSaver.save(saveData)
                    if (title != null) {
                        masterMessages.add(ChatMessage(ChatMessage.Role.SYSTEM, "💾 Skill saved: \"$title\""))
                    }
                }
            }

            if (recordingId != null) {
                TaskRecordingCoordinator.addToRetentionProtection(recordingId)
            }

            val failed = resolved.status == TaskStatus.FAILED || resolved.status == TaskStatus.STOPPED
            updateView(
                index,
                if (hung) BatchTaskPhase.HANG_TIMEOUT else BatchTaskPhase.SETTLED,
                taskStatus = resolved.status,
                reasonCode = resolved.reasonCode,
                recordingId = resolved.recordingId,
                endedAt = endedAt,
                durationMs = endedAt - startedAt,
                resultSummary = resolved.text.take(200),
            )

            // Wait for the session lock to actually drop before the next dispatch (Completed precedes releaseTask).
            if (!awaitOrchestratorIdle(SESSION_RELEASE_TIMEOUT_MS)) {
                XLog.w(TAG, "Orchestrator still busy after task #$index settled — skipping the next dispatch")
                // Never dispatch on top of a live loop: the engine would reject the run and the task
                // would be lost silently. Report it as an abandoned task instead.
                masterMessages.add(
                    ChatMessage(ChatMessage.Role.SYSTEM, "⏹️ Previous task did not finish stopping; halting batch to avoid overlap.")
                )
                halt("A task did not finish stopping. Batch stopped to avoid overlapping runs.")
                return TaskOutcome.ABANDONED
            }
            return when {
                hung -> TaskOutcome.HANG_TIMEOUT
                failed -> TaskOutcome.SETTLED_FAILED
                else -> TaskOutcome.SETTLED_OK
            }
        } finally {
            taskLatch = null
            currentBuilder = null
            _liveTaskTranscript.value = emptyList()
        }
    }

    // ==================== Pipeline event sink ====================

    /** Runs on the pipeline/agent thread. Feed non-terminals to the builder; capture terminals for the driver. */
    private fun onPipelineEvent(event: TaskEvent) {
        lastEventAt.set(System.currentTimeMillis())
        when (event) {
            is TaskEvent.Completed, is TaskEvent.Failed, is TaskEvent.Stopped, is TaskEvent.Cancelled -> {
                // A terminal settles the task: drop any pending upload prompt so a stale dialog can never
                // linger over the next task (e.g. the wait was cancelled or the task failed meanwhile).
                _userImagePrompt.value = null
                _sensitiveConfirmPrompt.value = null
                terminalEvent.set(event)
                taskLatch?.countDown()
            }
            is TaskEvent.ScreenshotBlocked -> {
                // Surface the upload dialog to the batch UI. The transcript line is still recorded below.
                val idx = _state.value.currentIndex
                _userImagePrompt.value = BatchUserImagePrompt(
                    taskSeq = idx + 1,
                    taskText = inputs.getOrNull(idx)?.text.orEmpty(),
                    intent = event.intent,
                    deadlineMs = System.currentTimeMillis() + event.timeoutMs,
                )
                currentBuilder?.onEvent(event)
            }
            is TaskEvent.UserImageProvided -> {
                // Uploaded / skipped / timed out — the orchestrator has already resumed; hide the dialog.
                _userImagePrompt.value = null
                currentBuilder?.onEvent(event)
            }
            is TaskEvent.SensitiveConfirmRequest -> {
                // Surface the confirmation dialog to the batch UI; transcript line recorded below.
                val idx = _state.value.currentIndex
                _sensitiveConfirmPrompt.value = BatchSensitiveConfirmPrompt(
                    taskSeq = idx + 1,
                    taskText = inputs.getOrNull(idx)?.text.orEmpty(),
                    actionSummary = event.actionSummary,
                    riskType = event.riskType,
                    deadlineMs = System.currentTimeMillis() + event.timeoutMs,
                )
                currentBuilder?.onEvent(event)
            }
            is TaskEvent.SensitiveConfirmResult -> {
                // Approved / denied / timed out — the orchestrator has already resumed; hide the dialog.
                _sensitiveConfirmPrompt.value = null
                currentBuilder?.onEvent(event)
            }
            else -> currentBuilder?.onEvent(event)
        }
        // Surface the freshly-updated in-flight transcript to the live detail view.
        currentBuilder?.let { _liveTaskTranscript.value = it.transcript }
    }

    // ==================== Device / ADB helpers ====================

    private fun adbAlive(): Boolean {
        if (!LocalAdbAutomation.hasConnectionConfig()) return false
        return LocalAdbAutomation.awaitReady(ADB_PROBE_TIMEOUT_MS)
    }

    /** Force-stop whatever target app is in front (never BQAAgent itself); returnToBatchApp then brings BQAAgent back. */
    private fun resetDevice() {
        try {
            val pkg = runCatching { LocalAdbDeviceDriver.foregroundPackageName() }.getOrNull()
            if (!pkg.isNullOrBlank() && pkg != appContext.packageName) {
                LocalAdbDeviceDriver.closeApp(pkg)
            }
        } catch (e: Exception) {
            XLog.w(TAG, "resetDevice failed: ${e.message}")
        }
    }

    /**
     * Bring BQAAgent (the batch screen) back to the foreground after a task settles, so the user sees
     * the terminal status in the task list and waits here before the next task launches into the target
     * app again. Mirrors TaskOrchestrator.autoReturnToChatIfNeeded (disabled for batch tasks) and reuses
     * the live BatchActivity via SINGLE_TOP so the ViewModel/driver are never recreated mid-run.
     */
    private fun returnToBatchApp() {
        try {
            val intent = android.content.Intent(appContext, BatchActivity::class.java).apply {
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            appContext.startActivity(intent)
            Thread.sleep(RETURN_TO_APP_SETTLE_MS)
        } catch (e: Exception) {
            XLog.w(TAG, "returnToBatchApp failed: ${e.message}")
        }
    }

    /**
     * Wait until the orchestrator has no session, no engine run and no live worker thread. The old
     * wait keyed off the session lock alone, which the cancel released BEFORE the loop was
     * interrupted, so a "settled" task could still be driving the device underneath the next dispatch.
     */
    private fun awaitOrchestratorIdle(timeoutMs: Long): Boolean =
        orchestrator.awaitIdle(timeoutMs)

    private fun awaitSessionRelease(timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (orchestrator.isTaskRunning() && System.currentTimeMillis() < deadline) {
            if (!sleepInterruptible(SESSION_POLL_MS)) return
        }
        awaitOrchestratorIdle((deadline - System.currentTimeMillis()).coerceAtLeast(0L))
    }

    /** Block until the recorder finishes stopping the previous task (or the timeout / a cancel hits). */
    private fun awaitRecorderIdle(timeoutMs: Long) {
        if (!TaskRecordingCoordinator.isEnabled()) return
        val deadline = System.currentTimeMillis() + timeoutMs
        while (TaskRecordingCoordinator.phaseSnapshot() != RecordingPhase.IDLE &&
            System.currentTimeMillis() < deadline) {
            if (!sleepInterruptible(SESSION_POLL_MS)) return
        }
    }

    /** Sleep in one shot; returns false if the batch was cancelled mid-sleep. */
    private fun sleepInterruptible(ms: Long): Boolean {
        if (ms <= 0) return !cancelled.get()
        val deadline = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < deadline) {
            if (cancelled.get()) return false
            val slice = (deadline - System.currentTimeMillis()).coerceIn(1, POLL_MS)
            try {
                Thread.sleep(slice)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return !cancelled.get()
    }

    // ==================== Wake lock (self-held, renewable) ====================

    private fun acquireWakeLock() {
        ScreenKeepAliveManager.acquire(appContext)
        lastWakeRenewAt = System.currentTimeMillis()
        XLog.i(TAG, "Batch wake lock acquired")
    }

    private fun renewWakeLockIfNeeded() {
        if (System.currentTimeMillis() - lastWakeRenewAt < WAKE_LOCK_RENEW_MS) return
        // IMPORTANT: must call renew(), NOT acquire() — acquire() would bump
        // ScreenKeepAliveManager's refCount without a matching release, leaking
        // the screen_off_timeout override permanently.
        ScreenKeepAliveManager.renew()
        lastWakeRenewAt = System.currentTimeMillis()
        XLog.d(TAG, "Batch wake lock renewed")
    }

    private fun releaseWakeLock() {
        ScreenKeepAliveManager.release()
        XLog.i(TAG, "Batch wake lock released")
    }

    // ==================== State / persistence ====================

    private fun appendSkipped(index: Int, note: String) {
        val input = inputs[index]
        val builder = BatchTranscriptBuilder()
        builder.beginUser(input.text)
        masterMessages.addAll(builder.transcript)
        masterMessages.add(ChatMessage(ChatMessage.Role.SYSTEM, note))
    }

    /**
     * Re-adopt an already-completed task on a "Continue" run (requirement 3): append its preserved span to
     * [masterMessages] (keeping the conversation index-aligned for groupTaskSpans) and adopt its saved view,
     * so a succeeded/failed task is never re-dispatched. Falls back to a description-only USER message if the
     * span is somehow empty, mirroring [appendSkipped], so the task can never vanish from the file.
     */
    private fun appendPreserved(index: Int) {
        val span = preservedSpans.getOrNull(index)
        if (!span.isNullOrEmpty()) {
            masterMessages.addAll(span)
        } else {
            val input = inputs[index]
            val builder = BatchTranscriptBuilder()
            builder.beginUser(input.text)
            masterMessages.addAll(builder.transcript)
        }
        preservedViews.getOrNull(index)?.let { view ->
            val views = _state.value.tasks.toMutableList()
            views[index] = view
            _state.value = _state.value.copy(tasks = views)
        }
    }

    private fun updateView(
        index: Int,
        phase: BatchTaskPhase,
        taskStatus: TaskStatus? = null,
        reasonCode: String? = null,
        recordingId: String? = null,
        startedAt: Long? = null,
        endedAt: Long? = null,
        durationMs: Long? = null,
        resultSummary: String? = null,
    ) {
        val views = _state.value.tasks.toMutableList()
        val prev = views[index]
        views[index] = prev.copy(
            phase = phase,
            taskStatus = taskStatus ?: prev.taskStatus,
            reasonCode = reasonCode ?: prev.reasonCode,
            recordingId = recordingId ?: prev.recordingId,
            startedAt = startedAt ?: prev.startedAt,
            endedAt = endedAt ?: prev.endedAt,
            durationMs = durationMs ?: prev.durationMs,
            resultSummary = resultSummary ?: prev.resultSummary,
        )
        _state.value = _state.value.copy(tasks = views)
    }

    private fun persist() {
        if (masterMessages.isEmpty()) return
        try {
            ChatHistoryManager.save(
                context = appContext,
                conversationId = conversationId,
                messages = masterMessages.toList(),
                model = modelName,
                mode = BatchUiMode.BATCH,
                batchConfig = config,
            )
        } catch (e: Exception) {
            XLog.e(TAG, "persist failed: ${e.message}", e)
        }
    }

    private fun publish(running: Boolean, currentIndex: Int) {
        _state.value = _state.value.copy(running = running, currentIndex = currentIndex)
    }

    private fun halt(reason: String) {
        halted = true
        haltReason = reason
    }

    private fun finish(cancelled: Boolean) {
        orchestrator.taskEventCallback = null
        activeRef.compareAndSet(this, null)
        // Defensive: never leave an upload dialog hanging after the whole run ends.
        _userImagePrompt.value = null
        // Publish the terminal snapshot FIRST. The VM collector keys off `finished` to fold results,
        // persist, and release the workspace lock ("Stopping…" → editable). Publishing before the cleanup
        // below guarantees the lock always releases even if a cleanup step were to throw or block.
        _state.value = _state.value.copy(
            running = false,
            finished = true,
            cancelled = cancelled,
            halted = halted,
            haltReason = haltReason,
            currentIndex = -1,
        )
        runCatching { TaskRecordingCoordinator.clearRetentionProtection() }
        runCatching { releaseWakeLock() }
        XLog.i(TAG, "Batch finished: cancelled=$cancelled halted=$halted reason=$haltReason tasks=${inputs.size}")
    }
}