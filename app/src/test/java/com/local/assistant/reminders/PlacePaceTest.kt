package com.local.assistant.reminders

import com.local.assistant.location.GeoPoint
import com.local.assistant.memory.db.PlaceSpot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlacePaceTest {

    // Thamarassery, where "Have food" rang (9 Nov 2026), with its 3 km circle.
    private val town = PlaceSpot(11.3594537, 75.9119868, 3_000, "Thamarassery")

    /** [metres] north of [spot]'s centre. */
    private fun north(metres: Double, spot: PlaceSpot = town) = GeoPoint(spot.lat + metres / 111_195.0, spot.lon)

    private fun fix(point: GeoPoint, accuracyM: Double = 10.0, speedMps: Double? = null, atMs: Long = 0) =
        PlacePace.Fix(point, accuracyM, speedMps, atMs)

    @Test
    fun `clearly inside after being seen outside is an arrival`() {
        assertEquals(PlacePace.Look.Arrived(0), PlacePace.look(fix(north(2_000.0)), listOf(town), seenOutside = true))
    }

    @Test
    fun `inside without having been outside waits, as for a reminder made there`() {
        assertEquals(PlacePace.Look.Neither, PlacePace.look(fix(north(2_000.0)), listOf(town), seenOutside = false))
    }

    @Test
    fun `a fix whose error crosses the edge says neither`() {
        // 2.5 km out, but a cell fix ±1 km may be inside.
        assertEquals(PlacePace.Look.Neither, PlacePace.look(fix(north(2_500.0), accuracyM = 1_000.0), listOf(town), seenOutside = true))
        assertEquals(PlacePace.Look.Neither, PlacePace.look(fix(north(3_500.0), accuracyM = 1_000.0), listOf(town), seenOutside = false))
    }

    @Test
    fun `clearly outside every spot is outside`() {
        assertEquals(PlacePace.Look.Outside, PlacePace.look(fix(north(5_000.0), accuracyM = 500.0), listOf(town), seenOutside = false))
        val shop = PlaceSpot(11.30, 75.95, 250)
        // Inside the second spot: that one rings.
        assertEquals(PlacePace.Look.Arrived(1), PlacePace.look(fix(north(100.0, shop), accuracyM = 20.0), listOf(town, shop), seenOutside = true))
    }

    @Test
    fun `the edge is the nearest a fix's error allows`() {
        assertEquals(2_000.0, PlacePace.toEdge(fix(north(5_000.0)), listOf(town)), 15.0)
        assertEquals(1_000.0, PlacePace.toEdge(fix(north(5_000.0), accuracyM = 1_000.0), listOf(town)), 15.0)
        assertEquals(0.0, PlacePace.toEdge(fix(north(1_000.0)), listOf(town)), 0.0)
    }

    @Test
    fun `far out it looks rarely, on Wi-Fi and cell towers`() {
        assertEquals(PlacePace.Pace(10 * 60_000L, precise = false), PlacePace.next(30_000.0, still = false))
        // 5 km: 3 minutes away at 100 km/h, so every 2.
        assertEquals(PlacePace.Pace(2 * 60_000L, precise = false), PlacePace.next(5_000.0, still = false))
    }

    @Test
    fun `near it looks often, with GPS`() {
        assertEquals(PlacePace.Pace(60_000L, precise = true), PlacePace.next(2_000.0, still = false))
        assertEquals(PlacePace.Pace(30_000L, precise = true), PlacePace.next(500.0, still = false))
        assertEquals(PlacePace.Pace(30_000L, precise = true), PlacePace.next(0.0, still = false))
    }

    @Test
    fun `still, near or far, it looks every 5 minutes without GPS`() {
        assertEquals(PlacePace.STILL, PlacePace.next(500.0, still = true))
        assertEquals(PlacePace.Pace(10 * 60_000L, precise = false), PlacePace.next(30_000.0, still = true))
    }

    @Test
    fun `still is two sure fixes close together, slowly`() {
        val home = north(10_000.0)
        val before = fix(home, accuracyM = 30.0, atMs = 0)
        assertTrue(PlacePace.still(before, fix(north(10_050.0), accuracyM = 25.0, speedMps = 0.0, atMs = 5 * 60_000L)))
        // Wi-Fi fixes, no speed: still too.
        assertTrue(PlacePace.still(before, fix(north(10_040.0), accuracyM = 40.0, atMs = 5 * 60_000L)))
    }

    @Test
    fun `a car stopped at the lights is not still`() {
        val before = fix(north(10_000.0), accuracyM = 10.0, atMs = 0)
        assertFalse(PlacePace.still(before, fix(north(9_600.0), accuracyM = 8.0, speedMps = 0.0, atMs = 30_000L)))
        // Crawling: 50 m in 30 s.
        assertFalse(PlacePace.still(before, fix(north(9_950.0), accuracyM = 8.0, atMs = 30_000L)))
    }

    @Test
    fun `unsure fixes, or moving ones, are not still`() {
        val before = fix(north(10_000.0), accuracyM = 30.0, atMs = 0)
        assertFalse(PlacePace.still(null, fix(north(10_000.0), speedMps = 0.0)))
        assertFalse(PlacePace.still(before, fix(north(10_000.0), accuracyM = 1_500.0, atMs = 5 * 60_000L)))
        assertFalse(PlacePace.still(before, fix(north(10_010.0), speedMps = 12.0, atMs = 5 * 60_000L)))
    }
}
