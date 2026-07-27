// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.tool.impl;

import io.agents.bqaagent.adb.LocalAdbDeviceDriver;
import io.agents.bqaagent.adb.UiNode;
import io.agents.bqaagent.tool.BaseTool;
import io.agents.bqaagent.tool.ToolParameter;
import io.agents.bqaagent.tool.ToolResult;
import io.agents.bqaagent.utils.ContactMatchUtils;
import io.agents.bqaagent.utils.XLog;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Generic high-level tool: sends a message to a contact in a messaging app
 * through Local ADB UI control.
 */
public class SendMessageTool extends BaseTool {

    private static final String TAG = "SendMessageTool";

    @Override
    public String getName() { return "send_message"; }

    @Override
    public String getDisplayName() { return "Send Message"; }

    @Override
    public String getDescriptionEN() {
        return "Send a text message to a contact via any messaging app (WhatsApp, Telegram, Messages, etc).";
    }

    @Override
    public String getDescriptionCN() {
        return "Send a text message to a contact via any messaging app (WhatsApp, Telegram, Messages, etc).";
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Arrays.asList(
                new ToolParameter("contact", "string", "Contact name or phone number to message (e.g. 'Mom', '+1 604 555 1234')", true),
                new ToolParameter("message", "string", "The message text to send", true),
                new ToolParameter("app", "string", "Messaging app name (default: WhatsApp)", false)
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        LocalAdbDeviceDriver driver = requireDeviceDriver();
        if (driver == null) {
            return ToolResult.error("Local ADB is not ready");
        }

        String contact = requireString(params, "contact");
        String message = requireString(params, "message");
        String app = params.containsKey("app") ? params.get("app").toString() : "WhatsApp";

        XLog.i(TAG, "Sending '" + message + "' to " + contact + " via " + app);

        try {
            String packageName = OpenAppTool.resolveAppNameStatic(app);
            if (packageName == null) packageName = app;
            if (!driver.openApp(packageName)) {
                return ToolResult.error("Failed to open " + app + ". Is it installed?");
            }
            Thread.sleep(2000);

            if (!isAlreadyInChatWith(driver, contact)) {
                if (!openContact(driver, contact)) {
                    return ToolResult.error("Could not find '" + contact + "' in " + app + ".");
                }
                Thread.sleep(1800);
            }

            UiNode input = null;
            for (int retry = 0; retry < 6; retry++) {
                input = driver.bottomEditableNode();
                if (input != null) break;
                Thread.sleep(700);
            }
            if (input == null) {
                return ToolResult.error("Could not find message input field.");
            }

            if (!driver.inputText(message, input.getNodeId(), true)) {
                return ToolResult.error("Could not input message text.");
            }
            Thread.sleep(600);

            UiNode send = driver.findBestSendNode();
            boolean sent = false;
            if (send != null) {
                sent = driver.performTap(send.getCenterX(), send.getCenterY());
            }
            if (!sent) {
                sent = driver.sendKeyEvent(android.view.KeyEvent.KEYCODE_ENTER);
            }
            if (!sent) {
                return ToolResult.error("Could not find or trigger send button.");
            }

            return ToolResult.success("Sent '" + message + "' to " + contact + " via " + app);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.error("Interrupted");
        } catch (Exception e) {
            XLog.e(TAG, "Failed", e);
            return ToolResult.error("Failed: " + e.getMessage());
        }
    }

    private boolean isAlreadyInChatWith(LocalAdbDeviceDriver driver, String contact) {
        LinkedHashSet<String> normalizedAliases = ContactMatchUtils.buildNormalizedAliases(contact);
        LinkedHashSet<String> digitAliases = ContactMatchUtils.buildDigitAliases(contact);
        for (UiNode node : driver.topTextNodes(320)) {
            if (ContactMatchUtils.matchesTarget(node.getText(), node.getContentDescription(), normalizedAliases, digitAliases)) {
                return driver.bottomEditableNode() != null;
            }
        }
        return false;
    }

    private boolean openContact(LocalAdbDeviceDriver driver, String contact) throws InterruptedException {
        LinkedHashSet<String> normalizedAliases = ContactMatchUtils.buildNormalizedAliases(contact);
        LinkedHashSet<String> digitAliases = ContactMatchUtils.buildDigitAliases(contact);

        if (tapContactIfVisible(driver, normalizedAliases, digitAliases)) return true;

        tapSearchIfVisible(driver);
        Thread.sleep(500);
        driver.inputText(contact, null, true);
        Thread.sleep(1200);
        if (tapContactIfVisible(driver, normalizedAliases, digitAliases)) return true;

        int[] size = getScreenSize();
        int centerX = size[0] / 2;
        int startY = (int) (size[1] * 0.72);
        int endY = (int) (size[1] * 0.30);
        String lastTree = driver.getScreenTree();
        for (int i = 0; i < 10; i++) {
            driver.performSwipe(centerX, startY, centerX, endY, 400);
            Thread.sleep(700);
            if (tapContactIfVisible(driver, normalizedAliases, digitAliases)) return true;
            String currentTree = driver.getScreenTree();
            if (currentTree != null && currentTree.equals(lastTree)) break;
            lastTree = currentTree;
        }
        return false;
    }

    private boolean tapSearchIfVisible(LocalAdbDeviceDriver driver) {
        for (String query : Arrays.asList("Search", "搜索", "搜尋", "検索")) {
            List<UiNode> nodes = driver.findNodesByText(query);
            if (!nodes.isEmpty()) {
                UiNode node = nodes.get(0);
                return driver.performTap(node.getCenterX(), node.getCenterY());
            }
        }
        return false;
    }

    private boolean tapContactIfVisible(
            LocalAdbDeviceDriver driver,
            LinkedHashSet<String> normalizedAliases,
            LinkedHashSet<String> digitAliases
    ) {
        for (UiNode node : driver.allTextNodes()) {
            if (!ContactMatchUtils.matchesTarget(node.getText(), node.getContentDescription(), normalizedAliases, digitAliases)) {
                continue;
            }
            if (node.getBounds().top < 120) {
                continue;
            }
            return driver.performTap(node.getCenterX(), node.getCenterY());
        }
        return false;
    }
}
