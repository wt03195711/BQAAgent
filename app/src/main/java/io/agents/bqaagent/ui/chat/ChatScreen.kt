// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import io.agents.bqaagent.utils.SherpaOnnxHelper
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import io.agents.bqaagent.R
import io.agents.bqaagent.agent.skill.Skill
import io.agents.bqaagent.agent.skill.SkillCategory
import io.agents.bqaagent.agent.skill.SkillRegistry
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.launch
import io.agents.bqaagent.agent.CloudProvider
import io.agents.bqaagent.agent.llm.ModelConfigRepository
import io.agents.bqaagent.utils.KVUtils
import io.agents.bqaagent.utils.PermissionUtils
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * BQAAgent Chat Screen — Jetpack Compose
 */

// ======================== THEME COLORS ========================

data class PokeclawColors(
    val background: Color,
    val surface: Color,
    val userBubble: Color,
    val userText: Color,
    val aiBubble: Color,
    val aiBubbleBorder: Color,
    val aiText: Color,
    val avatar: Color,
    val accent: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val divider: Color,
    val inputBorder: Color,
)

private val StraightControlShape = RoundedCornerShape(0.dp)
private val CompactSurfaceShape = RoundedCornerShape(4.dp)

val AbyssDark = PokeclawColors(
    background = Color(0xFF000000),
    surface = Color(0xFF111111),
    userBubble = Color(0xFFDB0011),
    userText = Color.White,
    aiBubble = Color(0xFF171717),
    aiBubbleBorder = Color(0xFF333333),
    aiText = Color(0xFFF2F2F2),
    avatar = Color(0xFFDB0011),
    accent = Color(0xFFFF3342),
    textPrimary = Color(0xFFF2F2F2),
    textSecondary = Color(0xFFB3B3B3),
    textTertiary = Color(0xFF8C8C8C),
    divider = Color(0xFF262626),
    inputBorder = Color(0xFF4A4A4A),
)

private fun Modifier.dismissKeyboardOnBackgroundTap(onDismissKeyboard: () -> Unit): Modifier =
    pointerInput(onDismissKeyboard) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            val up = waitForUpOrCancellation()
            if (up != null) {
                onDismissKeyboard()
            }
        }
    }

// ======================== MAIN SCREEN ========================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    messages: List<ChatMessage>,
    modelStatus: String,
    needsPermission: Boolean,
    isAwaitingReply: Boolean,
    isTaskRunning: Boolean,
    isDownloading: Boolean = false,
    downloadProgress: Int = 0,
    isLocalModel: Boolean = true,
    usesExplicitInputModes: Boolean = isLocalModel,
    sessionTokens: Int = 0,
    sessionCost: Double = 0.0,
    onSendChat: (String) -> Unit,
    onSendTask: (String) -> Unit,
    onStartMonitor: (MonitorTargetSpec) -> Unit = {},
    onSendDirectMessage: (contact: String, app: String, message: String) -> Unit = { _, _, _ -> },
    onSendUnified: (String) -> Unit = onSendTask,
    onNewChat: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenModels: () -> Unit,
    onFixPermissions: () -> Unit,
    onAttach: () -> Unit,
    conversations: List<ChatHistoryManager.ConversationSummary>,
    onSelectConversation: (ChatHistoryManager.ConversationSummary) -> Unit,
    onDeleteConversation: (ChatHistoryManager.ConversationSummary) -> Unit = {},
    onRenameConversation: (ChatHistoryManager.ConversationSummary, String) -> Unit = { _, _ -> },
    activeTasks: List<String> = emptyList(),
    onStopTask: (String) -> Unit = {},
    onStopAllTasks: () -> Unit = {},
    inputEnabled: Boolean = true,
    onModelSwitch: (modelId: String, displayName: String) -> Unit = { _, _ -> },
    colors: PokeclawColors = AbyssDark,
    voiceEnabled: Boolean = false,
    onRequestRecordPermission: () -> Unit,
    showUserImageUpload: Boolean = false,
    onUploadImage: () -> Unit = {},
    onSkipImageUpload: () -> Unit = {},
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val dismissKeyboard = {
        keyboardController?.hide()
        focusManager.clearFocus()
    }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    // Shared state for prompt chip → input bar prefill
    var prefillText by remember { mutableStateOf("") }
    var prefillIsTask by remember { mutableStateOf(false) }
    // Task mode state — lifted here so content area can react
    var isTaskMode by remember { mutableStateOf(false) }
    // Skill dialog and activation states
    var showMonitorSheet by remember { mutableStateOf(false) }
    var showSendSheet by remember { mutableStateOf(false) }
    var activatingSkill by remember { mutableStateOf<String?>(null) }

    // Chat mode is always the default — user can switch to Task manually

    // When activating finishes (2s animation), clear state
    LaunchedEffect(activatingSkill) {
        if (activatingSkill != null) {
            kotlinx.coroutines.delay(2000)
            activatingSkill = null
        }
    }

    LaunchedEffect(usesExplicitInputModes) {
        if (!usesExplicitInputModes) {
            isTaskMode = false
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = colors.surface,
            ) {
                SidebarContent(
                    conversations = conversations,
                    onNewChat = {
                        scope.launch { drawerState.close() }
                        onNewChat()
                    },
                    onSelectConversation = {
                        scope.launch { drawerState.close() }
                        onSelectConversation(it)
                    },
                    onDeleteConversation = onDeleteConversation,
                    onRenameConversation = onRenameConversation,
                    onSettings = {
                        scope.launch { drawerState.close() }
                        onOpenSettings()
                    },
                    onModels = {
                        scope.launch { drawerState.close() }
                        onOpenModels()
                    },
                    colors = colors,
                )
            }
        }
    ) {
        Scaffold(
            containerColor = colors.background,
            topBar = {
                Column(
                    modifier = Modifier.dismissKeyboardOnBackgroundTap(dismissKeyboard)
                ) {
                    ChatTopBar(
                        onMenuClick = { scope.launch { drawerState.open() } },
                        onSettings = onOpenSettings,
                        colors = colors,
                    )
                    if (activeTasks.isNotEmpty()) {
                        ActiveTaskBar(
                            tasks = activeTasks,
                            onStopTask = onStopTask,
                            onStopAll = onStopAllTasks,
                            colors = colors,
                        )
                    }
                }
            },
            bottomBar = {
                if (!isDownloading) {
                    Column(
                        modifier = Modifier.imePadding()
                    ) {
                        val showQuickTaskTemplates = false
                        // TODO 隐藏 Quick task templates
                        if (showQuickTaskTemplates) {
                            QuickTasksPanel(
                                isLocalModel = usesExplicitInputModes,
                                onFillTask = { text ->
                                    prefillText = text
                                    prefillIsTask = true
                                    if (usesExplicitInputModes) isTaskMode = true
                                },
                                onMonitorClick = { showMonitorSheet = true },
                                monitorActive = activeTasks.isNotEmpty(),
                                colors = colors,
                            )
                        }

                        ChatInputBar(
                            isAwaitingReply = isAwaitingReply,
                            isTaskRunning = isTaskRunning,
                            inputEnabled = inputEnabled,
                            isTaskMode = isTaskMode,
                            usesExplicitInputModes = usesExplicitInputModes,
                            onTaskModeChange = { isTaskMode = it },
                            onSendChat = onSendChat,
                            onSendTask = onSendTask,
                            onSendUnified = onSendUnified,
                            onStopAll = onStopAllTasks,
                            onAttach = onAttach,
                            modelStatus = modelStatus,
                            isLocalModel = isLocalModel,
                            sessionTokens = sessionTokens,
                            sessionCost = sessionCost,
                            onModelSwitch = onModelSwitch,
                            onSettings = onOpenModels,
                            colors = colors,
                            prefillText = prefillText,
                            prefillIsTask = prefillIsTask,
                            onPrefillConsumed = { prefillText = "" },
                            voiceEnabled = voiceEnabled,
                            // 新增权限回调
                            onRequestRecordPermission = onRequestRecordPermission
                        )
                    }
                }
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .dismissKeyboardOnBackgroundTap(dismissKeyboard)
            ) {
                if (!isDownloading) {
                    // v9: always show messages or empty state regardless of mode
                    val userMessages = messages.filter { it.role != ChatMessage.Role.SYSTEM }
                    if (userMessages.isEmpty()) {
                        EmptyStateWithPrompts(
                            isLocalModel = usesExplicitInputModes,
                            onSelectPrompt = { text, isTask ->
                                prefillText = text
                                prefillIsTask = isTask
                                if (isTask && usesExplicitInputModes) isTaskMode = true
                            },
                            colors = colors,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        MessageList(
                            messages = messages,
                            colors = colors,
                            onBackgroundTap = dismissKeyboard,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                if (showUserImageUpload) {
                    UserImageUploadCard(
                        onUpload = onUploadImage,
                        onSkip = onSkipImageUpload,
                        colors = colors,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 80.dp),
                    )
                }

                // Download blocking overlay
                if (isDownloading) {
                    DownloadOverlay(progress = downloadProgress, colors = colors)
                }
            }
        }
    }

    // Monitor skill dialog
    if (showMonitorSheet) {
        MonitorDialog(
            onDismiss = { showMonitorSheet = false },
            onStart = { target ->
                showMonitorSheet = false
                activatingSkill = "monitor"
                onStartMonitor(target)
            },
            colors = colors,
        )
    }

    // Send Message skill dialog
    if (showSendSheet) {
        SendMessageDialog(
            onDismiss = { showSendSheet = false },
            onSend = { contact, app, message ->
                showSendSheet = false
                onSendDirectMessage(contact, app, message)
            },
            colors = colors,
        )
    }

//    if (showAsrModelDialog) {
//        AlertDialog(
//            onDismissRequest = { },
//            containerColor = colors.surface,
//            title = {
//                Text("初始化语音识别", color = colors.textPrimary)
//            },
//            text = {
//                Column {
//                    Text(
//                        "正在下载离线语音模型（首次使用）...",
//                        fontSize = 13.sp,
//                        color = colors.textSecondary,
//                    )
//                    Spacer(Modifier.height(16.dp))
//                    LinearProgressIndicator(
//                        progress = { asrDownloadProgress / 100f },
//                        modifier = Modifier
//                            .fillMaxWidth()
//                            .height(6.dp)
//                            .clip(CompactSurfaceShape),
//                        color = colors.accent,
//                        trackColor = colors.inputBorder,
//                    )
//                    Spacer(Modifier.height(8.dp))
//                    Text(
//                        "$asrDownloadProgress%",
//                        fontSize = 14.sp,
//                        fontWeight = FontWeight.Bold,
//                        color = colors.accent,
//                    )
//                }
//            },
//            confirmButton = {},
//        )
//    }
}

// ======================== TOP BAR ========================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatTopBar(
    onMenuClick: () -> Unit,
    onSettings: () -> Unit,
    colors: PokeclawColors,
) {
    Column {
        TopAppBar(
            title = {
                Text(
                    buildAnnotatedString {
                        append("BQA")
                        withStyle(SpanStyle(color = colors.accent)) {
                            append("Agent")
                        }
                    },
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = colors.textPrimary,
                )
            },
            navigationIcon = {
                IconButton(onClick = onMenuClick) {
                    Icon(Icons.Default.Menu, contentDescription = "Menu")
                }
            },
            actions = {
                IconButton(onClick = onSettings) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings")
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

@Composable
private fun TextModelSwitcherRow(
    modelStatus: String,
    isLocalModel: Boolean,
    sessionTokens: Int,
    sessionCost: Double,
    onModelSwitch: (modelId: String, displayName: String) -> Unit,
    onSettings: () -> Unit,
    colors: PokeclawColors,
) {
    var showModelMenu by remember { mutableStateOf(false) }
    val configuration = LocalConfiguration.current
    val menuWidth = (configuration.screenWidthDp - 32)
        .coerceAtLeast(310)
        .coerceAtMost(420)
        .dp
    val currentModelName = modelStatus
        .substringBefore(" · ")
        .ifBlank { "No model selected" }

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showModelMenu = true }
                .padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = currentModelName,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = if (showModelMenu) colors.accent else colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = "Switch model",
                tint = if (showModelMenu) colors.accent else colors.textSecondary,
                modifier = Modifier.size(18.dp),
            )
        }
        DropdownMenu(
            expanded = showModelMenu,
            onDismissRequest = { showModelMenu = false },
            modifier = Modifier
                .width(menuWidth)
                .background(colors.surface)
                .border(1.dp, colors.aiBubbleBorder, StraightControlShape),
        ) {
            ModelSwitcherMenuContent(
                modelStatus = modelStatus,
                isLocalModel = isLocalModel,
                sessionTokens = sessionTokens,
                sessionCost = sessionCost,
                onModelSwitch = { modelId, displayName ->
                    showModelMenu = false
                    onModelSwitch(modelId, displayName)
                },
                onSettings = {
                    showModelMenu = false
                    onSettings()
                },
                colors = colors,
            )
        }
    }
}

@Composable
private fun ModelSwitcherMenuContent(
    modelStatus: String,
    isLocalModel: Boolean,
    sessionTokens: Int,
    sessionCost: Double,
    onModelSwitch: (modelId: String, displayName: String) -> Unit,
    onSettings: () -> Unit,
    colors: PokeclawColors,
) {
    val resolvedConfig = ModelConfigRepository.snapshot()
    val currentModelName = modelStatus
        .substringBefore(" · ")
        .ifBlank { "No model selected" }

    ModelMenuSectionLabel("Current model", colors)
    ModelMenuInfoRow(
        title = currentModelName,
        subtitle = if (isLocalModel) "Phone model" else "Online model",
        colors = colors,
        selected = true,
    )

    if (sessionTokens > 0 && !isLocalModel) {
        ModelMenuInfoRow(
            title = "Usage",
            subtitle = formatModelUsage(sessionTokens, sessionCost),
            colors = colors,
            selected = false,
        )
    }

    HorizontalDivider(color = colors.divider, thickness = 0.5.dp)
    ModelMenuSectionLabel("Phone models", colors)
    val localPath = resolvedConfig.local.modelPath
    if (localPath.isNotBlank() && File(localPath).exists()) {
        val localName = resolvedConfig.local.displayName.ifBlank {
            File(localPath).nameWithoutExtension.replace("-", " ").replace("_", " ")
        }
        ModelMenuRow(
            title = localName,
            subtitle = "Gemma 4 on device",
            selected = isLocalModel,
            trailing = if (isLocalModel) "Active" else null,
            colors = colors,
            onClick = { onModelSwitch("LOCAL", localName) },
        )
    } else {
        ModelMenuRow(
            title = "No phone model",
            subtitle = "Download Gemma 4 E2B or E4B",
            selected = false,
            trailing = "Open",
            colors = colors,
            onClick = onSettings,
        )
    }

    HorizontalDivider(color = colors.divider, thickness = 0.5.dp)
    ModelMenuSectionLabel("Online models", colors)
    val cloudConfig = if (resolvedConfig.isLocalActive()) {
        resolvedConfig.defaultCloud
    } else {
        resolvedConfig.activeCloud
    }
    val cloudProvider = cloudConfig.provider
    if (cloudConfig.isConfigured) {
        val cloudRows = if (cloudProvider.models.isNotEmpty()) {
            cloudProvider.models
        } else {
            listOf(
                io.agents.bqaagent.agent.CloudModel(
                    id = cloudConfig.modelName,
                    displayName = cloudConfig.modelName,
                    inputPricePerM = 0.0,
                    outputPricePerM = 0.0,
                    tier = io.agents.bqaagent.agent.ModelTier.SMART,
                    contextSize = 0,
                )
            )
        }
        cloudRows.forEach { model ->
            val selected = !isLocalModel && model.id == resolvedConfig.activeCloud.modelName
            ModelMenuRow(
                title = model.displayName,
                subtitle = if (cloudProvider == CloudProvider.CUSTOM) cloudConfig.resolvedBaseUrl else model.id,
                selected = selected,
                trailing = if (selected) "Active" else null,
                colors = colors,
                onClick = { onModelSwitch(model.id, model.displayName) },
            )
        }
    } else {
        ModelMenuRow(
            title = "Online setup required",
            subtitle = "Configure API key and model",
            selected = false,
            trailing = "Open",
            colors = colors,
            onClick = onSettings,
        )
    }

    HorizontalDivider(color = colors.divider, thickness = 0.5.dp)
    ModelMenuActionRow(
        title = "Manage models",
        colors = colors,
        onClick = onSettings,
    )
}

@Composable
private fun ModelMenuInfoRow(
    title: String,
    subtitle: String,
    colors: PokeclawColors,
    selected: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) colors.background else colors.surface)
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(32.dp)
                .background(if (selected) colors.accent else Color.Transparent),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 13.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                fontSize = 11.sp,
                color = colors.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun formatModelUsage(sessionTokens: Int, sessionCost: Double): String {
    val formattedTokens = if (sessionTokens >= 1000) {
        String.format(Locale.US, "%.1fK", sessionTokens / 1000.0)
    } else {
        "$sessionTokens"
    }
    val costText = when {
        sessionCost <= 0.0 -> null
        sessionCost < 0.01 -> "< $0.01"
        else -> "$${String.format(Locale.US, "%.2f", sessionCost)}"
    }
    return if (costText == null) {
        "$formattedTokens tokens"
    } else {
        "$formattedTokens tokens · $costText"
    }
}

@Composable
private fun ModelMenuSectionLabel(text: String, colors: PokeclawColors) {
    Text(
        text = text.uppercase(Locale.ROOT),
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        color = colors.textTertiary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

@Composable
private fun ModelMenuRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    trailing: String?,
    colors: PokeclawColors,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) colors.background else colors.surface)
            .clickable(onClick = onClick)
            .heightIn(min = 54.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(34.dp)
                .background(if (selected) colors.accent else Color.Transparent),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 13.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = colors.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (!trailing.isNullOrBlank()) {
            Text(
                text = trailing,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = colors.textSecondary,
                modifier = Modifier.padding(end = 16.dp),
            )
        }
    }
}

@Composable
private fun ModelMenuActionRow(
    title: String,
    colors: PokeclawColors,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Settings,
            contentDescription = null,
            tint = colors.textSecondary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = title,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = colors.textPrimary,
        )
    }
}

// ======================== PERMISSION BANNER ========================

@Composable
private fun PermissionBanner(onClick: () -> Unit, colors: PokeclawColors) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = colors.accent.copy(alpha = 0.12f),
        ),
        shape = StraightControlShape,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Shield, contentDescription = null, tint = colors.accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                "Permissions needed. Tap to fix.",
                color = colors.accent,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = colors.accent, modifier = Modifier.size(20.dp))
        }
    }
}

// ======================== MESSAGE LIST ========================

@Composable
private fun MessageList(
    messages: List<ChatMessage>,
    colors: PokeclawColors,
    onBackgroundTap: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val lastMessage = messages.lastOrNull()
    val lastMessageScrollKey = lastMessage?.let { message ->
        listOf(
            message.role.name,
            message.content.hashCode(),
            message.modelName.orEmpty(),
            message.toolSteps?.size ?: 0,
            message.toolSteps?.lastOrNull()?.summary?.hashCode() ?: 0,
            message.toolSteps?.lastOrNull()?.completedAt ?: 0L,
        ).joinToString("|")
    }.orEmpty()

    LaunchedEffect(messages.size, lastMessageScrollKey) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(onBackgroundTap) {
                detectTapGestures(onTap = { onBackgroundTap() })
            },
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        items(messages.size) { index ->
            val message = messages[index]
            when (message.role) {
                ChatMessage.Role.USER -> UserBubble(message.content, message.timestamp, colors)
                ChatMessage.Role.ASSISTANT -> AssistantBubble(message.content, message.timestamp, colors, message.modelName)
                ChatMessage.Role.SYSTEM -> SystemMessage(message.content, colors)
                ChatMessage.Role.TOOL_GROUP -> ToolGroup(message, colors)
            }
        }
        item {
            Spacer(Modifier.height(1.dp))
        }
    }
}

// ======================== BUBBLES ========================

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CopyableMessageContainer(
    text: String,
    colors: PokeclawColors,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    val context = LocalContext.current
    var menuExpanded by remember { mutableStateOf(false) }
    var showSelectableText by remember { mutableStateOf(false) }
    val copyable = text.isNotBlank() && text != "..."

    Box(modifier = modifier) {
        content(
            if (copyable) {
                Modifier.combinedClickable(
                    onClick = {},
                    onLongClick = { menuExpanded = true },
                )
            } else {
                Modifier
            }
        )

        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            containerColor = colors.surface,
        ) {
            DropdownMenuItem(
                text = { Text("Copy", color = colors.textPrimary) },
                leadingIcon = {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, tint = colors.textSecondary)
                },
                onClick = {
                    menuExpanded = false
                    copyTextToClipboard(context, text)
                },
            )
            DropdownMenuItem(
                text = { Text("Select text", color = colors.textPrimary) },
                leadingIcon = {
                    Icon(Icons.Default.Edit, contentDescription = null, tint = colors.textSecondary)
                },
                onClick = {
                    menuExpanded = false
                    showSelectableText = true
                },
            )
        }
    }

    if (showSelectableText) {
        SelectableMessageDialog(
            text = text,
            colors = colors,
            onCopyAll = { copyTextToClipboard(context, text) },
            onDismiss = { showSelectableText = false },
        )
    }
}

@Composable
private fun SelectableMessageDialog(
    text: String,
    colors: PokeclawColors,
    onCopyAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = {
            Text("Select text", color = colors.textPrimary)
        },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
                    .background(colors.background, StraightControlShape)
                    .padding(12.dp),
            ) {
                SelectionContainer {
                    Text(
                        text = text,
                        color = colors.textPrimary,
                        fontSize = 15.sp,
                        lineHeight = 21.sp,
                    )
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    onCopyAll()
                    onDismiss()
                },
            ) {
                Text("Copy all", color = colors.textSecondary)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done", color = colors.accent)
            }
        },
    )
}

@Composable
private fun UserImageUploadCard(
    onUpload: () -> Unit,
    onSkip: () -> Unit,
    colors: PokeclawColors,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        color = colors.surface,
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Default.AddPhotoAlternate,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(36.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Screenshot Required",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "The current screen is protected. Please take a screenshot and upload it to continue.",
                fontSize = 13.sp,
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
                lineHeight = 18.sp,
            )
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = onSkip,
                    modifier = Modifier.weight(1f),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.textSecondary),
                ) {
                    Text("Skip", fontSize = 14.sp)
                }
                Button(
                    onClick = onUpload,
                    modifier = Modifier.weight(1f),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                ) {
                    Icon(
                        Icons.Default.Upload,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Upload", fontSize = 14.sp, color = Color.White)
                }
            }
        }
    }
}

private fun copyTextToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("BQAAgent message", text))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}

@Composable
private fun UserBubble(text: String, timestamp: Long, colors: PokeclawColors) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 64.dp, end = 14.dp, top = 3.dp, bottom = 3.dp),
    ) {
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            CopyableMessageContainer(text = text, colors = colors) { longPressModifier ->
                Surface(
                    modifier = longPressModifier,
                    color = colors.userBubble,
                    shape = CompactSurfaceShape,
                ) {
                    Text(
                        text = text,
                        color = colors.userText,
                        fontSize = 15.sp,
                        lineHeight = 21.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }
        }
        Text(
            text = formatBubbleTimestamp(timestamp),
            fontSize = 9.sp,
            color = colors.textTertiary,
            modifier = Modifier
                .align(Alignment.End)
                .padding(end = 6.dp, top = 1.dp, bottom = 2.dp),
        )
    }
}

@Composable
private fun AssistantBubble(text: String, timestamp: Long, colors: PokeclawColors, modelName: String? = null) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 64.dp, top = 3.dp, bottom = 3.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.Bottom,
        ) {
            // Avatar
            androidx.compose.foundation.Image(
                painter = painterResource(R.drawable.bqa_agent_icon),
                contentDescription = "BQAAgent",
                modifier = Modifier
                    .size(32.dp)
                    .clip(CompactSurfaceShape),
            )
            Spacer(Modifier.width(8.dp))

            // Bubble
            if (text == "...") {
                Surface(
                    color = colors.aiBubble,
                    shape = CompactSurfaceShape,
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, colors.aiBubbleBorder),
                ) {
                    TypingIndicator(
                        color = colors.textTertiary,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                    )
                }
            } else {
                CopyableMessageContainer(text = text, colors = colors) { longPressModifier ->
                    Surface(
                        modifier = longPressModifier,
                        color = colors.aiBubble,
                        shape = CompactSurfaceShape,
                        border = androidx.compose.foundation.BorderStroke(0.5.dp, colors.aiBubbleBorder),
                    ) {
                        Text(
                            text = text,
                            color = colors.aiText,
                            fontSize = 15.sp,
                            lineHeight = 21.sp,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        )
                    }
                }
            }
        }
        if (text != "...") {
            val footer = listOfNotNull(
                modelName?.takeIf { it.isNotBlank() },
                formatBubbleTimestamp(timestamp)
            ).joinToString(" · ")
            Text(
                text = footer,
                fontSize = 9.sp,
                color = colors.textTertiary,
                modifier = Modifier.padding(start = 40.dp, top = 1.dp, bottom = 2.dp),
            )
        }
    }
}

private fun formatBubbleTimestamp(timestamp: Long): String {
    val pattern = if (DateUtils.isToday(timestamp)) "h:mm a" else "MMM d, h:mm a"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(timestamp))
}

@Composable
private fun TypingIndicator(color: Color, modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "typing")
    val dots = listOf(0, 1, 2)

    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        dots.forEach { index ->
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.2f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(600, delayMillis = index * 200),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot$index",
            )
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CompactSurfaceShape)
                    .background(color.copy(alpha = alpha)),
            )
        }
    }
}

@Composable
private fun SystemMessage(text: String, colors: PokeclawColors) {
    CopyableMessageContainer(text = text, colors = colors) { longPressModifier ->
        Text(
            text = text,
            color = colors.textTertiary,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = longPressModifier
                .fillMaxWidth()
                .padding(horizontal = 40.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun ToolGroup(message: ChatMessage, colors: PokeclawColors) {
    val steps = remember(message.toolSteps, message.content) {
        message.toolSteps?.takeIf { it.isNotEmpty() } ?: parseToolStepsFromContent(message.content, message.timestamp)
    }
    if (steps.isEmpty()) return

    var expanded by remember(message.timestamp) { mutableStateOf(false) }
    val completedCount = steps.count { it.completedAt != null }
    val tokenTotal = steps.mapNotNull { it.tokenCount }.sum()
    val headerMeta = buildString {
        append("$completedCount/${steps.size}")
        if (tokenTotal > 0) append(" · ${formatStepTokens(tokenTotal)} tokens")
    }

    Surface(
        modifier = Modifier
            .padding(start = 54.dp, end = 64.dp, top = 4.dp, bottom = 4.dp)
            .fillMaxWidth(),
        shape = StraightControlShape,
        color = colors.surface,
        border = androidx.compose.foundation.BorderStroke(0.5.dp, colors.inputBorder),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                    contentDescription = if (expanded) "Collapse steps" else "Expand steps",
                    tint = colors.textSecondary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "Execution steps",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textSecondary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    headerMeta,
                    fontSize = 11.sp,
                    color = colors.textTertiary,
                )
            }

            if (expanded) {
                HorizontalDivider(color = colors.divider, thickness = 0.5.dp)
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    steps.forEachIndexed { index, step ->
                        StepTimelineRow(
                            step = step,
                            isLast = index == steps.lastIndex,
                            colors = colors,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StepTimelineRow(step: ToolStep, isLast: Boolean, colors: PokeclawColors) {
    val dotColor = when {
        step.completedAt == null -> colors.textTertiary
        step.success -> colors.accent
        else -> Color(0xFFF87171)
    }
    val durationText = step.durationMs?.let { formatStepDuration(it) } ?: "running"
    val tokenText = step.tokenText?.let { " · $it tokens" }.orEmpty()
    val costText = step.costText?.let { " · $it" }.orEmpty()

    Row(modifier = Modifier.fillMaxWidth()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(dotColor, CompactSurfaceShape),
            )
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(34.dp)
                        .background(colors.divider),
                )
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
                Text(
                    formatStepTimestamp(step.startedAt),
                    fontSize = 10.sp,
                    color = colors.textTertiary,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                "Duration $durationText$tokenText$costText",
                fontSize = 10.sp,
                color = colors.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (step.summary.isNotBlank()) {
                Text(
                    step.summary,
                    fontSize = 11.sp,
                    color = colors.textTertiary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun formatStepTimestamp(timestamp: Long): String {
    val pattern = if (DateUtils.isToday(timestamp)) "h:mm:ss a" else "MMM d, h:mm:ss a"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(timestamp))
}

private fun formatStepDuration(durationMs: Long): String {
    return when {
        durationMs < 1000 -> "${durationMs}ms"
        durationMs < 60_000 -> String.format(Locale.US, "%.1fs", durationMs / 1000.0)
        else -> {
            val minutes = durationMs / 60_000
            val seconds = (durationMs % 60_000) / 1000
            "${minutes}m ${seconds}s"
        }
    }
}

private fun formatStepTokens(tokens: Int): String {
    return when {
        tokens < 1000 -> tokens.toString()
        tokens < 1_000_000 -> String.format(Locale.US, "%.1fK", tokens / 1000.0)
        else -> String.format(Locale.US, "%.1fM", tokens / 1_000_000.0)
    }
}

private fun parseToolStepsFromContent(content: String, fallbackTimestamp: Long): List<ToolStep> {
    return content.lines().mapNotNull { rawLine ->
        val line = rawLine.trim().removePrefix("-").trim()
        if (line.isBlank()) return@mapNotNull null

        val success = line.startsWith("✓") || line.startsWith("[x]", ignoreCase = true)
        val body = line
            .removePrefix("✓")
            .removePrefix("○")
            .removePrefix("✕")
            .removePrefix("…")
            .removePrefix("[x]")
            .removePrefix("[ ]")
            .trim()

        if (" | " in body) {
            val parts = body.split(" | ")
            val title = parts.firstOrNull()?.trim().orEmpty()
            val startedAt = parts.firstOrNull { it.startsWith("started=") }
                ?.removePrefix("started=")
                ?.toLongOrNull()
                ?: fallbackTimestamp
            val completedAt = parts.firstOrNull { it.startsWith("completed=") }
                ?.removePrefix("completed=")
                ?.toLongOrNull()
            val durationMs = parts.firstOrNull { it.startsWith("durationMs=") }
                ?.removePrefix("durationMs=")
                ?.toLongOrNull()
            val tokenCount = parts.firstOrNull { it.startsWith("tokens=") }
                ?.removePrefix("tokens=")
                ?.toIntOrNull()
            val tokenText = parts.firstOrNull { it.startsWith("tokenText=") }
                ?.removePrefix("tokenText=")
                ?.takeIf { it.isNotBlank() }
            val costText = parts.firstOrNull { it.startsWith("cost=") }
                ?.removePrefix("cost=")
                ?.takeIf { it.isNotBlank() }
            val isLlmCall = parts.firstOrNull { it.startsWith("llm=") }
                ?.removePrefix("llm=")
                ?.toBooleanStrictOrNull()
                ?: title.equals("LLM Call", ignoreCase = true)
            val summary = parts.lastOrNull()?.takeIf { !it.startsWith("started=") && !it.startsWith("duration=") }?.trim().orEmpty()
            ToolStep(
                toolName = title,
                summary = summary,
                success = success,
                startedAt = startedAt,
                completedAt = completedAt ?: durationMs?.let { startedAt + it },
                durationMs = durationMs,
                tokenCount = tokenCount,
                tokenText = tokenText,
                costText = costText,
                isLlmCall = isLlmCall,
            )
        } else {
            val name = body.substringBefore("→").trim()
            val summary = body.substringAfter("→", "").trim()
            ToolStep(
                toolName = name,
                summary = summary,
                success = success,
                startedAt = fallbackTimestamp,
                completedAt = fallbackTimestamp,
                durationMs = 0L,
            )
        }
    }
}

// ======================== INPUT BAR ========================
@Composable
private fun ChatInputBar(
    isAwaitingReply: Boolean,
    isTaskRunning: Boolean,
    inputEnabled: Boolean = true,
    isTaskMode: Boolean,
    usesExplicitInputModes: Boolean,
    onTaskModeChange: (Boolean) -> Unit,
    onSendChat: (String) -> Unit,
    onSendTask: (String) -> Unit,
    onSendUnified: (String) -> Unit,
    onStopAll: () -> Unit = {},
    onAttach: () -> Unit,
    modelStatus: String,
    isLocalModel: Boolean,
    sessionTokens: Int,
    sessionCost: Double,
    onModelSwitch: (modelId: String, displayName: String) -> Unit,
    onSettings: () -> Unit,
    colors: PokeclawColors,
    prefillText: String = "",
    prefillIsTask: Boolean = false,
    onPrefillConsumed: () -> Unit = {},
    voiceEnabled: Boolean = false,
    // 新增权限申请回调
    onRequestRecordPermission: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    var asrHelper by remember { mutableStateOf<SherpaOnnxHelper?>(null) }
    var isVoiceRecording by remember { mutableStateOf(false) }
    var isClickProcessing by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    LaunchedEffect(prefillText) {
        if (prefillText.isNotEmpty()) {
            text = prefillText
            if (usesExplicitInputModes) onTaskModeChange(prefillIsTask)
            onPrefillConsumed()
        }
    }

    LaunchedEffect(voiceEnabled) {
        if (voiceEnabled) {
            if (asrHelper == null) {
                asrHelper = SherpaOnnxHelper(context)
            }
            asrHelper?.let { helper ->
                if (!helper.isModelReady) {
                    helper.ensureModel()
                }
            }
        } else {
            asrHelper?.release()
            asrHelper = null
        }
    }


    fun submitInput() {
        if (isTaskRunning) {
            onStopAll()
            return
        }
        if (isAwaitingReply || !inputEnabled || text.isBlank()) return

        if (!usesExplicitInputModes) {
            onSendUnified(text.trim())
        } else if (isTaskMode) {
            onSendTask(text.trim())
        } else {
            onSendChat(text.trim())
        }
        text = ""
        focusManager.clearFocus()
        keyboardController?.hide()
    }

    // 页面销毁强制释放语音识别实例，避免引擎卡死
    DisposableEffect(Unit) {
        onDispose {
            asrHelper?.release()
        }
    }

    Column(
        modifier = Modifier
            .background(colors.surface)
            .navigationBarsPadding()
    ) {
        val showInputTopDivider = false
        if (showInputTopDivider) {
            HorizontalDivider(
                color = colors.divider,
                thickness = 1.dp,
            )
        }

        TextModelSwitcherRow(
            modelStatus = modelStatus,
            isLocalModel = isLocalModel,
            sessionTokens = sessionTokens,
            sessionCost = sessionCost,
            onModelSwitch = onModelSwitch,
            onSettings = onSettings,
            colors = colors,
        )

        if (usesExplicitInputModes) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 10.dp, top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Surface(
                    onClick = { onTaskModeChange(false) },
                    shape = StraightControlShape,
                    color = if (!isTaskMode) colors.aiBubble else Color.Transparent,
                    border = if (!isTaskMode) androidx.compose.foundation.BorderStroke(1.dp, colors.aiBubbleBorder) else null,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        "Chat",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (!isTaskMode) colors.textPrimary else colors.textTertiary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 9.dp),
                    )
                }
                Surface(
                    onClick = { onTaskModeChange(true) },
                    shape = StraightControlShape,
                    color = if (isTaskMode) colors.aiBubble else Color.Transparent,
                    border = if (isTaskMode) androidx.compose.foundation.BorderStroke(1.dp, colors.aiBubbleBorder) else null,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        "Task",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isTaskMode) colors.textPrimary else colors.textTertiary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 9.dp),
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = {
                    Text(
                        when {
                            usesExplicitInputModes && isTaskMode -> "Describe a phone task..."
                            !usesExplicitInputModes -> "Chat or give a task..."
                            else -> "Chat with local AI..."
                        },
                        color = colors.textTertiary,
                        fontSize = 14.sp,
                    )
                },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp, max = 100.dp),
                shape = StraightControlShape,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = if (isVoiceRecording) colors.accent
                    else if (isTaskMode && usesExplicitInputModes) colors.accent
                    else colors.accent.copy(alpha = 0.4f),
                    unfocusedBorderColor = if (isVoiceRecording) colors.accent.copy(alpha = 0.7f)
                    else if (isTaskMode && usesExplicitInputModes) colors.accent.copy(alpha = 0.6f)
                    else colors.inputBorder,
                    cursorColor = if (isTaskMode && usesExplicitInputModes) colors.accent else colors.accent,
                    focusedTextColor = colors.textPrimary,
                    unfocusedTextColor = colors.textPrimary,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                ),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp),
                maxLines = 4,
                trailingIcon = {
                    Surface(
                        onClick = { submitInput() },
                        modifier = Modifier
                            .size(34.dp)
                            .alpha(if ((text.isBlank() || !inputEnabled || isAwaitingReply) && !isTaskRunning) 0.35f else 1f),
                        shape = StraightControlShape,
                        color = when {
                            isTaskRunning -> Color(0xFFF44336)
                            isAwaitingReply -> colors.background
                            text.isBlank() -> colors.background
                            isTaskMode && usesExplicitInputModes -> colors.accent
                            else -> colors.userBubble
                        },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                when {
                                    isTaskRunning -> Icons.Default.Close
                                    isAwaitingReply -> Icons.Default.MoreHoriz
                                    else -> Icons.Default.ArrowUpward
                                },
                                contentDescription = when {
                                    isTaskRunning -> "Stop"
                                    isAwaitingReply -> "Waiting for reply"
                                    else -> "Send"
                                },
                                tint = Color.White,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                },
            )

            Spacer(Modifier.width(8.dp))

            if (voiceEnabled) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(40.dp)
                        .then(
                            if (isVoiceRecording) {
                                Modifier
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(colors.accent.copy(alpha = pulseAlpha))
                                    .then(Modifier.graphicsLayer {
                                        scaleX = pulseScale
                                        scaleY = pulseScale
                                    })
                            } else Modifier
                        )
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                down.consume()

                                if (isClickProcessing) {
                                    waitForUpOrCancellation()
                                    return@awaitEachGesture
                                }
                                isClickProcessing = true

                                val ctx = context

                                if (!PermissionUtils.hasRecordAudio(ctx)) {
                                    onRequestRecordPermission()
                                    isClickProcessing = false
                                    waitForUpOrCancellation()
                                    return@awaitEachGesture
                                }

                                val helper = asrHelper
                                if (helper == null || !helper.isModelReady) {
                                    Toast.makeText(ctx, "语音模型加载中...", Toast.LENGTH_SHORT).show()
                                    isClickProcessing = false
                                    waitForUpOrCancellation()
                                    return@awaitEachGesture
                                }

                                isVoiceRecording = true
                                @Suppress("MissingPermission")
                                helper.startRecognition(object : SherpaOnnxHelper.AsrCallback {
                                    override fun onPartialText(inputText: String) { text = inputText }
                                    override fun onFinalText(inputText: String) {
                                        text = inputText
                                        isVoiceRecording = false
                                        isClickProcessing = false
                                    }
                                    override fun onError(errorMsg: String) {
                                        Toast.makeText(ctx, "识别失败：$errorMsg", Toast.LENGTH_SHORT).show()
                                        isVoiceRecording = false
                                        isClickProcessing = false
                                    }
                                })

                                val up = waitForUpOrCancellation()
                                up?.consume()

                                if (helper.isRecording()) {
                                    helper.stopRecognition()
                                }
                                isVoiceRecording = false
                                isClickProcessing = false
                            }
                        },
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "长按说话",
                        tint = if (isVoiceRecording) Color.White else colors.textSecondary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }

}

// ======================== SKILL SHORTCUT BAR ========================

@Composable
private fun SkillShortcutBar(
    skills: List<Skill>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSkillTap: (Skill) -> Unit,
    colors: PokeclawColors,
) {
    val categoryIcons = mapOf(
        SkillCategory.INPUT to Icons.Outlined.Keyboard,
        SkillCategory.DISMISS to Icons.Outlined.Close,
        SkillCategory.NAVIGATION to Icons.Outlined.Navigation,
        SkillCategory.MESSAGING to Icons.Outlined.Chat,
        SkillCategory.MEDIA to Icons.Outlined.CameraAlt,
        SkillCategory.GENERAL to Icons.Outlined.AutoAwesome,
    )

    Column {
        // Toggle row
        Surface(
            onClick = onToggle,
            color = Color.Transparent,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    tint = colors.textTertiary,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "Skills",
                    fontSize = 12.sp,
                    color = colors.textTertiary,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = colors.textTertiary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        // Expanded skill chips
        if (expanded) {
            // Two rows of chips using FlowRow-style layout
            val rows = skills.chunked((skills.size + 1) / 2)
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (row in rows) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        for (skill in row) {
                            val icon = categoryIcons[skill.category] ?: Icons.Outlined.AutoAwesome
                            Surface(
                                onClick = { onSkillTap(skill) },
                                shape = StraightControlShape,
                                color = colors.accent.copy(alpha = 0.1f),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        icon,
                                        contentDescription = null,
                                        tint = colors.accent,
                                        modifier = Modifier.size(14.dp),
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        skill.name,
                                        fontSize = 11.sp,
                                        color = colors.accent,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

// ======================== DOWNLOAD OVERLAY ========================

@Composable
private fun DownloadOverlay(progress: Int, colors: PokeclawColors) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background.copy(alpha = 0.95f)),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 40.dp),
            colors = CardDefaults.cardColors(containerColor = colors.surface),
            shape = CompactSurfaceShape,
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                androidx.compose.foundation.Image(
                    painter = painterResource(R.drawable.bqa_agent_icon),
                    contentDescription = "BQAAgent",
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CompactSurfaceShape),
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "Downloading your AI brain",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "This only happens once",
                    fontSize = 13.sp,
                    color = colors.textTertiary,
                )
                Spacer(Modifier.height(24.dp))
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(CompactSurfaceShape),
                    color = colors.accent,
                    trackColor = colors.inputBorder,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "$progress%",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.accent,
                )
            }
        }
    }
}

// ======================== EMPTY STATE ========================

@Composable
private fun EmptyStateWithPrompts(
    isLocalModel: Boolean,
    onSelectPrompt: (String, Boolean) -> Unit,
    colors: PokeclawColors,
    modifier: Modifier = Modifier,
) {
    data class Prompt(val text: String, val isTask: Boolean)

    // Cloud: show task examples (user can give tasks from chat)
    // Local: show chat examples (chat only, tasks go to Workflows tab)
    val prompts = if (!isLocalModel) {
        listOf(
            Prompt("What time is it in Tokyo?", false),
            Prompt("Help me write a birthday message", false),
            Prompt("Send hi to Mom on WhatsApp", true),
        )
    } else {
        listOf(
            Prompt("Tell me a joke", false),
            Prompt("What can you do?", false),
            Prompt("Help me draft an email", false),
        )
    }

    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        androidx.compose.foundation.Image(
            painter = painterResource(R.drawable.bqa_agent_icon),
            contentDescription = "BQAAgent",
            modifier = Modifier
                .size(48.dp)
                .clip(CompactSurfaceShape),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "BQAAgent",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.textPrimary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Chat or run tasks. Just type what you need.",
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        )

        val showSuggestedPrompts = false
        // TODO 隐藏首页建议提示词
        if (showSuggestedPrompts) {
            Spacer(Modifier.height(12.dp))
            // Suggested prompt chips — same style as Quick Tasks items
            Column(
                modifier = Modifier.padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                prompts.forEach { prompt ->
                    val barAlpha = if (prompt.isTask) 1f else 0.5f
                    Surface(
                        shape = StraightControlShape,
                        color = colors.background,
                        border = androidx.compose.foundation.BorderStroke(0.5.dp, colors.inputBorder),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectPrompt(prompt.text, prompt.isTask) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .height(38.dp)
                                    .background(
                                        colors.accent.copy(alpha = barAlpha),
                                        StraightControlShape,
                                    ),
                            )
                            Text(
                                prompt.text,
                                fontSize = 12.sp,
                                color = colors.textSecondary,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ======================== QUICK TASKS PANEL (v9) ========================

@Composable
private fun QuickTasksPanel(
    isLocalModel: Boolean,
    onFillTask: (String) -> Unit,
    onMonitorClick: () -> Unit,
    monitorActive: Boolean,
    colors: PokeclawColors,
) {
    var expanded by remember { mutableStateOf(false) }

    // Cloud-only tasks at the top (multi-step, Siri/GA can't do these)
    // Cloud-only tasks (multi-step, Siri can't do)
    val cloudOnlyTasks = listOf(
        "Open Reddit and search for bqaagent",
        "Search YouTube for funny cat fails",
        "Install Telegram from Play Store",
        "Check what's trending on Twitter and tell me",
        "Check my latest WhatsApp chat and summarize it",
        "Copy the latest email subject and Google it",
        "Write an email saying I'll be late today",
    )
    // Reasoning tasks (1-2 tool calls + LLM analysis) — impressive, work on both
    val reasoningTasks = listOf(
        "Check my notifications — anything important?",
        "Read my clipboard and explain what it says",
        "Check my storage and apps — what can I delete?",
        "Read my notifications and summarize",
        "Check my battery and tell me if I need to charge",
    )
    // Simple deterministic tasks (1 tool, no reasoning)
    val deterministicTasks = listOf(
        "Send hi to Mom on WhatsApp",
        "What apps do I have?",
        "How hot is my phone?",
        "Is bluetooth on?",
        "How much battery left?",
        "Call Mom",
        "How much storage do I have?",
        "What Android version am I running?",
    )
    // Cloud: cloud-only → reasoning → deterministic
    // Local: reasoning first (impressive) → deterministic
    val quickTasks = if (isLocalModel) {
        reasoningTasks + deterministicTasks
    } else {
        cloudOnlyTasks + reasoningTasks + deterministicTasks
    }

    Column(
        modifier = Modifier.background(colors.surface),
    ) {
        HorizontalDivider(color = colors.divider, thickness = 1.dp)

        // Handle bar — ▲ Quick Tasks ▲
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = "Toggle",
                tint = colors.textPrimary,
                modifier = Modifier.size(12.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "Quick Task Templates",
                fontSize = 11.sp,
                fontWeight = FontWeight.Normal,
                color = colors.textPrimary,
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = "Toggle",
                tint = colors.textPrimary,
                modifier = Modifier.size(12.dp),
            )
        }

        // Collapsible content
        if (expanded) {
            // Quick task items — scrollable
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 280.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                quickTasks.forEach { task ->
                    Surface(
                        shape = StraightControlShape,
                        color = colors.background,
                        border = androidx.compose.foundation.BorderStroke(0.5.dp, colors.inputBorder),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onFillTask(task) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .height(38.dp)
                                    .background(colors.accent, StraightControlShape),
                            )
                            Text(
                                task,
                                fontSize = 12.sp,
                                color = colors.textSecondary,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                            )
                        }
                    }
                }
            }

            // Background section — always visible, NOT inside scroll
            Column(modifier = Modifier.padding(horizontal = 12.dp)) {
                Text(
                    "BACKGROUND",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textTertiary,
                    letterSpacing = 0.5.sp,
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                )

                // Monitor card
                val monitorBorderColor = if (monitorActive) colors.accent else colors.inputBorder
                Surface(
                    onClick = {
                        if (!monitorActive) onMonitorClick()
                    },
                    shape = StraightControlShape,
                    color = colors.background,
                    border = androidx.compose.foundation.BorderStroke(
                        if (monitorActive) 1.dp else 0.5.dp,
                        monitorBorderColor,
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .background(
                                    colors.accent.copy(alpha = 0.12f),
                                    CompactSurfaceShape,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("M", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = colors.accent)
                        }
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                if (monitorActive) "Active" else "Monitor & Auto-Reply",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.textPrimary,
                            )
                            Text(
                                if (monitorActive) "Monitoring active — use the top bar to stop" else "Watch messages and reply automatically",
                                fontSize = 9.sp,
                                color = colors.textTertiary,
                            )
                        }
                        if (!monitorActive) {
                            Text("›", color = colors.textTertiary, fontSize = 14.sp)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
            } // end Background Column
        }
    }
}

// ======================== SIDEBAR ========================

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SidebarContent(
    conversations: List<ChatHistoryManager.ConversationSummary>,
    onNewChat: () -> Unit,
    onSelectConversation: (ChatHistoryManager.ConversationSummary) -> Unit,
    onDeleteConversation: (ChatHistoryManager.ConversationSummary) -> Unit,
    onRenameConversation: (ChatHistoryManager.ConversationSummary, String) -> Unit,
    onSettings: () -> Unit,
    onModels: () -> Unit,
    colors: PokeclawColors,
) {
    var actionTarget by remember { mutableStateOf<ChatHistoryManager.ConversationSummary?>(null) }
    var deleteTarget by remember { mutableStateOf<ChatHistoryManager.ConversationSummary?>(null) }
    var renameTarget by remember { mutableStateOf<ChatHistoryManager.ConversationSummary?>(null) }
    var renameText by remember { mutableStateOf("") }

    // Long-press action menu: Rename / Delete
    if (actionTarget != null) {
        AlertDialog(
            onDismissRequest = { actionTarget = null },
            title = { Text(actionTarget!!.title, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
                            Text("Rename", color = colors.textPrimary)
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
                            Text("Delete", color = Color(0xFFF87171))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(
                    onClick = { actionTarget = null },
                    shape = StraightControlShape,
                ) { Text("Cancel", color = colors.textSecondary) }
            },
            containerColor = colors.surface,
        )
    }

    // Delete confirmation
    if (deleteTarget != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete conversation?", color = colors.textPrimary) },
            text = { Text(deleteTarget!!.title, color = colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteConversation(deleteTarget!!)
                        deleteTarget = null
                    },
                    shape = StraightControlShape,
                ) { Text("Delete", color = Color(0xFFF87171)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { deleteTarget = null },
                    shape = StraightControlShape,
                ) { Text("Cancel", color = colors.textSecondary) }
            },
            containerColor = colors.surface,
        )
    }

    // Rename dialog
    if (renameTarget != null) {
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename conversation", color = colors.textPrimary) },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
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
                            onRenameConversation(renameTarget!!, newName)
                        }
                        renameTarget = null
                    },
                    shape = StraightControlShape,
                ) { Text("Save", color = colors.accent) }
            },
            dismissButton = {
                TextButton(
                    onClick = { renameTarget = null },
                    shape = StraightControlShape,
                ) { Text("Cancel", color = colors.textSecondary) }
            },
            containerColor = colors.surface,
        )
    }
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .padding(top = 48.dp),
    ) {
        // Title with logo
        Row(
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.foundation.Image(
                painter = painterResource(R.drawable.bqa_agent_icon),
                contentDescription = null,
                modifier = Modifier
                    .size(28.dp)
                    .clip(CompactSurfaceShape),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "BQAAgent",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary,
            )
        }

        // New Chat button
        Button(
            onClick = onNewChat,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
            shape = StraightControlShape,
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("New Chat")
        }

        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = colors.divider, modifier = Modifier.padding(horizontal = 14.dp))
        Spacer(Modifier.height(8.dp))

        // Recent label
        Text(
            "Recent",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = colors.textTertiary,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
        )

        // Conversations
        LazyColumn(modifier = Modifier.weight(1f)) {
            if (conversations.isEmpty()) {
                item {
                    Text(
                        "No conversations yet",
                        fontSize = 13.sp,
                        color = colors.textTertiary,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    )
                }
            }
            items(conversations.size) { index ->
                val conv = conversations[index]
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = { onSelectConversation(conv) },
                            onLongClick = { actionTarget = conv },
                        ),
                    color = androidx.compose.ui.graphics.Color.Transparent,
                ) {
                    Text(
                        text = conv.title,
                        fontSize = 14.sp,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
            }
        }

        HorizontalDivider(color = colors.divider)

        // Bottom nav
        TextButton(
            onClick = onSettings,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            shape = StraightControlShape,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Settings, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text("Settings", color = colors.textSecondary)
            }
        }
        TextButton(
            onClick = onModels,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 0.dp),
            shape = StraightControlShape,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.SmartToy, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text("Models", color = colors.textSecondary)
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

// ======================== TASK SKILLS PANEL ========================

@Composable
private fun TaskSkillsPanel(
    isLocalModel: Boolean,
    taskMessages: List<ChatMessage>,
    onMonitorClick: () -> Unit,
    onSendClick: () -> Unit,
    onSkillTap: (String) -> Unit,
    activatingSkill: String?,
    monitorActive: Boolean,
    colors: PokeclawColors,
    modifier: Modifier = Modifier,
) {
    val builtInSkills = remember { SkillRegistry.getUserFacing() }
    val categoryIcons = mapOf(
        SkillCategory.INPUT to Icons.Outlined.Keyboard,
        SkillCategory.DISMISS to Icons.Outlined.Close,
        SkillCategory.NAVIGATION to Icons.Outlined.Navigation,
        SkillCategory.MESSAGING to Icons.Outlined.Chat,
        SkillCategory.GENERAL to Icons.Outlined.AutoAwesome,
    )

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                "Workflows",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary,
            )
            Text(
                "Background tasks powered by AI — things a single prompt can't do.",
                fontSize = 12.sp,
                color = colors.textTertiary,
                modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
            )
            Surface(
                shape = CompactSurfaceShape,
                color = colors.accent.copy(alpha = 0.12f),
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
            ) {
                Text(
                    "Experimental — more workflows coming soon",
                    fontSize = 11.sp,
                    color = colors.accent,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }

        // Monitor Messages — always shown (background workflow, both modes need it)
        item {
            SkillCard(
                icon = Icons.Outlined.Visibility,
                title = "Monitor Messages",
                description = "Auto-reply to someone's messages in background",
                onClick = onMonitorClick,
                isActivating = activatingSkill == "monitor",
                isActive = monitorActive,
                colors = colors,
            )
        }

        // Send Message — available on both (workflow card shortcut)
        item {
            SkillCard(
                icon = Icons.Outlined.Send,
                title = "Send Message",
                description = "Send a message to someone via any messaging app",
                onClick = onSendClick,
                colors = colors,
            )
        }

        // Built-in user-facing skills from SkillRegistry
        if (builtInSkills.isNotEmpty()) {
            items(builtInSkills.size) { index ->
                val skill = builtInSkills[index]
                val example = skill.triggerPatterns.firstOrNull()
                    ?.replace(Regex("\\{\\w+\\}"), "...")
                    ?.replace(".+", "...")
                    ?: skill.name
                SkillCard(
                    icon = categoryIcons[skill.category] ?: Icons.Outlined.AutoAwesome,
                    title = skill.name,
                    description = skill.description,
                    onClick = { onSkillTap(example) },
                    colors = colors,
                )
            }
        }

        // Task progress messages (if any)
        if (taskMessages.isNotEmpty()) {
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Recent",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textTertiary,
                )
            }
            items(taskMessages.size) { index ->
                val msg = taskMessages[index]
                if (msg.role == ChatMessage.Role.USER) {
                    UserBubble(msg.content, msg.timestamp, colors)
                } else {
                    SystemMessage(msg.content, colors)
                }
            }
        }
    }
}

@Composable
private fun SkillCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
    isActivating: Boolean = false,
    isActive: Boolean = false,
    colors: PokeclawColors,
) {
    val activeOrange = Color(0xFFE8751A)
    val borderColor = when {
        isActive -> activeOrange
        isActivating -> colors.accent
        else -> colors.inputBorder
    }
    val cardBg = when {
        isActive -> activeOrange.copy(alpha = 0.08f)
        else -> colors.surface
    }
    val iconBg = when {
        isActive -> activeOrange.copy(alpha = 0.15f)
        else -> colors.accent.copy(alpha = 0.12f)
    }
    val iconTint = if (isActive) activeOrange else colors.accent

    // Progress animation
    val progress by animateFloatAsState(
        targetValue = if (isActivating) 1f else 0f,
        animationSpec = if (isActivating) tween(2000, easing = LinearEasing) else snap(),
        label = "skillProgress",
    )

    Surface(
        onClick = onClick,
        shape = StraightControlShape,
        color = cardBg,
        border = androidx.compose.foundation.BorderStroke(if (isActive) 1.dp else 0.5.dp, borderColor),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(iconBg, CompactSurfaceShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isActive) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = activeOrange, modifier = Modifier.size(22.dp))
                    } else {
                        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (isActive) activeOrange else colors.textPrimary)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        if (isActive) "Running in background" else description,
                        fontSize = 12.sp,
                        color = if (isActive) activeOrange.copy(alpha = 0.7f) else colors.textTertiary,
                        lineHeight = 16.sp,
                    )
                }
                if (!isActive && !isActivating) {
                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(20.dp))
                }
            }

            // Progress bar during activation
            if (isActivating) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                    color = activeOrange,
                    trackColor = colors.inputBorder,
                )
            }
        }
    }
}

// ======================== SKILL DIALOGS ========================

@Composable
private fun MonitorDialog(
    onDismiss: () -> Unit,
    onStart: (MonitorTargetSpec) -> Unit,
    colors: PokeclawColors,
) {
    var contact by remember { mutableStateOf("") }
    var selectedApp by remember { mutableStateOf("WhatsApp") }
    var appMenuExpanded by remember { mutableStateOf(false) }
    var selectedTone by remember { mutableStateOf("Casual") }
    val apps = MonitorTargetSpec.supportedApps
    val tones = listOf("Casual", "Formal", "Funny")

    // Centered modal overlay
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.44f))
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
            ) { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        // Centered card
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                ) { /* block clicks from dismissing */ },
            shape = CompactSurfaceShape,
            color = colors.surface,
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Drag handle
                Box(
                    modifier = Modifier
                        .width(32.dp)
                        .height(3.dp)
                        .align(Alignment.CenterHorizontally)
                        .background(colors.textTertiary, RoundedCornerShape(2.dp)),
                )
                Spacer(Modifier.height(14.dp))

                // Title
                Text(
                    "Monitor & Auto-Reply",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                )
                Spacer(Modifier.height(12.dp))

                // Contact row: label + input
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Target",
                        fontSize = 11.sp,
                        color = colors.textSecondary,
                        modifier = Modifier.width(50.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    androidx.compose.foundation.text.BasicTextField(
                        value = contact,
                        onValueChange = { contact = it },
                        modifier = Modifier.weight(1f),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontSize = 12.sp,
                            color = colors.textPrimary,
                        ),
                        singleLine = true,
                        decorationBox = { innerTextField ->
                            Box(
                                modifier = Modifier
                                    .background(colors.background, CompactSurfaceShape)
                                    .then(
                                        Modifier.border(1.dp, colors.inputBorder, CompactSurfaceShape)
                                    )
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            ) {
                                if (contact.isEmpty()) {
                                    Text("e.g. Mom, +1 555 123 4567", fontSize = 12.sp, color = colors.textTertiary)
                                }
                                innerTextField()
                            }
                        },
                    )
                }
                Spacer(Modifier.height(8.dp))

                // App row: label + dropdown
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "App",
                        fontSize = 11.sp,
                        color = colors.textSecondary,
                        modifier = Modifier.width(50.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Box {
                        Surface(
                            shape = StraightControlShape,
                            color = colors.background,
                            border = androidx.compose.foundation.BorderStroke(1.dp, colors.inputBorder),
                        ) {
                            Row(
                                modifier = Modifier
                                    .clickable { appMenuExpanded = true }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(selectedApp, fontSize = 12.sp, color = colors.textPrimary)
                                Spacer(Modifier.width(4.dp))
                                Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(14.dp))
                            }
                        }
                        DropdownMenu(
                            expanded = appMenuExpanded,
                            onDismissRequest = { appMenuExpanded = false },
                        ) {
                            apps.forEach { app ->
                                DropdownMenuItem(
                                    text = { Text(app, fontSize = 12.sp) },
                                    onClick = { selectedApp = app; appMenuExpanded = false },
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))

                // Tone row: label + pill chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Tone",
                        fontSize = 11.sp,
                        color = colors.textSecondary,
                        modifier = Modifier.width(50.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        tones.forEach { tone ->
                            val isOn = tone == selectedTone
                            Surface(
                                shape = StraightControlShape,
                                color = if (isOn) colors.userBubble.copy(alpha = 0.1f) else colors.background,
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (isOn) colors.userBubble else colors.inputBorder,
                                ),
                            ) {
                                Text(
                                    tone,
                                    fontSize = 11.sp,
                                    color = if (isOn) colors.accent else colors.textSecondary,
                                    modifier = Modifier
                                        .clickable { selectedTone = tone }
                                        .padding(horizontal = 10.dp, vertical = 5.dp),
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))

                // Start Monitoring button
                Surface(
                    onClick = {
                        val trimmed = contact.trim()
                        if (trimmed.isNotBlank()) {
                            onStart(
                                MonitorTargetSpec(
                                    label = trimmed,
                                    app = selectedApp,
                                    tone = selectedTone,
                                )
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = StraightControlShape,
                    color = colors.userBubble,
                ) {
                    Text(
                        "Start Monitoring",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 11.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SendMessageDialog(
    onDismiss: () -> Unit,
    onSend: (contact: String, app: String, message: String) -> Unit,
    colors: PokeclawColors,
) {
    var contact by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var selectedApp by remember { mutableStateOf("WhatsApp") }
    var appMenuExpanded by remember { mutableStateOf(false) }
    val apps = listOf("WhatsApp", "Telegram", "Messages")

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = {
            Text("Send Message", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = colors.textPrimary)
        },
        text = {
            Column {
                Text("With a smarter LLM, you can just type:", fontSize = 11.sp, color = colors.textTertiary)
                Spacer(Modifier.height(2.dp))
                Text("\"send hi to Mom on WhatsApp\"", fontSize = 11.sp, color = colors.accent.copy(alpha = 0.7f))
                Spacer(Modifier.height(16.dp))

                // Fill-in-the-blank: "Send [___] to [___] on [WhatsApp ▾]"
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Send ", fontSize = 15.sp, color = colors.textPrimary)
                    Text("\"", fontSize = 15.sp, color = colors.textTertiary)
                    OutlinedTextField(
                        value = message,
                        onValueChange = { message = it },
                        placeholder = { Text("message", color = colors.textTertiary, fontSize = 14.sp) },
                        modifier = Modifier.weight(1f).heightIn(min = 40.dp),
                        shape = StraightControlShape,
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = colors.accent,
                            unfocusedBorderColor = colors.inputBorder,
                            cursorColor = colors.accent,
                            focusedTextColor = colors.textPrimary,
                            unfocusedTextColor = colors.textPrimary,
                        ),
                    )
                    Text("\"", fontSize = 15.sp, color = colors.textTertiary)
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("to ", fontSize = 15.sp, color = colors.textPrimary)
                    OutlinedTextField(
                        value = contact,
                        onValueChange = { contact = it },
                        placeholder = { Text("name", color = colors.textTertiary, fontSize = 14.sp) },
                        modifier = Modifier.weight(1f).heightIn(min = 40.dp),
                        shape = StraightControlShape,
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = colors.accent,
                            unfocusedBorderColor = colors.inputBorder,
                            cursorColor = colors.accent,
                            focusedTextColor = colors.textPrimary,
                            unfocusedTextColor = colors.textPrimary,
                        ),
                    )
                    Text(" on ", fontSize = 15.sp, color = colors.textPrimary)
                    Box {
                        Surface(
                            onClick = { appMenuExpanded = true },
                            shape = StraightControlShape,
                            color = Color.Transparent,
                            border = androidx.compose.foundation.BorderStroke(1.dp, colors.inputBorder),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(selectedApp, fontSize = 13.sp, color = colors.textPrimary)
                                Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(16.dp))
                            }
                        }
                        DropdownMenu(
                            expanded = appMenuExpanded,
                            onDismissRequest = { appMenuExpanded = false },
                        ) {
                            apps.forEach { app ->
                                DropdownMenuItem(
                                    text = { Text(app) },
                                    onClick = { selectedApp = app; appMenuExpanded = false },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { if (contact.isNotBlank() && message.isNotBlank()) onSend(contact.trim(), selectedApp, message.trim()) },
                enabled = contact.isNotBlank() && message.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                shape = StraightControlShape,
            ) {
                Text("Send")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                shape = StraightControlShape,
            ) {
                Text("Cancel", color = colors.textSecondary)
            }
        },
    )
}

// ======================== ACTIVE TASK BAR ========================

@Composable
private fun ActiveTaskBar(
    tasks: List<String>,
    onStopTask: (String) -> Unit,
    onStopAll: () -> Unit,
    colors: PokeclawColors,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface)
    ) {
        // Monitor tasks bar
        if (tasks.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            color = colors.accent,
                            shape = CompactSurfaceShape,
                        )
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = if (tasks.size == 1) "Monitoring: ${tasks[0]}" else "${tasks.size} monitoring",
                    color = colors.textPrimary,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (expanded) "Collapse" else "Expand",
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                )
            }
        }

        // Expanded — show each task with stop button
        if (expanded) {
            Divider(color = colors.textSecondary.copy(alpha = 0.2f), thickness = 0.5.dp)
            tasks.forEach { task ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(
                                color = colors.accent,
                                shape = CompactSurfaceShape,
                            )
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = task,
                        color = colors.textPrimary,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "Stop",
                        color = androidx.compose.ui.graphics.Color(0xFFF44336),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clickable { onStopTask(task) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
            if (tasks.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Text(
                        text = "Stop All",
                        color = androidx.compose.ui.graphics.Color(0xFFF44336),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clickable { onStopAll() }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}
