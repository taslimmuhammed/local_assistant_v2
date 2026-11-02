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

    /**
     * A command to act on rather than a question: an alarm, a timer, a reminder, a call, a message,
     * an app, a setting, a list. Nothing is recalled for one (the user, 30 Oct 2026: recall only
     * slows commands down). Only what is plainly put as a command counts: a question taken for a
     * command would lose its memory, while a command taken for a question only runs as before.
     * So "remind me what I said about Goa" is a question, and "send me my PAN number" is too.
     */
    fun isCommand(text: String): Boolean {
        var said = text.trim().lowercase(Locale.ROOT).replace('’', '\'')
        // "Hey Luna, can you please…" down to what is asked.
        while (true) {
            val shorter = said.replace(LEAD_IN, "").trimStart(' ', ',', '.', '!')
            if (shorter == said) break
            said = shorter
        }
        return COMMANDS.any { it.containsMatchIn(said) }
    }

    /** Wake words, politeness and "can you" before a request. */
    private val LEAD_IN = Regex(
        "^(hey luna|luna|ok|okay|please|pls|plz|kindly|just|now|quickly|" +
            "(can|could|would|will) (you|u)( please)?|i (want|need) you to)\\b",
    )

    private const val THINGS = "alarms?|timers?|reminders?|tasks?|to-?dos?|events?|meetings?|appointments?"

    /** At the start of what is asked, after [LEAD_IN]. */
    private val COMMANDS = listOf(
        // Not "remind me what…": that asks to be told something.
        Regex("^remind me\\b(?!\\s+(what|who|when|where|why|how|which|whether|if|of|about)\\b)"),
        Regex("^wake me\\b"),
        Regex("^(set|start|create|add|make|schedule|put|book)\\b.{0,40}\\b($THINGS)\\b"),
        Regex("^(cancel|delete|remove|clear|dismiss|snooze|stop|mark|complete|finish|reschedule|postpone|move|change|update|edit|turn off)\\b.{0,40}\\b($THINGS)\\b"),
        Regex("^(add|put) .{1,60}\\b(to|on|in|into) (my |the )?(\\w+ )?(list|tasks|to-?do list|calendar)\\b"),
        Regex("^(call|ring|dial|video call|facetime)\\s+\\S"),
        Regex("^(text|message|msg|sms|whatsapp|email|e-mail|mail)\\s+\\S"),
        Regex("^send\\b(?!\\s+me\\b)"),
        Regex("^(open|launch)\\s+\\S"),
        Regex("^(turn|switch) (on|off)\\b|^(turn|switch) (the |my )?\\S+( \\S+)? (on|off)\\b|^(enable|disable|mute|unmute)\\b"),
        Regex("^(increase|decrease|raise|lower|reduce|set|turn up|turn down) (the )?(volume|brightness)\\b|^(volume|brightness) (up|down)\\b"),
        Regex("^(play|pause|resume|skip)\\b|^(next|previous) (song|track)\\b|^stop (the )?(music|song|playback)\\b"),
        Regex("^(navigate|directions|take me|drive me) to\\b"),
        Regex("^(remember|note|save) (that|this|my)\\b|^forget\\b"),
    )

    /** "Show me photos from Munnar", "get my beach videos": asking to see them, not telling about them. */
    fun asksForPhotos(text: String): Boolean {
        val words = words(text.lowercase(Locale.ROOT))
        return words.any { it in PHOTO_WORDS } && words.any { it in SEE_WORDS }
    }

    private val PHOTO_WORDS = setOf("photo", "photos", "pic", "pics", "picture", "pictures", "video", "videos", "selfie", "selfies")
    private val SEE_WORDS = setOf("show", "find", "get", "fetch", "pull", "see", "give", "search")

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
