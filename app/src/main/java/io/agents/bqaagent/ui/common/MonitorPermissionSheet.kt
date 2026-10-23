// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.agents.bqaagent.AppCapabilityCoordinator
import io.agents.bqaagent.AppRequirement
import io.agents.bqaagent.R
import io.agents.bqaagent.ServiceBindingState
import io.agents.bqaagent.ui.chat.PokeclawColors
import kotlinx.coroutines.delay

/**
 * Non-blocking permission guide for monitor mode. Shown when the user starts monitoring
 * while required capabilities are missing — instead of failing the task, we remember the
 * target and auto-resume monitoring as soon as every required row turns ready.
 *
 * Rows: Local ADB / Notification Access / Notifications are required; Battery optimization
 * is advisory (never checked or requested automatically, never gates Start).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonitorPermissionSheet(
    colors: PokeclawColors,
    targetLabel: String,
    onRequestNotificationPermission: () -> Unit,
    onOpenRequirement: (AppRequirement) -> Unit,
    onStart: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var snapshot by remember { mutableStateOf(AppCapabilityCoordinator.snapshot(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            snapshot = AppCapabilityCoordinator.snapshot(context)
            delay(800)
        }
    }

    val adbReady = snapshot.localAdbState == ServiceBindingState.READY
    val accessReady = snapshot.notificationAccessState == ServiceBindingState.READY
    val notifReady = snapshot.notificationPermissionGranted
    val batteryReady = snapshot.batteryOptimizationIgnored
    val allRequiredReady = adbReady && accessReady && notifReady

    // Breakpoint resume: once everything required is granted (e.g. the user comes back from
    // system settings), continue the original startMonitor call without another tap.
    LaunchedEffect(allRequiredReady) {
        if (allRequiredReady) onStart()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
        ) {
            Text(
                stringResource(R.string.monitor_guide_title),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary,
            )
            if (targetLabel.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.monitor_guide_subtitle, targetLabel),
                    fontSize = 13.sp,
                    color = colors.textSecondary,
                )
            }
            Spacer(Modifier.height(12.dp))

            MonitorGuideRow(
                colors = colors,
                title = stringResource(R.string.monitor_guide_row_adb_title),
                description = stringResource(R.string.monitor_guide_row_adb_desc),
                ready = adbReady,
                actionLabel = stringResource(R.string.monitor_guide_action_setup),
                onAction = { onOpenRequirement(AppRequirement.LOCAL_ADB) },
            )
            MonitorGuideRow(
                colors = colors,
                title = stringResource(R.string.settings_perm_notif_access_title),
                description = stringResource(R.string.monitor_guide_row_notif_access_desc),
                ready = accessReady,
                actionLabel = stringResource(R.string.monitor_guide_action_setup),
                onAction = { onOpenRequirement(AppRequirement.NOTIFICATION_ACCESS) },
            )
            MonitorGuideRow(
                colors = colors,
                title = stringResource(R.string.monitor_guide_row_notif_perm_title),
                description = stringResource(R.string.monitor_guide_row_notif_perm_desc),
                ready = notifReady,
                actionLabel = stringResource(R.string.monitor_guide_action_allow),
                onAction = onRequestNotificationPermission,
            )
            MonitorGuideRow(
                colors = colors,
                title = stringResource(R.string.monitor_guide_row_battery_title),
                description = stringResource(R.string.monitor_guide_row_battery_desc),
                ready = batteryReady,
                actionLabel = stringResource(R.string.monitor_guide_action_setup),
                optional = true,
                onAction = { onOpenRequirement(AppRequirement.BATTERY_OPTIMIZATION) },
            )

            Spacer(Modifier.height(14.dp))
            if (!allRequiredReady) {
                Text(
                    stringResource(R.string.monitor_guide_waiting),
                    fontSize = 12.sp,
                    color = colors.textTertiary,
                )
                Spacer(Modifier.height(10.dp))
            }
            Surface(
                onClick = { if (allRequiredReady) onStart() },
                enabled = allRequiredReady,
                color = if (allRequiredReady) colors.accent else colors.divider,
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(vertical = 14.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Text(
                        stringResource(R.string.monitor_guide_start),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (allRequiredReady) Color.White else colors.textTertiary,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MonitorGuideRow(
    colors: PokeclawColors,
    title: String,
    description: String,
    ready: Boolean,
    actionLabel: String,
    optional: Boolean = false,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.textPrimary,
                )
                if (optional && !ready) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.monitor_guide_optional),
                        fontSize = 11.sp,
                        color = colors.textTertiary,
                    )
                }
            }
            Text(
                description,
                fontSize = 12.sp,
                color = colors.textSecondary,
            )
        }
        Spacer(Modifier.width(12.dp))
        if (ready) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(R.string.perm_status_ready),
                fontSize = 12.sp,
                color = colors.accent,
            )
        } else {
            Surface(
                onClick = onAction,
                color = colors.background,
                shape = RoundedCornerShape(4.dp),
            ) {
                Text(
                    actionLabel,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.accent,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
        }
    }
}