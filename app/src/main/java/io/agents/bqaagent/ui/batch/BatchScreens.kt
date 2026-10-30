// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.ui.batch

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoNotDisturb
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.agents.bqaagent.BuildConfig
import io.agents.bqaagent.R
import io.agents.bqaagent.TaskReasonCode
import io.agents.bqaagent.TaskStatus
import io.agents.bqaagent.agent.skill.ExecutionTemplate
import io.agents.bqaagent.agent.skill.SkillDefinition
import io.agents.bqaagent.agent.skill.SkillMatcher
import io.agents.bqaagent.ui.chat.ChatHistoryManager
import io.agents.bqaagent.ui.chat.ChatMessage
import io.agents.bqaagent.ui.chat.CopyableMessageContainer
import io.agents.bqaagent.ui.chat.PokeclawColors
import io.agents.bqaagent.ui.chat.SkillReplayPreviewCard
import io.agents.bqaagent.ui.chat.ToolStep
import io.agents.bqaagent.ui.chat.formatStepDuration
import io.agents.bqaagent.ui.chat.formatStepTimestamp
import io.agents.bqaagent.ui.chat.formatStepTokens
import io.agents.bqaagent.ui.common.VoiceMicButton
import io.agents.bqaagent.ui.recording.RecordingPlaybackActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** The top-level destinations of the batch feature, routed by [BatchRoot]. */
enum class BatchScreen { WORKSPACE, DETAIL }

private val OkGreen = Color(0xFF4CAF50)
private val WarnAmber = Color(0xFFFFB300)
private val ErrRed = Color(0xFFFF5252)

// Match the chat drawer's control + icon shapes for pixel parity.
private val StraightControlShape = RoundedCornerShape(0.dp)
private val CompactSurfaceShape = RoundedCornerShape(4.dp)

/**
 * Batch navigation host. Mirrors the chat screen's shell: a left drawer lists batch sessions, the
 * top bar carries the "BQAAgent" wordmark plus a mode-switch button (back to chat) and a settings
 * button (system settings), and the body is the edit-and-run workspace.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchRoot(
    vm: BatchViewModel,
    colors: PokeclawColors,
    onExit: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenModels: () -> Unit,
    onOcrRequest: () -> Unit = {},
    ocrLoading: Boolean = false,
    ocrExtractedText: String? = null,
    onOcrConsumed: () -> Unit = {},
    onUploadUserImage: () -> Unit = {},
    voiceEnabled: Boolean = false,
    onRequestRecordPermission: () -> Unit = {},
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    // Pending screenshot-upload prompt for the in-flight batch task (null = no dialog).
    val userImagePrompt by vm.userImagePrompt.collectAsState()
    // Pending sensitive-confirm prompt for the in-flight batch task (null = no dialog).
    val sensitiveConfirmPrompt by vm.sensitiveConfirmPrompt.collectAsState()
    // Observable run lock: true from Start until the driver finishes and the collector has folded
    // results back. Stays TRUE through a cancel's teardown ("Stopping…"), and is cleared only once
    // the driver has stopped + persisted and the collector has saved — no cross-interference.
    val batchActive by vm.runActive.collectAsState()
    var screen by remember { mutableStateOf(BatchScreen.WORKSPACE) }
    var detailIndex by remember { mutableStateOf<Int?>(null) }
    // Bumped after a session is opened (loadSession touches its file), so the drawer re-reads and
    // re-sorts with the just-opened batch at the top — matching chat's most-recently-accessed order.
    var sidebarReload by remember { mutableStateOf(0) }

    // Resolve context + lock hint in composition scope: BackHandler's lambda is a plain
    // () -> Unit where @Composable calls (LocalContext.current, stringResource) are illegal.
    val activityContext = LocalContext.current
    val batchLockHint = activityContext.getString(R.string.mode_lock_batch_running_locked)

    BackHandler {
        // Only the WORKSPACE -> onExit() path switches modes (returns to chat); that is the one
        // to lock while a run owns the engine. DETAIL -> WORKSPACE is intra-batch navigation and
        // must stay allowed so users can back out of a live task transcript mid-run.
        when (screen) {
            BatchScreen.WORKSPACE -> {
                if (batchActive) {
                    android.widget.Toast.makeText(
                        activityContext,
                        batchLockHint,
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    onExit()
                }
            }
            BatchScreen.DETAIL -> screen = BatchScreen.WORKSPACE
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = colors.surface) {
                BatchSidebar(
                    vm = vm,
                    colors = colors,
                    active = batchActive,
                    externalReloadKey = sidebarReload,
                    onNewBatch = {
                        scope.launch { drawerState.close() }
                        if (!batchActive) vm.clearQueue()
                        detailIndex = null
                        screen = BatchScreen.WORKSPACE
                    },
                    onSelectSession = { summary ->
                        scope.launch { drawerState.close() }
                        // Requirement 1: tapping a saved batch opens it in the EDITABLE workspace, not a
                        // read-only report. Guarded while a run is live so it can never clobber the run.
                        if (!batchActive && vm.loadSession(summary)) {
                            sidebarReload++
                            detailIndex = null
                            screen = BatchScreen.WORKSPACE
                        }
                    },
                    onSettings = onOpenSettings,
                    onModels = onOpenModels,
                )
            }
        },
    ) {
        Scaffold(
            containerColor = colors.background,
            topBar = {
                BatchTopBar(
                    colors = colors,
                    onMenu = { scope.launch { drawerState.open() } },
                    onSwitchToChat = onExit,
                    switchEnabled = !batchActive,
                    onSettings = onOpenSettings,
                )
            },
        ) { inner ->
            Box(Modifier.fillMaxSize().padding(inner)) {
                when (screen) {
                    // Run is INLINE on the workspace: the driver returns to this activity after every
                    // task, so the rows update in place (no separate run screen).
                    BatchScreen.WORKSPACE -> BatchWorkspace(
                        vm = vm, colors = colors,
                        onOpenDetail = { idx -> detailIndex = idx; screen = BatchScreen.DETAIL },
                        onOcrRequest = onOcrRequest,
                        ocrLoading = ocrLoading,
                        ocrExtractedText = ocrExtractedText,
                        onOcrConsumed = onOcrConsumed,
                        voiceEnabled = voiceEnabled,
                        onRequestRecordPermission = onRequestRecordPermission,
                    )
                    BatchScreen.DETAIL -> BatchDetailScreen(
                        vm = vm, colors = colors,
                        taskIndex = detailIndex,
                        onBack = { screen = BatchScreen.WORKSPACE },
                    )
                }
            }
        }
    }

    // Global screenshot-upload prompt: floats above BOTH the workspace list and any detail screen, so
    // a batch task blocked on a failed screenshot can always ask for input wherever the user is. On
    // upload/skip/timeout it dismisses and the orchestrator returns the device to the target app.
    userImagePrompt?.let { prompt ->
        BatchUserImageDialog(
            prompt = prompt,
            colors = colors,
            onUpload = onUploadUserImage,
            onSkip = { vm.provideUserImage(null) },
        )
    }

    // Global sensitive-confirm prompt: floats above BOTH the workspace list and any detail screen,
    // so a batch task paused on a high-risk action can always ask for confirmation wherever the
    // user is. Approve/deny/timeout resolves the same orchestrator wait as chat's dialog.
    sensitiveConfirmPrompt?.let { prompt ->
        BatchSensitiveConfirmDialog(
            prompt = prompt,
            colors = colors,
            onApprove = { vm.resolveSensitiveConfirm(true) },
            onDeny = { vm.resolveSensitiveConfirm(false) },
        )
    }
}

/**
 * Batch analogue of chat's SensitiveConfirmDialog, shown as a modal dialog. Adds the batch-only
 * context: WHICH task is waiting for confirmation, plus a live countdown to auto-deny.
 * Must NOT be dismissible by scrim tap or back press (same rule as BatchUserImageDialog): leaving
 * the app and coming back must find the prompt still waiting until an explicit decision or timeout.
 */
@Composable
private fun BatchSensitiveConfirmDialog(
    prompt: BatchSensitiveConfirmPrompt,
    colors: PokeclawColors,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
) {
    var remainingMs by remember(prompt.deadlineMs) {
        mutableStateOf((prompt.deadlineMs - System.currentTimeMillis()).coerceAtLeast(0L))
    }
    LaunchedEffect(prompt.deadlineMs) {
        while (true) {
            val left = prompt.deadlineMs - System.currentTimeMillis()
            if (left <= 0L) {
                remainingMs = 0L
                onDeny()
                break
            }
            remainingMs = left
            delay(1000L)
        }
    }
    val totalSec = (remainingMs / 1000L).coerceAtLeast(0L)
    val mmss = String.format(Locale.US, "%02d:%02d", totalSec / 60, totalSec % 60)
    val riskLabel = when (prompt.riskType.lowercase()) {
        "payment" -> stringResource(R.string.sensitive_confirm_risk_payment)
        "password" -> stringResource(R.string.sensitive_confirm_risk_password)
        else -> stringResource(R.string.sensitive_confirm_risk_other)
    }

    Dialog(
        onDismissRequest = { },
        properties = DialogProperties(
            dismissOnClickOutside = false,
            dismissOnBackPress = false,
        ),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = colors.surface,
            shadowElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(36.dp),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.sensitive_confirm_dialog_title),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = colors.background,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            text = stringResource(R.string.batch_image_dialog_task, prompt.taskSeq),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textSecondary,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = prompt.taskText,
                            fontSize = 14.sp,
                            color = colors.textPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = prompt.actionSummary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = colors.accent,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "${stringResource(R.string.sensitive_confirm_dialog_risk)}: $riskLabel",
                            fontSize = 12.sp,
                            color = colors.textSecondary,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.sensitive_confirm_dialog_desc),
                    fontSize = 13.sp,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp,
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    text = stringResource(R.string.sensitive_confirm_dialog_countdown, mmss),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (totalSec <= 10L) ErrRed else colors.accent,
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = onDeny,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.textSecondary),
                    ) {
                        Text(stringResource(R.string.sensitive_confirm_dialog_deny), fontSize = 14.sp)
                    }
                    Button(
                        onClick = onApprove,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                    ) {
                        Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color.White)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.sensitive_confirm_dialog_approve), fontSize = 14.sp, color = Color.White)
                    }
                }
            }
        }
    }
}

/**
 * Batch analogue of chat's UserImageUploadCard, shown as a modal dialog. Adds the batch-only context:
 * WHICH task is blocked and a live countdown to auto-skip. Buttons/behavior match chat exactly
 * (Skip + Upload); at zero it auto-skips, which the orchestrator treats as "no image" and continues.
 */
@Composable
private fun BatchUserImageDialog(
    prompt: BatchUserImagePrompt,
    colors: PokeclawColors,
    onUpload: () -> Unit,
    onSkip: () -> Unit,
) {
    var remainingMs by remember(prompt.deadlineMs) {
        mutableStateOf((prompt.deadlineMs - System.currentTimeMillis()).coerceAtLeast(0L))
    }
    LaunchedEffect(prompt.deadlineMs) {
        while (true) {
            val left = prompt.deadlineMs - System.currentTimeMillis()
            if (left <= 0L) {
                remainingMs = 0L
                onSkip()
                break
            }
            remainingMs = left
            delay(1000L)
        }
    }
    val totalSec = (remainingMs / 1000L).coerceAtLeast(0L)
    val mmss = String.format(Locale.US, "%02d:%02d", totalSec / 60, totalSec % 60)

    // MUST NOT be dismissible by tapping the scrim or pressing back. A swipe to switch apps can land
    // on the scrim and, with the default dismissOnClickOutside=true, silently resolves the dialog as a
    // SKIP (onDismissRequest -> onSkip -> provideUserImage(null)) — exactly the bug where leaving to
    // grab a screenshot made the prompt vanish. Pinning it means ONLY the Skip / Upload buttons or the
    // countdown can resolve it, so the user can leave and come back with the prompt still waiting.
    Dialog(
        onDismissRequest = { },
        properties = DialogProperties(
            dismissOnClickOutside = false,
            dismissOnBackPress = false,
        ),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = colors.surface,
            shadowElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = Icons.Filled.AddPhotoAlternate,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(36.dp),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.batch_image_dialog_title),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = colors.background,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            text = stringResource(R.string.batch_image_dialog_task, prompt.taskSeq),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textSecondary,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = prompt.taskText,
                            fontSize = 14.sp,
                            color = colors.textPrimary,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.batch_image_dialog_reason),
                    fontSize = 13.sp,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.batch_image_dialog_action),
                    fontSize = 13.sp,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp,
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    text = stringResource(R.string.batch_image_dialog_countdown, mmss),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (totalSec <= 10L) ErrRed else colors.accent,
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = onSkip,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.textSecondary),
                    ) {
                        Text(stringResource(R.string.chat_skip), fontSize = 14.sp)
                    }
                    Button(
                        onClick = onUpload,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                    ) {
                        Icon(Icons.Filled.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.chat_upload), fontSize = 14.sp, color = Color.White)
                    }
                }
            }
        }
    }
}

/** Top bar mirroring the chat screen: menu (batch list) + wordmark + mode switch (to chat) + settings. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BatchTopBar(
    colors: PokeclawColors,
    onMenu: () -> Unit,
    onSwitchToChat: () -> Unit,
    switchEnabled: Boolean = true,
    onSettings: () -> Unit,
) {
    Column {
        TopAppBar(
            title = {
                Text(
                    buildAnnotatedString {
                        append("BQA")
                        withStyle(SpanStyle(color = colors.accent)) { append("Agent") }
                    },
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = colors.textPrimary,
                )
            },
            navigationIcon = {
                IconButton(onClick = onMenu) {
                    Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.batch_cd_batch_list), tint = colors.textPrimary)
                }
            },
            actions = {
                TextButton(
                    onClick = onSwitchToChat,
                    enabled = switchEnabled,
                ) {
                    Icon(
                        Icons.Default.Forum,
                        contentDescription = null,
                        tint = if (switchEnabled) colors.textSecondary else colors.textTertiary.copy(alpha = 0.4f),
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        stringResource(R.string.batch_tab_chat),
                        fontSize = 13.sp,
                        color = if (switchEnabled) colors.textSecondary else colors.textTertiary.copy(alpha = 0.4f),
                    )
                }
                IconButton(onClick = onSettings) {
                    Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.batch_cd_settings), tint = colors.textSecondary)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = colors.surface,
                titleContentColor = colors.textPrimary,
                navigationIconContentColor = colors.textPrimary,
                actionIconContentColor = colors.textSecondary,
            ),
        )
        HorizontalDivider(color = colors.divider, thickness = 0.5.dp)
    }
}

/** Left drawer: the batch session list, mirroring the chat drawer (icon + wordmark header, long-press Rename/Delete, Settings/Models footer). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BatchSidebar(
    vm: BatchViewModel,
    colors: PokeclawColors,
    active: Boolean,
    externalReloadKey: Int,
    onNewBatch: () -> Unit,
    onSelectSession: (ChatHistoryManager.ConversationSummary) -> Unit,
    onSettings: () -> Unit,
    onModels: () -> Unit,
) {
    var reloadKey by remember { mutableStateOf(0) }
    val sessions by produceState<List<ChatHistoryManager.ConversationSummary>?>(
        initialValue = null,
        key1 = reloadKey,
        key2 = externalReloadKey,
    ) {
        value = withContext(Dispatchers.IO) { vm.listBatchSessions() }
    }

    var actionTarget by remember { mutableStateOf<ChatHistoryManager.ConversationSummary?>(null) }
    var deleteTarget by remember { mutableStateOf<ChatHistoryManager.ConversationSummary?>(null) }
    var renameTarget by remember { mutableStateOf<ChatHistoryManager.ConversationSummary?>(null) }
    var renameText by remember { mutableStateOf("") }

    // Long-press action menu: Rename / Delete (identical to the chat drawer).
    if (actionTarget != null) {
        AlertDialog(
            onDismissRequest = { actionTarget = null },
            title = { Text(actionTarget!!.title.ifBlank { actionTarget!!.id }, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    TextButton(
                        onClick = {
                            renameTarget = actionTarget
                            renameText = actionTarget!!.title
                            actionTarget = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = StraightControlShape,
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Edit, contentDescription = null, tint = colors.textPrimary, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(stringResource(R.string.batch_action_rename), color = colors.textPrimary)
                        }
                    }
                    TextButton(
                        onClick = {
                            deleteTarget = actionTarget
                            actionTarget = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = StraightControlShape,
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFFF87171), modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(stringResource(R.string.batch_action_delete), color = Color(0xFFF87171))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { actionTarget = null }, shape = StraightControlShape) {
                    Text(stringResource(R.string.common_cancel), color = colors.textSecondary)
                }
            },
            containerColor = colors.surface,
        )
    }

    // Delete confirmation
    if (deleteTarget != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.batch_delete_title), color = colors.textPrimary) },
            text = { Text(deleteTarget!!.title.ifBlank { deleteTarget!!.id }, color = colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (vm.deleteSession(deleteTarget!!)) reloadKey++
                        deleteTarget = null
                    },
                    shape = StraightControlShape,
                ) { Text(stringResource(R.string.batch_action_delete), color = Color(0xFFF87171)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }, shape = StraightControlShape) {
                    Text(stringResource(R.string.common_cancel), color = colors.textSecondary)
                }
            },
            containerColor = colors.surface,
        )
    }

    // Rename dialog
    if (renameTarget != null) {
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text(stringResource(R.string.batch_rename_title), color = colors.textPrimary) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = colors.textPrimary,
                        unfocusedTextColor = colors.textPrimary,
                        focusedBorderColor = colors.accent,
                        unfocusedBorderColor = colors.inputBorder,
                        cursorColor = colors.accent,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val newName = renameText.trim()
                        if (newName.isNotEmpty() && renameTarget != null) {
                            if (vm.renameSession(renameTarget!!, newName)) reloadKey++
                        }
                        renameTarget = null
                    },
                    shape = StraightControlShape,
                ) { Text(stringResource(R.string.common_save), color = colors.accent) }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }, shape = StraightControlShape) {
                    Text(stringResource(R.string.common_cancel), color = colors.textSecondary)
                }
            },
            containerColor = colors.surface,
        )
    }

    val data = sessions
    Column(Modifier.fillMaxHeight().padding(top = 48.dp)) {
        // Header: app icon + wordmark (identical to the chat drawer).
        Row(
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(R.drawable.bqa_agent_icon),
                contentDescription = null,
                modifier = Modifier.size(28.dp).clip(CompactSurfaceShape),
            )
            Spacer(Modifier.width(10.dp))
            Text("BQAAgent", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = colors.textPrimary)
        }

        Button(
            onClick = onNewBatch,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
            shape = StraightControlShape,
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.batch_new))
        }

        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = colors.divider, modifier = Modifier.padding(horizontal = 14.dp))
        Spacer(Modifier.height(8.dp))

        Text(
            stringResource(R.string.batch_recent),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = colors.textTertiary,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 4.dp),
        )
        Text(
            stringResource(R.string.batch_recent_count, data?.size ?: 0, ChatHistoryManager.MAX_CONVERSATIONS),
            fontSize = 11.sp,
            color = colors.textTertiary,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
        )

        LazyColumn(modifier = Modifier.weight(1f)) {
            if (data == null) {
                item {
                    Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = colors.accent, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
            } else if (data.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.batch_empty_list),
                        fontSize = 13.sp,
                        color = colors.textTertiary,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    )
                }
            } else {
                items(data, key = { it.id }) { summary ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = { onSelectSession(summary) },
                                // Locked while a batch runs: rename/delete must never touch the store
                                // mid-run (deleting the live conversation would corrupt the run).
                                onLongClick = { if (!active) actionTarget = summary },
                            ),
                        color = Color.Transparent,
                    ) {
                        Text(
                            text = summary.title.ifBlank { summary.id },
                            fontSize = 14.sp,
                            color = colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = colors.divider)

        TextButton(
            onClick = onSettings,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            shape = StraightControlShape,
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Settings, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.settings_title), color = colors.textSecondary)
            }
        }
        TextButton(
            onClick = onModels,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 0.dp),
            shape = StraightControlShape,
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.SmartToy, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.batch_models), color = colors.textSecondary)
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

/**
 * The unified edit-and-run workspace (requirements 3 & 4). Top-to-bottom: the task list (rows plus a
 * trailing "+" tile that hides at [BatchViewModel.MAX_TASKS]), then the inline batch settings (moved
 * out of the top-bar settings button, which now opens SYSTEM settings), then a progress bar computed
 * from per-task status, then Start + Cancel on one row. A run is INLINE: the driver returns here after
 * every task, so each row's status updates in place. Tapping a row's detail button drills into that
 * single task's transcript.
 */
@Composable
fun BatchWorkspace(
    vm: BatchViewModel,
    colors: PokeclawColors,
    onOpenDetail: (Int) -> Unit,
    onOcrRequest: () -> Unit = {},
    ocrLoading: Boolean = false,
    ocrExtractedText: String? = null,
    onOcrConsumed: () -> Unit = {},
    voiceEnabled: Boolean = false,
    onRequestRecordPermission: () -> Unit = {},
) {
    val queue by vm.queue.collectAsState()
    val runState by vm.runState.collectAsState()
    val config by vm.config.collectAsState()
    val gateOn by vm.skillCaptureEnabled.collectAsState()

    val active by vm.runActive.collectAsState()
    val cancelling by vm.cancelling.collectAsState()
    // Cross-mode gate (C1 UI half): a chat task owning the workspace must disable Start/Continue here.
    val chatBusy by vm.chatModeBlocked.collectAsState()
    val stopStalled by vm.stopStalled.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var showSkillPicker by remember { mutableStateOf(false) }
    var showPrecheck by remember { mutableStateOf(false) }
    var showRerunConfirm by remember { mutableStateOf(false) }
    var continueMode by remember { mutableStateOf(false) }
    var settingsExpanded by remember { mutableStateOf(true) }
    // Requirement 1: deleting a queued task asks for confirmation first (mirrors the drawer's
    // delete-batch dialog) so a stray tap on the row's trash icon can't silently drop a task.
    var deleteTaskIndex by remember { mutableStateOf<Int?>(null) }

    val hasResults = queue.any { it.result != null }
    val resumeIndex = queue.indexOfFirst { !it.result.isCompleted() }
    // "Continue" is only distinct from "Start over" when there is at least one unfinished task AND
    // at least one completed task worth preserving. resumeIndex == 0 still qualifies when a settled
    // SUCCESS/FAILED task sits AFTER an unfinished head (e.g. [STOPPED, SUCCESS, STOPPED]): Continue
    // re-runs only the unfinished rows and keeps the middle result, whereas Start over would wipe it.
    val hasCompleted = queue.any { it.result.isCompleted() }
    val resumable = !active && resumeIndex >= 0 && hasCompleted

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // ---- a. Task list (rows + trailing add tile) ----
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (queue.isEmpty()) {
                    item {
                        Text(
                            if (gateOn) stringResource(R.string.batch_workspace_empty_skill) else stringResource(R.string.batch_workspace_empty),
                            color = colors.textTertiary,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                }
                itemsIndexed(queue, key = { _, it -> it.id }) { index, item ->
                    val view = runState?.tasks?.getOrNull(index) ?: item.result
                    BatchQueueRow(
                        seq = index + 1,
                        view = view,
                        label = item.label,
                        active = active,
                        colors = colors,
                        onDetail = { onOpenDetail(index) },
                        onDelete = { deleteTaskIndex = index },
                    )
                }
                if (queue.size < BatchViewModel.MAX_TASKS) {
                    item {
                        AddTaskTile(
                            colors = colors,
                            enabled = !active,
                            onClick = { showAddDialog = true },
                        )
                    }
                }
            }

            HorizontalDivider(color = colors.divider)

            // ---- b. Inline batch settings ----
            BatchSettingsSection(
                config = config,
                gateOn = gateOn,
                expanded = settingsExpanded,
                enabled = !active,
                colors = colors,
                onToggle = { settingsExpanded = !settingsExpanded },
                onUpdate = { vm.updateConfig(it) },
            )

            // ---- 4. Progress bar (between settings and Start) ----
            BatchProgressSection(queue = queue, runState = runState, colors = colors)

            stopStalled?.let {
                Text(
                    text = it,
                    color = ErrRed,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
                )
            }

            // ---- c. Start / Continue / Cancel ---
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (active && cancelling) {
                    // Teardown in progress: the task is being stopped and its data saved. The workspace
                    // stays locked and this shows a disabled "Stopping…" so the user can't start a
                    // conflicting run mid-unwind. Cleared once the driver finishes + the collector folds
                    // results back / persists (or, worst case, the VM's teardown watchdog fires).
                    OutlinedButton(
                        onClick = {},
                        enabled = false,
                        modifier = Modifier.weight(1f).height(48.dp),
                    ) { Text(stringResource(R.string.batch_stopping), fontSize = 15.sp) }
                } else if (active) {
                    OutlinedButton(
                        onClick = { vm.cancelBatch() },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = ErrRed),
                        modifier = Modifier.weight(1f).height(48.dp),
                    ) { Text(stringResource(R.string.common_cancel), fontSize = 15.sp) }
                } else {
                    Button(
                        onClick = {
                            continueMode = false
                            if (hasResults) showRerunConfirm = true else showPrecheck = true
                        },
                        enabled = queue.isNotEmpty() && !chatBusy,
                        colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                        modifier = Modifier.weight(1f).height(48.dp),
                    ) {
                        Text(
                            stringResource(
                                when {
                                    chatBusy -> R.string.mode_lock_chat_busy
                                    hasResults -> R.string.batch_start_over
                                    else -> R.string.batch_start
                                }
                            ),
                            fontSize = 15.sp,
                        )
                    }
                    if (resumable) {
                        Button(
                            onClick = { continueMode = true; showPrecheck = true },
                            enabled = queue.isNotEmpty() && !chatBusy,
                            colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                            modifier = Modifier.weight(1f).height(48.dp),
                        ) { Text(stringResource(R.string.batch_continue), fontSize = 15.sp) }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddTaskDialog(
            vm = vm,
            colors = colors,
            gateOn = gateOn,
            onAdd = { text -> vm.addTextTask(text); showAddDialog = false },
            onFromSkill = { showAddDialog = false; showSkillPicker = true },
            onDismiss = { showAddDialog = false },
            onOcrRequest = onOcrRequest,
            ocrLoading = ocrLoading,
            ocrExtractedText = ocrExtractedText,
            onOcrConsumed = onOcrConsumed,
            voiceEnabled = voiceEnabled,
            onRequestRecordPermission = onRequestRecordPermission,
        )
    }

    if (showSkillPicker) {
        SkillPickerDialog(
            colors = colors,
            skills = vm.availableSkills(),
            templatesFor = { skillId -> vm.templatesFor(skillId) },
            onPick = { taskText ->
                vm.addTextTask(taskText)
                showSkillPicker = false
            },
            onDismiss = { showSkillPicker = false },
        )
    }

    if (showPrecheck) {
        PrecheckStartDialog(
            vm = vm,
            colors = colors,
            onDismiss = { showPrecheck = false },
            onGo = {
                showPrecheck = false
                if (continueMode) vm.continueBatch() else vm.startBatch()
            },
        )
    }

    if (showRerunConfirm) {
        AlertDialog(
            onDismissRequest = { showRerunConfirm = false },
            title = { Text(stringResource(R.string.batch_rerun_title), color = colors.textPrimary) },
            text = { Text(stringResource(R.string.batch_rerun_message), color = colors.textSecondary) },
            confirmButton = {
                TextButton(onClick = { showRerunConfirm = false; showPrecheck = true }, shape = StraightControlShape) {
                    Text(stringResource(R.string.batch_start_over), color = ErrRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRerunConfirm = false }, shape = StraightControlShape) {
                    Text(stringResource(R.string.common_cancel), color = colors.textSecondary)
                }
            },
            containerColor = colors.surface,
        )
    }

    // Requirement 1: confirm before removing a queued task.
    val delIdx = deleteTaskIndex
    if (delIdx != null) {
        AlertDialog(
            onDismissRequest = { deleteTaskIndex = null },
            title = { Text(stringResource(R.string.batch_delete_task_title), color = colors.textPrimary) },
            text = { Text(stringResource(R.string.batch_delete_task_message, delIdx + 1), color = colors.textSecondary) },
            confirmButton = {
                TextButton(onClick = { vm.removeTaskAt(delIdx); deleteTaskIndex = null }, shape = StraightControlShape) {
                    Text(stringResource(R.string.batch_action_delete), color = Color(0xFFF87171))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTaskIndex = null }, shape = StraightControlShape) {
                    Text(stringResource(R.string.common_cancel), color = colors.textSecondary)
                }
            },
            containerColor = colors.surface,
        )
    }
}

/** One editable queue row: status (left) · description + type (middle) · detail + delete (right). */
@Composable
private fun BatchQueueRow(
    seq: Int,
    view: BatchTaskView?,
    label: String,
    active: Boolean,
    colors: PokeclawColors,
    onDetail: () -> Unit,
    onDelete: () -> Unit,
) {
    val style = view?.let { resolvePhase(it, colors) } ?: PhaseView(R.string.batch_phase_none, colors.textTertiary)
    Card(
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "$seq",
                color = colors.textTertiary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.width(48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                // NONE (never run / just reset) is a hollow ring so it never reads as the grey CANCELLED
                // dot; every real phase is a solid colour dot.
                if (view == null) {
                    Box(Modifier.size(14.dp).border(1.5.dp, style.color, CircleShape))
                } else {
                    Box(Modifier.size(14.dp).clip(CircleShape).background(style.color))
                }
                Spacer(Modifier.height(3.dp))
                Text(stringResource(style.label), color = style.color, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(label, color = colors.textPrimary, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                // Deleted:Text(typeLabel, color = colors.textTertiary, fontSize = 12.sp)
            }
            IconButton(onClick = onDetail) {
                Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.batch_cd_details), tint = colors.textSecondary, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onDelete, enabled = !active) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.batch_action_delete), tint = colors.textSecondary, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** The trailing "+" tile; it moves down as tasks are added and hides at the cap. */
@Composable
private fun AddTaskTile(
    colors: PokeclawColors,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, if (enabled) colors.accent else colors.inputBorder),
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Add, contentDescription = null, tint = if (enabled) colors.accent else colors.textTertiary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.batch_add_task), color = if (enabled) colors.accent else colors.textTertiary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * Add-task dialog: plain text input with an "Add" button and a "From Skill" button.
 * No Skill matching at add time — matching is deferred to execution time (Phase B).
 */
@Composable
private fun AddTaskDialog(
    vm: BatchViewModel,
    colors: PokeclawColors,
    gateOn: Boolean,
    onAdd: (String) -> Unit,
    onFromSkill: () -> Unit,
    onDismiss: () -> Unit,
    onOcrRequest: () -> Unit = {},
    ocrLoading: Boolean = false,
    ocrExtractedText: String? = null,
    onOcrConsumed: () -> Unit = {},
    voiceEnabled: Boolean = false,
    onRequestRecordPermission: () -> Unit = {},
) {
    var text by remember { mutableStateOf("") }
    var isVoiceRecording by remember { mutableStateOf(false) }

    LaunchedEffect(ocrExtractedText) {
        val extracted = ocrExtractedText
        if (!extracted.isNullOrEmpty()) {
            text = extracted
            onOcrConsumed()
        }
    }

    Dialog(onDismissRequest = { onDismiss() }) {
        Card(
            colors = CardDefaults.cardColors(containerColor = colors.surface),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box {
                Column(Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Text(
                            stringResource(R.string.batch_task_dialog_title),
                            color = colors.textPrimary,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.weight(1f))
                        if (ocrLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = colors.textSecondary,
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Surface(
                                onClick = onOcrRequest,
                                modifier = Modifier.size(28.dp),
                                shape = RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp),
                                color = Color.Transparent,
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.CameraAlt,
                                        contentDescription = stringResource(R.string.ocr_cd_button),
                                        tint = colors.textTertiary,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it },
                            modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
                            placeholder = { Text(stringResource(R.string.batch_task_placeholder)) },
                            minLines = 5,
                            maxLines = 8,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = colors.textPrimary,
                                unfocusedTextColor = colors.textPrimary,
                                focusedBorderColor = if (isVoiceRecording) colors.accent else colors.accent,
                                unfocusedBorderColor = if (isVoiceRecording) colors.accent.copy(alpha = 0.7f) else colors.inputBorder,
                                cursorColor = colors.accent,
                                focusedPlaceholderColor = colors.textTertiary,
                                unfocusedPlaceholderColor = colors.textTertiary,
                            ),
                        )
                        // Floating hold-to-talk mic, pinned over the field's bottom-right corner on
                        // purpose: it overlaps the text area instead of stealing layout space.
                        VoiceMicButton(
                            voiceEnabled = voiceEnabled,
                            colors = colors,
                            onRequestRecordPermission = onRequestRecordPermission,
                            onText = { text = it },
                            onRecordingChange = { isVoiceRecording = it },
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = 8.dp, bottom = 8.dp),
                            buttonSize = 36.dp,
                            iconSize = 20.dp,
                            idleBackground = colors.surface.copy(alpha = 0.92f),
                            idleBorder = colors.inputBorder,
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = {
                                val t = text.trim()
                                if (t.isEmpty()) return@Button
                                onAdd(t)
                            },
                            enabled = text.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.batch_add))
                        }
                        if (gateOn) {
                            OutlinedButton(
                                onClick = onFromSkill,
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.textPrimary),
                                modifier = Modifier.weight(1f),
                            ) { Text(stringResource(R.string.batch_from_skill)) }
                        }
                    }
                }

                if (ocrLoading) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(colors.surface.copy(alpha = 0.85f))
                            .clickable(
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                indication = null,
                            ) { },
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(28.dp),
                                color = colors.accent,
                                strokeWidth = 2.5.dp,
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                stringResource(R.string.ocr_recognizing),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = colors.textPrimary,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The pre-flight gate shown on Start (requirement 3c): re-probes the device, and either lists the
 * blocking issues (user must close it manually, nothing runs) or shows the verdict plus a 3-second
 * countdown that then dismisses and starts the batch.
 */
@Composable
private fun PrecheckStartDialog(
    vm: BatchViewModel,
    colors: PokeclawColors,
    onDismiss: () -> Unit,
    onGo: () -> Unit,
) {
    val precheck by vm.precheck.collectAsState()
    val openedAt = remember { precheck.checkedAt }
    LaunchedEffect(Unit) { vm.runPrecheck() }
    val fresh = precheck.checkedAt != 0L && precheck.checkedAt != openedAt
    val blocked = precheck.blocking.isNotEmpty()
    val hasWarnings = precheck.warnings.isNotEmpty()
    // Three precheck scenarios: (1) blocked -> never runs; (2) warnings only -> wait for an
    // explicit user confirmation before the countdown; (3) clean -> count down immediately.
    var confirmed by remember { mutableStateOf(false) }
    var count by remember { mutableStateOf(3) }
    val countingDown = fresh && !blocked && (!hasWarnings || confirmed)

    LaunchedEffect(countingDown) {
        if (countingDown) {
            while (count > 0) { delay(1000); count-- }
            onGo()
        }
    }

    Dialog(onDismissRequest = { if (!countingDown) onDismiss() }) {
        Card(
            colors = CardDefaults.cardColors(containerColor = colors.surface),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.batch_precheck_title), color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                when {
                    !fresh -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = colors.accent, strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.batch_precheck_checking), color = colors.textSecondary, fontSize = 13.sp)
                    }
                    else -> {
                        // Same ADB state must read the same across Settings / chat banner / batch:
                        // reuse the shared perm_status_* vocabulary instead of batch-only wording.
                        val adbText = when {
                            !precheck.adbConfigured -> stringResource(R.string.perm_status_not_ready)
                            precheck.adbReady -> stringResource(R.string.perm_status_ready)
                            else -> stringResource(R.string.perm_status_disconnected)
                        }
                        val adbColor = when {
                            !precheck.adbConfigured -> ErrRed
                            precheck.adbReady -> OkGreen
                            else -> WarnAmber
                        }
                        InfoRow(colors, stringResource(R.string.batch_precheck_local_adb), adbText, adbColor)
                        if (precheck.recordingEnabled) {
                            InfoRow(colors, stringResource(R.string.batch_precheck_recording), if (precheck.recordingReady) stringResource(R.string.batch_recording_on) else stringResource(R.string.batch_recording_on_not_ready), if (precheck.recordingReady) OkGreen else WarnAmber)
                            InfoRow(colors, stringResource(R.string.batch_precheck_free_space), precheck.diskFreeText, if (precheck.hasRoomForRecording) colors.textPrimary else ErrRed)
                        } else {
                            InfoRow(colors, stringResource(R.string.batch_precheck_recording), stringResource(R.string.batch_recording_off), colors.textSecondary)
                        }
                        precheck.blocking.forEach { issue ->
                            Row(Modifier.padding(top = 4.dp)) {
                                Text("•  ", color = ErrRed, fontSize = 13.sp)
                                Text(issue, color = ErrRed, fontSize = 12.sp)
                            }
                        }
                        precheck.warnings.forEach { warning ->
                            Row(Modifier.padding(top = 4.dp)) {
                                Text("•  ", color = WarnAmber, fontSize = 13.sp)
                                Text(warning, color = WarnAmber, fontSize = 12.sp)
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        when {
                            blocked -> Text(stringResource(R.string.batch_precheck_blocked), color = ErrRed, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            hasWarnings && !confirmed -> Text(stringResource(R.string.batch_precheck_warning_prompt), color = WarnAmber, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            else -> Text(stringResource(R.string.batch_precheck_ready_countdown, count), color = OkGreen, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                if (fresh && blocked) {
                    TextButton(
                        onClick = onDismiss,
                        colors = ButtonDefaults.textButtonColors(contentColor = colors.accent),
                        modifier = Modifier.align(Alignment.End),
                    ) { Text(stringResource(R.string.common_close)) }
                } else if (fresh && !blocked && hasWarnings && !confirmed) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(
                            onClick = onDismiss,
                            colors = ButtonDefaults.textButtonColors(contentColor = colors.textSecondary),
                        ) { Text(stringResource(R.string.common_cancel)) }
                        Spacer(Modifier.width(8.dp))
                        TextButton(
                            onClick = { confirmed = true },
                            colors = ButtonDefaults.textButtonColors(contentColor = colors.accent),
                        ) { Text(stringResource(R.string.batch_continue)) }
                    }
                }
            }
        }
    }
}

/** Inline batch settings (moved here from the top-bar settings button); collapsible to save room. */
@Composable
private fun BatchSettingsSection(
    config: BatchConfig,
    gateOn: Boolean,
    expanded: Boolean,
    enabled: Boolean,
    colors: PokeclawColors,
    onToggle: () -> Unit,
    onUpdate: ((BatchConfig) -> BatchConfig) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.batch_settings_title), color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier.size(20.dp),
            )
        }
        if (expanded) {
            Spacer(Modifier.height(4.dp))
            if (gateOn) {
                ToggleRow(
                    colors = colors,
                    title = stringResource(R.string.batch_setting_prefer_replay_title),
                    subtitle = stringResource(R.string.batch_setting_prefer_replay_subtitle),
                    checked = config.preferReplay,
                    enabled = enabled,
                    onCheckedChange = { on -> onUpdate { it.copy(preferReplay = on) } },
                )
                ToggleRow(
                    colors = colors,
                    title = stringResource(R.string.batch_setting_autosave_title),
                    subtitle = stringResource(R.string.batch_setting_autosave_subtitle),
                    checked = config.autoSaveSkill,
                    enabled = enabled,
                    onCheckedChange = { on -> onUpdate { it.copy(autoSaveSkill = on) } },
                )
            }
            ToggleRow(
                colors = colors,
                title = stringResource(R.string.batch_setting_reset_title),
                subtitle = stringResource(R.string.batch_setting_reset_subtitle),
                checked = config.resetBetweenTasks,
                enabled = enabled,
                onCheckedChange = { on -> onUpdate { it.copy(resetBetweenTasks = on) } },
            )
            ToggleRow(
                colors = colors,
                title = stringResource(R.string.batch_setting_stop_title),
                subtitle = stringResource(R.string.batch_setting_stop_subtitle),
                checked = config.stopOnFailure,
                enabled = enabled,
                onCheckedChange = { on -> onUpdate { it.copy(stopOnFailure = on) } },
            )
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.batch_setting_interval_title), color = if (enabled) colors.textPrimary else colors.textTertiary, fontSize = 14.sp)
                    Text(stringResource(R.string.batch_setting_interval_subtitle), color = colors.textTertiary, fontSize = 12.sp)
                }
                TextButton(
                    onClick = { onUpdate { it.copy(taskIntervalSeconds = (it.taskIntervalSeconds - 1).coerceIn(0, 30)) } },
                    enabled = enabled,
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.accent),
                ) { Text("−", fontSize = 20.sp) }
                Text(
                    stringResource(R.string.batch_interval_value, config.taskIntervalSeconds),
                    color = if (enabled) colors.textPrimary else colors.textTertiary,
                    modifier = Modifier.width(48.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                TextButton(
                    onClick = { onUpdate { it.copy(taskIntervalSeconds = (it.taskIntervalSeconds + 1).coerceIn(0, 30)) } },
                    enabled = enabled,
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.accent),
                ) { Text("+", fontSize = 20.sp) }
            }
        }
    }
}

/** Progress bar computed from per-task status; sits between the settings and the Start button. */
@Composable
private fun BatchProgressSection(
    queue: List<BatchViewModel.QueueItem>,
    runState: BatchTaskDriver.BatchRunSnapshot?,
    colors: PokeclawColors,
) {
    val total = queue.size
    val done = if (runState != null) {
        runState.tasks.count { it.isCompleted() }
    } else {
        queue.count { it.result.isCompleted() }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        LinearProgressIndicator(
            progress = { if (total == 0) 0f else done.toFloat() / total.toFloat() },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            color = colors.accent,
            trackColor = colors.divider,
        )
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.batch_progress_completed, done, total), color = colors.textTertiary, fontSize = 12.sp)
    }
}

private data class PhaseView(@StringRes val label: Int, val color: Color)

private fun resolvePhase(task: BatchTaskView, colors: PokeclawColors): PhaseView = when (task.phase) {
    BatchTaskPhase.PENDING -> PhaseView(R.string.batch_phase_pending, colors.textTertiary)
    BatchTaskPhase.RUNNING -> PhaseView(R.string.batch_phase_running, colors.accent)
    BatchTaskPhase.SKIPPED_ADB -> PhaseView(R.string.batch_phase_skipped_adb, WarnAmber)
    BatchTaskPhase.HANG_TIMEOUT -> PhaseView(R.string.batch_phase_timeout, ErrRed)
    BatchTaskPhase.ABANDONED -> PhaseView(R.string.batch_phase_abandoned, ErrRed)
    BatchTaskPhase.SETTLED -> when (task.taskStatus) {
        TaskStatus.SUCCESS -> PhaseView(R.string.batch_status_success, OkGreen)
        TaskStatus.FAILED -> PhaseView(R.string.batch_status_failed, ErrRed)
        TaskStatus.STOPPED -> PhaseView(R.string.batch_status_stopped, WarnAmber)
        TaskStatus.CANCELLED -> PhaseView(R.string.batch_status_cancelled, colors.textSecondary)
        null -> PhaseView(R.string.batch_status_done, OkGreen)
    }
}

private fun statusIcon(status: TaskStatus?): ImageVector = when (status) {
    TaskStatus.SUCCESS -> Icons.Filled.CheckCircle
    TaskStatus.FAILED -> Icons.Filled.Cancel
    TaskStatus.STOPPED -> Icons.Filled.StopCircle
    TaskStatus.CANCELLED -> Icons.Filled.DoNotDisturb
    null -> Icons.Filled.ChatBubbleOutline
}

@Composable
fun BatchDetailScreen(
    vm: BatchViewModel,
    colors: PokeclawColors,
    taskIndex: Int?,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val runState by vm.runState.collectAsState()
    val liveTranscript by vm.liveTaskTranscript.collectAsState()
    // True while the task whose detail is open is the one the driver is executing right now.
    val liveRunning = taskIndex != null &&
            runState?.running == true &&
            runState?.currentIndex == taskIndex &&
            liveTranscript.isNotEmpty()
    val messages by produceState<List<ChatMessage>?>(
        initialValue = null,
        key1 = taskIndex,
        key2 = if (liveRunning) 1 else 0,
    ) {
        // Outside a live run of THIS task: read the persisted/folded span exactly as before. During a
        // live run the disk span is only written when the task settles, so skip the file read and let
        // the driver's in-flight transcript render below.
        value = if (liveRunning) null else withContext(Dispatchers.IO) {
            if (taskIndex == null) emptyList() else vm.taskMessages(taskIndex)
        }
    }

    Column(Modifier.fillMaxSize()) {
        TextButton(
            onClick = onBack,
            colors = ButtonDefaults.textButtonColors(contentColor = colors.textSecondary),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Icon(Icons.Outlined.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.batch_back))
        }
        HorizontalDivider(color = colors.divider)

        val data = if (liveRunning) liveTranscript else messages
        when {
            data == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.accent)
            }
            data.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.batch_detail_empty), color = colors.textTertiary, fontSize = 14.sp)
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                itemsIndexed(data) { _, message ->
                    when (message.role) {
                        ChatMessage.Role.USER -> DetailTaskHeader(colors, (taskIndex ?: 0) + 1, message)
                        ChatMessage.Role.TOOL_GROUP -> DetailToolGroup(colors, message)
                        ChatMessage.Role.ASSISTANT -> DetailResult(
                            colors = colors,
                            message = message,
                            onPlay = { rid -> RecordingPlaybackActivity.start(context, rid) },
                        )
                        ChatMessage.Role.SYSTEM -> CopyableMessageContainer(
                            text = message.content,
                            colors = colors,
                            modifier = Modifier.fillMaxWidth(),
                        ) { longPressModifier ->
                            Text(
                                message.content,
                                color = colors.textTertiary,
                                fontSize = 12.sp,
                                modifier = longPressModifier.padding(horizontal = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailTaskHeader(colors: PokeclawColors, seq: Int, message: ChatMessage) {
    Card(
        colors = CardDefaults.cardColors(containerColor = colors.userBubble),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.batch_detail_task_header, seq), color = colors.userText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(4.dp))
            CopyableMessageContainer(text = message.content, colors = colors) { longPressModifier ->
                Text(
                    message.content,
                    color = colors.userText,
                    fontSize = 15.sp,
                    modifier = longPressModifier,
                )
            }
        }
    }
}

@Composable
private fun DetailToolGroup(colors: PokeclawColors, message: ChatMessage) {
    val steps = message.toolSteps.orEmpty()
    if (steps.isEmpty()) return
    val completedCount = steps.count { it.completedAt != null }
    val tokenTotal = steps.mapNotNull { it.tokenCount }.sum()
    val groupTitle = message.groupTitle
    val title = when {
        groupTitle.isNullOrBlank() -> stringResource(R.string.batch_execution_steps)
        groupTitle == "Replay steps" -> stringResource(R.string.batch_replay_steps)
        else -> groupTitle
    }
    val tokenMeta = if (tokenTotal > 0) " · " + stringResource(R.string.batch_step_tokens, formatStepTokens(tokenTotal)) else ""
    val meta = "$completedCount/${steps.size}$tokenMeta"

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = StraightControlShape,
        color = colors.surface,
        border = BorderStroke(0.5.dp, colors.inputBorder),
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.textSecondary, modifier = Modifier.weight(1f))
                Text(meta, fontSize = 11.sp, color = colors.textTertiary)
            }
            HorizontalDivider(color = colors.divider, thickness = 0.5.dp)
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                steps.forEachIndexed { index, step ->
                    DetailStepRow(step = step, isLast = index == steps.lastIndex, colors = colors)
                }
            }
        }
    }
}

@Composable
private fun DetailStepRow(step: ToolStep, isLast: Boolean, colors: PokeclawColors) {
    val dotColor = when {
        step.completedAt == null -> colors.textTertiary
        step.success -> colors.accent
        else -> ErrRed
    }
    val durationText = step.durationMs?.let { formatStepDuration(it) } ?: stringResource(R.string.batch_step_running)
    val tokenText = step.tokenText?.let { " · " + stringResource(R.string.batch_step_tokens, it) }.orEmpty()
    val costText = step.costText?.let { " · $it" }.orEmpty()

    Row(modifier = Modifier.fillMaxWidth()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(14.dp),
        ) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(dotColor))
            if (!isLast) {
                Box(Modifier.width(1.dp).height(34.dp).background(colors.divider))
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    step.toolName,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(formatStepTimestamp(step.startedAt), fontSize = 10.sp, color = colors.textTertiary)
            }
            Spacer(Modifier.height(2.dp))
            Text(
                stringResource(R.string.batch_step_duration, durationText) + tokenText + costText,
                fontSize = 10.sp,
                color = colors.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (BuildConfig.DEBUG && !step.params.isNullOrBlank()) {
                Text(stringResource(R.string.batch_step_params, step.params), fontSize = 10.sp, color = colors.textTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (step.summary.isNotBlank()) {
                CopyableMessageContainer(text = step.summary, colors = colors) { longPressModifier ->
                    Text(
                        step.summary,
                        fontSize = 11.sp,
                        color = colors.textTertiary,
                        modifier = longPressModifier,
                    )
                }
            }
            if (!step.intent.isNullOrBlank()) {
                CopyableMessageContainer(text = step.intent, colors = colors) { longPressModifier ->
                    Text(
                        stringResource(R.string.batch_step_intent, step.intent),
                        fontSize = 11.sp,
                        color = colors.textSecondary,
                        modifier = longPressModifier,
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailResult(
    colors: PokeclawColors,
    message: ChatMessage,
    onPlay: (String) -> Unit,
) {
    // A chat-only completion is a SUCCESS sub-scenario: it carries taskStatus=SUCCESS (so the task
    // list and persistence treat it as a success), but in the detail we keep the original "reply"
    // title + message icon to tell a pure chat answer apart from a device-task success. Everything
    // else — the success green color, the card, the body — stays identical to SUCCESS.
    val isChatReply = message.reasonCode == TaskReasonCode.CHAT_ONLY
    val style = if (isChatReply) PhaseView(R.string.batch_status_reply, Color(0xFF4ADE80)) else statusStyle(message.taskStatus, colors)
    val icon = if (isChatReply) Icons.Filled.ChatBubbleOutline else statusIcon(message.taskStatus)
    Card(
        colors = CardDefaults.cardColors(containerColor = colors.aiBubble),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 14.dp, top = 10.dp, end = 14.dp, bottom = 8.dp),
            ) {
                Icon(icon, contentDescription = null, tint = style.color, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(style.label), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = style.color)
            }
            HorizontalDivider(color = colors.divider, thickness = 0.5.dp)
            if (message.content.isNotBlank()) {
                CopyableMessageContainer(text = message.content, colors = colors) { longPressModifier ->
                    Text(
                        message.content,
                        color = colors.aiText,
                        fontSize = 15.sp,
                        lineHeight = 21.sp,
                        modifier = longPressModifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
    if (!message.recordingId.isNullOrBlank()) {
        Surface(
            modifier = Modifier.padding(top = 4.dp).clickable { onPlay(message.recordingId) },
            shape = CompactSurfaceShape,
            color = colors.surface,
            border = BorderStroke(0.5.dp, colors.inputBorder),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Videocam, contentDescription = null, tint = colors.accent, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.batch_view_recording), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = colors.textSecondary)
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(12.dp))
            }
        }
    }
}

private fun statusStyle(status: TaskStatus?, colors: PokeclawColors): PhaseView = when (status) {
    TaskStatus.SUCCESS -> PhaseView(R.string.batch_result_success, Color(0xFF4ADE80))
    TaskStatus.FAILED -> PhaseView(R.string.batch_result_failed, Color(0xFFF87171))
    TaskStatus.STOPPED -> PhaseView(R.string.batch_result_stopped, Color(0xFFFBBF24))
    TaskStatus.CANCELLED -> PhaseView(R.string.batch_result_cancelled, colors.textTertiary)
    null -> PhaseView(R.string.batch_status_reply, colors.textTertiary)
}

@Composable
private fun ToggleRow(
    colors: PokeclawColors,
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) colors.textPrimary else colors.textTertiary, fontSize = 14.sp)
            Text(subtitle, color = colors.textTertiary, fontSize = 12.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = colors.accent,
                uncheckedThumbColor = colors.surface,
                uncheckedTrackColor = colors.divider,
                uncheckedBorderColor = colors.inputBorder,
            ),
        )
    }
}

@Composable
private fun SkillPickerDialog(
    colors: PokeclawColors,
    skills: List<SkillDefinition>,
    templatesFor: (String) -> List<ExecutionTemplate>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var detailSkill by remember { mutableStateOf<SkillDefinition?>(null) }
    var searchQuery by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        val preview = detailSkill
        if (preview != null) {
            val template = templatesFor(preview.skillId)
                .filter { runCatching { SkillMatcher.matchesCurrentEnvironment(it) }.getOrDefault(false) }
                .firstOrNull { it.isAvailable }
            SkillReplayPreviewCard(
                skill = preview,
                template = template,
                colors = colors,
                onBack = { detailSkill = null },
                taskDescription = preview.originalTaskText.ifBlank { preview.title },
                showReplayChip = false,
            )
        } else {
            Card(
                colors = CardDefaults.cardColors(containerColor = colors.surface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.batch_skill_picker_title), color = colors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text(stringResource(R.string.batch_skill_search_hint), color = colors.textTertiary, fontSize = 13.sp) },
                        singleLine = true,
                        leadingIcon = {
                            Icon(
                                Icons.Outlined.Search,
                                contentDescription = null,
                                tint = colors.textTertiary,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(
                                        Icons.Filled.Clear,
                                        contentDescription = null,
                                        tint = colors.textTertiary,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = colors.textPrimary,
                            unfocusedTextColor = colors.textPrimary,
                            focusedBorderColor = colors.accent,
                            unfocusedBorderColor = colors.inputBorder,
                            cursorColor = colors.accent,
                        ),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 36.dp),
                        shape = RoundedCornerShape(8.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    if (skills.isEmpty()) {
                        Text(stringResource(R.string.batch_skill_picker_empty), color = colors.textTertiary, fontSize = 13.sp)
                    } else {
                        val filteredSkills = remember(searchQuery, skills) {
                            if (searchQuery.isBlank()) skills
                            else skills.filter { it.title.contains(searchQuery.trim(), ignoreCase = true) }
                        }
                        if (filteredSkills.isEmpty()) {
                            Text(
                                stringResource(R.string.batch_skill_search_no_match),
                                color = colors.textTertiary,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(vertical = 24.dp),
                            )
                        } else {
                            LazyColumn(modifier = Modifier.height(420.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(filteredSkills, key = { it.skillId }) { item ->
                                    val envTemplates = remember(item.skillId) {
                                        templatesFor(item.skillId)
                                            .filter { runCatching { SkillMatcher.matchesCurrentEnvironment(it) }.getOrDefault(false) }
                                    }
                                    val hasTemplate = envTemplates.any { it.isAvailable }
                                    Card(
                                        colors = CardDefaults.cardColors(containerColor = colors.background),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Column(Modifier.weight(1f)) {
                                                Text(item.title, color = colors.textPrimary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Text(
                                                    if (hasTemplate) {
                                                        val t = envTemplates.first { it.isAvailable }
                                                        stringResource(R.string.batch_skill_steps, t.steps.size)
                                                    } else stringResource(R.string.batch_skill_no_template),
                                                    color = if (hasTemplate) colors.textTertiary else WarnAmber,
                                                    fontSize = 12.sp,
                                                )
                                            }
                                            Spacer(Modifier.width(8.dp))
                                            IconButton(onClick = { detailSkill = item }) {
                                                Icon(
                                                    Icons.Outlined.Info,
                                                    contentDescription = stringResource(R.string.batch_cd_skill_details),
                                                    tint = colors.accent,
                                                    modifier = Modifier.size(20.dp),
                                                )
                                            }
                                            Button(
                                                onClick = { onPick(item.originalTaskText.ifBlank { item.title }) },
                                                colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                                                modifier = Modifier.height(36.dp),
                                            ) { Text(stringResource(R.string.batch_add), fontSize = 13.sp) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = onDismiss,
                        colors = ButtonDefaults.textButtonColors(contentColor = colors.accent),
                        modifier = Modifier.align(Alignment.End),
                    ) { Text(stringResource(R.string.common_close)) }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(
    colors: PokeclawColors,
    label: String,
    value: String,
    valueColor: Color = colors.textPrimary,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = colors.textSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, color = valueColor, fontSize = 13.sp)
    }
}