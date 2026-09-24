package com.local.assistant.memory.tools

import com.google.gson.Gson
import com.local.assistant.data.db.Role
import com.local.assistant.data.repo.ChatRepository

/**
 * Tool calls stored as TOOL rows in the chat they happened in, as JSON. Their place among the
 * messages is what ties a chip to the reply it belongs under.
 */
class ChatToolLog(private val chats: ChatRepository) : ToolLog {

    override suspend fun record(chatId: Long, record: ToolRecord): Long =
        chats.addMessage(chatId = chatId, role = Role.TOOL, text = gson.toJson(record))

    override suspend fun read(id: Long): ToolRecord? =
        chats.message(id)?.takeIf { it.role == Role.TOOL }?.let { parse(it.text) }

    override suspend fun update(id: Long, record: ToolRecord) =
        chats.updateMessage(id, gson.toJson(record), incomplete = false)

    companion object {
        private val gson = Gson()

        /** Null for a row that is not a readable tool record. */
        fun parse(json: String): ToolRecord? = runCatching { gson.fromJson(json, ToolRecord::class.java) }.getOrNull()
    }
}
