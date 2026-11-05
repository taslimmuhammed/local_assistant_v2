package com.local.assistant.device

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lets code outside the UI ask for a runtime permission and wait for the answer: the contacts
 * lookup asks the first time the user says "call amma", not at install, and health data the
 * first time a kind of it is asked about. The chat screen shows the system dialog; with no
 * screen listening the answer is no, and the tool tells the model.
 */
class PermissionBroker(private val context: Context) {

    /**
     * [permissions]: one runtime permission, or with [health] the Health Connect ones, which
     * Health Connect asks for on its own screen.
     */
    class Request(val permissions: Set<String>, val health: Boolean = false) {
        constructor(permission: String) : this(setOf(permission))

        /** The runtime permission asked for. */
        val permission: String get() = permissions.first()

        val answer = CompletableDeferred<Boolean>()

        private val claimed = AtomicBoolean(false)

        /**
         * Whether the screen calling this is the one to ask: the first to claim it. With the
         * overlay over the chat both are on screen, and the user is to see one dialog, not two.
         */
        fun claim(): Boolean = claimed.compareAndSet(false, true)
    }

    private val _requests = MutableSharedFlow<Request>(extraBufferCapacity = 1)
    val requests: SharedFlow<Request> = _requests.asSharedFlow()

    /** One dialog at a time. */
    private val mutex = Mutex()

    fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    suspend fun request(permission: String): Boolean = mutex.withLock {
        if (granted(permission)) true else ask(Request(permission))
    }

    /**
     * Health Connect access to [permissions], on its screen, where the user can allow some and
     * not others: true when all were allowed. The caller checks what it has first.
     */
    suspend fun requestHealth(permissions: Set<String>): Boolean =
        mutex.withLock { ask(Request(permissions, health = true), HEALTH_ANSWER_TIMEOUT_MS) }

    private suspend fun ask(request: Request, timeoutMs: Long = ANSWER_TIMEOUT_MS): Boolean {
        if (_requests.subscriptionCount.value == 0) return false
        _requests.emit(request)
        val answer = withTimeoutOrNull(timeoutMs) { request.answer.await() }
        // Left unanswered: done with, so whatever waits on it (the overlay staying up) stops waiting.
        if (answer == null) request.answer.complete(false)
        return answer ?: false
    }

    private companion object {
        /** A dialog left unanswered this long counts as no, so the turn is not held forever. */
        const val ANSWER_TIMEOUT_MS = 60_000L

        /**
         * Health Connect's is a whole page to read, not a dialog: on the phone the user tapped
         * Allow 70 s in, after the minute was up, and was told the app had no access (5 Nov 2026).
         */
        const val HEALTH_ANSWER_TIMEOUT_MS = 180_000L
    }
}
