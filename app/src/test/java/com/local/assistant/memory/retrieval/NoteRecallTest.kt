package com.local.assistant.memory.retrieval

import com.local.assistant.memory.db.NoteItem
import com.local.assistant.memory.db.NoteKind
import com.local.assistant.memory.db.UserNoteChunkEntity
import com.local.assistant.memory.db.UserNoteEntity
import com.local.assistant.memory.embed.EmbedKind
import com.local.assistant.memory.embed.Embedder
import com.local.assistant.memory.embed.Int8Vectors
import com.local.assistant.memory.notes.NoteChunks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.sqrt

/** Which of the user's notes come with a message: named, close in meaning, or holding its rare word. */
class NoteRecallTest {

    private val dims = Int8Vectors.DIMENSIONS
    private val query = FloatArray(dims).also { it[0] = 1f }

    private fun along(cosine: Double): ByteArray = Int8Vectors.quantize(
        FloatArray(dims).also {
            it[0] = cosine.toFloat()
            it[1] = sqrt(1 - cosine * cosine).toFloat()
        },
    )!!

    private class FakeEmbedder(private val vectors: Map<String, FloatArray>) : Embedder {
        override val isReady = true
        override val modelId: String? = "fake@256"
        override val similarityThreshold = 0.6f
        override suspend fun embed(text: String, kind: EmbedKind): FloatArray? = vectors[text]
        override suspend fun prepare() = true
        override suspend fun unload() = Unit
    }

    private object NoArchive : ArchiveSource {
        override suspend fun keywordHits(match: String, limit: Int) = emptyList<KeywordHit>()
        override suspend fun chunks(ids: Collection<Long>) = emptyList<com.local.assistant.memory.db.ChunkEntity>()
        override suspend fun nearest(query: ByteArray, k: Int) = emptyList<VectorHit>()
    }

    private object NoFacts : FactSource {
        override suspend fun subjectsForAliases(aliases: List<String>) = emptyList<String>()
        override suspend fun nonCoreAbout(subjects: List<String>, limit: Int) = emptyList<com.local.assistant.memory.db.FactEntity>()
        override suspend fun searchNonCore(match: String, limit: Int) = emptyList<com.local.assistant.memory.db.FactEntity>()
    }

    /** Parts made as the real store makes them; keyword search by word prefix, as FTS4's. */
    private class FakeUserNotes(val all: List<UserNoteEntity>, vectors: Map<Long, ByteArray> = emptyMap()) : UserNoteSource {
        private var nextId = 1L
        val parts = all.flatMap { note ->
            NoteChunks.of(note).map { part ->
                val id = nextId++
                UserNoteChunkEntity(
                    id = id, noteId = part.noteId, ordinal = part.ordinal, text = part.text, fromIndex = part.fromIndex, toIndex = part.toIndex,
                    embedding = vectors[note.id], modelId = vectors[note.id]?.let { "fake@256" },
                )
            }
        }

        override suspend fun chunksMatching(match: String): List<UserNoteChunkEntity> {
            val terms = match.split(" OR ")
            return parts.filter { part ->
                val words = QueryText.words(part.text).map { it.lowercase(Locale.ROOT) }
                terms.any { term -> if (term.endsWith("*")) words.any { it.startsWith(term.dropLast(1)) } else term in words }
            }
        }

        override suspend fun embeddedChunks(modelId: String) = parts.filter { it.modelId == modelId }
        override suspend fun notes(ids: Collection<Long>) = all.filter { it.id in ids }
        override suspend fun all() = all
    }

    private val shopping = UserNoteEntity(
        id = 3, title = "Shopping list", kind = NoteKind.LIST,
        items = listOf(NoteItem("Milk"), NoteItem("Eggs"), NoteItem("Bread")), createdAt = 0, updatedAt = 2,
    )
    private val goa = UserNoteEntity(
        id = 4, title = "Goa packing", kind = NoteKind.LIST,
        items = listOf(NoteItem("Sunscreen"), NoteItem("Charger"), NoteItem("Passport")), createdAt = 0, updatedAt = 1,
    )
    private val wifi = UserNoteEntity(id = 7, title = "Wifi", kind = NoteKind.NOTE, body = "Network Home_5G, password on the router", createdAt = 0, updatedAt = 0)

    private fun recalled(text: String, notes: FakeUserNotes = FakeUserNotes(listOf(shopping, goa, wifi)), vectors: Map<String, FloatArray> = emptyMap()) =
        runBlocking {
            Retriever(NoFacts, NoArchive, FakeEmbedder(vectors), userNotes = notes, dispatcher = Dispatchers.Unconfined)
                .recall(chatId = 1, text = text, windowStartMessageId = 0, maxFacts = 0, maxSnippets = 0, maxNotes = 2)
                .notes.map { it.note.title }
        }

    @Test
    fun `a note named in the message comes with it`() {
        assertEquals(listOf("Shopping list"), recalled("what's on my shopping list?"))
        assertEquals(listOf("Shopping list"), recalled("read me the grocery list"))
        assertEquals(listOf("Goa packing"), recalled("what have I packed for Goa so far, in my list"))
    }

    @Test
    fun `an item asked about beside the word list finds its list`() {
        assertEquals(listOf("Shopping list"), recalled("is bread on my list?"))
    }

    @Test
    fun `a common word without a list or a name brings nothing`() {
        assertTrue(recalled("how do I bake bread at home").isEmpty())
        assertTrue(recalled("what's the capital of France").isEmpty())
    }

    @Test
    fun `a rare word finds the note it's in`() {
        assertEquals(listOf("Wifi"), recalled("what was Home_5G again"))
    }

    @Test
    fun `close in meaning is enough, far is not`() {
        val notes = FakeUserNotes(listOf(shopping, goa, wifi), vectors = mapOf(4L to along(0.8), 3L to along(0.2), 7L to along(0.1)))
        val text = "what should I take to the beach"
        assertEquals(listOf("Goa packing"), recalled(text, notes, vectors = mapOf(text to query)))
    }

    @Test
    fun `the word list alone doesn't bring every list`() {
        assertEquals(listOf("Goa packing"), recalled("is the charger in my list"))
        // Not about the user's lists at all.
        assertTrue(recalled("make a list of tips for a job interview").isEmpty())
        assertTrue(recalled("give me a list of bread recipes").isEmpty())
    }

    @Test
    fun `asked only what's on my list, the latest lists come`() {
        assertEquals(listOf("Shopping list", "Goa packing"), recalled("what's on my list?"))
        assertEquals(listOf("Wifi"), recalled("read me my notes"))
    }

    @Test
    fun `no notes, nothing to recall`() {
        assertTrue(recalled("what's on my shopping list?", FakeUserNotes(emptyList())).isEmpty())
    }
}
