package com.local.assistant.memory

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.local.assistant.data.db.AppDatabase
import com.local.assistant.data.db.Role
import com.local.assistant.data.repo.ChatRepository
import com.local.assistant.llm.ToolCall
import com.local.assistant.memory.db.EventEntity
import com.local.assistant.memory.db.MemoryRepository
import com.local.assistant.memory.db.SessionTracker
import com.local.assistant.memory.db.TaskEntity
import com.local.assistant.memory.prompt.HeuristicTokenEstimator
import com.local.assistant.memory.tools.ChatToolLog
import com.local.assistant.memory.tools.ReminderScheduler
import com.local.assistant.memory.tools.ToolContext
import com.local.assistant.memory.tools.ToolExecutor
import com.local.assistant.memory.work.AppForeground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The tool layer against the real database: a call and its TOOL row commit together (which
 * nests Room's own transactions inside ours), chips survive the round trip through the chat,
 * and the task, event and keyword queries behave on the bundled SQLite.
 */
@RunWith(AndroidJUnit4::class)
class ToolStoreTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private var now = ZonedDateTime.of(2026, 9, 21, 10, 0, 0, 0, zone)
    private lateinit var database: AppDatabase
    private lateinit var memory: MemoryRepository
    private lateinit var chats: ChatRepository
    private lateinit var executor: ToolExecutor
    private val scheduled = mutableMapOf<Long, Long>()
    private var chatId = 0L

    @Before
    fun setUp() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        memory = MemoryRepository(database, clock = { now.toInstant().toEpochMilli() }, zone = { zone })
        chats = ChatRepository(database.chatDao(), SessionTracker(database.sessionDao(), AppForeground()), HeuristicTokenEstimator)
        val reminders = object : ReminderScheduler {
            override fun canScheduleExact() = true
            override fun schedule(taskId: Long, dueAt: Long) { scheduled[taskId] = dueAt }
            override fun cancel(taskId: Long) { scheduled.remove(taskId) }
            override fun scheduleEvent(eventId: Long, alertAt: Long, startsAt: Long) = Unit
            override fun cancelEvent(eventId: Long) = Unit
        }
        val noClock = object : com.local.assistant.memory.tools.SystemAlarms {
            override fun available() = false
            override fun set(hour: Int, minute: Int, days: Set<java.time.DayOfWeek>, label: String?) = false
            override fun setTimer(seconds: Int, label: String?) = false
            override fun openClock() = Unit
        }
        executor = ToolExecutor(memory, reminders, noClock, ChatToolLog(chats), now = { now })
        chatId = chats.createChat()
    }

    @After
    fun tearDown() = database.close()

    private fun at(month: Int, day: Int, hour: Int) =
        ZonedDateTime.of(LocalDateTime.of(2026, month, day, hour, 0), zone).toInstant().toEpochMilli()

    @Test
    fun aToolCallAndItsRecordCommitTogether() = runBlocking {
        val userMessage = chats.addMessage(chatId, Role.USER, "remind me to call the CA tomorrow at 11")
        val outcome = executor.execute(
            ToolCall("add_task", mapOf("title" to "Call the CA", "when" to "tomorrow at 11")),
            ToolContext(chatId, userMessage),
        )
        assertTrue(outcome.ok)
        val task = memory.openTasks().single()
        assertEquals(at(9, 22, 11), task.dueAt)
        assertEquals(userMessage, task.sourceMessageId)
        assertEquals(at(9, 22, 11), scheduled[task.id])

        val row = chats.messagesFor(chatId).single { it.role == Role.TOOL }
        assertEquals(outcome.recordId, row.id)
        val record = ChatToolLog.parse(row.text)!!
        assertEquals("Reminder", record.chip!!.label)
        assertEquals(task.id, record.undo!!.createdTaskId)
    }

    @Test
    fun undoWorksFromTheStoredRecord() = runBlocking {
        val userMessage = chats.addMessage(chatId, Role.USER, "my dentist is Dr. Rao")
        executor.execute(ToolCall("save_fact", mapOf("subject" to "user", "attribute" to "dentist", "value" to "Dr. Rao")), ToolContext(chatId, userMessage))
        now = now.plusMinutes(3)
        val update = executor.execute(
            ToolCall("save_fact", mapOf("subject" to "user", "attribute" to "dentist", "value" to "Dr. Mehta")),
            ToolContext(chatId, userMessage),
        )
        assertEquals(ToolExecutor.UndoResult.UNDONE, executor.undo(update.recordId!!))
        assertEquals("Dr. Rao", database.factDao().find("user", "dentist")!!.value)
        assertTrue(ChatToolLog.parse(chats.message(update.recordId!!)!!.text)!!.undone)
    }

    @Test
    fun forgettingAndUnforgettingRoundTrips() = runBlocking {
        memory.saveFact("user", "dentist", "Dr. Rao", com.local.assistant.memory.db.FactOrigin.CHAT)
        val forgotten = memory.forgetFacts("user", "dentist")
        assertNull(database.factDao().find("user", "dentist"))
        memory.unforget(forgotten)
        assertEquals("Dr. Rao", database.factDao().find("user", "dentist")!!.value)
        assertNull(database.factDao().tombstone("user", "dentist"))
    }

    @Test
    fun keywordSearchMatchesPrefixesOfAnyWord() = runBlocking {
        memory.saveFact("user", "dentist", "Dr. Rao", com.local.assistant.memory.db.FactOrigin.CHAT)
        memory.saveFact("user", "ca", "Mr. Iyer", com.local.assistant.memory.db.FactOrigin.CHAT)
        assertEquals(listOf("dentist"), memory.searchFacts(listOf("dent"), 5).map { it.attribute })
        assertEquals(2, memory.searchFacts(listOf("rao", "iyer"), 5).size)
    }

    @Test
    fun agendaQueriesRespectStatusTimeAndRepetition() = runBlocking {
        val soon = memory.insertTask(TaskEntity(title = "Call the CA", dueAt = at(9, 22, 11), createdAt = 0, updatedAt = 0))
        memory.insertTask(TaskEntity(title = "Later", dueAt = at(10, 30, 9), createdAt = 0, updatedAt = 0))
        memory.insertTask(TaskEntity(title = "Done one", dueAt = at(9, 22, 12), status = com.local.assistant.memory.db.TaskStatus.DONE, createdAt = 0, updatedAt = 0))
        assertEquals(listOf(soon), memory.tasksDueBetween(at(9, 21, 10), at(9, 28, 10)).map { it.id })
        assertEquals(listOf("Call the CA"), memory.tasksMentioning("ca", 5).map { it.title })

        memory.insertEvent(EventEntity(title = "Standup", startsAt = at(1, 5, 9), recurrence = "FREQ=WEEKLY;BYDAY=MO", createdAt = 0, updatedAt = 0))
        val agenda = memory.agenda(now.toInstant().toEpochMilli(), limit = 5)
        val standup = agenda.single { it.title == "Standup" }
        assertEquals("the next Monday after now", at(9, 28, 9), standup.at)
        assertEquals("task $soon", agenda.first().ref)
    }
}
