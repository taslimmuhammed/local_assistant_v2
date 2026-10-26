package com.local.assistant.memory.retrieval

import com.local.assistant.data.db.AttachmentKind

/**
 * What goes into the archive for one exchange.
 *
 * One chunk per exchange: the user's message and the start of the reply. Embedding the pair
 * halves the work of embedding every message, and keeping most of the reply out stops the
 * assistant's own phrasing being recalled later as if it were something the user said.
 */
object ChunkPolicy {

    /** How much of the reply goes in. */
    const val REPLY_CHARS = 300

    /** Marks a chunk that is deliberately never embedded (in place of a model id). */
    const val NOT_EMBEDDED = "none"

    /** Prefix of the marker for a chunk the model could not embed: "!" + model id. */
    const val FAILED_PREFIX = "!"

    /**
     * Marks a chunk the user made the assistant forget. The row stays, emptied, so the backfill
     * does not archive the message again; its text and vector are gone.
     */
    const val FORGOTTEN = "forgotten"

    /**
     * The chunk text for an exchange, or null if there is nothing to archive: voice notes are
     * kept for playback but never remembered, and a picture with no words has nothing to search.
     */
    fun text(userText: String, attachment: AttachmentKind?, reply: String?): String? {
        if (attachment == AttachmentKind.AUDIO) return null
        val question = userText.trim()
        if (question.isEmpty()) return null
        val answer = reply?.trim()?.takeIf { it.isNotEmpty() }?.let(::excerpt)
        return if (answer == null) "User: $question" else "User: $question\nAssistant: $answer"
    }

    /** Marks an exchange deliberately left out: a sum, or working the phone. Empty, never embedded. */
    const val SKIPPED = "skipped"

    /**
     * Tools whose turns hold nothing about the user worth recalling. find_photos too: its answer
     * is a count that is out of date as soon as photos are added, and recalled beside a new
     * photo request the model repeated the old answer ("I found 0 photos in Kashmir") instead of
     * searching, so no photos were shown (2026-10-26, 2 of 2 turns in a fresh chat).
     */
    val UTILITY_TOOLS = setOf("calculate", "set_timer", "open_app", "phone_setting", "find_photos")

    /**
     * Whether an exchange is left out of the archive: all it did was work something out or work
     * the phone. Such turns hold nothing about the user, and they do harm when recalled — every
     * sum looks like every other sum, so old ones were recalled beside a new one, and the model
     * mixed their digits into it ("256 × 4" came back as "2*5*4"). A sum, or a request to see
     * photos, answered without the tool counts too; any other tool makes it a turn worth keeping.
     */
    fun isUtility(userText: String, tools: Collection<String>): Boolean =
        tools.all { it in UTILITY_TOOLS } && (tools.isNotEmpty() || looksLikeArithmetic(userText) || asksForPhotos(userText))

    /** "Show me photos from Munnar", "get my beach videos": asking to see them, not telling about them. */
    private fun asksForPhotos(text: String): Boolean {
        val words = QueryText.words(text.lowercase())
        return words.any { it in PHOTO_WORDS } && words.any { it in SEE_WORDS }
    }

    private val PHOTO_WORDS = setOf("photo", "photos", "pic", "pics", "picture", "pictures", "video", "videos", "selfie", "selfies")
    private val SEE_WORDS = setOf("show", "find", "get", "fetch", "pull", "see", "give", "search")

    private fun looksLikeArithmetic(text: String): Boolean {
        val lower = text.lowercase()
        return lower.any(Char::isDigit) && QueryText.words(lower).size <= ARITHMETIC_MAX_WORDS && ARITHMETIC.containsMatchIn(lower)
    }

    private const val ARITHMETIC_MAX_WORDS = 12

    /** Operators and their words. Not "-", "/" or "%": dates, times and ranges use them too. */
    private val ARITHMETIC = Regex("[+*×÷^=]|\\b(plus|minus|times|multiplied|divided|square root|sqrt|squared|cubed)\\b")

    /** Trivial exchanges are stored and keyword-searchable, but not embedded. */
    fun shouldEmbed(userText: String): Boolean = !QueryText.isTrivial(userText)

    /** The first [REPLY_CHARS] characters, cut at a word. */
    fun excerpt(reply: String): String {
        val flat = reply.replace(Regex("\\s+"), " ").trim()
        if (flat.length <= REPLY_CHARS) return flat
        val cut = flat.take(REPLY_CHARS)
        return cut.substringBeforeLast(' ', cut).trimEnd(',', ';', ':', ' ') + "…"
    }
}
