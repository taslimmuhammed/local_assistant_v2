package com.local.assistant.memory.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** The user's notes in the prompt: names in the prefix, what's in one only with a message about it. */
class PromptNotesTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private val budget = MemoryBudget.EIGHT_K
    private val estimator = HeuristicTokenEstimator
    private val assembler = PromptAssembler(budget, estimator, zone)
    private val now = ZonedDateTime.of(2026, 11, 5, 10, 0, 0, 0, zone)

    private fun prefix(notes: List<NoteTitle>) =
        assembler.buildPrefix(PrefixInputs("Instructions.", emptyList(), null, LocalDate.of(2026, 11, 5), emptyList(), notes))

    private fun shopping(whole: List<String> = listOf("Milk", "Eggs (ticked)", "Bread"), matched: List<String> = emptyList(), shownAbove: Boolean = false) =
        NoteLine(3, "Shopping list", list = true, whole = whole, matched = matched, updatedAt = 1, shownAbove = shownAbove)

    @Test
    fun `the prefix names the notes, and nothing in them`() {
        val prefix = prefix(listOf(NoteTitle(3, "Shopping list", list = true), NoteTitle(7, "Wifi", list = false)))

        assertTrue(prefix.text.endsWith("Notes and lists: “Shopping list” (list 3), “Wifi” (note 7)"))
        assertTrue(prefix.notesTokens in 1..budget.notesIndexCap)
    }

    @Test
    fun `many notes are named within the cap, the rest counted`() {
        val many = (1L..40L).map { NoteTitle(it, "Packing list for the trip number $it", list = true) }
        val prefix = prefix(many)

        assertTrue(prefix.notesTokens <= budget.notesIndexCap)
        assertTrue(Regex("and \\d+ more$").containsMatchIn(prefix.text))
    }

    @Test
    fun `no notes, no line`() {
        assertEquals("Instructions.", prefix(emptyList()).text)
    }

    @Test
    fun `a note asked about comes whole, before the user's words`() {
        val envelope = assembler.buildEnvelope(EnvelopeInputs(now, showNow = false, notes = listOf(shopping()), userText = "what's on my shopping list?"))

        assertTrue(envelope.text.startsWith("[List “Shopping list” (list 3): Milk; Eggs (ticked); Bread]"))
        assertTrue(envelope.text.endsWith("what's on my shopping list?"))
        assertEquals(1, envelope.notes.size)
    }

    @Test
    fun `a note too long to come whole comes as the part about the question`() {
        val items = List(80) { "Item number $it" } + "Saffron"
        val envelope = assembler.buildEnvelope(
            EnvelopeInputs(now, showNow = false, notes = listOf(shopping(whole = items, matched = listOf("Saffron"))), userText = "is saffron on it?"),
        )

        assertTrue(envelope.text.startsWith("[List “Shopping list” (list 3), the part about this (more in it): Saffron]"))
    }

    @Test
    fun `a note already shown as it is now is only pointed at`() {
        val envelope = assembler.buildEnvelope(EnvelopeInputs(now, showNow = false, notes = listOf(shopping(shownAbove = true)), userText = "and the bread?"))

        assertTrue(envelope.text.startsWith("[List “Shopping list” (list 3): as shown above, unchanged.]"))
    }

    @Test
    fun `notes together stay within their tokens`() {
        val long = List(200) { "Something to remember number $it" }
        val notes = (1L..3L).map { NoteLine(it, "Note $it", list = false, whole = long, matched = long.take(3), updatedAt = 1) }
        val envelope = assembler.buildEnvelope(EnvelopeInputs(now, showNow = false, notes = notes, userText = "QUESTION"))

        val noteText = envelope.text.substringBefore("QUESTION").trim()
        assertTrue(noteText, estimator.estimate(noteText) <= budget.noteTokens + 4)
        // Each too long whole: the parts about the question, as many notes as fit.
        assertTrue(envelope.notes.isNotEmpty())
        assertTrue(noteText.contains("the part about this (more in it)"))
    }
}
