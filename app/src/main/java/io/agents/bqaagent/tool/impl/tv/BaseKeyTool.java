// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.tool.impl.tv;

import io.agents.bqaagent.adb.LocalAdbDeviceDriver;
import io.agents.bqaagent.tool.BaseTool;
import io.agents.bqaagent.tool.ToolParameter;
import io.agents.bqaagent.tool.ToolResult;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Base class for simple TV remote key tools that send a single key event.
 */
public abstract class BaseKeyTool extends BaseTool {

    /**
     * Returns the Android KeyEvent keycode to send.
     */
    protected abstract int getKeyCode();

    /**
     * Returns a human-readable label for logging (e.g. "D-pad Up").
     */
    protected abstract String getKeyLabel();

    @Override
    public List<ToolParameter> getParameters() {
        return Collections.emptyList();
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        LocalAdbDeviceDriver driver = requireDeviceDriver();
        if (driver == null) {
            return ToolResult.error("Local ADB is not ready");
        }
        boolean success = driver.sendKeyEvent(getKeyCode());
        return success
                ? ToolResult.success("Pressed " + getKeyLabel())
                : ToolResult.error("Failed to press " + getKeyLabel());
    }
}
