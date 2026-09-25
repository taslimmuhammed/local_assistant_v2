package com.local.assistant.memory.db

import android.util.Log
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import com.local.assistant.data.db.AppDatabase
import com.local.assistant.data.db.Role
import com.local.assistant.memory.retrieval.ArchiveSource
import com.local.assistant.memory.retrieval.Bm25
import com.local.assistant.memory.retrieval.ChunkPolicy
import com.local.assistant.memory.retrieval.KeywordHit
import com.local.assistant.memory.retrieval.VectorHit
import com.local.assistant.memory.retrieval.VectorIndex

/**
 * The archive: every exchange as a chunk, keyword-indexed by Room's FTS triggers and embedded
 * later in the background (see `EmbeddingQueue`).
 *
 * `chunks.embedding` is the source of truth for vectors. The [index] is derived from it and kept
 * in step here — in the same transaction as the chunk where the index lives in the database —
 * and is cleared and refilled whenever the two might disagree.
 */
class ArchiveRepository(
    private val database: AppDatabase,
    private val index: VectorIndex,
) : ArchiveSource {

    private val chunks = database.chunkDao()
    private val messages = database.chatDao()
    private val state = database.appStateDao()

    private suspend fun <T> transaction(block: suspend () -> T): T =
        database.useWriterConnection { connection -> connection.immediateTransaction { block() } }

    /**
     * Archives the exchange [userMessageId] opened, with whatever reply is stored for it (possibly
     * partial, possibly none). A chunk already made for it is brought up to date. Returns true
     * when the chunk is one to embed.
     */
    suspend fun recordExchange(userMessageId: Long): Boolean = transaction {
        val user = messages.message(userMessageId)?.takeIf { it.role == Role.USER && !it.offRecord } ?: return@transaction false
        val reply = chunks.nextTurn(user.chatId, user.id)?.takeIf { it.role == Role.ASSISTANT }
        val text = ChunkPolicy.text(user.text, user.attachmentKind, reply?.text) ?: return@transaction false
        val marker = if (ChunkPolicy.shouldEmbed(user.text)) null else ChunkPolicy.NOT_EMBEDDED
        val existing = chunks.forMessage(user.id)
        // Forgotten on purpose: an edit to the exchange must not bring it back.
        if (existing?.modelId == ChunkPolicy.FORGOTTEN) return@transaction false
        when {
            existing == null -> chunks.insert(
                ChunkEntity(
                    messageId = user.id,
                    chatId = user.chatId,
                    sessionId = user.sessionId,
                    text = text,
                    modelId = marker,
                    createdAt = user.createdAt,
                ),
            )
            existing.text != text -> {
                chunks.replaceText(existing.id, text, marker)
                index.remove(listOf(existing.id))
            }
        }
        marker == null
    }

    /**
     * Archives user messages from before this feature existed, or whose chunk a crash prevented.
     * Only messages below [beforeId] are touched, so a turn in flight — whose reply is not stored
     * yet — is left to [recordExchange]. Returns how many were archived.
     */
    suspend fun backfill(beforeId: Long): Int {
        var after = 0L
        var archived = 0
        while (true) {
            val batch = chunks.unchunkedUserMessages(after, beforeId, BACKFILL_BATCH)
            if (batch.isEmpty()) return archived
            for (message in batch) {
                recordExchange(message.id)
                archived++
            }
            after = batch.last().id
        }
    }

    suspend fun lastMessageId(): Long = chunks.lastMessageId() ?: 0L

    suspend fun backlog(limit: Int): List<ChunkEntity> = chunks.backlog(limit)

    suspend fun backlogSize(): Int = chunks.backlogSize()

    /**
     * Stores [vector] for [chunk] as made by [modelId], unless the chunk's text changed since it
     * was read (its new text is then still in the backlog). A null vector marks the chunk as one
     * this model cannot embed, so the backlog does not retry it forever.
     */
    suspend fun saveEmbedding(chunk: ChunkEntity, vector: ByteArray?, modelId: String): Boolean = transaction {
        val marker = if (vector == null) ChunkPolicy.FAILED_PREFIX + modelId else modelId
        val updated = chunks.setEmbedding(chunk.id, chunk.text, vector, marker) == 1
        if (updated && vector != null) index.put(chunk.id, vector)
        updated
    }

    /**
     * Brings the archive in line with the embedder now installed: chunks embedded by any other
     * model go back to the backlog (vectors from different models are never compared), and the
     * index is refilled if it does not hold exactly the stored vectors.
     */
    suspend fun adoptModel(modelId: String) {
        if (state.get(KEY_MODEL) != modelId) {
            val reset = transaction {
                chunks.resetOtherModels(modelId, listOf(ChunkPolicy.NOT_EMBEDDED, ChunkPolicy.FORGOTTEN)).also {
                    state.put(AppStateEntity(KEY_MODEL, modelId))
                }
            }
            if (reset > 0) Log.i(TAG, "Embedding model is now $modelId; $reset chunks to re-embed")
            index.replaceAll(storedVectors(modelId))
            return
        }
        index.prune()
        val stored = chunks.embeddedCount(modelId)
        if (index.count() != stored) {
            Log.i(TAG, "Vector index out of step with $stored stored vectors; rebuilding")
            index.replaceAll(storedVectors(modelId))
        }
    }

    /** After chats were deleted: their chunks went by cascade, their index entries go now. */
    suspend fun onChatsDeleted() = index.prune()

    /**
     * Archived exchanges that mention [phrase] (as a phrase, in any case) and, when [alsoOneOf]
     * is given, at least one of those words too — "12 March" alone is too common to delete every
     * mention of, "12 March" with "mother" is the fact being forgotten.
     */
    suspend fun mentioning(phrase: String, alsoOneOf: List<String> = emptyList()): List<ChunkEntity> {
        val words = com.local.assistant.memory.retrieval.QueryText.words(phrase).map { it.lowercase() }
        if (words.isEmpty()) return emptyList()
        val found = try {
            chunks.matching("\"" + words.joinToString(" ") + "\"")
        } catch (e: Exception) {
            Log.w(TAG, "Could not search for '$phrase'", e)
            emptyList()
        }
        if (alsoOneOf.isEmpty()) return found
        // Word by word, and "mother's" counts as "mother".
        fun wordsOf(text: String) = com.local.assistant.memory.retrieval.QueryText.words(text)
            .map { it.lowercase().substringBefore('\'').substringBefore('’') }
        return found.filter { chunk ->
            val text = wordsOf(chunk.text).toSet()
            alsoOneOf.any { other -> wordsOf(other).all { it in text } }
        }
    }

    /** Empties [forget] (see [ChunkPolicy.FORGOTTEN]) and returns them as they were, for undo. */
    suspend fun forget(forget: List<ChunkEntity>): List<ChunkEntity> = transaction {
        if (forget.isEmpty()) return@transaction emptyList()
        chunks.blank(forget.map { it.id }, ChunkPolicy.FORGOTTEN)
        index.remove(forget.map { it.id })
        forget
    }

    /** Puts back chunks [forget] emptied. */
    suspend fun restore(restored: List<ChunkEntity>) = transaction {
        for (chunk in restored) {
            chunks.update(chunk)
            val vector = chunk.embedding
            if (vector != null && chunk.modelId != null && !chunk.modelId.startsWith(ChunkPolicy.FAILED_PREFIX)) index.put(chunk.id, vector)
        }
    }

    /** "Forget everything": every chunk emptied, the vector index cleared. */
    suspend fun forgetAll(): Int {
        val count = transaction { chunks.blankAll(ChunkPolicy.FORGOTTEN) }
        index.replaceAll(emptyList())
        return count
    }

    private suspend fun storedVectors(modelId: String): List<Pair<Long, ByteArray>> =
        chunks.vectors(modelId).map { it.id to it.embedding }

    // ---- ArchiveSource ----

    override suspend fun keywordHits(match: String, limit: Int): List<KeywordHit> =
        try {
            chunks.keywordMatches(match, limit).map { row ->
                val scored = Bm25.score(Bm25.parse(row.matchinfo))
                KeywordHit(row.id, row.messageId, row.chatId, scored.score, scored.termHits)
            }
        } catch (e: Exception) {
            // A query the FTS parser rejects must not cost the user their turn.
            Log.w(TAG, "Keyword search failed for '$match'", e)
            emptyList()
        }

    override suspend fun chunks(ids: Collection<Long>): List<ChunkEntity> =
        if (ids.isEmpty()) emptyList() else chunks.byIds(ids.toList())

    override suspend fun nearest(query: ByteArray, k: Int): List<VectorHit> = index.nearest(query, k)

    private companion object {
        const val TAG = "Archive"
        const val KEY_MODEL = "archive.embedding_model"
        const val BACKFILL_BATCH = 200
    }
}
