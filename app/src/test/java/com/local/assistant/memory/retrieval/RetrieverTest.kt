package com.local.assistant.memory.retrieval

import com.local.assistant.memory.db.ChunkEntity
import com.local.assistant.memory.db.FactCategory
import com.local.assistant.memory.db.FactEntity
import com.local.assistant.memory.db.FactOrigin
import com.local.assistant.memory.db.NoteEntity
import com.local.assistant.memory.embed.EmbedKind
import com.local.assistant.memory.embed.Embedder
import com.local.assistant.memory.embed.Int8Vectors
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import kotlin.math.sqrt

class RetrieverTest {

    private val dims = Int8Vectors.DIMENSIONS

    /** A unit vector whose cosine with the query ([axis] 0) is [cosine]. */
    private fun along(cosine: Double, axis: Int = 1): FloatArray =
        FloatArray(dims).also {
            it[0] = cosine.toFloat()
            it[axis] = sqrt(1 - cosine * cosine).toFloat()
        }

    private val query = FloatArray(dims).also { it[0] = 1f }

    private class FakeEmbedder(
        private val queries: Map<String, FloatArray>,
        override val isReady: Boolean = true,
    ) : Embedder {
        var embedded = 0
        override val modelId: String? = "fake@256"
        override val similarityThreshold = 0.6f
        override suspend fun embed(text: String, kind: EmbedKind): FloatArray? = queries[text].also { embedded++ }
        override suspend fun prepare() = true
        override suspend fun unload() = Unit
    }

    /** Keyword search by word prefix, vectors by the real Kotlin index. */
    private class FakeArchive(val chunks: List<ChunkEntity>) : ArchiveSource {
        val index = KotlinVectorIndex { chunks.filter { it.embedding != null }.map { it.id to it.embedding!! } }

        override suspend fun keywordHits(match: String, limit: Int): List<KeywordHit> {
            val terms = match.split(" OR ")
            return chunks.mapNotNull { chunk ->
                val words = QueryText.words(chunk.text).map { it.lowercase(Locale.ROOT) }
                val hits = BooleanArray(terms.size) { i ->
                    val term = terms[i]
                    if (term.endsWith("*")) words.any { it.startsWith(term.dropLast(1)) } else term in words
                }
                if (hits.none { it }) null else KeywordHit(chunk.id, chunk.messageId, chunk.chatId, hits.count { it }.toDouble(), hits)
            }.take(limit)
        }

        override suspend fun chunks(ids: Collection<Long>) = chunks.filter { it.id in ids }
        override suspend fun nearest(query: ByteArray, k: Int) = index.nearest(query, k)
    }

    private class FakeFacts(val facts: List<FactEntity>, val aliases: Map<String, String> = emptyMap()) : FactSource {
        override suspend fun subjectsForAliases(aliases: List<String>) = aliases.mapNotNull { this.aliases[it] }
        override suspend fun nonCoreAbout(subjects: List<String>, limit: Int) = facts.filter { it.subject in subjects }.take(limit)
        override suspend fun searchNonCore(match: String, limit: Int): List<FactEntity> {
            val terms = match.split(" OR ").map { it.removeSuffix("*") }
            return facts.filter { f -> terms.any { t -> f.attribute.startsWith(t) || f.value.lowercase(Locale.ROOT).contains(t) } }.take(limit)
        }
    }

    private fun chunk(id: Long, text: String, vector: FloatArray? = null, chatId: Long = 1, messageId: Long = id * 10) =
        ChunkEntity(
            id = id,
            messageId = messageId,
            chatId = chatId,
            sessionId = null,
            text = text,
            embedding = vector?.let { Int8Vectors.quantize(it) },
            modelId = vector?.let { "fake@256" },
            createdAt = id,
        )

    private fun fact(id: Long, subject: String, attribute: String, value: String) =
        FactEntity(id, subject, attribute, value, FactCategory.OTHER, core = false, origin = FactOrigin.CHAT, sourceMessageId = null, statedAt = 0, createdAt = 0, updatedAt = 0, lastConfirmedAt = 0)

    private fun retriever(chunks: List<ChunkEntity>, embedder: Embedder, facts: FactSource = FakeFacts(emptyList()), notes: NoteSource? = null) =
        Retriever(facts, FakeArchive(chunks), embedder, notes = notes)

    /** Keyword search by word prefix over title and details. */
    private class FakeNotes(val notes: List<NoteEntity>) : NoteSource {
        override suspend fun matching(match: String): List<NoteEntity> {
            val terms = match.split(" OR ")
            return notes.filter { note ->
                val words = QueryText.words(note.title + " " + note.details).map { it.lowercase(Locale.ROOT) }
                terms.any { term -> if (term.endsWith("*")) words.any { it.startsWith(term.dropLast(1)) } else term in words }
            }
        }

        override suspend fun embedded(modelId: String) = notes.filter { it.modelId == modelId && it.embedding != null }
    }

    private val imageFile = File.createTempFile("saved", ".jpg").apply { deleteOnExit() }

    private fun note(id: Long, title: String, details: String, vector: FloatArray? = null, chatId: Long = 2, messageId: Long? = id * 10, path: String = imageFile.path) =
        NoteEntity(
            id = id,
            title = title,
            details = details,
            imagePath = path,
            sourceMessageId = messageId,
            chatId = chatId,
            embedding = vector?.let { Int8Vectors.quantize(it) },
            modelId = vector?.let { "fake@256" },
            createdAt = id,
            updatedAt = id,
        )

    private val question = "what was the plan for the weekend trip"

    @Test
    fun trivialMessagesRecallNothingAndEmbedNothing() = runBlocking {
        val embedder = FakeEmbedder(mapOf("ok thanks" to query))
        val recall = retriever(listOf(chunk(1, "User: ok thanks", along(0.99))), embedder).recall(1, "ok thanks", 1_000, 5, 4)
        assertSame(Recall.NONE, recall)
        assertEquals(0, embedder.embedded)
    }

    @Test
    fun belowTheThresholdNothingIsInjected() = runBlocking {
        val chunks = listOf(chunk(1, "User: something unrelated about taxes", along(0.40)), chunk(2, "User: something else entirely", along(0.55, axis = 2)))
        val recall = retriever(chunks, FakeEmbedder(mapOf(question to query))).recall(1, question, 1_000, 5, 4)
        assertTrue(recall.snippets.isEmpty())
        assertEquals("both were candidates, just not good enough", 2, recall.trace.candidates.size)
    }

    @Test
    fun aboveTheThresholdIsInjectedBestFirst() = runBlocking {
        val chunks = listOf(
            chunk(1, "User: we could go to Coorg in October", along(0.72)),
            chunk(2, "User: a trip to Gokarna sounds fun", along(0.91, axis = 2)),
            chunk(3, "User: unrelated", along(0.20, axis = 3)),
        )
        val recall = retriever(chunks, FakeEmbedder(mapOf(question to query))).recall(1, question, 1_000, 5, 4)
        assertEquals(listOf("User: a trip to Gokarna sounds fun", "User: we could go to Coorg in October"), recall.snippets.map { it.text })
    }

    @Test
    fun aCommonWordAloneIsNotEnoughButARareOneIs() = runBlocking {
        val chunks = listOf(
            chunk(1, "User: the office meeting moved to Friday"),
            chunk(2, "User: my CA Mr. Iyer said the office will file by March"),
        )
        // No vectors at all: the embedder is still loading.
        val notReady = FakeEmbedder(emptyMap(), isReady = false)
        val common = retriever(chunks, notReady).recall(1, "what time is the office meeting", 1_000, 5, 4)
        assertTrue(common.snippets.isEmpty())
        assertTrue(common.trace.candidates.all { it.keywordHit && it.similarity == null })

        val rare = retriever(chunks, notReady).recall(1, "did Iyer say anything about the office", 1_000, 5, 4)
        assertEquals(listOf("User: my CA Mr. Iyer said the office will file by March"), rare.snippets.map { it.text })
    }

    @Test
    fun exchangesAlreadyInTheWindowAreLeftOut() = runBlocking {
        val chunks = listOf(
            chunk(1, "User: older, this chat", along(0.90), chatId = 1, messageId = 100),
            chunk(2, "User: in the window", along(0.95, axis = 2), chatId = 1, messageId = 500),
            chunk(3, "User: another chat, newer", along(0.80, axis = 3), chatId = 2, messageId = 900),
        )
        val recall = retriever(chunks, FakeEmbedder(mapOf(question to query))).recall(1, question, windowStartMessageId = 400, 5, 4)
        assertEquals(listOf("User: older, this chat", "User: another chat, newer"), recall.snippets.map { it.text })
        assertTrue(recall.trace.candidates.none { it.chunk.id == 2L })
    }

    @Test
    fun snippetsAreCappedByTheBudget() = runBlocking {
        val chunks = (1L..6L).map { chunk(it, "User: note $it", along(0.7 + it * 0.04, axis = it.toInt())) }
        val recall = retriever(chunks, FakeEmbedder(mapOf(question to query))).recall(1, question, 1_000, 5, maxSnippets = 2)
        assertEquals(listOf("User: note 6", "User: note 5"), recall.snippets.map { it.text })
    }

    @Test
    fun keywordAndVectorAgreementRanksFirst() = runBlocking {
        val chunks = listOf(
            chunk(1, "User: Gokarna trip plan with Anu", along(0.70)),
            chunk(2, "User: nothing in common", along(0.95, axis = 2)),
        )
        val text = "what was the Gokarna trip plan"
        val recall = retriever(chunks, FakeEmbedder(mapOf(text to query))).recall(1, text, 1_000, 5, 4)
        assertEquals(1L, recall.trace.candidates.first().chunk.id)
        assertTrue(recall.trace.candidates.first().rareTermHit)
    }

    @Test
    fun factsComeFromAliasesSubjectsAndKeywords() = runBlocking {
        val facts = FakeFacts(
            listOf(
                fact(1, "mother", "birthday", "12 March"),
                fact(2, "user", "dentist", "Dr. Rao"),
                fact(3, "user", "car", "Swift"),
            ),
            aliases = mapOf("amma" to "mother"),
        )
        val recall = retriever(emptyList(), FakeEmbedder(emptyMap()), facts).recall(1, "remind me to call amma and my dentist", 1_000, 5, 4)
        assertEquals(listOf(1L, 2L), recall.facts.map { it.id })
        val capped = retriever(emptyList(), FakeEmbedder(emptyMap()), facts).recall(1, "remind me to call amma and my dentist", 1_000, 1, 4)
        assertEquals(1, capped.facts.size)
    }

    @Test
    fun theSearchToolTakesAnyKeywordHitAndIgnoresTheWindow() = runBlocking {
        val chunks = listOf(chunk(1, "User: the office meeting moved to Friday", chatId = 1, messageId = 900))
        val found = retriever(chunks, FakeEmbedder(emptyMap(), isReady = false)).search("office meeting", 3)
        assertEquals(listOf(1L), found.map { it.chunk.id })
        assertFalse(found.single().rareTermHit)
    }

    // ---- Saved images ----

    private val cardQuestion = "what was the password on that wifi card"

    @Test
    fun aSavedImageCloseInMeaningIsRecalled() = runBlocking {
        val notes = FakeNotes(listOf(note(1, "Wifi card", "Network and password on the router card", along(0.8)), note(2, "Receipt", "Groceries", along(0.2, axis = 2))))
        val recall = retriever(emptyList(), FakeEmbedder(mapOf(cardQuestion to query)), notes = notes).recall(1, cardQuestion, 1_000, 5, 4)
        assertEquals(1L, recall.image?.noteId)
        assertEquals(imageFile.path, recall.image?.path)
    }

    @Test
    fun aSavedImageFarInMeaningIsNot() = runBlocking {
        val notes = FakeNotes(listOf(note(1, "Receipt", "Groceries from the market", along(0.3))))
        val recall = retriever(emptyList(), FakeEmbedder(mapOf(cardQuestion to query)), notes = notes).recall(1, cardQuestion, 1_000, 5, 4)
        assertNull(recall.image)
    }

    @Test
    fun aRareWordFindsASavedImageWithoutTheEmbedder() = runBlocking {
        val notes = FakeNotes(listOf(note(1, "Visiting card", "Dr. Mehta, dentist, Jayanagar clinic")))
        val notReady = FakeEmbedder(emptyMap(), isReady = false)
        assertEquals(1L, retriever(emptyList(), notReady, notes = notes).recall(1, "what's the number of the Jayanagar clinic", 1_000, 5, 4).image?.noteId)
        assertNull("a common word alone is not enough", retriever(emptyList(), notReady, notes = notes).recall(1, "which clinic was it", 1_000, 5, 4).image)
    }

    @Test
    fun anImageAlreadyInTheWindowOrWithoutItsFileIsNotRecalled() = runBlocking {
        val embedder = FakeEmbedder(mapOf(cardQuestion to query))
        val inWindow = FakeNotes(listOf(note(1, "Wifi card", "password", along(0.9), chatId = 1, messageId = 500)))
        assertNull(retriever(emptyList(), embedder, notes = inWindow).recall(1, cardQuestion, windowStartMessageId = 400, 5, 4).image)
        // The same image from another chat, or from before this window, is not in front of the model.
        assertEquals(1L, retriever(emptyList(), embedder, notes = inWindow).recall(2, cardQuestion, windowStartMessageId = 400, 5, 4).image?.noteId)
        assertEquals(1L, retriever(emptyList(), embedder, notes = inWindow).recall(1, cardQuestion, windowStartMessageId = 600, 5, 4).image?.noteId)

        val gone = FakeNotes(listOf(note(1, "Wifi card", "password", along(0.9), path = "/nowhere/card.jpg")))
        assertNull(retriever(emptyList(), embedder, notes = gone).recall(1, cardQuestion, 1_000, 5, 4).image)
    }

    @Test
    fun theBestOfSeveralSavedImagesWins() = runBlocking {
        val notes = FakeNotes(listOf(note(1, "Router box", "model number", along(0.7)), note(2, "Wifi card", "network and password", along(0.9, axis = 2))))
        assertEquals(2L, retriever(emptyList(), FakeEmbedder(mapOf(cardQuestion to query)), notes = notes).recall(1, cardQuestion, 1_000, 5, 4).image?.noteId)
    }
}
