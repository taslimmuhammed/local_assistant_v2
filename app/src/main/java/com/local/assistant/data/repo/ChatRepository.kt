package com.local.assistant.data.repo

import com.local.assistant.data.db.AttachmentKind
import com.local.assistant.data.db.ChatDao
import com.local.assistant.data.db.ChatEntity
import com.local.assistant.data.db.MessageEntity
import com.local.assistant.data.db.Role
import kotlinx.coroutines.flow.Flow

/** Single entry point for chat persistence. */
class ChatRepository(private val dao: ChatDao) {

    fun observeChats(): Flow<List<ChatEntity>> = dao.observeChats()

    fun observeMessages(chatId: Long): Flow<List<MessageEntity>> = dao.observeMessages(chatId)

    suspend fun messagesFor(chatId: Long): List<MessageEntity> = dao.messagesFor(chatId)

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
    ): Long = dao.appendMessage(
        MessageEntity(
            chatId = chatId,
            role = role,
            text = text,
            createdAt = System.currentTimeMillis(),
            incomplete = incomplete,
            attachmentPath = attachmentPath,
            attachmentKind = attachmentKind,
            attachmentDurationMs = attachmentDurationMs,
        ),
    )

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
