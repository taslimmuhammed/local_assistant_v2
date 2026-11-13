package com.local.assistant.ui.chat

import com.local.assistant.llm.LlmService
import org.junit.Assert.assertEquals
import org.junit.Test

class WaitingTextTest {

    @Test
    fun `a short wait says what is being done, a longer one counts its seconds`() {
        assertEquals("Thinking…", waitingText(null, null, shownAt = 0, now = 1_000))
        assertEquals("Reading the instructions…", waitingText("Reading the instructions…", null, shownAt = 0, now = 2_900))
        assertEquals("Reminder set · writing the reply · 4 s", waitingText("Reminder set · writing the reply…", null, shownAt = 0, now = 4_200))
    }

    @Test
    fun `while the model loads, it says about how long is left from the last load`() {
        val loading = LlmService.State.Loading(startedAt = 10_000, expectedMs = 9_000)
        assertEquals("Loading the model · about 9 s left", waitingText(null, loading, shownAt = 10_000, now = 10_000))
        assertEquals("Loading the model · about 3 s left", waitingText(null, loading, shownAt = 10_000, now = 16_500))
        assertEquals("Loading the model · nearly there", waitingText(null, loading, shownAt = 10_000, now = 19_500))
        assertEquals("Loading the model…", waitingText(null, LlmService.State.Loading(startedAt = 0, expectedMs = null), shownAt = 0, now = 5_000))
    }
}
