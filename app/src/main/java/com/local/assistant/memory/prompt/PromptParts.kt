package com.local.assistant.memory.prompt

import com.local.assistant.data.db.MessageEntity
import com.local.assistant.memory.core.CoreMemory
import com.local.assistant.memory.db.FactEntity
import java.time.LocalDate
import java.time.ZonedDateTime

/** One line of the agenda snapshot: an open task or an upcoming event. */
data class AgendaItem(
    val title: String,
    /** Null for an undated task. */
    val at: Long?,
    val allDay: Boolean = false,
    /** "task 42" or "event 7": lets the model name exactly the item it means in a tool call. */
    val ref: String? = null,
)

/** Section C: context carried over from before this conversation's verbatim window. */
data class SummaryBlock(val kind: Kind, val text: String) {
    enum class Kind {
        /** The summary of the most recent finished session, from any chat. */
        LAST_SESSION,

        /** This chat's own older turns, folded away by compaction. */
        EARLIER_IN_CHAT,
    }
}

/** Everything the static system instruction (sections A–D) is built from. */
data class PrefixInputs(
    val instructions: String,
    val coreFacts: List<FactEntity>,
    val summary: SummaryBlock?,
    /** Day granularity only, so the prefix changes at most once a day on its own. */
    val agendaDate: LocalDate,
    val agenda: List<AgendaItem>,
)

data class SystemPrefix(
    val text: String,
    val tokens: Int,
    val core: CoreMemory,
    val summaryTokens: Int,
    val agendaTokens: Int,
)

/** An archived exchange recalled for this turn. */
data class Snippet(val text: String, val at: Long)

/**
 * A saved image recalled for this turn. [attached]: the image goes with this message; otherwise
 * the conversation already holds it from an earlier turn, and only the line is repeated.
 */
data class SavedImageLine(val title: String, val details: String, val at: Long, val attached: Boolean = true)

/** Everything the per-turn user message (sections E–G) is built from. */
data class EnvelopeInputs(
    val now: ZonedDateTime,
    val showNow: Boolean,
    /** Non-core facts matched for this turn, best first. */
    val facts: List<FactEntity> = emptyList(),
    /** Retrieved snippets, best first. */
    val snippets: List<Snippet> = emptyList(),
    /** A saved image this message is about, attached to it. */
    val savedImage: SavedImageLine? = null,
    /** What files sent with the message cost: the user's own, and a recalled image. */
    val attachmentTokens: Int = 0,
    val userText: String,
)

data class Envelope(
    val text: String,
    val tokens: Int,
    val facts: List<FactEntity>,
    val snippets: List<Snippet>,
    val showedNow: Boolean,
    /** Whether the recalled image made it in; if not, it must not be attached either. */
    val savedImage: SavedImageLine? = null,
)

data class HistorySelection(
    val messages: List<MessageEntity>,
    /** Stored messages left out, oldest first. */
    val droppedCount: Int,
    val tokens: Int,
)

/** Budget-shedding steps, in the order they are applied. */
enum class ShedStep { SNIPPETS, FACTS, IMAGE, SUMMARY, HISTORY, CORE }

/** A complete prompt that fits the budget, and what was given up to make it fit. */
data class PromptPlan(
    val prefix: SystemPrefix,
    val history: HistorySelection,
    val envelope: Envelope,
    val toolTokens: Int,
    val shed: List<ShedStep>,
) {
    val totalTokens: Int
        get() = prefix.tokens + toolTokens + history.tokens + envelope.tokens
}
