// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.tool.impl.mobile;

import android.graphics.Rect;
import android.view.KeyEvent;

import io.agents.bqaagent.adb.LocalAdbDeviceDriver;
import io.agents.bqaagent.adb.UiNode;
import io.agents.bqaagent.tool.BaseTool;
import io.agents.bqaagent.tool.ToolParameter;
import io.agents.bqaagent.tool.ToolResult;
import io.agents.bqaagent.utils.UiTextMatchUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Inputs monetary amounts with numeric key events instead of clipboard paste.
 * This keeps amount entry deterministic and lets the tool report validation
 * blockers such as disabled Continue/Next or backend error text.
 */
public class InputAmountTool extends BaseTool {
    private static final List<String> VALIDATION_QUERIES = Arrays.asList(
            "12000", "发生错误", "發生錯誤", "sorry", "error", "warning",
            "minimum", "最低金额", "最低金額"
    );
    private static final List<String> LOGCAT_QUERIES = Arrays.asList(
            "12000", "liveexchangerates", "exchange-rate-request", "发生错误", "發生錯誤"
    );

    @Override
    public String getName() {
        return "input_amount";
    }

    @Override
    public String getDisplayName() {
        return "Input Amount";
    }

    @Override
    public String getDescriptionEN() {
        return "Enter a monetary amount into a visible amount field using numeric key events, then report amount field and Continue/Next state. Use field=\"debit\" for from/send amount and field=\"credit\" only when the user explicitly provides receive/to amount.";
    }

    @Override
    public String getDescriptionCN() {
        return getDescriptionEN();
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Arrays.asList(
                new ToolParameter("amount", "string", "Amount to enter, e.g. 1 or 1.50", true),
                new ToolParameter("field", "string", "Optional: debit/from/send, credit/to/receive, or auto. Default debit.", false),
                new ToolParameter("node_id", "string", "Optional: node ID of target amount field from get_screen_info.", false),
                new ToolParameter("clear_first", "boolean", "Whether to clear current amount first. Default true.", false),
                new ToolParameter("allow_receive_amount", "boolean", "Set true only when the user explicitly asked to enter receive/credit amount. Default false.", false),
                new ToolParameter("validation_wait_ms", "integer", "Milliseconds to wait for validation. Default 2500.", false)
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        LocalAdbDeviceDriver driver = requireDeviceDriver();
        if (driver == null) return ToolResult.error("Local ADB is not ready");

        String amount = normalizeAmount(requireString(params, "amount"));
        if (amount.isEmpty()) return ToolResult.error("amount must contain digits, optionally with one decimal point");
        String field = optionalString(params, "field", "debit");
        String normalizedField = normalize(field);
        String nodeId = optionalString(params, "node_id", "").replace("[", "").replace("]", "").trim();
        boolean clearFirst = optionalBoolean(params, "clear_first", true);
        boolean allowReceiveAmount = optionalBoolean(params, "allow_receive_amount", false);
        int waitMs = Math.max(300, Math.min(optionalInt(params, "validation_wait_ms", 2500), 5000));

        try { driver.getScreenTree("form"); } catch (Exception ignored) {}
        ScreenState before = inspectScreenState(driver);
        if (isCreditField(normalizedField) && before.isOwnAccountAmountPage() && !allowReceiveAmount) {
            return ToolResult.error(
                    "Do not enter the receive/credit amount unless the current request explicitly provides it. " +
                            "Keep the debit/from amount unchanged and finish with the blocker if Continue/Next remains disabled."
            );
        }

        UiNode target = !nodeId.isEmpty() ? driver.getNode(nodeId) : findAmountField(driver, field);
        if (target == null) return ToolResult.error("No visible enabled amount field found for field=\"" + field + "\".");
        if (!target.getEnabled()) return ToolResult.error("Target amount field " + target.getNodeId() + " is disabled.");
        Rect bounds = target.getBounds();
        if (bounds == null || bounds.width() <= 0 || bounds.height() <= 0) {
            return ToolResult.error("Target amount field has empty bounds and cannot be tapped.");
        }

        if (!driver.performTap(target.getCenterX(), target.getCenterY())) {
            return ToolResult.error("Failed to focus amount field " + target.getNodeId());
        }
        sleep(250);
        if (clearFirst) {
            driver.clearFocusedText(24);
            sleep(120);
        }
        if (!typeAmountWithKeyEvents(driver, amount)) {
            return ToolResult.error("Failed to enter amount with numeric key events.");
        }

        sleep(waitMs);
        ScreenState state = inspectScreenState(driver);
        if (state.cta != null && !state.cta.getEnabled() && state.messages.isEmpty()) {
            sleep(2500);
            state = inspectScreenState(driver);
        }

        String targetSummary = target.getNodeId() + " " + labelOf(target);
        String blocker = state.blockerMessage();
        String message = "Entered amount " + amount + " into " + targetSummary
                + ". Amount state: " + state.amountSummary()
                + ". Continue/Next: " + state.ctaSummary()
                + (blocker.isEmpty() ? "" : ". BLOCKED: " + blocker)
                + ". If Continue/Next is disabled, do not tap it; finish with the blocker.";
        return ToolResult.success(message);
    }

    private UiNode findAmountField(LocalAdbDeviceDriver driver, String field) {
        String normalizedField = normalize(field);
        List<UiNode> candidates = new ArrayList<>();
        for (UiNode node : driver.currentMappedNodes()) {
            if (!node.getEnabled()) continue;
            Rect bounds = node.getBounds();
            if (bounds == null || bounds.width() <= 0 || bounds.height() <= 0) continue;
            String label = labelOf(node);
            if (!isEditable(node) && !looksLikeAmountContainer(label)) continue;
            if (!matchesRequestedField(label, normalizedField)) continue;
            candidates.add(node);
        }
        if (candidates.isEmpty() && normalizedField.equals("auto")) {
            for (UiNode node : driver.currentMappedNodes()) {
                if (!node.getEnabled() || !isEditable(node)) continue;
                if (looksLikeAmountContainer(labelOf(node))) candidates.add(node);
            }
        }
        return candidates.stream().max(Comparator.comparingInt(node -> scoreAmountField(node, normalizedField))).orElse(null);
    }

    private boolean matchesRequestedField(String label, String field) {
        String normalizedLabel = normalize(label);
        if (field.equals("auto")) return looksLikeAmountContainer(label);
        boolean receiveSideLabel = normalizedLabel.contains("入账金额") ||
                normalizedLabel.contains("入賬金額") ||
                normalizedLabel.contains("入帳金額") ||
                normalizedLabel.contains("creditamount") ||
                normalizedLabel.contains("toamount") ||
                normalizedLabel.contains("receiveamount");
        if (field.equals("debit") || field.equals("from") || field.equals("send") || field.equals("source")) {
            return normalizedLabel.contains("汇出金额") ||
                    normalizedLabel.contains("debitamount") ||
                    normalizedLabel.contains("fromamount") ||
                    normalizedLabel.contains("sendamount") ||
                    (!receiveSideLabel && (
                            normalizedLabel.contains("金额") ||
                                    normalizedLabel.contains("金額") ||
                                    normalizedLabel.contains("amount")
                    ));
        }
        if (field.equals("credit") || field.equals("to") || field.equals("receive") || field.equals("destination")) {
            return receiveSideLabel;
        }
        return UiTextMatchUtils.matchesRelaxed(label, field);
    }

    private boolean isCreditField(String field) {
        return field.equals("credit") || field.equals("to") || field.equals("receive") || field.equals("destination");
    }

    private int scoreAmountField(UiNode node, String field) {
        int score = 0;
        String label = normalize(labelOf(node));
        if (isEditable(node)) score += 50;
        if (node.getFocused()) score += 10;
        if (node.getClickable()) score += 8;
        if (field.equals("debit") || field.equals("from") || field.equals("send") || field.equals("source")) {
            if (label.contains("汇出金额") || label.contains("sendamount") || label.contains("fromamount")) score += 80;
            score += Math.max(0, 2000 - node.getCenterY()) / 100;
        }
        if (field.equals("credit") || field.equals("to") || field.equals("receive") || field.equals("destination")) {
            if (label.contains("入账金额") || label.contains("receiveamount") || label.contains("toamount")) score += 80;
            score += node.getCenterY() / 100;
        }
        return score;
    }

    private boolean typeAmountWithKeyEvents(LocalAdbDeviceDriver driver, String amount) {
        for (int i = 0; i < amount.length(); i++) {
            int keyCode = keyCodeForAmountChar(amount.charAt(i));
            if (keyCode < 0 || !driver.sendKeyEvent(keyCode)) return false;
            sleep(80);
        }
        return true;
    }

    private int keyCodeForAmountChar(char ch) {
        if (ch >= '0' && ch <= '9') return KeyEvent.KEYCODE_0 + (ch - '0');
        if (ch == '.') return KeyEvent.KEYCODE_PERIOD;
        return -1;
    }

    private ScreenState inspectScreenState(LocalAdbDeviceDriver driver) {
        try { driver.getScreenTree("form"); } catch (Exception ignored) {}
        ScreenState state = new ScreenState();
        for (UiNode node : driver.currentMappedNodes()) {
            String label = labelOf(node);
            String normalized = normalize(label);
            if (looksLikeValidationMessage(normalized)) addMessage(state, label);
            if (isEditable(node) || looksLikeAmountContainer(label)) {
                boolean receiveSideLabel = normalized.contains("入账金额") ||
                        normalized.contains("入賬金額") ||
                        normalized.contains("入帳金額") ||
                        normalized.contains("receiveamount") ||
                        normalized.contains("toamount") ||
                        normalized.contains("creditamount");
                if (normalized.contains("汇出金额") || normalized.contains("sendamount") || normalized.contains("fromamount") || normalized.contains("debitamount")) {
                    state.debit = compactLabel(label);
                } else if (state.debit == null && !receiveSideLabel &&
                        (normalized.contains("金额") || normalized.contains("金額") || normalized.contains("amount"))) {
                    state.debit = compactLabel(label);
                } else if (receiveSideLabel) {
                    state.credit = compactLabel(label);
                }
            }
            if (isCta(node)) {
                if (state.cta == null || ctaScore(node) > ctaScore(state.cta)) state.cta = node;
            }
        }
        if ((state.cta != null && !state.cta.getEnabled()) || state.isOwnAccountAmountPage()) {
            try {
                for (String line : driver.findRecentLogcatLinesContaining(LOGCAT_QUERIES, 500, 5000)) {
                    addMessage(state, diagnosticMessageFromLogLine(line));
                }
            } catch (Exception ignored) {}
        }
        return state;
    }

    private boolean looksLikeValidationMessage(String normalizedLabel) {
        for (String query : VALIDATION_QUERIES) {
            if (normalizedLabel.contains(normalize(query))) return true;
        }
        return false;
    }

    private String diagnosticMessageFromLogLine(String line) {
        String compact = compactLabel(line);
        String normalized = normalize(compact);
        if (normalized.contains("12000") && normalized.contains("liveexchangerates")) {
            return "HSBC backend error 12000: liveexchangerates downstream server error";
        }
        if (normalized.contains("12000") && normalized.contains("exchange-rate-request")) {
            return "HSBC exchange-rate request failed with error 12000";
        }
        if (normalized.contains("exchange-rate-request") && normalized.contains("409conflict")) {
            return "HSBC exchange-rate request returned 409 Conflict";
        }
        if (normalized.contains("12000") && looksLikeBankBackendLog(normalized)) {
            return "Backend/error 12000: " + trimTo(compact, 180);
        }
        return "";
    }

    private boolean looksLikeBankBackendLog(String normalized) {
        return normalized.contains("hsbc") ||
                normalized.contains("sg.com.hsbc") ||
                normalized.contains("exchange-rate") ||
                normalized.contains("liveexchangerates") ||
                normalized.contains("transfer") ||
                normalized.contains("payment");
    }

    private void addMessage(ScreenState state, String label) {
        String compact = compactLabel(label);
        if (!compact.isEmpty() && !state.messages.contains(compact)) state.messages.add(compact);
    }

    private boolean isCta(UiNode node) {
        String label = normalize(labelOf(node));
        String id = normalize(node.getResourceId());
        return label.contains("继续") || label.contains("continue") || label.contains("下一步") ||
                label.contains("next") || id.contains("multistatebutton") || id.contains("submitcontainer");
    }

    private int ctaScore(UiNode node) {
        int score = node.getCenterY();
        if (node.getText() != null && !node.getText().isEmpty()) score += 1000;
        if (node.getEnabled()) score += 500;
        return score;
    }

    private boolean isEditable(UiNode node) {
        return node.getClassName() != null && node.getClassName().toLowerCase(Locale.ROOT).contains("edittext");
    }

    private boolean looksLikeAmountContainer(String label) {
        String normalized = normalize(label);
        return normalized.contains("金额") || normalized.contains("金額") || normalized.contains("amount") ||
                normalized.contains("sendamount") || normalized.contains("receiveamount");
    }

    private String labelOf(UiNode node) {
        return (safe(node.getText()) + " " + safe(node.getContentDescription()) + " " + safe(node.getResourceId())).trim();
    }

    private String compactLabel(String label) {
        return label == null ? "" : label.replaceAll("\\p{Cf}", "").replaceAll("\\s+", " ").trim();
    }

    private String normalize(String raw) {
        return raw == null ? "" : raw.replaceAll("\\p{Cf}", "").toLowerCase(Locale.ROOT).replace(" ", "");
    }

    private String normalizeAmount(String raw) {
        if (raw == null) return "";
        String cleaned = raw.trim().replace(",", "");
        if (!cleaned.matches("\\d+(\\.\\d{1,2})?")) return "";
        return cleaned.endsWith(".00") ? cleaned.substring(0, cleaned.length() - 3) : cleaned;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String trimTo(String value, int maxChars) {
        if (value == null) return "";
        if (value.length() <= maxChars) return value;
        return value.substring(0, Math.max(0, maxChars - 2)) + "..";
    }

    private void sleep(long durationMs) {
        try {
            Thread.sleep(durationMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static class ScreenState {
        String debit;
        String credit;
        UiNode cta;
        List<String> messages = new ArrayList<>();

        String amountSummary() {
            return "debit=[" + (debit == null ? "not found" : debit) + "], credit=[" + (credit == null ? "not found" : credit) + "]" +
                    (messages.isEmpty() ? "" : ", messages=" + messages);
        }

        String ctaSummary() {
            if (cta == null) return "not found";
            return (cta.getEnabled() ? "enabled" : "disabled") + " " + cta.getNodeId() + " " +
                    (cta.getText() == null || cta.getText().isEmpty() ? cta.getResourceId() : cta.getText());
        }

        boolean isOwnAccountAmountPage() {
            return debit != null && credit != null;
        }

        String blockerMessage() {
            if (cta != null && cta.getEnabled()) return "";

            String joined = String.join("; ", messages);
            String normalized = joined.toLowerCase(Locale.ROOT).replace(" ", "");
            if (normalized.contains("12000")) {
                return "Bank/backend returned error 12000 while calculating the receive amount/exchange rate; Continue remains unavailable.";
            }
            if (normalized.contains("409conflict") || normalized.contains("exchange-rate")) {
                return "Bank/backend returned an exchange-rate validation error while calculating the receive amount.";
            }
            if (cta != null && !cta.getEnabled()) {
                if (joined.isEmpty()) return "Continue/Next is disabled after amount entry.";
                return "Continue/Next is disabled after amount entry; validation message: " + joined;
            }
            return "";
        }
    }
}
