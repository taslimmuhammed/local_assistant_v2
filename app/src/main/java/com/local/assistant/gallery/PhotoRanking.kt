package com.local.assistant.gallery

import com.local.assistant.memory.embed.Int8Vectors

data class PhotoHit(val mediaId: Long, val similarity: Float)

/**
 * Which photos answer a query, best first.
 *
 * Set from the phone's own 2,428 photos (2026-10-25, `GalleryCalibrationTest`): for "beach",
 * "sunset", "food", "mountains" and "car", the top 100 were looked at and the right ones marked.
 * What separated them was distance from the query's own best photo. Where the matches ended sat
 * about 0.035 below the top score for every one of these queries, while the similarity at that
 * point ranged from 0.70 to 0.75 between them, so a fixed similarity would be too strict for one
 * query and too loose for the next. Kept within [MATCH_MARGIN] of the best: beach 11 photos (91%
 * right), sunset 30 (93%), food 30 (97%), mountains 27 (100%). Car was 38% right, mostly street
 * scenes with a car somewhere in them. A cutoff in standard deviations above the query's mean (the
 * first version) let in hundreds of near misses, and cut common subjects like "selfie" to a few.
 *
 * Anything has a best photo, so a query must also reach [MIN_BEST] to match at all. That floor is
 * set low on purpose: wording moves every score together, by as much as 0.035 ("beach" topped
 * out at 0.762, "beaches" at 0.727, on the same photos first), and a search that finds nothing
 * when the photos are there is worse than one that offers the nearest thing. "skiing", on a
 * phone with snowy mountains but no skiing, topped out at 0.732 and shows those. The tool asks
 * the model for a short singular query, the wording that scored highest.
 */
object PhotoRanking {

    /** How far below the best photo a photo may score and still be listed. */
    const val MATCH_MARGIN = 0.04f

    /** The best photo must reach this, or nothing matches. */
    const val MIN_BEST = 0.72f

    const val MAX_RESULTS = 2_000

    internal fun scores(query: ByteArray, vectors: ByteArray, norms: IntArray, size: Int): FloatArray? {
        if (size == 0) return null
        val queryNorm = Int8Vectors.squaredNorm(query)
        if (queryNorm == 0) return null
        val dims = Int8Vectors.DIMENSIONS
        return FloatArray(size) { i ->
            Int8Vectors.similarity(Int8Vectors.cosineDistance(Int8Vectors.dot(vectors, i * dims, query), norms[i], queryNorm))
        }
    }

    /**
     * The matches, best first. [ids] and [vectors] side by side: vector i is bytes
     * `i * DIMENSIONS until (i + 1) * DIMENSIONS` of [vectors], with its squared norm at [norms] i.
     */
    fun rank(query: ByteArray, ids: LongArray, vectors: ByteArray, norms: IntArray, size: Int = ids.size): List<PhotoHit> {
        val scores = scores(query, vectors, norms, size) ?: return emptyList()
        val cutoff = cutoff(scores) ?: return emptyList()
        val hits = ArrayList<PhotoHit>()
        for (i in 0 until size) if (scores[i] >= cutoff) hits += PhotoHit(ids[i], scores[i])
        hits.sortWith(compareByDescending<PhotoHit> { it.similarity }.thenBy { it.mediaId })
        return if (hits.size > MAX_RESULTS) hits.subList(0, MAX_RESULTS) else hits
    }

    /** The lowest similarity that still counts for this query, or null when nothing matches. */
    fun cutoff(scores: FloatArray): Float? {
        val best = scores.maxOrNull() ?: return null
        return if (best < MIN_BEST) null else best - MATCH_MARGIN
    }
}
