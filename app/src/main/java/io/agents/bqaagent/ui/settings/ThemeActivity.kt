// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.ui.settings

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import io.agents.bqaagent.R
import io.agents.bqaagent.base.BaseActivity
import io.agents.bqaagent.utils.KVUtils
import io.agents.bqaagent.widget.CommonToolbar

class ThemeActivity : BaseActivity() {

    data class ThemeConfig(
        val id: String,
        val name: String,
        val isDark: Boolean,
        val bg: Int,
        val userBubble: Int,
        val aiBubble: Int,
        val avatar: Int,
        val inputBar: Int,
        val accent: Int
    )

    // Only expose ember (brand color). Other themes kept in ThemeManager for future use.
    private val themes = listOf(
        ThemeConfig("ember_dark", "Dark", true, Color.parseColor("#000000"), Color.parseColor("#DB0011"), Color.parseColor("#171717"), Color.parseColor("#DB0011"), Color.parseColor("#4A4A4A"), Color.parseColor("#FF3342")),
        ThemeConfig("ember_light", "Light", false, Color.parseColor("#F5F5F5"), Color.parseColor("#DB0011"), Color.parseColor("#FFFFFF"), Color.parseColor("#DB0011"), Color.parseColor("#B7B7B7"), Color.parseColor("#DB0011")),
    )

    private var selectedThemeId = "ember_light"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tc = io.agents.bqaagent.ui.chat.ThemeManager.getColors()
        window.statusBarColor = tc.toolbarBg
        window.decorView.setBackgroundColor(tc.bg)

        setContentView(R.layout.activity_theme)

        val contentFrame = findViewById<android.view.ViewGroup>(android.R.id.content)
        contentFrame?.setBackgroundColor(tc.bg)
        (contentFrame?.getChildAt(0) as? android.view.View)?.setBackgroundColor(tc.bg)

        findViewById<CommonToolbar>(R.id.toolbar).apply {
            setTitle("Appearance")
            setTitleColor(tc.aiText)
            setBackgroundColor(tc.toolbarBg)
            showBackButton(true) { finish() }
            findViewById<android.widget.ImageView>(R.id.ivBack)?.setColorFilter(tc.aiText)
        }
        findViewById<TextView>(R.id.tvCurrentTheme)?.setTextColor(tc.aiText)

        val savedThemeId = KVUtils.getString("THEME_ID", "ember_light")
        selectedThemeId = themes.firstOrNull { it.id == savedThemeId }?.id
            ?: if (savedThemeId.endsWith("_dark")) "ember_dark" else "ember_light"

        val viewIds = listOf(R.id.themeEmberDark, R.id.themeEmberLight)
        // Hide other theme previews
        listOf(R.id.themeAbyssDark, R.id.themeMossDark, R.id.themeOnyxDark,
               R.id.themeAbyssLight, R.id.themeMossLight, R.id.themeOnyxLight).forEach {
            findViewById<View>(it)?.visibility = View.GONE
        }

        themes.forEachIndexed { index, theme ->
            val view = findViewById<View>(viewIds[index])
            setupThemePreview(view, theme)
        }

        updateSelection()
    }

    private fun setupThemePreview(view: View, theme: ThemeConfig) {
        val card = view.findViewById<LinearLayout>(R.id.cardPreview)
        val userBubble = view.findViewById<View>(R.id.previewUserBubble)
        val userBubble2 = view.findViewById<View>(R.id.previewUserBubble2)
        val aiBubble = view.findViewById<View>(R.id.previewAiBubble)
        val avatar = view.findViewById<View>(R.id.previewAvatar)
        val inputBar = view.findViewById<View>(R.id.previewInputBar)
        val name = view.findViewById<TextView>(R.id.tvThemeName)

        // Card background
        val cardBg = GradientDrawable().apply {
            setColor(theme.bg)
            cornerRadius = dp(4f)
        }
        card.background = cardBg

        // User bubble
        userBubble.background = roundRect(theme.userBubble, 4f)
        userBubble2.background = roundRect(theme.userBubble, 4f)

        // AI bubble
        aiBubble.background = roundRect(theme.aiBubble, 4f)

        // Avatar
        avatar.background = oval(theme.avatar)

        // Input bar
        inputBar.background = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke(dp(1).toInt(), theme.inputBar)
            cornerRadius = dp(4f)
        }

        name.text = theme.name

        view.setOnClickListener {
            selectedThemeId = theme.id
            KVUtils.putString("THEME_ID", theme.id)

            // Use system uimode command (works on MIUI where AppCompatDelegate doesn't)
            try {
                val mode = if (theme.isDark) "yes" else "no"
                Runtime.getRuntime().exec(arrayOf("cmd", "uimode", "night", mode))
            } catch (_: Exception) {
                // Fallback to AppCompatDelegate
                val newMode = if (theme.isDark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
                AppCompatDelegate.setDefaultNightMode(newMode)
            }

            // Restart app to apply theme everywhere
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            intent?.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            finishAffinity()
        }
    }

    private fun updateSelection() {
        val allViews = listOf(R.id.themeEmberDark, R.id.themeEmberLight)

        themes.forEachIndexed { index, theme ->
            val view = findViewById<View>(allViews[index])
            val indicator = view.findViewById<View>(R.id.selectedIndicator)
            val isSelected = theme.id == selectedThemeId

            if (isSelected) {
                indicator.visibility = View.VISIBLE
                indicator.background = roundRect(theme.accent, 2f)
            } else {
                indicator.visibility = View.INVISIBLE
            }
        }

        val current = themes.find { it.id == selectedThemeId }
        val label = current?.name ?: selectedThemeId
        findViewById<TextView>(R.id.tvCurrentTheme).text = "Current: $label"
    }

    private fun roundRect(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius)
    }

    private fun oval(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
    private fun dp(v: Int): Float = v * resources.displayMetrics.density
}
