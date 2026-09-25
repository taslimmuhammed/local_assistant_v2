package com.local.assistant.memory.core

import com.local.assistant.memory.db.FactCategory
import com.local.assistant.memory.db.FactEntity
import com.local.assistant.memory.db.FactOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AliasPlannerTest {

    private var nextId = 1L

    private fun fact(subject: String, attribute: String = "note", value: String = "x") =
        FactEntity(nextId++, subject, attribute, value, FactCategory.OTHER, false, FactOrigin.CHAT, null, 0, 0, 0, 0)

    @Test
    fun certainMergesAreKinshipHonorificsAndStoredAliases() {
        val plan = AliasPlanner.merges(
            subjects = listOf("user", "amma", "mother", "dr_rao", "priya", "priya_sharma", "anna"),
            aliases = mapOf("priya" to "priya_sharma"),
        )
        assertEquals(mapOf("amma" to "mother", "dr_rao" to "rao", "priya" to "priya_sharma"), plan)
    }

    @Test
    fun aliasChainsAreFollowedButNotInCircles() {
        assertEquals(mapOf("a" to "c"), AliasPlanner.merges(listOf("a"), mapOf("a" to "b", "b" to "c")))
        // A loop in the stored aliases ends after a few hops instead of spinning.
        assertEquals(1, AliasPlanner.merges(listOf("x"), mapOf("x" to "y", "y" to "x")).size)
    }

    @Test
    fun lookAlikesAreOnlySuggested() {
        val facts = listOf(fact("priya"), fact("priya_sharma"), fact("rahul"), fact("raahul"), fact("sister", "name", "Priya"), fact("user"))
        val suggestions = AliasPlanner.suggestions(facts, dismissed = emptySet())
        val pairs = suggestions.map { it.first to it.second }.toSet()
        assertTrue("priya" to "priya_sharma" in pairs)
        assertTrue("raahul" to "rahul" in pairs || "rahul" to "raahul" in pairs)
        assertTrue("sister" to "priya" in pairs)
        assertTrue(suggestions.none { "user" in listOf(it.first, it.second) })
    }

    @Test
    fun dismissedPairsStayDismissed() {
        val facts = listOf(fact("priya"), fact("priya_sharma"))
        val key = AliasPlanner.pairKey("priya_sharma", "priya")
        assertTrue(AliasPlanner.suggestions(facts, dismissed = setOf(key)).isEmpty())
    }

    @Test
    fun shortNamesAreNotTreatedAsSpellingSlips() {
        val facts = listOf(fact("ravi"), fact("ravu"), fact("al"), fact("al_khan"))
        assertTrue(AliasPlanner.suggestions(facts, emptySet()).isEmpty())
    }

    @Test
    fun editDistanceOne() {
        assertTrue(AliasPlanner.editDistanceIsOne("rahul", "raahul"))
        assertTrue(AliasPlanner.editDistanceIsOne("kochi", "kochy"))
        assertFalse(AliasPlanner.editDistanceIsOne("kochi", "kochi"))
        assertFalse(AliasPlanner.editDistanceIsOne("kochi", "cochin"))
    }
}
