# Building the EmbeddingGemma bundle

The app's "Memory search" model is EmbeddingGemma 300M, packaged for LiteRT-LM's
`EmbeddingEngine`. Google publishes EmbeddingGemma for LiteRT only as one gated `.tflite` plus a
separate tokenizer, which `EmbeddingEngine` cannot load, so the bundle is built here from the
original weights and loaded into the app from storage (Model → Memory search → Load from device
storage). The app recognises it by its file name, `embeddinggemma-300m_wi8.litertlm`.

## Why EmbeddingGemma rather than Granite

Granite Embedding 311M R2 is published ready for `EmbeddingEngine` and downloads without an
account, so the app offers it too. But on the public MTEB per-language results — the tasks both
models were scored on — EmbeddingGemma retrieves better almost everywhere this app is used
(nDCG@10, 768 dimensions):

| Task (BelebeleRetrieval unless noted) | EmbeddingGemma | Granite R2 |
|---|---|---|
| English → English | 0.964 | 0.931 |
| Hindi → Hindi | 0.905 | 0.846 |
| Romanized Hindi → English | 0.775 | 0.691 |
| Romanized Hindi → Hindi | 0.717 | 0.624 |
| Malayalam → English | 0.914 | 0.806 |
| Tamil → English | 0.918 | 0.836 |
| Telugu → English | 0.929 | 0.818 |
| Kannada → English | 0.914 | 0.753 |
| MLQA retrieval (Hindi/English and others), mean | 0.790 | 0.671 |
| MIRACL (hard negatives), mean | 0.662 | 0.598 |

Source: github.com/embeddings-benchmark/results, `google__embeddinggemma-300m` and
`ibm-granite__granite-embedding-311m-multilingual-r2`.

## Steps

Python 3.11 on a Mac (Apple silicon) works; about 5 GB of free disk.

```bash
python3.11 -m venv venv
./venv/bin/pip install --no-cache-dir "torch<2.14" "litert-torch==0.9.4" "ai-edge-quantizer==0.9.0" \
    "ai-edge-litert==2.2.0" "litert-lm==0.17.1" sentence-transformers huggingface_hub
```

Accept the licence at https://huggingface.co/google/embeddinggemma-300m, then `./venv/bin/hf auth login`.

```bash
./venv/bin/hf download google/embeddinggemma-300m --local-dir embeddinggemma-300m
./venv/bin/python convert_embeddinggemma.py embeddinggemma-300m out     # ~15 min
./venv/bin/python gate_bundle.py out/embeddinggemma-300m_wi8.litertlm embeddinggemma-300m
```

`convert_embeddinggemma.py --random-init out-test` runs the whole pipeline on a small random
model of the same architecture, without the weights.

## What the converter does

`EmbeddingEngine` wants the model as two graphs (contract read from LiteRT-LM's
`embedding_litert_compiled_model_executor.cc`, recipe from john-rocky/hf-to-litertlm):

- **embedder**: `token` int32[1] → the scaled embedding row, called once per token;
- **text encoder**: signatures `encoder_64/128/256/512`, `embeddings` [1,S,768] + `input_mask`
  [1,S] → the final vector. The bidirectional Gemma 3 body, mean pooling over real tokens
  (prompt included), both dense projections and L2 normalisation are all in the graph.

Plus the HF tokenizer and metadata declaring `<bos>` (2) and `<eos>` (1), which the engine inserts
itself. Weights are int8 (dynamic range) with float activations: EmbeddingGemma's activations do
not tolerate fp16.

One trap: litert-torch 0.9.4 converts `matmul(q, repeat_kv(k).transpose(2, 3))` — how
transformers matches Gemma 3's single key/value head to its three query heads — wrongly, with no
error. The converter registers its own attention function that groups the query heads instead;
the arithmetic is identical and converts exactly.

Every stage is checked against sentence-transformers running the original weights: the split
modules, the fp32 and int8 graphs driven token by token as the runtime does (with garbage in the
padding rows), and finally the packed bundle through LiteRT-LM itself (`gate_bundle.py`),
including the model card's example similarities.
