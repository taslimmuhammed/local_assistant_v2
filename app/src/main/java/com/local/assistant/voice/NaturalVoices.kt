package com.local.assistant.voice

import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import java.io.File

/**
 * The natural voices the assistant can speak with, each made from a downloaded [VoicePack] by
 * sherpa-onnx ([NaturalVoice] runs them). Settings stores the chosen one's [Voice.id].
 */
object NaturalVoices {

    sealed class Voice(val id: String, val name: String, val pack: VoicePack) {

        /** "Heart · Kokoro" */
        val label: String get() = "$name · ${pack.title}"

        /** How sherpa-onnx is told to run it from [dir], the unpacked [pack]. */
        abstract fun config(dir: File, threads: Int): OfflineTtsConfig

        /**
         * Makes speech for [text] with [tts] (made from [config]). [onAudio], when given, gets it
         * a sentence or a stretch at a time while it's made, and stops it by returning 0. It
         * must be an object, not a lambda: see [NaturalVoice].
         */
        abstract fun generate(tts: OfflineTts, dir: File, text: String, speed: Float, onAudio: ((FloatArray) -> Int)?): GeneratedAudio
    }

    /** One of Kokoro's 54 voices, by its speaker number. */
    class Kokoro(id: String, name: String, private val speaker: Int) : Voice(id, name, VoicePack.KOKORO) {

        override fun config(dir: File, threads: Int) = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = File(dir, VoicePack.KOKORO_MODEL).path,
                    voices = File(dir, VoicePack.KOKORO_VOICES).path,
                    tokens = File(dir, VoicePack.KOKORO_TOKENS).path,
                    dataDir = File(dir, VoicePack.ESPEAK_DATA).path,
                    lexicon = File(dir, VoicePack.KOKORO_LEXICON).path,
                    lang = "en-us",
                ),
                numThreads = threads,
                provider = "cpu",
            ),
            // A sentence at a time comes back through the callback, so speech starts sooner.
            maxNumSentences = 1,
        )

        override fun generate(tts: OfflineTts, dir: File, text: String, speed: Float, onAudio: ((FloatArray) -> Int)?): GeneratedAudio =
            if (onAudio == null) tts.generate(text, speaker, speed) else tts.generateWithCallback(text, speaker, speed, onAudio)
    }

    val HEART = Kokoro("kokoro-heart", "Heart", speaker = 3)

    val ALL: List<Voice> = listOf(HEART)

    fun byId(id: String?): Voice? = ALL.firstOrNull { it.id == id }

    /** The voice a pack's download switches to. */
    fun firstOf(pack: VoicePack): Voice = ALL.first { it.pack == pack }
}
