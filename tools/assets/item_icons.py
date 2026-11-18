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

# Seit 1.4.2: gepanzerte Faust von vorn – vier Fingerplatten mit Goldnieten, Daumen quer davor, Eis-Juwel auf dem
# Handrücken, goldenes Gelenkband, ausgestellte Stulpe mit Goldsaum (Crix: alte Profilfaust „sieht kacke aus“).
GAUNTLET = [
    '................',
    '...kk.kk.kk.kk..',
    '..k44k44k44k34k.',
    '..k43143143132k.',
    '..kG31G31G31G2k.',
    '..k33133133122k.',
    '.kk1111111111kk.',
    '.k444443333321k.',
    '.k43333IIi2221k.',
    '.k3333IiIi2211k.',
    '..k333iIi22211k.',
    '...kk2222211kk..',
    '...kGgGgGgGgk...',
    '..k4333332211kk.',
    '.k433333222111k.',
    '.kgGgGgGgGgGgkk.',
]
LEGEND['w'] = H.hexc('#ffffff')


def gauntlet_icon():
    im = H.Img()
    for y, row in enumerate(GAUNTLET):
        for x, ch in enumerate(row):
            if ch != '.':
                im.set(x, y, LEGEND[ch])
    return im


# Solvane: Struktur des Vanilla-Diamantschwerts (saubere Vanilla-Pixelarbeit), Ton für Ton umgefärbt.
SWORD = [
    '.............aaa',
    '............abbc',
    '...........abdbc',
    '..........abdbc.',
    '.........abdec..',
    '........abdec...',
    '..aa...aedec....',
    '..afa.aedec.....',
    '...agcegec......',
    '...aggfec.......',
    '....afac........',
    '...hicaac.......',
    '..hjk.ccac......',
    'aaik....cc......',
    'afc.............',
    'ccc.............',
]
SWORD_COL = {
    'a': '#141a2b', 'c': '#0b0f1a',                          # Umriss
    'b': '#ffffff', 'd': '#d6e4f3', 'e': '#86b4dd',          # Klinge: Glanz, Licht, Eis-Mittellinie
    'f': '#c99a3e', 'g': '#f1d58c',                          # Parierstange und Knauf: Gold
    'h': '#2a2638', 'i': '#4a4560', 'j': '#6b6688', 'k': '#c99a3e',   # Griffwicklung, Knaufring
}


def solvane_icon():
    """Solvane (seit 1.4.2): diagonale Bastardklinge mit breiter perlweißer Schneide und leuchtender Eis-Hohlkehle,
    Parierstange als goldene Drachenschwingen mit Eisjuwel, dunkle Griffwicklung, Goldknauf mit Eisstein."""
    im = H.Img()
    W = H.hexc('#ffffff'); B = H.hexc('#8fd8ff'); B2 = H.hexc('#d4f2ff'); S = H.hexc('#a9bdd6'); S2 = H.hexc('#cdd9e8')
    for y in range(0, 10):
        c = 15 - y
        if y == 0:
            im.set(15, 0, W)
            continue
        im.set(c - 1, y, W)
        im.set(c, y, B2 if y % 3 == 1 else B)
        if c + 1 < 16:
            im.set(c + 1, y, S if y > 2 else S2)
    gold = [H.hexc('#8a6228'), H.hexc('#c99a3e'), H.hexc('#f1d58c')]
    guard = [((5, 10), -1), ((4, 9), 2), ((3, 8), 2), ((2, 7), 1), ((2, 6), 1), ((1, 5), 0),
             ((6, 11), 2), ((7, 12), 2), ((8, 13), 1), ((9, 13), 1), ((10, 14), 0)]
    for (x, y), k in guard:
        im.set(x, y, H.hexc('#4fb0f0') if k < 0 else gold[k])
    im.set(3, 9, gold[1]); im.set(6, 12, gold[1])
    im.set(6, 10, H.hexc('#d9f4ff'))
    D = H.hexc('#262335'); d = H.hexc('#5a5478')
    for (x, y, c) in ((4, 11, d), (5, 11, D), (3, 12, D), (4, 12, d), (2, 13, d), (3, 13, D)):
        im.set(x, y, c)
    for (x, y, c) in ((1, 14, H.hexc('#8fd8ff')), (0, 14, gold[2]), (1, 15, gold[1]), (0, 15, gold[0]), (2, 14, gold[1]), (1, 13, gold[2])):
        im.set(x, y, c)
    return im.outline()


def scale_icon():
    """Schuppe des weißen Drachen: perlweiße Spitzbogen-Schuppe mit Rippen, eisblauer Schatten unten rechts, Glanz oben links."""
    import math
    im = H.Img()
    ramp = [H.hexc('#4a5d7d'), H.hexc('#7f95b6'), H.hexc('#b4c5dc'), H.hexc('#dfe8f4'), H.hexc('#ffffff')]
    for y in range(16):
        yy = y + 0.5
        if yy < 1.5 or yy > 14.5:
            continue
        half = 6.0 * math.sqrt(max(0.0, (yy - 1.5) / 13.0)) * (1.0 if yy < 11.5 else 1.0 - (yy - 11.5) / 3.6)
        for x in range(16):
            cx = x + 0.5 - 8.0
            if abs(cx) > half:
                continue
            # überlappende Bänder (Bögen nach unten), oben hell, unterer Bandrand dunkel
            v = (yy - 2.0 + 0.18 * cx * cx) % 4.0
            light = 0.66 - cx * 0.04
            if v < 1.0:
                light += 0.22
            elif v >= 3.0:
                light -= 0.38
            i = max(0, min(4, int(light * 5)))
            c = ramp[i]
            if abs(cx) > half - 1.0 and cx > 0:
                c = H.hexc('#7cc8f0')            # eisblauer Rand rechts
            im.set(x, y, c)
    im.set(6, 4, ramp[4]); im.set(6, 5, ramp[4]); im.set(5, 6, ramp[4])
    return im.outline()


def write_all():
    H.png(os.path.join(OUT_ITEM, 'arius_gauntlets.png'), 16, 16, rows_of(gauntlet_icon()))
    H.png(os.path.join(OUT_ITEM, 'solvane.png'), 16, 16, rows_of(solvane_icon()))
    meta = os.path.join(OUT_ITEM, 'solvane.png.mcmeta')
    if os.path.exists(meta):
        os.remove(meta)
    H.png(os.path.join(OUT_ITEM, 'nivaris_scale.png'), 16, 16, rows_of(scale_icon()))


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
    preview([gauntlet_icon(), solvane_icon(), scale_icon()])
    if '--preview' not in sys.argv:
        write_all()
