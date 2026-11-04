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
    fun greetingsAreSmallTalkHoweverLong() {
        listOf("good morning Luna", "Hi, how are you doing today?", "hey luna what's up", "good evening everyone", "have a nice day", "gm", "namaste, kaise ho")
            .forEach { assertTrue("'$it' should be small talk", QueryText.isSmallTalk(it)) }
        listOf("good morning, what's the weather today?", "how are the photos from Munnar", "good morning set an alarm for 6", "what is today", "")
            .forEach { assertFalse("'$it' should not be small talk", QueryText.isSmallTalk(it)) }
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

    @Test
    fun commandsAreToldFromQuestions() {
        for (command in listOf(
            "set an alarm for 6 am", "Hey Luna, set a timer for 10 minutes", "remind me to call amma at 7",
            "can you please remind me to pay the electricity bill tomorrow", "wake me up at 5:30",
            "call shiyas", "text priya I'm running late", "WhatsApp Sandra hi", "email hitesh the report",
            "send the address to rahul", "open youtube", "turn on the flashlight", "turn the wifi off",
            "increase the volume", "add milk to my shopping list", "schedule a meeting with Rahul tomorrow at 3",
            "cancel my 6 am alarm", "delete the reminder about the gym", "play some music",
            "navigate to the airport", "remember that my locker code is in the drawer", "forget my old address",
            "could you create a reminder for my passport renewal",
        )) {
            assertTrue(command, QueryText.isCommand(command))
        }
        for (question in listOf(
            "did I ever participate in push to prod hackathon?", "when is corniche resto's anniversary celebration",
            "remind me what I said about Goa", "remind me about our trip plan", "send me my PAN number",
            "what time is my alarm set for?", "do you remember my wife's birthday?", "show me my driving licence",
            "how do I set an alarm on this phone?", "who should I call about the leak?", "what's on my shopping list?",
            "tell me a joke", "is the flashlight on?", "when is my next meeting?",
        )) {
            assertFalse(question, QueryText.isCommand(question))
        }
    }

    @Test
    fun questionsAboutTheScreenAreSeenAsSuch() {
        listOf(
            "take a screenshot",
            "take a screen shot and share it on WhatsApp to Anjali",
            "summarise this",
            "what's on my screen?",
            "share this page with Priya",
            "email this to my landlord",
            "send it to amma on whatsapp",
            "what does this say?",
            "translate this",
            "remember this ticket",
        ).forEach { assertTrue(it, QueryText.asksAboutScreen(it)) }
    }

    @Test
    fun everydayQuestionsAreNotAboutTheScreen() {
        listOf(
            "set an alarm for 6:30",
            "remind me to pay rent tomorrow",
            "what's the weather in Kochi?",
            "call amma",
            "show me my beach photos",
            "WhatsApp Anjali that I'll be late",
            "what's 18% of 2450?",
        ).forEach { assertFalse(it, QueryText.asksAboutScreen(it)) }
    }
}
