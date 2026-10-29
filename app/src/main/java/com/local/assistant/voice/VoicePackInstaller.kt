package com.local.assistant.voice

import android.content.Context
import com.local.assistant.model.ProgressThrottle
import com.local.assistant.model.Transfer
import com.local.assistant.model.TransferKind
import com.local.assistant.model.fetchResumable
import com.local.assistant.model.formatBytes
import com.local.assistant.model.sha256Of
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Fetches and unpacks one natural voice [pack] into the app's files: resumable, checked
 * against the published digest, and unpacked beside the final place so a half-written voice
 * is never mistaken for one ([installed] only ever points at a complete one).
 */
class VoicePackInstaller(
    context: Context,
    val pack: VoicePack,
    private val scope: CoroutineScope,
    /** The pack was just installed. */
    private val onInstalled: () -> Unit = {},
) {

    /** What [download] is doing. */
    sealed interface Progress {
        data class Downloading(val transfer: Transfer) : Progress
        data class Unpacking(val fraction: Float) : Progress
    }

    private val root = File(context.filesDir, VoicePack.DIRECTORY).apply { mkdirs() }
    private val installedDir = File(root, pack.name)
    private val unpackingDir = File(root, pack.name + UNPACKING_SUFFIX)
    private val archive = File(root, pack.name + ARCHIVE_SUFFIX)

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val _installed = MutableStateFlow(readInstalled())

    /** The unpacked voice, or null while there is none. */
    val installed: StateFlow<File?> = _installed.asStateFlow()

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var job: Job? = null

    val isBusy: Boolean get() = job?.isActive == true

    /** Bytes already fetched by an interrupted download. */
    fun partialBytes(): Long = archive.length()

    fun download() {
        if (isBusy || _installed.value != null) return
        _error.value = null
        job = scope.launch(Dispatchers.IO) {
            val self = coroutineContext[Job]
            try {
                fetch(self)
                unpack(self)
                archive.delete()
                _installed.value = installedDir
                onInstalled()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.message ?: "The voice did not download"
            } finally {
                unpackingDir.deleteRecursively()
                _progress.value = null
            }
        }
    }

    /** Stops a download, keeping what has arrived for next time. */
    fun cancel() {
        job?.cancel()
    }

    /** Removes the voice and any partial download. */
    suspend fun delete() = withContext(Dispatchers.IO) {
        job?.cancel()
        job?.join()
        _installed.value = null
        installedDir.deleteRecursively()
        unpackingDir.deleteRecursively()
        archive.delete()
    }

    private fun fetch(self: Job?) {
        if (archive.length() == pack.archiveBytes) return // fetched before an unpack was cut short
        val usable = root.usableSpace
        val needed = pack.requiredFreeBytes - archive.length()
        if (usable < needed) throw IOException("Not enough storage: ${formatBytes(needed)} needed, ${formatBytes(usable)} free")
        val throttle = ProgressThrottle { done, total, rate ->
            _progress.value = Progress.Downloading(Transfer(TransferKind.DOWNLOAD, done, total, rate))
        }
        _progress.value = Progress.Downloading(Transfer(TransferKind.DOWNLOAD, archive.length(), pack.archiveBytes))
        fetchResumable(http, pack.url, archive, pack.archiveBytes) { done, total ->
            self?.ensureActive()
            throttle.report(done, total)
        }
    }

    private fun unpack(self: Job?) {
        _progress.value = Progress.Unpacking(0f)
        if (archive.length() != pack.archiveBytes || sha256Of(archive) != pack.archiveSha256) {
            archive.delete()
            throw IOException("The download was corrupted. Please try again.")
        }
        unpackingDir.deleteRecursively()
        var shown = 0
        archive.inputStream().use { input ->
            pack.unpack(input, unpackingDir) { read ->
                self?.ensureActive()
                val percent = (read * 100 / pack.archiveBytes).toInt()
                if (percent != shown) {
                    shown = percent
                    _progress.value = Progress.Unpacking(percent / 100f)
                }
            }
        }
        pack.problem(unpackingDir)?.let { throw IOException(it) }
        installedDir.deleteRecursively()
        if (!unpackingDir.renameTo(installedDir)) throw IOException("Could not move the voice into place")
    }

    private fun readInstalled(): File? {
        // Left by an unpack the app didn't live to finish.
        unpackingDir.deleteRecursively()
        return installedDir.takeIf { it.isDirectory && pack.problem(it) == null }
    }

    private companion object {
        const val UNPACKING_SUFFIX = ".unpacking"
        const val ARCHIVE_SUFFIX = ".tar.bz2.part"
    }
}
