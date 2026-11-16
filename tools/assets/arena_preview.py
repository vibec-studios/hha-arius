"""Heiligtum von Eirholm: Geometrie-Spiegel von `arena/ArenaPlan.kt` mit Vorschau-PNGs und Prüfungen (ohne Spiel).

Aufruf (aus dem Projektordner):
    python tools/assets/arena_preview.py [ausgabeordner]      # Standard: tmp/preview/arena

Die Funktionen hier sind **Zeile für Zeile** dieselben wie in `src/main/kotlin/dev/henny/hha_arius/arena/ArenaPlan.kt`
(gleiche Formeln, gleiche Hash-/Rauschfunktion, gleiche Block-Ids). Wer die Geometrie ändert, ändert beide Dateien.
Alle Koordinaten relativ zum Instanz-Ursprung (y = 0 ist die Lauffläche, Bodenblöcke bei y = −1, +z = Süden).

Ausgabe:
    top.png            Draufsicht (oberster Block, Höhe schattiert)
    plan_y5.png        Grundriss des Tempels in Brusthöhe (y = 5)
    plan_y-1.png       Bodenmuster (y = −1)
    plan_y-36.png      Gewölbe auf Laufhöhe (y = −36, Säulen)
    plan_y-37.png      Gewölbeboden mit Runen (y = −37)
    cut_x0.png         Schnitt Nord–Süd bei x = 0
    cut_x1.png         Schnitt Nord–Süd bei x = 1 (Schnittebene, Kluft)
    cut_z-50.png       Schnitt West–Ost durch die Tempelmitte
    cut_z0.png         Schnitt West–Ost durch Plateau und Gewölbe
    after_cut.png      Draufsicht nach dem Schnitt (Osthälfte weg, Trümmer, Kluft) und Einsturz (alle Ringe)
Dazu Blockzahlen und Prüfergebnisse auf der Konsole.
"""
import math
import os
import struct
import sys
import zlib

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

# --- Vertrag (ArenaLayout.kt) ------------------------------------------------------------------------------------
TEMPLE_Z = -50
TEMPLE_R = 18
TEMPLE_WALL_H = 12
TEMPLE_TOP = 25
GATE_Z = -32
GATE_HALF_WIDTH = 3
GATE_HEIGHT = 9
PILLAR_HALF = 1
PILLAR_H = 11
CUT_X = 1.5
PLATEAU_R = 40
RIM_R = 48
RIM_H = 12
SOUTH_WALL_H = 26
ICE_BOTTOM = -16
COLLAPSE_RINGS = (6.0, 10.0, 14.0)
VAULT_Y = -36
VAULT_R = 28
VAULT_CEIL = -16
BOUND_XZ = 52
BOUND_Z_MIN = -80
BOUND_Z_MAX = 52
BOUND_Y_MIN = -40
BOUND_Y_MAX = 30
WATCH_UPPER = (-21.5, 13.0, -50.5)
WATCH_VAULT = (-29.5, -30.0, 0.5)
SEAT = (0.5, 2.0, -64.5)
CUT_STAND = (1.5, 2.0, -63.5)
WATCH_SEAT = (-6.5, 2.0, -64.5)
ARRIVAL = (0.5, 0.0, -36.5)
LANDING = [(0.5, -22.5), (-5.5, -20.5), (6.5, -20.5), (-2.5, -17.5), (3.5, -17.5), (0.5, -14.5)]
VAULT_LANDING = [(0.5, 9.5), (-6.5, 8.5), (7.5, 8.5), (-3.5, 13.5), (4.5, 13.5), (0.5, 16.5)]
VAULT_REST = (0.5, -36.0, -22.5)


def _kround(v):
    """Math.round (Java): floor(v + 0.5)."""
    return math.floor(v + 0.5)


TEMPLE_PILLARS = []
for _i in range(10):
    _a = (_i + 0.5) / 10.0 * 2.0 * math.pi
    TEMPLE_PILLARS.append((_kround(math.sin(_a) * 12.0), TEMPLE_Z + _kround(math.cos(_a) * 12.0)))
VAULT_PILLARS = []
for _i in range(8):
    _a = (_i + 0.5) / 8.0 * 2.0 * math.pi
    VAULT_PILLARS.append((_kround(math.sin(_a) * 17.0), _kround(math.cos(_a) * 17.0)))

# --- Block-Ids (gleiche Reihenfolge wie ArenaPlan.kt) ---------------------------------------------------------------
NAMES = ['air', 'packed_ice', 'blue_ice', 'snow_block', 'snow1', 'snow2', 'deepslate_tiles', 'polished_deepslate',
         'chiseled_deepslate', 'deepslate_bricks', 'cobbled_deepslate', 'deepslate', 'tuff', 'calcite', 'quartz_bricks',
         'quartz_pillar', 'chiseled_quartz_block', 'gold_block', 'sea_lantern', 'light_blue_stained_glass',
         'smooth_quartz']
(AIR, PACKED, BLUE, SNOWB, SNOW1, SNOW2, DTILES, PDEEP, CDEEP, DBRICK, CDS, DEEP, TUFF, CALCITE, QBRICK, QPILLAR,
 CQUARTZ, GOLD, LAMP, GLASS, SQUARTZ) = range(len(NAMES))
LIGHT = {LAMP}

Y0 = BOUND_Y_MIN
NY = BOUND_Y_MAX - BOUND_Y_MIN + 1

# --- Hash und Rauschen (identisch zu ArenaPlan.kt) -----------------------------------------------------------------
M32 = 0xFFFFFFFF


def h32(x, y, z, s):
    h = ((x * 73856093) ^ (y * 19349663) ^ (z * 83492791) ^ (s * 0x9E3779B1)) & M32
    h ^= h >> 13
    h = (h * 0x5BD1E995) & M32
    h ^= h >> 15
    return h / 4294967296.0


def vnoise(x, z, cell, s):
    fx = x / cell
    fz = z / cell
    ix = math.floor(fx)
    iz = math.floor(fz)
    tx = fx - ix
    tz = fz - iz
    tx = tx * tx * (3.0 - 2.0 * tx)
    tz = tz * tz * (3.0 - 2.0 * tz)
    a = h32(ix, 0, iz, s)
    b = h32(ix + 1, 0, iz, s)
    c = h32(ix, 0, iz + 1, s)
    d = h32(ix + 1, 0, iz + 1, s)
    top = a + (b - a) * tx
    bot = c + (d - c) * tx
    return top + (bot - top) * tz


def clamp01(v):
    return 0.0 if v < 0.0 else (1.0 if v > 1.0 else v)


def smooth(v):
    v = clamp01(v)
    return v * v * (3.0 - 2.0 * v)


def wrap_deg(a):
    while a > 180.0:
        a -= 360.0
    while a < -180.0:
        a += 360.0
    return a


def arc(a_deg, center_deg, radius):
    """Bogenabstand in Blöcken zwischen zwei Winkeln auf einem Kreis mit Radius radius."""
    return abs(wrap_deg(a_deg - center_deg)) * (math.pi / 180.0) * radius


def near_step(a_deg, step, offset):
    """Winkelabstand (Grad) zum nächsten Vielfachen offset + k * step."""
    q = (a_deg - offset) / step
    return abs(q - math.floor(q + 0.5)) * step


# --- Formen ----------------------------------------------------------------------------------------------------------
def ellipse(x, z):
    """Tempelvorplatz: Ellipse um die Tempelmitte (Halbachsen 34 in x, 22 in z)."""
    ex = x / 34.0
    ez = (z - TEMPLE_Z) / 22.0
    return math.sqrt(ex * ex + ez * ez)


def is_ground(x, z):
    return math.sqrt(x * x + z * z) < PLATEAU_R + 0.5 or ellipse(x, z) <= 1.0


def outside_dist(x, z):
    """Ungefährer Abstand außerhalb der begehbaren Fläche (Plateau ∪ Vorplatz)."""
    sp = math.sqrt(x * x + z * z) - (PLATEAU_R + 0.5)
    e = ellipse(x, z)
    gx = x / (34.0 * 34.0)
    gz = (z - TEMPLE_Z) / (22.0 * 22.0)
    g = math.sqrt(gx * gx + gz * gz) / max(e, 1e-6)
    se = (e - 1.0) / max(g, 1e-6)
    return min(sp, se), sp, se


def vault_top(r):
    """Höchste Luftschicht des Gewölbes über Radius r (Kuppel: Mitte y −17, Wand y ≈ −26)."""
    return -17.0 - 9.0 * (r / (VAULT_R + 0.5)) * (r / (VAULT_R + 0.5))


def in_vault_air(x, y, z):
    r = math.sqrt(x * x + z * z)
    if r < VAULT_R + 0.5 and VAULT_Y <= y <= vault_top(r):
        return True
    # Nische (Zuschauer) in der Westwand
    if -31 <= x <= -29 and -2 <= z <= 2 and -30 <= y <= -27:
        return not (x == -29 and y == -30)
    return False


def pillar_at(x, z, pillars):
    for i, (px, pz) in enumerate(pillars):
        if abs(x - px) <= PILLAR_HALF and abs(z - pz) <= PILLAR_HALF:
            return i, x - px, z - pz
    return None


def wall_height(x, z):
    """Oberkante (erste Luftschicht) der Gletscherwand/Klippe in einer Spalte außerhalb der Lauffläche, oder None."""
    s, sp, se = outside_dist(x, z)
    if z < -78 or s <= 0.0:
        return None
    thick = 8.0 + 1.5 * vnoise(x, z, 5, 21)
    if s > thick:
        return None
    a = math.degrees(math.atan2(x, z))  # 0 = Süden
    if se < sp:
        # Klippe am Tempelvorplatz: hinter dem Tempel hoch, zu den Seiten auf Randhöhe
        t = smooth((TEMPLE_Z - z) / 14.0)
        base = RIM_H + (28.0 - RIM_H) * t
    else:
        aa = abs(a)
        if aa <= 42.0:
            base = float(SOUTH_WALL_H)
        elif aa <= 50.0:
            base = SOUTH_WALL_H + (RIM_H - SOUTH_WALL_H) * smooth((aa - 42.0) / 8.0)
        else:
            base = float(RIM_H)
    jag = (vnoise(x, z, 4, 22) - 0.5) * 5.0 + (vnoise(x, z, 11, 23) - 0.5) * 4.0
    h = base + jag
    h = h * (0.25 + 0.75 * smooth(s / 2.5))
    out = thick - s
    if out < 2.0:
        h = h - (2.0 - out) * 2.5
    top = int(math.floor(h))
    if top < 2:
        top = 2
    if top > BOUND_Y_MAX - 1:
        top = BOUND_Y_MAX - 1
    return top


def ice_body(x, y, z):
    band = (y + int(math.floor(vnoise(x, z, 9, 31) * 5.0))) % 6
    if band == 0 and vnoise(x, z, 7, 32) > 0.45:
        return BLUE
    return PACKED


def glacier_block(x, y, z, top, cliff):
    if y == top - 1:
        return SNOWB
    if cliff and y < 4 + int(math.floor(vnoise(x, z, 6, 41) * 6.0)):
        k = h32(x, y, z, 42)
        return DEEP if k < 0.45 else (CDS if k < 0.8 else TUFF)
    band = (y + int(math.floor(vnoise(x, z, 8, 43) * 4.0))) % 7
    if band <= 1:
        return BLUE
    if band == 4 and h32(x, y, z, 44) < 0.25:
        return SNOWB
    return PACKED


def temple_floor(x, z, dt, a):
    if dt < 0.5:
        return GOLD
    if dt < 2.5:
        return CQUARTZ
    if 5.5 <= dt < 6.5:
        return CALCITE
    if 9.8 <= dt < 10.8:
        return PDEEP
    for k in (1, 2, 3, 4, 6, 7, 8, 9):
        ra = k * 36.0 * math.pi / 180.0
        if x == _kround(math.sin(ra) * 15.5) and z == TEMPLE_Z + _kround(math.cos(ra) * 15.5):
            return LAMP
    if z > -62:
        if abs(x) == 3:
            return CALCITE
        if abs(x) <= 2:
            return PDEEP
    if 6.5 <= dt < 9.8 and near_step(a, 36.0, 18.0) * (math.pi / 180.0) * dt < 0.6:
        return CDEEP
    return DTILES


def plateau_surface(x, z, r):
    vein = abs(vnoise(x, z, 11, 4) - 0.5)
    if vein < 0.03:
        return PACKED
    patch = vnoise(x, z, 9, 5)
    if r > 30.0 and patch > 0.6:
        return PACKED
    if r <= 30.0 and patch > 0.8:
        return PACKED
    return SNOWB


def gate_path(x, z):
    if abs(x) <= 3 and -29 <= z <= -14:
        if z <= -23:
            return True
        return h32(x, 0, z, 7) < (-14 - z) / 9.0
    return False


def column(x, z):
    """Alle Blöcke einer Spalte, Liste der Länge NY (Index y − BOUND_Y_MIN). 0 = Luft (nicht gesetzt)."""
    col = [AIR] * NY

    def put(y, b):
        col[y - Y0] = b

    r = math.sqrt(x * x + z * z)
    dz = z - TEMPLE_Z
    dt = math.sqrt(x * x + dz * dz)
    at = math.degrees(math.atan2(x, dz))  # Winkel um die Tempelmitte, 0 = Süden
    ground = is_ground(x, z)

    # 1) Boden / Eisplatte bzw. Gletscherwand
    if ground:
        for y in range(ICE_BOTTOM, -1):
            put(y, ice_body(x, y, z))
        if dt < TEMPLE_R + 0.5:
            surf = temple_floor(x, z, dt, at)
        elif dt < 20.5:
            surf = PDEEP
        elif dt < 21.5:
            surf = DTILES
        elif dt < 22.5:
            surf = PDEEP
        elif gate_path(x, z):
            surf = DTILES
        else:
            surf = plateau_surface(x, z, r)
        put(-1, surf)
        if dt >= 25.0 and not gate_path(x, z) and (surf == SNOWB or surf == PACKED):
            n = vnoise(x, z, 5, 6)
            if n > 0.74:
                put(0, SNOW2)
            elif n > 0.6:
                put(0, SNOW1)
    else:
        top = wall_height(x, z)
        if top is not None:
            s, sp, se = outside_dist(x, z)
            cliff = se < sp and z < -56
            for y in range(ICE_BOTTOM, top):
                put(y, glacier_block(x, y, z, top, cliff))

    # 2) Herzgewölbe (unter der Eisplatte)
    if r < 32.5:
        for y in range(Y0, ICE_BOTTOM):
            if in_vault_air(x, y, z):
                continue
            put(y, vault_block(x, y, z, r))
        # Säulen
        p = pillar_at(x, z, VAULT_PILLARS)
        if p is not None:
            i, ox, oz = p
            ytop = int(math.floor(vault_top(r)))
            for y in range(VAULT_Y, ytop + 1):
                if y <= VAULT_Y + 1:
                    b = CDEEP if y == VAULT_Y else PDEEP
                elif y == ytop:
                    b = LAMP if (ox == 0) != (oz == 0) else BLUE
                elif ox != 0 and oz != 0:
                    b = PACKED
                else:
                    b = BLUE
                put(y, b)
        # Schnee auf dem Boden um die Mitte (der Druckstoß der Verwandlung räumt ihn)
        if r <= 12.5 and p is None:
            n = vnoise(x, z, 4, 61)
            if n > 0.4:
                put(VAULT_Y, SNOW2 if n > 0.7 else SNOW1)

    # 3) Tempel
    if dt < 24.5 or (-24 <= x <= -21 and -54 <= z <= -48):
        temple(x, z, dt, at, put, col)
    return col


def vault_block(x, y, z, r):
    a = math.degrees(math.atan2(x, z))
    if y == VAULT_Y - 1 and r < VAULT_R + 0.5:
        return vault_floor(x, z, r, a)
    if y < VAULT_Y - 1:
        return DEEP if h32(x, y, z, 51) < 0.6 else CDS
    # Wand und Kuppelschale
    if r >= VAULT_R + 0.5:
        if y == -32 and r < VAULT_R + 1.5 and near_step(a, 22.5, 11.25) * (math.pi / 180.0) * r < 0.55 \
                and not (abs(z) <= 4 and x < 0):
            return LAMP
        if y <= VAULT_Y + 1:
            return PDEEP
        band = (y + int(math.floor(vnoise(x, z, 6, 52) * 3.0))) % 5
        return PACKED if band == 0 else BLUE
    # über der Kuppel (r < 28.5)
    shell = y - vault_top(r)  # > 0
    if shell < 1.5:
        if r < 26.0 and abs(r - 22.0) < 0.75 and near_step(a, 30.0, 0.0) * (math.pi / 180.0) * r < 0.55:
            return LAMP
        if near_step(a, 45.0, 22.5) * (math.pi / 180.0) * max(r, 1.0) < 0.7 and r > 4.0:
            return BLUE
    if x == -31 and z == 0 and y == -26:
        return LAMP
    return ice_body(x, y, z) if shell < 3.0 else PACKED


GLYPHS = [
    0b010111010, 0b101010101, 0b111101111, 0b100111001, 0b011010110, 0b110010011,
    0b010101010, 0b111010111, 0b001111100, 0b101111101, 0b010110011, 0b110101011,
]


def vault_floor(x, z, r, a):
    if x == 0 and z == 0:
        return GOLD
    if r < 2.5:
        return PDEEP
    if 2.5 <= r < 3.5:
        return CQUARTZ
    if 7.0 <= r < 8.0:
        return CALCITE
    if 12.0 <= r < 13.0:
        return CALCITE
    if 8.0 <= r < 12.0:
        # zwölf Runen (3 × 3 Glyphen im Polarraster) auf dem Ring zwischen den Kreisen
        k = int(math.floor((a + 180.0) / 30.0)) % 12
        center = -180.0 + (k + 0.5) * 30.0
        u = int(math.floor(r - 10.0 + 0.5))          # −1..1 radial
        v = int(math.floor(wrap_deg(a - center) * (math.pi / 180.0) * r / 1.1 + 0.5))  # −1..1 tangential
        if -1 <= u <= 1 and -1 <= v <= 1:
            bit = (u + 1) * 3 + (v + 1)
            if (GLYPHS[k] >> (8 - bit)) & 1:
                return CQUARTZ
        return DTILES
    if 13.0 <= r < 18.5 and near_step(a, 45.0, 22.5) * (math.pi / 180.0) * r < 0.55:
        return CDEEP
    if r >= VAULT_R - 1.5:
        return PDEEP
    return DTILES


def temple(x, z, dt, at, put, col):
    dz = z - TEMPLE_Z
    inside = dt < TEMPLE_R + 0.5
    # Altarstufen
    if inside and z <= -62:
        if z >= -63:
            front = z == -62
            lamp = front and x in (-10, -6, 6, 10)
            put(0, LAMP if lamp else (CDEEP if front else PDEEP))
        else:
            put(0, PDEEP)
            if z == -64:
                put(1, GOLD if x in (-8, 8) else CQUARTZ)
            elif abs(x) <= 2 and z >= -66:
                put(1, CALCITE)
            else:
                put(1, DTILES)
        # Thron
        if -1 <= x <= 1 and z == -66:
            for y in range(2, 5):
                put(y, QPILLAR)
            put(5, GOLD if x == 0 else CQUARTZ)
        if x in (-1, 1) and z == -65:
            put(2, PDEEP)
    # Säulen
    if inside:
        p = pillar_at(x, z, TEMPLE_PILLARS)
        if p is not None:
            i, ox, oz = p
            for y in range(0, PILLAR_H):
                if y == 0:
                    b = PDEEP
                elif y == PILLAR_H - 1:
                    b = CQUARTZ
                elif ox != 0 and oz != 0:
                    b = BLUE
                else:
                    b = PACKED
                put(y, b)
    # Mauer
    if TEMPLE_R + 0.5 <= dt < 20.5:
        inner = dt < 19.5
        for y in range(0, TEMPLE_WALL_H):
            if y == 0 or y == TEMPLE_WALL_H - 1:
                b = PDEEP
            elif y == TEMPLE_WALL_H - 2:
                b = CDEEP
            elif inner:
                b = QBRICK
                if y == 7 and near_step(at, 36.0, 18.0) * (math.pi / 180.0) * dt < 0.5:
                    b = LAMP
            else:
                b = DBRICK
                if near_step(at, 36.0, 18.0) * (math.pi / 180.0) * dt < 0.8:
                    b = QPILLAR
            # Schlitzfenster zwischen den Säulen (nicht über Tor und Altar)
            if 3 <= y <= 8 and near_step(at, 36.0, 0.0) * (math.pi / 180.0) * dt < 0.6:
                k = int(math.floor(at / 36.0 + 0.5))
                if k % 10 not in (0, 5, -5):
                    b = GLASS
            put(y, b)
        # Relief hinter dem Thron
        if dz < 0 and abs(x) <= 3 and inner:
            for y in range(2, 11):
                if abs(x) <= 2 and y <= 9:
                    put(y, BLUE)
                elif abs(x) == 3 or y == 10:
                    put(y, GOLD if (x == 0 and y == 10) else CQUARTZ)
        # Zinnen
        if dt >= 19.5 and not (abs(x) <= 5 and dz > 0):
            if int(math.floor((at + 180.0) * (math.pi / 180.0) * 20.0 / 2.0)) % 2 == 0:
                put(TEMPLE_WALL_H, PDEEP)
    # Decke des Umgangs mit Lichtschächten, Westöffnung zur Galerie
    if 10.5 <= dt < TEMPLE_R + 0.5:
        shaft = 14.5 <= dt < 17.5 and near_step(at, 36.0, 0.0) * (math.pi / 180.0) * dt < 1.6
        west = x < 0 and abs(z + 51) <= 3 and dt >= 13.5
        if not shaft and not west:
            put(TEMPLE_WALL_H - 1, DTILES)
            if 12.0 <= dt < 18.5 and vnoise(x, z, 4, 71) > 0.62:
                put(TEMPLE_WALL_H, SNOW1)
    # Tambour
    if 10.5 <= dt < 11.5:
        for y in range(TEMPLE_WALL_H, 15):
            b = QBRICK
            if y == 13 and near_step(at, 36.0, 18.0) * (math.pi / 180.0) * dt < 0.6:
                b = GLASS
            put(y, b)
    # Kuppel
    if 1.5 <= dt < 11.5:
        yo = 14.0 + 11.0 * math.sqrt(max(0.0, 1.0 - (dt / 11.5) * (dt / 11.5)))
        yi = 14.0 + 10.0 * math.sqrt(max(0.0, 1.0 - (dt / 10.5) * (dt / 10.5))) if dt < 10.5 else 14.0
        lo = max(15, _kround(yi))
        hi = min(TEMPLE_TOP, _kround(yo))
        rib = near_step(at, 36.0, 18.0) * (math.pi / 180.0) * dt < 0.8
        for y in range(lo, hi + 1):
            if dt < 2.5:
                b = GOLD
            elif rib:
                b = PDEEP
            else:
                b = CALCITE if y < 21 else SQUARTZ
            put(y, b)
    # Südtor mit Portal
    if -31 <= z <= -28 and abs(x) <= 5:
        ax = abs(x)
        if ax >= 4:
            for y in range(0, 11):
                put(y, QPILLAR if ax == 4 else PDEEP)
            if ax == 5 and z == -28:
                put(7, LAMP)
        for y in (9, 10):
            if ax <= 3:
                put(y, GOLD if (x == 0 and y == 9 and z == -28) else CDEEP)
        put(11, PDEEP)
        if ax <= 3:
            put(12, CQUARTZ)
        if ax <= 1:
            put(13, PDEEP)
    if abs(x) <= GATE_HALF_WIDTH and -33 <= z <= -28:
        for y in range(0, GATE_HEIGHT):
            put(y, AIR)
    # Galerie (Zuschauer) an der Westseite
    if -24 <= x <= -21 and -54 <= z <= -48:
        put(TEMPLE_WALL_H, PDEEP)
        if x == -24 or z in (-54, -48):
            put(TEMPLE_WALL_H + 1, CDEEP)
        if x >= -22:
            put(TEMPLE_WALL_H - 1, DBRICK)
        if x == -23 and z in (-53, -49):
            for y in range(0, TEMPLE_WALL_H):
                put(y, DBRICK)
    # Westöffnung: Fenster in den Tambour vor der Galerie
    if x < 0 and abs(z + 51) <= 2 and 10.5 <= dt < 11.5:
        put(13, AIR)


# --- Ereignisse (Spiegel der API in ArenaBuilder.kt) ----------------------------------------------------------------
def collapse_ring_cols(ring):
    lo = 0.0 if ring == 0 else COLLAPSE_RINGS[ring - 1]
    hi = COLLAPSE_RINGS[ring]
    out = []
    for x in range(-15, 16):
        for z in range(-15, 16):
            r = math.sqrt(x * x + z * z)
            if lo <= r < hi:
                out.append((x, z, int(math.floor(vault_top(r)))))
    return out


def cleft_contains(x, y, z):
    if y < 0 or z <= 0:
        return False
    r = math.sqrt(x * x + z * z)
    if r < PLATEAU_R + 0.5:
        return False
    a = abs(math.degrees(math.atan2(x, z)))
    if a > 50.0:
        return False
    deep = RIM_R + 2 if y >= SOUTH_WALL_H - 8 else RIM_R - 2
    if r > deep:
        return False
    hw = 1.5 + 2.0 * y / SOUTH_WALL_H
    return abs(x + 0.5 - CUT_X) <= hw


def rubble_height(x, z):
    """Trümmerhöhe einer Spalte nach dem Sturz (Blöcke ab y = 0, nur in Luft gesetzt)."""
    if x < 5:
        return 0
    dz = z - TEMPLE_Z
    dt = math.sqrt(x * x + dz * dz)
    if gate_path(x, z) or (abs(x) <= GATE_HALF_WIDTH + 1 and z > -34):
        return 0
    ex = (x - 27.0) / 9.0
    ez = (z - TEMPLE_Z) / 13.0
    mound = 6.5 * (1.0 - ex * ex - ez * ez) + (vnoise(x, z, 3, 81) - 0.5) * 3.0
    h = int(math.floor(mound)) if mound > 0.0 else 0
    if dt < TEMPLE_R + 2.5:
        k = h32(x, 0, z, 82)
        if k > 0.93:
            h = max(h, 2)
        elif k > 0.72:
            h = max(h, 1)
    return min(h, 8)


RUBBLE = [QBRICK, DTILES, PACKED, CALCITE, DBRICK, BLUE, PDEEP, SNOWB, PACKED, QBRICK]


def rubble_block(x, y, z):
    # in Brocken (2 × 2 × 2), damit der Haufen aus erkennbaren Mauer-, Dach- und Säulenstücken besteht
    if h32(x, y, z, 84) < 0.18:
        return RUBBLE[int(h32(x, y, z, 83) * len(RUBBLE))]
    return RUBBLE[int(h32(x >> 1, y >> 1, z >> 1, 83) * len(RUBBLE))]


# --- Bild -----------------------------------------------------------------------------------------------------------
COLORS = {
    AIR: (18, 22, 30), PACKED: (141, 180, 250), BLUE: (96, 150, 245), SNOWB: (246, 252, 253), SNOW1: (226, 236, 242),
    SNOW2: (214, 228, 238), DTILES: (52, 52, 56), PDEEP: (74, 74, 78), CDEEP: (60, 60, 66), DBRICK: (84, 84, 88),
    CDS: (96, 96, 100), DEEP: (88, 88, 92), TUFF: (112, 113, 104), CALCITE: (214, 216, 212), QBRICK: (232, 226, 216),
    QPILLAR: (238, 233, 226), CQUARTZ: (206, 198, 186), GOLD: (232, 190, 52), LAMP: (120, 255, 230),
    GLASS: (90, 150, 220), SQUARTZ: (240, 236, 230),
}


def write_png(path, w, h, pix, scale):
    raw = bytearray()
    for yy in range(h * scale):
        raw.append(0)
        row = pix[yy // scale]
        for xx in range(w * scale):
            raw.extend(row[xx // scale])
    data = zlib.compress(bytes(raw), 9)

    def chunk(t, d):
        c = struct.pack('>I', len(d)) + t + d
        return c + struct.pack('>I', zlib.crc32(t + d) & 0xFFFFFFFF)
    png = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', w * scale, h * scale, 8, 2, 0, 0, 0)) \
        + chunk(b'IDAT', data) + chunk(b'IEND', b'')
    with open(path, 'wb') as f:
        f.write(png)


def shade(c, f):
    return tuple(max(0, min(255, int(v * f))) for v in c)


def mark(pix, px, py, color):
    if 0 <= py < len(pix) and 0 <= px < len(pix[0]):
        pix[py][px] = color


def main():
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    out_dir = args[0] if args else os.path.join('tmp', 'preview', 'arena')
    os.makedirs(out_dir, exist_ok=True)
    xs = list(range(-BOUND_XZ, BOUND_XZ + 1))
    zs = list(range(BOUND_Z_MIN, BOUND_Z_MAX + 1))
    print('berechne Plan …')
    plan = {}
    for x in xs:
        for z in zs:
            plan[(x, z)] = column(x, z)

    def get(x, y, z):
        c = plan.get((x, z))
        if c is None or not (Y0 <= y <= BOUND_Y_MAX):
            return AIR
        return c[y - Y0]

    if '--dump' in sys.argv:
        with open(os.path.join(out_dir, 'plan_py.bin'), 'wb') as f:
            for x in xs:
                for z in zs:
                    f.write(bytes(plan[(x, z)]))
    # --- Zählen und Prüfen ---
    total = 0
    counts = [0] * len(NAMES)
    east = 0
    for (x, z), col in plan.items():
        dt = math.sqrt(x * x + (z - TEMPLE_Z) ** 2)
        for i, b in enumerate(col):
            if b:
                total += 1
                counts[b] += 1
                if x >= 2 and i + Y0 >= 0 and dt <= 23.0 and is_ground(x, z):
                    east += 1
    print(f'Blöcke gesamt: {total}   Osthälfte Tempel (x ≥ 2, y ≥ 0): {east}')
    print('  ' + ', '.join(f'{NAMES[i]} {c}' for i, c in enumerate(counts) if c))

    problems = []
    lamps = [(x, y + Y0, z) for (x, z), col in plan.items() for y, b in enumerate(col) if b in LIGHT]
    snows = [(x, y + Y0, z) for (x, z), col in plan.items() for y, b in enumerate(col) if b in (SNOW1, SNOW2)]
    print(f'Leuchtblöcke: {len(lamps)}   Schneeschichten: {len(snows)}')
    lamp_set = {}
    for lx, ly, lz in lamps:
        lamp_set.setdefault((lx // 8, lz // 8), []).append((lx, ly, lz))
    for sx, sy, sz in snows:
        below = get(sx, sy - 1, sz)
        if below in (AIR, SNOW1, SNOW2, GLASS, LAMP):
            problems.append(f'Schnee ohne Halt bei {sx},{sy},{sz}')
        best = 99
        for cx in range(sx // 8 - 1, sx // 8 + 2):
            for cz in range(sz // 8 - 1, sz // 8 + 2):
                for lx, ly, lz in lamp_set.get((cx, cz), []):
                    best = min(best, abs(lx - sx) + abs(ly - sy) + abs(lz - sz))
        if best < 5:
            problems.append(f'Schnee im Licht (Abstand {best}) bei {sx},{sy},{sz}')

    # Ebenheit: Lauffläche Plateau (außerhalb Tempel), Vorplatz, Gewölbe
    rough = 0
    for (x, z), col in plan.items():
        r = math.sqrt(x * x + z * z)
        dt = math.sqrt(x * x + (z - TEMPLE_Z) ** 2)
        if is_ground(x, z) and dt >= 24.5:
            if get(x, -1, z) == AIR or get(x, 1, z) != AIR or get(x, 0, z) not in (AIR, SNOW1, SNOW2):
                if not (-24 <= x <= -21 and -54 <= z <= -48):
                    rough += 1
        if r < VAULT_R - 0.5 and pillar_at(x, z, VAULT_PILLARS) is None:
            if get(x, VAULT_Y - 1, z) == AIR or get(x, VAULT_Y + 1, z) != AIR \
                    or get(x, VAULT_Y, z) not in (AIR, SNOW1, SNOW2):
                rough += 1
                problems.append(f'Gewölbeboden uneben bei {x},{z}')
    print(f'unebene Laufspalten: {rough}')
    for i, (px, pz) in enumerate(TEMPLE_PILLARS):
        if abs(px) - PILLAR_HALF <= 3:
            print(f'  Hinweis: Tempelsäule {i} bei x={px}, z={pz} reicht bis x={px - PILLAR_HALF if px > 0 else px + PILLAR_HALF} (Mittelgang |x| ≤ 3)')
    # Punkte prüfen
    def free(px, py, pz, name, need=2):
        bx, by, bz = math.floor(px), math.floor(py), math.floor(pz)
        if get(bx, by - 1, bz) == AIR:
            problems.append(f'{name}: kein Boden unter {bx},{by - 1},{bz}')
        for k in range(need):
            if get(bx, by + k, bz) not in (AIR, SNOW1, SNOW2):
                problems.append(f'{name}: belegt bei {bx},{by + k},{bz} ({NAMES[get(bx, by + k, bz)]})')
    free(*SEAT, 'SEAT')
    free(*CUT_STAND, 'CUT_STAND')
    free(*WATCH_SEAT, 'WATCH_SEAT')
    free(*WATCH_UPPER, 'WATCH_UPPER')
    free(*WATCH_VAULT, 'WATCH_VAULT')
    free(*ARRIVAL, 'ARRIVAL')
    free(*VAULT_REST, 'VAULT_REST')
    for i, (lx, lz) in enumerate(LANDING):
        free(lx, 0.0, lz, f'LANDING{i}')
    for i, (lx, lz) in enumerate(VAULT_LANDING):
        free(lx, -36.0, lz, f'VAULT_LANDING{i}')
    # Tor frei
    for x in range(-GATE_HALF_WIDTH, GATE_HALF_WIDTH + 1):
        for z in range(-34, -26):
            for y in range(0, GATE_HEIGHT):
                if get(x, y, z) != AIR:
                    problems.append(f'Tor belegt bei {x},{y},{z}')
    # Schnittkorridor (x 0..2) im Tempel frei bis auf Säulen? (nur Info)
    # Einsturz: Loch geht bis ins Gewölbe
    for ring in range(3):
        for x, z, ytop in collapse_ring_cols(ring):
            if not in_vault_air(x, ytop, z):
                problems.append(f'Einsturz Ring {ring}: Unterkante {x},{ytop},{z} ist nicht Gewölbeluft')
    print(f'Probleme: {len(problems)}')
    for p in problems[:40]:
        print('  ' + p)

    # --- Bilder ---
    def top_view(getter, fname, marks=True):
        pix = []
        for z in zs:
            row = []
            for x in xs:
                c = (18, 22, 30)
                for y in range(BOUND_Y_MAX, BOUND_Y_MIN - 1, -1):
                    b = getter(x, y, z)
                    if b:
                        f = 0.55 + 0.45 * (y - BOUND_Y_MIN) / (BOUND_Y_MAX - BOUND_Y_MIN)
                        c = shade(COLORS[b], f + 0.25 if y >= -1 else f)
                        break
                row.append(c)
            pix.append(row)
        if marks:
            for lx, lz in LANDING:
                mark(pix, math.floor(lx) + BOUND_XZ, math.floor(lz) - BOUND_Z_MIN, (255, 80, 80))
            for k in range(-66, 53):
                if k % 3 == 0:
                    mark(pix, 1 + BOUND_XZ, k - BOUND_Z_MIN, (255, 60, 200))
        write_png(os.path.join(out_dir, fname), len(xs), len(zs), pix, 4)

    def plan_at(y, fname, x0=-BOUND_XZ, x1=BOUND_XZ, z0=BOUND_Z_MIN, z1=BOUND_Z_MAX, scale=5):
        pix = []
        for z in range(z0, z1 + 1):
            row = []
            for x in range(x0, x1 + 1):
                b = get(x, y, z)
                row.append(COLORS[b] if b else (18, 22, 30))
            pix.append(row)
        write_png(os.path.join(out_dir, fname), x1 - x0 + 1, z1 - z0 + 1, pix, scale)

    def cut(axis, v, fname):
        pix = []
        rng = zs if axis == 'x' else xs
        for y in range(BOUND_Y_MAX, BOUND_Y_MIN - 1, -1):
            row = []
            for u in rng:
                b = get(v, y, u) if axis == 'x' else get(u, y, v)
                c = COLORS[b] if b else ((40, 46, 60) if y >= 0 else (18, 22, 30))
                if y == 0 and not b:
                    c = (60, 66, 84)
                row.append(c)
            pix.append(row)
        write_png(os.path.join(out_dir, fname), len(rng), NY, pix, 5)

    top_view(get, 'top.png')
    plan_at(5, 'plan_y5.png', -26, 26, -76, -24, 8)
    plan_at(-1, 'plan_y-1.png', -26, 26, -76, -24, 8)
    plan_at(12, 'plan_y12.png', -26, 26, -76, -24, 8)
    plan_at(-36, 'plan_y-36.png', -33, 33, -33, 33, 7)
    plan_at(-37, 'plan_y-37.png', -33, 33, -33, 33, 7)
    cut('x', 0, 'cut_x0.png')
    cut('x', 1, 'cut_x1.png')
    cut('z', -50, 'cut_z-50.png')
    cut('z', 0, 'cut_z0.png')

    # Nach Schnitt und Einsturz
    removed = set()
    for (x, z), col in plan.items():
        dt = math.sqrt(x * x + (z - TEMPLE_Z) ** 2)
        if x >= 2 and dt <= 23.0 and is_ground(x, z):
            for y in range(0, BOUND_Y_MAX + 1):
                removed.add((x, y, z))
        for y in range(0, BOUND_Y_MAX + 1):
            if cleft_contains(x, y, z):
                removed.add((x, y, z))
    for ring in range(3):
        for x, z, ytop in collapse_ring_cols(ring):
            for y in range(ytop + 1, 9):
                removed.add((x, y, z))
    rubble = {}
    for x in range(0, 40):
        for z in range(-72, -28):
            h = rubble_height(x, z)
            for y in range(0, h):
                if (x, y, z) in removed or get(x, y, z) in (AIR, SNOW1, SNOW2):
                    if get(x, y - 1, z) != AIR or (x, y - 1, z) in rubble or y == 0:
                        rubble[(x, y, z)] = rubble_block(x, y, z)
    print(f'Trümmer: {len(rubble)} Blöcke')

    def after(x, y, z):
        if (x, y, z) in rubble:
            return rubble[(x, y, z)]
        if (x, y, z) in removed:
            return AIR
        return get(x, y, z)
    top_view(after, 'after_cut.png', marks=False)
    print(f'Bilder in {out_dir}')


if __name__ == '__main__':
    main()
