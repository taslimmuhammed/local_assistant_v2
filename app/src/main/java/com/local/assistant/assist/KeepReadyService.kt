package com.local.assistant.assist

import android.app.Notification
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
import com.local.assistant.MainActivity
import com.local.assistant.reminders.PlaceFences
import com.local.assistant.wake.WakeWatch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import com.local.assistant.wake.WakeWord
import com.local.assistant.wake.WakeWordMode

/**
 * A foreground service, with the notification Android requires of one, for the two things that
 * still need it.
 *
 * The wake word ([WakeWatch]), when that is on: Android lets a background app use the microphone
 * only from a foreground service of the microphone type, started while the app was in front.
 *
 * And a place reminder ([PlaceFences.watching]) while the app isn't the phone's assistant: Play
 * Services tells the app of an arrival, but with the screen off the app got no GPS fix, and the
 * alarm rang only once the screen came on again (the user, 9 Nov 2026).
 *
 * As the phone's assistant, neither keeping the model ready for the power button nor a place
 * reminder needs it: the system keeps [AssistantInteractionService] bound as a foreground service
 * with its capabilities (`CR FGS CAPS` in `dumpsys activity services`), which holds the app up as
 * this service did. It used to run for "Keep the assistant ready" too, and its notification was
 * always there; that was dropped as annoying (the user, 10 Nov 2026).
 */
class KeepReadyService : Service() {

    private var wake: WakeWatch? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val settings = appContainer.settings
        if (intent?.action == ACTION_WAKE_WORD_OFF) {
            settings.wakeWord = WakeWordMode.OFF
            if (!wanted(this)) {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        createChannel(this)
        val listening = listens(this)
        var types = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        if (!listening && !watchesPlaces(this)) {
            // Started for something that has gone since (the wake word turned off, the last place
            // reminder done): Android still wants the notification shown before the service stops.
            runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(listening = false), types) }
            stopSelf()
            return START_NOT_STICKY
        }
        if (listening && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(listening), types)
        } catch (e: RuntimeException) {
            // The microphone type is refused when started from the background: listen from the
            // next time the app is opened. A place reminder still wants the service without it.
            Log.w(TAG, "No microphone for the wake word now", e)
            stopWake()
            if (!watchesPlaces(this)) {
                stopSelf()
                return START_NOT_STICKY
            }
            val withoutMic = types and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE.inv()
            // Not allowed in the foreground at all now (a restart from the background): stop
            // rather than crash; the next time the app opens, this starts again.
            runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(listening = false), withoutMic) }
                .onFailure {
                    Log.w(TAG, "Not allowed to run in the foreground now", it)
                    stopSelf()
                }
            return START_NOT_STICKY
        }
        if (listening) startWake() else stopWake()
        // Sticky: OxygenOS kills the app when it is swiped out of the recent apps, and the wake
        // word should still answer afterwards (the user, 2026-10-30), and an arrival still ring.
        // Android restarts the service, and as the phone's assistant app the app may still use the
        // microphone from the background. A restart doesn't load the chat model, only the wake
        // word's few MB.
        return START_STICKY
    }

    /** Listening, it says so and how to stop; otherwise it is there for a place reminder. */
    private fun notification(listening: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            if (listening) {
                Intent(Intent.ACTION_ASSIST).setClass(this, AssistActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            } else {
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_assistant)
            .setContentIntent(open)
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
            builder.setContentTitle("Say “${WakeWord.PHRASE}” to talk")
                .setContentText(
                    if (appContainer.settings.wakeWord == WakeWordMode.WHILE_CHARGING) "Listening on the phone only, while charging." else "Listening on the phone only.",
                )
                .addAction(0, "Stop listening", off)
        } else {
            builder.setContentTitle("Watching for your place reminders").setContentText("So they ring as you arrive, with the screen off too.")
        }
        return builder.build()
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

    companion object {
        private const val TAG = "KeepReadyService"
        private const val CHANNEL = "assistant_ready"
        private const val NOTIFICATION_ID = 7001
        private const val ACTION_WAKE_WORD_OFF = "com.local.assistant.action.WAKE_WORD_OFF"

        /** The wake word is on, with a model to answer it and the microphone allowed. */
        private fun listens(context: Context): Boolean {
            val settings = context.appContainer.settings
            return settings.modelPath != null && settings.wakeWord != WakeWordMode.OFF &&
                context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        }

        /** A place reminder is set, and the system isn't already keeping the app up as the phone's assistant. */
        private fun watchesPlaces(context: Context): Boolean =
            PlaceFences.watching(context) && !AssistantRole.serviceActive(context)

        fun wanted(context: Context): Boolean = listens(context) || watchesPlaces(context)

        /**
         * Starts or stops the service to match the settings and place reminders; while the app
         * is in front, as a place reminder rings or is set again from its alarm, and as the
         * system starts or stops the assistant service.
         */
        fun update(context: Context) {
            if (wanted(context)) start(context) else stop(context)
        }

        /**
         * Works while the app is in the foreground, which is when it is mostly called, and from
         * the assistant service (Android lets the assistant app start the microphone from the
         * background).
         */
        private fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, KeepReadyService::class.java)) }
                .onFailure { Log.w(TAG, "Could not start the wake word or place reminder service", it) }
        }

        private fun stop(context: Context) {
            context.stopService(Intent(context, KeepReadyService::class.java))
        }

        private fun createChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(CHANNEL, "Wake word and place reminders", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shown while the assistant listens for its wake word, or, when it isn't the phone's digital assistant, watches for your place reminders."
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }
}
