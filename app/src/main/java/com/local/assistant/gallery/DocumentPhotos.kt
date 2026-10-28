package com.local.assistant.gallery

import com.local.assistant.memory.embed.Int8Vectors

/**
 * Which photos are documents or cards (ID cards, licences, passports, bank cards, certificates),
 * to keep them hidden until the user authenticates. Told by the photo model itself: a photo's
 * vector against a few descriptions of documents, the highest similarity being its score, as a
 * search for "driving licence" would rank it. Worked out once per photo while indexing, kept in
 * the index, and compared with [THRESHOLD] when shown, so the threshold can move without
 * scoring again.
 */
object DocumentPhotos {

    /** What a document looks like, in the photo model's own search words. */
    val DESCRIPTIONS = listOf(
        "ID card",
        "driving licence card",
        "passport page",
        "credit card or debit card",
        "certificate or mark sheet",
        "official document with printed text",
    )

    /** Which model and descriptions a score came from; a change of either scores again. */
    val SCORED_BY = "${GalleryModel.MODEL_ID}#docs1"

    /** A photo at or above this is hidden. See DocumentCalibrationTest. */
    const val THRESHOLD = 0.72f

    /** [photo]'s score: its best similarity to any of [descriptions] (quantized vectors). */
    fun score(photo: ByteArray, descriptions: List<ByteArray>): Float {
        val norm = Int8Vectors.squaredNorm(photo)
        if (norm == 0) return 0f
        return descriptions.maxOf { d ->
            Int8Vectors.similarity(Int8Vectors.cosineDistance(Int8Vectors.dot(photo, 0, d), norm, Int8Vectors.squaredNorm(d)))
        }
    }
}
