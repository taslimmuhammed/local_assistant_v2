package com.local.assistant.ui.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.local.assistant.AppContainer
import com.local.assistant.gallery.GalleryIndex
import com.local.assistant.gallery.PhotoLibrary
import com.local.assistant.gallery.PhotoThumbnails
import com.local.assistant.gallery.faces.FaceIndex
import com.local.assistant.data.prefs.SettingsStore
import com.local.assistant.model.InstalledModel
import com.local.assistant.model.ModelManager
import com.local.assistant.model.Transfer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Someone in the People row: a person the user named, or a group of faces still to name. */
sealed interface PeopleTarget {
    data class Named(val person: FaceIndex.Person) : PeopleTarget
    data class Unnamed(val group: FaceIndex.Group) : PeopleTarget
}

/** What the grid shows. */
sealed interface PhotoResults {
    data object Loading : PhotoResults

    /** Nothing typed: the newest photos. */
    data class Newest(val ids: List<Long>) : PhotoResults

    data class Found(val query: String, val ids: List<Long>) : PhotoResults

    /** Something typed, but the photo model isn't there (or wouldn't load) to search with. */
    data class CannotSearch(val query: String) : PhotoResults
}

class GalleryViewModel(
    private val library: PhotoLibrary,
    private val index: GalleryIndex,
    val modelManager: ModelManager,
    /** Shared with the chat's photo results. */
    val thumbnails: PhotoThumbnails,
    /** Small phones give the photo model's memory back as soon as the screen closes. */
    private val pauseWhenHidden: Boolean,
    private val faces: FaceIndex,
    private val settings: SettingsStore,
) : ViewModel() {

    private val _access = MutableStateFlow(library.access())
    val access: StateFlow<PhotoLibrary.Access> = _access.asStateFlow()

    val permissions: Array<String> get() = library.permissions

    val installed: StateFlow<InstalledModel?> = modelManager.installed
    val transfer: StateFlow<Transfer?> = modelManager.transfer
    val downloadError: StateFlow<String?> = modelManager.error
    val status: StateFlow<GalleryIndex.Status> = index.status

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _results = MutableStateFlow<PhotoResults>(PhotoResults.Loading)
    val results: StateFlow<PhotoResults> = _results.asStateFlow()

    // --- People ---------------------------------------------------------------------------

    private val _findPeople = MutableStateFlow(settings.findPeople)
    val findPeople: StateFlow<Boolean> = _findPeople.asStateFlow()

    val faceStatus: StateFlow<FaceIndex.Status> = faces.status

    private val _people = MutableStateFlow<List<FaceIndex.Person>>(emptyList())
    val people: StateFlow<List<FaceIndex.Person>> = _people.asStateFlow()

    private val _groups = MutableStateFlow<List<FaceIndex.Group>>(emptyList())
    val groups: StateFlow<List<FaceIndex.Group>> = _groups.asStateFlow()

    /** Turning it on starts looking; turning it off deletes every face found and every name. */
    fun setFindPeople(on: Boolean) {
        settings.findPeople = on
        _findPeople.value = on
        viewModelScope.launch {
            if (on) {
                faces.refreshStatus()
                startIfReady()
            } else {
                faces.forgetAll()
                loadPeople()
            }
        }
    }

    private suspend fun loadPeople() {
        _people.value = faces.people()
        _groups.value = faces.groups()
    }

    suspend fun photosOf(target: PeopleTarget): List<Long> = when (target) {
        is PeopleTarget.Named -> faces.photosOfPerson(target.person.id)
        is PeopleTarget.Unnamed -> faces.photosOfGroup(target.group.id)
    }

    fun name(group: FaceIndex.Group, name: String, isMe: Boolean, existing: Long?) = viewModelScope.launch {
        faces.nameGroup(group.id, name, isMe, existing)
    }

    fun rename(person: FaceIndex.Person, name: String) = viewModelScope.launch { faces.rename(person.id, name) }

    fun setMe(person: FaceIndex.Person) = viewModelScope.launch { faces.setMe(person.id) }

    fun merge(from: FaceIndex.Person, into: FaceIndex.Person) = viewModelScope.launch { faces.merge(from.id, into.id) }

    fun forget(person: FaceIndex.Person) = viewModelScope.launch { faces.forget(person.id) }

    fun notThem(person: FaceIndex.Person, mediaId: Long) = viewModelScope.launch { faces.notThemIn(mediaId, person.id) }

    init {
        // Who's who, read again whenever grouping or a name changed it, and as faces come in.
        viewModelScope.launch {
            faces.status.map { it.version to it.photosDone / PEOPLE_REFRESH_EVERY }.distinctUntilChanged().collectLatest { loadPeople() }
        }
        viewModelScope.launch { faces.refreshStatus() }
        observeResults()
        // A model that has just arrived starts indexing at once.
        viewModelScope.launch { installed.collect { if (it != null) startIfReady() } }
    }

    @OptIn(FlowPreview::class)
    private fun observeResults() {
        viewModelScope.launch {
            // While indexing, search again every so often so newly indexed photos show up.
            val progress = status.map { it.indexed / REFRESH_EVERY to it.running }.distinctUntilChanged()
            combine(_query.debounce(QUERY_DEBOUNCE_MS).map { it.trim() }.distinctUntilChanged(), progress, _access) { q, _, access -> q to access }
                .collectLatest { (q, access) ->
                    if (access == PhotoLibrary.Access.NONE) return@collectLatest
                    if (q.isEmpty()) {
                        _results.value = PhotoResults.Newest(index.newest(NEWEST_LIMIT).ifEmpty { scanNewest() })
                        return@collectLatest
                    }
                    if (_results.value !is PhotoResults.Found) _results.value = PhotoResults.Loading
                    val hits = index.search(q)
                    _results.value = if (hits == null) PhotoResults.CannotSearch(q) else PhotoResults.Found(q, hits.map { it.mediaId })
                }
        }
    }

    /** Before the first sync the table is empty; show the phone's newest photos straight away. */
    private suspend fun scanNewest(): List<Long> = withContext(Dispatchers.IO) {
        library.scan().take(NEWEST_LIMIT).map { it.mediaId }
    }

    fun setQuery(text: String) {
        _query.value = text
    }

    /** The screen is showing: catch up with permission changes and index what's new. */
    fun onVisible() {
        _access.value = library.access()
        startIfReady()
    }

    fun onHidden() {
        if (pauseWhenHidden) viewModelScope.launch { index.pause() }
    }

    fun onPermissionResult() {
        _access.value = library.access()
        startIfReady()
    }

    private fun startIfReady() {
        if (_access.value != PhotoLibrary.Access.NONE && installed.value != null) index.start()
    }

    fun download() = modelManager.download()

    fun cancelDownload() = modelManager.cancel()

    companion object {
        const val QUERY_DEBOUNCE_MS = 300L
        const val NEWEST_LIMIT = 300
        const val REFRESH_EVERY = 200
        const val PEOPLE_REFRESH_EVERY = 100

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                GalleryViewModel(
                    library = container.photoLibrary,
                    index = container.galleryIndex,
                    modelManager = container.galleryModelManager,
                    thumbnails = container.photoThumbnails,
                    pauseWhenHidden = container.isSmallDevice,
                    faces = container.faceIndex,
                    settings = container.settings,
                )
            }
        }
    }
}
