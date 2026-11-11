"""Play Store graphics: the 512 px icon, the 1024 x 500 feature graphic and the phone screenshots.

    python3 docs/play-store/graphics/make.py

Built from the launcher icon's shapes (res/drawable/ic_launcher_*.xml) and the phone
screenshots in docs/screens. Play wants screenshots no more than twice as tall as wide, and the
phone's own are 2.18 times, so each sits on a 9:16 card under a caption. The tour_* ones are
welcome tour pages, cut below the status bar and above the buttons; they carry their own
heading, so they go on the card larger and without one.
"""
import os

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, '..', '..', '..'))
SCREENS = os.path.join(ROOT, 'docs', 'screens')
FONT = os.path.join(ROOT, 'promo', 'source', 'fonts', 'Inter.ttf')

INK = (24, 24, 27)
GREY = (82, 82, 91)
TOP = (226, 232, 240)
BOTTOM = (248, 250, 252)
SS = 4  # drawn this many times larger, then scaled down, for smooth edges


def font(size, weight):
    f = ImageFont.truetype(FONT, size)
    f.set_variation_by_axes([min(32, max(14, size)), weight])
    return f


def gradient(w, h):
    """The glass UI's light, cool wash, top to bottom."""
    col = Image.new('RGB', (1, h))
    for y in range(h):
        u = y / max(1, h - 1)
        col.putpixel((0, y), tuple(round(a + (b - a) * u) for a, b in zip(TOP, BOTTOM)))
    return col.resize((w, h))


def _stops(t, stops):
    """Colours along a gradient: t in 0..1 (an array), stops as (offset, (r, g, b))."""
    out = np.zeros(t.shape + (3,))
    for (o0, c0), (o1, c1) in zip(stops, stops[1:]):
        u = np.clip((t - o0) / (o1 - o0), 0, 1)[..., None]
        inside = ((t >= o0) & (t <= o1))[..., None]
        out = np.where(inside, np.array(c0) + (np.array(c1) - np.array(c0)) * u, out)
    out = np.where((t < stops[0][0])[..., None], np.array(stops[0][1], float), out)
    return np.where((t > stops[-1][0])[..., None], np.array(stops[-1][1], float), out)


def _hex(c):
    return tuple(int(c[i:i + 2], 16) for i in (1, 3, 5))


def orb(size, unit_box=(18, 18, 90, 90)):
    """The launcher icon as the launcher shows it (its middle 72 of 108): the orb in its ring on
    the night background. Same shapes and colours as res/drawable/ic_launcher_*.xml."""
    big = size * SS
    x0, y0, x1, _ = unit_box
    k = big / (x1 - x0)
    ys, xs = np.mgrid[0:big, 0:big]
    ux, uy = (xs + 0.5) / k + x0, (ys + 0.5) / k + y0
    d = np.hypot(ux - 54, uy - 54)

    rgb = _stops(np.clip(d / 60, 0, 1), [(0, _hex('#1E1B2E')), (1, _hex('#09090B'))])
    ring = (np.abs(d - 27) <= 0.8)[..., None]
    rgb = np.where(ring, rgb * 0.55 + np.array(_hex('#8B5CF6')) * 0.45, rgb)
    t = np.clip(np.hypot(ux - 47, uy - 45) / 26, 0, 1)
    sphere = _stops(t, [(0, _hex('#A5F3FC')), (0.35, _hex('#22D3EE')), (0.7, _hex('#8B5CF6')), (1, _hex('#EC4899'))])
    rgb = np.where((d <= 20)[..., None], sphere, rgb)
    im = Image.fromarray(rgb.astype(np.uint8), 'RGB').convert('RGBA')

    # The glint: M44,44 Q47,39 53,38 Q48,41 46,46 Q45,47 44,44 Z, sampled.
    def quad(a, c, b, n=24):
        return [((1 - t) ** 2 * a[0] + 2 * (1 - t) * t * c[0] + t * t * b[0],
                 (1 - t) ** 2 * a[1] + 2 * (1 - t) * t * c[1] + t * t * b[1]) for t in np.linspace(0, 1, n)]
    pts = quad((44, 44), (47, 39), (53, 38)) + quad((53, 38), (48, 41), (46, 46)) + quad((46, 46), (45, 47), (44, 44))
    layer = Image.new('RGBA', im.size, (0, 0, 0, 0))
    ImageDraw.Draw(layer).polygon([((x - x0) * k, (y - y0) * k) for x, y in pts], fill=(255, 255, 255, 217))
    im.alpha_composite(layer)
    return im.convert('RGB').resize((size, size), Image.LANCZOS)


def rounded(im, radius):
    mask = Image.new('L', (im.width * SS, im.height * SS), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, mask.width - 1, mask.height - 1], radius=radius * SS, fill=255)
    out = im.convert('RGBA')
    out.putalpha(mask.resize(im.size, Image.LANCZOS))
    return out


def shadow(canvas, box, radius, blur=28, offset=14, alpha=60):
    layer = Image.new('RGBA', canvas.size, (0, 0, 0, 0))
    x0, y0, x1, y1 = box
    ImageDraw.Draw(layer).rounded_rectangle([x0, y0 + offset, x1, y1 + offset], radius=radius, fill=(15, 23, 42, alpha))
    canvas.alpha_composite(layer.filter(ImageFilter.GaussianBlur(blur)))


def icon():
    orb(512).save(os.path.join(HERE, 'icon-512.png'))


def feature_graphic():
    w, h = 1024, 500
    im = gradient(w, h).convert('RGBA')

    tile = 112
    shadow(im, (72, 96, 72 + tile, 96 + tile), 26, blur=16, offset=8, alpha=45)
    im.alpha_composite(rounded(orb(tile), 26), (72, 96))

    d = ImageDraw.Draw(im)
    d.text((70, 236), 'Local Assistant', font=font(62, 700), fill=INK)
    d.text((72, 322), 'A private AI assistant that', font=font(30, 400), fill=GREY)
    d.text((72, 362), 'runs on your phone.', font=font(30, 400), fill=GREY)
    d.text((72, 424), 'No account  ·  No cloud  ·  No ads', font=font(22, 600), fill=INK)

    # The home screen, its top two thirds, standing in from the right edge.
    shot = Image.open(os.path.join(SCREENS, 'home.jpg')).convert('RGB')
    sw = 300
    shot = shot.resize((sw, round(shot.height * sw / shot.width)), Image.LANCZOS)
    x, y = 640, 46
    shot = shot.crop((0, 0, sw, h - y + 40))
    shadow(im, (x, y, x + sw, y + shot.height), 34)
    im.alpha_composite(rounded(shot, 34), (x, y))
    im.convert('RGB').save(os.path.join(HERE, 'feature-graphic.png'))


SCREENSHOTS = [
    ('home', 'Your own AI assistant,', 'running on your phone'),
    ('assist', 'Hold the power button', 'and just talk'),
    ('tour_memory', None, None),
    ('place_reminder', 'Reminders that ring', 'when you get there'),
    ('tour_photos', None, None),
    ('tour_day', None, None),
    ('web_search', 'Searches the web', 'when you ask it to'),
    ('drawer', 'Chats, notes and photos', 'that stay on the phone'),
]


def screenshots():
    w, h = 1080, 1920
    for i, (name, line1, line2) in enumerate(SCREENSHOTS, 1):
        im = gradient(w, h).convert('RGBA')
        shot = Image.open(os.path.join(SCREENS, name + '.jpg')).convert('RGB')
        if line1:
            d = ImageDraw.Draw(im)
            f = font(64, 700)
            for j, line in enumerate((line1, line2)):
                tw = d.textlength(line, font=f)
                d.text(((w - tw) / 2, 120 + j * 84), line, font=f, fill=INK)
            sh = 1500
            sw = round(shot.width * sh / shot.height)
            x, y = (w - sw) // 2, 352
        else:
            sw = 960
            sh = round(shot.height * sw / shot.width)
            x, y = (w - sw) // 2, (h - sh) // 2
        shot = shot.resize((sw, sh), Image.LANCZOS)
        shadow(im, (x, y, x + sw, y + sh), 48)
        im.alpha_composite(rounded(shot, 48), (x, y))
        im.convert('RGB').save(os.path.join(HERE, f'screenshot-{i}-{name}.png'))


if __name__ == '__main__':
    icon()
    feature_graphic()
    screenshots()
