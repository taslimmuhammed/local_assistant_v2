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
import com.local.assistant.memory.embed.EmbedKind
import com.local.assistant.memory.embed.Embedder
import com.local.assistant.memory.embed.Int8Vectors
import com.local.assistant.memory.prompt.HeuristicTokenEstimator
import com.local.assistant.memory.retrieval.ChunkPolicy
import com.local.assistant.memory.retrieval.QueryText
import com.local.assistant.memory.retrieval.Retriever
import com.local.assistant.memory.retrieval.SqliteVec
import com.local.assistant.memory.retrieval.SqliteVecIndex
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
import kotlin.math.sqrt

/**
 * The archive on the real schema: chunks and their FTS index written by the exchange path and
 * the backfill, vectors kept in step with sqlite-vec, deletes cascading, and retrieval end to
 * end with a stand-in embedder.
 */
@RunWith(AndroidJUnit4::class)
class ArchiveStoreTest {

    private lateinit var database: AppDatabase
    private lateinit var chats: ChatRepository
    private lateinit var index: SqliteVecIndex
    private lateinit var archive: ArchiveRepository
    private var chatId = 0L

    @Before
    fun setUp() = runBlocking {
        assertNotNull(SqliteVec.probe())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .setDriver(SqliteVec.driver(withVectors = true))
            .setQueryCoroutineContext(Dispatchers.IO)
            .addCallback(AppDatabase.VectorTableCallback)
            .build()
        index = SqliteVecIndex(database)
        archive = ArchiveRepository(database, index)
        chats = ChatRepository(
            database.chatDao(),
            SessionTracker(database.sessionDao(), AppForeground()),
            HeuristicTokenEstimator,
            onChatsDeleted = { archive.onChatsDeleted() },
        )
        chatId = chats.createChat()
    }

    @After
    fun tearDown() = database.close()

    private val chunks get() = database.chunkDao()

    private suspend fun exchange(question: String, reply: String?, chat: Long = chatId): Long {
        val id = chats.addMessage(chat, Role.USER, question)
        if (reply != null) chats.addMessage(chat, Role.ASSISTANT, reply)
        return id
    }

    private fun unit(vararg pairs: Pair<Int, Double>): ByteArray {
        val v = FloatArray(Int8Vectors.DIMENSIONS)
        pairs.forEach { (i, x) -> v[i] = x.toFloat() }
        val norm = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        return Int8Vectors.quantize(FloatArray(v.size) { v[it] / norm })!!
    }

    @Test
    fun anExchangeBecomesOneChunkWaitingForAVector() = runBlocking {
        val id = exchange("my CA is Mr. Iyer, office in Jayanagar", "Got it — Mr. Iyer in Jayanagar is your CA.")
        assertTrue(archive.recordExchange(id))
        val chunk = chunks.forMessage(id)!!
        assertEquals("User: my CA is Mr. Iyer, office in Jayanagar\nAssistant: Got it — Mr. Iyer in Jayanagar is your CA.", chunk.text)
        assertNull(chunk.modelId)
        assertEquals(listOf(chunk.id), archive.backlog(10).map { it.id })
    }

    @Test
    fun trivialExchangesAreKeywordSearchableButNotQueued() = runBlocking {
        val id = exchange("thanks Rao!", "You're welcome.")
        assertFalse(archive.recordExchange(id))
        assertEquals(ChunkPolicy.NOT_EMBEDDED, chunks.forMessage(id)!!.modelId)
        assertEquals(0, archive.backlogSize())
        assertEquals(1, archive.keywordHits("rao", 10).size)
    }

    @Test
    fun voiceNotesAreNotArchived() = runBlocking {
        val id = chats.addMessage(chatId, Role.USER, "", attachmentPath = "/x.wav", attachmentKind = AttachmentKind.AUDIO)
        chats.addMessage(chatId, Role.ASSISTANT, "I heard you.")
        assertFalse(archive.recordExchange(id))
        assertNull(chunks.forMessage(id))
    }

    @Test
    fun aReplyStoredLaterUpdatesTheChunkAndItsVector() = runBlocking {
        val id = exchange("plan the Gokarna trip for October", null)
        archive.recordExchange(id)
        val first = chunks.forMessage(id)!!
        assertTrue(archive.saveEmbedding(first, unit(0 to 1.0), "fake@256"))
        assertEquals(1, index.count())

        chats.addMessage(chatId, Role.ASSISTANT, "Sure: Gokarna, second weekend of October.")
        assertTrue(archive.recordExchange(id))
        val updated = chunks.forMessage(id)!!
        assertEquals(first.id, updated.id)
        assertTrue(updated.text.endsWith("Assistant: Sure: Gokarna, second weekend of October."))
        assertNull("new text needs a new vector", updated.embedding)
        assertEquals(0, index.count())
        // A vector computed from the old text is refused.
        assertFalse(archive.saveEmbedding(first, unit(0 to 1.0), "fake@256"))
    }

    @Test
    fun keywordHitsAreRankedWithMatchinfo() = runBlocking {
        archive.recordExchange(exchange("the office meeting moved to Friday", "Noted."))
        archive.recordExchange(exchange("office party at the office on Saturday", "Fun."))
        archive.recordExchange(exchange("Mr. Iyer said the office files by March", "OK."))
        val match = QueryText.ftsMatch(QueryText.terms("did Iyer mention the office"))!!
        val hits = archive.keywordHits(match, 50).sortedByDescending { it.score }
        assertEquals(3, hits.size)
        val best = chunks.byIds(listOf(hits.first().chunkId)).single()
        assertTrue("the rare term outweighs a common one: ${best.text}", "Iyer" in best.text)
        // Terms in query order: iyer, mention, office.
        assertEquals(listOf(true, false, true), hits.first().termHits.toList())
    }

    @Test
    fun deletingAChatTakesItsChunksAndVectorsWithIt() = runBlocking {
        val other = chats.createChat()
        val keep = exchange("keep this one about the Coorg trip", "OK", chat = other)
        val gone = exchange("delete this one about the Gokarna trip", "OK")
        archive.recordExchange(keep)
        archive.recordExchange(gone)
        archive.backlog(10).forEachIndexed { i, chunk -> archive.saveEmbedding(chunk, unit(i to 1.0), "fake@256") }
        assertEquals(2, index.count())

        chats.deleteChat(chatId)
        assertNull(chunks.forMessage(gone))
        assertNotNull(chunks.forMessage(keep))
        assertEquals(1, index.count())
        assertTrue(archive.keywordHits("gokarna*", 10).isEmpty())
    }

    @Test
    fun anotherModelsVectorsGoBackToTheBacklog() = runBlocking {
        val embedded = exchange("vectors from the old model", "OK")
        val trivial = exchange("ok thanks", "Sure")
        archive.recordExchange(embedded)
        archive.recordExchange(trivial)
        archive.adoptModel("old@256")
        archive.saveEmbedding(archive.backlog(1).single(), unit(3 to 1.0), "old@256")
        assertEquals(1, index.count())

        archive.adoptModel("new@256")
        assertEquals(listOf(embedded), archive.backlog(10).map { it.messageId })
        assertEquals(ChunkPolicy.NOT_EMBEDDED, chunks.forMessage(trivial)!!.modelId)
        assertEquals(0, index.count())
    }

    @Test
    fun theIndexIsRebuiltWhenItDriftsFromTheStoredVectors() = runBlocking {
        archive.recordExchange(exchange("first thing to remember today", "OK"))
        archive.recordExchange(exchange("second thing to remember today", "OK"))
        archive.adoptModel("fake@256")
        archive.backlog(10).forEachIndexed { i, chunk -> archive.saveEmbedding(chunk, unit(i to 1.0), "fake@256") }
        index.replaceAll(emptyList())
        archive.adoptModel("fake@256")
        assertEquals(2, index.count())
    }

    @Test
    fun backfillArchivesOlderExchangesOnly() = runBlocking {
        // A tool record sits between a question and its reply; the reply is still found.
        val old = chats.addMessage(chatId, Role.USER, "an old question about the Coorg homestay")
        chats.addMessage(chatId, Role.TOOL, "{\"tool\":\"record\"}")
        chats.addMessage(chatId, Role.ASSISTANT, "An old answer.")
        val older = exchange("another old question about the car service", null)
        val boundary = archive.lastMessageId() + 1
        val inFlight = exchange("a question asked while backfilling", null)

        assertEquals(2, archive.backfill(boundary))
        assertEquals("User: an old question about the Coorg homestay\nAssistant: An old answer.", chunks.forMessage(old)!!.text)
        assertNotNull(chunks.forMessage(older))
        assertNull(chunks.forMessage(inFlight))
        assertEquals("nothing twice", 0, archive.backfill(boundary))
    }

    @Test
    fun retrievalEndToEndOnTheRealArchive() = runBlocking {
        val query = "what did we plan for the weekend trip"
        val embedder = object : Embedder {
            override val modelId = "fake@256"
            override val similarityThreshold = 0.6f
            override val isReady = true
            override suspend fun embed(text: String, kind: EmbedKind) = FloatArray(Int8Vectors.DIMENSIONS).also { it[0] = 1f }
            override suspend fun prepare() = true
            override suspend fun unload() = Unit
        }
        val related = exchange("let's do Gokarna on the second weekend", "Sounds good.")
        val unrelated = exchange("my car insurance renews in May", "Noted.")
        val inWindow = exchange("actually make it Coorg instead", "Coorg it is.")
        listOf(related, unrelated, inWindow).forEach { archive.recordExchange(it) }
        archive.adoptModel("fake@256")
        val vectors = mapOf(related to unit(0 to 0.8, 1 to 0.6), unrelated to unit(0 to 0.2, 2 to 0.98), inWindow to unit(0 to 0.95, 3 to 0.3))
        archive.backlog(10).forEach { archive.saveEmbedding(it, vectors.getValue(it.messageId), "fake@256") }

        val retriever = Retriever(MemoryRepository(database), archive, embedder)
        val recall = retriever.recall(chatId, query, windowStartMessageId = inWindow, maxFacts = 3, maxSnippets = 4)
        assertEquals(listOf("User: let's do Gokarna on the second weekend\nAssistant: Sounds good."), recall.snippets.map { it.text })
        assertTrue(recall.trace.candidates.none { it.chunk.messageId == inWindow })
    }
}
