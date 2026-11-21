"""Texturen des gefrorenen Sterns (Mond-Angriff, seit 1.10.0) – prozedural, ohne fremde Vorlagen.

Aufruf (aus dem Projektordner): python tools/assets/frozen_moon.py

Schreibt nach assets/hha_arius/textures/fx/:
- frozen_moon.png       1024 x 512, äquirektangulär: Eiskruste (Albedo, leicht bläulich), große dunklere Eisebenen,
                        Krater mit hellem Rand und Strahlen, feine Frostkörnung, Rissnetz (etwas heller).
- frozen_moon_glow.png  1024 x 512: nur die leuchtenden Risse (eisblau, Alpha = Leuchten), für die Glühschicht.
- moon_halo.png         256 x 256: weicher Lichthof (Alpha fällt gaußförmig und dann exponentiell ab, keine Kante).
- moon_limb.png         256 x 256: Atmosphärensaum am Rand der Scheibe (Scheibenrand bei r = 0,5 der Kantenlänge
                        → bei Billboard-Größe 2 r liegt der Saum genau auf dem Rand), innen schnell, außen weich auslaufend.
Die Kanten der äquirektangulären Karte schließen nahtlos (periodisches Rauschen in der Länge).
"""
import math
import os

import numpy as np
from PIL import Image

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
OUT = os.path.join(ROOT, 'src', 'main', 'resources', 'assets', 'hha_arius', 'textures', 'fx')
W, H = 1024, 512
rng = np.random.default_rng(1729)


def sphere_dirs():
    lon = (np.arange(W) + 0.5) / W * 2 * math.pi
    lat = (np.arange(H) + 0.5) / H * math.pi
    th, ph = np.meshgrid(lat, lon, indexing='ij')
    x = np.sin(th) * np.cos(ph)
    y = np.cos(th)
    z = np.sin(th) * np.sin(ph)
    return np.stack([x, y, z], axis=-1)


DIRS = sphere_dirs()


def noise3(p, scale, octaves, seed):
    """Wertrauschen auf der Kugel (3D-Gitter, trilinear, glatt), Summe über Oktaven, Bereich etwa -1..1."""
    r = np.random.default_rng(seed)
    total = np.zeros(p.shape[:2])
    amp = 1.0
    norm = 0.0
    for o in range(octaves):
        n = 32
        grid = r.uniform(-1, 1, (n, n, n))
        q = (p * scale * (2 ** o) + 50.0) % n
        i0 = np.floor(q).astype(int)
        f = q - i0
        f = f * f * (3 - 2 * f)
        i1 = (i0 + 1) % n
        acc = 0.0
        for dx in (0, 1):
            for dy in (0, 1):
                for dz in (0, 1):
                    ix = i1[..., 0] if dx else i0[..., 0]
                    iy = i1[..., 1] if dy else i0[..., 1]
                    iz = i1[..., 2] if dz else i0[..., 2]
                    wx = f[..., 0] if dx else 1 - f[..., 0]
                    wy = f[..., 1] if dy else 1 - f[..., 1]
                    wz = f[..., 2] if dz else 1 - f[..., 2]
                    acc = acc + grid[ix, iy, iz] * wx * wy * wz
        total += acc * amp
        norm += amp
        amp *= 0.5
    return total / norm


def ridged(p, scale, octaves, seed):
    """Rissnetz: schmale Grate dort, wo das Rauschen die Null kreuzt."""
    n = noise3(p, scale, octaves, seed)
    return np.clip(1.0 - np.abs(n) * 32.0, 0.0, 1.0) ** 2


def surface():
    p = DIRS
    # Eisebenen (Maria): große, weiche Flecken.
    mare = noise3(p, 1.3, 4, 3)
    mare = np.clip((mare + 0.05) * 2.2, 0, 1)
    mare = mare * mare * (3 - 2 * mare)
    # Grundalbedo mit mittlerer Körnung.
    grain = noise3(p, 9.0, 4, 7) * 0.06 + noise3(p, 40.0, 3, 9) * 0.035
    alb = 0.86 - mare * 0.32 + grain
    # Krater: Mulde (dunkler), heller Ring, schwache Strahlen.
    rays = np.zeros(p.shape[:2])
    for k in range(70):
        v = rng.normal(size=3)
        v /= np.linalg.norm(v)
        size = 0.03 + (rng.random() ** 2.2) * 0.22
        dot = np.clip(p @ v, -1, 1)
        d = np.arccos(dot) / size
        bowl = np.where(d < 0.8, -0.3 * np.clip(1 - (d / 0.8) ** 2, 0, 1) ** 0.7, 0.0)
        rim = np.exp(-((d - 0.92) / 0.1) ** 2) * 0.22
        alb = alb + bowl + rim
        if size > 0.12:
            # Strahlen: nach außen auslaufende Rippen (Winkelrauschen um den Krater).
            ang = np.arctan2(p @ np.cross(v, [0.3, 1, 0.2]) / 1.0, p @ np.cross(v, np.cross(v, [0.3, 1, 0.2])))
            rib = np.clip(np.sin(ang * (9 + k % 7) + k) * 0.5 + 0.5, 0, 1) ** 6
            fall = np.clip(1.0 - (d - 1.0) / 2.6, 0, 1) * (d > 1.0)
            rays += rib * fall * 0.08
    alb = alb + rays
    mask = np.clip(noise3(p, 1.1, 2, 31) * 2.5 + 0.35, 0, 1)
    cracks = (ridged(p, 2.2, 3, 21) * 0.8 + ridged(p, 5.0, 2, 23) * 0.4) * mask
    cracks = np.clip(cracks, 0, 1)
    alb = np.clip(alb + cracks * 0.12, 0.05, 1.0)
    # Farbe: helles Eis leicht bläulich, Ebenen kühler/dunkler, Risse türkis.
    lo = np.array([0.42, 0.55, 0.70])
    hi = np.array([0.96, 0.98, 1.00])
    col = lo[None, None, :] * (1 - alb[..., None]) + hi[None, None, :] * alb[..., None]
    col = col * (1 - 0.15 * mare[..., None]) + np.array([0.05, 0.1, 0.18]) * 0.15 * mare[..., None]
    col = col + cracks[..., None] * np.array([0.0, 0.08, 0.12])
    rgb = (np.clip(col, 0, 1) * 255).astype(np.uint8)
    a = np.full(p.shape[:2] + (1,), 255, np.uint8)
    Image.fromarray(np.concatenate([rgb, a], axis=-1), 'RGBA').save(os.path.join(OUT, 'frozen_moon.png'), optimize=True)
    # Leuchtende Risse: nur die kräftigsten Linien, weich verbreitert.
    glow = np.clip((ridged(p, 2.2, 3, 21) * 1.1 + ridged(p, 5.0, 2, 23) * 0.3) * mask, 0, 1)
    glow = np.clip(glow * 1.2 - 0.05, 0, 1)
    g = np.zeros(p.shape[:2] + (4,), np.uint8)
    g[..., 0] = 200
    g[..., 1] = 240
    g[..., 2] = 255
    g[..., 3] = (glow * 255).astype(np.uint8)
    Image.fromarray(g, 'RGBA').save(os.path.join(OUT, 'frozen_moon_glow.png'), optimize=True)


def halo():
    n = 256
    y, x = np.mgrid[0:n, 0:n]
    r = np.hypot(x + 0.5 - n / 2, y + 0.5 - n / 2) / (n / 2)
    a = 0.55 * np.exp(-(r / 0.32) ** 2) + 0.45 * np.exp(-r / 0.2)
    a *= np.clip((1.0 - r) / 0.25, 0, 1) ** 2  # sicher auf 0 vor der Quadratkante
    a = np.clip(a / a.max(), 0, 1)
    img = np.zeros((n, n, 4), np.uint8)
    img[..., 0] = 255
    img[..., 1] = 255
    img[..., 2] = 255
    img[..., 3] = (a * 255).astype(np.uint8)
    Image.fromarray(img, 'RGBA').save(os.path.join(OUT, 'moon_halo.png'), optimize=True)


def limb():
    n = 256
    y, x = np.mgrid[0:n, 0:n]
    r = np.hypot(x + 0.5 - n / 2, y + 0.5 - n / 2) / (n / 2)  # 1 = Kante; Scheibenrand bei 0.5
    d = r - 0.5
    inner = np.exp(-(np.minimum(d, 0) / 0.025) ** 2)
    outer = np.exp(-np.maximum(d, 0) / 0.07)
    a = np.where(d < 0, inner, outer)
    a *= np.clip((1.0 - r) / 0.2, 0, 1) ** 2
    img = np.zeros((n, n, 4), np.uint8)
    img[..., 0] = 255
    img[..., 1] = 255
    img[..., 2] = 255
    img[..., 3] = (np.clip(a, 0, 1) * 255).astype(np.uint8)
    Image.fromarray(img, 'RGBA').save(os.path.join(OUT, 'moon_limb.png'), optimize=True)


if __name__ == '__main__':
    os.makedirs(OUT, exist_ok=True)
    surface()
    halo()
    limb()
    print('ok')
