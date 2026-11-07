package com.local.assistant.memory.notes

import com.local.assistant.memory.db.NoteKind
import com.local.assistant.memory.db.UserNoteEntity
import java.util.Locale

/**
 * Which of the user's notes a call means: "note 3", "my grocery list", "the Goa one". By id, or by
 * the words of its name, with the words people swap for each other taken as one ("grocery list"
 * is the "Shopping list"). A name nothing matches is a new note, for a call that adds to it.
 */
object NoteMatch {

    sealed interface Found {
        data class One(val note: UserNoteEntity) : Found

        /** Several fit equally well: which one is the user's to say. */
        data class Many(val notes: List<UserNoteEntity>) : Found

        data object None : Found
    }

    /**
     * [notes] newest first. [loose]: a name with words the note's lacks still finds it ("my weekly
     * shopping list" finds "Shopping list"), for changes that can't make a new note anyway.
     */
    fun find(notes: List<UserNoteEntity>, reference: String, loose: Boolean = false): Found {
        val said = reference.trim().lowercase(Locale.ROOT)
        ID.matchEntire(said)?.let { match ->
            return notes.firstOrNull { it.id == match.groupValues[1].toLong() }?.let { Found.One(it) } ?: Found.None
        }
        val words = keys(said)
        if (words.isEmpty()) {
            // "my list", "the note": the only one of its kind.
            val kind = when {
                LIST_WORD.containsMatchIn(said) -> NoteKind.LIST
                NOTE_WORD.containsMatchIn(said) -> NoteKind.NOTE
                else -> null
            }
            val ofKind = notes.filter { kind == null || it.kind == kind }
            return when (ofKind.size) {
                0 -> Found.None
                1 -> Found.One(ofKind.single())
                else -> Found.Many(ofKind)
            }
        }
        val holding = notes.filter { keys(it.title).containsAll(words) }
        best(holding, words)?.let { return it }
        if (loose) {
            val within = notes.filter { note -> keys(note.title).let { it.isNotEmpty() && words.containsAll(it) } }
            best(within, words)?.let { return it }
        }
        return Found.None
    }

    /** The one of [candidates] whose name has the fewest words besides [words]; null for none. */
    private fun best(candidates: List<UserNoteEntity>, words: Set<String>): Found? {
        if (candidates.isEmpty()) return null
        val extra = candidates.groupBy { (keys(it.title) - words).size }
        val closest = extra.getValue(extra.keys.min())
        return if (closest.size == 1) Found.One(closest.single()) else Found.Many(closest)
    }

    /** What a name is about: its words singular, without the generic ones, swapped words as one. */
    fun keys(name: String): Set<String> =
        WORD.findAll(name.lowercase(Locale.ROOT))
            .map { NoteEdits.singular(it.value) }
            .filter { it !in GENERIC }
            .map { SAME[it] ?: it }
            .toSet()

    /** "3", "#3", "note 3", "list #3". */
    private val ID = Regex("(?:#|(?:note|list)\\s*#?\\s*)?(\\d+)")

    private val WORD = Regex("[\\p{L}\\p{M}\\p{N}]+")

    private val LIST_WORD = Regex("\\b(?:list|lists|checklist)\\b")
    private val NOTE_WORD = Regex("\\bnotes?\\b")

    /** Words in a name that say only that it's a note. */
    private val GENERIC = setOf(
        "my", "the", "a", "an", "our", "this", "that", "new", "list", "checklist", "note", "for", "of", "to", "on", "in",
        "called", "named", "titled", "one", "item", "thing", "stuff",
    )

    /** Words people use for one another in a list's name. */
    private val SAME = mapOf(
        "grocery" to "shopping", "groceries" to "shopping", "shop" to "shopping", "market" to "shopping",
        "supermarket" to "shopping", "kirana" to "shopping", "provision" to "shopping", "buy" to "shopping",
        "pack" to "packing", "luggage" to "packing",
        "todo" to "to-do", "do" to "to-do", "task" to "to-do",
        "film" to "movie", "watch" to "movie", "watchlist" to "movie",
        "present" to "gift", "wishlist" to "wish",
        "idea" to "idea", "reading" to "book",
    )
}
