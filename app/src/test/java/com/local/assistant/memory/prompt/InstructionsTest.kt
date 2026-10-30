package com.local.assistant.memory.prompt

import com.local.assistant.data.prefs.SettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InstructionsTest {

    @Test
    fun theDefaultPersonaNamesAnAssistantAsIs() {
        assertEquals(
            "You are Luna, a helpful assistant running entirely on the user's device.",
            Instructions.identity(SettingsStore.DEFAULT_SYSTEM_PROMPT),
        )
    }

    @Test
    fun noPersonaGetsTheRoleAndTheName() {
        assertEquals("${Instructions.ROLE} ${Instructions.NAMED}", Instructions.identity("  "))
    }

    @Test
    fun aPersonaWithoutTheRoleGetsItFirst() {
        assertEquals("${Instructions.ROLE} Talk like a pirate. ${Instructions.NAMED}", Instructions.identity("Talk like a pirate."))
    }

    @Test
    fun anAssistantPersonaWithoutTheNameGetsTheName() {
        assertEquals("You are a helpful assistant. ${Instructions.NAMED}", Instructions.identity("You are a helpful assistant."))
    }

    @Test
    fun aPersonaThatNamesLunaIsLeftAlone() {
        assertEquals("${Instructions.ROLE} Luna speaks briefly.", Instructions.identity("Luna speaks briefly."))
    }

    @Test
    fun theIdentityOpensTheInstructions() {
        val rendered = Instructions.render(SettingsStore.DEFAULT_SYSTEM_PROMPT, withTools = true)
        assertTrue(rendered.startsWith("You are Luna, a helpful assistant running entirely on the user's device.\n\nThe app gives you a memory."))
    }
}
