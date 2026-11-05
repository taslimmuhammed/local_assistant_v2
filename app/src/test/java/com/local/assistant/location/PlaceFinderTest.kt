package com.local.assistant.location

import com.local.assistant.memory.tools.FakeLocation
import com.local.assistant.memory.tools.FakeOwnPlaces
import com.local.assistant.memory.tools.FakePlaceSearch
import com.local.assistant.memory.tools.FakePlaceSearch.Companion.place
import com.local.assistant.memory.tools.HOODI
import com.local.assistant.memory.tools.TEST_TOWNS
import com.local.assistant.memory.tools.ToolError
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PlaceFinderTest {

    private val search = FakePlaceSearch().apply { places["dmart"] = FakePlaceSearch.DMARTS }
    private val location = FakeLocation()
    private val own = FakeOwnPlaces()
    private val finder = PlaceFinder(search, { TEST_TOWNS }, location, own)

    private val kakkanad = GeoPoint(10.0159, 76.3419)

    /** East of Kozhikode: Thamarassery, its taluk's middle and Koduvally are all in the first box searched. */
    private val eastKozhikode = GeoPoint(11.40, 75.95)

    private fun find(said: String, locked: Boolean = false) = runBlocking { finder.find(said, locked) }

    private fun refused(said: String, locked: Boolean = false): String {
        try {
            find(said, locked)
        } catch (e: ToolError) {
            return e.message.orEmpty()
        }
        fail("'$said' was placed")
        error("unreachable")
    }

    @Test
    fun `near DMart is the nearest few branches`() {
        val found = find("near DMart")

        assertEquals("DMart", found.place.name)
        assertEquals(
            listOf("Hoodi Main Road", "Ramamurthinagar Main Road", "Varthur Road", "Hennuru - Bagaluru Road", "Temple Trees Rd"),
            found.place.spots.map { it.label },
        )
        assertTrue(found.place.spots.all { it.radiusM == PlaceSearch.MIN_RADIUS_M })
        assertEquals(760.0, found.nearestM!!, 10.0)
        assertTrue(found.canRing)
    }

    @Test
    fun `the search is sent the user's area, never where exactly they are`() {
        find("near DMart")

        val (query, around, span) = search.asked.single()
        assertEquals("DMart", query)
        assertEquals(13.0, around.lat, 1e-9)
        assertEquals(77.7, around.lon, 1e-9)
        assertEquals(0.15, span, 1e-9)
        assertTrue(around != HOODI)
    }

    @Test
    fun `a place that isn't a chain is its best match alone`() {
        location.at = kakkanad
        search.places["lulu"] = listOf(
            place("LuLu Mall, Kochi", 10.0274822, 76.3079131, "Edappally"),
            place("LuLu IT Twin Towers", 10.0126066, 76.3720886, "Edachira"),
            place("Lulu Bolgatty International Convention Centre", 9.9894956, 76.2645490, "Ponjikkara"),
        )

        val spots = find("Lulu").place.spots

        assertEquals(listOf("Edappally"), spots.map { it.label })
    }

    @Test
    fun `a shop mapped twice counts once`() {
        search.places["more"] = listOf(
            place("More", 12.99, 77.70, "outline"),
            // 25 m away, and a little further from the user.
            place("More", 12.9899, 77.6999, "pin"),
        )

        assertEquals(listOf("outline"), find("More").place.spots.map { it.label })
    }

    @Test
    fun `a town and the taluk named after it are one place, the town`() {
        location.at = eastKozhikode
        // As OpenStreetMap had them (5 Nov 2026), in its order: the taluk first, its middle 9 km
        // into the hills from the town.
        search.places["thamarassery"] = listOf(
            place("Thamarassery", 11.4183747, 76.0190597, "Kozhikode", PlaceSearch.MAX_RADIUS_M, FoundPlace.Kind.AREA),
            place("Thamarassery", 11.4177520, 75.9369456, "Kozhikode", PlaceSearch.MAX_RADIUS_M, FoundPlace.Kind.SETTLEMENT),
        )
        // The town first this time, then the municipality round it.
        search.places["koduvally"] = listOf(
            place("Koduvally", 11.3594537, 75.9119868, "Thamarassery", PlaceSearch.MAX_RADIUS_M, FoundPlace.Kind.SETTLEMENT),
            place("Koduvally", 11.3722139, 75.9222322, "Thamarassery", PlaceSearch.MAX_RADIUS_M, FoundPlace.Kind.AREA),
        )

        assertEquals(GeoPoint(11.4177520, 75.9369456), find("near Thamarassery").place.spots.single().let { GeoPoint(it.lat, it.lon) })
        assertEquals(GeoPoint(11.3594537, 75.9119868), find("near Koduvally").place.spots.single().let { GeoPoint(it.lat, it.lon) })
    }

    @Test
    fun `an area with no town of its name is the area`() {
        location.at = eastKozhikode
        search.places["thamarassery taluk"] = listOf(
            place("Thamarassery Taluk", 11.4183747, 76.0190597, "Kozhikode", PlaceSearch.MAX_RADIUS_M, FoundPlace.Kind.AREA),
        )

        assertEquals(76.0190597, find("Thamarassery Taluk").place.spots.single().lon, 1e-7)
    }

    @Test
    fun `a town on its own is found on the phone`() {
        val found = find("when I reach Munnar")

        assertTrue(search.asked.isEmpty())
        assertEquals("Munnar", found.place.name)
        val spot = found.place.spots.single()
        // The town list keeps floats.
        assertEquals(10.0889, spot.lat, 1e-5)
        assertEquals(PlaceFinder.townRadius(40_000), spot.radiusM)
    }

    @Test
    fun `a town named with the shop is where it's looked for, not where the user is`() {
        search.places["dmart"] = listOf(place("DMart", 10.02, 76.33, "Seaport Airport Road"))

        val found = find("DMart in Kakkanad")

        assertEquals(0, location.fixes)
        val around = search.asked.single().second
        assertEquals(10.0, around.lat, 1e-9)
        assertEquals(76.35, around.lon, 1e-9)
        assertEquals(listOf("Seaport Airport Road"), found.place.spots.map { it.label })
    }

    @Test
    fun `nothing nearby looks once more widely, then asks the user`() {
        location.at = kakkanad

        val error = refused("near DMart")

        assertEquals(listOf(0.15, 0.5), search.asked.map { it.third })
        assertTrue(error, "Ask the user" in error && "Nothing was saved" in error)
    }

    @Test
    fun `a search that can't be made is told, not guessed around`() {
        search.failure = "The map search couldn't reach the internet; the phone may be offline."

        assertTrue("offline" in refused("near DMart"))
    }

    @Test
    fun `home never told asks the user where it is`() {
        val error = refused("home")

        assertTrue(error, "save_fact" in error && "attribute home" in error)
        assertTrue(search.asked.isEmpty())
    }

    @Test
    fun `office is looked up once from what the user said it is`() {
        own.addresses["office"] = "Infopark, Kakkanad"
        search.places["infopark"] = listOf(place("Infopark", 10.0093607, 76.3615845, "Edachira", radiusM = 600))

        val first = find("when I reach office")
        val second = find("office")

        assertEquals(1, search.asked.size)
        assertEquals("Infopark", search.asked.single().first)
        assertEquals("office", first.place.name)
        assertEquals(first.place.spots, second.place.spots)
        assertEquals(600, first.place.spots.single().radiusM)

        // Moved office: looked up again.
        own.addresses["office"] = "Lulu Cyber Tower 2, Kakkanad"
        search.places["lulu cyber tower 2"] = listOf(place("Lulu Cyber Tower 2", 10.009679, 76.3648571))
        find("office")
        assertEquals(2, search.asked.size)
    }

    @Test
    fun `here is the phone's own fix`() {
        location.at = GeoPoint(10.016, 76.342)

        val found = find("here")

        assertEquals("here (Kakkanad)", found.place.name)
        assertEquals(10.016, found.place.spots.single().lat, 1e-9)
        assertTrue(search.asked.isEmpty())
    }

    @Test
    fun `no fix asks the user to turn location on`() {
        location.at = null

        assertTrue("turn location on" in refused("near DMart"))
    }

    @Test
    fun `location is asked for the first time, and a no saves nothing`() {
        location.locate = false
        location.allowLocate = false

        val error = refused("near DMart")

        assertEquals(listOf("locate"), location.asked)
        assertTrue(error, "didn't allow precise location" in error)
        assertTrue(search.asked.isEmpty())
    }

    @Test
    fun `on the lock screen nothing is asked`() {
        location.locate = false

        assertTrue("unlock" in refused("near DMart", locked = true))
        assertTrue(location.asked.isEmpty())
    }

    @Test
    fun `without location all the time it's found, but can't ring yet`() {
        location.watch = false
        location.allowWatch = false

        val found = find("near DMart")

        assertEquals(listOf("watch"), location.asked)
        assertFalse(found.canRing)
        assertEquals(5, found.place.spots.size)
    }

    @Test
    fun `allowed before, nothing is asked`() {
        find("near DMart")
        assertTrue(location.asked.isEmpty())
    }

    @Test
    fun `an area is rounded to about 5 km`() {
        val rounded = GeoPoint(9.9312, 76.2673).rounded()
        assertEquals(9.95, rounded.lat, 1e-9)
        assertEquals(76.25, rounded.lon, 1e-9)
        assertEquals(760.0, HOODI.metresTo(GeoPoint(12.9889936, 77.7100422)), 10.0)
    }
}
