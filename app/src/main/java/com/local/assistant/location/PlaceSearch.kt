package com.local.assistant.location

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A point on the map. */
data class GeoPoint(val lat: Double, val lon: Double) {

    /** Metres to [other], along the ground. */
    fun metresTo(other: GeoPoint): Double {
        val p = Math.PI / 180
        val h = 0.5 - cos((other.lat - lat) * p) / 2 + cos(lat * p) * cos(other.lat * p) * (1 - cos((other.lon - lon) * p)) / 2
        return 12_742_000 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /**
     * This point to the nearest [step] degrees (0.05° is about 5 km): what a search is centred on,
     * so the map service learns the user's area, never where exactly they are.
     */
    fun rounded(step: Double = AREA_STEP): GeoPoint = GeoPoint(snap(lat, step), snap(lon, step))

    private fun snap(value: Double, step: Double): Double = (value / step).roundToInt() * step

    companion object {
        const val AREA_STEP = 0.05
    }
}

/** One place a map search found. */
data class FoundPlace(
    val name: String,
    val point: GeoPoint,
    /** How near counts as there: its size on the map, within [PlaceSearch.MIN_RADIUS_M] and [PlaceSearch.MAX_RADIUS_M]. */
    val radiusM: Int,
    /** Where it is, short: "Hoodi Main Road". */
    val label: String?,
    val kind: Kind = Kind.SPOT,
) {
    /** What it is on the map. */
    enum class Kind {
        /** A shop, a building, an address: what a chain has several of. */
        SPOT,

        /** A town, a village, a suburb: its middle is the town. */
        SETTLEMENT,

        /** The taluk or municipality round a town: its middle can be anywhere in it. */
        AREA,
    }
}

/** Looks a place's name up on a map, online. */
fun interface PlaceSearch {

    /**
     * Places called [query] within [spanDeg] degrees either side of [around], best match first.
     * Throws [PlaceSearchError] when the search can't be made.
     */
    suspend fun search(query: String, around: GeoPoint, spanDeg: Double): List<FoundPlace>

    companion object {
        /** A shop's own outline is tens of metres; a fix can be off by more, and a car passes quickly. */
        const val MIN_RADIUS_M = 250

        /** A suburb or a long road: beyond this, "there" stops meaning much. */
        const val MAX_RADIUS_M = 3_000

        /** How near counts as at a place [halfDiagonalM] metres across its middle. */
        fun radiusFor(halfDiagonalM: Double): Int = halfDiagonalM.roundToInt().coerceIn(MIN_RADIUS_M, MAX_RADIUS_M)
    }
}

/** A search that couldn't be made, said for the model to pass on. */
class PlaceSearchError(message: String) : Exception(message)
