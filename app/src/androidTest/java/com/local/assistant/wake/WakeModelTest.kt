package com.local.assistant.wake

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The wake word model hears on the phone exactly what it was tested with on the Mac. For the same
 * audio (`assets/wake/golden.*` here: a Kokoro voice saying "Hey Luna" in light noise, made by
 * tools/wakeword/make_golden.py), the app's frontend must give microWakeWord's Python features
 * value for value, and the model the same scores.
 */
@RunWith(AndroidJUnit4::class)
class WakeModelTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val appAssets = instrumentation.targetContext.assets
    private val pcm by lazy { wav(instrumentation.context.assets.open("wake/golden.wav").use { it.readBytes() }) }
    private val golden by lazy { Golden.parse(instrumentation.context.assets.open("wake/golden.txt").bufferedReader().readText()) }

    @Test
    fun featuresMatchTraining() {
        val frames = mutableListOf<IntArray>()
        MicroFrontend().use { it.process(pcm, pcm.size) { frame -> frames += frame.copyOf() } }
        // Python leaves out the last frame or two (it stops a step early); the rest must agree.
        assertTrue("${frames.size} frames, Python had ${golden.frames.size}", frames.size >= golden.frames.size)
        val differing = golden.frames.indices.filterNot { frames[it].contentEquals(golden.frames[it]) }
        assertEquals("frames that differ: ${differing.take(10)}", emptyList<Int>(), differing)
    }

    @Test
    fun scoresMatchTraining() {
        val scores = WakeModel(appAssets).use { it.scores(pcm, pcm.size) }
        assertTrue("${scores.size} scores, Python had ${golden.scores.size}", scores.size >= golden.scores.size)
        assertEquals(golden.scores, scores.take(golden.scores.size))
    }

    @Test
    fun hearsThePhraseHoweverTheAudioArrives() {
        WakeModel(appAssets).use { model ->
            val whole = model.scores(pcm, pcm.size)
            model.reset()
            val chunked = pcm.toList().chunked(WakeWord.CHUNK_SAMPLES).flatMap { model.scores(it.toShortArray(), it.size) }
            assertEquals(whole, chunked)
            model.reset()
            assertTrue(pcm.toList().chunked(WakeWord.CHUNK_SAMPLES).any { model.accept(it.toShortArray(), it.size) })
            model.reset()
            val silence = ShortArray(WakeWord.CHUNK_SAMPLES)
            assertFalse((1..30).any { model.accept(silence, silence.size) })
        }
    }

    private class Golden(val frames: List<IntArray>, val scores: List<Int>) {
        companion object {
            fun parse(text: String): Golden {
                val lines = text.lines().filter { it.isNotBlank() && !it.startsWith("#") }
                val frameCount = lines[0].removePrefix("frames ").toInt()
                val frames = lines.subList(1, 1 + frameCount).map { line -> line.split(" ").map(String::toInt).toIntArray() }
                check(lines[1 + frameCount].startsWith("scores "))
                val scores = lines[2 + frameCount].split(" ").map(String::toInt)
                return Golden(frames, scores)
            }
        }
    }

    /** The samples of a 16-bit mono WAV, from its data chunk. */
    private fun wav(bytes: ByteArray): ShortArray {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var at = 12
        while (at + 8 <= bytes.size) {
            val id = String(bytes, at, 4, Charsets.US_ASCII)
            val size = b.getInt(at + 4)
            if (id == "data") {
                val samples = ShortArray(size / 2)
                b.position(at + 8)
                b.asShortBuffer().get(samples)
                return samples
            }
            at += 8 + size + (size and 1)
        }
        error("no data chunk")
    }
}
