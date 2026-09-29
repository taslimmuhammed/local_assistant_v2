package com.local.assistant.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTextTest {

    @Test
    fun `formatting marks are not read out`() {
        assertEquals(
            "Here is the plan. Book the dentist. Call the CA.",
            SpeechText.clean("## Here is the plan\n- **Book** the dentist\n* Call the *CA*"),
        )
    }

    @Test
    fun `links keep their words and lose their address`() {
        assertEquals("See the docs for more.", SpeechText.clean("See [the docs](https://example.com/a) for more."))
        assertEquals("Try.", SpeechText.clean("Try https://example.com/long/path?x=1."))
    }

    @Test
    fun `code blocks are skipped and inline code is read plainly`() {
        assertEquals(
            "Run this: Then type ls to check.",
            SpeechText.clean("Run this:\n```bash\n./gradlew build\n```\nThen type `ls` to check."),
        )
    }

    @Test
    fun `emoji are dropped rather than named`() {
        assertEquals("Happy birthday!", SpeechText.clean("Happy birthday! 🎉🎂"))
        assertEquals("Thumbs up.", SpeechText.clean("Thumbs up 👍🏽"))
    }

    @Test
    fun `table rules go and cells are separated by pauses`() {
        val spoken = SpeechText.clean("| Day | Plan |\n|---|---|\n| Mon | Gym |")
        assertTrue(spoken, !spoken.contains("-") && !spoken.contains("|"))
        assertTrue(spoken, spoken.contains("Mon , Gym"))
    }

    @Test
    fun `decimals and times are left alone`() {
        assertEquals("It costs 3.50 at 10:30.", SpeechText.clean("It costs 3.50 at 10:30."))
    }
}

class SpeechChunkerTest {

    @Test
    fun `sentences are handed out as soon as they are complete`() {
        val chunker = SpeechChunker()
        assertEquals(emptyList<String>(), chunker.push("Sure"))
        assertEquals(emptyList<String>(), chunker.push("Sure, it is 5"))
        assertEquals(listOf("Sure, it is 5 pm."), chunker.push("Sure, it is 5 pm. Your"))
        assertEquals(emptyList<String>(), chunker.push("Sure, it is 5 pm. Your meeting"))
        assertEquals(listOf("Your meeting is at 6!"), chunker.push("Sure, it is 5 pm. Your meeting is at 6! "))
        assertEquals(listOf("Enjoy."), chunker.finish("Sure, it is 5 pm. Your meeting is at 6! Enjoy"))
        assertEquals(emptyList<String>(), chunker.finish("Sure, it is 5 pm. Your meeting is at 6! Enjoy"))
    }

    @Test
    fun `a line break ends a piece, so list items are spoken one by one`() {
        val chunker = SpeechChunker()
        assertEquals(listOf("Milk."), chunker.push("- Milk\n- Eggs"))
        assertEquals(listOf("Eggs."), chunker.finish("- Milk\n- Eggs"))
    }

    @Test
    fun `nothing inside an open code block is spoken until it closes`() {
        val chunker = SpeechChunker()
        assertEquals(listOf("Here you go:"), chunker.push("Here you go:\n```kotlin\nval x = 1.\n"))
        assertEquals(emptyList<String>(), chunker.push("Here you go:\n```kotlin\nval x = 1.\nval y = 2.\n"))
        assertEquals(listOf("That sets two values."), chunker.finish("Here you go:\n```kotlin\nval x = 1.\nval y = 2.\n```\nThat sets two values."))
    }

    @Test
    fun `a decimal point is not a sentence end`() {
        val chunker = SpeechChunker()
        assertEquals(emptyList<String>(), chunker.push("It is 3.5 km"))
    }

    @Test
    fun `pieces with nothing to say are skipped`() {
        val chunker = SpeechChunker()
        assertEquals(emptyList<String>(), chunker.push("🎉\n"))
        assertEquals(listOf("Done."), chunker.finish("🎉\nDone."))
    }

    @Test
    fun `skipping starts from the next sentence`() {
        val chunker = SpeechChunker()
        chunker.skip("First one. Second")
        assertEquals(listOf("Second one."), chunker.push("First one. Second one. Third"))
    }
}
