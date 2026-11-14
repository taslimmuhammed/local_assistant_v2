package com.local.assistant.inbox

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers

/**
 * A message that came to the phone, kept for search ([Inbox]): from a chat app's notification,
 * or from the SMS inbox while the app is the phone's assistant ([SmsSync]).
 *
 * [text] is as [com.local.assistant.away.NotificationLines.clean] left it: one-time codes and
 * secrets never stored, card and ID numbers masked. [dedupeKey] keeps a message from being kept
 * twice: chat apps post a conversation's recent messages again with every new one.
 */
@Entity(
    tableName = "messages",
    indices = [Index(value = ["dedupeKey"], unique = true), Index("at"), Index("conversationKey")],
)
data class InboxMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** A [Source] name. */
    val source: String,
    val dedupeKey: String,
    val packageName: String,
    /** The app as its icon names it: "WhatsApp", "Messages"; "SMS" for the inbox read directly. */
    val appLabel: String,
    val conversationKey: String,
    /** The chat's name: the group, or the person. */
    val title: String,
    /** Who wrote it; null for the user's own messages. */
    val sender: String?,
    val fromMe: Boolean,
    val text: String,
    val at: Long,
    val keptAt: Long,
) {
    enum class Source { NOTIFICATION, SMS }
}

/** Keyword index over the messages, kept in sync by Room's triggers. */
@Fts4(contentEntity = InboxMessage::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "messages_fts")
data class InboxMessageFts(
    val title: String,
    val sender: String?,
    val text: String,
)

/** An app messages were kept from, and how many ([InboxDao.apps]). */
data class KeptApp(val packageName: String, val appLabel: String, val kept: Int)

@Dao
interface InboxDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(rows: List<InboxMessage>): List<Long>

    /** Messages matching an FTS4 MATCH expression within [from, to], newest first. */
    @Query(
        """
        SELECT messages.* FROM messages
        JOIN messages_fts ON messages.id = messages_fts.rowid
        WHERE messages_fts MATCH :match AND messages.at BETWEEN :from AND :to
        ORDER BY messages.at DESC
        LIMIT :limit
        """,
    )
    suspend fun search(match: String, from: Long, to: Long, limit: Int): List<InboxMessage>

    /** Messages from someone, or in a chat, named like [pattern] (a LIKE pattern), newest first. */
    @Query(
        """
        SELECT * FROM messages
        WHERE (sender LIKE :pattern OR title LIKE :pattern) AND at BETWEEN :from AND :to
        ORDER BY at DESC
        LIMIT :limit
        """,
    )
    suspend fun named(pattern: String, from: Long, to: Long, limit: Int): List<InboxMessage>

    @Query("SELECT * FROM messages WHERE at BETWEEN :from AND :to ORDER BY at DESC LIMIT :limit")
    suspend fun recent(from: Long, to: Long, limit: Int): List<InboxMessage>

    /** What one app's notifications kept since [since]: to leave out SMS that came that way too. */
    @Query("SELECT * FROM messages WHERE source = 'NOTIFICATION' AND packageName = :packageName AND at >= :since")
    suspend fun fromApp(packageName: String, since: Long): List<InboxMessage>

    /** Each app something was kept from, with how many, for Settings › Messages. */
    @Query("SELECT packageName, MAX(appLabel) AS appLabel, COUNT(*) AS kept FROM messages GROUP BY packageName")
    suspend fun apps(): List<KeptApp>

    @Query("SELECT COUNT(*) FROM messages")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM messages WHERE source = :source")
    suspend fun count(source: String): Int

    @Query("DELETE FROM messages WHERE at < :before")
    suspend fun deleteBefore(before: Long): Int

    @Query("DELETE FROM messages WHERE packageName = :packageName")
    suspend fun deleteApp(packageName: String): Int

    @Query("DELETE FROM messages")
    suspend fun deleteAll()
}

/**
 * Its own file, as the brief's away.db: other people's words, never learned from, kept apart so
 * a migration can't put the user's chats and memory at risk, and easy to wipe whole.
 */
@Database(entities = [InboxMessage::class, InboxMessageFts::class], version = 1, exportSchema = true)
abstract class InboxDatabase : RoomDatabase() {

    abstract fun dao(): InboxDao

    companion object {
        const val FILE_NAME = "inbox.db"

        fun build(context: Context): InboxDatabase =
            Room.databaseBuilder(context, InboxDatabase::class.java, FILE_NAME)
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()
    }
}
