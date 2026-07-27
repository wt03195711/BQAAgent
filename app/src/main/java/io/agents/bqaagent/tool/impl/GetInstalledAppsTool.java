// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.tool.impl;

import io.agents.bqaagent.ClawApplication;
import io.agents.bqaagent.R;
import io.agents.bqaagent.tool.BaseTool;
import io.agents.bqaagent.tool.ToolParameter;
import io.agents.bqaagent.tool.ToolResult;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Get the list of installed launchable apps on the device (app name + package name).
 * When the target app's package name is unknown, call this tool first to get the list, then use open_app to open it.
 */
public class GetInstalledAppsTool extends BaseTool {

    @Override
    public String getName() {
        return "get_installed_apps";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_get_installed_apps);
    }

    @Override
    public String getDescriptionEN() {
        return "Get a list of all installed launchable apps on the device, including app name and package name. Use this only when the user asks what apps are installed; open_app can resolve app names from the cached app index by itself.";
    }

    @Override
    public String getDescriptionCN() {
        return "Get a list of all installed launchable apps on the device, including app name and package name. Use this only when the user asks what apps are installed; open_app can resolve app names from the cached app index by itself.";
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Collections.singletonList(
                new ToolParameter("keyword", "string",
                        "Optional keyword to filter apps by name (case-insensitive). If empty, returns all apps.", false)
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        String keyword = optionalString(params, "keyword", "");

        try {
            List<AppReferenceIndex.AppReference> apps = AppReferenceIndex.listLaunchableApps(keyword);
            if (apps.isEmpty() && keyword.isEmpty()) {
                return ToolResult.error("No installed apps found");
            }

            if (apps.isEmpty()) {
                return ToolResult.success("No apps found matching keyword: " + keyword);
            }

            StringBuilder sb = new StringBuilder();
            sb.append("Found ").append(apps.size()).append(" apps:\n");
            for (AppReferenceIndex.AppReference app : apps) {
                sb.append(app.toDisplayLine()).append("\n");
            }
            return ToolResult.success(sb.toString());
        } catch (Exception e) {
            return ToolResult.error("Failed to get installed apps: " + e.getMessage());
        }
    }
}
