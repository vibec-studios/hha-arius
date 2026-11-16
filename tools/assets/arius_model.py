"""Arius, die weiße Klinge: ein Skelett, drei Formen, zwei Atlanten gleicher Aufteilung.

Mensch, 19, ca. 185 cm. Modellhöhe 61,5 Einheiten von der Sohle bis zur Haarspitze (ohne Antenne), Renderer-Faktor
SCALE (siehe AriusRenderer.kt) → 1,90 Blöcke. Heroisch, aber realistisch proportioniert (7,5 Köpfe).

Koordinaten: 1/16 Block vor dem Renderer-Faktor, +y nach unten, -z vorn, +x = seine Linke, Sohlen bei y = 24.

Formen (AriusForm): DARK = Eirholm-Rüstung unter dem offenen Mantel, SERIOUS = ohne Mantel, WHITE = weißes Gewand mit
stahlblauer Schärpe. Die Sichtbarkeit steuert FORM_HIDE (Knochen samt Kindern), die Farben steuern zwei Atlanten mit
denselben UVs: `arius_dark.png` (DARK, SERIOUS) und `arius_white.png` (WHITE); jeweils `_glow.png` mit dezenten
Leuchtnähten und der Klingenkante. Die Augen liegen allein in `arius_eyes.png` (Stärke über eyeGlow im Renderer).

Aufrufe (aus dem Projektordner):
    python tools/assets/boss_models.py --only arius      # Texturen + AriusModelData.kt
    python tools/assets/arius_model.py --kotlin           # Schlüsselposen-Block in AriusModel.kt
    ARIUS_FORM=white python tools/assets/preview.py arius out.png ...   # Vorschau einer Form (dark|serious|white)
"""
import math
import os
import re
import sys

from modelkit import Cube as _Cube, Model, Part, rgb, speckle
from texkit import Image, hexc, mix, ramp, save

HERE = os.path.dirname(os.path.abspath(__file__))

# Welche Farbschicht die Maler gerade liefern: 'dark' | 'white' | 'eyes' (siehe variants()).
FORM_NAME = os.environ.get('ARIUS_FORM', 'dark')
MODE = 'white' if FORM_NAME == 'white' else 'dark'
PREVIEW_EYES = os.environ.get('ARIUS_EYES', '') == '1'

# --- Paletten (dunkel -> hell) --------------------------------------------------------------------------------------
SKIN = rgb('#6e4436', '#a7725d', '#d4a289', '#ecc3a8', '#fadfcb')
HAIR_D = rgb('#4a505f', '#7a8091', '#afb4c2', '#d8dbe3', '#f2f3f6', '#ffffff')       # weiß, leicht wirr
HAIR_W = rgb('#1f2433', '#4b536a', '#8890a7', '#c0c7d7', '#e4e8f1', '#f6f8fd')       # kühles Grauweiß, dunkle Strähnen
COAT = rgb('#0d0e13', '#17191f', '#22252d', '#2f323c', '#40444f', '#565b68')         # Anthrazit
LINING = rgb('#0f1828', '#182640', '#24395e', '#325080', '#476ba0')                   # Stahlblau (Innenlage)
ARMOR = rgb('#0a0d14', '#141a25', '#1f2737', '#2d384d', '#41506b', '#61728f')         # Anthrazit mit Stahlblau
GOLD = rgb('#30220f', '#56421f', '#7f6534', '#a88b55', '#cbb27f', '#e8dab4')          # Altgold
PANTS = rgb('#060709', '#0d0e12', '#16181d', '#20232a', '#2c3038')
LEATHER = rgb('#050506', '#0c0d10', '#16171b', '#22242a', '#33363e')
BROWN = rgb('#170f09', '#2a1c13', '#3f2c1d', '#57402a', '#705539')
UNDER = rgb('#0e121a', '#171e2b', '#222b3e', '#2f3b55', '#425272')                    # Untergewand, stahlblauer Stich
ROBE = rgb('#56658a', '#8a98b6', '#bcc6d6', '#e3e5e4', '#f4f1e8', '#fffcf4')          # Kalkweiß, blaue Faltenschatten
SASH = rgb('#101c33', '#1b2d52', '#284679', '#3b5f9c', '#5680bd')
BLADE = rgb('#76829a', '#adb7c8', '#dbe2ec', '#f1f5fa', '#ffffff')
METAL = rgb('#202530', '#3a4251', '#5c6576', '#8790a2', '#b9c1cf')

ICE = (130, 205, 255)
SEAM = (hexc('#4f86b8'), (ICE[0], ICE[1], ICE[2], 85))
EYE_GOLD, EYE_GOLD_GLOW = hexc('#f0b23a'), (255, 192, 74, 255)
EYE_BLUE, EYE_BLUE_GLOW = hexc('#7fd2ff'), (130, 214, 255, 255)

LIGHT = {'top': 0.14, 'bottom': -0.22, 'front': 0.0, 'back': -0.08, 'left': -0.05, 'right': -0.05}
SIDES = ('front', 'back', 'left', 'right')


# --- Maler -----------------------------------------------------------------------------------------------------------
def C(x, y, z, w, h, d, paint, dilate=0.0):
    """Der Atlas rechnet in ganzen Texeln; Positionen dürfen halbe Einheiten haben."""
    return _Cube(x, y, z, max(1, round(w)), max(1, round(h)), max(1, round(d)), paint, dilate)


def block(x, y, z, w, h, d, paint, bevel=0.5, side=None):
    """Quader mit gefasten senkrechten Kanten (Kreuz aus zwei Quadern, der zweite minimal gedehnt gegen Flimmern)."""
    return [C(x + bevel, y, z, w - 2 * bevel, h, d, paint),
            C(x, y, z + bevel, w, h, d - 2 * bevel, side or paint, dilate=0.02)]


def by_mode(dark, white=None):
    """Maler je Atlas: dunkler Atlas (DARK/SERIOUS) oder weißer Atlas (WHITE); im Augen-Durchgang leer."""
    def paint(face, x, y, w, h):
        if MODE == 'eyes':
            return None
        return (white if (white is not None and MODE == 'white') else dark)(face, x, y, w, h)
    return paint


def surf(pal, base=0.55, grad=0.16, seed=0, roundx=0.0, ridge=0.0, noise=0.03):
    """Glatte Fläche: Flächenlicht, Verlauf von oben nach unten, leichtes Korn, optional Rundung und Lichtkante."""
    def paint(face, x, y, w, h):
        v = base + LIGHT[face] + (0.5 - y / max(h - 1, 1)) * grad + (speckle(x, y, seed) - 0.5) * noise * 2
        if roundx and face in SIDES and w > 2:
            k = (x + 0.5) / w * 2 - 1
            v += roundx * (0.35 - k * k)
        if ridge and face in SIDES and y == 0:
            v += ridge
        return ramp(pal, v)
    return paint


def fabric(pal, base=0.5, seed=0, folds=0.09, freq=1.35, grad=0.12, noise=0.04):
    """Stoff mit weichen senkrechten Falten."""
    def paint(face, x, y, w, h):
        v = base + LIGHT[face] + (0.5 - y / max(h - 1, 1)) * grad + (speckle(x, y, seed) - 0.5) * noise * 2
        if face in SIDES:
            v += folds * math.sin(x * freq + seed * 0.7)
        return ramp(pal, v)
    return paint


def robe(seed=0, base=0.66, hem_bottom=False, hem_top=False, folds=0.13):
    """Weißes Gewand: warmes Kalkweiß, Falten mit blauem Schatten, goldene Säume."""
    def paint(face, x, y, w, h):
        if face in SIDES and ((hem_bottom and y == h - 1) or (hem_top and y == 0)):
            return ramp(GOLD, 0.66 + LIGHT[face] - (0.12 if x % 5 == 2 else 0.0))
        v = base + LIGHT[face] + (0.5 - y / max(h - 1, 1)) * 0.1 + (speckle(x, y, seed) - 0.5) * 0.05
        if face in SIDES:
            v += folds * math.sin(x * 1.25 + seed * 0.9) - (0.08 if (x + seed) % 4 == 0 else 0.0)
        return ramp(ROBE, v)
    return paint


def gold(base=0.62, seed=0, notch=0):
    def paint(face, x, y, w, h):
        v = base + LIGHT[face] + (0.12 if (y == 0 and face in SIDES) else 0.0) + (speckle(x, y, seed) - 0.5) * 0.06
        if notch and face in SIDES and x % notch == notch // 2:
            v -= 0.16
        return ramp(GOLD, v)
    return paint


def star_px(x, y, w, h, big=True):
    """Vierstern-Motiv, mittig auf der Fläche. 2 = Kern, 1 = Strahl, 0 = nichts."""
    cx, cy = (w - 1) / 2.0, (h - 1) / 2.0
    dx, dy = abs(x - cx), abs(y - cy)
    if dx < 0.6 and dy < 0.6:
        return 2
    if big:
        if (dx < 0.6 and dy < 3.1) or (dy < 0.6 and dx < 3.1) or (dx < 1.1 and dy < 1.1):
            return 1
    elif (dx < 0.6 and dy < 2.1) or (dy < 0.6 and dx < 2.1):
        return 1
    return 0


def plate(seed=0, base=0.5, trim_top=0, trim_bottom=1, seam=True, star=None, big_star=True, edge=0.15):
    """Eirholm-Platte: Anthrazit mit stahlblauem Unterton, Grat an den Kanten, Altgold-Saum, eisblaue Leuchtnaht."""
    def paint(face, x, y, w, h):
        if face in SIDES:
            if (trim_bottom and y >= h - trim_bottom) or (trim_top and y < trim_top):
                return ramp(GOLD, 0.6 + LIGHT[face] + (0.1 if y in (0, h - 1) else 0.0) - (0.14 if x % 4 == 1 else 0.0))
            if seam and h >= 4 and ((trim_bottom and y == h - trim_bottom - 1) or (trim_top and y == trim_top)):
                if x % 5 != 2 and 0 < x < w - 1:
                    return SEAM
            if star and face == star:
                s = star_px(x, y, w, h, big_star)
                if s:
                    return ramp(GOLD, 0.78 if s == 2 else 0.6 + LIGHT[face])
        v = base + LIGHT[face] + (0.5 - y / max(h - 1, 1)) * 0.18 + (speckle(x, y, seed) - 0.5) * 0.08
        if x in (0, w - 1) or y == 0:
            v += edge
        elif x in (1, w - 2):
            v -= edge * 0.3
        return ramp(ARMOR, v)
    return paint


def hair_paint(seed=0, base=0.62, tips=False):
    """Haar in Strähnen: helle Bahnen, dunkle Fugen; die weiße Form bekommt kühleres Grau und tiefere Strähnenschatten."""
    def paint(face, x, y, w, h):
        white = MODE == 'white'
        pal = HAIR_W if white else HAIR_D
        v = base + LIGHT[face] * 1.2 + 0.11 * math.sin(x * 1.9 + y * 0.5 + seed) + (speckle(x, y, seed) - 0.5) * 0.08
        if face in SIDES:
            v += (0.5 - y / max(h - 1, 1)) * 0.16
        if (x * 3 + y + seed) % 5 == 0:
            v -= 0.28 if white else 0.17                     # Strähnenschatten
        if tips and face in SIDES and y == h - 1:
            v -= 0.12
        return ramp(pal, v)
    return by_mode(paint, paint)


# --- Gesicht und Augen -------------------------------------------------------------------------------------------
def eye_texel(which):
    """Iris: Albedo gold/blau, im Augen-Durchgang die Leuchtfarbe (eigene Textur arius_eyes.png)."""
    col, glow = (EYE_GOLD, EYE_GOLD_GLOW) if which == 'gold' else (EYE_BLUE, EYE_BLUE_GLOW)
    if MODE == 'eyes' or PREVIEW_EYES:
        return (col, glow)
    return col


def face_upper(face, x, y, w, h):
    """Schädel 6 x 6 vorn: Stirn unter dem Pony, ruhige Brauen, rechtes Auge golden (Texel 1), linkes eisblau (4)."""
    if face == 'front' and y == 3 and x in (1, 4):
        return eye_texel('gold' if x == 1 else 'blue')
    if MODE == 'eyes':
        return None
    hair = HAIR_W if MODE == 'white' else HAIR_D
    if face in ('top', 'back'):
        return ramp(hair, 0.55 + 0.1 * math.sin(x * 1.7))
    if face != 'front':
        return ramp(hair, 0.5) if y < 3 else ramp(SKIN, 0.5 + LIGHT[face] - 0.04 * y)
    skin = 0.66 - (0.1 if x in (0, w - 1) else 0.0) - 0.02 * y
    if y <= 1:
        return ramp(hair, 0.6 + 0.12 * math.sin(x * 2.3)) if (y == 0 or x not in (2, 4)) else ramp(SKIN, 0.6)
    if y == 2:
        if x in (0, 1, 4, 5):
            return ramp(hair, 0.32 if x in (1, 4) else 0.42)     # Brauen: hell, ruhig, leicht gesenkt
        return ramp(SKIN, skin)
    if y == 3:
        if x in (0, 5):
            return hexc('#e9edf2')                             # Augenweiß außen, Blick nach vorn
        return ramp(SKIN, skin - 0.06)                          # Nasenwurzel
    if y == 4:
        if x in (0, 1, 4, 5):
            return ramp(SKIN, skin - 0.05)                      # Lidschatten unter dem Auge
        return ramp(SKIN, skin + 0.03)
    return ramp(SKIN, skin - (0.06 if x in (2, 3) else 0.0))


def face_lower(face, x, y, w, h):
    """Kiefer 5 x 2 vorn: schmaler, ruhiger Mund, Kinn."""
    if MODE == 'eyes':
        return None
    if face != 'front':
        return ramp(SKIN, 0.48 + LIGHT[face] - 0.06 * y)
    if w == 5:
        if x == 2:
            return hexc('#b98276')                              # ruhiger, schmaler Mund
        return ramp(SKIN, 0.6 - (0.08 if x in (0, w - 1) else 0.0))
    return ramp(SKIN, 0.54 - (0.06 if x in (0, w - 1) else 0.0))


face_upper.is_face = True


# --- Kleidung, Rüstung, Leder -------------------------------------------------------------------------------------
def under(seed=0, base=0.5, part='torso'):
    """Untergewand (dunkler Atlas) bzw. weißes Gewand (weißer Atlas) an Rumpf und Armen."""
    dark = fabric(UNDER, base, seed, folds=0.06)

    def white(face, x, y, w, h):
        if part == 'chest' and face == 'front':
            cx = (w - 1) / 2.0
            # V-Ausschnitt mit Goldsaum und überlappender Gewandkante (links über rechts).
            if y <= 2 and abs(x - cx) < 1.6 - y * 0.5:
                return ramp(LINING, 0.45)
            if y <= 3 and abs(abs(x - cx) - (1.6 - y * 0.5)) < 0.75:
                return ramp(GOLD, 0.66)
            if y > 2 and abs(x - (cx - 0.6 + (y - 3) * 0.32)) < 0.55:
                return ramp(GOLD, 0.6)
        if part == 'sleeve_low' and face in SIDES and y >= h - 1:
            return ramp(GOLD, 0.62 + LIGHT[face])
        return robe(seed, 0.66)(face, x, y, w, h)
    return by_mode(dark, white)


def pants(seed=0, base=0.5):
    p = fabric(PANTS, base, seed, folds=0.07, freq=1.1)
    return by_mode(p, p)


def leather(seed=0, base=0.5, pal=LEATHER, top_rim=False):
    def paint(face, x, y, w, h):
        v = base + LIGHT[face] * 1.2 + (0.5 - y / max(h - 1, 1)) * 0.12 + (speckle(x, y, seed) - 0.5) * 0.08
        if top_rim and face in SIDES and y == 0:
            v += 0.2
        return ramp(pal, v)
    return by_mode(paint, paint)


def boot_foot(seed=0):
    def paint(face, x, y, w, h):
        if face == 'bottom':
            return ramp(BROWN, 0.25)
        if face in SIDES and y == h - 1:
            return ramp(BROWN, 0.3 + LIGHT[face])               # Sohle
        v = 0.48 + LIGHT[face] * 1.2 + (speckle(x, y, seed) - 0.5) * 0.08
        if face == 'top' and y < 3:
            v += 0.12                                           # Spann glänzt
        return ramp(LEATHER, v)
    return by_mode(paint, paint)


def glove(seed=0, knuckles=None, cuff=False):
    """Schwarz verstärkter Handschuh: Lederfläche, auf der Außenseite eine Reihe Metall-Knöchelplatten."""
    def paint(face, x, y, w, h):
        if knuckles and face == knuckles and y >= h - 2 and x % 2 == 0:
            return ramp(METAL, 0.55 + LIGHT[face] + (0.15 if y == h - 2 else 0.0))
        if cuff and face in SIDES and y == 0:
            return ramp(LEATHER, 0.7)
        v = 0.46 + LIGHT[face] * 1.2 + (0.5 - y / max(h - 1, 1)) * 0.1 + (speckle(x, y, seed) - 0.5) * 0.08
        if face in SIDES and y % 2 == 1 and not cuff:
            v -= 0.06
        return ramp(LEATHER, v)
    return by_mode(paint, paint)


def mark_07(face, x, y, w, h):
    """„07“ auf dem linken Handrücken (Platte mit doppelter Texeldichte, siehe glove_mark)."""
    if MODE == 'eyes':
        return None
    if face == 'left':
        digits = ['###', '#.#', '#.#', '#.#', '###'], ['###', '..#', '.#.', '.#.', '.#.']
        if 0 <= y < 5 and 0 <= x < 6:
            d = digits[0] if x < 3 else digits[1]
            if d[y][x % 3] == '#':
                return ramp(METAL, 0.85)
    return ramp(LEATHER, 0.4 + LIGHT[face])


def belt_paint(seed=0):
    def dark(face, x, y, w, h):
        if face == 'front' and abs(x - (w - 1) / 2.0) < 1.1:
            return ramp(GOLD, 0.7 if y == 0 else 0.55)
        v = 0.5 + LIGHT[face] + (0.12 if y == 0 else -0.05) + (speckle(x, y, seed) - 0.5) * 0.08
        return ramp(BROWN, v)

    def white(face, x, y, w, h):
        if face in SIDES and y in (0, h - 1):
            return ramp(GOLD, 0.62 + LIGHT[face])
        return ramp(SASH, 0.55 + LIGHT[face] + 0.1 * math.sin(x * 1.1 + seed))
    return by_mode(dark, white)


def strap_paint(seed=0):
    def paint(face, x, y, w, h):
        v = 0.5 + LIGHT[face] + (0.15 if x == 0 else 0.0) + (speckle(x, y, seed) - 0.5) * 0.1
        if y % 6 == 3 and face in ('front', 'back'):
            return ramp(GOLD, 0.6)                              # Nieten
        return ramp(BROWN, v)
    return by_mode(paint, paint)


def coat_paint(seed=0, outward='back', hem=False, tatter=False, lining_col=None, lapel=False, white_hem=False):
    """Reisemantel: Anthrazit mit weichen Falten, innen stahlblau, Altgold-Saum, zerfranster Saum (durchsichtige Texel).
    Im weißen Atlas wird daraus der Gewandrock (Kalkweiß, Goldsaum unten am oberen Glied)."""
    inward = {'back': 'front', 'front': 'back', 'left': 'right', 'right': 'left'}[outward]

    def jag(x, y, w, h):
        if not tatter:
            return False
        depth = h - 1 - y
        n = speckle(x * 3 + seed, 7, seed % 11)
        return depth < 3 and n < (0.75 - depth * 0.24) + 0.18 * ((x + seed) % 2)

    def dark(face, x, y, w, h):
        xx = (w - 1 - x) if face in ('back', 'left') else x
        if face in SIDES and jag(xx, y, w, h):
            return None
        if face == 'bottom' and tatter:
            return None
        if face == inward:
            return ramp(LINING, 0.42 + 0.08 * math.sin(x * 1.4 + seed) + (speckle(x, y, seed) - 0.5) * 0.06)
        if lapel and face == outward and lining_col is not None and x == lining_col:
            return ramp(LINING, 0.55)
        if face in SIDES and hem and (y == h - 1 or (tatter and jag(xx, y + 1, w, h))):
            return ramp(GOLD, 0.56 + LIGHT[face])
        if lapel and face == outward and lining_col is not None and abs(x - lining_col) == 1:
            return ramp(GOLD, 0.6)
        v = 0.5 + LIGHT[face] + (0.5 - y / max(h - 1, 1)) * 0.1 + 0.08 * math.sin(xx * 1.3 + seed) + (speckle(x, y, seed) - 0.5) * 0.08
        return ramp(COAT, v)

    def white(face, x, y, w, h):
        return robe(seed, 0.66, hem_bottom=white_hem)(face, x, y, w, h)
    return by_mode(dark, white)


def collar_paint(seed=0, inward='front'):
    def paint(face, x, y, w, h):
        if face == inward:
            return ramp(LINING, 0.48 + 0.06 * math.sin(x + seed))
        if face in SIDES and y == 0:
            return ramp(GOLD, 0.66 + LIGHT[face])
        if face == 'top':
            return ramp(GOLD, 0.6)
        v = 0.52 + LIGHT[face] + (0.5 - y / max(h - 1, 1)) * 0.12 + (speckle(x, y, seed) - 0.5) * 0.07
        return ramp(COAT, v)
    return by_mode(paint, paint)


def sleeve_coat(seed=0, cuff=False):
    def paint(face, x, y, w, h):
        if cuff and face in SIDES and y == h - 1:
            return ramp(GOLD, 0.58 + LIGHT[face])
        if cuff and face == 'bottom':
            return ramp(LINING, 0.4)
        v = 0.5 + LIGHT[face] + (0.5 - y / max(h - 1, 1)) * 0.12 + 0.07 * math.sin(x * 1.6 + y * 0.35 + seed)
        v += (speckle(x, y, seed) - 0.5) * 0.08
        if face in SIDES and (y + x) % 7 == 0:
            v -= 0.12                                           # Knitterfalten
        return ramp(COAT, v)
    return by_mode(paint, paint)


def sash_paint(seed=0):
    def paint(face, x, y, w, h):
        if face in SIDES and (x == 0 or x == w - 1):
            return ramp(GOLD, 0.6 + LIGHT[face])
        if face in SIDES and y == h - 1:
            return ramp(GOLD, 0.56)
        return ramp(SASH, 0.55 + LIGHT[face] + 0.12 * math.sin(y * 0.9 + seed) + (speckle(x, y, seed) - 0.5) * 0.06)
    return by_mode(paint, paint)


# --- Solvane --------------------------------------------------------------------------------------------------------
def blade_paint(seg):
    """Weiße Klinge, feine blaue Kante (leuchtet leicht): Kantenreihen auf den Flachseiten und die Schneidenflächen."""
    def paint(face, x, y, w, h):
        if face in ('top', 'bottom'):
            return (hexc('#a9dcff'), (ICE[0], ICE[1], ICE[2], 110))       # die Schneiden selbst
        if face in ('front', 'back'):
            return ramp(BLADE, 0.8)
        along = x if face == 'right' else w - 1 - x                         # 0 = Spitzenseite
        if h >= 3 and y in (0, h - 1):
            return hexc('#d6ecfb')
        v = 0.74 + (0.1 if face == 'left' else 0.0) + 0.05 * math.sin(along * 0.4)
        if h >= 3 and seg == 0 and along > w - 4:
            return ramp(GOLD, 0.66)                                         # goldene Ricasso-Einlage am Heft
        if h >= 3 and seg == 0 and y == 1 and along > 3:
            v -= 0.12                                                       # Hohlkehle
        return ramp(BLADE, v)
    return by_mode(paint, paint)


def grip_paint(face, x, y, w, h):
    if MODE == 'eyes':
        return None
    if face in ('front', 'back'):
        return ramp(LEATHER, 0.4)
    k = (x + y) % 3
    return ramp(LEATHER, 0.3 + (0.25 if k == 0 else 0.0) + LIGHT[face])   # gewickelt


def guard_paint(face, x, y, w, h):
    if MODE == 'eyes':
        return None
    v = 0.64 + LIGHT[face] + (0.14 if face in ('left', 'right') and y in (0, h - 1) else 0.0)
    if face in ('left', 'right') and 0 < y < h - 1 and abs(y - (h - 1) / 2.0) > 1.5:
        v -= 0.1 * (y % 2)
    return ramp(GOLD, v)


def gem_paint(face, x, y, w, h):
    """Raute in der Parierstange: Vierstern-Kerbe auf den Flachseiten."""
    if MODE == 'eyes':
        return None
    cx = (w - 1) / 2.0
    if face in ('left', 'right'):
        if abs(x - cx) < 0.6 or abs(y - cx) < 0.6:
            return ramp(GOLD, 0.84)
        return ramp(GOLD, 0.52)
    return ramp(GOLD, 0.62 + LIGHT[face])


def scabbard_paint(seed=0):
    """Dunkle Scheide mit feiner Goldlinie und Rautenmotiv; Flachseiten = left/right (lokal x)."""
    def paint(face, x, y, w, h):
        if face in ('left', 'right'):
            along = x if face == 'left' else w - 1 - x
            cy = (h - 1) / 2.0
            if abs(along - 13) + abs(y - cy) * 2.0 < 2.2:
                return ramp(GOLD, 0.66 if abs(along - 13) < 0.6 else 0.5)    # Raute
            if (along in (3, 4, 22, 23) and abs(y - cy) < 1.1) or (5 < along < 11 and abs(y - cy) < 0.6 and along % 2):
                return ramp(GOLD, 0.48)
        v = 0.42 + LIGHT[face] + (speckle(x, y, seed) - 0.5) * 0.08 + (0.08 if y in (0, h - 1) else 0.0)
        return ramp(COAT, v)
    return by_mode(paint, paint)


# --- Bau -------------------------------------------------------------------------------------------------------------
def tuft(name, pivot, rot, w=2, l=3, d=2, seed=0):
    """Haarsträhne als gedrehte Spitze: Ansatz w x d, verjüngt sich nach oben (-y)."""
    return Part(name, pivot, rot, cubes=[
        C(-w / 2.0, -l, -d / 2.0, w, l, d, hair_paint(seed, 0.64)),
        C(-max(1, w - 1) / 2.0, -l - 1.5, -max(1, d - 1) / 2.0, max(1, w - 1), 2, max(1, d - 1), hair_paint(seed + 1, 0.7, tips=True)),
    ])


def head_part():
    skin = by_mode(surf(SKIN, 0.6, 0.1, 1, roundx=0.2))
    nose = by_mode(surf(SKIN, 0.68, 0.2, 2))
    hair_top = hair_paint(3, 0.7)
    hair_side = hair_paint(4, 0.6)
    hair_back = Part('hair_back', (0, -4.0, 4.2), cubes=[
        C(-3, -1, -0.8, 6, 3, 2, hair_paint(5, 0.55)),
        C(-2.6, 1.8, -0.6, 2, 2, 1, hair_paint(6, 0.5, tips=True)),
        C(0.4, 1.6, -0.6, 2, 2, 1, hair_paint(7, 0.5, tips=True)),
        C(-0.8, 2.4, -0.4, 1, 2, 1, hair_paint(8, 0.48, tips=True)),
    ])
    antenna = Part('antenna', (0.6, -11.2, -0.6), (-12, 0, 14), cubes=[C(-0.5, -3, -0.5, 1, 3, 1, hair_paint(9, 0.75))],
                   children=[Part('antenna_1', (0, -3, 0), (-60, 0, 0), cubes=[C(-0.5, -2.5, -0.5, 1, 3, 1, hair_paint(10, 0.8))])])
    tufts = [
        tuft('tuft_0', (-3.4, -8.2, -2.4), (-8, 0, -118), 2, 3, 2, 11),       # Schläfen-Flügel, nach außen unten
        tuft('tuft_1', (3.4, -8.2, -2.4), (-8, 0, 118), 2, 3, 2, 12),
        tuft('tuft_2', (-2.8, -9.4, 2.0), (62, 0, -48), 2, 3, 2, 13),         # Hinterkopf, nach hinten außen
        tuft('tuft_3', (2.8, -9.4, 2.2), (66, 0, 46), 2, 3, 2, 14),
        tuft('tuft_4', (0.0, -10.2, 3.0), (78, 0, 0), 3, 3, 2, 15),            # Wirbel hinten
        tuft('tuft_5', (-3.8, -5.0, 0.8), (8, 0, -162), 2, 2, 2, 16),         # über den Ohren, nach unten
        tuft('tuft_6', (3.8, -5.0, 0.8), (8, 0, 162), 2, 2, 2, 17),
        tuft('tuft_7', (-1.6, -10.4, -3.0), (-62, 0, -14), 2, 2, 2, 18),      # Pony hebt sich vorn
        tuft('tuft_8', (1.8, -10.4, -2.8), (-56, 0, 20), 2, 2, 2, 19),
        tuft('tuft_9', (-2.4, -4.4, 3.8), (152, 0, -22), 2, 2, 2, 20),        # Nackenspitzen
        tuft('tuft_10', (2.4, -4.4, 3.8), (150, 0, 24), 2, 2, 2, 21),
    ]
    return Part('head', (0, -12, 0), cubes=[
        C(-3, -9, -3.5, 6, 6, 7, face_upper),                 # Schädel, vorn das Gesicht
        C(-2.5, -3, -3.3, 5, 1, 6, face_lower),               # Oberkiefer mit Mund
        C(-1.5, -2, -3.2, 3, 1, 5, face_lower),               # Kinn
        C(-0.5, -4.6, -3.9, 1, 1, 1, nose),                   # Nase
        C(-3.5, -6, -0.5, 1, 2, 1, skin), C(2.5, -6, -0.5, 1, 2, 1, skin),     # Ohren
        C(-3.5, -10, -4, 7, 2, 8, hair_top),                  # Haarkappe
        C(-2.5, -10.7, -3, 5, 1, 6, hair_top),                # Fülle auf dem Scheitel
        C(-3.5, -9, 3.2, 7, 6, 2, hair_side),                 # Hinterkopf
        C(-3, -3.4, 3.0, 6, 2, 2, hair_side),                 # Nacken
        C(-4, -9, -3, 1, 5, 7, hair_side), C(3, -9, -3, 1, 5, 7, hair_side),   # Schläfen
        C(-3.2, -9, -4.3, 2, 3, 1, hair_paint(22, 0.66, tips=True)),           # Pony rechts (über der Braue)
        C(-0.6, -9, -4.4, 1, 4, 1, hair_paint(23, 0.7, tips=True)),            # Strähne zwischen den Augen
        C(1.2, -9, -4.3, 2, 2, 1, hair_paint(24, 0.66, tips=True)),            # Pony links
        C(2.4, -8.5, -4.2, 1, 3, 1, hair_paint(25, 0.6, tips=True)),
    ], children=[hair_back, antenna] + tufts)


def hilt_cubes(mirror=False):
    """Heft von Solvane, Ursprung = Griffmitte, Klinge Richtung -z. mirror: Klinge Richtung +z (in der Scheide)."""
    cubes = [
        C(-1, -1, -2, 2, 2, 8, grip_paint),                   # Griff, schwarz gewickelt
        C(-1, -4.5, -3, 2, 9, 1, guard_paint),                # Parierstange
        C(-1, -5.5, -3.5, 2, 1, 2, guard_paint), C(-1, 4.5, -3.5, 2, 1, 2, guard_paint),   # Enden
        C(-1.5, -1.5, 5.5, 3, 3, 1, by_mode(gold(0.6, 30))),  # Knaufring
    ]
    if mirror:
        for c in cubes:
            c.z = -(c.z + c.d)
    gem = Part('gem', (0, 0, 2.5 if mirror else -2.5), (45, 0, 0), cubes=[C(-1.5, -1.5, -1.5, 3, 3, 3, gem_paint)])
    pommel = Part('pommel', (0, 0, -8 if mirror else 8), (45, 0, 0), cubes=[
        C(-1.5, -1.5, -1.5, 3, 3, 3, by_mode(gold(0.66, 31))),
        C(-0.5, -0.5, -2.6 if not mirror else 1.6, 1, 1, 1, by_mode(gold(0.8, 32))),
    ])
    return cubes, [gem, pommel]


def solvane_part():
    cubes, kids = hilt_cubes()
    cubes += [
        C(-0.5, -1.5, -24, 1, 3, 21, blade_paint(0)),
        C(-0.5, -1, -30, 1, 2, 6, blade_paint(1)),
        C(-0.5, -0.5, -33, 1, 1, 3, blade_paint(2)),
    ]
    return Part('solvane', PALM, cubes=cubes, children=kids)


def scabbard_part():
    cubes, kids = hilt_cubes(mirror=True)
    hilt = Part('sheath_hilt', (0, 0, -4.0), cubes=cubes, children=kids)
    band = by_mode(gold(0.62, 40))
    return Part('scabbard', SCABBARD_MOUTH, SCABBARD_ROT, cubes=[
        C(-1, -2, 0, 2, 4, 28, scabbard_paint(41)),
        C(-1.5, -2.5, -1, 3, 5, 2, band),                     # Mundblech
        C(-1.5, -2.5, 9, 3, 5, 1, leather(42, 0.5, BROWN)),  # Trageriemen
        C(-1.5, -2.5, 18, 3, 5, 1, leather(43, 0.5, BROWN)),
        C(-1, -1.5, 28, 2, 3, 3, band),                       # Ortblech
        C(-0.5, -1, 31, 1, 2, 1, band),
    ], children=[hilt])


def hand_parts(s):
    side = -1 if s == 'r' else 1
    outer = 'right' if s == 'r' else 'left'
    fist = Part(f'fist_{s}', cubes=[
        C(-1.5, 0, -2, 3, 3, 4, glove(50 + side, knuckles=outer)),
        C(-1.0, 0.4, -2.7, 2, 2, 1, glove(52)),               # Daumen über dem Griff
        C(-1.25 if s == 'r' else -0.75, 2.4, -1.75, 2, 1, 4, glove(53)),     # eingerollte Finger
    ])
    if s == 'r':
        return [fist]
    open_ = Part('open_l', cubes=[
        C(-0.5, 0, -2, 1, 3, 4, glove(54)),                   # Handteller
        C(-0.5, 3, -2, 1, 3, 4, glove(55)),                   # Finger
        C(-0.5, 0.8, -3.2, 1, 2, 1, glove(56)),               # Daumen
    ])
    point = Part('point_l', cubes=[
        C(-1.5, 0, -2, 3, 3, 4, glove(57, knuckles='left')),
        C(-0.5, 2.6, -2, 1, 4, 2, glove(58)),                 # Zeige- und Mittelfinger gestreckt
        C(-1.0, 0.4, -2.7, 2, 2, 1, glove(59)),
    ])
    # „07“: 4 x 5 x 6 Texel, um 1,5 geschrumpft → 1 x 2 x 3 Einheiten mit doppelter Texeldichte (im Spiel).
    mark = Part('glove_mark', cubes=[C(-0.1, -0.9, -2.5, 4, 5, 6, mark_07, dilate=-1.5)])
    return [fist, open_, point, mark]


def arm_part(s):
    side = -1 if s == 'r' else 1
    sleeve = under(60 + side, 0.5, 'sleeve')
    sleeve_low = under(62 + side, 0.48, 'sleeve_low')
    hand = Part(f'hand_{s}', (0, 10, 0), children=hand_parts(s) + ([solvane_part()] if s == 'r' else []))
    fore = Part(f'fore_{s}', (0, 11, 0), cubes=[
        *block(-2, 0, -2, 4, 5, 4, sleeve),
        C(-1.5, 4.5, -1.5, 3, 6, 3, sleeve_low),
        *block(-2, 7, -2, 4, 4, 4, glove(64, cuff=True)),     # Handschuhstulpe
    ], children=[
        hand,
        Part(f'armor_bracer_{s}', cubes=[C(-2, 1.5, -2, 4, 5, 4, by_mode(plate(65, 0.5, 1, 1)), dilate=0.3)]),
        Part(f'coat_cuff_{s}', cubes=[C(-2.5, -0.5, -2.5, 5, 5, 5, sleeve_coat(66, cuff=True), dilate=0.05)]),
    ])
    star = 'left' if s == 'l' else None
    shoulder = Part(f'armor_shoulder_{s}', (side * 0.4, -0.5, 0), (0, 0, side * -9), cubes=[
        C(-3, -2.8, -3, 6, 3, 6, by_mode(plate(67, 0.55, 0, 0, seam=False))),
        C(-3.5, -0.5, -3.5, 7, 3, 7, by_mode(plate(68, 0.5, 0, 1, star=star, big_star=False))),
        C(-3.5, 2.0, -3.5, 7, 2, 7, by_mode(plate(69, 0.45, 0, 1)), dilate=-0.2),
    ])
    return Part(f'arm_{s}', (side * 6.5, -9, 0), cubes=[
        C(-2.5, -2, -2.5, 5, 4, 5, sleeve),                   # Schulterkugel
        *block(-2, 0, -2, 4, 11, 4, sleeve),                  # Oberarm
    ], children=[
        fore,
        Part(f'coat_sleeve_{s}', cubes=[C(-2.5, -1.5, -2.5, 5, 12, 5, sleeve_coat(70), dilate=0.12)]),
        shoulder,
    ])


def leg_part(s):
    side = -1 if s == 'r' else 1
    foot = Part(f'foot_{s}', (0, 13, 0), cubes=[
        C(-2.5, 0, -5.5, 5, 3, 8, boot_foot(80 + side)),
        C(-2, -0.5, 1.5, 4, 3, 2, boot_foot(81)),             # Ferse
    ], children=[Part(f'armor_foot_{s}', cubes=[
        C(-2.5, -0.5, -5.8, 5, 2, 4, by_mode(plate(82, 0.5, 0, 0, seam=False)), dilate=0.2),
        C(-2.5, -1.2, -2.5, 5, 2, 5, by_mode(plate(83, 0.48, 1, 0)), dilate=0.35),
    ])])
    shin = Part(f'shin_{s}', (0, 14, 0), cubes=[
        *block(-2, 0, -2, 4, 8, 4, pants(84)),
        C(-2.5, 5, -2.5, 5, 3, 5, pants(85, 0.45)),           # Hose bauscht über dem Stiefel
        *block(-2.5, 8, -2.5, 5, 5, 5, leather(86, 0.5, top_rim=True)),   # Stiefelschaft
    ], children=[
        foot,
        Part(f'armor_knee_{s}', cubes=[C(-2.5, -1.8, -3.3, 5, 3, 2, by_mode(plate(87, 0.6, 0, 1, seam=False)))]),
        Part(f'armor_greave_{s}', cubes=[C(-2.5, 1.5, -2.5, 5, 9, 5, by_mode(plate(88, 0.5, 0, 1, star='front', big_star=False)), dilate=0.3)]),
    ])
    return Part(f'thigh_{s}', (side * 2.5, 0, 0), cubes=[
        *block(-2.5, -1, -2.5, 5, 15, 5, pants(89 + side)),
    ], children=[
        shin,
        Part(f'armor_thigh_{s}', cubes=[C(-2.5, 2, -2.5, 5, 9, 5, by_mode(plate(90, 0.5, 0, 1)), dilate=0.35)]),
    ])


def coat_skirt(s, back):
    """Mantelschoß als eigener Knochen mit zweitem Glied (Nachschwingen). Vorn: flache Bahn neben der offenen Mitte;
    hinten: Rückenbahn plus Seitenbahn. Im weißen Atlas der Gewandrock (oberes Glied, Goldsaum)."""
    side = -1 if s == 'r' else 1
    name = f'coat_{"b" if back else "f"}{s}'
    if back:
        sx = 3.0
        out = 'back'
        up = [C(-3, 0, -0.5, 6, 15, 1, coat_paint(100 + side, 'back', white_hem=True)),
              C(-3.5 if s == 'r' else 2.5, 0, -6.2, 1, 15, 6, coat_paint(101 + side, 'right' if s == 'r' else 'left', white_hem=True))]
        low = [C(-3, 0, -0.5, 6, 10, 1, coat_paint(102 + side, 'back', hem=True, tatter=True)),
               C(-3.5 if s == 'r' else 2.5, 0, -6.2, 1, 10, 6, coat_paint(103 + side, 'right' if s == 'r' else 'left', hem=True, tatter=True))]
        pivot = (side * sx, -3, 3.2)
    else:
        out = 'front'
        lining = 3 if s == 'r' else 0                         # Spalte an der offenen Kante
        up = [C(-2, 0, -0.5, 4, 15, 1, coat_paint(104 + side, out, lapel=True, lining_col=lining, white_hem=True))]
        low = [C(-2, 0, -0.5, 4, 10, 1, coat_paint(105 + side, out, hem=True, tatter=True, lapel=True, lining_col=lining))]
        pivot = (side * 3.6, -3, -3.2)
    lower = Part(f'{name}_1', (0, 15, 0), cubes=low)
    return Part(name, pivot, cubes=up, children=[lower])


def torso_parts():
    chest_paint = under(110, 0.5, 'chest')
    belly = under(111, 0.48, 'torso')
    skin = by_mode(surf(SKIN, 0.55, 0.1, 112, roundx=0.2))
    coat_body = Part('coat_body', cubes=[
        C(-6, -11, 3.4, 12, 11, 1, coat_paint(113, 'back')),                       # Rücken
        C(-6.3, -10, -3.2, 1, 10, 7, coat_paint(114, 'right')),                    # Seiten
        C(5.3, -10, -3.2, 1, 10, 7, coat_paint(115, 'left')),
        C(-5.6, -10, -4.4, 3, 10, 1, coat_paint(116, 'front', lapel=True, lining_col=2)),   # Revers, innen stahlblau
        C(2.6, -10, -4.4, 3, 10, 1, coat_paint(117, 'front', lapel=True, lining_col=0)),
        C(-6, -11.2, -3.4, 12, 1, 7, sleeve_coat(118)),                           # Schulterjoch
    ])
    collar = Part('coat_collar', (0, -10.8, 0.2), cubes=[
        C(-3.5, -5.5, 2.0, 7, 6, 1, collar_paint(120, 'front')),
    ], children=[
        Part('collar_r', (-3.4, 0, 0), (0, 22, -14), cubes=[C(-0.5, -5.5, -3.2, 1, 6, 6, collar_paint(121, 'left'))]),
        Part('collar_l', (3.4, 0, 0), (0, -22, 14), cubes=[C(-0.5, -5.5, -3.2, 1, 6, 6, collar_paint(122, 'right'))]),
    ])
    robe_collar = Part('robe_collar', cubes=[
        C(-3, -12.2, 1.4, 6, 2, 1, robe(123, 0.7, hem_top=True)),
        C(-3.2, -12.2, -2.2, 1, 2, 4, robe(124, 0.7, hem_top=True)),
        C(2.2, -12.2, -2.2, 1, 2, 4, robe(125, 0.7, hem_top=True)),
    ])
    robe_collar.cubes = [C(c.x, c.y, c.z, c.w, c.h, c.d, by_mode(c.paint, c.paint)) for c in robe_collar.cubes]
    armor_chest = Part('armor_chest', cubes=[
        C(-5.5, -10.5, -3.5, 11, 9, 7, by_mode(plate(126, 0.52, 1, 0, star='front'))),
        C(-3.5, -11.8, -3.0, 7, 2, 6, by_mode(plate(127, 0.46, 0, 0, seam=False))),     # Halsberge
    ])
    strap = Part('strap', (-0.5, -5.5, -3.4), (0, 0, -36), cubes=[C(-1, -8, -1, 2, 17, 2, strap_paint(128))])
    chest = Part('chest', (0, -6, 0), cubes=[
        *block(-5, -10, -3, 10, 10, 6, chest_paint, bevel=1),
        C(-3.5, -11, -2, 7, 2, 4, chest_paint),                                    # Trapez
        C(-1.5, -12.5, -1.5, 3, 3, 3, skin),                                       # Hals
    ], children=[head_part(), arm_part('r'), arm_part('l'), armor_chest, coat_body, collar, robe_collar, strap,
                 scabbard_part()])
    torso = Part('torso', (0, -3, 0), cubes=[
        *block(-4, -6.5, -2.5, 8, 7, 5, belly, bevel=1),
    ], children=[
        chest,
        Part('armor_belly', cubes=[C(-4.5, -6, -3, 9, 5, 6, by_mode(plate(130, 0.46, 0, 1)))]),
        Part('coat_waist', cubes=[
            C(-5, -6.5, 3.0, 10, 7, 1, coat_paint(131, 'back')),
            C(-5.6, -6, -2.8, 1, 6, 6, coat_paint(132, 'right')),
            C(4.6, -6, -2.8, 1, 6, 6, coat_paint(133, 'left')),
            C(-5, -6.5, -3.8, 3, 7, 1, coat_paint(134, 'front', lapel=True, lining_col=2)),
            C(2, -6.5, -3.8, 3, 7, 1, coat_paint(135, 'front', lapel=True, lining_col=0)),
        ]),
    ])
    sash = Part('robe_sash', (4.4, -2.6, -2.2), (0, 0, -10), cubes=[
        C(-1.5, -1.2, -1.0, 3, 2, 2, sash_paint(140)),
    ], children=[Part('sash_tail', (0, 0.6, 0), (0, 0, 4), cubes=[C(-1, 0, -0.5, 2, 9, 1, sash_paint(141))])])
    hips = Part('hips', (0, -6, 0), cubes=[
        *block(-4.5, -3, -2.5, 9, 5, 5, pants(142), bevel=1),
        C(-5, -3.5, -3, 10, 2, 6, belt_paint(143)),
    ], children=[torso, leg_part('r'), leg_part('l'),
                 coat_skirt('r', False), coat_skirt('l', False), coat_skirt('r', True), coat_skirt('l', True), sash])
    return hips


# Griffmitte in der Faust (Handraum) und Lage der Scheide auf dem Rücken (Brustraum).
PALM = (0, 1.5, 0)
GRIP_L = 4.0                       # zweite Hand: so weit hinter der rechten am Griff (Richtung Knauf)
SCABBARD_MOUTH = (-3.6, -10.4, 5.3)
SCABBARD_DIR = (-0.42, -0.9, 0.0)   # Richtung vom Mundblech zum Heft (über die rechte Schulter)
SCABBARD_ROT = (0, 0, 0)            # wird unten aus SCABBARD_DIR berechnet


def _norm(v):
    n = math.sqrt(sum(c * c for c in v)) or 1.0
    return tuple(c / n for c in v)


def _cross(a, b):
    return (a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])


def _dot(a, b):
    return sum(x * y for x, y in zip(a, b))


def euler(m):
    """Zerlegt R = Rz(roll) · Ry(yaw) · Rx(pitch) (ModelPart-Reihenfolge) in (pitch, yaw, roll)."""
    sy = max(-1.0, min(1.0, -m[2][0]))
    yaw = math.asin(sy)
    if abs(sy) < 0.9999:
        return (math.atan2(m[2][1], m[2][2]), yaw, math.atan2(m[1][0], m[0][0]))
    return (math.atan2(-m[1][2], m[1][1]), yaw, 0.0)


def frame(b, u):
    """Drehung mit lokal -z = b (Klinge) und lokal -y ≈ u (Richtung zum Unterarm)."""
    bb = _norm(b)
    u = _norm(u)
    proj = tuple(uc - _dot(u, bb) * bc for uc, bc in zip(u, bb))
    if math.sqrt(_dot(proj, proj)) < 1e-4:
        proj = (1.0, 0.0, 0.0)
    yc = tuple(-c for c in _norm(proj))
    zc = tuple(-c for c in bb)
    xc = _cross(yc, zc)
    return [[xc[i], yc[i], zc[i]] for i in range(3)]


def _scabbard_rot():
    # lokal -z = Richtung zum Heft, lokal +x = nach hinten (Flachseite liegt auf dem Rücken).
    zc = tuple(-c for c in _norm(SCABBARD_DIR))
    xc = (0.0, 0.0, 1.0)
    yc = _cross(zc, xc)
    m = [[xc[i], yc[i], zc[i]] for i in range(3)]
    return tuple(math.degrees(a) for a in euler(m))


SCABBARD_ROT = _scabbard_rot()


def arius():
    return Model('arius', 256, 256, [torso_parts()], texture='arius_dark')


# --- Formen ----------------------------------------------------------------------------------------------------------
COAT_ALL = ['coat_body', 'coat_waist', 'coat_collar', 'coat_sleeve_r', 'coat_sleeve_l', 'coat_cuff_r', 'coat_cuff_l',
            'coat_fr', 'coat_fl', 'coat_br', 'coat_bl']
COAT_LOWER = ['coat_fr_1', 'coat_fl_1', 'coat_br_1', 'coat_bl_1']
ARMOR_ALL = ['armor_chest', 'armor_belly', 'armor_shoulder_r', 'armor_shoulder_l', 'armor_bracer_r', 'armor_bracer_l',
             'armor_thigh_r', 'armor_thigh_l', 'armor_knee_r', 'armor_knee_l', 'armor_greave_r', 'armor_greave_l',
             'armor_foot_r', 'armor_foot_l']
ROBE_ONLY = ['robe_collar', 'robe_sash']
FORM_HIDE = {
    0: ROBE_ONLY,                                                              # DARK
    1: ROBE_ONLY + COAT_ALL,                                                   # SERIOUS
    2: ARMOR_ALL + COAT_LOWER + [n for n in COAT_ALL if n not in ('coat_fr', 'coat_fl', 'coat_br', 'coat_bl')],  # WHITE
}
FORM_OF = {'dark': 0, 'serious': 1, 'white': 2}


# =====================================================================================================================
# Kinematik: Vorwärtskette, IK für Arme (mit Klingenrahmen) und Beine, Bodenkontakt, Schwerkraft für Mantel und Haar
# =====================================================================================================================
from preview_model import apply as _apply, mat_mul as _mul, rot_zyx as _rot  # noqa: E402

# Knochen, deren Winkel die Kotlin-Animation aus den Schlüsselposen liest (Reihenfolge = Datenlayout).
BONES = ['hips', 'torso', 'chest', 'head', 'hair_back', 'antenna',
         'arm_r', 'fore_r', 'hand_r', 'arm_l', 'fore_l', 'hand_l', 'fist_l', 'open_l', 'point_l',
         'thigh_r', 'shin_r', 'foot_r', 'thigh_l', 'shin_l', 'foot_l',
         'coat_fr', 'coat_fr_1', 'coat_fl', 'coat_fl_1', 'coat_br', 'coat_br_1', 'coat_bl', 'coat_bl_1',
         'sash_tail']
IDENT = [[1, 0, 0], [0, 1, 0], [0, 0, 1]]
ZERO6 = (0.0,) * 6
_MODEL = None
_PARENT = {}


def _model():
    global _MODEL
    if _MODEL is None:
        _MODEL = arius()

        def walk(part, parent):
            _PARENT[part.name] = (parent, part)
            for ch in part.children:
                walk(ch, part.name)
        for top in _MODEL.parts:
            walk(top, None)
    return _MODEL


def _t(m):
    return [[m[j][i] for j in range(3)] for i in range(3)]


def _value(pose, part):
    value = pose.get(part.name, ...)
    pivot = part.pivot
    if value is ... or value is None:
        return tuple(math.radians(a) for a in part.rot), pivot
    if len(value) >= 6:
        pivot = (pivot[0] + value[3], pivot[1] + value[4], pivot[2] + value[5])
    return tuple(value[:3]), pivot


def chain(pose, name):
    """Weltdrehung und -lage des Ursprungs eines Knochens."""
    _model()
    path = []
    n = name
    while n:
        path.append(_PARENT[n][1])
        n = _PARENT[n][0]
    rot, pos = IDENT, (0.0, 0.0, 0.0)
    for part in reversed(path):
        r, pivot = _value(pose, part)
        o = _apply(rot, pivot)
        pos = (pos[0] + o[0], pos[1] + o[1], pos[2] + o[2])
        rot = _mul(rot, _rot(*r))
    return rot, pos


def world_point(pose, name, local):
    r, p = chain(pose, name)
    v = _apply(r, local)
    return (p[0] + v[0], p[1] + v[1], p[2] + v[2])


def world_corners(pose, name, recursive=False):
    _model()
    part = _PARENT[name][1]
    parts = list(part.walk()) if recursive else [part]
    pts = []
    for q in parts:
        r, p = chain(pose, q.name)
        for c in q.cubes:
            for x in (c.x, c.x + c.w):
                for y in (c.y, c.y + c.h):
                    for z in (c.z, c.z + c.d):
                        v = _apply(r, (x, y, z))
                        pts.append((v[0] + p[0], v[1] + p[1], v[2] + p[2]))
    return pts


def axis_angle(axis, ang):
    x, y, z = _norm(axis)
    c, s, t = math.cos(ang), math.sin(ang), 1 - math.cos(ang)
    return [[t * x * x + c, t * x * y - s * z, t * x * z + s * y],
            [t * x * y + s * z, t * y * y + c, t * y * z - s * x],
            [t * x * z - s * y, t * y * z + s * x, t * z * z + c]]


def _full(v):
    v = tuple(v or ZERO6)
    return v + (0.0,) * (6 - len(v))


def _setv(pose, bone, i, val):
    v = list(_full(pose.get(bone)))
    v[i] = val
    pose[bone] = tuple(v)


def _excess(v, lo, hi):
    return (lo - v) if v < lo else (v - hi) if v > hi else 0.0


def _descend(pose, variables, cost, step=0.3, floor=0.002):
    best = cost()
    while step > floor:
        improved = False
        for bone, i in variables:
            for sign in (1, -1):
                old = pose[bone]
                _setv(pose, bone, i, old[i] + sign * step)
                c = cost()
                if c < best - 1e-9:
                    best = c
                    improved = True
                else:
                    pose[bone] = old
        if not improved:
            step *= 0.5
    return best


def _wrist_cost(v):
    p, y, r = v[:3]
    return (40 * (_excess(p, -0.75, 0.95) ** 2 + _excess(r, -0.55, 0.55) ** 2 + _excess(y, -0.5, 0.5) ** 2)
            + 0.4 * (p * p + r * r) + 0.15 * y * y)


def _set_hand(pose, s, target_rot):
    rf, _ = chain(pose, f'fore_{s}')
    pose[f'hand_{s}'] = tuple(euler(_mul(_t(rf), target_rot))) + (0.0, 0.0, 0.0)


def palm(pose, s):
    return world_point(pose, f'hand_{s}', PALM)


def reach_arm(pose, s, target, rot=None, twist_axis=None):
    """Schulter (3), Ellbogen und Unterarmdrehung so, dass die Faustmitte target erreicht; die Hand dreht sich exakt
    in rot (Weltdrehung), die Arme wählen dabei ein bequemes Handgelenk. twist_axis: rot darf sich darum drehen."""
    side = -1 if s == 'r' else 1
    names = [(f'arm_{s}', 0), (f'arm_{s}', 2), (f'fore_{s}', 0), (f'arm_{s}', 1), (f'fore_{s}', 1)]
    for n in (f'arm_{s}', f'fore_{s}', f'hand_{s}'):
        pose[n] = _full(pose.get(n))
    start = {n: tuple(pose[n]) for n, _ in names}
    if twist_axis is not None:
        pose['_twist'] = ZERO6
        names.append(('_twist', 0))

    def cost():
        if rot is not None:
            r = rot
            if twist_axis is not None:
                r = _mul(axis_angle(twist_axis, pose['_twist'][0]), rot)
            _set_hand(pose, s, r)
        d = sum((a - b) ** 2 for a, b in zip(palm(pose, s), target))
        reg = sum((pose[n][i] - start[n][i]) ** 2 for n, i in names if n != '_twist') * 1.2
        arm = pose[f'arm_{s}']
        fore = pose[f'fore_{s}']
        lim = _excess(fore[0], -2.6, 0.0) ** 2 + _excess(fore[1], -1.4, 1.4) ** 2
        lim += _excess(arm[0], -3.3, 1.3) ** 2 + _excess(side * -arm[2], -0.6, 3.0) ** 2 + _excess(arm[1], -1.7, 1.7) ** 2
        w = _wrist_cost(pose[f'hand_{s}']) if rot is not None else 0.0
        return d * 3 + reg + 300 * lim + w

    _descend(pose, names, cost)
    cost()
    pose.pop('_twist', None)
    return math.dist(palm(pose, s), target)


def ankle(pose, s):
    return chain(pose, f'foot_{s}')[1]


def reach_leg(pose, s, target):
    side = -1 if s == 'r' else 1
    names = [(f'thigh_{s}', 0), (f'thigh_{s}', 2), (f'shin_{s}', 0), (f'thigh_{s}', 1)]
    for n in (f'thigh_{s}', f'shin_{s}'):
        pose[n] = _full(pose.get(n))
    start = {n: tuple(pose[n]) for n, _ in names}

    def cost():
        d = sum((a - b) ** 2 for a, b in zip(ankle(pose, s), target))
        reg = sum((pose[n][i] - start[n][i]) ** 2 * (3.0 if i == 1 else 0.6) for n, i in names)
        th, sh = pose[f'thigh_{s}'], pose[f'shin_{s}']
        lim = _excess(sh[0], 0.0, 2.6) ** 2 + _excess(th[0], -2.4, 1.1) ** 2 + _excess(side * -th[2], -0.5, 1.4) ** 2
        return d * 4 + reg + 300 * lim

    _descend(pose, names, cost)
    return math.dist(ankle(pose, s), target)


def flat_foot(pose, s, pitch=0.0, roll=0.0):
    """Sohle waagerecht in Laufrichtung des Schienbeins; pitch > 0 hebt die Ferse (Zehenstand)."""
    rs, _ = chain(pose, f'shin_{s}')
    fwd = _apply(rs, (0, 0, -1))
    heading = math.atan2(-fwd[0], -fwd[2])
    target = _mul(_rot(0, heading, 0), _rot(-pitch, 0, roll))
    pose[f'foot_{s}'] = tuple(euler(_mul(_t(rs), target))) + (0.0, 0.0, 0.0)


LEG_PARTS = ['foot_r', 'foot_l', 'shin_r', 'shin_l']


def ground(pose):
    low = max(q[1] for name in LEG_PARTS for q in world_corners(pose, name))
    h = list(pose['hips'])
    h[4] += 24 - low
    pose['hips'] = tuple(h)


def _hang(pose, parent):
    """Winkel (pitch, roll), mit denen ein Kind von parent senkrecht nach unten hängt."""
    r, _ = chain(pose, parent)
    d = _apply(_t(r), (0, 1, 0))
    return math.atan2(d[2], d[1]), math.atan2(-d[0], d[1])


def grip_point(pose, along=None):
    return world_point(pose, 'hand_r', (PALM[0], PALM[1], PALM[2] + (GRIP_L if along is None else along)))


def dress(prim, feet=None, ground_=True, hand_r=None, blade=None, two=False, hand_l=None, frame_l=None,
          shape='fist', sheathed=False, coat=(0.0, 0.0), coat_low=0.0, hair=0.0, toe=None, quiet=False, name='',
          spin=0.0):
    """Aus Hauptwinkeln eine vollständige Pose.

    feet: {'r': (x, z)} Knöchel über dem Boden an (x, z) oder {'r': (x, y, z)} frei (Tritte, Sprünge).
    hand_r: Weltpunkt der Faustmitte; blade = (Klingenrichtung, Richtung zum Unterarm). two: linke Hand am Griff.
    hand_l / frame_l: linke Faust frei. coat = (Zusatz-Nicken, Aufspreizen) der Schöße. toe: {'r': (pitch, roll)}.
    """
    pose = {b: ZERO6 for b in BONES}
    for k, v in prim.items():
        pose[k] = _full(v)
    toe = toe or {}
    for s, tgt in (feet or {}).items():
        t3 = (tgt[0], 21.0, tgt[1]) if len(tgt) == 2 else tuple(tgt)
        err = reach_leg(pose, s, t3)
        if err > 1.5 and not quiet:
            print(f'  {name}: Fuß {s} verfehlt sein Ziel um {err:.1f}')
    for s in 'rl':
        if f'foot_{s}' not in prim:
            flat_foot(pose, s, *toe.get(s, (0.0, 0.0)))
    if ground_:
        ground(pose)
    rot_r = frame(*blade) if blade else None
    if hand_r == 'hilt':
        # Hand am Heft in der Scheide über der rechten Schulter (Iai, Ziehen).
        rs, _ = chain(pose, 'sheath_hilt')
        b = _apply(rs, (0, 0, 1))
        err = reach_arm(pose, 'r', world_point(pose, 'sheath_hilt', (0, 0, 0)), frame(b, _apply(rs, (0, 1, 0))), twist_axis=b)
        if err > 1.5 and not quiet:
            print(f'  {name}: rechte Hand verfehlt das Heft um {err:.1f}')
    elif hand_r is not None:
        err = reach_arm(pose, 'r', hand_r, rot_r)
        if err > 1.5 and not quiet:
            print(f'  {name}: rechte Hand verfehlt ihr Ziel um {err:.1f}')
    elif rot_r is not None:
        _set_hand(pose, 'r', rot_r)
    if two:
        rr, _ = chain(pose, 'hand_r')
        b = _apply(rr, (0, 0, -1))
        err = reach_arm(pose, 'l', grip_point(pose), rr, twist_axis=b)
        if err > 1.5 and not quiet:
            print(f'  {name}: linke Hand verfehlt den Griff um {err:.1f}')
    elif hand_l is not None:
        err = reach_arm(pose, 'l', hand_l, frame(*frame_l) if frame_l else None)
        if err > 1.5 and not quiet:
            print(f'  {name}: linke Hand verfehlt ihr Ziel um {err:.1f}')
    elif frame_l is not None:
        _set_hand(pose, 'l', frame(*frame_l))
    for shp in ('fist', 'open', 'point'):
        pose[f'{shp}_l'] = ZERO6 if shp == shape else None
    # Mantelschöße: Schwerkraft gegen das Becken, die Oberschenkel schieben sie mit.
    for s in 'rl':
        side = -1 if s == 'r' else 1
        th = pose[f'thigh_{s}']
        for back in (False, True):
            n = f'coat_{"b" if back else "f"}{s}'
            if n not in prim:
                gp, gr = _hang(pose, 'hips')
                p = max(gp, th[0] + 0.12) if back else min(gp, th[0] - 0.12)
                rl = max(gr, th[2] * 0.85 + 0.03) if side < 0 else min(gr, th[2] * 0.85 - 0.03)
                pose[n] = (p + coat[0], 0.0, rl - side * coat[1], 0.0, 0.0, 0.0)
            if f'{n}_1' not in prim:
                gp1, gr1 = _hang(pose, n)
                pose[f'{n}_1'] = (gp1 * 0.85 + coat_low, 0.0, gr1 * 0.7, 0.0, 0.0, 0.0)
    if 'hair_back' not in prim:
        gp, gr = _hang(pose, 'head')
        pose['hair_back'] = (max(-0.35, min(1.1, gp * 0.7)) + hair, 0.0, max(-0.5, min(0.5, gr * 0.6)), 0.0, 0.0, 0.0)
    if 'sash_tail' not in prim:
        gp, gr = _hang(pose, 'robe_sash')
        pose['sash_tail'] = (gp, 0.0, gr, 0.0, 0.0, 0.0)
    if spin:
        h = list(pose['hips'])
        h[1] += spin
        pose['hips'] = tuple(h)
    pose['_sheathed'] = sheathed
    return pose


KEYS = {}


def K(name, prim, **opts):
    KEYS[name] = (prim, opts)


def V(x, y, z):
    return (x, y, z)


DOWN, UP, FWD, BACK, RIGHT, LEFT = V(0, 1, 0), V(0, -1, 0), V(0, 0, -1), V(0, 0, 1), V(-1, 0, 0), V(1, 0, 0)


# =====================================================================================================================
# Schlüsselposen. Rechts = -x, vorn = -z, oben = -y. Positive Gierung dreht nach seiner Rechten, positives Nicken
# beugt nach vorn (Rumpf/Kopf) bzw. schwingt Arme und Beine nach hinten. Ziele sind Modellkoordinaten (Sohle y = 24).
# =====================================================================================================================

STAND = dict(hips=(0.04, 0.22, 0.0, 0.0, 1.0, 0.0), torso=(0.03, -0.08, 0.0), chest=(0.03, -0.08, 0.02),
             head=(0.02, -0.08, 0.0), arm_l=(0.1, 0.0, -0.14), fore_l=(-0.32, 0.0, 0.0))

# ------------------------------------------------------------------------------------------------ Ruhe, Haltungen
K('idle', STAND, feet={'r': (-4.4, 2.0), 'l': (3.0, -3.4)},
  hand_r=V(-8.2, -1.0, -3.5), blade=(V(-0.25, 0.5, -1), V(0.15, -1, 0.35)))

K('guard', dict(hips=(0.08, 0.3, 0.0, 0.0, 2.0, 1.5), torso=(-0.04, -0.12, 0.0), chest=(-0.03, -0.1, 0.0),
                head=(0.05, -0.12, 0.0), arm_r=(-1.0, 0.0, 0.5), fore_r=(-1.2, 0.0, 0.0),
                arm_l=(-1.1, 0.0, -0.3), fore_l=(-1.0, 0.0, 0.0)),
  feet={'r': (-4.5, 3.5), 'l': (3.2, -4.0)},
  hand_r=V(-5.0, -18.5, -9.0), blade=(V(1, -0.04, -0.12), V(-0.5, 0.55, 0.6)),
  hand_l=V(8.0, -17.2, -10.5), frame_l=(V(1, 0, 0), V(0, 0.3, 1)), shape='open')

# ------------------------------------------------------------------------------------------------ Kombo
K('c1_wind', dict(hips=(0.05, 0.5, 0.0, 0.0, 2.0, 1.0), torso=(-0.05, 0.15, 0.0), chest=(-0.05, 0.22, -0.05),
                  head=(0.0, -0.75, 0.0), arm_r=(-2.4, 0.0, 0.6), fore_r=(-1.2, 0.0, 0.0),
                  arm_l=(-2.2, 0.0, -0.3), fore_l=(-1.4, 0.0, 0.0)),
  feet={'r': (-4.6, 3.5), 'l': (2.6, -5.0)},
  hand_r=V(-6.5, -31.0, -1.0), blade=(V(-0.25, -0.65, 0.75), V(0.3, 0.8, -0.5)), two=True)
K('c1_hit', dict(hips=(0.12, -0.35, 0.0, 0.0, 2.5, -3.0), torso=(0.08, -0.18, 0.0), chest=(0.06, -0.15, 0.04),
                 head=(-0.05, 0.55, 0.0), arm_r=(-1.5, 0.0, 0.0), fore_r=(-0.3, 0.0, 0.0),
                 arm_l=(-1.3, 0.0, -0.1), fore_l=(-0.6, 0.0, 0.0)),
  feet={'r': (-4.2, 1.5), 'l': (2.8, -9.0)},
  hand_r=V(-0.5, -15.0, -13.0), blade=(V(0.65, 0.4, -1), V(-0.45, -0.6, 0.6)), two=True)
K('c1_follow', dict(hips=(0.16, -0.6, 0.0, 0.0, 3.0, -3.0), torso=(0.1, -0.25, 0.0), chest=(0.1, -0.2, 0.06),
                    head=(0.0, 0.75, 0.0), arm_r=(-0.9, 0.0, -0.6), fore_r=(-0.4, 0.0, 0.0),
                    arm_l=(-0.6, 0.0, -0.2), fore_l=(-0.8, 0.0, 0.0)),
  feet={'r': (-4.2, 1.5), 'l': (2.8, -9.0)},
  hand_r=V(5.0, -5.0, -8.0), blade=(V(0.7, 0.75, 0.25), V(-0.5, -0.6, -0.4)), two=True)


# =====================================================================================================================
def build_poses():
    out = {}
    for name, (prim, opts) in KEYS.items():
        out[name] = dress(prim, name=name, **opts)
    return out


def visible(pose, form):
    """Vorschau: blendet die Teile der anderen Formen, die Glove-Marke (doppelte Texeldichte) und das jeweils nicht
    benutzte Schwert aus."""
    out = {k: v for k, v in pose.items() if not k.startswith('_')}
    for n in FORM_HIDE[form]:
        out[n] = None
    out['glove_mark'] = None
    out['sheath_hilt' if not pose.get('_sheathed') else 'solvane'] = None
    return out


_BUILT = None


def built():
    global _BUILT
    if _BUILT is None:
        _BUILT = build_poses()
    return _BUILT


class _LazyPoses(dict):
    """preview.py liest POSES erst beim Aufruf; die IK läuft nur, wenn jemand die Posen braucht."""
    def _fill(self):
        if not dict.__len__(self):
            form = FORM_OF.get(FORM_NAME, 0)
            for name, pose in built().items():
                dict.__setitem__(self, name, visible(pose, form))

    def __getitem__(self, k):
        self._fill()
        return dict.__getitem__(self, k)

    def get(self, k, d=None):
        self._fill()
        return dict.get(self, k, d)

    def __iter__(self):
        self._fill()
        return dict.__iter__(self)

    def __contains__(self, k):
        self._fill()
        return dict.__contains__(self, k)

    def __len__(self):
        self._fill()
        return dict.__len__(self)

    def __bool__(self):
        return True


POSES = _LazyPoses()

# Für die Größenprüfung in preview.py: Zubehör über dem Scheitel zählt nicht zur Körperhöhe.
HEIGHT_EXCLUDE = ('antenna', 'scabbard', 'solvane', 'tuft_4', 'tuft_2', 'tuft_3', 'coat_collar')
