// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.agent.llm

import io.agents.bqaagent.agent.AgentConfig
import io.agents.bqaagent.agent.CloudProvider
import io.agents.bqaagent.agent.CustomCloudDefaults
import io.agents.bqaagent.agent.LlmProvider
import io.agents.bqaagent.utils.KVUtils
import java.io.File

enum class ActiveModelMode { LOCAL, CLOUD }

data class LocalModelConfig(
    val modelPath: String,
    val modelId: String,
    val displayName: String,
    val backendPreference: String
) {
    val isConfigured: Boolean get() = modelPath.isNotBlank()
}

data class CloudModelConfig(
    val providerName: String,
    val modelName: String,
    val baseUrl: String,
    val apiKey: String
) {
    val provider: CloudProvider get() = CloudProvider.fromName(providerName)
    val resolvedBaseUrl: String
        get() = baseUrl.ifBlank {
            if (provider == CloudProvider.CUSTOM) {
                CustomCloudDefaults.baseUrl()
            } else {
                provider.defaultBaseUrl
            }
        }
    val isConfigured: Boolean get() = modelName.isNotBlank() && apiKey.isNotBlank()
    val agentProvider: LlmProvider
        get() = LlmProvider.OPENAI
}

data class ResolvedModelConfig(
    val activeMode: ActiveModelMode,
    val local: LocalModelConfig,
    val activeCloud: CloudModelConfig,
    val defaultCloud: CloudModelConfig
) {
    fun isLocalActive(): Boolean = activeMode == ActiveModelMode.LOCAL

    fun usesExplicitInputModes(): Boolean {
        if (!isLocalActive()) return false
        val localId = local.modelId.lowercase()
        if (localId == "gemma4-e2b" || localId == "gemma4-e4b") return true
        val modelPath = local.modelPath.lowercase()
        return LocalModelManager.AVAILABLE_MODELS.any { model ->
            modelPath.endsWith(model.fileName.lowercase()) &&
                (model.id == "gemma4-e2b" || model.id == "gemma4-e4b")
        }
    }

    fun toAgentConfig(
        temperature: Double,
        maxIterations: Int,
        streaming: Boolean = false
    ): AgentConfig {
        return if (activeMode == ActiveModelMode.LOCAL) {
            AgentConfig(
                apiKey = "",
                baseUrl = local.modelPath,
                modelName = local.modelId,
                maxIterations = maxIterations,
                temperature = temperature,
                provider = LlmProvider.LOCAL,
                streaming = streaming
            )
        } else {
            AgentConfig(
                apiKey = activeCloud.apiKey,
                baseUrl = activeCloud.resolvedBaseUrl,
                modelName = activeCloud.modelName,
                maxIterations = maxIterations,
                temperature = temperature,
                provider = activeCloud.agentProvider,
                streaming = streaming
            )
        }
    }
}

/**
 * Resolves active/default local and cloud model config from KVUtils without changing
 * the persisted key format. This is the single source of truth for model selection.
 */
object ModelConfigRepository {

    fun snapshot(): ResolvedModelConfig {
        val activeProviderRaw = KVUtils.getLlmProvider().ifBlank { "DEEPSEEK" }.uppercase()
        val activeMode = if (activeProviderRaw == "LOCAL") ActiveModelMode.LOCAL else ActiveModelMode.CLOUD

        val localModelPath = KVUtils.getLocalModelPath()
        val matchedLocalModel = LocalModelManager.AVAILABLE_MODELS.find { localModelPath.endsWith(it.fileName) }
        val localModelId = matchedLocalModel?.id
            ?: if (activeMode == ActiveModelMode.LOCAL) KVUtils.getLlmModelName() else ""
        val localDisplayName = matchedLocalModel?.displayName
            ?: localModelPath.takeIf { it.isNotBlank() }?.let { File(it).nameWithoutExtension }
            ?: localModelId

        val local = LocalModelConfig(
            modelPath = localModelPath,
            modelId = localModelId,
            displayName = localDisplayName,
            backendPreference = KVUtils.getLocalBackendPreference()
        )

        val defaultProvider = normalizeCloudProvider(
            KVUtils.getDefaultCloudProvider().ifBlank {
                if (activeMode == ActiveModelMode.CLOUD) activeProviderRaw else "DEEPSEEK"
            }
        )
        val defaultModel = KVUtils.getDefaultCloudModel().ifBlank {
            if (activeMode == ActiveModelMode.CLOUD && activeProviderRaw == defaultProvider) {
                KVUtils.getLlmModelName()
            } else {
                ""
            }
        }
        val defaultBaseUrl = KVUtils.getDefaultCloudBaseUrl().ifBlank {
            if (activeMode == ActiveModelMode.CLOUD && activeProviderRaw == defaultProvider) {
                KVUtils.getLlmBaseUrl()
            } else {
                ""
            }
        }
        val defaultCloud = buildCloudConfig(defaultProvider, defaultModel, defaultBaseUrl)

        val activeCloudProvider = if (activeMode == ActiveModelMode.CLOUD) {
            normalizeCloudProvider(activeProviderRaw)
        } else {
            defaultCloud.providerName
        }
        val sameProviderFallback = activeCloudProvider == defaultCloud.providerName
        val activeCloudModel = if (activeMode == ActiveModelMode.CLOUD) {
            KVUtils.getLlmModelName().ifBlank { if (sameProviderFallback) defaultCloud.modelName else "" }
        } else {
            defaultCloud.modelName
        }
        val activeCloudBaseUrl = if (activeMode == ActiveModelMode.CLOUD) {
            KVUtils.getLlmBaseUrl().ifBlank { if (sameProviderFallback) defaultCloud.baseUrl else "" }
        } else {
            defaultCloud.baseUrl
        }
        val activeCloud = buildCloudConfig(activeCloudProvider, activeCloudModel, activeCloudBaseUrl)

        return ResolvedModelConfig(
            activeMode = activeMode,
            local = local,
            activeCloud = activeCloud,
            defaultCloud = defaultCloud
        )
    }

    fun isLocalActive(): Boolean = snapshot().isLocalActive()

    fun activeModelUsesExplicitInputModes(): Boolean = snapshot().usesExplicitInputModes()

    fun saveLocalDefault(modelPath: String, modelId: String, activateNow: Boolean) {
        KVUtils.setLocalModelPath(modelPath)
        if (activateNow) {
            activateLocal(modelPath, modelId)
        }
    }

    fun activateLocal(modelPath: String, modelId: String) {
        KVUtils.setLocalModelPath(modelPath)
        KVUtils.setLlmProvider("LOCAL")
        KVUtils.setLlmModelName(modelId)
    }

    fun saveCloudDefault(
        providerName: String,
        modelId: String,
        baseUrl: String,
        apiKey: String,
        activateNow: Boolean
    ): Boolean {
        val normalizedProvider = normalizeCloudProvider(providerName)
        val resolvedBaseUrl = resolveCloudBaseUrl(normalizedProvider, baseUrl)
        KVUtils.setDefaultCloudModel(modelId)
        KVUtils.setDefaultCloudProvider(normalizedProvider)
        KVUtils.setDefaultCloudBaseUrl(resolvedBaseUrl)
        KVUtils.setLlmApiKey(apiKey)
        KVUtils.setApiKeyForProvider(normalizedProvider, apiKey)
        val customConfigSaved = if (normalizedProvider == CloudProvider.CUSTOM.name) {
            CustomCloudDefaults.save(
                apiKey = apiKey,
                baseUrl = resolvedBaseUrl,
                modelName = modelId
            )
        } else {
            true
        }
        if (activateNow) {
            activateCloudSelection(
                modelId = modelId,
                explicitProviderName = normalizedProvider,
                explicitBaseUrl = resolvedBaseUrl
            )
        }
        return customConfigSaved
    }

    fun activateCloudSelection(
        modelId: String,
        explicitProviderName: String? = null,
        explicitBaseUrl: String? = null
    ) {
        val snapshot = snapshot()
        val inferredProvider = explicitProviderName
            ?.takeIf { it.isNotBlank() }
            ?: CloudProvider.findProviderForModel(modelId)?.name
            ?: snapshot.defaultCloud.providerName
        val normalizedProvider = normalizeCloudProvider(inferredProvider)
        val resolvedBaseUrl = resolveCloudBaseUrl(
            normalizedProvider,
            explicitBaseUrl
                ?: if (snapshot.defaultCloud.providerName == normalizedProvider) snapshot.defaultCloud.baseUrl else ""
        )

        KVUtils.setDefaultCloudModel(modelId)
        KVUtils.setDefaultCloudProvider(normalizedProvider)
        KVUtils.setDefaultCloudBaseUrl(resolvedBaseUrl)
        KVUtils.setLlmProvider(normalizedProvider)
        KVUtils.setLlmModelName(modelId)
        KVUtils.setLlmBaseUrl(resolvedBaseUrl)
    }

    private fun buildCloudConfig(
        providerName: String,
        modelName: String,
        baseUrl: String
    ): CloudModelConfig {
        val normalizedProvider = normalizeCloudProvider(providerName)
        val provider = CloudProvider.fromName(normalizedProvider)
        val customDefaults = if (provider == CloudProvider.CUSTOM) {
            CustomCloudDefaults.current()
        } else {
            null
        }
        val apiKey = KVUtils.getApiKeyForProvider(normalizedProvider)
            .ifEmpty { KVUtils.getLlmApiKey() }
            .ifBlank { customDefaults?.apiKey.orEmpty() }
        val resolvedModelName = if (provider == CloudProvider.CUSTOM) {
            modelName.ifBlank { customDefaults?.modelName.orEmpty() }
        } else {
            val providerModels = provider.models
            val modelBelongsToProvider = providerModels.any { it.id == modelName }
            modelName
                .takeIf { it.isNotBlank() && modelBelongsToProvider }
                ?: providerModels.firstOrNull { it.recommended }?.id
                ?: providerModels.firstOrNull()?.id
                ?: ""
        }
        return CloudModelConfig(
            providerName = normalizedProvider,
            modelName = resolvedModelName,
            baseUrl = resolveCloudBaseUrl(normalizedProvider, baseUrl),
            apiKey = apiKey
        )
    }

    private fun normalizeCloudProvider(providerName: String): String {
        val normalized = providerName.ifBlank { "DEEPSEEK" }.uppercase()
        if (normalized == "LOCAL") return CloudProvider.DEEPSEEK.name
        return CloudProvider.entries.find { it.name == normalized }?.name ?: CloudProvider.DEEPSEEK.name
    }

    private fun resolveCloudBaseUrl(providerName: String, baseUrl: String): String {
        val provider = CloudProvider.fromName(providerName)
        if (provider == CloudProvider.CUSTOM) return baseUrl.trim().ifBlank { CustomCloudDefaults.baseUrl() }
        return provider.defaultBaseUrl
    }
}
