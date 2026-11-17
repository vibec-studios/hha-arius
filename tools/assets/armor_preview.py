"""Vorschau der Drachenherz-Wacht ohne Spiel: ganze Figur (Steve-Körper, Rüstung, optional Handschuhe) als PNG.

Aufruf (aus dem Projektordner):
    python tools/assets/armor_preview.py               # alle Bögen nach tmp/preview/armor/
    python tools/assets/armor_preview.py --quick       # nur der Hauptbogen

Gerendert wird aus den fertig gemalten Atlanten mit genau der UV-Abbildung von ModelPart.Cuboid (Ober-/Unterseite:
Texelzeile 0 bei z max) – so zeigt die Vorschau auch Packfehler. Licht wie Minecrafts Entity-Licht (zwei Lichter von
oben, Seiten ~0,5, Vorderseiten ~0,74), Leuchtmaske wie die eyes-Ebene (Alpha-Mischung, lichtunabhängig).
Hängende Teile bewegt `hanging()` mit derselben Formel wie ArmorFeature.kt (Beinfolge der Tassetten).
"""
import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)

import armor_model as A  # noqa: E402
import gauntlets_model as GM  # noqa: E402
import gear_art as G  # noqa: E402
from preview_model import apply, mat_mul, rot_zyx  # noqa: E402

OUT = os.path.join(G.ROOT, 'tmp', 'preview', 'armor')
IDENT = [[1, 0, 0], [0, 1, 0], [0, 0, 1]]

_L0 = (0.2, -1.0, -0.7)
_L1 = (-0.2, -1.0, 0.7)
_n0 = math.sqrt(sum(c * c for c in _L0))
L0 = tuple(c / _n0 for c in _L0)
L1 = tuple(c / _n0 for c in _L1)


class Atlas:
    def __init__(self, w, h, px, res):
        self.w, self.h, self.px, self.res = w, h, px, res

    def get(self, x, y):
        if 0 <= x < self.w and 0 <= y < self.h:
            return self.px[y * self.w + x]
        return (0, 0, 0, 0)


def faces(cube, res):
    """(Name, Ursprung, U-Achse ganze Fläche, V-Achse, Normale, Atlas-x, Atlas-y, Texel w, Texel h) wie im Spiel."""
    d = getattr(cube, 'dilate', 0.0) or 0.0
    x0, y0, z0 = cube.x - d, cube.y - d, cube.z - d
    x1, y1, z1 = cube.x + cube.w + d, cube.y + cube.h + d, cube.z + cube.d + d
    W, H, D = (int(round(v * res)) for v in (cube.w, cube.h, cube.d))
    u, v = int(round(cube.u * res)), int(round(cube.v * res))
    X, Y, Z = x1 - x0, y1 - y0, z1 - z0
    return [
        ('top', (x0, y0, z1), (X, 0, 0), (0, 0, -Z), (0, -1, 0), u + D, v, W, D),
        ('bottom', (x0, y1, z1), (X, 0, 0), (0, 0, -Z), (0, 1, 0), u + D + W, v, W, D),
        ('right', (x0, y0, z1), (0, 0, -Z), (0, Y, 0), (-1, 0, 0), u, v + D, D, H),
        ('front', (x0, y0, z0), (X, 0, 0), (0, Y, 0), (0, 0, -1), u + D, v + D, W, H),
        ('left', (x1, y0, z0), (0, 0, Z), (0, Y, 0), (1, 0, 0), u + D + W, v + D, D, H),
        ('back', (x1, y0, z1), (-X, 0, 0), (0, Y, 0), (0, 0, 1), u + D + D + W, v + D, W, H),
    ]


class Scene:
    def __init__(self):
        self.items = []         # (cube, M, T, albedo, glow, glow_scale)

    def add_part(self, part, M, T, albedo, glow=None, pose=None, glow_scale=1.0):
        """Teil samt Kindern; pose[name] = (pitch, yaw, roll) in Rad überschreibt die Drehung des Teils."""
        rot = (pose or {}).get(part.name)
        if rot is None:
            rot = tuple(math.radians(a) for a in part.rot)
        origin = apply(M, part.pivot)
        T2 = (T[0] + origin[0], T[1] + origin[1], T[2] + origin[2])
        M2 = mat_mul(M, rot_zyx(*rot))
        for c in part.cubes:
            self.items.append((c, M2, T2, albedo, glow, glow_scale))
        for ch in part.children:
            self.add_part(ch, M2, T2, albedo, glow, pose, glow_scale)

    def view(self, yaw, pitch=0.0):
        return mat_mul(rot_zyx(math.radians(pitch), 0, 0), rot_zyx(0, math.radians(yaw), 0))

    def bounds(self, yaw, pitch=0.0):
        """Bildschirm-Hülle (x0, y0, x1, y1) in Modelleinheiten für eine Ansicht."""
        view = self.view(yaw, pitch)
        xs, ys = [], []
        for cube, M, T, *_ in self.items:
            d = getattr(cube, 'dilate', 0.0) or 0.0
            for cx in (cube.x - d, cube.x + cube.w + d):
                for cy in (cube.y - d, cube.y + cube.h + d):
                    for cz in (cube.z - d, cube.z + cube.d + d):
                        q = apply(M, (cx, cy, cz))
                        v = apply(view, (q[0] + T[0], q[1] + T[1], q[2] + T[2]))
                        xs.append(v[0])
                        ys.append(v[1])
        return min(xs), min(ys), max(xs), max(ys)

    def render(self, size_w, size_h, yaw, scale, center, night=False, ss=2, bg=((30, 34, 46), (70, 76, 92)),
               silhouette=False, pitch=0.0, raw=False, ambient=None):
        W, H = size_w * ss, size_h * ss
        sc = scale * ss
        view = self.view(yaw, pitch)
        zbuf = [1e9] * (W * H)
        img = [None] * (W * H)
        for cube, M, T, albedo, glow, gscale in self.items:
            res = albedo.res
            for name, O, U, V, N, fu, fv, tw, th in faces(cube, res):
                if tw <= 0 or th <= 0:
                    continue
                n = apply(view, apply(M, N))
                if n[2] > 1e-6:
                    continue                                           # Rückseite (Kamera bei -z)
                shade = min(1.0, 0.4 + 0.6 * (max(0.0, sum(a * b for a, b in zip(n, L0))) +
                                              max(0.0, sum(a * b for a, b in zip(n, L1)))))
                if ambient is not None:
                    shade = ambient + (1 - ambient) * shade
                o = apply(view, [T[k] + apply(M, O)[k] for k in range(3)])
                uu = apply(view, apply(M, U))
                vv = apply(view, apply(M, V))
                # Bildschirm: x = (x - cx) * sc + W/2, y = (y - cy) * sc + H/2
                ox, oy = (o[0] - center[0]) * sc + W / 2, (o[1] - center[1]) * sc + H / 2
                ux, uy, vx, vy = uu[0] * sc, uu[1] * sc, vv[0] * sc, vv[1] * sc
                det = ux * vy - uy * vx
                if abs(det) < 1e-9:
                    continue
                xs = [ox, ox + ux, ox + vx, ox + ux + vx]
                ys = [oy, oy + uy, oy + vy, oy + uy + vy]
                bx0, bx1 = max(0, int(min(xs))), min(W - 1, int(max(xs)) + 1)
                by0, by1 = max(0, int(min(ys))), min(H - 1, int(max(ys)) + 1)
                inv = 1.0 / det
                for py in range(by0, by1 + 1):
                    dy = py + 0.5 - oy
                    row = py * W
                    for px in range(bx0, bx1 + 1):
                        dx = px + 0.5 - ox
                        s = (dx * vy - dy * vx) * inv
                        if s < 0 or s >= 1:
                            continue
                        t = (ux * dy - uy * dx) * inv
                        if t < 0 or t >= 1:
                            continue
                        z = o[2] + s * uu[2] + t * vv[2]
                        k = row + px
                        if z >= zbuf[k]:
                            continue
                        ti, tj = fu + int(s * tw), fv + int(t * th)
                        c = albedo.get(ti, tj)
                        if c[3] < 128:
                            continue
                        zbuf[k] = z
                        if silhouette:
                            img[k] = (0, 0, 0)
                            continue
                        f = shade * (0.22 if night else 1.0)
                        col = [c[0] * f, c[1] * f, c[2] * f]
                        if glow is not None:
                            g = glow.get(ti, tj)
                            if g[3]:
                                a = g[3] / 255.0 * gscale
                                col = [col[q] + (g[q] - col[q]) * a for q in range(3)]
                        img[k] = tuple(min(255, int(v)) for v in col)
        if raw:
            return W, H, img
        out = []
        for y in range(size_h):
            t = y / max(size_h - 1, 1)
            bgc = (16, 16, 22) if silhouette else tuple(int(a + (b - a) * t) for a, b in zip(*bg))
            if silhouette:
                bgc = (255, 255, 255)
            for x in range(size_w):
                acc = [0, 0, 0]
                for sy in range(ss):
                    for sx in range(ss):
                        c = img[(y * ss + sy) * W + x * ss + sx]
                        c = c if c is not None else bgc
                        acc[0] += c[0]; acc[1] += c[1]; acc[2] += c[2]
                out.append(tuple(v // (ss * ss) for v in acc))
        return size_w, size_h, out


# ---------------------------------------------------------------------------------------------------------------------
# Körper, Atlanten
# ---------------------------------------------------------------------------------------------------------------------
class Box:
    def __init__(self, x, y, z, w, h, d, u, v, dilate=0.0):
        self.x, self.y, self.z, self.w, self.h, self.d = x, y, z, w, h, d
        self.u, self.v, self.dilate = u, v, dilate


_BONES = {
    'head': ((0, 0, 0), [Box(-4, -8, -4, 8, 8, 8, 0, 0), Box(-4, -8, -4, 8, 8, 8, 32, 0, 0.5)]),
    'body': ((0, 0, 0), [Box(-4, 0, -2, 8, 12, 4, 16, 16), Box(-4, 0, -2, 8, 12, 4, 16, 32, 0.25)]),
    'right_arm': ((-5, 2, 0), [Box(-3, -2, -2, 4, 12, 4, 40, 16), Box(-3, -2, -2, 4, 12, 4, 40, 32, 0.25)]),
    'left_arm': ((5, 2, 0), [Box(-1, -2, -2, 4, 12, 4, 32, 48), Box(-1, -2, -2, 4, 12, 4, 48, 48, 0.25)]),
    'right_leg': ((-1.9, 12, 0), [Box(-2, 0, -2, 4, 12, 4, 0, 16), Box(-2, 0, -2, 4, 12, 4, 0, 32, 0.25)]),
    'left_leg': ((1.9, 12, 0), [Box(-2, 0, -2, 4, 12, 4, 16, 48), Box(-2, 0, -2, 4, 12, 4, 0, 48, 0.25)]),
}

_cache = {}


def atlases():
    if 'armor' not in _cache:
        w, h, px = G.vanilla('entity/player/wide/steve.png')
        _cache['skin'] = Atlas(w, h, px, 1)
        model = A.armor()
        model.pack()
        alb, glo = model.paint()                  # nur gemalt, nicht gespeichert (ohne texkit.flush)
        _cache['armor'] = (model, Atlas(alb.w, alb.h, alb.px, GM.RES), Atlas(glo.w, glo.h, glo.px, GM.RES))
        gm = GM.gauntlets()
        gm.pack()
        ga, gg = gm.paint()
        _cache['gauntlets'] = (gm, Atlas(ga.w, ga.h, ga.px, GM.RES), Atlas(gg.w, gg.h, gg.px, GM.RES))
    return _cache


def hanging(pose):
    """Gleiche Regel wie ArmorFeature.kt (ohne Federn): vordere Tassetten folgen dem Bein nach vorn, die hintere dem
    weiter zurückgesetzten Bein; dazu eine leichte Ruheneigung."""
    rl, ll = pose.get('right_leg', (0, 0, 0))[0], pose.get('left_leg', (0, 0, 0))[0]
    out = {
        'legs_tasset_fr': (min(0.0, rl) * TASSET_FOLLOW + TASSET_REST_FRONT, 0, 0),
        'legs_tasset_fl': (min(0.0, ll) * TASSET_FOLLOW + TASSET_REST_FRONT, 0, 0),
        'legs_tasset_b': (max(0.0, rl, ll) * TASSET_FOLLOW_BACK + TASSET_REST_BACK, 0, 0),
    }
    return out


# Diese Werte stehen identisch in ArmorFeature.kt (TASSET_*).
TASSET_FOLLOW = 1.08
TASSET_FOLLOW_BACK = 1.08
TASSET_REST_FRONT = -0.04
TASSET_REST_BACK = 0.05


def figure(pose=None, armor=True, gauntlets=False, body=True, pieces=('helm', 'chest', 'legs', 'boots'),
           forearm=None, glow_scale=1.0):
    pose = pose or {}
    cache = atlases()
    sc = Scene()
    bone_mats = {}
    for bone, (pivot, boxes) in _BONES.items():
        rot = pose.get(bone, (0, 0, 0))
        M = rot_zyx(*rot)
        bone_mats[bone] = (M, pivot)
        if body:
            for b in boxes:
                sc.items.append((b, M, pivot, cache['skin'], None, 1.0))
    if armor:
        model, alb, glo = cache['armor']
        hang = hanging(pose)
        show_fore = (not gauntlets) if forearm is None else forearm
        for part in model.parts:
            if A.SLOT[part.name] not in pieces:
                continue
            if part.name.startswith('chest_forearm') and not show_fore:
                continue
            M, pivot = bone_mats[A.BONE[part.name]]
            sc.add_part(part, M, pivot, alb, glo, pose=hang, glow_scale=glow_scale)
    if gauntlets:
        gm, ga, gg = cache['gauntlets']
        for part in gm.parts:
            if part.name.endswith('_open'):
                continue
            M, pivot = bone_mats['right_arm' if part.name.startswith('r_') else 'left_arm']
            sc.add_part(part, M, pivot, ga, gg, glow_scale=0.0)
    return sc


# ---------------------------------------------------------------------------------------------------------------------
# Icons (32 x 32): aus dem Modell gerendert, 8 x 8 Abtastpunkte je Pixel, dominante Farbe, 1-px-Umriss (wie Solvane)
# ---------------------------------------------------------------------------------------------------------------------
ICON_VIEWS = {
    # Teil: (Rüstungsteile, Knochenhaltung, yaw, pitch)
    'eirholm_helmet': (('helm',), {}, 22, 12),
    'eirholm_chestplate': (('chest',), {'right_arm': (0, 0, 0.22), 'left_arm': (0, 0, -0.22)}, 18, 10),
    'eirholm_leggings': (('legs',), {'right_leg': (0, 0, 0.06), 'left_leg': (0, 0, -0.06)}, 18, 8),
    'eirholm_boots': (('boots',), {'right_leg': (0.0, 0.5, 0.0), 'left_leg': (0.0, 0.5, 0.0)}, 32, 9),
}


def icon(name, n=32, ss=8):
    pieces, pose, yaw, pitch = ICON_VIEWS[name]
    sc = figure(pose=pose, body=False, pieces=pieces, forearm=False, glow_scale=0.7)
    if name == 'eirholm_boots':
        # Stiefel näher zusammen und leicht versetzt, damit sie das Feld füllen
        sc.items = [(c, M, (T[0] * 0.55, T[1], T[2] + (1.5 if T[0] > 0 else -1.5)), a, g, k) for c, M, T, a, g, k in sc.items]
    x0, y0, x1, y1 = sc.bounds(yaw, pitch)
    span = max(x1 - x0, y1 - y0)
    scale = (n - 3) / span
    big = n * ss
    _, _, buf = sc.render(n, n, yaw, scale, ((x0 + x1) / 2, (y0 + y1) / 2), ss=ss, pitch=pitch, raw=True, ambient=0.5)
    out = G.Tex(n, n)
    for y in range(n):
        for x in range(n):
            counts, hits = {}, 0
            for sy in range(ss):
                for sx in range(ss):
                    c = buf[(y * ss + sy) * big + x * ss + sx]
                    if c is None:
                        continue
                    hits += 1
                    counts.setdefault(tuple(v // 10 * 10 for v in c), []).append(c)
            if hits * 3 < ss * ss:
                continue
            best = max(counts.values(), key=len)
            out.set(x, y, tuple(sum(cc[k] for cc in best) // len(best) for k in range(3)) + (255,))
    return G.outline(out, G.hexc('#12131a'))


def icons():
    return {name: icon(name) for name in ICON_VIEWS}


WALK = {'right_leg': (-0.62, 0, 0), 'left_leg': (0.62, 0, 0), 'right_arm': (0.5, 0, 0.05), 'left_arm': (-0.5, 0, -0.05)}
RAISE = {'right_arm': (-2.6, 0, 0.2), 'left_arm': (-1.2, -0.3, 0), 'head': (-0.35, 0.3, 0)}


def main(argv):
    os.makedirs(OUT, exist_ok=True)
    pw, ph, scale, center = 260, 360, 7.4, (0, 4.5, 0)
    views = (0, 35, 90, 180)

    def row(sc, night=False, sil=False):
        return [sc.render(pw, ph, v, scale, center, night=night, silhouette=sil) for v in views]

    main_rows = row(figure()) + row(figure(gauntlets=True))
    G.sheet(main_rows, 4, os.path.join(OUT, 'armor_figure.png'))
    print('geschrieben:', os.path.join(OUT, 'armor_figure.png'))
    if '--quick' in argv:
        return
    ic = icons()
    G.sheet([G.upscale(t, 8) for t in ic.values()], 4, os.path.join(OUT, 'armor_icons.png'))
    G.sheet(row(figure(pose=WALK, gauntlets=True)) + row(figure(pose=RAISE, gauntlets=True)),
            4, os.path.join(OUT, 'armor_motion.png'))
    G.sheet(row(figure(), night=True) + row(figure(), sil=True), 4, os.path.join(OUT, 'armor_night_silhouette.png'))
    # Einzelteile ohne Körper (was jedes Rüstungsteil allein zeigt)
    singles = [figure(body=True, pieces=(p,)).render(pw, ph, 30, scale, center) for p in ('helm', 'chest', 'legs', 'boots')]
    G.sheet(singles, 4, os.path.join(OUT, 'armor_pieces.png'))
    # Spielentfernung: klein gerendert, pixelig vergrößert
    small = [figure(gauntlets=g).render(56, 78, v, 1.6, center, ss=3) for g in (False, True) for v in (0, 35, 180)]
    big = []
    for w, h, px in small:
        k = 4
        big.append((w * k, h * k, [px[(y // k) * w + x // k] for y in range(h * k) for x in range(w * k)]))
    G.sheet(big, 6, os.path.join(OUT, 'armor_distance.png'))
    print('fertig:', OUT)


if __name__ == '__main__':
    main(sys.argv[1:])
