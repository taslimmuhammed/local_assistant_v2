package com.local.assistant.memory.retrieval

import kotlin.math.ln

/**
 * Reciprocal rank fusion: each ranked list gives an item 1 / ([K] + rank), and the sums decide.
 * It needs no calibration between a keyword score and a cosine, which live on unrelated scales;
 * only positions matter. Ties go to the newer item.
 */
object RankFusion {

    const val K = 60

    data class Fused(val id: Long, val score: Double)

    /** [rankings] best first; [recency] is larger for newer items. */
    fun fuse(rankings: List<List<Long>>, recency: (Long) -> Long): List<Fused> {
        val scores = linkedMapOf<Long, Double>()
        for (ranking in rankings) {
            ranking.forEachIndexed { index, id ->
                scores[id] = (scores[id] ?: 0.0) + 1.0 / (K + index + 1)
            }
        }
        return scores.map { (id, score) -> Fused(id, score) }
            .sortedWith(compareByDescending<Fused> { it.score }.thenByDescending { recency(it.id) })
    }
}

/**
 * BM25 over FTS4's `matchinfo(…, 'pcnalx')`, which SQLite fills in for each matching row: how
 * often each query term occurs in this row and in the whole table, and how long this row is
 * against the average. FTS4 has no ranking of its own; this is the standard recipe from its
 * documentation.
 */
object Bm25 {

    private const val K1 = 1.2
    private const val B = 0.75

    /** One matching row: its score and which query terms (in query order) it contains. */
    data class Scored(val score: Double, val termHits: BooleanArray)

    fun score(matchinfo: IntArray): Scored {
        val phrases = matchinfo[0]
        val columns = matchinfo[1]
        val rows = matchinfo[2].toDouble()
        val averageLength = DoubleArray(columns) { matchinfo[3 + it].toDouble() }
        val length = DoubleArray(columns) { matchinfo[3 + columns + it].toDouble() }
        val x = 3 + 2 * columns
        var score = 0.0
        val hits = BooleanArray(phrases)
        for (phrase in 0 until phrases) {
            for (column in 0 until columns) {
                val base = x + 3 * (phrase * columns + column)
                val inRow = matchinfo[base].toDouble()
                val rowsWithHit = matchinfo[base + 2].toDouble()
                if (inRow <= 0) continue
                hits[phrase] = true
                val idf = ln((rows - rowsWithHit + 0.5) / (rowsWithHit + 0.5) + 1.0)
                val norm = if (averageLength[column] > 0) length[column] / averageLength[column] else 1.0
                score += idf * inRow * (K1 + 1) / (inRow + K1 * (1 - B + B * norm))
            }
        }
        return Scored(score, hits)
    }

    /** matchinfo's blob: unsigned 32-bit integers in the machine's (little-endian) byte order. */
    fun parse(blob: ByteArray): IntArray {
        val buffer = java.nio.ByteBuffer.wrap(blob).order(java.nio.ByteOrder.nativeOrder())
        return IntArray(blob.size / 4) { buffer.getInt(it * 4) }
    }
}
