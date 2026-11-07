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
            val picked = Starters.pick(Random(seed), hour = 20, web = false, photos = false, health = false, count = 4)
            assertTrue(picked.all { it.needs == Starter.Needs.NOTHING })
        }
    }

    @Test
    fun atMostOnePlainChatAndTheRestItsOwn() {
        repeat(200) { seed ->
            val picked = Starters.pick(Random(seed), hour = 9, web = false, photos = false, count = 4).drop(1)
            assertEquals(4, picked.size)
            assertTrue(picked.count { it.kind.plainChat } <= 1)
        }
    }

    @Test
    fun everyKindTurnsUp() {
        // The new abilities among them: place reminders, lists, health, two at once.
        val seen = (0 until 400).flatMap { seed ->
            Starters.pick(Random(seed), hour = 9, web = true, photos = true, health = true, count = 4).map { it.kind }
        }.toSet()
        assertEquals(Starter.Kind.entries.toSet(), seen)
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
