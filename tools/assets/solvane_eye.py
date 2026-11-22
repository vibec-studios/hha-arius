"""Solvanes Auge für die Höhlenszene: Atlas 512x256 aus Augapfel, Schlitzpupille, Ober- und Unterlid."""
import math
import random
from pathlib import Path

from PIL import Image

OUT = Path(__file__).resolve().parents[2] / "src/main/resources/assets/hha_arius/textures/fx/solvane_eye.png"
W, H = 512, 256


def clamp(v):
    return max(0, min(255, int(round(v))))


def smooth(a, b, x):
    t = max(0.0, min(1.0, (x - a) / (b - a)))
    return t * t * (3 - 2 * t)


def mix(a, b, k):
    return a + (b - a) * k


def lobes(rng, count):
    return [(rng.uniform(0, math.tau), rng.randint(3, 19), rng.uniform(0.2, 1.0)) for _ in range(count)]


def fibre(angle, table):
    return sum(w * math.sin(angle * n + p) for p, n, w in table) / sum(w for _, _, w in table)


def eyeball(px, x, y, fibres, veins):
    """Kreis in (0..256, 0..256): dunkler Augapfel, Limbus, Iris aus Eisfasern."""
    dx = (x + 0.5 - 128) / 124
    dy = (y + 0.5 - 128) / 124
    r = math.hypot(dx, dy)
    if r > 1.0:
        px[x, y] = (0, 0, 0, 0)
        return
    ang = math.atan2(dy, dx)
    # Augapfel: fast schwarzes Blau mit feinen, blassen Adern, die zur Iris laufen.
    vein = max(0.0, fibre(ang, veins) - 0.42) * 2.4 * smooth(0.98, 0.62, r)
    base = (10 + 26 * vein, 16 + 40 * vein, 28 + 52 * vein)
    iris_r = 0.70
    if r < iris_r:
        k = r / iris_r
        f = 0.5 + 0.5 * fibre(ang + 0.5 * k, fibres)
        streak = f ** 2.2
        # innen fast weißes Eis, außen tiefes Blau, dunkler Limbus
        c0, c1, c2 = (232, 250, 255), (92, 196, 240), (16, 58, 112)
        t = smooth(0.12, 0.62, k)
        col = [mix(c0[i], c1[i], t) for i in range(3)]
        t2 = smooth(0.58, 1.0, k)
        col = [mix(col[i], c2[i], t2) for i in range(3)]
        shade = 0.42 + 0.78 * streak
        col = [c * shade for c in col]
        # heller Ring um die Pupille
        ring = math.exp(-(((k - 0.2) / 0.075) ** 2)) * 0.8
        col = [mix(col[i], (240, 252, 255)[i], ring) for i in range(3)]
        limbus = smooth(0.86, 1.0, k)
        base = [mix(col[i], base[i] * 0.4, limbus) for i in range(3)]
    edge = smooth(1.0, 0.95, r)
    px[x, y] = (clamp(base[0]), clamp(base[1]), clamp(base[2]), clamp(255 * edge))


def pupil(px, x, y):
    """Schlitz in (256..384, 0..256) mit weichem Rand."""
    dx = (x + 0.5 - 320) / 62
    dy = (y + 0.5 - 128) / 124
    # Spitz zulaufende Linse statt einer Ellipse.
    half = max(0.0, 1.0 - abs(dy) ** 1.6)
    d = abs(dx) - half
    a = smooth(0.1, -0.1, d) * smooth(1.0, 0.92, abs(dy))
    px[x, y] = (2, 4, 9, clamp(255 * a))


def lid(px, x, y, y0, upper, rng, grain):
    """Lid in (384..512, y0..y0+128): schuppige Haut, die Kante zum Auge ist eine Mandelform."""
    u = (x + 0.5 - 448) / 64
    v = (y + 0.5 - y0) / 128
    if not upper:
        v = 1 - v
    # v = 1 ist die Lidkante in der Mitte; zu den Seiten zieht sie sich zum Augenwinkel.
    edge = 1.0 - 0.34 * u * u
    if v > edge:
        px[x, y] = (0, 0, 0, 0)
        return
    # Schuppen: versetzte Reihen, jede Schuppe unten heller.
    row = v * 9
    ri = int(row)
    su = (u + 1) * 5.5 + (0.5 if ri % 2 else 0.0)
    fu = su - int(su) - 0.5
    fv = row - ri
    arc = fv - (0.62 - 1.1 * fu * fu)
    scale = smooth(-0.12, 0.05, arc) * (1 - smooth(0.05, 0.2, arc))
    tone = 15 + 13 * (1 - fv) + 26 * scale + grain[y % 64][x % 64] * 5
    # Zum Rand hin glänzt das Lid feucht und wird dunkler.
    rim = smooth(edge - 0.16, edge - 0.015, v)
    tone *= 1 - 0.55 * rim
    wet = math.exp(-(((edge - 0.012 - v) / 0.016) ** 2)) * 0.55
    r = tone * 0.78 + 110 * wet
    g = tone * 0.9 + 170 * wet
    b = tone * 1.25 + 200 * wet
    px[x, y] = (clamp(r), clamp(g), clamp(b), 255)


def main():
    rng = random.Random(909)
    fibres = lobes(rng, 26)
    veins = lobes(rng, 12)
    grain = [[rng.gauss(0, 1) for _ in range(64)] for _ in range(64)]
    im = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    px = im.load()
    for y in range(H):
        for x in range(256):
            eyeball(px, x, y, fibres, veins)
        for x in range(256, 384):
            pupil(px, x, y)
        for x in range(384, 512):
            if y < 128:
                lid(px, x, y, 0, True, rng, grain)
            else:
                lid(px, x, y, 128, False, rng, grain)
    im.save(OUT)
    print("ok", OUT)


if __name__ == "__main__":
    main()
