package com.local.assistant.memory.retrieval

import com.local.assistant.memory.embed.Int8Vectors
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class KotlinVectorIndexTest {

    private val dims = Int8Vectors.DIMENSIONS

    private fun vector(random: Random) = ByteArray(dims) { (random.nextInt(255) - 127).toByte() }

    /** Every distance, sorted as the index promises: nearest first, then by id. */
    private fun bruteForce(all: List<Pair<Long, ByteArray>>, query: ByteArray, k: Int) =
        all.map { (id, v) -> VectorHit(id, Int8Vectors.cosineDistance(v, query)) }
            .sortedWith(compareBy<VectorHit> { it.distance }.thenBy { it.chunkId })
            .take(k)

    @Test
    fun nearestMatchesAFullSort() = runBlocking {
        val random = Random(7)
        val all = (1L..3_000L).map { it to vector(random) }
        val index = KotlinVectorIndex { all }
        repeat(20) {
            val query = vector(random)
            assertEquals(bruteForce(all, query, 20), index.nearest(query, 20))
        }
    }

    @Test
    fun equalDistancesComeInIdOrder() = runBlocking {
        val random = Random(8)
        val shared = vector(random)
        // Five copies of the same vector: the same distance to anything.
        val all = listOf(50L, 10L, 40L, 20L, 30L).map { it to shared.copyOf() } + (100L to vector(random))
        val hits = KotlinVectorIndex { all }.nearest(shared, 3)
        assertEquals(listOf(10L, 20L, 30L), hits.map { it.chunkId })
    }

    @Test
    fun putRemoveAndReplaceKeepItConsistent() = runBlocking {
        val random = Random(9)
        val all = (1L..100L).map { it to vector(random) }.toMutableList()
        val index = KotlinVectorIndex { all }
        assertEquals(100, index.count())

        val moved = vector(random)
        index.put(5L, moved)
        index.put(500L, vector(random))
        index.remove(listOf(1L, 2L, 99L))
        assertEquals(98, index.count())
        assertEquals(5L, index.nearest(moved, 1).single().chunkId)
        assertTrue(index.nearest(moved, 200).none { it.chunkId in setOf(1L, 2L, 99L) })

        index.replaceAll(listOf(7L to moved))
        assertEquals(listOf(7L), index.nearest(moved, 10).map { it.chunkId })
    }

    @Test
    fun pruneReloadsWhatTheDatabaseNowHas() = runBlocking {
        val random = Random(10)
        var stored = (1L..10L).map { it to vector(random) }
        val index = KotlinVectorIndex { stored }
        assertEquals(10, index.count())
        stored = stored.take(4)
        index.prune()
        assertEquals(4, index.count())
    }

    @Test
    fun anEmptyIndexFindsNothing() = runBlocking {
        assertTrue(KotlinVectorIndex { emptyList() }.nearest(vector(Random(11)), 5).isEmpty())
    }
}
