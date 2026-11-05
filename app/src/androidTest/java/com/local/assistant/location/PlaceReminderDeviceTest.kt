package com.local.assistant.location

import android.app.Notification
import android.app.NotificationManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.local.assistant.appContainer
import com.local.assistant.device.PhoneLocation
import com.local.assistant.memory.db.PlaceSpot
import com.local.assistant.memory.db.TaskEntity
import com.local.assistant.memory.db.TaskPlace
import com.local.assistant.reminders.PlaceFences
import com.local.assistant.reminders.PlayGeofences
import com.local.assistant.reminders.ReminderNotifications
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A place reminder rung by a real geofence, without walking anywhere and without mock
 * locations: a reminder is made for the spot the phone is at, and its fence is set with Play
 * Services told to ring at once when the phone is already inside (the app's own fences wait for
 * the next arrival). From there it is an arrival like any other: Play Services wakes
 * [com.local.assistant.reminders.PlaceReminderReceiver], which rings the reminder and marks it
 * rung. Needs location allowed all the time and notifications on; everything it makes is deleted.
 *
 *     adb shell am instrument -w -e demo place \
 *         -e class com.local.assistant.location.PlaceReminderDeviceTest \
 *         com.local.assistant.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Progress goes to logcat (tags PlaceReminderTest, PlaceFences, PlaceReminders).
 */
@RunWith(AndroidJUnit4::class)
class PlaceReminderDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = context.appContainer

    @Test
    fun ringsOnArriving() = runBlocking<Unit> {
        assumeTrue("opt-in: -e demo place", InstrumentationRegistry.getArguments().getString("demo") == "place")
        assumeTrue("allow location all the time first", PlaceFences.allowed(context))
        assumeTrue("allow notifications first", ReminderNotifications.canPost(context))
        assumeTrue(
            "Play Services' geofences",
            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS,
        )
        val at = PhoneLocation(context, container.permissions).current()
        assertNotNull("no location fix: is location on?", at)
        Log.i(TAG, "The phone is at ${at!!.rounded()} (rounded)")

        val now = System.currentTimeMillis()
        val place = TaskPlace("here (test)", listOf(PlaceSpot(at.lat, at.lon, RADIUS_M, "where the phone is")))
        val id = container.memoryRepository.insertTask(TaskEntity(title = TITLE, createdAt = now, updatedAt = now, place = place))
        val manager = context.getSystemService(NotificationManager::class.java)
        try {
            val started = System.currentTimeMillis()
            PlayGeofences(context, initialEnter = true).watch(id, place)
            val shown = withTimeoutOrNull(WAIT_MS) {
                while (manager.activeNotifications.none { it.id == id.toInt() }) delay(1_000)
                manager.activeNotifications.first { it.id == id.toInt() }.notification
            }
            assertNotNull("no notification within ${WAIT_MS / 1000} s", shown)
            Log.i(TAG, "Rang ${(System.currentTimeMillis() - started) / 1000} s after the fence was set")
            val notification = shown!!
            assertEquals(TITLE, notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
            assertEquals("You're near here (test), where the phone is", notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
            assertTrue("rings until seen", notification.flags and Notification.FLAG_INSISTENT != 0)
            assertEquals(listOf("Done", "Next time"), notification.actions.map { it.title.toString() })
            // Marked rung, so wandering back in at the edge stays quiet for a while.
            val rang = container.memoryRepository.task(id)?.place?.rangAt
            assertNotNull(rang)
            Log.i(TAG, "Passed: rang and marked rung")
            // Seen for a moment on the phone before it goes.
            delay(SHOWN_MS)
        } finally {
            // Takes the fence and the notification down too.
            container.reminderScheduler.cancel(id)
            container.memoryRepository.deleteTask(id)
        }
    }

    private companion object {
        const val TAG = "PlaceReminderTest"
        const val TITLE = "Place reminder test"
        const val RADIUS_M = 200
        const val WAIT_MS = 180_000L
        const val SHOWN_MS = 5_000L
    }
}
