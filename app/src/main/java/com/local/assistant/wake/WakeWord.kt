package com.local.assistant.wake

/** When the wake word listens (Settings › Assistant). Off until the user turns it on. */
enum class WakeWordMode {
    OFF,

    /** Only while the phone is plugged in: a desk, a car. Costs nothing off the charger. */
    WHILE_CHARGING,

    /** Whenever the phone is in use, and on the lock screen only if the assistant may be. */
    ALWAYS,
}

/**
 * The wake phrase and how its two listeners are tuned ([WakeDetector]). The spotter is
 * sherpa-onnx's keyword spotter (the same 1.13.8 library the natural voice runs on) with k2-fsa's
 * 3M-parameter zipformer, which listens for phonemes, so a phrase is written as ARPAbet phones
 * and needs no training. The model is a small microWakeWord network trained for this phrase. Both
 * are in `assets/wake/`, and nothing of what they hear is kept.
 */
object WakeWord {

    /**
     * What the user says (the user's pick, 2026-10-29). Played to the phone through the air it was
     * heard 12 times in 12, Indian voices too, and woke on no sound-alike; "Hey Kalyani" had
     * needed six spellings and still missed the user's own voice (docs/TECHNICAL.md, "The wake
     * word").
     */
    const val PHRASE = "Hey Luna"

    /** Under assets/. */
    const val ASSET_DIR = "wake"

    /**
     * The phrase in sherpa-onnx's keyword syntax, one way of saying it per line. The model is
     * bilingual and hears the user's "Hey Luna" mostly as Mandarin syllables ("hēi lóng ne",
     * "hēi lóng nǎ", "xiè lóng ne"), so beside the dictionary's English phones the file has
     * those, in pinyin initials and toned finals: with them all 13 of the user's recordings are
     * heard, against 8 (docs/TECHNICAL.md, "The wake word").
     */
    const val KEYWORDS_FILE = "keywords.txt"

    /**
     * How much the phrase's path is favoured over others while decoding, and how sure the spotter
     * must be (0..1; lower hears more, and wakes more by mistake). Tuned on the user's own "hey
     * luna" recordings, played to the phone through the air: 0.2 with boost 2 heard 7 of 12, this
     * 10 of 12, with one false wake ("only one story") in 10.75 hours of LibriSpeech for both
     * (docs/TECHNICAL.md, "The wake word").
     */
    const val BOOST = 3.0f
    const val THRESHOLD = 0.05f

    /**
     * How many ways of reading the audio the spotter keeps open (sherpa-onnx's max_active_paths;
     * 4 is its default). Wider hears more through a room's echo, but wakes on far more of
     * everything else: on the user's recordings played through the air, 4 heard 16 of 26 plays at
     * about 1 false wake per hour of speech, 16 heard 18 at 15, 64 heard 23 at 170
     * (docs/TECHNICAL.md, "The wake word").
     */
    const val SEARCH_WIDTH = 4

    /**
     * The second listener, [WakeModel]: a microWakeWord model trained on the Mac from synthetic
     * voices, LibriSpeech and the user's own 40 recorded "Hey Luna"s and talk (tools/wakeword).
     * It and the spotter miss different phrases, so the phrase is heard when either hears it: of
     * the user's 26 test recordings (13 held back from training, 13 older ones), the spotter heard
     * 23, the model 24, the two together all 26 (docs/TECHNICAL.md, "The wake word").
     */
    const val MODEL_FILE = "hey_luna.tflite"

    /**
     * How sure the model must be (0..1), averaged over its last [MODEL_WINDOW] scores (30 ms apart).
     * At 0.9 it woke 1.2 times an hour on LibriSpeech's dev-other, which it never trained on, and on
     * 10 of 434 sound-alikes; 0.8 heard no more of the user's held-back phrases and woke 3.1 times
     * an hour (the user's choice, 2026-10-30).
     */
    const val MODEL_CUTOFF = 0.9f
    const val MODEL_WINDOW = 5

    /** The microphone's rate, and the model's. */
    const val SAMPLE_RATE = 16_000

    /** Audio handed to the spotter at a time: 100 ms, so the processor wakes ten times a second. */
    const val CHUNK_SAMPLES = SAMPLE_RATE / 10
}

/**
 * Whether the wake word listens right now: always, the app's own screens and the overlay
 * included ("it should work always", the user), except while the app is recording a question
 * (the phrase would only land in it), while Settings' Try it has the microphone, during a call,
 * and on a locked phone unless the assistant is allowed on the lock screen.
 */
object WakeConditions {

    data class State(
        val mode: WakeWordMode,
        val micGranted: Boolean,
        val charging: Boolean,
        val locked: Boolean,
        val lockScreenAllowed: Boolean,
        /** The app is recording a question or a voice message. */
        val recording: Boolean,
        /** Settings' Try it is listening. */
        val trying: Boolean,
        /** A phone or video call. */
        val inCall: Boolean,
    )

    fun shouldListen(s: State): Boolean = whyNot(s) == null

    /** Why it isn't listening, for the log; null when it is. */
    fun whyNot(s: State): String? = when {
        s.mode == WakeWordMode.OFF -> "turned off"
        !s.micGranted -> "no microphone permission"
        s.recording -> "the app is recording"
        s.trying -> "Try it is listening"
        s.inCall -> "a call is on"
        s.mode == WakeWordMode.WHILE_CHARGING && !s.charging -> "not charging"
        s.locked && !s.lockScreenAllowed -> "the phone is locked"
        else -> null
    }
}
