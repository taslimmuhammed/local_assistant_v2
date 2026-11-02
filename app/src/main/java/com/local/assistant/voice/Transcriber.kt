package com.local.assistant.voice

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.annotation.MainThread
import com.local.assistant.media.MediaLimits
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.Locale

/**
 * Writes a voice message down as it is recorded, with the phone's own on-device recogniser (on
 * the test phone, Google's, the one Gboard and the Google app use): no model of ours, nothing
 * sent off the phone ([SpeechRecognizer.createOnDeviceSpeechRecognizer] never does).
 *
 * The recorder's own audio is fed to it as it comes ([RecognizerIntent.EXTRA_AUDIO_SOURCE]), so
 * the words are ready about when the user stops, and the recording stays what the chat plays
 * back. Android 13 and later, where an app may hand the recogniser its audio; elsewhere, or when
 * the phone has no on-device recogniser, there is no [Session] and voice messages go as audio, as
 * before.
 */
class Transcriber(private val context: Context, private val scope: CoroutineScope) {

    /** Whether this phone can write voice messages down. */
    val available: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    /**
     * Starts writing down a recording about to begin; null when it can't. On the main thread, as
     * the recogniser asks. [hints]: phrases to lean towards ([RecognizerIntent.EXTRA_BIASING_STRINGS]);
     * [alternatives]: how many guesses to ask for each stretch of speech.
     */
    @MainThread
    fun start(locale: Locale = Locale.getDefault(), hints: List<String> = emptyList(), alternatives: Int = 1): Session? {
        if (!available) return null
        return runCatching { Session(locale, hints, alternatives) }
            .onFailure { Log.w(TAG, "No transcript: the recogniser wouldn't start", it) }
            .getOrNull()
    }

    /** One recording's words. [feed] it the audio, then [finish] for what was said. */
    inner class Session internal constructor(locale: Locale, hints: List<String>, alternatives: Int) {

        private val pipe = ParcelFileDescriptor.createPipe()
        private val out = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])

        /** The recorder never waits on the recogniser: its chunks queue here, and go down the pipe from this. */
        private val chunks = Channel<ByteArray>(Channel.UNLIMITED)
        private val writer = scope.launch(Dispatchers.IO) {
            try {
                for (chunk in chunks) out.write(chunk)
            } catch (e: IOException) {
                // The recogniser stopped reading (an error): what it heard is in [said].
            } finally {
                runCatching { out.close() }
            }
        }

        /** Each segment's best guess, in order: written by the recogniser's callbacks, read by [finish]. */
        private val said: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())

        /** Each segment's guesses, best first. */
        val guesses: MutableList<List<String>> = java.util.Collections.synchronizedList(ArrayList())
        private val done = CompletableDeferred<Unit>()
        private val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        private val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, MediaLimits.AUDIO_SAMPLE_RATE_HZ)
            // The whole recording, pauses and all, not just the first sentence.
            putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
            if (hints.isNotEmpty()) putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(hints))
            if (alternatives > 1) putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, alternatives)
        }

        init {
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onSegmentResults(segmentResults: Bundle) = keep(segmentResults)
                override fun onEndOfSegmentedSession() {
                    done.complete(Unit)
                }
                override fun onResults(results: Bundle) {
                    keep(results)
                    done.complete(Unit)
                }
                override fun onError(error: Int) {
                    Log.i(TAG, "Recogniser error $error; keeping ${said.size} segments")
                    if (error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) fetchLanguage()
                    // Stops the writer if the recogniser stopped reading.
                    runCatching { pipe[0].close() }
                    done.complete(Unit)
                }
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            recognizer.startListening(intent)
        }

        /** A chunk of 16 kHz mono 16-bit PCM, from any thread; never blocks. */
        fun feed(pcm: ByteArray, length: Int) {
            chunks.trySend(pcm.copyOf(length))
        }

        /** What was said, or null when nothing was made out. The recording is over. */
        suspend fun finish(): String? {
            // A moment of quiet after the last word: without it the recogniser left that word out
            // when the audio stopped right on it ("turn on dark" for "turn on dark mode", 14 of 34
            // clips on the phone).
            chunks.trySend(ByteArray(TRAILING_SILENCE_BYTES))
            chunks.close()
            val started = System.currentTimeMillis()
            val heard = withTimeoutOrNull(FINISH_TIMEOUT_MS) {
                writer.join()
                done.await()
            } != null
            val words = synchronized(said) { said.joinToString(" ") }.replace(Regex("\\s+"), " ").trim()
            Log.i(TAG, "${if (heard) "Transcribed" else "Timed out"} in ${System.currentTimeMillis() - started} ms after the recording: ${words.length} characters")
            release()
            // "Sarlamat 4:30 p.m. today" was "set alarm at 4:30 p.m. today".
            val meant = SpokenCommands.repair(words)
            if (meant != words) Log.i(TAG, "A garbled alarm request put right")
            return meant.ifEmpty { null }
        }

        /** The recording was thrown away. */
        fun cancel() {
            chunks.close()
            writer.cancel()
            release()
        }

        private fun keep(results: Bundle) {
            val all = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty().map { it.trim() }.filter { it.isNotEmpty() }
            all.firstOrNull()?.let(said::add)
            if (all.isNotEmpty()) guesses += all
        }

        /** The language's pack, for next time: the phone downloads it itself. */
        private fun fetchLanguage() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                runCatching { recognizer.triggerModelDownload(Intent(intent).apply { removeExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE) }) }
            }
        }

        private fun release() {
            scope.launch(Dispatchers.Main) {
                runCatching { recognizer.destroy() }
                runCatching { pipe[0].close() }
            }
        }
    }

    companion object {
        private const val TAG = "Transcriber"

        /**
         * Requests the recogniser got wrong for the user: "set alarm at" came out "sarlamat". Not
         * used: on the user's 21 alarm recordings (2 Nov 2026, `TranscriberDeviceTest -e compare
         * true`) these hints changed no word, and no other guess held "set alarm" either; asking for
         * 5 guesses once gave nothing at all. [SpokenCommands] puts the two misheard ones right.
         */
        val COMMAND_HINTS = listOf("set alarm", "set an alarm", "set alarm at", "set alarm for", "set a timer", "remind me", "wake me up", "Luna")

        /** How long the words may take after the recording ends before the audio goes alone. */
        private const val FINISH_TIMEOUT_MS = 4_000L

        /** 0.7 s of silence, 16 kHz mono 16-bit. */
        private const val TRAILING_SILENCE_BYTES = MediaLimits.AUDIO_SAMPLE_RATE_HZ * 2 * 7 / 10
    }
}
