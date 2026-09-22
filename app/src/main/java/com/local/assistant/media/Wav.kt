package com.local.assistant.media

/**
 * Builds a 44-byte canonical RIFF/WAVE header for 16-bit PCM.
 *
 * Pulled out of the recorder as a plain function because it is the one part of voice input that
 * fails silently: a wrong field here produces a file the runtime's decoder rejects, with nothing
 * in the app to indicate why.
 */
fun wavHeader(
    pcmBytes: Long,
    sampleRate: Int = MediaLimits.AUDIO_SAMPLE_RATE_HZ,
    channels: Int = MediaLimits.AUDIO_CHANNELS,
    bitsPerSample: Int = MediaLimits.AUDIO_BITS_PER_SAMPLE,
): ByteArray {
    val bytesPerFrame = channels * bitsPerSample / 8
    val header = ByteArray(WAV_HEADER_BYTES)
    var at = 0

    fun ascii(value: String) {
        value.forEach { header[at++] = it.code.toByte() }
    }

    fun int32(value: Long) {
        header[at++] = (value and 0xFF).toByte()
        header[at++] = ((value shr 8) and 0xFF).toByte()
        header[at++] = ((value shr 16) and 0xFF).toByte()
        header[at++] = ((value shr 24) and 0xFF).toByte()
    }

    fun int16(value: Int) {
        header[at++] = (value and 0xFF).toByte()
        header[at++] = ((value shr 8) and 0xFF).toByte()
    }

    ascii("RIFF")
    int32(36 + pcmBytes) // everything after this field
    ascii("WAVE")
    ascii("fmt ")
    int32(16) // PCM subchunk size
    int16(1) // format: PCM
    int16(channels)
    int32(sampleRate.toLong())
    int32(sampleRate.toLong() * bytesPerFrame) // byte rate
    int16(bytesPerFrame) // block align
    int16(bitsPerSample)
    ascii("data")
    int32(pcmBytes)

    return header
}

const val WAV_HEADER_BYTES = 44
