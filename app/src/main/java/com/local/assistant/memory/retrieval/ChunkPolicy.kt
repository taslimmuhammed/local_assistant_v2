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
