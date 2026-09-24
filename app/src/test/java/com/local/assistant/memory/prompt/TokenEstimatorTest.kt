package com.local.assistant.memory.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenEstimatorTest {

    private val estimator = HeuristicTokenEstimator

    @Test
    fun `empty text is free`() {
        assertEquals(0, estimator.estimate(""))
    }

    @Test
    fun `latin text runs about three and a half characters a token, plus margin`() {
        // 350 chars / 3.5 = 100 tokens, +10% = 110.
        assertEquals(110, estimator.estimate("a".repeat(350)))
    }

    @Test
    fun `indic scripts are charged far more densely than latin`() {
        // 180 / 1.8 = 100 tokens, +10% = 110 — against 57 for the same length of Latin.
        assertEquals(110, estimator.estimate("क".repeat(180)))
        assertEquals(57, estimator.estimate("a".repeat(180)))
        assertTrue(estimator.estimate("मेरे डेंटिस्ट डॉक्टर राव हैं") > estimator.estimate("my dentist is dr rao"))
    }

    @Test
    fun `mixed hinglish is charged per character by script`() {
        val mixed = "kal subah 11 baje CA ko call karna yaad dilana"
        assertEquals(estimator.estimate(mixed), kotlin.math.ceil(mixed.length / 3.5 * 1.1).toInt())
    }

    @Test
    fun `estimates never undercount a string that grows`() {
        var previous = 0
        for (n in 1..200) {
            val current = estimator.estimate("x".repeat(n))
            assertTrue(current >= previous)
            previous = current
        }
    }
}
