// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.ui.settings

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import io.agents.bqaagent.R
import io.agents.bqaagent.agent.skill.ExecutionTemplate
import io.agents.bqaagent.agent.skill.SkillDefinition

sealed class SkillManagerRow {
    data class SkillHeader(
        val skill: SkillDefinition,
        val templateCount: Int,
        val expanded: Boolean
    ) : SkillManagerRow()

    data class TemplateItem(
        val skill: SkillDefinition,
        val template: ExecutionTemplate,
        val envMatched: Boolean
    ) : SkillManagerRow()
}

internal fun ExecutionTemplate.displayLabel(): String {
    return templateId
}

internal fun ExecutionTemplate.envSummary(): String {
    val parts = mutableListOf<String>()
    if (environment.appVersions.isNotEmpty()) {
        parts.add(environment.appVersions.entries.joinToString(", ") { (pkg, version) -> "$pkg v$version" })
    }
    if (environment.screenWidth > 0 && environment.screenHeight > 0) {
        parts.add("${environment.screenWidth}x${environment.screenHeight}")
    }
    val device = listOf(environment.brand, environment.model)
        .filter { it.isNotBlank() }.joinToString(" ")
    if (device.isNotBlank()) parts.add(device)
    val localeTag = if (environment.country.isNotBlank()) {
        "${environment.language}-${environment.country}"
    } else {
        "${environment.language.ifBlank { "?" }} (region unknown)"
    }
    parts.add(localeTag)
    parts.add("font %.2fx".format(environment.fontScale))
    return parts.joinToString(" · ").ifBlank { "Unknown environment" }
}

internal fun ExecutionTemplate.successRateLabel(): String {
    if (executionCount <= 0) return "Not run yet"
    val rate = successCount * 100 / executionCount
    return "$successCount/$executionCount ($rate%)"
}

class SkillManagerAdapter(
    private val onToggleSkill: (skillId: String) -> Unit,
    private val onEditSkill: (skill: SkillDefinition) -> Unit,
    private val onDeleteSkill: (skill: SkillDefinition) -> Unit,
    private val onDeleteTemplate: (skill: SkillDefinition, template: ExecutionTemplate) -> Unit,
    private val onViewSkillDetail: (skill: SkillDefinition) -> Unit,
    private val onViewTemplateDetail: (skill: SkillDefinition, template: ExecutionTemplate) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private companion object {
        const val TYPE_SKILL = 0
        const val TYPE_TEMPLATE = 1
    }

    private val rows = mutableListOf<SkillManagerRow>()

    fun submit(newRows: List<SkillManagerRow>) {
        rows.clear()
        rows.addAll(newRows)
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int =
        if (rows[position] is SkillManagerRow.SkillHeader) TYPE_SKILL else TYPE_TEMPLATE

    override fun getItemCount(): Int = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_SKILL) {
            SkillViewHolder(inflater.inflate(R.layout.item_skill_row, parent, false))
        } else {
            TemplateViewHolder(inflater.inflate(R.layout.item_template_row, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is SkillManagerRow.SkillHeader -> (holder as SkillViewHolder).bind(row)
            is SkillManagerRow.TemplateItem -> (holder as TemplateViewHolder).bind(row)
        }
    }

    private inner class SkillViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val ivChevron: ImageView = view.findViewById(R.id.ivChevron)
        private val tvTitle: TextView = view.findViewById(R.id.tvSkillTitle)
        private val tvCount: TextView = view.findViewById(R.id.tvTemplateCount)
        private val btnViewDetail: ImageView = view.findViewById(R.id.btnViewSkillDetail)
        private val btnEdit: ImageView = view.findViewById(R.id.btnEditSkill)
        private val btnDelete: ImageView = view.findViewById(R.id.btnDeleteSkill)

        fun bind(row: SkillManagerRow.SkillHeader) {
            tvTitle.text = row.skill.title
            tvCount.text = when (row.templateCount) {
                0 -> "No templates"
                1 -> "1 template"
                else -> "${row.templateCount} templates"
            }
            ivChevron.rotation = if (row.expanded) 90f else 0f
            itemView.setOnClickListener { onToggleSkill(row.skill.skillId) }
            btnViewDetail.setOnClickListener { onViewSkillDetail(row.skill) }
            btnEdit.setOnClickListener { onEditSkill(row.skill) }
            btnDelete.setOnClickListener { onDeleteSkill(row.skill) }
        }
    }

    private inner class TemplateViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvBadge: TextView = view.findViewById(R.id.tvTemplateBadge)
        private val tvLabel: TextView = view.findViewById(R.id.tvTemplateLabel)
        private val tvRate: TextView = view.findViewById(R.id.tvSuccessRate)
        private val tvEnv: TextView = view.findViewById(R.id.tvTemplateEnv)
        private val btnViewDetail: ImageView = view.findViewById(R.id.btnViewTemplateDetail)
        private val btnDelete: ImageView = view.findViewById(R.id.btnDeleteTemplate)

        fun bind(row: SkillManagerRow.TemplateItem) {
            val template = row.template
            tvLabel.text = template.displayLabel()
            tvRate.text = template.successRateLabel()
            tvEnv.text = template.envSummary()

            val (text, color) = when {
                !template.isAvailable -> "Disabled" to Color.parseColor("#9E9E9E")
                !row.envMatched -> "Env mismatch" to Color.parseColor("#B26A00")
                else -> "Available" to Color.parseColor("#1E8E3E")
            }
            tvBadge.text = text
            tvBadge.setTextColor(color)
            tvBadge.background = GradientDrawable().apply {
                cornerRadius = 8f * itemView.resources.displayMetrics.density
                setColor(withAlpha(color, 0.15f))
            }
            btnViewDetail.setOnClickListener { onViewTemplateDetail(row.skill, template) }
            btnDelete.setOnClickListener { onDeleteTemplate(row.skill, template) }
        }

        private fun withAlpha(color: Int, factor: Float): Int {
            val alpha = (Color.alpha(color) * factor).toInt()
            return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
        }
    }
}