package com.local.assistant.data.repo

import com.local.assistant.data.db.AttachmentKind
import com.local.assistant.data.db.ChatDao
import com.local.assistant.data.db.ChatEntity
import com.local.assistant.data.db.MessageEntity
import com.local.assistant.data.db.Role
import com.local.assistant.memory.db.SessionTracker
import com.local.assistant.memory.prompt.TokenEstimator
import kotlinx.coroutines.flow.Flow

/**
 * Single entry point for chat persistence.
 *
 * Every message is written with its session and its token estimate, so nothing downstream ever
 * has to reconstruct either.
 */
class ChatRepository(
    private val dao: ChatDao,
    private val sessions: SessionTracker,
    private val estimator: TokenEstimator,
) {

    fun observeChats(): Flow<List<ChatEntity>> = dao.observeChats()

    fun observeMessages(chatId: Long): Flow<List<MessageEntity>> = dao.observeMessages(chatId)

    suspend fun messagesFor(chatId: Long): List<MessageEntity> = dao.messagesFor(chatId)

    /** User and assistant turns before [beforeId]: the history a conversation is built from. */
    suspend fun turnsBefore(chatId: Long, beforeId: Long): List<MessageEntity> =
        dao.turnsBefore(chatId, beforeId)

    suspend fun chat(chatId: Long): ChatEntity? = dao.chat(chatId)

    suspend fun message(messageId: Long): MessageEntity? = dao.message(messageId)

    suspend fun createChat(title: String = DEFAULT_TITLE): Long {
        val now = System.currentTimeMillis()
        return dao.insertChat(ChatEntity(title = title, createdAt = now, updatedAt = now))
    }

    suspend fun addMessage(
        chatId: Long,
        role: Role,
        text: String,
        incomplete: Boolean = false,
        attachmentPath: String? = null,
        attachmentKind: AttachmentKind? = null,
        attachmentDurationMs: Long? = null,
        tokensPerSecond: Double? = null,
        timeToFirstTokenMs: Long? = null,
    ): Long {
        val now = System.currentTimeMillis()
        return dao.appendMessage(
            MessageEntity(
                chatId = chatId,
                role = role,
                text = text,
                createdAt = now,
                incomplete = incomplete,
                attachmentPath = attachmentPath,
                attachmentKind = attachmentKind,
                attachmentDurationMs = attachmentDurationMs,
                tokensPerSecond = tokensPerSecond,
                timeToFirstTokenMs = timeToFirstTokenMs,
                sessionId = sessions.sessionFor(chatId, now),
                tokenEst = estimator.estimate(text),
            ),
        )
    }

    suspend fun updateMessage(messageId: Long, text: String, incomplete: Boolean) =
        dao.updateMessage(messageId, text, incomplete)

    /** Attachment paths still referenced by any message, for pruning orphaned files. */
    suspend fun attachmentPaths(): Set<String> = dao.attachmentPaths().toSet()

    suspend fun deleteChat(chatId: Long) = dao.deleteChat(chatId)

    suspend fun deleteAllChats() = dao.deleteAllChats()

    suspend fun renameChat(chatId: Long, title: String) = dao.renameChat(chatId, title)

    /**
     * Gives an untitled chat a name derived from its first user message, the way ChatGPT does.
     * No-op once the chat has a real title.
     */
    suspend fun titleFromFirstMessage(chatId: Long, firstMessage: String, fallback: String = DEFAULT_TITLE) {
        if (dao.chat(chatId)?.title != DEFAULT_TITLE) return
        if (firstMessage.isBlank()) {
            dao.renameChat(chatId, fallback)
            return
        }
        val title = firstMessage.trim()
            .replace(Regex("\\s+"), " ")
            .take(TITLE_MAX_CHARS)
            .ifBlank { DEFAULT_TITLE }
        dao.renameChat(chatId, if (firstMessage.trim().length > TITLE_MAX_CHARS) "$title…" else title)
    }

    companion object {
        const val DEFAULT_TITLE = "New chat"
        private const val TITLE_MAX_CHARS = 40
    }
}
