"""Kokoro's voices saying "Hey Luna" three ways at three speeds, written at 16 kHz.
Usage: gen_kokoro.py MODELS_DIR OUT_DIR"""
import glob, os, sys
import numpy as np, sherpa_onnx as so, soundfile as sf
MODELS, OUT = sys.argv[1], sys.argv[2]
os.makedirs(OUT, exist_ok=True)
d = glob.glob(MODELS + "/kokoro-multi-lang-v1_0/*/")[0]
m = so.OfflineTtsModelConfig(num_threads=4, provider="cpu")
m.kokoro = so.OfflineTtsKokoroModelConfig(model=d + "model.onnx", voices=d + "voices.bin", tokens=d + "tokens.txt",
    lexicon=d + "lexicon-us-en.txt", data_dir=d + "espeak-ng-data", lang="en-us")
tts = so.OfflineTts(so.OfflineTtsConfig(model=m))
n = 0
for sid in range(tts.num_speakers):
    for ti, text in enumerate(["Hey Luna.", "Hey Luna!", "Hey, Luna."]):
        for speed in (0.85, 1.0, 1.15):
            p = f"{OUT}/k{sid:02d}-{ti}-{speed}.wav"
            if os.path.exists(p): continue
            a = tts.generate(text, sid=sid, speed=speed)
            x = np.asarray(a.samples, dtype=np.float32)
            t = np.arange(0, len(x) / a.sample_rate, 1 / 16000)
            sf.write(p, np.interp(t, np.arange(len(x)) / a.sample_rate, x).astype(np.float32), 16000, subtype="PCM_16")
            n += 1
print(tts.num_speakers, "voices,", n, "clips")
