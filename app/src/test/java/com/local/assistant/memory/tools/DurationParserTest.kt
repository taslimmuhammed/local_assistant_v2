package com.local.assistant.memory.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DurationParserTest {

    private fun s(text: String) = DurationParser.seconds(text)

    @Test
    fun `numbers and units`() {
        assertEquals(600, s("10 minutes"))
        assertEquals(600, s("10 min"))
        assertEquals(300, s("5mins"))
        assertEquals(90, s("90 seconds"))
        assertEquals(45, s("45 sec"))
        assertEquals(7_200, s("2 hours"))
        assertEquals(5_400, s("1h30m"))
        assertEquals(5_400, s("1 hour 30 minutes"))
        assertEquals(5_400, s("1.5 hours"))
    }

    @Test
    fun `a bare number is minutes, and one after a unit is the next smaller unit`() {
        assertEquals(600, s("10"))
        assertEquals(5_400, s("1 hour 30"))
        assertEquals(150, s("2 minutes 30"))
    }

    @Test
    fun `words and halves`() {
        assertEquals(3_600, s("an hour"))
        assertEquals(60, s("a minute"))
        assertEquals(1_800, s("half an hour"))
        assertEquals(5_400, s("an hour and a half"))
        assertEquals(90, s("a minute and a half"))
        assertEquals(5_400, s("one and a half hours"))
        assertEquals(150, s("two and a half minutes"))
        assertEquals(900, s("quarter of an hour"))
        assertEquals(1_500, s("twenty five minutes"))
        assertEquals(100, s("a hundred seconds"))
    }

    @Test
    fun `hinglish`() {
        assertEquals(600, s("dus minute"))
        assertEquals(300, s("paanch minute ka timer"))
        assertEquals(1_800, s("aadha ghanta"))
        assertEquals(5_400, s("dedh ghanta"))
        assertEquals(9_000, s("dhai ghante"))
        assertEquals(8_100, s("sawa do ghante"))
        assertEquals(6_300, s("paune do ghante"))
        assertEquals(2_700, s("pauna ghanta"))
        assertEquals(210, s("saadhe teen minute"))
        assertEquals(7_200, s("do ghante"))
    }

    @Test
    fun `nothing to go on`() {
        assertNull(s("for the pasta"))
        assertNull(s(""))
        assertNull(s("0 minutes"))
    }

    @Test
    fun `described for the chip`() {
        assertEquals("10 min", DurationParser.describe(600))
        assertEquals("1 h 30 min", DurationParser.describe(5_400))
        assertEquals("1 min 30 sec", DurationParser.describe(90))
    }
}
