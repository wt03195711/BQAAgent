// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.tool.impl;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import io.agents.bqaagent.ClawApplication;
import io.agents.bqaagent.utils.XLog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Process-local launchable app reference index.
 *
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

    private AppReferenceIndex() {}

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

    public static String resolvePackageName(String appName) {
        String query = sanitizeAppName(appName);
        if (query.isEmpty()) return null;

        String common = COMMON_PACKAGES.get(normalizeKeyword(query));
        if (common != null) return common;

        String fromCache = resolveFromApps(query, ensureLoaded(false));
        if (fromCache != null) return fromCache;

        refresh();
        return resolveFromApps(query, cachedApps);
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

    private static String resolveFromApps(String appName, List<AppReference> apps) {
        String query = normalizeKeyword(appName);
        String queryNoSpace = query.replace(" ", "");
        if (query.isEmpty()) return null;

        AppReference best = null;
        int bestScore = 0;
        for (AppReference app : apps) {
            String label = normalizeKeyword(app.appName);
            String labelNoSpace = label.replace(" ", "");
            String pkg = normalizeKeyword(app.packageName);
            int score = score(query, queryNoSpace, label, labelNoSpace, pkg);
            if (score > bestScore) {
                bestScore = score;
                best = app;
            }
        }

        if (best != null && bestScore >= 100) {
            XLog.i(TAG, "Resolved app name '" + appName + "' -> '" + best.packageName + "' (score=" + bestScore + ")");
            return best.packageName;
        }
        return null;
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

    private static String sanitizeAppName(String value) {
        if (value == null) return "";
        return value.trim()
                .replaceAll("(?i)\\s+(app|application)$", "")
                .replaceAll("\\s+(应用|應用|应用程序|應用程式)$", "")
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
}
