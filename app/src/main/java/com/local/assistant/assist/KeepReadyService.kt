package com.local.assistant.assist

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.local.assistant.R
import com.local.assistant.appContainer
import com.local.assistant.MainActivity
import com.local.assistant.data.prefs.SettingsStore
import com.local.assistant.reminders.PlaceFences
import com.local.assistant.reminders.PlaceReminderReceiver
import com.local.assistant.wake.WakeWatch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
 *
 * And it runs while a place reminder is set ([PlaceFences.watching]), without loading the model:
 * Play Services tells the app of an arrival, but with the screen off OxygenOS had frozen the app,
 * and the alarm rang only once the screen came on again (the user, 9 Nov 2026).
 */
class KeepReadyService : Service() {

    private var wake: WakeWatch? = null

    /**
     * Location back on: Play Services dropped every geofence when it went off, and they were set
     * again only when the app next came to the front. The user turns location off and on during
     * the day (13:20 off, 16:46 on, 17:07 off on 9 Nov 2026), so a reminder could go unwatched
     * for hours. Android tells this only to a running app, which this service keeps it.
     */
    private val locationSwitch = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (context.getSystemService(LocationManager::class.java)?.isLocationEnabled != true) return
            val container = appContainer
            container.appScope.launch {
                runCatching { PlaceReminderReceiver.watchAll(container) }
                    .onSuccess { Log.i(TAG, "Location back on: watching $it place reminders again") }
                    .onFailure { Log.w(TAG, "Watching place reminders again failed", it) }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ContextCompat.registerReceiver(this, locationSwitch, IntentFilter(LocationManager.MODE_CHANGED_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

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
        val listening = settings.wakeWord != WakeWordMode.OFF && micGranted()
        val places = PlaceFences.watching(this)
        val onlyPlaces = places && !wanted(settings)
        val talk = PendingIntent.getActivity(
            this,
            0,
            if (onlyPlaces) {
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            } else {
                Intent(Intent.ACTION_ASSIST).setClass(this, AssistActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
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
        } else if (onlyPlaces) {
            builder.setContentTitle("Watching for your place reminders").setContentText("So they ring as you arrive, with the screen off too.")
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
            stopWake()
            // Not allowed in the foreground at all now (a restart from the background): stop
            // rather than crash; the next time the app opens, this starts again.
            runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, builder.clearActions().build(), withoutMic) }
                .onFailure {
                    Log.w(TAG, "Not allowed to run in the foreground now", it)
                    stopSelf()
                }
            return START_NOT_STICKY
        }
        if (listening) startWake() else stopWake()
        // Listening, sticky: OxygenOS kills the app when it is swiped out of the recent apps, and
        // the wake word should still answer afterwards (the user, 2026-10-30). Android restarts
        // the service, and as the phone's assistant app the app may still use the microphone from
        // the background. A restart doesn't load the chat model, only the wake word's few MB.
        // Watching for a place, sticky too: an arrival should still ring after a swipe from the
        // recent apps, and a restart is the app without its model loaded.
        // Otherwise not sticky: if the system kills the app for memory, it doesn't fight back by
        // restarting it; the next time the app or the overlay opens, this starts again.
        return if (listening || places) START_STICKY else START_NOT_STICKY
    }

    override fun onDestroy() {
        stopWake()
        unregisterReceiver(locationSwitch)
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

        /** The settings give the service something to do: keep the model ready, or listen for the wake word. */
        fun wanted(settings: SettingsStore): Boolean =
            settings.modelPath != null && (settings.keepAssistantReady || settings.wakeWord != WakeWordMode.OFF)

        /** The settings do, or a place reminder is set. */
        fun wanted(context: Context): Boolean = wanted(context.appContainer.settings) || PlaceFences.watching(context)

        /**
         * Starts or stops the service to match the settings and place reminders; while the app
         * is in front, or as a place reminder rings or is set again from its alarm.
         */
        fun update(context: Context) {
            if (wanted(context)) start(context) else stop(context)
        }

        /**
         * Works while the app is in the foreground, which is when it is mostly called, and from
         * the assistant service (Android lets the assistant app start the microphone from the
         * background).
         */
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
                description = "Shown while the assistant is kept loaded for the power button, listens for its wake word, or watches for your place reminders."
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }
}
