// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.tool.impl.mobile;

import android.graphics.Rect;

import io.agents.bqaagent.ClawApplication;
import io.agents.bqaagent.R;
import io.agents.bqaagent.adb.LocalAdbDeviceDriver;
import io.agents.bqaagent.adb.UiNode;
import io.agents.bqaagent.tool.BaseTool;
import io.agents.bqaagent.tool.ToolParameter;
import io.agents.bqaagent.tool.ToolResult;
import io.agents.bqaagent.utils.XLog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Swipe tool with scrollable-area awareness.
 *
 * 1. Direction mode: swipe(direction="up"/"down"/"left"/"right") computes a safe
 *    path inside the detected scrollable container automatically.
 * 2. Coordinate correction: when explicit coordinates fall outside every
 *    scrollable container, they are relocated into the best-matching container
 *    (direction preserved), so the gesture does not land on fixed navigation
 *    bars or keypads and trigger accidental taps.
 */
public class SwipeTool extends BaseTool {

    private static final String TAG = "SwipeTool";
    /** Keep the gesture this far inside scrollable region edges */
    private static final int REGION_PADDING = 6;
    /** Swipes shorter than this on the primary axis can register as taps */
    private static final int MIN_TRAVEL_PX = 120;
    /** Avoid the very top of the screen — a swipe starting there pulls down the notification shade */
    private static final float TOP_SAFE_MARGIN_RATIO = 0.05f;
    /** Avoid the very bottom — an upward swipe there can trigger gesture navigation */
    private static final float BOTTOM_SAFE_MARGIN_RATIO = 0.03f;
    /** Tolerance when checking whether a point belongs to a scrollable region */
    private static final int CONTAINMENT_TOLERANCE = 24;

    @Override
    public String getName() {
        return "swipe";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_swipe);
    }

    @Override
    public String getDescriptionEN() {
        return "Swipe on the screen. For scrolling, prefer passing only direction (up/down/left/right): the tool swipes safely inside the detected scrollable area. Explicit start/end coordinates are also accepted and will be adjusted automatically if they fall outside any scrollable area.";
    }

    @Override
    public String getDescriptionCN() {
        return "Swipe on the screen. For scrolling, prefer passing only direction (up/down/left/right): the tool swipes safely inside the detected scrollable area. Explicit start/end coordinates are also accepted and will be adjusted automatically if they fall outside any scrollable area.";
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Arrays.asList(
                new ToolParameter("direction", "string",
                        "Recommended for scrolling: 'up'/'down'/'left'/'right'. When provided, the tool performs the swipe safely inside the detected scrollable area and coordinates are optional.", false),
                new ToolParameter("start_x", "integer", "Start X coordinate (required if direction is omitted)", false),
                new ToolParameter("start_y", "integer", "Start Y coordinate (required if direction is omitted)", false),
                new ToolParameter("end_x", "integer", "End X coordinate (required if direction is omitted)", false),
                new ToolParameter("end_y", "integer", "End Y coordinate (required if direction is omitted)", false),
                new ToolParameter("duration_ms", "integer", "Swipe duration in milliseconds (default 500)", false)
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        LocalAdbDeviceDriver driver = requireDeviceDriver();
        if (driver == null) {
            return ToolResult.error("Local ADB is not ready");
        }
        String direction = optionalString(params, "direction", "").trim().toLowerCase(Locale.ROOT);
        Integer startX = optionalIntOrNull(params, "start_x");
        Integer startY = optionalIntOrNull(params, "start_y");
        Integer endX = optionalIntOrNull(params, "end_x");
        Integer endY = optionalIntOrNull(params, "end_y");
        long duration = optionalLong(params, "duration_ms", 500);
        int[] screen = getScreenSize();

        boolean hasCoords = startX != null && startY != null && endX != null && endY != null;
        boolean validDirection = "up".equals(direction) || "down".equals(direction)
                || "left".equals(direction) || "right".equals(direction);
        if (!hasCoords && !validDirection) {
            return ToolResult.error("Provide either direction (up/down/left/right) or all of start_x/start_y/end_x/end_y.");
        }

        List<UiNode> regions;
        try {
            regions = LocalAdbDeviceDriver.findScrollableRegions(false);
        } catch (Throwable t) {
            XLog.w(TAG, "findScrollableRegions failed: " + t.getMessage());
            regions = new ArrayList<>();
        }

        int fromX, fromY, toX, toY;
        String note;
        if (!hasCoords) {
            Rect area = pickAreaForDirection(regions, direction, screen);
            int[] path = synthesizePath(area, direction);
            fromX = path[0];
            fromY = path[1];
            toX = path[2];
            toY = path[3];
            note = "";
        } else {
            String boundsError = validateCoordinates(startX, startY);
            if (boundsError != null) return ToolResult.error(boundsError);
            boundsError = validateCoordinates(endX, endY);
            if (boundsError != null) return ToolResult.error(boundsError);
            StringBuilder correctionNote = new StringBuilder();
            int[] corrected = correctCoordinates(startX, startY, endX, endY, direction, regions, screen, correctionNote);
            fromX = corrected[0];
            fromY = corrected[1];
            toX = corrected[2];
            toY = corrected[3];
            note = correctionNote.toString();
        }

        boolean success = driver.performSwipe(fromX, fromY, toX, toY, duration);
        if (!success) {
            return ToolResult.error("Failed to swipe");
        }
        StringBuilder message = new StringBuilder("Swiped from (")
                .append(fromX).append(", ").append(fromY).append(") to (")
                .append(toX).append(", ").append(toY).append(")");
        if (!note.isEmpty()) {
            message.append(". ").append(note);
        }
        return ToolResult.success(message.toString());
    }

    // === Direction mode: synthesize a safe path ===

    private Rect pickAreaForDirection(List<UiNode> regions, String direction, int[] screen) {
        boolean vertical = "up".equals(direction) || "down".equals(direction);
        UiNode best = null;
        long bestArea = -1;
        for (UiNode region : regions) {
            Rect b = region.getBounds();
            boolean suitable = vertical ? b.height() >= screen[1] * 0.2 : b.width() >= screen[0] * 0.3;
            if (!suitable) continue;
            long area = (long) b.width() * b.height();
            if (area > bestArea) {
                bestArea = area;
                best = region;
            }
        }
        if (best == null && !regions.isEmpty()) {
            best = regions.get(0);
        }
        Rect bounds = best != null ? best.getBounds() : new Rect(0, 0, screen[0], screen[1]);
        return safeRect(bounds, screen);
    }

    private int[] synthesizePath(Rect r, String direction) {
        if ("up".equals(direction) || "down".equals(direction)) {
            int x = (r.left + r.right) / 2;
            int nearTop = r.top + (int) (r.height() * 0.2);
            int nearBottom = r.top + (int) (r.height() * 0.8);
            if (nearBottom - nearTop < MIN_TRAVEL_PX) {
                nearTop = r.top;
                nearBottom = r.bottom;
            }
            return "up".equals(direction)
                    ? new int[]{x, nearBottom, x, nearTop}
                    : new int[]{x, nearTop, x, nearBottom};
        }
        int y = (r.top + r.bottom) / 2;
        int nearLeft = r.left + (int) (r.width() * 0.12);
        int nearRight = r.right - (int) (r.width() * 0.12);
        if (nearRight - nearLeft < MIN_TRAVEL_PX) {
            nearLeft = r.left;
            nearRight = r.right;
        }
        return "left".equals(direction)
                ? new int[]{nearRight, y, nearLeft, y}
                : new int[]{nearLeft, y, nearRight, y};
    }

    // === Coordinate mode: relocate into a scrollable area ===

    private int[] correctCoordinates(int sx, int sy, int ex, int ey, String direction,
                                     List<UiNode> regions, int[] screen, StringBuilder note) {
        int[] original = new int[]{sx, sy, ex, ey};
        if (regions.isEmpty()) return original;

        int dx = ex - sx;
        int dy = ey - sy;
        boolean vertical;
        if ("up".equals(direction) || "down".equals(direction)) vertical = true;
        else if ("left".equals(direction) || "right".equals(direction)) vertical = false;
        else vertical = Math.abs(dy) >= Math.abs(dx);

        UiNode region = regionContaining(regions, sx, sy);
        if (region == null) {
            region = vertical
                    ? bestRegionForAxis(regions, true, Math.min(sx, ex), Math.max(sx, ex))
                    : bestRegionForAxis(regions, false, Math.min(sy, ey), Math.max(sy, ey));
        }
        if (region == null) return original;

        Rect r = safeRect(region.getBounds(), screen);
        if (r.width() < 20 || r.height() < 20) return original;

        int fx, fy, tx, ty;
        if (vertical) {
            boolean upward = "up".equals(direction) || (!"down".equals(direction) && dy <= 0);
            fx = clamp(sx, r.left, r.right);
            tx = clamp(ex, r.left, r.right);
            boolean pathInside = sy >= r.top && sy <= r.bottom && ey >= r.top && ey <= r.bottom;
            if (pathInside) {
                fy = sy;
                ty = ey;
                if (Math.abs(ty - fy) < MIN_TRAVEL_PX) {
                    int span = clamp((int) (r.height() * 0.55), MIN_TRAVEL_PX, r.height());
                    int mid = clamp((fy + ty) / 2, r.top + span / 2, r.bottom - span / 2);
                    fy = upward ? mid + span / 2 : mid - span / 2;
                    ty = upward ? mid - span / 2 : mid + span / 2;
                }
            } else {
                int nearTop = r.top + (int) (r.height() * 0.2);
                int nearBottom = r.top + (int) (r.height() * 0.8);
                if (nearBottom - nearTop < MIN_TRAVEL_PX) {
                    nearTop = r.top;
                    nearBottom = r.bottom;
                }
                fy = upward ? nearBottom : nearTop;
                ty = upward ? nearTop : nearBottom;
            }
        } else {
            boolean leftward = "left".equals(direction) || (!"right".equals(direction) && dx <= 0);
            fy = clamp(sy, r.top, r.bottom);
            ty = clamp(ey, r.top, r.bottom);
            boolean pathInside = sx >= r.left && sx <= r.right && ex >= r.left && ex <= r.right;
            if (pathInside) {
                fx = sx;
                tx = ex;
                if (Math.abs(tx - fx) < MIN_TRAVEL_PX) {
                    int span = clamp((int) (r.width() * 0.55), MIN_TRAVEL_PX, r.width());
                    int mid = clamp((fx + tx) / 2, r.left + span / 2, r.right - span / 2);
                    fx = leftward ? mid + span / 2 : mid - span / 2;
                    tx = leftward ? mid - span / 2 : mid + span / 2;
                }
            } else {
                int nearLeft = r.left + (int) (r.width() * 0.12);
                int nearRight = r.right - (int) (r.width() * 0.12);
                if (nearRight - nearLeft < MIN_TRAVEL_PX) {
                    nearLeft = r.left;
                    nearRight = r.right;
                }
                fx = leftward ? nearRight : nearLeft;
                tx = leftward ? nearLeft : nearRight;
            }
        }

        if (fx != sx || fy != sy || tx != ex || ty != ey) {
            XLog.i(TAG, "swipe corrected: (" + sx + "," + sy + ")->(" + ex + "," + ey + ")"
                    + " relocated to (" + fx + "," + fy + ")->(" + tx + "," + ty + ")"
                    + " inside [" + region.getNodeId() + "] bounds=" + region.getBounds().toShortString());
            note.append("Coordinates were outside the scrollable area and have been adjusted into [")
                    .append(region.getNodeId()).append("] bounds=")
                    .append(region.getBounds().toShortString())
                    .append(". Keep swipe coordinates inside that area.");
        }
        return new int[]{fx, fy, tx, ty};
    }

    // === Helpers ===

    /** Innermost scrollable region containing the point (with tolerance). */
    private UiNode regionContaining(List<UiNode> regions, int x, int y) {
        UiNode best = null;
        long bestArea = Long.MAX_VALUE;
        for (UiNode region : regions) {
            Rect b = region.getBounds();
            Rect inflated = new Rect(b.left - CONTAINMENT_TOLERANCE, b.top - CONTAINMENT_TOLERANCE,
                    b.right + CONTAINMENT_TOLERANCE, b.bottom + CONTAINMENT_TOLERANCE);
            if (inflated.contains(x, y)) {
                long area = (long) b.width() * b.height();
                if (area < bestArea) {
                    bestArea = area;
                    best = region;
                }
            }
        }
        return best;
    }

    /** Largest region, preferring one whose perpendicular span overlaps the gesture. */
    private UiNode bestRegionForAxis(List<UiNode> regions, boolean vertical, int spanLo, int spanHi) {
        UiNode best = null;
        long bestScore = -1;
        for (UiNode region : regions) {
            Rect b = region.getBounds();
            int rLo = vertical ? b.left : b.top;
            int rHi = vertical ? b.right : b.bottom;
            int overlap = Math.min(spanHi, rHi) - Math.max(spanLo, rLo);
            long area = (long) b.width() * b.height();
            long score = (overlap > 0 ? overlap * 1000L : 0) + Math.min(area, 999L);
            if (score > bestScore) {
                bestScore = score;
                best = region;
            }
        }
        return best;
    }

    /** Inset the region and keep it away from status bar / gesture navigation edges. */
    private Rect safeRect(Rect bounds, int[] screen) {
        int topSafe = (int) (screen[1] * TOP_SAFE_MARGIN_RATIO);
        int bottomSafe = (int) (screen[1] * (1 - BOTTOM_SAFE_MARGIN_RATIO));
        Rect r = new Rect(bounds);
        r.left = Math.max(r.left + REGION_PADDING, 0);
        r.right = Math.min(r.right - REGION_PADDING, screen[0]);
        r.top = Math.max(r.top + REGION_PADDING, topSafe);
        r.bottom = Math.min(r.bottom - REGION_PADDING, bottomSafe);
        return r;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static Integer optionalIntOrNull(Map<String, Object> params, String key) {
        Object value = params.get(key);
        if (value == null) return null;
        try {
            if (value instanceof Number) return ((Number) value).intValue();
            String text = value.toString().trim();
            if (text.isEmpty()) return null;
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}