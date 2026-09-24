package com.local.assistant.memory.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil

class MeasuredTokenEstimatorTest {

    private class MemoryStore : TokenRateStore {
        val saved = mutableMapOf<String, Double>()
        override fun load(modelKey: String) = saved[modelKey]
        override fun save(modelKey: String, latinCharsPerToken: Double) {
            saved[modelKey] = latinCharsPerToken
        }
    }

    private val store = MemoryStore()
    private var model: String? = "gemma"
    private val estimator = MeasuredTokenEstimator(store) { model }

    private val english = "the quick brown fox jumps over the lazy dog ".repeat(100)
    private val hindi = "मेरे डेंटिस्ट डॉक्टर राव हैं ".repeat(100)

    /** A conversation of [chars] Latin characters that the runtime counted at [charsPerToken]. */
    private fun observeEnglish(chars: Int, charsPerToken: Double, messages: Int = 10) =
        estimator.observe(ScriptCounts(chars, 0), messages, ceil(chars / charsPerToken).toInt())

    @Test
    fun `before any measurement it is exactly the heuristic`() {
        assertEquals(HeuristicTokenEstimator.estimate(english), estimator.estimate(english))
        assertEquals(HeuristicTokenEstimator.estimate(hindi), estimator.estimate(hindi))
    }

    @Test
    fun `a measured rate brings english estimates down but never below what was counted`() {
        repeat(20) { observeEnglish(chars = 20_000, charsPerToken = 5.5) }
        assertTrue(estimator.latinCharsPerToken > 4.5)

        val counted = ceil(english.length / 5.5).toInt()
        val estimated = estimator.estimate(english)
        assertTrue("estimate $estimated fell below the real $counted", estimated >= counted)
        assertTrue(estimated < HeuristicTokenEstimator.estimate(english))
    }

    @Test
    fun `indic script keeps the dense rate however much english was measured`() {
        repeat(20) { observeEnglish(chars = 20_000, charsPerToken = 5.5) }
        // Only the Devanagari itself: the spaces between words are Latin, and measured as such.
        val devanagari = hindi.replace(" ", "")
        assertEquals(HeuristicTokenEstimator.estimate(devanagari), estimator.estimate(devanagari))
    }

    @Test
    fun `denser text tightens the rate at once, looser text relaxes it only gradually`() {
        repeat(20) { observeEnglish(chars = 20_000, charsPerToken = 5.5) }
        val relaxed = estimator.latinCharsPerToken

        observeEnglish(chars = 20_000, charsPerToken = 4.0)
        assertTrue(estimator.latinCharsPerToken < 4.1)

        observeEnglish(chars = 20_000, charsPerToken = 5.5)
        assertTrue(estimator.latinCharsPerToken < relaxed)
    }

    @Test
    fun `the rate never goes past the safe bounds`() {
        repeat(50) { observeEnglish(chars = 20_000, charsPerToken = 9.0) }
        assertEquals(MeasuredTokenEstimator.MAX_LATIN_CHARS_PER_TOKEN, estimator.latinCharsPerToken, 1e-6)

        observeEnglish(chars = 20_000, charsPerToken = 2.0)
        assertEquals(HeuristicTokenEstimator.LATIN_CHARS_PER_TOKEN, estimator.latinCharsPerToken, 1e-9)
    }

    @Test
    fun `small or mixed-script conversations teach it nothing`() {
        observeEnglish(chars = 500, charsPerToken = 6.0)
        estimator.observe(ScriptCounts(latin = 10_000, other = 5_000), messages = 10, realTokens = 4_000)
        assertEquals(HeuristicTokenEstimator.LATIN_CHARS_PER_TOKEN, estimator.latinCharsPerToken, 1e-9)
    }

    @Test
    fun `an overflow throws the measurement away`() {
        repeat(20) { observeEnglish(chars = 20_000, charsPerToken = 5.5) }
        estimator.onOverflow()
        assertEquals(HeuristicTokenEstimator.estimate(english), estimator.estimate(english))
        assertEquals(HeuristicTokenEstimator.LATIN_CHARS_PER_TOKEN, store.saved.getValue("gemma"), 1e-9)
    }

    @Test
    fun `the rate survives a restart and belongs to one model`() {
        repeat(20) { observeEnglish(chars = 20_000, charsPerToken = 5.5) }
        val learned = estimator.latinCharsPerToken

        val restarted = MeasuredTokenEstimator(store) { model }
        assertEquals(learned, restarted.latinCharsPerToken, 1e-9)

        model = "another-model"
        assertEquals(HeuristicTokenEstimator.LATIN_CHARS_PER_TOKEN, restarted.latinCharsPerToken, 1e-9)
    }
}
