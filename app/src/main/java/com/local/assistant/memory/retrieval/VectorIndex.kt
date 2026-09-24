package com.local.assistant.memory.retrieval

import com.local.assistant.memory.embed.Int8Vectors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.PriorityQueue

data class VectorHit(val chunkId: Long, val distance: Float) {
    val similarity: Float get() = Int8Vectors.similarity(distance)
}

/**
 * Nearest-neighbour search over the archive's int8 vectors, by cosine distance.
 *
 * Always derived: `chunks.embedding` is the source of truth, and an index can be cleared and
 * refilled from it at any time. At personal scale (tens of thousands of chunks) an exact scan is
 * fast enough, so neither implementation approximates.
 */
interface VectorIndex {

    /** Up to [k] vectors nearest [query], nearest first; equal distances by chunk id. */
    suspend fun nearest(query: ByteArray, k: Int): List<VectorHit>

    /** Adds or replaces the vector for [chunkId]. Joins the caller's transaction if it has one. */
    suspend fun put(chunkId: Long, vector: ByteArray)

    suspend fun remove(chunkIds: Collection<Long>)

    suspend fun count(): Int

    /** Empties the index and fills it with [vectors], e.g. after the embedding model changed. */
    suspend fun replaceAll(vectors: List<Pair<Long, ByteArray>>)

    /**
     * Drops entries whose chunk is gone or no longer has a vector. Deleting a chat cascades to
     * its chunks in SQL, but deliberately not into the index (see [SqliteVec.CREATE_TABLE]).
     */
    suspend fun prune()
}

/**
 * The fallback when sqlite-vec is not available: every vector in one contiguous array, scored
 * with the same arithmetic as sqlite-vec (see [Int8Vectors.cosineDistance]), so both return the
 * same neighbours. About 14 MB at 55,000 chunks; loaded on first search.
 */
class KotlinVectorIndex(
    /** Every stored vector, for the first search. */
    private val loadAll: suspend () -> List<Pair<Long, ByteArray>>,
) : VectorIndex {

    private val mutex = Mutex()
    private var loaded = false
    private var ids = LongArray(INITIAL_CAPACITY)
    private var vectors = ByteArray(INITIAL_CAPACITY * DIMS)
    private var norms = IntArray(INITIAL_CAPACITY)
    private var size = 0
    private val slots = HashMap<Long, Int>()

    override suspend fun nearest(query: ByteArray, k: Int): List<VectorHit> = mutex.withLock {
        withContext(Dispatchers.Default) { scan(query, k) }
    }

    private suspend fun scan(query: ByteArray, k: Int): List<VectorHit> {
        ensureLoaded()
        if (size == 0 || k <= 0) return emptyList()
        val queryNorm = Int8Vectors.squaredNorm(query)
        if (queryNorm == 0) return emptyList()
        // Worst of the best k on top.
        val worstFirst = compareByDescending<VectorHit> { it.distance }.thenByDescending { it.chunkId }
        val best = PriorityQueue(k + 1, worstFirst)
        for (slot in 0 until size) {
            val distance = Int8Vectors.cosineDistance(Int8Vectors.dot(vectors, slot * DIMS, query), norms[slot], queryNorm)
            if (best.size < k) {
                best += VectorHit(ids[slot], distance)
            } else if (worstFirst.compare(VectorHit(ids[slot], distance), best.peek()) > 0) {
                best.poll()
                best += VectorHit(ids[slot], distance)
            }
        }
        return best.sortedWith(compareBy<VectorHit> { it.distance }.thenBy { it.chunkId })
    }

    override suspend fun put(chunkId: Long, vector: ByteArray) = mutex.withLock {
        if (!loaded) return@withLock // The first search loads it from the database anyway.
        insert(chunkId, vector)
    }

    override suspend fun remove(chunkIds: Collection<Long>) = mutex.withLock {
        if (!loaded) return@withLock
        chunkIds.forEach(::delete)
    }

    override suspend fun count(): Int = mutex.withLock {
        ensureLoaded()
        size
    }

    override suspend fun replaceAll(vectors: List<Pair<Long, ByteArray>>) = mutex.withLock {
        clear()
        vectors.forEach { (id, vector) -> insert(id, vector) }
        loaded = true
    }

    /** Forgets everything; the next search reloads what the database has now. */
    override suspend fun prune() = mutex.withLock {
        clear()
        loaded = false
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        clear()
        loadAll().forEach { (id, vector) -> insert(id, vector) }
        loaded = true
    }

    private fun insert(chunkId: Long, vector: ByteArray) {
        require(vector.size == DIMS) { "Expected $DIMS bytes, got ${vector.size}" }
        val slot = slots[chunkId] ?: run {
            if (size == ids.size) grow()
            size++
            (size - 1).also { slots[chunkId] = it }
        }
        ids[slot] = chunkId
        vector.copyInto(vectors, slot * DIMS)
        norms[slot] = Int8Vectors.squaredNorm(vector)
    }

    /** Moves the last entry into the freed slot. */
    private fun delete(chunkId: Long) {
        val slot = slots.remove(chunkId) ?: return
        val last = size - 1
        if (slot != last) {
            ids[slot] = ids[last]
            vectors.copyInto(vectors, slot * DIMS, last * DIMS, (last + 1) * DIMS)
            norms[slot] = norms[last]
            slots[ids[slot]] = slot
        }
        size--
    }

    private fun grow() {
        val capacity = ids.size * 2
        ids = ids.copyOf(capacity)
        vectors = vectors.copyOf(capacity * DIMS)
        norms = norms.copyOf(capacity)
    }

    private fun clear() {
        size = 0
        slots.clear()
    }

    private companion object {
        const val DIMS = Int8Vectors.DIMENSIONS
        const val INITIAL_CAPACITY = 1_024
    }
}
