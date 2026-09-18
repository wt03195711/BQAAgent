// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.agent.llm

import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.model.output.TokenUsage

data class LlmResponse(
    val text: String?,
    val toolExecutionRequests: List<ToolExecutionRequest>,
    val tokenUsage: TokenUsage? = null,
    /** The actual model name returned by the API (e.g. "deepseek-v4-flash"). */
    val modelName: String? = null,
    /** Reasoning content from reasoning models (OpenAI-compatible "reasoning_content" or langchain4j thinking). */
    val reasoningText: String? = null
) {
    fun hasToolExecutionRequests(): Boolean = toolExecutionRequests.isNotEmpty()
}
