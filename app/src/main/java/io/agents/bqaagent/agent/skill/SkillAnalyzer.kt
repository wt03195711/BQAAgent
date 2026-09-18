package io.agents.bqaagent.agent.skill

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage
import io.agents.bqaagent.agent.AgentConfig
import io.agents.bqaagent.agent.LlmProvider
import io.agents.bqaagent.agent.llm.LlmClientFactory
import io.agents.bqaagent.agent.llm.ModelConfigRepository
import io.agents.bqaagent.utils.XLog

object SkillAnalyzer {
    private const val TAG = "SkillAnalyzer"
    private val gson = Gson()

    private data class MatchedParam(
        val name: String = "",
        val value: String = ""
    )

    private data class AnalyzerOutput(
        @SerializedName("matched_skill_id") val matchedSkillId: String? = null,
        @SerializedName("matched_template_id") val matchedTemplateId: String? = null,
        val parameters: List<MatchedParam>? = null
    )

    fun analyze(taskText: String): SkillAnalysis {
        if (SkillStore.getAllSkills().isEmpty()) {
            XLog.i(TAG, "No saved skills, skipping LLM matching")
            return SkillAnalysis()
        }
        val config = resolveCloudConfig()
        if (config == null) {
            XLog.i(TAG, "No cloud LLM configured, skipping skill matching")
            return SkillAnalysis()
        }

        val client = LlmClientFactory.create(config)
        try {
            val prompt = buildPrompt()
            val messages = listOf(
                SystemMessage.from(prompt),
                UserMessage.from(taskText)
            )
            val response = client.chat(messages, emptyList())
            val rawText = response.text?.trim() ?: return SkillAnalysis()
            val fence = "`".repeat(3)
            val jsonText = rawText
                .removePrefix(fence + "json")
                .removePrefix(fence)
                .removeSuffix(fence)
                .trim()
            val output = gson.fromJson(jsonText, AnalyzerOutput::class.java)
                ?: return SkillAnalysis()

            val params = (output.parameters ?: emptyList())
                .filter { it.name.isNotBlank() && it.value.isNotBlank() }
                .associate { it.name to it.value }
            XLog.i(TAG, "LLM matching: skill=${output.matchedSkillId ?: "(none)"}, " +
                    "template=${output.matchedTemplateId ?: "(none)"}, params=$params")
            return SkillAnalysis(
                matchedSkillId = output.matchedSkillId?.takeIf { it.isNotBlank() },
                matchedTemplateId = output.matchedTemplateId?.takeIf { it.isNotBlank() },
                extractedParams = params
            )
        } catch (e: Exception) {
            XLog.w(TAG, "Skill matching failed: ${e.message}")
            return SkillAnalysis()
        } finally {
            client.close()
        }
    }

    private fun resolveCloudConfig(): AgentConfig? {
        val snapshot = ModelConfigRepository.snapshot()
        if (snapshot.isLocalActive()) {
            val cloud = snapshot.activeCloud
            if (!cloud.isConfigured) return null
            return AgentConfig(
                apiKey = cloud.apiKey,
                baseUrl = cloud.resolvedBaseUrl,
                modelName = cloud.modelName,
                provider = cloud.agentProvider,
                temperature = 0.1,
                maxIterations = 1
            )
        }
        val config = snapshot.toAgentConfig(temperature = 0.1, maxIterations = 1)
        return if (config.provider == LlmProvider.LOCAL) null else config
    }

    /**
     * Every saved skill is listed with its environment-converged head template
     * (the same selection replay routing would use). The recorded original task
     * text is the semantic evidence; only an AVAILABLE template id is offered
     * for replay, skills without one can still match and take the re-record
     * path afterwards.
     */
    private fun buildPrompt(): String {
        return buildString {
            appendLine("You are a strict task matcher for an Android automation assistant.")
            appendLine("Compare the user's new task against the saved skills below and decide whether ONE of them performs EXACTLY the same task.")
            appendLine()
            appendLine("## SAVED SKILLS (format: skillId | templateId | recorded task text | recorded parameters)")
            for (skill in SkillStore.getAllSkills()) {
                val head = SkillMatcher.selectEnvHeadTemplate(skill.skillId)?.takeIf { it.isAvailable }
                val templateId = head?.templateId ?: "none"
                val params = head?.keyValues
                    ?.joinToString(", ") { kv ->
                        if (kv.purpose.isNotBlank()) "${kv.name}=${kv.value} (purpose: ${kv.purpose})"
                        else "${kv.name}=${kv.value}"
                    }
                    ?.ifBlank { "(none)" } ?: "(none)"
                appendLine("- ${skill.skillId} | $templateId | ${skill.originalTaskText} | params: $params")
            }
            appendLine()
            appendLine("## RULES")
            appendLine("1. Match ONLY when the new task is semantically the SAME action as a recorded task text: same purpose, same workflow, same target apps. Concrete values (amounts, names, contacts) may differ. Any difference in purpose or workflow means NO match.")
            appendLine("2. Any uncertainty must be treated as NO match. It is always better to report no match than to use a wrong skill.")
            appendLine("3. \"matched_skill_id\" and \"matched_template_id\" must be copied verbatim from the list above. Set \"matched_template_id\" only when that skill lists a real template id (not \"none\").")
            appendLine("4. When matched, \"parameters\" must contain one entry for EVERY recorded parameter of that template, copying each recorded parameter \"name\" exactly as shown above. The new value is taken from the user's task text, or the recorded value is repeated when it is unchanged. Every \"value\" MUST be an exact substring of the user's task text — never invent or translate values. If you cannot locate a value for any recorded parameter, output NO match.")
            appendLine("5. When no skill matches, set matched_skill_id and matched_template_id to null and parameters to [].")
            appendLine("6. Output ONLY valid JSON, no markdown fences, no explanation.")
            appendLine()
            appendLine("## OUTPUT FORMAT")
            appendLine("""{"matched_skill_id":"string or null","matched_template_id":"string or null","parameters":[{"name":"string","value":"string"}]}""")
        }
    }
}