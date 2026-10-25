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
}
