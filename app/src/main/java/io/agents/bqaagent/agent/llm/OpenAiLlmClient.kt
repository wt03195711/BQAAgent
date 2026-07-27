// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.agent.llm

import android.os.Build
import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.chat.request.json.JsonAnyOfSchema
import dev.langchain4j.model.chat.request.json.JsonArraySchema
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema
import dev.langchain4j.model.chat.request.json.JsonEnumSchema
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema
import dev.langchain4j.model.chat.request.json.JsonNumberSchema
import dev.langchain4j.model.chat.request.json.JsonObjectSchema
import dev.langchain4j.model.chat.request.json.JsonRawSchema
import dev.langchain4j.model.chat.request.json.JsonReferenceSchema
import dev.langchain4j.model.chat.request.json.JsonSchemaElement
import dev.langchain4j.model.chat.request.json.JsonStringSchema
import dev.langchain4j.model.output.TokenUsage
import io.agents.bqaagent.ClawApplication
import io.agents.bqaagent.agent.AgentConfig
import io.agents.bqaagent.agent.langchain.http.OkHttpClientBuilderAdapter
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class OpenAiLlmClient(
    private val config: AgentConfig,
    @Suppress("UNUSED_PARAMETER") private val httpClientBuilder: OkHttpClientBuilderAdapter
) : LlmClient {

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }
    private val currentCall = AtomicReference<Call?>()

    override fun chat(messages: List<ChatMessage>, toolSpecs: List<ToolSpecification>): LlmResponse {
        val requestJson = JSONObject()
            .put("model", config.modelName)
            .put("temperature", config.temperature)
            .put("stream", false)
            .put("messages", messages.toOpenAiMessages())

        if (toolSpecs.isNotEmpty()) {
            requestJson.put("tools", toolSpecs.toOpenAiTools())
            requestJson.put("tool_choice", "auto")
        }

        val body = requestJson.toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url("${config.baseUrl.trimEnd('/')}/chat/completions")
            .header("Authorization", "Bearer ${config.apiKey.ifEmpty { "ollama" }}")
            .header("Content-Type", "application/json")
            .withBqaDeviceHeaders()
            .withBqaTraceHeaders()
            .post(body)
            .build()

        val call = httpClient.newCall(request)
        currentCall.set(call)
        try {
            call.execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw RuntimeException("HTTP ${response.code}: ${responseBody.take(500)}")
                }
                return parseChatCompletion(responseBody)
            }
        } finally {
            currentCall.compareAndSet(call, null)
        }
    }

    override fun chatStreaming(
        messages: List<ChatMessage>,
        toolSpecs: List<ToolSpecification>,
        listener: StreamingListener
    ): LlmResponse {
        val response = chat(messages, toolSpecs)
        response.text?.takeIf { it.isNotEmpty() }?.let(listener::onPartialText)
        listener.onComplete(response)
        return response
    }

    override fun close() {
        currentCall.getAndSet(null)?.cancel()
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }

    private fun Request.Builder.withBqaDeviceHeaders(): Request.Builder {
        val app = ClawApplication.instance
        header("X-BQA-Device-Manufacturer", Build.MANUFACTURER.safeHeaderValue())
        header("X-BQA-Device-Brand", Build.BRAND.safeHeaderValue())
        header("X-BQA-Device-Model", Build.MODEL.safeHeaderValue())
        header("X-BQA-Device-Name", Build.DEVICE.safeHeaderValue())
        header("X-BQA-Device-Product", Build.PRODUCT.safeHeaderValue())
        header("X-BQA-Android-Release", Build.VERSION.RELEASE.safeHeaderValue())
        header("X-BQA-Android-SDK", Build.VERSION.SDK_INT.toString())
        header("X-BQA-Package", app.packageName.safeHeaderValue())
        val appVersion = try {
            app.packageManager.getPackageInfo(app.packageName, 0).versionName
        } catch (_: Exception) {
            null
        }
        if (!appVersion.isNullOrBlank()) {
            header("X-BQA-App-Version", appVersion.safeHeaderValue())
        }
        return this
    }

    private fun Request.Builder.withBqaTraceHeaders(): Request.Builder {
        val trace = LlmTraceContext.current() ?: return this
        header("X-BQA-Conversation-Id", trace.conversationId.safeHeaderValue())
        header("X-BQA-Turn-Id", trace.turnId.safeHeaderValue())
        header("X-BQA-Turn-Source", trace.turnSource.safeHeaderValue())
        header("X-BQA-User-Prompt-Hash", trace.userPromptHash.safeHeaderValue())
        return this
    }

    private fun String?.safeHeaderValue(): String {
        return orEmpty()
            .filter { it.code in 32..126 }
            .take(200)
            .ifBlank { "unknown" }
    }

    private fun parseChatCompletion(raw: String): LlmResponse {
        val root = JSONObject(raw)
        val choice = root.optJSONArray("choices")
            ?.optJSONObject(0)
            ?: throw RuntimeException("OpenAI-compatible response has no choices")
        val message = choice.optJSONObject("message")
            ?: throw RuntimeException("OpenAI-compatible response has no message")

        val text = if (message.isNull("content")) "" else message.optString("content")
        val toolCalls = parseToolCalls(message.optJSONArray("tool_calls"))
        val usage = root.optJSONObject("usage")?.let {
            TokenUsage(
                it.optNullableInt("prompt_tokens"),
                it.optNullableInt("completion_tokens"),
                it.optNullableInt("total_tokens")
            )
        }
        val modelName = root.optString("model").takeIf { it.isNotBlank() }

        return LlmResponse(
            text = text,
            toolExecutionRequests = toolCalls,
            tokenUsage = usage,
            modelName = modelName
        )
    }

    private fun parseToolCalls(toolCallsJson: JSONArray?): List<ToolExecutionRequest> {
        if (toolCallsJson == null || toolCallsJson.length() == 0) return emptyList()
        val requests = mutableListOf<ToolExecutionRequest>()
        for (i in 0 until toolCallsJson.length()) {
            val call = toolCallsJson.optJSONObject(i) ?: continue
            val function = call.optJSONObject("function") ?: continue
            val name = function.optString("name").takeIf { it.isNotBlank() } ?: continue
            val arguments = function.optString("arguments").ifBlank { "{}" }
            requests.add(
                ToolExecutionRequest.builder()
                    .id(call.optString("id").ifBlank { "call_$i" })
                    .name(name)
                    .arguments(arguments)
                    .build()
            )
        }
        return requests
    }

    private fun List<ChatMessage>.toOpenAiMessages(): JSONArray {
        val array = JSONArray()
        for (message in this) {
            when (message) {
                is SystemMessage -> array.put(
                    JSONObject()
                        .put("role", "system")
                        .put("content", message.text())
                )

                is UserMessage -> array.put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", message.toPlainText())
                )

                is AiMessage -> array.put(message.toOpenAiMessage())

                is ToolExecutionResultMessage -> array.put(
                    JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", message.id())
                        .put("name", message.toolName())
                        .put("content", message.text())
                )
            }
        }
        return array
    }

    private fun AiMessage.toOpenAiMessage(): JSONObject {
        val json = JSONObject()
            .put("role", "assistant")
            .put("content", text() ?: "")
        val requests = toolExecutionRequests()
        if (!requests.isNullOrEmpty()) {
            val toolCalls = JSONArray()
            requests.forEach { request ->
                toolCalls.put(
                    JSONObject()
                        .put("id", request.id())
                        .put("type", "function")
                        .put(
                            "function",
                            JSONObject()
                                .put("name", request.name())
                                .put("arguments", request.arguments() ?: "{}")
                        )
                )
            }
            json.put("tool_calls", toolCalls)
        }
        return json
    }

    private fun UserMessage.toPlainText(): String {
        return if (hasSingleText()) {
            singleText()
        } else {
            contents().joinToString("\n") { it.toString() }
        }
    }

    private fun List<ToolSpecification>.toOpenAiTools(): JSONArray {
        val tools = JSONArray()
        forEach { spec ->
            tools.put(
                JSONObject()
                    .put("type", "function")
                    .put(
                        "function",
                        JSONObject()
                            .put("name", spec.name())
                            .put("description", spec.description() ?: "")
                            .put("parameters", spec.parameters().toOpenAiSchema())
                    )
            )
        }
        return tools
    }

    private fun JsonObjectSchema?.toOpenAiSchema(): JSONObject {
        val schema = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject())
        if (this == null) return schema

        description()?.takeIf { it.isNotBlank() }?.let { schema.put("description", it) }
        val propertiesJson = JSONObject()
        properties()?.forEach { (name, element) ->
            propertiesJson.put(name, element.toOpenAiSchema())
        }
        schema.put("properties", propertiesJson)
        required()?.takeIf { it.isNotEmpty() }?.let { schema.put("required", JSONArray(it)) }
        additionalProperties()?.let { schema.put("additionalProperties", it) }
        return schema
    }

    private fun JsonSchemaElement?.toOpenAiSchema(): JSONObject {
        val schema = when (this) {
            null -> JSONObject().put("type", "string")
            is JsonObjectSchema -> this.toOpenAiSchema()
            is JsonStringSchema -> JSONObject().put("type", "string")
            is JsonIntegerSchema -> JSONObject().put("type", "integer")
            is JsonNumberSchema -> JSONObject().put("type", "number")
            is JsonBooleanSchema -> JSONObject().put("type", "boolean")
            is JsonArraySchema -> JSONObject()
                .put("type", "array")
                .put("items", items().toOpenAiSchema())
            is JsonEnumSchema -> JSONObject()
                .put("type", "string")
                .put("enum", JSONArray(enumValues()))
            is JsonAnyOfSchema -> JSONObject()
                .put("anyOf", JSONArray(anyOf().map { it.toOpenAiSchema() }))
            is JsonReferenceSchema -> JSONObject()
                .put("\$ref", reference())
            is JsonRawSchema -> JSONObject(schema())
            else -> JSONObject().put("type", "string")
        }
        this?.description()?.takeIf { it.isNotBlank() && !schema.has("description") }?.let {
            schema.put("description", it)
        }
        return schema
    }

    private fun JSONObject.optNullableInt(name: String): Int? {
        return if (has(name) && !isNull(name)) optInt(name) else null
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
