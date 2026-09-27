package com.local.assistant.memory.tools

import java.util.Locale

/**
 * Puts back the digits the model garbled when it read a number out to the user.
 *
 * Past token position 2,048 the GPU backend mis-copies digits (see [NumberGrounding]), and every
 * reply is past it: a saved Wi-Fi card's "Admin PIN: 4417" came back as "44417", its
 * "1800-209-4455" as "18000-2095555", while the words around them were right. The app has the
 * text the model was reading from — the prompt's memory, the envelope with its recalled facts,
 * snippets and saved image, and this turn's tool results — so a number in the reply that appears
 * in none of it, but is one of its numbers with a digit or two wrong, *and* sits by the same label
 * ("admin PIN", "support"), is taken from there instead.
 *
 * Left alone: numbers the sources contain (in any grouping, or a 24-hour time as 12-hour), numbers
 * with no labelled source number close to them — sums, conversions, general knowledge — and a
 * number the reply gives before saying what it is.
 */
class ReplyGrounding(sources: List<String> = emptyList()) {

    private class Candidate(val digits: String, val text: String, val labels: List<String>)

    private val present = mutableSetOf<String>()
    private val candidates = mutableListOf<Candidate>()

    init {
        sources.forEach(::add)
    }

    /** More text the model has read this turn: a tool result. */
    fun add(source: String) {
        for (match in NUMBER.findAll(source)) {
            val groups = RUN.findAll(match.value).toList()
            val labels = (labelsBefore(source, match.range.first) + labelsAfter(source, match.range.last + 1)).distinct()
            for (i in groups.indices) for (j in i until groups.size) {
                val digits = groups.subList(i, j + 1).joinToString("") { it.value }
                present += digits
                if (labels.isNotEmpty() && digits.length >= MIN_SOURCE_DIGITS) {
                    candidates += Candidate(digits, match.value.substring(groups[i].range.first, groups[j].range.last + 1), labels)
                }
            }
            TIME.matchEntire(match.value)?.let { time ->
                val hour = time.groupValues[1].toInt()
                val minutes = time.groupValues[2]
                when {
                    hour in 13..23 -> present += "${hour - 12}$minutes"
                    hour == 0 -> present += "12$minutes"
                }
            }
        }
    }

    /**
     * The source's number for [number], or null to keep it. [before] is the reply up to it: the
     * words since the last number or sentence are what name it.
     */
    fun fix(number: String, before: String): String? {
        val digits = number.filter(Char::isDigit)
        if (digits.length < MIN_REPLY_DIGITS || digits in present) return null
        val named = words(clause(before.takeLast(REPLY_WINDOW), fromEnd = true))
        if (named.isEmpty()) return null
        val scored = candidates
            .filter { abs(it.digits.length - digits.length) <= MAX_LENGTH_CHANGE }
            .mapNotNull { candidate ->
                val anchors = candidate.labels.count { label -> named.any { sameWord(label, it) } }
                val likeness = commonSubsequence(digits, candidate.digits).toDouble() / maxOf(digits.length, candidate.digits.length)
                if (anchors == 0 || likeness < MIN_LIKENESS) null else Triple(candidate, anchors, likeness)
            }
            .sortedWith(compareByDescending<Triple<Candidate, Int, Double>> { it.second }.thenByDescending { it.third })
        val best = scored.firstOrNull() ?: return null
        val tied = scored.drop(1).any { it.second == best.second && it.third == best.third && it.first.digits != best.first.digits }
        return if (tied) null else best.first.text
    }

    // ---- Labels ----

    /** The last few words before a source number, back to the previous number or a boundary. */
    private fun labelsBefore(source: String, start: Int): List<String> =
        words(clause(source.substring(maxOf(0, start - SOURCE_WINDOW), start), fromEnd = true)).takeLast(LABELS_BEFORE)

    /**
     * A word or two after it ("11:45 · Dentist"), up to the next number, boundary or the next
     * item's own label ("4417 Support: 1800…" gives nothing).
     */
    private fun labelsAfter(source: String, end: Int): List<String> {
        val after = clause(source.substring(end, minOf(source.length, end + SOURCE_WINDOW)), fromEnd = false)
        val nextLabel = NEXT_LABEL.find(after)?.range?.first ?: after.length
        return words(after.substring(0, nextLabel)).take(LABELS_AFTER)
    }

    /** The part of [text] next to the number: after its last (or before its first) number or boundary. */
    private fun clause(text: String, fromEnd: Boolean): String {
        val cuts = BOUNDARY.findAll(text).map { if (fromEnd) it.range.last + 1 else it.range.first }.toList()
        return if (fromEnd) text.substring(cuts.maxOrNull() ?: 0) else text.substring(0, cuts.minOrNull() ?: text.length)
    }

    private fun words(text: String): List<String> =
        WORD.findAll(text).map { it.value.lowercase(Locale.ROOT) }.filter { it !in STOP }.toList()

    private fun sameWord(a: String, b: String): Boolean =
        a == b || a + "s" == b || b + "s" == a || (a.length >= STEM && b.length >= STEM && a.take(STEM) == b.take(STEM))

    companion object {
        /** [text] with its numbers checked against [sources] in one go, as a reply is while it streams. */
        fun correct(text: String, sources: List<String>): String =
            GroundedReply(ReplyGrounding(sources)).let { it.push(text) + it.flush() }

        /**
         * A number as written: digit groups joined by one of . , : / - or a single space before a
         * group of two or more ("98450 12345", "1800-209-4455", "11:45", "2,340.50").
         */
        val NUMBER = Regex("(?<!\\d)\\d+(?:[.,:/-]\\d+| (?=\\d\\d)\\d+)*")

        private val RUN = Regex("\\d+")
        private val TIME = Regex("(\\d{1,2}):(\\d{2})")
        private val WORD = Regex("[A-Za-z][A-Za-z']{2,}")
        private val NEXT_LABEL = Regex("[A-Za-z][\\w-]*:")

        /** Where a label stops: a number, a bracket or quote, a line or a sentence's end. */
        private val BOUNDARY = Regex("\\d|[\\[\\]{}()\"“”;|\\n]|[.!?](?=\\s)")

        private const val MIN_SOURCE_DIGITS = 3
        private const val MIN_REPLY_DIGITS = 2
        private const val MAX_LENGTH_CHANGE = 2

        /** Digits in common, in order, over the longer number: 4417 in 44417 is 0.8. */
        private const val MIN_LIKENESS = 0.6
        private const val SOURCE_WINDOW = 60
        private const val REPLY_WINDOW = 120
        private const val LABELS_BEFORE = 3
        private const val LABELS_AFTER = 2
        private const val STEM = 5

        /** Words that name nothing: grammar, and what the app's own prompt lines add around memory. */
        private val STOP = setOf(
            "the", "and", "for", "with", "you", "your", "yours", "are", "was", "were", "this", "that", "these", "those",
            "from", "has", "have", "had", "its", "it's", "not", "but", "all", "any", "can", "our", "out", "per", "via",
            "there", "here", "what", "which", "who", "whom", "whose", "will", "would", "shall", "should", "could",
            "been", "being", "into", "onto", "than", "then", "them", "they", "their", "his", "her", "she", "him",
            "also", "just", "only", "very", "about", "some", "such", "more", "most", "other", "same", "too", "now",
            "let", "know", "sure", "okay", "yes", "here's", "that's", "it’s", "i'm", "i've",
            "saved", "image", "attached", "message", "memory", "look", "again", "earlier", "chat", "time",
        )

        private fun abs(n: Int) = if (n < 0) -n else n

        private fun commonSubsequence(a: String, b: String): Int {
            val table = Array(a.length + 1) { IntArray(b.length + 1) }
            for (i in 1..a.length) for (j in 1..b.length) {
                table[i][j] = if (a[i - 1] == b[j - 1]) table[i - 1][j - 1] + 1 else maxOf(table[i - 1][j], table[i][j - 1])
            }
            return table[a.length][b.length]
        }
    }
}

/**
 * A reply as it streams, with [ReplyGrounding] applied. A number is held back until it is
 * complete — the next character can't continue it — so a wrong one is never shown and then
 * swapped; everything else passes straight through.
 */
class GroundedReply(
    private val grounding: ReplyGrounding,
    /** A number was put right: as the model wrote it, and as the sources have it. */
    private val onFix: (wrote: String, source: String) -> Unit = { _, _ -> },
) {

    private val shown = StringBuilder()
    private var held = ""

    /** What of [delta] can be shown now. */
    fun push(delta: String): String {
        val text = held + delta
        val cut = UNFINISHED.find(text)?.range?.first ?: text.length
        held = text.substring(cut)
        return release(text.substring(0, cut))
    }

    /** The reply stops here, for now or for good: whatever was held back. */
    fun flush(): String {
        val rest = held
        held = ""
        return release(rest)
    }

    private fun release(chunk: String): String {
        if (chunk.isEmpty()) return chunk
        val out = StringBuilder()
        var last = 0
        for (match in ReplyGrounding.NUMBER.findAll(chunk)) {
            out.append(chunk, last, match.range.first)
            val before = shown.takeLast(CONTEXT).toString() + out
            val fixed = grounding.fix(match.value, before)
            if (fixed != null) onFix(match.value, fixed)
            out.append(fixed ?: match.value)
            last = match.range.last + 1
        }
        out.append(chunk, last, chunk.length)
        shown.append(out)
        return out.toString()
    }

    private companion object {
        /** A number the next characters could still extend: digits, maybe a separator after them. */
        val UNFINISHED = Regex("(?<!\\d)\\d+(?:[.,:/\\s-]\\d+)*[.,:/\\s-]?$")
        const val CONTEXT = 200
    }
}
