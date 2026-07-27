// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import io.agents.bqaagent.ClawApplication
import io.agents.bqaagent.R
import io.agents.bqaagent.agent.llm.ActiveModelMode
import io.agents.bqaagent.agent.llm.ModelConfigRepository
import io.agents.bqaagent.server.ConfigServerManager
import io.agents.bqaagent.utils.KVUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * ViewModel for SettingsActivity
 */
class SettingsViewModel : ViewModel() {

    // Settings data Flow (for dynamic updates)
    private val _settingItems = MutableStateFlow<Map<String, SettingValue>>(emptyMap())
    val settingItems: StateFlow<Map<String, SettingValue>> = _settingItems

    // Menu click event
    private val _menuClickEvent = MutableStateFlow<MenuAction?>(null)
    val menuClickEvent: StateFlow<MenuAction?> = _menuClickEvent

    init {
        refresh()
    }

    fun refresh() {
        val map = mapOf(
            MenuAction.LLM_CONFIG.name to SettingValue.Text(getActiveModelDisplayName()),
            MenuAction.VLM_CONFIG.name to SettingValue.Text(getVlmStatusText()),
            MenuAction.LAN_CONFIG.name to SettingValue.Text(getLanConfigTrailingText())
        )
        _settingItems.value = map
    }

    /** Show the actual active model, not stale shared key. */
    private fun getActiveModelDisplayName(): String {
        val config = ModelConfigRepository.snapshot()
        val app = ClawApplication.instance
        return if (config.activeMode == ActiveModelMode.LOCAL) {
            val path = config.local.modelPath
            if (path.isNotEmpty() && java.io.File(path).exists()) {
                config.local.displayName + " · Local"
            } else {
                app.getString(R.string.common_unconfigured)
            }
        } else {
            val cloudModel = config.activeCloud.modelName
            if (cloudModel.isNotEmpty()) {
                "$cloudModel · Cloud"
            } else {
                app.getString(R.string.common_unconfigured)
            }
        }
    }

    private fun getVlmStatusText(): String {
        return if (io.agents.bqaagent.agent.VlmConfigRepository.isConfigured()) {
            val config = io.agents.bqaagent.agent.VlmConfigRepository.load()
            "${config.modelName} · Vision"
        } else {
            ClawApplication.instance.getString(R.string.common_unconfigured)
        }
    }

    /**
     * Update a setting value
     */
    fun updateSettingValue(key: String, value: SettingValue) {
        _settingItems.value = _settingItems.value.toMutableMap().apply {
            put(key, value)
        }
    }

    /**
     * Update trailing text
     */
    fun updateTrailingText(key: String, text: String) {
        updateSettingValue(key, SettingValue.Text(text))
    }

    /**
     * Handle menu item click
     */
    fun onMenuItemClick(action: MenuAction) {
        _menuClickEvent.value = action
    }

    /**
     * Clear menu click event
     */
    fun clearMenuClickEvent() {
        _menuClickEvent.value = null
    }

    /**
     * Toggle LAN config server on/off
     */
    fun toggleConfigServer(context: Context): String {
        return if (ConfigServerManager.isRunning()) {
            ConfigServerManager.stop()
            KVUtils.setConfigServerEnabled(false)
            val text = getLanConfigTrailingText()
            updateTrailingText(MenuAction.LAN_CONFIG.name, text)
            text
        } else {
            val started = ConfigServerManager.start(context)
            if (started) {
                KVUtils.setConfigServerEnabled(true)
                val text = getLanConfigTrailingText()
                updateTrailingText(MenuAction.LAN_CONFIG.name, text)
                text
            } else {
                ClawApplication.instance.getString(R.string.lan_config_no_wifi)
            }
        }
    }

    private fun getLanConfigTrailingText(): String {
        return if (ConfigServerManager.isRunning()) {
            ConfigServerManager.getAddress() ?: ClawApplication.instance.getString(R.string.lan_config_stopped)
        } else {
            ClawApplication.instance.getString(R.string.lan_config_stopped)
        }
    }

    /**
     * Sealed class for setting values
     */
    sealed class SettingValue {
        data class Text(val text: String) : SettingValue()
        data class Switch(val isOn: Boolean) : SettingValue()
    }

    /**
     * Menu action enum
     */
    enum class MenuAction {
        LAN_CONFIG,
        LLM_CONFIG,
        VLM_CONFIG
    }
}
