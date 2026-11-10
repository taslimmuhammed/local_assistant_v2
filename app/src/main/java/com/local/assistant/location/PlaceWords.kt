package com.local.assistant.location

import java.util.Locale

/**
 * The place a reminder is for, as the model passed it ("near DMart", "office", "DMart in
 * Kakkanad"), read for what kind of place it is. Only the words: whether a name is a town is
 * asked of [read]'s `isTown`, so this stays testable without the town list.
 */
sealed interface PlaceWords {

    /** "here", "this place": where the phone is now. */
    data object Here : PlaceWords

    /** "home", "office": one of the user's own places, found once and kept. [key] is home or office. */
    data class Own(val key: String) : PlaceWords

    /**
     * Anything else: a shop or a chain ("DMart"), an address, a town. [area] is a town named with
     * it ("DMart in Kakkanad"), which the search is centred on instead of the user.
     */
    data class Named(val what: String, val area: String?) : PlaceWords

    companion object {

        /** What [raw] names, or null when nothing is left once the filler words are gone. */
        fun read(raw: String, isTown: (String) -> Boolean): PlaceWords? {
            val text = strip(raw)
            if (text.isEmpty()) return null
            val lower = text.lowercase(Locale.ROOT)
            if (lower in HERE) return Here
            val own = lower.removePrefix("my ").removePrefix("our ").removePrefix("the ").trim()
            OWN[own]?.let { return Own(it) }

            // "DMart in Kakkanad", "DMart, Kakkanad", "DMart near Edappally": the last such split
            // whose second half is a town.
            for (match in AREA_SPLIT.findAll(text).toList().asReversed()) {
                val what = text.substring(0, match.range.first).trim()
                val area = text.substring(match.range.last + 1).trim()
                if (what.isNotEmpty() && area.isNotEmpty() && isTown(area)) return Named(what, area)
            }
            // "DMart Kakkanad": a town as the last word or two.
            val words = text.split(' ')
            for (n in 2 downTo 1) {
                if (words.size <= n) continue
                val area = words.takeLast(n).joinToString(" ")
                if (isTown(area)) return Named(words.dropLast(n).joinToString(" "), area)
            }
            return Named(text, null)
        }

        /**
         * Whether the user said [place] in [userText]: one of its words, spaces aside ("D Mart" is
         * DMart), or for home, office and here a word that means the same ("work" for office).
         */
        fun mentioned(place: String, userText: String): Boolean {
            if (userText.isBlank()) return true
            val said = userText.lowercase(Locale.ROOT)
            val squashed = said.filter(Char::isLetterOrDigit)
            val tokens = strip(place).lowercase(Locale.ROOT).split(NON_WORD).filter { it.length >= 2 }
            if (tokens.filter { it.length >= 3 }.ifEmpty { tokens }.any { it in squashed }) return true
            val saidWords = said.split(NON_WORD).toSet()
            return when (val kind = read(place) { false }) {
                Here -> "here" in saidWords
                is Own -> OWN.any { (word, key) -> key == kind.key && word in saidWords }
                else -> false
            }
        }

        /**
         * Whether [text], given as when something should ring, says arriving somewhere rather
         * than a time: "when I reach avilora", "before I reach Bangalore", "5 km before Munnar".
         * Gemma passed an alarm's place so (8 Nov 2026), as set_alarm's when.
         */
        fun isArrival(text: String): Boolean =
            (ARRIVAL.containsMatchIn(text) || DistanceWords.find(text) != null) && strip(text).any(Char::isLetter)

        /**
         * [raw] without the words around a place's name: "near the nearest DMart store" is "DMart
         * store", and "5 km before I reach Munnar" is "Munnar" (the distance is [DistanceWords]').
         */
        internal fun strip(raw: String): String {
            var text = DistanceWords.remove(raw).trim().trim('.', ',', '!', '?', '"', '\'').replace(SPACES, " ")
            while (true) {
                val shorter = text.replace(LEADING, "").replace(TRAILING, "").trim()
                if (shorter == text) return text
                text = shorter
            }
        }

        private val SPACES = Regex("\\s+")
        private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

        /** Said before a place, never part of it. */
        private val LEADING = Regex(
            "^(?:when i (?:reach|get to|arrive at)|when i(?:'m| am) at|i (?:reach|get to)|reaching|reach|get to|arrive at|" +
                "before|once|as soon as|on|" +
                "near to|nearby|near|close to|around|next to|at|to|in|the|any|a|an|some|nearest|closest|one of the)(?:\\s+|$)",
            RegexOption.IGNORE_CASE,
        )

        /** Arriving, as against a time: "when I reach", "get to", "near". */
        private val ARRIVAL = Regex("\\b(?:reach|reaching|reaches|get to|arrive|arriving|near|close to)\\b", RegexOption.IGNORE_CASE)

        /** Said after a place, never part of it. */
        private val TRAILING = Regex("\\s+(?:nearby|near me|close by|anywhere|branch|branches|outlet|outlets)$", RegexOption.IGNORE_CASE)

        private val AREA_SPLIT = Regex("\\s+(?:in|at|near)\\s+|\\s*,\\s*", RegexOption.IGNORE_CASE)

        private val HERE = setOf(
            "here", "this place", "this spot", "this location", "my location", "current location", "my current location",
            "where i am", "where i am now", "where i'm", "where i'm now", "right here",
        )

        private val OWN = mapOf(
            "home" to "home", "house" to "home", "place" to "home", "flat" to "home",
            "office" to "office", "work" to "office", "workplace" to "office",
        )
    }
}
