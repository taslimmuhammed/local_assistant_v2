package com.local.assistant.location

import com.local.assistant.memory.db.PlaceDistance
import com.local.assistant.memory.db.PlaceSpot
import com.local.assistant.memory.db.TaskPlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceDraftTest {

    private val koduvally = TaskPlace(
        "Koduvally",
        listOf(PlaceSpot(11.3594537, 75.9119868, 3000, "Koduvally"), PlaceSpot(11.3722139, 75.9222322, 1200, "Puthur")),
    )

    @Test
    fun `one circle for every pin, and back to the sizes they were found at`() {
        val circle = koduvally.resized(PlaceDistance(500, beforeArriving = false), koduvally)

        assertEquals(listOf(500, 500), circle.spots.map { it.radiusM })
        assertEquals(500, circle.ringMetres())
        assertEquals(koduvally, circle.sizedAs(koduvally))
        assertNull(koduvally.ringMetres())
    }

    @Test
    fun `a moved pin keeps its size and the others stay`() {
        val moved = koduvally.movedTo(1, GeoPoint(11.38, 75.93))

        assertEquals(PlaceSpot(11.38, 75.93, 1200, "Puthur"), moved.spots[1])
        assertEquals(koduvally.spots[0], moved.spots[0])
    }

    @Test
    fun `a circle drawn round a point is that far from it all the way round`() {
        val centre = GeoPoint(11.2871589, 75.7844214)
        val ring = centre.circle(250.0)

        assertEquals(65, ring.size)
        assertEquals(ring.first(), ring.last())
        assertTrue(ring.all { kotlin.math.abs(centre.metresTo(it) - 250) < 1 })
    }

    @Test
    fun `the map zooms so the circle fits its box`() {
        val centre = GeoPoint(11.2871589, 75.7844214)

        // A 250 m circle in a 300 dp box is a street's view; 10 km is a district's.
        assertEquals(15.11, centre.zoomToShow(250.0, 300f), 0.01)
        assertEquals(9.79, centre.zoomToShow(10_000.0, 300f), 0.01)
    }
}
