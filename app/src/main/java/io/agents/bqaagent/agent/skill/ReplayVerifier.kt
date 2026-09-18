// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.agent.skill

import io.agents.bqaagent.adb.LocalAdbDeviceDriver
import io.agents.bqaagent.tool.ToolRegistry
import io.agents.bqaagent.utils.XLog

object ReplayVerifier {

    private const val TAG = "ReplayVerifier"

    sealed class VerifyResult {
        object Pass : VerifyResult()
        data class Warning(val reason: String) : VerifyResult()
        data class Fail(val reason: String) : VerifyResult()
    }

    val SYSTEM_DIALOG_PACKAGES = setOf(
        "com.android.permissioncontroller",
        "com.android.systemui",
        "com.android.packageinstaller",
        "com.google.android.permissioncontroller",
        "com.google.android.packageinstaller"
    )

    fun verifyStep(step: RecordedSkillStep, skippedAnchorValues: Set<String> = emptySet()): VerifyResult {
        if (step.expectedPackageAfter.isNotBlank()) {
            val currentPackage = try {
                LocalAdbDeviceDriver.foregroundPackageName()
            } catch (e: Exception) {
                XLog.w(TAG, "Failed to get foreground package", e)
                return VerifyResult.Warning("Cannot read foreground package")
            }

            if (currentPackage.isBlank()) {
                return VerifyResult.Warning("Cannot read foreground package")
            }

            if (currentPackage != step.expectedPackageAfter) {
                if (currentPackage in SYSTEM_DIALOG_PACKAGES) {
                    return VerifyResult.Warning("System dialog detected: $currentPackage")
                }
                return VerifyResult.Fail("Package mismatch: expected=${step.expectedPackageAfter}, actual=$currentPackage")
            }
        }

        if (step.expectedActivityAfter.isNotEmpty()) {
            val currentActivity = try {
                LocalAdbDeviceDriver.activeActivityName()
            } catch (e: Exception) {
                XLog.w(TAG, "Failed to get activity name", e)
                return VerifyResult.Warning("Cannot read activity name")
            }

            if (currentActivity.isEmpty()) {
                return VerifyResult.Warning("Activity name is empty")
            }

            if (!matchesActivity(step.expectedActivityAfter, currentActivity)) {
                if (step.anchorTexts.isEmpty()) {
                    return VerifyResult.Fail("Activity mismatch: expected=${step.expectedActivityAfter}, actual=$currentActivity")
                }
                XLog.d(TAG, "Activity mismatch (expected=${step.expectedActivityAfter}, actual=$currentActivity), trying anchor texts as last resort")
            }
        }

        if (step.anchorTexts.isNotEmpty()) {
            val effectiveAnchors = if (skippedAnchorValues.isEmpty()) {
                step.anchorTexts
            } else {
                step.anchorTexts.filter { anchor ->
                    skippedAnchorValues.none { old -> anchor == old || anchor.contains(old) }
                }
            }
            if (effectiveAnchors.isEmpty()) {
                XLog.d(TAG, "All anchor texts were substituted values, skipping anchor verification")
                return VerifyResult.Pass
            }
            return verifyAnchorTexts(effectiveAnchors)
        }

        return VerifyResult.Pass
    }

    fun autoDismissSystemDialog(): Boolean {
        return try {
            val tool = ToolRegistry.getInstance().getTool("system_key")
            val result = tool?.execute(mapOf("key" to "back"))
            result?.isSuccess == true
        } catch (e: Exception) {
            XLog.w(TAG, "Failed to dismiss system dialog", e)
            false
        }
    }

    private fun matchesActivity(expected: String, actual: String): Boolean {
        if (expected == actual) return true
        val expectedShort = expected.substringAfterLast('.')
        val actualShort = actual.substringAfterLast('.')
        return expectedShort == actualShort
    }

    private fun verifyAnchorTexts(anchorTexts: List<String>): VerifyResult {
        if (anchorTexts.isEmpty()) return VerifyResult.Pass
        try {
            val screenTool = ToolRegistry.getInstance().getTool("get_screen_info")
            val result = screenTool?.execute(mapOf("mode" to "text"))
            if (result == null || !result.isSuccess || result.data == null) {
                return VerifyResult.Warning("Cannot get screen data for anchor text verification")
            }
            val screenText = result.data!!.lowercase()
            val found = anchorTexts.count { screenText.contains(it.lowercase()) }
            val ratio = found.toDouble() / anchorTexts.size
            return when {
                ratio >= 1.0 -> VerifyResult.Pass
                ratio >= 0.5 -> VerifyResult.Warning("Partial anchor match: $found/${anchorTexts.size}")
                else -> VerifyResult.Fail("Anchor texts not found on screen: $anchorTexts")
            }
        } catch (e: Exception) {
            XLog.w(TAG, "Anchor text verification failed", e)
            return VerifyResult.Warning("Anchor text verification error: ${e.message}")
        }
    }
}
