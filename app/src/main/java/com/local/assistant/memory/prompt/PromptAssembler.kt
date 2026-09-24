package com.local.assistant.memory.prompt

import com.local.assistant.data.db.MessageEntity
import com.local.assistant.data.db.Role
import com.local.assistant.llm.ContextWindow
import com.local.assistant.memory.core.CoreMemoryRenderer
import com.local.assistant.memory.core.FactKeys
import com.local.assistant.memory.core.FactLabels
import com.local.assistant.memory.db.FactEntity
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Builds the two halves of every prompt and keeps them inside [budget].
 *
 * **System prefix** (built only when a conversation is created, then byte-stable for its life):
 * A instructions, B core memory, C summary, D agenda.
 *
 * **Envelope** (the user message, every turn): E the current time — only when it has moved on —
 * then F facts and snippets recalled for this turn, then G the user's own words.
 *
 * Anything that changes per turn goes in the envelope; the prefix never contains the time of
 * day, so the conversation's KV cache survives from one turn to the next.
 *
 * When the whole does not fit, parts are shed in a fixed order: snippets, then facts, then the
 * summary, then the oldest verbatim turns, then core memory by priority (keeping preferences).
 */
class PromptAssembler(
    private val budget: MemoryBudget,
    private val estimator: TokenEstimator,
    private val zone: ZoneId,
    /** What one image costs, as reported by the model. */
    private val visionTokensPerImage: Int = DEFAULT_VISION_TOKENS,
) {

    private val coreRenderer = CoreMemoryRenderer(estimator)

    // ---- Prefix (A–D) ----

    fun buildPrefix(
        inputs: PrefixInputs,
        coreCap: Int = budget.coreCap,
        summaryCap: Int = summaryCapFor(inputs.summary),
    ): SystemPrefix {
        val core = coreRenderer.render(inputs.coreFacts, coreCap)
        val summary = inputs.summary?.let { renderSummary(it, summaryCap) }.orEmpty()
        val agenda = renderAgenda(inputs)
        val text = listOf(inputs.instructions.trim(), core.text, summary, agenda)
            .filter { it.isNotEmpty() }
            .joinToString("\n\n")
        return SystemPrefix(
            text = text,
            tokens = estimator.estimate(text),
            core = core,
            summaryTokens = estimator.estimate(summary),
            agendaTokens = estimator.estimate(agenda),
        )
    }

    fun summaryCapFor(summary: SummaryBlock?): Int = when (summary?.kind) {
        SummaryBlock.Kind.EARLIER_IN_CHAT -> budget.rollingSummaryCap
        else -> budget.summaryCap
    }

    private fun renderSummary(summary: SummaryBlock, capTokens: Int): String {
        val heading = when (summary.kind) {
            SummaryBlock.Kind.LAST_SESSION -> "Last time you talked:"
            SummaryBlock.Kind.EARLIER_IN_CHAT -> "Earlier in this chat:"
        }
        val body = truncateToTokens(summary.text.trim(), capTokens - estimator.estimate("$heading\n"))
        return if (body.isEmpty()) "" else "$heading\n$body"
    }

    /** "Agenda as of <date>" and the next few open items, within the agenda cap. */
    private fun renderAgenda(inputs: PrefixInputs): String {
        if (inputs.agenda.isEmpty()) return ""
        val heading = "Agenda as of ${AGENDA_DATE.format(inputs.agendaDate)}:"
        val lines = mutableListOf<String>()
        for (item in inputs.agenda.take(budget.agendaItems)) {
            val line = "- ${formatWhen(item)} · ${FactLabels.inline(item.title)}"
            val candidate = (listOf(heading) + lines + line).joinToString("\n")
            if (estimator.estimate(candidate) > budget.agendaCap) break
            lines += line
        }
        return if (lines.isEmpty()) "" else (listOf(heading) + lines).joinToString("\n")
    }

    private fun formatWhen(item: AgendaItem): String {
        val at = item.at ?: return "no date"
        val time = Instant.ofEpochMilli(at).atZone(zone)
        return if (item.allDay) DAY.format(time) else DAY_TIME.format(time)
    }

    // ---- Envelope (E–G) ----

    /** Whether "Now" has to be repeated: first turn, a new day, or [MemoryBudget.nowRefreshMinutes] on. */
    fun shouldShowNow(lastShown: ZonedDateTime?, now: ZonedDateTime): Boolean =
        lastShown == null ||
            lastShown.toLocalDate() != now.toLocalDate() ||
            Duration.between(lastShown, now).toMinutes() >= budget.nowRefreshMinutes

    /**
     * The user turn as the model receives it. [EnvelopeInputs.userText] is never trimmed or
     * dropped; everything above it is held to [MemoryBudget.envelopeCap].
     */
    fun buildEnvelope(inputs: EnvelopeInputs): Envelope {
        val nowLine = if (inputs.showNow) "[Now: ${NOW.format(inputs.now)}]" else null

        val facts = inputs.facts.take(budget.maxEnvelopeFacts)
        val snippets = mutableListOf<String>()
        val keptSnippets = mutableListOf<Snippet>()
        var snippetTokens = 0
        for (snippet in inputs.snippets.take(budget.maxSnippets)) {
            val line = renderSnippet(snippet)
            val cost = estimator.estimate(line)
            if (snippetTokens + cost > budget.snippetsCap) break
            snippetTokens += cost
            snippets += line
            keptSnippets += snippet
        }

        // Hold the app-added part to its cap: snippets go before facts.
        var keptFacts = facts
        fun header() = listOfNotNull(nowLine, renderFacts(keptFacts)) + snippets
        while (estimator.estimate(header().joinToString("\n")) > budget.envelopeCap) {
            when {
                snippets.isNotEmpty() -> {
                    snippets.removeAt(snippets.lastIndex)
                    keptSnippets.removeAt(keptSnippets.lastIndex)
                }
                keptFacts.isNotEmpty() -> keptFacts = keptFacts.dropLast(1)
                else -> break
            }
        }

        val text = (header() + inputs.userText).joinToString("\n")
        return Envelope(
            text = text,
            tokens = estimator.estimate(text) + ContextWindow.TURN_OVERHEAD_TOKENS,
            facts = keptFacts,
            snippets = keptSnippets,
            showedNow = nowLine != null,
        )
    }

    private fun renderFacts(facts: List<FactEntity>): String? {
        if (facts.isEmpty()) return null
        return facts.joinToString(separator = "; ", prefix = "[Memory: ", postfix = "]") { fact ->
            val attribute = FactLabels.attribute(fact.attribute)
            val label = if (fact.subject == FactKeys.USER) attribute else "${fact.subject.replace('_', ' ')} $attribute"
            "$label: ${FactLabels.inline(fact.value)}"
        }
    }

    private fun renderSnippet(snippet: Snippet): String {
        val date = Instant.ofEpochMilli(snippet.at).atZone(zone)
        val heading = "[Memory (${SNIPPET_DATE.format(date)}): "
        val body = truncateToTokens(FactLabels.inline(snippet.text), budget.snippetTokens - estimator.estimate(heading))
        return "$heading$body]"
    }

    // ---- History ----

    /** What one stored message costs in the conversation, attachments and turn markers included. */
    fun historyCost(message: MessageEntity): Int {
        val text = if (message.tokenEst > 0 || message.text.isEmpty()) {
            message.tokenEst
        } else {
            estimator.estimate(message.text)
        }
        return text + ContextWindow.attachmentTokens(message, visionTokensPerImage) +
            ContextWindow.TURN_OVERHEAD_TOKENS
    }

    /**
     * The newest whole turns that fit both [maxTurns] and [budgetTokens]. A window always starts
     * at a user message, so the model never sees a reply without the question that prompted it.
     */
    fun selectHistory(
        history: List<MessageEntity>,
        maxTurns: Int = budget.maxVerbatimTurns,
        budgetTokens: Int = budget.historyCap,
    ): HistorySelection {
        var start = history.size
        var tokens = 0
        var turns = 0
        var end = history.lastIndex
        while (end >= 0 && turns < maxTurns) {
            var turnStart = end
            while (turnStart > 0 && history[turnStart].role != Role.USER) turnStart--
            val cost = (turnStart..end).sumOf { historyCost(history[it]) }
            if (tokens + cost > budgetTokens) break
            tokens += cost
            turns++
            start = turnStart
            end = turnStart - 1
        }
        return HistorySelection(history.subList(start, history.size), start, tokens)
    }

    // ---- Whole prompt ----

    /**
     * A prompt for a freshly built conversation that fits [MemoryBudget.promptCeiling], shedding
     * in the fixed order until it does. If even the unsheddable minimum — instructions,
     * preferences, the user's words — is over, the plan is returned anyway: refusing to answer
     * would be worse than a long prompt, and the window still has headroom.
     */
    fun plan(
        prefixInputs: PrefixInputs,
        history: List<MessageEntity>,
        envelopeInputs: EnvelopeInputs,
        toolTokens: Int,
    ): PromptPlan {
        val shed = mutableListOf<ShedStep>()
        var snippets = envelopeInputs.snippets
        var facts = envelopeInputs.facts
        var summaryCap = summaryCapFor(prefixInputs.summary)
        var maxTurns = budget.maxVerbatimTurns
        var coreCap = budget.coreCap

        while (true) {
            val envelope = buildEnvelope(envelopeInputs.copy(facts = facts, snippets = snippets))
            // Shedding inside buildEnvelope already applied; carry its result forward.
            snippets = envelope.snippets
            facts = envelope.facts

            val prefix = buildPrefix(prefixInputs, coreCap = coreCap, summaryCap = summaryCap)
            val rolling = if (prefixInputs.summary?.kind == SummaryBlock.Kind.EARLIER_IN_CHAT) prefix.summaryTokens else 0
            val selection = selectHistory(history, maxTurns, budget.historyCap - rolling)
            val plan = PromptPlan(prefix, selection, envelope, toolTokens, shed.toList())
            if (plan.totalTokens <= budget.promptCeiling) return plan

            val turnsKept = selection.messages.count { it.role == Role.USER }
            when {
                snippets.isNotEmpty() -> {
                    snippets = snippets.dropLast(1)
                    shed.addOnce(ShedStep.SNIPPETS)
                }
                facts.isNotEmpty() -> {
                    facts = facts.dropLast(1)
                    shed.addOnce(ShedStep.FACTS)
                }
                prefix.summaryTokens > 0 -> {
                    // Halve what is actually there; a summary too short to halve usefully goes.
                    val half = prefix.summaryTokens / 2
                    summaryCap = if (half > MIN_SUMMARY_TOKENS) half else 0
                    shed.addOnce(ShedStep.SUMMARY)
                }
                turnsKept > 0 -> {
                    maxTurns = turnsKept - 1
                    shed.addOnce(ShedStep.HISTORY)
                }
                prefix.core.included.isNotEmpty() && coreCap > 0 -> {
                    coreCap = (minOf(coreCap, prefix.core.tokens) - CORE_STEP_TOKENS).coerceAtLeast(0)
                    shed.addOnce(ShedStep.CORE)
                }
                else -> return plan
            }
        }
    }

    /**
     * The envelope for a turn on a live conversation already holding [liveTokens] (the runtime's
     * real count). Only the envelope can shed here — the rest is already in the KV cache — so a
     * null result means the conversation itself has to be rebuilt smaller before sending.
     */
    fun fitEnvelope(liveTokens: Int, inputs: EnvelopeInputs): Envelope? {
        var snippets = inputs.snippets
        var facts = inputs.facts
        while (true) {
            val envelope = buildEnvelope(inputs.copy(facts = facts, snippets = snippets))
            if (liveTokens + envelope.tokens <= budget.promptCeiling) return envelope
            snippets = envelope.snippets
            facts = envelope.facts
            when {
                snippets.isNotEmpty() -> snippets = snippets.dropLast(1)
                facts.isNotEmpty() -> facts = facts.dropLast(1)
                else -> return null
            }
        }
    }

    /** Cuts [text] at a word boundary so it fits [capTokens], marking the cut with an ellipsis. */
    fun truncateToTokens(text: String, capTokens: Int): String {
        if (capTokens <= 0) return ""
        if (estimator.estimate(text) <= capTokens) return text
        var low = 0
        var high = text.length
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (estimator.estimate(text.take(mid) + "…") <= capTokens) low = mid else high = mid - 1
        }
        val cut = text.take(low)
        val atWord = cut.substringBeforeLast(' ', cut).trimEnd()
        return if (atWord.isEmpty()) "" else "$atWord…"
    }

    private fun <T> MutableList<T>.addOnce(item: T) {
        if (item !in this) add(item)
    }

    companion object {
        const val DEFAULT_VISION_TOKENS = 256
        private const val MIN_SUMMARY_TOKENS = 50
        private const val CORE_STEP_TOKENS = 100

        private val NOW = DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm", Locale.ENGLISH)
        private val AGENDA_DATE = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH)
        private val DAY_TIME = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.ENGLISH)
        private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
        private val SNIPPET_DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
    }
}
