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
import java.util.Collections;

public class InputTextTool extends BaseTool {

    @Override
    public String getName() {
        return "input_text";
    }

    @Override
    public List<String> getValueParamNames() {
        return Collections.singletonList("text");
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_input_text);
    }

    @Override
    public String getDescriptionEN() {
        return "Input text into a focused text field through Local ADB. If node_id is provided, taps that node first. "
                + "By default clears existing content before inputting (clear_first=true).";
    }

    @Override
    public String getDescriptionCN() {
        return "Input text into a focused text field through Local ADB. If node_id is provided, taps that node first. "
                + "By default clears existing content before inputting (clear_first=true).";
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Arrays.asList(
                new ToolParameter("text", "string", "The text to input", true),
                new ToolParameter("node_id", "string", "Optional: node ID from get_screen_info (e.g. 'n5') to target a specific text field", false),
                new ToolParameter("clear_first", "boolean", "Whether to clear existing text before input (default true)", false)
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        LocalAdbDeviceDriver driver = requireDeviceDriver();
        if (driver == null) {
            return ToolResult.error("Local ADB is not ready");
        }

        String text = requireString(params, "text");
        String nodeId = optionalString(params, "node_id", "");
        boolean clearFirst = optionalBoolean(params, "clear_first", true);

        boolean success = driver.inputText(text, nodeId.isEmpty() ? null : nodeId, clearFirst);
        if (success) {
            return ToolResult.success(clearFirst ? "Input text: " + text : "Appended text: " + text);
        }
        return ToolResult.error("Failed to input text through Local ADB. Make sure a text field is focused.");
    }
}
