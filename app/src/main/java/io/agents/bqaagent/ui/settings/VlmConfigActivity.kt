// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.ui.settings

import android.os.Bundle
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import io.agents.bqaagent.R
import io.agents.bqaagent.agent.SavedVlmProfile
import io.agents.bqaagent.agent.SavedVlmProfilesStore
import io.agents.bqaagent.agent.VlmConfigRepository
import io.agents.bqaagent.agent.llm.SavedProfileLimits
import io.agents.bqaagent.base.BaseActivity
import io.agents.bqaagent.ui.chat.ThemeManager
import io.agents.bqaagent.widget.AlertDialog
import io.agents.bqaagent.widget.CloudModelConfigDialog
import io.agents.bqaagent.widget.CommonToolbar
import io.agents.bqaagent.widget.KButton

/**
 * Configuration page for VLM (Vision Language Model).
 * Cloud configs are managed as a saved-profile list; add/edit happen in a
 * shared [CloudModelConfigDialog]; each item can be enabled, disabled or deleted.
 */
class VlmConfigActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_vlm_config)

        val tc = ThemeManager.getColors()
        window.statusBarColor = tc.toolbarBg
        window.decorView.setBackgroundColor(tc.bg)
        val contentFrame = findViewById<android.view.ViewGroup>(android.R.id.content)
        contentFrame?.setBackgroundColor(tc.bg)
        (contentFrame?.getChildAt(0) as? View)?.setBackgroundColor(tc.bg)

        // Toolbar
        findViewById<CommonToolbar>(R.id.toolbar).apply {
            setTitle("Vision Model")
            showBackButton(true) { finish() }
            setBackgroundColor(tc.toolbarBg)
            setTitleColor(tc.aiText)
            findViewById<android.widget.ImageView>(R.id.ivBack)?.setColorFilter(tc.aiText)
        }

        // Views
        val tvStatus = findViewById<TextView>(R.id.tvVlmStatus)
        val tvStatusMeta = findViewById<TextView>(R.id.tvVlmStatusMeta)
        val layoutSavedProfiles = findViewById<LinearLayout>(R.id.layoutSavedVlmProfiles)
        val btnAddNew = findViewById<KButton>(R.id.btnAddNewVlmProfile)

        tvStatus.setTextColor(tc.aiText)
        tvStatusMeta.setTextColor(tc.toolDefault)

        // Migrate existing config into saved profiles (one-time)
        SavedVlmProfilesStore.migrateFromCurrentIfNeeded()

        updateStatusCard(tvStatus, tvStatusMeta, VlmConfigRepository.isConfigured())

        btnAddNew.setOnClickListener {
            val profiles = SavedVlmProfilesStore.listProfiles()
            if (profiles.size >= SavedProfileLimits.MAX_VLM_PROFILES) {
                Toast.makeText(this, "Max ${SavedProfileLimits.MAX_VLM_PROFILES} configs allowed. Delete one first.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            CloudModelConfigDialog.show(
                context = this,
                dialogTitle = "Add Vision Model",
                preset = null
            ) { input ->
                val profile = SavedVlmProfile(
                    id = SavedVlmProfilesStore.generateId(),
                    title = input.title,
                    apiKey = input.apiKey,
                    baseUrl = input.baseUrl,
                    modelName = input.modelName
                )
                SavedVlmProfilesStore.saveProfile(profile)
                Toast.makeText(this, "Config saved. Tap Enable to use it.", Toast.LENGTH_SHORT).show()
                recreate()
            }
        }

        renderSavedProfiles(layoutSavedProfiles, tc)
    }

    private fun renderSavedProfiles(container: LinearLayout, tc: ThemeManager.ChatColors) {
        container.removeAllViews()
        val profiles = SavedVlmProfilesStore.listProfiles()
        val activeId = SavedVlmProfilesStore.getActiveProfileId()

        profiles.forEach { profile ->
            val isActive = profile.id == activeId
            val card = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
                background = buildProfileCardBackground(isActive)
            }

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }

            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val titleRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            titleRow.addView(TextView(this).apply {
                text = profile.title
                textSize = 14f
                setTextColor(tc.aiText)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            info.addView(titleRow)
            info.addView(TextView(this).apply {
                text = "${profile.modelName} · ${profile.baseUrl}"
                textSize = 11f
                setTextColor(tc.toolDefault)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })

            val onToggle = {
                if (isActive) {
                    SavedVlmProfilesStore.disableActiveProfile()
                } else {
                    SavedVlmProfilesStore.enableProfile(profile.id)
                }
                recreate()
            }

            val selector = ImageView(this).apply {
                setImageResource(
                    if (isActive) R.drawable.icon_radio_selected
                    else R.drawable.icon_radio_unselected
                )
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply {
                    marginEnd = dp(12)
                }
                setOnClickListener { onToggle() }
            }
            card.setOnClickListener { onToggle() }

            val btnEdit = TextView(this).apply {
                text = "Edit"
                textSize = 12f
                setTextColor(getColor(R.color.colorBrandPrimary))
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setOnClickListener {
                    CloudModelConfigDialog.show(
                        context = this@VlmConfigActivity,
                        dialogTitle = "Edit Vision Model",
                        preset = CloudModelConfigDialog.CloudModelInput(
                            title = profile.title,
                            apiKey = profile.apiKey,
                            baseUrl = profile.baseUrl,
                            modelName = profile.modelName
                        )
                    ) { input ->
                        val updatedProfile = profile.copy(
                            title = input.title,
                            apiKey = input.apiKey,
                            baseUrl = input.baseUrl,
                            modelName = input.modelName
                        )
                        SavedVlmProfilesStore.updateProfile(updatedProfile)
                        SavedVlmProfilesStore.refreshActiveIfMatching(profile.id)
                        Toast.makeText(this@VlmConfigActivity, "Config updated.", Toast.LENGTH_SHORT).show()
                        recreate()
                    }
                }
            }

            val btnDelete = TextView(this).apply {
                text = "Delete"
                textSize = 12f
                setTextColor(getColor(R.color.colorErrorPrimary))
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setOnClickListener {
                    AlertDialog.showWarm(
                        context = this@VlmConfigActivity,
                        title = "Delete Config",
                        message = "Delete \"${profile.title}\"? This cannot be undone.",
                        actionTitle = "Delete",
                        cancelTitle = getString(R.string.common_cancel),
                        onAction = {
                            SavedVlmProfilesStore.deleteProfile(profile.id)
                            recreate()
                        }
                    )
                }
            }

            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            actions.addView(btnEdit)
            actions.addView(btnDelete)

            row.addView(selector)
            row.addView(info)
            row.addView(actions)
            card.addView(row)
            container.addView(card)
        }
    }

    private fun buildProfileCardBackground(isActive: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(8).toFloat()
            setColor(
                getColor(if (isActive) R.color.colorBrandContainer else R.color.colorContainerBrighten)
            )
            setStroke(
                dp(1),
                getColor(if (isActive) R.color.colorBrandPrimary else R.color.colorBorderBase)
            )
        }
    }

    private fun updateStatusCard(
        tvStatus: TextView,
        tvStatusMeta: TextView,
        isConfigured: Boolean
    ) {
        if (isConfigured) {
            val config = VlmConfigRepository.load()
            tvStatus.text = config.modelName
            tvStatusMeta.text = "Enabled · ${config.baseUrl}"
        } else {
            tvStatus.text = "Not configured"
            tvStatusMeta.text = "Add a vision model config and enable it"
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
