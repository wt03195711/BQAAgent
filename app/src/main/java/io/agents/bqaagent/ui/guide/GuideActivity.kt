// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.ui.guide

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import io.agents.bqaagent.R
import io.agents.bqaagent.AppCapabilityCoordinator
import io.agents.bqaagent.AppRequirement
import io.agents.bqaagent.base.BaseActivity
import io.agents.bqaagent.utils.KVUtils

class GuideActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_guide)

        bindSection(
            findViewById(R.id.guideLocalAdb),
            R.drawable.ic_local_adb,
            R.string.guide_title_local_adb,
            R.string.guide_desc_local_adb
        ) {
            AppCapabilityCoordinator.openSystemSettings(this, AppRequirement.LOCAL_ADB)
            Toast.makeText(this, R.string.home_enable_local_adb, Toast.LENGTH_LONG).show()
        }
        bindSection(
            findViewById(R.id.guideNotification),
            R.drawable.ic_notification,
            R.string.guide_title_notification,
            R.string.guide_desc_notification
        ) {
            if (!AppCapabilityCoordinator.isNotificationPermissionGranted(this)) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100)
                }
            } else {
                Toast.makeText(this, R.string.home_notification_enabled, Toast.LENGTH_SHORT).show()
            }
        }
        bindSection(
            findViewById(R.id.guideOverlay),
            R.drawable.ic_window,
            R.string.guide_title_overlay,
            R.string.guide_desc_overlay
        ) {
            if (!AppCapabilityCoordinator.snapshot(this).overlayGranted) {
                AppCapabilityCoordinator.openSystemSettings(this, AppRequirement.OVERLAY)
            }
        }
        bindSection(
            findViewById(R.id.guideBattery),
            R.drawable.ic_battery,
            R.string.guide_title_battery,
            R.string.guide_desc_battery
        ) {
            AppCapabilityCoordinator.openSystemSettings(this, AppRequirement.BATTERY_OPTIMIZATION)
        }
        bindSection(
            findViewById(R.id.guideStorage),
            R.drawable.ic_storage,
            R.string.guide_title_storage,
            R.string.guide_desc_storage
        ) {
            AppCapabilityCoordinator.openSystemSettings(this, AppRequirement.STORAGE)
        }

        findViewById<View>(R.id.btnStart).setOnClickListener { finishGuide() }
        findViewById<View>(R.id.tvSkip).setOnClickListener { finishGuide() }
    }

    private fun bindSection(view: View, iconRes: Int, titleRes: Int, descRes: Int, onClick: () -> Unit) {
        view.findViewById<ImageView>(R.id.ivIcon).setImageResource(iconRes)
        view.findViewById<TextView>(R.id.tvTitle).setText(titleRes)
        view.findViewById<TextView>(R.id.tvDescription).setText(descRes)
        view.setOnClickListener { onClick() }
    }

    private fun finishGuide() {
        KVUtils.setGuideShown(true)
        finish()
    }
}
