package com.local.assistant.gallery.faces

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A face print as stored: its 128 numbers scaled so the largest is ±127 and rounded to bytes,
 * like the photos' [com.local.assistant.memory.embed.Int8Vectors]. The scale is per print, which
 * cosine similarity ignores; 128 bytes a face instead of 512.
 */
object FacePrints {

    const val SIZE = FaceFinder.PRINT_SIZE

    fun quantize(print: FloatArray): ByteArray {
        require(print.size == SIZE) { "Expected $SIZE numbers, got ${print.size}" }
        var max = 0f
        for (v in print) max = maxOf(max, abs(v))
        if (max == 0f || max.isNaN()) return ByteArray(SIZE)
        val scale = 127f / max
        return ByteArray(SIZE) { (print[it] * scale).roundToInt().coerceIn(-127, 127).toByte() }
    }

    /** Back to a unit-length print, for comparing. */
    fun unit(bytes: ByteArray): FloatArray {
        val v = FloatArray(SIZE) { bytes[it].toFloat() }
        val norm = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        if (norm > 0f) for (i in v.indices) v[i] /= norm
        return v
    }

    /** Cosine similarity of two unit-length prints. */
    fun similarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        for (i in 0 until SIZE) dot += a[i] * b[i]
        return dot
    }
}
