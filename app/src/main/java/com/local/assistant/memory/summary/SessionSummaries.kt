package com.local.assistant.memory.summary

import android.util.Log
import com.local.assistant.data.db.Role
import com.local.assistant.llm.LlmBackend
import com.local.assistant.memory.db.SessionDao
import com.local.assistant.memory.db.SummaryStatus
import com.local.assistant.memory.prompt.MemoryBudget
import com.local.assistant.memory.retrieval.QueryText
import com.local.assistant.memory.work.AppForeground
import com.local.assistant.memory.work.ModelAccess
import com.local.assistant.memory.work.ModelScheduler

/**
 * Ends sessions and writes their summaries: what the next conversation — in any chat — starts
 * from ("Last time you talked: …").
 *
 * Runs from the session-end job ten minutes after the app leaves the foreground, and from the
 * nightly job for anything still pending. Never on the way to a reply: a summary waits for the
 * model to be free, one session at a time, and gives way the moment the user is back.
 */
class SessionSummaries(
    private val sessions: SessionDao,
    private val summarizer: Summarizer,
    private val backend: LlmBackend,
    private val conversations: ModelAccess,
    private val scheduler: ModelScheduler,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** Closes every session that has been quiet for a full session gap. */
    suspend fun endQuietSessions(): Int {
        var ended = 0
        val now = clock()
        for (open in sessions.openSessions()) {
            val lastActivity = maxOf(open.startedAt, sessions.lastActivity(open.id) ?: 0L)
            if (now - lastActivity >= AppForeground.SESSION_GAP_MS) {
                sessions.end(open.id, endedAt = lastActivity)
                ended++
            }
        }
        return ended
    }

    /**
     * Summarises ended sessions still pending, oldest first, until [deadline]. With [loadModel]
     * false only an engine already in memory is used — the session-end job does not load
     * gigabytes to write a paragraph; the nightly job, on the charger, does. Returns how many are
     * still pending.
     */
    suspend fun summarisePending(deadline: Long, loadModel: Boolean): Int {
        while (clock() < deadline) {
            val session = sessions.pendingSummaries(1).firstOrNull() ?: break
            val turns = sessions.turns(session.id).filter { !it.offRecord }
            val worthIt = turns.any { it.role == Role.USER && !QueryText.isTrivial(it.text) }
            if (!worthIt) {
                // Greetings and "ok"s: nothing to carry forward.
                sessions.setSummary(session.id, null, SummaryStatus.DONE)
                continue
            }
            if (!loadModel && backend.capabilities == null) break
            val outcome = scheduler.runBackground(ModelScheduler.Priority.COMPACTION) {
                conversations.withModelFree {
                    if (!backend.ensureReady()) return@withModelFree Outcome.Unavailable
                    val cap = MemoryBudget.forWindow(checkNotNull(backend.capabilities).maxContextTokens).summaryCap
                    try {
                        Outcome.Written(summarizer.session(turns, cap))
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Summary failed for session ${session.id}", e)
                        Outcome.Written(null)
                    }
                }
            } ?: break // The user came back; the rest waits.
            when (outcome) {
                Outcome.Unavailable -> break
                is Outcome.Written -> {
                    val status = if (outcome.summary != null) SummaryStatus.DONE else SummaryStatus.FAILED
                    sessions.setSummary(session.id, outcome.summary, status)
                    Log.i(TAG, "Session ${session.id}: summary ${status.name.lowercase()}")
                }
            }
        }
        return sessions.pendingSummaryCount()
    }

    private sealed interface Outcome {
        data object Unavailable : Outcome
        data class Written(val summary: String?) : Outcome
    }

    private companion object {
        const val TAG = "SessionSummaries"
    }
}
