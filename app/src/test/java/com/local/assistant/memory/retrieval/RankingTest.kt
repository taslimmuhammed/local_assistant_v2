package com.local.assistant.memory.retrieval

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RankingTest {

    @Test
    fun rrfScoresAreReciprocalRanksWithK60() {
        val fused = RankFusion.fuse(listOf(listOf(1L, 2L), listOf(2L, 3L)), recency = { 0 })
        val scores = fused.associate { it.id to it.score }
        assertEquals(1.0 / 61, scores.getValue(1), 1e-12)
        assertEquals(1.0 / 62 + 1.0 / 61, scores.getValue(2), 1e-12)
        assertEquals(1.0 / 62, scores.getValue(3), 1e-12)
        assertEquals("found by both lists beats first in one", listOf(2L, 1L, 3L), fused.map { it.id })
    }

    @Test
    fun agreementOutranksASingleTopPlace() {
        // 7 is only first for keywords; 9 is fifth in both.
        val keyword = listOf(7L, 1L, 2L, 3L, 9L)
        val vector = listOf(4L, 5L, 6L, 8L, 9L)
        assertEquals(9L, RankFusion.fuse(listOf(keyword, vector), recency = { 0 }).first().id)
    }

    @Test
    fun tiesGoToTheNewer() {
        val fused = RankFusion.fuse(listOf(listOf(10L), listOf(20L)), recency = { id -> if (id == 20L) 5 else 1 })
        assertEquals(listOf(20L, 10L), fused.map { it.id })
    }

    @Test
    fun emptyListsFuseToNothing() {
        assertTrue(RankFusion.fuse(listOf(emptyList(), emptyList()), recency = { 0 }).isEmpty())
    }

    /** 'pcnalx' for two phrases, one column, over [rows] rows. */
    private fun matchinfo(rows: Int, average: Int, length: Int, vararg phrase: Triple<Int, Int, Int>): IntArray =
        intArrayOf(phrase.size, 1, rows, average, length) + phrase.flatMap { listOf(it.first, it.second, it.third) }

    @Test
    fun bm25PrefersTheRarerTerm() {
        // Same row length; one row hits a word in 2 of 1000 rows, the other a word in 600.
        val rare = Bm25.score(matchinfo(1000, 20, 20, Triple(1, 2, 2), Triple(0, 900, 600)))
        val common = Bm25.score(matchinfo(1000, 20, 20, Triple(0, 2, 2), Triple(1, 900, 600)))
        assertTrue(rare.score > common.score * 3)
        assertArrayEquals(booleanArrayOf(true, false), rare.termHits)
        assertArrayEquals(booleanArrayOf(false, true), common.termHits)
    }

    @Test
    fun bm25FavoursShortRowsForTheSameHits() {
        val short = Bm25.score(matchinfo(100, 40, 10, Triple(1, 5, 5)))
        val long = Bm25.score(matchinfo(100, 40, 160, Triple(1, 5, 5)))
        assertTrue(short.score > long.score)
    }

    @Test
    fun matchinfoBlobIsNativeOrderInts() {
        val values = intArrayOf(2, 1, 50, 12, 9, 1, 3, 2, 0, 7, 4)
        val blob = ByteBuffer.allocate(values.size * 4).order(ByteOrder.nativeOrder()).apply { values.forEach { putInt(it) } }.array()
        assertArrayEquals(values, Bm25.parse(blob))
    }
}
