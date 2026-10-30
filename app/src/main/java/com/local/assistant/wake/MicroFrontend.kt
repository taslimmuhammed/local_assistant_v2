package com.local.assistant.wake

/**
 * The spectrogram [WakeModel] listens to: TensorFlow Lite Micro's audio frontend, built from
 * source for this app (`src/main/cpp/wake_features.cpp`) with the settings microWakeWord trains
 * with. Every 10 ms of audio gives a frame of [CHANNELS] values, each 0..65535, from a 30 ms window.
 * Holds native memory: [close] it.
 */
class MicroFrontend : AutoCloseable {

    private var handle = nativeCreate().also { check(it != 0L) { "The wake word frontend did not start" } }
    private var out = IntArray(0)

    /**
     * Feeds [count] samples of [pcm] and calls [onFrame] with each frame that finished, in order.
     * The array passed is reused for the next frame.
     */
    fun process(pcm: ShortArray, count: Int, onFrame: (IntArray) -> Unit) {
        val room = (count / STEP_SAMPLES + 1) * CHANNELS
        if (out.size < room) out = IntArray(room)
        val frames = nativeProcess(handle, pcm, count, out)
        val frame = IntArray(CHANNELS)
        for (f in 0 until frames) {
            out.copyInto(frame, 0, f * CHANNELS, (f + 1) * CHANNELS)
            onFrame(frame)
        }
    }

    /** Forgets the audio it has heard, as if new. */
    fun reset() = nativeReset(handle)

    override fun close() {
        if (handle != 0L) nativeDestroy(handle)
        handle = 0L
    }

    companion object {
        const val CHANNELS = 40
        const val STEP_SAMPLES = WakeWord.SAMPLE_RATE / 100

        init {
            System.loadLibrary("wake_features")
        }
    }

    private external fun nativeCreate(): Long
    private external fun nativeProcess(handle: Long, pcm: ShortArray, count: Int, out: IntArray): Int
    private external fun nativeReset(handle: Long)
    private external fun nativeDestroy(handle: Long)
}
