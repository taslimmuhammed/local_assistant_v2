package com.local.assistant.memory

import androidx.room.Room
import androidx.room.useWriterConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.local.assistant.data.db.AppDatabase
import com.local.assistant.data.db.Role
import com.local.assistant.data.repo.ChatRepository
import com.local.assistant.memory.db.ArchiveRepository
import com.local.assistant.memory.db.FactOrigin
import com.local.assistant.memory.db.MemoryRepository
import com.local.assistant.memory.db.SessionTracker
import com.local.assistant.memory.db.TaskEntity
import com.local.assistant.memory.embed.Int8Vectors
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The memory screen's operations on the real schema: deleting a fact takes the archived
 * exchanges that state it along (and undo brings both back), merges carry tombstones, forgetting
 * everything leaves chats intact but nothing to learn from, and paused messages are never archived.
 */
@RunWith(AndroidJUnit4::class)
class MemoryControlsTest {

    private lateinit var database: AppDatabase
    private lateinit var memory: MemoryRepository
    private lateinit var archive: ArchiveRepository
    private lateinit var index: SqliteVecIndex
    private lateinit var chats: ChatRepository
    private lateinit var controls: MemoryControls
    private val cancelled = mutableListOf<Long>()
    private var paused = false
    private var chatId = 0L

    @Before
    fun setUp() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertNotNull(SqliteVec.probe())
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .setDriver(SqliteVec.driver(withVectors = true))
            .setQueryCoroutineContext(Dispatchers.IO)
            .addCallback(AppDatabase.VectorTableCallback)
            .build()
        memory = MemoryRepository(database)
        index = SqliteVecIndex(database)
        archive = ArchiveRepository(database, index)
        chats = ChatRepository(
            database.chatDao(),
            SessionTracker(database.sessionDao(), AppForeground()),
            HeuristicTokenEstimator,
            onChatsDeleted = { archive.onChatsDeleted() },
            memoryPaused = { paused },
        )
        val reminders = object : ReminderScheduler {
            override fun canScheduleExact() = true
            override fun schedule(taskId: Long, dueAt: Long) = Unit
            override fun cancel(taskId: Long) { cancelled += taskId }
            override fun scheduleEvent(eventId: Long, alertAt: Long, startsAt: Long) = Unit
            override fun cancelEvent(eventId: Long) = Unit
        }
        controls = MemoryControls(database, memory, archive, chats, reminders, onChanged = {})
        chatId = chats.createChat()
    }

    @After
    fun tearDown() = database.close()

    private suspend fun exchange(question: String, reply: String = "Noted."): Long {
        val id = chats.addMessage(chatId, Role.USER, question)
        chats.addMessage(chatId, Role.ASSISTANT, reply)
        archive.recordExchange(id)
        return id
    }

    private fun vector(i: Int) = Int8Vectors.quantize(FloatArray(Int8Vectors.DIMENSIONS).also { it[i] = 1f })!!

    @Test
    fun deletingAFactTakesTheExchangesThatStateItAndUndoBringsThemBack() = runBlocking {
        val said = exchange("my mother's birthday is on 12 March")
        val unrelated = exchange("my exam is on 12 March too")
        archive.adoptModel("fake@256")
        archive.backlog(10).forEachIndexed { i, chunk -> archive.saveEmbedding(chunk, vector(i), "fake@256") }
        memory.saveFact("mother", "birthday", "12 March", FactOrigin.CHAT, sourceMessageId = said)
        val fact = database.factDao().find("mother", "birthday")!!

        val deletion = controls.delete(fact)
        assertNull(database.factDao().find("mother", "birthday"))
        assertNotNull("a tombstone stays", database.factDao().tombstone("mother", "birthday"))
        assertEquals(listOf(said), deletion.snippets.map { it.messageId })
        val emptied = database.chunkDao().forMessage(said)!!
        assertEquals("", emptied.text)
        assertEquals(ChunkPolicy.FORGOTTEN, emptied.modelId)
        assertTrue(archive.keywordHits("birthday", 10).isEmpty())
        assertEquals("the exam mention is not the fact", 1, index.count())
        assertNotNull(database.chunkDao().forMessage(unrelated)!!.embedding)

        // The forgotten exchange is not archived again by the backfill or the live path.
        assertEquals(0, archive.backfill(archive.lastMessageId() + 1))
        assertFalse(archive.recordExchange(said))

        controls.undo(deletion)
        assertEquals("12 March", database.factDao().find("mother", "birthday")!!.value)
        assertNull(database.factDao().tombstone("mother", "birthday"))
        assertTrue(database.chunkDao().forMessage(said)!!.text.contains("birthday"))
        assertEquals(2, index.count())
    }

    @Test
    fun editsBecomeTheUsersAndPinsOnlyApplyToTheUser() = runBlocking {
        memory.saveFact("user", "city", "Kochi", FactOrigin.EXTRACTED)
        memory.saveFact("mother", "city", "Thrissur", FactOrigin.CHAT)
        controls.edit(database.factDao().find("user", "city")!!, "Bengaluru")
        val edited = database.factDao().find("user", "city")!!
        assertEquals("Bengaluru", edited.value)
        assertEquals(FactOrigin.USER_EDIT, edited.origin)

        controls.setCore(edited, true)
        assertTrue(database.factDao().find("user", "city")!!.core)
        controls.setCore(database.factDao().find("mother", "city")!!, true)
        assertFalse(database.factDao().find("mother", "city")!!.core)
    }

    @Test
    fun mergingMovesFactsTombstonesAndFutureWrites() = runBlocking {
        memory.saveFact("priya", "city", "Pune", FactOrigin.CHAT, statedAt = 2_000)
        memory.saveFact("priya", "phone", "98450 00000", FactOrigin.CHAT, statedAt = 1_000)
        memory.saveFact("priya_sharma", "city", "Mumbai", FactOrigin.CHAT, statedAt = 1_000)
        memory.forgetFacts("priya", "birthday")

        controls.merge(from = "priya", into = "priya_sharma")
        val about = database.factDao().bySubject("priya_sharma").associate { it.attribute to it.value }
        assertEquals(mapOf("city" to "Pune", "phone" to "98450 00000"), about)
        assertTrue(database.factDao().bySubject("priya").isEmpty())
        assertNotNull(database.factDao().tombstone("priya_sharma", "birthday"))

        // "Priya" now files under priya_sharma.
        memory.saveFact("Priya", "employer", "Infosys", FactOrigin.CHAT)
        assertEquals("Infosys", database.factDao().find("priya_sharma", "employer")!!.value)
        assertTrue(controls.duplicates().isEmpty())
    }

    @Test
    fun certainMergesRunWithoutAsking() = runBlocking {
        // Written before a rule existed, say: the old key and the canonical one side by side.
        database.factDao().insert(
            com.local.assistant.memory.db.FactEntity(
                subject = "amma", attribute = "phone", value = "94470 00000",
                category = com.local.assistant.memory.db.FactCategory.PEOPLE, core = false, origin = FactOrigin.CHAT,
                sourceMessageId = null, statedAt = 1, createdAt = 1, updatedAt = 1, lastConfirmedAt = 1,
            ),
        )
        memory.saveFact("mother", "birthday", "12 March", FactOrigin.CHAT)
        assertEquals(1, memory.mergeCertainAliases())
        assertEquals(setOf("phone", "birthday"), database.factDao().bySubject("mother").map { it.attribute }.toSet())
    }

    @Test
    fun forgettingEverythingKeepsChatsButLeavesNothingToLearnFrom() = runBlocking {
        val said = exchange("my dentist is Dr. Rao near Indiranagar")
        memory.saveFact("user", "dentist", "Dr. Rao", FactOrigin.CHAT, sourceMessageId = said)
        val task = memory.insertTask(TaskEntity(title = "Call the CA", dueAt = System.currentTimeMillis() + 3_600_000, createdAt = 0, updatedAt = 0))
        var skippedTo = -1L

        controls.forgetEverything(skipExtractionTo = { skippedTo = it })
        assertTrue(database.factDao().all().isEmpty())
        assertTrue(memory.openTasks().isEmpty())
        assertEquals(listOf(task), cancelled)
        assertEquals("", database.chunkDao().forMessage(said)!!.text)
        assertEquals(archive.lastMessageId(), skippedTo)
        assertEquals("the chat itself stays", 2, chats.messagesFor(chatId).size)
        assertEquals(0, archive.backfill(archive.lastMessageId() + 1))
    }

    @Test
    fun retentionDeletesOldMessagesAndTheirArchive() = runBlocking {
        val old = exchange("an old question about the Coorg trip")
        // Age the first exchange by forty days.
        database.execForTest("UPDATE messages SET createdAt = createdAt - ${40L * 24 * 3_600_000} WHERE id <= ${old + 1}")
        exchange("a new question about the Gokarna trip")
        assertEquals(2, controls.applyRetention(30))
        assertNull(database.chunkDao().forMessage(old))
        assertEquals(2, chats.messagesFor(chatId).size)
    }

    @Test
    fun theProfileIsPinnedAndItsOwnAndClearingForgets() = runBlocking {
        // Said in a chat under another word, before the profile existed.
        database.factDao().insert(
            com.local.assistant.memory.db.FactEntity(
                subject = "user", attribute = "occupation", value = "Teacher",
                category = com.local.assistant.memory.db.FactCategory.WORK, core = false, origin = FactOrigin.CHAT,
                sourceMessageId = null, statedAt = 1, createdAt = 1, updatedAt = 1, lastConfirmedAt = 1,
            ),
        )
        memory.saveFact("user", "name", "Taslim", FactOrigin.CHAT)
        assertEquals(mapOf("name" to "Taslim", "job" to "Teacher"), controls.profile())

        assertTrue(controls.saveProfile(mapOf("name" to "Taslim", "age" to "29", "job" to "Software engineer", "city" to " ")))
        val facts = database.factDao()
        for ((attribute, value) in listOf("name" to "Taslim", "age" to "29", "job" to "Software engineer")) {
            val fact = facts.find("user", attribute)!!
            assertEquals(value, fact.value)
            assertTrue("$attribute is always in mind", fact.core)
            assertEquals(FactOrigin.USER_EDIT, fact.origin)
        }
        assertFalse("nothing changed, nothing written", controls.saveProfile(controls.profile()))

        // Clearing a field forgets it: gone, and a tombstone so old chats cannot bring it back.
        assertTrue(controls.saveProfile(mapOf("name" to "Taslim", "age" to "29", "job" to "")))
        assertNull(facts.find("user", "job"))
        assertNotNull(facts.tombstone("user", "job"))
        memory.saveFact("user", "occupation", "Teacher", FactOrigin.EXTRACTED, statedAt = 2)
        assertNull(facts.find("user", "job"))
    }

    @Test
    fun pausedMessagesAreKeptButNeverArchived() = runBlocking {
        paused = true
        val secret = exchange("my salary is 1.2 lakh a month")
        paused = false
        assertTrue(chats.message(secret)!!.offRecord)
        assertNull(database.chunkDao().forMessage(secret))
        assertEquals(0, archive.backfill(archive.lastMessageId() + 1))
        assertTrue(database.maintenanceDao().userMessagesAfter(0, 10).isEmpty())
    }
}

private suspend fun AppDatabase.execForTest(sql: String) {
    useWriterConnection { connection -> connection.usePrepared(sql) { it.step() } }
}
