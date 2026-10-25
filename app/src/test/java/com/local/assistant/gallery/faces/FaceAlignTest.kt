package com.local.assistant.gallery.faces

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class FaceAlignTest {

    /** Expected values from Umeyama's method in NumPy, which matched OpenCV's alignCrop. */
    @Test
    fun `the fit matches Umeyama's least squares`() {
        val tilted = floatArrayOf(412.3f, 288.1f, 498.7f, 301.4f, 452.2f, 350.9f, 418.8f, 402.6f, 487.1f, 412.0f)
        assertArrayEquals(floatArrayOf(0.37592f, 0.02725f, -124.13619f, -0.02725f, 0.37592f, -47.67998f), FaceAlign.transform(tilted), 2e-4f)

        val small = floatArrayOf(100f, 100f, 140f, 95f, 121f, 125f, 104f, 150f, 138f, 147f)
        assertArrayEquals(floatArrayOf(0.81902f, -0.03857f, -37.98906f, 0.03857f, 0.81902f, -33.81793f), FaceAlign.transform(small), 2e-4f)
    }

    @Test
    fun `landmarks already in place map to themselves`() {
        val target = floatArrayOf(38.2946f, 51.6963f, 73.5318f, 51.5014f, 56.0252f, 71.7366f, 41.5493f, 92.3655f, 70.7299f, 92.2041f)
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f), FaceAlign.transform(target), 1e-4f)
    }
}
