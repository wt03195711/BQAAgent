// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.agent.skill

import io.agents.bqaagent.adb.LocalAdbDeviceDriver
import io.agents.bqaagent.adb.UiNode
import io.agents.bqaagent.tool.ToolRegistry
import io.agents.bqaagent.utils.XLog

object SkillRecorder {

    private const val TAG = "SkillRecorder"
    private const val MIN_STEPS_TO_RECORD = 2

    private val NODE_ID_PATTERN = Regex("^n\\d+$")

    /** Maximum character length of a recorded step intent */
    private const val STEP_INTENT_MAX_LENGTH = 120
    /** Casual introductory fillers stripped from recorded intents */
    private val CASUAL_INTRO_REGEX = Regex(
        "^(?:Okay|OK|Ok|Alright|Sure|Great|Now|Well|好的|接下来|那么)[,，:\\s]+",
        RegexOption.IGNORE_CASE
    )
    private val FIRST_SENTENCE_REGEX = Regex("^(.*?[.!?。！？])")

    private val RECORDABLE_TOOLS = setOf(
        "open_app", "open_url",
        "tap_visible_text", "tap_node", "tap", "long_press",
        "find_and_tap", "scroll_to_find",
        "input_text", "input_amount",
        "secure_keypad_input",
        "system_key",
        "swipe",
        "wait",
        // Composite loop tool: recorded as ONE opaque step. Its embedded actions replay
        // verbatim (substitution cannot reach inside the actions JSON), and step verification
        // relies on package/activity + post-loop stable keys (no next-step target element).
        "repeat_actions",
        "send_message", "send_file", "clipboard",
        "make_call", "auto_reply"
    )

    /**
     * Tools whose next action addresses an element that is ALREADY on the current screen, so
     * the element's absence after the previous step is a genuine failure.
     * open_app/open_url (launch), system_key/swipe (no target element), wait and the read-only
     * tools are intentionally absent: they define no expectation at all.
     */
    private val NEXT_STEP_STRICT_TOOLS = setOf(
        "tap_node", "tap", "long_press", "tap_visible_text",
        "input_text", "input_amount", "secure_keypad_input"
    )

    /**
     * Tools that scroll until the target appears (FindAndTapTool / ScrollToFindTool), so the
     * target may legitimately be off-screen right after the previous step: absence is a warning.
     */
    private val NEXT_STEP_LENIENT_TOOLS = setOf("find_and_tap", "scroll_to_find")

    /**
     * Tools whose value parameter IS an on-screen label (getValueParamNames() = "text"),
     * as opposed to the content being typed or the package being launched.
     */
    private val NEXT_STEP_LABEL_PARAM_TOOLS = setOf("tap_visible_text", "find_and_tap", "scroll_to_find")

    /** Tools that act on a field; only the field node is assertable, never its content. */
    private val INPUT_TARGET_TOOLS = setOf("input_text", "input_amount", "secure_keypad_input")

    /**
     * Reasons for an empty targetText that are expected by design, NOT a capture failure:
     *  - INPUT_VALUE: an input tool's value param is the payload being typed (text / amount /
     *    PIN digits); we deliberately never store it as a target label (field identity lives in
     *    nodeLocator / the next-step expectation instead).
     *  - NOT_APPLICABLE: non-target action tools (swipe, system_key, wait, repeat_actions, …)
     *    act on no labelled on-screen element, so there is nothing to reverse-look-up.
     * Every other empty reason (BAD_COORDS / POINT_IN_GAP / NO_DUMP / NODE_NO_TEXT / ERROR) is a
     * genuine miss and stays a warning.
     */
    private val EXPECTED_EMPTY_TARGET_REASONS = setOf("INPUT_VALUE", "NOT_APPLICABLE")

    /** Labels carrying these characters are content values (amounts, dates, counters), not identity. */
    private val VOLATILE_LABEL_SYMBOLS = charArrayOf('¥', '￥', '$', '€', '£', '%')

    /**
     * Cap on structural keys stored per step. This is soft (T3) verification evidence that can
     * only corroborate a screen - it never fails a step on its own - so a handful of the
     * strongest identity keys is plenty; capture keeps the highest-authority ones (resource-id,
     * then content-description) and drops the rest.
     */
    private const val STABLE_KEYS_MAX = 5

    @Volatile
    private var isRecording = false

    @Volatile
    private var recordingEnabled = true
    private var startParams: RecordStartParams? = null
    private val recordedSteps = mutableListOf<RecordedSkillStep>()

    private data class RecordStartParams(
        val rawUserRequest: String,
    )

    /**
     * Enable recording for normal AgentLoop tasks (scenario 1 / scenario 2).
     */
    fun enableRecording() {
        recordingEnabled = true
    }

    /**
     * Disable recording for fallback AgentLoop after replay failure (scenario 3):
     * the mixed replay+AgentLoop flow produces unreliable step data.
     */
    fun disableRecording() {
        recordingEnabled = false
        discard()
        XLog.d(TAG, "Recording disabled (fallback flow)")
    }

    @Synchronized
    fun startRecording(
        rawUserRequest: String
    ) {
        if (!recordingEnabled) {
            XLog.d(TAG, "Recording disabled, skipping startRecording")
            return
        }
        isRecording = true
        recordedSteps.clear()
        startParams = RecordStartParams(
            rawUserRequest = rawUserRequest
        )
        XLog.i(TAG, "Recording started for task: ${rawUserRequest.take(80)}")
    }

    fun isRecordingActive(): Boolean = isRecording

    @Synchronized
    fun recordStep(
        toolName: String,
        displayName: String,
        params: Map<String, Any>,
        elapsedMs: Long = 0L,
        expectedPackageAfter: String = "",
        expectedActivityAfter: String = "",
        nodeLocator: NodeLocator? = null,
        intent: String = "",
        precomputedTargetText: String? = null,
        sensitive: Boolean = false,
        confirmSummary: String = "",
        stableKeys: List<StableElementKey>? = null
    ) {
        if (!isRecording) return
        if (toolName !in RECORDABLE_TOOLS) return

        // anchorTexts is a LEGACY-only verification evidence: new templates never write it, not
        // even when structural capture came up empty (that step then honestly carries no T3
        // evidence and verification falls back to package/activity + next-step expectation).
        // The field and the verifier's anchors branch exist solely for historical templates.

        // NEVER re-capture locator / targetText here: recordStep runs AFTER
        // execution, when Opt-3 has already rebuilt nodeIdMap for the NEW
        // screen. Any lookup at this point would resolve against the wrong
        // screen. Pre-execution captures passed in by the agent loop are the
        // only trusted source; missing data is recorded as-is (empty/null).
        // stableKeys is the one exception by design: it is read from that same
        // post-action node map (the screen this step produced) and never by nodeId.
        val step = RecordedSkillStep(
            stepIndex = recordedSteps.size,
            toolName = toolName,
            displayName = displayName,
            params = params,
            elapsedMs = elapsedMs,
            expectedPackageAfter = expectedPackageAfter,
            expectedActivityAfter = expectedActivityAfter,
            anchorTexts = null,
            nodeLocator = nodeLocator,
            targetText = precomputedTargetText.orEmpty(),
            intent = normalizeStepIntent(intent).takeIf { it.isNotBlank() },
            sensitive = sensitive,
            confirmSummary = confirmSummary,
            stableKeys = stableKeys
        )
        recordedSteps.add(step)
        XLog.d(TAG, "Recorded step ${step.stepIndex}: $toolName")
    }

    /**
     * Back-fill the previous recorded step with the element THIS round is about to act upon.
     *
     * Timing is the whole trick: the agent loop calls this after the previous step's Opt-3
     * refresh and before the current tool runs, so nodeIdMap still describes the screen the
     * previous step produced, while the locator/target text about to be used is exactly the
     * element that screen must expose for the sequence to continue. Costs no extra dump and
     * no LLM round - the data is already captured for the step itself.
     *
     * First expectation wins: an intervening read-only round must not overwrite a stronger
     * expectation, and a step never gets more than one.
     */
    @Synchronized
    fun attachNextStepExpectation(
        toolName: String,
        locator: NodeLocator?,
        targetText: String,
        targetTextSource: String
    ) {
        if (!isRecording) return
        val expectation = buildNextStepExpectation(toolName, locator, targetText, targetTextSource) ?: return
        val lastIndex = recordedSteps.size - 1
        if (lastIndex < 0) return
        val previous = recordedSteps[lastIndex]
        if (previous.nextExpectation != null) return
        recordedSteps[lastIndex] = previous.copy(nextExpectation = expectation)
        XLog.d(
            TAG, "Attached next-step expectation to step ${previous.stepIndex} (${previous.toolName}): " +
                    "text='${expectation.text}' desc='${expectation.contentDescription}' " +
                    "id='${expectation.resourceId}' strict=${expectation.strict} origin=${expectation.origin}"
        )
    }

    private fun buildNextStepExpectation(
        toolName: String,
        locator: NodeLocator?,
        targetText: String,
        targetTextSource: String
    ): NextStepExpectation? {
        val strict = when {
            toolName in NEXT_STEP_STRICT_TOOLS -> true
            toolName in NEXT_STEP_LENIENT_TOOLS -> false
            else -> return null
        }
        var text = ""
        var desc = ""
        var id = ""
        var origin = ""
        if (locator != null) {
            id = locator.resourceId.trim()
            desc = locator.contentDescription.trim()
            // An input step's field is identified by id/desc only: its current text is the
            // value being typed, or a leftover from the recording run - never a stable identity.
            text = if (toolName in INPUT_TARGET_TOOLS) "" else locator.text.trim()
            origin = if (toolName in INPUT_TARGET_TOOLS) "INPUT_FIELD" else "NODE"
        } else {
            if (toolName in INPUT_TARGET_TOOLS) return null
            val label = targetText.trim()
            if (label.isEmpty()) return null
            val usable = targetTextSource.startsWith("COORD_") ||
                    (targetTextSource == "VALUE_PARAM" && toolName in NEXT_STEP_LABEL_PARAM_TOOLS)
            if (!usable) return null
            text = label
            origin = if (targetTextSource == "VALUE_PARAM") "LABEL_PARAM" else "COORD_LABEL"
        }
        if (text.isBlank() && desc.isBlank() && id.isBlank()) return null
        // A value-like label is a legitimate tap target but not a stable identity across runs:
        // keep it as evidence, but its absence may not fail the step.
        val effectiveStrict = strict && !(text.isNotBlank() && isVolatileLabel(text))
        return NextStepExpectation(
            text = text,
            contentDescription = desc,
            resourceId = id,
            strict = effectiveStrict,
            origin = origin
        )
    }

    /**
     * Stable structural keys of the CURRENT screen (verification T3). Only author-written
     * identity survives content changes, so resource-id / content-description are required and
     * value-like labels are dropped. Soft evidence by construction: capture is best-effort and a
     * failure to read the node map simply yields no keys.
     */
    @Synchronized
    fun captureStableKeys(): List<StableElementKey> {
        if (!isRecording) return emptyList()
        return try {
            val nodes = LocalAdbDeviceDriver.currentMappedNodes()
            if (nodes.isEmpty() || isNodeMapFromOtherApp()) {
                XLog.d(TAG, "Stable keys skipped: ${nodeMapDiag()}")
                emptyList()
            } else {
                nodes.asSequence()
                    .filter { it.enabled && it.bounds.width() > 0 && it.bounds.height() > 0 }
                    .map { node ->
                        StableElementKey(
                            resourceId = node.resourceId.trim(),
                            contentDescription = node.contentDescription.trim(),
                            text = node.text.trim()
                        )
                    }
                    .filter { it.resourceId.isNotBlank() || it.contentDescription.isNotBlank() }
                    .filter { it.text.isBlank() || !isVolatileLabel(it.text) }
                    .distinct()
                    .sortedWith(
                        compareByDescending<StableElementKey> { it.resourceId.isNotBlank() }
                            .thenByDescending { it.contentDescription.isNotBlank() }
                    )
                    .take(STABLE_KEYS_MAX)
                    .toList()
            }
        } catch (e: Exception) {
            XLog.w(TAG, "captureStableKeys failed: ${e.message}")
            emptyList()
        }
    }

    /** Digits / currency / percent make a label a value, not an identity. */
    private fun isVolatileLabel(label: String): Boolean {
        if (label.isBlank()) return true
        return label.any { it.isDigit() || it in VOLATILE_LABEL_SYMBOLS }
    }

    fun isRecordableTool(toolName: String): Boolean = toolName in RECORDABLE_TOOLS

    /**
     * Normalize the LLM-provided step intent into a concise, formal narration:
     * collapse whitespace, keep only the first sentence, drop casual introductory
     * fillers and cap the length. Applied once at record time, so both the
     * in-memory RecordedSkillStep and the persisted template JSON carry it.
     */
    private fun normalizeStepIntent(raw: String): String {
        if (raw.isBlank()) return ""
        val collapsed = raw.replace(Regex("\\s+"), " ").trim()
        val firstSentence = FIRST_SENTENCE_REGEX.find(collapsed)?.groupValues?.get(1) ?: collapsed
        return CASUAL_INTRO_REGEX.replace(firstSentence, "")
            .take(STEP_INTENT_MAX_LENGTH)
            .trim()
    }

    /**
     * Capture a NodeLocator snapshot from the CURRENT nodeIdMap.
     * Must be called BEFORE tool execution: action tools trigger Opt-3
     * post-screen refresh which rebuilds nodeIdMap for the NEW screen,
     * making the same nodeId point to a different (often empty) node.
     */
    fun captureNodeLocator(params: Map<String, Any>): NodeLocator? {
        return buildNodeLocator(params)
    }

    private fun buildNodeLocator(params: Map<String, Any>): NodeLocator? {
        val entry = params.entries.firstOrNull { (_, value) ->
            NODE_ID_PATTERN.matches(normalizeNodeId(value.toString()))
        } ?: return null
        val nodeId = normalizeNodeId(entry.value.toString())
        val node = try {
            LocalAdbDeviceDriver.getNode(nodeId)
        } catch (_: Exception) {
            null
        } ?: return null

        // Label enrichment: a nameless clickable container (e.g. HSBC-style
        // "container + child TextView") records blank text/desc, which forces replay
        // into boundsFallback (fragile across resolutions/layouts). Borrow the smallest
        // contained labelled descendant's text — the SAME algorithm detail/compact
        // rendering uses (LocalAdbDeviceDriver.containedLabelFor) — so replay can
        // relocate the target by text. Only fills the blank case; never overrides a
        // real self label.
        var text = node.text
        if (text.isBlank() && node.contentDescription.isBlank()) {
            val all = try {
                LocalAdbDeviceDriver.currentMappedNodes()
            } catch (_: Exception) {
                emptyList()
            }
            val contained = LocalAdbDeviceDriver.containedLabelFor(node, all)
            if (contained.isNotBlank()) {
                text = contained
                XLog.i(TAG, "NodeLocator text enriched from contained descendant: '${contained.take(40)}'")
            }
        }

        return NodeLocator(
            paramName = entry.key,
            text = text,
            contentDescription = node.contentDescription,
            resourceId = node.resourceId,
            boundsLeft = node.bounds.left,
            boundsTop = node.bounds.top,
            boundsRight = node.bounds.right,
            boundsBottom = node.bounds.bottom
        )
    }

    private fun normalizeNodeId(value: String): String {
        return value.replace("[", "").replace("]", "").trim()
    }

    /**
     * Public entry for the agent loop: capture the step's target text BEFORE
     * execution so coordinate reverse-lookup sees the screen being acted on.
     */
    fun captureTargetText(toolName: String, params: Map<String, Any>, locator: NodeLocator?): String {
        return captureTargetTextDetailed(toolName, params, locator).text
    }

    /**
     * Same single capture, with provenience. The agent loop needs the reason because a
     * captured string only qualifies as a next-step expectation when it describes an element
     * on screen, which depends on the tool that produced it (see [attachNextStepExpectation]).
     */
    fun captureTargetTextInfo(
        toolName: String,
        params: Map<String, Any>,
        locator: NodeLocator?
    ): TargetTextCapture {
        return captureTargetTextDetailed(toolName, params, locator)
    }

    data class TargetTextCapture(val text: String, val reason: String)

    private fun captureTargetTextDetailed(toolName: String, params: Map<String, Any>, locator: NodeLocator?): TargetTextCapture {
        val capture = try {
            when {
                toolName == "tap_node" -> {
                    val t = locator?.text?.trim().orEmpty()
                    if (t.isNotEmpty()) TargetTextCapture(t, "LOCATOR_TEXT")
                    else {
                        val d = locator?.contentDescription?.trim().orEmpty()
                        if (d.isNotEmpty()) TargetTextCapture(d, "LOCATOR_DESC")
                        else TargetTextCapture("", "LOCATOR_NO_LABEL")
                    }
                }
                else -> {
                    val valueKeys = ToolRegistry.getInstance().getTool(toolName)?.getValueParamNames() ?: emptyList()
                    val fromParam = valueKeys.firstNotNullOfOrNull { key ->
                        params[key]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                    }
                    // INPUT tools: the value param is the payload being typed (text / amount / PIN
                    // digits), NOT an on-screen target label. Storing it as targetText would persist a
                    // sensitive value that replay never reads (targetText is consumed only by
                    // SELECTION_TOOLS) and the real value already lives in step.params. Record it as
                    // absent - the field identity is captured via nodeLocator / next-step expectation.
                    if (fromParam != null && toolName in INPUT_TARGET_TOOLS) {
                        TargetTextCapture("", "INPUT_VALUE")
                    } else if (fromParam != null) {
                        TargetTextCapture(fromParam, "VALUE_PARAM")
                    } else if (toolName == "tap" || toolName == "long_press") lookupTextByCoordinate(params)
                    else TargetTextCapture("", "NOT_APPLICABLE")
                }
            }
        } catch (e: Exception) {
            XLog.w(TAG, "captureTargetText failed for $toolName: ${e.message}")
            TargetTextCapture("", "ERROR")
        }
        if (capture.text.isEmpty()) {
            if (capture.reason in EXPECTED_EMPTY_TARGET_REASONS) {
                XLog.d(TAG, "TargetText intentionally empty: tool=$toolName reason=${capture.reason}")
            } else {
                XLog.w(TAG, "TargetText capture EMPTY: tool=$toolName reason=${capture.reason} ${nodeMapDiag()}")
            }
        } else {
            XLog.d(TAG, "TargetText capture OK: tool=$toolName reason=${capture.reason} text='${capture.text.take(40)}'")
        }
        return capture
    }

    private fun nodeMapDiag(): String {
        val fgPkg = try { LocalAdbDeviceDriver.foregroundPackageName() } catch (_: Exception) { "" }
        return "mapNodes=${LocalAdbDeviceDriver.currentMappedNodes().size} " +
                "mapPkg=${LocalAdbDeviceDriver.nodeMapPackage().ifBlank { "unknown" }} " +
                "fgPkg=${fgPkg.ifBlank { "unknown" }}"
    }

    private fun isNodeMapFromOtherApp(): Boolean {
        val mapPkg = LocalAdbDeviceDriver.nodeMapPackage()
        if (mapPkg.isBlank()) return false
        val fgPkg = try { LocalAdbDeviceDriver.foregroundPackageName() } catch (_: Exception) { "" }
        return fgPkg.isNotBlank() && mapPkg != fgPkg
    }

    /**
     * Coordinate -> text attribution. Trusts the existing nodeIdMap unless it is
     * empty or belongs to another app (verified facts: action tools always trigger
     * a post-action dump, skip tools dump internally, so age is NOT a staleness
     * signal). Anchors on the smallest node containing (x,y), then inherits a
     * label from the smallest labeled descendant, then the nearest labeled
     * ancestor; text preferred over contentDescription at every level.
     */
    private fun lookupTextByCoordinate(params: Map<String, Any>): TargetTextCapture {
        val x = params["x"]?.toString()?.toIntOrNull() ?: return TargetTextCapture("", "BAD_COORDS")
        val y = params["y"]?.toString()?.toIntOrNull() ?: return TargetTextCapture("", "BAD_COORDS")

        var nodes = LocalAdbDeviceDriver.currentMappedNodes()
        val crossApp = isNodeMapFromOtherApp()
        if (nodes.isEmpty() || crossApp) {
            val fresh = LocalAdbDeviceDriver.refreshNodeMap()
            if (fresh.isEmpty()) return TargetTextCapture("", "NO_DUMP")
            XLog.i(TAG, "Coordinate lookup forced dump (${if (nodes.isEmpty()) "EMPTY_MAP" else "CROSS_APP"}): ${nodeMapDiag()}")
            nodes = fresh
        }

        val area = { node: UiNode -> node.bounds.width().toLong() * node.bounds.height().toLong() }
        val container = nodes.filter { it.bounds.contains(x, y) }.minByOrNull(area)
            ?: return TargetTextCapture("", "POINT_IN_GAP")
        container.text.trim().takeIf { it.isNotEmpty() }?.let { return TargetTextCapture(it, "COORD_SELF_TEXT") }
        container.contentDescription.trim().takeIf { it.isNotEmpty() }?.let { return TargetTextCapture(it, "COORD_SELF_DESC") }

        val descendant = nodes
            .filter { it != container && it.label.isNotBlank() && container.bounds.contains(it.bounds) }
            .minByOrNull(area)
        if (descendant != null) return TargetTextCapture(descendant.label.trim(), "COORD_DESCENDANT")

        val ancestor = nodes
            .filter { it != container && it.label.isNotBlank() && it.bounds.contains(x, y) && area(it) > area(container) }
            .minByOrNull(area)
        if (ancestor != null) return TargetTextCapture(ancestor.label.trim(), "COORD_ANCESTOR")

        return TargetTextCapture("", "NODE_NO_TEXT")
    }

    /**
     * Stop recording and hand over the raw session data without persisting.
     * Ownership of the data moves to the caller (in-memory only, per design
     * nothing is staged on disk). Returns null when the session has fewer
     * than MIN_STEPS_TO_RECORD successfully recorded steps.
     */
    @Synchronized
    fun stopWithoutSave(): RecordingSession? {
        if (!isRecording) return null
        isRecording = false

        val params = startParams ?: run {
            XLog.w(TAG, "No start params, discarding recording")
            recordedSteps.clear()
            return null
        }

        if (recordedSteps.size < MIN_STEPS_TO_RECORD) {
            XLog.i(TAG, "Only ${recordedSteps.size} steps (< $MIN_STEPS_TO_RECORD), discarding")
            recordedSteps.clear()
            startParams = null
            return null
        }

        val session = RecordingSession(
            rawUserRequest = params.rawUserRequest,
            steps = recordedSteps.toList()
        )

        recordedSteps.clear()
        startParams = null

        XLog.i(
            TAG,
            "Recording stopped: ${session.steps.size} steps handed over"
        )
        return session
    }

    @Synchronized
    fun discard() {
        if (!isRecording) return
        isRecording = false
        recordedSteps.clear()
        startParams = null
        XLog.d(TAG, "Recording discarded")
    }
}
