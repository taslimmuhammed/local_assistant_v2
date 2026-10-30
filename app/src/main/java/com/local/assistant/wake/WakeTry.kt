package com.local.assistant.wake

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.local.assistant.media.wavHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

/**
 * Settings' "Try it": listens for a few seconds the way the background listener does, with the
 * same detector, and says whether it heard the phrase. In a debuggable build each try is also
 * kept as a WAV under `Android/data/<app>/files/wake-tries/`, to tune the listeners to real
 * voices; a release build keeps nothing.
 */
object WakeTry {

    private val _active = MutableStateFlow(false)

    /** A try is listening: the background listener stands aside, or it would open the overlay. */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    data class Result(
        val heard: Boolean,
        /** The loudest moment, 0..1 of full scale: how well the microphone caught the voice. */
        val peak: Float,
    )

    /** Caller holds RECORD_AUDIO, and the background listener isn't running (the app is in front). */
    @SuppressLint("MissingPermission")
    suspend fun listen(context: Context, seconds: Int = TRY_SECONDS): Result = withContext(Dispatchers.IO) {
        _active.value = true
        val detector = WakeDetector(context.assets)
        val minBuffer = AudioRecord.getMinBufferSize(WakeWord.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            WakeWord.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, WakeWord.SAMPLE_RATE * 2),
        )
        val kept = ShortArray(WakeWord.SAMPLE_RATE * seconds)
        var count = 0
        var by: WakeDetector.By? = null
        var peak = 0
        try {
            check(record.state == AudioRecord.STATE_INITIALIZED) { "Microphone unavailable" }
            record.startRecording()
            val pcm = ShortArray(WakeWord.CHUNK_SAMPLES)
            while (count < kept.size) {
                coroutineContext.ensureActive()
                val read = record.read(pcm, 0, minOf(pcm.size, kept.size - count))
                if (read <= 0) continue
                pcm.copyInto(kept, count, 0, read)
                count += read
                for (i in 0 until read) peak = maxOf(peak, abs(pcm[i].toInt()))
                // Keeps listening to the end once heard, so a kept try has the whole phrase.
                detector.accept(pcm, read)?.let { if (by == null) by = it }
            }
        } finally {
            runCatching {
                record.stop()
                record.release()
            }
            detector.close()
            _active.value = false
        }
        val result = Result(by != null, peak / 32768f)
        Log.i(TAG, "Try: heard=${by?.tag ?: "no"} peak=${"%.2f".format(result.peak)}")
        if (keepsTries(context)) keep(context, kept, count, by)
        result
    }

    /** A debuggable build keeps each try; Settings says so. */
    fun keepsTries(context: Context): Boolean = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    private fun keep(context: Context, samples: ShortArray, count: Int, by: WakeDetector.By?) {
        val dir = context.getExternalFilesDir(TRIES_DIR) ?: return
        val bytes = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) bytes.putShort(samples[i])
        // e.g. try-1793272511518-heard-model.wav, try-1793272511518-missed.wav
        val name = "try-${System.currentTimeMillis()}-${if (by != null) "heard-${by.tag}" else "missed"}.wav"
        File(dir, name).writeBytes(wavHeader(count * 2L, sampleRate = WakeWord.SAMPLE_RATE, channels = 1, bitsPerSample = 16) + bytes.array())
    }

    const val TRY_SECONDS = 4
    private const val TRIES_DIR = "wake-tries"
    private const val TAG = "WakeTry"
}
