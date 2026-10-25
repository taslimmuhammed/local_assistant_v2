package com.local.assistant.gallery.faces

import android.util.Log
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import com.local.assistant.gallery.FaceEntity
import com.local.assistant.gallery.GalleryDatabase
import com.local.assistant.gallery.PersonEntity
import com.local.assistant.gallery.PhotoLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * The people in the photos: finding faces as photos are indexed, grouping them, and the names the
 * user gives. Off until the user turns on "Find people" ([enabled]); nothing about faces is read
 * or kept before that, and [forgetAll] removes everything it found.
 */
class FaceIndex(
    private val database: GalleryDatabase,
    private val library: PhotoLibrary,
    private val newFinder: () -> FaceFinder,
    private val enabled: () -> Boolean,
    /** The name in the user's profile: the person of that name is the user, marked or not. */
    private val userName: suspend () -> String? = { null },
    private val clock: () -> Long = System::currentTimeMillis,
) {

    data class Status(
        val photosDone: Int = 0,
        val photosLeft: Int = 0,
        /** Bumped whenever who-is-who may have changed, for screens to read it again. */
        val version: Int = 0,
    )

    private val photos = database.photoDao()
    private val faces = database.faceDao()

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    /** The finder holds two interpreters and isn't thread-safe: one photo at a time. */
    private val finderLock = Mutex()
    private var finder: FaceFinder? = null

    /**
     * Finds the faces in photos not yet looked at, newest first, a batch per background slot via
     * [runWhenIdle], until [deadline]. Regroups when it found any. False when photos are left.
     */
    suspend fun indexPending(deadline: Long, runWhenIdle: suspend (suspend () -> Boolean) -> Boolean): Boolean {
        if (!enabled()) return true
        var looked = 0
        var found = 0
        val started = clock()
        try {
            while (clock() < deadline) {
                val batch = photos.facesPending(MODEL_ID, BATCH)
                if (batch.isEmpty()) return true
                runWhenIdle {
                    for (photo in batch) {
                        coroutineContext.ensureActive()
                        if (!enabled()) return@runWhenIdle false
                        val bitmap = withContext(Dispatchers.IO) { library.bitmap(photo.mediaId, FaceFinder.PHOTO_PIXELS) }
                        val inPhoto = bitmap?.let { b ->
                            try {
                                finderLock.withLock { withContext(Dispatchers.Default) { finder().find(b) } }
                            } finally {
                                b.recycle()
                            }
                        }.orEmpty()
                        database.useWriterConnection { connection ->
                            connection.immediateTransaction {
                                faces.deleteForPhoto(photo.mediaId)
                                if (inPhoto.isNotEmpty()) {
                                    faces.insert(
                                        inPhoto.map {
                                            FaceEntity(
                                                mediaId = photo.mediaId,
                                                x = it.x, y = it.y, w = it.w, h = it.h,
                                                score = it.score,
                                                print = FacePrints.quantize(it.print),
                                            )
                                        },
                                    )
                                }
                                photos.setFacesModel(photo.mediaId, MODEL_ID)
                            }
                        }
                        looked++
                        found += inPhoto.size
                    }
                    true
                }
                refreshStatus()
            }
            return false
        } finally {
            if (looked > 0) {
                Log.i(TAG, "Looked at $looked photos, $found faces, in ${(clock() - started) / 1000.0} s")
                if (found > 0) regroup()
            }
        }
    }

    /** Places every face not confirmed by the user ([FaceGrouping]) and writes it down. */
    suspend fun regroup() {
        val started = clock()
        val all = faces.all()
        val placements = withContext(Dispatchers.Default) {
            FaceGrouping.place(
                all.map {
                    FaceGrouping.Face(
                        id = it.id,
                        print = FacePrints.unit(it.print),
                        personId = it.personId,
                        confirmed = it.confirmed,
                        notPersonId = it.notPersonId,
                        quality = it.score * minOf(it.w, it.h),
                        side = minOf(it.w, it.h),
                    )
                },
            )
        }
        database.useWriterConnection { connection ->
            connection.immediateTransaction {
                for ((id, placement) in placements) faces.assign(id, placement.personId, placement.groupId)
            }
        }
        val groups = placements.values.mapNotNull { it.groupId }.toSet().size
        Log.i(TAG, "Regrouped ${all.size} faces into ${groups} unnamed groups in ${clock() - started} ms")
        bump()
    }

    // --- People ---------------------------------------------------------------------------

    class Person(val id: Long, val name: String, val isMe: Boolean, val cover: FaceEntity?, val photos: Int)

    class Group(val id: Long, val cover: FaceEntity, val faces: Int, val photos: Int)

    /** Named people, the user first, with the clearest face of each. */
    suspend fun people(): List<Person> = faces.people().map { person ->
        val theirs = faces.ofPerson(person.id)
        Person(person.id, person.name, person.isMe, theirs.maxByOrNull { it.score * it.w }, theirs.map { it.mediaId }.toSet().size)
    }

    /** Unnamed groups, biggest first, with at least [minPhotos] photos each. */
    suspend fun groups(minPhotos: Int = 2): List<Group> =
        faces.all().filter { it.groupId != null && it.personId == null }.groupBy { it.groupId!! }
            .map { (id, members) -> Group(id, members.maxBy { it.score * it.w }, members.size, members.map { it.mediaId }.toSet().size) }
            .filter { it.photos >= minPhotos }
            .sortedByDescending { it.photos }

    suspend fun photosOfPerson(personId: Long): List<Long> = faces.photosOf(personId)

    suspend fun photosOfGroup(groupId: Long): List<Long> =
        faces.inGroup(groupId).mapNotNull { faces.byId(it)?.mediaId }.distinct()

    /** The user names an unnamed group: a new person, or [existing] when it's someone already named. */
    suspend fun nameGroup(groupId: Long, name: String, isMe: Boolean, existing: Long? = null): Long {
        val personId = existing ?: faces.insertPerson(PersonEntity(name = name.trim(), isMe = false, createdAt = clock()))
        faces.confirm(faces.inGroup(groupId), personId)
        // Named with the profile's own name, it's the user even without "This is me".
        if (isMe || name.trim().equals(userName()?.trim(), ignoreCase = true)) faces.setMe(personId)
        regroup()
        return personId
    }

    suspend fun rename(personId: Long, name: String) {
        faces.rename(personId, name.trim())
        bump()
    }

    suspend fun setMe(personId: Long) {
        faces.setMe(personId)
        bump()
    }

    /** Two named people are one: [from]'s faces become [into]'s. */
    suspend fun merge(from: Long, into: Long) {
        if (from == into) return
        database.useWriterConnection { connection ->
            connection.immediateTransaction {
                faces.movePerson(from, into)
                faces.deletePerson(from)
            }
        }
        regroup()
    }

    /** "That's not them": the face leaves the person and is never matched to them again. */
    suspend fun notThem(faceId: Long, personId: Long) {
        faces.reject(faceId, personId)
        regroup()
    }

    /** "That's not them" from a photo of theirs: every face of theirs in [mediaId] leaves them. */
    suspend fun notThemIn(mediaId: Long, personId: Long) {
        for (face in faces.ofPerson(personId)) if (face.mediaId == mediaId) faces.reject(face.id, personId)
        regroup()
    }

    /** The person's faces become nobody's again; their photos stay. */
    suspend fun forget(personId: Long) {
        faces.deletePerson(personId)
        regroup()
    }

    /**
     * "Me", "myself" or a named person, as find_photos is asked for them; null if there's no one.
     * "Me" is whoever is marked "This is me", or else the person named as in the user's profile:
     * the model knows the user's name and asks for "me" when they ask for photos of themself by it.
     */
    suspend fun resolve(who: String): PersonEntity? {
        val name = who.trim().lowercase()
        if (name.isEmpty()) return null
        val people = faces.people()
        if (name in SELF) {
            return people.firstOrNull { it.isMe }
                ?: userName()?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { own -> people.firstOrNull { it.name.lowercase() == own } }
        }
        return people.firstOrNull { it.name.lowercase() == name }
            ?: people.firstOrNull { name.length >= 3 && (name in it.name.lowercase() || it.name.lowercase() in name) }
    }

    /** Photos with every one of [personIds] in them, newest first. */
    suspend fun photosWithAll(personIds: List<Long>): List<Long> {
        if (personIds.isEmpty()) return emptyList()
        val rest = personIds.drop(1).map { faces.photosOf(it).toHashSet() }
        return faces.photosOf(personIds.first()).filter { id -> rest.all { id in it } }
    }

    /** Everything found about faces and every name given, gone; the photos are untouched. */
    suspend fun forgetAll() {
        database.useWriterConnection { connection ->
            connection.immediateTransaction {
                faces.clear()
                faces.clearPeople()
                photos.forgetFacesModel()
            }
        }
        close()
        refreshStatus()
        bump()
    }

    suspend fun refreshStatus() {
        val left = photos.facesPendingCount(MODEL_ID)
        val total = photos.count()
        _status.update { it.copy(photosDone = total - left, photosLeft = left) }
    }

    private fun bump() = _status.update { it.copy(version = it.version + 1) }

    private fun finder(): FaceFinder = finder ?: newFinder().also { finder = it }

    /** Gives the models' memory back; the next photo loads them again. */
    suspend fun close() = finderLock.withLock {
        finder?.close()
        finder = null
    }

    companion object {
        private const val TAG = "FaceIndex"

        /** Which models found the faces: a change means looking at every photo again. */
        const val MODEL_ID = "yunet-2023mar+sface-2021dec-int8w"

        const val BATCH = 16

        val SELF = setOf("me", "myself", "i", "user", "the user", "my", "mine")
    }
}
