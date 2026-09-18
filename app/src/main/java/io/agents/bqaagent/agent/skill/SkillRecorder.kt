// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.agent.skill

import io.agents.bqaagent.adb.LocalAdbDeviceDriver
import io.agents.bqaagent.adb.UiNode
import io.agents.bqaagent.tool.ToolRegistry
import io.agents.bqaagent.utils.XLog

object SkillRecorder {

    private const val TAG = "SkillRecorder"
    private const val MIN_STEPS_TO_RECORD = 3

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
        "bank_own_account_transfer",
        "system_key",
        "swipe",
        "wait",
        "send_message", "send_file", "clipboard",
        "make_call", "search_app_in_store", "auto_reply"
    )

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
        anchorTexts: List<String> = emptyList(),
        nodeLocator: NodeLocator? = null,
        intent: String = "",
        precomputedTargetText: String? = null
    ) {
        if (!isRecording) return
        if (toolName !in RECORDABLE_TOOLS) return

        // NEVER re-capture locator / targetText here: recordStep runs AFTER
        // execution, when Opt-3 has already rebuilt nodeIdMap for the NEW
        // screen. Any lookup at this point would resolve against the wrong
        // screen. Pre-execution captures passed in by the agent loop are the
        // only trusted source; missing data is recorded as-is (empty/null).
        val step = RecordedSkillStep(
            stepIndex = recordedSteps.size,
            toolName = toolName,
            displayName = displayName,
            params = params,
            elapsedMs = elapsedMs,
            expectedPackageAfter = expectedPackageAfter,
            expectedActivityAfter = expectedActivityAfter,
            anchorTexts = anchorTexts,
            nodeLocator = nodeLocator,
            targetText = precomputedTargetText.orEmpty(),
            intent = normalizeStepIntent(intent).takeIf { it.isNotBlank() }
        )
        recordedSteps.add(step)
        XLog.d(TAG, "Recorded step ${step.stepIndex}: $toolName")
    }

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

    private data class TargetTextCapture(val text: String, val reason: String)

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
                    if (fromParam != null) TargetTextCapture(fromParam, "VALUE_PARAM")
                    else if (toolName == "tap" || toolName == "long_press") lookupTextByCoordinate(params)
                    else TargetTextCapture("", "NOT_APPLICABLE")
                }
            }
        } catch (e: Exception) {
            XLog.w(TAG, "captureTargetText failed for $toolName: ${e.message}")
            TargetTextCapture("", "ERROR")
        }
        if (capture.text.isEmpty()) {
            XLog.w(TAG, "TargetText capture EMPTY: tool=$toolName reason=${capture.reason} ${nodeMapDiag()}")
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
