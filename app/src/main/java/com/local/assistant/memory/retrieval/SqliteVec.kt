package com.local.assistant.memory.retrieval

import android.util.Log
import androidx.room.RoomDatabase
import androidx.room.immediateTransaction
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.local.assistant.memory.embed.Int8Vectors

/**
 * sqlite-vec, built from source for this app (`src/main/cpp`) and loaded into the bundled SQLite.
 *
 * The driver loads every registered extension into every connection it opens, and a failure
 * there fails the open, so the extension is registered only after [probe] has loaded it
 * successfully on a scratch database. A device where it will not load keeps a working database
 * and searches with [KotlinVectorIndex] instead.
 */
object SqliteVec {

    private const val TAG = "SqliteVec"
    private const val LIBRARY = "sqlite_vec"
    private const val FILE = "libsqlite_vec.so"
    private const val ENTRY_POINT = "sqlite3_vec_init"

    const val TABLE = "vec_chunks"

    /**
     * Created on open rather than by a migration, because it exists only where the extension
     * loads. Nothing refers to it from a trigger: if the extension were missing, any trigger
     * touching it would make every write to `chunks` fail.
     */
    const val CREATE_TABLE =
        "CREATE VIRTUAL TABLE IF NOT EXISTS $TABLE USING vec0(" +
            "chunk_id INTEGER PRIMARY KEY, embedding int8[${Int8Vectors.DIMENSIONS}] distance_metric=cosine)"

    /** The extension's version if it loads into the bundled SQLite here, else null. */
    fun probe(): String? = try {
        // Loaded by name first so the linker has it; SQLite's dlopen of the bare name then finds
        // it in the app's namespace even when native libraries stay inside the APK.
        System.loadLibrary(LIBRARY)
        val driver = BundledSQLiteDriver().apply { addExtension(FILE, ENTRY_POINT) }
        driver.open(":memory:").use { connection ->
            connection.prepare("SELECT vec_version()").use { if (it.step()) it.getText(0) else null }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "sqlite-vec unavailable; using the Kotlin index", t)
        null
    }

    /** A driver for the app database, with sqlite-vec when [withVectors]. */
    fun driver(withVectors: Boolean): BundledSQLiteDriver =
        BundledSQLiteDriver().apply { if (withVectors) addExtension(FILE, ENTRY_POINT) }

    fun createTable(connection: SQLiteConnection) = connection.execSQL(CREATE_TABLE)
}

/** [VectorIndex] on sqlite-vec's `vec0`, inside the app database. */
class SqliteVecIndex(private val database: RoomDatabase) : VectorIndex {

    override suspend fun nearest(query: ByteArray, k: Int): List<VectorHit> {
        if (k <= 0) return emptyList()
        return database.useReaderConnection { connection ->
            connection.usePrepared(
                "SELECT chunk_id, distance FROM ${SqliteVec.TABLE} " +
                    "WHERE embedding MATCH vec_int8(?) AND k = ? ORDER BY distance",
            ) { statement ->
                statement.bindBlob(1, query)
                statement.bindLong(2, k.toLong())
                buildList {
                    while (statement.step()) add(VectorHit(statement.getLong(0), statement.getDouble(1).toFloat()))
                }
            }
        // vec0 accepts nothing but "ORDER BY distance" on a KNN query; ties are ordered here.
        }.sortedWith(compareBy<VectorHit> { it.distance }.thenBy { it.chunkId })
    }

    override suspend fun put(chunkId: Long, vector: ByteArray) {
        require(vector.size == Int8Vectors.DIMENSIONS)
        database.useWriterConnection { connection ->
            // vec0 has no upsert.
            connection.usePrepared("DELETE FROM ${SqliteVec.TABLE} WHERE chunk_id = ?") {
                it.bindLong(1, chunkId)
                it.step()
            }
            connection.usePrepared("INSERT INTO ${SqliteVec.TABLE}(chunk_id, embedding) VALUES (?, vec_int8(?))") {
                it.bindLong(1, chunkId)
                it.bindBlob(2, vector)
                it.step()
            }
        }
    }

    override suspend fun remove(chunkIds: Collection<Long>) {
        if (chunkIds.isEmpty()) return
        database.useWriterConnection { connection ->
            for (id in chunkIds) {
                connection.usePrepared("DELETE FROM ${SqliteVec.TABLE} WHERE chunk_id = ?") {
                    it.bindLong(1, id)
                    it.step()
                }
            }
        }
    }

    override suspend fun count(): Int = database.useReaderConnection { connection ->
        connection.usePrepared("SELECT COUNT(*) FROM ${SqliteVec.TABLE}") { if (it.step()) it.getLong(0).toInt() else 0 }
    }

    override suspend fun replaceAll(vectors: List<Pair<Long, ByteArray>>) {
        // One transaction: tens of thousands of autocommitted inserts would take minutes.
        database.useWriterConnection { connection ->
            connection.immediateTransaction {
                usePrepared("DELETE FROM ${SqliteVec.TABLE}") { it.step() }
                usePrepared("INSERT INTO ${SqliteVec.TABLE}(chunk_id, embedding) VALUES (?, vec_int8(?))") { statement ->
                    for ((id, vector) in vectors) {
                        statement.bindLong(1, id)
                        statement.bindBlob(2, vector)
                        statement.step()
                        statement.reset()
                    }
                }
            }
        }
    }

    override suspend fun prune() {
        database.useWriterConnection { connection ->
            connection.usePrepared(
                "DELETE FROM ${SqliteVec.TABLE} WHERE chunk_id NOT IN (SELECT id FROM chunks WHERE embedding IS NOT NULL)",
            ) { it.step() }
        }
    }
}
