package com.local.assistant.ui.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.local.assistant.AppContainer
import com.local.assistant.memory.db.NoteKind
import com.local.assistant.memory.db.UserNoteEntity
import com.local.assistant.memory.notes.UserNotes
import com.local.assistant.ui.memory.Notice
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The Notes screen, and the lists shown under replies in chat. A note being edited is saved as it
 * is typed, a moment after the typing stops, and once more on leaving it, as in a notes app: there
 * is no Save button. A new note is kept only once something is written in it.
 */
class NotesViewModel(
    private val notes: UserNotes,
    /** A note was saved here: its parts need vectors again, and a name may be new to the prefix. */
    private val onChanged: () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    val all: StateFlow<List<UserNoteEntity>> = notes.observe().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** The note open in the editor, as it was opened; null on the list. */
    private val _open = MutableStateFlow<UserNoteEntity?>(null)
    val open: StateFlow<UserNoteEntity?> = _open.asStateFlow()

    private val _notices = MutableSharedFlow<Notice>(extraBufferCapacity = 4)
    val notices: SharedFlow<Notice> = _notices.asSharedFlow()

    /** One editing of one note: its id once saved, what was saved last, and the latest draft. */
    private class Session(var id: Long, var saved: UserNoteEntity?, var latest: UserNoteEntity)

    private var session: Session? = null
    private var pending: Job? = null
    private val saving = Mutex()

    fun open(note: UserNoteEntity) {
        finish()
        session = Session(note.id, note, note)
        _open.value = note
    }

    /** A blank note or list, kept once something is written in it. */
    fun create(kind: NoteKind) {
        finish()
        val now = clock()
        val blank = UserNoteEntity(title = "", kind = kind, createdAt = now, updatedAt = now)
        session = Session(0, null, blank)
        _open.value = blank
    }

    /** The editor's draft as it is now; saved once the typing pauses. */
    fun change(draft: UserNoteEntity) {
        val current = session ?: return
        current.latest = draft
        pending?.cancel()
        pending = viewModelScope.launch {
            delay(AUTOSAVE_MS)
            // Once begun, a save finishes: cut short, a new note's row could be written without its id kept.
            withContext(NonCancellable) { persist(current) }
        }
    }

    /** Leaves the editor, saving what's there. */
    fun close() {
        finish()
        _open.value = null
    }

    private fun finish() {
        val current = session ?: return
        session = null
        pending?.cancel()
        pending = null
        viewModelScope.launch { persist(current) }
    }

    private suspend fun persist(current: Session) = saving.withLock {
        // A row left empty in the editor isn't an item.
        val draft = current.latest.let { it.copy(title = titled(it), items = it.items.filter { item -> item.text.isNotBlank() }) }
        val saved = current.saved
        if (saved != null && sameContent(saved, draft)) return@withLock
        val now = clock()
        if (current.id == 0L) {
            if (isBlank(current.latest)) return@withLock
            val written = draft.copy(createdAt = now, updatedAt = now)
            current.id = notes.insert(written)
            current.saved = written.copy(id = current.id)
        } else {
            val written = draft.copy(id = current.id, updatedAt = now)
            notes.update(written)
            current.saved = written
        }
        onChanged()
    }

    /** Ticks or unticks a list's item, from its card or under a reply. */
    fun toggle(noteId: Long, index: Int) = viewModelScope.launch {
        saving.withLock {
            val note = notes.note(noteId) ?: return@withLock
            val item = note.items.getOrNull(index) ?: return@withLock
            notes.update(note.copy(items = note.items.toMutableList().apply { set(index, item.copy(done = !item.done)) }, updatedAt = clock()))
        }
        onChanged()
    }

    fun setPinned(note: UserNoteEntity, pinned: Boolean) = viewModelScope.launch {
        saving.withLock {
            val current = notes.note(note.id) ?: return@withLock
            notes.update(current.copy(pinned = pinned))
        }
        onChanged()
    }

    /** Deletes [note] (asked first, on screen), with an undo. */
    fun delete(note: UserNoteEntity) {
        // The editor's copy of a note made in it keeps id 0; once saved, its id is the session's.
        val editing = session?.takeIf { it.id == note.id || note.id == 0L }
        if (editing != null) {
            session = null
            pending?.cancel()
            _open.value = null
        }
        viewModelScope.launch {
            val current = saving.withLock {
                // Read under the lock, so a first save still under way has given it its id.
                val id = editing?.id ?: note.id
                if (id == 0L) return@withLock null
                notes.note(id)?.also { notes.delete(it.id) }
            } ?: return@launch
            onChanged()
            _notices.emit(
                Notice("Deleted “${current.title}”", undo = {
                    notes.restore(current)
                    onChanged()
                }),
            )
        }
    }

    fun undo(notice: Notice) = viewModelScope.launch { notice.undo?.invoke() }

    fun settle(notice: Notice) = viewModelScope.launch { notice.settle?.invoke() }

    companion object {
        /** How long after the last keystroke a note is saved. */
        private const val AUTOSAVE_MS = 700L

        /** Nothing written in it: no title, no text, no items. */
        fun isBlank(note: UserNoteEntity): Boolean =
            note.title.isBlank() && note.body.isBlank() && note.items.none { it.text.isNotBlank() }

        /** Its title, or for one left untitled its first words, as it is kept and named to the model. */
        fun titled(note: UserNoteEntity): String {
            note.title.trim().takeIf { it.isNotEmpty() }?.let { return it }
            val first = (note.body.lineSequence().firstOrNull { it.isNotBlank() } ?: note.items.firstOrNull { it.text.isNotBlank() }?.text)
                ?.trim()?.split(Regex("\\s+"))?.take(UNTITLED_WORDS)?.joinToString(" ")
            return first?.takeIf { it.isNotEmpty() } ?: if (note.kind == NoteKind.LIST) "Untitled list" else "Untitled note"
        }

        private const val UNTITLED_WORDS = 5

        private fun sameContent(a: UserNoteEntity, b: UserNoteEntity) =
            a.title == b.title && a.kind == b.kind && a.body == b.body && a.items == b.items && a.pinned == b.pinned

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { NotesViewModel(container.userNotes, onChanged = container::notesChanged) }
        }
    }
}
