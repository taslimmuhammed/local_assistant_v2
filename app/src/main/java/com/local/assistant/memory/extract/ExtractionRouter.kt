package com.local.assistant.memory.extract

import com.local.assistant.data.db.MessageEntity
import com.local.assistant.memory.core.FactDecision
import com.local.assistant.memory.core.FactKeys
import com.local.assistant.memory.db.EventEntity
import com.local.assistant.memory.db.FactOrigin
import com.local.assistant.memory.db.MemoryStore
import com.local.assistant.memory.db.TaskEntity
import com.local.assistant.memory.tools.EventTimes
import com.local.assistant.memory.tools.ReminderScheduler
import com.local.assistant.memory.tools.WhenDefaults
import com.local.assistant.memory.tools.WhenResolver
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * Decides, in code, what an extracted item may change. The model proposes; this validates.
 *
 * - Every item must point at a message in the batch it came from.
 * - Facts go through the ordinary upsert rules with the message's own time as the statement
 *   time: a later statement still wins, a deleted fact's tombstone still holds, and a value the
 *   user typed on the memory screen is never overwritten.
 * - Only standing instructions on how to reply may be core; anything else would sit in every
 *   prompt on the model's say-so.
 * - Tasks and events need a time, resolved against the day the message was written; ones already
 *   past, or already there (the same title within an hour), are skipped.
 */
class ExtractionRouter(
    private val store: MemoryStore,
    private val reminders: ReminderScheduler,
    private val resolver: WhenResolver = WhenResolver(),
    private val defaults: WhenDefaults = WhenDefaults(),
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    data class Outcome(val facts: Int = 0, val tasks: Int = 0, val events: Int = 0, val skipped: Int = 0) {
        operator fun plus(other: Outcome) =
            Outcome(facts + other.facts, tasks + other.tasks, events + other.events, skipped + other.skipped)
    }

    suspend fun route(items: List<ExtractedItem>, messages: Map<Long, MessageEntity>): Outcome {
        var outcome = Outcome()
        for (item in items) {
            val message = messages[item.sourceMessageId]
            val written = message != null && when (item.type) {
                ExtractedItem.Type.FACT -> fact(item, message)
                ExtractedItem.Type.TASK -> task(item, message)
                ExtractedItem.Type.EVENT -> event(item, message)
            }
            outcome += when {
                !written -> Outcome(skipped = 1)
                item.type == ExtractedItem.Type.FACT -> Outcome(facts = 1)
                item.type == ExtractedItem.Type.TASK -> Outcome(tasks = 1)
                else -> Outcome(events = 1)
            }
        }
        return outcome
    }

    private suspend fun fact(item: ExtractedItem, message: MessageEntity): Boolean {
        val subject = item.subject?.takeIf { it.length <= MAX_KEY_CHARS } ?: return false
        val attribute = item.attribute?.takeIf { it.length <= MAX_KEY_CHARS } ?: return false
        val value = item.value?.takeIf { it.length <= MAX_VALUE_CHARS } ?: return false
        val subjectKey = FactKeys.subject(subject)
        val attributeKey = FactKeys.attribute(attribute)
        if (subjectKey.isEmpty() || attributeKey.isEmpty() || subjectKey in NOT_SUBJECTS) return false
        val core = item.core && subjectKey == FactKeys.USER && attributeKey.startsWith(FactKeys.PREFERENCE_PREFIX)
        val result = store.saveFact(
            subject = subject,
            attribute = attribute,
            value = value,
            origin = FactOrigin.EXTRACTED,
            pin = core,
            statedAt = message.createdAt,
            sourceMessageId = message.id,
        )
        return result.decision is FactDecision.Insert || result.decision is FactDecision.Update
    }

    private suspend fun task(item: ExtractedItem, message: MessageEntity): Boolean {
        val title = item.title?.let(::tidy)?.takeIf { it.isNotEmpty() && it.length <= MAX_TITLE_CHARS } ?: return false
        val said = Instant.ofEpochMilli(message.createdAt).atZone(zone())
        val due = item.whenText?.let { resolver.resolve(it, said) }?.at?.toInstant()?.toEpochMilli() ?: return false
        val now = clock()
        if (due <= now || rolledOver(due, message)) return false
        val nearby = store.tasksDueBetween(due - DUPLICATE_WINDOW_MS, due + DUPLICATE_WINDOW_MS)
        if (nearby.any { sameTitle(it.title, title) }) return false
        // One insert, then its alarm: the alarm is only set once the row exists.
        val id = store.insertTask(
            TaskEntity(title = title, dueAt = due, sourceMessageId = message.id, createdAt = now, updatedAt = now),
        )
        reminders.schedule(id, due)
        return true
    }

    private suspend fun event(item: ExtractedItem, message: MessageEntity): Boolean {
        val title = item.title?.let(::tidy)?.takeIf { it.isNotEmpty() && it.length <= MAX_TITLE_CHARS } ?: return false
        val said = Instant.ofEpochMilli(message.createdAt).atZone(zone())
        val start = item.whenText?.let { resolver.resolve(it, said) } ?: return false
        val now = clock()
        val today = Instant.ofEpochMilli(now).atZone(zone()).toLocalDate()
        val startsAt = if (start.dateOnly) start.at.toLocalDate().atStartOfDay(zone()) else start.at
        val ahead = if (start.dateOnly) !start.at.toLocalDate().isBefore(today) else startsAt.toInstant().toEpochMilli() > now
        val at = startsAt.toInstant().toEpochMilli()
        if (!ahead || rolledOver(at, message)) return false
        val nearby = store.eventsBetween(at - DUPLICATE_WINDOW_MS, at + DUPLICATE_WINDOW_MS)
            .filter { it.startsAt in (at - DUPLICATE_WINDOW_MS)..(at + DUPLICATE_WINDOW_MS) }
        if (nearby.any { sameTitle(it.title, title) }) return false
        val event = EventEntity(
            title = title,
            startsAt = at,
            allDay = start.dateOnly,
            sourceMessageId = message.id,
            createdAt = now,
            updatedAt = now,
        )
        val id = store.insertEvent(event)
        EventTimes.nextAlert(event.copy(id = id), now, zone(), defaults.dateOnly)
            ?.let { reminders.scheduleEvent(id, it.at, it.startsAt) }
        return true
    }

    /**
     * The resolver reads a date that has passed as its next occurrence, which is right for someone
     * planning in chat ("on 2 September", said on the 20th, means next year) and wrong for an old
     * message about something already over. A time about a year after the message is that
     * rollover, so it is taken as past.
     */
    private fun rolledOver(at: Long, message: MessageEntity): Boolean = at - message.createdAt > ROLLOVER_MS

    private fun tidy(title: String): String =
        title.trim().trimEnd('.').replaceFirstChar { it.titlecase(Locale.ROOT) }

    companion object {
        /** A task or event with the same title this close to an existing one is the same one. */
        const val DUPLICATE_WINDOW_MS = 60 * 60 * 1000L

        /** Anything this far past the message it came from is a rolled-over past date. */
        private const val ROLLOVER_MS = 300L * 24 * 60 * 60 * 1000

        private const val MAX_KEY_CHARS = 60
        private const val MAX_VALUE_CHARS = 200
        private const val MAX_TITLE_CHARS = 120

        /** Not things the user has facts about. */
        private val NOT_SUBJECTS = setOf("assistant", "you", "model", "ai", "bot")

        fun sameTitle(a: String, b: String): Boolean = normalise(a) == normalise(b)

        private fun normalise(title: String): String =
            title.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() || it.isWhitespace() }
                .split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
    }
}
