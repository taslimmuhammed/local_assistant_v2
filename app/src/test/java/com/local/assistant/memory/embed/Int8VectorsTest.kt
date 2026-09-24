package com.local.assistant.memory.embed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

class Int8VectorsTest {

    private val dims = Int8Vectors.DIMENSIONS

    private fun unit(random: Random, size: Int = dims): FloatArray {
        val v = FloatArray(size) { random.nextFloat() * 2 - 1 }
        val norm = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        return FloatArray(size) { v[it] / norm }
    }

    private fun cosine(a: FloatArray, b: FloatArray): Double {
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in 0 until dims) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        return dot / (sqrt(na) * sqrt(nb))
    }

    @Test
    fun theLargestComponentUsesTheFullRange() {
        val q = Int8Vectors.quantize(unit(Random(1)))!!
        assertEquals(dims, q.size)
        assertEquals(127, q.maxOf { abs(it.toInt()) })
    }

    @Test
    fun onlyTheFirstDimensionsAreKept() {
        val long = unit(Random(2), 768)
        assertEquals(dims, Int8Vectors.quantize(long)!!.size)
    }

    @Test
    fun anEmptyVectorHasNoDirection() {
        assertNull(Int8Vectors.quantize(FloatArray(dims)))
    }

    @Test
    fun quantisedCosineStaysCloseToTheFloatOne() {
        val random = Random(3)
        repeat(200) {
            val a = unit(random)
            // Correlated pairs as well as unrelated ones, so the whole range is covered.
            val b = FloatArray(dims) { i -> a[i] * (it % 5) + unit(random)[i] }
            val exact = cosine(a, b)
            val quantised = 1 - Int8Vectors.cosineDistance(Int8Vectors.quantize(a)!!, Int8Vectors.quantize(b)!!)
            assertEquals(exact, quantised.toDouble(), 0.01)
        }
    }

    @Test
    fun aVectorIsNearestItself() {
        val q = Int8Vectors.quantize(unit(Random(4)))!!
        assertTrue(Int8Vectors.cosineDistance(q, q) < 1e-6f)
        val opposite = ByteArray(dims) { (-q[it]).toByte() }
        assertEquals(2f, Int8Vectors.cosineDistance(q, opposite), 1e-6f)
    }
}
