package com.local.assistant.memory.retrieval

import java.util.Locale

/**
 * How a message is read for recall: is it worth searching for at all, which words to search
 * with, and which of those words are rare enough that a keyword hit alone means something.
 *
 * Deliberately plain rules, shared by the per-turn retrieval gate and the archive's decision
 * about which exchanges are worth embedding.
 */
object QueryText {

    /** A word: letters (with their combining marks, for Indic scripts) and digits. */
    private val WORD = Regex("[\\p{L}\\p{M}\\p{N}]+(?:['’][\\p{L}]+)?")

    fun words(text: String): List<String> = WORD.findAll(text).map { it.value }.toList()

    /**
     * Gate 0: nothing to recall for "ok", "thanks!", "👍" or anything under three words. Such
     * messages are still stored and keyword-searchable; they are just not searched *for*, and not
     * embedded.
     */
    fun isTrivial(text: String): Boolean {
        val words = words(text).map { it.lowercase(Locale.ROOT) }
        if (words.size < MIN_WORDS) return true
        return words.all { it in ACKNOWLEDGEMENTS }
    }

    /** Lower-cased words worth searching for, in order, without repeats. */
    fun terms(text: String): List<String> =
        words(text).map { it.lowercase(Locale.ROOT).replace('’', '\'').substringBefore('\'') }
            .filter { it.length >= 2 && it !in STOP_WORDS && it !in TITLES }
            .distinct()

    /**
     * Words that name something specific: anything with a digit ("2024", "B12", a phone number),
     * an acronym ("CA", "GST"), or a capitalised word that does not merely start a sentence
     * ("Iyer", "Jayanagar"). A keyword hit on one of these is evidence on its own; a hit on
     * "meeting" is not.
     */
    fun rareTerms(text: String): Set<String> {
        val rare = linkedSetOf<String>()
        var sentenceStart = true
        var index = 0
        var previous = ""
        for (match in WORD.findAll(text)) {
            val between = text.substring(index, match.range.first)
            // "Mr. Iyer": the full stop after a title does not end a sentence.
            val ended = between.any { it in ".!?\n" } && !(previous in TITLES && between.trim() == ".")
            if (index == 0 || ended) sentenceStart = true
            index = match.range.last + 1
            val word = match.value
            val lower = word.lowercase(Locale.ROOT).substringBefore('\'').substringBefore('’')
            previous = lower
            val first = sentenceStart
            sentenceStart = false
            if (lower.length < 2 || lower in STOP_WORDS || lower in TITLES) continue
            val hasDigit = word.any { it.isDigit() }
            val letters = word.filter { it.isLetter() }
            val acronym = letters.length >= 2 && letters.all { it.isUpperCase() }
            val capitalised = word.first().isUpperCase() && !first && lower !in CAPITALISED_COMMON
            if (hasDigit || acronym || capitalised) rare += lower
        }
        return rare
    }

    /**
     * [terms] as an FTS4 query: any of them, longer ones also as prefixes ("dentist" finds
     * "dentists"). Short words match whole only, so "ca" does not find every "call". Null when
     * there is nothing to search for.
     */
    fun ftsMatch(terms: List<String>): String? =
        // Terms are letters, marks and digits only (see [WORD]), so nothing in them is FTS syntax.
        terms.takeIf { it.isNotEmpty() }
            ?.joinToString(" OR ") { if (it.length >= PREFIX_FROM) "$it*" else it }

    private const val MIN_WORDS = 3
    private const val PREFIX_FROM = 4

    /** Titles before a name: capitalised, but the name after them is what is specific. */
    private val TITLES = setOf("mr", "mrs", "ms", "dr", "prof", "st", "sr", "jr", "smt", "shri", "sri")

    /** Capitalised by convention, not because they name anything specific to this user. */
    private val CAPITALISED_COMMON = setOf(
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
        "january", "february", "march", "april", "june", "july", "august", "september", "october",
        "november", "december", "god", "english", "hindi",
    )

    /** Replies that close a turn rather than open a topic, in English and Hinglish. */
    private val ACKNOWLEDGEMENTS = setOf(
        "ok", "okay", "okk", "okie", "k", "kk", "thanks", "thank", "thx", "ty", "you", "so", "much", "a", "lot",
        "cool", "great", "nice", "good", "fine", "sure", "yes", "yeah", "yep", "yup", "no", "nope", "nah",
        "hmm", "hm", "alright", "right", "got", "it", "lol", "haha", "bye", "hi", "hello", "hey", "done",
        "perfect", "awesome", "wow", "noted", "understood", "bro", "man", "dude", "very", "that's", "thats",
        "that", "is", "sounds", "fair", "enough", "welcome", "goodnight", "night", "morning", "see", "ya",
        "theek", "thik", "hai", "haan", "han", "ha", "ji", "accha", "acha", "achha", "shukriya", "dhanyavad",
        "bas", "sahi", "badhiya", "chalo", "chal", "thanku", "tq",
    )

    /**
     * Words that carry no topic. English plus the Hinglish particles people mix in, and the labels
     * every archived chunk has ("User", "Assistant").
     */
    private val STOP_WORDS = setOf(
        "the", "a", "an", "and", "or", "but", "if", "then", "so", "to", "of", "in", "on", "at", "by", "for",
        "with", "about", "from", "into", "is", "am", "are", "was", "were", "be", "been", "being", "do", "does",
        "did", "have", "has", "had", "i", "me", "my", "mine", "you", "your", "yours", "we", "our", "us",
        "he", "him", "his", "she", "her", "they", "them", "their", "it", "its", "this", "that", "these",
        "those", "what", "which", "who", "whom", "when", "where", "why", "how", "can", "could", "would",
        "should", "will", "shall", "may", "might", "must", "not", "no", "yes", "please", "just", "also",
        "any", "some", "all", "there", "here", "tell", "said", "say", "know", "remember", "again",
        "ok", "okay", "thanks", "hi", "hello", "hey", "like", "get", "got", "want", "need", "let", "let's",
        "im", "i'm", "dont", "don't", "one", "up", "out", "as", "than", "too", "very", "more", "much",
        "user", "assistant",
        "ka", "ki", "ke", "ko", "se", "hai", "hain", "tha", "thi", "mera", "meri", "mere", "mujhe",
        "kya", "kab", "kaun", "kahan", "aur", "bhi", "nahi", "na", "ho", "hoga", "karna", "kar", "wala",
        "wali", "ek", "yeh", "ye", "woh", "wo", "tum", "aap",
    )
}
