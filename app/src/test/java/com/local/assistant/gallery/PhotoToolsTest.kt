package com.local.assistant.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** How find_photos reads what the model sent, as it was seen to send it on the phone. */
class PhotoToolsTest {

    @Test
    fun `several people come apart however they are joined`() {
        assertEquals(listOf("me", "shiyas"), PhotoTools.people("me, shiyas"))
        assertEquals(listOf("me", "shiyas"), PhotoTools.people("me and shiyas"))
        assertEquals(listOf("taslim", "shiyas"), PhotoTools.people("taslim & shiyas"))
        assertEquals(listOf("me", "Shiyas"), PhotoTools.people("me with Shiyas"))
        assertEquals(listOf("Anand"), PhotoTools.people("Anand"))
        assertEquals(emptyList<String>(), PhotoTools.people(null))
    }

    @Test
    fun `filler words leave the query, what's in the photo stays`() {
        assertNull(PhotoTools.description("all"))
        assertNull(PhotoTools.description("all photos"))
        assertNull(PhotoTools.description("me"))
        assertEquals("beach", PhotoTools.description("beach photos"))
        assertEquals("beach", PhotoTools.description("beach videos"))
        assertNull(PhotoTools.description("my videos"))
        assertEquals("birthday cake", PhotoTools.description("birthday cake"))
    }

    @Test
    fun `asking what a photo says is a question to read, asking to see one is not`() {
        assertEquals("what is my driving licence number?", PhotoTools.questionIn("what is my driving licence number?"))
        assertEquals("What's my passport number", PhotoTools.questionIn("What's my passport number"))
        assertEquals("when does my insurance expire", PhotoTools.questionIn("when does my insurance expire"))
        assertEquals("tell me my PAN number", PhotoTools.questionIn("tell me my PAN number"))
        assertNull(PhotoTools.questionIn("show me my sslc certificate"))
        assertNull(PhotoTools.questionIn("where is my PAN card?"))
        assertNull(PhotoTools.questionIn("get my aadhaar card"))
        // "whatever" isn't "what".
        assertNull(PhotoTools.questionIn("whatever photos you have from goa"))
    }

    @Test
    fun `a document's short name is spelled out for the search`() {
        assertEquals("PAN card", PhotoTools.documentQuery("pan"))
        assertEquals("PAN card number", PhotoTools.documentQuery("pan number"))
        assertEquals("PAN card", PhotoTools.documentQuery("PAN card"))
        assertEquals("driving licence number", PhotoTools.documentQuery("DL number"))
        assertEquals("Japan trip", PhotoTools.documentQuery("Japan trip"))
    }

    @Test
    fun `asking for something printed on a document is a question however it's put`() {
        for (asked in listOf(
            "fetch my pan number", "give me my PAN card number", "get my sslc marks", "fetch my 10th marks", "my DL number?",
            "my dl no", "send me my aadhaar no", "fetch my credit card expiry", "my blood group?", "get my CGPA",
        )) {
            assertEquals(asked, asked, PhotoTools.questionIn(asked))
        }
        // The document itself, or photos of something: shown, not read.
        for (asked in listOf(
            "show me my marks card", "get my aadhaar card", "photos of my car's number plate", "show me photos of Mark",
            "photos with no people in them", "find my sslc mark sheet",
        )) {
            assertNull(asked, PhotoTools.questionIn(asked))
        }
    }
}
