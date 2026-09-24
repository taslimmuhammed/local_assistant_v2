#!/usr/bin/env python3
"""Checks a built EmbeddingGemma bundle through LiteRT-LM itself, against sentence-transformers.

    python gate_bundle.py <bundle.litertlm> [<hf_model_dir_or_id> | --random-init]

This is the runtime the phone uses (the same C++ engine behind the Kotlin EmbeddingEngine), so
it catches what the converter's own checks cannot: the packing, the metadata's special tokens,
and the engine's tokenizer, which does not run the HF post-processor.
"""
import argparse
import statistics
import time

import numpy as np

import convert_embeddinggemma as c


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("bundle")
    ap.add_argument("model", nargs="?", default="google/embeddinggemma-300m")
    ap.add_argument("--random-init", action="store_true")
    args = ap.parse_args()

    from litert_lm import interfaces
    from litert_lm.embedding_engine import EmbeddingEngine, EmbeddingOptions

    st = c.load(args.model, args.random_init)
    started = time.time()
    engine = EmbeddingEngine(args.bundle, backend=interfaces.CPU(thread_count=4))
    print(f"engine load: {time.time() - started:.2f} s")

    def embed(text, size=None):
        v = np.array(engine.compute_embedding(text, EmbeddingOptions(normalize=True, output_size=size)).embedding)
        return v / np.linalg.norm(v)

    texts = ([c.DOCUMENT_PROMPT + t for t in c.SAMPLE_TEXTS] + [c.QUERY_PROMPT + t for t in c.SAMPLE_TEXTS]
             + [c.QUERY_PROMPT + c.CARD_QUERY] + [c.DOCUMENT_PROMPT + d for d in c.CARD_DOCUMENTS])
    ref = c.reference(st, texts)
    cosines, times = [], []
    for text, r in zip(texts, ref):
        started = time.perf_counter()
        got = embed(text)
        times.append((time.perf_counter() - started) * 1000)
        cosines.append(float(r @ got))
    print(f"BUNDLE vs sentence-transformers: worst cosine {min(cosines):.6f}, mean {statistics.mean(cosines):.6f} "
          f"over {len(texts)} texts; median {statistics.median(times):.1f} ms per text on this Mac")
    worst = int(np.argmin(cosines))
    print(f"  worst: {texts[worst][:70]!r}")

    if not args.random_init:
        for size in (None, 256):
            q = embed(c.QUERY_PROMPT + c.CARD_QUERY, size)
            sims = [float(q @ embed(c.DOCUMENT_PROMPT + d, size)) for d in c.CARD_DOCUMENTS]
            print(f"CARD {size or 768}d:", [f"{s:.4f}" for s in sims], "card says", c.CARD_SIMILARITIES)


if __name__ == "__main__":
    main()
