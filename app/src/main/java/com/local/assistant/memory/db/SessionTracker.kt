package com.local.assistant.memory.db

import com.local.assistant.memory.work.AppForeground

/**
 * Assigns every message to a session: one foreground period within one chat.
 *
 * A chat's open session carries on unless the app has since been away for at least
 * [AppForeground.SESSION_GAP_MS] (or the process restarted) *and* that long has passed since the
 * session's last message. Then the old session is closed — its summary stays pending until the
 * session-end job writes it — and a new one opens.
 *
 * Deciding lazily, at the next message, means a session boundary needs no background work to
 * exist; the job that summarises ended sessions can run whenever it gets the chance.
 */
class SessionTracker(
    private val sessions: SessionDao,
    private val foreground: AppForeground,
) {

    suspend fun sessionFor(chatId: Long, now: Long): Long {
        val open = sessions.openSession(chatId)
        if (open != null) {
            val lastActivity = maxOf(open.startedAt, sessions.lastActivity(open.id) ?: 0L)
            val continuous = lastActivity >= foreground.boundaryAt ||
                now - lastActivity < AppForeground.SESSION_GAP_MS
            if (continuous) return open.id
            sessions.end(open.id, endedAt = lastActivity)
        }
        return sessions.insert(SessionEntity(chatId = chatId, startedAt = now))
    }
}
