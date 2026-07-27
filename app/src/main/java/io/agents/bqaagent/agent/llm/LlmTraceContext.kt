package io.agents.bqaagent.agent.llm

import java.security.MessageDigest

object LlmTraceContext {
    data class Trace(
        val conversationId: String,
        val turnId: String,
        val turnSource: String,
        val userPromptHash: String,
    )

    private val currentTrace = ThreadLocal<Trace?>()

    fun current(): Trace? = currentTrace.get()

    fun set(trace: Trace) {
        currentTrace.set(trace)
    }

    fun clear() {
        currentTrace.remove()
    }

    fun newTurnId(source: String, userPrompt: String): String {
        return "$source-${System.currentTimeMillis()}-${promptHash(userPrompt).take(12)}"
    }

    fun promptHash(userPrompt: String): String = sha256(userPrompt)

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
