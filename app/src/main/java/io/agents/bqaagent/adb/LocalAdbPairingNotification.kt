// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.adb

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import io.agents.bqaagent.R
import io.agents.bqaagent.ui.chat.ComposeChatActivity
import io.agents.bqaagent.utils.XLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object LocalAdbPairingNotification {
    private const val TAG = "LocalAdbPairingNotification"
    private const val CHANNEL_ID = "BQAAgent_local_adb_pairing_v2"
    private const val NOTIFICATION_ID = 1801

    const val ACTION_SUBMIT = "io.agents.bqaagent.LOCAL_ADB_PAIR_SUBMIT"
    const val ACTION_OPEN_SETTINGS = "io.agents.bqaagent.LOCAL_ADB_PAIR_OPEN_SETTINGS"
    const val KEY_PAIRING_INPUT = "local_adb_pairing_input"

    @JvmStatic
    fun show(context: Context): Boolean {
        if (!hasNotificationPermission(context)) return false
        createChannel(context)
        return runCatching {
            showInputNotification(context, context.getString(R.string.local_adb_notification_text))
        }.onFailure {
            XLog.w(TAG, "Failed to show Local ADB pairing notification", it)
        }.isSuccess
    }

    @JvmStatic
    fun cancel(context: Context) {
        runCatching { manager(context).cancel(NOTIFICATION_ID) }
    }

    internal fun showStatus(context: Context, title: String, text: String, ongoing: Boolean) {
        if (!hasNotificationPermission(context)) return
        createChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setColor(ContextCompat.getColor(context, R.color.colorBrandPrimary))
            .setColorized(true)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setOnlyAlertOnce(true)
            .build()
        runCatching { manager(context).notify(NOTIFICATION_ID, notification) }
            .onFailure { XLog.w(TAG, "Failed to update Local ADB pairing notification", it) }
    }

    internal fun showInputNotification(context: Context, text: String) {
        if (!hasNotificationPermission(context)) return
        createChannel(context)
        manager(context).notify(NOTIFICATION_ID, buildPairingNotification(context, text))
    }

    private fun buildPairingNotification(context: Context, text: String): Notification {
        val submitIntent = Intent(context, LocalAdbPairingReceiver::class.java).apply {
            action = ACTION_SUBMIT
        }
        val submitPendingIntent = PendingIntent.getBroadcast(
            context,
            1,
            submitIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or mutableFlag()
        )
        val remoteInput = RemoteInput.Builder(KEY_PAIRING_INPUT)
            .setLabel(context.getString(R.string.local_adb_notification_input_label))
            .build()
        val submitAction = NotificationCompat.Action.Builder(
            R.drawable.ic_check,
            context.getString(R.string.local_adb_notification_pair_action),
            submitPendingIntent
        ).addRemoteInput(remoteInput).build()

        val settingsIntent = Intent(context, LocalAdbPairingReceiver::class.java).apply {
            action = ACTION_OPEN_SETTINGS
        }
        val settingsAction = NotificationCompat.Action.Builder(
            R.drawable.ic_settings,
            context.getString(R.string.local_adb_notification_open_settings),
            PendingIntent.getBroadcast(
                context,
                2,
                settingsIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        ).build()

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.local_adb_notification_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setColor(ContextCompat.getColor(context, R.color.colorBrandPrimary))
            .setColorized(true)
            .addAction(settingsAction)
            .addAction(submitAction)
            .build()
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.local_adb_notification_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.local_adb_notification_channel_description)
            setShowBadge(false)
            enableLights(true)
            lightColor = ContextCompat.getColor(context, R.color.colorBrandPrimary)
        }
        manager(context).createNotificationChannel(channel)
    }

    private fun manager(context: Context): NotificationManager {
        return context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    private fun hasNotificationPermission(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun mutableFlag(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
    }
}

class LocalAdbPairingReceiver : BroadcastReceiver() {
    private companion object {
        private const val TAG = "LocalAdbPairingReceiver"
        private val pairingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        when (action) {
            LocalAdbPairingNotification.ACTION_OPEN_SETTINGS -> {
                LocalAdbAutomation.openWirelessDebuggingSettings(context.applicationContext)
            }
            LocalAdbPairingNotification.ACTION_SUBMIT -> {
                handlePairingInput(context.applicationContext, intent)
            }
        }
    }

    private fun handlePairingInput(context: Context, intent: Intent) {
        val input = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(LocalAdbPairingNotification.KEY_PAIRING_INPUT)
            ?.toString()
            .orEmpty()
        val defaultHost = LocalAdbHostResolver.defaultPairingHost(context)
        val parsed = LocalAdbAutomation.parsePairingInput(input, defaultHost)
        if (parsed == null) {
            LocalAdbPairingNotification.showInputNotification(
                context,
                context.getString(R.string.local_adb_notification_invalid_input),
            )
            return
        }

        val pendingResult = goAsync()
        LocalAdbPairingNotification.showStatus(
            context,
            context.getString(R.string.local_adb_notification_title),
            context.getString(R.string.local_adb_notification_pairing),
            ongoing = true,
        )
        pairingScope.launch {
            try {
                XLog.i(TAG, "Local ADB notification pairing started; hasPairPort=${parsed.pairPort != null}")
                val pairPort = parsed.pairPort
                val result = if (pairPort != null) {
                    LocalAdbAutomation.pairAndConfigureByDiscovery(
                        context,
                        parsed.host,
                        pairPort,
                        parsed.pairingCode,
                    )
                } else {
                    LocalAdbAutomation.pairAndConfigureByPairingCodeDiscovery(
                        context,
                        parsed.host,
                        parsed.pairingCode,
                    )
                }
                if (result.isSuccess) {
                    LocalAdbPairingNotification.showStatus(
                        context = context,
                        title = context.getString(R.string.local_adb_setup_success),
                        text = context.getString(R.string.local_adb_notification_success_text),
                        ongoing = false,
                    )
                    returnToBqaAgent(context)
                } else {
                    LocalAdbPairingNotification.showInputNotification(
                        context,
                        result.combinedOutput.ifBlank { context.getString(R.string.local_adb_setup_failed) },
                    )
                }
            } catch (e: Throwable) {
                XLog.w(TAG, "Local ADB notification pairing failed", e)
                LocalAdbPairingNotification.showInputNotification(
                    context,
                    e.message?.takeIf { it.isNotBlank() } ?: context.getString(R.string.local_adb_setup_failed),
                )
            }
        }
        pendingResult.finish()
    }

    private fun returnToBqaAgent(context: Context) {
        runCatching {
            val intent = Intent(context, ComposeChatActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            context.startActivity(intent)
        }.onFailure {
            XLog.w(TAG, "Failed to return to BQAAgent after Local ADB pairing", it)
        }
    }
}
