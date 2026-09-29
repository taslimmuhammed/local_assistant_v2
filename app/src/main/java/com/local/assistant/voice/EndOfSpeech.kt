package com.local.assistant.voice

import kotlin.math.max

/**
 * Decides when a spoken question is over, from the recorder's level readings (the peak of each
 * buffer, 0..1): once the user has said something and then stayed quiet for [silenceMs]. So the
 * overlay sends by itself, the way a voice assistant should, and the mic button is only needed
 * to cut it short.
 *
 * "Loud" is relative to the room: a noise floor is learned from the quiet readings, so a fan or
 * traffic doesn't count as talking. The floor is capped, so opening the overlay mid-sentence
 * can't teach it that speech is the background.
 */
class EndOfSpeech(
    private val silenceMs: Long = 1_300,
    private val nothingHeardMs: Long = 7_000,
    private val minSpeechMs: Long = 250,
) {

    enum class Verdict { LISTENING, DONE, NOTHING_HEARD }

    private var smoothed = -1f
    private var floor = -1f
    private var speechMs = 0L
    private var lastLoudAt = 0L
    private var lastAt = 0L

    /** Enough speech has been heard to be worth sending. */
    val heardSpeech: Boolean get() = speechMs >= minSpeechMs

    /** The latest level, smoothed, for the waveform. */
    val level: Float get() = smoothed.coerceAtLeast(0f)

    fun onLevel(elapsedMs: Long, peak: Float): Verdict {
        val step = (elapsedMs - lastAt).coerceIn(0L, MAX_STEP_MS)
        lastAt = elapsedMs
        smoothed = if (smoothed < 0f) peak else smoothed * (1 - SMOOTHING) + peak * SMOOTHING

        if (floor < 0f) floor = smoothed.coerceAtMost(INITIAL_FLOOR_CAP)
        val threshold = max(MIN_SPEECH_LEVEL, floor * SPEECH_OVER_FLOOR)
        val loud = smoothed > threshold
        // Quiet readings are the room: follow them closely. Loud ones may be a steady noise
        // that started after the floor was set, so creep towards those — slowly.
        floor += (smoothed - floor) * if (loud) FLOOR_CREEP else FLOOR_FOLLOW
        floor = floor.coerceIn(MIN_FLOOR, MAX_FLOOR)

        if (loud) {
            speechMs += step
            lastLoudAt = elapsedMs
        }
        return when {
            heardSpeech && elapsedMs - lastLoudAt >= silenceMs -> Verdict.DONE
            !heardSpeech && elapsedMs >= nothingHeardMs -> Verdict.NOTHING_HEARD
            else -> Verdict.LISTENING
        }
    }

    private companion object {
        /** Readings come every 40–120 ms; a longer gap (the process stalled) counts as one step. */
        const val MAX_STEP_MS = 200L
        const val SMOOTHING = 0.5f

        /** Quieter than this is never speech, however still the room. */
        const val MIN_SPEECH_LEVEL = 0.03f
        const val SPEECH_OVER_FLOOR = 2.5f

        const val INITIAL_FLOOR_CAP = 0.02f
        const val MIN_FLOOR = 0.002f

        /** A floor above this would put ordinary speech under the threshold. */
        const val MAX_FLOOR = 0.04f
        const val FLOOR_FOLLOW = 0.05f
        const val FLOOR_CREEP = 0.0015f
    }
}
