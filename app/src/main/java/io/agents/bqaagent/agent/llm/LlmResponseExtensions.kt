// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.agent.llm

import dev.langchain4j.model.chat.response.ChatResponse

internal fun ChatResponse.toLlmResponse(): LlmResponse {
    val aiMessage = aiMessage()
    return LlmResponse(
        text = aiMessage.text(),
        toolExecutionRequests = aiMessage.toolExecutionRequests() ?: emptyList(),
        tokenUsage = tokenUsage(),
        modelName = modelName(),
        reasoningText = aiMessage.thinking()?.takeIf { it.isNotBlank() }
    )
}
