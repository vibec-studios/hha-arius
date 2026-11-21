"""Generate the carved frost-veined gate stone, luminous aurora portal sheet, and layered portal panel models."""
import json
import math
import random
from pathlib import Path

from PIL import Image

ASSETS = Path(__file__).resolve().parents[2] / "src/main/resources/assets/hha_arius"
BLOCKS = ASSETS / "textures/block"


def clamp(v: float, lo: int = 0, hi: int = 255) -> int:
    return max(lo, min(hi, round(v)))


def frame_stone():
    """Carved alpine dark slate ashlar with frosted bevels and inlaid glowing glacier-cyan crystal veins."""
    rng = random.Random(71)
    im = Image.new("RGB", (32, 32))
    noise = [[rng.gauss(0, 2.6) for _ in range(32)] for _ in range(32)]

    # Precompute smooth frost-crystal vein intensity along two organic rune-like fractures.
    vein_pts = [
        (3.0, 9.0), (6.0, 8.2), (9.5, 6.5), (13.0, 5.8), (16.5, 7.2),
        (17.0, 25.0), (20.5, 24.0), (24.0, 22.0), (27.5, 21.5), (29.5, 23.0),
    ]

    def seg_dist(px, py, ax, ay, bx, by):
        vx, vy = bx - ax, by - ay
        l2 = vx * vx + vy * vy
        t = 0.0 if l2 < 1e-6 else max(0.0, min(1.0, ((px - ax) * vx + (py - ay) * vy) / l2))
        return math.hypot(px - (ax + vx * t), py - (ay + vy * t))

    segs = [(vein_pts[i], vein_pts[i + 1]) for i in (0, 1, 2, 3, 5, 6, 7, 8)]

    for y in range(32):
        row, dy = divmod(y, 16)
        for x in range(32):
            dx = (x + 11 * row) % 32
            # Layered slate strata + fine stone grain.
            strata = 4.2 * math.sin((x * 0.28 + y * 0.62) + 0.7 * math.sin(x * 0.2))
            grain = noise[y][x] + strata
            # Deep carved mortar joints & frosted top bevel.
            bevel = 0.0
            frost = 0.0
            if dx == 0 or dy == 0:
                bevel = -26.0
            elif dx == 31 or dy == 15:
                bevel = -12.0
            elif dy == 1:
                bevel = 16.0
                frost = 0.45
            elif dx == 1:
                bevel = 9.0
                frost = 0.15
            elif dy == 2 and dx in range(2, 30):
                frost = 0.14 * (0.5 + 0.5 * math.sin(x * 0.7 + row * 2.1))
            # Subtle inset border groove inside each ashlar block.
            if (dx in (3, 28) and 3 <= dy <= 12) or (dy in (3, 12) and 3 <= dx <= 28):
                bevel -= 6.0

            d_vein = min(seg_dist(x, y, a[0], a[1], b[0], b[1]) for a, b in segs)
            vein_core = math.exp(-(d_vein / 0.72) ** 2)
            vein_glow = math.exp(-(d_vein / 2.2) ** 2) * 0.38

            r = 40.0 + grain * 0.85 + bevel + frost * 58.0 + vein_glow * 30.0 + vein_core * 88.0
            g = 53.0 + grain * 0.95 + bevel + frost * 74.0 + vein_glow * 78.0 + vein_core * 158.0
            b = 70.0 + grain * 1.05 + bevel + frost * 88.0 + vein_glow * 105.0 + vein_core * 172.0
            im.putpixel((x, y), (clamp(r), clamp(g), clamp(b)))
    im.save(BLOCKS / "rime_gate_stone.png")


def aurora():
    """Seamless 64x64x32 animated aurora rift with emerald/glacier/violet ribbons, crystalline shimmer, and rising motes."""
    size, frames = 64, 32
    sheet = Image.new("RGBA", (size, size * frames))
    rng = random.Random(103)
    # Rising frost-star motes that loop seamlessly in Y and pulse smoothly in phase.
    motes = [
        (rng.uniform(0, size), rng.uniform(0, size), rng.uniform(0, math.tau),
         rng.choice((1, 1, 2)), rng.uniform(0.55, 1.0), rng.choice((0, 1)))
        for _ in range(34)
    ]

    for frame in range(frames):
        t = math.tau * frame / frames
        im = Image.new("RGBA", (size, size))
        buf = [[None] * size for _ in range(size)]
        for y in range(size):
            v = math.tau * y / size
            for x in range(size):
                u = math.tau * x / size
                # Multi-harmonic flowing aurora curtains (seamless in u, v, and t).
                warp1 = 0.62 * math.sin(v - t) + 0.34 * math.cos(u + v + t)
                warp2 = 0.48 * math.sin(2.0 * v + t) + 0.26 * math.sin(u - 2.0 * v - t)
                swirl = u + warp1

                curtain_a = ((1.0 + math.cos(swirl * 2.0 + 0.55 * math.sin(v + t))) * 0.5) ** 3.6
                curtain_b = ((1.0 + math.cos((u - warp2) * 3.0 + math.sin(v - t))) * 0.5) ** 4.8
                filament = ((1.0 + math.cos(swirl * 4.0 + 1.15 * math.sin(v - t))) * 0.5) ** 18.0
                shimmer = ((1.0 + math.sin(u * 3.0 - v * 2.0 + t)) * 0.5) ** 6.0 * curtain_a
                depth = 0.5 + 0.5 * math.sin(v + 1.15 * math.cos(swirl + t))

                # Color palette: deep midnight abyss -> aurora emerald-cyan -> glacier ice -> arcane violet accent -> white-gold core.
                r = 9.0 + 16.0 * depth + 24.0 * curtain_a + 78.0 * curtain_b + 132.0 * filament + 65.0 * shimmer
                g = 18.0 + 34.0 * depth + 148.0 * curtain_a + 64.0 * curtain_b + 158.0 * filament + 95.0 * shimmer
                b = 48.0 + 52.0 * depth + 136.0 * curtain_a + 142.0 * curtain_b + 148.0 * filament + 90.0 * shimmer
                a = 196.0 + 42.0 * curtain_a + 24.0 * curtain_b + 30.0 * filament
                buf[y][x] = [r, g, b, a]

        # Add soft twinkling crystalline frost motes (cross-bloom so they look luminous, not harsh dots).
        for mx, my, phase, speed, bright, kind in motes:
            cy = (my - frame * speed * (size / frames)) % size
            cx = (mx + 1.8 * math.sin(t + phase)) % size
            glint = ((0.5 + 0.5 * math.sin(t * 2.0 + phase)) ** 1.6) * bright
            if glint < 0.08:
                continue
            ix, iy = int(round(cx)) % size, int(round(cy)) % size
            # 0 = ice-cyan mote, 1 = pale gold-white starlight mote
            cr, cg, cb = (155.0, 235.0, 255.0) if kind == 0 else (255.0, 236.0, 185.0)
            for oy in (-1, 0, 1):
                for ox in (-1, 0, 1):
                    w = 1.0 if (ox == 0 and oy == 0) else (0.42 if (ox == 0 or oy == 0) else 0.14)
                    px = (ix + ox) % size
                    py = (iy + oy) % size
                    cell = buf[py][px]
                    cell[0] += cr * glint * w
                    cell[1] += cg * glint * w
                    cell[2] += cb * glint * w
                    cell[3] = min(255.0, cell[3] + 50.0 * glint * w)

        for y in range(size):
            for x in range(size):
                c = buf[y][x]
                im.putpixel((x, y), (clamp(c[0]), clamp(c[1]), clamp(c[2]), clamp(c[3])))
        sheet.paste(im, (0, frame * size))
    sheet.save(BLOCKS / "eirholm_gate.png")


def panels():
    """Two-layer volumetric portal panel models so the aurora has crystalline depth from every angle."""
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
                        "from": [0, 0, 7.65],
                        "to": [16, 16, 8.35],
                        "shade": False,
                        "faces": {
                            "north": {"uv": [u + 8, v, u, v + 8], "texture": "#portal"},
                            "south": {"uv": [u, v, u + 8, v + 8], "texture": "#portal"},
                        },
                    },
                    {
                        "from": [0, 0, 6.8],
                        "to": [16, 16, 9.2],
                        "shade": False,
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


if __name__ == "__main__":
    frame_stone()
    aurora()
    panels()
    print("ok")
