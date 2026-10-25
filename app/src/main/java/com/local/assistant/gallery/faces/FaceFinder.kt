package com.local.assistant.gallery.faces

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.sqrt

/**
 * One face in a photo: where it is, as fractions of the photo's width and height, how sure YuNet
 * was, and SFace's 128-number print of it (L2-normalised: the cosine of two prints is their dot
 * product, about 0.36 and up for the same person).
 */
class FoundFace(val x: Float, val y: Float, val w: Float, val h: Float, val score: Float, val print: FloatArray)

/**
 * The faces in a photo: YuNet finds them, SFace prints them. Both are small TensorFlow Lite
 * models in the APK (assets/faces, see NOTICE.txt there), run on the CPU, memory-mapped from the
 * APK. One photo at a time; not thread-safe.
 *
 * The photo is shrunk and letterboxed into YuNet's 640×640 input to find the faces, and each face
 * is then cut from the photo as given (up to 1024 px, sharper than the 640 copy) for SFace.
 */
class FaceFinder(private val assets: AssetManager, private val threads: Int = DEFAULT_THREADS) : Closeable {

    private var detector: Interpreter? = null
    private var recognizer: Interpreter? = null

    /** The detector's outputs by kind and stride, filled by each run. */
    private val outputs = HashMap<Int, ByteBuffer>()
    private val outputKinds = arrayOfNulls<Pair<Kind, Int>>(OUTPUT_COUNT)

    private enum class Kind(val width: Int) { CLS(1), OBJ(1), BOX(4), KPS(10) }

    private val detectorInput = ByteBuffer.allocateDirect(4 * YuNet.SIZE * YuNet.SIZE * 3).order(ByteOrder.nativeOrder())
    private val recognizerInput = ByteBuffer.allocateDirect(4 * FaceAlign.CROP * FaceAlign.CROP * 3).order(ByteOrder.nativeOrder())
    private val recognizerOutput = ByteBuffer.allocateDirect(4 * PRINT_SIZE).order(ByteOrder.nativeOrder())
    private val letterbox = Bitmap.createBitmap(YuNet.SIZE, YuNet.SIZE, Bitmap.Config.ARGB_8888)
    private val crop = Bitmap.createBitmap(FaceAlign.CROP, FaceAlign.CROP, Bitmap.Config.ARGB_8888)
    private val pixels = IntArray(YuNet.SIZE * YuNet.SIZE)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** Faces in [photo] (longest side up to about 1024 px), most confident first. */
    fun find(photo: Bitmap): List<FoundFace> {
        val (detector, recognizer) = load()
        val scale = YuNet.SIZE.toFloat() / max(photo.width, photo.height)

        // Letterbox: the photo shrunk into the top-left corner, black around it, as OpenCV's
        // reference was given it.
        Canvas(letterbox).apply {
            drawColor(Color.BLACK)
            drawBitmap(photo, Matrix().apply { setScale(scale, scale) }, paint)
        }
        letterbox.getPixels(pixels, 0, YuNet.SIZE, 0, 0, YuNet.SIZE, YuNet.SIZE)
        detectorInput.rewind()
        for (p in pixels) {
            // BGR, 0–255: OpenCV's blobFromImage with no scaling or swap.
            detectorInput.putFloat((p and 0xFF).toFloat())
            detectorInput.putFloat((p shr 8 and 0xFF).toFloat())
            detectorInput.putFloat((p shr 16 and 0xFF).toFloat())
        }
        detectorInput.rewind()
        outputs.values.forEach { it.rewind() }
        detector.runForMultipleInputsOutputs(arrayOf<Any>(detectorInput), outputs as Map<Int, Any>)

        val byKind = Kind.entries.associateWith { MutableList(YuNet.STRIDES.size) { FloatArray(0) } }
        for ((index, buffer) in outputs) {
            val (kind, stride) = outputKinds[index]!!
            buffer.rewind()
            val values = FloatArray(buffer.capacity() / 4).also { buffer.asFloatBuffer().get(it) }
            byKind.getValue(kind)[YuNet.STRIDES.indexOf(stride)] = values
        }
        val detected = YuNet.decode(byKind.getValue(Kind.CLS), byKind.getValue(Kind.OBJ), byKind.getValue(Kind.BOX), byKind.getValue(Kind.KPS))

        return detected
            // Too small to tell people apart; and boxes that run off the photo into the black.
            .filter { minOf(it.w, it.h) >= MIN_FACE_PIXELS }
            .map { face ->
                val inPhoto = FloatArray(10) { face.landmarks[it] / scale }
                FoundFace(
                    x = (face.x / scale / photo.width).coerceIn(0f, 1f),
                    y = (face.y / scale / photo.height).coerceIn(0f, 1f),
                    w = (face.w / scale / photo.width).coerceIn(0f, 1f),
                    h = (face.h / scale / photo.height).coerceIn(0f, 1f),
                    score = face.score,
                    print = print(recognizer, photo, inPhoto),
                )
            }
    }

    private fun print(recognizer: Interpreter, photo: Bitmap, landmarks: FloatArray): FloatArray {
        Canvas(crop).apply {
            drawColor(Color.BLACK)
            drawBitmap(photo, Matrix().apply { setValues(FaceAlign.transform(landmarks) + floatArrayOf(0f, 0f, 1f)) }, paint)
        }
        crop.getPixels(pixels, 0, FaceAlign.CROP, 0, 0, FaceAlign.CROP, FaceAlign.CROP)
        recognizerInput.rewind()
        for (i in 0 until FaceAlign.CROP * FaceAlign.CROP) {
            val p = pixels[i]
            // RGB, 0–255: OpenCV's blobFromImage(swapRB = true) with no scaling.
            recognizerInput.putFloat((p shr 16 and 0xFF).toFloat())
            recognizerInput.putFloat((p shr 8 and 0xFF).toFloat())
            recognizerInput.putFloat((p and 0xFF).toFloat())
        }
        recognizerInput.rewind()
        recognizerOutput.rewind()
        recognizer.run(recognizerInput, recognizerOutput)
        recognizerOutput.rewind()
        val print = FloatArray(PRINT_SIZE).also { recognizerOutput.asFloatBuffer().get(it) }
        val norm = sqrt(print.sumOf { (it * it).toDouble() }).toFloat()
        if (norm > 0f) for (i in print.indices) print[i] /= norm
        return print
    }

    private fun load(): Pair<Interpreter, Interpreter> {
        val d = detector ?: Interpreter(map(DETECTOR), options()).also { interpreter ->
            check(interpreter.outputTensorCount == OUTPUT_COUNT) { "YuNet has ${interpreter.outputTensorCount} outputs" }
            for (i in 0 until OUTPUT_COUNT) {
                val tensor = interpreter.getOutputTensor(i)
                // Recognised by shape: anchors per stride, values per anchor. Class and objectness
                // share a shape, and the converter keeps ONNX's order: class before objectness.
                val shape = tensor.shape()
                val stride = YuNet.STRIDES.first { YuNet.cells(it) == shape[1] }
                val kind = when (shape[2]) {
                    4 -> Kind.BOX
                    10 -> Kind.KPS
                    else -> if (outputKinds.none { it == Kind.CLS to stride }) Kind.CLS else Kind.OBJ
                }
                outputKinds[i] = kind to stride
                outputs[i] = ByteBuffer.allocateDirect(tensor.numBytes()).order(ByteOrder.nativeOrder())
            }
            detector = interpreter
        }
        val r = recognizer ?: Interpreter(map(RECOGNIZER), options()).also { recognizer = it }
        return d to r
    }

    private fun options() = Interpreter.Options().setNumThreads(threads)

    private fun map(name: String): MappedByteBuffer = assets.openFd(name).use { fd ->
        FileInputStream(fd.fileDescriptor).channel.use { it.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength) }
    }

    override fun close() {
        detector?.close()
        recognizer?.close()
        detector = null
        recognizer = null
        outputs.clear()
        outputKinds.fill(null)
        Log.i(TAG, "Face models closed")
    }

    companion object {
        private const val TAG = "FaceFinder"
        const val DETECTOR = "faces/yunet_2023mar.tflite"
        const val RECOGNIZER = "faces/sface_2021dec_int8w.tflite"
        const val PRINT_SIZE = 128
        private const val OUTPUT_COUNT = 12
        private const val DEFAULT_THREADS = 4

        /** The longest side a photo is decoded at for faces: sharp enough for small faces. */
        const val PHOTO_PIXELS = 1024

        /** Faces smaller than this, in YuNet's 640 px input, aren't printed. */
        const val MIN_FACE_PIXELS = 20f
    }
}
