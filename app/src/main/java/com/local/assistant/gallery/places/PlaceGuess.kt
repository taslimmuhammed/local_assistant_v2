package com.local.assistant.gallery.places

/**
 * Where a photo with no GPS tags was probably taken, from the photos around it in time that have
 * them, as Google Photos estimates locations. Most photos have none (the camera's location setting
 * off, or copied and received photos stripped of it): 125 of 2,680 on the test phone.
 *
 * A photo takes the place of the nearer of the located photos just before and after it when those
 * two are within [SPAN_MS] and [NEAR_KM] of each other (the user stayed around there), or else of
 * one taken within [ALONE_MS] of it. On the test phone (2026-10-26) that placed 105 more photos;
 * applied to the located photos themselves, each in turn treated as unlocated, all 90 it placed
 * landed within 15 km of where they were, most at the same spot. A wider span (24 hours) placed
 * 264, but most of them in one town, from a single pair a day apart.
 *
 * Photos stamped by the second in bunches ([BULK] or more at once) are left alone: those are
 * copies dated by when they arrived, not when they were taken.
 */
object PlaceGuess {

    const val SPAN_MS = 12 * 3_600_000L
    const val NEAR_KM = 25.0
    const val ALONE_MS = 60 * 60_000L
    const val BULK = 4

    /** A photo with its own location. */
    data class Fix(val mediaId: Long, val takenAt: Long, val lat: Double, val lon: Double)

    /** For each of [unplaced] (id to when taken), the located photo whose place it takes; absent when none. */
    fun guess(unplaced: Map<Long, Long>, located: List<Fix>, everyTakenAt: Collection<Long>): Map<Long, Fix> {
        if (located.isEmpty()) return emptyMap()
        val sorted = located.sortedBy { it.takenAt }
        val times = LongArray(sorted.size) { sorted[it].takenAt }
        val perSecond = everyTakenAt.groupingBy { it / 1000 }.eachCount()
        val result = HashMap<Long, Fix>()
        for ((mediaId, at) in unplaced) {
            if ((perSecond[at / 1000] ?: 0) >= BULK) continue
            // The first located photo at or after it.
            val i = times.binarySearch(at).let { if (it < 0) -it - 1 else it }
            val before = sorted.getOrNull(i - 1)
            val after = sorted.getOrNull(i)
            val nearer = listOfNotNull(before, after).minByOrNull { kotlin.math.abs(it.takenAt - at) } ?: continue
            val stayed = before != null && after != null && after.takenAt - before.takenAt <= SPAN_MS &&
                Places.km(before.lat, before.lon, after.lat, after.lon) <= NEAR_KM
            if (stayed || kotlin.math.abs(nearer.takenAt - at) <= ALONE_MS) result[mediaId] = nearer
        }
        return result
    }
}
