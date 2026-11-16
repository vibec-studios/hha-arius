"""Grafiken für Arius' Kampfhandschuhe und ihre Zauber.

Aufruf (aus dem Projektordner):
    python tools/assets/spell_art.py            # alles schreiben (Texturen per mctex, JSON direkt) + Vorschauen
    python tools/assets/spell_art.py --preview  # nur Vorschauen nach tmp/preview/spells/

Inhalt:
  * Item-Icon `arius_gauntlets` (32 x 32 wie die Blood/Void-Icons): aus dem 3D-Handschuhmodell gerendert
    (gauntlets_model.py, Magiemodus, beide Hände nebeneinander), 8 x 8 Abtastpunkte je Pixel, dominante Farbe,
    1-px-Umriss. Item-Definition: Icon in gui/fixed/ground/on_shelf, in allen Hand-Kontexten nichts (die Handschuhe
    zeichnet der Renderer direkt auf die Arme).
  * Bau-Blöcke der Zauber (durchscheinend, echtes Alpha): `frost_construct` (blasses Kristalleis mit feinen
    Bruchlinien im 60°-Gitter) und `yang_construct` (warmweißes Licht mit feinem goldenem Wabenmuster, Rand heller).
  * Rad-/HUD-Symbole 16 x 16 unter textures/gui/sprites/hud/art/ im Stil der HUD-Sprites aus gear_art.py: helle
    Glyphe, dunkler Umriss. Farbwelt: Wind blassgrau-blau, Eis eisblau, Yang warmweiß-gold.
"""
import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import gear_art as G  # noqa: E402
from gear_art import Tex, hexc, mix, outline, sheet, upscale, write_json, write_png  # noqa: E402

PREVIEW = os.path.join(G.ROOT, 'tmp', 'preview', 'spells')
OUT = hexc('#0d1220')              # Umriss wie die vorhandenen HUD-Sprites

# Farbwelten (hell -> dunkel)
W0, W1, W2, W3 = hexc('#f4f7fc'), hexc('#cfd9e8'), hexc('#a3b4cc'), hexc('#71839f')      # Wind
I0, I1, I2, I3 = hexc('#eefaff'), hexc('#a8e8ff'), hexc('#5fb8e6'), hexc('#2f74b0')      # Eis
Y0, Y1, Y2, Y3 = hexc('#fffcf2'), hexc('#ffecb8'), hexc('#f2c766'), hexc('#c08a32')      # Yang
P0, P1, P2 = hexc('#ffffff'), hexc('#dde4ef'), hexc('#a9b4c8')                          # Perlweiß (Handschuh)
GD = hexc('#d2a654')


# ---------------------------------------------------------------------------------------------------------------------
# Kleiner Zeichenkasten: Formen werden 4x4-fach abgetastet und ab halber Deckung gesetzt (klare Pixelkanten)
# ---------------------------------------------------------------------------------------------------------------------
class Canvas(Tex):
    SS = 4

    def __init__(self, n=16):
        super().__init__(n, n)

    def shape(self, inside, color):
        """inside(x, y) -> bool in Pixelkoordinaten (0..16); color: Farbe oder f(x, y) -> Farbe."""
        s = self.SS
        for py in range(self.h):
            for px in range(self.w):
                hit = 0
                for sy in range(s):
                    for sx in range(s):
                        if inside(px + (sx + 0.5) / s, py + (sy + 0.5) / s):
                            hit += 1
                if hit * 2 >= s * s:
                    self.set(px, py, color(px + 0.5, py + 0.5) if callable(color) else color)
        return self

    def circle(self, cx, cy, r, color):
        return self.shape(lambda x, y: (x - cx) ** 2 + (y - cy) ** 2 <= r * r, color)

    def ring(self, cx, cy, r0, r1, color, ry=None):
        k = (r1 / ry) if ry else 1.0

        def inside(x, y):
            d = math.hypot(x - cx, (y - cy) * k)
            return r0 <= d <= r1
        return self.shape(inside, color)

    def line(self, x0, y0, x1, y1, r, color):
        def inside(x, y):
            dx, dy = x1 - x0, y1 - y0
            t = max(0.0, min(1.0, ((x - x0) * dx + (y - y0) * dy) / max(dx * dx + dy * dy, 1e-9)))
            return math.hypot(x - x0 - t * dx, y - y0 - t * dy) <= r
        return self.shape(inside, color)

    def poly(self, pts, color):
        def inside(x, y):
            c = False
            j = len(pts) - 1
            for i in range(len(pts)):
                xi, yi = pts[i]
                xj, yj = pts[j]
                if (yi > y) != (yj > y) and x < (xj - xi) * (y - yi) / (yj - yi) + xi:
                    c = not c
                j = i
            return c
        return self.shape(inside, color)

    def arc(self, cx, cy, r, a0, a1, width, color, ry=None):
        """Bogen von a0 bis a1 (Grad, 0 = rechts, im Uhrzeigersinn, weil y nach unten zeigt)."""
        k = (r / ry) if ry else 1.0

        def inside(x, y):
            dx, dy = x - cx, (y - cy) * k
            if abs(math.hypot(dx, dy) - r) > width / 2:
                return False
            a = math.degrees(math.atan2(dy, dx)) % 360
            lo, hi = a0 % 360, a1 % 360
            return lo <= a <= hi if lo <= hi else (a >= lo or a <= hi)
        return self.shape(inside, color)

    def dots(self, pts, color):
        for x, y in pts:
            self.set(x, y, color)
        return self

    def grid(self, rows, legend, ox=0, oy=0):
        for y, row in enumerate(rows):
            for x, ch in enumerate(row):
                if ch in legend:
                    self.set(ox + x, oy + y, legend[ch])
        return self

    def done(self, diagonal=False):
        return outline(self, OUT, diagonal)


def lit(c_hi, c_lo, cx=8, cy=8, ax=-1, ay=-1):
    """Zweiton-Schattierung: Seite zum Licht (oben links) hell, gegenüber dunkler."""
    def f(x, y):
        return c_hi if (x - cx) * ax + (y - cy) * ay > 0 else c_lo
    return f


# ---------------------------------------------------------------------------------------------------------------------
# HUD-/Rad-Symbole
# ---------------------------------------------------------------------------------------------------------------------
def wind_blade():
    """Windklinge: eine schlanke Sichel, dahinter zwei Böenlinien."""
    c = Canvas()
    c.shape(lambda x, y: math.hypot(x - 7, y - 9) <= 6.6 and math.hypot(x - 4.8, y - 11.2) > 6.0,
            lambda x, y: W0 if x + y < 15 else W1)
    c.line(1.5, 14.5, 4.0, 12.0, 0.5, W2)
    c.line(4.0, 15.0, 7.0, 12.4, 0.45, W2)
    return c.done()


def wind_leap():
    """Windsprung: zwei Aufwärts-Winkel über einer Böenwelle."""
    c = Canvas()
    for oy, col in ((1.5, W0), (5.5, W1)):
        c.line(3, oy + 5, 8, oy, 0.75, col)
        c.line(8, oy, 13, oy + 5, 0.75, col)
    c.arc(5.5, 12.0, 2.3, 180, 360, 1.1, W2)
    c.arc(10.5, 12.0, 2.3, 0, 180, 1.1, W2)
    return c.done()


def gale():
    """Sturmstoß: ein Kegel, der sich nach rechts öffnet, mit Windlinien darin."""
    c = Canvas()
    c.poly([(1.5, 8), (14.5, 1.5), (14.5, 14.5)], lambda x, y: W2 if y > 8 + (x - 1.5) * 0.18 else W1)
    for y0, k in ((5.5, 0.6), (8.0, 0.0), (10.5, -0.6)):
        c.line(5.5, 8 + (y0 - 8) * 0.3, 14.0, y0 + k * 2.3, 0.42, W0)
    return c.done()


def wind_pull():
    """Windsog: Spirale, die nach innen läuft, mit kleinem Kern."""
    c = Canvas()

    def spiral(x, y):
        dx, dy = x - 8, y - 8
        r = math.hypot(dx, dy)
        if r < 1.2 or r > 7.4:
            return False
        a = math.atan2(dy, dx) % (2 * math.pi)
        for turn in range(3):
            rr = 1.4 + (a + turn * 2 * math.pi) * 0.5
            if abs(r - rr) < 0.62:
                return True
        return False
    c.shape(spiral, lambda x, y: W0 if math.hypot(x - 8, y - 8) > 4 else W1)
    c.circle(8, 8, 1.3, W2)
    return c.done()


def frost_shards():
    """Frostsplitter: drei schlanke Eissplitter (lange Rauten), parallel nach rechts oben fliegend."""
    c = Canvas()
    for cx, cy, ang, ln, w in ((5.2, 6.6, -45, 8.0, 1.4), (7.8, 8.6, -45, 12.0, 1.7), (10.6, 11.0, -45, 7.5, 1.3)):
        a = math.radians(ang)
        ux, uy = math.cos(a), math.sin(a)
        nx, ny = -uy, ux
        head = (cx + ux * ln * 0.55, cy + uy * ln * 0.55)
        tail = (cx - ux * ln * 0.45, cy - uy * ln * 0.45)
        c.poly([head, (cx + nx * w, cy + ny * w), tail, (cx - nx * w, cy - ny * w)],
               lambda x, y, nx=nx, ny=ny, cx=cx, cy=cy: I1 if (x - cx) * nx + (y - cy) * ny < 0 else I2)
        c.line(cx, cy, head[0] - ux * 1.0, head[1] - uy * 1.0, 0.28, I0)
    return c.done()


def ice_wall():
    """Eiswand: drei Kristallsäulen mit gezackter Krone über einer Bodenlinie."""
    c = Canvas()
    cols = ((2.0, 5.5, 4.0), (5.5, 10.5, 1.5), (10.5, 14.0, 3.0))
    for x0, x1, top in cols:
        xm = (x0 + x1) / 2
        c.poly([(x0, 13.5), (x0, top + 2.5), (xm, top), (x1, top + 2.5), (x1, 13.5)],
               lambda x, y, xm=xm: I1 if x < xm else I2)
        c.line(xm, top + 1.5, xm, 12.5, 0.25, I0)
    c.line(1.0, 14.3, 15.0, 14.3, 0.45, I3)
    return c.done()


def frost_field():
    """Frostfeld: Frostkreis flach am Boden (Ellipse), in ihm Eiszacken."""
    c = Canvas()
    c.ring(8, 11.5, 5.6, 7.4, lambda x, y: I1 if y < 11.5 else I2, ry=3.0)
    c.shape(lambda x, y: ((x - 8) / 5.6) ** 2 + ((y - 11.5) / 2.2) ** 2 <= 1, I3)
    for x0, h in ((4.6, 4.5), (8.0, 8.0), (11.4, 4.5)):
        c.poly([(x0 - 1.0, 12.0), (x0, 12.0 - h), (x0 + 1.0, 12.0)], lambda x, y, x0=x0: I0 if x < x0 else I1)
    return c.done()


def ice_armor():
    """Eispanzer: Brustplatte aus Eis mit Facettengrat."""
    c = Canvas()
    c.poly([(2, 3), (6, 1.5), (8, 3), (10, 1.5), (14, 3), (13.2, 10), (8, 14.8), (2.8, 10)],
           lambda x, y: I1 if x < 8 else I2)
    c.line(8, 3.6, 8, 13.4, 0.35, I0)
    c.line(3.4, 5.0, 7.0, 8.0, 0.3, I0)
    c.line(12.6, 5.0, 9.0, 8.0, 0.3, I1)
    return c.done()


def yang_orb():
    """Yang-Kugel: leuchtende Kugel mit vier kurzen Strahlen."""
    c = Canvas()
    for a in range(0, 360, 45):
        r0, r1 = (6.0, 7.6) if a % 90 == 0 else (5.6, 6.6)
        c.line(8 + math.cos(math.radians(a)) * r0, 8 + math.sin(math.radians(a)) * r0,
               8 + math.cos(math.radians(a)) * r1, 8 + math.sin(math.radians(a)) * r1, 0.45, Y2)
    c.circle(8, 8, 4.6, lambda x, y: Y1 if math.hypot(x - 6.8, y - 6.8) < 3.4 else Y2)
    c.circle(6.8, 6.8, 1.6, Y0)
    return c.done()


def yang_shield():
    """Yang-Schild: Wappenschild in Warmweiß mit kleiner Sonne."""
    c = Canvas()
    c.poly([(2.2, 2), (13.8, 2), (13.8, 8), (8, 14.8), (2.2, 8)], lambda x, y: Y0 if x < 8 else Y1)
    c.poly([(3.6, 3.4), (12.4, 3.4), (12.4, 7.6), (8, 12.9), (3.6, 7.6)], lambda x, y: Y1 if x < 8 else Y2)
    c.circle(8, 6.6, 2.0, Y0)
    c.dots([(8, 3), (8, 10), (5, 7), (11, 7)], Y0)
    return c.done()


def yang_platform():
    """Plattform: schwebende Lichtplatte (schräg von oben) mit Leuchten darunter."""
    c = Canvas()
    c.poly([(1.5, 7), (8, 4), (14.5, 7), (8, 10)], lambda x, y: Y0 if y < 7 else Y1)
    c.poly([(1.5, 7), (8, 10), (8, 11.5), (1.5, 8.5)], Y2)
    c.poly([(14.5, 7), (8, 10), (8, 11.5), (14.5, 8.5)], Y3)
    for x0 in (4.5, 8.0, 11.5):
        c.line(x0, 12.8, x0, 14.2, 0.35, Y1)
    return c.done()


def yang_bridge():
    """Lichtbrücke: flacher Bogen aus Lichtsegmenten zwischen zwei Pfeilern."""
    c = Canvas()
    c.arc(8, 15.5, 7.5, 200, 340, 1.6, lambda x, y: Y0 if x < 8 else Y1, ry=8.5)
    for k in range(5):
        a = math.radians(205 + k * 32.5)
        c.set(int(8 + math.cos(a) * 7.5 * 1.0), int(15.5 + math.sin(a) * 8.5), Y2)
    c.line(1.5, 9.5, 1.5, 14.5, 0.6, Y2)
    c.line(14.5, 9.5, 14.5, 14.5, 0.6, Y3)
    return c.done()


def yang_glide():
    """Lichtgleiter: schräge Scheibe mit Bewegungslinien."""
    c = Canvas()
    c.shape(lambda x, y: ((x - 9.5) / 5.4) ** 2 + ((y - 8.5) / 2.4) ** 2 <= 1, lambda x, y: Y0 if y < 8.3 else Y2)
    c.shape(lambda x, y: ((x - 9.5) / 2.2) ** 2 + ((y - 7.6) / 1.0) ** 2 <= 1, Y1)
    for y0, x0 in ((6.0, 1.2), (9.0, 0.6), (12.0, 2.0)):
        c.line(x0, y0 + 1.0, x0 + 2.4, y0 + 0.6, 0.4, Y1)
    return c.done()


def school_wind():
    """Schule Wind: drei geschwungene Böen um einen Kern (Wirbel)."""
    c = Canvas()
    for k in range(3):
        base = k * 120
        c.arc(8 + math.cos(math.radians(base + 90)) * 2.0, 8 + math.sin(math.radians(base + 90)) * 2.0,
              4.8, base + 140, base + 260, 1.5, W0 if k == 0 else W1 if k == 1 else W2)
    c.circle(8, 8, 1.4, W1)
    return c.done()


def school_ice():
    """Schule Eis: sechsstrahliger Schneekristall."""
    c = Canvas()
    for k in range(6):
        a = math.radians(90 + k * 60)
        ex, ey = 8 + math.cos(a) * 6.8, 8 - math.sin(a) * 6.8
        c.line(8, 8, ex, ey, 0.55, I1 if k in (0, 1, 5) else I2)
        mx, my = 8 + math.cos(a) * 4.2, 8 - math.sin(a) * 4.2
        for s in (-1, 1):
            b = a + s * math.radians(45)
            c.line(mx, my, mx + math.cos(b) * 2.0, my - math.sin(b) * 2.0, 0.4, I1)
    c.circle(8, 8, 1.3, I0)
    return c.done()


def school_yang():
    """Schule Yang: Sonnenscheibe mit acht Strahlen."""
    c = Canvas()
    for k in range(8):
        a = math.radians(k * 45 + 22.5)
        c.poly([(8 + math.cos(a - 0.22) * 4.6, 8 + math.sin(a - 0.22) * 4.6), (8 + math.cos(a) * 7.6, 8 + math.sin(a) * 7.6),
                (8 + math.cos(a + 0.22) * 4.6, 8 + math.sin(a + 0.22) * 4.6)], Y2 if k % 2 else Y1)
    c.circle(8, 8, 4.4, Y2)
    c.circle(8, 8, 3.3, lambda x, y: Y0 if x + y < 15.5 else Y1)
    return c.done()


FIST = [
    '................',
    '................',
    '....pp.pp.pp....',
    '...pPPpPPpPPp...',
    '...pPPpPPpPPpp..',
    '..ppPPpPPpPPPPp.',
    '..pPPPPPPPPPPPp.',
    '..pPttttttPPPp..',
    '..pPPPPPPtPPPp..',
    '...pPPPPPPPPp...',
    '....ggggggggg...',
    '....pPPPPPPPp...',
    '....pPPPPPPPp...',
    '....pPPPPPPPp...',
    '....ppppppppp...',
    '................',
]
OPEN_HAND = [
    '......p.........',
    '....p.Pp.p......',
    '....PpPp.Pp.....',
    '....PpPpPPp.....',
    '.p..PpPpPPpp....',
    '.Pp.PpPpPPp.....',
    '.PPpPpPpPPp.....',
    '..PPPPPPPPp.....',
    '..pPPPsPPPp.....',
    '...pPsssPPp.....',
    '...pPPsPPp......',
    '....ggggggg.....',
    '....pPPPPPp.....',
    '....pPPPPPp.....',
    '....ppppppp.....',
    '................',
]


def mode_melee():
    """Nahkampfmodus: geballte Faust im Handschuh (Perlweiß, Goldsaum)."""
    c = Canvas()
    c.grid(FIST, {'p': P2, 'P': P0, 'g': GD, 't': P1})
    return c.done()


def mode_magic():
    """Magiemodus: offene Hand, in der Handfläche die Yang-Sonne, darüber ein Funke."""
    c = Canvas()
    c.grid(OPEN_HAND, {'p': P2, 'P': P0, 'g': GD, 's': Y1})
    c.dots([(13, 2), (12, 3), (14, 3), (13, 4)], I1)
    c.set(13, 3, I0)
    return c.done()


def clear_cut():
    """Klarer Schnitt: eine gleißende Schnittlinie, die das Feld diagonal teilt, mit versetzten Hälften."""
    c = Canvas()
    c.line(1.5, 14.5, 14.5, 1.5, 0.75, lambda x, y: W0 if abs(x + y - 16) < 0.9 else I1)
    c.line(3.0, 14.8, 7.0, 10.8, 0.35, I2)
    c.line(9.0, 5.2, 13.0, 1.2, 0.35, I2)
    c.dots([(8, 7), (7, 8)], P0)
    return c.done()


def white_hole():
    """Weißes Loch: gleißender Kern, umgeben von einer schräg liegenden Lichtscheibe."""
    c = Canvas()
    c.ring(8, 8, 4.6, 7.4, lambda x, y: I1 if y < 8 else I2, ry=2.6)
    c.circle(8, 8, 3.6, I0)
    c.circle(8, 8, 2.4, P0)
    c.arc(8, 8, 6.0, 200, 340, 1.2, I0, ry=2.1)                               # vordere Scheibe läuft vor dem Kern
    return c.done()


HUD_ART = {
    'wind_blade': wind_blade, 'wind_leap': wind_leap, 'gale': gale, 'wind_pull': wind_pull,
    'frost_shards': frost_shards, 'ice_wall': ice_wall, 'frost_field': frost_field, 'ice_armor': ice_armor,
    'yang_orb': yang_orb, 'yang_shield': yang_shield, 'yang_platform': yang_platform, 'yang_bridge': yang_bridge,
    'yang_glide': yang_glide,
    'school_wind': school_wind, 'school_ice': school_ice, 'school_yang': school_yang,
    'mode_melee': mode_melee, 'mode_magic': mode_magic,
    'clear_cut': clear_cut, 'white_hole': white_hole,
    'frost_step': G.hud_frost_step, 'white_core': G.hud_white_core,          # bewährte Motive aus gear_art
}


# ---------------------------------------------------------------------------------------------------------------------
# Bau-Blöcke (16 x 16, durchscheinend)
# ---------------------------------------------------------------------------------------------------------------------
def _voronoi(pts, x, y, size=16):
    """Abstand zum nächsten und zweitnächsten Punkt (nahtlos kachelnd), Index des nächsten."""
    best = []
    for i, (px, py) in enumerate(pts):
        dx = min(abs(x - px), size - abs(x - px))
        dy = min(abs(y - py), size - abs(y - py))
        best.append((math.hypot(dx, dy), i))
    best.sort()
    return best[0][0], best[1][0], best[0][1]


FROST_SEEDS = [(2.5, 3.0), (8.5, 1.5), (13.5, 4.5), (5.5, 8.5), (11.0, 9.5), (1.5, 12.5), (7.5, 13.5), (14.0, 14.0)]


def frost_construct():
    """Blasses Kristalleis: unregelmäßige Kristallfacetten mit feinen hellen Bruchkanten (Voronoi, nahtlos), jede
    Facette leicht anders getönt, Rand heller. Alpha ca. 45–72 %; bewusst kein Vanilla-Eis (dort diagonale Schlieren)."""
    t = Tex(16, 16)
    tints = [hexc('#cfeeff'), hexc('#dcf3ff'), hexc('#c4e6fb'), hexc('#e6f7ff')]
    alphas = [0.48, 0.55, 0.5, 0.6]
    for y in range(16):
        for x in range(16):
            d1, d2, i = _voronoi(FROST_SEEDS, x + 0.5, y + 0.5)
            col, a = tints[i % 4], alphas[i % 4]
            # leichter Verlauf in jeder Facette: zur Kante hin heller (Lichtbrechung)
            col = mix(col, hexc('#f4fbff'), max(0.0, 0.35 - (d2 - d1) * 0.12))
            if d2 - d1 < 0.9:
                col, a = hexc('#f7fdff'), 0.74                               # feine Bruchkante
            if x in (0, 15) or y in (0, 15):
                col, a = hexc('#effaff'), 0.72
            t.set(x, y, col[:3] + (int(a * 255),))
    for x, y in ((3, 4), (12, 7), (6, 11)):
        t.set(x, y, (255, 255, 255, 225))                                   # Funkelpunkte
    return t


def yang_construct():
    """Warmweißes Licht mit feinem goldenem Wabenmuster und hellerem Rand; die Mittelwabe ist eine kleine Sonne."""
    t = Tex(16, 16)
    light = hexc('#fff3d8')
    centers = [(7.5 + i * 5 + (j % 2) * 2.5, 7.5 + j * 4.33) for i in range(-3, 4) for j in range(-3, 4)]
    for y in range(16):
        for x in range(16):
            ds = sorted((math.hypot(x - cx, y - cy), k) for k, (cx, cy) in enumerate(centers))
            (d1, k1), (d2, _) = ds[0], ds[1]
            col, a = light, 0.42 + 0.1 * math.exp(-((x - 7.5) ** 2 + (y - 7.5) ** 2) / 26.0)
            if d2 - d1 < 0.9:
                col, a = hexc('#eec062'), 0.66                               # goldene Wabenkante
            elif centers[k1] == (7.5, 7.5) and d1 < 1.6:
                col, a = hexc('#fffaee'), 0.66                               # Sonnenkern
            if x in (0, 15) or y in (0, 15):
                col, a = hexc('#fffaf0'), 0.8
            elif x in (1, 14) or y in (1, 14):
                col, a = mix(light, hexc('#ffe7a8'), 0.55), max(a, 0.6)
            t.set(x, y, col[:3] + (int(a * 255),))
    return t


BLOCKS = {'frost_construct': frost_construct, 'yang_construct': yang_construct}


# ---------------------------------------------------------------------------------------------------------------------
# Handschuh-Icon: aus dem 3D-Modell gerendert
# ---------------------------------------------------------------------------------------------------------------------
def icon_gauntlets(n=32, ss=8, dx=2.2, roll=-0.1, turn=0.5, view=-25.0, dy=1.5, dz=2.0):
    import boss_models
    import preview as P

    model = boss_models.build('gauntlets')
    P.FACE_SHADE = {'top': 1.0, 'bottom': 0.62, 'front': 0.92, 'back': 0.78, 'left': 0.74, 'right': 0.84}
    # Beide Hände im Magiemodus, senkrecht wie in der Referenz, aber versetzt hintereinander (vorn links tiefer,
    # hinten rechts höher) und leicht gedreht, damit sich die zwei Umrisse im 32er-Icon nicht zu einem Block vereinen.
    r = (0, turn, -roll, -dx, dy, -dz)
    l_ = (0, -turn, roll, dx, -dy, dz)
    pose = {'r_base': r, 'r_open': r, 'r_fist': None, 'l_base': l_, 'l_open': l_, 'l_fist': None}
    pts = P.world_points(model, pose)
    P.FACE_SHADE = None
    yaw = math.radians(view)
    proj = P.project(pts, yaw)
    xs, ys = [q[0] for q in proj], [q[1] for q in proj]
    span = max(max(xs) - min(xs), max(ys) - min(ys))
    big = n * ss
    scale = (n - 2.6) * ss / span
    cx, cy = (max(xs) + min(xs)) / 2, (max(ys) + min(ys)) / 2
    depth = [1e9] * (big * big)
    buf = [None] * (big * big)
    rad = max(1, int(math.ceil(scale * 0.3)))
    for x, y, z, col in proj:
        sx, sy = int(big / 2 + (x - cx) * scale), int(big / 2 + (y - cy) * scale)
        for dy in range(-rad, rad):
            for dx in range(-rad, rad):
                px, py = sx + dx, sy + dy
                if 0 <= px < big and 0 <= py < big and z < depth[py * big + px]:
                    depth[py * big + px] = z
                    buf[py * big + px] = col
    icon = Tex(n, n)
    for y in range(n):
        for x in range(n):
            counts, hits = {}, 0
            for sy in range(ss):
                for sx in range(ss):
                    c = buf[(y * ss + sy) * big + x * ss + sx]
                    if c is None:
                        continue
                    hits += 1
                    counts.setdefault(tuple(v // 8 * 8 for v in c), []).append(c)
            if hits * 3 < ss * ss:
                continue
            best = max(counts.values(), key=len)
            icon.set(x, y, tuple(sum(cc[k] for cc in best) // len(best) for k in range(3)) + (255,))
    return outline(icon, hexc('#14141c'))


# ---------------------------------------------------------------------------------------------------------------------
def preview(hud, blocks, gicon):
    os.makedirs(PREVIEW, exist_ok=True)
    names = list(hud)
    sheet([upscale(hud[k], 8) for k in names], 8, os.path.join(PREVIEW, 'hud_art_big.png'))
    # Spielgröße auf dunklem Grund (HUD) und im Rad (zwei Größen)
    W, H = 22 * len(names) + 6, 48
    px = [(24, 26, 32)] * (W * H)
    for i, k in enumerate(names):
        G.blend_onto(px, W, 4 + i * 22, 3, hud[k], 1)
        G.blend_onto(px, W, 4 + i * 22 - 8 if i else 4, 22, hud[k], 1)
    write_png(os.path.join(PREVIEW, 'hud_art_ingame.png'), W, H, px)
    # Blöcke: Fläche aus 3 x 3 Blöcken vor hellem und dunklem Himmel
    panels = []
    for name, t in blocks.items():
        for bg in ((150, 190, 230), (40, 44, 56)):
            S = 4
            w = 16 * 3 * S
            img = [bg] * (w * w)
            for y in range(w):
                for x in range(w):
                    c = t.get((x // S) % 16, (y // S) % 16)
                    a = c[3] / 255.0
                    img[y * w + x] = tuple(int(b + (cc - b) * a) for b, cc in zip(bg, c[:3]))
            panels.append((w, w, img))
    sheet(panels, 4, os.path.join(PREVIEW, 'constructs.png'))
    gw, gh, gpx = upscale(gicon, 8)
    sheet([(gw, gh, gpx), upscale(gicon, 2, checker=False), upscale(gicon, 1, checker=False)], 3,
          os.path.join(PREVIEW, 'gauntlets_icon.png'))


def write_jsons():
    write_json('models/item/arius_gauntlets.json', {'parent': 'minecraft:item/generated',
                                                    'textures': {'layer0': 'hha_arius:item/arius_gauntlets'}})
    hands = ['firstperson_righthand', 'firstperson_lefthand', 'thirdperson_righthand', 'thirdperson_lefthand', 'head']
    write_json('items/arius_gauntlets.json', {'model': {
        'type': 'minecraft:select', 'property': 'minecraft:display_context',
        'cases': [
            {'when': ['gui', 'fixed', 'ground', 'on_shelf'],
             'model': {'type': 'minecraft:model', 'model': 'hha_arius:item/arius_gauntlets'}},
            {'when': hands, 'model': {'type': 'minecraft:empty'}},
        ],
        'fallback': {'type': 'minecraft:empty'}}})
    for name in BLOCKS:
        write_json(f'models/block/{name}.json', {'parent': 'minecraft:block/cube_all',
                                                  'textures': {'all': f'hha_arius:block/{name}'}})
        write_json(f'blockstates/{name}.json', {'variants': {'': {'model': f'hha_arius:block/{name}'}}})


def main(argv):
    hud = {k: f() for k, f in HUD_ART.items()}
    blocks = {k: f() for k, f in BLOCKS.items()}
    gicon = icon_gauntlets()
    preview(hud, blocks, gicon)
    if '--preview' in argv:
        return
    jobs = [(t, f'textures/gui/sprites/hud/art/{k}.png') for k, t in hud.items()]
    jobs += [(t, f'textures/block/{k}.png') for k, t in blocks.items()]
    jobs.append((gicon, 'textures/item/arius_gauntlets.png'))
    G.save_textures(jobs)
    write_jsons()


if __name__ == '__main__':
    main(sys.argv[1:])
