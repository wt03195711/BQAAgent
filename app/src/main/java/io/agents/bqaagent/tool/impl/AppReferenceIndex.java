// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.tool.impl;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import io.agents.bqaagent.ClawApplication;
import io.agents.bqaagent.utils.XLog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Process-local launchable app reference index.
 * <p>
 * The agent often needs to map a human app name ("HSBC Singapore Cert") to a
 * package name. Keep that reference list in memory so open_app, send_message,
 * and task guards do not repeatedly scan PackageManager or ask the LLM to call
 * get_installed_apps first.
 */
public final class AppReferenceIndex {
    private static final String TAG = "AppReferenceIndex";
    private static final long CACHE_TTL_MS = 10 * 60 * 1000L;
    private static final Object LOCK = new Object();

    private static volatile List<AppReference> cachedApps = Collections.emptyList();
    private static volatile long loadedAtMs = 0L;

    private static final Map<String, String> COMMON_PACKAGES = buildCommonPackages();
    /**
     * Colloquial name (incl. Chinese) -> canonical search keyword. Used only to
     * normalize the query; the final package always comes from the installed index
     * or an install-verified COMMON_PACKAGES entry.
     */
    private static final Map<String, String> APP_ALIASES = buildAppAliases();
    /**
     * Tokens ignored during segment matching (package-name noise and leading verbs).
     */
    private static final List<String> GENERIC_TOKENS = Arrays.asList(
            "com", "org", "net", "io", "app", "apps", "apk", "android", "mobile",
            "www", "http", "https", "open", "launch", "start", "the",
            "打开", "启动", "帮我"
    );

    private AppReferenceIndex() {
    }

    public static final class AppReference {
        public final String appName;
        public final String packageName;

        AppReference(String appName, String packageName) {
            this.appName = appName;
            this.packageName = packageName;
        }

        public String toDisplayLine() {
            return appName + " | " + packageName;
        }
    }

    /**
     * Staged resolution result.
     * - resolved: packageName != null, safe to launch.
     * - ambiguous: multiple installed candidates, caller must NOT launch, ask the LLM to pick.
     * - unresolved: no candidates, caller should report and suggest get_installed_apps.
     */
    public static final class ResolveResult {
        public final String packageName;
        public final List<AppReference> candidates;
        public final boolean ambiguous;

        private ResolveResult(String packageName, List<AppReference> candidates, boolean ambiguous) {
            this.packageName = packageName;
            this.candidates = candidates;
            this.ambiguous = ambiguous;
        }

        public boolean isResolved() {
            return packageName != null;
        }

        static ResolveResult resolved(String packageName) {
            return new ResolveResult(packageName, Collections.<AppReference>emptyList(), false);
        }

        static ResolveResult ambiguous(List<AppReference> candidates) {
            return new ResolveResult(null, candidates, true);
        }

        static ResolveResult unresolved(List<AppReference> suggestions) {
            return new ResolveResult(null, suggestions, false);
        }
    }

    public static void warmUp() {
        ensureLoaded(false);
    }

    public static void refresh() {
        ensureLoaded(true);
    }

    public static List<AppReference> listLaunchableApps(String keyword) {
        String query = normalizeKeyword(keyword);
        List<AppReference> apps = ensureLoaded(false);
        if (query.isEmpty()) {
            return apps;
        }

        List<AppReference> filtered = new ArrayList<>();
        for (AppReference app : apps) {
            String label = normalizeKeyword(app.appName);
            String pkg = normalizeKeyword(app.packageName);
            if (label.contains(query) || pkg.contains(query)) {
                filtered.add(app);
            }
        }
        return filtered;
    }

    /**
     * Staged resolver. Tiers allowed to auto-launch: (A) exact label / spaceless label /
     * package name match, (B) alias-normalized exact match, (C) install-verified
     * COMMON_PACKAGES entry. Anything less confident becomes a candidate list
     * (ambiguous) for the LLM/user to pick — fuzzy matches never auto-launch.
     * A total miss triggers one index refresh to cover freshly installed apps.
     */
    public static ResolveResult resolveApp(String rawInput) {
        String input = sanitizeAppName(rawInput);
        if (input.isEmpty()) return ResolveResult.unresolved(Collections.<AppReference>emptyList());

        ResolveResult result = resolveAgainst(input, ensureLoaded(false));
        if (!result.isResolved() && result.candidates.isEmpty()) {
            // Nothing matched at all: the cache may be stale (freshly installed app), rescan once.
            refresh();
            result = resolveAgainst(input, cachedApps);
        }
        return result;
    }

    private static ResolveResult resolveAgainst(String input, List<AppReference> apps) {
        String query = normalizeKeyword(input);
        if (query.isEmpty()) return ResolveResult.unresolved(Collections.<AppReference>emptyList());
        // Alias-normalize the query AND (inside matchers) the app labels, so a Chinese
        // query can exact-match a Chinese label via a shared canonical form ("hsbc ...").
        String queryAlias = applyAliases(query);

        // Tier A: exact match on raw OR alias-normalized forms of BOTH sides -> launch.
        AppReference exact = findExactMatch(query, queryAlias, apps);
        if (exact != null) {
            XLog.i(TAG, "Resolved by exact match: '" + input + "' -> '" + exact.packageName + "'");
            return ResolveResult.resolved(exact.packageName);
        }

        // Tier B: COMMON_PACKAGES, only when install-verified.
        String common = COMMON_PACKAGES.get(query);
        if (common == null && !queryAlias.equals(query)) common = COMMON_PACKAGES.get(queryAlias);
        if (common == null) {
            for (String token : queryAlias.split(" ")) {
                common = COMMON_PACKAGES.get(token);
                if (common != null) break;
            }
        }
        if (common != null && isInstalled(common, apps)) {
            XLog.i(TAG, "Resolved via verified common package: " + common);
            return ResolveResult.resolved(common);
        }

        // Tier C: partial matches are CANDIDATES ONLY — never auto-launch a fuzzy match,
        // so a brand-level token (e.g. "hsbc") can never silently open the wrong regional app.
        List<AppReference> candidates = scoreCandidates(query, queryAlias, apps);
        if (candidates.isEmpty()) {
            candidates = segmentCandidates(query, queryAlias, apps);
        }
        if (candidates.isEmpty()) {
            return ResolveResult.unresolved(Collections.<AppReference>emptyList());
        }
        if (candidates.size() > 5) {
            candidates = new ArrayList<>(candidates.subList(0, 5));
        }
        XLog.w(TAG, "No exact match for '" + input + "': " + candidates.size() + " partial candidates");
        return ResolveResult.ambiguous(candidates);

    }

    /**
     * Kept for existing callers (e.g. SendMessageTool via OpenAppTool.resolveAppNameStatic).
     */
    public static String resolvePackageName(String appName) {
        return resolveApp(appName).packageName;
    }


    private static List<AppReference> ensureLoaded(boolean forceRefresh) {
        long now = System.currentTimeMillis();
        List<AppReference> current = cachedApps;
        if (!forceRefresh && !current.isEmpty() && now - loadedAtMs < CACHE_TTL_MS) {
            return current;
        }

        synchronized (LOCK) {
            now = System.currentTimeMillis();
            current = cachedApps;
            if (!forceRefresh && !current.isEmpty() && now - loadedAtMs < CACHE_TTL_MS) {
                return current;
            }

            List<AppReference> loaded = loadLaunchableApps();
            if (!loaded.isEmpty() || current.isEmpty()) {
                cachedApps = loaded;
            }
            loadedAtMs = now;
            XLog.i(TAG, "Loaded launchable app reference index: " + cachedApps.size() + " apps");
            return cachedApps;
        }
    }

    private static List<AppReference> loadLaunchableApps() {
        try {
            PackageManager pm = ClawApplication.Companion.getInstance().getPackageManager();
            Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
            mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);

            List<ResolveInfo> resolveInfos = pm.queryIntentActivities(mainIntent, 0);
            if (resolveInfos == null || resolveInfos.isEmpty()) {
                return Collections.emptyList();
            }

            Map<String, AppReference> byPackage = new LinkedHashMap<>();
            for (ResolveInfo info : resolveInfos) {
                if (info.activityInfo == null || info.activityInfo.packageName == null) continue;
                String packageName = info.activityInfo.packageName;
                CharSequence label = info.loadLabel(pm);
                String appName = label != null ? label.toString().trim() : "";
                if (appName.isEmpty()) appName = packageName;
                byPackage.put(packageName, new AppReference(appName, packageName));
            }

            List<AppReference> apps = new ArrayList<>(byPackage.values());
            apps.sort(Comparator.comparing(a -> a.appName.toLowerCase(Locale.ROOT)));
            return Collections.unmodifiableList(apps);
        } catch (Exception e) {
            XLog.w(TAG, "Failed to load launchable app reference index", e);
            return Collections.emptyList();
        }
    }

    /**
     * Exact match only: label / spaceless label / package equals the query, on EITHER
     * raw or alias-normalized forms of both sides. The only tier allowed to auto-launch.
     */
    private static AppReference findExactMatch(String query, String queryAlias, List<AppReference> apps) {
        String queryNoSpace = query.replace(" ", "");
        String queryAliasNoSpace = queryAlias.replace(" ", "");
        for (AppReference app : apps) {
            String label = normalizeKeyword(app.appName);
            String labelNoSpace = label.replace(" ", "");
            String pkg = normalizeKeyword(app.packageName);
            if (label.equals(query) || labelNoSpace.equals(queryNoSpace) || pkg.equals(query)) {
                return app;
            }
            String labelAlias = applyAliases(label);
            String labelAliasNoSpace = labelAlias.replace(" ", "");
            if (labelAlias.equals(queryAlias) || labelAliasNoSpace.equals(queryAliasNoSpace)) {
                return app;
            }
        }
        return null;
    }

    /** Ranked partial matches (substring tiers); candidates only, never auto-launched. */
    private static List<AppReference> scoreCandidates(String query, String queryAlias, List<AppReference> apps) {
        String queryNoSpace = query.replace(" ", "");
        String queryAliasNoSpace = queryAlias.replace(" ", "");
        if (query.isEmpty()) return Collections.emptyList();

        Map<AppReference, Integer> scored = new LinkedHashMap<>();
        for (AppReference app : apps) {
            String label = normalizeKeyword(app.appName);
            String labelNoSpace = label.replace(" ", "");
            String labelAlias = applyAliases(label);
            String labelAliasNoSpace = labelAlias.replace(" ", "");
            String pkg = normalizeKeyword(app.packageName);
            int value = Math.max(
                    score(query, queryNoSpace, label, labelNoSpace, pkg),
                    score(queryAlias, queryAliasNoSpace, labelAlias, labelAliasNoSpace, pkg));
            if (value >= 100) scored.put(app, value);
        }
        return topBand(scored);
    }

    private static List<AppReference> topBand(Map<AppReference, Integer> scored) {
        if (scored.isEmpty()) return Collections.emptyList();
        List<Map.Entry<AppReference, Integer>> sorted = new ArrayList<>(scored.entrySet());
        Collections.sort(sorted, (a, b) -> b.getValue() - a.getValue());
        int top = sorted.get(0).getValue();
        List<AppReference> result = new ArrayList<>();
        for (Map.Entry<AppReference, Integer> entry : sorted) {
            if (entry.getValue() < top - 30) break;
            result.add(entry.getKey());
            if (result.size() >= 5) break;
        }
        return result;
    }

    private static int score(String query, String queryNoSpace, String label, String labelNoSpace, String pkg) {
        if (label.equals(query)) return 1000 + query.length();
        if (labelNoSpace.equals(queryNoSpace)) return 950 + queryNoSpace.length();
        if (pkg.equals(query)) return 900 + query.length();

        int score = 0;
        if (query.length() >= 3 && label.contains(query)) {
            score = Math.max(score, 500 + query.length());
        }
        if (queryNoSpace.length() >= 3 && labelNoSpace.contains(queryNoSpace)) {
            score = Math.max(score, 480 + queryNoSpace.length());
        }
        if (query.length() >= 3 && pkg.contains(query)) {
            score = Math.max(score, 300 + query.length());
        }
        if (queryNoSpace.length() >= 3 && pkg.contains(queryNoSpace)) {
            score = Math.max(score, 280 + queryNoSpace.length());
        }

        String[] segments = pkg.split("\\.");
        if (segments.length > 0 && segments[segments.length - 1].equals(query)) {
            score = Math.max(score, 700 + query.length());
        }
        return score;
    }

    /**
     * Replace any known alias key contained in the query with its canonical keyword.
     * Example: "汇丰新加坡" -> "hsbc 新加坡". Operates on containment so compound
     * Chinese phrases without spaces are still handled.
     */
    private static String applyAliases(String normalizedQuery) {
        String result = normalizedQuery;
        for (Map.Entry<String, String> entry : APP_ALIASES.entrySet()) {
            String key = entry.getKey();
            if (key.length() >= 2 && result.contains(key)) {
                result = result.replace(key, " " + entry.getValue() + " ");
            }
        }
        return result.replaceAll("\\s+", " ").trim();
    }

    /**
     * Split a normalized query into usable tokens: ASCII tokens >= 3 chars, CJK tokens >= 2 chars.
     */
    private static List<String> extractTokens(String normalized) {
        List<String> tokens = new ArrayList<>();
        for (String part : normalized.split("[\\s.]+")) {
            if (part.isEmpty() || GENERIC_TOKENS.contains(part)) continue;
            boolean asciiOnly = true;
            for (int i = 0; i < part.length(); i++) {
                if (part.charAt(i) >= 128) {
                    asciiOnly = false;
                    break;
                }
            }
            int minLen = asciiOnly ? 3 : 2;
            if (part.length() >= minLen) tokens.add(part);
        }
        return tokens;
    }

    private static List<AppReference> segmentCandidates(String query, String aliased, List<AppReference> apps) {
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        tokens.addAll(extractTokens(query));
        if (aliased != null && !aliased.equals(query)) {
            tokens.addAll(extractTokens(aliased));
        }
        if (tokens.isEmpty()) return Collections.emptyList();

        Map<AppReference, Integer> scored = new LinkedHashMap<>();
        for (AppReference app : apps) {
            // Alias-normalize the label too, so "hsbc" also matches Chinese labels like "汇丰...".
            String label = applyAliases(normalizeKeyword(app.appName));
            String labelNoSpace = label.replace(" ", "");
            String pkg = normalizeKeyword(app.packageName);
            int total = 0;
            for (String token : tokens) {
                if (label.contains(token)) total += 200 + token.length();
                else if (labelNoSpace.contains(token)) total += 180 + token.length();
                else if (pkg.contains(token)) total += 100 + token.length();
            }
            if (total > 0) scored.put(app, total);
        }
        return topBand(scored);
    }

    private static boolean isInstalled(String packageName, List<AppReference> apps) {
        String target = normalizeKeyword(packageName);
        for (AppReference app : apps) {
            if (normalizeKeyword(app.packageName).equals(target)) return true;
        }
        return false;
    }

    private static String sanitizeAppName(String value) {
        if (value == null) return "";
        return value.trim()
                .replaceAll("(?i)\\s+(app|application)$", "")
                .replaceAll("(应用程序|應用程式|应用|應用)$", "")
                .trim();
    }

    private static String normalizeKeyword(String value) {
        if (value == null) return "";
        return value.trim()
                .replaceAll("[^\\p{L}\\p{Nd}.]+", " ")
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private static Map<String, String> buildCommonPackages() {
        Map<String, String> map = new HashMap<>();
        map.put("whatsapp", "com.whatsapp");
        map.put("telegram", "org.telegram.messenger");
        map.put("instagram", "com.instagram.android");
        map.put("youtube", "com.google.android.youtube");
        map.put("chrome", "com.android.chrome");
        map.put("camera", "com.android.camera2");
        map.put("settings", "com.android.settings");
        map.put("messages", "com.google.android.apps.messaging");
        map.put("gmail", "com.google.android.gm");
        map.put("maps", "com.google.android.apps.maps");
        map.put("phone", "com.google.android.dialer");
        map.put("contacts", "com.google.android.contacts");
        map.put("calendar", "com.google.android.calendar");
        map.put("clock", "com.google.android.deskclock");
        map.put("calculator", "com.google.android.calculator");
        map.put("files", "com.google.android.documentsui");
        map.put("photos", "com.google.android.apps.photos");
        map.put("spotify", "com.spotify.music");
        map.put("twitter", "com.twitter.android");
        map.put("x", "com.twitter.android");
        map.put("facebook", "com.facebook.katana");
        map.put("tiktok", "com.zhiliaoapp.musically");
        map.put("snapchat", "com.snapchat.android");
        map.put("reddit", "com.reddit.frontpage");
        map.put("discord", "com.discord");
        map.put("slack", "com.Slack");
        map.put("wechat", "com.tencent.mm");
        map.put("line", "jp.naver.line.android");
        return Collections.unmodifiableMap(map);
    }

    /**
     * Longer keys first so containment replacement prefers the most specific alias.
     */
    private static Map<String, String> buildAppAliases() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("汇丰银行", "hsbc");
        map.put("匯豐銀行", "hsbc");
        map.put("汇丰", "hsbc");
        map.put("匯豐", "hsbc");
        map.put("微信", "wechat");
        map.put("支付宝", "alipay");
        map.put("抖音", "tiktok");
        map.put("电报", "telegram");
        map.put("油管", "youtube");
        map.put("浏览器", "chrome");
        map.put("短信", "messages");
        map.put("信息", "messages");
        map.put("电话", "phone");
        map.put("拨号", "phone");
        map.put("照相机", "camera");
        map.put("相机", "camera");
        map.put("设置", "settings");
        map.put("时钟", "clock");
        map.put("计算器", "calculator");
        map.put("日历", "calendar");
        map.put("通讯录", "contacts");
        map.put("联系人", "contacts");
        map.put("文件", "files");
        map.put("相册", "photos");
        map.put("照片", "photos");
        map.put("地图", "maps");
        map.put("邮件", "gmail");
        map.put("推特", "twitter");
        return Collections.unmodifiableMap(map);
    }
}
