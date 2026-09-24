package com.local.assistant.memory.core

import com.local.assistant.memory.db.FactCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FactKeysTest {

    @Test
    fun `keys are lowercased, trimmed, collapsed and snake cased`() {
        assertEquals("office_address", FactKeys.attribute("  Office   Address "))
        assertEquals("office_address", FactKeys.attribute("office-address"))
        assertEquals("best_friend", FactKeys.attribute("Best__Friend"))
    }

    @Test
    fun `honorifics are stripped from keys but the value keeps them`() {
        assertEquals("rao", FactKeys.subject("Dr. Rao"))
        assertEquals("rao", FactKeys.subject("Dr Rao"))
        assertEquals("rao", FactKeys.subject("Rao ji"))
        assertEquals("iyer", FactKeys.subject("Mr. Iyer sir"))
        assertEquals("Dr. Rao", FactKeys.value("  Dr.  Rao "))
    }

    @Test
    fun `a lone honorific is not stripped to nothing`() {
        assertEquals("dr", FactKeys.subject("Dr."))
    }

    @Test
    fun `the user is always user`() {
        listOf("me", "I", "myself", "User", "my").forEach { assertEquals(it, "user", FactKeys.subject(it)) }
    }

    @Test
    fun `possessives are dropped`() {
        assertEquals("mother", FactKeys.subject("my mother"))
        assertEquals("wife", FactKeys.subject("My wife"))
    }

    @Test
    fun `kinship terms across languages file under one key`() {
        listOf("amma", "Mummy", "mom", "maa", "ammi").forEach { assertEquals(it, "mother", FactKeys.subject(it)) }
        listOf("appa", "Papa", "dad", "pitaji").forEach { assertEquals(it, "father", FactKeys.subject(it)) }
        assertEquals("mother", FactKeys.attribute("Mom"))
    }

    @Test
    fun `ambiguous kinship words are left alone`() {
        // In Hindi "mama" is a maternal uncle, not a mother.
        assertEquals("mama", FactKeys.subject("mama"))
        assertEquals("baba", FactKeys.subject("baba"))
    }

    @Test
    fun `response preferences are namespaced`() {
        assertEquals("pref.reply_style", FactKeys.attribute("pref.reply_style"))
        assertEquals("pref.reply_style", FactKeys.attribute("reply style"))
        assertEquals("pref.reply_style", FactKeys.attribute("preference.reply_style"))
        assertEquals("pref.language", FactKeys.attribute("pref_language"))
    }

    @Test
    fun `indic script keys survive with their vowel signs`() {
        assertEquals("डॉक्टर_राव", FactKeys.subject("डॉक्टर राव"))
        assertEquals("ராஜேஷ்", FactKeys.subject("ராஜேஷ்"))
    }

    @Test
    fun `kinship words in their own scripts file under the same key`() {
        assertEquals("mother", FactKeys.subject("माँ"))
        assertEquals("mother", FactKeys.subject("अम्मा"))
        assertEquals("father", FactKeys.subject("அப்பா"))
        assertEquals("mother", FactKeys.subject("അമ്മ"))
    }

    @Test
    fun `composed and decomposed spellings are one key`() {
        assertEquals(FactKeys.subject("Zo\u00EB"), FactKeys.subject("Zoe\u0308"))
    }

    @Test
    fun `values compare loosely`() {
        assertTrue(FactKeys.sameValue("Dr. Rao", "dr. rao."))
        assertTrue(FactKeys.sameValue("short  answers", "Short answers"))
        assertFalse(FactKeys.sameValue("Dr. Rao", "Dr. Mehta"))
    }

    @Test
    fun `categories come from a table, not the model`() {
        assertEquals(FactCategory.PEOPLE, FactCategorizer.categorize("user", "dentist"))
        assertEquals(FactCategory.PEOPLE, FactCategorizer.categorize("user", "ca"))
        assertEquals(FactCategory.PREFERENCE, FactCategorizer.categorize("user", "pref.reply_style"))
        assertEquals(FactCategory.PROFILE, FactCategorizer.categorize("user", "name"))
        assertEquals(FactCategory.PROFILE, FactCategorizer.categorize("user", "city"))
        assertEquals(FactCategory.PLACES, FactCategorizer.categorize("user", "office_address"))
        assertEquals(FactCategory.WORK, FactCategorizer.categorize("user", "employer"))
        assertEquals(FactCategory.HEALTH, FactCategorizer.categorize("user", "allergies"))
        assertEquals(FactCategory.ROUTINE, FactCategorizer.categorize("user", "gym_time"))
        assertEquals(FactCategory.PEOPLE, FactCategorizer.categorize("mother", "birthday"))
        assertEquals(FactCategory.PEOPLE, FactCategorizer.categorize("rahul", "phone"))
        assertEquals(FactCategory.PLACES, FactCategorizer.categorize("office", "floor"))
        assertEquals(FactCategory.OTHER, FactCategorizer.categorize("car", "registration"))
        assertEquals(FactCategory.OTHER, FactCategorizer.categorize("user", "favourite_number"))
    }
}
