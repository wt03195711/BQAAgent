// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.fallback;

import android.content.Context;

import com.google.gson.Gson;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.agents.bqaagent.adb.LocalAdbDeviceDriver;
import io.agents.bqaagent.adb.UiNode;
import io.agents.bqaagent.utils.UiTextMatchUtils;
import io.agents.bqaagent.utils.XLog;

/**
 * Fallback 组件注册表（核心类）。
 *
 * 职责：
 * 1. 在应用启动时从 assets/fallback_profiles/ 全量加载所有 JSON 配置（不做版本筛选）
 * 2. 查询时根据当前设备的应用版本，从公共配置 + 版本配置中合并出有效组件集
 * 3. 提供文本模糊匹配查询，仅在当前前台应用范围内匹配，不跨应用搜索
 * 4. 坐标自动缩放（分辨率 + 字体补偿）由 FallbackNode 内部完成
 *
 * 数据分层存储：
 * - commonComponentsMap：公共配置组件（app_version 为 null），始终生效
 * - versionComponentsMap：版本专属配置组件，查询时按实际版本匹配后覆盖公共组件
 *
 * 匹配流程（两层过滤，逐步降级）：
 * - 第 1 层：Activity 精确过滤 — 仅匹配 activity 字段与当前 Activity 一致的组件
 * - 第 2 层：通用组件兜底 — 匹配 activity 为空的通用组件（如系统弹窗按钮）
 *
 * 系统弹窗处理：
 * 当系统弹窗（权限请求等）出现时，前台包名变为系统包名，
 * 自动回退到目标应用的配置中查找。
 */
public class FallbackRegistry {

    private static final String TAG = "FallbackRegistry";

    /** assets 下的配置根目录 */
    private static final String PROFILES_DIR = "fallback_profiles";

    /** 全局注册表文件名 */
    private static final String REGISTRY_FILE = "_registry.json";

    /**
     * 系统弹窗包名列表。
     * 当系统弹窗（如权限请求）出现时，前台包名会变成系统包名，
     * 此时需要回退到目标应用的配置中查找组件。
     */
    private static final String[] SYSTEM_DIALOG_PACKAGES = {
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.samsung.android.permissioncontroller",
            "com.android.permissioncontroller",
    };

    // ==================== 单例 ====================

    private static volatile FallbackRegistry sInstance;

    /**
     * 获取 FallbackRegistry 单例实例。
     *
     * @return 全局唯一的 FallbackRegistry 实例
     */
    public static FallbackRegistry getInstance() {
        if (sInstance == null) {
            synchronized (FallbackRegistry.class) {
                if (sInstance == null) {
                    sInstance = new FallbackRegistry();
                }
            }
        }
        return sInstance;
    }

    // ==================== 内部数据结构 ====================

    /**
     * 公共组件映射表（app_version 为 null 的配置）。
     * 外层 key = 应用包名（如 "com.hsbc.hkmb.app"）
     * 内层 key = 组件 id（如 "transfer_confirm_btn"）
     * value = FallbackNode 实例
     */
    private final Map<String, Map<String, FallbackNode>> commonComponentsMap = new HashMap<>();

    /**
     * 版本专属组件映射表。
     * 外层 key = 应用包名（如 "com.hsbc.hkmb.app"）
     * 中层 key = 版本号（如 "5.2.0"）
     * 内层 key = 组件 id（如 "transfer_confirm_btn"）
     * value = FallbackNode 实例
     *
     * 查询时根据设备实际版本取出对应中层的组件，按 id 覆盖公共组件。
     */
    private final Map<String, Map<String, Map<String, FallbackNode>>> versionComponentsMap = new HashMap<>();

    /** Gson 实例，用于 JSON 反序列化 */
    private final Gson gson = new Gson();

    /** 标记是否已完成初始化加载 */
    private volatile boolean initialized = false;

    /** 缓存应用上下文，用于后续查询应用版本 */
    private Context appContext;

    // ==================== 初始化加载 ====================

    /**
     * 从 assets/fallback_profiles/ 全量加载所有配置。
     * 应在应用启动时调用一次（ClawApplication.onCreate 中）。
     *
     * 加载流程：
     * 1. 读取 _registry.json 获取所有配置条目
     * 2. 遍历所有条目，加载每个 JSON 文件中的组件
     * 3. 按 app_version 分类存储：
     *    - app_version 为 null → 存入 commonComponentsMap（公共组件）
     *    - app_version 不为 null → 存入 versionComponentsMap（版本组件）
     * 4. 此阶段不做任何版本筛选，所有配置全量加载到内存
     *
     * @param context Android Context
     */
    public void loadAll(Context context) {
        if (initialized) {
            XLog.w(TAG, "FallbackRegistry already initialized, skipping reload");
            return;
        }

        appContext = context.getApplicationContext();
        commonComponentsMap.clear();
        versionComponentsMap.clear();

        try {
            // Step 1: 读取全局注册表文件
            String registryPath = PROFILES_DIR + "/" + REGISTRY_FILE;
            RegistryData registry;
            try (InputStreamReader reader = new InputStreamReader(
                    context.getAssets().open(registryPath), StandardCharsets.UTF_8)) {
                registry = gson.fromJson(reader, RegistryData.class);
            }

            if (registry == null || registry.profiles == null || registry.profiles.isEmpty()) {
                XLog.w(TAG, "Registry is empty or invalid, no fallback profiles loaded");
                initialized = true;
                return;
            }

            // Step 2: 遍历所有条目，全量加载每个配置文件（不做版本筛选）
            int totalCount = 0;
            for (RegistryEntry entry : registry.profiles) {
                totalCount += loadProfileFiles(context, entry);
            }

            // Step 3: 输出汇总
            int commonCount = 0;
            for (Map<String, FallbackNode> comps : commonComponentsMap.values()) {
                commonCount += comps.size();
            }
            int versionCount = 0;
            for (Map<String, Map<String, FallbackNode>> pkgVersions : versionComponentsMap.values()) {
                for (Map<String, FallbackNode> comps : pkgVersions.values()) {
                    versionCount += comps.size();
                }
            }

            XLog.i(TAG, "FallbackRegistry initialized: "
                    + commonComponentsMap.size() + " app(s) with common config, "
                    + versionComponentsMap.size() + " app(s) with version config, "
                    + commonCount + " common component(s), "
                    + versionCount + " version-specific component(s)");

        } catch (Exception e) {
            XLog.e(TAG, "Failed to load fallback profiles", e);
        }

        initialized = true;
    }

    /**
     * 加载单个 RegistryEntry 中声明的所有 JSON 文件。
     * 根据 JSON 文件内部的 app_version 字段分类存储：
     * - app_version 为 null → 存入 commonComponentsMap
     * - app_version 不为 null → 存入 versionComponentsMap
     *
     * @param context Android Context
     * @param entry   注册表中的一个配置条目
     * @return 成功加载的组件数量
     */
    private int loadProfileFiles(Context context, RegistryEntry entry) {
        if (entry.files == null) return 0;
        int count = 0;

        for (String filePath : entry.files) {
            try {
                String fullPath = PROFILES_DIR + "/" + filePath;
                FallbackProfile profile;
                try (InputStreamReader reader = new InputStreamReader(
                        context.getAssets().open(fullPath), StandardCharsets.UTF_8)) {
                    profile = gson.fromJson(reader, FallbackProfile.class);
                }

                if (profile == null || profile.getComponents().isEmpty()) continue;

                // 设置每个组件的基准分辨率（用于后续坐标缩放）
                profile.initComponentResolutions();

                String pkg = profile.getAppPackage();
                String version = profile.getAppVersion();

                if (version == null || version.isEmpty()) {
                    // 公共配置 → 存入 commonComponentsMap
                    if (!commonComponentsMap.containsKey(pkg)) {
                        commonComponentsMap.put(pkg, new LinkedHashMap<>());
                    }
                    Map<String, FallbackNode> compMap = commonComponentsMap.get(pkg);
                    for (FallbackNode node : profile.getComponents()) {
                        String nodeId = node.getId();
                        if (nodeId == null || nodeId.isEmpty()) {
                            nodeId = "auto_common_" + count;
                        }
                        compMap.put(nodeId, node);
                        count++;
                    }
                } else {
                    // 版本配置 → 存入 versionComponentsMap
                    if (!versionComponentsMap.containsKey(pkg)) {
                        versionComponentsMap.put(pkg, new LinkedHashMap<>());
                    }
                    Map<String, Map<String, FallbackNode>> pkgVersions = versionComponentsMap.get(pkg);
                    if (!pkgVersions.containsKey(version)) {
                        pkgVersions.put(version, new LinkedHashMap<>());
                    }
                    Map<String, FallbackNode> versionCompMap = pkgVersions.get(version);
                    for (FallbackNode node : profile.getComponents()) {
                        String nodeId = node.getId();
                        if (nodeId == null || nodeId.isEmpty()) {
                            nodeId = "auto_v" + version + "_" + count;
                        }
                        versionCompMap.put(nodeId, node);
                        count++;
                    }
                }

                XLog.d(TAG, "Loaded profile: " + profile.getProfileId()
                        + " (" + profile.getComponents().size() + " components)"
                        + (profile.isVersionSpecific() ? " [v" + profile.getAppVersion() + "]" : " [common]"));

            } catch (Exception e) {
                XLog.w(TAG, "Failed to load fallback file: " + filePath, e);
            }
        }
        return count;
    }

    // ==================== 查询方法 ====================

    /**
     * 按文本模糊匹配查询 fallback 组件。
     *
     * 匹配流程（两层过滤，逐步降级）：
     * 1. Activity 精确过滤：仅保留 activity 字段与当前 Activity 匹配的组件
     * 2. 通用组件兜底：匹配 activity 为空的通用组件（如系统弹窗按钮）
     *
     * 特殊处理：当系统弹窗出现时（前台包名为系统包名），
     * 自动回退到目标应用的配置中查找。
     *
     * @param queryText 查询文本
     * @return 匹配的 UiNode 列表；空列表表示无匹配
     */
    public List<UiNode> queryByFuzzyText(String queryText) {
        if (!initialized || queryText == null || queryText.trim().isEmpty()) {
            return new ArrayList<>();
        }

        String trimmedQuery = queryText.trim();

        String currentPackage = "";
        try {
            currentPackage = LocalAdbDeviceDriver.activePackageName();
        } catch (Exception ignored) {}

        if (currentPackage.isEmpty()) {
            XLog.d(TAG, "queryByFuzzyText: unable to determine current app package");
            return new ArrayList<>();
        }

        // 如果当前前台是系统弹窗，回退到目标应用配置中查找
        String effectivePackage = resolveEffectivePackage(currentPackage);

        Map<String, FallbackNode> effectiveComponents = getEffectiveComponents(effectivePackage);
        if (effectiveComponents.isEmpty()) {
            XLog.d(TAG, "queryByFuzzyText: no fallback config for app [" + effectivePackage + "]");
            return new ArrayList<>();
        }

        // 获取当前 Activity 名（用于页面级过滤）
        String currentActivity = getCurrentActivityShortName();

        // ===== 第 1 层：Activity 精确过滤 =====
        if (!currentActivity.isEmpty()) {
            List<UiNode> activityMatched = matchInActivityScope(effectiveComponents, trimmedQuery, currentActivity);
            if (!activityMatched.isEmpty()) {
                XLog.i(TAG, "queryByFuzzyText activity hit in app [" + effectivePackage + "] activity=[" + currentActivity + "]: \""
                        + trimmedQuery + "\" → " + activityMatched.size() + " node(s)");
                return activityMatched;
            }
        }

        // ===== 第 2 层：通用组件兜底 =====
        // 只匹配 activity 为空的通用组件，避免跨页面误匹配
        List<UiNode> globalMatched = matchGlobalComponents(effectiveComponents, trimmedQuery);
        if (!globalMatched.isEmpty()) {
            XLog.i(TAG, "queryByFuzzyText global hit in app [" + effectivePackage + "]: \""
                    + trimmedQuery + "\" → " + globalMatched.size() + " node(s)");
            return globalMatched;
        }

        XLog.d(TAG, "queryByFuzzyText no match in app [" + effectivePackage + "] for: \"" + trimmedQuery + "\"");
        return new ArrayList<>();
    }

    /**
     * 查询当前前台应用的所有 fallback 组件。
     *
     * 返回结果 = 公共组件 + 版本匹配组件（版本按 id 覆盖公共）
     *
     * @return 当前应用的所有 fallback 组件（已转换为 UiNode）；空列表表示无配置
     */
    public List<UiNode> queryAllForCurrentApp() {
        if (!initialized) return new ArrayList<>();

        String currentPackage = "";
        try {
            currentPackage = LocalAdbDeviceDriver.activePackageName();
        } catch (Exception ignored) {}

        if (currentPackage.isEmpty()) return new ArrayList<>();

        String effectivePackage = resolveEffectivePackage(currentPackage);

        Map<String, FallbackNode> effectiveComponents = getEffectiveComponents(effectivePackage);
        if (effectiveComponents.isEmpty()) return new ArrayList<>();

        List<UiNode> results = new ArrayList<>();
        for (FallbackNode node : effectiveComponents.values()) {
            results.add(node.toUiNode());
        }

        XLog.d(TAG, "queryAllForCurrentApp: " + results.size() + " fallback node(s) for [" + effectivePackage + "]");
        return results;
    }

    // ==================== 两层匹配逻辑 ====================

    /**
     * 第 1 层：在 Activity 匹配的组件中做文本匹配。
     * 仅检查 activity 字段与当前 Activity 名一致的组件。
     */
    private List<UiNode> matchInActivityScope(Map<String, FallbackNode> compMap, String queryText, String currentActivity) {
        List<UiNode> results = new ArrayList<>();
        for (FallbackNode node : compMap.values()) {
            String nodeActivity = node.getActivity();
            if (nodeActivity.isEmpty()) continue;
            if (activityMatches(nodeActivity, currentActivity)) {
                if (matchesNode(node, queryText)) {
                    results.add(node.toUiNode());
                }
            }
        }
        return results;
    }

    /**
     * 判断配置的 activity 字段是否与当前 Activity 匹配。
     *
     * 匹配规则（安全匹配，不会扩大范围）：
     * 1. 精确匹配（忽略大小写）
     * 2. 全限定名匹配：nodeActivity 为全限定名时，取其最后一段短名与 currentActivity 比较
     *    例如：nodeActivity = ".ui.transfer.ConfirmActivity"，currentActivity = "ConfirmActivity" → 匹配
     *
     * @param nodeActivity    配置中的 activity 字段值
     * @param currentActivity 当前前台 Activity 短名
     * @return true 表示 Activity 匹配成功
     */
    private boolean activityMatches(String nodeActivity, String currentActivity) {
        if (nodeActivity.equalsIgnoreCase(currentActivity)) {
            return true;
        }
        if (nodeActivity.contains(".")) {
            String shortName = nodeActivity.substring(nodeActivity.lastIndexOf('.') + 1);
            return shortName.equalsIgnoreCase(currentActivity);
        }
        return false;
    }

    /**
     * 第 2 层：通用组件兜底匹配。
     * 只匹配 activity 为空的通用组件（如系统弹窗按钮）。
     * 有 activity 的组件不在这一层匹配，避免跨页面误命中。
     */
    private List<UiNode> matchGlobalComponents(Map<String, FallbackNode> compMap, String queryText) {
        List<UiNode> results = new ArrayList<>();
        for (FallbackNode node : compMap.values()) {
            if (!node.getActivity().isEmpty()) continue;
            if (matchesNode(node, queryText)) {
                results.add(node.toUiNode());
            }
        }
        return results;
    }

    // ==================== 系统弹窗处理 ====================

    /**
     * 解析有效的应用包名。
     * 当系统弹窗（权限请求等）出现时，前台包名会变成系统包名，
     * 此时需要回退到目标应用的配置中查找。
     *
     * @param currentPackage 当前前台包名
     * @return 有效的应用包名（如果是系统弹窗，返回目标应用包名；否则原样返回）
     */
    private String resolveEffectivePackage(String currentPackage) {
        if (isSystemDialogPackage(currentPackage)) {
            String targetPackage = findTargetAppPackage();
            if (!targetPackage.isEmpty()) {
                XLog.d(TAG, "System dialog detected [" + currentPackage + "], falling back to target app [" + targetPackage + "]");
                return targetPackage;
            }
        }
        return currentPackage;
    }

    /**
     * 判断是否为系统弹窗包名。
     */
    private boolean isSystemDialogPackage(String packageName) {
        for (String sysPkg : SYSTEM_DIALOG_PACKAGES) {
            if (sysPkg.equals(packageName)) return true;
        }
        return false;
    }

    /**
     * 查找目标应用包名（排除自身和系统弹窗包名）。
     * 通过遍历已注册的 fallback 配置，找到当前已安装且在运行的应用。
     */
    private String findTargetAppPackage() {
        for (String pkg : commonComponentsMap.keySet()) {
            if (!isSystemDialogPackage(pkg) && isAppInstalled(pkg)) {
                return pkg;
            }
        }
        for (String pkg : versionComponentsMap.keySet()) {
            if (!isSystemDialogPackage(pkg) && isAppInstalled(pkg)) {
                return pkg;
            }
        }
        return "";
    }

    /**
     * 判断指定包名的应用是否已安装。
     */
    private boolean isAppInstalled(String packageName) {
        if (appContext == null) return false;
        try {
            appContext.getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== Activity 获取 ====================

    /**
     * 获取当前前台 Activity 的简短类名。
     * 例如：当前 Activity 为 "com.hsbc.hkmb.app.ui.transfer.ConfirmActivity"
     *       返回 "ConfirmActivity"
     */
    private String getCurrentActivityShortName() {
        try {
            String fullActivity = LocalAdbDeviceDriver.activeActivityName();
            if (fullActivity == null || fullActivity.isEmpty()) return "";
            return fullActivity.substring(fullActivity.lastIndexOf('.') + 1);
        } catch (Exception e) {
            XLog.d(TAG, "Failed to get current activity name");
            return "";
        }
    }

    // ==================== 版本合并逻辑 ====================

    /**
     * 获取指定应用的有效组件集。
     * 合并规则：
     * 1. 先取该应用的公共组件（commonComponentsMap）
     * 2. 获取设备实际安装的应用版本
     * 3. 如果 versionComponentsMap 中有匹配版本的组件，按 id 覆盖公共组件
     *
     * @param packageName 应用包名
     * @return 合并后的有效组件映射表；如果该应用无任何配置则返回空 Map
     */
    private Map<String, FallbackNode> getEffectiveComponents(String packageName) {
        // 先取公共组件
        Map<String, FallbackNode> common = commonComponentsMap.get(packageName);
        Map<String, FallbackNode> effective = new LinkedHashMap<>();
        if (common != null) {
            effective.putAll(common);
        }

        // 获取设备实际安装的应用版本
        String installedVersion = getInstalledAppVersion(packageName);

        // 如果有匹配的版本组件，按 id 覆盖公共组件
        if (installedVersion != null && !installedVersion.isEmpty()) {
            Map<String, Map<String, FallbackNode>> pkgVersions = versionComponentsMap.get(packageName);
            if (pkgVersions != null) {
                Map<String, FallbackNode> versionComps = pkgVersions.get(installedVersion);
                if (versionComps != null && !versionComps.isEmpty()) {
                    effective.putAll(versionComps);
                    XLog.d(TAG, "Version override for [" + packageName + "] v" + installedVersion
                            + ": " + versionComps.size() + " component(s) override common");
                }
            }
        }

        return effective;
    }

    /**
     * 获取指定应用的设备实际安装版本号。
     * 通过 PackageManager 查询，用于在查询时（而非加载时）匹配版本组件。
     *
     * @param packageName 应用包名
     * @return 版本名（如 "5.2.0"），未安装或查询失败返回 null
     */
    private String getInstalledAppVersion(String packageName) {
        if (appContext == null) return null;
        try {
            android.content.pm.PackageInfo info =
                    appContext.getPackageManager().getPackageInfo(packageName, 0);
            return info.versionName;
        } catch (Exception e) {
            XLog.d(TAG, "App not installed or version unavailable: " + packageName);
            return null;
        }
    }

    // ==================== 内部匹配逻辑 ====================

    /**
     * 判断单个 FallbackNode 是否与查询文本匹配。
     * 按优先级依次检查：text → contentDescription → resourceId → tags
     *
     * @param node      待匹配的 fallback 组件
     * @param queryText 查询文本
     * @return true 表示匹配成功
     */
    private boolean matchesNode(FallbackNode node, String queryText) {
        // 第 1 层：匹配 text（精确 + 归一化）
        if (!node.getText().isEmpty()) {
            if (UiTextMatchUtils.matchesExactOrNormalized(node.getText(), queryText)) {
                return true;
            }
        }

        // 第 2 层：匹配 contentDescription（精确 + 归一化）
        if (!node.getContentDescription().isEmpty()) {
            if (UiTextMatchUtils.matchesExactOrNormalized(node.getContentDescription(), queryText)) {
                return true;
            }
        }

        // 第 3 层：匹配 resourceId（宽松匹配）
        if (!node.getResourceId().isEmpty()) {
            if (UiTextMatchUtils.matchesRelaxed(node.getResourceId(), queryText)) {
                return true;
            }
        }

        // 第 4 层：遍历 tags 数组，每个 tag 走完整匹配
        String[] tags = node.getTags();
        if (tags != null) {
            for (String tag : tags) {
                if (tag == null || tag.trim().isEmpty()) continue;
                if (UiTextMatchUtils.matchesExactOrNormalized(tag, queryText)
                        || UiTextMatchUtils.matchesRelaxed(tag, queryText)) {
                    return true;
                }
            }
        }

        return false;
    }

    // ==================== 注册表数据模型（内部类） ====================

    /**
     * _registry.json 的数据模型。
     * 仅用于 Gson 反序列化，不对外暴露。
     */
    private static class RegistryData {
        List<RegistryEntry> profiles;
    }

    /**
     * 注册表中的单个配置条目。
     * 声明一个应用/版本对应的配置文件列表。
     */
    private static class RegistryEntry {
        /** 应用包名 */
        String appPackage;

        /** 应用版本号（null 表示公共配置） */
        String appVersion;

        /** 设备型号（预留字段，当前未使用） */
        String deviceModel;

        /** 配置文件路径列表（相对于 fallback_profiles/ 目录） */
        List<String> files;
    }
}
