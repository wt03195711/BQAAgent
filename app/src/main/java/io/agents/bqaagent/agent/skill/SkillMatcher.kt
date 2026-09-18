package io.agents.bqaagent.agent.skill

import android.os.Build
import io.agents.bqaagent.ClawApplication
import io.agents.bqaagent.utils.XLog

object SkillMatcher {

    private const val TAG = "SkillMatcher"

    private const val SORT_WEIGHT_SUCCESS = 0.6
    private const val SORT_WEIGHT_RECENT = 0.4

    fun match(taskText: String, analysis: SkillAnalysis?): SkillMatchResult {
        val allSkills = SkillStore.getAllSkills()
        if (allSkills.isEmpty()) {
            XLog.d(TAG, "No saved skills, returning NoMatch")
            return SkillMatchResult.NoMatch
        }

        // Level 0: verbatim task-text equality. Identical text implies identical
        // parameters, so replay runs on recorded defaults (no contract check).
        val level0 = exactMatch(taskText, allSkills)
        if (level0 != null) {
            XLog.i(TAG, "Level 0 exact match: ${level0.skillId}")
            return selectTemplate(level0, emptyMap(), preferredTemplateId = null, requireFullContract = false)
        }

        val matchedId = analysis?.matchedSkillId
        if (matchedId.isNullOrBlank()) {
            XLog.d(TAG, "LLM reported no matching skill, returning NoMatch")
            return SkillMatchResult.NoMatch
        }

        // Gate 1: matched_skill_id must reference a real stored skill
        val skill = SkillStore.findSkillById(matchedId)
        if (skill == null) {
            XLog.w(TAG, "Gate 1 rejected: skill id '$matchedId' not found in store")
            return SkillMatchResult.NoMatch
        }

        // Gate 3: every extracted value must be a verbatim substring of the task text
        val rawParams = analysis.extractedParams
        val extractedParams = rawParams.filter { (name, value) ->
            name.isNotBlank() && value.isNotBlank() && taskText.contains(value)
        }
        val rejectedCount = rawParams.size - extractedParams.size
        if (rejectedCount > 0) {
            XLog.w(TAG, "Gate 3 dropped $rejectedCount parameter value(s) not present in task text")
        }

        // Gate 2 (template attribution) and Gate 4 (parameter contract) are
        // enforced inside selectTemplate against the concrete candidate.
        return selectTemplate(skill, extractedParams, analysis.matchedTemplateId, requireFullContract = true)
    }

    private fun exactMatch(taskText: String, skills: List<SkillDefinition>): SkillDefinition? {
        return skills.find { it.originalTaskText == taskText }
    }

    /**
     * Three-branch environment-converged selection:
     *  - no template in the current environment -> SkillOnly (new template path);
     *  - candidate unavailable -> SkillOnly with degradedTemplateId (re-record revives it);
     *  - candidate available and contract passes -> FullMatch.
     * [preferredTemplateId] is the LLM's choice; it is honoured only when it
     * belongs to the current-environment set, otherwise the ranked head is used
     * (compatibility path for the abnormal multi-template state).
     */
    private fun selectTemplate(
        skill: SkillDefinition,
        extractedParams: Map<String, String>,
        preferredTemplateId: String?,
        requireFullContract: Boolean
    ): SkillMatchResult {
        val envTemplates = SkillStore.getTemplates(skill.skillId)
            .filter { matchesCurrentEnvironment(it) }
        if (envTemplates.isEmpty()) {
            XLog.i(TAG, "Skill ${skill.skillId} has no template in current environment, returning SkillOnly")
            return SkillMatchResult.SkillOnly(skill)
        }

        val candidate = envTemplates.firstOrNull { it.templateId == preferredTemplateId }
            ?: envTemplates.sortedWith(rankComparator(extractedParams)).first()
        if (preferredTemplateId != null && candidate.templateId != preferredTemplateId) {
            XLog.w(TAG, "Gate 2 rejected LLM template '$preferredTemplateId' (not in current environment " +
                    "or not owned by skill ${skill.skillId}), falling back to ranked head ${candidate.templateId}")
        }

        if (!candidate.isAvailable) {
            XLog.i(TAG, "Env template ${candidate.templateId} is unavailable, returning SkillOnly for re-record")
            return SkillMatchResult.SkillOnly(skill, degradedTemplateId = candidate.templateId)
        }

        val aligned = if (requireFullContract) alignParamContract(candidate, extractedParams) else extractedParams
        if (aligned == null) {
            XLog.w(TAG, "Gate 4 rejected: parameter contract mismatch against template ${candidate.templateId}, " +
                    "degrading instead of silently replaying stale values")
            return SkillMatchResult.SkillOnly(skill, degradedTemplateId = candidate.templateId)
        }

        XLog.i(TAG, "Selected template ${candidate.templateId} for skill ${skill.skillId}, params=$aligned")
        return SkillMatchResult.FullMatch(skill, candidate, aligned)
    }

    /**
     * Gate 4 — parameter contract completeness:
     *  - every LLM parameter must align to a recorded parameter by name, or be
     *    remapped when its value equals a recorded value;
     *  - every recorded parameter must be covered by the LLM output, otherwise
     *    replay would silently reuse a stale value.
     * Returns null when the contract cannot be satisfied.
     */
    private fun alignParamContract(template: ExecutionTemplate, extractedParams: Map<String, String>): Map<String, String>? {
        val aligned = mutableMapOf<String, String>()
        for ((name, value) in extractedParams) {
            val direct = template.keyValues.firstOrNull { it.name == name }
            if (direct != null) {
                aligned[direct.name] = value
                continue
            }
            val byValue = template.keyValues.firstOrNull { it.value == value && !aligned.containsKey(it.name) }
            if (byValue != null) {
                XLog.d(TAG, "Gate 4 remapped parameter '$name' -> '${byValue.name}' by recorded value")
                aligned[byValue.name] = value
                continue
            }
            XLog.w(TAG, "Gate 4: parameter '$name' aligns to no recorded parameter of template ${template.templateId}")
            return null
        }
        val missing = template.keyValues.firstOrNull { !aligned.containsKey(it.name) }
        if (missing != null) {
            XLog.w(TAG, "Gate 4: recorded parameter '${missing.name}' missing from LLM output")
            return null
        }
        return aligned
    }

    /**
     * Multi-template compatibility ranking: parameter overlap first, then
     * success rate x 0.6 + recency x 0.4. Under the healthy lifecycle each
     * skill holds at most one current-environment template, so this only
     * matters in the abnormal multi-template state.
     */
    private fun rankComparator(extractedParams: Map<String, String>): Comparator<ExecutionTemplate> {
        return compareByDescending<ExecutionTemplate> { tmpl ->
            tmpl.keyValues.count { kv -> extractedParams[kv.name] == kv.value }
        }.thenByDescending { tmpl ->
            val total = tmpl.executionCount
            val successRate = if (total > 0) tmpl.successCount.toDouble() / total else 0.5
            val recency = if (tmpl.lastUsedMs > 0) {
                1.0 / (1.0 + (System.currentTimeMillis() - tmpl.lastUsedMs) / (24.0 * 3600 * 1000))
            } else 0.0
            successRate * SORT_WEIGHT_SUCCESS + recency * SORT_WEIGHT_RECENT
        }
    }

    /**
     * Environment-converged head template, shared by replay routing
     * (selectTemplate) and LLM prompt assembly (SkillAnalyzer) so both sides
     * always talk about the same template for a skill.
     */
    fun selectEnvHeadTemplate(skillId: String): ExecutionTemplate? {
        val envTemplates = SkillStore.getTemplates(skillId)
            .filter { matchesCurrentEnvironment(it) }
        return envTemplates.sortedWith(rankComparator(emptyMap())).firstOrNull()
    }

    /**
     * Environment equality: all device fields (including locale region and
     * font scale) must match exactly, and EVERY app version recorded with the
     * template must match the current installed version (uninstalled apps
     * resolve to 0 and therefore never match).
     */
    fun environmentMatches(template: ExecutionTemplate, env: EnvironmentInfo): Boolean {
        val t = template.environment
        if (t.language != env.language ||
            t.country != env.country ||
            t.screenWidth != env.screenWidth ||
            t.screenHeight != env.screenHeight ||
            t.brand != env.brand ||
            t.model != env.model ||
            Math.abs(t.fontScale - env.fontScale) > 0.001f
        ) return false
        return t.appVersions.all { (pkg, version) -> env.appVersions[pkg] == version }
    }

    fun matchesCurrentEnvironment(template: ExecutionTemplate): Boolean {
        return environmentMatches(template, buildCurrentEnvironment(template.environment.appVersions.keys))
    }

    fun buildCurrentEnvironment(packages: Collection<String>): EnvironmentInfo {
        val ctx = ClawApplication.instance
        val metrics = ctx.resources.displayMetrics
        val locale = java.util.Locale.getDefault()
        return EnvironmentInfo(
            appVersions = packages.filter { it.isNotBlank() }.distinct()
                .associateWith { resolveAppVersionCode(it) },
            language = locale.language,
            country = locale.country,
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels,
            brand = Build.BRAND,
            model = Build.MODEL,
            fontScale = ctx.resources.configuration.fontScale
        )
    }

    fun resolveAppVersionCode(packageName: String): Int {
        if (packageName.isBlank()) return 0
        return try {
            val info = ClawApplication.instance.packageManager.getPackageInfo(packageName, 0)
            if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt()
            else info.versionCode
        } catch (e: Exception) {
            0
        }
    }
}