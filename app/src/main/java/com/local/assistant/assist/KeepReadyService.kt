package com.local.assistant.assist

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.local.assistant.R
import com.local.assistant.appContainer
import com.local.assistant.data.prefs.SettingsStore
import com.local.assistant.wake.WakeWatch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import com.local.assistant.wake.WakeWord
import com.local.assistant.wake.WakeWordMode

/**
 * Keeps the app's process — and with it the loaded model — alive in the background, so holding
 * the power button gets an answer in about a second instead of after a ~15 s cold start.
 *
 * Android keeps a background app only as long as it likes, and OxygenOS freezes cached apps and
 * kills them early; a foreground service, with its notification, is the sanctioned way to stay.
 * This service only holds the process up. The model itself is loaded by `LlmService` and, while
 * the setting is on, not given back when the app goes to the background.
 *
 * It is also where the wake word listens ([WakeWatch]), when that is on: Android lets a
 * background app use the microphone only from a foreground service of the microphone type,
 * started while the app was in front.
 */
class KeepReadyService : Service() {

    private var wake: WakeWatch? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val settings = appContainer.settings
        if (intent?.action == ACTION_WAKE_WORD_OFF) {
            settings.wakeWord = WakeWordMode.OFF
            if (!wanted(settings)) {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        createChannel(this)
        val listening = settings.wakeWord != WakeWordMode.OFF && micGranted()
        val talk = PendingIntent.getActivity(
            this,
            0,
            Intent(Intent.ACTION_ASSIST).setClass(this, AssistActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_assistant)
            .setContentIntent(talk)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
        if (listening) {
            val off = PendingIntent.getService(
                this,
                1,
                Intent(this, KeepReadyService::class.java).setAction(ACTION_WAKE_WORD_OFF),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.setContentTitle("Say \u201c${WakeWord.PHRASE}\u201d to talk")
                .setContentText(
                    if (settings.wakeWord == WakeWordMode.WHILE_CHARGING) "Listening on the phone only, while charging." else "Listening on the phone only.",
                )
                .addAction(0, "Stop listening", off)
        } else {
            builder.setContentTitle("Assistant is ready").setContentText("Hold the power button to talk. Tap to talk now.")
        }
        var types = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        if (listening && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, builder.build(), types)
        } catch (e: RuntimeException) {
            // The microphone type is refused when started from the background: keep the app
            // ready without listening, until it is next opened.
            Log.w(TAG, "No microphone for the wake word now", e)
            val withoutMic = types and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE.inv()
            ServiceCompat.startForeground(this, NOTIFICATION_ID, builder.clearActions().build(), withoutMic)
            stopWake()
            return START_NOT_STICKY
        }
        if (listening) startWake() else stopWake()
        // Not sticky: if the system still kills the app for memory, it doesn't fight back by
        // restarting it; the next time the app or the overlay opens, this starts again.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopWake()
        super.onDestroy()
    }

    private fun startWake() {
        val container = appContainer
        val watch = wake ?: run {
            val recording = container.audioRecorder.state
                .map { it != null }
                .stateIn(container.appScope, SharingStarted.WhileSubscribed(), container.audioRecorder.isRecording)
            WakeWatch(this, container.settings, recording, onHeard = ::openAssistant)
        }.also { wake = it }
        watch.start()
        watch.update()
    }

    private fun stopWake() {
        wake?.stop()
        wake = null
    }

    /**
     * The overlay, as the power button opens it, with the screen turned on if it was off. Asked
     * of the assistant service: Android blocks a background app opening a screen itself, which
     * is left as the fallback for a phone where that's still allowed.
     */
    private fun openAssistant() {
        if (AssistantInteractionService.showForWakeWord()) return
        runCatching {
            startActivity(
                Intent(Intent.ACTION_ASSIST)
                    .setClass(this, AssistActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(AssistActivity.EXTRA_WAKE_WORD, true),
            )
        }.onFailure { Log.w(TAG, "Could not open the assistant for the wake word", it) }
    }

    private fun micGranted(): Boolean = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "KeepReadyService"
        private const val CHANNEL = "assistant_ready"
        private const val NOTIFICATION_ID = 7001
        private const val ACTION_WAKE_WORD_OFF = "com.local.assistant.action.WAKE_WORD_OFF"

        /** The service has something to do: keep the model ready, or listen for the wake word. */
        fun wanted(settings: SettingsStore): Boolean =
            settings.modelPath != null && (settings.keepAssistantReady || settings.wakeWord != WakeWordMode.OFF)

        /** Starts or stops the service to match the settings; while the app is in front. */
        fun update(context: Context) {
            if (wanted(context.appContainer.settings)) start(context) else stop(context)
        }

        /** Only works while the app is in the foreground, which is when it is called. */
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, KeepReadyService::class.java)) }
                .onFailure { Log.w(TAG, "Could not start keeping the assistant ready", it) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, KeepReadyService::class.java))
        }

        private fun createChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(CHANNEL, "Assistant ready", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shown while the assistant is kept loaded for the power button, or listens for its wake word. Turn them off in the app's settings."
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }
}
