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
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class SystemKeyTool extends BaseTool {

    @Override
    public String getName() {
        return "system_key";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_system_key);
    }

    @Override
    public String getDescriptionEN() {
        return "Press a system key. Supported keys: back (navigate back), home (go to home screen), recent_apps (open task switcher), notifications (expand notification shade), collapse_notifications (collapse notification/quick settings), lock_screen (lock screen, Android 9+), unlock_screen (wake up and unlock screen), enter (press Enter/submit), tab (press Tab).";
    }

    @Override
    public String getDescriptionCN() {
        return "Press a system key. Supported keys: back (go back), home (go to home screen), recent_apps (open recent tasks), notifications (expand notification bar), collapse_notifications (collapse notification bar/quick settings), lock_screen (lock screen, requires Android 9+), unlock_screen (wake and unlock screen), enter (press Enter/confirm), tab (press Tab).";
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Collections.singletonList(
                new ToolParameter(
                        "key",
                        "string",
                        "The system key to press. Must be one of: back, home, recent_apps, notifications, collapse_notifications, lock_screen, unlock_screen, enter, tab.",
                        true
                )
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        LocalAdbDeviceDriver driver = requireDeviceDriver();
        if (driver == null) {
            return ToolResult.error("Local ADB is not ready");
        }

        String key = requireString(params, "key");
        boolean success;
        String successMsg;

        switch (key) {
            case "back":
                success = driver.pressBack();
                successMsg = "Pressed Back button";
                break;
            case "home":
                success = driver.pressHome();
                successMsg = "Pressed Home button";
                break;
            case "recent_apps":
                success = driver.openRecentApps();
                successMsg = "Opened recent apps";
                break;
            case "notifications":
                success = driver.expandNotifications();
                successMsg = "Expanded notifications";
                break;
            case "collapse_notifications":
                success = driver.collapseNotifications();
                successMsg = "Collapsed notifications";
                break;
            case "lock_screen":
                success = driver.lockScreen();
                successMsg = "Screen locked";
                break;
            case "unlock_screen":
                success = driver.unlockScreen();
                successMsg = "Screen unlock requested";
                break;
            case "enter":
                success = driver.sendKeyEvent(android.view.KeyEvent.KEYCODE_ENTER);
                successMsg = "Pressed Enter key";
                break;
            case "tab":
                success = driver.sendKeyEvent(android.view.KeyEvent.KEYCODE_TAB);
                successMsg = "Pressed Tab key";
                break;
            default:
                return ToolResult.error("Unknown system key: " + key + ". Must be one of: back, home, recent_apps, notifications, collapse_notifications, lock_screen, unlock_screen, enter, tab.");
        }

        return success ? ToolResult.success(successMsg)
                : ToolResult.error("Failed to execute " + key);
    }
}
