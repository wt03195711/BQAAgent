// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.ui.chat

import android.graphics.Color
import io.agents.bqaagent.utils.KVUtils

/**
 * Runtime theme color provider.
 * Reads saved theme ID from KVUtils and returns the appropriate colors.
 */
object ThemeManager {

    data class ChatColors(
        val bg: Int,
        val toolbarBg: Int,
        val userBubble: Int,
        val userText: Int,
        val aiBubble: Int,
        val aiBubbleBorder: Int,
        val aiText: Int,
        val avatarBg: Int,
        val inputBorder: Int,
        val sendColor: Int,
        val toolOk: Int,
        val toolDefault: Int,
        val divider: Int
    )

    private val themes = mapOf(
        "ember_dark" to ChatColors(
            bg = Color.parseColor("#000000"), toolbarBg = Color.parseColor("#111111"),
            userBubble = Color.parseColor("#DB0011"), userText = Color.parseColor("#FFFFFF"),
            aiBubble = Color.parseColor("#171717"), aiBubbleBorder = Color.parseColor("#333333"),
            aiText = Color.parseColor("#F2F2F2"), avatarBg = Color.parseColor("#DB0011"),
            inputBorder = Color.parseColor("#4A4A4A"), sendColor = Color.parseColor("#FF3342"),
            toolOk = Color.parseColor("#FF3342"), toolDefault = Color.parseColor("#8C8C8C"),
            divider = Color.parseColor("#262626")
        ),
        "ember_light" to ChatColors(
            bg = Color.parseColor("#F5F5F5"), toolbarBg = Color.parseColor("#FFFFFF"),
            userBubble = Color.parseColor("#DB0011"), userText = Color.parseColor("#FFFFFF"),
            aiBubble = Color.parseColor("#FFFFFF"), aiBubbleBorder = Color.parseColor("#D7D8D6"),
            aiText = Color.parseColor("#1D1D1B"), avatarBg = Color.parseColor("#DB0011"),
            inputBorder = Color.parseColor("#B7B7B7"), sendColor = Color.parseColor("#DB0011"),
            toolOk = Color.parseColor("#DB0011"), toolDefault = Color.parseColor("#767676"),
            divider = Color.parseColor("#E6E6E6")
        )
    )

    fun getColors(): ChatColors {
        val id = KVUtils.getString("THEME_ID", "ember_light")
        return if (id.endsWith("_dark")) themes["ember_dark"]!! else themes["ember_light"]!!
    }

    fun ChatColors.toComposeColors(): PokeclawColors {
        val dark = isDark()
        return PokeclawColors(
            background = androidx.compose.ui.graphics.Color(bg),
            surface = androidx.compose.ui.graphics.Color(toolbarBg),
            userBubble = androidx.compose.ui.graphics.Color(userBubble),
            userText = androidx.compose.ui.graphics.Color(userText),
            aiBubble = androidx.compose.ui.graphics.Color(aiBubble),
            aiBubbleBorder = androidx.compose.ui.graphics.Color(aiBubbleBorder),
            aiText = androidx.compose.ui.graphics.Color(aiText),
            avatar = androidx.compose.ui.graphics.Color(avatarBg),
            accent = androidx.compose.ui.graphics.Color(sendColor),
            textPrimary = if (dark) androidx.compose.ui.graphics.Color(0xFFF2F2F2.toInt())
                          else androidx.compose.ui.graphics.Color(0xFF1D1D1B.toInt()),
            textSecondary = if (dark) androidx.compose.ui.graphics.Color(0xFFB3B3B3.toInt())
                            else androidx.compose.ui.graphics.Color(0xFF4D4D4D.toInt()),
            textTertiary = if (dark) androidx.compose.ui.graphics.Color(0xFF8C8C8C.toInt())
                           else androidx.compose.ui.graphics.Color(0xFF767676.toInt()),
            divider = androidx.compose.ui.graphics.Color(divider),
            inputBorder = androidx.compose.ui.graphics.Color(inputBorder),
        )
    }

    fun isDark(): Boolean {
        val id = KVUtils.getString("THEME_ID", "ember_light")
        return id.endsWith("_dark")
    }
}
