"""Handgezeichnete 16 × 16-Item-Icons im Vanilla-Stil (scharfe Pixel, dunkler Umriss), wo der 32er-Render aus dem
3D-Modell matschig wirkte. Aktuell: Arius' Kampfhandschuhe.

Aufruf: python tools/assets/item_icons.py  (schreibt textures/item/arius_gauntlets.png, Vorschau tmp/preview/item_icons.png)
spell_art.py nimmt das Icon von hier.
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import hud_icons as H  # noqa: E402

ROOT = H.ROOT
OUT_ITEM = os.path.join(ROOT, 'src', 'main', 'resources', 'assets', 'hha_arius', 'textures', 'item')

# k Umriss, P0..P3 Perlweiß (dunkel → hell), g/G Gold, i/I Eis, w Schwinge (hell), d dunkles Leder
LEGEND = {
    'k': H.hexc('#141826'),
    '1': H.hexc('#5d6f8c'), '2': H.hexc('#98a9c2'), '3': H.hexc('#cfd9e8'), '4': H.hexc('#f3f7fc'),
    'g': H.hexc('#9c7230'), 'G': H.hexc('#e2c37e'),
    'i': H.hexc('#4f95cc'), 'I': H.hexc('#bfeaff'),
    'd': H.hexc('#2a2f40'),
}

# Eine Faust im Profil (Handrücken zum Betrachter), Unterarmschiene nach rechts oben, Schwinge oben, Eisband am Gelenk.
GAUNTLET = [
    '................',
    '.....kkkkkk.....',
    '....k434343k....',
    '....kG4G4G4k....',
    '..kkk333333k....',
    '.k43k333332k....',
    '.k32k333322k....',
    '..k2kk32222k....',
    '...kk22222k..kk.',
    '....kIIIIIIk.kwk',
    '....k234432kk3k.',
    '....k23ii32kk3k.',
    '....k2iIIi2k3k..',
    '...k223ii322Gk..',
    '...kGGGGGGGGk...',
    '...kkkkkkkkkk...',
]
LEGEND['w'] = H.hexc('#ffffff')


def gauntlet_icon():
    im = H.Img()
    for y, row in enumerate(GAUNTLET):
        for x, ch in enumerate(row):
            if ch != '.':
                im.set(x, y, LEGEND[ch])
    return im


def rows_of(im):
    return [[c if c is not None else (0, 0, 0, 0) for c in im.px[y]] for y in range(H.S)]


def preview(icons):
    scale, gap = 12, 6
    w = len(icons) * (16 * scale + gap) + gap
    h = 16 * scale + 2 * gap
    bg = (139, 139, 139, 255)
    canvas = [[bg] * w for _ in range(h)]
    for i, im in enumerate(icons):
        for y in range(16):
            for x in range(16):
                c = im.px[y][x]
                if c is None:
                    continue
                for yy in range(scale):
                    for xx in range(scale):
                        canvas[gap + y * scale + yy][gap + i * (16 * scale + gap) + x * scale + xx] = c
    out = os.path.join(ROOT, 'tmp', 'preview', 'item_icons.png')
    H.png(out, w, h, canvas)
    print(out)


if __name__ == '__main__':
    g = gauntlet_icon()
    preview([g])
    if '--preview' not in sys.argv:
        H.png(os.path.join(OUT_ITEM, 'arius_gauntlets.png'), 16, 16, rows_of(g))
