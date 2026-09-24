package com.local.assistant.memory.tools

import com.local.assistant.memory.db.EventEntity
import com.local.assistant.memory.db.TaskEntity
import com.local.assistant.memory.db.TaskStatus
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * What happens to a task when it is finished or put off. Shared by the tools and by the
 * notification's own Done and Snooze buttons, so both paths agree.
 */
object TaskOps {

    /**
     * Finishing a repeating task moves it to its next occurrence instead of closing it; it
     * closes only once its rule runs out.
     */
    fun complete(task: TaskEntity, now: ZonedDateTime): TaskEntity {
        val nowMs = now.toInstant().toEpochMilli()
        val rule = task.repeatRule?.let(RepeatRule::parse)
        val due = task.dueAt?.let { Instant.ofEpochMilli(it).atZone(now.zone) }
        if (rule != null && due != null) {
            val after = if (due.isAfter(now)) due else now
            rule.next(due, after)?.let { next ->
                return task.copy(
                    dueAt = next.toInstant().toEpochMilli(),
                    repeatRule = rule.afterOccurrence().toRrule(),
                    status = TaskStatus.OPEN,
                    updatedAt = nowMs,
                )
            }
        }
        return task.copy(status = TaskStatus.DONE, completedAt = nowMs, updatedAt = nowMs)
    }

    fun snooze(task: TaskEntity, now: ZonedDateTime, by: Duration = SNOOZE): TaskEntity =
        task.copy(dueAt = now.plus(by).toInstant().toEpochMilli(), status = TaskStatus.OPEN, updatedAt = now.toInstant().toEpochMilli())

    val SNOOZE: Duration = Duration.ofHours(1)
}

/** When events happen, allowing for ones that repeat. */
object EventTimes {

    /** How far ahead of a timed event its alert goes off. */
    val ALERT_LEAD: Duration = Duration.ofMinutes(30)

    /** An event's next alert still to come, and the start it is for. */
    data class Alert(val at: Long, val startsAt: Long)

    /**
     * The next alert after [now]: [ALERT_LEAD] before a timed event, [allDayAt] on the day of an
     * all-day one. A repeating event whose next alert has passed gets the occurrence after it.
     */
    fun nextAlert(event: EventEntity, now: Long, zone: ZoneId, allDayAt: LocalTime): Alert? {
        var from = now
        repeat(3) {
            val start = nextStart(event, from, zone) ?: return null
            val alert = if (event.allDay) {
                Instant.ofEpochMilli(start).atZone(zone).toLocalDate().atTime(allDayAt).atZone(zone).toInstant().toEpochMilli()
            } else {
                start - ALERT_LEAD.toMillis()
            }
            if (alert > now) return Alert(alert, start)
            // Too late to warn about this one; for a repeating event, the next.
            if (event.recurrence == null) return null
            from = start + 1
        }
        return null
    }


    /** When [event] next starts at or after [from]: itself, or the next time its rule comes round. */
    fun nextStart(event: EventEntity, from: Long, zone: ZoneId): Long? {
        if (event.startsAt >= from) return event.startsAt
        val rule = event.recurrence?.let(RepeatRule::parse) ?: return null
        val anchor = Instant.ofEpochMilli(event.startsAt).atZone(zone)
        val after = Instant.ofEpochMilli(from - 1).atZone(zone)
        return rule.next(anchor, after)?.toInstant()?.toEpochMilli()
    }
}
