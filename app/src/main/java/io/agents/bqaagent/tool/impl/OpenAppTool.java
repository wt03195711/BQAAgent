// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.tool.impl;

import io.agents.bqaagent.ClawApplication;
import io.agents.bqaagent.R;
import io.agents.bqaagent.adb.LocalAdbDeviceDriver;
import io.agents.bqaagent.adb.UiNode;
import io.agents.bqaagent.tool.BaseTool;
import io.agents.bqaagent.tool.ToolParameter;
import io.agents.bqaagent.tool.ToolResult;
import io.agents.bqaagent.utils.XLog;

import android.os.Build;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class OpenAppTool extends BaseTool {

    private static final String TAG = "OpenAppTool";

    /**
     * Common "Allow" button labels on chain-launch intercept dialogs (covering major manufacturers).
     * These are the on-screen button text strings matched against the device UI — do not translate.
     * Xiaomi/MIUI: "允许" (Allow)
     * Huawei/EMUI/HarmonyOS: "允许" / "允许打开" (Allow / Allow to open)
     * OPPO/ColorOS: "允许" / "打开" (Allow / Open)
     * vivo/OriginOS: "允许" (Allow)
     * Samsung OneUI: "允许" (Allow)
     */
    private static final List<String> ALLOW_KEYWORDS = Arrays.asList(
            "允许", "允许打开", "打开", "Allow", "ALLOW"
    );
    private static final List<String> POSITIVE_BUTTON_IDS = Arrays.asList(
            "android:id/button1",
            "miuix.appcompat:id/button1",
            "com.android.permissioncontroller:id/permission_allow_button",
            "com.android.permissioncontroller:id/permission_allow_foreground_only_button",
            "com.android.permissioncontroller:id/permission_allow_one_time_button"
    );
    private static final long CHAIN_DIALOG_DUMP_TIMEOUT_MS = 1_000L;
    private static final long CHAIN_DIALOG_CHECK_DELAY_MS = 250L;
    private static final int CHAIN_DIALOG_CHECK_ATTEMPTS = 1;

    @Override
    public String getName() {
        return "open_app";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_open_app);
    }

    @Override
    public String getDescriptionEN() {
        return "Open an application by app name or package name. App names are resolved through an in-memory installed-app reference index, so do not call get_installed_apps first just to open an app.";
    }

    @Override
    public String getDescriptionCN() {
        return "Open an application by app name or package name. App names are resolved through an in-memory installed-app reference index, so do not call get_installed_apps first just to open an app.";
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Collections.singletonList(
                new ToolParameter("package_name", "string", "The app name or package name to open, e.g. 'HSBC Singapore Cert' or 'com.android.settings'", true)
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        LocalAdbDeviceDriver driver = requireDeviceDriver();
        if (driver == null) {
            return ToolResult.error("Local ADB is not ready");
        }
        String packageName = params.containsKey("package_name")
                ? requireString(params, "package_name")
                : requireString(params, "app_name");
        String requestedApp = packageName;

        AppReferenceIndex.warmUp();

        boolean resolvedFromName = false;
        String ambiguityWarning = null;
        if (!looksLikePackageName(packageName)) {
            List<AppReferenceIndex.AppReference> matches = AppReferenceIndex.listLaunchableApps(packageName);
            if (matches.size() > 1 && matches.size() <= 5) {
                StringBuilder sb = new StringBuilder();
                sb.append("Multiple apps match \"").append(packageName).append("\":\n");
                for (AppReferenceIndex.AppReference match : matches) {
                    sb.append("  - ").append(match.toDisplayLine()).append("\n");
                }
                sb.append("If the wrong app was opened, retry with the exact app name or package name from the list above.");
                ambiguityWarning = sb.toString();
                XLog.w(TAG, "Ambiguous app name: " + packageName + " (" + matches.size() + " matches)");
            } else if (matches.size() > 5) {
                ambiguityWarning = "Found " + matches.size() + " apps matching \"" + packageName
                        + "\". If the wrong app was opened, use get_installed_apps to find the exact package name and retry.";
                XLog.w(TAG, "Ambiguous app name: " + packageName + " (" + matches.size() + " matches, truncated)");
            }
            String resolved = resolveAppName(packageName);
            if (resolved != null) {
                XLog.i(TAG, "Resolved app name '" + packageName + "' → '" + resolved + "'");
                packageName = resolved;
                resolvedFromName = true;
            }
        }

        boolean success = driver.openApp(packageName);
        if (!success && looksLikePackageName(packageName)) {
            String guessedAlias = packageName.substring(packageName.lastIndexOf('.') + 1);
            String aliasResolved = resolveAppName(guessedAlias);
            if (aliasResolved != null && !aliasResolved.equals(packageName)) {
                XLog.i(TAG, "Retrying open after guessed package fallback: " + packageName + " → " + aliasResolved);
                packageName = aliasResolved;
                success = driver.openApp(packageName);
            }
        }
        if (!success && resolvedFromName) {
            AppReferenceIndex.refresh();
            String refreshedPackageName = resolveAppName(requestedApp);
            if (refreshedPackageName != null && !refreshedPackageName.equals(packageName)) {
                XLog.i(TAG, "Retrying open after app index refresh: " + requestedApp + " → " + refreshedPackageName);
                packageName = refreshedPackageName;
                success = driver.openApp(packageName);
            }
        }
        if (!success) {
            return ToolResult.error("Failed to open app: " + packageName + ". Make sure the app is installed.");
        }

        // Wait for possible chain-launch intercept dialog and auto-click "Allow"
        if (shouldCheckChainLaunchDialog()) {
            dismissChainLaunchDialog(driver);
        }

        return ToolResult.success(ambiguityWarning != null
                ? "Opened app: " + packageName + "\n\nNote: " + ambiguityWarning
                : "Opened app: " + packageName);
    }

    private boolean shouldCheckChainLaunchDialog() {
        String brand = Build.BRAND == null ? "" : Build.BRAND.toLowerCase();
        String manufacturer = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.toLowerCase();
        String product = Build.PRODUCT == null ? "" : Build.PRODUCT.toLowerCase();
        if (brand.contains("google") || manufacturer.contains("google")) return false;
        return !product.contains("sdk_gphone") && !product.contains("aosp");
    }

    /**
     * Some manufacturers (Xiaomi, Huawei, OPPO, vivo, etc.) show an intercept dialog
     * ("Allow xxx to open yyy?") when launching an app from the background.
     * This method waits for the dialog and auto-clicks the "Allow" button.
     * Checks once after a short settle delay; silently returns if no dialog appears.
     */
    private void dismissChainLaunchDialog(LocalAdbDeviceDriver driver) {
        for (int attempt = 0; attempt < CHAIN_DIALOG_CHECK_ATTEMPTS; attempt++) {
            try {
                Thread.sleep(CHAIN_DIALOG_CHECK_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            UiNode node = driver.findFirstNodeByIdsOrText(
                    POSITIVE_BUTTON_IDS,
                    ALLOW_KEYWORDS,
                    CHAIN_DIALOG_DUMP_TIMEOUT_MS
            );
            if (node != null && isPositiveDialogNode(node)) {
                boolean clicked = driver.performTap(node.getCenterX(), node.getCenterY());
                XLog.i(TAG, "Chain launch dialog: tapped positive node " + (clicked ? "success" : "failed"));
                if (clicked) return;
            }
        }
    }

    private boolean isPositiveDialogNode(UiNode node) {
        if (POSITIVE_BUTTON_IDS.contains(node.getResourceId())) return true;
        return matchesAllowButton(node.getText()) || matchesAllowButton(node.getContentDescription());
    }

    /**
     * Resolve common app names to package names.
     * Falls back to searching installed apps by label.
     */
    /** Public static version for other tools to reuse */
    public static String resolveAppNameStatic(String appName) {
        return AppReferenceIndex.resolvePackageName(appName);
    }

    private String resolveAppName(String appName) {
        return AppReferenceIndex.resolvePackageName(appName);
    }

    private boolean looksLikePackageName(String value) {
        return value != null && value.trim().matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+");
    }

    /**
     * Exact match for allow button labels, to avoid accidentally tapping other elements whose content contains the keyword
     */
    private boolean matchesAllowButton(String text) {
        if (text == null) return false;
        String trimmed = text.trim();
        for (String keyword : ALLOW_KEYWORDS) {
            if (trimmed.equalsIgnoreCase(keyword)) return true;
        }
        return false;
    }
}
