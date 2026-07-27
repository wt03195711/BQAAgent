// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent

/**
 * Typed event emitted by TaskOrchestrator during task execution.
 * Replaces the previous string-based callback protocol that required
 * fragile prefix parsing ("Task completed:", "Task failed", etc.).
 *
 * ComposeChatActivity (or any future UI) pattern-matches on these
 * to update the UI — no string parsing, no ambiguity.
 */
sealed class TaskEvent {

    /** LLM responded with text (chat answer or task summary). */
    data class Response(val text: String, val modelName: String? = null) : TaskEvent()

    /** A tool is being executed (e.g. "Send Message", "Open App"). */
    data class ToolAction(val toolName: String) : TaskEvent()

    /** Tool execution result. */
    data class ToolResult(val toolName: String, val success: Boolean, val detail: String) : TaskEvent()

    /** Agent loop started a new round. */
    data class LoopStart(val round: Int) : TaskEvent()

    /** Skill/workflow step progress. */
    data class Progress(val step: Int, val description: String) : TaskEvent()

    /** Token usage update. */
    data class TokenUpdate(
        val step: Int,
        val formattedTokens: String,
        val formattedCost: String,
        val tokenState: io.agents.bqaagent.agent.TokenMonitor.State,
        val totalTokens: Int = 0,
        val inputTokens: Int = 0,
        val outputTokens: Int = 0,
        val estimatedCostUsd: Double = 0.0
    ) : TaskEvent()

    /** Task completed successfully with an answer/summary. */
    data class Completed(val answer: String, val modelName: String? = null) : TaskEvent()

    /** Task failed with an error. */
    data class Failed(val error: String) : TaskEvent()

    /** Task was cancelled by user. */
    object Cancelled : TaskEvent()

    /** Task blocked by system dialog. */
    object Blocked : TaskEvent()

    /** Screenshot blocked by FLAG_SECURE — requesting user to upload an image. */
    data class ScreenshotBlocked(val intent: String, val timeoutMs: Long) : TaskEvent()

    /** User provided an image (or skipped) in response to ScreenshotBlocked. */
    data class UserImageProvided(val imagePath: String?) : TaskEvent()

    /** Thinking/content stream from LLM (non-streaming mode). */
    data class Thinking(val content: String) : TaskEvent()
}
