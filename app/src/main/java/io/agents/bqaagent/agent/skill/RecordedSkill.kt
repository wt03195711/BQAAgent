// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.agent.skill

data class RecordedSkillStep(
    val stepIndex: Int,
    val toolName: String,
    val displayName: String,
    val params: Map<String, Any>,
    val elapsedMs: Long = 0L,
    val expectedPackageAfter: String = "",
    val expectedActivityAfter: String = "",
    val anchorTexts: List<String> = emptyList(),
    val nodeLocator: NodeLocator? = null,
    val targetText: String = "",
    val intent: String? = null
)

data class NodeLocator(
    val paramName: String = "",
    val text: String = "",
    val contentDescription: String = "",
    val resourceId: String = "",
    val boundsLeft: Int = 0,
    val boundsTop: Int = 0,
    val boundsRight: Int = 0,
    val boundsBottom: Int = 0
)

data class ReplayResult(
    val success: Boolean,
    val stepsUsed: Int,
    val tokensUsed: Int = 0,
    val fallbackUsed: Boolean = false,
    val message: String = "",
    val cancelled: Boolean = false
)
