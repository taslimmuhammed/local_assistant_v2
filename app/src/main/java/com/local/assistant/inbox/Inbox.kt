package com.local.assistant.inbox

import android.util.Log
import com.local.assistant.away.CapturedEntity
import com.local.assistant.away.Kind
import com.local.assistant.away.SeenNotification
import com.local.assistant.gallery.PhotoPeriod
import com.local.assistant.privacy.LogText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.exp

/**
 * The messages kept for search: what chat apps' notifications showed, and the SMS inbox while
 * the app is the phone's assistant ([SmsSync]). Kept as long as chats are
 * ([com.local.assistant.data.prefs.SettingsStore.historyRetentionDays]); ads and one-time codes
 * never. Found by words, by who sent them, by app and by when ([search]), for check_inbox.
 */
class Inbox(
    private val dao: InboxDao,
    private val scope: CoroutineScope,
    /** Kept at all: Settings › Messages. */
    private val keeping: () -> Boolean,
    /** Apps the user left out, by package. */
    private val ignoredApps: () -> Set<String>,
    /** Chats are kept this many days; 0 keeps everything. */
    private val retentionDays: () -> Int,
    /** The phone's SMS app. */
    private val smsApp: () -> String?,
    /** The SMS inbox is read directly ([SmsSync]): the SMS app's notifications would be kept twice. */
    private val readsSmsInbox: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    @Volatile
    private var purgedAt = 0L

    /** The messages in a notification [seen] showed, as [com.local.assistant.away.NotificationLines] read them. */
    fun keepNotification(seen: SeenNotification, lines: List<CapturedEntity>) {
        if (!keeping()) return
        val rows = fromNotification(seen, lines, ignoredApps(), smsApp(), readsSmsInbox(), clock())
        if (rows.isEmpty()) return
        scope.launch {
            runCatching { dao.insert(rows) }.onFailure { Log.w(TAG, "Couldn't keep messages", it) }
            purgeSometimes()
        }
    }

    /**
     * SMS read from the inbox ([SmsSync]), less the ones the SMS app's notifications already
     * brought in before the inbox was read: the same words within a few minutes. How many were kept.
     */
    suspend fun keepSms(rows: List<InboxMessage>, smsApp: String?): Int {
        if (rows.isEmpty()) return 0
        val earliest = rows.minOf { it.at } - SAME_SMS_MS
        val already = smsApp?.let { dao.fromApp(it, earliest) }.orEmpty()
        val fresh = rows.filter { row -> already.none { it.text == row.text && kotlin.math.abs(it.at - row.at) <= SAME_SMS_MS } }
        return dao.insert(fresh).count { it > 0 }
    }

    /**
     * Messages for check_inbox: [query]'s words (any of them, best matched first), from someone
     * named like [from], in [app], within [period]; at most [limit]. When none from [from] has the
     * words, their latest messages, and [Found.note] says so.
     */
    suspend fun search(query: String?, from: String?, app: String?, period: PhotoPeriod.Range?, limit: Int = MAX_FOUND): Found {
        val now = clock()
        val start = period?.start ?: 0L
        val end = period?.end?.let { it - 1 } ?: Long.MAX_VALUE
        val person = from?.trim()?.takeIf { it.isNotEmpty() }
        val terms = InboxSearch.terms(query.orEmpty(), without = person)
        val wanted = { rows: List<InboxMessage> -> InboxSearch.inApp(InboxSearch.named(rows, person), app) }
        val started = android.os.SystemClock.elapsedRealtime()
        var note: String? = null
        var rows = if (terms.isNotEmpty()) wanted(dao.search(InboxSearch.match(terms), start, end, POOL)) else emptyList()
        if (rows.isEmpty()) {
            if (terms.isNotEmpty() && person == null && app.isNullOrBlank()) {
                return Found(emptyList(), null)
            }
            val pool = if (person != null) dao.named(InboxSearch.likePattern(person), start, end, POOL) else dao.recent(start, end, POOL)
            rows = wanted(pool)
            if (terms.isNotEmpty() && rows.isNotEmpty()) {
                note = "None of these mentions \"${query?.trim()}\"; they are the latest${person?.let { " from $it" } ?: ""}."
            }
        }
        val ranked = InboxSearch.rank(terms, rows, now).take(limit)
        Log.i(TAG, "Inbox search ${LogText.of(listOfNotNull(query, from, app).joinToString(" | "))}: ${ranked.size} of ${rows.size} in ${android.os.SystemClock.elapsedRealtime() - started} ms")
        return Found(ranked, note)
    }

    class Found(val messages: List<InboxMessage>, val note: String?)

    /** Messages older than chats are kept for go too; how many went. */
    suspend fun purge(): Int {
        purgedAt = clock()
        val days = retentionDays()
        if (days <= 0) return 0
        return dao.deleteBefore(clock() - days * DAY_MS)
    }

    private suspend fun purgeSometimes() {
        if (clock() - purgedAt < PURGE_EVERY_MS) return
        runCatching { purge() }.onFailure { Log.w(TAG, "Couldn't delete old messages", it) }
    }

    suspend fun count(): Int = dao.count()

    suspend fun apps(): List<KeptApp> = dao.apps()

    suspend fun count(source: InboxMessage.Source): Int = dao.count(source.name)

    suspend fun deleteAll() = dao.deleteAll()

    suspend fun deleteApp(packageName: String): Int = dao.deleteApp(packageName)

    companion object {
        private const val TAG = "Inbox"
        const val MAX_FOUND = 6
        private const val POOL = 300
        private const val DAY_MS = 24 * 60 * 60_000L
        private const val PURGE_EVERY_MS = 60 * 60_000L
        private const val SAME_SMS_MS = 5 * 60_000L

        /**
         * What of a notification is kept: people's messages, and emails; not missed calls or
         * other apps' news, not ads, not what the user left out, and not the SMS app's own while
         * the SMS inbox is read directly.
         */
        fun fromNotification(
            seen: SeenNotification,
            lines: List<CapturedEntity>,
            ignored: Set<String>,
            smsApp: String?,
            readsSmsInbox: Boolean,
            now: Long,
        ): List<InboxMessage> {
            if (seen.packageName in ignored) return emptyList()
            val fromSmsApp = smsApp != null && seen.packageName == smsApp
            if (fromSmsApp && readsSmsInbox) return emptyList()
            val email = seen.category == "email"
            val source = when {
                email -> PromoFilter.Source.OTHER
                fromSmsApp -> PromoFilter.Source.SMS
                else -> PromoFilter.Source.CHAT
            }
            return lines.filter { it.kind == Kind.MESSAGE.name || (email && it.kind == Kind.OTHER.name) }
                .filterNot { line -> PromoFilter.isPromo(source, line.sender ?: line.title, line.text, seen.channel, seen.category) }
                .map { line ->
                    InboxMessage(
                        source = InboxMessage.Source.NOTIFICATION.name,
                        dedupeKey = "n|${line.dedupeKey}",
                        packageName = line.packageName,
                        appLabel = line.appLabel,
                        conversationKey = line.conversationKey,
                        title = line.title,
                        sender = if (line.fromMe) null else line.sender ?: line.title,
                        fromMe = line.fromMe,
                        text = line.text,
                        at = line.postedAt,
                        keptAt = now,
                    )
                }
        }
    }
}

/** The pure part of [Inbox.search]: which words to look for, and how the messages found rank. */
object InboxSearch {

    /** [query]'s words worth matching, lower case, less [without] (the sender, searched apart). */
    fun terms(query: String, without: String? = null): List<String> {
        val skip = without?.lowercase(Locale.ROOT)?.let { WORD.findAll(it).map { m -> m.value }.toSet() }.orEmpty()
        return WORD.findAll(query.lowercase(Locale.ROOT)).map { it.value }
            .filter { it.length >= 2 && it !in STOP && it !in skip }
            .distinct()
            .take(MAX_TERMS)
            .toList()
    }

    /** An FTS4 MATCH expression: any of [terms], each as a word's start ("trip" finds "trips"). */
    fun match(terms: List<String>): String = terms.joinToString(" OR ") { "$it*" }

    /** "%rahul%": for the sender or chat name. */
    fun likePattern(name: String): String = "%" + name.trim().replace("%", "").replace("_", "") + "%"

    /** [rows] from someone, or in a chat, named like [name]: all of it, or its first word ("Rahul" for "Rahul Menon"). */
    fun named(rows: List<InboxMessage>, name: String?): List<InboxMessage> {
        val wanted = name?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return rows
        val first = WORD.find(wanted)?.value ?: wanted
        return rows.filter { row ->
            val names = listOfNotNull(row.sender, row.title).map { it.lowercase(Locale.ROOT) }
            names.any { wanted in it } || names.any { n -> WORD.findAll(n).any { it.value == first } }
        }
    }

    /** [rows] in an app named like [app] ("whatsapp", "sms", "telegram"). */
    fun inApp(rows: List<InboxMessage>, app: String?): List<InboxMessage> {
        val wanted = app?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return rows
        val sms = wanted in SMS_WORDS
        return rows.filter { row ->
            row.appLabel.lowercase(Locale.ROOT).contains(wanted) ||
                row.packageName.lowercase(Locale.ROOT).contains(wanted.replace(" ", "")) ||
                (sms && (row.source == InboxMessage.Source.SMS.name || row.packageName in SMS_APPS))
        }
    }

    /** Most of the words first, then the newest. */
    fun rank(terms: List<String>, rows: List<InboxMessage>, now: Long): List<InboxMessage> {
        if (terms.isEmpty()) return rows.sortedByDescending { it.at }
        return rows.sortedWith(
            compareByDescending<InboxMessage> { row ->
                val words = WORD.findAll(listOfNotNull(row.title, row.sender, row.text).joinToString(" ").lowercase(Locale.ROOT)).map { it.value }.toList()
                val matched = terms.count { term -> words.any { it.startsWith(term) } }
                matched.toDouble() / terms.size + RECENCY * exp(-(now - row.at).coerceAtLeast(0) / RECENCY_MS)
            }.thenByDescending { it.at },
        )
    }

    private const val MAX_TERMS = 8
    private const val RECENCY = 0.15
    private const val RECENCY_MS = 14 * 24 * 60 * 60_000.0

    private val WORD = Regex("[\\p{L}\\p{M}\\p{N}]+")

    private val SMS_WORDS = setOf("sms", "text", "texts", "text message", "text messages", "messages")
    private val SMS_APPS = setOf("com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms", "com.oneplus.mms")

    /** Words that ask, not what was said: "what did Rahul say about the trip" is about "trip". */
    private val STOP = setOf(
        "a", "an", "the", "and", "or", "of", "to", "in", "on", "at", "for", "from", "with", "about", "is", "was", "are", "were",
        "what", "when", "where", "who", "which", "how", "why", "did", "do", "does", "say", "said", "says", "tell", "told",
        "me", "my", "i", "you", "your", "he", "she", "they", "them", "him", "her", "it", "this", "that", "any", "anything",
        "message", "messages", "msg", "text", "texts", "sms", "sent", "send", "wrote", "write", "chat", "whatsapp", "telegram",
        "last", "latest", "recent", "today", "yesterday", "week", "month", "inbox", "find", "check", "show", "read",
    )
}
