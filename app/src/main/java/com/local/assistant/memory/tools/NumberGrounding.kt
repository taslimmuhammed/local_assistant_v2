package com.local.assistant.memory.tools

import java.util.Locale

/**
 * Puts back the digits the model dropped when it copied the user's words into a tool argument.
 *
 * Measured on the target phone (LiteRT-LM 0.17.1, GPU backend): once a message sits past token
 * position 2,048 of the conversation, the model no longer copies digits reliably — "11:45" arrives
 * as "1:45", "6:35" as "6:5", "98450 12345" as "94501235", "25+25" as "5+5+2" — while the words
 * around them come through intact. The boundary is the same with a 4K, 8K or 16K window, and the
 * CPU backend copies every digit (at ~40 s to the first token). The app has the user's exact
 * words, so a number that differs from them only in its digits is taken from them instead.
 */
object NumberGrounding {

    /** [value] with its numbers taken from [userText] where the model evidently mis-copied them. */
    fun ground(value: String, userText: String): String {
        if (!needsGrounding(value, userText)) return value
        return bySkeleton(value, userText) ?: byPhoneNumber(value, userText) ?: value
    }

    /**
     * A calculation: when the user's message is itself a sum ("25+25", "256 multiplied by 4",
     * "square root of 25"), that sum; otherwise the model's expression, grounded like any value.
     * The model's own expression wins whenever its numbers are all the user's — it may have
     * rewritten a word problem more cleverly than the words themselves.
     */
    fun groundExpression(expression: String, userText: String): String {
        if (!needsGrounding(expression, userText)) return expression
        return arithmeticIn(userText) ?: bySkeleton(expression, userText) ?: byDigitRuns(expression, userText)
    }

    private fun needsGrounding(value: String, userText: String): Boolean {
        if (value.none(Char::isDigit) || userText.none(Char::isDigit)) return false
        val theirs = digitRuns(userText).toSet()
        return !digitRuns(value).all { it in theirs }
    }

    // ---- The same words, other digits ----

    private data class Piece(val text: String, val start: Int, val end: Int)

    /** Digit runs become "#", spaces collapse to one, everything else is kept, lowercased. */
    private fun skeleton(text: String): List<Piece> {
        val pieces = mutableListOf<Piece>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val start = i
            when {
                c.isDigit() -> {
                    while (i < text.length && text[i].isDigit()) i++
                    pieces += Piece("#", start, i)
                }
                c.isWhitespace() -> {
                    while (i < text.length && text[i].isWhitespace()) i++
                    pieces += Piece(" ", start, i)
                }
                else -> {
                    i++
                    pieces += Piece(c.lowercase(Locale.ROOT), start, i)
                }
            }
        }
        return pieces
    }

    /**
     * "tomorrow at 1:45" against "remind me to call the CA tomorrow at 11:45": the same shape with
     * the digits aside, so it is "tomorrow at 11:45". Needs something besides digits to anchor on,
     * and where the shape appears twice, the place whose digits the model's contain.
     */
    private fun bySkeleton(value: String, userText: String): String? {
        val pattern = skeleton(value.trim()).map { it.text }
        if (pattern.none { it != "#" && it != " " }) return null
        val user = skeleton(userText)
        val texts = user.map { it.text }
        val matches = (0..texts.size - pattern.size).filter { at -> texts.subList(at, at + pattern.size) == pattern }
        val digits = value.filter(Char::isDigit)
        val best = matches
            .map { at -> userText.substring(user[at].start, user[at + pattern.size - 1].end) }
            .map { candidate -> candidate to commonSubsequence(digits, candidate.filter(Char::isDigit)) }
            .sortedByDescending { it.second }
        return when {
            best.isEmpty() -> null
            best.size > 1 && best[0].second == best[1].second -> null
            else -> best[0].first
        }
    }

    /** A phone number with digits dropped: "94501235" is "98450 12345" once the digits line up. */
    private fun byPhoneNumber(value: String, userText: String): String? {
        val digits = value.filter(Char::isDigit)
        if (digits.length < PHONE_DIGITS || value.any { it.isLetter() }) return null
        val candidates = PHONE.findAll(userText).map { it.value.trim() }
            .filter { number ->
                val theirs = number.filter(Char::isDigit)
                isSubsequence(digits, theirs) || isSubsequence(theirs, digits)
            }
            .toList()
        return candidates.singleOrNull()
    }

    /**
     * For a word problem's expression only: each run of two or more digits the user never wrote,
     * where exactly one number of theirs is it with a digit or two dropped ("340" for "3450").
     */
    private fun byDigitRuns(value: String, userText: String): String {
        val theirs = digitRuns(userText).distinct()
        fun fits(run: String): String? = if (run in theirs) {
            run
        } else {
            theirs.filter { it.length > run.length && it.length - run.length <= 2 && isSubsequence(run, it) }.singleOrNull()
        }
        // A number the model split with a space: "8 75" for "875".
        val joined = SPLIT_NUMBER.replace(value) { match ->
            fits(match.value.filter(Char::isDigit)) ?: match.value
        }
        return RUN.replace(joined) { match ->
            val run = match.value
            if (run in theirs || run.length < 2) run else fits(run) ?: run
        }
    }

    // ---- The user's own sum ----

    /**
     * The answer to a message that is nothing but a sum — "whats 25 * 2", "what is root of 25",
     * "18% of 2450" — worked out here, without the model: "25 × 2 = 50". Null for anything else,
     * which goes to the model as usual. The model never gets to misread the digits (past token
     * 2,048 it does), and the answer comes at once.
     */
    fun directAnswer(userText: String): String? {
        val expression = arithmeticIn(userText) ?: return null
        // Everything outside the sum must be a way of asking for it.
        var rest = " " + userText.lowercase(Locale.ROOT).replace("√", " sqrt ") + " "
        for ((words, operator) in Calculator.OPERATOR_WORDS) rest = rest.replace(Regex("\\s$words\\s"), " $operator ")
        val leftover = TOKEN.findAll(rest).map { it.value }.filterNot { isArithmetic(it) || it in ASKING || it in CLOSERS }.toList()
        if (leftover.isNotEmpty()) return null
        val result = runCatching { Calculator.run(expression) }.getOrNull() ?: return null
        return Calculator.answer(expression, result)
    }

    /** The sum the user wrote, operators spelled out or not, if their message is one. */
    private fun arithmeticIn(userText: String): String? {
        var text = " " + userText.lowercase(Locale.ROOT).replace("√", " sqrt ") + " "
        for ((words, operator) in Calculator.OPERATOR_WORDS) text = text.replace(Regex("\\s$words\\s"), " $operator ")
        val tokens = TOKEN.findAll(text).map { it.value }.toList()
        var best: List<String> = emptyList()
        var run = mutableListOf<String>()
        for (token in tokens + listOf("")) {
            if (token.isNotEmpty() && isArithmetic(token)) {
                run += token
            } else {
                val trimmed = run.dropWhile { it in LEADING_JUNK }.dropLastWhile { it in TRAILING_JUNK }
                if (trimmed.size > best.size) best = trimmed
                run = mutableListOf()
            }
        }
        val numbers = best.count { it.first().isDigit() }
        val operators = best.count { it in BINARY || it == "sqrt" || it == "%" }
        if (numbers == 0 || operators == 0 || (numbers < 2 && "sqrt" !in best && "%" !in best && "^" !in best)) return null
        val expression = best.joinToString(" ")
        return expression.takeIf { runCatching { Calculator.run(it) }.isSuccess }
    }

    private fun isArithmetic(token: String): Boolean =
        token.first().isDigit() || token in BINARY || token in setOf("(", ")", "sqrt", "of", "%")

    /** Punctuation that closes a question without being part of the sum. */
    private val CLOSERS = setOf("=", "?")

    // ---- Helpers ----

    private fun digitRuns(text: String): List<String> = RUN.findAll(text).map { it.value }.toList()

    private fun isSubsequence(short: String, long: String): Boolean {
        var i = 0
        for (c in long) if (i < short.length && short[i] == c) i++
        return i == short.length
    }

    private fun commonSubsequence(a: String, b: String): Int {
        val table = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 1..a.length) for (j in 1..b.length) {
            table[i][j] = if (a[i - 1] == b[j - 1]) table[i - 1][j - 1] + 1 else maxOf(table[i - 1][j], table[i][j - 1])
        }
        return table[a.length][b.length]
    }

    private const val PHONE_DIGITS = 6

    private val RUN = Regex("\\d+")

    /** Digit runs with single spaces between them. */
    private val SPLIT_NUMBER = Regex("\\d+(?: \\d+)+")

    /** Digit groups joined by single spaces or dashes, with an optional +: "98450 12345", "+91-98450-12345". */
    private val PHONE = Regex("\\+?\\d[\\d\\s-]{4,}\\d")

    private val TOKEN = Regex("\\d[\\d,]*(?:\\.\\d+)?|sqrt|[-+*/^×÷%()=?]|[a-z']+")

    /** Words that only ask for a sum: "what's", "calculate", "how much is"… */
    private val ASKING = setOf(
        "what", "what's", "whats", "is", "are", "the", "calculate", "compute", "solve", "evaluate", "find",
        "how", "much", "tell", "me", "please", "pls", "equals", "equal", "answer", "result", "value", "can", "you",
        "hey", "ok", "so", "and", "then",
    )

    private val BINARY = setOf("+", "-", "*", "/", "^", "×", "÷")
    private val LEADING_JUNK = setOf("of", "+", "*", "/", "^", "×", "÷", ")")
    private val TRAILING_JUNK = setOf("of", "+", "-", "*", "/", "^", "×", "÷", "(", "sqrt")
}
