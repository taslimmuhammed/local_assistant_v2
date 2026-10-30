package com.local.assistant.wake

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.concurrent.thread

/**
 * The microphone and the [WakeDetector], on a thread of their own while [start]ed. Audio goes
 * from the microphone to the detector 100 ms at a time and nowhere else: nothing is written down
 * or sent. On the phrase it lets go of the microphone first, so the overlay can have it, then
 * calls [onHeard] on the main thread.
 */
class WakeListener(
    private val context: Context,
    /** The microphone or the detector failed; the listener has stopped. On the main thread. */
    private val onFailed: () -> Unit = {},
    private val onHeard: () -> Unit,
) {

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var running = false
    private var worker: Thread? = null

    /** Loaded on the first listen and kept while the listener lives: about 6 MB. */
    private var detector: WakeDetector? = null

    val isListening: Boolean get() = running

    /** Caller holds RECORD_AUDIO. */
    fun start() {
        if (running) return
        worker?.join(STOP_WAIT_MS)
        running = true
        worker = thread(name = "wake-word") { listen() }
    }

    /** Lets go of the microphone; returns once it has (or after [STOP_WAIT_MS]). */
    fun stop() {
        running = false
        worker?.takeIf { it !== Thread.currentThread() }?.join(STOP_WAIT_MS)
    }

    fun release() {
        stop()
        detector?.close()
        detector = null
    }

    @SuppressLint("MissingPermission")
    private fun listen() {
        var heard = false
        var failed = false
        var record: AudioRecord? = null
        try {
            val detector = detector ?: WakeDetector(context.assets).also { detector = it }
            val minBuffer = AudioRecord.getMinBufferSize(WakeWord.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            // A second of room, so a slow turn on a small core loses nothing.
            val bufferBytes = maxOf(minBuffer, WakeWord.SAMPLE_RATE * 2)
            record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                WakeWord.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes,
            )
            check(record.state == AudioRecord.STATE_INITIALIZED) { "Microphone unavailable" }
            record.startRecording()
            val pcm = ShortArray(WakeWord.CHUNK_SAMPLES)
            var loudest = 0
            var chunks = 0
            while (running) {
                val read = record.read(pcm, 0, pcm.size)
                if (read <= 0) continue
                for (i in 0 until read) loudest = maxOf(loudest, kotlin.math.abs(pcm[i].toInt()))
                // How loud the room reaches the detector, every 10 s: a silenced microphone (another
                // app recording) shows as 0.00.
                if (++chunks == LEVEL_EVERY_CHUNKS) {
                    Log.i(TAG, "Loudest in the last 10 s: ${"%.2f".format(loudest / 32768f)}")
                    loudest = 0
                    chunks = 0
                }
                val by = detector.accept(pcm, read)
                if (by != null) {
                    Log.i(TAG, "Heard by ${by.tag}")
                    heard = true
                    break
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "The wake word stopped listening", e)
            failed = true
        } finally {
            runCatching {
                record?.stop()
                record?.release()
            }
            runCatching { detector?.reset() }
            running = false
        }
        if (heard) main.post(onHeard)
        if (failed) main.post(onFailed)
    }

    private companion object {
        const val TAG = "WakeListener"
        const val STOP_WAIT_MS = 1_000L
        const val LEVEL_EVERY_CHUNKS = 100
    }
}
