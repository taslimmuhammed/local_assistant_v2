package com.local.assistant.privacy

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class LogTextTest {

    @After
    fun reset() {
        LogText.showsUserText = false
    }

    @Test
    fun `a release build hides the user's text and the exception that could carry it`() {
        LogText.showsUserText = false
        assertEquals("Place '…' isn't in the user's words", "Place '${LogText.of("DMart")}' isn't in the user's words")
        assertEquals(LogText.HIDDEN, LogText.of(mapOf("phone" to "98765 43210")))
        assertNull(LogText.trace(IllegalStateException("fts5: syntax error near \"milk\"")))
    }

    @Test
    fun `a debuggable build shows them`() {
        LogText.showsUserText = true
        assertEquals("DMart", LogText.of("DMart"))
        assertEquals("{phone=98765 43210}", LogText.of(mapOf("phone" to "98765 43210")))
        val e = IllegalStateException("fts5: syntax error near \"milk\"")
        assertSame(e, LogText.trace(e))
    }
}
