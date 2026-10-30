"""Today's sherpa spotter (the app's assets: C6 spellings, threshold 0.05, boost 3) on the same
sets as eval_mww.py, for a like-for-like comparison. Usage: eval_sherpa.py"""
import glob, os, sys
import numpy as np, soundfile as sf, sherpa_onnx as so
W = os.environ.get("WAKEWORD_WORK") or sys.exit("Set WAKEWORD_WORK to the working folder (see README.md).")
A = os.path.join(os.path.dirname(os.path.abspath(__file__)), "../../app/src/main/assets/wake/")
k = so.KeywordSpotter(tokens=A + "tokens.txt", encoder=A + "encoder.int8.onnx", decoder=A + "decoder.onnx", joiner=A + "joiner.int8.onnx",
    num_threads=1, keywords_file=A + "keywords.txt", keywords_score=3.0, keywords_threshold=0.05, provider="cpu")
def count(x):
    s = k.create_stream(); n = 0
    for i in range(0, len(x), 1600):
        s.accept_waveform(16000, x[i:i + 1600])
        while k.is_ready(s):
            k.decode_stream(s)
            if k.get_result(s): n += 1; k.reset_stream(s)
    return n
def load(p):
    x, sr = sf.read(p, dtype="float32")
    if x.ndim > 1: x = x[:, 0]
    if sr != 16000:
        t = np.arange(0, len(x) / sr, 1 / 16000); x = np.interp(t, np.arange(len(x)) / sr, x).astype(np.float32)
    return np.concatenate([np.zeros(8000, np.float32), x, np.zeros(16000, np.float32)])
sets = {
    "user recorder (13)": sorted(glob.glob(W + "/data/user/recorder/*.wav")),
    "user held-out": sorted(glob.glob(W + "/data/user/test/*.wav")),
    "voices, unseen": sorted(glob.glob(W + "/eval/voices/*.wav")),
    "sound-alikes": sorted(glob.glob(W + "/eval/near/*.wav")),
}
for name, files in sets.items():
    if files: print(f"{name:22s} of {len(files):3d}: {sum(1 for f in files if count(load(f)) > 0)}", flush=True)
dev = sorted(glob.glob(W + "/data/eval/LibriSpeech/dev-other/**/*.flac", recursive=True))
if dev:
    total = 0.0; n = 0
    for f in dev:
        x, _ = sf.read(f, dtype="float32"); total += len(x) / 16000; n += count(x)
    print(f"dev-other {total / 3600:.2f} h false wakes per hour: {n / (total / 3600):.2f}")
