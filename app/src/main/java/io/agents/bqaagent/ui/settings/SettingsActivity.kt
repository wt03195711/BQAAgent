// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.ui.settings

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.agents.bqaagent.adb.LocalAdbAutomation
import io.agents.bqaagent.adb.LocalAdbDiscovery
import io.agents.bqaagent.adb.LocalAdbHostResolver
import io.agents.bqaagent.adb.LocalAdbPairingNotification
import io.agents.bqaagent.R
import io.agents.bqaagent.base.BaseActivity
import io.agents.bqaagent.widget.AlertDialog
import io.agents.bqaagent.widget.ConfirmDialog
import io.agents.bqaagent.widget.CommonToolbar
import io.agents.bqaagent.widget.LocalAdbSetupDialog
import io.agents.bqaagent.widget.LoadingDialog
import io.agents.bqaagent.widget.MenuGroup
import io.agents.bqaagent.widget.MenuItem
import io.agents.bqaagent.AppCapabilityCoordinator
import io.agents.bqaagent.AppRequirement
import io.agents.bqaagent.ServiceBindingState
import io.agents.bqaagent.appViewModel
import io.agents.bqaagent.recording.RecordingSelfTest
import io.agents.bqaagent.recording.TaskRecordingCoordinator
import io.agents.bqaagent.recording.TaskRecordingStore
import io.agents.bqaagent.server.ConfigServerManager
import io.agents.bqaagent.service.ForegroundService
import io.agents.bqaagent.support.DebugReportManager
import io.agents.bqaagent.utils.KVUtils
import io.agents.bqaagent.utils.XLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings screen
 */
class SettingsActivity : BaseActivity() {
    private companion object {
        private const val REQUEST_POST_NOTIFICATIONS = 100
    }

    // Poll permissions every second (same as original HomeActivity)
    private val handler = Handler(Looper.getMainLooper())
    private val permPoller = object : Runnable {
        override fun run() {
            refreshPermissions()
            updateTaskRecordingLabel()
            handler.postDelayed(this, 1000)
        }
    }

    // Permission menu items — kept for onResume refresh
    private var permLocalAdb: io.agents.bqaagent.widget.MenuItem? = null
    private var permNotification: io.agents.bqaagent.widget.MenuItem? = null
    private var permNotifAccess: io.agents.bqaagent.widget.MenuItem? = null
    private var permOverlay: io.agents.bqaagent.widget.MenuItem? = null
    private var permBattery: io.agents.bqaagent.widget.MenuItem? = null
    private var permStorage: io.agents.bqaagent.widget.MenuItem? = null
    private var externalAutomationItem: io.agents.bqaagent.widget.MenuItem? = null
    private var languageItem: io.agents.bqaagent.widget.MenuItem? = null
    private var voiceInputItem: io.agents.bqaagent.widget.MenuItem? = null
    private var sensitiveModeItem: io.agents.bqaagent.widget.MenuItem? = null
    private var skillCaptureModeItem: io.agents.bqaagent.widget.MenuItem? = null
    private var taskRecordingItem: io.agents.bqaagent.widget.MenuItem? = null
    private var recordingFilesItem: io.agents.bqaagent.widget.MenuItem? = null
    private var localAdbSetupOpening = false
    private var localAdbSetupDialog: LocalAdbSetupDialog? = null
    private var localAdbRefreshOnResume = false
    private var localAdbRefreshing = false
    private var pendingLocalAdbNotificationPermission = false

    private val viewModel by lazy {
        ViewModelProvider(this)[SettingsViewModel::class.java]
    }

    // Keep MenuItem references for dynamic updates
    private val menuItems = mutableMapOf<String, MenuItem>()

    // Register launcher to refresh after returning from LLM config screen
    private val llmConfigLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { _ ->
        viewModel.refresh()
    }

    private val vlmConfigLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { _ ->
        viewModel.refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Force match theme from ThemeManager
        val themeColors = io.agents.bqaagent.ui.chat.ThemeManager.getColors()
        window.statusBarColor = themeColors.toolbarBg
        window.decorView.setBackgroundColor(themeColors.bg)

        setContentView(R.layout.activity_settings)

        // Override XML backgrounds with ThemeManager colors
        val contentFrame = findViewById<android.view.ViewGroup>(android.R.id.content)
        contentFrame?.setBackgroundColor(themeColors.bg)
        // Root LinearLayout has android:background="@color/colorBgPrimary" — override it
        (contentFrame?.getChildAt(0) as? android.view.View)?.setBackgroundColor(themeColors.bg)

        initToolbar()
        initMenuGroups()
        initVersionFooter()
        hideDeferredSettingsGroups()
        applyThemeToGroups(themeColors)
        observeViewModel()
    }

    override fun onResume() {
        super.onResume()
        refreshSettings()
        refreshPermissions()
        refreshExternalAutomation()
        refreshVoiceInput()
        refreshSensitiveMode()
        refreshSkillCaptureMode()
        refreshTaskRecording()
        handler.removeCallbacks(permPoller)
        handler.postDelayed(permPoller, 1000)
        localAdbRefreshOnResume = false
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(permPoller)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_POST_NOTIFICATIONS) return

        refreshPermissions()
        if (!pendingLocalAdbNotificationPermission) return

        pendingLocalAdbNotificationPermission = false
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            openLocalAdbPairingFlow()
        } else {
            Toast.makeText(this, R.string.local_adb_notification_permission_required, Toast.LENGTH_LONG).show()
        }
    }

    private fun refreshPermissions() {
        val capabilities = AppCapabilityCoordinator.snapshot(this)
        permLocalAdb?.setTrailingText(capabilities.localAdbStatusLabel)
        permNotification?.setTrailingText(capabilities.notificationPermissionStatusLabel)
        permNotifAccess?.setTrailingText(capabilities.notificationAccessStatusLabel)
        permOverlay?.setTrailingText(if (capabilities.overlayGranted) "Enabled" else "Disabled")
        permBattery?.setTrailingText(if (capabilities.batteryOptimizationIgnored) "Unrestricted" else "Restricted")
        permStorage?.setTrailingText(if (capabilities.storageAccessGranted) "Enabled" else "Disabled")
    }

    private fun refreshExternalAutomation() {
        externalAutomationItem?.setTrailingText(
            if (KVUtils.isExternalAutomationEnabled()) "Enabled" else "Disabled"
        )
    }

    private fun refreshVoiceInput() {
        voiceInputItem?.setTrailingText(
            if (KVUtils.isVoiceInputEnabled()) "Enabled" else "Disabled"
        )
    }

    private fun refreshSensitiveMode() {
        sensitiveModeItem?.setTrailingText(
            if (KVUtils.isSensitiveModeEnabled()) "Enabled" else "Disabled"
        )
    }

    private fun refreshSkillCaptureMode() {
        skillCaptureModeItem?.setTrailingText(
            if (KVUtils.isSkillCaptureModeEnabled()) "Enabled" else "Disabled"
        )
    }

    /**
     * Cheap, main-thread-safe status label for the Task Recording row. Reflects the live Local ADB
     * state so a wireless disconnect immediately shows the switch is blocked instead of a stale
     * "Enabled". Polled every second by [permPoller] alongside the ADB row; deliberately does NO
     * disk I/O — the recordings summary lives in [refreshTaskRecording].
     */
    private fun updateTaskRecordingLabel() {
        val enabled = KVUtils.isTaskRecordingEnabled()
        // DEGRADED ("Disconnected") / DISABLED (no host+port) are definitive down states. CONNECTING
        // is a transient probe — isReady() returns it whenever the 15s cache is stale (e.g. right
        // after onResume) — so treating it as down would flash a false "ADB off" on every resume.
        val adbState = AppCapabilityCoordinator.localAdbState(this)
        val adbDown = adbState == ServiceBindingState.DEGRADED || adbState == ServiceBindingState.DISABLED
        val abort = TaskRecordingCoordinator.lastAbortReason
        taskRecordingItem?.setTrailingText(
            when {
                !enabled -> "Disabled"
                adbDown -> "Enabled · ADB off"
                !abort.isNullOrBlank() -> "Enabled · issue"
                else -> "Enabled"
            }
        )
        taskRecordingItem?.setTrailingTextColor(
            if (enabled && (adbDown || !abort.isNullOrBlank())) getColor(R.color.colorErrorPrimary)
            else getColor(R.color.colorTextSecondary)
        )
    }

    private fun refreshTaskRecording() {
        updateTaskRecordingLabel()
        lifecycleScope.launch {
            val summary = withContext(Dispatchers.IO) {
                val entries = TaskRecordingStore.listRecordings()
                val bytes = entries.sumOf { TaskRecordingStore.dirSize(it.dir) }
                "${entries.size} file(s) · ${TaskRecordingStore.formatBytes(bytes)}"
            }
            recordingFilesItem?.setTrailingText(summary)
        }
    }

    /** Guards the ~1 minute self-test against double taps and against overlapping runs. */
    private var selfTestRunning = false
    private var verifyingRecording = false

    private fun toggleTaskRecording() {
        val enabled = !KVUtils.isTaskRecordingEnabled()
        if (!enabled) {
            KVUtils.setTaskRecordingEnabled(false)
            refreshTaskRecording()
            Toast.makeText(this, "Task Recording disabled", Toast.LENGTH_SHORT).show()
            return
        }
        if (!LocalAdbAutomation.hasConnectionConfig()) {
            AlertDialog.show(
                context = this,
                title = "Local ADB required",
                message = "Task recording runs over the same Local ADB channel as automation.\n\nConnect Local ADB first (Permissions → Local ADB), then enable recording.",
                actionTitle = "Got it"
            )
            return
        }
        if (verifyingRecording || selfTestRunning) {
            Toast.makeText(this, "Already checking this device", Toast.LENGTH_SHORT).show()
            return
        }
        if (appViewModel.isTaskRunning()) {
            Toast.makeText(this, "Stop the running task first", Toast.LENGTH_LONG).show()
            return
        }
        verifyThenEnableRecording()
    }

    /**
     * Probes FIRST and flips the switch only on a pass. The previous order enabled the switch,
     * deferred the probe by 60s and discarded its result (preWarmAsync returns Unit), so a device
     * that could not record displayed "Enabled" forever and silently produced nothing — the user
     * only found out by looking for a video that was never there.
     */
    private fun verifyThenEnableRecording() {
        verifyingRecording = true
        val loading = LoadingDialog.show(this, "Preparing recording check…")
        val startedAt = System.currentTimeMillis()
        var latestStage = "Preparing recording check…"

        fun renderLoading() {
            if (isFinishing || isDestroyed) return
            val elapsed = (System.currentTimeMillis() - startedAt) / 1000
            loading.setMessage("$latestStage\nElapsed ${elapsed}s, please wait…")
        }

        // Ticking elapsed-seconds on the main-thread Handler: the strongest "still alive" signal, and
        // unlike the indeterminate ProgressBar it keeps moving even when the device's Animator duration
        // scale is 0 — which is exactly what made the spinner look frozen on test devices.
        val ticker = object : Runnable {
            override fun run() {
                renderLoading()
                handler.postDelayed(this, 1_000L)
            }
        }
        renderLoading()
        handler.post(ticker)

        lifecycleScope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    TaskRecordingCoordinator.verifyDevice { _, _, msg ->
                        runOnUiThread {
                            latestStage = msg
                            renderLoading()
                        }
                    }
                }
            }
            handler.removeCallbacks(ticker)
            verifyingRecording = false
            runCatching { loading.dismiss() }
            if (isFinishing || isDestroyed) return@launch
            outcome.onFailure { e ->
                XLog.e("SettingsActivity", "Recording verification crashed", e)
                showRecordingUnavailable("verify-crashed", "${e.javaClass.simpleName}: ${e.message}")
                return@launch
            }
            val verdict = outcome.getOrThrow()
            refreshTaskRecording()

            if (!verdict.usable) {
                // Show why recording is unavailable so the user knows what to fix, not just that the
                // switch refused to turn on.
                showRecordingUnavailable(verdict.reasonCode, verdict.humanReason)
                return@launch
            }

            KVUtils.setTaskRecordingEnabled(true)
            refreshTaskRecording()
            // Users only care about two things here: does it work, and what to watch out for.
            // The verification time / wrapper / overlap flags are diagnostics, not instructions —
            // they stay in copyRecordingDiagnostics() for support reports instead of this dialog.
            val seamNote = if (verdict.overlap) "" else
                "\n\nOne thing to note: on very long recordings this device may pause for about a " +
                        "second every couple of minutes while the video rolls over. Short tasks are unaffected."
            AlertDialog.show(
                context = this@SettingsActivity,
                title = "Task Recording is ready",
                message = "Recording works on this device — you're all set.\n\n" +
                        "What gets recorded: only tasks that actually operate the device " +
                        "(running an agent, replaying or using a Skill, opening an app). " +
                        "Recording starts the moment a task begins and stops when it ends, " +
                        "so idle chatting is never captured." +
                        seamNote,
                actionTitle = "Got it",
                isDismissible = true
            )
        }
    }

    private fun showRecordingUnavailable(code: String, human: String) {
        AlertDialog.show(
            context = this@SettingsActivity,
            title = "Recording unavailable on this device",
            message = "$human\n\n($code)\n\nThe switch stayed off — no task will be recorded until this passes.",
            actionTitle = "Copy diagnostics",
            cancelTitle = getString(R.string.common_cancel),
            messageAlignStart = true,
            onAction = { copyRecordingDiagnostics() }
        )
    }

    private fun copyRecordingDiagnostics() {
        val text = buildString {
            append("BQAAgent recording diagnostics\n")
            append("Device : ").append(TaskRecordingStore.deviceModel()).append('\n')
            append("System : ").append(TaskRecordingStore.androidVersion()).append('\n')
            append("App    : ").append(TaskRecordingStore.appVersion()).append("\n\n")
            append(TaskRecordingCoordinator.diagnostics())
        }
        runCatching {
            getSystemService(android.content.ClipboardManager::class.java)
                ?.setPrimaryClip(android.content.ClipData.newPlainText("bqaagent_recording", text))
            Toast.makeText(this, "Diagnostics copied", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(this, "Could not copy diagnostics", Toast.LENGTH_SHORT).show()
        }
    }

    private fun runRecordingSelfTest() {
        if (selfTestRunning) {
            Toast.makeText(this, "Self-test already running", Toast.LENGTH_SHORT).show()
            return
        }
        if (appViewModel.isTaskRunning()) {
            Toast.makeText(this, "Stop the running task first", Toast.LENGTH_LONG).show()
            return
        }
        if (!LocalAdbAutomation.hasConnectionConfig()) {
            Toast.makeText(this, "Connect Local ADB first", Toast.LENGTH_LONG).show()
            return
        }
        selfTestRunning = true
        Toast.makeText(
            this,
            "Running recording self-test… takes up to a minute, keep this page open",
            Toast.LENGTH_LONG
        ).show()
        lifecycleScope.launch {
            // runCatching, not a bare call: lifecycleScope has no CoroutineExceptionHandler,
            // so anything that escapes here terminates the process instead of showing an error.
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    RecordingSelfTest.run(
                        isTaskRunning = { appViewModel.isTaskRunning() },
                        deep = true
                    )
                }
            }
            selfTestRunning = false
            if (isFinishing || isDestroyed) return@launch
            refreshTaskRecording()
            outcome.onFailure { e ->
                XLog.e("SettingsActivity", "Recording self-test crashed the coroutine", e)
                AlertDialog.show(
                    context = this@SettingsActivity,
                    title = "Self-test crashed",
                    message = "${e.javaClass.simpleName}: ${e.message}\n\nSee logcat tag RecordingSelfTest for the stack.",
                    actionTitle = "Got it",
                    messageAlignStart = true
                )
                return@launch
            }
            val result = outcome.getOrThrow()
            AlertDialog.show(
                context = this@SettingsActivity,
                title = if (result.passed) "Self-test passed" else "Self-test found problems",
                message = result.summary,
                actionTitle = "Share full report",
                cancelTitle = getString(R.string.common_cancel),
                messageAlignStart = true,
                messageTextSizeDp = 12f,
                messageMaxLines = 18,
                onAction = {
                    val file = result.reportFile
                    if (file != null && file.exists()) {
                        sharePlainFile(file, "Share recording self-test report", "text/plain")
                    } else {
                        Toast.makeText(this@SettingsActivity, "Report file unavailable", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }
    }

    private fun sharePlainFile(file: java.io.File, chooserTitle: String, mimeType: String) {
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(intent, chooserTitle))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No app available to share this file", Toast.LENGTH_LONG).show()
        }
    }

    private fun refreshLanguageItem() {
        languageItem?.setTrailingText(currentLanguageLabel())
    }

    private fun initToolbar() {
        findViewById<CommonToolbar>(R.id.toolbar).apply {
            setTitle(getString(R.string.settings_title))
            showBackButton(true) { finish() }
        }
    }

    private fun hideDeferredSettingsGroups() {
        // TODO 隐藏暂未开放的设置分组
        listOf(R.id.toolsGroup, R.id.remoteGroup, R.id.aboutGroup).forEach { id ->
            findViewById<MenuGroup>(id)?.visibility = android.view.View.GONE
        }
    }

    private fun applyThemeToGroups(tc: io.agents.bqaagent.ui.chat.ThemeManager.ChatColors) {
        val groups = listOf(
            R.id.permissionsGroup, R.id.channelGroup, R.id.modelGroup,
            R.id.appearanceGroup, R.id.skillManagerGroup, R.id.recordingGroup,
            R.id.toolsGroup, R.id.remoteGroup, R.id.aboutGroup
        )
        for (id in groups) {
            val g = findViewById<MenuGroup>(id) ?: continue
            g.setTitleColor(tc.aiText)
            g.setCardBackgroundColor(tc.toolbarBg)
            for (i in 0 until g.getMenuItemCount()) {
                g.getMenuItemAt(i)?.apply {
                    setTitleColor(tc.aiText)
                    setTrailingTextColor(tc.toolDefault)
                    setLeadingIconColor(tc.aiText)
                    setTrailingIconColor(tc.toolDefault)
                }
            }
        }
        findViewById<android.widget.TextView>(R.id.tvVersionFooter)?.setTextColor(tc.toolDefault)
        // Toolbar
        findViewById<CommonToolbar>(R.id.toolbar)?.apply {
            setBackgroundColor(tc.toolbarBg)
            setTitleColor(tc.aiText)
            findViewById<android.widget.ImageView>(R.id.ivBack)?.setColorFilter(tc.aiText)
        }
    }

    private fun refreshSettings() {
        viewModel.refresh()
        refreshLanguageItem()
        initVersionFooter()
    }

    private fun initVersionFooter() {
        findViewById<android.widget.TextView>(R.id.tvVersionFooter)?.text =
            getString(R.string.settings_footer_version, io.agents.bqaagent.BuildConfig.VERSION_NAME)
    }

    private fun currentLanguageLabel(): String {
        return if (isCurrentLanguageChinese()) {
            getString(R.string.settings_language_chinese)
        } else {
            getString(R.string.settings_language_english)
        }
    }

    private fun isCurrentLanguageChinese(): Boolean {
        val savedLanguage = KVUtils.getAppLanguageTag()
        val languageTag = savedLanguage.ifBlank {
            AppCompatDelegate.getApplicationLocales().toLanguageTags().ifBlank {
                currentResourceLanguageTag()
            }
        }
        return languageTag.lowercase().startsWith("zh")
    }

    private fun currentResourceLanguageTag(): String {
        val locale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            resources.configuration.locales[0]
        } else {
            @Suppress("DEPRECATION")
            resources.configuration.locale
        }
        return locale.toLanguageTag()
    }

    private fun toggleAppLanguage() {
        val nextLanguageTag = if (isCurrentLanguageChinese()) "en" else "zh-CN"
        KVUtils.setAppLanguageTag(nextLanguageTag)
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(nextLanguageTag))
        refreshLanguageItem()
        Toast.makeText(this, R.string.settings_language_switched, Toast.LENGTH_SHORT).show()
    }

    private fun toggleExternalAutomation() {
        if (KVUtils.isExternalAutomationEnabled()) {
            KVUtils.setExternalAutomationEnabled(false)
            refreshExternalAutomation()
            Toast.makeText(this, "External Automation disabled", Toast.LENGTH_SHORT).show()
            return
        }

        ConfirmDialog.showWarm(
            context = this,
            title = "Enable External Automation?",
            message = "This lets trusted apps like Tasker, MacroDroid, or ADB start BQAAgent tasks with explicit Android intents. Keep it off unless you control the automation that will call it.",
            actionTitle = "Enable",
            cancelTitle = getString(R.string.common_cancel),
            onAction = {
                KVUtils.setExternalAutomationEnabled(true)
                refreshExternalAutomation()
                Toast.makeText(this, "External Automation enabled", Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun toggleVoiceInput() {
        val enabled = !KVUtils.isVoiceInputEnabled()
        KVUtils.setVoiceInputEnabled(enabled)
        refreshVoiceInput()
        Toast.makeText(
            this,
            if (enabled) "Voice input enabled" else "Voice input disabled",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun toggleSensitiveMode() {
        if (KVUtils.isSensitiveModeEnabled()) {
            ConfirmDialog.showWarm(
                context = this,
                title = getString(R.string.sensitive_mode_disable_title),
                message = getString(R.string.sensitive_mode_disable_message),
                actionTitle = getString(R.string.sensitive_mode_disable_confirm),
                cancelTitle = getString(R.string.common_cancel),
                onAction = {
                    KVUtils.setSensitiveModeEnabled(false)
                    refreshSensitiveMode()
                    Toast.makeText(this, R.string.sensitive_mode_disabled, Toast.LENGTH_SHORT).show()
                }
            )
        } else {
            KVUtils.setSensitiveModeEnabled(true)
            refreshSensitiveMode()
            Toast.makeText(this, R.string.sensitive_mode_enabled, Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleSkillCaptureMode() {
        val enabled = !KVUtils.isSkillCaptureModeEnabled()
        KVUtils.setSkillCaptureModeEnabled(enabled)
        refreshSkillCaptureMode()
        if (enabled) {
            AlertDialog.show(
                context = this,
                title = "Skill Capture Mode enabled",
                message = "Tasks now run independently without chat history, and completed tasks can be recorded and saved as reusable skills.\n\nNote: please describe the complete task in one message, since follow-up replies will not continue the previous task.",
                actionTitle = "Got it"
            )
        } else {
            Toast.makeText(
                this,
                "Skill Capture Mode off: tasks follow chat history again, skill saving disabled",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun showLocalAdbSetupDialog() {
        openSmartLocalAdbEntry()
    }

    private fun openSmartLocalAdbEntry() {
        if (LocalAdbAutomation.hasRememberedPairing()) {
            reconnectRememberedLocalAdb()
        } else {
            openLocalAdbPairingFlow()
        }
    }

    private fun reconnectRememberedLocalAdb() {
        if (localAdbSetupOpening) return
        localAdbSetupOpening = true
        permLocalAdb?.setTrailingText("Not Ready")
        Toast.makeText(this, R.string.local_adb_reconnecting_saved_pair, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                LocalAdbAutomation.reconnectRemembered(this@SettingsActivity)
            }
            localAdbSetupOpening = false
            refreshPermissions()
            if (result.isSuccess) {
                Toast.makeText(this@SettingsActivity, R.string.local_adb_setup_success, Toast.LENGTH_LONG).show()
            } else {
                XLog.w("SettingsActivity", "Remembered Local ADB reconnect failed: ${result.combinedOutput}")
                Toast.makeText(this@SettingsActivity, R.string.local_adb_reconnect_failed_pair_again, Toast.LENGTH_LONG).show()
                openLocalAdbPairingFlow()
            }
        }
    }

    private fun openLocalAdbPairingFlow() {
        if (showLocalAdbPairingNotification()) {
            Toast.makeText(this, R.string.local_adb_notification_ready, Toast.LENGTH_LONG).show()
            LocalAdbAutomation.openWirelessDebuggingSettings(this)
        } else {
            Toast.makeText(this, R.string.local_adb_notification_permission_required, Toast.LENGTH_LONG).show()
        }
    }

    private fun showLocalAdbPairingNotification(): Boolean {
        val notificationShown = LocalAdbPairingNotification.show(this)
        if (!notificationShown && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pendingLocalAdbNotificationPermission = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_POST_NOTIFICATIONS)
        }
        return notificationShown
    }

    private fun showLocalAdbInputDialog(preset: LocalAdbSetupDialog.Preset) {
        localAdbSetupDialog = LocalAdbSetupDialog.show(
            context = this,
            preset = preset,
            onOpenWirelessDebugging = {
                localAdbRefreshOnResume = true
                showLocalAdbPairingNotification()
                LocalAdbAutomation.openWirelessDebuggingSettings(this)
            },
            onRefreshPorts = {
                refreshLocalAdbSetupDialog()
            },
            onComplete = { form ->
                lifecycleScope.launch {
                    permLocalAdb?.setTrailingText("Not Ready")
                    val result = withContext(Dispatchers.IO) {
                        val pairingCode = form.pairingCode?.takeIf { it.isNotBlank() }
                        val pairResult = if (pairingCode != null) {
                            LocalAdbAutomation.pair(form.ip, form.port, pairingCode)
                        } else {
                            null
                        }
                        if (pairResult != null && !pairResult.isSuccess) {
                            return@withContext pairResult
                        }

                        val connectPort = resolveLocalAdbConnectPort(pairingCode, form)
                        if (connectPort == null) {
                            null
                        } else {
                            LocalAdbAutomation.configureConnection("127.0.0.1", connectPort)
                        }
                    }
                    refreshPermissions()
                    if (result?.isSuccess == true) {
                        Toast.makeText(this@SettingsActivity, R.string.local_adb_setup_success, Toast.LENGTH_LONG).show()
                    } else {
                        showLocalAdbSetupError(result)
                    }
                    if (result != null && !result.isSuccess) {
                        XLog.w("SettingsActivity", "Local ADB setup failed: ${result.combinedOutput}")
                    }
                }
            }
        ).also { dialog ->
            dialog.setOnDismissListener {
                if (localAdbSetupDialog === dialog) {
                    localAdbSetupDialog = null
                }
            }
        }
    }

    private fun refreshLocalAdbSetupDialog() {
        val dialog = localAdbSetupDialog ?: return
        if (localAdbRefreshing) return
        localAdbRefreshing = true
        Toast.makeText(this, R.string.local_adb_detecting, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            try {
                val preset = withContext(Dispatchers.IO) {
                    buildLocalAdbSetupPreset(LocalAdbDiscovery.discoverBlocking(this@SettingsActivity))
                }
                dialog.updatePreset(preset)
                Toast.makeText(
                    this@SettingsActivity,
                    if (preset.port != null) R.string.local_adb_ports_refreshed else R.string.local_adb_ports_not_found,
                    Toast.LENGTH_SHORT
                ).show()
            } finally {
                localAdbRefreshing = false
            }
        }
    }

    private fun buildLocalAdbSetupPreset(discovery: LocalAdbDiscovery.Result): LocalAdbSetupDialog.Preset {
        val connectPort = discovery.connectPort
        val visiblePort = discovery.pairPort ?: discovery.connectPort
        return LocalAdbSetupDialog.Preset(
            ip = LocalAdbHostResolver.defaultPairingHost(this),
            port = visiblePort,
            connectPort = connectPort,
            portIsPairingPort = discovery.pairPort != null,
        )
    }

    private fun defaultLocalAdbSetupPreset(): LocalAdbSetupDialog.Preset {
        return LocalAdbSetupDialog.Preset(ip = LocalAdbHostResolver.defaultPairingHost(this))
    }

    private fun resolveLocalAdbConnectPort(
        pairingCode: String?,
        form: LocalAdbSetupDialog.Result,
    ): Int? {
        if (pairingCode == null) {
            val isUnchangedPairingPort = form.portIsPairingPort && form.portMatchesPreset
            return if (isUnchangedPairingPort) form.connectPort ?: form.port else form.port
        }
        return form.connectPort
            ?: LocalAdbDiscovery.discoverBlocking(this, timeoutMs = 7_000L).connectPort
    }

    private fun showLocalAdbSetupError(result: io.agents.bqaagent.adb.ShellCommandResult?) {
        val message = result?.combinedOutput
            ?.ifBlank { null }
            ?: getString(R.string.local_adb_connect_port_missing)
        AlertDialog.show(
            context = this,
            title = getString(R.string.local_adb_setup_failed),
            message = message,
            actionTitle = getString(R.string.common_confirm),
        )
    }

    private fun initMenuGroups() {
        // Permissions
        val permissionsGroup = findViewById<MenuGroup>(R.id.permissionsGroup)
        permissionsGroup.setTitle("Permissions")

        permLocalAdb = permissionsGroup.addMenuItem(
            leadingIcon = R.drawable.ic_local_adb,
            title = getString(R.string.home_card_local_adb_title),
            onClick = {
                showLocalAdbSetupDialog()
            },
            showDivider = true
        )

        permNotification = permissionsGroup.addMenuItem(
            leadingIcon = R.drawable.ic_notification,
            title = getString(R.string.home_card_notification_title),
            onClick = {
                if (!AppCapabilityCoordinator.isNotificationPermissionGranted(this@SettingsActivity)) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_POST_NOTIFICATIONS)
                    }
                } else {
                    Toast.makeText(this@SettingsActivity, R.string.home_notification_enabled, Toast.LENGTH_SHORT).show()
                }
            },
            showDivider = true
        ).apply {
            setTrailingText(
                if (AppCapabilityCoordinator.isNotificationPermissionGranted(this@SettingsActivity)) "Enabled" else "Disabled"
            )
        }

        permNotifAccess = permissionsGroup.addMenuItem(
            leadingIcon = R.drawable.ic_notification,
            title = "Notification Access",
            onClick = {
                AppCapabilityCoordinator.openSystemSettings(this, AppRequirement.NOTIFICATION_ACCESS)
            },
            showDivider = true
        )

        permOverlay = permissionsGroup.addMenuItem(
            leadingIcon = R.drawable.ic_window,
            title = getString(R.string.home_card_system_window_title),
            onClick = {
                if (AppCapabilityCoordinator.snapshot(this@SettingsActivity).overlayGranted) {
                    Toast.makeText(this@SettingsActivity, R.string.home_overlay_enabled, Toast.LENGTH_SHORT).show()
                } else {
                    AppCapabilityCoordinator.openSystemSettings(this@SettingsActivity, AppRequirement.OVERLAY)
                }
            },
            showDivider = true
        )

        permBattery = permissionsGroup.addMenuItem(
            leadingIcon = R.drawable.ic_battery,
            title = getString(R.string.home_card_battery_title),
            onClick = {
                if (AppCapabilityCoordinator.snapshot(this@SettingsActivity).batteryOptimizationIgnored) {
                    Toast.makeText(this@SettingsActivity, R.string.home_battery_ignored, Toast.LENGTH_SHORT).show()
                } else {
                    AppCapabilityCoordinator.openSystemSettings(this@SettingsActivity, AppRequirement.BATTERY_OPTIMIZATION)
                }
            },
            showDivider = true
        )

        permStorage = permissionsGroup.addMenuItem(
            leadingIcon = R.drawable.ic_storage,
            title = getString(R.string.home_card_storage_title),
            onClick = {
                if (AppCapabilityCoordinator.snapshot(this@SettingsActivity).storageAccessGranted) {
                    Toast.makeText(this@SettingsActivity, R.string.home_storage_enabled, Toast.LENGTH_SHORT).show()
                } else {
                    AppCapabilityCoordinator.openSystemSettings(this@SettingsActivity, AppRequirement.STORAGE)
                }
            },
            showDivider = false
        )

        // Local browser config (hidden)
        val channelGroup = findViewById<MenuGroup>(R.id.channelGroup)
        channelGroup.setTitle(getString(R.string.settings_group_channel))

        menuItems[SettingsViewModel.MenuAction.LAN_CONFIG.name] = channelGroup.addMenuItem(
            leadingIcon = R.drawable.ic_lan_config,
            title = getString(R.string.menu_lan_config),
            onClick = { viewModel.onMenuItemClick(SettingsViewModel.MenuAction.LAN_CONFIG) },
            showDivider = false
        )
        menuItems[SettingsViewModel.MenuAction.LAN_CONFIG.name]?.setLeadingIconColor(getColor(R.color.colorTextPrimary))


        val modelGroup = findViewById<MenuGroup>(R.id.modelGroup)
        modelGroup.setTitle(getString(R.string.settings_group_model))

        menuItems[SettingsViewModel.MenuAction.LLM_CONFIG.name] = modelGroup.addMenuItem(
            leadingIcon = R.drawable.icon_current_model,
            title = getString(R.string.menu_llm_config),
            onClick = { viewModel.onMenuItemClick(SettingsViewModel.MenuAction.LLM_CONFIG) },
            showDivider = true
        )
        menuItems[SettingsViewModel.MenuAction.LLM_CONFIG.name]?.setLeadingIconColor(getColor(R.color.colorTextPrimary))

        menuItems[SettingsViewModel.MenuAction.VLM_CONFIG.name] = modelGroup.addMenuItem(
            leadingIcon = R.drawable.icon_current_model,
            title = "Vision Model",
            onClick = { viewModel.onMenuItemClick(SettingsViewModel.MenuAction.VLM_CONFIG) },
            showDivider = false
        )
        menuItems[SettingsViewModel.MenuAction.VLM_CONFIG.name]?.setLeadingIconColor(getColor(R.color.colorTextPrimary))

        // Appearance
        val appearanceGroup = findViewById<MenuGroup>(R.id.appearanceGroup)
        appearanceGroup.setTitle(getString(R.string.settings_group_appearance))

        appearanceGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_slideshow,
            title = getString(R.string.settings_theme),
            onClick = {
                startActivity(Intent(this, ThemeActivity::class.java))
            },
            showDivider = true
        ).apply {
            val themeId = KVUtils.getString("THEME_ID", "ember_light")
            val label = themeId.replace("_", " ").replaceFirstChar { it.uppercase() }
            setTrailingText(label)
        }

        languageItem = appearanceGroup.addMenuItem(
            leadingIcon = R.drawable.ic_language,
            title = getString(R.string.settings_language),
            onClick = { toggleAppLanguage() },
            showDivider = true
        ).apply {
            setTrailingText(currentLanguageLabel())
        }

        voiceInputItem = appearanceGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_btn_speak_now,
            title = "Voice Input",
            onClick = { toggleVoiceInput() },
            showDivider = true
        ).apply {
            setTrailingText(if (KVUtils.isVoiceInputEnabled()) "Enabled" else "Disabled")
        }

        sensitiveModeItem = appearanceGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_lock_lock,
            title = getString(R.string.sensitive_mode_title),
            onClick = { toggleSensitiveMode() },
            showDivider = false
        ).apply {
            setTrailingText(if (KVUtils.isSensitiveModeEnabled()) "Enabled" else "Disabled")
        }

        // Skill Management
        val skillManagerGroup = findViewById<MenuGroup>(R.id.skillManagerGroup)
        skillManagerGroup.setTitle("Skill Management")

        skillCaptureModeItem = skillManagerGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_save,
            title = "Skill Capture Mode",
            onClick = { toggleSkillCaptureMode() },
            showDivider = true
        ).apply {
            setTrailingText(if (KVUtils.isSkillCaptureModeEnabled()) "Enabled" else "Disabled")
        }

        skillManagerGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_agenda,
            title = "Skill Data",
            onClick = {
                startActivity(Intent(this, SkillManagerActivity::class.java))
            },
            showDivider = false
        )

        // Task Recording
        val recordingGroup = findViewById<MenuGroup>(R.id.recordingGroup)
        recordingGroup.setTitle("Task Recording")

        taskRecordingItem = recordingGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_camera,
            title = "Task Recording",
            onClick = { toggleTaskRecording() },
            showDivider = true
        ).apply {
            setTrailingText(if (KVUtils.isTaskRecordingEnabled()) "Enabled" else "Disabled")
        }

        recordingFilesItem = recordingGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_gallery,
            title = "Recorded Files",
            onClick = {
                startActivity(
                    Intent(this, io.agents.bqaagent.ui.recording.RecordingManagerActivity::class.java)
                )
            },
            showDivider = false
        ).apply {
            setTrailingText("0 file(s)")
            // Support tool, not a user flow. Everything a user needs is now covered by the
            // verification gate in toggleTaskRecording(), so the self-test moves behind a
            // long-press: it stays reachable for diagnostics without asking anyone to run it.
            setOnLongClickListener {
                runRecordingSelfTest()
                true
            }
        }

        // Tools
        val toolsGroup = findViewById<MenuGroup>(R.id.toolsGroup)
        toolsGroup.setTitle("Tools")

        toolsGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_manage,
            title = "Manage Tools",
            onClick = {
                Toast.makeText(this, "12 tools enabled. Tool management coming soon.", Toast.LENGTH_SHORT).show()
            },
            showDivider = false
        ).apply {
            setTrailingText("12 enabled")
        }

        // Local automation
        val remoteGroup = findViewById<MenuGroup>(R.id.remoteGroup)
        remoteGroup.setTitle("Automation")

        externalAutomationItem = remoteGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_share,
            title = "External Automation",
            onClick = { toggleExternalAutomation() },
            showDivider = false
        ).apply {
            setTrailingText(if (KVUtils.isExternalAutomationEnabled()) "Enabled" else "Disabled")
        }

        // About
        val aboutGroup = findViewById<MenuGroup>(R.id.aboutGroup)
        aboutGroup.setTitle("About")

        aboutGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_info_details,
            title = "BQAAgent",
            onClick = { },
            showDivider = true
        ).apply {
            setTrailingText("v${io.agents.bqaagent.BuildConfig.VERSION_NAME}")
        }

        aboutGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_send,
            title = "Report a Bug",
            onClick = { reportBug() },
            showDivider = true
        ).apply {
            setTrailingText("GitHub + ZIP")
        }

        aboutGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_upload,
            title = "Share Debug Report",
            onClick = { shareDebugReport() },
            showDivider = true
        ).apply {
            setTrailingText("ZIP logs + state")
        }

        aboutGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_share,
            title = "GitHub",
            onClick = {
                startActivity(Intent(Intent.ACTION_VIEW, "https://github.com/agents-io/BQAAgent".toUri()))
            },
            showDivider = true
        ).apply {
            setTrailingText("agents-io/BQAAgent")
        }

        aboutGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_compass,
            title = "Built by",
            onClick = {
                startActivity(Intent(Intent.ACTION_VIEW, "https://github.com/ithiria894".toUri()))
            },
            showDivider = false
        ).apply {
            setTrailingText("ithiria894")
        }
    }

    private fun reportBug() {
        buildSupportBundle(
            preparingToast = "Preparing bug report…"
        ) { report ->
            AlertDialog.show(
                context = this@SettingsActivity,
                title = "Bug report ready",
                message = """
                    ${report.name} is ready.

                    Open GitHub Issue to file the bug now.
                    If your browser or GitHub app makes attachment upload awkward, tap Share ZIP instead and send the report manually.
                """.trimIndent(),
                actionTitle = "Open GitHub Issue",
                cancelTitle = "Share ZIP",
                onAction = { openGitHubIssue(report) },
                onCancel = {
                    shareReportFile(
                        report = report,
                        chooserTitle = "Share bug report ZIP",
                        subject = "BQAAgent bug report ${io.agents.bqaagent.BuildConfig.VERSION_NAME}",
                        body = """
                            Attach this ZIP to your GitHub issue:
                            https://github.com/agents-io/BQAAgent/issues/new
                        """.trimIndent()
                    )
                }
            )
        }
    }

    private fun shareDebugReport() {
        buildSupportBundle(
            preparingToast = "Preparing debug report…",
        ) { report ->
            shareReportFile(
                report = report,
                chooserTitle = "Share debug report",
                subject = "BQAAgent debug report ${io.agents.bqaagent.BuildConfig.VERSION_NAME}",
                body = "Attach this debug report when reporting a BQAAgent issue."
            )
        }
    }

    private fun buildSupportBundle(
        preparingToast: String,
        onReportReady: (java.io.File) -> Unit,
    ) {
        lifecycleScope.launch {
            Toast.makeText(this@SettingsActivity, preparingToast, Toast.LENGTH_SHORT).show()
            runCatching {
                withContext(Dispatchers.IO) {
                    DebugReportManager.buildReport(this@SettingsActivity)
                }
            }.onSuccess { report ->
                onReportReady(report)
            }.onFailure { error ->
                XLog.e("SettingsActivity", "Failed to build debug report", error)
                Toast.makeText(this@SettingsActivity, "Failed to build debug report", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openGitHubIssue(report: java.io.File) {
        val issueUri = "https://github.com/agents-io/BQAAgent/issues/new".toUri()
            .buildUpon()
            .appendQueryParameter(
                "title",
                "[Bug] ${Build.MANUFACTURER} ${Build.MODEL} - "
            )
            .appendQueryParameter("body", buildGitHubIssueBody(report))
            .build()
        try {
            startActivity(Intent(Intent.ACTION_VIEW, issueUri))
            Toast.makeText(
                this,
                "Attach ${report.name} to the GitHub issue after the page opens",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No app available to open GitHub", Toast.LENGTH_LONG).show()
        }
    }

    private fun buildGitHubIssueBody(report: java.io.File): String {
        return """
            ## What happened
            -

            ## What you expected
            -

            ## Exact steps to reproduce
            1.
            2.
            3.

            ## Device
            - Manufacturer: ${Build.MANUFACTURER}
            - Model: ${Build.MODEL}
            - Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})

            ## Attachments
            - Attach this ZIP from BQAAgent: `${report.name}`
            - If this looks device-specific and you have ADB available, also attach `adb logcat`

            Generated by BQAAgent ${io.agents.bqaagent.BuildConfig.VERSION_NAME}.
        """.trimIndent()
    }

    private fun shareReportFile(
        report: java.io.File,
        chooserTitle: String,
        subject: String,
        body: String,
    ) {
        val uri = FileProvider.getUriForFile(
            this@SettingsActivity,
            "${packageName}.fileprovider",
            report
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(intent, chooserTitle))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this@SettingsActivity, "No app available to share the report", Toast.LENGTH_LONG).show()
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Observe settings changes and dynamically update UI
                launch {
                    viewModel.settingItems.collect { items ->
                        items.forEach { (key, value) ->
                            when (value) {
                                is SettingsViewModel.SettingValue.Text -> {
                                    menuItems[key]?.setTrailingText(value.text)
                                }
                                is SettingsViewModel.SettingValue.Switch -> {
                                    // Update switch state here if needed
                                }
                            }
                        }
                    }
                }

                // Observe H5 config changes, refresh UI and re-initialize Agent.
                launch {
                    ConfigServerManager.configChanged.collect {
                        viewModel.refresh()
                        appViewModel.initAgent()
                        appViewModel.afterInit()
                    }
                }

                // Observe menu click events
                launch {
                    viewModel.menuClickEvent.collect { action ->
                        when (action) {
                            SettingsViewModel.MenuAction.LAN_CONFIG -> {
                                val result = viewModel.toggleConfigServer(this@SettingsActivity)
                                if (result == getString(R.string.lan_config_no_wifi)) {
                                    Toast.makeText(this@SettingsActivity, R.string.lan_config_no_wifi, Toast.LENGTH_SHORT).show()
                                }
                            }
                            SettingsViewModel.MenuAction.LLM_CONFIG -> {
                                llmConfigLauncher.launch(Intent(this@SettingsActivity, LlmConfigActivity::class.java))
                            }
                            SettingsViewModel.MenuAction.VLM_CONFIG -> {
                                vlmConfigLauncher.launch(Intent(this@SettingsActivity, VlmConfigActivity::class.java))
                            }
                            null -> {}
                        }
                        viewModel.clearMenuClickEvent()
                    }
                }
            }
        }
    }

}
