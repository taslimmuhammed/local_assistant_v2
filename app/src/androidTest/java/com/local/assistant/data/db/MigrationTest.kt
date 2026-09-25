package com.local.assistant.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The upgrade runs on real chats, so it is tested against rows shaped like the ones on a phone
 * today — and Room validates the migrated schema against its own at the end of every test.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val file = instrumentation.targetContext.getDatabasePath("migration-test.db")

    @get:Rule
    val helper = MigrationTestHelper(
        instrumentation = instrumentation,
        file = file,
        driver = BundledSQLiteDriver(),
        databaseClass = AppDatabase::class,
    )

    /** The helper does not clear up after itself, and each test starts from an older version. */
    @Before
    @After
    fun freshFile() {
        listOf("", "-wal", "-shm", ".lck").forEach { File(file.path + it).delete() }
    }

    @Test
    fun version3ChatsSurviveTheMemoryUpgrade() {
        helper.createDatabase(3).use { db ->
            db.execSQL("INSERT INTO chats (id, title, createdAt, updatedAt) VALUES (1, 'Dentist', 1000, 5000)")
            db.execSQL("INSERT INTO chats (id, title, createdAt, updatedAt) VALUES (2, 'Trip', 6000, 9000)")
            db.execSQL(
                "INSERT INTO messages (id, chatId, role, text, createdAt, incomplete) " +
                    "VALUES (1, 1, 'USER', 'my dentist is Dr. Rao', 1000, 0)",
            )
            db.execSQL(
                "INSERT INTO messages (id, chatId, role, text, createdAt, incomplete, tokensPerSecond) " +
                    "VALUES (2, 1, 'ASSISTANT', 'Noted.', 1200, 0, 19.5)",
            )
            db.execSQL(
                "INSERT INTO messages (id, chatId, role, text, createdAt, incomplete) " +
                    "VALUES (3, 2, 'USER', 'कल सुबह कोच्चि जाना है', 6000, 0)",
            )
        }

        helper.runMigrationsAndValidate(4, AppDatabase.MIGRATIONS.toList()).use { db ->
            // Text, stats and ordering untouched.
            assertEquals(listOf("my dentist is Dr. Rao", "Noted.", "कल सुबह कोच्चि जाना है"), db.strings("SELECT text FROM messages ORDER BY id"))
            assertEquals(19.5, db.double("SELECT tokensPerSecond FROM messages WHERE id = 2"), 0.0)

            // One ended session per chat, summary pending, and every message attributed.
            assertEquals(2L, db.long("SELECT COUNT(*) FROM sessions"))
            assertEquals(0L, db.long("SELECT COUNT(*) FROM sessions WHERE endedAt IS NULL OR summaryStatus != 'PENDING'"))
            assertEquals(0L, db.long("SELECT COUNT(*) FROM messages WHERE sessionId IS NULL"))
            assertEquals(
                db.long("SELECT id FROM sessions WHERE chatId = 1"),
                db.long("SELECT sessionId FROM messages WHERE id = 2"),
            )

            // Token estimates filled in, Devanagari charged more densely than Latin.
            assertEquals(0L, db.long("SELECT COUNT(*) FROM messages WHERE tokenEst <= 0"))
            assertTrue(db.long("SELECT tokenEst FROM messages WHERE id = 3") > db.long("SELECT tokenEst FROM messages WHERE id = 1"))

            // The new keyword index is live: its triggers were created by the migration.
            db.execSQL(
                "INSERT INTO facts (subject, attribute, value, category, core, origin, statedAt, createdAt, updatedAt, lastConfirmedAt) " +
                    "VALUES ('user', 'dentist', 'Dr. Rao', 'PEOPLE', 0, 'CHAT', 1, 1, 1, 1)",
            )
            assertEquals(1L, db.long("SELECT COUNT(*) FROM facts_fts WHERE facts_fts MATCH 'rao'"))

            // Deleting a chat still cascades through everything that hangs off it. (Room's
            // generated onOpen turns foreign keys on; this raw test connection has to ask.)
            db.execSQL("PRAGMA foreign_keys = ON")
            db.execSQL("DELETE FROM chats WHERE id = 1")
            assertEquals(1L, db.long("SELECT COUNT(*) FROM sessions"))
            assertEquals(1L, db.long("SELECT COUNT(*) FROM messages"))
        }
    }

    @Test
    fun everyVersionUpgradesToTheLatest() {
        helper.createDatabase(1).use { db ->
            db.execSQL("INSERT INTO chats (id, title, createdAt, updatedAt) VALUES (1, 'Old', 1, 2)")
            db.execSQL("INSERT INTO messages (chatId, role, text, createdAt, incomplete) VALUES (1, 'USER', 'hello', 1, 0)")
        }
        helper.runMigrationsAndValidate(6, AppDatabase.MIGRATIONS.toList()).use { db ->
            assertEquals(1L, db.long("SELECT COUNT(*) FROM messages WHERE sessionId IS NOT NULL AND tokenEst > 0 AND offRecord = 0"))
        }
    }

    @Test
    fun version4MessagesStayOnTheRecord() {
        helper.createDatabase(4).use { db ->
            db.execSQL("INSERT INTO chats (id, title, createdAt, updatedAt) VALUES (1, 'Trip', 1, 2)")
            db.execSQL("INSERT INTO messages (id, chatId, role, text, createdAt, incomplete, tokenEst) VALUES (1, 1, 'USER', 'plan Gokarna', 1, 0, 3)")
        }
        helper.runMigrationsAndValidate(5, AppDatabase.MIGRATIONS.toList()).use { db ->
            // Everything written before Pause memory existed was written with memory on.
            assertEquals(0L, db.long("SELECT offRecord FROM messages WHERE id = 1"))
            db.execSQL("INSERT INTO messages (chatId, role, text, createdAt, incomplete, tokenEst) VALUES (1, 'USER', 'x', 2, 0, 1)")
            assertEquals(0L, db.long("SELECT offRecord FROM messages WHERE text = 'x'"))
        }
    }

    @Test
    fun version5GainsSavedImagesWithTheirSearchIndex() {
        helper.createDatabase(5).use { db ->
            db.execSQL("INSERT INTO chats (id, title, createdAt, updatedAt) VALUES (1, 'Wifi', 1, 2)")
            db.execSQL("INSERT INTO messages (id, chatId, role, text, createdAt, incomplete, tokenEst, offRecord) VALUES (7, 1, 'USER', 'remember this', 1, 0, 3, 0)")
        }
        helper.runMigrationsAndValidate(6, AppDatabase.MIGRATIONS.toList()).use { db ->
            assertEquals("the chat is untouched", 1L, db.long("SELECT COUNT(*) FROM messages"))
            db.execSQL(
                "INSERT INTO notes (id, title, details, imagePath, sourceMessageId, chatId, createdAt, updatedAt) " +
                    "VALUES (1, 'Wifi card', 'Network Home_5G, password kochi2026', '/x/card.jpg', 7, 1, 5, 5)",
            )
            // The triggers keep the index in step: insert, update and delete.
            assertEquals(1L, db.long("SELECT COUNT(*) FROM notes_fts WHERE notes_fts MATCH 'kochi2026'"))
            db.execSQL("UPDATE notes SET details = 'Network Home_5G, password goa2027' WHERE id = 1")
            assertEquals(0L, db.long("SELECT COUNT(*) FROM notes_fts WHERE notes_fts MATCH 'kochi2026'"))
            assertEquals(1L, db.long("SELECT COUNT(*) FROM notes_fts WHERE notes_fts MATCH 'goa2027'"))
            // Deleting the chat keeps the saved image; it only forgets where it came from.
            db.execSQL("PRAGMA foreign_keys = ON")
            db.execSQL("DELETE FROM messages WHERE id = 7")
            assertEquals(1L, db.long("SELECT COUNT(*) FROM notes WHERE sourceMessageId IS NULL"))
            db.execSQL("DELETE FROM notes WHERE id = 1")
            assertEquals(0L, db.long("SELECT COUNT(*) FROM notes_fts WHERE notes_fts MATCH 'goa2027'"))
        }
    }

    private fun SQLiteConnection.long(sql: String): Long = prepare(sql).use { it.step(); it.getLong(0) }

    private fun SQLiteConnection.double(sql: String): Double = prepare(sql).use { it.step(); it.getDouble(0) }

    private fun SQLiteConnection.strings(sql: String): List<String> = prepare(sql).use {
        buildList { while (it.step()) add(it.getText(0)) }
    }
}
