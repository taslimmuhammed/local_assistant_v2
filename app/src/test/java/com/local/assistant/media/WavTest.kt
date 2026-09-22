package com.local.assistant.media

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A malformed header makes the runtime's decoder reject the clip with no useful signal in the
 * app, so the field layout is pinned down here rather than discovered on a device.
 */
class WavTest {

    private fun ascii(header: ByteArray, at: Int, length: Int) =
        String(header, at, length, Charsets.US_ASCII)

    private fun int32(header: ByteArray, at: Int): Long =
        (header[at].toLong() and 0xFF) or
            ((header[at + 1].toLong() and 0xFF) shl 8) or
            ((header[at + 2].toLong() and 0xFF) shl 16) or
            ((header[at + 3].toLong() and 0xFF) shl 24)

    private fun int16(header: ByteArray, at: Int): Int =
        (header[at].toInt() and 0xFF) or ((header[at + 1].toInt() and 0xFF) shl 8)

    @Test
    fun `header is the canonical 44 bytes`() {
        assertEquals(44, wavHeader(1000).size)
    }

    @Test
    fun `chunk identifiers are in the right places`() {
        val header = wavHeader(1000)
        assertEquals("RIFF", ascii(header, 0, 4))
        assertEquals("WAVE", ascii(header, 8, 4))
        assertEquals("fmt ", ascii(header, 12, 4))
        assertEquals("data", ascii(header, 36, 4))
    }

    @Test
    fun `riff size covers everything after the size field`() {
        assertEquals(36L + 1000, int32(wavHeader(1000), 4))
    }

    @Test
    fun `data size is the raw pcm length`() {
        assertEquals(1000L, int32(wavHeader(1000), 40))
    }

    @Test
    fun `format block describes 16 bit mono pcm at 16 kHz`() {
        val header = wavHeader(0)
        assertEquals(16L, int32(header, 16))
        assertEquals(1, int16(header, 20))
        assertEquals(1, int16(header, 22))
        assertEquals(16_000L, int32(header, 24))
        assertEquals(16, int16(header, 34))
    }

    @Test
    fun `byte rate and block align match the format`() {
        val header = wavHeader(0)
        assertEquals(32_000L, int32(header, 28))
        assertEquals(2, int16(header, 32))
    }

    @Test
    fun `other rates and channel counts are described correctly`() {
        val header = wavHeader(pcmBytes = 0, sampleRate = 44_100, channels = 2, bitsPerSample = 16)
        assertEquals(2, int16(header, 22))
        assertEquals(44_100L, int32(header, 24))
        assertEquals(176_400L, int32(header, 28))
        assertEquals(4, int16(header, 32))
    }

    @Test
    fun `recording cap matches the declared duration`() {
        assertEquals(960_000L, MediaLimits.MAX_RECORDING_BYTES)
    }
}
