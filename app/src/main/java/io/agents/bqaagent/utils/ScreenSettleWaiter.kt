// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.
package io.agents.bqaagent.utils

import io.agents.bqaagent.ClawApplication
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

    /**
     * Snapshot the current screen fingerprint BEFORE executing an action. Pass
     * the returned value to [waitForScreenSettle] so settle detection can
     * require the screen to actually change relative to this baseline.
     */
    @JvmStatic
    fun captureBaseline(): String = sampleScreenFingerprint()

    /**
     * Wait until the screen after [toolName] is ready. Never throws; on timeout
     * it proceeds with whatever screen is present. Returns true if settled.
     *
     * @param baselineFingerprint pre-action fingerprint from [captureBaseline];
     *        empty skips the change gate (legacy behavior).
     * @param budgetMs maximum time to spend in fingerprint sampling.
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
        return waitForScreenFingerprintSettle(baselineFingerprint, budgetMs, isCancelled)
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

        // Stage 1 (baseline only): did the action change the screen at all?
        // Unchanged → short fixed wait, then one re-check: still unchanged means
        // there is nothing to wait for; changed during the wait means a late
        // transition started and stability detection takes over in stage 2.
        if (baselineFingerprint.isNotEmpty()) {
            val fingerprint = sampleScreenFingerprint()
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
                val recheck = sampleScreenFingerprint()
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

        // Stage 2: wait until the screen stops changing.
        while (System.currentTimeMillis() < deadline) {
            if (isCancelled()) {
                XLog.i(TAG, "Screen settle interrupted by cancellation")
                return false
            }
            val fingerprint = sampleScreenFingerprint()
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
     * Fingerprint = foreground package/activity + hash of the screen tree body.
     * The first tree line is dropped because it contains an incrementing snapshot
     * id (snapshot=sXX), which would make every fingerprint differ. Cache is
     * invalidated first so each sample is a genuine fresh dump; the final stable
     * sample stays in cache and is reused by the subsequent screen capture.
     */
    private fun sampleScreenFingerprint(): String {
        val foreground = try {
            "${LocalAdbDeviceDriver.foregroundPackageName()}/${LocalAdbDeviceDriver.activeActivityName()}"
        } catch (_: Exception) {
            ""
        }
        return try {
            LocalAdbDeviceDriver.invalidateScreenCache()
            val tree = LocalAdbDeviceDriver.getScreenTree() ?: ""
            val body = tree.lineSequence().drop(1).joinToString("\n")
            "$foreground#${body.hashCode()}"
        } catch (e: Exception) {
            "$foreground#error:${e.javaClass.simpleName}"
        }
    }
}