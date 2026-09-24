package com.local.assistant.data.db

import androidx.room.ColumnInfo
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
    /**
     * "Earlier in this chat": the turns that were folded out of the verbatim window, summarised.
     * Persisted so the live conversation can be rebuilt the same way after process death.
     */
    val rollingSummary: String? = null,
    /** Last message [rollingSummary] covers. Everything after it is sent verbatim. */
    val rollingUptoMessageId: Long? = null,
)

/**
 * Persisted author of a message. Kept as a string column so adding roles later is a no-op.
 *
 * [TOOL] rows record a tool call and its result. They are never shown as chat bubbles, never
 * chunked or embedded, and never replayed into a rebuilt conversation: by then whatever the tool
 * changed is already in the system prefix.
 */
enum class Role { USER, ASSISTANT, TOOL }

/** What kind of file a message carries alongside its text. */
enum class AttachmentKind { IMAGE, AUDIO }

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
    indices = [Index("chatId"), Index("sessionId"), Index("createdAt")],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chatId: Long,
    val role: Role,
    val text: String,
    val createdAt: Long,
    /** True when generation was stopped or failed part-way; the text is still what we got. */
    val incomplete: Boolean = false,
    /** Absolute path inside the app's attachments directory, or null for a text-only message. */
    val attachmentPath: String? = null,
    val attachmentKind: AttachmentKind? = null,
    /** Recording length, for the duration label on a voice message. */
    val attachmentDurationMs: Long? = null,
    /** Decode speed reported by the runtime for this reply. Null for user turns. */
    val tokensPerSecond: Double? = null,
    /** Prefill latency: how long the model took before the first token appeared. */
    val timeToFirstTokenMs: Long? = null,
    /**
     * The foreground period this message was written in. Null only for rows that predate
     * sessions and could not be attributed. Deliberately not a foreign key: deleting the chat
     * already cascades to both.
     */
    val sessionId: Long? = null,
    /**
     * Estimated tokens for [text], computed once at write so budgeting never re-estimates the
     * whole history. Attachments are charged separately, at budgeting time.
     */
    @ColumnInfo(defaultValue = "0")
    val tokenEst: Int = 0,
)
