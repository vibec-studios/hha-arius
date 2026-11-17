"""Drachenherz-Wacht: Arius' Eirholm-Rüstung als eigenes 3D-Modell (statt umgefärbtem Netherite).

Lore: Solvane wurde aus dem Herzen eines uralten weißen Drachen geschmiedet und kann dessen Drachenform imitieren. Die
Rüstung greift Motive dieser Drachenform auf: Bauchschilde (quer liegende Ventralplatten) auf dem Kürass, ein leuchtendes
Herz-Juwel im Rautenstern (wie auf Solvanes Parierstange), nach hinten gefaltete Schwingenplatten an den Schultern,
eine Krone aus nach hinten gekämmten Eishörnern (eines abgebrochen), Schuppen-Tassetten, Drachenklauen an Knien und
Stiefelspitzen.

Material: Perlweiß mit eisblauen Schatten (passt zu den Handschuhen), Gold nur als Kante (gedecktes Altgold), dunkles
Navy-Leder darunter, Eis an den Hörnern. Leuchtmaske: Herz-Juwel (pulsiert im Spiel) und feine eisblaue Linien an
Schuppenkanten.

HD wie die Handschuhe: 2 Texel je Modelleinheit (Bild 256 x 256, Kotlin meldet 128 x 128), alle Maße auf dem
Halbeinheiten-Raster. Die Teile haben Drehpunkt 0 im Knochenraum des Bipeds (1/16 Block, +y nach unten, -z vorn):

  Kopf   x -4..4, y -8..0, z -4..4           helm_head
  Rumpf  x -4..4, y 0..12, z -2..2           chest_body, chest_heart, legs_body, legs_tasset_fr/_fl/_b (hängend)
  Arme   rechts x -3..1, y -2..10 (außen -x) chest_arm_r/_l, chest_wing_r/_l (hängend), chest_forearm_r/_l
  Beine  rechts x -2..2, y 0..12 (außen -x)  legs_leg_r/_l, boots_leg_r/_l

Hängende Teile (`*_tasset_*`, `*_wing_*`) haben ihr Gelenk als Drehpunkt; der Renderer dreht sie über die MatrixStack um
dieses Gelenk (Federn, Beinfolge). `chest_forearm_*` ist eine schlanke Unterarmschiene, die nur ohne Handschuhe in der
Hand gezeichnet wird (mit Handschuhen bleibt am Arm nur die Oberarmschiene, nichts doppelt).

Verträge (siehe _check): nichts reicht am Arm unter y 1,75 außer der Unterarmschiene (dort beginnt die Stulpe der
Handschuhe), der Rücken bleibt hinter z 3,0 (Umhang), die Krone überragt den Kopf um höchstens 6,4 Einheiten (0,4 Blöcke).
"""
import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)

from modelkit import Part, hexc, mix, ramp, rgb, speckle  # noqa: E402
from gauntlets_model import GOLD, HDCube, HDModel, RES, gold_c, mirrored  # noqa: E402

# ---------------------------------------------------------------------------------------------------------------------
# Materialien (dunkel -> hell)
# ---------------------------------------------------------------------------------------------------------------------
PLATE = rgb('#34405c', '#5b6c90', '#91a3c2', '#c2cee2', '#e2e9f3', '#f6f8fc', '#ffffff')   # Perlweiß, eisblaue Tiefen
NAVY = rgb('#090d1a', '#10172b', '#19223d', '#243052', '#33426a', '#4a5b86')            # Navy-Leder
ICE = rgb('#3e6390', '#6b98c6', '#a3cdea', '#d3ecfa', '#f1fbff', '#ffffff')              # Eishorn
HEART_RIM = rgb('#0c2c6e', '#1b4fae', '#2f7be0', '#63aef5', '#a8dcff')                   # blauer Rand des Juwels
HEART_CORE = rgb('#8a5410', '#c98a22', '#f2c25a', '#ffe7a6', '#fff8e6')                  # goldener Kern

GLOW_LINE = (196, 238, 255, 120)       # feine Eislinie an Schuppenkanten
GLOW_LINE_SOFT = (196, 238, 255, 90)
GLOW_RIM = (120, 190, 255, 235)        # blauer Juwelrand
GLOW_CORE = (255, 246, 222, 255)       # warmweißer Kern (bewusst nicht goldgelb)
GLOW_ICE_TIP = (214, 244, 255, 110)    # Hornspitzen, kaum

LIGHT = {'top': 0.12, 'bottom': -0.2, 'front': 0.02, 'back': -0.1, 'left': -0.06, 'right': -0.03}
SIDES = ('front', 'back', 'left', 'right')


def _n(x, y, seed):
    return speckle(x * 3 + seed * 5, y * 3 + seed * 7, seed) - 0.5


def _h(a, b, c=0):
    """Deterministischer Zufall 0..1 je Schuppe."""
    h = (a * 374761393 + b * 668265263 + c * 2147483647 + 0x9E3779B9) & 0xffffffff
    h = ((h ^ (h >> 13)) * 1274126177) & 0xffffffff
    return ((h ^ (h >> 16)) & 0xffff) / 65535.0


# ---------------------------------------------------------------------------------------------------------------------
# Malfunktionen. paint(face, x, y, w, h): Texel-Koordinaten der Fläche. Achtung: auf 'top'/'bottom' liegt im Spiel die
# Zeile y = 0 hinten (z max), siehe ModelPart.Cuboid. Seitenflächen: y = 0 oben.
# ---------------------------------------------------------------------------------------------------------------------
def enamel_t(face, x, y, w, h, base=0.6, seed=0, gloss=True):
    """Helligkeit einer Perlweiß-Platte: Flächenlicht, Verlauf, Pixel-Fase, Emaille-Glanz, feines Korn."""
    t = base + LIGHT[face] + _n(x, y, seed) * 0.05
    if face in SIDES:
        t += (0.5 - y / max(h - 1, 1)) * 0.1
        if h > 2 and y == 0:
            t += 0.1
        if h > 2 and y == h - 1:
            t -= 0.08
        if w > 2 and x in (0, w - 1):
            t -= 0.05
        if gloss and w > 3:
            k = x / (w - 1)
            t += 0.12 * math.exp(-((k - 0.32) / 0.17) ** 2)
    elif w > 2 and h > 2 and (x in (0, w - 1) or y in (0, h - 1)):
        t += 0.05
    return t


def navy_c(face, x, y, w, h, base=0.5, quilt=True):
    t = base + LIGHT[face] * 0.8 + _n(x, y, 21) * 0.1
    if quilt and face in SIDES and w > 3 and h > 3:
        if (x + y) % 6 == 0 or (x - y) % 6 == 0:
            t -= 0.16                                                      # Steppnaht (Rauten)
    return ramp(NAVY, t)


def navy(base=0.5, quilt=True):
    def paint(face, x, y, w, h):
        return navy_c(face, x, y, w, h, base, quilt)
    return paint


def gold(base=0.58):
    def paint(face, x, y, w, h):
        return gold_c(face, x, y, w, h, base)
    return paint


def scale_px(face, x, y, w, h, base, seed, sw=6, sh=4, glow_share=0.16):
    """Kleine überlappende Drachenschuppen (Dachziegel): runde Unterkante als Grat, darunter Schatten der nächsten Reihe.
    Liefert Farbe oder (Farbe, Leuchten)."""
    row = y // sh
    off = (sw // 2) if row % 2 else 0
    col = (x + off) // sw
    cx = (x + off) % sw
    v = y % sh
    u = (cx + 0.5) / sw * 2 - 1
    edge = sh - 1 - int(round(1.6 * u * u))
    key = _h(col, row, seed)
    t = base + LIGHT[face] + (key - 0.5) * 0.06 + _n(x, y, seed) * 0.02
    if v > edge:                                                           # Ecke: Schatten der Schuppe darunter
        return ramp(PLATE, t - 0.15)
    if v == edge:                                                          # freie Kante fängt Licht
        c = ramp(PLATE, t + 0.17)
        if key < glow_share and abs(u) < 0.7:
            return (mix(c, hexc('#bfe8ff'), 0.5), GLOW_LINE_SOFT)
        return c
    t += 0.08 * v / max(sh - 1, 1) - (0.06 if v == 0 else 0)               # Wurzel liegt unter der Kante darüber
    t += 0.05 * (1 - abs(u))                                               # Wölbung
    return ramp(PLATE, t)


def scaled(base=0.6, seed=1, faces=('front', 'back', 'right', 'left'), rim=None, rim_gold=True, sw=4, sh=3):
    """Platte mit Schuppenmuster auf den genannten Flächen; rim: Goldkante an 'top'/'bottom'-Zeilen der Seitenflächen."""
    rim = rim or ()

    def paint(face, x, y, w, h):
        if face in SIDES:
            if 'bottom' in rim and y == h - 1 and rim_gold:
                return gold_c(face, x, y, w, h, 0.55)
            if 'top' in rim and y == 0 and rim_gold:
                return gold_c(face, x, y, w, h, 0.62)
            if face in faces:
                return scale_px(face, x, y, w, h, base, seed + SIDES.index(face), sw, sh)
        return ramp(PLATE, enamel_t(face, x, y, w, h, base - 0.04, seed))
    return paint


def plate(base=0.62, seed=0, rim=('bottom',), glow_rim=False, inner=None, gloss=True, keel=False, cut=None):
    """Glatte Perlweiß-Platte. rim: Goldkante ('bottom', 'top', 'sides'); glow_rim: Eislinie direkt über der unteren
    Goldkante (nur vorn/außen); inner: Fläche, die Navy-Leder zeigt; keel: heller Mittelgrat; cut(face,x,y,w,h) -> True
    macht den Texel durchsichtig (Ausschnitte, Wellenkante)."""
    def paint(face, x, y, w, h):
        if cut is not None and cut(face, x, y, w, h):
            return None
        if inner is not None and face == inner:
            return navy_c(face, x, y, w, h, 0.42, quilt=False)
        if face in SIDES:
            if 'bottom' in rim and y == h - 1:
                return gold_c(face, x, y, w, h, 0.56)
            if 'top' in rim and y == 0:
                return gold_c(face, x, y, w, h, 0.64)
            if 'sides' in rim and w > 2 and x in (0, w - 1):
                return gold_c(face, x, y, w, h, 0.52)
            t = enamel_t(face, x, y, w, h, base, seed, gloss)
            if keel and w > 4 and abs(x - (w - 1) / 2) < 0.6:
                t += 0.12
            c = ramp(PLATE, t)
            if glow_rim and face in ('front', 'right', 'back') and 'bottom' in rim and y == h - 2 and h > 3:
                if 0 < x < w - 1:
                    return (mix(c, hexc('#c4ecff'), 0.55), GLOW_LINE)
            return c
        if face == 'bottom' and 'bottom' in rim:
            return gold_c(face, x, y, w, h, 0.46)
        return ramp(PLATE, enamel_t(face, x, y, w, h, base - 0.05, seed))
    return paint


def scute(seed=0):
    """Bauchschild (quer liegende Ventralplatte des Drachen): oben verschattet (liegt unter der Platte darüber), Mitte
    mit hellem Kiel, unten Eislinie + Goldkante, die unteren Ecken abgerundet (durchsichtig)."""
    def cut(face, x, y, w, h):
        if face in ('front', 'back') and y == h - 1 and (x == 0 or x == w - 1):
            return True
        return False

    def paint(face, x, y, w, h):
        if cut(face, x, y, w, h):
            return None
        if face in ('front', 'back'):
            if y == h - 1 or (y == h - 2 and (x == 0 or x == w - 1)):
                return gold_c(face, x, y, w, h, 0.6)
            t = 0.6 + LIGHT[face] + _n(x, y, seed) * 0.04 + 0.1 * y / max(h - 1, 1)
            if y == 0:
                t -= 0.16
            cx = (w - 1) / 2
            t += 0.12 * math.exp(-((x - cx) / (w * 0.12)) ** 2)            # Kiel
            t += 0.08 * math.exp(-(((x / (w - 1)) - 0.3) / 0.14) ** 2)      # Glanz
            c = ramp(PLATE, t)
            if face == 'front' and y == h - 2 and 1 < x < w - 2:
                return (mix(c, hexc('#c4ecff'), 0.5), GLOW_LINE)
            return c
        if face in ('left', 'right'):
            return gold_c(face, x, y, w, h, 0.5) if y >= h - 1 else ramp(PLATE, 0.52 + LIGHT[face])
        return ramp(PLATE, 0.5 + LIGHT[face])
    return paint


def scallop_cut(period=4):
    """Wellenkante unten: in jeder Periode bleiben die beiden mittleren Texel der letzten Zeile stehen."""
    def cut(face, x, y, w, h):
        if face in ('front', 'back') and y == h - 1:
            return (x % period) in (0, period - 1)
        if face in ('left', 'right') and y == h - 1:
            return True
        return False
    return cut


def lame(base=0.62, seed=0, glow=True, period=4, outer='front'):
    """Tassetten- und Schwingenlamelle: Perlweiß, Wellenkante unten mit Goldsaum, darüber eine feine Eislinie."""
    cut = scallop_cut(period)

    def paint(face, x, y, w, h):
        if cut(face, x, y, w, h):
            return None
        if face in SIDES:
            if y == h - 1:
                return gold_c(face, x, y, w, h, 0.64)
            t = enamel_t(face, x, y, w, h, base, seed)
            if y == 0:
                t -= 0.08
            c = ramp(PLATE, t)
            if glow and face == outer and y == h - 3 and h > 4 and (x % period) in (1, 2):
                return (mix(c, hexc('#c4ecff'), 0.45), GLOW_LINE_SOFT)
            return c
        if face == 'bottom':
            return gold_c(face, x, y, w, h, 0.44)
        return ramp(PLATE, enamel_t(face, x, y, w, h, base - 0.06, seed))
    return paint


def collar():
    """Halsberge: Goldwulst oben, Perlweiß, oben eine dunkle Öffnung (hier steckt der Hals)."""
    def paint(face, x, y, w, h):
        if face == 'top':
            if 2 <= x < w - 2 and 2 <= y < h - 2:
                return navy_c(face, x, y, w, h, 0.25, quilt=False)
            return gold_c(face, x, y, w, h, 0.6)
        if face in SIDES:
            if y == 0:
                return gold_c(face, x, y, w, h, 0.66)
            return ramp(PLATE, enamel_t(face, x, y, w, h, 0.6, 31))
        return navy_c(face, x, y, w, h, 0.3, quilt=False)
    return paint


def spine():
    """Rückgrat-Leiste: kleine Rückenplatten (Chevrons) mit Goldkanten, flach (Umhang)."""
    def paint(face, x, y, w, h):
        if face == 'back':
            if x in (0, w - 1):
                return gold_c(face, x, y, w, h, 0.5)
            k = (y + abs(x - (w - 1) / 2)) % 4
            t = 0.64 + LIGHT[face] + (0.12 if k < 1 else -0.1 if k >= 3 else 0)
            return ramp(PLATE, t)
        return gold_c(face, x, y, w, h, 0.5)
    return paint


def ice(base=0.45, tip=False, broken=False):
    """Eishorn: heller Kern, kühle Ränder, Spitze heller; nur die Spitze trägt einen Hauch Leuchten."""
    def paint(face, x, y, w, h):
        t = base + LIGHT[face] * 1.2 + _n(x, y, 41) * 0.05
        if face in SIDES and w > 1:
            t += 0.1 * (1 - abs(x - (w - 1) / 2) / max((w - 1) / 2, 0.5))
        if broken and face == 'back':
            return ramp(ICE, 0.86 + _n(x, y, 3) * 0.2)                   # frische Bruchfläche
        c = ramp(ICE, t)
        if tip and face in SIDES and y == 0 and x == (w - 1) // 2:
            return (mix(c, hexc('#ffffff'), 0.4), GLOW_ICE_TIP)
        return c
    return paint


def heart_setting():
    """Fassung des Herz-Juwels: Altgold, Facettengrat auf den Diagonalen (nach 45° Drehung zu den Kanten)."""
    def paint(face, x, y, w, h):
        if face != 'front':
            return gold_c(face, x, y, w, h, 0.5)
        rim = x in (0, w - 1) or y in (0, h - 1)
        cx, cy = (w - 1) / 2, (h - 1) / 2
        k = 0.66 if (x - cx) + (y - cy) < 0 else 0.46
        return ramp(GOLD, k - (0.12 if rim else 0))
    return paint


def heart_jewel():
    """Herz-Juwel: goldener Kern, blauer Rand; beides leuchtet (der Renderer lässt es atmen)."""
    def paint(face, x, y, w, h):
        cx, cy = (w - 1) / 2, (h - 1) / 2
        r = max(abs(x - cx), abs(y - cy)) / max(cx, 0.5)
        if face == 'front':
            if r < 0.4:
                return (ramp(HEART_CORE, 0.95), GLOW_CORE)
            if r < 0.75:
                return (ramp(HEART_CORE, 0.7), (255, 236, 200, 200))
            return (ramp(HEART_RIM, 0.62 + (0.15 if x + y < w - 1 else -0.1)), GLOW_RIM)
        return (ramp(HEART_RIM, 0.45 + LIGHT[face]), GLOW_RIM[:3] + (150,))
    return paint


def star_ray():
    def paint(face, x, y, w, h):
        return gold_c(face, x, y, w, h, 0.68 if face in ('front', 'top') else 0.5)
    return paint


def claw():
    """Goldene Drachenkralle: heller Grat vorn, dunkle Spitze."""
    def paint(face, x, y, w, h):
        t = 0.68 + LIGHT[face] * 1.1 - 0.16 * (y / max(h - 1, 1))
        if face in ('front', 'right', 'left') and y == 0:
            t += 0.12
        return ramp(GOLD, t)
    return paint


def knee_cop():
    """Kniekachel wie eine Drachenpranke: gewölbtes Perlweiß, Goldrand, Mittelgrat; die Krallen hängen darunter."""
    def paint(face, x, y, w, h):
        if face == 'front':
            cx = (w - 1) / 2
            if x in (0, w - 1) or y == 0 or y == h - 1:
                return gold_c(face, x, y, w, h, 0.66)
            t = 0.66 + 0.14 * math.exp(-((x - cx) / (w * 0.16)) ** 2) - 0.12 * abs(y - h * 0.4) / h
            if y == h // 2 and abs(x - cx) > 0.6:
                t -= 0.14
            return ramp(PLATE, t)
        return gold_c(face, x, y, w, h, 0.56)
    return paint


def brow():
    """Stirnreif: Perlweiß mit Goldkanten oben und unten, eine feine Eislinie in der Mitte."""
    def paint(face, x, y, w, h):
        if face in SIDES:
            if y in (0, h - 1):
                return gold_c(face, x, y, w, h, 0.6)
            c = ramp(PLATE, enamel_t(face, x, y, w, h, 0.66, 51))
            if face == 'front' and y == h // 2 and 2 < x < w - 3 and abs(x - (w - 1) / 2) > 1.5:
                return (mix(c, hexc('#c4ecff'), 0.5), GLOW_LINE_SOFT)
            return c
        return gold_c(face, x, y, w, h, 0.5)
    return paint


def helm_cap():
    """Helmglocke: Perlweiß, Schuppenmuster an den Seiten, vorn glatt (dort sitzt der Stern), Oberseite gewölbt."""
    def paint(face, x, y, w, h):
        if face == 'top':
            cx, cy = (w - 1) / 2, (h - 1) / 2
            d = math.hypot((x - cx) / cx, (y - cy) / cy)
            return ramp(PLATE, 0.78 - 0.18 * d + _n(x, y, 61) * 0.04)
        if face == 'bottom':
            return navy_c(face, x, y, w, h, 0.25, quilt=False)
        if face in ('front', 'right', 'left'):
            if y == h - 1:
                return gold_c(face, x, y, w, h, 0.64)
            t = enamel_t(face, x, y, w, h, 0.68, 62)
            if face != 'front' and y == h - 3 and 1 < x < w - 2:
                return (mix(ramp(PLATE, t), hexc('#c4ecff'), 0.5), GLOW_LINE_SOFT)
            return ramp(PLATE, t)
        return scale_px(face, x, y, w, h, 0.62, 63)
    return paint


# ---------------------------------------------------------------------------------------------------------------------
# Bauhelfer
# ---------------------------------------------------------------------------------------------------------------------
def make_c(right):
    """Quader-Fabrik für eine Körperseite: links = gespiegelt (x und Malfunktion)."""
    def C(x, y, z, w, h, d, paint, dilate=0.0):
        if right:
            return HDCube(x, y, z, w, h, d, paint, dilate)
        return HDCube(-(x + w), y, z, w, h, d, mirrored(paint), dilate)
    return C


def make_p(right):
    """Teil-Fabrik: links mit gespiegeltem Drehpunkt und gespiegelter Drehung (yaw, roll negiert)."""
    def P(name, pivot=(0, 0, 0), rot=(0, 0, 0), cubes=(), children=()):
        if right:
            return Part(name, pivot, rot, cubes, children)
        return Part(name, (-pivot[0], pivot[1], pivot[2]), (rot[0], -rot[1], -rot[2]), cubes, children)
    return P


def horn(P, C, name, root, rot, segs, bend, broken=False):
    """Eishorn entlang +z (nach hinten); jedes Segment ein Kindteil, das um bend Grad weiter nach oben biegt."""
    parent = None
    first = None
    z = 0.0
    for k, (length, width) in enumerate(segs):
        last = k == len(segs) - 1
        hw = width / 2
        cubes = [C(-hw, -hw, 0.0, width, width, length, ice(0.42 + 0.1 * k, tip=last and not broken, broken=last and broken))]
        if last and broken:
            # Splitterkante: zwei kleine Zacken stehen über die Bruchfläche hinaus
            cubes.append(C(-hw, -hw, length, 0.5, 0.5, 0.5, ice(0.7)))
            cubes.append(C(hw - 0.5, 0.0, length, 0.5, 0.5, 0.5, ice(0.62)))
        piv = root if k == 0 else (0.0, 0.0, z)
        r = rot if k == 0 else (bend, 0.0, 0.0)
        part = P(f'{name}_{k}', piv, r, cubes)
        if parent is None:
            first = part
        else:
            parent.add(part)
        parent = part
        z = length
    return first


# ---------------------------------------------------------------------------------------------------------------------
# Rüstungsteile
# ---------------------------------------------------------------------------------------------------------------------
def helmet():
    R, L = make_c(True), make_c(False)
    PR, PL = make_p(True), make_p(False)
    C = R
    cubes = [
        C(-4.5, -9.0, -4.5, 9.0, 3.5, 9.0, helm_cap(), 0.25),                    # Glocke, außerhalb der Hutschicht (4,5)
        C(-3.5, -9.5, -3.5, 7.0, 0.5, 7.0, plate(0.72, 64, rim=())),              # Wölbung
        C(-0.5, -10.0, -3.0, 1.0, 0.5, 6.5, gold(0.62)),                          # niedriger Kamm (Gold)
        C(-4.75, -6.75, -5.0, 9.5, 1.5, 0.5, brow()),                             # Stirnreif über den Augen
        C(-4.5, -5.5, 4.5, 9.0, 4.5, 0.5, scaled(0.58, 70, rim=('bottom',))),     # Nacken
        C(-4.25, -1.5, 4.75, 8.5, 1.5, 0.5, lame(0.6, 71, glow=False)),           # Nackenlamellen, nach außen gestuft
        C(-4.0, -0.5, 5.0, 8.0, 1.5, 0.5, lame(0.56, 72, glow=False)),
    ]
    for C, P in ((R, PR), (L, PL)):
        cubes += [
            C(-5.0, -5.5, -2.5, 0.5, 4.5, 7.5, scaled(0.58, 73, rim=('bottom',))),      # Schläfenwand
            # Wangenschilde als drei Schuppenplatten, nach unten schmaler; decken die äußere Gesichtskante
            C(-5.0, -5.5, -5.0, 2.0, 1.5, 3.0, plate(0.66, 74, glow_rim=True)),
            C(-4.75, -4.0, -4.75, 1.5, 1.5, 2.5, plate(0.62, 75, glow_rim=True)),
            C(-4.75, -2.5, -4.75, 1.0, 1.5, 2.0, plate(0.58, 76)),
        ]
    star = Part('helm_star', (0.0, -7.75, -4.75), (0, 0, 0), [
        R(-0.25, -1.5, -0.25, 0.5, 3.0, 0.5, star_ray()),                         # langer senkrechter Strahl
        R(-1.25, -0.25, -0.25, 2.5, 0.5, 0.5, star_ray()),                        # kurzer Querstrahl
    ], [Part('helm_star_core', (0, 0, -0.25), (0, 0, 45), [R(-0.5, -0.5, -0.25, 1.0, 1.0, 0.5, gold(0.74))])])
    children = [star]
    for P, C, right in ((PR, R, True), (PL, L, False)):
        s = 'r' if right else 'l'
        # Krone: drei nach hinten gekämmte Eishörner je Seite; links ist das große Horn abgebrochen.
        children.append(horn(P, C, f'helm_horn_a{s}', (-3.75, -8.75, -2.75), (24, -14, -6),
                             [(2.5, 2.0), (2.5, 1.5), (2.0, 1.0), (1.5, 0.5)] if right else [(2.5, 2.0), (1.5, 1.5)],
                             12, broken=not right))
        children.append(horn(P, C, f'helm_horn_b{s}', (-4.25, -7.5, -0.25), (14, -20, -8),
                             [(2.0, 1.5), (2.0, 1.0), (1.5, 0.5)], 12))
        children.append(horn(P, C, f'helm_horn_c{s}', (-4.25, -6.25, 2.25), (4, -22, -6),
                             [(1.5, 1.0), (1.5, 0.5)], 10))
    return [Part('helm_head', cubes=cubes, children=children)]


def chest():
    R = make_c(True)
    body = Part('chest_body', cubes=[
        R(-4.0, 0.0, -2.0, 8.0, 10.5, 4.0, navy(0.48), 0.4),                     # Gambeson darunter
        R(-4.5, -0.5, -2.75, 9.0, 1.5, 5.5, collar()),                            # Halsberge
        R(-4.5, 0.5, -2.75, 9.0, 10.0, 0.5, scaled(0.56, 80, faces=('front',))),  # Brustgrund (kleine Schuppen)
        R(-4.5, 0.5, 2.25, 9.0, 10.0, 0.5, scaled(0.54, 81, faces=('back',), rim=('bottom',))),  # Rückenplatte
        R(-1.0, 0.0, 2.5, 2.0, 10.0, 0.5, spine()),                               # Rückgrat, bleibt vor z 3,0
    ])
    # Brustplatten (Pektoralschilde), außen leicht zurückgedreht, unten leicht vor
    for right in (True, False):
        C, P = make_c(right), make_p(right)
        body.add(P('chest_pec_' + ('r' if right else 'l'), (-0.75, 0.75, -2.75), (-5, 13, 0), [
            C(-4.0, 0.0, -0.5, 4.0, 4.0, 0.5, plate(0.66, 82, rim=('bottom',), glow_rim=True, keel=False)),
            C(-3.5, 0.25, -0.75, 2.5, 1.0, 0.25 * 2, plate(0.74, 83, rim=())),      # Glanzwulst oben
        ]))
    # Bauchschilde (Ventralplatten), jede liegt mit der Unterkante vor der nächsten
    for k, (y0, width, hgt) in enumerate(((4.5, 6.0, 2.0), (6.0, 5.5, 2.0), (7.5, 5.0, 2.0), (9.0, 4.0, 1.5))):
        body.add(Part(f'chest_scute_{k}', (0.0, y0, -2.75), (-11, 0, 0), [
            R(-width / 2, 0.0, -0.5, width, hgt, 0.5, scute(90 + k)),
        ]))
    heart = Part('chest_heart', (0.0, 2.75, -3.25), (0, 0, 0), [
        R(-0.25, -2.5, -0.25, 0.5, 1.5, 0.5, star_ray()),                         # Rautenstern: Strahlen
        R(-0.25, 1.0, -0.25, 0.5, 2.0, 0.5, star_ray()),
        R(-2.25, -0.25, -0.25, 1.0, 0.5, 0.5, star_ray()),
        R(1.25, -0.25, -0.25, 1.0, 0.5, 0.5, star_ray()),
    ], [
        Part('chest_heart_setting', (0, 0, 0), (0, 0, 45), [R(-1.25, -1.25, -0.5, 2.5, 2.5, 0.5, heart_setting())]),
        Part('chest_heart_jewel', (0, 0, -0.5), (0, 0, 45), [R(-0.75, -0.75, -0.5, 1.5, 1.5, 0.5, heart_jewel())]),
    ])
    parts = [body, heart]
    for right in (True, False):
        s = 'r' if right else 'l'
        C, P = make_c(right), make_p(right)
        arm = P(f'chest_arm_{s}', cubes=[
            C(-4.0, -2.75, -2.75, 5.5, 3.0, 5.5, pauldron_shell()),                          # Schulterschale
            C(-3.25, -3.25, -2.25, 4.0, 0.5, 4.5, plate(0.76, 100, rim=())),                 # Wölbung oben
            C(-3.5, 0.0, -2.5, 5.0, 1.5, 5.0, plate(0.6, 101, rim=('top', 'bottom'))),       # Oberarmschiene
            C(-3.0, 1.5, -2.0, 4.0, 0.5, 4.0, navy(0.66, quilt=False), 0.3),                  # Lederwickel (in der Stulpe)
        ], children=[
            # Schulterlamellen außen, nach unten weiter ausgestellt (roll)
            P(f'chest_pauldron_{s}0', (-3.0, -2.5, 0.0), (0, 0, 10), [
                C(-1.5, 0.0, -3.0, 2.5, 2.0, 6.0, scaled(0.62, 102, faces=('right',), rim=('bottom',)))]),
            P(f'chest_pauldron_{s}1', (-3.0, -0.75, 0.0), (0, 0, 17), [
                C(-1.75, 0.0, -2.75, 2.0, 1.5, 5.5, lame(0.62, 103))]),
            P(f'chest_pauldron_{s}2', (-3.25, 0.5, 0.0), (0, 0, 24), [
                C(-1.75, 0.0, -2.5, 1.5, 1.0, 5.0, lame(0.58, 104, period=5))]),
        ])
        # Gefaltete Schwingenplatten: Gelenk hinten oben an der Schulter, drei Finger nach hinten-unten gefächert
        wing = P(f'chest_wing_{s}', (-3.5, -2.5, 1.75), (0, 0, 0), children=[
            P(f'chest_wing_{s}0', (0, 0, 0), (-22, -12, 0), [C(-0.5, -1.0, 0.0, 0.5, 2.0, 4.5, wing_bone(110))]),
            P(f'chest_wing_{s}1', (0, 0.75, 0), (-42, -9, 0), [C(-0.5, -0.75, 0.0, 0.5, 1.5, 3.5, wing_bone(111))]),
            P(f'chest_wing_{s}2', (0, 1.5, -0.25), (-64, -6, 0), [C(-0.5, -0.5, 0.0, 0.5, 1.0, 2.5, wing_bone(112))]),
        ])
        fore = P(f'chest_forearm_{s}', cubes=[
            C(-3.25, 2.0, -2.25, 4.5, 6.5, 4.5, scaled(0.6, 120, faces=('right', 'front', 'back'), rim=('top',)), 0.25),
            C(-3.5, 8.0, -2.5, 5.0, 1.0, 5.0, plate(0.56, 121, rim=('top', 'bottom'))),       # Handgelenkreif
            C(-3.75, 5.0, -0.5, 0.5, 2.5, 1.0, gold(0.6)),                                    # Grat außen
        ])
        parts += [arm, wing, fore]
    return parts


def pauldron_shell():
    """Schulterschale: Perlweiß, unten Goldsaum mit Eislinie, innen (zum Hals) Navy."""
    base = plate(0.68, 105, rim=('bottom',), glow_rim=True)

    def paint(face, x, y, w, h):
        if face == 'left':
            return navy_c(face, x, y, w, h, 0.4, quilt=False)
        return base(face, x, y, w, h)
    return paint


def wing_bone(seed):
    """Schwingenfinger: Perlweiß, Goldkante oben (Vorderkante des Flügels), feine Eislinie darunter."""
    def paint(face, x, y, w, h):
        if face in ('right', 'left'):
            if y == 0:
                return gold_c(face, x, y, w, h, 0.62)
            t = enamel_t(face, x, y, w, h, 0.64, seed)
            t -= 0.12 * (x / max(w - 1, 1))                                 # zur Spitze hin kühler
            c = ramp(PLATE, t)
            if face == 'right' and y == 1 and x < w - 2:
                return (mix(c, hexc('#c4ecff'), 0.5), GLOW_LINE_SOFT)
            return c
        if face == 'top':
            return gold_c(face, x, y, w, h, 0.6)
        return ramp(PLATE, 0.5 + LIGHT[face])
    return paint


def leggings():
    R = make_c(True)
    body = Part('legs_body', cubes=[
        R(-4.75, 10.0, -3.25, 9.5, 2.5, 6.0, belt()),                                         # Gürtel (vorn gewölbt)
        R(-1.0, 10.5, -3.5, 2.0, 1.5, 0.5, buckle()),                                         # Schnalle (zwischen den Tassetten)
        R(-0.75, 12.0, -3.5, 1.5, 2.0, 0.5, plate(0.6, 130, rim=('bottom',))),                # Mittelschild
    ])
    parts = [body]
    for right in (True, False):
        s = 'r' if right else 'l'
        C, P = make_c(right), make_p(right)
        # Gelenk an der Gürteloberkante: so hebt der Oberschenkel die Tassette, ohne hindurchzustechen (siehe
        # ArmorFeature.TASSET_FOLLOW, nachgerechnet bis 1,2 rad Beinschwung).
        parts.append(P(f'legs_tasset_f{s}', (-2.25, 10.0, -3.5), (0, 0, 0), [
            C(-2.0, 0.0, -0.75, 3.5, 3.0, 0.5, lame(0.64, 131)),
            C(-1.75, 2.5, -0.5, 3.0, 3.0, 0.5, lame(0.6, 132)),
        ]))
        parts.append(P(f'legs_leg_{s}', cubes=[
            C(-2.0, 0.0, -2.0, 4.0, 6.0, 4.0, navy(0.56), 0.5),                              # Hose (Leder)
            C(-2.0, 0.5, 2.5, 4.0, 4.0, 0.5, scaled(0.58, 139, faces=('back',), rim=('bottom',))),           # Schenkel hinten
            C(-2.0, 0.5, -3.0, 4.0, 4.5, 0.5, plate(0.62, 133, rim=('bottom',), glow_rim=True, keel=True)),  # Schenkel vorn
            C(-3.0, 0.5, -2.0, 0.5, 4.5, 4.0, scaled(0.6, 134, faces=('right',), rim=('bottom',))),          # außen
            C(-2.0, 6.0, -2.0, 4.0, 6.0, 4.0, shin(), 0.35),                                 # Schienbein (unter den Stiefeln)
            C(-1.75, 5.0, -3.25, 3.5, 2.5, 0.5, knee_cop()),                                 # Kniekachel
            C(-1.5, 7.5, -3.25, 0.5, 1.0, 0.5, claw()),                                      # Krallen
            C(-0.25, 7.5, -3.5, 0.5, 1.5, 0.5, claw()),
            C(1.0, 7.5, -3.25, 0.5, 1.0, 0.5, claw()),
            C(-3.0, 5.25, -1.5, 0.5, 2.0, 2.5, plate(0.6, 135, rim=('top', 'bottom'))),     # Knieflügel außen
        ]))
    parts.append(Part('legs_tasset_b', (0.0, 10.0, 3.25), (0, 0, 0), [
        R(-3.5, 0.0, 0.25, 7.0, 3.0, 0.5, lame(0.6, 136, period=4, outer='back')),
        R(-3.0, 2.5, 0.0, 6.0, 2.5, 0.5, lame(0.56, 137, period=4, outer='back')),
        R(-1.0, 4.5, 0.0, 2.0, 1.5, 0.5, tail_tip()),                                       # Schwanzschuppe
    ]))
    return parts


def shin():
    """Schienbein der Beinlinge: vorn eine schmale Perlweiß-Schiene mit Grat, sonst Navy-Leder (die Stiefel liegen darüber)."""
    front = plate(0.6, 141, rim=('bottom',), keel=True)

    def paint(face, x, y, w, h):
        if face == 'front' or (face == 'right' and 0 < y < h - 1 and 1 <= x < w - 1):
            return front(face, x, y, w, h)
        return navy_c(face, x, y, w, h, 0.52)
    return paint


def belt():
    """Gürtel: Navy-Leder mit einem Perlweiß-Plattenband und Goldkanten."""
    def paint(face, x, y, w, h):
        if face in SIDES:
            if y in (0, h - 1):
                return gold_c(face, x, y, w, h, 0.62)
            if (x // 3) % 2 == 1 and y == h // 2 and face == 'front':
                return ramp(PLATE, 0.5)
            return ramp(PLATE, enamel_t(face, x, y, w, h, 0.62, 140))
        return navy_c(face, x, y, w, h, 0.32, quilt=False)
    return paint


def buckle():
    def paint(face, x, y, w, h):
        if face == 'front':
            cx, cy = (w - 1) / 2, (h - 1) / 2
            if abs(x - cx) + abs(y - cy) < 0.8:
                return (ramp(HEART_RIM, 0.75), GLOW_RIM[:3] + (170,))
            return gold_c(face, x, y, w, h, 0.62)
        return gold_c(face, x, y, w, h, 0.5)
    return paint


def tail_tip():
    def cut(face, x, y, w, h):
        return face in ('front', 'back', 'left', 'right') and y >= h - 2 and abs(x - (w - 1) / 2) > (h - 1 - y) + 0.6
    return plate(0.62, 138, rim=('sides',), cut=cut)


def boots():
    parts = []
    for right in (True, False):
        s = 'r' if right else 'l'
        C, P = make_c(right), make_p(right)
        parts.append(P(f'boots_leg_{s}', cubes=[
            C(-3.0, 6.0, -3.0, 6.0, 1.0, 6.0, cuff()),                                         # Stulpe oben
            C(-2.0, 7.0, -2.0, 4.0, 3.5, 4.0, sabaton_lames(150), 0.75),                       # Beinröhre (Lamellen)
            C(-2.75, 10.5, -3.0, 5.5, 1.5, 6.0, plate(0.6, 151, rim=('top',)), 0.05),        # Fuß
            C(-2.5, 10.75, -4.0, 5.0, 1.5, 1.0, plate(0.66, 152, rim=('top',))),               # Zehenlamelle
            C(-1.0, 11.0, -5.0, 2.0, 1.0, 1.0, plate(0.68, 153, rim=('top',), keel=True)),    # Klauenspitze
            C(-0.5, 11.5, -5.5, 1.0, 0.5, 0.5, claw()),                                      # Mittelkralle
            C(-2.5, 11.5, -4.5, 0.5, 0.5, 0.5, claw()),
            C(1.5, 11.5, -4.5, 0.5, 0.5, 0.5, claw()),
            C(-2.75, 12.0, -4.0, 5.5, 0.5, 7.0, sole()),                                     # Sohle
            C(-0.5, 10.25, 2.75, 1.0, 1.0, 0.5, gold(0.56)),                                   # Fersensporn
        ]))
    return parts


def cuff():
    def paint(face, x, y, w, h):
        if face == 'top':
            if 2 <= x < w - 2 and 2 <= y < h - 2:
                return navy_c(face, x, y, w, h, 0.25, quilt=False)
            return gold_c(face, x, y, w, h, 0.62)
        if face in SIDES:
            return gold_c(face, x, y, w, h, 0.7 if y == 0 else 0.58)
        return navy_c(face, x, y, w, h, 0.3, quilt=False)
    return paint


def sabaton_lames(seed):
    """Beinröhre aus waagerechten Schuppenlamellen: alle 3 Texel eine Fuge mit Goldsaum, vorn ein heller Grat."""
    def paint(face, x, y, w, h):
        if face in ('top', 'bottom'):
            return navy_c(face, x, y, w, h, 0.3, quilt=False)
        band = y % 3
        t = enamel_t(face, x, y, w, h, 0.6, seed) + (0.08 if band == 0 else -0.04 * band)
        if face == 'front' and abs(x - (w - 1) / 2) < 0.6:
            t += 0.12
        if band == 2:
            return gold_c(face, x, y, w, h, 0.5) if face in ('front', 'right') else ramp(PLATE, t - 0.1)
        return ramp(PLATE, t)
    return paint


def sole():
    def paint(face, x, y, w, h):
        return navy_c(face, x, y, w, h, 0.34, quilt=False) if face != 'top' else ramp(PLATE, 0.5)
    return paint


# ---------------------------------------------------------------------------------------------------------------------
# Modell
# ---------------------------------------------------------------------------------------------------------------------
BONE = {
    'helm_head': 'head',
    'chest_body': 'body', 'chest_heart': 'body', 'legs_body': 'body',
    'legs_tasset_fr': 'body', 'legs_tasset_fl': 'body', 'legs_tasset_b': 'body',
    'chest_arm_r': 'right_arm', 'chest_wing_r': 'right_arm', 'chest_forearm_r': 'right_arm',
    'chest_arm_l': 'left_arm', 'chest_wing_l': 'left_arm', 'chest_forearm_l': 'left_arm',
    'legs_leg_r': 'right_leg', 'boots_leg_r': 'right_leg',
    'legs_leg_l': 'left_leg', 'boots_leg_l': 'left_leg',
}
SLOT = {name: name.split('_')[0] for name in BONE}            # helm / chest / legs / boots


# Seit 1.2.2 trägt die Netherite-Form (armor_netherite.py, Vanilla-Equipment-Ebene) die Rüstung; das 3D-Modell zeichnet
# nur noch diese Akzente. Alle anderen Teile bleiben als leere Knochen erhalten (der Renderer erwartet die Namen).
ACCENTS = {'helm_head': lambda p: p.name.startswith('helm_horn'), 'chest_heart': None,
           'chest_wing_r': None, 'chest_wing_l': None}


def accents_only(parts):
    for part in parts:
        keep = ACCENTS.get(part.name, False)
        if keep is None:
            continue
        part.cubes = []
        part.children = [c for c in part.children if keep and keep(c)] if keep else []
    return parts


def armor():
    model = HDModel('armor', 128, 128, accents_only(helmet() + chest() + leggings() + boots()), texture='eirholm_armor')
    _check(model)
    return model


def _bounds(part, rot=None, pos=(0, 0, 0)):
    """Achsenausgerichtete Hülle eines Teils samt Kindern im Knochenraum (Ecken aller Quader, mit Dilation)."""
    from preview_model import apply, mat_mul, rot_zyx
    rot = rot or [[1, 0, 0], [0, 1, 0], [0, 0, 1]]
    origin = apply(rot, part.pivot)
    p = (pos[0] + origin[0], pos[1] + origin[1], pos[2] + origin[2])
    r = mat_mul(rot, rot_zyx(*(math.radians(a) for a in part.rot)))
    pts = []
    for c in part.cubes:
        d = c.dilate
        for cx in (c.x - d, c.x + c.w + d):
            for cy in (c.y - d, c.y + c.h + d):
                for cz in (c.z - d, c.z + c.d + d):
                    q = apply(r, (cx, cy, cz))
                    pts.append((q[0] + p[0], q[1] + p[1], q[2] + p[2]))
    for ch in part.children:
        pts += _bounds(ch, r, p)
    return pts


def _check(model):
    for part in model.parts:
        assert part.name in BONE, f'{part.name}: kein Knochen zugeordnet'
        for p in part.walk():
            for c in p.cubes:
                for v in (c.w, c.h, c.d):
                    assert abs(v * RES - round(v * RES)) < 1e-6, f'{p.name}: Maß {v} nicht auf dem Texelraster'
        pts = _bounds(part)
        if not pts:
            continue  # leerer Knochen (nur Akzente aktiv)
        lo = [min(q[i] for q in pts) for i in range(3)]
        hi = [max(q[i] for q in pts) for i in range(3)]
        if part.name.startswith('chest_arm') or part.name.startswith('chest_wing'):
            # Handschuh-Stulpe beginnt bei y 1,5 (Spitzbogen) bzw. 2,0. Tiefer darf nur, was eng am Arm in der Stulpe
            # verschwindet (x -3,35..1,35 rechts, |z| <= 2,35) oder hinter z 2,8 liegt.
            def hidden(q):
                xs = q[0] if part.name.endswith('_r') or '_r' in part.name[-3:] else -q[0]
                return -3.35 <= xs <= 1.35 and abs(q[2]) <= 2.35
            low = [q for q in pts if q[1] > 1.8 and q[2] < 2.8 and not hidden(q)]
            assert not low, f'{part.name}: ragt in die Handschuh-Stulpe ({max(q[1] for q in low):.2f})'
        if BONE[part.name] == 'body':
            # Umhang (mit Rüstungsversatz) beginnt bei z 3,1 und hängt schräg nach hinten: oben flach, unten mehr Raum.
            top = max((q[2] for q in pts if q[1] < 9.5), default=0)
            low = max((q[2] for q in pts if q[1] >= 9.5), default=0)
            assert top <= 3.05 and low <= 4.05, f'{part.name}: Rücken bei z {top:.2f}/{low:.2f} (Umhang)'
        if part.name == 'helm_head':
            assert lo[1] >= -8.0 - 6.4 - 1e-6, f'Krone zu hoch: y {lo[1]:.2f}'
        _skin_clash(part)
    assert not CLASHES, 'Flächen auf der Hautschicht: ' + '; '.join(f'{n} {ax}={v} {cube}' for n, ax, v, sk, cube in CLASHES)


# Hautschichten des Spielers (Grundkörper und Überzug) je Knochen: x0, x1, y0, y1, z0, z1 (rechte Seite)
_SKIN = {
    'head': [(-4, 4, -8, 0, -4, 4), (-4.5, 4.5, -8.5, 0.5, -4.5, 4.5)],
    'body': [(-4, 4, 0, 12, -2, 2), (-4.25, 4.25, -0.25, 12.25, -2.25, 2.25)],
    'right_arm': [(-3, 1, -2, 10, -2, 2), (-3.25, 1.25, -2.25, 10.25, -2.25, 2.25)],
    'right_leg': [(-2, 2, 0, 12, -2, 2), (-2.25, 2.25, -0.25, 12.25, -2.25, 2.25)],
}


CLASHES = []


def _skin_clash(part):
    """Keine Quaderfläche darf in derselben Ebene liegen wie eine Hautschicht (sonst flimmert es im Spiel)."""
    bone = BONE[part.name].replace('left_', 'right_')
    mirror = BONE[part.name].startswith('left_')
    if any(abs(v) > 1e-6 for v in part.pivot) or part.rot != (0, 0, 0):
        return                                                  # Teile mit Gelenk liegen frei vor dem Körper
    for c in part.cubes:                                        # nur Quader direkt am Teil (Drehpunkt 0)
        d = c.dilate
        x0, x1 = c.x - d, c.x + c.w + d
        if mirror:
            x0, x1 = -x1, -x0
        faces = {'x': (x0, x1), 'y': (c.y - d, c.y + c.h + d), 'z': (c.z - d, c.z + c.d + d)}
        for sk in _SKIN[bone]:
            for ax, (a0, a1) in faces.items():
                k = 'xyz'.index(ax)
                for q, v in ((0, a0), (1, a1)):
                    # gleich ausgerichtete Flächen (beide zeigen nach -/+): die Rüstungsfläche wäre außen sichtbar
                    if abs(v - sk[2 * k + q]) < 1e-6:
                        # nur ein Problem, wenn sich die Flächen auch in den anderen Achsen überdecken
                        others = [i for i in range(3) if i != k]
                        lo_hi = [faces['xyz'[i]] for i in others]
                        if all(min(lh[1], sk[2 * i + 1]) - max(lh[0], sk[2 * i]) > 0.3 for lh, i in zip(lo_hi, others)):
                            CLASHES.append((part.name, ax, v, sk, (c.x, c.y, c.z, c.w, c.h, c.d)))


# ---------------------------------------------------------------------------------------------------------------------
# Vorschau-Posen für preview.py (Knochen in Spielerlage). armor_preview.py zeigt die ganze Figur mit Körper.
# ---------------------------------------------------------------------------------------------------------------------
_OFF = {'head': (0, 0, 0), 'body': (0, 0, 0), 'right_arm': (-5, 2, 0), 'left_arm': (5, 2, 0),
        'right_leg': (-1.9, 12, 0), 'left_leg': (1.9, 12, 0)}
POSES = {'stand': {name: (0, 0, 0) + tuple(_OFF[b]) for name, b in BONE.items()}}
