package com.local.assistant.reminders

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.content.getSystemService
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.common.api.ApiException
import com.local.assistant.assist.KeepReadyService
import com.local.assistant.memory.db.TaskPlace

/**
 * Watches for the user arriving at a place reminder's spots, with the app closed: Android does
 * the watching, and wakes [PlaceReminderReceiver] on arrival. Geofences are gone after a reboot
 * (and from Play Services when location is turned off), so they are set again from the database
 * on boot and whenever the app comes to the front.
 */
interface PlaceFences {

    /** Starts watching [place]'s spots for reminder [taskId], replacing what it had. */
    fun watch(taskId: Long, place: TaskPlace)

    /** Stops watching reminder [taskId]'s spots; nothing to do if it had none. */
    fun stopWatching(taskId: Long)

    /** The reminders whose spots are watched, or wait to be until location is allowed. */
    fun watched(): Set<Long>

    companion object {
        /**
         * Play Services' geofencing where the phone has it: it watches with the least power,
         * from Wi-Fi, cell towers and the motion sensors, and turns to GPS only near a fence.
         * Elsewhere Android's own proximity alerts, which poll for a fix more often the nearer
         * the user is.
         */
        fun forPhone(context: Context): PlaceFences =
            if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS) {
                PlayGeofences(context)
            } else {
                ProximityFences(context)
            }

        /** Whether an arrival may come for some reminder, so the app must stay awake for it ([Watched]). */
        fun watching(context: Context): Boolean = allowed(context) && Watched(context).ids().isNotEmpty()

        /** Whether Android will tell this app of an arrival with the app closed. */
        fun allowed(context: Context): Boolean {
            fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
            return granted(Manifest.permission.ACCESS_FINE_LOCATION) &&
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
        }
    }
}

/**
 * How many spots each reminder has watched, so stopping one knows which fences to take down,
 * and a reminder at a time (most of them) costs nothing to cancel.
 *
 * Whether any are watched also keeps the app awake ([KeepReadyService]): with the screen off,
 * OxygenOS freezes a background app, and an arrival Play Services sent waited until the screen
 * came on (the user, 9 Nov 2026), so the alarm rang on a phone in use, as a banner.
 */
private class Watched(private val context: Context) {
    private val prefs = context.getSharedPreferences("place_fences", Context.MODE_PRIVATE)

    fun count(taskId: Long): Int? = prefs.getInt(taskId.toString(), -1).takeIf { it >= 0 }

    fun ids(): Set<Long> = prefs.all.keys.mapNotNull { it.toLongOrNull() }.toSet()

    fun set(taskId: Long, count: Int) {
        prefs.edit { putInt(taskId.toString(), count) }
        KeepReadyService.update(context)
    }

    fun remove(taskId: Long) {
        prefs.edit { remove(taskId.toString()) }
        KeepReadyService.update(context)
    }
}

/**
 * Play Services' geofences. [initialEnter] rings at once if the phone is already inside: only
 * for the on-device test, which can't walk anywhere; a reminder made where it rings ("remind me
 * when I'm back here") waits for the next arrival.
 */
class PlayGeofences(private val context: Context, private val initialEnter: Boolean = false) : PlaceFences {

    private val client by lazy { LocationServices.getGeofencingClient(context) }
    private val watched = Watched(context)
    private val lookout = PlaceLookout(context)

    @SuppressLint("MissingPermission")
    override fun watch(taskId: Long, place: TaskPlace) {
        val spots = place.spots
        // Fewer spots than before: the extra ones go.
        watched.count(taskId)?.takeIf { it > spots.size }?.let { before ->
            client.removeGeofences((spots.size until before).map { PlaceAlerts.fenceId(taskId, it) })
        }
        // Kept even when it can't be watched yet, so allowing location later starts it (see sync).
        watched.set(taskId, spots.size)
        if (!PlaceFences.allowed(context)) {
            Log.i(TAG, "Reminder $taskId waits for location all the time")
            return
        }
        val fences = spots.mapIndexed { i, spot ->
            Geofence.Builder()
                .setRequestId(PlaceAlerts.fenceId(taskId, i))
                .setCircularRegion(spot.lat, spot.lon, spot.radiusM.toFloat())
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
                .setNotificationResponsiveness(RESPONSIVENESS_MS)
                .build()
        }
        val request = GeofencingRequest.Builder()
            .setInitialTrigger(if (initialEnter) GeofencingRequest.INITIAL_TRIGGER_ENTER else 0)
            .addGeofences(fences)
            .build()
        try {
            client.addGeofences(request, PlaceReminderReceiver.fenceIntent(context))
                .addOnSuccessListener { Log.i(TAG, "Watching ${fences.size} spots near ${place.name} for reminder $taskId") }
                .addOnFailureListener { e ->
                    val code = (e as? ApiException)?.statusCode
                    Log.w(TAG, "Couldn't watch reminder $taskId: ${code?.let(GeofenceStatusCodes::getStatusCodeString) ?: e.message}")
                }
        } catch (e: SecurityException) {
            Log.w(TAG, "Location permission withdrawn; reminder $taskId not watched", e)
        }
        // The geofences alone wait for the screen to come on (see PlaceLookout).
        lookout.start()
    }

    override fun stopWatching(taskId: Long) {
        val count = watched.count(taskId) ?: return
        watched.remove(taskId)
        if (count > 0) client.removeGeofences((0 until count).map { PlaceAlerts.fenceId(taskId, it) })
        lookout.forget(taskId)
        if (watched.ids().isEmpty()) lookout.stop()
        Log.i(TAG, "Stopped watching reminder $taskId")
    }

    override fun watched(): Set<Long> = watched.ids()

    private companion object {
        const val TAG = "PlaceFences"

        /**
         * How late an arrival may be told. A minute keeps a drive past (a 500 m circle at 40 km/h
         * is 45 s) from being missed, while still letting Play Services batch its checks.
         */
        const val RESPONSIVENESS_MS = 60_000
    }
}

/** Android's own proximity alerts, for phones without Play Services: one per spot. */
class ProximityFences(private val context: Context) : PlaceFences {

    private val manager = context.getSystemService<LocationManager>()
    private val watched = Watched(context)

    @SuppressLint("MissingPermission")
    override fun watch(taskId: Long, place: TaskPlace) {
        val manager = manager ?: return
        watched.count(taskId)?.let { before -> (place.spots.size until before).forEach { manager.removeProximityAlert(intent(taskId, it)) } }
        watched.set(taskId, place.spots.size)
        if (!PlaceFences.allowed(context)) {
            Log.i(TAG, "Reminder $taskId waits for location all the time")
            return
        }
        try {
            place.spots.forEachIndexed { i, spot ->
                manager.addProximityAlert(spot.lat, spot.lon, spot.radiusM.toFloat(), NEVER, intent(taskId, i))
            }
            Log.i(TAG, "Watching ${place.spots.size} spots near ${place.name} for reminder $taskId (proximity alerts)")
        } catch (e: SecurityException) {
            Log.w(TAG, "Location permission withdrawn; reminder $taskId not watched", e)
        }
    }

    override fun stopWatching(taskId: Long) {
        val count = watched.count(taskId) ?: return
        watched.remove(taskId)
        (0 until count).forEach { manager?.removeProximityAlert(intent(taskId, it)) }
    }

    override fun watched(): Set<Long> = watched.ids()

    /** One intent per spot, told apart by its data; Android adds whether the phone is entering. */
    private fun intent(taskId: Long, spot: Int): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, PlaceReminderReceiver::class.java)
            .setAction(PlaceReminderReceiver.ACTION_NEAR)
            .setData(Uri.parse(PlaceAlerts.fenceId(taskId, spot))),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    private companion object {
        const val TAG = "PlaceFences"
        const val NEVER = -1L
    }
}
