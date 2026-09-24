package com.local.assistant.memory

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.local.assistant.data.db.AppDatabase
import com.local.assistant.memory.embed.Int8Vectors
import com.local.assistant.memory.retrieval.KotlinVectorIndex
import com.local.assistant.memory.retrieval.SqliteVec
import com.local.assistant.memory.retrieval.SqliteVecIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/**
 * sqlite-vec and the Kotlin fallback must be interchangeable: same neighbours, same distances
 * to the bit, same order for ties. Also checks the extension builds, loads through the bundled
 * driver and speaks the int8 KNN syntax the index uses.
 */
@RunWith(AndroidJUnit4::class)
class VectorParityTest {

    private lateinit var database: AppDatabase
    private lateinit var sql: SqliteVecIndex

    @Before
    fun setUp() {
        val version = SqliteVec.probe()
        assertEquals("v0.1.9", version)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .setDriver(SqliteVec.driver(withVectors = true))
            .setQueryCoroutineContext(Dispatchers.IO)
            .addCallback(AppDatabase.VectorTableCallback)
            .build()
        sql = SqliteVecIndex(database)
    }

    @After
    fun tearDown() = database.close()

    private fun vector(random: Random) = ByteArray(Int8Vectors.DIMENSIONS) { (random.nextInt(255) - 127).toByte() }

    @Test
    fun bothIndexesReturnTheSameNeighbours() = runBlocking {
        val random = Random(42)
        val base = (1L..5_000L).map { it to vector(random) }
        // Exact duplicates, so ties have to be ordered the same way too.
        val duplicates = (1L..50L).map { 10_000L + it to base[it.toInt()].second.copyOf() }
        val all = base + duplicates
        sql.replaceAll(all)
        val kotlin = KotlinVectorIndex { all }
        assertEquals(all.size, sql.count())

        repeat(100) { round ->
            // Half the queries sit exactly on a stored (duplicated) vector.
            val query = if (round % 2 == 0) base[round % 50 + 1].second else vector(random)
            val expected = kotlin.nearest(query, 20)
            val actual = sql.nearest(query, 20)
            assertEquals("round $round", expected, actual)
        }
    }

    @Test
    fun putAndRemoveMaintainTheSqlIndex() = runBlocking {
        val random = Random(1)
        val moved = vector(random)
        sql.replaceAll((1L..10L).map { it to vector(random) })
        sql.put(3L, moved)
        sql.put(99L, vector(random))
        sql.remove(listOf(1L, 2L))
        assertEquals(9, sql.count())
        assertEquals(3L, sql.nearest(moved, 1).single().chunkId)
        assertTrue(sql.nearest(moved, 50).none { it.chunkId in setOf(1L, 2L) })
    }

    @Test
    fun knnOverFiftyFiveThousandChunksIsFast() = runBlocking {
        val random = Random(5)
        val all = (1L..55_000L).map { it to vector(random) }
        val filled = System.nanoTime()
        sql.replaceAll(all)
        val fillMs = (System.nanoTime() - filled) / 1_000_000
        val kotlin = KotlinVectorIndex { all }
        kotlin.count() // Load outside the timing.

        val sqlTimes = mutableListOf<Long>()
        val kotlinTimes = mutableListOf<Long>()
        repeat(20) {
            val query = vector(random)
            var started = System.nanoTime()
            val a = sql.nearest(query, 40)
            sqlTimes += (System.nanoTime() - started) / 1_000_000
            started = System.nanoTime()
            val b = kotlin.nearest(query, 40)
            kotlinTimes += (System.nanoTime() - started) / 1_000_000
            assertEquals(b, a)
        }
        val report = "55k int8[256]: fill ${fillMs} ms; KNN k=40 sqlite-vec median ${sqlTimes.sorted()[10]} ms " +
            "max ${sqlTimes.max()} ms; Kotlin median ${kotlinTimes.sorted()[10]} ms max ${kotlinTimes.max()} ms"
        android.util.Log.i("VectorParityTest", report)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply { putString("report", report) })
        assertTrue("sqlite-vec KNN too slow: $report", sqlTimes.sorted()[10] < 150)
    }
}
