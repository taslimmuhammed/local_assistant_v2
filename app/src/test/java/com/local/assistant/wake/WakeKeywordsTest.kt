package com.local.assistant.wake

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The phrase's keywords file is in the APK and spelled in the model's own phones: sherpa-onnx
 * gives up on a keywords file with a phone it doesn't know, and the listener stops with it.
 */
class WakeKeywordsTest {

    // Unit tests run from the module's folder.
    private val assets = File("src/main/assets/${WakeWord.ASSET_DIR}")
    private val phones = File(assets, WakeSpotter.TOKENS).readLines().map { it.substringBefore(' ') }.toSet()

    @Test
    fun `the keywords file is in the model's phones`() {
        val file = File(assets, WakeWord.KEYWORDS_FILE)
        assertTrue("${file.path} is missing", file.isFile)
        val lines = file.readLines().filter { it.isNotBlank() }
        assertTrue("no spellings", lines.isNotEmpty())
        for (line in lines) {
            val spelled = line.substringBefore(" @").split(' ').filter { it.isNotEmpty() && !it.startsWith(':') && !it.startsWith('#') }
            val unknown = spelled.filterNot { it in phones }
            assertTrue("$unknown not among the model's phones", unknown.isEmpty())
            assertTrue("\"$line\" has no tag", line.contains(" @"))
        }
    }
}
