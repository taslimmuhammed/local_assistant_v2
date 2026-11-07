package com.local.assistant.memory.notes

import com.local.assistant.memory.db.NoteKind
import com.local.assistant.memory.db.UserNoteEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteMatchTest {

    private fun note(id: Long, title: String, kind: NoteKind = NoteKind.LIST) =
        UserNoteEntity(id = id, title = title, kind = kind, createdAt = 0, updatedAt = 0)

    private val shopping = note(3, "Shopping list")
    private val goa = note(4, "Goa packing list")
    private val wifi = note(7, "Wifi", NoteKind.NOTE)
    private val all = listOf(shopping, goa, wifi)

    private fun one(reference: String, notes: List<UserNoteEntity> = all, loose: Boolean = false) =
        (NoteMatch.find(notes, reference, loose) as? NoteMatch.Found.One)?.note

    @Test
    fun `by id, as the notes index names them`() {
        assertEquals(shopping, one("list 3"))
        assertEquals(wifi, one("note #7"))
        assertEquals(goa, one("4"))
        assertEquals(NoteMatch.Found.None, NoteMatch.find(all, "note 9"))
    }

    @Test
    fun `by name, with the words people swap taken as one`() {
        assertEquals(shopping, one("my shopping list"))
        assertEquals(shopping, one("grocery list"))
        assertEquals(shopping, one("Groceries"))
        assertEquals(goa, one("the Goa one"))
        assertEquals(goa, one("packing"))
        assertEquals(wifi, one("wifi note"))
    }

    @Test
    fun `a name with more words finds the note only when loose`() {
        assertEquals(NoteMatch.Found.None, NoteMatch.find(all, "weekly shopping list"))
        assertEquals(shopping, one("weekly shopping list", loose = true))
    }

    @Test
    fun `my list is the only list there is, or a question`() {
        assertEquals(wifi, one("my note"))
        val lists = NoteMatch.find(all, "my list")
        assertTrue(lists is NoteMatch.Found.Many)
        assertEquals(shopping, one("my list", listOf(shopping, wifi)))
    }

    @Test
    fun `two that fit as well as each other are both given back`() {
        val two = listOf(note(1, "Shopping list"), note(2, "Shopping"))
        val found = NoteMatch.find(two, "shopping")
        assertTrue(found is NoteMatch.Found.Many)
        // The one named exactly wins over a longer name.
        assertEquals(two[1], one("shopping", listOf(note(1, "Big shopping list for Diwali"), two[1])))
    }

    @Test
    fun `a name nothing has is none`() {
        assertEquals(NoteMatch.Found.None, NoteMatch.find(all, "party list"))
        assertEquals(NoteMatch.Found.None, NoteMatch.find(emptyList(), "my list"))
    }
}
