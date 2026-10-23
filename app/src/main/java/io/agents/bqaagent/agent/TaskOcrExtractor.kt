// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.agent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import io.agents.bqaagent.R
import io.agents.bqaagent.utils.XLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Extracts task descriptions from images using the configured VLM.
 *
 * Reuses the same OpenAI-compatible /chat/completions pattern as [VisionAnalyzer],
 * but with a dedicated OCR system prompt that produces plain-text task descriptions
 * instead of the structured JSON action format used for screen analysis.
 */
sealed class OcrExtractionError(message: String) : RuntimeException(message) {
    abstract val userMessageRes: Int
    object BlurryImage : OcrExtractionError("Image is blurry") {
        override val userMessageRes = R.string.ocr_error_blurry
    }
    object NoValidTask : OcrExtractionError("No valid task found") {
        override val userMessageRes = R.string.ocr_error_no_task
    }
    object Technical : OcrExtractionError("Technical error") {
        override val userMessageRes = R.string.ocr_image_error
    }
}


object TaskOcrExtractor {

    private const val TAG = "TaskOcrExtractor"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    private const val CONNECT_TIMEOUT_SEC = 30
    private const val READ_TIMEOUT_SEC = 120

    private const val VLM_TARGET_WIDTH = 1280
    private const val VLM_TARGET_HEIGHT = 720

    private const val MARKER_BLURRY = "__BLURRY_IMAGE__"
    private const val MARKER_NO_TASK = "__NO_VALID_TASK__"

    private const val OCR_SYSTEM_PROMPT = """
You are a test-case text extractor. You will receive a photo or screenshot taken from a PC-based test management system.

Your task:
- Extract ONLY the test case / task description content from the image.
- Preserve the ORIGINAL text format exactly as written, especially numbered steps (e.g. "1. xxx 2. xxx 3. xxx"). Do NOT merge, rewrite, summarize, or rephrase the steps.
- Keep all specific values (names, numbers, amounts, URLs, account info) exactly as shown.
- Output in the SAME language as the source text.

Filter out irrelevant content:
- Ignore UI element descriptions (button labels, menu items, navigation bars, breadcrumbs).
- Ignore browser chrome text (address bar, tab titles, bookmarks, status bar).
- Ignore system UI text (taskbar, clock, notification area, window controls).
- Ignore page headers, footers, sidebar navigation, and other layout elements.
- Only extract text that is clearly part of the test case description or test steps.

If the image is blurry, too dark, or text is unreadable:
- Output exactly: $MARKER_BLURRY
If the image does NOT contain a recognizable test case or task description:
- Output exactly: $MARKER_NO_TASK
Do NOT guess, explain, or provide any alternative content in either case above.

Output rules:
- Output ONLY the extracted task text. No JSON, no markdown fences, no commentary, no preamble.
"""

    suspend fun extractTaskFromImage(imagePath: String, config: VlmConfig): Result<String> = withContext(Dispatchers.IO) {
        if (!config.isConfigured) {
            XLog.w(TAG, "extractTaskFromImage: VLM is not configured")
            return@withContext Result.failure(OcrExtractionError.Technical)
        }

        val bitmap = BitmapFactory.decodeFile(imagePath) ?: run {
            XLog.e(TAG, "extractTaskFromImage: failed to decode $imagePath")
            return@withContext Result.failure(OcrExtractionError.Technical)
        }

        val (vlmW, vlmH) = computeVlmDimensions(bitmap.width, bitmap.height)
        val resized = Bitmap.createScaledBitmap(bitmap, vlmW, vlmH, true)
        if (resized !== bitmap) bitmap.recycle()

        val base64 = encodeBase64(resized)
        resized.recycle()
        if (base64 == null) {
            XLog.e(TAG, "extractTaskFromImage: base64 encoding failed")
            return@withContext Result.failure(OcrExtractionError.Technical)
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SEC.toLong(), TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SEC.toLong(), TimeUnit.SECONDS)
            .writeTimeout(CONNECT_TIMEOUT_SEC.toLong(), TimeUnit.SECONDS)
            .build()

        try {
            val requestJson = buildRequestJson(config.modelName, base64, OCR_SYSTEM_PROMPT)
            val request = Request.Builder()
                .url(config.chatCompletionsUrl)
                .header("Authorization", "Bearer ${config.apiKey}")
                .header("Content-Type", "application/json")
                .post(requestJson.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            XLog.i(TAG, "OCR VLM request: model=${config.modelName}")

            val response = client.newCall(request).execute()
            response.use { resp ->
                val body = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    XLog.e(TAG, "OCR VLM API error: HTTP ${resp.code} body=${body.take(500)}")
                    return@withContext Result.failure(OcrExtractionError.Technical)
                }
                val content = parseResponseContent(body)
                if (content.isNullOrBlank()) {
                    XLog.w(TAG, "OCR VLM returned empty content")
                    return@withContext Result.failure(OcrExtractionError.Technical)
                }
                val cleaned = stripMarkdownFence(content.trim())
                if (cleaned.contains(MARKER_BLURRY)) {
                    XLog.w(TAG, "OCR VLM: image is blurry/unreadable")
                    return@withContext Result.failure(OcrExtractionError.BlurryImage)
                }
                if (cleaned.contains(MARKER_NO_TASK)) {
                    XLog.w(TAG, "OCR VLM: no valid task content found in image")
                    return@withContext Result.failure(OcrExtractionError.NoValidTask)
                }
                XLog.i(TAG, "OCR VLM response: ${cleaned.take(200)}")
                return@withContext Result.success(cleaned)
            }
        } catch (e: Exception) {
            XLog.e(TAG, "OCR VLM extraction failed", e)
            return@withContext Result.failure(OcrExtractionError.Technical)
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    private fun buildRequestJson(modelName: String, imageBase64: String, systemPrompt: String): JSONObject {
        val textPart = JSONObject()
            .put("type", "text")
            .put("text", "Please extract the task description from this image.")

        val imagePart = JSONObject()
            .put("type", "image_url")
            .put("image_url", JSONObject()
                .put("url", "data:image/jpeg;base64,$imageBase64")
                .put("detail", "high"))

        val contentArray = JSONArray().put(textPart).put(imagePart)

        val systemMessage = JSONObject().put("role", "system").put("content", systemPrompt)
        val userMessage = JSONObject().put("role", "user").put("content", contentArray)
        val messages = JSONArray().put(systemMessage).put(userMessage)

        return JSONObject()
            .put("model", modelName)
            .put("max_tokens", 1024)
            .put("temperature", 0.1)
            .put("messages", messages)
    }

    private fun parseResponseContent(rawResponse: String): String? {
        return try {
            val root = JSONObject(rawResponse)
            val choices = root.optJSONArray("choices")
            if (choices == null || choices.length() == 0) {
                XLog.w(TAG, "OCR VLM response has no choices")
                return null
            }
            val message = choices.getJSONObject(0).optJSONObject("message")
            if (message == null) {
                XLog.w(TAG, "OCR VLM response has no message")
                return null
            }
            if (message.isNull("content")) return ""
            message.getString("content").trim()
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to parse OCR VLM response", e)
            null
        }
    }

    private fun computeVlmDimensions(srcWidth: Int, srcHeight: Int): Pair<Int, Int> {
        val aspectRatio = srcWidth.toFloat() / srcHeight.toFloat()
        val targetAspect = VLM_TARGET_WIDTH.toFloat() / VLM_TARGET_HEIGHT.toFloat()
        return if (aspectRatio > targetAspect) {
            VLM_TARGET_WIDTH to Math.round(VLM_TARGET_WIDTH / aspectRatio)
        } else {
            Math.round(VLM_TARGET_HEIGHT * aspectRatio) to VLM_TARGET_HEIGHT
        }
    }

    private fun encodeBase64(bitmap: Bitmap): String? {
        return try {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            XLog.e(TAG, "encodeBase64 failed", e)
            null
        }
    }

    // Strip ```code fences that some VLMs may wrap around plain text
    private fun stripMarkdownFence(text: String): String {
        val fence = "```"
        if (text.startsWith(fence)) {
            val afterOpen = text.substringAfter("\n").ifEmpty { text.substring(fence.length) }
            val closing = afterOpen.lastIndexOf(fence)
            if (closing > 0) return afterOpen.substring(0, closing).trim()
        }
        return text
    }
}