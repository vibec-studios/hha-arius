"""Gesamtwirkung von Nivaris als Schlangendrache ohne Spiel: setzt die Teile aus nivaris_model entlang einer Raumkurve
(wie der Renderer: jedes Glied starr auf seine Sehne, Querschnitt mit taper(i) verjüngt) und rendert perspektivisch.

Aufruf (aus dem Projektordner):
    python tools/assets/nivaris_serpent_preview.py [ausgabeordner] [--only liegend,flug,nadel_weit,...] [--width 1400]

Standard-Ausgabe: tmp/preview/nivaris_serpent/. Szenen:
    liegend          am Boden in Bögen zusammengerollt (Schwingen angelegt)
    liegend_nah      Kopf und Vorderkörper liegend, nah
    flug_nah         Kopf, Schwingen und Vorderläufe im Flug, nah
    flug             S-Kurve im Flug, Schwingen gespannt (schräg von oben)
    flug_seite       dieselbe Kurve von der Seite
    nadel_weit       gewunden um die Nadel, Blick vom Plateau (0, 12, -20) nach Süden (die Cutscene-Totale)
    nadel_schlucht   dieselbe Windung vom Schluchtrand aus, näher
    nadel_nah        nahe Kamera an den Windungen (Schuppen, Kamm, Kristalle)
    nadel_kopf       Kopf über dem Gipfel
    nadel_vorschlag  wie nadel_schlucht, aber Helix y 144..168 mit 1,5 Umläufen (füllt der Körper ganz aus)
    nadel_oben       Draufsicht auf die Windung (Prüfung: liegt der Körper frei von der Felswand?)

Gerendert wird mit Texel-Splats (2 Texel je Einheit wie die HD-Textur) und Tiefenpuffer, Licht nach Flächennormalen
(ähnlich Minecrafts Entity-Licht), dazu Dunst mit der Entfernung. Leuchttexel werden aufgehellt.
Die Nadel ist ArenaLayout.spireRadius(y) als Drehkörper; coil(s) und alle Maße stammen aus arena/ArenaLayout.kt.
"""
import math
import os
import shutil
import subprocess
import sys
import tempfile
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, '..', '..'))
if HERE not in sys.path:
    sys.path.insert(0, HERE)
try:
    sys.stdout.reconfigure(encoding='utf-8')
except Exception:
    pass

import nivaris_model as NM  # noqa: E402
from preview_model import apply, mat_mul, rot_zyx  # noqa: E402

U = NM.SCALE / 16.0                     # Blöcke je Modelleinheit
GLIED = NM.SEG * U                      # 2,4 Blöcke

# --- ArenaLayout (gleiche Werte wie arena/ArenaLayout.kt) ------------------------------------------------------------
SPIRE = (0, -40, 150)
SPIRE_TOP = 185
SPIRE_BASE_R = 38.0
AXIS = (SPIRE[0] + 0.5, SPIRE[2] + 0.5)


def spire_radius(y):
    t = min(1.0, max(0.0, (y - SPIRE[1]) / (SPIRE_TOP - SPIRE[1])))
    return max(0.0, SPIRE_BASE_R * (1.0 - t) ** 1.35)


COIL = (70.0, 168.0, 2.25)               # wie ArenaLayout.coil: y unten, y oben, Umläufe
COIL_PROPOSAL = (144.0, 168.0, 1.5)      # Vorschlag: so viel Helix trägt der 96-Block-Körper wirklich


def coil(s, spec=COIL):
    y0, y1, turns = spec
    y = y0 + (y1 - y0) * s
    r = spire_radius(y) + 2.5 + 2.2
    a = s * turns * 2 * math.pi
    return (AXIS[0] + math.sin(a) * r, y, AXIS[1] + math.cos(a) * r)


# --- Vektoren --------------------------------------------------------------------------------------------------------
def add(a, b):
    return (a[0] + b[0], a[1] + b[1], a[2] + b[2])


def sub(a, b):
    return (a[0] - b[0], a[1] - b[1], a[2] - b[2])


def mul(a, k):
    return (a[0] * k, a[1] * k, a[2] * k)


def dot(a, b):
    return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]


def cross(a, b):
    return (a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])


def norm(a):
    n = math.sqrt(dot(a, a)) or 1.0
    return (a[0] / n, a[1] / n, a[2] / n)


def lerp(a, b, t):
    return (a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t)


def smooth(e0, e1, x):
    t = min(1.0, max(0.0, (x - e0) / (e1 - e0)))
    return t * t * (3 - 2 * t)


# --- Texel-Wolken der Teile ------------------------------------------------------------------------------------------
_MODEL = NM.nivaris()
_PARTS = {p.name: p for p in _MODEL.parts}
_CACHE = {}


def part_texels(name, pose=None, lod=1):
    """Alle Texel eines Teils samt Kindern in Teil-Koordinaten: (Punkt, Normale, Farbe, leuchtet)."""
    key = (name, tuple(sorted((pose or {}).items())), lod)
    if key in _CACHE:
        return _CACHE[key]
    pose = pose or {}
    out = []

    def walk(part, rot, pos, top):
        value = pose.get(part.name, ...)
        pivot = part.pivot
        if top or value is ...:
            r = (0.0, 0.0, 0.0) if top else tuple(math.radians(a) for a in part.rot)
        else:
            r = tuple(value[:3])
        if not top and value is not ... and len(value) >= 6:
            pivot = add(pivot, value[3:6])
        o = apply(rot, pivot)
        p = add(pos, o)
        m = mat_mul(rot, rot_zyx(*r)) if not top else rot
        for c in part.cubes:
            for face, (fw, fh) in _face_dims(c).items():
                nrm = apply(m, NM.NORMAL[face])
                for j in range(0, fh, lod):
                    for i in range(0, fw, lod):
                        col, glow, _, _ = c.eval(face, i, j, game=False)
                        if col is None:
                            continue
                        lp = c.where(face, i, j, game=False)
                        wp = add(apply(m, lp), p)
                        out.append((wp, nrm, col[:3], glow[3] / 255.0 if glow else 0.0, glow[:3] if glow else None))
        for ch in part.children:
            walk(ch, m, p, False)
    walk(_PARTS[name], [[1, 0, 0], [0, 1, 0], [0, 0, 1]], (0, 0, 0), True)
    _CACHE[key] = out
    return out


def _face_dims(c):
    r = NM.RES
    W, H, D = (int(round(v * r)) for v in (c.w, c.h, c.d))
    return {'front': (W, H), 'back': (W, H), 'right': (D, H), 'left': (D, H), 'top': (W, D), 'bottom': (W, D)}


# --- Kurven ----------------------------------------------------------------------------------------------------------
class Path:
    """Polylinie Kopf -> Schwanz mit Bogenlänge; s = 0 ist das Kopfgelenk, s < 0 liegt vor dem Kopf."""

    def __init__(self, pts, s0_index, back_hint):
        self.pts = pts
        self.acc = [0.0]
        for a, b in zip(pts, pts[1:]):
            self.acc.append(self.acc[-1] + math.sqrt(dot(sub(b, a), sub(b, a))))
        self.offset = self.acc[s0_index]
        self.back_hint = back_hint

    def at(self, s):
        s += self.offset
        acc = self.acc
        if s <= 0:
            return self.pts[0]
        if s >= acc[-1]:
            return self.pts[-1]
        lo, hi = 0, len(acc) - 1
        while hi - lo > 1:
            mid = (lo + hi) // 2
            if acc[mid] <= s:
                lo = mid
            else:
                hi = mid
        t = (s - acc[lo]) / max(acc[hi] - acc[lo], 1e-9)
        return lerp(self.pts[lo], self.pts[hi], t)

    def length_behind_head(self):
        return self.acc[-1] - self.offset


def frame(path, s0, s1):
    """Gliedrahmen: Ursprung = Kurvenpunkt bei s0, +z (Modell) zur Sehne nach s1; Rücken nach back_hint."""
    p0, p1 = path.at(s0), path.at(s1)
    T = norm(sub(p1, p0))
    hint = path.back_hint(p0, s0)
    B = norm(sub(hint, mul(T, dot(hint, T))))
    L = cross(T, B)
    return p0, L, mul(B, -1), T          # Ursprung, Modell-x, Modell-y, Modell-z (Welt)


def to_world(fr, local, sx=1.0, sy=1.0):
    o, X, Y, Z = fr
    return (o[0] + (X[0] * local[0] * sx + Y[0] * local[1] * sy + Z[0] * local[2]) * U,
            o[1] + (X[1] * local[0] * sx + Y[1] * local[1] * sy + Z[1] * local[2]) * U,
            o[2] + (X[2] * local[0] * sx + Y[2] * local[1] * sy + Z[2] * local[2]) * U)


def dir_world(fr, n, sx=1.0, sy=1.0):
    o, X, Y, Z = fr
    v = (n[0] / sx, n[1] / sy, n[2])
    return norm((X[0] * v[0] + Y[0] * v[1] + Z[0] * v[2], X[1] * v[0] + Y[1] * v[1] + Z[1] * v[2],
                 X[2] * v[0] + Y[2] * v[1] + Z[2] * v[2]))


def body_instances(path, wings='fold', jaw=0.0, lod=1):
    """Liste aus (Texel, Rahmen, sx, sy) für Kopf, 40 Glieder, Läufe, Schwingen und Fächer (Renderer-Vorschlag)."""
    inst = []
    frames = []
    for i in range(NM.SEGMENTS):
        fr = frame(path, i * GLIED, (i + 1) * GLIED)
        frames.append(fr)
        tp = NM.taper(i)
        inst.append((part_texels(NM.glied_part(i), lod=lod), fr, tp, tp))
    # Kopf: Rahmen aus der Kurve vor dem Kopfgelenk (s -2,4 .. 0)
    hf = frame(path, -GLIED, 0.0)
    hf = (path.at(0.0), hf[1], hf[2], hf[3])
    inst.append((part_texels('head', {'jaw': (math.radians(jaw), 0, 0)}, lod=lod), hf, 1.0, 1.0))
    # Fächer am Ende des letzten Glieds, unverjüngt
    last = frames[-1]
    tail = (to_world(last, (0, 0, NM.SEG)), last[1], last[2], last[3])
    inst.append((part_texels('tail_fan', lod=lod), tail, 1.0, 1.0))
    # Läufe und Schwingen: Ansatzpunkt mit taper des Glieds, Teil selbst unverjüngt
    wp = NM.wing_pose(wings)
    for name, (g, at) in NM.ATTACH.items():
        fr = frames[g]
        tp = NM.taper(g)
        origin = to_world(fr, at, tp, tp)
        pose = {}
        if name.startswith('wing'):
            pose = {k: v for k, v in wp.items() if k.startswith(name + '_')}
            rot = wp[name][:3]
            m = rot_zyx(*rot)
            o, X, Y, Z = fr
            # Drehung des Wurzelteils in den Rahmen einrechnen.
            cols = [apply(m, e) for e in ((1, 0, 0), (0, 1, 0), (0, 0, 1))]
            Xn = add(add(mul(X, cols[0][0]), mul(Y, cols[0][1])), mul(Z, cols[0][2]))
            Yn = add(add(mul(X, cols[1][0]), mul(Y, cols[1][1])), mul(Z, cols[1][2]))
            Zn = add(add(mul(X, cols[2][0]), mul(Y, cols[2][1])), mul(Z, cols[2][2]))
            pfr = (origin, Xn, Yn, Zn)
        else:
            pfr = (origin, fr[1], fr[2], fr[3])
        inst.append((part_texels(name, pose, lod=lod), pfr, 1.0, 1.0))
    return inst


# --- Szenen-Kurven ---------------------------------------------------------------------------------------------------
UP = (0.0, 1.0, 0.0)


def path_lying():
    """Am Boden zusammengerollt: Kopf vorn leicht erhoben, Körper in einer weiten Spirale um ihn herum."""
    pts = []
    for k in range(40, 0, -1):                        # vor dem Kopf (für den Kopfrahmen)
        pts.append((0.0, 2.3, -k * 0.1))
    head_i = len(pts)
    for k in range(61):                               # erst gerade nach hinten, dabei sinkt der Kopf auf den Boden zu
        s = k * 0.1
        pts.append((0.0, 2.3 - 1.2 * smooth(0, 6, s), s))
    r0, cx, cz = 9.0, -9.0, 6.0                       # dann eine weite Spirale nach links herum, Radius wächst
    phi, length = 0.0, 0.0
    while length < 120.0:
        phi += 0.004
        r = r0 + 1.6 * phi
        p = (cx + math.cos(phi) * r, 1.1, cz + math.sin(phi) * r)
        length += math.sqrt(dot(sub(p, pts[-1]), sub(p, pts[-1])))
        pts.append(p)
    return Path(pts, head_i, lambda p, s: UP)


def path_flight():
    pts = []
    for k in range(-40, 1400):
        s = k * 0.1
        x = 10.0 * math.sin(2 * math.pi * s / 52.0) * smooth(-5, 12, s)
        y = 40.0 + 3.0 * math.sin(2 * math.pi * s / 70.0 + 0.6)
        pts.append((x, y, s))
    return Path(pts, 40, lambda p, s: UP)


def path_coil(spec=COIL):
    """Windung um die Nadel nach coil(s), dann steigt der Hals über den Gipfel; Kopf blickt nach Norden (-z)."""
    up = []
    n = 2400
    for k in range(n + 1):
        up.append(coil(k / n, spec))
    p0 = coil(1.0, spec)
    t0 = norm(sub(coil(1.0, spec), coil(1.0 - 1e-3, spec)))
    head = (AXIS[0] - 1.0, SPIRE_TOP + 8.0, AXIS[1] - 4.0)
    fwd = norm((0.0, -0.22, -1.0))
    k0, k1 = 22.0, 16.0
    rise = []
    m = 300
    for k in range(1, m + 1):
        t = k / m
        h00, h10, h01, h11 = 2 * t ** 3 - 3 * t ** 2 + 1, t ** 3 - 2 * t ** 2 + t, -2 * t ** 3 + 3 * t ** 2, t ** 3 - t ** 2
        rise.append(add(add(mul(p0, h00), mul(t0, h10 * k0)), add(mul(head, h01), mul(fwd, h11 * k1))))
    ahead = [add(head, mul(fwd, 0.1 * k)) for k in range(1, 41)]
    fwd_pts = up + rise + ahead                     # Schwanz -> Kopf -> davor
    pts = list(reversed(fwd_pts))
    head_i = len(ahead)

    def hint(p, s):
        radial = norm((p[0] - AXIS[0], 0.0, p[2] - AXIS[1]))
        w = smooth(6.0, 26.0, s)                    # am Kopf: Rücken nach oben, an der Wand: Rücken nach außen
        return norm(lerp(UP, radial, w))
    return Path(pts, head_i, hint)


# --- Renderer --------------------------------------------------------------------------------------------------------
SUN = norm((-0.45, 0.8, -0.35))
FILL = norm((0.6, 0.3, 0.7))


class Cam:
    def __init__(self, eye, target, fov, W, H):
        self.eye, self.W, self.H = eye, W, H
        f = norm(sub(target, eye))
        r = norm(cross(f, UP))
        u = cross(r, f)
        self.f, self.r, self.u = f, r, u
        self.focal = (W / 2) / math.tan(math.radians(fov) / 2)

    def project(self, p):
        d = sub(p, self.eye)
        z = dot(d, self.f)
        if z < 0.3:
            return None
        return (self.W / 2 + dot(d, self.r) * self.focal / z, self.H / 2 - dot(d, self.u) * self.focal / z, z)

    def ray(self, px, py):
        x = (px - self.W / 2) / self.focal
        y = -(py - self.H / 2) / self.focal
        return norm(add(add(self.f, mul(self.r, x)), mul(self.u, y)))


class Canvas:
    def __init__(self, cam, sky=((150, 170, 196), (214, 224, 236)), fog=(200, 212, 226), fog_dist=420.0):
        self.cam = cam
        W, H = cam.W, cam.H
        self.pix = [None] * (W * H)
        self.depth = [1e18] * (W * H)
        self.fog, self.fog_dist = fog, fog_dist
        for y in range(H):
            t = y / max(H - 1, 1)
            c = tuple(int(a + (b - a) * t) for a, b in zip(sky[0], sky[1]))
            for x in range(W):
                self.pix[y * W + x] = c

    def ground(self, height, color_fn, fog=True):
        cam = self.cam
        W, H = cam.W, cam.H
        for py in range(H):
            for px in range(W):
                d = cam.ray(px + 0.5, py + 0.5)
                if d[1] >= -1e-4:
                    continue
                t = (height - cam.eye[1]) / d[1]
                if t <= 0:
                    continue
                p = add(cam.eye, mul(d, t))
                z = t * dot(d, cam.f)
                i = py * W + px
                if z < self.depth[i]:
                    self.depth[i] = z
                    self.pix[i] = self.fogged(color_fn(p), t) if fog else color_fn(p)

    def fogged(self, c, dist):
        k = 1.0 - math.exp(-dist / self.fog_dist)
        return tuple(int(a + (b - a) * k) for a, b in zip(c, self.fog))

    def splat(self, p, n, col, glow, glow_rgb, size_world, light=True, cull=True):
        cam = self.cam
        to_cam = sub(cam.eye, p)
        if cull and dot(n, to_cam) < 0:
            return
        q = cam.project(p)
        if q is None:
            return
        sx, sy, z = q
        rad = size_world * cam.focal / z * 0.5
        r = max(0, int(math.ceil(rad - 0.5)))
        W, H = cam.W, cam.H
        ix, iy = int(sx), int(sy)
        if ix + r < 0 or iy + r < 0 or ix - r >= W or iy - r >= H:
            return
        if light:
            k = 0.52 + 0.5 * max(0.0, dot(n, SUN)) + 0.14 * max(0.0, dot(n, FILL))
            c = (min(255, int(col[0] * k)), min(255, int(col[1] * k)), min(255, int(col[2] * k)))
        else:
            c = col
        if glow > 0 and glow_rgb is not None:
            g = glow * 0.75
            c = tuple(min(255, int(a + (b - a) * g + 50 * g)) for a, b in zip(c, glow_rgb))
        dist = math.sqrt(dot(to_cam, to_cam))
        c = self.fogged(c, dist)
        depth, pix = self.depth, self.pix
        for yy in range(max(0, iy - r), min(H, iy + r + 1)):
            row = yy * W
            for xx in range(max(0, ix - r), min(W, ix + r + 1)):
                j = row + xx
                if z < depth[j]:
                    depth[j] = z
                    pix[j] = c

    def save(self, out):
        W, H = self.cam.W, self.cam.H
        tmp = tempfile.mktemp(suffix='.ppm')
        with open(tmp, 'wb') as f:
            f.write(f'P6 {W} {H} 255\n'.encode())
            f.write(bytes(v for c in self.pix for v in c))
        os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
        subprocess.run([shutil.which('ffmpeg'), '-hide_banner', '-loglevel', 'error', '-y', '-i', tmp, out], check=True)
        os.remove(tmp)
        print('  ->', out)


def draw_body(cv, inst, lod):
    tex = 0.5 * lod                                  # Texelkante in Einheiten
    for texels, fr, sx, sy in inst:
        size = tex * U * max(sx, sy) * 1.45
        for lp, n, col, glow, grgb in texels:
            wp = to_world(fr, lp, sx, sy)
            wn = dir_world(fr, n, sx, sy)
            cv.splat(wp, wn, col, glow, grgb, size)


def draw_spire(cv, y_from=-40.0, step=0.7):
    """Die Nadel als Punktwolke: kaltes, kantiges Gletschereis mit senkrechten Rinnen und Schnee auf den Kanten."""
    y = y_from
    while y < SPIRE_TOP:
        r = spire_radius(y)
        if r < 0.05:
            break
        dr = (spire_radius(y + 0.5) - spire_radius(y - 0.5))
        n_around = max(6, int(2 * math.pi * r / step))
        for k in range(n_around):
            a = 2 * math.pi * (k + 0.5 * (int(y / step) % 2)) / n_around
            flute = math.sin(a * 13 + y * 0.05) * 0.5 + math.sin(a * 29 - y * 0.11) * 0.25
            rr = r * (1 + 0.035 * flute)
            p = (AXIS[0] + math.sin(a) * rr, y, AXIS[1] + math.cos(a) * rr)
            n = norm((math.sin(a) + 0.35 * math.cos(a * 13) * 0.4, -dr, math.cos(a) - 0.35 * math.sin(a * 13) * 0.4))
            v = 0.5 + 0.3 * flute
            dark, light = (70, 96, 130), (150, 178, 206)
            col = tuple(int(d + (l - d) * v) for d, l in zip(dark, light))
            if math.sin(a * 13 + y * 0.05) > 0.8:
                col = tuple(int(c + (232 - c) * 0.7) for c in col)      # Schnee in den Rinnenkanten
            cv.splat(p, n, col, 0.0, None, step * 1.5, cull=True)
        y += step * 0.9


def snow_ground(p):
    v = 0.92 + 0.06 * math.sin(p[0] * 0.21) * math.sin(p[2] * 0.17)
    return tuple(int(c * v) for c in (226, 232, 240))


def canyon_floor(p):
    v = 0.8 + 0.1 * math.sin(p[0] * 0.05) * math.sin(p[2] * 0.07)
    return tuple(int(c * v) for c in (150, 166, 186))


def clearance(path):
    """Kleinster Abstand der Körperachse zur Felswand (Blöcke) und Glieder, deren Rumpf in den Fels ragt."""
    worst, inside = 1e9, []
    for i in range(NM.SEGMENTS):
        for f in (0.0, 0.5, 1.0):
            p = path.at((i + f) * GLIED)
            d = math.hypot(p[0] - AXIS[0], p[2] - AXIS[1]) - spire_radius(p[1])
            body_r = 7 * NM.taper(i) * U
            gap = d - body_r
            worst = min(worst, gap)
            if gap < 0 and p[1] < SPIRE_TOP:
                inside.append(i)
    return worst, sorted(set(inside))


SCENES = {}


def scene(fn):
    SCENES[fn.__name__] = fn
    return fn


@scene
def liegend(W, out):
    path = path_lying()
    cam = Cam((-38, 26, 34), (-8, 1, 6), 46, W, int(W * 0.62))
    cv = Canvas(cam, fog_dist=600)
    cv.ground(0.0, snow_ground)
    draw_body(cv, body_instances(path, 'fold', jaw=8, lod=1), 1)
    cv.save(out)


@scene
def flug(W, out):
    path = path_flight()
    cam = Cam((-62, 78, -40), (0, 38, 40), 50, W, int(W * 0.62))
    cv = Canvas(cam, fog_dist=500)
    cv.ground(0.0, snow_ground)
    draw_body(cv, body_instances(path, 'spread', lod=1), 1)
    cv.save(out)


@scene
def flug_nah(W, out):
    path = path_flight()
    head = path.at(0.0)
    cam = Cam(add(head, (-16, 9, -9)), add(head, (0, -1, 9)), 55, W, int(W * 0.62))
    cv = Canvas(cam, fog_dist=500)
    draw_body(cv, body_instances(path, 'spread', jaw=14, lod=1), 1)
    cv.save(out)


@scene
def liegend_nah(W, out):
    path = path_lying()
    head = path.at(0.0)
    cam = Cam(add(head, (-9, 5, -10)), add(head, (1, -0.5, 6)), 55, W, int(W * 0.62))
    cv = Canvas(cam, fog_dist=600)
    cv.ground(0.0, snow_ground)
    draw_body(cv, body_instances(path, 'fold', jaw=8, lod=1), 1)
    cv.save(out)


@scene
def flug_seite(W, out):
    path = path_flight()
    cam = Cam((-95, 44, 40), (0, 40, 42), 55, W, int(W * 0.5))
    cv = Canvas(cam, fog_dist=500)
    draw_body(cv, body_instances(path, 'spread', lod=1), 1)
    cv.save(out)


def coil_canvas(cam, fog_dist=650):
    cv = Canvas(cam, sky=((122, 142, 170), (206, 216, 230)), fog=(196, 208, 222), fog_dist=fog_dist)
    cv.ground(-70.0, canyon_floor)
    return cv


@scene
def nadel_weit(W, out):
    path = path_coil()
    cam = Cam((0.5, 12, -20), (0.5, 128, 150), 44, W, int(W * 0.62))
    cv = coil_canvas(cam)
    draw_spire(cv, step=0.9)
    draw_body(cv, body_instances(path, 'fold', jaw=20, lod=2), 2)
    cv.save(out)


@scene
def nadel_schlucht(W, out):
    path = path_coil()
    cam = Cam((-40, 70, 64), (0.5, 150, 150), 50, W, int(W * 0.62))
    cv = coil_canvas(cam)
    draw_spire(cv, y_from=0, step=0.6)
    draw_body(cv, body_instances(path, 'fold', jaw=20, lod=1), 1)
    cv.save(out)


@scene
def nadel_nah(W, out):
    path = path_coil()
    cam = Cam((-30, 150, 128), (2, 154, 150), 50, W, int(W * 0.62))
    cv = coil_canvas(cam, fog_dist=900)
    draw_spire(cv, y_from=110, step=0.35)
    draw_body(cv, body_instances(path, 'fold', jaw=20, lod=1), 1)
    cv.save(out)


@scene
def nadel_kopf(W, out):
    path = path_coil()
    head = path.at(0.0)
    cam = Cam(add(head, (-9, 1.5, -11)), add(head, (0.5, -2.5, 3)), 55, W, int(W * 0.62))
    cv = coil_canvas(cam, fog_dist=1400)
    draw_spire(cv, y_from=150, step=0.25)
    draw_body(cv, body_instances(path, 'fold', jaw=26, lod=1), 1)
    cv.save(out)


@scene
def nadel_vorschlag(W, out):
    """Wie nadel_schlucht, aber mit der vorgeschlagenen Helix (y 144..168, 1,5 Umläufe), die der Körper ganz füllt."""
    path = path_coil(COIL_PROPOSAL)
    cam = Cam((-40, 70, 64), (0.5, 150, 150), 50, W, int(W * 0.62))
    cv = coil_canvas(cam)
    draw_spire(cv, y_from=0, step=0.6)
    draw_body(cv, body_instances(path, 'fold', jaw=20, lod=1), 1)
    cv.save(out)
    worst, inside = clearance(path)
    print(f'  Vorschlag: Abstand zur Felswand mindestens {worst:.2f} Blöcke; Glieder im Fels: {inside or "keine"}; '
          f'Kurve hinter dem Kopf {path.length_behind_head():.1f} Blöcke')


@scene
def nadel_oben(W, out):
    path = path_coil()
    cam = Cam((AXIS[0] + 0.01, 300, AXIS[1] - 0.01), (AXIS[0], 120, AXIS[1]), 34, W, W)
    cv = coil_canvas(cam, fog_dist=5000)
    draw_spire(cv, y_from=40, step=0.7)
    draw_body(cv, body_instances(path, 'fold', lod=2), 2)
    cv.save(out)
    worst, inside = clearance(path)
    print(f'  Abstand Körperoberfläche zur Felswand: mindestens {worst:.2f} Blöcke; Glieder im Fels: {inside or "keine"}')
    print(f'  Kurvenlänge hinter dem Kopf: {path.length_behind_head():.1f} Blöcke (Körper {NM.SEGMENTS * GLIED:.0f})')


def main(argv):
    out_dir = argv[0] if argv and not argv[0].startswith('--') else os.path.join(ROOT, 'tmp', 'preview', 'nivaris_serpent')
    W = int(argv[argv.index('--width') + 1]) if '--width' in argv else 1200
    names = argv[argv.index('--only') + 1].split(',') if '--only' in argv else list(SCENES)
    for n in names:
        t = time.time()
        print(n)
        SCENES[n](W, os.path.join(out_dir, f'{n}.png'))
        print(f'  {time.time() - t:.1f} s')


if __name__ == '__main__':
    main(sys.argv[1:])
