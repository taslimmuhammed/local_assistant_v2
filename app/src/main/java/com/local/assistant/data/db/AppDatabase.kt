package com.local.assistant.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.local.assistant.memory.db.AgendaDao
import com.local.assistant.memory.db.AppStateDao
import com.local.assistant.memory.db.AppStateEntity
import com.local.assistant.memory.db.ChunkDao
import com.local.assistant.memory.db.ChunkEntity
import com.local.assistant.memory.db.ChunkFts
import com.local.assistant.memory.db.EventEntity
import com.local.assistant.memory.db.FactDao
import com.local.assistant.memory.db.FactEntity
import com.local.assistant.memory.db.FactFts
import com.local.assistant.memory.db.ForgottenEntity
import com.local.assistant.memory.db.MaintenanceDao
import com.local.assistant.memory.db.SessionDao
import com.local.assistant.memory.db.SessionEntity
import com.local.assistant.memory.db.SubjectAliasEntity
import com.local.assistant.memory.db.TaskEntity
import com.local.assistant.memory.retrieval.SqliteVec
import kotlinx.coroutines.Dispatchers

/**
 * One file for chats and memory, so that a message, its archive chunk and any fact learned from
 * it commit or roll back together, and forgetting something is a single transaction.
 */
@Database(
    entities = [
        ChatEntity::class,
        MessageEntity::class,
        SessionEntity::class,
        FactEntity::class,
        FactFts::class,
        SubjectAliasEntity::class,
        ForgottenEntity::class,
        TaskEntity::class,
        EventEntity::class,
        ChunkEntity::class,
        ChunkFts::class,
        AppStateEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun chatDao(): ChatDao

    abstract fun factDao(): FactDao

    abstract fun agendaDao(): AgendaDao

    abstract fun sessionDao(): SessionDao

    abstract fun appStateDao(): AppStateDao

    abstract fun chunkDao(): ChunkDao

    abstract fun maintenanceDao(): MaintenanceDao

    /** The vector index is a derived table outside Room's schema, created where it can be. */
    object VectorTableCallback : Callback() {
        override fun onOpen(connection: SQLiteConnection) = SqliteVec.createTable(connection)
    }

    companion object {
        const val FILE_NAME = "assistant.db"

        /** Adds attachment columns. Existing chats keep their text and survive the upgrade. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE messages ADD COLUMN attachmentPath TEXT")
                connection.execSQL("ALTER TABLE messages ADD COLUMN attachmentKind TEXT")
                connection.execSQL("ALTER TABLE messages ADD COLUMN attachmentDurationMs INTEGER")
            }
        }

        /** Adds per-reply speed stats. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE messages ADD COLUMN tokensPerSecond REAL")
                connection.execSQL("ALTER TABLE messages ADD COLUMN timeToFirstTokenMs INTEGER")
            }
        }

        /** Pause memory: messages written while it is on are kept but never learned from. */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE messages ADD COLUMN offRecord INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, Migration3To4, MIGRATION_4_5)

        /**
         * [withVectors]: sqlite-vec loaded on this device (see [SqliteVec.probe]). Its table is
         * then made on open; without it the archive's vectors are searched in Kotlin instead.
         */
        fun build(context: Context, withVectors: Boolean): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, FILE_NAME)
                // Our own SQLite build rather than the platform's: same version on every device,
                // and it can load extensions (sqlite-vec) where the framework one cannot.
                .setDriver(SqliteVec.driver(withVectors))
                .setQueryCoroutineContext(Dispatchers.IO)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(*MIGRATIONS)
                .apply { if (withVectors) addCallback(VectorTableCallback) }
                .build()
    }
}
