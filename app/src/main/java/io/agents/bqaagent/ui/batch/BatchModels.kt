// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, 2.0.

package io.agents.bqaagent.ui.batch

import io.agents.bqaagent.TaskStatus

/**
 * Pure data models for the batch-execution feature.
 *
 * A batch session reuses the chat markdown conversation model: one batch = one conversation,
 * one task = a USER message (the instruction) followed by Tools/Assistant messages
 * (steps + terminal result). These types describe the batch-specific metadata layered on top
 * of that shared structure; they carry no logic.
 */

/** Top-level screen identity persisted via KVUtils.getLastUiMode(). */
object BatchUiMode {
    const val CHAT = "chat"
    const val BATCH = "batch"
}

/**
 * Run-time-only context for a single batch task dispatch. NEVER persisted: every (re)run rebuilds it
 * from the live [BatchConfig], so a task always executes under the switches as they are NOW — not as
 * they were when it was first queued. A batch task carries no add-time skill state; the skill (if any)
 * is resolved just-in-time at dispatch by TaskOrchestrator. The non-null presence of this object is also
 * what tells TaskOrchestrator "this is a batch task" (vs a chat task), enabling the JIT re-validation path.
 */
data class BatchRunContext(
    /** Switch C: prefer replaying a matched skill's template over an AI run. */
    val preferReplay: Boolean,
    /**
     * Switch B: auto-save/update skills on AI-run success. Used by the JIT only to WORD its transcript
     * note; the real save gate lives in BatchTaskDriver (which reads the same flag from BatchConfig).
     */
    val autoSaveSkill: Boolean,
)

/**
 * Batch-level configuration stored in the conversation frontmatter. Recording is intentionally
 * absent — it always follows the global system switch (KVUtils.isTaskRecordingEnabled()).
 */
data class BatchConfig(
    /** Auto save/update skills from AgentLoop tasks. Only meaningful when SkillCaptureMode is on. */
    val autoSaveSkill: Boolean = false,
    // Switch C (batch-level): prefer replay when a matching skill is found at JIT time.
    // Only meaningful when SkillCaptureMode (switch A) is on.
    val preferReplay: Boolean = false,
    /** Strict reset (force-stop touched apps + home) between tasks. */
    val resetBetweenTasks: Boolean = true,
    /** Idle gap after a task settles (and resets) before the next one starts. */
    val taskIntervalSeconds: Int = 3,
    /** false = keep going after a failure (default); true = halt the whole batch. */
    val stopOnFailure: Boolean = false,
)

/**
 * Batch-layer lifecycle phase. Layered on top of the system [TaskStatus]: a task that has settled
 * also carries a [TaskStatus]; the batch-only phases (pending / skipped / hang) have none.
 */
enum class BatchTaskPhase {
    /** Queued, not started yet (a USER message with no terminal ASSISTANT message). */
    PENDING,

    /** Currently executing. */
    RUNNING,

    /** Finished with a system terminal state (SUCCESS / FAILED / STOPPED / CANCELLED). */
    SETTLED,

    /** Skipped because ADB dropped mid-batch. */
    SKIPPED_ADB,

    /** Killed by the heartbeat watchdog after 12 min with no events. */
    HANG_TIMEOUT,

    /** Watchdog fired but the session never released; batch halted, scene preserved. */
    ABANDONED,
}

/** One task row in the batch detail list, derived from a conversation's messages. */
data class BatchTaskView(
    val seq: Int,
    val taskText: String,
    val phase: BatchTaskPhase,
    val taskStatus: TaskStatus? = null,
    val reasonCode: String? = null,
    val recordingId: String? = null,
    val startedAt: Long? = null,
    val endedAt: Long? = null,
    val durationMs: Long? = null,
    val rounds: Int? = null,
    val totalTokens: Int? = null,
    val resultSummary: String? = null,
)

fun BatchTaskView?.isCompleted(): Boolean =
    this != null && phase == BatchTaskPhase.SETTLED &&
            (taskStatus == TaskStatus.SUCCESS || taskStatus == TaskStatus.FAILED)

/** Batch session header in the batch list, derived from conversation frontmatter + messages. */
data class BatchSessionView(
    val id: String,
    val title: String,
    val created: Long,
    val updated: Long,
    val taskCount: Int,
    val lastRunAt: Long?,
    /** Count of settled tasks per system [TaskStatus] — drives the "✅7 ⚠️1 🟠1" summary. */
    val statusCounts: Map<TaskStatus, Int>,
    val pendingCount: Int,
    val config: BatchConfig,
)

/**
 * Runtime-only prompt describing a pending user-image upload during a BATCH task — the batch analogue
 * of chat's showUserImageUpload flag. Produced by BatchTaskDriver from TaskEvent.ScreenshotBlocked and
 * cleared on TaskEvent.UserImageProvided (upload / skip / timeout) or any terminal event. NEVER persisted.
 */
data class BatchUserImagePrompt(
    /** 1-based index of the blocked task, for the "current task" line. */
    val taskSeq: Int,
    /** The task instruction text, so the user knows exactly which task is waiting. */
    val taskText: String,
    /** The VLM intent string; recorded into the task transcript, not shown verbatim in the dialog. */
    val intent: String,
    /** Epoch millis when the wait expires (receivedAt + timeoutMs); drives the countdown. */
    val deadlineMs: Long,
)

/**
 * Runtime-only prompt describing a pending sensitive-action confirmation during a BATCH task —
 * the batch analogue of chat's sensitiveConfirmPrompt. Produced by BatchTaskDriver from
 * TaskEvent.SensitiveConfirmRequest and cleared on TaskEvent.SensitiveConfirmResult, approve/deny
 * or any terminal event. NEVER persisted.
 */
data class BatchSensitiveConfirmPrompt(
    /** 1-based index of the task awaiting confirmation. */
    val taskSeq: Int,
    /** The task instruction text, so the user knows exactly which task is asking. */
    val taskText: String,
    /** Human-readable summary of the risky action. */
    val actionSummary: String,
    /** "payment" | "password" | other free-form label. */
    val riskType: String,
    /** Epoch millis when the confirmation expires (receivedAt + timeoutMs); drives the countdown. */
    val deadlineMs: Long,
)
