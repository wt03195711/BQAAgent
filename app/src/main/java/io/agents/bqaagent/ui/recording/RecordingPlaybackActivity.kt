package io.agents.bqaagent.ui.recording

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import io.agents.bqaagent.R
import io.agents.bqaagent.base.BaseActivity
import io.agents.bqaagent.recording.RecordingPlaylist
import io.agents.bqaagent.recording.TaskRecordingStore
import io.agents.bqaagent.ui.chat.ThemeManager
import io.agents.bqaagent.utils.XLog
import io.agents.bqaagent.widget.AlertDialog
import io.agents.bqaagent.widget.CommonToolbar
import io.agents.bqaagent.widget.LoadingDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Plays one task's recording as a single continuous video.
 *
 * Reached from the "View recording" chip on a task result message and from the recording manager.
 * The recordingId is the directory name; it is resolved lazily here rather than at the call site so
 * a folder that retention or a manual delete removed produces an explanation instead of a crash.
 */
@OptIn(UnstableApi::class)
class RecordingPlaybackActivity : BaseActivity() {

    companion object {
        private const val TAG = "RecordingPlayback"
        private const val EXTRA_RECORDING_ID = "extra_recording_id"

        private fun intent(context: Context, recordingId: String): Intent =
            Intent(context, RecordingPlaybackActivity::class.java)
                .putExtra(EXTRA_RECORDING_ID, recordingId)

        /**
         * Single entry point used by the chat "View recording" chip (ComposeChatActivity) and the
         * recording manager. Public, and the companion is NOT private — a `private companion object`
         * would hide this from every external caller and reproduce the unresolved-reference error.
         */
        fun start(context: Context, recordingId: String) {
            context.startActivity(intent(context, recordingId))
        }
    }

    private var player: ExoPlayer? = null
    private var plan: RecordingPlaylist.Plan? = null
    // Restores playback after the player is rebuilt on return from background (onStart/onStop).
    private var savedMediaIndex = 0
    private var savedPositionMs = 0L

    private lateinit var playerView: PlayerView
    private lateinit var tvSubtitle: TextView
    private lateinit var btnSteps: TextView
    private lateinit var scrollMarkers: ScrollView
    private lateinit var llMarkers: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tc = ThemeManager.getColors()
        window.statusBarColor = tc.toolbarBg
        window.decorView.setBackgroundColor(tc.bg)
        setContentView(R.layout.activity_recording_playback)

        val contentFrame = findViewById<android.view.ViewGroup>(android.R.id.content)
        contentFrame?.setBackgroundColor(tc.bg)
        (contentFrame?.getChildAt(0) as? View)?.setBackgroundColor(tc.bg)

        val recordingId = intent.getStringExtra(EXTRA_RECORDING_ID).orEmpty()

        findViewById<CommonToolbar>(R.id.toolbar).apply {
            setTitle("Recording")
            // Left-align instead of center: a long task title (take(40)) centered overflows its 56dp
            // margins and covers ivBack. setTitleCentered(false) starts the title after the back
            // button and lets the XML's ellipsize/maxLines trim it.
            setTitleCentered(false)
            setTitleColor(tc.aiText)
            setBackgroundColor(tc.toolbarBg)
            showBackButton(true) { finish() }
            findViewById<ImageView>(R.id.ivBack)?.setColorFilter(tc.aiText)
        }

        tvSubtitle = findViewById(R.id.tvSubtitle)
        playerView = findViewById(R.id.playerView)
        scrollMarkers = findViewById(R.id.scrollMarkers)
        llMarkers = findViewById(R.id.llMarkers)
        btnSteps = findViewById(R.id.btnSteps)

        findViewById<TextView>(R.id.btnShare).setOnClickListener { shareSegments() }
        findViewById<TextView>(R.id.btnSaveGallery).setOnClickListener { saveToGallery() }
        btnSteps.setOnClickListener {
            scrollMarkers.visibility =
                if (scrollMarkers.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        tvSubtitle.setTextColor(tc.toolDefault)
        btnSteps.setTextColor(tc.toolDefault)
        findViewById<TextView>(R.id.btnShare).setTextColor(tc.toolDefault)
        findViewById<TextView>(R.id.btnSaveGallery).setTextColor(tc.toolDefault)

        load(recordingId)
    }

    private fun load(recordingId: String) {
        if (recordingId.isBlank()) {
            unavailable("This message carries no recording reference.")
            return
        }
        val loading = LoadingDialog.show(this, "Loading recording…")
        lifecycleScope.launch {
            // Directory scan + manifest parse + a stat per segment; never on the main thread.
            // Returns Plan?: null covers BOTH "folder gone" (findRecording null) and "folder exists
            // but holds no playable segment" (build null) — the same user-facing outcome. The File
            // was never read after the old null check, and pairing it made getOrThrow() a nullable
            // Pair, which cannot be destructured (component1/component2 have no nullable receiver).
            val built = runCatching {
                withContext(Dispatchers.IO) {
                    val dir = TaskRecordingStore.findRecording(recordingId) ?: return@withContext null
                    RecordingPlaylist.build(dir)
                }
            }
            runCatching { loading.dismiss() }
            if (isFinishing || isDestroyed) return@launch
            built.onFailure { e ->
                XLog.e(TAG, "Failed to open recording $recordingId", e)
                unavailable("Could not read this recording.\n(${e.javaClass.simpleName})")
                return@launch
            }
            val builtPlan = built.getOrThrow()
            if (builtPlan == null) {
                unavailable(
                    "This recording is no longer available.\n\n" +
                            "Recordings are removed automatically, oldest first, once the 800MB " +
                            "limit is reached, and can be deleted from Settings → Recorded Files."
                )
                return@launch
            }
            plan = builtPlan
            render(builtPlan)
        }
    }

    private fun render(built: RecordingPlaylist.Plan) {
        findViewById<CommonToolbar>(R.id.toolbar)
            .setTitle(built.taskText.ifBlank { "Recording" }.take(40))

        val first = built.entries.first()
        tvSubtitle.text = buildString {
            append(built.entries.size).append(if (built.entries.size == 1) " segment" else " segments")
            append(" · ").append(TaskRecordingStore.formatDuration(built.totalMs))
            val size = built.entries.sumOf { it.file.length() }
            append(" · ").append(TaskRecordingStore.formatBytes(size))
            if (built.trimmed) append(" · overlap trimmed")
            append(" · ").append(built.outcome.lowercase())
            // Honest about a recording that was rescued from a crash: it has no wall-clock timing,
            // so nothing could be trimmed and the ~1s rotation gaps are still in there.
            if (first.wallStartMs <= 0L) append(" · timing unavailable")
        }

        buildMarkerRows(built)

        initializePlayer()
    }

    /**
     * (Re)builds the ExoPlayer from [plan] and attaches it to the PlayerView.
     *
     * Called once after the plan loads (from [render]) and again from [onStart] every time the app
     * returns from the background. [onStop] releases the player (re-attaching a stale player to a new
     * Surface is what produced the black frame), so without this rebuild the PlayerView would have no
     * player and show a black screen on return. Idempotent via the `player != null` guard, and it
     * resumes from the position saved in [onStop].
     */
    private fun initializePlayer() {
        val built = plan ?: return
        if (player != null) return
        val exoPlayer = ExoPlayer.Builder(this).build()
        player = exoPlayer
        playerView.player = exoPlayer
        exoPlayer.setMediaItems(RecordingPlaylist.toMediaItems(built))
        exoPlayer.prepare()
        if (savedMediaIndex > 0 || savedPositionMs > 0L) {
            exoPlayer.seekTo(savedMediaIndex, savedPositionMs)
        }
        exoPlayer.playWhenReady = false
        exoPlayer.addListener(object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                XLog.w(TAG, "Playback error: ${error.errorCodeName} ${error.message}")
                Toast.makeText(
                    this@RecordingPlaybackActivity,
                    "Cannot play this segment: ${error.errorCodeName}",
                    Toast.LENGTH_LONG
                ).show()
            }
        })
    }

    /**
     * Step markers as tappable rows. `RecordingStepMarker.offsetMs` is wall-clock relative to
     * manifest.startedAt, so the position shown here is the one RecordingPlaylist re-projected onto
     * the merged (and trimmed) timeline — seeking with the raw offset would drift further behind
     * with every segment boundary.
     */
    private fun buildMarkerRows(built: RecordingPlaylist.Plan) {
        llMarkers.removeAllViews()
        btnSteps.text = when (built.markers.size) {
            0 -> "No steps"
            1 -> "1 step"
            else -> "${built.markers.size} steps"
        }
        if (built.markers.isEmpty()) {
            scrollMarkers.visibility = View.GONE
            btnSteps.isEnabled = false
            return
        }
        val density = resources.displayMetrics.density
        for (marker in built.markers) {
            val row = TextView(this).apply {
                text = "${formatPosition(marker.positionMs)}   ${marker.label}" +
                        (if (marker.detail.isNotBlank()) "  ·  ${marker.detail}" else "")
                textSize = 12f
                setTextColor(getColor(R.color.colorTextSecondary))
                maxLines = 2
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(android.R.attr.selectableItemBackground.let {
                    val out = android.util.TypedValue()
                    context.theme.resolveAttribute(it, out, true)
                    out.resourceId
                })
                setPadding((16 * density).toInt(), (10 * density).toInt(),
                    (16 * density).toInt(), (10 * density).toInt())
                setOnClickListener { seekTo(marker.positionMs) }
            }
            llMarkers.addView(
                row, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    private fun seekTo(positionMs: Long) {
        val built = plan ?: return
        val exoPlayer = player ?: return
        val (index, local) = RecordingPlaylist.locate(built, positionMs)
        exoPlayer.seekTo(index, local)
        exoPlayer.playWhenReady = true
    }

    private fun formatPosition(ms: Long): String {
        val total = ms / 1000
        return "%02d:%02d".format(total / 60, total % 60)
    }

    private fun segmentFiles(): List<File> = plan?.entries?.map { it.file } ?: emptyList()

    private fun shareSegments() {
        val files = segmentFiles()
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
            // Fall back to the raw segments when a lossless merge was impossible (formats differ).
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

    private fun saveToGallery() {
        val files = segmentFiles()
        if (files.isEmpty()) return
        if (!RecordingExporter.hasPermission(this)) {
            Toast.makeText(
                this,
                "Storage permission is required on this Android version",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val built = plan ?: return
        val loading = LoadingDialog.show(this, "Saving to Photos…")
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { RecordingExporter.export(files, built.taskText) }
            }
            runCatching { loading.dismiss() }
            if (isFinishing || isDestroyed) return@launch
            result.onFailure { e ->
                Toast.makeText(this@RecordingPlaybackActivity, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
                return@launch
            }
            val outcome = result.getOrThrow()
            AlertDialog.show(
                context = this@RecordingPlaybackActivity,
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

    private fun unavailable(message: String) {
        AlertDialog.show(
            context = this,
            title = "Recording unavailable",
            message = message,
            actionTitle = "Close",
            messageAlignStart = true,
            onAction = { finish() }
        )
        // Dismissing by tapping outside must also leave, otherwise the user lands on a black player.
        playerView.visibility = View.GONE
    }

    override fun onStart() {
        super.onStart()
        initializePlayer()
    }

    override fun onStop() {
        // Released here rather than in onDestroy: returning from background leaves the PlayerView's
        // Surface invalid, and re-attaching a stale player to a new Surface is what produces the
        // black-video-with-audio failure. onStart() rebuilds a fresh player, so save where we were.
        player?.let {
            savedMediaIndex = it.currentMediaItemIndex
            savedPositionMs = it.currentPosition
        }
        playerView.player = null
        player?.release()
        player = null
        super.onStop()
    }
}