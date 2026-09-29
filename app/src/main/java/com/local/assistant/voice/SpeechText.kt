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
 * the sentences completed since the last one; [finish] returns whatever is left.
 */
class SpeechChunker {

    /** How much of the raw reply has been handed out. */
    private var spokenUpTo = 0

    fun push(reply: String): List<String> {
        val end = lastBoundary(reply)
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
        return if (spoken.isBlank() || spoken.none { it.isLetterOrDigit() }) emptyList() else listOf(spoken)
    }

    /**
     * Where the last complete sentence ends: after a full stop, question or exclamation mark
     * followed by a space, or at a line break. Never inside a code block that is still open,
     * which would be spoken as a fragment.
     */
    private fun lastBoundary(reply: String): Int {
        val fences = FENCE.findAll(reply).count()
        val limit = if (fences % 2 == 1) reply.lastIndexOf("```") else reply.length
        var i = limit - 1
        while (i > spokenUpTo) {
            val c = reply[i]
            if (c == '\n') return i + 1
            if (c.isWhitespace() && reply[i - 1] in ".!?") return i
            i--
        }
        return spokenUpTo
    }

    private companion object {
        val FENCE = Regex("```")
    }
}
