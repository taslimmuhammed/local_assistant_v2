package com.local.assistant.gallery.faces

import android.util.Log
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import com.local.assistant.gallery.ContactDeclineEntity
import com.local.assistant.gallery.FaceEntity
import com.local.assistant.gallery.GalleryDatabase
import com.local.assistant.gallery.PersonEntity
import com.local.assistant.gallery.PhotoLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
    /** Which [FaceGrouping.VERSION] the faces were last placed by, kept across launches. */
    private val groupedBy: () -> Int = { FaceGrouping.VERSION },
    private val setGroupedBy: (Int) -> Unit = {},
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

    /** "Find people" is on. */
    val isOn: Boolean get() = enabled()

    /** Faces found since they were last grouped: grouped again every [REGROUP_EVERY], and at the end of a pass. */
    @Volatile
    private var ungrouped = 0

    /** Whether [photo] still needs its faces looked for: Find people is on, it's a photo, and these models haven't seen it. */
    fun wants(photo: com.local.assistant.gallery.PhotoEntity): Boolean =
        enabled() && photo.durationMs == null && photo.facesModel != MODEL_ID

    /**
     * Finds and keeps the faces in [bitmap], photo [mediaId] decoded at up to
     * [FaceFinder.PHOTO_PIXELS]; the caller recycles it. The photo index calls this for each
     * photo as it embeds it, so people are found along with the photos, not after all of them.
     * Returns how many faces it found.
     */
    suspend fun lookAt(mediaId: Long, bitmap: android.graphics.Bitmap): Int {
        // New matching rules: the faces found so far are placed again by them, once.
        if (groupedBy() != FaceGrouping.VERSION) {
            regroup()
            setGroupedBy(FaceGrouping.VERSION)
        }
        val inPhoto = finderLock.withLock { withContext(Dispatchers.Default) { finder().find(bitmap) } }
        keep(mediaId, inPhoto)
        ungrouped += inPhoto.size
        foundSoFar += inPhoto.size
        return inPhoto.size
    }

    /** Faces found since the app started, so a pass can tell whether it found any. */
    @Volatile
    var foundSoFar = 0L
        private set

    /** A photo that couldn't be read: marked looked at, with no faces, as before. */
    suspend fun unreadable(mediaId: Long) = keep(mediaId, emptyList())

    private suspend fun keep(mediaId: Long, inPhoto: List<FoundFace>) {
        database.useWriterConnection { connection ->
            connection.immediateTransaction {
                faces.deleteForPhoto(mediaId)
                if (inPhoto.isNotEmpty()) {
                    faces.insert(
                        inPhoto.map {
                            FaceEntity(
                                mediaId = mediaId,
                                x = it.x, y = it.y, w = it.w, h = it.h,
                                score = it.score,
                                print = FacePrints.quantize(it.print),
                            )
                        },
                    )
                }
                photos.setFacesModel(mediaId, MODEL_ID)
            }
        }
    }

    /** Groups the faces found so far once enough are new, so people appear while indexing runs. */
    suspend fun regroupIfDue() {
        if (ungrouped >= REGROUP_EVERY) regroupNew()
    }

    /**
     * Groups whatever was found since the last grouping, even when the pass was stopped: a stopped
     * pass used to leave its faces ungrouped until the next one finished.
     */
    suspend fun regroupNew() {
        if (ungrouped == 0) return
        withContext(NonCancellable) {
            ungrouped = 0
            regroup()
        }
    }

    /**
     * Finds the faces in photos not yet looked at, newest first, a batch per background slot via
     * [runWhenIdle], until [deadline]: the photos indexed before Find people was turned on. Most
     * photos get theirs from [lookAt] as they're indexed. False when photos are left.
     */
    suspend fun indexPending(deadline: Long, runWhenIdle: suspend (suspend () -> Boolean) -> Boolean): Boolean {
        if (!enabled()) return true
        var looked = 0
        var found = 0
        val started = clock()
        try {
            while (clock() < deadline) {
                val finished = runWhenIdle {
                    // Read inside the slot: a batch read before it may be half done by the time it starts.
                    val batch = photos.facesPending(MODEL_ID, BATCH)
                    if (batch.isEmpty()) return@runWhenIdle true
                    for (photo in batch) {
                        coroutineContext.ensureActive()
                        // Turned off meanwhile: stop rather than come back for the same batch.
                        if (!enabled()) return@runWhenIdle true
                        val bitmap = withContext(Dispatchers.IO) { library.bitmap(photo.mediaId, FaceFinder.PHOTO_PIXELS) }
                        if (bitmap == null) {
                            unreadable(photo.mediaId)
                        } else {
                            try {
                                found += lookAt(photo.mediaId, bitmap)
                            } finally {
                                bitmap.recycle()
                            }
                        }
                        looked++
                    }
                    false
                }
                refreshStatus()
                if (!enabled() || finished) return true
                regroupIfDue()
            }
            return false
        } finally {
            if (looked > 0) Log.i(TAG, "Looked at $looked photos, $found faces, in ${(clock() - started) / 1000.0} s")
            regroupNew()
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

    /** [fromContacts]: named from a contact's photo ([ContactNames]). */
    class Person(val id: Long, val name: String, val isMe: Boolean, val cover: FaceEntity?, val photos: Int, val fromContacts: Boolean = false)

    class Group(val id: Long, val cover: FaceEntity, val faces: Int, val photos: Int)

    /** Named people, the user first, with the clearest face of each. */
    suspend fun people(): List<Person> = faces.people().map { person ->
        val theirs = faces.ofPerson(person.id)
        Person(person.id, person.name, person.isMe, theirs.maxByOrNull { it.score * it.w }, theirs.map { it.mediaId }.toSet().size, person.contactKey != null)
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

    /**
     * The person's faces become nobody's again; their photos stay. Named from a contact, those
     * faces are marked as not that contact, so the name isn't put back.
     */
    suspend fun forget(personId: Long) {
        faces.person(personId)?.contactKey?.let { key ->
            database.contactFaceDao().decline(faces.ofPerson(personId).map { ContactDeclineEntity(key, it.id) })
        }
        faces.deletePerson(personId)
        regroup()
    }

    // --- Names from contacts ([ContactNames]) ------------------------------------------------

    /** The print of the one face in [photo] (a contact's), or null when it has none or several. */
    suspend fun printOfOnlyFace(photo: android.graphics.Bitmap): FloatArray? =
        finderLock.withLock { withContext(Dispatchers.Default) { finder().find(photo) } }.singleOrNull()?.print

    /** The named people and the unnamed groups (as [groups] lists them), with their faces' prints. */
    suspend fun forContactMatching(): Pair<List<ContactMatching.Person>, List<ContactMatching.Group>> {
        val all = faces.all()
        val people = faces.people().map { person ->
            ContactMatching.Person(person.name, person.contactKey, all.filter { it.personId == person.id }.map { FacePrints.unit(it.print) })
        }
        val shown = groups().map { it.id }.toSet()
        val groups = all.filter { it.groupId in shown && it.personId == null }.groupBy { it.groupId!! }
            .map { (id, members) -> ContactMatching.Group(id, members.map { it.id }.toSet(), members.map { FacePrints.unit(it.print) }) }
        return people to groups
    }

    suspend fun facesOfGroup(groupId: Long): List<Long> = faces.inGroup(groupId)

    /**
     * Names the group after the contact [contactKey], as if the user had typed [name]; the person
     * and where the name came from are written together.
     */
    suspend fun nameFromContact(groupId: Long, name: String, contactKey: String): Long {
        val personId = faces.insertPerson(PersonEntity(name = name.trim(), isMe = false, createdAt = clock(), contactKey = contactKey))
        faces.confirm(faces.inGroup(groupId), personId)
        if (name.trim().equals(userName()?.trim(), ignoreCase = true)) faces.setMe(personId)
        regroup()
        return personId
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
                database.contactFaceDao().clear()
                database.contactFaceDao().clearDeclines()
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

        /** New faces between groupings while indexing: people show up as they're found. */
        const val REGROUP_EVERY = 500

        val SELF = setOf("me", "myself", "i", "user", "the user", "my", "mine")
    }
}
