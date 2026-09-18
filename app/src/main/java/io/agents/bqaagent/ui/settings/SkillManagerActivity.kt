// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.ui.settings

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.GsonBuilder
import io.agents.bqaagent.R
import io.agents.bqaagent.agent.skill.ExecutionTemplate
import io.agents.bqaagent.agent.skill.SkillDefinition
import io.agents.bqaagent.agent.skill.SkillMatcher
import io.agents.bqaagent.agent.skill.SkillStore
import io.agents.bqaagent.base.BaseActivity
import io.agents.bqaagent.ui.chat.ThemeManager
import io.agents.bqaagent.widget.CommonToolbar
import io.agents.bqaagent.widget.ConfirmDialog
import io.agents.bqaagent.widget.InputDialog
import io.agents.bqaagent.widget.SkillDetailDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SkillManagerActivity : BaseActivity() {

    private lateinit var adapter: SkillManagerAdapter
    private val expandedSkillIds = mutableSetOf<String>()

    private val detailGson by lazy {
        GsonBuilder().setPrettyPrinting().serializeNulls().disableHtmlEscaping().create()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tc = ThemeManager.getColors()
        window.statusBarColor = tc.toolbarBg
        window.decorView.setBackgroundColor(tc.bg)
        setContentView(R.layout.activity_skill_manager)

        val contentFrame = findViewById<ViewGroup>(android.R.id.content)
        contentFrame?.setBackgroundColor(tc.bg)
        (contentFrame?.getChildAt(0) as? View)?.setBackgroundColor(tc.bg)

        findViewById<CommonToolbar>(R.id.toolbar).apply {
            setTitle("Skill Management")
            setTitleColor(tc.aiText)
            setBackgroundColor(tc.toolbarBg)
            showBackButton(true) { finish() }
            findViewById<ImageView>(R.id.ivBack)?.setColorFilter(tc.aiText)
        }

        adapter = SkillManagerAdapter(
            onToggleSkill = { skillId -> toggleSkill(skillId) },
            onEditSkill = { skill -> showEditTitleDialog(skill) },
            onDeleteSkill = { skill -> showDeleteSkillDialog(skill) },
            onDeleteTemplate = { skill, template -> showDeleteTemplateDialog(skill, template) },
            onViewSkillDetail = { skill -> showSkillDetailDialog(skill) },
            onViewTemplateDetail = { skill, template -> showTemplateDetailDialog(skill, template) }
        )
        findViewById<RecyclerView>(R.id.rvSkills).apply {
            layoutManager = LinearLayoutManager(this@SkillManagerActivity)
            adapter = this@SkillManagerActivity.adapter
        }

        loadData()
    }

    private fun toggleSkill(skillId: String) {
        if (!expandedSkillIds.remove(skillId)) {
            expandedSkillIds.add(skillId)
        }
        loadData()
    }

    private fun loadData() {
        lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) { buildRows() }
            adapter.submit(rows)
            findViewById<TextView>(R.id.tvEmpty).visibility =
                if (rows.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun buildRows(): List<SkillManagerRow> {
        val rows = mutableListOf<SkillManagerRow>()
        for (skill in SkillStore.getAllSkills().sortedByDescending { it.updatedAtMs }) {
            val templates = SkillStore.getTemplates(skill.skillId)
            val expanded = skill.skillId in expandedSkillIds
            rows.add(SkillManagerRow.SkillHeader(skill, templates.size, expanded))
            if (!expanded) continue

            for (template in templates.sortedByDescending { it.createdAtMs }) {
                rows.add(SkillManagerRow.TemplateItem(skill, template, envMatches(template)))
            }
        }
        return rows
    }

    private fun envMatches(template: ExecutionTemplate): Boolean {
        return runCatching { SkillMatcher.matchesCurrentEnvironment(template) }.getOrDefault(false)
    }

    private fun showSkillDetailDialog(skill: SkillDefinition) {
        SkillDetailDialog.show(
            context = this,
            title = "Skill Detail",
            jsonContent = detailGson.toJson(skill),
            root = detailGson.toJsonTree(skill)
        )
    }

    private fun showTemplateDetailDialog(skill: SkillDefinition, template: ExecutionTemplate) {
        SkillDetailDialog.show(
            context = this,
            title = "Template Detail",
            jsonContent = detailGson.toJson(template),
            root = detailGson.toJsonTree(template),
            arrayItemBadge = { arrayKey, index ->
                if (arrayKey == "steps") (index + 1).toString() else null
            }
        )
    }

    private fun showEditTitleDialog(skill: SkillDefinition) {
        InputDialog.show(
            context = this,
            title = "Edit Skill Title",
            presetText = skill.title,
            hint = "Enter a new title",
            minLength = 1,
            maxLength = SkillDefinition.SKILL_TITLE_MAX_LENGTH,
            confirmText = "Save"
        ) { newTitle ->
            if (newTitle != skill.title) {
                SkillStore.updateSkill(skill.copy(title = newTitle, updatedAtMs = System.currentTimeMillis()))
                Toast.makeText(this, "Skill title updated", Toast.LENGTH_SHORT).show()
                loadData()
            }
        }
    }

    private fun showDeleteSkillDialog(skill: SkillDefinition) {
        val templateCount = SkillStore.getTemplates(skill.skillId).size
        val message = buildString {
            append("Delete skill \"${skill.title}\"?")
            if (templateCount > 0) {
                append("\n\nIts $templateCount template(s) will be deleted together with it.")
            }
            append("\n\nThis action cannot be undone.")
        }
        ConfirmDialog.showWarm(
            context = this,
            title = "Delete Skill",
            message = message,
            actionTitle = "Delete",
            cancelTitle = getString(R.string.common_cancel),
            onAction = {
                SkillStore.removeSkill(skill.skillId)
                expandedSkillIds.remove(skill.skillId)
                Toast.makeText(this, "Skill deleted", Toast.LENGTH_SHORT).show()
                loadData()
            }
        )
    }

    private fun showDeleteTemplateDialog(skill: SkillDefinition, template: ExecutionTemplate) {
        ConfirmDialog.showWarm(
            context = this,
            title = "Delete Template",
            message = "Delete this template?\n\n${template.displayLabel()}\n\nThis action cannot be undone.",
            actionTitle = "Delete",
            cancelTitle = getString(R.string.common_cancel),
            onAction = {
                SkillStore.removeTemplate(skill.skillId, template.templateId)
                Toast.makeText(this, "Template deleted", Toast.LENGTH_SHORT).show()
                loadData()
            }
        )
    }
}