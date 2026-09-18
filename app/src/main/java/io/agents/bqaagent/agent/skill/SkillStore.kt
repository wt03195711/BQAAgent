// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.agent.skill

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import io.agents.bqaagent.ClawApplication
import io.agents.bqaagent.utils.XLog
import java.io.File

object SkillStore {

    private const val TAG = "SkillStore"
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    private var skillsRootDir: File? = null
    private val skills = mutableListOf<SkillDefinition>()
    private val templatesBySkill = mutableMapOf<String, MutableList<ExecutionTemplate>>()
    private val lock = Any()

    fun init() {
        skillsRootDir = File(ClawApplication.instance.filesDir, "skills").apply { mkdirs() }
        synchronized(lock) { loadAll() }
        XLog.i(TAG, "Initialized with ${getAllSkills().size} skills")
    }

    fun getAllSkills(): List<SkillDefinition> = synchronized(lock) { skills.toList() }

    fun findSkillById(skillId: String): SkillDefinition? =
        synchronized(lock) { skills.find { it.skillId == skillId } }

    fun saveSkill(skill: SkillDefinition) {
        val dir = skillDir(skill.skillId, create = true) ?: return
        try {
            File(dir, "skill.json").writeText(gson.toJson(skill))
            synchronized(lock) {
                skills.removeAll { it.skillId == skill.skillId }
                skills.add(skill)
                templatesBySkill.getOrPut(skill.skillId) { mutableListOf() }
            }
            XLog.i(TAG, "Saved skill: ${skill.skillId} (${skill.title})")
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to save skill: ${skill.skillId}", e)
            throw e
        }
    }

    fun updateSkill(skill: SkillDefinition) {
        saveSkill(skill)
    }

    fun removeSkill(skillId: String) {
        synchronized(lock) {
            skills.removeAll { it.skillId == skillId }
            templatesBySkill.remove(skillId)
        }
        val dir = skillDir(skillId, create = false) ?: return
        try {
            dir.deleteRecursively()
            XLog.i(TAG, "Removed skill: $skillId")
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to remove skill dir: $skillId", e)
        }
    }

    fun getTemplates(skillId: String): List<ExecutionTemplate> =
        synchronized(lock) { templatesBySkill[skillId]?.toList() ?: emptyList() }

    fun saveTemplate(template: ExecutionTemplate) {
        val dir = skillDir(template.skillId, create = true)?.let {
            File(it, "templates").apply { mkdirs() }
        } ?: return
        try {
            File(dir, "${template.templateId}.json").writeText(gson.toJson(template))
            synchronized(lock) {
                val list = templatesBySkill.getOrPut(template.skillId) { mutableListOf() }
                list.removeAll { it.templateId == template.templateId }
                list.add(template)
            }
            XLog.i(TAG, "Saved template: ${template.templateId} (skill=${template.skillId})")
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to save template: ${template.templateId}", e)
            throw e
        }
    }

    fun updateTemplate(template: ExecutionTemplate) {
        saveTemplate(template)
    }

    fun removeTemplate(skillId: String, templateId: String) {
        synchronized(lock) {
            templatesBySkill[skillId]?.removeAll { it.templateId == templateId }
        }
        val file = skillDir(skillId, create = false)?.let {
            File(File(it, "templates"), "$templateId.json")
        } ?: return
        try {
            file.delete()
            XLog.i(TAG, "Removed template: $templateId (skill=$skillId)")
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to remove template file: $templateId", e)
        }
    }

    private fun loadAll() {
        val root = skillsRootDir ?: return
        if (!root.exists()) return
        skills.clear()
        templatesBySkill.clear()
        root.listFiles { f -> f.isDirectory }?.forEach { dir ->
            val skillFile = File(dir, "skill.json")
            if (!skillFile.exists()) return@forEach
            try {
                val skill = gson.fromJson(skillFile.readText(), SkillDefinition::class.java)
                    ?: return@forEach
                skills.add(skill)
                val list = mutableListOf<ExecutionTemplate>()
                File(dir, "templates").listFiles { f -> f.extension == "json" }?.forEach { tmplFile ->
                    try {
                        val tmpl = gson.fromJson(tmplFile.readText(), ExecutionTemplate::class.java)
                        if (tmpl != null) list.add(tmpl)
                    } catch (e: Exception) {
                        XLog.w(TAG, "Failed to load template from ${tmplFile.name}: ${e.message}")
                    }
                }
                templatesBySkill[skill.skillId] = list
            } catch (e: Exception) {
                XLog.w(TAG, "Failed to load skill from ${dir.name}: ${e.message}")
            }
        }
    }

    private fun skillDir(skillId: String, create: Boolean): File? {
        val root = skillsRootDir ?: return null
        return File(root, skillId).apply { if (create) mkdirs() }
    }
}
