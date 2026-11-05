package com.local.assistant.location

import com.local.assistant.gallery.places.Places
import com.local.assistant.memory.db.PlaceSpot
import com.local.assistant.memory.db.TaskPlace
import com.local.assistant.memory.tools.ToolError
import java.util.Locale

/** The phone's location, and the user's leave to use it. */
interface DeviceLocation {

    /** Precise location while the app is in use: what finding "here" or the nearest branch needs. */
    fun canLocate(): Boolean

    /** Asks for precise location, saying why first; true when given. */
    suspend fun askToLocate(): Boolean

    /** Location all the time: what ringing on arrival with the app closed needs (Android 10 and later). */
    fun canWatch(): Boolean

    /** Asks for location all the time, saying why first; true when given. */
    suspend fun askToWatch(): Boolean

    /** Where the phone is now; null when it can't tell (location off, no fix in time). */
    suspend fun current(): GeoPoint?
}

/** The user's home and office: what they said they are, and where that was found. */
interface OwnPlaces {

    /** What the user told the assistant their [key] (home, office) is: an address or an area. */
    suspend fun address(key: String): String?

    /** Where [address] was found for [key] before; null if it never was, or the address has changed since. */
    suspend fun spot(key: String, address: String): PlaceSpot?

    suspend fun keep(key: String, address: String, spot: PlaceSpot)
}

/** A place reminder's place, found: where it rings, and whether it can ring with the app closed. */
data class PlaceFinding(
    val place: TaskPlace,
    /** False without location all the time: saved, but it can't ring until that is allowed. */
    val canRing: Boolean,
    /** How far the nearest spot is from the phone, when the phone's location was needed anyway. */
    val nearestM: Double?,
    /** Where the phone was then, when it was needed anyway. */
    val here: GeoPoint? = null,
)

/**
 * Where "remind me to buy milk near DMart" rings. As much as possible is found on the phone:
 *
 * - "here": the phone's own fix.
 * - "home", "office": what the user said it is (a fact), looked up once and kept.
 * - a town ("when I reach Munnar"): the town list in the APK (`gallery/places`).
 * - a shop, a chain or an address: only a map knows those, so they're looked up online ([search],
 *   OpenStreetMap), sending the name and a box around the user's area (rounded, ~5 km) or the
 *   town they named, never the reminder or their exact location. A chain is its nearest few
 *   branches: "near DMart" means whichever one they pass. A town the APK's list lacks is the
 *   town alone, not also the taluk named after it.
 *
 * Anything it can't place comes back as a [ToolError] asking the user, never a guess.
 */
class PlaceFinder(
    private val search: PlaceSearch,
    private val towns: suspend () -> Places?,
    private val location: DeviceLocation,
    private val own: OwnPlaces,
) {

    /** Where [said] is. On the lock screen ([locked]) nothing is asked: the dialogs need the phone unlocked. */
    suspend fun find(said: String, locked: Boolean = false): PlaceFinding {
        val places = towns()
        val words = PlaceWords.read(said) { places?.town(it) != null }
            ?: throw ToolError("Which place should it remind them at? Ask the user.")
        if (!location.canLocate()) {
            if (locked) {
                throw ToolError(
                    "A place reminder needs location access, which can only be given with the phone unlocked. Nothing was saved: ask the user to unlock it and ask again.",
                )
            }
            if (!location.askToLocate()) {
                throw ToolError(
                    "The user didn't allow precise location, which a place reminder needs to know when they get there. Nothing was saved. Tell them they can allow it in Android's settings for this app, under Location, and ask again.",
                )
            }
        }
        val here = Here()
        val place = when (words) {
            PlaceWords.Here -> {
                val at = here.need()
                val town = places?.nearest(at.lat, at.lon)?.town
                TaskPlace(town?.let { "here ($it)" } ?: "here", listOf(PlaceSpot(at.lat, at.lon, PlaceSearch.MIN_RADIUS_M, town)))
            }
            is PlaceWords.Own -> TaskPlace(words.key, listOf(ownSpot(words.key, places, here)))
            is PlaceWords.Named -> {
                val town = places?.town(words.what)?.takeIf { words.area == null }
                if (town != null) {
                    // A town on its own: found on the phone.
                    TaskPlace(town.name, listOf(PlaceSpot(town.lat, town.lon, townRadius(town.population), town.name)))
                } else {
                    val area = words.area?.let { places?.town(it) }
                    val from = area?.let { GeoPoint(it.lat, it.lon) } ?: here.need()
                    val found = lookUp(words.what, from)
                    if (found.isEmpty()) {
                        val near = area?.name ?: "the user"
                        throw ToolError(
                            "I couldn't find '${words.what}' near $near. Nothing was saved. Ask the user for its address or the area it's in (\"${words.what} in <area>\").",
                        )
                    }
                    TaskPlace(words.what, pick(found, from).map { PlaceSpot(it.point.lat, it.point.lon, it.radiusM, it.label) })
                }
            }
        }
        val canRing = location.canWatch() || (!locked && location.askToWatch())
        val nearest = here.known?.let { at -> place.spots.minOf { GeoPoint(it.lat, it.lon).metresTo(at) } }
        return PlaceFinding(place, canRing, nearest, here.known)
    }

    /** Where the user's [key] is: kept from before, or looked up from what they said it is. */
    private suspend fun ownSpot(key: String, places: Places?, here: Here): PlaceSpot {
        val address = own.address(key) ?: throw ToolError(
            "I don't know where the user's $key is. Nothing was saved. Ask them for its address or area; when they say, save it " +
                "with save_fact (subject user, attribute $key) and call add_task again with place $key.",
        )
        own.spot(key, address)?.let { return it }
        val words = PlaceWords.read(address) { places?.town(it) != null } as? PlaceWords.Named
            ?: throw ToolError("'$address' isn't an address I can look up. Ask the user where their $key is.")
        val spot = places?.town(words.what)?.takeIf { words.area == null }
            ?.let { PlaceSpot(it.lat, it.lon, townRadius(it.population), it.name) }
            ?: run {
                val from = words.area?.let { places?.town(it) }?.let { GeoPoint(it.lat, it.lon) } ?: here.need()
                val best = lookUp(words.what, from).firstOrNull()
                    ?: throw ToolError(
                        "I couldn't find their $key, '$address', on the map. Nothing was saved. Ask the user for a fuller address or a landmark near it.",
                    )
                PlaceSpot(best.point.lat, best.point.lon, best.radiusM, best.label)
            }
        own.keep(key, address, spot)
        return spot
    }

    /** [query] around [from], in a city-sized box, then a wider one if that has none. */
    private suspend fun lookUp(query: String, from: GeoPoint): List<FoundPlace> {
        val around = from.rounded()
        for (span in SPANS) {
            val found = try {
                search.search(query, around, span)
            } catch (e: PlaceSearchError) {
                throw ToolError("${e.message} Nothing was saved; tell the user.")
            }
            if (found.isNotEmpty()) return found
        }
        return emptyList()
    }

    /** The phone's location, asked for at most once, and only when something needs it. */
    private inner class Here {
        var known: GeoPoint? = null
            private set

        suspend fun need(): GeoPoint = known ?: (location.current() ?: throw ToolError(NO_FIX)).also { known = it }
    }

    companion object {
        /** The most spots one reminder watches: a chain's nearest few branches. */
        const val MAX_SPOTS = 5

        /** Degrees either side of the user: a city (~33 km across), then a region (~110 km). */
        private val SPANS = listOf(0.15, 0.5)

        /** Two results this close are the same shop mapped twice (its outline and its pin). */
        private const val SAME_SPOT_M = 100.0

        private const val NO_FIX =
            "The phone couldn't tell where it is just now (location may be off). Nothing was saved. Ask the user to turn location on, or to say which area or town the place is in."

        /**
         * The places to ring at, of what the search found ([from] is where "near" is measured
         * from). Several shops with the best match's name are a chain: its nearest [MAX_SPOTS].
         * Otherwise the best match alone ("Lulu" is LuLu Mall, not every building named after it).
         * A town is one place: OpenStreetMap also has the taluk or municipality named after it,
         * whose middle can be far from the town (Thamarassery taluk's is 9 km into the hills), so
         * the town itself is where it rings.
         */
        fun pick(found: List<FoundPlace>, from: GeoPoint): List<FoundPlace> {
            val top = found.firstOrNull() ?: return emptyList()
            val named = found.filter { key(it.name) == key(top.name) }
            if (top.kind != FoundPlace.Kind.SPOT) {
                return listOf(named.firstOrNull { it.kind == FoundPlace.Kind.SETTLEMENT } ?: top)
            }
            val chain = named.filter { it.kind == FoundPlace.Kind.SPOT }
            if (chain.size < 2) return listOf(top)
            val chosen = mutableListOf<FoundPlace>()
            for (place in chain.sortedBy { it.point.metresTo(from) }) {
                if (chosen.none { it.point.metresTo(place.point) < SAME_SPOT_M }) chosen += place
                if (chosen.size == MAX_SPOTS) break
            }
            return chosen
        }

        /** Arriving in a town: the edge of a small one, further out for a city. */
        fun townRadius(population: Int): Int = when {
            population < 50_000 -> 2_000
            population < 1_000_000 -> 4_000
            else -> 8_000
        }

        private fun key(name: String) = name.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)
    }
}
