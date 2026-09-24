#!/usr/bin/env python3
"""Picks the inject threshold for an embedding bundle, the way the app will use it.

    python calibrate_threshold.py <bundle.litertlm>

A recalled exchange goes into the prompt when its cosine similarity to the user's message
reaches the threshold (or it shares a rare word). Too low and every turn carries unrelated old
chat; too high and the memory stays silent. This scores labelled pairs through the bundle exactly
as the app does — archived text as a document ("User: … / Assistant: …"), the message as a query,
first 256 dimensions, int8 with a per-vector scale — and reports the separation.

Negatives include hard ones: another fact of the same kind ("who is my CA?" against the dentist),
because those are the recalls that would actually mislead.
"""
import argparse
import sys

import numpy as np

QUERY = "task: search result | query: "
DOCUMENT = "title: none | text: "
DIMS = 256


def chunk(user, reply="Noted."):
    return f"User: {user}\nAssistant: {reply}"


# (archived exchange, a later message, should it be recalled?)
PAIRS = [
    # --- English, related
    (chunk("my CA is Mr. Iyer, his office is in Jayanagar", "Got it, Mr. Iyer in Jayanagar is your CA."), "who does my taxes?", True),
    (chunk("my dentist is Dr. Rao near Indiranagar"), "I have a toothache, who should I call", True),
    (chunk("let's plan Gokarna for the second weekend of October", "Sounds great, Gokarna on the second weekend of October."), "what did we decide about the trip", True),
    (chunk("my daughter's school annual day is on 14 December"), "when is the school function", True),
    (chunk("I'm allergic to peanuts", "Thanks, I'll keep that in mind for recipes."), "can I eat this satay sauce", True),
    (chunk("my car insurance renews in May with ICICI Lombard"), "when is the vehicle policy due", True),
    (chunk("I started running 5k three times a week", "That's a great routine!"), "how is my exercise plan going", True),
    (chunk("the wifi password is on the sticker under the router"), "how do I get on the home internet", True),
    (chunk("I want to learn Spanish before my Barcelona trip next year"), "any tips for my language learning", True),
    (chunk("my landlord Suresh wants the rent by the 5th every month"), "when do I need to pay rent", True),
    (chunk("I keep my passport in the blue folder in the cupboard"), "where did I put my passport", True),
    (chunk("my son's football coach is Mr. D'Souza, practice is Saturday 7am"), "what time is football practice", True),
    (chunk("generate a website for an ice-cream shop in HTML", "Here is a basic HTML page for an ice-cream shop..."), "what was that web page you made for me", True),
    (chunk("my blood pressure was 140/90 at the last checkup", "That's a bit high, worth following up."), "what did the doctor say about my BP", True),
    (chunk("I prefer window seats and vegetarian meals on flights"), "book me something for the Delhi flight", True),
    (chunk("we're thinking of getting a golden retriever puppy"), "remind me what dog breed we wanted", True),
    # --- Hinglish, related
    (chunk("Amma ka birthday 12 March ko hai"), "mother's birthday kab hai", True),
    (chunk("kal Rahul ke saath lunch hai Koramangala mein, 1 baje"), "what are my plans with Rahul", True),
    (chunk("mera gym membership 30 November ko khatam ho raha hai"), "when does my gym plan expire", True),
    (chunk("electricity ka bill har mahine 10 tarikh tak bharna hai"), "bijli bill kab tak pay karna hai", True),
    (chunk("Priya ko chocolate cake bahut pasand hai"), "what cake should I get for Priya", True),
    (chunk("office ka laptop Dell hai, IT wale Ramesh bhai hain"), "laptop kharab ho gaya, kisko bolu", True),
    # --- Hindi / Malayalam, related
    (chunk("मेरी बहन का नाम प्रिया है"), "what is my sister's name", True),
    (chunk("मेरी दवाई सुबह नाश्ते के बाद लेनी है"), "when should I take my medicine", True),
    (chunk("എന്റെ ഡോക്ടറുടെ പേര് ഡോ. മേനോൻ ആണ്"), "who is my doctor", True),
    (chunk("അച്ഛന്റെ ഓപ്പറേഷൻ അടുത്ത ചൊവ്വാഴ്ചയാണ്"), "when is father's surgery", True),

    # --- hard negatives: same kind of thing, different fact
    (chunk("my CA is Mr. Iyer, his office is in Jayanagar"), "who is my dentist?", False),
    (chunk("my dentist is Dr. Rao near Indiranagar"), "who is my CA?", False),
    (chunk("let's plan Gokarna for the second weekend of October"), "what time is my flight to Delhi tomorrow", False),
    (chunk("my daughter's school annual day is on 14 December"), "when is my son's football practice", False),
    (chunk("I'm allergic to peanuts"), "am I allergic to dust?", False),
    (chunk("my car insurance renews in May with ICICI Lombard"), "when does my health insurance renew", False),
    (chunk("I started running 5k three times a week"), "what did I eat for dinner yesterday", False),
    (chunk("my landlord Suresh wants the rent by the 5th every month"), "when is the electricity bill due", False),
    (chunk("I keep my passport in the blue folder in the cupboard"), "where are my car keys", False),
    (chunk("Amma ka birthday 12 March ko hai"), "Rahul ka birthday kab hai", False),
    (chunk("kal Rahul ke saath lunch hai Koramangala mein, 1 baje"), "what are my plans with Anjali", False),
    (chunk("mera gym membership 30 November ko khatam ho raha hai"), "when does my Netflix subscription end", False),
    (chunk("मेरी बहन का नाम प्रिया है"), "what is my brother's name", False),
    (chunk("എന്റെ ഡോക്ടറുടെ പേര് ഡോ. മേനോൻ ആണ്"), "who is my lawyer", False),
    (chunk("my blood pressure was 140/90 at the last checkup"), "what was my cholesterol result", False),
    (chunk("I prefer window seats and vegetarian meals on flights"), "what hotel do I like in Goa", False),
    (chunk("generate a website for an ice-cream shop in HTML"), "write me a Python script to rename files", False),
    # --- plain negatives: nothing to do with it
    (chunk("my CA is Mr. Iyer, his office is in Jayanagar"), "what's a good pasta recipe", False),
    (chunk("my dentist is Dr. Rao near Indiranagar"), "explain how black holes form", False),
    (chunk("let's plan Gokarna for the second weekend of October"), "fix this null pointer exception", False),
    (chunk("my daughter's school annual day is on 14 December"), "what's the capital of Peru", False),
    (chunk("I'm allergic to peanuts"), "set an alarm for 6", False),
    (chunk("my car insurance renews in May with ICICI Lombard"), "write a poem about the sea", False),
    (chunk("I started running 5k three times a week"), "translate good morning into French", False),
    (chunk("the wifi password is on the sticker under the router"), "recommend a thriller novel", False),
    (chunk("Amma ka birthday 12 March ko hai"), "how do I reset my phone", False),
    (chunk("kal Rahul ke saath lunch hai Koramangala mein, 1 baje"), "summarise this article for me", False),
    (chunk("मेरी बहन का नाम प्रिया है"), "what's the weather like in winter", False),
    (chunk("electricity ka bill har mahine 10 tarikh tak bharna hai"), "tell me a joke", False),
    (chunk("Priya ko chocolate cake bahut pasand hai"), "how does compound interest work", False),
    (chunk("office ka laptop Dell hai, IT wale Ramesh bhai hain"), "who won the 2011 world cup", False),
    (chunk("I want to learn Spanish before my Barcelona trip next year"), "what's 15% of 2400", False),
    (chunk("we're thinking of getting a golden retriever puppy"), "how do I make filter coffee", False),
    (chunk("അച്ഛന്റെ ഓപ്പറേഷൻ അടുത്ത ചൊവ്വാഴ്ചയാണ്"), "suggest a name for my startup", False),
]


def quantize(v):
    v = np.asarray(v[:DIMS], dtype=np.float64)
    q = np.clip(np.rint(v / np.abs(v).max() * 127), -127, 127)
    return q


def cosine(a, b):
    return float(a @ b / (np.linalg.norm(a) * np.linalg.norm(b)))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("bundle")
    args = ap.parse_args()

    from litert_lm import interfaces
    from litert_lm.embedding_engine import EmbeddingEngine, EmbeddingOptions

    engine = EmbeddingEngine(args.bundle, backend=interfaces.CPU(thread_count=4))
    options = EmbeddingOptions(normalize=True, output_size=DIMS)

    def embed(text):
        return quantize(engine.compute_embedding(text, options).embedding)

    scored = []
    for stored, asked, relevant in PAIRS:
        s = cosine(embed(DOCUMENT + stored), embed(QUERY + asked))
        scored.append((s, relevant, stored.splitlines()[0][6:46], asked))

    pos = sorted(s for s, r, *_ in scored if r)
    neg = sorted(s for s, r, *_ in scored if not r)
    print(f"related   n={len(pos):2d}  min {pos[0]:.3f}  p10 {np.percentile(pos, 10):.3f}  median {np.median(pos):.3f}")
    print(f"unrelated n={len(neg):2d}  max {neg[-1]:.3f}  p90 {np.percentile(neg, 90):.3f}  median {np.median(neg):.3f}")

    best = None
    print("\n  τ     recall  precision  F1   (of related pairs recalled / of recalls that are right)")
    for tau in np.arange(0.30, 0.71, 0.02):
        tp = sum(1 for s, r, *_ in scored if s >= tau and r)
        fp = sum(1 for s, r, *_ in scored if s >= tau and not r)
        recall = tp / len(pos)
        precision = tp / (tp + fp) if tp + fp else 1.0
        f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
        # A wrong recall costs more than a missed one: the model may act on it.
        f_half = 1.25 * precision * recall / (0.25 * precision + recall) if precision + recall else 0.0
        print(f"  {tau:.2f}  {recall:6.2f}  {precision:9.2f}  {f1:.2f}")
        if best is None or f_half > best[0]:
            best = (f_half, tau)
    print(f"\nbest F0.5 threshold: {best[1]:.2f}")

    print("\nclosest calls:")
    for s, r, stored, asked in sorted(scored, key=lambda x: abs(x[0] - best[1]))[:12]:
        print(f"  {'+' if r else '-'} {s:.3f}  {stored!r:44} ← {asked!r}")


if __name__ == "__main__":
    sys.exit(main())
