package com.local.assistant.location

import com.local.assistant.memory.db.PlaceDistance
import com.local.assistant.memory.db.TaskPlace
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.log2
import kotlin.math.sin

// A place reminder as it's being changed on the Reminders screen, before it is saved: where its
// pins are and how far out it rings. Its name stays: a new place is a new reminder.

/**
 * [this] ringing [reach] out: a circle round each pin, or for "5 km before Munnar" each pin's own
 * size plus the distance. Sizes that come out as [original]'s keep its wording ("near DMart").
 */
fun TaskPlace.resized(reach: PlaceDistance, original: TaskPlace): TaskPlace {
    val spots = spots.map { spot -> spot.copy(radiusM = reach.radiusFor(distance?.ownRadius(spot.radiusM) ?: spot.radiusM)) }
    val same = spots.map { it.radiusM } == original.spots.map { it.radiusM }
    return copy(spots = spots, distance = if (same) original.distance else reach)
}

/** [this] at [original]'s sizes and wording again, wherever its pins now are. */
fun TaskPlace.sizedAs(original: TaskPlace): TaskPlace =
    copy(spots = spots.zip(original.spots) { now, was -> now.copy(radiusM = was.radiusM) }, distance = original.distance)

/** [this] with pin [index] at [to]. */
fun TaskPlace.movedTo(index: Int, to: GeoPoint): TaskPlace =
    copy(spots = spots.mapIndexed { i, spot -> if (i == index) spot.copy(lat = to.lat, lon = to.lon) else spot })

/** [this] ringing at pin [index] alone: one place of several it watched (a town and its taluk, or one branch). */
fun TaskPlace.onlyAt(index: Int): TaskPlace = copy(spots = listOf(spots[index]))

/** How far out it rings, as one number: the distance said, or the circle its pins share; null when their sizes differ. */
fun TaskPlace.ringMetres(): Int? =
    distance?.takeIf { it.beforeArriving }?.metres ?: spots.map { it.radiusM }.distinct().singleOrNull()

/** [points] points round a circle of [radiusM] about this point, for drawing it; the first comes again last. */
fun GeoPoint.circle(radiusM: Double, points: Int = 64): List<GeoPoint> {
    val reach = radiusM / EARTH_RADIUS_M
    val lat1 = Math.toRadians(lat)
    val lon1 = Math.toRadians(lon)
    return (0..points).map { i ->
        val bearing = 2 * PI * i / points
        val lat2 = asin(sin(lat1) * cos(reach) + cos(lat1) * sin(reach) * cos(bearing))
        val lon2 = lon1 + atan2(sin(bearing) * sin(reach) * cos(lat1), cos(reach) - sin(lat1) * sin(lat2))
        GeoPoint(Math.toDegrees(lat2), Math.toDegrees(lon2))
    }
}

/**
 * The map zoom at which a circle of [radiusM] here fits a box whose shorter side is [sizeDp], with
 * a margin round it. A map at zoom 0 is 512 dp round the equator, and each step halves the metres a dp shows.
 */
fun GeoPoint.zoomToShow(radiusM: Double, sizeDp: Float): Double {
    val metresPerDpAtZoom0 = 2 * PI * EARTH_RADIUS_M * cos(Math.toRadians(lat)) / 512
    return log2(metresPerDpAtZoom0 * sizeDp / (radiusM * 2 * 1.3)).coerceIn(2.0, 18.0)
}

private const val EARTH_RADIUS_M = 6_371_000.0
