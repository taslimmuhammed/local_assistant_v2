package com.local.assistant.voice

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/**
 * One natural voice download: a .tar.bz2 from sherpa-onnx's tts-models release, of which the
 * phone keeps what [keepFile] keeps, under `files/voice/<name>`. The voices made from it are in
 * [NaturalVoices].
 */
class VoicePack(
    /** The archive's name and its top folder. */
    val name: String,
    /** What settings calls it. */
    val title: String,
    /** Of the archive as published; checked after the download. */
    val archiveBytes: Long,
    val archiveSha256: String,
    /** Unpacked, what [keepFile] lets through. */
    val installedBytes: Long,
    /** Files a usable voice has, with their exact size where a different one means a different model. */
    private val required: Map<String, Long?>,
    /** Whether a file, by its path inside the top folder, is kept. */
    private val keepFile: (String) -> Boolean,
) {

    val url: String get() = "$RELEASE/$name.tar.bz2"

    /** The archive and the unpacked files side by side, plus room to spare. */
    val requiredFreeBytes: Long get() = archiveBytes + installedBytes + 100L * 1024 * 1024

    /** Where an archive entry goes inside the installed voice, relative; null to leave it out. */
    fun keep(entry: String): String? {
        val inArchive = entry.removePrefix("./")
        if (!inArchive.startsWith("$name/")) return null
        val path = inArchive.removePrefix("$name/")
        if (path.isEmpty() || path.startsWith("/") || path.split('/').any { it == ".." || it == "." }) return null
        return path.takeIf(keepFile)
    }

    /**
     * Unpacks [archive] into [destination], which must not exist yet, keeping what [keep] keeps.
     * [onProgress] gets archive bytes read so far.
     */
    fun unpack(archive: InputStream, destination: File, onProgress: (Long) -> Unit = {}) {
        if (destination.exists()) throw IOException("${destination.name} is already there")
        val counted = CountingStream(BufferedInputStream(archive, BUFFER_BYTES), onProgress)
        TarArchiveInputStream(BZip2CompressorInputStream(counted, true)).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                if (!entry.isFile) continue
                val relative = keep(entry.name) ?: continue
                val out = File(destination, relative)
                out.parentFile?.mkdirs()
                out.outputStream().use { tar.copyTo(it, BUFFER_BYTES) }
            }
        }
    }

    /** What makes an unpacked voice usable; the problem, or null when it is. */
    fun problem(directory: File): String? {
        for ((path, size) in required) {
            val file = File(directory, path)
            if (!file.isFile) return "Part of the voice is missing"
            if (size != null && file.length() != size) return "The voice model is not the expected one"
        }
        return null
    }

    override fun toString() = name

    private class CountingStream(input: InputStream, private val onProgress: (Long) -> Unit) : FilterInputStream(input) {
        private var count = 0L

        override fun read(): Int = super.read().also { if (it >= 0) report(1L) }

        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) report(it.toLong()) }

        override fun skip(n: Long): Long = super.skip(n).also { if (it > 0) report(it) }

        private fun report(n: Long) {
            count += n
            onProgress(count)
        }
    }

    companion object {
        private const val RELEASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models"
        private const val BUFFER_BYTES = 1 shl 16

        /** Under the app's files. */
        const val DIRECTORY = "voice"

        const val KOKORO_MODEL = "model.onnx"
        const val KOKORO_VOICES = "voices.bin"
        const val KOKORO_TOKENS = "tokens.txt"
        const val KOKORO_LEXICON = "lexicon-us-en.txt"
        const val ESPEAK_DATA = "espeak-ng-data"
        private val OTHER_DICTIONARY = Regex("$ESPEAK_DATA/[^/]+_dict")

        /**
         * Kokoro-82M v1.0, full precision. The int8 build is a third of the size but 2.5× slower
         * and doesn't get faster with more threads (Mac M2: 1.5× real time on 4 threads, against
         * 3.4× for this one). The archive has 54 voices and Chinese support; the phone keeps the
         * model, the voice table and what English needs, 345 MB of the 400 unpacked. Published
         * 2026-10-26.
         */
        val KOKORO = VoicePack(
            name = "kokoro-multi-lang-v1_0",
            title = "Kokoro",
            archiveBytes = 349_886_476L,
            archiveSha256 = "60703707bc05ccd48d60e1eb723f38b07d6e474795a0b6a588d7220400e0f7a4",
            installedBytes = 362_000_000L,
            required = mapOf(
                KOKORO_MODEL to 325_560_556L,
                KOKORO_VOICES to null,
                KOKORO_TOKENS to null,
                KOKORO_LEXICON to null,
                "$ESPEAK_DATA/en_dict" to null,
            ),
            keepFile = { path ->
                path in setOf(KOKORO_MODEL, KOKORO_VOICES, KOKORO_TOKENS, KOKORO_LEXICON, "LICENSE") ||
                    path.startsWith("$ESPEAK_DATA/") && path != "$ESPEAK_DATA/" &&
                    (!OTHER_DICTIONARY.matches(path) || path == "$ESPEAK_DATA/en_dict")
            },
        )

        val ALL = listOf(KOKORO)
    }
}
