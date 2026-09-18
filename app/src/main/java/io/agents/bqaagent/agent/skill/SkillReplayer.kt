// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.agent.skill

import io.agents.bqaagent.adb.LocalAdbDeviceDriver
import io.agents.bqaagent.tool.ToolRegistry
import io.agents.bqaagent.tool.ToolResult
import io.agents.bqaagent.utils.ScreenSettleWaiter
import io.agents.bqaagent.utils.XLog
import java.util.concurrent.atomic.AtomicBoolean

class SkillReplayer(
    private val skill: SkillDefinition,
    private val template: ExecutionTemplate,
    private val extractedParams: Map<String, String>,
    private val isSessionActive: () -> Boolean
) {

    private val cancelled = AtomicBoolean(false)

    private enum class SubstitutionKind { SELECTION, INPUT }

    private data class Substitution(
        val paramName: String,
        val oldValue: String,
        val newValue: String,
        val kind: SubstitutionKind,
        val inputParamKey: String = ""
    )

    // Built once before replay: locates each changed parameter value inside the
    // recorded steps by value-tracing (old value scan), no LLM involvement.
    private val substitutionsByStep: Map<Int, Substitution> =
        buildSubstitutionPlan(template, extractedParams)
    private val substitutedOldValues: Set<String> =
        substitutionsByStep.values.map { it.oldValue }.toSet()

    fun replay(
        onStepProgress: ((step: Int, total: Int, description: String) -> Unit)? = null,
        onStepResult: ((step: Int, total: Int, name: String, success: Boolean, detail: String, durationMs: Long, params: String) -> Unit)? = null
    ): ReplayResult {
        XLog.i(TAG, "Replaying template: ${template.templateId} (${template.steps.size} steps, skill=${skill.skillId})")

        val totalSteps = template.steps.size
        val startTime = System.currentTimeMillis()
        val estimatedTotalMs = template.steps.sumOf { step ->
            val launchExtra = if (step.toolName in LAUNCH_TOOLS) LAUNCH_STEP_EXTRA_MS else 0L
            step.elapsedMs + STEP_TIMEOUT_BASE_MS + launchExtra
        } + 10000L
        val timeoutMs = estimatedTotalMs * 2

        var stepsCompleted = 0

        // Node map left by the previous step's settle; lets the next resolve skip a dump.
        // Starts false: before the first step the map may still describe this agent app's
        // own UI, which could false-match generic texts like "OK".
        var settledMapUsable = false

        for ((index, step) in template.steps.withIndex()) {
            if (isCancelled()) {
                XLog.i(TAG, "Replay cancelled at step ${index + 1}")
                return ReplayResult(false, stepsCompleted, message = "Replay cancelled", cancelled = true)
            }

            val elapsed = System.currentTimeMillis() - startTime
            if (elapsed > timeoutMs) {
                XLog.w(TAG, "Replay timeout at step ${index + 1}")
                return ReplayResult(false, stepsCompleted, message = "Replay timeout after ${elapsed}ms")
            }

            onStepProgress?.invoke(index + 1, totalSteps, "${step.displayName}...")
            XLog.d(TAG, "Replay step ${index + 1}/$totalSteps: ${step.toolName}")

            val stepStartTime = System.currentTimeMillis()
            val sub = substitutionsByStep[step.stepIndex]

            // Pre-action baseline for settle detection: settle must observe the
            // screen actually changing relative to this before declaring it ready.
            val baseline = ScreenSettleWaiter.captureBaseline()

            val resolved: Pair<String, Map<String, Any>> = when {
                sub != null && sub.kind == SubstitutionKind.SELECTION -> {
                    val resolveDeadline = System.currentTimeMillis() + RESOLVE_RETRY_BUDGET_MS
                    var node = findNodeByText(sub.newValue, settledMapUsable)
                    while (node == null && !isCancelled() && System.currentTimeMillis() < resolveDeadline) {
                        XLog.d(TAG, "Substituted target \"${sub.newValue}\" not on screen yet, polling (${resolveDeadline - System.currentTimeMillis()}ms budget left)")
                        Thread.sleep(RESOLVE_POLL_INTERVAL_MS)
                        node = findNodeByText(sub.newValue, false)
                    }
                    if (node == null) {
                        if (isCancelled()) {
                            XLog.i(TAG, "Replay cancelled while resolving step ${index + 1}")
                            return ReplayResult(false, stepsCompleted, message = "Replay cancelled", cancelled = true)
                        }
                        XLog.w(TAG, "Substituted selection target \"${sub.newValue}\" not found within budget, aborting before execution")
                        SkillGovernor.recordTemplateFailure(template, index, step.toolName)
                        onStepResult?.invoke(index + 1, totalSteps, stepLabel(step), false, "Target \"${sub.newValue}\" not found", System.currentTimeMillis() - stepStartTime, formatParamsForDisplay(step.params))
                        return ReplayResult(
                            success = false,
                            stepsUsed = stepsCompleted,
                            message = "Substituted target \"${sub.newValue}\" not found at step ${index + 1}"
                        )
                    }
                    XLog.i(TAG, "SELECTION substitution at step ${index + 1}: \"${sub.oldValue}\" -> \"${sub.newValue}\" (node=${node.nodeId})")
                    val tapParams = mutableMapOf<String, Any>("x" to node.centerX, "y" to node.centerY)
                    step.params["wait_after"]?.let { tapParams["wait_after"] = it }
                    "tap" to tapParams
                }
                sub != null && sub.kind == SubstitutionKind.INPUT -> {
                    XLog.i(TAG, "INPUT substitution at step ${index + 1}: ${sub.inputParamKey} \"${sub.oldValue}\" -> \"${sub.newValue}\"")
                    resolveWithRetry(step, step.params + (sub.inputParamKey to sub.newValue), settledMapUsable)
                }
                else -> resolveWithRetry(step, step.params, settledMapUsable)
            }

            val result = try {
                ToolRegistry.getInstance().executeTool(resolved.first, resolved.second)
            } catch (e: Exception) {
                XLog.e(TAG, "Step ${index + 1} exception", e)
                ToolResult.error("Replay exception: ${e.message}")
            }

            val stepElapsed = System.currentTimeMillis() - stepStartTime
            XLog.d(TAG, "Step ${index + 1} result: success=${result.isSuccess}, elapsed=${stepElapsed}ms")

            if (!result.isSuccess) {
                XLog.w(TAG, "Step ${index + 1} failed: ${result.error}")
                SkillGovernor.recordTemplateFailure(template, index, step.toolName)
                onStepResult?.invoke(index + 1, totalSteps, stepLabel(step), false, result.error ?: "Tool failed", System.currentTimeMillis() - stepStartTime, formatParamsForDisplay(resolved.second))
                return ReplayResult(
                    success = false,
                    stepsUsed = stepsCompleted,
                    message = "Failed at step ${index + 1} (${step.toolName}): ${result.error}"
                )
            }
            if (isCancelled()) {
                XLog.i(TAG, "Replay cancelled after step ${index + 1} execution")
                return ReplayResult(false, stepsCompleted, message = "Replay cancelled", cancelled = true)
            }

            ScreenSettleWaiter.waitForScreenSettle(
                step.toolName,
                baselineFingerprint = baseline
            ) { isCancelled() }
            if (isCancelled()) {
                XLog.i(TAG, "Replay cancelled during settle/verify of step ${index + 1}")
                return ReplayResult(false, stepsCompleted, message = "Replay cancelled", cancelled = true)
            }
            // Settle left a fresh node map describing this exact screen; the next step's
            // resolve may reuse it without dumping again.
            settledMapUsable = true
            // Verification is the readiness criterion: poll within budget until the
            // recorded expectations (package/activity/anchor texts) hold, instead of
            // failing instantly on a screen that is stable but still loading.
            val verifyDeadline = System.currentTimeMillis() + REPLAY_VERIFY_BUDGET_MS
            var verifyResult = ReplayVerifier.verifyStep(step, substitutedOldValues)
            while (verifyResult is ReplayVerifier.VerifyResult.Fail &&
                !isCancelled() && System.currentTimeMillis() < verifyDeadline
            ) {
                XLog.d(TAG, "Step ${index + 1} not verified yet (${verifyResult.reason}), polling (${verifyDeadline - System.currentTimeMillis()}ms budget left)")
                Thread.sleep(REPLAY_POLL_INTERVAL_MS)
                LocalAdbDeviceDriver.invalidateScreenCache()
                verifyResult = ReplayVerifier.verifyStep(step, substitutedOldValues)
            }
            val stepDuration = System.currentTimeMillis() - stepStartTime
            when (verifyResult) {
                is ReplayVerifier.VerifyResult.Pass -> {
                    XLog.d(TAG, "Step ${index + 1} verification passed")
                    onStepResult?.invoke(index + 1, totalSteps, stepLabel(step), true, "Done", stepDuration, formatParamsForDisplay(resolved.second))
                }
                is ReplayVerifier.VerifyResult.Warning -> {
                    XLog.w(TAG, "Step ${index + 1} verification warning: ${verifyResult.reason}")
                    onStepResult?.invoke(index + 1, totalSteps, stepLabel(step), true, "Warning: ${verifyResult.reason}", stepDuration, formatParamsForDisplay(resolved.second))
                    if (verifyResult.reason.startsWith("System dialog")) {
                        ReplayVerifier.autoDismissSystemDialog()
                        Thread.sleep(500)
                        // The dialog dismissal changed the screen; the settled map is stale.
                        settledMapUsable = false
                    }
                }
                is ReplayVerifier.VerifyResult.Fail -> {
                    XLog.w(TAG, "Step ${index + 1} verification failed: ${verifyResult.reason}")
                    SkillGovernor.recordTemplateFailure(template, index, step.toolName)
                    onStepResult?.invoke(index + 1, totalSteps, stepLabel(step), false, verifyResult.reason, stepDuration, formatParamsForDisplay(resolved.second))
                    return ReplayResult(
                        success = false,
                        stepsUsed = stepsCompleted,
                        message = "Verification failed at step ${index + 1}: ${verifyResult.reason}"
                    )
                }
            }

            stepsCompleted++
            Thread.sleep(STEP_DELAY_MS)
        }

        SkillGovernor.recordTemplateSuccess(template)
        val totalElapsed = System.currentTimeMillis() - startTime
        XLog.i(TAG, "Replay completed: $stepsCompleted steps in ${totalElapsed}ms")
        return ReplayResult(
            success = true,
            stepsUsed = stepsCompleted,
            message = "Replayed ${template.steps.size} steps successfully in ${totalElapsed}ms"
        )
    }

    fun cancel() {
        cancelled.set(true)
    }

    private fun isCancelled(): Boolean = cancelled.get() || !isSessionActive()

    private fun stepLabel(step: RecordedSkillStep): String {
        return step.displayName.ifBlank { step.toolName }
    }

    private fun formatParamsForDisplay(params: Map<String, Any>): String {
        if (params.isEmpty()) return ""
        return params.entries.joinToString(", ") { "${it.key}=${it.value}" }
            .take(PARAMS_DISPLAY_MAX_LENGTH)
    }

    /**
     * Live text lookup for SELECTION substitution: fast path reuses the settled
     * node map, slow path forces a fresh dump. Prefers enabled nodes with exact
     * text, then any enabled node, then any candidate.
     */
    private fun findNodeByText(text: String, canReuseSettledNodes: Boolean): io.agents.bqaagent.adb.UiNode? {
        var candidates = if (canReuseSettledNodes) LocalAdbDeviceDriver.findNodesByText(text, false) else emptyList()
        if (candidates.isEmpty()) {
            candidates = LocalAdbDeviceDriver.findNodesByText(text, true)
        }
        val valid = candidates.filter { LocalAdbDeviceDriver.getNode(it.nodeId) != null }
        if (valid.isEmpty()) return null
        return valid.firstOrNull { it.enabled && it.text == text }
            ?: valid.firstOrNull { it.enabled }
            ?: valid.first()
    }


    /**
     * Resolve the step's recorded node locator to a live nodeId. Returns null
     * when text-based re-resolution failed so the caller can retry while the
     * page is still loading, or fall back to recorded bounds as a last resort.
     */
    private fun resolveNodeAction(step: RecordedSkillStep, params: Map<String, Any>, canReuseSettledNodes: Boolean): Pair<String, Map<String, Any>>? {
        val locator = step.nodeLocator ?: return step.toolName to params
        val key = locator.paramName.ifBlank { "node_id" }
        if (!params.containsKey(key)) return step.toolName to params

        val hasTextFeature = locator.text.isNotBlank() ||
                locator.contentDescription.isNotBlank() ||
                locator.resourceId.isNotBlank()

        if (hasTextFeature) {
            try {
                // Fast path: nothing has touched the screen since the previous settle,
                // so the cached node map already describes it - no dump needed.
                var candidates = if (canReuseSettledNodes) searchCandidates(locator, refreshFirst = false) else emptyList()
                if (candidates.isEmpty()) {
                    candidates = searchCandidates(locator, refreshFirst = true)
                }
                val node = pickBestNode(candidates, locator)
                if (node != null) {
                    XLog.i(TAG, "nodeId re-resolved for param '$key': ${params[key]} -> ${node.nodeId} (text=\"${locator.text}\", candidates=${candidates.size}, cached=$canReuseSettledNodes)")
                    return step.toolName to params + (key to node.nodeId)
                }
                XLog.w(TAG, "nodeId re-resolve failed for param '$key': text=\"${locator.text}\", resourceId=\"${locator.resourceId}\"")
            } catch (e: Exception) {
                XLog.w(TAG, "nodeId re-resolve exception", e)
            }
            return null
        }

        return boundsFallback(step, locator, params)
    }

    /**
     * Retry text-based node resolution within a budget: the target node may not
     * exist yet because the page is still loading. Only after the budget is
     * exhausted does replay degrade to the recorded-bounds fallback.
     */
    private fun resolveWithRetry(
        step: RecordedSkillStep,
        params: Map<String, Any>,
        canReuseSettledNodes: Boolean
    ): Pair<String, Map<String, Any>> {
        val locator = step.nodeLocator
        val key = locator?.paramName?.ifBlank { "node_id" } ?: "node_id"
        val canRetry = locator != null && params.containsKey(key) &&
                (locator.text.isNotBlank() || locator.contentDescription.isNotBlank() || locator.resourceId.isNotBlank())
        if (!canRetry) {
            return resolveNodeAction(step, params, canReuseSettledNodes) ?: boundsFallback(step, locator!!, params)
        }

        val deadline = System.currentTimeMillis() + RESOLVE_RETRY_BUDGET_MS
        var reuseSettled = canReuseSettledNodes
        while (true) {
            resolveNodeAction(step, params, reuseSettled)?.let { return it }
            if (isCancelled() || System.currentTimeMillis() >= deadline) break
            XLog.d(TAG, "Target node \"${locator.text}\" not on screen yet, retrying (${deadline - System.currentTimeMillis()}ms budget left)")
            Thread.sleep(RESOLVE_POLL_INTERVAL_MS)
            reuseSettled = false
        }
        XLog.w(TAG, "Node resolve budget exhausted for \"${locator.text}\", trying bounds fallback")
        return boundsFallback(step, locator, params)
    }

    /**
     * Search candidates by text -> contentDescription -> resourceId, chaining at most
     * one uiautomator dump. When [refreshFirst] is false the current node map is used
     * as-is (fast path); when true the first lookup forces a fresh dump and the later
     * lookups reuse it instead of dumping again.
     */
    private fun searchCandidates(locator: NodeLocator, refreshFirst: Boolean): List<io.agents.bqaagent.adb.UiNode> {
        var dumped = false
        var candidates = emptyList<io.agents.bqaagent.adb.UiNode>()
        if (locator.text.isNotBlank()) {
            candidates = LocalAdbDeviceDriver.findNodesByText(locator.text, refreshFirst)
            dumped = refreshFirst
        }
        if (candidates.isEmpty() && locator.contentDescription.isNotBlank()) {
            candidates = LocalAdbDeviceDriver.findNodesByText(locator.contentDescription, refreshFirst && !dumped)
            dumped = dumped || refreshFirst
        }
        if (candidates.isEmpty() && locator.resourceId.isNotBlank()) {
            candidates = if (dumped) {
                LocalAdbDeviceDriver.currentMappedNodes().filter { it.resourceId == locator.resourceId }
            } else {
                LocalAdbDeviceDriver.findNodesById(locator.resourceId)
            }
        }
        return candidates
    }

    private fun pickBestNode(candidates: List<io.agents.bqaagent.adb.UiNode>, locator: NodeLocator): io.agents.bqaagent.adb.UiNode? {
        val valid = candidates.filter { LocalAdbDeviceDriver.getNode(it.nodeId) != null }
        if (valid.isEmpty()) return null
        if (valid.size == 1) return valid.first()

        valid.firstOrNull {
            it.bounds.left == locator.boundsLeft && it.bounds.top == locator.boundsTop &&
                    it.bounds.right == locator.boundsRight && it.bounds.bottom == locator.boundsBottom
        }?.let {
            XLog.d(TAG, "Disambiguated by exact bounds match among ${valid.size} candidates")
            return it
        }

        val cx = (locator.boundsLeft + locator.boundsRight) / 2
        val cy = (locator.boundsTop + locator.boundsBottom) / 2
        fun distance(node: io.agents.bqaagent.adb.UiNode): Long {
            val dx = node.centerX - cx
            val dy = node.centerY - cy
            return dx.toLong() * dx + dy.toLong() * dy
        }
        val enabledNearest = valid.filter { it.enabled }.minByOrNull { distance(it) }
        val nearest = valid.minByOrNull { distance(it) }
        XLog.d(TAG, "Disambiguated by nearest center among ${valid.size} candidates")
        return enabledNearest ?: nearest
    }

    private fun boundsFallback(step: RecordedSkillStep, locator: NodeLocator, params: Map<String, Any>): Pair<String, Map<String, Any>> {
        if (step.toolName != "tap_node") {
            XLog.w(TAG, "No usable text feature for '${step.toolName}', bounds fallback only supports tap_node, replaying with original params")
            return step.toolName to params
        }
        val width = locator.boundsRight - locator.boundsLeft
        val height = locator.boundsBottom - locator.boundsTop
        if (width <= 0 || height <= 0) {
            XLog.w(TAG, "Invalid recorded bounds (w=$width h=$height), replaying with original params")
            return step.toolName to params
        }
        val x = (locator.boundsLeft + locator.boundsRight) / 2
        val y = (locator.boundsTop + locator.boundsBottom) / 2
        XLog.i(TAG, "Bounds fallback: tap($x, $y) instead of tap_node (original param: ${params[locator.paramName.ifBlank { "node_id" }]})")
        return "tap" to mapOf("x" to x, "y" to y)
    }

    companion object {
        private const val PARAMS_DISPLAY_MAX_LENGTH = 200
        private const val TAG = "SkillReplayer"
        // Settle already guarantees a stable screen; this is only a pacing buffer.
        private const val STEP_DELAY_MS = 300L
        /**
         * Base per-step budget covering: node resolve retry (RESOLVE_RETRY_BUDGET_MS)
         * + tool execution + settle detection (REPLAY_SETTLE_BUDGET_MS)
         * + verification polling (REPLAY_VERIFY_BUDGET_MS) + dumps and step delay.
         */
        private const val STEP_TIMEOUT_BASE_MS = 35_000L
        /** Launch tools additionally run the foreground leave-self gate (up to 6s) */
        private const val LAUNCH_STEP_EXTRA_MS = 8_000L
        /** Budget for polling node resolution / step verification before declaring failure. */
        private const val RESOLVE_RETRY_BUDGET_MS = 8_000L
        private const val REPLAY_VERIFY_BUDGET_MS = 10_000L
        private const val RESOLVE_POLL_INTERVAL_MS = 1_000L
        private const val REPLAY_POLL_INTERVAL_MS = 1_000L
        private val LAUNCH_TOOLS = setOf("open_app", "open_url")
        private val SELECTION_TOOLS = setOf("tap_visible_text", "find_and_tap", "scroll_to_find", "tap_node", "tap", "long_press")

        /**
         * Locates each changed parameter value inside the recorded steps: first the
         * step recorded at save time (KeyValueParam.stepIndex), then value-tracing
         * over every remaining step that anchors the old value. A step is claimed
         * by at most one parameter (first claim wins).
         */
        private fun buildSubstitutionPlan(
            template: ExecutionTemplate,
            extractedParams: Map<String, String>
        ): Map<Int, Substitution> {
            val plan = mutableMapOf<Int, Substitution>()
            for (kv in template.keyValues) {
                val newValue = extractedParams[kv.name]
                if (newValue.isNullOrBlank() || newValue == kv.value) continue
                val oldValue = kv.value
                var substituted = 0

                // Preferred anchor: the step recorded at save time. Legacy templates
                // carry stepIndex = -1 and fall through to value tracing below.
                if (kv.stepIndex >= 0) {
                    val hinted = template.steps.firstOrNull { it.stepIndex == kv.stepIndex }
                    if (hinted != null && !plan.containsKey(hinted.stepIndex)) {
                        val kind = substitutionKindFor(hinted, oldValue)
                        if (kind != null) {
                            plan[hinted.stepIndex] = if (kind == SubstitutionKind.INPUT) {
                                Substitution(kv.name, oldValue, newValue, kind, inputKeyOf(hinted))
                            } else {
                                Substitution(kv.name, oldValue, newValue, kind)
                            }
                            substituted++
                            XLog.i(TAG, "Substitution plan (hint): ${kv.name} '$oldValue'->'$newValue' as $kind at step ${hinted.stepIndex}")
                        }
                    }
                }

                // Value tracing: substitute EVERY remaining step where the old value
                // is anchored, so values echoed across multiple steps stay consistent.
                for (step in template.steps) {
                    if (plan.containsKey(step.stepIndex)) continue
                    val kind = substitutionKindFor(step, oldValue) ?: continue
                    plan[step.stepIndex] = if (kind == SubstitutionKind.INPUT) {
                        Substitution(kv.name, oldValue, newValue, kind, inputKeyOf(step))
                    } else {
                        Substitution(kv.name, oldValue, newValue, kind)
                    }
                    substituted++
                    XLog.i(TAG, "Substitution plan: ${kv.name} '$oldValue'->'$newValue' as $kind at step ${step.stepIndex}")
                }

                if (substituted == 0) {
                    XLog.w(TAG, "Param '${kv.name}' (old='$oldValue') could not be traced in steps, replaying with recorded value")
                }
            }
            return plan
        }

        /**
         * Display twin of the replay plan: stepIndex -> (old value, new value) pairs,
         * anchored by the exact same rules replay uses, so the confirmation preview
         * only marks steps whose recorded values replay will actually rewrite.
         */
        fun buildDisplaySubstitutionPlan(
            template: ExecutionTemplate,
            extractedParams: Map<String, String>
        ): Map<Int, List<Pair<String, String>>> {
            // The plan map is already keyed by stepIndex (one substitution per step,
            // first claim wins), so each entry becomes a single-element pair list.
            return buildSubstitutionPlan(template, extractedParams)
                .mapValues { (_, sub) -> listOf(sub.oldValue to sub.newValue) }
        }

        private fun substitutionKindFor(step: RecordedSkillStep, oldValue: String): SubstitutionKind? {
            val valueKeys = ToolRegistry.getInstance().getTool(step.toolName)?.getValueParamNames() ?: emptyList()
            if (valueKeys.any { step.params[it]?.toString() == oldValue }) return SubstitutionKind.INPUT
            if (step.toolName in SELECTION_TOOLS && step.targetText.isNotEmpty() && step.targetText.contains(oldValue)) {
                return SubstitutionKind.SELECTION
            }
            return null
        }

        private fun inputKeyOf(step: RecordedSkillStep): String {
            val valueKeys = ToolRegistry.getInstance().getTool(step.toolName)?.getValueParamNames() ?: emptyList()
            return valueKeys.firstOrNull { step.params[it]?.toString() != null } ?: ""
        }
    }
}
