package com.local.assistant.files

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.MediaStore.Files.FileColumns
import android.util.Log
import com.local.assistant.gallery.PhotoPeriod
import com.local.assistant.privacy.LogText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Files on the phone found by name, when they're asked for: a query of Android's own index of
 * shared storage (MediaStore), which with All files access lists every file, plus a walk of the
 * folders where documents arrive (Download, Documents, WhatsApp's and Telegram's documents), in
 * case the index missed some. Nothing of this app's own is built or kept: names are matched as
 * asked ([FileNames]), and a file is read only when asked about ([DocumentFiles]).
 *
 * Photos and videos are left out: find_photos has them, by what they show.
 */
class FileFinder(private val context: Context, private val clock: () -> Long = System::currentTimeMillis) {

    /** A file found. [path] is where it is on the phone; [mediaId] its row in MediaStore, when it has one. */
    data class Found(
        val name: String,
        /** Its folder from the storage root, `Download/Bank/`. */
        val folder: String,
        val path: String?,
        val mediaId: Long?,
        val sizeBytes: Long,
        val modifiedMs: Long,
        val mime: String?,
    ) {
        val entry: FileNames.Entry get() = FileNames.Entry(name, folder, modifiedMs)
    }

    /** A folder found by its name: its path from the storage root (`Download/Bank/`) and what's in it. */
    data class Folder(val name: String, val path: String, val files: Int, val latestMs: Long)

    /** The files that best answer [asked] (a name, a kind or both), best first. */
    suspend fun find(asked: String, type: FileNames.Type, period: PhotoPeriod.Range?, limit: Int = LIMIT): List<Found> = withContext(Dispatchers.IO) {
        val started = android.os.SystemClock.elapsedRealtime()
        val words = FileNames.searchWords(asked)
        val (indexed, walked) = coroutineScope {
            val fromIndex = async { runCatching { indexed(words, type, period) }.onFailure { Log.w(TAG, "MediaStore query failed", it) }.getOrDefault(emptyList()) }
            val fromFolders = async { walk() }
            fromIndex.await() to fromFolders.await()
        }
        val known = indexed.mapNotNullTo(HashSet()) { it.path }
        val extra = walked.filter { it.path !in known && type.allows(it.name) && (period == null || it.modifiedMs in period) }
        val ranked = FileNames.rank(words, indexed + extra, clock(), limit) { it.entry }
        Log.i(TAG, "Found ${ranked.size} for \"${LogText.of(asked)}\" ($type): ${indexed.size} from the index, ${extra.size} more from the folders, ${android.os.SystemClock.elapsedRealtime() - started} ms")
        ranked
    }

    /** Folders named like [asked], best first. */
    suspend fun folders(asked: String, limit: Int = LIMIT): List<Folder> = withContext(Dispatchers.IO) {
        val words = FileNames.searchWords(asked)
        if (words.isEmpty()) return@withContext emptyList()
        val paths = HashMap<String, Pair<Int, Long>>()
        fun add(folder: String, modifiedMs: Long) {
            // Each folder on the way counts: `Download/Bank/2024/` is in `Download/Bank/` too.
            val segments = folder.split('/').filter { it.isNotEmpty() }
            for (depth in 1..segments.size) {
                val path = segments.take(depth).joinToString("/", postfix = "/")
                val (count, latest) = paths[path] ?: (0 to 0L)
                paths[path] = (count + 1) to maxOf(latest, modifiedMs)
            }
        }
        runCatching { folderRows(words) { folder, modifiedMs -> add(folder, modifiedMs) } }.onFailure { Log.w(TAG, "MediaStore folder query failed", it) }
        walk().forEach { add(it.folder, it.modifiedMs) }
        val folders = paths.map { (path, stats) ->
            val segments = path.trimEnd('/').split('/')
            Folder(segments.last(), path, stats.first, stats.second)
        }
        // A folder by its own name, not its parent's: "bank" finds `Documents/Bank/`, not everything in it.
        FileNames.rank(words, folders, clock(), limit) { FileNames.Entry(it.name, "", it.latestMs) }
    }

    private fun indexed(words: List<String>, type: FileNames.Type, period: PhotoPeriod.Range?): List<Found> {
        val where = ArrayList<String>()
        val args = ArrayList<String>()
        where += "${FileColumns.MEDIA_TYPE} NOT IN (${FileColumns.MEDIA_TYPE_IMAGE}, ${FileColumns.MEDIA_TYPE_VIDEO})"
        where += "${FileColumns.MIME_TYPE} IS NOT NULL"
        where += "${FileColumns.SIZE} > 0"
        where += "${FileColumns.DISPLAY_NAME} NOT LIKE '.%'"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) where += NOT_HIDDEN
        val likes = FileNames.likeParts(words)
        if (likes.isNotEmpty()) {
            val folderToo = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            where += likes.joinToString(" OR ", "(", ")") {
                if (folderToo) "${FileColumns.DISPLAY_NAME} LIKE ? OR ${FileColumns.RELATIVE_PATH} LIKE ?" else "${FileColumns.DISPLAY_NAME} LIKE ?"
            }
            likes.forEach { like ->
                args += "%$like%"
                if (folderToo) args += "%$like%"
            }
        }
        if (type.extensions.isNotEmpty()) {
            where += type.extensions.joinToString(" OR ", "(", ")") { "${FileColumns.DISPLAY_NAME} LIKE ?" }
            type.extensions.forEach { args += "%.$it" }
        }
        if (period != null) {
            where += "${FileColumns.DATE_MODIFIED} >= ? AND ${FileColumns.DATE_MODIFIED} < ?"
            args += (period.start / 1000).toString()
            args += ((period.end + 999) / 1000).toString()
        }
        val projection = buildList {
            add(FileColumns._ID)
            add(FileColumns.DISPLAY_NAME)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(FileColumns.RELATIVE_PATH)
            @Suppress("DEPRECATION") add(FileColumns.DATA)
            add(FileColumns.SIZE)
            add(FileColumns.DATE_MODIFIED)
            add(FileColumns.MIME_TYPE)
        }.toTypedArray()
        val found = ArrayList<Found>()
        query(projection, where.joinToString(" AND "), args.toTypedArray(), MAX_ROWS)?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(FileColumns._ID)
            val name = cursor.getColumnIndexOrThrow(FileColumns.DISPLAY_NAME)
            val relative = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) cursor.getColumnIndex(FileColumns.RELATIVE_PATH) else -1
            @Suppress("DEPRECATION") val data = cursor.getColumnIndex(FileColumns.DATA)
            val size = cursor.getColumnIndexOrThrow(FileColumns.SIZE)
            val modified = cursor.getColumnIndexOrThrow(FileColumns.DATE_MODIFIED)
            val mime = cursor.getColumnIndexOrThrow(FileColumns.MIME_TYPE)
            while (cursor.moveToNext() && found.size < MAX_ROWS) {
                val path = if (data >= 0) cursor.getString(data) else null
                val fileName = cursor.getString(name) ?: path?.substringAfterLast('/') ?: continue
                val folder = (if (relative >= 0) cursor.getString(relative) else null) ?: path?.let(::folderOf).orEmpty()
                found += Found(fileName, folder, path, cursor.getLong(id), cursor.getLong(size), cursor.getLong(modified) * 1000, cursor.getString(mime))
            }
        }
        return found
    }

    /** Every file's folder (photos' too: a folder of photos is a folder) whose path has one of [words]. */
    private fun folderRows(words: List<String>, each: (String, Long) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val likes = FileNames.likeParts(words)
        val where = "$NOT_HIDDEN AND " + likes.joinToString(" OR ", "(", ")") { "${FileColumns.RELATIVE_PATH} LIKE ?" }
        query(arrayOf(FileColumns.RELATIVE_PATH, FileColumns.DATE_MODIFIED), where, likes.map { "%$it%" }.toTypedArray(), MAX_FOLDER_ROWS)?.use { cursor ->
            var rows = 0
            while (cursor.moveToNext() && rows++ < MAX_FOLDER_ROWS) {
                val folder = cursor.getString(0) ?: continue
                each(folder, cursor.getLong(1) * 1000)
            }
        }
    }

    private fun query(projection: Array<String>, where: String, args: Array<String>, limit: Int): Cursor? {
        val resolver = context.contentResolver
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bundle = Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, where)
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
                putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(FileColumns.DATE_MODIFIED))
                putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
                putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
            }
            resolver.query(COLLECTION, projection, bundle, null)
        } else {
            resolver.query(COLLECTION, projection, where, args, "${FileColumns.DATE_MODIFIED} DESC")
        }
    }

    /**
     * The files in the folders documents arrive in, walked a few levels down, kept 10 minutes.
     * Empty without All files access: the folders can't be read.
     */
    private fun walk(): List<Found> {
        walked?.let { (at, files) -> if (clock() - at < WALK_KEEP_MS) return files }
        if (!FileAccess.granted(context)) return emptyList()
        val started = android.os.SystemClock.elapsedRealtime()
        @Suppress("DEPRECATION") val root = Environment.getExternalStorageDirectory()
        val files = ArrayList<Found>()
        fun visit(dir: File, depth: Int) {
            val entries = dir.listFiles() ?: return
            for (entry in entries) {
                if (files.size >= MAX_WALKED) return
                if (entry.name.startsWith('.')) continue
                if (entry.isDirectory) {
                    if (depth < WALK_DEPTH) visit(entry, depth + 1)
                } else if (FileNames.extensionOf(entry.name) !in MEDIA_EXTENSIONS && entry.length() > 0) {
                    files += Found(entry.name, folderOf(entry.path), entry.path, null, entry.length(), entry.lastModified(), null)
                }
            }
        }
        WALKED_ROOTS.map { File(root, it) }.filter { it.isDirectory }.forEach { visit(it, 0) }
        walked = clock() to files
        Log.i(TAG, "Walked ${files.size} files in ${android.os.SystemClock.elapsedRealtime() - started} ms")
        return files
    }

    @Volatile
    private var walked: Pair<Long, List<Found>>? = null

    companion object {
        private const val TAG = "FileFinder"

        /** Files offered for one question: the model reads their names, the chip shows them. */
        const val LIMIT = 6

        private const val MAX_ROWS = 400
        private const val MAX_FOLDER_ROWS = 5_000
        private const val MAX_WALKED = 20_000
        private const val WALK_DEPTH = 4
        private const val WALK_KEEP_MS = 10 * 60_000L

        /** Not in a hidden folder (`.thumbnails/`, `.DownloadWhatsAppStatus/`), at the top or further down. */
        private val NOT_HIDDEN = "${FileColumns.RELATIVE_PATH} NOT LIKE '.%' AND ${FileColumns.RELATIVE_PATH} NOT LIKE '%/.%'"

        private val COLLECTION: Uri =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL) else MediaStore.Files.getContentUri("external")

        /** Where documents arrive, from the storage root. */
        private val WALKED_ROOTS = listOf(
            Environment.DIRECTORY_DOWNLOADS,
            Environment.DIRECTORY_DOCUMENTS,
            "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Documents",
            "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Documents",
            "WhatsApp/Media/WhatsApp Documents",
            "Android/media/org.telegram.messenger/Telegram/Telegram Documents",
            "Telegram/Telegram Documents",
        )

        /** Photos and videos, find_photos' and not this. */
        private val MEDIA_EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "bmp", "dng", "raw",
            "mp4", "mkv", "mov", "webm", "avi", "3gpp", "m4v",
        )

        /** `Download/Bank/` for `/storage/emulated/0/Download/Bank/x.pdf`. */
        fun folderOf(path: String): String {
            @Suppress("DEPRECATION") val root = Environment.getExternalStorageDirectory().path.trimEnd('/') + "/"
            val parent = path.substringBeforeLast('/', "")
            if (parent.isEmpty()) return ""
            val relative = if (parent.startsWith(root)) parent.removePrefix(root) else parent.removePrefix("/storage/").substringAfter('/')
            return if (relative.isEmpty()) "" else "$relative/"
        }

        /** The MediaStore row of a found file, for opening it in another app. */
        fun uriOf(found: Found): Uri? = found.mediaId?.let { ContentUris.withAppendedId(COLLECTION, it) }
    }
}
