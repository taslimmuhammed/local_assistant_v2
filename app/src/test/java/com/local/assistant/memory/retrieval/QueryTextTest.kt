package com.local.assistant.memory.retrieval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueryTextTest {

    @Test
    fun acknowledgementsAndShortMessagesAreTrivial() {
        listOf("ok", "Thanks!", "👍", "ok thank you so much", "haan theek hai", "cool, got it", "sounds good bro", "", "call Rao")
            .forEach { assertTrue("'$it' should be trivial", QueryText.isTrivial(it)) }
    }

    @Test
    fun realQuestionsAreNot() {
        listOf("my dentist is Dr. Rao", "what did I tell you about the CA", "kal meeting kab hai?", "ok so what about the flat in Jayanagar")
            .forEach { assertFalse("'$it' should not be trivial", QueryText.isTrivial(it)) }
    }

    @Test
    fun termsDropStopWordsAndRepeats() {
        assertEquals(listOf("ca", "iyer", "office", "jayanagar"), QueryText.terms("My CA is Mr. Iyer, his office is in Jayanagar. Iyer!"))
        assertEquals(listOf("meeting", "kal"), QueryText.terms("meeting kab hai kal?"))
    }

    @Test
    fun rareTermsAreNamesAcronymsAndNumbers() {
        val rare = QueryText.rareTerms("My CA is Mr. Iyer, office in Jayanagar. Flat 402 on Monday.")
        assertTrue("ca" in rare)
        assertTrue("iyer" in rare)
        assertTrue("jayanagar" in rare)
        assertTrue("402" in rare)
        assertFalse("titles are not names", "mr" in rare)
        assertFalse("sentence-initial capitals are not names", "flat" in rare)
        assertFalse("weekdays are capitalised by convention", "monday" in rare)
        assertFalse("office" in rare)
    }

    @Test
    fun lowercaseTypingHasNoRareTermsButDigitsStill() {
        assertEquals(setOf("b12"), QueryText.rareTerms("did the doctor say anything about b12 levels"))
    }

    @Test
    fun ftsMatchUsesPrefixesOnlyForLongerWords() {
        assertEquals("ca OR dentist* OR 402", QueryText.ftsMatch(listOf("ca", "dentist", "402")))
        assertNull(QueryText.ftsMatch(emptyList()))
    }

    @Test
    fun indicScriptsKeepTheirMarksInsideWords() {
        val terms = QueryText.terms("मेरा डॉक्टर कौन है")
        assertTrue(terms.contains("डॉक्टर"))
    }
}
