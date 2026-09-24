package com.local.assistant.data.db

import androidx.room.Room
import androidx.room.useReaderConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Upgrades a *copy* of this device's real chat database and checks nothing was lost. The
 * original is never opened. Opt-in, since it reads personal data:
 *
 *     adb shell am instrument -w -e realdata true \
 *         -e class com.local.assistant.data.db.RealDataMigrationTest \
 *         com.local.assistant.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class RealDataMigrationTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun onlyWhenAsked() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("realdata") == "true")
    }

    @Test
    fun theRealDatabaseUpgradesWithoutLoss() {
        val original = context.getDatabasePath(AppDatabase.FILE_NAME)
        assumeTrue("no database on this device", original.exists())

        val copy = File(context.cacheDir, "migration-copy.db")
        // The bundled driver also leaves a .lck file beside the database.
        val suffixes = listOf("", "-wal", "-shm", ".lck")
        suffixes.forEach { File(copy.path + it).delete() }
        suffixes.forEach { suffix ->
            File(original.path + suffix).takeIf { it.exists() }?.copyTo(File(copy.path + suffix))
        }

        val before = SqliteCounts.read(copy)
        try {
            val database = Room.databaseBuilder(context, AppDatabase::class.java, copy.absolutePath)
                .setDriver(BundledSQLiteDriver())
                .addMigrations(*AppDatabase.MIGRATIONS)
                .build()
            val after = runBlocking {
                database.useReaderConnection { connection ->
                    connection.usePrepared(
                        "SELECT (SELECT COUNT(*) FROM chats), (SELECT COUNT(*) FROM messages), " +
                            "(SELECT COUNT(*) FROM sessions), " +
                            "(SELECT COUNT(*) FROM messages WHERE sessionId IS NULL), " +
                            "(SELECT COUNT(*) FROM messages WHERE tokenEst = 0 AND text != '')",
                    ) {
                        it.step()
                        List(5) { i -> it.getLong(i) }
                    }
                }
            }
            database.close()
            println("RealDataMigration before=$before after=$after")
            assertEquals("chats", before.first, after[0])
            assertEquals("messages", before.second, after[1])
            assertEquals("one session per chat", after[0], after[2])
            assertEquals("unattributed messages", 0L, after[3])
            assertEquals("messages without an estimate", 0L, after[4])
        } finally {
            suffixes.forEach { File(copy.path + it).delete() }
        }
    }

    /** Counts read straight from the file before Room touches it. */
    private object SqliteCounts {
        fun read(file: File): Pair<Long, Long> {
            val connection = BundledSQLiteDriver().open(file.absolutePath)
            return try {
                connection.prepare("SELECT (SELECT COUNT(*) FROM chats), (SELECT COUNT(*) FROM messages)").use {
                    it.step()
                    it.getLong(0) to it.getLong(1)
                }
            } finally {
                connection.close()
            }
        }
    }
}
