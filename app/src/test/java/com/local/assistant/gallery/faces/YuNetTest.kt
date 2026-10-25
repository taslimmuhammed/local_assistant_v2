package com.local.assistant.gallery.faces

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.ln

class YuNetTest {

    private class Outputs {
        val cls = YuNet.STRIDES.map { FloatArray(YuNet.cells(it)) }
        val obj = YuNet.STRIDES.map { FloatArray(YuNet.cells(it)) }
        val box = YuNet.STRIDES.map { FloatArray(YuNet.cells(it) * 4) }
        val kps = YuNet.STRIDES.map { FloatArray(YuNet.cells(it) * 10) }

        /** A face at [row], [column] of stride index [k], [size] cells across, scoring √(c·o). */
        fun face(k: Int, row: Int, column: Int, size: Float, c: Float, o: Float) {
            val cell = row * (YuNet.SIZE / YuNet.STRIDES[k]) + column
            cls[k][cell] = c
            obj[k][cell] = o
            box[k][cell * 4 + 0] = 0.5f
            box[k][cell * 4 + 1] = 0.5f
            box[k][cell * 4 + 2] = ln(size)
            box[k][cell * 4 + 3] = ln(size)
            for (i in 0 until 10) kps[k][cell * 10 + i] = 0.25f
        }

        fun decode() = YuNet.decode(cls, obj, box, kps)
    }

    @Test
    fun `a cell's offsets become a box and landmarks in input pixels`() {
        val out = Outputs().apply { face(k = 1, row = 10, column = 20, size = 4f, c = 0.9f, o = 0.9f) }

        val face = out.decode().single()

        // Stride 16: centre (20.5, 10.5) cells = (328, 168) px; 4 cells = 64 px across.
        assertEquals(296f, face.x, 1e-3f)
        assertEquals(136f, face.y, 1e-3f)
        assertEquals(64f, face.w, 1e-3f)
        assertEquals(64f, face.h, 1e-3f)
        assertEquals(0.9f, face.score, 1e-4f)
        assertEquals((20 + 0.25f) * 16, face.landmarks[0], 1e-3f)
        assertEquals((10 + 0.25f) * 16, face.landmarks[1], 1e-3f)
    }

    @Test
    fun `weak cells are ignored and overlapping boxes keep the stronger one`() {
        val out = Outputs().apply {
            face(k = 0, row = 40, column = 40, size = 6f, c = 0.95f, o = 0.95f)
            face(k = 0, row = 40, column = 41, size = 6f, c = 0.85f, o = 0.85f) // same face, one cell over
            face(k = 2, row = 2, column = 2, size = 2f, c = 0.7f, o = 0.8f) // √0.56 ≈ 0.75 < 0.8
        }

        val faces = out.decode()

        assertEquals(1, faces.size)
        assertEquals(0.95f, faces.single().score, 1e-4f)
    }
}
