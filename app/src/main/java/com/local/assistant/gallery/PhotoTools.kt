package com.local.assistant.gallery

import com.local.assistant.gallery.faces.FaceIndex
import com.local.assistant.gallery.places.PlaceList
import com.local.assistant.gallery.places.Places
import com.local.assistant.memory.tools.ActionResult
import com.local.assistant.memory.tools.MemoryChip
import com.local.assistant.memory.tools.PhotoSet
import com.local.assistant.memory.tools.ToolError
import java.time.ZonedDateTime

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
    private val places: PlaceList? = null,
    /** A copy of the photo sharp enough to read text from, as a file for the model; null if it can't be read. */
    private val readable: suspend (mediaId: Long) -> String? = { null },
    /** Whether a found photo may be read out: not over the lock screen, where photos stay hidden. */
    private val mayRead: () -> Boolean = { true },
    /**
     * Answers a question from a found photo on its own ([PhotoReader]), the reply written; null
     * when it can't, and then the photo goes to the chat model to read.
     */
    private val reader: (suspend (path: String, question: String) -> String?)? = null,
    private val now: () -> ZonedDateTime = ZonedDateTime::now,
) {

    /**
     * [question]: what the user wants to know from the photos ("SSLC score"); the best match then
     * goes back to the model with the result, to answer from what it shows.
     */
    internal suspend fun find(
        query: String?,
        person: String?,
        taken: String? = null,
        place: String? = null,
        question: String? = null,
    ): ActionResult {
        if (!ready()) throw ToolError("Photo search isn't set up. Tell the user to open Photos in the menu to turn it on.")
        val names = people(person).toMutableList()
        var what = query?.trim()?.take(MAX_QUERY)?.takeIf { it.isNotEmpty() }
        // A query that is someone already named is a second person, not something in the photo.
        // "Me" only beside someone named ("me with shiyas" came as person shiyas, query me): alone,
        // as in "show me photos from Calicut" (query me), it meant nobody.
        if (what != null && faces != null && findPeople()) {
            val asPeople = people(what).filter { names.isNotEmpty() || it.lowercase() !in FaceIndex.SELF }
            if (asPeople.isNotEmpty() && asPeople.all { faces.resolve(it) != null }) {
                names += asPeople
                what = null
            }
        }
        // "beach videos", "videos from Goa": videos only.
        val videosOnly = what?.split(Regex("\\s+"))?.any { it.lowercase().trim(',', '.', '!', '?') in VIDEO_WORDS } == true
        what = what?.let(::description)
        var period = taken?.trim()?.takeIf { it.isNotEmpty() }?.let { phrase ->
            PhotoPeriod.parse(phrase, now()) ?: throw ToolError("Couldn't tell when \"$phrase\" is. Ask the user for a day, month or year.")
        }
        // For the card: the time as the user put it.
        var periodWords = taken?.trim()?.takeIf { period != null }
        // A date the model left in the query ("2023", "beach last december") is a when.
        if (period == null && what != null) {
            PhotoPeriod.parse(what, now())?.let { inQuery ->
                val rest = PhotoPeriod.withoutTime(what!!)
                period = inQuery
                periodWords = what!!.split(Regex("\\s+")).filter { it !in rest.split(' ') }.joinToString(" ")
                what = description(rest)
            }
        }
        val placeName = place?.trim()?.takeIf { it.isNotEmpty() }
        // Given only what to read ("SSLC score", no query), search by that: it names the photo well enough.
        if (what == null && names.isEmpty() && period == null && placeName == null) {
            what = question?.trim()?.take(MAX_QUERY)?.let(::description)
        }
        // Asking what a document says, "pan" is the PAN card: alone, it found a frying pan.
        if (!question.isNullOrBlank()) what = what?.let(::documentQuery)
        if (what == null && names.isEmpty() && period == null && placeName == null && !videosOnly) {
            throw ToolError("Say what the photos are of, who is in them, where or when they were taken.")
        }
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

        val area = placeName?.let { area(it) }

        // Who, where and when narrow the photos down, newest first; what's in them then ranks those.
        var narrowed: List<Long>? = null
        fun narrow(to: List<Long>) {
            narrowed = narrowed?.let { before -> to.toHashSet().let { set -> before.filter { it in set } } } ?: to
        }
        if (videosOnly) narrow(index.videoIds())
        if (named.isNotEmpty()) narrow(faces!!.photosWithAll(named.map { it.id }))
        if (area != null) narrow(index.takenAt(area))
        if (period != null) narrow(index.takenIn(period))
        val found = when {
            what == null -> PhotoSet("", narrowed!!)
            narrowed != null -> index.searchAmongForChat(what, narrowed!!)?.let { PhotoSet.of("", it) }
            else -> index.searchForChat(what)
        } ?: throw ToolError("Photo search couldn't start. Tell the user to try again in a moment.")
        val ids = found.ids

        val who = named.joinToString(" & ") { if (it.isMe) "You" else it.name }.takeIf { it.isNotEmpty() }
        val where = when (area) {
            is Places.Area.Near -> area.name
            is Places.Area.InRegion -> area.name
            is Places.Area.InDistrict -> area.name
            is Places.Area.InCountry -> area.name
            null -> null
        }
        val label = listOfNotNull(who, what, where, periodWords).joinToString(" · ")
        val result = linkedMapOf<String, Any?>("ok" to true)
        // The area searched, in full ("Jammu and Kashmir" for "Kashmir"), for the reply to name.
        if (where != null) result["place"] = where
        // Photos and videos apart, when there are videos: given a total and the videos among it,
        // the model added them up again ("18 photos and 4 videos" for 14 and 4).
        val videos = index.videoIds().toHashSet().let { all -> ids.count { it in all } }
        if (videos == 0) {
            result["found"] = ids.size
        } else {
            result["photos"] = ids.size - videos
            result["videos"] = videos
        }
        if (named.isNotEmpty()) result["people"] = named.map { it.name }
        // Said so the model doesn't call it a full answer while most of the library is unseen.
        if (remaining > 0) result["not_yet_searchable"] = remaining
        if (area != null) index.locating().first.takeIf { it > 0 }?.let { result["location_not_yet_read"] = it }
        // Said in words: the model read past bare fields, answering "in Kashmir" with place
        // "Jammu and Kashmir" given, and "I found 0 photos" with the located count given.
        val notes = ArrayList<String>()
        if (where != null && !where.equals(placeName, ignoreCase = true)) {
            notes += "The place searched is $where, which is what \"$placeName\" matched: use the name $where in the reply."
        }
        // Most photos carry no location (the camera's setting off, received photos stripped of
        // it), so "none from Munnar" mustn't sound like none were taken there.
        if (area != null && ids.isEmpty()) {
            notes += "Only ${index.locatedCount()} of the user's $indexed photos have a saved location, so photos from there " +
                "may have none. Say so, and that they can search by what's in them instead."
        }
        val asked = question?.trim()?.takeIf { it.isNotEmpty() }
        val read = if (asked != null && ids.isNotEmpty()) read(ids, videos = index.videoIds().toHashSet(), asked, notes) else null
        if (read?.reply != null) result["answered"] = "from the best-matching photo, read on its own"
        if (notes.isNotEmpty()) result["note"] = notes.joinToString(" ")
        return ActionResult(
            result,
            MemoryChip(MemoryChip.Kind.PHOTOS, "Photos", label, photos = found.copy(query = label)).takeIf { ids.isNotEmpty() },
            read?.image,
            read?.reply,
        )
    }

    /** A found photo read for a question: the reply written from it, or the photo for the chat model. */
    private class Read(val image: String? = null, val reply: String? = null)

    /**
     * The best photo among [ids] (best first; videos skipped) read for [question]: answered by
     * [reader], or else as a file for the chat model to read, with what to do with it added to
     * [notes]; null when none can be read.
     */
    private suspend fun read(ids: List<Long>, videos: Set<Long>, question: String, notes: MutableList<String>): Read? {
        if (!mayRead()) {
            notes += "The phone is locked, so the photos can't be read now: tell the user to unlock it to see them and the answer."
            return null
        }
        val best = ids.firstOrNull { it !in videos } ?: return null
        val file = readable(best) ?: return null
        reader?.invoke(file, question)?.let { return Read(reply = it) }
        // Named first: given a plus-two question and the SSLC certificate, the model read out the
        // SSLC marks as the answer; said as "Your SSLC certificate shows…", the user can tell.
        notes += "The best-matching photo is attached. Say what document or photo it is, then answer \"$question\" from what it " +
            "shows, in a sentence or two, reading any numbers exactly. If it isn't what they asked about, or doesn't show the " +
            "answer, say so and that the photos shown below may help."
        return Read(image = file)
    }

    /** What [name] stands for, among towns with the user's photos when several share it. */
    private suspend fun area(name: String): Places.Area {
        val list = places ?: throw ToolError("Searching photos by place isn't available.")
        val (pending, canRead) = index.locating()
        if (!canRead && pending > 0) {
            throw ToolError("The app can't see where photos were taken yet. Tell the user to open Photos in the menu, which lets it.")
        }
        val taken = index.locations()
        return list.get().resolve(name) { points, radius ->
            points.filter { (lat, lon) -> taken.any { Places.km(it.first, it.second, lat, lon) <= radius } }
        } ?: throw ToolError("Couldn't find a place called $name. Ask the user for the nearest town, or the state or country.")
    }

    companion object {
        const val MAX_QUERY = 120

        /** Words that ask for videos rather than photos (declared before [FILLER], which takes them in). */
        private val VIDEO_WORDS = setOf("video", "videos", "clip", "clips", "footage", "recording", "recordings")

        /** Words that say "photos" or "all of them" rather than what's in them. */
        private val FILLER = setOf(
            "all", "every", "everything", "any", "photo", "photos", "picture", "pictures", "pic", "pics", "image", "images",
            "of", "the", "my", "me", "myself", "mine", "together", "with", "and", "show", "find",
        ) + VIDEO_WORDS

        /**
         * [userText] when it asks what something says ("what was my SSLC score?", "when does my
         * insurance expire") or asks for something printed on one ("fetch my pan number", "get my
         * sslc marks", "my DL no?"), for reading from the best photo; null for asking to see or
         * find photos ("show me…", "where is my PAN card?", "get my aadhaar card"). Without the
         * second kind, none of ten "fetch / get / give me … number" requests on the phone was
         * read (28 Oct 2026): the model passed no question for them.
         */
        fun questionIn(userText: String): String? {
            val text = userText.trim()
            return text.takeIf { QUESTION.containsMatchIn(it) || DETAIL.containsMatchIn(it) || NUMBER_NO.containsMatchIn(it) }?.take(MAX_QUERY)
        }

        private val QUESTION = Regex("^(what|what's|whats|which|when|how much|how many|tell me)\\b", RegexOption.IGNORE_CASE)

        /** Something printed on a document, not the document ("marks card", "number plate"). */
        private val DETAIL = Regex(
            "\\b(numbers?|marks|scores?|grades?|c?gpa|percentage|expiry|expires?|expired|validity|valid (?:till|until|upto|up to)|" +
                "date of birth|dob|blood group|ifsc)\\b(?!\\s*(?:card|sheet|list|memo|plate|certificate)s?\\b)",
            RegexOption.IGNORE_CASE,
        )

        /** "dl no", "pan no": a bare "no" only after what it numbers ("photos with no people" isn't one). */
        private val NUMBER_NO = Regex(
            "\\b(dl|pan|aadhaa?r|licen[cs]e|passport|card|account|a/c|policy|roll|reg(?:ister)?)\\s*no\\b",
            RegexOption.IGNORE_CASE,
        )

        /** "me, shiyas", "me and shiyas", "taslim & shiyas" as separate names. */
        fun people(text: String?): List<String> =
            text.orEmpty().split(Regex("\\s*(?:,|&|/|\\band\\b|\\bwith\\b)\\s*", RegexOption.IGNORE_CASE))
                .map { it.trim() }
                .filter { it.isNotEmpty() }

        /** [query] with a document's short name spelled out: "pan" → "PAN card", "dl" → "driving licence". */
        fun documentQuery(query: String): String =
            query.replace(Regex("\\bpan\\b(?!\\s*card)", RegexOption.IGNORE_CASE), "PAN card")
                .replace(Regex("\\bdl\\b", RegexOption.IGNORE_CASE), "driving licence")

        /** What the photos are of, without the filler; null when nothing is left. */
        fun description(query: String): String? =
            query.split(Regex("\\s+")).filter { it.lowercase().trim(',', '.', '!', '?') !in FILLER }.joinToString(" ").takeIf { it.isNotBlank() }
    }
}
