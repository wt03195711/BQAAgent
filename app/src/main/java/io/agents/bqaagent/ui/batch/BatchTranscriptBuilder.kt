// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.ui.batch

import io.agents.bqaagent.TaskEvent
import io.agents.bqaagent.TaskReasonCode
import io.agents.bqaagent.TaskStatus
import io.agents.bqaagent.recording.TaskRecordingCoordinator
import io.agents.bqaagent.ui.chat.ChatMessage
import io.agents.bqaagent.ui.chat.ToolStep
import java.util.Locale

/**
 * Headless transcript accumulator for ONE batch task.
 *
 * Mirrors TaskFlowController's event->message logic (step groups, replay group, terminal bubble) but
 * operates on a plain list with no Compose state, so the batch driver can build the exact same
 * markdown a chat task would produce. One instance == one task: call [beginUser], feed every
 * [TaskEvent] through [onEvent], then call [appendTerminal] once when the task settles.
 *
 * The two step groups are kept separate exactly like chat: replay steps land in a TOOL_GROUP titled
 * "Replay steps" (activeReplayGroupIndex), agent-loop tool/LLM steps land in an untitled TOOL_GROUP
 * (activeToolGroupIndex). A replay that degrades to AgentLoop therefore yields both groups.
 */
class BatchTranscriptBuilder {

    /** Resolved terminal outcome handed back to the driver for its own bookkeeping. */
    data class BatchTerminal(
        val status: TaskStatus?,
        val reasonCode: String?,
        val recordingId: String?,
        val text: String,
    )

    private val messages = mutableListOf<ChatMessage>()
    private var activeToolGroupIndex: Int? = null
    private var activeReplayGroupIndex: Int? = null
    private val loopStartedAt = mutableMapOf<Int, Long>()
    private var lastTotalTokens = 0
    private var lastEstimatedCostUsd = 0.0

    /** Snapshot of this task's transcript so far (safe to persist mid-task for a live view). */
    val transcript: List<ChatMessage> get() = messages.toList()

    fun beginUser(text: String) {
        activeToolGroupIndex = null
        activeReplayGroupIndex = null
        loopStartedAt.clear()
        lastTotalTokens = 0
        lastEstimatedCostUsd = 0.0
        messages.add(ChatMessage(ChatMessage.Role.USER, text))
    }

    fun onEvent(event: TaskEvent) {
        when (event) {
            is TaskEvent.ToolAction -> {
                if (!event.toolName.contains("Finish", ignoreCase = true)) {
                    addRunningToolStep(event.toolName, System.currentTimeMillis(), event.params)
                }
            }
            is TaskEvent.ToolResult -> {
                if (!event.toolName.contains("Finish", ignoreCase = true)) {
                    completeToolStep(event.toolName, event.success, event.detail, System.currentTimeMillis())
                }
            }
            is TaskEvent.TokenUpdate -> addLlmStep(event, System.currentTimeMillis())
            is TaskEvent.LoopStart -> loopStartedAt[event.round] = System.currentTimeMillis()
            is TaskEvent.ReplayStart -> {
                activeReplayGroupIndex = null
                addSystem("▶️ Replaying skill \"${event.skillTitle}\" (${event.totalSteps} steps)")
                ensureReplayGroupIndex()
            }
            is TaskEvent.ReplayStep -> addReplayStepResult(event)
            is TaskEvent.Progress -> addSystem(event.description)
            is TaskEvent.ScreenshotBlocked -> {
                addSystem("⚠️ Unable to capture the screen automatically — asked the user to upload a screenshot to continue.")
                if (event.intent.isNotBlank()) {
                    addSystem("🎯 AI intent: ${event.intent}")
                }
            }
            is TaskEvent.UserImageProvided ->
                addSystem(if (event.imagePath != null) "📷 Screenshot received, analyzing..." else "⏭️ Skipped screenshot upload — AI continues without it.")

            is TaskEvent.SensitiveConfirmRequest -> {
                addSystem("🔒 Sensitive action detected — user confirmation required.")
                addSystem("🎯 ${event.actionSummary}")
            }
            is TaskEvent.SensitiveConfirmResult ->
                addSystem(if (event.approved) "✅ Sensitive action confirmed by user, continuing..." else "❌ Sensitive action rejected or timed out — task failed.")

            // Batch never reaches these: ReplayConfirmRequest is bypassed (batch replays without a prompt),
            // Response/Thinking are intermediate, and the four terminal events go through appendTerminal.
            is TaskEvent.ReplayConfirmRequest, is TaskEvent.Response, is TaskEvent.Thinking,
            is TaskEvent.Completed, is TaskEvent.Failed, is TaskEvent.Stopped, is TaskEvent.Cancelled -> Unit
        }
    }

    /**
     * Append the terminal ASSISTANT bubble and return the resolved outcome. Status/reasonCode/
     * recordingId rules mostly mirror TaskFlowController.handleTaskEvent, with ONE deliberate batch
     * divergence on CHAT_ONLY:
     *  - CHAT_ONLY completion -> in CHAT this is a silent success with NO status marker (plain bubble),
     *    but a BATCH row must always carry a terminal status so it shows a real result, persists, and
     *    re-derives as SETTLED/SUCCESS on reload instead of collapsing to NONE. A chat-only task did
     *    complete successfully (it just needed no device action), so it is marked SUCCESS. It has no
     *    footage, so recordingId stays null and its LLM-only step group is dropped, exactly like chat;
     *  - CANCELLED -> no recordingId (the footage is discarded on RecordingOutcome.CANCELLED);
     *  - FAILED/STOPPED -> keep recordingId, strip the redundant "Task stopped" lead-in.
     */
    fun appendTerminal(event: TaskEvent): BatchTerminal {
        return when (event) {
            is TaskEvent.Completed -> {
                val isChatOnly = event.reasonCode == TaskReasonCode.CHAT_ONLY
                // Batch always resolves a completion to SUCCESS (see KDoc): unlike chat, a batch row
                // without a status is re-derived as NONE on reload. Chat-only just has no recording.
                val recordingId = if (isChatOnly) null else currentRecordingId()
                if (isChatOnly) removeCurrentToolGroup()
                addAssistant(event.answer, TaskStatus.SUCCESS, event.reasonCode, recordingId)
                BatchTerminal(TaskStatus.SUCCESS, event.reasonCode, recordingId, event.answer)
            }
            is TaskEvent.Failed -> {
                val recordingId = currentRecordingId()
                addAssistant(event.error, TaskStatus.FAILED, event.reasonCode, recordingId)
                BatchTerminal(TaskStatus.FAILED, event.reasonCode, recordingId, event.error)
            }
            is TaskEvent.Stopped -> {
                val stopText = event.message
                    .removePrefix("Task stopped:")
                    .removePrefix("Task stopped")
                    .trim()
                    .ifEmpty { event.message }
                val recordingId = currentRecordingId()
                addAssistant(stopText, TaskStatus.STOPPED, event.reasonCode, recordingId)
                BatchTerminal(TaskStatus.STOPPED, event.reasonCode, recordingId, stopText)
            }
            is TaskEvent.Cancelled -> {
                addAssistant("Task cancelled by user.", TaskStatus.CANCELLED, TaskReasonCode.USER_CANCEL, null)
                BatchTerminal(TaskStatus.CANCELLED, TaskReasonCode.USER_CANCEL, null, "Task cancelled by user.")
            }
            else -> {
                // Not a terminal event; callers only invoke this on settle, so this is a no-op guard.
                BatchTerminal(null, null, null, "")
            }
        }
    }

    // ==================== Message helpers (mirror TaskFlowController) ====================

    private fun addAssistant(text: String, status: TaskStatus?, reasonCode: String?, recordingId: String?) {
        messages.add(
            ChatMessage(
                ChatMessage.Role.ASSISTANT,
                text,
                recordingId = recordingId,
                taskStatus = status,
                reasonCode = reasonCode,
            )
        )
    }

    private fun addSystem(text: String) {
        messages.add(ChatMessage(ChatMessage.Role.SYSTEM, text))
    }

    private fun currentRecordingId(): String? = TaskRecordingCoordinator.currentTaskRecordingId()

    private fun removeCurrentToolGroup() {
        val index = activeToolGroupIndex
        if (index != null && index in messages.indices && messages[index].role == ChatMessage.Role.TOOL_GROUP) {
            messages.removeAt(index)
        }
        activeToolGroupIndex = null
    }

    private fun addRunningToolStep(toolName: String, startedAt: Long, params: String = "") {
        updateToolGroup { steps ->
            steps + ToolStep(
                toolName = toolName,
                summary = "Running",
                success = false,
                startedAt = startedAt,
                params = params.takeIf { it.isNotBlank() },
            )
        }
    }

    private fun completeToolStep(toolName: String, success: Boolean, detail: String, completedAt: Long) {
        updateToolGroup { steps ->
            val index = steps.indexOfLast { !it.isLlmCall && it.toolName == toolName && it.completedAt == null }
            val summary = if (success) "Done" else detail.ifBlank { "Failed" }.take(120)
            if (index >= 0) {
                steps.mapIndexed { stepIndex, step ->
                    if (stepIndex == index) {
                        step.copy(
                            summary = summary,
                            success = success,
                            completedAt = completedAt,
                            durationMs = (completedAt - step.startedAt).coerceAtLeast(0L),
                        )
                    } else step
                }
            } else {
                steps + ToolStep(
                    toolName = toolName,
                    summary = summary,
                    success = success,
                    startedAt = completedAt,
                    completedAt = completedAt,
                    durationMs = 0L,
                )
            }
        }
    }

    private fun addLlmStep(event: TaskEvent.TokenUpdate, completedAt: Long) {
        val startedAt = loopStartedAt[event.step] ?: completedAt
        val deltaTokens = if (event.totalTokens > 0) (event.totalTokens - lastTotalTokens).coerceAtLeast(0) else 0
        val deltaCost = if (event.estimatedCostUsd > 0.0) (event.estimatedCostUsd - lastEstimatedCostUsd).coerceAtLeast(0.0) else 0.0
        if (event.totalTokens > 0) lastTotalTokens = event.totalTokens
        if (event.estimatedCostUsd > 0.0) lastEstimatedCostUsd = event.estimatedCostUsd
        val intentText = event.intent.trim()
        updateToolGroup { steps ->
            steps + ToolStep(
                toolName = "LLM Call",
                summary = "Reasoning step ${event.step}",
                success = true,
                startedAt = startedAt,
                completedAt = completedAt,
                durationMs = (completedAt - startedAt).coerceAtLeast(0L),
                tokenCount = deltaTokens.takeIf { it > 0 },
                tokenText = when {
                    deltaTokens > 0 -> formatTokenCount(deltaTokens)
                    event.formattedTokens.isNotBlank() -> event.formattedTokens
                    else -> null
                },
                costText = deltaCost.takeIf { it > 0.0 }?.let { formatCost(it) },
                isLlmCall = true,
                intent = intentText.takeIf { it.isNotBlank() },
            )
        }
    }

    private fun addReplayStepResult(event: TaskEvent.ReplayStep) {
        val now = System.currentTimeMillis()
        updateReplayGroup { steps ->
            steps + ToolStep(
                toolName = "${event.step}/${event.total} ${event.name}",
                summary = event.detail.ifBlank { if (event.success) "Done" else "Failed" }.take(120),
                success = event.success,
                startedAt = (now - event.durationMs).coerceAtLeast(0L),
                completedAt = now,
                durationMs = event.durationMs,
                params = event.params.takeIf { it.isNotBlank() },
            )
        }
    }

    private fun updateToolGroup(transform: (List<ToolStep>) -> List<ToolStep>) {
        val index = ensureToolGroupIndex()
        val existing = messages[index]
        val updatedSteps = transform(existing.toolSteps.orEmpty())
        messages[index] = existing.copy(content = buildToolGroupContent(updatedSteps), toolSteps = updatedSteps)
    }

    private fun updateReplayGroup(transform: (List<ToolStep>) -> List<ToolStep>) {
        val index = ensureReplayGroupIndex()
        val existing = messages[index]
        val updatedSteps = transform(existing.toolSteps.orEmpty())
        messages[index] = existing.copy(content = buildToolGroupContent(updatedSteps), toolSteps = updatedSteps)
    }

    private fun ensureToolGroupIndex(): Int {
        activeToolGroupIndex?.let { index ->
            if (index in messages.indices && messages[index].role == ChatMessage.Role.TOOL_GROUP) return index
        }
        val insertIndex = messages.size
        messages.add(ChatMessage(role = ChatMessage.Role.TOOL_GROUP, content = "", toolSteps = emptyList()))
        activeToolGroupIndex = insertIndex
        return insertIndex
    }

    private fun ensureReplayGroupIndex(): Int {
        activeReplayGroupIndex?.let { index ->
            if (index in messages.indices && messages[index].role == ChatMessage.Role.TOOL_GROUP) return index
        }
        val insertIndex = messages.size
        messages.add(
            ChatMessage(
                role = ChatMessage.Role.TOOL_GROUP,
                content = "",
                toolSteps = emptyList(),
                groupTitle = "Replay steps",
            )
        )
        activeReplayGroupIndex = insertIndex
        return insertIndex
    }

    private fun buildToolGroupContent(steps: List<ToolStep>): String {
        return steps.joinToString("\n") { step ->
            val status = if (step.success) "✓" else if (step.completedAt == null) "…" else "✕"
            val duration = step.durationMs?.toString().orEmpty()
            val completed = step.completedAt?.toString().orEmpty()
            val tokens = step.tokenCount?.toString().orEmpty()
            val tokenText = step.tokenText.orEmpty()
            val costText = step.costText.orEmpty()
            val safeSummary = step.summary.replace("\n", " ").replace("|", "·")
            val safeIntent = step.intent.orEmpty().replace("\n", " ").replace("|", "·")
            val safeParams = step.params.orEmpty().replace("\n", " ").replace("|", "·")
            "- $status ${step.toolName} | started=${step.startedAt} | completed=$completed | durationMs=$duration | tokens=$tokens | tokenText=$tokenText | cost=$costText | llm=${step.isLlmCall} | params=$safeParams | intent=$safeIntent | $safeSummary"
        }
    }

    private fun formatTokenCount(tokens: Int): String = when {
        tokens < 1000 -> tokens.toString()
        tokens < 1_000_000 -> String.format(Locale.US, "%.1fK", tokens / 1000.0)
        else -> String.format(Locale.US, "%.1fM", tokens / 1_000_000.0)
    }

    private fun formatCost(cost: Double): String =
        if (cost < 0.01) String.format(Locale.US, "$%.4f", cost) else String.format(Locale.US, "$%.2f", cost)
}