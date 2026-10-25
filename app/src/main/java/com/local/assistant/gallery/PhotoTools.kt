package com.local.assistant.gallery

import com.local.assistant.gallery.faces.FaceIndex
import com.local.assistant.memory.tools.ActionResult
import com.local.assistant.memory.tools.MemoryChip
import com.local.assistant.memory.tools.PhotoSet
import com.local.assistant.memory.tools.ToolError

/**
 * find_photos: the phone's photos that match what the user asked for, of something, of people
 * they named (People, on the Photos screen), or both. The model only gets the counts back; the
 * photos themselves travel in the chip and are drawn under the reply, so no image goes through
 * the model and no image tokens are spent.
 *
 * What the model sends is taken loosely, as it was seen to send it on the phone (2026-10-25):
 * "photos of shiyas" came as person shiyas, query "all", and "me with shiyas" as person me,
 * query shiyas. So filler words in the query are dropped, a query that names someone already
 * named counts as another person, and several people can be named at once ("me, shiyas").
 */
class PhotoTools(
    private val index: GalleryIndex,
    private val ready: () -> Boolean,
    private val faces: FaceIndex? = null,
    private val findPeople: () -> Boolean = { false },
) {

    internal suspend fun find(query: String?, person: String?): ActionResult {
        if (!ready()) throw ToolError("Photo search isn't set up. Tell the user to open Photos in the menu to turn it on.")
        val names = people(person).toMutableList()
        var what = query?.trim()?.take(MAX_QUERY)?.takeIf { it.isNotEmpty() }
        // A query that is someone already named is a second person, not something in the photo.
        if (what != null && faces != null && findPeople()) {
            val asPeople = people(what)
            if (asPeople.isNotEmpty() && asPeople.all { faces.resolve(it) != null }) {
                names += asPeople
                what = null
            }
        }
        what = what?.let(::description)
        if (what == null && names.isEmpty()) throw ToolError("Say what the photos are of, or who is in them.")
        val (indexed, remaining) = index.counts()
        if (indexed == 0) {
            throw ToolError("The photos aren't ready for search yet. Tell the user they get ready while Photos in the menu is open, and overnight while charging.")
        }

        val named = names.distinct().map { name ->
            if (faces == null || !findPeople()) {
                throw ToolError("Finding people in photos is off. Tell the user they can turn it on under Photos in the menu, then name people there.")
            }
            faces.resolve(name) ?: throw ToolError(
                if (name.lowercase() in FaceIndex.SELF) {
                    "The user hasn't said which face is theirs. Tell them to open Photos in the menu, tap their face under People, and choose This is me."
                } else {
                    "No one called $name has been named. Tell the user they can name people under Photos in the menu, in People."
                },
            )
        }.distinctBy { it.id }

        val ids = when {
            named.isNotEmpty() && what == null -> faces!!.photosWithAll(named.map { it.id })
            named.isNotEmpty() -> index.searchAmongForChat(what!!, faces!!.photosWithAll(named.map { it.id }))
            else -> index.searchForChat(what!!)?.ids
        } ?: throw ToolError("Photo search couldn't start. Tell the user to try again in a moment.")

        val who = named.joinToString(" & ") { if (it.isMe) "You" else it.name }.takeIf { it.isNotEmpty() }
        val label = listOfNotNull(who, what).joinToString(" · ")
        val result = linkedMapOf<String, Any?>("ok" to true, "found" to ids.size)
        if (named.isNotEmpty()) result["people"] = named.map { it.name }
        // Said so the model doesn't call it a full answer while most of the library is unseen.
        if (remaining > 0) result["not_yet_searchable"] = remaining
        return ActionResult(
            result,
            MemoryChip(MemoryChip.Kind.PHOTOS, "Photos", label, photos = PhotoSet(label, ids)).takeIf { ids.isNotEmpty() },
        )
    }

    companion object {
        const val MAX_QUERY = 120

        /** Words that say "photos" or "all of them" rather than what's in them. */
        private val FILLER = setOf(
            "all", "every", "everything", "any", "photo", "photos", "picture", "pictures", "pic", "pics", "image", "images",
            "of", "the", "my", "me", "myself", "mine", "together", "with", "and", "show", "find",
        )

        /** "me, shiyas", "me and shiyas", "taslim & shiyas" as separate names. */
        fun people(text: String?): List<String> =
            text.orEmpty().split(Regex("\\s*(?:,|&|/|\\band\\b|\\bwith\\b)\\s*", RegexOption.IGNORE_CASE))
                .map { it.trim() }
                .filter { it.isNotEmpty() }

        /** What the photos are of, without the filler; null when nothing is left. */
        fun description(query: String): String? =
            query.split(Regex("\\s+")).filter { it.lowercase().trim(',', '.', '!', '?') !in FILLER }.joinToString(" ").takeIf { it.isNotBlank() }
    }
}
