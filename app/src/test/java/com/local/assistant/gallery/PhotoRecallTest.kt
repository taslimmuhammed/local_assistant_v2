package com.local.assistant.gallery

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoRecallTest {

    /** The best photo first, then nine more, falling evenly to [tenth]. */
    private fun scores(best: Float, second: Float, tenth: Float) =
        listOf(best) + List(PhotoRecall.COMPARED - 1) { i -> second - (second - tenth) * i / (PhotoRecall.COMPARED - 2) }

    @Test
    fun `the user's questions of 30 Oct send their photos`() {
        // "did I ever participate in push to prod hackathon?" and "when is corniche resto's anniversary celebration".
        assertTrue(PhotoRecall.passes(scores(0.809f, 0.775f, 0.721f)))
        assertTrue(PhotoRecall.passes(scores(0.840f, 0.716f, 0.669f)))
    }

    @Test
    fun `a photo that stands out from the rest goes though it scores lower`() {
        // "which hackathons have I been to?": 0.773, 0.082 above the 10th.
        assertTrue(PhotoRecall.passes(scores(0.773f, 0.751f, 0.691f)))
    }

    @Test
    fun `general questions send none`() {
        // "translate good morning into malayalam", the highest-scoring of 30 general questions.
        assertFalse(PhotoRecall.passes(scores(0.777f, 0.776f, 0.735f)))
        // "what's a good name for a cat?", the one standing out most (0.055 above the 10th).
        assertFalse(PhotoRecall.passes(scores(0.704f, 0.704f, 0.649f)))
        // "who is the prime minister of india?"
        assertFalse(PhotoRecall.passes(scores(0.750f, 0.729f, 0.696f)))
    }

    @Test
    fun `too few photos to compare, or none, send none unless the best is close`() {
        assertFalse(PhotoRecall.passes(emptyList()))
        assertFalse(PhotoRecall.passes(listOf(0.79f, 0.60f)))
        assertTrue(PhotoRecall.passes(listOf(0.81f)))
    }
}
