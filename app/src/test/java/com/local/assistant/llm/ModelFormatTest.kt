package com.local.assistant.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.random.Random

class ModelFormatTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** The start of the real Gemma 4 E4B file, padded out to where its first section begins. */
    private fun realHeader() = javaClass.getResource("/litertlm/gemma-4-E4B-it-header-fp16.bin")!!.readBytes()
        .let { it + ByteArray(FIRST_SECTION - it.size) }

    @Test
    fun `the real model's header passes`() {
        assertTrue(ModelFormat.isLiteRtLm(folder.newFile().apply { writeBytes(realHeader()) }))
        assertTrue(ModelFormat.startsLikeLiteRtLm(realHeader().copyOf(ModelFormat.PREFIX_BYTES), size = null))
    }

    @Test
    fun `random bytes are refused, the file that crashed the app`() {
        assertFalse(ModelFormat.isLiteRtLm(folder.newFile().apply { writeBytes(Random(7).nextBytes(4096)) }))
    }

    @Test
    fun `a text file with the right name is refused`() {
        assertFalse(ModelFormat.isLiteRtLm(folder.newFile("gemma-4-E4B-it.litertlm").apply { writeText("not a model file. ".repeat(250)) }))
    }

    @Test
    fun `too short to hold the magic and the header's end is refused`() {
        assertFalse(ModelFormat.isLiteRtLm(folder.newFile().apply { writeBytes("LITERTLM".toByteArray()) }))
        assertFalse(ModelFormat.isLiteRtLm(folder.newFile()))
    }

    @Test
    fun `a header that ends past the end of the file is refused, as a cut-off copy would be`() {
        val start = realHeader().copyOf(ModelFormat.PREFIX_BYTES)
        assertFalse(ModelFormat.startsLikeLiteRtLm(start, size = 100))
        assertFalse(ModelFormat.isLiteRtLm(folder.newFile().apply { writeBytes(start) }))
    }

    /** The real EmbeddingGemma header, all 608 bytes of it (no weights). */
    private fun embedderHeader() = javaClass.getResource("/litertlm/embeddinggemma-300m-header.bin")!!.readBytes()

    @Test
    fun `the chat model is accepted as the chat model, and refused as the search model`() {
        val gemma = folder.newFile().apply { writeBytes(realHeader()) }
        assertNull(ModelFormat.problem(gemma, ModelFormat.Kind.CHAT))
        assertEquals(ModelFormat.Kind.EMBEDDER.wrongKind, ModelFormat.problem(gemma, ModelFormat.Kind.EMBEDDER))
    }

    @Test
    fun `the embedding model is accepted for search, and refused as the chat model, which aborted the runtime`() {
        val embedder = folder.newFile().apply { writeBytes(embedderHeader()) }
        assertNull(ModelFormat.problem(embedder, ModelFormat.Kind.EMBEDDER))
        assertEquals(ModelFormat.Kind.CHAT.wrongKind, ModelFormat.problem(embedder, ModelFormat.Kind.CHAT))
    }

    @Test
    fun `a file that isn't LiteRT-LM at all gets the plain message whichever kind was wanted`() {
        val junk = folder.newFile().apply { writeBytes(Random(7).nextBytes(4096)) }
        assertEquals(ModelFormat.NOT_A_MODEL, ModelFormat.problem(junk, ModelFormat.Kind.CHAT))
        assertEquals(ModelFormat.NOT_A_MODEL, ModelFormat.problem(junk, ModelFormat.Kind.EMBEDDER))
    }

    @Test
    fun `a missing file is refused, not thrown`() {
        assertFalse(ModelFormat.isLiteRtLm(folder.root.resolve("absent.litertlm")))
    }

    private companion object {
        /** Where the fixture's first section starts; the header must end before it (ModelPrecisionTest). */
        const val FIRST_SECTION = 16 * 1024
    }
}
