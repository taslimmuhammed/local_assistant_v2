package com.local.assistant.llm

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Whether a file is a LiteRT-LM bundle at all, checked before the runtime is given it.
 *
 * LiteRT-LM 0.17.1 refuses anything else with "Invalid magic number or failed to read: " followed
 * by the file's first bytes, passed up through JNI as a string. Bytes that aren't valid text make
 * Android abort the whole app there (CheckJNI, on in debug builds), and since the chat model is
 * loaded at every launch, a wrong file imported once crashed the app at every launch after
 * (seen on the emulator, 2026-09-30). So a wrong file is refused here, with a message.
 *
 * The check is the start of the layout [ModelPrecision] reads: "LITERTLM", then at 24 where the
 * header ends, which has to lie inside the file. Every model the app uses has it: Gemma 4 E4B,
 * EmbeddingGemma and Granite.
 */
object ModelFormat {

    /** How much of the start of a file [startsLikeLiteRtLm] looks at. */
    const val PREFIX_BYTES = 32

    /** For a file [size] bytes long ([size] null when unknown), from its first [PREFIX_BYTES]. */
    fun startsLikeLiteRtLm(start: ByteArray, size: Long?): Boolean {
        if (start.size < PREFIX_BYTES) return false
        if (String(start, 0, MAGIC.length, Charsets.US_ASCII) != MAGIC) return false
        val headerEnd = ByteBuffer.wrap(start, HEADER_END_AT, 8).order(ByteOrder.LITTLE_ENDIAN).long
        return headerEnd > PREFIX_BYTES && (size == null || headerEnd <= size)
    }

    fun isLiteRtLm(file: File): Boolean = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < PREFIX_BYTES) return@use false
            val start = ByteArray(PREFIX_BYTES)
            raf.readFully(start)
            startsLikeLiteRtLm(start, raf.length())
        }
    }.getOrDefault(false)

    const val NOT_A_MODEL = "That isn't a LiteRT-LM model file (.litertlm)."

    private const val MAGIC = "LITERTLM"
    private const val HEADER_END_AT = 24
}
