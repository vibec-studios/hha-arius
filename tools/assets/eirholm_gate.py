"""Generate the Rime Gate stone (four crisp 16x16 masonry variants), the frozen-aurora veil sheet, and panel models."""
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


def frame_stone(variant: int) -> Image.Image:
    """Crisp 16x16 glacial sanctuary masonry: hewn deep-slate stone with stepped carved bevels, subtle inlaid frost-ice
    border seams, quiet gold corner pins, and a refined chiseled central diamond relief."""
    size = 16
    rng = random.Random(7400 + variant * 101)
    im = Image.new("RGB", (size, size))

    mortar = (15, 21, 32)
    slate_shadow = (22, 31, 45)
    slate_dark = (30, 41, 58)
    slate_mid = (42, 56, 77)
    slate_lit = (63, 82, 108)
    rime_top = (158, 206, 232)
    ice_seam = (49, 102, 146)
    ice_mid = (94, 168, 212)
    ice_bright = (186, 232, 252)
    gold_dim = (164, 128, 56)
    gold_bright = (218, 182, 96)

    stone_var = [[rng.uniform(-4.5, 4.5) for _ in range(size)] for _ in range(size)]
    for y in range(0, size, 2):
        for x in range(0, size, 2):
            v = rng.uniform(-3.0, 3.0)
            for dy in (0, 1):
                for dx in (0, 1):
                    stone_var[y + dy][x + dx] += v

    rime_mask = [rng.uniform(0.0, 1.0) for _ in range(size)]

    for y in range(size):
        for x in range(size):
            edge = min(x, y, size - 1 - x, size - 1 - y)
            cx = abs(x - 7.5)
            cy = abs(y - 7.5)
            md = cx + cy

            if edge == 0:
                col = mortar
                if y == 0 and rime_mask[x] > 0.42 + variant * 0.04:
                    col = blend(mortar, rime_top, 0.36)
            elif edge == 1:
                if y == 1 or x == 1:
                    col = slate_lit
                    if y == 1 and 2 <= x <= 13 and rime_mask[x] > 0.30:
                        col = blend(col, rime_top, 0.34 if variant % 2 == 0 else 0.22)
                else:
                    col = slate_dark
            elif edge == 2:
                # Hewn corner caps wrap the stone; straight inlaid frost seam runs cleanly between corners
                if (x in (2, 13)) and (y in (2, 13)):
                    col = slate_lit if (x == 2 or y == 2) else slate_shadow
                elif (x in (2, 13) and y in (3, 12)) or (y in (2, 13) and x in (3, 12)):
                    col = gold_bright if (x <= 3 and y <= 3) else gold_dim
                else:
                    along = min(abs(x - 7.5), abs(y - 7.5))
                    t = max(0.0, 1.0 - along / 4.5)
                    col = blend(ice_seam, ice_mid, 0.25 + 0.75 * t)
                    if along <= 0.6:
                        col = blend(col, ice_bright, 0.35)
            elif edge == 3:
                # Deep chiseled shadow step into the carved central panel
                if (x in (3, 12)) and (y in (3, 12)):
                    col = slate_shadow
                elif y == 3 or x == 3:
                    col = mortar
                else:
                    col = slate_mid
            else:
                # Central carved stone panel (4..11 x 4..11) with subtle chiseled bevel & modest frost diamond crest
                sheen = 0.5 + 0.5 * ((7.5 - x) + (7.5 - y)) / 9.0
                col = blend(slate_dark, slate_mid, 0.3 + 0.7 * sheen)
                if md <= 1.1:
                    col = ice_bright if (x == 7 and y == 7) else ice_mid
                elif md <= 2.1:
                    col = ice_seam if (x >= 8 or y >= 8) else blend(ice_seam, ice_mid, 0.55)
                elif md <= 3.1:
                    col = slate_shadow
                elif md <= 4.1:
                    col = slate_lit if (x < 8 and y < 8) else slate_dark

            grain_scale = 0.22 if (edge == 2 and not (x in (2, 13) and y in (2, 13))) or (edge >= 4 and md <= 2.1) else 1.0
            n = stone_var[y][x] * grain_scale
            im.putpixel((x, y), (clamp(col[0] + n * 0.85), clamp(col[1] + n * 0.95), clamp(col[2] + n * 1.10)))

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
                # All harmonics use integer multipliers on u, v, and t -> guaranteed zero tile seam!
                su = u + 0.45 * math.sin(v + t) + 0.16 * math.cos(2 * v - t)
                sv = v + t + 0.24 * math.sin(u - t)

                curtain1 = (0.5 + 0.5 * math.sin(su + 0.35 * math.sin(sv))) ** 3.0
                curtain2 = (0.5 + 0.5 * math.cos(2 * su - sv + 0.35 * math.sin(t))) ** 3.6
                mist = 0.5 + 0.5 * math.sin(sv + 0.55 * math.cos(su))
                sheen = (0.5 + 0.5 * math.sin(su - sv)) ** 2.4

                glow = curtain1 * 0.65 + curtain2 * 0.35
                # Deep midnight-navy trough -> luminous glacial blue -> crisp frost-white aurora crest
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
    """Two gently separated translucent veil planes with harmonious UVs (no mirrored crossing) for calm glacial depth."""
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
    state = {"variants": {"": [{"model": f"hha_arius:block/{n}"} for n in names]}}
    (ASSETS / "blockstates/rime_gate_stone.json").write_text(json.dumps(state, indent=2) + "\n")


if __name__ == "__main__":
    stones()
    veil()
    panels()
    print("ok")
