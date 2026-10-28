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
 * The phone's photos as vectors, and searching them. Videos too: a vector for each of a few
 * moments of each, so a search finds the moment as well as the video.
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
    /** The people in the photos, looked for once the photos themselves are indexed. */
    private val faces: com.local.assistant.gallery.faces.FaceIndex? = null,
    /** Towns, states and countries, for where photos were taken; none when not wired in. */
    private val places: com.local.assistant.gallery.places.PlaceList? = null,
    /** Which photos look like documents, each time that's worked out again. */
    private val onDocuments: (Set<Long>) -> Unit = {},
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

    /** Asked to start during a pass: what it synced may be out of date (a permission just given). */
    @Volatile
    private var again = false

    /** Syncs and indexes in the background; during a pass, runs another once it ends. */
    @Synchronized
    fun start() {
        if (job?.isActive == true) {
            again = true
            return
        }
        job = scope.launch {
            try {
                do {
                    again = false
                    passLock.withLock { pass(deadline = Long.MAX_VALUE) }
                } while (again)
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
        faces?.close()
    }

    /** For the nightly job: false when photos are left that a later run should get to. */
    suspend fun indexUntil(deadline: Long): Boolean = passLock.withLock {
        try {
            pass(deadline)
        } finally {
            _status.update { it.copy(running = false) }
            embedder.unload()
            faces?.close()
        }
    }

    /** False when photos are left to do; true when done, or when nothing can be done for now. */
    private suspend fun pass(deadline: Long): Boolean {
        if (!embedder.isInstalled || library.access() == PhotoLibrary.Access.NONE) return true
        _status.update { it.copy(running = true) }
        sync()
        refreshStatus()
        if (!indexPending(deadline)) return false
        // Photo search first, then which are documents (the model is still loaded), then where
        // they were taken (quick), then people.
        scoreDocuments()
        embedder.unload()
        if (!locatePending(deadline)) return false
        return faces?.indexPending(deadline, ::runWhenIdle) ?: true
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
                changed += PhotoEntity(photo.mediaId, photo.dateTaken, photo.dateModified, photo.width, photo.height, durationMs = photo.durationMs)
            }
        }
        val gone = known.keys.filter { it !in seen }
        changed.chunked(SQL_BATCH).forEach { dao.upsert(it) }
        gone.chunked(SQL_BATCH).forEach { dao.delete(it) }
        if (changed.isNotEmpty() || gone.isNotEmpty()) {
            dao.deleteOrphanFrames()
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
                        if (photo.durationMs != null) {
                            // A video is a slot of its own: up to twenty moments, several seconds.
                            if (photo !== batch.first()) break
                            if (!indexVideo(photo.mediaId, photo.durationMs)) return@runWhenIdle false
                            embedded++
                            break
                        }
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

    /**
     * Scores photos not yet scored for how much they look like a document ([DocumentPhotos]),
     * from their stored vectors: only the descriptions go through the model.
     */
    private suspend fun scoreDocuments() {
        val pending = dao.documentsPending(GalleryModel.MODEL_ID, DocumentPhotos.SCORED_BY)
        if (pending.isEmpty()) return
        val started = clock()
        val descriptions = DocumentPhotos.DESCRIPTIONS.map { embedder.embedQuery(it)?.let(Int8Vectors::quantize) ?: return }
        for (photo in pending) {
            coroutineContext.ensureActive()
            dao.setDocScore(photo.mediaId, DocumentPhotos.score(photo.embedding, descriptions), DocumentPhotos.SCORED_BY)
        }
        refreshDocuments()
        Log.i(TAG, "Scored ${pending.size} photos as documents in ${clock() - started} ms; ${documentCount} look like one")
    }

    @Volatile
    private var documentCount = 0

    /** Tells [onDocuments] which photos look like documents, as the index has them now. */
    suspend fun refreshDocuments() {
        val ids = dao.documents(DocumentPhotos.THRESHOLD).toHashSet()
        documentCount = ids.size
        onDocuments(ids)
    }

    /** Embeds moments of a video; false when the model won't load. A video that can't be read is marked failed. */
    private suspend fun indexVideo(mediaId: Long, durationMs: Long): Boolean {
        val frames = withContext(Dispatchers.IO) {
            library.frames(mediaId, durationMs, GalleryModel.INPUT_PIXELS, FRAME_EVERY_MS, MAX_FRAMES)
        }
        val vectors = ArrayList<FrameEntity>(frames.size)
        for ((atMs, jpeg) in frames) {
            coroutineContext.ensureActive()
            val vector = embedder.embedImage(jpeg)?.let(Int8Vectors::quantize)
            if (vector == null) {
                if (!embedder.isLoaded) return false
                continue
            }
            vectors += FrameEntity(mediaId, atMs, vector)
        }
        dao.setFrames(mediaId, vectors, if (vectors.isEmpty()) PhotoEntity.FAILED else GalleryModel.MODEL_ID)
        vectorsStale = true
        return true
    }

    /**
     * Reads where each photo not yet looked at was taken, from its GPS tags, and its nearest town.
     * Only file reads, so no model slot is needed; it gives way to the user between batches.
     * Waits for the location permission rather than marking photos as having no location.
     */
    private suspend fun locatePending(deadline: Long): Boolean {
        if (!library.locationAccess()) return true
        val list = places?.get() ?: return true
        var read = 0
        var located = 0
        val started = clock()
        while (clock() < deadline) {
            val batch = dao.placesPending(LOCATE_BATCH)
            if (batch.isEmpty()) break
            coroutineContext.ensureActive()
            for (mediaId in batch) {
                val at = withContext(Dispatchers.IO) { library.location(mediaId) }
                val where = at?.let { (lat, lon) -> list.nearest(lat, lon) }
                dao.setPlace(mediaId, at?.first, at?.second, where?.town, where?.district, where?.region, where?.country)
                read++
                if (at != null) located++
            }
        }
        if (read > 0) Log.i(TAG, "Read the location of $read photos ($located had one) in ${clock() - started} ms")
        val done = dao.placesPendingCount() == 0
        if (done) {
            // Placed before districts were kept: their district, from the same nearest town.
            for (photo in dao.withoutDistrict()) {
                val where = list.nearest(photo.lat, photo.lon)
                dao.setPlace(photo.mediaId, photo.lat, photo.lon, where?.town, where?.district ?: "", where?.region, where?.country)
            }
            guessPlaces()
        }
        return done
    }

    /**
     * Estimates the place of photos without GPS tags from the photos around them in time
     * ([com.local.assistant.gallery.places.PlaceGuess]), and drops estimates that no longer hold.
     * Every photo at once, in memory: a few thousand rows, a few milliseconds.
     */
    private suspend fun guessPlaces() {
        val rows = dao.placeRows()
        val own = rows.filter { it.lat != null && !it.placeGuessed }
        val byId = own.associateBy { it.mediaId }
        val guesses = com.local.assistant.gallery.places.PlaceGuess.guess(
            unplaced = rows.filter { it.lat == null || it.placeGuessed }.associate { it.mediaId to it.dateTaken },
            located = own.map { com.local.assistant.gallery.places.PlaceGuess.Fix(it.mediaId, it.dateTaken, it.lat!!, it.lon!!) },
            everyTakenAt = rows.map { it.dateTaken },
        )
        var changed = 0
        for (row in rows) {
            if (row.lat != null && !row.placeGuessed) continue
            val from = guesses[row.mediaId]?.let { byId.getValue(it.mediaId) }
            if (from == null) {
                if (row.placeGuessed) {
                    dao.setGuessedPlace(row.mediaId, null, null, null, null, null, null, guessed = false)
                    changed++
                }
            } else if (!row.placeGuessed || row.lat != from.lat || row.lon != from.lon) {
                dao.setGuessedPlace(row.mediaId, from.lat, from.lon, from.town, from.district, from.region, from.country, guessed = true)
                changed++
            }
        }
        if (changed > 0) Log.i(TAG, "Estimated places: ${guesses.size} photos placed by the photos around them ($changed changed)")
    }

    /** Photos with a place, their own or estimated. */
    suspend fun locatedCount(): Int = dao.locatedCount()

    /** Photos taken in [area], newest first. */
    suspend fun takenAt(area: com.local.assistant.gallery.places.Places.Area): List<Long> = when (area) {
        is com.local.assistant.gallery.places.Places.Area.InRegion -> dao.inRegions(area.codes)
        is com.local.assistant.gallery.places.Places.Area.InDistrict -> dao.inDistricts(area.codes)
        is com.local.assistant.gallery.places.Places.Area.InCountry -> dao.inCountry(area.code)
        is com.local.assistant.gallery.places.Places.Area.Near -> dao.located().filter { photo ->
            area.points.any { (lat, lon) -> com.local.assistant.gallery.places.Places.km(photo.lat, photo.lon, lat, lon) <= area.radiusKm }
        }.map { it.mediaId }
    }

    /** Where photos were taken, for choosing between towns of one name. */
    suspend fun locations(): List<Pair<Double, Double>> = dao.located().map { it.lat to it.lon }

    /** Photos whose location hasn't been read yet, and whether it can be read now. */
    suspend fun locating(): Pair<Int, Boolean> = dao.placesPendingCount() to library.locationAccess()

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
    /** Side by side, one per vector: whose it is, and for a video's frame, its moment (-1 for a photo). */
    private var ids = LongArray(0)
    private var moments = LongArray(0)
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
                perItem(PhotoRanking.rank(vector, LongArray(ids.size) { it.toLong() }, vectors, norms))
            }
        }.also { Log.i(TAG, "\"$query\": ${it.size} of ${ids.size} vectors in ${clock() - started} ms (query $embedMs ms)") }
    }

    /** Hits on vector positions as hits on photos and videos: a video once, at its best moment. */
    private fun perItem(hits: List<PhotoHit>): List<PhotoHit> {
        val seen = HashSet<Long>()
        return hits.mapNotNull { hit ->
            val i = hit.mediaId.toInt()
            if (!seen.add(ids[i])) return@mapNotNull null
            PhotoHit(ids[i], hit.similarity, moments[i].takeIf { it >= 0 })
        }
    }

    /**
     * [query]'s photos for a chat reply, best first; null when the model isn't installed or won't
     * load. The model is let go a little after the last search unless indexing still needs it, so
     * a chat doesn't keep it in memory.
     */
    suspend fun searchForChat(query: String): com.local.assistant.memory.tools.PhotoSet? {
        val hits = search(query) ?: return null
        unloadWhenIdle()
        return com.local.assistant.memory.tools.PhotoSet.of(query, hits)
    }

    /** Every indexed photo and its similarity to [query], best first: for calibrating [PhotoRanking]. */
    internal suspend fun scoreAll(query: String): List<PhotoHit>? {
        val vector = embedder.embedQuery(query)?.let(Int8Vectors::quantize) ?: return null
        return vectorsLock.withLock {
            withContext(Dispatchers.Default) {
                if (vectorsStale) loadVectors()
                val scores = PhotoRanking.scores(vector, vectors, norms, ids.size) ?: return@withContext emptyList()
                perItem(ids.indices.map { PhotoHit(it.toLong(), scores[it]) }.sortedByDescending { it.similarity })
            }
        }
    }

    /**
     * Of [allowed] (someone's photos), those that match [query], best first, by the same rule as
     * a search of everything; null when the model isn't installed or won't load.
     */
    suspend fun searchAmongForChat(query: String, allowed: Collection<Long>): List<PhotoHit>? {
        val vector = embedder.embedQuery(query)?.let(Int8Vectors::quantize) ?: return null
        val wanted = allowed.toHashSet()
        val hits = vectorsLock.withLock {
            withContext(Dispatchers.Default) {
                if (vectorsStale) loadVectors()
                val dims = Int8Vectors.DIMENSIONS
                val picked = ids.indices.filter { ids[it] in wanted }
                val subVectors = ByteArray(picked.size * dims)
                picked.forEachIndexed { n, i -> vectors.copyInto(subVectors, n * dims, i * dims, (i + 1) * dims) }
                perItem(PhotoRanking.rank(vector, LongArray(picked.size) { picked[it].toLong() }, subVectors, IntArray(picked.size) { norms[picked[it]] }))
            }
        }
        unloadWhenIdle()
        return hits
    }

    /** The videos, newest first. */
    suspend fun videoIds(): List<Long> = dao.videos().map { it.mediaId }

    /** Photos taken in [period], newest first. */
    suspend fun takenIn(period: PhotoPeriod.Range): List<Long> = dao.takenBetween(period.start, period.end)

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
        val frames = dao.frameVectors(GalleryModel.MODEL_ID)
        val total = rows.size + frames.size
        val dims = Int8Vectors.DIMENSIONS
        val newIds = LongArray(total)
        val newMoments = LongArray(total)
        val newVectors = ByteArray(total * dims)
        val newNorms = IntArray(total)
        var size = 0
        fun add(mediaId: Long, atMs: Long, embedding: ByteArray) {
            if (embedding.size != dims) return
            newIds[size] = mediaId
            newMoments[size] = atMs
            embedding.copyInto(newVectors, size * dims)
            newNorms[size] = Int8Vectors.squaredNorm(embedding)
            size++
        }
        rows.forEach { add(it.mediaId, -1, it.embedding) }
        frames.forEach { add(it.mediaId, it.atMs, it.embedding) }
        ids = newIds.copyOf(size)
        moments = newMoments.copyOf(size)
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

        const val LOCATE_BATCH = 100

        /** A video's moments: one every ten seconds, twenty at most (one every 3 minutes in an hour). */
        const val FRAME_EVERY_MS = 10_000L
        const val MAX_FRAMES = 20

        /** How long the model stays loaded after a search from a chat, for a follow-up one. */
        const val IDLE_UNLOAD_MS = 2 * 60_000L
    }
}
