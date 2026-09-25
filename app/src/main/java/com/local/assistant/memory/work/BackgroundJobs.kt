package com.local.assistant.memory.work

import android.content.Context
import android.os.PowerManager
import android.util.Log
import androidx.room.useWriterConnection
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.local.assistant.appContainer
import java.util.concurrent.TimeUnit

/**
 * The two scheduled jobs:
 *
 * - **session end**, ten minutes after the app leaves the foreground (cancelled if it comes back
 *   sooner): closes the session and summarises it with the engine if it is still loaded;
 * - **nightly**, on the charger with the phone idle: everything that needs the model for a while.
 *
 * WorkManager stops a worker after about ten minutes, so the nightly one works to a deadline and
 * queues a continuation for whatever is left; every step keeps its own place, so a continuation
 * picks up where the last run stopped.
 */
class BackgroundJobs(private val context: Context) {

    private val work get() = WorkManager.getInstance(context)

    fun onBackground() {
        work.enqueueUniqueWork(
            SESSION_END,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SessionEndWorker>().setInitialDelay(SESSION_END_DELAY_MIN, TimeUnit.MINUTES).build(),
        )
    }

    fun onForeground() {
        work.cancelUniqueWork(SESSION_END)
    }

    /** Once per install; keeps the existing schedule if there is one. */
    fun scheduleNightly() {
        work.enqueueUniquePeriodicWork(
            NIGHTLY,
            ExistingPeriodicWorkPolicy.KEEP,
            // No backoff policy: the system refuses one on a job that waits for the phone to be idle.
            PeriodicWorkRequestBuilder<NightlyWorker>(24, TimeUnit.HOURS)
                .setConstraints(nightlyConstraints())
                .build(),
        )
    }

    internal fun continueNightly() {
        work.enqueueUniqueWork(
            NIGHTLY_CONTINUATION,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<NightlyWorker>()
                .setConstraints(nightlyConstraints())
                .setInitialDelay(1, TimeUnit.MINUTES)
                .build(),
        )
    }

    private fun nightlyConstraints() = Constraints.Builder()
        .setRequiresCharging(true)
        .setRequiresDeviceIdle(true)
        .setRequiresBatteryNotLow(true)
        .setRequiresStorageNotLow(true)
        .build()

    companion object {
        const val SESSION_END = "session-end"
        const val NIGHTLY = "nightly-consolidation"
        const val NIGHTLY_CONTINUATION = "nightly-consolidation-continue"
        const val SESSION_END_DELAY_MIN = 10L

        /** Short of WorkManager's ten-minute limit, with room to stop cleanly. */
        const val RUN_BUDGET_MS = 8 * 60 * 1000L
    }
}

/** Ten minutes after the app went to the background. */
class SessionEndWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = applicationContext.appContainer
        val ended = container.sessionSummaries.endQuietSessions()
        // Only an engine still in memory: loading gigabytes for a paragraph can wait for the charger.
        val pending = container.sessionSummaries.summarisePending(
            deadline = System.currentTimeMillis() + BackgroundJobs.RUN_BUDGET_MS,
            loadModel = false,
        )
        Log.i(TAG, "Ended $ended sessions; $pending summaries left for the nightly job")
        return Result.success()
    }

    private companion object {
        const val TAG = "SessionEndWorker"
    }
}

/** On the charger, overnight. */
class NightlyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val power = applicationContext.getSystemService(PowerManager::class.java)
        if (android.os.Build.VERSION.SDK_INT >= 29 && power.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE) {
            // Everything here keeps its place, so skipping a warm night loses nothing.
            Log.i(TAG, "Phone is warm; skipping until the next idle charge")
            return Result.success()
        }
        val container = applicationContext.appContainer
        val finished = container.consolidation.run(deadline = System.currentTimeMillis() + BackgroundJobs.RUN_BUDGET_MS)
        if (!finished) container.backgroundJobs.continueNightly()
        return Result.success()
    }

    private companion object {
        const val TAG = "NightlyWorker"
    }
}

/**
 * The nightly steps, in order. Each keeps its own place — the embedding backlog and pending
 * summaries by their state in the database, extraction by its watermark — so running this again
 * after an interruption repeats nothing.
 */
class Consolidation(
    private val embeddings: EmbeddingQueue,
    private val sessions: com.local.assistant.memory.summary.SessionSummaries,
    private val extractor: com.local.assistant.memory.extract.Extractor,
    private val memory: com.local.assistant.memory.db.MemoryRepository,
    private val database: com.local.assistant.data.db.AppDatabase,
    private val retentionDays: () -> Int,
    private val applyRetention: suspend (Int) -> Int,
    /** Frees the chat model afterwards if nobody is using the app. */
    private val releaseModel: suspend () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** True when every step finished before [deadline]. */
    suspend fun run(deadline: Long): Boolean {
        val started = clock()
        try {
            embeddings.drainNow()
            sessions.endQuietSessions()
            if (sessions.summarisePending(deadline, loadModel = true) > 0) return false
            if (!extractor.run(deadline)) return false
            val merged = memory.mergeCertainAliases()
            if (merged > 0) Log.i(TAG, "Merged $merged facts under their canonical names")
            val removed = applyRetention(retentionDays())
            if (removed > 0) Log.i(TAG, "Deleted $removed messages past the retention period")
            optimize()
            return true
        } finally {
            releaseModel()
            Log.i(TAG, "Nightly run took ${(clock() - started) / 1000} s")
        }
    }

    /** SQLite's own upkeep, and merging each FTS index's segments into one. */
    private suspend fun optimize() {
        database.useWriterConnection { connection ->
            for (sql in listOf(
                "INSERT INTO facts_fts(facts_fts) VALUES('optimize')",
                "INSERT INTO chunks_fts(chunks_fts) VALUES('optimize')",
                "PRAGMA optimize",
            )) {
                connection.usePrepared(sql) { it.step() }
            }
        }
    }

    private companion object {
        const val TAG = "Consolidation"
    }
}
