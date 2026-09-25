package com.local.assistant.ui.memory

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.local.assistant.AppContainer
import com.local.assistant.data.prefs.SettingsStore
import com.local.assistant.memory.MemoryControls
import com.local.assistant.memory.core.CoreMemoryRenderer
import com.local.assistant.memory.core.DuplicateSuggestion
import com.local.assistant.memory.core.FactKeys
import com.local.assistant.memory.core.FactLabels
import com.local.assistant.memory.db.EventEntity
import com.local.assistant.memory.db.FactCategory
import com.local.assistant.memory.db.FactEntity
import com.local.assistant.memory.db.FactOrigin
import com.local.assistant.memory.db.TaskEntity
import com.local.assistant.memory.prompt.MemoryBudget
import com.local.assistant.memory.prompt.TokenEstimator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What the facts tab shows. */
data class FactsView(
    val core: List<FactEntity>,
    /** What "Always in mind" costs in every prompt, against its cap. */
    val coreTokens: Int,
    val coreCap: Int,
    val groups: List<Pair<FactCategory, List<FactEntity>>>,
    val total: Int,
) {
    val isEmpty: Boolean get() = core.isEmpty() && groups.isEmpty()

    companion object {
        val EMPTY = FactsView(emptyList(), 0, MemoryBudget.EIGHT_K.coreCap, emptyList(), 0)

        fun of(all: List<FactEntity>, query: String, renderer: CoreMemoryRenderer, coreCap: Int): FactsView {
            val q = query.trim().lowercase(Locale.ROOT)
            val shown = if (q.isEmpty()) all else all.filter { fact ->
                listOf(fact.subject.replace('_', ' '), FactLabels.attribute(fact.attribute), fact.value)
                    .any { q in it.lowercase(Locale.ROOT) }
            }
            val core = shown.filter { it.core }.sortedBy { it.id }
            val coreTokens = renderer.render(all.filter { it.core && it.subject == FactKeys.USER }, Int.MAX_VALUE).tokens
            val groups = shown.filter { !it.core }
                .groupBy { it.category }
                .toSortedMap(compareBy { SECTION_ORDER.indexOf(it) })
                .map { (category, facts) -> category to facts.sortedWith(compareBy({ it.subject != FactKeys.USER }, { it.subject }, { it.attribute })) }
            return FactsView(core, coreTokens, coreCap, groups, all.size)
        }

        /** "About you" first: the order the brief lists them in. */
        private val SECTION_ORDER = listOf(
            FactCategory.PROFILE, FactCategory.PREFERENCE, FactCategory.PEOPLE, FactCategory.PLACES,
            FactCategory.WORK, FactCategory.HEALTH, FactCategory.ROUTINE, FactCategory.OTHER,
        )
    }
}

/** A short-lived message under the screen, with an undo when there is one. */
data class Notice(val message: String, val undo: (suspend () -> Unit)? = null)

class MemoryViewModel(
    private val controls: MemoryControls,
    private val settings: SettingsStore,
    private val forgetAll: suspend () -> Unit,
    estimator: TokenEstimator,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) : ViewModel() {

    private val renderer = CoreMemoryRenderer(estimator)
    private val coreCap = MemoryBudget.EIGHT_K.coreCap

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val facts = controls.observeFacts().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val factsView: StateFlow<FactsView> = combine(facts, _query) { all, q -> FactsView.of(all, q, renderer, coreCap) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, FactsView.EMPTY)

    val tasks: StateFlow<List<TaskEntity>> = controls.observeTasks().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val events: StateFlow<List<EventEntity>> = controls.observeEvents().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _duplicates = MutableStateFlow<List<DuplicateSuggestion>>(emptyList())
    val duplicates: StateFlow<List<DuplicateSuggestion>> = _duplicates.asStateFlow()

    val memoryPaused: StateFlow<Boolean> = settings.observeMemoryPaused()
        .stateIn(viewModelScope, SharingStarted.Eagerly, settings.memoryPaused)

    val retentionDays: StateFlow<Int> = settings.observeHistoryRetentionDays()
        .stateIn(viewModelScope, SharingStarted.Eagerly, settings.historyRetentionDays)

    private val _notices = MutableSharedFlow<Notice>(extraBufferCapacity = 4)
    val notices: SharedFlow<Notice> = _notices.asSharedFlow()

    init {
        facts.onEach { refreshDuplicates() }.launchIn(viewModelScope)
    }

    fun search(text: String) {
        _query.value = text
    }

    fun edit(fact: FactEntity, value: String) = launch { controls.edit(fact, value) }

    fun togglePin(fact: FactEntity) = launch { controls.setCore(fact, !fact.core) }

    fun delete(fact: FactEntity) = launch {
        val deletion = controls.delete(fact)
        val also = deletion.snippets.size
        val message = "Forgot " + label(fact) + when (also) {
            0 -> ""
            1 -> " · also removed 1 related snippet"
            else -> " · also removed $also related snippets"
        }
        _notices.emit(Notice(message) { controls.undo(deletion) })
    }

    suspend fun source(fact: FactEntity): String? {
        val (message, chat) = controls.source(fact) ?: return null
        val date = DATE.format(Instant.ofEpochMilli(message.createdAt).atZone(zone()))
        return "“${message.text.trim()}”\n\n${chat?.title ?: "A deleted chat"} · $date"
    }

    fun merge(suggestion: DuplicateSuggestion, into: String) = launch {
        val from = if (into == suggestion.first) suggestion.second else suggestion.first
        controls.merge(from, into)
        _notices.emit(Notice("“${from.replace('_', ' ')}” is now another name for “${into.replace('_', ' ')}”"))
    }

    fun dismiss(suggestion: DuplicateSuggestion) = launch {
        controls.dismiss(suggestion)
        refreshDuplicates()
    }

    fun completeTask(task: TaskEntity) = launch { controls.completeTask(task) }

    fun deleteTask(task: TaskEntity) = launch {
        controls.deleteTask(task)
        _notices.emit(Notice("Deleted “${task.title}”") { controls.restoreTask(task) })
    }

    fun deleteEvent(event: EventEntity) = launch {
        controls.deleteEvent(event)
        _notices.emit(Notice("Deleted “${event.title}”") { controls.restoreEvent(event) })
    }

    fun setPaused(paused: Boolean) {
        settings.memoryPaused = paused
    }

    /** Shortening the period deletes what is now past it, straight away. */
    fun setRetention(days: Int) = launch {
        settings.historyRetentionDays = days
        val deleted = controls.applyRetention(days)
        if (deleted > 0) _notices.emit(Notice("Deleted $deleted older messages"))
    }

    fun forgetEverything() = launch {
        forgetAll()
        _notices.emit(Notice("Forgot everything. Your chats are still there."))
    }

    fun export(uri: Uri, resolver: ContentResolver) = launch {
        val json = controls.exportJson()
        val ok = withContext(Dispatchers.IO) {
            runCatching { resolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) } != null }.getOrDefault(false)
        }
        _notices.emit(Notice(if (ok) "Exported" else "Could not write the file"))
    }

    fun undo(notice: Notice) = launch { notice.undo?.invoke() }

    private suspend fun refreshDuplicates() {
        _duplicates.value = controls.duplicates()
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    companion object {
        private val DATE = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.ENGLISH)

        /** "Dentist" for the user's own facts, "Mother · birthday" for someone else's. */
        fun label(fact: FactEntity): String {
            val attribute = FactLabels.attribute(fact.attribute).replaceFirstChar { it.titlecase(Locale.ROOT) }
            return if (fact.subject == FactKeys.USER) {
                attribute
            } else {
                fact.subject.replace('_', ' ').replaceFirstChar { it.titlecase(Locale.ROOT) } + " · " + attribute.lowercase(Locale.ROOT)
            }
        }

        /** "From chat, 21 Sep" / "Learned from chat, 21 Sep" / "Edited by you, 22 Sep". */
        fun provenance(fact: FactEntity, zone: ZoneId = ZoneId.systemDefault()): String {
            val how = when (fact.origin) {
                FactOrigin.CHAT -> "From chat"
                FactOrigin.EXTRACTED -> "Learned from chat"
                FactOrigin.USER_EDIT -> "Edited by you"
            }
            return "$how, " + SHORT_DATE.format(Instant.ofEpochMilli(fact.statedAt).atZone(zone))
        }

        private val SHORT_DATE = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                MemoryViewModel(
                    controls = container.memoryControls,
                    settings = container.settings,
                    forgetAll = container::forgetEverything,
                    estimator = container.tokenEstimator,
                )
            }
        }
    }
}
