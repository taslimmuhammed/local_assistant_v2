package com.local.assistant.memory

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.local.assistant.memory.embed.EmbedderCatalog
import com.local.assistant.model.ModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * EmbeddingGemma downloaded the way a new user gets it: the app's own [ModelManager], from the
 * GitHub release in [EmbedderCatalog], through its redirect, checked against the SHA-256. Into
 * a directory of its own, so the embedder the app uses is never touched, and deleted after.
 *
 * Opt-in, since it downloads 333 MB:
 *
 *     adb shell am instrument -w -e eval download \
 *         -e class com.local.assistant.memory.EmbedderDownloadTest \
 *         com.local.assistant.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class EmbedderDownloadTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** Where the test's manager remembers its file; never the app's own setting. */
    private var storedPath: String? = null

    @Test
    fun theReleaseDownloadsAndMatchesItsChecksum() = runBlocking<Unit> {
        assumeTrue("opt-in: -e eval download", InstrumentationRegistry.getArguments().getString("eval") == "download")
        val file = EmbedderCatalog.FILE.copy(directory = "embedders-download-test")
        val dir = File(context.filesDir, file.directory)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val manager = ModelManager(context, file, this@EmbedderDownloadTest::storedPath, scope)
            val started = System.currentTimeMillis()
            manager.download()
            val (installed, error) = withTimeout(15 * 60_000L) {
                // The manager's check already compared the SHA-256; a mismatch surfaces as an error.
                combine(manager.installed, manager.error) { installed, error -> installed to error }
                    .first { (installed, error) -> installed != null || error != null }
            }
            assertNotNull("download failed: $error", installed)
            assertEquals(333_151_761L, installed!!.file.length())
            Log.i(TAG, "Downloaded ${installed.file.length()} bytes in ${System.currentTimeMillis() - started} ms")
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    private companion object {
        const val TAG = "EmbedderDownloadTest"
    }
}
