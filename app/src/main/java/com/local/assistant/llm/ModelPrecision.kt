package com.local.assistant.llm

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Has the model's text decoder run in fp32 on the GPU, so it can tell token 2,049 from 2,050.
 *
 * Gemma 4 E4B's `.litertlm` asks for fp16 activations for its text decoder (the
 * `tf_lite_prefill_decode` section's `prefer_activation_type`), and in fp16 whole numbers stop
 * being exact at 2,048. Past that, neighbouring positions merge — two apart up to 4,096, four apart
 * after — and since Gemma gives every digit a token of its own, a run of digits is exactly what
 * falls apart: "4361044" came back "43610104", "09/08/2001" "09/08/20001". Measured on the phone
 * (MemoryEvalTest.digitPrecision), digits copied after 3,334 and 4,786 tokens were 0/16 right in
 * fp16 and 16/16 in fp32, at about the same decode speed; reading the prompt is 1.6–1.9× slower.
 * Server-side models keep positions as integers and do the rotary maths in fp32 for this reason.
 *
 * LiteRT-LM 0.17.1 reads the preference from the file's header and offers no API for it, so the
 * header is edited in place: "fp16" and "fp32" are the same length, so it is a 2-byte write, and
 * nothing else in the 3.6 GB file moves. The runtime's caches are keyed on the file's mtime, so
 * its GPU programs are rebuilt once for fp32.
 *
 * File layout (LiteRT-LM `schema/core`): "LITERTLM", the version at 8, the header's end offset
 * (uint64) at 24, and from 32 a FlatBuffer `LiteRTLMMetaData` describing 16 KB-aligned sections.
 */
object ModelPrecision {

    enum class Outcome {
        /** It said fp16; it says fp32 now. */
        PATCHED,
        /** It already said fp32. */
        ALREADY_FP32,
        /** Not a file this knows how to change (another format, or no such setting): left as it was. */
        UNSUPPORTED,
    }

    /** Makes [file]'s text decoder ask for fp32. Must not be called while an engine has it open. */
    fun ensureFp32TextDecoder(file: File): Outcome = RandomAccessFile(file, "rw").use { raf ->
        val found = find(raf) ?: return Outcome.UNSUPPORTED
        when (found.value) {
            FP32 -> Outcome.ALREADY_FP32
            FP16 -> {
                raf.seek(found.offset)
                raf.write(FP32.toByteArray(Charsets.US_ASCII))
                raf.fd.sync()
                Outcome.PATCHED
            }
            else -> Outcome.UNSUPPORTED
        }
    }

    /** What [file]'s text decoder asks for ("fp16", "fp32"…), or null if it doesn't say. */
    fun textDecoderPrecision(file: File): String? = RandomAccessFile(file, "r").use { find(it)?.value }

    private class Found(val value: String, val offset: Long)

    /** The text decoder's `prefer_activation_type` string and where its bytes are in the file. */
    private fun find(raf: RandomAccessFile): Found? {
        if (raf.length() < HEADER_BEGIN) return null
        val start = ByteArray(HEADER_BEGIN)
        raf.seek(0)
        raf.readFully(start)
        if (String(start, 0, MAGIC.length, Charsets.US_ASCII) != MAGIC) return null
        val end = ByteBuffer.wrap(start, HEADER_END_AT, 8).order(ByteOrder.LITTLE_ENDIAN).long
        if (end <= HEADER_BEGIN || end > minOf(raf.length(), MAX_HEADER.toLong())) return null
        val header = ByteArray((end - HEADER_BEGIN).toInt())
        raf.readFully(header)
        return runCatching { Header(header).textDecoderPreference() }.getOrNull()
            ?.let { (value, at) -> Found(value, HEADER_BEGIN + at.toLong()) }
    }

    /** Just enough FlatBuffer reading for `LiteRTLMMetaData` (litertlm_header_schema.fbs). */
    private class Header(bytes: ByteArray) {
        private val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        /** (value, offset of its first byte in the header) for the prefill/decode section. */
        fun textDecoderPreference(): Pair<String, Int>? {
            val root = indirect(0)
            val sections = table(root, ROOT_SECTION_METADATA) ?: return null
            for (section in vector(sections, SECTIONS_OBJECTS)) {
                val items = vector(section, SECTION_ITEMS).map { item -> key(item) to item }
                if (items.none { (key, item) -> key == MODEL_TYPE && stringValue(item)?.first == TEXT_DECODER }) continue
                return items.firstOrNull { it.first == PREFER_ACTIVATION_TYPE }?.second?.let(::stringValue)
            }
            return null
        }

        private fun u32(at: Int) = buf.getInt(at)
        private fun u16(at: Int) = buf.getShort(at).toInt() and 0xFFFF
        private fun indirect(at: Int) = at + u32(at)

        /** Where a table's field is, or null when absent. */
        private fun field(table: Int, index: Int): Int? {
            val vtable = table - u32(table)
            val slot = 4 + 2 * index
            if (slot >= u16(vtable)) return null
            val offset = u16(vtable + slot)
            return if (offset == 0) null else table + offset
        }

        private fun table(table: Int, index: Int): Int? = field(table, index)?.let(::indirect)

        private fun vector(table: Int, index: Int): List<Int> {
            val at = field(table, index)?.let(::indirect) ?: return emptyList()
            return (0 until u32(at)).map { indirect(at + 4 + 4 * it) }
        }

        private fun string(at: Int): Pair<String, Int> {
            val length = u32(at)
            return String(buf.array(), at + 4, length, Charsets.UTF_8) to at + 4
        }

        private fun key(item: Int): String? = field(item, KV_KEY)?.let { string(indirect(it)).first }

        /** A KeyValuePair's value when it is a StringValue. */
        private fun stringValue(item: Int): Pair<String, Int>? {
            val type = field(item, KV_VALUE_TYPE)?.let { buf.get(it).toInt() } ?: return null
            if (type != VDATA_STRING_VALUE) return null
            val value = table(item, KV_VALUE) ?: return null
            return field(value, 0)?.let { string(indirect(it)) }
        }
    }

    private const val MAGIC = "LITERTLM"
    private const val HEADER_BEGIN = 32
    private const val HEADER_END_AT = 24

    /** Sections start at 16 KB boundaries; a header is never near this. */
    private const val MAX_HEADER = 1 shl 20

    private const val FP16 = "fp16"
    private const val FP32 = "fp32"
    private const val MODEL_TYPE = "model_type"
    private const val TEXT_DECODER = "tf_lite_prefill_decode"
    private const val PREFER_ACTIVATION_TYPE = "prefer_activation_type"

    // Field indices, in schema order.
    private const val ROOT_SECTION_METADATA = 1
    private const val SECTIONS_OBJECTS = 0
    private const val SECTION_ITEMS = 0
    private const val KV_KEY = 0
    private const val KV_VALUE_TYPE = 1
    private const val KV_VALUE = 2

    /** `VData` union: NONE, UInt8, Int8, UInt16, Int16, UInt32, Int32, Float32, Bool, StringValue… */
    private const val VDATA_STRING_VALUE = 9
}
