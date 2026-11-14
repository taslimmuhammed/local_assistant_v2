package com.local.assistant.inbox

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Telephony
import android.util.Log
import com.local.assistant.away.NotificationLines
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The SMS inbox read into the [Inbox]: what came before notification access was allowed, and
 * SMS as they arrive. Only while the user keeps messages, the app is the phone's assistant
 * (Google Play allows SMS access to assistants only), and SMS access is allowed: every read checks
 * all three, so losing any one stops it. What was kept stays, as long as chats are.
 *
 * Read from where it got to ([lastId]), at most [BATCH] at a time; on the first read, back as
 * far as chats are kept. Each message goes through the same cleaning as a notification
 * ([NotificationLines.clean]: one-time codes dropped, ID numbers masked) and the [PromoFilter].
 */
class SmsSync(
    private val context: Context,
    private val inbox: Inbox,
    private val scope: CoroutineScope,
    private val keeping: () -> Boolean,
    private val isAssistant: () -> Boolean,
    private val retentionDays: () -> Int,
    /** The SMS provider id read up to: kept, so deleting the kept messages doesn't bring them back. */
    private val lastId: () -> Long,
    private val setLastId: (Long) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val mutex = Mutex()
    private var observer: ContentObserver? = null
    private var pending: Job? = null

    /** Allowed to read the SMS inbox now. */
    fun allowed(): Boolean =
        keeping() && isAssistant() &&
            context.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    /** The phone's SMS app. */
    fun smsApp(): String? = runCatching { Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull()

    /** Reads what's new, soon, and watches for more while the app runs. */
    fun syncSoon(delayMs: Long = 0) {
        if (!allowed()) {
            stopWatching()
            return
        }
        watch()
        synchronized(this) {
            pending?.cancel()
            pending = scope.launch {
                delay(delayMs)
                runCatching { sync() }.onFailure { Log.w(TAG, "Reading SMS failed", it) }
            }
        }
    }

    /** Reads what's new now; how many were kept. */
    suspend fun sync(): Int = mutex.withLock {
        if (!allowed()) return@withLock 0
        val started = android.os.SystemClock.elapsedRealtime()
        var kept = 0
        var read = 0
        val names = HashMap<String, String?>()
        val app = smsApp()
        while (true) {
            val batch = withContext(Dispatchers.IO) { readBatch(lastId(), names) } ?: break
            if (batch.lastId <= lastId()) break
            kept += inbox.keepSms(batch.rows, app)
            read += batch.read
            setLastId(batch.lastId)
            if (batch.read < BATCH) break
            // Another check between batches: the role or the permission may have gone.
            if (!allowed()) break
        }
        if (read > 0) Log.i(TAG, "Read $read SMS, kept $kept, in ${android.os.SystemClock.elapsedRealtime() - started} ms")
        kept
    }

    private class Batch(val rows: List<InboxMessage>, val read: Int, val lastId: Long)

    private fun readBatch(after: Long, names: MutableMap<String, String?>): Batch? {
        val days = retentionDays()
        val since = if (after == 0L && days > 0) clock() - days * DAY_MS else 0L
        val cursor = context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms._ID, Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE),
            "${Telephony.Sms._ID} > ? AND ${Telephony.Sms.DATE} >= ? AND ${Telephony.Sms.TYPE} IN (${Telephony.Sms.MESSAGE_TYPE_INBOX}, ${Telephony.Sms.MESSAGE_TYPE_SENT})",
            arrayOf(after.toString(), since.toString()),
            "${Telephony.Sms._ID} ASC",
        ) ?: return null
        val rows = ArrayList<InboxMessage>()
        var read = 0
        var last = after
        val now = clock()
        cursor.use {
            while (it.moveToNext() && read < BATCH) {
                read++
                val id = it.getLong(0)
                last = maxOf(last, id)
                val address = it.getString(2)?.trim().orEmpty()
                val body = it.getString(3) ?: continue
                val mine = it.getInt(5) == Telephony.Sms.MESSAGE_TYPE_SENT
                // Not "you sent" ads: the user's own texts are kept whatever they say.
                if (!mine && PromoFilter.isPromo(PromoFilter.Source.SMS, address, body)) continue
                val text = NotificationLines.clean(body) ?: continue
                val name = names.getOrPut(address) { contactName(address) } ?: address.ifEmpty { "Unknown" }
                rows += InboxMessage(
                    source = InboxMessage.Source.SMS.name,
                    dedupeKey = "sms|$id",
                    packageName = SMS_PACKAGE,
                    appLabel = "SMS",
                    conversationKey = "sms|${it.getLong(1)}",
                    title = name,
                    sender = if (mine) null else name,
                    fromMe = mine,
                    text = text,
                    at = it.getLong(4),
                    keptAt = now,
                )
            }
        }
        if (read == 0) return null
        return Batch(rows, read, last)
    }

    /** The contact's name for [address], when contacts may be read; never asked for here. */
    private fun contactName(address: String): String? {
        if (address.isEmpty() || address.none(Char::isDigit)) return null
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        return runCatching {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address))
            context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
    }

    private fun watch() = synchronized(this) {
        if (observer != null) return
        observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = syncSoon(SETTLE_MS)
        }.also { context.contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, it) }
    }

    fun stopWatching() = synchronized(this) {
        observer?.let { runCatching { context.contentResolver.unregisterContentObserver(it) } }
        observer = null
    }

    companion object {
        private const val TAG = "SmsSync"
        private const val BATCH = 500
        private const val DAY_MS = 24 * 60 * 60_000L

        /** An SMS arriving changes the provider a few times in a row. */
        private const val SETTLE_MS = 2_000L

        /** Kept SMS are filed under this, whatever the SMS app is. */
        const val SMS_PACKAGE = "sms"
    }
}
