package com.local.assistant.gallery

import com.local.assistant.gallery.places.Places
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class PlacesTest {

    /** A few lines in the asset's format: towns, a state, a country. */
    private val places = Places.read(
        tsv(
            "P\tMunnar\t\t10.0889\t77.0595\tIN\t13\t38471",
            "P\tKochi\tCochin,Ernakulam\t9.9399\t76.2602\tIN\t13\t604696",
            "P\tMumbai\tBombay\t19.0728\t72.8826\tIN\t16\t12691836",
            "P\tAlappuzha\tAlleppey\t9.4900\t76.3264\tIN\t13\t176783",
            // Two towns of one name, far apart.
            "P\tSalem\t\t11.6500\t78.1600\tIN\t25\t778900",
            "P\tSalem\t\t42.5195\t-70.8967\tUS\tMA\t44000",
            "P\tGoa\t\t13.6981\t123.4888\tPH\t05\t60000",
            "R\tIN.13\tKerala",
            "R\tIN.33\tGoa",
            "R\tIN.16\tMaharashtra",
            "C\tIN\tIndia",
            "C\tUS\tUnited States",
        ),
    )

    @Test
    fun aPhotoGetsItsNearestTown() {
        // A tea estate a few km out of Munnar.
        val where = places.nearest(10.11, 77.08)!!
        assertEquals("Munnar", where.town)
        assertEquals("IN.13", where.region)
        assertEquals("IN", where.country)
    }

    @Test
    fun theMiddleOfTheOceanHasNoTown() {
        assertNull(places.nearest(-30.0, 80.0))
    }

    @Test
    fun townsAreFoundByAnyOfTheirNames() {
        val area = places.resolve("Cochin") as Places.Area.Near
        assertEquals("Kochi", area.name)
        assertEquals(15.0, area.radiusKm, 0.0)
        assertTrue(places.resolve("bombay") is Places.Area.Near)
        assertEquals(30.0, (places.resolve("bombay") as Places.Area.Near).radiusKm, 0.0)
    }

    @Test
    fun statesAndCountries() {
        assertEquals(Places.Area.InRegion("Kerala", "IN.13"), places.resolve("kerala"))
        assertEquals(Places.Area.InCountry("India", "IN"), places.resolve("India"))
        assertEquals(Places.Area.InCountry("United States", "US"), places.resolve("the United States"))
        assertNull(places.resolve("Narnia"))
    }

    @Test
    fun ofTwoTownsOfOneNameTheOneWithPhotosWins() {
        val near = places.resolve("Salem") { points, radius -> points.filter { Places.km(it.first, it.second, 42.52, -70.9) <= radius } }
        assertEquals(listOf(42.5195f.toDouble() to (-70.8967f).toDouble()), (near as Places.Area.Near).points)
        // With photos near neither, the bigger one.
        val neither = places.resolve("Salem") { _, _ -> emptyList() } as Places.Area.Near
        assertEquals(11.65f.toDouble(), neither.points.single().first, 1e-6)
    }

    @Test
    fun aStateBeatsAFarTownOfItsNameUnlessThePhotosAreThere() {
        assertEquals(Places.Area.InRegion("Goa", "IN.33"), places.resolve("Goa") { _, _ -> emptyList() })
        assertTrue(places.resolve("Goa") { points, _ -> points } is Places.Area.Near)
    }

    @Test
    fun distances() {
        // Kochi to Munnar, about 85 km as the crow flies.
        assertEquals(85.0, Places.km(9.9399, 76.2602, 10.0889, 77.0595), 5.0)
    }

    private fun tsv(vararg lines: String) = ByteArrayInputStream(lines.joinToString("\n").toByteArray())
}
