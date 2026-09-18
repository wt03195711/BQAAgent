// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.recording

import io.agents.bqaagent.adb.LocalAdbAutomation
import io.agents.bqaagent.utils.KVUtils
import io.agents.bqaagent.utils.XLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-app replacement for the external pre-research plan. Runs entirely on the device through the
 * same shell channel the recorder uses, resolves every open uncertainty, and persists the answers
 * so the runtime can self-configure.
 *
 * One run produces two views:
 *  - [Result.summary]: headline + one short line per check, sized for the on-screen dialog;
 *  - [Result.report]: everything, including the raw `--help` dump, for the shareable text file.
 * The split is deliberate — the raw help text alone is ~700 chars and used to push the verdict
 * off the bottom of a non-scrollable dialog.
 *
 * MUST be called from a worker thread. Refuses to run while a task is active.
 */
object RecordingSelfTest {

    private const val TAG = "RecordingSelfTest"
    private const val PROBE = "bqaagent_rec_probe"

    /** Hard cap for the value part of one summary row, so no row can blow up the dialog. */
    private const val SUMMARY_VALUE_MAX = 26

    data class Result(
        val passed: Boolean,
        val report: String,
        val reportFile: File?,
        /** Compact dialog-sized view of the same run. Equals [report] for early exits. */
        val summary: String = report
    )

    fun run(isTaskRunning: () -> Boolean, deep: Boolean = false): Result {
        // Everything is inside one try: an escape from here reaches lifecycleScope.launch,
        // which has no CoroutineExceptionHandler, and that kills the whole process.
        val summaryRows = mutableListOf<String>()
        val detailLines = mutableListOf<String>()
        val failedLabels = mutableListOf<String>()
        var checks = 0
        var failures = 0

        /** A numbered check. Appears in the dialog summary and in the file report. */
        fun check(label: String, ok: Boolean, detail: String, brief: String = detail) {
            checks++
            val no = String.format(Locale.US, "%02d", checks)
            if (!ok) {
                failures++
                failedLabels += "#$no $label"
            }
            summaryRows += "${if (ok) "\u2705" else "\u274C"} $no  $label \u2014 ${clip(brief)}"
            detailLines += "[${if (ok) "PASS" else "FAIL"}] $no $label \u2014 $detail"
        }

        /** Supporting evidence. File report only — never shown in the dialog. */
        fun info(label: String, detail: String) {
            detailLines += "        \u00B7 $label: $detail"
        }

        var probeRoot: File? = null
        try {
            if (isTaskRunning()) {
                val refused = "Self-test refused: a task is currently running.\nStop it and try again."
                return Result(false, refused, null, refused)
            }

            val root = TaskRecordingStore.rootDir()
            if (root == null) return Result(false, "No external storage", null, "No external storage")
            probeRoot = File(root, "selftest").apply { mkdirs() }

            // Every return below is inside try/finally, so the lock can never leak.
            if (!AdbScreenRecorder.tryAcquireProbe("self-test")) {
                val busy = "\u274C SKIPPED — the recorder is still probing this device.\n\n" +
                        "This happens right after you enable Task Recording (background pre-warm).\n" +
                        "Wait about a minute and run the self-test again."
                return Result(false, busy, null, busy)
            }

            try {
                // 1 — ADB configuration
                val port = KVUtils.getLocalAdbPort()
                check("ADB config", port > 0, "host=${KVUtils.getLocalAdbHost()} port=$port", "port $port")
                if (port <= 0) return finish(summaryRows, detailLines, failedLabels, checks, failures, probeRoot)

                // 2 — transport isolation
                AdbScreenRecorder.resetTransportPreference()
                AdbScreenRecorder.resetSizeLevel()
                // Always probe the intended configuration. Inheriting a degradation persisted by an
                // earlier run would make every later run skip --size, so the suspect size would never
                // be re-tested and the config would silently stay at full resolution forever.
                KVUtils.setTaskRecordingSizeLevel(0)
            val pingStart = System.currentTimeMillis()
            val ping = AdbScreenRecorder.exec("echo $PROBE")
            val pingMs = System.currentTimeMillis() - pingStart
            check(
                "Shell channel",
                ping.isSuccess && ping.stdout.contains(PROBE),
                "${AdbScreenRecorder.transportName}, echo ${pingMs}ms",
                "${AdbScreenRecorder.transportName}, ${pingMs}ms"
            )
            info(
                "Isolation",
                if (AdbScreenRecorder.transportName == "dedicated-kadb")
                    "recording uses its own Kadb connection — automation channel untouched"
                else
                    "FALLBACK: sharing the automation channel (adbd refused a 2nd connection?)"
            )

            // 3/4 — binary and supported options
            val support = AdbScreenRecorder.probeSupport()
            check(
                "screenrecord binary", support.available,
                support.binaryPath.ifBlank { "not on PATH" },
                if (support.available) "found" else "MISSING"
            )
                val maxLimit = support.maxTimeLimitSec
                if (maxLimit in 1..100_000) {
                    check(
                        "--time-limit ceiling", true,
                        "parsed max=${maxLimit}s",
                        "max ${maxLimit}s"
                    )
                } else {
                    // Not a device defect: effectiveTimeLimitSec() falls back to the AOSP 180s ceiling
                    // and clamps to REQUESTED_TIME_LIMIT_SEC. The fallback stays unverified until check
                    // 08 actually launches a segment with it, so report it as a caveat, not a failure.
                    val effective = AdbScreenRecorder.effectiveTimeLimitSec(support)
                    check(
                        "--time-limit ceiling", true,
                        "unparsed — using the AOSP fallback, effective ${effective}s " +
                                "(verified empirically by check 08)",
                        "unparsed, fallback ${effective}s"
                    )
                    info("--time-limit help", extractTimeLimitHelp(support.helpText))
                }
                info("--help (raw)", support.helpText.replace("\n", " / ").take(700))
                if (support.problems.isNotEmpty()) info("Probe notes", support.problems.joinToString(" | "))

            // 5 — resolution
            val display = AdbScreenRecorder.queryDisplaySize()
            if (display == null) {
                check(
                    "Display size", false,
                    "`wm size` unparseable — will record without --size",
                    "wm size unparseable"
                )
            } else {
                val rec = AdbScreenRecorder.computeRecordSize(display[0], display[1], 720)
                check(
                    "Display size", true,
                    "display ${display[0]}x${display[1]} → record ${rec[0]}x${rec[1]}",
                    "${display[0]}x${display[1]}→${rec[0]}x${rec[1]}"
                )
            }

            // 6 — storage
            val free = TaskRecordingStore.freeSpaceBytes()
            check(
                "Free space", free > 300L * 1024 * 1024,
                TaskRecordingStore.formatBytes(free) + " free",
                TaskRecordingStore.formatBytes(free)
            )

            // 7 — THE critical one: does kadb return immediately for a backgrounded command?
            KVUtils.setTaskRecordingWrapper("")
            val wrapperStart = System.currentTimeMillis()
            val wrapper = AdbScreenRecorder.detectWrapper(probeRoot, 6)
            val wrapperMs = System.currentTimeMillis() - wrapperStart
            check(
                "Background launch", wrapper != null,
                if (wrapper != null) "wrapper='${wrapper.ifBlank { "none" }}' detected in ${wrapperMs}ms"
                else "no wrapper produced a live pid + playable file (see logcat $TAG)",
                if (wrapper != null) "'${wrapper.ifBlank { "none" }}' in ${wrapperMs}ms" else "no wrapper works"
            )
                if (wrapper == null) {
                    AdbScreenRecorder.lastDetectTrail.forEach { info("Wrapper probe", it) }
                    info("Pid lookup", AdbScreenRecorder.pidLookupNote)
                    info("Control /data/local/tmp", AdbScreenRecorder.probeShellOwnedDir(6))
                    return finish(summaryRows, detailLines, failedLabels, checks, failures, probeRoot)
                }

                // 8 — real 4s recording with SIGINT finalisation
                val segFile = File(probeRoot, "selftest_main.mp4")
                val segLog = File(probeRoot, "selftest_main.log")
                val segSizeArg = AdbScreenRecorder.resolveSizeArg()
                val start = AdbScreenRecorder.startSegment(
                    segFile, segLog, segSizeArg,
                    AdbScreenRecorder.effectiveTimeLimitSec(support), wrapper, 8_000L
                )
                check(
                    "Launch promptness", start.success && !start.blocked,
                    "pid=${start.pid} elapsed=${start.elapsedMs}ms blocked=${start.blocked} out=${start.output}",
                    "${start.elapsedMs}ms pid=${start.pid}"
                )
                if (start.success) {
                    Thread.sleep(4_000)
                    val stopped = AdbScreenRecorder.stopSegment(start.pid)
                    // Same readability step detectWrapper performs. Without it "unplayable" cannot be
                    // told apart from "this process was not allowed to open the file".
                    AdbScreenRecorder.makeReadable(segFile, segLog)
                    val size = AdbScreenRecorder.awaitFileSettled(segFile)
                    val (probe, probeReason) = TaskRecordingStore.probeVideoDetailed(segFile)
                    val moov = AdbScreenRecorder.inspectBox(segFile, "moov")
                    // A real open attempt, not string-matching on a diagnostic's formatting: this
                    // verdict drives the persisted --size degradation below, so it must reflect
                    // actual access and not how inspectBox happens to spell its failure.
                    val readable = AdbScreenRecorder.canReadBack(segFile)
                    check(
                        "SIGINT finalisation", stopped,
                        if (stopped) "process exited on kill -2" else "needed SIGTERM/SIGKILL",
                        if (stopped) "kill -2 honoured" else "needed SIGTERM/9"
                    )
                    check(
                        "File playable", probe != null,
                        probe?.let { "${TaskRecordingStore.formatBytes(size)}, ${it.durationMs}ms, ${it.width}x${it.height}" }
                            ?: "unplayable (${TaskRecordingStore.formatBytes(size)}): $probeReason $moov " +
                            "sizeArg='${segSizeArg ?: "none"}' " +
                            "ls='${AdbScreenRecorder.describeOnDevice(segFile)}' Log: ${readLog(segLog)}",
                        probe?.let { "${TaskRecordingStore.formatBytes(size)}, ${it.width}x${it.height}" }
                            ?: "UNPLAYABLE"
                    )
                    if (probe != null) {
                        KVUtils.setTaskRecordingSizeLevel(0)
                    } else if (readable) {
                        // The file was readable, so the verdict really is about its content and
                        // --size is a legitimate suspect.
                        KVUtils.setTaskRecordingSizeLevel(1)
                        info("Action taken", "size level degraded to 1 (record without --size)")
                    } else {
                        // Unreadable says nothing about --size. Degrading here would permanently raise
                        // every future recording's resolution because of a file-access problem.
                        info("Action taken", "size level NOT degraded — file unreadable, not evidence against --size")
                    }
                }

                // 9 — can two screenrecord instances overlap? decides seamless vs gapped rotation
                // Deep-only: this launches TWO concurrent encoders, which is the single most
                // device-hostile thing the self-test does. Skipped unless explicitly requested.
                if (deep) {
                    val overlapOk = testOverlap(probeRoot, wrapper)
                    KVUtils.setTaskRecordingOverlapEnabled(overlapOk)
                    check(
                        "Concurrent capture", overlapOk,
                        if (overlapOk) "overlap rotation ENABLED (near-seamless segments)"
                        else "sequential rotation (about 1s gap at each rotation, every ~2-3 min)",
                        if (overlapOk) "overlap ON, seamless" else "overlap OFF, ~1s gap"
                    )
                    info("Overlap detail", AdbScreenRecorder.overlapNote)
                } else {
                    KVUtils.setTaskRecordingOverlapEnabled(false)
                    info("Concurrent capture", "skipped (deep mode off) — sequential rotation, ~1s gap per segment")
                }

                // 10 — impact on the automation channel while recording (replaces external test A5)
                // Deep-only: starts a third encoder and puts 10 extra calls on the shared channel.
                if (deep) {
                    val idleMedian = measureSharedChannel(5)
                    val longFile = File(probeRoot, "selftest_load.mp4")
                    val longLog = File(probeRoot, "selftest_load.log")
                    val longStart = AdbScreenRecorder.startSegment(longFile, longLog, AdbScreenRecorder.resolveSizeArg(), 20, wrapper, 8_000L)
                    val loadMedian = if (longStart.success) {
                        Thread.sleep(1_500)
                        measureSharedChannel(5)
                    } else -1L
                    if (longStart.success) {
                        AdbScreenRecorder.stopSegment(longStart.pid)
                        AdbScreenRecorder.awaitFileSettled(longFile)
                    }
                    if (loadMedian < 0) {
                        check("Channel impact", false, "could not start the load recording", "load run failed")
                    } else {
                        val delta = if (idleMedian > 0) ((loadMedian - idleMedian) * 100 / idleMedian) else 0
                        check(
                            "Channel impact", delta < 25,
                            "shared-channel echo median: idle=${idleMedian}ms recording=${loadMedian}ms " +
                                    "(${if (delta >= 0) "+" else ""}$delta%), threshold +25%",
                            "${if (delta >= 0) "+" else ""}$delta% (${idleMedian}→${loadMedian}ms)"
                        )
                        info("Threshold", "regression must stay under +25% (automation issues ~1000 shell calls per task)")
                    }
                } else {
                    info("Channel impact", "skipped (deep mode off)")
                }

            // 11 — leftovers
            val stale = AdbScreenRecorder.pidsOfScreenRecord()
            stale.forEach { AdbScreenRecorder.stopSegment(it) }
            check(
                "Cleanup", AdbScreenRecorder.pidsOfScreenRecord().isEmpty(),
                if (stale.isEmpty()) "no stray screenrecord process" else "killed ${stale.size} stray process(es)",
                if (stale.isEmpty()) "no stray process" else "killed ${stale.size} stray"
            )
            } catch (e: Throwable) {
                checks++
                failures++
                failedLabels += "crash"
                summaryRows += "\u274C !!  Self-test crashed \u2014 ${e.javaClass.simpleName}"
                detailLines += "[FAIL] !! Self-test crashed — ${e.javaClass.simpleName}: ${e.message}"
                XLog.e(TAG, "Self-test crashed", e)
            } finally {
                val dir = probeRoot
                if (dir != null) {
                    runCatching { dir.listFiles()?.forEach { if (it.name != "selftest_main.mp4") it.delete() } }
                }
                AdbScreenRecorder.releaseProbe("self-test")
            }
        } catch (e: Throwable) {
            // Last line of defence: reaching here means a bug in the harness itself.
            XLog.e(TAG, "Self-test harness failed", e)
            val msg = "\u274C Self-test could not complete: ${e.javaClass.simpleName}: ${e.message}"
            return Result(false, msg, null, msg)
        }

        // finish() must never throw out of run(): the caller has no exception handler.
        val dir = probeRoot
        return if (dir == null) {
            Result(false, "No external storage", null, "No external storage")
        } else {
            runCatching { finish(summaryRows, detailLines, failedLabels, checks, failures, dir) }
                .getOrElse { e ->
                    XLog.e(TAG, "finish() failed", e)
                    val msg = "\u274C Report assembly failed: ${e.javaClass.simpleName}"
                    Result(false, msg, null, msg)
                }
        }
    }

    /** Delegates to the production probe so the two can never drift apart. */
    private fun testOverlap(dir: File, wrapper: String): Boolean =
        AdbScreenRecorder.probeOverlap(dir, wrapper)

    private fun measureSharedChannel(samples: Int): Long {
        val values = mutableListOf<Long>()
        repeat(samples) {
            val t0 = System.currentTimeMillis()
            LocalAdbAutomation.exec("echo $PROBE", 15_000L)
            values += (System.currentTimeMillis() - t0)
            Thread.sleep(120)
        }
        if (values.isEmpty()) return -1L
        return values.sorted()[values.size / 2]
    }

    /**
     * The 700-char raw --help dump never reaches the --time-limit paragraph, which is exactly the
     * part needed when the ceiling regex misses. Pull that paragraph out on its own.
     */
    private fun extractTimeLimitHelp(help: String): String {
        val idx = help.indexOf("--time-limit")
        if (idx < 0) return "(--time-limit absent from --help)"
        return help.substring(idx).lineSequence()
            .filter { it.isNotBlank() }
            .take(3)
            .joinToString(" / ") { it.trim() }
            .take(240)
    }

    /** Empty and unreadable must not report the same string — that ambiguity hides EACCES. */
    private fun readLog(file: File): String = runCatching {
        file.readText().trim().take(300).ifBlank { "empty" }
    }.getOrElse { "unreadable(${it.javaClass.simpleName})" }

    private fun clip(value: String): String =
        if (value.length <= SUMMARY_VALUE_MAX) value else value.take(SUMMARY_VALUE_MAX - 1) + "\u2026"

    /**
     * Builds both views. The headline goes first in [Result.summary] so the verdict is never the
     * thing that gets scrolled off or truncated.
     */
    private fun finish(
        summaryRows: List<String>,
        detailLines: List<String>,
        failedLabels: List<String>,
        checks: Int,
        failures: Int,
        probeRoot: File
    ): Result {
        val allPass = failures == 0
        val headline = if (allPass) {
            "\u2705 ALL $checks CHECKS PASSED — Task Recording is safe to enable on this device."
        } else {
            "\u274C $failures of $checks CHECKS FAILED: ${failedLabels.joinToString(", ")}"
        }
        val configLine = "wrapper='${KVUtils.getTaskRecordingWrapper().ifBlank { "none" }}' " +
                "overlap=${KVUtils.isTaskRecordingOverlapEnabled()} " +
                "sizeLevel=${KVUtils.getTaskRecordingSizeLevel()} " +
                "bitRate=${KVUtils.getTaskRecordingBitRate()} " +
                "timeLimit=${AdbScreenRecorder.REQUESTED_TIME_LIMIT_SEC}s"
        val samplePath = File(probeRoot, "selftest_main.mp4").absolutePath

        val summary = buildString {
            append(headline).append("\n\n")
            summaryRows.forEach { append(it).append('\n') }
        }

        val report = buildString {
            append("BQAAgent — Task Recording device self-test\n")
            append("Device : ").append(TaskRecordingStore.deviceModel()).append('\n')
            append("System : ").append(TaskRecordingStore.androidVersion()).append('\n')
            append("App    : ").append(TaskRecordingStore.appVersion()).append('\n')
            append("Run at : ")
                .append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
                .append('\n')
            append("Result : ")
                .append(if (allPass) "ALL PASS ($checks/$checks)" else "$failures FAILED of $checks")
                .append("\n\n")
            append("── Checks ──\n")
            detailLines.forEach { append(it).append('\n') }
            append("\n── Verdict ──\n").append(headline).append("\n\n")
            append("── Persisted configuration ──\n").append(configLine).append("\n\n")
            append("── Sample video ──\n").append(samplePath).append('\n')
        }

        val reportFile = TaskRecordingStore.rootDir()?.let { File(it, "selftest_latest.txt") }
        runCatching { reportFile?.writeText(report) }
        XLog.i(TAG, "\n$report")
        return Result(allPass, report, reportFile, summary)
    }
}