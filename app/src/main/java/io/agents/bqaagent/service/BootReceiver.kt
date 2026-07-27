// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.agents.bqaagent.utils.XLog

/**
 * Boot broadcast receiver retained for future restart hooks.
 * BQAAgent no longer starts a persistent foreground notification on boot.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            XLog.i(TAG, "Boot broadcast received, no foreground service needed at boot")
        }
    }
}
