"""Nivaris, die Alte vom Eirgletscher: uralte weiße Drachin, jetzt als langer Schlangendrache (96 Blöcke, 40 Glieder).

Erkennungsmerkmale (wie beim alten Vierbeiner, siehe _reference_nivaris_quadruped.py):
1. Kopf eines östlichen Drachen (seit 1.8.1, vorher eine Krone aus Eishörnern): langer Kamelkopf mit wulstiger Nase,
   zwei Hirschgeweihe aus altem Elfenbein, Mähne aus Eishaar an Wangen und Nacken, Rinderohren, lange Barteln von der
   Oberlippe und ein Kinnbart.
2. Augen: rechtes golden, linkes eisblau (rechts = -x, Blick nach -z).
3. Rückenkamm aus Kristallplatten (auf jedem zweiten Glied), dazwischen kleine Dornen.
4. Schwanzfächer aus Eisklingen nach dem letzten Glied.
5. Weiße Schuppen mit grauen Altersrändern, perlmuttgrauer, quer geschienter Bauch.
6. Herzenserbe in der Brust (Glied 3, Bauchseite): goldener Kern mit blauem Rand zwischen zwei Brustschilden.
7. Zerfranste, alte Schwingen mit Löchern (Cutout), kleine kräftige Läufe mit Klauen.

Vertrag mit dem Renderer (siehe docs/NIVARIS_SERPENT.md): Modellraum 1 Einheit = 1/16, +y nach unten, Kopf blickt
nach -z; Renderer-Faktor SCALE = 2,4, ein Glied = 16 Einheiten = 2,4 Blöcke. Alle Teile hängen direkt unter root mit
Drehpunkt 0 und ohne Drehung; Kinder (Kiefer, Flügelglieder, Beinglieder, Barteln) haben eigene Drehpunkte.

HD: gemalt mit RES = 2 Texeln je Einheit (Bild 512 x 512), Kotlin meldet 256 x 256 (Minecraft normiert die UVs).
Alle Maße liegen auf dem Halbeinheiten-Raster. Gemalt wird nach **Raumposition** (Maler bekommen den 3D-Punkt des
Texels im Teilraum): So laufen Schuppen nahtlos über Quaderkanten und von Glied zu Glied, egal wie die Quader liegen.

Achtung Abbildung: Ober- und Unterseite eines Quaders liegen im Spiel mit Zeile 0 bei z = max (geprüft am
Bytecode von ModelPart$Cube, 1.21.11); preview.py legt sie bei z = min an. Der Atlas wird deshalb mit der
Spiel-Abbildung gemalt, preview.py bekommt über Cube.paint die eigene (gleiche Raumpunkte, nur anders adressiert).

Texturen (alle mit denselben UVs):
* nivaris.png              Farbe (Membranlöcher und Fransen transparent, Cutout)
* nivaris_glow.png         alle Leuchttexel (Übersicht)
* nivaris_glow_eyes.png    Augen + Hornspitzen (Renderer: eyeGlow, lead)
* nivaris_glow_heart.png   Kehle, Maul innen, Herzkern (Renderer: throatGlow)
* nivaris_resonance.png    Linien in Membranen, Kamm, Klingen und entlang des Rückens (nur bei resonance)

Aufrufe:
    python tools/assets/boss_models.py --only nivaris        # Texturen + NivarisModelData.kt
    python tools/assets/preview.py nivaris <png> --poses kopf,vorderteil ...
    python tools/assets/nivaris_serpent_preview.py           # Gesamtwirkung auf Kurven (liegend, Flug, Nadel)
    python tools/assets/nivaris_model.py --taper             # Verjüngungstabelle für den Renderer
"""
import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)

from modelkit import Cube as _Cube, Model, Part, hexc, mix, ramp, rgb  # noqa: E402
from texkit import Image, save  # noqa: E402

RES = 2              # Texel je Modelleinheit
SCALE = 2.4          # Renderer-Faktor
SEG = 16             # Gliedlänge in Einheiten
SEGMENTS = 40        # ArenaLayout.NIVARIS_SEGMENTS
TEX_W, TEX_H = 256, 256

# ---------------------------------------------------------------------------------------------------------------------
# Paletten (dunkel -> hell)
# ---------------------------------------------------------------------------------------------------------------------
SCALES = rgb('#4f5663', '#7c8491', '#a9b0bb', '#d2d7de', '#ebeef2', '#f8f9fb', '#ffffff')
AGE = rgb('#5a5c62', '#77787d', '#94948f', '#b1aea5', '#c9c5ba')        # Altersränder: grau, leicht vergilbt
FROST = hexc('#7fa3c8')                                                 # kalte Schatten
PEARL = rgb('#5d5c66', '#86848f', '#adacb6', '#cecdd6', '#e6e5ec', '#f8f7fa')
MEMB = rgb('#33404f', '#4f5f72', '#6f8297', '#93a5b8', '#b6c5d3', '#d6e1ea')
ICE = rgb('#17324f', '#2c5a82', '#4f86b3', '#82b5da', '#b8dcf2', '#e4f5fe', '#ffffff')
CLAW = rgb('#1f2026', '#3a3a42', '#5f5d62', '#8f8a82', '#c2bcae', '#e4dfd2')
MOUTH = rgb('#140f19', '#261c2e', '#3b2b43', '#56405c', '#7a5a78')
GOLD_EYE = rgb('#7a4a08', '#c88a18', '#ffcf48', '#fff0a8')
BLUE_EYE = rgb('#0e3f6e', '#2f88c8', '#8fe0ff', '#e0f8ff')
GOLD_CORE = rgb('#b86e10', '#f0a828', '#ffd45c', '#fff0b0', '#fffbe6')
BLUE_RIM = rgb('#163f78', '#2f7fd0', '#5cb4ff', '#a4dcff')
RESO_WHITE = (236, 246, 255)
RESO_BLUE = (120, 200, 255)
THROAT_GLOW = (255, 205, 110)
MOUTH_GLOW = (150, 210, 255)

LIGHT = {'top': 0.12, 'bottom': -0.2, 'front': 0.0, 'back': -0.06, 'left': -0.04, 'right': -0.04}
NORMAL = {'front': (0, 0, -1), 'back': (0, 0, 1), 'right': (-1, 0, 0), 'left': (1, 0, 0), 'top': (0, -1, 0),
          'bottom': (0, 1, 0)}


def _hash(*v):
    h = 2166136261
    for x in v:
        h = ((h ^ (int(x) & 0xffffffff)) * 16777619) & 0xffffffff
        h ^= h >> 13
    return h / 0xffffffff


def vnoise(x, y, z, seed=0):
    """Glattes Wertrauschen 0..1 (Gitter 1 Einheit)."""
    ix, iy, iz = math.floor(x), math.floor(y), math.floor(z)
    fx, fy, fz = x - ix, y - iy, z - iz
    fx, fy, fz = fx * fx * (3 - 2 * fx), fy * fy * (3 - 2 * fy), fz * fz * (3 - 2 * fz)

    def g(a, b, c):
        return _hash(ix + a, iy + b, iz + c, seed)
    x00 = g(0, 0, 0) + (g(1, 0, 0) - g(0, 0, 0)) * fx
    x10 = g(0, 1, 0) + (g(1, 1, 0) - g(0, 1, 0)) * fx
    x01 = g(0, 0, 1) + (g(1, 0, 1) - g(0, 0, 1)) * fx
    x11 = g(0, 1, 1) + (g(1, 1, 1) - g(0, 1, 1)) * fx
    y0 = x00 + (x10 - x00) * fy
    y1 = x01 + (x11 - x01) * fy
    return y0 + (y1 - y0) * fz


def fbm(x, y, z, seed=0, octaves=3):
    v, a, t = 0.0, 1.0, 0.0
    for o in range(octaves):
        v += a * vnoise(x, y, z, seed + o * 17)
        t += a
        x, y, z, a = x * 2.03, y * 2.03, z * 2.03, a * 0.5
    return v / t


# ---------------------------------------------------------------------------------------------------------------------
# HD-Quader und Modell
# ---------------------------------------------------------------------------------------------------------------------

def _half(v):
    return abs(v * 2 - round(v * 2)) < 1e-6


class Cube(_Cube):
    """Quader mit RES Texeln je Einheit. paint(face, p, cube) bekommt den Raumpunkt p des Texels im Teilraum und
    liefert Farbe, (Farbe, Leuchten) oder (Farbe, Leuchten, Kanal) bzw. None (durchsichtig). Kanal: 'eyes' oder
    'heart' (Standard: ch des Quaders). reso(face, p, cube) liefert die Resonanzlinie (RGBA) oder None."""
    res = RES

    def __init__(self, x, y, z, w, h, d, paint, ch=None, reso=None):
        for v in (w, h, d):
            if not _half(v):
                raise SystemExit(f'Nivaris: Maß {v} liegt nicht auf dem Halbeinheiten-Raster')
        super().__init__(x, y, z, w, h, d, self._preview_paint)
        self.p3 = paint
        self.ch = ch
        self.reso = reso
        self.share = None     # gleicher Schlüssel = gleiche UVs (nur für Quader mit gleicher Form und gleichem Maler)

    def where(self, face, i, j, game=True):
        r = RES
        x0, y0, z0, w, h, d = self.x, self.y, self.z, self.w, self.h, self.d
        a, b = (i + 0.5) / r, (j + 0.5) / r
        if face == 'front':
            return (x0 + a, y0 + b, z0)
        if face == 'back':
            return (x0 + w - a, y0 + b, z0 + d)
        if face == 'right':
            return (x0, y0 + b, z0 + d - a)
        if face == 'left':
            return (x0 + w, y0 + b, z0 + a)
        zz = (z0 + d - b) if game else (z0 + b)
        return (x0 + a, y0 if face == 'top' else y0 + h, zz)

    def eval(self, face, i, j, game=True):
        p = self.where(face, i, j, game)
        res = self.p3(face, p, self)
        if res is None:
            return None, None, None, None
        if isinstance(res[0], tuple):
            col, glow = res[0], res[1]
            ch = res[2] if len(res) > 2 else self.ch
        else:
            col, glow, ch = res, None, None
        reso = self.reso(face, p, self) if self.reso else None
        return col, glow, ch, reso

    def _preview_paint(self, face, i, j, fw, fh):
        col, glow, _, _ = self.eval(face, i, j, game=False)
        if col is None:
            return None
        return col if glow is None else (col, glow)


def faces_px(c):
    """Flächen eines Quaders im Bild (Pixel): Name, u, v, Breite, Höhe."""
    u, v = c.u * RES, c.v * RES
    w, h, d = (int(round(k * RES)) for k in (c.w, c.h, c.d))
    return [('top', u + d, v, w, d), ('bottom', u + d + w, v, w, d), ('right', u, v + d, d, h),
            ('front', u + d, v + d, w, h), ('left', u + d + w, v + d, d, h), ('back', u + d + w + d, v + d, w, h)]


class SerpentModel(Model):
    """Wie modelkit.Model, aber UV-Raster in Modelleinheiten und Bild in RES-facher Größe; malt alle fünf Texturen."""

    def pack(self):
        cubes = sorted(self.cubes(), key=lambda c: (-math.ceil(c.uv_size[1] - 1e-6), -math.ceil(c.uv_size[0] - 1e-6)))
        x = y = row = 0
        shared = {}
        for c in cubes:
            if c.share is not None and c.share in shared:
                c.u, c.v = shared[c.share]
                continue
            cw, ch = (int(math.ceil(v - 1e-6)) for v in c.uv_size)
            if x + cw > self.tex_w:
                x, y, row = 0, y + row, 0
            if y + ch > self.tex_h:
                raise SystemExit(f'Atlas {self.name} zu klein ({self.tex_w}x{self.tex_h})')
            c.u, c.v = x, y
            if c.share is not None:
                shared[c.share] = (x, y)
            x += cw
            row = max(row, ch)
        print(f'{self.name}: {len(cubes)} Quader ({len(cubes) - len(shared) - sum(1 for c in cubes if c.share is None)} mit geteilten UVs), Atlas belegt bis y={y + row}/{self.tex_h} (Bild {self.tex_w * RES}px)')

    def paint(self):
        W, H = self.tex_w * RES, self.tex_h * RES
        self.images = {k: Image(W, H) for k in ('albedo', 'glow', 'eyes', 'heart', 'reso')}
        im = self.images
        done = set()
        for c in self.cubes():
            if c.share is not None:
                if c.share in done:
                    continue
                done.add(c.share)
            for name, fu, fv, fw, fh in faces_px(c):
                for iy in range(fh):
                    for ix in range(fw):
                        col, glow, ch, reso = c.eval(name, ix, iy, game=True)
                        if col is None:
                            continue
                        im['albedo'].set(fu + ix, fv + iy, col)
                        if glow is not None:
                            im['glow'].set(fu + ix, fv + iy, glow)
                            im['heart' if ch == 'heart' else 'eyes'].set(fu + ix, fv + iy, glow)
                        if reso is not None:
                            im['reso'].set(fu + ix, fv + iy, reso)
        save(im['albedo'], f'textures/entity/{self.texture}.png')
        save(im['glow'], f'textures/entity/{self.texture}_glow.png')
        return im['albedo'], im['glow']


def variants(model, albedo, glow):
    save(model.images['eyes'], 'textures/entity/nivaris_glow_eyes.png')
    save(model.images['heart'], 'textures/entity/nivaris_glow_heart.png')
    save(model.images['reso'], 'textures/entity/nivaris_resonance.png')


# ---------------------------------------------------------------------------------------------------------------------
# Maler (alle nach Raumposition)
# ---------------------------------------------------------------------------------------------------------------------

def _plane(face, p):
    """Die beiden Flächenkoordinaten eines Raumpunkts (für Facetten und Risse)."""
    x, y, z = p
    if face in ('front', 'back'):
        return x, y
    if face in ('left', 'right'):
        return z, y
    return x, z


def _edge_dist(face, p, c):
    """Abstand (Einheiten) zur nächsten Kante der Fläche."""
    x, y, z = p
    dx = min(x - c.x, c.x + c.w - x)
    dy = min(y - c.y, c.y + c.h - y)
    dz = min(z - c.z, c.z + c.d - z)
    if face in ('front', 'back'):
        return min(dx, dy)
    if face in ('left', 'right'):
        return min(dz, dy)
    return min(dx, dz)


def _cold(col, v, k=0.45):
    """Kalte, blaue Schatten in dunklen Bereichen (Eislicht statt grauem Schmutz)."""
    return mix(col, FROST, max(0.0, min(1.0, (0.55 - v) * k)))


def scale_field(z, s, zp, wp, seed):
    """Überlappende Schuppen: Reihen entlang z (Länge zp), Spalten um den Körper (Breite wp), jede zweite Reihe
    versetzt. Jede Schuppe ist eine nach vorn verlängerte Ellipse; die weiter vorn liegende Reihe deckt die nächste
    (freie Kante zeigt zum Schwanz, +z) und wirft dort einen Schatten.
    Liefert (Helligkeitsversatz, 'rim'|'shadow'|None, Schuppen-Alter 0..1)."""
    row0 = math.floor(z / zp)
    best = None
    shadow = 0.0
    for r in (row0 - 2, row0 - 1, row0):
        shift = 0.5 * (r % 2)
        c0 = math.floor(s / wp - shift)
        zc = (r + 0.5) * zp
        for c in (c0 - 1, c0, c0 + 1):
            sc = (c + 0.5 + shift) * wp
            dz = (z - zc) / (0.92 * zp)
            ds = (s - sc) / (0.66 * wp)
            d = ds * ds + (dz * dz if dz > 0 else dz * dz * 0.2)
            if d < 1.0:
                if best is None or r < best[0] or (r == best[0] and d < best[2]):
                    best = (r, c, d, dz, ds)
            elif dz > 0 and d < 1.5:
                shadow = max(shadow, (1.5 - d) / 0.5)
    if best is None:
        return -0.08, 'shadow', 0.5
    r, c, d, dz, ds = best
    # Schatten nur von Schuppen, die über dieser liegen (weiter vorn)
    if shadow > 0 and r == row0 - 2:
        shadow = 0.0
    period = max(1, round(16 / zp)) if abs(16 / zp - round(16 / zp)) < 1e-6 else 997
    age = _hash(c, r % period, seed)
    dv = 0.08 * (1.0 - d) - 0.02 + (_hash(c, r % 4, seed + 5) - 0.5) * 0.08   # gewölbt; jede Schuppe etwas anders
    dv -= 0.07 * ds                                   # Licht von oben: obere Hälfte heller (s wächst nach unten)
    if dz > 0.1:
        dv += 0.05 * min(1.0, dz)                     # die freie Kante hebt sich ins Licht
    if abs(ds) < 0.12 and dz > -0.6:
        dv += 0.03                                    # feiner Kiel
    kind = None
    if d > 0.86 and dz > 0.25:
        kind = 'rim'
    elif shadow > 0.0:
        kind = 'shadow'
        dv -= 0.15 * min(1.0, shadow) ** 0.7
    return dv, kind, age


def hide(seed=0, zp=4.0, wp=3.4, belly=2.2, throat=False, radius=7.0, cy=0.0, base=0.72, joint=True):
    """Rumpfhaut: weiße Schuppen (oben/Flanken), perlmuttgraue Querschienen (Bauch). Achse = z durch (0, cy)."""
    def paint(face, p, cube):
        x, y, z = p
        yy = y - cy
        th = math.atan2(x, -yy)
        a = abs(th)
        rr = math.hypot(x, yy) or 1.0
        ny = yy / rr
        lit = -0.13 * ny + LIGHT[face] * 0.45
        if joint and z < 1.0:
            lit -= 0.07 * min(1.0, (1.0 - z) / 1.5)   # Schatten unter dem überlappenden Vorderglied
        weather = fbm(x * 0.22, y * 0.22, z * 0.22, seed + 3)
        if a > belly:
            return scute(face, p, lit, seed, throat)
        dv, kind, age = scale_field(z, a * radius, zp, wp, seed)
        v = base + lit + dv + (weather - 0.5) * 0.12 + (_hash(x * 4, y * 4, z * 4, seed) - 0.5) * 0.035
        if a > belly - 0.16:
            v -= 0.06                                 # Saum zum Bauch: kleinere, dunklere Schuppen
            dv2, kind2, _ = scale_field(z * 2.0, a * radius * 2.0, zp, wp, seed + 9)
            v += dv2 * 0.5
            kind = kind2 or kind
        if kind == 'rim':
            if age > 0.7 or weather > 0.74:
                return _cold(ramp(AGE, 0.62 + (1 - age) * 0.5 + lit), v - 0.04)  # grauer Altersrand
            v -= 0.03
        if age > 0.955 and kind is None:
            v -= 0.16                                 # abgeplatzte Schuppe
        col = ramp(SCALES, v)
        if weather > 0.68:
            col = mix(col, ramp(AGE, v + 0.1), (weather - 0.68) * 1.6)
        if _hash(x * 6, y * 6, z * 6, seed + 1) > 0.992:
            col = mix(col, hexc('#f2fbff'), 0.8)      # Reifglitzer
        return _cold(col, v)
    return paint


def scute(face, p, lit, seed, throat=False):
    """Bauchschienen: quer liegende Perlmuttplatten (4 Einheiten lang), dunkle Fugen, heller Vordergrat."""
    x, y, z = p
    fb = (z / 4.0) % 1.0
    v = 0.6 + lit * 0.7
    if fb > 0.87:
        v -= 0.26
    elif fb < 0.12:
        v += 0.1
    else:
        v += (0.5 - fb) * 0.1
    v -= min(1.0, abs(x) / 6.5) ** 2 * 0.16
    v += (fbm(x * 0.3, y * 0.3, z * 0.3, seed + 7) - 0.5) * 0.1
    col = ramp(PEARL, v)
    hue = mix(hexc('#c4d4f2'), hexc('#ecd0e4'), 0.5 + 0.5 * math.sin(z * 0.37 + x * 0.6))
    col = mix(col, hue, 0.08)
    if throat and face == 'bottom' and abs(x) < 0.9:
        return (mix(col, hexc('#ffe7a0'), 0.35), THROAT_GLOW + (200,), 'heart')
    return _cold(col, v, 0.3)


def hide_reso(face, p, cube):
    """Resonanz: feine Linie auf dem Rücken und je eine Seitenlinie auf den Flanken."""
    x, y, z = p
    if face == 'top' and abs(x) < 0.5:
        return RESO_WHITE + (170,)
    th = abs(math.atan2(x, -y))
    if face in ('left', 'right') and abs(th - 1.62) < 0.07:
        return RESO_BLUE + (120,)
    return None


def ice(base=0.58, seed=0, axis='-y', tip_glow=0, edge=0.2, ch='eyes'):
    """Eis: kantige Facetten, heller Grat an den Kanten, tiefblauer Kern, feine Risse. axis = Richtung zur Spitze;
    tip_glow > 0: das letzte Fünftel leuchtet schwach (Kanal ch)."""
    def paint(face, p, c):
        x, y, z = p
        rel = {'-y': (c.y + c.h - y) / c.h, '+y': (y - c.y) / c.h, '+z': (z - c.z) / c.d, '-z': (c.z + c.d - z) / c.d,
               '+x': (x - c.x) / c.w, '-x': (c.x + c.w - x) / c.w}[axis]
        a, b = _plane(face, p)
        v = base + LIGHT[face] * 1.1 + (rel - 0.5) * 0.24
        v += (_hash(math.floor((a * 0.9 + b * 0.5) / 1.7), math.floor((a * 0.4 - b * 0.8) / 2.1), seed) - 0.5) * 0.18
        if abs(math.sin(a * 1.1 - b * 1.9 + seed)) < 0.05:
            v -= 0.2                                   # Riss
        ed = _edge_dist(face, p, c)
        if ed < 0.5:
            v += edge
        else:
            v -= 0.08 * min(1.0, (ed - 0.5) / 1.5)    # Kern dunkler, wirkt tiefer
        col = ramp(ICE, v)
        if _hash(x * 8, y * 8, z * 8, seed) > 0.985:
            col = mix(col, hexc('#ffffff'), 0.7)
        if tip_glow and rel > 0.8:
            return (mix(col, hexc('#e8f6ff'), 0.55), (190, 230, 255, tip_glow), ch)
        return col
    return paint


def ice_reso(face, p, c):
    """Resonanz in Kristallen und Klingen: helle Mittelader, blauer Saum an der Kante."""
    if _edge_dist(face, p, c) < 0.5:
        return RESO_BLUE + (130,)
    x, y, z = p
    if face in ('left', 'right') and abs(z - (c.z + c.d / 2)) < 0.5:
        return RESO_WHITE + (220,)
    if face in ('front', 'back') and abs(x - (c.x + c.w / 2)) < 0.5:
        return RESO_WHITE + (220,)
    return None


def claw(base=0.45, seed=0, axis='-z'):
    """Klaue/Zahn: dunkles Horn an der Wurzel, elfenbeinhell zur Spitze."""
    def paint(face, p, c):
        x, y, z = p
        rel = {'-z': (c.z + c.d - z) / c.d, '+y': (y - c.y) / c.h, '+z': (z - c.z) / c.d, '-y': (c.y + c.h - y) / c.h}[axis]
        v = base + LIGHT[face] + rel * 0.4 + (vnoise(x * 2, y * 2, z * 2, seed) - 0.5) * 0.1
        if _edge_dist(face, p, c) < 0.5:
            v += 0.06
        return ramp(CLAW, v)
    return paint


def membrane(length, depth, side, kind, seed=0):
    """Schwingenhaut als Platte im Teilraum: x entlang des Knochens (0 = Wurzel), y vom Knochen zur Hinterkante.
    kind: 'inner' (am Rumpf), 'mid', 'tip' (Dreieck zur Flügelspitze). Löcher und Fransen sind durchsichtig."""
    sign = -1 if side == 'r' else 1
    ribs = {'inner': [0.0, 0.5, 1.0], 'mid': [0.0, 0.5, 1.0], 'tip': [0.0, 0.42, 0.75, 1.0]}[kind]
    holes = []
    count = {'inner': 3, 'mid': 4, 'tip': 2}[kind]
    for i in range(count):
        s = (i + 0.25 + 0.5 * _hash(seed, i, 1)) / count * (0.75 if kind == 'tip' else 0.95)
        t = 0.32 + 0.45 * _hash(seed, i, 2)
        r = 1.3 + 2.6 * _hash(seed, i, 3)
        holes.append((s, t, r))

    def limit(s):
        if kind == 'inner':
            d = 1.0 - 0.16 * s
        elif kind == 'mid':
            d = 0.9 - 0.06 * s
        else:
            d = max(0.04, 0.86 * (1 - s) ** 0.8)
        for a, b in zip(ribs, ribs[1:]):
            if a <= s <= b:
                k = (s - a) / max(b - a, 1e-6)
                d -= 0.17 * math.sin(math.pi * k) ** 0.7 * (1.0 if kind != 'tip' else 0.6)
        d -= 0.045 * abs(math.sin(s * math.pi * 5.5))
        d -= 0.05 * fbm(s * length * 0.35, 0, 0, seed + 4)
        # wenige, unregelmäßige Risse in der Hinterkante (alte Segeltuchkante)
        tear = _hash(seed, math.floor(s * 7 + 0.5 * math.sin(s * 9)), 9)
        if tear > 0.84:
            k = abs(((s * 7) % 1.0) - 0.5) * 2           # V-förmiger Einriss
            d -= (0.1 + 1.6 * (tear - 0.84)) * (1 - k)
        return d

    def st(p):
        return (sign * p[0]) / length, p[1] / depth

    def inside(s, t):
        if t > limit(s) or s < 0 or s > 1:
            return False
        for hs, ht, hr in holes:
            dx, dy = (s - hs) * length, (t - ht) * depth
            wob = 1 + 0.35 * math.sin(math.atan2(dy, dx) * 3 + hs * 10)
            if dx * dx + dy * dy < (hr * wob) ** 2:
                return False
        return True

    def vein(s, t):
        best = 99.0
        # drei Adern je Platte, vom Knochen zur Hinterkante gefächert und leicht gebogen
        for i in range(3):
            s0 = (i + 0.35 + 0.3 * _hash(seed, i, 5)) / 3
            fan = -0.25 + 0.5 * (s0 - 0.5) + 0.12 * (_hash(seed, i, 6) - 0.5)
            best = min(best, abs(s - (s0 + fan * t + 0.05 * math.sin(t * 5 + i))) * length)
        return best

    def paint(face, p, c):
        if face not in ('front', 'back'):
            return None
        s, t = st(p)
        if not inside(s, t):
            return None
        lim = limit(s)
        v = 0.6 + (fbm(p[0] * 0.4, p[1] * 0.4, seed, seed) - 0.5) * 0.16 - 0.2 * t
        if face == 'back':
            v -= 0.07
        if t < 0.05:
            v -= 0.16                      # Saum am Knochen
        if lim - t < 0.045:
            v -= 0.16                      # ausgefranster, dunklerer Rand
        if vein(s, t) < 0.45:
            v += 0.09                      # Adern heller (durchscheinend)
        v += 0.05 * math.sin(s * length * 0.9 + t * 3) * (1 - t)   # leichte Falten
        for hs, ht, hr in holes:           # Lochränder dunkler, wie verbranntes Segeltuch
            dd = math.hypot((s - hs) * length, (t - ht) * depth)
            if dd < hr * 1.35 + 0.7:
                v -= 0.12
        col = ramp(MEMB, v)
        if lim - t < 0.03 and _hash(p[0] * 4, p[1] * 4, seed) > 0.55:
            col = mix(col, hexc('#eef7ff'), 0.6)   # Reif an den Fransen
        return col

    def lines(face, p, c):
        if face not in ('front', 'back'):
            return None
        s, t = st(p)
        if not inside(s, t):
            return None
        d = vein(s, t)
        if d < 0.5:
            return RESO_WHITE + (235,)
        if d < 1.1:
            return RESO_BLUE + (150,)
        if limit(s) - t < 0.035:
            return RESO_BLUE + (110,)
        return None
    return paint, lines


def heart_core(face, p, c):
    """Herzenserbe: goldener Kern, nach außen blau, feine Kristallrisse; hell im Herzkanal."""
    x, y, z = p
    cx, cz = c.x + c.w / 2, c.z + c.d / 2
    r = math.hypot((x - cx) / (c.w / 2), (z - cz) / (c.d / 2))
    if face != 'bottom':
        return (ramp(BLUE_RIM, 0.45), (90, 170, 255, 190), 'heart')
    if abs(math.sin((x - cx) * 2.4 + (z - cz) * 1.1)) < 0.08 and r > 0.25:
        return (ramp(GOLD_CORE, 0.35), (255, 170, 70, 200), 'heart')
    if r < 0.42:
        return (ramp(GOLD_CORE, 0.98 - r), (255, 225, 130, 255), 'heart')
    if r < 0.78:
        return (ramp(GOLD_CORE, 0.62 - (r - 0.42)), (255, 192, 82, 240), 'heart')
    return (ramp(BLUE_RIM, 0.75), (110, 190, 255, 230), 'heart')


def breast_shield(seed):
    """Brustschild neben dem Herzen: große Perlmuttplatte, zur Mitte golden durchädert (schwach leuchtend)."""
    base = scute

    def paint(face, p, c):
        x, y, z = p
        col = base(face, p, LIGHT[face] * 0.5 + 0.06, seed)
        if isinstance(col[0], tuple):
            col = col[0]
        if _edge_dist(face, p, c) < 0.5:
            col = mix(col, hexc('#f8f6fb'), 0.35)
        if face == 'bottom' and abs(x) < 2.2 and abs(math.sin(z * 1.3 + x * 2.0 + seed)) < 0.16:
            return (mix(col, hexc('#ffdf8a'), 0.5), (255, 200, 100, 120), 'heart')
        return col
    return paint


# ---------------------------------------------------------------------------------------------------------------------
# Rumpfglieder
# ---------------------------------------------------------------------------------------------------------------------

def shell(half, z0, length, paint, reso=hide_reso, cy=0.0):
    """Gerundeter Querschnitt (2*half breit und hoch) aus Kern und zwei Stufen je Seite."""
    k = half - 1          # Kern
    a = half - 3          # breite Stufe 1 dick
    b = half - 2          # schmale Stufe 0,5 dick
    cubes = [Cube(-k, cy - k, z0, 2 * k, 2 * k, length, paint, reso=reso)]
    for sgn in (-1, 1):
        # oben/unten
        y1 = cy - half if sgn < 0 else cy + k
        cubes.append(Cube(-a, y1, z0, 2 * a, 1, length, paint, reso=reso))
        y2 = cy - k - 0.5 if sgn < 0 else cy + k
        cubes.append(Cube(-b, y2, z0, 2 * b, 0.5, length, paint, reso=reso))
        # Flanken
        x1 = -half if sgn < 0 else k
        cubes.append(Cube(x1, cy - a, z0, 1, 2 * a, length, paint, reso=reso))
        x2 = -k - 0.5 if sgn < 0 else k
        cubes.append(Cube(x2, cy - b, z0, 0.5, 2 * b, length, paint, reso=reso))
    return cubes


def dorsal_spines(z_list, top, seed):
    """Kleine Dornen auf dem Rückengrat (alte, abgewetzte Eisdornen)."""
    cubes = []
    for i, z in enumerate(z_list):
        cubes.append(Cube(-1, top - 1.5, z, 2, 1.5, 4, ice(0.42, seed + i, axis='-y', edge=0.14)))
        cubes.append(Cube(-0.5, top - 2.5, z + 1.5, 1, 1, 2.5, ice(0.55, seed + i + 5, axis='-y', edge=0.14)))
    return cubes


def crystal(name, pivot, rot, height, length, seed, thick=3.0):
    """Kristallplatte des Rückenkamms: Splitter, der nach oben schmaler wird und mit jeder Stufe nach hinten rückt;
    wächst entlang -y (der Teil ist nach hinten geneigt)."""
    h1 = round(height * 0.3 * 2) / 2
    h2 = round(height * 0.4 * 2) / 2
    h3 = height - h1 - h2
    L = length
    t2, t3 = max(1.0, thick - 1), max(1.0, thick - 2)
    l2, l3 = round(L * 0.7 * 2) / 2, max(1.5, round(L * 0.4 * 2) / 2)
    cubes = [
        Cube(-thick / 2, -h1, -L / 2, thick, h1, L, ice(0.5, seed, '-y', edge=0.14), reso=ice_reso),
        Cube(-t2 / 2, -h1 - h2, -L / 2 + 1, t2, h2, l2, ice(0.62, seed + 1, '-y', edge=0.16), reso=ice_reso),
        Cube(-t3 / 2, -height, -L / 2 + 1 + l2 - l3, t3, h3, l3, ice(0.78, seed + 2, '-y', tip_glow=40, edge=0.16,
                                                                      ch='eyes'), reso=ice_reso),
    ]
    return Part(name, pivot, rot, cubes=cubes)


def segment(name, kind='plain'):
    """Rumpfglied z 0..16 (Kern ragt 3 Einheiten nach vorn ins Vorderglied, damit Biegungen keine Lücken zeigen)."""
    paint = hide(seed=11)
    cubes = shell(7, -0.5, 17, paint)
    cubes.append(Cube(-5, -5, -3, 10, 10, 2.5, paint, reso=hide_reso))          # Gelenkfüller
    for k, c in enumerate(cubes):
        c.share = f'segment_shell_{k}'       # alle drei Rumpfglieder teilen sich die Haut (gleiche Form, gleicher Maler)
    children = []
    if kind == 'dorsal':
        cubes += dorsal_spines([1.5], -7, 40)
        children.append(crystal('crest', (0, -6.5, 9), (-34, 0, 0), 15, 9, 60))
        children.append(crystal('crest_shard', (1.5, -6.5, 13), (-48, 0, 22), 7, 5, 64, thick=2.0))
    else:
        cubes += dorsal_spines([2.5, 10.5], -7, 44)
    if kind == 'heart':
        cubes.append(Cube(-5.5, 6.5, 1.5, 4, 1.5, 13, breast_shield(70)))
        cubes.append(Cube(1.5, 6.5, 1.5, 4, 1.5, 13, breast_shield(71)))
        cubes.append(Cube(-1.5, 6.5, 3.5, 3, 1, 9, heart_core, ch='heart'))
    return Part(name, cubes=cubes, children=children)


def neck():
    """Erstes Glied hinter dem Kopf: schlanker (12 x 12), Kehlnaht leuchtet am Bauch, kleine Dornen statt Kamm."""
    paint = hide(seed=13, throat=True)
    cubes = shell(6, -0.5, 17, paint)
    cubes.append(Cube(-4, -4, -2.5, 8, 8, 2, paint, reso=hide_reso))
    cubes += dorsal_spines([3, 10], -6, 48)
    return Part('neck', cubes=cubes)


# ---------------------------------------------------------------------------------------------------------------------
# Kopf
# ---------------------------------------------------------------------------------------------------------------------

def head_hide(seed):
    """Kopfschuppen: kleiner als am Rumpf, um die Kopfachse (y = -1) gelegt."""
    return hide(seed=seed, zp=2.6, wp=2.4, belly=2.35, radius=6.0, cy=-1.0, base=0.76, joint=False)


def eye_paint(side):
    """Auge als eigener Quader: Iris (rechts golden, links eisblau), senkrechter Schlitz, dunkler Lidrand."""
    pal = GOLD_EYE if side == 'r' else BLUE_EYE
    out_face = 'right' if side == 'r' else 'left'

    def paint(face, p, c):
        x, y, z = p
        if face not in (out_face, 'front'):
            return ramp(SCALES, 0.3)
        if face == 'front':
            du = (x - (c.x + c.w / 2)) / (c.w / 2)
        else:
            du = (z - (c.z + c.d / 2)) / (c.d / 2)
        dv = (y - (c.y + c.h / 2)) / (c.h / 2)
        if abs(dv) > 0.74 or abs(du) > 0.86:
            return ramp(AGE, 0.12)                      # Lidrand
        if abs(du) < (0.26 if face == 'front' else 0.17) and abs(dv) < 0.62:
            return (hexc('#120c08'), pal[1][:3] + (140,), 'eyes')     # Schlitzpupille
        k = 1 - math.hypot(du, dv) * 0.6
        col = ramp(pal, 0.4 + k * 0.6)
        if dv < -0.3 and du < -0.3:
            col = mix(col, hexc('#ffffff'), 0.55)        # Glanzlicht
        return (col, pal[2][:3] + (255,), 'eyes')
    return paint


def _teeth(face, p, c, rows_from_edge, upper):
    """Zahnreihe: spitze, elfenbeinfarbene Zähne mit dunklen Lücken (nur an den Seiten)."""
    x, y, z = p
    k = (z / 1.5) % 1.0
    tip = rows_from_edge < 0.5
    if (k < 0.42 and not tip) or (0.08 < k < 0.3 and tip):
        return claw(0.78, 5 if upper else 6, '+y' if upper else '-y')(face, p, c)
    return ramp(MOUTH, 0.25)


def snout_paint(seed=63):
    base = head_hide(seed)

    def paint(face, p, c):
        x, y, z = p
        if face == 'bottom':
            return (ramp(MOUTH, 0.35 + (z + 20) / 40), MOUTH_GLOW + (70,), 'heart')     # Gaumen
        edge = c.y + c.h - y
        if face in ('left', 'right') and edge < 1.0:
            return _teeth(face, p, c, edge, True)
        if face == 'front' and edge < 0.5:
            return ramp(MOUTH, 0.3)
        return base(face, p, c)
    return paint


def nose_paint(face, p, c):
    """Nasenwulst: oben zwei schmale, schräge Nüstern (dunkel, heller Rand), vorn eine glatte, glänzende Kuppe mit
    feiner Mittelfurche (von vorn soll nichts wie ein zweites Augenpaar wirken)."""
    x, y, z = p
    if face == 'top':
        for sx in (-1.7, 1.7):
            u = (x - sx) * 0.9 - (z - (c.z + 1.4)) * 0.45 * (1 if sx > 0 else -1)
            w = (z - (c.z + 1.4)) * 0.9
            d = math.hypot(u * 1.6, w * 0.7)
            if d < 0.7:
                return hexc('#2a3040')                                   # Nüster
            if d < 1.15:
                return ramp(PEARL, 0.66)                                 # wulstiger Rand
    col = head_hide(69)(face, p, c)
    if isinstance(col[0], tuple):
        col = col[0]
    if face == 'front':
        if abs(x) < 0.25 and y < c.y + c.h - 0.8:
            return mix(col, hexc('#8b93a3'), 0.5)                        # Mittelfurche
        return mix(col, hexc('#ffffff'), 0.2)
    return col


def jaw_paint():
    base = head_hide(64)

    def paint(face, p, c):
        x, y, z = p
        if face == 'top':
            if abs(x) < 1.4:
                return (ramp(MOUTH, 0.75), MOUTH_GLOW + (170,), 'heart')            # Zunge, Kehle glüht
            if abs(x) > c.w / 2 - 0.5:
                return claw(0.72, 7, '-y')(face, p, c) if (z / 1.5) % 1.0 < 0.42 else ramp(MOUTH, 0.3)
            return (ramp(MOUTH, 0.42), (110, 180, 255, 90), 'heart')
        edge = y - c.y
        if face in ('left', 'right') and edge < 1.0:
            return _teeth(face, p, c, edge, False)
        if face == 'bottom':
            return scute(face, (x, y, z * 0.6), LIGHT[face] * 0.5 + 0.08, 65)
        return base(face, p, c)
    return paint


def skull_paint():
    base = head_hide(61)

    def paint(face, p, c):
        x, y, z = p
        if face == 'bottom' and z > -6:
            # Kehle unter dem Schädel: Fortsetzung der leuchtenden Kehlnaht des Halses
            col = scute(face, p, LIGHT[face] * 0.5, 62)
            if isinstance(col[0], tuple):
                col = col[0]
            if abs(x) < 0.9:
                return (mix(col, hexc('#ffe7a0'), 0.35), THROAT_GLOW + (220,), 'heart')
            return col
        if face in ('left', 'right') and -8 < z < -3 and y < -3.5:
            return _cold(ramp(SCALES, 0.42), 0.42)                # Brauenschatten hinter den Augen
        return base(face, p, c)
    return paint


def horn(name, pivot, rot, segs, seed, broken=False, tine=None):
    """Eishorn aus Gliedern, wächst entlang -y; jedes Glied biegt um x (nach hinten). Spitze schwach leuchtend.
    tine = (Glied, Länge): kleiner Seitenspross wie bei einem Geweih."""
    root = cur = None
    prev = 0
    for i, (w, l, bend) in enumerate(segs):
        last = i == len(segs) - 1
        glow = (0 if broken else 110) if last else 0
        c = Cube(-w / 2, -l, -w / 2, w, l, w, ice(0.5 + i * 0.07, seed + i, '-y', tip_glow=glow, edge=0.1),
                 ch='eyes', reso=ice_reso if i else None)
        if cur is None:
            cur = root = Part(name, pivot, rot, cubes=[c])
        else:
            nxt = Part(f'{name}_{i}', (0, -prev + 0.5, 0), (bend, 0, 0), cubes=[c])
            cur.add(nxt)
            cur = nxt
        if broken and last:
            # Bruchkante: helle, zersplitterte Fläche oben
            cur.cubes.append(Cube(-w / 2 + 0.5, -l - 0.5, -w / 2 + 0.5, max(0.5, w - 1), 0.5, max(0.5, w - 1),
                                  ice(0.85, seed + 9, '-y', edge=0.05)))
        if tine and i == tine[0]:
            cur.add(Part(f'{name}_tine', (0, -l * 0.6, 0), (-10, 0, 40 if pivot[0] > 0 else -40), cubes=[
                Cube(-0.5, -tine[1], -0.5, 1, tine[1], 1, ice(0.7, seed + 7, '-y', tip_glow=90, edge=0.2),
                     ch='eyes')]))
        prev = l
    return root


# --- Seit 1.8.1 (Crix: „das Gesicht sieht immer noch komisch aus“): Kopf eines östlichen Drachen (Loong) -------------
# Langer, leicht nach oben geschwungener Kamelkopf mit wulstiger Nase, Lippenwülsten und langem Unterkiefer; große,
# runde Augen unter schweren Brauen; zwei verzweigte Hirschgeweihe, die nach hinten oben schwingen; eine Mähne aus
# Eishaar an Wangen und Nacken; kleine Rinderohren; zwei lange Barteln von der Oberlippe und ein Kinnbart.

ANTLER = rgb('#4d4a45', '#7a7466', '#a9a18c', '#d3cbb4', '#efe9d8', '#fffbf0')     # Geweih: altes Elfenbein
MANE = rgb('#1d3b5c', '#3f6f9c', '#76a7cf', '#acd2ec', '#dcefff', '#ffffff')        # Mähne: Eishaar


def antler_paint(seed, tip_glow=0):
    """Geweih: Elfenbein mit Ringwülsten an der Wurzel, glatter zur Spitze; Spitze kalt leuchtend (Kanal eyes)."""
    def paint(face, p, c):
        x, y, z = p
        rel = (c.y + c.h - y) / c.h                 # 0 = Wurzel, 1 = Spitze (wächst nach -y)
        a, b = _plane(face, p)
        v = 0.5 + LIGHT[face] * 1.2 + rel * 0.18
        if rel < 0.55 and (y * 1.6) % 1.0 < 0.22:
            v -= 0.16                               # Ringwulst
        v += (vnoise(a * 1.4, b * 1.4, seed, seed) - 0.5) * 0.16
        if _edge_dist(face, p, c) < 0.5:
            v += 0.06
        col = ramp(ANTLER, v)
        if tip_glow and rel > 0.78:
            return (mix(col, hexc('#dff3ff'), 0.6), (170, 220, 255, tip_glow), 'eyes')
        return col
    return paint


def mane_paint(seed, tip_glow=0):
    """Eishaar: Strähnen entlang +z (zur Spitze heller und durchscheinend weiß), dunkle Fugen zwischen den Strähnen."""
    def paint(face, p, c):
        x, y, z = p
        rel = (z - c.z) / c.d
        a, b = _plane(face, p)
        strand = math.sin((x * 2.3 + y * 1.7) * 1.9 + seed) * 0.5 + 0.5
        v = 0.42 + rel * 0.45 + LIGHT[face] * 0.9 + (strand - 0.5) * 0.22
        if strand < 0.12:
            v -= 0.12
        v += (vnoise(a * 2, b * 2, z * 0.5, seed) - 0.5) * 0.1
        col = ramp(MANE, v)
        if tip_glow and rel > 0.8:
            return (mix(col, hexc('#f0faff'), 0.5), (180, 225, 255, tip_glow), 'eyes')
        return col
    return paint


def antler(side):
    """Hirschgeweih: Hauptstange in vier Gliedern, die sich nach hinten krümmt; Sprossen nach vorn oben und außen.
    Wächst entlang -y."""
    s = -1 if side == 'r' else 1
    beam = [(2.5, 5, 0), (2, 5, -16), (1.5, 4.5, -18), (1, 4, -14)]
    tines = {1: [(4.5, 48, 0)], 2: [(3.5, 30, s * 38)], 3: [(2.5, 34, -s * 20)]}
    root = cur = None
    prev = 0
    for i, (w, l, bend) in enumerate(beam):
        last = i == len(beam) - 1
        c = Cube(-w / 2, -l, -w / 2, w, l, w, antler_paint(200 + i + (side == 'l') * 10, 120 if last else 0),
                 reso=ice_reso if i else None)
        if cur is None:
            cur = root = Part(f'antler_{side}', (s * 3.25, -7, -2.5), (-34, 0, s * 18), cubes=[c])
        else:
            nxt = Part(f'antler_{side}_{i}', (0, -prev + 0.5, 0), (bend, 0, 0), cubes=[c])
            cur.add(nxt)
            cur = nxt
        for k, (ln, pitch, roll) in enumerate(tines.get(i, [])):
            tw = 1.0 if ln < 4 else 1.5
            cur.add(Part(f'antler_{side}_{i}_tine{k}', (0, -l * 0.55, 0), (pitch, 0, roll), cubes=[
                Cube(-tw / 2, -ln, -tw / 2, tw, ln, tw, antler_paint(220 + i * 3 + k, 110))]))
        prev = l
    return root


def mane_tuft(name, pivot, rot, length, width, seed, bend=-16):
    """Eine Strähne: flaches Blatt entlang +z, das zweite Glied knickt um [bend] Grad ab (hängt nach)."""
    l1 = round(length * 0.55 * 2) / 2
    l2 = round((length - l1) * 2) / 2
    w2 = max(0.5, width - 0.5)
    tuft = Part(name, pivot, rot, cubes=[Cube(-0.5, -width / 2, 0, 1, width, l1, mane_paint(seed))])
    tuft.add(Part(f'{name}_tip', (0, 0, l1 - 0.5), (bend, 0, 0), cubes=[
        Cube(-0.5, -w2 / 2, 0, 1, w2, l2, mane_paint(seed + 1, 70))]))
    return tuft


def mane(side):
    """Mähne an Wange und Kieferwinkel: vier Strähnen wehen nach hinten, leicht nach außen."""
    s = -1 if side == 'r' else 1
    spec = [(-4, 11, 14, 14), (-1, 14, 4, 18), (2, 13, -8, 20), (5, 10, -20, 16)]
    tufts = [mane_tuft(f'mane_{side}_{i}', (0, y, 0), (pitch, s * yaw, 0), ln, 2 if i in (1, 2) else 1.5,
                       240 + i * 2 + (side == 'l'))
             for i, (y, ln, pitch, yaw) in enumerate(spec)]
    return Part(f'mane_{side}', (s * 6, -1, -3), cubes=[], children=tufts)


def mane_top():
    """Nackenmähne zwischen den Geweihen: drei Strähnen, die über den Halsansatz nach hinten wehen."""
    tufts = [mane_tuft('mane_top_0', (0, 0, 0), (24, 0, 0), 12, 3, 260),
             mane_tuft('mane_top_1', (-2, 0.5, -1), (18, -16, 0), 10, 2.5, 262),
             mane_tuft('mane_top_2', (2, 0.5, -1), (18, 16, 0), 10, 2.5, 264)]
    return Part('mane_top', (0, -7, 0), cubes=[], children=tufts)


def ear(side):
    """Kleines Rinderohr hinter dem Auge, nach hinten außen gelegt."""
    s = -1 if side == 'r' else 1
    hh = head_hide(90 + (side == 'l'))
    return Part(f'ear_{side}', (s * 5, -6.5, -5), (22, s * 40, 0), cubes=[
        Cube(-0.5, -1.5, 0, 1, 3, 4.5, hh),
        Cube(-0.5, -1, 4.5, 1, 2, 1.5, hh),
    ])


def whisker(side):
    """Lange Bartel von der Oberlippe neben der Nase: schwingt erst nach außen, dann weit nach hinten und hängt durch
    (Kette; der Renderer lässt die Glieder whisker_<s>_1..3 sanft schwingen)."""
    s = -1 if side == 'r' else 1
    links = [(1, 6, (0, 0, 0)), (1, 7, (-8, s * 4, 0)), (1, 7, (-6, -s * 14, 0)), (1, 7, (-4, -s * 16, 0))]
    root = cur = None
    prev = 0
    for i, (w, l, rot) in enumerate(links):
        paint = ice(0.74 + i * 0.05, 150 + i, '+z', tip_glow=90 if i == 3 else 0, edge=0.08)
        c = Cube(-w / 2, -w / 2, 0, w, w, l, paint, ch='eyes')
        if cur is None:
            cur = root = Part(f'whisker_{side}', (s * 4, -1.5, -21), (-10, s * 32, 0), cubes=[c])
        else:
            nxt = Part(f'whisker_{side}_{i}', (0, 0, prev - 0.25), rot, cubes=[c])
            cur.add(nxt)
            cur = nxt
        prev = l
    return root


def head():
    skull = skull_paint()
    hh = head_hide(66)
    cubes = [
        Cube(-5.5, -6, -11, 11, 9.5, 13, skull, reso=hide_reso),                # Schädel
        Cube(-4.5, -7, -10, 9, 1, 11, hh),                                       # Schädeldach (gerundet)
        Cube(-5, -5, 2, 10, 9, 1.5, hh),                                         # Hinterhaupt in den Hals
        Cube(-6.5, -8.5, -13.5, 4, 2.5, 6, head_hide(67)),                       # Brauenwulst rechts (überhängend)
        Cube(2.5, -8.5, -13.5, 4, 2.5, 6, head_hide(68)),                        # Brauenwulst links
        Cube(-6.5, -9.5, -9, 2, 1.5, 3.5, head_hide(71)),                        # Brauendorn rechts
        Cube(4.5, -9.5, -9, 2, 1.5, 3.5, head_hide(72)),                         # Brauendorn links
        Cube(-7, -6.5, -12.5, 2, 3.5, 4.5, eye_paint('r'), ch='eyes'),           # Auge rechts (golden), groß
        Cube(5, -6.5, -12.5, 2, 3.5, 4.5, eye_paint('l'), ch='eyes'),            # Auge links (eisblau)
        Cube(-6.5, -3, -10.5, 1.5, 6.5, 9, hh),                                  # Wange rechts
        Cube(5, -3, -10.5, 1.5, 6.5, 9, hh),                                     # Wange links
        Cube(-4.5, -5, -17, 9, 6, 6, snout_paint(63)),                           # Oberkiefer hinten
        Cube(-4, -4.5, -22.5, 8, 5.5, 5.5, snout_paint(64)),                     # Oberkiefer vorn
        Cube(-2.5, -6, -20, 5, 1, 9, head_hide(69)),                             # Nasenrücken
        Cube(-3.5, -7, -24.5, 7, 4, 4.5, nose_paint),                            # Nasenwulst (hochgezogen)
        Cube(-3.5, -3, -24, 7, 4, 1.5, snout_paint(65)),                         # Oberlippe vorn
        Cube(-5, -1.5, -21, 1, 2.5, 7, head_hide(73)),                           # Lippenwulst rechts
        Cube(4, -1.5, -21, 1, 2.5, 7, head_hide(74)),                            # Lippenwulst links
        Cube(-3.5, 1, -23, 1, 2.5, 1, claw(0.6, 1, '+y')),                       # Fänge oben vorn
        Cube(2.5, 1, -23, 1, 2.5, 1, claw(0.6, 2, '+y')),
        Cube(-4, 1, -16.5, 1, 2, 1, claw(0.62, 8, '+y')),                        # Säbelzähne oben
        Cube(3, 1, -16.5, 1, 2, 1, claw(0.62, 9, '+y')),
    ]
    beard = Part('beard', (0, 3, -14), (42, 0, 0), cubes=[
        Cube(-2, 0, -1, 4, 3.5, 2.5, mane_paint(180)),
        Cube(-1.5, 3.5, -0.5, 3, 3.5, 2, mane_paint(181)),
        Cube(-1, 7, 0, 2, 3, 1.5, mane_paint(182, 60), reso=ice_reso),
        Cube(-0.5, 10, 0.5, 1, 2.5, 1, mane_paint(183, 90)),
    ])
    jaw = Part('jaw', (0, 1, -4), cubes=[
        Cube(-3.5, 0, -19, 7, 2.5, 15, jaw_paint()),                              # Unterkiefer vorn
        Cube(-5.5, 0, -6.5, 11, 4, 8, head_hide(65)),                            # Kieferwinkel
        Cube(-2.5, 2.5, -18.5, 5, 1, 8, jaw_paint()),                            # Kinn
        Cube(-3, -1.5, -18, 1, 1.5, 1, claw(0.6, 3, '-y')),                      # Fänge unten
        Cube(2, -1.5, -18, 1, 1.5, 1, claw(0.6, 4, '-y')),
    ], children=[beard])
    return Part('head', cubes=cubes, children=[jaw, antler('r'), antler('l'), mane('r'), mane('l'), mane_top(),
                                               ear('r'), ear('l'), whisker('r'), whisker('l')])


# ---------------------------------------------------------------------------------------------------------------------
# Läufe, Schwingen, Fächer
# ---------------------------------------------------------------------------------------------------------------------

def limb_hide(seed, axis='y'):
    """Schuppen für Läufe (Achse y, Schuppen zeigen nach unten) und Flügelknochen (Achse x, nach außen).
    Gerechnet um die Mittelachse des jeweiligen Quaders; oben hell, unten/innen perlgrau."""
    def paint(face, p, c):
        x, y, z = p
        cx, cy, cz = c.x + c.w / 2, c.y + c.h / 2, c.z + c.d / 2
        if axis == 'y':
            along, th = y, math.atan2(x - cx, -(z - cz))
            up = -0.1 if face == 'bottom' else (0.1 if face == 'top' else 0.0)
        else:
            along, th = abs(x), math.atan2(z - cz, -(y - cy))
            up = 0.12 * -math.cos(th)
        a = abs(th)
        dv, kind, age = scale_field(along, a * 2.4, 2.0, 1.9, seed)
        v = 0.74 + dv + LIGHT[face] * 0.5 - up * 0.0 + up + (fbm(x * 0.3, y * 0.3, z * 0.3, seed) - 0.5) * 0.1
        if axis == 'x' and face == 'bottom':
            return scute(face, (y, x, abs(x) * 1.0), LIGHT[face] * 0.5, seed)
        if kind == 'rim' and age > 0.7:
            return _cold(ramp(AGE, 0.6 + LIGHT[face]), v - 0.05)
        return _cold(ramp(SCALES, v), v)
    return paint


def mx(side, x, w):
    """x-Lage eines Quaders für die Seite (Maße für rechts, -x = außen; links gespiegelt)."""
    return x if side == 'r' else -(x + w)


def claws(side, y, z, seed, n=3, size=1.5):
    out = []
    for k in range(n):
        x = -2.5 + k * 1.75
        out.append(Cube(mx(side, x, size), y, z - 2, size, size, 2.5, claw(0.42, seed + k, '-z')))
        out.append(Cube(mx(side, x + 0.25, 1), y + size - 0.5, z - 3.5, 1, 1.5, 1.5, claw(0.7, seed + k + 5, '-z')))
    return out


def foreleg(side):
    """Kleiner, kräftiger Vorderlauf. Drehpunkt an der Schulter; Ruhe: Unterarm schräg nach vorn, Klauen nach
    vorn/unten. Kinder: foreleg_<s>_lower (Ellbogen), foreleg_<s>_foot (Handgelenk)."""
    s = -1 if side == 'r' else 1
    foot = Part(f'foreleg_{side}_foot', (0, 7, 0), (40, 0, 0), cubes=[
        Cube(mx(side, -3, 5.5), -0.5, -4, 5.5, 2.5, 6, limb_hide(84)),
    ] + claws(side, 0.5, -4, 85))
    lower = Part(f'foreleg_{side}_lower', (s * 1.75, 7.5, 0), (-40, 0, 0), cubes=[
        Cube(mx(side, -2, 4), -0.5, -2, 4, 8, 4, limb_hide(82)),
        Cube(mx(side, -1, 2), -1, 1.5, 2, 2, 3.5, ice(0.5, 83, '+z', edge=0.2)),        # Ellbogendorn
    ], children=[foot])
    return Part(f'foreleg_{side}', cubes=[
        Cube(mx(side, -4.5, 6), -3, -3, 6, 11, 6, limb_hide(80)),
        Cube(mx(side, -5, 3), -2, -2, 3, 6, 4, limb_hide(81)),                           # Schultermuskel
    ], children=[lower])


def hindleg(side):
    """Hinterlauf: dicker Schenkel, Schienbein schräg nach hinten, Fuß flach mit Klauen nach vorn."""
    s = -1 if side == 'r' else 1
    foot = Part(f'hindleg_{side}_foot', (0, 8, 0), (-30, 0, 0), cubes=[
        Cube(mx(side, -3, 5.5), -0.5, -4.5, 5.5, 2.5, 7, limb_hide(94)),
        Cube(mx(side, -1, 2), 0, 2, 2, 1.5, 2.5, claw(0.5, 95, '+z')),                    # Sporn hinten
    ] + claws(side, 0.5, -4.5, 96))
    lower = Part(f'hindleg_{side}_lower', (s * 2, 8, 0.5), (30, 0, 0), cubes=[
        Cube(mx(side, -1.75, 3.5), -0.5, -1.75, 3.5, 9, 3.5, limb_hide(92)),
    ], children=[foot])
    return Part(f'hindleg_{side}', cubes=[
        Cube(mx(side, -5, 7), -4, -3.5, 7, 12, 7, limb_hide(90)),
        Cube(mx(side, -3, 4), 0, -1, 4, 3, 1, ice(0.45, 91, '-y', edge=0.1)),
    ], children=[lower])


WING_L = (20, 22, 24)      # Oberarm, Unterarm, Hand
WING_D = (26, 30, 30)      # Hauttiefe je Glied


def membrane_part(name, length, depth, side, kind, seed, z0=0.0):
    """Hautplatte: im Teilraum x-y-Platte (x entlang des Knochens), um +90 Grad gekippt -> Hinterkante bei +z."""
    paint, lines = membrane(length, depth, side, kind, seed)
    x0 = -length if side == 'r' else 0
    return Part(name, (0, 0, z0), (90, 0, 0), cubes=[Cube(x0, 0, -0.5, length, depth, 1, paint, reso=lines)])


def wing(side):
    """Schwinge: Schulter bei 0, Knochen entlang -x (rechts) bzw. +x (links), Haut nach hinten (+z).
    Ruhe = gespannt (Spannweite etwa 64 Einheiten). Kinder: wing_<s>_fore (Ellbogen), wing_<s>_tip (Handgelenk)."""
    s = -1 if side == 'r' else 1
    l1, l2, l3 = WING_L
    d1, d2, d3 = WING_D
    def bone(seed):
        return limb_hide(seed, 'x')

    fingers = []
    for i, (ang, ln) in enumerate(((22, 28), (44, 25), (66, 20))):
        fingers.append(Part(f'wing_{side}_finger_{i}', (0, 0, 0.5), (0, -s * ang, 0), cubes=[
            Cube(mx(side, -ln, ln), -0.75, -0.75, ln, 1.5, 1.5, ice(0.66, 200 + i, '+x' if side == 'l' else '-x',
                                                                          edge=0.06)),
        ]))
    tip = Part(f'wing_{side}_tip', (s * l2, 0, 0), (0, -s * 14, 0), cubes=[
        Cube(mx(side, -9, 9), -1.25, -1.25, 9, 2.5, 2.5, bone(203)),
        Cube(mx(side, -l3, l3 - 9), -0.75, -0.75, l3 - 9, 1.5, 1.5, claw(0.6, 204, '-z')),
        Cube(mx(side, -1, 2), -1, -4.5, 2, 2, 3.5, claw(0.5, 205, '-z')),                 # Daumenkralle
    ], children=[membrane_part(f'wing_{side}_memb_c', l3, d3, side, 'tip', 7 if side == 'r' else 8, z0=0.5)]
        + fingers)
    fore = Part(f'wing_{side}_fore', (s * l1, 0, 0), (0, -s * 8, 0), cubes=[
        Cube(mx(side, -l2, l2), -1.5, -1.5, l2, 3, 3, bone(206)),
    ], children=[membrane_part(f'wing_{side}_memb_b', l2, d2, side, 'mid', 5 if side == 'r' else 6, z0=1.0), tip])
    return Part(f'wing_{side}', cubes=[
        Cube(mx(side, -2.5, 4.5), -2.5, -2.5, 4.5, 5, 5, bone(207)),                       # Schultergelenk
        Cube(mx(side, -l1, l1 - 1), -1.75, -1.75, l1 - 1, 3.5, 3.5, bone(208)),
    ], children=[membrane_part(f'wing_{side}_memb_a', l1, d1, side, 'inner', 3 if side == 'r' else 4, z0=1.25),
                 fore])


def blade(name, pivot, rot, length, width, seed):
    """Eisklinge des Schwanzfächers: flach (dünn in x), wächst entlang +z, verjüngt sich zur Spitze."""
    L = length
    seg = [round(L * f * 2) / 2 for f in (0.3, 0.35, 0.2)]
    seg.append(L - sum(seg))
    w = width
    cubes = [
        Cube(-1.5, -w / 4, 0, 3, round(w / 2 * 2) / 2, seg[0], ice(0.5, seed, '+z', edge=0.26), reso=ice_reso),
        Cube(-1, -w / 2, seg[0], 2, w, seg[1], ice(0.6, seed + 1, '+z', edge=0.28), reso=ice_reso),
        Cube(-0.5, -w / 2 + 1.5, seg[0] + seg[1], 1, max(1.5, w - 3), seg[2], ice(0.7, seed + 2, '+z', edge=0.28),
             reso=ice_reso),
        Cube(-0.5, -w / 2 + 2.5, L - seg[3], 1, max(1.0, w - 5), seg[3], ice(0.84, seed + 3, '+z', tip_glow=60,
                                                                               edge=0.24), reso=ice_reso),
    ]
    return Part(name, pivot, rot, cubes=cubes)


def tail_fan():
    """Fächer aus Eisklingen nach dem letzten Glied; Gelenk bei 0, zeigt nach +z. Gebaut für den Endquerschnitt
    (etwa 5 x 5 Einheiten bei taper 0,32); der Renderer setzt ihn unverjüngt an."""
    blades = []
    spec = ((46, -10, 15, 5), (22, 10, 22, 6), (0, -3, 27, 7), (-24, 9, 21, 6), (-46, -8, 14, 5))
    for i, (pitch, yaw, ln, w) in enumerate(spec):
        blades.append(blade(f'fan_blade_{i}', (0, 0, 1.5), (pitch, yaw, 0), ln, w, 330 + i * 4))
    for i, s in enumerate((-1, 1)):
        blades.append(blade(f'fan_side_{i}', (s * 1.5, 0.5, 1), (10, s * 38, s * 90), 16, 5, 360 + i * 4))
    return Part('tail_fan', cubes=[
        Cube(-2.5, -2.5, -1, 5, 5, 3.5, ice(0.45, 340, '+z', edge=0.2)),
        Cube(-1.5, -1.5, 2.5, 3, 3, 1.5, ice(0.55, 341, '+z', edge=0.2)),
    ], children=blades)


def nivaris():
    parts = [head(), neck(), segment('segment'), segment('segment_dorsal', 'dorsal'), segment('segment_heart', 'heart'),
             foreleg('l'), foreleg('r'), hindleg('l'), hindleg('r'), wing('l'), wing('r'), tail_fan()]
    return SerpentModel('nivaris', TEX_W, TEX_H, parts)


# ---------------------------------------------------------------------------------------------------------------------
# Renderer-Vorschlag: Glieder, Verjüngung, Ansatzstellen (gleiche Werte in docs/NIVARIS_SERPENT.md)
# ---------------------------------------------------------------------------------------------------------------------

WING_GLIED, HEART_GLIED, FORE_GLIED, HIND_GLIED = 2, 3, 3, 17
ATTACH = {
    'wing_l': (WING_GLIED, (6, -6, 4)), 'wing_r': (WING_GLIED, (-6, -6, 4)),
    'foreleg_l': (FORE_GLIED, (7, 3, 8)), 'foreleg_r': (FORE_GLIED, (-7, 3, 8)),
    'hindleg_l': (HIND_GLIED, (6, 3, 8)), 'hindleg_r': (HIND_GLIED, (-6, 3, 8)),
}


def taper(i):
    """Querschnittsfaktor (x und y) für Glied i (0 = Hals). Länge (z) bleibt 1, damit der Bogenabstand stimmt."""
    if i <= 0:
        return 1.0
    if i < 5:
        return (0.96, 1.04, 1.1, 1.12)[i - 1]
    t = (i - 5) / (SEGMENTS - 1 - 5)
    return round(1.12 - 0.8 * t ** 1.25, 3)


def glied_part(i):
    """Welches Teil der Renderer auf Glied i setzt (Vorschlag)."""
    if i == 0:
        return 'neck'
    if i == HEART_GLIED:
        return 'segment_heart'
    if i % 2 == 0 and i < SEGMENTS - 2:
        return 'segment_dorsal'
    return 'segment'


# ---------------------------------------------------------------------------------------------------------------------
# Posen für preview.py (Teile hängen alle bei 0 unter root, die Posen legen sie für die Vorschau auseinander)
# ---------------------------------------------------------------------------------------------------------------------

def R(*deg):
    if len(deg) <= 3:
        return tuple(math.radians(a) for a in deg)
    return tuple(math.radians(a) for a in deg[:3]) + tuple(deg[3:])


TOP = ['head', 'neck', 'segment', 'segment_dorsal', 'segment_heart', 'foreleg_l', 'foreleg_r', 'hindleg_l',
       'hindleg_r', 'wing_l', 'wing_r', 'tail_fan']
# Empfohlene Flügelhaltungen (Renderer): Winkel für wing_r, wing_l gespiegelt (yaw und roll mit umgekehrtem Vorzeichen).
WING_POSES = {
    'spread': {'wing': (0, 0, 0)},
    # Angelegt wie ein zerschlissener Umhang: Knochen nach hinten über die Schulter, Haut außen an der Flanke.
    # pitch 180 dreht die Schwinge um den eigenen Knochen, sonst zeigt die Haut nach innen in den Rumpf.
    'fold': {'wing': (180, 84, -45)},
    'fold_tight': {'wing': (180, 80, -70)},
    'up': {'wing': (0, 6, 40)},
    'down': {'wing': (0, 4, -34)},
}


def wing_pose(name, dx=0.0, dy=0.0, dz=0.0, side_dx=0.0):
    out = {}
    for side, s in (('r', 1), ('l', -1)):
        spec = WING_POSES[name]
        for bone, (p, y, r) in spec.items():
            key = f'wing_{side}' if bone == 'wing' else f'wing_{side}_{bone}'
            if bone == 'wing':
                out[key] = R(p, s * y, s * r, dx - s * side_dx, dy, dz)
            else:
                # Kinder: Grunddrehung (Schwung nach hinten) bleibt, die Faltung kommt dazu.
                base = {'fore': 8, 'tip': 14}[bone]
                out[key] = R(p, s * (y + base), s * r)
    return out


def only(*names, **offsets):
    pose = {n: None for n in TOP}
    for n in names:
        pose[n] = R(0, 0, 0, *offsets.get(n, (0, 0, 0)))
    return pose


def _forebody(wings='fold', jaw=0.0):
    """Kopf, Hals und Glied 1-4 gerade hintereinander, Schwingen und Vorderläufe angesetzt."""
    pose = only('head', 'neck')
    pose['segment'] = R(0, 0, 0, 0, 0, 16)
    pose['segment_dorsal'] = R(0, 0, 0, 0, 0, 32)
    pose['segment_heart'] = R(0, 0, 0, 0, 0, 48)
    pose['foreleg_l'] = R(0, 0, 0, 7, 3, 56)
    pose['foreleg_r'] = R(0, 0, 0, -7, 3, 56)
    pose.update({k: v for k, v in wing_pose(wings, 0, -6, 36, side_dx=6).items()})
    pose['jaw'] = R(jaw, 0, 0)
    return pose


POSES = {
    'kopf': only('head'),
    'kopf_offen': dict(only('head'), jaw=R(34, 0, 0)),
    'glied': only('segment_dorsal'),
    'glieder': only('neck', 'segment', 'segment_dorsal', 'segment_heart',
                    neck=(0, 0, -40), segment=(0, 0, -20), segment_dorsal=(0, 0, 0), segment_heart=(0, 0, 20)),
    'vorderteil': _forebody('fold'),
    'vorderteil_gespannt': _forebody('spread', jaw=20),
    'laeufe': only('foreleg_l', 'foreleg_r', 'hindleg_l', 'hindleg_r',
                   foreleg_l=(8, 0, -10), foreleg_r=(-8, 0, -10), hindleg_l=(8, 0, 10), hindleg_r=(-8, 0, 10)),
    'faecher': only('tail_fan'),
}
HEIGHT_EXCLUDE = ()

# Anker der Trefferzonen im Raum ihres Teils (Einheiten, vor SCALE). Siehe docs/NIVARIS_ANCHORS.md.
ANCHORS = {
    'head': ('head', (0, -2.5, -9)),          # Kopfmitte zwischen den Augen
    'throat': ('head', (0, 4.5, -1)),         # Kehle: Unterseite hinter dem Kiefergelenk (leuchtende Naht)
    'heart': ('segment_heart', (0, 7.5, 8)),  # Mitte des Herzkerns auf der Bauchseite von Glied 3
    'tail_tip': ('tail_fan', (0, -1, 13)),    # Mitte des Klingenfächers
}


if __name__ == '__main__':
    if '--taper' in sys.argv:
        for i in range(SEGMENTS):
            print(f'{i:2d} {glied_part(i):15s} {taper(i):.3f}')
