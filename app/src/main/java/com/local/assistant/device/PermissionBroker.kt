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

/**
 * Lets code outside the UI ask for a runtime permission and wait for the answer: the contacts
 * lookup asks the first time the user says "call amma", not at install. The chat screen shows
 * the system dialog; with no screen listening the answer is no, and the tool tells the model.
 */
class PermissionBroker(private val context: Context) {

    class Request(val permission: String) {
        val answer = CompletableDeferred<Boolean>()
    }

    private val _requests = MutableSharedFlow<Request>(extraBufferCapacity = 1)
    val requests: SharedFlow<Request> = _requests.asSharedFlow()

    /** One dialog at a time. */
    private val mutex = Mutex()

    fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    suspend fun request(permission: String): Boolean = mutex.withLock {
        if (granted(permission)) return@withLock true
        if (_requests.subscriptionCount.value == 0) return@withLock false
        val request = Request(permission)
        _requests.emit(request)
        withTimeoutOrNull(ANSWER_TIMEOUT_MS) { request.answer.await() } ?: false
    }

    private companion object {
        /** A dialog left unanswered this long counts as no, so the turn is not held forever. */
        const val ANSWER_TIMEOUT_MS = 60_000L
    }
}
