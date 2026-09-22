package com.local.assistant.media

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.math.min

/** A finished recording, ready to attach to a message. */
data class Recording(val file: File, val durationMs: Long)

data class RecordingState(
    val elapsedMs: Long,
    /** 0f..1f, for the level meter. */
    val amplitude: Float,
) {
    val remainingSeconds: Int
        get() = (MediaLimits.MAX_RECORDING_SECONDS - elapsedMs / 1000).toInt().coerceAtLeast(0)

    val isNearlyOutOfTime: Boolean
        get() = remainingSeconds <= MediaLimits.RECORDING_WARN_SECONDS
}

/**
 * Captures raw PCM with [AudioRecord] and writes a WAV file.
 *
 * Raw PCM rather than [MediaRecorder] because the runtime wants plain mono audio it can decode,
 * and this way the sample rate and channel count are exactly what we say they are.
 */
class AudioRecorder(private val scope: CoroutineScope) {

    private val _state = MutableStateFlow<RecordingState?>(null)
    val state: StateFlow<RecordingState?> = _state.asStateFlow()

    private var job: Job? = null
    private var record: AudioRecord? = null

    /** Set by [cancel] so the capture loop's own cleanup throws the file away. */
    @Volatile
    private var discardRequested = false

    val isRecording: Boolean get() = job?.isActive == true

    /**
     * Starts recording into [destination]. [onFinished] receives the completed recording, or null
     * if it was cancelled or produced nothing. Caller must already hold RECORD_AUDIO permission.
     */
    @SuppressLint("MissingPermission")
    fun start(destination: File, onFinished: (Recording?) -> Unit) {
        if (isRecording) return
        discardRequested = false

        job = scope.launch(Dispatchers.IO) {
            var totalPcmBytes = 0L
            var completed = false
            try {
                val minBuffer = AudioRecord.getMinBufferSize(
                    MediaLimits.AUDIO_SAMPLE_RATE_HZ,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
                require(minBuffer > 0) { "This device cannot record 16 kHz mono audio" }
                val bufferSize = minBuffer * 2

                val recorder = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    MediaLimits.AUDIO_SAMPLE_RATE_HZ,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize,
                )
                check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Recorder unavailable" }
                record = recorder

                RandomAccessFile(destination, "rw").use { out ->
                    out.setLength(0)
                    out.write(ByteArray(WAV_HEADER_BYTES)) // placeholder, filled in at the end

                    recorder.startRecording()
                    val buffer = ByteArray(bufferSize)
                    val startedAt = System.currentTimeMillis()

                    while (isRecording && totalPcmBytes < MediaLimits.MAX_RECORDING_BYTES) {
                        val read = recorder.read(buffer, 0, buffer.size)
                        if (read <= 0) continue

                        val allowed = min(
                            read.toLong(),
                            MediaLimits.MAX_RECORDING_BYTES - totalPcmBytes,
                        ).toInt()
                        out.write(buffer, 0, allowed)
                        totalPcmBytes += allowed

                        _state.value = RecordingState(
                            elapsedMs = System.currentTimeMillis() - startedAt,
                            amplitude = peakAmplitude(buffer, allowed),
                        )
                    }

                    out.seek(0)
                    out.write(wavHeader(totalPcmBytes))
                }
                completed = true
            } finally {
                runCatching {
                    record?.let { if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop() }
                    record?.release()
                }
                record = null
                _state.value = null

                val durationMs = totalPcmBytes * 1000 / BYTES_PER_SECOND
                val usable = completed && !discardRequested &&
                    totalPcmBytes > 0 && durationMs >= MIN_DURATION_MS
                if (!usable) destination.delete()
                onFinished(if (usable) Recording(destination, durationMs) else null)
            }
        }
    }

    /** Stops and keeps the recording. [start]'s callback fires with the result. */
    fun stop() {
        discardRequested = false
        job?.cancel()
        job = null
    }

    /**
     * Stops and throws the recording away. The flag is read by the capture coroutine's own
     * cleanup, so the file is deleted and the callback reports nothing — the caller cannot
     * reliably do this itself, because the callback has not fired yet when cancel() returns.
     */
    fun cancel() {
        discardRequested = true
        job?.cancel()
        job = null
    }

    private fun peakAmplitude(buffer: ByteArray, length: Int): Float {
        var peak = 0
        var i = 0
        while (i + 1 < length) {
            val sample = (buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)
            peak = maxOf(peak, abs(sample.toShort().toInt()))
            i += 64 // sampling a subset is plenty for a level meter
        }
        return (peak / Short.MAX_VALUE.toFloat()).coerceIn(0f, 1f)
    }

    companion object {
        private const val MIN_DURATION_MS = 400L
        private const val BYTES_PER_SECOND =
            MediaLimits.AUDIO_SAMPLE_RATE_HZ * MediaLimits.AUDIO_CHANNELS *
                (MediaLimits.AUDIO_BITS_PER_SAMPLE / 8)
    }
}
