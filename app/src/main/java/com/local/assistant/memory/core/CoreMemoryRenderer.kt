package com.local.assistant.memory.core

import com.local.assistant.memory.db.FactCategory
import com.local.assistant.memory.db.FactEntity
import com.local.assistant.memory.prompt.TokenEstimator

/** The rendered "always in mind" block, plus what had to be left out of it. */
data class CoreMemory(
    val text: String,
    val tokens: Int,
    val included: List<FactEntity>,
    /** Core facts that did not fit, for "N facts didn't fit in Always in mind". */
    val omitted: List<FactEntity>,
) {
    companion object {
        val EMPTY = CoreMemory("", 0, emptyList(), emptyList())
    }
}

/**
 * Renders core facts into the compact block at the top of every conversation.
 *
 * The output must be byte-stable: it sits in the system instruction, and any change to that
 * forces the whole conversation to be prefilled again. So it is rendered in code (the model never
 * edits it), selection and order depend only on the stored rows, and nothing time-dependent is
 * included.
 *
 * Selection is by priority — preferences, then profile, then people, then everything else, each
 * freshest first — until the cap. Preferences are how the user wants to be answered, so they are
 * never dropped, even over the cap. The kept facts are then rendered in a fixed order: category,
 * then id.
 */
class CoreMemoryRenderer(private val estimator: TokenEstimator) {

    fun render(candidates: List<FactEntity>, capTokens: Int): CoreMemory {
        val eligible = candidates.filter { it.core && it.subject == FactKeys.USER }
        if (eligible.isEmpty()) return CoreMemory.EMPTY

        val (preferences, others) = eligible.partition { it.category == FactCategory.PREFERENCE }
        val ranked = others.sortedWith(
            compareBy<FactEntity> { priority(it.category) }
                .thenByDescending { it.lastConfirmedAt }
                .thenBy { it.id },
        )

        val chosen = preferences.toMutableList()
        val omitted = mutableListOf<FactEntity>()
        for (fact in ranked) {
            if (estimator.estimate(format(chosen + fact)) <= capTokens) chosen += fact else omitted += fact
        }

        val ordered = chosen.sortedWith(RENDER_ORDER)
        val text = format(ordered)
        return CoreMemory(text, estimator.estimate(text), ordered, omitted.sortedWith(RENDER_ORDER))
    }

    private fun format(facts: List<FactEntity>): String {
        if (facts.isEmpty()) return ""
        return buildString {
            append(HEADER)
            facts.sortedWith(RENDER_ORDER).groupBy { it.category }.forEach { (category, group) ->
                append('\n')
                append(FactLabels.section(category))
                append(": ")
                group.joinTo(this, separator = " | ") {
                    "${FactLabels.attribute(it.attribute)}: ${FactLabels.inline(it.value)}"
                }
            }
        }
    }

    /** Preferences, profile and people outrank the rest, which share one rank. */
    private fun priority(category: FactCategory): Int = minOf(category.ordinal, 3)

    companion object {
        const val HEADER = "About the user (always keep in mind):"

        private val RENDER_ORDER = compareBy<FactEntity> { it.category.ordinal }.thenBy { it.id }
    }
}

/** Human-readable names for keys, shared by the prompt and the memory screen. */
object FactLabels {

    fun section(category: FactCategory): String = when (category) {
        FactCategory.PREFERENCE -> "Preferences"
        FactCategory.PROFILE -> "About"
        FactCategory.PEOPLE -> "People"
        FactCategory.PLACES -> "Places"
        FactCategory.WORK -> "Work"
        FactCategory.HEALTH -> "Health"
        FactCategory.ROUTINE -> "Routine"
        FactCategory.OTHER -> "Other"
    }

    /** `pref.reply_style` → "reply style"; `ca` → "CA". */
    fun attribute(key: String): String =
        key.removePrefix(FactKeys.PREFERENCE_PREFIX)
            .split('_', '.')
            .filter { it.isNotEmpty() }
            .joinToString(" ") { if (it in ACRONYMS) it.uppercase() else it }

    /** A value on one line, without the separator the block itself uses. */
    fun inline(value: String): String = value.replace(LINE_BREAKS, " ").replace("|", "/").trim()

    private val LINE_BREAKS = Regex("[\\r\\n]+")
    private val ACRONYMS = setOf("ca", "gp", "hr", "ceo", "cto", "cfo", "dob", "pa", "pin", "pan", "upi")
}
