package com.local.assistant.memory

import androidx.room.Room
import androidx.room.useWriterConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.local.assistant.data.db.AppDatabase
import com.local.assistant.data.db.AttachmentKind
import com.local.assistant.data.db.Role
import com.local.assistant.data.repo.ChatRepository
import com.local.assistant.llm.BackendCapabilities
import com.local.assistant.llm.ChatSession
import com.local.assistant.llm.ChatSpec
import com.local.assistant.llm.LlmBackend
import com.local.assistant.llm.Sampling
import com.local.assistant.memory.db.MemoryRepository
import com.local.assistant.memory.db.SessionTracker
import com.local.assistant.memory.db.SummaryStatus
import com.local.assistant.memory.extract.ExtractionRouter
import com.local.assistant.memory.extract.Extractor
import com.local.assistant.memory.prompt.HeuristicTokenEstimator
import com.local.assistant.memory.summary.SessionSummaries
import com.local.assistant.memory.summary.Summarizer
import com.local.assistant.memory.tools.ReminderScheduler
import com.local.assistant.memory.work.AppForeground
import com.local.assistant.memory.work.ModelAccess
import com.local.assistant.memory.work.ModelScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The nightly extraction and the session summaries on the real schema, with a scripted model:
 * what reaches the model, what is skipped, and that a stopped run resumes where it left off.
 */
@RunWith(AndroidJUnit4::class)
class BackgroundWorkTest {

    /** Plays the model: records every prompt, answers from a queue. */
    private class ScriptedBackend(var loaded: Boolean = true) : LlmBackend {
        val prompts = mutableListOf<String>()
        val replies = ArrayDeque<String>()
        override val capabilities: BackendCapabilities?
            get() = if (loaded) BackendCapabilities(tools = true, constrainedJson = true, thinking = false, speculativeDecoding = false, maxContextTokens = 8_192, visionTokensPerImage = 256) else null
        override suspend fun ensureReady() = loaded
        override suspend fun openChat(spec: ChatSpec): ChatSession = error("not used")
        override suspend fun complete(system: String, input: String, jsonSchema: String?, maxTokens: Int, sampling: Sampling): String {
            prompts += input
            return replies.removeFirstOrNull() ?: "[]"
        }
        override suspend fun release() = Unit
        override fun isContextOverflow(error: Throwable) = false
    }

    private object DirectAccess : ModelAccess {
        override suspend fun <T> withModelFree(block: suspend () -> T): T = block()
    }

    private val noReminders = object : ReminderScheduler {
        override fun canScheduleExact() = true
        override fun schedule(taskId: Long, dueAt: Long) = Unit
        override fun cancel(taskId: Long) = Unit
        override fun scheduleEvent(eventId: Long, alertAt: Long, startsAt: Long) = Unit
        override fun cancelEvent(eventId: Long) = Unit
    }

    private lateinit var database: AppDatabase
    private lateinit var chats: ChatRepository
    private lateinit var backend: ScriptedBackend
    private var paused = false
    private var chatId = 0L

    @Before
    fun setUp() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        chats = ChatRepository(database.chatDao(), SessionTracker(database.sessionDao(), AppForeground()), HeuristicTokenEstimator, memoryPaused = { paused })
        backend = ScriptedBackend()
        chatId = chats.createChat()
    }

    @After
    fun tearDown() = database.close()

    private fun extractor(clock: () -> Long = System::currentTimeMillis) = Extractor(
        maintenance = database.maintenanceDao(),
        state = database.appStateDao(),
        backend = backend,
        router = ExtractionRouter(MemoryRepository(database), noReminders),
        conversations = DirectAccess,
        scheduler = ModelScheduler(),
        estimator = HeuristicTokenEstimator,
        clock = clock,
    )

    @Test
    fun extractionReadsOnlyWhatTheChatDidNotAlreadyActOn() = runBlocking {
        val fact = chats.addMessage(chatId, Role.USER, "by the way my sister Priya lives in Pune")
        chats.addMessage(chatId, Role.ASSISTANT, "Nice!")
        val acted = chats.addMessage(chatId, Role.USER, "remind me to call the CA tomorrow at 11")
        chats.addMessage(chatId, Role.TOOL, "{\"tool\":\"add_task\"}")
        chats.addMessage(chatId, Role.ASSISTANT, "Done.")
        chats.addMessage(chatId, Role.USER, "ok thanks")
        chats.addMessage(chatId, Role.USER, "", attachmentPath = "/v.wav", attachmentKind = AttachmentKind.AUDIO)
        paused = true
        chats.addMessage(chatId, Role.USER, "my salary is 1.2 lakh a month")
        paused = false

        backend.replies += """[{"type":"fact","subject":"sister","attribute":"city","value":"Pune","source_message_id":$fact},
            {"type":"fact","subject":"user","attribute":"ca","value":"Mr. Iyer","source_message_id":$acted}]"""
        assertTrue(extractor().run(deadline = Long.MAX_VALUE))

        assertEquals(1, backend.prompts.size)
        val prompt = backend.prompts.single()
        assertTrue("#$fact" in prompt)
        assertFalse("the turn that made a tool call", "#$acted" in prompt)
        assertFalse("CA" in prompt)
        assertFalse("ok thanks" in prompt)
        assertFalse("salary" in prompt)
        assertEquals("Pune", database.factDao().find("sister", "city")!!.value)
        assertNull("an id outside the batch is ignored", database.factDao().find("user", "ca"))

        // Caught up: a second run reads nothing new.
        assertTrue(extractor().run(deadline = Long.MAX_VALUE))
        assertEquals(1, backend.prompts.size)
    }

    @Test
    fun aStoppedRunPicksUpWhereItLeftOff() = runBlocking {
        repeat(80) { chats.addMessage(chatId, Role.USER, "message number $it about my plans for the week ahead") }
        var ticks = 0L
        // The deadline passes after the first batch.
        val stopsEarly = extractor(clock = { if (ticks++ < 1) 0L else Long.MAX_VALUE - 1 })
        assertFalse(stopsEarly.run(deadline = Long.MAX_VALUE - 2))
        val firstBatch = backend.prompts.size
        assertEquals(1, firstBatch)
        assertTrue(extractor().run(deadline = Long.MAX_VALUE))
        val read = backend.prompts.flatMap { Regex("#(\\d+)").findAll(it).map { m -> m.groupValues[1] }.toList() }
        assertEquals("every message read exactly once", read.distinct().size, read.size)
        assertEquals(80, read.size)
    }

    @Test
    fun aReplyThatIsNotJsonIsRetriedOnceThenSkipped() = runBlocking {
        chats.addMessage(chatId, Role.USER, "my blood group is O positive, remember that")
        backend.replies += "Sure! The user's blood group is O+."
        backend.replies += "[{\"type\":\"fact\",\"subject\":\"user\",\"attribute\":\"blood_group\",\"value\":\"O positive\",\"source_message_id\":${chats.messagesFor(chatId).first().id}}]"
        assertTrue(extractor().run(deadline = Long.MAX_VALUE))
        assertEquals(2, backend.prompts.size)
        assertEquals("O positive", database.factDao().find("user", "blood_group")!!.value)
    }

    private fun summaries() = SessionSummaries(
        sessions = database.sessionDao(),
        summarizer = Summarizer(backend, HeuristicTokenEstimator),
        backend = backend,
        conversations = DirectAccess,
        scheduler = ModelScheduler(),
    )

    @Test
    fun endedSessionsAreSummarisedAndSmallTalkIsNot() = runBlocking {
        chats.addMessage(chatId, Role.USER, "let's plan the Gokarna trip for the second weekend of October")
        chats.addMessage(chatId, Role.ASSISTANT, "Sounds good, I can help with that.")
        val talk = database.sessionDao().openSession(chatId)!!.id
        val other = chats.createChat()
        chats.addMessage(other, Role.USER, "hi")
        val smallTalk = database.sessionDao().openSession(other)!!.id
        // Both quiet for longer than a session gap.
        database.useWriterConnection { c -> c.usePrepared("UPDATE messages SET createdAt = createdAt - 3600000") { it.step() } }
        database.useWriterConnection { c -> c.usePrepared("UPDATE sessions SET startedAt = startedAt - 3600000") { it.step() } }

        val work = summaries()
        assertEquals(2, work.endQuietSessions())
        backend.replies += "The user is planning a trip to Gokarna for the second weekend of October."
        assertEquals(0, work.summarisePending(deadline = Long.MAX_VALUE, loadModel = true))
        val sessions = database.sessionDao()
        assertEquals("The user is planning a trip to Gokarna for the second weekend of October.", sessions.summarised().single().summary)
        assertEquals(1, backend.prompts.size)
        assertTrue("Gokarna" in backend.prompts.single())
        assertTrue(sessions.pendingSummaries(10).none { it.id == talk || it.id == smallTalk })
    }

    @Test
    fun theSessionEndJobDoesNotLoadTheModel() = runBlocking {
        chats.addMessage(chatId, Role.USER, "my passport renewal appointment is next Tuesday")
        database.useWriterConnection { c -> c.usePrepared("UPDATE messages SET createdAt = createdAt - 3600000") { it.step() } }
        database.useWriterConnection { c -> c.usePrepared("UPDATE sessions SET startedAt = startedAt - 3600000") { it.step() } }
        backend.loaded = false
        val work = summaries()
        work.endQuietSessions()
        assertEquals("left for the nightly job", 1, work.summarisePending(deadline = Long.MAX_VALUE, loadModel = false))
        assertEquals(SummaryStatus.PENDING, database.sessionDao().pendingSummaries(1).single().summaryStatus)
        assertTrue(backend.prompts.isEmpty())
    }
}
