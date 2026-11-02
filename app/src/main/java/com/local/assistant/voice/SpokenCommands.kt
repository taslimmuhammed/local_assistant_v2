package com.local.assistant.voice

import com.local.assistant.memory.retrieval.QueryText
import java.util.Locale

/**
 * "Set alarm at 4:30" as the recogniser sometimes writes it down for the user: "sarlamat 4:30
 * p.m. today", "setalaam 4:30" (2 Nov 2026). Read that way, the chat made a reminder named
 * "Sarlamat" instead of an alarm, and an alarm named after the garble. A message that opens with
 * words a few letters from "set alarm (at|for)" and comes to a time within a few words is put
 * right before anything reads it ([repair]); a name that is only those words is no name
 * ([isGarbledCommand]).
 *
 * The bar ([MAX_EDITS], [MAX_SHARE]): of garbled openings, "setalaam" is 1 letter from "set
 * alarm", "sarlamat" and "salaam" 3 from "set alarm at" and "set alarm"; of real ones, "call mom
 * at", "gym at" and "leave at" are 4 or more from all of them.
 */
object SpokenCommands {

    private class Opening(said: String, val meant: String) {
        val letters = letters(said)
    }

    private val OPENINGS = listOf(
        "set alarm" to "set an alarm", "set an alarm" to "set an alarm", "set the alarm" to "set an alarm", "set a alarm" to "set an alarm",
        "set alarm at" to "set an alarm at", "set an alarm at" to "set an alarm at", "set the alarm at" to "set an alarm at", "alarm at" to "set an alarm at",
        "set alarm for" to "set an alarm for", "set an alarm for" to "set an alarm for", "set the alarm for" to "set an alarm for", "alarm for" to "set an alarm for",
    ).map { (said, meant) -> Opening(said, meant) }

    /** At most this many letters changed, added or dropped from an opening… */
    private const val MAX_EDITS = 3

    /** …and at most this share of its letters. */
    private const val MAX_SHARE = 0.45f

    /** The opening is at most this many words, and the time this many words after it. */
    private const val MAX_WORDS = 3
    private const val TIME_WITHIN = 4

    /** Shorter heads are too easily a few letters from anything. */
    private const val MIN_LETTERS = 4

    /** "4:30", "6", "16.30", "5am", "7pm,": an hour, maybe minutes, maybe am or pm. */
    private val TIME = Regex("^(\\d{1,2}([:.]\\d{2})?(a\\.?m\\.?|p\\.?m\\.?)?|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve)[,.!?]?$")

    /** Words a name may carry beside the garble: "Sarlamat 4:30 pm today". */
    private val TIME_WORDS = setOf("at", "for", "am", "pm", "today", "tomorrow", "tonight", "morning", "evening", "night", "o'clock", "oclock")

    /**
     * [text] with a garbled alarm request at its start put right ("Sarlamat 4:30 p.m. today" →
     * "Set an alarm at 4:30 p.m. today"); anything else as it is.
     */
    fun repair(text: String): String {
        if (QueryText.isCommand(text)) return text
        val words = text.trim().split(Regex("\\s+"))
        var taken = 0
        var meant: Opening? = null
        var bestShare = Float.MAX_VALUE
        for (k in 1..minOf(MAX_WORDS, words.size - 1)) {
            // Only words: a time is where the opening ends.
            if (words[k - 1].any { it.isDigit() }) break
            val head = letters(words.take(k).joinToString(" "))
            if (head.length < MIN_LETTERS) continue
            for (opening in OPENINGS) {
                val share = closeness(head, opening) ?: continue
                if (share < bestShare) {
                    bestShare = share
                    taken = k
                    meant = opening
                }
            }
        }
        val opening = meant ?: return text
        val rest = words.drop(taken)
        if (rest.take(TIME_WITHIN).none { TIME.matches(it.lowercase(Locale.ROOT)) }) return text
        return (opening.meant + " " + rest.joinToString(" ")).replaceFirstChar { it.titlecase(Locale.ROOT) }
    }

    /** Whether [name] is only a garbled "set alarm", maybe with its time: "Setalaam", "Sarlamat 4:30 pm". */
    fun isGarbledCommand(name: String): Boolean {
        val kept = QueryText.words(name.lowercase(Locale.ROOT)).filter { word -> word !in TIME_WORDS && word.none { it.isDigit() } }
        val head = letters(kept.joinToString(" "))
        return head.length >= MIN_LETTERS && OPENINGS.any { closeness(head, it) != null }
    }

    /** The share of [opening]'s letters [head] differs by, when within the bar; null when not. */
    private fun closeness(head: String, opening: Opening): Float? {
        val edits = edits(head, opening.letters)
        val share = edits.toFloat() / opening.letters.length
        return share.takeIf { edits <= MAX_EDITS && it <= MAX_SHARE }
    }

    private fun letters(text: String): String = text.lowercase(Locale.ROOT).filter { it.isLetter() }

    /** Levenshtein distance. */
    private fun edits(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            previous = current
        }
        return previous[b.length]
    }
}
