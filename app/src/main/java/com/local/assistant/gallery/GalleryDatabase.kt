package com.local.assistant.gallery

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers

/**
 * One row per photo on the phone, keyed by its MediaStore id.
 *
 * [dateModified] is MediaStore's: when it changes the photo was edited, and its vector is made
 * again. [embedding] is [com.local.assistant.memory.embed.Int8Vectors] bytes, null until indexed;
 * [modelId] says which model made it ([FAILED] when the photo could not be read). [facesModel]
 * is set once its faces have been looked for, by which face models; null until then.
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
    val facesModel: String? = null,
) {
    override fun equals(other: Any?): Boolean = other is PhotoEntity && other.mediaId == mediaId && other.dateModified == dateModified
    override fun hashCode(): Int = mediaId.hashCode()

    companion object {
        const val FAILED = "!"
    }
}

/**
 * Someone in the photos, named by the user. [isMe] marks the user themself, which is how
 * "photos of me" is answered.
 */
@Entity(tableName = "people")
data class PersonEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val isMe: Boolean = false,
    val createdAt: Long,
)

/**
 * One face in one photo. The box is in fractions of the photo's width and height; [print] is
 * SFace's 128-number print as [com.local.assistant.gallery.faces.FacePrints] bytes.
 *
 * Who it is: [personId] with [confirmed] when the user said so (named its group, or moved it),
 * [personId] without [confirmed] when grouping matched it to a named person, null when no one
 * matched. [notPersonId] is the person the user said it is not, never matched to them again.
 * [groupId] gathers faces of the same unnamed person, for the People screen to offer a name.
 * Deleting the photo deletes its faces.
 */
@Entity(
    tableName = "faces",
    foreignKeys = [
        ForeignKey(entity = PhotoEntity::class, parentColumns = ["mediaId"], childColumns = ["mediaId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = PersonEntity::class, parentColumns = ["id"], childColumns = ["personId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index("mediaId"), Index("personId"), Index("groupId")],
)
data class FaceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mediaId: Long,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val score: Float,
    val print: ByteArray,
    val personId: Long? = null,
    val confirmed: Boolean = false,
    val notPersonId: Long? = null,
    val groupId: Long? = null,
) {
    override fun equals(other: Any?): Boolean = other is FaceEntity && other.id == id
    override fun hashCode(): Int = id.hashCode()
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

    /** Photos whose faces haven't been looked for by [model], newest first. */
    @Query("SELECT * FROM photos WHERE facesModel IS NULL OR facesModel != :model ORDER BY dateTaken DESC LIMIT :limit")
    suspend fun facesPending(model: String, limit: Int): List<PhotoEntity>

    @Query("SELECT COUNT(*) FROM photos WHERE facesModel IS NULL OR facesModel != :model")
    suspend fun facesPendingCount(model: String): Int

    @Query("UPDATE photos SET facesModel = :model WHERE mediaId = :mediaId")
    suspend fun setFacesModel(mediaId: Long, model: String?)

    @Query("UPDATE photos SET facesModel = NULL")
    suspend fun forgetFacesModel()
}

@Dao
interface FaceDao {

    @Insert
    suspend fun insert(faces: List<FaceEntity>)

    @Query("DELETE FROM faces WHERE mediaId = :mediaId")
    suspend fun deleteForPhoto(mediaId: Long)

    @Query("SELECT * FROM faces")
    suspend fun all(): List<FaceEntity>

    @Query("SELECT * FROM faces WHERE id = :id")
    suspend fun byId(id: Long): FaceEntity?

    @Query("SELECT COUNT(*) FROM faces")
    suspend fun count(): Int

    @Query("UPDATE faces SET personId = :personId, groupId = :groupId WHERE id = :id AND confirmed = 0")
    suspend fun assign(id: Long, personId: Long?, groupId: Long?)

    /** The user says faces [ids] are [personId]: kept through every regrouping. */
    @Query("UPDATE faces SET personId = :personId, confirmed = 1, groupId = NULL, notPersonId = NULL WHERE id IN (:ids)")
    suspend fun confirm(ids: List<Long>, personId: Long)

    /** The user says face [id] isn't [personId]. */
    @Query("UPDATE faces SET personId = NULL, confirmed = 0, groupId = NULL, notPersonId = :personId WHERE id = :id")
    suspend fun reject(id: Long, personId: Long)

    @Query("SELECT id FROM faces WHERE groupId = :groupId")
    suspend fun inGroup(groupId: Long): List<Long>

    @Query("SELECT * FROM faces WHERE personId = :personId")
    suspend fun ofPerson(personId: Long): List<FaceEntity>

    /** Photos with [personId] in them, newest first. */
    @Query("SELECT DISTINCT f.mediaId FROM faces f JOIN photos p ON p.mediaId = f.mediaId WHERE f.personId = :personId ORDER BY p.dateTaken DESC")
    suspend fun photosOf(personId: Long): List<Long>

    @Query("UPDATE faces SET personId = :into WHERE personId = :from")
    suspend fun movePerson(from: Long, into: Long)

    @Query("DELETE FROM faces")
    suspend fun clear()

    @Insert
    suspend fun insertPerson(person: PersonEntity): Long

    @Query("SELECT * FROM people ORDER BY isMe DESC, name COLLATE NOCASE")
    suspend fun people(): List<PersonEntity>

    @Query("SELECT * FROM people WHERE id = :id")
    suspend fun person(id: Long): PersonEntity?

    @Query("UPDATE people SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    /** At most one person is the user. */
    @Query("UPDATE people SET isMe = (id = :id)")
    suspend fun setMe(id: Long)

    @Query("DELETE FROM people WHERE id = :id")
    suspend fun deletePerson(id: Long)

    @Query("DELETE FROM people")
    suspend fun clearPeople()
}

/**
 * The photo index, in a file of its own, so it never needs a migration of the chats-and-memory
 * database. Version 1 held only what can be rebuilt from the photos; version 2 adds the people
 * the user named, which can't be, so from 2 on every schema change gets a real migration.
 */
@Database(entities = [PhotoEntity::class, PersonEntity::class, FaceEntity::class], version = 2, exportSchema = true)
abstract class GalleryDatabase : RoomDatabase() {

    abstract fun photoDao(): PhotoDao

    abstract fun faceDao(): FaceDao

    companion object {
        const val FILE_NAME = "gallery.db"

        /**
         * Faces and the people they belong to. The photos and their vectors stay as they are:
         * indexing them again would take the phone about ten minutes.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE photos ADD COLUMN facesModel TEXT")
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `people` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                        "`isMe` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)",
                )
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `faces` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `mediaId` INTEGER NOT NULL, " +
                        "`x` REAL NOT NULL, `y` REAL NOT NULL, `w` REAL NOT NULL, `h` REAL NOT NULL, `score` REAL NOT NULL, " +
                        "`print` BLOB NOT NULL, `personId` INTEGER, `confirmed` INTEGER NOT NULL, `notPersonId` INTEGER, `groupId` INTEGER, " +
                        "FOREIGN KEY(`mediaId`) REFERENCES `photos`(`mediaId`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                        "FOREIGN KEY(`personId`) REFERENCES `people`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )",
                )
                connection.execSQL("CREATE INDEX IF NOT EXISTS `index_faces_mediaId` ON `faces` (`mediaId`)")
                connection.execSQL("CREATE INDEX IF NOT EXISTS `index_faces_personId` ON `faces` (`personId`)")
                connection.execSQL("CREATE INDEX IF NOT EXISTS `index_faces_groupId` ON `faces` (`groupId`)")
            }
        }

        fun build(context: Context): GalleryDatabase =
            Room.databaseBuilder(context, GalleryDatabase::class.java, FILE_NAME)
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                // No destructive fallback any more: from version 2 it holds names the user gave.
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
