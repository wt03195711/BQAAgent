// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.ui.recording

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.agents.bqaagent.R
import io.agents.bqaagent.base.BaseActivity
import io.agents.bqaagent.recording.TaskRecordingCoordinator
import io.agents.bqaagent.recording.TaskRecordingStore
import io.agents.bqaagent.ui.chat.ThemeManager
import io.agents.bqaagent.utils.XLog
import io.agents.bqaagent.widget.AlertDialog
import io.agents.bqaagent.widget.CommonToolbar
import io.agents.bqaagent.widget.ConfirmDialog
import io.agents.bqaagent.widget.LoadingDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lists every stored task recording, newest first, and ties each one back to the task that produced
 * it (task text + outcome + timestamp) — exactly what the raw folder name cannot convey.
 *
 * Replaces the old single-newest summary dialog (SettingsActivity.showRecordingsSummary), which
 * could only ever show or share entries.first() while many recordings are retained.
 *
 * All disk I/O (manifest parse, segment stat, size sum) runs on Dispatchers.IO before rows reach the
 * adapter, so scrolling never touches storage.
 */
class RecordingManagerActivity : BaseActivity() {

    private companion object {
        private const val TAG = "RecordingManager"
    }

    private lateinit var adapter: RecordingManagerAdapter
    private lateinit var tvSummary: TextView
    private lateinit var tvWarning: TextView
    private lateinit var tvEmpty: TextView
    private val metaDateFormat = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tc = ThemeManager.getColors()
        window.statusBarColor = tc.toolbarBg
        window.decorView.setBackgroundColor(tc.bg)
        setContentView(R.layout.activity_recording_manager)

        val contentFrame = findViewById<ViewGroup>(android.R.id.content)
        contentFrame?.setBackgroundColor(tc.bg)
        (contentFrame?.getChildAt(0) as? View)?.setBackgroundColor(tc.bg)

        findViewById<CommonToolbar>(R.id.toolbar).apply {
            setTitle("Recorded Files")
            setTitleColor(tc.aiText)
            setBackgroundColor(tc.toolbarBg)
            showBackButton(true) { finish() }
            findViewById<ImageView>(R.id.ivBack)?.setColorFilter(tc.aiText)
        }

        tvSummary = findViewById(R.id.tvSummary)
        tvWarning = findViewById(R.id.tvWarning)
        tvEmpty = findViewById(R.id.tvEmpty)
        tvSummary.setTextColor(tc.toolDefault)

        adapter = RecordingManagerAdapter(
            onPlay = { row -> RecordingPlaybackActivity.start(this, row.recordingId) },
            onSaveToGallery = { row -> saveToGallery(row) },
            onShare = { row -> shareSegments(row.segmentFiles) },
            onDelete = { row -> confirmDelete(row) },
            onDetails = { row -> showDetails(row) }
        )
        findViewById<RecyclerView>(R.id.rvRecordings).apply {
            layoutManager = LinearLayoutManager(this@RecordingManagerActivity)
            adapter = this@RecordingManagerActivity.adapter
        }
        // No loadData() here: onResume always follows onCreate and does the first load, so listing it
        // twice would just scan the folder two times on launch.
    }

    /**
     * Full, untruncated view of one recording. The list row elides tvTask (2 lines) and tvMeta/tvNote
     * (1 line each); this dialog is the only place the complete task text and metadata can be read.
     * messageMaxLines caps the height and makes the body scroll internally for very long tasks.
     */
    private fun showDetails(row: RecordingRow) {
        AlertDialog.show(
            context = this,
            title = "Recording details",
            message = buildString {
                append("Task\n").append(row.taskText).append("\n\n")
                append(row.metaLine)
                if (row.noteLine.isNotBlank()) {
                    append("\n\nID / status\n").append(row.noteLine)
                }
            },
            actionTitle = "Close",
            messageAlignStart = true,
            messageMaxLines = 14
        )
    }

    override fun onResume() {
        super.onResume()
        // A recording may have finished — or retention pruned folders — while this screen was in the
        // background, so re-scan on every return instead of showing a stale list.
        if (::adapter.isInitialized) loadData()
    }

    private fun loadData() {
        lifecycleScope.launch {
            val snapshot = withContext(Dispatchers.IO) { buildSnapshot() }
            if (isFinishing || isDestroyed) return@launch
            adapter.submit(snapshot.rows)
            tvSummary.text = snapshot.summary
            tvWarning.text = snapshot.warning.orEmpty()
            tvWarning.visibility = if (snapshot.warning != null) View.VISIBLE else View.GONE
            tvEmpty.visibility = if (snapshot.rows.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private data class Snapshot(val rows: List<RecordingRow>, val summary: String, val warning: String?)

    private fun buildSnapshot(): Snapshot {
        val entries = TaskRecordingStore.listRecordings()
        val rows = entries.map { buildRow(it) }
        val totalBytes = entries.sumOf { TaskRecordingStore.dirSize(it.dir) }
        val summary = buildString {
            append(rows.size).append(if (rows.size == 1) " recording · " else " recordings · ")
            append(TaskRecordingStore.formatBytes(totalBytes))
            append(" of ").append(TaskRecordingStore.formatBytes(TaskRecordingStore.maxTotalBytes()))
            append("\nWhen the limit is reached, the oldest recordings are deleted automatically.")
        }
        // Soft warning line (750MB of the 800MB cap): tell the user BEFORE auto-deletion starts
        // removing their oldest recordings, so they can export or delete what matters first.
        val warning = if (TaskRecordingStore.isNearCapacity()) {
            "Storage almost full — " +
                    "${TaskRecordingStore.formatBytes(totalBytes)} of " +
                    "${TaskRecordingStore.formatBytes(TaskRecordingStore.maxTotalBytes())} used. " +
                    "Once the limit is reached, the oldest recordings are deleted automatically to " +
                    "make room for new ones. Consider removing recordings you no longer need."
        } else {
            null
        }
        return Snapshot(rows, summary, warning)
    }

    private fun buildRow(entry: TaskRecordingStore.Entry): RecordingRow {
        val dir = entry.dir
        val manifest = entry.manifest
        val recordingId = manifest?.recordingId?.ifBlank { dir.name } ?: dir.name
        val segments = TaskRecordingStore.segmentFilesOf(dir)
        val taskText = manifest?.taskText?.takeIf { it.isNotBlank() } ?: "(no task text)"

        val metaLine = buildString {
            append(metaDateFormat.format(Date(entry.updatedAt)))
            append(" · ").append(TaskRecordingStore.formatDuration(manifest?.totalDurationMs ?: 0L))
            append(" · ").append(segments.size).append(if (segments.size == 1) " part" else " parts")
            append(" · ").append(TaskRecordingStore.formatBytes(TaskRecordingStore.dirSize(dir)))
            append(" · ").append((manifest?.outcome ?: "unknown").lowercase())
        }

        // Flag only anomalies, but always carry the id so a problem folder can be pulled by name over
        // adb. abortedReason is mapped through the same humanReason() the Settings gate uses, so the
        // wording never diverges between "why it won't enable" and "why this one stopped early".
        val anomaly = when {
            !manifest?.abortedReason.isNullOrBlank() ->
                "stopped early: " + TaskRecordingCoordinator.humanReason(manifest?.abortedReason)
            (manifest?.gapCount ?: 0) > 0 -> "${manifest?.gapCount} gap(s) between segments"
            segments.isEmpty() -> "no playable segment"
            else -> ""
        }
        val noteLine = if (anomaly.isBlank()) recordingId else "$recordingId · $anomaly"

        return RecordingRow(
            recordingId = recordingId,
            taskText = taskText,
            metaLine = metaLine,
            noteLine = noteLine,
            segmentFiles = segments,
            updatedAt = entry.updatedAt
        )
    }

    private fun confirmDelete(row: RecordingRow) {
        ConfirmDialog.showWarm(
            context = this,
            title = "Delete recording",
            message = "Delete this recording?\n\n${row.taskText.take(60)}\n\n" +
                    "${row.segmentFiles.size} file(s) will be removed. This cannot be undone.",
            actionTitle = "Delete",
            cancelTitle = getString(R.string.common_cancel),
            onAction = {
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        val dir = TaskRecordingStore.findRecording(row.recordingId)
                        if (dir != null) {
                            TaskRecordingStore.deleteRecording(dir)
                            !dir.exists()
                        } else {
                            false
                        }
                    }
                    if (isFinishing || isDestroyed) return@launch
                    Toast.makeText(
                        this@RecordingManagerActivity,
                        if (ok) "Recording deleted" else "Could not delete recording",
                        Toast.LENGTH_SHORT
                    ).show()
                    loadData()
                }
            }
        )
    }

    private fun shareSegments(files: List<File>) {
        if (files.isEmpty()) return
        if (files.size == 1) {
            launchShare(files)
            return
        }
        // Multiple segments: merge into one continuous file first, so Share hands over the same single
        // video as Save to Photos instead of a pile of ~2-minute parts.
        val loading = LoadingDialog.show(this, "Preparing recording…")
        lifecycleScope.launch {
            val merged = runCatching {
                withContext(Dispatchers.IO) { RecordingExporter.mergeToShareableTemp(files) }
            }.getOrNull()
            runCatching { loading.dismiss() }
            if (isFinishing || isDestroyed) return@launch
            launchShare(if (merged != null) listOf(merged) else files)
        }
    }

    private fun launchShare(files: List<File>) {
        if (files.isEmpty()) return
        val uris = files.map { FileProvider.getUriForFile(this, "${packageName}.fileprovider", it) }
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply { putExtra(Intent.EXTRA_STREAM, uris.first()) }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
        }.apply {
            type = "video/mp4"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(intent, "Share recording"))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No app available to share the recording", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveToGallery(row: RecordingRow) {
        val files = row.segmentFiles
        if (files.isEmpty()) return
        if (!RecordingExporter.hasPermission(this)) {
            Toast.makeText(
                this,
                "Storage permission is required on this Android version",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val loading = LoadingDialog.show(this, "Saving to Photos…")
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { RecordingExporter.export(files, row.taskText) }
            }
            runCatching { loading.dismiss() }
            if (isFinishing || isDestroyed) return@launch
            result.onFailure { e ->
                XLog.w(TAG, "Gallery export failed: ${e.message}")
                Toast.makeText(
                    this@RecordingManagerActivity, "Save failed: ${e.message}", Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            val outcome = result.getOrThrow()
            AlertDialog.show(
                context = this@RecordingManagerActivity,
                title = if (outcome.allOk) "Saved to Photos" else "Partly saved",
                message = buildString {
                    if (outcome.merged) {
                        append("Saved as one continuous video to Movies/BQAAgent.\n\n")
                    } else {
                        append(outcome.copied).append(" file(s) saved to Movies/BQAAgent.\n\n")
                        if (outcome.copied > 1) {
                            append("The segments could not be merged losslessly (their formats ")
                            append("differ), so each ~2 minute part was saved separately. Use the ")
                            append("in-app player for one continuous timeline.\n\n")
                        }
                    }
                    if (outcome.failed.isNotEmpty()) {
                        append("Failed:\n").append(outcome.failed.joinToString("\n").take(400))
                    }
                },
                actionTitle = "Got it",
                messageAlignStart = true
            )
        }
    }
}