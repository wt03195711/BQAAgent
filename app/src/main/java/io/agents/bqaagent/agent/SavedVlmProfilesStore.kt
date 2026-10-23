package io.agents.bqaagent.agent

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.agents.bqaagent.agent.llm.SavedProfileLimits
import io.agents.bqaagent.utils.KVUtils
import java.util.UUID

object SavedVlmProfilesStore {

    private val gson = Gson()

    fun listProfiles(): List<SavedVlmProfile> {
        val json = KVUtils.getVlmProfilesJson()
        return try {
            val type = object : TypeToken<List<SavedVlmProfile>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveProfile(profile: SavedVlmProfile): Boolean {
        val profiles = listProfiles().toMutableList()
        if (profiles.size >= SavedProfileLimits.MAX_VLM_PROFILES) return false
        profiles.add(profile)
        KVUtils.setVlmProfilesJson(gson.toJson(profiles))
        return true
    }

    fun updateProfile(profile: SavedVlmProfile): Boolean {
        val profiles = listProfiles().toMutableList()
        val index = profiles.indexOfFirst { it.id == profile.id }
        if (index == -1) return false
        profiles[index] = profile
        KVUtils.setVlmProfilesJson(gson.toJson(profiles))
        return true
    }

    fun deleteProfile(profileId: String): Boolean {
        val profiles = listProfiles().toMutableList()
        val removed = profiles.removeAll { it.id == profileId }
        if (removed) {
            KVUtils.setVlmProfilesJson(gson.toJson(profiles))
            if (KVUtils.getActiveVlmProfileId() == profileId) {
                disableActiveProfile()
            }
        }
        return removed
    }

    fun activateProfile(profileId: String): Boolean {
        val profile = listProfiles().find { it.id == profileId } ?: return false
        KVUtils.setActiveVlmProfileId(profileId)
        VlmConfigRepository.save(
            apiKey = profile.apiKey,
            baseUrl = profile.baseUrl,
            modelName = profile.modelName
        )
        return true
    }

    fun enableProfile(profileId: String): Boolean = activateProfile(profileId)

    fun disableActiveProfile() {
        KVUtils.setActiveVlmProfileId("")
        VlmConfigRepository.save("", "", "")
    }

    fun refreshActiveIfMatching(profileId: String) {
        if (getActiveProfileId() == profileId) {
            activateProfile(profileId)
        }
    }

    fun getActiveProfileId(): String = KVUtils.getActiveVlmProfileId()

    fun generateId(): String = UUID.randomUUID().toString()

    fun migrateFromCurrentIfNeeded() {
        if (listProfiles().isNotEmpty()) return
        if (!VlmConfigRepository.isConfigured()) return
        val config = VlmConfigRepository.load()
        val profile = SavedVlmProfile(
            id = generateId(),
            title = config.modelName,
            apiKey = config.apiKey,
            baseUrl = config.baseUrl,
            modelName = config.modelName
        )
        saveProfile(profile)
        KVUtils.setActiveVlmProfileId(profile.id)
    }
}