package com.local.assistant.memory.summary

import com.local.assistant.data.db.MessageEntity
import com.local.assistant.data.db.Role
import com.local.assistant.llm.LlmBackend
import com.local.assistant.llm.Sampling
import com.local.assistant.memory.prompt.TokenEstimator

/**
 * Writes the two kinds of summary, each with a one-shot call at low temperature:
 *
 * - **rolling**: a chat's older turns folded into "Earlier in this chat", so a long chat keeps
 *   its thread without replaying every word;
 * - **session**: one foreground period, for the next conversation to start from.
 *
 * Summaries are context only. Nothing reads facts out of them.
 */
class Summarizer(
    private val backend: LlmBackend,
    private val estimator: TokenEstimator,
) {

    /**
     * [previous] rewritten to also cover [turns]. Null if the model produced nothing usable, in
     * which case the caller keeps what it had.
     */
    suspend fun rolling(previous: String?, turns: List<MessageEntity>, capTokens: Int): String? {
        val input = SummaryPrompts.rollingInput(previous, turns, estimator, INPUT_TOKENS)
        return complete(SummaryPrompts.rollingSystem(SummaryPrompts.wordsFor(capTokens)), input, capTokens)
    }

    /** A summary of one session's messages, or null if the model produced nothing usable. */
    suspend fun session(messages: List<MessageEntity>, capTokens: Int): String? {
        val input = SummaryPrompts.sessionInput(messages, estimator, INPUT_TOKENS)
        return complete(SummaryPrompts.sessionSystem(SummaryPrompts.wordsFor(capTokens)), input, capTokens)
    }

    private suspend fun complete(system: String, input: String, capTokens: Int): String? {
        val raw = backend.complete(
            system = system,
            input = input,
            maxTokens = capTokens + OUTPUT_SLACK,
            sampling = Sampling.BACKGROUND,
        )
        return SummaryPrompts.clean(raw)
    }

    private companion object {
        /**
         * What a summary call may read. With the 8K window, the instructions and a reply of a few
         * hundred tokens, this keeps well clear of the edge.
         */
        const val INPUT_TOKENS = 3_500

        /** Room to finish a sentence past the asked-for length; the result is cut to fit anyway. */
        const val OUTPUT_SLACK = 64
    }
}

/** The prompts and transcript rendering, kept pure so they can be tested without a model. */
object SummaryPrompts {

    /** Roughly how many words fit in [tokens] for the languages this app sees. */
    fun wordsFor(tokens: Int): Int = (tokens * 0.6).toInt().coerceAtLeast(30)

    fun rollingSystem(words: Int): String = """
        You keep a running summary of one chat between a user and an assistant, so the assistant can carry on without rereading it.
        Rewrite the summary so it also covers the new part. Keep what the user asked for or told the assistant, decisions made, facts about the user and the people and things in their life, what the assistant produced (in a few words, not the content), and anything left open. Drop greetings and small talk.
        Write short notes in the third person ("The user…"), in the language the user writes in. At most $words words. Reply with the summary only.
    """.trimIndent()

    fun sessionSystem(words: Int): String = """
        Summarise this conversation between a user and an assistant, so the assistant can pick up where it left off next time.
        Cover the topics discussed, decisions made, and open loops: things the user said they would do, or wanted followed up.
        Write short notes in the third person ("The user…"), in the language the user writes in. At most $words words. Reply with the summary only.
    """.trimIndent()

    fun rollingInput(previous: String?, turns: List<MessageEntity>, estimator: TokenEstimator, maxTokens: Int): String {
        val head = "Summary so far:\n" + (previous?.trim()?.takeIf { it.isNotEmpty() } ?: "(none)") + "\n\nNew part of the chat:\n"
        return head + transcript(turns, estimator, maxTokens - estimator.estimate(head))
    }

    fun sessionInput(messages: List<MessageEntity>, estimator: TokenEstimator, maxTokens: Int): String =
        "The conversation:\n" + transcript(messages, estimator, maxTokens)

    /**
     * "User: …" / "Assistant: …" lines, newest kept when it all does not fit. Each message is
     * clipped first: a long reply (a page of code, say) matters to a summary by what it was, not
     * by every line of it.
     */
    fun transcript(messages: List<MessageEntity>, estimator: TokenEstimator, maxTokens: Int): String {
        val lines = messages.filter { it.role != Role.TOOL && it.text.isNotBlank() }.map { message ->
            val limit = if (message.role == Role.USER) USER_CHARS else ASSISTANT_CHARS
            val text = message.text.trim().replace(Regex("\\s*\\n\\s*"), " ").let {
                if (it.length > limit) it.take(limit).substringBeforeLast(' ') + " …" else it
            }
            (if (message.role == Role.USER) "User: " else "Assistant: ") + text
        }
        val kept = ArrayDeque<String>()
        var tokens = 0
        for (line in lines.asReversed()) {
            val cost = estimator.estimate(line) + 1
            if (tokens + cost > maxTokens && kept.isNotEmpty()) break
            kept.addFirst(line)
            tokens += cost
        }
        val omitted = lines.size - kept.size
        return (if (omitted > 0) listOf("(… $omitted earlier messages omitted)") else emptyList<String>())
            .plus(kept)
            .joinToString("\n")
    }

    /** The model's answer without labels or wrapping; null when nothing is left. */
    fun clean(raw: String): String? {
        var text = raw.trim()
        text = text.removePrefix("```").removeSuffix("```").trim()
        text = text.replace(Regex("^(summary|updated summary|new summary)\\s*:\\s*", RegexOption.IGNORE_CASE), "")
        return text.trim().takeIf { it.length >= MIN_CHARS }
    }

    private const val USER_CHARS = 1_000
    private const val ASSISTANT_CHARS = 500
    private const val MIN_CHARS = 10
}
