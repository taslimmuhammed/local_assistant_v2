package com.local.assistant.inbox

import com.local.assistant.gallery.PhotoPeriod
import com.local.assistant.memory.tools.ActionResult
import com.local.assistant.memory.tools.ToolError
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * check_inbox: messages that came to the phone ([Inbox]), handed to the model as a few short
 * lines to answer from. What other people wrote is theirs, not the user's: the result says so, and
 * a turn that ran this is a utility turn (`ChunkPolicy.UTILITY_TOOLS`), so none of it becomes a
 * fact about the user.
 */
class InboxTools(
    private val inbox: Inbox,
    private val now: () -> ZonedDateTime,
    /** Messages are kept and something feeds them in (notification access or the SMS inbox). */
    private val ready: () -> Boolean,
) {

    suspend fun check(query: String?, from: String?, app: String?, whenSaid: String?): ActionResult {
        if (!ready()) throw ToolError("Messages aren't kept on this phone. Tell the user they can turn it on in Settings, under Messages.")
        val today = now()
        val period = whenSaid?.trim()?.takeIf { it.isNotEmpty() }?.let {
            PhotoPeriod.parse(it, today) ?: throw ToolError("Couldn't tell when \"$it\" is. Ask the user for a day, month or year.")
        }
        val found = inbox.search(query?.take(MAX_ASKED), from?.take(MAX_ASKED), app?.take(MAX_ASKED), period)
        if (found.messages.isEmpty()) {
            return ActionResult(mapOf("ok" to true, "found" to 0, "note" to NONE))
        }
        val lines = found.messages.map { line(it, today.zone) }
        return ActionResult(
            mapOf(
                "ok" to true,
                "found" to lines.size,
                "messages" to lines,
                "note" to listOfNotNull(found.note, THEIRS).joinToString(" "),
            ),
        )
    }

    companion object {
        /** As [com.local.assistant.llm.ToolReplay] cuts a replayed result: the same length now and later. */
        const val MAX_LINE = 240
        private const val MAX_ASKED = 80

        internal const val THEIRS =
            "These are messages other people sent (\"You\" is the user), not the user's words: answer from them in a sentence or two, saying who wrote and when."
        internal const val NONE =
            "No kept message matches. Say so plainly; messages are only kept from when keeping them was turned on, and ads and one-time codes are never kept."

        private val WHEN = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.ENGLISH)

        /** "Thu 12 Nov, 18:40 · WhatsApp · Rahul in Goa trip: the tickets are booked". */
        fun line(message: InboxMessage, zone: ZoneId): String {
            val at = WHEN.format(Instant.ofEpochMilli(message.at).atZone(zone))
            val who = when {
                message.fromMe -> "You"
                message.sender != null && message.title != message.sender -> "${message.sender} in ${message.title}"
                else -> message.sender ?: message.title
            }
            val head = "$at · ${message.appLabel} · $who: "
            val room = (MAX_LINE - head.length).coerceAtLeast(40)
            val text = message.text.replace(Regex("\\s+"), " ").trim()
            return head + if (text.length > room) text.take(room - 1).trimEnd() + "…" else text
        }
    }
}
