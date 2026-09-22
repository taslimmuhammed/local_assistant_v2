package com.local.assistant.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "chats")
data class ChatEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long,
    /** Bumped on every new message so the chat list can sort by recency. */
    val updatedAt: Long,
)

/** Persisted author of a message. Kept as a string column so adding roles later is a no-op. */
enum class Role { USER, ASSISTANT }

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatEntity::class,
            parentColumns = ["id"],
            childColumns = ["chatId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("chatId")],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chatId: Long,
    val role: Role,
    val text: String,
    val createdAt: Long,
    /** True when generation was stopped or failed part-way; the text is still what we got. */
    val incomplete: Boolean = false,
)
