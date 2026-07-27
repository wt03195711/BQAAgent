// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.tool.impl.mobile;

import android.graphics.Rect;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import io.agents.bqaagent.ClawApplication;
import io.agents.bqaagent.R;
import io.agents.bqaagent.adb.LocalAdbDeviceDriver;
import io.agents.bqaagent.adb.UiNode;
import io.agents.bqaagent.tool.BaseTool;
import io.agents.bqaagent.tool.ToolParameter;
import io.agents.bqaagent.tool.ToolResult;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Scroll-to-find tool: automatically scrolls the current page and finds elements containing the specified text.
 * Returns the element's coordinate info when found, avoiding the LLM loop of repeatedly calling get_screen_info + swipe.
 */
public class ScrollToFindTool extends BaseTool {

    @Override
    public String getName() {
        return "scroll_to_find";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_scroll_to_find);
    }

    @Override
    public String getDescriptionEN() {
        return "Scroll the screen to find an element containing the specified text. "
                + "Automatically scrolls in the given direction and searches after each scroll. "
                + "Returns the element's bounds and center coordinates if found. "
                + "Much more efficient than manually calling swipe + get_screen_info in a loop.";
    }

    @Override
    public String getDescriptionCN() {
        return "Scroll the screen to find an element containing the specified text. Automatically scrolls in the given direction and searches after each scroll."
                + " Returns the element's bounds and center coordinates when found. Much more efficient than manually looping swipe + get_screen_info.";
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Arrays.asList(
                new ToolParameter("text", "string", "The text to search for on the screen", true),
                new ToolParameter("direction", "string",
                        "Scroll direction: 'up' or 'down' (default 'down'). 'down' means content moves up to reveal lower content.", false),
                new ToolParameter("max_scrolls", "integer",
                        "Maximum number of scrolls to attempt (default 4, max 8)", false)
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

        // Get screen size
        int[] screenSize = getScreenSize();
        int screenWidth = screenSize[0];
        int screenHeight = screenSize[1];

        // Scroll parameters: scroll in the middle region of the screen, avoiding the top status bar and bottom nav bar
        int centerX = screenWidth / 2;
        int scrollStartY, scrollEndY;
        if ("up".equals(direction)) {
            // Scroll up (content moves down): swipe from top to bottom
            scrollStartY = (int) (screenHeight * 0.3);
            scrollEndY = (int) (screenHeight * 0.7);
        } else {
            // Scroll down (content moves up): swipe from bottom to top
            scrollStartY = (int) (screenHeight * 0.7);
            scrollEndY = (int) (screenHeight * 0.3);
        }

        String lastScreenContent = normalizeForChangeDetection(getScreenSnapshot(driver));

        // First search on current screen (no scroll). Reuse the snapshot above
        // so one screen read also populates the node map used for matching.
        ToolResult found = findElement(driver, text, false);
        if (found != null) {
            return found;
        }

        // Loop: scroll → find
        for (int i = 0; i < maxScrolls; i++) {
            // Perform swipe
            boolean swiped = driver.performSwipe(centerX, scrollStartY, centerX, scrollEndY, 400);
            if (!swiped) {
                return ToolResult.error("Swipe failed at scroll #" + (i + 1));
            }

            // Wait for page to settle
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ToolResult.error("Interrupted during scroll");
            }

            String currentScreenRaw = getScreenSnapshot(driver);
            String currentScreen = normalizeForChangeDetection(currentScreenRaw);

            // Search for target using the node map from the current snapshot.
            found = findElement(driver, text, false);
            if (found != null) {
                return found;
            }

            // Detect if we reached the bottom/top (screen content no longer changes)
            if (currentScreen != null && currentScreen.equals(lastScreenContent)) {
                return ToolResult.error("Element with text \"" + text + "\" not found. "
                        + "Reached the " + ("up".equals(direction) ? "top" : "bottom")
                        + " after " + (i + 1) + " scroll(s)."
                        + describeVisibleScreen(currentScreenRaw));
            }
            lastScreenContent = currentScreen;
        }

        return ToolResult.error("Element with text \"" + text + "\" not found after " + maxScrolls + " scroll(s)."
                + describeVisibleScreen(lastScreenContent));
    }

    /**
     * Search for an element containing the specified text on the current screen. Returns ToolResult if found, null if not found.
     */
    private ToolResult findElement(LocalAdbDeviceDriver driver, String text, boolean refresh) {
        List<UiNode> nodes = refresh
                ? driver.findNodesByText(text)
                : driver.findNodesByText(text, false);
        if (nodes.isEmpty()) {
            return null;
        }
        // Take the first enabled node
        for (UiNode node : nodes) {
            Rect bounds = node.getBounds();
            if (node.getEnabled() && bounds != null && bounds.width() > 0 && bounds.height() > 0) {
                int centerX = bounds.centerX();
                int centerY = bounds.centerY();
                StringBuilder sb = new StringBuilder();
                sb.append("Found element with text \"").append(text).append("\"");
                sb.append("\n  bounds=").append(bounds.toShortString());
                sb.append("\n  center=(").append(centerX).append(", ").append(centerY).append(")");
                sb.append("\n  clickable=").append(node.getClickable());
                if (node.getClassName() != null) {
                    sb.append("\n  class=").append(node.getClassName());
                }
                return ToolResult.success(sb.toString());
            }
        }
        return null;
    }

    private String describeVisibleScreen(String screenContent) {
        if (screenContent == null || screenContent.trim().isEmpty()) {
            return "";
        }
        String trimmed = screenContent.trim();
        if (trimmed.length() > 1200) {
            trimmed = trimmed.substring(0, 1200) + "\n...";
        }
        return "\nVisible screen:\n" + trimmed;
    }

    /**
     * Get a quick summary of screen content, used to detect whether the page has scrolled to the bottom/top.
     */
    private String getScreenSnapshot(LocalAdbDeviceDriver driver) {
        try {
            return driver.getScreenTree();
        } catch (Exception e) {
            return null;
        }
    }

    private String normalizeForChangeDetection(String screenContent) {
        if (screenContent == null) return null;
        return screenContent
                .replaceFirst("snapshot=s\\d+", "snapshot=s")
                .trim();
    }

    // getScreenSize() is provided by BaseTool
}
