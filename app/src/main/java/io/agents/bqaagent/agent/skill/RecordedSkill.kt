// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.agent.skill

data class RecordedSkillStep(
    val stepIndex: Int,
    val toolName: String,
    val displayName: String,
    val params: Map<String, Any>,
    val elapsedMs: Long = 0L,
    val expectedPackageAfter: String = "",
    val expectedActivityAfter: String = "",
    /**
     * LEGACY verification evidence (T3 anchors). Nothing writes this field anymore: new
     * templates leave it null, so Gson omits the key from the persisted JSON entirely (no more
     * `"anchorTexts": []`). It is deserialized only so historical templates keep their structural
     * fallback. Do not delete: ReplayVerifier.structureTier reads it exclusively when stableKeys
     * is absent.
     */
    val anchorTexts: List<String>? = null,
    val nodeLocator: NodeLocator? = null,
    val targetText: String = "",
    val intent: String? = null,
    val sensitive: Boolean = false,
    val confirmSummary: String = "",
    /**
     * Identity of the element the NEXT recorded step acts upon, captured while the node map
     * still described THIS step's resulting screen. Primary replay-verification evidence: if
     * the next step's target is present, this step's outcome is usable.
     *
     * Nullable on purpose - templates saved before this field existed deserialize to null,
     * which means "no expectation for this step" and the verifier silently falls back to
     * activity + structural evidence (Gson ignores Kotlin default arguments).
     */
    val nextExpectation: NextStepExpectation? = null,
    /**
     * Author-written identity fields (resource-id / content-description) of the screen this
     * step produced. Soft corroborating evidence only; a miss can never fail a step because
     * screen content legitimately differs between runs. Null = legacy template.
     */
    val stableKeys: List<StableElementKey>? = null
)

data class NodeLocator(
    val paramName: String = "",
    val text: String = "",
    val contentDescription: String = "",
    val resourceId: String = "",
    val boundsLeft: Int = 0,
    val boundsTop: Int = 0,
    val boundsRight: Int = 0,
    val boundsBottom: Int = 0
)

/**
 * What must be on screen after the step that carries this expectation.
 *
 * Deliberately does NOT reuse NodeLocator: recorded bounds are resolution/font-scale
 * dependent and would "match" any screen, turning the check into a no-op, so geometry is
 * not part of an existence assertion at all.
 *
 * @param strict true when the recorded flow found this element without scrolling, so its
 *   absence is a real failure; false when the element may only appear after scrolling, or
 *   when the label is value-like (digits/currency), in which case absence is a warning.
 * @param origin capture provenience for diagnostics (NODE / INPUT_FIELD / LABEL_PARAM / COORD_LABEL)
 */
data class NextStepExpectation(
    val text: String = "",
    val contentDescription: String = "",
    val resourceId: String = "",
    val strict: Boolean = true,
    val origin: String = ""
)

/**
 * Stable structural key of a screen: at least one of [resourceId] / [contentDescription] is
 * non-blank (enforced at capture time). [text] is kept only when it does not look value-like.
 */
data class StableElementKey(
    val resourceId: String = "",
    val contentDescription: String = "",
    val text: String = ""
)

data class ReplayResult(
    val success: Boolean,
    val stepsUsed: Int,
    val tokensUsed: Int = 0,
    val fallbackUsed: Boolean = false,
    val message: String = "",
    val cancelled: Boolean = false,
    val rejected: Boolean = false,
    /**
     * Verification warnings collected during the run. They never blocked execution, but they
     * must be reported: a replay that succeeded while steps were merely plausible is worth
     * showing to the user, and is the signal for "this template should be re-recorded".
     */
    val warnings: List<String> = emptyList()
)
