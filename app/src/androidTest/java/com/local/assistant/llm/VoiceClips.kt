package com.local.assistant.llm

import android.os.SystemClock
import com.local.assistant.voice.Transcriber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/** Recorded clips (16 kHz mono WAV) put through the phone's recogniser as a recording is, for the evals. */
object VoiceClips {

    private const val CHUNK = 3_200
    private const val CHUNK_MS = 100L

    /** A clip fed as a recording comes, a tenth of a second at a time: the words, and how long after the last of it. */
    suspend fun transcribe(transcriber: Transcriber, clip: File): Pair<String?, Long> {
        val pcm = pcmOf(clip.readBytes())
        val session = withContext(Dispatchers.Main) { transcriber.start() } ?: error("no recogniser session")
        var at = 0
        while (at < pcm.size) {
            val n = minOf(CHUNK, pcm.size - at)
            session.feed(pcm.copyOfRange(at, at + n), n)
            at += n
            delay(CHUNK_MS)
        }
        val ended = SystemClock.elapsedRealtime()
        val words = withContext(Dispatchers.Main) { session.finish() }
        return words to SystemClock.elapsedRealtime() - ended
    }

    /** How long the clip lasts, at 16 kHz, 16-bit mono. */
    fun durationMs(clip: File): Long = pcmOf(clip.readBytes()).size / 32L

    /** The samples of a WAV file: what follows its "data" chunk's header, whatever chunks come before. */
    fun pcmOf(wav: ByteArray): ByteArray {
        var at = 12
        while (at + 8 <= wav.size) {
            val id = String(wav, at, 4, Charsets.US_ASCII)
            val size = (wav[at + 4].toInt() and 0xff) or ((wav[at + 5].toInt() and 0xff) shl 8) or
                ((wav[at + 6].toInt() and 0xff) shl 16) or ((wav[at + 7].toInt() and 0xff) shl 24)
            if (id == "data") return wav.copyOfRange(at + 8, minOf(wav.size, at + 8 + size))
            at += 8 + size + (size and 1)
        }
        error("No data chunk")
    }
}
