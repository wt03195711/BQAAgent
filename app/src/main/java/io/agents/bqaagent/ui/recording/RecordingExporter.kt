package io.agents.bqaagent.ui.recording

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import io.agents.bqaagent.utils.XLog
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Copies finished recordings into the system gallery (Movies/BQAAgent).
 *
 * When a task produced several segments, export() first tries a LOSSLESS merge: all segments come
 * from the same screenrecord run with identical codec/size and no audio track, so their encoded
 * samples can be concatenated with MediaExtractor + MediaMuxer (copy only, no re-encode) into one
 * continuous mp4. If any segment's format differs (e.g. a runtime resolution degrade) or the merge
 * throws, it falls back to saving each segment separately — the export never fails just because a
 * merge was impossible.
 *
 * Overlap note: with near-seamless rotation the segment boundary carries ~1s of duplicated frames.
 * This merge concatenates samples as-is (a lossless cut can only land on a keyframe, which
 * screenrecord emits ~once per second), so the seam may briefly replay. The in-app player trims it.
 */
object RecordingExporter {

    private const val TAG = "RecordingExporter"
    private const val GALLERY_DIR = "BQAAgent"

    /** Sample copy buffer for the lossless merge; screenrecord frames are well under 1MB. */
    private const val MERGE_BUFFER_BYTES = 1024 * 1024

    /** ~1 frame at 30fps, inserted between segments so PTS stays strictly increasing at the seam. */
    private const val MERGE_FRAME_GAP_US = 33_333L

    data class Result(val copied: Int, val failed: List<String>, val merged: Boolean = false) {
        val allOk: Boolean get() = failed.isEmpty() && copied > 0
    }

    /** API 28 still needs the runtime permission; API 29+ uses scoped MediaStore and needs none. */
    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
                ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED

    fun export(files: List<File>, displayNamePrefix: String): Result {
        val resolver = io.agents.bqaagent.ClawApplication.instance.contentResolver
        val prefix = sanitize(displayNamePrefix)

        // Prefer a single losslessly-merged file. Any incompatibility or failure falls through to the
        // per-segment export below, so merging can only ever help, never break the save.
        if (files.size > 1) {
            val merged = mergeToTemp(files)
            if (merged != null) {
                try {
                    copyOne(resolver, merged, "$prefix.mp4")
                    return Result(copied = 1, failed = emptyList(), merged = true)
                } catch (e: Throwable) {
                    XLog.w(TAG, "Merged gallery copy failed, falling back to segments: ${e.message}")
                } finally {
                    runCatching { merged.delete() }
                }
            }
        }

        var copied = 0
        val failed = mutableListOf<String>()
        files.forEachIndexed { index, file ->
            val name = if (files.size == 1) "$prefix.mp4" else "${prefix}_part${index + 1}.mp4"
            runCatching { copyOne(resolver, file, name) }
                .onSuccess { copied++ }
                .onFailure {
                    XLog.w(TAG, "Export failed for ${file.name}: ${it.message}")
                    failed.add("${file.name}: ${it.message ?: it.javaClass.simpleName}")
                }
        }
        return Result(copied, failed)
    }

    /**
     * Merges all segments into one temp mp4 in cacheDir. Returns null when a lossless merge is not
     * possible (differing formats, no video track, any error), signalling the caller to fall back to
     * per-segment export. The caller owns and deletes the returned file.
     */
    private fun mergeToTemp(sources: List<File>): File? {
        val out = File(
            io.agents.bqaagent.ClawApplication.instance.cacheDir,
            "merge_${System.currentTimeMillis()}.mp4"
        )
        return try {
            if (mergeLossless(sources, out) && out.exists() && out.length() > 0L) out
            else {
                runCatching { out.delete() }
                null
            }
        } catch (e: Throwable) {
            XLog.w(TAG, "Merge to temp failed: ${e.message}")
            runCatching { out.delete() }
            null
        }
    }

    /**
     * Merges segments into ONE shareable file under cacheDir/share, so "Share" hands over a single
     * continuous video exactly like "Save to Photos" does. Returns the lone source file when there is
     * only one segment, or null when a lossless merge is impossible (differing formats) — the caller
     * then falls back to sharing the raw segments. The result lives in cacheDir (exposed to
     * FileProvider by file_paths.xml) and is deliberately NOT deleted here, because the receiving app
     * reads it after we return; stale copies are pruned by age on the next call instead.
     */
    fun mergeToShareableTemp(files: List<File>): File? {
        if (files.isEmpty()) return null
        if (files.size == 1) return files.first()
        val dir = File(io.agents.bqaagent.ClawApplication.instance.cacheDir, "share").apply { mkdirs() }
        // A shared file only needs to outlive the receiving app's read, so drop anything older than
        // 30 min. Recent files are left alone in case a previous share is still in flight.
        val cutoff = System.currentTimeMillis() - 30L * 60 * 1000
        runCatching {
            dir.listFiles()?.forEach { if (it.isFile && it.lastModified() < cutoff) it.delete() }
        }
        val out = File(dir, "share_${System.currentTimeMillis()}.mp4")
        return try {
            if (mergeLossless(files, out) && out.exists() && out.length() > 0L) out
            else {
                runCatching { out.delete() }
                null
            }
        } catch (e: Throwable) {
            XLog.w(TAG, "Share merge failed: ${e.message}")
            runCatching { out.delete() }
            null
        }
    }

    /**
     * Sample-level concat: reads each segment's encoded video samples and rewrites them into one
     * muxer with a cumulative PTS offset — no decode, no re-encode, no quality loss. Bails (false)
     * the moment a segment's format differs from the first, because a lossless concat requires
     * identical mime/width/height/rotation across all inputs.
     */
    private fun mergeLossless(sources: List<File>, output: File): Boolean {
        if (sources.size < 2) return false
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val buffer = ByteBuffer.allocate(MERGE_BUFFER_BYTES)
        val info = MediaCodec.BufferInfo()
        var muxerStarted = false
        var baseFormat: MediaFormat? = null
        var trackIndex = -1
        var writeOffsetUs = 0L
        var lastPtsUs = -1L
        var ok = false
        try {
            for (file in sources) {
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(file.absolutePath)
                    val videoTrack = selectVideoTrack(extractor) ?: return false
                    val format = extractor.getTrackFormat(videoTrack)
                    extractor.selectTrack(videoTrack)
                    if (!muxerStarted) {
                        baseFormat = format
                        trackIndex = muxer.addTrack(format)
                        muxer.start()
                        muxerStarted = true
                    } else if (!sameVideoFormat(baseFormat!!, format)) {
                        // Resolution/rotation changed mid-recording: a lossless concat is impossible.
                        XLog.i(TAG, "Merge aborted: segment format differs from the first")
                        return false
                    }
                    var firstSampleUs = Long.MIN_VALUE
                    while (true) {
                        info.offset = 0
                        // ByteBuffer overload takes (buffer, offset) only — capacity is implied by
                        // buffer.remaining(). The 3-arg form is the ByteArray overload.
                        info.size = extractor.readSampleData(buffer, 0)
                        if (info.size < 0) break
                        val sampleUs = extractor.sampleTime
                        if (firstSampleUs == Long.MIN_VALUE) firstSampleUs = sampleUs
                        var pts = writeOffsetUs + (sampleUs - firstSampleUs)
                        if (pts <= lastPtsUs) pts = lastPtsUs + 1
                        info.presentationTimeUs = pts
                        info.flags =
                            if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                                MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                        muxer.writeSampleData(trackIndex, buffer, info)
                        lastPtsUs = pts
                        extractor.advance()
                    }
                    // Gap keeps PTS strictly increasing across the seam into the next segment.
                    writeOffsetUs = lastPtsUs + MERGE_FRAME_GAP_US
                } finally {
                    extractor.release()
                }
            }
            ok = muxerStarted && lastPtsUs >= 0L
        } catch (e: Throwable) {
            XLog.w(TAG, "Lossless merge failed: ${e.message}")
            ok = false
        } finally {
            runCatching { if (muxerStarted) muxer.stop() }
            runCatching { muxer.release() }
        }
        return ok
    }

    private fun selectVideoTrack(extractor: MediaExtractor): Int? {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) return i
        }
        return null
    }

    private fun sameVideoFormat(a: MediaFormat, b: MediaFormat): Boolean {
        fun mime(f: MediaFormat) = f.getString(MediaFormat.KEY_MIME)
        fun width(f: MediaFormat) =
            if (f.containsKey(MediaFormat.KEY_WIDTH)) f.getInteger(MediaFormat.KEY_WIDTH) else -1
        fun height(f: MediaFormat) =
            if (f.containsKey(MediaFormat.KEY_HEIGHT)) f.getInteger(MediaFormat.KEY_HEIGHT) else -1
        fun rotation(f: MediaFormat) =
            if (f.containsKey(MediaFormat.KEY_ROTATION)) f.getInteger(MediaFormat.KEY_ROTATION) else 0
        return mime(a) == mime(b) && width(a) == width(b) && height(a) == height(b) &&
                rotation(a) == rotation(b)
    }

    private fun copyOne(resolver: ContentResolver, source: File, displayName: String): Uri {
        val scoped = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (scoped) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$GALLERY_DIR")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val collection = if (scoped) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val uri = resolver.insert(collection, values)
            ?: throw IOException("MediaStore.insert returned null")
        try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("openOutputStream returned null")
            out.use { sink -> source.inputStream().use { it.copyTo(sink) } }
            if (scoped) {
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
        } catch (e: Throwable) {
            // Never leave a half-written, permanently "pending" row behind in the gallery.
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        return uri
    }

    /** MediaStore rejects /, and a task text can contain anything the user typed. */
    private fun sanitize(raw: String): String {
        val cleaned = raw.trim()
            .replace(Regex("[^A-Za-z0-9\\u4e00-\\u9fa5 _-]"), "")
            .replace(Regex("\\s+"), "_")
            .take(60)
            .trim('_')
        return cleaned.ifBlank { "BQAAgent_recording" }
    }
}