package com.local.assistant.wake

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

/**
 * Hears [WakeWord.PHRASE] in a stream of 16 kHz audio. One thread, about 6 MB of model; only
 * the last second or so of audio is held, inside the model's own state.
 */
class WakeSpotter(assets: AssetManager) {

    private val spotter = KeywordSpotter(
        assets,
        KeywordSpotterConfig(
            featConfig = FeatureConfig(sampleRate = WakeWord.SAMPLE_RATE, featureDim = 80, dither = 0f),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "${WakeWord.ASSET_DIR}/$ENCODER",
                    decoder = "${WakeWord.ASSET_DIR}/$DECODER",
                    joiner = "${WakeWord.ASSET_DIR}/$JOINER",
                ),
                tokens = "${WakeWord.ASSET_DIR}/$TOKENS",
                numThreads = 1,
                provider = "cpu",
            ),
            keywordsFile = "${WakeWord.ASSET_DIR}/${WakeWord.KEYWORDS_FILE}",
            keywordsScore = WakeWord.BOOST,
            keywordsThreshold = WakeWord.THRESHOLD,
            maxActivePaths = WakeWord.SEARCH_WIDTH,
            numTrailingBlanks = 1,
        ),
    )

    private var stream: OnlineStream = newStream()

    /** Feeds [samples] (−1..1); true when they finished the phrase. */
    fun accept(samples: FloatArray): Boolean {
        stream.acceptWaveform(samples, WakeWord.SAMPLE_RATE)
        var heard = false
        while (spotter.isReady(stream)) {
            spotter.decode(stream)
            if (spotter.getResult(stream).keyword.isNotEmpty()) {
                heard = true
                spotter.reset(stream)
            }
        }
        return heard
    }

    /** Forgets what it has heard, so a new listen starts clean. */
    fun reset() {
        stream.release()
        stream = newStream()
    }

    fun release() {
        stream.release()
        spotter.release()
    }

    private fun newStream(): OnlineStream = spotter.createStream()

    companion object {
        const val ENCODER = "encoder.int8.onnx"
        const val DECODER = "decoder.onnx"
        const val JOINER = "joiner.int8.onnx"
        const val TOKENS = "tokens.txt"
    }
}
