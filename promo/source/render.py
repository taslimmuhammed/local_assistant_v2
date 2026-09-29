"""Local Assistant promo — 1080x1920, 30 fps, 60 s. Every frame is a pure function of time.

    python render.py --still 5 13.5 ...     # PNG stills into stills/
    python render.py --video                # frames piped to ffmpeg -> video.mp4
"""
import math
import os
import subprocess
import sys
from functools import lru_cache

import numpy as np
from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont

P = os.path.dirname(os.path.abspath(__file__))
SCR = os.path.join(P, 'assets', 'screens')
W, H, FPS = 1080, 1920, 30
DUR = 60.0
BEAT = 0.5
SW_FULL, SH_FULL = 1272, 2772          # phone screenshot size

WHITE = (255, 255, 255)
INK = (24, 24, 27)
CYAN = (34, 211, 238)
BLUE = (59, 130, 246)
VIOLET = (139, 92, 246)
PINK = (236, 72, 153)
AMBER = (251, 191, 36)
GRAD = [CYAN, VIOLET, PINK]


# ------------------------------------------------------------------ easing
def clamp(x, a=0.0, b=1.0):
    return a if x < a else b if x > b else x


def prog(t, t0, d):
    return clamp((t - t0) / d)


def lerp(a, b, u):
    return a + (b - a) * u


def e_out3(u):
    return 1 - (1 - u) ** 3


def e_inout3(u):
    return 4 * u ** 3 if u < 0.5 else 1 - (-2 * u + 2) ** 3 / 2


def e_expo(u):
    return 1.0 if u >= 1 else 1 - 2 ** (-10 * u)


def e_back(u, s=1.7):
    c3 = s + 1
    return 1 + c3 * (u - 1) ** 3 + s * (u - 1) ** 2


def spring(x, zeta=0.42, w=13.0):
    """Damped spring from 0 to 1, x in seconds since start."""
    if x <= 0:
        return 0.0
    wd = w * math.sqrt(1 - zeta ** 2)
    return 1 - math.exp(-zeta * w * x) * (math.cos(wd * x) + zeta * w / wd * math.sin(wd * x))


def beat_pulse(t, decay=0.16):
    """1 on every beat, decaying — only inside the groove sections."""
    if not ((4.0 <= t < 36.0) or (40.0 <= t < 56.0)):
        return 0.0
    ph = t % BEAT
    return math.exp(-ph / decay)


# ------------------------------------------------------------------ fonts & text
@lru_cache(maxsize=None)
def font(size, weight=700, opsz=32, mono=False):
    f = ImageFont.truetype(os.path.join(P, 'fonts', 'JetBrainsMono.ttf' if mono else 'Inter.ttf'), size)
    f.set_variation_by_axes([weight] if mono else [opsz, weight])
    return f


def grad_colors(n, stops=GRAD):
    x = np.linspace(0, 1, n)
    segs = len(stops) - 1
    out = np.zeros((n, 3))
    for i in range(n):
        u = x[i] * segs
        k = min(int(u), segs - 1)
        f = u - k
        out[i] = np.array(stops[k]) * (1 - f) + np.array(stops[k + 1]) * f
    return out


@lru_cache(maxsize=4096)
def text_sprite(text, size, weight=800, color=WHITE, grad=None, opsz=32, mono=False, track=0.0):
    """RGBA sprite of a single line. Returns (img, pad, ascent). `grad` = (x0, x1) canvas span."""
    f = font(size, weight, opsz, mono)
    asc, desc = f.getmetrics()
    pad = int(size * 0.25)
    if track:
        widths = [f.getlength(c) for c in text]
        tw = sum(widths) + track * size * (len(text) - 1)
    else:
        tw = f.getlength(text)
    w, h = int(tw + 2 * pad), int(asc + desc + 2 * pad)
    m = Image.new('L', (w, h), 0)
    d = ImageDraw.Draw(m)
    if track:
        x = pad
        for c, cw in zip(text, widths):
            d.text((x, pad), c, font=f, fill=255)
            x += cw + track * size
    else:
        d.text((pad, pad), text, font=f, fill=255)
    if grad:
        x0, x1 = grad
        cols = grad_colors(256)
        xs = np.clip((np.arange(w) - pad + x0) / max(1, x1), 0, 1)
        row = cols[(xs * 255).astype(int)]
        rgb = np.broadcast_to(row[None, :, :], (h, w, 3)).astype(np.uint8)
        img = Image.fromarray(np.ascontiguousarray(rgb), 'RGB').convert('RGBA')
        img.putalpha(m)
    else:
        img = Image.new('RGBA', (w, h), color + (0,))
        img.putalpha(m)
    return img, pad, asc


def fade(img, a):
    if a >= 0.999:
        return img
    if a <= 0.001:
        return None
    out = img.copy()
    lut = [int(v * a) for v in range(256)]
    out.putalpha(out.getchannel('A').point(lut))
    return out


def comp(canvas, img, x, y):
    """Alpha-composite img at (x, y), clipping at the canvas edges."""
    if img is None:
        return
    x, y = int(round(x)), int(round(y))
    w, h = img.size
    cx0, cy0 = max(0, x), max(0, y)
    cx1, cy1 = min(canvas.width, x + w), min(canvas.height, y + h)
    if cx1 <= cx0 or cy1 <= cy0:
        return
    src = img if (cx0 - x, cy0 - y, cx1 - x, cy1 - y) == (0, 0, w, h) else img.crop((cx0 - x, cy0 - y, cx1 - x, cy1 - y))
    canvas.alpha_composite(src, (cx0, cy0))


def scaled(img, s):
    if abs(s - 1) < 0.002:
        return img
    w, h = max(1, int(img.width * s)), max(1, int(img.height * s))
    return img.resize((w, h), Image.BICUBIC)


def put_center(canvas, img, cx, cy, s=1.0, a=1.0):
    im = fade(scaled(img, s), a)
    if im is None:
        return
    comp(canvas, im, cx - im.width / 2, cy - im.height / 2)


def text_line(canvas, text, cx, baseline, size, weight=800, color=WHITE, a=1.0, s=1.0,
              grad=False, dy=0, opsz=32, track=0.0, mono=False, anchor='c'):
    f = font(size, weight, opsz, mono)
    tw = f.getlength(text) if not track else sum(f.getlength(c) for c in text) + track * size * (len(text) - 1)
    x_left = cx - tw / 2 if anchor == 'c' else cx
    g = (int(x_left), int(tw)) if grad else None
    img, pad, asc = text_sprite(text, size, weight, color, g, opsz, mono, track)
    if s != 1.0:
        im = scaled(img, s)
        ox = x_left - pad * s - (tw * (s - 1) / 2 if anchor == 'c' else 0)
        oy = baseline + dy - (pad + asc) * s + (asc * (s - 1)) * 0.35
        comp(canvas, fade(im, a), ox, oy)
    else:
        comp(canvas, fade(img, a), x_left - pad, baseline + dy - pad - asc)
    return tw


def layout(text, size, weight, maxw):
    """Word-wrap `text` (words in *stars* are gradient). Returns lines of (word, grad, x_offset), widths."""
    f = font(size, weight)
    space = f.getlength(' ')
    words = text.split(' ')
    lines, cur, curw = [], [], 0
    for wd in words:
        if wd == '|':
            if cur:
                lines.append((cur, curw))
            cur, curw = [], 0
            continue
        g = wd.startswith('*')
        clean = wd.strip('*')
        ww = f.getlength(clean)
        if cur and curw + space + ww > maxw:
            lines.append((cur, curw))
            cur, curw = [], 0
        cur.append((clean, g, curw + (space if cur else 0), ww))
        curw += (space if len(cur) > 1 else 0) + ww
    if cur:
        lines.append((cur, curw))
    return lines


def headline(canvas, t, t0, text, top, size=112, weight=850, maxw=960, lh=1.08, stagger=0.07,
             color=WHITE, cx=W / 2):
    """Kinetic headline: words rise in one after another. Returns bottom y."""
    lines = layout(text, size, weight, maxw)
    f = font(size, weight)
    asc, _ = f.getmetrics()
    y = top + asc
    k = 0
    gx0 = cx - max(w for _, w in lines) / 2
    gw = max(w for _, w in lines)
    for words, lw in lines:
        x0 = cx - lw / 2
        for word, g, xo, ww in words:
            u = e_expo(prog(t, t0 + k * stagger, 0.55))
            if u > 0:
                gspan = (int(x0 + xo - gx0), int(gw)) if g else None
                img, pad, a_ = text_sprite(word, size, weight, color, gspan)
                comp(canvas, fade(img, u), x0 + xo - pad, y + (1 - u) * 70 - pad - asc)
            k += 1
        y += size * lh
    return y - size * lh + size * 0.3


def kicker(canvas, t, t0, text, y, color=CYAN):
    u = e_out3(prog(t, t0, 0.45))
    if u <= 0:
        return
    f = font(30, 700, 14)
    tw = sum(f.getlength(c) for c in text) + 0.18 * 30 * (len(text) - 1)
    # small gradient bar before the label
    bar_w = 46
    total = bar_w + 18 + tw
    x = W / 2 - total / 2
    d = ImageDraw.Draw(canvas)
    d.rounded_rectangle((x, y - 13, x + bar_w * u, y - 7), 3, fill=color + (int(255 * u),))
    text_line(canvas, text, x + bar_w + 18, y, 30, 700, color, a=u, opsz=14, track=0.18, anchor='l', dy=(1 - u) * 20)


def subline(canvas, t, t0, text, y, size=42, color=(214, 214, 228), maxw=940):
    u = e_out3(prog(t, t0, 0.5))
    if u <= 0:
        return
    for i, (words, lw) in enumerate(layout(text, size, 500, maxw)):
        line = ' '.join(w for w, *_ in words)
        text_line(canvas, line, W / 2, y + i * size * 1.3, size, 500, color, a=u, opsz=14, dy=(1 - u) * 26)


# ------------------------------------------------------------------ background
_GX, _GY = 108, 192
_gx = np.linspace(0, 1, _GX)[None, :]
_gy = np.linspace(0, 1, _GY)[:, None] * (H / W)


def _vignette():
    y, x = np.mgrid[0:H, 0:W]
    d = np.sqrt(((x - W / 2) / (W * 0.75)) ** 2 + ((y - H * 0.45) / (H * 0.62)) ** 2)
    v = np.clip(1.15 - d * 0.55, 0.35, 1.0)
    return v[..., None].astype(np.float32)


VIG = None
GRAIN = None


def init_globals():
    global VIG, GRAIN
    if VIG is None:
        VIG = _vignette()
        r = np.random.default_rng(3)
        GRAIN = [r.normal(0, 3.0, (H, W, 1)).astype(np.float32) for _ in range(6)]


def aurora(t, intensity=1.0, palette=None):
    pal = palette or [VIOLET, BLUE, CYAN, PINK]
    acc = np.zeros((_GY, _GX, 3), np.float32)
    specs = [
        (0.20, 0.25, 0.30, 0.13, 0.00, 0.45),
        (0.80, 0.55, 0.33, 0.11, 1.70, 0.42),
        (0.30, 1.35, 0.36, 0.09, 3.10, 0.40),
        (0.75, 1.10, 0.28, 0.15, 4.40, 0.38),
    ]
    for (bx, by, r, sp, ph, g), col in zip(specs, pal):
        cx = bx + 0.18 * math.sin(t * sp * 2 + ph)
        cy = by + 0.22 * math.cos(t * sp * 1.6 + ph * 1.3)
        d2 = (_gx - cx) ** 2 + (_gy - cy) ** 2
        acc += np.exp(-d2 / (2 * r * r))[..., None] * (np.array(col, np.float32) / 255.0) * g
    acc *= intensity
    base = np.array([5, 5, 9], np.float32) / 255
    img = base + (1 - np.exp(-acc * 1.6)) * 0.85
    small = Image.fromarray((np.clip(img, 0, 1) * 255).astype(np.uint8), 'RGB')
    return small.resize((W, H), Image.BICUBIC)


def finish(img_rgb, t, shake=(0, 0), chroma=0, flash=0.0, zoom=1.0):
    init_globals()
    if zoom != 1.0:
        cw, ch = W / zoom, H / zoom
        img_rgb = img_rgb.crop((int((W - cw) / 2), int((H - ch) / 2), int((W + cw) / 2), int((H + ch) / 2))).resize((W, H), Image.BILINEAR)
    a = np.asarray(img_rgb).astype(np.float32)
    a = a * VIG + GRAIN[int(t * FPS) % len(GRAIN)]
    if chroma:
        a[..., 0] = np.roll(a[..., 0], chroma, axis=1)
        a[..., 2] = np.roll(a[..., 2], -chroma, axis=1)
    if shake != (0, 0):
        a = np.roll(a, (int(shake[1]), int(shake[0])), axis=(0, 1))
    if flash > 0:
        a = a + (255 - a) * flash
    return np.clip(a, 0, 255).astype(np.uint8)


# ------------------------------------------------------------------ assets: screens & phones
@lru_cache(maxsize=None)
def screen(name):
    return Image.open(os.path.join(SCR, name + '.png')).convert('RGB')


def phone_dims(sw):
    b = max(8, int(sw * 0.028))
    sh = int(round(sw * SH_FULL / SW_FULL))
    return b, sh, sw + 2 * b, sh + 2 * b, int(sw * 0.12)


@lru_cache(maxsize=None)
def bezel(sw):
    b, sh, DW, DH, R = phone_dims(sw)
    ss = 3
    big = Image.new('RGBA', (DW * ss, DH * ss), (0, 0, 0, 0))
    d = ImageDraw.Draw(big)
    d.rounded_rectangle((0, 0, DW * ss - 1, DH * ss - 1), (R + b) * ss, fill=(20, 20, 26, 255))
    d.rounded_rectangle((2 * ss, 2 * ss, DW * ss - 1 - 2 * ss, DH * ss - 1 - 2 * ss), (R + b - 2) * ss,
                        outline=(92, 92, 108, 255), width=2 * ss)
    d.rounded_rectangle((b * ss - ss, b * ss - ss, (DW - b) * ss + ss, (DH - b) * ss + ss), (R + 1) * ss, fill=(0, 0, 0, 255))
    big = big.resize((DW, DH), Image.LANCZOS)
    mask = Image.new('L', (sw * ss, sh * ss), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, sw * ss - 1, sh * ss - 1), R * ss, fill=255)
    mask = mask.resize((sw, sh), Image.LANCZOS)
    return big, mask


@lru_cache(maxsize=64)
def screen_scaled(name, sw):
    b, sh, *_ = phone_dims(sw)
    return screen(name).resize((sw, sh), Image.LANCZOS)


def build_device(scr_img, sw):
    b, sh, DW, DH, R = phone_dims(sw)
    base, mask = bezel(sw)
    dev = base.copy()
    dev.paste(scr_img, (b, b), mask)
    d = ImageDraw.Draw(dev)
    cr = sw * 0.021
    cx, cy = DW / 2, b + sh * 0.0165
    d.ellipse((cx - cr, cy - cr, cx + cr, cy + cr), fill=(8, 8, 10, 255))
    return dev


@lru_cache(maxsize=32)
def shadow(sw, glow=None):
    b, sh, DW, DH, R = phone_dims(sw)
    pad = 140
    s = Image.new('RGBA', (DW + 2 * pad, DH + 2 * pad), (0, 0, 0, 0))
    d = ImageDraw.Draw(s)
    if glow:
        d.rounded_rectangle((pad - 10, pad - 10, pad + DW + 10, pad + DH + 10), R + b, fill=glow + (120,))
        s = s.filter(ImageFilter.GaussianBlur(60))
    else:
        d.rounded_rectangle((pad + 10, pad + 50, pad + DW - 10, pad + DH + 30), R + b, fill=(0, 0, 0, 190))
        s = s.filter(ImageFilter.GaussianBlur(38))
    return s, pad


@lru_cache(maxsize=48)
def phone_sprite(name, sw, glow=VIOLET):
    dev = build_device(screen_scaled(name, sw), sw)
    g, pad = shadow(sw, glow)
    sh_, _ = shadow(sw, None)
    out = Image.new('RGBA', g.size, (0, 0, 0, 0))
    out.alpha_composite(g)
    out.alpha_composite(sh_)
    out.alpha_composite(dev, (pad, pad))
    return out, pad


def draw_phone(canvas, name, cx, top, sw, s=1.0, a=1.0, glow=VIOLET, dev=None):
    """Place a phone by its device-top edge and centre x. Returns screen origin & scale for overlays."""
    if dev is None:
        spr, pad = phone_sprite(name, sw, glow)
    else:
        g, pad = shadow(sw, glow)
        sh_, _ = shadow(sw, None)
        spr = Image.new('RGBA', g.size, (0, 0, 0, 0))
        spr.alpha_composite(g)
        spr.alpha_composite(sh_)
        spr.alpha_composite(dev, (pad, pad))
    b, sh, DW, DH, R = phone_dims(sw)
    im = fade(scaled(spr, s), a)
    ox = cx - (DW / 2 + pad) * s
    oy = top - pad * s
    comp(canvas, im, ox, oy)
    k = sw * s / SW_FULL
    return (cx - DW / 2 * s + b * s, top + b * s, k)   # screen x0, y0, px-per-screenshot-px


# ------------------------------------------------------------------ callouts
@lru_cache(maxsize=64)
def callout_sprite(name, box, width, ring=True):
    crop = screen(name).crop(box)
    s = width / crop.width
    crop = crop.resize((int(width), int(crop.height * s)), Image.LANCZOS)
    w, h = crop.size
    R = 34
    pad = 70
    out = Image.new('RGBA', (w + 2 * pad, h + 2 * pad), (0, 0, 0, 0))
    sh = Image.new('RGBA', out.size, (0, 0, 0, 0))
    ImageDraw.Draw(sh).rounded_rectangle((pad, pad + 24, pad + w, pad + h + 24), R, fill=(0, 0, 0, 170))
    out.alpha_composite(sh.filter(ImageFilter.GaussianBlur(28)))
    if ring:
        gl = Image.new('RGBA', out.size, (0, 0, 0, 0))
        ImageDraw.Draw(gl).rounded_rectangle((pad - 6, pad - 6, pad + w + 6, pad + h + 6), R + 6, fill=VIOLET + (110,))
        out.alpha_composite(gl.filter(ImageFilter.GaussianBlur(22)))
        # gradient ring
        ringimg = Image.new('RGBA', out.size, (0, 0, 0, 0))
        cols = grad_colors(out.width)
        rgb = np.broadcast_to(cols[None, :, :], (out.height, out.width, 3)).astype(np.uint8)
        gimg = Image.fromarray(np.ascontiguousarray(rgb), 'RGB').convert('RGBA')
        m = Image.new('L', out.size, 0)
        ImageDraw.Draw(m).rounded_rectangle((pad - 4, pad - 4, pad + w + 4, pad + h + 4), R + 4, fill=255)
        gimg.putalpha(m)
        out.alpha_composite(gimg)
    mask = Image.new('L', (w, h), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, w - 1, h - 1), R, fill=255)
    card = crop.convert('RGBA')
    card.putalpha(mask)
    out.alpha_composite(card, (pad, pad))
    return out


def callout(canvas, t, t0, name, box, cy, width=940, cx=W / 2, t_out=None):
    u = prog(t, t0, 0.42)
    if u <= 0:
        return
    s = lerp(0.82, 1.0, e_back(u, 2.0))
    a = min(1.0, u * 4.5)
    if t_out is not None and t > t_out:
        v = e_out3(prog(t, t_out, 0.2))
        a *= 1 - v
        s *= 1 - 0.1 * v
    float_y = math.sin((t - t0) * 2.2) * 5
    put_center(canvas, callout_sprite(name, box, width), cx, cy + float_y + (1 - e_out3(u)) * 60, s, a)


def highlight(canvas, t, t0, origin, box, color=CYAN, t_out=None):
    """Glowing rounded outline around a screenshot region, drawn on top of a placed phone."""
    u = prog(t, t0, 0.3)
    if u <= 0:
        return
    a = u
    if t_out is not None and t > t_out:
        a *= 1 - prog(t, t_out, 0.2)
    if a <= 0:
        return
    x0, y0, k = origin
    bx0, by0, bx1, by1 = [v * k for v in box]
    pad = 40
    w, h = int(bx1 - bx0 + 2 * pad), int(by1 - by0 + 2 * pad)
    lay = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    ImageDraw.Draw(lay).rounded_rectangle((pad - 6, pad - 6, w - pad + 6, h - pad + 6), 22, outline=color + (255,), width=5)
    glow = lay.filter(ImageFilter.GaussianBlur(10))
    out = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    out.alpha_composite(glow)
    out.alpha_composite(glow)
    out.alpha_composite(lay)
    pulse = 1 + 0.03 * math.sin((t - t0) * 9)
    put_center(canvas, out, x0 + (bx0 + bx1) / 2, y0 + (by0 + by1) / 2, pulse, a)


# ------------------------------------------------------------------ small graphics
@lru_cache(maxsize=8)
def app_icon(size):
    ss = 4
    S = size * ss
    im = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.rounded_rectangle((0, 0, S - 1, S - 1), int(S * 0.26), fill=(255, 255, 255, 255))
    # the launcher's chat bubble (viewport 108; visible 18..90)
    k = S / 72.0
    o = -18 * k
    X = lambda v: o + v * k
    d.rounded_rectangle((X(24 + 4), X(32 + 4), X(84 - 4), X(74 - 2)), int(9 * k), fill=INK + (255,))
    d.polygon([(X(50), X(72)), (X(38), X(82)), (X(38), X(72))], fill=INK + (255,))
    # three dots: "typing"
    for i in range(3):
        cx = X(44 + i * 10)
        cy = X(53)
        r = 2.8 * k
        d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=(255, 255, 255, 255))
    return im.resize((size, size), Image.LANCZOS)


@lru_cache(maxsize=8)
def icon_with_glow(size):
    pad = int(size * 0.8)
    out = Image.new('RGBA', (size + 2 * pad, size + 2 * pad), (0, 0, 0, 0))
    g = Image.new('RGBA', out.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(g)
    d.rounded_rectangle((pad - 10, pad - 10, pad + size + 10, pad + size + 10), int(size * 0.3), fill=VIOLET + (230,))
    d.ellipse((pad + size * 0.4, pad - 30, pad + size + 40, pad + size * 0.7), fill=CYAN + (170,))
    d.ellipse((pad - 40, pad + size * 0.3, pad + size * 0.6, pad + size + 40), fill=PINK + (150,))
    out.alpha_composite(g.filter(ImageFilter.GaussianBlur(size * 0.28)))
    out.alpha_composite(app_icon(size), (pad, pad))
    return out


def ring_pulse(canvas, cx, cy, t, t0, r0, r1, color=CYAN, dur=0.9, width=4):
    u = prog(t, t0, dur)
    if u <= 0 or u >= 1:
        return
    r = lerp(r0, r1, e_out3(u))
    a = int(200 * (1 - u))
    d = ImageDraw.Draw(canvas)
    d.ellipse((cx - r, cy - r, cx + r, cy + r), outline=color + (a,), width=width)


def glass(canvas, box, r=28, a=1.0, fill=(255, 255, 255, 20), outline=(255, 255, 255, 46)):
    x0, y0, x1, y1 = [int(v) for v in box]
    lay = Image.new('RGBA', (x1 - x0 + 2, y1 - y0 + 2), (0, 0, 0, 0))
    d = ImageDraw.Draw(lay)
    d.rounded_rectangle((0, 0, x1 - x0, y1 - y0), r, fill=fill, outline=outline, width=2)
    comp(canvas, fade(lay, a), x0, y0)


@lru_cache(maxsize=64)
def pill_sprite(label, color, size=36, solid=False, width=None):
    f = font(size, 650, 14)
    tw = f.getlength(label)
    h = int(size * 2.25)
    dot = int(size * 0.5)
    w = int(width or (tw + size * 1.4 + dot + size * 0.55))
    ss = 2
    im = Image.new('RGBA', (w * ss, h * ss), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    if solid:
        d.rounded_rectangle((0, 0, w * ss - 1, h * ss - 1), h * ss // 2, fill=(22, 22, 34, 248), outline=color + (220,), width=3 * ss)
    else:
        d.rounded_rectangle((0, 0, w * ss - 1, h * ss - 1), h * ss // 2, fill=(255, 255, 255, 22), outline=(255, 255, 255, 60), width=2 * ss)
    cx, cy = int(size * 0.7 + dot / 2) * ss, h * ss // 2
    d.ellipse((cx - dot * ss / 2, cy - dot * ss / 2, cx + dot * ss / 2, cy + dot * ss / 2), fill=color + (255,))
    im = im.resize((w, h), Image.LANCZOS)
    txt, pad, asc = text_sprite(label, size, 650, WHITE, None, 14)
    comp(im, txt, size * 0.7 + dot + size * 0.45 - pad, h / 2 - pad - asc + size * 0.36)
    # soft dot glow
    return im


def check_icon(canvas, cx, cy, s, a, color=CYAN):
    if a <= 0:
        return
    size = int(64 * s) + 2
    lay = Image.new('RGBA', (size * 2, size * 2), (0, 0, 0, 0))
    d = ImageDraw.Draw(lay)
    d.ellipse((size * 0.5, size * 0.5, size * 1.5, size * 1.5), fill=color + (60,), outline=color + (255,), width=max(2, int(4 * s)))
    pts = [(size * 0.78, size * 1.02), (size * 0.95, size * 1.2), (size * 1.26, size * 0.84)]
    d.line(pts, fill=WHITE + (255,), width=max(2, int(7 * s)), joint='curve')
    comp(canvas, fade(lay, a), cx - size, cy - size)


# ------------------------------------------------------------------ scenes

def header(c, t, t0, kick, kcol, head, sub=None, size=108, sub_size=40):
    """Kicker + kinetic headline + subline. Returns the y where content can start."""
    kicker(c, t, t0 - 0.05, kick, 230, kcol)
    hb = headline(c, t, t0, head, 268, size=size)
    y = hb + 8
    if sub:
        subline(c, t, t0 + 0.3, sub, hb + 62, sub_size)
        n = len(layout(sub, sub_size, 500, 940))
        y = hb + 62 + (n - 1) * sub_size * 1.3 + 20
    return y

def phone_rise(t, t0, dist=900, dur=0.9):
    """Vertical offset for a phone entering from below with a spring."""
    return (1 - spring(t - t0, 0.8, 11.0)) * dist


def s_hook(t):
    c = aurora(t, 0.25 + 0.45 * prog(t, 0, 4)).convert('RGBA')
    lines = [('What if', 0.0, False), ('your AI', 1.0, False), ('never left', 2.0, False), ('your *phone?*', 3.0, True)]
    y0 = 700
    for i, (txt, t0, g) in enumerate(lines):
        if t < t0:
            continue
        u = e_expo(prog(t, t0, 0.32))
        s = lerp(1.9, 1.0, u)
        a = min(1.0, (t - t0) / 0.06)
        # previous lines dim a little as new ones land
        newer = sum(1 for _, tt, _ in lines if t0 < tt <= t)
        a *= 1 - 0.22 * min(1, newer) * prog(t, t0 + 1.0, 0.3)
        size = 150
        words = txt.split(' ')
        f = font(size, 900)
        clean = [w.strip('*') for w in words]
        total = sum(f.getlength(w) for w in clean) + f.getlength(' ') * (len(words) - 1)
        x = W / 2 - total / 2
        base = y0 + i * 175
        for w_raw, w in zip(words, clean):
            ww = f.getlength(w)
            gspan = (int(x - (W / 2 - total / 2)), int(total)) if w_raw.startswith('*') else None
            img, pad, asc = text_sprite(w, size, 900, WHITE, gspan)
            im = scaled(img, s)
            cxw = x + ww / 2
            comp(c, fade(im, a), W / 2 + (cxw - W / 2) * s - im.width / 2, base - asc * 0.6 - im.height / 2 + (1 - u) * 0)
            x += ww + f.getlength(' ')
    shake, chroma = (0, 0), 0
    for t0 in (0.0, 1.0, 2.0, 3.0):
        if 0 <= t - t0 < 0.25:
            k = 1 - (t - t0) / 0.25
            shake = (int(math.sin(t * 90) * 14 * k), int(math.cos(t * 70) * 10 * k))
            chroma = int(8 * k)
    # tiny brand tag
    u = prog(t, 0.2, 0.6)
    text_line(c, 'LOCAL ASSISTANT', W / 2, 1650, 28, 700, (180, 180, 200), a=u * 0.8, opsz=14, track=0.3)
    return c.convert('RGB'), dict(shake=shake, chroma=chroma)


def s_title(t):
    T = t - 4.0
    c = aurora(t, 0.9 + 0.25 * beat_pulse(t)).convert('RGBA')
    s = spring(T, 0.38, 12) if T > 0 else 0
    icy = 400
    for k in range(8):
        ring_pulse(c, W / 2, icy, t, 4.0 + k * BEAT, 120, 300, CYAN if k % 2 == 0 else PINK, 0.9, 4)
    put_center(c, icon_with_glow(220), W / 2, icy, max(0.001, s), min(1, T * 5))
    hb = headline(c, t, 4.25, 'Local *Assistant*', 580, size=122, weight=900, stagger=0.12, maxw=1000)
    subline(c, t, 4.6, 'A private AI that lives on your phone', hb + 66, 44)
    top = hb + 150 + phone_rise(t, 4.45, 1100) - 40 * prog(t, 5.5, 2.5)
    draw_phone(c, 's_alarm_name', W / 2, top, 600, glow=BLUE)
    flash = max(0.0, 0.85 * (1 - T / 0.35)) if T < 0.35 else 0.0
    return c.convert('RGB'), dict(flash=flash)


def stat_card(c, t, t0, x, y, w, h, big, label, count_to=None, suffix='', prefix='', count_from=0):
    u = e_back(prog(t, t0, 0.45), 1.6)
    if u <= 0:
        return
    a = min(1, prog(t, t0, 0.2) * 1.5)
    yy = y + (1 - u) * 80
    glass(c, (x, yy, x + w, yy + h), 30, a)
    if count_to is not None:
        v = lerp(count_from, count_to, e_out3(prog(t, t0 + 0.1, 1.1)))
        big = f'{prefix}{int(round(v))}{suffix}'
    text_line(c, big, x + w / 2, yy + h * 0.55, 84, 850, WHITE, a=a, grad=True)
    text_line(c, label, x + w / 2, yy + h * 0.83, 30, 600, (190, 190, 210), a=a, opsz=14)


def s_gemma(t):
    c = aurora(t, 0.8 + 0.25 * beat_pulse(t), [BLUE, VIOLET, CYAN, BLUE]).convert('RGBA')
    kicker(c, t, 7.98, 'THE BRAIN', 250)
    u = e_expo(prog(t, 8.0, 0.5))
    text_line(c, 'Powered by', W / 2, 360, 52, 600, (210, 210, 230), a=u, dy=(1 - u) * 40, opsz=14)
    u2 = e_expo(prog(t, 8.08, 0.6))
    text_line(c, 'Gemma 4 E4B', W / 2, 515, 140, 900, WHITE, a=u2, s=lerp(1.25, 1.0, u2), grad=True)
    subline(c, t, 8.5, 'Google’s open multimodal model, fully on-device', 620, 40)
    cw, ch, gap = 300, 230, 30
    x0 = W / 2 - (3 * cw + 2 * gap) / 2
    stat_card(c, t, 8.8, x0, 750, cw, ch, '', 'tokens / sec', count_to=50, prefix='~')
    stat_card(c, t, 9.0, x0 + cw + gap, 750, cw, ch, '0', 'servers')
    stat_card(c, t, 9.2, x0 + 2 * (cw + gap), 750, cw, ch, '', 'context', count_to=16, suffix='K', count_from=4)
    # modalities
    mods = [('Text', CYAN), ('Images', VIOLET), ('Voice', PINK), ('Tools', AMBER)]
    sprites = [pill_sprite(m, col, 38) for m, col in mods]
    total = sum(s.width for s in sprites) + 22 * (len(sprites) - 1)
    x = W / 2 - total / 2
    for i, sp in enumerate(sprites):
        uu = e_back(prog(t, 9.6 + i * 0.125, 0.4), 2.2)
        if uu > 0:
            put_center(c, sp, x + sp.width / 2, 1100, max(0.01, uu), min(1, uu * 2))
        x += sp.width + 22
    # settings callout: the real model row
    callout(c, t, 10.2, 's_settings', (24, 385, 1250, 540), 1390, 960)
    uu = prog(t, 10.6, 0.5)
    text_line(c, 'LiteRT-LM  ·  GPU + MTP  ·  4K–16K window', W / 2, 1570, 34, 600, (200, 200, 220), a=uu, opsz=14, dy=(1 - e_out3(uu)) * 20)
    return c.convert('RGB'), {}


def s_see(t):
    c = aurora(t, 0.85 + 0.2 * beat_pulse(t), [VIOLET, PINK, BLUE, CYAN]).convert('RGBA')
    y = header(c, t, 12.0, '01 · IT SEES', PINK, 'Show it a *photo.*', 'It reads the picture and saves it to memory.', 112)
    top = y + 70 + phone_rise(t, 12.03, 1200) - 30 * prog(t, 13, 3)
    org = draw_phone(c, 's_remember', W / 2, top, 600, glow=PINK)
    x0, y0, k = org
    bx0, by0, bx1, by1 = 700, 1020, 1220, 1775
    u = prog(t, 12.7, 1.1)
    if 0 < u < 1:
        yy = y0 + (by0 + (by1 - by0) * e_inout3(u)) * k
        lay = Image.new('RGBA', (int((bx1 - bx0) * k) + 40, 70), (0, 0, 0, 0))
        d = ImageDraw.Draw(lay)
        for i in range(30):
            d.line((20, 35 - i, lay.width - 20, 35 - i), fill=CYAN + (int(120 * (1 - i / 30) ** 2),), width=1)
        d.line((20, 35, lay.width - 20, 35), fill=WHITE + (255,), width=3)
        comp(c, lay.filter(ImageFilter.GaussianBlur(1)), x0 + bx0 * k - 20, yy - 35)
    highlight(c, t, 12.7, org, (bx0, by0, bx1, by1), CYAN, t_out=13.7)
    highlight(c, t, 13.8, org, (54, 2238, 1220, 2380), PINK)
    callout(c, t, 14.0, 's_remember', (40, 2222, 1232, 2388), 1560, 980)
    return c.convert('RGB'), {}

def s_recall_photo(t):
    c = aurora(t, 0.85 + 0.2 * beat_pulse(t), [PINK, VIOLET, CYAN, BLUE]).convert('RGBA')
    y = header(c, t, 16.0, '02 · IT REMEMBERS', VIOLET, 'Ask about it. | *Days later.*', 'Saved photos come back the moment you ask.', 104)
    ptop = y + 70
    swap = 18.0
    if t < swap + 0.25:
        dx = -1200 * e_inout3(prog(t, swap, 0.35))
        top = ptop + phone_rise(t, 16.03, 1200)
        org = draw_phone(c, 's_voice_alarm', W / 2 + dx, top, 590, glow=VIOLET)
        highlight(c, t, 16.7, org, (40, 420, 1240, 905), CYAN, t_out=17.8)
        callout(c, t, 16.9, 's_voice_alarm', (26, 424, 1246, 928), ptop + 760, 980, t_out=17.8)
    if t >= swap:
        dx = 1200 * (1 - e_out3(prog(t, swap, 0.45)))
        org = draw_phone(c, 's_scores', W / 2 + dx, ptop, 590, glow=VIOLET)
        highlight(c, t, 18.5, org, (40, 690, 1240, 1320), CYAN)
        callout(c, t, 18.7, 's_scores', (26, 420, 1246, 1362), ptop + 790, 900)
    return c.convert('RGB'), {}

def waveform(c, t, cy, n=34, width=900, amp=120, a=1.0):
    bw = width / n
    x0 = W / 2 - width / 2
    cols = grad_colors(n)
    d = ImageDraw.Draw(c)
    for i in range(n):
        ph = i * 0.55
        v = abs(math.sin(t * 7.3 + ph) * 0.6 + math.sin(t * 11.1 + ph * 1.7) * 0.4)
        env = math.exp(-((i - n / 2) / (n / 2.6)) ** 2)
        h = 16 + amp * v * env * (0.6 + 0.4 * beat_pulse(t, 0.2))
        x = x0 + i * bw + bw * 0.2
        col = tuple(int(v) for v in cols[i])
        d.rounded_rectangle((x, cy - h / 2, x + bw * 0.6, cy + h / 2), int(bw * 0.3), fill=col + (int(255 * a),))


def s_voice(t):
    c = aurora(t, 0.85 + 0.2 * beat_pulse(t), [CYAN, BLUE, PINK, VIOLET]).convert('RGBA')
    y = header(c, t, 20.0, '03 · IT LISTENS', CYAN, 'Just *say it.*', 'Voice notes turn into calls, alarms and reminders.', 124)
    u = prog(t, 20.3, 0.4)
    waveform(c, t, y + 90, a=u)
    top = y + 200 + phone_rise(t, 20.03, 1200)
    org = draw_phone(c, 's_call', W / 2, top, 600, glow=CYAN)
    highlight(c, t, 20.9, org, (40, 420, 1240, 930), PINK)
    callout(c, t, 21.2, 's_call', (26, 424, 1246, 936), top + 830, 980)
    uu = e_back(prog(t, 22.2, 0.4), 2)
    if uu > 0:
        sp = pill_sprite('Opens the dialer — you tap call', CYAN, 32, True)
        put_center(c, sp, W / 2, top + 1110, max(0.01, uu), min(1, uu * 2))
    return c.convert('RGB'), {}

@lru_cache(maxsize=4)
def notif_card(width):
    """A heads-up notification in the Android style (screenshot pixels)."""
    h = 330
    ss = 2
    im = Image.new('RGBA', (width * ss, h * ss), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.rounded_rectangle((0, 0, width * ss - 1, h * ss - 1), 56 * ss, fill=(246, 246, 250, 255), outline=(225, 225, 232, 255), width=2 * ss)
    im = im.resize((width, h), Image.LANCZOS)
    ic = app_icon(64)
    im.alpha_composite(ic, (48, 44))
    d = ImageDraw.Draw(im)
    d.text((132, 76), 'Local Assistant · now', font=font(34, 500, 14), fill=(90, 90, 100), anchor='lm')
    d.text((48, 160), 'Read book', font=font(48, 650, 14), fill=INK, anchor='lm')
    d.text((48, 220), 'Reminder', font=font(36, 450, 14), fill=(90, 90, 100), anchor='lm')
    d.text((48, 290), 'Done', font=font(38, 650, 14), fill=(37, 99, 235), anchor='lm')
    d.text((220, 290), 'Snooze 1 h', font=font(38, 650, 14), fill=(37, 99, 235), anchor='lm')
    return im


def s_remind(t):
    c = aurora(t, 0.85 + 0.2 * beat_pulse(t), [AMBER, PINK, VIOLET, BLUE]).convert('RGBA')
    y = header(c, t, 24.0, '04 · IT GETS THINGS DONE', AMBER, 'Reminders & alarms | *that ring.*', 'By text or voice. Edit or undo with a tap.', 100)
    top = y + 70 + phone_rise(t, 24.03, 1200)
    sw = 590
    u = e_out3(prog(t, 25.6, 0.5))
    if u > 0:
        base = screen('s_reminder').copy().convert('RGBA')
        card = notif_card(1180)
        base.alpha_composite(card, (46, int(lerp(-360, 150, u))))
        b, sh, *_ = phone_dims(sw)
        scr = base.convert('RGB').resize((sw, sh), Image.BILINEAR)
        org = draw_phone(c, None, W / 2, top, sw, glow=AMBER, dev=build_device(scr, sw))
    else:
        org = draw_phone(c, 's_reminder', W / 2, top, sw, glow=AMBER)
    highlight(c, t, 24.7, org, (40, 1740, 1240, 2390), AMBER, t_out=25.6)
    callout(c, t, 24.9, 's_reminder', (30, 1735, 1242, 2395), top + 850, 960, t_out=26.3)
    callout(c, t, 26.62, 's_voice_alarm', (30, 950, 1242, 1438), top + 860, 960)
    return c.convert('RGB'), {}

def s_knows(t):
    c = aurora(t, 0.85 + 0.2 * beat_pulse(t), [VIOLET, CYAN, PINK, BLUE]).convert('RGBA')
    y = header(c, t, 28.0, '05 · IT KNOWS YOU', CYAN, 'It never | *forgets you.*', 'Learns facts as you chat. Recalls old conversations.', 110)
    top = y + 70 + phone_rise(t, 28.03, 1200)
    org = draw_phone(c, 's_mem', W / 2, top, 590, glow=CYAN)
    highlight(c, t, 28.6, org, (40, 890, 1240, 1600), CYAN, t_out=29.3)
    callout(c, t, 28.8, 's_fact', (30, 860, 1242, 1335), top + 700, 960, t_out=30.3)
    callout(c, t, 30.62, 's_recall_old', (30, 420, 1242, 905), top + 760, 960)
    return c.convert('RGB'), {}

LAYERS = [
    ('Always in mind', 'Name, age and preferences — sent with every message', 'CORE', CYAN),
    ('Facts & people', 'Learned from chat · deduped · editable · forgettable', 'FACTS', BLUE),
    ('Session summaries', '“Last time you talked…” carried into new chats', 'SUMMARY', VIOLET),
    ('Chat archive', 'Every exchange · keyword + EmbeddingGemma vector search', 'RAG', PINK),
    ('Saved images', 'Photos re-attached when your question needs them', 'VISION', AMBER),
]


@lru_cache(maxsize=16)
def layer_card(i, lit=False):
    title, desc, tag, col = LAYERS[i]
    w, h = 960, 176
    pad = 40
    im = Image.new('RGBA', (w + 2 * pad, h + 2 * pad), (0, 0, 0, 0))
    if lit:
        g = Image.new('RGBA', im.size, (0, 0, 0, 0))
        ImageDraw.Draw(g).rounded_rectangle((pad - 6, pad - 6, pad + w + 6, pad + h + 6), 34, fill=col + (170,))
        im.alpha_composite(g.filter(ImageFilter.GaussianBlur(20)))
    lay = Image.new('RGBA', (w * 2, h * 2), (0, 0, 0, 0))
    d = ImageDraw.Draw(lay)
    fill = (22, 22, 34, 235) if not lit else (34, 30, 52, 250)
    d.rounded_rectangle((0, 0, w * 2 - 1, h * 2 - 1), 60, fill=fill, outline=col + (200 if lit else 90,), width=4)
    # number badge
    d.rounded_rectangle((36, 40, 36 + 192, 40 + 272), 44, fill=col + (255,))
    lay = lay.resize((w, h), Image.LANCZOS)
    im.alpha_composite(lay, (pad, pad))
    d = ImageDraw.Draw(im)
    d.text((pad + 18 + 48, pad + h / 2 + 2), str(i + 1), font=font(76, 900), fill=(10, 10, 16), anchor='mm')
    d.text((pad + 150, pad + 62), title, font=font(50, 800), fill=WHITE, anchor='lm')
    d.text((pad + 150, pad + 124), desc, font=font(27, 500, 14), fill=(196, 196, 214), anchor='lm')
    tf = font(24, 750, 14)
    tw = tf.getlength(tag)
    tx1 = pad + w - 30
    d.rounded_rectangle((tx1 - tw - 36, pad + 40, tx1, pad + 84), 22, outline=col + (255,), width=2)
    d.text((tx1 - tw / 2 - 18, pad + 62), tag, font=tf, fill=col, anchor='mm')
    return im


def s_layers(t):
    breakdown = t >= 36.0
    inten = 0.85 + 0.2 * beat_pulse(t) if not breakdown else 0.55 + 0.35 * prog(t, 36, 4)
    c = aurora(t, inten, [VIOLET, BLUE, CYAN, PINK]).convert('RGBA')
    kicker(c, t, 31.98, 'UNDER THE HOOD', 220, VIOLET)
    headline(c, t, 32.0, '5 layers of *memory*', 262, size=112)
    y0, step = 540, 206
    query_y = None
    if t >= 36.3:
        qu = prog(t, 36.6, 2.2)
        query_y = lerp(430, y0 + 4 * step + 90, e_inout3(qu))
    for i in range(5):
        t0 = 32.55 + i * 0.5
        u = e_expo(prog(t, t0, 0.5))
        if u <= 0:
            continue
        cy = y0 + i * step + 88
        lit = False
        if query_y is not None:
            near = abs(query_y - cy) < 110
            lit = near or (i == 4 and t > 38.7)
        card = layer_card(i, lit)
        put_center(c, card, W / 2 + (1 - u) * 700, cy, 1.0, u)
    # the query drops through the stack
    if query_y is not None:
        a = prog(t, 36.3, 0.3)
        if t > 38.9:
            a *= 1 - prog(t, 38.9, 0.3)
        sp = pill_sprite('“When is the wedding?”', CYAN, 38, True)
        put_center(c, sp, W / 2, query_y, 1.0, a)
    ua = e_back(prog(t, 39.0, 0.4), 2)
    if ua > 0:
        sp = pill_sprite('→ Sunday, 25 Oct · 12:05 PM', AMBER, 40, True)
        put_center(c, sp, W / 2, y0 + 5 * step + 80, max(0.01, ua), min(1, ua * 2))
    uf = prog(t, 34.8, 0.5) * (1 - prog(t, 38.8, 0.3))
    text_line(c, '+ nightly consolidation while you charge', W / 2, y0 + 5 * step + 70, 34, 600, (200, 200, 222), a=uf, opsz=14)
    text_line(c, 'sqlite-vec  ·  EmbeddingGemma 300M  ·  all on-device', W / 2, y0 + 5 * step + 130, 30, 500, (160, 160, 185), a=prog(t, 35.1, 0.5) * (1 - prog(t, 38.8, 0.3)), opsz=14)
    return c.convert('RGB'), dict(flash=max(0.0, 0.0))


@lru_cache(maxsize=1)
def code_parts():
    s = screen('s_code')
    tall = Image.open(os.path.join(SCR, 'code_tall.png')).convert('RGB')
    return s.crop((0, 0, SW_FULL, 370)), tall, s.crop((0, 2428, SW_FULL, SH_FULL))


def code_screen(scroll, sw):
    top, tall, bottom = code_parts()
    b, sh, *_ = phone_dims(sw)
    k = sw / SW_FULL
    th, bh = int(370 * k), int((SH_FULL - 2428) * k)
    mid_h = sh - th - bh
    y = int(scroll)
    crop = tall.crop((0, y, SW_FULL, y + int(mid_h / k) + 1)).resize((sw, mid_h), Image.BILINEAR)
    out = Image.new('RGB', (sw, sh), WHITE)
    out.paste(top.resize((sw, th), Image.BILINEAR), (0, 0))
    out.paste(crop, (0, th))
    out.paste(bottom.resize((sw, bh), Image.BILINEAR), (0, sh - bh))
    return out


def s_code(t):
    c = aurora(t, 0.9 + 0.25 * beat_pulse(t), [BLUE, CYAN, VIOLET, PINK]).convert('RGBA')
    T = t - 40.0
    y = header(c, t, 40.0, '06 · IT CREATES', CYAN, 'Writes code. *Offline.*', 'Web pages, scripts and more — no internet needed.', 112)
    sw = 610
    scroll = 5600 * e_inout3(prog(t, 40.6, 3.2))
    top = y + 70 + phone_rise(t, 40.1, 1200)
    draw_phone(c, None, W / 2, top, sw, glow=CYAN, dev=build_device(code_screen(scroll, sw), sw))
    uu = e_back(prog(t, 41.2, 0.4), 2)
    if uu > 0:
        put_center(c, pill_sprite('</>  HTML · CSS · JS · Python', CYAN, 36, True), W / 2, 1790, max(0.01, uu), min(1, uu * 2))
    flash = max(0.0, 0.8 * (1 - T / 0.3)) if T < 0.3 else 0.0
    return c.convert('RGB'), dict(flash=flash)

TOOLS = [
    ('Alarms', AMBER), ('Timers', AMBER), ('Reminders', AMBER), ('Events', AMBER),
    ('Phone calls', CYAN), ('SMS & WhatsApp', CYAN), ('Open any app', CYAN), ('Directions', CYAN),
    ('Calculator', VIOLET), ('Web search', VIOLET), ('Flashlight', PINK), ('Do Not Disturb', PINK),
    ('Play music', PINK), ('Save facts', BLUE), ('Search memory', BLUE), ('Remember images', BLUE),
]


def s_tools(t):
    c = aurora(t, 0.9 + 0.25 * beat_pulse(t), [AMBER, CYAN, VIOLET, PINK]).convert('RGBA')
    y0 = header(c, t, 44.0, '16 BUILT-IN TOOLS', AMBER, 'It doesn’t just talk. | *It acts.*', None, 104)
    colw, gap, rowh = 468, 24, 108
    y = y0 + 110
    for k, (n, col) in enumerate(TOOLS):
        r, q = divmod(k, 2)
        sp = pill_sprite(n, col, 38, False, colw)
        x = W / 2 + (q - 1) * (colw + gap) + gap / 2 + colw / 2 if q == 0 else W / 2 + gap / 2 + colw / 2
        u = e_back(prog(t, 44.5 + k * 0.125, 0.38), 2.3)
        if u > 0:
            put_center(c, sp, x, y + r * rowh, max(0.01, u), min(1, u * 2))
    y = y + 8 * rowh - 40
    uu = prog(t, 46.8, 0.5)
    text_line(c, 'Calls and messages open ready — you press send.', W / 2, y + 60, 34, 500, (190, 190, 210), a=uu, opsz=14)
    return c.convert('RGB'), {}


def s_web(t):
    c = aurora(t, 0.9 + 0.25 * beat_pulse(t), [VIOLET, BLUE, AMBER, CYAN]).convert('RGBA')
    y = header(c, t, 48.0, '07 · IT SEARCHES THE WEB', AMBER, 'Searches the web. | *Only if you want.*',
               'Optional: add your own free Tavily key.', 104)
    sw = 660
    top = y + 60 + phone_rise(t, 48.03, 1200)
    org = draw_phone(c, 's_websearch', W / 2, top, sw, glow=AMBER)
    x0, y0, k = org
    highlight(c, t, 48.55, org, (40, 1768, 1180, 1925), AMBER, t_out=50.1)
    callout(c, t, 48.75, 's_websearch', (26, 1690, 1210, 1935), y0 + 1830 * k, 980, t_out=50.1)
    callout(c, t, 50.35, 's_tavily', (30, 345, 1242, 722), y0 + 1760 * k, 960)
    uu = e_back(prog(t, 50.9, 0.4), 2)
    if uu > 0:
        sp = pill_sprite('Off until you add a key', AMBER, 34, True)
        put_center(c, sp, W / 2, min(1830, y0 + 1760 * k + 215), max(0.01, uu), min(1, uu * 2))
    return c.convert('RGB'), {}


def shield(c, cx, cy, s, a, t):
    if a <= 0:
        return
    size = int(260 * s)
    ss = 3
    S = size * ss
    im = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    pts = [(S * 0.5, S * 0.04), (S * 0.9, S * 0.2), (S * 0.86, S * 0.58), (S * 0.5, S * 0.96), (S * 0.14, S * 0.58), (S * 0.1, S * 0.2)]
    d.polygon(pts, fill=(255, 255, 255, 255))
    m = im.getchannel('A')
    cols = grad_colors(S, [CYAN, VIOLET, PINK])
    rgb = np.broadcast_to(cols[None, :, :], (S, S, 3)).astype(np.uint8)
    g = Image.fromarray(np.ascontiguousarray(rgb), 'RGB').convert('RGBA')
    g.putalpha(m)
    d = ImageDraw.Draw(g)
    # lock
    d.rounded_rectangle((S * 0.34, S * 0.44, S * 0.66, S * 0.72), S * 0.04, fill=(255, 255, 255, 255))
    d.arc((S * 0.39, S * 0.27, S * 0.61, S * 0.55), 180, 360, fill=(255, 255, 255, 255), width=int(S * 0.045))
    d.ellipse((S * 0.475, S * 0.54, S * 0.525, S * 0.59), fill=(80, 60, 160, 255))
    g = g.resize((size, size), Image.LANCZOS)
    glow = Image.new('RGBA', (size * 2, size * 2), (0, 0, 0, 0))
    ImageDraw.Draw(glow).ellipse((size * 0.45, size * 0.45, size * 1.55, size * 1.55), fill=VIOLET + (160,))
    glow = glow.filter(ImageFilter.GaussianBlur(size * 0.22))
    comp(c, fade(glow, a * (0.8 + 0.2 * beat_pulse(t))), cx - size, cy - size)
    comp(c, fade(g, a), cx - size / 2, cy - size / 2)


def s_private(t):
    c = aurora(t, 0.8 + 0.2 * beat_pulse(t), [VIOLET, CYAN, BLUE, PINK]).convert('RGBA')
    u = spring(t - 51.99, 0.4, 12)
    shield(c, W / 2, 420, max(0.01, u), min(1, (t - 52.0) * 4), t)
    for k in range(4):
        ring_pulse(c, W / 2, 420, t, 52.2 + k * 1.0, 140, 380, CYAN, 1.0, 4)
    headline(c, t, 52.12, 'Your memory never | *leaves your phone.*', 620, size=100)
    items = ['No account, no sign-up', 'Runs offline — no server', 'Pause, export or forget anytime']
    for i, it in enumerate(items):
        t0 = 53.0 + i * 0.5
        uu = e_back(prog(t, t0, 0.4), 1.8)
        if uu <= 0:
            continue
        y = 1000 + i * 118
        glass(c, (120, y - 50, 960, y + 50), 50, min(1, uu * 2))
        check_icon(c, 190, y, 0.85 * max(0.01, uu), min(1, uu * 2))
        text_line(c, it, 250, y + 14, 40, 650, WHITE, a=min(1, uu * 2), opsz=14, anchor='l')
    callout(c, t, 54.5, 's_settings', (24, 1900, 1250, 2580), 1560, 900)
    return c.convert('RGB'), {}


def s_outro(t):
    T = t - 56.0
    c = aurora(t, 1.0 * (1 - 0.6 * prog(t, 58.5, 1.5))).convert('RGBA')
    s = spring(T, 0.38, 11)
    for k in range(3):
        ring_pulse(c, W / 2, 780, t, 56.0 + k * 0.5, 130, 225, CYAN if k % 2 == 0 else PINK, 1.1, 5)
    put_center(c, icon_with_glow(250), W / 2, 780, max(0.01, s), min(1, T * 5))
    headline(c, t, 56.2, 'Local *Assistant*', 1000, size=124, weight=900, stagger=0.12, maxw=1000)
    subline(c, t, 56.6, 'Your AI. Your phone. Your memory.', 1215, 50, WHITE)
    mods = [('Gemma 4 E4B', CYAN), ('On-device', VIOLET), ('Private', PINK)]
    sprites = [pill_sprite(m, col, 34) for m, col in mods]
    total = sum(sp.width for sp in sprites) + 20 * (len(sprites) - 1)
    x = W / 2 - total / 2
    for i, sp in enumerate(sprites):
        uu = e_back(prog(t, 57.0 + i * 0.15, 0.4), 2)
        if uu > 0:
            put_center(c, sp, x + sp.width / 2, 1370, max(0.01, uu), min(1, uu * 2))
        x += sp.width + 20
    flash = max(0.0, 0.9 * (1 - T / 0.4)) if T < 0.4 else 0.0
    out = c.convert('RGB')
    blk = prog(t, 59.2, 0.8)
    if blk > 0:
        out = Image.blend(out, Image.new('RGB', (W, H), (0, 0, 0)), e_inout3(blk))
    return out, dict(flash=flash)


SCENES = [
    (0.0, 4.0, s_hook), (4.0, 8.0, s_title), (8.0, 12.0, s_gemma), (12.0, 16.0, s_see),
    (16.0, 20.0, s_recall_photo), (20.0, 24.0, s_voice), (24.0, 28.0, s_remind),
    (28.0, 32.0, s_knows), (32.0, 40.0, s_layers), (40.0, 44.0, s_code), (44.0, 48.0, s_tools),
    (48.0, 52.0, s_web), (52.0, 56.0, s_private), (56.0, 60.0, s_outro),
]


def render(t):
    for i, (a, b, fn) in enumerate(SCENES):
        if a <= t < b or (i == len(SCENES) - 1 and t >= a):
            img, fx = fn(t)
            zoom = 1.0
            flash = fx.get('flash', 0.0)
            # cut transitions: punch out of the old scene, settle into the new one
            if i < len(SCENES) - 1 and b - t < 0.12:
                zoom = 1 + 0.07 * (1 - (b - t) / 0.12) ** 2
            if i > 0 and t - a < 0.2 and fn not in (s_title, s_code, s_outro):
                v = (t - a) / 0.2
                zoom = 1 + 0.09 * (1 - e_out3(v))
                flash = max(flash, 0.35 * (1 - v))
            return finish(img, t, fx.get('shake', (0, 0)), fx.get('chroma', 0), flash, zoom)
    raise ValueError(t)


def render_frame(i):
    return render(i / FPS).tobytes()


def main():
    args = sys.argv[1:]
    if args and args[0] == '--still':
        os.makedirs(os.path.join(P, 'stills'), exist_ok=True)
        for s in args[1:]:
            t = float(s)
            Image.fromarray(render(t)).save(os.path.join(P, 'stills', f'{t:05.2f}.png'))
            print('still', t)
        return
    if args and args[0] == '--video':
        import multiprocessing as mp
        t0 = float(args[1]) if len(args) > 1 else 0.0
        t1 = float(args[2]) if len(args) > 2 else DUR
        out = args[3] if len(args) > 3 else os.path.join(P, 'video.mp4')
        frames = range(int(t0 * FPS), int(t1 * FPS))
        ff = subprocess.Popen(['ffmpeg', '-y', '-loglevel', 'error', '-f', 'rawvideo', '-pix_fmt', 'rgb24',
                               '-s', f'{W}x{H}', '-r', str(FPS), '-i', '-', '-c:v', 'libx264', '-preset', 'medium',
                               '-crf', '15', '-pix_fmt', 'yuv420p', out], stdin=subprocess.PIPE)
        with mp.Pool(8) as pool:
            for k, buf in enumerate(pool.imap(render_frame, frames, chunksize=4)):
                ff.stdin.write(buf)
                if k % 150 == 0:
                    print('frame', frames[0] + k, flush=True)
        ff.stdin.close()
        ff.wait()
        print('wrote', out)


if __name__ == '__main__':
    main()
