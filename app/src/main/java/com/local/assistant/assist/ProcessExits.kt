package com.local.assistant.assist

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build

/**
 * How often the phone itself closed the app lately. Every such close throws the loaded model away,
 * so the next answer waits ~10 s for it to load and ~6 s more for the prompt to be read. OxygenOS
 * closed the app 16 times in 23 hours on the user's OnePlus (13 Nov 2026), with 6.5 GB of memory
 * free and the app bound as the phone's assistant: "OTHER KILLS BY SYSTEM", described as
 * "o-kill(…)". The user can usually stop it from the app's battery settings.
 */
object ProcessExits {

    /**
     * Closes of the app's main process by the system since [sinceMs] (wall clock): for want of
     * memory, by the phone maker's own killer, or for using too much. Not the user's own (a force
     * stop, a swipe off the recents screen), and not an update.
     */
    fun systemKillsSince(context: Context, sinceMs: Long): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return 0
        val manager = context.getSystemService(ActivityManager::class.java) ?: return 0
        val exits = runCatching { manager.getHistoricalProcessExitReasons(null, 0, 0) }.getOrNull() ?: return 0
        return exits.count { exit ->
            exit.timestamp >= sinceMs &&
                exit.processName == context.packageName &&
                exit.reason in SYSTEM_REASONS &&
                exit.description?.contains("remove task", ignoreCase = true) != true
        }
    }

    private val SYSTEM_REASONS = setOf(
        ApplicationExitInfo.REASON_LOW_MEMORY,
        ApplicationExitInfo.REASON_OTHER,
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
    )

    /** How many closes in a day are worth telling the user about. */
    const val WORTH_A_NOTICE = 3
}
