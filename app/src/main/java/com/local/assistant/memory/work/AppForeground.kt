package com.local.assistant.memory.work

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Tracks when the app comes and goes, for the two things that care: sessions (a session is one
 * foreground period) and the engine (warm while the app is in use, released under memory
 * pressure once it is not).
 *
 * Registered on `ProcessLifecycleOwner`, which reports the app as a whole rather than any one
 * activity, and debounces configuration changes.
 */
class AppForeground(
    private val clock: () -> Long = System::currentTimeMillis,
    private val onForeground: () -> Unit = {},
) : DefaultLifecycleObserver {

    /**
     * The last moment a session boundary happened: process start, or a return to the foreground
     * after at least [SESSION_GAP_MS] away. Messages older than this belong to an earlier session.
     */
    @Volatile
    var boundaryAt: Long = clock()
        private set

    @Volatile
    var isForeground: Boolean = false
        private set

    @Volatile
    private var stoppedAt: Long? = null

    override fun onStart(owner: LifecycleOwner) {
        val now = clock()
        val away = stoppedAt?.let { now - it }
        if (away != null && away >= SESSION_GAP_MS) boundaryAt = now
        stoppedAt = null
        isForeground = true
        onForeground()
    }

    override fun onStop(owner: LifecycleOwner) {
        stoppedAt = clock()
        isForeground = false
    }

    companion object {
        /** How long the app must be away before coming back starts a new session. */
        const val SESSION_GAP_MS = 10 * 60 * 1000L
    }
}
