package io.agents.bqaagent.recording

import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import java.io.File

/**
 * Turns a recorded task directory into one continuous timeline.
 *
 * Segments are played as a Media3 playlist, so the user sees a single video with a single progress
 * bar and seeks across segment boundaries without a "loading next file" seam.
 *
 * Overlap handling: rotate() (TaskRecordingCoordinator L521-527) launches the *next* encoder
 * before stopping the previous one when overlap is enabled, so the head of segment i shows the
 * same frames as the tail of segment i-1. `trimStartMs` below is exactly that duplicated span and
 * is handed to Media3 as a per-item clipping start — playing them untrimmed makes every ~2 minute
 * boundary visibly replay. This is also why countGaps() reports 0 for overlap recordings: its
 * predicate `startedAt - endedAt > 400` is negative when segments overlap.
 */
object RecordingPlaylist {

    data class Entry(
        val file: File,
        /** Duplicated head, already excluded from [durationMs] and from the Media3 item. */
        val trimStartMs: Long,
        /** Playable length of this item after trimming. */
        val durationMs: Long,
        /** Where this item starts on the merged timeline. */
        val playlistStartMs: Long,
        /** Wall-clock window this item covers, used to place [Marker]s. */
        val wallStartMs: Long,
        val wallEndMs: Long
    )

    data class Marker(val label: String, val detail: String, val positionMs: Long)

    data class Plan(
        val recordingId: String,
        val taskText: String,
        val outcome: String,
        val entries: List<Entry>,
        val totalMs: Long,
        val markers: List<Marker>,
        /** True when at least one boundary had duplicated frames removed. */
        val trimmed: Boolean
    )

    /** Null when the folder is gone, has no manifest, or holds no playable segment. */
    fun build(dir: File): Plan? {
        val manifest = TaskRecordingStore.readManifest(dir) ?: return null
        val segments = manifest.segments.filter { it.valid }.sortedBy { it.index }

        val entries = mutableListOf<Entry>()
        var cursor = 0L
        var previousEnd = 0L
        for (segment in segments) {
            val file = File(dir, segment.fileName)
            if (!file.exists() || file.length() <= 0L) continue
            // Both guards matter: crash-recovered manifests carry startedAt = endedAt = 0
            // (TaskRecordingStore.collectValidSegments L129-130), and a stale previousEnd must
            // never trim more than the segment actually contains.
            val rawTrim = if (previousEnd > 0L && segment.startedAt in 1 until previousEnd) {
                previousEnd - segment.startedAt
            } else {
                0L
            }
            val trim = rawTrim.coerceIn(0L, segment.durationMs.coerceAtLeast(0L))
            val duration = (segment.durationMs - trim).coerceAtLeast(0L)
            entries.add(
                Entry(
                    file = file,
                    trimStartMs = trim,
                    durationMs = duration,
                    playlistStartMs = cursor,
                    wallStartMs = segment.startedAt,
                    wallEndMs = segment.endedAt
                )
            )
            cursor += duration
            previousEnd = segment.endedAt
        }
        if (entries.isEmpty()) return null

        val markers = manifest.stepMarkers
            .mapNotNull { marker ->
                val at = positionOf(manifest.startedAt + marker.offsetMs, entries)
                if (at < 0L) null else Marker(marker.label, marker.detail, at)
            }
            .sortedBy { it.positionMs }

        return Plan(
            recordingId = manifest.recordingId.ifBlank { dir.name },
            taskText = manifest.taskText,
            outcome = manifest.outcome,
            entries = entries,
            totalMs = cursor,
            markers = markers,
            trimmed = entries.any { it.trimStartMs > 0L }
        )
    }

    fun toMediaItems(plan: Plan): List<MediaItem> = plan.entries.map { entry ->
        val builder = MediaItem.Builder()
            .setUri(entry.file.toUri())
            .setMediaId(entry.file.name)
        if (entry.trimStartMs > 0L) {
            builder.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(entry.trimStartMs)
                    .build()
            )
        }
        builder.build()
    }

    /**
     * Maps a wall-clock instant onto the merged timeline. `stepMarkers[].offsetMs` is relative to
     * `manifest.startedAt` (TaskRecordingCoordinator.addMarker L134), so markers are recorded in
     * wall-clock terms and must be re-projected once trimming has shifted everything downstream.
     * -1 when the instant falls outside every segment.
     */
    private fun positionOf(wallClock: Long, entries: List<Entry>): Long {
        if (wallClock <= 0L) return -1L
        val containing = entries.firstOrNull { entry ->
            entry.wallEndMs > entry.wallStartMs &&
                    wallClock >= entry.wallStartMs && wallClock < entry.wallEndMs
        }
        val entry = containing
            ?: entries.lastOrNull { it.wallStartMs in 1..wallClock }
            ?: return -1L
        val into = (wallClock - entry.wallStartMs - entry.trimStartMs).coerceIn(0L, entry.durationMs)
        return entry.playlistStartMs + into
    }

    /**
     * Splits a merged-timeline position into the (mediaItemIndex, localPosition) pair
     * `ExoPlayer.seekTo(int, long)` needs — `seekTo(long)` alone only seeks within the current item.
     */
    fun locate(plan: Plan, positionMs: Long): Pair<Int, Long> {
        val index = plan.entries.indexOfFirst {
            positionMs < it.playlistStartMs + it.durationMs
        }.let { if (it >= 0) it else plan.entries.lastIndex }
        val local = (positionMs - plan.entries[index].playlistStartMs).coerceAtLeast(0L)
        return index to local
    }
}