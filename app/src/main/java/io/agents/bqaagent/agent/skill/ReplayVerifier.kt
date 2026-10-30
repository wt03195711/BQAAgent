// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.agent.skill

import io.agents.bqaagent.adb.LocalAdbDeviceDriver
import io.agents.bqaagent.adb.UiNode
import io.agents.bqaagent.tool.ToolRegistry
import io.agents.bqaagent.utils.UiTextMatchUtils
import io.agents.bqaagent.utils.XLog

/**
 * Checks that a replayed step produced the screen the recording produced.
 *
 * Three tiers, in descending authority (see [decide]):
 *  - T1 foreground package, then Activity. Author-written identity, essentially independent of
 *    screen content; the package check is the only veto signal.
 *  - T2 the element the NEXT step acts upon. Captured during recording from this very screen, so
 *    its presence is direct evidence the sequence can continue - the criterion replay actually
 *    cares about. Templates recorded before this field exists carry no expectation and fall back.
 *  - T3 structural keys (resource-id / content-description), or for legacy templates the quoted
 *    labels the old text-mode extractor produced. Screen content legitimately changes between
 *    runs (greeting by time of day, balances, list rows, promotions), so this tier may only
 *    corroborate; it can never fail a step.
 *
 * Missing evidence is not contradicting evidence: only a hard expectation that was satisfied
 * during recording, or two independent negative signals, stops a replay.
 */
object ReplayVerifier {

    private const val TAG = "ReplayVerifier"

    /** Share of stable keys that is enough to call a screen structurally recognised. */
    private const val STABLE_KEY_PASS_RATIO = 0.6

    sealed class VerifyResult {
        object Pass : VerifyResult()

        /**
         * @param escalateOnSensitive false when the warning came from unreadable device data, so
         *   a risky step is not failed because a probe failed.
         */
        data class Warning(
            val reason: String,
            val escalateOnSensitive: Boolean = true
        ) : VerifyResult()

        data class Fail(val reason: String) : VerifyResult()
    }

    private enum class Tier { Present, Soft, Absent, None, Unavailable }

    private enum class ActivityTier { Match, Mismatch, None, Unavailable }

    val SYSTEM_DIALOG_PACKAGES = setOf(
        "com.android.permissioncontroller",
        "com.android.systemui",
        "com.android.packageinstaller",
        "com.google.android.permissioncontroller",
        "com.google.android.packageinstaller"
    )

    /**
     * @param skippedAnchorValues recorded values the user replaced for this run; anchors and
     *   expectations carrying them are substituted away (their absence proves nothing).
     * @param forceNodeRefresh true on polling retries, where the cached node map may still
     *   describe the previous attempt's screen.
     * @param nextStepSubstitution old -> new value for the step that follows, so a substituted
     *   selection target is looked up under its NEW text.
     */
    @JvmOverloads
    fun verifyStep(
        step: RecordedSkillStep,
        skippedAnchorValues: Set<String> = emptySet(),
        forceNodeRefresh: Boolean = false,
        nextStepSubstitution: Pair<String, String>? = null
    ): VerifyResult {
        // ---- T1a: package (the only veto) ----
        val expectedPackage = step.expectedPackageAfter
        if (expectedPackage.isNotBlank()) {
            val currentPackage = try {
                LocalAdbDeviceDriver.foregroundPackageName()
            } catch (e: Exception) {
                XLog.w(TAG, "Failed to get foreground package", e)
                return VerifyResult.Warning("Cannot read foreground package", escalateOnSensitive = false)
            }
            if (currentPackage.isBlank()) {
                return VerifyResult.Warning("Cannot read foreground package", escalateOnSensitive = false)
            }
            if (currentPackage != expectedPackage) {
                if (currentPackage in SYSTEM_DIALOG_PACKAGES) {
                    return VerifyResult.Warning("System dialog detected: $currentPackage", escalateOnSensitive = false)
                }
                return VerifyResult.Fail("Package mismatch: expected=$expectedPackage, actual=$currentPackage")
            }
        }

        // ---- T1b: activity ----
        val activity = activityTier(step)

        // A snapshot, not a per-tier dump: tiers share one node map.
        val needsNodes = step.nextExpectation != null ||
                stableKeysOf(step).isNotEmpty() ||
                anchorsOf(step).isNotEmpty()
        val nodes = if (needsNodes) nodesSnapshot(forceNodeRefresh) else null

        // ---- T2: next-step element ----
        val next = nextExpectationTier(step, nodes, nextStepSubstitution)

        // ---- T3: structure / legacy anchors (softest) ----
        // Tier.None means "this step recorded nothing to compare against" (silent pass), which is
        // NOT the same as Unavailable ("we had evidence to look for but the screen could not be
        // read"). Calling structureTier with a null snapshot would report Unavailable for the
        // first case and would also preempt the activity-mismatch-with-no-evidence veto below.
        val structure = if (needsNodes) structureTier(step, nodes, skippedAnchorValues) else Tier.None

        return decide(step, activity, next, structure)
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

    // ------------------------------------------------------------------ tiers

    private fun activityTier(step: RecordedSkillStep): ActivityTier {
        val expected = step.expectedActivityAfter
        if (expected.isEmpty()) return ActivityTier.None
        val current = try {
            LocalAdbDeviceDriver.activeActivityName()
        } catch (e: Exception) {
            XLog.w(TAG, "Failed to get activity name", e)
            return ActivityTier.Unavailable
        }
        if (current.isEmpty()) return ActivityTier.Unavailable
        return if (matchesActivity(expected, current)) ActivityTier.Match else ActivityTier.Mismatch
    }

    private fun nodesSnapshot(forceRefresh: Boolean): List<UiNode>? {
        return try {
            if (forceRefresh) {
                LocalAdbDeviceDriver.refreshNodeMap()
            } else {
                val cached = LocalAdbDeviceDriver.currentMappedNodes()
                if (cached.isEmpty()) LocalAdbDeviceDriver.refreshNodeMap() else cached
            }
        } catch (e: Exception) {
            XLog.w(TAG, "Node map snapshot failed", e)
            null
        }
    }

    private fun nextExpectationTier(
        step: RecordedSkillStep,
        nodes: List<UiNode>?,
        nextStepSubstitution: Pair<String, String>?
    ): Tier {
        val expectation = step.nextExpectation ?: return Tier.None
        val text = resolveExpectationText(expectation, nextStepSubstitution)
        val desc = expectation.contentDescription
        val id = expectation.resourceId
        if (text.isBlank() && desc.isBlank() && id.isBlank()) return Tier.None
        if (nodes == null || nodes.isEmpty()) return Tier.Unavailable

        // Any one identity field is enough (OR), mirroring the priority chain the replayer uses
        // to relocate nodes: a renamed resource-id after an app update must not fail a step whose
        // label still matches, and vice versa.
        val present = nodes.any { node ->
            node.bounds.width() > 0 && node.bounds.height() > 0 &&
                    (
                            (text.isNotBlank() && (UiTextMatchUtils.matchesExactOrNormalized(node.text, text) ||
                                    UiTextMatchUtils.matchesExactOrNormalized(node.contentDescription, text))) ||
                                    (desc.isNotBlank() && UiTextMatchUtils.matchesExactOrNormalized(node.contentDescription, desc)) ||
                                    (id.isNotBlank() && node.resourceId == id)
                            )
        }
        XLog.d(TAG, "T2 next-step check: ${describeExpectation(expectation)} present=$present strict=${expectation.strict}")
        return when {
            present -> Tier.Present
            expectation.strict -> Tier.Absent
            else -> Tier.Soft
        }
    }

    private fun structureTier(
        step: RecordedSkillStep,
        nodes: List<UiNode>?,
        skippedAnchorValues: Set<String>
    ): Tier {
        if (nodes == null || nodes.isEmpty()) return Tier.Unavailable

        val keys = stableKeysOf(step)
        if (keys.isNotEmpty()) {
            val hit = keys.count { key -> nodes.any { node -> keyMatches(node, key) } }
            val ratio = hit.toDouble() / keys.size
            return when {
                hit == 0 -> Tier.Absent
                ratio >= STABLE_KEY_PASS_RATIO -> Tier.Present
                else -> Tier.Soft
            }
        }

        // Legacy templates: quoted labels captured by the old text-mode extractor. Matched
        // against the same node map instead of a rendered text tree, which is a superset of the
        // tree's labels (the tree renders from these nodes) and costs no extra dump. Soft by
        // definition - volatility of screen content is precisely why this tier lost its veto.
        val anchors = anchorsOf(step).let { list ->
            if (skippedAnchorValues.isEmpty()) list
            else list.filter { anchor -> skippedAnchorValues.none { old -> anchor == old || anchor.contains(old) } }
        }
        if (anchors.isEmpty()) return Tier.None
        val found = anchors.count { anchor -> anyLabelMatches(nodes, anchor) }
        // Soft by construction: any hit corroborates the screen, zero hits is a single negative
        // signal that decide() turns into a warning. The old 0.5 ratio could never fail anything
        // here anymore, so it is dropped rather than kept as dead branches.
        return if (found == 0) Tier.Absent else Tier.Soft
    }

    // ------------------------------------------------------------------ verdict

    private fun decide(
        step: RecordedSkillStep,
        activity: ActivityTier,
        next: Tier,
        structure: Tier
    ): VerifyResult {
        if (next == Tier.Present) {
            return if (activity == ActivityTier.Mismatch) {
                VerifyResult.Warning(
                    "Activity differs (expected=${step.expectedActivityAfter}) but next-step target " +
                            describeExpectation(step.nextExpectation) + " is on screen"
                )
            } else {
                VerifyResult.Pass
            }
        }

        if (next == Tier.Absent) {
            return when (activity) {
                ActivityTier.Unavailable -> VerifyResult.Warning(
                    "Next-step target " + describeExpectation(step.nextExpectation) +
                            " not found and activity is unreadable",
                    escalateOnSensitive = false
                )
                else -> VerifyResult.Fail(
                    "Next-step target " + describeExpectation(step.nextExpectation) +
                            " not found on screen after step ${step.stepIndex + 1} (${step.toolName})"
                )
            }
        }

        if (structure == Tier.Unavailable) {
            return VerifyResult.Warning("Screen data unavailable for verification", escalateOnSensitive = false)
        }

        // No decisive T2 evidence (legacy template, or the next step has no addressable target):
        // T1 plus soft structure. A single negative signal warns; two of them fail.
        return when (activity) {
            ActivityTier.Match -> if (structure == Tier.Absent) {
                VerifyResult.Warning("Activity matches but no recorded structure is on screen")
            } else {
                VerifyResult.Pass
            }

            ActivityTier.Mismatch -> when (structure) {
                Tier.Absent -> VerifyResult.Fail(
                    "Activity mismatch (expected=${step.expectedActivityAfter}) and no recorded structure on screen"
                )
                Tier.None -> VerifyResult.Fail(
                    "Activity mismatch: expected=${step.expectedActivityAfter}, no other evidence"
                )
                else -> VerifyResult.Warning(
                    "Activity mismatch (expected=${step.expectedActivityAfter}), structure evidence partial"
                )
            }

            ActivityTier.None -> if (structure == Tier.Absent) {
                VerifyResult.Warning("No recorded structure is on screen (activity was not recorded)")
            } else {
                VerifyResult.Pass
            }

            ActivityTier.Unavailable -> if (structure == Tier.Absent) {
                VerifyResult.Warning("Activity unreadable and no recorded structure on screen", escalateOnSensitive = false)
            } else {
                VerifyResult.Pass
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun matchesActivity(expected: String, actual: String): Boolean {
        if (expected == actual) return true
        val expectedShort = expected.substringAfterLast('.')
        val actualShort = actual.substringAfterLast('.')
        return expectedShort == actualShort
    }

    private fun resolveExpectationText(
        expectation: NextStepExpectation,
        substitution: Pair<String, String>?
    ): String {
        val text = expectation.text
        if (substitution == null || text.isBlank()) return text
        val (oldValue, newValue) = substitution
        if (oldValue.isBlank()) return text
        if (text == oldValue) return newValue
        if (text.contains(oldValue)) return text.replace(oldValue, newValue)
        return text
    }

    private fun describeExpectation(expectation: NextStepExpectation?): String {
        if (expectation == null) return "<none>"
        val label = when {
            expectation.text.isNotBlank() -> "\"${expectation.text}\""
            expectation.contentDescription.isNotBlank() -> "desc \"${expectation.contentDescription}\""
            else -> "id \"${expectation.resourceId}\""
        }
        return "$label (origin=${expectation.origin.ifBlank { "unknown" }})"
    }

    private fun keyMatches(node: UiNode, key: StableElementKey): Boolean {
        if (key.resourceId.isNotBlank() && node.resourceId == key.resourceId) return true
        if (key.contentDescription.isNotBlank() &&
            UiTextMatchUtils.matchesExactOrNormalized(node.contentDescription, key.contentDescription)
        ) return true
        if (key.text.isNotBlank() && UiTextMatchUtils.matchesExactOrNormalized(node.text, key.text)) return true
        return false
    }

    private fun anyLabelMatches(nodes: List<UiNode>, anchor: String): Boolean {
        val needle = anchor.lowercase()
        return nodes.any { node ->
            UiTextMatchUtils.matchesExactOrNormalized(node.text, anchor) ||
                    UiTextMatchUtils.matchesExactOrNormalized(node.contentDescription, anchor) ||
                    node.text.lowercase().contains(needle) ||
                    node.contentDescription.lowercase().contains(needle)
        }
    }

    /**
     * Defensive reads: Gson bypasses Kotlin default arguments, so fields added after a
     * template was persisted can arrive as null at runtime even when typed non-null.
     */
    private fun anchorsOf(step: RecordedSkillStep): List<String> {
        val raw: List<String>? = step.anchorTexts
        return raw?.filter { it.isNotBlank() } ?: emptyList()
    }

    private fun stableKeysOf(step: RecordedSkillStep): List<StableElementKey> = step.stableKeys.orEmpty()
}
