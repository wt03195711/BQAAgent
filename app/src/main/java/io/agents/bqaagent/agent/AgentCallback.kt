// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.agent

import io.agents.bqaagent.TaskStatus
import io.agents.bqaagent.tool.ToolResult

interface AgentCallback {
    /**
     * Callback when a new Agent Loop round starts
     * @param round current round number (starts from 1)
     */
    fun onLoopStart(round: Int)
    fun onContent(round: Int, content: String)
    fun onToolCall(round: Int, toolId: String, toolName: String, parameters: String)
    fun onToolResult(round: Int, toolId: String, toolName: String, parameters: String, result: ToolResult)
    fun onTokenUpdate(status: TokenMonitor.Status) {}
    fun onComplete(
        round: Int,
        finalAnswer: String,
        totalTokens: Int,
        modelName: String?,
        status: TaskStatus,
        reasonCode: String? = null
    )
    fun onError(round: Int, error: Exception, totalTokens: Int)
    fun onSystemDialogBlocked(round: Int, totalTokens: Int)
    /**
     * Called when analyze_screen_visual returns SCREENSHOT_BLOCKED.
     * Blocks the agent thread until the user uploads an image, skips, or times out.
     *
     * @param round current iteration
     * @param intent the LLM's original intent description for visual analysis
     * @param timeoutMs max wait time in milliseconds
     * @return absolute path to user-uploaded image, or null if skipped/timed out
     */
    fun onScreenshotBlocked(round: Int, intent: String, timeoutMs: Long): String?

    /**
     * Called when the sensitive-confirm gate is raised (AI-judged gate tool or a hard-gate
     * tool such as secure_keypad_input / input_amount). Blocks the agent thread until the
     * user approves/denies or the timeout elapses.
     *
     * Default implementation auto-approves so non-UI callers (tests, channels) keep working.
     *
     * @param round current iteration
     * @param actionSummary human-readable summary of the risky action
     * @param riskType "payment" | "password" | other free-form label
     * @param timeoutMs max wait time in milliseconds
     * @return true if the user approved, false if denied/timed out
     */
    fun onSensitiveConfirm(round: Int, actionSummary: String, riskType: String, timeoutMs: Long): Boolean = true

    /**
     * Session liveness probe (C3). The orchestrator owns the task session; when it is released,
     * cancelled or handed to another run, every in-flight loop must treat the current task as dead
     * and stop before touching the device again.
     *
     * Deliberately independent of DefaultAgentService's own `cancelled` flag: that flag is reset at
     * the start of a run, so a cancel that lands while the run is being submitted would otherwise be
     * erased and the loop would resurrect with no owner left able to stop it.
     *
     * Default false keeps non-UI callers (tests, channels, skills) behaving exactly as before.
     */
    fun isSessionCancelled(): Boolean = false

    /**
     * Fired from the executeTask finally EVERY time the engine clears its running flag — including
     * when cancellation suppresses the terminal callback. Consumers that publish liveness (the
     * orchestrator) must use this to republish `busy`; without it a cancelled loop's exit is
     * unobservable and the cross-mode gate freezes on a stale "busy" forever.
     */
    fun onEngineSettled() {}
}
