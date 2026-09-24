package com.local.assistant.reminders

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.util.Log
import com.local.assistant.memory.tools.SystemAlarms
import java.time.DayOfWeek

/**
 * Alarms handed to the phone's clock app with the standard `ACTION_SET_ALARM` intent, set
 * without opening it. From then on the alarm is the clock app's: it rings like any other alarm,
 * and that is where it is changed or deleted.
 *
 * Starting another app's activity is only allowed while this app is in front, which it is when
 * the user has just asked; if it is not, the call is refused rather than silently dropped.
 */
class ClockAlarms(
    private val context: Context,
    private val inForeground: () -> Boolean,
) : SystemAlarms {

    override fun available(): Boolean =
        Intent(AlarmClock.ACTION_SET_ALARM).resolveActivity(context.packageManager) != null

    override fun set(hour: Int, minute: Int, days: Set<DayOfWeek>, label: String?): Boolean {
        if (!inForeground()) return false
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        label?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        if (days.isNotEmpty()) intent.putExtra(AlarmClock.EXTRA_DAYS, ArrayList(days.map(::calendarDay)))
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No clock app took the alarm", e)
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "Clock app refused the alarm", e)
            false
        }
    }

    override fun openClock() {
        runCatching { context.startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Log.w(TAG, "Could not open the clock app", it) }
    }

    /** `java.util.Calendar` numbers the week from Sunday = 1. */
    private fun calendarDay(day: DayOfWeek): Int = day.value % 7 + 1

    private companion object {
        const val TAG = "ClockAlarms"
    }
}
