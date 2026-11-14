package com.local.assistant.memory.prompt

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** What the app did itself since the model's last turn reaches it with the next message, before the user's words. */
class DoneByAppTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private val assembler = PromptAssembler(MemoryBudget.EIGHT_K, HeuristicTokenEstimator, zone)
    private val now = ZonedDateTime.of(2026, 11, 13, 22, 0, 0, 0, zone)

    @Test
    fun `the app's own exchanges come before the user's words`() {
        val done = "[Done by the app before this message, without you: the user said \"set an alarm for 6:30\"; set_alarm returned {\"ok\":true}; the app told them \"Alarm set for 6:30 AM tomorrow.\"]"
        val envelope = assembler.buildEnvelope(EnvelopeInputs(now, showNow = false, userText = "what time did I set it for?", doneByApp = listOf(done)))
        assertEquals("$done\nwhat time did I set it for?", envelope.text)
    }

    @Test
    fun `none, no line`() {
        assertEquals("hi", assembler.buildEnvelope(EnvelopeInputs(now, showNow = false, userText = "hi")).text)
    }
}
