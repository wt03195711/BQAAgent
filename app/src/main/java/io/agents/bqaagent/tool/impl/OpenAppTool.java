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
    public List<String> getValueParamNames() {
        return Collections.singletonList("package_name");
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
                new ToolParameter("package_name", "string",
                        "The app name EXACTLY as the user said it (preferred), or a package name you are completely certain about. "
                                + "Do NOT translate, abbreviate, or rephrase the app name, and do NOT guess package names — "
                                + "the tool resolves names against the installed-app index on the device.", true)
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        LocalAdbDeviceDriver driver = requireDeviceDriver();
        if (driver == null) {
            return ToolResult.error("Local ADB is not ready");
        }
        String requestedApp = params.containsKey("package_name")
                ? requireString(params, "package_name")
                : requireString(params, "app_name");

        AppReferenceIndex.warmUp();
        AppReferenceIndex.ResolveResult result = AppReferenceIndex.resolveApp(requestedApp);

        if (!result.isResolved()) {
            if (!result.candidates.isEmpty()) {
                return ToolResult.error(buildAmbiguousMessage(requestedApp, result));
            }
            return ToolResult.error(buildNotFoundMessage(requestedApp));
        }

        String packageName = result.packageName;
        if (!packageName.equals(requestedApp.trim())) {
            XLog.i(TAG, "Resolved '" + requestedApp + "' -> '" + packageName + "'");
        }

        boolean success = driver.openApp(packageName);
        if (!success) {
            AppReferenceIndex.refresh();
            AppReferenceIndex.ResolveResult retried = AppReferenceIndex.resolveApp(requestedApp);
            if (retried.isResolved() && !retried.packageName.equals(packageName)) {
                packageName = retried.packageName;
                XLog.i(TAG, "Retrying open after app index refresh: " + requestedApp + " -> " + packageName);
                success = driver.openApp(packageName);
            } else if (!retried.isResolved()) {
                if (!retried.candidates.isEmpty()) {
                    return ToolResult.error(buildAmbiguousMessage(requestedApp, retried));
                }
                return ToolResult.error(buildNotFoundMessage(requestedApp));
            }
        }
        if (!success) {
            return ToolResult.error(buildLaunchFailedMessage(requestedApp, packageName));
        }

        // Wait for possible chain-launch intercept dialog and auto-click "Allow"
        if (shouldCheckChainLaunchDialog()) {
            dismissChainLaunchDialog(driver);
        }

        return ToolResult.success("Opened app: " + packageName);
    }

    private String buildAmbiguousMessage(String requestedApp, AppReferenceIndex.ResolveResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("No exact installed match for \"").append(requestedApp)
                .append("\". Closest candidates (partial matches, NOT opened):\n");
        for (AppReferenceIndex.AppReference candidate : result.candidates) {
            sb.append("  - ").append(candidate.toDisplayLine()).append("\n");
        }
        sb.append("If the app the user wants is in the list, retry open_app with its EXACT app name or package name. ")
                .append("If it is not in the list, call get_installed_apps(keyword=...) to search; if still not found, the app is not installed.");
        return sb.toString();
    }

    private String buildNotFoundMessage(String requestedApp) {
        return "Failed to resolve app \"" + requestedApp + "\": no installed app matched by name, package name, alias, or keyword. "
                + "Do NOT conclude it is uninstalled yet. Call get_installed_apps(keyword=\"<main keyword of the app name>\") to look up the exact package name, then retry open_app with it. "
                + "If the lookup is also empty, the app is likely not installed: call finish and tell the user.";
    }

    private String buildLaunchFailedMessage(String requestedApp, String packageName) {
        return "Failed to launch " + packageName + " (resolved from \"" + requestedApp + "\") even though it is in the installed-app index. "
                + "Call get_installed_apps to confirm the package name, then retry once with the exact package name; if it still fails, call finish and report the problem.";
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

    /** Public static version for other tools to reuse (delegates to the staged resolver). */
    public static String resolveAppNameStatic(String appName) {
        return AppReferenceIndex.resolvePackageName(appName);
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
