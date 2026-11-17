"""Simuliert ohne Spiel, wie Solvanes Körper im Kampf liegt (Server-Flug in NivarisMoves.flyTo + Client-Spur in
NivarisBody): Abheben, zwei gelenkte Anflüge, Sturzflug, Landung im Bogen, liegen. Schreibt Draufsicht und Seitenansicht
je Zeitpunkt als PNG nach tmp/preview/dragon_body/ (Aufruf: PYTHONPATH=tmp/pylib python tools/assets/dragon_body_sim.py).
"""
import math
import os
import struct
import zlib

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, '..', '..', 'tmp', 'preview', 'dragon_body')
SEG = 2.4
SEGMENTS = 40
POINTS = SEGMENTS + 1
SPINE_UP = 1.8
FLOOR = 0.0
ARENA_R = 38.0


def norm(v):
    n = np.linalg.norm(v)
    return v / n if n > 1e-9 else v


def turn_toward(a, b, max_angle):
    dot = float(np.clip(np.dot(a, b), -1, 1))
    ang = math.acos(dot)
    if ang <= max_angle or ang < 1e-6:
        return b
    hint = norm(np.array([-a[2], 0, a[0]])) if dot < -0.999 else norm(b - a * dot)
    return norm(a * math.cos(max_angle) + hint * math.sin(max_angle))


class Dragon:
    def __init__(self):
        self.pos = np.array([0.0, FLOOR, 0.0])
        self.facing = np.array([0.0, 0.0, 1.0])
        self.vel = None
        self.flying = False
        self.frames = []  # (pos, flying)

    def place(self, p):
        r = math.hypot(p[0], p[2])
        if r > ARENA_R - 2:
            p = np.array([p[0] * (ARENA_R - 2) / r, p[1], p[2] * (ARENA_R - 2) / r])
        self.pos = p
        self.frames.append((p.copy(), self.flying))

    def fly_to(self, to, speed, max_ticks):
        self.flying = True
        v = self.vel if self.vel is not None else self.facing * speed * 0.6
        n = 0
        arrived = False
        ceil = max(self.pos[1], to[1]) + 3
        floor = min(self.pos[1], to[1], FLOOR + 1)
        while n < max_ticks:
            n += 1
            delta = to - self.pos
            ln = np.linalg.norm(delta)
            vl = np.linalg.norm(v)
            sp = vl + float(np.clip(speed - vl, -0.08, 0.06))
            if ln <= max(sp, 0.3) * 1.1:
                self.place(to.copy())
                arrived = True
                break
            want = delta / ln
            left = max(max_ticks - n + 1, 1)
            urg = max(0.0, 1 - ln / (sp * 14)) + max(0.0, 1 - left / 8)
            d = turn_toward(norm(v), want, math.radians(6 + 34 * min(urg, 1)))
            v = d * max(sp, ln / left)
            nxt = self.pos + v
            if nxt[1] > ceil or nxt[1] < floor:
                nxt[1] = min(max(nxt[1], floor), ceil)
                v = np.array([v[0], nxt[1] - self.pos[1], v[2]])
            self.place(nxt)
            h = np.array([v[0], 0, v[2]])
            if np.linalg.norm(h) > 1e-6:
                self.facing = norm(h)
        if not arrived:
            self.place(to.copy())
        self.vel = v

    def wait(self, n):
        for _ in range(n):
            self.place(self.pos.copy())


def run_fight():
    d = Dragon()
    d.wait(20)
    # Abheben vorwärts steigend
    d.flying = True
    d.vel = d.facing * 0.35 + np.array([0, 0.25, 0])
    d.fly_to(np.array([0, 7.0, 14.0]), 0.6, 30)
    # Atemangriff: Anflug quer
    d.fly_to(np.array([20, 8.0, -10]), 0.9, 40)
    d.wait(30)
    # Sturz: Abstand gewinnen, dann auf den Spieler
    d.fly_to(np.array([-16, 8.0, -18]), 0.9, 30)
    d.wait(14)
    d.fly_to(np.array([4, 0.0, 6]), 3.0, 8)
    d.flying = False
    d.wait(20)
    # erneut abheben, Landung im Bogen
    d.flying = True
    d.vel = d.facing * 0.35 + np.array([0, 0.25, 0])
    d.fly_to(np.array([10, 7.0, 20]), 0.6, 30)
    spot = np.array([-8, 0.0, -6])
    frm = norm(np.array([d.pos[0] - spot[0], 0, d.pos[2] - spot[2]]))
    approach = spot + np.array([0, 3, 0]) + frm * 9
    d.fly_to(approach, max(0.7, np.linalg.norm(approach - d.pos) / 20), 20)
    start = d.pos.copy()
    for i in range(1, 11):
        k = i / 10
        d.place(start + (spot - start) * k + np.array([0, math.sin(k * math.pi) * 0.8, 0]))
    d.flying = False
    d.vel = None
    d.wait(80)
    return d.frames


def body_frames(frames):
    """Client: Spur der Rumpfmitte alle 0,5 Blöcke, am Boden absinken, abtasten, glätten, über dem Boden halten."""
    trail = []
    ground = 0.0
    out = []
    face = np.array([0, 0, 1.0])
    for k, (p, flying) in enumerate(frames):
        spine = p + np.array([0, SPINE_UP, 0])
        if not trail:
            side = np.array([face[2], 0, -face[0]])
            trail = [spine - face * (j * 2) + side * math.sin(j * 0.15) * 2 for j in range(1, 61)]
        if np.linalg.norm(spine - trail[0]) >= 0.5:
            trail.insert(0, spine)
        # Länge kürzen
        ln = 0
        for j in range(1, len(trail)):
            ln += np.linalg.norm(trail[j] - trail[j - 1])
            if ln > SEG * POINTS + 40:
                trail = trail[:j + 1]
                break
        target = 0.0 if flying else 1.0
        ground = min(target, ground + 1 / 34) if ground < target else max(target, ground - 1 / 34)
        if ground > 0.01:
            rest = FLOOR + SPINE_UP * 0.55
            for j in range(7, len(trail)):
                if trail[j][1] > rest:
                    trail[j] = np.array([trail[j][0], max(rest, trail[j][1] - 0.35 * ground), trail[j][2]])
        pts = sample([spine] + trail)
        for _ in range(3):
            for i in range(3, POINTS - 1):
                pts[i] = pts[i] * 0.5 + (pts[i - 1] + pts[i + 1]) * 0.25
        pts = sample(pts)
        for i in range(POINTS):
            r = taper(i) * 7 * 2.4 / 16 * 0.9
            if pts[i][1] < FLOOR + r:
                pts[i] = np.array([pts[i][0], FLOOR + r, pts[i][2]])
        out.append(pts)
    return out


def taper(i):
    if i < 5:
        return 1.0
    return 1.12 - 0.8 * ((i - 5) / (SEGMENTS - 1 - 5)) ** 1.25


def sample(poly):
    poly = [np.asarray(q, dtype=float) for q in poly]
    out = []
    acc = 0.0
    seg = 0
    for i in range(POINTS):
        target = i * SEG
        done = False
        while seg < len(poly) - 1:
            ln = np.linalg.norm(poly[seg + 1] - poly[seg])
            if acc + ln >= target and ln > 1e-9:
                out.append(poly[seg] + (poly[seg + 1] - poly[seg]) * ((target - acc) / ln))
                done = True
                break
            acc += ln
            seg += 1
        if not done:
            last = poly[-1]
            d = norm(poly[-1] - poly[-2]) if len(poly) >= 2 else np.array([0, 0, 1.0])
            out.append(last + d * (target - acc))
    return out


def png(path, img):
    h, w, _ = img.shape
    raw = b''.join(b'\x00' + img[y].tobytes() for y in range(h))
    def chunk(t, d):
        return struct.pack('>I', len(d)) + t + d + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)
    with open(path, 'wb') as f:
        f.write(b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 2, 0, 0, 0)) +
                chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b''))


def draw(pts, path):
    W, H = 640, 400
    img = np.full((H, W, 3), 24, dtype=np.uint8)
    # links Draufsicht (x/z), rechts Seitenansicht (x/y), Boden als Linie
    def top(p):
        return int(160 + p[0] * 3.6), int(200 + p[2] * 3.6)
    def side(p):
        return int(480 + p[0] * 3.6), int(330 - p[1] * 9)
    img[330, 320:] = (90, 120, 160)
    for y in range(H):
        img[y, 320] = (60, 60, 60)
    for i in range(POINTS - 1):
        r = taper(i) * 7 * 2.4 / 16
        for t in np.linspace(0, 1, 12):
            q = pts[i] + (pts[i + 1] - pts[i]) * t
            for fn, rr in ((top, r * 3.6), (side, r * 6)):
                cx, cy = fn(q)
                col = (235, 240, 250) if i else (255, 210, 120)
                rad = max(1, int(rr))
                y0, y1 = max(0, cy - rad), min(H, cy + rad + 1)
                x0, x1 = max(0, cx - rad), min(W, cx + rad + 1)
                if fn is top:
                    x1 = min(x1, 319)
                else:
                    x0 = max(x0, 321)
                if x0 < x1 and y0 < y1:
                    img[y0:y1, x0:x1] = col
    png(path, img)


def main():
    os.makedirs(OUT, exist_ok=True)
    frames = run_fight()
    bodies = body_frames(frames)
    picks = [40, 90, 140, 190, 230, 260, 300, 340, len(bodies) - 1]
    names = []
    for k in picks:
        k = min(k, len(bodies) - 1)
        name = os.path.join(OUT, 'body_%03d.png' % k)
        draw(bodies[k], name)
        names.append(name)
        # größter Knick zwischen Gliedern (Grad)
        b = bodies[k]
        worst = 0
        for i in range(2, POINTS - 1):
            a = norm(b[i] - b[i - 1]); c = norm(b[i + 1] - b[i])
            worst = max(worst, math.degrees(math.acos(float(np.clip(np.dot(a, c), -1, 1)))))
        print('Tick %3d  fliegt=%s  Kopf y=%.1f  größter Knick %.0f°' % (k, frames[k][1], frames[k][0][1], worst))
    print('\n'.join(names))


if __name__ == '__main__':
    main()
