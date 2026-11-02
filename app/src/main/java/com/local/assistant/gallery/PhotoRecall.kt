package com.local.assistant.gallery

import android.util.Log
import com.local.assistant.memory.prompt.PhotoLine
import com.local.assistant.memory.prompt.PhotoSource
import com.local.assistant.memory.prompt.RecalledPhotos
import com.local.assistant.memory.retrieval.QueryText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Photos from the gallery sent with a question, as recalled snippets are: the model didn't think
 * to look ("did I ever participate in push to prod hackathon?" got a memory search and "I don't
 * have any record", 30 Oct 2026, while Photos found the certificate for "push to prod").
 *
 * The question itself is the search, whole, so a photo goes to the model only when it clearly
 * stands out ([passes]). Up to two, the second only as close to the first as search results are
 * ([PhotoRanking.MATCH_MARGIN]); videos aren't sent. Not for an ID number (PAN, card, licence…),
 * which find_photos reads and checks. The closest photos are shown under the reply either way.
 */
class PhotoRecall(
    private val index: GalleryIndex,
    private val library: PhotoLibrary,
    private val ready: () -> Boolean,
    private val cacheDir: File,
) : PhotoSource {

    override suspend fun photosFor(text: String, max: Int, inConversation: Set<Long>): RecalledPhotos {
        if (!ready() || QueryText.isTrivial(text) || QueryText.isSmallTalk(text)) return RecalledPhotos.NONE
        val best = index.bestPhotos(text.take(MAX_QUERY), COMPARED) ?: return RecalledPhotos.NONE
        val top = best.firstOrNull()?.similarity ?: return RecalledPhotos.NONE
        // Shown more readily than sent ([SHOWN_FROM]): the answer may be in one the model wasn't
        // sent, or one the user recognises. As close to the best as search lists them.
        val close = best.filter { it.similarity >= top - PhotoRanking.MATCH_MARGIN }
        val sends = passes(best.map { it.similarity })
        val related = if (sends || top >= SHOWN_FROM) close.map { it.mediaId } else emptyList()
        // An ID number is left to find_photos, whose reader copies it on its own and checks it:
        // read off a photo inside the chat, the PAN came out wrong in one run of two (`PhotoReader`).
        if (max <= 0 || PhotoReader.Field.of(text) != null || !sends) return RecalledPhotos(related = related)
        val chosen = close.take(max)
        Log.i(TAG, "${chosen.size} for a message: best %.3f, 10th %.3f".format(top, best.getOrNull(COMPARED - 1)?.similarity ?: 0f))
        val taken = withContext(Dispatchers.IO) { library.datesTaken(chosen.map { it.mediaId }) }
        val sent = withContext(Dispatchers.IO) {
            chosen.mapIndexedNotNull { i, hit ->
                if (hit.mediaId in inConversation) return@mapIndexedNotNull PhotoLine(hit.mediaId, "", taken[hit.mediaId], attached = false)
                // One file per place, replaced by the next message's: the model reads it as the message is sent.
                val bytes = library.jpeg(hit.mediaId, PIXELS) ?: return@mapIndexedNotNull null
                val file = File(cacheDir, "recalled-photo-$i.jpg").apply { writeBytes(bytes) }
                PhotoLine(hit.mediaId, file.absolutePath, taken[hit.mediaId])
            }
        }
        return RecalledPhotos(sent, related)
    }

    companion object {
        private const val TAG = "PhotoRecall"

        /** How many photos the best is measured against: the 10th is the [STANDS_OUT] reference. */
        const val COMPARED = 10

        /**
         * The best photo's score from which the closest photos are shown under the reply (the
         * "Related photos" card): lower than what sends them to the model ([CLOSE]), so they show
         * more often. Raise it for fewer cards, lower it for more. On the phone's photos (30 Oct
         * 2026), at 0.75 the card showed for 11 of 16 questions the photos answer and 2 of 30
         * general ones; at 0.74 for 12 and 4; at 0.72 (the search's own floor) for 15 and 6.
         */
        const val SHOWN_FROM = 0.75f

        /** A best photo this close to the question goes, however close the next ones are. */
        const val CLOSE = 0.80f

        /** A best photo this far above the 10th goes too: it stands out from the rest. */
        const val STANDS_OUT = 0.065f

        /** Longest side of a photo sent: as a found photo is read (`PhotoTools`). */
        const val PIXELS = 1024

        const val MAX_QUERY = 200

        /**
         * Whether the best of [scores] (best first, [COMPARED] of them) is close enough to the
         * question to send. Set on the phone's 2,685 photos (`PhotoRecallCalibrationTest`, 30 Oct
         * 2026): of 30 general questions ("what is the capital of France?", "tell me a joke") the
         * best photo scored at most 0.777 and stood at most 0.056 above the 10th; of 16 questions
         * the photos hold, 7 passed, both of the user's among them (0.809 and 0.840, 0.088 and
         * 0.171 above the 10th). A bar on the score alone took 5 of them; a whole question scores
         * differently from a short search, so the search's own floor (0.72) let in 6 of the 30.
         */
        fun passes(scores: List<Float>): Boolean {
            val best = scores.firstOrNull() ?: return false
            if (best < PhotoRanking.MIN_BEST) return false
            if (best >= CLOSE) return true
            val tenth = scores.getOrNull(COMPARED - 1) ?: return false
            return best - tenth >= STANDS_OUT
        }
    }
}
