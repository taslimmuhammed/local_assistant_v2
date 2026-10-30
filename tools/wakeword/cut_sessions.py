"""Cuts the user's recorded sessions (pulled to data/user/sessions) into single phrases.
Phrase sessions: bursts of speech 0.25–2.5 s long (gaps under 0.3 s join), padded 0.25 s before and 0.35 s after; every
fourth one is held out for testing (data/user/test), the rest train (data/user/train).
"With noise" sessions also give their quiet stretches as background noise (data/user/noise).
Talk sessions are copied to data/user/talk. Usage: cut_sessions.py"""
import glob, os, shutil, sys
import numpy as np, soundfile as sf
W = os.environ.get("WAKEWORD_WORK") or sys.exit("Set WAKEWORD_WORK to the working folder (see README.md).")
U = W + "/data/user"
for d in ["train", "test", "noise", "talk"]:
    os.makedirs(f"{U}/{d}", exist_ok=True)
summary = {}
n_all = 0
for path in sorted(glob.glob(U + "/sessions/*.wav")):
    name = os.path.basename(path)[:-4]
    x, sr = sf.read(path, dtype="float32"); assert sr == 16000, path
    if name.startswith("talk-"):
        shutil.copy(path, f"{U}/talk/{name}.wav"); continue
    place = name.split("-")[1]
    frame = 160  # 10 ms
    rms = np.array([np.sqrt(np.mean(x[i:i + frame] ** 2)) for i in range(0, len(x) - frame, frame)])
    floor = np.percentile(rms, 20)
    speech = rms > max(floor * 4, 0.01)
    # Close small gaps (inside the phrase) before finding bursts.
    s = speech.copy()
    for i in range(1, len(s) - 30):
        if not s[i] and s[i - 1] and s[i:i + 30].any(): s[i] = True
    bursts, start = [], None
    for i, v in enumerate(np.append(s, False)):
        if v and start is None: start = i
        if not v and start is not None:
            if 25 <= i - start <= 250: bursts.append((start, i))
            start = None
    for j, (a, b) in enumerate(bursts):
        clip = x[max(0, (a - 25) * frame):min(len(x), (b + 35) * frame)]
        split = "test" if n_all % 4 == 3 else "train"
        sf.write(f"{U}/{split}/{name}-{j:02d}.wav", clip, 16000, subtype="PCM_16")
        n_all += 1
        summary.setdefault(place, {"train": 0, "test": 0})[split] += 1
    if place == "noisy":
        quiet = np.concatenate([x[i * frame:(i + 1) * frame] for i in np.where(~s)[0]]) if (~s).any() else np.zeros(0)
        if len(quiet) > 16000: sf.write(f"{U}/noise/{name}.wav", quiet, 16000, subtype="PCM_16")
talk = sum(sf.info(p).duration for p in glob.glob(U + "/talk/*.wav"))
print("phrases:", summary, "| talk:", round(talk), "s | noise files:", len(glob.glob(U + "/noise/*.wav")))
