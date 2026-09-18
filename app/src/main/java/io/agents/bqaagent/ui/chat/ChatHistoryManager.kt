// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.ui.chat

import android.content.Context
import io.agents.bqaagent.TaskStatus
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saves and loads chat conversations as markdown files.
 *
 * Storage: /storage/emulated/0/BQAAgent/chats/
 * Format: 2026-04-04-send-hi-to-mom.md
 *
 * Each file:
 * ---
 * title: Send hi to Mom
 * created: 2026-04-04T15:30:00
 * model: FunctionGemma-270M
 * ---
 *
 * ## User
 * Open WhatsApp and send hi to Mom
 *
 * ## 🦞 Assistant
 * On it. Checking your screen.
 *
 * ## System
 * Task completed.
 */
object ChatHistoryManager {

    private const val MESSAGE_TIMESTAMP_PREFIX = "<!-- bqaagent:timestamp="
    private const val MESSAGE_TIMESTAMP_SUFFIX = " -->"
    /** Same hidden-comment trick as the timestamp: survives markdown, invisible when rendered. */
    private const val MESSAGE_RECORDING_PREFIX = "<!-- bqaagent:recording="
    private const val MESSAGE_RECORDING_SUFFIX = " -->"
    private const val MESSAGE_STATUS_PREFIX = "<!-- bqaagent:status="
    private const val MESSAGE_STATUS_SUFFIX = " -->"
    private val frontmatterDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

    /** Maximum number of conversations retained on disk; the least-recently-accessed are pruned beyond this. */
    const val MAX_CONVERSATIONS = 100

    data class ConversationSummary(
        val id: String,
        val title: String,
        val created: Long,
        val file: File
    )

    private fun getChatDir(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), "chats")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Save a conversation to markdown file.
     */
    fun save(context: Context, conversationId: String, messages: List<ChatMessage>, model: String) {
        if (messages.isEmpty()) return

        val dir = getChatDir(context)
        // Match the target file by conversation id (frontmatter) so a save ALWAYS rewrites the same
        // file. New conversations are named by the unique conversationId — never by message content —
        // so two chats can never collide onto one filename and silently overwrite each other.
        val file = findFileByConversationId(dir, conversationId) ?: File(dir, "$conversationId.md")

        val firstUserMsg = messages.firstOrNull { it.role == ChatMessage.Role.USER }
        // A user-renamed title is authoritative and must survive every later save; only derive a
        // title from the first message when the user has not customized it.
        val customTitle = readCustomTitle(file)
        val isCustomTitle = customTitle != null
        val title = (customTitle ?: firstUserMsg?.content?.take(80))
            ?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() } ?: "Untitled"

        val sb = StringBuilder()
        // Frontmatter
        sb.appendLine("---")
        sb.appendLine("id: $conversationId")
        sb.appendLine("title: $title")
        if (isCustomTitle) sb.appendLine("title_custom: true")
        sb.appendLine("created: ${SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date(messages.first().timestamp))}")
        sb.appendLine("model: $model")
        sb.appendLine("---")
        sb.appendLine()

        // Messages
        messages.forEach { msg ->
            when (msg.role) {
                ChatMessage.Role.USER -> {
                    sb.appendLine("## User")
                    sb.appendLine(serializeTimestamp(msg.timestamp))
                    sb.appendLine(msg.content)
                    sb.appendLine()
                }
                ChatMessage.Role.ASSISTANT -> {
                    if (!msg.modelName.isNullOrEmpty()) {
                        sb.appendLine("## 🦞 Assistant [${msg.modelName}]")
                    } else {
                        sb.appendLine("## 🦞 Assistant")
                    }
                    sb.appendLine(serializeTimestamp(msg.timestamp))
                    msg.recordingId?.takeIf { it.isNotBlank() }?.let {
                        sb.appendLine(serializeRecordingId(it))
                    }
                    msg.taskStatus?.let {
                        sb.appendLine(serializeTaskStatus(it.name))
                    }
                    sb.appendLine(msg.content)
                    sb.appendLine()
                }
                ChatMessage.Role.SYSTEM -> {
                    sb.appendLine("## System")
                    sb.appendLine(serializeTimestamp(msg.timestamp))
                    sb.appendLine(msg.content)
                    sb.appendLine()
                }
                ChatMessage.Role.TOOL_GROUP -> {
                    val titleSuffix = msg.groupTitle?.takeIf { it.isNotBlank() }?.let { " [$it]" }.orEmpty()
                    sb.appendLine("## Tools$titleSuffix")
                    sb.appendLine(serializeTimestamp(msg.timestamp))
                    val steps = msg.toolSteps.orEmpty()
                    if (steps.isNotEmpty()) {
                        steps.forEach { step ->
                            val icon = if (step.success) "✓" else if (step.completedAt == null) "…" else "✕"
                            val completed = step.completedAt?.toString().orEmpty()
                            val duration = step.durationMs?.toString().orEmpty()
                            val tokens = step.tokenCount?.toString().orEmpty()
                            val tokenText = step.tokenText.orEmpty()
                            val costText = step.costText.orEmpty()
                            val safeSummary = step.summary.replace("\n", " ").replace("|", "·")
                            val safeIntent = step.intent.orEmpty().replace("\n", " ").replace("|", "·")
                            val safeParams = step.params.orEmpty().replace("\n", " ").replace("|", "·")
                            sb.appendLine(
                                "- $icon ${step.toolName} | started=${step.startedAt} | completed=$completed | " +
                                        "durationMs=$duration | tokens=$tokens | tokenText=$tokenText | cost=$costText | " +
                                        "llm=${step.isLlmCall} | params=$safeParams | intent=$safeIntent | $safeSummary"
                            )
                        }
                    } else if (msg.content.isNotBlank()) {
                        sb.appendLine(msg.content)
                    }
                    sb.appendLine()
                }
            }
        }

        file.writeText(sb.toString())

        // Index in SQLite for fast search
        try {
            val chatDatabase = ChatDatabase(context)
            try {
                chatDatabase.indexConversation(
                    id = conversationId,
                    title = title,
                    created = messages.first().timestamp,
                    model = model,
                    filePath = file.absolutePath,
                    messages = messages
                )
            } finally {
                chatDatabase.close()
            }
        } catch (_: Exception) { /* index failure is non-fatal */ }
    }

    /**
     * Load a conversation from markdown file.
     */
    fun load(file: File): List<ChatMessage> {
        if (!file.exists()) return emptyList()

        val messages = mutableListOf<ChatMessage>()
        val lines = file.readLines()

        var inFrontmatter = false
        var fallbackConversationTimestamp = file.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()
        var currentRole: ChatMessage.Role? = null
        var currentModelName: String? = null
        var currentTimestamp: Long? = null
        var currentGroupTitle: String? = null
        var currentRecordingId: String? = null
        var currentTaskStatus: TaskStatus? = null
        val contentBuilder = StringBuilder()

        for (line in lines) {
            if (line == "---") {
                if (!inFrontmatter) { inFrontmatter = true; continue }
                else { inFrontmatter = false; continue }
            }
            if (inFrontmatter) {
                if (line.startsWith("created: ")) {
                    parseFrontmatterTimestamp(line.removePrefix("created: "))?.let {
                        fallbackConversationTimestamp = it
                    }
                }
                continue
            }

            when {
                line.startsWith("## User") -> {
                    flushMessage(messages, currentRole, contentBuilder, currentModelName, currentTimestamp, fallbackConversationTimestamp, currentGroupTitle, currentRecordingId, currentTaskStatus)
                    currentRole = ChatMessage.Role.USER
                    currentModelName = null
                    currentTimestamp = null
                    currentGroupTitle = null
                    currentRecordingId = null
                    currentTaskStatus = null
                }
                line.startsWith("## 🦞 Assistant") -> {
                    flushMessage(messages, currentRole, contentBuilder, currentModelName, currentTimestamp, fallbackConversationTimestamp, currentGroupTitle, currentRecordingId, currentTaskStatus)
                    currentRole = ChatMessage.Role.ASSISTANT
                    // Extract model name from "## 🦞 Assistant [ModelName]"
                    val bracketMatch = Regex("\\[(.+)]").find(line)
                    currentModelName = bracketMatch?.groupValues?.get(1)
                    currentTimestamp = null
                    currentGroupTitle = null
                    currentRecordingId = null
                    currentTaskStatus = null
                }
                line.startsWith("## System") -> {
                    flushMessage(messages, currentRole, contentBuilder, currentModelName, currentTimestamp, fallbackConversationTimestamp, currentGroupTitle, currentRecordingId, currentTaskStatus)
                    currentRole = ChatMessage.Role.SYSTEM
                    currentModelName = null
                    currentTimestamp = null
                    currentGroupTitle = null
                    currentRecordingId = null
                    currentTaskStatus = null
                }
                line.startsWith("## Tools") -> {
                    flushMessage(messages, currentRole, contentBuilder, currentModelName, currentTimestamp, fallbackConversationTimestamp, currentGroupTitle, currentRecordingId, currentTaskStatus)
                    currentRole = ChatMessage.Role.TOOL_GROUP
                    currentModelName = null
                    currentTimestamp = null
                    // Extract group title from "## Tools [Replay Steps]"
                    currentGroupTitle = Regex("\\[(.+)]").find(line)?.groupValues?.get(1)
                    currentRecordingId = null
                    currentTaskStatus = null
                }
                currentRole != null && line.startsWith(MESSAGE_TIMESTAMP_PREFIX) -> {
                    currentTimestamp = parseMessageTimestamp(line)
                }
                // Must stay above the `else` branch: otherwise the marker is appended to
                // contentBuilder and the user sees raw HTML comment text inside the bubble.
                currentRole != null && line.startsWith(MESSAGE_RECORDING_PREFIX) -> {
                    currentRecordingId = parseRecordingId(line)
                }
                currentRole != null && line.startsWith(MESSAGE_STATUS_PREFIX) -> {
                    currentTaskStatus = parseTaskStatus(line)
                }
                else -> {
                    if (currentRole != null && line.isNotBlank()) {
                        if (contentBuilder.isNotEmpty()) contentBuilder.appendLine()
                        contentBuilder.append(line)
                    }
                }
            }
        }
        flushMessage(messages, currentRole, contentBuilder, currentModelName, currentTimestamp, fallbackConversationTimestamp, currentGroupTitle, currentRecordingId, currentTaskStatus)

        return messages
    }

    private fun flushMessage(
        messages: MutableList<ChatMessage>,
        role: ChatMessage.Role?,
        content: StringBuilder,
        modelName: String? = null,
        timestamp: Long? = null,
        fallbackConversationTimestamp: Long,
        groupTitle: String? = null,
        recordingId: String? = null,
        taskStatus: TaskStatus? = null
    ) {
        if (role != null && content.isNotEmpty()) {
            val resolvedTimestamp = timestamp ?: (fallbackConversationTimestamp + messages.size * 1000L)
            val text = content.toString().trim()
            val toolSteps = if (role == ChatMessage.Role.TOOL_GROUP) {
                parseToolSteps(text, resolvedTimestamp)
            } else {
                null
            }
            messages.add(
                ChatMessage(
                    role = role,
                    content = text,
                    timestamp = resolvedTimestamp,
                    toolSteps = toolSteps,
                    modelName = modelName,
                    groupTitle = groupTitle,
                    recordingId = recordingId,
                    taskStatus = taskStatus
                )
            )
            content.clear()
        }
    }

    private fun serializeTimestamp(timestamp: Long): String {
        return "$MESSAGE_TIMESTAMP_PREFIX$timestamp$MESSAGE_TIMESTAMP_SUFFIX"
    }

    private fun serializeRecordingId(recordingId: String): String {
        return "$MESSAGE_RECORDING_PREFIX$recordingId$MESSAGE_RECORDING_SUFFIX"
    }

    private fun parseRecordingId(line: String): String? {
        return line.removePrefix(MESSAGE_RECORDING_PREFIX)
            .removeSuffix(MESSAGE_RECORDING_SUFFIX)
            .trim()
            .takeIf { it.isNotBlank() }
    }

    private fun serializeTaskStatus(status: String): String {
        return "$MESSAGE_STATUS_PREFIX$status$MESSAGE_STATUS_SUFFIX"
    }

    private fun parseTaskStatus(line: String): TaskStatus? {
        val raw = line.removePrefix(MESSAGE_STATUS_PREFIX)
            .removeSuffix(MESSAGE_STATUS_SUFFIX)
            .trim()
        return runCatching { TaskStatus.valueOf(raw) }.getOrNull()
    }

    private fun parseMessageTimestamp(line: String): Long? {
        return line.removePrefix(MESSAGE_TIMESTAMP_PREFIX)
            .removeSuffix(MESSAGE_TIMESTAMP_SUFFIX)
            .toLongOrNull()
    }

    private fun parseFrontmatterTimestamp(raw: String): Long? {
        return try {
            frontmatterDateFormat.parse(raw.trim())?.time
        } catch (_: Exception) {
            null
        }
    }

    private fun parseToolSteps(content: String, fallbackTimestamp: Long): List<ToolStep> {
        return content.lines().mapNotNull { rawLine ->
            val line = rawLine.trim().removePrefix("-").trim()
            if (line.isBlank()) return@mapNotNull null

            val success = line.startsWith("✓") || line.startsWith("[x]", ignoreCase = true)
            val body = line
                .removePrefix("✓")
                .removePrefix("○")
                .removePrefix("✕")
                .removePrefix("…")
                .removePrefix("[x]")
                .removePrefix("[ ]")
                .trim()

            if (" | " in body) {
                val parts = body.split(" | ")
                val title = parts.firstOrNull()?.trim().orEmpty()
                val startedAt = parts.firstOrNull { it.startsWith("started=") }
                    ?.removePrefix("started=")
                    ?.toLongOrNull()
                    ?: fallbackTimestamp
                val completedAt = parts.firstOrNull { it.startsWith("completed=") }
                    ?.removePrefix("completed=")
                    ?.toLongOrNull()
                val durationMs = parts.firstOrNull { it.startsWith("durationMs=") }
                    ?.removePrefix("durationMs=")
                    ?.toLongOrNull()
                val tokenCount = parts.firstOrNull { it.startsWith("tokens=") }
                    ?.removePrefix("tokens=")
                    ?.toIntOrNull()
                val tokenText = parts.firstOrNull { it.startsWith("tokenText=") }
                    ?.removePrefix("tokenText=")
                    ?.takeIf { it.isNotBlank() }
                val costText = parts.firstOrNull { it.startsWith("cost=") }
                    ?.removePrefix("cost=")
                    ?.takeIf { it.isNotBlank() }
                val isLlmCall = parts.firstOrNull { it.startsWith("llm=") }
                    ?.removePrefix("llm=")
                    ?.toBooleanStrictOrNull()
                    ?: title.equals("LLM Call", ignoreCase = true)
                val intent = parts.firstOrNull { it.startsWith("intent=") }
                    ?.removePrefix("intent=")
                    ?.takeIf { it.isNotBlank() }
                val params = parts.firstOrNull { it.startsWith("params=") }
                    ?.removePrefix("params=")
                    ?.takeIf { it.isNotBlank() }
                var summary = parts.lastOrNull()?.trim().orEmpty()
                var resolvedIntent = intent
                if (resolvedIntent == null && " · Intent: " in summary) {
                    resolvedIntent = summary.substringAfter(" · Intent: ").trim().takeIf { it.isNotBlank() }
                    summary = summary.substringBefore(" · Intent: ").trim()
                }
                ToolStep(
                    toolName = title,
                    summary = summary,
                    success = success,
                    startedAt = startedAt,
                    completedAt = completedAt ?: durationMs?.let { startedAt + it },
                    durationMs = durationMs,
                    tokenCount = tokenCount,
                    tokenText = tokenText,
                    costText = costText,
                    isLlmCall = isLlmCall,
                    intent = resolvedIntent,
                    params = params,
                )
            } else {
                val name = body.substringBefore("→").trim()
                val summary = body.substringAfter("→", "").trim()
                ToolStep(
                    toolName = name,
                    summary = summary,
                    success = success,
                    startedAt = fallbackTimestamp,
                    completedAt = fallbackTimestamp,
                    durationMs = 0L,
                )
            }
        }
    }

    /**
     * List all saved conversations, newest first.
     */
    fun listConversations(context: Context): List<ConversationSummary> {
        val dir = getChatDir(context)
        if (!dir.exists()) return emptyList()

        return dir.listFiles()
            ?.filter { it.extension == "md" && !it.name.endsWith(".memory.md") }
            ?.map { file ->
                var title = file.nameWithoutExtension
                var id = ""
                // Parse frontmatter for title
                file.useLines { lines ->
                    var inFm = false
                    for (line in lines) {
                        if (line == "---") { if (!inFm) { inFm = true; continue } else break }
                        if (inFm && line.startsWith("title: ")) title = line.removePrefix("title: ")
                        if (inFm && line.startsWith("id: ")) id = line.removePrefix("id: ")
                    }
                }
                ConversationSummary(
                    id = id.ifEmpty { file.nameWithoutExtension },
                    title = title,
                    created = file.lastModified(),
                    file = file
                )
            }
            ?.sortedByDescending { it.created }
            ?: emptyList()
    }

    /**
     * Rename a conversation by updating the title in the markdown frontmatter.
     * Marks the title as user-customized (title_custom) so later saves never overwrite it.
     */
    fun rename(file: File, newTitle: String): Boolean {
        if (!file.exists()) return false
        return try {
            val content = file.readText()
            val safeTitle = newTitle.replace(Regex("\\s+"), " ").trim()
            val hasCustomFlag = Regex("(?m)^title_custom:").containsMatchIn(content)
            val replacement =
                if (hasCustomFlag) "title: $safeTitle" else "title: $safeTitle\ntitle_custom: true"
            val updated = content.replaceFirst(
                Regex("(?m)^title: .*$"),
                Regex.escapeReplacement(replacement)
            )
            file.writeText(updated)
            true
        } catch (e: Exception) {
            io.agents.bqaagent.utils.XLog.e("ChatHistoryManager", "Failed to rename conversation", e)
            false
        }
    }

    /**
     * Mark a conversation as just accessed so it sorts to the top of the recent list.
     */
    fun touchAccess(file: File) {
        runCatching { if (file.exists()) file.setLastModified(System.currentTimeMillis()) }
    }

    /**
     * Keep at most [MAX_CONVERSATIONS] conversations on disk. When over the limit, prune the
     * least-recently-accessed ones (bottom of the recent list) first, never the [protectId] one.
     */
    fun enforceRetention(context: Context, protectId: String) {
        val conversations = listConversations(context) // most-recently-accessed first
        val excess = conversations.size - MAX_CONVERSATIONS
        if (excess <= 0) return
        val victims = conversations.asReversed().filter { it.id != protectId }.take(excess)
        if (victims.isEmpty()) return
        val db = runCatching { ChatDatabase(context) }.getOrNull()
        try {
            victims.forEach { conv ->
                deleteConversationFiles(conv.file)
                runCatching { db?.deleteConversation(conv.id) }
            }
        } finally {
            runCatching { db?.close() }
        }
    }

    private fun deleteConversationFiles(file: File) {
        runCatching {
            val memory = File(file.parentFile, file.nameWithoutExtension + ".memory.md")
            file.delete()
            if (memory.exists()) memory.delete()
        }
    }

    private fun findFileByConversationId(dir: File, conversationId: String): File? {
        if (conversationId.isEmpty() || !dir.exists()) return null
        val files = dir.listFiles { f -> f.extension == "md" && !f.name.endsWith(".memory.md") }
            ?: return null
        return files.firstOrNull { readFrontmatterId(it) == conversationId }
    }

    private fun readFrontmatterId(file: File): String {
        var id = ""
        runCatching {
            file.useLines { lines ->
                var inFm = false
                for (line in lines) {
                    if (line == "---") { if (!inFm) { inFm = true; continue } else break }
                    if (inFm && line.startsWith("id: ")) { id = line.removePrefix("id: ").trim(); break }
                }
            }
        }
        return id
    }

    private fun readCustomTitle(file: File): String? {
        if (!file.exists()) return null
        var title: String? = null
        var custom = false
        runCatching {
            file.useLines { lines ->
                var inFm = false
                for (line in lines) {
                    if (line == "---") { if (!inFm) { inFm = true; continue } else break }
                    if (inFm) {
                        if (line.startsWith("title: ")) title = line.removePrefix("title: ")
                        if (line.startsWith("title_custom:")) custom = line.removePrefix("title_custom:").trim().toBoolean()
                    }
                }
            }
        }
        return if (custom) title?.takeIf { it.isNotBlank() } else null
    }

    /**
     * Delete a conversation and its companion memory digest file.
     */
    fun delete(file: File): Boolean {
        if (!file.exists()) return false
        deleteConversationFiles(file)
        return true
    }
}
