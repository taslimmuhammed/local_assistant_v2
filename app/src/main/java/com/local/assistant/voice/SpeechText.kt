package com.local.assistant.voice

/**
 * Turns a reply written for the screen into something a voice can say. The model writes the same
 * reply whether it will be read or heard — asking it for plain speech cost the overlay its tool
 * calls — and "asterisk asterisk" or a URL read out letter by letter is the fastest way to sound
 * broken.
 */
object SpeechText {

    private val FENCED_CODE = Regex("```[\\s\\S]*?(```|$)")
    private val INLINE_CODE = Regex("`([^`]*)`")
    // Every bracket escaped: Android's ICU regex is stricter than the JVM's.
    private val IMAGE = Regex("!\\[([^\\]]*)\\]\\(([^)]*)\\)")
    private val LINK = Regex("\\[([^\\]]+)\\]\\(([^)]*)\\)")
    private val URL = Regex("https?://\\S+|www\\.\\S+")
    private val HEADING = Regex("(?m)^\\s{0,3}#{1,6}\\s+")
    private val QUOTE = Regex("(?m)^\\s*>\\s?")
    private val BULLET = Regex("(?m)^\\s*[-*+•]\\s+")
    private val TABLE_RULE = Regex("(?m)^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")
    private val EMPHASIS = Regex("\\*\\*|__|\\*|~~")
    private val SPACES = Regex("[ \\t]+")
    private val BLANK_LINES = Regex("\\s*\\n\\s*")

    fun clean(markdown: String): String {
        var text = markdown
            .replace(FENCED_CODE, " ")
            .replace(INLINE_CODE, "$1")
            .replace(IMAGE, "$1")
            .replace(LINK, "$1")
            .replace(URL, "")
            .replace(TABLE_RULE, "")
            .replace(HEADING, "")
            .replace(QUOTE, "")
            .replace(BULLET, "")
            .replace(EMPHASIS, "")
            .replace('|', ',')
        text = stripSymbols(text)
        // A line break is a pause; a sentence without its full stop runs into the next one.
        return text.replace(SPACES, " ")
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ") { line -> if (line.last() in ".!?:;,") line else "$line." }
            .replace(BLANK_LINES, " ")
            .trim()
    }

    /** Emoji and other pictographs, which a voice reads out by name ("smiling face with…"). */
    private fun stripSymbols(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val type = Character.getType(cp)
            val drop = type == Character.OTHER_SYMBOL.toInt() ||
                type == Character.SURROGATE.toInt() ||
                cp == ZWJ || cp in VARIATION_SELECTORS || cp in SKIN_TONES
            if (!drop) out.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return out.toString()
    }

    private const val ZWJ = 0x200D
    private val VARIATION_SELECTORS = 0xFE00..0xFE0F
    private val SKIN_TONES = 0x1F3FB..0x1F3FF
}

/**
 * Cuts a reply into pieces to speak while it is still streaming, so the voice starts after the
 * first sentence instead of after the whole answer. Each [push] gets the reply so far and returns
 * the pieces completed since the last one; [finish] returns whatever is left.
 *
 * With [clauses], a piece can also end at a comma (or ; : —) rather than the full stop: while
 * the reply is starting, so the voice doesn't wait for a whole first sentence (the natural voice
 * needs about half a second per second of speech before it can play one), and in a long
 * sentence, so it keeps up. Short of [MIN_CLAUSE_WORDS] words a clause isn't worth a piece.
 */
class SpeechChunker(private val clauses: Boolean = true) {

    /** How much of the raw reply has been handed out. */
    private var spokenUpTo = 0

    /** Words handed out so far: under [EARLY_WORDS], the reply is still starting. */
    private var wordsOut = 0

    fun push(reply: String): List<String> {
        var end = lastBoundary(reply)
        if (clauses) end = maxOf(end, clauseBoundary(reply, end))
        if (end <= spokenUpTo) return emptyList()
        return take(reply, end)
    }

    fun finish(reply: String): List<String> {
        if (reply.length <= spokenUpTo) return emptyList()
        return take(reply, reply.length)
    }

    /** Starts speaking from the next sentence of [reply] on, as if what came before had been said. */
    fun skip(reply: String) {
        spokenUpTo = maxOf(spokenUpTo, lastBoundary(reply))
    }

    private fun take(reply: String, end: Int): List<String> {
        val piece = reply.substring(spokenUpTo, end)
        spokenUpTo = end
        val spoken = SpeechText.clean(piece)
        if (spoken.isBlank() || spoken.none { it.isLetterOrDigit() }) return emptyList()
        wordsOut += words(spoken)
        return listOf(spoken)
    }

    /**
     * Where the last complete sentence ends: after a full stop, question or exclamation mark
     * followed by a space, or at a line break. Never inside a code block that is still open,
     * which would be spoken as a fragment.
     */
    private fun lastBoundary(reply: String): Int {
        val limit = openLimit(reply)
        var i = limit - 1
        while (i > spokenUpTo) {
            val c = reply[i]
            if (c == '\n') return i + 1
            if (c.isWhitespace() && reply[i - 1] in ".!?") return i
            i--
        }
        return spokenUpTo
    }

    /**
     * Where the last clause worth speaking on its own ends, after [from]: a comma, semicolon,
     * colon or dash followed by a space ("1,000" and "8:30" have none), once there are
     * [MIN_CLAUSE_WORDS] words to say, and only while the reply is starting or the piece has
     * grown to [LONG_PIECE_WORDS]. [from] when there is none.
     */
    private fun clauseBoundary(reply: String, from: Int): Int {
        val start = maxOf(from, spokenUpTo)
        val limit = openLimit(reply)
        var best = start
        var i = start
        while (i < limit - 1) {
            val c = reply[i]
            if ((c in ",;:" || c == '—' || c == '–') && reply[i + 1].isWhitespace()) {
                val n = words(reply.substring(spokenUpTo, i + 1))
                if (n >= MIN_CLAUSE_WORDS && (wordsOut < EARLY_WORDS || n >= LONG_PIECE_WORDS)) best = i + 1
            }
            i++
        }
        return best
    }

    /** The end of what may be spoken: before a code block that is still open. */
    private fun openLimit(reply: String): Int {
        val fences = FENCE.findAll(reply).count()
        return if (fences % 2 == 1) reply.lastIndexOf("```") else reply.length
    }

    private fun words(text: String): Int = text.split(SPACE).count { word -> word.any { it.isLetterOrDigit() } }

    private companion object {
        val FENCE = Regex("```")
        val SPACE = Regex("\\s+")

        /** A clause shorter than this goes with what follows: "Sure," alone is too short to be worth it. */
        const val MIN_CLAUSE_WORDS = 2

        /** Until this many words have been handed out, the reply is starting and clauses go at once. */
        const val EARLY_WORDS = 8

        /** Later on, a sentence is cut at a clause only once the piece is this long. */
        const val LONG_PIECE_WORDS = 12
    }
}
