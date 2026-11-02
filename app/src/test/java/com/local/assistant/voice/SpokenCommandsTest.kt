package com.local.assistant.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpokenCommandsTest {

    @Test
    fun `a garbled alarm request is put right`() {
        // As the recogniser wrote the user's "set alarm at 4:30" (2 Nov 2026).
        assertEquals("Set an alarm at 4:30 p.m. today", SpokenCommands.repair("sarlamat 4:30 p.m. today"))
        assertEquals("Set an alarm 4:30", SpokenCommands.repair("setalaam 4:30"))
        assertEquals("Set an alarm for gym at 6 a.m.", SpokenCommands.repair("Setalaam for gym at 6 a.m."))
        assertEquals("Set an alarm at 5:45", SpokenCommands.repair("sat alarm at 5:45"))
        assertEquals("Set an alarm at 7pm", SpokenCommands.repair("said alarm at 7pm"))
        assertEquals("Set an alarm at 6", SpokenCommands.repair("Alarm at 6"))
    }

    @Test
    fun `anything else is left as it was said`() {
        listOf(
            "set an alarm for 6 am", "set alarm at 4:30", "call mom at 4:30", "gym at 6", "leave at 5:30 tomorrow",
            "let's meet at 4", "Salaam, what time does the shop close, is it 4:30?", "settle the bill by 5",
            "salary 50000 credited", "setalaam", "what is the time", "",
        ).forEach { assertEquals(it, SpokenCommands.repair(it)) }
    }

    @Test
    fun `a name that is only the garble is no name`() {
        listOf("Setalaam", "Sarlamat", "Sarlamat 4:30 pm today", "set alarm").forEach { assertTrue(it, SpokenCommands.isGarbledCommand(it)) }
        listOf("Gym", "Wake up", "Wake up for the flight", "Medicine", "Call amma", "Salon").forEach { assertFalse(it, SpokenCommands.isGarbledCommand(it)) }
    }
}
