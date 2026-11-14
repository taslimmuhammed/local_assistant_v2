package com.local.assistant.memory.tools

/**
 * Spots token soup: a reply that is not words at all ("840tsetUauthorx205619i aponitimiCrap.M
 * WMWWCOMXIai").
 *
 * On 14 Nov 2026 one app process gave nothing else for 12 minutes, in every chat, typed or spoken,
 * until it was restarted. The instructions at the start of the model's memory still read: one
 * reply came round to "no info has been provided in the prompt other than instructions". What
 * came after about token 4,100 did not. The photo model closed on the GPU beside an open
 * conversation did it ([com.local.assistant.gallery.GalleryEmbedder.leaveGpu]); this stays to
 * catch any other cause as it streams.
 *
 * Judged by the first ten words of the reply (twenty if two of those are odd), from words no one
 * writes: a capital inside a long word, letters run into digits, two scripts in one word, symbols
 * between letters, a run of 21 Latin letters. Code, links, emails, file names and IDs
 * (MH12AB1234) don't count. Of the replies stored on the phone that day, all 8 soup ones were
 * caught by their sixth to eighth word and none of the 67 real ones was; nor was any of the 294
 * paragraphs of this app's docs.
 */
object GarbledText {

    enum class Verdict { FINE, SOUP }

    /**
     * What the opening of [text] is, or null while there is too little of it to say. [final]: the
     * text is all there is; otherwise its last word may still be coming.
     */
    fun verdict(text: CharSequence, final: Boolean): Verdict? {
        // The same broken state wrote its reasoning out instead, thinking being off: "[Thinking
        // Process: 1. **Analyze the Request:** … <channel|>The Eiffel Tower was built …" (14 Nov 2026).
        if (THINKING_OPENING.containsMatchIn(text) || "<channel|>" in text || "<|channel>" in text) return Verdict.SOUP
        // Code is judged by other rules than prose; a reply that opens with it is left alone.
        if ("```" in text) return Verdict.FINE
        val tokens = text.split(SPACE).filter { it.isNotEmpty() }
        val complete = if (final || text.isEmpty() || text.last().isWhitespace()) tokens else tokens.dropLast(1)
        val words = complete.asSequence().mapNotNull(::kind).take(LONGEST_WORDS).toList()
        // Word by word, so a reply is judged the same whole or as it streams.
        var odd = 0
        for ((index, isOdd) in words.withIndex()) {
            if (isOdd) odd++
            val seen = index + 1
            if (seen >= MIN_WORDS && odd >= MIN_ODD && odd >= seen * SOUP_SHARE) return Verdict.SOUP
            // Ten words with one odd one at most are words; with two, it looks on.
            if (seen >= SETTLED_WORDS && odd <= 1) return Verdict.FINE
        }
        return when {
            words.size >= LONGEST_WORDS -> Verdict.FINE
            !final -> null
            // A short reply: half of it odd, and two words at least.
            words.size >= 2 && odd >= 2 && odd * 2 >= words.size -> Verdict.SOUP
            else -> Verdict.FINE
        }
    }

    /** True for a word no one writes, false for a word; null for one that isn't judged. */
    internal fun kind(token: String): Boolean? {
        if ('`' in token) return null
        val word = token.trim { it in STRIP }
        if (word.isEmpty() || word.none { it.isLetter() }) return false
        if ("://" in word || '@' in word || word.startsWith("www.", ignoreCase = true) || FILE_NAME.matches(word)) return null
        if (ID.matches(word)) return false
        return isOdd(word)
    }

    private fun isOdd(word: String): Boolean {
        if (LETTER_DIGIT.containsMatchIn(word) && word.any { it.isLowerCase() } && !UNIT.matches(word) && !PREFIXED_NUMBER.matches(word)) return true
        if (scripts(word) > 1) return true
        if (SYMBOL_BETWEEN.containsMatchIn(word)) return true
        if (LONG_LATIN.containsMatchIn(word)) return true
        val switches = INNER_CAPITAL.findAll(word).count()
        return switches >= 3 || (switches >= 1 && word.count { it.isLetter() } >= CAPITAL_IN_LONG)
    }

    /** How many writing systems the word's letters come from (Latin, Devanagari, Hangul …). */
    private fun scripts(word: String): Int {
        val seen = HashSet<Character.UnicodeScript>()
        var i = 0
        while (i < word.length) {
            val point = word.codePointAt(i)
            if (Character.isLetter(point)) {
                val script = Character.UnicodeScript.of(point)
                if (script != Character.UnicodeScript.COMMON && script != Character.UnicodeScript.INHERITED) seen += script
            }
            i += Character.charCount(point)
        }
        return seen.size
    }

    /** Below this many words, only a reply that has ended is judged. */
    private const val MIN_WORDS = 6

    /** With this many words and one odd one at most, the reply is fine: soup starts with the first word. */
    private const val SETTLED_WORDS = 10

    /**
     * How far it looks when the first ten have two odd words: "frt68other mouse T Auto-waiti1
     * Jackie_like = 'donneisitcce ispareicuqi 00.preferencesa thank you" (14 Nov 2026).
     */
    private const val LONGEST_WORDS = 20

    private const val MIN_ODD = 3
    private const val SOUP_SHARE = 0.3

    /** Letters in a word with a capital inside it: WhatsApp and MakeMyTrip pass, aponitimiCrap doesn't. */
    private const val CAPITAL_IN_LONG = 13

    private val THINKING_OPENING = Regex("^[\\s\\[*#]*thinking process\\s*:", RegexOption.IGNORE_CASE)
    private val SPACE = Regex("\\s+")
    private val STRIP = "\"'“”‘’«»()[]{}<>*_.,!?:;…-–—".toSet()
    private val FILE_NAME = Regex("[^\\s]+\\.[A-Za-z][A-Za-z0-9]{1,3}")
    /** Codes in capitals and digits: number plates, PAN, flights, 4K, COVID-19, AT&T. */
    private val ID = Regex("[A-Z0-9][A-Z0-9/&-]*")
    private val LETTER_DIGIT = Regex("\\p{L}\\d|\\d\\p{L}")
    private val UNIT = Regex(
        "\\d+(?:[.,:/]\\d+)*(?:st|nd|rd|th|am|pm|k|m|g|x|s|h|km|kg|gb|mb|tb|ml|cm|mm|min|mins|hr|hrs|mp|mah|kw|kwh|hz|ghz|fps|yrs|l|p|v|w|cr|lakh)",
        RegexOption.IGNORE_CASE,
    )
    /** Rs500, v2, mp4. */
    private val PREFIXED_NUMBER = Regex("\\p{L}{1,3}\\.?\\d+(?:[.,]\\d+)*")
    private val SYMBOL_BETWEEN = Regex("\\p{L}[=*&|\\\\^~<>{}\\[\\]#$%:_]+\\p{L}")
    private val LONG_LATIN = Regex("[A-Za-z\\u00C0-\\u024F]{21,}")
    private val INNER_CAPITAL = Regex("\\p{Ll}\\p{Lu}")
}
