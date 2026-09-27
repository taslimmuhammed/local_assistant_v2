package com.local.assistant.memory

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.local.assistant.data.db.AppDatabase
import com.local.assistant.data.db.AttachmentKind
import com.local.assistant.data.db.Role
import com.local.assistant.data.repo.ChatRepository
import com.local.assistant.memory.db.ArchiveRepository
import com.local.assistant.memory.db.SessionEntity
import com.local.assistant.memory.db.SessionTracker
import com.local.assistant.memory.db.SummaryStatus
import com.local.assistant.memory.notes.ImageDescriber
import com.local.assistant.memory.notes.SavedImages
import com.local.assistant.memory.prompt.HeuristicTokenEstimator
import com.local.assistant.memory.retrieval.SqliteVec
import com.local.assistant.memory.retrieval.SqliteVecIndex
import com.local.assistant.memory.work.AppForeground
import com.local.assistant.memory.work.PrecisionUpgrade
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The once-only clean-up after the move to fp32, on the real schema: the fp16 model's numbers go
 * from the archive, the summaries and saved images; the user's words and later replies stay.
 */
@RunWith(AndroidJUnit4::class)
class PrecisionUpgradeTest {

    private lateinit var database: AppDatabase
    private lateinit var chats: ChatRepository
    private lateinit var archive: ArchiveRepository
    private lateinit var images: SavedImages
    private lateinit var root: File
    private var now = 1_000_000L

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(context.cacheDir, "precision-upgrade-test").apply { deleteRecursively(); mkdirs() }
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .setDriver(SqliteVec.driver(withVectors = true))
            .setQueryCoroutineContext(Dispatchers.IO)
            .addCallback(AppDatabase.VectorTableCallback)
            .build()
        archive = ArchiveRepository(database, SqliteVecIndex(database))
        chats = ChatRepository(database.chatDao(), SessionTracker(database.sessionDao(), AppForeground()), HeuristicTokenEstimator, onChatsDeleted = { archive.onChatsDeleted() })
        images = SavedImages(root, database.noteDao(), chats, clock = { now })
    }

    @After
    fun tearDown() {
        database.close()
        root.deleteRecursively()
    }

    private suspend fun exchange(chatId: Long, question: String, answer: String): Long {
        val user = chats.addMessage(chatId, Role.USER, question)
        chats.addMessage(chatId, Role.ASSISTANT, answer)
        archive.recordExchange(user)
        return user
    }

    private suspend fun chunkText(userId: Long) = database.chunkDao().forMessage(userId)?.text

    @Test
    fun theFp16ErasNumbersGoAndEverythingElseStays() = runBlocking {
        val chatId = chats.createChat()
        val rollNo = exchange(chatId, "what was my roll no for the exam", "Your Roll No. was **43610104**.")
        val greeting = exchange(chatId, "hi what do you know about me", "You are Taslim.")
        val card = chats.addMessage(chatId, Role.USER, "remember this", attachmentPath = File(root, "card.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }.path, attachmentKind = AttachmentKind.IMAGE)
        val found = images.findImage(chatId, card)!!
        val noteId = images.save("CBSE Mark Sheet", "Roll No: 436104\nDate of Birth 09/08/2001", found)
        val session = database.sessionDao().insert(SessionEntity(chatId = chatId, startedAt = 1L, endedAt = 2L, summary = "The assistant said the roll no was 43610104.", summaryStatus = SummaryStatus.DONE))
        val plain = database.sessionDao().insert(SessionEntity(chatId = chatId, startedAt = 3L, endedAt = 4L, summary = "The user said hello.", summaryStatus = SummaryStatus.DONE))
        database.chatDao().setRollingSummary(chatId, "Earlier the assistant gave the date as 09/08/20001.", 10L)

        // The move to fp32 happens here; exchanges are stamped with the real clock.
        Thread.sleep(5)
        val movedAt = System.currentTimeMillis()
        Thread.sleep(5)
        val read = mutableListOf<String>()
        val upgrade = PrecisionUpgrade(
            database, archive, images,
            reread = { path -> read += path; ImageDescriber.Description("ignored", "Roll No: 4361044\nDate of Birth 09/08/2001") },
            onChanged = {},
            clock = { movedAt },
            retryDelayMs = 0,
        )
        val later = exchange(chatId, "what's my roll no", "Your roll number is 4361044.")

        val report = upgrade.run()!!

        assertEquals("User: what was my roll no for the exam", chunkText(rollNo))
        assertEquals("a reply without numbers stays", "User: hi what do you know about me\nAssistant: You are Taslim.", chunkText(greeting))
        assertEquals("a reply from after the move stays", "User: what's my roll no\nAssistant: Your roll number is 4361044.", chunkText(later).also { require(it != null) })
        assertNull(database.sessionDao().summarised().firstOrNull { it.id == session })
        assertEquals("The user said hello.", database.sessionDao().summarised().single { it.id == plain }.summary)
        assertNull(database.chatDao().chat(chatId)!!.rollingSummary)
        val note = database.noteDao().byId(noteId)!!
        assertEquals("CBSE Mark Sheet", note.title)
        assertEquals("Roll No: 4361044\nDate of Birth 09/08/2001", note.details)
        assertEquals(listOf(note.imagePath), read)
        assertEquals(PrecisionUpgrade.Report(exchanges = 1, summaries = 2, images = 1), report)

        assertNull("only once", upgrade.run())
    }

    @Test
    fun anImageTheModelCantGetToYetIsTriedAgain() = runBlocking {
        val chatId = chats.createChat()
        val card = chats.addMessage(chatId, Role.USER, "remember this", attachmentPath = File(root, "card.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }.path, attachmentKind = AttachmentKind.IMAGE)
        val noteId = images.save("Card", "PIN 44417", images.findImage(chatId, card)!!)
        var tries = 0
        val upgrade = PrecisionUpgrade(
            database, archive, images,
            reread = { if (++tries < 3) null else ImageDescriber.Description("Card", "PIN 4417") },
            onChanged = {},
            clock = { 2_000_000_000_000L },
            retryDelayMs = 0,
        )
        upgrade.run()
        assertEquals(3, tries)
        assertEquals("PIN 4417", database.noteDao().byId(noteId)!!.details)
    }
}
