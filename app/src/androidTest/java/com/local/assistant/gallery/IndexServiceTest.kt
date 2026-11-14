package com.local.assistant.gallery

import android.app.NotificationManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.local.assistant.appContainer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The indexing service on the phone, against the app's own index (nothing is cleared): it comes
 * up in the foreground with its notice, runs a pass, and goes away with the notice once the pass
 * is done. With nothing left to index that takes a few seconds.
 *
 *     adb shell am instrument -w -e eval indexservice -e class com.local.assistant.gallery.IndexServiceTest \
 *         com.taslim.localassistant.dev.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class IndexServiceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun startsShowsAndStops() = runBlocking<Unit> {
        assumeTrue("opt-in: -e eval indexservice", InstrumentationRegistry.getArguments().getString("eval") == "indexservice")
        assumeTrue("the photo model or photo access is missing", context.appContainer.photoSearchReady())
        val lines = ArrayList<String>()
        fun report(line: String) { lines += line; Log.i("IndexServiceTest", line) }
        val notices = context.getSystemService(NotificationManager::class.java)
        fun noticeUp() = notices.activeNotifications.any { it.notification.channelId == "indexing" }
        try {
            val started = System.currentTimeMillis()
            IndexService.start(context)
            var up = false
            var sawNotice = false
            while (System.currentTimeMillis() - started < 120_000) {
                if (IndexService.running) up = true
                if (noticeUp()) sawNotice = true
                if (up && !IndexService.running) break
                delay(100)
            }
            delay(1_000)
            report("service came up: $up; notice seen: $sawNotice; stopped by itself: ${up && !IndexService.running} after ${(System.currentTimeMillis() - started) / 1000.0} s; notice gone after: ${!noticeUp()}")
            val status = context.appContainer.galleryIndex.status.value
            report("index: ${status.indexed} of ${status.total} done, running ${status.running}")
        } finally {
            context.getExternalFilesDir(null)?.let { File(it, "index-service.txt").appendText(lines.joinToString("\n", postfix = "\n\n")) }
        }
    }
}
