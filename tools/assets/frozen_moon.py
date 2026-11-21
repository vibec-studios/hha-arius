"""Texturen des gefrorenen Sterns (Mond-Angriff, seit 1.10.0) – prozedural, ohne fremde Vorlagen.

Aufruf (aus dem Projektordner): python tools/assets/frozen_moon.py

Schreibt nach assets/hha_arius/textures/fx/:
- frozen_moon.png        1024 x 512, äquirektangulär: Eiskruste mit tiefen Kratern, Terrassenrändern, Strahlen, Eisebenen.
- frozen_moon_glow.png   1024 x 512: leuchtende kristalline Eisrisse (türkis-eisblau, Alpha = Leuchten).
- frozen_moon_relief.bin 49 x 97 signed int16 radiales Höhenprofil für die echte 3D-Kugel.
- moon_halo.png          256 x 256: weicher Lichthof ohne harte Kanten.
- moon_limb.png          256 x 256: Atmosphärensaum genau auf dem Scheibenrand (r = 0.5).
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


def ridged(p, scale, octaves, seed, sharpness=30.0):
    """Rissnetz: schmale Grate dort, wo das Rauschen die Null kreuzt."""
    n = noise3(p, scale, octaves, seed)
    return np.clip(1.0 - np.abs(n) * sharpness, 0.0, 1.0) ** 2


def surface():
    p = DIRS
    # Eisebenen (Maria): kontrastreiche, tiefere Frostbecken.
    mare = noise3(p, 1.35, 4, 3)
    mare = np.clip((mare + 0.06) * 2.4, 0, 1)
    mare = mare * mare * (3 - 2 * mare)
    # Feine kristalline Oberflächenkörnung + tektonische Eisgrate.
    grain = noise3(p, 9.0, 4, 7) * 0.065 + noise3(p, 38.0, 3, 9) * 0.04
    ridges = ridged(p, 3.2, 2, 15, sharpness=14.0) * 0.08
    alb = 0.87 - mare * 0.38 + grain + ridges

    # echtes 3D-Relief auf dem 49x97-Kugelnetz (stärkere Kratermulden, Terrassenränder und Eisgrate).
    lat, lon = np.meshgrid(np.linspace(0, np.pi, 49), np.linspace(0, 2 * np.pi, 97), indexing='ij')
    mesh = np.stack([np.sin(lat) * np.cos(lon), np.cos(lat), np.sin(lat) * np.sin(lon)], axis=-1)
    mesh_mare = np.clip((noise3(mesh, 1.35, 4, 3) + 0.06) * 2.4, 0, 1)
    mesh_mare = mesh_mare * mesh_mare * (3 - 2 * mesh_mare)
    relief = noise3(mesh, 2.2, 3, 11) * 0.018 - mesh_mare * 0.016

    # Krater: tiefe Mulde mit dunklem AO-Boden, scharfer heller Terrassenrand, leuchtende Auswurfstrahlen.
    rays = np.zeros(p.shape[:2])
    for k in range(74):
        v = rng.normal(size=3)
        v /= np.linalg.norm(v)
        size = 0.032 + (rng.random() ** 2.15) * 0.24
        md = np.arccos(np.clip(mesh @ v, -1, 1)) / size
        # Sculpted 3D bowl + raised terraced rim + subtle central rebound peak on large craters.
        rim3d = 0.165 * np.exp(-((md - 0.94) / 0.18) ** 2)
        bowl3d = -0.31 * np.maximum(1.0 - md ** 2, 0.0) ** 1.3
        peak3d = (0.075 * np.exp(-(md / 0.22) ** 2)) if size > 0.15 else 0.0
        relief += size * (rim3d + bowl3d + peak3d)

        dot = np.clip(p @ v, -1, 1)
        d = np.arccos(dot) / size
        bowl = np.where(d < 0.84, -0.36 * np.clip(1 - (d / 0.84) ** 2, 0, 1) ** 0.72, 0.0)
        rim = np.exp(-((d - 0.94) / 0.085) ** 2) * 0.28
        terrace = np.exp(-((d - 0.78) / 0.05) ** 2) * 0.07 if size > 0.11 else 0.0
        peak_alb = np.exp(-(d / 0.16) ** 2) * 0.16 if size > 0.15 else 0.0
        alb = alb + bowl + rim + terrace + peak_alb
        if size > 0.115:
            ref = np.array([0.3, 1.0, 0.2])
            if abs(np.dot(v, ref)) > 0.9:
                ref = np.array([1.0, 0.2, 0.3])
            u_ax = np.cross(v, ref)
            u_ax /= np.linalg.norm(u_ax)
            w_ax = np.cross(v, u_ax)
            ang = np.arctan2(p @ u_ax, p @ w_ax)
            rib = np.clip(np.sin(ang * (10 + k % 7) + k) * 0.5 + 0.5, 0, 1) ** 5
            fall = np.clip(1.0 - (d - 1.0) / 2.9, 0, 1) * (d > 1.0)
            rays += rib * fall * 0.11

    # Nahtlos an den Polen und am Längengrad-Übergang schließen.
    relief = np.clip(relief, -0.14, 0.12)
    relief[:, -1] = relief[:, 0]
    relief[0, :] = relief[0, 0]
    relief[-1, :] = relief[-1, 0]
    np.rint(relief * 32767).astype('<i2').tofile(os.path.join(OUT, 'frozen_moon_relief.bin'))

    alb = alb + rays
    mask = np.clip(noise3(p, 1.15, 2, 31) * 2.4 + 0.42, 0, 1)
    cracks_main = ridged(p, 2.2, 3, 21, sharpness=26.0)
    cracks_fine = ridged(p, 5.2, 2, 23, sharpness=30.0)
    cracks = np.clip((cracks_main * 0.9 + cracks_fine * 0.45) * mask, 0, 1)
    alb = np.clip(alb + cracks * 0.16, 0.04, 1.0)

    # Farbe: tieferes Saphir-Schiefer in den Becken, kristallines Eisweiß auf den Graten, leuchtende Cyan-Risse.
    lo = np.array([0.30, 0.44, 0.64])
    hi = np.array([0.96, 0.99, 1.00])
    col = lo[None, None, :] * (1 - alb[..., None]) + hi[None, None, :] * alb[..., None]
    col = col * (1 - 0.22 * mare[..., None]) + np.array([0.04, 0.10, 0.22]) * 0.22 * mare[..., None]
    col = col + cracks[..., None] * np.array([0.02, 0.14, 0.20])
    rgb = (np.clip(col, 0, 1) * 255).astype(np.uint8)
    a = np.full(p.shape[:2] + (1,), 255, np.uint8)
    Image.fromarray(np.concatenate([rgb, a], axis=-1), 'RGBA').save(os.path.join(OUT, 'frozen_moon.png'), optimize=True)

    # Leuchtende Risse: kräftige elektrische Eisspalten + feines Begleitnetz.
    glow = np.clip((cracks_main * 1.25 + cracks_fine * 0.42) * mask, 0, 1)
    glow = np.clip(glow * 1.35 - 0.04, 0, 1)
    g = np.zeros(p.shape[:2] + (4,), np.uint8)
    g[..., 0] = 175
    g[..., 1] = 238
    g[..., 2] = 255
    g[..., 3] = (glow * 255).astype(np.uint8)
    Image.fromarray(g, 'RGBA').save(os.path.join(OUT, 'frozen_moon_glow.png'), optimize=True)


def halo():
    n = 256
    y, x = np.mgrid[0:n, 0:n]
    r = np.hypot(x + 0.5 - n / 2, y + 0.5 - n / 2) / (n / 2)
    a = 0.55 * np.exp(-(r / 0.32) ** 2) + 0.45 * np.exp(-r / 0.2)
    a *= np.clip((1.0 - r) / 0.25, 0, 1) ** 2
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
    r = np.hypot(x + 0.5 - n / 2, y + 0.5 - n / 2) / (n / 2)
    d = r - 0.5
    inner = np.exp(-(np.minimum(d, 0) / 0.032) ** 2)
    outer = np.exp(-np.maximum(d, 0) / 0.075)
    a = np.where(d < 0, inner, outer)
    a *= np.clip((1.0 - r) / 0.2, 0, 1) ** 2
    img = np.zeros((n, n, 4), np.uint8)
    img[..., 0] = 220
    img[..., 1] = 246
    img[..., 2] = 255
    img[..., 3] = (np.clip(a, 0, 1) * 255).astype(np.uint8)
    Image.fromarray(img, 'RGBA').save(os.path.join(OUT, 'moon_limb.png'), optimize=True)


if __name__ == '__main__':
    os.makedirs(OUT, exist_ok=True)
    surface()
    halo()
    limb()
    print('ok')
