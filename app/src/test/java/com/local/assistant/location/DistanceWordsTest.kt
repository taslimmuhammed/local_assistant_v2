package com.local.assistant.location

import com.local.assistant.memory.db.PlaceDistance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DistanceWordsTest {

    private fun before(metres: Int) = PlaceDistance(metres, beforeArriving = true)

    private fun from(metres: Int) = PlaceDistance(metres, beforeArriving = false)

    private fun read(text: String) = DistanceWords.find(text)?.distance

    @Test
    fun `the user's own words, 5 Nov 2026`() {
        assertEquals(before(5000), read("set a reminder before 5 kilometer before I reach Munnar"))
        assertEquals(before(5000), read("Reminder before 5 kilometer"))
    }

    @Test
    fun `before arriving, however it is said`() {
        assertEquals(before(2000), read("remind me 2 km before office"))
        assertEquals(before(2000), read("remind me 2km before office"))
        assertEquals(before(5000), read("5 kms before Kochi"))
        assertEquals(before(1500), read("1.5 km ahead of Kakkanad"))
        assertEquals(before(1000), read("a kilometre before home"))
        assertEquals(before(500), read("half a km before DMart"))
        assertEquals(before(1000), read("1,000 m before DMart"))
        assertEquals(before(4828), read("3 miles before Bengaluru"))
        assertEquals(before(10_000), read("ten kilometers before Munnar"))
        assertEquals(before(300), read("about 300 metres early"))
    }

    @Test
    fun `within or away from the place`() {
        assertEquals(from(500), read("when I'm within 500 m of DMart"))
        assertEquals(from(1000), read("1 km away from home"))
        assertEquals(from(2000), read("when I'm 2 km from Munnar"))
    }

    @Test
    fun `a number and a unit without a way to go are not a distance`() {
        assertNull(read("remind me to buy 2 m of cloth near Reliance Smart"))
        assertNull(read("run 5 km near home"))
        assertNull(read("remind me 5 min before I reach home"))
        assertNull(read("call mom at 5 a.m. near office"))
        assertNull(read("remind me to buy milk near DMart"))
    }

    @Test
    fun `the distance comes out of a title or a place`() {
        assertEquals("Reminder", DistanceWords.remove("Reminder before 5 kilometer"))
        assertEquals("I reach Munnar", DistanceWords.remove("5 km before I reach Munnar"))
        assertEquals("DMart", DistanceWords.remove("within 500 m of DMart"))
        assertEquals("Buy milk", DistanceWords.remove("Buy milk"))
    }

    @Test
    fun `how it is shown and how far out it rings`() {
        assertEquals("5 km before Munnar", before(5000).describe("Munnar"))
        assertEquals("within 500 m of DMart", from(500).describe("DMart"))
        assertEquals("1.5 km", before(1500).words())
        // Before arriving is out from the place's own edge; within is from the place, never under 100 m.
        assertEquals(6000, before(5000).radiusFor(1000))
        assertEquals(500, from(500).radiusFor(250))
        assertEquals(PlaceDistance.MIN_RADIUS_M, from(20).radiusFor(250))
    }
}
