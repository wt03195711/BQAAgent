// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.tool.impl;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import io.agents.bqaagent.ClawApplication;
import io.agents.bqaagent.agent.VisionAnalyzer;
import io.agents.bqaagent.agent.VlmConfig;
import io.agents.bqaagent.agent.VlmConfigRepository;
import io.agents.bqaagent.tool.BaseTool;
import io.agents.bqaagent.tool.ToolParameter;
import io.agents.bqaagent.tool.ToolResult;
import io.agents.bqaagent.utils.XLog;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * User-assisted visual fallback tool: accepts a user-uploaded image,
 * sends it to VLM for analysis, and returns recommended next action.
 *
 * <p>Triggered ONLY when analyze_screen_visual returns SCREENSHOT_BLOCKED
 * and the system has prompted the user to upload a screenshot.</p>
 */
public class AnalyzeUserImageTool extends BaseTool {

    private static final String TAG = "AnalyzeUserImageTool";
    private static final int VLM_TARGET_WIDTH = 768;
    private static final int VLM_TARGET_HEIGHT = 1680;
    private static final int MIN_DIMENSION = 200;
    private static final long MIN_FILE_SIZE = 10 * 1024;
    private static final long MAX_FILE_SIZE = 20L * 1024 * 1024;
    private static final float MAX_ASPECT_RATIO = 5.0f;
    private static final float MIN_ASPECT_RATIO = 0.2f;

    @Override
    public String getName() {
        return "analyze_user_image";
    }

    @Override
    public String getDisplayName() {
        return "Analyze User Image";
    }

    @Override
    public String getDescriptionEN() {
        return "Analyze a user-provided screenshot when automatic capture is blocked by FLAG_SECURE. "
                + "ONLY use this after analyze_screen_visual returns SCREENSHOT_BLOCKED. "
                + "The system will prompt the user to upload an image automatically — "
                + "you do NOT need to ask the user manually. "
                + "Returns recommended next action with PIXEL coordinates.";
    }

    @Override
    public String getDescriptionCN() {
        return "当自动截图被FLAG_SECURE阻止时，分析用户提供的截图。"
                + "仅在 analyze_screen_visual 返回 SCREENSHOT_BLOCKED 后使用。"
                + "系统会自动提示用户上传图片——你不需要手动询问用户。"
                + "返回带像素坐标的下一步操作建议。";
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Arrays.asList(
                new ToolParameter("image_path", "string",
                        "Absolute path to the user-uploaded image file in app sandbox", true),
                new ToolParameter("intent", "string",
                        "What you are trying to accomplish on this screen", true)
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        String imagePath = requireString(params, "image_path");
        String intent = requireString(params, "intent");

        File imageFile = new File(imagePath);
        if (!imageFile.exists()) {
            return ToolResult.error("IMAGE_NOT_FOUND: No file found at path " + imagePath
                    + ". The image may have been deleted or the path is incorrect.");
        }

        try {
            if (!VlmConfigRepository.isConfigured()) {
                return ToolResult.error("VLM_NOT_CONFIGURED: Vision model is not configured. "
                        + "Go to Settings > Vision Model to configure API Key, Base URL and Model Name.");
            }
            // Security: must be in app's private upload directory
            String canonical = imageFile.getCanonicalPath();
            File uploadDir = ClawApplication.Companion.getInstance().getExternalFilesDir("user_uploads");
            if (uploadDir == null) {
                return ToolResult.error("UPLOAD_DIR_UNAVAILABLE: External storage is not accessible. "
                        + "Cannot verify image path.");
            }
            String allowedPrefix = uploadDir.getCanonicalPath();
            if (!canonical.startsWith(allowedPrefix + File.separator) && !canonical.equals(allowedPrefix)) {
                return ToolResult.error("INVALID_PATH: Image must be in app upload directory.");
            }

            if (imageFile.length() < MIN_FILE_SIZE) {
                return ToolResult.error("IMAGE_TOO_SMALL: File appears to be a thumbnail ("
                        + imageFile.length() + " bytes). "
                        + "Please upload a full-screen screenshot.");
            }

            if (imageFile.length() > MAX_FILE_SIZE) {
                return ToolResult.error("IMAGE_TOO_LARGE: File is too large ("
                        + (imageFile.length() / (1024 * 1024)) + "MB). "
                        + "Maximum allowed is 20MB. Please upload a smaller screenshot.");
            }

            Bitmap bitmap = BitmapFactory.decodeFile(imagePath);
            if (bitmap == null) {
                return ToolResult.error("IMAGE_DECODE_FAILED: Could not decode image file. "
                        + "The file may be corrupted or in an unsupported format.");
            }

            // Dimension pre-check
            if (bitmap.getWidth() < MIN_DIMENSION || bitmap.getHeight() < MIN_DIMENSION) {
                int w = bitmap.getWidth();
                int h = bitmap.getHeight();
                bitmap.recycle();
                return ToolResult.error("IMAGE_TOO_SMALL: Resolution too low for analysis ("
                        + w + "x" + h + "). "
                        + "Minimum required is " + MIN_DIMENSION + "x" + MIN_DIMENSION + ".");
            }

            // Reject extreme aspect ratios that pass dimension check
            // but would be severely distorted after VLM resizing
            float aspectRatio = (float) bitmap.getWidth() / bitmap.getHeight();
            if (aspectRatio > MAX_ASPECT_RATIO || aspectRatio < MIN_ASPECT_RATIO) {
                int w = bitmap.getWidth();
                int h = bitmap.getHeight();
                XLog.w(TAG, "Image rejected: extreme aspect ratio " + aspectRatio
                        + " (" + w + "x" + h + ")");
                bitmap.recycle();
                return ToolResult.error("IMAGE_INVALID_ASPECT: Image aspect ratio is too extreme ("
                        + w + "x" + h + "). "
                        + "Please upload a normal screenshot.");
            }

            // Resize for VLM
            int[] vlmDim = computeVlmDimensions(bitmap.getWidth(), bitmap.getHeight());
            Bitmap resized = Bitmap.createScaledBitmap(bitmap, vlmDim[0], vlmDim[1], true);
            if (resized != bitmap) bitmap.recycle();

            String base64 = encodeBase64(resized);
            resized.recycle();

            if (base64 == null || base64.isEmpty()) {
                return ToolResult.error("IMAGE_ENCODE_FAILED: Failed to encode resized image to base64.");
            }

            // Enriched intent: handle both screenshots and camera photos
            String enrichedIntent = intent
                    + "\n\n[IMAGE SOURCE NOTE] This image was provided by the user as a substitute for an automatic screenshot. "
                    + "It may be a proper screenshot OR a photo taken by another camera/phone pointing at the screen. "
                    + "If it is a photo, account for perspective distortion, bezels/frame around the actual screen, "
                    + "possible finger/hand occlusion, and reflections/glare. "
                    + "Focus your coordinate output on the ACTUAL APP SCREEN CONTENT area only, ignoring bezels and background.\n\n"
                    + "[VALIDATION] First verify this image shows an Android app interface relevant to the task. "
                    + "If it shows a photo, meme, desktop, landscape, or clearly unrelated content, "
                    + "return {\"action\":\"finish\",\"reason\":\"IMAGE_MISMATCH: The uploaded image does not show a relevant Android app screen.\"}";

            VlmConfig config = VlmConfigRepository.load();
            String vlmResponse = VisionAnalyzer.analyze(config, base64, enrichedIntent, vlmDim[0], vlmDim[1], "image/jpeg");

            if (vlmResponse == null || vlmResponse.isEmpty()) {
                return ToolResult.error("VLM_API_ERROR: Vision model returned empty response. "
                        + "Check VLM configuration or try again.");
            }

            String converted = convertToPixelCoordinates(vlmResponse);
            return ToolResult.success(converted);

        } catch (Exception e) {
            XLog.e(TAG, "analyze_user_image failed", e);
            return ToolResult.error("PROCESSING_FAILED: " + e.getMessage());
        } finally {
            if (imageFile.exists()) {
                XLog.d(TAG, "Cleaning up uploaded image: " + imageFile.getAbsolutePath()
                        + " (" + imageFile.length() + " bytes)");
                imageFile.delete();
            }
        }
    }

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

    private String encodeBase64(Bitmap bitmap) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out);
            return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            XLog.e(TAG, "encodeBase64 failed", e);
            return null;
        }
    }

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
}