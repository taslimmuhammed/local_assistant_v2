package com.local.assistant.memory.tools

import java.util.Locale
import kotlin.math.roundToInt

/**
 * A timer's length from the user's own words, which the model copies unchanged: "10 minutes",
 * "1h30m", "an hour and a half", "90 sec", "dus minute", "dedh ghanta". Deterministic, like
 * [WhenResolver]: the model never converts units itself.
 */
object DurationParser {

    /** Seconds, or null when the words hold no length. A bare number means minutes. */
    fun seconds(text: String): Int? {
        val tokens = TOKEN.findAll(text.lowercase(Locale.ROOT)).map { it.value }.toList()
        var total = 0.0
        var pending: Double? = null
        /** "sawa", "saadhe", "paune": a quarter or half added to (or taken from) the next number. */
        var modifier = 0.0
        var lastUnit: Double? = null
        var sawUnit = false

        fun number(value: Double) {
            val current = pending
            pending = when {
                // "twenty five", "one hundred"
                current != null && current >= 20 && current % 10 == 0.0 && value < 10 -> current + value
                current != null && value == 100.0 -> current * 100
                else -> value
            } + modifier
            modifier = 0.0
        }

        var i = 0
        while (i < tokens.size) {
            val token = tokens[i]
            val next = tokens.getOrNull(i + 1)
            val unit = UNITS[token]
            when {
                token.first().isDigit() -> number(token.toDouble())
                unit != null -> {
                    total += (pending ?: 1.0) * unit
                    pending = null
                    lastUnit = unit
                    sawUnit = true
                }
                token in HALVES -> when {
                    // "one and a half hours"
                    pending != null -> pending = pending!! + 0.5
                    // "an hour and a half"
                    lastUnit != null && (next == null || UNITS[next] == null && next !in ARTICLES) -> total += 0.5 * lastUnit!!
                    else -> pending = 0.5
                }
                token == "quarter" -> pending = 0.25
                // "an hour and a half": the "a" belongs to the half.
                token in ARTICLES -> if (pending == null && next !in HALVES) pending = 1.0
                token in MODIFIERS -> modifier = MODIFIERS.getValue(token)
                token in FRACTIONS -> number(FRACTIONS.getValue(token))
                token in WORDS -> number(WORDS.getValue(token).toDouble())
            }
            i++
        }
        // "1 hour 30": a number left after a unit counts in the next smaller one.
        pending?.let { total += it * if (sawUnit) smaller(lastUnit ?: MINUTE) else MINUTE }
        val seconds = total.roundToInt()
        return seconds.takeIf { it > 0 }
    }

    /** "1 h 30 min", "10 min", "45 sec". */
    fun describe(seconds: Int): String {
        val h = seconds / 3600
        val m = seconds % 3600 / 60
        val s = seconds % 60
        return listOfNotNull(
            h.takeIf { it > 0 }?.let { "$it h" },
            m.takeIf { it > 0 }?.let { "$it min" },
            s.takeIf { it > 0 }?.let { "$it sec" },
        ).joinToString(" ")
    }

    private fun smaller(unit: Double): Double = when (unit) {
        HOUR -> MINUTE
        else -> SECOND
    }

    private const val SECOND = 1.0
    private const val MINUTE = 60.0
    private const val HOUR = 3600.0

    private val TOKEN = Regex("\\d+(?:\\.\\d+)?|\\p{L}+")

    private val UNITS: Map<String, Double> = buildMap {
        listOf("s", "sec", "secs", "second", "seconds", "seconde", "sekand").forEach { put(it, SECOND) }
        listOf("m", "min", "mins", "minute", "minutes", "minat", "mint").forEach { put(it, MINUTE) }
        listOf("h", "hr", "hrs", "hour", "hours", "ghanta", "ghante", "ghanton", "ghante").forEach { put(it, HOUR) }
    }

    private val ARTICLES = setOf("a", "an")

    private val HALVES = setOf("half", "aadha", "adha", "aadhe")

    private val MODIFIERS = mapOf("sawa" to 0.25, "saadhe" to 0.5, "sadhe" to 0.5, "saade" to 0.5, "paune" to -0.25)

    /** Hindi words that are a number and a fraction at once. */
    private val FRACTIONS = mapOf("dedh" to 1.5, "derh" to 1.5, "dhai" to 2.5, "dhaai" to 2.5, "pauna" to 0.75)

    private val WORDS: Map<String, Int> = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8,
        "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "fifteen" to 15, "twenty" to 20,
        "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "ninety" to 90, "hundred" to 100,
        "ek" to 1, "do" to 2, "teen" to 3, "char" to 4, "chaar" to 4, "paanch" to 5, "panch" to 5,
        "chhe" to 6, "che" to 6, "saat" to 7, "aath" to 8, "nau" to 9, "das" to 10, "dus" to 10,
        "gyarah" to 11, "barah" to 12, "pandrah" to 15, "bees" to 20, "pachees" to 25, "tees" to 30,
        "chalis" to 40, "chaalis" to 40, "pachas" to 50, "pachaas" to 50,
    )
}
