// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.agent.skill

import io.agents.bqaagent.utils.XLog
import java.util.UUID

/**
 * UI-free skill persistence. This is the pure save logic behind TaskFlowController.confirmSkillSave,
 * extracted so a batch run can auto-save/auto-update a skill with no overlay flow.
 *
 * Semantics are identical to the confirmed product rules:
 *  - matchedSkill != null (scenario 2): update that skill's template in place. A degraded template is
 *    removed and rebuilt under the SAME id, so the slot is revived rather than duplicated.
 *  - matchedSkill == null (scenario 1): create a brand-new skill + template from the recording.
 *
 * Returns the saved title, or null if persistence failed (callers decide how to surface it).
 */
object SkillAutoSaver {

    private const val TAG = "SkillAutoSaver"
    /** Upper bound for the auto-derived default title (mirrors TaskFlowController). */
    private const val DEFAULT_TITLE_MAX_LENGTH = 30

    fun save(saveData: SkillSaveData): String? {
        return try {
            val session = saveData.recordingSession
            val matchedSkill = saveData.matchedSkill
            if (matchedSkill != null) {
                val degradedId = saveData.degradedTemplateId
                if (degradedId != null) {
                    SkillStore.removeTemplate(matchedSkill.skillId, degradedId)
                }
                val built = SkillTemplateBuilder.buildTemplate(matchedSkill, session)
                val template = if (degradedId != null) built.copy(templateId = degradedId) else built
                SkillStore.saveTemplate(template)
                SkillGovernor.enforceTemplateLimit(matchedSkill.skillId)
                XLog.i(TAG, "Auto-updated template ${template.templateId} for skill ${matchedSkill.skillId}")
                matchedSkill.title
            } else {
                // Cap guard (authoritative choke point): never grow the library past MAX_SKILLS in an
                // unattended batch. The batch driver pre-checks this too and surfaces a transcript note;
                // this backstop keeps the limit binding regardless of caller. Updating a skill (the branch
                // above) is never capped.
                if (SkillStore.getAllSkills().size >= SkillStore.MAX_SKILLS) {
                    XLog.w(TAG, "Skill cap (${SkillStore.MAX_SKILLS}) reached — abandoning new-skill auto-save")
                    return null
                }
                val now = System.currentTimeMillis()
                val resolvedTitle = defaultTitleFromTaskText(saveData.originalTaskText)
                    .take(SkillDefinition.SKILL_TITLE_MAX_LENGTH)
                    .trim()
                val skill = SkillDefinition(
                    skillId = "skill_" + UUID.randomUUID().toString().replace("-", ""),
                    title = resolvedTitle,
                    originalTaskText = saveData.originalTaskText,
                    createdAtMs = now,
                    updatedAtMs = now,
                )
                val template = SkillTemplateBuilder.buildTemplate(skill, session)
                SkillStore.saveSkill(skill)
                SkillStore.saveTemplate(template)
                SkillGovernor.enforceTemplateLimit(skill.skillId)
                XLog.i(TAG, "Auto-saved new skill ${skill.skillId} (${skill.title}), template=${template.templateId}")
                skill.title
            }
        } catch (e: Exception) {
            XLog.e(TAG, "Auto-save failed: ${e.message}", e)
            null
        }
    }

    /**
     * Derive a short default title from the raw task text: collapse whitespace, keep it whole when
     * short enough, otherwise cut at the last sentence (preferred) or word boundary within the limit
     * and append an ellipsis. Always <= SKILL_TITLE_MAX_LENGTH. Mirrors TaskFlowController exactly.
     */
    private fun defaultTitleFromTaskText(taskText: String): String {
        val collapsed = taskText.replace(Regex("\\s+"), " ").trim()
        if (collapsed.length <= DEFAULT_TITLE_MAX_LENGTH) return collapsed
        val window = collapsed.take(DEFAULT_TITLE_MAX_LENGTH)
        val minCut = DEFAULT_TITLE_MAX_LENGTH / 2
        val sentenceCut = window.indexOfLast { it in ".!?。！？" }
        val wordCut = window.lastIndexOf(' ')
        val cut = when {
            sentenceCut >= minCut -> sentenceCut
            wordCut >= minCut -> wordCut
            else -> window.length
        }
        return window.substring(0, cut)
            .trim()
            .trimEnd(',', '，', ';', '；', ':', '：')
            .trim() + "…"
    }
}