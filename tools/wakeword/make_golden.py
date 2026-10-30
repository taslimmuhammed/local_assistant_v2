"""The app's exactness check (WakeModelTest): a Kokoro voice saying "Hey Luna" between stretches of
seeded noise, with the features and model scores microWakeWord's Python computes for it. The app
must produce the same numbers. Usage: make_golden.py KOKORO_CLIP MODEL.tflite OUT_DIR"""
import os, sys
import numpy as np, soundfile as sf
from microwakeword.inference import Model
from microwakeword.audio.audio_utils import generate_features_for_clip
clip, tfl, out = sys.argv[1:4]
x, sr = sf.read(clip, dtype="int16"); assert sr == 16000
rng = np.random.default_rng(7)
noise = lambda n: rng.normal(0, 60, n)  # about -55 dB of full scale
audio = np.concatenate([noise(8000), x + noise(len(x)), noise(16000)])
audio = np.clip(np.rint(audio), -32768, 32767).astype(np.int16)
sf.write(f"{out}/golden.wav", audio, 16000, subtype="PCM_16")
features = generate_features_for_clip(audio, step_ms=10)
values = np.rint(features / 0.0390625).astype(np.int64)
assert np.array_equal(values * 0.0390625, features.astype(np.float64)), "features not on the 0.0390625 grid"
scores = np.rint(np.array(Model(tfl).predict_spectrogram(features)) * 255).astype(int)
with open(f"{out}/golden.txt", "w") as f:
    f.write("# tools/wakeword/make_golden.py: the frontend's frames (40 values, 0..65535) and the model's scores (0..255)\n")
    f.write(f"frames {len(values)}\n")
    for row in values: f.write(" ".join(map(str, row)) + "\n")
    f.write(f"scores {len(scores)}\n" + " ".join(map(str, scores)) + "\n")
mean = np.convolve(scores / 255, np.ones(5) / 5, mode="valid")
print(f"{len(audio) / 16000:.2f} s, {len(values)} frames, max value {values.max()}, {len(scores)} scores, best window {mean.max():.3f}")
