package com.local.assistant.memory.retrieval

import android.util.Log
import com.local.assistant.memory.db.ChunkEntity
import com.local.assistant.memory.db.FactEntity
import com.local.assistant.memory.db.NoteEntity
import com.local.assistant.memory.embed.EmbedKind
import com.local.assistant.memory.embed.Embedder
import com.local.assistant.memory.embed.Int8Vectors
import com.local.assistant.memory.prompt.Snippet
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** An archived exchange matched by keywords, scored by [Bm25]. */
class KeywordHit(
    val chunkId: Long,
    val messageId: Long,
    val chatId: Long,
    val score: Double,
    /** Which of the query's terms, in query order, this chunk contains. */
    val termHits: BooleanArray,
)

/** The archive as retrieval reads it. */
interface ArchiveSource {
    suspend fun keywordHits(match: String, limit: Int): List<KeywordHit>

    suspend fun chunks(ids: Collection<Long>): List<ChunkEntity>

    suspend fun nearest(query: ByteArray, k: Int): List<VectorHit>
}

/** Structured facts as retrieval reads them. */
interface FactSource {
    suspend fun subjectsForAliases(aliases: List<String>): List<String>

    suspend fun nonCoreAbout(subjects: List<String>, limit: Int): List<FactEntity>

    /** Non-core facts matching an FTS4 query. */
    suspend fun searchNonCore(match: String, limit: Int): List<FactEntity>
}

/** Saved images as retrieval reads them. */
interface NoteSource {
    /** Notes matching an FTS4 query. */
    suspend fun matching(match: String): List<NoteEntity>

    /** Notes embedded by [modelId]. */
    suspend fun embedded(modelId: String): List<NoteEntity>
}

/** A saved image this turn is about: its line in the envelope, and the image to attach. */
data class RecalledImage(val noteId: Long, val title: String, val details: String, val path: String, val at: Long)

/** What was recalled for one turn, and how, for the envelope and the debug log. */
data class Recall(
    val facts: List<FactEntity>,
    val snippets: List<Snippet>,
    val trace: RecallTrace,
    /** A saved image the message is about, to be looked at again. */
    val image: RecalledImage? = null,
) {
    companion object {
        val NONE = Recall(emptyList(), emptyList(), RecallTrace(gated = true))
    }
}

data class RecallTrace(
    val gated: Boolean,
    val keywordHits: Int = 0,
    val vectorHits: Int = 0,
    /** Null when no query vector was made (no model, or not loaded yet). */
    val embedMs: Long? = null,
    val totalMs: Long = 0,
    val candidates: List<Candidate> = emptyList(),
)

/** One fused candidate and whether it passed the inject rule. */
data class Candidate(
    val chunk: ChunkEntity,
    val fusedScore: Double,
    /** Cosine similarity to the query, where both have a vector from the current model. */
    val similarity: Float?,
    val keywordHit: Boolean,
    val rareTermHit: Boolean,
    val injected: Boolean,
)

/**
 * Per-turn recall, run before generation:
 *
 * 0. Gate: trivial messages ("ok", "thanks", fewer than three words) recall nothing.
 * 1. Facts: message words against aliases, subjects and the facts' keyword index; non-core only,
 *    since core facts are already in the prefix.
 * 2. Snippets: the keyword top 20 and the vector top 20 over the archive, leaving out exchanges
 *    already in the conversation's verbatim window, fused by reciprocal rank (k = 60) with
 *    recency breaking ties.
 * 3. Inject rule: a snippet goes in only if its cosine similarity reaches the model's threshold,
 *    or it contains one of the message's rare terms (a name, an acronym, a number). A keyword
 *    hit on a common word is not enough, and neither is being the best of a bad lot.
 */
class Retriever(
    private val facts: FactSource,
    private val archive: ArchiveSource,
    private val embedder: Embedder,
    private val notes: NoteSource? = null,
    private val logScores: Boolean = false,
    /** Turns are started from the main thread; scoring and fusion must not run there. */
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    /**
     * [windowStartMessageId]: messages of [chatId] from this id on are already in the
     * conversation, so recalling them would only repeat what the model can see.
     * [embedQuery] false skips the vector half, e.g. while the embedder is still loading.
     */
    suspend fun recall(
        chatId: Long,
        text: String,
        windowStartMessageId: Long,
        maxFacts: Int,
        maxSnippets: Int,
        embedQuery: Boolean = embedder.isReady,
    ): Recall = withContext(dispatcher) {
        if (QueryText.isTrivial(text)) return@withContext Recall.NONE
        val started = System.nanoTime()
        val terms = QueryText.terms(text)
        val rare = QueryText.rareTerms(text)

        val recalledFacts = recallFacts(terms, maxFacts)
        val (query, embedMs) = queryVector(text, embedQuery)
        val inWindow = { hitChatId: Long?, messageId: Long? ->
            hitChatId == chatId && messageId != null && messageId >= windowStartMessageId
        }
        val search = search(terms, rare, query) { hitChatId, messageId -> inWindow(hitChatId, messageId) }
        val threshold = embedder.similarityThreshold
        val candidates = search.candidates.map { candidate ->
            val passes = (candidate.similarity ?: -1f) >= threshold || candidate.rareTermHit
            candidate.copy(injected = passes)
        }
        val snippets = candidates.filter { it.injected }.take(maxSnippets).map { Snippet(it.chunk.text, it.chunk.createdAt) }
        // An image sent in this conversation's window is already in front of the model.
        val image = recallImage(terms, rare, query, threshold) { inWindow(it.chatId, it.sourceMessageId) }
        val trace = RecallTrace(
            gated = false,
            keywordHits = search.keywordHits,
            vectorHits = search.vectorHits,
            embedMs = embedMs,
            totalMs = (System.nanoTime() - started) / 1_000_000,
            candidates = candidates,
        )
        if (logScores) log(text, trace, threshold, image)
        Recall(recalledFacts, snippets, trace, image)
    }

    /** The message as a query vector, and how long that took; nulls when there is no model to ask. */
    private suspend fun queryVector(text: String, embedQuery: Boolean): Pair<ByteArray?, Long?> {
        if (!embedQuery || embedder.modelId == null) return null to null
        val started = System.nanoTime()
        val vector = embedder.embed(text, EmbedKind.QUERY)?.let(Int8Vectors::quantize)
        return vector to (System.nanoTime() - started) / 1_000_000
    }

    /**
     * The saved image the message is most likely about, under the same rule as snippets: its
     * details are close enough in meaning, or share one of the message's rare words. At most one:
     * each costs a few hundred tokens of the window to look at.
     */
    private suspend fun recallImage(
        terms: List<String>,
        rare: Set<String>,
        query: ByteArray?,
        threshold: Float,
        excluded: (NoteEntity) -> Boolean,
    ): RecalledImage? {
        val source = notes ?: return null
        val byKeyword = QueryText.ftsMatch(terms)?.let { runCatching { source.matching(it) }.getOrNull() }.orEmpty()
        val modelId = embedder.modelId
        val byVector = if (query != null && modelId != null) source.embedded(modelId) else emptyList()
        val scored = (byKeyword + byVector).distinctBy { it.id }.map { note ->
            val words = QueryText.words(note.title + " " + note.details).map { it.lowercase() }.toSet()
            val vector = note.embedding
            val similarity = if (query != null && vector != null && note.modelId == modelId) {
                Int8Vectors.similarity(Int8Vectors.cosineDistance(vector, query))
            } else {
                null
            }
            Triple(note, similarity, rare.any { it in words })
        }
        return scored
            .filter { (note, similarity, rareHit) ->
                ((similarity ?: -1f) >= threshold || rareHit) && !excluded(note) && note.imagePath?.let { java.io.File(it).isFile } == true
            }
            .maxByOrNull { (note, similarity, rareHit) -> (similarity ?: 0f) + (if (rareHit) RARE_BONUS else 0f) + note.createdAt * 1e-15f }
            ?.let { (note, _, _) -> RecalledImage(note.id, note.title, note.details, note.imagePath!!, note.createdAt) }
    }

    /** Saved images for search_memory: any keyword hit or a close enough meaning. */
    suspend fun searchImages(query: String, limit: Int): List<NoteEntity> = withContext(dispatcher) {
        val source = notes ?: return@withContext emptyList()
        val terms = QueryText.terms(query)
        val keyword = QueryText.ftsMatch(terms)?.let { runCatching { source.matching(it) }.getOrNull() }.orEmpty()
        val (vector, _) = queryVector(query, embedQuery = true)
        val modelId = embedder.modelId
        val threshold = embedder.similarityThreshold - SEARCH_THRESHOLD_SLACK
        val close = if (vector != null && modelId != null) {
            source.embedded(modelId).filter { note ->
                note.embedding?.let { Int8Vectors.similarity(Int8Vectors.cosineDistance(it, vector)) >= threshold } == true
            }
        } else {
            emptyList()
        }
        (keyword + close).distinctBy { it.id }.take(limit)
    }

    /**
     * For the search_memory tool: the model asked, so no gate and no window. The bar is lower
     * than for unasked recall — any keyword hit, or a similarity a little under the threshold.
     */
    suspend fun search(query: String, limit: Int): List<Candidate> = withContext(dispatcher) {
        val terms = QueryText.terms(query)
        val (vector, _) = queryVector(query, embedQuery = true)
        val search = search(terms, QueryText.rareTerms(query), vector) { _, _ -> false }
        val threshold = embedder.similarityThreshold - SEARCH_THRESHOLD_SLACK
        search.candidates
            .map { it.copy(injected = it.keywordHit || (it.similarity ?: -1f) >= threshold) }
            .filter { it.injected }
            .take(limit)
    }

    private class Search(
        val candidates: List<Candidate>,
        val keywordHits: Int,
        val vectorHits: Int,
    )

    private suspend fun search(
        terms: List<String>,
        rare: Set<String>,
        query: ByteArray?,
        excluded: (chatId: Long, messageId: Long) -> Boolean,
    ): Search {
        val rareIndexes = terms.indices.filter { terms[it] in rare }
        val keyword = QueryText.ftsMatch(terms)
            ?.let { archive.keywordHits(it, KEYWORD_POOL) }.orEmpty()
            .filterNot { excluded(it.chatId, it.messageId) }
            .sortedWith(compareByDescending<KeywordHit> { it.score }.thenByDescending { it.messageId })
            .take(PER_LIST)

        // Over-fetch: some neighbours will be in the window, or stale after a chat was deleted.
        val nearest = query?.let { archive.nearest(it, PER_LIST + NEAREST_MARGIN) }.orEmpty()

        val chunks = archive.chunks((keyword.map { it.chunkId } + nearest.map { it.chunkId }).distinct())
            .associateBy { it.id }
        val vector = nearest
            .filter { hit -> chunks[hit.chunkId]?.let { !excluded(it.chatId, it.messageId) } == true }
            .take(PER_LIST)

        val byKeyword = keyword.associateBy { it.chunkId }
        val byVector = vector.associateBy { it.chunkId }
        val modelId = embedder.modelId
        val fused = RankFusion.fuse(
            listOf(keyword.map { it.chunkId }, vector.map { it.chunkId }),
            recency = { chunks[it]?.messageId ?: 0L },
        )
        val candidates = fused.mapNotNull { (id, score) ->
            val chunk = chunks[id] ?: return@mapNotNull null
            val similarity = byVector[id]?.similarity
                ?: if (query != null && chunk.embedding != null && chunk.modelId == modelId) {
                    Int8Vectors.similarity(Int8Vectors.cosineDistance(chunk.embedding, query))
                } else {
                    null
                }
            val hits = byKeyword[id]?.termHits
            Candidate(
                chunk = chunk,
                fusedScore = score,
                similarity = similarity,
                keywordHit = hits?.any { it } == true,
                rareTermHit = hits != null && rareIndexes.any { it < hits.size && hits[it] },
                injected = false,
            )
        }
        return Search(candidates, keyword.size, vector.size)
    }

    private suspend fun recallFacts(terms: List<String>, limit: Int): List<FactEntity> {
        if (terms.isEmpty() || limit <= 0) return emptyList()
        // Subjects are stored as keys ("mother", "dr_rao"): try each word and each adjacent pair.
        val keys = terms + terms.zipWithNext { a, b -> "${a}_$b" }
        val subjects = (facts.subjectsForAliases(keys) + keys).distinct()
        val about = facts.nonCoreAbout(subjects, limit)
        val matching = QueryText.ftsMatch(terms)?.let { facts.searchNonCore(it, limit) }.orEmpty()
        return (about + matching).distinctBy { it.id }.take(limit)
    }

    private fun log(text: String, trace: RecallTrace, threshold: Float, image: RecalledImage?) {
        image?.let { Log.d(TAG, "recall \"${text.take(60)}\": attaching saved image \"${it.title}\"") }
        Log.d(
            TAG,
            "recall \"${text.take(60)}\": ${trace.keywordHits} keyword, ${trace.vectorHits} vector, " +
                "embed ${trace.embedMs ?: "-"} ms, total ${trace.totalMs} ms, τ=$threshold",
        )
        trace.candidates.take(LOGGED_CANDIDATES).forEach {
            Log.d(
                TAG,
                "  ${if (it.injected) "+" else "-"} sim=${it.similarity?.let { s -> "%.3f".format(s) } ?: "n/a"} " +
                    "kw=${it.keywordHit} rare=${it.rareTermHit} rrf=${"%.4f".format(it.fusedScore)} " +
                    "\"${it.chunk.text.replace('\n', ' ').take(70)}\"",
            )
        }
    }

    companion object {
        private const val TAG = "Retriever"

        /** Each ranked list going into fusion. */
        const val PER_LIST = 20

        /** Keyword matches scored before the best [PER_LIST] are kept. */
        private const val KEYWORD_POOL = 200
        private const val NEAREST_MARGIN = 20
        private const val SEARCH_THRESHOLD_SLACK = 0.1f

        /** A rare word naming the image outweighs a small difference in similarity. */
        private const val RARE_BONUS = 0.2f
        private const val LOGGED_CANDIDATES = 8
    }
}
