"""Generate the Rime Gate stone (four seamless 16x16 glacial-obsidian variants inspired by vanilla Obsidian & Crying Obsidian),
the frozen-aurora portal veil sheet, and panel models."""
import json
import math
import random
from pathlib import Path

from PIL import Image

ASSETS = Path(__file__).resolve().parents[2] / "src/main/resources/assets/hha_arius"
BLOCKS = ASSETS / "textures/block"


def clamp(v: float, lo: int = 0, hi: int = 255) -> int:
    return max(lo, min(hi, round(v)))


def blend(a: tuple[float, float, float], b: tuple[float, float, float], t: float) -> tuple[float, float, float]:
    t = max(0.0, min(1.0, t))
    return (a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t)


def torus_dist(ax: float, ay: float, bx: float, by: float, size: int) -> tuple[float, float, float]:
    dx = ax - bx
    dy = ay - by
    if dx > size / 2:
        dx -= size
    elif dx < -size / 2:
        dx += size
    if dy > size / 2:
        dy -= size
    elif dy < -size / 2:
        dy += size
    return math.hypot(dx, dy), dx, dy


def frame_stone(variant: int) -> Image.Image:
    """Calm, deliberate 16x16 dark navy-black dressed stone masonry (4 running-bond ashlar variants):
    large flat stone facets, low internal contrast, zero speckles or per-pixel noise.
    Guaranteed full-width dressed courses (zero narrow sliver bricks). Variants 0, 1, 2 are pure calm
    dressed masonry with subtle slab tone & broad-facet variation; variant 3 carries one thin,
    deliberate hairline ice-blue vein (used sparingly via low blockstate weight)."""
    size = 16

    # Tight, calm dark navy-black dressed stone palette (very low internal contrast — reads as clean masonry at 10m)
    seam = (9, 13, 21)       # quiet dressed mortar joint
    shad = (14, 20, 31)      # soft lower/right chamfer shadow
    slab_a = (18, 25, 39)    # calm flat stone face A
    slab_b = (20, 28, 43)    # calm flat stone face B (primary)
    slab_c = (23, 32, 48)    # calm flat stone face C (subtle broad facet)
    lit = (27, 37, 55)       # gentle upper/left dressed edge catchlight
    corner = (31, 43, 63)    # quiet top-left corner accent

    # Restrained ice-blue hairline vein tones (variant 3 only — thin, deliberate, never speckled or neon)
    vein_edge = (25, 58, 90)
    vein_core = (66, 146, 196)

    # Classic architectural running-bond ashlar: upper course joint near center (7, 8, or 9),
    # lower course joint at x = 0 (or 15) so every stone across adjacent blocks is always 14-18px wide!
    # Tuple: (upper_joint_x, lower_joint_x, upper_left_tone, upper_right_tone, lower_tone)
    layouts = [
        (8, 0, slab_b, slab_a, slab_b),
        (7, 0, slab_a, slab_b, slab_c),
        (9, 0, slab_c, slab_b, slab_a),
        (8, 0, slab_b, slab_a, slab_b),
    ]
    uj, lj, ul_col, ur_col, lo_col = layouts[variant % len(layouts)]

    grid = [[slab_b] * size for _ in range(size)]

    def fill_course(y0: int, jx: int, left_col: tuple[int, int, int], right_col: tuple[int, int, int]) -> None:
        for ly in range(8):
            y = y0 + ly
            for x in range(size):
                if ly == 0:
                    grid[y][x] = seam
                    continue
                dx = (x - jx) % size
                if dx == 0:
                    grid[y][x] = seam
                    continue
                base_col = left_col if x < jx else right_col
                # Broad, quiet dressed-stone facet: slightly softer/brighter upper half of slab interior
                if ly == 1 and dx == 1:
                    grid[y][x] = corner
                elif ly == 1 or dx == 1:
                    grid[y][x] = lit
                elif ly == 7 or dx == size - 1:
                    grid[y][x] = shad
                elif ly == 2 and dx in (2, 3, 4, 5):
                    grid[y][x] = blend(base_col, lit, 0.25)
                elif ly == 6 and dx >= 4:
                    grid[y][x] = blend(base_col, shad, 0.28)
                else:
                    grid[y][x] = base_col

    fill_course(0, uj, ul_col, ur_col)
    fill_course(8, lj, lo_col, lo_col)

    # Variant 3 ONLY (low blockstate weight): one thin, deliberate hairline ice-blue vein
    # gliding calmly across the lower dressed stone slab — zero speckles, zero bright blotches.
    if variant == 3:
        vein_pts = [
            (4, 11), (5, 11), (6, 12), (7, 12), (8, 12), (9, 13), (10, 13), (11, 14),
        ]
        vein_set = set(vein_pts)
        for vx, vy in vein_pts:
            for ox, oy in ((-1, 0), (1, 0), (0, -1), (0, 1)):
                nx, ny = vx + ox, vy + oy
                if 9 <= ny <= 14 and 2 <= nx <= 14 and (nx, ny) not in vein_set:
                    grid[ny][nx] = blend(grid[ny][nx], vein_edge, 0.42)
        for vx, vy in vein_pts:
            grid[vy][vx] = vein_core

    im = Image.new("RGB", (size, size))
    for y in range(size):
        for x in range(size):
            c = grid[y][x]
            im.putpixel((x, y), (clamp(c[0]), clamp(c[1]), clamp(c[2])))
    return im


def veil() -> None:
    """Serene glacial aurora veil: broad, silky ice-blue currents rising smoothly over deep midnight navy,
    with soft drifting frost motes. 100% seamless in space (integer harmonics, 2x2 blocks) and time (24 frames)."""
    size, frames = 32, 24
    sheet = Image.new("RGBA", (size, size * frames))
    rng = random.Random(307)
    motes = [
        (rng.uniform(0, size), rng.uniform(0, size), rng.uniform(0, math.tau), rng.choice((1, 1, 2)),
         rng.uniform(1.4, 2.3), rng.uniform(0.42, 0.85))
        for _ in range(9)
    ]

    for frame in range(frames):
        t = math.tau * frame / frames
        buf = [[None] * size for _ in range(size)]
        for y in range(size):
            v = math.tau * y / size
            for x in range(size):
                u = math.tau * x / size
                su = u + 0.45 * math.sin(v + t) + 0.16 * math.cos(2 * v - t)
                sv = v + t + 0.24 * math.sin(u - t)

                curtain1 = (0.5 + 0.5 * math.sin(su + 0.35 * math.sin(sv))) ** 3.0
                curtain2 = (0.5 + 0.5 * math.cos(2 * su - sv + 0.35 * math.sin(t))) ** 3.6
                mist = 0.5 + 0.5 * math.sin(sv + 0.55 * math.cos(su))
                sheen = (0.5 + 0.5 * math.sin(su - sv)) ** 2.4

                glow = curtain1 * 0.65 + curtain2 * 0.35
                r = 6 + 10 * mist + 48 * glow + 102 * (glow ** 2.6) + 16 * sheen
                g = 18 + 24 * mist + 106 * glow + 108 * (glow ** 2.6) + 28 * sheen
                bl = 44 + 44 * mist + 132 * glow + 66 * (glow ** 2.6) + 34 * sheen
                al = 198 + 20 * mist + 34 * glow
                buf[y][x] = [r, g, bl, al]

        for mx, my, phase, speed, rad, bright in motes:
            cy = (my - frame * speed * (size / frames)) % size
            cx = (mx + 1.0 * math.sin(t + phase)) % size
            pulse = (0.45 + 0.55 * math.sin(t + phase)) * bright
            if pulse < 0.12:
                continue
            r_int = int(math.ceil(rad))
            ix0, iy0 = int(cx), int(cy)
            for oy in range(-r_int, r_int + 1):
                for ox in range(-r_int, r_int + 1):
                    px = (ix0 + ox) % size
                    py = (iy0 + oy) % size
                    dx = min(abs(px - cx), size - abs(px - cx))
                    dy = min(abs(py - cy), size - abs(py - cy))
                    w = math.exp(-(dx * dx + dy * dy) / (rad * rad * 0.52)) * pulse
                    if w < 0.02:
                        continue
                    cell = buf[py][px]
                    cell[0] += 135 * w
                    cell[1] += 175 * w
                    cell[2] += 200 * w
                    cell[3] = min(252, cell[3] + 36 * w)

        im = Image.new("RGBA", (size, size))
        for y in range(size):
            for x in range(size):
                c = buf[y][x]
                im.putpixel((x, y), (clamp(c[0]), clamp(c[1]), clamp(c[2]), clamp(c[3])))
        sheet.paste(im, (0, frame * size))

    sheet.save(BLOCKS / "eirholm_gate.png")


def panels() -> None:
    """Two gently separated translucent veil planes with harmonious UVs for calm glacial depth."""
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
                            "north": {"uv": [u, v, u + 8, v + 8], "texture": "#portal"},
                            "south": {"uv": [u, v, u + 8, v + 8], "texture": "#portal"},
                        },
                    },
                    {
                        "from": [0, 0, 6.85], "to": [16, 16, 9.15], "shade": False,
                        "faces": {
                            "north": {"uv": [u, v, u + 8, v + 8], "texture": "#portal"},
                            "south": {"uv": [u, v, u + 8, v + 8], "texture": "#portal"},
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


def stones() -> None:
    names = ["rime_gate_stone"] + [f"rime_gate_stone_{i}" for i in range(1, 4)]
    for i, name in enumerate(names):
        frame_stone(i).save(BLOCKS / f"{name}.png")
        if i:
            (ASSETS / f"models/block/{name}.json").write_text(
                json.dumps({"parent": "minecraft:block/cube_all", "textures": {"all": f"hha_arius:block/{name}"}}, indent=2) + "\n"
            )
    # Heavily favor calm unveined dressed stone (variants 0-2); rare single hairline vein on variant 3 (~12% of blocks)
    entries = []
    weights = [5, 5, 4, 2]
    for name, w in zip(names, weights):
        for rot in (0, 90, 180, 270):
            entry = {"model": f"hha_arius:block/{name}", "weight": w}
            if rot:
                entry["y"] = rot
            entries.append(entry)
    state = {"variants": {"": entries}}
    (ASSETS / "blockstates/rime_gate_stone.json").write_text(json.dumps(state, indent=2) + "\n")


if __name__ == "__main__":
    stones()
    veil()
    panels()
    print("ok")
