package io.agents.bqaagent.agent.llm

import com.google.gson.annotations.SerializedName

data class SavedModelProfile(
    @SerializedName("id") val id: String,
    @SerializedName("title") val title: String,
    @SerializedName("provider") val providerName: String,
    @SerializedName("model_name") val modelName: String,
    @SerializedName("base_url") val baseUrl: String,
    @SerializedName("api_key") val apiKey: String,
    @SerializedName("created_at") val createdAt: Long = System.currentTimeMillis()
)

object SavedProfileLimits {
    const val MAX_LLM_PROFILES = 5
    const val MAX_VLM_PROFILES = 5
}