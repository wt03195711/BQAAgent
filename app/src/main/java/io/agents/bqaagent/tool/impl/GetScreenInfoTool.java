// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.tool.impl;

import io.agents.bqaagent.ClawApplication;
import io.agents.bqaagent.R;
import io.agents.bqaagent.adb.LocalAdbDeviceDriver;
import io.agents.bqaagent.tool.BaseTool;
import io.agents.bqaagent.tool.ToolParameter;
import io.agents.bqaagent.tool.ToolResult;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class GetScreenInfoTool extends BaseTool {

    @Override
    public String getName() {
        return "get_screen_info";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_get_screen_info);
    }

    @Override
    public String getDescriptionEN() {
        return "Get the current screen's UI elements from ADB dump. Optional mode: compact (default, row-compressed), form (fields/actions), actionable (clickable/editable nodes), or text (visible text rows). Node IDs (e.g. [n3]) can be used with tap_node and stay valid for the current snapshot.";
    }

    @Override
    public String getDescriptionCN() {
        return "Get the current screen's UI elements from ADB dump. Optional mode: compact (default, row-compressed), form (fields/actions), actionable (clickable/editable nodes), or text (visible text rows). Node IDs (e.g. [n3]) can be used with tap_node and stay valid for the current snapshot.";
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Arrays.asList(
                new ToolParameter("mode", "string", "Optional output mode: compact, form, actionable, or text. Default: compact.", false)
        );
    }

    public static final String SYSTEM_DIALOG_BLOCKED = "__SYSTEM_DIALOG_BLOCKED__";

    /**
     * Switch to full node tree mode (includes all nodes and all attributes, for debugging).
     * false = compact mode (default, saves tokens); true = full mode.
     */
    public static boolean useFullTree = false;

    @Override
    public ToolResult execute(Map<String, Object> params) {
        LocalAdbDeviceDriver driver = requireDeviceDriver();
        if (driver == null) {
            return ToolResult.error("Local ADB is not ready");
        }
        Object modeValue = params == null ? null : params.get("mode");
        String mode = modeValue == null ? null : String.valueOf(modeValue);
        String tree = useFullTree ? driver.getScreenTreeFull() : driver.getScreenTree(mode);
        if (tree == null) {
            return ToolResult.error(SYSTEM_DIALOG_BLOCKED);
        }
        return ToolResult.success(tree);
    }
}
