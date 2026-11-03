package com.local.assistant.away

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow

/**
 * One line of what arrived while the user was away: a message, a missed call, or another app's
 * notification, as its notification showed it.
 *
 * [conversationKey] groups lines into one item of the brief: a chat, a caller, or a single
 * notification. [dedupeKey] keeps a message from being stored twice, since a chat app re-posts
 * its whole recent history with every new message. [fromMe] lines are the user's own replies,
 * which a chat app shows in the same notification. [seenAt] is set once the user has dealt with
 * it anywhere: read it in its app, dismissed it, or answered it from the brief. [briefedAt] is set
 * once it was shown in a brief the user closed, so the next brief opens on what's new.
 */
@Entity(
    tableName = "captured",
    indices = [Index(value = ["dedupeKey"], unique = true), Index("conversationKey"), Index("postedAt")],
)
data class CapturedEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dedupeKey: String,
    val notificationKey: String,
    val packageName: String,
    val appLabel: String,
    val kind: String,
    val conversationKey: String,
    /** The chat's name: the group, or the person. */
    val title: String,
    /** Who wrote it; null for the user's own lines and for notifications with no person. */
    val sender: String?,
    val fromMe: Boolean,
    val isGroup: Boolean,
    val text: String,
    val postedAt: Long,
    val capturedAt: Long,
    val seenAt: Long? = null,
    val briefedAt: Long? = null,
)

/**
 * What the model made of one conversation, up to its line [upToId]: how much it needs the user
 * ([level], a [Level] name), a line saying why ([gist]), and replies the user may send
 * ([replies], one per line). A row with [HEADLINE_KEY] holds the sentence the brief opens with.
 */
@Entity(tableName = "triage")
data class TriageEntity(
    @PrimaryKey val conversationKey: String,
    val upToId: Long,
    val level: String,
    val gist: String,
    val replies: String,
    val triagedAt: Long,
) {
    companion object {
        const val HEADLINE_KEY = "#headline"
    }
}

/** How much an item of the brief needs the user. */
enum class Level {
    /** A person waiting on the user: a question, a missed call, something urgent. */
    NEED,

    /** Worth knowing, nothing to do: a delivery, a payment, a plan changing. */
    FYI,

    /** Noise: offers, likes, group chatter. Counted, not shown. */
    SKIP,
}

/** What a captured line is. */
enum class Kind { MESSAGE, CALL, OTHER }

@Dao
interface AwayDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(rows: List<CapturedEntity>): List<Long>

    /** Unseen lines from [since] on, oldest first. */
    @Query("SELECT * FROM captured WHERE seenAt IS NULL AND postedAt >= :since ORDER BY postedAt, id")
    fun observeUnseen(since: Long): Flow<List<CapturedEntity>>

    @Query("SELECT * FROM captured WHERE seenAt IS NULL AND postedAt >= :since ORDER BY postedAt, id")
    suspend fun unseen(since: Long): List<CapturedEntity>

    /** The last lines of one conversation, newest first. */
    @Query("SELECT * FROM captured WHERE conversationKey = :key ORDER BY postedAt DESC, id DESC LIMIT :limit")
    suspend fun recent(key: String, limit: Int): List<CapturedEntity>

    @Query("UPDATE captured SET seenAt = :at WHERE conversationKey IN (:keys) AND seenAt IS NULL")
    suspend fun markSeen(keys: List<String>, at: Long): Int

    @Query("UPDATE captured SET seenAt = :at WHERE notificationKey = :notificationKey AND seenAt IS NULL")
    suspend fun markSeenByNotification(notificationKey: String, at: Long): Int

    @Query("UPDATE captured SET briefedAt = :at WHERE id IN (:ids) AND briefedAt IS NULL")
    suspend fun markBriefed(ids: List<Long>, at: Long): Int

    @Query("SELECT DISTINCT packageName, appLabel FROM captured ORDER BY appLabel")
    fun observeApps(): Flow<List<AppSeen>>

    @Query("DELETE FROM captured WHERE capturedAt < :before")
    suspend fun deleteBefore(before: Long): Int

    @Query("DELETE FROM captured WHERE packageName = :packageName")
    suspend fun deleteApp(packageName: String): Int

    @Query("DELETE FROM captured")
    suspend fun deleteAll()

    @Upsert
    suspend fun putTriage(rows: List<TriageEntity>)

    @Query("SELECT * FROM triage")
    fun observeTriage(): Flow<List<TriageEntity>>

    @Query("SELECT * FROM triage")
    suspend fun triage(): List<TriageEntity>

    @Query("DELETE FROM triage WHERE triagedAt < :before")
    suspend fun deleteTriageBefore(before: Long): Int

    @Query("DELETE FROM triage")
    suspend fun deleteAllTriage()
}

data class AppSeen(val packageName: String, val appLabel: String)

/**
 * Its own file rather than a table in assistant.db: these are other people's words, kept for two
 * days and never learned from, and a separate file can't put the user's chats and memory at risk
 * in a migration, and is easy to wipe whole.
 */
@Database(entities = [CapturedEntity::class, TriageEntity::class], version = 1, exportSchema = true)
abstract class AwayDatabase : RoomDatabase() {

    abstract fun dao(): AwayDao

    companion object {
        const val FILE_NAME = "away.db"

        fun build(context: Context): AwayDatabase =
            Room.databaseBuilder(context, AwayDatabase::class.java, FILE_NAME)
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()
    }
}
