"""Clean the app screenshots for the promo: a tidy status bar, and no private phone numbers."""
import glob, os, sys
from PIL import Image, ImageDraw, ImageFilter, ImageFont

P = os.path.dirname(os.path.abspath(__file__))
SRC = '/Users/taslimmuhammedmoosa/Desktop/folders/AI/local_assistant_v2/images'
OUT = os.path.join(P, 'assets', 'screens')
os.makedirs(OUT, exist_ok=True)


def font(sz, w=600, opsz=14):
    f = ImageFont.truetype(os.path.join(P, 'fonts', 'Inter.ttf'), sz)
    f.set_variation_by_axes([opsz, w])
    return f


INK = (24, 24, 27)


def status_bar(im, bg=(255, 255, 255)):
    d = ImageDraw.Draw(im)
    d.rectangle((0, 0, im.width, 150), fill=bg)
    d.text((88, 84), '9:41', font=font(46, 600), fill=INK, anchor='lm')
    # signal bars
    x0, base = 1000, 102
    for i in range(4):
        h = 14 + i * 9
        d.rounded_rectangle((x0 + i * 15, base - h, x0 + i * 15 + 9, base), 2, fill=INK)
    # wifi: three arcs and a dot
    cx, cy = 1098, 104
    for r in (40, 27, 14):
        d.arc((cx - r, cy - r, cx + r, cy + r), 225, 315, fill=INK, width=7)
    d.ellipse((cx - 5, cy - 9, cx + 5, cy + 1), fill=INK)
    # battery
    bx, by = 1140, 68
    d.rounded_rectangle((bx, by, bx + 64, by + 32), 8, outline=INK, width=4)
    d.rounded_rectangle((bx + 7, by + 7, bx + 46, by + 25), 3, fill=INK)
    d.rounded_rectangle((bx + 67, by + 10, bx + 72, by + 22), 2, fill=INK)
    return im


def status_bar_dark(im):
    """Status bar over a wallpaper: blur away the icons, redraw in white."""
    band = im.crop((0, 0, im.width, 150)).filter(ImageFilter.GaussianBlur(40))
    im.paste(band, (0, 0))
    d = ImageDraw.Draw(im)
    W_ = (255, 255, 255)
    d.text((88, 84), '9:41', font=font(46, 600), fill=W_, anchor='lm')
    x0, base = 1000, 102
    for i in range(4):
        h = 14 + i * 9
        d.rounded_rectangle((x0 + i * 15, base - h, x0 + i * 15 + 9, base), 2, fill=W_)
    cx, cy = 1098, 104
    for r in (40, 27, 14):
        d.arc((cx - r, cy - r, cx + r, cy + r), 225, 315, fill=W_, width=7)
    d.ellipse((cx - 5, cy - 9, cx + 5, cy + 1), fill=W_)
    bx, by = 1140, 68
    d.rounded_rectangle((bx, by, bx + 64, by + 32), 8, outline=W_, width=4)
    d.rounded_rectangle((bx + 7, by + 7, bx + 46, by + 25), 3, fill=W_)
    d.rounded_rectangle((bx + 67, by + 10, bx + 72, by + 22), 2, fill=W_)
    return im


def blur(im, box, radius=11):
    """Frosted blur: downsample then blur, so the text can't be recovered."""
    x0, y0, x1, y1 = box
    r = im.crop(box)
    w, h = r.size
    r = r.resize((max(1, w // 7), max(1, h // 7)), Image.BILINEAR).resize((w, h), Image.BILINEAR)
    r = r.filter(ImageFilter.GaussianBlur(radius))
    im.paste(r, (x0, y0))
    return im


# Anagha and Lijo's wedding: names, church, place and date are blurred everywhere.
WEDDING_BLUR = {
    's_voice_alarm': [
        (392, 218, 626, 296),     # toolbar: "anagha's"
        (706, 466, 942, 542),     # question bubble: "anagha's"
        (48, 609, 292, 686),      # "Anagha's"
        (1036, 609, 1120, 686),   # "St."
        (48, 695, 1026, 770),     # "Joseph's Church, Pazhunnana, Thrissur"
        (48, 780, 1068, 856),     # "Sunday, 25th October 2026 at 12:05 PM."
    ],
    's_remember': [
        (700, 1010, 1232, 1790),  # the invitation itself
        (860, 1998, 1068, 2068),  # "Anagha"
        (48, 2080, 650, 2152),    # "Wilson and Lijo T Jose's"
        (372, 2262, 956, 2318),   # chip: "Anagha Wilson and Lijo T Jose"
    ],
}


def mask_number(im, box, text):
    """Paint over a phone number with the chip's own background and dots."""
    d = ImageDraw.Draw(im)
    bg = im.getpixel((box[0] - 6, box[3] + 6))
    d.rectangle(box, fill=bg)
    d.text((box[0] + 2, (box[1] + box[3]) // 2), text, font=font(42, 400), fill=INK, anchor='lm')
    return im


names = {
    0: 's_calc', 1: 's_web', 2: 's_call', 3: 's_voice_alarm', 4: 's_remember',
    5: 's_code', 6: 's_scores', 7: 's_mem', 8: 's_images',
}
files = sorted(glob.glob(os.path.join(SRC, 'Screenshot_2026-09-27*.jpg')))
for i, f in enumerate(files):
    im = Image.open(f).convert('RGB')
    status_bar(im)
    if names[i] == 's_call':
        mask_number(im, (366, 806, 664, 858), '•••-•••-••••')
        ImageDraw.Draw(im).rectangle((40, 700, 720, 762), fill=(255, 255, 255))   # drop the old speed line
    for box in WEDDING_BLUR.get(names.get(i, ''), []):
        blur(im, box, 16 if box[3] - box[1] > 300 else 11)
    im.save(os.path.join(OUT, names[i] + '.png'))
    print(names[i], im.size)

# Screens captured from the phone in this session.
for name, src in [('s_empty', 'app1'), ('s_settings', 'set2'), ('s_settings_top', 'set1')]:
    p = os.path.join(P, 'shots', src + '.png')
    if os.path.exists(p):
        im = Image.open(p).convert('RGB')
        status_bar(im)
        im.save(os.path.join(OUT, name + '.png'))
        print(name, im.size)

for extra in sys.argv[1:]:
    name, src = extra.split('=')
    im = Image.open(src).convert('RGB')
    status_bar(im)
    if name == 's_tavily':
        ImageDraw.Draw(im).rectangle((0, 740, 1272, 815), fill=(255, 255, 255))   # the saved key's last characters
    im.save(os.path.join(OUT, name + '.png'))
    print(name, im.size)

# The power-button assistant panel (2026-09-30), over the home screen.
for f in sorted(glob.glob(os.path.join(SRC, 'Screenshot_2026-09-30-06-11*.jpg'))):
    im = Image.open(f).convert('RGB')
    status_bar_dark(im)
    blur(im, (80, 962, 350, 1030), 9)       # the weather widget's location
    im.save(os.path.join(OUT, 's_assist.png'))
    print('s_assist', im.size)
