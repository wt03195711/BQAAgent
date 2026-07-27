// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.agent.llm

import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.anthropic.AnthropicChatModel
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.openai.OpenAiChatModel
import io.agents.bqaagent.agent.CloudProvider
import io.agents.bqaagent.agent.langchain.http.OkHttpClientBuilderAdapter
import io.agents.bqaagent.utils.XLog

/**
 * Single source of truth for LLM client creation.
 *
 * Eliminates duplicate client construction in ComposeChatActivity,
 * AutoReplyManager.generateReplyCloud(), and AutoReplyManager.singleLlmCall().
 *
 * Thread-safe. All methods can be called from any thread.
 */
object LlmSessionManager {

    private const val TAG = "LlmSessionManager"
    private const val DEFAULT_LOCAL_SYSTEM_PROMPT = "You are a helpful assistant. Answer concisely."

    /**
     * Create a Cloud LLM ChatModel using the user's current config.
     * Returns null if no API key is configured.
     */
    fun createCloudChatModel(temperature: Double = 0.7): dev.langchain4j.model.chat.ChatModel? {
        val config = ModelConfigRepository.snapshot()
        if (config.activeMode == ActiveModelMode.LOCAL) {
            XLog.w(TAG, "createCloudChatModel: local mode is active")
            return null
        }

        val cloud = config.activeCloud
        if (cloud.apiKey.isEmpty()) {
            XLog.w(TAG, "createCloudChatModel: no API key configured")
            return null
        }

        XLog.d(TAG, "createCloudChatModel: provider=${cloud.providerName}, model=${cloud.modelName}, baseUrl=${cloud.resolvedBaseUrl}")
        return when (cloud.agentProvider) {
            io.agents.bqaagent.agent.LlmProvider.ANTHROPIC -> AnthropicChatModel.builder()
                .httpClientBuilder(OkHttpClientBuilderAdapter())
                .apiKey(cloud.apiKey)
                .modelName(cloud.modelName)
                .baseUrl(cloud.resolvedBaseUrl)
                .temperature(temperature)
                .build()

            else -> OpenAiChatModel.builder()
                .httpClientBuilder(OkHttpClientBuilderAdapter())
                .apiKey(cloud.apiKey)
                .modelName(cloud.modelName.ifEmpty { "deepseek-v4-flash" })
                .baseUrl(cloud.resolvedBaseUrl.ifEmpty { CloudProvider.DEEPSEEK.defaultBaseUrl })
                .temperature(temperature)
                .build()
        }
    }

    /**
     * Create a Cloud LlmClient using the resolved active config.
     */
    fun createCloudClient(temperature: Double = 0.7): LlmClient? {
        val config = ModelConfigRepository.snapshot()
        if (config.activeMode == ActiveModelMode.LOCAL) return null
        val cloud = config.activeCloud
        if (cloud.apiKey.isEmpty() || cloud.modelName.isEmpty()) {
            XLog.w(TAG, "createCloudClient: incomplete cloud config")
            return null
        }
        return LlmClientFactory.create(
            config.toAgentConfig(
                temperature = temperature,
                maxIterations = 60
            )
        )
    }

    /**
     * Single-shot LLM call — send one prompt, get one response.
     * Uses the user's selected Cloud or Local model.
     * For quick targeted questions (not a full agent loop).
     *
     * @return LLM response text, or null if failed
     */
    fun singleShot(prompt: String, temperature: Double = 0.3): String? {
        return if (ModelConfigRepository.snapshot().activeMode == ActiveModelMode.CLOUD) {
            singleShotCloud(prompt, temperature)
        } else {
            singleShotLocal(prompt, temperature)
        }
    }

    /**
     * Single-shot Cloud LLM call.
     */
    fun singleShotCloud(prompt: String, temperature: Double = 0.7): String? {
        val client = createCloudClient(temperature) ?: return null
        return try {
            val messages = listOf<ChatMessage>(UserMessage.from(prompt))
            client.chat(messages, emptyList()).text
        } catch (e: Exception) {
            XLog.w(TAG, "singleShotCloud failed: ${e.message}")
            null
        } finally {
            client.close()
        }
    }

    /**
     * Single-shot Cloud LLM call with system prompt.
     */
    fun singleShotCloud(systemPrompt: String, userPrompt: String, temperature: Double = 0.7): String? {
        val client = createCloudClient(temperature) ?: return null
        return try {
            val messages = listOf<ChatMessage>(
                SystemMessage.from(systemPrompt),
                UserMessage.from(userPrompt)
            )
            client.chat(messages, emptyList()).text
        } catch (e: Exception) {
            XLog.w(TAG, "singleShotCloud failed: ${e.message}")
            null
        } finally {
            client.close()
        }
    }

    /**
     * Single-shot Local LLM call using LiteRT-LM.
     */
    fun singleShotLocal(prompt: String, temperature: Double = 0.3): String? {
        return singleShotLocal(
            systemPrompt = DEFAULT_LOCAL_SYSTEM_PROMPT,
            prompt = prompt,
            temperature = temperature
        )
    }

    fun singleShotLocal(systemPrompt: String, prompt: String, temperature: Double = 0.3): String? {
        return try {
            val modelPath = ModelConfigRepository.snapshot().local.modelPath
            if (modelPath.isNullOrEmpty()) return null

            val context = io.agents.bqaagent.ClawApplication.instance
            LocalModelRuntime.runSingleShot(
                context = context,
                modelPath = modelPath,
                systemPrompt = systemPrompt,
                prompt = prompt,
                temperature = temperature,
            ).text
        } catch (e: Exception) {
            XLog.w(TAG, "singleShotLocal failed: ${e.message}")
            null
        }
    }

    /**
     * Check if Cloud LLM is configured (has API key).
     */
    fun isCloudConfigured(): Boolean {
        return ModelConfigRepository.snapshot().defaultCloud.isConfigured
    }
}
