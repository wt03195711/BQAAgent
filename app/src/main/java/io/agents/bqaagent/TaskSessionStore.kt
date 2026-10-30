// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent

import io.agents.bqaagent.channel.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class TaskSessionPhase {
    IDLE,
    RUNNING,
    STOPPING,
    WAITING_USER_IMAGE,
}

/**
 * Which UI owns the current task session. NONE means nobody owns it — the workspace is free.
 * This is the authoritative token for cross-mode exclusion (C1): a CHAT session can never be
 * acquired by the batch driver and vice versa, even if an Activity was recreated and lost its
 * own local state.
 */
enum class TaskRunMode {
    NONE,
    CHAT,
    BATCH,
}

data class TaskSessionState(
    val phase: TaskSessionPhase = TaskSessionPhase.IDLE,
    val messageId: String = "",
    val channel: Channel? = null,
    val taskText: String = "",
    val startedAtMillis: Long = 0L,
    val stopRequested: Boolean = false,
    val autoReturnToChat: Boolean = false,
    // True when this session belongs to a batch run (BatchActivity) rather than a chat task
    // (ComposeChatActivity). Lets the orchestrator foreground the correct screen when a task needs
    // a user-uploaded screenshot.
    val isBatch: Boolean = false,
    // Owner of the session; mirrors isBatch but is the value cross-mode checks compare against.
    val mode: TaskRunMode = TaskRunMode.NONE,
    // Set once by the stop-confirmation loop when it is about to publish the terminal outcome.
    // Guarantees the cancel finalization (release + Cancelled event) runs exactly once even if the
    // agent loop also settles concurrently.
    val finalizing: Boolean = false,
) {
    val isRunning: Boolean
        get() = phase != TaskSessionPhase.IDLE && messageId.isNotEmpty()

    val isStopping: Boolean
        get() = phase == TaskSessionPhase.STOPPING && messageId.isNotEmpty()
}

/**
 * Single authoritative state holder for the currently running task session.
 *
 * The orchestrator mutates this store; UI and service layers can observe it
 * without having to infer task truth from multiple ad-hoc fields.
 */
class TaskSessionStore {

    private val lock = Any()
    private val _state = MutableStateFlow(TaskSessionState())
    val state: StateFlow<TaskSessionState> = _state

    /**
     * The messageId of the most recent session a stop was requested for. Kept AFTER the lock is
     * released so a fallback re-entry (Skill/Replay -> AgentLoop) can recognise that the run it is
     * about to resume was already cancelled by the user and refuse to resurrect it.
     */
    @Volatile
    private var lastStoppedMessageId: String? = null

    /**
     * True when a stop was previously requested for [messageId]. Used by the orchestrator to gate
     * every fallback re-entry: without it, a cancel landing during a replay leaves the lock on its
     * way to IDLE, and the degraded AgentLoop re-acquires a fresh RUNNING session (stopRequested
     * reset to false) that no entry point can stop — UI shows "cancelled" while the device keeps
     * being driven.
     */
    fun wasStopped(messageId: String): Boolean = lastStoppedMessageId == messageId

    fun snapshot(): TaskSessionState = _state.value

    fun isTaskRunning(): Boolean = _state.value.isRunning

    fun tryAcquire(
        messageId: String,
        channel: Channel,
        taskText: String = "",
        autoReturnToChat: Boolean = true,
        isBatch: Boolean = false,
    ): Boolean {
        val mode = if (isBatch) TaskRunMode.BATCH else TaskRunMode.CHAT
        synchronized(lock) {
            if (_state.value.isRunning) return false
            // Reaching tryAcquire means a genuinely fresh session is starting (the stop-locked
            // STOPPING state is still `isRunning` and routed to the else-branch, and a cancelled
            // fallback is gated in TaskOrchestrator BEFORE it can get here). So any prior stop
            // marker belongs to a finished run and must be cleared — otherwise a batch item that
            // reuses the SAME messageId after an earlier cancel would have its legitimate replay-
            // failure -> AgentLoop fallback wrongly blocked.
            lastStoppedMessageId = null
            _state.value = TaskSessionState(
                phase = TaskSessionPhase.RUNNING,
                messageId = messageId,
                channel = channel,
                taskText = taskText,
                startedAtMillis = System.currentTimeMillis(),
                stopRequested = false,
                autoReturnToChat = autoReturnToChat,
                isBatch = isBatch,
                mode = mode,
            )
            return true
        }
    }

    /**
     * Cross-mode rejection (C1 backend gate): returns the mode that currently owns the session, or
     * null when it is free. Never mutates state.
     */
    fun ownerMode(): TaskRunMode = _state.value.mode

    fun updateTaskText(taskText: String) {
        synchronized(lock) {
            val current = _state.value
            if (!current.isRunning || current.taskText == taskText) return
            _state.value = current.copy(taskText = taskText)
        }
    }

    /**
     * Idempotent stop intent (C3). Unlike the old one-shot gate, a session that is ALREADY stopping
     * returns true, so a repeated tap, the batch driver's periodic nag, or a cancel routed from a
     * different entry point all re-run the interrupt sequence instead of silently no-op'ing.
     */
    fun requestStop(): Boolean {
        synchronized(lock) {
            val current = _state.value
            if (!current.isRunning) return false
            if (current.phase == TaskSessionPhase.STOPPING && current.stopRequested) return true
            // Record BEFORE the state flips, so a fallback that arrives after release() can still
            // see that THIS messageId was cancelled and abort instead of re-acquiring the lock.
            lastStoppedMessageId = current.messageId
            _state.value = current.copy(
                phase = TaskSessionPhase.STOPPING,
                stopRequested = true,
            )
            return true
        }
    }

    /** Kept for callers not yet migrated to the idempotent stop intent. */
    @Deprecated("Use requestStop(); the old one-shot gate swallowed repeat cancels")
    fun markStopping(): Boolean = requestStop()

    /**
     * Exactly-once claim on the finalize step: only the caller that flips this to true may release
     * the session and publish the terminal outcome of a cancellation.
     */
    fun beginFinalize(): Boolean {
        synchronized(lock) {
            val current = _state.value
            if (current.phase != TaskSessionPhase.STOPPING || current.finalizing) return false
            _state.value = current.copy(finalizing = true)
            return true
        }
    }

    fun markWaitingUserImage(): Boolean {
        synchronized(lock) {
            val current = _state.value
            if (!current.isRunning) return false
            _state.value = current.copy(phase = TaskSessionPhase.WAITING_USER_IMAGE)
            return true
        }
    }

    fun resumeFromWait(): Boolean {
        synchronized(lock) {
            val current = _state.value
            if (current.phase != TaskSessionPhase.WAITING_USER_IMAGE) return false
            _state.value = current.copy(phase = TaskSessionPhase.RUNNING)
            return true
        }
    }

    fun release(): TaskSessionState {
        synchronized(lock) {
            val current = _state.value
            _state.value = TaskSessionState()
            return current
        }
    }
}
