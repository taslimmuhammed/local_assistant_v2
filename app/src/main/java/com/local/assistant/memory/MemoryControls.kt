package com.local.assistant.memory

import com.google.gson.GsonBuilder
import com.local.assistant.data.db.AppDatabase
import com.local.assistant.data.db.ChatEntity
import com.local.assistant.data.db.MessageEntity
import com.local.assistant.data.repo.ChatRepository
import com.local.assistant.memory.core.AliasPlanner
import com.local.assistant.memory.core.DuplicateSuggestion
import com.local.assistant.memory.core.FactKeys
import com.local.assistant.memory.db.AppStateEntity
import com.local.assistant.memory.db.ArchiveRepository
import com.local.assistant.memory.db.ChunkEntity
import com.local.assistant.memory.db.EventEntity
import com.local.assistant.memory.db.FactEntity
import com.local.assistant.memory.db.FactOrigin
import com.local.assistant.memory.db.ForgetResult
import com.local.assistant.memory.db.MemoryRepository
import com.local.assistant.memory.db.TaskEntity
import com.local.assistant.memory.db.TaskStatus
import com.local.assistant.memory.tools.ReminderScheduler
import com.local.assistant.memory.tools.TaskOps
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.ZoneId

/**
 * Everything the "What you know about me" screen can do: see, edit, pin and delete what the
 * assistant knows, and the global controls. Deleted things stay deleted — a tombstone keeps the
 * nightly pass from learning them again from old messages.
 */
class MemoryControls(
    private val database: AppDatabase,
    private val memory: MemoryRepository,
    private val archive: ArchiveRepository,
    private val chats: ChatRepository,
    private val reminders: ReminderScheduler,
    /** Something the prompt prefix shows changed; the live conversation should catch up. */
    private val onChanged: suspend () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private val facts = database.factDao()
    private val agenda = database.agendaDao()
    private val sessions = database.sessionDao()
    private val state = database.appStateDao()

    fun observeFacts(): Flow<List<FactEntity>> = facts.observeAll()

    fun observeTasks(): Flow<List<TaskEntity>> = agenda.observeOpenTasks()

    /** Events from the start of today on. */
    fun observeEvents(): Flow<List<EventEntity>> =
        agenda.observeEvents(Instant.ofEpochMilli(clock()).atZone(zone()).toLocalDate().atStartOfDay(zone()).toInstant().toEpochMilli())

    // ---- Facts ----

    /** The user typed a new value: it is theirs now, and nothing extracted will overwrite it. */
    suspend fun edit(fact: FactEntity, value: String) {
        val clean = FactKeys.value(value)
        if (clean.isEmpty() || clean == fact.value) return
        val now = clock()
        facts.update(fact.copy(value = clean, origin = FactOrigin.USER_EDIT, statedAt = now, updatedAt = now, lastConfirmedAt = now))
        onChanged()
    }

    /** Always in mind, or not. Only facts about the user themself can be. */
    suspend fun setCore(fact: FactEntity, core: Boolean) {
        if (fact.subject != FactKeys.USER || fact.core == core) return
        facts.update(fact.copy(core = core, updatedAt = clock()))
        onChanged()
    }

    /** What a delete took with it, so undo can put it all back. */
    data class Deletion(val forgotten: ForgetResult, val snippets: List<ChunkEntity>)

    /**
     * Deletes [fact], leaves a tombstone, and empties the archived exchanges that state it: its
     * value as a phrase, alongside the subject (or one of its names) when it is about someone else.
     */
    suspend fun delete(fact: FactEntity): Deletion {
        val forgotten = memory.forgetFacts(fact.subject, fact.attribute)
        val names = if (fact.subject == FactKeys.USER) {
            // "short answers" or "Dr. Rao" can be common words; with the attribute, they are the fact.
            if (fact.value.length < SHORT_VALUE) listOf(FactKeys.attribute(fact.attribute).removePrefix(FactKeys.PREFERENCE_PREFIX).replace('_', ' ')) else emptyList()
        } else {
            listOf(fact.subject.replace('_', ' ')) + aliasesOf(fact.subject)
        }
        val snippets = archive.forget(archive.mentioning(fact.value, names))
        onChanged()
        return Deletion(forgotten, snippets)
    }

    suspend fun undo(deletion: Deletion) {
        memory.unforget(deletion.forgotten)
        archive.restore(deletion.snippets)
        onChanged()
    }

    /** The message a fact came from, and its chat, for "tap to see where this came from". */
    suspend fun source(fact: FactEntity): Pair<MessageEntity, ChatEntity?>? {
        val message = fact.sourceMessageId?.let { chats.message(it) } ?: return null
        return message to chats.chat(message.chatId)
    }

    private suspend fun aliasesOf(subject: String): List<String> =
        facts.aliases().filter { it.subject == subject }.map { it.alias.replace('_', ' ') }

    // ---- Possible duplicates ----

    suspend fun duplicates(): List<DuplicateSuggestion> = AliasPlanner.suggestions(facts.all(), dismissed())

    /** The user says they are the same: [from] becomes a name for [into]. */
    suspend fun merge(from: String, into: String) {
        memory.mergeSubjects(from, into)
        onChanged()
    }

    suspend fun dismiss(suggestion: DuplicateSuggestion) {
        state.put(AppStateEntity(KEY_DISMISSED, (dismissed() + suggestion.key).joinToString("\n")))
    }

    private suspend fun dismissed(): Set<String> =
        state.get(KEY_DISMISSED)?.split("\n")?.filter { it.isNotBlank() }?.toSet().orEmpty()

    // ---- Reminders and events ----

    suspend fun completeTask(task: TaskEntity) {
        val done = TaskOps.complete(task, Instant.ofEpochMilli(clock()).atZone(zone()))
        memory.updateTask(done)
        if (done.status == TaskStatus.OPEN && done.dueAt != null) reminders.schedule(done.id, done.dueAt) else reminders.cancel(task.id)
        onChanged()
    }

    suspend fun deleteTask(task: TaskEntity) {
        memory.deleteTask(task.id)
        reminders.cancel(task.id)
        onChanged()
    }

    suspend fun restoreTask(task: TaskEntity) {
        memory.insertTask(task)
        if (task.status == TaskStatus.OPEN && task.dueAt != null && task.dueAt > clock()) reminders.schedule(task.id, task.dueAt)
        onChanged()
    }

    suspend fun deleteEvent(event: EventEntity) {
        memory.deleteEvent(event.id)
        reminders.cancelEvent(event.id)
        onChanged()
    }

    suspend fun restoreEvent(event: EventEntity) {
        memory.insertEvent(event)
        com.local.assistant.memory.tools.EventTimes.nextAlert(event, clock(), zone(), com.local.assistant.memory.tools.WhenDefaults().dateOnly)
            ?.let { reminders.scheduleEvent(event.id, it.at, it.startsAt) }
        onChanged()
    }

    // ---- Global controls ----

    /**
     * Facts, names, reminders and events, the archive's search index and every summary go.
     * Chats stay as they are, but nothing is ever learned from what was said before now.
     */
    suspend fun forgetEverything(skipExtractionTo: suspend (Long) -> Unit) {
        for (task in agenda.allTasks()) reminders.cancel(task.id)
        for (event in agenda.allEvents()) reminders.cancelEvent(event.id)
        memory.transaction {
            facts.deleteAll()
            facts.deleteAllAliases()
            agenda.deleteAllTasks()
            agenda.deleteAllEvents()
            sessions.clearSummaries()
            database.chatDao().clearRollingSummaries()
        }
        archive.forgetAll()
        skipExtractionTo(archive.lastMessageId())
        onChanged()
    }

    /** Everything above, as JSON the user can keep. Chats are not included. */
    suspend fun exportJson(): String {
        val out = linkedMapOf<String, Any?>(
            "exported_at" to Instant.ofEpochMilli(clock()).toString(),
            "facts" to facts.all().map {
                linkedMapOf(
                    "subject" to it.subject, "attribute" to it.attribute, "value" to it.value,
                    "category" to it.category.name.lowercase(), "always_in_mind" to it.core,
                    "origin" to it.origin.name.lowercase(), "stated_at" to Instant.ofEpochMilli(it.statedAt).toString(),
                )
            },
            "names" to facts.aliases().associate { it.alias to it.subject },
            "reminders" to agenda.allTasks().map {
                linkedMapOf(
                    "title" to it.title, "due" to it.dueAt?.let { at -> Instant.ofEpochMilli(at).toString() },
                    "repeat" to it.repeatRule, "status" to it.status.name.lowercase(),
                )
            },
            "events" to agenda.allEvents().map {
                linkedMapOf(
                    "title" to it.title, "starts" to Instant.ofEpochMilli(it.startsAt).toString(),
                    "all_day" to it.allDay, "repeat" to it.recurrence, "notes" to it.notes,
                )
            },
            "conversation_summaries" to sessions.summarised().map {
                linkedMapOf("started" to Instant.ofEpochMilli(it.startedAt).toString(), "summary" to it.summary)
            },
        )
        return GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(out)
    }

    /** Deletes messages older than [days] (0 keeps everything). Returns how many went. */
    suspend fun applyRetention(days: Int): Int {
        if (days <= 0) return 0
        val cutoff = clock() - days * DAY_MS
        val maintenance = database.maintenanceDao()
        val deleted = maintenance.deleteMessagesBefore(cutoff)
        maintenance.deleteEmptyChatsBefore(cutoff)
        if (deleted > 0) {
            archive.onChatsDeleted()
            onChanged()
        }
        return deleted
    }

    private companion object {
        const val KEY_DISMISSED = "duplicates.dismissed"
        const val SHORT_VALUE = 12
        const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}
