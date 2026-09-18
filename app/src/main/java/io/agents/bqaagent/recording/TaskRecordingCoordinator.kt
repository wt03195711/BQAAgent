// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.recording

import android.content.Context
import android.os.PowerManager
import io.agents.bqaagent.ClawApplication
import io.agents.bqaagent.adb.LocalAdbAutomation
import io.agents.bqaagent.utils.KVUtils
import io.agents.bqaagent.utils.XLog
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Task-scoped screen recording.
 *
 * Contract with the rest of the app:
 *  - every public method is non-blocking (arm/start/stop only post to [scheduler]);
 *  - when the switch is off (default) nothing here touches the device at all;
 *  - any recording failure downgrades to "no recording" and never propagates to the task.
 */
object TaskRecordingCoordinator {

    private const val TAG = "TaskRecording"

    private const val TICK_INTERVAL_MS = 1_000L
    private const val SAFE_ROTATE_MS = 120_000L
    private const val FORCE_ROTATE_MS = 165_000L
    private const val DEVICE_ACTIVE_COOLDOWN_MS = 2_500L
    private const val TAIL_FRAME_MS = 800L
    private const val CHAT_RESUME_WAIT_MS = 3_000L
    private const val WAKE_LOCK_TIMEOUT_MS = 5 * 60 * 1000L
    private const val WAKE_LOCK_RENEW_MS = 4 * 60 * 1000L
    private const val MAX_SEGMENTS = 400
    /** Liveness costs a shell round trip; poll it 10x less often than the tick. */
    private const val LIVENESS_POLL_MS = 10_000L
    /** Consecutive real misses required before declaring the recorder dead. */
    private const val LIVENESS_MISS_THRESHOLD = 2
    /**
     * Reuse a full verification for this window instead of re-launching screenrecord on every toggle.
     * Short enough that a genuinely broken channel is re-detected soon after it expires; long enough
     * to make "turn it off then on again while debugging" instant.
     */
    private const val REUSE_VERIFICATION_TTL_MS = 10 * 60_000L

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "task-recording").apply { isDaemon = true }
    }

    private val manifestLock = Any()

    @Volatile private var phase = RecordingPhase.IDLE
    @Volatile private var armedMessageId = ""
    @Volatile private var armedTaskText = ""
    @Volatile private var armedChannel = ""
    @Volatile private var chatResumed = false
    @Volatile private var unsafeUntil = 0L
    @Volatile private var lastWakeLockTouch = 0L

    @Volatile private var recordingDir: File? = null
    @Volatile private var manifest: TaskRecordingManifest? = null
    @Volatile private var support: AdbScreenRecorder.Support? = null
    @Volatile private var activePid = 0
    @Volatile private var activeSegmentFile: File? = null
    @Volatile private var activeSegmentStartedAt = 0L
    @Volatile private var activeSegmentIndex = -1
    @Volatile private var tickFuture: ScheduledFuture<*>? = null
    @Volatile private var stopRequested = false
    @Volatile private var lastLivenessAt = 0L
    @Volatile private var lastAlive = true
    /** Consecutive liveness misses where the lookup actually ran. A failed lookup does not count. */
    @Volatile private var livenessMissCount = 0
    @Volatile private var firstLivenessMissAt = 0L
    @Volatile var lastAbortReason: String? = null
        private set

    /**
     * Recording belonging to the task that is armed right now, or the one that just finished.
     *
     * Deliberately survives [reset]: TaskEvent.Completed reaches the UI through
     * `activity.runOnUiThread { handleTaskEvent(it) }` (TaskFlowController L212), so the result
     * message is built on a *later* main-thread turn, by which time releaseTask() has already run
     * stop() -> doStop() -> reset() and nulled [manifest]. tick()'s total-limit / low-storage
     * branches (L463/L471) call doStop() on the scheduler thread directly, so that race is not
     * even narrow. Reading the id off the manifest would drop the binding for exactly the messages
     * that need it.
     *
     * Cleared only in [arm], which every fresh task acquisition calls (TaskOrchestrator L237), so
     * it can never leak into the next task's message; and it is set only in [doStart], so tiers
     * that never record (DirectIntent, chat, monitor) leave it null.
     */
    @Volatile private var taskRecordingId: String? = null

    private var wakeLock: PowerManager.WakeLock? = null

    // ==================== Public API (all non-blocking) ====================

    fun isEnabled(): Boolean = KVUtils.isTaskRecordingEnabled() && LocalAdbAutomation.hasConnectionConfig()

    fun phaseSnapshot(): RecordingPhase = phase

    /**
     * Called right after a task acquires the session lock. Only stores context; recording starts
     * later via [start] so that tiers we deliberately do not record (DirectIntent, monitor) never
     * touch the device. Re-arming with the same messageId is a no-op, which makes the fallback
     * re-entry paths (Skill -> AgentLoop, Replay -> AgentLoop) safe.
     */
    fun arm(messageId: String, channelName: String, taskText: String) {
        // Before the isEnabled() gate: a task that does not record must never inherit the previous
        // task's id, otherwise its result message would offer a video that belongs to someone else.
        taskRecordingId = null
        if (!isEnabled()) return
        if (phase != RecordingPhase.IDLE && armedMessageId == messageId) return
        if (phase != RecordingPhase.IDLE) {
            XLog.w(TAG, "arm() ignored, recorder busy in phase=$phase for $armedMessageId")
            return
        }
        armedMessageId = messageId
        armedChannel = channelName
        armedTaskText = taskText
        phase = RecordingPhase.ARMED
        stopRequested = false
        XLog.i(TAG, "Armed for message=$messageId task=\"${taskText.take(60)}\"")
    }

    /** Called when a recorded pipeline tier actually begins executing. */
    fun start(pipelineTier: String) {
        if (phase != RecordingPhase.ARMED) return
        scheduler.execute {
            try {
                doStart(pipelineTier)
            } catch (e: Throwable) {
                XLog.e(TAG, "doStart failed, recording disabled for this task", e)
                abort("start-failed: ${e.message}")
            }
        }
    }

    /** Device is about to be touched — defer segment rotation out of this window. */
    fun markDeviceActive() {
        unsafeUntil = System.currentTimeMillis() + DEVICE_ACTIVE_COOLDOWN_MS
    }

    /** Device is idle (LLM thinking / streaming) — rotation may happen now. */
    fun markDeviceIdle() {
        unsafeUntil = 0L
    }

    fun addMarker(label: String, detail: String = "") {
        val m = manifest ?: return
        val base = m.startedAt
        if (base <= 0L) return
        synchronized(manifestLock) {
            if (m.stepMarkers.size >= 500) return
            m.stepMarkers.add(
                RecordingStepMarker(
                    offsetMs = System.currentTimeMillis() - base,
                    label = label.take(60),
                    detail = detail.take(200)
                )
            )
        }
    }

    /** Called from every releaseTask() path. Idempotent and non-blocking. */
    fun stop(outcome: RecordingOutcome) {
        if (phase == RecordingPhase.IDLE || phase == RecordingPhase.STOPPING) return
        stopRequested = true
        scheduler.execute {
            try {
                doStop(outcome)
            } catch (e: Throwable) {
                XLog.e(TAG, "doStop failed", e)
                abort("stop-failed: ${e.message}")
            }
        }
    }

    fun onChatResumed() { chatResumed = true }

    /** Id of the recording for the current/just-finished task, or null when it was not recorded. */
    fun currentTaskRecordingId(): String? = taskRecordingId

    fun onChatPaused() { chatResumed = false }

    fun reconcileAsync() {
        scheduler.execute {
            try {
                reconcile()
            } catch (e: Throwable) {
                XLog.w(TAG, "Reconcile failed: ${e.message}")
            }
        }
    }

    /** Outcome of [verifyDevice]. `reasonCode` is machine-readable; `humanReason` is showable as-is. */
    data class DeviceVerdict(
        val usable: Boolean,
        val reasonCode: String,
        val humanReason: String,
        val elapsedMs: Long,
        val overlap: Boolean = false,
        val wrapper: String = ""
    )

    /**
    * One-shot device verification used as the recording gate. Probes in increasing order of risk so
    * the cheap, unambiguous failures short-circuit before anything heavy runs. Runs on a background
    * thread. [onProgress] is invoked on the calling (background) thread before each stage so the UI
    * can show live progress; the default is a no-op for callers that do not care (e.g. preWarmAsync).
    */
    fun verifyDevice(onProgress: (step: Int, total: Int, message: String) -> Unit = { _, _, _ -> }): DeviceVerdict {
        val startedAt = System.currentTimeMillis()
        fun verdict(
            usable: Boolean,
            code: String,
            human: String,
            overlap: Boolean = false,
            wrapper: String = ""
        ) = DeviceVerdict(usable, code, human, System.currentTimeMillis() - startedAt, overlap, wrapper)

        // Fast path: this exact device passed the full gate moments ago and the configuration is still
        // cached. A fresh probe costs 10s–3min of real screenrecord launches, so inside the TTL we trust
        // the previous verdict and toggle instantly. hasConnectionConfig() is instant and still catches
        // "ADB was turned off since"; anything deeper is re-checked once the TTL expires.
        val verifiedAt = KVUtils.getTaskRecordingVerifiedAt()
        if (REUSE_VERIFICATION_TTL_MS > 0 &&
            LocalAdbAutomation.hasConnectionConfig() &&
            AdbScreenRecorder.isConfigForThisDevice() &&
            AdbScreenRecorder.cachedWrapper().isNotBlank() &&
            verifiedAt > 0L &&
            System.currentTimeMillis() - verifiedAt < REUSE_VERIFICATION_TTL_MS
        ) {
            XLog.i(TAG, "verifyDevice: reusing verification from ${(System.currentTimeMillis() - verifiedAt) / 1000}s ago")
            onProgress(1, 1, "Reusing recent verification for this device")
            return verdict(true, "ok", "Recording works on this device.",
                KVUtils.isTaskRecordingOverlapEnabled(),
                KVUtils.getTaskRecordingWrapper().ifBlank { "none" })
        }

        if (!LocalAdbAutomation.hasConnectionConfig()) {
            return verdict(false, "adb-not-configured",
                "Local ADB is not connected. Enable Wireless debugging and pair BQAAgent first.")
        }
        onProgress(1, 2, "Connecting to local ADB…")
        // hasConnectionConfig() only proves a host/port was saved once; it says nothing about whether
        // adbd answers now. Skip the echo probe only when one succeeded <15s ago (READY_CACHE_TTL_MS),
        // otherwise actually confirm the channel is live before probing anything heavier.
        if (!LocalAdbAutomation.hasRecentReadyProbe() && !LocalAdbAutomation.awaitReady(8_000L)) {
            return verdict(false, "adb-unreachable",
                "Local ADB did not respond. Reconnect it under Permissions, then try again.")
        }
        // Same lock the self-test uses: both flows locate the pid by diffing `pidof` around a launch,
        // so if they overlap each one can adopt — or kill — the other's process.
        if (!AdbScreenRecorder.tryAcquireProbe("verify")) {
            return verdict(false, "probe-busy",
                "Another recording probe is still running. Wait a moment and try again.")
        }
        try {
            if (phase != RecordingPhase.IDLE) {
                return verdict(false, "recorder-busy",
                    "A recording is in progress. Try again once it has finished.")
            }
            val probe = AdbScreenRecorder.probeSupport().also { support = it }
            if (!probe.available) {
                return verdict(false, "screenrecord-unavailable",
                    "This device's system does not provide screen recording (no usable `screenrecord`).")
            }
            if (!TaskRecordingStore.hasRoomForRecording()) {
                return verdict(false, "low-storage",
                    "Less than 300MB of free space. Free up storage and try again.")
            }
            val dir = TaskRecordingStore.createProbeDir()
                ?: return verdict(false, "no-dir",
                    "Cannot create the recording folder on external storage.")

            AdbScreenRecorder.resetTransportPreference()
            AdbScreenRecorder.resetSizeLevel()
            KVUtils.setTaskRecordingSizeLevel(0)
            KVUtils.setTaskRecordingWrapper("")

            onProgress(2, 2, "Testing background recording (about 10–30 seconds)…")
            val wrapper = AdbScreenRecorder.detectWrapper(
                dir, AdbScreenRecorder.effectiveTimeLimitSec(probe)
            )
            if (wrapper == null) {
                runCatching { dir.deleteRecursively() }
                return verdict(false, "no-background-wrapper",
                    "Recording cannot stay alive in the background on this device. Tried setsid, " +
                            "nohup and a plain background launch; none produced a playable video.")
            }

            // probeOverlap (two concurrent encoders, ~7s typical / ~38s worst) only refines whether
            // rotation is near-seamless or leaves a ~1s seam — recording works either way — so it no
            // longer blocks the gate. Keep the last persisted value now and re-probe in the background.
            val overlapOk = KVUtils.isTaskRecordingOverlapEnabled()
            AdbScreenRecorder.markVerifiedOnThisDevice()
            runCatching { dir.deleteRecursively() }

            val elapsed = System.currentTimeMillis() - startedAt
            XLog.i(TAG, "Device verified in ${elapsed}ms wrapper='${wrapper.ifBlank { "none" }}' overlap=$overlapOk (deferred re-probe queued)")
            probeOverlapAsync(wrapper)
            return verdict(true, "ok", "Recording works on this device.",
                overlapOk, wrapper.ifBlank { "none" })
        } catch (e: Throwable) {
            XLog.w(TAG, "verifyDevice failed: ${e.message}", e)
            return verdict(false, "verify-crashed",
                "Verification failed: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            AdbScreenRecorder.releaseProbe("verify")
        }
    }

    /**
     * Refines the near-seamless-rotation flag off the interactive path. Runs on the single scheduler
     * thread, so it is serialised against doStart()/rotate() and can never stack encoders onto a live
     * recording; the phase guards bail the instant a task arms. verifyDevice() releases the probe lock
     * in its finally microseconds after queuing this, so wait briefly (≤1s) for it instead of skipping.
     */
    private fun probeOverlapAsync(wrapper: String) {
        scheduler.execute {
            val deadline = System.currentTimeMillis() + 1_000L
            var acquired = false
            while (System.currentTimeMillis() < deadline) {
                if (phase != RecordingPhase.IDLE) return@execute
                if (AdbScreenRecorder.tryAcquireProbe("overlap")) { acquired = true; break }
                Thread.sleep(100)
            }
            if (!acquired) return@execute
            try {
                if (phase != RecordingPhase.IDLE) return@execute
                val dir = TaskRecordingStore.createProbeDir() ?: return@execute
                val ok = AdbScreenRecorder.probeOverlap(dir, wrapper)
                KVUtils.setTaskRecordingOverlapEnabled(ok)
                runCatching { dir.deleteRecursively() }
                XLog.i(TAG, "Deferred overlap probe finished: overlap=$ok")
            } catch (e: Throwable) {
                XLog.w(TAG, "Deferred overlap probe failed: ${e.message}")
            } finally {
                AdbScreenRecorder.releaseProbe("overlap")
            }
        }
    }

    /** Machine code -> something a user can act on. Keep in sync with every abort() call site. */
    fun humanReason(code: String?): String = when {
        code.isNullOrBlank() -> ""
        code == "adb-not-configured" -> "Local ADB is not connected."
        code == "adb-unreachable" -> "Local ADB did not respond."
        code == "screenrecord-unavailable" -> "This device's system does not provide screen recording."
        code == "no-background-wrapper" -> "Recording cannot stay alive in the background on this device."
        code == "no-dir" -> "Cannot create the recording folder on external storage."
        code == "low-storage" -> "Less than 300MB of free space."
        code == "first-segment-failed" -> "The recorder refused to start for this task."
        code == "disabled" -> "Task Recording is switched off."
        code == "probe-busy" -> "Another recording probe is still running."
        code == "recorder-busy" -> "A recording is already in progress."
        code == "total-limit" -> "Recording stopped: the total time limit was reached."
        code == "process-died" -> "Recording stopped early: the recorder process died."
        code.startsWith("start-failed") -> "Recording failed to start:${code.substringAfter("start-failed:")}"
        code.startsWith("stop-failed") -> "Recording failed to stop cleanly:${code.substringAfter("stop-failed:")}"
        else -> code
    }

    /** Everything worth sending when someone reports "recording does not work on my phone". */
    fun diagnostics(): String = buildString {
        append("enabled=").append(KVUtils.isTaskRecordingEnabled()).append('\n')
        append("phase=").append(phase).append('\n')
        append("lastAbortReason=").append(lastAbortReason ?: "-").append('\n')
        append("transport=").append(AdbScreenRecorder.transportName).append('\n')
        append("configForThisDevice=").append(AdbScreenRecorder.isConfigForThisDevice()).append('\n')
        append("thisDevice=").append(TaskRecordingStore.deviceKey()).append('\n')
        append("verifiedDevice=").append(KVUtils.getTaskRecordingVerifiedDevice().ifBlank { "-" }).append('\n')
        append("verifiedAt=").append(KVUtils.getTaskRecordingVerifiedAt()).append('\n')
        append("wrapper='").append(KVUtils.getTaskRecordingWrapper().ifBlank { "none" }).append("'\n")
        append("overlap=").append(KVUtils.isTaskRecordingOverlapEnabled()).append('\n')
        append("sizeLevel=").append(AdbScreenRecorder.currentSizeLevel()).append('\n')
        append("pidLookup=").append(AdbScreenRecorder.pidLookupNote.ifBlank { "-" }).append('\n')
        append("overlapNote=").append(AdbScreenRecorder.overlapNote.ifBlank { "-" }).append('\n')
        AdbScreenRecorder.lastDetectTrail.forEach { append("wrapperProbe: ").append(it).append('\n') }
    }

    // ==================== Internals (scheduler thread only) ===================

    private fun doStart(pipelineTier: String) {
        if (phase != RecordingPhase.ARMED) return
        AdbScreenRecorder.resetTransportPreference()
        AdbScreenRecorder.resetSizeLevel()
        if (!isEnabled()) { abort("disabled"); return }
        if (!TaskRecordingStore.hasRoomForRecording()) {
            abort("low-storage")
            XLog.w(TAG, "Recording skipped: less than 300MB free")
            return
        }

        val probe = support ?: AdbScreenRecorder.probeSupport().also { support = it }
        if (!probe.available) { abort("screenrecord-unavailable"); return }

        val wrapper = AdbScreenRecorder.cachedWrapper().ifBlank {
            val dir = TaskRecordingStore.createProbeDir()
            if (dir == null) { abort("no-dir"); return }
            val detected = AdbScreenRecorder.detectWrapper(dir, AdbScreenRecorder.effectiveTimeLimitSec(probe))
            runCatching { dir.deleteRecursively() }
            if (detected == null) { abort("no-background-wrapper"); return }
            detected
        }

        val dir = TaskRecordingStore.createRecordingDir()
        if (dir == null) { abort("no-dir"); return }

        val now = System.currentTimeMillis()
        val newManifest = TaskRecordingManifest(
            recordingId = dir.name,
            taskText = armedTaskText,
            messageId = armedMessageId,
            channelName = armedChannel,
            pipelineTier = pipelineTier,
            startedAt = now,
            bitRate = AdbScreenRecorder.bitRate(),
            deviceModel = TaskRecordingStore.deviceModel(),
            androidVersion = TaskRecordingStore.androidVersion(),
            appVersion = TaskRecordingStore.appVersion(),
            transport = AdbScreenRecorder.transportName,
            backgroundWrapper = wrapper.ifBlank { "none" }
        )

        recordingDir = dir
        manifest = newManifest
        taskRecordingId = newManifest.recordingId
        lastAbortReason = null
        acquireWakeLock()

        val started = launchSegment(dir, 0, "task-start", newManifest)
        if (!started) {
            // The configuration we trusted just failed on its first real use. Clear it, otherwise
            // doStart() (L207) would keep reusing a cache that only refreshes when blank, and this
            // device would stay marked "verified" while every future task silently recorded nothing.
            KVUtils.clearTaskRecordingVerification()
            KVUtils.setTaskRecordingWrapper("")
            abort("first-segment-failed")
            return
        }

        phase = RecordingPhase.RECORDING
        startTicking()
        addMarker("start", pipelineTier)
        XLog.i(TAG, "Recording started id=${newManifest.recordingId} tier=$pipelineTier " +
                "transport=${AdbScreenRecorder.transportName} wrapper=${newManifest.backgroundWrapper} " +
                "size=${newManifest.width}x${newManifest.height}")
    }

    private fun launchSegment(dir: File, index: Int, reason: String, m: TaskRecordingManifest): Boolean {
        if (index >= MAX_SEGMENTS) {
            XLog.w(TAG, "Segment cap reached ($MAX_SEGMENTS), stopping rotation")
            return false
        }
        val outFile = TaskRecordingStore.segmentFile(dir, index)
        val logFile = TaskRecordingStore.segmentLogFile(dir, index)
        val sizeArg = AdbScreenRecorder.resolveSizeArg()
        val timeLimit = AdbScreenRecorder.effectiveTimeLimitSec(support)
        val wrapper = m.backgroundWrapper.let { if (it == "none") "" else it }

        val start = AdbScreenRecorder.startSegment(outFile, logFile, sizeArg, timeLimit, wrapper)
        if (!start.success) {
            XLog.w(TAG, "Segment $index failed to start: blocked=${start.blocked} out=${start.output}")
            // One retry with the next size level, then give up on recording for this task.
            if (AdbScreenRecorder.degradeSizeLevelRuntime() >= 2) return false
            val retrySize = AdbScreenRecorder.resolveSizeArg()
            val retry = AdbScreenRecorder.startSegment(outFile, logFile, retrySize, timeLimit, wrapper)
            if (!retry.success) return false
            activePid = retry.pid
        } else {
            activePid = start.pid
        }

        activeSegmentFile = outFile
        activeSegmentIndex = index
        activeSegmentStartedAt = System.currentTimeMillis()
        lastLivenessAt = activeSegmentStartedAt
        lastAlive = true
        livenessMissCount = 0
        firstLivenessMissAt = 0L

        synchronized(manifestLock) {
            m.segments.add(
                RecordingSegmentInfo(
                    index = index,
                    fileName = outFile.name,
                    startedAt = activeSegmentStartedAt,
                    pid = activePid,
                    rotationReason = reason
                )
            )
            m.segmentCount = m.segments.size
        }
        return true
    }

    private fun startTicking() {
        tickFuture?.cancel(false)
        tickFuture = scheduler.scheduleWithFixedDelay(
            {
                try {
                    tick()
                } catch (e: Throwable) {
                    XLog.w(TAG, "tick failed: ${e.message}")
                }
            },
            TICK_INTERVAL_MS, TICK_INTERVAL_MS, TimeUnit.MILLISECONDS
        )
    }

    private fun tick() {
        if (phase != RecordingPhase.RECORDING) return
        val m = manifest ?: return
        val dir = recordingDir ?: return

        renewWakeLockIfNeeded()

        val totalMs = System.currentTimeMillis() - m.startedAt
        val maxTotalMs = KVUtils.getTaskRecordingMaxTotalMinutes().let {
            (if (it in 1..600) it else 30) * 60_000L
        }
        if (totalMs >= maxTotalMs) {
            XLog.i(TAG, "Total recording limit reached (${totalMs / 1000}s), stopping")
            m.abortedReason = "total-limit"
            stopRequested = true
            doStop(RecordingOutcome.UNKNOWN)
            return
        }

        if (!TaskRecordingStore.hasRoomForRecording()) {
            XLog.w(TAG, "Free space dropped below 300MB, stopping recording")
            m.abortedReason = "low-storage"
            stopRequested = true
            doStop(RecordingOutcome.UNKNOWN)
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastLivenessAt >= LIVENESS_POLL_MS) {
            lastLivenessAt = now
            val lookup = AdbScreenRecorder.pidsOfScreenRecordDetailed()
            when {
                // The shell call never completed. A busy, reset or timing-out channel says nothing
                // about whether screenrecord is alive, so keep the previous verdict. Treating this
                // as a miss used to rotate a perfectly healthy segment and then SIGINT it, losing
                // the tail — and it wrote "process-died" into the manifest, so every later
                // diagnosis was reading a lie.
                !lookup.lookupRan -> XLog.w(
                    TAG, "Liveness lookup did not run (channel busy); keeping lastAlive=$lastAlive"
                )
                activePid > 0 && activePid in lookup.pids -> {
                    lastAlive = true
                    livenessMissCount = 0
                    firstLivenessMissAt = 0L
                }
                else -> {
                    lastAlive = false
                    livenessMissCount++
                    if (firstLivenessMissAt == 0L) firstLivenessMissAt = now
                }
            }
        }
        val age = now - activeSegmentStartedAt
        val reason = when {
            // Two consecutive real misses spanning at least one full poll interval. `age` is the
            // segment's age, not the time since the first miss, so it cannot gate this.
            !lastAlive && livenessMissCount >= LIVENESS_MISS_THRESHOLD &&
                    firstLivenessMissAt > 0L &&
                    now - firstLivenessMissAt >= LIVENESS_POLL_MS -> "process-died"
            age >= FORCE_ROTATE_MS -> "forced-165s"
            age >= SAFE_ROTATE_MS && now >= unsafeUntil -> "safe-window"
            else -> null
        } ?: return

        rotate(dir, m, reason)
    }

    private fun rotate(dir: File, m: TaskRecordingManifest, reason: String) {
        val previousPid = activePid
        val previousFile = activeSegmentFile
        val previousIndex = activeSegmentIndex
        val overlap = AdbScreenRecorder.isConfigForThisDevice() && KVUtils.isTaskRecordingOverlapEnabled()

        if (overlap) {
            // Near-seamless: the next segment is already capturing before the old one is stopped.
            val launched = launchSegment(dir, previousIndex + 1, reason, m)
            finalizeSegment(m, previousIndex, previousFile, previousPid)
            if (!launched) {
                XLog.w(TAG, "Overlap rotation failed to launch segment ${previousIndex + 1}")
            }
        } else {
            finalizeSegment(m, previousIndex, previousFile, previousPid)
            val launched = launchSegment(dir, previousIndex + 1, reason, m)
            if (!launched) XLog.w(TAG, "Sequential rotation failed at segment ${previousIndex + 1}")
        }
        XLog.i(TAG, "Rotated segment ($reason), now at index $activeSegmentIndex")
    }

    private fun finalizeSegment(m: TaskRecordingManifest, index: Int, file: File?, pid: Int) {
        if (pid > 0) AdbScreenRecorder.stopSegment(pid)
        if (file == null) return
        // screenrecord runs as uid `shell` and the resulting inode stays 0600 shell:shell, so this
        // process can stat() it but not open() it. Without this chmod probeVideo() returns null for
        // a perfectly good recording, which marks the segment invalid and deletes it — and doStop()
        // then discards the whole directory. `umask 0022` in startSegment is not sufficient; the
        // self-test proved that empirically (check 07 passed with chmod, check 10 failed without).
        AdbScreenRecorder.makeReadable(file)
        val size = AdbScreenRecorder.awaitFileSettled(file)
        val probe = TaskRecordingStore.probeVideo(file)
        synchronized(manifestLock) {
            val segment = m.segments.firstOrNull { it.index == index } ?: return
            segment.endedAt = System.currentTimeMillis()
            segment.sizeBytes = size
            segment.durationMs = probe?.durationMs ?: 0L
            segment.width = probe?.width ?: 0
            segment.height = probe?.height ?: 0
            segment.valid = probe != null && size > 0
            if (probe != null) {
                m.width = probe.width
                m.height = probe.height
            }
            if (!segment.valid) {
                XLog.w(TAG, "Segment $index is not playable (${file.name}, $size bytes)")
                runCatching { file.delete() }
            }
        }
    }

    private fun doStop(outcome: RecordingOutcome) {
        if (phase != RecordingPhase.RECORDING && phase != RecordingPhase.ARMED) {
            phase = RecordingPhase.IDLE
            return
        }
        phase = RecordingPhase.STOPPING
        tickFuture?.cancel(false)
        tickFuture = null

        val m = manifest
        val dir = recordingDir

        if (m == null || dir == null || m.segments.isEmpty()) {
            // ARMED but never started (DirectIntent, monitor, gated out) — nothing on disk.
            runCatching { dir?.deleteRecursively() }
            reset()
            return
        }

        // The user explicitly stopped the task: kill the recorder and discard the partial footage
        // instead of saving it. An explicit cancel means "abandon this run", mirroring
        // SkillRecorder.discard(), which already throws the skill capture away on cancel. Skips the
        // chat-resume wait / tail frames (pointless when the footage is discarded) and the manifest
        // write. Only CANCELLED is treated this way — FAILED and BLOCKED are involuntary and keep
        // their footage for diagnosis (they are also the outcomes that attach a "View recording" chip,
        // whereas TaskFlowController's Cancelled branch attaches none, so no dead link is left behind).
        if (outcome == RecordingOutcome.CANCELLED) {
            finalizeSegment(m, activeSegmentIndex, activeSegmentFile, activePid)
            XLog.i(TAG, "Task cancelled by user, discarding recording ${dir.name}")
            runCatching { dir.deleteRecursively() }
            reset()
            return
        }

        // Let the return-to-chat transition be captured before cutting the last segment.
        val resumeDeadline = System.currentTimeMillis() + CHAT_RESUME_WAIT_MS
        while (!chatResumed && System.currentTimeMillis() < resumeDeadline) Thread.sleep(100)
        Thread.sleep(TAIL_FRAME_MS)

        finalizeSegment(m, activeSegmentIndex, activeSegmentFile, activePid)

        val valid = m.segments.filter { it.valid }
        synchronized(manifestLock) {
            m.outcome = outcome.name
            m.endedAt = System.currentTimeMillis()
            m.totalDurationMs = valid.sumOf { it.durationMs }
            m.totalBytes = valid.sumOf { it.sizeBytes }
            m.segmentCount = m.segments.size
            m.gapCount = countGaps(m.segments)
            m.segments.removeAll { !it.valid }
            m.segmentCount = m.segments.size
        }

        if (valid.isEmpty()) {
            XLog.w(TAG, "No playable segment produced, discarding ${dir.name}")
            runCatching { dir.deleteRecursively() }
            reset()
            return
        }

        TaskRecordingStore.writeManifest(dir, m)
        TaskRecordingStore.enforceRetention()
        releaseWakeLock()

        XLog.i(TAG, "Recording saved id=${m.recordingId} outcome=${m.outcome} " +
                "segments=${m.segmentCount} duration=${TaskRecordingStore.formatDuration(m.totalDurationMs)} " +
                "size=${TaskRecordingStore.formatBytes(m.totalBytes)} gaps=${m.gapCount} " +
                "dir=${dir.absolutePath}")
        reset()
    }

    private fun countGaps(segments: List<RecordingSegmentInfo>): Int {
        val sorted = segments.filter { it.valid }.sortedBy { it.startedAt }
        var gaps = 0
        for (i in 1 until sorted.size) {
            if (sorted[i].startedAt - sorted[i - 1].endedAt > 400L) gaps++
        }
        return gaps
    }

    private fun abort(reason: String) {
        lastAbortReason = reason
        XLog.i(TAG, "Recording not started/aborted: $reason")
        runCatching { recordingDir?.deleteRecursively() }
        reset()
    }

    private fun reset() {
        phase = RecordingPhase.IDLE
        recordingDir = null
        manifest = null
        activePid = 0
        activeSegmentFile = null
        activeSegmentIndex = -1
        activeSegmentStartedAt = 0L
        armedMessageId = ""
        armedTaskText = ""
        armedChannel = ""
        unsafeUntil = 0L
        stopRequested = false
        lastLivenessAt = 0L
        lastAlive = true
        livenessMissCount = 0
        firstLivenessMissAt = 0L
        releaseWakeLock()
    }

    // ==================== Wake lock ====================

    /**
     * AppViewModel's automation wake lock times out after 10 minutes and is never renewed
     * (AppViewModel L116/L123), which would black out both the automation and the recording on
     * long tasks. This lock is held only while recording is active, uses exactly the same flags as
     * the existing one, and is additive — nothing changes while the recording switch is off.
     */
    private fun acquireWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                lastWakeLockTouch = System.currentTimeMillis()
                return
            }
            val pm = ClawApplication.instance.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            wakeLock = pm.newWakeLock(
                PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "BQAAgent::TaskRecordingWakeLock"
            ).apply { acquire(WAKE_LOCK_TIMEOUT_MS) }
            lastWakeLockTouch = System.currentTimeMillis()
            XLog.i(TAG, "Recording wake lock acquired")
        } catch (e: Throwable) {
            XLog.w(TAG, "Failed to acquire recording wake lock: ${e.message}")
        }
    }

    private fun renewWakeLockIfNeeded() {
        if (System.currentTimeMillis() - lastWakeLockTouch < WAKE_LOCK_RENEW_MS) return
        acquireWakeLock()
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Throwable) {
            XLog.w(TAG, "Failed to release recording wake lock: ${e.message}")
        }
        wakeLock = null
    }

    // ==================== Crash recovery ====================

    /** Finalises recordings left behind by a killed process, then applies retention. */
    private fun reconcile() {
        val root = TaskRecordingStore.rootDir() ?: return
        val dirs = root.listFiles { f -> f.isDirectory && f.name.startsWith("rec_") } ?: return
        var repaired = 0
        for (dir in dirs) {
            // The owning process may have been killed before finalizeSegment() could chmod, leaving
            // shell-owned 0600 segments. collectValidSegments() would then reject every one of them
            // and the branch below would delete the directory instead of rescuing it.
            AdbScreenRecorder.makeDirReadable(dir)
            val m = TaskRecordingStore.readManifest(dir)
            if (m == null) {
                val segments = TaskRecordingStore.collectValidSegments(dir)
                if (segments.none { it.valid }) {
                    runCatching { dir.deleteRecursively() }
                    continue
                }
                val rebuilt = TaskRecordingManifest(
                    recordingId = dir.name,
                    outcome = RecordingOutcome.UNKNOWN.name,
                    abortedReason = "process-died",
                    startedAt = dir.lastModified(),
                    endedAt = dir.lastModified(),
                    segments = segments,
                    segmentCount = segments.size,
                    totalBytes = segments.sumOf { it.sizeBytes },
                    totalDurationMs = segments.sumOf { it.durationMs },
                    deviceModel = TaskRecordingStore.deviceModel(),
                    androidVersion = TaskRecordingStore.androidVersion(),
                    appVersion = TaskRecordingStore.appVersion()
                )
                TaskRecordingStore.writeManifest(dir, rebuilt)
                repaired++
                continue
            }
            if (m.endedAt > 0L) continue
            val segments = TaskRecordingStore.collectValidSegments(dir)
            if (segments.none { it.valid }) {
                runCatching { dir.deleteRecursively() }
                continue
            }
            m.segments = segments
            m.segmentCount = segments.size
            m.totalBytes = segments.filter { it.valid }.sumOf { it.sizeBytes }
            m.totalDurationMs = segments.filter { it.valid }.sumOf { it.durationMs }
            m.endedAt = dir.lastModified()
            m.outcome = RecordingOutcome.UNKNOWN.name
            m.abortedReason = "process-died"
            TaskRecordingStore.writeManifest(dir, m)
            repaired++
        }
        // Sweep probe folders orphaned by a process killed mid-verification. They are never listed
        // (probe_ prefix), but they do take space; a live probe finishes in seconds, so only remove
        // ones untouched for over an hour.
        runCatching {
            val cutoff = System.currentTimeMillis() - 60L * 60 * 1000
            root.listFiles { f -> f.isDirectory && f.name.startsWith("probe_") }?.forEach { probeDir ->
                if (probeDir.lastModified() < cutoff) probeDir.deleteRecursively()
            }
        }
        TaskRecordingStore.enforceRetention()
        if (repaired > 0) XLog.i(TAG, "Reconcile repaired $repaired orphaned recording(s)")
    }
}