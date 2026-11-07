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
import java.util.Locale

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
    /**
     * A place reminder's place: it rings when the user arrives there, not at a time, so
     * [dueAt] stays null. Kept as JSON (see `Converters`).
     */
    val place: TaskPlace? = null,
)

/** Where a place reminder rings: "DMart" as the nearest few branches, "office" as one spot. */
data class TaskPlace(
    /** As the user said it, for the chip and the agenda: "DMart", "office". */
    val name: String,
    val spots: List<PlaceSpot>,
    /**
     * When it last rang. Another arrival soon after is the same visit — a fix wandering back and
     * forth across the edge — and stays quiet (`PlaceAlerts.QUIET_MS`).
     */
    val rangAt: Long? = null,
    /** How far out it rings, when the user said ("5 km before I reach Munnar"); the spots' radii already include it. */
    val distance: PlaceDistance? = null,
) {
    /** "near DMart", or "5 km before Munnar": where it rings, as the chip, the agenda and the model see it. */
    fun where(): String = distance?.describe(name) ?: "near $name"

    /** Which of its spots: "any of the 5 nearest", or the one's street ("Karaparmba Road"); null when its name says it. */
    fun which(): String? = if (spots.size > 1) "any of the ${spots.size} nearest" else spots.singleOrNull()?.label?.takeUnless { it == name }
}

/**
 * How far out a place reminder rings, when the user said: "5 km before I reach Munnar" rings 5 km
 * out from where arriving in Munnar would; "within 500 m of DMart" 500 m from the shop itself.
 */
data class PlaceDistance(
    val metres: Int,
    /** "before I reach": out from the place's edge. Otherwise ("within", "away from") from the place. */
    val beforeArriving: Boolean,
) {
    /** How near counts as there, for a spot whose own size is [radiusM]. */
    fun radiusFor(radiusM: Int): Int = if (beforeArriving) radiusM + metres else maxOf(metres, MIN_RADIUS_M)

    /** A spot's own size, out of the [radiusM] this distance gave it; a circle drawn "within" leaves only itself. */
    fun ownRadius(radiusM: Int): Int = if (beforeArriving) maxOf(radiusM - metres, MIN_RADIUS_M) else radiusM

    /** "5 km before Munnar", "within 500 m of DMart". */
    fun describe(place: String): String = if (beforeArriving) "${words()} before $place" else "within ${words()} of $place"

    /** "5 km", "1.5 km", "500 m". */
    fun words(): String {
        if (metres < 1000) return "$metres m"
        val km = metres / 1000.0
        return (if (km % 1.0 == 0.0) km.toInt().toString() else String.format(Locale.ROOT, "%.1f", km)) + " km"
    }

    companion object {
        /** The furthest out a reminder rings. */
        const val MAX_M = 100_000

        /** Smaller than this a phone's fix wanders across the edge (Android's geofencing guide says 100–150 m). */
        const val MIN_RADIUS_M = 100
    }
}

/** One spot a place reminder rings near: a branch, an address, a town. */
data class PlaceSpot(
    val lat: Double,
    val lon: Double,
    /** How near counts as there, in metres. */
    val radiusM: Int,
    /** Which one, for the notification: "Hoodi Main Road". */
    val label: String? = null,
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

/** A note is free text; a list is items to tick off. Both are the user's notes. */
enum class NoteKind { NOTE, LIST }

/** One line of a list: "Eggs", ticked off once bought. */
data class NoteItem(val text: String, val done: Boolean = false)

/**
 * A note or list the user keeps: "Shopping list", "Wifi details". Made and changed in chat (the
 * edit_note tool) or on the Notes screen, and read back to the model when a message is about it.
 *
 * Not the `notes` table, which holds saved images. What recall searches is [UserNoteChunkEntity]:
 * the note cut into parts and embedded, made again every time the note changes.
 */
@Entity(
    tableName = "user_notes",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceMessageId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("sourceMessageId"), Index("updatedAt")],
)
data class UserNoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val kind: NoteKind,
    /** A note's text; empty for a list. */
    val body: String = "",
    /** A list's items, in order; empty for a note. Kept as JSON (see `Converters`). */
    val items: List<NoteItem> = emptyList(),
    /** Shown first, on the Notes screen and to the model. */
    val pinned: Boolean = false,
    /** The message that asked for it; null for one made on the Notes screen, or once that chat is deleted. */
    val sourceMessageId: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * A part of a note as recall searches it: the note's title, then a few of its items or lines.
 * A note's parts are made again whenever it changes ([com.local.assistant.memory.notes.NoteChunks]);
 * a part whose words didn't change keeps its vector.
 */
@Entity(
    tableName = "user_note_chunks",
    foreignKeys = [
        ForeignKey(
            entity = UserNoteEntity::class,
            parentColumns = ["id"],
            childColumns = ["noteId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("noteId"), Index("modelId")],
)
class UserNoteChunkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val noteId: Long,
    /** Its place in the note, from 0. */
    val ordinal: Int,
    /** The note's title, then this part of it: what is searched and embedded. */
    val text: String,
    /** Which part: a list's items [from, to), or a note's characters [from, to). */
    val fromIndex: Int,
    val toIndex: Int,
    /** int8, like the archive's (see `Int8Vectors`); null until embedded. */
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB)
    val embedding: ByteArray? = null,
    val modelId: String? = null,
)

/** Keyword index over the parts of notes, kept in sync by Room's triggers. */
@Fts4(contentEntity = UserNoteChunkEntity::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "user_note_chunks_fts")
data class UserNoteChunkFts(val text: String)
