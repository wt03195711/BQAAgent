// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.ui.chat

import android.content.Context
import io.agents.bqaagent.utils.KVUtils

/**
 * Owns persisted conversation identity and markdown-backed history operations.
 *
 * This keeps Activity code focused on UI wiring instead of direct KV/markdown glue.
 */
class ConversationStore(
    private val context: Context
) {

    companion object {
        private const val CURRENT_CONVERSATION_ID_KEY = "CURRENT_CONVERSATION_ID"

        private fun newConversationId(): String = "chat_${System.currentTimeMillis()}"
    }

    data class SessionSnapshot(
        val conversationId: String,
        val messages: List<ChatMessage>,
        val conversations: List<ChatHistoryManager.ConversationSummary>
    )

    var currentConversationId: String = KVUtils.getString(CURRENT_CONVERSATION_ID_KEY, "")
        .takeIf { it.isNotEmpty() }
        ?: newConversationId()
        private set

    fun refreshSidebar(): List<ChatHistoryManager.ConversationSummary> {
        return ChatHistoryManager.listConversations(context)
    }

    fun restoreLastConversation(): SessionSnapshot? {
        val conversations = refreshSidebar()
        val match = conversations.firstOrNull { it.id == currentConversationId } ?: return null
        return SessionSnapshot(
            conversationId = currentConversationId,
            messages = ChatHistoryManager.load(match.file),
            conversations = conversations
        )
    }

    fun saveCurrent(messages: List<ChatMessage>, modelName: String): List<ChatHistoryManager.ConversationSummary> {
        ChatHistoryManager.save(context, currentConversationId, messages, modelName)
        persistCurrentConversationId()
        // Enforce the cap right after a save — this is where a newly added chat pushes the
        // least-recently-accessed one off the bottom of the list.
        ChatHistoryManager.enforceRetention(context, currentConversationId)
        return refreshSidebar()
    }

    fun startNewConversation(
        currentMessages: List<ChatMessage>,
        modelName: String
    ): SessionSnapshot {
        saveCurrent(currentMessages, modelName)
        currentConversationId = newConversationId()
        persistCurrentConversationId()
        return SessionSnapshot(
            conversationId = currentConversationId,
            messages = emptyList(),
            conversations = refreshSidebar()
        )
    }

    fun openConversation(
        target: ChatHistoryManager.ConversationSummary,
        currentMessages: List<ChatMessage>,
        modelName: String
    ): SessionSnapshot {
        // Persist the outgoing conversation first, still under its own id.
        ChatHistoryManager.save(context, currentConversationId, currentMessages, modelName)
        // Switch identity to the selected conversation.
        currentConversationId = target.id
        persistCurrentConversationId()
        // Mark it as most-recently-accessed so it sorts to the top AND is protected from retention
        // (touch happens before enforceRetention, so opening the oldest chat at the cap never
        // deletes the very chat being opened).
        ChatHistoryManager.touchAccess(target.file)
        val messages = ChatHistoryManager.load(target.file)
        ChatHistoryManager.enforceRetention(context, currentConversationId)
        return SessionSnapshot(
            conversationId = currentConversationId,
            messages = messages,
            conversations = refreshSidebar()
        )
    }

    fun renameConversation(
        target: ChatHistoryManager.ConversationSummary,
        newTitle: String
    ): Boolean {
        return ChatHistoryManager.rename(target.file, newTitle)
    }

    fun deleteConversation(target: ChatHistoryManager.ConversationSummary): Boolean {
        val deleted = ChatHistoryManager.delete(target.file)
        runCatching {
            val db = ChatDatabase(context)
            try { db.deleteConversation(target.id) } finally { db.close() }
        }
        return deleted
    }

    private fun persistCurrentConversationId() {
        KVUtils.putString(CURRENT_CONVERSATION_ID_KEY, currentConversationId)
    }
}
