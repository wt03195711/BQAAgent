// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.tool.impl.mobile;

import android.graphics.Rect;

import io.agents.bqaagent.adb.LocalAdbDeviceDriver;
import io.agents.bqaagent.adb.UiNode;
import io.agents.bqaagent.tool.BaseTool;
import io.agents.bqaagent.tool.ToolParameter;
import io.agents.bqaagent.tool.ToolResult;
import io.agents.bqaagent.utils.UiTextMatchUtils;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Drives the stable ADB-only HSBC own-account transfer sub-flow as one tool.
 * This avoids spending an LLM round on every account-list and loading screen.
 */
public class BankOwnAccountTransferTool extends BaseTool {
    private static final List<String> HOME_TABS = Arrays.asList("首页", "首頁", "主页", "主頁", "Home");
    private static final List<String> TRANSFER_TABS = Arrays.asList("转账", "轉賬", "轉帳", "Transfer", "Transfers");
    private static final List<String> DASHBOARD_PEER_TABS = Arrays.asList(
            "银行卡", "銀行卡", "财务", "財務", "财富", "財富", "产品", "產品", "Cards", "Wealth", "Products"
    );
    private static final List<String> TRANSFER_TO_OWN_ACCOUNT_TEXTS = Arrays.asList(
            "转账至名下账户", "轉賬至名下賬戶", "转账至名下賬戶", "轉賬至名下账户",
            "轉帳至名下帳戶", "轉賬至名下帳戶", "名下账户", "名下賬戶", "名下帳戶",
            "Own account", "Own-account", "Between own accounts"
    );
    private static final List<String> SOURCE_SELECTION_MARKERS = Arrays.asList(
            "汇出账户", "匯出賬戶", "汇出賬戶", "匯出帳戶",
            "From account", "Source account", "Debit account"
    );
    private static final List<String> DESTINATION_SELECTION_MARKERS = Arrays.asList(
            "入账账户", "入賬賬戶", "入帳帳戶", "收款账户", "收款賬戶", "收款帳戶",
            "To account", "Destination account", "Credit account"
    );
    private static final List<String> AMOUNT_PAGE_MARKERS = Arrays.asList(
            "汇出金额", "匯出金額", "入账金额", "入賬金額", "入帳金額",
            "金额", "金額",
            "Debit amount", "From amount", "Send amount", "Credit amount", "To amount", "Receive amount", "Amount"
    );
    private static final List<String> CONTINUE_TEXTS = Arrays.asList("继续", "繼續", "Continue", "下一步", "Next");
    private static final List<String> CONFIRM_TEXTS = Arrays.asList("确认", "確認", "Confirm");
    private static final List<String> SUBMIT_TEXTS = Arrays.asList("提交", "Submit");
    private static final List<String> CLOSE_TEXTS = Arrays.asList("关闭", "關閉", "Close", "Done", "完成");

    @Override
    public String getName() {
        return "bank_own_account_transfer";
    }

    @Override
    public String getDisplayName() {
        return "Bank Own Account Transfer";
    }

    @Override
    public String getDescriptionEN() {
        return "ADB-only helper for HSBC Singapore Cert own-account transfers. From the dashboard or Pay/Transfer screen, navigate through the top Transfer tab only, select own-account transfer, choose source and destination accounts, enter debit/credit amounts, and optionally tap Continue/Confirm/Submit if enabled.";
    }

    @Override
    public String getDescriptionCN() {
        return getDescriptionEN();
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Arrays.asList(
                new ToolParameter("source_account", "string", "Source/from account number, e.g. 144-481868-060", true),
                new ToolParameter("destination_account", "string", "Destination/to account number, e.g. 144-481868-178", true),
                new ToolParameter("debit_amount", "string", "Debit/from amount to enter", true),
                new ToolParameter("credit_amount", "string", "Optional credit/to amount to enter when explicitly requested", false),
                new ToolParameter("submit_final", "boolean", "Tap enabled Continue/Confirm/Submit final actions after exact review match. Default false.", false)
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        LocalAdbDeviceDriver driver = requireDeviceDriver();
        if (driver == null) return ToolResult.error("Local ADB is not ready");

        String source = requireString(params, "source_account").trim();
        String destination = requireString(params, "destination_account").trim();
        String debitAmount = requireString(params, "debit_amount").trim();
        String creditAmount = optionalString(params, "credit_amount", "").trim();
        boolean submitFinal = optionalBoolean(params, "submit_final", false);
        StringBuilder trace = new StringBuilder();

        if (source.isEmpty() || destination.isEmpty() || debitAmount.isEmpty()) {
            return ToolResult.error("source_account, destination_account, and debit_amount are required.");
        }

        try {
            if (!ensureOwnAccountEntryStarted(driver, source, trace)) {
                return blocked("Source-account list did not appear. The tool did not tap dashboard cards; it only tried the visible top Transfer tab and own-account transfer entry.", trace);
            }
            if (!tapText(driver, source, "source account", trace)) {
                return blocked("Source account not found or not tappable: " + source + ".", trace);
            }

            if (!waitForDestinationSelectionScreen(driver, destination, 18_000)) {
                return blocked("Destination-account list did not appear after selecting the source account.", trace);
            }
            if (!tapText(driver, destination, "destination account", trace)) {
                return blocked("Destination account not found or not tappable: " + destination + ".", trace);
            }
            waitForAmountPage(driver, 12_000);

            ToolResult debit = enterAmount("debit", debitAmount, false);
            trace.append("debit=[").append(summaryOf(debit)).append("]; ");
            if (!debit.isSuccess()) return blocked(summaryOf(debit), trace);
            boolean debitBlocked = isBlocked(debit);

            if (!creditAmount.isEmpty() && debitBlocked) {
                ToolResult credit = enterAmount("credit", creditAmount, true);
                trace.append("credit=[").append(summaryOf(credit)).append("]; ");
                if (!credit.isSuccess()) return blocked(summaryOf(credit), trace);
                if (isBlocked(credit)) return ToolResult.success("BLOCKED: " + credit.getData() + " " + trace);
            } else if (!creditAmount.isEmpty()) {
                trace.append("credit=[skipped because debit amount enabled Continue/Next]; ");
            } else if (debitBlocked) {
                return ToolResult.success("BLOCKED: " + debit.getData() + " " + trace);
            }

            if (!submitFinal) {
                return ToolResult.success("Prepared own-account transfer. " + trace);
            }

            String finalResult = tapFinalActions(driver, source, destination, debitAmount, creditAmount, trace);
            if (finalResult.startsWith("BLOCKED:") || finalResult.startsWith("COMPLETED:")) {
                return ToolResult.success(finalResult + " " + trace);
            }
            return ToolResult.error(finalResult + " " + trace);
        } catch (Exception e) {
            return ToolResult.error("Bank transfer flow failed: " + e.getMessage() + ". " + trace);
        }
    }

    private boolean ensureOwnAccountEntryStarted(LocalAdbDeviceDriver driver, String source, StringBuilder trace) {
        if (!returnToDashboardBeforeTransfer(driver, trace)) {
            trace.append("dashboard reset not confirmed; ");
            return false;
        }

        if (tapDashboardTransferTabIfVisible(driver, trace)) {
            waitForAnyText(driver, concat(TRANSFER_TO_OWN_ACCOUNT_TEXTS, Arrays.asList("Pay", "Payment", "Transfer", "转账", "轉賬")), 8_000);
        }

        if (tapAnyIfVisible(driver, TRANSFER_TO_OWN_ACCOUNT_TEXTS, 10_000, trace)) {
            return waitForSourceSelectionScreen(driver, source, 15_000);
        }

        return waitForSourceSelectionScreen(driver, source, 1_500);
    }

    private boolean returnToDashboardBeforeTransfer(LocalAdbDeviceDriver driver, StringBuilder trace) {
        for (int attempt = 0; attempt < 6; attempt++) {
            try {
                driver.getScreenTree("actionable");
            } catch (Exception ignored) {
            }
            List<UiNode> nodes = driver.currentMappedNodes();
            if (isDashboardHomeScreen(nodes)) {
                trace.append("dashboard home confirmed; ");
                return true;
            }
            if (isRootHubScreen(nodes)) {
                if (tapHomeTabIfVisible(driver, trace)) {
                    sleep(1200);
                    continue;
                }
                trace.append("root hub visible without home tap; ");
                return true;
            }
            if (tapTopNavigationText(driver, CLOSE_TEXTS, "close-to-dashboard", trace)) {
                sleep(1500);
                continue;
            }
            if (driver.pressBack()) {
                trace.append("press back toward dashboard; ");
                sleep(1500);
                continue;
            }
            break;
        }

        try {
            driver.getScreenTree("actionable");
        } catch (Exception ignored) {
        }
        List<UiNode> nodes = driver.currentMappedNodes();
        if (isDashboardHomeScreen(nodes)) {
            trace.append("dashboard home confirmed; ");
            return true;
        }
        if (isRootHubScreen(nodes) && tapHomeTabIfVisible(driver, trace)) {
            sleep(1200);
            try {
                driver.getScreenTree("actionable");
            } catch (Exception ignored) {
            }
            return isDashboardHomeScreen(driver.currentMappedNodes());
        }
        return false;
    }

    private boolean isDashboardHomeScreen(List<UiNode> nodes) {
        if (!isRootHubScreen(nodes)) return false;
        return nodes.stream()
                .filter(UiNode::getEnabled)
                .filter(UiNode::getSelected)
                .anyMatch(node -> HOME_TABS.stream().anyMatch(tab -> matchesLabel(node, tab)));
    }

    private boolean isRootHubScreen(List<UiNode> nodes) {
        boolean hasHome = nodes.stream().anyMatch(node ->
                node.getEnabled() &&
                        hasVisibleBounds(node) &&
                        node.getCenterY() <= topNavigationMaxY() &&
                        HOME_TABS.stream().anyMatch(tab -> matchesLabel(node, tab))
        );
        if (!hasHome) return false;
        boolean hasTransfer = nodes.stream().anyMatch(node ->
                node.getEnabled() &&
                        hasVisibleBounds(node) &&
                        node.getCenterY() <= topNavigationMaxY() &&
                        TRANSFER_TABS.stream().anyMatch(tab -> matchesLabel(node, tab))
        );
        long peerCount = DASHBOARD_PEER_TABS.stream().filter(peer ->
                nodes.stream().anyMatch(node ->
                        node.getEnabled() &&
                                hasVisibleBounds(node) &&
                                node.getCenterY() <= topNavigationMaxY() &&
                                matchesLabel(node, peer)
                )
        ).count();
        return hasTransfer && peerCount >= 2;
    }

    private boolean tapHomeTabIfVisible(LocalAdbDeviceDriver driver, StringBuilder trace) {
        List<UiNode> homeCandidates = findNodesByLabel(driver, HOME_TABS);
        UiNode best = homeCandidates.stream()
                .filter(UiNode::getEnabled)
                .filter(this::hasVisibleBounds)
                .filter(node -> node.getCenterY() <= topNavigationMaxY())
                .filter(node -> dashboardPeerCountNear(node, driver.currentMappedNodes()) >= 2)
                .max(Comparator.comparingInt(node ->
                        (node.getClickable() ? 1000 : 0) +
                                (node.getSelected() ? 700 : 0) -
                                Math.abs(node.getCenterX() - 84)
                ))
                .orElse(null);
        if (best == null) return false;
        if (best.getSelected()) return true;
        boolean tapped = driver.performTap(best.getCenterX(), best.getCenterY());
        if (tapped) {
            trace.append("tap dashboard home tab@").append(best.getNodeId()).append("; ");
            sleep(1000);
        }
        return tapped;
    }

    private boolean tapDashboardTransferTabIfVisible(LocalAdbDeviceDriver driver, StringBuilder trace) {
        List<UiNode> transferCandidates = findNodesByLabel(driver, TRANSFER_TABS);
        if (transferCandidates.isEmpty()) return false;

        UiNode best = transferCandidates.stream()
                .filter(UiNode::getEnabled)
                .filter(this::hasVisibleBounds)
                .filter(node -> node.getCenterY() <= topNavigationMaxY())
                .filter(node -> dashboardPeerCountNear(node, driver.currentMappedNodes()) >= 2)
                .max(Comparator.comparingInt(node ->
                        (node.getClickable() ? 1000 : 0) +
                                dashboardPeerCountNear(node, driver.currentMappedNodes()) * 500 -
                                node.getCenterY()
                ))
                .orElse(null);

        if (best == null) {
            trace.append("dashboard transfer tab not confirmed; ");
            return false;
        }

        String boundsError = validateCoordinates(best.getCenterX(), best.getCenterY());
        if (boundsError != null) {
            trace.append("dashboard transfer tab out of bounds: ").append(boundsError).append("; ");
            return false;
        }

        boolean tapped = driver.performTap(best.getCenterX(), best.getCenterY());
        if (tapped) {
            trace.append("tap dashboard top transfer tab@")
                    .append(best.getNodeId())
                    .append("; ");
            sleep(1500);
        }
        return tapped;
    }

    private int topNavigationMaxY() {
        int[] size = getScreenSize();
        return Math.max(420, (int) (size[1] * 0.40f));
    }

    private int dashboardPeerCountNear(UiNode anchor, List<UiNode> nodes) {
        int count = 0;
        for (String peer : DASHBOARD_PEER_TABS) {
            boolean found = nodes.stream().anyMatch(node ->
                            node.getEnabled() &&
                            hasVisibleBounds(node) &&
                            Math.abs(node.getCenterY() - anchor.getCenterY()) <= 140 &&
                            matchesLabel(node, peer)
            );
            if (found) count++;
        }
        return count;
    }

    private ToolResult enterAmount(String field, String amount, boolean allowReceiveAmount) {
        Map<String, Object> amountParams = new HashMap<>();
        amountParams.put("field", field);
        amountParams.put("amount", amount);
        amountParams.put("allow_receive_amount", allowReceiveAmount);
        amountParams.put("validation_wait_ms", 3500);
        return new InputAmountTool().execute(amountParams);
    }

    private String tapFinalActions(
            LocalAdbDeviceDriver driver,
            String source,
            String destination,
            String debitAmount,
            String creditAmount,
            StringBuilder trace
    ) {
        String screen = safe(driver.getScreenTree("text"));
        if (isTransferSuccessScreen(screen)) {
            if (screenContainsRequestedDetails(screen, source, destination, debitAmount, creditAmount)) {
                return "COMPLETED: requested test transfer is already on the submitted receipt screen.";
            }
            return "BLOCKED: Submitted receipt screen did not exactly show the requested transfer details.";
        }

        if (!tapFirst(driver, CONTINUE_TEXTS, "continue", trace)) {
            return "BLOCKED: Continue/Next is not enabled after amount entry.";
        }

        screen = waitForPostContinueScreen(driver, 8_000);
        String blockingMessage = bankBlockingMessage(screen);
        if (!blockingMessage.isEmpty()) {
            return "BLOCKED: " + blockingMessage;
        }
        if (isAmountEntryScreen(screen)) {
            trace.append("continue remained on amount page; retry continue; ");
            if (!tapFirst(driver, CONTINUE_TEXTS, "continue", trace)) {
                return "BLOCKED: Continue/Next did not navigate away from the amount page.";
            }
            screen = waitForPostContinueScreen(driver, 10_000);
            blockingMessage = bankBlockingMessage(screen);
            if (!blockingMessage.isEmpty()) {
                return "BLOCKED: " + blockingMessage;
            }
        }
        if (isAmountEntryScreen(screen)) {
            return "BLOCKED: Continue/Next did not navigate away from the amount page.";
        }

        if (!screenContainsRequestedDetails(screen, source, destination, debitAmount, creditAmount)) {
            return "BLOCKED: Review screen did not exactly show the requested source, destination, and debit amount.";
        }
        if (isTransferSuccessScreen(screen)) {
            return "COMPLETED: requested test transfer reached the submitted receipt screen.";
        }

        if (!tapFirst(driver, CONFIRM_TEXTS, "confirm", trace)) {
            if (!tapFirst(driver, SUBMIT_TEXTS, "submit", trace)) {
                return "BLOCKED: Confirm/Submit button is not enabled or review screen is not ready.";
            }
            screen = waitForTransferSuccessScreen(driver, 10_000);
            if (isTransferSuccessScreen(screen)) {
                return "COMPLETED: tapped Continue and Submit for the exact requested test transfer.";
            }
            return "COMPLETED: tapped Continue and Submit after exact requested details; final receipt was not exposed by the dump.";
        }

        screen = waitForTransferDetailsOrSuccessScreen(driver, source, destination, debitAmount, creditAmount, 8_000);
        if (isTransferSuccessScreen(screen)) {
            return "COMPLETED: tapped Continue and Confirm for the exact requested test transfer.";
        }
        if (!screenContainsRequestedDetails(screen, source, destination, debitAmount, creditAmount)) {
            return "BLOCKED: Post-confirm screen did not preserve the requested transfer details.";
        }

        if (!tapFirst(driver, SUBMIT_TEXTS, "submit", trace)) {
            return "BLOCKED: Submit button is not enabled or final submission screen is not ready.";
        }
        screen = waitForTransferSuccessScreen(driver, 10_000);
        if (isTransferSuccessScreen(screen)) {
            return "COMPLETED: tapped Continue, Confirm, and Submit for the exact requested test transfer.";
        }
        return "COMPLETED: tapped Continue, Confirm, and Submit after exact requested details; final receipt was not exposed by the dump.";
    }

    private boolean isAmountEntryScreen(String screen) {
        String compact = normalize(screen);
        return compact.contains("sinputfieldtext") ||
                compact.contains("amountstate") ||
                ((compact.contains("继续") || compact.contains("continue") || compact.contains("下一步")) &&
                        (compact.contains("可用余额") ||
                                compact.contains("availablebalance") ||
                                compact.contains("汇出金额") ||
                                compact.contains("匯出金額") ||
                                compact.contains("入账金额") ||
                                compact.contains("入賬金額") ||
                                compact.contains("入帳金額") ||
                                compact.contains("debitamount") ||
                                compact.contains("creditamount") ||
                                compact.contains("fromamount") ||
                                compact.contains("toamount")));
    }

    private String waitForPostContinueScreen(LocalAdbDeviceDriver driver, long timeoutMs) {
        return waitForTextScreen(driver, timeoutMs, screen ->
                isTransferSuccessScreen(screen) || !isAmountEntryScreen(screen)
        );
    }

    private String waitForTransferDetailsOrSuccessScreen(
            LocalAdbDeviceDriver driver,
            String source,
            String destination,
            String debitAmount,
            String creditAmount,
            long timeoutMs
    ) {
        return waitForTextScreen(driver, timeoutMs, screen ->
                isTransferSuccessScreen(screen) ||
                        screenContainsRequestedDetails(screen, source, destination, debitAmount, creditAmount)
        );
    }

    private String waitForTransferSuccessScreen(LocalAdbDeviceDriver driver, long timeoutMs) {
        return waitForTextScreen(driver, timeoutMs, this::isTransferSuccessScreen);
    }

    private String waitForTextScreen(
            LocalAdbDeviceDriver driver,
            long timeoutMs,
            java.util.function.Predicate<String> predicate
    ) {
        long end = System.currentTimeMillis() + timeoutMs;
        String latest = safe(driver.getScreenTree("text"));
        while (System.currentTimeMillis() < end) {
            if (predicate.test(latest)) return latest;
            sleep(600);
            latest = safe(driver.getScreenTree("text"));
        }
        return latest;
    }

    private boolean screenContainsRequestedDetails(
            String screen,
            String source,
            String destination,
            String debitAmount,
            String creditAmount
    ) {
        String compact = normalize(screen);
        if (!compact.contains(normalize(source))) return false;
        if (!compact.contains(normalize(destination))) return false;
        if (!screenContainsAmount(screen, debitAmount)) return false;
        return creditAmount.isEmpty() || screenContainsAmount(screen, creditAmount);
    }

    private boolean screenContainsAmount(String screen, String amount) {
        String compact = normalize(screen);
        String requested = normalize(amount);
        if (requested.isEmpty()) return true;
        if (requested.matches("\\d+")) {
            return compact.contains(requested + ".0") ||
                    compact.contains(requested + ".00") ||
                    compact.contains(requested + ".00sgd") ||
                    compact.contains(requested + "sgd") ||
                    compact.contains(requested + ".00新加坡元") ||
                    compact.contains(requested + "新加坡元");
        }
        return compact.contains(requested);
    }

    private boolean isTransferSuccessScreen(String screen) {
        String compact = normalize(screen);
        return compact.contains("指示已提交") ||
                compact.contains("指示已遞交") ||
                compact.contains("您的转账正在进行中") ||
                compact.contains("您的轉賬正在進行中") ||
                compact.contains("您的轉帳正在進行中") ||
                (compact.contains("谢谢") && compact.contains("转账")) ||
                (compact.contains("謝謝") && compact.contains("轉賬")) ||
                (compact.contains("謝謝") && compact.contains("轉帳")) ||
                compact.contains("instructionsubmitted") ||
                compact.contains("yourtransferisinprogress") ||
                compact.contains("yourtransferisbeingprocessed") ||
                (compact.contains("submitted") && compact.contains("transfer")) ||
                (compact.contains("thankyou") && compact.contains("transfer"));
    }

    private String bankBlockingMessage(String screen) {
        String compact = normalize(screen);
        if (compact.contains("ref503") ||
                compact.contains("currentlyunavailable") ||
                compact.contains("notbeenprocessed") ||
                compact.contains("pleasetryagainlater") ||
                compact.contains("暂时无法使用") ||
                compact.contains("暫時無法使用") ||
                compact.contains("目前无法使用") ||
                compact.contains("目前無法使用") ||
                compact.contains("交易未处理") ||
                compact.contains("交易未處理")) {
            return "Bank validation/backend error after Continue: feature unavailable or transaction not processed (Ref 503 if shown).";
        }
        return "";
    }

    private boolean tapTopNavigationText(
            LocalAdbDeviceDriver driver,
            List<String> texts,
            String label,
            StringBuilder trace
    ) {
        List<UiNode> matches = findNodesByLabel(driver, texts);
        UiNode best = matches.stream()
                .filter(UiNode::getEnabled)
                .filter(this::hasVisibleBounds)
                .filter(node -> node.getCenterY() <= 380)
                .filter(node -> node.getCenterX() <= 240)
                .max(Comparator.comparingInt(node -> (node.getClickable() ? 1000 : 0) - node.getCenterY()))
                .orElse(null);
        if (best == null) return false;
        boolean tapped = driver.performTap(best.getCenterX(), best.getCenterY());
        if (tapped) {
            trace.append("tap ").append(label).append("@").append(best.getNodeId()).append("; ");
            sleep(1000);
        }
        return tapped;
    }

    private boolean tapAnyIfVisible(LocalAdbDeviceDriver driver, List<String> texts, long timeoutMs, StringBuilder trace) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            for (String text : texts) {
                if (tapText(driver, text, "own-account transfer", trace)) return true;
            }
            sleep(500);
        }
        return false;
    }

    private boolean tapFirst(LocalAdbDeviceDriver driver, List<String> texts, String label, StringBuilder trace) {
        for (String text : texts) {
            if (tapText(driver, text, label, trace)) return true;
        }
        return tapEnabledActionContainer(driver, texts, label, trace);
    }

    private boolean tapText(LocalAdbDeviceDriver driver, String text, String label, StringBuilder trace) {
        List<UiNode> matches = findNodesByLabel(driver, Arrays.asList(text));
        UiNode best = matches.stream()
                .filter(UiNode::getEnabled)
                .filter(node -> {
                    Rect bounds = node.getBounds();
                    return bounds != null && bounds.width() > 0 && bounds.height() > 0;
                })
                .max(Comparator.comparingInt(node -> (node.getClickable() ? 1000 : 0) + node.getCenterY()))
                .orElse(null);
        if (best == null) return false;
        boolean tapped = driver.performTap(best.getCenterX(), best.getCenterY());
        if (tapped) {
            trace.append("tap ").append(label).append(" ")
                    .append(text).append("@").append(best.getNodeId()).append("; ");
            sleep(1000);
        }
        return tapped;
    }

    private boolean tapEnabledActionContainer(
            LocalAdbDeviceDriver driver,
            List<String> texts,
            String label,
            StringBuilder trace
    ) {
        try {
            driver.getScreenTree("form");
        } catch (Exception ignored) {
        }
        UiNode best = driver.currentMappedNodes().stream()
                .filter(UiNode::getEnabled)
                .filter(this::hasVisibleBounds)
                .filter(node -> isExpectedActionContainer(node, texts, label))
                .max(Comparator.comparingInt(node -> actionScore(node, texts)))
                .orElse(null);
        if (best == null) return false;

        String boundsError = validateCoordinates(best.getCenterX(), best.getCenterY());
        if (boundsError != null) {
            trace.append(label).append(" action out of bounds: ").append(boundsError).append("; ");
            return false;
        }
        boolean tapped = driver.performTap(best.getCenterX(), best.getCenterY());
        if (tapped) {
            trace.append("tap ").append(label).append(" container@")
                    .append(best.getNodeId()).append("; ");
            sleep(1000);
        }
        return tapped;
    }

    private int actionScore(UiNode node, List<String> texts) {
        String label = normalize(labelOf(node));
        String id = normalize(safe(node.getResourceId()));
        int score = node.getCenterY();
        if (node.getClickable()) score += 1000;
        if (texts.stream().anyMatch(text -> matchesLabel(node, text))) score += 1200;
        if (id.contains("multistatebutton")) score += 900;
        if (id.contains("submitcontainer")) score += 700;
        if (id.contains("buttoncontainer")) score += 500;
        if (id.contains("calltoaction")) score += 300;
        if (label.contains("cancel") || label.contains("取消") || label.contains("back") || label.contains("返回")) score -= 2000;
        return score;
    }

    private boolean isFinancialActionContainer(UiNode node) {
        String id = normalize(safe(node.getResourceId()));
        String label = normalize(labelOf(node));
        return id.contains("multistatebutton") ||
                id.contains("submitcontainer") ||
                id.contains("buttoncontainer") ||
                id.contains("calltoaction") ||
                label.contains("继续") ||
                label.contains("continue") ||
                label.contains("确认") ||
                label.contains("確認") ||
                label.contains("confirm") ||
                label.contains("提交") ||
                label.contains("submit");
    }

    private boolean isExpectedActionContainer(UiNode node, List<String> texts, String step) {
        if (texts.stream().anyMatch(text -> matchesLabel(node, text))) return true;
        String id = normalize(safe(node.getResourceId()));
        String normalizedStep = normalize(step);
        if (normalizedStep.contains("continue")) {
            return id.contains("multistatebutton") || id.contains("calltoaction");
        }
        if (normalizedStep.contains("confirm")) {
            return id.contains("confirm") ||
                    id.contains("submitcontainer") ||
                    id.contains("buttoncontainer") ||
                    id.contains("multistatebutton");
        }
        if (normalizedStep.contains("submit")) {
            return id.contains("submit") ||
                    id.contains("buttoncontainer") ||
                    id.contains("multistatebutton");
        }
        return isFinancialActionContainer(node);
    }

    private List<UiNode> findNodesByLabel(LocalAdbDeviceDriver driver, List<String> queries) {
        try {
            driver.getScreenTree("actionable");
        } catch (Exception ignored) {
        }
        return driver.currentMappedNodes().stream()
                .filter(node -> queries.stream().anyMatch(query -> matchesLabel(node, query)))
                .collect(java.util.stream.Collectors.toList());
    }

    private boolean waitForAnyText(LocalAdbDeviceDriver driver, List<String> texts, long timeoutMs) {
        return waitForScreen(driver, nodes -> screenHasAny(nodes, texts), timeoutMs);
    }

    private boolean waitForSourceSelectionScreen(LocalAdbDeviceDriver driver, String source, long timeoutMs) {
        return waitForScreen(driver, nodes ->
                screenHasAny(nodes, SOURCE_SELECTION_MARKERS) &&
                        screenHasAny(nodes, Arrays.asList(source)),
                timeoutMs
        );
    }

    private boolean waitForDestinationSelectionScreen(LocalAdbDeviceDriver driver, String destination, long timeoutMs) {
        return waitForScreen(driver, nodes ->
                screenHasAny(nodes, DESTINATION_SELECTION_MARKERS) &&
                        screenHasAny(nodes, Arrays.asList(destination)),
                timeoutMs
        );
    }

    private boolean waitForAmountPage(LocalAdbDeviceDriver driver, long timeoutMs) {
        return waitForScreen(driver, nodes -> screenHasAny(nodes, AMOUNT_PAGE_MARKERS), timeoutMs);
    }

    private boolean waitForScreen(LocalAdbDeviceDriver driver, java.util.function.Predicate<List<UiNode>> predicate, long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            try {
                driver.getScreenTree("text");
            } catch (Exception ignored) {
            }
            List<UiNode> nodes = driver.currentMappedNodes();
            if (predicate.test(nodes)) return true;
            sleep(700);
        }
        return false;
    }

    private boolean screenHasAny(List<UiNode> nodes, List<String> queries) {
        for (UiNode node : nodes) {
            for (String query : queries) {
                if (matchesLabel(node, query)) return true;
            }
        }
        return false;
    }

    private boolean matchesLabel(UiNode node, String query) {
        String label = labelOf(node);
        return UiTextMatchUtils.matchesExactOrNormalized(label, query) ||
                UiTextMatchUtils.matchesRelaxed(label, query);
    }

    private String labelOf(UiNode node) {
        return (safe(node.getText()) + " " + safe(node.getContentDescription()) + " " + safe(node.getResourceId())).trim();
    }

    private boolean hasVisibleBounds(UiNode node) {
        Rect bounds = node.getBounds();
        return bounds != null && bounds.width() > 0 && bounds.height() > 0;
    }

    private boolean isBlocked(ToolResult result) {
        String data = result.getData();
        return data != null && data.contains("BLOCKED:");
    }

    private ToolResult blocked(String message, StringBuilder trace) {
        return ToolResult.success("BLOCKED: " + message + " " + trace);
    }

    private String summaryOf(ToolResult result) {
        if (result.isSuccess()) return trimTo(result.getData(), 260);
        return "ERROR: " + trimTo(result.getError(), 260);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String normalize(String value) {
        return safe(value)
                .replaceAll("\\p{Cf}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "");
    }

    private String trimTo(String value, int maxChars) {
        if (value == null) return "";
        if (value.length() <= maxChars) return value;
        return value.substring(0, Math.max(0, maxChars - 2)) + "..";
    }

    private List<String> concat(List<String> first, List<String> second) {
        java.util.ArrayList<String> combined = new java.util.ArrayList<>(first);
        combined.addAll(second);
        return combined;
    }

    private void sleep(long durationMs) {
        try {
            Thread.sleep(durationMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
