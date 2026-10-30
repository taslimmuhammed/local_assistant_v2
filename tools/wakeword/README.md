# Training the "Hey Luna" model

These scripts build `app/src/main/assets/wake/hey_luna.tflite`, the wake word's second listener
(`wake/WakeModel`): a 60 KB microWakeWord model that runs beside the sherpa-onnx keyword spotter.
The app wakes when either one hears the phrase (`wake/WakeDetector`). Training runs on a Mac (an
M2 takes about 30 minutes); the data and the trained runs go in a working folder outside the
repository, named by `WAKEWORD_WORK`.

## Data, all licence-clean

- **The phrase, synthetic**: Piper's sample generator (MIT) with the LibriTTS-R voice model
  (CC BY 4.0), 800 speakers, the text two ways and nine phonetic spellings close to how the user
  says it (13.5k clips, `gen_synthetic.sh`), and Kokoro's voices through sherpa-onnx (486 clips,
  `gen_kokoro.py`).
- **Sound-alikes**: 25 phrases such as "hey lorna", "hey lunar", "hey, long time", 250 clips each
  (`gen_synthetic.sh`).
- **Rooms**: 300 impulse responses computed with pyroomacoustics (MIT) (`make_rirs.py`), instead of
  the downloaded set microWakeWord's notebook uses.
- **Speech it must not wake on**: LibriSpeech (CC BY 4.0). The test sets (10.75 h) and
  train-clean-100 (100 h, streamed a shard at a time from Hugging Face's Parquet copy by
  `stream_speech.py`) are negatives; dev-clean estimates false wakes while training; dev-other is
  kept for the final check. microWakeWord's published negative sets are CC BY-NC, so they aren't
  used.
- **The user's voice**: test builds of the app have Settings › Wake word › Samples for training
  (`wake/WakeSamples`). It records sessions of "Hey Luna" said over and over (close, across the
  room, with noise) and of ordinary talk to `Android/data/<app>/files/wake-samples/`. The
  recordings stay in the working folder and are never added to the repository; delete them when
  done.

## Setting up

In `$WAKEWORD_WORK`:

- `train-venv`: Python 3.11 with TensorFlow 2.21, torch, `ai-edge-litert` 2.3.0, `datasets<4`
  (4.x wants torchcodec), `setuptools<81` (webrtcvad needs `pkg_resources`), audiomentations
  0.43.1, pyroomacoustics, tensorboard, piper-tts and piper-phonemize-cross.
- `micro-wake-word`: https://github.com/OHF-Voice/micro-wake-word at 4665173, installed editable
  into the venv. It computes features with pymicro-features 2.0.2, the same frontend code the app
  builds (`app/src/main/cpp/microfrontend`).
- `piper-sample-generator`: https://github.com/rhasspy/piper-sample-generator at 2971426, with
  `models/en_US-libritts_r-medium.pt`. On macOS run it through `gen_piper.py`, which swaps in
  piper-phonemize's espeak (the piper package's macOS build looks for its data at a path baked in
  on its build machine).

## Steps

```
export WAKEWORD_WORK=…            # the working folder
bash gen_synthetic.sh             # data/pos, data/adv (+ LibriSpeech dev-clean)
python gen_kokoro.py MODELS data/pos/kokoro
python make_rirs.py data/rirs 300
python stream_speech.py           # features/speech100
# the user's sessions: adb pull …/files/wake-samples/. data/user/sessions/
python cut_sessions.py            # data/user/{train,test,noise,talk}: every 4th phrase held back
python build_features.py pos; … adv; … user; SPEECH=test … speech; … ambient
bash train.sh v3 20000            # trained/v3/tflite_stream_state_internal_quant/
python eval_mww.py MODEL.tflite   # the user's held-back phrases, eval/voices, eval/near, dev-other
python eval_sherpa.py             # the app's spotter on the same sets (a venv with sherpa-onnx 1.13.8)
python per_clip.py MODEL.tflite   # clip by clip, to see which ones each listener misses
```

`cut_sessions.py` finds phrases by loudness: bursts 0.25–2.5 s long, with gaps under 0.3 s joined.
On the first sessions this found exactly as many phrases as the app had counted while recording.
`eval/voices` holds voices the model never trained on saying the phrase (the macOS `say` voices),
and `eval/near` the sound-alikes. Optionally, `data/user/recorder` holds older recordings of the
user, a fairer check than the held-back phrases, which come from the same sessions as the
training ones.

To ship a model: copy it to `app/src/main/assets/wake/hey_luna.tflite`. Then run
`make_golden.py data/pos/kokoro/k00-0-1.0.wav MODEL app/src/androidTest/assets/wake`, which writes
a Kokoro clip with Python's features and scores for it, and run `WakeModelTest` on a phone: the app
must match Python exactly.

## Results (2026-10-30)

Cut-off 0.9, the mean of the last 5 scores (30 ms apart):

| | User's 13 held back | User's 13 older | Unseen voices (24) | Sound-alikes (434) | False wakes/h, dev-other |
|---|---|---|---|---|---|
| v1: synthetic, 10.75 h negatives | – | 10 | 20 | – | 7 |
| v2: + 100 h negatives, two stages | 2 | 4 | 24 | – | 1.8 |
| v3: + the user's 40 phrases and talk | 11 | 11 | 18 | 10 | 1.2 |
| The spotter | 10 | 13 | 24 | 33 | 0 |

Synthetic voices alone never covered how the user says it, which the spotter's bilingual model
hears as "hēi lóng nǎ". v3 trains on the user's phrases at 50 varied copies each (echo, noise,
gain), sampled twice as often as all the synthetic ones together. v3 and the spotter miss
different phrases, so together they heard all 26 of the user's recordings, on the Mac and on the
phone.
