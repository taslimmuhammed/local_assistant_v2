package com.local.assistant.memory.embed

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The stored form of an embedding: [DIMENSIONS] signed bytes.
 *
 * The embedder's output is cut to its first [DIMENSIONS] dimensions (Matryoshka) and
 * L2-normalised by the runtime; here it is scaled so its largest component is ±127 and rounded.
 * The scale is per vector, which cosine similarity ignores, so every vector uses the full int8
 * range rather than the few steps a fixed ×127 would leave a unit vector's small components.
 * 256 bytes a chunk instead of 3 KB of floats.
 */
object Int8Vectors {

    const val DIMENSIONS = 256

    /** Null for a vector with nothing in it, which has no direction to compare. */
    fun quantize(vector: FloatArray): ByteArray? {
        require(vector.size >= DIMENSIONS) { "Expected at least $DIMENSIONS dimensions, got ${vector.size}" }
        var max = 0f
        for (i in 0 until DIMENSIONS) max = maxOf(max, abs(vector[i]))
        if (max == 0f || max.isNaN()) return null
        val scale = 127f / max
        return ByteArray(DIMENSIONS) { i -> (vector[i] * scale).roundToInt().coerceIn(-127, 127).toByte() }
    }

    /** Σ v², exact: 256 × 127² is far inside an Int. */
    fun squaredNorm(v: ByteArray): Int {
        var sum = 0
        for (x in v) sum += x * x
        return sum
    }

    fun dot(a: ByteArray, aOffset: Int, b: ByteArray): Int {
        var sum = 0
        for (i in b.indices) sum += a[aOffset + i] * b[i]
        return sum
    }

    /**
     * Cosine distance (1 − cosine), computed exactly as sqlite-vec's `distance_cosine_int8` does:
     * integer sums (exact in its float accumulators at this size), square roots and the division
     * in double, the result rounded to float. The Kotlin index and the SQL one therefore agree to
     * the bit, which is what lets the parity test demand identical rankings.
     */
    fun cosineDistance(dot: Int, squaredNormA: Int, squaredNormB: Int): Float =
        (1.0 - dot.toDouble() / (sqrt(squaredNormA.toDouble()) * sqrt(squaredNormB.toDouble()))).toFloat()

    fun cosineDistance(a: ByteArray, b: ByteArray): Float =
        cosineDistance(dot(a, 0, b), squaredNorm(a), squaredNorm(b))

    fun similarity(distance: Float): Float = 1f - distance
}
