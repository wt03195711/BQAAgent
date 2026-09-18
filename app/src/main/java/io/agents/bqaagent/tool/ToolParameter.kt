// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.tool

data class ToolParameter @JvmOverloads constructor(
    val name: String,
    val type: String,
    val description: String,
    val isRequired: Boolean,
    /**
     * Optional enumeration of allowed string values. When non-empty, LangChain4jToolBridge emits a
     * JSON-schema `enum` so the LLM is constrained to these values (e.g. finish.status).
     */
    val enumValues: List<String>? = null
)
