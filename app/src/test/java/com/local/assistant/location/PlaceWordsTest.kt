package com.local.assistant.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceWordsTest {

    private val towns = setOf("kakkanad", "kochi", "edappally", "munnar")
    private fun read(text: String) = PlaceWords.read(text) { it.lowercase() in towns }

    @Test
    fun `filler words around a place are dropped`() {
        assertEquals(PlaceWords.Named("DMart", null), read("near DMart"))
        assertEquals(PlaceWords.Named("DMart", null), read("the nearest DMart"))
        assertEquals(PlaceWords.Named("DMart", null), read("any DMart nearby"))
        assertEquals(PlaceWords.Named("DMart", null), read("near to a DMart branch"))
        assertEquals(PlaceWords.Named("Lulu Mall", null), read("when I reach Lulu Mall"))
        assertEquals(PlaceWords.Named("Lulu Mall", null), read("when I'm at Lulu Mall."))
    }

    @Test
    fun `here is where the phone is`() {
        assertEquals(PlaceWords.Here, read("here"))
        assertEquals(PlaceWords.Here, read("this place"))
        assertEquals(PlaceWords.Here, read("my current location"))
    }

    @Test
    fun `home and office are the user's own, by any of their names`() {
        assertEquals(PlaceWords.Own("home"), read("home"))
        assertEquals(PlaceWords.Own("home"), read("my house"))
        assertEquals(PlaceWords.Own("office"), read("office"))
        assertEquals(PlaceWords.Own("office"), read("the office"))
        assertEquals(PlaceWords.Own("office"), read("when I reach work"))
    }

    @Test
    fun `a town named with a place is the area to look in`() {
        assertEquals(PlaceWords.Named("DMart", "Kakkanad"), read("DMart in Kakkanad"))
        assertEquals(PlaceWords.Named("DMart", "Kakkanad"), read("DMart, Kakkanad"))
        assertEquals(PlaceWords.Named("Lulu Mall", "Edappally"), read("Lulu Mall near Edappally"))
        assertEquals(PlaceWords.Named("DMart", "Kakkanad"), read("DMart Kakkanad"))
        // Not a town: the whole of it is the place.
        assertEquals(PlaceWords.Named("Infopark Phase 2", null), read("Infopark Phase 2"))
        assertEquals(PlaceWords.Named("coffee shop in Infopark", null), read("coffee shop in Infopark"))
    }

    @Test
    fun `a town alone is a place of its own`() {
        assertEquals(PlaceWords.Named("Munnar", null), read("when I reach Munnar"))
    }

    @Test
    fun `a distance said with the place is not part of it`() {
        assertEquals(PlaceWords.Named("Munnar", null), read("5 km before I reach Munnar"))
        assertEquals(PlaceWords.Named("DMart", null), read("within 500 m of DMart"))
    }

    @Test
    fun `arriving words given as a time are a place, and a time is not`() {
        // As Gemma passed set_alarm's when (8 Nov 2026).
        assertTrue(PlaceWords.isArrival("when I reach avilora"))
        assertTrue(PlaceWords.isArrival("before I reach Bangalore"))
        assertTrue(PlaceWords.isArrival("5 km before Munnar"))
        assertTrue(PlaceWords.isArrival("when I get to office"))
        assertFalse(PlaceWords.isArrival("6am"))
        assertFalse(PlaceWords.isArrival("tomorrow at 5:30"))
        assertFalse(PlaceWords.isArrival("in 10 minutes"))
        assertFalse(PlaceWords.isArrival("when I reach"))
        assertEquals(PlaceWords.Named("Bangalore", null), read("before I reach Bangalore"))
        assertEquals(PlaceWords.Named("Munnar", null), read("as soon as I reach Munnar"))
    }

    @Test
    fun `nothing left is no place`() {
        assertNull(read("near the"))
        assertNull(read("  "))
    }

    @Test
    fun `a place counts as said when its words are in the message`() {
        assertTrue(PlaceWords.mentioned("DMart", "remind me to buy milk near DMart"))
        // The recogniser wrote it in two words.
        assertTrue(PlaceWords.mentioned("DMart", "remind me to buy milk near D Mart"))
        assertTrue(PlaceWords.mentioned("office", "when I reach work remind me to call Priya"))
        assertTrue(PlaceWords.mentioned("here", "remind me to water the plants when I'm back here"))
        assertTrue(PlaceWords.mentioned("near the nearest DMart store", "buy milk at dmart"))
        assertFalse(PlaceWords.mentioned("office", "remind me to call the CA at 5"))
        assertFalse(PlaceWords.mentioned("DMart", "remind me to buy milk tomorrow"))
        // Nothing to check against: it stands.
        assertTrue(PlaceWords.mentioned("DMart", ""))
    }
}
