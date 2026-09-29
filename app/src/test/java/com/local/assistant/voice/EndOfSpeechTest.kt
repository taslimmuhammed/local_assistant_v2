package com.local.assistant.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EndOfSpeechTest {

    /** Feeds [levels] one reading every [stepMs] from [startMs]; returns the last verdict and when. */
    private fun EndOfSpeech.feed(levels: List<Float>, startMs: Long = 0, stepMs: Long = 60): Pair<EndOfSpeech.Verdict, Long> {
        var at = startMs
        var verdict = EndOfSpeech.Verdict.LISTENING
        for (level in levels) {
            verdict = onLevel(at, level)
            if (verdict != EndOfSpeech.Verdict.LISTENING) return verdict to at
            at += stepMs
        }
        return verdict to at
    }

    private fun quiet(ms: Long, level: Float = 0.01f, stepMs: Long = 60) = List((ms / stepMs).toInt()) { level }

    private fun talk(ms: Long, stepMs: Long = 60) = List((ms / stepMs).toInt()) { i -> if (i % 3 == 0) 0.12f else 0.3f }

    @Test
    fun `a question followed by silence ends it`() {
        val detector = EndOfSpeech()
        val (during, _) = detector.feed(quiet(500) + talk(1500))
        assertEquals(EndOfSpeech.Verdict.LISTENING, during)
        assertTrue(detector.heardSpeech)
        val (after, at) = detector.feed(quiet(2000), startMs = 2000)
        assertEquals(EndOfSpeech.Verdict.DONE, after)
        // About the silence it waits for, after the last loud reading.
        assertTrue("ended at $at", at in 3200L..3500L)
    }

    @Test
    fun `a short pause mid-sentence does not end it`() {
        val detector = EndOfSpeech()
        val (verdict, _) = detector.feed(talk(1000) + quiet(700) + talk(1000))
        assertEquals(EndOfSpeech.Verdict.LISTENING, verdict)
    }

    @Test
    fun `talking straight away still counts, the room isn't learned from the voice`() {
        val detector = EndOfSpeech()
        val (verdict, _) = detector.feed(talk(3000))
        assertEquals(EndOfSpeech.Verdict.LISTENING, verdict)
        assertTrue(detector.heardSpeech)
    }

    @Test
    fun `steady background noise is not speech`() {
        val detector = EndOfSpeech()
        val (verdict, at) = detector.feed(quiet(8000, level = 0.025f))
        assertEquals(EndOfSpeech.Verdict.NOTHING_HEARD, verdict)
        assertFalse(detector.heardSpeech)
        assertTrue("gave up at $at", at >= 7000)
    }

    @Test
    fun `speech over a noisy room is still heard, and its end found`() {
        val detector = EndOfSpeech()
        val noise = quiet(600, level = 0.035f)
        val speech = List(25) { i -> if (i % 2 == 0) 0.25f else 0.4f }
        val (during, _) = detector.feed(noise + speech)
        assertEquals(EndOfSpeech.Verdict.LISTENING, during)
        assertTrue(detector.heardSpeech)
        val (after, _) = detector.feed(quiet(2000, level = 0.035f), startMs = 2100)
        assertEquals(EndOfSpeech.Verdict.DONE, after)
    }

    @Test
    fun `a single click is not a question`() {
        val detector = EndOfSpeech()
        val (verdict, _) = detector.feed(listOf(0.8f) + quiet(8000))
        assertEquals(EndOfSpeech.Verdict.NOTHING_HEARD, verdict)
    }
}
