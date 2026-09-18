// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.ui.chat

import io.agents.bqaagent.TaskStatus

data class ChatMessage(
    val role: Role,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val toolSteps: List<ToolStep>? = null,
    val modelName: String? = null,
    val groupTitle: String? = null,
    val recordingId: String? = null,
    /**
     * Terminal outcome of the task this message concludes. Null on non-terminal messages and on
     * pure-chat replies (which render as a plain bubble with no status line/icon). Drives the
     * status header at the top of the assistant bubble.
     */
    val taskStatus: TaskStatus? = null
) {
    enum class Role { USER, ASSISTANT, SYSTEM, TOOL_GROUP }
}

data class ToolStep(
    val toolName: String,
    val summary: String,
    val success: Boolean = false,
    val startedAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val durationMs: Long? = null,
    val tokenCount: Int? = null,
    val tokenText: String? = null,
    val costText: String? = null,
    val isLlmCall: Boolean = false,
    val intent: String? = null,
    val params: String? = null
)
