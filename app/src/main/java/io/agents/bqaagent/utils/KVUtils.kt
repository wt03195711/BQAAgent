// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.utils

import android.content.Context
import com.tencent.mmkv.MMKV

/**
 * MMKV key-value storage utility
 *
 * Usage:
 *   // Initialize in Application.onCreate
 *   KVUtils.init(context)
 *
 *   // Read and write data
 *   KVUtils.putString("key", "value")
 *   val value = KVUtils.getString("key", "default")
 */
object KVUtils {

    private lateinit var mmkv: MMKV

    private const val DEFAULT_INT = 0
    private const val DEFAULT_LONG = 0L
    private const val DEFAULT_BOOL = false
    private const val DEFAULT_FLOAT = 0f
    private const val DEFAULT_DOUBLE = 0.0

    /**
     * Call to initialize in Application.onCreate
     */
    fun init(context: Context) {
        MMKV.initialize(context)
        mmkv = MMKV.defaultMMKV()
    }

    // ==================== String ====================
    fun putString(key: String, value: String?): Boolean {
        return mmkv.encode(key, value)
    }

    fun getString(key: String, defaultValue: String = ""): String {
        return mmkv.decodeString(key, defaultValue) ?: defaultValue
    }

    // ==================== Int ====================
    fun putInt(key: String, value: Int): Boolean {
        return mmkv.encode(key, value)
    }

    fun getInt(key: String, defaultValue: Int = DEFAULT_INT): Int {
        return mmkv.decodeInt(key, defaultValue)
    }

    // ==================== Long ====================
    fun putLong(key: String, value: Long): Boolean {
        return mmkv.encode(key, value)
    }

    fun getLong(key: String, defaultValue: Long = DEFAULT_LONG): Long {
        return mmkv.decodeLong(key, defaultValue)
    }

    // ==================== Boolean ====================
    fun putBoolean(key: String, value: Boolean): Boolean {
        return mmkv.encode(key, value)
    }

    fun getBoolean(key: String, defaultValue: Boolean = DEFAULT_BOOL): Boolean {
        return mmkv.decodeBool(key, defaultValue)
    }

    // ==================== Float ====================
    fun putFloat(key: String, value: Float): Boolean {
        return mmkv.encode(key, value)
    }

    fun getFloat(key: String, defaultValue: Float = DEFAULT_FLOAT): Float {
        return mmkv.decodeFloat(key, defaultValue)
    }

    // ==================== Double ====================
    fun putDouble(key: String, value: Double): Boolean {
        return mmkv.encode(key, value)
    }

    fun getDouble(key: String, defaultValue: Double = DEFAULT_DOUBLE): Double {
        return mmkv.decodeDouble(key, defaultValue)
    }

    // ==================== Bytes ====================
    fun putBytes(key: String, value: ByteArray?): Boolean {
        return mmkv.encode(key, value)
    }

    fun getBytes(key: String): ByteArray? {
        return mmkv.decodeBytes(key)
    }

    // ==================== Common Operations ====================
    fun contains(key: String): Boolean {
        return mmkv.containsKey(key)
    }

    fun remove(key: String) {
        mmkv.removeValueForKey(key)
    }

    fun remove(vararg keys: String) {
        mmkv.removeValuesForKeys(keys)
    }

    fun clear() {
        mmkv.clearAll()
    }

    fun getAllKeys(): Array<String> {
        return mmkv.allKeys() ?: emptyArray()
    }

    // ==================== Appearance ====================
    private const val KEY_APP_LANGUAGE_TAG = "KEY_APP_LANGUAGE_TAG"

    fun getAppLanguageTag(): String = getString(KEY_APP_LANGUAGE_TAG, "")

    fun setAppLanguageTag(languageTag: String) = putString(KEY_APP_LANGUAGE_TAG, languageTag)

    /**
     * Flush to disk synchronously (default is async)
     */
    fun sync() {
        mmkv.sync()
    }


    // ==================== Onboarding ====================
    private const val KEY_GUIDE_SHOWN = "KEY_GUIDE_SHOWN"

    fun isGuideShown(): Boolean = getBoolean(KEY_GUIDE_SHOWN, false)

    fun setGuideShown(shown: Boolean) = putBoolean(KEY_GUIDE_SHOWN, shown)

    // ==================== LAN Config Service ====================
    private const val KEY_CONFIG_SERVER_ENABLED = "KEY_CONFIG_SERVER_ENABLED"
    fun isConfigServerEnabled(): Boolean = getBoolean(KEY_CONFIG_SERVER_ENABLED, false)
    fun setConfigServerEnabled(enabled: Boolean) = putBoolean(KEY_CONFIG_SERVER_ENABLED, enabled)

    // ==================== External Automation ====================
    private const val KEY_EXTERNAL_AUTOMATION_ENABLED = "KEY_EXTERNAL_AUTOMATION_ENABLED"
    fun isExternalAutomationEnabled(): Boolean = getBoolean(KEY_EXTERNAL_AUTOMATION_ENABLED, false)
    fun setExternalAutomationEnabled(enabled: Boolean) = putBoolean(KEY_EXTERNAL_AUTOMATION_ENABLED, enabled)

    // ==================== Voice Input ====================
    private const val KEY_VOICE_INPUT_ENABLED = "KEY_VOICE_INPUT_ENABLED"
    fun isVoiceInputEnabled(): Boolean = getBoolean(KEY_VOICE_INPUT_ENABLED, false)
    fun setVoiceInputEnabled(enabled: Boolean) = putBoolean(KEY_VOICE_INPUT_ENABLED, enabled)

    // ==================== Sensitive Mode ====================
    private const val KEY_SENSITIVE_MODE_ENABLED = "KEY_SENSITIVE_MODE_ENABLED"
    fun isSensitiveModeEnabled(): Boolean = getBoolean(KEY_SENSITIVE_MODE_ENABLED, true)
    fun setSensitiveModeEnabled(enabled: Boolean) = putBoolean(KEY_SENSITIVE_MODE_ENABLED, enabled)

    // ==================== Skill Capture Mode ====================
    private const val KEY_SKILL_CAPTURE_MODE_ENABLED = "KEY_SKILL_CAPTURE_MODE_ENABLED"
    fun isSkillCaptureModeEnabled(): Boolean = getBoolean(KEY_SKILL_CAPTURE_MODE_ENABLED, false)
    fun setSkillCaptureModeEnabled(enabled: Boolean) = putBoolean(KEY_SKILL_CAPTURE_MODE_ENABLED, enabled)

    // ==================== LLM Skill Matching ====================
    // When off (default), skill matching is L0-only: a task replays a saved skill
    // ONLY when its text is verbatim identical to the recorded originalTaskText.
    // When on, the LLM semantic matcher (SkillAnalyzer) is additionally consulted.
    private const val KEY_LLM_SKILL_MATCHING_ENABLED = "KEY_LLM_SKILL_MATCHING_ENABLED"
    fun isLlmSkillMatchingEnabled(): Boolean = getBoolean(KEY_LLM_SKILL_MATCHING_ENABLED, false)
    fun setLlmSkillMatchingEnabled(enabled: Boolean) = putBoolean(KEY_LLM_SKILL_MATCHING_ENABLED, enabled)

    // ==================== Task Recording ====================
    // Master switch, default OFF. While off, TaskRecordingCoordinator never touches the device.
    private const val KEY_TASK_RECORDING_ENABLED = "KEY_TASK_RECORDING_ENABLED"
    // Background wrapper resolved by the in-app self-test: "setsid" / "nohup" / "none"; "" = not probed yet.
    private const val KEY_TASK_RECORDING_WRAPPER = "KEY_TASK_RECORDING_WRAPPER"
    // Whether two screenrecord instances can capture at the same time (near-seamless rotation).
    private const val KEY_TASK_RECORDING_OVERLAP = "KEY_TASK_RECORDING_OVERLAP"
    // 0 = downscale to a 720 short side, 1 = record without --size, 2 = unsupported.
    private const val KEY_TASK_RECORDING_SIZE_LEVEL = "KEY_TASK_RECORDING_SIZE_LEVEL"
    private const val KEY_TASK_RECORDING_BIT_RATE = "KEY_TASK_RECORDING_BIT_RATE"
    private const val KEY_TASK_RECORDING_MAX_TOTAL_MINUTES = "KEY_TASK_RECORDING_MAX_TOTAL_MINUTES"
    // Device the configuration above was verified on. Without this scope a wrapper cached on one
    // phone survives a backup restore onto another, and TaskRecordingCoordinator.doStart() only
    // re-probes when the cache is blank — so the foreign value would be used forever unvalidated.
    private const val KEY_TASK_RECORDING_VERIFIED_DEVICE = "KEY_TASK_RECORDING_VERIFIED_DEVICE"
    private const val KEY_TASK_RECORDING_VERIFIED_AT = "KEY_TASK_RECORDING_VERIFIED_AT"

    fun isTaskRecordingEnabled(): Boolean = getBoolean(KEY_TASK_RECORDING_ENABLED, false)
    fun setTaskRecordingEnabled(enabled: Boolean) = putBoolean(KEY_TASK_RECORDING_ENABLED, enabled)
    fun getTaskRecordingWrapper(): String = getString(KEY_TASK_RECORDING_WRAPPER, "")
    fun setTaskRecordingWrapper(value: String) = putString(KEY_TASK_RECORDING_WRAPPER, value)
    fun isTaskRecordingOverlapEnabled(): Boolean = getBoolean(KEY_TASK_RECORDING_OVERLAP, false)
    fun setTaskRecordingOverlapEnabled(value: Boolean) = putBoolean(KEY_TASK_RECORDING_OVERLAP, value)
    fun getTaskRecordingSizeLevel(): Int = getInt(KEY_TASK_RECORDING_SIZE_LEVEL, 0)
    fun setTaskRecordingSizeLevel(value: Int) = putInt(KEY_TASK_RECORDING_SIZE_LEVEL, value)
    fun getTaskRecordingBitRate(): Int = getInt(KEY_TASK_RECORDING_BIT_RATE, 2_000_000)
    fun setTaskRecordingBitRate(value: Int) = putInt(KEY_TASK_RECORDING_BIT_RATE, value)
    fun getTaskRecordingMaxTotalMinutes(): Int = getInt(KEY_TASK_RECORDING_MAX_TOTAL_MINUTES, 30)
    fun setTaskRecordingMaxTotalMinutes(value: Int) = putInt(KEY_TASK_RECORDING_MAX_TOTAL_MINUTES, value)
    fun getTaskRecordingVerifiedDevice(): String = getString(KEY_TASK_RECORDING_VERIFIED_DEVICE, "")
    fun setTaskRecordingVerifiedDevice(value: String) = putString(KEY_TASK_RECORDING_VERIFIED_DEVICE, value)
    fun getTaskRecordingVerifiedAt(): Long = getLong(KEY_TASK_RECORDING_VERIFIED_AT, 0L)
    fun setTaskRecordingVerifiedAt(value: Long) = putLong(KEY_TASK_RECORDING_VERIFIED_AT, value)
    fun clearTaskRecordingVerification() =
        remove(KEY_TASK_RECORDING_VERIFIED_DEVICE, KEY_TASK_RECORDING_VERIFIED_AT)

    // ==================== Local ADB Automation ===================
    private const val KEY_LOCAL_ADB_HOST = "KEY_LOCAL_ADB_HOST"
    private const val KEY_LOCAL_ADB_PORT = "KEY_LOCAL_ADB_PORT"
    private const val KEY_LOCAL_ADB_CERT = "KEY_LOCAL_ADB_CERT"
    private const val KEY_LOCAL_ADB_PRIVATE_KEY = "KEY_LOCAL_ADB_PRIVATE_KEY"
    private const val KEY_LOCAL_ADB_PAIRED = "KEY_LOCAL_ADB_PAIRED"
    private const val KEY_LOCAL_ADB_PAIR_HOST = "KEY_LOCAL_ADB_PAIR_HOST"

    fun getLocalAdbHost(): String = getString(KEY_LOCAL_ADB_HOST, "127.0.0.1")
    fun setLocalAdbHost(value: String) = putString(KEY_LOCAL_ADB_HOST, value)
    fun getLocalAdbPort(): Int = getInt(KEY_LOCAL_ADB_PORT, 0)
    fun setLocalAdbPort(value: Int) = putInt(KEY_LOCAL_ADB_PORT, value)
    fun getLocalAdbCert(): ByteArray? = getBytes(KEY_LOCAL_ADB_CERT)
    fun setLocalAdbCert(value: ByteArray) = putBytes(KEY_LOCAL_ADB_CERT, value)
    fun getLocalAdbPrivateKey(): ByteArray? = getBytes(KEY_LOCAL_ADB_PRIVATE_KEY)
    fun setLocalAdbPrivateKey(value: ByteArray) = putBytes(KEY_LOCAL_ADB_PRIVATE_KEY, value)
    fun isLocalAdbPaired(): Boolean = getBoolean(KEY_LOCAL_ADB_PAIRED, false)
    fun setLocalAdbPaired(value: Boolean) = putBoolean(KEY_LOCAL_ADB_PAIRED, value)
    fun getLocalAdbPairHost(): String = getString(KEY_LOCAL_ADB_PAIR_HOST, "")
    fun setLocalAdbPairHost(value: String) = putString(KEY_LOCAL_ADB_PAIR_HOST, value)

    private const val KEY_PENDING_NOTIFICATION_ACCESS_RETURN = "KEY_PENDING_NOTIFICATION_ACCESS_RETURN"
    private const val KEY_PENDING_NOTIFICATION_ACCESS_RETURN_AT = "KEY_PENDING_NOTIFICATION_ACCESS_RETURN_AT"
    private const val KEY_NOTIFICATION_LISTENER_LAST_CONNECTED_AT = "KEY_NOTIFICATION_LISTENER_LAST_CONNECTED_AT"
    private const val KEY_NOTIFICATION_LISTENER_LAST_DISCONNECTED_AT = "KEY_NOTIFICATION_LISTENER_LAST_DISCONNECTED_AT"

    fun markPendingNotificationAccessReturn() {
        putBoolean(KEY_PENDING_NOTIFICATION_ACCESS_RETURN, true)
        putLong(KEY_PENDING_NOTIFICATION_ACCESS_RETURN_AT, System.currentTimeMillis())
    }

    fun hasPendingNotificationAccessReturn(maxAgeMs: Long = 120_000L): Boolean {
        val pending = getBoolean(KEY_PENDING_NOTIFICATION_ACCESS_RETURN, false)
        val requestedAt = getLong(KEY_PENDING_NOTIFICATION_ACCESS_RETURN_AT, 0L)
        if (!pending || requestedAt <= 0L) return false
        return System.currentTimeMillis() - requestedAt <= maxAgeMs
    }

    fun consumePendingNotificationAccessReturn(maxAgeMs: Long = 120_000L): Boolean {
        val pending = getBoolean(KEY_PENDING_NOTIFICATION_ACCESS_RETURN, false)
        val requestedAt = getLong(KEY_PENDING_NOTIFICATION_ACCESS_RETURN_AT, 0L)
        clearPendingNotificationAccessReturn()
        if (!pending || requestedAt <= 0L) return false
        return System.currentTimeMillis() - requestedAt <= maxAgeMs
    }

    fun clearPendingNotificationAccessReturn() {
        putBoolean(KEY_PENDING_NOTIFICATION_ACCESS_RETURN, false)
        putLong(KEY_PENDING_NOTIFICATION_ACCESS_RETURN_AT, 0L)
    }

    fun noteNotificationListenerConnected() {
        putLong(KEY_NOTIFICATION_LISTENER_LAST_CONNECTED_AT, System.currentTimeMillis())
    }

    fun noteNotificationListenerDisconnected() {
        putLong(KEY_NOTIFICATION_LISTENER_LAST_DISCONNECTED_AT, System.currentTimeMillis())
    }

    fun getNotificationListenerLastConnectedAt(): Long =
        getLong(KEY_NOTIFICATION_LISTENER_LAST_CONNECTED_AT, 0L)

    fun getNotificationListenerLastDisconnectedAt(): Long =
        getLong(KEY_NOTIFICATION_LISTENER_LAST_DISCONNECTED_AT, 0L)

    private const val KEY_LLM_API_KEY = "KEY_LLM_API_KEY"
    private const val KEY_LLM_BASE_URL = "KEY_LLM_BASE_URL"
    private const val KEY_LLM_MODEL_NAME = "KEY_LLM_MODEL_NAME"
    private const val KEY_LLM_PROVIDER = "KEY_LLM_PROVIDER"
    private const val KEY_LOCAL_MODEL_PATH = "KEY_LOCAL_MODEL_PATH"
    private const val KEY_LOCAL_BACKEND_PREFERENCE = "KEY_LOCAL_BACKEND_PREFERENCE"
    private const val KEY_LOCAL_CPU_SAFE_DEVICE = "KEY_LOCAL_CPU_SAFE_DEVICE"
    private const val KEY_LOCAL_CPU_SAFE_REASON = "KEY_LOCAL_CPU_SAFE_REASON"
    private const val KEY_LOCAL_CPU_SAFE_AT = "KEY_LOCAL_CPU_SAFE_AT"
    private const val KEY_LOCAL_GPU_VERIFIED_DEVICE = "KEY_LOCAL_GPU_VERIFIED_DEVICE"
    private const val KEY_LOCAL_GPU_VERIFIED_AT = "KEY_LOCAL_GPU_VERIFIED_AT"
    private const val KEY_PENDING_LOCAL_GPU_INIT_DEVICE = "KEY_PENDING_LOCAL_GPU_INIT_DEVICE"
    private const val KEY_PENDING_LOCAL_GPU_INIT_MODEL = "KEY_PENDING_LOCAL_GPU_INIT_MODEL"
    private const val KEY_PENDING_LOCAL_GPU_INIT_AT = "KEY_PENDING_LOCAL_GPU_INIT_AT"
    private const val KEY_PENDING_LOCAL_GPU_INIT_PID = "KEY_PENDING_LOCAL_GPU_INIT_PID"

    fun getLlmApiKey(): String = getString(KEY_LLM_API_KEY, "")
    fun setLlmApiKey(value: String) = putString(KEY_LLM_API_KEY, value)

    /** Per-provider API key storage — allows users to save keys for multiple providers simultaneously. */
    fun getApiKeyForProvider(provider: String): String =
        getString("KEY_LLM_API_KEY_${provider.uppercase()}", "")
    fun setApiKeyForProvider(provider: String, key: String) =
        putString("KEY_LLM_API_KEY_${provider.uppercase()}", key)
    fun getLlmBaseUrl(): String = getString(KEY_LLM_BASE_URL, "")
    fun setLlmBaseUrl(value: String) = putString(KEY_LLM_BASE_URL, value)
    fun getLlmModelName(): String = getString(KEY_LLM_MODEL_NAME, "")
    fun setLlmModelName(value: String) = putString(KEY_LLM_MODEL_NAME, value)
    fun getLlmProvider(): String = getString(KEY_LLM_PROVIDER, "DEEPSEEK")
    fun setLlmProvider(value: String) = putString(KEY_LLM_PROVIDER, value)
    fun getLocalModelPath(): String = getString(KEY_LOCAL_MODEL_PATH, "")
    fun setLocalModelPath(value: String) = putString(KEY_LOCAL_MODEL_PATH, value)
    fun getLocalBackendPreference(): String = getString(KEY_LOCAL_BACKEND_PREFERENCE, "")
    fun setLocalBackendPreference(value: String) = putString(KEY_LOCAL_BACKEND_PREFERENCE, value)
    fun getLocalCpuSafeDevice(): String = getString(KEY_LOCAL_CPU_SAFE_DEVICE, "")
    fun setLocalCpuSafeDevice(value: String) = putString(KEY_LOCAL_CPU_SAFE_DEVICE, value)
    fun getLocalCpuSafeReason(): String = getString(KEY_LOCAL_CPU_SAFE_REASON, "")
    fun setLocalCpuSafeReason(value: String) = putString(KEY_LOCAL_CPU_SAFE_REASON, value)
    fun getLocalCpuSafeAt(): Long = getLong(KEY_LOCAL_CPU_SAFE_AT, 0L)
    fun setLocalCpuSafeAt(value: Long) = putLong(KEY_LOCAL_CPU_SAFE_AT, value)
    fun getLocalGpuVerifiedDevice(): String = getString(KEY_LOCAL_GPU_VERIFIED_DEVICE, "")
    fun setLocalGpuVerifiedDevice(value: String) = putString(KEY_LOCAL_GPU_VERIFIED_DEVICE, value)
    fun getLocalGpuVerifiedAt(): Long = getLong(KEY_LOCAL_GPU_VERIFIED_AT, 0L)
    fun setLocalGpuVerifiedAt(value: Long) = putLong(KEY_LOCAL_GPU_VERIFIED_AT, value)
    fun clearLocalCpuSafeMode() {
        remove(KEY_LOCAL_CPU_SAFE_DEVICE, KEY_LOCAL_CPU_SAFE_REASON, KEY_LOCAL_CPU_SAFE_AT)
    }
    fun clearLocalGpuVerified() {
        remove(KEY_LOCAL_GPU_VERIFIED_DEVICE, KEY_LOCAL_GPU_VERIFIED_AT)
    }
    fun getPendingLocalGpuInitDevice(): String = getString(KEY_PENDING_LOCAL_GPU_INIT_DEVICE, "")
    fun setPendingLocalGpuInitDevice(value: String) = putString(KEY_PENDING_LOCAL_GPU_INIT_DEVICE, value)
    fun getPendingLocalGpuInitModel(): String = getString(KEY_PENDING_LOCAL_GPU_INIT_MODEL, "")
    fun setPendingLocalGpuInitModel(value: String) = putString(KEY_PENDING_LOCAL_GPU_INIT_MODEL, value)
    fun getPendingLocalGpuInitAt(): Long = getLong(KEY_PENDING_LOCAL_GPU_INIT_AT, 0L)
    fun setPendingLocalGpuInitAt(value: Long) = putLong(KEY_PENDING_LOCAL_GPU_INIT_AT, value)
    fun getPendingLocalGpuInitPid(): Int = getInt(KEY_PENDING_LOCAL_GPU_INIT_PID, 0)
    fun setPendingLocalGpuInitPid(value: Int) = putInt(KEY_PENDING_LOCAL_GPU_INIT_PID, value)
    fun clearPendingLocalGpuInit() {
        remove(
            KEY_PENDING_LOCAL_GPU_INIT_DEVICE,
            KEY_PENDING_LOCAL_GPU_INIT_MODEL,
            KEY_PENDING_LOCAL_GPU_INIT_AT,
            KEY_PENDING_LOCAL_GPU_INIT_PID,
        )
    }

    // ==================== Independent Default Models ====================
    // Local and Cloud each have their own default model config.
    // Switching tabs reads from these keys — they never overwrite each other.

    private const val KEY_DEFAULT_CLOUD_MODEL = "KEY_DEFAULT_CLOUD_MODEL"
    private const val KEY_DEFAULT_CLOUD_PROVIDER = "KEY_DEFAULT_CLOUD_PROVIDER"
    private const val KEY_DEFAULT_CLOUD_BASE_URL = "KEY_DEFAULT_CLOUD_BASE_URL"

    private const val KEY_VLM_API_KEY = "KEY_VLM_API_KEY"
    private const val KEY_VLM_BASE_URL = "KEY_VLM_BASE_URL"
    private const val KEY_VLM_MODEL_NAME = "KEY_VLM_MODEL_NAME"


    fun getDefaultCloudModel(): String = getString(KEY_DEFAULT_CLOUD_MODEL, "")
    fun setDefaultCloudModel(value: String) = putString(KEY_DEFAULT_CLOUD_MODEL, value)
    fun getDefaultCloudProvider(): String = getString(KEY_DEFAULT_CLOUD_PROVIDER, "")
    fun setDefaultCloudProvider(value: String) = putString(KEY_DEFAULT_CLOUD_PROVIDER, value)
    fun getDefaultCloudBaseUrl(): String = getString(KEY_DEFAULT_CLOUD_BASE_URL, "")
    fun setDefaultCloudBaseUrl(value: String) = putString(KEY_DEFAULT_CLOUD_BASE_URL, value)

    // ==================== VLM (Vision Language Model) Config ====================

    fun getVlmApiKey(): String = getString(KEY_VLM_API_KEY, "")
    fun setVlmApiKey(value: String) = putString(KEY_VLM_API_KEY, value)
    fun getVlmBaseUrl(): String = getString(KEY_VLM_BASE_URL, "")
    fun setVlmBaseUrl(value: String) = putString(KEY_VLM_BASE_URL, value)
    fun getVlmModelName(): String = getString(KEY_VLM_MODEL_NAME, "")
    fun setVlmModelName(value: String) = putString(KEY_VLM_MODEL_NAME, value)

    /** Returns true if VLM is fully configured (API key + base URL + model name all present). */
    fun isVlmConfigured(): Boolean =
        getString(KEY_VLM_API_KEY, "").isNotBlank() &&
                getString(KEY_VLM_BASE_URL, "").isNotBlank() &&
                getString(KEY_VLM_MODEL_NAME, "").isNotBlank()

    /** Returns true if a local default model is configured and the file exists. */


    /** Returns true if a local default model is configured and the file exists. */
    fun hasDefaultLocalModel(): Boolean {
        val path = getLocalModelPath()
        return path.isNotEmpty() && java.io.File(path).exists()
    }

    /** Returns true if a cloud default model is configured (model + API key both present). */
    fun hasDefaultCloudModel(): Boolean {
        val model = getDefaultCloudModel()
        val provider = getDefaultCloudProvider().ifEmpty { "DEEPSEEK" }
        val apiKey = getApiKeyForProvider(provider).ifEmpty { getLlmApiKey() }
        return model.isNotEmpty() && apiKey.isNotEmpty()
    }

    /** Returns true if LLM is configured (API key, base URL, or local model path is non-empty) */
    fun hasLlmConfig(): Boolean =
        getLlmApiKey().isNotEmpty() || getLlmBaseUrl().isNotEmpty() || getLocalModelPath().isNotEmpty()
}
