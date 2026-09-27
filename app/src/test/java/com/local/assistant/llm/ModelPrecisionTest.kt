package com.local.assistant.llm

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** On the real Gemma 4 E4B header, padded out to where its first section begins. */
class ModelPrecisionTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun model(fixture: String): File {
        val header = javaClass.getResource("/litertlm/$fixture")!!.readBytes()
        return folder.newFile().apply { writeBytes(header + ByteArray(FIRST_SECTION - header.size)) }
    }

    @Test
    fun `the text decoder's fp16 becomes fp32, and nothing else changes`() {
        val file = model("gemma-4-E4B-it-header-fp16.bin")
        val before = file.readBytes()
        assertEquals("fp16", ModelPrecision.textDecoderPrecision(file))

        assertEquals(ModelPrecision.Outcome.PATCHED, ModelPrecision.ensureFp32TextDecoder(file))

        assertEquals("fp32", ModelPrecision.textDecoderPrecision(file))
        val after = file.readBytes()
        val changed = before.indices.filter { before[it] != after[it] }
        assertEquals("only \"16\" → \"32\"", 2, changed.size)
        // The vision encoder's own "fp16", earlier in the header, is left alone.
        assertEquals(1, String(after, Charsets.ISO_8859_1).windowed(4).count { it == "fp16" })
    }

    @Test
    fun `a second time there is nothing to do`() {
        val file = model("gemma-4-E4B-it-header-fp16.bin")
        ModelPrecision.ensureFp32TextDecoder(file)
        val patched = file.readBytes()
        assertEquals(ModelPrecision.Outcome.ALREADY_FP32, ModelPrecision.ensureFp32TextDecoder(file))
        assertArrayEquals(patched, file.readBytes())
    }

    @Test
    fun `other settings and other files are left as they are`() {
        val mixed = model("gemma-4-E4B-it-header-mixed.bin")
        val before = mixed.readBytes()
        assertEquals("fp32_fp16", ModelPrecision.textDecoderPrecision(mixed))
        assertEquals(ModelPrecision.Outcome.UNSUPPORTED, ModelPrecision.ensureFp32TextDecoder(mixed))
        assertArrayEquals(before, mixed.readBytes())

        val notAModel = folder.newFile().apply { writeText("PK\u0003\u0004 not a litertlm file at all, just some bytes") }
        assertEquals(ModelPrecision.Outcome.UNSUPPORTED, ModelPrecision.ensureFp32TextDecoder(notAModel))

        val start = javaClass.getResource("/litertlm/gemma-4-E4B-it-header-fp16.bin")!!.readBytes().copyOf(900)
        val truncated = folder.newFile().apply { writeBytes(start) }
        assertEquals(ModelPrecision.Outcome.UNSUPPORTED, ModelPrecision.ensureFp32TextDecoder(truncated))
    }

    private companion object {
        const val FIRST_SECTION = 16 * 1024
    }
}
