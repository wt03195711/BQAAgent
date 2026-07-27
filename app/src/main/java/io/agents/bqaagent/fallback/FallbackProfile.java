// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.fallback;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.List;

/**
 * Fallback 配置 Profile 模型。
 * 对应一个 JSON 配置文件，包含一组属于同一应用/版本的组件定义。
 *
 * 配置文件示例：
 * {
 *   "profile_id": "hsbc_hk_buttons_common",
 *   "app_package": "com.hsbc.hkmb.app",
 *   "app_version": null,
 *   "component_type": "button",
 *   "description": "汇丰香港 通用按钮组件",
 *   "bounds_base_resolution": [1080, 2400],
 *   "components": [ ... ]
 * }
 */
public class FallbackProfile {

    /** Profile 唯一标识（用于日志和调试） */
    @SerializedName("profile_id")
    private String profileId;

    /** 所属应用的包名（如 "com.hsbc.hkmb.app"） */
    @SerializedName("app_package")
    private String appPackage;

    /**
     * 适用的应用版本号（如 "5.2.0"）。
     * null 表示该配置为公共配置，适用于所有版本。
     */
    @SerializedName("app_version")
    private String appVersion;

    /** 组件类型分类（如 "button"、"input"、"tab"、"dialog"），仅用于组织管理 */
    @SerializedName("component_type")
    private String componentType;

    /** 配置描述信息 */
    @SerializedName("description")
    private String description;

    /**
     * 基准分辨率 [width, height]。
     * 配置中的组件坐标基于此分辨率定义，运行时会自动缩放。
     * 如果为 null，默认使用 [1080, 2400]。
     */
    @SerializedName("bounds_base_resolution")
    private int[] boundsBaseResolution;

    /** 该配置中包含的所有组件列表 */
    @SerializedName("components")
    private List<FallbackNode> components;

    // ==================== Getter 方法 ====================

    public String getProfileId() { return profileId != null ? profileId : ""; }
    public String getAppPackage() { return appPackage != null ? appPackage : ""; }
    public String getAppVersion() { return appVersion; }
    public String getComponentType() { return componentType != null ? componentType : ""; }
    public String getDescription() { return description != null ? description : ""; }
    public List<FallbackNode> getComponents() { return components != null ? components : new ArrayList<>(); }

    /**
     * 获取基准分辨率宽度，默认 1080。
     */
    public int getBaseResolutionWidth() {
        return (boundsBaseResolution != null && boundsBaseResolution.length >= 2)
                ? boundsBaseResolution[0] : 1080;
    }

    /**
     * 获取基准分辨率高度，默认 2400。
     */
    public int getBaseResolutionHeight() {
        return (boundsBaseResolution != null && boundsBaseResolution.length >= 2)
                ? boundsBaseResolution[1] : 2400;
    }

    /**
     * 判断该 Profile 是否为版本专属配置（而非公共配置）。
     *
     * @return true 表示该配置仅适用于特定应用版本
     */
    public boolean isVersionSpecific() {
        return appVersion != null && !appVersion.isEmpty();
    }

    /**
     * 初始化所有组件的基准分辨率。
     * 在 Profile 加载完成后调用，确保每个组件在坐标缩放时使用正确的基准值。
     */
    public void initComponentResolutions() {
        int width = getBaseResolutionWidth();
        int height = getBaseResolutionHeight();
        for (FallbackNode node : getComponents()) {
            node.setBaseResolution(width, height);
        }
    }
}
