// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.tool.impl;

import io.agents.bqaagent.adb.LocalAdbDeviceDriver;
import io.agents.bqaagent.adb.UiNode;
import io.agents.bqaagent.tool.BaseTool;
import io.agents.bqaagent.tool.ToolParameter;
import io.agents.bqaagent.tool.ToolResult;
import io.agents.bqaagent.utils.ContactMatchUtils;
import io.agents.bqaagent.utils.UiTextMatchUtils;
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
    public List<String> getValueParamNames() {
        return Arrays.asList("contact", "message", "app");
    }

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
            // Skip the search box / composer: on the search screen they carry the query text
            // themselves and would falsely make us think we are already inside the chat.
            if (node.isEditable() || node.getFocused()) continue;
            if (!matchesStrictly(node, normalizedAliases, digitAliases)) continue;
            return driver.bottomEditableNode() != null;
        }
        return false;
    }

    private boolean openContact(LocalAdbDeviceDriver driver, String contact) throws InterruptedException {
        LinkedHashSet<String> normalizedAliases = ContactMatchUtils.buildNormalizedAliases(contact);
        LinkedHashSet<String> digitAliases = ContactMatchUtils.buildDigitAliases(contact);

        // Step 1 — before searching the list is unfiltered, so only a strict exact/normalized
        // match is safe here (never tap an unrelated row just because it contains the letters).
        if (tapContactIfVisible(driver, normalizedAliases, digitAliases, /*relaxedOnFilteredScreen*/ false)) {
            return true;
        }

        // Step 2 — open the app's search and type the contact name.
        tapSearchIfVisible(driver);
        Thread.sleep(500);
        driver.inputText(contact, null, true);

        // Step 3 — search results render asynchronously (contact lookup / network can exceed any
        // fixed delay). Retry with settle delays instead of one-shot sleep. This is the general fix
        // for ANY contact, not just single-letter ones.
        for (int attempt = 0; attempt < 8; attempt++) {
            if (tapContactIfVisible(driver, normalizedAliases, digitAliases, /*relaxedOnFilteredScreen*/ true)) {
                return true;
            }
            Thread.sleep(800);
        }

        // Step 4 — result list longer than one screen: scroll to reveal more matches.
        int[] size = getScreenSize();
        int centerX = size[0] / 2;
        int startY = (int) (size[1] * 0.72);
        int endY = (int) (size[1] * 0.30);
        String lastTree = driver.getScreenTree();
        for (int i = 0; i < 10; i++) {
            driver.performSwipe(centerX, startY, centerX, endY, 400);
            Thread.sleep(700);
            if (tapContactIfVisible(driver, normalizedAliases, digitAliases, /*relaxedOnFilteredScreen*/ true)) {
                return true;
            }
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
            LinkedHashSet<String> digitAliases,
            boolean relaxedOnFilteredScreen
    ) {
        List<UiNode> nodes = driver.allTextNodes();

        // Pass 1 (strict): exact / normalized-equality match. Preferred whenever the row label is
        // exactly the contact name — works for any name length.
        UiNode strict = findContactNode(nodes, normalizedAliases, digitAliases, true);
        if (strict != null) {
            return driver.performTap(strict.getCenterX(), strict.getCenterY());
        }

        // Pass 2 (tolerant substring). Safe on the already-filtered search-results screen for ANY
        // target length (the list only contains query matches), and on the main list only when the
        // target is unambiguous. The search box / composer are never candidates because those carry
        // the query text themselves and are excluded in findContactNode.
        if (relaxedOnFilteredScreen || !isAmbiguousShortTarget(normalizedAliases)) {
            UiNode tolerant = findContactNode(nodes, normalizedAliases, digitAliases, false);
            if (tolerant != null) {
                return driver.performTap(tolerant.getCenterX(), tolerant.getCenterY());
            }
        }

        return false;
    }

    /**
     * Picks the best contact-result node from {@code nodes}. Never returns an editable field or the
     * focused input (search box / message composer), and prefers an enabled, clickable row.
     *
     * @param strictOnly when true, requires exact/normalized equality; when false, allows the
     *                   tolerant substring match via {@link ContactMatchUtils}.
     */
    private UiNode findContactNode(
            List<UiNode> nodes,
            LinkedHashSet<String> normalizedAliases,
            LinkedHashSet<String> digitAliases,
            boolean strictOnly
    ) {
        UiNode fallback = null;
        for (UiNode node : nodes) {
            if (node.isEditable() || node.getFocused()) continue;
            if (node.getBounds().top < 120) continue;

            boolean hit = strictOnly
                    ? matchesStrictly(node, normalizedAliases, digitAliases)
                    : ContactMatchUtils.matchesTarget(
                            node.getText(), node.getContentDescription(), normalizedAliases, digitAliases);
            if (!hit) continue;

            if (node.getClickable() && node.getEnabled()) return node;
            if (fallback == null) fallback = node;
        }
        return fallback;
    }

    private boolean matchesStrictly(
            UiNode node,
            LinkedHashSet<String> normalizedAliases,
            LinkedHashSet<String> digitAliases
    ) {
        String text = node.getText();
        String desc = node.getContentDescription();
        for (String alias : normalizedAliases) {
            if (alias.isEmpty()) continue;
            if (UiTextMatchUtils.matchesExactOrNormalized(text, alias)
                    || UiTextMatchUtils.matchesExactOrNormalized(desc, alias)) {
                return true;
            }
        }
        for (String digits : digitAliases) {
            if (digits.isEmpty()) continue;
            if (UiTextMatchUtils.matchesExactOrNormalized(text, digits)
                    || UiTextMatchUtils.matchesExactOrNormalized(desc, digits)) {
                return true;
            }
        }
        return false;
    }

    /** True when every non-empty alias is a single character (e.g. contact "A") — too ambiguous for substring matching. */
    private boolean isAmbiguousShortTarget(LinkedHashSet<String> normalizedAliases) {
        boolean hasAny = false;
        for (String alias : normalizedAliases) {
            if (alias.isEmpty()) continue;
            hasAny = true;
            if (alias.length() > 1) return false;
        }
        return hasAny;
    }
}
