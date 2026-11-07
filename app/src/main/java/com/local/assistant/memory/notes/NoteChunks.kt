package com.local.assistant.memory.notes

import com.local.assistant.memory.db.NoteKind
import com.local.assistant.memory.db.UserNoteChunkEntity
import com.local.assistant.memory.db.UserNoteEntity

/**
 * A note cut into the parts recall searches: a few items of a list, a few lines of a note, each
 * with the note's title in front, so "eggs" is found as part of the "Shopping list". Most notes
 * are one part. A long one is several, so a question about one thing in it brings back that part
 * and not the whole note.
 *
 * Remade whenever the note changes; a part whose text is the same as before keeps its vector, so
 * ticking off an item re-embeds nothing and adding one re-embeds only the part it went into.
 */
object NoteChunks {

    /** About 100 tokens: small enough to say what a part is about, big enough to read on its own. */
    const val TARGET_CHARS = 400

    /** The parts of [note], ready to store (no ids, no vectors yet). */
    fun of(note: UserNoteEntity): List<UserNoteChunkEntity> {
        val head = note.title.trim().ifEmpty { "Untitled" }
        val spans = when (note.kind) {
            NoteKind.LIST -> listSpans(note)
            NoteKind.NOTE -> noteSpans(note.body)
        }
        if (spans.isEmpty()) return listOf(UserNoteChunkEntity(noteId = note.id, ordinal = 0, text = head, fromIndex = 0, toIndex = 0))
        return spans.mapIndexed { i, (from, to) ->
            val part = when (note.kind) {
                NoteKind.LIST -> note.items.subList(from, to).joinToString(", ") { it.text }
                NoteKind.NOTE -> note.body.substring(from, to).trim()
            }
            UserNoteChunkEntity(noteId = note.id, ordinal = i, text = "$head: $part", fromIndex = from, toIndex = to)
        }
    }

    /** Runs of items, each run up to [TARGET_CHARS]. */
    private fun listSpans(note: UserNoteEntity): List<Pair<Int, Int>> {
        val spans = mutableListOf<Pair<Int, Int>>()
        var start = 0
        var length = 0
        note.items.forEachIndexed { i, item ->
            val cost = item.text.length + 2
            if (i > start && length + cost > TARGET_CHARS) {
                spans += start to i
                start = i
                length = 0
            }
            length += cost
        }
        if (start < note.items.size) spans += start to note.items.size
        return spans
    }

    /**
     * Runs of whole lines, each up to [TARGET_CHARS]; a line longer than that is cut at sentence
     * ends, then at spaces. Character ranges into [body], blank stretches left out.
     */
    private fun noteSpans(body: String): List<Pair<Int, Int>> {
        if (body.isBlank()) return emptyList()
        val pieces = mutableListOf<Pair<Int, Int>>()
        var lineStart = 0
        while (lineStart <= body.length) {
            val end = body.indexOf('\n', lineStart).let { if (it < 0) body.length else it }
            if (body.substring(lineStart, end).isNotBlank()) pieces += split(body, lineStart, end)
            lineStart = end + 1
        }
        val spans = mutableListOf<Pair<Int, Int>>()
        var current: Pair<Int, Int>? = null
        for (piece in pieces) {
            current = when {
                current == null -> piece
                piece.second - current.first <= TARGET_CHARS -> current.first to piece.second
                else -> {
                    spans += current
                    piece
                }
            }
        }
        current?.let { spans += it }
        return spans
    }

    /** [from, to) of [body] in pieces of at most [TARGET_CHARS]: at a sentence's end where there is one, else at a space. */
    private fun split(body: String, from: Int, to: Int): List<Pair<Int, Int>> {
        val out = mutableListOf<Pair<Int, Int>>()
        var start = from
        while (to - start > TARGET_CHARS) {
            val window = body.substring(start, start + TARGET_CHARS)
            val cut = listOf(". ", "! ", "? ").maxOf { window.lastIndexOf(it) }.takeIf { it > TARGET_CHARS / 3 }?.plus(1)
                ?: window.lastIndexOf(' ').takeIf { it > TARGET_CHARS / 3 }
                ?: TARGET_CHARS
            out += start to start + cut
            start += cut
            while (start < to && body[start] == ' ') start++
        }
        if (start < to) out += start to to
        return out
    }
}
