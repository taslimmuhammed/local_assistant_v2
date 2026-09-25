package com.local.assistant.memory.core

import com.local.assistant.memory.db.FactEntity

/** Two subjects that may be the same person or thing. Only the user merges these. */
data class DuplicateSuggestion(val first: String, val second: String, val reason: Reason) {
    enum class Reason { NAME_EXTENDS, SPELLING, NAMED_BY }

    /** Order-free, for remembering a dismissal. */
    val key: String get() = AliasPlanner.pairKey(first, second)
}

/**
 * Which subjects are the same entity.
 *
 * Merged automatically only when it is certain: the key re-normalises to another (an honorific,
 * a kinship word — "amma" and "mom" are both `mother`), or the user already merged the name once
 * (a stored alias). Anything that merely looks alike becomes a suggestion for the memory screen.
 */
object AliasPlanner {

    /** subject → the subject it belongs under, for every subject that should move. */
    fun merges(subjects: Collection<String>, aliases: Map<String, String>): Map<String, String> {
        val plan = linkedMapOf<String, String>()
        for (subject in subjects) {
            if (subject == FactKeys.USER) continue
            var target = FactKeys.subject(subject)
            // Follow alias chains, but never around in a circle.
            repeat(MAX_ALIAS_HOPS) { aliases[target]?.takeIf { it != target }?.let { target = it } }
            if (target.isNotEmpty() && target != subject) plan[subject] = target
        }
        return plan
    }

    fun suggestions(facts: List<FactEntity>, dismissed: Set<String>): List<DuplicateSuggestion> {
        val subjects = facts.map { it.subject }.filter { it != FactKeys.USER }.distinct().sorted()
        val found = linkedMapOf<String, DuplicateSuggestion>()
        fun add(a: String, b: String, reason: DuplicateSuggestion.Reason) {
            val suggestion = DuplicateSuggestion(a, b, reason)
            if (a != b && suggestion.key !in dismissed) found.putIfAbsent(suggestion.key, suggestion)
        }
        for (i in subjects.indices) for (j in i + 1 until subjects.size) {
            val a = subjects[i]
            val b = subjects[j]
            val ta = a.split('_')
            val tb = b.split('_')
            when {
                ta.size < tb.size && tb.containsAll(ta) && ta.all { it.length >= MIN_TOKEN } -> add(a, b, DuplicateSuggestion.Reason.NAME_EXTENDS)
                tb.size < ta.size && ta.containsAll(tb) && tb.all { it.length >= MIN_TOKEN } -> add(b, a, DuplicateSuggestion.Reason.NAME_EXTENDS)
                minOf(a.length, b.length) >= MIN_SPELLING && editDistanceIsOne(a, b) -> add(a, b, DuplicateSuggestion.Reason.SPELLING)
            }
        }
        // "sister: name = Priya" alongside facts about "priya".
        val subjectSet = subjects.toSet()
        for (fact in facts) {
            if (fact.attribute != NAME || fact.subject == FactKeys.USER) continue
            val named = FactKeys.subject(fact.value)
            if (named != fact.subject && named in subjectSet) add(fact.subject, named, DuplicateSuggestion.Reason.NAMED_BY)
        }
        return found.values.toList()
    }

    fun pairKey(a: String, b: String): String = if (a < b) "$a|$b" else "$b|$a"

    /** Exactly one insertion, deletion or substitution apart. */
    fun editDistanceIsOne(a: String, b: String): Boolean {
        if (a == b || kotlin.math.abs(a.length - b.length) > 1) return false
        var i = 0
        var j = 0
        var edits = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) {
                i++
                j++
                continue
            }
            if (++edits > 1) return false
            when {
                a.length > b.length -> i++
                a.length < b.length -> j++
                else -> {
                    i++
                    j++
                }
            }
        }
        return edits + (a.length - i) + (b.length - j) == 1
    }

    private const val MAX_ALIAS_HOPS = 3
    private const val MIN_TOKEN = 3
    private const val MIN_SPELLING = 5
    private const val NAME = "name"
}
