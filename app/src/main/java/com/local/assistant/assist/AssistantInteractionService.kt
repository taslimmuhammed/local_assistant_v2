package com.local.assistant.assist

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.util.Log
import kotlin.concurrent.thread

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
        // Android binds this after the app's process was killed (swiped out of the recent apps,
        // say): start listening for the wake word again without waiting for the app to be opened.
        KeepReadyService.update(this)
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
        fun showForWakeWord(): Boolean = show(
            Bundle().apply { putBoolean(AssistActivity.EXTRA_WAKE_WORD, true) },
            // As the power button does: "share this with…" needs what was on the screen.
            VoiceInteractionSession.SHOW_WITH_SCREENSHOT,
        )

        /**
         * Opens "While you were away" by itself, just after an unlock, the way the wake word opens
         * the overlay. False when the app isn't the phone's assistant or the system refused.
         */
        fun showBrief(): Boolean = show(Bundle().apply { putBoolean(EXTRA_BRIEF, true) })

        private fun show(args: Bundle, flags: Int = 0): Boolean {
            val service = running ?: return false
            return runCatching {
                service.showSession(args, flags)
                true
            }.onFailure { Log.w(TAG, "Could not show the assistant", it) }.getOrDefault(false)
        }

        internal const val EXTRA_BRIEF = "com.local.assistant.extra.BRIEF"
    }
}

/** Makes the sessions the system asks for. */
class AssistantSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AssistantSession(this)
}

/**
 * A session with no window of its own: it opens the overlay and steps aside. Called for the
 * power button and for the wake word alike.
 *
 * Android takes a screenshot of the screen underneath as the session shows, and hands it over a
 * moment later — but cancels it if the session has stepped aside by then. So it waits for the
 * screenshot ([ScreenCapture]), for [SCREENSHOT_WAIT_MS] at most, before it does.
 */
class AssistantSession(context: Context) : VoiceInteractionSession(context) {

    private val main = Handler(Looper.getMainLooper())
    private val giveUp = Runnable { stepAside() }
    private var waitingForScreen = false

    override fun onCreate() {
        super.onCreate()
        // The overlay is the UI; the session's own layer would sit over it and take the touches.
        setUiEnabled(false)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        if (args?.getBoolean(AssistantInteractionService.EXTRA_BRIEF, false) == true) {
            startAssistantActivity(com.local.assistant.away.BriefActivity.intent(context, newOnly = true))
            hide()
            return
        }
        // Whatever was kept last time was another screen.
        ScreenCapture.discard(context)
        val wakeWord = args?.getBoolean(AssistActivity.EXTRA_WAKE_WORD, false) == true
        startAssistantActivity(
            Intent(Intent.ACTION_ASSIST)
                .setClass(context, AssistActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(AssistActivity.EXTRA_WAKE_WORD, wakeWord),
        )
        // Over the lock screen there is nothing of the user's to see.
        val locked = context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        if (showFlags and SHOW_WITH_SCREENSHOT != 0 && !locked) {
            waitingForScreen = true
            main.postDelayed(giveUp, SCREENSHOT_WAIT_MS)
        } else {
            hide()
        }
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        if (!waitingForScreen) return
        stepAside()
        // None when "Use screenshot" is off in the assistant settings, or the app on screen forbids them.
        if (screenshot == null) {
            Log.i(TAG, "No screenshot came")
            return
        }
        val app = context.applicationContext
        thread(name = "ScreenCapture") { ScreenCapture.keep(app, screenshot) }
    }

    override fun onHide() {
        waitingForScreen = false
        main.removeCallbacks(giveUp)
        super.onHide()
    }

    private fun stepAside() {
        waitingForScreen = false
        main.removeCallbacks(giveUp)
        hide()
    }

    private companion object {
        const val TAG = "AssistantSession"

        /** The screenshot comes in about a tenth of that; past it, it isn't coming. */
        const val SCREENSHOT_WAIT_MS = 2_000L
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
