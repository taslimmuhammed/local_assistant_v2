package com.local.assistant.model

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.local.assistant.data.prefs.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/** A model file that is present on disk and ready to load. */
data class InstalledModel(val file: File, val sizeBytes: Long)

enum class TransferKind { DOWNLOAD, IMPORT }

/** Progress of an in-flight download or import. */
data class Transfer(
    val kind: TransferKind,
    val bytesDone: Long,
    val bytesTotal: Long,
    val bytesPerSecond: Long = 0,
) {
    val fraction: Float get() = if (bytesTotal > 0) (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f) else 0f

    /** Seconds remaining, or null while the rate is still unknown. */
    val etaSeconds: Long?
        get() = if (bytesPerSecond > 0 && bytesTotal > bytesDone) {
            (bytesTotal - bytesDone) / bytesPerSecond
        } else {
            null
        }
}

/**
 * Owns the model file on disk: downloading it, importing one the user already has, and
 * reporting what is currently installed.
 *
 * Downloads resume from wherever they stopped, which matters a lot for a ~3 GB file.
 */
class ModelManager(
    private val context: Context,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
) {

    private val modelsDir: File = File(context.filesDir, "models").apply { mkdirs() }

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val _installed = MutableStateFlow(readInstalled())
    val installed: StateFlow<InstalledModel?> = _installed.asStateFlow()

    private val _transfer = MutableStateFlow<Transfer?>(null)
    val transfer: StateFlow<Transfer?> = _transfer.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var job: Job? = null

    val isBusy: Boolean get() = job?.isActive == true

    /** Bytes already fetched by a previous, interrupted download. */
    fun partialBytes(): Long = partFile().length()

    fun clearError() {
        _error.value = null
    }

    fun download() = start(TransferKind.DOWNLOAD) { onProgress ->
        downloadInto(destinationFile(), onProgress)
    }

    fun importFrom(uri: Uri) = start(TransferKind.IMPORT) { onProgress ->
        copyInto(uri, destinationFile(), onProgress)
    }

    fun cancel() {
        job?.cancel()
    }

    /** Removes the installed model and any partial download, freeing the disk space. */
    suspend fun deleteInstalled() = withContext(Dispatchers.IO) {
        cancel()
        destinationFile().delete()
        partFile().delete()
        settings.modelPath = null
        _installed.value = null
    }

    private fun start(kind: TransferKind, block: suspend ((Long, Long) -> Unit) -> Unit) {
        if (isBusy) return
        _error.value = null
        job = scope.launch(Dispatchers.IO) {
            val throttle = ProgressThrottle { done, total, rate ->
                _transfer.value = Transfer(kind, done, total, rate)
            }
            try {
                _transfer.value = Transfer(kind, 0, ModelCatalog.SIZE_BYTES)
                block { done, total -> throttle.report(done, total) }
                val file = destinationFile()
                settings.modelPath = file.absolutePath
                _installed.value = InstalledModel(file, file.length())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.message ?: "Transfer failed"
            } finally {
                _transfer.value = null
            }
        }
    }

    // --- download -------------------------------------------------------------------------

    private fun downloadInto(destination: File, onProgress: (Long, Long) -> Unit) {
        val part = partFile()
        ensureSpace(ModelCatalog.REQUIRED_FREE_BYTES - part.length())

        var alreadyHave = part.length()
        val request = Request.Builder()
            .url(ModelCatalog.DOWNLOAD_URL)
            .apply { if (alreadyHave > 0) header("Range", "bytes=$alreadyHave-") }
            .build()

        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Download failed: HTTP ${response.code}")
            }
            // A 200 to a ranged request means the server ignored the range; start over.
            val resuming = response.code == 206
            if (!resuming) {
                part.delete()
                alreadyHave = 0
            }
            val body = response.body ?: throw IOException("Empty response body")
            val total = if (body.contentLength() > 0) {
                alreadyHave + body.contentLength()
            } else {
                ModelCatalog.SIZE_BYTES
            }

            RandomAccessFile(part, "rw").use { out ->
                out.seek(alreadyHave)
                body.byteStream().copyTo(
                    write = { buffer, length -> out.write(buffer, 0, length) },
                    startingAt = alreadyHave,
                    total = total,
                    onProgress = onProgress,
                )
            }
        }

        if (!part.renameTo(destination)) {
            throw IOException("Could not move the downloaded model into place")
        }
        onProgress(destination.length(), destination.length())
    }

    // --- import ---------------------------------------------------------------------------

    private fun copyInto(uri: Uri, destination: File, onProgress: (Long, Long) -> Unit) {
        val total = sizeOf(uri) ?: ModelCatalog.SIZE_BYTES
        ensureSpace(total + FREE_SPACE_HEADROOM)

        val part = partFile()
        part.delete()
        val input: InputStream = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Could not open the selected file")

        input.use { source ->
            part.outputStream().use { out ->
                source.copyTo(
                    write = { buffer, length -> out.write(buffer, 0, length) },
                    startingAt = 0,
                    total = total,
                    onProgress = onProgress,
                )
            }
        }

        destination.delete()
        if (!part.renameTo(destination)) {
            throw IOException("Could not move the imported model into place")
        }
        onProgress(destination.length(), destination.length())
    }

    private fun sizeOf(uri: Uri): Long? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) {
                    cursor.getLong(index)
                } else {
                    null
                }
            }

    // --- shared plumbing ------------------------------------------------------------------

    private inline fun InputStream.copyTo(
        write: (ByteArray, Int) -> Unit,
        startingAt: Long,
        total: Long,
        onProgress: (Long, Long) -> Unit,
    ) {
        val buffer = ByteArray(BUFFER_BYTES)
        var done = startingAt
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            write(buffer, read)
            done += read
            onProgress(done, total)
        }
    }

    private fun ensureSpace(needed: Long) {
        val usable = modelsDir.usableSpace
        if (usable < needed) {
            throw IOException(
                "Not enough storage: ${formatBytes(needed)} needed, ${formatBytes(usable)} free",
            )
        }
    }

    private fun destinationFile() = File(modelsDir, ModelCatalog.FILE_NAME)

    private fun partFile() = File(modelsDir, ModelCatalog.FILE_NAME + PART_SUFFIX)

    private fun readInstalled(): InstalledModel? {
        val path = settings.modelPath
        val expected = destinationFile()

        // A model installed by an earlier build can be a different file than the one we now
        // expect. Loading a mismatched .litertlm does not fail cleanly - it starts and then
        // emits raw vocab tokens - so treat anything unexpected as "not installed".
        if (path != null && File(path).name != ModelCatalog.FILE_NAME) {
            settings.modelPath = null
        }

        // Drop leftovers from a previous model name so they stop occupying gigabytes.
        modelsDir.listFiles()?.forEach { file ->
            if (file.name != expected.name && file.name != expected.name + PART_SUFFIX) {
                file.delete()
            }
        }

        if (settings.modelPath == null) return null
        return if (expected.isFile && expected.length() > 0) {
            InstalledModel(expected, expected.length())
        } else {
            null
        }
    }

    /** Coalesces per-buffer progress into at most one UI update per [INTERVAL_MS]. */
    private class ProgressThrottle(private val emit: (Long, Long, Long) -> Unit) {
        private var lastAt = 0L
        private var lastBytes = 0L

        fun report(done: Long, total: Long) {
            val now = System.currentTimeMillis()
            if (lastAt == 0L) {
                lastAt = now
                lastBytes = done
                emit(done, total, 0)
                return
            }
            val elapsed = now - lastAt
            if (elapsed < INTERVAL_MS) return
            val rate = (done - lastBytes) * 1000 / elapsed
            lastAt = now
            lastBytes = done
            emit(done, total, rate)
        }

        companion object {
            private const val INTERVAL_MS = 250L
        }
    }

    companion object {
        private const val PART_SUFFIX = ".part"
        private const val BUFFER_BYTES = 1 shl 16
        private const val FREE_SPACE_HEADROOM = 256L * 1024 * 1024
    }
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format("%.2f GB", bytes.toDouble() / (1L shl 30))
    bytes >= 1L shl 20 -> String.format("%.0f MB", bytes.toDouble() / (1L shl 20))
    bytes >= 1L shl 10 -> String.format("%.0f KB", bytes.toDouble() / (1L shl 10))
    else -> "$bytes B"
}
