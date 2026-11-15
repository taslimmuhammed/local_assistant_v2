package com.local.assistant.model

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.local.assistant.gallery.GalleryModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Pause stops a download for real, and the next one carries on from its bytes (users, Nov 2026:
 * the button did nothing, as the copy loop never looked at its job). A few MB of the photo model,
 * fetched into a directory of its own, not remembered as wanted: the app's own downloads aren't
 * touched.
 *
 *     adb shell am instrument -w -e class com.local.assistant.model.DownloadPauseTest \
 *         com.taslim.localassistant.dev.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class DownloadPauseTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val file = GalleryModel.FILE.copy(directory = "pause-test")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var stored: String? = null

    @After
    fun tearDown() {
        scope.cancel()
        File(context.filesDir, file.directory).deleteRecursively()
    }

    @Test
    fun pauseStopsTheDownloadAndTheNextPicksUpWhereItStopped() = runBlocking {
        val manager = ModelManager(context, file, this@DownloadPauseTest::stored, scope)
        manager.download()
        withTimeout(60_000) { manager.transfer.first { (it?.bytesDone ?: 0) > 3_000_000 } }

        val pausedAt = System.nanoTime()
        manager.pause()
        withTimeout(5_000) { manager.transfer.first { it == null } }
        val stopMs = (System.nanoTime() - pausedAt) / 1_000_000
        manager.awaitIdle()
        val kept = manager.partialBytes()
        delay(3_000)
        Log.i(TAG, "Stopped ${stopMs} ms after Pause, ${kept / 1_000_000} MB kept")
        assertEquals("nothing written after the pause", kept, manager.partialBytes())
        assertTrue("its bytes are kept", kept > 3_000_000)
        assertNull(manager.installed.value)
        assertNull(manager.error.value)

        manager.download()
        val first = withTimeout(60_000) { manager.transfer.first { it != null && it.bytesDone > 0 } }!!
        assertTrue("carried on from ${kept / 1_000_000} MB, not from the start (${first.bytesDone})", first.bytesDone >= kept)
        withTimeout(60_000) { manager.transfer.first { (it?.bytesDone ?: 0) > kept + 1_000_000 } }

        manager.cancel()
        withTimeout(5_000) { manager.transfer.first { it == null } }
        manager.awaitIdle()
        assertNull(manager.installed.value)
        assertNull(manager.error.value)
    }

    private companion object {
        const val TAG = "DownloadPauseTest"
    }
}
