package com.local.assistant.assist

import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

/**
 * Whether this app is the phone's digital assistant — the one holding the power button (or the
 * home button, or a corner swipe, depending on the phone) — and the way to make it so.
 *
 * The app qualifies because [AssistActivity] handles `ACTION_ASSIST`. Android doesn't let an app
 * ask for the assistant role (`RoleManager` marks it not requestable), so the user picks it in
 * the system's settings.
 */
object AssistantRole {

    fun isDefault(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roles = context.getSystemService(RoleManager::class.java)
            if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
                return roles.isRoleHeld(RoleManager.ROLE_ASSISTANT)
            }
        }
        // Before roles: the component the system launches, e.g. "com.local.assistant/.assist.AssistActivity".
        val assistant = runCatching { Settings.Secure.getString(context.contentResolver, "assistant") }.getOrNull()
        return assistant?.substringBefore('/') == context.packageName
    }

    /**
     * Opens the "Digital assistant app" setting: on most phones that is the voice input screen;
     * failing that, the default apps list.
     */
    fun openSettings(context: Context) {
        val screens = listOf(
            Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (screen in screens) {
            try {
                context.startActivity(screen.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: ActivityNotFoundException) {
                continue
            }
        }
    }
}
