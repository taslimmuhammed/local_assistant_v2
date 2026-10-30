package com.local.assistant.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeConditionsTest {

    private val idle = WakeConditions.State(
        mode = WakeWordMode.ALWAYS,
        micGranted = true,
        charging = false,
        locked = false,
        lockScreenAllowed = false,
        recording = false,
        trying = false,
        inCall = false,
    )

    private fun listens(state: WakeConditions.State) = WakeConditions.shouldListen(state)

    @Test
    fun `listens in the background when always on`() {
        assertTrue(listens(idle))
    }

    @Test
    fun `never when off or without the microphone`() {
        assertFalse(listens(idle.copy(mode = WakeWordMode.OFF)))
        assertFalse(listens(idle.copy(micGranted = false)))
    }

    @Test
    fun `while charging means only on the charger`() {
        assertFalse(listens(idle.copy(mode = WakeWordMode.WHILE_CHARGING)))
        assertTrue(listens(idle.copy(mode = WakeWordMode.WHILE_CHARGING, charging = true)))
    }

    @Test
    fun `a locked phone listens only if the assistant is allowed on the lock screen`() {
        assertFalse(listens(idle.copy(locked = true)))
        assertFalse(listens(idle.copy(mode = WakeWordMode.WHILE_CHARGING, charging = true, locked = true)))
        assertTrue(listens(idle.copy(locked = true, lockScreenAllowed = true)))
    }

    @Test
    fun `quiet while the app records, Try it listens, or a call is on`() {
        assertFalse(listens(idle.copy(recording = true)))
        assertFalse(listens(idle.copy(trying = true)))
        assertFalse(listens(idle.copy(inCall = true)))
    }

    @Test
    fun `says why it isn't listening`() {
        assertEquals(null, WakeConditions.whyNot(idle))
        assertEquals("the phone is locked", WakeConditions.whyNot(idle.copy(locked = true)))
        assertEquals("the app is recording", WakeConditions.whyNot(idle.copy(recording = true)))
    }
}
