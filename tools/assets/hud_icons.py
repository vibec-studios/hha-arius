"""Animierte Abklingzeit-Icons der aktiven Fähigkeiten (HUD-Leiste von HHA), im Stil von HHA Blood: 16 × 16, mehrere Bilder
untereinander (Animation per .mcmeta), kräftige dunkle Kontur, 4–5 Töne eines eisblauen Verlaufs, Gold als Akzent und
ein Motiv, das sich bewegt.

Aufruf: python tools/assets/hud_icons.py [--preview]
Schreibt nach src/main/resources/assets/hha_arius/textures/gui/sprites/hud/ (und hud/art/ für Weißer Kern, Frostschritt,
Klarer Schnitt, Weißes Loch); Vorschau tmp/preview/hud_icons.png (8-fach). gear_art.py und spell_art.py überspringen
diese Namen (NAMES) und rufen dieses Skript am Ende auf.
"""
import math
import os
import struct
import sys
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, '..', '..'))
ASSETS = os.path.join(ROOT, 'src', 'main', 'resources', 'assets', 'hha_arius', 'textures', 'gui', 'sprites', 'hud')
PREVIEW = os.path.join(ROOT, 'tmp', 'preview', 'hud_icons.png')

S = 16
FRAMES = 6
FRAMETIME = 3


def hexc(h, a=255):
    h = h.lstrip('#')
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a)


OUT = hexc('#0b1020')
ICE = [hexc('#1d3456'), hexc('#2f5f96'), hexc('#4f95cc'), hexc('#8fd0f2'), hexc('#d6f2ff'), hexc('#ffffff')]
GOLD = [hexc('#7a5524'), hexc('#b8862f'), hexc('#e0bd62'), hexc('#fbe6a6')]
STEEL = [hexc('#3a4458'), hexc('#6f7c94'), hexc('#aeb9cc'), hexc('#e6edf6')]


class Img:
    def __init__(self):
        self.px = [[None] * S for _ in range(S)]

    def set(self, x, y, c):
        if 0 <= x < S and 0 <= y < S and c is not None:
            self.px[y][x] = c

    def get(self, x, y):
        return self.px[y][x] if 0 <= x < S and 0 <= y < S else None

    def outline(self, diagonal=False):
        nb = [(1, 0), (-1, 0), (0, 1), (0, -1)] + ([(1, 1), (-1, -1), (1, -1), (-1, 1)] if diagonal else [])
        src = [row[:] for row in self.px]
        for y in range(S):
            for x in range(S):
                if src[y][x] is None and any(0 <= x + dx < S and 0 <= y + dy < S and src[y + dy][x + dx] is not None
                                             and src[y + dy][x + dx] != OUT for dx, dy in nb):
                    self.px[y][x] = OUT
        return self


def ice(t):
    """t 0..1 → eisblauer Verlauf (dunkel → weiß)."""
    i = max(0, min(len(ICE) - 1, int(t * (len(ICE) - 1) + 0.5)))
    return ICE[i]


def each(fn):
    """Ruft fn(x, y, cx, cy) für jede Pixelmitte; cx/cy relativ zur Mitte (7,5/7,5)."""
    for y in range(S):
        for x in range(S):
            fn(x, y, x - 7.5, y - 7.5)


# ---------------------------------------------------------------------------------------------------------------------
# Motive (f = Bild 0..FRAMES-1, k = f / FRAMES)

def white_core(f):
    """Weißer Kern: pulsierender Kern, ein Druckring läuft nach außen und verblasst, vier Eisdorne."""
    im = Img()
    k = f / FRAMES
    ring = 3.2 + 4.2 * k
    pulse = 2.2 + 0.5 * math.sin(k * 2 * math.pi)

    def px(x, y, cx, cy):
        r = math.hypot(cx, cy)
        if r <= pulse:
            im.set(x, y, ICE[5] if r < pulse - 1.0 else ICE[4])
        elif r <= pulse + 0.9:
            im.set(x, y, ICE[3])
        elif abs(r - ring) < 0.55 and ring < 7.4:
            fade = 1 - k
            im.set(x, y, ice(0.35 + 0.6 * fade) if (cx + cy) < 0.5 else ice(0.2 + 0.5 * fade))
    each(px)
    for d, (dx, dy) in enumerate([(0, -1), (1, 0), (0, 1), (-1, 0)]):
        for s in range(2):
            im.set(int(7.5 + dx * (6 - s) + (0.5 if dx == 0 else 0) - 0.5), int(7.5 + dy * (6 - s) + (0.5 if dy == 0 else 0) - 0.5),
                   ICE[3] if s == 0 else ICE[4])
    im.set(7, 7, ICE[5]); im.set(8, 7, ICE[5])
    return im.outline()


def frost_step(f):
    """Frostschritt: drei Pfeilspitzen (Chevrons), die nacheinander nach rechts aufleuchten, vorn eine Eisspitze."""
    im = Img()
    lit = f % 3
    for i, x0 in enumerate((1, 5, 9)):
        hot = (i == lit)
        warm = (i == (lit + 2) % 3)
        for d in range(5):
            for w in range(2):
                x = x0 + (4 - d) - w + 1
                for y in (7 - d, 8 + d):
                    c = ICE[5] if hot else (ICE[3] if warm else ICE[1])
                    if w == 1:
                        c = ICE[4] if hot else (ICE[2] if warm else ICE[0])
                    im.set(x, y, c)
    # Eisspitze vorn
    for y in range(5, 11):
        span = 3 - abs(y - 7.5) * 0.9
        for x in range(13, 16):
            if x - 13 < span:
                im.set(x, y, ICE[5] if y < 8 else ICE[4])
    im.set(15, 7, GOLD[3]); im.set(15, 8, GOLD[3])
    return im.outline()


def white_hole(f):
    """Weißes Loch: gleißender Kern, zwei Lichtarme drehen sich nach außen, darüber eine schräge goldene Scheibe."""
    im = Img()
    rot = f / FRAMES * 2 * math.pi

    def px(x, y, cx, cy):
        r = math.hypot(cx, cy)
        a = math.atan2(cy, cx)
        if r <= 1.9:
            im.set(x, y, ICE[5])
            return
        if r <= 2.7:
            im.set(x, y, ICE[4])
            return
        # zwei Spiralarme (Abstoßung nach außen)
        for arm in (0, math.pi):
            phase = (a - rot - arm - 0.55 * r) % (2 * math.pi)
            if r < 7.2 and (phase < 0.55 or phase > 2 * math.pi - 0.2):
                im.set(x, y, ice(0.95 - r / 9.0))
        # schräge Scheibe
        ex = cx / 7.0
        ey = (cy * 0.9 + cx * 0.35) / 2.1
        d = math.hypot(ex, ey)
        if 0.82 <= d <= 1.0:
            front = ey > 0
            im.set(x, y, GOLD[3] if front else GOLD[2])
    each(px)
    return im.outline()


def clear_cut(f):
    """Klarer Schnitt: ein Eisblock ist diagonal geteilt, die Hälften gleiten auseinander; ein Lichtfunke läuft die
    Schnittlinie entlang."""
    im = Img()
    k = f / FRAMES
    slide = 0.9 * math.sin(k * math.pi)

    def px(x, y, cx, cy):
        u = (cx + cy) / math.sqrt(2)  # quer zur Schnittlinie
        v = (cx - cy) / math.sqrt(2)  # entlang
        for side in (-1, 1):
            su = u - side * (0.9 + slide)
            sv = v - side * slide * 0.6
            if side * su >= -0.2 and abs(su) <= 4.6 and abs(sv) <= 5.2 and abs(su) + abs(sv) <= 7.2:
                edge = abs(su) > 3.8 or abs(sv) > 4.4 or abs(su) + abs(sv) > 6.4
                shade = 0.35 if side > 0 else 0.6
                im.set(x, y, ICE[1] if edge else ice(shade + (0.15 if sv < 0 else 0)))
    each(px)
    # Schnittlinie mit Glanz
    for i in range(-7, 8):
        x, y = 7 + i, 8 - i
        if 0 <= x < S and 0 <= y < S:
            im.set(x, y, ICE[4])
    t = -6 + int(12 * k)
    for d in (-1, 0, 1):
        im.set(7 + t + d, 8 - t, ICE[5])
        im.set(7 + t, 8 - t + d, ICE[5])
    im.set(7 + t, 8 - t, GOLD[3])
    return im.outline()


def counter(f):
    """Konterhaltung: zwei gekreuzte Klingen, an der Kreuzung blüht ein goldener Funke auf."""
    im = Img()
    k = f / FRAMES
    for y in range(S):
        for x in range(S):
            a, b = x + y - 15, x - y
            if a in (0, -1) and 1 <= x <= 13 and y >= 1:
                im.set(x, y, STEEL[3] if a == -1 else STEEL[2])
            if b in (0, 1) and 2 <= x <= 14 and y <= 13:
                im.set(x, y, STEEL[3] if b == 1 else STEEL[2])
    for x, y, c in [(1, 11, GOLD[1]), (2, 12, GOLD[2]), (3, 13, GOLD[1]), (2, 13, STEEL[0]), (1, 14, STEEL[0]),
                    (14, 11, GOLD[1]), (13, 12, GOLD[2]), (12, 13, GOLD[1]), (13, 13, STEEL[0]), (14, 14, STEEL[0])]:
        im.set(x, y, c)
    size = 1.5 + 3.0 * abs(math.sin(k * math.pi))
    for y in range(S):
        for x in range(S):
            dx, dy = x + 0.5 - 8.0, y + 0.5 - 8.0
            r = abs(dx) + abs(dy)
            ray = min(abs(dx), abs(dy)) < 0.6 and max(abs(dx), abs(dy)) <= size + 1.0
            if r <= 1.6 + 0.4 * size * 0.5 or ray:
                im.set(x, y, GOLD[3] if (r < 1.6 or max(abs(dx), abs(dy)) < size * 0.6) else GOLD[2])
    im.set(7, 7, ICE[5]); im.set(8, 8, ICE[5]); im.set(7, 8, ICE[5]); im.set(8, 7, ICE[5])
    return im.outline()


def wingscale(f):
    """Flügelschuppe: eine große Drachenschuppe (Spitzbogen) mit Rippen, ein Glanz wandert schräg darüber."""
    im = Img()
    k = f / FRAMES
    band = -9 + 18 * k

    def px(x, y, cx, cy):
        # Spitzbogen: breit unten, spitz oben
        yy = (y + 0.5)
        if yy < 1.5 or yy > 14.5:
            return
        half = 6.2 * math.sqrt(max(0.0, (yy - 1.5) / 13.0)) * (1.0 if yy < 11.5 else 1.0 - (yy - 11.5) / 3.6)
        if abs(cx) > half:
            return
        rib = (abs(cx) * 1.3 + (14.5 - yy) * 0.55) % 3.0 < 0.8
        base = 0.55 - (yy - 1.5) / 40.0
        c = ice(base - 0.15) if rib else ice(base)
        if abs((cx + cy) - band) < 1.2:
            c = ICE[5] if abs((cx + cy) - band) < 0.6 else ICE[4]
        im.set(x, y, c)
    each(px)
    for y in range(3, 14):
        if im.get(7, y) is not None:
            im.set(7, y, GOLD[2] if y % 3 else GOLD[3])
    return im.outline()



def dragon_breath(f):
    """Drachenatem: kleiner Drachenkopf links, ein Frostkegel strömt nach rechts, Schneeflocken wandern hinaus."""
    im = Img()
    k = f / FRAMES
    # Kegel
    for x in range(5, 16):
        half = (x - 5) * 0.55
        for y in range(16):
            dy = abs(y + 0.5 - 8.0)
            if dy <= half + 0.5:
                edge = dy > half - 0.8
                im.set(x, y, ICE[2] if edge else ICE[3] if (x + y + f) % 3 else ICE[4])
    # wandernde Flocken
    for i in range(5):
        x = 6 + int(((i * 2.3 + k * 10) % 10))
        y = 8 + int(((i * 37) % 7) - 3) * (x - 5) // 8
        im.set(x, y, ICE[5])
    # Kopf
    head = ['.kk..', 'k44k.', 'k4G4k', 'k443k', '.kkk.']
    for yy, row in enumerate(head):
        for xx, ch in enumerate(row):
            c = {'k': OUT, '4': ICE[4], '3': ICE[3], 'G': GOLD[3]}.get(ch)
            if c:
                im.set(1 + xx, 6 + yy, c)
    im.set(2, 5, ICE[5]); im.set(1, 4, ICE[4])   # Horn
    return im.outline()


def wing_leap(f):
    """Schwingensprung: zwei Lichtschwingen, darunter ein gestrichelter Sprungbogen, am Ende ein wachsender Aufprallring."""
    im = Img()
    k = f / FRAMES
    flap = 1 if f % 2 else 0
    # Schwingen (V-Form), schlagen leicht
    for i in range(6):
        for side in (-1, 1):
            x = 8 + side * (1 + i)
            y = 6 - i // 2 - (flap if i > 2 else 0)
            im.set(x, y, ICE[5] if i < 2 else ICE[4])
            im.set(x, y + 1, ICE[3])
            if i % 2 == 0:
                im.set(x, y + 2, ICE[2])
    im.set(8, 7, GOLD[3]); im.set(8, 8, GOLD[2])
    # Sprungbogen
    for i in range(12):
        x = 2 + i
        y = int(13 - 4 * math.sin(i / 11 * math.pi))
        if (i + f) % 3 != 0:
            im.set(x, y, ICE[3])
    # Aufprallring rechts unten
    rr = 0.8 + 2.2 * k
    for y in range(16):
        for x in range(16):
            d = math.hypot((x + 0.5 - 13.5) * 0.8, (y + 0.5 - 13.5) * 1.6)
            if abs(d - rr) < 0.45:
                im.set(x, y, ICE[4] if k < 0.6 else ICE[3])
    return im.outline()



def tornado(f):
    """Wirbelsturm: Trichter aus Windstreifen (oben breit, unten eng), Striche laufen um, der Fuß pendelt."""
    im = Img()
    for row in range(6):
        y = 2 + row * 2
        half = 6.5 - row * 1.05
        wob = int(round(math.sin((f + row) * 1.0) * (row / 5.0)))
        for x in range(16):
            dx = x + 0.5 - 8.0 - wob
            if abs(dx) <= half:
                lit = (x + f * 2 + row) % 4 < 2
                im.set(x, y, ICE[5] if lit else ICE[3])
                if abs(dx) > half - 1.2:
                    im.set(x, y + 1, ICE[2])
    im.set(8, 14, ICE[4]); im.set(7, 14, ICE[2]); im.set(9, 14, ICE[2])
    return im.outline()


def glacier_lance(f):
    """Gletscherlanze: riesiger diagonaler Eisspeer, ein Glanz läuft die Spitze hinauf."""
    im = Img()
    for i in range(13):
        x, y = 2 + i, 13 - i
        w = 1 if i > 9 else 2
        for k in range(-w, w + 1):
            c = ICE[4] if k < 0 else ICE[3] if k == 0 else ICE[2]
            im.set(x + k, y, c)
            im.set(x, y + k, c)
    im.set(15, 0, ICE[5]); im.set(14, 1, ICE[5])
    g = 2 + (f * 2) % 12
    im.set(g, 15 - g, ICE[5]); im.set(g + 1, 14 - g, GOLD[3])
    for x, y in ((1, 13), (2, 14), (1, 14), (0, 15)):
        im.set(x, y, GOLD[2])
    return im.outline()


ICONS = {
    'white_core': dragon_breath,
    'frost_step': wing_leap,
    'counter': counter,
    'wingscale': wingscale,
    'art/white_core': dragon_breath,
    'art/frost_step': wing_leap,
    'art/clear_cut': clear_cut,
    'art/white_hole': white_hole,
    'art/tornado': tornado,
    'art/glacier_lance': glacier_lance,
}

# Namen, die gear_art.py (hud/) und spell_art.py (hud/art/) nicht mehr selbst schreiben.
NAMES = {'white_core', 'frost_step', 'counter', 'wingscale', 'clear_cut', 'white_hole'}


# ---------------------------------------------------------------------------------------------------------------------

def png(path, w, h, rows):
    raw = b''.join(b'\x00' + bytes(v for p in row for v in p) for row in rows)

    def chunk(t, d):
        return struct.pack('>I', len(d)) + t + d + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'wb') as fh:
        fh.write(b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 6, 0, 0, 0)) +
                 chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b''))


def strip(fn):
    rows = []
    for f in range(FRAMES):
        im = fn(f)
        for y in range(S):
            rows.append([c if c is not None else (0, 0, 0, 0) for c in im.px[y]])
    return rows


def write_all():
    for name, fn in ICONS.items():
        path = os.path.join(ASSETS, *name.split('/')) + '.png'
        png(path, S, S * FRAMES, strip(fn))
        with open(path + '.mcmeta', 'w', encoding='utf8', newline='\n') as fh:
            fh.write('{\n  "animation": {\n    "interpolate": false,\n    "frametime": %d\n  }\n}\n' % FRAMETIME)


def preview():
    names = [n for n in ICONS if not n.startswith('art/') or n.split('/')[1] not in ICONS]
    scale = 8
    gap = 4
    w = len(names) * (S * scale + gap) + gap
    h = FRAMES * (S * scale + gap) + gap
    bg = (36, 38, 48, 255)
    canvas = [[bg] * w for _ in range(h)]
    for i, n in enumerate(names):
        rows = strip(ICONS[n])
        for f in range(FRAMES):
            for y in range(S):
                for x in range(S):
                    c = rows[f * S + y][x]
                    if c[3] == 0:
                        continue
                    for yy in range(scale):
                        for xx in range(scale):
                            canvas[gap + f * (S * scale + gap) + y * scale + yy][gap + i * (S * scale + gap) + x * scale + xx] = c
    png(PREVIEW, w, h, canvas)
    print(PREVIEW)


if __name__ == '__main__':
    preview()
    if '--preview' not in sys.argv:
        write_all()
        print('geschrieben:', ', '.join(ICONS))
