package com.local.assistant.memory.prompt

/**
 * Every token limit the memory system works to, in one place.
 *
 * The window is never planned full. Every rebuild has to prefill everything that was kept (about
 * 730 tokens a second on the target phone), and a 4B-class model attends less reliably the longer
 * the context gets. So the prompt has a steady-state [promptTarget] well below the window, a hard
 * [promptCeiling] below that, and the gap to the window absorbs the reply, tool rounds and
 * estimation error.
 *
 * Memory is a separate matter: the KV cache is sized by the window the engine is loaded with, not
 * by how full it is (measured flat from 1.7K to 6K tokens). That is why the window itself is
 * capped (`SettingsStore.contextCeilingTokens`), rather than the prompt merely kept small.
 *
 * Nothing else in the memory system hardcodes a limit; it reads them from the active profile.
 */
data class MemoryBudget(
    val name: String,
    /** The engine's context window this profile is sized for. */
    val contextTokens: Int,

    // Static system prefix (A–D) and tool declarations.
    val instructionsCap: Int,
    val toolDeclarationsCap: Int,
    /** Hard cap on the always-in-mind block. */
    val coreCap: Int,
    /** What the block should normally stay inside; the memory screen's meter aims for this. */
    val coreTarget: Int,
    /** Last session's summary (section C). */
    val summaryCap: Int,
    val agendaCap: Int,
    val agendaItems: Int,

    // Rolling history.
    /** "Earlier in this chat", when compaction has folded older turns into a summary. */
    val rollingSummaryCap: Int,
    val maxVerbatimTurns: Int,
    /** Verbatim turns plus rolling summary, before compaction kicks in. */
    val historyCap: Int,

    // Per-turn envelope (E + F).
    val envelopeCap: Int,
    val maxEnvelopeFacts: Int,
    val maxSnippets: Int,
    val snippetTokens: Int,
    val snippetsCap: Int,
    /** The line describing a recalled saved image; the image itself is charged separately. */
    val savedImageTokens: Int,

    // Totals.
    val promptTarget: Int,
    val promptCeiling: Int,
    /** Compaction runs after a reply once the prompt passes this… */
    val compactionTrigger: Int,
    /** …and aims to bring it back down to this. */
    val compactionTarget: Int,

    // Generation.
    val generationReserve: Int,
    val toolRoundsReserve: Int,
    val maxToolRounds: Int,

    /** "Now" is repeated in the envelope when the date changes or this much time has passed. */
    val nowRefreshMinutes: Long,
) {
    val staticPrefixCap: Int
        get() = instructionsCap + toolDeclarationsCap + coreCap + summaryCap + agendaCap

    /** Room the window must still have once the prompt is at its ceiling. */
    val headroom: Int
        get() = contextTokens - promptCeiling

    companion object {

        /** For a 16,384-token window, if the ceiling is ever raised on a device that holds it. */
        val SIXTEEN_K = MemoryBudget(
            name = "16K",
            contextTokens = 16_384,
            // Measured by the runtime (RoutingEvalTest), 26 Sep 2026: instructions 571 tokens with
            // the tool rules, 1,272 for 15 tool declarations; web search adds one tool and a rule.
            instructionsCap = 650,
            toolDeclarationsCap = 1_400,
            coreCap = 1_200,
            coreTarget = 800,
            summaryCap = 400,
            agendaCap = 200,
            agendaItems = 5,
            rollingSummaryCap = 600,
            maxVerbatimTurns = 12,
            historyCap = 6_000,
            envelopeCap = 600,
            maxEnvelopeFacts = 5,
            maxSnippets = 4,
            snippetTokens = 110,
            snippetsCap = 450,
            savedImageTokens = 300,
            promptTarget = 10_000,
            promptCeiling = 12_000,
            compactionTrigger = 9_000,
            compactionTarget = 5_500,
            generationReserve = 2_048,
            toolRoundsReserve = 450,
            maxToolRounds = 3,
            nowRefreshMinutes = 15,
        )

        /**
         * The default: the engine runs at 8K even where 16K fits, leaving memory for the embedder
         * and a voice model alongside it. Halves the history window, envelope, summary and agenda;
         * the instructions, tools and core block cannot shrink without losing function, so they
         * keep their caps.
         *
         * Replies keep their full 2,048 tokens — long answers and generated code are a real use —
         * so the prompt ceiling is what gives: 8,192 less the reply, tool rounds and about 500 of
         * slack. When a large core block and a long summary coincide, history is shed to fit.
         */
        val EIGHT_K = SIXTEEN_K.copy(
            name = "8K",
            contextTokens = 8_192,
            summaryCap = 200,
            agendaCap = 100,
            agendaItems = 3,
            rollingSummaryCap = 300,
            maxVerbatimTurns = 6,
            historyCap = 3_000,
            envelopeCap = 300,
            maxEnvelopeFacts = 3,
            maxSnippets = 2,
            snippetsCap = 225,
            savedImageTokens = 150,
            promptTarget = 4_400,
            promptCeiling = 5_200,
            compactionTrigger = 4_000,
            compactionTarget = 2_400,
            generationReserve = 2_048,
        )

        /**
         * The profile for the window the engine is actually running with, which calibration
         * measured on this device. Below 8K everything is scaled down proportionally so the
         * arithmetic still holds, though memory is then a squeeze.
         */
        fun forWindow(contextTokens: Int): MemoryBudget = when {
            contextTokens >= SIXTEEN_K.contextTokens -> SIXTEEN_K
            contextTokens >= EIGHT_K.contextTokens -> EIGHT_K
            else -> EIGHT_K.scaledTo(contextTokens)
        }
    }

    /** Every token figure multiplied by `window / contextTokens`; counts are left alone. */
    fun scaledTo(window: Int): MemoryBudget {
        val f = window.toDouble() / contextTokens
        fun s(v: Int) = (v * f).toInt()
        return copy(
            name = "${name}×%.2f".format(f),
            contextTokens = window,
            instructionsCap = s(instructionsCap),
            toolDeclarationsCap = s(toolDeclarationsCap),
            coreCap = s(coreCap),
            coreTarget = s(coreTarget),
            summaryCap = s(summaryCap),
            agendaCap = s(agendaCap),
            rollingSummaryCap = s(rollingSummaryCap),
            historyCap = s(historyCap),
            envelopeCap = s(envelopeCap),
            snippetsCap = s(snippetsCap),
            savedImageTokens = s(savedImageTokens),
            promptTarget = s(promptTarget),
            promptCeiling = s(promptCeiling),
            compactionTrigger = s(compactionTrigger),
            compactionTarget = s(compactionTarget),
            generationReserve = s(generationReserve),
            toolRoundsReserve = s(toolRoundsReserve),
        )
    }
}
