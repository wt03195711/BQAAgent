// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.agent.skill

import io.agents.bqaagent.ClawApplication
import io.agents.bqaagent.tool.ToolRegistry
import io.agents.bqaagent.utils.XLog
import java.util.UUID

object SkillTemplateBuilder {

    private const val TAG = "SkillTemplateBuilder"

    fun buildTemplate(skill: SkillDefinition, session: RecordingSession): ExecutionTemplate {
        val involved = collectInvolvedPackages(session.steps)
            .filter { it.isNotBlank() }
            .distinct()
        val env = SkillMatcher.buildCurrentEnvironment(involved)
        val now = System.currentTimeMillis()
        val derived = deriveKeyValues(session.steps, session.rawUserRequest)
        val template = ExecutionTemplate(
            templateId = "tmpl_" + UUID.randomUUID().toString().replace("-", ""),
            skillId = skill.skillId,
            environment = env,
            keyValues = derived,
            steps = session.steps,
            createdAtMs = now
        )
        XLog.i(TAG, "Built template ${template.templateId}: ${template.steps.size} steps, " +
                "${template.keyValues.size} key values, apps=${template.environment.appVersions.size}")
        return template
    }

    /**
     * All apps the recorded task touches: distinct expectedPackageAfter values,
     * excluding this agent app and OS system-dialog packages (their versions
     * change with system updates and templates have no control over them).
     */
    fun collectInvolvedPackages(steps: List<RecordedSkillStep>): List<String> {
        val selfPackage = ClawApplication.instance.packageName
        return steps
            .mapNotNull { it.expectedPackageAfter.takeIf { pkg -> pkg.isNotBlank() } }
            .distinct()
            .filter { it != selfPackage && it !in ReplayVerifier.SYSTEM_DIALOG_PACKAGES }
    }

    /**
     * Derive dynamic parameters from recorded-step facts intersected with the
     * original task text: only values the user actually said can be parameters.
     * Candidates come from every value parameter declared by the step's tool
     * metadata; a value consumed by several steps is recorded once, anchored
     * at the first consuming step so replay can substitute precisely.
     */
    fun deriveKeyValues(steps: List<RecordedSkillStep>, rawUserRequest: String): List<KeyValueParam> {
        val result = mutableListOf<KeyValueParam>()
        var autoIndex = 0

        for (step in steps) {
            val valueKeys = ToolRegistry.getInstance().getTool(step.toolName)?.getValueParamNames() ?: emptyList()
            for (key in valueKeys) {
                val candidate = step.params[key]?.toString()?.trim() ?: ""
                if (candidate.length < 2) continue
                if (!rawUserRequest.contains(candidate)) continue
                if (result.any { it.value == candidate }) continue
                val name = "param_${++autoIndex}"
                val purpose = "${step.displayName.ifBlank { step.toolName }} (${step.toolName}.$key)"
                result.add(KeyValueParam(name = name, value = candidate, purpose = purpose, stepIndex = step.stepIndex))
            }
        }
        XLog.d(TAG, "Derived ${result.size} key values from ${steps.size} steps: " +
                result.joinToString { "${it.name}=${it.value}" })
        return result
    }
}