package com.local.assistant.memory.notes

import com.local.assistant.memory.db.NoteItem
import com.local.assistant.memory.db.NoteKind
import com.local.assistant.memory.db.UserNoteEntity
import java.util.Locale

/**
 * The changes edit_note makes to a note or list, kept apart from the database so they can be
 * tested on their own: which note "my grocery list" means, which item "eggs" is, what "remove the
 * ticked ones" removes.
 *
 * Items are matched as people say them: "eggs" is "Eggs (12)", "tomato" is "Tomatoes". A list
 * never holds the same item twice: adding one that's there already says so, and adding one that
 * was ticked off puts it back on the list.
 */
object NoteEdits {

    /** What a call asks to change; null and empty mean leave it. */
    data class Request(
        /** Items to add to a list, or text to add to a note. */
        val add: String? = null,
        val remove: String? = null,
        val check: String? = null,
        val uncheck: String? = null,
        /** The whole new text: a note's, or a list's items, one per line or comma. */
        val text: String? = null,
        val title: String? = null,
    ) {
        val changes: Boolean
            get() = listOf(add, remove, check, uncheck, text, title).any { !it.isNullOrBlank() }
    }

    /** What a change did, item by item, for the model and the chip. */
    data class Outcome(
        val note: UserNoteEntity,
        val added: List<String> = emptyList(),
        /** On the list already, not ticked: left as it was. */
        val already: List<String> = emptyList(),
        /** Ticked off before, and added again: back on the list. */
        val reopened: List<String> = emptyList(),
        val removed: List<String> = emptyList(),
        val checked: List<String> = emptyList(),
        val unchecked: List<String> = emptyList(),
        /** Asked to remove or tick, but not on it. */
        val notFound: List<String> = emptyList(),
        val rewritten: Boolean = false,
        val renamed: Boolean = false,
    ) {
        val changed: Boolean
            get() = added.isNotEmpty() || reopened.isNotEmpty() || removed.isNotEmpty() || checked.isNotEmpty() ||
                unchecked.isNotEmpty() || rewritten || renamed
    }

    /** A change that can't be made, said so the model can tell the user. */
    class Refused(message: String) : Exception(message)

    /** [note] with [request] applied. [now] stamps it when anything changed. */
    fun apply(note: UserNoteEntity, request: Request, now: Long): Outcome {
        var out = Outcome(note)
        request.title?.let(::tidyTitle)?.takeIf { it.isNotEmpty() && it != note.title }?.let { title ->
            out = out.copy(note = out.note.copy(title = title), renamed = true)
        }
        out = when (note.kind) {
            NoteKind.LIST -> applyToList(out, request)
            NoteKind.NOTE -> applyToNote(out, request)
        }
        return if (out.changed) out.copy(note = out.note.copy(updatedAt = now)) else out
    }

    private fun applyToList(start: Outcome, request: Request): Outcome {
        var out = start
        var items = out.note.items
        request.text?.let { text ->
            items = items(text).map { NoteItem(it) }
            out = out.copy(rewritten = true)
        }
        request.add?.let { said ->
            val added = mutableListOf<String>()
            val already = mutableListOf<String>()
            val reopened = mutableListOf<String>()
            for (item in items(said)) {
                val index = items.indexOfFirst { same(it.text, item) }
                when {
                    index < 0 -> {
                        items = items + NoteItem(item)
                        added += item
                    }
                    items[index].done -> {
                        items = items.toMutableList().apply { set(index, items[index].copy(done = false)) }
                        reopened += items[index].text
                    }
                    else -> already += items[index].text
                }
            }
            out = out.copy(added = added, already = already, reopened = reopened)
        }
        val notFound = mutableListOf<String>()
        request.remove?.let { said ->
            val (indexes, missing) = pick(items, said, forRemoval = true)
            out = out.copy(removed = indexes.map { items[it].text })
            items = items.filterIndexed { i, _ -> i !in indexes }
            notFound += missing
        }
        request.check?.let { said ->
            val (indexes, missing) = pick(items, said, forRemoval = false)
            out = out.copy(checked = indexes.filter { !items[it].done }.map { items[it].text })
            items = items.mapIndexed { i, item -> if (i in indexes) item.copy(done = true) else item }
            notFound += missing
        }
        request.uncheck?.let { said ->
            val (indexes, missing) = pick(items, said, forRemoval = false)
            out = out.copy(unchecked = indexes.filter { items[it].done }.map { items[it].text })
            items = items.mapIndexed { i, item -> if (i in indexes) item.copy(done = false) else item }
            notFound += missing
        }
        return out.copy(note = out.note.copy(items = items), notFound = notFound)
    }

    private fun applyToNote(start: Outcome, request: Request): Outcome {
        if (request.check != null || request.uncheck != null) {
            throw Refused("“${start.note.title}” is a note, not a list: it has nothing to tick off.")
        }
        var out = start
        var body = out.note.body
        request.text?.let {
            body = it.trim()
            out = out.copy(rewritten = true)
        }
        request.add?.trim()?.takeIf { it.isNotEmpty() }?.let { said ->
            body = if (body.isBlank()) said else body.trimEnd() + "\n" + said
            out = out.copy(added = listOf(said))
        }
        val notFound = mutableListOf<String>()
        request.remove?.let { said ->
            val removed = mutableListOf<String>()
            for (part in items(said)) {
                val lines = body.lines()
                val gone = lines.filter { it.contains(part, ignoreCase = true) }
                if (gone.isEmpty()) {
                    notFound += part
                } else {
                    removed += gone.map { it.trim() }
                    body = lines.filterNot { it.contains(part, ignoreCase = true) }.joinToString("\n").trim()
                }
            }
            out = out.copy(removed = removed)
        }
        return out.copy(note = out.note.copy(body = body), notFound = notFound)
    }

    /**
     * Which of [items] [said] names: each of its parts matched as people say them, or "all", or
     * for removal "the ticked ones". The indexes, and the parts that matched nothing.
     */
    private fun pick(items: List<NoteItem>, said: String, forRemoval: Boolean): Pair<Set<Int>, List<String>> {
        val whole = key(said)
        if (whole in ALL) return items.indices.toSet() to emptyList()
        if (forRemoval && whole in DONE) return items.indices.filter { items[it].done }.toSet() to emptyList()
        val indexes = linkedSetOf<Int>()
        val missing = mutableListOf<String>()
        for (part in items(said)) {
            val index = find(items, part)
            if (index == null) missing += part else indexes += index
        }
        return indexes to missing
    }

    /** The item [said] means: the same words, or else the one whose words hold all of its (fewest others first). */
    fun find(items: List<NoteItem>, said: String): Int? {
        items.indexOfFirst { same(it.text, said) }.takeIf { it >= 0 }?.let { return it }
        val words = keyWords(said)
        if (words.isEmpty()) return null
        return items.indices
            .map { it to keyWords(items[it].text) }
            .filter { (_, item) -> item.containsAll(words) || (item.isNotEmpty() && words.containsAll(item)) }
            .minByOrNull { (i, item) -> (item - words).size * 1_000 + i }
            ?.first
    }

    private fun same(a: String, b: String): Boolean = keyWords(a).let { it.isNotEmpty() && it == keyWords(b) }

    /**
     * Items as the model wrote them: one per comma, semicolon or line; "milk, eggs and bread" is
     * three. "Milk and eggs" alone is two as well, unless it's one thing ("salt and pepper").
     */
    fun items(raw: String): List<String> {
        val lines = raw.split(Regex("[\\n;]+|,(?![^(]*\\))")).map(::tidyItem).filter { it.isNotEmpty() }
        val listed = lines.size > 1
        return lines.flatMap { line ->
            val halves = line.split(AND, limit = 2).map(::tidyItem)
            val pair = halves.size == 2 && halves.all { it.isNotEmpty() }
            val oneThing = line.lowercase(Locale.ROOT) in PAIRS
            when {
                !pair || oneThing -> listOf(line)
                // "milk, eggs and bread": the last of a list.
                listed && line == lines.last() -> halves
                // "milk and eggs": two short things.
                !listed && halves.all { it.split(' ').size <= SHORT_ITEM_WORDS } -> halves
                else -> listOf(line)
            }
        }.distinctBy { key(it) }
    }

    /** "- [ ] eggs.", "1. Eggs" → "Eggs". */
    fun tidyItem(raw: String): String =
        raw.trim()
            .replace(BULLET, "")
            .trim()
            .trim('"', '“', '”', '\'')
            .trimEnd('.', '!')
            .trim()
            .replaceFirstChar { it.titlecase(Locale.ROOT) }

    /** A note's name as shown: "shopping list" → "Shopping list". */
    fun tidyTitle(raw: String): String =
        raw.trim().trim('"', '“', '”', '\'').trimEnd('.', ':').trim()
            .replace(Regex("\\s+"), " ")
            .take(MAX_TITLE)
            .replaceFirstChar { it.titlecase(Locale.ROOT) }

    /** Lower-case, without articles, each word singular: "the Tomatoes" → "tomato". */
    fun key(text: String): String = keyWords(text).joinToString(" ")

    private fun keyWords(text: String): List<String> =
        WORD.findAll(text.lowercase(Locale.ROOT)).map { singular(it.value) }.filter { it !in ARTICLES }.toList()

    /** "tomatoes" → "tomato", "berries" → "berry", "eggs" → "egg"; "glass" stays. */
    fun singular(word: String): String = when {
        word.length > 4 && word.endsWith("ies") -> word.dropLast(3) + "y"
        word.length > 4 && (word.endsWith("oes") || word.endsWith("ches") || word.endsWith("shes") || word.endsWith("sses") || word.endsWith("xes")) -> word.dropLast(2)
        word.length > 3 && word.endsWith("s") && !word.endsWith("ss") && !word.endsWith("us") && !word.endsWith("is") -> word.dropLast(1)
        else -> word
    }

    /** What a new note should be: a list for list words in its name ("shopping list") or several items; else a note. */
    fun kindFor(title: String, add: String?, text: String?): NoteKind {
        val words = WORD.findAll(title.lowercase(Locale.ROOT)).map { it.value }.toSet()
        if (words.any { it in LIST_WORDS } || "to buy" in title.lowercase(Locale.ROOT)) return NoteKind.LIST
        if (text == null && add != null && items(add).size > 1) return NoteKind.LIST
        return NoteKind.NOTE
    }

    /** A note's text as a list's items, or a list's items as a note's lines: the Notes screen's "Turn into…". */
    fun converted(note: UserNoteEntity, now: Long): UserNoteEntity = when (note.kind) {
        NoteKind.NOTE -> note.copy(kind = NoteKind.LIST, body = "", items = note.body.lines().map(::tidyItem).filter { it.isNotEmpty() }.map { NoteItem(it) }, updatedAt = now)
        NoteKind.LIST -> note.copy(kind = NoteKind.NOTE, items = emptyList(), body = note.items.joinToString("\n") { it.text }, updatedAt = now)
    }

    /**
     * The user's message only asks about a note ("is bread on my grocery list?", "what's on my
     * list"), with nothing in it to change. Gemma answered "is bread on my grocery list?" by adding
     * bread (on the phone, 5 Nov 2026), so a change edit_note is called for on such a message is
     * read back instead. A change word the user aims at the assistant still counts ("is bread on
     * it? if not add it", "can you add bread?"); one in a question about the past does not ("did I
     * add bread?").
     */
    fun onlyAsks(userText: String): Boolean {
        val said = userText.trim().lowercase(Locale.ROOT).replace('’', '\'')
        if (said.isEmpty() || !('?' in said || ASKING.containsMatchIn(said))) return false
        return CHANGE.findAll(said).all { verb -> ASKED_BEFORE.containsMatchIn(said.substring(0, verb.range.first)) }
    }

    /** How a question opens, typed or heard without its question mark. */
    private val ASKING = Regex(
        "^(?:is|are|was|were|am|do|does|did|have|has|had|what|what's|whats|which|how|when|where|who|whose|why|any|anything|" +
            "should|shall|tell me|read|show|list out|remind me what)\\b",
    )

    /** Words that change a note when said to the assistant. */
    private val CHANGE = Regex(
        "\\b(?:add|adding|put|include|remove|delete|take|cross|tick|untick|check|uncheck|mark|rename|call|make|create|start|" +
            "write|note|jot|save|keep|change|replace|update|clear|empty|move|append|insert|drop|scratch|strike|edit|fix|turn|convert)\\b",
    )

    /** "Did I", "should we", "have you already": the change is what's asked about, not asked for. */
    private val ASKED_BEFORE = Regex("\\b(?:did|do|does|have|has|had|should|shall)\\s+(?:i|we|you|they)(?:\\s+(?:already|ever|just))?\\s+$")

    private const val MAX_TITLE = 80

    /** Words of an item or a name: letters and digits, with an apostrophe inside ("mum's"). */
    private val WORD = Regex("[\\p{L}\\p{M}\\p{N}]+(?:['’][\\p{L}]+)?")

    private val ARTICLES = setOf("a", "an", "the", "some", "more")

    /** "- ", "* ", "• ", "1. ", "2) ", "[ ] ", "[x] " at the start of an item. */
    private val BULLET = Regex("^(?:[-*•·]+\\s*|\\d{1,2}[.)]\\s+|\\[[ xX✓]?]\\s*)+")

    private val AND = Regex("\\s+(?:and|&)\\s+", RegexOption.IGNORE_CASE)

    /** "Milk and eggs" splits in two when each side is at most this long. */
    private const val SHORT_ITEM_WORDS = 2

    /** Things named with an "and" that are one thing on a list. */
    private val PAIRS = setOf(
        "salt and pepper", "mac and cheese", "bread and butter", "fish and chips", "rock and roll",
        "salt & pepper", "mac & cheese", "bread & butter", "fish & chips", "pen and paper", "nuts and bolts",
        "oil and vinegar", "cookies and cream", "half and half", "sweet and sour sauce", "pros and cons",
    )

    /** Everything on it. */
    private val ALL = setOf("all", "everything", "all item", "every item", "whole list", "entire list", "all of them", "all of it", "list")

    /** Everything ticked off. */
    private val DONE = setOf(
        "done", "checked", "ticked", "completed", "bought", "crossed off", "done one", "checked one", "ticked one",
        "completed one", "bought one", "done item", "checked item", "ticked item", "completed item", "bought item",
        "ticked off", "checked off", "finished", "finished one", "finished item",
    )

    /** Words in a name that make it a list. */
    private val LIST_WORDS = setOf(
        "list", "lists", "checklist", "groceries", "grocery", "shopping", "packing", "todo", "todos", "wishlist", "watchlist",
    )
}
