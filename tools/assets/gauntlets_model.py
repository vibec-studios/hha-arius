"""Arius' Kampfhandschuhe: ein Paar Plattenhandschuhe, getragen als EIN Item in der Haupthand.

Gestaltung nach der Referenz (helle Platten, goldene Einfassung, gegliederte Finger, markante Unterarmschienen),
übertragen auf Arius und Solvane: Perlweiß statt Elfenbein, Gold nur als Kante, und statt roter Ornamente drei Motive:
* Wind  – geschwungene Linien auf der Außenseite der Schiene (die Seite, die beim Schlag zum Gegner zeigt),
* Eis   – ein Kristallstern auf der Vorderseite der Schiene,
* Yang  – eine Sonnenscheibe auf dem Handrücken; sie leuchtet im Magiemodus (Leuchtmaske).

Koordinaten wie ein Spielerarm im eigenen Knochenraum (1/16 Block, +y nach unten, -z vorn): der rechte Arm liegt bei
x -3..1, y -2..10, z -2..2, die Hand ist das untere Ende (y = 10). Der linke Arm ist gespiegelt (x -1..3). Alle Teile
haben den Drehpunkt 0: Der Renderer setzt sie in den Raum des Spielerarms (dritte Person: `setArmAngle`, erste Person:
Arm-Drehpunkt), deshalb bewegen sie sich exakt mit dem Arm.

Teile je Seite (r/l):
* `<s>_base`  Schiene, Manschette, Handgelenk, Handplatte, Sonnenscheibe   (immer)
* `<s>_fist`  Knöchelkappe und eingerollte Finger                          (Nahkampfmodus)
* `<s>_open`  gestreckte, gegliederte Finger und Daumen                    (Magiemodus)

Die Finger ragen höchstens 2,9 Einheiten über das Armende hinaus (nicht überlang) und nie in den Körper: Die Innenseite
(zum Rumpf) bleibt glatt.
"""
import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)

from modelkit import Cube, Model, Part, hexc, mix, ramp, rgb, speckle  # noqa: E402

PLATE = rgb('#59606f', '#8c95a7', '#c3cad8', '#e6ebf2', '#f7f9fc', '#ffffff')
GOLD = rgb('#5a3410', '#8e5b17', '#c08a2a', '#e5b850', '#f8dc8a', '#fff4cc')
UNDER = rgb('#15161c', '#23252e', '#343744', '#4a4e5e')
ICE_LINE = hexc('#7cc8f2')
ICE_CORE = hexc('#d4f1ff')
WIND_LINE = hexc('#9fb4cc')
SUN = rgb('#b5761a', '#f0b43a', '#ffd970', '#fff2c2', '#ffffff')
LIGHT = {'top': 0.12, 'bottom': -0.2, 'front': 0.02, 'back': -0.08, 'left': -0.04, 'right': -0.04}


def plate(base=0.62, seed=0):
    def paint(face, x, y, w, h):
        g = (0.5 - y / max(h - 1, 1)) * 0.1
        n = (speckle(x, y, seed) - 0.5) * 0.05
        return ramp(PLATE, base + LIGHT[face] + g + n)
    return paint


def gold(base=0.6, seed=3):
    def paint(face, x, y, w, h):
        g = (0.5 - y / max(h - 1, 1)) * 0.18
        n = (speckle(x, y, seed) - 0.5) * 0.06
        return ramp(GOLD, base + LIGHT[face] + g + n)
    return paint


def under(base=0.5):
    def paint(face, x, y, w, h):
        return ramp(UNDER, base + LIGHT[face] + (speckle(x, y, 9) - 0.5) * 0.1)
    return paint


def vambrace(outer):
    """Unterarmschiene: Goldkante oben/unten, Wind auf der Außenseite, Eiskristall vorn, feine Mittelgrat-Linie."""
    base = plate(0.64, 1)
    rim = gold(0.62)

    def paint(face, x, y, w, h):
        if face in ('top', 'bottom'):
            return rim(face, x, y, w, h) if (x in (0, w - 1) or y in (0, h - 1)) else base(face, x, y, w, h)
        if y == 0 or y == h - 1:
            return rim(face, x, y, w, h)
        c = base(face, x, y, w, h)
        if face == outer:
            # Windlinien: zwei gegenläufige Bögen über die Fläche.
            for k, off in ((1, 0.8), (-1, 2.2)):
                yy = off + 0.9 * math.sin((x + 0.5) / max(w, 1) * math.pi * 1.2 * k + 0.6)
                if abs(y - yy) < 0.55:
                    return mix(c, WIND_LINE, 0.75)
            return c
        if face == 'front':
            # Eiskristall: Stern aus drei Linien um die Mitte (4 x 4 Texel).
            cx, cy = (w - 1) / 2, (h - 1) / 2
            dx, dy = x - cx, y - cy
            if abs(dx) < 0.6 and abs(dy) < 1.6:
                return ICE_CORE if abs(dy) < 0.6 else ICE_LINE
            if abs(dy) < 0.6 and abs(dx) < 1.6:
                return ICE_LINE
            return c
        if face == 'back':
            return mix(c, ramp(GOLD, 0.7), 0.85) if x == w // 2 else c
        return c
    return paint


def cuff():
    g = gold(0.68)

    def paint(face, x, y, w, h):
        c = g(face, x, y, w, h)
        if face in ('top', 'bottom') and 0 < x < w - 1 and 0 < y < h - 1:
            return ramp(GOLD, 0.45)                                    # Innenkante dunkler
        return c
    return paint


def hand_plate(outer):
    base = plate(0.6, 4)
    rim = gold(0.58)

    def paint(face, x, y, w, h):
        if face in ('front', 'back', outer) and (y == 0 or y == h - 1):
            return rim(face, x, y, w, h)
        return base(face, x, y, w, h)
    return paint


def sun_disc():
    """Yang-Sonnenscheibe: goldener Ring, heller Kern. Leuchtmaske nur im Kern und auf vier Strahlen."""
    def paint(face, x, y, w, h):
        cx, cy = (w - 1) / 2, (h - 1) / 2
        r = math.hypot(x - cx, y - cy)
        if face in ('left', 'right'):
            if r < 0.8:
                c = ramp(SUN, 0.95)
                return (c, (255, 244, 214, 255))
            c = ramp(SUN, 0.55)
            return (c, (255, 214, 140, 150))
        return ramp(GOLD, 0.55 + LIGHT[face])
    return paint


def knuckles():
    """Knöchelkappe (Faust): vier Fingerglieder als Platten, dunkle Fugen dazwischen, Goldkante vorn."""
    base = plate(0.66, 6)

    def paint(face, x, y, w, h):
        if face == 'bottom':
            # Schlagfläche: Glieder entlang x, Fugen dunkel.
            c = base(face, x, y, w, h)
            return mix(c, ramp(UNDER, 0.6), 0.35) if x % 2 else c          # vier Glieder, abwechselnd schattiert
        if face == 'front':
            return ramp(GOLD, 0.62) if y == h - 1 else base(face, x, y, w, h)
        return base(face, x, y, w, h)
    return paint


def curl():
    """Eingerollte Finger, von vorn gesehen: vier Glieder nebeneinander, Fugen dunkel, Spitzen gold."""
    base = plate(0.6, 7)

    def paint(face, x, y, w, h):
        if face == 'front':
            c = base(face, x, y, w, h)
            if y == h - 1:
                return ramp(GOLD, 0.6)
            if y == 0:
                return ramp(UNDER, 0.55)                               # Fuge zur Handplatte
            return mix(c, ramp(PLATE, 0.45), 0.4) if x % 2 else c
        return base(face, x, y, w, h)
    return paint


def finger(tip_gold=True):
    """Gestreckter Finger: zwei Glieder (Fuge in der Mitte), goldene Spitze."""
    base = plate(0.66, 8)

    def paint(face, x, y, w, h):
        if face == 'bottom':
            return ramp(GOLD, 0.66) if tip_gold else base(face, x, y, w, h)
        if face == 'top':
            return ramp(UNDER, 0.5)
        if y == h // 2:
            return ramp(UNDER, 0.55)                                   # Gelenkfuge
        if tip_gold and y == h - 1:
            return ramp(GOLD, 0.6 + LIGHT[face])
        return base(face, x, y, w, h)
    return paint


def fin(outer):
    """Markanter Grat auf der Außenseite der Schiene: Platte mit Goldkante, läuft zur Hand hin schmal aus."""
    base = plate(0.7, 11)
    rim = gold(0.64)

    def paint(face, x, y, w, h):
        if face in ('front', 'back', 'bottom', 'top'):
            return rim(face, x, y, w, h)
        return base(face, x, y, w, h) if 0 < y < h - 1 else rim(face, x, y, w, h)
    return paint


def side(right):
    """Teile einer Seite. right=True: rechter Arm (Außenseite = -x = Fläche 'right')."""
    outer = 'right' if right else 'left'
    s = 'r' if right else 'l'

    def X(x, w):
        """Spiegelt Quader vom rechten auf den linken Arm (x -> -x)."""
        return x if right else -(x + w)

    def C(x, y, z, w, h, d, paint, dilate=0.0):
        return Cube(X(x, w), y, z, w, h, d, paint, dilate)

    base = Part(f'{s}_base', cubes=[
        C(-3.5, 3.0, -2.5, 5, 1, 5, cuff(), 0.12),                 # Manschette (ausgestellt)
        C(-3.0, 4.0, -2.0, 4, 4, 4, vambrace(outer), 0.45),        # Unterarmschiene
        C(-4.15, 4.6, -1.0, 1, 3, 2, fin(outer), 0.0),             # Grat außen
        C(-3.0, 8.0, -2.0, 4, 1, 4, gold(0.66), 0.36),             # Handgelenkring
        C(-3.0, 9.0, -2.0, 4, 1, 4, hand_plate(outer), 0.26),      # Handplatte (Handrücken + Handballen)
        C(-3.75, 8.7, -1.0, 1, 2, 2, sun_disc(), -0.12),           # Yang-Sonne auf dem Handrücken
    ])
    fist = Part(f'{s}_fist', cubes=[
        C(-3.0, 10.0, -2.0, 4, 1, 4, knuckles(), 0.05),            # Knöchelkappe (Schlagfläche)
        C(-3.0, 9.1, -2.75, 4, 2, 1, curl(), -0.05),               # eingerollte Finger, vorn
    ])
    open_ = Part(f'{s}_open', cubes=[
        C(-3.0, 10.1, -1.4, 1, 2, 2, finger(), -0.14),             # kleiner Finger (kürzer)
        C(-2.0, 10.1, -1.4, 1, 3, 2, finger(), -0.14),
        C(-1.0, 10.1, -1.4, 1, 3, 2, finger(), -0.14),
        C(0.0, 10.1, -1.4, 1, 2, 2, finger(), -0.14),              # Zeigefinger (innen)
        C(-1.5, 9.4, -2.9, 2, 2, 1, finger(), -0.12),              # Daumen vorn, quer gelegt
    ])
    return [base, fist, open_]


def gauntlets():
    parts = side(True) + side(False)
    return Model('gauntlets', 64, 64, parts)


# Vorschau: rechter Arm an der Spielerschulter (-5, 2, 0), linker bei (5, 2, 0).
_R = (0, 0, 0, -5, 2, 0)
_L = (0, 0, 0, 5, 2, 0)
_PUNCH_R = (-1.57, 0, 0, -5, 2, 0)
POSES = {
    'melee': {'r_base': _R, 'r_fist': _R, 'r_open': None, 'l_base': _L, 'l_fist': _L, 'l_open': None},
    'magic': {'r_base': _R, 'r_fist': None, 'r_open': _R, 'l_base': _L, 'l_fist': None, 'l_open': _L},
    'punch': {'r_base': _PUNCH_R, 'r_fist': _PUNCH_R, 'r_open': None, 'l_base': _L, 'l_fist': _L, 'l_open': None},
}
