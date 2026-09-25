package com.local.assistant.memory

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.local.assistant.data.db.AppDatabase
import com.local.assistant.data.db.AttachmentKind
import com.local.assistant.data.db.Role
import com.local.assistant.data.repo.ChatRepository
import com.local.assistant.memory.db.ArchiveRepository
import com.local.assistant.memory.db.MemoryRepository
import com.local.assistant.memory.db.SessionTracker
import com.local.assistant.memory.embed.Int8Vectors
import com.local.assistant.memory.notes.SavedImages
import com.local.assistant.memory.prompt.HeuristicTokenEstimator
import com.local.assistant.memory.retrieval.ChunkPolicy
import com.local.assistant.memory.retrieval.SqliteVec
import com.local.assistant.memory.retrieval.SqliteVecIndex
import com.local.assistant.memory.tools.ReminderScheduler
import com.local.assistant.memory.work.AppForeground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Images the user asked to have remembered, on the real schema and a real directory: the copy
 * outlives the chat, a delete can be undone until the nightly tidy, and the global controls
 * include them.
 */
@RunWith(AndroidJUnit4::class)
class SavedImagesTest {

    private lateinit var database: AppDatabase
    private lateinit var chats: ChatRepository
    private lateinit var images: SavedImages
    private lateinit var controls: MemoryControls
    private lateinit var root: File
    private var chatId = 0L

    @Before
    fun setUp() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(context.cacheDir, "saved-images-test").apply { deleteRecursively(); mkdirs() }
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .setDriver(SqliteVec.driver(withVectors = true))
            .setQueryCoroutineContext(Dispatchers.IO)
            .addCallback(AppDatabase.VectorTableCallback)
            .build()
        val archive = ArchiveRepository(database, SqliteVecIndex(database))
        chats = ChatRepository(
            database.chatDao(),
            SessionTracker(database.sessionDao(), AppForeground()),
            HeuristicTokenEstimator,
            onChatsDeleted = { archive.onChatsDeleted() },
        )
        images = SavedImages(root, database.noteDao(), chats, clock = { 1_000L })
        val reminders = object : ReminderScheduler {
            override fun canScheduleExact() = true
            override fun schedule(taskId: Long, dueAt: Long) = Unit
            override fun cancel(taskId: Long) = Unit
            override fun scheduleEvent(eventId: Long, alertAt: Long, startsAt: Long) = Unit
            override fun cancelEvent(eventId: Long) = Unit
        }
        controls = MemoryControls(database, MemoryRepository(database), archive, chats, reminders, onChanged = {}, images = images)
        chatId = chats.createChat()
    }

    @After
    fun tearDown() {
        database.close()
        root.deleteRecursively()
    }

    private fun sentImage(name: String): String =
        File(root, "attachments").apply { mkdirs() }.resolve(name).apply { writeBytes(byteArrayOf(1, 2, 3, name.length.toByte())) }.path

    private fun savedFiles(): List<File> = File(root, "saved_images").listFiles()?.toList().orEmpty()

    @Test
    fun theNewestRecentImageIsFoundAndCopiedOutOfTheChat() = runBlocking {
        chats.addMessage(chatId, Role.USER, "", attachmentPath = sentImage("old.jpg"), attachmentKind = AttachmentKind.IMAGE)
        val card = chats.addMessage(chatId, Role.USER, "my wifi card", attachmentPath = sentImage("card.jpg"), attachmentKind = AttachmentKind.IMAGE)
        chats.addMessage(chatId, Role.ASSISTANT, "It says Home_5G.")
        val ask = chats.addMessage(chatId, Role.USER, "remember this")
        chats.addMessage(chatId, Role.USER, "", attachmentPath = sentImage("later.jpg"), attachmentKind = AttachmentKind.IMAGE)

        val found = images.findImage(chatId, ask)!!
        assertEquals("not the one sent after the request", card, found.messageId)

        val id = images.save("Wifi card", "Network Home_5G, password kochi2026", found)
        val note = database.noteDao().byId(id)!!
        assertNotEquals(found.path, note.imagePath)
        assertTrue(File(note.imagePath!!).readBytes().contentEquals(File(found.path).readBytes()))
        assertEquals(chatId, note.chatId)
        assertEquals(listOf(id), database.noteDao().matching("kochi2026").map { it.id })

        // The chat goes; the saved image stays and only forgets where it came from.
        chats.deleteChat(chatId)
        val kept = database.noteDao().byId(id)!!
        assertNull(kept.sourceMessageId)
        assertTrue(File(kept.imagePath!!).isFile)
    }

    @Test
    fun onlyAFewMessagesBackCount() = runBlocking {
        chats.addMessage(chatId, Role.USER, "", attachmentPath = sentImage("receipt.jpg"), attachmentKind = AttachmentKind.IMAGE)
        var last = 0L
        repeat(8) { last = chats.addMessage(chatId, Role.USER, "and another thing $it") }
        assertNull(images.findImage(chatId, last))
        assertNull("another chat's images are not this one's", images.findImage(chats.createChat(), Long.MAX_VALUE))
    }

    @Test
    fun deleteUndoAndTheNightlyTidy() = runBlocking {
        val sent = chats.addMessage(chatId, Role.USER, "", attachmentPath = sentImage("card.jpg"), attachmentKind = AttachmentKind.IMAGE)
        val found = images.findImage(chatId, sent)!!

        // The chip's undo: the note and its copy both go at once.
        val undone = images.save("Card", "details", found)
        images.delete(undone)
        assertNull(database.noteDao().byId(undone))
        assertTrue(savedFiles().isEmpty())

        // The memory screen: the row goes, the file waits so undo can bring it back.
        val id = images.save("Card", "details", found)
        val note = database.noteDao().byId(id)!!
        controls.deleteSavedImage(note)
        assertNull(database.noteDao().byId(id))
        assertEquals(1, savedFiles().size)
        controls.restoreSavedImage(note)
        assertNotNull(database.noteDao().byId(id))
        assertEquals("nothing to tidy while the note is there", 0, images.prune())

        controls.deleteSavedImage(note)
        assertEquals(1, images.prune())
        assertTrue(savedFiles().isEmpty())
    }

    @Test
    fun embeddingSkipsWhatFailedForThisModelButRetriesForANewOne() = runBlocking {
        val sent = chats.addMessage(chatId, Role.USER, "", attachmentPath = sentImage("card.jpg"), attachmentKind = AttachmentKind.IMAGE)
        val found = images.findImage(chatId, sent)!!
        val done = images.save("Done", "details", found)
        val failed = images.save("Failed", "details", found)
        val fresh = images.save("Fresh", "details", found)
        val notes = database.noteDao()
        notes.setEmbedding(done, Int8Vectors.quantize(FloatArray(Int8Vectors.DIMENSIONS).also { it[0] = 1f }), "m1")
        notes.setEmbedding(failed, null, ChunkPolicy.FAILED_PREFIX + "m1")

        assertEquals(listOf(fresh), notes.unembedded("m1").map { it.id })
        assertEquals(listOf(done, failed, fresh), notes.unembedded("m2").map { it.id })
        assertEquals(listOf(done), notes.embedded("m1").map { it.id })
    }

    @Test
    fun exportListsThemAndForgettingEverythingDeletesThem() = runBlocking {
        val sent = chats.addMessage(chatId, Role.USER, "", attachmentPath = sentImage("card.jpg"), attachmentKind = AttachmentKind.IMAGE)
        images.save("Wifi card", "Network Home_5G", images.findImage(chatId, sent)!!)

        val json = controls.exportJson()
        assertTrue("\"saved_images\"" in json)
        assertTrue("Network Home_5G" in json)
        assertFalse("paths on the phone are not exported", root.path in json)

        controls.forgetEverything(skipExtractionTo = {})
        assertTrue(database.noteDao().all().isEmpty())
        assertTrue(savedFiles().isEmpty())
        assertTrue("the chat's own image is the chat's", File(found(sent)).isFile)
    }

    private suspend fun found(messageId: Long): String = chats.message(messageId)!!.attachmentPath!!
}
