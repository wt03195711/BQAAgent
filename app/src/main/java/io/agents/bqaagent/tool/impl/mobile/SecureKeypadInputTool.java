// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.tool.impl.mobile;

import android.graphics.Rect;
import android.view.KeyEvent;

import io.agents.bqaagent.adb.LocalAdbAutomation;
import io.agents.bqaagent.adb.LocalAdbDeviceDriver;
import io.agents.bqaagent.adb.UiNode;
import io.agents.bqaagent.tool.BaseTool;
import io.agents.bqaagent.tool.ToolParameter;
import io.agents.bqaagent.tool.ToolResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Enters a numeric PIN/passcode in a focused secure numeric field.
 *
 * It never installs or switches input methods. It clears stale focused input,
 * tries Android numeric key events first, then falls back to visible keypad
 * digits or a standard 3x4 keypad layout when key events do not advance.
 */
public class SecureKeypadInputTool extends BaseTool {
    @Override
    public String getName() {
        return "secure_keypad_input";
    }

    @Override
    public List<String> getValueParamNames() {
        return Collections.singletonList("digits");
    }

    @Override
    public String getDisplayName() {
        return "Secure Keypad Input";
    }

    @Override
    public String getDescriptionEN() {
        return "Enter a numeric PIN/passcode into a secure numeric field. Clears stale input, tries numeric key events, then falls back to visible keypad digits. Does not install/switch input methods.";
    }

    @Override
    public String getDescriptionCN() {
        return getDescriptionEN();
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Arrays.asList(
                new ToolParameter("digits", "string", "Numeric digits to enter, e.g. 135791", true),
                new ToolParameter("layout", "string", "Optional: auto or standard_3x4. Default auto.", false),
                new ToolParameter("submit_after_entry", "boolean", "Optional: tap a visible submit/login action after entering digits. Default false.", false),
                new ToolParameter("post_wait_ms", "integer", "Optional: max wait after entry. Default 9000.", false)
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        LocalAdbDeviceDriver driver = requireDeviceDriver();
        if (driver == null) return ToolResult.error("Local ADB is not ready");

        String digits = requireString(params, "digits").trim();
        if (!digits.matches("\\d{1,12}")) {
            return ToolResult.error("digits must contain 1-12 numeric characters");
        }
        String layout = optionalString(params, "layout", "auto").trim().toLowerCase(Locale.ROOT);
        boolean submitAfterEntry = optionalBoolean(params, "submit_after_entry", false);
        int postWaitMs = Math.max(1500, Math.min(optionalInt(params, "post_wait_ms", 4000), 15000));

        focusSecureField(driver);
        clearFocusedSecureInput(driver);

        // focusSecureField 内部已调用 detectCustomKeypad 并缓存到节点扫描中，
        // 这里复用同一份 currentMappedNodes 再次确认（内存操作，无 dump 成本）
        boolean skipKeyEvents = "standard_3x4".equals(layout) || detectCustomKeypad(driver);

        if (!skipKeyEvents) {
            if (!typeDigitsWithKeyEvents(driver, digits)) {
                return ToolResult.error("Failed to enter secure keypad digits with Android numeric key events.");
            }
            SecureLoginState keyEventState = waitForPostPinState(driver, Math.min(postWaitMs, 3000));
            if (keyEventState == SecureLoginState.OTHER || keyEventState == SecureLoginState.LOADING) {
                return ToolResult.success("Entered " + digits.length() +
                        " secure keypad digit(s) using key_events. Post-entry state=" + keyEventState + ".");
            }
            if (submitAfterEntry && keyEventState == SecureLoginState.LOGIN_BUTTON && tapPostPinLoginActionIfVisible(driver)) {
                SecureLoginState submittedState = waitForPostPinState(driver, postWaitMs);
                if (submittedState == SecureLoginState.OTHER || submittedState == SecureLoginState.LOADING) {
                    return ToolResult.success("Entered " + digits.length() +
                            " secure keypad digit(s) using key_events and tapped the enabled login action. Post-entry state=" + submittedState + ".");
                }
            }
            clearFocusedSecureInput(driver);
            driver.getScreenTree("actionable");
        }

        Map<Character, int[]> inferredGrid = "standard_3x4".equals(layout)
                ? new HashMap<>()
                : inferClickableGridFromCache(driver);

        Map<Character, int[]> digitCoords = "standard_3x4".equals(layout)
                ? new HashMap<>()
                : batchResolveDigitCoordinates(driver, digits);

        // 构建 tap 列表
        List<int[]> tapSequence = new ArrayList<>();
        int entered = 0;
        String method = "visible_nodes";
        for (char digit : digits.toCharArray()) {
            int[] xy;
            if ("standard_3x4".equals(layout)) {
                xy = null;
            } else {
                xy = digitCoords.get(digit);
                if (xy == null) {
                    xy = inferredGrid.get(digit);
                    method = "inferred_grid";
                }
            }
            if (xy == null) {
                xy = standardCoordinates(digit);
                method = "standard_3x4";
            }
            if (xy == null) return ToolResult.error("No coordinate available for secure keypad digit #" + (entered + 1));

            String boundsError = validateCoordinates(xy[0], xy[1]);
            if (boundsError != null) return ToolResult.error(boundsError);
            tapSequence.add(xy);
            entered++;
        }

        // 优化：将多次 tap 合并为一条 shell 命令，减少 ADB 往返
        if (!batchPerformTaps(driver, tapSequence)) {
            return ToolResult.error("Failed to tap secure keypad digits with " + method);
        }
        driver.invalidateScreenCache();

        boolean tappedLoginAction = submitAfterEntry && tapPostPinLoginActionIfVisible(driver);
        SecureLoginState state = waitForPostPinState(driver, postWaitMs);
        if (state == SecureLoginState.PIN_ENTRY || state == SecureLoginState.LOGIN_BUTTON) {
            return ToolResult.error("Secure PIN input did not leave the login flow. Current state=" + state +
                    ". The keypad may not accept automated input, the PIN may be rejected, or the app may have returned to login.");
        }

        return ToolResult.success("Entered " + entered + " secure keypad digit(s) using " + method +
                (tappedLoginAction ? " and tapped the enabled login action" : "") +
                ". Post-entry state=" + state + ".");
    }

    private void clearFocusedSecureInput(LocalAdbDeviceDriver driver) {
        // 只有当焦点是可编辑的 EditText 且当前文本非空时才清除；
        // 触屏键盘场景（无 EditText 焦点）下 clearFocusedText 完全无效且浪费 1.5s。
        if (!hasEditableFocusedNode(driver)) return;
        driver.clearFocusedText(12);
        sleep(150);
    }

    private boolean hasEditableFocusedNode(LocalAdbDeviceDriver driver) {
        for (UiNode node : driver.currentMappedNodes()) {
            if (!node.getFocused()) continue;
            if (node.isEditable()) {
                String text = node.getText();
                if (text != null && !text.isEmpty()) return true;
            }
        }
        return false;
    }

    private boolean typeDigitsWithKeyEvents(LocalAdbDeviceDriver driver, String digits) {
        for (int i = 0; i < digits.length(); i++) {
            int keyCode = keyCodeForDigit(digits.charAt(i));
            if (keyCode < 0 || !driver.sendKeyEvent(keyCode)) return false;
            sleep(110);
        }
        driver.invalidateScreenCache();
        return true;
    }

    private int keyCodeForDigit(char digit) {
        if (digit < '0' || digit > '9') return -1;
        return KeyEvent.KEYCODE_0 + (digit - '0');
    }

    private void focusSecureField(LocalAdbDeviceDriver driver) {
        try { driver.getScreenTree("actionable"); } catch (Exception ignored) {}
        int[] size = getScreenSize();

        for (UiNode node : driver.currentMappedNodes()) {
            if (!node.getFocused()) continue;
            String focusedLabel = normalized(node.getText() + " " + node.getContentDescription() + " " + node.getResourceId());
            if (focusedLabel.contains("pin")) return;
        }

        // 自绘键盘无需 focus tap：若屏幕下半区已有 ≥3 个可点击数字节点，直接返回
        if (detectCustomKeypad(driver)) return;

        UiNode best = null;
        int bestScore = 0;
        for (UiNode node : driver.currentMappedNodes()) {
            if (!node.getEnabled()) continue;
            Rect bounds = node.getBounds();
            if (bounds == null || bounds.width() <= 0 || bounds.height() <= 0) continue;
            if (bounds.width() > size[0] * 0.9f || bounds.height() > size[1] * 0.35f) continue;

            String text = normalized(node.getText());
            String description = normalized(node.getContentDescription());
            String label = text + " " + description + " " + normalized(node.getResourceId());
            if (!label.contains("pin")) continue;
            if (label.contains("forgot") || label.contains("help") || label.contains("?") ||
                    label.contains("帮助") || label.contains("幫助") ||
                    label.contains("忘记") || label.contains("忘記") || label.contains("忘了") ||
                    label.contains("问题") || label.contains("問題") || label.contains("客服")) continue;

            int score = 0;
            if (node.isEditable()) score += 100;
            if (text.contains("pin") || description.contains("pin")) score += 40;
            if (label.contains("input") || label.contains("enter") ||
                    label.contains("请输入") || label.contains("請輸入") || label.contains("输入")) score += 30;
            if (node.getFocused()) score += 30;
            if (node.getClickable()) score += 10;
            if (node.getCenterY() < size[1] * 0.45f) score += 5;
            if (score > bestScore) {
                best = node;
                bestScore = score;
            }
        }
        if (best != null && bestScore >= 40) {
            driver.performTap(best.getCenterX(), best.getCenterY());
            sleep(350);
        }
    }

    private int[] findDigitCoordinates(LocalAdbDeviceDriver driver, char digit) {
        List<UiNode> nodes = driver.findNodesByText(String.valueOf(digit), true);
        if (nodes.isEmpty()) return null;
        int[] size = getScreenSize();
        UiNode best = nodes.stream()
                .filter(node -> isKeypadCandidate(node, size))
                .max(Comparator.comparingInt(node -> node.getClickable() ? 2 : 1))
                .orElse(null);
        if (best == null) return null;
        return new int[]{best.getCenterX(), best.getCenterY()};
    }

    private boolean isKeypadCandidate(UiNode node, int[] size) {
        Rect bounds = node.getBounds();
        if (!node.getEnabled() || bounds == null || bounds.width() <= 0 || bounds.height() <= 0) return false;
        if (node.getCenterY() < size[1] * 0.35f) return false;
        if (bounds.width() > size[0] * 0.55f || bounds.height() > size[1] * 0.20f) return false;
        return true;
    }

    private int[] findDigitCoordinatesFromCache(LocalAdbDeviceDriver driver, char digit) {
        List<UiNode> nodes = driver.findNodesByText(String.valueOf(digit), false);
        if (nodes.isEmpty()) return null;
        int[] size = getScreenSize();
        UiNode best = nodes.stream()
                .filter(node -> isKeypadCandidate(node, size))
                .max(Comparator.comparingInt(node -> node.getClickable() ? 2 : 1))
                .orElse(null);
        if (best == null) return null;
        return new int[]{best.getCenterX(), best.getCenterY()};
    }

    /**
     * 优化：批量解析所有数字坐标，复用同一份缓存，避免逐字 refresh dump。
     */
    private Map<Character, int[]> batchResolveDigitCoordinates(LocalAdbDeviceDriver driver, String digits) {
        Map<Character, int[]> result = new HashMap<>();
        int[] size = getScreenSize();
        List<UiNode> nodes = driver.currentMappedNodes();
        if (nodes.isEmpty()) {
            try { driver.getScreenTree("actionable"); } catch (Exception ignored) {}
            nodes = driver.currentMappedNodes();
        }
        for (char digit : digits.toCharArray()) {
            if (result.containsKey(digit)) continue;
            UiNode best = null;
            String query = String.valueOf(digit);
            for (UiNode node : nodes) {
                if (!io.agents.bqaagent.utils.UiTextMatchUtils.matchesExactOrNormalized(node.getText(), query)
                        && !io.agents.bqaagent.utils.UiTextMatchUtils.matchesExactOrNormalized(node.getContentDescription(), query)) continue;
                if (!isKeypadCandidate(node, size)) continue;
                int score = node.getClickable() ? 2 : 1;
                if (best == null || score > (best.getClickable() ? 2 : 1)) best = node;
            }
            if (best != null) {
                result.put(digit, new int[]{best.getCenterX(), best.getCenterY()});
            }
        }
        if (result.size() < countDistinctDigits(digits)) {
            for (char digit : digits.toCharArray()) {
                if (result.containsKey(digit)) continue;
                List<UiNode> fallback = driver.findNodesByText(String.valueOf(digit), false);
                if (!fallback.isEmpty()) {
                    int[] size2 = getScreenSize();
                    UiNode best = fallback.stream()
                            .filter(node -> isKeypadCandidate(node, size2))
                            .max(Comparator.comparingInt(node -> node.getClickable() ? 2 : 1))
                            .orElse(null);
                    if (best != null) {
                        result.put(digit, new int[]{best.getCenterX(), best.getCenterY()});
                    }
                }
            }
        }
        return result;
    }

    private int countDistinctDigits(String digits) {
        return (int) digits.chars().distinct().count();
    }

    private Map<Character, int[]> inferClickableGridFromCache(LocalAdbDeviceDriver driver) {
        Map<Character, int[]> result = new HashMap<>();

        int[] size = getScreenSize();
        List<UiNode> candidates = new ArrayList<>();
        for (UiNode node : driver.currentMappedNodes()) {
            Rect bounds = node.getBounds();
            if (!node.getEnabled() || bounds == null || bounds.width() <= 0 || bounds.height() <= 0) continue;
            if (node.getCenterY() < size[1] * 0.42f) continue;
            if (bounds.width() < 40 || bounds.height() < 40) continue;
            if (bounds.width() > size[0] * 0.45f || bounds.height() > size[1] * 0.18f) continue;
            if (node.getClickable() || node.getClassName().contains("Button")) candidates.add(node);
        }
        if (candidates.size() < 10) return result;

        candidates.sort(Comparator.comparingInt(UiNode::getCenterY).thenComparingInt(UiNode::getCenterX));
        List<List<UiNode>> rows = new ArrayList<>();
        for (UiNode node : candidates) {
            List<UiNode> row = findNearbyRow(rows, node);
            if (row == null) {
                row = new ArrayList<>();
                rows.add(row);
            }
            row.add(node);
        }
        rows.removeIf(row -> row.isEmpty());
        rows.sort(Comparator.comparingInt(this::averageY));
        if (rows.size() < 4) return result;

        putRow(result, rows.get(0), new char[]{'1', '2', '3'});
        putRow(result, rows.get(1), new char[]{'4', '5', '6'});
        putRow(result, rows.get(2), new char[]{'7', '8', '9'});
        List<UiNode> lastRow = new ArrayList<>(rows.get(3));
        lastRow.sort(Comparator.comparingInt(UiNode::getCenterX));
        UiNode zero = lastRow.size() >= 3 ? lastRow.get(1) : lastRow.get(lastRow.size() / 2);
        result.put('0', new int[]{zero.getCenterX(), zero.getCenterY()});
        return result;
    }

    /**
     * 判断当前屏幕是否为自绘数字键盘（触屏专用），若是则 KeyEvent 无意义。
     * 判据：屏幕下半区域存在 >=3 个 clickable 且标签为单个数字的节点。
     */
    private boolean detectCustomKeypad(LocalAdbDeviceDriver driver) {
        int[] size = getScreenSize();
        int digitNodes = 0;
        for (UiNode node : driver.currentMappedNodes()) {
            if (!node.getClickable() || !node.getEnabled()) continue;
            Rect bounds = node.getBounds();
            if (bounds == null || bounds.width() <= 0 || bounds.height() <= 0) continue;
            if (node.getCenterY() < size[1] * 0.42f) continue;
            String label = node.getText();
            if (label.length() != 1 || !Character.isDigit(label.charAt(0))) {
                String desc = node.getContentDescription();
                if (desc.length() != 1 || !Character.isDigit(desc.charAt(0))) continue;
            }
            digitNodes++;
            if (digitNodes >= 3) return true;
        }
        return false;
    }

    /**
     * 将多个 tap 操作合并为一条 shell 命令执行，减少 ADB 往返开销。
     * 单次往返 ~0.3s vs N 次往返 ~N×0.3s。
     * 每个 tap 之间仍保留 150ms 间隔给 App 注册。
     */
    private boolean batchPerformTaps(LocalAdbDeviceDriver driver, List<int[]> taps) {
        if (taps.isEmpty()) return true;
        if (taps.size() == 1) {
            return driver.performTap(taps.get(0)[0], taps.get(0)[1]);
        }
        StringBuilder cmd = new StringBuilder();
        for (int i = 0; i < taps.size(); i++) {
            if (i > 0) cmd.append(" && sleep 0.15 && ");
            cmd.append("input tap ").append(taps.get(i)[0]).append(" ").append(taps.get(i)[1]);
        }
        return LocalAdbAutomation.exec(cmd.toString(), 10_000L).isSuccess();
    }

    private boolean tapPostPinLoginActionIfVisible(LocalAdbDeviceDriver driver) {
        sleep(200);
        try { driver.getScreenTree("actionable"); } catch (Exception ignored) {}
        int[] size = getScreenSize();
        UiNode best = null;
        int bestScore = Integer.MIN_VALUE;
        for (UiNode node : driver.currentMappedNodes()) {
            Rect bounds = node.getBounds();
            if (!node.getEnabled() || bounds == null || bounds.width() <= 0 || bounds.height() <= 0) continue;
            String label = normalized(node.getText() + " " + node.getContentDescription() + " " + node.getResourceId());
            if (label.contains("forgot") ||
                    label.contains("help") ||
                    label.contains("cancel") ||
                    label.contains("password") ||
                    label.contains("passcode") ||
                    label.contains("密码") ||
                    label.contains("密碼")) continue;
            boolean looksLikeLogin = label.contains("log on") ||
                    label.contains("logon") ||
                    label.contains("login") ||
                    label.contains("sign in") ||
                    label.contains("signin") ||
                    label.contains("continue") ||
                    label.contains("登录") ||
                    label.contains("登入") ||
                    label.contains("继续") ||
                    label.contains("繼續");
            if (!looksLikeLogin) continue;
            int score = node.getClickable() ? 100 : 0;
            score += node.getCenterY() > size[1] * 0.45f ? 30 : 0;
            score += Math.max(0, size[1] - Math.abs(size[1] / 2 - node.getCenterY())) / 100;
            if (score > bestScore) {
                best = node;
                bestScore = score;
            }
        }
        if (best == null) return false;
        boolean tapped = driver.performTap(best.getCenterX(), best.getCenterY());
        if (tapped) sleep(300);
        return tapped;
    }

    private SecureLoginState waitForPostPinState(LocalAdbDeviceDriver driver, long maxWaitMs) {
        long deadline = System.currentTimeMillis() + maxWaitMs;
        SecureLoginState last = SecureLoginState.OTHER;
        long interval = 400;
        while (System.currentTimeMillis() <= deadline) {
            // 状态轮询必须读到最新屏幕，主动作废 2s 缓存
            driver.invalidateScreenCache();
            last = detectSecureLoginState(driver);
            // OTHER / LOGIN_BUTTON / LOADING 都视为"已推进"，立即返回
            if (last == SecureLoginState.OTHER
                    || last == SecureLoginState.LOGIN_BUTTON
                    || last == SecureLoginState.LOADING) {
                return last;
            }
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) break;
            sleep(Math.min(interval, remaining));
            interval = Math.min(interval + 300, 1500);
        }
        return last;
    }

    private SecureLoginState detectSecureLoginState(LocalAdbDeviceDriver driver) {
        String tree;
        try {
            tree = driver.getScreenTree("compact");
        } catch (Exception e) {
            return SecureLoginState.OTHER;
        }
        String lower = normalized(tree);
        String compact = lower.replaceAll("\\s+", "");
        boolean hasPin = compact.contains("pin") || compact.contains("数码pin") || compact.contains("數碼pin");
        boolean loading = compact.contains("loading") ||
                compact.contains("progressbar") ||
                compact.contains("pleasewait") ||
                compact.contains("请稍候") ||
                compact.contains("請稍候") ||
                compact.contains("加载") ||
                compact.contains("載入");
        if (loading) return SecureLoginState.LOADING;
        if (!hasPin) return SecureLoginState.OTHER;
        if (compact.contains("logonwithpin") || compact.contains("loginwithpin")) return SecureLoginState.LOGIN_BUTTON;
        if (compact.contains("enteryour6-digitpin") ||
                compact.contains("enter6-digitpin") ||
                compact.contains("6-digitpin") ||
                compact.contains("logontohsbcmobilebanking") ||
                compact.contains("forgotyourpin") ||
                compact.contains("请输入") ||
                compact.contains("請輸入") ||
                compact.contains("输入") ||
                compact.contains("數碼pin") ||
                compact.contains("数码pin")) {
            return SecureLoginState.PIN_ENTRY;
        }
        return SecureLoginState.OTHER;
    }

    private String normalized(String raw) {
        return raw == null ? "" : raw.replaceAll("\\p{Cf}", "").toLowerCase();
    }

    private void sleep(long durationMs) {
        try {
            Thread.sleep(durationMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private List<UiNode> findNearbyRow(List<List<UiNode>> rows, UiNode node) {
        for (List<UiNode> row : rows) {
            if (Math.abs(averageY(row) - node.getCenterY()) <= 80) return row;
        }
        return null;
    }

    private int averageY(List<UiNode> row) {
        int sum = 0;
        for (UiNode node : row) sum += node.getCenterY();
        return sum / Math.max(row.size(), 1);
    }

    private void putRow(Map<Character, int[]> result, List<UiNode> row, char[] digits) {
        List<UiNode> ordered = new ArrayList<>(row);
        ordered.sort(Comparator.comparingInt(UiNode::getCenterX));
        int count = Math.min(3, ordered.size());
        for (int i = 0; i < count; i++) {
            UiNode node = ordered.get(i);
            result.put(digits[i], new int[]{node.getCenterX(), node.getCenterY()});
        }
    }

    private int[] standardCoordinates(char digit) {
        int[] size = getScreenSize();
        int width = size[0];
        int height = size[1];
        int[] xs = new int[]{
                (int) (width * 0.25f),
                (int) (width * 0.50f),
                (int) (width * 0.75f)
        };
        int[] ys = new int[]{
                (int) (height * 0.58f),
                (int) (height * 0.67f),
                (int) (height * 0.76f),
                (int) (height * 0.85f)
        };
        switch (digit) {
            case '1': return new int[]{xs[0], ys[0]};
            case '2': return new int[]{xs[1], ys[0]};
            case '3': return new int[]{xs[2], ys[0]};
            case '4': return new int[]{xs[0], ys[1]};
            case '5': return new int[]{xs[1], ys[1]};
            case '6': return new int[]{xs[2], ys[1]};
            case '7': return new int[]{xs[0], ys[2]};
            case '8': return new int[]{xs[1], ys[2]};
            case '9': return new int[]{xs[2], ys[2]};
            case '0': return new int[]{xs[1], ys[3]};
            default: return null;
        }
    }

    private enum SecureLoginState {
        PIN_ENTRY,
        LOGIN_BUTTON,
        LOADING,
        OTHER
    }
}
