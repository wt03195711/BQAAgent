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
                    if (KVUtils.isNotifPermAskedBefore() &&
                        !shouldShowRequestPermissionRationale(android.Manifest.permission.POST_NOTIFICATIONS)
                    ) {
                        // Permanently denied: mirror the Settings pairing gate escape hatch.
                        Toast.makeText(this, R.string.local_adb_pair_notif_perm_blocked, Toast.LENGTH_LONG).show()
                        AppCapabilityCoordinator.openAppNotificationSettings(this)
                    } else {
                        KVUtils.markNotifPermAsked()
                        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100)
                    }
                }
            } else {
                Toast.makeText(this, R.string.home_notification_enabled, Toast.LENGTH_SHORT).show()
            }
        }
        // Overlay / Battery / Storage are not pairing prerequisites and the features guide the
        // user in-context (floating switch, monitor sheet). Keep the folded rows out of the
        // first-run flow so the guide shows only the two required steps.
        findViewById<View>(R.id.guideOverlay).visibility = View.GONE
        findViewById<View>(R.id.guideBattery).visibility = View.GONE
        findViewById<View>(R.id.guideStorage).visibility = View.GONE

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
