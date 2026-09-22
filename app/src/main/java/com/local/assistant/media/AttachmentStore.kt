package com.local.assistant.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Owns the files attached to messages. Everything lives in the app's private storage so a chat
 * can be reopened later with its image or voice note intact.
 */
class AttachmentStore(private val context: Context) {

    private val dir: File = File(context.filesDir, "attachments").apply { mkdirs() }

    fun newAudioFile(): File = File(dir, "${UUID.randomUUID()}.wav")

    /**
     * Copies the picked image in, rotated upright and scaled down to
     * [MediaLimits.MAX_IMAGE_EDGE_PX]. The original may be a 12 MP camera shot, which would cost
     * memory and vision tokens without showing the model anything more.
     */
    suspend fun importImage(uri: Uri): File = withContext(Dispatchers.IO) {
        val source = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IOException("Could not read the selected image")

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Not a readable image")

        val decoded = BitmapFactory.decodeByteArray(
            source,
            0,
            source.size,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
            },
        ) ?: throw IOException("Could not decode the selected image")

        val upright = applyExifRotation(decoded, source)
        val scaled = scaleToFit(upright)

        val destination = File(dir, "${UUID.randomUUID()}.jpg")
        destination.outputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, MediaLimits.IMAGE_JPEG_QUALITY, out)
        }
        if (scaled !== decoded) scaled.recycle()
        decoded.recycle()
        destination
    }

    fun delete(path: String?) {
        if (path == null) return
        val file = File(path)
        if (file.parentFile?.absolutePath == dir.absolutePath) file.delete()
    }

    /** Removes attachments no message references any more. */
    suspend fun pruneExcept(keepPaths: Set<String>) = withContext(Dispatchers.IO) {
        dir.listFiles()?.forEach { file ->
            if (file.absolutePath !in keepPaths) file.delete()
        }
        Unit
    }

    private fun sampleSizeFor(width: Int, height: Int): Int {
        var sample = 1
        while (max(width, height) / (sample * 2) >= MediaLimits.MAX_IMAGE_EDGE_PX) {
            sample *= 2
        }
        return sample
    }

    private fun scaleToFit(bitmap: Bitmap): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= MediaLimits.MAX_IMAGE_EDGE_PX) return bitmap
        val ratio = MediaLimits.MAX_IMAGE_EDGE_PX.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).roundToInt().coerceAtLeast(1),
            (bitmap.height * ratio).roundToInt().coerceAtLeast(1),
            true,
        )
    }

    /** Phone photos are usually stored sideways with an EXIF tag saying which way is up. */
    private fun applyExifRotation(bitmap: Bitmap, source: ByteArray): Bitmap {
        val degrees = runCatching {
            val exif = ExifInterface(source.inputStream())
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)

        if (degrees == 0f) return bitmap
        val rotated = Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height,
            Matrix().apply { postRotate(degrees) },
            true,
        )
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }
}
