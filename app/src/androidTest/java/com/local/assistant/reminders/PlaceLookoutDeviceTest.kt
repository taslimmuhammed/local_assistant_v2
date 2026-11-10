package com.local.assistant.reminders

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.local.assistant.appContainer
import com.local.assistant.device.PhoneLocation
import com.local.assistant.location.GeoPoint
import com.local.assistant.memory.db.PlaceSpot
import com.local.assistant.memory.db.TaskEntity
import com.local.assistant.memory.db.TaskPlace
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Sets up, or clears, a place reminder for watching how [PlaceLookout] looks for the user with
 * the screen off; the looking itself happens after this run, in the app as it is used.
 *
 * `-e lookout set` makes a reminder for a small circle 1.5 km north of the phone, so a phone
 * left on a desk is near one but never arrives: it should look with GPS until it finds itself
 * still, then every 5 minutes without. Reinstall the app afterwards (an instrument run stops it),
 * turn the screen off, and read `adb shell dumpsys location` for the app's fixes.
 * With `-e start moving` it starts as if the user were on the way: no fix before, and GPS every
 * 30 s, so the first fixes after the reinstall are GPS ones (reinstall with the screen off to see
 * them made with it off). `-e lookout clear` deletes it again.
 *
 *     adb shell am instrument -w -e lookout set|clear \
 *         -e class com.local.assistant.reminders.PlaceLookoutDeviceTest \
 *         com.local.assistant.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class PlaceLookoutDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = context.appContainer

    @Test
    fun setOrClear() = runBlocking<Unit> {
        val mode = InstrumentationRegistry.getArguments().getString("lookout")
        assumeTrue("opt-in: -e lookout set|clear", mode == "set" || mode == "clear")
        val made = container.memoryRepository.openTasks().filter { it.title == TITLE }
        made.forEach {
            container.reminderScheduler.cancel(it.id)
            container.memoryRepository.deleteTask(it.id)
            Log.i(TAG, "Deleted reminder ${it.id}")
        }
        if (mode == "clear") return@runBlocking

        assumeTrue("allow location all the time first", PlaceFences.allowed(context))
        val at = PhoneLocation(context, container.permissions).current()
        assertNotNull("no location fix: is location on?", at)
        val spot = GeoPoint(at!!.lat + NORTH_M / 111_195.0, at.lon)
        val place = TaskPlace("near here (test)", listOf(PlaceSpot(spot.lat, spot.lon, RADIUS_M, "1.5 km north of the phone")))
        val now = System.currentTimeMillis()
        val id = container.memoryRepository.insertTask(TaskEntity(title = TITLE, createdAt = now, updatedAt = now, place = place))
        container.reminderScheduler.schedulePlace(id, place)
        if (InstrumentationRegistry.getArguments().getString("start") == "moving") {
            delay(2_000)
            // PlaceLookout's own keys: no fix to be still against, and the near pace.
            context.getSharedPreferences("place_lookout", Context.MODE_PRIVATE).edit(commit = true) {
                listOf("lat", "lon", "accuracy", "at").forEach(::remove)
                putLong("every_ms", PlacePace.STEPS_MS.first())
                putBoolean("precise", true)
            }
            Log.i(TAG, "Starting as if moving: GPS every ${PlacePace.STEPS_MS.first() / 1000} s")
        }
        Log.i(TAG, "Reminder $id set ${NORTH_M.toInt()} m north, ${RADIUS_M} m circle; watching: ${PlaceFences.watching(context)}")
    }

    private companion object {
        const val TAG = "PlaceLookoutTest"
        const val TITLE = "Lookout test (deleted after)"
        const val NORTH_M = 1_500.0
        const val RADIUS_M = 500
    }
}
