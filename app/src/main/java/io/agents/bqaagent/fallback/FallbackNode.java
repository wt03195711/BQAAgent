// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.fallback;

import android.content.res.Configuration;
import android.graphics.Rect;
import android.util.DisplayMetrics;

import com.google.gson.annotations.SerializedName;

import io.agents.bqaagent.ClawApplication;
import io.agents.bqaagent.adb.UiNode;

/**
 * Fallback 组件数据模型。
 * 属性与 UiNode 完全一致，确保可以无缝替代 UiNode 参与工具执行。
 * 额外扩展 tags（别名标签）和 activity（页面归属）两个辅助字段。
 */
public class FallbackNode {

    // ==================== 与 UiNode 对应的属性 ====================

    /** 组件唯一标识（语义化 ID，如 "transfer_confirm_btn"，不同于 ADB 动态生成的 "n1"） */
    @SerializedName("id")
    private String id;

    /** 组件显示文本，对应 UiNode.text */
    @SerializedName("text")
    private String text;

    /** 无障碍描述，对应 UiNode.contentDescription */
    @SerializedName("contentDescription")
    private String contentDescription;

    /** 资源 ID，对应 UiNode.resourceId（如 "com.hsbc.hkmb.app:id/btn_confirm"） */
    @SerializedName("resourceId")
    private String resourceId;

    /** 类名，对应 UiNode.className（如 "android.widget.Button"） */
    @SerializedName("className")
    private String className;

    /** 包名，对应 UiNode.packageName（如 "com.hsbc.hkmb.app"） */
    @SerializedName("packageName")
    private String packageName;

    /**
     * 边界坐标 [left, top, right, bottom]。
     * 基于 bounds_base_resolution 指定的基准分辨率定义。
     * 运行时会根据实际设备分辨率自动缩放。
     */
    @SerializedName("bounds")
    private int[] bounds;

    /** 是否可点击 */
    @SerializedName("clickable")
    private boolean clickable;

    /** 是否可长按 */
    @SerializedName("longClickable")
    private boolean longClickable;

    /** 是否可滚动 */
    @SerializedName("scrollable")
    private boolean scrollable;

    /** 是否可选（如 CheckBox） */
    @SerializedName("checkable")
    private boolean checkable;

    /** 是否已选中 */
    @SerializedName("checked")
    private boolean checked;

    /** 是否已启用 */
    @SerializedName("enabled")
    private boolean enabled;

    /** 是否已聚焦 */
    @SerializedName("focused")
    private boolean focused;

    /** 是否已选择 */
    @SerializedName("selected")
    private boolean selected;

    // ==================== 扩展属性 ====================

    /**
     * 别名/关键词标签数组。
     * 用于解决同义词、繁简体、多语言、LLM 推理偏差导致的匹配失败问题。
     * 例如：["转账", "汇款", "转帐", "付款", "transfer", "payment"]
     */
    @SerializedName("tags")
    private String[] tags;

    /**
     * 该组件所属的 Activity 类名（简短名或全限定名均可）。
     * 用于页面级精确过滤，解决同一应用内不同页面存在同名组件的问题。
     * 匹配时取 Activity 类名的最后一段（如 "ConfirmActivity"）进行比较。
     * 例如："ConfirmActivity"、".ui.transfer.ConfirmActivity"
     *
     * 为 null 或空表示通用组件（如系统弹窗按钮），在所有页面都可能出现。
     */
    @SerializedName("activity")
    private String activity;

    // ==================== Getter 方法 ====================

    public String getId() { return id; }
    public String getText() { return text != null ? text : ""; }
    public String getContentDescription() { return contentDescription != null ? contentDescription : ""; }
    public String getResourceId() { return resourceId != null ? resourceId : ""; }
    public String getClassName() { return className != null ? className : ""; }
    public String getPackageName() { return packageName != null ? packageName : ""; }
    public int[] getBoundsRaw() { return bounds; }
    public boolean getClickable() { return clickable; }
    public boolean getLongClickable() { return longClickable; }
    public boolean getScrollable() { return scrollable; }
    public boolean getCheckable() { return checkable; }
    public boolean getChecked() { return checked; }
    public boolean getEnabled() { return enabled; }
    public boolean getFocused() { return focused; }
    public boolean getSelected() { return selected; }
    public String[] getTags() { return tags; }
    public String getActivity() { return activity != null ? activity : ""; }

    // ==================== 转换与缩放方法 ====================

    /**
     * 将 FallbackNode 转换为 UiNode，供工具类直接使用。
     * 使用 "fb_" 前缀的 ID 与 ADB 动态生成的 nodeId（如 "n1"）区分。
     *
     * @return 转换后的 UiNode 实例
     */
    public UiNode toUiNode() {
        Rect scaledBounds = getScaledBounds();
        return new UiNode(
                "fb_" + (id != null ? id : "unknown"),
                getText(),
                getContentDescription(),
                getResourceId(),
                getClassName(),
                getPackageName(),
                scaledBounds,
                clickable,
                longClickable,
                scrollable,
                checkable,
                checked,
                enabled,
                focused,
                selected
        );
    }

    /**
     * 获取经过缩放和字体补偿后的边界坐标。
     * 缩放基于配置中的 bounds_base_resolution 与当前设备实际分辨率的比例。
     * 同时根据系统字体缩放比例对组件高度进行补偿。
     *
     * @return 缩放后的 Rect 对象；如果 bounds 无效则返回空 Rect
     */
    public Rect getScaledBounds() {
        if (bounds == null || bounds.length < 4) return new Rect();

        int left = bounds[0], top = bounds[1], right = bounds[2], bottom = bounds[3];

        // 获取当前设备屏幕分辨率
        DisplayMetrics metrics = ClawApplication.Companion.getInstance().getResources().getDisplayMetrics();
        int currentWidth = metrics.widthPixels;
        int currentHeight = metrics.heightPixels;

        // 获取配置的基准分辨率（默认 1080x2400，覆盖大多数标准手机）
        int baseWidth = getBaseResolutionWidth();
        int baseHeight = getBaseResolutionHeight();

        // 计算缩放比例
        float scaleX = (float) currentWidth / baseWidth;
        float scaleY = (float) currentHeight / baseHeight;

        // 应用分辨率缩放
        left = Math.round(left * scaleX);
        top = Math.round(top * scaleY);
        right = Math.round(right * scaleX);
        bottom = Math.round(bottom * scaleY);

        // 字体大小补偿：系统字体放大时，含文字组件的高度会增加
        Configuration config = ClawApplication.Companion.getInstance().getResources().getConfiguration();
        float fontScale = config.fontScale;
        if (fontScale > 1.0f) {
            int originalHeight = bottom - top;
            // 经验系数 0.5：字体放大对组件高度的影响约为字体缩放比例的 50%
            int heightDelta = Math.round(originalHeight * (fontScale - 1.0f) * 0.5f);
            bottom += heightDelta;
        }

        // 确保坐标不超出屏幕范围
        left = Math.max(0, Math.min(left, currentWidth - 1));
        top = Math.max(0, Math.min(top, currentHeight - 1));
        right = Math.max(left + 1, Math.min(right, currentWidth));
        bottom = Math.max(top + 1, Math.min(bottom, currentHeight));

        return new Rect(left, top, right, bottom);
    }

    /**
     * 获取基准分辨率宽度。
     * 从配置文件的 bounds_base_resolution 字段获取，默认 1080。
     * 子类/调用方可通过 setBaseResolution 覆盖。
     */
    private int baseResolutionWidth = 1080;
    private int baseResolutionHeight = 2400;

    /**
     * 设置基准分辨率（由 FallbackProfile 加载时调用）。
     *
     * @param width  基准宽度（如 1080）
     * @param height 基准高度（如 2400）
     */
    public void setBaseResolution(int width, int height) {
        if (width > 0 && height > 0) {
            this.baseResolutionWidth = width;
            this.baseResolutionHeight = height;
        }
    }

    private int getBaseResolutionWidth() { return baseResolutionWidth; }
    private int getBaseResolutionHeight() { return baseResolutionHeight; }
}
