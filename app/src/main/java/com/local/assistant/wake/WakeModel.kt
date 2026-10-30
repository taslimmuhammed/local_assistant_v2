package com.local.assistant.wake

import android.content.res.AssetManager
import androidx.annotation.VisibleForTesting
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Hears [WakeWord.PHRASE] with a 60 KB microWakeWord model trained on the user's own voice
 * (2026-10-30), beside [WakeSpotter]: [MicroFrontend]'s spectrogram goes to the model three
 * frames (30 ms) at a time, and the phrase is heard when the mean of its last [WakeWord.MODEL_WINDOW]
 * scores reaches [WakeWord.MODEL_CUTOFF], as when it was tested on the Mac. The model keeps its
 * own memory of the last second or so between calls; nothing else is held.
 */
class WakeModel(assets: AssetManager) : AutoCloseable {

    private val model: MappedByteBuffer = map(assets, "${WakeWord.ASSET_DIR}/${WakeWord.MODEL_FILE}")
    private var interpreter = newInterpreter()
    private val frontend = MicroFrontend()

    private val input = ByteBuffer.allocateDirect(SLICES * MicroFrontend.CHANNELS).order(ByteOrder.nativeOrder())
    private val output = ByteBuffer.allocateDirect(1).order(ByteOrder.nativeOrder())
    private val inputScale: Float
    private val inputZero: Int
    private var sliced = 0

    private val recent = FloatArray(WakeWord.MODEL_WINDOW)
    private var scored = 0

    init {
        val tensor = interpreter.getInputTensor(0)
        check(tensor.shape().contentEquals(intArrayOf(1, SLICES, MicroFrontend.CHANNELS))) { "Unexpected wake model input ${tensor.shape().toList()}" }
        inputScale = tensor.quantizationParams().scale
        inputZero = tensor.quantizationParams().zeroPoint
    }

    /** Feeds [count] samples of [pcm]; true when they finished the phrase. */
    fun accept(pcm: ShortArray, count: Int): Boolean {
        var heard = false
        feed(pcm, count) { _, finished -> if (finished) heard = true }
        return heard
    }

    /** As [accept], but returns the model's raw scores (0..255), one per 30 ms of audio. */
    @VisibleForTesting
    internal fun scores(pcm: ShortArray, count: Int): List<Int> = buildList { feed(pcm, count) { score, _ -> add(score) } }

    private fun feed(pcm: ShortArray, count: Int, onScore: (score: Int, heard: Boolean) -> Unit) {
        frontend.process(pcm, count) { frame ->
            for (value in frame) input.put(quantize(value))
            if (++sliced == SLICES) {
                val score = score()
                recent[scored % recent.size] = score / 255f
                scored++
                onScore(score, scored >= recent.size && recent.average() >= WakeWord.MODEL_CUTOFF)
                sliced = 0
                input.clear()
            }
        }
    }

    /**
     * Into the model's int8 input the way microWakeWord's own inference code does it: the frontend's
     * value scaled to the training features (× 0.0390625), then divided by the input scale, offset by
     * its zero point and truncated.
     */
    private fun quantize(value: Int): Byte {
        val q = ((value * FEATURE_SCALE / inputScale).toDouble() + inputZero).toInt()
        return q.coerceIn(Byte.MIN_VALUE.toInt(), Byte.MAX_VALUE.toInt()).toByte()
    }

    /** The model's probability for the last 30 ms, as its uint8 output: 0..255. */
    private fun score(): Int {
        input.rewind()
        output.rewind()
        interpreter.run(input, output)
        return output.get(0).toInt() and 0xFF
    }

    /** Forgets what it has heard, so a new listen starts clean. */
    fun reset() {
        frontend.reset()
        // The model's memory lives in its variables; a fresh interpreter starts them at zero.
        interpreter.close()
        interpreter = newInterpreter()
        input.clear()
        sliced = 0
        recent.fill(0f)
        scored = 0
    }

    override fun close() {
        interpreter.close()
        frontend.close()
    }

    private fun newInterpreter() = Interpreter(model, Interpreter.Options().setNumThreads(1))

    private fun map(assets: AssetManager, name: String): MappedByteBuffer = assets.openFd(name).use { fd ->
        FileInputStream(fd.fileDescriptor).channel.use { it.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength) }
    }

    private companion object {
        /** Spectrogram frames per model call: the model's stride. */
        const val SLICES = 3

        /** From the frontend's 0..65535 to the training features' scale. */
        const val FEATURE_SCALE = 0.0390625f
    }
}
