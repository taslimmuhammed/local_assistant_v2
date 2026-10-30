package com.local.assistant.wake

import android.content.res.AssetManager

/**
 * Both listeners for [WakeWord.PHRASE], fed the same audio: the [WakeSpotter] and the [WakeModel]
 * trained on the user's voice. They miss different phrases, so the phrase is heard when either
 * hears it. Each keeps its own state, so both hear every chunk, whoever heard the last one.
 */
class WakeDetector(assets: AssetManager) : AutoCloseable {

    /** Which listener heard the phrase, for the log and kept tries. */
    enum class By(val tag: String) { SPOTTER("spotter"), MODEL("model"), BOTH("both") }

    private val spotter = WakeSpotter(assets)
    private val model = WakeModel(assets)

    /** Feeds [count] samples of [pcm]; who heard the phrase finish in them, or null. */
    fun accept(pcm: ShortArray, count: Int): By? {
        val bySpotter = spotter.accept(FloatArray(count) { pcm[it] / 32768f })
        val byModel = model.accept(pcm, count)
        return when {
            bySpotter && byModel -> By.BOTH
            bySpotter -> By.SPOTTER
            byModel -> By.MODEL
            else -> null
        }
    }

    /** Forgets what both have heard, so a new listen starts clean. */
    fun reset() {
        spotter.reset()
        model.reset()
    }

    override fun close() {
        spotter.release()
        model.close()
    }
}
