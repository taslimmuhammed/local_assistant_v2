package com.local.assistant.ui.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Picking photos from a card to share them. */
class PhotoSelectionTest {

    private val card = listOf(10L, 20L, 30L, 40L)

    @Test
    fun `a tap picks a photo and a second lets it go`() {
        val one = PhotoSelection(card).toggle(20L)
        assertEquals(setOf(20L), one.picked)
        assertEquals(1, one.count)
        assertEquals(emptySet<Long>(), one.toggle(20L).picked)
    }

    @Test
    fun `a long press picks, never lets go`() {
        val picked = PhotoSelection(card).pick(30L)
        assertEquals(setOf(30L), picked.picked)
        assertEquals(setOf(30L), picked.pick(30L).picked)
    }

    @Test
    fun `select all picks every one, then clears`() {
        val none = PhotoSelection(card)
        assertFalse(none.all)
        val every = none.toggleAll()
        assertTrue(every.all)
        assertEquals(card.toSet(), every.picked)
        assertEquals(emptySet<Long>(), every.toggleAll().picked)
        // Some picked: select all fills in the rest.
        assertTrue(none.toggle(10L).toggleAll().all)
    }

    @Test
    fun `every one picked by hand counts as all`() {
        val byHand = card.fold(PhotoSelection(card)) { selection, id -> selection.toggle(id) }
        assertTrue(byHand.all)
        assertTrue(PhotoSelection.all(card).all)
        assertFalse(PhotoSelection(emptyList()).all)
    }

    @Test
    fun `picked photos go in the order shown, not the order tapped`() {
        val picked = PhotoSelection(card).toggle(40L).toggle(10L).toggle(30L)
        assertEquals(listOf(10L, 30L, 40L), picked.inOrder())
        // The sheet sorted newest first.
        assertEquals(listOf(40L, 30L, 10L), picked.inOrder(listOf(40L, 30L, 20L, 10L)))
    }

    @Test
    fun `the count says how many of how many`() {
        assertEquals("Select photos", selectedLabel(0, 12))
        assertEquals("3 of 12 selected", selectedLabel(3, 12))
        assertEquals("All 12 selected", selectedLabel(12, 12))
        assertEquals("1 of 2 selected", selectedLabel(1, 2))
        assertEquals("1 selected", selectedLabel(1, 1))
    }
}
