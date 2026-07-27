// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.ui.chat

data class ChatMessage(
    val role: Role,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val toolSteps: List<ToolStep>? = null,
    val modelName: String? = null
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
    val isLlmCall: Boolean = false
)
