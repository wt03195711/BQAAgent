// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.recording

import com.flyfishxu.kadb.Kadb
import io.agents.bqaagent.adb.LocalAdbAutomation
import io.agents.bqaagent.adb.ShellCommandResult
import io.agents.bqaagent.utils.KVUtils
import io.agents.bqaagent.utils.XLog
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shell layer for `screenrecord`.
 *
 * Isolation rule: recording owns its **own** [Kadb] connection so that nothing it does can
 * reset or re-timeout the client that [LocalAdbAutomation] shares with the automation pipeline.
 * [LocalAdbAutomation.exec] tears the shared client down on any Throwable (L179) and only applies
 * `socketTimeout` when the client is first created (L609) — a long-running recording command on
 * that channel could permanently degrade every automation call. Only when the dedicated
 * connection cannot be established do we fall back to the shared channel, and then strictly with
 * the automation default timeout so the shared client's configuration is never altered.
 */
object AdbScreenRecorder {

    private const val TAG = "AdbScreenRecorder"

    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val DEDICATED_SOCKET_TIMEOUT_MS = 210_000
    /** Must equal LocalAdbAutomation's default: never re-timeout the shared client. */
    private const val SHARED_EXEC_TIMEOUT_MS = 15_000L
    private const val SHORT_EXEC_TIMEOUT_MS = 5_000L

    /** Interval between pid polls while resolving a launch. */
    private const val PID_POLL_INTERVAL_MS = 150L
    /** Floor for the pid-resolution window — what the idle probe needed (6 x 150ms). */
    private const val PID_RESOLVE_MIN_MS = 900L
    /** Ceiling, so a large [startSegment.launchBoundMs] cannot stall rotation indefinitely. */
    private const val PID_RESOLVE_MAX_MS = 3_000L

    private const val TARGET_SHORT_SIDE = 720
    private const val DEFAULT_BIT_RATE = 2_000_000
    /** Just under the 180s AOSP hard limit — acts as a dead-man switch if we lose the pid. */
    const val REQUESTED_TIME_LIMIT_SEC = 178

    /** One-shot verification record duration. Short, but enough for real frames. */
    private const val PROBE_RECORD_MS = 2000L
    private const val PROBE_OVERLAP_TIMEOUT_MS = 8_000L
    /**
     * Separates `command -v screenrecord` output from `screenrecord --help` output when both are
     * fetched in a single exec. Safe because combinedOutput is always stdout-then-stderr, and both
     * `command -v` and `echo` write to stdout while `--help` writes to stderr.
     */
    private const val HELP_SENTINEL = "---BQA_HELP---"

    private val WRAPPERS = listOf("setsid", "nohup", "")

    private val lock = Any()
    @Volatile private var dedicated: Kadb? = null
    @Volatile private var useDedicated: Boolean = true
    /** Runtime-only size degradation; -1 means "use the persisted level". Reset per recording. */
    @Volatile private var runtimeSizeLevel: Int = -1
    @Volatile var transportName: String = "uninitialized"
        private set

    private val guardExecutor = Executors.newCachedThreadPool { r ->
        Thread(r, "rec-shell-guard").apply { isDaemon = true }
    }

    /**
     * Serialises the two device-probing flows (in-app self-test and coordinator pre-warm).
     * Both launch real `screenrecord` processes and both locate the pid by diffing `pidof`
     * before/after the launch, so if they overlap each one can adopt — or kill — the other's
     * process, and the configuration they persist becomes nondeterministic.
     * Never touched by the real recording path.
     */
    private val probeLock = AtomicBoolean(false)

    /** Returns false when another probe is already in flight; the caller must skip, not queue. */
    fun tryAcquireProbe(owner: String): Boolean {
        val acquired = probeLock.compareAndSet(false, true)
        if (acquired) XLog.i(TAG, "Probe lock acquired by $owner")
        else XLog.w(TAG, "Probe lock busy — $owner skipped")
        return acquired
    }

    fun releaseProbe(owner: String) {
        if (probeLock.compareAndSet(true, false)) XLog.i(TAG, "Probe lock released by $owner")
    }

    fun isProbeBusy(): Boolean = probeLock.get()

    data class Support(
        val available: Boolean,
        val binaryPath: String,
        val helpText: String,
        val maxTimeLimitSec: Int,
        val problems: List<String>
    )

    data class StartResult(
        val pid: Int,
        val elapsedMs: Long,
        val blocked: Boolean,
        val output: String
    ) {
        val success: Boolean get() = pid > 0
    }

    // ==================== Transport ====================

    private fun dedicatedClient(): Kadb? = synchronized(lock) {
        if (!useDedicated) return null
        dedicated?.let { existing ->
            val healthy = runCatching { existing.connectionCheck() }.getOrDefault(false)
            if (healthy) return existing
            runCatching { existing.close() }
            dedicated = null
        }
        val host = KVUtils.getLocalAdbHost().ifBlank { return null }
        val port = KVUtils.getLocalAdbPort()
        if (port <= 0) return null
        val created = runCatching {
            Kadb.create(
                host = host,
                port = port,
                connectTimeout = CONNECT_TIMEOUT_MS,
                socketTimeout = DEDICATED_SOCKET_TIMEOUT_MS
            )
        }.onFailure { XLog.w(TAG, "Dedicated Kadb create failed: ${it.message}") }.getOrNull()
        dedicated = created
        created
    }

    private fun rawExec(command: String, allowSharedFallback: Boolean = true): ShellCommandResult {
        val client = dedicatedClient()
        if (client != null) {
            transportName = "dedicated-kadb"
            return try {
                val response = client.shell(command)
                ShellCommandResult(response.exitCode, false, response.output, response.errorOutput)
            } catch (e: Throwable) {
                synchronized(lock) { runCatching { dedicated?.close() }; dedicated = null }
                XLog.w(TAG, "Dedicated shell failed, retrying once: ${e.message}")
                val retry = dedicatedClient() ?: return sharedFallback(command, allowSharedFallback)
                return try {
                    val response = retry.shell(command)
                    ShellCommandResult(response.exitCode, false, response.output, response.errorOutput)
                } catch (e2: Throwable) {
                    XLog.w(TAG, "Dedicated shell failed twice, degrading to shared channel", e2)
                    useDedicated = false
                    sharedFallback(command, allowSharedFallback)
                }
            }
        }
        return sharedFallback(command, allowSharedFallback)
    }

    /**
     * Short, bounded commands (pidof / kill / wm size / echo) may degrade to the shared channel.
     * The backgrounded `screenrecord` launch must NOT: LocalAdbAutomation tears its shared client
     * down on any Throwable (L179) and that client may carry a 5s socketTimeout, so a launch that
     * outlives it would reset the automation connection. Recording is expendable; automation is not.
     */
    private fun sharedFallback(command: String, allowSharedFallback: Boolean): ShellCommandResult =
        if (allowSharedFallback) {
            sharedExec(command)
        } else {
            ShellCommandResult(-1, false, "", "dedicated recording transport unavailable")
        }

    private fun sharedExec(command: String): ShellCommandResult {
        transportName = "shared-local-adb"
        return try {
            LocalAdbAutomation.exec(command, SHARED_EXEC_TIMEOUT_MS)
        } catch (e: Throwable) {
            ShellCommandResult(-1, false, "", e.message ?: e.toString())
        }
    }

    /** Fire-and-forget shell call. Never throws. */
    fun exec(command: String, allowSharedFallback: Boolean = true): ShellCommandResult = try {
        rawExec(command, allowSharedFallback)
    } catch (e: Throwable) {
        ShellCommandResult(-1, false, "", e.message ?: e.toString())
    }

    data class BoundedResult(val result: ShellCommandResult?, val timedOut: Boolean, val elapsedMs: Long)

    /**
     * Runs a command but abandons it after [boundMs]. Used for the backgrounded `screenrecord`
     * launch: if kadb does not return promptly we must not sit on it for the whole socket timeout.
     * On timeout the dedicated client is dropped so the abandoned stream can never corrupt a
     * subsequent request.
     */
    fun execBounded(
        command: String,
        boundMs: Long,
        allowSharedFallback: Boolean = true
    ): BoundedResult {
        val startedAt = System.currentTimeMillis()
        val future = guardExecutor.submit<ShellCommandResult> { rawExec(command, allowSharedFallback) }
        return try {
            val result = future.get(boundMs, TimeUnit.MILLISECONDS)
            BoundedResult(result, false, System.currentTimeMillis() - startedAt)
        } catch (e: TimeoutException) {
            future.cancel(true)
            synchronized(lock) { runCatching { dedicated?.close() }; dedicated = null }
            XLog.w(TAG, "Command did not return within ${boundMs}ms (kadb may block on background jobs)")
            BoundedResult(null, true, System.currentTimeMillis() - startedAt)
        } catch (e: Throwable) {
            BoundedResult(null, false, System.currentTimeMillis() - startedAt)
        }
    }

    /** Forces the next call to retry the dedicated connection (used by the self-test). */
    fun resetTransportPreference() {
        synchronized(lock) {
            useDedicated = true
            runCatching { dedicated?.close() }
            dedicated = null
        }
        transportName = "uninitialized"
    }

    // ==================== Capability probing ====================

    /**
     * Probes `screenrecord` existence and its maximum --time-limit.
     *
     * `screenrecord --help` prints to stderr and exits non-zero on most builds, so its exit code is
     * meaningless — only the text matters. Never gate `available` on `--help` succeeding, otherwise a
     * perfectly usable recorder would be rejected.
     */
    fun probeSupport(): Support {
        val problems = mutableListOf<String>()

        // One round trip instead of two: `command -v` writes the path, the sentinel separates it from
        // `--help` (which exits non-zero and usually prints to stderr, so read the combined output).
        val raw = exec("command -v screenrecord; echo '$HELP_SENTINEL'; screenrecord --help").combinedOutput
        val whichPart = raw.substringBefore(HELP_SENTINEL)
        val helpText = raw.substringAfter(HELP_SENTINEL, "")

        val binaryPath = whichPart.trim().lineSequence().firstOrNull { it.contains("screenrecord") }?.trim() ?: ""
        if (binaryPath.isEmpty()) problems += "command -v screenrecord returned empty"
        if (helpText.isBlank()) problems += "screenrecord --help produced no output"

        val maxLimit = parseMaxTimeLimit(helpText)
        if (maxLimit <= 0) problems += "could not parse a maximum --time-limit from --help"

        return Support(
            available = binaryPath.isNotEmpty() || helpText.contains("time-limit"),
            binaryPath = binaryPath,
            helpText = helpText,
            maxTimeLimitSec = maxLimit,
            problems = problems
        )
    }

    private fun parseMaxTimeLimit(help: String): Int {
        val patterns = listOf(
            Regex("""maximum is (\d+)""", RegexOption.IGNORE_CASE),
            Regex("""between 1 and (\d+)""", RegexOption.IGNORE_CASE),
            Regex("""Default / maximum is (\d+)""", RegexOption.IGNORE_CASE),
            // Android 16 wording: "--time-limit TIME  Set the maximum recording time, in seconds.
            // Default is 180. Set to 0 to remove the time limit." Scoped to the --time-limit
            // paragraph so --size's "Default is the device's main display resolution" cannot match.
            Regex("""--time-limit[\s\S]{0,200}?Default is (\d+)""", RegexOption.IGNORE_CASE)
        )
        for (pattern in patterns) {
            pattern.find(help)?.groupValues?.get(1)?.toIntOrNull()?.let { if (it in 1..100_000) return it }
        }
        return 0
    }

    fun effectiveTimeLimitSec(support: Support?): Int {
        val parsed = support?.maxTimeLimitSec ?: 0
        val ceiling = if (parsed in 1..100_000) parsed else 180
        return ceiling.coerceAtMost(REQUESTED_TIME_LIMIT_SEC).coerceAtLeast(5)
    }

    /** Parses `wm size`; prefers the override size because that is what is actually scanned out. */
    fun queryDisplaySize(): IntArray? {
        val out = exec("wm size").combinedOutput
        if (out.isBlank()) return null
        val override = Regex("""Override size:\s*(\d{2,5})x(\d{2,5})""").find(out)
        val physical = Regex("""Physical size:\s*(\d{2,5})x(\d{2,5})""").find(out)
        val fallback = Regex("""(\d{2,5})x(\d{2,5})""").find(out)
        val match = override ?: physical ?: fallback ?: return null
        val w = match.groupValues[1].toIntOrNull() ?: return null
        val h = match.groupValues[2].toIntOrNull() ?: return null
        return if (w > 0 && h > 0) intArrayOf(w, h) else null
    }

    /**
     * Size level 0 = downscale so the short side is 720 (aspect preserved, even-aligned).
     * Size level 1 = omit --size entirely (device default). Level 2 = unsupported.
     * A level persisted for a different device is ignored: full-resolution recording because of
     * another phone's --size problem would cost 2.25x the pixels for no reason.
     */
    fun currentSizeLevel(): Int = when {
        runtimeSizeLevel >= 0 -> runtimeSizeLevel
        isConfigForThisDevice() -> KVUtils.getTaskRecordingSizeLevel()
        else -> 0
    }

    fun resetSizeLevel() {
        runtimeSizeLevel = -1
    }

    fun resolveSizeArg(): String? {
        if (currentSizeLevel() >= 1) return null
        val display = queryDisplaySize() ?: return null
        val record = computeRecordSize(display[0], display[1], TARGET_SHORT_SIDE)
        return if (record[0] > 0 && record[1] > 0) "${record[0]}x${record[1]}" else null
    }

    fun computeRecordSize(width: Int, height: Int, targetShortSide: Int): IntArray {
        if (width <= 0 || height <= 0) return intArrayOf(0, 0)
        val short = minOf(width, height)
        val long = maxOf(width, height)
        if (short <= targetShortSide) return intArrayOf(even(width), even(height))
        val scaledLong = Math.round(long * (targetShortSide.toFloat() / short))
        return if (width >= height) {
            intArrayOf(even(scaledLong), even(targetShortSide))
        } else {
            intArrayOf(even(targetShortSide), even(scaledLong))
        }
    }

    /**
     * Degrades for the current recording only. A transient transport failure must not permanently
     * raise the resolution of every future recording — only the self-test persists a size level.
     */
    fun degradeSizeLevelRuntime(): Int {
        val next = (currentSizeLevel() + 1).coerceAtMost(2)
        runtimeSizeLevel = next
        XLog.w(TAG, "Recording size level degraded to $next (this recording only)")
        return next
    }

    private fun even(value: Int): Int = if (value % 2 == 0) value else (value - 1).coerceAtLeast(2)

    fun bitRate(): Int = KVUtils.getTaskRecordingBitRate().let { if (it in 200_000..40_000_000) it else DEFAULT_BIT_RATE }

    // ==================== Process control ====================

    /**
     * Outcome of a pid lookup. [lookupRan] is false only when the shell call itself never
     * completed (transport down or reset). That is NOT evidence that screenrecord died: `pidof`
     * exits 1 with empty output when it runs fine and finds nothing, so exitCode == -1 is the only
     * transport-failure sentinel (ShellCommandResult.parse L46/L53, LocalAdbAutomation.exec
     * L163/L183). Callers that drive liveness off this must keep the two cases apart — collapsing
     * them made a busy channel rotate a healthy segment and then SIGINT it.
     */
    data class PidLookup(val pids: List<Int>, val lookupRan: Boolean)

    fun pidsOfScreenRecordDetailed(): PidLookup {
        val primary = exec("pidof screenrecord")
        val fromPidof = primary.stdout.trim()
            .split(Regex("\\s+"))
            .mapNotNull { it.toIntOrNull() }
            .filter { it > 0 }
        if (fromPidof.isNotEmpty()) {
            pidLookupNote = "pidof=${fromPidof}"
            return PidLookup(fromPidof, true)
        }
        // pidof is a toybox applet and is missing or muted on some ROMs. Without a pid every
        // wrapper looks broken even when screenrecord is recording happily, so fall back to ps.
        val err = primary.stderr.trim().take(80)
        val ps = exec("ps -A")
        val fromPs = ps.combinedOutput.lineSequence()
            .filter { it.contains("screenrecord") }
            .mapNotNull { line -> line.trim().split(Regex("\\s+")).getOrNull(1)?.toIntOrNull() }
            .filter { it > 0 }
            .distinct()
            .toList()
        val ran = primary.exitCode != -1 || ps.exitCode != -1
        pidLookupNote = when {
            fromPs.isNotEmpty() -> "pidof empty(exit=${primary.exitCode},err='$err') ps=${fromPs}"
            !ran -> "LOOKUP FAILED pidof exit=${primary.exitCode} ps exit=${ps.exitCode} " +
                    "— channel unusable, not evidence of death"
            else -> "pidof empty(exit=${primary.exitCode},err='$err') ps empty — nothing launched"
        }
        return PidLookup(fromPs, ran)
    }

    fun pidsOfScreenRecord(): List<Int> = pidsOfScreenRecordDetailed().pids

    /**
     * Launches one segment in the background and resolves the real `screenrecord` pid.
     *
     * `$!` is not trustworthy: when the wrapper (setsid) has to fork, `$!` is the wrapper's pid and
     * the wrapper exits immediately, so `kill -2 $!` would never reach screenrecord. The pid is
     * therefore cross-checked against `pidof screenrecord` before/after the launch.
     */
    fun startSegment(
        outFile: File,
        logFile: File,
        sizeArg: String?,
        timeLimitSec: Int,
        wrapper: String,
        launchBoundMs: Long = 8_000L
    ): StartResult {
        val before = pidsOfScreenRecord().toSet()

        val args = buildString {
            append("screenrecord")
            if (!sizeArg.isNullOrBlank()) append(" --size ").append(sizeArg)
            append(" --bit-rate ").append(bitRate())
            append(" --time-limit ").append(timeLimitSec.coerceIn(1, 100_000))
            append(' ').append(shellQuote(outFile.absolutePath))
        }
        val prefix = if (wrapper.isBlank()) "" else "$wrapper "
        // screenrecord runs as uid `shell` with umask 077 and creates 0600 shell:shell files — the
        // control probe's `ls -l` showed exactly that. Under /storage/emulated/0/Android/data/<pkg>
        // the lower inode keeps that ownership, so the app's FUSE-mediated open fails with EACCES
        // while stat() still succeeds: File.length() reports a size but MediaMetadataRetriever
        // throws. The 0022 below is best-effort only — it did NOT make files readable on Android 16
        // (check 07 passed because of the explicit chmod, check 10 failed without one, same
        // directory and same command). Every read-back path must still call makeReadable().
        val command = "umask 0022; $prefix$args > ${shellQuote(logFile.absolutePath)} 2>&1 " +
                "< /dev/null & echo \$!"

        val bounded = execBounded(command, launchBoundMs, allowSharedFallback = false)
        if (bounded.timedOut) {
            return StartResult(0, bounded.elapsedMs, true, "launch did not return within ${launchBoundMs}ms")
        }
        val result = bounded.result ?: return StartResult(0, bounded.elapsedMs, false, "no result")

        // Give the process a moment to appear, then resolve the pid. The window scales with
        // [launchBoundMs] rather than being a fixed 6 x 150ms: under real task load (screencap,
        // uiautomator dump and inference all competing for the same adbd) the gap between process
        // creation and `pidof` visibility can be far longer than the 900ms the idle probe needed,
        // and running out of window here reports a launch failure for a recorder that started fine.
        var pid = 0
        val pidDeadline = System.currentTimeMillis() + pidResolveBudgetMs(launchBoundMs)
        // `return@repeat` would only end the current iteration, so a pid found early could be
        // overwritten with 0 once the process exits — mislabelling a playable-file failure as a
        // launch failure. A labelled run{} gives us a real break.
        run poll@{
            while (System.currentTimeMillis() < pidDeadline) {
                Thread.sleep(PID_POLL_INTERVAL_MS)
                val after = pidsOfScreenRecord()
                val fresh = after.filter { it !in before }
                val reported = result.stdout.trim().lineSequence().lastOrNull()?.trim()?.toIntOrNull() ?: 0
                val candidate = when {
                    fresh.size == 1 -> fresh[0]
                    reported > 0 && reported in after -> reported
                    fresh.isNotEmpty() -> fresh.max()
                    else -> 0
                }
                if (candidate > 0) {
                    pid = candidate
                    return@poll
                }
            }
        }

        return StartResult(
            pid = pid,
            elapsedMs = bounded.elapsedMs,
            blocked = bounded.elapsedMs > 3_000L,
            output = result.combinedOutput.take(400)
        )
    }

    private fun pidResolveBudgetMs(launchBoundMs: Long): Long =
        (launchBoundMs / 4).coerceIn(PID_RESOLVE_MIN_MS, PID_RESOLVE_MAX_MS)

    /**
     * Stops a segment with SIGINT so screenrecord finalises the moov atom. Escalates only if the
     * process refuses to die; a SIGKILL'd file will fail validation and be dropped.
     */
    fun stopSegment(pid: Int): Boolean {
        if (pid <= 0) return false
        exec("kill -2 $pid")
        if (awaitExit(pid, 2_000L)) return true
        XLog.w(TAG, "screenrecord pid=$pid ignored SIGINT, escalating to SIGTERM")
        exec("kill -15 $pid")
        if (awaitExit(pid, 1_500L)) return true
        XLog.w(TAG, "screenrecord pid=$pid ignored SIGTERM, escalating to SIGKILL")
        exec("kill -9 $pid")
        return awaitExit(pid, 1_000L)
    }

    private fun awaitExit(pid: Int, budgetMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + budgetMs
        while (System.currentTimeMillis() < deadline) {
            if (pid !in pidsOfScreenRecord()) return true
            Thread.sleep(120)
        }
        return pid !in pidsOfScreenRecord()
    }

    /** Waits until the file stops growing so we never validate a half-flushed mp4. */
    fun awaitFileSettled(file: File, budgetMs: Long = 3_000L): Long {
        val deadline = System.currentTimeMillis() + budgetMs
        var lastSize = -1L
        var stableHits = 0
        while (System.currentTimeMillis() < deadline) {
            val size = if (file.exists()) file.length() else 0L
            if (size == lastSize && size > 0) {
                stableHits++
                if (stableHits >= 2) return size
            } else {
                stableHits = 0
            }
            lastSize = size
            Thread.sleep(150)
        }
        return if (file.exists()) file.length() else 0L
    }

    /**
     * Finds the background wrapper that works on this device and caches it.
     * Returns null when none of them yields a live pid.
     */
    fun detectWrapper(probeDir: File, timeLimitSec: Int): String? {
        val trail = mutableListOf<String>()
        val cached = cachedWrapper()
        val candidates = if (cached.isNotBlank()) listOf(cached) + WRAPPERS.filter { it != cached } else WRAPPERS
        for (wrapper in candidates) {
            val name = wrapper.ifBlank { "bare&" }
            val out = File(probeDir, "probe_${System.nanoTime()}.mp4")
            val log = File(probeDir, "probe_${System.nanoTime()}.log")
            val start = startSegment(out, log, null, timeLimitSec.coerceAtLeast(5), wrapper)
            if (start.success) {
                // Let the encoder actually produce frames before finalising, otherwise the
                // playability gate below measures nothing but our own impatience.
                Thread.sleep(PROBE_RECORD_MS)
                val stopped = stopSegment(start.pid)
                makeReadable(out, log)
                awaitFileSettled(out, 3_000L)
                val (probe, reason) = TaskRecordingStore.probeVideoDetailed(out)
                val bytes = if (out.exists()) out.length() else -1L
                val moov = inspectBox(out, "moov")
                val listing = describeOnDevice(out)
                val logTail = readProbeLog(log)
                runCatching { out.delete() }
                runCatching { log.delete() }
                if (probe != null) {
                    trail += "$name OK pid=${start.pid} launch=${start.elapsedMs}ms " +
                            "${probe.durationMs}ms ${probe.width}x${probe.height} ${bytes}B"
                    lastDetectTrail = trail
                    KVUtils.setTaskRecordingWrapper(wrapper)
                    XLog.i(TAG, "Background wrapper selected: '${wrapper.ifBlank { "none" }}' " +
                            "(launch ${start.elapsedMs}ms, blocked=${start.blocked})")
                    return wrapper
                }
                // pid resolved but the mp4 is unusable. `moov` and `log` now distinguish "this
                // process could not read the file" from "the file is genuinely malformed" — they
                // used to report the same false/empty value for both, which is what mislead us.
                trail += "$name pid=${start.pid} UNPLAYABLE ${bytes}B $moov stopped=$stopped " +
                        "$reason ls='$listing' log='$logTail'"
                XLog.w(TAG, "Wrapper '$wrapper' produced a pid but no playable file " +
                        "(${bytes}B $moov stopped=$stopped $reason ls='$listing' log='$logTail')")
            } else {
                trail += "$name NO-PID blocked=${start.blocked} ${start.elapsedMs}ms " +
                        "pids='$pidLookupNote' out='${start.output.take(160)}'"
                runCatching { out.delete() }
                runCatching { log.delete() }
                XLog.w(TAG, "Wrapper '$wrapper' failed: blocked=${start.blocked} out=${start.output}")
            }
        }
        lastDetectTrail = trail
        return null
    }

    /** Why the last [probeOverlap] returned what it did; surfaced in diagnostics and the self-test. */
    @Volatile
    var overlapNote: String = ""
        private set

    /**
     * Can two `screenrecord` instances capture at once? Decides near-seamless vs gapped rotation.
     * This is the most device-hostile probe we run — it starts two concurrent encoders — so it
     * belongs to the one-shot verification gate, never to a real recording.
     *
     * Migrated from RecordingSelfTest.testOverlap(): overlap used to be measurable only by the
     * user-initiated self-test, so declining that dialog left rotation permanently sequential.
     */
    fun probeOverlap(dir: File, wrapper: String): Boolean {
        val a = File(dir, "overlap_a.mp4"); val aLog = File(dir, "overlap_a.log")
        val b = File(dir, "overlap_b.mp4"); val bLog = File(dir, "overlap_b.log")
        val sizeArg = resolveSizeArg()
        val first = startSegment(a, aLog, sizeArg, 8, wrapper, 8_000L)
        if (!first.success) {
            overlapNote = "first encoder never started (pid=0) out='${first.output.take(120)}'"
            listOf(a, aLog).forEach { runCatching { it.delete() } }
            return false
        }
        Thread.sleep(800)
        val second = startSegment(b, bLog, sizeArg, 8, wrapper, 8_000L)
        val concurrent = second.success && second.pid != first.pid
        Thread.sleep(2_000)
        if (second.success) stopSegment(second.pid)
        stopSegment(first.pid)
        // Without this both files read back as unplayable even when two encoders ran concurrently,
        // which makes "device refuses a 2nd encoder" indistinguishable from "app could not read".
        makeReadable(a, aLog, b, bLog)
        awaitFileSettled(a); awaitFileSettled(b)
        val (probeA, reasonA) = TaskRecordingStore.probeVideoDetailed(a)
        val (probeB, reasonB) = TaskRecordingStore.probeVideoDetailed(b)
        val ok = concurrent && probeA != null && probeB != null
        overlapNote = "concurrent=$concurrent pids=${first.pid}/${second.pid} sizeArg='${sizeArg ?: "none"}' | " +
                "a=${if (a.exists()) a.length() else -1}B ${inspectBox(a, "moov")} $reasonA | " +
                "b=${if (b.exists()) b.length() else -1}B ${inspectBox(b, "moov")} $reasonB"
        listOf(a, aLog, b, bLog).forEach { runCatching { it.delete() } }
        XLog.i(TAG, "Overlap probe: $overlapNote")
        return ok
    }

    /**
     * Makes finished segments readable by this process. screenrecord runs as uid `shell`; even with
     * `umask 0022` in [startSegment] some FUSE stacks keep the lower inode shell-owned, and the
     * app's open then fails with EACCES while stat() still reports a size. Every caller that reads
     * a segment back must go through this, otherwise "unplayable" and "unreadable" look identical.
     */
    fun makeReadable(vararg files: File) {
        if (files.isEmpty()) return
        exec("chmod 644 " + files.joinToString(" ") { shellQuote(it.absolutePath) })
    }

    /**
     * Makes every mp4 in [dir] readable in one shell call. Used by crash recovery, where
     * [finalizeSegment] never ran and the orphaned segments are still 0600 shell:shell — without
     * this, reconcile() rejects them all and deletes the very recordings it exists to rescue.
     * The glob stays outside the quotes so the shell still expands it.
     */
    fun makeDirReadable(dir: File) {
        exec("chmod 644 ${shellQuote(dir.absolutePath + File.separator)}*.mp4 2>/dev/null")
    }

    /** `ls -l` through the shell channel — the only way to see the real on-device owner and mode. */
    fun describeOnDevice(file: File): String =
        exec("ls -l ${shellQuote(file.absolutePath)}").combinedOutput.trim().replace('\n', ' ')

    /** Bytes of tail scanned by [inspectBox]. */
    private const val BOX_SCAN_TAIL_BYTES = 256 * 1024

    /**
     * Container sanity check that never confuses "box absent" with "file unreadable by this
     * process". Scans only the last [BOX_SCAN_TAIL_BYTES]: screenrecord emits `ftyp -> mdat -> moov`
     * (not faststart), so moov is always at the tail and reading the whole file allocated twice its
     * size for nothing. Safe on real segments.
     */
    fun inspectBox(file: File, box: String): String = runCatching {
        val len = file.length()
        if (len <= 0L) return@runCatching "$box=no(0B)"
        val from = if (len > BOX_SCAN_TAIL_BYTES) len - BOX_SCAN_TAIL_BYTES else 0L
        val bytes = RandomAccessFile(file, "r").use { raf ->
            raf.seek(from)
            ByteArray((len - from).toInt()).also { raf.readFully(it) }
        }
        if (String(bytes, Charsets.ISO_8859_1).contains(box)) "$box=yes" else "$box=no"
    }.getOrElse { "$box=unreadable(${it.javaClass.simpleName})" }

    /**
     * True only when this process can actually open the file. [inspectBox] reports *content*; this
     * reports *access*. They must stay separate because the self-test persists a permanent `--size`
     * degradation off the access verdict, and degrading because of an EACCES would raise every
     * future recording to full resolution for a reason that has nothing to do with `--size`.
     */
    fun canReadBack(file: File): Boolean = runCatching {
        FileInputStream(file).use { true }
    }.getOrDefault(false)

    /** Same distinction for screenrecord's captured stderr: empty and unreadable are not equal. */
    fun readProbeLog(file: File): String = runCatching {
        file.readText().trim().take(160).ifBlank { "empty" }
    }.getOrElse { "unreadable(${it.javaClass.simpleName})" }

    /**
     * Diagnostic control for a failed [detectWrapper]: records into shell-owned /data/local/tmp
     * and reads the resulting size back through the shell channel. Splits "screenrecord cannot
     * launch at all" from "screenrecord cannot write into the app-private external dir", which
     * the app process would then also be unable to read back.
     */
    fun probeShellOwnedDir(timeLimitSec: Int): String {
        val path = "/data/local/tmp/bqaagent_probe_control.mp4"
        val logPath = "/data/local/tmp/bqaagent_probe_control.log"
        exec("rm -f $path $logPath")
        val before = pidsOfScreenRecord().toSet()
        val command = "screenrecord --time-limit ${timeLimitSec.coerceAtLeast(5)} $path " +
                "> $logPath 2>&1 < /dev/null & echo \$!"
        val bounded = execBounded(command, 8_000L, allowSharedFallback = false)
        if (bounded.timedOut) return "launch did not return within 8000ms"
        Thread.sleep(1_500)
        val fresh = pidsOfScreenRecord().filter { it !in before }
        val pid = if (fresh.size == 1) fresh[0] else fresh.maxOrNull() ?: 0
        val listing = exec("ls -l $path").combinedOutput.trim().replace('\n', ' ')
        val logTail = exec("tail -c 160 $logPath").combinedOutput.trim().replace('\n', ' ')
        if (pid > 0) stopSegment(pid)
        exec("rm -f $path $logPath")
        return "pid=$pid fresh=$fresh ls='$listing' log='$logTail'"
    }

    /** True when the persisted recording configuration was verified on this exact device. */
    fun isConfigForThisDevice(): Boolean =
        KVUtils.getTaskRecordingVerifiedDevice() == TaskRecordingStore.deviceKey()

    /**
     * The cached wrapper, but only when it was verified on *this* device. Returning "" for a
     * foreign fingerprint makes doStart()'s existing `ifBlank {}` branch re-probe automatically.
     */
    fun cachedWrapper(): String {
        val wrapper = KVUtils.getTaskRecordingWrapper()
        if (wrapper.isBlank()) return ""
        val verifiedOn = KVUtils.getTaskRecordingVerifiedDevice()
        val current = TaskRecordingStore.deviceKey()
        if (verifiedOn != current) {
            XLog.w(TAG, "Ignoring cached wrapper '$wrapper' — verified on '$verifiedOn', now on '$current'")
            return ""
        }
        return wrapper
    }

    /** Called once the whole verification gate has passed on this device. */
    fun markVerifiedOnThisDevice() {
        KVUtils.setTaskRecordingVerifiedDevice(TaskRecordingStore.deviceKey())
        KVUtils.setTaskRecordingVerifiedAt(System.currentTimeMillis())
    }

    /**
     * Per-wrapper outcome of the last [detectWrapper] run. The self-test prints this so a failure
     * is diagnosable from the report alone instead of requiring a logcat capture.
     */
    @Volatile
    var lastDetectTrail: List<String> = emptyList()
        private set

    /** How the last pid lookup resolved; distinguishes "pidof unusable" from "nothing launched". */
    @Volatile
    var pidLookupNote: String = ""
        private set

    fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"
}