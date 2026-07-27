// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.adb

import android.graphics.Rect

data class UiNode(
    val nodeId: String,
    val text: String,
    val contentDescription: String,
    val resourceId: String,
    val className: String,
    val packageName: String,
    val bounds: Rect,
    val clickable: Boolean,
    val longClickable: Boolean,
    val scrollable: Boolean,
    val checkable: Boolean,
    val checked: Boolean,
    val enabled: Boolean,
    val focused: Boolean,
    val selected: Boolean,
) {
    val centerX: Int
        get() = bounds.centerX()

    val centerY: Int
        get() = bounds.centerY()

    val isEditable: Boolean
        get() = className.contains("EditText", ignoreCase = true) ||
            className.contains("TextInput", ignoreCase = true)

    val label: String
        get() = text.ifBlank { contentDescription }
}
