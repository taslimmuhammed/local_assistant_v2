package com.local.assistant.memory.tools

import com.google.gson.Gson
import com.local.assistant.llm.ToolCall
import com.local.assistant.memory.core.FactDecision
import com.local.assistant.memory.core.FactKeys
import com.local.assistant.memory.core.FactLabels
import com.local.assistant.memory.db.EventEntity
import com.local.assistant.memory.db.FactEntity
import com.local.assistant.memory.db.FactOrigin
import com.local.assistant.memory.db.MemoryStore
import com.local.assistant.memory.db.TaskEntity
import com.local.assistant.memory.db.TaskStatus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Runs the model's tool calls against the memory store.
 *
 * Each call is validated, deduplicated (the same call within [dedupeWindowMs] returns what the
 * first one did — models do repeat themselves), applied in one transaction together with its
 * TOOL row, and answered with a compact JSON result: `{"ok":true,"id":42,"due":"Tue 22 Sep 11:00"}`.
 * Arguments the app cannot use come back as `{"ok":false,"error":"…"}` so the model asks the user
 * rather than guessing.
 *
 * Everything that changed is recorded with enough to undo it, and anything worth showing comes
 * back as a chip for under the reply.
 */
class ToolExecutor(
    private val store: MemoryStore,
    private val reminders: ReminderScheduler,
    private val log: ToolLog,
    private val now: () -> ZonedDateTime,
    private val resolver: WhenResolver = WhenResolver(),
    private val defaults: WhenDefaults = WhenDefaults(),
    private val dedupeWindowMs: Long = DEDUPE_WINDOW_MS,
) {

    data class Outcome(
        /** The JSON the model gets back. */
        val result: String,
        val ok: Boolean,
        /** The TOOL row this call was recorded in. */
        val recordId: Long?,
        val chip: MemoryChip?,
        /** Whether the system prefix (core memory, agenda) now renders differently. */
        val changedPrefix: Boolean,
    )

    enum class UndoResult { UNDONE, ALREADY_UNDONE, CHANGED_SINCE, NOT_UNDOABLE }

    private class ToolError(message: String) : Exception(message)

    private sealed interface AlarmChange {
        data class Schedule(val taskId: Long, val dueAt: Long) : AlarmChange
        data class Cancel(val taskId: Long) : AlarmChange
        data class ScheduleEvent(val eventId: Long, val alert: EventTimes.Alert) : AlarmChange
        data class CancelEvent(val eventId: Long) : AlarmChange
    }

    private data class Applied(
        val result: Map<String, Any?>,
        val chip: MemoryChip? = null,
        val undo: UndoToken? = null,
        val changedPrefix: Boolean = false,
        val alarms: List<AlarmChange> = emptyList(),
    )

    private val recentMutex = Mutex()
    private val recent = LinkedHashMap<String, Pair<Long, Outcome>>()

    suspend fun execute(call: ToolCall, context: ToolContext): Outcome {
        val nowMs = nowMs()
        val key = dedupeKey(call)
        recentMutex.withLock {
            recent.entries.removeAll { nowMs - it.value.first > dedupeWindowMs }
            recent[key]?.let { (_, first) -> return first.copy(chip = null, changedPrefix = false) }
        }

        val (applied, recordId) = store.transaction {
            val applied = try {
                dispatch(call, context)
            } catch (e: ToolError) {
                Applied(failure(e.message.orEmpty()))
            }
            val record = ToolRecord(call.name, call.arguments, gson.toJson(applied.result), applied.chip, applied.undo)
            applied to log.record(context.chatId, record)
        }
        applied.alarms.forEach(::applyAlarm)

        val ok = applied.result["ok"] == true
        val outcome = Outcome(gson.toJson(applied.result), ok, recordId, applied.chip, applied.changedPrefix)
        if (ok && call.name in ToolCatalog.WRITES) recentMutex.withLock { recent[key] = nowMs to outcome }
        return outcome
    }

    private suspend fun dispatch(call: ToolCall, context: ToolContext): Applied {
        val args = Args(call.arguments)
        return when (call.name) {
            ToolCatalog.ADD_TASK -> addTask(args, context)
            ToolCatalog.UPDATE_TASK -> updateTask(args)
            ToolCatalog.ADD_EVENT -> addEvent(args, context)
            ToolCatalog.SAVE_FACT -> saveFact(args, context)
            ToolCatalog.GET_UPCOMING -> getUpcoming(args)
            ToolCatalog.SEARCH_MEMORY -> searchMemory(args)
            ToolCatalog.FORGET -> forget(args)
            else -> throw ToolError("There is no tool called ${call.name}.")
        }
    }

    // ---- Tasks ----

    private suspend fun addTask(args: Args, context: ToolContext): Applied {
        val title = tidyTitle(args.required("title"))
        val rule = args.text("repeat")?.let { RepeatRule.parse(it) ?: throw ToolError("I couldn't understand repeat '$it'. Ask how often.") }
        val now = now()
        val due: ZonedDateTime? = when (val whenText = args.text("when")) {
            null -> rule?.let { firstOccurrence(it, now) }
            else -> future(whenText, now).at
        }

        val nowMs = nowMs()
        val id = store.insertTask(
            TaskEntity(
                title = title,
                dueAt = due?.millis(),
                repeatRule = rule?.toRrule(),
                sourceMessageId = context.userMessageId,
                createdAt = nowMs,
                updatedAt = nowMs,
            ),
        )
        return Applied(
            result = ok("id" to id, "due" to due?.let(::modelTime), "repeats" to rule?.let { true }),
            chip = MemoryChip(
                kind = MemoryChip.Kind.TASK,
                label = if (due != null) "Reminder" else "To-do",
                detail = title,
                at = due?.millis(),
                note = lateNote(due != null),
                editable = true,
            ),
            undo = UndoToken(createdTaskId = id),
            changedPrefix = true,
            alarms = listOfNotNull(due?.let { AlarmChange.Schedule(id, it.millis()) }),
        )
    }

    private suspend fun updateTask(args: Args): Applied {
        val statusText = args.text("status")
        val whenText = args.text("when")
        if (statusText == null && whenText == null) throw ToolError("Say what to change: status or when.")
        val task = findTask(args.required("task"))
        val status = statusText?.let(::parseStatus)
        val now = now()
        val nowMs = nowMs()

        var updated = task.copy(updatedAt = nowMs)
        var label = "Updated"
        if (whenText != null) {
            val resolved = resolver.resolve(whenText, now) ?: throw ToolError("I couldn't understand the time '$whenText'. Ask the user when.")
            // "Move it to 3" keeps the reminder's own day.
            val current = task.dueAt?.let { Instant.ofEpochMilli(it).atZone(now.zone) }
            val at = if (!resolved.daySaid && current != null) {
                current.toLocalDate().atTime(resolved.at.toLocalTime()).atZone(now.zone)
            } else {
                resolved.at
            }
            if (!at.isAfter(now)) throw ToolError("'$whenText' has already passed. Ask for a later time.")
            updated = updated.copy(dueAt = at.millis(), status = TaskStatus.OPEN, completedAt = null)
            label = "Moved"
        }
        when (status) {
            TaskStatus.DONE -> {
                updated = TaskOps.complete(updated, now)
                label = if (updated.status == TaskStatus.OPEN) "Done, next one set" else "Done"
            }
            TaskStatus.CANCELLED -> {
                updated = updated.copy(status = TaskStatus.CANCELLED)
                label = "Cancelled"
            }
            TaskStatus.OPEN -> if (task.status != TaskStatus.OPEN) {
                updated = updated.copy(status = TaskStatus.OPEN, completedAt = null)
                label = "Reopened"
            }
            null -> Unit
        }
        store.updateTask(updated)

        val live = updated.status == TaskStatus.OPEN && updated.dueAt != null && updated.dueAt > nowMs
        return Applied(
            result = ok(
                "id" to task.id,
                "status" to updated.status.name.lowercase(Locale.ROOT),
                "due" to updated.dueAt?.let { modelTime(it.atZone(now)) },
            ),
            chip = MemoryChip(
                kind = MemoryChip.Kind.TASK,
                label = label,
                detail = task.title,
                at = updated.dueAt.takeIf { updated.status == TaskStatus.OPEN },
                note = lateNote(live),
                editable = updated.status == TaskStatus.OPEN,
            ),
            undo = UndoToken(previousTask = task),
            changedPrefix = true,
            alarms = listOf(if (live) AlarmChange.Schedule(task.id, updated.dueAt!!) else AlarmChange.Cancel(task.id)),
        )
    }

    /** A task by id ("42", "#42", "task 42") or by words from its title. */
    private suspend fun findTask(reference: String): TaskEntity {
        ID_REFERENCE.matchEntire(reference.trim().lowercase(Locale.ROOT))?.let { match ->
            val id = match.groupValues[1].toLong()
            return store.task(id) ?: throw ToolError("There is no reminder #$id.")
        }
        val words = significantWords(reference)
        if (words.isEmpty()) throw ToolError("Which reminder? Use its id or words from its title.")

        fun score(task: TaskEntity): Int {
            val title = task.title.lowercase(Locale.ROOT)
            return words.count { it in title }
        }
        val open = store.openTasks().map { it to score(it) }.filter { it.second > 0 }
        val candidates = open.ifEmpty {
            // Reopening a finished one: look beyond the open list.
            words.flatMap { store.tasksMentioning(it, 5) }.distinctBy { it.id }.map { it to score(it) }
        }
        val best = candidates.maxOfOrNull { it.second } ?: throw ToolError("I couldn't find a reminder matching '$reference'.")
        val top = candidates.filter { it.second == best }.map { it.first }
        if (top.size > 1) {
            val choices = top.take(3).joinToString("; ") { "#${it.id} ${it.title}" }
            throw ToolError("More than one reminder matches: $choices. Ask which one.")
        }
        return top.single()
    }

    private fun parseStatus(text: String): TaskStatus = when (text.trim().lowercase(Locale.ROOT)) {
        "done", "complete", "completed", "finished", "finish", "did it" -> TaskStatus.DONE
        "cancelled", "canceled", "cancel", "delete", "deleted", "remove", "removed", "drop" -> TaskStatus.CANCELLED
        "open", "reopen", "reopened", "pending", "undone", "not done" -> TaskStatus.OPEN
        else -> throw ToolError("status must be done, cancelled or open.")
    }

    // ---- Events ----

    private suspend fun addEvent(args: Args, context: ToolContext): Applied {
        val title = tidyTitle(args.required("title"))
        val whenText = args.required("when")
        val now = now()
        val start = resolver.resolve(whenText, now) ?: throw ToolError("I couldn't understand the time '$whenText'. Ask the user when.")
        val allDay = start.dateOnly
        val startsAt = if (allDay) start.at.toLocalDate().atStartOfDay(now.zone) else start.at
        val stillAhead = if (allDay) !start.at.toLocalDate().isBefore(now.toLocalDate()) else start.at.isAfter(now)
        if (!stillAhead) throw ToolError("'$whenText' has already passed. Ask for the right date.")

        val endsAt = args.text("ends")?.let { endsText ->
            val end = resolver.resolve(endsText, now) ?: throw ToolError("I couldn't understand the end time '$endsText'.")
            val at = if (!end.daySaid) startsAt.toLocalDate().atTime(end.at.toLocalTime()).atZone(now.zone) else end.at
            if (!at.isAfter(startsAt)) throw ToolError("The end has to be after the start.")
            at
        }
        val rule = args.text("repeat")?.let { RepeatRule.parse(it) ?: throw ToolError("I couldn't understand repeat '$it'.") }

        val nowMs = nowMs()
        val event = EventEntity(
            title = title,
            startsAt = startsAt.millis(),
            endsAt = endsAt?.millis(),
            allDay = allDay,
            recurrence = rule?.toRrule(),
            notes = args.text("notes"),
            sourceMessageId = context.userMessageId,
            createdAt = nowMs,
            updatedAt = nowMs,
        )
        val id = store.insertEvent(event)
        val alert = alertFor(event.copy(id = id))
        return Applied(
            result = ok(
                "id" to id,
                "starts" to if (allDay) modelDate(startsAt) else modelTime(startsAt),
                "ends" to endsAt?.let(::modelTime),
                "repeats" to rule?.let { true },
            ),
            chip = MemoryChip(
                MemoryChip.Kind.EVENT,
                "Event",
                title,
                startsAt.millis(),
                allDay = allDay,
                note = lateNote(alert != null),
                editable = true,
            ),
            undo = UndoToken(createdEventId = id),
            changedPrefix = true,
            alarms = listOfNotNull(alert?.let { AlarmChange.ScheduleEvent(id, it) }),
        )
    }

    // ---- Facts ----

    private suspend fun saveFact(args: Args, context: ToolContext): Applied {
        val subject = args.required("subject")
        val attribute = args.required("attribute")
        val value = args.required("value")
        if (value.length > MAX_FACT_LENGTH) throw ToolError("That is too long to keep as a fact; keep it short.")
        val core = args.bool("core") ?: false

        val written = store.saveFact(subject, attribute, value, FactOrigin.CHAT, pin = core, sourceMessageId = context.userMessageId)
        val fact = written.fact ?: throw ToolError("subject, attribute and value are all needed.")
        val previous = written.previous
        val changedPrefix = fact.core || previous?.core == true

        val label = when (written.decision) {
            is FactDecision.Insert -> "Saved"
            is FactDecision.Update -> "Updated"
            // Restating what was already known changes nothing worth a chip, unless it pinned it.
            else -> if (fact.core && previous?.core == false) "Saved" else null
        }
        return Applied(
            result = ok(
                "id" to fact.id,
                "saved" to "${FactLabels.attribute(fact.attribute)}: ${fact.value}",
                "core" to fact.core.takeIf { it },
                "unchanged" to (label == null).takeIf { it },
            ),
            chip = label?.let {
                MemoryChip(MemoryChip.Kind.FACT, it, describe(fact), note = if (fact.core) "Always in mind" else null)
            },
            undo = label?.let { UndoToken(previousFact = previous, writtenFact = fact) },
            changedPrefix = changedPrefix,
        )
    }

    private suspend fun forget(args: Args): Applied {
        val subject = args.required("subject")
        val attribute = args.text("attribute")
        var forgotten = store.forgetFacts(subject, attribute)
        // "Forget my dentist" arrives as forget(subject = "dentist") as often as it arrives as
        // forget(subject = "user", attribute = "dentist").
        if (forgotten.removed.isEmpty() && attribute == null && FactKeys.subject(subject) != FactKeys.USER) {
            val asAttribute = store.forgetFacts(FactKeys.USER, subject)
            if (asAttribute.removed.isNotEmpty()) {
                // It was the user's dentist, not an entity called "dentist": withdraw that tombstone.
                store.unforget(forgotten)
                forgotten = asAttribute
            } else {
                // Nothing stored either way. The first reading's tombstone still stops extraction
                // learning it from older messages; the second is noise.
                store.unforget(asAttribute)
            }
        }
        val removed = forgotten.removed
        return Applied(
            result = ok("removed" to removed.size),
            chip = removed.takeIf { it.isNotEmpty() }?.let {
                val what = if (it.size == 1) describe(it.single()).substringBefore(':') else "${it.size} things about ${subjectLabel(forgotten.subject)}"
                MemoryChip(MemoryChip.Kind.FORGET, "Forgot", what)
            },
            undo = UndoToken(forgotten = forgotten).takeIf { removed.isNotEmpty() },
            changedPrefix = removed.any { it.core },
        )
    }

    // ---- Reading ----

    private suspend fun getUpcoming(args: Args): Applied {
        val days = (args.int("days") ?: 7).coerceIn(1, 60)
        val now = now()
        val from = now.millis()
        val to = now.plusDays(days.toLong()).millis()
        val tasks = store.tasksDueBetween(from, to).map { Triple(it.dueAt!!, "task", it) }
        val events = store.eventsBetween(from, to).mapNotNull { event ->
            EventTimes.nextStart(event, from, now.zone)?.takeIf { it < to }?.let { Triple(it, "event", event) }
        }
        val items = (tasks + events).sortedBy { it.first }
        return Applied(
            ok(
                "items" to items.take(MAX_LISTED).map { (at, type, item) ->
                    val (id, title) = when (item) {
                        is TaskEntity -> item.id to item.title
                        is EventEntity -> item.id to item.title
                        else -> error("unreachable")
                    }
                    mapOf("id" to id, "type" to type, "title" to title, "at" to modelTime(at.atZone(now)))
                },
                "more" to (items.size - MAX_LISTED).takeIf { it > 0 },
            ),
        )
    }

    private suspend fun searchMemory(args: Args): Applied {
        val words = significantWords(args.required("query"))
        if (words.isEmpty()) throw ToolError("Search for what? Give a word or two.")
        val facts = store.searchFacts(words, MAX_FOUND).map(::describe)
        val now = now()
        val tasks = words.take(3).flatMap { store.tasksMentioning(it, MAX_FOUND) }.distinctBy { it.id }
            .map { "${it.title} (task ${it.id}, ${it.status.name.lowercase(Locale.ROOT)}${it.dueAt?.let { at -> ", " + modelTime(at.atZone(now)) }.orEmpty()})" }
        val events = words.take(3).flatMap { store.eventsMentioning(it, MAX_FOUND) }.distinctBy { it.id }
            .map { "${it.title} (event ${it.id}, ${modelTime(it.startsAt.atZone(now))})" }
        return Applied(
            ok(
                "facts" to facts.takeIf { it.isNotEmpty() },
                "items" to (tasks + events).take(MAX_FOUND).takeIf { it.isNotEmpty() },
                "found" to (facts.size + tasks.size + events.size),
            ),
        )
    }

    // ---- Undo and edit ----

    /** Puts things back as they were before the call recorded in [recordId]. */
    suspend fun undo(recordId: Long): UndoResult {
        val record = log.read(recordId) ?: return UndoResult.NOT_UNDOABLE
        if (record.undone) return UndoResult.ALREADY_UNDONE
        val token = record.undo ?: return UndoResult.NOT_UNDOABLE
        val alarms = mutableListOf<AlarmChange>()
        val restored = store.transaction {
            val done = when {
                token.writtenFact != null -> store.restoreFact(token.previousFact, token.writtenFact)
                token.createdTaskId != null -> {
                    store.deleteTask(token.createdTaskId)
                    alarms += AlarmChange.Cancel(token.createdTaskId)
                    true
                }
                token.previousTask != null -> {
                    val previous = token.previousTask
                    store.updateTask(previous)
                    val live = previous.status == TaskStatus.OPEN && previous.dueAt != null && previous.dueAt > nowMs()
                    alarms += if (live) AlarmChange.Schedule(previous.id, previous.dueAt!!) else AlarmChange.Cancel(previous.id)
                    true
                }
                token.createdEventId != null -> {
                    store.deleteEvent(token.createdEventId)
                    alarms += AlarmChange.CancelEvent(token.createdEventId)
                    true
                }
                token.previousEvent != null -> {
                    store.updateEvent(token.previousEvent)
                    alarms += alertFor(token.previousEvent)?.let { AlarmChange.ScheduleEvent(token.previousEvent.id, it) }
                        ?: AlarmChange.CancelEvent(token.previousEvent.id)
                    true
                }
                token.forgotten != null -> {
                    store.unforget(token.forgotten)
                    true
                }
                else -> false
            }
            if (done) log.update(recordId, record.copy(undone = true))
            done
        }
        alarms.forEach(::applyAlarm)
        return if (restored) UndoResult.UNDONE else UndoResult.CHANGED_SINCE
    }

    /** Moves the reminder or event recorded in [recordId] to [at]. False if it no longer exists. */
    suspend fun editTime(recordId: Long, at: Long): Boolean {
        val record = log.read(recordId) ?: return false
        val chip = record.chip ?: return false
        val token = record.undo
        val nowMs = nowMs()
        val alarms = mutableListOf<AlarmChange>()
        val edited = store.transaction {
            when (chip.kind) {
                MemoryChip.Kind.TASK -> {
                    val id = token?.createdTaskId ?: token?.previousTask?.id ?: return@transaction false
                    val task = store.task(id) ?: return@transaction false
                    store.updateTask(task.copy(dueAt = at, status = TaskStatus.OPEN, completedAt = null, updatedAt = nowMs))
                    if (at > nowMs) alarms += AlarmChange.Schedule(id, at) else alarms += AlarmChange.Cancel(id)
                }
                MemoryChip.Kind.EVENT -> {
                    val id = token?.createdEventId ?: token?.previousEvent?.id ?: return@transaction false
                    val event = store.event(id) ?: return@transaction false
                    val length = event.endsAt?.minus(event.startsAt)
                    val moved = event.copy(startsAt = at, endsAt = length?.let { at + it }, allDay = false, updatedAt = nowMs)
                    store.updateEvent(moved)
                    alarms += alertFor(moved)?.let { AlarmChange.ScheduleEvent(id, it) } ?: AlarmChange.CancelEvent(id)
                }
                else -> return@transaction false
            }
            log.update(recordId, record.copy(chip = chip.copy(at = at, allDay = false)))
            true
        }
        alarms.forEach(::applyAlarm)
        return edited
    }

    // ---- Helpers ----

    private fun applyAlarm(change: AlarmChange) = when (change) {
        is AlarmChange.Schedule -> reminders.schedule(change.taskId, change.dueAt)
        is AlarmChange.Cancel -> reminders.cancel(change.taskId)
        is AlarmChange.ScheduleEvent -> reminders.scheduleEvent(change.eventId, change.alert.at, change.alert.startsAt)
        is AlarmChange.CancelEvent -> reminders.cancelEvent(change.eventId)
    }

    private fun alertFor(event: EventEntity): EventTimes.Alert? =
        EventTimes.nextAlert(event, nowMs(), now().zone, defaults.dateOnly)

    /** A time that has not already gone, or a ToolError the model can relay. */
    private fun future(whenText: String, now: ZonedDateTime): ResolvedWhen {
        val resolved = resolver.resolve(whenText, now) ?: throw ToolError("I couldn't understand the time '$whenText'. Ask the user when.")
        if (!resolved.at.isAfter(now)) throw ToolError("'$whenText' has already passed. Ask for a later time.")
        return resolved
    }

    /** A repeating task with no time given starts at the default time, the next time it comes round. */
    private fun firstOccurrence(rule: RepeatRule, now: ZonedDateTime): ZonedDateTime? {
        val today = now.toLocalDate().atTime(defaults.dateOnly).atZone(now.zone)
        return if (today.isAfter(now) && (rule.byDay.isEmpty() || today.dayOfWeek in rule.byDay)) today else rule.next(today, now)
    }

    private fun lateNote(scheduled: Boolean): String? =
        if (scheduled && !reminders.canScheduleExact()) "may be a few minutes late" else null

    private fun describe(fact: FactEntity): String {
        val attribute = FactLabels.attribute(fact.attribute).replaceFirstChar { it.titlecase(Locale.ROOT) }
        return if (fact.subject == FactKeys.USER) {
            "$attribute: ${fact.value}"
        } else {
            "${subjectLabel(fact.subject)}'s ${FactLabels.attribute(fact.attribute)}: ${fact.value}"
        }
    }

    private fun subjectLabel(subject: String): String =
        subject.replace('_', ' ').replaceFirstChar { it.titlecase(Locale.ROOT) }

    private fun tidyTitle(raw: String): String =
        raw.trim().removePrefix("remind me to ").removePrefix("to ").trim().trimEnd('.')
            .replaceFirstChar { it.titlecase(Locale.ROOT) }

    private fun significantWords(text: String): List<String> =
        text.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{M}\\p{N}]+"))
            .filter { it.length >= 2 && it !in STOP_WORDS }

    private fun dedupeKey(call: ToolCall): String =
        call.name + "|" + call.arguments.toSortedMap().entries.joinToString("|") { (key, value) ->
            "$key=" + value.toString().trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
        }

    private fun ok(vararg fields: Pair<String, Any?>): Map<String, Any?> =
        linkedMapOf<String, Any?>("ok" to true).apply { fields.forEach { (k, v) -> if (v != null) put(k, v) } }

    private fun failure(message: String): Map<String, Any?> = linkedMapOf("ok" to false, "error" to message)

    private fun now(): ZonedDateTime = now.invoke()

    private fun nowMs(): Long = now().millis()

    private fun ZonedDateTime.millis(): Long = toInstant().toEpochMilli()

    private fun Long.atZone(reference: ZonedDateTime): ZonedDateTime = Instant.ofEpochMilli(this).atZone(reference.zone)

    private fun modelTime(at: ZonedDateTime): String =
        if (at.toLocalTime() == LocalTime.MIDNIGHT) modelDate(at) else MODEL_TIME.format(at)

    private fun modelDate(at: ZonedDateTime): String = MODEL_DATE.format(at)

    /** Lenient reads of the model's arguments, which may arrive as strings, numbers or booleans. */
    private class Args(private val map: Map<String, Any?>) {
        fun text(key: String): String? = map[key]
            ?.let { if (it is Number && it.toDouble() == Math.floor(it.toDouble())) it.toLong().toString() else it.toString() }
            ?.trim()
            ?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }

        fun required(key: String): String = text(key) ?: throw ToolError("'$key' is missing.")

        fun bool(key: String): Boolean? = when (val value = map[key]) {
            is Boolean -> value
            is String -> value.trim().lowercase(Locale.ROOT) in setOf("true", "yes", "1")
            is Number -> value.toInt() != 0
            else -> null
        }

        fun int(key: String): Int? = when (val value = map[key]) {
            is Number -> value.toInt()
            is String -> value.trim().toIntOrNull()
            else -> null
        }
    }

    companion object {
        const val DEDUPE_WINDOW_MS = 2 * 60 * 1000L
        private const val MAX_FACT_LENGTH = 300
        private const val MAX_LISTED = 8
        private const val MAX_FOUND = 5

        private val gson = Gson()
        private val MODEL_TIME = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.ENGLISH)
        private val MODEL_DATE = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
        private val ID_REFERENCE = Regex("(?:#|task\\s*#?\\s*)?(\\d+)")

        private val STOP_WORDS = setOf(
            "the", "a", "an", "to", "my", "me", "for", "with", "about", "reminder", "remind", "task",
            "it", "that", "this", "and", "of", "on", "at", "in", "is", "ko", "ka", "ki", "se",
        )
    }
}
