// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.tool.impl.mobile;

import io.agents.bqaagent.adb.LocalAdbDeviceDriver;
import io.agents.bqaagent.adb.UiNode;
import io.agents.bqaagent.tool.BaseTool;
import io.agents.bqaagent.tool.ToolParameter;
import io.agents.bqaagent.tool.ToolResult;
import io.agents.bqaagent.utils.XLog;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Skill: find an element by text (scrolling if needed) and tap it — all in one tool call.
 * Saves 3-5 LLM rounds compared to manually calling scroll_to_find + tap.
 */
public class FindAndTapTool extends BaseTool {

    private static final String TAG = "FindAndTapTool";

    @Override
    public String getName() {
        return "find_and_tap";
    }

    @Override
    public List<String> getValueParamNames() {
        return Collections.singletonList("text");
    }

    @Override
    public String getDisplayName() {
        return "Find & Tap";
    }

    @Override
    public String getDescriptionEN() {
        return "Find a UI element by text (scrolling if needed) and tap it. Combines scroll_to_find + tap into one action. Use this instead of manual scroll + tap loops.";
    }

    @Override
    public String getDescriptionCN() {
        return "Find a UI element by text (scrolling if needed) and tap it. Combines scroll_to_find + tap into one action.";
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Arrays.asList(
                new ToolParameter("text", "string", "The text to find and tap", true),
                new ToolParameter("direction", "string", "Scroll direction: 'up' or 'down' (default 'down')", false),
                new ToolParameter("max_scrolls", "integer", "Max scrolls to attempt (default 4, max 8)", false)
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        LocalAdbDeviceDriver driver = requireDeviceDriver();
        if (driver == null) {
            return ToolResult.error("Local ADB is not ready");
        }

        String text = requireString(params, "text");
        String direction = optionalString(params, "direction", "down");
        int maxScrolls = optionalInt(params, "max_scrolls", 4);
        maxScrolls = Math.min(Math.max(maxScrolls, 1), 8);

        String lastScreen = getScreenSnapshot(driver);

        // First try current screen. Reuse the snapshot's node map.
        ToolResult tapResult = findAndTap(driver, text, false);
        if (tapResult != null) return tapResult;

        // Scroll + find + tap
        int[] screenSize = getScreenSize();
        int centerX = screenSize[0] / 2;
        int scrollStartY, scrollEndY;
        if ("up".equals(direction)) {
            scrollStartY = (int) (screenSize[1] * 0.3);
            scrollEndY = (int) (screenSize[1] * 0.7);
        } else {
            scrollStartY = (int) (screenSize[1] * 0.7);
            scrollEndY = (int) (screenSize[1] * 0.3);
        }

        String lastScreenComparable = normalizeForChangeDetection(lastScreen);

        for (int i = 0; i < maxScrolls; i++) {
            driver.performSwipe(centerX, scrollStartY, centerX, scrollEndY, 400);
            try { Thread.sleep(500); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ToolResult.error("Interrupted");
            }

            String currentScreen = getScreenSnapshot(driver);

            tapResult = findAndTap(driver, text, false);
            if (tapResult != null) return tapResult;

            // Detect end of scroll
            String currentScreenComparable = normalizeForChangeDetection(currentScreen);
            if (currentScreenComparable != null && currentScreenComparable.equals(lastScreenComparable)) {
                return ToolResult.error("\"" + text + "\" not found. Reached " +
                        ("up".equals(direction) ? "top" : "bottom") + " after " + (i + 1) + " scrolls.");
            }
            lastScreenComparable = currentScreenComparable;
        }

        return ToolResult.error("\"" + text + "\" not found after " + maxScrolls + " scrolls.");
    }

    private ToolResult findAndTap(LocalAdbDeviceDriver driver, String text, boolean refresh) {
        List<UiNode> nodes = driver.findNodesByText(text, refresh);
        if (nodes.isEmpty()) return null;
        for (UiNode node : nodes) {
            if (node.getEnabled()) {
                int cx = node.getCenterX();
                int cy = node.getCenterY();
                boolean tapped = driver.performTap(cx, cy);
                if (tapped) {
                    XLog.i(TAG, "Found and tapped \"" + text + "\" at (" + cx + "," + cy + ")");
                    return ToolResult.success("Found \"" + text + "\" and tapped at (" + cx + ", " + cy + ")");
                }
            }
        }
        return null;
    }

    private String getScreenSnapshot(LocalAdbDeviceDriver driver) {
        try {
            return driver.getScreenTree();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String normalizeForChangeDetection(String screenContent) {
        if (screenContent == null) return null;
        return screenContent
                .replaceFirst("snapshot=s\\d+", "snapshot=s")
                .trim();
    }
}
