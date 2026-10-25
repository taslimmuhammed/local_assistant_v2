package com.local.assistant.gallery.faces

/**
 * Where SFace expects a face: its five landmarks moved onto ArcFace's standard positions in a
 * 112×112 crop, by the similarity transform (scale, rotation, shift) that fits them best.
 *
 * In two dimensions the least-squares fit has a closed form, the same answer Umeyama's method
 * (and OpenCV's FaceRecognizerSF.alignCrop) gives: with both point sets centred,
 * a = Σ(s·d) / Σ|s|² and b = Σ(s×d) / Σ|s|², the transform being
 * x' = a·x − b·y + tx, y' = b·x + a·y + ty.
 */
object FaceAlign {

    const val CROP = 112

    /** Eyes, nose tip, mouth corners in the 112×112 crop SFace was trained on. */
    private val TARGET = floatArrayOf(
        38.2946f, 51.6963f,
        73.5318f, 51.5014f,
        56.0252f, 71.7366f,
        41.5493f, 92.3655f,
        70.7299f, 92.2041f,
    )

    /**
     * The transform taking [landmarks] (five x,y pairs) to the crop, as the six values of an
     * affine matrix in [android.graphics.Matrix]'s order: scaleX, skewX, transX, skewY, scaleY,
     * transY.
     */
    fun transform(landmarks: FloatArray): FloatArray {
        require(landmarks.size == 10) { "Expected five landmarks, got ${landmarks.size / 2}" }
        var sx = 0.0; var sy = 0.0; var dx = 0.0; var dy = 0.0
        for (i in 0 until 5) {
            sx += landmarks[2 * i]; sy += landmarks[2 * i + 1]
            dx += TARGET[2 * i]; dy += TARGET[2 * i + 1]
        }
        sx /= 5; sy /= 5; dx /= 5; dy /= 5
        var dot = 0.0; var cross = 0.0; var norm = 0.0
        for (i in 0 until 5) {
            val px = landmarks[2 * i] - sx; val py = landmarks[2 * i + 1] - sy
            val qx = TARGET[2 * i] - dx; val qy = TARGET[2 * i + 1] - dy
            dot += px * qx + py * qy
            cross += px * qy - py * qx
            norm += px * px + py * py
        }
        if (norm == 0.0) return floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f)
        val a = dot / norm
        val b = cross / norm
        val tx = dx - (a * sx - b * sy)
        val ty = dy - (b * sx + a * sy)
        return floatArrayOf(a.toFloat(), (-b).toFloat(), tx.toFloat(), b.toFloat(), a.toFloat(), ty.toFloat())
    }
}
