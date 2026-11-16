"""REFERENZ (nicht in boss_models.py registriert): das alte vierbeinige Nivaris-Modell vor dem Umbau zum
Schlangendrachen (16.11.). Nur zum Nachschlagen von Posen und Malern; nichts importiert diese Datei.

Nivaris, die Alte vom Eirgletscher: uralte weiße Drachin (Abschnitt II/III).

Erkennungsmerkmale (alle im Schattenriss):
1. Langer, gerader Hals; am Hinterkopf eine Krone aus nach hinten gekämmten Eishörnern, die wie ein gebrochener
   Heiligenschein um den Kopf steht (ein Horn abgebrochen, eines fehlt).
2. Vierbeiner mit schweren Vorderläufen und hohen Schultern; die Schwingen sind eigene Glieder auf dem Rücken.
3. Zerfranste Membranen mit Löchern wie altes Segeltuch (Ausschnitt über die Textur, Cutout).
4. Rückenkamm aus Kristallplatten, langer Schwanz mit einem Fächer aus Eisklingen.

Material: weiße Schuppen mit grauen Altersrändern, Bauch perlmuttgrau, Membranen blassblau-grau, Eis für Hörner, Kamm
und Fächer. Augen: rechtes golden, linkes eisblau. In der Brust das Herzenserbe (goldener Kern mit blauem Rand) zwischen
zwei Brustschuppen.

Koordinaten: 1/16 Block vor dem Renderer-Faktor SCALE = 1,6 (also 10 Einheiten = 1 Block), +y nach unten, -z vorn,
Füße bei y = 24. Ursprung (x = z = 0) liegt unter der Rumpfmitte, dort steht die Entity.

Texturen (alle mit denselben UVs):
* nivaris.png              Farbe (Membranlöcher und Fransen sind transparent, Cutout)
* nivaris_glow.png         alle Leuchttexel (Augen, Hornspitzen, Kehle, Herzkern), Übersicht
* nivaris_glow_eyes.png    Augen + Hornspitzen (Renderer: eyeGlow, lead)
* nivaris_glow_heart.png   Kehle, Maul innen, Herzkern (Renderer: throatGlow)
* nivaris_resonance.png    weiße/blaue Linien in Membranen und Kamm (nur bei resonance = true)

`POSES` enthält jede Schlüsselpose der Kotlin-Animation (client/model/nivaris/NivarisModel.kt). Der Block zwischen
`// <keys>` und `// </keys>` dort wird mit `python tools/assets/nivaris_model.py --kotlin` aus genau diesen Posen
erzeugt. `python tools/assets/nivaris_model.py --anchors` schreibt docs/NIVARIS_ANCHORS.md.
"""
import math
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)

from bosskit import Cube as _BCube  # noqa: E402
from modelkit import Model, Part, hexc, mix, ramp, rgb, speckle  # noqa: E402
from texkit import Image, save  # noqa: E402

SCALE = 1.6          # Renderer-Faktor
U = 16 / SCALE       # Einheiten je Block (10)
GROUND = 24

# ---------------------------------------------------------------------------------------------------------------------
# Paletten
# ---------------------------------------------------------------------------------------------------------------------

SCALES = rgb('#5d636d', '#8a919c', '#b9bfc8', '#dde1e7', '#f3f5f8', '#ffffff')
AGE = rgb('#6a6f78', '#868b95', '#a3a8b1', '#bfc3ca')
PEARL = rgb('#6e6a78', '#958fa2', '#b9b3c6', '#d6d1e0', '#ece8f2', '#fbf9fd')
MEMB = rgb('#4a586c', '#6c7d93', '#8fa1b7', '#b2c2d4', '#d2dee9', '#e8f0f6')
ICE = rgb('#2f5578', '#5383ac', '#86b3d8', '#b9daf1', '#e2f3fd', '#ffffff')
CLAW = rgb('#2c2c33', '#4a4a52', '#76747a', '#a8a39a', '#d2cdc2')
MOUTH = rgb('#1a1420', '#2c2234', '#433248', '#5e4560')
GOLD_EYE = hexc('#ffd04a')
BLUE_EYE = hexc('#8fe0ff')
GOLD_CORE = rgb('#b86e10', '#f0a828', '#ffd45c', '#fff0b0', '#fffbe6')
BLUE_RIM = rgb('#1d4f8c', '#2f7fd0', '#5cb4ff', '#a4dcff')
LIGHT = {'top': 0.14, 'bottom': -0.24, 'front': 0.0, 'back': -0.08, 'left': -0.05, 'right': -0.05}
RESO_WHITE = (236, 246, 255)
RESO_BLUE = (120, 200, 255)


def _hash(*v):
    h = 2166136261
    for x in v:
        h = ((h ^ (int(x) & 0xffffffff)) * 16777619) & 0xffffffff
    return h / 0xffffffff


def Cube(x, y, z, w, h, d, paint, ch=None, reso=None):
    """Quader mit Leuchtkanal ('eyes' oder 'heart') und optionalem Resonanz-Maler."""
    c = _BCube(x, y, z, w, h, d, paint)
    c.ch = ch
    c.reso = reso
    return c


# ---------------------------------------------------------------------------------------------------------------------
# Maler
# ---------------------------------------------------------------------------------------------------------------------

def scales(base=0.6, seed=0, size=3, pal=SCALES, belly_face=None):
    """Weiße Schuppen als versetzte Bögen; der untere Bogenrand ist grau (Altersrand), alte Schuppen dunkler."""
    sx = size + 1
    sy = size

    def paint(face, x, y, w, h):
        if belly_face and face == belly_face:
            return pearl(0.55, seed)(face, x, y, w, h)
        row = y // sy
        shift = (row % 2) * (sx // 2)
        col = (x + shift) // sx
        fx = (x + shift) % sx
        fy = y % sy
        age = _hash(col, row, seed + 7)
        v = base + LIGHT[face] + (0.5 - y / max(h - 1, 1)) * 0.1
        v += (speckle(x, y, seed) - 0.5) * 0.04 + (_hash(col, row, seed) - 0.5) * 0.04
        v += (0.5 - fy / max(sy - 1, 1)) * 0.06
        edge = (fy == sy - 1 and 0 < fx < sx - 1) or (fy == sy - 2 and fx in (0, sx - 1))
        if edge:
            if age > 0.72:
                return ramp(AGE, 0.65 + (1.0 - age) * 1.0 + LIGHT[face])
            v -= 0.05
        return ramp(pal, v)
    return paint


def pearl(base=0.55, seed=0, stripes=True):
    """Bauch: perlmuttgrau, quer gebändert, mit leichtem Farbschimmer."""
    def paint(face, x, y, w, h):
        v = base + LIGHT[face] * 0.8 + (speckle(x, y, seed + 5) - 0.5) * 0.08
        band = (y % 3 == 2) if face in ('top', 'bottom') else (y % 3 == 2)
        if stripes and band:
            v -= 0.16
        c = ramp(PEARL, v)
        tint = speckle(x + 17, y + 3, seed + 11)
        hue = hexc('#c9d6f0') if tint > 0.5 else hexc('#ead2e6')
        return mix(c, hue, 0.12)
    return paint


def ice(base=0.58, seed=0, facet=True, tip_glow=0, edge=0.2):
    """Eis: kantig facettiert, heller Grat, dunkler Kern. tip_glow > 0: obere Reihen leuchten schwach."""
    def paint(face, x, y, w, h):
        v = base + LIGHT[face] * 1.1 + (0.5 - y / max(h - 1, 1)) * 0.18
        v += (speckle(x, y, seed + 21) - 0.5) * 0.12
        if facet and ((x + y + seed) % 5 == 0):
            v += 0.14
        if x == 0 or x == w - 1:
            v += edge
        c = ramp(ICE, v)
        if tip_glow and y <= 1:
            return (mix(c, hexc('#e8f6ff'), 0.6), (190, 230, 255, tip_glow))
        return c
    return paint


def claws(base=0.5, seed=0):
    def paint(face, x, y, w, h):
        v = base + LIGHT[face] + (y / max(h - 1, 1)) * 0.25 + (speckle(x, y, seed + 31) - 0.5) * 0.1
        return ramp(CLAW, v)
    return paint


def membrane(length, depth, root_low_x, kind, seed=0):
    """Schwingenhaut als dünne Platte (Front/Rückseite groß). x läuft entlang des Knochens, y vom Knochen weg (Hinterkante).

    kind: 'inner' (am Rumpf), 'mid', 'tip' (Dreieck zur Flügelspitze). Außerhalb der Umrisslinie und in den Löchern
    bleibt die Textur leer (Cutout). Gleiche Funktion liefert über `lines` die Resonanzlinien.
    """
    ribs = {'inner': [0.0, 1.0], 'mid': [0.0, 1.0], 'tip': [0.0, 0.42, 0.75, 1.0]}[kind]
    holes = []
    rnd = seed * 31 + 7
    count = {'inner': 3, 'mid': 4, 'tip': 2}[kind]
    for i in range(count):
        s = (i + 0.25 + 0.5 * _hash(rnd, i, 1)) / count * (0.8 if kind == 'tip' else 1.0)
        t = 0.3 + 0.45 * _hash(rnd, i, 2)
        r = 1.4 + 2.4 * _hash(rnd, i, 3)
        holes.append((s, t, r))

    def limit(s):
        """Tiefe der Haut (Anteil von depth) an der Stelle s (0 = Wurzel, 1 = Spitze)."""
        if kind == 'inner':
            d = 1.0 - 0.18 * s
        elif kind == 'mid':
            d = 0.92 - 0.05 * s
        else:
            d = max(0.06, 0.9 * (1 - s) ** 0.85)
        # Bögen zwischen den Rippen (alte Segeltuchkante) und Risse.
        for a, b in zip(ribs, ribs[1:]):
            if a <= s <= b:
                k = (s - a) / max(b - a, 1e-6)
                d -= 0.2 * math.sin(math.pi * k) ** 0.7 * (1.0 if kind != 'tip' else 0.7)
        # Kleinere Bögen (Segeltuchkante zwischen den Adern) und Risse.
        d -= 0.05 * abs(math.sin(s * math.pi * 3.5))
        tear = _hash(rnd, int(s * 19), 9)
        if tear > 0.78:
            d -= 0.14 + 0.2 * (tear - 0.78) * 4.5
        elif tear > 0.58:
            d -= 0.05
        return d

    def coords(face, x, y, w, h):
        if face == 'back':
            x = w - 1 - x
        s = (x + 0.5) / w if root_low_x else 1.0 - (x + 0.5) / w
        t = (y + 0.5) / h
        return s, t

    def inside(s, t):
        if t > limit(s):
            return False
        for hs, ht, hr in holes:
            dx = (s - hs) * length
            dy = (t - ht) * depth
            wob = 1 + 0.35 * math.sin(math.atan2(dy, dx) * 3 + hs * 10)
            if dx * dx + dy * dy < (hr * wob) ** 2:
                return False
        return True

    def vein(s, t):
        """Abstand zur nächsten Ader (in Einheiten): Adern laufen von der Knochenkante schräg nach hinten."""
        best = 99.0
        for i in range(6):
            s0 = (i + 0.3) / 6
            # Ader: von (s0, 0) nach (s0 - 0.18, 1)
            ss = s0 - 0.18 * t
            best = min(best, abs(s - ss) * length)
        return best

    def paint(face, x, y, w, h):
        if face not in ('front', 'back'):
            return None
        s, t = coords(face, x, y, w, h)
        if not inside(s, t):
            return None
        lim = limit(s)
        v = 0.6 + (speckle(x, y, seed + 41) - 0.5) * 0.12 - 0.2 * t
        if face == 'back':
            v -= 0.08
        if t < 0.06:
            v -= 0.15                      # dunkler Saum am Knochen
        if lim - t < 0.05:
            v -= 0.18                      # ausgefranster, dunklerer Rand
        if vein(s, t) < 0.7:
            v += 0.14                      # Adern heller (durchscheinend)
        for hs, ht, hr in holes:           # Lochränder dunkler
            dd = math.hypot((s - hs) * length, (t - ht) * depth)
            if dd < hr * 1.35 + 0.8:
                v -= 0.12
        if _hash(x, y, seed + 51) > 0.93:
            v -= 0.1
        return ramp(MEMB, v)

    def lines(face, x, y, w, h):
        if face not in ('front', 'back'):
            return None
        s, t = coords(face, x, y, w, h)
        if not inside(s, t):
            return None
        d = vein(s, t)
        if d < 0.6:
            return RESO_WHITE + (235,)
        if d < 1.2:
            return RESO_BLUE + (150,)
        if limit(s) - t < 0.04:
            return RESO_BLUE + (110,)
        return None

    return paint, lines


def crystal_lines(face, x, y, w, h):
    """Resonanzlinien in den Kammplatten: eine helle Mittelader, blauer Saum."""
    if face in ('left', 'right'):
        mid = (w - 1) / 2
        if abs(x - mid) < 0.6:
            return RESO_WHITE + (230,)
        if abs(x - mid) < 1.6 or y == 0:
            return RESO_BLUE + (140,)
    if face in ('front', 'back') and y <= 1:
        return RESO_BLUE + (120,)
    return None


def spine_lines(face, x, y, w, h):
    """Resonanz: feine Linie auf dem Rücken entlang des Kamms."""
    if face == 'top' and abs(x - (w - 1) / 2) < 0.6:
        return RESO_BLUE + (130,)
    return None


def heart_core(face, x, y, w, h):
    """Herzenserbe: goldener Kern, nach außen blau."""
    cx, cy = (w - 1) / 2, (h - 1) / 2
    r = math.hypot((x - cx) / max(cx, 0.5), (y - cy) / max(cy, 0.5))
    if face not in ('front',):
        return (ramp(BLUE_RIM, 0.5), (90, 170, 255, 200))
    if r < 0.45:
        return (ramp(GOLD_CORE, 0.95 - r), (255, 220, 120, 255))
    if r < 0.8:
        return (ramp(GOLD_CORE, 0.6 - (r - 0.45)), (255, 190, 80, 240))
    return (ramp(BLUE_RIM, 0.7), (110, 190, 255, 230))


def chest_paint(seed):
    """Rippenkorb: Schuppen, vorn unten der blaue Rand des Herzkerns zwischen den Brustplatten."""
    base = scales(0.62, seed, 3)
    belly = pearl(0.55, seed)

    def paint(face, x, y, w, h):
        if face == 'bottom':
            return belly(face, x, y, w, h)
        if face == 'front':
            cx = (w - 1) / 2
            # Schlitz zwischen den Brustplatten: blauer Rand, innen golden.
            if abs(x - cx) < 3.2 and h * 0.5 <= y < h * 0.9:
                dx = abs(x - cx)
                if dx < 1.4:
                    return (ramp(GOLD_CORE, 0.75), (255, 200, 90, 230))
                return (ramp(BLUE_RIM, 0.6), (90, 175, 255, 210))
            if y >= h * 0.45:
                return belly(face, x, y, w, h)
        return base(face, x, y, w, h)
    return paint


def neck_paint(seed, throat=True):
    """Hals: Schuppen oben, perlmuttgraue Kehle unten mit leuchtender Naht (Kehle/Atemholen)."""
    base = scales(0.64, seed, 3)
    belly = pearl(0.56, seed)

    def paint(face, x, y, w, h):
        if face == 'bottom':
            c = belly(face, x, y, w, h)
            if throat and abs(x - (w - 1) / 2) < 1.1:
                return (mix(c, hexc('#ffe7a0'), 0.35), (255, 205, 110, 200))
            return c
        if face in ('left', 'right') and y >= h - 2:
            return belly(face, x, y, w, h)
        return base(face, x, y, w, h)
    return paint


def skull_paint(face, x, y, w, h):
    """Schädel mit Augen: rechtes Auge (x klein, -x) golden, linkes eisblau; dunkler Brauenschatten darüber."""
    base = scales(0.7, 61, 2)
    eye_rows = (h - 5, h - 4)
    if face in ('right', 'left'):
        # i läuft auf 'right' von hinten nach vorn, auf 'left' von vorn nach hinten.
        front = x >= w - 3 if face == 'right' else x <= 2
        nearer = x == w - 2 if face == 'right' else x == 1
        if front and y in eye_rows:
            col = GOLD_EYE if face == 'right' else BLUE_EYE
            if nearer or y == eye_rows[0]:
                return (col, col[:3] + (255,))
            return (mix(col, hexc('#ffffff'), 0.3), col[:3] + (255,))
        if front and y == eye_rows[0] - 1:
            return ramp(AGE, 0.2)
    if face == 'front':
        if y in eye_rows and (x == 0 or x == w - 1):
            col = GOLD_EYE if x == 0 else BLUE_EYE
            return (col, col[:3] + (255,))
    if face == 'bottom':
        return pearl(0.52, 62)(face, x, y, w, h)
    return base(face, x, y, w, h)


def snout_paint(face, x, y, w, h):
    base = scales(0.72, 63, 2)
    if face == 'front' and y == 1 and x in (1, w - 2):
        return hexc('#2a2a32')               # Nüstern
    if face in ('left', 'right', 'front') and y == h - 1:
        if x % 2 == 0:
            return ramp(CLAW, 0.95)          # obere Zahnreihe
        return ramp(MOUTH, 0.4)
    if face == 'bottom':
        return (ramp(MOUTH, 0.35), (120, 190, 255, 60))
    return base(face, x, y, w, h)


def jaw_paint(face, x, y, w, h):
    base = scales(0.6, 64, 2)
    if face == 'top':
        # Maul innen: dunkel, Zunge, die Kehle leuchtet beim Atemholen von innen.
        cx = (w - 1) / 2
        if abs(x - cx) < 1.2:
            return (ramp(MOUTH, 0.7), (150, 210, 255, 170))
        if x == 0 or x == w - 1:
            return ramp(CLAW, 0.95) if y % 2 == 0 else ramp(MOUTH, 0.3)
        return (ramp(MOUTH, 0.4), (110, 180, 255, 90))
    if face in ('left', 'right', 'front') and y == 0:
        return ramp(CLAW, 0.9) if x % 2 == 1 else ramp(MOUTH, 0.4)
    if face == 'bottom':
        return pearl(0.5, 65)(face, x, y, w, h)
    return base(face, x, y, w, h)


# ---------------------------------------------------------------------------------------------------------------------
# Formhelfer
# ---------------------------------------------------------------------------------------------------------------------

def horn(name, pivot, rot, segs, seed, broken=False):
    """Eishorn aus Gliedern, wächst entlang -y; jedes Glied biegt um x (nach hinten). Spitze schwach leuchtend."""
    root = cur = None
    prev = 0
    for i, (w, l, bend) in enumerate(segs):
        last = i == len(segs) - 1
        paint = ice(0.6 - i * 0.04, seed + i, tip_glow=(0 if broken else 95) if last else 0)
        c = Cube(-w / 2, -l, -w / 2, w, l, w, paint, ch='eyes' if last and not broken else None)
        if cur is None:
            cur = root = Part(name, pivot, rot, cubes=[c])
        else:
            nxt = Part(f'{name}_{i}', (0, -prev, 0), (bend, 0, 0), cubes=[c])
            cur.add(nxt)
            cur = nxt
        prev = l
    return root


def plate(name, pivot, h, d, tilt, seed, thick=2):
    """Kristallplatte des Rückenkamms: dünn in x, hoch, nach hinten geneigt."""
    return Part(name, pivot, (-tilt, 0, 0), cubes=[
        Cube(-thick / 2, -h, -d / 2, thick, h, d, ice(0.62, seed, edge=0.25), reso=crystal_lines),
        Cube(-thick / 2 + 0.5, -h - 2, -d / 4, max(1, thick - 1), 2, max(2, d // 2), ice(0.75, seed + 1), reso=crystal_lines),
    ])


def blade(name, pivot, rot, length, width, seed):
    """Eisklinge des Schwanzfächers: flach (dünn in x), wächst entlang +z, Spitze schmal."""
    return Part(name, pivot, rot, cubes=[
        Cube(-1, -width / 4, 0, 2, width / 2, length * 0.3, ice(0.55, seed, edge=0.3), reso=crystal_lines),
        Cube(-0.5, -width / 2, length * 0.3, 1, width, length * 0.35, ice(0.64, seed + 1, edge=0.3), reso=crystal_lines),
        Cube(-0.5, -width / 2 + 1.5, length * 0.65, 1, max(2, width - 3), length * 0.2, ice(0.72, seed + 2, edge=0.3), reso=crystal_lines),
        Cube(-0.5, -width / 2 + 3, length * 0.85, 1, max(1, width - 6), length * 0.15, ice(0.85, seed + 3, edge=0.3), reso=crystal_lines),
    ])


# ---------------------------------------------------------------------------------------------------------------------
# Glieder
# ---------------------------------------------------------------------------------------------------------------------

ARM_L1, ARM_L2, HAND_SOLE = 14, 11, 3
LEG_L1, LEG_L2, LEG_L3, TOE_SOLE = 11, 9, 7, 2
WING_L = (22, 28, 30)
NECK_L = 8
TAIL_L = 10


def foreleg(side):
    s = -1 if side == 'r' else 1
    hand = Part(f'hand_{side}', (0, ARM_L2, 0), cubes=[
        Cube(-4, 0, -6, 8, HAND_SOLE, 10, scales(0.55, 70, 2)),
    ], children=[
        Part(f'claws_{side}', (0, 0.5, -6), cubes=[
            Cube(-4, 0, -3, 2, 2.5, 3, claws(0.6, 71)),
            Cube(-1, 0, -4, 2, 2.5, 4, claws(0.6, 72)),
            Cube(2, 0, -3, 2, 2.5, 3, claws(0.6, 73)),
        ]),
    ])
    fore = Part(f'fore_{side}', (0, ARM_L1, 0), cubes=[
        Cube(-3, 0, -3.5, 6, ARM_L2, 7, scales(0.6, 74, 3)),
        Cube(-3.5, -1.5, 1.5, 7, 5, 3, scales(0.55, 75, 2)),     # Ellbogen
    ], children=[hand])
    return Part(f'arm_{side}', (s * 10, -3, -15), cubes=[
        Cube(-4.5, -5, -5, 9, ARM_L1 + 5, 10, scales(0.62, 76, 3)),
    ], children=[fore])


def hindleg(side):
    s = -1 if side == 'r' else 1
    toes = Part(f'toes_{side}', (0, LEG_L3, 0), (math.degrees(-META_TILT), 0, 0), cubes=[
        Cube(-3, 0, -6, 6, TOE_SOLE, 8, scales(0.55, 80, 2)),
        Cube(-3, 0.5, -8, 2, 1.5, 2, claws(0.6, 81)),
        Cube(-0.5, 0.5, -9, 1.5, 1.5, 3, claws(0.6, 82)),
        Cube(1.5, 0.5, -8, 2, 1.5, 2, claws(0.6, 83)),
    ])
    foot = Part(f'foot_{side}', (0, LEG_L2, 0), cubes=[
        Cube(-2, 0, -2, 4, LEG_L3, 4, scales(0.6, 84, 2)),
    ], children=[toes])
    shin = Part(f'shin_{side}', (0, LEG_L1, 0), cubes=[
        Cube(-2.5, -1, -2.5, 5, LEG_L2 + 1, 5, scales(0.6, 85, 3)),
    ], children=[foot])
    return Part(f'thigh_{side}', (s * 9, -2, 14), cubes=[
        Cube(-4, -4, -5, 8, LEG_L1 + 4, 10, scales(0.62, 86, 3)),
    ], children=[shin])


def membrane_part(name, length, depth, side, kind, seed, z0=0.5):
    """Hautplatte am Knochen: liegt in der x-z-Ebene des Knochens, Hinterkante bei +z."""
    s = -1 if side == 'r' else 1
    paint, lines = membrane(length, depth, root_low_x=(side == 'l'), kind=kind, seed=seed)
    x0 = -length if side == 'r' else 0
    # Platte w x depth x 1 in der x-y-Ebene, um +90° gekippt: lokales +y zeigt danach nach +z (hinten).
    return Part(name, (0, 0, z0), (90, 0, 0), cubes=[Cube(x0, 0, -0.5, length, depth, 1, paint, reso=lines)])


def wing(side):
    s = -1 if side == 'r' else 1
    l1, l2, l3 = WING_L

    def bone(x_len, thick, seed, taper=None):
        x0 = -x_len if side == 'r' else 0
        cubes = [Cube(x0, -thick / 2, -thick / 2, x_len, thick, thick, scales(0.66, seed, 2))]
        return cubes

    spars = []
    for i, (ang, ln) in enumerate(((24, 30), (48, 27))):
        spars.append(Part(f'spar_{side}_{i}', (0, 0, 0.5), (0, s * -ang if side == 'l' else ang, 0), cubes=[
            Cube(-ln if side == 'r' else 0, -0.5, -0.5, ln, 1, 1, ice(0.45, 90 + i, facet=False, edge=0)),
        ]))
    hand = Part(f'wing_hand_{side}', (s * l2, 0, 0), cubes=[
        Cube(-l3 * 0.6 if side == 'r' else 0, -1, -1, l3 * 0.6, 2, 2, scales(0.68, 91, 2)),
        Cube(-l3 if side == 'r' else l3 * 0.6, -0.5, -0.5, l3 * 0.4, 1, 1, claws(0.7, 92)),
    ], children=[
        membrane_part(f'memb_c_{side}', l3, 30, side, 'tip', 3 if side == 'r' else 4),
        Part(f'thumb_{side}', (0, -1, -1), (-30, 0, 0), cubes=[Cube(-1, -1, -4, 2, 2, 4, claws(0.6, 93))]),
    ] + spars)
    fore = Part(f'wing_fore_{side}', (s * l1, 0, 0), cubes=bone(l2, 3, 94), children=[
        membrane_part(f'memb_b_{side}', l2, 30, side, 'mid', 2 if side == 'r' else 5),
        hand,
    ])
    return Part(f'wing_{side}', (s * 8, -11, -11), cubes=bone(l1, 4, 95), children=[
        membrane_part(f'memb_a_{side}', l1, 28, side, 'inner', 1 if side == 'r' else 6),
        fore,
    ])


def head_part():
    # Krone: Hörner im Halbkreis um den Hinterkopf (Winkel 0 = senkrecht nach oben), nach hinten gekämmt.
    halo = [(-102, 17), (-70, 22), (-38, 25), (-8, 6, True), (24, 25), (56, 22), (90, 18), (122, 10, True)]
    horns = []
    for i, spec in enumerate(halo):
        ang, ln = spec[:2]
        broken = len(spec) > 2
        a = math.radians(ang)
        pivot = (math.sin(a) * 5.0, -math.cos(a) * 5.0, 0)
        n = 4 if ln > 12 else 2
        segs = [(3 if k == 0 else 2 if k < n - 1 else 1, ln / n, 0 if k == 0 else -13) for k in range(n)]
        horns.append(horn(f'horn_{i}', pivot, (-38, 0, ang), segs, 100 + i * 3, broken=broken))
    crown = Part('crown', (0, -2.5, 0.5), cubes=[
        Cube(-5, -1.5, -1.5, 10, 3, 3, ice(0.5, 120)),
    ], children=horns)
    jaw = Part('jaw', (0, 1.5, -6), cubes=[
        Cube(-3, 0, -10, 6, 2, 11, jaw_paint, ch='heart'),
        Cube(-4, 0, -3, 8, 3, 5, scales(0.58, 66, 2)),
    ])
    return Part('head', (0, 0, -NECK_L), cubes=[
        Cube(-5, -5, -9, 10, 7, 10, skull_paint, ch='eyes'),
        Cube(-3.5, -3, -17, 7, 4, 8, snout_paint, ch='heart'),
        Cube(-6, -6, -9, 3, 2, 5, scales(0.55, 67, 2)),        # Brauenwulst rechts
        Cube(3, -6, -9, 3, 2, 5, scales(0.55, 68, 2)),         # Brauenwulst links
        Cube(-2, -4, -16, 4, 2, 6, scales(0.74, 69, 2)),       # Nasenrücken
    ], children=[jaw, crown])


def neck_parts():
    sizes = [10, 9, 8, 7]
    parts = []
    for i, sz in enumerate(sizes):
        cubes = [Cube(-sz / 2, -sz / 2, -NECK_L - 1, sz, sz, NECK_L + 2, neck_paint(200 + i), ch='heart')]
        kids = [plate(f'neck_plate_{i}', (0, -sz / 2 + 0.5, -NECK_L / 2), 3 + (3 - i) // 2, 4, 30, 210 + i)]
        parts.append(Part(f'neck_{i}', (0, 0, -NECK_L) if i else (0, -7, -23), cubes=cubes, children=kids))
    for a, b in zip(parts, parts[1:]):
        a.add(b)
    parts[-1].add(head_part())
    return parts[0]


def tail_parts():
    sizes = [(10, 9), (9, 8), (8, 7), (7, 6), (5, 5), (4, 4)]
    parts = []
    for i, (w, h) in enumerate(sizes):
        kids = []
        if i < 5:
            kids.append(plate(f'tail_plate_{i}', (0, -h / 2 + 0.5, TAIL_L / 2), 5 - i * 0.7, 5, 40, 300 + i))
        parts.append(Part(f'tail_{i}', (0, 0, TAIL_L) if i else (0, -4, 19), cubes=[
            Cube(-w / 2, -h / 2, -1, w, h, TAIL_L + 2, scales(0.62, 310 + i, 3, belly_face='bottom'), reso=spine_lines),
        ], children=kids))
    for a, b in zip(parts, parts[1:]):
        a.add(b)
    # Fächer aus Eisklingen: fünf Klingen im Halbkreis (hinten, nach oben und zur Seite), eine Mittelklinge am längsten.
    blades = []
    for i, (pitch, yaw, ln) in enumerate(((-38, -18, 15), (-8, 14, 21), (22, -4, 27), (52, 14, 21), (82, -18, 15))):
        blades.append(blade(f'fan_blade_{i}', (0, -0.5, 1), (pitch, yaw, 0), ln, 6 if i == 2 else 5, 330 + i))
    fan = Part('fan', (0, 0, TAIL_L), cubes=[Cube(-2, -2, -1, 4, 4, 3, ice(0.5, 340))], children=blades)
    parts[-1].add(fan)
    return parts[0]


def nivaris():
    crest = [plate(f'crest_{i}', (0, -10 + i * 0.5, -19 + i * 6), 9 - i, 6, 32, 400 + i) for i in range(4)]
    chest = Part('chest', (0, -2, 2), cubes=[
        Cube(-11, -10, -24, 22, 20, 25, chest_paint(500), ch='heart', reso=spine_lines),
        Cube(-12, -14, -22, 7, 10, 14, scales(0.66, 501, 3)),     # Schulter rechts (hoch)
        Cube(5, -14, -22, 7, 10, 14, scales(0.66, 502, 3)),       # Schulter links
        Cube(-10, -1, -25, 7, 10, 1, pearl(0.62, 503)),           # Brustschuppe rechts
        Cube(3, -1, -25, 7, 10, 1, pearl(0.62, 504)),             # Brustschuppe links
        Cube(-2, 1, -25.5, 4, 6, 1, heart_core, ch='heart'),      # Herzenserbe
    ], children=[neck_parts(), wing('r'), wing('l'), foreleg('r'), foreleg('l')] + crest)
    hips_crest = [plate(f'hip_crest_{i}', (0, -6.5, 5 + i * 7), 7 - i, 6, 36, 420 + i) for i in range(2)]
    body = Part('body', (0, 4, 0), cubes=[
        Cube(-9, -7, -2, 18, 13, 22, scales(0.62, 510, 3, belly_face='bottom'), reso=spine_lines),
        Cube(-7, 6, 0, 14, 2, 18, pearl(0.55, 511)),
    ], children=[chest, hindleg('r'), hindleg('l'), tail_parts()] + hips_crest)
    return Model('nivaris', 512, 256, [body])


# ---------------------------------------------------------------------------------------------------------------------
# Zusatz-Texturen (gleiche UVs): getrennte Leuchtmasken für Augen/Herz und die Resonanzlinien
# ---------------------------------------------------------------------------------------------------------------------

def _faces(c):
    u, v, w, h, d = c.u, c.v, c.w, c.h, c.d
    return [
        ('top', u + d, v, w, d), ('bottom', u + d + w, v, w, d), ('right', u, v + d, d, h),
        ('front', u + d, v + d, w, h), ('left', u + d + w, v + d, d, h), ('back', u + d + w + d, v + d, w, h),
    ]


def variants(model, albedo, glow):
    eyes = Image(model.tex_w, model.tex_h)
    heart = Image(model.tex_w, model.tex_h)
    reso = Image(model.tex_w, model.tex_h)
    for c in model.cubes():
        for name, fu, fv, fw, fh in _faces(c):
            for iy in range(fh):
                for ix in range(fw):
                    if c.ch:
                        res = c.paint(name, ix, iy, fw, fh)
                        if res is not None and isinstance(res[0], tuple) and res[1] is not None:
                            (eyes if c.ch == 'eyes' else heart).set(fu + ix, fv + iy, res[1])
                    if c.reso:
                        r = c.reso(name, ix, iy, fw, fh)
                        if r is not None:
                            reso.set(fu + ix, fv + iy, r)
    save(eyes, 'textures/entity/nivaris_glow_eyes.png')
    save(heart, 'textures/entity/nivaris_glow_heart.png')
    save(reso, 'textures/entity/nivaris_resonance.png')


# ---------------------------------------------------------------------------------------------------------------------
# Posen
# ---------------------------------------------------------------------------------------------------------------------

# Knochen, die die Animation bewegt (Reihenfolge = Kotlin-Kanäle). Alle haben Grunddrehung 0.
BONES = ['body', 'chest',
         'neck_0', 'neck_1', 'neck_2', 'neck_3', 'head', 'jaw', 'crown',
         'wing_r', 'wing_fore_r', 'wing_hand_r', 'wing_l', 'wing_fore_l', 'wing_hand_l',
         'arm_r', 'fore_r', 'hand_r', 'arm_l', 'fore_l', 'hand_l',
         'thigh_r', 'shin_r', 'foot_r', 'thigh_l', 'shin_l', 'foot_l',
         'tail_0', 'tail_1', 'tail_2', 'tail_3', 'tail_4', 'tail_5', 'fan']

from preview_model import apply as _apply, mat_mul as _mul, rot_zyx as _rot  # noqa: E402

_MODEL = None
IDENT = [[1, 0, 0], [0, 1, 0], [0, 0, 1]]


def _model():
    global _MODEL
    if _MODEL is None:
        _MODEL = nivaris()
    return _MODEL


def frames(pose):
    """Vorwärtskinematik wie preview.collect: Name -> (Drehung, Position, Part) im Modellraum."""
    out = {}

    def walk(part, rot, pos):
        value = pose.get(part.name, ...)
        pivot = part.pivot
        if value is ... or value is None:
            r = tuple(math.radians(a) for a in part.rot)
        else:
            r = tuple(value[:3])
            if len(value) >= 6:
                pivot = (pivot[0] + value[3], pivot[1] + value[4], pivot[2] + value[5])
        o = _apply(rot, pivot)
        p = (pos[0] + o[0], pos[1] + o[1], pos[2] + o[2])
        m = _mul(rot, _rot(*r))
        out[part.name] = (m, p, part)
        for ch in part.children:
            walk(ch, m, p)
    for top in _model().parts:
        walk(top, IDENT, (0, 0, 0))
    return out


def point(fr, name, local):
    m, p, _ = fr[name]
    w = _apply(m, local)
    return (w[0] + p[0], w[1] + p[1], w[2] + p[2])


def _t(m):
    return [[m[j][i] for j in range(3)] for i in range(3)]


def lowest(fr, names, deep=True):
    """Tiefster Punkt (größtes y) aller Quader der genannten Knochen (deep: samt Kindern)."""
    best = -1e9
    for name in names:
        stack = [fr[name][2]]
        while stack:
            part = stack.pop()
            m, p, _ = fr[part.name]
            for c in part.cubes:
                for x in (c.x, c.x + c.w):
                    for y in (c.y, c.y + c.h):
                        for z in (c.z, c.z + c.d):
                            w = _apply(m, (x, y, z))
                            best = max(best, w[1] + p[1])
            if deep:
                stack.extend(part.children)
    return best


def _pitch_of(m):
    """Nickwinkel einer Drehung um die x-Achse (für Knochen, die nur nicken)."""
    return math.atan2(m[2][1], m[1][1])


def solve_leg(pose, upper, lower, l1, l2, target, bend_sign, end=None):
    """Zwei-Glied-IK in der y-z-Ebene des Elternknochens: setzt die Nickwinkel von upper und lower so, dass das Ende
    von lower (bzw. der Ankerpunkt end im lower-Raum) auf target (Modellraum) liegt. bend_sign: +1 Knie nach vorn
    (Hinterlauf), -1 Ellbogen nach hinten (Vorderlauf)."""
    fr = frames(pose)
    parent_m, _, _ = fr[_parent()[upper]]
    _, up_p, _ = fr[upper]
    rel = _apply(_t(parent_m), (target[0] - up_p[0], target[1] - up_p[1], target[2] - up_p[2]))
    Y, Z = rel[1], rel[2]
    d = min(math.hypot(Y, Z), l1 + l2 - 0.01)
    cb = (d * d - l1 * l1 - l2 * l2) / (2 * l1 * l2)
    b = math.acos(max(-1.0, min(1.0, cb))) * bend_sign
    theta = math.atan2(Z, Y)
    a = theta - math.atan2(l2 * math.sin(b), l1 + l2 * math.cos(b))
    old_u = pose.get(upper, (0, 0, 0))
    old_l = pose.get(lower, (0, 0, 0))
    pose[upper] = (a, old_u[1], old_u[2]) + tuple(old_u[3:6])
    pose[lower] = (b, old_l[1], old_l[2]) + tuple(old_l[3:6])


_PARENT = {}


def _parent():
    if not _PARENT:
        def walk(part, parent):
            _PARENT[part.name] = parent
            for ch in part.children:
                walk(ch, part.name)
        for p in _model().parts:
            walk(p, None)
    return _PARENT


# Standfußpunkte (Modellraum, Sohle am Boden) in der Grundhaltung.
STANCE = {'r_front': (-10, -17), 'l_front': (10, -17), 'r_hind': (-9, 14), 'l_hind': (9, 14)}


def plant_fore(pose, side, x, z, lift=0.0, sole_pitch=0.0):
    """Vorderlauf: Handwurzel über (x, z), Sohle flach (bzw. sole_pitch), lift hebt den Fuß."""
    target = (x, GROUND - HAND_SOLE - lift, z)
    solve_leg(pose, f'arm_{side}', f'fore_{side}', ARM_L1, ARM_L2, target, -1)
    fr = frames(pose)
    m = fr[f'fore_{side}'][0]
    pose[f'hand_{side}'] = (-_pitch_of(m) + sole_pitch, 0, 0)


META_TILT = -0.42   # Mittelfuß gegen die Senkrechte (negativ = Zehen vorn, Ferse hoch)


def plant_hind(pose, side, x, z, lift=0.0, meta=META_TILT):
    """Hinterlauf (Zehengänger): Zehen auf (x, z); der Mittelfuß steht schräg (meta), Oberschenkel/Schienbein per IK."""
    # Sprunggelenk = Zehenansatz minus Mittelfuß-Vektor (Weltneigung meta).
    ankle_to_toe = (0, LEG_L3 * math.cos(meta), LEG_L3 * math.sin(meta))
    toe_base = (x, GROUND - TOE_SOLE - lift, z + 1)
    ankle = (toe_base[0], toe_base[1] - ankle_to_toe[1], toe_base[2] - ankle_to_toe[2])
    solve_leg(pose, f'thigh_{side}', f'shin_{side}', LEG_L1, LEG_L2, ankle, +1)
    fr = frames(pose)
    m = fr[f'shin_{side}'][0]
    pose[f'foot_{side}'] = (meta - _pitch_of(m), 0, 0)


def R(*deg):
    """Grad -> Radiant (pitch, yaw, roll[, dx, dy, dz])."""
    if len(deg) <= 3:
        return tuple(math.radians(a) for a in deg)
    return tuple(math.radians(a) for a in deg[:3]) + tuple(deg[3:])


def _norm(v):
    n = math.sqrt(sum(c * c for c in v)) or 1.0
    return tuple(c / n for c in v)


def _cross(a, b):
    return (a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])


def _dot(a, b):
    return sum(x * y for x, y in zip(a, b))


def euler(m):
    """Zerlegt eine Drehmatrix in (pitch, yaw, roll) für die Reihenfolge Z * Y * X (wie ModelPart)."""
    yaw = math.asin(max(-1.0, min(1.0, -m[2][0])))
    pitch = math.atan2(m[2][1], m[2][2])
    roll = math.atan2(m[1][0], m[0][0])
    return (pitch, yaw, roll)


def wing_frames(spec, side):
    """spec: drei Paare (Knochenrichtung, Hautrichtung) im Brustraum für die RECHTE Schwinge (x < 0 = außen rechts).
    Liefert die lokalen Euler-Winkel von Humerus, Unterarm und Hand der Schwinge side."""
    out = []
    parent = IDENT
    for bone_dir, memb_dir in spec:
        if side == 'l':
            bone_dir = (-bone_dir[0], bone_dir[1], bone_dir[2])
            memb_dir = (-memb_dir[0], memb_dir[1], memb_dir[2])
        d = _norm(bone_dir)
        X = d if side == 'l' else tuple(-c for c in d)
        mz = _norm(memb_dir)
        Z = _norm(tuple(mz[i] - _dot(mz, X) * X[i] for i in range(3)))
        Y = _cross(Z, X)
        M = [[X[0], Y[0], Z[0]], [X[1], Y[1], Z[1]], [X[2], Y[2], Z[2]]]
        local = _mul(_t(parent), M)
        out.append(euler(local))
        parent = M
    return out


def wings_sym(spec):
    """Beide Schwingen spiegelbildlich aus einer Richtungs-Beschreibung der rechten Schwinge (siehe wing_frames)."""
    out = {}
    for side in ('r', 'l'):
        names = [f'wing_{side}', f'wing_fore_{side}', f'wing_hand_{side}']
        for n, e in zip(names, wing_frames(spec, side)):
            out[n] = e
    return out


def neck(*pitches, yaw=0.0, roll=0.0):
    """Halsglieder neck_0..3 und Kopf (pitch in Grad; yaw/roll je Glied gleich verteilt)."""
    names = ['neck_0', 'neck_1', 'neck_2', 'neck_3', 'head']
    out = {}
    for i, n in enumerate(names):
        p = pitches[i] if i < len(pitches) else 0.0
        out[n] = R(p, yaw if n != 'head' else yaw * 0.5, roll)
    return out


def tail(*pitch_yaw):
    out = {}
    for i, (p, y) in enumerate(pitch_yaw):
        out[f'tail_{i}'] = R(p, y, 0)
    return out


# Flügel-Haltungen der rechten Schwinge im Brustraum: je Glied (Knochenrichtung, Richtung der Haut-Hinterkante).
# x < 0 = nach außen (rechts), y > 0 = nach unten, z > 0 = nach hinten.
DOWN, BACK = (0, 1, 0), (0, 0, 1)
# Angelegt wie ein zerschlissener Umhang: Ellbogen hoch über der Schulter, Handgelenk vorn an der Schulter, die
# Fingerknochen laufen die Flanke entlang nach hinten, die Haut hängt außen an der Flanke herab.
WING_FOLD = (((-0.32, -0.62, 0.72), (-0.45, 1, 0.1)),
             ((0.06, 0.42, -0.9), (-0.3, 1, 0.15)),
             ((-0.12, 0.14, 1), (-0.25, 1, 0)))
WING_TIGHT = (((-0.18, -0.5, 0.85), (-0.3, 1, 0.1)),
              ((0.05, 0.4, -0.92), (-0.2, 1, 0.1)),
              ((-0.06, 0.1, 1), (-0.15, 1, 0)))
WING_HALF = (((-0.8, -0.55, 0.3), (-0.2, 1, 0.6)),
             ((-0.5, 0.35, -0.8), (-0.2, 0.6, 1)),
             ((-0.4, 0.3, 0.85), (-0.3, 0.6, 0.2)))
WING_SPREAD = (((-1, -0.12, 0.14), BACK), ((-1, 0, 0.02), BACK), ((-1, 0.06, 0.5), BACK))
WING_GLIDE = (((-1, -0.18, 0.14), BACK), ((-1, -0.04, 0.04), BACK), ((-1, 0.08, 0.52), (0.1, 0, 1)))
WING_UP = (((-0.55, -0.82, 0.12), (0, -0.2, 1)), ((-0.4, -0.9, 0.0), (0, -0.1, 1)), ((-0.15, -1, 0.3), (0, 0.15, 1)))
WING_DOWN = (((-0.75, 0.62, 0.0), (0, 0.15, 1)), ((-0.45, 0.88, -0.1), (0, 0.2, 1)), ((-0.2, 1, 0.25), (0, 0.3, 1)))
WING_FWD = (((-0.75, -0.25, -0.6), (0.35, 0, 1)), ((-0.35, 0.1, -0.95), (0.6, 0, 0.5)), ((0.1, 0.15, -1), (0.8, 0, 0.3)))
WING_BACKUP = (((-0.45, -0.75, 0.5), (0, 0, 1)), ((-0.3, -0.75, 0.6), (0, 0.4, 1)), ((-0.1, -0.6, 0.8), (0, 0.6, 1)))
WING_SLACK = (((-0.85, 0.35, 0.35), (-0.1, 0.15, 1)), ((-0.85, 0.5, -0.1), (0, 0.25, 1)), ((-0.8, 0.5, 0.35), (0, 0.3, 1)))
WING_VERTICAL = (((-0.25, -1, 0.05), BACK), ((-0.1, -1, -0.05), BACK), ((0.05, -1, 0.15), BACK))
WING_BRAKE = (((-0.6, -0.6, -0.5), (0, 0.4, 1)), ((-0.55, -0.4, -0.7), (0, 0.6, 1)), ((-0.4, -0.1, -0.9), (0, 0.8, 0.6)))
WING_DIVE = (((-0.35, -0.3, 0.9), (0.05, 1, 0.2)), ((0.05, 0.15, -1), (0.0, 1, 0.1)), ((-0.05, 0.05, 1), (0, 1, 0)))


def base_pose():
    return {b: (0.0, 0.0, 0.0) for b in BONES}


def build(body=(0, 0, 0), chest=(0, 0, 0), neckp=None, wings=WING_FOLD, tailp=None, jaw=0, extra=None,
          feet='stand', front=None, hind=None, lift=None, ground_names=None, ground_extra=0.0, flying=False,
          tuck=None, body_dy=0.0):
    """Setzt eine Schlüsselpose zusammen. feet='stand': alle vier Füße per IK auf den Boden (front/hind überschreiben
    Fußpunkte als {seite: (x, z)}); feet='free': Beine stehen in tuck (Flug) bzw. werden nicht gelöst."""
    pose = base_pose()
    pose['body'] = R(*body) + (0.0, body_dy, 0.0)
    pose['chest'] = R(*chest)
    pose.update(neck(*(neckp or (-24, -6, -4, -2, 30))[:5]))
    pose.update(wings_sym(wings))
    pose.update(tail(*(tailp or ((-10, 0), (-6, 0), (-4, 0), (-2, 0), (2, 0), (4, 0)))))
    pose['jaw'] = R(jaw, 0, 0)
    if extra:
        for k, v in extra.items():
            pose[k] = R(*v)
    if feet == 'stand':
        front = front or {}
        hind = hind or {}
        lift = lift or {}
        for _ in range(6):
            planted = []
            for side in ('r', 'l'):
                f = front.get(side, STANCE[f'{side}_front'])
                if f != 'free':
                    plant_fore(pose, side, f[0], f[1], lift.get(f'{side}_front', 0.0))
                    if not lift.get(f'{side}_front'):
                        planted.append(f'hand_{side}')
                h = hind.get(side, STANCE[f'{side}_hind'])
                if h != 'free':
                    plant_hind(pose, side, h[0], h[1], lift.get(f'{side}_hind', 0.0))
                    if not lift.get(f'{side}_hind'):
                        planted.append(f'toes_{side}')
            fr = frames(pose)
            if ground_names:
                shift = GROUND + ground_extra - lowest(fr, ground_names, deep=False)
            else:
                # Erreichen die Füße den Boden nicht (Aufbäumen), sinkt der Rumpf nach.
                shift = max(0.0, GROUND - lowest(fr, planted)) if planted else 0.0
            if abs(shift) < 0.02:
                break
            b = list(pose['body'])
            b[4] += shift
            pose['body'] = tuple(b)
    elif tuck:
        pose.update(tuck)
    return pose


# Angezogene Beine im Flug.
TUCK = {
    'arm_r': R(55, 0, 0), 'fore_r': R(-95, 0, 0), 'hand_r': R(60, 0, 0),
    'arm_l': R(55, 0, 0), 'fore_l': R(-95, 0, 0), 'hand_l': R(60, 0, 0),
    'thigh_r': R(70, 0, 0), 'shin_r': R(-20, 0, 0), 'foot_r': R(70, 0, 0),
    'thigh_l': R(70, 0, 0), 'shin_l': R(-20, 0, 0), 'foot_l': R(70, 0, 0),
}
# Beine hängen lang nach unten (Landeanflug).
REACH = {
    'arm_r': R(-30, 0, 0), 'fore_r': R(-20, 0, 0), 'hand_r': R(40, 0, 0),
    'arm_l': R(-30, 0, 0), 'fore_l': R(-20, 0, 0), 'hand_l': R(40, 0, 0),
    'thigh_r': R(-30, 0, 0), 'shin_r': R(40, 0, 0), 'foot_r': R(-20, 0, 0),
    'thigh_l': R(-30, 0, 0), 'shin_l': R(40, 0, 0), 'foot_l': R(-20, 0, 0),
}
FLY_TAIL = ((4, 0), (2, 0), (0, 0), (0, 0), (2, 0), (4, 0))
FLY_DY = -6.0    # Flughaltung: Körper leicht angehoben, bleibt in der Trefferbox (4,5 x 3,6) über dem Entity-Punkt


def flight(body=(0, 0, 0), neckp=(-6, 0, 2, 2, 8), wings=WING_SPREAD, tailp=FLY_TAIL, jaw=0, legs=None, dy=FLY_DY,
           chest=(0, 0, 0), extra=None):
    return build(body=body, chest=chest, neckp=neckp, wings=wings, tailp=tailp, jaw=jaw, feet='free',
                 tuck=legs or TUCK, body_dy=dy, extra=extra)


KEYS = {}


def lift_tail(pose):
    """Am Boden: Schwanzglieder, die in den Boden ragen würden, Glied für Glied anheben."""
    for i in range(6):
        name = f'tail_{i}'
        for _ in range(40):
            fr = frames(pose)
            if lowest(fr, [name] if i < 5 else [name, 'fan'], deep=(i == 5)) <= GROUND - 0.5:
                break
            p = list(pose[name])
            p[0] += math.radians(3)
            pose[name] = tuple(p)


def lift_neck(pose):
    """Am Boden: Hals und Kopf liegen höchstens auf, sie stechen nie hinein (vom Rumpf zur Schnauze)."""
    for name in ('neck_0', 'neck_1', 'neck_2', 'neck_3', 'head'):
        for _ in range(60):
            fr = frames(pose)
            if lowest(fr, [name, 'jaw'] if name == 'head' else [name], deep=False) <= GROUND - 0.3:
                break
            p = list(pose[name])
            p[0] -= math.radians(2)
            pose[name] = tuple(p)


def lift_wings(pose):
    """Am Boden: Schwingen höchstens bis zum Boden; zu tiefe Schwingen werden an der Schulter angehoben."""
    for side, sign in (('r', 1), ('l', -1)):
        name = f'wing_{side}'
        for _ in range(60):
            fr = frames(pose)
            if lowest(fr, [name]) <= GROUND - 0.3:
                break
            p = list(pose[name])
            p[2] += sign * math.radians(2)
            pose[name] = tuple(p)


def key(name, pose):
    if pose['body'][4] > -3:
        lift_neck(pose)
        lift_tail(pose)
        lift_wings(pose)
    KEYS[name] = pose


# --- Boden ---------------------------------------------------------------------------------------------------------
# Ruhe: Hals lang und gerade schräg nach oben, Kopf waagerecht; Schwingen angelegt; Schwanz liegt in flachem Bogen.
key('idle', build(neckp=(-44, -3, -2, -1, 50),
                  tailp=((-12, 0), (-6, 4), (-2, 6), (2, 6), (4, 4), (4, 0))))
# Einatmen: Hals weit zurück (S-Kurve nach oben), Brust gehoben, Schwingen halb offen.
key('inhale', build(body=(-6, 0, 0), chest=(-10, 0, 0), neckp=(-48, -18, 8, 14, 40), wings=WING_HALF, jaw=10,
                    tailp=((-6, 0), (-4, 0), (-2, 0), (2, 0), (4, 0), (6, 0))))
# Atem: Hals stößt nach vorn, Kopf gerade, Maul weit offen.
key('breath', build(body=(4, 0, 0), chest=(6, 0, 0), neckp=(-10, 2, 2, 2, 8), wings=WING_HALF, jaw=46,
                    tailp=((-8, 0), (-4, 0), (0, 0), (2, 0), (4, 0), (6, 0))))
# Eisfeld: Atem schräg nach unten in einen Kreis.
key('freeze_windup', build(body=(-4, 0, 0), chest=(-8, 0, 0), neckp=(-44, -14, 6, 10, 36), wings=WING_HALF, jaw=8))
key('freeze', build(body=(8, 0, 0), chest=(10, 0, 0), neckp=(16, 10, 8, 6, 14), wings=WING_HALF, jaw=48))
# Klauenhieb: rechter Vorderlauf hoch und zurück, Schulter hoch, Gewicht nach links.
key('claw_windup', build(body=(-8, -10, -6), chest=(-8, -14, -8), neckp=(-26, -4, -2, 0, 28, 0, 0), wings=WING_HALF,
                         front={'r': 'free'}, extra={'arm_r': (-120, 0, -24), 'fore_r': (-40, 0, 0), 'hand_r': (-20, 0, 0)},
                         tailp=((-8, 10), (-4, 10), (0, 8), (2, 6), (4, 4), (4, 0))))
key('claw_hit', build(body=(6, 14, 4), chest=(10, 18, 6), neckp=(-16, 2, 2, 2, 22), wings=WING_HALF,
                      front={'r': 'free'}, extra={'arm_r': (-50, 0, 34), 'fore_r': (-12, 0, 0), 'hand_r': (30, 0, 0)},
                      tailp=((-10, -10), (-6, -10), (-2, -8), (0, -6), (4, -4), (4, 0))))
# Schwanzfeger: Körper verdreht, Schwanz holt nach rechts aus und fegt nach links vorn.
key('tail_windup', build(body=(0, 18, 0), chest=(0, 14, 0), neckp=(-26, -4, -4, -2, 30), wings=WING_HALF,
                         tailp=((-8, -34), (-2, -24), (0, -18), (2, -14), (4, -10), (10, -6)),
                         extra={'neck_0': (-26, -24, 0)}))
key('tail_hit', build(body=(0, -24, 0), chest=(0, -16, 0), neckp=(-22, 0, -2, -2, 28), wings=WING_HALF,
                      tailp=((-4, 38), (-2, 26), (0, 22), (2, 18), (2, 12), (6, 8)),
                      extra={'neck_0': (-22, 22, 0)}))
key('tail_follow', build(body=(0, -30, 0), chest=(0, -20, 0), neckp=(-22, 0, -2, -2, 28), wings=WING_HALF,
                         tailp=((-6, 44), (-4, 32), (-2, 26), (0, 20), (0, 14), (4, 8)),
                         extra={'neck_0': (-22, 26, 0)}))
# Flügelstoß: Schwingen hoch und zurück (sammeln), dann weit nach vorn geschlagen. Hinterbeine stemmen sich.
key('gust_windup', build(body=(-14, 0, 0), chest=(-10, 0, 0), neckp=(-34, -10, -4, 0, 36), wings=WING_BACKUP, jaw=12,
                         hind={'r': (-9, 18), 'l': (9, 18)}))
key('gust_hit', build(body=(8, 0, 0), chest=(6, 0, 0), neckp=(-14, 0, 2, 2, 16), wings=WING_FWD, jaw=30,
                      hind={'r': (-9, 20), 'l': (9, 20)}))
# Griff: Kopf und rechte Klaue tief nach vorn, dann mit dem Brocken in der Klaue aufrichten.
key('grab_reach', build(body=(14, 0, 0), chest=(14, 0, 0), neckp=(10, 10, 8, 6, 20), wings=WING_HALF, jaw=22,
                        front={'r': (-8, -34)}))
key('grab_hold', build(body=(-10, 0, 0), chest=(-16, 0, 0), neckp=(-30, -6, -2, 0, 30), wings=WING_HALF, jaw=6,
                       front={'r': 'free'}, extra={'arm_r': (-70, 0, 10), 'fore_r': (-60, 0, 0), 'hand_r': (-40, 0, 0)}))
# Wurf: aufbäumen, Klaue weit zurück, dann nach vorn schleudern.
key('throw_windup', build(body=(-22, 0, 0), chest=(-20, -16, 0), neckp=(-24, -6, 0, 0, 26, 0), wings=WING_BACKUP,
                          front={'r': 'free'}, extra={'arm_r': (20, 0, 20), 'fore_r': (-70, 0, 0), 'hand_r': (-30, 0, 0)}))
key('throw_hit', build(body=(10, 0, 0), chest=(10, 16, 0), neckp=(-14, 0, 2, 2, 18), wings=WING_FWD, jaw=20,
                       front={'r': 'free'}, extra={'arm_r': (-110, 0, -10), 'fore_r': (-10, 0, 0), 'hand_r': (10, 0, 0)}))
# Brüllen: Kopf hoch, Maul offen, Schwingen halb gespreizt.
key('roar', build(body=(-10, 0, 0), chest=(-12, 0, 0), neckp=(-44, -10, -6, -4, -10), wings=WING_HALF, jaw=40,
                  tailp=((-6, 0), (-2, 0), (2, 0), (6, 0), (8, 0), (10, 0))))
# Zu Boden gebracht: Bauch am Boden, Kopf flach, Schwingen schlaff ausgebreitet.
key('downed', build(body=(2, 0, 4), chest=(2, 0, -2), neckp=(20, 4, 2, 0, -6, 0), wings=WING_SLACK,
                    front={'r': (-14, -36), 'l': (13, -38)}, hind={'r': (-17, 16), 'l': (17, 18)},
                    ground_names=('body', 'chest'),
                    tailp=((4, 8), (0, 8), (0, 10), (0, 12), (0, 10), (0, 6))))
# Erschöpft: Kopf tief, Hals hängt, Schwingen halb herabgelassen.
key('exhausted', build(body=(8, 0, 0), chest=(6, 0, 0), neckp=(16, 8, 6, 4, -4), wings=WING_SLACK, jaw=12,
                       tailp=((-14, 0), (-8, 6), (-2, 6), (0, 6), (2, 4), (2, 0))))
# Abflug: tief in die Hocke, Schwingen hoch (Ausholen), dann ein Schlag nach unten.
key('takeoff_crouch', build(body=(-4, 0, 0), neckp=(-20, -6, -4, -2, 26), wings=WING_UP, body_dy=7,
                            hind={'r': (-9, 12), 'l': (9, 12)}))
key('takeoff_beat', flight(body=(-10, 0, 0), neckp=(-24, -6, -2, 0, 24), wings=WING_DOWN, legs=REACH, dy=-10))
# Resonanz: Schwingen senkrecht, Kopf hoch.
key('resonance', build(body=(-8, 0, 0), chest=(-10, 0, 0), neckp=(-40, -10, -6, -6, 30), wings=WING_VERTICAL,
                       tailp=((-8, 0), (-4, 0), (0, 0), (2, 0), (4, 0), (8, 0))))
# Liegt ruhig zusammengerollt: Hals nach rechts um den Körper, Schwanz nach links nach vorn.
key('lie_down', build(body=(0, 0, 0), chest=(0, 0, 0), neckp=(18, 6, 0, 0, -2), wings=WING_TIGHT,
                      front={'r': (-12, -36), 'l': (12, -34)}, hind={'r': (-16, 18), 'l': (16, 16)},
                      ground_names=('body', 'chest'),
                      tailp=((2, 20), (0, 30), (0, 34), (0, 36), (0, 30), (0, 20)),
                      extra={'neck_0': (18, -26, 0), 'neck_1': (6, -30, 0), 'neck_2': (0, -32, 0), 'neck_3': (0, -26, 0),
                             'head': (-2, -20, 0)}))

# --- Luft ----------------------------------------------------------------------------------------------------------
key('hover', flight(body=(-14, 0, 0), neckp=(-18, -4, 0, 2, 26), wings=WING_SPREAD))
key('fly_up', flight(body=(-14, 0, 0), neckp=(-18, -4, 0, 2, 26), wings=WING_UP))
key('fly_down', flight(body=(-14, 0, 0), neckp=(-18, -4, 0, 2, 26), wings=WING_DOWN))
key('glide', flight(body=(2, 0, 0), neckp=(-4, 0, 2, 2, 4), wings=WING_GLIDE))
key('dive_windup', flight(body=(-30, 0, 0), neckp=(-10, 0, 0, 0, 20), wings=WING_UP))
key('dive', flight(body=(38, 0, 0), neckp=(2, 2, 0, 0, -6), wings=WING_DIVE, tailp=((4, 0), (2, 0), (0, 0), (0, 0), (0, 0), (0, 0))))
key('crash_windup', flight(body=(-36, 0, 0), neckp=(-6, 0, 0, 0, 16), wings=WING_UP))
key('crash', flight(body=(80, 0, 0), neckp=(0, 0, 0, 0, -4), wings=WING_DIVE, tailp=((4, 0), (2, 0), (0, 0), (0, 0), (0, 0), (0, 0))))
key('inhale_air', flight(body=(-16, 0, 0), chest=(-8, 0, 0), neckp=(-44, -16, 8, 12, 40), jaw=10))
key('breath_air', flight(body=(6, 0, 0), chest=(4, 0, 0), neckp=(10, 6, 6, 4, 14), jaw=46))
# Landung: Anflug mit Schwingen hoch-vorn (Bremsen), Beine gestreckt; Aufprall tief gestaucht, Schwingen nach unten.
key('land_windup', flight(body=(-24, 0, 0), neckp=(-22, -6, -2, 0, 30), wings=WING_BRAKE,
                          legs=REACH, dy=-10))
key('land_hit', build(body=(6, 0, 0), chest=(8, 0, 0), neckp=(6, 6, 6, 4, 6), wings=WING_DOWN, jaw=10, body_dy=0,
                      front={'r': (-12, -19), 'l': (12, -19)}, hind={'r': (-10, 13), 'l': (10, 13)},
                      ground_extra=0.0))


def _land_squash():
    """Aufprall: Rumpf 3 Einheiten tiefer als der Stand erlaubt (Beine gebeugt), danach federt alles zurück."""
    p = KEYS['land_hit']
    b = list(p['body'])
    b[4] += 3.0
    p['body'] = tuple(b)
    for side in ('r', 'l'):
        plant_fore(p, side, -12 if side == 'r' else 12, -19)
        plant_hind(p, side, -10 if side == 'r' else 10, 13)


_land_squash()

# Takeoff-Hocke etwas tiefer: Beine neu lösen, nachdem der Rumpf gesenkt ist.
for _side in ('r', 'l'):
    plant_fore(KEYS['takeoff_crouch'], _side, -10 if _side == 'r' else 10, -17)
    plant_hind(KEYS['takeoff_crouch'], _side, -9 if _side == 'r' else 9, 12)

POSES = KEYS


# ---------------------------------------------------------------------------------------------------------------------
# Anker (Trefferzonen) und Kotlin-Block
# ---------------------------------------------------------------------------------------------------------------------

ANCHORS = {
    'head': ('head', (0, -1.5, -8)),
    'heart': ('chest', (0, 4, -25)),
    'throat': ('neck_1', (0, 3.5, -4)),
    'tail_tip': ('fan', (0, 0, 8)),
}


def anchor_blocks(pose_name):
    fr = frames(POSES[pose_name])
    out = {}
    for k, (bone, local) in ANCHORS.items():
        x, y, z = point(fr, bone, local)
        # Renderer: Modell blickt nach -z; vorwärts = -z, hoch = 24 - y, rechts (der Drachin) = -x.
        out[k] = (-z / U, (GROUND - y) / U, -x / U)
    return out


def write_anchors():
    rows = []
    for p in ('idle', 'inhale', 'downed', 'exhausted', 'hover'):
        a = anchor_blocks(p)
        for k, label in (('head', 'Kopfmitte'), ('throat', 'Kehle'), ('heart', 'Herzkern'), ('tail_tip', 'Schwanzspitze')):
            f, u, r = a[k]
            rows.append(f'| {p.upper()} | {label} | {f:+.2f} | {u:+.2f} | {r:+.2f} |')
    text = f'''# Nivaris: Anker der Trefferzonen

Erzeugt von `python tools/assets/nivaris_model.py --anchors` aus denselben Schlüsselposen, die das Kotlin-Modell
(`client/model/nivaris/NivarisModel.kt`, Block `Keys`) benutzt. Alle Werte in **Blöcken relativ zur Entity-Position**
(Bodenpunkt unter der Rumpfmitte), Renderer-Faktor {SCALE} schon eingerechnet:

* **vor** = entlang der Blickrichtung (yaw) der Entity, negativ = hinter der Mitte
* **hoch** = über der Entity-Position (im Flug hebt der Server die Entity an; der Wert gilt relativ dazu)
* **rechts** = zur rechten Seite der Drachin (bei symmetrischen Posen 0)

| Pose | Zone | vor | hoch | rechts |
|---|---|---|---|---|
''' + '\n'.join(rows) + f'''

Hinweise:
* Die Werte sind die gehaltenen Endhaltungen. Atmen, Gehen und Flügelschlag verschieben sie um höchstens etwa
  ±0,3 Blöcke (HOVER: Körper hebt und senkt sich mit dem Flügelschlag um etwa ±0,25).
* INHALE gilt am Boden. Atmet sie im Flug ein (`flying = true`), nimmt das Modell die Flughaltung: Kopf
  {_inhale_air_note()}.
* HOVER: Rumpf, Beine und Schwanz bleiben mindestens {_hover_clear():.1f} Blöcke über dem Entity-Punkt (innerhalb der
  Trefferbox 4,5 × 3,6); nur die Schwingen reichen beim Abschlag bis etwa {-_hover_wing():.1f} Blöcke darunter.
* Kehle = Unterseite des zweiten Halsglieds (dort, wo die Naht beim Atemholen glüht); Herzkern = Mitte des goldenen
  Kerns zwischen den Brustschuppen. Für eine einzelne Zone „Kehle/Herzkern“ eignet sich der Mittelpunkt beider.
* Kopfmitte = Mitte des Schädels (zwischen den Augen), Schwanzspitze = Mitte des Eisklingen-Fächers.
'''
    path = os.path.join(HERE, '..', '..', 'docs', 'NIVARIS_ANCHORS.md')
    with open(path, 'w', encoding='utf8', newline='\n') as f:
        f.write(text)
    print('Anker:', os.path.abspath(path))
    for p in ('idle', 'inhale', 'downed', 'exhausted', 'hover', 'inhale_air'):
        print(p, {k: tuple(round(v, 2) for v in val) for k, val in anchor_blocks(p).items()})


def _hover_clear():
    fr = frames(POSES['hover'])
    low = max(lowest(fr, [n], deep=False) for n in fr if not n.startswith(('wing', 'memb', 'spar', 'thumb')))
    return (GROUND - low) / U


def _hover_wing():
    return (GROUND - lowest(frames(POSES['fly_down']), ['wing_r'])) / U


def _inhale_air_note():
    a = anchor_blocks('inhale_air')
    f, u, _ = a['head']
    tf, tu, _ = a['throat']
    return f'vor {f:+.2f} / hoch {u:+.2f}, Kehle vor {tf:+.2f} / hoch {tu:+.2f}'


def bone_paths():
    paths = {}

    def walk(part, prefix):
        path = f'{prefix}/{part.name}' if prefix else part.name
        paths[part.name] = path
        for ch in part.children:
            walk(ch, path)
    for p in _model().parts:
        walk(p, '')
    return {b: paths[b] for b in BONES}


def kotlin_block():
    paths = bone_paths()
    lines = ['    // <keys> erzeugt von tools/assets/nivaris_model.py --kotlin; nicht von Hand ändern', '    object Keys {']
    lines.append('        /** Knochenpfade in Kanalreihenfolge (je 6 Werte: pitch, yaw, roll, dx, dy, dz). */')
    lines.append('        val BONES = arrayOf(' + ', '.join(f'"{paths[b]}"' for b in BONES) + ')')
    for i, b in enumerate(BONES):
        lines.append(f'        const val {b.upper()} = {i}')
    lines.append(f'        const val COUNT = {len(BONES)}')
    lines.append(f'        /** Versatz des Körpers in der Flughaltung (Einheiten, -y = hoch). */')
    lines.append(f'        const val FLY_DY = {FLY_DY}f')
    for name, pose in POSES.items():
        vals = []
        for b in BONES:
            v = tuple(pose.get(b, (0.0, 0.0, 0.0)))
            v = v + (0.0,) * (6 - len(v))
            vals.extend(v)
        data = ','.join(('%.4f' % x).rstrip('0').rstrip('.') if abs(x) > 1e-6 else '0' for x in vals)
        lines.append(f'        val {name.upper()} = key("{data}")')
    lines.append('    }')
    lines.append('    // </keys>')
    return '\n'.join(lines)


def write_kotlin():
    path = os.path.join(HERE, '..', '..', 'src', 'main', 'kotlin', 'dev', 'henny', 'hha_arius', 'client', 'model',
                        'nivaris', 'NivarisModel.kt')
    with open(path, encoding='utf8') as f:
        text = f.read()
    if '// <keys>' not in text:
        raise SystemExit('Marker // <keys> fehlt in NivarisModel.kt')
    new = re.sub(r'    // <keys>.*?// </keys>', lambda m: kotlin_block(), text, flags=re.S)
    with open(path, 'w', encoding='utf8', newline='\n') as f:
        f.write(new)
    print(f'NivarisModel.kt: {len(POSES)} Schlüsselposen geschrieben')


if __name__ == '__main__':
    if '--kotlin' in sys.argv:
        write_kotlin()
    if '--anchors' in sys.argv:
        write_anchors()
    if '--feet' in sys.argv:
        for n, p in POSES.items():
            fr = frames(p)
            print(n, 'tiefster Punkt', round(lowest(fr, ['body']), 2),
                  'Füße', [round(lowest(fr, [f]), 1) for f in ('hand_r', 'hand_l', 'toes_r', 'toes_l')])

# Für die Größenprüfung in preview.py: Diese Knochen zählen nicht zur Körperhöhe.
HEIGHT_EXCLUDE = ('crown',)
