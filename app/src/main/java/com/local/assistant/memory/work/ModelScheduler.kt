package com.local.assistant.memory.work

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import java.util.PriorityQueue

/**
 * One model job at a time, and the user always first.
 *
 * The phone has one model and one accelerator; two jobs on it at once would each run at half
 * speed at best. So every model call goes through here. The user's own generation outranks
 * everything; background work runs only while the user is neither generating nor typing, and is
 * cancelled the moment they start either.
 *
 * Background work must be cooperative: check for cancellation between steps. A native call that
 * is already running cannot be interrupted from here, which is why background jobs are kept
 * short.
 */
class ModelScheduler(private val clock: () -> Long = System::currentTimeMillis) {

    /** Highest first. */
    enum class Priority {
        USER_GENERATION,
        QUERY_EMBEDDING,
        COMPACTION,
        EMBEDDING_BACKLOG,
        NIGHTLY,
    }

    private class Waiter(val priority: Priority, val seq: Long) {
        val turn = CompletableDeferred<Unit>()
    }

    private val lock = Any()
    private var busy = false
    private var seq = 0L
    private val queue = PriorityQueue<Waiter>(compareBy<Waiter>({ it.priority.ordinal }, { it.seq }))
    private var runningBackground: Deferred<*>? = null

    @Volatile
    private var lastUserActivityAt = 0L

    /** Runs the user's generation, pre-empting any background job that holds the model. */
    suspend fun <T> runUser(block: suspend () -> T): T {
        acquire(Priority.USER_GENERATION)
        try {
            return block()
        } finally {
            release()
        }
    }

    /**
     * Runs background model work when the model is free and the user is idle. Returns null if
     * the job was pre-empted or skipped because the user is active; the caller retries later.
     */
    suspend fun <T> runBackground(priority: Priority, block: suspend () -> T): T? {
        require(priority != Priority.USER_GENERATION)
        if (userRecentlyActive()) return null
        acquire(priority)
        try {
            if (userRecentlyActive()) return null
            return coroutineScope {
                val work = async { block() }
                synchronized(lock) { runningBackground = work }
                try {
                    work.await()
                } catch (e: CancellationException) {
                    // Pre-empted: our own scope is still active, only the work was cancelled.
                    if (work.isCancelled && isActive) null else throw e
                }
            }
        } finally {
            synchronized(lock) { runningBackground = null }
            release()
        }
    }

    /** The user is typing: stop background work now and hold off starting more for a moment. */
    fun onUserActivity() {
        lastUserActivityAt = clock()
        synchronized(lock) { runningBackground }?.cancel()
    }

    private fun userRecentlyActive(): Boolean = clock() - lastUserActivityAt < QUIET_MS

    private suspend fun acquire(priority: Priority) {
        val waiter = synchronized(lock) {
            if (!busy && queue.isEmpty()) {
                busy = true
                return
            }
            if (priority == Priority.USER_GENERATION) runningBackground?.cancel()
            Waiter(priority, seq++).also(queue::add)
        }
        try {
            waiter.turn.await()
        } catch (e: CancellationException) {
            val handedOver = synchronized(lock) { !queue.remove(waiter) }
            // The slot was passed to us just as we were cancelled: pass it on.
            if (handedOver) release()
            throw e
        }
    }

    private fun release() {
        synchronized(lock) {
            val next = queue.poll()
            if (next == null) busy = false else next.turn.complete(Unit)
        }
    }

    companion object {
        /** How long after the last keystroke background work stays off the model. */
        const val QUIET_MS = 4_000L
    }
}
