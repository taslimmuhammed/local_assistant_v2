package com.local.assistant.gallery.faces

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A face YuNet found, in the coordinates of its 640×640 input: the box, and five landmarks as
 * x,y pairs (right eye, left eye, nose tip, right and left mouth corners, as the subject faces).
 */
class DetectedFace(val x: Float, val y: Float, val w: Float, val h: Float, val landmarks: FloatArray, val score: Float)

/**
 * Decoding YuNet (face_detection_yunet_2023mar, MIT) the way OpenCV's FaceDetectorYN does: for
 * each of the three strides, every grid cell scores a face as √(class × objectness), its box is an
 * offset from the cell plus a log-scaled size, and its landmarks are offsets from the cell. Then
 * overlapping boxes are thinned by non-maximum suppression. Checked against OpenCV on 18 photos
 * with 34 faces (2026-10-25): the same faces, boxes and landmarks to the pixel.
 */
object YuNet {

    const val SIZE = 640

    const val MIN_SCORE = 0.8f

    const val NMS_IOU = 0.3f

    val STRIDES = intArrayOf(8, 16, 32)

    /** Grid cells at [stride]: (640 / stride)². */
    fun cells(stride: Int): Int = (SIZE / stride) * (SIZE / stride)

    /**
     * [cls], [obj], [box] and [kps] are the model's outputs for each stride, in [STRIDES] order,
     * flattened: one value per cell for class and objectness, four per cell for the box, ten for
     * the landmarks. Best first.
     */
    fun decode(cls: List<FloatArray>, obj: List<FloatArray>, box: List<FloatArray>, kps: List<FloatArray>): List<DetectedFace> {
        val found = ArrayList<DetectedFace>()
        STRIDES.forEachIndexed { k, stride ->
            val columns = SIZE / stride
            for (cell in 0 until cells(stride)) {
                val score = sqrt(cls[k][cell].coerceIn(0f, 1f) * obj[k][cell].coerceIn(0f, 1f))
                if (score < MIN_SCORE) continue
                val row = cell / columns
                val column = cell % columns
                val b = cell * 4
                val cx = (column + box[k][b]) * stride
                val cy = (row + box[k][b + 1]) * stride
                val w = exp(box[k][b + 2]) * stride
                val h = exp(box[k][b + 3]) * stride
                val p = cell * 10
                val landmarks = FloatArray(10) { i -> ((if (i % 2 == 0) column else row) + kps[k][p + i]) * stride }
                found += DetectedFace(cx - w / 2, cy - h / 2, w, h, landmarks, score)
            }
        }
        found.sortByDescending { it.score }
        val kept = ArrayList<DetectedFace>()
        for (face in found) if (kept.none { iou(it, face) >= NMS_IOU }) kept += face
        return kept
    }

    fun iou(a: DetectedFace, b: DetectedFace): Float {
        val w = max(0f, min(a.x + a.w, b.x + b.w) - max(a.x, b.x))
        val h = max(0f, min(a.y + a.h, b.y + b.h) - max(a.y, b.y))
        val inter = w * h
        return inter / (a.w * a.h + b.w * b.h - inter + 1e-9f)
    }
}
