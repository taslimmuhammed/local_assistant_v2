import sys, numpy as np
from PIL import Image
d, out = sys.argv[1], sys.argv[2]
n = int(sys.argv[3])
T, B = 370, 2428          # content area between the toolbar and the input bar
ims = [np.asarray(Image.open(f'{d}/{k}.png').convert('RGB')).astype(np.int16) for k in range(n)]
content = [im[T:B] for im in ims]
tall = content[0].copy()
offs = [0]
for k in range(1, n):
    prev, cur = content[k-1], content[k]
    h = prev.shape[0]
    best, bs = None, 1e18
    # cur[y] == prev[y+s] for the scroll distance s
    strip = cur[200:600]
    for s in range(100, h - 700):
        diff = np.abs(prev[200+s:600+s] - strip).mean()
        if diff < bs: bs, best = diff, s
    print(k, 'scroll', best, 'err', round(float(bs), 3))
    if bs > 3 or best is None:
        print('stop: no match'); break
    if best < 20: print('stop: end'); break
    offs.append(offs[-1] + best)
    newpart = cur[h - best:]
    tall = np.concatenate([tall, newpart], 0)
print('tall', tall.shape)
Image.fromarray(tall.astype(np.uint8)).save(out)
