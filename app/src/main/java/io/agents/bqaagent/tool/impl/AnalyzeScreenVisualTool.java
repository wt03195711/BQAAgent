// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.tool.impl;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import io.agents.bqaagent.adb.LocalAdbDeviceDriver;
import io.agents.bqaagent.agent.VisionAnalyzer;
import io.agents.bqaagent.agent.VlmConfig;
import io.agents.bqaagent.agent.VlmConfigRepository;
import io.agents.bqaagent.tool.BaseTool;
import io.agents.bqaagent.tool.ToolParameter;
import io.agents.bqaagent.tool.ToolResult;
import io.agents.bqaagent.utils.XLog;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Visual fallback tool: takes a screenshot, sends it to a VLM for analysis,
 * and returns the model's recommended next action as structured JSON text.
 *
 * <p>Usage: LLM calls this tool when ADB UI dump returns SCREEN_TREE_UNUSABLE
 * and it needs visual guidance to proceed. The LLM provides an "intent" parameter
 * describing what it is trying to accomplish.</p>
 *
 * <p>Three error scenarios are handled:
 * <ul>
 *   <li>VLM not configured → returns VLM_NOT_CONFIGURED error</li>
 *   <li>Screenshot command fails → returns SCREENSHOT_FAILED error</li>
 *   <li>Screenshot is blank/black (FLAG_SECURE) → returns SCREENSHOT_BLOCKED error</li>
 * </ul>
 * </p>
 */
public class AnalyzeScreenVisualTool extends BaseTool {

    private static final String TAG = "AnalyzeScreenVisualTool";

    /**
     * Pixel sampling grid size for blank screenshot detection.
     * We sample a 10x10 grid across the image for efficiency.
     */
    private static final int SAMPLE_GRID = 10;

    /**
     * If more than this ratio of sampled pixels are near-black,
     * the screenshot is considered blank (FLAG_SECURE blocked).
     */
    private static final double BLANK_THRESHOLD = 0.95;

    /** RGB channel upper bound for a "dark" pixel. */
    private static final int DARK_PIXEL_THRESHOLD = 20;

    /** RGB channel lower bound for a "bright" pixel (white blank detection). */
    private static final int BRIGHT_PIXEL_THRESHOLD = 235;

    /** Maximum RGB channel range for a pixel to be considered "uniform" in monochrome detection. */
    private static final int MONOCHROME_RANGE = 15;

    /** Target width for VLM input, within the training distribution of most VLM models. */
    private static final int VLM_TARGET_WIDTH = 768;

    /** Target height for VLM input, within the training distribution of most VLM models. */
    private static final int VLM_TARGET_HEIGHT = 1680;

    @Override
    public String getName() {
        return "analyze_screen_visual";
    }

    @Override
    public String getDisplayName() {
        return "Analyze Screen (Visual)";
    }

    @Override
    public String getDescriptionEN() {
        return "Take a screenshot of the current screen and analyze it using a vision model. "
                + "Returns a recommended next action with PIXEL coordinates.\n\n"
                + "WHEN TO USE (this is a FALLBACK, not a first resort):\n"
                + "1. get_screen_info returns SCREEN_TREE_UNUSABLE (the ADB dump is genuinely blank/hidden) -> call this tool.\n"
                + "2. The ADB tree IS usable but genuinely lacks an element you have strong reason to believe is on screen (e.g. an image-only icon or a custom-drawn/WebView control) -> call this tool BEFORE guessing.\n"
                + "3. If the tree is usable and already shows plausible elements, reason and act on them first — do NOT call this tool just because the screen looks sparse.\n\n"
                + "The \"intent\" parameter must describe:\n"
                + "1. Your current task goal\n"
                + "2. What you just tried (last action and result)\n"
                + "3. What you need from the visual analysis\n\n"
                + "The returned x, y are PIXEL coordinates ready for tap(x, y). No conversion needed.\n"
                + "After using this tool's recommendation and executing the action, "
                + "call get_screen_info again — ADB data may be available on the new screen.";
    }

    @Override
    public String getDescriptionCN() {
        return "截取当前屏幕并使用视觉模型进行分析，返回建议的下一步操作及像素坐标。\n\n"
                + "使用时机（这是兜底手段，不是首选）：\n"
                + "1. get_screen_info 返回 SCREEN_TREE_UNUSABLE（ADB dump 确实为空/被隐藏）→ 调用此工具。\n"
                + "2. ADB 树可用，但确实缺少你有充分理由相信存在于屏幕上的元素（如纯图片图标、自绘/WebView 控件）→ 在猜测前先调用此工具。\n"
                + "3. 如果 ADB 树可用且已包含合理元素，请先基于它们推理并行动——不要因为界面看起来元素少就调用此工具。\n\n"
                + "\"intent\" 参数必须描述：\n"
                + "1. 当前任务目标\n"
                + "2. 上一步尝试的操作及结果\n"
                + "3. 需要从视觉分析中获取的信息\n\n"
                + "返回的 x, y 为像素坐标，可直接用于 tap(x, y)，无需转换。\n"
                + "执行完建议操作后，再次调用 get_screen_info — 新页面可能恢复 ADB 数据。";
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Collections.singletonList(
                new ToolParameter(
                        "intent",
                        "string",
                        "Describe what you are trying to accomplish and what you need from the visual analysis. "
                                + "Include: task goal, last action taken, and what information you need.",
                        true
                )
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        // 1. Check VLM configuration
        if (!VlmConfigRepository.isConfigured()) {
            return ToolResult.error(
                    "VLM_NOT_CONFIGURED: Vision model is not configured. "
                            + "Go to Settings > Vision Model to configure API Key, Base URL and Model Name."
            );
        }

        // 2. Get ADB device driver
        LocalAdbDeviceDriver driver = requireDeviceDriver();
        if (driver == null) {
            return ToolResult.error("SCREENSHOT_FAILED: Local ADB is not ready.");
        }

        // 3. Take screenshot
        String intent = requireString(params, "intent");
        File screenshot = driver.takeScreenshotFile();
        if (screenshot == null) {
            return ToolResult.error(
                    "SCREENSHOT_FAILED: Screenshot command failed. "
                            + "The device may not support screenshots."
            );
        }

        try {
            // 4. Decode screenshot once, detect blank, then resize and encode
            Bitmap bitmap = BitmapFactory.decodeFile(screenshot.getAbsolutePath());
            if (bitmap == null) {
                return ToolResult.error(
                        "SCREENSHOT_FAILED: Failed to decode screenshot."
                );
            }

            if (isBlankBitmap(bitmap)) {
                bitmap.recycle();
                return ToolResult.error(
                        "SCREENSHOT_BLOCKED: The current screen blocks screenshots "
                                + "(Android FLAG_SECURE). The screenshot is blank/black. "
                                + "Please inform the user that this screen cannot be captured "
                                + "by visual analysis, and ask them to decide the next step."
                );
            }

            // 5. Resize to VLM-friendly resolution to avoid silent compression by VLM API
            int[] vlmDim = computeVlmDimensions(bitmap.getWidth(), bitmap.getHeight());
            Bitmap resized = Bitmap.createScaledBitmap(bitmap, vlmDim[0], vlmDim[1], true);
            if (resized != bitmap) bitmap.recycle();

            String base64 = encodeBase64(resized);
            resized.recycle();

            if (base64 == null || base64.isEmpty()) {
                return ToolResult.error(
                        "SCREENSHOT_FAILED: Failed to encode screenshot to base64."
                );
            }

            // 6. Call VLM with resized dimensions so prompt matches what model actually sees
            VlmConfig config = VlmConfigRepository.load();
            String vlmResponse = VisionAnalyzer.analyze(config, base64, intent, vlmDim[0], vlmDim[1], "image/jpeg");
            if (vlmResponse == null || vlmResponse.isEmpty()) {
                return ToolResult.error(
                        "VLM_API_ERROR: Vision model returned empty response. "
                                + "Check VLM configuration or try again."
                );
            }

            // 7. Convert normalized coordinates (0-1000) to pixel coordinates
            String convertedResponse = convertToPixelCoordinates(vlmResponse);

            // 8. Return converted result to LLM
            return ToolResult.success(convertedResponse);
        } catch (Exception e) {
            XLog.e(TAG, "analyze_screen_visual failed", e);
            return ToolResult.error("PROCESSING_FAILED: " + e.getMessage());
        } finally {
            if (screenshot.exists()) {
                screenshot.delete();
            }
        }
    }

    /**
     * Compute VLM-friendly dimensions preserving the source aspect ratio,
     * fitting within VLM_TARGET_WIDTH x VLM_TARGET_HEIGHT.
     */
    private int[] computeVlmDimensions(int srcWidth, int srcHeight) {
        float aspectRatio = (float) srcWidth / srcHeight;
        float targetAspect = (float) VLM_TARGET_WIDTH / VLM_TARGET_HEIGHT;
        int targetW, targetH;
        if (aspectRatio > targetAspect) {
            targetW = VLM_TARGET_WIDTH;
            targetH = Math.round(VLM_TARGET_WIDTH / aspectRatio);
        } else {
            targetH = VLM_TARGET_HEIGHT;
            targetW = Math.round(VLM_TARGET_HEIGHT * aspectRatio);
        }
        return new int[]{targetW, targetH};
    }

    /**
     * Encode a Bitmap to base64 JPEG string.
     */
    private String encodeBase64(Bitmap bitmap) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out);
            byte[] bytes = out.toByteArray();
            return Base64.encodeToString(bytes, Base64.NO_WRAP);
        } catch (Exception e) {
            XLog.e(TAG, "encodeBase64 failed", e);
            return null;
        }
    }

    // ==================== Coordinate Conversion ====================

    /**
     * Convert VLM's normalized coordinates (0-1000) to actual pixel coordinates.
     * Uses screen dimensions for conversion since the VLM outputs normalized values
     * relative to the full screen.
     * Expects format: "coordinate": [x, y] and "coordinate2": [x2, y2].
     */
    private String convertToPixelCoordinates(String vlmResponse) {
        try {
            String cleaned = stripMarkdownFence(vlmResponse);
            JSONObject json = new JSONObject(cleaned);
            String action = json.optString("action", "");

            if ("tap".equals(action) || "long_press".equals(action)) {
                convertCoordArray(json, "coordinate");
            } else if ("swipe".equals(action)) {
                convertCoordArray(json, "coordinate");
                convertCoordArray(json, "coordinate2");
            }

            return json.toString();
        } catch (Exception e) {
            XLog.w(TAG, "convertToPixelCoordinates: parse failed, returning raw", e);
            return vlmResponse;
        }
    }

    private void convertCoordArray(JSONObject json, String key) throws Exception {
        if (!json.has(key)) return;

        JSONArray coord = json.getJSONArray(key);
        if (coord.length() < 2) return;

        double rawX = coord.getDouble(0);
        double rawY = coord.getDouble(1);

        if (rawX < 0 || rawX > 1000 || rawY < 0 || rawY > 1000) {
            XLog.w(TAG, "convertCoordArray: VLM returned out-of-range coordinates "
                    + "key=" + key + " raw=(" + rawX + "," + rawY + ")");
        }

        int[] screenSize = getScreenSize();
        int pixelX = (int) Math.round(rawX / 1000.0 * screenSize[0]);
        int pixelY = (int) Math.round(rawY / 1000.0 * screenSize[1]);

        pixelX = Math.max(0, Math.min(pixelX, screenSize[0] - 1));
        pixelY = Math.max(0, Math.min(pixelY, screenSize[1] - 1));

        XLog.d(TAG, "convertCoordArray: key=" + key
                + " raw=(" + rawX + "," + rawY + ")"
                + " screen=" + screenSize[0] + "x" + screenSize[1]
                + " pixel=" + pixelX + "," + pixelY);

        JSONArray pixelCoord = new JSONArray();
        pixelCoord.put(pixelX);
        pixelCoord.put(pixelY);
        json.put(key, pixelCoord);
    }

    /**
     * Strip markdown code fence (e.g. ```json ... ```) from VLM response if present.
     */
    private String stripMarkdownFence(String text) {
        if (text == null) return "";
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline > 0) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
            if (trimmed.endsWith("```")) {
                trimmed = trimmed.substring(0, trimmed.length() - 3);
            }
            return trimmed.trim();
        }
        return trimmed;
    }

    // ==================== Blank Screenshot Detection ====================

    /**
     * Detect whether a screenshot is blank/black, which indicates that
     * the app uses Android FLAG_SECURE to prevent screenshots.
     *
     * <p>Uses grid sampling (10x10 = 100 points) for efficiency.
     * If more than 95% of sampled pixels are near-black (RGB < 20),
     * the image is considered blank.</p>
     *
     * @param bitmap the already-decoded screenshot bitmap
     * @return true if the screenshot appears to be blank/blocked
     */
    private boolean isBlankBitmap(Bitmap bitmap) {
        try {
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            int stepX = Math.max(1, width / SAMPLE_GRID);
            int stepY = Math.max(1, height / SAMPLE_GRID);

            int darkPixels = 0;
            int brightPixels = 0;
            int totalSamples = 0;

            int minR = 255, maxR = 0;
            int minG = 255, maxG = 0;
            int minB = 255, maxB = 0;

            for (int x = 0; x < width; x += stepX) {
                for (int y = 0; y < height; y += stepY) {
                    int pixel = bitmap.getPixel(x, y);
                    int r = Color.red(pixel);
                    int g = Color.green(pixel);
                    int b = Color.blue(pixel);

                    if (r < DARK_PIXEL_THRESHOLD
                            && g < DARK_PIXEL_THRESHOLD
                            && b < DARK_PIXEL_THRESHOLD) {
                        darkPixels++;
                    }
                    if (r > BRIGHT_PIXEL_THRESHOLD
                            && g > BRIGHT_PIXEL_THRESHOLD
                            && b > BRIGHT_PIXEL_THRESHOLD) {
                        brightPixels++;
                    }

                    if (r < minR) minR = r;
                    if (r > maxR) maxR = r;
                    if (g < minG) minG = g;
                    if (g > maxG) maxG = g;
                    if (b < minB) minB = b;
                    if (b > maxB) maxB = b;

                    totalSamples++;
                }
            }

            double darkRatio = (double) darkPixels / totalSamples;
            double brightRatio = (double) brightPixels / totalSamples;
            boolean isDarkBlank = darkRatio > BLANK_THRESHOLD;
            boolean isBrightBlank = brightRatio > BLANK_THRESHOLD;
            boolean isMonochrome = (maxR - minR) < MONOCHROME_RANGE
                    && (maxG - minG) < MONOCHROME_RANGE
                    && (maxB - minB) < MONOCHROME_RANGE;

            boolean isBlank = isDarkBlank || isBrightBlank || isMonochrome;
            if (isBlank) {
                XLog.w(TAG, "isBlankBitmap: detected blank screenshot "
                        + "(dark=" + String.format("%.2f", darkRatio)
                        + ", bright=" + String.format("%.2f", brightRatio)
                        + ", monochrome=" + isMonochrome
                        + ", samples=" + totalSamples + ")");
            }
            return isBlank;

        } catch (Exception e) {
            XLog.e(TAG, "isBlankBitmap: exception during detection", e);
            return true;
        }
    }
}
