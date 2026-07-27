// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.agent

import io.agents.bqaagent.utils.KVUtils

/**
 * Immutable snapshot of VLM (Vision Language Model) configuration.
 * Loaded from KVUtils on demand; never cached across calls.
 */
data class VlmConfig(
    val apiKey: String,
    val baseUrl: String,
    val modelName: String
) {
    /** True when all three fields are non-blank. */
    val isConfigured: Boolean
        get() = apiKey.isNotBlank() && baseUrl.isNotBlank() && modelName.isNotBlank()

    /** Build the full endpoint URL for the OpenAI-compatible chat completions API. */
    val chatCompletionsUrl: String
        get() = "${baseUrl.trimEnd('/')}/chat/completions"
}

/**
 * Single source of truth for VLM configuration.
 *
 * Mirrors the pattern used by [ModelConfigRepository] for LLM config:
 * read/write through [KVUtils] (MMKV-backed SharedPreferences).
 *
 * The VLM config is completely independent from the LLM config —
 * users can have a cloud LLM for reasoning and a separate VLM endpoint
 * (e.g. GPT-4o, Gemini, Qwen-VL) for visual fallback analysis.
 */
object VlmConfigRepository {

    /** Load the current VLM configuration from persistent storage. */
    @JvmStatic
    fun load(): VlmConfig {
        return VlmConfig(
            apiKey = KVUtils.getVlmApiKey(),
            baseUrl = KVUtils.getVlmBaseUrl(),
            modelName = KVUtils.getVlmModelName()
        )
    }

    /** Persist VLM configuration to storage. */
    @JvmStatic
    fun save(apiKey: String, baseUrl: String, modelName: String) {
        KVUtils.setVlmApiKey(apiKey)
        KVUtils.setVlmBaseUrl(baseUrl)
        KVUtils.setVlmModelName(modelName)
    }

    /** Convenience: returns true when a complete VLM config exists. */
    @JvmStatic
    fun isConfigured(): Boolean = KVUtils.isVlmConfigured()
}
