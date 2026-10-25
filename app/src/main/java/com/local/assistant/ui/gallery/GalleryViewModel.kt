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

    init {
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

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                GalleryViewModel(
                    library = container.photoLibrary,
                    index = container.galleryIndex,
                    modelManager = container.galleryModelManager,
                    thumbnails = container.photoThumbnails,
                    pauseWhenHidden = container.isSmallDevice,
                )
            }
        }
    }
}
