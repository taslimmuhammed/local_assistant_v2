package com.local.assistant.voice

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The same cleanup as the JVM tests, on the phone: Android's ICU regex engine rejects some
 * patterns the JVM accepts, and a pattern it rejects fails the first time a reply is spoken.
 */
@org.junit.runner.RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
class SpeechTextDeviceTest {

    @Test
    fun everyPatternCompilesAndCleans() {
        assertEquals(
            "Here is the plan. Book the dentist. See the docs. Run this: Then type ls.",
            SpeechText.clean(
                "## Here is the plan\n- **Book** the dentist\n> See [the docs](https://example.com)\n" +
                    "Run this:\n```bash\n./gradlew build\n```\nThen type `ls`. 🎉",
            ),
        )
        assertEquals("Mon , Gym ,", SpeechText.clean("|---|---|\n| Mon | Gym |").removePrefix(", "))
    }

    @Test
    fun chunkerFindsSentencesAndSkipsOpenCode() {
        val chunker = SpeechChunker()
        assertEquals(listOf("Sure, it is 5 pm."), chunker.push("Sure, it is 5 pm. Your"))
        // Up to the fence, and nothing of the code inside it.
        assertEquals(listOf("Your code:"), chunker.push("Sure, it is 5 pm. Your code:\n```\nx.\n"))
    }
}
