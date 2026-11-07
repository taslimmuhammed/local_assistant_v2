package com.local.assistant.memory.notes

import com.local.assistant.memory.db.NoteItem
import com.local.assistant.memory.db.NoteKind
import com.local.assistant.memory.db.UserNoteEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteChunksTest {

    @Test
    fun `a short list is one part, its title in front`() {
        val list = UserNoteEntity(id = 3, title = "Shopping list", kind = NoteKind.LIST, items = listOf(NoteItem("Milk"), NoteItem("Eggs", done = true)), createdAt = 0, updatedAt = 0)
        val parts = NoteChunks.of(list)

        assertEquals(1, parts.size)
        assertEquals("Shopping list: Milk, Eggs", parts.single().text)
        assertEquals(0 to 2, parts.single().fromIndex to parts.single().toIndex)
        assertEquals(3L, parts.single().noteId)
    }

    @Test
    fun `an empty one is still found by its name`() {
        val empty = UserNoteEntity(id = 1, title = "Gift ideas", kind = NoteKind.NOTE, createdAt = 0, updatedAt = 0)
        assertEquals(listOf("Gift ideas"), NoteChunks.of(empty).map { it.text })
    }

    @Test
    fun `a long list is cut between items, each part within size`() {
        val items = List(60) { NoteItem("Item number $it with a few words") }
        val list = UserNoteEntity(id = 1, title = "Big list", kind = NoteKind.LIST, items = items, createdAt = 0, updatedAt = 0)
        val parts = NoteChunks.of(list)

        assertTrue(parts.size > 1)
        assertEquals(0, parts.first().fromIndex)
        assertEquals(60, parts.last().toIndex)
        parts.zipWithNext { a, b -> assertEquals(a.toIndex, b.fromIndex) }
        parts.forEach { assertTrue(it.text.length <= NoteChunks.TARGET_CHARS + "Big list: ".length + 40) }
        assertEquals(parts.indices.toList(), parts.map { it.ordinal })
    }

    @Test
    fun `a long note is cut at lines, then sentences, never mid-word`() {
        val sentence = "The plumber said the tank valve needs changing before the monsoon. "
        val body = "Flat 4B\n\n" + sentence.repeat(12).trim() + "\nCall him on Monday"
        val note = UserNoteEntity(id = 1, title = "House", kind = NoteKind.NOTE, body = body, createdAt = 0, updatedAt = 0)
        val parts = NoteChunks.of(note)

        assertTrue(parts.size > 1)
        assertTrue(parts.first().text.startsWith("House: Flat 4B"))
        assertTrue(parts.last().text.endsWith("Call him on Monday"))
        parts.forEach { part ->
            assertTrue(part.text.removePrefix("House: ").length <= NoteChunks.TARGET_CHARS)
            assertTrue(part.text.endsWith(".") || part.text.endsWith("Monday"))
        }
    }
}
