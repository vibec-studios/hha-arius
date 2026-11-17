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
    '....kkkkkkk.....',
    '...k4343434k....',
    '...k3GgGgGgk....',
    '..kk3333332kk...',
    '.k43k333322k3k..',
    '.k32k332221k2k..',
    '.k22kk22211k2k..',
    '..k2kk22211kk...',
    '...kkIIIIIIk....',
    '...k2iI44Ii2k...',
    '...k23I44I32k...',
    '..k223i44i322k..',
    '..k2233ii3322k..',
    '..kgGgGgGgGgGk..',
    '..kkkkkkkkkkkk..',
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
    """Solvane: Vanilla-Schwertstruktur in Perlweiß/Eis mit Goldparierstange, Eisjuwel und dunklem Griff."""
    im = H.Img()
    for y, row in enumerate(SWORD):
        for x, ch in enumerate(row):
            if ch != '.':
                im.set(x, y, H.hexc(SWORD_COL[ch]))
    im.set(5, 8, H.hexc('#bfeaff'))   # Eisjuwel in der Parierstange
    im.set(4, 9, H.hexc('#3e8fd0'))
    return im


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
