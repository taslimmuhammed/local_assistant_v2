package com.local.assistant.gallery.faces

/**
 * Which unnamed groups of faces a contact's photo names: [AUTO] and clear of the next group by
 * [AUTO_MARGIN] names the group outright; [SUGGEST] and clear by [SUGGEST_MARGIN] asks the user
 * ("Is this Rahul?"). Matched as [FaceGrouping] matches, by the mean of the closest faces.
 *
 * Set on the test phone (2026-11-02, `ContactFacesCalibrationTest`): its 107 contact photos are all
 * 96 px thumbnails, a face in about 70 of them. Against the 110 unnamed groups, three contacts stood
 * out at 0.65 to 0.67, 0.22 to 0.31 above their next group, and three more at 0.51 to 0.54, 0.08
 * to 0.15 above; the rest scored 0.46 at most. The one contact named like a named person matched
 * them at 0.61, but another, named differently, matched the user at 0.59: a thumbnail that close
 * to someone already named is left alone, at the bar for named people ([FaceGrouping.PERSON_MATCH]).
 */
object ContactMatching {

    const val AUTO = 0.62f
    const val AUTO_MARGIN = 0.15f
    const val SUGGEST = 0.50f
    const val SUGGEST_MARGIN = 0.07f

    class Contact(val key: String, val name: String, val print: FloatArray)

    class Person(val name: String, val contactKey: String?, val prints: List<FloatArray>)

    class Group(val id: Long, val faceIds: Set<Long>, val prints: List<FloatArray>)

    data class Match(val contactKey: String, val name: String, val groupId: Long, val score: Float, val auto: Boolean)

    /**
     * The groups [contacts] name, at most one contact a group and one group a contact. Not for a
     * contact already linked to someone, named like someone (the user named them), or whose face
     * is close to someone named; not for a group any of whose faces the user said isn't them
     * ([declined]: contact key to face ids).
     */
    fun matches(contacts: List<Contact>, people: List<Person>, groups: List<Group>, declined: Map<String, Set<Long>>): List<Match> {
        val linked = people.mapNotNull { it.contactKey }.toSet()
        val candidates = ArrayList<Match>()
        for (contact in contacts) {
            if (contact.key in linked) continue
            if (people.any { sameFirstName(it.name, contact.name) }) continue
            if (people.any { closest(contact.print, it.prints) >= FaceGrouping.PERSON_MATCH }) continue
            val notThem = declined[contact.key].orEmpty()
            val scored = groups.filter { g -> g.faceIds.none { it in notThem } }
                .map { it to closest(contact.print, it.prints) }
                .sortedByDescending { it.second }
            val (group, best) = scored.firstOrNull() ?: continue
            val margin = best - (scored.getOrNull(1)?.second ?: 0f)
            val auto = best >= AUTO && margin >= AUTO_MARGIN
            if (auto || (best >= SUGGEST && margin >= SUGGEST_MARGIN)) {
                candidates += Match(contact.key, contact.name.trim(), group.id, best, auto)
            }
        }
        // Two contacts for one group: the closer one has it.
        return candidates.groupBy { it.groupId }.values.map { rivals -> rivals.maxBy { it.score } }.sortedByDescending { it.score }
    }

    /** "Shiyas" and "Shiyas K": the user may have named the person before the contact was read. */
    fun sameFirstName(a: String, b: String): Boolean {
        val x = a.trim().lowercase().split(Regex("\\s+")).first()
        val y = b.trim().lowercase().split(Regex("\\s+")).first()
        return x.isNotEmpty() && x == y
    }

    /** Mean similarity to the [FaceGrouping.NEIGHBOURS] closest of [prints]. */
    private fun closest(print: FloatArray, prints: List<FloatArray>): Float {
        if (prints.isEmpty()) return -1f
        return prints.map { FacePrints.similarity(print, it) }.sortedDescending().take(FaceGrouping.NEIGHBOURS).average().toFloat()
    }
}
