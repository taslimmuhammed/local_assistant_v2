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
            "P\tMunnar\t\t10.0889\t77.0595\tIN\t13\t38471\t596",
            "P\tKochi\tCochin,Ernakulam\t9.9399\t76.2602\tIN\t13\t604696\t595",
            "P\tThrissur\tTrichur\t10.5167\t76.2167\tIN\t13\t315596\t594",
            "P\tSrinagar\t\t34.0837\t74.7973\tIN\t12\t975857",
            "P\tMuzaffarabad\t\t34.37\t73.4708\tPK\t06\t725000",
            "P\tMumbai\tBombay\t19.0728\t72.8826\tIN\t16\t12691836",
            "P\tAlappuzha\tAlleppey\t9.4900\t76.3264\tIN\t13\t176783",
            // Two towns of one name, far apart.
            "P\tSalem\t\t11.6500\t78.1600\tIN\t25\t778900",
            "P\tSalem\t\t42.5195\t-70.8967\tUS\tMA\t44000",
            "P\tGoa\t\t13.6981\t123.4888\tPH\t05\t60000",
            "R\tIN.13\tKerala",
            "R\tIN.12\tJammu and Kashmir",
            "R\tPK.06\tAzad Kashmir",
            "R\tIN.25\tTamil Nadu",
            "R\tIN.30\tUttar Pradesh",
            "R\tIN.17\tMadhya Pradesh",
            "R\tIN.02\tAndhra Pradesh",
            "R\tIN.11\tHimachal Pradesh",
            "R\tIN.03\tArunachal Pradesh",
            "R\tIN.18\tMaharashtra",
            "D\tIN.13.590\tWayanad",
            "D\tIN.13.596\tIdukki",
            "D\tIN.13.594\tThrissur District",
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
        assertEquals("IN.13.596", where.district)
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
        assertEquals(Places.Area.InRegion("Kerala", listOf("IN.13")), places.resolve("kerala"))
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
        assertEquals(Places.Area.InRegion("Goa", listOf("IN.33")), places.resolve("Goa") { _, _ -> emptyList() })
        assertTrue(places.resolve("Goa") { points, _ -> points } is Places.Area.Near)
    }

    @Test
    fun districts() {
        // Munnar is in Idukki; asked for the district, photos anywhere in it count.
        assertEquals(Places.Area.InDistrict("Idukki", listOf("IN.13.596")), places.resolve("idukki"))
        // A town in its own district of the same name: the district, so places around it count.
        assertEquals(Places.Area.InDistrict("Thrissur", listOf("IN.13.594")), places.resolve("Thrissur"))
        assertEquals(Places.Area.InDistrict("Thrissur", listOf("IN.13.594")), places.resolve("Thrissur District"))
        // Another name of the town: its district all the same.
        assertEquals(Places.Area.InDistrict("Thrissur", listOf("IN.13.594")), places.resolve("Trichur"))
    }

    @Test
    fun aStateByAPartOfItsName() {
        // Named in full, so the reply says Jammu and Kashmir.
        assertEquals(Places.Area.InRegion("Jammu and Kashmir", listOf("IN.12")), places.resolve("Kashmir"))
        assertEquals(Places.Area.InRegion("Jammu and Kashmir", listOf("IN.12")), places.resolve("jammu and kashmir"))
        assertEquals(listOf("IN.12"), (places.resolve("Jammu") as Places.Area.InRegion).codes)
    }

    @Test
    fun aWordSearchWhenNoNameMatches() {
        assertEquals(Places.Area.InRegion("Tamil Nadu", listOf("IN.25")), places.resolve("nadu"))
        assertEquals(Places.Area.InRegion("Azad Kashmir", listOf("PK.06")), places.resolve("azad"))
        assertEquals(Places.Area.InDistrict("Wayanad", listOf("IN.13.590")), places.resolve("Wayanad district"))
        // Words that only say what kind of place match nothing.
        assertNull(places.resolve("state"))
        // A word in too many names means nothing in particular.
        assertNull(places.resolve("pradesh"))
    }

    @Test
    fun distances() {
        // Kochi to Munnar, about 85 km as the crow flies.
        assertEquals(85.0, Places.km(9.9399, 76.2602, 10.0889, 77.0595), 5.0)
    }

    private fun tsv(vararg lines: String) = ByteArrayInputStream(lines.joinToString("\n").toByteArray())
}
