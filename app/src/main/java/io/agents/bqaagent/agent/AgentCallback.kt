// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.agent

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
    fun onComplete(round: Int, finalAnswer: String, totalTokens: Int, modelName: String? = null)
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
}
