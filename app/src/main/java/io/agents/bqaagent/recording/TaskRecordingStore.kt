// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.recording

import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.StatFs
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import io.agents.bqaagent.ClawApplication
import io.agents.bqaagent.utils.XLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Owns everything on disk: `{externalFilesDir}/recordings/rec_{ts}_{id}/`.
 *
 * Layout per recording:
 *   manifest.json
 *   seg_000.mp4 / seg_000.log
 *   seg_001.mp4 / seg_001.log
 *   ...
 */
object TaskRecordingStore {

    private const val TAG = "TaskRecordingStore"
    private const val DIR_PREFIX = "rec_"
    private const val PROBE_PREFIX = "probe_"
    private const val MANIFEST_NAME = "manifest.json"
    private const val SEGMENT_PREFIX = "seg_"

    private const val MAX_TOTAL_BYTES = 800L * 1024 * 1024
    private const val WARN_TOTAL_BYTES = 750L * 1024 * 1024
    private const val MIN_FREE_BYTES = 300L * 1024 * 1024

    private val gson: Gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private val nameFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    fun rootDir(): File? {
        val base = runCatching { ClawApplication.instance.getExternalFilesDir(null) }.getOrNull() ?: return null
        return File(base, "recordings").apply { if (!exists()) mkdirs() }
    }

    fun createRecordingDir(): File? {
        val root = rootDir() ?: return null
        val id = System.currentTimeMillis().toString(36)
        val dir = File(root, "${DIR_PREFIX}${nameFormat.format(Date())}_$id")
        return if (dir.mkdirs() || dir.exists()) dir else null
    }

    /**
     * One-shot probe folder for switch verification (detectWrapper / probeOverlap). Uses a `probe_`
     * prefix so listRecordings() — which only matches `rec_` — never surfaces a transient probe entry
     * in the Recorded Files list while the toggle is being checked. Same intent as RecordingSelfTest's
     * separate "selftest" subdir. Callers delete it right after the probe finishes.
     */
    fun createProbeDir(): File? {
        val root = rootDir() ?: return null
        val id = System.currentTimeMillis().toString(36)
        val dir = File(root, "${PROBE_PREFIX}${nameFormat.format(Date())}_$id")
        return if (dir.mkdirs() || dir.exists()) dir else null
    }

    fun segmentFile(dir: File, index: Int): File =
        File(dir, "$SEGMENT_PREFIX%03d.mp4".format(index))

    fun segmentLogFile(dir: File, index: Int): File =
        File(dir, "$SEGMENT_PREFIX%03d.log".format(index))

    fun manifestFile(dir: File): File = File(dir, MANIFEST_NAME)

    fun writeManifest(dir: File, manifest: TaskRecordingManifest) {
        try {
            manifestFile(dir).writeText(gson.toJson(manifest))
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to write manifest in ${dir.name}", e)
        }
    }

    fun readManifest(dir: File): TaskRecordingManifest? {
        val file = manifestFile(dir)
        if (!file.exists()) return null
        return try {
            gson.fromJson(file.readText(), TaskRecordingManifest::class.java)
        } catch (e: Exception) {
            XLog.w(TAG, "Failed to parse manifest in ${dir.name}: ${e.message}")
            null
        }
    }

    /** Validates a finished segment; returns null when the file is missing, empty or unplayable. */
    fun probeVideo(file: File): VideoProbe? = probeVideoDetailed(file).first

    /**
     * Same gate as [probeVideo] but reports *why* a file was rejected. "duration=0 alongside a moov
     * box" means the muxer finalised a recording with no samples; "setDataSource threw" means the
     * container is truncated or unreadable. Callers that only need the verdict use [probeVideo].
     */
    fun probeVideoDetailed(file: File): Pair<VideoProbe?, String> {
        if (!file.exists()) return null to "missing"
        val len = file.length()
        if (len <= 0) return null to "empty(0B)"
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: -1L
            val width = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            if (durationMs <= 0L || width <= 0 || height <= 0) {
                val reason = "metadata unusable: duration=${durationMs}ms size=${width}x${height}"
                XLog.w(TAG, "probeVideo rejected ${file.name}: $reason (${len}B)")
                null to reason
            } else {
                VideoProbe(durationMs, width, height, len) to "ok"
            }
        } catch (e: Exception) {
            val reason = "setDataSource threw ${e.javaClass.simpleName}: ${e.message?.take(90)}"
            XLog.w(TAG, "probeVideo failed for ${file.name}: $reason")
            null to reason
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** Re-scans a directory and keeps only the segments that are actually playable. */
    fun collectValidSegments(dir: File): MutableList<RecordingSegmentInfo> {
        val result = mutableListOf<RecordingSegmentInfo>()
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith(SEGMENT_PREFIX) && f.name.endsWith(".mp4") }
            ?.sortedBy { it.name } ?: return result.toMutableList()
        files.forEachIndexed { index, file ->
            val probe = probeVideo(file)
            result.add(
                RecordingSegmentInfo(
                    index = index,
                    fileName = file.name,
                    startedAt = 0L,
                    endedAt = 0L,
                    sizeBytes = file.length(),
                    durationMs = probe?.durationMs ?: 0L,
                    width = probe?.width ?: 0,
                    height = probe?.height ?: 0,
                    valid = probe != null
                )
            )
        }
        return result
    }

    data class Entry(val dir: File, val manifest: TaskRecordingManifest?, val updatedAt: Long)

    fun listRecordings(): List<Entry> {
        val root = rootDir() ?: return emptyList()
        val dirs = root.listFiles { f -> f.isDirectory && f.name.startsWith(DIR_PREFIX) } ?: return emptyList()
        return dirs.map { dir ->
            Entry(dir, readManifest(dir), dir.lastModified())
        }.sortedByDescending { it.updatedAt }
    }

    /**
     * Resolves a recordingId back to its folder. recordingId IS the directory name
     * (TaskRecordingCoordinator L349), so this is a single path join — no scan.
     * Null when retention or a manual delete removed it, which the player turns into an explanation.
     */
    fun findRecording(recordingId: String): File? {
        if (recordingId.isBlank()) return null
        val root = rootDir() ?: return null
        val dir = File(root, recordingId)
        return if (dir.isDirectory) dir else null
    }

    fun segmentFilesOf(dir: File): List<File> {
        val manifest = readManifest(dir)
        val names = manifest?.segments?.filter { it.valid }?.map { it.fileName }
        return if (!names.isNullOrEmpty()) {
            names.map { File(dir, it) }.filter { it.exists() && it.length() > 0 }
        } else {
            collectValidSegments(dir).map { File(dir, it.fileName) }.filter { it.exists() }
        }
    }

    fun deleteRecording(dir: File) {
        runCatching { dir.deleteRecursively() }
            .onFailure { XLog.w(TAG, "Failed to delete ${dir.name}: ${it.message}") }
    }

    fun totalBytes(): Long = listRecordings().sumOf { dirSize(it.dir) }

    fun dirSize(dir: File): Long =
        dir.listFiles()?.sumOf { if (it.isDirectory) dirSize(it) else it.length() } ?: 0L

    fun freeSpaceBytes(): Long {
        val root = rootDir() ?: return 0L
        return try {
            StatFs(root.absolutePath).availableBytes
        } catch (e: Exception) {
            0L
        }
    }

    fun hasRoomForRecording(): Boolean = freeSpaceBytes() > MIN_FREE_BYTES

    /** Hard storage cap for retained recordings (800MB). Exposed so the manager UI can show it. */
    fun maxTotalBytes(): Long = MAX_TOTAL_BYTES

    /** True once stored recordings cross the soft warning line (750MB of the 800MB cap). */
    fun isNearCapacity(): Boolean = totalBytes() >= WARN_TOTAL_BYTES

    /**
     * Keeps total stored size under MAX_TOTAL_BYTES (800MB) by dropping WHOLE recordings, oldest
     * first. One task's folder (manifest + all its segments) is the unit of both accounting and
     * deletion — never a single segment. The most recent recording is always kept, even if it alone
     * exceeds the cap, so a just-saved recording is never pruned by its own write.
     *
     * Count and age limits were removed by design: total size is the only retention rule now.
     * MIN_FREE_BYTES stays separate — it is the "don't start when the disk is nearly full" admission
     * gate (hasRoomForRecording), not a retention rule.
     */
    fun enforceRetention() {
        val entries = listRecordings()          // newest first (listRecordings sorts by updatedAt desc)
        if (entries.size <= 1) return
        var bytes = entries.sumOf { dirSize(it.dir) }
        if (bytes <= MAX_TOTAL_BYTES) return
        var removed = 0
        // asReversed() = oldest -> newest; dropLast(1) excludes the newest so it is never deleted.
        for (entry in entries.asReversed().dropLast(1)) {
            if (bytes <= MAX_TOTAL_BYTES) break
            val size = dirSize(entry.dir)
            deleteRecording(entry.dir)
            bytes -= size
            removed++
        }
        if (removed > 0) {
            XLog.i(TAG, "Retention removed $removed oldest recording(s), now ${bytes / 1024 / 1024}MB")
        }
    }

    fun deviceModel(): String = "${Build.MANUFACTURER} ${Build.MODEL}"

    /**
     * Stable per-device identity used to scope the persisted recording configuration.
     * Mirrors LocalBackendHealth.currentDeviceKey() (L31-37) — duplicated on purpose, because the
     * recording module must not depend on agent/llm. A cached wrapper carried over by a backup
     * restore or a phone change would otherwise be trusted forever: doStart() (TaskRecordingCoordinator
     * L207) only re-probes when the cache is blank.
     */
    fun deviceKey(): String {
        val fingerprint = Build.FINGERPRINT?.trim().orEmpty()
        if (fingerprint.isNotEmpty()) return fingerprint
        return listOf(Build.MANUFACTURER, Build.MODEL, Build.DEVICE, Build.HARDWARE)
            .filter { !it.isNullOrBlank() }
            .joinToString("|")
    }

    fun androidVersion(): String = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

    fun appVersion(): String = runCatching { io.agents.bqaagent.BuildConfig.VERSION_NAME }.getOrDefault("unknown")

    fun formatBytes(bytes: Long): String = when {
        bytes >= 1024 * 1024 * 1024 -> "%.2f GB".format(bytes / 1024.0 / 1024 / 1024)
        bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024)
        bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    fun formatDuration(ms: Long): String {
        if (ms <= 0) return "0s"
        val totalSec = ms / 1000
        val m = totalSec / 60
        val s = totalSec % 60
        return if (m > 0) "${m}m${s}s" else "${s}s"
    }
}