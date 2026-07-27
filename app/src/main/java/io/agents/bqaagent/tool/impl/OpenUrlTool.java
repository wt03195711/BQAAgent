// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.tool.impl;

import io.agents.bqaagent.adb.LocalAdbDeviceDriver;
import io.agents.bqaagent.tool.BaseTool;
import io.agents.bqaagent.tool.ToolParameter;
import io.agents.bqaagent.tool.ToolResult;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class OpenUrlTool extends BaseTool {

    @Override
    public String getName() {
        return "open_url";
    }

    @Override
    public String getDisplayName() {
        return "Open URL";
    }

    @Override
    public String getDescriptionEN() {
        return "Open a URL directly through Android VIEW intent, preferably in Chrome. Use this for web pages and search URLs instead of manually editing the browser address bar.";
    }

    @Override
    public String getDescriptionCN() {
        return "Open a URL directly through Android VIEW intent, preferably in Chrome. Use this for web pages and search URLs instead of manually editing the browser address bar.";
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Arrays.asList(
                new ToolParameter("url", "string", "URL to open. If no scheme is provided, https:// is added.", true),
                new ToolParameter("package_name", "string", "Optional browser package. Default: com.android.chrome", false)
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        LocalAdbDeviceDriver driver = requireDeviceDriver();
        if (driver == null) {
            return ToolResult.error("Local ADB is not ready");
        }
        String url = requireString(params, "url");
        String packageName = optionalString(params, "package_name", "com.android.chrome");
        boolean success = driver.openUrl(url, packageName);
        if (success) {
            return ToolResult.success("Opened URL: " + url);
        }
        return ToolResult.error("Failed to open URL: " + url);
    }
}
