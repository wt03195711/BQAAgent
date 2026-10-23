// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.ui.batch

/**
 * Result of the batch pre-flight check.
 *
 * Recording follows the GLOBAL system switch (KVUtils.isTaskRecordingEnabled()). Per the confirmed
 * product rules, when recording is OFF the precheck reports only "recording is off" and performs NO
 * disk/budget checks — those fields stay at their defaults and the UI hides them. When recording is
 * ON, the UI shows the recording status plus free-disk and storage-budget lines.
 *
 * ADB is a hard prerequisite for EVERY batch task (they all drive the device), so a missing ADB
 * configuration is a blocking issue regardless of the recording switch.
 */
data class BatchPrecheck(
    val checkedAt: Long = 0L,
    val taskCount: Int = 0,
    // --- ADB (always checked) ---
    val adbConfigured: Boolean = false,
    val adbReady: Boolean = false,
    // --- Recording (only meaningful when recordingEnabled) ---
    val recordingEnabled: Boolean = false,
    val recordingReady: Boolean = false,
    val diskFreeBytes: Long = 0L,
    val diskFreeText: String = "",
    val hasRoomForRecording: Boolean = false,
    val usedBytes: Long = 0L,
    val usedText: String = "",
    val maxBytes: Long = 0L,
    val maxText: String = "",
    val nearCapacity: Boolean = false,
    // --- Verdicts ---
    val blocking: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
) {
    /** Start is allowed only with at least one task and no blocking issue. */
    val canStart: Boolean get() = taskCount > 0 && blocking.isEmpty()
}