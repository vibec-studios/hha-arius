"""Arius' Kampfhandschuhe: ein Paar Plattenhandschuhe, getragen als EIN Item in der Haupthand.

Gestaltung nach der Referenz (helle Platten, goldene Einfassung, gegliederte Finger, markante Unterarmschienen),
übertragen auf Arius und Solvane: kühles Perlweiß, Gold nur als Kante, und statt roter Ornamente drei Motive:
* Wind  – geschwungene, gravierte Böen auf der Außenseite der Schiene (beidseits eines schmalen Grats),
* Eis   – ein sechsstrahliger Kristall auf der Vorderseite der Schiene,
* Yang  – eine Sonnenscheibe auf dem Handrücken; sie leuchtet im Magiemodus (Leuchtmaske).

HD: Die Textur wird mit 2 Texeln je Modelleinheit gemalt (Bild 128 x 128), das Kotlin-Modell meldet weiter 64 x 64
(Minecraft normiert UVs auf die angegebene Größe). Dafür gibt es hier `HDCube`/`HDModel` mit eigenem Packen und Malen;
modelkit selbst bleibt unverändert, Arius und Nivaris kommen also genauso heraus wie vorher. Alle Maße liegen auf
dem Halbeinheiten-Raster, damit jede Fläche ganze Texel hat.

Koordinaten wie ein Spielerarm im eigenen Knochenraum (1/16 Block, +y nach unten, -z vorn): der rechte Arm liegt bei
x -3..1, y -2..10, z -2..2, die Hand ist das untere Ende (y = 10). Der linke Arm ist gespiegelt (x -1..3), samt Textur.
Alle Teile haben den Drehpunkt 0 und keine Drehung: Der Renderer setzt sie in den Raum des Spielerarms.

Anatomie: Bei hängendem Arm zeigt der Handrücken nach außen (rechter Arm -x), die Handfläche zum Rumpf (+x), der Daumen
nach vorn (-z). Die vier Finger liegen deshalb hintereinander entlang z (Zeigefinger vorn, kleiner Finger hinten).

Teile je Seite (r/l):
* `<s>_base`  Stulpe, Schiene mit Grat, untere Lamelle, Handplatte, Sonnenscheibe, Ziernieten     (immer)
* `<s>_fist`  Faust: eingerollte Finger, Knöchelschutz, quer gelegter Daumen                    (Nahkampfmodus)
* `<s>_open`  gestreckte, dreigliedrige Finger mit spitzen Kappen und Daumen                     (Magiemodus)

Regeln: Finger höchstens 3 Einheiten über das Armende (y <= 13); die Innenseite (rechter Arm +x) steht höchstens
0,45 über (glatt); alle Teile sitzen starr im Armraum, schneiden sich also beim Schlag nicht gegenseitig.
"""
import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)

from modelkit import Cube, Model, Part, hexc, mix, ramp, rgb, speckle  # noqa: E402
from texkit import Image, save  # noqa: E402

RES = 2                       # Texel je Modelleinheit

# Materialien (dunkel -> hell)
PLATE = rgb('#434a5c', '#737d92', '#a6b0c3', '#ccd4e2', '#e7ecf4', '#f8fafd', '#ffffff')   # kühles Perlweiß
GOLD = rgb('#3f240b', '#6e4518', '#9c7230', '#c49a4c', '#e2c37e', '#f8e8c0')            # gedecktes Altgold
UNDER = rgb('#1a1c26', '#262a38', '#363b4e', '#4a5068', '#636a84')                         # Unterzug/Leder (schiefer)
ICE = hexc('#6fbfe8')
ICE_HI = hexc('#d6f2ff')
WIND = hexc('#7186a8')        # gravierte Windlinie (dunkler als die Platte)
WIND_HI = hexc('#c6d5ea')
SUN = rgb('#a8661a', '#e2a33a', '#ffd673', '#fff0c4', '#fffaf0')

GLOW_SUN = (255, 248, 230, 255)
GLOW_SUN_RING = (255, 236, 200, 150)
GLOW_ICE = (190, 236, 255, 190)
GLOW_WIND = (214, 230, 255, 120)

LIGHT = {'top': 0.12, 'bottom': -0.2, 'front': 0.02, 'back': -0.1, 'left': -0.06, 'right': -0.03}
SIDES = ('front', 'back', 'left', 'right')
OUTER = 'right'               # rechter Arm: Außenseite -x (Fläche 'right'), Innenseite +x ('left')
INNER = 'left'


# ---------------------------------------------------------------------------------------------------------------------
# HD-Baukasten
# ---------------------------------------------------------------------------------------------------------------------
class HDCube(Cube):
    """Quader, dessen Flächen mit RES Texeln je Einheit gemalt werden (paint bekommt Texel-Koordinaten)."""
    res = RES


def mirrored(paint):
    """Malfunktion des rechten Arms für den gespiegelten linken: Seiten tauschen, alle Flächen horizontal spiegeln."""
    swap = {'left': 'right', 'right': 'left'}

    def wrapped(face, x, y, w, h):
        return paint(swap.get(face, face), w - 1 - x, y, w, h)
    return wrapped


class HDModel(Model):
    """Wie modelkit.Model, aber UV-Raster in Modelleinheiten (Kotlin meldet tex_w x tex_h) und Bild in RES-facher Größe."""

    def pack(self):
        cubes = sorted(self.cubes(), key=lambda c: (-c.uv_size[1], -c.uv_size[0]))
        x = y = row = 0
        for c in cubes:
            cw, ch = (int(math.ceil(v - 1e-6)) for v in c.uv_size)
            if x + cw > self.tex_w:
                x, y, row = 0, y + row, 0
            if y + ch > self.tex_h:
                raise SystemExit(f'Atlas {self.name} zu klein ({self.tex_w}x{self.tex_h})')
            c.u, c.v = x, y
            x += cw
            row = max(row, ch)
        print(f'{self.name}: {len(cubes)} Quader, Atlas belegt bis y={y + row}/{self.tex_h} (Bild {self.tex_w * RES}px)')

    def paint(self):
        W, H = self.tex_w * RES, self.tex_h * RES
        albedo, glow = Image(W, H), Image(W, H)
        for c in self.cubes():
            u, v = c.u * RES, c.v * RES
            w, h, d = (int(round(k * RES)) for k in (c.w, c.h, c.d))
            faces = [
                ('top', u + d, v, w, d),
                ('bottom', u + d + w, v, w, d),
                ('right', u, v + d, d, h),
                ('front', u + d, v + d, w, h),
                ('left', u + d + w, v + d, d, h),
                ('back', u + d + w + d, v + d, w, h),
            ]
            for name, fu, fv, fw, fh in faces:
                for iy in range(fh):
                    for ix in range(fw):
                        res = c.paint(name, ix, iy, fw, fh)
                        if res is None:
                            continue
                        if isinstance(res[0], tuple):
                            col, g = res
                            albedo.set(fu + ix, fv + iy, col)
                            if g is not None:
                                glow.set(fu + ix, fv + iy, g)
                        else:
                            albedo.set(fu + ix, fv + iy, res)
        save(albedo, f'textures/entity/{self.texture}.png')
        save(glow, f'textures/entity/{self.texture}_glow.png')
        return albedo, glow


# ---------------------------------------------------------------------------------------------------------------------
# Grundmaterialien (alle in Texel-Koordinaten: x nach rechts, y nach unten auf der Fläche, w/h = Flächengröße)
# ---------------------------------------------------------------------------------------------------------------------
def _n(x, y, seed):
    return speckle(x * 3 + seed * 5, y * 3 + seed * 7, seed) - 0.5


def enamel_t(face, x, y, w, h, base=0.62, seed=0, gloss=True):
    """Helligkeit (0..1) einer Perlweiß-Platte: Flächenlicht, Verlauf, Pixel-Fase, Glanzstreif, feines Korn."""
    t = base + LIGHT[face] + _n(x, y, seed) * 0.05
    if face in SIDES:
        t += (0.5 - y / max(h - 1, 1)) * 0.12
        if h > 2 and y == 0:
            t += 0.12                                                       # Fase oben fängt Licht
        if h > 2 and y == h - 1:
            t -= 0.1
        if w > 2 and x in (0, w - 1):
            t -= 0.05
        if gloss and w > 2:
            k = x / (w - 1)
            t += 0.13 * math.exp(-((k - 0.3) / 0.16) ** 2)                 # Emaille-Glanz
    elif w > 2 and h > 2 and (x in (0, w - 1) or y in (0, h - 1)):
        t += 0.06
    return t


def enamel(base=0.62, seed=0, gloss=True):
    def paint(face, x, y, w, h):
        return ramp(PLATE, enamel_t(face, x, y, w, h, base, seed, gloss))
    return paint


def gold_c(face, x, y, w, h, base=0.58, seed=3):
    t = base + LIGHT[face] * 1.3 + _n(x, y, seed) * 0.06
    if face in SIDES:
        t += (0.5 - y / max(h - 1, 1)) * 0.16
        if h > 1 and y == 0:
            t += 0.14
        if w > 2:
            t += 0.12 * math.exp(-((x / (w - 1) - 0.32) / 0.2) ** 2)
    return ramp(GOLD, t)


def gold(base=0.58, seed=3):
    def paint(face, x, y, w, h):
        return gold_c(face, x, y, w, h, base, seed)
    return paint


def under_c(face, x, y, w, h, base=0.45):
    return ramp(UNDER, base + LIGHT[face] + _n(x, y, 9) * 0.12)


def opening(face, x, y, w, h, rim):
    """Ober-/Unterseite eines Rohrs: Goldrand außen, dunkle Öffnung (hier steckt der Arm)."""
    if x < rim or y < rim or x >= w - rim or y >= h - rim:
        return None
    return under_c(face, x, y, w, h, 0.3)


# ---------------------------------------------------------------------------------------------------------------------
# Bauteile
# ---------------------------------------------------------------------------------------------------------------------
def flare():
    """Ausgestellte Stulpe am Ellbogen: goldener Wulst oben, Perlweiß darunter, vorn eine Spitzbogen-Kante."""
    def paint(face, x, y, w, h):
        if face in ('top', 'bottom'):
            o = opening(face, x, y, w, h, 2)
            if o is not None:
                return o
            edge = x in (0, w - 1) or y in (0, h - 1)
            if face == 'top' and edge:
                return gold_c(face, x, y, w, h, 0.56)                          # schmaler Goldwulst außen
            return ramp(PLATE, 0.62 if face == 'top' else 0.4)
        if y == 0:
            return gold_c(face, x, y, w, h, 0.6)
        c = ramp(PLATE, enamel_t(face, x, y, w, h, 0.7, 1))
        if face == 'front' and abs(x - (w - 1) / 2) < 0.6:
            return gold_c(face, x, 0, w, h, 0.52)                           # Spitze des Bogens läuft nach unten
        return c
    return paint


ICE_STAR = [
    '..i..',
    'i.i.i',
    '.iOi.',
    'i.i.i',
    '..i..',
]
# Außenseite der Schiene, 8 x 7 Texel, x = 0 hinten ... 7 vorn; die Mitte (3, 4) verdeckt der Grat.
WIND_LINES = [
    '........',
    'hw....wh',
    '..w..w..',
    '.w....w.',
    'w......w',
    'hw....wh',
    '........',
]


def scales_t(x, y, period=3, rows=3):
    """Drachenschuppen: Bögen in versetzten Reihen; liefert Helligkeitsversatz (unterer Schuppenrand dunkler)."""
    row = y // rows
    off = (period // 2) if row % 2 else 0
    u = (x + off) % period
    v = y % rows
    edge = v == rows - 1 or (v == rows - 2 and u in (0, period - 1))
    return -0.16 if edge else (0.07 if v == 0 and u == period // 2 else 0.0)


def brace():
    """Unterarmschiene: Drachenschuppen, vorn der Eiskristall, außen die Windböen, hinten eine feine Naht."""
    def paint(face, x, y, w, h):
        if face in ('top', 'bottom'):
            o = opening(face, x, y, w, h, 1)
            return o if o is not None else ramp(PLATE, 0.5)
        t = enamel_t(face, x, y, w, h, 0.68, 2) + scales_t(x, y)
        c = ramp(PLATE, t)
        if face == 'front':
            cx = (w - 1) // 2
            # Spitzbogen: goldene Linie, die von der Stulpe zur Mitte hinabläuft.
            if y == 0 and abs(x - cx) in (1, 2) or y == 1 and abs(x - cx) == 0:
                return gold_c(face, x, y, w, h, 0.5)
            sy, sx = y - 1, x - (cx - 2)
            if 0 <= sy < 5 and 0 <= sx < 5:
                ch = ICE_STAR[sy][sx]
                if ch == 'O':
                    return (ICE_HI, GLOW_ICE)
                if ch == 'i':
                    return (mix(c, ICE, 0.78), GLOW_ICE[:3] + (130,))
            return c
        if face == OUTER:
            ch = WIND_LINES[y][x] if y < len(WIND_LINES) and x < 8 else '.'
            if ch == 'w':
                return (mix(c, WIND, 0.95), GLOW_WIND)
            if ch == 'h':
                return (mix(c, WIND_HI, 0.6), GLOW_WIND[:3] + (80,))
            return c
        if face == 'back' and x == w // 2:
            return ramp(PLATE, t - 0.12)                                       # feine Naht hinten (kein Gold)
        if y == h - 1:
            return gold_c(face, x, y, w, h, 0.5)                              # Goldlippe über der Lamelle
        return c
    return paint


def ridge():
    """Schmaler Grat mitten auf der Außenseite: Perlweiß mit Goldkanten, läuft unten spitz aus."""
    def paint(face, x, y, w, h):
        if face in ('front', 'back', 'top', 'bottom'):
            return gold_c(face, x, y, w, h, 0.6)
        if y in (0, h - 1):
            return gold_c(face, x, y, w, h, 0.64)
        return ramp(PLATE, enamel_t(face, x, y, w, h, 0.72, 4) + (0.1 if x == 0 else 0))
    return paint


def rivet():
    """Runde Goldniete: heller Kern, dunkler Rand."""
    def paint(face, x, y, w, h):
        if face == OUTER:
            return ramp(GOLD, 0.82 if (x, y) == (1, 0) else 0.62 if y == 0 else 0.42)
        return gold_c(face, x, y, w, h, 0.45)
    return paint


def crest():
    """Kleine goldene Spitze, die den Spitzbogen der Stulpe nach oben fortsetzt."""
    def paint(face, x, y, w, h):
        return gold_c(face, x, y, w, h, 0.66 if face in ('front', OUTER, 'top') else 0.46)
    return paint


def hand_back():
    """Gewölbte Handrückenplatte (außen): Perlweiß mit Goldrand, Fassung der Sonnenscheibe."""
    def paint(face, x, y, w, h):
        if face == OUTER:
            if x in (0, w - 1) or y == h - 1:
                return gold_c(face, x, y, w, h, 0.52)
            return ramp(PLATE, enamel_t(face, x, y, w, h, 0.68, 12))
        if face in ('front', 'back', 'bottom'):
            return gold_c(face, x, y, w, h, 0.48)
        return ramp(PLATE, 0.55)
    return paint


def band():
    """Untere Lamelle über dem Handgelenk: Goldkante oben, Perlweiß darunter."""
    def paint(face, x, y, w, h):
        if face in ('top', 'bottom'):
            o = opening(face, x, y, w, h, 1)
            return o if o is not None else gold_c(face, x, y, w, h, 0.5)
        if y == h - 1:
            return gold_c(face, x, y, w, h, 0.56)                             # Goldkante am Lamellenrand
        return ramp(PLATE, enamel_t(face, x, y, w, h, 0.66, 5) + scales_t(x, y, 2, 2))
    return paint


def hand_plate():
    """Handrücken und Handballen: Perlweiß mit Goldsaum unten, Innenseite (Handfläche) dunkles Leder."""
    def paint(face, x, y, w, h):
        if face == INNER:
            return under_c(face, x, y, w, h, 0.68)
        if face == 'top':
            return ramp(PLATE, 0.5)
        if face == 'bottom':
            # Unterseite: außen Perlweiß (Fingerwurzeln), zur Handfläche hin Leder.
            if x >= w * 0.62:
                return under_c(face, x, y, w, h, 0.4)
            return ramp(PLATE, enamel_t(face, x, y, w, h, 0.5, 6))
        if y == h - 1 and face in (OUTER, 'front'):
            return gold_c(face, x, y, w, h, 0.5)                              # Goldsaum nur außen und vorn
        return ramp(PLATE, enamel_t(face, x, y, w, h, 0.68, 6))
    return paint


def sun_disc():
    """Yang-Sonne auf dem Handrücken (4 x 4 Texel): warmweißer Kern, goldener Ring; Leuchtmaske nur Kern und Ring."""
    def paint(face, x, y, w, h):
        if face != OUTER:
            return gold_c(face, x, y, w, h, 0.5)
        cx, cy = (w - 1) / 2, (h - 1) / 2
        r = math.hypot(x - cx, y - cy)
        if r < 0.5:
            return (ramp(SUN, 0.98), GLOW_SUN)                                 # warmweißer Kern
        if r < 1.2:
            return (ramp(SUN, 0.7 if x + y < w - 1 else 0.58), GLOW_SUN_RING)  # Strahlenkranz
        return ramp(GOLD, 0.4 if x + y < w else 0.3)                           # Ecken: Fassung
    return paint


def fist_block():
    """Eingerollte Finger: außen Fingerplatten, unten (Schlagfläche) vier Glieder, innen die Fingerspitzen."""
    def paint(face, x, y, w, h):
        if face == 'top':
            return under_c(face, x, y, w, h, 0.4)
        if face == 'bottom':
            seam = y % 2 == 1 and y < h - 1
            if x >= w - 3:                                                    # eingerollte Spitzen zur Handfläche
                return ramp(PLATE, 0.5) if not seam else under_c(face, x, y, w, h, 0.6)
            c = ramp(PLATE, enamel_t(face, x, y, w, h, 0.66, 7))
            return mix(c, ramp(UNDER, 0.75), 0.55) if seam else c
        if face == INNER:
            return ramp(PLATE, 0.5) if (x // 2) % 2 == 0 and y == h - 1 else under_c(face, x, y, w, h, 0.5)
        if face in ('front', 'back'):
            c = ramp(PLATE, enamel_t(face, x, y, w, h, 0.6, 7, gloss=False))
            return gold_c(face, x, y, w, h, 0.5) if y == h - 1 and x < w - 3 else c
        return ramp(PLATE, enamel_t(face, x, y, w, h, 0.62, 7))
    return paint


def knuckle_guard():
    """Knöchelschutz außen: vier gewölbte Platten (je 2 Texel), Goldfugen dazwischen."""
    def paint(face, x, y, w, h):
        if face == OUTER:
            if x % 2 == 1 and x < w - 1:
                return gold_c(face, x, y, w, h, 0.48)
            return ramp(PLATE, 0.86 if y == 0 else 0.66)
        if face == 'bottom':
            return ramp(PLATE, 0.5 if y % 2 else 0.6)
        return gold_c(face, x, y, w, h, 0.55)
    return paint


def thumb_fist():
    """Quer über die Faust gelegter Daumen: Perlweiß, Goldkappe an der Spitze (innen)."""
    def paint(face, x, y, w, h):
        if face == 'front':
            if x == w - 1:
                return gold_c(face, x, y, w, h, 0.6)
            if x == w // 2 - 1:
                return ramp(UNDER, 0.6)                                       # Gelenkfuge
            return ramp(PLATE, enamel_t(face, x, y, w, h, 0.66, 8))
        if face == INNER:
            return gold_c(face, x, y, w, h, 0.55)
        return ramp(PLATE, enamel_t(face, x, y, w, h, 0.56, 8, gloss=False))
    return paint


def phalanx(tip=False):
    """Fingerglied: Perlweiß, oben eine dunkle Gelenkfuge, unten (beim Endglied) eine Goldkante."""
    def paint(face, x, y, w, h):
        if face == 'top':
            return under_c(face, x, y, w, h, 0.45)
        if face == 'bottom':
            return gold_c(face, x, y, w, h, 0.62) if tip else ramp(PLATE, 0.45)
        if face == INNER:
            return under_c(face, x, y, w, h, 0.5) if not tip else ramp(PLATE, 0.5)
        if y == 0 and h > 1:
            return mix(ramp(PLATE, 0.5), ramp(UNDER, 0.5), 0.55)
        if tip and y == h - 1:
            return gold_c(face, x, y, w, h, 0.6)
        return ramp(PLATE, enamel_t(face, x, y, w, h, 0.66, 10, gloss=face != 'back'))
    return paint


def claw():
    """Spitze Fingerkappe: Gold eingefasst, Perlweiß mit Glanzpunkt."""
    def paint(face, x, y, w, h):
        if face in ('bottom', 'top'):
            return gold_c(face, x, y, w, h, 0.62)
        if face in ('front', 'back') and w > 1:
            return gold_c(face, x, y, w, h, 0.66) if x == w - 1 else ramp(PLATE, 0.86)
        return ramp(PLATE, 0.78) if face == OUTER else gold_c(face, x, y, w, h, 0.5)
    return paint


def fin(tip=False):
    """Drachenschwinge außen an der Schiene: Perlweiß, vorn eine Goldkante, innen eine leuchtende Eislinie."""
    def paint(face, x, y, w, h):
        if face == 'front' or (tip and face == 'top'):
            return gold_c(face, x, y, w, h, 0.66)
        if face == OUTER:
            c = ramp(PLATE, enamel_t(face, x, y, w, h, 0.74, 14))
            if x == 0:
                return gold_c(face, x, y, w, h, 0.6)
            if x == w - 1 and h > 1:
                return (mix(c, ICE, 0.7), GLOW_ICE[:3] + (150,))
            return c
        if face in ('top', 'bottom'):
            return gold_c(face, x, y, w, h, 0.5)
        return ramp(PLATE, enamel_t(face, x, y, w, h, 0.56, 14, gloss=False))
    return paint


def spike():
    """Kleiner Dorn (Knöchel, Ellbogen): Goldfuß, eisweiße Spitze."""
    def paint(face, x, y, w, h):
        if face in ('top', 'bottom'):
            return ramp(PLATE, 0.9)
        return gold_c(face, x, y, w, h, 0.62) if y == h - 1 else ramp(PLATE, 0.88 if face == OUTER else 0.7)
    return paint


def wrist_ice():
    """Eisband am Handgelenk: eisblau, leuchtet schwach (Magiemodus stärker über die Leuchtmaske)."""
    def paint(face, x, y, w, h):
        if face in ('top', 'bottom'):
            o = opening(face, x, y, w, h, 1)
            return o if o is not None else ramp(GOLD, 0.5)
        c = mix(ramp(PLATE, 0.7), ICE, 0.55 + 0.2 * ((x + y) % 2))
        return (c, GLOW_ICE[:3] + (110,))
    return paint


# ---------------------------------------------------------------------------------------------------------------------
# Zusammenbau
# ---------------------------------------------------------------------------------------------------------------------
FINGERS = (
    # z-Start, Gliedhöhen (Einheiten), Spitze bis y
    (-2.0, (1.0, 1.0), 12.5),     # Zeigefinger (vorn)
    (-1.0, (1.0, 1.0, 0.5), 13.0),  # Mittelfinger (längster, genau 3 über dem Armende)
    (0.0, (1.0, 1.0), 12.5),      # Ringfinger
    (1.0, (1.0, 0.5), 12.0),      # kleiner Finger (hinten)
)


def side(right):
    s = 'r' if right else 'l'

    def C(x, y, z, w, h, d, paint, dilate=0.0):
        if right:
            return HDCube(x, y, z, w, h, d, paint, dilate)
        return HDCube(-(x + w), y, z, w, h, d, mirrored(paint), dilate)

    # Seit 1.2.2 (Crix: „Modell ist kacke“): schlanker, kein Eimer mehr. Flache Stulpe, außen eine geschwungene
    # Drachenschwinge (stufig, Spitze über dem Ellbogen), Ellbogendorn, Eisband am Handgelenk, Knöcheldornen an der Faust.
    base = Part(f'{s}_base', cubes=[
        # Seit 1.4.2 (Crix: „das 3D-Modell ist kacke“): Stulpe schmaler, keine Hörner mehr über dem Ellbogen; die
        # Schwinge liegt flach an der Außenseite und schwingt nach hinten aus, die Schiene trägt weniger auf.
        C(-3.75, 2.5, -2.5, 5.0, 1.0, 5.0, flare()),                          # Stulpe: x -3,75..1,25
        C(-1.75, 2.0, -2.5, 1.0, 0.5, 0.5, crest()),                         # Spitzbogen vorn
        C(-4.35, 4.0, -1.25, 0.5, 3.0, 1.5, fin()),                          # Schwinge (am Grat)
        C(-4.35, 3.0, 0.25, 0.5, 3.0, 1.0, fin()),                           # Schwinge, nach hinten gezogen
        C(-4.35, 2.5, 1.25, 0.5, 2.5, 1.0, fin()),
        C(-4.35, 2.5, 2.25, 0.5, 1.5, 0.5, fin(tip=True)),                   # Schwingenspitze hinten am Ellbogen
        C(-2.0, 2.75, 2.5, 1.5, 1.0, 0.5, spike()),                         # Ellbogendorn (Fuß)
        C(-1.75, 3.0, 3.0, 1.0, 0.5, 0.5, spike()),                         # Ellbogendorn (Spitze)
        # Abstände zu Arm (±2) und Ärmel (±2,25) und untereinander ≥ 0,1, sonst flimmern Flächen (z-fighting, 1.6.2).
        C(-3.5, 3.5, -2.0, 4.5, 3.0, 4.0, brace(), 0.4),                     # Schiene: x -3.9..1.4, z ±2.4, y 3.1..6.9
        C(-4.3, 4.25, -0.5, 0.5, 2.5, 1.0, ridge()),                          # Grat außen
        C(-3.5, 7.0, -2.0, 4.5, 1.0, 4.0, band(), 0.5),                      # untere Lamelle, schmaler (Verjüngung)
        C(-3.25, 7.75, -1.75, 4.0, 0.5, 3.5, wrist_ice(), 0.65),              # Eisband am Handgelenk
        C(-3.0, 8.0, -2.0, 4.0, 2.0, 4.0, hand_plate(), 0.15),               # Handplatte: x -3.15..1.15, bis y 10.15
        C(-3.75, 8.75, -1.75, 0.5, 1.5, 3.5, hand_back()),                    # gewölbter Handrücken
        C(-4.25, 8.75, -0.75, 0.5, 1.5, 1.5, sun_disc()),                     # Yang-Sonne auf dem Handrücken
    ])
    fist = Part(f'{s}_fist', cubes=[
        # Seit 1.4.2 eine richtige Faust (zwei Einheiten hoch statt einer flachen Scheibe).
        C(-3.0, 10.0, -2.0, 4.0, 2.0, 4.0, fist_block(), 0.4),              # eingerollte Finger, y bis 12.2
        C(-4.0, 10.0, -2.0, 0.5, 1.5, 4.0, knuckle_guard()),         # Knöchelschutz außen
        C(-2.5, 10.5, -2.75, 3.0, 1.0, 0.5, thumb_fist()),                   # Daumen quer vor der Faust
        C(-4.4, 10.5, -1.75, 0.5, 0.5, 0.5, spike()),                       # Knöcheldornen (vier, außen)
        C(-4.4, 10.5, -0.75, 0.5, 0.5, 0.5, spike()),
        C(-4.4, 10.5, 0.25, 0.5, 0.5, 0.5, spike()),
        C(-4.4, 10.5, 1.25, 0.5, 0.5, 0.5, spike()),
    ])
    open_cubes = []
    for z0, segs, end in FINGERS:
        y = 10.0
        for k, hgt in enumerate(segs):
            shrink = -0.04 - 0.05 * k
            last = k == len(segs) - 1
            open_cubes.append(C(-2.0, y, z0, 1.5, hgt, 1.0, phalanx(tip=False if not last else False), shrink))
            y += hgt
        # spitze Kappe, schmaler als das Glied
        open_cubes.append(C(-1.75, y, z0 + 0.25, 1.0, end - y, 0.5, claw(), -0.02))
    open_cubes += [
        C(-2.0, 8.5, -3.1, 1.5, 1.5, 1.0, phalanx(), -0.02),                 # Daumenballen, ragt vorn heraus
        C(-1.75, 10.0, -3.5, 1.0, 1.0, 1.0, phalanx(), -0.05),               # Daumen, nach vorn-unten
        C(-1.75, 11.0, -3.75, 1.0, 0.5, 0.5, claw(), -0.02),                 # Daumenkappe
    ]
    open_ = Part(f'{s}_open', cubes=open_cubes)
    return [base, fist, open_]


def gauntlets():
    model = HDModel('gauntlets', 64, 64, side(True) + side(False))
    _check(model)
    return model


def _check(model):
    """Verträge prüfen: Finger höchstens 3 über dem Armende, Innenseite glatt (Überstand <= 0,5)."""
    for part in model.parts:
        right = part.name.startswith('r_')
        for c in part.cubes:
            lo_x, hi_x = c.x - c.dilate, c.x + c.w + c.dilate
            inner_over = hi_x - 1.0 if right else -1.0 - lo_x
            assert inner_over <= 0.5 + 1e-6, f'{part.name}: Innenseite steht {inner_over:.2f} über'
            assert c.y + c.h + c.dilate <= 13.0 + 1e-6, f'{part.name}: reicht bis y {c.y + c.h + c.dilate:.2f}'
            for v in (c.w, c.h, c.d):
                assert abs(v * RES - round(v * RES)) < 1e-6, f'{part.name}: Maß {v} nicht auf dem Texelraster'


# Vorschau: rechter Arm an der Spielerschulter (-5, 2, 0), linker bei (5, 2, 0).
_R = (0, 0, 0, -5, 2, 0)
_L = (0, 0, 0, 5, 2, 0)
_PUNCH_R = (-1.57, 0, 0, -5, 2, 0)
_CAST_R = (-1.4, 0.3, 0, -5, 2, 0)
_CAST_L = (-1.4, -0.3, 0, 5, 2, 0)
POSES = {
    'melee': {'r_base': _R, 'r_fist': _R, 'r_open': None, 'l_base': _L, 'l_fist': _L, 'l_open': None},
    'magic': {'r_base': _R, 'r_fist': None, 'r_open': _R, 'l_base': _L, 'l_fist': None, 'l_open': _L},
    'punch': {'r_base': _PUNCH_R, 'r_fist': _PUNCH_R, 'r_open': None, 'l_base': _L, 'l_fist': _L, 'l_open': None},
    'cast': {'r_base': _CAST_R, 'r_fist': None, 'r_open': _CAST_R, 'l_base': _CAST_L, 'l_fist': None, 'l_open': _CAST_L},
    # Nur der rechte Arm, für Detailansichten (--poses r_melee,r_magic).
    'r_melee': {'r_open': None, 'l_base': None, 'l_fist': None, 'l_open': None},
    'r_magic': {'r_fist': None, 'l_base': None, 'l_fist': None, 'l_open': None},
}
