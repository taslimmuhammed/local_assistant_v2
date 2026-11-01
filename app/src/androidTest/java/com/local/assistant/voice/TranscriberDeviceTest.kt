package com.local.assistant.voice

import android.speech.SpeechRecognizer
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.local.assistant.appContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Whether the phone writes voice messages down ([Transcriber]), how well, and how soon after the
 * user stops: recorded clips are fed in at the pace a recording comes, and what comes back is
 * logged with how long it took after the last of the audio.
 *
 *     adb shell am instrument -w -e eval transcribe -e clips routing-voice \
 *         -e class com.local.assistant.voice.TranscriberDeviceTest com.local.assistant.test/androidx.test.runner.AndroidJUnitRunner
 *
 * `-e clips` is a folder of 16 kHz mono WAVs under the app's external files. Results go to logcat
 * (tag TranscriberEval) and `Android/data/<app>/files/transcriber-eval.txt`.
 */
@RunWith(AndroidJUnit4::class)
class TranscriberDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val out by lazy { File(context.getExternalFilesDir(null), "transcriber-eval.txt").apply { writeText("") } }

    @Test
    fun writesClipsDown() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Opt-in: -e eval transcribe", args.getString("eval") == "transcribe")
        val transcriber = context.appContainer.transcriber
        report("on-device recogniser: ${SpeechRecognizer.isOnDeviceRecognitionAvailable(context)}; usable here: ${transcriber.available}")
        if (!transcriber.available) return@runBlocking
        val folder = File(context.getExternalFilesDir(null), args.getString("clips") ?: "routing-voice")
        val clips = folder.listFiles { f -> f.name.endsWith(".wav") }.orEmpty().sortedBy { it.nameWithoutExtension.toIntOrNull() ?: Int.MAX_VALUE }
        report("${clips.size} clips in ${folder.name}")
        val limit = args.getString("limit")?.toInt() ?: clips.size
        for (clip in clips.take(limit)) {
            val pcm = clip.readBytes().let { it.copyOfRange(WAV_HEADER, it.size) }
            val session = withContext(Dispatchers.Main) { transcriber.start() } ?: error("no session")
            // As a recording comes: a tenth of a second at a time.
            var at = 0
            while (at < pcm.size) {
                val n = minOf(CHUNK, pcm.size - at)
                session.feed(pcm.copyOfRange(at, at + n), n)
                at += n
                delay(CHUNK_MS)
            }
            val ended = System.currentTimeMillis()
            val words = withContext(Dispatchers.Main) { session.finish() }
            report("${clip.nameWithoutExtension} | %.1f s of audio | words after %d ms | ${words ?: "(nothing)"}".format(pcm.size / 32_000.0, System.currentTimeMillis() - ended))
        }
    }

    private fun report(line: String) {
        Log.i(TAG, line)
        out.appendText("$line\n")
    }

    private companion object {
        const val TAG = "TranscriberEval"
        const val WAV_HEADER = 44

        /** A tenth of a second of 16 kHz mono 16-bit audio. */
        const val CHUNK = 3_200
        const val CHUNK_MS = 100L
    }
}
