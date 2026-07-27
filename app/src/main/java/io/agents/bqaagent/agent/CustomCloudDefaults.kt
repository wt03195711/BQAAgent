// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.agent

import android.content.Context
import io.agents.bqaagent.ClawApplication
import io.agents.bqaagent.utils.XLog
import java.io.File
import org.json.JSONObject

data class CustomCloudConfig(
    val apiKey: String,
    val baseUrl: String,
    val modelName: String
)

object CustomCloudDefaults {
    private const val TAG = "CustomCloudDefaults"
    const val FILE_NAME = "custom_llm_config.json"
    const val FALLBACK_API_KEY = "root1234"
    const val FALLBACK_MODEL_NAME = "Qwen3.5-9B-MLX-4bit"
    const val FALLBACK_BASE_URL = "http://10.0.2.2:22001/v1"

    private val fallback = CustomCloudConfig(
        apiKey = FALLBACK_API_KEY,
        baseUrl = FALLBACK_BASE_URL,
        modelName = FALLBACK_MODEL_NAME
    )

    fun current(context: Context = ClawApplication.instance): CustomCloudConfig {
        return readRuntimeFile(context)
            ?: readAssetFile(context)
            ?: fallback
    }

    fun apiKey(context: Context = ClawApplication.instance): String = current(context).apiKey

    fun baseUrl(context: Context = ClawApplication.instance): String = current(context).baseUrl

    fun modelName(context: Context = ClawApplication.instance): String = current(context).modelName

    fun save(
        apiKey: String,
        baseUrl: String,
        modelName: String,
        context: Context = ClawApplication.instance
    ): Boolean {
        return try {
            val file = runtimeFile(context)
            file.parentFile?.mkdirs()
            val payload = JSONObject()
                .put("api_key", apiKey.trim())
                .put("base_url", baseUrl.trim())
                .put("model_name", modelName.trim())
                .toString(2)
            file.writeText(payload, Charsets.UTF_8)
            true
        } catch (t: Throwable) {
            XLog.w(TAG, "Failed to save ${FILE_NAME}", t)
            false
        }
    }

    private fun readRuntimeFile(context: Context): CustomCloudConfig? {
        val file = runtimeFile(context)
        if (!file.exists() || file.length() <= 0L) return null
        return try {
            parse(file.readText(Charsets.UTF_8), source = file.absolutePath)
        } catch (t: Throwable) {
            XLog.w(TAG, "Failed to read runtime ${FILE_NAME}", t)
            null
        }
    }

    private fun readAssetFile(context: Context): CustomCloudConfig? {
        return try {
            context.assets.open(FILE_NAME).use { input ->
                parse(input.bufferedReader(Charsets.UTF_8).readText(), source = "assets/$FILE_NAME")
            }
        } catch (_: java.io.FileNotFoundException) {
            null
        } catch (t: Throwable) {
            XLog.w(TAG, "Failed to read asset ${FILE_NAME}", t)
            null
        }
    }

    private fun parse(raw: String, source: String): CustomCloudConfig? {
        if (raw.isBlank()) return null
        return try {
            val json = JSONObject(raw)
            CustomCloudConfig(
                apiKey = json.firstNonBlank("api_key", "apiKey", "llmApiKey")
                    .ifBlank { fallback.apiKey },
                baseUrl = json.firstNonBlank("base_url", "baseUrl", "llmBaseUrl")
                    .ifBlank { fallback.baseUrl },
                modelName = json.firstNonBlank("model_name", "modelName", "llmModelName")
                    .ifBlank { fallback.modelName }
            )
        } catch (t: Throwable) {
            XLog.w(TAG, "Invalid custom LLM config: $source", t)
            null
        }
    }

    private fun JSONObject.firstNonBlank(vararg keys: String): String {
        for (key in keys) {
            val value = optString(key, "").trim()
            if (value.isNotBlank()) return value
        }
        return ""
    }

    private fun runtimeFile(context: Context): File = File(context.filesDir, FILE_NAME)
}
