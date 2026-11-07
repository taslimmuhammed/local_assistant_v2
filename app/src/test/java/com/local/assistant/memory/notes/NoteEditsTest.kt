package com.local.assistant.memory.notes

import com.local.assistant.memory.db.NoteItem
import com.local.assistant.memory.db.NoteKind
import com.local.assistant.memory.db.UserNoteEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteEditsTest {

    private fun list(vararg items: String, done: Set<String> = emptySet()) = UserNoteEntity(
        id = 1, title = "Shopping list", kind = NoteKind.LIST, items = items.map { NoteItem(it, it in done) }, createdAt = 0, updatedAt = 0,
    )

    private fun note(body: String) = UserNoteEntity(id = 2, title = "Wifi", kind = NoteKind.NOTE, body = body, createdAt = 0, updatedAt = 0)

    private fun texts(note: UserNoteEntity) = note.items.map { it.text }

    // ---- Splitting what the model wrote into items ----

    @Test
    fun `items split on commas, lines and semicolons, and the last and of a list`() {
        assertEquals(listOf("Milk", "Eggs", "Bread"), NoteEdits.items("milk, eggs and bread"))
        assertEquals(listOf("Milk", "Eggs", "Bread"), NoteEdits.items("- milk\n- eggs\n- bread"))
        assertEquals(listOf("Milk", "Eggs"), NoteEdits.items("milk; eggs."))
        assertEquals(listOf("Milk", "Eggs"), NoteEdits.items("milk and eggs"))
    }

    @Test
    fun `one thing named with and stays one item`() {
        assertEquals(listOf("Salt and pepper"), NoteEdits.items("salt and pepper"))
        assertEquals(listOf("Fish curry masala and coconut milk powder"), NoteEdits.items("fish curry masala and coconut milk powder"))
        assertEquals(listOf("Eggs (12, brown)", "Milk"), NoteEdits.items("eggs (12, brown), milk"))
    }

    @Test
    fun `the same item twice is one`() {
        assertEquals(listOf("Eggs", "Milk"), NoteEdits.items("eggs, milk, Eggs"))
    }

    // ---- Lists ----

    @Test
    fun `adding puts new items at the end and says which were there`() {
        val out = NoteEdits.apply(list("Milk", "Eggs"), NoteEdits.Request(add = "bread, egg"), now = 5)

        assertEquals(listOf("Milk", "Eggs", "Bread"), texts(out.note))
        assertEquals(listOf("Bread"), out.added)
        assertEquals(listOf("Eggs"), out.already)
        assertEquals(5, out.note.updatedAt)
    }

    @Test
    fun `adding a ticked item puts it back on the list`() {
        val out = NoteEdits.apply(list("Milk", "Eggs", done = setOf("Eggs")), NoteEdits.Request(add = "eggs"), now = 5)

        assertEquals(listOf(NoteItem("Milk"), NoteItem("Eggs")), out.note.items)
        assertEquals(listOf("Eggs"), out.reopened)
        assertTrue(out.changed)
    }

    @Test
    fun `items are found as people say them`() {
        val items = list("Tomatoes", "Eggs (12)", "Basmati rice 5 kg").items
        assertEquals(0, NoteEdits.find(items, "tomato"))
        assertEquals(1, NoteEdits.find(items, "the eggs"))
        assertEquals(2, NoteEdits.find(items, "rice"))
        assertEquals(null, NoteEdits.find(items, "bread"))
    }

    @Test
    fun `ticking, unticking and removing name what they did and what wasn't there`() {
        val start = list("Milk", "Eggs", "Bread")

        val ticked = NoteEdits.apply(start, NoteEdits.Request(check = "milk, butter"), now = 1)
        assertEquals(listOf(true, false, false), ticked.note.items.map { it.done })
        assertEquals(listOf("Milk"), ticked.checked)
        assertEquals(listOf("Butter"), ticked.notFound)

        val unticked = NoteEdits.apply(ticked.note, NoteEdits.Request(uncheck = "milk"), now = 2)
        assertEquals(listOf("Milk"), unticked.unchecked)
        assertTrue(unticked.note.items.none { it.done })

        val removed = NoteEdits.apply(start, NoteEdits.Request(remove = "bread"), now = 3)
        assertEquals(listOf("Milk", "Eggs"), texts(removed.note))
        assertEquals(listOf("Bread"), removed.removed)
    }

    @Test
    fun `remove the ticked ones, tick all`() {
        val start = list("Milk", "Eggs", "Bread", done = setOf("Milk", "Bread"))

        assertEquals(listOf("Eggs"), texts(NoteEdits.apply(start, NoteEdits.Request(remove = "the ticked ones"), now = 1).note))
        assertEquals(listOf("Eggs"), texts(NoteEdits.apply(start, NoteEdits.Request(remove = "done"), now = 1).note))
        assertTrue(NoteEdits.apply(start, NoteEdits.Request(check = "all"), now = 1).note.items.all { it.done })
        assertTrue(NoteEdits.apply(start, NoteEdits.Request(remove = "everything"), now = 1).note.items.isEmpty())
    }

    @Test
    fun `text replaces the whole list, a title renames it`() {
        val out = NoteEdits.apply(list("Milk"), NoteEdits.Request(text = "rice\ndal", title = "monthly groceries"), now = 1)

        assertEquals(listOf("Rice", "Dal"), texts(out.note))
        assertEquals("Monthly groceries", out.note.title)
        assertTrue(out.rewritten && out.renamed)
    }

    @Test
    fun `nothing that changes anything leaves it as it was, time and all`() {
        val start = list("Milk")
        val out = NoteEdits.apply(start, NoteEdits.Request(add = "milk", check = "butter"), now = 9)

        assertFalse(out.changed)
        assertEquals(start, out.note)
    }

    // ---- Notes ----

    @Test
    fun `a note takes added text as a new line, and loses the lines removed`() {
        val added = NoteEdits.apply(note("Name: Home_5G"), NoteEdits.Request(add = "Password is on the router"), now = 1)
        assertEquals("Name: Home_5G\nPassword is on the router", added.note.body)

        val removed = NoteEdits.apply(added.note, NoteEdits.Request(remove = "password"), now = 2)
        assertEquals("Name: Home_5G", removed.note.body)
        assertEquals(listOf("Password is on the router"), removed.removed)
    }

    @Test(expected = NoteEdits.Refused::class)
    fun `a note has nothing to tick`() {
        NoteEdits.apply(note("Gate code 4417"), NoteEdits.Request(check = "gate"), now = 1)
    }

    // ---- Asking, not telling ----

    @Test
    fun `a question about a note asks for no change`() {
        for (text in listOf(
            "is bread on my grocery list? what is on it",
            "what's on my shopping list",
            "do I have eggs on the list",
            "did I add bread to my list?",
            "have I already put the charger on the Goa list?",
            "read me my packing list",
            "how many things are left on my list?",
            "should I add more to it?",
        )) {
            assertTrue(text, NoteEdits.onlyAsks(text))
        }
    }

    @Test
    fun `a change asked for is one, question mark or not`() {
        for (text in listOf(
            "add bread to my list",
            "is bread on my list? if not add it",
            "can you add bread to my shopping list?",
            "could you remove the eggs?",
            "make a shopping list with milk and eggs",
            "what's on my list? tick off the milk",
            "we're out of rice, put it on the list",
            "",
        )) {
            assertTrue(text, !NoteEdits.onlyAsks(text))
        }
    }

    // ---- New notes ----

    @Test
    fun `a new one is a list when its name or its items say so`() {
        assertEquals(NoteKind.LIST, NoteEdits.kindFor("shopping list", add = "milk", text = null))
        assertEquals(NoteKind.LIST, NoteEdits.kindFor("Things to buy for Onam", add = null, text = null))
        assertEquals(NoteKind.LIST, NoteEdits.kindFor("Goa", add = "sunscreen, hat, charger", text = null))
        assertEquals(NoteKind.NOTE, NoteEdits.kindFor("Wifi", add = "The password is on the router", text = null))
        assertEquals(NoteKind.NOTE, NoteEdits.kindFor("Gift ideas for Amma", add = null, text = "A saree, maybe a phone"))
    }

    @Test
    fun `turning a note into a list makes each line an item, and back`() {
        val asList = NoteEdits.converted(note("- rice\n\n- dal"), now = 1)
        assertEquals(NoteKind.LIST, asList.kind)
        assertEquals(listOf("Rice", "Dal"), texts(asList))

        val back = NoteEdits.converted(asList, now = 2)
        assertEquals(NoteKind.NOTE, back.kind)
        assertEquals("Rice\nDal", back.body)
    }
}
