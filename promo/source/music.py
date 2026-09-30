"""An original 120 BPM electro-house cue for the promo, synthesised from scratch (no samples).

Structure (bars of 2 s):
  0-4 s    intro: filtered chords, four hits under the hook words, snare roll + riser
  4 s      drop
  4-36 s   main groove; arp joins at 12 s; a whoosh under every scene cut
  36-40 s  breakdown + riser (memory-layers query)
  40 s     second drop, 40-56 s full energy
  56-60 s  final hit, ringing chord, fade
"""
import os
import numpy as np
from scipy import signal

SR = 44100
BPM = 120
BEAT = 60 / BPM
BAR = 4 * BEAT
DUR = 64.0
N = int(SR * (DUR + 0.5))
rng = np.random.default_rng(7)
P = os.path.dirname(os.path.abspath(__file__))


def midi(n):
    return 440.0 * 2 ** ((n - 69) / 12)


def t_arr(sec):
    return np.arange(int(sec * SR)) / SR


def add(buf, x, at, gain=1.0, pan=0.0):
    """Mix mono or stereo x into stereo buf at time `at` (s), constant-power pan."""
    i = int(round(at * SR))
    if i >= buf.shape[0]:
        return
    if x.ndim == 1:
        l = np.cos((pan + 1) * np.pi / 4)
        r = np.sin((pan + 1) * np.pi / 4)
        x = np.stack([x * l * 1.414, x * r * 1.414], 1)
    n = min(len(x), buf.shape[0] - i)
    if i < 0:
        x = x[-i:]
        n = min(len(x), buf.shape[0])
        i = 0
    buf[i:i + n] += x[:n] * gain


def lp(x, fc, order=2):
    b, a = signal.butter(order, min(fc, SR * 0.45) / (SR / 2), 'low')
    return signal.lfilter(b, a, x, axis=0)


def hp(x, fc, order=2):
    b, a = signal.butter(order, fc / (SR / 2), 'high')
    return signal.lfilter(b, a, x, axis=0)


def bp(x, lo, hi, order=2):
    b, a = signal.butter(order, [lo / (SR / 2), min(hi, SR * 0.45) / (SR / 2)], 'band')
    return signal.lfilter(b, a, x, axis=0)


def saw(freq, sec, phase=0.0, os_=4):
    """Naive saw at 4x oversampling, decimated: clean enough under a low-pass."""
    n = int(sec * SR * os_)
    ph = (phase + np.cumsum(np.full(n, freq / (SR * os_)))) % 1.0
    return signal.resample_poly(2 * ph - 1, 1, os_)


def sweep_lp(x, f0, f1, steps=64):
    """Time-varying low-pass by crossfading blocks (cheap and smooth enough)."""
    out = np.zeros_like(x)
    n = len(x)
    edges = np.linspace(0, n, steps + 1).astype(int)
    zi = None
    for k in range(steps):
        fc = f0 * (f1 / f0) ** (k / max(1, steps - 1))
        b, a = signal.butter(2, min(fc, SR * 0.45) / (SR / 2), 'low')
        seg = x[edges[k]:edges[k + 1]]
        if zi is None:
            zi = signal.lfilter_zi(b, a) * 0
            if x.ndim == 2:
                zi = np.zeros((2, x.shape[1]))
        y, zi = signal.lfilter(b, a, seg, axis=0, zi=zi)
        out[edges[k]:edges[k + 1]] = y
    return out


# ---------------------------------------------------------------- drums
def kick(sec=0.45):
    t = t_arr(sec)
    f = 46 + 120 * np.exp(-t / 0.028) + 30 * np.exp(-t / 0.12)
    ph = 2 * np.pi * np.cumsum(f) / SR
    body = np.sin(ph) * np.exp(-t / 0.30)
    click = hp(rng.standard_normal(len(t)), 2500) * np.exp(-t / 0.003) * 0.35
    return np.tanh(1.8 * (body + click)) * 0.95


def clap(sec=0.35):
    t = t_arr(sec)
    n = rng.standard_normal(len(t))
    env = np.zeros_like(t)
    for d in (0.0, 0.009, 0.018):
        env += (t >= d) * np.exp(-np.clip(t - d, 0, None) / 0.006)
    env += (t >= 0.026) * np.exp(-np.clip(t - 0.026, 0, None) / 0.11) * 0.8
    x = bp(n, 900, 5200) * env
    body = np.sin(2 * np.pi * 190 * t) * np.exp(-t / 0.04) * 0.25
    return (x + body) * 0.8


def snare(sec=0.25):
    t = t_arr(sec)
    n = bp(rng.standard_normal(len(t)), 1500, 9000) * np.exp(-t / 0.07)
    tone = np.sin(2 * np.pi * 210 * t) * np.exp(-t / 0.05)
    return (n * 0.8 + tone * 0.5) * 0.7


def hat(open_=False):
    sec = 0.35 if open_ else 0.06
    t = t_arr(sec)
    n = hp(rng.standard_normal(len(t)), 7500 if not open_ else 6000, 4)
    # a little metallic ring
    m = sum(np.sign(np.sin(2 * np.pi * f * t)) for f in (3140, 4260, 5530, 6470)) * 0.08
    x = (n + hp(m, 5000)) * np.exp(-t / (0.12 if open_ else 0.018))
    return x * (0.35 if open_ else 0.28)


def crash(sec=2.6):
    t = t_arr(sec)
    n = hp(rng.standard_normal((len(t), 2)), 3500, 2)
    m = sum(np.sin(2 * np.pi * f * t + rng.uniform(0, 6)) for f in (2950, 3730, 5210, 7120))
    x = (n + 0.15 * m[:, None]) * np.exp(-t / 0.9)[:, None]
    return x * 0.33


def impact(sec=3.0):
    t = t_arr(sec)
    f = 30 + 70 * np.exp(-t / 0.08)
    boom = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t / 0.9)
    noise = lp(rng.standard_normal(len(t)), 1800) * np.exp(-t / 0.25) * 0.6
    return np.tanh(1.5 * (boom + noise)) * 0.9


def riser(sec):
    t = t_arr(sec)
    n = rng.standard_normal((len(t), 2))
    x = np.zeros_like(n)
    steps = 48
    edges = np.linspace(0, len(t), steps + 1).astype(int)
    for k in range(steps):
        c = 400 * (9000 / 400) ** (k / (steps - 1))
        seg = n[edges[k]:edges[k + 1]]
        x[edges[k]:edges[k + 1]] = bp(seg, c * 0.7, c * 1.3, 1)
    amp = (t / sec) ** 2.2
    tone = np.sin(2 * np.pi * np.cumsum(200 * (8 ** (t / sec))) / SR) * 0.18
    return (x * 1.3 + tone[:, None]) * amp[:, None] * 0.5


def whoosh(sec=0.55):
    t = t_arr(sec)
    n = rng.standard_normal(len(t))
    steps = 24
    edges = np.linspace(0, len(t), steps + 1).astype(int)
    x = np.zeros_like(n)
    for k in range(steps):
        u = k / (steps - 1)
        c = 500 + 5500 * np.sin(np.pi * u) ** 1.5
        x[edges[k]:edges[k + 1]] = bp(n[edges[k]:edges[k + 1]], c * 0.6, c * 1.4, 1)
    env = np.sin(np.pi * np.clip(t / sec, 0, 1)) ** 2
    env = env * np.clip((t / sec) * 1.6, 0, 1)
    pan = np.linspace(-0.8, 0.8, len(t))
    l, r = np.cos((pan + 1) * np.pi / 4), np.sin((pan + 1) * np.pi / 4)
    y = x * env * 0.55
    return np.stack([y * l, y * r], 1) * 1.4


# ---------------------------------------------------------------- harmony
# F minor: Fm - Db - Ab - Eb, one chord per bar.
CHORDS = [
    [53, 56, 60, 65, 68],   # Fm   F3 Ab3 C4 F4 Ab4
    [49, 53, 56, 61, 65],   # Db
    [48, 51, 56, 60, 63],   # Ab/C
    [51, 55, 58, 63, 67],   # Eb
]
BASS = [41, 37, 44, 39]      # F2 Db2 Ab2 Eb2


def supersaw_chord(notes, sec, voices=7, detune=0.012):
    out = np.zeros((int(sec * SR), 2))
    for n in notes:
        f0 = midi(n + 12)
        for v in range(voices):
            d = (v - (voices - 1) / 2) / ((voices - 1) / 2)
            f = f0 * (1 + detune * d)
            x = saw(f, sec, phase=rng.uniform())[:out.shape[0]]
            pan = d * 0.8
            out[:len(x), 0] += x * np.cos((pan + 1) * np.pi / 4)
            out[:len(x), 1] += x * np.sin((pan + 1) * np.pi / 4)
    return out / (len(notes) * voices) * 2.2


def pluck(freq, sec=0.3):
    t = t_arr(sec)
    x = saw(freq, sec)[:len(t)] * 0.6 + np.sign(np.sin(2 * np.pi * freq * t)) * 0.25
    y = lp(x * np.exp(-t / 0.09), 3200)
    return y * 0.5


def bass_note(freq, sec):
    t = t_arr(sec)
    x = saw(freq, sec)[:len(t)]
    sub = np.sin(2 * np.pi * freq / 2 * t)
    env = np.minimum(1, t / 0.005) * np.exp(-t / 0.5)
    y = lp(x, 700) * 0.7 + sub * 0.6
    return np.tanh(1.6 * y * env) * 0.6


def reverb_ir(sec=2.2, decay=0.55):
    t = t_arr(sec)
    n = rng.standard_normal((len(t), 2))
    ir = n * np.exp(-t / decay)[:, None]
    ir = lp(ir, 6000)
    ir[:int(0.012 * SR)] = 0
    return ir / np.sqrt((ir ** 2).sum(0))


def sidechain(n_samples, beats, depth=0.8, release=0.19):
    """Gain curve that ducks at every kick time."""
    g = np.ones(n_samples)
    for b in beats:
        i = int(b * SR)
        if i >= n_samples:
            continue
        L = min(int(release * 2.5 * SR), n_samples - i)
        tt = np.arange(L) / SR
        g[i:i + L] = np.minimum(g[i:i + L], 1 - depth * np.exp(-tt / release))
    return g


def build():
    drums = np.zeros((N, 2))
    music = np.zeros((N, 2))
    fx = np.zeros((N, 2))
    verb_send = np.zeros((N, 2))

    groove = lambda t: (4.0 <= t < 40.0) or (44.0 <= t < 60.0)
    K, C, S = kick(), clap(), snare()
    HC, HO = hat(False), hat(True)

    kicks = []
    # hook hits under the four words (0,1,2,3 s)
    for tt in (0.0, 1.0, 2.0, 3.0):
        add(drums, K, tt, 0.9)
        add(drums, C, tt, 0.55)
        add(verb_send, C, tt, 0.5)
        add(fx, impact(1.2)[:int(0.9 * SR)], tt, 0.35)
        kicks.append(tt)
    # snare roll into the drop (2-4 s) and into the second drop (38-40 s)
    for start in (2.0, 42.0):
        steps = [(start + i * BEAT / 2, 0.25 + 0.2 * i / 8) for i in range(4)]
        steps += [(start + 1.0 + i * BEAT / 4, 0.35 + 0.35 * i / 8) for i in range(8)]
        for tt, g in steps:
            add(drums, S, tt, g)
            add(verb_send, S, tt, g * 0.6)

    nb = int(DUR / BEAT)
    for b in range(nb):
        tt = b * BEAT
        if groove(tt):
            add(drums, K, tt, 1.0)
            kicks.append(tt)
            if b % 2 == 1:
                add(drums, C, tt, 0.75)
                add(verb_send, C, tt, 0.35)
            add(drums, HO, tt + BEAT / 2, 0.55, pan=0.25)
            for s in range(4):
                acc = 1.0 if s == 2 else 0.55
                if s != 2:
                    add(drums, HC, tt + s * BEAT / 4, 0.5 * acc, pan=-0.3 if s % 2 else 0.3)
            # fill on the last beat of every 4th bar
            if b % 16 == 15:
                for s in range(4):
                    add(drums, S, tt + s * BEAT / 4, 0.3 + 0.12 * s)
        # breakdown: half-time claps keep pulse
        if 40.0 <= tt < 42.0 and b % 4 == 2:
            add(drums, C, tt, 0.5)
            add(verb_send, C, tt, 0.6)

    # drops and big moments
    for tt, g in ((4.0, 1.0), (44.0, 1.0), (60.0, 1.1)):
        add(fx, crash(), tt, 0.9 * g)
        add(fx, impact(), tt, 0.85 * g)
        add(verb_send, impact(), tt, 0.25)
    add(fx, riser(2.0), 2.0, 0.9)
    add(fx, riser(4.0), 40.0, 1.0)
    # a whoosh peaking at every scene cut
    for cut in (8, 16, 20, 24, 28, 32, 36, 48, 52, 56):
        add(fx, whoosh(0.55), cut - 0.36, 0.55)

    # chords per bar
    nbar = int(DUR / BAR)
    chords = np.zeros((N, 2))
    for bar in range(nbar + 1):
        t0 = bar * BAR
        if t0 >= DUR:
            break
        ch = CHORDS[bar % 4]
        if t0 < 4.0:
            seg = supersaw_chord(ch, BAR + 0.05)
            seg = sweep_lp(seg, 300 + 500 * bar, 900 + 1800 * bar)
            add(chords, seg, t0, 0.55)
        elif 40.0 <= t0 < 44.0:
            seg = supersaw_chord(ch, BAR + 0.05)
            seg = sweep_lp(seg, 2200 if t0 < 42 else 900, 900 if t0 < 42 else 4000)
            add(chords, seg, t0, 0.5)
        elif t0 >= 60.0:
            seg = supersaw_chord(CHORDS[0], 4.2)
            t = t_arr(4.2)[:len(seg)]
            seg = lp(seg, 5000) * np.exp(-t / 1.4)[:, None]
            add(chords, seg, t0, 0.8)
            add(verb_send, seg, t0, 0.5)
        else:
            # stabs on the off-beats plus a sustained bed
            bed = lp(supersaw_chord(ch, BAR + 0.05), 2600 if t0 < 44 else 4200)
            add(chords, bed, t0, 0.42)
            for k in range(4):
                st = supersaw_chord(ch, 0.22)
                tt = t_arr(0.22)[:len(st)]
                st = lp(st, 5200) * np.exp(-tt / 0.08)[:, None]
                add(chords, st, t0 + k * BEAT + BEAT / 2, 0.55)
        # bass: off-beat 8ths in the groove
        if groove(t0) or t0 >= 60.0:
            f = midi(BASS[bar % 4])
            if t0 >= 60.0:
                add(music, bass_note(f, 2.5), t0, 0.8)
            else:
                for k in range(4):
                    add(music, bass_note(f, BEAT / 2 - 0.01), t0 + k * BEAT + BEAT / 2, 0.8)
                    if k in (1, 3):
                        add(music, bass_note(f * 2, BEAT / 4), t0 + k * BEAT + 3 * BEAT / 4, 0.35)
        # arp from 12 s, and all through the second drop
        if (8.0 <= t0 < 40.0) or (44.0 <= t0 < 60.0):
            tones = [ch[1], ch[2], ch[3], ch[4], ch[3] + 12, ch[4], ch[3], ch[2]]
            for s in range(16):
                n = tones[s % 8] + 12
                pan = -0.45 if s % 2 else 0.45
                x = pluck(midi(n), 0.28)
                add(music, x, t0 + s * BEAT / 4, 0.30, pan=pan)
                add(verb_send, x, t0 + s * BEAT / 4, 0.12)
                # 3/16 ping-pong echo
                add(music, x, t0 + s * BEAT / 4 + 3 * BEAT / 4, 0.12, pan=-pan)

    sc = sidechain(N, kicks, depth=0.75)
    chords *= sc[:, None]
    music *= (0.35 + 0.65 * sc)[:, None]
    verb = signal.fftconvolve(verb_send, reverb_ir(), axes=0)[:N]

    mix = drums * 0.55 + music * 0.9 + chords * 2.4 + fx * 0.7 + verb * 0.4
    mix = hp(mix, 28)
    # outro fade 58.6 -> 60
    t = np.arange(N) / SR
    fade = np.clip((64.0 - t) / 1.4, 0, 1) ** 1.5
    mix *= fade[:, None]
    # glue: gentle saturation, then peak normalise
    pk=np.abs(mix).max(); rms=np.sqrt((mix**2).mean()); print('pre-sat peak',pk,'rms',rms, 'p99.9', np.percentile(np.abs(mix),99.9))
    for nm,bus in (('drums',drums*0.55),('music',music*0.9),('chords',chords*2.4),('fx',fx*0.7),('verb',verb*0.4)): print(nm, round(float(np.abs(bus).max()),2), round(float(np.sqrt((bus**2).mean())),3))
    mix = mix / np.percentile(np.abs(mix), 99.9) * 0.9
    mix = np.tanh(mix * 1.1) / np.tanh(1.1)
    mix /= np.abs(mix).max() + 1e-9
    mix *= 0.93
    return mix[:int(DUR * SR)]


if __name__ == '__main__':
    from scipy.io import wavfile
    m = build()
    out = os.path.join(P, 'music.wav')
    wavfile.write(out, SR, (m * 32767).astype(np.int16))
    print('wrote', out, m.shape)
