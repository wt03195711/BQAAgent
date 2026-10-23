package io.agents.bqaagent.agent

import com.google.gson.annotations.SerializedName

data class SavedVlmProfile(
    @SerializedName("id") val id: String,
    @SerializedName("title") val title: String,
    @SerializedName("api_key") val apiKey: String,
    @SerializedName("base_url") val baseUrl: String,
    @SerializedName("model_name") val modelName: String,
    @SerializedName("created_at") val createdAt: Long = System.currentTimeMillis()
)