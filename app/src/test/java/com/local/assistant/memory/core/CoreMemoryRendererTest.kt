package com.local.assistant.memory.core

import com.local.assistant.memory.db.FactCategory
import com.local.assistant.memory.db.FactEntity
import com.local.assistant.memory.db.FactOrigin
import com.local.assistant.memory.prompt.HeuristicTokenEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreMemoryRendererTest {

    private val renderer = CoreMemoryRenderer(HeuristicTokenEstimator)
    private var nextId = 1L

    private fun fact(
        attribute: String,
        value: String,
        subject: String = "user",
        core: Boolean = true,
        lastConfirmedAt: Long = 0,
    ) = FactEntity(
        id = nextId++,
        subject = subject,
        attribute = attribute,
        value = value,
        category = FactCategorizer.categorize(subject, attribute),
        core = core,
        origin = FactOrigin.CHAT,
        sourceMessageId = null,
        statedAt = 0,
        createdAt = 0,
        updatedAt = 0,
        lastConfirmedAt = lastConfirmedAt,
    )

    @Test
    fun `renders compact key value lines grouped by category`() {
        val facts = listOf(
            fact("dentist", "Dr. Rao"),
            fact("name", "Arjun"),
            fact("pref.reply_style", "short answers"),
            fact("city", "Bengaluru"),
            fact("ca", "Mr. Iyer"),
            fact("pref.language", "English"),
        )
        val core = renderer.render(facts, capTokens = 1_200)
        assertEquals(
            """
            About the user (always keep in mind):
            Preferences: reply style: short answers | language: English
            About: name: Arjun | city: Bengaluru
            People: dentist: Dr. Rao | CA: Mr. Iyer
            """.trimIndent(),
            core.text,
        )
        assertTrue(core.omitted.isEmpty())
    }

    @Test
    fun `output is byte-identical whatever order the rows arrive in`() {
        val facts = listOf(fact("name", "Arjun"), fact("dentist", "Dr. Rao"), fact("pref.tone", "warm"))
        assertEquals(
            renderer.render(facts, 1_200).text,
            renderer.render(facts.reversed(), 1_200).text,
        )
    }

    @Test
    fun `only core facts about the user are rendered`() {
        val facts = listOf(
            fact("name", "Arjun"),
            fact("city", "Bengaluru", core = false),
            fact("birthday", "12 May", subject = "mother"),
        )
        val core = renderer.render(facts, 1_200)
        assertEquals(listOf("name"), core.included.map { it.attribute })
    }

    @Test
    fun `over the cap, lower priority and staler facts go first but preferences never do`() {
        val preferences = (1..3).map { fact("pref.rule_$it", "a fairly long standing instruction number $it") }
        val profile = fact("name", "Arjun", lastConfirmedAt = 10)
        val staleOther = fact("favourite_number", "seven", lastConfirmedAt = 1)
        val freshOther = fact("car", "a red hatchback", lastConfirmedAt = 99)

        val all = preferences + profile + staleOther + freshOther
        val tight = renderer.render(all, capTokens = HeuristicTokenEstimator.estimate(renderer.render(preferences + profile + freshOther, 10_000).text))

        assertTrue(tight.included.containsAll(preferences))
        assertTrue(profile in tight.included)
        assertTrue(freshOther in tight.included)
        assertEquals(listOf(staleOther), tight.omitted)

        val nothingFits = renderer.render(all, capTokens = 1)
        assertTrue("preferences survive any cap", nothingFits.included.containsAll(preferences))
        assertEquals(3, nothingFits.omitted.size)
    }

    @Test
    fun `values cannot break the line format`() {
        val core = renderer.render(listOf(fact("pref.style", "short\nno lists | ever")), 1_200)
        assertEquals("Preferences: style: short no lists / ever", core.text.lines().last())
    }

    @Test
    fun `no core facts renders nothing at all`() {
        assertEquals(CoreMemory.EMPTY, renderer.render(listOf(fact("name", "Arjun", core = false)), 1_200))
    }

    @Test
    fun `categories are ranked preference, profile, people, then the rest`() {
        assertEquals(
            listOf(FactCategory.PREFERENCE, FactCategory.PROFILE, FactCategory.PEOPLE),
            FactCategory.entries.take(3),
        )
    }
}
