package com.local.assistant.gallery

import android.util.Log
import com.local.assistant.memory.embed.Int8Vectors
import com.local.assistant.memory.work.ModelScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * The phone's photos as vectors, and searching them.
 *
 * [start] brings the index in line with MediaStore (new photos in, edited ones redone, deleted
 * ones out) and then embeds whatever has no vector yet, newest first. Every batch goes through
 * the [ModelScheduler], behind anything the user or the chat memory is doing, and stops between
 * photos when they start. It runs while the Photos screen is open, while the app stays in front
 * on phones with room for it, and on the charger at night ([indexUntil]).
 */
class GalleryIndex(
    private val dao: PhotoDao,
    private val library: PhotoLibrary,
    private val embedder: GalleryEmbedder,
    private val scheduler: ModelScheduler,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    data class Status(
        /** Photos the app can see. */
        val total: Int = 0,
        /** Of those, how many have been looked at (including the few that could not be read). */
        val indexed: Int = 0,
        val running: Boolean = false,
    ) {
        val remaining: Int get() = (total - indexed).coerceAtLeast(0)
    }

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private var job: Job? = null

    /** One pass at a time, whether from the screen or the nightly job. */
    private val passLock = Mutex()

    /** Syncs and indexes in the background; does nothing if a pass is already running. */
    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            try {
                passLock.withLock { pass(deadline = Long.MAX_VALUE) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Indexing stopped", e)
            } finally {
                _status.update { it.copy(running = false) }
            }
        }
    }

    /** Stops indexing and gives the model's memory back; [start] carries on where it stopped. */
    suspend fun pause() {
        synchronized(this) { job }?.let {
            it.cancel()
            it.join()
        }
        embedder.unload()
    }

    /** For the nightly job: false when photos are left that a later run should get to. */
    suspend fun indexUntil(deadline: Long): Boolean = passLock.withLock {
        try {
            pass(deadline)
        } finally {
            _status.update { it.copy(running = false) }
            embedder.unload()
        }
    }

    /** False when photos are left to do; true when done, or when nothing can be done for now. */
    private suspend fun pass(deadline: Long): Boolean {
        if (!embedder.isInstalled || library.access() == PhotoLibrary.Access.NONE) return true
        _status.update { it.copy(running = true) }
        sync()
        refreshStatus()
        return indexPending(deadline)
    }

    /** Brings the table in line with what MediaStore lists now. */
    private suspend fun sync() = withContext(Dispatchers.IO) {
        val started = clock()
        val photos = library.scan()
        // No access any more reads as an empty library; that is no reason to drop the index.
        if (library.access() == PhotoLibrary.Access.NONE) return@withContext
        val known = HashMap<Long, Long>().apply { dao.stamps().forEach { put(it.mediaId, it.dateModified) } }
        val changed = ArrayList<PhotoEntity>()
        val seen = HashSet<Long>(photos.size * 2)
        for (photo in photos) {
            seen += photo.mediaId
            if (known[photo.mediaId] != photo.dateModified) {
                changed += PhotoEntity(photo.mediaId, photo.dateTaken, photo.dateModified, photo.width, photo.height)
            }
        }
        val gone = known.keys.filter { it !in seen }
        changed.chunked(SQL_BATCH).forEach { dao.upsert(it) }
        gone.chunked(SQL_BATCH).forEach { dao.delete(it) }
        if (changed.isNotEmpty() || gone.isNotEmpty()) {
            vectorsStale = true
            Log.i(TAG, "Synced ${photos.size} photos: ${changed.size} new or edited, ${gone.size} gone, in ${clock() - started} ms")
        }
    }

    private suspend fun indexPending(deadline: Long): Boolean {
        var embedded = 0
        val started = clock()
        try {
            while (clock() < deadline) {
                val batch = dao.pending(GalleryModel.MODEL_ID, BATCH)
                if (batch.isEmpty()) return true
                val finished = runWhenIdle {
                    for (photo in batch) {
                        coroutineContext.ensureActive()
                        val jpeg = withContext(Dispatchers.IO) { library.jpeg(photo.mediaId, GalleryModel.INPUT_PIXELS) }
                        val vector = jpeg?.let { embedder.embedImage(it) }?.let(Int8Vectors::quantize)
                        if (vector == null && !embedder.isLoaded) return@runWhenIdle false
                        dao.setEmbedding(photo.mediaId, vector, if (vector == null) PhotoEntity.FAILED else GalleryModel.MODEL_ID)
                        vectorsStale = true
                        embedded++
                    }
                    true
                }
                refreshStatus()
                // The model would not load: nothing more can be done until it does.
                if (!finished) return true
            }
            return false
        } finally {
            if (embedded > 0) {
                val seconds = (clock() - started) / 1000.0
                Log.i(TAG, "Indexed $embedded photos in ${"%.1f".format(seconds)} s; ${_status.value.remaining} left")
            }
        }
    }

    private suspend fun refreshStatus() {
        val total = dao.count()
        val pending = dao.pendingCount(GalleryModel.MODEL_ID)
        _status.update { it.copy(total = total, indexed = total - pending) }
    }

    /** Runs [block] in a background slot, waiting out the user's typing and turns. */
    private suspend fun runWhenIdle(block: suspend () -> Boolean): Boolean {
        while (true) {
            val result = scheduler.runBackground(ModelScheduler.Priority.GALLERY_INDEX) { block() }
            if (result != null) return result
            delay(ModelScheduler.QUIET_MS)
        }
    }

    // --- Search ---

    private val vectorsLock = Mutex()

    @Volatile
    private var vectorsStale = true
    private var ids = LongArray(0)
    private var vectors = ByteArray(0)
    private var norms = IntArray(0)

    /**
     * Photos matching [query], best first; empty when none do. Null when the model isn't
     * installed or won't load, so the caller can say so rather than show "no photos".
     */
    suspend fun search(query: String): List<PhotoHit>? {
        if (query.isBlank()) return emptyList()
        val started = clock()
        val vector = embedder.embedQuery(query)?.let(Int8Vectors::quantize) ?: return null
        val embedMs = clock() - started
        return vectorsLock.withLock {
            withContext(Dispatchers.Default) {
                if (vectorsStale) loadVectors()
                PhotoRanking.rank(vector, ids, vectors, norms)
            }
        }.also { Log.i(TAG, "\"$query\": ${it.size} of ${ids.size} photos in ${clock() - started} ms (query $embedMs ms)") }
    }

    /**
     * [query]'s photos for a chat reply, best first; null when the model isn't installed or won't
     * load. The model is let go a little after the last search unless indexing still needs it, so
     * a chat doesn't keep it in memory.
     */
    suspend fun searchForChat(query: String): com.local.assistant.memory.tools.PhotoSet? {
        val hits = search(query) ?: return null
        unloadWhenIdle()
        return com.local.assistant.memory.tools.PhotoSet(query, hits.map { it.mediaId })
    }

    /** Every indexed photo and its similarity to [query], best first: for calibrating [PhotoRanking]. */
    internal suspend fun scoreAll(query: String): List<PhotoHit>? {
        val vector = embedder.embedQuery(query)?.let(Int8Vectors::quantize) ?: return null
        return vectorsLock.withLock {
            withContext(Dispatchers.Default) {
                if (vectorsStale) loadVectors()
                val scores = PhotoRanking.scores(vector, vectors, norms, ids.size) ?: return@withContext emptyList()
                ids.indices.map { PhotoHit(ids[it], scores[it]) }.sortedByDescending { it.similarity }
            }
        }
    }

    /** Photos looked at so far and photos still waiting, read from the index itself. */
    suspend fun counts(): Pair<Int, Int> {
        refreshStatus()
        return _status.value.indexed to _status.value.remaining
    }

    private var idleUnload: Job? = null

    @Synchronized
    private fun unloadWhenIdle() {
        idleUnload?.cancel()
        idleUnload = scope.launch {
            delay(IDLE_UNLOAD_MS)
            if (synchronized(this@GalleryIndex) { job?.isActive } != true) embedder.unload()
        }
    }

    /** The newest photos, for the screen before anything is typed. */
    suspend fun newest(limit: Int): List<Long> = dao.newest(limit)

    private suspend fun loadVectors() {
        vectorsStale = false
        val rows = dao.vectors(GalleryModel.MODEL_ID)
        val dims = Int8Vectors.DIMENSIONS
        val newIds = LongArray(rows.size)
        val newVectors = ByteArray(rows.size * dims)
        val newNorms = IntArray(rows.size)
        var size = 0
        for (row in rows) {
            if (row.embedding.size != dims) continue
            newIds[size] = row.mediaId
            row.embedding.copyInto(newVectors, size * dims)
            newNorms[size] = Int8Vectors.squaredNorm(row.embedding)
            size++
        }
        ids = newIds.copyOf(size)
        vectors = newVectors
        norms = newNorms
    }

    /** Forgets every vector and photo; the next [start] indexes from scratch. */
    suspend fun clear() {
        pause()
        dao.clear()
        vectorsStale = true
        _status.value = Status()
    }

    private companion object {
        const val TAG = "GalleryIndex"

        /** Photos per scheduler slot: a few seconds of work, so the user never waits long. */
        const val BATCH = 8

        const val SQL_BATCH = 500

        /** How long the model stays loaded after a search from a chat, for a follow-up one. */
        const val IDLE_UNLOAD_MS = 2 * 60_000L
    }
}
