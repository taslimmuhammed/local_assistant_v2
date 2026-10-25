package com.local.assistant.gallery

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers

/**
 * One row per photo on the phone, keyed by its MediaStore id.
 *
 * [dateModified] is MediaStore's: when it changes the photo was edited, and its vector is made
 * again. [embedding] is [com.local.assistant.memory.embed.Int8Vectors] bytes, null until indexed;
 * [modelId] says which model made it ([FAILED] when the photo could not be read).
 */
@Entity(tableName = "photos")
data class PhotoEntity(
    @PrimaryKey val mediaId: Long,
    val dateTaken: Long,
    val dateModified: Long,
    val width: Int,
    val height: Int,
    val embedding: ByteArray? = null,
    val modelId: String? = null,
) {
    override fun equals(other: Any?): Boolean = other is PhotoEntity && other.mediaId == mediaId && other.dateModified == dateModified
    override fun hashCode(): Int = mediaId.hashCode()

    companion object {
        const val FAILED = "!"
    }
}

/** What a scan of MediaStore needs to compare against: id and when it last changed. */
data class PhotoStamp(val mediaId: Long, val dateModified: Long)

/** A stored vector, for the search scan. */
data class PhotoVector(val mediaId: Long, val embedding: ByteArray)

@Dao
interface PhotoDao {

    @Query("SELECT mediaId, dateModified FROM photos")
    suspend fun stamps(): List<PhotoStamp>

    /** New photos, and edited ones with their vector cleared. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(photos: List<PhotoEntity>)

    @Query("DELETE FROM photos WHERE mediaId IN (:ids)")
    suspend fun delete(ids: List<Long>)

    /** Newest first, so the photos people look for most are searchable soonest. */
    @Query("SELECT * FROM photos WHERE modelId IS NULL OR modelId NOT IN (:modelId, '!') ORDER BY dateTaken DESC LIMIT :limit")
    suspend fun pending(modelId: String, limit: Int): List<PhotoEntity>

    @Query("SELECT COUNT(*) FROM photos WHERE modelId IS NULL OR modelId NOT IN (:modelId, '!')")
    suspend fun pendingCount(modelId: String): Int

    @Query("SELECT COUNT(*) FROM photos")
    suspend fun count(): Int

    @Query("UPDATE photos SET embedding = :embedding, modelId = :modelId WHERE mediaId = :mediaId")
    suspend fun setEmbedding(mediaId: Long, embedding: ByteArray?, modelId: String)

    @Query("SELECT mediaId, embedding FROM photos WHERE modelId = :modelId AND embedding IS NOT NULL")
    suspend fun vectors(modelId: String): List<PhotoVector>

    @Query("SELECT mediaId FROM photos ORDER BY dateTaken DESC LIMIT :limit")
    suspend fun newest(limit: Int): List<Long>

    @Query("DELETE FROM photos")
    suspend fun clear()
}

/**
 * The photo index, in a file of its own: everything in it can be rebuilt from the phone's photos,
 * so it never needs a migration of the chats-and-memory database, and dropping it loses nothing.
 */
@Database(entities = [PhotoEntity::class], version = 1, exportSchema = true)
abstract class GalleryDatabase : RoomDatabase() {

    abstract fun photoDao(): PhotoDao

    companion object {
        const val FILE_NAME = "gallery.db"

        fun build(context: Context): GalleryDatabase =
            Room.databaseBuilder(context, GalleryDatabase::class.java, FILE_NAME)
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                // Derived data: a schema change rebuilds it rather than migrating it.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
