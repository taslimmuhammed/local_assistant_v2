package com.local.assistant.memory.tools

import com.google.gson.Gson
import com.local.assistant.llm.ToolCall
import com.local.assistant.memory.db.NoteItem
import com.local.assistant.memory.db.NoteKind
import com.local.assistant.memory.db.UserNoteEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** edit_note through the real executor, against notes in a map. */
class NotesToolTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private var now = ZonedDateTime.of(2026, 11, 5, 10, 0, 0, 0, zone)
    private val store = FakeMemoryStore { now.toInstant().toEpochMilli() }
    private val log = FakeToolLog()
    private val notes = FakeNoteStore()
    private var paused = false
    private val executor = ToolExecutor(
        store, FakeReminders(), FakeSystemAlarms(), log, now = { now }, memoryPaused = { paused }, notes = notes,
    )
    private val context = ToolContext(chatId = 1, userMessageId = 10)

    private fun edit(vararg args: Pair<String, Any?>, said: String = ""): Pair<ToolExecutor.Outcome, Map<*, *>> {
        now = now.plusSeconds(1)
        val outcome = runBlocking { executor.execute(ToolCall("edit_note", mapOf(*args)), context.copy(userText = said)) }
        return outcome to Gson().fromJson(outcome.result, Map::class.java)
    }

    private fun texts(id: Long) = notes.notes.getValue(id).items.map { it.text }

    private fun shoppingList(): Long = runBlocking {
        notes.insert(
            UserNoteEntity(
                title = "Shopping list", kind = NoteKind.LIST, items = listOf(NoteItem("Milk"), NoteItem("Eggs"), NoteItem("Bread")),
                createdAt = 0, updatedAt = 0,
            ),
        )
    }

    @Test
    fun `make a shopping list makes a list with every item`() {
        val (outcome, result) = edit("note" to "shopping list", "add" to "milk, eggs, bread and curd")

        val note = notes.notes.values.single()
        assertEquals(NoteKind.LIST, note.kind)
        assertEquals("Shopping list", note.title)
        assertEquals(listOf("Milk", "Eggs", "Bread", "Curd"), note.items.map { it.text })
        assertEquals(10L, note.sourceMessageId)
        assertEquals(true, result["ok"])
        assertEquals("list Shopping list", result["created"])
        assertEquals("New list", outcome.chip?.label)
        assertEquals(note.id, outcome.chip?.noteId)
        // Its name is new to "Notes and lists:".
        assertTrue(outcome.changedPrefix)
    }

    @Test
    fun `a note to keep is a note`() {
        val (outcome, _) = edit("note" to "Gate code", "text" to "The society gate code is 4417", said = "note down that the gate code is 4417")

        val note = notes.notes.values.single()
        assertEquals(NoteKind.NOTE, note.kind)
        assertEquals("The society gate code is 4417", note.body)
        assertEquals("New note", outcome.chip?.label)
    }

    @Test
    fun `adding to my grocery list finds the shopping list`() {
        val id = shoppingList()
        val (outcome, result) = edit("note" to "my grocery list", "add" to "curd, eggs")

        assertEquals(1, notes.notes.size)
        assertEquals(listOf("Milk", "Eggs", "Bread", "Curd"), texts(id))
        assertEquals(listOf("Curd"), result["added"])
        assertEquals(listOf("Eggs"), result["already_on_it"])
        assertEquals("4 to do, 0 ticked", result["items"])
        assertEquals("Added", outcome.chip?.label)
        assertEquals("Curd", outcome.chip?.note)
        // Items change what's in it, not its name: the prefix stays as it was.
        assertFalse(outcome.changedPrefix)
    }

    @Test
    fun `ticking off and removing, by the id in the index`() {
        val id = shoppingList()
        val (ticked, result) = edit("note" to "list $id", "check" to "milk, bread")
        assertEquals(listOf(true, false, true), notes.notes.getValue(id).items.map { it.done })
        assertEquals("Ticked", ticked.chip?.label)
        assertEquals("1 to do, 2 ticked", result["items"])

        edit("note" to "list $id", "remove" to "the ticked ones")
        assertEquals(listOf("Eggs"), texts(id))
    }

    @Test
    fun `an item not on the list is said so, and nothing else changes`() {
        val id = shoppingList()
        val (outcome, result) = edit("note" to "shopping list", "remove" to "butter")

        assertEquals(listOf("Milk", "Eggs", "Bread"), texts(id))
        assertEquals(listOf("Butter"), result["not_found"])
        assertTrue(result["note"].toString().startsWith("Not on it, so left alone: Butter."))
        assertNull(log.records.getValue(outcome.recordId!!).undo)
    }

    @Test
    fun `removing from a list that isn't there makes nothing and names the ones there are`() {
        shoppingList()
        val (_, result) = edit("note" to "party list", "remove" to "balloons")

        assertEquals(false, result["ok"])
        assertTrue(result["error"].toString().contains("Shopping list (note 1)"))
        assertEquals(1, notes.notes.size)
    }

    @Test
    fun `a call with nothing to change reads the note back`() {
        val id = shoppingList()
        edit("note" to "shopping list", "check" to "eggs")
        val (outcome, result) = edit("note" to "shopping list")

        assertEquals(listOf("Milk", "Eggs (ticked)", "Bread"), result["items"])
        assertEquals("List", outcome.chip?.label)
        assertEquals(id, outcome.chip?.noteId)
    }

    @Test
    fun `renaming changes the prefix, deleting can be undone`() {
        val id = shoppingList()
        val (renamed, _) = edit("note" to "shopping list", "title" to "Monthly groceries")
        assertEquals("Monthly groceries", notes.notes.getValue(id).title)
        assertTrue(renamed.changedPrefix)

        val (deleted, result) = edit("note" to "monthly groceries", "delete" to true)
        assertTrue(notes.notes.isEmpty())
        assertEquals("list Monthly groceries", result["deleted"])
        assertEquals(ToolExecutor.UndoResult.UNDONE, runBlocking { executor.undo(deleted.recordId!!) })
        assertEquals(listOf("Milk", "Eggs", "Bread"), texts(id))
    }

    @Test
    fun `undo takes an edit back, unless the note was changed since`() {
        val id = shoppingList()
        val (added, _) = edit("note" to "shopping list", "add" to "curd")
        assertEquals(ToolExecutor.UndoResult.UNDONE, runBlocking { executor.undo(added.recordId!!) })
        assertEquals(listOf("Milk", "Eggs", "Bread"), texts(id))

        val (again, _) = edit("note" to "shopping list", "add" to "jaggery")
        // Ticked on the Notes screen in between.
        runBlocking { notes.update(notes.notes.getValue(id).let { it.copy(items = it.items.map { item -> item.copy(done = true) }) }) }
        assertEquals(ToolExecutor.UndoResult.CHANGED_SINCE, runBlocking { executor.undo(again.recordId!!) })
        assertEquals(4, texts(id).size)
    }

    @Test
    fun `undo of a new list deletes it`() {
        val (made, _) = edit("note" to "Goa packing list", "add" to "sunscreen, charger")
        assertEquals(ToolExecutor.UndoResult.UNDONE, runBlocking { executor.undo(made.recordId!!) })
        assertTrue(notes.notes.isEmpty())
    }

    @Test
    fun `two notes that fit as well as each other are asked about`() {
        runBlocking {
            notes.insert(UserNoteEntity(title = "Shopping", kind = NoteKind.LIST, createdAt = 0, updatedAt = 0))
            notes.insert(UserNoteEntity(title = "Shopping list", kind = NoteKind.LIST, createdAt = 0, updatedAt = 0))
        }
        val (_, result) = edit("note" to "shopping", "remove" to "milk")

        assertEquals(false, result["ok"])
        assertTrue(result["error"].toString().startsWith("More than one note fits"))
    }

    @Test
    fun `asked whether bread is on the list, a call to add it reads the list instead`() {
        val id = shoppingList()
        runBlocking { notes.update(notes.notes.getValue(id).copy(items = listOf(NoteItem("Milk")))) }
        val (outcome, result) = edit("note" to "Shopping list", "add" to "bread", said = "is bread on my grocery list? what is on it")

        assertEquals(listOf("Milk"), texts(id))
        assertEquals(listOf("Milk"), result["items"])
        assertTrue(result["note"].toString().startsWith("They only asked"))
        assertEquals("List", outcome.chip?.label)
        assertNull(log.records.getValue(outcome.recordId!!).undo)
    }

    @Test
    fun `asked about a list there isn't, none is made`() {
        val (_, result) = edit("note" to "packing list", "add" to "charger", said = "is the charger on my packing list?")

        assertEquals(false, result["ok"])
        assertTrue(notes.notes.isEmpty())
    }

    @Test
    fun `a note has nothing to tick`() {
        edit("note" to "Wifi", "text" to "Home_5G, password on the router")
        val (_, result) = edit("note" to "wifi", "check" to "password")

        assertEquals(false, result["ok"])
        assertTrue(result["error"].toString().contains("is a note, not a list"))
    }

    @Test
    fun `an edited note's name and the not-found hint don't share a key`() {
        edit("note" to "Wifi", "text" to "Name: Home_5G")
        val (_, result) = edit("note" to "wifi", "remove" to "password")

        assertEquals("Wifi", result["title"])
        assertTrue(result["note"].toString().startsWith("Not on it"))
    }

    @Test
    fun `notes are kept while memory is paused, being the user's own writing`() {
        paused = true
        edit("note" to "shopping list", "add" to "milk, eggs")
        assertEquals(1, notes.notes.size)
    }

    @Test
    fun `the note's id is never grounded to a number the user said`() {
        val id = shoppingList()
        edit("note" to "list $id", "add" to "12 eggs", said = "add 12 eggs to my shopping list")
        assertEquals(listOf("Milk", "Eggs", "Bread", "12 eggs"), texts(id))
    }
}
