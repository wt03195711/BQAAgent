// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.agent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

import io.agents.bqaagent.utils.XLog;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Sends a screenshot + task intent to a VLM (Vision Language Model) endpoint
 * and returns the model's textual recommendation.
 *
 * <p>The VLM endpoint must be OpenAI-compatible ({@code /chat/completions})
 * and support the {@code image_url} content part with base64 data URIs.</p>
 *
 * <p>This class is intentionally stateless and thread-safe. Each call creates
 * a short-lived OkHttpClient so that timeouts do not bleed across invocations.</p>
 */
public final class VisionAnalyzer {

    private static final String TAG = "VisionAnalyzer";
    private static final MediaType JSON_MEDIA_TYPE =
            MediaType.parse("application/json; charset=utf-8");

    /** Maximum time to wait for the VLM to respond (vision models can be slow). */
    private static final int CONNECT_TIMEOUT_SEC = 30;
    private static final int READ_TIMEOUT_SEC = 120;

    // ==================== VLM System Prompt ====================

    private static final String VLM_SYSTEM_PROMPT =
            "You are a visual screen analyst for Android mobile automation.\n" +
                    "You will receive a screenshot of an Android phone and a task intent.\n" +
                    "Analyze the screenshot and recommend the SINGLE BEST next action.\n\n" +
                    "The screen's resolution is {width}x{height}.\n" +
                    "All positions use normalized coordinates (0 to 1000).\n" +
                    "Make sure to click any buttons, links, icons, etc with the cursor tip in the center of the element. Don't click boxes on their edges unless asked.\n\n" +
                    "### MANDATORY NON-NEGOTIABLE OUTPUT RULES\n" +
                    "1. Your entire response MUST BE ONLY a single pure JSON string with zero extra characters.\n" +
                    "2. ABSOLUTELY FORBIDDEN: ```json, ```, backticks, markdown code blocks, any introductory text, any trailing explanation, comments, line breaks outside JSON structure.\n" +
                    "3. No words, symbols or whitespace outside the outermost pair of { } braces.\n" +
                    "4. Do NOT wrap JSON in any markup fence under any circumstance.\n" +
                    "5. Strictly follow this fixed JSON schema, do not rename or delete any keys:\n" +
                    "{\n" +
                    "  \"screen_summary\": \"brief description of what you see on screen\",\n" +
                    "  \"action\": \"tap|swipe|input_text|system_key|long_press|wait|finish\",\n" +
                    "  \"coordinate\": [500, 680],\n" +
                    "  \"coordinate2\": [0, 0],\n" +
                    "  \"direction\": \"\",\n" +
                    "  \"text\": \"\",\n" +
                    "  \"key\": \"\",\n" +
                    "  \"reason\": \"why this action is the best next step\"\n" +
                    "}\n\n" +
                    "Coordinate System (0 to 1000):\n" +
                    "- coordinate = [x, y]: x is horizontal (0=left edge, 500=center, 1000=right edge), y is vertical (0=top edge, 500=middle, 1000=bottom edge).\n" +
                    "- coordinate2 = [x2, y2]: same range for swipe end point.\n" +
                    "- NEVER output values above 1000 or below 0.\n" +
                    "- Think about WHERE the element is before assigning coordinates:\n" +
                    "  * Left third of screen → x between 0 and 333\n" +
                    "  * Center third of screen → x between 334 and 666\n" +
                    "  * Right third of screen → x between 667 and 1000\n" +
                    "  * Top third of screen → y between 0 and 333\n" +
                    "  * Middle third of screen → y between 334 and 666\n" +
                    "  * Bottom third of screen → y between 667 and 1000\n\n" +
                    "Examples (study the x,y relationship to position):\n" +
                    "- Full-width button at bottom (e.g. 'Login') → [500, 920]\n" +
                    "- Button at bottom-LEFT quarter → [200, 920]\n" +
                    "- Button at bottom-RIGHT quarter → [800, 920]\n" +
                    "- Icon at top-left corner → [100, 80]\n" +
                    "- Icon at top-right corner → [900, 80]\n" +
                    "- Center of screen → [500, 500]\n" +
                    "- Input field in upper-middle area → [500, 350]\n\n" +
                    "IMPORTANT: Android buttons are often full-width and centered. If a button spans most of the screen width, its x coordinate should be near 500 (center). Only use a non-centered x value if the element is clearly on one side.\n\n" +
                    "Action details:\n" +
                    "- \"tap\" / \"long_press\": provide coordinate of the target element center.\n" +
                    "- \"swipe\": provide coordinate of start point AND coordinate2 of end point. \"direction\" is optional (up/down/left/right) for quick swipes.\n" +
                    "- \"input_text\": provide the text in \"text\". If the field has content, mention it in \"reason\".\n" +
                    "- \"system_key\": provide key name in \"key\" (back/home/enter).\n" +
                    "- \"wait\": no coordinates needed, explain what you are waiting for in \"reason\".\n" +
                    "- \"finish\": explain in \"reason\" why the task cannot continue.\n\n" +
                    "General rules:\n" +
                    "- If the screen shows a popup/dialog/ad, address it first before the main task.\n" +
                    "- If the screenshot is blank/black/corrupted, use action \"finish\" with reason \"SCREENSHOT_BLOCKED\".\n" +
                    "- If the task is impossible, use \"finish\" with a clear reason.\n" +
                    "- screen_summary should mention visible text labels, button text, input field placeholders, and page type.\n" +
                    "- ONLY output the bare JSON object, no other content whatsoever.";
    private VisionAnalyzer() {
        // Utility class — no instantiation.
    }

    // ==================== Public API ====================

    /**
     * Analyze a screenshot using the configured VLM.
     *
     * @param config     VLM configuration (API key, base URL, model name)
     * @param imageBase64 base64-encoded image data (no "data:image/..." prefix)
     * @param intent     LLM-provided description of what it needs from the visual analysis
     * @param imageWidth  width of the image sent to VLM (for prompt placeholder)
     * @param imageHeight height of the image sent to VLM (for prompt placeholder)
     * @param mimeType   image MIME type (e.g. "image/jpeg" or "image/png")
     * @return the VLM's response text (expected to be a JSON object), or {@code null} on failure
     */
    @Nullable
    public static String analyze(@NonNull VlmConfig config,
                                 @NonNull String imageBase64,
                                 @NonNull String intent,
                                 int imageWidth,
                                 int imageHeight,
                                 @NonNull String mimeType) {
        if (!config.isConfigured()) {
            XLog.w(TAG, "analyze called but VLM is not configured");
            return null;
        }

        OkHttpClient client = null;
        try {
            client = new OkHttpClient.Builder()
                    .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
                    .readTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
                    .writeTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
                    .build();

            String systemPrompt = VLM_SYSTEM_PROMPT
                    .replace("{width}", String.valueOf(imageWidth))
                    .replace("{height}", String.valueOf(imageHeight));

            JSONObject requestJson = buildRequestJson(config.getModelName(), imageBase64, intent, systemPrompt, mimeType);
            String body = requestJson.toString();

            Request request = new Request.Builder()
                    .url(config.getChatCompletionsUrl())
                    .header("Authorization", "Bearer " + config.getApiKey())
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(body, JSON_MEDIA_TYPE))
                    .build();

            XLog.i(TAG, "VLM request: model=" + config.getModelName()
                    + " intent=" + intent.substring(0, Math.min(intent.length(), 120)));

            try (Response response = client.newCall(request).execute()) {
                String responseBody = response.body() != null
                        ? response.body().string() : "";

                if (!response.isSuccessful()) {
                    XLog.e(TAG, "VLM API error: HTTP " + response.code()
                            + " body=" + responseBody.substring(0, Math.min(responseBody.length(), 500)));
                    return null;
                }

                String content = parseResponseContent(responseBody);
                if (content == null || content.isEmpty()) {
                    XLog.w(TAG, "VLM returned empty content");
                    return null;
                }

                XLog.i(TAG, "VLM response: " + content.substring(0, Math.min(content.length(), 200)));
                return content;
            }
        } catch (Exception e) {
            XLog.e(TAG, "VLM analyze failed", e);
            return null;
        } finally {
            if (client != null) {
                client.dispatcher().executorService().shutdown();
                client.connectionPool().evictAll();
            }
        }
    }

    // ==================== Internal Helpers ====================

    /**
     * Build the OpenAI-compatible request JSON with vision content parts.
     */
    private static JSONObject buildRequestJson(String modelName,
                                               String imageBase64,
                                               String intent,
                                               String systemPrompt,
                                               String mimeType) throws Exception {
        // Text content part
        JSONObject textPart = new JSONObject()
                .put("type", "text")
                .put("text", "Task intent: " + intent);

        // Image content part (base64 data URI)
        JSONObject imagePart = new JSONObject()
                .put("type", "image_url")
                .put("image_url", new JSONObject()
                        .put("url", "data:" + mimeType + ";base64," + imageBase64)
                        .put("detail", "high"));

        // User message content array
        JSONArray contentArray = new JSONArray()
                .put(textPart)
                .put(imagePart);

        // System message
        JSONObject systemMessage = new JSONObject()
                .put("role", "system")
                .put("content", systemPrompt);

        // User message
        JSONObject userMessage = new JSONObject()
                .put("role", "user")
                .put("content", contentArray);

        // Messages array
        JSONArray messages = new JSONArray()
                .put(systemMessage)
                .put(userMessage);

        // Full request
        return new JSONObject()
                .put("model", modelName)
                .put("max_tokens", 800)
                .put("temperature", 0.0)
                .put("messages", messages);
    }

    /**
     * Extract the assistant's text content from the OpenAI-compatible response.
     *
     * @return the content string, or null if parsing fails
     */
    @Nullable
    private static String parseResponseContent(String rawResponse) {
        try {
            JSONObject root = new JSONObject(rawResponse);
            JSONArray choices = root.optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                XLog.w(TAG, "VLM response has no choices");
                return null;
            }
            JSONObject message = choices.getJSONObject(0).optJSONObject("message");
            if (message == null) {
                XLog.w(TAG, "VLM response has no message in first choice");
                return null;
            }
            if (message.isNull("content")) {
                return "";
            }
            return message.getString("content").trim();
        } catch (Exception e) {
            XLog.e(TAG, "Failed to parse VLM response", e);
            return null;
        }
    }
}
