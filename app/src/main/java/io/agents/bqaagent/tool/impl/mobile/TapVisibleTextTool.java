// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.tool.impl.mobile;

import android.graphics.Rect;

import io.agents.bqaagent.adb.LocalAdbDeviceDriver;
import io.agents.bqaagent.adb.UiNode;
import io.agents.bqaagent.tool.BaseTool;
import io.agents.bqaagent.tool.ToolParameter;
import io.agents.bqaagent.tool.ToolResult;

import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Taps visible text on the current screen without scrolling.
 * Useful for fixed navigation tabs where scroll_to_find is the wrong primitive.
 */
public class TapVisibleTextTool extends BaseTool {
    @Override
    public String getName() {
        return "tap_visible_text";
    }

    @Override
    public List<String> getValueParamNames() {
        return Collections.singletonList("text");
    }

    @Override
    public String getDisplayName() {
        return "Tap Visible Text";
    }

    @Override
    public String getDescriptionEN() {
        return "Tap text that is already visible on the current screen. Does not scroll. Use for fixed tabs and visible buttons.";
    }

    @Override
    public String getDescriptionCN() {
        return getDescriptionEN();
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Arrays.asList(
                new ToolParameter("text", "string", "Visible text/content description to tap", true),
                new ToolParameter("prefer", "string", "Optional: top, bottom, left, right, or center. Default center.", false)
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        LocalAdbDeviceDriver driver = requireDeviceDriver();
        if (driver == null) return ToolResult.error("Local ADB is not ready");

        String text = requireString(params, "text");
        String prefer = optionalString(params, "prefer", "center").toLowerCase();
        List<UiNode> matches = driver.findNodesByText(text, true);
        if (matches.isEmpty()) {
            return ToolResult.error("Visible text not found: " + text);
        }

        int[] size = getScreenSize();
        UiNode best = matches.stream()
                .filter(UiNode::getEnabled)
                .filter(node -> {
                    Rect bounds = node.getBounds();
                    return bounds != null && bounds.width() > 0 && bounds.height() > 0;
                })
                .max(Comparator.comparingInt(node -> score(node, prefer, size)))
                .orElse(null);
        if (best == null) return ToolResult.error("Text found but no enabled visible target: " + text);

        String boundsError = validateCoordinates(best.getCenterX(), best.getCenterY());
        if (boundsError != null) return ToolResult.error(boundsError);
        boolean tapped = driver.performTap(best.getCenterX(), best.getCenterY());
        return tapped
                ? ToolResult.success("Tapped visible text \"" + text + "\" at (" + best.getCenterX() + ", " + best.getCenterY() + ")")
                : ToolResult.error("Failed to tap visible text: " + text);
    }

    private int score(UiNode node, String prefer, int[] size) {
        int score = node.getClickable() ? 1000 : 0;
        int cx = node.getCenterX();
        int cy = node.getCenterY();
        switch (prefer) {
            case "top": return score + Math.max(0, size[1] - cy);
            case "bottom": return score + cy;
            case "left": return score + Math.max(0, size[0] - cx);
            case "right": return score + cx;
            default:
                return score - Math.abs(cx - size[0] / 2) - Math.abs(cy - size[1] / 2) / 2;
        }
    }
}
