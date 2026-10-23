// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.ui.settings

import android.os.Bundle
import android.graphics.drawable.GradientDrawable
import android.widget.FrameLayout
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.cardview.widget.CardView
import io.agents.bqaagent.ClawApplication
import io.agents.bqaagent.R
import io.agents.bqaagent.agent.CloudProvider
import io.agents.bqaagent.agent.llm.ActiveModelMode
import io.agents.bqaagent.agent.llm.LocalModelManager
import io.agents.bqaagent.agent.llm.ModelConfigRepository
import io.agents.bqaagent.agent.llm.SavedLlmProfilesStore
import io.agents.bqaagent.agent.llm.SavedModelProfile
import io.agents.bqaagent.agent.llm.SavedProfileLimits
import io.agents.bqaagent.base.BaseActivity
import io.agents.bqaagent.ui.chat.ThemeManager
import io.agents.bqaagent.utils.KVUtils
import io.agents.bqaagent.widget.AlertDialog
import io.agents.bqaagent.widget.CloudModelConfigDialog
import io.agents.bqaagent.widget.CommonToolbar
import io.agents.bqaagent.widget.KButton
import java.util.concurrent.Executors

class LlmConfigActivity : BaseActivity() {

    private val executor = Executors.newSingleThreadExecutor()
    private var isDownloading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_llm_config)

        // Apply dark theme from ThemeManager to match rest of app
        val tc = ThemeManager.getColors()
        window.statusBarColor = tc.toolbarBg
        window.decorView.setBackgroundColor(tc.bg)
        val contentFrame = findViewById<android.view.ViewGroup>(android.R.id.content)
        contentFrame?.setBackgroundColor(tc.bg)
        (contentFrame?.getChildAt(0) as? View)?.setBackgroundColor(tc.bg)

        findViewById<CommonToolbar>(R.id.toolbar).apply {
            setTitle("Models")
            showBackButton(true) { finish() }
            setBackgroundColor(tc.toolbarBg)
            setTitleColor(tc.aiText)
            findViewById<android.widget.ImageView>(R.id.ivBack)?.setColorFilter(tc.aiText)
        }

        val models = LocalModelManager.AVAILABLE_MODELS
        val activeModelName = findViewById<TextView>(R.id.tvActiveModelName)
        val activeModelMeta = findViewById<TextView>(R.id.tvActiveModelMeta)
        val activeModelStatus = findViewById<TextView>(R.id.tvActiveModelStatus)
        val defaultLocalName = findViewById<TextView>(R.id.tvDefaultLocalName)
        val defaultLocalMeta = findViewById<TextView>(R.id.tvDefaultLocalMeta)
        val defaultLocalStatus = findViewById<TextView>(R.id.tvDefaultLocalStatus)
        val defaultCloudName = findViewById<TextView>(R.id.tvDefaultCloudName)
        val defaultCloudMeta = findViewById<TextView>(R.id.tvDefaultCloudMeta)
        val defaultCloudStatus = findViewById<TextView>(R.id.tvDefaultCloudStatus)
        val modelList = findViewById<LinearLayout>(R.id.layoutModelList)
        // Migrate legacy cloud config to a saved profile BEFORE rendering, so the
        // "enabled profile" gate below reflects the true state even on first launch.
        SavedLlmProfilesStore.migrateFromCurrentIfNeeded()
        val resolvedConfig = ModelConfigRepository.snapshot()
        val activeProfileId = SavedLlmProfilesStore.getActiveProfileId()
        val hasEnabledProfile = activeProfileId.isNotBlank() &&
                SavedLlmProfilesStore.listProfiles().any { it.id == activeProfileId }
        val deviceSupport = LocalModelManager.deviceSupport(this)
        val catalog = LocalModelManager.catalog(this).associateBy { it.model.id }

        // Apply theme to active model card text
        activeModelName.setTextColor(tc.aiText)
        activeModelMeta.setTextColor(tc.toolDefault)
        defaultLocalName.setTextColor(tc.aiText)
        defaultLocalMeta.setTextColor(tc.toolDefault)
        defaultCloudName.setTextColor(tc.aiText)
        defaultCloudMeta.setTextColor(tc.toolDefault)

        // Apply theme to all CardViews in XML layout
        val scrollContent = findViewById<LinearLayout>(R.id.layoutModelList)?.parent as? LinearLayout
        if (scrollContent != null) {
            for (i in 0 until scrollContent.childCount) {
                val child = scrollContent.getChildAt(i)
                if (child is TextView && child.id == View.NO_ID) {
                    // Section headers ("Active Model", "Available Models", "Cloud LLM")
                    child.setTextColor(tc.toolDefault)
                }
                if (child is CardView) {
                    child.setCardBackgroundColor(tc.toolbarBg)
                }
            }
        }

        // Active model — show what is ACTUALLY active based on provider
        if (resolvedConfig.activeMode == ActiveModelMode.LOCAL) {
            val activeState = LocalModelManager.resolveActiveModelState(this, resolvedConfig.local)
            activeModelName.text = activeState.displayName
            activeModelMeta.text = activeState.metaText
            activeModelStatus.text = activeState.statusText
            activeModelStatus.setTextColor(
                when (activeState.statusKind) {
                    LocalModelManager.StatusKind.WARNING -> getColor(R.color.colorWarningPrimary)
                    else -> tc.toolDefault
                }
            )
        } else {
            val cloudModel = if (hasEnabledProfile) resolvedConfig.activeCloud.modelName else ""
            if (cloudModel.isNotEmpty()) {
                activeModelName.text = cloudModel
                val providerName = resolvedConfig.activeCloud.provider.displayName
                activeModelMeta.text = "$providerName · Cloud"
                activeModelStatus.text = "Connected"
                activeModelStatus.setTextColor(tc.toolDefault)
            } else {
                activeModelName.text = "No model selected"
                activeModelMeta.text = "Configure a cloud model below"
                activeModelStatus.text = "Not configured"
                activeModelStatus.setTextColor(tc.toolDefault)
            }
        }

        val defaultLocalState = LocalModelManager.resolveActiveModelState(this, resolvedConfig.local)
        defaultLocalName.text = defaultLocalState.displayName
        defaultLocalMeta.text = defaultLocalState.metaText
        defaultLocalStatus.text = defaultLocalState.statusText
        defaultLocalStatus.setTextColor(
            when (defaultLocalState.statusKind) {
                LocalModelManager.StatusKind.WARNING -> getColor(R.color.colorWarningPrimary)
                else -> tc.toolDefault
            }
        )

        if (hasEnabledProfile && resolvedConfig.defaultCloud.isConfigured) {
            defaultCloudName.text = resolvedConfig.defaultCloud.modelName
            defaultCloudMeta.text = "${resolvedConfig.defaultCloud.provider.displayName} · Cloud"
            defaultCloudStatus.text = "Ready"
            defaultCloudStatus.setTextColor(tc.toolDefault)
        } else {
            defaultCloudName.text = "No default cloud model"
            defaultCloudMeta.text = "Add a cloud model and enable it"
            defaultCloudStatus.text = "Not configured"
            defaultCloudStatus.setTextColor(tc.toolDefault)
        }

        val activeLocalModelId = if (resolvedConfig.activeMode == ActiveModelMode.LOCAL) resolvedConfig.local.modelId else ""
        val defaultLocalModelId = resolvedConfig.local.modelId
        val configuredBuiltInLocal = LocalModelManager.configuredBuiltInModel(resolvedConfig.local)

        // Build model list
        models.forEach { model ->
            val modelEntry = catalog[model.id]
            val availability = LocalModelManager.availabilityForModel(this, model, resolvedConfig.local)
            val downloaded = availability.isAvailable
            val isActive = model.id == activeLocalModelId
            val isDefaultLocal = model.id == defaultLocalModelId
            val supportedOnDevice = modelEntry?.isSupported == true
            val resolvedLocalPath = when (availability.source) {
                LocalModelManager.AvailabilitySource.MANAGED_DOWNLOAD -> LocalModelManager.getModelPath(this, model)
                LocalModelManager.AvailabilitySource.LINKED_FILE ->
                    if (configuredBuiltInLocal?.id == model.id) resolvedConfig.local.modelPath else null
                LocalModelManager.AvailabilitySource.MISSING -> null
            }

            val card = CardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6) }
                radius = 0f
                cardElevation = dp(1).toFloat()
                setCardBackgroundColor(tc.toolbarBg)
            }

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
            }

            // Model info
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val nameTV = TextView(this).apply {
                text = model.displayName
                textSize = 14f
                setTextColor(tc.aiText)
                if (isActive) setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            info.addView(nameTV)

            val descTV = TextView(this).apply {
                val baseText = "${model.sizeBytes / 1_000_000} MB · ${model.minRamGb}GB+ RAM"
                text = if (supportedOnDevice) baseText else "$baseText · This phone reports ${deviceSupport.deviceRamGb}GB"
                textSize = 12f
                setTextColor(if (supportedOnDevice) tc.toolDefault else getColor(R.color.colorWarningPrimary))
            }
            info.addView(descTV)

            row.addView(info)

            // Action button
            if (downloaded) {
                if (isActive) {
                    val check = TextView(this).apply {
                        text = if (supportedOnDevice) "Active" else "Active"
                        textSize = 12f
                        setTextColor(if (supportedOnDevice) tc.toolDefault else getColor(R.color.colorWarningPrimary))
                    }
                    row.addView(check)
                } else {
                    if (isDefaultLocal) {
                        row.addView(TextView(this).apply {
                            text = "Default"
                            textSize = 12f
                            setTextColor(tc.toolDefault)
                            setPadding(dp(12), dp(6), dp(12), dp(6))
                        })
                    }
                    if (supportedOnDevice) {
                        val useBtn = TextView(this).apply {
                            text = "Use"
                            textSize = 13f
                            setTextColor(getColor(R.color.colorTextSecondary))
                            setPadding(dp(12), dp(6), dp(12), dp(6))
                            setOnClickListener {
                                val path = resolvedLocalPath
                                if (path != null) {
                                    // Save as default local model (independent of cloud config)
                                    // Only switch active provider if currently on local tab
                                    val shouldActivateLocal = ModelConfigRepository.isLocalActive() || !KVUtils.hasDefaultCloudModel()
                                    ModelConfigRepository.saveLocalDefault(path, model.id, shouldActivateLocal)
                                    ClawApplication.appViewModelInstance.updateAgentConfig()
                                    ClawApplication.appViewModelInstance.initAgent()
                                    Toast.makeText(this@LlmConfigActivity, "Set default local: ${model.displayName}", Toast.LENGTH_SHORT).show()
                                    recreate()
                                } else {
                                    Toast.makeText(this@LlmConfigActivity, "Model file not found", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        row.addView(useBtn)
                    } else {
                        row.addView(TextView(this).apply {
                            text = "Needs ${model.minRamGb}GB+"
                            textSize = 12f
                            setTextColor(getColor(R.color.colorWarningPrimary))
                            setPadding(dp(12), dp(6), dp(12), dp(6))
                        })
                    }

                    if (availability.source == LocalModelManager.AvailabilitySource.MANAGED_DOWNLOAD) {
                        val delBtn = TextView(this).apply {
                            text = "Delete"
                            textSize = 12f
                            setTextColor(getColor(R.color.colorErrorPrimary))
                            setPadding(dp(8), dp(4), dp(4), dp(4))
                            alpha = 0.7f
                            setOnClickListener {
                                LocalModelManager.deleteModel(this@LlmConfigActivity, model)
                                Toast.makeText(this@LlmConfigActivity, "Deleted ${model.displayName}", Toast.LENGTH_SHORT).show()
                                recreate()
                            }
                        }
                        row.addView(delBtn)
                    }
                }
            } else {
                if (supportedOnDevice) {
                    val dlBtn = TextView(this).apply {
                        text = "Download"
                        textSize = 13f
                        setTextColor(tc.toolDefault)
                        setPadding(dp(12), dp(6), dp(12), dp(6))
                        setOnClickListener {
                            if (isDownloading) {
                                Toast.makeText(this@LlmConfigActivity, "Already downloading", Toast.LENGTH_SHORT).show()
                                return@setOnClickListener
                            }
                            isDownloading = true
                            text = "Downloading..."
                            isEnabled = false

                            executor.submit {
                                LocalModelManager.downloadModel(this@LlmConfigActivity, model, object : LocalModelManager.DownloadCallback {
                                    override fun onProgress(bytesDownloaded: Long, totalBytes: Long, bytesPerSecond: Long) {
                                        val pct = if (totalBytes > 0) (bytesDownloaded * 100 / totalBytes).toInt() else 0
                                        runOnUiThread { text = "$pct%" }
                                    }
                                    override fun onComplete(modelPath: String) {
                                        runOnUiThread {
                                            ModelConfigRepository.saveLocalDefault(
                                                modelPath = modelPath,
                                                modelId = model.id,
                                                activateNow = false
                                            )
                                            isDownloading = false
                                            Toast.makeText(this@LlmConfigActivity, "Downloaded!", Toast.LENGTH_SHORT).show()
                                            recreate()
                                        }
                                    }
                                    override fun onError(error: String) {
                                        runOnUiThread {
                                            isDownloading = false
                                            text = "Download"
                                            isEnabled = true
                                            Toast.makeText(this@LlmConfigActivity, error, Toast.LENGTH_LONG).show()
                                        }
                                    }
                                })
                            }
                        }
                    }
                    row.addView(dlBtn)
                } else {
                    row.addView(TextView(this).apply {
                        text = "Needs ${model.minRamGb}GB+"
                        textSize = 12f
                        setTextColor(getColor(R.color.colorWarningPrimary))
                        setPadding(dp(12), dp(6), dp(12), dp(6))
                    })
                }
            }

            card.addView(row)
            modelList.addView(card)
        }

        // Storage info
        updateStorageInfo()

        // Cloud LLM — add button opens the shared dialog
        setupCloudLlm(tc)

        // Saved profiles rendering
        setupSavedProfiles(tc)
    }

    private fun updateStorageInfo() {
        val models = LocalModelManager.AVAILABLE_MODELS
        var totalSize = 0L
        var count = 0
        models.forEach { model ->
            if (LocalModelManager.isModelDownloaded(this, model)) {
                totalSize += model.sizeBytes
                count++
            }
        }
        val mbUsed = totalSize / 1_000_000
        val allocated = 4000L // 4GB rough estimate
        val pct = (mbUsed * 100 / allocated).toInt().coerceAtMost(100)

        findViewById<TextView>(R.id.tvStorageInfo).text = "$count model${if (count != 1) "s" else ""} · ${mbUsed} MB"
        findViewById<ProgressBar>(R.id.progressStorage).progress = pct
        findViewById<TextView>(R.id.tvStorageDetail).text = "${mbUsed} MB of ${allocated} MB allocated"
    }

    private fun setupCloudLlm(tc: ThemeManager.ChatColors) {
        val btnAdd = findViewById<KButton>(R.id.btnAddNewProfile)
        btnAdd.setOnClickListener {
            if (SavedLlmProfilesStore.listProfiles().size >= SavedProfileLimits.MAX_LLM_PROFILES) {
                Toast.makeText(this, "Max ${SavedProfileLimits.MAX_LLM_PROFILES} configs allowed. Delete one first.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            CloudModelConfigDialog.show(
                context = this,
                dialogTitle = "Add Cloud Model",
                preset = null
            ) { input ->
                val profile = SavedModelProfile(
                    id = SavedLlmProfilesStore.generateId(),
                    title = input.title,
                    providerName = CloudProvider.CUSTOM.name,
                    modelName = input.modelName,
                    baseUrl = input.baseUrl,
                    apiKey = input.apiKey
                )
                SavedLlmProfilesStore.saveProfile(profile)
                Toast.makeText(this, "Config saved. Tap Enable to use it.", Toast.LENGTH_SHORT).show()
                recreate()
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun setupSavedProfiles(tc: ThemeManager.ChatColors) {
        val container = findViewById<LinearLayout>(R.id.layoutSavedProfiles)
        container.removeAllViews()
        val profiles = SavedLlmProfilesStore.listProfiles()
        val activeId = SavedLlmProfilesStore.getActiveProfileId()

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
                    SavedLlmProfilesStore.disableActiveProfile()
                } else {
                    SavedLlmProfilesStore.enableProfile(profile.id)
                }
                rebindAgent()
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
                        context = this@LlmConfigActivity,
                        dialogTitle = "Edit Cloud Model",
                        preset = CloudModelConfigDialog.CloudModelInput(
                            title = profile.title,
                            apiKey = profile.apiKey,
                            baseUrl = profile.baseUrl,
                            modelName = profile.modelName
                        )
                    ) { input ->
                        val updatedProfile = profile.copy(
                            title = input.title,
                            providerName = CloudProvider.CUSTOM.name,
                            modelName = input.modelName,
                            baseUrl = input.baseUrl,
                            apiKey = input.apiKey
                        )
                        SavedLlmProfilesStore.updateProfile(updatedProfile)
                        SavedLlmProfilesStore.refreshActiveIfMatching(profile.id)
                        rebindAgent()
                        Toast.makeText(this@LlmConfigActivity, "Config updated.", Toast.LENGTH_SHORT).show()
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
                        context = this@LlmConfigActivity,
                        title = "Delete Config",
                        message = "Delete \"${profile.title}\"? This cannot be undone.",
                        actionTitle = "Delete",
                        cancelTitle = getString(R.string.common_cancel),
                        onAction = {
                            SavedLlmProfilesStore.deleteProfile(profile.id)
                            rebindAgent()
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

    private fun rebindAgent() {
        ClawApplication.appViewModelInstance.updateAgentConfig()
        ClawApplication.appViewModelInstance.initAgent()
        ClawApplication.appViewModelInstance.afterInit()
    }

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
