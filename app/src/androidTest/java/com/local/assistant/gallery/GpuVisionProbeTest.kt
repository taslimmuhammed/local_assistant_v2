package com.local.assistant.gallery

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.local.assistant.appContainer
import com.local.assistant.data.db.AttachmentKind
import com.local.assistant.llm.ChatSpec
import com.local.assistant.llm.GenEvent
import com.local.assistant.llm.PromptAttachment
import com.local.assistant.llm.Sampling
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Whether the chat model can still look at an image while the photo model's vision encoder is on
 * the GPU. On LiteRT-LM 0.18.1 it couldn't ("TensorBuffer must have all static dimensions",
 * 26 Oct 2026), which is why photo indexing moved to the slower CPU. Tried in every order on a
 * drawn picture (a red circle and "GO 42"), never one of the user's photos:
 *
 *  A. chat engine loaded, then the photo model on the GPU, then the chat reads the picture;
 *  B. the chat reads the picture first, then the photo model on the GPU, then the chat again;
 *  C. the photo model on the GPU before the chat engine loads, then the chat reads it;
 *  D. the photo model closed again, then the chat reads it.
 *
 *     adb shell am instrument -w -e eval gpuvision -e class com.local.assistant.gallery.GpuVisionProbeTest \
 *         com.taslim.localassistant.dev.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class GpuVisionProbeTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val lines = ArrayList<String>()

    private fun report(line: String) {
        lines += line
        Log.i(TAG, line)
    }

    @Test
    fun chatSeesImagesBesideGpuPhotoModel() = runBlocking<Unit> {
        assumeTrue("opt-in: -e eval gpuvision", InstrumentationRegistry.getArguments().getString("eval") == "gpuvision")
        val container = context.appContainer
        val modelFile = container.galleryModelManager.installed.value?.file
        assumeTrue("the photo model isn't installed", modelFile != null)
        val picture = drawnPicture()
        val jpeg = picture.readBytes()

        container.galleryIndex.pause()
        container.photoModel.unload()
        val gpuEmbedder = { GalleryEmbedder({ modelFile }, context.cacheDir, visionOnGpu = true) }
        try {
            container.conversations.withModelFree {
                // A: chat engine first.
                check(container.llmBackend.ensureReady()) { "No chat model loaded" }
                report("A0 chat alone: ${ask(picture.path)}")
                var photos = gpuEmbedder()
                report("A1 photo model on GPU: vector ${photos.embedImage(jpeg)?.size ?: "none"}")
                report("A2 chat with GPU photo model loaded: ${ask(picture.path)}")
                // B: chat image, then the photo model embeds again, then chat.
                report("B1 photo model embeds again: vector ${photos.embedImage(jpeg)?.size ?: "none"}")
                report("B2 chat again: ${ask(picture.path)}")
                photos.unload()
                // C: the photo model on the GPU before the chat engine loads.
                container.llmService.unload()
                photos = gpuEmbedder()
                report("C1 photo model on GPU, no chat engine: vector ${photos.embedImage(jpeg)?.size ?: "none"}")
                check(container.llmBackend.ensureReady()) { "Chat model didn't load again" }
                report("C2 chat engine loaded after it: ${ask(picture.path)}")
                // D: closed again.
                photos.unload()
                report("D chat after the photo model closed: ${ask(picture.path)}")
            }
        } finally {
            picture.delete()
            context.getExternalFilesDir(null)?.let { File(it, "gpu-vision-probe.txt").appendText(lines.joinToString("\n", postfix = "\n\n")) }
        }
    }

    /**
     * Whether GPU vectors can sit beside the CPU ones already in gallery.db: the newest photos
     * embedded both ways, and the cosine of each pair. Only the numbers are reported.
     */
    @Test
    fun gpuVectorsMatchCpu() = runBlocking<Unit> {
        assumeTrue("opt-in: -e eval gpuvision", InstrumentationRegistry.getArguments().getString("eval") == "gpuvision")
        val container = context.appContainer
        val modelFile = container.galleryModelManager.installed.value?.file
        assumeTrue("the photo model isn't installed", modelFile != null)
        container.galleryIndex.pause()
        container.photoModel.unload()
        val library = container.photoLibrary
        val jpegs = library.scan().filter { it.durationMs == null }.take(24).mapNotNull { library.jpeg(it.mediaId, GalleryModel.INPUT_PIXELS) }
        val cpu = GalleryEmbedder({ modelFile }, context.cacheDir, visionOnGpu = false)
        val onCpu = try { jpegs.map { cpu.embedImage(it) } } finally { cpu.unload() }
        val gpu = GalleryEmbedder({ modelFile }, context.cacheDir, visionOnGpu = true)
        val onGpu = try { jpegs.map { gpu.embedImage(it) } } finally { gpu.unload() }
        fun dot(a: FloatArray, b: FloatArray) = a.indices.sumOf { (a[it] * b[it]).toDouble() }
        val cosines = onCpu.zip(onGpu).mapNotNull { (a, b) -> if (a == null || b == null) null else dot(a, b) }.sorted()
        // Does each GPU vector still find its own CPU twin first among all the CPU vectors?
        val sameFirst = onGpu.indices.count { i ->
            val g = onGpu[i] ?: return@count false
            onCpu.indices.maxByOrNull { j -> onCpu[j]?.let { dot(g, it) } ?: -1.0 } == i
        }
        report("CPU vs GPU on ${cosines.size} photos: cosine min ${"%.4f".format(cosines.first())}, median ${"%.4f".format(cosines[cosines.size / 2])}; own twin first $sameFirst/${onGpu.size}")
        context.getExternalFilesDir(null)?.let { File(it, "gpu-vision-probe.txt").appendText(lines.joinToString("\n", postfix = "\n\n")) }
    }

    /** What the chat model says about the picture, or the error it gave. */
    private suspend fun ask(path: String): String = try {
        val reply = StringBuilder()
        context.appContainer.llmBackend.openChat(ChatSpec(systemPrefix = "You are a careful assistant.", history = emptyList(), sampling = Sampling.BACKGROUND, maxOutputTokens = 40)).use { session ->
            session.send("What colour is the circle, and what text is written? Reply in five words.", PromptAttachment(path, AttachmentKind.IMAGE)).collect { event ->
                when (event) {
                    is GenEvent.TextDelta -> reply.append(event.text)
                    is GenEvent.Error -> throw event.cause
                    else -> Unit
                }
            }
        }
        val said = reply.toString().replace('\n', ' ').trim()
        if (said.isEmpty()) "EMPTY reply" else "ok \"${said.take(60)}\""
    } catch (e: Exception) {
        "ERROR ${e.javaClass.simpleName}: ${e.message?.take(160)}"
    }

    private fun drawnPicture(): File {
        val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        canvas.drawCircle(220f, 240f, 140f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED })
        canvas.drawText("GO 42", 400f, 260f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 72f })
        val file = File(context.cacheDir, "gpu-probe.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        return file
    }

    private companion object {
        const val TAG = "GpuVisionProbe"
    }
}
