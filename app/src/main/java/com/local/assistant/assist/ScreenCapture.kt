package com.local.assistant.assist

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import java.io.File

/**
 * The screen the assistant was opened over: Android hands the digital assistant a screenshot of
 * it each time it is called up (holding the power button, or the wake word asking for one), as
 * long as "Use screenshot" is on in the phone's assistant settings, as it is out of the box.
 *
 * Kept only while the overlay that asked about it is open, in the app's cache: a question about
 * the screen ("summarise this", "share this with Anjali") sends it to the model, and the tools
 * save it to the gallery or send it on ([com.local.assistant.memory.tools.DeviceTools]) from
 * copies of their own. Never kept over the lock screen, which is all there would be to see.
 */
object ScreenCapture {

    private const val TAG = "ScreenCapture"
    private const val DIR = "screen"

    /** High enough that small text stays sharp for the model to read and the person sent it. */
    private const val QUALITY = 95

    /** Older than this, it is no longer the screen the user is asking about. */
    private const val MAX_AGE_MS = 10 * 60 * 1000L

    private data class Shot(val file: File, val at: Long)

    @Volatile
    private var latest: Shot? = null

    /** The screenshot's file, if there is one from this opening of the assistant. */
    fun current(): String? = latest?.takeIf { SystemClock.elapsedRealtime() - it.at < MAX_AGE_MS && it.file.isFile }?.file?.absolutePath

    /**
     * Keeps [bitmap] as the screen, in place of any before it. Not on the main thread. A JPEG:
     * a PNG of the 1272×2772 screen took 2.8 s while the model loaded (4 Nov 2026), long enough
     * for "share this" to find no screen yet, and came to 3.4 MB to send.
     */
    fun keep(context: Context, bitmap: Bitmap) {
        val started = SystemClock.elapsedRealtime()
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        val file = File(dir, "screen-${System.currentTimeMillis()}.jpg")
        try {
            // A hardware bitmap is copied out of the GPU first, to be sure it can be written.
            val readable = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
            file.outputStream().use { readable.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't keep the screenshot", e)
            file.delete()
            return
        }
        val previous = latest
        latest = Shot(file, SystemClock.elapsedRealtime())
        previous?.file?.delete()
        Log.i(TAG, "Kept the screen, ${bitmap.width}×${bitmap.height}, ${file.length() / 1024} KB in ${SystemClock.elapsedRealtime() - started} ms")
    }

    /** Forgets every screen kept: the assistant is being opened again, over something else. */
    fun discard(context: Context) {
        latest = null
        File(context.cacheDir, DIR).listFiles()?.forEach { it.delete() }
    }

    /** Forgets the screen at [path], the one an overlay now closed was opened over, unless a newer one replaced it. */
    fun forget(path: String) {
        if (latest?.file?.absolutePath == path) latest = null
        File(path).delete()
    }
}
