package com.local.assistant.location

import com.local.assistant.memory.db.PlaceDistance
import java.util.Locale
import kotlin.math.roundToInt

/**
 * A distance said with a place reminder: "5 km before I reach Munnar", "before 5 kilometer",
 * "within 500 m of DMart", "a mile ahead of Kochi". Read from the user's words, or from the title
 * or place the model passed: on 5 Nov 2026 Gemma named a reminder "Reminder before 5 kilometer"
 * and passed only "Munnar" as its place.
 *
 * A number and a unit are not enough on their own, or "2 m of cloth" would be one: a word has to
 * say which way, before arriving or from the place.
 */
object DistanceWords {

    /** The distance [text] says, and the characters that say it. */
    data class Said(val distance: PlaceDistance, val range: IntRange)

    fun find(text: String?): Said? {
        if (text.isNullOrBlank()) return null
        for (match in PHRASE.findAll(text)) {
            val ways = (match.groupValues[1] + " " + match.groupValues[4]).lowercase(Locale.ROOT)
            val beforeArriving = when {
                BEFORE.containsMatchIn(ways) -> true
                FROM.containsMatchIn(ways) -> false
                else -> continue
            }
            val amount = amount(match.groupValues[2]) ?: continue
            val metres = (amount * unit(match.groupValues[3])).roundToInt()
            if (metres <= 0) continue
            return Said(PlaceDistance(metres, beforeArriving), match.range)
        }
        return null
    }

    /** [text] without the distance it says: "Reminder before 5 kilometer" is "Reminder". */
    fun remove(text: String): String {
        val said = find(text) ?: return text
        return text.removeRange(said.range).replace(SPACES, " ").trim().trim(',', '-')
    }

    private fun amount(said: String): Double? {
        val word = said.lowercase(Locale.ROOT).replace('-', ' ').replace(SPACES, " ")
        return word.replace(",", "").toDoubleOrNull() ?: NUMBERS[word]
    }

    private fun unit(said: String): Double {
        val unit = said.lowercase(Locale.ROOT)
        return when {
            unit.startsWith("k") -> 1000.0
            unit.startsWith("mi") -> 1609.344
            else -> 1.0
        }
    }

    private val SPACES = Regex("\\s+")

    private val NUMBERS = mapOf(
        "a" to 1.0, "an" to 1.0, "one" to 1.0, "half" to 0.5, "half a" to 0.5, "half an" to 0.5,
        "two" to 2.0, "three" to 3.0, "four" to 4.0, "five" to 5.0, "six" to 6.0, "seven" to 7.0, "eight" to 8.0,
        "nine" to 9.0, "ten" to 10.0, "fifteen" to 15.0, "twenty" to 20.0, "twenty five" to 25.0, "thirty" to 30.0,
        "forty" to 40.0, "fifty" to 50.0, "hundred" to 100.0, "a hundred" to 100.0,
    )

    private const val NUMBER =
        "\\d{1,3}(?:,\\d{3})+|\\d+(?:\\.\\d+)?|half an?|half|a hundred|hundred|twenty[- ]five|twenty|thirty|forty|fifty|fifteen|" +
            "one|two|three|four|five|six|seven|eight|nine|ten|an?"

    private const val UNIT = "kilo\\s?met(?:er|re)s?|k\\.?m\\.?s?|met(?:er|re)s?|mtrs?|miles?|mi|m"

    /** Words around the distance that say which way, or join it to the place: consumed with it. */
    private const val AFTER =
        "before|ahead of|ahead|earlier|early|prior to|prior|short of|away from|away|out from|from|of"

    /**
     * [1] a word before ("within 500 m", "before 5 km"), [2] the number, [3] the unit, [4] the
     * words after ("5 km before", "1 km away from").
     */
    private val PHRASE = Regex(
        "(?<![\\p{L}\\p{N}])(?:(within|before|ahead of|about|around|nearly|almost)\\s+)?($NUMBER)\\s*($UNIT)(?![\\p{L}])((?:\\s+(?:$AFTER)(?![\\p{L}]))*)",
        RegexOption.IGNORE_CASE,
    )

    private val BEFORE = Regex("\\b(?:before|ahead|earlier|early|prior|short)\\b")
    private val FROM = Regex("\\b(?:within|away|out|from)\\b")
}
