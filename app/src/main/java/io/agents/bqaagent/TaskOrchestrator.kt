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
import io.agents.bqaagent.agent.skill.SkillRecorder
import io.agents.bqaagent.agent.skill.SkillAnalyzer
import io.agents.bqaagent.agent.skill.SkillAnalysis
import io.agents.bqaagent.agent.skill.SkillMatchResult
import io.agents.bqaagent.agent.skill.SkillMatcher
import io.agents.bqaagent.agent.skill.SkillSaveData
import io.agents.bqaagent.agent.skill.SkillReplayer
import io.agents.bqaagent.channel.Channel
import io.agents.bqaagent.channel.ChannelManager
import io.agents.bqaagent.floating.FloatingCircleManager
import io.agents.bqaagent.adb.LocalAdbDeviceDriver
import io.agents.bqaagent.recording.RecordingOutcome
import io.agents.bqaagent.recording.TaskRecordingCoordinator
import io.agents.bqaagent.service.ForegroundService
import io.agents.bqaagent.tool.ToolResult
import io.agents.bqaagent.utils.XLog
import io.agents.bqaagent.utils.KVUtils

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

    /** Currently running replayer (if any) so cancellation can interrupt it. */
    @Volatile
    private var activeReplayer: SkillReplayer? = null

    /** Pending replay-confirmation handoff (same pattern as pendingUserImage). */
    private var pendingReplayConfirmLatch: java.util.concurrent.CountDownLatch? = null
    @Volatile
    private var pendingReplayConfirmed = false

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

    private fun releaseTask(outcome: RecordingOutcome = RecordingOutcome.UNKNOWN): TaskSessionState {
        val state = taskSessionStore.release()
        // Non-blocking: hands the tail-off to the recorder's own single-thread scheduler.
        // A no-op when recording is off or nothing was ever armed.
        TaskRecordingCoordinator.stop(outcome)
        return state
    }

    /**
     * Bring BQAAgent back to the foreground after a task ends so the user sees the terminal result
     * (success / failure / stop / cancel) in the chatroom instead of being stranded in the target
     * app. Mirrors the success path for all four terminal states.
     */
    private fun autoReturnToChatIfNeeded(session: TaskSessionState) {
        if (!session.autoReturnToChat) return
        XLog.i(TAG, "autoReturnToChatIfNeeded: returning to BQAAgent chatroom")
        try {
            val context = ClawApplication.instance
            val intent = android.content.Intent(context, io.agents.bqaagent.ui.chat.ComposeChatActivity::class.java).apply {
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            XLog.w(TAG, "autoReturnToChatIfNeeded: auto-return failed", e)
        }
    }

    fun isTaskRunning(): Boolean = taskSessionStore.isTaskRunning()

    // ==================== Task Execution ====================

    fun cancelCurrentTask() {
        if (!taskSessionStore.markStopping()) return
        // Interrupt an in-flight replay; its loop polls this flag between phases.
        activeReplayer?.cancel()
        // Release any pending user image wait so the agent thread doesn't hang
        cleanupPendingUserImage()
        pendingUserImageLatch?.countDown()
        // Release a pending replay confirmation so the pipeline thread doesn't hang
        pendingReplayConfirmLatch?.countDown()
        val cancelledSession = releaseTask(RecordingOutcome.CANCELLED)
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
        FloatingCircleManager.setCancelledState()
        onTaskFinished()
        if (::agentService.isInitialized) {
            agentService.cancel()
        }
        XLog.d(TAG, "Current task cancellation requested")
    }

    fun provideUserImage(imagePath: String?) {
        pendingUserImagePath = imagePath
        pendingUserImageLatch?.countDown()
    }

    /**
     * Called by UI when the user confirms or declines the replay confirmation
     * dialog (TaskEvent.ReplayConfirmRequest).
     */
    fun resolveReplayConfirm(confirmed: Boolean) {
        pendingReplayConfirmed = confirmed
        pendingReplayConfirmLatch?.countDown()
    }

    /**
     * Block the pipeline thread until the user confirms or declines the replay
     * offered via TaskEvent.ReplayConfirmRequest. Deliberately no timeout:
     * replay must never start without an explicit user decision, however long
     * the review takes. The wait is bounded anyway because task cancellation
     * counts down the latch, and the pipeline runs on a dedicated worker
     * thread (no ANR risk).
     */
    private fun awaitReplayConfirmation(): Boolean {
        val latch = java.util.concurrent.CountDownLatch(1)
        pendingReplayConfirmed = false
        pendingReplayConfirmLatch = latch
        try {
            latch.await()
            return pendingReplayConfirmed
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        } finally {
            pendingReplayConfirmLatch = null
        }
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
        // The pipeline contains blocking LLM calls (SkillAnalyzer). Running it on
        // the caller thread would freeze the chat UI and trigger an ANR when
        // invoked from the UI thread, so always run it on a worker thread.
        Thread({
            startNewTaskInternal(channel, task, messageID, agentPromptOverride, isFallback)
        }, "task-pipeline").start()
    }

    private fun startNewTaskInternal(
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
            // Arm only on a fresh acquire: fallback re-entry (Skill/Replay -> AgentLoop) takes the
            // else-branch below, so the same recording continues across the whole task.
            TaskRecordingCoordinator.arm(messageID, channel.displayName, task)
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
                    TaskRecordingCoordinator.start("direct-tool:${route.toolName}")
                    var success = false
                    val answer = try {
                        val toolResult = pipelineRouter.executeTool(route.toolName, route.params)
                        if (!toolResult.isSuccess) {
                            val error = toolResult.error ?: "Unknown error"
                            XLog.w(TAG, "Tier 1 tool failed: $error")
                            taskEventCallback?.invoke(TaskEvent.Failed("${route.description}: $error", TaskReasonCode.OTHER))
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
                        releaseTask(if (success) RecordingOutcome.COMPLETED else RecordingOutcome.FAILED)
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
                            TaskRecordingCoordinator.start("skill:${route.skillId}")
                            val skillResult = skillExecutor.execute(skill, route.params) { step, total, desc ->
                                TaskRecordingCoordinator.markDeviceActive()
                                taskEventCallback?.invoke(TaskEvent.Progress(step, "Step $step/$total: $desc"))
                                ForegroundService.updateTaskStatus(ClawApplication.instance, desc)
                            }
                            if (skillResult.success) {
                                ChannelManager.sendMessage(channel, skillResult.message, messageID)
                                taskEventCallback?.invoke(TaskEvent.Completed(skillResult.message))
                                releaseTask(RecordingOutcome.COMPLETED)
                                FloatingCircleManager.setSuccessState()
                                ForegroundService.resetToIdle(ClawApplication.instance)
                                onTaskFinished()
                            } else {
                                val fallbackGoal = skill.fallbackGoal
                                    .let { g -> route.params.entries.fold(g) { acc, (k, v) -> acc.replace("{$k}", v) } }
                                XLog.i(TAG, "Skill ${skill.id} failed, falling back to agent loop: $fallbackGoal")
                                taskEventCallback?.invoke(TaskEvent.ToolAction("Retrying with AI agent"))
                                taskEventCallback?.invoke(
                                    TaskEvent.ToolResult("Retrying with AI agent", false, skillResult.message)
                                )
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

        // Tier 1.8: LLM-based Skill matching (scenarios 1-4)
        var sessionMatchResult: SkillMatchResult? = null
        if (!isFallback) {
            if (KVUtils.isSkillCaptureModeEnabled()) {
                SkillRecorder.enableRecording()

                try {
                    val analysis: SkillAnalysis? = if (KVUtils.isLlmSkillMatchingEnabled()) {
                        taskEventCallback?.invoke(TaskEvent.Progress(0, "🧠 Analyzing your task…"))
                        // SkillAnalyzer performs a blocking LLM call; startNewTask may run on
                        // the UI thread, so run it on a worker thread with a hard timeout.
                        val analysisHolder = arrayOfNulls<SkillAnalysis>(1)
                        val analyzeLatch = java.util.concurrent.CountDownLatch(1)
                        Thread({
                            try {
                                analysisHolder[0] = SkillAnalyzer.analyze(task)
                            } catch (e: Exception) {
                                XLog.w(TAG, "Skill analysis failed: ${e.message}")
                            } finally {
                                analyzeLatch.countDown()
                            }
                        }, "skill-analyzer").start()
                        if (!analyzeLatch.await(300, java.util.concurrent.TimeUnit.SECONDS)) {
                            XLog.w(TAG, "Skill analysis timed out after 300s, treating as no match")
                        }
                        analysisHolder[0]
                    } else {
                        // L0-only matching: skip the LLM entirely. SkillMatcher.match() runs the
                        // verbatim task-text equality check before reading `analysis`, so passing
                        // null restricts matching to exact replays and never triggers semantic matching.
                        XLog.i(TAG, "LLM skill matching disabled — using L0 exact-text match only")
                        null
                    }
                    sessionMatchResult = SkillMatcher.match(task, analysis)
                } catch (e: Exception) {
                    XLog.w(TAG, "Skill matching failed, falling through to agent loop", e)
                    sessionMatchResult = null
                }

                when (val match = sessionMatchResult) {
                    is SkillMatchResult.FullMatch -> {
                        XLog.i(TAG, "Pipeline Tier 1.8: full match — skill=${match.skill.skillId}, template=${match.template.templateId}")
                        // Let the user review the matched template before touching the
                        // device. Declining degrades to the no-usable-template scenario:
                        // AgentLoop re-runs the task with recording on, and afterwards
                        // the user is offered to update the template in place.
                        taskEventCallback?.invoke(
                            TaskEvent.ReplayConfirmRequest(match.skill, match.template, match.extractedParams)
                        )
                        val replayConfirmed = awaitReplayConfirmation()
                        if (!isCurrentSessionActive()) {
                            XLog.i(TAG, "Task cancelled while waiting for replay confirmation")
                            return
                        }
                        if (!replayConfirmed) {
                            XLog.i(TAG, "Replay declined — degrading to agent loop, template " +
                                    "${match.template.templateId} offered for update afterwards")
                            taskEventCallback?.invoke(
                                TaskEvent.Progress(0, "⏭️ Replay declined — running \"${match.skill.title}\" with AI agent")
                            )
                            sessionMatchResult = SkillMatchResult.SkillOnly(
                                match.skill,
                                degradedTemplateId = match.template.templateId
                            )
                        }
                        if (replayConfirmed) {
                            FloatingCircleManager.ensureShowing()
                            FloatingCircleManager.showTaskNotify(task, channel)
                            Thread({
                                taskEventCallback?.invoke(TaskEvent.ReplayStart(match.skill.title, match.template.steps.size))
                                // After the ReplayStart event and before any device action, so the
                                // recording never starts mid-gesture.
                                TaskRecordingCoordinator.start("replay:${match.skill.skillId}")
                                val replayer = SkillReplayer(
                                    skill = match.skill,
                                    template = match.template,
                                    extractedParams = match.extractedParams,
                                    isSessionActive = { isCurrentSessionActive() }
                                )
                                activeReplayer = replayer
                                val replayResult = try {
                                    replayer.replay(
                                        onStepProgress = { _, _, desc ->
                                            TaskRecordingCoordinator.markDeviceActive()
                                            ForegroundService.updateTaskStatus(ClawApplication.instance, desc)
                                        },
                                        onStepResult = { step, total, name, success, detail, durationMs, params ->
                                            TaskRecordingCoordinator.markDeviceActive()
                                            TaskRecordingCoordinator.addMarker(
                                                "replay-step",
                                                "$step/$total $name ${if (success) "ok" else "fail"}"
                                            )
                                            taskEventCallback?.invoke(TaskEvent.ReplayStep(step, total, name, success, detail, durationMs, params))
                                        }
                                    )
                                } finally {
                                    activeReplayer = null
                                }
                                if (replayResult.success) {
                                    // Scenario 4: replay succeeded — bring user back to chat UI first
                                    returnToAgentApp()
                                    val answer = "✅ Replay succeeded — skill \"${match.skill.title}\"\n${replayResult.message}"
                                    ChannelManager.sendMessage(channel, answer, messageID)
                                    taskEventCallback?.invoke(TaskEvent.Completed(answer))
                                    releaseTask(RecordingOutcome.COMPLETED)
                                    FloatingCircleManager.setSuccessState()
                                    ForegroundService.resetToIdle(ClawApplication.instance)
                                    onTaskFinished()
                                } else if (replayResult.cancelled) {
                                    // User stopped the task — cleanup was already done by
                                    // cancelCurrentTask(); do NOT fall back to the agent loop.
                                    XLog.i(TAG, "Replay cancelled by user, skipping fallback")
                                } else {
                                    // Scenario 3: replay failed — return to chat UI, report, then degrade to AgentLoop
                                    returnToAgentApp()
                                    XLog.i(TAG, "Skill replay failed: ${replayResult.message}, falling back to agent loop")
                                    taskEventCallback?.invoke(TaskEvent.Progress(0, "❌ Replay failed — skill \"${match.skill.title}\": ${replayResult.message}"))
                                    taskEventCallback?.invoke(TaskEvent.ToolAction("Retrying with AI agent"))
                                    taskEventCallback?.invoke(
                                        TaskEvent.ToolResult("Retrying with AI agent", false, "Replay failed")
                                    )
                                    startNewTask(channel, task, messageID, isFallback = true)
                                }
                            }, "skill-replayer").start()
                            return
                        }
                    }
                    is SkillMatchResult.SkillOnly -> {

                        // Scenario 2: skill matched but no usable template — AgentLoop will
                        // auto-create a new template on completion
                        XLog.i(TAG, "Pipeline Tier 1.8: scenario 2 — skill=${match.skill.skillId}, no usable template")
                    }
                    is SkillMatchResult.NoMatch -> {
                        // Scenario 1: no matching skill — offer save after AgentLoop completes
                        XLog.i(TAG, "Pipeline Tier 1.8: scenario 1 — no matching skill")
                    }
                    null -> {
                        XLog.i(TAG, "Pipeline Tier 1.8: matching skipped or failed, using agent loop")
                    }
                }
            } else {
                // Skill Capture Mode off: the entire skill pipeline (recording, saving,
                // analysis, matching and replay) is disabled — the task goes straight
                // to the agent loop with chat history context.
                SkillRecorder.disableRecording()
                XLog.i(TAG, "Skill Capture Mode off: skill recording/save/replay disabled")
            }
        } else {
            // Fallback after replay failure (scenario 3): do not record the mixed flow
            SkillRecorder.disableRecording()
        }

        if (!updateAgentConfig()) {
            XLog.e(TAG, "Failed to prepare AgentService for task")
            releaseTask(RecordingOutcome.FAILED)
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
                releaseTask(RecordingOutcome.FAILED)
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

        // Recording starts before the first round so the whole agent loop is captured.
        TaskRecordingCoordinator.start(if (isFallback) "agent-loop-fallback" else "agent-loop")

        val agentPrompt = agentPromptOverride?.takeIf { it.isNotBlank() } ?: task
        agentService.executeTask(agentPrompt, object : AgentCallback {
            override fun onLoopStart(round: Int) {
                if (!isCurrentSessionActive()) return
                // A new round means the device is idle while the LLM thinks — a safe rotation window.
                TaskRecordingCoordinator.markDeviceIdle()
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
                TaskRecordingCoordinator.markDeviceIdle()
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
                    estimatedCostUsd = status.estimatedCostUsd,
                    intent = status.intent
                ))
            }

            override fun onContent(round: Int, content: String) {
                if (!isCurrentSessionActive()) return
                TaskRecordingCoordinator.markDeviceIdle()
                if (content.isNotEmpty()) {
                    roundBuffer.append(content)
                    taskEventCallback?.invoke(TaskEvent.Thinking(content))
                }
            }

            override fun onToolCall(round: Int, toolId: String, toolName: String, parameters: String) {
                if (!isCurrentSessionActive()) return
                // The device is about to be touched: defer segment rotation out of this window.
                TaskRecordingCoordinator.markDeviceActive()
                TaskRecordingCoordinator.addMarker("round $round", toolName)
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
                    taskEventCallback?.invoke(TaskEvent.ToolAction(displayName, formatToolParams(parameters)))
                    ForegroundService.updateTaskStatus(ClawApplication.instance, "$displayName...")
                }
            }

            override fun onToolResult(round: Int, toolId: String, toolName: String, parameters: String, result: ToolResult) {
                if (!isCurrentSessionActive()) return
                // ScreenSettleWaiter may still be sampling after an action tool — stay unsafe.
                TaskRecordingCoordinator.markDeviceActive()
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

            override fun onComplete(round: Int, finalAnswer: String, totalTokens: Int, modelName: String?, status: TaskStatus, reasonCode: String?) {
                if (!isCurrentSessionActive()) {
                    XLog.i(TAG, "Ignoring stale onComplete after task cancellation")
                    return
                }
                XLog.i(TAG, "onComplete: rounds=$round, totalTokens=$totalTokens, model=$modelName, status=$status, reason=$reasonCode, answer=$finalAnswer")

                // 1) User cancellation — highest priority.
                if (status == TaskStatus.CANCELLED) {
                    SkillRecorder.discard()
                    taskEventCallback?.invoke(TaskEvent.Cancelled)
                    ForegroundService.resetToIdle(ClawApplication.instance)
                    flushRoundBuffer()
                    val cancelledSession = releaseTask(RecordingOutcome.CANCELLED)
                    if (cancelledSession.channel != null && cancelledSession.messageId.isNotEmpty()) {
                        ChannelManager.sendMessage(
                            cancelledSession.channel,
                            ClawApplication.instance.getString(R.string.channel_msg_task_cancelled),
                            cancelledSession.messageId
                        )
                        ChannelManager.flushMessages(cancelledSession.channel)
                    }
                    FloatingCircleManager.setCancelledState()
                    onTaskFinished()
                    return
                }

                // 2) System-decided stop (token/iteration/stuck/sensitive/unusable/image).
                if (status == TaskStatus.STOPPED) {
                    SkillRecorder.discard()
                    taskEventCallback?.invoke(TaskEvent.Stopped(reasonCode ?: TaskReasonCode.OTHER, finalAnswer))
                    ForegroundService.resetToIdle(ClawApplication.instance)
                    flushRoundBuffer()
                    val stoppedSession = releaseTask(RecordingOutcome.STOPPED)
                    val stoppedChannel = stoppedSession.channel ?: channel
                    val stoppedMessageId = stoppedSession.messageId.ifEmpty { messageID }
                    ChannelManager.sendMessage(stoppedChannel, finalAnswer, stoppedMessageId)
                    ChannelManager.flushMessages(stoppedChannel)
                    FloatingCircleManager.setStoppedState()
                    autoReturnToChatIfNeeded(stoppedSession)
                    onTaskFinished()
                    return
                }

                // 3) LLM-judged failure (finish status=failed, evidence downgrade, or empty response).
                if (status == TaskStatus.FAILED) {
                    SkillRecorder.discard()
                    val failureText = finalAnswer.ifEmpty { "Task could not be completed." }
                    taskEventCallback?.invoke(TaskEvent.Failed(failureText, reasonCode ?: TaskReasonCode.OTHER))
                    ForegroundService.resetToIdle(ClawApplication.instance)
                    flushRoundBuffer()
                    val failedSession = releaseTask(RecordingOutcome.FAILED)
                    val failedChannel = failedSession.channel ?: channel
                    val failedMessageId = failedSession.messageId.ifEmpty { messageID }
                    ChannelManager.sendMessage(failedChannel, failureText, failedMessageId)
                    ChannelManager.flushMessages(failedChannel)
                    FloatingCircleManager.setErrorState()
                    autoReturnToChatIfNeeded(failedSession)
                    onTaskFinished()
                    return
                }

                // 4) SUCCESS (reasonCode TASK_SUCCESS or CHAT_ONLY).
                var answer = finalAnswer.ifEmpty { "Done." }
                answer = answer.removePrefix("Task completed:").removePrefix("Task completed").trim()
                if (answer.isEmpty()) answer = "Done."

                val isChatOnly = reasonCode == TaskReasonCode.CHAT_ONLY

                // === Skill post-processing on successful completion (never for pure chat) ===
                var skillSaveData: SkillSaveData? = null
                if (isChatOnly) {
                    SkillRecorder.discard()
                } else {
                    try {
                        val session = SkillRecorder.stopWithoutSave()
                        if (session != null) {
                            when (val match = sessionMatchResult) {
                                is SkillMatchResult.SkillOnly -> {
                                    skillSaveData = SkillSaveData(
                                        recordingSession = session,
                                        originalTaskText = task,
                                        matchedSkill = match.skill,
                                        degradedTemplateId = match.degradedTemplateId
                                    )
                                    XLog.i(TAG, "Scenario 2: template save offered to UI for skill ${match.skill.skillId}")
                                }
                                is SkillMatchResult.NoMatch -> {
                                    skillSaveData = SkillSaveData(
                                        recordingSession = session,
                                        originalTaskText = task
                                    )
                                    XLog.i(TAG, "Scenario 1: skill save offered to UI")
                                }
                                is SkillMatchResult.FullMatch, null -> Unit
                            }
                        }
                    } catch (e: Exception) {
                        XLog.w(TAG, "Skill post-processing failed", e)
                    }
                }

                taskEventCallback?.invoke(TaskEvent.Completed(answer, modelName, skillSaveData, reasonCode ?: TaskReasonCode.TASK_SUCCESS))
                ForegroundService.resetToIdle(ClawApplication.instance)
                flushRoundBuffer()
                val completedSession = releaseTask(RecordingOutcome.COMPLETED)
                ChannelManager.flushMessages(completedSession.channel ?: channel)
                FloatingCircleManager.setSuccessState()
                autoReturnToChatIfNeeded(completedSession)
                onTaskFinished()
            }

            override fun onError(round: Int, error: Exception, totalTokens: Int) {
                if (!isCurrentSessionActive()) {
                    XLog.i(TAG, "Ignoring stale onError after task cancellation: ${error.message}")
                    return
                }
                XLog.e(TAG, "onError: ${error.message}, totalTokens=$totalTokens", error)
                SkillRecorder.discard()
                val errorMessage = error.message ?: "Unknown error"
                taskEventCallback?.invoke(TaskEvent.Stopped(TaskReasonCode.LLM_ERROR, errorMessage))
                ForegroundService.resetToIdle(ClawApplication.instance)
                flushRoundBuffer()
                val stoppedSession = releaseTask(RecordingOutcome.STOPPED)
                val stoppedChannel = stoppedSession.channel ?: channel
                val stoppedMessageId = stoppedSession.messageId.ifEmpty { messageID }
                ChannelManager.sendMessage(
                    stoppedChannel,
                    ClawApplication.instance.getString(R.string.channel_msg_task_error, errorMessage),
                    stoppedMessageId
                )
                ChannelManager.flushMessages(stoppedChannel)
                FloatingCircleManager.setStoppedState()
                autoReturnToChatIfNeeded(stoppedSession)
                onTaskFinished()
            }

            override fun onSystemDialogBlocked(round: Int, totalTokens: Int) {
                if (!isCurrentSessionActive()) return
                XLog.w(TAG, "onSystemDialogBlocked: round=$round, totalTokens=$totalTokens")
                SkillRecorder.discard()
                val blockedMessage = ClawApplication.instance.getString(R.string.channel_msg_system_dialog_blocked)
                taskEventCallback?.invoke(TaskEvent.Stopped(TaskReasonCode.SYSTEM_DIALOG, blockedMessage))
                flushRoundBuffer()
                val blockedSession = releaseTask(RecordingOutcome.STOPPED)
                val blockedChannel = blockedSession.channel ?: channel
                val blockedMessageId = blockedSession.messageId.ifEmpty { messageID }
                ChannelManager.sendMessage(blockedChannel, blockedMessage, blockedMessageId)
                try {
                    val file = LocalAdbDeviceDriver.takeScreenshotFile()
                    if (file != null && file.exists()) {
                        ChannelManager.sendImage(blockedChannel, file.readBytes(), blockedMessageId)
                    }
                } catch (e: Exception) {
                    XLog.e(TAG, "Failed to send screenshot for system dialog", e)
                }
                FloatingCircleManager.setStoppedState()
                autoReturnToChatIfNeeded(blockedSession)
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
                // Waiting for the user: the device is idle and BQAAgent is about to be foregrounded.
                TaskRecordingCoordinator.markDeviceIdle()
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
                TaskRecordingCoordinator.markDeviceActive()

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

    /**
     * After replay the device usually stays in the target app. Bring BQAAgent back
     * to foreground so the user sees the replay result in the chat UI.
     */
    private fun returnToAgentApp() {
        try {
            LocalAdbDeviceDriver.openApp(ClawApplication.instance.packageName)
            Thread.sleep(1500)
        } catch (e: Exception) {
            XLog.w(TAG, "Failed to return to agent app", e)
        }
    }

    private fun formatToolParams(rawParams: String): String {
        val trimmed = rawParams.trim()
        if (trimmed.isEmpty() || trimmed == "{}") return ""
        return try {
            val obj = org.json.JSONObject(trimmed)
            obj.keys().asSequence()
                .joinToString(", ") { key -> "$key=${obj.opt(key)}" }
                .take(200)
        } catch (e: Exception) {
            trimmed.take(200)
        }
    }
}
