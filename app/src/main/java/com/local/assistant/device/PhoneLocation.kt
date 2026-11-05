package com.local.assistant.device

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.getSystemService
import com.local.assistant.location.DeviceLocation
import com.local.assistant.location.GeoPoint
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * The phone's location for place reminders, from Android's own location service; the fix stays
 * in the app. Asked for the first time a place reminder is made: precise location while in use
 * (to find "here" and the nearest branch), then all the time (to ring with the app closed),
 * each after a word on why.
 */
class PhoneLocation(private val context: Context, private val permissions: PermissionBroker) : DeviceLocation {

    private val manager = context.getSystemService<LocationManager>()

    override fun canLocate(): Boolean = permissions.granted(Manifest.permission.ACCESS_FINE_LOCATION)

    // The explanation is read before Android's dialog, inside the same wait: 60 s ran out on the phone.
    override suspend fun askToLocate(): Boolean =
        permissions.request(setOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), LOCATE_WHY, longWait = true)

    override fun canWatch(): Boolean =
        canLocate() && (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || permissions.granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION))

    override suspend fun askToWatch(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return canLocate()
        return permissions.request(setOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), WATCH_WHY, longWait = true)
    }

    /** A fix from the last few minutes if there is one, else a fresh one, waited for up to [FIX_WAIT_MS]. */
    @SuppressLint("MissingPermission")
    override suspend fun current(): GeoPoint? {
        val manager = manager ?: return null
        if (!canLocate()) return null
        val providers = manager.getProviders(true)
        val known = providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
        val now = System.currentTimeMillis()
        known.filter { now - it.time < FRESH_MS }.minByOrNull { it.accuracy }?.let { return it.point() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            for (provider in PREFERRED.filter { it in providers }) {
                fresh(manager, provider)?.let { return it.point() }
            }
        }
        // Better an older fix than none: the search is by area, and a fence is set where the place is.
        return known.filter { now - it.time < STALE_MS }.maxByOrNull { it.time }?.point()
    }

    @SuppressLint("MissingPermission")
    private suspend fun fresh(manager: LocationManager, provider: String): Location? = withTimeoutOrNull(FIX_WAIT_MS) {
        suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            try {
                manager.getCurrentLocation(provider, signal, context.mainExecutor) { location -> continuation.resume(location) }
            } catch (e: SecurityException) {
                continuation.resume(null)
            }
        }
    }

    private fun Location.point() = GeoPoint(latitude, longitude)

    private companion object {
        const val FRESH_MS = 5 * 60_000L
        const val STALE_MS = 60 * 60_000L
        const val FIX_WAIT_MS = 15_000L

        /** Play Services' fused provider where there is one (Android 12+), then the network's quick fix, then GPS. */
        val PREFERRED = listOf("fused", LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)

        val LOCATE_WHY = PermissionBroker.Rationale(
            "Location for place reminders",
            "To remind you when you reach a place, the app needs your precise location. It is used on this phone " +
                "only. To find a shop like DMart, just its name and your rough area (about 5 km) are looked up on " +
                "OpenStreetMap's map; the reminder and your exact location are never sent.",
        )

        val WATCH_WHY = PermissionBroker.Rationale(
            "Ring when you arrive",
            "So the reminder can ring when you get there with the app closed, choose \"Allow all the time\" on the " +
                "next screen. Android watches for the places on the phone; this app sends nothing.",
        )
    }
}
