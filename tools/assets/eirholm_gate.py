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
    """Seamless 16x16 Glacial Obsidian inspired directly by vanilla Obsidian & Crying Obsidian:
    domain-warped angular volcanic-glass shards (zero straight ruler lines, zero stripes, zero borders),
    deep midnight-navy crevices, crisp cold obsidian catchlights, and recessed glowing glacial-ice fissures
    tucked organically into fracture junctions (like Crying Obsidian in frost-blue)."""
    size = 16
    # Hand-picked seeds that produce balanced chunky obsidian shards and organic fracture junctions
    seeds = [5204, 5341, 5488, 5619]
    rng = random.Random(seeds[variant % len(seeds)])

    # 10 irregular shard centers on a 16x16 periodic torus (chunky 4-6px volcanic glass facets)
    sites = []
    for gy in range(3):
        for gx in range(3):
            sx = (gx + 0.16 + rng.random() * 0.68) * (size / 3.0)
            sy = (gy + 0.16 + rng.random() * 0.68) * (size / 3.0)
            tone = rng.uniform(-1.0, 1.0)
            sites.append((sx % size, sy % size, tone))
    sites.append((rng.uniform(0, size), rng.uniform(0, size), rng.uniform(-1.0, 1.0)))

    # Select recessed glowing fissures by variant so a 4x5 portal frame feels monolithic & hand-carved:
    # variant 0 (hero / item): one clean branching frost fissure
    # variant 1 (monolithic dark): pure glacial obsidian with only one tiny recessed ice pocket
    # variant 2 (veined): two connected glacial cracks meeting at a junction
    # variant 3 (subtle): one short glowing seam tucked into dark obsidian rock
    hot_site = (variant * 3 + 1) % len(sites)
    nbrs = sorted(
        [(torus_dist(sites[hot_site][0], sites[hot_site][1], s[0], s[1], size)[0], idx)
         for idx, s in enumerate(sites) if idx != hot_site]
    )
    glow_pairs: dict[tuple[int, int], float] = {}
    if variant == 0:
        glow_pairs[tuple(sorted((hot_site, nbrs[0][1])))] = 1.0
        glow_pairs[tuple(sorted((hot_site, nbrs[1][1])))] = 0.78
    elif variant == 1:
        glow_pairs[tuple(sorted((hot_site, nbrs[0][1])))] = 0.62
    elif variant == 2:
        glow_pairs[tuple(sorted((hot_site, nbrs[0][1])))] = 0.95
        glow_pairs[tuple(sorted((hot_site, nbrs[2][1])))] = 0.80
    else:
        glow_pairs[tuple(sorted((hot_site, nbrs[1][1])))] = 0.85

    # Volcanic glacial-obsidian palette (deep rich darks like vanilla Obsidian, crisp cool bevels,
    # saturated glacial-cyan fissures with restrained frost-white hearts)
    crevice = (6, 9, 16)
    obs_deep = (11, 16, 28)
    obs_dark = (16, 23, 39)
    obs_base = (24, 34, 54)
    obs_mid = (35, 49, 75)
    obs_lit = (52, 72, 106)
    obs_spec = (76, 105, 144)

    ice_halo = (20, 56, 98)
    ice_vein = (44, 128, 188)
    ice_core = (116, 200, 240)
    ice_heart = (212, 244, 255)

    # First pass: compute domain-warped Voronoi facets, recessed seams, and directional obsidian bevels
    grid_col = [[obs_base] * size for _ in range(size)]
    vein_strength = [[0.0] * size for _ in range(size)]

    phase_a = variant * 1.7 + 0.4
    phase_b = variant * 2.3 + 1.1

    for y in range(size):
        v = math.tau * (y + 0.5) / size
        for x in range(size):
            u = math.tau * (x + 0.5) / size
            # Periodic organic domain warp bends Voronoi bisectors into natural conchoidal rock fractures
            wx = (x + 0.5 + 0.68 * math.sin(v + phase_a) + 0.34 * math.cos(2 * u - v + phase_b)) % size
            wy = (y + 0.5 + 0.68 * math.cos(u + phase_b) + 0.34 * math.sin(2 * v + u + phase_a)) % size

            dists = []
            for idx, (sx, sy, tone) in enumerate(sites):
                d, dx, dy = torus_dist(wx, wy, sx, sy, size)
                dists.append((d, idx, dx, dy, tone))
            dists.sort(key=lambda t: t[0])
            d1, i1, dx1, dy1, tone1 = dists[0]
            d2, i2, _, _, _ = dists[1]
            d3 = dists[2][0]
            edge = d2 - d1  # 0 on fracture boundary between nearest two obsidian shards
            junction = d3 - d1  # small right at 3-shard crack vertices

            pair = tuple(sorted((i1, i2)))
            seam_power = glow_pairs.get(pair, 0.0)
            # Taper glowing fissures naturally toward shard ends so they pool organically around junctions
            taper = max(0.0, min(1.0, 1.35 - 0.24 * junction))
            glow_width = 1.05 * seam_power * taper

            inv_d = 1.0 / max(d1, 0.35)
            lit = (-dx1 * 0.72 - dy1 * 0.72) * inv_d  # top-left directional obsidian bevel

            if seam_power > 0.0 and edge < glow_width:
                s = max(0.0, 1.0 - edge / max(glow_width, 0.01)) * seam_power
                vein_strength[y][x] = s
                if edge < 0.26 and junction < 2.25 and seam_power >= 0.85:
                    col = ice_heart
                elif edge < 0.48 and s > 0.48:
                    col = ice_core
                elif edge < 0.78:
                    col = ice_vein
                else:
                    col = ice_halo
            elif edge < 0.52:
                # Deep obsidian fracture crevice
                col = crevice
            elif edge < 1.12 and lit > 0.32:
                # Crisp obsidian chipped edge catching cold light
                col = obs_spec if (lit > 0.72 and tone1 > -0.15) else obs_lit
            elif edge < 1.08 and lit < -0.28:
                # Deeply shadowed underside of a shard
                col = obs_deep
            else:
                # Chunky interior obsidian glass facet
                v_tone = 0.46 + 0.30 * lit + 0.24 * tone1 + rng.uniform(-0.07, 0.07)
                if v_tone < 0.22:
                    col = obs_deep
                elif v_tone < 0.42:
                    col = obs_dark
                elif v_tone < 0.66:
                    col = obs_base
                elif v_tone < 0.84:
                    col = obs_mid
                else:
                    col = obs_lit

            g = rng.uniform(-2.5, 2.5) if vein_strength[y][x] == 0.0 else rng.uniform(-1.2, 1.2)
            grid_col[y][x] = (col[0] + g * 0.8, col[1] + g * 0.9, col[2] + g * 1.1)

    # Second pass: rich subtle glacial-blue glow bleeding 1px from recessed fissures onto neighboring obsidian
    im = Image.new("RGB", (size, size))
    for y in range(size):
        for x in range(size):
            col = grid_col[y][x]
            if vein_strength[y][x] == 0.0:
                best_nb = 0.0
                for oy in (-1, 0, 1):
                    for ox in (-1, 0, 1):
                        if ox == 0 and oy == 0:
                            continue
                        w = 1.0 if (ox == 0 or oy == 0) else 0.45
                        best_nb = max(best_nb, vein_strength[(y + oy) % size][(x + ox) % size] * w)
                if best_nb > 0.14:
                    col = blend(col, ice_halo, min(0.65, best_nb * 0.62))
            im.putpixel((x, y), (clamp(col[0]), clamp(col[1]), clamp(col[2])))
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
    # Weighted variants + y-rotations so large frames never show grid repetition
    entries = []
    weights = [3, 4, 2, 3]
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
