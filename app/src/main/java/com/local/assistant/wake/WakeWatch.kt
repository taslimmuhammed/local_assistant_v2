package com.local.assistant.wake

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.app.KeyguardManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.local.assistant.data.prefs.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Turns the [WakeListener] on and off as the phone's state changes ([WakeConditions]): the
 * screen, the lock, the charger, calls, and the app's own recording. Lives in the foreground
 * service that keeps the app alive (`KeepReadyService`), on the main thread.
 */
class WakeWatch(
    private val context: Context,
    private val settings: SettingsStore,
    /** The app's recorder is recording. */
    private val recording: StateFlow<Boolean>,
    /** The phrase was heard; the microphone is free again. */
    private val onHeard: () -> Unit,
) {

    // The microphone can be busy for a moment (another app starting to record): look again soon.
    private val listener = WakeListener(context, onFailed = { main.postDelayed(afterQuiet, RETRY_AFTER_FAILURE_MS) }) {
        Log.i(TAG, "Heard the wake word")
        // The overlay takes a moment to come to the front, and wants the microphone then.
        quietUntil = SystemClock.elapsedRealtime() + QUIET_AFTER_WAKE_MS
        onHeard()
        update()
    }
    private val main = Handler(Looper.getMainLooper())
    private var quietUntil = 0L

    /**
     * Looks again once the quiet after a wake is over. Rescheduled by every [update] inside it:
     * a single post at the end could land a millisecond early, find it still quiet, and leave the
     * listener off for good when nothing else changes (seen on the phone, 2026-10-29).
     */
    private val afterQuiet = Runnable { update() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private val keyguard = context.getSystemService(KeyguardManager::class.java)

    private var lastListen: Boolean? = null
    private var started = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = update()
    }

    private val modeListener = AudioManager.OnModeChangedListener { update() }

    fun start() {
        if (started) return
        started = true
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audio.addOnModeChangedListener(ContextCompat.getMainExecutor(context), modeListener)
        scope.launch { recording.collect { update() } }
        scope.launch { WakeTry.active.collect { update() } }
        scope.launch { WakeSamples.active.collect { update() } }
        update()
    }

    fun stop() {
        if (!started) return
        started = false
        scope.cancel()
        main.removeCallbacksAndMessages(null)
        runCatching { context.unregisterReceiver(receiver) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audio.removeOnModeChangedListener(modeListener)
        listener.release()
    }

    /** Looks at the phone's state again and listens, or doesn't. */
    fun update() {
        if (!started) return
        val now = SystemClock.elapsedRealtime()
        if (now < quietUntil) {
            main.removeCallbacks(afterQuiet)
            main.postDelayed(afterQuiet, quietUntil - now + QUIET_MARGIN_MS)
        }
        val why = if (now < quietUntil) "just heard it" else WakeConditions.whyNot(state())
        val listen = why == null
        if (listen != lastListen) Log.i(TAG, if (listen) "Listening" else "Not listening: $why")
        lastListen = listen
        if (listen && !listener.isListening) listener.start()
        if (!listen && listener.isListening) listener.stop()
    }

    private fun state() = WakeConditions.State(
        mode = settings.wakeWord,
        micGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
        charging = charging(),
        // Screen off counts as locked: the lock follows a few seconds later, with no broadcast.
        locked = keyguard.isKeyguardLocked || !power.isInteractive,
        lockScreenAllowed = settings.assistantOnLockScreen,
        recording = recording.value,
        // Try it, or a training sample being recorded: either has the microphone.
        trying = WakeTry.active.value || WakeSamples.active.value,
        inCall = audio.mode == AudioManager.MODE_IN_CALL || audio.mode == AudioManager.MODE_IN_COMMUNICATION,
    )

    private fun charging(): Boolean {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        return battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    }

    private companion object {
        const val TAG = "WakeWatch"
        const val QUIET_AFTER_WAKE_MS = 3_000L
        const val QUIET_MARGIN_MS = 50L
        const val RETRY_AFTER_FAILURE_MS = 5_000L
    }
}
