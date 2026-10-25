package com.local.assistant.gallery

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The photo index's migrations: the phone's 2,428 indexed photos must come through them untouched,
 * and Room checks each result against its own schema.
 */
@RunWith(AndroidJUnit4::class)
class GalleryMigrationTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val file = instrumentation.targetContext.getDatabasePath("gallery-migration-test.db")

    @get:Rule
    val helper = MigrationTestHelper(
        instrumentation = instrumentation,
        file = file,
        driver = BundledSQLiteDriver(),
        databaseClass = GalleryDatabase::class,
    )

    @Before
    @After
    fun freshFile() {
        listOf("", "-wal", "-shm", ".lck").forEach { File(file.path + it).delete() }
    }

    @Test
    fun indexedPhotosSurviveAndFacesHangOffThem() {
        helper.createDatabase(1).use { db ->
            db.execSQL("INSERT INTO photos (mediaId, dateTaken, dateModified, width, height, embedding, modelId) VALUES (7, 100, 200, 4000, 3000, X'0102FF', 'embeddinggemma-2-tv-440m@256@v70')")
            db.execSQL("INSERT INTO photos (mediaId, dateTaken, dateModified, width, height) VALUES (8, 300, 400, 1080, 1920)")
        }

        helper.runMigrationsAndValidate(2, listOf(GalleryDatabase.MIGRATION_1_2)).use { db ->
            assertEquals(2L, db.long("SELECT COUNT(*) FROM photos"))
            assertArrayEquals(byteArrayOf(1, 2, -1), db.blob("SELECT embedding FROM photos WHERE mediaId = 7"))
            assertEquals("embeddinggemma-2-tv-440m@256@v70", db.string("SELECT modelId FROM photos WHERE mediaId = 7"))
            assertEquals(2L, db.long("SELECT COUNT(*) FROM photos WHERE facesModel IS NULL"))

            db.execSQL("PRAGMA foreign_keys = ON")
            db.execSQL("INSERT INTO people (id, name, isMe, createdAt) VALUES (1, 'Me', 1, 1)")
            db.execSQL("INSERT INTO faces (mediaId, x, y, w, h, score, print, personId, confirmed) VALUES (7, 0.1, 0.1, 0.2, 0.2, 0.9, X'00', 1, 1)")
            db.execSQL("INSERT INTO faces (mediaId, x, y, w, h, score, print, personId, confirmed) VALUES (8, 0.1, 0.1, 0.2, 0.2, 0.9, X'00', 1, 0)")

            // Deleting a person leaves their faces, unnamed; deleting a photo takes its faces.
            db.execSQL("DELETE FROM people WHERE id = 1")
            assertEquals(2L, db.long("SELECT COUNT(*) FROM faces WHERE personId IS NULL"))
            db.execSQL("DELETE FROM photos WHERE mediaId = 7")
            assertEquals(1L, db.long("SELECT COUNT(*) FROM faces"))
        }
    }

    @Test
    fun photosGainPlacesStillToRead() {
        helper.createDatabase(2).use { db ->
            db.execSQL("INSERT INTO photos (mediaId, dateTaken, dateModified, width, height, embedding, modelId, facesModel) VALUES (7, 100, 200, 4000, 3000, X'0102FF', 'embeddinggemma-2-tv-440m@256@v70', 'yunet+sface')")
        }

        helper.runMigrationsAndValidate(3, listOf(GalleryDatabase.MIGRATION_2_3)).use { db ->
            assertArrayEquals(byteArrayOf(1, 2, -1), db.blob("SELECT embedding FROM photos WHERE mediaId = 7"))
            assertEquals("yunet+sface", db.string("SELECT facesModel FROM photos WHERE mediaId = 7"))
            // Every photo's place is still to be read, and none is known yet.
            assertEquals(1L, db.long("SELECT COUNT(*) FROM photos WHERE placeChecked = 0 AND lat IS NULL AND town IS NULL"))
        }
    }

    @Test
    fun photosStayPhotosAndVideosGetFrames() {
        helper.createDatabase(3).use { db ->
            db.execSQL("INSERT INTO photos (mediaId, dateTaken, dateModified, width, height, embedding, modelId, town, placeChecked) VALUES (7, 100, 200, 4000, 3000, X'0102FF', 'embeddinggemma-2-tv-440m@256@v70', 'Munnar', 1)")
        }

        helper.runMigrationsAndValidate(4, listOf(GalleryDatabase.MIGRATION_3_4)).use { db ->
            assertEquals("Munnar", db.string("SELECT town FROM photos WHERE mediaId = 7"))
            assertEquals(1L, db.long("SELECT COUNT(*) FROM photos WHERE durationMs IS NULL"))

            db.execSQL("PRAGMA foreign_keys = ON")
            db.execSQL("INSERT INTO photos (mediaId, dateTaken, dateModified, width, height, durationMs) VALUES (9, 100, 200, 1920, 1080, 30000)")
            db.execSQL("INSERT INTO frames (mediaId, atMs, embedding) VALUES (9, 5000, X'01'), (9, 15000, X'02')")
            assertEquals(2L, db.long("SELECT COUNT(*) FROM frames"))
            // Deleting the video takes its frames.
            db.execSQL("DELETE FROM photos WHERE mediaId = 9")
            assertEquals(0L, db.long("SELECT COUNT(*) FROM frames"))
        }
    }

    private fun SQLiteConnection.long(sql: String): Long = prepare(sql).use { it.step(); it.getLong(0) }

    private fun SQLiteConnection.string(sql: String): String = prepare(sql).use { it.step(); it.getText(0) }

    private fun SQLiteConnection.blob(sql: String): ByteArray = prepare(sql).use { it.step(); it.getBlob(0) }
}
