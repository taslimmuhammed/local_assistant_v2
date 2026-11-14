package com.local.assistant.gallery

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.local.assistant.MainActivity
import com.local.assistant.R
import com.local.assistant.appContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.text.NumberFormat

/**
 * Keeps the first big photo index going once the app leaves the screen, with a notice that says
 * so (the user, 2026-11-13: "keep going, with a notice"). Out of sight the app otherwise pauses
 * it until the charger at night, so a new install's photos would take days.
 *
 * A foreground service of the data sync type (local processing of the user's files), started
 * while the app is in front and a backlog is left ([start]). It holds the CPU awake while it
 * runs, and stops when the photos and faces are done, when the user taps Stop, below 20% battery
 * off the charger, or when Android 15's daily allowance for this kind of service runs out
 * ([onTimeout]); the nightly job carries on from there. A warm phone pauses the index itself,
 * which the notice says.
 */
class IndexService : Service() {

    private var scope: CoroutineScope? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "Stopped from the notice")
            finish(pauseIndex = true)
            return START_NOT_STICKY
        }
        createChannel(this)
        val started = runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification(this, null, null),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
            )
        }
        if (started.isFailure) {
            // Not allowed now (started too late after leaving the screen): the night job does it.
            Log.w(TAG, "Could not start in the foreground", started.exceptionOrNull())
            stopSelf()
            return START_NOT_STICKY
        }
        if (scope != null) return START_NOT_STICKY
        running = true
        wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LocalAssistant:photo-index")
            .apply { acquire(WAKE_LIMIT_MS) }
        val container = appContainer
        val index = container.galleryIndex
        val faces = container.faceIndex
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main).also { scope = it }
        index.start()
        scope.launch {
            var sawRunning = false
            combine(index.status, faces.status) { photos, people -> photos to people }
                .distinctUntilChanged()
                .collect { (photos, people) ->
                    if (photos.running) sawRunning = true
                    val facesLeft = if (faces.isOn) people.photosLeft else 0
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(this@IndexService, photos, facesLeft))
                    when {
                        sawRunning && !photos.running -> {
                            Log.i(TAG, "Done: ${photos.remaining} photos and $facesLeft faces left")
                            finish(pauseIndex = false)
                        }
                        lowBattery() -> {
                            Log.i(TAG, "Stopping: battery low")
                            finish(pauseIndex = true)
                        }
                    }
                }
        }
        return START_NOT_STICKY
    }

    /** Android 15 and up: the day's allowance for data sync services is used up. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.i(TAG, "Stopping: Android's daily time for this is used up")
        finish(pauseIndex = true)
    }

    private fun finish(pauseIndex: Boolean) {
        running = false
        // Stopped out of sight, the index waits for the charger at night again; in sight it
        // carries on, as it always has.
        val container = appContainer
        if (pauseIndex && !container.appForeground.isForeground) CoroutineScope(Dispatchers.Default).launch { container.galleryIndex.pause() }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun lowBattery(): Boolean {
        val battery = getSystemService(BatteryManager::class.java) ?: return false
        return !battery.isCharging && battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) in 0 until LOW_BATTERY_PERCENT
    }

    override fun onDestroy() {
        running = false
        scope?.cancel()
        scope = null
        wake?.takeIf { it.isHeld }?.release()
        wake = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "IndexService"
        private const val CHANNEL = "indexing"
        private const val NOTIFICATION_ID = 7004
        private const val ACTION_STOP = "com.local.assistant.gallery.STOP_INDEXING"

        /** Photos (and faces) left that are worth a notice: fewer are done in a minute or two in front. */
        const val BACKLOG = 200

        private const val LOW_BATTERY_PERCENT = 20

        /** Released sooner as indexing ends; a library of tens of thousands takes hours. */
        private const val WAKE_LIMIT_MS = 6 * 60 * 60_000L

        /** The service is up: leaving the screen doesn't pause the index. */
        @Volatile
        var running = false
            private set

        /** Starts it, while the app is in front; does nothing if Android won't allow it now. */
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, IndexService::class.java)) }
                .onFailure { Log.w(TAG, "Not started", it) }
        }

        private fun createChannel(context: Context) {
            val channel = NotificationChannel(CHANNEL, "Indexing photos", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Photos and faces being indexed on the phone after the app is closed."
                setShowBadge(false)
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        private fun notification(context: Context, photos: GalleryIndex.Status?, facesLeft: Int?): Notification {
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val stop = PendingIntent.getService(
                context,
                1,
                Intent(context, IndexService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val numbers = NumberFormat.getIntegerInstance()
            val left = (photos?.remaining ?: 0).coerceAtLeast(facesLeft ?: 0)
            val text = when {
                photos == null || photos.total == 0 -> "Getting started"
                photos.cooling -> "Paused while the phone cools · ${numbers.format(left)} left"
                else -> "${numbers.format(left)} left · on this phone only"
            }
            val builder = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification_assistant)
                .setContentTitle("Indexing photos and faces")
                .setContentText(text)
                .setContentIntent(open)
                .addAction(0, "Stop", stop)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            if (photos != null && photos.total > 0) builder.setProgress(photos.total, photos.indexed, false) else builder.setProgress(0, 0, true)
            return builder.build()
        }
    }
}
