// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.ui.batch

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import io.agents.bqaagent.R
import io.agents.bqaagent.agent.TaskOcrExtractor
import io.agents.bqaagent.agent.OcrExtractionError
import io.agents.bqaagent.agent.VlmConfigRepository
import io.agents.bqaagent.ui.chat.ThemeManager
import io.agents.bqaagent.ui.settings.LlmConfigActivity
import io.agents.bqaagent.ui.settings.SettingsActivity
import io.agents.bqaagent.utils.KVUtils
import io.agents.bqaagent.utils.OcrImageHelper
import io.agents.bqaagent.utils.XLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Standalone Compose host for the batch-execution feature (setup / precheck / run / history / detail).
 *
 * Mirrors the other feature activities (Settings, SkillManager, Recording): a separate task the user
 * enters from chat and returns from with Back. All runtime state lives in [BatchViewModel]; this
 * activity only wires lifecycle, theme and the back stack. Execution itself is delegated all the way
 * down to [BatchTaskDriver] -> TaskOrchestrator, so nothing here duplicates the single-task pipeline.
 */
class BatchActivity : AppCompatActivity() {

    private val batchViewModel: BatchViewModel by viewModels()
    private var keyguardLock: KeyguardManager.KeyguardLock? = null
    private var batchKeepAliveEnabled = false

    // Camera permission for OCR
    private val ocrCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchOcrCamera()
        } else {
            Toast.makeText(this, getString(R.string.ocr_camera_perm_denied), Toast.LENGTH_SHORT).show()
            _ocrLoading.value = false
        }
    }

    // Camera launcher for OCR
    private var ocrCameraFile: java.io.File? = null
    private val ocrCameraLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && ocrCameraFile != null && ocrCameraFile!!.exists()) {
            processOcrImage(android.net.Uri.fromFile(ocrCameraFile))
        } else {
            _ocrLoading.value = false
            ocrCameraFile?.delete()
            ocrCameraFile = null
        }
    }

    // Gallery picker for OCR
    private val ocrGalleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) {
            _ocrLoading.value = false
            return@registerForActivityResult
        }
        processOcrImage(uri)
    }

    // Microphone permission request for voice input (same contract as chat's input bar).
    private val recordAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            Toast.makeText(this, getString(R.string.chat_voice_perm_granted), Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, getString(R.string.chat_voice_perm_denied), Toast.LENGTH_SHORT).show()
        }
    }

    // Voice-input enablement: global setting, re-read on resume so toggling in settings applies here.
    private val _voiceEnabled = mutableStateOf(io.agents.bqaagent.utils.KVUtils.isVoiceInputEnabled())

    // Image picker for the user-assisted screenshot fallback during a batch task. Shares the exact
    // validation/save/toast logic with chat via UserImageUploadHelper and unlocks the same orchestrator
    // latch through BatchViewModel.provideUserImage (null == skip).
    private val userImagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        batchViewModel.provideUserImage(
            io.agents.bqaagent.utils.UserImageUploadHelper.savePickedImage(this, uri)
        )
    }

    // OCR state
    private val _ocrLoading = mutableStateOf(false)
    private val _ocrExtractedText = mutableStateOf<String?>(null)
    private val _showOcrSheet = mutableStateOf(false)
    private val activityScope = CoroutineScope(Dispatchers.Main)

    private fun launchOcrCamera() {
        val ocrDir = getExternalFilesDir("ocr_uploads")
        if (ocrDir == null) {
            Toast.makeText(this, getString(R.string.ocr_storage_unavailable), Toast.LENGTH_SHORT).show()
            _ocrLoading.value = false
            return
        }
        try {
            if (!ocrDir.exists()) ocrDir.mkdirs()
            val file = java.io.File(ocrDir, "ocr_cam_${System.currentTimeMillis()}.jpg")
            ocrCameraFile = file
            val uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file
            )
            ocrCameraLauncher.launch(uri)
        } catch (e: Exception) {
            XLog.e("BatchActivity", "launchOcrCamera failed", e)
            Toast.makeText(this, getString(R.string.ocr_camera_failed), Toast.LENGTH_SHORT).show()
            _ocrLoading.value = false
        }
    }

    private fun processOcrImage(uri: android.net.Uri) {
        _ocrLoading.value = true
        activityScope.launch {
            val result = withContext(Dispatchers.IO) {
                OcrImageHelper.prepareImage(uri, this@BatchActivity)
            }
            val path = result.getOrNull()
            if (path == null) {
                Toast.makeText(this@BatchActivity, getString(R.string.ocr_image_error), Toast.LENGTH_SHORT).show()
                _ocrLoading.value = false
                return@launch
            }

            val config = VlmConfigRepository.load()
            if (!config.isConfigured) {
                Toast.makeText(this@BatchActivity, getString(R.string.ocr_vlm_not_configured), Toast.LENGTH_LONG).show()
                _ocrLoading.value = false
                return@launch
            }

            val extractionResult = TaskOcrExtractor.extractTaskFromImage(path, config)
            _ocrLoading.value = false
            extractionResult.fold(
                onSuccess = { extracted ->
                    _ocrExtractedText.value = extracted
                    withContext(Dispatchers.IO) {
                        OcrImageHelper.cleanupOldImages(this@BatchActivity)
                    }
                },
                onFailure = { error ->
                    val msgRes = (error as? OcrExtractionError)?.userMessageRes ?: R.string.ocr_image_error
                    Toast.makeText(this@BatchActivity, getString(msgRes), Toast.LENGTH_SHORT).show()
                },
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        batchViewModel.refreshGate()
        if (savedInstanceState == null) batchViewModel.restoreLastSession()
        val colors = with(ThemeManager) { getColors().toComposeColors() }
        setContent {
            BatchRoot(
                vm = batchViewModel,
                colors = colors,
                onExit = { finish() },
                onOpenSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
                onOpenModels = { startActivity(Intent(this, LlmConfigActivity::class.java)) },
                onOcrRequest = { _showOcrSheet.value = true },
                ocrLoading = _ocrLoading.value,
                ocrExtractedText = _ocrExtractedText.value,
                onOcrConsumed = { _ocrExtractedText.value = null },
                onUploadUserImage = { userImagePickerLauncher.launch("image/*") },
                voiceEnabled = _voiceEnabled.value,
                onRequestRecordPermission = {
                    recordAudioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                },
            )

            if (_ocrLoading.value) {
                io.agents.bqaagent.ui.common.OcrLoadingOverlay(colors = colors)
            }

            if (_showOcrSheet.value) {
                io.agents.bqaagent.ui.common.OcrSourceChooserSheet(
                    colors = colors,
                    onCamera = {
                        _showOcrSheet.value = false
                        if (ContextCompat.checkSelfPermission(
                                this@BatchActivity, android.Manifest.permission.CAMERA
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            launchOcrCamera()
                        } else {
                            ocrCameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                        }
                    },
                    onGallery = {
                        _showOcrSheet.value = false
                        ocrGalleryLauncher.launch("image/*")
                    },
                    onDismiss = { _showOcrSheet.value = false },
                )
            }
        }

        lifecycleScope.launch {
            batchViewModel.runState.collect { snapshot ->
                val shouldEnable = snapshot != null && snapshot.running
                if (shouldEnable != batchKeepAliveEnabled) {
                    batchKeepAliveEnabled = shouldEnable
                    if (shouldEnable) enableKeepAlive() else disableKeepAlive()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        batchViewModel.refreshGate()
        _voiceEnabled.value = io.agents.bqaagent.utils.KVUtils.isVoiceInputEnabled()
        // Authoritative "last mode" marker for cold-start restore; the top-most resumed screen wins.
        KVUtils.setLastUiMode(BatchUiMode.BATCH)
    }

    override fun onDestroy() {
        super.onDestroy()
        disableKeepAlive()
    }

    private fun enableKeepAlive() {
        runOnUiThread {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (keyguardLock == null) {
                val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                keyguardLock = km.newKeyguardLock("BatchActivity")
            }
            keyguardLock?.disableKeyguard()
            XLog.d("BatchActivity", "Keep-alive enabled")
        }
    }

    private fun disableKeepAlive() {
        runOnUiThread {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            keyguardLock?.reenableKeyguard()
            keyguardLock = null
            XLog.d("BatchActivity", "Keep-alive disabled")
        }
    }

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, BatchActivity::class.java).apply {
                if (context !is ComponentActivity) {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            }
            context.startActivity(intent)
        }
    }
}