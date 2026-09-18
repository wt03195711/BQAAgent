// Copyright 2026 BQAAgent (agents.io). All rights reserved. // Licensed under the Apache License, Version 2.0.
package io.agents.bqaagent.agent.skill

data class SkillDefinition(
    val skillId: String,
    val title: String,
    val originalTaskText: String,
    val createdAtMs: Long,
    val updatedAtMs: Long
) {
    companion object {
        /** Maximum allowed length of a skill title; enforced by all edit entry points */
        const val SKILL_TITLE_MAX_LENGTH = 60
    }
}

/**
 * One derived dynamic parameter of a template.
 * @param purpose what the value is used for (derived from the consuming step)
 * @param stepIndex the step where the value was first consumed; -1 for
 *   templates migrated from the legacy Map<String, String> format
 */
data class KeyValueParam(
    val name: String,
    val value: String,
    val purpose: String = "",
    val stepIndex: Int = -1
)

data class ExecutionTemplate(
    val templateId: String,
    val skillId: String,
    val environment: EnvironmentInfo,
    val keyValues: List<KeyValueParam> = emptyList(),
    val steps: List<RecordedSkillStep>,
    val executionCount: Int = 0,
    val successCount: Int = 0,
    val lastUsedMs: Long = 0L,
    val isAvailable: Boolean = true,
    val lastFailedStepKey: String = "",
    val consecutiveFailCount: Int = 0,
    val createdAtMs: Long
)

/**
 * Device identity plus the version of EVERY app the task touches.
 */
data class EnvironmentInfo(
    val appVersions: Map<String, Int> = emptyMap(),
    val language: String = "",
    val country: String = "",
    val screenWidth: Int = 0,
    val screenHeight: Int = 0,
    val brand: String = "",
    val model: String = "",
    val fontScale: Float
)

data class RecordingSession(
    val rawUserRequest: String,
    val steps: List<RecordedSkillStep>
)

data class SkillSaveData(
    val recordingSession: RecordingSession,
    val originalTaskText: String,
    val matchedSkill: SkillDefinition? = null,
    val degradedTemplateId: String? = null
)

sealed class SkillMatchResult {
    object NoMatch : SkillMatchResult()

    data class SkillOnly(
        val skill: SkillDefinition,
        val degradedTemplateId: String? = null
    ) : SkillMatchResult()

    data class FullMatch(
        val skill: SkillDefinition,
        val template: ExecutionTemplate,
        val extractedParams: Map<String, String>
    ) : SkillMatchResult()
}

/**
 * Output of the single LLM matching call: which stored skill/template matches
 * the new task and the parameter values to substitute. All null/empty when no
 * skill matches. Code gates decide whether this result is trustworthy.
 */
data class SkillAnalysis(
    val matchedSkillId: String? = null,
    val matchedTemplateId: String? = null,
    val extractedParams: Map<String, String> = emptyMap()
)