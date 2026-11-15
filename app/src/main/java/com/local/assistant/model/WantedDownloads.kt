package com.local.assistant.model

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException

/**
 * Downloads the user started and hasn't got yet, kept on disk: the phone killed the app, or the
 * connection dropped, and the download picks up again by itself (see [BackgroundDownloads]) rather
 * than waiting for someone to open the right screen and tap Resume. Cancelling, deleting, a
 * finished download, or a failure a retry wouldn't fix (no space, a corrupt file) drop it.
 */
class WantedDownloads(context: Context) {

    private val prefs = context.getSharedPreferences("downloads", Context.MODE_PRIVATE)

    fun want(key: String) = prefs.edit { putBoolean(key, true) }

    fun drop(key: String) = prefs.edit { remove(key) }

    fun wanted(key: String): Boolean = prefs.getBoolean(key, false)

    fun any(): Boolean = prefs.all.keys.any { it != PAUSED }

    private val _paused = MutableStateFlow(prefs.getBoolean(PAUSED, false))

    /**
     * The user paused downloading (a Pause button, the notification's, Stop in Task Manager):
     * what was under way stays wanted, with what has arrived, but nothing is picked up by itself
     * until a download is started again, which ends the pause.
     */
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    fun pause(on: Boolean) {
        if (_paused.value == on) return
        prefs.edit { if (on) putBoolean(PAUSED, true) else remove(PAUSED) }
        _paused.value = on
    }

    /** Ended by [failure]: still wanted only if it was the connection, which comes back. */
    fun failed(key: String, failure: Throwable) {
        if (!failure.isConnection()) drop(key)
    }

    private companion object {
        const val PAUSED = "paused"
    }
}

/**
 * The connection's doing (a timeout, no route, a reset): the I/O exceptions the network stack
 * throws. The plain [IOException]s the downloads throw themselves (no space, an HTTP error, a
 * corrupt file) are not.
 */
internal fun Throwable.isConnection(): Boolean = this is IOException && this.javaClass != IOException::class.java
