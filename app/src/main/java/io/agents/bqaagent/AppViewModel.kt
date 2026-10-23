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
        if (isTaskRunning()) {
            onEvent(TaskEvent.Failed("Another task is still running. Stop it first."))
            return
        }
        onBeforeTask?.invoke()
        taskOrchestrator.taskEventCallback = onEvent
        taskOrchestrator.startNewTask(Channel.LOCAL, task, taskId, agentPromptOverride = agentPromptOverride)
    }

    fun stopTask() {
        taskOrchestrator.cancelCurrentTask()
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
     * Chat → ComposeChatActivity, Batch → BatchActivity. The same value drives cold-start
     * UI restoration (KVUtils.getLastUiMode()), so the pill returns you to the right screen.
     */
    private fun bringAppToForeground() {
        val context = ClawApplication.instance
        val target = when (KVUtils.getLastUiMode()) {
            io.agents.bqaagent.ui.batch.BatchUiMode.BATCH ->
                io.agents.bqaagent.ui.batch.BatchActivity::class.java
            else ->
                io.agents.bqaagent.ui.chat.ComposeChatActivity::class.java
        }
        val intent = android.content.Intent(context, target).apply {
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        context.startActivity(intent)
    }

    // Old pass-through methods removed — use startTask/stopTask/isTaskRunning/clearTaskCallback instead

}
