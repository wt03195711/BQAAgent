// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.ui.recording

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import io.agents.bqaagent.R
import java.io.File

/**
 * One pre-resolved row. All disk I/O (manifest parse, segment stat, size sum) happens before
 * submit(), never in onBindViewHolder — a RecyclerView rebinds on every scroll frame and must not
 * touch storage while the list is moving.
 */
data class RecordingRow(
    val recordingId: String,
    val taskText: String,
    val metaLine: String,
    val noteLine: String,
    val segmentFiles: List<File>,
    val updatedAt: Long
)

class RecordingManagerAdapter(
    private val onPlay: (RecordingRow) -> Unit,
    private val onSaveToGallery: (RecordingRow) -> Unit,
    private val onShare: (RecordingRow) -> Unit,
    private val onDelete: (RecordingRow) -> Unit,
    private val onDetails: (RecordingRow) -> Unit
) : RecyclerView.Adapter<RecordingManagerAdapter.Holder>() {

    private val rows = mutableListOf<RecordingRow>()

    fun submit(newRows: List<RecordingRow>) {
        rows.clear()
        rows.addAll(newRows)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_recording_row, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(rows[position])

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val ivPlay: ImageView = view.findViewById(R.id.ivPlayBadge)
        private val tvTask: TextView = view.findViewById(R.id.tvTask)
        private val tvMeta: TextView = view.findViewById(R.id.tvMeta)
        private val tvNote: TextView = view.findViewById(R.id.tvNote)
        private val btnSave: ImageView = view.findViewById(R.id.btnSaveGallery)
        private val btnShare: ImageView = view.findViewById(R.id.btnShare)
        private val btnDelete: ImageView = view.findViewById(R.id.btnDelete)

        fun bind(row: RecordingRow) {
            tvTask.text = row.taskText
            tvMeta.text = row.metaLine
            tvNote.text = row.noteLine
            tvNote.isVisible = row.noteLine.isNotBlank()

            // A recording with no playable segment cannot be opened, exported or shared; leaving the
            // buttons live would produce a black player or an empty chooser. Delete stays enabled so
            // an unplayable folder can still be removed to reclaim space.
            val playable = row.segmentFiles.isNotEmpty()
            itemView.setOnClickListener { if (playable) onPlay(row) }
            // The row clamps tvTask to 2 lines and tvMeta/tvNote to 1, so a long task description is
            // visually elided with nowhere else to read it. Long-press opens a scrollable dialog with
            // the full, untruncated text — tap still plays, the two gestures do not conflict.
            itemView.setOnLongClickListener {
                onDetails(row)
                true
            }
            // The leading play badge is the "tap to open the player" affordance; dim it in step with
            // the action buttons when the row cannot be played.
            ivPlay.alpha = if (playable) 1f else 0.3f

            btnSave.isEnabled = playable
            btnShare.isEnabled = playable
            // isEnabled alone does not dim an ImageView tinted via android:tint, so fade it explicitly
            // — otherwise a dead button looks tappable.
            btnSave.alpha = if (playable) 1f else 0.3f
            btnShare.alpha = if (playable) 1f else 0.3f

            btnSave.setOnClickListener { if (playable) onSaveToGallery(row) }
            btnShare.setOnClickListener { if (playable) onShare(row) }
            btnDelete.setOnClickListener { onDelete(row) }
        }
    }
}