package com.local.assistant.privacy

/**
 * Card numbers, ID numbers, account numbers and secrets in a piece of text, found by their shape
 * rather than by asking the model: it already has them in front of it when it would decide, a
 * tool call costs a round of the model, and it skips tools often enough that a guard built on
 * one would leak. Shapes with a checksum are checked (Luhn for cards, Verhoeff for Aadhaar), so
 * phone numbers, dates and amounts stay as they are; numbers with no checksum count only next to
 * the word for them ("account", "passport"); secrets only after theirs ("pin", "password").
 *
 * Hiding is what the screens and the voice do with what's found ([masked], [spoken]); the text
 * itself, in memory and in the chat, is never changed.
 */
object SensitiveText {

    enum class Kind { CARD, AADHAAR, ACCOUNT, PAN, DRIVING_LICENCE, PASSPORT, SECRET }

    /** [start] until [end] of the text is a [kind]. */
    data class Span(val start: Int, val end: Int, val kind: Kind)

    /** Every sensitive span in [text], in order, none overlapping. */
    fun find(text: String): List<Span> {
        val spans = ArrayList<Span>()
        fun add(start: Int, end: Int, kind: Kind) {
            if (spans.none { it.start < end && start < it.end }) spans += Span(start, end, kind)
        }

        for (m in DIGIT_RUN.findAll(text)) {
            // A phone number written with its country code ("+91 98765 43210") is no ID.
            if (m.range.first > 0 && text[m.range.first - 1] == '+') continue
            val digits = m.value.filter(Char::isDigit)
            val kind = when {
                digits.length in 13..19 && luhn(digits) -> Kind.CARD
                digits.length == 12 && digits[0] in '2'..'9' && verhoeff(digits) -> Kind.AADHAAR
                digits.length in 9..18 && nearBefore(text, m.range.first, ACCOUNT_WORDS) -> Kind.ACCOUNT
                else -> null
            }
            kind?.let { add(m.range.first, m.range.last + 1, it) }
        }
        PAN.findAll(text).forEach { add(it.range.first, it.range.last + 1, Kind.PAN) }
        DRIVING_LICENCE.findAll(text).forEach { add(it.range.first, it.range.last + 1, Kind.DRIVING_LICENCE) }
        PASSPORT.findAll(text).filter { nearBefore(text, it.range.first, PASSPORT_WORDS) }
            .forEach { add(it.range.first, it.range.last + 1, Kind.PASSPORT) }
        for (m in SECRET_SAID.findAll(text) + SECRET_BARE.findAll(text)) {
            val value = m.groups[VALUE_GROUP] ?: continue
            val token = value.value.trimEnd('.', ',', ';', ')', '!', '?')
            if (token.isEmpty() || token.lowercase() in NOT_SECRETS) continue
            // A PIN, CVV or OTP is digits; anything else after "password" is the password.
            if (m.groups[KEYWORD_GROUP]!!.value.lowercase() !in WORD_SECRETS && token.none(Char::isDigit)) continue
            add(value.range.first, value.range.first + token.length, Kind.SECRET)
        }
        return spans.sortedBy { it.start }
    }

    fun contains(text: String): Boolean = find(text).isNotEmpty()

    /** [text] with each span hidden for the screen: "•••• •••• •••• 4417", "••••" for a secret. */
    fun masked(text: String): String = replace(text) { value, kind -> hide(value, kind) }

    /** [text] with each span hidden for the voice: "ending in 4 4 1 7", "hidden" for a secret. */
    fun spoken(text: String): String = replace(text) { value, kind ->
        if (kind == Kind.SECRET) "hidden" else "ending in " + lastFour(value).toList().joinToString(" ")
    }

    /**
     * What of a reply still being written can be shown: all but a trailing number that may not be
     * complete yet ("4111 1111" on its way to a card number, which only then passes its check).
     * Shown as "…" until the next character settles it.
     */
    fun settled(streaming: String): String {
        val tail = TRAILING_NUMBER.find(streaming) ?: return streaming
        return streaming.substring(0, tail.range.first) + "…"
    }

    private fun replace(text: String, hidden: (String, Kind) -> String): String {
        val spans = find(text)
        if (spans.isEmpty()) return text
        return buildString {
            var at = 0
            for (span in spans) {
                append(text, at, span.start)
                append(hidden(text.substring(span.start, span.end), span.kind))
                at = span.end
            }
            append(text, at, text.length)
        }
    }

    private fun hide(value: String, kind: Kind): String = when (kind) {
        Kind.SECRET -> "••••"
        // Keeps the grouping it was written with, so it still reads as a card or an Aadhaar.
        else -> {
            val keep = lastFour(value).length
            var kept = 0
            value.reversed().map { c ->
                when {
                    !c.isLetterOrDigit() -> c
                    kept < keep -> { kept++; c }
                    else -> '•'
                }
            }.reversed().joinToString("")
        }
    }

    private fun lastFour(value: String): String = value.filter(Char::isLetterOrDigit).takeLast(4)

    private fun nearBefore(text: String, at: Int, words: Regex): Boolean =
        words.containsMatchIn(text.substring(maxOf(0, at - NEAR_CHARS), at))

    /** The Luhn check every card number passes. */
    internal fun luhn(digits: String): Boolean {
        var sum = 0
        for ((i, c) in digits.reversed().withIndex()) {
            var d = c - '0'
            if (i % 2 == 1) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
        }
        return sum % 10 == 0
    }

    /** The Verhoeff check every Aadhaar number passes. */
    internal fun verhoeff(digits: String): Boolean {
        var c = 0
        for ((i, ch) in digits.reversed().withIndex()) c = VERHOEFF_D[c][VERHOEFF_P[i % 8][ch - '0']]
        return c == 0
    }

    private val VERHOEFF_D = arrayOf(
        intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), intArrayOf(1, 2, 3, 4, 0, 6, 7, 8, 9, 5),
        intArrayOf(2, 3, 4, 0, 1, 7, 8, 9, 5, 6), intArrayOf(3, 4, 0, 1, 2, 8, 9, 5, 6, 7),
        intArrayOf(4, 0, 1, 2, 3, 9, 5, 6, 7, 8), intArrayOf(5, 9, 8, 7, 6, 0, 4, 3, 2, 1),
        intArrayOf(6, 5, 9, 8, 7, 1, 0, 4, 3, 2), intArrayOf(7, 6, 5, 9, 8, 2, 1, 0, 4, 3),
        intArrayOf(8, 7, 6, 5, 9, 3, 2, 1, 0, 4), intArrayOf(9, 8, 7, 6, 5, 4, 3, 2, 1, 0),
    )
    private val VERHOEFF_P = arrayOf(
        intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), intArrayOf(1, 5, 7, 6, 2, 8, 3, 0, 9, 4),
        intArrayOf(5, 8, 0, 3, 7, 9, 6, 1, 4, 2), intArrayOf(8, 9, 1, 6, 0, 4, 3, 5, 2, 7),
        intArrayOf(9, 4, 5, 3, 1, 2, 6, 8, 7, 0), intArrayOf(4, 2, 8, 6, 5, 7, 3, 9, 0, 1),
        intArrayOf(2, 7, 9, 3, 8, 0, 6, 4, 1, 5), intArrayOf(7, 0, 4, 6, 9, 1, 3, 2, 5, 8),
    )

    /** How far before a number its word may be: "account number: 1234…", "a/c no. ending". */
    private const val NEAR_CHARS = 40

    /** 9 or more digits, in groups split by single spaces or dashes. */
    private val DIGIT_RUN = Regex("(?<![\\dA-Za-z])\\d(?:[ -]?\\d){8,18}(?![\\dA-Za-z])")

    private val ACCOUNT_WORDS = Regex("\\b(account|acct|acc|a/c|ac no|bank)\\b", RegexOption.IGNORE_CASE)
    private val PASSPORT_WORDS = Regex("\\bpassport\\b", RegexOption.IGNORE_CASE)

    /** Five letters (the fourth says what kind of holder), four digits, a letter. */
    private val PAN = Regex("\\b[A-Z]{3}[ABCFGHJLPT][A-Z]\\d{4}[A-Z]\\b", RegexOption.IGNORE_CASE)

    /** India's: state, RTO, year issued, number ("KL11 20110012345"). */
    private val DRIVING_LICENCE = Regex("\\b[A-Z]{2}[ -]?\\d{2}[ -]?(?:19|20)\\d{2}[ -]?\\d{7}\\b", RegexOption.IGNORE_CASE)

    /** India's: a letter and seven digits. */
    private val PASSPORT = Regex("\\b[A-PR-WY][1-9]\\d ?\\d{4}[1-9]\\b", RegexOption.IGNORE_CASE)

    /** Not "pin code": in India that's the postal code. */
    private const val SECRET_WORDS = "(pin(?![ \\t]*code)|mpin|upi pin|atm pin|cvv|cvc|otp|password|passcode|passwd|pwd)"

    /** The word, a few words at most ("for the wifi"), then is / : / = / -, then the value. */
    private val SECRET_SAID = Regex(
        "\\b$SECRET_WORDS\\b(?:[ \\t]+[\\p{L}\\d'’.]+){0,4}?[ \\t]*(?:\\bis\\b|:|=|-)[ \\t]*([^\\s,;]+)",
        RegexOption.IGNORE_CASE,
    )

    /** The word, maybe "number" or "code", then the value straight after: "cvv 123", "pin no 4417". */
    private val SECRET_BARE = Regex(
        "\\b$SECRET_WORDS\\b(?:[ \\t]+(?:number|no\\.?|code))?[ \\t]+([^\\s,;]+)",
        RegexOption.IGNORE_CASE,
    )
    private const val KEYWORD_GROUP = 1
    private const val VALUE_GROUP = 2

    /** Secrets that are words, not only digits. */
    private val WORD_SECRETS = setOf("password", "passcode", "passwd", "pwd")

    /** What follows "password" when no password does ("the password is weak", "a password for"). */
    private val NOT_SECRETS = setOf(
        "is", "was", "for", "to", "the", "a", "an", "and", "or", "of", "it", "this", "that", "my", "your", "you",
        "weak", "strong", "wrong", "correct", "incorrect", "changed", "reset", "please", "again", "here", "there",
        "hidden", "••••",
    )

    /** Words ending a reply that each have a digit in them: possibly a number still being written. */
    private val TRAILING_NUMBER = Regex("(?:\\S*\\d\\S*[ -]?)+$")
}
