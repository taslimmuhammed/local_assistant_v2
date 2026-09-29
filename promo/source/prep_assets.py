"""Clean the app screenshots for the promo: a tidy status bar, and no private phone numbers."""
import glob, os, sys
from PIL import Image, ImageDraw, ImageFont

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
files = sorted(glob.glob(os.path.join(SRC, '*.jpg')))
for i, f in enumerate(files):
    im = Image.open(f).convert('RGB')
    status_bar(im)
    if names[i] == 's_call':
        mask_number(im, (366, 806, 664, 858), '•••-•••-••••')
        ImageDraw.Draw(im).rectangle((40, 700, 720, 762), fill=(255, 255, 255))   # drop the old speed line
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
