package com.local.assistant.wake

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.local.assistant.media.wavHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.sqrt

/**
 * Recordings for training a wake word model on the user's own voice (the user's request,
 * 2026-10-29: train a dedicated "Hey Luna" model with microWakeWord). Test builds only, from
 * Settings › Wake word: the user says the phrase again and again, or talks normally, and the
 * session is kept whole as a WAV under `Android/data/<app>/files/wake-samples/` for the Mac to cut
 * up. Recorded as the background listener hears (the raw voice-recognition microphone, 16 kHz
 * mono), not through the Recorder app, which cleans the sound up first.
 */
object WakeSamples {

    /** What a session holds. */
    enum class Kind(val tag: String) {
        /** "Hey Luna", said over and over with a pause between. */
        PHRASE("luna"),

        /** Ordinary talk without the phrase: what the model must not wake on. */
        TALK("talk"),
    }

    /** Where it was said, kept in the file name so results can be split by it. */
    enum class Place(val tag: String, val label: String) {
        CLOSE("close", "Close"),
        FAR("far", "Across the room"),
        NOISY("noisy", "With noise"),
    }

    data class Progress(
        val seconds: Int,
        /** Bursts of speech that ended: for the phrase, roughly how many times it was said. */
        val said: Int,
        /** How many times the wake word's detector heard the phrase (or, in talk, woke by mistake). */
        val heard: Int,
        /** Current loudness, 0..1. */
        val level: Float,
    )

    data class Summary(val phrases: Int, val phraseSessions: Int, val talkSeconds: Int)

    private val _active = MutableStateFlow(false)

    /** A session is recording: the background listener stands aside, or it would open the overlay. */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress.asStateFlow()

    /** Only in a debuggable build, as with Try it's kept tries. */
    fun available(context: Context): Boolean = WakeTry.keepsTries(context)

    /**
     * Records until cancelled or [maxSeconds], then finishes the WAV. Caller holds RECORD_AUDIO.
     * A phrase session with nothing said is thrown away.
     */
    @SuppressLint("MissingPermission")
    suspend fun record(context: Context, kind: Kind, place: Place, maxSeconds: Int = MAX_SECONDS) = withContext(Dispatchers.IO) {
        val dir = context.getExternalFilesDir(DIR) ?: return@withContext
        val part = File(dir, "recording.part").apply { delete() }
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
        val bursts = Bursts()
        var samples = 0L
        var heard = 0
        try {
            check(record.state == AudioRecord.STATE_INITIALIZED) { "Microphone unavailable" }
            RandomAccessFile(part, "rw").use { out ->
                out.write(ByteArray(WAV_HEADER_BYTES))
                record.startRecording()
                val pcm = ShortArray(WakeWord.CHUNK_SAMPLES)
                val bytes = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                try {
                    while (samples < maxSeconds.toLong() * WakeWord.SAMPLE_RATE) {
                        coroutineContext.ensureActive()
                        val read = record.read(pcm, 0, pcm.size)
                        if (read <= 0) continue
                        bytes.clear()
                        var energy = 0.0
                        for (i in 0 until read) {
                            bytes.putShort(pcm[i])
                            energy += pcm[i].toDouble() * pcm[i]
                        }
                        out.write(bytes.array(), 0, read * 2)
                        samples += read
                        val rms = (sqrt(energy / read) / 32768).toFloat()
                        bursts.add(rms)
                        if (detector.accept(pcm, read) != null) heard++
                        _progress.value = Progress((samples / WakeWord.SAMPLE_RATE).toInt(), bursts.count, heard, minOf(1f, rms * 8))
                    }
                } finally {
                    out.seek(0)
                    out.write(wavHeader(samples * 2, sampleRate = WakeWord.SAMPLE_RATE, channels = 1, bitsPerSample = 16))
                }
            }
        } finally {
            runCatching {
                record.stop()
                record.release()
            }
            detector.close()
            _progress.value = null
            _active.value = false
            val seconds = (samples / WakeWord.SAMPLE_RATE).toInt()
            val keep = when (kind) {
                Kind.PHRASE -> bursts.count > 0
                Kind.TALK -> seconds >= MIN_TALK_SECONDS
            }
            // e.g. luna-far-1793272511518-n12-h9.wav, talk-close-1793272511518-95s.wav
            val name = when (kind) {
                Kind.PHRASE -> "${kind.tag}-${place.tag}-${System.currentTimeMillis()}-n${bursts.count}-h$heard.wav"
                Kind.TALK -> "${kind.tag}-${place.tag}-${System.currentTimeMillis()}-${seconds}s.wav"
            }
            if (keep) part.renameTo(File(dir, name)) else part.delete()
        }
    }

    fun summary(context: Context): Summary {
        val files = context.getExternalFilesDir(DIR)?.listFiles { f -> f.name.endsWith(".wav") }.orEmpty()
        val phrases = files.filter { it.name.startsWith(Kind.PHRASE.tag + "-") }
        val talk = files.filter { it.name.startsWith(Kind.TALK.tag + "-") }
        return Summary(
            phrases = phrases.sumOf { PHRASE_COUNT.find(it.name)?.groupValues?.get(1)?.toInt() ?: 0 },
            phraseSessions = phrases.size,
            talkSeconds = talk.sumOf { TALK_SECONDS.find(it.name)?.groupValues?.get(1)?.toInt() ?: 0 },
        )
    }

    fun deleteAll(context: Context) {
        context.getExternalFilesDir(DIR)?.listFiles()?.forEach { it.delete() }
    }

    /**
     * Counts bursts of speech in 100 ms loudness readings: louder than four times the quiet
     * between them (or 0.01), for 0.3–3 s, then 0.4 s of quiet. Only feedback for the user ("12
     * said"); the Mac cuts the sessions up itself.
     */
    internal class Bursts {
        var count = 0
            private set

        /** The background level: from the first reading, down at once, up only slowly. */
        private var floor = -1f
        private var loud = 0
        private var quiet = 0

        fun add(rms: Float) {
            if (floor < 0 || rms < floor) floor = rms
            val speech = rms > maxOf(floor * 4, 0.01f)
            if (speech) {
                // A steady noise switched on mid-session (a fan) stops counting as speech after
                // ten seconds or so; a phrase, even a long speech, is over before this has moved.
                floor += 0.003f * (rms - floor)
                loud++
                quiet = 0
            } else {
                floor = 0.95f * floor + 0.05f * rms
                if (loud > 0 && ++quiet >= END_QUIET_CHUNKS) {
                    if (loud in MIN_CHUNKS..MAX_CHUNKS) count++
                    loud = 0
                    quiet = 0
                }
            }
        }

        private companion object {
            const val MIN_CHUNKS = 3
            const val MAX_CHUNKS = 30
            const val END_QUIET_CHUNKS = 4
        }
    }

    private const val DIR = "wake-samples"
    private const val WAV_HEADER_BYTES = 44
    const val MAX_SECONDS = 180
    private const val MIN_TALK_SECONDS = 5
    private val PHRASE_COUNT = Regex("-n(\\d+)-")
    private val TALK_SECONDS = Regex("-(\\d+)s\\.wav$")
}
