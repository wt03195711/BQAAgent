// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent

/**
 * The four user-facing terminal outcomes of a task.
 *
 * The judgement authority is encoded in the value itself:
 * - [SUCCESS] / [FAILED] are decided by the LLM through finish(status=...). SUCCESS is additionally
 *   subject to a lightweight system evidence check: a success claim with zero successful device
 *   actions on a non-chat task is downgraded to FAILED / UNVERIFIED.
 * - [STOPPED] is decided by system code: token/iteration limits, stuck detector, system dialog,
 *   unusable screen, sensitive policy, image-analysis termination.
 * - [CANCELLED] is decided by the user.
 */
enum class TaskStatus {
    SUCCESS,
    FAILED,
    STOPPED,
    CANCELLED
}

/**
 * Backend reason codes carried alongside [TaskStatus] for code logic and diagnostics. Not shown to
 * the user verbatim.
 *
 * The FAILED family is intentionally open-ended: the LLM returns a free-form reason string which is
 * bucketed into one of these when possible and falls back to [OTHER] otherwise, so failure causes
 * never have to be exhaustively pre-enumerated.
 */
object TaskReasonCode {
    // --- SUCCESS ---
    const val TASK_SUCCESS = "TASK_SUCCESS"
    const val CHAT_ONLY = "CHAT_ONLY"

    // --- FAILED (LLM-judged; the free-form reason text is preserved separately) ---
    const val NEEDS_MORE_INFO = "NEEDS_MORE_INFO"
    const val IMPOSSIBLE = "IMPOSSIBLE"
    const val TARGET_NOT_FOUND = "TARGET_NOT_FOUND"
    const val DESCRIPTION_MISMATCH = "DESCRIPTION_MISMATCH"
    const val UNVERIFIED = "UNVERIFIED"
    const val EMPTY_RESPONSE = "EMPTY_RESPONSE"
    const val OTHER = "OTHER"

    // --- STOPPED (system-decided) ---
    const val TOKEN_LIMIT = "TOKEN_LIMIT"
    const val MAX_ITERATIONS = "MAX_ITERATIONS"
    const val STUCK = "STUCK"
    const val SYSTEM_DIALOG = "SYSTEM_DIALOG"
    const val UNUSABLE_SCREEN = "UNUSABLE_SCREEN"
    const val SENSITIVE_POLICY = "SENSITIVE_POLICY"
    const val IMAGE_ANALYSIS = "IMAGE_ANALYSIS"
    const val LLM_ERROR = "LLM_ERROR"

    // --- CANCELLED ---
    const val USER_CANCEL = "USER_CANCEL"
}

/**
 * LLM-facing values for the finish tool's `status` parameter. Deliberately small and robust: the
 * fine-grained "why" is carried by finish's free-form `reason` argument, not by more enum values.
 *
 * Mapping to [TaskStatus]:
 * - [SUCCESS]    -> TaskStatus.SUCCESS (reasonCode TASK_SUCCESS, pending the evidence check)
 * - [FAILED]     -> TaskStatus.FAILED  (reasonCode derived from the reason text, else OTHER)
 * - [NOT_A_TASK] -> TaskStatus.SUCCESS (reasonCode CHAT_ONLY, rendered silently as plain chat)
 */
object FinishStatus {
    const val SUCCESS = "success"
    const val FAILED = "failed"
    const val NOT_A_TASK = "not_a_task"

    @JvmField
    val VALUES: List<String> = listOf(SUCCESS, FAILED, NOT_A_TASK)
}