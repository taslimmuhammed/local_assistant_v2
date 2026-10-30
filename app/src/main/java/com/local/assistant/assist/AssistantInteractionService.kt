package com.local.assistant.assist

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * The app as the phone's digital assistant, the way Android runs one: the system keeps this
 * service bound while the app holds the assistant role, and holding the power button asks it
 * for a session, which opens [AssistActivity] — the same overlay as before.
 *
 * It exists for the wake word. A background app may not open a screen by itself (Android 16
 * blocked it on the test phone); the assistant may, by asking for its own session
 * ([showForWakeWord]).
 */
class AssistantInteractionService : VoiceInteractionService() {

    override fun onReady() {
        super.onReady()
        running = this
    }

    override fun onShutdown() {
        if (running === this) running = null
        super.onShutdown()
    }

    companion object {
        private const val TAG = "AssistantInteraction"

        @Volatile
        private var running: AssistantInteractionService? = null

        /**
         * Opens the overlay for the wake word. False when the app isn't the phone's assistant (the
         * system then doesn't run this service) or the system refused.
         */
        fun showForWakeWord(): Boolean {
            val service = running ?: return false
            return runCatching {
                service.showSession(Bundle().apply { putBoolean(AssistActivity.EXTRA_WAKE_WORD, true) }, 0)
                true
            }.onFailure { Log.w(TAG, "Could not show the assistant", it) }.getOrDefault(false)
        }
    }
}

/** Makes the sessions the system asks for. */
class AssistantSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AssistantSession(this)
}

/**
 * A session with no window of its own: it opens the overlay and steps aside. Called for the
 * power button and for the wake word alike.
 */
class AssistantSession(context: Context) : VoiceInteractionSession(context) {

    override fun onCreate() {
        super.onCreate()
        // The overlay is the UI; the session's own layer would sit over it and take the touches.
        setUiEnabled(false)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val wakeWord = args?.getBoolean(AssistActivity.EXTRA_WAKE_WORD, false) == true
        startAssistantActivity(
            Intent(Intent.ACTION_ASSIST)
                .setClass(context, AssistActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(AssistActivity.EXTRA_WAKE_WORD, wakeWord),
        )
        hide()
    }
}

/**
 * Required of a digital assistant by Android, which lists it among the phone's voice input
 * services. The app's voice goes to its own model instead, so this declines every request.
 */
class AssistantRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        runCatching { listener?.error(SpeechRecognizer.ERROR_CLIENT) }
    }

    override fun onCancel(listener: Callback?) = Unit

    override fun onStopListening(listener: Callback?) = Unit
}
