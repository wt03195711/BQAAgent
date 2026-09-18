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
        return "Get the current screen's UI elements from ADB dump. Modes: detail (richest; every addressable element on its own line with node id + class + label + flags + tap coordinates + resource-id + bounds), compact (row-grouped, token-saving; every element in a row carries its OWN node id + tap coordinates), text (visible text rows only, no ids), full (complete raw node tree with class/package/bounds, for debugging hard cases). Every element line has a node id (e.g. [n12]) usable with tap_node and valid for the current snapshot; when a line lists multiple elements, use the id/coordinates of the element matching your target.";
    }

    @Override
    public String getDescriptionCN() {
        return "Get the current screen's UI elements from ADB dump. Modes: detail (richest; every addressable element on its own line with node id + class + label + flags + tap coordinates + resource-id + bounds), compact (row-grouped, token-saving; every element in a row carries its OWN node id + tap coordinates), text (visible text rows only, no ids), full (complete raw node tree with class/package/bounds, for debugging hard cases). Every element line has a node id (e.g. [n12]) usable with tap_node and valid for the current snapshot; when a line lists multiple elements, use the id/coordinates of the element matching your target.";
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Arrays.asList(
                new ToolParameter("mode", "string", "Output mode: detail (per-element, richest), compact (row-grouped), text (text rows), full (complete raw tree). Default: compact when unspecified.", false)
        );
    }

    public static final String SYSTEM_DIALOG_BLOCKED = "__SYSTEM_DIALOG_BLOCKED__";


    @Override
    public ToolResult execute(Map<String, Object> params) {
        LocalAdbDeviceDriver driver = requireDeviceDriver();
        if (driver == null) {
            return ToolResult.error("Local ADB is not ready");
        }
        Object modeValue = params == null ? null : params.get("mode");
        String mode = modeValue == null ? null : String.valueOf(modeValue);
        String tree = driver.getScreenTree(mode);
        if (tree == null) {
            return ToolResult.error(SYSTEM_DIALOG_BLOCKED);
        }
        return ToolResult.success(tree);
    }
}
