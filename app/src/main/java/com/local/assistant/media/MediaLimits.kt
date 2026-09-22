package com.local.assistant.media

/**
 * Constraints on what we hand the model.
 *
 * The audio numbers are dictated by the runtime: the native preprocessor states "Only mono audio
 * is supported", and it decodes through miniaudio, which resamples for us — so 16 kHz mono PCM
 * is both the safe choice and the conventional rate for a speech encoder.
 *
 * The model also enforces its own ceiling (`valid_audio_length <= max_audio_seq_length`), which
 * comes from the model file rather than the library, so it cannot be read ahead of time. The
 * recording cap here is the conservative side of that: long enough for a spoken question, short
 * enough to stay inside the window.
 */
object MediaLimits {

    const val AUDIO_SAMPLE_RATE_HZ = 16_000
    const val AUDIO_CHANNELS = 1
    const val AUDIO_BITS_PER_SAMPLE = 16

    /** Hard stop for a single recording. */
    const val MAX_RECORDING_SECONDS = 30

    /** Warn the user this many seconds before the hard stop. */
    const val RECORDING_WARN_SECONDS = 5

    val MAX_RECORDING_BYTES: Long =
        MAX_RECORDING_SECONDS.toLong() *
            AUDIO_SAMPLE_RATE_HZ *
            AUDIO_CHANNELS *
            (AUDIO_BITS_PER_SAMPLE / 8)

    /**
     * Longest edge an image is scaled to before it reaches the vision encoder. Sending a full
     * camera photo wastes both memory and vision tokens for no gain in what the model sees.
     */
    const val MAX_IMAGE_EDGE_PX = 768

    const val IMAGE_JPEG_QUALITY = 90
}
