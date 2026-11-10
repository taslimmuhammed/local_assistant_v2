package com.local.assistant.location

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.location.LocationManager
import android.net.Uri
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
import com.local.assistant.memory.db.TaskStatus
import com.local.assistant.reminders.PlaceAlerts
import com.local.assistant.reminders.PlaceFences
import com.local.assistant.reminders.PlaceReminderReceiver
import com.local.assistant.reminders.PlayGeofences
import com.local.assistant.reminders.ReminderNotifications
import com.local.assistant.reminders.ReminderReceiver
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
 * [com.local.assistant.reminders.PlaceReminderReceiver], which rings the reminder as an alarm and
 * finishes it; "Next time" opens it again. With `-e screen show` it is left ringing for half a
 * minute, to be answered on the phone: a locked phone shows the alarm's own screen. Needs location allowed all the time and
 * notifications on; everything it makes is deleted.
 *
 *     adb shell am instrument -w -e demo place [-e screen show] [-e via near] \
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
        // "-e via near": rung through Android's proximity alert, as a phone without Play Services
        // is, so with no location fix needed (a locked OnePlus gave none). The spot is then nowhere.
        val near = InstrumentationRegistry.getArguments().getString("via") == "near"
        val at = if (near) GeoPoint(0.0, 0.0) else PhoneLocation(context, container.permissions).current()
        assertNotNull("no location fix: is location on? (or -e via near)", at)
        if (!near) Log.i(TAG, "The phone is at ${at!!.rounded()} (rounded)")

        val now = System.currentTimeMillis()
        val place = TaskPlace("here (test)", listOf(PlaceSpot(at!!.lat, at.lon, RADIUS_M, "where the phone is")))
        val id = container.memoryRepository.insertTask(TaskEntity(title = TITLE, createdAt = now, updatedAt = now, place = place))
        val manager = context.getSystemService(NotificationManager::class.java)
        try {
            val started = System.currentTimeMillis()
            if (near) {
                context.sendBroadcast(
                    Intent(context, PlaceReminderReceiver::class.java)
                        .setAction(PlaceReminderReceiver.ACTION_NEAR)
                        .setData(Uri.parse(PlaceAlerts.fenceId(id, 0)))
                        .putExtra(LocationManager.KEY_PROXIMITY_ENTERING, true),
                )
            } else {
                PlayGeofences(context, initialEnter = true).watch(id, place)
            }
            val shown = withTimeoutOrNull(WAIT_MS) {
                while (manager.activeNotifications.none { it.id == id.toInt() }) delay(1_000)
                manager.activeNotifications.first { it.id == id.toInt() }.notification
            }
            assertNotNull("no notification within ${WAIT_MS / 1000} s", shown)
            Log.i(TAG, "Rang ${(System.currentTimeMillis() - started) / 1000} s after the fence was set")
            val notification = shown!!
            assertEquals(TITLE, notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
            assertEquals("You're near here (test), where the phone is", notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
            // An alarm: its own channel on the alarm stream, the whole screen when locked, ringing until answered.
            assertEquals("place_alarms", notification.channelId)
            assertEquals(Notification.CATEGORY_ALARM, notification.category)
            assertNotNull("full-screen intent", notification.fullScreenIntent)
            assertTrue("rings until answered", notification.flags and Notification.FLAG_INSISTENT != 0)
            assertEquals(listOf("Dismiss", "Next time"), notification.actions.map { it.title.toString() })
            Log.i(TAG, "Full screen allowed: ${ReminderNotifications.canUseFullScreen(context)}")
            // Over once it rang, and no longer watched; when it rang is kept.
            val rang = container.memoryRepository.task(id)!!
            assertEquals(TaskStatus.DONE, rang.status)
            assertNotNull(rang.place?.rangAt)
            Log.i(TAG, "Passed: rang as an alarm, and finished")

            if (InstrumentationRegistry.getArguments().getString("screen") == "show") {
                // Left ringing for its answer on the phone: on a locked phone the system puts up its
                // screen (PlaceAlarmActivity) by itself; in use, the banner opens it.
                Log.i(TAG, "Ringing for ${SCREEN_MS / 1000} s, for an answer on the phone")
                withTimeoutOrNull(SCREEN_MS) {
                    while (manager.activeNotifications.any { it.id == id.toInt() }) delay(500)
                    Log.i(TAG, "Answered on the alarm screen")
                }
            }

            // "Next time": open again, its notification gone, for the next arrival.
            context.sendBroadcast(
                Intent(context, PlaceReminderReceiver::class.java)
                    .setAction(PlaceReminderReceiver.ACTION_NEXT_TIME)
                    .putExtra(ReminderReceiver.EXTRA_TASK_ID, id),
            )
            val reopened = withTimeoutOrNull(10_000) {
                while (container.memoryRepository.task(id)?.status != TaskStatus.OPEN) delay(250)
                container.memoryRepository.task(id)
            }
            assertNotNull("not open again after Next time", reopened)
            assertEquals(rang.place?.rangAt, reopened!!.place?.rangAt)
            assertTrue("notification gone", manager.activeNotifications.none { it.id == id.toInt() })
            Log.i(TAG, "Passed: open again after Next time")
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
        const val SCREEN_MS = 30_000L
    }
}
