package com.local.assistant.gallery.faces

import kotlin.math.sqrt

/**
 * Who each face is, as far as the prints can tell.
 *
 * 1. Faces the user placed ([Face.confirmed]) stay where they are; they define the named people.
 * 2. Every other face goes to the named person it matches best, if that match reaches
 *    [PERSON_MATCH] and the user hasn't said the face isn't them.
 * 3. What's left is gathered into unnamed groups for the People screen, clearest faces first:
 *    each joins the group it matches best if that reaches [GROUP_MATCH], or starts a new one. A
 *    group of one is no group. Faces narrower than [MIN_SIDE] of the photo are left out of the
 *    groups: their prints are too unreliable to build a group on.
 *
 * "Matches" is always the mean similarity to the [NEIGHBOURS] closest faces of that person or
 * group, never to their average: people look different from one year, angle or haircut to the
 * next, and an average sits between people who look alike and pulls them all in.
 *
 * Set from the phone's own 2,199 faces (2026-10-25, `FaceCalibrationTest`): with groups formed by
 * running averages at SFace's published 0.363 and up to 0.55, the biggest groups mixed people who
 * share a look (glasses, sunglasses, children of an age, young men with beards). By closest faces
 * at 0.65 the groups looked like one person each, the same person sometimes split in two, which
 * naming both alike puts right. On those groups a face scored 0.64 or more (median 0.83) against
 * its own person and 0.48 at most (median 0.31) against the nearest other one, hence 0.55 for
 * matching a named person. Half the faces were under about 68 px across; under [MIN_SIDE] (about
 * 72 px) is where the mixing came from.
 *
 * Small faces were at first left out of matching named people too, and that is where most of a
 * person's photos went missing (2026-10-26, `FaceRecallTest`): of the faces scoring 0.55 or more
 * against the user's named faces but not placed, all 82 were small (9 under 4% of the photo),
 * and on a contact sheet nearly all were the user, scoring 0.39 at most against anyone else
 * named. Below 0.55, at any size, most were other people (0.50 to 0.55 mixed, 0.45 to 0.50
 * mostly children, women and other men), so the bar stays. Matching small faces too took the
 * user from 200 photos to 282, and two friends from 25 to 47 and 10 to 15, all of the 27 added
 * faces theirs on sight.
 */
object FaceGrouping {

    /** Raised whenever the rules change, so faces already found are placed again by the new ones. 2: small faces match named people. */
    const val VERSION = 2

    const val PERSON_MATCH = 0.55f

    const val GROUP_MATCH = 0.65f

    const val NEIGHBOURS = 3

    /** As a fraction of the photo's width or height, whichever is smaller for the face. */
    const val MIN_SIDE = 0.08f

    class Face(
        val id: Long,
        /** Unit length. */
        val print: FloatArray,
        val personId: Long?,
        val confirmed: Boolean,
        val notPersonId: Long?,
        /** How clear the face is; the clearest seed the groups. */
        val quality: Float,
        /** min(w, h) as fractions of the photo. */
        val side: Float = 1f,
    )

    data class Placement(val personId: Long?, val groupId: Long?)

    /** Where every face that isn't confirmed belongs now, by face id. */
    fun place(faces: List<Face>): Map<Long, Placement> {
        val confirmed = faces.filter { it.confirmed && it.personId != null }.groupBy { it.personId!! }
        val placements = HashMap<Long, Placement>()
        val loose = ArrayList<Face>()

        for (face in faces) {
            if (face.confirmed && face.personId != null) continue
            var bestPerson: Long? = null
            var bestScore = PERSON_MATCH
            for ((person, theirs) in confirmed) {
                if (person == face.notPersonId) continue
                val score = closest(face.print, theirs.map { it.print })
                if (score >= bestScore) {
                    bestScore = score
                    bestPerson = person
                }
            }
            when {
                bestPerson != null -> placements[face.id] = Placement(bestPerson, null)
                face.side < MIN_SIDE -> placements[face.id] = Placement(null, null)
                else -> loose += face
            }
        }

        for ((faceId, groupId) in groups(loose)) placements[faceId] = Placement(null, groupId)
        return placements
    }

    /** Mean similarity to the [NEIGHBOURS] closest of [prints]. */
    private fun closest(print: FloatArray, prints: List<FloatArray>): Float {
        val top = FloatArray(NEIGHBOURS) { -1f }
        var count = 0
        for (other in prints) {
            val s = FacePrints.similarity(print, other)
            count++
            // Keep the top few in descending order.
            var i = minOf(count, NEIGHBOURS) - 1
            if (count > NEIGHBOURS && s <= top[NEIGHBOURS - 1]) continue
            while (i > 0 && top[i - 1] < s) {
                top[i] = top[i - 1]
                i--
            }
            top[i] = s
        }
        val n = minOf(count, NEIGHBOURS)
        return if (n == 0) -1f else top.take(n).sum() / n
    }

    /** Face id to group id (the id of the face that started the group); groups of one get null. */
    private fun groups(faces: List<Face>): Map<Long, Long?> {
        val members = ArrayList<MutableList<Face>>()
        for (face in faces.sortedByDescending { it.quality }) {
            var best = -1
            var score = GROUP_MATCH
            for (g in members.indices) {
                val s = closest(face.print, members[g].map { it.print })
                if (s >= score) {
                    score = s
                    best = g
                }
            }
            if (best >= 0) members[best] += face else members += mutableListOf(face)
        }
        val result = HashMap<Long, Long?>()
        for (group in members) {
            val id = group.first().id.takeIf { group.size >= 2 }
            for (face in group) result[face.id] = id
        }
        return result
    }
}
