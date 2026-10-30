"""Room impulse responses computed with pyroomacoustics (MIT licence), in place of the notebook's
downloaded set: shoebox rooms 3–10 m by 3–8 m, echo times 0.15–0.9 s, the voice 0.5–4 m from the
phone. Usage: make_rirs.py OUT_DIR COUNT"""
import os, sys
import numpy as np, pyroomacoustics as pra, soundfile as sf
OUT, N = sys.argv[1], int(sys.argv[2])
os.makedirs(OUT, exist_ok=True)
rng = np.random.default_rng(0)
made = 0
while made < N:
    room = np.array([rng.uniform(3, 10), rng.uniform(3, 8), rng.uniform(2.4, 3.5)])
    rt60 = rng.uniform(0.15, 0.9)
    try:
        absorption, max_order = pra.inverse_sabine(rt60, room)
    except ValueError:
        continue
    r = pra.ShoeBox(room, fs=16000, materials=pra.Material(absorption), max_order=min(max_order, 30))
    mic = np.array([rng.uniform(0.5, room[0] - 0.5), rng.uniform(0.5, room[1] - 0.5), rng.uniform(0.7, 1.5)])
    for _ in range(20):
        d = rng.uniform(0.5, 4.0); a = rng.uniform(0, 2 * np.pi)
        src = mic + np.array([d * np.cos(a), d * np.sin(a), rng.uniform(0.2, 0.7)])
        if np.all(src > 0.3) and np.all(src < room - 0.3): break
    else:
        continue
    r.add_source(src); r.add_microphone(mic)
    r.compute_rir()
    h = np.asarray(r.rir[0][0], dtype=np.float32)
    h = h / (np.max(np.abs(h)) + 1e-9)
    sf.write(f"{OUT}/rir{made:03d}-rt{rt60:.2f}-d{d:.1f}.wav", h, 16000, subtype="PCM_16")
    made += 1
print(made, "impulse responses")
