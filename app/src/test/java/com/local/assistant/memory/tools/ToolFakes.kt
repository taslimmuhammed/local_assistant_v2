package com.local.assistant.memory.tools

import com.local.assistant.llm.ChatSession
import com.local.assistant.llm.GenEvent
import com.local.assistant.llm.PromptAttachment
import com.local.assistant.llm.ToolCall
import com.local.assistant.llm.ToolResult
import com.local.assistant.memory.core.FactDecision
import com.local.assistant.memory.core.FactKeys
import com.local.assistant.memory.core.FactUpsertPolicy
import com.local.assistant.memory.core.FactWrite
import com.local.assistant.memory.db.EventEntity
import com.local.assistant.memory.db.FactEntity
import com.local.assistant.memory.db.FactOrigin
import com.local.assistant.memory.db.FactWriteResult
import com.local.assistant.memory.db.ForgetResult
import com.local.assistant.memory.db.MemoryStore
import com.local.assistant.memory.db.TaskEntity
import com.local.assistant.memory.db.TaskPlace
import com.local.assistant.memory.db.TaskStatus
import com.local.assistant.memory.db.PlaceSpot
import com.local.assistant.gallery.places.Places
import com.local.assistant.location.DeviceLocation
import com.local.assistant.location.FoundPlace
import com.local.assistant.location.GeoPoint
import com.local.assistant.location.OwnPlaces
import com.local.assistant.location.PlaceSearch
import com.local.assistant.location.PlaceSearchError
import com.local.assistant.memory.notes.FoundImage
import com.local.assistant.memory.notes.ImageNotes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow

/** The memory store in plain maps, applying the same upsert policy as the real one. */
class FakeMemoryStore(private val clock: () -> Long) : MemoryStore {
    val facts = linkedMapOf<Long, FactEntity>()
    val tombstones = mutableMapOf<Pair<String, String>, Long>()
    val tasks = linkedMapOf<Long, TaskEntity>()
    val events = linkedMapOf<Long, EventEntity>()
    private var nextId = 1L

    override suspend fun <T> transaction(block: suspend () -> T): T = block()

    override suspend fun saveFact(
        subject: String,
        attribute: String,
        value: String,
        origin: FactOrigin,
        pin: Boolean,
        statedAt: Long?,
        sourceMessageId: Long?,
    ): FactWriteResult {
        val write = FactWrite(FactKeys.subject(subject), FactKeys.attribute(attribute), FactKeys.value(value), pin, origin, statedAt ?: clock(), sourceMessageId)
        val existing = facts.values.firstOrNull { it.subject == write.subject && it.attribute == write.attribute }
        val forgottenAt = listOfNotNull(tombstones[write.subject to ""], tombstones[write.subject to write.attribute]).maxOrNull()
        val decision = when (val d = FactUpsertPolicy.decide(existing, write, forgottenAt, clock())) {
            is FactDecision.Insert -> FactDecision.Insert(d.fact.copy(id = nextId++)).also { facts[it.fact.id] = it.fact }
            is FactDecision.Update -> d.also { facts[it.fact.id] = it.fact }
            is FactDecision.Confirm -> d.also { facts[it.fact.id] = it.fact }
            is FactDecision.Skip -> d
        }
        return FactWriteResult(decision, existing)
    }

    override suspend fun restoreFact(previous: FactEntity?, written: FactEntity): Boolean {
        if (facts[written.id] != written) return false
        if (previous == null) facts.remove(written.id) else facts[previous.id] = previous
        return true
    }

    override suspend fun forgetFacts(subject: String, attribute: String?): ForgetResult {
        val s = FactKeys.subject(subject)
        val a = attribute?.takeIf { it.isNotBlank() }?.let(FactKeys::attribute).orEmpty()
        val removed = facts.values.filter { it.subject == s && (a.isEmpty() || it.attribute == a) }
        removed.forEach { facts.remove(it.id) }
        val previous = tombstones[s to a]
        tombstones[s to a] = clock()
        return ForgetResult(s, a, removed, clock(), previous)
    }

    override suspend fun unforget(result: ForgetResult) {
        result.removed.forEach { facts[it.id] = it }
        val previous = result.previousTombstone
        if (previous == null) tombstones.remove(result.subject to result.attribute) else tombstones[result.subject to result.attribute] = previous
    }

    override suspend fun searchFacts(words: List<String>, limit: Int): List<FactEntity> =
        facts.values.filter { fact -> words.any { w -> listOf(fact.subject, fact.attribute, fact.value.lowercase()).any { w in it } } }.take(limit)

    override suspend fun coreFacts(): List<FactEntity> = facts.values.filter { it.core && it.subject == FactKeys.USER }

    override suspend fun insertTask(task: TaskEntity): Long = nextId++.also { tasks[it] = task.copy(id = it) }
    override suspend fun task(id: Long): TaskEntity? = tasks[id]
    override suspend fun updateTask(task: TaskEntity) { tasks[task.id] = task }
    override suspend fun deleteTask(id: Long) { tasks.remove(id) }
    override suspend fun openTasks(): List<TaskEntity> = tasks.values.filter { it.status == TaskStatus.OPEN }
    override suspend fun tasksDueBetween(from: Long, to: Long): List<TaskEntity> =
        openTasks().filter { (it.dueAt ?: -1) in from until to }.sortedBy { it.dueAt }
    override suspend fun tasksMentioning(word: String, limit: Int): List<TaskEntity> =
        tasks.values.filter { word in it.title.lowercase() }.take(limit)

    override suspend fun insertEvent(event: EventEntity): Long = nextId++.also { events[it] = event.copy(id = it) }
    override suspend fun event(id: Long): EventEntity? = events[id]
    override suspend fun updateEvent(event: EventEntity) { events[event.id] = event }
    override suspend fun deleteEvent(id: Long) { events.remove(id) }
    override suspend fun eventsBetween(from: Long, to: Long): List<EventEntity> =
        events.values.filter { it.startsAt in from until to || it.recurrence != null }
    override suspend fun eventsMentioning(word: String, limit: Int): List<EventEntity> =
        events.values.filter { word in it.title.lowercase() }.take(limit)
}

class FakeReminders(var exact: Boolean = true) : ReminderScheduler {
    val scheduled = linkedMapOf<Long, Long>()
    /** Place reminders being watched: as the real one, each call says the whole of how one goes off. */
    val places = linkedMapOf<Long, TaskPlace>()
    val eventAlerts = linkedMapOf<Long, Long>()
    override fun canScheduleExact() = exact
    override fun schedule(taskId: Long, dueAt: Long) { places.remove(taskId); scheduled[taskId] = dueAt }
    override fun schedulePlace(taskId: Long, place: TaskPlace) { scheduled.remove(taskId); places[taskId] = place }
    override fun cancel(taskId: Long) { scheduled.remove(taskId); places.remove(taskId) }
    override fun scheduleEvent(eventId: Long, alertAt: Long, startsAt: Long) { eventAlerts[eventId] = alertAt }
    override fun cancelEvent(eventId: Long) { eventAlerts.remove(eventId) }
}

class FakeSystemAlarms(var present: Boolean = true, var reachable: Boolean = true) : SystemAlarms {
    data class Set(val hour: Int, val minute: Int, val days: kotlin.collections.Set<java.time.DayOfWeek>, val label: String?)
    val set = mutableListOf<Set>()
    override fun available() = present
    override fun set(hour: Int, minute: Int, days: kotlin.collections.Set<java.time.DayOfWeek>, label: String?): Boolean {
        if (!reachable) return false
        set += Set(hour, minute, days, label)
        return true
    }
    val timers = mutableListOf<Pair<Int, String?>>()
    override fun setTimer(seconds: Int, label: String?): Boolean {
        if (!reachable) return false
        timers += seconds to label
        return true
    }
    var clockOpened = 0
    override fun openClock() { clockOpened++ }
}

class FakeToolLog : ToolLog {
    val records = linkedMapOf<Long, ToolRecord>()
    private var nextId = 1_000L
    override suspend fun record(chatId: Long, record: ToolRecord): Long = nextId++.also { records[it] = record }
    override suspend fun read(id: Long): ToolRecord? = records[id]
    override suspend fun update(id: Long, record: ToolRecord) { records[id] = record }
}

/**
 * Plays the model: each send gets the next scripted list of events. Records what it was sent,
 * so a test can see the envelope and the tool results the model would have read.
 */
class ScriptedSession(private val script: ArrayDeque<List<GenEvent>>) : ChatSession {
    val sentEnvelopes = mutableListOf<String>()
    val sentResults = mutableListOf<List<ToolResult>>()

    override val isAlive = true

    override fun send(envelope: String, attachment: PromptAttachment?): Flow<GenEvent> {
        sentEnvelopes += envelope
        return next()
    }

    override fun sendToolResults(results: List<ToolResult>): Flow<GenEvent> {
        sentResults += results
        return next()
    }

    private fun next(): Flow<GenEvent> = (script.removeFirstOrNull() ?: listOf(GenEvent.Done(null))).asFlow()

    override fun tokenCount(): Int? = null
    override fun cancel() = Unit
    override fun close() = Unit

    companion object {
        fun calls(vararg calls: ToolCall) = listOf(GenEvent.ToolCalls(calls.toList()), GenEvent.Done(null))
        fun says(text: String) = listOf(GenEvent.TextDelta(text), GenEvent.Done(null))
    }
}

/** Images by message: what `remember_image` can find, and what it saved. */
class FakeImageNotes : ImageNotes {
    /** chatId → (messageId → path) of images the user sent. */
    val sent = mutableMapOf<Long, MutableMap<Long, String>>()
    val saved = linkedMapOf<Long, Triple<String, String, FoundImage>>()
    private var nextId = 1L

    override suspend fun findImage(chatId: Long, upToMessageId: Long): FoundImage? =
        sent[chatId].orEmpty().filterKeys { it <= upToMessageId }.maxByOrNull { it.key }
            ?.let { (messageId, path) -> FoundImage(chatId, messageId, path) }

    override suspend fun save(title: String, details: String, image: FoundImage): Long =
        nextId++.also { saved[it] = Triple(title, details, image) }

    override suspend fun delete(noteId: Long) {
        saved.remove(noteId)
    }
}

/** The phone as a log of what would have been opened or switched. */
class FakePhone(var foreground: Boolean = true) : PhoneActions {
    val done = mutableListOf<String>()
    var apps = listOf(InstalledApp("YouTube", "com.google.android.youtube"), InstalledApp("Maps", "com.google.android.apps.maps"))
    var whatsapp = true
    var dndAccess = false
    var silentNeedsAccess = false
    var volumeLevel = 40

    /** Another app came to the front: what a call or message does on the phone. */
    var onHandoff: () -> Unit = {}

    private fun act(what: String): Handoff = if (!foreground) Handoff.IN_BACKGROUND else Handoff.DONE.also { done += what; onHandoff() }

    override fun dial(number: String) = act("dial $number")
    override fun compose(app: MessageApp, to: String, text: String?, image: java.io.File?) =
        if (app == MessageApp.WHATSAPP && !whatsapp) Handoff.NO_APP else act("$app $to: $text" + (image?.let { " + ${it.name}" } ?: ""))
    override fun saveScreenshot(image: java.io.File) = Handoff.DONE.also { done += "saved ${image.name}" }
    override fun installedApps() = apps
    override fun open(target: AppTarget) = act("open $target")
    override fun torch(on: Boolean) = Handoff.DONE.also { done += "torch $on" }
    override fun ringer(mode: RingerMode) =
        if (mode == RingerMode.SILENT && silentNeedsAccess) Handoff.NEEDS_ACCESS else Handoff.DONE.also { done += "ringer $mode" }
    override fun volume(change: VolumeChange): Int {
        volumeLevel = when (change) {
            VolumeChange.Up -> (volumeLevel + 20).coerceAtMost(100)
            VolumeChange.Down -> (volumeLevel - 20).coerceAtLeast(0)
            VolumeChange.Mute -> 0
            VolumeChange.Unmute -> 40
            is VolumeChange.To -> change.percent
        }
        return volumeLevel
    }
    override fun doNotDisturb(on: Boolean) = if (dndAccess) Handoff.DONE.also { done += "dnd $on" } else Handoff.NEEDS_ACCESS
    override fun openPanel(panel: SettingsPanel) = act("panel $panel")
}

/** A few towns, as the APK's place list has them (see [Places.read]). */
val TEST_TOWNS: Places = Places.read(
    listOf(
        "P\tKakkanad\t\t10.0159\t76.3419\tIN\t13\t30000\t",
        "P\tKochi\tCochin,Ernakulam\t9.9312\t76.2673\tIN\t13\t600000\t",
        "P\tMunnar\t\t10.0889\t77.0595\tIN\t13\t40000\t",
        "P\tBengaluru\tBangalore\t12.9716\t77.5946\tIN\t19\t8000000\t",
        "R\tIN.13\tKerala",
        "R\tIN.19\tKarnataka",
        "C\tIN\tIndia",
    ).joinToString("\n").byteInputStream(),
)

/** Near Hoodi, Bengaluru: where the user is in the place tests. */
val HOODI = GeoPoint(12.9912, 77.7034)

/**
 * The map, as places by the query that finds them (best match first); what's outside the box
 * asked about isn't found. Records each search.
 */
class FakePlaceSearch : PlaceSearch {
    val places = mutableMapOf<String, List<FoundPlace>>()
    val asked = mutableListOf<Triple<String, GeoPoint, Double>>()
    var failure: String? = null

    override suspend fun search(query: String, around: GeoPoint, spanDeg: Double): List<FoundPlace> {
        failure?.let { throw PlaceSearchError(it) }
        asked += Triple(query, around, spanDeg)
        return places[query.lowercase()].orEmpty()
            .filter { kotlin.math.abs(it.point.lat - around.lat) <= spanDeg && kotlin.math.abs(it.point.lon - around.lon) <= spanDeg }
    }

    companion object {
        fun place(
            name: String,
            lat: Double,
            lon: Double,
            label: String? = null,
            radiusM: Int = PlaceSearch.MIN_RADIUS_M,
            kind: FoundPlace.Kind = FoundPlace.Kind.SPOT,
        ) = FoundPlace(name, GeoPoint(lat, lon), radiusM, label, kind)

        /** DMarts around Bengaluru as OpenStreetMap had them (5 Nov 2026), in its order, and one DMart Ready. */
        val DMARTS = listOf(
            place("DMart", 12.8755954, 77.5445916, "Vajrahalli"),
            place("DMart", 13.1003549, 77.5862263, "1st A Main"),
            place("DMart", 12.9555717, 77.7286963, "Varthur Road"),
            place("DMart", 12.9889936, 77.7100422, "Hoodi Main Road"),
            place("DMart Ready", 12.9202013, 77.6105156, "9th Cross Road"),
            place("DMart", 13.0123108, 77.6672269, "Ramamurthinagar Main Road"),
            place("Dmart", 13.0290360, 77.6071212, "KHB Main Road"),
            place("DMart", 12.9321517, 77.6346228, "Temple Trees Rd"),
            place("DMart", 13.0334461, 77.6374519, "Hennuru - Bagaluru Road"),
        )
    }
}

/** The phone's location and what the user allows of it; [answers] are what they tap when asked. */
class FakeLocation(
    var at: GeoPoint? = HOODI,
    var locate: Boolean = true,
    var watch: Boolean = true,
    var allowLocate: Boolean = true,
    var allowWatch: Boolean = true,
) : DeviceLocation {
    val asked = mutableListOf<String>()
    var fixes = 0

    override fun canLocate() = locate
    override suspend fun askToLocate(): Boolean {
        asked += "locate"
        locate = allowLocate
        return locate
    }
    override fun canWatch() = locate && watch
    override suspend fun askToWatch(): Boolean {
        asked += "watch"
        watch = allowWatch
        return canWatch()
    }
    override suspend fun current(): GeoPoint? {
        fixes++
        return at.takeIf { locate }
    }
}

/** Home and office as the user said them, and where each was found. */
class FakeOwnPlaces : OwnPlaces {
    val addresses = mutableMapOf<String, String>()
    val kept = mutableMapOf<String, Pair<String, PlaceSpot>>()
    override suspend fun address(key: String) = addresses[key]
    override suspend fun spot(key: String, address: String) = kept[key]?.takeIf { it.first == address }?.second
    override suspend fun keep(key: String, address: String, spot: PlaceSpot) {
        kept[key] = address to spot
    }
}
