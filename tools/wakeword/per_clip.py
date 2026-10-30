"""Per clip scores on the user's held-out and recorder clips, to compare models clip by clip.
Usage: per_clip.py MODEL.tflite (train-venv)  |  per_clip.py sherpa (the sherpa venv)"""
import glob, os, sys
W = os.environ.get("WAKEWORD_WORK") or sys.exit("Set WAKEWORD_WORK to the working folder (see README.md).")
T = os.path.dirname(os.path.abspath(__file__))
files = [f for pattern in ["test/luna-*", "recorder/*"] for f in sorted(glob.glob(f"{W}/data/user/{pattern}.wav"))]
if sys.argv[1] == "sherpa":
    exec(open(T + "/eval_sherpa.py").read().split("sets = {")[0])
    for f in files: print(os.path.basename(f), 1.0 if count(load(f)) else 0.0)
else:
    exec(open(T + "/eval_mww.py").read().split("m0 = Model(TFL)")[0])
    for f in files: print(os.path.basename(f), round(float(np.convolve(probs(load(f)), np.ones(WINDOW) / WINDOW, mode="valid").max()), 3))
