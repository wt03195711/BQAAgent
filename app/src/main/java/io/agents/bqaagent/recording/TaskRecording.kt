// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.recording

/**
 * How the task session ended. Persisted (as [name]) so a replay can be judged later.
 *
 * STOPPED is the unified involuntary-stop outcome (token/iteration limits, stuck, system dialog,
 * unusable screen, sensitive policy, image analysis). BLOCKED is retained as a legacy value so
 * manifests written before STOPPED existed still parse and display; new tasks no longer write it.
 */
enum class RecordingOutcome { COMPLETED, FAILED, CANCELLED, BLOCKED, STOPPED, UNKNOWN }

/** ARMED = a task acquired the lock but recording has not been started for it. */
enum class RecordingPhase { IDLE, ARMED, RECORDING, STOPPING }

data class RecordingSegmentInfo(
    var index: Int = 0,
    var fileName: String = "",
    var startedAt: Long = 0L,
    var endedAt: Long = 0L,
    var sizeBytes: Long = 0L,
    var durationMs: Long = 0L,
    var width: Int = 0,
    var height: Int = 0,
    var pid: Int = 0,
    var valid: Boolean = false,
    var rotationReason: String = ""
)

data class RecordingStepMarker(
    var offsetMs: Long = 0L,
    var label: String = "",
    var detail: String = ""
)

/**
 * One JSON file per recording (`manifest.json`). Every field has a default so Gson can
 * instantiate it through the synthetic no-arg constructor (same pattern as SkillDefinition).
 */
data class TaskRecordingManifest(
    var schemaVersion: Int = 1,
    var recordingId: String = "",
    var taskText: String = "",
    var messageId: String = "",
    var channelName: String = "",
    var pipelineTier: String = "",
    var outcome: String = RecordingOutcome.UNKNOWN.name,
    var startedAt: Long = 0L,
    var endedAt: Long = 0L,
    var totalDurationMs: Long = 0L,
    var totalBytes: Long = 0L,
    var segmentCount: Int = 0,
    var gapCount: Int = 0,
    var width: Int = 0,
    var height: Int = 0,
    var bitRate: Int = 0,
    var deviceModel: String = "",
    var androidVersion: String = "",
    var appVersion: String = "",
    var transport: String = "",
    var backgroundWrapper: String = "",
    var abortedReason: String = "",
    var segments: MutableList<RecordingSegmentInfo> = mutableListOf(),
    var stepMarkers: MutableList<RecordingStepMarker> = mutableListOf()
)

data class VideoProbe(
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val sizeBytes: Long
)