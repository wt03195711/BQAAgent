// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent

import io.agents.bqaagent.agent.AgentCallback
import io.agents.bqaagent.agent.AgentConfig
import io.agents.bqaagent.agent.AgentService
import io.agents.bqaagent.agent.AgentServiceFactory
import io.agents.bqaagent.agent.PipelineRouter
import io.agents.bqaagent.agent.skill.SkillExecutor
import io.agents.bqaagent.agent.skill.SkillRegistry
import io.agents.bqaagent.channel.Channel
import io.agents.bqaagent.channel.ChannelManager
import io.agents.bqaagent.floating.FloatingCircleManager
import io.agents.bqaagent.adb.LocalAdbDeviceDriver
import io.agents.bqaagent.service.ForegroundService
import io.agents.bqaagent.tool.ToolResult
import io.agents.bqaagent.utils.XLog

/**
 * Task orchestrator — manages agent lifecycle, task locking, pipeline routing, and execution.
 */
class TaskOrchestrator(
    private val agentConfigProvider: () -> AgentConfig,
    private val onTaskFinished: () -> Unit
) {
    /**
     * Typed event callback for in-app Task mode UI.
     * Called on the agent executor thread — UI must post to main thread.
     */
    var taskEventCallback: ((TaskEvent) -> Unit)? = null

    companion object {
        private const val TAG = "TaskOrchestrator"
    }

    // User image handoff fields
    private var pendingUserImageLatch: java.util.concurrent.CountDownLatch? = null
    @Volatile
    private var pendingUserImagePath: String? = null

    private lateinit var agentService: AgentService
    private val pipelineRouter = PipelineRouter(ClawApplication.instance)
    private val skillExecutor = SkillExecutor()
    val taskSessionStore = TaskSessionStore()

    val inProgressTaskMessageId: String
        get() = taskSessionStore.snapshot().messageId
    val inProgressTaskChannel: Channel?
        get() = taskSessionStore.snapshot().channel

    // ==================== Agent Lifecycle ====================

    fun initAgent() {
        agentService = AgentServiceFactory.create()
        try {
            agentService.initialize(agentConfigProvider())
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to initialize AgentService", e)
        }
    }

    fun updateAgentConfig(): Boolean {
        return try {
            val config = agentConfigProvider()
            if (::agentService.isInitialized) {
                agentService.updateConfig(config)
                XLog.d(TAG, "Agent config updated: model=${config.modelName}, temp=${config.temperature}")
                true
            } else {
                XLog.w(TAG, "AgentService not initialized, initializing with new config")
                agentService = AgentServiceFactory.create()
                agentService.initialize(config)
                true
            }
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to update agent config", e)
            false
        }
    }

    // ==================== Task Lock ====================

    fun tryAcquireTask(messageId: String, channel: Channel, taskText: String = ""): Boolean {
        return taskSessionStore.tryAcquire(
            messageId = messageId,
            channel = channel,
            taskText = taskText,
        )
    }

    private fun releaseTask(): TaskSessionState = taskSessionStore.release()

    fun isTaskRunning(): Boolean = taskSessionStore.isTaskRunning()

    // ==================== Task Execution ====================

    fun cancelCurrentTask() {
        if (!taskSessionStore.markStopping()) return
        // Release any pending user image wait so the agent thread doesn't hang
        cleanupPendingUserImage()
        pendingUserImageLatch?.countDown()
        val cancelledSession = releaseTask()
        taskEventCallback?.invoke(TaskEvent.Cancelled)
        ForegroundService.resetToIdle(ClawApplication.instance)
        if (cancelledSession.channel != null && cancelledSession.messageId.isNotEmpty()) {
            ChannelManager.sendMessage(
                cancelledSession.channel,
                ClawApplication.instance.getString(R.string.channel_msg_task_cancelled),
                cancelledSession.messageId
            )
            ChannelManager.flushMessages(cancelledSession.channel)
        }
        FloatingCircleManager.setErrorState()
        onTaskFinished()
        if (::agentService.isInitialized) {
            agentService.cancel()
        }
        XLog.d(TAG, "Current task cancellation requested")
    }

    /**
     * Called by UI when user uploads or skips image.
     */
    fun provideUserImage(imagePath: String?) {
        pendingUserImagePath = imagePath
        pendingUserImageLatch?.countDown()
    }

    private fun cleanupPendingUserImage() {
        val path = pendingUserImagePath ?: return
        pendingUserImagePath = null
        try {
            val file = java.io.File(path)
            if (file.exists()) {
                file.delete()
                XLog.i(TAG, "Cleaned up unused user image: $path")
            }
        } catch (e: Exception) {
            XLog.w(TAG, "Failed to cleanup user image: $path", e)
        }
    }

    /**
     * Start a new task. Routes through the 3-tier pipeline.
     */
    fun startNewTask(
        channel: Channel,
        task: String,
        messageID: String,
        agentPromptOverride: String? = null,
        isFallback: Boolean = false,
    ) {
        // Acquire task lock if not already held
        if (!isTaskRunning()) {
            if (!tryAcquireTask(messageID, channel, task)) {
                XLog.w(TAG, "Failed to acquire task lock for: $task")
                taskEventCallback?.invoke(TaskEvent.Failed("Another task is running"))
                return
            }
        } else {
            val current = taskSessionStore.snapshot()
            if (current.messageId == messageID && current.channel == channel) {
                taskSessionStore.updateTaskText(task)
            } else {
                XLog.w(
                    TAG,
                    "Rejecting new task while another task is still active: current=${current.messageId}/${current.channel} new=$messageID/$channel"
                )
                taskEventCallback?.invoke(TaskEvent.Failed("Another task is still running. Stop it first."))
                ChannelManager.sendMessage(channel, "Another task is still running. Stop it first.", messageID)
                return
            }
        }

        ForegroundService.updateTaskStatus(ClawApplication.instance, "Preparing task...")

        fun isCurrentSessionActive(): Boolean {
            val current = taskSessionStore.snapshot()
            return current.isRunning && current.messageId == messageID && current.channel == channel
        }

        // Tier 1: Deterministic routing
        val route = pipelineRouter.route(task)
        when (route) {
            is PipelineRouter.Route.DirectIntent -> {
                XLog.i(TAG, "Pipeline Tier 1: DirectIntent — ${route.description}")
                pipelineRouter.executeIntent(route.intent)
                XLog.i(TAG, "onComplete: rounds=0, totalTokens=0, model=direct, answer=${route.description}")
                taskEventCallback?.invoke(TaskEvent.Completed(route.description))
                ChannelManager.sendMessage(channel, "✓ ${route.description}", messageID)
                releaseTask()
                ForegroundService.resetToIdle(ClawApplication.instance)
                FloatingCircleManager.setSuccessState()
                onTaskFinished()
                return
            }
            is PipelineRouter.Route.DirectTool -> {
                XLog.i(TAG, "Pipeline Tier 1: DirectTool — ${route.toolName}")
                Thread({
                    var success = false
                    val answer = try {
                        val toolResult = pipelineRouter.executeTool(route.toolName, route.params)
                        if (!toolResult.isSuccess) {
                            val error = toolResult.error ?: "Unknown error"
                            XLog.w(TAG, "Tier 1 tool failed: $error")
                            taskEventCallback?.invoke(TaskEvent.Completed("Failed: ${route.description}"))
                            ChannelManager.sendMessage(channel, "✗ ${route.description}: $error", messageID)
                            "Failed: ${route.description}: $error"
                        } else {
                            success = true
                            taskEventCallback?.invoke(TaskEvent.Completed(route.description))
                            ChannelManager.sendMessage(channel, "✓ ${route.description}", messageID)
                            route.description
                        }
                    } catch (e: Exception) {
                        val message = e.message ?: "Unknown error"
                        XLog.e(TAG, "Tier 1 tool crashed: ${route.toolName}", e)
                        taskEventCallback?.invoke(TaskEvent.Failed(message))
                        ChannelManager.sendMessage(channel, "✗ ${route.description}: $message", messageID)
                        "Failed: ${route.description}: $message"
                    } finally {
                        releaseTask()
                        ForegroundService.resetToIdle(ClawApplication.instance)
                        if (success) {
                            FloatingCircleManager.setSuccessState()
                        } else {
                            FloatingCircleManager.setErrorState()
                        }
                        onTaskFinished()
                    }
                    XLog.i(TAG, "onComplete: rounds=0, totalTokens=0, model=direct, answer=$answer")
                }, "direct-tool-${route.toolName}").start()
                return
            }
            is PipelineRouter.Route.Skill -> {
                if (isFallback) {
                    XLog.i(TAG, "Skipping skill route on fallback, going to agent loop: ${route.skillId}")
                } else {
                    XLog.i(TAG, "Pipeline Tier 2: Skill — ${route.skillId}")
                    val skill = SkillRegistry.findById(route.skillId)
                    if (skill != null) {
                        FloatingCircleManager.ensureShowing()
                        FloatingCircleManager.showTaskNotify(task, channel)
                        Thread({
                            val skillResult = skillExecutor.execute(skill, route.params) { step, total, desc ->
                                taskEventCallback?.invoke(TaskEvent.Progress(step, "Step $step/$total: $desc"))
                                ForegroundService.updateTaskStatus(ClawApplication.instance, desc)
                            }
                            if (skillResult.success) {
                                ChannelManager.sendMessage(channel, skillResult.message, messageID)
                                taskEventCallback?.invoke(TaskEvent.Completed(skillResult.message))
                                releaseTask()
                                FloatingCircleManager.setSuccessState()
                                ForegroundService.resetToIdle(ClawApplication.instance)
                                onTaskFinished()
                            } else {
                                val fallbackGoal = skill.fallbackGoal
                                    .let { g -> route.params.entries.fold(g) { acc, (k, v) -> acc.replace("{$k}", v) } }
                                XLog.i(TAG, "Skill ${skill.id} failed, falling back to agent loop: $fallbackGoal")
                                taskEventCallback?.invoke(TaskEvent.ToolAction("Retrying with AI agent"))
                                startNewTask(channel, fallbackGoal, messageID, isFallback = true)
                            }
                        }, "skill-executor").start()
                        return
                    }
                    XLog.w(TAG, "Skill ${route.skillId} not found, falling through to agent loop")
                }
            }
            is PipelineRouter.Route.Chat, is PipelineRouter.Route.AgentLoop -> {
                // Fall through to agent loop
            }
        }

        if (!updateAgentConfig()) {
            XLog.e(TAG, "Failed to prepare AgentService for task")
            releaseTask()
            ForegroundService.resetToIdle(ClawApplication.instance)
            taskEventCallback?.invoke(TaskEvent.Failed("AI service not ready"))
            ChannelManager.sendMessage(channel, ClawApplication.instance.getString(R.string.channel_msg_service_not_ready), messageID)
            return
        }

        if (!::agentService.isInitialized) {
            XLog.e(TAG, "AgentService not initialized, attempting to initialize")
            try {
                agentService = AgentServiceFactory.create()
                agentService.initialize(agentConfigProvider())
            } catch (e: Exception) {
                XLog.e(TAG, "Failed to initialize AgentService", e)
                releaseTask()
                ForegroundService.resetToIdle(ClawApplication.instance)
                taskEventCallback?.invoke(TaskEvent.Failed("AI service not ready"))
                ChannelManager.sendMessage(channel, ClawApplication.instance.getString(R.string.channel_msg_service_not_ready), messageID)
                return
            }
        }

        // Per-round message buffer for channel messaging
        val roundBuffer = StringBuilder()
        fun flushRoundBuffer() {
            if (roundBuffer.isNotEmpty()) {
                ChannelManager.sendMessage(channel, roundBuffer.toString().trim(), messageID)
                roundBuffer.clear()
            }
        }

        var floatingShown = false

        val agentPrompt = agentPromptOverride?.takeIf { it.isNotBlank() } ?: task
        agentService.executeTask(agentPrompt, object : AgentCallback {
            override fun onLoopStart(round: Int) {
                if (!isCurrentSessionActive()) return
                flushRoundBuffer()
                XLog.d(TAG, "onLoopStart: round=$round")
                taskEventCallback?.invoke(TaskEvent.LoopStart(round))
                if (round > 1) {
                    FloatingCircleManager.ensureShowing()
                    FloatingCircleManager.setRunningState(round, channel)
                    if (ForegroundService.isRunning()) {
                        ForegroundService.updateTaskStatus(ClawApplication.instance, "Step $round")
                    }
                }
            }

            override fun onTokenUpdate(status: io.agents.bqaagent.agent.TokenMonitor.Status) {
                if (!isCurrentSessionActive()) return
                FloatingCircleManager.updateTokenStatus(
                    step = status.step,
                    formattedTokens = status.formattedTokens,
                    formattedCost = status.formattedCost,
                    tokenState = status.state
                )
                taskEventCallback?.invoke(TaskEvent.TokenUpdate(
                    step = status.step,
                    formattedTokens = status.formattedTokens,
                    formattedCost = status.formattedCost,
                    tokenState = status.state,
                    totalTokens = status.totalTokens,
                    inputTokens = status.inputTokens,
                    outputTokens = status.outputTokens,
                    estimatedCostUsd = status.estimatedCostUsd
                ))
            }

            override fun onContent(round: Int, content: String) {
                if (!isCurrentSessionActive()) return
                if (content.isNotEmpty()) {
                    roundBuffer.append(content)
                    taskEventCallback?.invoke(TaskEvent.Thinking(content))
                }
            }

            override fun onToolCall(round: Int, toolId: String, toolName: String, parameters: String) {
                if (!isCurrentSessionActive()) return
                XLog.d(TAG, "onToolCall: $toolId($toolName), $parameters")
                // Don't show floating circle for finish tool (it's just completion, not a real action)
                val isFinish = toolName == "finish" || toolId == "finish"
                if (!floatingShown && !isFinish) {
                    floatingShown = true
                    FloatingCircleManager.ensureShowing()
                    FloatingCircleManager.showTaskNotify(task, channel)
                    ForegroundService.updateTaskStatus(ClawApplication.instance, "Running task...")
                }
                if (toolName.isNotEmpty()) {
                    val displayName = io.agents.bqaagent.tool.ToolRegistry.getInstance().getDisplayName(toolName)
                    taskEventCallback?.invoke(TaskEvent.ToolAction(displayName))
                    ForegroundService.updateTaskStatus(ClawApplication.instance, "$displayName...")
                }
            }

            override fun onToolResult(round: Int, toolId: String, toolName: String, parameters: String, result: ToolResult) {
                if (!isCurrentSessionActive()) return
                val app = ClawApplication.instance
                val success = result.isSuccess
                var data = if (success) result.data else result.error
                if (data != null && data.length > 300) data = data.substring(0, 300) + "..."
                if (!success) XLog.e(TAG, "Tool failed: $toolName $data")

                val displayName = io.agents.bqaagent.tool.ToolRegistry.getInstance().getDisplayName(toolName)
                taskEventCallback?.invoke(TaskEvent.ToolResult(displayName, success, data ?: ""))

                if (toolId == "finish" && result.data?.isNotEmpty() == true) {
                    flushRoundBuffer()
                    ChannelManager.sendMessage(channel, result.data, messageID)
                } else {
                    if (roundBuffer.isNotEmpty()) roundBuffer.append("\n")
                    roundBuffer.append(app.getString(R.string.channel_msg_tool_execution, toolName + parameters,
                        if (success) app.getString(R.string.channel_msg_tool_success) else app.getString(R.string.channel_msg_tool_failure)))
                }
            }

            override fun onComplete(round: Int, finalAnswer: String, totalTokens: Int, modelName: String?) {
                if (!isCurrentSessionActive()) {
                    XLog.i(TAG, "Ignoring stale onComplete after task cancellation")
                    return
                }
                XLog.i(TAG, "onComplete: rounds=$round, totalTokens=$totalTokens, model=$modelName, answer=$finalAnswer")
                val cancelAnswers = setOf(
                    ClawApplication.instance.getString(R.string.agent_task_cancel),
                    ClawApplication.instance.getString(R.string.agent_task_cancelled),
                    ClawApplication.instance.getString(R.string.channel_msg_task_cancelled)
                )
                if (finalAnswer.trim() in cancelAnswers) {
                    taskEventCallback?.invoke(TaskEvent.Cancelled)
                    ForegroundService.resetToIdle(ClawApplication.instance)
                    flushRoundBuffer()
                    val cancelledSession = releaseTask()
                    if (cancelledSession.channel != null && cancelledSession.messageId.isNotEmpty()) {
                        ChannelManager.sendMessage(
                            cancelledSession.channel,
                            ClawApplication.instance.getString(R.string.channel_msg_task_cancelled),
                            cancelledSession.messageId
                        )
                        ChannelManager.flushMessages(cancelledSession.channel)
                    }
                    FloatingCircleManager.setErrorState()
                    onTaskFinished()
                    XLog.d(TAG, "Current task cancelled by user")
                    return
                }
                // Strip common LLM-added prefixes from the answer
                var answer = finalAnswer.ifEmpty { "Done." }
                answer = answer.removePrefix("Task completed:").removePrefix("Task completed").trim()
                if (answer.isEmpty()) answer = "Done."
                taskEventCallback?.invoke(TaskEvent.Completed(answer, modelName))
                ForegroundService.resetToIdle(ClawApplication.instance)
                flushRoundBuffer()
                val completedSession = releaseTask()
                ChannelManager.flushMessages(completedSession.channel ?: channel)
                FloatingCircleManager.setSuccessState()
                // Auto-return to BQAAgent after in-app task completes
                if (completedSession.autoReturnToChat) {
                    XLog.i(TAG, "onComplete: auto-returning to BQAAgent chatroom")
                    try {
                        val context = ClawApplication.instance
                        val intent = android.content.Intent(context, io.agents.bqaagent.ui.chat.ComposeChatActivity::class.java).apply {
                            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                    android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                        }
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        XLog.w(TAG, "onComplete: auto-return failed", e)
                    }
                }
                onTaskFinished()
            }

            override fun onError(round: Int, error: Exception, totalTokens: Int) {
                if (!isCurrentSessionActive()) {
                    XLog.i(TAG, "Ignoring stale onError after task cancellation: ${error.message}")
                    return
                }
                XLog.e(TAG, "onError: ${error.message}, totalTokens=$totalTokens", error)
                taskEventCallback?.invoke(TaskEvent.Failed(error.message ?: "Unknown error"))
                ForegroundService.resetToIdle(ClawApplication.instance)
                flushRoundBuffer()
                val failedSession = releaseTask()
                val failedChannel = failedSession.channel ?: channel
                val failedMessageId = failedSession.messageId.ifEmpty { messageID }
                ChannelManager.sendMessage(
                    failedChannel,
                    ClawApplication.instance.getString(R.string.channel_msg_task_error, error.message),
                    failedMessageId
                )
                ChannelManager.flushMessages(failedChannel)
                FloatingCircleManager.setErrorState()
                onTaskFinished()
            }

            override fun onSystemDialogBlocked(round: Int, totalTokens: Int) {
                if (!isCurrentSessionActive()) return
                XLog.w(TAG, "onSystemDialogBlocked: round=$round, totalTokens=$totalTokens")
                taskEventCallback?.invoke(TaskEvent.Blocked)
                flushRoundBuffer()
                val blockedSession = releaseTask()
                val blockedChannel = blockedSession.channel ?: channel
                val blockedMessageId = blockedSession.messageId.ifEmpty { messageID }
                ChannelManager.sendMessage(
                    blockedChannel,
                    ClawApplication.instance.getString(R.string.channel_msg_system_dialog_blocked),
                    blockedMessageId
                )
                try {
                    val file = LocalAdbDeviceDriver.takeScreenshotFile()
                    if (file != null && file.exists()) {
                        ChannelManager.sendImage(blockedChannel, file.readBytes(), blockedMessageId)
                    }
                } catch (e: Exception) {
                    XLog.e(TAG, "Failed to send screenshot for system dialog", e)
                }
                FloatingCircleManager.setErrorState()
                onTaskFinished()
            }

            override fun onScreenshotBlocked(round: Int, intent: String, timeoutMs: Long): String? {
                if (!isCurrentSessionActive()) return null
                XLog.i(TAG, "onScreenshotBlocked: round=$round, intent=${intent.take(80)}")

                val targetPackage = try {
                    LocalAdbDeviceDriver.foregroundPackageName()
                } catch (e: Exception) {
                    XLog.w(TAG, "Failed to get foreground package", e)
                    ""
                }
                XLog.i(TAG, "onScreenshotBlocked: targetPackage=$targetPackage")

                taskSessionStore.markWaitingUserImage()
                taskEventCallback?.invoke(TaskEvent.ScreenshotBlocked(intent, timeoutMs))

                // Bring BQAAgent to foreground so user can see the upload UI
                try {
                    val context = ClawApplication.instance
                    val bringIntent = android.content.Intent(context, io.agents.bqaagent.ui.chat.ComposeChatActivity::class.java).apply {
                        flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                    context.startActivity(bringIntent)
                } catch (e: Exception) {
                    XLog.e(TAG, "Failed to bring app to foreground", e)
                }

                val latch = java.util.concurrent.CountDownLatch(1)
                pendingUserImageLatch = latch
                pendingUserImagePath = null

                val completed = latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)

                taskSessionStore.resumeFromWait()

                val resultPath = pendingUserImagePath
                pendingUserImageLatch = null
                pendingUserImagePath = null

                if (!completed || resultPath == null) {
                    XLog.i(TAG, "User image request timed out or skipped")
                    if (resultPath != null) {
                        try {
                            java.io.File(resultPath).delete()
                            XLog.i(TAG, "Cleaned up unused user image: $resultPath")
                        } catch (e: Exception) {
                            XLog.w(TAG, "Failed to cleanup user image: $resultPath", e)
                        }
                    }
                    taskEventCallback?.invoke(TaskEvent.UserImageProvided(null))
                    return null
                }

                XLog.i(TAG, "User image received: $resultPath, restoring target app: $targetPackage")
                if (targetPackage.isNotBlank()) {
                    try {
                        LocalAdbDeviceDriver.openApp(targetPackage)
                        Thread.sleep(500)
                    } catch (e: Exception) {
                        XLog.w(TAG, "Failed to restore target app", e)
                    }
                }

                taskEventCallback?.invoke(TaskEvent.UserImageProvided(resultPath))
                return resultPath
            }
        })
    }
}
