package com.local.assistant.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class VoiceChoiceTest {

    private val india = Locale("en", "IN")

    private fun voice(
        name: String,
        locale: Locale,
        quality: Int = 400,
        latency: Int = 300,
        network: Boolean = false,
        installed: Boolean = true,
    ) = VoiceInfo(name, locale, quality, latency, network, installed)

    private val voices = listOf(
        voice("en-us-x-iob-local", Locale.US),
        voice("en-us-x-iob-network", Locale.US, quality = 500, network = true),
        voice("en-in-x-ene-local", india),
        voice("en-in-x-end-local", india, quality = 300),
        voice("en-gb-x-gba-local", Locale.UK, installed = false),
        voice("hi-in-x-hia-local", Locale("hi", "IN")),
        voice("fr-fr-x-frb-local", Locale.FRANCE),
    )

    @Test
    fun `only installed voices that need no network, in the phone's language or English`() {
        val names = VoiceChoice.usable(voices, india).map { it.name }
        assertEquals(listOf("en-in-x-ene-local", "en-in-x-end-local", "en-us-x-iob-local"), names)
    }

    @Test
    fun `a Hindi phone gets Hindi voices first, then English`() {
        val names = VoiceChoice.usable(voices, Locale("hi", "IN")).map { it.name }
        assertEquals("hi-in-x-hia-local", names.first())
        assertEquals(4, names.size)
    }

    @Test
    fun `the saved voice wins, then the engine's default, then the best`() {
        assertEquals("en-us-x-iob-local", VoiceChoice.choose(voices, "en-us-x-iob-local", "en-in-x-end-local", india)?.name)
        assertEquals("en-in-x-end-local", VoiceChoice.choose(voices, null, "en-in-x-end-local", india)?.name)
        assertEquals("en-in-x-ene-local", VoiceChoice.choose(voices, null, null, india)?.name)
    }

    @Test
    fun `a saved voice that is gone, or a default that needs the network, falls back`() {
        assertEquals("en-in-x-ene-local", VoiceChoice.choose(voices, "uninstalled-voice", "en-us-x-iob-network", india)?.name)
    }

    @Test
    fun `no usable voice means none`() {
        assertNull(VoiceChoice.choose(listOf(voices[1], voices[4]), null, null, india))
    }

    @Test
    fun `voices are numbered within their language and country`() {
        val labels = VoiceChoice.options(voices, india).map { it.label }
        assertEquals(
            listOf("English (India) · Voice 1", "English (India) · Voice 2", "English (United States) · Voice 1"),
            labels,
        )
    }
}
