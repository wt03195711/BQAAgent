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
import io.agents.bqaagent.adb.LocalAdbPairingNotification
import io.agents.bqaagent.R
import io.agents.bqaagent.base.BaseActivity
import io.agents.bqaagent.widget.AlertDialog
import io.agents.bqaagent.widget.ConfirmDialog
import io.agents.bqaagent.widget.CommonToolbar
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
        private const val SECTION_PERM_ADVANCED = "perm_advanced"
        private const val SECTION_SKILL_MGMT = "skill_mgmt"
        private const val SECTION_TASK_RECORDING = "task_recording"
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
    private var floatingWindowItem: io.agents.bqaagent.widget.MenuItem? = null
    private var skillCaptureModeItem: io.agents.bqaagent.widget.MenuItem? = null
    private var taskRecordingItem: io.agents.bqaagent.widget.MenuItem? = null
    private var recordingFilesItem: io.agents.bqaagent.widget.MenuItem? = null
    private var skillDataItem: io.agents.bqaagent.widget.MenuItem? = null
    private var localAdbSetupOpening = false
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
        refreshFloatingWindow()
        refreshSkillCaptureMode()
        refreshSkillDataCount()
        refreshTaskRecording()
        handler.removeCallbacks(permPoller)
        handler.postDelayed(permPoller, 1000)
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
        } else if (!shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
            // Second denial with "don't ask again" — the system dialog is gone for good;
            // route to notification settings instead of looping on an invisible request.
            Toast.makeText(this, R.string.local_adb_pair_notif_perm_blocked, Toast.LENGTH_LONG).show()
            AppCapabilityCoordinator.openAppNotificationSettings(this)
        } else {
            Toast.makeText(this, R.string.local_adb_notification_permission_required, Toast.LENGTH_LONG).show()
        }
    }

    private fun refreshPermissions() {
        val capabilities = AppCapabilityCoordinator.snapshot(this)
        permLocalAdb?.setTrailingText(
            when (capabilities.localAdbState) {
                ServiceBindingState.READY -> getString(R.string.perm_status_ready)
                ServiceBindingState.CONNECTING -> getString(R.string.perm_status_not_ready)
                ServiceBindingState.DEGRADED -> getString(R.string.perm_status_disconnected)
                ServiceBindingState.DISABLED -> getString(R.string.perm_status_not_ready)
            }
        )
        permNotification?.setTrailingText(
            getString(
                if (capabilities.notificationPermissionGranted) R.string.perm_status_allowed
                else R.string.perm_status_not_allowed
            )
        )
        permNotifAccess?.setTrailingText(
            when (capabilities.notificationAccessState) {
                ServiceBindingState.READY -> getString(R.string.perm_status_connected)
                ServiceBindingState.CONNECTING -> getString(R.string.perm_status_connecting)
                ServiceBindingState.DEGRADED -> getString(R.string.perm_status_disconnected)
                ServiceBindingState.DISABLED -> getString(R.string.perm_status_disabled)
            }
        )
        permOverlay?.setTrailingText(
            getString(
                if (capabilities.overlayGranted) R.string.perm_status_allowed
                else R.string.perm_status_not_allowed
            )
        )
        permBattery?.setTrailingText(
            getString(
                if (capabilities.batteryOptimizationIgnored) R.string.perm_status_unrestricted
                else R.string.perm_status_restricted
            )
        )
        permStorage?.setTrailingText(
            when {
                capabilities.storageAccessGranted -> getString(R.string.perm_status_allowed)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> getString(R.string.perm_status_not_needed)
                else -> getString(R.string.perm_status_not_allowed)
            }
        )
        updateAdvancedSummary(capabilities)
    }

    /**
     * Summary counts only what actually matters today: the notification permission is a hard
     * prerequisite for ADB pairing; overlay counts as advisory only when the floating-window
     * feature is ON; battery is advisory. Notification Access is NOT counted (the monitor guide
     * owns it) and neither is storage (not needed on Android 11+).
     */
    private fun updateAdvancedSummary(capabilities: io.agents.bqaagent.AppCapabilitySnapshot) {
        val requiredMissing = if (!capabilities.notificationPermissionGranted) 1 else 0
        val floatingNeedsOverlay = KVUtils.isFloatingWindowEnabled() && !capabilities.overlayGranted
        val advisory =
            (if (!capabilities.batteryOptimizationIgnored) 1 else 0) +
                    (if (floatingNeedsOverlay) 1 else 0)
        // Advanced summary fallback. The Overlay row's default subtitle explains the permission
        // itself; the dynamic one below cross-links it to the feature that needs it.
        permOverlay?.setSubtitle(
            if (floatingNeedsOverlay) getString(R.string.settings_perm_overlay_needed_by_floating)
            else getString(R.string.settings_perm_overlay_subtitle)
        )
        val (text, warning) = when {
            requiredMissing > 0 -> getString(R.string.settings_perm_summary_to_enable, requiredMissing) to true
            advisory > 0 -> getString(R.string.settings_perm_summary_recommended, advisory) to false
            else -> getString(R.string.settings_perm_summary_all_set) to false
        }
        findViewById<MenuGroup>(R.id.permissionsGroup).setSectionSummary(SECTION_PERM_ADVANCED, text, warning)
    }

    private fun refreshExternalAutomation() {
        externalAutomationItem?.setTrailingText(
            if (KVUtils.isExternalAutomationEnabled()) getString(R.string.settings_state_enabled)
            else getString(R.string.settings_state_disabled)
        )
    }

    private fun refreshVoiceInput() {
        voiceInputItem?.setTrailingText(
            if (KVUtils.isVoiceInputEnabled()) getString(R.string.settings_state_enabled)
            else getString(R.string.settings_state_disabled)
        )
    }

    private fun refreshSensitiveMode() {
        sensitiveModeItem?.setTrailingText(
            if (KVUtils.isSensitiveConfirmEnabled()) getString(R.string.settings_state_enabled)
            else getString(R.string.settings_state_disabled)
        )
    }

    private fun refreshFloatingWindow() {
        val enabled = KVUtils.isFloatingWindowEnabled()
        // Combo label (mirrors updateTaskRecordingLabel): a stale plain "Enabled" hides the fact
        // that the pill cannot actually show without the overlay permission.
        val overlayMissing = !android.provider.Settings.canDrawOverlays(this)
        val warning = enabled && overlayMissing
        floatingWindowItem?.setTrailingText(
            when {
                !enabled -> getString(R.string.settings_state_disabled)
                warning -> getString(R.string.settings_floating_window_no_overlay)
                else -> getString(R.string.settings_state_enabled)
            }
        )
        floatingWindowItem?.setTrailingTextColor(
            if (warning) getColor(R.color.colorErrorPrimary) else getColor(R.color.colorTextSecondary)
        )
    }

    private fun refreshSkillCaptureMode() {
        skillCaptureModeItem?.setTrailingText(
            if (KVUtils.isSkillCaptureModeEnabled()) getString(R.string.settings_state_enabled)
            else getString(R.string.settings_state_disabled)
        )
    }

    /**
     * Shows the saved-skill usage on the Skill Data row (current / max) so the cap is visible before
     * the user dives into Skill Management. Mirrors [refreshTaskRecording]: the skill library is read
     * off the main thread, and it refreshes on every onResume so save/delete changes are reflected.
     */
    private fun refreshSkillDataCount() {
        lifecycleScope.launch {
            val summary = withContext(Dispatchers.IO) {
                val count = io.agents.bqaagent.agent.skill.SkillStore.getAllSkills().size
                val max = io.agents.bqaagent.agent.skill.SkillStore.MAX_SKILLS
                getString(R.string.settings_skill_data_count, count, max)
            }
            skillDataItem?.setTrailingText(summary)
        }
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
                !enabled -> getString(R.string.settings_state_disabled)
                adbDown -> getString(R.string.settings_task_recording_adb_off)
                !abort.isNullOrBlank() -> getString(R.string.settings_task_recording_issue)
                else -> getString(R.string.settings_state_enabled)
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
                getString(R.string.settings_recordings_files_count, entries.size) +
                        " · " + TaskRecordingStore.formatBytes(bytes)
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
            Toast.makeText(this, R.string.settings_recording_disabled_toast, Toast.LENGTH_SHORT).show()
            return
        }
        if (!LocalAdbAutomation.hasConnectionConfig()) {
            AlertDialog.show(
                context = this,
                title = getString(R.string.settings_recording_adb_required_title),
                message = getString(R.string.settings_recording_adb_required_message),
                actionTitle = getString(R.string.common_got_it)
            )
            return
        }
        if (verifyingRecording || selfTestRunning) {
            Toast.makeText(this, R.string.settings_recording_checking_toast, Toast.LENGTH_SHORT).show()
            return
        }
        if (appViewModel.isTaskRunning()) {
            Toast.makeText(this, R.string.settings_recording_stop_task_toast, Toast.LENGTH_LONG).show()
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
        // TODO Hide settings groups that are not yet available
        listOf(R.id.toolsGroup, R.id.remoteGroup, R.id.aboutGroup).forEach { id ->
            findViewById<MenuGroup>(id)?.visibility = android.view.View.GONE
        }
    }

    private fun applyThemeToGroups(tc: io.agents.bqaagent.ui.chat.ThemeManager.ChatColors) {
        // skillManagerGroup / recordingGroup no longer exist as standalone cards (their items
        // live as collapsible sections inside generalGroup); iterating a group whose first child
        // is a collapsible header would repaint the header's warning-colored trailing every poll.
        val groups = listOf(
            R.id.permissionsGroup, R.id.generalGroup, R.id.channelGroup, R.id.modelGroup,
            R.id.appearanceGroup,
            R.id.toolsGroup, R.id.remoteGroup, R.id.aboutGroup
        )
        for (id in groups) {
            val g = findViewById<MenuGroup>(id) ?: continue
            g.setTitleColor(tc.aiText)
            g.setCardBackgroundColor(tc.toolbarBg)
            for (i in 0 until g.getMenuItemCount()) {
                g.getMenuItemAt(i)?.apply {
                    setTitleColor(tc.aiText)
                    setSubtitleColor(tc.toolDefault)
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
        if (KVUtils.isSensitiveConfirmEnabled()) {
            ConfirmDialog.showWarm(
                context = this,
                title = getString(R.string.sensitive_mode_disable_title),
                message = getString(R.string.sensitive_mode_disable_message),
                actionTitle = getString(R.string.sensitive_mode_disable_confirm),
                cancelTitle = getString(R.string.common_cancel),
                onAction = {
                    KVUtils.setSensitiveConfirmEnabled(false)
                    refreshSensitiveMode()
                    Toast.makeText(this, R.string.sensitive_mode_disabled, Toast.LENGTH_SHORT).show()
                }
            )
        } else {
            KVUtils.setSensitiveConfirmEnabled(true)
            refreshSensitiveMode()
            Toast.makeText(this, R.string.sensitive_mode_enabled, Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleFloatingWindow() {
        val enabled = !KVUtils.isFloatingWindowEnabled()
        KVUtils.setFloatingWindowEnabled(enabled)
        if (enabled) {
            // Turning on shows the bubble immediately (independent of any running task).
            if (!android.provider.Settings.canDrawOverlays(this)) {
                // Never block the switch itself; guide instead. Auto-jump is a proactive prompt,
                // so it obeys the 24h per-touchpoint anti-spam rule — once suppressed, the combo
                // label + hint toast carry the message and the Overlay row stays a manual tap away.
                if (KVUtils.shouldShowGuidePrompt("overlay_toggle")) {
                    KVUtils.markGuidePromptShown("overlay_toggle")
                    AppCapabilityCoordinator.openSystemSettings(this, AppRequirement.OVERLAY)
                } else {
                    Toast.makeText(this, R.string.settings_floating_window_overlay_hint, Toast.LENGTH_LONG).show()
                }
            }
            io.agents.bqaagent.floating.FloatingCircleManager.show(application)
        } else {
            // Turning off immediately hides the window currently on screen.
            io.agents.bqaagent.floating.FloatingCircleManager.hide()
        }
        refreshFloatingWindow()
        Toast.makeText(
            this,
            if (enabled) R.string.settings_floating_window_enabled_toast
            else R.string.settings_floating_window_disabled_toast,
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun toggleSkillCaptureMode() {
        val enabled = !KVUtils.isSkillCaptureModeEnabled()
        KVUtils.setSkillCaptureModeEnabled(enabled)
        refreshSkillCaptureMode()
        if (enabled) {
            AlertDialog.show(
                context = this,
                title = getString(R.string.settings_skill_capture_on_title),
                message = getString(R.string.settings_skill_capture_on_message),
                actionTitle = getString(R.string.common_got_it)
            )
        } else {
            Toast.makeText(
                this,
                R.string.settings_skill_capture_off_toast,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun showLocalAdbSetupDialog() {
        openSmartLocalAdbEntry()
    }

    /**
     * Inline how-to for first-time users: pairing Local ADB over wireless debugging is not obvious
     * on a phone, so the info button next to the row walks them through the exact steps.
     */
    private fun showLocalAdbPairingGuide() {
        AlertDialog.show(
            context = this,
            title = getString(R.string.local_adb_guide_title),
            message = getString(R.string.local_adb_guide_message),
            actionTitle = getString(R.string.common_got_it),
            messageAlignStart = true,
            messageTextSizeDp = 13f,
            messageMaxLines = 18,
        )
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
        permLocalAdb?.setTrailingText(getString(R.string.perm_status_not_ready))
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
        // Step 0: the pairing code is delivered through a notification, so a blocked notification
        // permission silently kills the flow later. Gate it upfront, with a permanent-denial
        // escape hatch — never just a dead-end toast.
        ensureNotificationPermissionForPairing { continueLocalAdbPairingFlow() }
    }

    private fun ensureNotificationPermissionForPairing(proceed: () -> Unit) {
        if (AppCapabilityCoordinator.isNotificationPermissionGranted(this)) {
            proceed()
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            // No runtime notification permission below 13; notification visibility is decided
            // at channel level and handled by showLocalAdbPairingNotification's failure path.
            proceed()
            return
        }
        if (KVUtils.isNotifPermAskedBefore() &&
            !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            Toast.makeText(this, R.string.local_adb_pair_notif_perm_blocked, Toast.LENGTH_LONG).show()
            AppCapabilityCoordinator.openAppNotificationSettings(this)
            return
        }
        KVUtils.markNotifPermAsked()
        pendingLocalAdbNotificationPermission = true
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_POST_NOTIFICATIONS)
    }

    private fun continueLocalAdbPairingFlow() {
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

    private fun initMenuGroups() {
        // Permissions
        val permissionsGroup = findViewById<MenuGroup>(R.id.permissionsGroup)
        permissionsGroup.setTitle(getString(R.string.settings_group_permissions))

        permLocalAdb = permissionsGroup.addMenuItem(
            leadingIcon = R.drawable.ic_local_adb,
            title = getString(R.string.home_card_local_adb_title),
            subtitle = getString(R.string.home_card_local_adb_subtitle),
            onClick = {
                showLocalAdbSetupDialog()
            },
            onInfoClick = {
                showLocalAdbPairingGuide()
            },
            showDivider = true
        )
        permLocalAdb?.setInfoButtonContentDescription(getString(R.string.local_adb_guide_content_desc))

        permissionsGroup.beginCollapsibleSection(
            sectionId = SECTION_PERM_ADVANCED,
            title = getString(R.string.settings_perm_advanced_title),
            headerIcon = R.drawable.ic_settings,
        ) { expanded ->
            KVUtils.setPermAdvancedExpanded(expanded)
        }
        permissionsGroup.setSectionExpanded(SECTION_PERM_ADVANCED, KVUtils.isPermAdvancedExpanded())

        permNotification = permissionsGroup.addMenuItem(
            leadingIcon = R.drawable.ic_notification,
            title = getString(R.string.home_card_notification_title),
            subtitle = getString(R.string.home_card_notification_subtitle),
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
        )

        permNotifAccess = permissionsGroup.addMenuItem(
            leadingIcon = R.drawable.ic_notification,
            title = getString(R.string.settings_perm_notif_access_title),
            subtitle = getString(R.string.settings_perm_notif_access_subtitle),
            onClick = {
                AppCapabilityCoordinator.openSystemSettings(this, AppRequirement.NOTIFICATION_ACCESS)
            },
            showDivider = true
        )

        permOverlay = permissionsGroup.addMenuItem(
            leadingIcon = R.drawable.ic_window,
            title = getString(R.string.settings_perm_overlay_title),
            subtitle = getString(R.string.settings_perm_overlay_subtitle),
            onClick = {
                if (AppCapabilityCoordinator.snapshot(this@SettingsActivity).overlayGranted) {
                    Toast.makeText(this@SettingsActivity, R.string.settings_perm_overlay_already_granted, Toast.LENGTH_SHORT).show()
                } else {
                    AppCapabilityCoordinator.openSystemSettings(this@SettingsActivity, AppRequirement.OVERLAY)
                }
            },
            showDivider = true
        )

        permBattery = permissionsGroup.addMenuItem(
            leadingIcon = R.drawable.ic_battery,
            title = getString(R.string.home_card_battery_title),
            subtitle = getString(R.string.home_card_battery_subtitle),
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
            subtitle = getString(R.string.home_card_storage_subtitle),
            onClick = {
                if (AppCapabilityCoordinator.snapshot(this@SettingsActivity).storageAccessGranted) {
                    Toast.makeText(this@SettingsActivity, R.string.home_storage_enabled, Toast.LENGTH_SHORT).show()
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // On Android 11+ the app never needs this; explain instead of pushing to a
                    // scary system page (see refreshPermissions: label reads "Not needed").
                    Toast.makeText(this@SettingsActivity, R.string.perm_status_not_needed, Toast.LENGTH_SHORT).show()
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
            title = getString(R.string.settings_vision_model_title),
            onClick = { viewModel.onMenuItemClick(SettingsViewModel.MenuAction.VLM_CONFIG) },
            showDivider = false
        )
        menuItems[SettingsViewModel.MenuAction.VLM_CONFIG.name]?.setLeadingIconColor(getColor(R.color.colorTextPrimary))

        // General
        val generalGroup = findViewById<MenuGroup>(R.id.generalGroup)
        generalGroup.setTitle(getString(R.string.settings_group_general))

        sensitiveModeItem = generalGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_lock_lock,
            title = getString(R.string.sensitive_mode_title),
            subtitle = getString(R.string.settings_sensitive_mode_subtitle),
            onClick = { toggleSensitiveMode() },
            showDivider = true
        ).apply {
            setTrailingText(
                if (KVUtils.isSensitiveConfirmEnabled()) getString(R.string.settings_state_enabled)
                else getString(R.string.settings_state_disabled)
            )
        }

        floatingWindowItem = generalGroup.addMenuItem(
            leadingIcon = R.drawable.ic_window,
            title = getString(R.string.settings_floating_window_title),
            subtitle = getString(R.string.settings_floating_window_subtitle),
            onClick = { toggleFloatingWindow() },
            showDivider = true
        ).apply {
            setTrailingText(if (KVUtils.isFloatingWindowEnabled()) getString(R.string.settings_state_enabled) else getString(R.string.settings_state_disabled))
        }

        voiceInputItem = generalGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_btn_speak_now,
            title = getString(R.string.settings_voice_input_title),
            onClick = { toggleVoiceInput() },
            showDivider = true
        ).apply {
            setTrailingText(
                if (KVUtils.isVoiceInputEnabled()) getString(R.string.settings_state_enabled)
                else getString(R.string.settings_state_disabled)
            )
        }

        generalGroup.beginCollapsibleSection(
            sectionId = SECTION_SKILL_MGMT,
            title = getString(R.string.settings_group_skill_management),
            headerIcon = android.R.drawable.ic_menu_agenda,
        ) { expanded ->
            KVUtils.setSectionExpanded(SECTION_SKILL_MGMT, expanded)
        }
        generalGroup.setSectionExpanded(SECTION_SKILL_MGMT, KVUtils.isSectionExpanded(SECTION_SKILL_MGMT))

        skillCaptureModeItem = generalGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_save,
            title = getString(R.string.settings_skill_capture_mode_title),
            onClick = { toggleSkillCaptureMode() },
            showDivider = true
        ).apply {
            setTrailingText(
                if (KVUtils.isSkillCaptureModeEnabled()) getString(R.string.settings_state_enabled)
                else getString(R.string.settings_state_disabled)
            )
        }

        skillDataItem = generalGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_agenda,
            title = getString(R.string.settings_skill_data_title),
            onClick = {
                startActivity(Intent(this, SkillManagerActivity::class.java))
            },
            showDivider = true
        ).apply {
            setTrailingText(
                getString(
                    R.string.settings_skill_data_count,
                    io.agents.bqaagent.agent.skill.SkillStore.getAllSkills().size,
                    io.agents.bqaagent.agent.skill.SkillStore.MAX_SKILLS
                )
            )
        }
        generalGroup.endCollapsibleSection()

        generalGroup.beginCollapsibleSection(
            sectionId = SECTION_TASK_RECORDING,
            title = getString(R.string.settings_task_recording_title),
            headerIcon = android.R.drawable.ic_menu_manage,
        ) { expanded ->
            KVUtils.setSectionExpanded(SECTION_TASK_RECORDING, expanded)
        }
        generalGroup.setSectionExpanded(SECTION_TASK_RECORDING, KVUtils.isSectionExpanded(SECTION_TASK_RECORDING))

        taskRecordingItem = generalGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_camera,
            title = getString(R.string.settings_task_recording_enable_title),
            onClick = { toggleTaskRecording() },
            showDivider = true
        ).apply {
            setTrailingText(
                if (KVUtils.isTaskRecordingEnabled()) getString(R.string.settings_state_enabled)
                else getString(R.string.settings_state_disabled)
            )
        }

        recordingFilesItem = generalGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_gallery,
            title = getString(R.string.settings_recorded_files_title),
            onClick = {
                startActivity(
                    Intent(this, io.agents.bqaagent.ui.recording.RecordingManagerActivity::class.java)
                )
            },
            showDivider = false
        ).apply {
            setTrailingText(getString(R.string.settings_recordings_files_count, 0))
            // Support tool, not a user flow. Everything a user needs is now covered by the
            // verification gate in toggleTaskRecording(), so the self-test moves behind a
            // long-press: it stays reachable for diagnostics without asking anyone to run it.
            setOnLongClickListener {
                runRecordingSelfTest()
                true
            }
        }
        generalGroup.endCollapsibleSection()

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
            showDivider = false
        ).apply {
            setTrailingText(currentLanguageLabel())
        }

        // Tool
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
