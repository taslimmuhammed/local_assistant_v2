package com.local.assistant.llm

import com.local.assistant.data.db.AttachmentKind
import com.local.assistant.data.db.MessageEntity
import kotlin.math.ceil

/** Outcome of fitting a chat's history into the context window. */
data class TrimResult(
    val messages: List<MessageEntity>,
    val droppedCount: Int,
) {
    val trimmed: Boolean get() = droppedCount > 0
}

/**
 * Fits a chat's stored history into the context window by dropping the oldest turns.
 *
 * Pure functions over the stored rows, with no runtime dependency, because the alternative —
 * discovering the policy only by overflowing a real conversation — is slow and destroys the chat
 * being tested.
 *
 * Token counts here are estimates: the runtime does not expose its tokenizer, and
 * `Conversation.getTokenCount()` only reports a count *after* a conversation exists. Callers
 * should treat the estimate as a first cut and reconcile against the real count.
 */
object ContextWindow {

    /**
     * Deliberately pessimistic. English prose runs nearer 4 characters per token, but code and
     * markup tokenize far more densely, and under-estimating is what overflows the window.
     */
    const val CHARS_PER_TOKEN = 3.0

    /** Per-message overhead for the chat template's role and turn markers. */
    const val TURN_OVERHEAD_TOKENS = 8

    /** Rough cost of a second of audio. An estimate; tune once the model's real rate is known. */
    const val AUDIO_TOKENS_PER_SECOND = 32

    fun estimateTextTokens(text: String): Int =
        ceil(text.length / CHARS_PER_TOKEN).toInt()

    fun estimateTokens(message: MessageEntity, visionTokensPerImage: Int): Int {
        val attachment = when (message.attachmentKind) {
            AttachmentKind.IMAGE -> visionTokensPerImage
            AttachmentKind.AUDIO -> {
                val seconds = ceil((message.attachmentDurationMs ?: 0L) / 1000.0).toInt()
                seconds * AUDIO_TOKENS_PER_SECOND
            }
            null -> 0
        }
        return estimateTextTokens(message.text) + attachment + TURN_OVERHEAD_TOKENS
    }

    /**
     * Drops whole messages from the oldest end until the estimate fits [budgetTokens].
     *
     * The newest message is always kept, even when it alone exceeds the budget: returning an
     * empty history would send the model a turn with no content at all, and the runtime's own
     * "input token ids are too long" error is a better failure than silently sending nothing.
     */
    fun trimToBudget(
        history: List<MessageEntity>,
        budgetTokens: Int,
        visionTokensPerImage: Int,
    ): TrimResult {
        if (history.isEmpty()) return TrimResult(history, 0)

        val costs = history.map { estimateTokens(it, visionTokensPerImage) }
        var total = costs.sum()
        if (total <= budgetTokens) return TrimResult(history, 0)

        var firstKept = 0
        // Stop before the last message so at least one turn always survives.
        while (firstKept < history.lastIndex && total > budgetTokens) {
            total -= costs[firstKept]
            firstKept++
        }

        return TrimResult(history.subList(firstKept, history.size), firstKept)
    }

    /**
     * Room the history may occupy: the whole window less what the reply needs and an allowance
     * for the system prompt, which is prepended outside the history.
     */
    fun budgetFor(contextTokens: Int, maxOutputTokens: Int, systemPrompt: String): Int {
        val reserved = maxOutputTokens +
            estimateTextTokens(systemPrompt) +
            TURN_OVERHEAD_TOKENS
        return (contextTokens - reserved).coerceAtLeast(MIN_HISTORY_BUDGET)
    }

    private const val MIN_HISTORY_BUDGET = 256
}
