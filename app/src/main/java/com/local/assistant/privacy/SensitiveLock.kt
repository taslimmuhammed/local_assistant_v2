package com.local.assistant.privacy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Whether sensitive details ([SensitiveText], document photos) are shown. Hidden until the user
 * proves it's them (fingerprint, face or the phone's PIN), then shown for [REVEAL_MS] so that
 * every message doesn't ask again, and hidden again at once when the app leaves the screen or the
 * screen turns off. Never shown over the lock screen: that's checked where it's asked for.
 */
class SensitiveLock(private val scope: CoroutineScope) {

    private val _revealed = MutableStateFlow(false)
    val revealed: StateFlow<Boolean> = _revealed.asStateFlow()

    private var relock: Job? = null

    /** The user has just authenticated. */
    @Synchronized
    fun reveal() {
        _revealed.value = true
        relock?.cancel()
        relock = scope.launch {
            delay(REVEAL_MS)
            lock()
        }
    }

    @Synchronized
    fun lock() {
        relock?.cancel()
        relock = null
        _revealed.value = false
    }

    companion object {
        /** How long one unlock keeps details shown. */
        const val REVEAL_MS = 5 * 60_000L
    }
}
