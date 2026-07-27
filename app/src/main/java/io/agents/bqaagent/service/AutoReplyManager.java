// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.service;

import io.agents.bqaagent.ClawApplication;
import io.agents.bqaagent.adb.LocalAdbAutomation;
import io.agents.bqaagent.tool.ToolResult;
import io.agents.bqaagent.tool.impl.SendMessageTool;
import io.agents.bqaagent.utils.ContactMatchUtils;
import io.agents.bqaagent.utils.XLog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Monitors incoming messaging notifications and auto-replies through Local ADB.
 */
public class AutoReplyManager {

    public static final class MonitorTarget {
        private final String displayName;
        private final String appName;
        private final String packageName;
        private final String key;
        private final LinkedHashSet<String> normalizedAliases;
        private final LinkedHashSet<String> digitAliases;

        private MonitorTarget(String displayName, String appName, String packageName) {
            this.displayName = displayName;
            this.appName = appName;
            this.packageName = packageName;
            this.key = packageName + "|" + ContactMatchUtils.normalizeText(displayName) + "|" + ContactMatchUtils.digitsOnly(displayName);
            this.normalizedAliases = ContactMatchUtils.buildNormalizedAliases(displayName);
            this.digitAliases = ContactMatchUtils.buildDigitAliases(displayName);
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getAppName() {
            return appName;
        }

        public String getPackageName() {
            return packageName;
        }

        public String getDisplayLabel() {
            return displayName + " on " + appName;
        }

        public String getKey() {
            return key;
        }

        private boolean matches(String incomingPackage, String incomingTitle) {
            if (!packageName.equals(incomingPackage)) return false;
            return ContactMatchUtils.matchesCandidate(incomingTitle, normalizedAliases, digitAliases);
        }

        private boolean matchesRemovalQuery(String query) {
            return ContactMatchUtils.matchesCandidate(query, normalizedAliases, digitAliases) ||
                ContactMatchUtils.matchesCandidate(query, ContactMatchUtils.buildNormalizedAliases(getDisplayLabel()), ContactMatchUtils.buildDigitAliases(getDisplayLabel())) ||
                ContactMatchUtils.matchesCandidate(query, ContactMatchUtils.buildNormalizedAliases(appName + " " + displayName), ContactMatchUtils.buildDigitAliases(appName + " " + displayName));
        }
    }

    private static final String TAG = "AutoReplyManager";
    private static final long DEBOUNCE_MS = 5_000L;
    private static final String REPLY_SYSTEM_PROMPT =
            "You are replying to a chat message on behalf of the user. " +
            "Keep replies SHORT (under 15 words), casual, and friendly. " +
            "Reply in the same language as the incoming message. " +
            "Do NOT use emojis excessively. Sound like a real person texting.";

    private static AutoReplyManager instance;

    private boolean enabled = false;
    private final Map<String, MonitorTarget> monitoredTargets = new LinkedHashMap<>();
    private final Set<String> monitoredApps = new LinkedHashSet<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean replying = new AtomicBoolean(false);
    private final Map<String, Long> lastReplyTime = new HashMap<>();
    private final Set<String> ownSentMessages = new HashSet<>();

    private volatile boolean hasPending = false;
    private volatile String pendingPackage = null;
    private volatile String pendingContact = null;
    private volatile String pendingText = null;

    private AutoReplyManager() {
        monitoredApps.add("com.whatsapp");
        monitoredApps.add("org.telegram.messenger");
        monitoredApps.add("com.google.android.apps.messaging");
        monitoredApps.add("jp.naver.line.android");
        monitoredApps.add("com.tencent.mm");
    }

    public static synchronized AutoReplyManager getInstance() {
        if (instance == null) instance = new AutoReplyManager();
        return instance;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        XLog.i(TAG, "Auto-reply " + (enabled ? "ENABLED" : "DISABLED") +
                " for contacts: " + getMonitoredContacts());
        syncForegroundNotification();
    }

    public boolean isEnabled() { return enabled; }

    public Set<String> getMonitoredContacts() {
        LinkedHashSet<String> labels = new LinkedHashSet<>();
        for (MonitorTarget target : monitoredTargets.values()) {
            labels.add(target.getDisplayLabel());
        }
        return labels;
    }

    public List<MonitorTarget> getMonitoredTargets() {
        return new ArrayList<>(monitoredTargets.values());
    }

    public void stopAll() {
        enabled = false;
        monitoredTargets.clear();
        XLog.i(TAG, "Auto-reply stopped and all contacts cleared");
        syncForegroundNotification();
    }

    public void addContact(String name) {
        addTarget(name, "WhatsApp");
    }

    public void addTarget(String name, String appName) {
        MonitorTarget target = buildTarget(name, appName);
        if (target == null) {
            XLog.w(TAG, "Ignoring empty monitor target");
            return;
        }
        monitoredTargets.put(target.getKey(), target);
        XLog.i(TAG, "Added monitor target: " + target.getDisplayLabel());
        syncForegroundNotification();
    }

    public void removeContact(String name) {
        String query = name != null ? name.trim() : "";
        if (query.isEmpty()) return;

        List<String> toRemove = new ArrayList<>();
        for (Map.Entry<String, MonitorTarget> entry : monitoredTargets.entrySet()) {
            if (entry.getValue().matchesRemovalQuery(query)) {
                toRemove.add(entry.getKey());
            }
        }
        for (String key : toRemove) {
            monitoredTargets.remove(key);
        }
        syncForegroundNotification();
    }

    public void clearContacts() {
        monitoredTargets.clear();
        syncForegroundNotification();
    }

    public void debugSimulateIncomingMessage(String appName, String sender, String message, boolean ensureTargetEnabled) {
        String normalizedApp = normalizeAppName(appName);
        String packageName = resolvePackageName(normalizedApp);
        if (packageName == null) {
            normalizedApp = "WhatsApp";
            packageName = "com.whatsapp";
        }

        if (ensureTargetEnabled) {
            addTarget(sender, normalizedApp);
            setEnabled(true);
        }

        XLog.i(TAG, "Debug-simulating incoming message from " + sender + " on " + normalizedApp + ": '" + message + "'");
        onNotificationReceived(packageName, sender, message);
    }

    public void onNotificationReceived(String packageName, String title, String text) {
        if (!enabled) return;
        if (!monitoredApps.contains(packageName)) return;
        if (title == null || title.isEmpty() || text == null || text.isEmpty()) return;

        MonitorTarget matchedTarget = null;
        for (MonitorTarget target : monitoredTargets.values()) {
            if (target.matches(packageName, title)) {
                matchedTarget = target;
                break;
            }
        }
        if (matchedTarget == null) return;

        if (text.matches("^\\d+ (new )?messages?.*")) {
            XLog.d(TAG, "Skipping summary notification: " + text);
            return;
        }
        if (ownSentMessages.remove(text)) {
            XLog.d(TAG, "Skipping own message: " + text);
            return;
        }

        long now = System.currentTimeMillis();
        Long lastReply = lastReplyTime.get(matchedTarget.getKey());
        if (lastReply != null && (now - lastReply) < DEBOUNCE_MS) {
            XLog.d(TAG, "Debounce: skipping reply to " + matchedTarget.getDisplayLabel());
            return;
        }

        if (!replying.compareAndSet(false, true)) {
            hasPending = true;
            pendingPackage = packageName;
            pendingContact = matchedTarget.getDisplayName();
            pendingText = text;
            XLog.d(TAG, "Already replying, queued pending from " + matchedTarget.getDisplayLabel());
            return;
        }

        MonitorTarget finalTarget = matchedTarget;
        executor.submit(() -> runReplyPipeline(finalTarget, text));
    }

    private void runReplyPipeline(MonitorTarget target, String incomingMessage) {
        try {
            if (!LocalAdbAutomation.awaitReady(12_000L)) {
                XLog.w(TAG, "Skipping auto-reply because Local ADB is not ready");
                return;
            }

            String reply = generateReply(target.getDisplayName(), incomingMessage);
            if (reply == null || reply.trim().isEmpty()) {
                XLog.w(TAG, "LLM did not produce a safe auto-reply");
                return;
            }

            ownSentMessages.add(reply);
            SendMessageTool sendTool = new SendMessageTool();
            Map<String, Object> params = new HashMap<>();
            params.put("contact", target.getDisplayName());
            params.put("message", reply);
            params.put("app", target.getAppName());
            ToolResult result = sendTool.execute(params);

            if (result.isSuccess()) {
                XLog.i(TAG, "Auto-reply sent to " + target.getDisplayLabel() + ": '" + reply + "'");
                lastReplyTime.put(target.getKey(), System.currentTimeMillis());
                ClawNotificationListener.dismissNotifications(target.getPackageName());
            } else {
                ownSentMessages.remove(reply);
                XLog.w(TAG, "Auto-reply send failed: " + result.getError());
            }
        } catch (Exception e) {
            XLog.w(TAG, "Auto-reply pipeline crashed", e);
        } finally {
            replying.set(false);
            if (hasPending && pendingPackage != null && pendingContact != null && pendingText != null) {
                String pkg = pendingPackage;
                String contact = pendingContact;
                String text = pendingText;
                hasPending = false;
                pendingPackage = null;
                pendingContact = null;
                pendingText = null;
                handlePending(pkg, contact, text);
            }
        }
    }

    private void handlePending(String packageName, String contact, String text) {
        for (MonitorTarget target : monitoredTargets.values()) {
            if (target.getPackageName().equals(packageName) && target.getDisplayName().equals(contact)) {
                if (replying.compareAndSet(false, true)) {
                    executor.submit(() -> runReplyPipeline(target, text));
                }
                return;
            }
        }
    }

    private String generateReply(String sender, String incomingMessage) {
        try {
            String prompt = sender + " says: \"" + incomingMessage + "\"\nYour reply:";
            String provider = io.agents.bqaagent.utils.KVUtils.INSTANCE.getLlmProvider();
            String reply;
            if (!"LOCAL".equals(provider)) {
                reply = io.agents.bqaagent.agent.llm.LlmSessionManager.INSTANCE.singleShotCloud(
                        REPLY_SYSTEM_PROMPT, prompt, 0.7
                );
            } else {
                reply = io.agents.bqaagent.agent.llm.LlmSessionManager.INSTANCE.singleShotLocal(
                        REPLY_SYSTEM_PROMPT, prompt, 0.7
                );
            }
            if (reply != null) {
                reply = reply.replaceAll("^[\"']|[\"']$", "").trim();
                if (reply.startsWith("Your reply:")) reply = reply.substring(11).trim();
            }
            if (reply == null || reply.isEmpty() || reply.length() > 200) {
                return null;
            }
            return reply;
        } catch (Exception e) {
            XLog.w(TAG, "generateReply failed", e);
            return null;
        }
    }

    private MonitorTarget buildTarget(String name, String appName) {
        String displayName = name != null ? name.trim() : "";
        if (displayName.isEmpty()) return null;
        String resolvedAppName = normalizeAppName(appName);
        String packageName = resolvePackageName(resolvedAppName);
        if (packageName == null) {
            resolvedAppName = "WhatsApp";
            packageName = "com.whatsapp";
        }
        return new MonitorTarget(displayName, resolvedAppName, packageName);
    }

    private String normalizeAppName(String appName) {
        if (appName == null) return "WhatsApp";
        String lower = appName.trim().toLowerCase(java.util.Locale.ROOT);
        switch (lower) {
            case "telegram":
                return "Telegram";
            case "messages":
            case "google messages":
            case "sms":
                return "Messages";
            case "line":
                return "LINE";
            case "wechat":
            case "we chat":
                return "WeChat";
            default:
                return "WhatsApp";
        }
    }

    private String resolvePackageName(String appName) {
        switch (appName) {
            case "WhatsApp":
                return "com.whatsapp";
            case "Telegram":
                return "org.telegram.messenger";
            case "Messages":
                return "com.google.android.apps.messaging";
            case "LINE":
                return "jp.naver.line.android";
            case "WeChat":
                return "com.tencent.mm";
            default:
                return null;
        }
    }

    private void syncForegroundNotification() {
        try {
            if (enabled && !monitoredTargets.isEmpty()) {
                ForegroundService.Companion.showMonitorStatus(ClawApplication.Companion.getInstance());
                KeepAliveJobService.Companion.schedule(ClawApplication.Companion.getInstance());
            } else {
                KeepAliveJobService.Companion.cancel(ClawApplication.Companion.getInstance());
                ForegroundService.Companion.resetToIdle(ClawApplication.Companion.getInstance());
            }
        } catch (Exception e) {
            XLog.w(TAG, "Failed to sync foreground notification", e);
        }
    }
}
