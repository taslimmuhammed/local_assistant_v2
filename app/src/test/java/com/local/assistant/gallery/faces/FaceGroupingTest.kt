package com.local.assistant.gallery.faces

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class FaceGroupingTest {

    private val random = Random(7)

    private fun unit(): FloatArray {
        val v = FloatArray(FacePrints.SIZE) { random.nextFloat() - 0.5f }
        val n = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        return FloatArray(v.size) { v[it] / n }
    }

    /** A print [similarity] away from [base], in a fresh direction. */
    private fun near(base: FloatArray, similarity: Float): FloatArray {
        val other = unit()
        val along = other.indices.sumOf { (other[it] * base[it]).toDouble() }.toFloat()
        for (i in other.indices) other[i] -= along * base[i]
        val n = sqrt(other.sumOf { (it * it).toDouble() }).toFloat()
        val angle = acos(similarity)
        return FloatArray(base.size) { cos(angle) * base[it] + sin(angle) * other[it] / n }
    }

    private var nextId = 1L

    private fun face(print: FloatArray, personId: Long? = null, confirmed: Boolean = false, notPersonId: Long? = null) =
        FaceGrouping.Face(nextId++, print, personId, confirmed, notPersonId, quality = 1f)

    @Test
    fun `a face like someone the user named joins them`() {
        val amma = unit()
        val named = listOf(face(near(amma, 0.9f), personId = 1, confirmed = true), face(near(amma, 0.9f), personId = 1, confirmed = true))
        val newPhoto = face(near(amma, 0.9f))
        val stranger = face(unit())

        val placed = FaceGrouping.place(named + newPhoto + stranger)

        assertEquals(1L, placed.getValue(newPhoto.id).personId)
        assertNull(placed.getValue(stranger.id).personId)
        // Confirmed faces are the user's word: not placed again.
        assertEquals(setOf(newPhoto.id, stranger.id), placed.keys)
    }

    @Test
    fun `small faces join someone named, but never start or join a group`() {
        val amma = unit()
        val stranger = unit()
        val named = listOf(face(near(amma, 0.95f), personId = 1, confirmed = true))
        fun small(print: FloatArray) = FaceGrouping.Face(nextId++, print, null, false, null, quality = 0.01f, side = 0.03f)
        val smallAmma = small(near(amma, 0.9f))
        // Small faces of someone unnamed: alike enough to group, if they were bigger.
        val smallStrangers = List(3) { small(near(stranger, 0.95f)) }

        val placed = FaceGrouping.place(named + smallAmma + smallStrangers)

        assertEquals(FaceGrouping.Placement(1, null), placed.getValue(smallAmma.id))
        smallStrangers.forEach { assertEquals(FaceGrouping.Placement(null, null), placed.getValue(it.id)) }
    }

    @Test
    fun `a face the user said isn't them is never matched to them again`() {
        val amma = unit()
        val named = listOf(face(near(amma, 0.9f), personId = 1, confirmed = true))
        val rejected = face(near(amma, 0.95f), notPersonId = 1)

        assertNull(FaceGrouping.place(named + rejected).getValue(rejected.id).personId)
    }

    @Test
    fun `unnamed faces of the same person share a group, other people get their own, and loners none`() {
        val a = unit()
        val b = unit()
        val aFaces = List(4) { face(near(a, 0.92f)) }
        val bFaces = List(3) { face(near(b, 0.92f)) }
        val loner = face(unit())

        val placed = FaceGrouping.place(aFaces + bFaces + loner)

        val aGroups = aFaces.map { placed.getValue(it.id).groupId }.toSet()
        val bGroups = bFaces.map { placed.getValue(it.id).groupId }.toSet()
        assertEquals(1, aGroups.size)
        assertEquals(1, bGroups.size)
        assertNotEquals(aGroups, bGroups)
        assertNull(placed.getValue(loner.id).groupId)
    }
}
