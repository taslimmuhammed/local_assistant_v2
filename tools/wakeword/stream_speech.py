"""LibriSpeech train-clean-100 (CC BY 4.0) as negative features, a Parquet shard at a time from
Hugging Face: download, cut each utterance into 3 s pieces, features as uint16, delete the shard.
The Mac is short of disk; this needs only the features (~3 GB). Usage: stream_speech.py"""
import io, json, os, subprocess, sys, urllib.request
import numpy as np, pyarrow.parquet as pq, soundfile as sf
from mmap_ninja.ragged import RaggedMmap
from microwakeword.audio.audio_utils import generate_features_for_clip
W = os.environ.get("WAKEWORD_WORK") or sys.exit("Set WAKEWORD_WORK to the working folder (see README.md).")
OUT = W + "/features/speech100/training"
os.makedirs(OUT, exist_ok=True)
urls = json.load(urllib.request.urlopen("https://huggingface.co/api/datasets/openslr/librispeech_asr/parquet/clean/train.100"))
print(len(urls), "shards", flush=True)

def pieces(table):
    for audio in table.column("audio").to_pylist():
        x, sr = sf.read(io.BytesIO(audio["bytes"]), dtype="float32")
        assert sr == 16000
        for i in range(0, len(x) - 16000, 48000):  # 3 s pieces, the last one at least 1 s
            spec = generate_features_for_clip(x[i:i + 48000], step_ms=10)
            yield np.clip(np.rint(spec / 0.0390625), 0, 65535).astype(np.uint16)

for n, url in enumerate(urls):
    out = f"{OUT}/shard{n:02d}_mmap"
    if os.path.exists(out): continue
    tmp = f"{W}/data/shard.parquet"
    subprocess.run(["curl", "-sL", "-o", tmp, url], check=True)
    RaggedMmap.from_generator(out_dir=out, sample_generator=pieces(pq.read_table(tmp, columns=["audio"])), batch_size=500, verbose=False)
    os.remove(tmp)
    print(f"shard {n + 1}/{len(urls)} done", flush=True)
print("ALL DONE")
