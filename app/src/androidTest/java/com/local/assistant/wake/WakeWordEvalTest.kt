package com.local.assistant.wake

import android.annotation.SuppressLint
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.local.assistant.MainActivity
import com.local.assistant.media.wavHeader
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The wake word's two listeners on the phone, with the models as the APK has them: how often each
 * hears the phrase in test clips (and either, as [WakeDetector] does), how often they wake on the
 * near-misses, and what listening costs in processor time. The clips are 16-bit mono WAVs pushed
 * beforehand to `Android/data/<app>/files/wake/{pos,near,user}/` (`user`: the user's own
 * recordings, deleted after the run); results go to `wake-word.txt` beside them.
 *
 *     adb shell am instrument -w -e eval wake-word \
 *         -e class com.local.assistant.wake.WakeWordEvalTest \
 *         com.local.assistant.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class WakeWordEvalTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root by lazy { File(context.getExternalFilesDir(null), "wake") }
    private val results by lazy { File(context.getExternalFilesDir(null), "wake-word.txt") }

    @Test
    fun clips() {
        assumeTrue("opt-in: -e eval wake-word", InstrumentationRegistry.getArguments().getString("eval") == "wake-word")
        report("--- ${WakeWord.PHRASE}")
        val spotter = WakeSpotter(context.assets)
        val model = WakeModel(context.assets)
        try {
            var audioSeconds = 0.0
            var spotterNanos = 0L
            var modelNanos = 0L
            for (set in listOf("pos", "near", "user")) {
                val files = File(root, set).listFiles { f -> f.name.endsWith(".wav") }.orEmpty().sortedBy { it.name }
                if (files.isEmpty()) continue
                val bySpotter = mutableSetOf<String>()
                val byModel = mutableSetOf<String>()
                for (file in files) {
                    val audio = padded(read16kMono(file))
                    audioSeconds += audio.size / WakeWord.SAMPLE_RATE.toDouble()
                    var at = 0
                    while (at < audio.size) {
                        val end = minOf(at + WakeWord.CHUNK_SAMPLES, audio.size)
                        val chunk = audio.copyOfRange(at, end)
                        var t = Debug.threadCpuTimeNanos()
                        if (spotter.accept(FloatArray(chunk.size) { chunk[it] / 32768f })) bySpotter += file.name
                        spotterNanos += Debug.threadCpuTimeNanos() - t
                        t = Debug.threadCpuTimeNanos()
                        if (model.accept(chunk, chunk.size)) byModel += file.name
                        modelNanos += Debug.threadCpuTimeNanos() - t
                        at = end
                    }
                    spotter.reset()
                    model.reset()
                }
                val either = bySpotter + byModel
                report("$set: spotter ${bySpotter.size}, model ${byModel.size}, either ${either.size} of ${files.size}")
                if (set == "near") report("near woke: spotter ${bySpotter.take(10)}, model ${byModel.take(10)}")
                else report("$set missed by both: ${files.map { it.name }.filterNot { it in either }.take(40)}")
            }
            for ((name, nanos) in listOf("spotter" to spotterNanos, "model" to modelNanos)) {
                val share = nanos / 1e9 / audioSeconds
                report("$name processor time: %.1f ms per second of audio, %.2f%% of one core".format(share * 1000, share * 100))
            }
        } finally {
            spotter.release()
            model.close()
        }
    }

    /**
     * Through the air: the Mac plays phrases through its speakers while this records from the
     * phone's microphone for `-e seconds` (default 300), with both listeners listening. The app is
     * brought to the front first, so Android lets it record and the background listener stands
     * aside. Each wake is reported with its time from the start and who heard it; the recording is
     * saved as `wake-live.wav` for scoring again on the Mac, and should be deleted afterwards.
     */
    @SuppressLint("MissingPermission")
    @Test
    fun live() {
        assumeTrue("opt-in: -e eval wake-word", InstrumentationRegistry.getArguments().getString("eval") == "wake-word")
        val seconds = InstrumentationRegistry.getArguments().getString("seconds")?.toInt() ?: 300
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Thread.sleep(2_000)
        val detector = WakeDetector(context.assets)
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            WakeWord.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            WakeWord.SAMPLE_RATE * 2,
        )
        val total = WakeWord.SAMPLE_RATE * seconds
        val kept = ShortArray(total)
        var count = 0
        val heard = mutableListOf<String>()
        try {
            record.startRecording()
            report("--- live, recording started")
            val pcm = ShortArray(WakeWord.CHUNK_SAMPLES)
            while (count < total) {
                val read = record.read(pcm, 0, minOf(pcm.size, total - count))
                if (read <= 0) continue
                pcm.copyInto(kept, count, 0, read)
                count += read
                detector.accept(pcm, read)?.let { heard += "${count * 1000L / WakeWord.SAMPLE_RATE} ${it.tag}" }
            }
        } finally {
            runCatching {
                record.stop()
                record.release()
            }
            detector.close()
        }
        report("live: heard at (ms, by) $heard")
        val bytes = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) bytes.putShort(kept[i])
        File(context.getExternalFilesDir(null), "wake-live.wav")
            .writeBytes(wavHeader(count * 2L, sampleRate = WakeWord.SAMPLE_RATE, channels = 1, bitsPerSample = 16) + bytes.array())
    }

    /** Half a second of quiet before, a second after: as the Mac scoring did. */
    private fun padded(x: ShortArray) = ShortArray(WakeWord.SAMPLE_RATE / 2) + x + ShortArray(WakeWord.SAMPLE_RATE)

    private fun read16kMono(file: File): ShortArray {
        val bytes = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        var at = 12
        var rate = 0
        while (at + 8 <= bytes.limit()) {
            val id = String(ByteArray(4) { bytes.get(at + it) }, Charsets.US_ASCII)
            val size = bytes.getInt(at + 4)
            if (id == "fmt ") rate = bytes.getInt(at + 12)
            if (id == "data") {
                check(rate == WakeWord.SAMPLE_RATE) { "${file.name}: $rate Hz" }
                val count = minOf(size, bytes.limit() - at - 8) / 2
                return ShortArray(count) { bytes.getShort(at + 8 + it * 2) }
            }
            at += 8 + size + (size and 1)
        }
        error("${file.name} has no audio")
    }

    private fun report(line: String) {
        Log.i(TAG, line)
        results.appendText("$line\n")
    }

    private companion object {
        const val TAG = "WakeWordEval"
    }
}
