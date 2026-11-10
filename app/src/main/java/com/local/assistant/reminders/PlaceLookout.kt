package com.local.assistant.reminders

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.location.Location
import android.util.Log
import androidx.core.content.edit
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.local.assistant.location.GeoPoint
import com.local.assistant.memory.db.PlaceSpot

/**
 * Looks for the user itself while a place reminder is set, as often as how near they are calls
 * for, and tells [PlaceReminderReceiver] each fix ([PlaceReminderReceiver.ACTION_LOOK]).
 *
 * Play Services' geofences alone missed arrivals with the screen off (the user, 9 Nov 2026).
 * With nothing else asking for location, the phone made a Wi-Fi fix every 2–5 minutes and no GPS
 * at all, and "Have food" near Thamarassery was told only after the screen came on and Maps
 * turned GPS on (13:19:44; it rang at 13:20:03). Asking for fixes gives the geofences them too,
 * as every fix the phone makes goes to them, and each one is also checked here ([PlacePace.look]).
 */
class PlaceLookout(private val context: Context) {

    private val client by lazy { LocationServices.getFusedLocationProviderClient(context) }
    private val prefs = context.getSharedPreferences("place_lookout", Context.MODE_PRIVATE)

    /** Starts looking, at the pace it had, or at [PlacePace.FIRST] until the first fix says how far. */
    fun start() = request(pace() ?: PlacePace.FIRST)

    fun stop() {
        client.removeLocationUpdates(intent(context))
        prefs.edit { clear() }
        Log.i(TAG, "Stopped looking")
    }

    /** Looks at [pace] from now on, if that's a change. */
    fun keepTo(pace: PlacePace.Pace) {
        if (pace != pace()) request(pace)
    }

    /** The fix before this one, to tell if the user has moved since. */
    fun previous(): PlacePace.Fix? {
        if (!prefs.contains(LAT)) return null
        return PlacePace.Fix(
            GeoPoint(prefs.getFloat(LAT, 0f).toDouble(), prefs.getFloat(LON, 0f).toDouble()),
            accuracyM = prefs.getFloat(ACCURACY, 0f).toDouble(),
            speedMps = null,
            atMs = prefs.getLong(AT, 0),
        )
    }

    fun remember(fix: PlacePace.Fix) = prefs.edit {
        putFloat(LAT, fix.point.lat.toFloat())
        putFloat(LON, fix.point.lon.toFloat())
        putFloat(ACCURACY, fix.accuracyM.toFloat())
        putLong(AT, fix.atMs)
    }

    /** Whether reminder [taskId] has been seen clearly outside all its circles, at these [spots]. */
    fun seenOutside(taskId: Long, spots: List<PlaceSpot>): Boolean = prefs.getBoolean(outsideKey(taskId, spots), false)

    fun markOutside(taskId: Long, spots: List<PlaceSpot>) = prefs.edit { putBoolean(outsideKey(taskId, spots), true) }

    /** Forgets reminder [taskId]: it rang, or stopped being watched. */
    fun forget(taskId: Long) = prefs.edit { prefs.all.keys.filter { it.startsWith("$OUTSIDE$taskId:") }.forEach(::remove) }

    private fun pace(): PlacePace.Pace? =
        if (prefs.contains(EVERY)) PlacePace.Pace(prefs.getLong(EVERY, 0), prefs.getBoolean(PRECISE, false)) else null

    @SuppressLint("MissingPermission")
    private fun request(pace: PlacePace.Pace) {
        if (!PlaceFences.allowed(context)) return
        val request = LocationRequest.Builder(
            if (pace.precise) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            pace.everyMs,
        )
            // Fixes others asked for come too, up to this often: free, and the first after the
            // user starts moving often comes this way (Play Services looks on an activity change).
            .setMinUpdateIntervalMillis(PlacePace.STEPS_MS.first())
            .build()
        try {
            client.requestLocationUpdates(request, intent(context))
                .addOnSuccessListener {
                    prefs.edit {
                        putLong(EVERY, pace.everyMs)
                        putBoolean(PRECISE, pace.precise)
                    }
                    Log.i(TAG, "Looking every ${pace.everyMs / 1000} s${if (pace.precise) ", with GPS" else ""}")
                }
                .addOnFailureListener { Log.w(TAG, "Couldn't look for the user", it) }
        } catch (e: SecurityException) {
            Log.w(TAG, "Location permission withdrawn; not looking", e)
        }
    }

    private fun outsideKey(taskId: Long, spots: List<PlaceSpot>) = "$OUTSIDE$taskId:${spots.hashCode()}"

    companion object {
        private const val TAG = "PlaceLookout"
        private const val EVERY = "every_ms"
        private const val PRECISE = "precise"
        private const val LAT = "lat"
        private const val LON = "lon"
        private const val ACCURACY = "accuracy"
        private const val AT = "at"
        private const val OUTSIDE = "outside:"

        fun intent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            1,
            Intent(context, PlaceReminderReceiver::class.java).setAction(PlaceReminderReceiver.ACTION_LOOK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

        fun fixOf(location: Location) = PlacePace.Fix(
            GeoPoint(location.latitude, location.longitude),
            accuracyM = if (location.hasAccuracy()) location.accuracy.toDouble() else PlacePace.UNKNOWN_ACCURACY_M,
            speedMps = if (location.hasSpeed()) location.speed.toDouble() else null,
            atMs = location.time,
        )
    }
}

/**
 * What a fix says, and how soon to look again: the rules [PlaceLookout] follows, kept apart so
 * they can be tested.
 */
object PlacePace {

    /** How often to look, and whether with GPS. */
    data class Pace(val everyMs: Long, val precise: Boolean)

    /** Where the user was at [atMs], give or take [accuracyM]; [speedMps] when the fix knows it (GPS). */
    data class Fix(val point: GeoPoint, val accuracyM: Double, val speedMps: Double?, val atMs: Long)

    /** What a fix says of one reminder. */
    sealed interface Look {
        /** Clearly inside spot [spot]'s circle, having been seen outside: ring. */
        data class Arrived(val spot: Int) : Look

        /** Clearly outside every circle: the next clear "inside" is an arrival. */
        data object Outside : Look

        /** Inside but never seen outside (it was made here), or too unsure to say. */
        data object Neither : Look
    }

    /** Assumed top speed, on the way to a place: 100 km/h. */
    const val TOP_SPEED_MPS = 100 / 3.6

    /** Within this of a circle, look with GPS: Wi-Fi and cell fixes are off by hundreds of metres. */
    const val PRECISE_WITHIN_M = 3_000.0

    /** How often it may look, from the nearest to the farthest. */
    val STEPS_MS = listOf(30_000L, 60_000L, 2 * 60_000L, 5 * 60_000L, 10 * 60_000L)

    /** Still: no need for GPS, nor to look often; a fix when the user moves off comes anyway. */
    val STILL = Pace(5 * 60_000L, precise = false)

    /** Before the first fix says how far the nearest place is. */
    val FIRST = Pace(60_000L, precise = false)

    /** Below this speed (m/s), a GPS fix is of someone standing, or sitting. */
    const val STILL_SPEED_MPS = 1.0

    /** Fixes this near each other, each sure to within this, are the user not moving. */
    const val STILL_M = 200.0

    /** A fix without an accuracy: assume a cell tower's. */
    const val UNKNOWN_ACCURACY_M = 2_000.0

    /**
     * What [fix] says of a reminder at [spots]. Ringing needs the fix clearly inside a circle
     * (all of its error within), after one clearly outside them all: a reminder made where it
     * rings ("when I'm back here") waits for the next arrival, as its geofences do.
     */
    fun look(fix: Fix, spots: List<PlaceSpot>, seenOutside: Boolean): Look {
        val metres = spots.map { fix.point.metresTo(GeoPoint(it.lat, it.lon)) }
        val inside = spots.indices.firstOrNull { metres[it] + fix.accuracyM <= spots[it].radiusM }
        return when {
            inside != null -> if (seenOutside) Look.Arrived(inside) else Look.Neither
            spots.indices.all { metres[it] - fix.accuracyM > spots[it].radiusM } -> Look.Outside
            else -> Look.Neither
        }
    }

    /** Metres from [fix] to the nearest edge of any of [spots]' circles, at the nearest its error allows; 0 inside. */
    fun toEdge(fix: Fix, spots: List<PlaceSpot>): Double =
        spots.minOfOrNull { (fix.point.metresTo(GeoPoint(it.lat, it.lon)) - it.radiusM - fix.accuracyM).coerceAtLeast(0.0) }
            ?: Double.MAX_VALUE

    /**
     * Whether the user is still: two fixes, both sure to within [STILL_M], less than that apart
     * and slower than [STILL_SPEED_MPS] between them, and the newer one, if GPS, slower too. A
     * car stopped at the lights reads 0 km/h, but was far from the fix before. Unsure (cell
     * fixes) is moving: looking too hard costs battery, but too little misses the place.
     */
    fun still(previous: Fix?, now: Fix): Boolean {
        if ((now.speedMps ?: 0.0) >= STILL_SPEED_MPS) return false
        if (previous == null || previous.accuracyM > STILL_M || now.accuracyM > STILL_M) return false
        val moved = previous.point.metresTo(now.point)
        val seconds = (now.atMs - previous.atMs) / 1000.0
        return moved < STILL_M && seconds > 0 && moved / seconds < STILL_SPEED_MPS
    }

    /**
     * How to look next, [edgeM] from the nearest circle: often enough that at [TOP_SPEED_MPS]
     * the user can't reach it between two looks, and with GPS for the last [PRECISE_WITHIN_M].
     * 30 km out that's every 10 minutes on Wi-Fi and cell towers; 1 km out, every 30 s with GPS.
     */
    fun next(edgeM: Double, still: Boolean): Pace {
        val reach = (edgeM / TOP_SPEED_MPS * 1000).toLong()
        val every = STEPS_MS.lastOrNull { it <= reach } ?: STEPS_MS.first()
        if (still) return Pace(maxOf(every, STILL.everyMs), precise = false)
        return Pace(every, precise = edgeM < PRECISE_WITHIN_M)
    }
}
