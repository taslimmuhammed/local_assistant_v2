package com.local.assistant.voice

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

class VoicePackTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val kokoro = VoicePack.KOKORO
    private val name = kokoro.name

    @Test
    fun `keeps the model, the voices and what English needs`() {
        assertEquals("model.onnx", kokoro.keep("$name/model.onnx"))
        assertEquals("voices.bin", kokoro.keep("$name/voices.bin"))
        assertEquals("tokens.txt", kokoro.keep("$name/tokens.txt"))
        assertEquals("lexicon-us-en.txt", kokoro.keep("$name/lexicon-us-en.txt"))
        assertEquals("LICENSE", kokoro.keep("./$name/LICENSE"))
        assertEquals("espeak-ng-data/en_dict", kokoro.keep("$name/espeak-ng-data/en_dict"))
        assertEquals("espeak-ng-data/phontab", kokoro.keep("$name/espeak-ng-data/phontab"))
        assertEquals("espeak-ng-data/lang/gmw/en-US", kokoro.keep("$name/espeak-ng-data/lang/gmw/en-US"))
    }

    @Test
    fun `leaves out Chinese, British English and other languages' dictionaries`() {
        for (entry in listOf(
            "lexicon-zh.txt", "lexicon-gb-en.txt", "date-zh.fst", "number-zh.fst", "phone-zh.fst",
            "dict/jieba.dict.utf8", "README.md", "espeak-ng-data/de_dict", "espeak-ng-data/cmn_dict",
        )) {
            assertNull(entry, kokoro.keep("$name/$entry"))
        }
    }

    @Test
    fun `refuses anything outside the voice's own folder`() {
        assertNull(kokoro.keep("model.onnx"))
        assertNull(kokoro.keep("other/model.onnx"))
        assertNull(kokoro.keep("$name/../model.onnx"))
        assertNull(kokoro.keep("$name/espeak-ng-data/../../outside"))
        assertNull(kokoro.keep("$name//etc/passwd"))
        assertNull(kokoro.keep("$name/"))
    }

    @Test
    fun `unpacks only what it keeps`() {
        val archive = tarBz2(
            "$name/model.onnx" to "model",
            "$name/lexicon-zh.txt" to "zh",
            "$name/espeak-ng-data/en_dict" to "en",
            "$name/espeak-ng-data/fr_dict" to "fr",
            "$name/../escape.txt" to "no",
        )
        val out = File(temp.root, "voice")
        var lastRead = 0L
        kokoro.unpack(ByteArrayInputStream(archive), out) { lastRead = it }

        assertEquals("model", File(out, "model.onnx").readText())
        assertEquals("en", File(out, "espeak-ng-data/en_dict").readText())
        assertFalse(File(out, "lexicon-zh.txt").exists())
        assertFalse(File(out, "espeak-ng-data/fr_dict").exists())
        assertFalse(File(temp.root, "escape.txt").exists())
        assertEquals(archive.size.toLong(), lastRead)
    }

    @Test(expected = IOException::class)
    fun `won't unpack over an existing folder`() {
        val out = temp.newFolder("voice")
        kokoro.unpack(ByteArrayInputStream(tarBz2("$name/model.onnx" to "m")), out)
    }

    @Test
    fun `a voice is usable only with every part and the right model`() {
        val dir = temp.newFolder("installed")
        assertTrue(kokoro.problem(dir) != null)
        File(dir, VoicePack.KOKORO_MODEL).writeText("too small")
        assertEquals("The voice model is not the expected one", kokoro.problem(dir))
    }

    @Test
    fun `every voice belongs to a pack that can be downloaded`() {
        for (voice in NaturalVoices.ALL) assertTrue(voice.id, voice.pack in VoicePack.ALL)
        assertEquals(NaturalVoices.ALL.size, NaturalVoices.ALL.map { it.id }.toSet().size)
        assertEquals(NaturalVoices.HEART, NaturalVoices.byId("kokoro-heart"))
        assertEquals(NaturalVoices.HEART, NaturalVoices.firstOf(VoicePack.KOKORO))
    }

    private fun tarBz2(vararg files: Pair<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        TarArchiveOutputStream(BZip2CompressorOutputStream(bytes)).use { tar ->
            for ((path, text) in files) {
                val data = text.toByteArray()
                tar.putArchiveEntry(TarArchiveEntry(path, true).apply { size = data.size.toLong() })
                tar.write(data)
                tar.closeArchiveEntry()
            }
        }
        return bytes.toByteArray()
    }
}
