package com.local.assistant.files

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.local.assistant.files.DocumentText.Read
import com.local.assistant.files.DocumentText.Why
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

/** Reading a found or attached document's text, and opening a file or folder in the app for it. */
object DocumentFiles {

    /** The text of the file at [file] (or its MediaStore row when the path can't be read). */
    fun read(context: Context, found: FileFinder.Found): Read {
        val file = found.path?.let(::File)?.takeIf { it.canRead() }
        val uri = FileFinder.uriOf(found)
        return when {
            file != null -> read(context, file, found.name, found.mime)
            uri != null -> read(context, uri, found.name, found.mime)
            else -> Read.Unreadable(Why.MISSING)
        }
    }

    fun read(context: Context, file: File, name: String = file.name, mime: String? = null): Read =
        read(context, name, mime, { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY) }, { file.inputStream() })

    fun read(context: Context, uri: Uri, name: String, mime: String?): Read =
        read(
            context, name, mime,
            { context.contentResolver.openFileDescriptor(uri, "r") ?: throw FileNotFoundException("No descriptor") },
            { context.contentResolver.openInputStream(uri) ?: throw FileNotFoundException("No stream") },
        )

    private fun read(context: Context, name: String, mime: String?, descriptor: () -> ParcelFileDescriptor, stream: () -> InputStream): Read = try {
        when (val format = DocumentText.formatOf(name, mime)) {
            DocumentText.Format.PDF -> PdfText.read(descriptor, File(context.cacheDir, PAGES_DIR))
            DocumentText.Format.OTHER -> Read.Unreadable(Why.NOT_A_DOCUMENT)
            else -> DocumentText.read(format, stream())
        }
    } catch (e: FileNotFoundException) {
        Read.Unreadable(Why.MISSING)
    } catch (e: SecurityException) {
        Read.Unreadable(Why.MISSING)
    } catch (e: IOException) {
        Log.w(TAG, "Couldn't read a document", e)
        Read.Unreadable(Why.DAMAGED)
    } catch (e: OutOfMemoryError) {
        Read.Unreadable(Why.TOO_BIG)
    }

    /**
     * Opens [found] in the app the phone uses for its kind. A copy goes to the other app, through
     * this app's file provider (which hands out only `files/shared/`): copied when tapped, so a
     * chip from last month still opens today's file.
     */
    fun open(context: Context, path: String?, name: String, mime: String?, mediaId: Long?): Boolean {
        val type = mime ?: mimeOf(name)
        val source = path?.let(::File)?.takeIf { it.canRead() }
        val uri = try {
            when {
                source != null && source.length() <= MAX_COPY_BYTES -> shareable(context, source, name)
                mediaId != null -> FileFinder.uriOf(FileFinder.Found(name, "", path, mediaId, 0, 0, mime))
                else -> null
            }
        } catch (e: IOException) {
            Log.w(TAG, "Couldn't copy a file to open", e)
            null
        } ?: return false
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, type)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return start(context, view)
    }

    /** Opens the Files app at [folder] (`Download/Bank/`), or the Files app when it can't go there. */
    fun openFolder(context: Context, folder: String): Boolean {
        val documentId = "primary:" + folder.trimEnd('/')
        val uri = DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, documentId)
        val atFolder = Intent(Intent.ACTION_VIEW).setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        if (start(context, atFolder)) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return start(context, Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_FILES).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        return false
    }

    fun mimeOf(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(FileNames.extensionOf(name)) ?: "application/octet-stream"

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

    /** A copy in `files/shared/open/`, kept a day, older copies dropped each time. */
    private fun shareable(context: Context, source: File, name: String): Uri {
        val dir = File(context.filesDir, "shared/open").apply { mkdirs() }
        val cutoff = System.currentTimeMillis() - KEEP_MS
        dir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.deleteRecursively() }
        // In a folder of its own, so the other app sees the file's own name.
        val copy = File(File(dir, System.nanoTime().toString()).apply { mkdirs() }, name.replace('/', '_'))
        source.copyTo(copy, overwrite = true)
        return FileProvider.getUriForFile(context, "${context.packageName}.share", copy)
    }

    private const val TAG = "DocumentFiles"
    private const val PAGES_DIR = "doc-pages"
    private const val KEEP_MS = 24 * 60 * 60_000L
    private const val MAX_COPY_BYTES = 200L * 1024 * 1024
    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
}
