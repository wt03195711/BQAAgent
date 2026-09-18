// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.agent.skill

import io.agents.bqaagent.utils.XLog

object SkillGovernor {

    private const val TAG = "SkillGovernor"
    private const val MAX_TEMPLATES_PER_SKILL = 5
    private const val QUICK_CIRCUIT_CONSECUTIVE_FAILS = 2
    private const val MIN_EXECUTIONS_FOR_RATE_CHECK = 3
    private const val SUCCESS_RATE_THRESHOLD = 0.50

    fun recordTemplateSuccess(template: ExecutionTemplate) {
        val updated = template.copy(
            executionCount = template.executionCount + 1,
            successCount = template.successCount + 1,
            lastUsedMs = System.currentTimeMillis(),
            lastFailedStepKey = "",
            consecutiveFailCount = 0
        )
        SkillStore.updateTemplate(updated)
        XLog.d(TAG, "Template ${template.templateId} success: " +
                "${updated.successCount}/${updated.executionCount}")
    }

    /**
     * Record a replay failure at the given step. If the same step fails
     * [QUICK_CIRCUIT_CONSECUTIVE_FAILS] times consecutively, the template is
     * marked unavailable (quick circuit break).
     */
    fun recordTemplateFailure(template: ExecutionTemplate, failedStepIndex: Int, failedToolName: String) {
        val key = "$failedStepIndex:$failedToolName"
        val newCount = if (template.lastFailedStepKey == key) template.consecutiveFailCount + 1 else 1
        val circuitBroken = newCount >= QUICK_CIRCUIT_CONSECUTIVE_FAILS
        val newExecutionCount = template.executionCount + 1
        val rateBroken = newExecutionCount > MIN_EXECUTIONS_FOR_RATE_CHECK &&
                template.successCount.toDouble() / newExecutionCount < SUCCESS_RATE_THRESHOLD
        val updated = template.copy(
            executionCount = newExecutionCount,
            lastUsedMs = System.currentTimeMillis(),
            lastFailedStepKey = key,
            consecutiveFailCount = newCount,
            isAvailable = template.isAvailable && !circuitBroken && !rateBroken
        )
        SkillStore.updateTemplate(updated)
        when {
            circuitBroken -> XLog.w(TAG, "Template ${template.templateId} circuit-broken: " +
                    "$newCount consecutive failures at step $key")
            rateBroken -> XLog.w(TAG, "Template ${template.templateId} rate-broken: " +
                    "${updated.successCount}/$newExecutionCount success rate below $SUCCESS_RATE_THRESHOLD")
            else -> XLog.d(TAG, "Template ${template.templateId} failure recorded at step $key " +
                    "($newCount/$QUICK_CIRCUIT_CONSECUTIVE_FAILS)")
        }
    }

    /**
     * Keep at most [MAX_TEMPLATES_PER_SKILL] templates per skill.
     * Evicts lowest success rate first, oldest last-used as tie-breaker.
     */
    fun enforceTemplateLimit(skillId: String) {
        val templates = SkillStore.getTemplates(skillId)
        if (templates.size <= MAX_TEMPLATES_PER_SKILL) return
        val sorted = templates.sortedWith(
            compareBy<ExecutionTemplate> { successRateOf(it) }.thenBy { it.lastUsedMs }
        )
        val toRemove = sorted.take(templates.size - MAX_TEMPLATES_PER_SKILL)
        toRemove.forEach { tmpl ->
            SkillStore.removeTemplate(skillId, tmpl.templateId)
            XLog.i(TAG, "Evicted template ${tmpl.templateId} (skill=$skillId, " +
                    "rate=${successRateOf(tmpl)})")
        }
    }

    private fun successRateOf(template: ExecutionTemplate): Double {
        return if (template.executionCount > 0) {
            template.successCount.toDouble() / template.executionCount
        } else {
            0.5
        }
    }
}
