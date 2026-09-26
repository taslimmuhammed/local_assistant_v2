package com.local.assistant.memory.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceMatchingTest {

    private fun contact(name: String, vararg numbers: String, primary: Int = -1, mobile: Int = -1) =
        Contact(name, numbers.mapIndexed { i, n -> ContactPhone(n, primary = i == primary, mobile = i == mobile) })

    private val book = listOf(
        contact("Amma", "+91 98450 11111"),
        contact("Amma Home", "080 2222 3333"),
        contact("Priya Sharma", "98450 22222"),
        contact("Priya Nair", "98450 33333"),
        contact("Dr Rao Dentist", "080 4444 5555"),
        contact("Anjali", "98450 66666", "080 7777 8888", mobile = 0),
        contact("Ravi Kumar", "080 1111 2222", "98450 99999", primary = 1),
    )

    private fun find(vararg candidates: String) = ContactMatcher.find(candidates.toList(), book)

    @Test
    fun `a whole name beats one that merely contains it`() {
        assertEquals("Amma", (find("amma") as ContactMatcher.Match.One).contact.name)
        assertEquals("Anjali", (find("Anjali") as ContactMatcher.Match.One).contact.name)
    }

    @Test
    fun `two people with the name are asked about, with digits to tell them apart`() {
        val several = find("Priya") as ContactMatcher.Match.Several
        assertEquals(listOf("Priya Sharma (…2222)", "Priya Nair (…3333)"), several.labels)
        assertEquals("Priya Nair", (find("priya nair") as ContactMatcher.Match.One).contact.name)
    }

    @Test
    fun `what memory knows is tried when the words the user said find no one`() {
        // "my dentist" → the user's dentist fact says "Dr. Rao".
        assertEquals("Dr Rao Dentist", (find("my dentist", "Dr. Rao") as ContactMatcher.Match.One).contact.name)
        // "mom" → the other words for mother.
        assertEquals("Amma", (find("mom", "mummy", "amma") as ContactMatcher.Match.One).contact.name)
        assertEquals(ContactMatcher.Match.None, find("the plumber"))
    }

    @Test
    fun `the default number, else a mobile, else the first`() {
        assertEquals("98450 99999", ContactMatcher.number(book.single { it.name == "Ravi Kumar" }))
        assertEquals("98450 66666", ContactMatcher.number(book.single { it.name == "Anjali" }))
        assertEquals("080 2222 3333", ContactMatcher.number(book.single { it.name == "Amma Home" }))
    }

    private val apps = listOf(
        InstalledApp("YouTube", "com.google.android.youtube"),
        InstalledApp("YouTube Music", "com.google.android.apps.youtube.music"),
        InstalledApp("WhatsApp", "com.whatsapp"),
        InstalledApp("WhatsApp Business", "com.whatsapp.w4b"),
        InstalledApp("Maps", "com.google.android.apps.maps"),
        InstalledApp("Spotify", "com.spotify.music"),
        InstalledApp("Phone", "com.google.android.dialer"),
        InstalledApp("Google Pay", "com.google.android.apps.nbu.paisa.user"),
        InstalledApp("PhonePe", "com.phonepe.app"),
    )

    private fun open(app: String, query: String? = null) = AppMatcher.resolve(app, query, apps)

    private fun target(app: String, query: String? = null) = (open(app, query) as AppMatcher.Result.Found).target

    @Test
    fun `apps are opened by their label`() {
        assertEquals("YouTube", (target("youtube") as AppTarget.Launch).app.label)
        assertEquals("WhatsApp", (target("WhatsApp") as AppTarget.Launch).app.label)
        assertEquals("Google Pay", (target("google pay") as AppTarget.Launch).app.label)
        assertEquals("PhonePe", (target("phonepe") as AppTarget.Launch).app.label)
        assertEquals(AppTarget.Camera, target("camera"))
        assertEquals(AppTarget.Settings, target("settings"))
        assertEquals(AppMatcher.Result.None, open("Instagram"))
    }

    @Test
    fun `searches go to the app that answers them`() {
        assertEquals(AppTarget.Directions("Indiranagar"), target("maps", "Indiranagar"))
        assertEquals(AppTarget.Directions("Indiranagar"), target("Google Maps", "Indiranagar"))
        assertEquals(AppTarget.Directions("Indiranagar"), target("maps", "directions to Indiranagar"))
        assertEquals(AppTarget.Directions("MG Road"), target("maps", "navigate to MG Road"))
        assertEquals(AppTarget.Videos("lofi beats", apps[0]), target("youtube", "lofi beats"))
        assertEquals(AppTarget.Play("Arijit Singh", null), target("music", "Arijit Singh"))
        assertEquals(AppTarget.Play("Arijit Singh", apps[5]), target("spotify", "Arijit Singh"))
        assertEquals(AppTarget.Play("Kesariya", apps[1]), target("youtube music", "Kesariya"))
        assertEquals(AppTarget.WebSearch("weather in Kochi"), target("browser", "weather in Kochi"))
        assertEquals(AppTarget.StoreSearch("Swiggy"), target("play store", "Swiggy"))
    }

    @Test
    fun `an app that cannot be searched is still opened, and the model is told`() {
        val found = open("WhatsApp", "Priya") as AppMatcher.Result.Found
        assertEquals("WhatsApp", (found.target as AppTarget.Launch).app.label)
        assertTrue(found.note!!.contains("can't search"))
    }
}
