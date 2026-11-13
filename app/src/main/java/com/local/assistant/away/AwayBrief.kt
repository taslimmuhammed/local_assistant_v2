package com.local.assistant.away

import android.content.Context
import android.media.AudioManager
import android.util.Log
import com.local.assistant.assist.AssistActivity
import com.local.assistant.assist.AssistantInteractionService
import com.local.assistant.data.prefs.SettingsStore
import com.local.assistant.llm.LlmBackend
import com.local.assistant.memory.work.ModelAccess
import com.local.assistant.memory.work.ModelScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZoneId

/**
 * "While you were away": what came in while the phone was locked, sorted by what needs the user,
 * shown when they're back.
 *
 * - **Collecting** ([capture]): [MessageListener] hands over each notification's lines; they're
 *   kept two days in their own database, then deleted.
 * - **Away** ([onScreenOff], [onUserPresent]): from the screen going off to the next unlock. Back
 *   after at least the user's chosen time with someone waiting on them, the brief opens.
 * - **Reading** ([triage]): the chat model sorts conversations and drafts replies. While the
 *   phone is locked it does so once things go quiet, if the model is already loaded (it is when
 *   the assistant is kept ready), so the brief is ready at unlock; anything left is read when the
 *   brief opens. Until then, [BriefRules] sorts it.
 */
class AwayBrief(
    private val context: Context,
    private val settings: SettingsStore,
    private val dao: AwayDao,
    val replies: ReplyActions,
    private val scheduler: ModelScheduler,
    private val model: ModelAccess,
    private val backend: LlmBackend,
    /** The chat model is in memory: reading while locked mustn't load gigabytes. */
    private val modelLoaded: () -> Boolean,
    private val scope: CoroutineScope,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val prefs = context.getSharedPreferences("away", Context.MODE_PRIVATE)

    private val _reading = MutableStateFlow(false)

    /** The model is reading conversations for the brief now. */
    val reading: StateFlow<Boolean> = _reading.asStateFlow()

    private val triageLock = Mutex()
    private var quietTriage: Job? = null

    /** When the screen last went off, unlocked since or not; 0 while the user is here. */
    private var awaySince: Long
        get() = prefs.getLong(KEY_AWAY_SINCE, 0L)
        set(value) = prefs.edit().putLong(KEY_AWAY_SINCE, value).apply()

    /** How long the user was last away, for the brief's header. */
    var lastAwayMs: Long
        get() = prefs.getLong(KEY_LAST_AWAY_MS, 0L)
        private set(value) = prefs.edit().putLong(KEY_LAST_AWAY_MS, value).apply()

    private var lastAwayTriageAt = 0L

    // ---- Collecting ----

    fun capture(lines: List<CapturedEntity>) {
        if (lines.isEmpty() || !settings.awayBrief) return
        scope.launch {
            val added = dao.insert(lines).count { it > 0 }
            dao.deleteBefore(clock() - KEEP_MS)
            if (added > 0 && awaySince > 0) scheduleQuietTriage()
        }
    }

    /** The notification [notificationKey] went: read in its app, dismissed, or answered. */
    fun onRemoved(notificationKey: String) {
        scope.launch { dao.markSeenByNotification(notificationKey, clock()) }
    }

    // ---- Away and back ----

    fun onScreenOff() {
        if (awaySince == 0L) awaySince = clock()
    }

    fun onUserPresent() {
        val since = awaySince
        awaySince = 0L
        quietTriage?.cancel()
        if (since == 0L || !settings.awayBrief) return
        val away = clock() - since
        lastAwayMs = away
        if (away < settings.awayMinutes * 60_000L) return
        scope.launch {
            // Let the lock screen get out of the way first.
            delay(SHOW_AFTER_UNLOCK_MS)
            val brief = BriefBuilder.build(dao.unseen(clock() - WINDOW_MS), dao.triage(), newOnly = true)
            if (brief.needs.isEmpty()) {
                // Nothing pressing: read the rest now, for whenever the brief is opened.
                triage(user = false)
                return@launch
            }
            show(brief)
        }
    }

    private fun show(brief: Brief) {
        val audio = context.getSystemService(AudioManager::class.java)
        val inCall = audio?.mode in setOf(AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION, AudioManager.MODE_RINGTONE)
        val popped = settings.awayPopUp && !inCall && !AssistActivity.inView && AssistantInteractionService.showBrief()
        if (!popped) BriefNotifications.post(context, brief)
        Log.i(TAG, "Back after ${lastAwayMs / 60_000} min: ${brief.needs.size} need you (${if (popped) "shown" else "notified"})")
    }

    // ---- The brief ----

    /** The brief, kept current. [newOnly]: what wasn't in an earlier brief (as at unlock). */
    fun observe(newOnly: Boolean): Flow<Brief> =
        combine(dao.observeUnseen(clock() - WINDOW_MS), dao.observeTriage(), replies.version) { lines, triage, _ ->
            BriefBuilder.build(lines, triage, newOnly)
        }

    val apps get() = dao.observeApps()

    /** The user dealt with these: replied, called back, opened or swiped them away. */
    fun markSeen(keys: List<String>) {
        if (keys.isEmpty()) return
        scope.launch { dao.markSeen(keys, clock()) }
    }

    /** The brief showing these closed: the next one opens on what's new. */
    fun markBriefed(ids: List<Long>) {
        if (ids.isEmpty()) return
        scope.launch {
            dao.markBriefed(ids, clock())
            BriefNotifications.cancel(context)
        }
    }

    suspend fun forgetApp(packageName: String) {
        dao.deleteApp(packageName)
    }

    suspend fun forgetAll() {
        dao.deleteAll()
        dao.deleteAllTriage()
        replies.clear()
    }

    // ---- Reading ----

    /** Things came in while locked: read them once it's been quiet a minute, at most every ten. */
    private fun scheduleQuietTriage() {
        if (!modelLoaded()) return
        quietTriage?.cancel()
        quietTriage = scope.launch {
            val wait = maxOf(QUIET_MS, lastAwayTriageAt + AWAY_TRIAGE_EVERY_MS - clock())
            delay(wait)
            if (awaySince == 0L) return@launch
            lastAwayTriageAt = clock()
            triage(user = false)
        }
    }

    /**
     * Reads every conversation with lines the model hasn't seen. [user]: the brief is open and
     * waiting on it, so it goes ahead of background work (and may load the model).
     * False when the model was busy or unavailable; what was read is kept either way.
     */
    suspend fun triage(user: Boolean): Boolean = triageLock.withLock {
        val lines = dao.unseen(clock() - WINDOW_MS)
        val readings = dao.triage().associateBy { it.conversationKey }
        val brief = BriefBuilder.build(lines, readings.values.toList(), newOnly = false)
        val waiting = (brief.needs + brief.fyi + brief.skipped)
            .filter { it.kind != Kind.CALL && !it.read && !it.lines.last().fromMe }
        if (waiting.isEmpty()) return true
        if (!user && !modelLoaded()) return false

        // The most recent first: they're the ones the user will look at.
        val batches = waiting.sortedByDescending { it.latestAt }.chunked(BriefTriage.BATCH)
        val work: suspend () -> Boolean = {
            model.withModelFree {
                // From the start: loading the model is most of the wait the first time.
                _reading.value = true
                try {
                    if (!backend.ensureReady()) return@withModelFree false
                    batches.forEachIndexed { index, batch -> read(batch, headline = index == 0) }
                    true
                } finally {
                    _reading.value = false
                }
            }
        }
        return try {
            if (user) scheduler.runUser(work) else scheduler.runBackground(ModelScheduler.Priority.BRIEF, block = work) ?: false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Reading the brief failed", e)
            false
        }
    }

    private suspend fun read(batch: List<BriefItem>, headline: Boolean) {
        val entries = batch.mapIndexed { i, item -> BriefTriage.Entry(i + 1, item) }
        val prompt = BriefTriage.render(entries, zone())
        val started = clock()
        val reading = BriefTriage.parse(backend.complete(BriefTriage.SYSTEM, prompt, maxTokens = BriefTriage.OUTPUT_TOKENS), entries)
            ?: BriefTriage.parse(backend.complete(BriefTriage.SYSTEM, "$prompt\n${BriefTriage.RETRY}", maxTokens = BriefTriage.OUTPUT_TOKENS), entries)
        Log.i(TAG, "Read ${entries.size} conversations in ${clock() - started} ms${if (reading == null) " (no usable answer)" else ""}")
        val now = clock()
        val rows = entries.map { (id, item) ->
            val said = reading?.items?.get(id)
            TriageEntity(
                conversationKey = item.key,
                upToId = item.lines.maxOf { it.id },
                // Not understood: kept as the rules have it, so it isn't asked about again and again.
                level = (said?.level ?: item.level).name,
                gist = said?.gist.orEmpty(),
                replies = said?.replies.orEmpty().joinToString("\n"),
                triagedAt = now,
            )
        }.toMutableList()
        if (headline && reading?.headline != null) {
            rows += TriageEntity(TriageEntity.HEADLINE_KEY, upToId = rows.maxOf { it.upToId }, level = Level.FYI.name, gist = reading.headline, replies = "", triagedAt = now)
        }
        dao.putTriage(rows)
        dao.deleteTriageBefore(now - KEEP_MS)
    }

    companion object {
        private const val TAG = "AwayBrief"
        private const val KEY_AWAY_SINCE = "away_since"
        private const val KEY_LAST_AWAY_MS = "last_away_ms"

        /** Lines are deleted two days after they came. */
        const val KEEP_MS = 48 * 60 * 60 * 1000L

        /** A brief covers the last day at most. */
        const val WINDOW_MS = 24 * 60 * 60 * 1000L

        const val SHOW_AFTER_UNLOCK_MS = 600L
        const val QUIET_MS = 60_000L
        const val AWAY_TRIAGE_EVERY_MS = 10 * 60_000L

        val AWAY_CHOICES = listOf(15, 30, 60, 120)
        const val DEFAULT_AWAY_MINUTES = 30
    }
}
