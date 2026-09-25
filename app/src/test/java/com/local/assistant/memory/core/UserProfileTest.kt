package com.local.assistant.memory.core

import com.local.assistant.memory.db.FactEntity
import com.local.assistant.memory.db.FactOrigin
import com.local.assistant.memory.prompt.HeuristicTokenEstimator
import com.local.assistant.memory.prompt.Instructions
import com.local.assistant.memory.prompt.MemoryBudget
import com.local.assistant.memory.prompt.PrefixInputs
import com.local.assistant.memory.prompt.PromptAssembler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class UserProfileTest {

    private fun field(attribute: String) = UserProfile.FIELDS.single { it.attribute == attribute }

    @Test
    fun profileWordsFromChatLandOnTheSameFields() {
        assertEquals("job", FactKeys.attribute("Occupation"))
        assertEquals("job", FactKeys.attribute("profession"))
        assertEquals("city", FactKeys.attribute("current city"))
        assertEquals("languages", FactKeys.attribute("language"))
        assertEquals("interests", FactKeys.attribute("Hobbies"))
        // An office's location is an address, not the user's city.
        assertEquals("location", FactKeys.attribute("location"))
        // The reply language is a preference, not the languages the user speaks.
        assertEquals("pref.language", FactKeys.attribute("pref.language"))
    }

    @Test
    fun everyFieldIsFiledSensibly() {
        assertEquals(
            listOf("PROFILE", "PROFILE", "WORK", "PROFILE", "PROFILE", "PROFILE"),
            UserProfile.FIELDS.map { FactCategorizer.categorize(FactKeys.USER, it.attribute).name },
        )
        // Every field key is already in its normalised form, so the screen and the chat agree.
        UserProfile.FIELDS.forEach { assertEquals(it.attribute, FactKeys.attribute(it.attribute)) }
    }

    @Test
    fun ageMustBeAPlausibleNumberAndBlankMeansClear() {
        assertNull(UserProfile.problem(field("age"), "29"))
        assertNull(UserProfile.problem(field("age"), ""))
        assertEquals("Just the number", UserProfile.problem(field("age"), "twenty"))
        assertEquals("Between 1 and 120", UserProfile.problem(field("age"), "0"))
        assertNull(UserProfile.problem(field("name"), "Taslim"))
        assertEquals("Keep it under 60 characters", UserProfile.problem(field("name"), "x".repeat(61)))
    }

    @Test
    fun theProfileOpensEveryConversationsSystemPrompt() {
        var id = 1L
        fun core(attribute: String, value: String) = FactEntity(
            id++, FactKeys.USER, attribute, value, FactCategorizer.categorize(FactKeys.USER, attribute),
            core = true, origin = FactOrigin.USER_EDIT, sourceMessageId = null, statedAt = 1, createdAt = 1, updatedAt = 1, lastConfirmedAt = 1,
        )
        val profile = listOf(core("name", "Taslim"), core("age", "29"), core("job", "Software engineer"), core("city", "Kochi"), core("languages", "English, Malayalam"))
        val prefix = PromptAssembler(MemoryBudget.EIGHT_K, HeuristicTokenEstimator, ZoneId.of("Asia/Kolkata")).buildPrefix(
            PrefixInputs(Instructions.render("You are a helpful assistant."), profile, null, LocalDate.of(2026, 9, 25), emptyList()),
        )
        assertTrue(prefix.text.startsWith("You are a helpful assistant."))
        assertTrue(CoreMemoryRenderer.HEADER in prefix.text)
        listOf("name: Taslim", "age: 29", "job: Software engineer", "city: Kochi", "languages: English, Malayalam")
            .forEach { assertTrue("$it in the prefix", it in prefix.text) }
    }
}
