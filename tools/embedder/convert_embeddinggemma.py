#!/usr/bin/env python3
"""EmbeddingGemma-300M -> a LiteRT-LM EmbeddingEngine bundle (.litertlm) for the app.

    python convert_embeddinggemma.py <hf_model_dir_or_id> <out_dir> [--seqs 512,256,128,64]
    python convert_embeddinggemma.py --random-init <out_dir>      # pipeline check, no weights

Google publishes EmbeddingGemma for LiteRT only as a single gated .tflite plus a separate
tokenizer, which LiteRT-LM's EmbeddingEngine cannot load. The engine (litert-lm 0.17,
runtime/executor/embedding_litert_compiled_model_executor.cc) wants the model split in two:

  TF_LITE_EMBEDDER      one signature, one input `token` int32[1] -> f32 [1,1,768]: the token
                        embedding row (Gemma's sqrt(768) scale included). Called once per token;
                        the rows fill the encoder's `embeddings` buffer.
  TF_LITE_TEXT_ENCODER  signatures `encoder_<S>`: `embeddings` f32 [1,S,768] + `input_mask`
                        f32 [1,S] (1 for real tokens, 0 after) -> f32 [1,768]. Everything after
                        the lookup is in the graph: the bidirectional Gemma 3 body, mean pooling
                        over real tokens (prompt included, as sentence-transformers does), the
                        two dense projections and L2 normalisation. The engine uses the smallest
                        signature that fits the tokens.
  tokenizer + EmbeddingMetadata: the engine does not run the tokenizer's post-processor, so the
                        <bos> / <eos> the model was trained with are declared in the metadata
                        and inserted by the engine (insert_special_tokens, on by default).

This follows the recipe of john-rocky/hf-to-litertlm (embedding_engine_work/), which built the
published Granite Embedding bundles the same way.

Checks along the way, each against sentence-transformers running the original weights:
  eager    the split modules (lookup -> encoder) equal the reference;
  tflite   the exported fp32 and int8 graphs, driven token by token as the runtime does;
  card     the model card's example similarities (0.3011, 0.6359, 0.4930, 0.4889).
The packed bundle is then checked through LiteRT-LM itself by gate_bundle.py.
"""
import argparse
import collections
import json
import os
import subprocess
import sys

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F

QUERY_PROMPT = "task: search result | query: "
DOCUMENT_PROMPT = "title: none | text: "

# The model card's example: encode_query(query) against encode_document(documents).
CARD_QUERY = "Which planet is known as the Red Planet?"
CARD_DOCUMENTS = [
    "Venus is often called Earth's twin because of its similar size and proximity.",
    "Mars, known for its reddish appearance, is often referred to as the Red Planet.",
    "Jupiter, the largest planet in our solar system, has a prominent red spot.",
    "Saturn, famous for its rings, is sometimes mistaken for the Red Planet.",
]
CARD_SIMILARITIES = [0.3011, 0.6359, 0.4930, 0.4889]

# Mixed scripts and lengths, the way the app's archive looks.
SAMPLE_TEXTS = [
    "User: my CA is Mr. Iyer, his office is in Jayanagar\nAssistant: Noted, Mr. Iyer is your CA.",
    "kal Rahul ke saath lunch hai Koramangala mein, 1 baje",
    "मेरी बहन का नाम प्रिया है और वह पुणे में रहती है",
    "എന്റെ ഡോക്ടറുടെ പേര് ഡോ. മേനോൻ ആണ്",
    "remind me to pay the electricity bill every month",
    "hi",
]

NEG = -1e9  # additive mask value; finite so a fully-masked row cannot turn into NaN


def grouped_attention(module, query, key, value, attention_mask, dropout=0.0, scaling=None, softcap=None, **kwargs):
    """Eager attention without `repeat_kv`.

    Gemma 3 has fewer key/value heads than query heads (EmbeddingGemma: 3 and 1), and
    transformers matches them with `repeat_kv` — an expand and reshape. litert-torch 0.9.4
    converts `matmul(q, repeat_kv(k).transpose(2, 3))` wrongly (a max error of 74 on unit-scale
    inputs, while `repeat_kv` alone and each matmul alone convert exactly). Grouping the query
    heads that share a key head gives the same arithmetic as plain batched matmuls.
    """
    B, H, S, D = query.shape
    KV = key.shape[1]
    G = H // KV
    if scaling is None:
        scaling = module.head_dim ** -0.5
    w = torch.matmul(query.reshape(B, KV, G * S, D), key.transpose(2, 3)) * scaling
    w = w.reshape(B, H, S, key.shape[2])
    if softcap is not None:
        w = torch.tanh(w / softcap) * softcap
    if attention_mask is not None:
        if attention_mask.dtype == torch.bool:
            attention_mask = torch.where(attention_mask, 0.0, NEG)
        w = w + attention_mask
    w = F.softmax(w, dim=-1, dtype=torch.float32).to(query.dtype)
    out = torch.matmul(w.reshape(B, KV, G * S, key.shape[2]), value)       # [B, KV, G*S, D]
    return out.reshape(B, H, S, D).transpose(1, 2).contiguous(), None      # [B, S, H, D], as eager


def use_grouped_attention(body):
    from transformers import AttentionInterface
    from transformers.masking_utils import AttentionMaskInterface, eager_mask

    AttentionInterface.register("litert_grouped", grouped_attention)
    # Masks built by transformers (the sentence-transformers reference) come in eager's form.
    AttentionMaskInterface.register("litert_grouped", eager_mask)
    body.config._attn_implementation = "litert_grouped"


class Lookup(nn.Module):
    """token int32[1] -> scaled embedding row f32[1,1,D], exactly what the body reads."""

    def __init__(self, embed_tokens):
        super().__init__()
        self.embed_tokens = embed_tokens

    def forward(self, token):
        return self.embed_tokens(token)[None]


class Encoder(nn.Module):
    """embeddings f32[1,S,D] + input_mask f32[1,S] -> normalised f32[1,D]."""

    def __init__(self, body, dense1, dense2, sliding_window):
        super().__init__()
        self.body = body
        self.dense1 = dense1
        self.dense2 = dense2
        self.sliding_window = sliding_window

    def forward(self, embeddings, input_mask):
        S = embeddings.shape[1]
        valid1 = input_mask > 0.5                                           # [1,S]
        # The runtime fills only the first rows; whatever sits in the rest must not matter.
        emb = torch.where(valid1[:, :, None], embeddings, torch.zeros((), dtype=embeddings.dtype))
        valid = valid1[:, None, None, :]                                    # keys [1,1,1,S]
        q = torch.arange(S)[:, None]
        k = torch.arange(S)[None, :]
        # Pad rows attend to themselves only, so no row is all -inf.
        eye = (q == k)[None, None]
        zero = torch.zeros((), dtype=torch.float32)
        neg = torch.full((), NEG, dtype=torch.float32)
        full = torch.where(valid | eye, zero, neg)
        band = ((q - k).abs() < self.sliding_window)[None, None]
        sliding = torch.where((valid & band) | eye, zero, neg)
        h = self.body(
            inputs_embeds=emb,
            attention_mask={"full_attention": full, "sliding_attention": sliding},
            position_ids=torch.arange(S)[None],
            use_cache=False,
        ).last_hidden_state
        m = valid1[:, :, None]
        pooled = torch.where(m, h, torch.zeros((), dtype=h.dtype)).sum(dim=1) / m.to(h.dtype).sum(dim=1)
        return F.normalize(self.dense2(self.dense1(pooled)), p=2, dim=-1)


def load(model_id, random_init):
    from sentence_transformers import SentenceTransformer

    if not random_init:
        st = SentenceTransformer(
            model_id, device="cpu",
            model_kwargs={"attn_implementation": "eager", "dtype": torch.float32},
        )
        return st.eval()

    # Same architecture, small and random: for checking the pipeline before the weights arrive.
    import tempfile
    from sentence_transformers import models
    from tokenizers import Tokenizer, models as tm, pre_tokenizers, processors
    from transformers import Gemma3TextConfig, Gemma3TextModel, PreTrainedTokenizerFast

    vocab = {f"t{i}": i for i in range(4, 4096)}
    vocab.update({"<pad>": 0, "<eos>": 1, "<bos>": 2, "<unk>": 3})
    tok = Tokenizer(tm.WordLevel(vocab, unk_token="<unk>"))
    tok.pre_tokenizer = pre_tokenizers.Whitespace()
    tok.post_processor = processors.TemplateProcessing(single="<bos> $A <eos>", special_tokens=[("<bos>", 2), ("<eos>", 1)])
    hf_tok = PreTrainedTokenizerFast(tokenizer_object=tok, bos_token="<bos>", eos_token="<eos>", pad_token="<pad>", unk_token="<unk>")
    config = Gemma3TextConfig(
        vocab_size=4096, hidden_size=768, intermediate_size=1152, num_hidden_layers=4,
        num_attention_heads=3, num_key_value_heads=1, head_dim=256, sliding_window=512,
        max_position_embeddings=2048, use_bidirectional_attention=True,
        layer_types=["sliding_attention", "sliding_attention", "sliding_attention", "full_attention"],
    )
    torch.manual_seed(0)
    directory = tempfile.mkdtemp(prefix="eg-random-")
    Gemma3TextModel(config).save_pretrained(directory)
    hf_tok.save_pretrained(directory)
    transformer = models.Transformer(directory, model_kwargs={"attn_implementation": "eager", "dtype": torch.float32})
    pooling = models.Pooling(768, pooling_mode="mean", include_prompt=True)
    dense1 = models.Dense(768, 3072, bias=False, activation_function=nn.Identity())
    dense2 = models.Dense(3072, 768, bias=False, activation_function=nn.Identity())
    st = SentenceTransformer(modules=[transformer, pooling, dense1, dense2, models.Normalize()], device="cpu")
    return st.eval()


def parts(st):
    """Backbone, the two dense layers, and a check that the pipeline is what the encoder rebuilds."""
    names = [type(m).__name__ for m in st]
    assert names[:1] == ["Transformer"] and names[1] == "Pooling" and names[-1] == "Normalize", names
    pooling = st[1].get_config_dict()
    assert pooling.get("pooling_mode") == "mean", pooling
    assert pooling.get("include_prompt", True), "prompt tokens must be pooled, as sentence-transformers does"
    denses = [m for m in st if type(m).__name__ == "Dense"]
    assert len(denses) == 2, names
    for d in denses:
        assert isinstance(d.activation_function, nn.Identity), d.activation_function
    return st[0].auto_model, denses[0].linear, denses[1].linear


def token_ids(st, text):
    """Token ids exactly as sentence-transformers feeds them (specials included)."""
    return st.tokenize([text])["input_ids"][0].tolist()


def reference(st, texts, prompt=""):
    with torch.inference_mode():
        return st.encode([prompt + t for t in texts], convert_to_numpy=True, normalize_embeddings=True)


def padded(ids, S):
    assert len(ids) <= S, f"{len(ids)} tokens do not fit {S}"
    x = torch.zeros((1, S), dtype=torch.int32)
    x[0, :len(ids)] = torch.tensor(ids, dtype=torch.int32)
    mask = torch.zeros((1, S), dtype=torch.float32)
    mask[0, :len(ids)] = 1.0
    return x, mask


def compose(lookup, encoder, ids, S):
    x, mask = padded(ids, S)
    with torch.no_grad():
        rows = torch.cat([lookup(x[0, i:i + 1]) for i in range(S)], dim=1)
        return encoder(rows, mask).numpy()[0]


def trace_sample(lookup, ids, S):
    x, mask = padded(ids, S)
    with torch.no_grad():
        rows = torch.cat([lookup(x[0, i:i + 1]) for i in range(S)], dim=1).detach().clone()
    return rows, mask


def tflite_embed(embedder_path, encoder_path, ids, S):
    """Drive the two graphs the way the runtime does: one lookup per token, then the encoder."""
    from ai_edge_litert.interpreter import Interpreter

    look = Interpreter(model_path=embedder_path)
    sig = list(look.get_signature_list())[0]
    runner = look.get_signature_runner(sig)
    in_name = list(look.get_signature_list()[sig]["inputs"])[0]
    D = None
    rows = []
    for t in ids:
        row = list(runner(**{in_name: np.array([t], dtype=np.int32)}).values())[0]
        D = row.shape[-1]
        rows.append(row.reshape(1, 1, D))
    # Pad rows hold garbage in the runtime; prove it does not matter.
    rows.append(np.full((1, S - len(ids), D), 1e4, dtype=np.float32))
    enc = Interpreter(model_path=encoder_path, num_threads=os.cpu_count())
    mask = np.zeros((1, S), dtype=np.float32)
    mask[0, :len(ids)] = 1.0
    out = enc.get_signature_runner(f"encoder_{S}")(embeddings=np.concatenate(rows, axis=1), input_mask=mask)
    return list(out.values())[0][0]


def op_report(path, tag):
    from ai_edge_litert.interpreter import Interpreter

    it = Interpreter(model_path=path)
    hist = collections.Counter(d["op_name"] for d in it._get_ops_details())
    print(f"{tag}: {os.path.getsize(path) / 1e6:.1f} MB, ops {dict(hist.most_common(12))}")
    print(f"{tag} signatures:", {k: (v["inputs"], v["outputs"]) for k, v in it.get_signature_list().items()})


def quantize(src, dst, configure):
    from ai_edge_quantizer import quantizer, recipe_manager

    rm = recipe_manager.RecipeManager()
    configure(rm)
    qt = quantizer.Quantizer(src, rm.get_quantization_recipe())
    assert not qt.need_calibration
    if os.path.exists(dst):
        os.remove(dst)
    qt.quantize().export_model(dst)


def smallest_fit(n, seqs):
    return min(s for s in seqs if s >= n)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("model", nargs="?", default="google/embeddinggemma-300m")
    ap.add_argument("out_dir")
    ap.add_argument("--seqs", default="512,256,128,64")
    ap.add_argument("--random-init", action="store_true")
    args = ap.parse_args()
    seqs = sorted(int(s) for s in args.seqs.split(","))
    os.makedirs(args.out_dir, exist_ok=True)
    P = lambda n: os.path.join(args.out_dir, n)  # noqa: E731
    report = {"model": "random-init" if args.random_init else args.model, "seqs": seqs}

    st = load(args.model, args.random_init)
    body, dense1, dense2 = parts(st)
    config = body.config
    assert getattr(config, "use_bidirectional_attention", False), "EmbeddingGemma's body is bidirectional"
    window = config.sliding_window
    print(f"body: {config.num_hidden_layers} layers, hidden {config.hidden_size}, vocab {config.vocab_size}, "
          f"sliding window {window}, layer types {collections.Counter(config.layer_types)}")
    lookup = Lookup(body.embed_tokens).eval()
    encoder = Encoder(body, dense1, dense2, window).eval()

    # Which specials the tokenizer adds: the engine has to add the same ones.
    probe = token_ids(st, "hello")
    tok = st.tokenizer
    specials = {"bos": tok.bos_token_id, "eos": tok.eos_token_id, "pad": tok.pad_token_id}
    adds_bos = probe[0] == tok.bos_token_id
    adds_eos = probe[-1] == tok.eos_token_id
    print(f"tokenizer: 'hello' -> {probe}; specials {specials}; adds bos {adds_bos}, eos {adds_eos}")
    report["specials"] = {"bos": tok.bos_token_id if adds_bos else None, "eos": tok.eos_token_id if adds_eos else None}

    # ---- eager: split modules == sentence-transformers (the reference with stock eager attention)
    texts = SAMPLE_TEXTS + [QUERY_PROMPT + CARD_QUERY] + [DOCUMENT_PROMPT + d for d in CARD_DOCUMENTS]
    ref = reference(st, texts)
    use_grouped_attention(body)
    worst = 1.0
    for text, r in zip(texts, ref):
        ids = token_ids(st, text)
        got = compose(lookup, encoder, ids, smallest_fit(len(ids), seqs))
        worst = min(worst, float(np.dot(r, got)))
    print(f"EAGER split vs sentence-transformers: worst cosine {worst:.7f}")
    assert worst > 0.99999, worst
    report["eager_worst_cos"] = worst

    import litert_torch

    # ---- export
    litert_torch.signature("embedder", lookup, sample_kwargs={"token": torch.tensor([2], dtype=torch.int32)}) \
        .convert().export(P("embedder_fp32.tflite"))
    op_report(P("embedder_fp32.tflite"), "embedder fp32")
    conv = None
    sample_ids = token_ids(st, DOCUMENT_PROMPT + SAMPLE_TEXTS[0])
    for S in reversed(seqs):
        rows, mask = trace_sample(lookup, sample_ids[:S], S)
        kw = {"embeddings": rows, "input_mask": mask}
        conv = (litert_torch.signature(f"encoder_{S}", encoder, sample_kwargs=kw) if conv is None
                else conv.signature(f"encoder_{S}", encoder, sample_kwargs=kw))
    conv.convert().export(P("encoder_fp32.tflite"))
    op_report(P("encoder_fp32.tflite"), "encoder fp32")

    def check(tag, embedder, enc):
        worst = 1.0
        for text, r in zip(texts, ref):
            ids = token_ids(st, text)
            got = tflite_embed(embedder, enc, ids, smallest_fit(len(ids), seqs))
            assert np.isfinite(got).all(), f"{tag}: non-finite output"
            worst = min(worst, float(np.dot(r, got / np.linalg.norm(got))))
        # Every signature, not just the ones the texts happened to pick.
        for S in seqs:
            ids = token_ids(st, SAMPLE_TEXTS[0])
            got = tflite_embed(embedder, enc, ids, S)
            worst = min(worst, float(np.dot(ref[0], got / np.linalg.norm(got))))
        print(f"TFLITE {tag}: worst cosine vs sentence-transformers {worst:.6f}")
        report[f"{tag}_worst_cos"] = worst
        return worst

    assert check("fp32", P("embedder_fp32.tflite"), P("encoder_fp32.tflite")) > 0.9999

    # ---- int8 weights, float activations (EmbeddingGemma's activations do not tolerate fp16)
    from ai_edge_quantizer import qtyping
    G, OP = qtyping.QuantGranularity, qtyping.TFLOperationName
    quantize(P("encoder_fp32.tflite"), P("encoder_wi8fc.tflite"),
             lambda rm: rm.add_dynamic_config(regex=".*", operation_name=OP.FULLY_CONNECTED, num_bits=8))
    quantize(P("embedder_fp32.tflite"), P("embedder_wi8.tflite"),
             lambda rm: rm.add_dynamic_config(regex=".*", operation_name=OP.EMBEDDING_LOOKUP,
                                              num_bits=8, granularity=G.CHANNELWISE))
    op_report(P("encoder_wi8fc.tflite"), "encoder wi8fc")
    op_report(P("embedder_wi8.tflite"), "embedder wi8")
    check("wi8", P("embedder_wi8.tflite"), P("encoder_wi8fc.tflite"))

    # ---- the model card's own example, through the int8 graphs at 768 dimensions
    def embed_int8(text):
        ids = token_ids(st, text)
        v = tflite_embed(P("embedder_wi8.tflite"), P("encoder_wi8fc.tflite"), ids, smallest_fit(len(ids), seqs))
        return v / np.linalg.norm(v)

    if not args.random_init:
        q = embed_int8(QUERY_PROMPT + CARD_QUERY)
        sims = [float(q @ embed_int8(DOCUMENT_PROMPT + d)) for d in CARD_DOCUMENTS]
        print("CARD similarities int8:", [f"{s:.4f}" for s in sims], "expected", CARD_SIMILARITIES)
        report["card_int8"] = sims

    # ---- the bundle: both graphs, the tokenizer, and which specials the engine must add
    st.tokenizer.save_pretrained(P("tokenizer"))
    meta = ["embedding_model_type { generic_model {} }"]
    if adds_bos:
        meta.append(f"bos_token {{ token_ids {{ ids: {tok.bos_token_id} }} }}")
    if adds_eos:
        meta.append(f"eos_token {{ token_ids {{ ids: {tok.eos_token_id} }} }}")
    with open(P("embedding_metadata.textproto"), "w") as f:
        f.write("\n".join(meta) + "\n")
    bundle = P(("random" if args.random_init else "embeddinggemma-300m") + "_wi8.litertlm")
    if os.path.exists(bundle):
        os.remove(bundle)
    subprocess.run([
        sys.executable, "-m", "litert_lm_builder.litertlm_builder_cli",
        "embedding_metadata", "--path", P("embedding_metadata.textproto"),
        "tflite_model", "--path", P("embedder_wi8.tflite"), "--model_type", "embedder",
        "tflite_model", "--path", P("encoder_wi8fc.tflite"), "--model_type", "text_encoder",
        "hf_tokenizer", "--path", P("tokenizer/tokenizer.json"),
        "output", "--path", bundle,
    ], check=True)
    report["bundle"] = {"path": bundle, "bytes": os.path.getsize(bundle)}
    print(f"BUNDLE {bundle}: {os.path.getsize(bundle) / 1e6:.1f} MB")

    with open(P("convert_report.json"), "w") as f:
        json.dump(report, f, indent=2)
    print("DONE:", args.out_dir)


if __name__ == "__main__":
    main()
