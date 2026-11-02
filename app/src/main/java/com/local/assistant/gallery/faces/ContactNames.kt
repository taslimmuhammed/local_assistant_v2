package com.local.assistant.gallery.faces

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import com.local.assistant.gallery.ContactDeclineEntity
import com.local.assistant.gallery.ContactFaceEntity
import com.local.assistant.gallery.GalleryDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The phone's contacts that have a photo, and the photos. */
interface ContactPhotos {
    data class Entry(val key: String, val name: String, val photoId: Long)

    /** Null when the contacts may not be read. */
    suspend fun withPhotos(): List<Entry>?

    suspend fun photo(key: String): Bitmap?
}

/**
 * Contacts' photos, read only when contacts are already allowed (for calls and messages): naming
 * people doesn't ask for them on its own. On the test phone they are 96 px thumbnails.
 */
class AndroidContactPhotos(private val context: Context) : ContactPhotos {

    override suspend fun withPhotos(): List<ContactPhotos.Entry>? {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        return withContext(Dispatchers.IO) {
            val entries = ArrayList<ContactPhotos.Entry>()
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts.LOOKUP_KEY, ContactsContract.Contacts.DISPLAY_NAME, ContactsContract.Contacts.PHOTO_ID),
                "${ContactsContract.Contacts.PHOTO_ID} IS NOT NULL",
                null,
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val key = c.getString(0) ?: continue
                    val name = c.getString(1)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                    entries += ContactPhotos.Entry(key, name, c.getLong(2))
                }
            }
            entries
        }
    }

    override suspend fun photo(key: String): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val contact = ContactsContract.Contacts.lookupContact(resolver, Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_LOOKUP_URI, key))
                ?: return@runCatching null
            ContactsContract.Contacts.openContactPhotoInputStream(resolver, contact, true)?.use { BitmapFactory.decodeStream(it) }
        }.getOrNull()
    }
}

/**
 * People named from contacts' photos (the user, 2 Nov 2026: most faces had to be named by hand).
 * Each contact's photo is read once (again only when it changes) and its face compared with the
 * unnamed groups ([ContactMatching]): a confident match names the group, a weaker one is offered
 * ("Is this Rahul?") for a yes or a no. A name the user removed, or a no, is never brought back.
 */
class ContactNames(
    private val database: GalleryDatabase,
    private val faces: FaceIndex,
    private val photos: ContactPhotos,
    private val enabled: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val contacts = database.contactFaceDao()
    private val lock = Mutex()

    /**
     * Reads contacts' photos not read yet, then names the groups they confidently match. How
     * many were named; 0 with finding people off or contacts not allowed.
     */
    suspend fun update(): Int = lock.withLock {
        if (!enabled()) return 0
        val entries = photos.withPhotos() ?: return 0
        val known = contacts.all().associateBy { it.contactKey }
        var read = 0
        for (entry in entries) {
            val had = known[entry.key]
            if (had != null && had.photoId == entry.photoId) {
                if (had.name != entry.name) contacts.put(had.copy(name = entry.name))
                continue
            }
            val bitmap = photos.photo(entry.key)
            val print = try {
                bitmap?.let { faces.printOfOnlyFace(it) }
            } finally {
                bitmap?.recycle()
            }
            contacts.put(ContactFaceEntity(entry.key, entry.name, entry.photoId, print?.let(FacePrints::quantize), clock()))
            read++
        }
        // Contacts deleted, or their photo removed: their faces go too.
        val present = entries.map { it.key }.toSet()
        if (known.keys.any { it !in present }) contacts.keepOnly(present.toList())
        val confident = matches().filter { it.auto }
        for (match in confident) faces.nameFromContact(match.groupId, match.name, match.contactKey)
        if (read > 0 || confident.isNotEmpty()) Log.i(TAG, "Read $read contact photos; named ${confident.size} groups from contacts")
        confident.size
    }

    /** Groups a contact may be, for the user to say yes or no, by group id. */
    suspend fun suggestions(): Map<Long, ContactMatching.Match> = lock.withLock {
        if (!enabled()) return emptyMap()
        matches().filter { !it.auto }.associateBy { it.groupId }
    }

    /** Yes: the group is [match]'s contact. */
    suspend fun accept(match: ContactMatching.Match): Long = lock.withLock {
        faces.nameFromContact(match.groupId, match.name, match.contactKey)
    }

    /** No: the group's faces aren't [match]'s contact, and it isn't offered for them again. */
    suspend fun decline(match: ContactMatching.Match) = lock.withLock {
        contacts.decline(faces.facesOfGroup(match.groupId).map { ContactDeclineEntity(match.contactKey, it) })
    }

    private suspend fun matches(): List<ContactMatching.Match> {
        val read = contacts.all().mapNotNull { c -> c.print?.let { ContactMatching.Contact(c.contactKey, c.name, FacePrints.unit(it)) } }
        if (read.isEmpty()) return emptyList()
        val (people, groups) = faces.forContactMatching()
        val declined = contacts.declines().groupBy({ it.contactKey }, { it.faceId }).mapValues { it.value.toSet() }
        return withContext(Dispatchers.Default) { ContactMatching.matches(read, people, groups, declined) }
    }

    private companion object {
        const val TAG = "ContactNames"
    }
}
