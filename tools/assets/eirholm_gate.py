"""Generate the Rime Gate stone (four weathered variants), the frozen-veil portal sheet and the layered panel models."""
import json
import math
import random
from pathlib import Path

from PIL import Image

ASSETS = Path(__file__).resolve().parents[2] / "src/main/resources/assets/hha_arius"
BLOCKS = ASSETS / "textures/block"


def clamp(v: float, lo: int = 0, hi: int = 255) -> int:
    return max(lo, min(hi, round(v)))


def smooth(x: float) -> float:
    x = max(0.0, min(1.0, x))
    return x * x * (3 - 2 * x)


def value_noise(size: int, cells: int, rng: random.Random):
    """Seamless value noise with `cells` lattice cells across the tile."""
    lattice = [[rng.random() for _ in range(cells)] for _ in range(cells)]
    out = [[0.0] * size for _ in range(size)]
    for y in range(size):
        fy = y / size * cells
        y0 = int(fy) % cells
        y1 = (y0 + 1) % cells
        ty = smooth(fy - int(fy))
        for x in range(size):
            fx = x / size * cells
            x0 = int(fx) % cells
            x1 = (x0 + 1) % cells
            tx = smooth(fx - int(fx))
            a = lattice[y0][x0] + (lattice[y0][x1] - lattice[y0][x0]) * tx
            b = lattice[y1][x0] + (lattice[y1][x1] - lattice[y1][x0]) * tx
            out[y][x] = a + (b - a) * ty
    return out


def seg_dist(px, py, ax, ay, bx, by):
    vx, vy = bx - ax, by - ay
    l2 = vx * vx + vy * vy
    t = 0.0 if l2 < 1e-6 else max(0.0, min(1.0, ((px - ax) * vx + (py - ay) * vy) / l2))
    return math.hypot(px - (ax + vx * t), py - (ay + vy * t))


def crack(rng: random.Random, size: int):
    """A thin hairline fracture that wanders across part of the stone."""
    x, y = rng.uniform(4, size - 4), rng.uniform(4, size - 4)
    ang = rng.uniform(0, math.tau)
    pts = [(x, y)]
    for _ in range(rng.randint(3, 5)):
        ang += rng.uniform(-0.8, 0.8)
        step = rng.uniform(3.0, 6.0)
        x = max(3.0, min(size - 3.0, x + math.cos(ang) * step))
        y = max(3.0, min(size - 3.0, y + math.sin(ang) * step))
        pts.append((x, y))
    return list(zip(pts, pts[1:]))


def frame_stone(variant: int):
    """Dark glacial basalt: one hewn block per cell, chipped edges, frost settled on the top and a faint ice fracture."""
    size = 32
    rng = random.Random(4100 + variant * 37)
    coarse = value_noise(size, 4, rng)
    fine = value_noise(size, 11, rng)
    grain = [[rng.gauss(0, 1.0) for _ in range(size)] for _ in range(size)]
    segs = crack(rng, size) + (crack(rng, size) if variant % 2 == 0 else [])
    chips = [(rng.randrange(size), rng.choice((0, 1, size - 2, size - 1))) for _ in range(5)]
    chips += [(rng.choice((0, 1, size - 2, size - 1)), rng.randrange(size)) for _ in range(5)]
    im = Image.new("RGB", (size, size))
    for y in range(size):
        for x in range(size):
            edge = min(x, y, size - 1 - x, size - 1 - y)
            tone = 34 + 20 * (coarse[y][x] - 0.5) + 13 * (fine[y][x] - 0.5) + grain[y][x] * 1.6
            # Hewn edge: a dark joint, then a soft lit bevel on the upper left.
            if edge == 0:
                tone -= 15
            elif edge == 1:
                tone += 7 if (x <= y and x < size / 2) or y < size / 2 else -5
            if any(abs(x - cx) + abs(y - cy) <= 1 for cx, cy in chips):
                tone -= 10
            # Frost gathers on the upper part of the stone and in its joints.
            frost = smooth((6.5 - y + 5 * (fine[y][x] - 0.5)) / 6.5) * 0.5
            frost += smooth((2.4 - edge) / 2.4) * 0.14
            frost *= 0.45 + coarse[y][x]
            d = min((seg_dist(x, y, a[0], a[1], b[0], b[1]) for a, b in segs), default=9.0)
            core = math.exp(-((d / 0.62) ** 2))
            halo = math.exp(-((d / 2.1) ** 2)) * 0.32
            r = tone * 0.92 + frost * 92 + halo * 16 + core * 62
            g = tone * 1.08 + frost * 112 + halo * 44 + core * 118
            b = tone * 1.34 + frost * 126 + halo * 62 + core * 142
            im.putpixel((x, y), (clamp(r), clamp(g), clamp(b)))
    return im


def veil():
    """A frozen veil: slow pale filaments over a deep night-blue void, seamless in space and time (2x2 blocks)."""
    size, frames = 64, 24
    sheet = Image.new("RGBA", (size, size * frames))
    rng = random.Random(211)
    motes = [(rng.uniform(0, size), rng.uniform(0, size), rng.uniform(0, math.tau), rng.choice((1, 1, 2)), rng.uniform(0.5, 1.0)) for _ in range(16)]
    for frame in range(frames):
        t = math.tau * frame / frames
        buf = [[None] * size for _ in range(size)]
        for y in range(size):
            v = math.tau * y / size
            for x in range(size):
                u = math.tau * x / size
                # Domain warp (integer harmonics keep the tile seamless).
                wu = u + 0.75 * math.sin(v + t) + 0.32 * math.sin(2 * v - u + t)
                wv = v + 0.55 * math.cos(u - t) + 0.26 * math.sin(2 * u + v - t)
                # Ridged filaments: thin bright lines where the wave crosses zero.
                a = 1 - abs(math.sin(wu + 0.5 * math.sin(wv)))
                b = 1 - abs(math.sin(2 * wu - wv + t))
                c = 1 - abs(math.sin(wv + 2 * wu - t))
                thread = a ** 14 * 0.95 + b ** 22 * 0.6 + c ** 30 * 0.4
                body = (0.5 + 0.5 * math.sin(wu - wv + t)) ** 2
                depth = 0.5 + 0.5 * math.sin(wv + math.cos(wu))
                r = 7 + 12 * depth + 20 * body + 200 * thread
                g = 15 + 26 * depth + 62 * body + 222 * thread
                bl = 40 + 44 * depth + 84 * body + 214 * thread
                al = 214 + 16 * body + 25 * min(1.0, thread * 1.6)
                buf[y][x] = [r, g, bl, al]
        for mx, my, phase, speed, bright in motes:
            cy = (my - frame * speed * (size / frames)) % size
            cx = (mx + 1.4 * math.sin(t + phase)) % size
            glint = (0.5 + 0.5 * math.sin(t * 2 + phase)) ** 2 * bright
            if glint < 0.1:
                continue
            ix, iy = int(cx) % size, int(cy) % size
            for oy in (-1, 0, 1):
                for ox in (-1, 0, 1):
                    w = 1.0 if ox == 0 and oy == 0 else (0.28 if ox == 0 or oy == 0 else 0.0)
                    if not w:
                        continue
                    cell = buf[(iy + oy) % size][(ix + ox) % size]
                    cell[0] += 200 * glint * w
                    cell[1] += 232 * glint * w
                    cell[2] += 245 * glint * w
                    cell[3] = min(255, cell[3] + 40 * glint * w)
        im = Image.new("RGBA", (size, size))
        for y in range(size):
            for x in range(size):
                c = buf[y][x]
                im.putpixel((x, y), (clamp(c[0]), clamp(c[1]), clamp(c[2]), clamp(c[3])))
        sheet.paste(im, (0, frame * size))
    sheet.save(BLOCKS / "eirholm_gate.png")


def panels():
    """Two offset layers per cell give the veil parallax from every angle."""
    variants = {}
    for tile in range(5):
        name = "eirholm_gate" if tile == 4 else f"eirholm_gate_{tile}"
        if tile < 4:
            u = 8 * (tile % 2)
            v = 8 * (1 - tile // 2)
            model = {
                "parent": "hha_arius:block/eirholm_gate",
                "elements": [
                    {
                        "from": [0, 0, 7.65], "to": [16, 16, 8.35], "shade": False,
                        "faces": {
                            "north": {"uv": [u + 8, v, u, v + 8], "texture": "#portal"},
                            "south": {"uv": [u, v, u + 8, v + 8], "texture": "#portal"},
                        },
                    },
                    {
                        "from": [0, 0, 6.8], "to": [16, 16, 9.2], "shade": False,
                        "faces": {
                            "north": {"uv": [u, v, u + 8, v + 8], "texture": "#portal"},
                            "south": {"uv": [u + 8, v, u, v + 8], "texture": "#portal"},
                        },
                    },
                ],
            }
            (ASSETS / f"models/block/{name}.json").write_text(json.dumps(model, indent=2) + "\n")
        for axis in ("x", "z"):
            entry = {"model": f"hha_arius:block/{name}"}
            if axis == "z":
                entry["y"] = 90
            variants[f"axis={axis},panel={tile}"] = entry
    (ASSETS / "blockstates/eirholm_gate.json").write_text(json.dumps({"variants": variants}, indent=2) + "\n")


def stones():
    names = ["rime_gate_stone"] + [f"rime_gate_stone_{i}" for i in range(1, 4)]
    for i, name in enumerate(names):
        frame_stone(i).save(BLOCKS / f"{name}.png")
        if i:
            (ASSETS / f"models/block/{name}.json").write_text(json.dumps({"parent": "minecraft:block/cube_all", "textures": {"all": f"hha_arius:block/{name}"}}, indent=2) + "\n")
    state = {"variants": {"": [{"model": f"hha_arius:block/{n}"} for n in names]}}
    (ASSETS / "blockstates/rime_gate_stone.json").write_text(json.dumps(state, indent=2) + "\n")


if __name__ == "__main__":
    stones()
    veil()
    panels()
    print("ok")
