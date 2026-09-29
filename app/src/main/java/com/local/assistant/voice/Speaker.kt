package com.local.assistant.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import com.local.assistant.data.prefs.SettingsStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * The assistant's voice: the phone's own text-to-speech engine, with one of its on-device voices
 * (see [VoiceChoice]), so speaking needs no network and no model of our own in memory.
 *
 * The engine is bound the first time something is said, not at app start, and text said before
 * it is ready waits for it. Everything here runs on the main thread.
 */
class Speaker(context: Context, private val settings: SettingsStore) {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private var tts: TextToSpeech? = null
    private var ready: CompletableDeferred<Boolean>? = null

    /** [tts] has started and is configured. */
    private var engineReady = false

    /** Said before the engine was up; spoken once it is. */
    private val queued = ArrayDeque<String>()

    /** Utterances handed to the engine and not yet finished. */
    private val pending = mutableSetOf<String>()
    private var nextId = 0L

    private val _speaking = MutableStateFlow(false)

    /** Something is being said, or waiting to be. */
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) = Unit

        override fun onDone(utteranceId: String) {
            main.post { finished(utteranceId) }
        }

        @Deprecated("Still abstract in the platform class")
        override fun onError(utteranceId: String) {
            main.post { finished(utteranceId) }
        }

        override fun onError(utteranceId: String, errorCode: Int) {
            Log.w(TAG, "Could not say $utteranceId: error $errorCode")
            main.post { finished(utteranceId) }
        }

        override fun onStop(utteranceId: String, interrupted: Boolean) {
            main.post { finished(utteranceId) }
        }
    }

    /** Says [text] after anything still being said. */
    fun speak(text: String) {
        if (text.isBlank()) return
        val engine = engine()
        if (engineReady) say(engine, text) else queued.addLast(text)
        updateSpeaking()
    }

    /** Stops at once, and drops anything waiting. */
    fun stop() {
        queued.clear()
        pending.clear()
        if (engineReady) tts?.stop()
        updateSpeaking()
    }

    /** The voices settings can offer; empty when the phone has no usable engine or voice. */
    suspend fun voiceOptions(): List<VoiceOption> {
        val engine = awaitEngine() ?: return emptyList()
        return VoiceChoice.options(voicesOf(engine).map(::info), Locale.getDefault())
    }

    /** The voice in use: the one picked in settings, or the automatic choice. */
    suspend fun currentVoice(): String? {
        val engine = awaitEngine() ?: return null
        return chooseVoice(engine)?.name
    }

    /** Settings changed the voice or its pace: use them from now on, and let the user hear it. */
    fun preview() {
        stop()
        if (engineReady) tts?.let(::configure)
        speak(SAMPLE)
    }

    /** Gives the engine back, e.g. when the system asks for memory. It binds again when needed. */
    fun release() {
        stop()
        tts?.shutdown()
        tts = null
        ready = null
        engineReady = false
    }

    private fun engine(): TextToSpeech {
        tts?.let { return it }
        val deferred = CompletableDeferred<Boolean>()
        ready = deferred
        lateinit var created: TextToSpeech
        // Posted: the engine can report failure from inside its own constructor.
        created = TextToSpeech(appContext) { status -> main.post { onInit(created, deferred, status) } }
        created.setOnUtteranceProgressListener(progress)
        tts = created
        return created
    }

    private fun onInit(engine: TextToSpeech, deferred: CompletableDeferred<Boolean>, status: Int) {
        if (tts !== engine) return // released while starting
        val ok = status == TextToSpeech.SUCCESS
        if (ok) {
            configure(engine)
            engineReady = true
            deferred.complete(true)
            while (queued.isNotEmpty()) say(engine, queued.removeFirst())
        } else {
            // No engine, or it failed: nothing to say it with. The next speak() tries again.
            Log.w(TAG, "Text-to-speech did not start (status $status)")
            deferred.complete(false)
            queued.clear()
            engine.shutdown()
            tts = null
            ready = null
        }
        updateSpeaking()
    }

    private suspend fun awaitEngine(): TextToSpeech? {
        val engine = engine()
        return engine.takeIf { ready?.await() == true && tts === engine }
    }

    private fun configure(engine: TextToSpeech) {
        engine.setAudioAttributes(ASSISTANT_AUDIO)
        chooseVoice(engine)?.let { chosen ->
            voicesOf(engine).firstOrNull { it.name == chosen.name }?.let { engine.voice = it }
        }
        engine.setSpeechRate(settings.speechRate)
    }

    private fun chooseVoice(engine: TextToSpeech): VoiceInfo? {
        val engineDefault = runCatching { engine.defaultVoice?.name }.getOrNull()
        return VoiceChoice.choose(voicesOf(engine).map(::info), settings.assistantVoice, engineDefault, Locale.getDefault())
    }

    private fun say(engine: TextToSpeech, text: String) {
        val id = "reply-${nextId++}"
        pending += id
        if (engine.speak(text, TextToSpeech.QUEUE_ADD, null, id) != TextToSpeech.SUCCESS) pending -= id
    }

    private fun finished(utteranceId: String) {
        pending -= utteranceId
        updateSpeaking()
    }

    private fun updateSpeaking() {
        _speaking.value = queued.isNotEmpty() || pending.isNotEmpty()
    }

    private companion object {
        const val TAG = "Speaker"
        const val SAMPLE = "Hi! This is how I'll sound when I answer you."

        val ASSISTANT_AUDIO: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        fun voicesOf(engine: TextToSpeech): List<Voice> = runCatching { engine.voices?.toList() }.getOrNull().orEmpty()

        fun info(voice: Voice) = VoiceInfo(
            name = voice.name,
            locale = voice.locale,
            quality = voice.quality,
            latency = voice.latency,
            needsNetwork = voice.isNetworkConnectionRequired,
            installed = voice.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true,
        )
    }
}

/**
 * Quiets other audio while the assistant listens and answers — music pauses, as it does for any
 * voice assistant — and gives it back when the overlay closes.
 */
class AudioFocus(context: Context) {

    private val audio = context.getSystemService(AudioManager::class.java)
    private var request: AudioFocusRequest? = null

    fun hold() {
        if (request != null) return
        val asked = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .build()
        if (audio.requestAudioFocus(asked) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) request = asked
    }

    fun release() {
        request?.let(audio::abandonAudioFocusRequest)
        request = null
    }
}
