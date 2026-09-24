package com.local.assistant.memory.prompt

import kotlin.math.ceil

/**
 * Estimates how many tokens a piece of text costs the model.
 *
 * LiteRT-LM 0.17.1 has no tokenizer API: the only real number is `Conversation.getTokenCount()`,
 * and that exists only after the text has been prefilled. So everything that has to be decided
 * *before* sending — what fits, what to shed — works from an estimate, and the live count is used
 * to correct course wherever one exists.
 */
fun interface TokenEstimator {
    fun estimate(text: String): Int
}

/** How many UTF-16 units of a text are Latin script, and how many are anything else. */
data class ScriptCounts(val latin: Int, val other: Int) {
    val total: Int get() = latin + other

    operator fun plus(other: ScriptCounts) = ScriptCounts(latin + other.latin, this.other + other.other)

    companion object {
        val ZERO = ScriptCounts(0, 0)
    }
}

/**
 * Script-aware and deliberately pessimistic.
 *
 * Latin text is charged at 3.5 characters per token. Devanagari, Tamil and the other Indic
 * scripts tokenize far more densely — charged at 1.8 — and so do emoji, which arrive as surrogate
 * pairs and are counted per UTF-16 unit here. A 10% margin sits on top.
 *
 * On Gemma 4 the Latin rate is measured nearer 5–6 characters per token, so for English this
 * over-estimates by nearly half; [MeasuredTokenEstimator] corrects that from real counts. This
 * object stays as the floor that correction starts from and falls back to.
 */
object HeuristicTokenEstimator : TokenEstimator {

    const val LATIN_CHARS_PER_TOKEN = 3.5
    const val OTHER_CHARS_PER_TOKEN = 1.8
    const val MARGIN = 1.10

    private const val EPSILON = 1e-9

    /** Everything below here is Basic Latin, Latin-1 or Latin Extended-A/B. */
    private const val LATIN_LIMIT = 0x0250

    override fun estimate(text: String): Int = estimate(countScripts(text), LATIN_CHARS_PER_TOKEN)

    /** The same arithmetic with a different Latin rate. */
    fun estimate(counts: ScriptCounts, latinCharsPerToken: Double): Int {
        if (counts.total == 0) return 0
        val tokens = counts.latin / latinCharsPerToken + counts.other / OTHER_CHARS_PER_TOKEN
        // The epsilon keeps floating-point noise (100 × 1.1 = 110.00000000000001) from
        // rounding an exact count up by one.
        return ceil(tokens * MARGIN - EPSILON).toInt()
    }

    fun countScripts(text: String): ScriptCounts {
        var latin = 0
        for (char in text) if (char.code < LATIN_LIMIT) latin++
        return ScriptCounts(latin, text.length - latin)
    }
}
