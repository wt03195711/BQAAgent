// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.
package io.agents.bqaagent.utils

import io.agents.bqaagent.ClawApplication
import io.agents.bqaagent.adb.LocalAdbAutomation
import io.agents.bqaagent.adb.LocalAdbDeviceDriver
import io.agents.bqaagent.utils.XLog

/**
 * Unified "wait until the screen is ready" waiter used by BOTH the recording
 * path (Opt-3 screen capture) and the replay path (pre-verification), so that
 * anchor texts are recorded and verified against screens captured identically.
 *
 * Phase 1 (launch tools only): open_app/open_url start apps asynchronously;
 * poll the cheap foreground package until it leaves this agent app and stays
 * stable. Without this gate, fingerprint sampling could settle on the agent's
 * own pre-launch screen and record wrong anchors.
 *
 * Phase 2 (all action tools): two-stage detection against the pre-action
 * baseline fingerprint. Stage 1 asks "did the action change the screen at
 * all?": an unchanged first sample triggers a short fixed wait
 * (NO_CHANGE_WAIT_MS) plus one re-check, so same-page inputs / no-op scrolls
 * settle in a few seconds instead of burning the whole budget; a change
 * observed before or during that wait is handed to stage 2, which waits
 * until the new screen stops changing (two consecutive identical samples).
 * Without a baseline the stage-1 gate is skipped. Stage 2 is bounded only by
 * the caller-provided time budget, never by a fixed sample count, so
 * slow-loading pages are waited out instead of abandoned.
 */
object ScreenSettleWaiter {

    private const val TAG = "ScreenSettleWaiter"

    /** Tools whose action is an asynchronous app launch */
    private val LAUNCH_TOOLS = setOf("open_app", "open_url")

    // Phase 1: foreground leave-self polling (dumpsys only, no uiautomator dump)
    private const val LAUNCH_PHASE_MAX_MS = 6_000L
    private const val LAUNCH_POLL_MS = 200L
    private const val LAUNCH_STABLE_POLLS = 2

    // Phase 2: fingerprint settle detection
    const val SETTLE_MAX_MS = 8_000L
    private const val INITIAL_DELAY_MS = 150L
    private const val SAMPLE_GAP_MS = 200L

    /**
     * Fixed wait for actions whose first post-action sample still equals the
     * pre-action baseline (same-page input, no-op scrolls). A re-check after
     * this wait decides whether the screen really stayed unchanged (settle
     * immediately) or a late transition started (hand over to stability
     * detection).
     */
    private const val NO_CHANGE_WAIT_MS = 2_000L
    private const val SCREENCAP_TIMEOUT_MS = 3_000L

    /**
     * Snapshot the current screen fingerprint BEFORE executing an action. Pass
     * the returned value to [waitForScreenSettle] so settle detection can
     * require the screen to actually change relative to this baseline.
     */
    @JvmStatic
    @JvmOverloads
    fun captureBaseline(reuseFreshCache: Boolean = false): String =
        screenFingerprint(reuseFreshCache)

    /**
     * Wait until the screen after [toolName] is ready. Never throws; on timeout
     * it proceeds with whatever screen is present. Returns true if settled.
     */
    @JvmStatic
    @JvmOverloads
    fun waitForScreenSettle(
        toolName: String = "",
        baselineFingerprint: String = "",
        budgetMs: Long = SETTLE_MAX_MS,
        isCancelled: () -> Boolean = { false }
    ): Boolean {
        if (toolName in LAUNCH_TOOLS) {
            waitForForegroundLeaveSelf(isCancelled)
            if (isCancelled()) return false
        }
        val settled = waitForScreenFingerprintSettle(
            baselineFingerprint,
            budgetMs,
            isCancelled
        )
        // The stability loop uses a cheap screencap fingerprint and no longer reads the
        // uiautomator tree per sample, so do exactly ONE authoritative tree read here to
        // keep the on-exit contract: the cache holds a fresh, stable screen tree that the
        // caller's verify / node resolve (replay) and anchor capture (recording) reuse
        // with zero extra dumps. Skipped when the task was cancelled.
        if (!isCancelled()) {
            try {
                LocalAdbDeviceDriver.invalidateScreenCache()
                LocalAdbDeviceDriver.getScreenTree()
            } catch (e: Exception) {
                XLog.w(TAG, "Authoritative tree read failed: ${e.javaClass.simpleName}")
            }
        }
        return settled
    }

    private fun waitForForegroundLeaveSelf(isCancelled: () -> Boolean) {
        val selfPackage = ClawApplication.instance.packageName
        val deadline = System.currentTimeMillis() + LAUNCH_PHASE_MAX_MS
        var lastPkg = ""
        var stableCount = 0
        while (System.currentTimeMillis() < deadline) {
            if (isCancelled()) {
                XLog.i(TAG, "Launch foreground settle interrupted by cancellation")
                return
            }
            try {
                Thread.sleep(LAUNCH_POLL_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            val pkg = try {
                LocalAdbDeviceDriver.foregroundPackageName()
            } catch (_: Exception) {
                ""
            }
            if (pkg.isNotBlank() && pkg != selfPackage) {
                if (pkg == lastPkg) {
                    stableCount++
                    if (stableCount >= LAUNCH_STABLE_POLLS) {
                        XLog.i(TAG, "Launch foreground settled: $pkg")
                        return
                    }
                } else {
                    stableCount = 0
                }
            } else {
                stableCount = 0
            }
            lastPkg = pkg
        }
        XLog.w(TAG, "Launch foreground settle timeout after ${LAUNCH_PHASE_MAX_MS}ms")
    }

    private fun waitForScreenFingerprintSettle(
        baselineFingerprint: String,
        budgetMs: Long,
        isCancelled: () -> Boolean
    ): Boolean {
        val startedAt = System.currentTimeMillis()
        val deadline = startedAt + maxOf(budgetMs, 0L)
        var lastFingerprint = ""
        var samples = 0

        if (baselineFingerprint.isNotEmpty()) {
            val fingerprint = screenFingerprint()
            samples++
            if (fingerprint == baselineFingerprint) {
                try {
                    Thread.sleep(NO_CHANGE_WAIT_MS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
                if (isCancelled()) {
                    XLog.i(TAG, "Screen settle interrupted by cancellation")
                    return false
                }
                if (System.currentTimeMillis() >= deadline) {
                    XLog.w(TAG, "Screen settle budget exhausted during no-change wait (${System.currentTimeMillis() - startedAt}ms), proceeding anyway")
                    return false
                }
                val recheck = screenFingerprint()
                samples++
                if (recheck == baselineFingerprint) {
                    XLog.i(TAG, "Screen unchanged vs baseline after ${NO_CHANGE_WAIT_MS}ms wait (${System.currentTimeMillis() - startedAt}ms total): action did not visibly change the screen")
                    return true
                }
                XLog.d(TAG, "Screen changed vs baseline during no-change wait after $samples samples")
                lastFingerprint = recheck
            } else {
                XLog.d(TAG, "Screen changed vs baseline after $samples samples")
                lastFingerprint = fingerprint
            }
        }

        while (System.currentTimeMillis() < deadline) {
            if (isCancelled()) {
                XLog.i(TAG, "Screen settle interrupted by cancellation")
                return false
            }
            val fingerprint = screenFingerprint()
            samples++
            if (samples >= 2 && fingerprint == lastFingerprint) {
                XLog.i(TAG, "Screen settled after $samples samples (${System.currentTimeMillis() - startedAt}ms)")
                return true
            }
            lastFingerprint = fingerprint
            try {
                Thread.sleep(SAMPLE_GAP_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        XLog.w(TAG, "Screen settle gave up after $samples samples (${System.currentTimeMillis() - startedAt}ms), proceeding anyway")
        return false
    }

    /**
     * Fingerprint used ONLY as the fallback signal: foreground package/activity +
     * hash of the uiautomator tree body. Costs ~2s per call (a full dump), so it is
     * now only used when screencap hashing is unavailable on the device. Tree
     * fingerprints are returned WITHOUT the "shot:" prefix, so a mid-run switch
     * between the two signals can never falsely compare equal (it only makes
     * samples differ, i.e. the conservative direction).
     */
    private fun sampleScreenFingerprint(reuseFreshCache: Boolean = false): String {
        val foreground = try {
            val (pkg, act) = LocalAdbDeviceDriver.foregroundPackageAndActivity()
            "$pkg/$act"
        } catch (_: Exception) {
            ""
        }
        return try {
            if (!reuseFreshCache) {
                LocalAdbDeviceDriver.invalidateScreenCache()
            }
            val tree = LocalAdbDeviceDriver.getScreenTree() ?: ""
            val body = tree.lineSequence().drop(1).joinToString("\n")
            "$foreground#${body.hashCode()}"
        } catch (e: Exception) {
            "$foreground#error:${e.javaClass.simpleName}"
        }
    }

    @Volatile
    private var screencapUnavailable = false

    /**
     * Primary stability fingerprint: foreground package/activity + a device-side
     * md5 of a screencap (pixels). ~160-300ms per sample vs ~2000ms for a uiautomator
     * tree read, which is what made every settle cost 4-8s. Pixels are the right
     * "did the screen change / stop changing" proxy and also stay sensitive to
     * FLAG_SECURE surfaces (a constant black capture simply compares equal). If the
     * device lacks screencap/md5sum, this sticky-switches to the tree signal for all
     * subsequent samples so correctness is preserved at the cost of speed.
     */
    private fun screenFingerprint(reuseFreshCache: Boolean = false): String {
        if (screencapUnavailable) {
            return sampleScreenFingerprint(reuseFreshCache)
        }
        val foreground = try {
            val (pkg, act) = LocalAdbDeviceDriver.foregroundPackageAndActivity()
            "$pkg/$act"
        } catch (_: Exception) {
            ""
        }
        val hash = screenshotHash()
        if (hash == null) {
            screencapUnavailable = true
            XLog.w(TAG, "screencap fingerprint unavailable; falling back to uiautomator tree fingerprint for all samples")
            return sampleScreenFingerprint(reuseFreshCache)
        }
        return "shot:$foreground#$hash"
    }

    private val MD5_HEX = Regex("^[0-9a-fA-F]{32}$")

    private fun screenshotHash(): String? {
        return try {
            val out = LocalAdbAutomation
                .exec("screencap -p | md5sum", SCREENCAP_TIMEOUT_MS)
                .stdout
                .trim()
                .substringBefore(' ')
                .trim()
            if (out.matches(MD5_HEX)) out else null
        } catch (e: Exception) {
            null
        }
    }
}