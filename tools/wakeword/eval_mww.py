"""A trained microWakeWord model against the user's recordings, unseen synthetic voices, the
sound-alikes and dev-other speech, at several cut-offs; today's sherpa spotter on dev-other too.
Detection as on devices: the mean of the last 5 window probabilities reaching the cut-off, then
quiet for 2 s. Usage: eval_mww.py MODEL.tflite"""
import glob, json, os, sys
import numpy as np, soundfile as sf
from microwakeword.inference import Model
from microwakeword.audio.audio_utils import generate_features_for_clip
TFL = sys.argv[1]
W = os.environ.get("WAKEWORD_WORK") or sys.exit("Set WAKEWORD_WORK to the working folder (see README.md).")
CUTS = [0.5, 0.7, 0.8, 0.9, 0.95, 0.97]
WINDOW = 5

def load(p):
    x, sr = sf.read(p, dtype="float32")
    if x.ndim > 1: x = x[:, 0]
    if sr != 16000:
        t = np.arange(0, len(x) / sr, 1 / 16000); x = np.interp(t, np.arange(len(x)) / sr, x).astype(np.float32)
    return np.concatenate([np.zeros(8000, np.float32), x, np.zeros(16000, np.float32)])

def probs(x):
    m = Model(TFL)  # fresh streaming state per clip
    return np.array(m.predict_spectrogram(generate_features_for_clip(x, step_ms=10)), dtype=np.float32)

def wakes(p, cut, step_s):
    if len(p) < WINDOW: return 0
    mean = np.convolve(p, np.ones(WINDOW) / WINDOW, mode="valid")
    n, quiet_until = 0, -1
    for i, v in enumerate(mean):
        if i >= quiet_until and v >= cut:
            n += 1; quiet_until = i + int(2.0 / step_s)
    return n

m0 = Model(TFL); step_s = m0.stride * 0.01
sets = {
    "user recorder (13)": sorted(glob.glob(W + "/data/user/recorder/*.wav")),
    "user held-out": sorted(glob.glob(W + "/data/user/test/*.wav")),
    "voices, unseen": sorted(glob.glob(W + "/eval/voices/*.wav")),
    "sound-alikes": sorted(glob.glob(W + "/eval/near/*.wav")),
}
res = {}
for name, files in sets.items():
    if not files: continue
    ps = [probs(load(f)) for f in files]
    res[name] = {c: sum(1 for p in ps if wakes(p, c, step_s) > 0) for c in CUTS}
    print(f"{name:22s} of {len(files):3d}: " + "  ".join(f"{c}:{res[name][c]}" for c in CUTS), flush=True)

# dev-other, file by file (5.3 h)
dev = sorted(glob.glob(W + "/data/eval/LibriSpeech/dev-other/**/*.flac", recursive=True))
if dev:
    total = 0.0; fw = {c: 0 for c in CUTS}
    for f in dev:
        x, _ = sf.read(f, dtype="float32"); total += len(x) / 16000
        p = probs(x)
        for c in CUTS: fw[c] += wakes(p, c, step_s)
    hours = total / 3600
    print(f"dev-other {hours:.2f} h false wakes per hour: " + "  ".join(f"{c}:{fw[c] / hours:.2f}" for c in CUTS), flush=True)
print("model", os.path.getsize(TFL) // 1024, "KB, window step", step_s, "s")
