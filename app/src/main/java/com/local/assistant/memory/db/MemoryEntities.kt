package com.local.assistant.memory.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.PrimaryKey
import com.local.assistant.data.db.ChatEntity
import com.local.assistant.data.db.MessageEntity

/**
 * Where a fact is filed. Assigned in code from the subject and attribute (see
 * `FactCategorizer`), never by the model, so classification does not hinge on a 4B model's
 * judgement. The declaration order is also the priority order for core memory.
 */
enum class FactCategory { PREFERENCE, PROFILE, PEOPLE, PLACES, WORK, HEALTH, ROUTINE, OTHER }

/** How a fact got written, which decides what may overwrite it. */
enum class FactOrigin {
    /** A `save_fact` tool call during a chat. */
    CHAT,

    /** Nightly extraction from older messages. Never overwrites a [USER_EDIT]. */
    EXTRACTED,

    /** Typed on the memory screen. */
    USER_EDIT,
}

enum class TaskStatus { OPEN, DONE, CANCELLED }

enum class SummaryStatus { PENDING, DONE, FAILED }

/**
 * One thing the assistant knows: `subject.attribute = value`.
 *
 * [subject] and [attribute] are normalised keys ("user", "dentist"); [value] is display text
 * ("Dr. Rao"). The pair is unique, so a new statement about the same thing replaces the old one
 * rather than piling up next to it.
 */
@Entity(
    tableName = "facts",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceMessageId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["subject", "attribute"], unique = true),
        Index("sourceMessageId"),
        Index("core"),
    ],
)
data class FactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val subject: String,
    val attribute: String,
    val value: String,
    val category: FactCategory,
    /** Rendered into every conversation's system prefix. Only `user` facts may be core. */
    val core: Boolean,
    val origin: FactOrigin,
    val sourceMessageId: Long?,
    /** When the statement this value came from was made. The newest statement wins. */
    val statedAt: Long,
    val createdAt: Long,
    val updatedAt: Long,
    /** Last time anything restated this value; breaks ties when core memory is over budget. */
    val lastConfirmedAt: Long,
)

/** Keyword index over facts, kept in sync by Room's triggers. */
@Fts4(contentEntity = FactEntity::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "facts_fts")
data class FactFts(
    val subject: String,
    val attribute: String,
    val value: String,
)

/**
 * Another name for an entity: `amma → mother`, `rao → dr_rao`. Aliases describe an entity rather
 * than one fact, so they live here instead of being copied onto every fact row.
 */
@Entity(tableName = "subject_aliases", indices = [Index("subject")])
data class SubjectAliasEntity(
    @PrimaryKey val alias: String,
    val subject: String,
)

/**
 * A tombstone. Extraction reads old messages, so without this it would happily re-learn a fact
 * the user deleted. An empty [attribute] covers the whole subject.
 */
@Entity(tableName = "forgotten", primaryKeys = ["subject", "attribute"])
data class ForgottenEntity(
    val subject: String,
    val attribute: String,
    val forgottenAt: Long,
)

@Entity(
    tableName = "tasks",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceMessageId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index(value = ["status", "dueAt"]), Index("sourceMessageId")],
)
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val notes: String? = null,
    /** Null for an undated to-do: it lives in the list and never notifies. */
    val dueAt: Long? = null,
    /** RRULE subset. Completing a repeating task advances [dueAt] instead of closing it. */
    val repeatRule: String? = null,
    val status: TaskStatus = TaskStatus.OPEN,
    val sourceMessageId: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val completedAt: Long? = null,
)

@Entity(
    tableName = "events",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceMessageId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("startsAt"), Index("sourceMessageId")],
)
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val startsAt: Long,
    val endsAt: Long? = null,
    val allDay: Boolean = false,
    /** Same RRULE subset as tasks. */
    val recurrence: String? = null,
    val notes: String? = null,
    val sourceMessageId: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * One foreground period within one chat. The app has many chats, so a session belongs to a
 * chat; ending one (after the app has been in the background for a while) is what produces a
 * summary for the next session to start from.
 */
@Entity(
    tableName = "sessions",
    foreignKeys = [
        ForeignKey(
            entity = ChatEntity::class,
            parentColumns = ["id"],
            childColumns = ["chatId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("chatId"), Index("endedAt")],
)
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chatId: Long,
    val startedAt: Long,
    val endedAt: Long? = null,
    /** Context for later sessions only — never a source of facts. */
    val summary: String? = null,
    val summaryStatus: SummaryStatus = SummaryStatus.PENDING,
)

/**
 * One archived exchange: the user's message plus the start of the reply, keyword-indexed and
 * (from Phase 3) embedded.
 *
 * [embedding] is the source of truth for vectors; any vector index is derived from it and can
 * be rebuilt. [modelId] stays null until the chunk is embedded, which makes the embedding
 * backlog an ordinary indexed lookup. (A partial `WHERE embedding IS NULL` index would have to be
 * created outside Room, and Room's schema validation after the next migration would reject an
 * index it does not know about.)
 */
@Entity(
    tableName = "chunks",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["messageId"], unique = true),
        Index(value = ["chatId", "messageId"]),
        Index("modelId"),
        Index("createdAt"),
    ],
)
class ChunkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** The user message that opens the exchange. */
    val messageId: Long,
    val chatId: Long,
    val sessionId: Long?,
    val text: String,
    /** int8 vector, L2-normalised before quantisation. Null until embedded. */
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB)
    val embedding: ByteArray? = null,
    /** Embedder name and dimensions. Vectors from different models are never compared. */
    val modelId: String? = null,
    val createdAt: Long,
)

@Fts4(contentEntity = ChunkEntity::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "chunks_fts")
data class ChunkFts(val text: String)

/** Watermarks and small settings mirrors that have to change in the same transaction as data. */
@Entity(tableName = "app_state")
data class AppStateEntity(
    @PrimaryKey val key: String,
    val value: String,
)

/**
 * An image the user asked to be remembered, kept with what the model saw in it at the time.
 *
 * The details are what recall searches; the image itself is what answers the questions nobody
 * thought to write down. When a later message is about it, the image is attached to that turn
 * and the model looks at it again ("what was the phone number on that card?").
 */
@Entity(
    tableName = "notes",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceMessageId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("sourceMessageId")],
)
class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** "Dr. Mehta's visiting card". */
    val title: String,
    /** What the model saw in it when saving, in its words. */
    val details: String,
    /** The app's own copy of the image, kept even if the chat it came from is deleted. */
    val imagePath: String?,
    /** The message the image came with; null once that chat is deleted. */
    val sourceMessageId: Long?,
    /**
     * The chat it was sent in, so recall can tell when the image is already in front of the
     * model. Deliberately not a foreign key: the note outlives the chat.
     */
    val chatId: Long?,
    /** int8, like the archive's (see `Int8Vectors`); null until embedded. */
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB)
    val embedding: ByteArray? = null,
    val modelId: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

/** Keyword index over notes, kept in sync by Room's triggers. */
@Fts4(contentEntity = NoteEntity::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "notes_fts")
data class NoteFts(val title: String, val details: String)
