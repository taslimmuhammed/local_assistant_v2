package com.local.assistant.memory.tools

import java.time.DayOfWeek

import com.local.assistant.memory.db.EventEntity
import com.local.assistant.memory.db.FactEntity
import com.local.assistant.memory.db.ForgetResult
import com.local.assistant.memory.db.TaskEntity
import com.local.assistant.memory.prompt.Snippet

/**
 * The small confirmation under a reply: "Saved · Dentist: Dr. Rao · Undo".
 *
 * Times are kept as epoch millis and formatted when shown, so the chip follows the device's
 * zone and locale rather than whatever they were when it was written.
 */
data class MemoryChip(
    val kind: Kind,
    /** "Saved", "Reminder", "Event", "Done", "Moved", "Forgot"… */
    val label: String,
    /** "Dentist: Dr. Rao", "Call the CA". */
    val detail: String,
    val at: Long? = null,
    val allDay: Boolean = false,
    /** A caveat worth showing, e.g. that the reminder may be a few minutes late. */
    val note: String? = null,
    /** Whether the time can be changed from the chip. */
    val editable: Boolean = false,
) {
    enum class Kind { FACT, TASK, EVENT, FORGET, ALARM, IMAGE }
}

/**
 * What undoing a tool call needs: the state before it. Kept in the TOOL row itself, so an undo
 * still works after a restart and no separate table is needed.
 */
data class UndoToken(
    val previousFact: FactEntity? = null,
    val writtenFact: FactEntity? = null,
    val createdTaskId: Long? = null,
    val previousTask: TaskEntity? = null,
    val createdEventId: Long? = null,
    val previousEvent: EventEntity? = null,
    val forgotten: ForgetResult? = null,
    val createdNoteId: Long? = null,
)

/**
 * One tool call as persisted in a TOOL message row: what was asked, what came back, and what the
 * chip under the reply shows. Never shown as a bubble, chunked or embedded.
 */
data class ToolRecord(
    val tool: String,
    val arguments: Map<String, Any?>,
    /** The compact JSON the model was given back. */
    val result: String,
    val chip: MemoryChip? = null,
    val undo: UndoToken? = null,
    val undone: Boolean = false,
)

/** Where a tool call happens: which chat, and the user message that asked for it. */
data class ToolContext(val chatId: Long, val userMessageId: Long)

/** Persists tool calls as TOOL rows in the chat. */
interface ToolLog {
    suspend fun record(chatId: Long, record: ToolRecord): Long

    suspend fun read(id: Long): ToolRecord?

    suspend fun update(id: Long, record: ToolRecord)
}

/**
 * Alarms in the phone's own clock app — the ones that ring loudly and on time — as opposed to the
 * app's reminders. They belong to the clock app once set: it is where they are changed or deleted.
 */
interface SystemAlarms {
    /** Whether there is a clock app that accepts alarms from other apps. */
    fun available(): Boolean

    /**
     * Sets an alarm at [hour]:[minute]. With no [days] it rings once, the next time that time
     * comes round; with days it repeats on them. False if the clock app could not be reached.
     */
    fun set(hour: Int, minute: Int, days: Set<DayOfWeek>, label: String?): Boolean

    fun openClock()
}

/** Makes reminders and event alerts go off. The Android implementation uses AlarmManager. */
interface ReminderScheduler {
    /** Whether alarms can be exact; without the permission they may arrive a few minutes late. */
    fun canScheduleExact(): Boolean

    fun schedule(taskId: Long, dueAt: Long)

    fun cancel(taskId: Long)

    /** Alerts about the event starting at [startsAt], at [alertAt] (ahead of it). */
    fun scheduleEvent(eventId: Long, alertAt: Long, startsAt: Long)

    fun cancelEvent(eventId: Long)
}

/** Archived exchanges matching a query, best first, for search_memory. */
fun interface ArchiveSearch {
    suspend fun search(query: String, limit: Int): List<Snippet>
}
