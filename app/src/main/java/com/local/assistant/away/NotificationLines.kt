package com.local.assistant.away

import com.local.assistant.privacy.SensitiveText
import java.util.Locale

/**
 * A notification as it was posted, read into plain values so the rules below can be tested
 * without Android ([MessageListener] fills it in).
 */
data class SeenNotification(
    val packageName: String,
    val appLabel: String,
    /** The system's key for it: removing the notification later names this. */
    val key: String,
    val id: Int,
    val tag: String?,
    val postedAt: Long,
    val category: String?,
    val ongoing: Boolean,
    val groupSummary: Boolean,
    /** The channel's importance, as NotificationManager numbers it (1 = min … 4 = high); null when unknown. */
    val importance: Int?,
    val title: String?,
    val text: String?,
    val bigText: String?,
    val lines: List<String> = emptyList(),
    /** A chat app's messages (MessagingStyle), oldest first; empty for any other notification. */
    val messages: List<Message> = emptyList(),
    val conversationTitle: String? = null,
    val isGroup: Boolean = false,
    /** The conversation's shortcut: the steadiest name a chat has across notifications. */
    val shortcutId: String? = null,
) {
    /** [sender] null: the user's own message. */
    data class Message(val sender: String?, val text: String, val at: Long)
}

/**
 * Which notifications go into the brief, and as what lines.
 *
 * Kept: messages, missed calls, and anything else a person might want to hear about (an email,
 * a delivery, a payment). Left out: what is not news (ongoing ones like music or navigation, the
 * app's own, group summaries that only repeat their children, silent-and-minimised ones), and
 * one-time codes, which are useless an hour later and dangerous to keep. Other numbers that look
 * like cards or IDs are masked before they are stored, so neither the screen nor the model sees them.
 */
object NotificationLines {

    /** Lines longer than this are cut: a brief needs the gist, not a whole email. */
    const val MAX_CHARS = 600

    /** Android 15+ shows this to listeners in place of a one-time code. */
    private const val HIDDEN_BY_ANDROID = "sensitive notification content hidden"

    private val SKIPPED_CATEGORIES = setOf(
        "call", // a call ringing or under way, not a missed one
        "transport", "progress", "service", "sys", "status", "navigation", "stopwatch", "alarm",
        "location_sharing", "car_emergency", "car_warning", "car_information",
    )

    private val MISSED_CALL = Regex("\\bmissed\\s+(voice\\s+|video\\s+)?calls?\\b", RegexOption.IGNORE_CASE)

    /** "Your OTP is 4417", "123456 is your verification code": nothing to brief. */
    private val ONE_TIME_CODE = Regex(
        "\\b(otp|one[- ]time (pass(word|code)|code)|verification code|security code|login code|auth(entication)? code)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** NotificationManager.IMPORTANCE_MIN: shown only when the shade is pulled all the way down. */
    private const val IMPORTANCE_MIN = 1

    /**
     * The lines [seen] holds, ready to store; none when it isn't for the brief. [ownPackage] is
     * this app; [ignored] are apps the user left out.
     */
    fun read(seen: SeenNotification, ownPackage: String, ignored: Set<String>, now: Long): List<CapturedEntity> {
        if (seen.packageName == ownPackage || seen.packageName in ignored) return emptyList()
        if (seen.ongoing || seen.groupSummary) return emptyList()
        if (seen.category in SKIPPED_CATEGORIES) return emptyList()
        if (seen.importance != null && seen.importance <= IMPORTANCE_MIN) return emptyList()

        val title = seen.title?.trim().orEmpty()
        val text = (seen.bigText ?: seen.text ?: seen.lines.joinToString("\n")).trim()

        if (seen.category == "missed_call") return listOfNotNull(missedCall(seen, title, text, now))
        // A chat's messages before the words "missed call": "Sorry, missed calls all day" is a message.
        if (seen.messages.isNotEmpty()) return messages(seen, now)
        if (MISSED_CALL.containsMatchIn(title) || MISSED_CALL.containsMatchIn(text)) return listOfNotNull(missedCall(seen, title, text, now))
        if (seen.category == "msg" && title.isNotEmpty() && text.isNotEmpty()) {
            // A chat app that doesn't say who wrote what: the title is the person.
            val line = clean(text) ?: return emptyList()
            val key = "${seen.packageName}|m|${seen.shortcutId ?: title}"
            return listOf(
                line(seen, key, kind = Kind.MESSAGE, title = title, sender = title, fromMe = false, text = line, at = seen.postedAt, now = now, dedupe = "$key|$title|${seen.postedAt}|${line.hashCode()}"),
            )
        }
        val body = clean(if (text.isEmpty()) title else text) ?: return emptyList()
        val key = "${seen.packageName}|n|${seen.tag.orEmpty()}|${seen.id}"
        return listOf(
            line(seen, key, kind = Kind.OTHER, title = title.ifEmpty { seen.appLabel }, sender = null, fromMe = false, text = body, at = seen.postedAt, now = now, dedupe = "$key|${hour(seen.postedAt)}|${body.hashCode()}"),
        )
    }

    private fun messages(seen: SeenNotification, now: Long): List<CapturedEntity> {
        val firstOther = seen.messages.firstOrNull { it.sender != null }?.sender
        val title = seen.conversationTitle?.takeIf { it.isNotBlank() }
            ?: firstOther.takeIf { !seen.isGroup }
            ?: seen.title?.takeIf { it.isNotBlank() }
            ?: seen.appLabel
        val key = "${seen.packageName}|m|${seen.shortcutId ?: title}"
        return seen.messages.mapNotNull { message ->
            val body = clean(message.text) ?: return@mapNotNull null
            val at = if (message.at > 0) message.at else seen.postedAt
            line(
                seen, key, kind = Kind.MESSAGE, title = title, sender = message.sender, fromMe = message.sender == null,
                text = body, at = at, now = now, dedupe = "$key|${message.sender.orEmpty()}|$at|${body.hashCode()}",
            )
        }
    }

    private fun missedCall(seen: SeenNotification, title: String, text: String, now: Long): CapturedEntity? {
        // "Missed call" / "Amma", or "Amma" / "Missed voice call": the caller is the other one.
        val caller = when {
            MISSED_CALL.containsMatchIn(title) && text.isNotEmpty() && !MISSED_CALL.containsMatchIn(text) -> text
            title.isNotEmpty() && !MISSED_CALL.containsMatchIn(title) -> title
            else -> text.ifEmpty { title }
        }.lines().first().trim()
        if (caller.isEmpty()) return null
        val said = listOf(title, text).firstOrNull { MISSED_CALL.containsMatchIn(it) } ?: "Missed call"
        val key = "${seen.packageName}|c|${caller.lowercase(Locale.ROOT)}"
        return line(
            seen, key, kind = Kind.CALL, title = caller, sender = caller, fromMe = false, text = said.trim(),
            at = seen.postedAt, now = now, dedupe = "$key|${hour(seen.postedAt)}|${said.lowercase(Locale.ROOT)}",
        )
    }

    /** [text] as stored: one-time codes dropped, ID numbers masked, cut to [MAX_CHARS]. */
    internal fun clean(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.lowercase(Locale.ROOT).contains(HIDDEN_BY_ANDROID)) return null
        if (ONE_TIME_CODE.containsMatchIn(trimmed)) return null
        if (SensitiveText.find(trimmed).any { it.kind == SensitiveText.Kind.SECRET }) return null
        val masked = SensitiveText.masked(trimmed)
        return if (masked.length > MAX_CHARS) masked.take(MAX_CHARS - 1).trimEnd() + "…" else masked
    }

    private fun hour(at: Long): Long = at / 3_600_000L

    private fun line(
        seen: SeenNotification,
        conversationKey: String,
        kind: Kind,
        title: String,
        sender: String?,
        fromMe: Boolean,
        text: String,
        at: Long,
        now: Long,
        dedupe: String,
    ) = CapturedEntity(
        dedupeKey = dedupe,
        notificationKey = seen.key,
        packageName = seen.packageName,
        appLabel = seen.appLabel,
        kind = kind.name,
        conversationKey = conversationKey,
        title = title,
        sender = sender,
        fromMe = fromMe,
        isGroup = seen.isGroup,
        text = text,
        postedAt = at,
        capturedAt = now,
    )
}
