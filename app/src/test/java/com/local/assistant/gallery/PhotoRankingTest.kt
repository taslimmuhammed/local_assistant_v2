package com.local.assistant.gallery

import com.local.assistant.memory.embed.Int8Vectors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class PhotoRankingTest {

    private val dims = Int8Vectors.DIMENSIONS

    /** A unit vector [similarity] away from [base] (also a unit vector), in a fresh direction. */
    private fun near(base: FloatArray, similarity: Float, random: Random): FloatArray {
        val other = FloatArray(dims) { random.nextFloat() - 0.5f }
        // Gram-Schmidt: the part of `other` at right angles to `base`, made unit length.
        val along = other.indices.sumOf { (other[it] * base[it]).toDouble() }.toFloat()
        for (i in other.indices) other[i] -= along * base[i]
        val norm = kotlin.math.sqrt(other.sumOf { (it * it).toDouble() }).toFloat()
        val angle = kotlin.math.acos(similarity)
        return FloatArray(dims) { cos(angle) * base[it] + sin(angle) * other[it] / norm }
    }

    private fun unit(random: Random): FloatArray {
        val v = FloatArray(dims) { random.nextFloat() - 0.5f }
        val norm = kotlin.math.sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        return FloatArray(dims) { v[it] / norm }
    }

    private class Library(val ids: LongArray, val vectors: ByteArray, val norms: IntArray)

    private fun library(photos: List<FloatArray>): Library {
        val quantized = photos.map { Int8Vectors.quantize(it)!! }
        val vectors = ByteArray(quantized.size * dims)
        quantized.forEachIndexed { i, v -> v.copyInto(vectors, i * dims) }
        return Library(LongArray(photos.size) { it.toLong() }, vectors, IntArray(photos.size) { Int8Vectors.squaredNorm(quantized[it]) })
    }

    @Test
    fun `photos within the margin of the best one come back, best first, and the rest do not`() {
        val random = Random(1)
        val query = unit(random)
        // Like "sunset" on the phone: the best at 0.78, real ones down to about 0.75, then a crowd.
        val photos = List(200) { near(query, 0.55f + random.nextFloat() * 0.15f, random) } +
            listOf(near(query, 0.78f, random), near(query, 0.765f, random), near(query, 0.752f, random), near(query, 0.735f, random))
        val lib = library(photos)

        val hits = PhotoRanking.rank(Int8Vectors.quantize(query)!!, lib.ids, lib.vectors, lib.norms)

        assertEquals(listOf(200L, 201L, 202L), hits.map { it.mediaId })
        assertTrue(hits.zipWithNext().all { (a, b) -> a.similarity >= b.similarity })
    }

    @Test
    fun `when even the best photo is weak, nothing matches`() {
        val random = Random(2)
        val query = unit(random)
        // Nothing on the phone comes close: a best of 0.70.
        val photos = List(200) { near(query, 0.55f + random.nextFloat() * 0.13f, random) } + near(query, 0.70f, random)
        val lib = library(photos)

        assertEquals(emptyList<PhotoHit>(), PhotoRanking.rank(Int8Vectors.quantize(query)!!, lib.ids, lib.vectors, lib.norms))
    }

    @Test
    fun `a common subject keeps all its photos, however many there are`() {
        val random = Random(3)
        val query = unit(random)
        // Like "selfie": a third of the phone scores close to the best.
        val photos = List(100) { near(query, 0.55f + random.nextFloat() * 0.1f, random) } +
            List(50) { near(query, 0.765f + random.nextFloat() * 0.02f, random) }
        val lib = library(photos)

        val hits = PhotoRanking.rank(Int8Vectors.quantize(query)!!, lib.ids, lib.vectors, lib.norms)

        assertEquals((100L until 150L).toSet(), hits.map { it.mediaId }.toSet())
    }

    @Test
    fun `an empty library has no matches`() {
        val query = Int8Vectors.quantize(unit(Random(4)))!!
        assertEquals(emptyList<PhotoHit>(), PhotoRanking.rank(query, LongArray(0), ByteArray(0), IntArray(0)))
    }
}
