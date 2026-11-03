package com.local.assistant.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class StartersTest {

    @Test
    fun greetingFirstThenFourOfDifferentKinds() {
        repeat(50) { seed ->
            val picked = Starters.pick(Random(seed), hour = 9, web = true, photos = true, count = 4)
            assertEquals(5, picked.size)
            assertEquals("Hey, good morning!", picked.first().text)
            assertEquals(picked.size, picked.map { it.kind }.distinct().size)
        }
    }

    @Test
    fun nothingThatIsNotSetUp() {
        repeat(50) { seed ->
            val picked = Starters.pick(Random(seed), hour = 20, web = false, photos = false)
            assertTrue(picked.all { it.needs == Starter.Needs.NOTHING })
        }
    }

    @Test
    fun greetingFollowsTheClock() {
        assertEquals("Hey, good morning!", Starters.greeting(5).text)
        assertTrue(Starters.greeting(14).text.startsWith("Good afternoon"))
        assertEquals("Good evening!", Starters.greeting(21).text)
        assertEquals("Hey, still up?", Starters.greeting(2).text)
    }

    @Test
    fun noSuggestionReachesSomeoneElse() {
        // A tap sends at once: nothing that calls or messages a person.
        val reaching = Regex("""\b(call|text|message|whatsapp|send)\b""", RegexOption.IGNORE_CASE)
        assertFalse(Starters.ALL.any { it.kind != Starter.Kind.REMINDER && reaching.containsMatchIn(it.text) })
    }
}
