package com.local.assistant.device

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.local.assistant.memory.tools.AppMatcher
import com.local.assistant.memory.tools.AppTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What open_app can see on a real phone. Read-only: nothing is opened, dialled or switched.
 */
@RunWith(AndroidJUnit4::class)
class AndroidPhoneTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val phone = AndroidPhone(context, inForeground = { false })

    @Test
    fun launcherAppsAreVisibleByLabel() {
        val apps = phone.installedApps()
        assertTrue("the manifest's <queries> lets launcher apps be seen", apps.size > 5)
        assertEquals(apps.size, apps.distinctBy { it.packageName }.size)
        assertFalse(apps.any { it.packageName == context.packageName })
        // Every phone has a Settings app with that label, found the way open_app finds apps.
        val settings = apps.firstOrNull { it.label.equals("Settings", ignoreCase = true) }
        if (settings != null) {
            val found = AppMatcher.resolve("settings", null, apps) as AppMatcher.Result.Found
            assertEquals(AppTarget.Settings, found.target)
        }
    }

    @Test
    fun nothingOpensFromTheBackground() {
        assertEquals(com.local.assistant.memory.tools.Handoff.IN_BACKGROUND, phone.dial("100"))
    }
}
