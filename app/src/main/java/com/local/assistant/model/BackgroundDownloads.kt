package com.local.assistant.model

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.local.assistant.MainActivity
import com.local.assistant.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch

/** What is downloading now, for the notification. */
data class DownloadState(
    val title: String,
    /** 0..1, or null while it can't be told (unpacking without a size, the start). */
    val fraction: Float?,
    val detail: String,
    /** Fetched, and being checked or unpacked: no network traffic while this runs. */
    val finishing: Boolean = false,
)

/**
 * Keeps a model or voice download going with the app out of sight. The download itself runs in
 * the app as before ([ModelManager], the voice installer); without something the system counts as
 * work, OxygenOS froze the app a few seconds after it left the screen, and a ~3.7 GB model stopped
 * until it was opened again.
 *
 * From Android 14 that something is a user-initiated data transfer job ([DownloadJob]): made for
 * downloads the user asked for, and without the data-sync foreground service Play wants a
 * declaration and a video for (the manifest removes that permission). Older phones get a plain
 * foreground service ([DownloadService]), which needs no type there. Either shows the progress in
 * a notification, gone when the download ends.
 *
 * The job alone doesn't see it through on OxygenOS: its freezer spared the app only while data
 * was flowing, and once the Kokoro archive was in and being unpacked it cut the app's network,
 * the job stopped for its network constraint and the app froze halfway (10 Nov 2026). So the
 * check or unpack after a download runs in a short foreground service ([FinishService]), which
 * Android 14 gives up to three minutes and Play asks no declaration for. Android allows starting
 * one only with the app in front; reached in the background, the step waits for the app to be
 * opened again, with everything fetched kept.
 *
 * And the app can be killed outright (swiped from the recent apps, or short of memory), or the
 * connection drop. A download started is remembered ([WantedDownloads]) until it is here: the job
 * asks to be run again while one is, and picks it up when Android does, as the service does when
 * Android restarts it, and the app as it comes to the front.
 */
object BackgroundDownloads {

    private const val TAG = "BackgroundDownloads"
    private const val CHANNEL = "downloads"
    internal const val NOTIFICATION_ID = 7002
    private const val JOB_ID = 7002

    /** A download ended: the next one (the photo model after the chat model) gets this long to begin. */
    private const val SETTLE_MS = 3_000L

    /** Waiting this long, after being run again, for a download picked up to report itself. */
    private const val PICKUP_MS = 15_000L

    private val _state = MutableStateFlow<DownloadState?>(null)
    internal val state: StateFlow<DownloadState?> = _state.asStateFlow()

    /** The job or the service is running: a new download goes under it, not a second one. */
    @Volatile
    internal var keeping = false

    /** Picks up the downloads started and not finished; true when there was one. */
    internal var resume: () -> Boolean = { false }
        private set

    /** A download is started and not finished. */
    internal var pending: () -> Boolean = { false }
        private set

    /**
     * Watches [downloads] for as long as the app runs, starting the keeper as one begins;
     * [resume] and [pending] are for the keeper run again after the app was killed.
     */
    fun watch(
        context: Context,
        scope: CoroutineScope,
        downloads: Flow<DownloadState?>,
        resume: () -> Boolean,
        pending: () -> Boolean,
    ) {
        this.resume = resume
        this.pending = pending
        val app = context.applicationContext
        scope.launch {
            var running = false
            var finishing = false
            downloads.distinctUntilChanged().collectLatest { now ->
                if (now == null) {
                    if (!running) return@collectLatest
                    delay(SETTLE_MS)
                    running = false
                    finishing = false
                    _state.value = null
                    return@collectLatest
                }
                _state.value = now
                if (!running) {
                    running = true
                    start(app, now)
                } else {
                    notify(app, now)
                }
                if (now.finishing != finishing) {
                    finishing = now.finishing
                    if (finishing) finish(app, now)
                }
            }
        }
    }

    private fun start(context: Context, now: DownloadState) {
        if (keeping) return
        createChannel(context)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val scheduler = context.getSystemService(JobScheduler::class.java)
                // Already waiting to be run again (for the connection): scheduling anew would replace it.
                if (scheduler.getPendingJob(JOB_ID) != null) return
                val job = JobInfo.Builder(JOB_ID, ComponentName(context, DownloadJob::class.java))
                    .setUserInitiated(true)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .build()
                scheduler.schedule(job)
            } else {
                ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
            }
        }.onFailure {
            // Begun from the background (the photo model after the chat model, with the keeper
            // already gone): it carries on while the app is left running.
            Log.w(TAG, "Could not keep \"${now.title}\" going in the background", it)
        }
    }

    /** The check or unpack after a download, kept going (see the class's note); before 14, [DownloadService] already does. */
    private fun finish(context: Context, now: DownloadState) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        // Allowed only with the app in front: from the background Android refuses it, and the
        // step is finished as the app next comes to the front (AppContainer.finishCutShortDownloads).
        runCatching { ContextCompat.startForegroundService(context, Intent(context, FinishService::class.java)) }
            .onFailure { Log.i(TAG, "\"${now.title}\" finishes the next time the app is opened: ${it.javaClass.simpleName}") }
    }

    internal fun notification(context: Context, now: DownloadState?): Notification {
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_assistant)
            .setContentTitle(now?.title ?: "Downloading")
            .setContentText(now?.detail)
            .setProgress(100, ((now?.fraction ?: 0f) * 100).toInt(), now?.fraction == null)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }

    private fun notify(context: Context, now: DownloadState) {
        runCatching { context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(context, now)) }
    }

    internal fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW).apply {
            description = "A model or voice downloading, while the app is closed."
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /**
     * Calls [onChange] with each change of the download until there is none, then [finished].
     * Run again with nothing under way, it first picks up what was left, and waits for it.
     */
    internal fun follow(scope: CoroutineScope, onChange: (DownloadState) -> Unit, finished: () -> Unit): Job =
        scope.launch {
            if (state.value == null) {
                val picked = resume() && withTimeoutOrNull(PICKUP_MS) { state.first { it != null } } != null
                if (!picked) {
                    finished()
                    return@launch
                }
            }
            state.collect { now ->
                if (now == null) {
                    finished()
                    cancel()
                } else {
                    onChange(now)
                }
            }
        }
}

/** Android 14 and later: the job a user-asked download runs as (see [BackgroundDownloads]). */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class DownloadJob : JobService() {

    private var scope: CoroutineScope? = null

    override fun onStartJob(params: JobParameters): Boolean {
        BackgroundDownloads.keeping = true
        BackgroundDownloads.createChannel(this)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main).also { scope = it }
        setNotification(
            params,
            BackgroundDownloads.NOTIFICATION_ID,
            BackgroundDownloads.notification(this, BackgroundDownloads.state.value),
            JOB_END_NOTIFICATION_POLICY_REMOVE,
        )
        BackgroundDownloads.follow(
            scope,
            onChange = { now ->
                setNotification(
                    params,
                    BackgroundDownloads.NOTIFICATION_ID,
                    BackgroundDownloads.notification(this, now),
                    JOB_END_NOTIFICATION_POLICY_REMOVE,
                )
            },
            finished = {
                BackgroundDownloads.keeping = false
                // Ended by the connection: run again, with Android's backoff, once there is one.
                jobFinished(params, BackgroundDownloads.pending())
            },
        )
        return true
    }

    /**
     * Stopped by the system: the network gone (run again once it's back, and the download, ended
     * by its timeout or carrying on, picked up then), or the user stopping it in Task Manager.
     */
    override fun onStopJob(params: JobParameters): Boolean {
        BackgroundDownloads.keeping = false
        scope?.cancel()
        scope = null
        return params.stopReason != JobParameters.STOP_REASON_USER && BackgroundDownloads.pending()
    }
}

/** Before Android 14: a foreground service, of no type, while a download runs (see [BackgroundDownloads]). */
class DownloadService : Service() {

    private var scope: CoroutineScope? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        BackgroundDownloads.keeping = true
        BackgroundDownloads.createChannel(this)
        startForeground(BackgroundDownloads.NOTIFICATION_ID, BackgroundDownloads.notification(this, BackgroundDownloads.state.value))
        if (scope != null) return START_STICKY
        // With the screen off the phone sleeps, and the download with it: a job holds this itself.
        wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LocalAssistant:download")
            .apply { acquire(WAKE_LIMIT_MS) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main).also { scope = it }
        BackgroundDownloads.follow(
            scope,
            onChange = { now ->
                getSystemService(NotificationManager::class.java)
                    .notify(BackgroundDownloads.NOTIFICATION_ID, BackgroundDownloads.notification(this, now))
            },
            finished = { stopSelf() },
        )
        // Sticky: killed with a download under way, Android starts the service again, and it
        // picks the download up (BackgroundDownloads.follow).
        return START_STICKY
    }

    override fun onDestroy() {
        BackgroundDownloads.keeping = false
        scope?.cancel()
        scope = null
        wake?.takeIf { it.isHeld }?.release()
        wake = null
        super.onDestroy()
    }

    private companion object {
        /** A slow connection's ~3.7 GB; released sooner as the download ends. */
        const val WAKE_LIMIT_MS = 3 * 60 * 60 * 1000L
    }
}

/**
 * Android 14 and later: a short foreground service while a download is checked or unpacked, with
 * no traffic to keep the app awake on OxygenOS (see [BackgroundDownloads]). Android stops it
 * after three minutes; the voice's unpack took about a minute on the phone.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class FinishService : Service() {

    private var scope: CoroutineScope? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        BackgroundDownloads.createChannel(this)
        startForeground(
            BackgroundDownloads.NOTIFICATION_ID,
            BackgroundDownloads.notification(this, BackgroundDownloads.state.value),
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE,
        )
        if (scope != null) return START_NOT_STICKY
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main).also { scope = it }
        scope.launch {
            BackgroundDownloads.state.collect { now ->
                if (now?.finishing != true) stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w("FinishService", "Out of time while a download was finished")
        stopSelf()
    }

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        super.onDestroy()
    }
}
