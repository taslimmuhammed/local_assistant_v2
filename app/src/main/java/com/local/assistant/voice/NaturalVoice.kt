package com.local.assistant.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * The natural voice: the chosen one of [NaturalVoices] (Kokoro) run by sherpa-onnx
 * on the CPU, played through an [AudioTrack]. Each piece is synthesised on one thread while the one before it plays on
 * another, so after the first sentence the voice keeps ahead of itself: 1.8× faster than real
 * time on the OnePlus while the chat model writes (NaturalVoiceEvalTest).
 *
 * Kokoro is about 600 MB in memory, so the model loads only when wanted ([prepare], as the overlay
 * opens, or the first [speak]) and goes again after [IDLE_RELEASE_MS] of quiet. If it can't
 * load, what it was given goes to [fallback], the phone's own voice, and so does everything
 * after until the voice is installed again.
 */
class NaturalVoice(
    /** The voice to speak with and its unpacked pack; null when there is none. */
    private val chosen: () -> Chosen?,
    /** Pace, 1.0 normal: the same setting as the phone's voice. */
    private val rate: () -> Float,
    /** Text it couldn't say. Called on the main thread. */
    private val fallback: (String) -> Unit,
    /** [isSpeaking] changed. Called on the main thread. */
    private val onSpeakingChanged: () -> Unit,
    private val threads: Int = DEFAULT_THREADS,
) {

    private val main = Handler(Looper.getMainLooper())
    private val synthesis = Executors.newSingleThreadExecutor { Thread(it, "natural-voice") }
    private val playback = Executors.newSingleThreadExecutor { task ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            task.run()
        }, "natural-voice-out")
    }

    /** A voice ready to load: [voice] made from [dir]. */
    data class Chosen(val voice: NaturalVoices.Voice, val dir: File)

    // Synthesis thread only.
    private var tts: OfflineTts? = null
    private var loadedFrom: Chosen? = null

    // Playback thread only, apart from pause() in stop().
    @Volatile
    private var track: AudioTrack? = null
    private var trackRate = 0
    private var headAtStart = 0L
    private var framesWritten = 0L

    /** The engine wouldn't load this voice; the phone's voice speaks until the choice changes. */
    @Volatile
    private var failedOn: Chosen? = null

    /** Bumped by [stop]: work for an earlier generation is dropped. */
    private val generation = AtomicInteger()

    /** Pieces of text of this generation not yet heard to the end. */
    private val outstanding = AtomicInteger()

    @Volatile
    private var askedAt = 0L

    /** From the first piece of the last reply being handed over to its first sound, in ms. */
    @Volatile
    var startDelayMs = -1L
        private set

    val isSpeaking: Boolean get() = outstanding.get() > 0

    private val releaseWhenIdle = Runnable { releaseEngine() }

    /** Installed, and not known to fail. */
    fun usable(): Boolean = chosen()?.let { it != failedOn } ?: false

    /** Loads the model now, so the first sentence doesn't wait for it. */
    fun prepare() {
        main.removeCallbacks(releaseWhenIdle)
        synthesis.execute { chosen()?.let(::engine) }
    }

    /** Says [text] after anything already given. */
    fun speak(text: String) {
        main.removeCallbacks(releaseWhenIdle)
        val gen = generation.get()
        if (outstanding.getAndIncrement() == 0) {
            askedAt = SystemClock.elapsedRealtime()
            startDelayMs = -1
        }
        changed()
        synthesis.execute {
            if (generation.get() != gen) return@execute
            val current = chosen()
            val engine = current?.let(::engine)
            if (current == null || engine == null) {
                handBack(gen, text)
                return@execute
            }
            val started = SystemClock.elapsedRealtime()
            val sampleRate = engine.sampleRate()
            // An object, not a lambda: the native side calls `invoke(float[]): Integer` by name,
            // which Kotlin 2's lambdas don't have (only the erased `invoke(Object)`), and a
            // failed lookup aborts the whole app.
            val onAudio = object : (FloatArray) -> Int {
                override fun invoke(samples: FloatArray): Int {
                    if (generation.get() != gen) return 0
                    val copy = samples.copyOf()
                    playback.execute { play(gen, copy, sampleRate) }
                    return 1
                }
            }
            val audio = try {
                current.voice.generate(engine, current.dir, text, rate(), onAudio)
            } catch (e: Exception) {
                Log.w(TAG, "Could not say a sentence", e)
                null
            }
            if (audio == null) {
                handBack(gen, text)
                return@execute
            }
            val seconds = audio.samples.size / audio.sampleRate.toFloat()
            Log.i(TAG, "Made %.1f s of speech in %d ms".format(seconds, SystemClock.elapsedRealtime() - started))
            playback.execute { finished(gen) }
        }
    }

    /** Stops at once and drops anything waiting. */
    fun stop() {
        generation.incrementAndGet()
        outstanding.set(0)
        runCatching { track?.pause() }
        changed()
    }

    /** Frees the model and the audio output; they come back when next needed. */
    fun release() {
        stop()
        releaseEngine()
        playback.execute {
            track?.release()
            track = null
        }
    }

    private fun releaseEngine() {
        main.removeCallbacks(releaseWhenIdle)
        synthesis.execute {
            tts?.release()
            tts = null
            loadedFrom = null
        }
    }

    private fun engine(want: Chosen): OfflineTts? {
        if (want == failedOn) return null
        tts?.let { if (loadedFrom == want) return it }
        // Another voice was chosen: one model in memory at a time.
        tts?.release()
        tts = null
        loadedFrom = null
        // A missing file stops the native side with exit(), so it is checked here first.
        want.voice.pack.problem(want.dir)?.let {
            Log.w(TAG, "Not loading ${want.voice.id}: $it")
            failedOn = want
            return null
        }
        return try {
            val started = SystemClock.elapsedRealtime()
            OfflineTts(null, want.voice.config(want.dir, threads)).also {
                tts = it
                loadedFrom = want
                Log.i(TAG, "Loaded ${want.voice.id} in ${SystemClock.elapsedRealtime() - started} ms")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "${want.voice.id} did not load", e)
            failedOn = want
            null
        }
    }

    private fun handBack(gen: Int, text: String) {
        if (generation.get() != gen) return
        main.post {
            if (generation.get() != gen) return@post
            outstanding.decrementAndGet()
            fallback(text)
            changed()
        }
    }

    private fun play(gen: Int, samples: FloatArray, sampleRate: Int) {
        if (generation.get() != gen) return
        val out = track(sampleRate) ?: return
        if (out.playState != AudioTrack.PLAYSTATE_PLAYING) {
            // Paused by stop() or after the last reply: whatever it still held is stale.
            out.flush()
            out.play()
            headAtStart = head(out)
            framesWritten = 0
        }
        if (startDelayMs < 0) startDelayMs = SystemClock.elapsedRealtime() - askedAt
        var offset = 0
        while (offset < samples.size && generation.get() == gen) {
            val n = out.write(samples, offset, minOf(sampleRate / 10, samples.size - offset), AudioTrack.WRITE_BLOCKING)
            if (n <= 0) break
            offset += n
            framesWritten += n
        }
    }

    /** One piece is fully handed over: the last one waits until it has been heard. */
    private fun finished(gen: Int) {
        if (generation.get() != gen) return
        val out = track
        if (outstanding.get() == 1 && out != null) {
            val rate = trackRate.coerceAtLeast(1)
            val deadline = SystemClock.elapsedRealtime() + (framesWritten - (head(out) - headAtStart)) * 1000 / rate + DRAIN_SLACK_MS
            while (generation.get() == gen && head(out) - headAtStart < framesWritten && SystemClock.elapsedRealtime() < deadline) {
                Thread.sleep(DRAIN_POLL_MS)
            }
            // Nothing more came meanwhile: stop feeding the speaker silence.
            if (generation.get() == gen && outstanding.get() == 1) runCatching { out.pause() }
        }
        main.post {
            if (generation.get() != gen) return@post
            if (outstanding.decrementAndGet() == 0) main.postDelayed(releaseWhenIdle, IDLE_RELEASE_MS)
            changed()
        }
    }

    private fun track(sampleRate: Int): AudioTrack? {
        track?.let { if (trackRate == sampleRate) return it else it.release() }
        return try {
            val minimum = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                // A fifth of a second: enough to ride over a busy moment, short enough to stop at once.
                .setBufferSizeInBytes(maxOf(minimum, sampleRate / 5 * Float.SIZE_BYTES))
                .build()
                .also {
                    track = it
                    trackRate = sampleRate
                }
        } catch (e: Exception) {
            Log.w(TAG, "No audio output", e)
            track = null
            null
        }
    }

    private fun head(out: AudioTrack): Long = out.playbackHeadPosition.toLong() and 0xFFFFFFFFL

    private fun changed() {
        if (Looper.myLooper() == main.looper) onSpeakingChanged() else main.post(onSpeakingChanged)
    }

    companion object {
        private const val TAG = "NaturalVoice"

        /**
         * Of the phone's eight cores; the chat model runs on the GPU meanwhile. On the OnePlus,
         * 6 made speech 1.79× faster than real time while the chat model wrote, against 1.39×
         * with 4 and 0.90× with 2 (alone).
         */
        const val DEFAULT_THREADS = 6

        /** Quiet this long, the ~600 MB model goes; reloading takes about a second. */
        const val IDLE_RELEASE_MS = 120_000L

        private const val DRAIN_POLL_MS = 20L
        private const val DRAIN_SLACK_MS = 500L
    }
}
