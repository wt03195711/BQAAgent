// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent

import androidx.lifecycle.ViewModel
import io.agents.bqaagent.agent.AgentConfig
import io.agents.bqaagent.agent.llm.ModelConfigRepository
import io.agents.bqaagent.channel.Channel
import io.agents.bqaagent.service.ForegroundService
import io.agents.bqaagent.floating.FloatingCircleManager
import io.agents.bqaagent.server.ConfigServerManager
import io.agents.bqaagent.service.KeepAliveJobService
import io.agents.bqaagent.utils.KVUtils
import io.agents.bqaagent.utils.ScreenKeepAliveManager
import io.agents.bqaagent.utils.XLog

class AppViewModel : ViewModel() {

    companion object {
        private const val TAG = "AppViewModel"
    }

    private var _commonInitialized = false

    val taskOrchestrator = TaskOrchestrator(
        agentConfigProvider = { getAgentConfig() },
        onTaskFinished = { /* refresh */ }
    )

    val taskSessionStore: TaskSessionStore
        get() = taskOrchestrator.taskSessionStore
    val inProgressTaskMessageId: String get() = taskSessionStore.snapshot().messageId
    val inProgressTaskChannel: Channel? get() = taskSessionStore.snapshot().channel

    /**
     * Shared workspace gate for both modes. Chat and batch read the SAME flow, so neither can offer a
     * Start/Send control while the other mode still owns the engine, and "stopping" is visible in both.
     */
    val taskBusy: kotlinx.coroutines.flow.StateFlow<Boolean> get() = taskOrchestrator.busy

    fun ownerMode(): io.agents.bqaagent.TaskRunMode = taskOrchestrator.ownerMode()

    fun isStopping(): Boolean = taskSessionStore.snapshot().isStopping

    // ==================== Task API (clean interface for Activity) ====================

    /**
     * Called before a task starts — allows the chat UI to release its local LLM conversation
     * so the task agent can use the same LiteRT-LM engine (only 1 session supported).
     */
    var onBeforeTask: (() -> Unit)? = null

    fun startTask(
        task: String,
        taskId: String,
        agentPromptOverride: String? = null,
        onEvent: (TaskEvent) -> Unit,
    ) {
        if (isTaskRunning() || taskOrchestrator.busyNow()) {
            val msgRes = if (ownerMode() == io.agents.bqaagent.TaskRunMode.BATCH) {
                io.agents.bqaagent.R.string.mode_lock_batch_busy
            } else {
                io.agents.bqaagent.R.string.mode_lock_stopping
            }
            onEvent(io.agents.bqaagent.TaskEvent.Failed(
                androidContext().getString(msgRes),
                io.agents.bqaagent.TaskReasonCode.OTHER
            ))
            return
        }
        onBeforeTask?.invoke()
        taskOrchestrator.taskEventCallback = onEvent
        taskOrchestrator.startNewTask(Channel.LOCAL, task, taskId, agentPromptOverride = agentPromptOverride)
    }

    /**
     * Stop entry shared by the chat button and the floating pill. Routes to the live batch driver when
     * the batch owns the workspace so "cancel" means the whole batch, not just the current item.
     */
    fun stopTask() {
        val batch = io.agents.bqaagent.ui.batch.BatchTaskDriver.active()
        if (batch != null && (taskSessionStore.snapshot().isBatch || batch.isRunning)) {
            batch.cancel()
            return
        }
        taskOrchestrator.cancelCurrentTask(source = if (taskSessionStore.snapshot().isBatch) "batch-ui" else "chat-ui")
    }

    fun isTaskRunning(): Boolean = taskSessionStore.isTaskRunning()

    fun clearTaskCallback() {
        taskOrchestrator.taskEventCallback = null
    }

    fun provideUserImage(imagePath: String?) {
        taskOrchestrator.provideUserImage(imagePath)
    }

    fun resolveReplayConfirm(confirmed: Boolean) {
        taskOrchestrator.resolveReplayConfirm(confirmed)
    }

    fun resolveSensitiveConfirm(approved: Boolean) {
        taskOrchestrator.resolveSensitiveConfirm(approved)
    }

    fun init() {
        initCommon()
        initAgent()
    }

    fun initCommon() {
        if (_commonInitialized) return
        _commonInitialized = true
    }

    fun initAgent() {
        if (!ModelConfigRepository.hasUsableModel()) return
        taskOrchestrator.initAgent()
    }

    fun getAgentConfig(): AgentConfig =
        ModelConfigRepository.snapshot().toAgentConfig(
            temperature = 0.1,
            maxIterations = 60
        )

    fun updateAgentConfig(): Boolean = taskOrchestrator.updateAgentConfig()

    fun afterInit() {
        ScreenKeepAliveManager.acquireCpuOnly(ClawApplication.instance)
        KeepAliveJobService.cancel(ClawApplication.instance)
        ForegroundService.syncToBackgroundState(ClawApplication.instance)
        ConfigServerManager.autoStartIfNeeded(ClawApplication.instance)
    }

    /**
     * Prepare the floating window: keep an Application reference and wire the click/stop
     * callbacks. Visibility is driven purely by the feature toggle — if the toggle is ON we
     * show the persistent bubble right away; otherwise nothing is displayed.
     */
    fun setupFloatingCircle() {
        try {
            FloatingCircleManager.init(ClawApplication.instance)
            FloatingCircleManager.onFloatClick = {
                XLog.d(TAG, "Floating circle clicked")
                bringAppToForeground()
            }
            FloatingCircleManager.onStopTask = {
                XLog.i(TAG, "Stop task requested from floating pill")
                // The pill is a router, not a third policy: it dispatches to whichever mode owns the
                // session, so pill / chat button / batch button all mean the same confirmed stop.
                stopTask()
                bringAppToForeground()
            }
            if (KVUtils.isFloatingWindowEnabled()) {
                FloatingCircleManager.ensureShowing()
            }
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to setup floating circle: ${e.message}")
        }
    }

    /**
     * Bring the app to the foreground — routed to the mode the user was last in.
     * While a task owns the workspace, the OWNER mode wins over the last-visited marker so the
     * pill always returns to the screen that hosts the running task's UI.
     * Chat → ComposeChatActivity, Batch → BatchActivity.
     */
    private fun bringAppToForeground() {
        val context = ClawApplication.instance
        val owner = ownerMode()
        val target = when {
            owner == io.agents.bqaagent.TaskRunMode.BATCH ->
                io.agents.bqaagent.ui.batch.BatchActivity::class.java
            owner == io.agents.bqaagent.TaskRunMode.CHAT ->
                io.agents.bqaagent.ui.chat.ComposeChatActivity::class.java
            KVUtils.getLastUiMode() == io.agents.bqaagent.ui.batch.BatchUiMode.BATCH ->
                io.agents.bqaagent.ui.batch.BatchActivity::class.java
            else ->
                io.agents.bqaagent.ui.chat.ComposeChatActivity::class.java
        }
        val intent = android.content.Intent(context, target).apply {
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        context.startActivity(intent)
    }

    private fun androidContext(): android.content.Context = ClawApplication.instance

    // Old pass-through methods removed — use startTask/stopTask/isTaskRunning/clearTaskCallback instead

}
