package com.local.assistant.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NominatimSearchTest {

    /** Trimmed from Nominatim's answers for "DMart" around Bengaluru, "Kakkanad" around Kochi and "Thamarassery" (5 Nov 2026). */
    private val answer = """
        [
          {"place_id":1,"lat":"12.9889936","lon":"77.7100422","category":"shop","type":"supermarket","name":"DMart",
           "display_name":"DMart, Hoodi Main Road, Hoodi, Kaveri Nagara, Bengaluru East City Corporation, Bengaluru, Karnataka, 560048, India",
           "boundingbox":["12.9887","12.9893","77.7097","77.7104"]},
          {"place_id":2,"lat":"10.0165700","lon":"76.3427500","category":"place","type":"suburb","name":"Kakkanad",
           "display_name":"Kakkanad, Ernakulam, Kanayannur, Ernakulam, Kerala, 682030, India",
           "boundingbox":["9.9965700","10.0365700","76.3227500","76.3627500"]},
          {"place_id":3,"lat":"10.0","lon":"76.3","name":"","display_name":"Seaport Airport Road, Kakkanad, Kerala"},
          {"place_id":5,"lat":"11.4183747","lon":"76.0190597","category":"boundary","type":"administrative","name":"Thamarassery",
           "display_name":"Thamarassery, Kozhikode, Kerala, India"},
          {"place_id":4,"lon":"76.3","name":"No latitude"}
        ]
    """.trimIndent()

    @Test
    fun `places come back with where they are, how big, and which one`() {
        val found = NominatimSearch.parse(answer)

        assertEquals(listOf("DMart", "Kakkanad", "Seaport Airport Road", "Thamarassery"), found.map { it.name })
        // What each is: a town and the taluk named after it are told apart.
        assertEquals(
            listOf(FoundPlace.Kind.SPOT, FoundPlace.Kind.SETTLEMENT, FoundPlace.Kind.SPOT, FoundPlace.Kind.AREA),
            found.map { it.kind },
        )
        val dmart = found[0]
        assertEquals(GeoPoint(12.9889936, 77.7100422), dmart.point)
        assertEquals("Hoodi Main Road", dmart.label)
        // A shop is small: the least radius, which a fix's error and a passing car need.
        assertEquals(PlaceSearch.MIN_RADIUS_M, dmart.radiusM)
        // A suburb 6 km across: as far as "there" goes.
        assertEquals(PlaceSearch.MAX_RADIUS_M, found[1].radiusM)
        assertEquals("Ernakulam", found[1].label)
    }

    @Test
    fun `an answer that isn't a list is nothing found`() {
        assertTrue(NominatimSearch.parse("{\"error\":\"bad\"}").isEmpty())
        assertTrue(NominatimSearch.parse("not json").isEmpty())
    }

    @Test
    fun `the request carries the name and a box, nothing else`() {
        val url = NominatimSearch.url(NominatimSearch.ENDPOINT, "DMart", GeoPoint(13.0, 77.7), 0.15)

        assertEquals("nominatim.openstreetmap.org", url.host)
        assertEquals(setOf("q", "format", "limit", "viewbox", "bounded"), url.queryParameterNames)
        assertEquals("DMart", url.queryParameter("q"))
        assertEquals("77.550,13.150,77.850,12.850", url.queryParameter("viewbox"))
        assertEquals("1", url.queryParameter("bounded"))
    }
}
