package com.local.assistant.gallery

import android.util.Log
import com.local.assistant.gallery.faces.FaceFinder
import com.local.assistant.memory.embed.Int8Vectors
import com.local.assistant.memory.work.ModelScheduler
import com.local.assistant.privacy.LogText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
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
 * ones out) and then embeds whatever has no vector yet: photos newest first, then videos. With
 * Find people on, each photo's faces are looked for in the same slot. Every batch goes through
 * the [ModelScheduler], behind anything the user or the chat memory is doing, and stops between
 * photos when they start. It starts once the photo model and access are there and runs while the
 * app is in front, and on the charger at night ([indexUntil]).
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
    /** After a pass that found faces: contacts' photos name the people they clearly match. */
    private val afterFaces: suspend () -> Unit = {},
    /** The phone's thermal status (`PowerManager.THERMAL_STATUS_*`); indexing waits while it's warm. */
    private val heat: () -> Int = { 0 },
    private val clock: () -> Long = System::currentTimeMillis,
) {

    data class Status(
        /** Photos the app can see. */
        val total: Int = 0,
        /** Of those, how many have been looked at (including the few that could not be read). */
        val indexed: Int = 0,
        val running: Boolean = false,
        /** Waiting for the phone to cool before carrying on. */
        val cooling: Boolean = false,
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

    /** Stops indexing and gives the model's memory back (what memory search isn't using); [start] carries on where it stopped. */
    suspend fun pause() {
        synchronized(this) { job }?.let {
            it.cancel()
            it.join()
        }
        embedder.release()
        faces?.close()
    }

    /** For the nightly job: false when photos are left that a later run should get to. */
    suspend fun indexUntil(deadline: Long): Boolean = passLock.withLock {
        try {
            pass(deadline)
        } finally {
            _status.update { it.copy(running = false) }
            embedder.release()
            faces?.close()
        }
    }

    /** False when photos are left to do; true when done, or when nothing can be done for now. */
    private suspend fun pass(deadline: Long): Boolean {
        if (!embedder.isInstalled || library.access() == PhotoLibrary.Access.NONE) return true
        _status.update { it.copy(running = true) }
        val facesBefore = faces?.foundSoFar ?: 0L
        var finished = false
        try {
            sync()
            refreshStatus()
            // Photos and their faces together, then which are documents (the model is still
            // loaded), then where they were taken (quick), then the faces of photos indexed
            // before Find people was on.
            if (!indexPending(deadline)) return false
            scoreDocuments()
            embedder.release()
            if (!locatePending(deadline)) return false
            finished = faces?.indexPending(deadline) { block -> runWhenIdle(block) } ?: true
            return finished
        } finally {
            // Faces found are grouped even when the pass was stopped, so people show up.
            faces?.regroupNew()
            if (finished && faces != null && faces.foundSoFar > facesBefore) {
                runCatching { afterFaces() }.onFailure { if (it is CancellationException) throw it else Log.w(TAG, "Naming people from contacts failed", it) }
            }
        }
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

    private enum class Slot { MORE, DONE, NO_MODEL }

    private suspend fun indexPending(deadline: Long): Boolean {
        var embedded = 0
        var looked = 0
        val started = clock()
        try {
            while (clock() < deadline) {
                val slot = runWhenIdle { coroutineScope {
                    // Read inside the slot: a batch read before it could be half saved by a slot
                    // the user's turn cut short, and those photos embedded twice.
                    val batch = dao.pending(GalleryModel.MODEL_ID, BATCH)
                    if (batch.isEmpty()) return@coroutineScope Slot.DONE
                    for (photo in batch) {
                        ensureActive()
                        if (photo.durationMs != null) {
                            // A video is a slot of its own: up to twenty moments, several seconds.
                            if (photo !== batch.first()) break
                            if (!indexVideo(photo, photo.durationMs)) return@coroutineScope Slot.NO_MODEL
                            embedded++
                            break
                        }
                        // Its faces while it's embedded (the face models run on the CPU, the photo
                        // model's vision on the GPU), so people are found along with the photos.
                        // Decoded on its own at the larger size faces need: the photo vector stays
                        // exactly what it was.
                        val lookedFor = if (faces?.wants(photo) == true) async(Dispatchers.Default) { lookForFaces(faces, photo.mediaId) } else null
                        val jpeg = withContext(Dispatchers.IO) { library.jpeg(photo.mediaId, GalleryModel.INPUT_PIXELS) }
                        val vector = jpeg?.let { embedder.embedImage(it) }?.let(Int8Vectors::quantize)
                        if (vector == null && !embedder.isLoaded) {
                            lookedFor?.cancelAndJoin()
                            return@coroutineScope Slot.NO_MODEL
                        }
                        dao.setEmbedding(photo.mediaId, vector, if (vector == null) PhotoEntity.FAILED else GalleryModel.MODEL_ID)
                        vectorsStale = true
                        embedded++
                        if (lookedFor != null) {
                            lookedFor.await()
                            looked++
                        }
                    }
                    Slot.MORE
                } }
                refreshStatus()
                if (looked > 0) faces?.refreshStatus()
                when (slot) {
                    Slot.DONE -> return true
                    // The model would not load: nothing more can be done until it does.
                    Slot.NO_MODEL -> return true
                    Slot.MORE -> faces?.regroupIfDue()
                }
            }
            return false
        } finally {
            if (embedded > 0) {
                val seconds = (clock() - started) / 1000.0
                Log.i(TAG, "Indexed $embedded photos (faces looked for in $looked) in ${"%.1f".format(seconds)} s; ${_status.value.remaining} left")
            }
        }
    }

    /** Finds and keeps the faces in photo [mediaId]; one that can't be read is marked looked at. */
    private suspend fun lookForFaces(faces: com.local.assistant.gallery.faces.FaceIndex, mediaId: Long) {
        val bitmap = withContext(Dispatchers.IO) { library.bitmap(mediaId, FaceFinder.PHOTO_PIXELS) }
        if (bitmap == null) {
            faces.unreadable(mediaId)
            return
        }
        try {
            faces.lookAt(mediaId, bitmap)
        } finally {
            bitmap.recycle()
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

    /**
     * Embeds moments of a video, saving each as it's done, so a video the user's turn interrupts
     * carries on where it stopped rather than from its first moment. False when the model won't
     * load. A video that can't be read is marked failed.
     */
    private suspend fun indexVideo(video: PhotoEntity, durationMs: Long): Boolean {
        val mediaId = video.mediaId
        val partial = PhotoEntity.partial(GalleryModel.MODEL_ID)
        // Part done by this model: those moments are kept. New, edited or done by another model:
        // its old moments go.
        val done = if (video.modelId == partial) {
            dao.frameTimes(mediaId).toHashSet()
        } else {
            dao.startVideo(mediaId, partial)
            emptySet()
        }
        val frames = withContext(Dispatchers.IO) {
            library.frames(mediaId, durationMs, GalleryModel.INPUT_PIXELS, FRAME_EVERY_MS, MAX_FRAMES, skip = done)
        }
        var kept = done.size
        for ((atMs, jpeg) in frames) {
            coroutineContext.ensureActive()
            val vector = embedder.embedImage(jpeg)?.let(Int8Vectors::quantize)
            if (vector == null) {
                if (!embedder.isLoaded) return false
                continue
            }
            dao.insertFrame(FrameEntity(mediaId, atMs, vector))
            kept++
        }
        dao.setEmbedding(mediaId, null, if (kept == 0) PhotoEntity.FAILED else GalleryModel.MODEL_ID)
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

    /** Runs [block] in a background slot, waiting out the user's typing and turns, and the phone's heat. */
    private suspend fun <T : Any> runWhenIdle(block: suspend () -> T): T {
        while (true) {
            coolDown()
            val result = scheduler.runBackground(ModelScheduler.Priority.GALLERY_INDEX) { block() }
            if (result != null) return result
            delay(ModelScheduler.QUIET_MS)
        }
    }

    /**
     * Waits while the phone is warm: from [HOT] until it is back to [COOL]. Indexing flat out
     * took the OnePlus from cool to MODERATE in about three minutes, and it cooled again in one
     * or two (2026-11-14).
     */
    private suspend fun coolDown() {
        if (heat() < HOT) return
        Log.i(TAG, "Paused while the phone cools")
        _status.update { it.copy(cooling = true) }
        try {
            while (heat() > COOL) delay(COOL_CHECK_MS)
        } finally {
            _status.update { it.copy(cooling = false) }
        }
        Log.i(TAG, "Carrying on: the phone has cooled")
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
        }.also { Log.i(TAG, "\"${LogText.of(query)}\": ${it.size} of ${ids.size} vectors in ${clock() - started} ms (query $embedMs ms)") }
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

    /**
     * The [limit] photos most like [query], best first, however they score; videos left out. For
     * [PhotoRecall], which sets its own bar. Null when the model isn't installed or won't load.
     */
    suspend fun bestPhotos(query: String, limit: Int): List<PhotoHit>? {
        if (query.isBlank()) return emptyList()
        val vector = embedder.embedQuery(query)?.let(Int8Vectors::quantize) ?: return null
        unloadWhenIdle()
        return vectorsLock.withLock {
            withContext(Dispatchers.Default) {
                if (vectorsStale) loadVectors()
                val scores = PhotoRanking.scores(vector, vectors, norms, ids.size) ?: return@withContext emptyList()
                ids.indices.filter { moments[it] < 0 }
                    .sortedByDescending { scores[it] }
                    .take(limit)
                    .map { PhotoHit(ids[it], scores[it]) }
            }
        }
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
            if (synchronized(this@GalleryIndex) { job?.isActive } != true) embedder.release()
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

        /** `PowerManager.THERMAL_STATUS_MODERATE` and `_LIGHT`: indexing pauses at the first and carries on at the second. */
        const val HOT = 2
        const val COOL = 1
        const val COOL_CHECK_MS = 15_000L
    }
}
