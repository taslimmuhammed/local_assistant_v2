package com.local.assistant.memory.retrieval

import android.util.Log
import com.local.assistant.memory.db.ChunkEntity
import com.local.assistant.memory.db.FactEntity
import com.local.assistant.memory.db.NoteEntity
import com.local.assistant.memory.db.UserNoteChunkEntity
import com.local.assistant.memory.db.UserNoteEntity
import com.local.assistant.memory.db.NoteKind
import com.local.assistant.memory.notes.NoteEdits
import com.local.assistant.memory.notes.NoteMatch
import com.local.assistant.memory.embed.EmbedKind
import com.local.assistant.memory.embed.Embedder
import com.local.assistant.memory.embed.Int8Vectors
import com.local.assistant.memory.prompt.Snippet
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

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

/** The user's notes and lists as retrieval reads them: their parts, searched, and the notes themselves. */
interface UserNoteSource {
    /** Parts matching an FTS4 query. */
    suspend fun chunksMatching(match: String): List<UserNoteChunkEntity>

    /** Parts embedded by [modelId]. */
    suspend fun embeddedChunks(modelId: String): List<UserNoteChunkEntity>

    suspend fun notes(ids: Collection<Long>): List<UserNoteEntity>

    /** Every note, pinned first, then the latest changed. */
    suspend fun all(): List<UserNoteEntity>
}

/**
 * A note or list this turn is about, as it is now, and which of its parts matched ([parts]: the
 * ordinals; all of a short note is one part). The envelope shows the parts that fit.
 */
data class RecalledNote(val note: UserNoteEntity, val parts: List<UserNoteChunkEntity>, val score: Float)

/** A saved image this turn is about: its line in the envelope, and the image to attach. */
data class RecalledImage(val noteId: Long, val title: String, val details: String, val path: String, val at: Long)

/** What was recalled for one turn, and how, for the envelope and the debug log. */
data class Recall(
    val facts: List<FactEntity>,
    val snippets: List<Snippet>,
    val trace: RecallTrace,
    /** A saved image the message is about, to be looked at again. */
    val image: RecalledImage? = null,
    /** Notes and lists the message is about, best first. */
    val notes: List<RecalledNote> = emptyList(),
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
    /** The user's notes and lists; none when null. */
    private val userNotes: UserNoteSource? = null,
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
        maxNotes: Int = 0,
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
        val recalledNotes = if (maxNotes > 0) recallNotes(text, terms, rare, query, threshold).take(maxNotes) else emptyList()
        val trace = RecallTrace(
            gated = false,
            keywordHits = search.keywordHits,
            vectorHits = search.vectorHits,
            embedMs = embedMs,
            totalMs = (System.nanoTime() - started) / 1_000_000,
            candidates = candidates,
        )
        if (logScores) log(text, trace, threshold, image, recalledNotes)
        Recall(recalledFacts, snippets, trace, image, recalledNotes)
    }

    /**
     * The notes the message is about, best first, each with the parts that matched. A note comes
     * when the message names it ("my shopping list", "the Goa packing one", "the grocery list"
     * for the "Shopping list"), when a part is close enough in meaning or shares a rare word, or
     * when the message speaks of one of the user's lists or notes ("is bread on my list?") and a
     * part has one of its words. Asked only "what's on my list?", the latest ones of that kind.
     *
     * "List" and "note" alone are no one's name: every list has the word in its title, so a hit
     * on it says nothing (it brought every list with any message that said "list"). "Make a list
     * of tips" isn't about the user's lists: only "my", "the", "our" lists and notes are.
     */
    private suspend fun recallNotes(text: String, terms: List<String>, rare: Set<String>, query: ByteArray?, threshold: Float): List<RecalledNote> {
        val source = userNotes ?: return emptyList()
        val all = source.all()
        if (all.isEmpty()) return emptyList()
        val lower = text.lowercase(Locale.ROOT)
        val ofLists = THEIR_LIST.containsMatchIn(lower)
        val ofNotes = THEIR_NOTE.containsMatchIn(lower)
        val aboutNotes = ofLists || ofNotes
        val content = terms.filter { NoteEdits.singular(it) !in NOTE_WORDS }
        val byKeyword = QueryText.ftsMatch(content)?.let { runCatching { source.chunksMatching(it) }.getOrNull() }.orEmpty()
        val modelId = embedder.modelId
        val byVector = if (query != null && modelId != null) source.embeddedChunks(modelId) else emptyList()
        val keywordIds = byKeyword.map { it.id }.toSet()
        // The message's own words (not "what", "is"), as a note's name is matched.
        val named = content.flatMap { NoteMatch.keys(it) }.toSet()
        val namedIds = all.filter { note ->
            val keys = NoteMatch.keys(note.title)
            // All of its name ("Goa trip packing"), or some of it beside "list" or "note" ("my Goa list").
            keys.isNotEmpty() && (named.containsAll(keys) || (aboutNotes && keys.any { it in named }))
        }.map { it.id }.toSet()

        val parts = (byKeyword + byVector).distinctBy { it.id }
        val scoredParts = parts.mapNotNull { part ->
            val similarity = part.embedding?.takeIf { query != null && part.modelId == modelId }
                ?.let { Int8Vectors.similarity(Int8Vectors.cosineDistance(it, query!!)) }
            val words = QueryText.words(part.text).map { it.lowercase(Locale.ROOT) }.toSet()
            val rareHit = rare.any { it in words }
            val keywordHit = part.id in keywordIds
            val passes = (similarity ?: -1f) >= threshold || rareHit || part.noteId in namedIds || (aboutNotes && keywordHit)
            if (!passes) return@mapNotNull null
            part to (similarity ?: 0f) + (if (rareHit) RARE_BONUS else 0f) + (if (keywordHit) KEYWORD_BONUS else 0f)
        }
        val byNote = scoredParts.groupBy { it.first.noteId }
        val ids = byNote.keys + namedIds
        val found = all.filter { it.id in ids }.map { note ->
            val hits = byNote[note.id].orEmpty()
            val score = (hits.maxOfOrNull { it.second } ?: 0f) + (if (note.id in namedIds) NAMED_BONUS else 0f)
            RecalledNote(note, hits.map { it.first }.sortedBy { it.ordinal }, score)
        }
        if (found.isEmpty() && aboutNotes && rare.isEmpty()) {
            // "What's on my list?", "read me my notes": the latest of that kind, whole.
            val kind = when {
                ofLists && !ofNotes -> NoteKind.LIST
                ofNotes && !ofLists -> NoteKind.NOTE
                else -> null
            }
            return all.filter { kind == null || it.kind == kind }.sortedByDescending { it.updatedAt }.map { RecalledNote(it, emptyList(), 0f) }
        }
        return found.sortedWith(compareByDescending<RecalledNote> { it.score }.thenByDescending { it.note.updatedAt })
    }

    /** Notes for search_memory: the model asked, so any keyword hit or a meaning a little under the threshold. */
    suspend fun searchNotes(query: String, limit: Int): List<UserNoteEntity> = withContext(dispatcher) {
        val source = userNotes ?: return@withContext emptyList()
        val terms = QueryText.terms(query)
        val keyword = QueryText.ftsMatch(terms)?.let { runCatching { source.chunksMatching(it) }.getOrNull() }.orEmpty()
        val (vector, _) = queryVector(query, embedQuery = true)
        val modelId = embedder.modelId
        val threshold = embedder.similarityThreshold - SEARCH_THRESHOLD_SLACK
        val close = if (vector != null && modelId != null) {
            source.embeddedChunks(modelId).filter { part ->
                part.embedding?.let { Int8Vectors.similarity(Int8Vectors.cosineDistance(it, vector)) >= threshold } == true
            }
        } else {
            emptyList()
        }
        val ids = (keyword + close).map { it.noteId }.distinct().take(limit)
        val found = source.notes(ids).associateBy { it.id }
        ids.mapNotNull { found[it] }
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

    private fun log(text: String, trace: RecallTrace, threshold: Float, image: RecalledImage?, notes: List<RecalledNote>) {
        image?.let { Log.d(TAG, "recall \"${text.take(60)}\": attaching saved image \"${it.title}\"") }
        notes.forEach { Log.d(TAG, "recall \"${text.take(60)}\": note ${it.note.id} parts ${it.parts.map { p -> p.ordinal }} score ${"%.3f".format(it.score)}") }
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

        /** A note the message names ("my shopping list") ranks above one only close in meaning. */
        private const val NAMED_BONUS = 0.5f
        private const val KEYWORD_BONUS = 0.1f

        /** Words that say a message is about a note or list (singular, as [NoteEdits.singular] makes them). */
        private val NOTE_WORDS = setOf("list", "note", "checklist", "noted", "jotted", "wrote", "written")

        /** "My list", "the shopping list", "our packing checklist": one of the user's own. */
        private val THEIR_LIST = Regex("\\b(?:my|our|the|that|this)\\s+(?:[\\w'’-]+\\s+){0,2}?(?:list|lists|checklist)\\b")

        /** "My notes", "the wifi note", "what I noted down". */
        private val THEIR_NOTE = Regex("\\b(?:(?:my|our|the|that|this)\\s+(?:[\\w'’-]+\\s+){0,2}?notes?|i\\s+(?:noted|jotted|wrote)(?:\\s+\\w+)?\\s+down)\\b")
        private const val LOGGED_CANDIDATES = 8
    }
}
