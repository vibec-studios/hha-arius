"""Fernlandschaft von Eirholm (Nadel, Schlucht, Gletscherzüge): Geometrie-Spiegel von `arena/FarPlan.kt` mit Vorschau.

Aufruf (aus dem Projektordner):
    python tools/assets/far_preview.py [ausgabeordner]      # Standard: tmp/preview/far

Gleiche Formeln wie in FarPlan.kt (Höhe, Haut, Material). Ausgabe:
    far_top.png      Draufsicht (Höhe schattiert, Material gefärbt), Kern-Arena als Umriss
    far_view.png     Blick vom Plateau (0, 1.6, -20) nach Süden: einfache Höhenfeld-Projektion
    far_side.png     Schnitt bei x = 0 (Nord–Süd)
"""
import math
import os
import struct
import sys
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, '..', '..'))

# --- ArenaLayout (gleiche Werte) --------------------------------------------------------------------------------------
SPIRE = (0, -40, 128)
SPIRE_TOP = 185
SPIRE_BASE_R = 38.0
CANYON_Z_FROM, CANYON_Z_TO, CANYON_FLOOR = 56, 92, -70
FAR_X, FAR_Z_MAX, FAR_Y_MIN = 140, 200, -80
FAR_Z_MIN = -130
CORE_X, CORE_Z_MIN, CORE_Z_MAX = 52, -80, 52

M32 = 0xFFFFFFFF


def h32(x, y, z, s):
    h = ((x * 73856093) ^ (y * 19349663) ^ (z * 83492791) ^ (s * -1640531535)) & M32
    h ^= h >> 13
    h = (h * 0x5bd1e995) & M32
    h ^= h >> 15
    return (h & 0xFFFFFF) / float(0x1000000)


def vnoise(x, z, cell, s):
    fx, fz = x / cell, z / cell
    x0, z0 = math.floor(fx), math.floor(fz)
    tx, tz = fx - x0, fz - z0
    tx = tx * tx * (3 - 2 * tx)
    tz = tz * tz * (3 - 2 * tz)
    a = h32(x0, 0, z0, s)
    b = h32(x0 + 1, 0, z0, s)
    c = h32(x0, 0, z0 + 1, s)
    d = h32(x0 + 1, 0, z0 + 1, s)
    return (a + (b - a) * tx) + ((c + (d - c) * tx) - (a + (b - a) * tx)) * tz


def fbm(x, z, cell, s, octaves=4):
    v, amp, tot = 0.0, 1.0, 0.0
    for o in range(octaves):
        v += vnoise(x, z, max(1, cell >> o), s + o * 31) * amp
        tot += amp
        amp *= 0.5
    return v / tot


def ridged(x, z, cell, s, octaves=4):
    v, amp, tot = 0.0, 1.0, 0.0
    for o in range(octaves):
        n = 1.0 - abs(vnoise(x, z, max(1, cell >> o), s + o * 17) * 2.0 - 1.0)
        v += n * n * amp
        tot += amp
        amp *= 0.5
    return v / tot


def smooth(e0, e1, v):
    t = min(1.0, max(0.0, (v - e0) / (e1 - e0)))
    return t * t * (3 - 2 * t)


def canyon_center(z):
    return 9.0 * math.sin(z / 19.0) + 4.0 * math.sin(z / 7.3)


def canyon_half(z):
    # schmal an den Enden, in der Mitte bis 34 breit
    t = (z - CANYON_Z_FROM) / float(CANYON_Z_TO - CANYON_Z_FROM)
    return 14.0 + 22.0 * math.sin(math.pi * min(1.0, max(0.0, t)))


def spire_top(x, z):
    """Höhe der Nadel über (x, z) oder None. Kannelierte Flanken, leicht gedreht."""
    dx, dz = x + 0.5 - (SPIRE[0] + 0.5), z + 0.5 - (SPIRE[2] + 0.5)
    d = math.hypot(dx, dz)
    if d > SPIRE_BASE_R * 1.25:
        return None
    a = math.atan2(dx, dz)
    flute = 1.0 + 0.13 * math.sin(5 * a + d * 0.08) + 0.06 * math.sin(11 * a - 1.3)
    r = d / (SPIRE_BASE_R * flute)
    if r >= 1.0:
        return None
    h = SPIRE[1] + (SPIRE_TOP - SPIRE[1]) * (1.0 - r) ** 1.35
    h += (vnoise(x, z, 6, 91) - 0.5) * 4.0 * (1.0 - r)
    return int(math.floor(h))


def ground(x, z):
    """Oberkante (y) des Gletschergeländes ohne Nadel."""
    ax = abs(x)
    base = -14.0 + (fbm(x, z, 32, 3) - 0.5) * 8.0
    # Gletscherzüge in Ost und West: steigen ab |x| 56 steil an, gezackte Grate (Seracs)
    rise = smooth(54.0, 118.0, ax)
    ridge = ridged(x + 400, z, 48, 7)
    h = base + rise * (70.0 + 55.0 * ridge) + rise * (vnoise(x, z, 5, 13) - 0.5) * 7.0
    # im Norden hinter dem Tempel: Klippen
    north = smooth(-92.0, -128.0, z)
    h = max(h, base + north * (60.0 + 30.0 * ridged(x, z + 300, 40, 11)))
    # Sichtachse: zwischen den Zügen bleibt es tief (Tal Richtung Nadel)
    # Schelf um die Nadel (Fuß bei y -40), steigt zu den Zügen an
    sb = smooth(CANYON_Z_TO - 16.0, CANYON_Z_TO + 10.0, z)
    if sb > 0.0:
        shelf = -38.0 + (fbm(x, z, 20, 5) - 0.5) * 6.0
        sh = max(shelf + rise * 40.0, min(h, shelf + rise * 120.0))
        h = h + (sh - h) * sb
    # Schlucht
    if CANYON_Z_FROM - 10 <= z <= CANYON_Z_TO + 10:
        cz = min(1.0, (z - (CANYON_Z_FROM - 10)) / 10.0, ((CANYON_Z_TO + 10) - z) / 10.0)
        dxc = abs(x - canyon_center(z))
        half = canyon_half(min(max(z, CANYON_Z_FROM), CANYON_Z_TO))
        inside = 1.0 - smooth(half - 1.0, half + 5.0 + 3.0 * vnoise(x, z, 4, 21), dxc)
        floor_y = CANYON_FLOOR + (vnoise(x, z, 9, 23) - 0.5) * 6.0
        h = h + (floor_y - h) * inside * max(0.0, cz)
    return int(math.floor(h))


def in_core(x, z):
    return -CORE_X <= x <= CORE_X and CORE_Z_MIN <= z <= CORE_Z_MAX


def top(x, z):
    t = ground(x, z)
    s = spire_top(x, z)
    if s is not None and s > t:
        t = s
    if in_core(x, z):
        # unter der Kern-Arena: Gletscherrumpf bis knapp unter das Gewölbe, damit nichts schwebt
        t = min(t, -41)
    return max(FAR_Y_MIN, min(SPIRE_TOP, t))


def material(x, y, z, t, slope, spire):
    """Block-Id: 1 packed_ice, 2 blue_ice, 3 snow_block, 11 deepslate, 12 tuff, 13 calcite"""
    depth = t - y
    if spire:
        if depth == 0 and slope <= 2:
            return 3
        band = (y + int(6 * vnoise(x, z, 7, 41))) % 9
        return 2 if band < 2 or h32(x, y, z, 43) < 0.08 else 1
    if y < CANYON_FLOOR + 6 and depth > 0:
        return 11 if h32(x, y, z, 51) < 0.6 else 12
    if depth == 0 and slope <= 2:
        return 3
    if depth <= 1 and slope <= 1:
        return 3
    band = (y + int(5 * vnoise(x, z, 11, 61))) % 7
    if band == 0:
        return 2
    if slope >= 6 and h32(x, y, z, 63) < 0.2:
        return 2
    return 1


COLORS = {0: (0, 0, 0), 1: (150, 184, 226), 2: (88, 140, 220), 3: (240, 244, 250), 11: (70, 70, 80), 12: (100, 100, 96), 13: (220, 220, 214)}


def write_png(path, w, h, px):
    raw = b''.join(b'\x00' + bytes(sum((list(px[y * w + x]) for x in range(w)), [])) for y in range(h))
    def chunk(t, d):
        return struct.pack('>I', len(d)) + t + d + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)
    data = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 2, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(raw, 6)) + chunk(b'IEND', b'')
    open(path, 'wb').write(data)


def main(argv):
    out = argv[0] if argv else os.path.join(ROOT, 'tmp', 'preview', 'far')
    os.makedirs(out, exist_ok=True)
    xs = range(-FAR_X, FAR_X + 1)
    zs = range(FAR_Z_MIN, FAR_Z_MAX + 1)
    tops = {}
    for x in xs:
        for z in zs:
            tops[(x, z)] = top(x, z)
    # Draufsicht
    W, H = len(xs), len(zs)
    px = []
    total = 0
    for z in zs:
        for x in xs:
            t = tops[(x, z)]
            nb = [tops.get((x + dx, z + dz), t) for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1))]
            slope = max(abs(t - n) for n in nb)
            total += max(1, t - min(nb) + 1) if not in_core(x, z) else 0
            m = material(x, t, z, t, slope, spire_top(x, z) is not None and spire_top(x, z) >= ground(x, z))
            c = COLORS[m]
            shade = 0.45 + 0.55 * (t - FAR_Y_MIN) / (SPIRE_TOP - FAR_Y_MIN)
            light = 1.0 - 0.04 * min(8, (tops.get((x - 1, z - 1), t) - t))
            if in_core(x, z):
                c = (50, 50, 60)
            px.append(tuple(max(0, min(255, int(ch * shade * light))) for ch in c))
    write_png(os.path.join(out, 'far_top.png'), W, H, px)
    print('Haut-Blöcke (geschätzt):', total)
    # Schnitt x = 0
    SH = SPIRE_TOP - FAR_Y_MIN + 1
    px = [(26, 30, 40)] * (H * SH)
    for zi, z in enumerate(zs):
        t = tops[(0, z)]
        for y in range(FAR_Y_MIN, t + 1):
            row = SPIRE_TOP - y
            px[row * H + zi] = (150, 184, 226) if y < t else (240, 244, 250)
    write_png(os.path.join(out, 'far_side.png'), H, SH, px)
    # Blick vom Plateau nach Süden (Raymarch über das Höhenfeld)
    VW, VH = 240, 135
    eye = (0.5, 1.6 + 2, -20.5)
    fov = math.radians(70)
    px = []
    for j in range(VH):
        for i in range(VW):
            u = (i / VW - 0.5) * 2 * math.tan(fov / 2) * VW / VH
            v = (0.5 - j / VH) * 2 * math.tan(fov / 2) + 0.35
            d = (u, v, 1.0)
            n = math.sqrt(sum(c * c for c in d))
            d = tuple(c / n for c in d)
            col = None
            tt = 2.0
            while tt < 260:
                x = eye[0] + d[0] * tt
                y = eye[1] + d[1] * tt
                z = eye[2] + d[2] * tt
                xi, zi = int(math.floor(x)), int(math.floor(z))
                t = tops.get((xi, zi))
                if t is not None and y <= t + 1 and not (in_core(xi, zi) and z < 48):
                    fog = min(1.0, tt / 240.0)
                    base = (240, 244, 250) if y > t - 0.5 else (150, 184, 226)
                    shade = 0.75 + 0.25 * (t - y if y < t else 1.0) / 1.0
                    col = tuple(int(c * (1 - fog) * min(1.0, shade) + 210 * fog) for c in base)
                    break
                tt += 1.0 + tt * 0.01
            if col is None:
                col = (int(180 + 40 * j / VH), int(200 + 30 * j / VH), 228)
            px.append(col)
    write_png(os.path.join(out, 'far_view.png'), VW, VH, px)
    print('fertig:', out)


if __name__ == '__main__':
    main(sys.argv[1:])
