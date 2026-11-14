package com.local.assistant.gallery.faces

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.local.assistant.appContainer
import com.local.assistant.gallery.PhotoLibrary
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Where a face pass's time goes: filling YuNet's 640×640 input value by value against in bulk,
 * and the detector and recognizer runs with different thread counts and XNNPACK on or off. On a
 * flat grey input, so no photo is read.
 *
 *     adb shell am instrument -w -e eval facespeed -e class com.local.assistant.gallery.faces.FaceSpeedProbeTest \
 *         com.taslim.localassistant.dev.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class FaceSpeedProbeTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val lines = ArrayList<String>()

    private fun report(line: String) {
        lines += line
        Log.i(TAG, line)
    }

    @Test
    fun timesTheFaceModels() {
        assumeTrue("opt-in: -e eval facespeed", InstrumentationRegistry.getArguments().getString("eval") == "facespeed")
        val size = YuNet.SIZE
        val pixels = IntArray(size * size) { 0xFF808080.toInt() }
        val input = ByteBuffer.allocateDirect(4 * size * size * 3).order(ByteOrder.nativeOrder())
        val floats = FloatArray(size * size * 3)
        try {
            val oneByOne = timed(10) {
                input.rewind()
                for (p in pixels) {
                    input.putFloat((p and 0xFF).toFloat())
                    input.putFloat((p shr 8 and 0xFF).toFloat())
                    input.putFloat((p shr 16 and 0xFF).toFloat())
                }
            }
            val bulk = timed(10) {
                var i = 0
                for (p in pixels) {
                    floats[i++] = (p and 0xFF).toFloat()
                    floats[i++] = (p shr 8 and 0xFF).toFloat()
                    floats[i++] = (p shr 16 and 0xFF).toFloat()
                }
                input.rewind()
                input.asFloatBuffer().put(floats)
            }
            report("fill 640×640×3: one by one $oneByOne ms, bulk $bulk ms")

            for (xnnpack in listOf(true, false)) for (threads in listOf(2, 4, 6)) {
                val options = Interpreter.Options().setNumThreads(threads).setUseXNNPACK(xnnpack)
                Interpreter(map(FaceFinder.DETECTOR), options).use { detector ->
                    val outputs = HashMap<Int, Any>()
                    for (i in 0 until detector.outputTensorCount) outputs[i] = ByteBuffer.allocateDirect(detector.getOutputTensor(i).numBytes()).order(ByteOrder.nativeOrder())
                    val run = timed(10) {
                        input.rewind()
                        outputs.values.forEach { (it as ByteBuffer).rewind() }
                        detector.runForMultipleInputsOutputs(arrayOf<Any>(input), outputs)
                    }
                    Interpreter(map(FaceFinder.RECOGNIZER), options).use { recognizer ->
                        val crop = ByteBuffer.allocateDirect(4 * FaceAlign.CROP * FaceAlign.CROP * 3).order(ByteOrder.nativeOrder())
                        val print = ByteBuffer.allocateDirect(4 * FaceFinder.PRINT_SIZE).order(ByteOrder.nativeOrder())
                        val face = timed(10) {
                            crop.rewind()
                            print.rewind()
                            recognizer.run(crop, print)
                        }
                        report("XNNPACK ${if (xnnpack) "on " else "off"}, $threads threads: detector $run ms, recognizer $face ms per face")
                    }
                }
            }
        } finally {
            context.getExternalFilesDir(null)?.let { File(it, "face-speed.txt").appendText(lines.joinToString("\n", postfix = "\n\n")) }
        }
    }

    /**
     * The same steps FaceFinder takes, one by one, on the newest photos: the 1024 px decode, the
     * letterbox draw, reading its pixels, the input fill, the detector, reading its outputs, the
     * decode, and per face the crop and the recognizer. Only timings and counts are reported.
     */
    @Test
    fun breaksDownRealPhotos() = runBlocking<Unit> {
        assumeTrue("opt-in: -e eval facespeed", InstrumentationRegistry.getArguments().getString("eval") == "facespeed")
        val library = context.appContainer.photoLibrary
        assumeTrue("no photo access", library.access() != PhotoLibrary.Access.NONE)
        context.appContainer.galleryIndex.pause()
        val ids = library.scan().filter { it.durationMs == null }.take(24).map { it.mediaId }
        val options = Interpreter.Options().setNumThreads(4)
        val detector = Interpreter(map(FaceFinder.DETECTOR), options)
        val recognizer = Interpreter(map(FaceFinder.RECOGNIZER), options)
        val outputs = HashMap<Int, Any>()
        for (i in 0 until detector.outputTensorCount) outputs[i] = ByteBuffer.allocateDirect(detector.getOutputTensor(i).numBytes()).order(ByteOrder.nativeOrder())
        val size = YuNet.SIZE
        val letterbox = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val crop = Bitmap.createBitmap(FaceAlign.CROP, FaceAlign.CROP, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(size * size)
        val input = ByteBuffer.allocateDirect(4 * size * size * 3).order(ByteOrder.nativeOrder())
        val cropInput = ByteBuffer.allocateDirect(4 * FaceAlign.CROP * FaceAlign.CROP * 3).order(ByteOrder.nativeOrder())
        val bulkInput = ByteBuffer.allocateDirect(4 * size * size * 3).order(ByteOrder.nativeOrder())
        val floats = FloatArray(size * size * 3)
        var sameBytes = 0
        val print = ByteBuffer.allocateDirect(4 * FaceFinder.PRINT_SIZE).order(ByteOrder.nativeOrder())
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val steps = linkedMapOf<String, ArrayList<Long>>()
        fun step(name: String, since: Long) { steps.getOrPut(name) { ArrayList() } += (System.nanoTime() - since) / 1000 }
        var faces = 0
        var configs = HashMap<String, Int>()
        try {
            for ((n, id) in ids.withIndex()) {
                var t = System.nanoTime()
                val photo = library.bitmap(id, FaceFinder.PHOTO_PIXELS) ?: continue
                step("decode", t)
                configs.merge("${photo.config}", 1, Int::plus)
                val scale = size.toFloat() / maxOf(photo.width, photo.height)
                t = System.nanoTime()
                Canvas(letterbox).apply { drawColor(Color.BLACK); drawBitmap(photo, Matrix().apply { setScale(scale, scale) }, paint) }
                step("letterbox", t)
                t = System.nanoTime()
                letterbox.getPixels(pixels, 0, size, 0, 0, size, size)
                step("getPixels", t)
                t = System.nanoTime()
                input.rewind()
                for (p in pixels) {
                    input.putFloat((p and 0xFF).toFloat())
                    input.putFloat((p shr 8 and 0xFF).toFloat())
                    input.putFloat((p shr 16 and 0xFF).toFloat())
                }
                input.rewind()
                step("fill", t)
                // The bulk fill FaceFinder uses now must give the detector the very same bytes.
                var at = 0
                for (p in pixels) {
                    floats[at++] = (p and 0xFF).toFloat()
                    floats[at++] = (p shr 8 and 0xFF).toFloat()
                    floats[at++] = (p shr 16 and 0xFF).toFloat()
                }
                bulkInput.rewind()
                bulkInput.asFloatBuffer().put(floats)
                bulkInput.rewind()
                if (bulkInput == input) sameBytes++
                input.rewind()
                t = System.nanoTime()
                outputs.values.forEach { (it as ByteBuffer).rewind() }
                detector.runForMultipleInputsOutputs(arrayOf<Any>(input), outputs)
                step("detector", t)
                t = System.nanoTime()
                val byShape = outputs.mapValues { (_, b) -> (b as ByteBuffer).rewind(); FloatArray(b.capacity() / 4).also { b.asFloatBuffer().get(it) } }
                step("read outputs", t)
                // The decode itself is timed on the class, kind and stride layout FaceFinder works out.
                val cls = ArrayList<FloatArray>(); val obj = ArrayList<FloatArray>(); val box = ArrayList<FloatArray>(); val kps = ArrayList<FloatArray>()
                for (stride in YuNet.STRIDES) {
                    val mine = (0 until detector.outputTensorCount).filter { detector.getOutputTensor(it).shape()[1] == YuNet.cells(stride) }
                    val ones = mine.filter { detector.getOutputTensor(it).shape()[2] == 1 }
                    cls += byShape.getValue(ones[0]); obj += byShape.getValue(ones[1])
                    box += byShape.getValue(mine.first { detector.getOutputTensor(it).shape()[2] == 4 })
                    kps += byShape.getValue(mine.first { detector.getOutputTensor(it).shape()[2] == 10 })
                }
                t = System.nanoTime()
                val found = YuNet.decode(cls, obj, box, kps).filter { minOf(it.w, it.h) >= FaceFinder.MIN_FACE_PIXELS }
                step("decode boxes", t)
                for (face in found) {
                    faces++
                    t = System.nanoTime()
                    val inPhoto = FloatArray(10) { face.landmarks[it] / scale }
                    Canvas(crop).apply { drawColor(Color.BLACK); drawBitmap(photo, Matrix().apply { setValues(FaceAlign.transform(inPhoto) + floatArrayOf(0f, 0f, 1f)) }, paint) }
                    crop.getPixels(pixels, 0, FaceAlign.CROP, 0, 0, FaceAlign.CROP, FaceAlign.CROP)
                    cropInput.rewind()
                    for (i in 0 until FaceAlign.CROP * FaceAlign.CROP) {
                        val p = pixels[i]
                        cropInput.putFloat((p shr 16 and 0xFF).toFloat()); cropInput.putFloat((p shr 8 and 0xFF).toFloat()); cropInput.putFloat((p and 0xFF).toFloat())
                    }
                    cropInput.rewind()
                    step("face crop+fill", t)
                    t = System.nanoTime()
                    print.rewind()
                    recognizer.run(cropInput, print)
                    step("recognizer", t)
                }
                photo.recycle()
                if (n == 0) steps.values.forEach { it.clear() } // the first photo warms up
            }
        } finally {
            detector.close(); recognizer.close()
        }
        report("real photos: ${ids.size} (bitmap configs $configs), $faces faces; bulk fill byte-identical on $sameBytes of ${configs.values.sum()}")
        for ((name, us) in steps) report("  $name: median ${(us.sorted().getOrNull(us.size / 2) ?: 0) / 1000.0} ms, total ${us.sum() / 1000} ms over ${us.size}")
        context.getExternalFilesDir(null)?.let { File(it, "face-speed.txt").appendText(lines.joinToString("\n", postfix = "\n\n")) }
    }

    /** Median of [times] runs after one warm-up, in ms. */
    private fun timed(times: Int, block: () -> Unit): Long {
        block()
        val runs = LongArray(times) {
            val started = System.nanoTime()
            block()
            (System.nanoTime() - started) / 1_000_000
        }
        return runs.sorted()[times / 2]
    }

    private fun map(name: String) = context.assets.openFd(name).use { fd ->
        FileInputStream(fd.fileDescriptor).channel.use { it.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength) }
    }

    private companion object {
        const val TAG = "FaceSpeedProbe"
    }
}
