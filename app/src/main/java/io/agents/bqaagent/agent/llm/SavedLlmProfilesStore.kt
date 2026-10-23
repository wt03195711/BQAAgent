package io.agents.bqaagent.agent.llm

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.agents.bqaagent.utils.KVUtils
import java.util.UUID

object SavedLlmProfilesStore {

    private val gson = Gson()

    fun listProfiles(): List<SavedModelProfile> {
        val json = KVUtils.getLlmProfilesJson()
        return try {
            val type = object : TypeToken<List<SavedModelProfile>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveProfile(profile: SavedModelProfile): Boolean {
        val profiles = listProfiles().toMutableList()
        if (profiles.size >= SavedProfileLimits.MAX_LLM_PROFILES) return false
        profiles.add(profile)
        KVUtils.setLlmProfilesJson(gson.toJson(profiles))
        return true
    }

    fun updateProfile(profile: SavedModelProfile): Boolean {
        val profiles = listProfiles().toMutableList()
        val index = profiles.indexOfFirst { it.id == profile.id }
        if (index == -1) return false
        profiles[index] = profile
        KVUtils.setLlmProfilesJson(gson.toJson(profiles))
        return true
    }

    fun deleteProfile(profileId: String): Boolean {
        val profiles = listProfiles().toMutableList()
        val removed = profiles.removeAll { it.id == profileId }
        if (removed) {
            KVUtils.setLlmProfilesJson(gson.toJson(profiles))
            if (KVUtils.getActiveLlmProfileId() == profileId) {
                disableActiveProfile()
            }
        }
        return removed
    }

    /**
     * Activates a saved profile: writes its values to the legacy KV keys
     * (so all downstream code works unchanged) and marks it as active.
     */
    fun activateProfile(profileId: String): Boolean {
        val profile = listProfiles().find { it.id == profileId } ?: return false
        KVUtils.setActiveLlmProfileId(profileId)
        ModelConfigRepository.saveCloudDefault(
            providerName = profile.providerName,
            modelId = profile.modelName,
            baseUrl = profile.baseUrl,
            apiKey = profile.apiKey,
            activateNow = true
        )
        return true
    }

    /** Alias with clearer intent for the new enable/disable UX. */
    fun enableProfile(profileId: String): Boolean = activateProfile(profileId)

    /**
     * Disables the currently-enabled profile: clears runtime cloud config and
     * resets the active profile id. Default Models cloud becomes "not configured".
     */
    fun disableActiveProfile() {
        KVUtils.setActiveLlmProfileId("")
        ModelConfigRepository.clearCloudConfig()
    }

    /**
     * Re-applies the given profile to runtime config if it is the active one.
     * Called after editing the enabled profile so changes take effect immediately.
     */
    fun refreshActiveIfMatching(profileId: String) {
        if (getActiveProfileId() == profileId) {
            activateProfile(profileId)
        }
    }

    fun getActiveProfileId(): String = KVUtils.getActiveLlmProfileId()

    fun generateId(): String = UUID.randomUUID().toString()

    fun migrateFromCurrentIfNeeded() {
        if (listProfiles().isNotEmpty()) return
        val snapshot = ModelConfigRepository.snapshot()
        val cloud = snapshot.activeCloud
        if (!cloud.isConfigured) return
        val profile = SavedModelProfile(
            id = generateId(),
            title = "${cloud.provider} - ${cloud.modelName}",
            providerName = cloud.providerName,
            modelName = cloud.modelName,
            baseUrl = cloud.baseUrl,
            apiKey = cloud.apiKey
        )
        saveProfile(profile)
        KVUtils.setActiveLlmProfileId(profile.id)
    }
}