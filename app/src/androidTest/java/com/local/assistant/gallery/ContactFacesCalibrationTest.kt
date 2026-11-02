package com.local.assistant.gallery

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.provider.ContactsContract
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.local.assistant.gallery.faces.FaceFinder
import com.local.assistant.gallery.faces.FacePrints
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Whether contacts' photos can name the people in the photos: each contact with a photo has its
 * face found and printed as a photo's would be, then compared with the people the user named and
 * with the unnamed groups, as `FaceGrouping` compares (the mean of the three closest faces).
 * Nobody looks at a face or a name: the log has scores, and whether the closest named person
 * carries the contact's name.
 *
 *     adb shell am instrument -w -e eval contactfaces -e class com.local.assistant.gallery.ContactFacesCalibrationTest \
 *         com.local.assistant.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Results go to logcat (tag ContactFaces) and `Android/data/<app>/files/contact-faces.txt`.
 */
@RunWith(AndroidJUnit4::class)
class ContactFacesCalibrationTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val out by lazy { File(context.getExternalFilesDir(null), "contact-faces.txt").apply { writeText("") } }

    @Test
    fun contactsAgainstNamedPeople() = runBlocking<Unit> {
        assumeTrue("Opt-in: -e eval contactfaces", InstrumentationRegistry.getArguments().getString("eval") == "contactfaces")
        assumeTrue("Contacts not allowed", context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED)
        val faces = GalleryDatabase.build(context).faceDao()
        val people = faces.people()
        val all = faces.all()
        val named = people.associateWith { person -> all.filter { it.personId == person.id }.map { FacePrints.unit(it.print) } }
        val groups = all.filter { it.groupId != null && it.personId == null }.groupBy { it.groupId!! }
            .mapValues { (_, members) -> members.map { FacePrints.unit(it.print) } }
            .filterValues { it.size >= 2 }
        report("${people.size} named people (${named.values.sumOf { it.size }} faces), ${groups.size} unnamed groups")

        val resolver = context.contentResolver
        data class Row(val id: Long, val name: String, val highRes: Boolean)
        val rows = ArrayList<Row>()
        resolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME, ContactsContract.Contacts.PHOTO_FILE_ID),
            "${ContactsContract.Contacts.PHOTO_ID} IS NOT NULL", null, null,
        )?.use { c ->
            while (c.moveToNext()) rows += Row(c.getLong(0), c.getString(1).orEmpty(), !c.isNull(2))
        }
        report("${rows.size} contacts with a photo, ${rows.count { it.highRes }} of them full size")

        FaceFinder(context.assets).use { finder ->
            for (row in rows) {
                val uri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, row.id)
                val bitmap = ContactsContract.Contacts.openContactPhotoInputStream(resolver, uri, true)?.use { BitmapFactory.decodeStream(it) }
                if (bitmap == null) {
                    report("contact | no photo readable")
                    continue
                }
                val found = finder.find(bitmap)
                val size = "${bitmap.width}x${bitmap.height}"
                bitmap.recycle()
                val face = found.maxByOrNull { it.w * it.h }
                if (face == null) {
                    report("contact | $size | no face")
                    continue
                }
                val print = FacePrints.unit(FacePrints.quantize(face.print))
                val byPerson = named.mapValues { (_, prints) -> closest(print, prints) }.entries.sortedByDescending { it.value }
                val byGroup = groups.mapValues { (_, prints) -> closest(print, prints) }.entries.sortedByDescending { it.value }
                val best = byPerson.firstOrNull()
                val sameName = best?.key?.name?.let { sameName(it, row.name) } == true
                // Whether any named person carries this contact's name: the right answer, if the face matches.
                val namedAlike = people.any { sameName(it.name, row.name) }
                // Looser: one name inside the other ("antha" in "Anantha K"), the best person's or anyone's.
                val bestLoose = best?.key?.name?.let { looseName(it, row.name) } == true
                val anyLoose = people.count { looseName(it.name, row.name) }
                report(
                    "contact | $size | ${found.size} faces, face %.2f of the photo | best named %.3f%s (2nd %.3f) | named alike: $namedAlike, loosely: best $bestLoose, any $anyLoose, best is me: ${best?.key?.isMe} | best group %.3f (2nd %.3f)".format(
                        face.w * face.h,
                        best?.value ?: -1f, if (sameName) " SAME NAME" else "",
                        byPerson.getOrNull(1)?.value ?: -1f,
                        byGroup.firstOrNull()?.value ?: -1f, byGroup.getOrNull(1)?.value ?: -1f,
                    ),
                )
            }
        }
    }

    /** First names alike: "Shiyas" and "Shiyas K", "taslim" and "Taslim Muhammed Moosa". */
    private fun sameName(a: String, b: String): Boolean {
        val x = a.trim().lowercase().split(Regex("\\s+")).firstOrNull().orEmpty()
        val y = b.trim().lowercase().split(Regex("\\s+")).firstOrNull().orEmpty()
        return x.isNotEmpty() && x == y
    }

    /** One name inside the other, as words: "antha" and "Anantha K" no, "taslim" and "Taslim M" yes, "shiyas" and "shiyasmon" yes. */
    private fun looseName(a: String, b: String): Boolean {
        val x = a.trim().lowercase()
        val y = b.trim().lowercase()
        if (x.length < 3 || y.length < 3) return false
        return x in y || y in x || x.split(Regex("\\s+")).any { w -> w.length >= 3 && y.split(Regex("\\s+")).any { it.startsWith(w) || w.startsWith(it) } }
    }

    /** Mean similarity to the three closest of [prints], as `FaceGrouping` matches. */
    private fun closest(print: FloatArray, prints: List<FloatArray>): Float =
        prints.map { FacePrints.similarity(print, it) }.sortedDescending().take(3).let { if (it.isEmpty()) -1f else it.average().toFloat() }

    private fun report(line: String) {
        Log.i(TAG, line)
        out.appendText("$line\n")
    }

    private companion object {
        const val TAG = "ContactFaces"
    }
}
