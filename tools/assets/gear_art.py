"""Grafiken für Rüstung und Items von H&H Armors: Arius (Eirholm-Wacht).

Aufruf (aus dem Projektordner):
    python tools/assets/gear_art.py            # alles erzeugen (Texturen per mctex batch, JSON direkt) + Vorschauen
    python tools/assets/gear_art.py --preview  # nur Vorschauen nach tmp/preview/gear/ (schreibt keine Assets)

Vanilla-Texturen (für Vorschauen und Trim-Paletten) kommen aus dem Loom-Cache (werden nach tmp/vanilla/ entpackt).
Alles andere wird hier in Python gerechnet. Kein PIL: PNGs werden mit zlib gelesen und für Vorschauen geschrieben.

Inhalt:
  * Trim-Muster „Weiße Klinge“ (Graustufen nach Vanilla-trim_palette) + Atlas-Ergänzung armor_trims.json
  * Item-Icons (die vier Rüstungsicons rendert armor_preview.py aus dem 3D-Modell, siehe docs/ARMOR_3D.md),
    Solvane-Handmodell (3D, Hohlkehle animiert in item/solvane_fuller.png), HUD-Sprites, Mod-Icon
  * Die Rüstung selbst ist seit V6 ein eigenes 3D-Modell: tools/assets/armor_model.py (keine Equipment-Ebenen mehr)
"""
import json
import math
import os
import struct
import sys
import zipfile
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import texkit  # noqa: E402

ROOT = texkit.ROOT
ASSETS = texkit.ASSETS
VANILLA = os.path.join(ROOT, 'tmp', 'vanilla')
PREVIEW = os.path.join(ROOT, 'tmp', 'preview', 'gear')
CLIENT_JAR = os.path.expanduser(r'~\.gradle\caches\fabric-loom\1.21.11\minecraft-client.jar')

# ---------------------------------------------------------------------------------------------------------------------
# PNG lesen/schreiben
# ---------------------------------------------------------------------------------------------------------------------


def read_png(path):
    """Liefert (w, h, [RGBA]) für 8-Bit-PNGs (Graustufen, RGB, Palette, GA, RGBA), ohne Interlace."""
    data = open(path, 'rb').read()
    assert data[:8] == b'\x89PNG\r\n\x1a\n', path
    pos, idat, plte, trns = 8, b'', None, None
    w = h = depth = ctype = 0
    while pos < len(data):
        n, typ = struct.unpack('>I4s', data[pos:pos + 8])
        chunk = data[pos + 8:pos + 8 + n]
        pos += 12 + n
        if typ == b'IHDR':
            w, h, depth, ctype, _, _, inter = struct.unpack('>IIBBBBB', chunk)
            assert inter == 0, 'interlaced PNG'
        elif typ == b'PLTE':
            plte = [tuple(chunk[i:i + 3]) for i in range(0, n, 3)]
        elif typ == b'tRNS':
            trns = chunk
        elif typ == b'IDAT':
            idat += chunk
    raw = zlib.decompress(idat)
    chans = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[ctype]
    bits = chans * depth
    stride = (w * bits + 7) // 8
    bpp = max(1, bits // 8)
    rows, prev, i = [], bytearray(stride), 0
    for _ in range(h):
        f = raw[i]
        line = bytearray(raw[i + 1:i + 1 + stride])
        i += 1 + stride
        for x in range(stride):
            a = line[x - bpp] if x >= bpp else 0
            b = prev[x]
            c = prev[x - bpp] if x >= bpp else 0
            if f == 1:
                line[x] = (line[x] + a) & 255
            elif f == 2:
                line[x] = (line[x] + b) & 255
            elif f == 3:
                line[x] = (line[x] + (a + b) // 2) & 255
            elif f == 4:
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                line[x] = (line[x] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
        rows.append(line)
        prev = line
    key = None
    if trns is not None and ctype in (0, 2):
        key = struct.unpack('>H', trns[:2])[0] if ctype == 0 else struct.unpack('>HHH', trns[:6])
    px = []
    for line in rows:
        if depth < 8:
            vals = []
            for byte in line:
                for s in range(8 - depth, -1, -depth):
                    vals.append((byte >> s) & ((1 << depth) - 1))
        else:
            vals = line
        for x in range(w):
            if ctype == 3:
                idx = vals[x]
                r, g, b = plte[idx]
                px.append((r, g, b, trns[idx] if trns is not None and idx < len(trns) else 255))
            elif ctype == 0:
                v = vals[x] if depth == 8 else vals[x] * 255 // ((1 << depth) - 1)
                px.append((v, v, v, 0 if key is not None and vals[x] == key else 255))
            elif ctype == 4:
                px.append((vals[2 * x], vals[2 * x], vals[2 * x], vals[2 * x + 1]))
            elif ctype == 2:
                rgb = (vals[3 * x], vals[3 * x + 1], vals[3 * x + 2])
                px.append(rgb + (0 if key is not None and rgb == tuple(key) else 255,))
            else:
                px.append(tuple(vals[4 * x:4 * x + 4]))
    return w, h, px


def write_png(path, w, h, px):
    raw = bytearray()
    for y in range(h):
        raw.append(0)
        for c in px[y * w:(y + 1) * w]:
            raw.extend(c[:3])
            raw.append(c[3] if len(c) > 3 else 255)

    def chunk(t, d):
        return struct.pack('>I', len(d)) + t + d + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)

    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    with open(path, 'wb') as f:
        f.write(b'\x89PNG\r\n\x1a\n')
        f.write(chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 6, 0, 0, 0)))
        f.write(chunk(b'IDAT', zlib.compress(bytes(raw), 9)))
        f.write(chunk(b'IEND', b''))


def vanilla(rel):
    """rel unter assets/minecraft/textures/, z. B. item/netherite_helmet.png → (w, h, px)."""
    path = os.path.join(VANILLA, 'assets', 'minecraft', 'textures', rel)
    if not os.path.exists(path):
        with zipfile.ZipFile(CLIENT_JAR) as z:
            z.extract('assets/minecraft/textures/' + rel, VANILLA)
    return read_png(path)


class Tex:
    """Bearbeitbare RGBA-Textur."""

    def __init__(self, w, h, px=None):
        self.w, self.h = w, h
        self.px = list(px) if px is not None else [(0, 0, 0, 0)] * (w * h)

    @classmethod
    def load(cls, rel):
        w, h, px = vanilla(rel)
        return cls(w, h, px)

    def copy(self):
        return Tex(self.w, self.h, self.px)

    def get(self, x, y):
        if 0 <= x < self.w and 0 <= y < self.h:
            return self.px[y * self.w + x]
        return (0, 0, 0, 0)

    def set(self, x, y, c):
        if 0 <= x < self.w and 0 <= y < self.h:
            self.px[y * self.w + x] = c

    def to_image(self):
        img = texkit.Image(self.w, self.h)
        img.px = list(self.px)
        return img


def hexc(h, a=255):
    return texkit.hexc(h, a)


def mix(a, b, t):
    return texkit.mix(a, b, t)


def ramp(cols, t):
    return texkit.ramp(cols, t)


def lum(c):
    return 0.299 * c[0] + 0.587 * c[1] + 0.114 * c[2]


# ---------------------------------------------------------------------------------------------------------------------
# Farben (KONZEPT 10)
# ---------------------------------------------------------------------------------------------------------------------
ANTH = [hexc('#0c0d12'), hexc('#16171e'), hexc('#22232b'), hexc('#33353f'), hexc('#4a4c5c'), hexc('#5e6174')]
STEEL = [hexc('#0e1522'), hexc('#1b2a40'), hexc('#2c4466'), hexc('#3f5d87'), hexc('#5a7fae'), hexc('#7d9cc6')]
GOLD = [hexc('#4a3410'), hexc('#8a6624'), hexc('#b8913f'), hexc('#e0bd62'), hexc('#f6dd94')]
ICE = hexc('#9fe6ff')
SEAM = hexc('#1a2b40')          # Grundfarbe unter einer Leuchtnaht (dunkle Fuge)
CORE_HI, CORE_LO = hexc('#9fdcff'), hexc('#3f7fc0')
HEART = hexc('#ffd98a')


def eirholm_metal(t):
    """t 0..1 (Helligkeit im Netherite-Original) → Anthrazit mit stahlblauem Unterton."""
    a = ramp(ANTH, t)
    s = ramp(STEEL, t)
    # Mitteltöne tragen das Blau, Tiefen bleiben neutral-dunkel, Lichter werden kühl.
    k = 0.18 + 0.42 * math.sin(min(1.0, t) * math.pi * 0.85)
    return mix(a, s, k)


def gold(level):
    """level 0..4 (0 tiefster Schatten, 4 Glanz)."""
    return GOLD[max(0, min(4, level))]


# ---------------------------------------------------------------------------------------------------------------------
# Rüstung: seit V6 ein eigenes 3D-Modell (tools/assets/armor_model.py, Vorschau armor_preview.py). Die frühere
# umgefärbte Netherite-Ebene samt Leuchtnähten und equipment/eirholm.json gibt es nicht mehr.
# ---------------------------------------------------------------------------------------------------------------------


# ---------------------------------------------------------------------------------------------------------------------
# Trim „Weiße Klinge“ (Graustufen der Vanilla-trim_palette: 224 hell … 0 dunkel)
# ---------------------------------------------------------------------------------------------------------------------
TRIM_LEVELS = (224, 192, 160, 128, 96, 64, 32, 0)


def trim_px(v):
    return (v, v, v, 255)


TRIM_HUMANOID = [
    # Helm: Klingenlinie über den Scheitel, Vierstern auf der Stirn
    (11, 2, 128), (12, 2, 96), (11, 3, 160), (12, 3, 128), (11, 4, 160), (12, 4, 128), (11, 5, 192), (12, 5, 160),
    (11, 6, 192), (12, 6, 160), (11, 7, 224), (12, 7, 192),
    (11, 8, 224), (12, 8, 192), (10, 9, 160), (11, 9, 224), (12, 9, 224), (13, 9, 128), (11, 10, 192), (12, 10, 160),
    # Brust: Schwert mit der Spitze nach unten. Parierstange = Vierstern, Klinge bis zum Saum
    (22, 23, 160), (23, 23, 224), (24, 23, 224), (25, 23, 128),
    (20, 24, 96), (21, 24, 160), (22, 24, 192), (23, 24, 224), (24, 24, 224), (25, 24, 192), (26, 24, 128), (27, 24, 96),
    (22, 25, 128), (23, 25, 224), (24, 25, 192), (25, 25, 96),
    (23, 26, 224), (24, 26, 160), (23, 27, 224), (24, 27, 160), (23, 28, 224), (24, 28, 160),
    (23, 29, 192), (24, 29, 128), (23, 30, 160), (24, 30, 128), (23, 31, 128),
    # Rücken: schmale Klingenlinie
    (35, 21, 128), (36, 21, 96), (35, 22, 192), (36, 22, 160), (34, 23, 128), (35, 23, 224), (36, 23, 224),
    (37, 23, 128), (35, 24, 192), (36, 24, 160), (35, 25, 192), (36, 25, 128), (35, 26, 160), (36, 26, 128),
    (35, 27, 160), (36, 27, 96), (35, 28, 128), (36, 28, 96),
    # Schultern: Vierstern außen
    (41, 20, 160), (42, 20, 128), (41, 21, 224), (42, 21, 192), (40, 22, 128), (41, 22, 224), (42, 22, 224),
    (43, 22, 128), (41, 23, 192), (42, 23, 160), (41, 24, 128), (42, 24, 96),
    # Stiefel: kurze Klingenlinie außen
    (1, 27, 192), (2, 27, 160), (1, 28, 224), (2, 28, 160), (1, 29, 192), (2, 29, 128), (1, 30, 128), (2, 30, 96),
]
TRIM_LEGGINGS = [
    # Außennaht: senkrechte Klinge mit Vierstern oben
    (1, 20, 192), (2, 20, 160), (0, 21, 160), (1, 21, 224), (2, 21, 224), (3, 21, 128), (1, 22, 224), (2, 22, 160),
    (1, 23, 224), (2, 23, 160), (1, 24, 192), (2, 24, 160), (1, 25, 192), (2, 25, 128), (1, 26, 160),
    (2, 26, 128), (1, 27, 128), (2, 27, 96),
    # Gürtel vorn: kleiner Vierstern
    (23, 27, 192), (24, 27, 160), (22, 28, 160), (23, 28, 224), (24, 28, 224), (25, 28, 128), (23, 29, 160),
    (24, 29, 128),
]


def build_trims():
    out = []
    for pts in (TRIM_HUMANOID, TRIM_LEGGINGS):
        t = Tex(64, 32)
        for x, y, v in pts:
            assert v in TRIM_LEVELS, v
            t.set(x, y, trim_px(v))
        out.append(t)
    return out


def apply_trim(tex, trim, material):
    """Für Vorschauen: färbt den Graustufen-Trim mit einer Vanilla-Materialpalette und legt ihn auf tex."""
    _, _, key = vanilla('trims/color_palettes/trim_palette.png')
    _, _, pal = vanilla('trims/color_palettes/%s.png' % material)
    lut = {k[:3]: p for k, p in zip(key, pal)}
    out = tex.copy()
    for i, c in enumerate(trim.px):
        if c[3]:
            out.px[i] = lut[c[:3]]
    return out


TRIM_ATLAS = {
    'sources': [{
        'type': 'minecraft:paletted_permutations',
        'palette_key': 'minecraft:trims/color_palettes/trim_palette',
        'permutations': {m: 'minecraft:trims/color_palettes/' + m for m in (
            'amethyst', 'copper', 'copper_darker', 'diamond', 'diamond_darker', 'emerald', 'gold', 'gold_darker',
            'iron', 'iron_darker', 'lapis', 'netherite', 'netherite_darker', 'quartz', 'redstone', 'resin')},
        'textures': ['hha_arius:trims/entity/humanoid/white_blade', 'hha_arius:trims/entity/humanoid_leggings/white_blade'],
    }]
}

# ---------------------------------------------------------------------------------------------------------------------
# Vorschau: Spielerfigur mit Rüstung (orthografisch, Tiefenpuffer)
# ---------------------------------------------------------------------------------------------------------------------


class Box:
    def __init__(self, tex, uv, origin, size, dil=0.0, mirror=False, glow=False):
        self.tex, self.uv, self.origin, self.size, self.dil, self.mirror, self.glow = tex, uv, origin, size, dil, mirror, glow

    def texels(self):
        """Liefert (Punkt-Generator-Parameter): je Texel (Farbe, Ecke, Achse u, Achse v, Normale)."""
        u, v = self.uv
        w, h, d = self.size
        e = self.dil
        x0, y0, z0 = self.origin
        X0, Y0, Z0 = x0 - e, y0 - e, z0 - e
        X1, Y1, Z1 = x0 + w + e, y0 + h + e, z0 + d + e
        W, H, D = X1 - X0, Y1 - Y0, Z1 - Z0
        faces = [
            # (Bereich u, v, Breite, Höhe), Abbildung (i, j) -> Punkt-Ursprung, Schritt in i, Schritt in j, Normale
            ((u + d, v + d, w, h), lambda i, j: ((X0 + i * W / w, Y0 + j * H / h, Z0), (W / w, 0, 0), (0, H / h, 0)), (0, 0, -1)),
            ((u + 2 * d + w, v + d, w, h), lambda i, j: ((X1 - i * W / w, Y0 + j * H / h, Z1), (-W / w, 0, 0), (0, H / h, 0)), (0, 0, 1)),
            ((u, v + d, d, h), lambda i, j: ((X0, Y0 + j * H / h, Z1 - i * D / d), (0, 0, -D / d), (0, H / h, 0)), (-1, 0, 0)),
            ((u + d + w, v + d, d, h), lambda i, j: ((X1, Y0 + j * H / h, Z0 + i * D / d), (0, 0, D / d), (0, H / h, 0)), (1, 0, 0)),
            ((u + d, v, w, d), lambda i, j: ((X0 + i * W / w, Y0, Z1 - j * D / d), (W / w, 0, 0), (0, 0, -D / d)), (0, -1, 0)),
            ((u + d + w, v, w, d), lambda i, j: ((X0 + i * W / w, Y1, Z0 + j * D / d), (W / w, 0, 0), (0, 0, D / d)), (0, 1, 0)),
        ]
        for (fu, fv, fw, fh), fn, normal in faces:
            for j in range(fh):
                for i in range(fw):
                    c = self.tex.get(fu + i, fv + j)
                    if c[3] < 8:
                        continue
                    p, du, dv = fn(i, j)
                    if self.mirror:
                        p = (-p[0], p[1], p[2])
                        du = (-du[0], du[1], du[2])
                        dv = (-dv[0], dv[1], dv[2])
                        n = (-normal[0], normal[1], normal[2])
                    else:
                        n = normal
                    yield c, p, du, dv, n


def player_boxes(skin, armor_h=None, armor_l=None, glow_h=None, glow_l=None):
    """Boxen in Weltkoordinaten (y nach unten, vorn = -z). Linke Teile = gespiegelte rechte."""
    boxes = []

    def part(tex, uv, origin, size, dil, mirror=False, glow=False):
        if tex is not None:
            boxes.append(Box(tex, uv, origin, size, dil, mirror, glow))

    # Körper (Steve, klassisches Layout, linke Seite gespiegelt aus rechter UV, damit die Vorschau einfach bleibt)
    part(skin, (0, 0), (-4, -8, -4), (8, 8, 8), 0)
    part(skin, (16, 16), (-4, 0, -2), (8, 12, 4), 0)
    part(skin, (40, 16), (-8, 0, -2), (4, 12, 4), 0)
    part(skin, (40, 16), (-8, 0, -2), (4, 12, 4), 0, True)
    part(skin, (0, 16), (-3.9, 12, -2), (4, 12, 4), 0)
    part(skin, (0, 16), (-3.9, 12, -2), (4, 12, 4), 0, True)
    for tex, glow in ((armor_l, False), (glow_l, True)):
        part(tex, (16, 16), (-4, 0, -2), (8, 12, 4), 0.5, glow=glow)
        part(tex, (0, 16), (-3.9, 12, -2), (4, 12, 4), 0.5, glow=glow)
        part(tex, (0, 16), (-3.9, 12, -2), (4, 12, 4), 0.5, True, glow=glow)
    for tex, glow in ((armor_h, False), (glow_h, True)):
        part(tex, (0, 0), (-4, -8, -4), (8, 8, 8), 1.0, glow=glow)
        part(tex, (16, 16), (-4, 0, -2), (8, 12, 4), 1.0, glow=glow)
        part(tex, (40, 16), (-8, 0, -2), (4, 12, 4), 1.0, glow=glow)
        part(tex, (40, 16), (-8, 0, -2), (4, 12, 4), 1.0, True, glow=glow)
        part(tex, (0, 16), (-3.9, 12, -2), (4, 12, 4), 1.0, glow=glow)
        part(tex, (0, 16), (-3.9, 12, -2), (4, 12, 4), 1.0, True, glow=glow)
    return boxes


LIGHT = (-0.35, -0.8, -0.5)


def render_boxes(boxes, size, yaw_deg, pitch_deg=8.0, scale=None, center=(0, 7.5, 0), bg=((34, 38, 50), (64, 70, 86)),
                 glow_strength=1.0):
    ya, pa = math.radians(yaw_deg), math.radians(pitch_deg)
    cy_, sy_ = math.cos(ya), math.sin(ya)
    cp, sp = math.cos(pa), math.sin(pa)
    ln = math.sqrt(sum(c * c for c in LIGHT))
    light = tuple(c / ln for c in LIGHT)

    def rot(p):
        x, y, z = p
        x, z = x * cy_ + z * sy_, -x * sy_ + z * cy_
        y, z = y * cp - z * sp, y * sp + z * cp
        return x, y, z

    W = H = size
    pix = []
    for y in range(H):
        t = y / max(1, H - 1)
        pix.extend([tuple(int(a + (b - a) * t) for a, b in zip(*bg))] * W)
    depth = [1e9] * (W * H)
    if scale is None:
        scale = size / 37.0
    for box in boxes:
        for c, p, du, dv, n in box.texels():
            nr = rot(n)
            if box.glow:
                k = glow_strength
                col = tuple(min(255, int(ch * k)) for ch in c[:3])
            else:
                shade = 0.55 + 0.45 * max(0.0, -(nr[0] * light[0] + nr[1] * light[1] + nr[2] * light[2]))
                col = tuple(int(ch * shade) for ch in c[:3])
            steps = max(1, int(math.ceil(scale * max(math.sqrt(sum(a * a for a in du)), math.sqrt(sum(a * a for a in dv))) * 1.6)))
            for sj in range(steps):
                for si in range(steps):
                    fi, fj = (si + 0.5) / steps, (sj + 0.5) / steps
                    q = (p[0] + du[0] * fi + dv[0] * fj, p[1] + du[1] * fi + dv[1] * fj, p[2] + du[2] * fi + dv[2] * fj)
                    q = (q[0] - center[0], q[1] - center[1], q[2] - center[2])
                    xr, yr, zr = rot(q)
                    # Glühen und Trims liegen auf gleicher Fläche wie die Rüstung: leicht nach vorn ziehen.
                    zr -= 0.02 if box.glow else 0.0
                    sx = int(W / 2 + xr * scale)
                    sy = int(H / 2 + yr * scale)
                    if 0 <= sx < W and 0 <= sy < H:
                        k = sy * W + sx
                        if zr < depth[k] - 1e-6 or (box.glow and zr <= depth[k] + 0.01):
                            depth[k] = zr
                            if c[3] < 255 and not box.glow:
                                a = c[3] / 255.0
                                pix[k] = tuple(int(o + (n_ - o) * a) for o, n_ in zip(pix[k], col))
                            else:
                                pix[k] = col
    return pix


def sheet(panels, cols, out, gap=4, bg=(16, 16, 20)):
    """panels: Liste (w, h, px). Legt sie in einem Raster ab."""
    rows = (len(panels) + cols - 1) // cols
    cw = max(p[0] for p in panels)
    ch = max(p[1] for p in panels)
    W, H = cols * cw + (cols + 1) * gap, rows * ch + (rows + 1) * gap
    px = [bg] * (W * H)
    for n, (w, h, p) in enumerate(panels):
        ox = gap + (n % cols) * (cw + gap)
        oy = gap + (n // cols) * (ch + gap)
        for y in range(h):
            for x in range(w):
                c = p[y * w + x]
                px[(oy + y) * W + ox + x] = c[:3]
    write_png(out, W, H, px)
    return out


def upscale(tex, s, checker=True):
    w, h = tex.w * s, tex.h * s
    px = []
    for y in range(h):
        for x in range(w):
            c = tex.get(x // s, y // s)
            k = (70 if ((x // max(1, s // 2 or 1)) + (y // max(1, s // 2 or 1))) % 2 else 58) if checker else 40
            base = (k, k, k + 6)
            a = c[3] / 255.0
            px.append(tuple(int(b + (cc - b) * a) for b, cc in zip(base, c[:3])))
    return w, h, px


def preview_trims(trims):
    """Trim „Weiße Klinge“ auf Vanilla-Rüstung (die Eirholm-Rüstung selbst zeigt keine Trims mehr, siehe ARMOR_3D.md)."""
    _, _, skin_px = vanilla('entity/player/wide/steve.png')
    skin = Tex(64, 64, skin_px)
    # Trim „Weiße Klinge“ auf Netherite- und Eisenrüstung
    panels = []
    neth = (Tex.load('entity/equipment/humanoid/netherite.png'), Tex.load('entity/equipment/humanoid_leggings/netherite.png'))
    iron = (Tex.load('entity/equipment/humanoid/iron.png'), Tex.load('entity/equipment/humanoid_leggings/iron.png'))
    for (base_h, base_l), mat in ((neth, 'gold'), (neth, 'diamond'), (iron, 'quartz'), (iron, 'redstone')):
        th = apply_trim(base_h, trims[0], mat)
        tl = apply_trim(base_l, trims[1], mat)
        b = player_boxes(skin, th, tl)
        panels.append((300, 300, render_boxes(b, 300, 25, scale=300 / 40.0)))
    sheet(panels, 4, os.path.join(PREVIEW, 'trim_white_blade.png'))
    sheet([upscale(t, 8) for t in trims], 2, os.path.join(PREVIEW, 'trim_flat.png'))


# ---------------------------------------------------------------------------------------------------------------------
# Item-Icons (16 x 16, Vanilla-Stil: harte Pixel, dunkler Umriss)
# ---------------------------------------------------------------------------------------------------------------------
OUTLINE = hexc('#111219')


def grid(rows, legend, w=16):
    t = Tex(w, len(rows))
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch not in '. ':
                t.set(x, y, legend[ch])
    return t


def outline(t, color=OUTLINE, diagonal=False):
    """Setzt einen Umriss um alle deckenden Pixel (4er-Nachbarschaft, optional 8er). color darf f(src, x, y) sein."""
    src = t.copy()
    nb = [(1, 0), (-1, 0), (0, 1), (0, -1)] + ([(1, 1), (-1, -1), (1, -1), (-1, 1)] if diagonal else [])
    for y in range(t.h):
        for x in range(t.w):
            if src.get(x, y)[3]:
                continue
            if any(src.get(x + dx, y + dy)[3] > 0 for dx, dy in nb):
                t.set(x, y, color(src, x, y) if callable(color) else color)
    return t


G0, G1, G2, G3, G4 = GOLD
WHITE = hexc('#ffffff')
BLADE_L, BLADE_S = hexc('#dfe6ef'), hexc('#a9b6c8')
GRIP_D, GRIP_H = hexc('#141419'), hexc('#3a3b48')


# ---------------------------------------------------------------------------------------------------------------------
# Solvane: mittellanges Bastardschwert als Elemente-Modell (Hand, Boden, Kopf); GUI/Rahmen/Regal zeigen das Icon.
# ---------------------------------------------------------------------------------------------------------------------
# Vorbild sind die Handmodelle der Schwester-Addons (Void: GenVoidModels.java): das Modell steht AUFRECHT (Längsachse
# y, Mitte x = z = 8), die Textur wird mit 2 Texeln je Einheit aus der 3D-Lage jedes Texels gemalt (Fase, Korn,
# Glanz), das 32x32-Icon wird aus demselben Modell gerendert (schräg auf die Diagonale gelegt, 8x8 Abtastpunkte je
# Pixel, dominante Farbe, 1-px-Umriss) und die Display-Werte werden wie bei Void aus den Vanilla-Werten abgeleitet,
# sodass die Hand am oberen Griffende direkt unter der Parierstange sitzt.
#
# Designblatt „Solvane – Final Design“: weiße Klinge mit Goldakzenten in der Hohlkehle, symmetrische Parierstange mit
# Rautenstern und Rautenenden, langer dunkel umwickelter Anderthalbhand-Griff mit Goldzwingen, geometrischer
# Rautenknauf. V6 (Rückmeldung „noch tot“, Lore: Klinge aus dem Herzen eines uralten weißen Drachen): Klinge ~35 %
# länger (Langschwert), leuchtende Hohlkehle mit langsamem Lichtfluss (animierte Textur, light_emission),
# Eisglanz an den Schneiden, Drachenschuppen-Gravur an der breiteren Klingenwurzel, Herz-Juwel im Rautenstern.
# Maße: Knauf -7,6 … Spitze 28 (≈ 35,6 Einheiten), Griff 6, Parierstange 10,5 breit, Klinge 25.
GRIP_Y = -0.5            # Griffpunkt (Mitte der Hand): obere Griffhälfte, direkt unter Zwinge und Parierstange
BLADE_Y0 = 3.0           # Klingenwurzel (über der Parierstange)
BLADE_TIP = 28.0         # Spitze
FULLER_Y0 = 5.5          # Hohlkehle beginnt (unter der Zunge des Sterns)
FULLER_GOLD = 9.0        # bis hier ist die Hohlkehle mit Gold ausgelegt
FULLER_END = 22.0        # hier läuft die Hohlkehle aus
ENGRAVE_END = 7.5        # Schuppengravur an der Klingenwurzel bis hier
SOLVANE_FRAMES = 16      # Lichtfluss in der Hohlkehle
SOLVANE_FRAMETIME = 4

# (y0, y1, halbe Breite des Klingenkörpers, Breite einer Schneide)
BLADE_SECTIONS = [
    (BLADE_Y0, 6.5, 1.0, 0.5),
    (6.5, 7.5, 0.875, 0.5),
    (7.5, 20.5, 0.75, 0.5),
    (20.5, 24.0, 0.5, 0.5),
    (24.0, 26.0, 0.5, 0.25),
    (26.0, 27.0, 0.25, 0.25),
]


def solvane_parts():
    """(Name, from, to, Drehung um z in Grad oder 0). Rauten sind um 45° gedrehte Quader um ihre eigene Mitte."""
    parts = []

    def box(name, fr, to):
        parts.append((name, fr, to, 0))

    def diamond(name, cx, cy, size, thick):
        h = size / 2.0
        parts.append((name, (cx - h, cy - h, 8 - thick / 2), (cx + h, cy + h, 8 + thick / 2), 45))

    diamond('pommel', 8, -6.15, 2.0, 1.8)                                  # Rautenknauf (Spitze unten bei -7,6)
    box('collar', (7.0, -5.0, 7.0), (9.0, -4.5, 9.0))                       # Knaufzwinge
    box('grip', (7.25, -4.5, 7.25), (8.75, 1.5, 8.75))                      # Griff, 6 lang (anderthalb Hände)
    box('ferrule', (7.0, 1.5, 7.0), (9.0, 2.0, 9.0))                        # obere Zwinge
    box('guard', (3.5, 2.0, 7.25), (12.5, 3.0, 8.75))                       # Parierstange
    diamond('guard_end', 3.3, 2.5, 1.5, 1.7)                                 # Rautenenden
    diamond('guard_end', 12.7, 2.5, 1.5, 1.7)
    diamond('guard_star', 8, 2.5, 2.7, 2.1)                                  # Rautenstern in der Mitte
    box('langet', (7.5, 3.5, 7.4), (8.5, 5.0, 8.6))                         # Stern läuft spitz auf die Klinge
    box('langet', (7.75, 5.0, 7.45), (8.25, 6.0, 8.55))
    for y0, y1, hb, e in BLADE_SECTIONS:
        box('blade', (8 - hb, y0, 7.55), (8 + hb, y1, 8.45))
        box('edge', (8 - hb - e, y0, 7.75), (8 - hb, y1, 8.25))
        box('edge', (8 + hb, y0, 7.75), (8 + hb + e, y1, 8.25))
    box('point', (7.75, 27.0, 7.75), (8.25, BLADE_TIP, 8.25))
    # Leuchtende Hohlkehle: hauchdünn vor beiden Flachseiten, eigene Lichtstufe (siehe LIGHT_EMISSION)
    box('fuller', (7.75, FULLER_Y0, 7.5), (8.25, FULLER_END, 8.5))
    # Herz-Juwel im Rautenstern: goldener Kern, blauer Rand, steht vorn und hinten knapp vor dem Stern
    diamond('heart', 8, 2.5, 1.25, 2.3)
    return parts


LIGHT_EMISSION = {'fuller': 10, 'heart': 13}


TEX3D = 128          # Texturgröße (wird bei Bedarf verdoppelt)
PX = 4               # Texel je Modelleinheit (V6: doppelt so fein, für Gravur, Juwel und Lichtfluss)

# Paletten (dunkel -> hell)
BLADE_STEEL = [hexc('#5f6b80'), hexc('#8693a8'), hexc('#adb9cb'), hexc('#cfd8e5'), hexc('#e7edf5'), hexc('#f7f9fc'),
         hexc('#ffffff')]
AUR = [hexc('#3d2509'), hexc('#6b4615'), hexc('#9a722c'), hexc('#c69d4c'), hexc('#e6c67f'), hexc('#fbedc4')]
WRAP = [hexc('#0f0e12'), hexc('#1a181e'), hexc('#28252c'), hexc('#3a3540'), hexc('#4e4855')]
GROOVE = [hexc('#7f8ea6'), hexc('#a3b2c8'), hexc('#c3cfe0')]
OUTLINE_ICON = hexc('#12131a')


def _hash(a, b, c):
    h = (a * 374761393 + b * 668265263 + c * 2147483647 + 0x9E3779B9) & 0xffffffff
    h = ((h ^ (h >> 13)) * 1274126177) & 0xffffffff
    h ^= h >> 16
    return (h & 0xffffff) / float(0x1000000)


def _q(v):
    return int(math.floor(v * PX + 1e-6))


# Flächen eines Elements: Ursprung (Texel 0,0 oben links), Richtung u, Richtung v, Normale (Minecraft-UV-Lage)
def face_spec(fr, to, face):
    x0, y0, z0 = fr
    x1, y1, z1 = to
    return {
        'south': ((x0, y1, z1), (1, 0, 0), (0, -1, 0), (0, 0, 1)),
        'north': ((x1, y1, z0), (-1, 0, 0), (0, -1, 0), (0, 0, -1)),
        'east': ((x1, y1, z1), (0, 0, -1), (0, -1, 0), (1, 0, 0)),
        'west': ((x0, y1, z0), (0, 0, 1), (0, -1, 0), (-1, 0, 0)),
        'up': ((x0, y1, z0), (1, 0, 0), (0, 0, 1), (0, 1, 0)),
        'down': ((x0, y0, z1), (1, 0, 0), (0, 0, -1), (0, -1, 0)),
    }[face]


def face_dims(fr, to, face):
    w, h, d = (to[i] - fr[i] for i in range(3))
    return {'north': (w, h), 'south': (w, h), 'east': (d, h), 'west': (d, h), 'up': (w, d), 'down': (w, d)}[face]


def _bevel(i, j, pw, ph, face):
    """Pixel-Fase wie bei Void: Oberkante hell, Unterkante dunkel, Rand der Deckflächen etwas heller."""
    if face in ('up', 'down'):
        return 0.5 if (pw > 2 and ph > 2 and (i in (0, pw - 1) or j in (0, ph - 1))) else 0.0
    b = 0.0
    if ph > 2 and j == 0:
        b += 0.9
    if ph > 2 and j == ph - 1:
        b -= 0.8
    return b


FRAME = [0]          # aktueller Frame des Lichtflusses (build_solvane_3d malt alle Frames nacheinander)
FLOW_ICE = [hexc('#4f78ac'), hexc('#7fb2e2'), hexc('#bfe4fa'), hexc('#eefaff'), hexc('#ffffff')]
HEART_RIM_C = [hexc('#143f8f'), hexc('#2f7be0'), hexc('#7cc0ff')]
HEART_CORE_C = [hexc('#c98a22'), hexc('#f6cf6e'), hexc('#fff3cf')]
ICE_SHEEN = hexc('#d4f2ff')


def _flow(y):
    """Helligkeit 0..1 des Lichtbands in der Hohlkehle im aktuellen Frame: ein weiches Band wandert die Klinge hinauf."""
    span = FULLER_END - FULLER_Y0 + 6.0
    c = FULLER_Y0 - 3.0 + span * FRAME[0] / SOLVANE_FRAMES
    return math.exp(-((y - c) / 1.9) ** 2)


def paint_solvane(name, face, p, i, j, pw, ph):
    """Farbe eines Texels. p = 3D-Lage (Modellraum des Elements), i/j = Texel auf der Fläche, pw/ph = Flächengröße."""
    x, y, z = p
    n = _hash(_q(x), _q(y), _q(z) + len(face) * 31)
    flat = face in ('north', 'south')
    top = {'up': 0.9, 'down': -1.1}.get(face, 0.0)
    if name == 'fuller':
        f = _flow(y)
        if y < FULLER_GOLD:                                                  # Goldauslage am Ansatz
            if y > FULLER_GOLD - 0.3:
                return AUR[2]
            g = 3.5 + (0.7 if _q(y) % 5 == 0 else 0) + (n - 0.5) * 0.5
            return mix(ramp(AUR, g / 5.0), hexc('#fff4d6'), 0.75 * f)
        fade = (y - FULLER_GOLD) / (FULLER_END - FULLER_GOLD)              # oben kühler und heller
        c = ramp(FLOW_ICE, 0.3 + 0.22 * fade + (n - 0.5) * 0.08)
        if y > FULLER_END - 0.5:
            c = mix(c, ramp(BLADE_STEEL, 0.6), 0.5)                          # läuft weich aus
        return mix(c, hexc('#ffffff'), 0.85 * f)
    if name == 'heart':
        if not flat:
            return HEART_RIM_C[0]
        cx, cy = (pw - 1) / 2.0, (ph - 1) / 2.0
        r = max(abs(i - cx), abs(j - cy)) / max(cx, 0.5)
        if r < 0.3:
            return HEART_CORE_C[2]
        if r < 0.62:
            return HEART_CORE_C[1] if (i - cx) + (j - cy) < 0 else HEART_CORE_C[0]
        return HEART_RIM_C[2] if (i - cx) + (j - cy) < 0 else HEART_RIM_C[1]
    if name in ('blade', 'edge', 'point'):
        along = (y - BLADE_Y0) / (BLADE_TIP - BLADE_Y0)                     # 0 Wurzel … 1 Spitze
        if name == 'edge' or name == 'point':
            k = 4.9 + (n - 0.5) * 0.5 + along * 0.4 + top * 0.5
            c = None
            if flat:
                hb = next((sec[2] for sec in BLADE_SECTIONS if sec[0] <= y < sec[1]), 0.25)
                outer = abs(x - 8) > hb + 0.25 if name == 'edge' else True
                k += 0.6 if outer else 0.0                                   # helle Schneidkante
                c = ramp(BLADE_STEEL, k / 6.0)
                if outer:
                    c = mix(c, ICE_SHEEN, 0.32 + 0.25 * along)                # Eisglanz an der Schneide
                if n > 0.94:
                    c = mix(c, hexc('#ffffff'), 0.8)                         # Frostfunke
            return c or ramp(BLADE_STEEL, k / 6.0)
        if not flat:
            return ramp(BLADE_STEEL, (3.6 + top) / 6.0)
        d = x - 8.0                                                          # Abstand zur Mittellinie
        # Flachseite: Licht von links oben, sanfter Längsverlauf, ganz feines Korn, kühler Widerschein zur Spitze
        side = 0.55 if d < 0 else -0.65
        if face == 'north':
            side = -side
        k = 4.0 + side + (n - 0.5) * 0.3 - along * 0.15
        c = ramp(BLADE_STEEL, k / 6.0)
        if y < ENGRAVE_END and abs(d) >= 0.25:
            # Drachenschuppen-Gravur an der Klingenwurzel: versetzte Reihen kleiner Bögen (3 Texel hoch)
            v = int(math.floor((y - BLADE_Y0) * PX + 1e-6))
            u = int(math.floor((abs(d) - 0.25) * PX + 1e-6))
            row, rr = v // 3, v % 3
            cu = (u + (row % 2)) % 2
            if rr == 2 or (rr == 1 and cu == 1):
                fade = max(0.0, (ENGRAVE_END - y) / (ENGRAVE_END - BLADE_Y0))
                return mix(c, ramp(BLADE_STEEL, (k - 1.6) / 6.0), 0.4 + 0.5 * fade)
        return mix(c, ICE_SHEEN, 0.12 * along)
    if name in ('guard', 'collar', 'ferrule', 'langet'):
        k = 2.9 + (n - 0.5) * 0.9 + top + _bevel(i, j, pw, ph, face)
        if name == 'guard' and flat and ph > 2 and j == ph // 2 and 2 < i < pw - 3:
            k -= 1.0                                                          # gravierte Mittellinie
        if name == 'guard' and flat:
            k += 0.35 * math.exp(-((abs(x - 8) - 3.2) / 1.6) ** 2)            # Glanz auf den Armen
        if name == 'langet' and flat:
            k += 0.6 if i < pw / 2 else -0.2
        return ramp(AUR, k / 5.0)
    if name in ('guard_star', 'guard_end', 'pommel'):
        # Facettierte Raute: vier Dreiecke zur Mitte (nach der 45°-Drehung zeigen sie zu den vier Kanten),
        # heller Grat auf den Diagonalen, dunkle Fassung am Rand.
        if not flat:
            return ramp(AUR, (2.6 + top + (n - 0.5) * 0.6) / 5.0)
        cx, cy = (pw - 1) / 2.0, (ph - 1) / 2.0
        di, dj = i - cx, j - cy
        if face == 'north':
            di = -di
        rim = i in (0, pw - 1) or j in (0, ph - 1)
        if abs(abs(di) - abs(dj)) < 0.6 and not rim:
            k = 4.6                                                           # Facettengrat
        elif abs(dj) >= abs(di):
            k = 4.0 if dj < 0 else 1.8                                       # oben-links hell, unten-rechts dunkel
        else:
            k = 3.2 if di < 0 else 2.3
        if rim:
            k -= 0.8
        if name == 'guard_star' and abs(di) < 0.6 and abs(dj) < 0.6:
            return hexc('#fff6dc')                                            # Lichtpunkt im Sternzentrum
        return ramp(AUR, (k + (n - 0.5) * 0.4) / 5.0)
    if name == 'grip':
        if face in ('up', 'down'):
            return WRAP[1]
        # Schräg gewickelte Lederbänder: Band 2 Texel, Fuge 1 Texel, versetzt über die Seiten (wie eine Spirale).
        around = {'south': x - 7.25, 'east': 1.5 + (8.75 - z), 'north': 3.0 + (8.75 - x), 'west': 4.5 + (z - 7.25)}[face]
        s = int(math.floor((y + around * 0.5) * 2 + 1e-6)) % 3
        if s == 2:
            return WRAP[0]                                                    # Fuge
        k = (3.2 if s == 0 else 2.2) + (n - 0.5) * 0.6
        k += {'south': 0.4, 'west': 0.2, 'east': -0.4, 'north': -0.5}[face]
        return ramp(WRAP, k / 4.0)
    return hexc('#ff00ff')


ANIMATED = ('fuller',)    # Elemente mit eigener, animierter Textur #1 (solvane_fuller.png)
FULLER_TEX = 72           # Kantenlänge eines Frames der Hohlkehlen-Textur


def _pack_solvane(size, animated=False):
    """animated=False: alle statischen Elemente in Textur #0; animated=True: nur die Hohlkehle in Textur #1."""
    tex = Tex(size, size)
    elements = []
    cx = cy = row_h = 0
    key = '#1' if animated else '#0'
    for name, fr, to, rot in solvane_parts():
        if (name in ANIMATED) != animated:
            continue
        faces = {}
        for face in ('north', 'east', 'south', 'west', 'up', 'down'):
            fw, fh = face_dims(fr, to, face)
            pw, ph = int(math.ceil(fw * PX - 1e-6)), int(math.ceil(fh * PX - 1e-6))
            if cx + pw > size:
                cx, cy, row_h = 0, cy + row_h, 0
            if cy + ph > size:
                return None
            o, du, dv, _ = face_spec(fr, to, face)
            for j in range(ph):
                for i in range(pw):
                    fu, fv = min((i + 0.5) / PX, fw - 1e-3), min((j + 0.5) / PX, fh - 1e-3)
                    p = tuple(o[k] + du[k] * fu + dv[k] * fv for k in range(3))
                    tex.set(cx + i, cy + j, paint_solvane(name, face, p, i, j, pw, ph))
            s = 16.0 / size
            faces[face] = {'uv': [round(cx * s, 4), round(cy * s, 4), round((cx + fw * PX) * s, 4), round((cy + fh * PX) * s, 4)],
                           'texture': key}
            cx += pw
            row_h = max(row_h, ph)
        el = {'name': name, 'from': [round(v, 4) for v in fr], 'to': [round(v, 4) for v in to], 'faces': faces}
        if name in LIGHT_EMISSION:
            el['light_emission'] = LIGHT_EMISSION[name]
        if rot:
            el['rotation'] = {'angle': rot, 'axis': 'z',
                              'origin': [round((fr[k] + to[k]) / 2, 4) for k in range(3)]}
        elements.append(el)
    return tex, elements


# --- Display-Werte (Rechenweg aus GenVoidModels.java: held/pose/leftOf) ------------------------------------------
def _rx(d):
    a = math.radians(d)
    return [[1, 0, 0], [0, math.cos(a), -math.sin(a)], [0, math.sin(a), math.cos(a)]]


def _ry(d):
    a = math.radians(d)
    return [[math.cos(a), 0, math.sin(a)], [0, 1, 0], [-math.sin(a), 0, math.cos(a)]]


def _rz(d):
    a = math.radians(d)
    return [[math.cos(a), -math.sin(a), 0], [math.sin(a), math.cos(a), 0], [0, 0, 1]]


def _mm(a, b):
    return [[sum(a[i][k] * b[k][j] for k in range(3)) for j in range(3)] for i in range(3)]


def _mv(a, v):
    return [sum(a[i][k] * v[k] for k in range(3)) for i in range(3)]


def _r2(v):
    r = round(v * 100) / 100.0
    return 0 if r == 0 else r


def _euler(m):
    if abs(m[0][2]) > 0.999999:
        a, b, c = 0.0, 90.0 if m[0][2] > 0 else -90.0, math.degrees(math.atan2(m[1][0], m[1][1]))
    else:
        b = math.degrees(math.asin(m[0][2]))
        a = math.degrees(math.atan2(-m[1][2], m[2][2]))
        c = math.degrees(math.atan2(-m[0][1], m[0][0]))
    if abs(a) > 90:
        a -= math.copysign(180, a)
        b = 180 - b
        c -= math.copysign(180, c)
        if b > 180:
            b -= 360
    return [_r2(a), _r2(b), _r2(c)]


def _rot_xyz(r):
    return _mm(_rx(r[0]), _mm(_ry(r[1]), _rz(r[2])))


def _held(z_v, t_v, s_v, vgrip, rot, n, grip):
    """Griffpunkt grip landet dort, wo Vanilla (handheld, z-Rotation z_v, Verschiebung t_v, Größe s_v) vgrip hält."""
    a = _mv(_mm(_ry(-90), _rz(z_v - 45)), [s_v * (vgrip[k] - 8) for k in range(3)])
    b = _mv(_rot_xyz(rot), [n * (grip[k] - 8) for k in range(3)])
    return list(rot) + [_r2(t_v[k] + a[k] - b[k]) for k in range(3)] + [n]


def _pose(lean, pitch, roll, hand, n, grip):
    """Ego-Sicht als Haltung: roll um die Längsachse, pitch Spitze nach vorn, lean Spitze zur Bildmitte."""
    r = _mm(_rz(lean), _mm(_rx(-pitch), _ry(-90 + roll)))
    b = _mv(r, [n * (grip[k] - 8) for k in range(3)])
    return _euler(r) + [_r2(hand[k] - b[k]) for k in range(3)] + [n]


def _left_of(d):
    """Werte der linken Hand als exaktes Spiegelbild (das Modell ist in z symmetrisch)."""
    r = _rot_xyz(d[:3])
    m = [[(-1 if i == 0 else 1) * r[i][j] * (-1 if j == 2 else 1) for j in range(3)] for i in range(3)]
    e = _euler(m)
    return [e[0], _r2(-e[1]), _r2(-e[2])] + list(d[3:])


def _entry(d):
    return {'rotation': [_r2(v) for v in d[:3]], 'translation': [_r2(v) for v in d[3:6]], 'scale': [d[6]] * 3}


SOLVANE_GRIP = (8, GRIP_Y, 8)
# 3rd Person wie Void-Schwert (Vanilla-Griff [8, 1, 8], z-Rotation 55-45 = 10). V6: Größe 0,95 -> 0,9, weil die Klinge
# 35 % länger ist (Spitze bei hängendem Arm ~1,6 Blöcke vor dem Körper, nie unter Fußhöhe; Griffpunkt unverändert).
# Ändert sich diese Zeile, müssen ArtPose.GRIP/BLADE_FROM/BLADE_TO (Schwertspur) nachgezogen werden.
SOLVANE_TP_SCALE = 0.9
SOLVANE_TP = _held(55, (0, 4, 0.5), 0.85, (8, 1, 8), (0, -90, 10), SOLVANE_TP_SCALE, SOLVANE_GRIP)
# Ego-Sicht wie ein Vanilla-Schwert (Crix 17.11.: „so halten wie ein normales Netherite-Schwert“): Vanilla-Handheld
# (z 25°, Verschiebung 1,13/3,2/1,13, Größe 0,68), Griff dort, wo Vanilla den Schwertgriff hält. Größe 0,52: Die Klinge
# ist so etwa ein Drittel länger als das Netherite-Schwert. Ändert sich das, GauntletFirstPerson.ITEM_* nachziehen.
SOLVANE_FP = _held(25, (1.13, 3.2, 1.13), 0.68, (8, 2.3, 8), (0, -90, -20), 0.52, SOLVANE_GRIP)


def build_solvane_3d():
    """Liefert (Texturen, Modell-JSON). Texturen: {'#0': statische Textur (wächst, bis alles passt), '#1': Hohlkehle im
    ersten Frame, 'strip': alle SOLVANE_FRAMES Frames der Hohlkehle untereinander (Lichtfluss, animiert per mcmeta)}."""
    FRAME[0] = 0
    size = TEX3D
    packed = _pack_solvane(size)
    while packed is None:
        size *= 2
        packed = _pack_solvane(size)
    tex, elements = packed
    fs = FULLER_TEX
    strip = Tex(fs, fs * SOLVANE_FRAMES)
    fuller_elements = None
    for f in range(SOLVANE_FRAMES):
        FRAME[0] = f
        ft, fel = _pack_solvane(fs, animated=True)
        fuller_elements = fuller_elements or fel
        strip.px[f * fs * fs:(f + 1) * fs * fs] = ft.px
    FRAME[0] = 0
    elements = elements + fuller_elements
    texs = {'#0': tex, '#1': Tex(fs, fs, strip.px[:fs * fs]), 'strip': strip}
    model = {
        'textures': {'0': 'hha_arius:item/solvane_3d', '1': 'hha_arius:item/solvane_fuller', 'particle': 'hha_arius:item/solvane'},
        'elements': elements,
        'display': {
            'thirdperson_righthand': _entry(SOLVANE_TP),
            'thirdperson_lefthand': _entry(_left_of(SOLVANE_TP)),
            'firstperson_righthand': _entry(SOLVANE_FP),
            'firstperson_lefthand': _entry(_left_of(SOLVANE_FP)),
            'ground': {'rotation': [0, 0, 0], 'translation': [0, 3, 0], 'scale': [0.36, 0.36, 0.36]},
            'head': {'rotation': [0, 180, 0], 'translation': [0, 13, 7], 'scale': [1, 1, 1]},
        },
    }
    return texs, model


# --- Rasterer für Vorschau und Icon ------------------------------------------------------------------------------
def _el_rot(el):
    r = el.get('rotation')
    if not r:
        return None
    return _rz(r['angle']), r['origin']


def raster(model, tex, size, view, scale, center, light=(-0.45, 0.7, 0.55), ambient=0.58):
    """Orthografischer Render. view: 3x3 (Modell -> Kamera, x rechts, y oben, z zur Kamera). Liefert pro Pixel
    (Farbe, Name, y im Schwert-Rahmen) oder None."""
    texs = tex if isinstance(tex, dict) else {'#0': tex}
    ln = math.sqrt(sum(c * c for c in light))
    light = [c / ln for c in light]
    depth = [-1e9] * (size * size)
    out = [None] * (size * size)
    for el in model['elements']:
        fr, to = el['from'], el['to']
        rot = _el_rot(el)
        for face, f in el['faces'].items():
            ftex = texs.get(f.get('texture', '#0'), texs['#0'])
            tsize = ftex.w
            u0, v0, u1, v1 = (c * tsize / 16.0 for c in f['uv'])
            fw, fh = face_dims(fr, to, face)
            o, du, dv, nrm = face_spec(fr, to, face)
            if rot:
                nrm = _mv(rot[0], nrm)
            nv = _mv(view, nrm)
            if nv[2] <= 0:
                continue                                                      # Rückseite
            shade = ambient + (1 - ambient) * max(0.0, sum(a * b for a, b in zip(nv, light)))
            if el.get('light_emission'):
                shade = 1.0                                                   # leuchtet unabhängig vom Licht
            steps = max(2.0, scale * 1.7)
            for jj in range(int(math.ceil(fh * steps))):
                fv = (jj + 0.5) / steps
                if fv > fh:
                    continue
                for ii in range(int(math.ceil(fw * steps))):
                    fu = (ii + 0.5) / steps
                    if fu > fw:
                        continue
                    c = ftex.get(int(u0 + fu * (u1 - u0) / fw), int(v0 + fv * (v1 - v0) / fh))
                    if not c[3]:
                        continue
                    p = [o[k] + du[k] * fu + dv[k] * fv for k in range(3)]
                    sy = p[1]
                    if rot:
                        m, org = rot
                        q = _mv(m, [p[k] - org[k] for k in range(3)])
                        p = [q[k] + org[k] for k in range(3)]
                    v = _mv(view, [p[k] - center[k] for k in range(3)])
                    px, py = int(size / 2 + v[0] * scale), int(size / 2 - v[1] * scale)
                    if 0 <= px < size and 0 <= py < size and v[2] > depth[py * size + px]:
                        depth[py * size + px] = v[2]
                        out[py * size + px] = (tuple(min(255, int(ch * shade)) for ch in c[:3]) + (255,), el['name'], sy)
    return out


def _view(yaw, pitch):
    return _mm(_rx(pitch), _ry(yaw))


def render_model(model, tex, size, yaw_deg, pitch_deg=0.0, scale=None, center=(8, 7, 8)):
    scale = scale or size / 34.0
    px = []
    buf = raster(model, tex, size, _view(yaw_deg, pitch_deg), scale, center)
    for k, c in enumerate(buf):
        t = (k // size) / (size - 1)
        px.append(c[0][:3] if c else (int(36 + 30 * t), int(40 + 32 * t), int(52 + 36 * t)))
    return px


ICON_TURN, ICON_TILT = 192, 12       # Icon: um die Längsachse gedreht, leicht von oben (wie Void)


def _icon_frames(model, tex, n=32, ss=8):
    """Rendert das Icon (n x n) und liefert (Tex, Klingen-Lage je Pixel für den Schimmer)."""
    view = _mm(_rz(-45), _mm(_rx(ICON_TILT), _ry(ICON_TURN)))
    big = n * ss
    # Länge -7,6 … 28 auf die Diagonale mit 1 px Rand je Seite (Umriss)
    scale = (n - 3) * math.sqrt(2) / (BLADE_TIP + 7.6 + 0.5) * ss
    buf = raster(model, tex, big, view, scale, (8, (BLADE_TIP - 7.6) / 2, 8), ambient=0.82)
    icon = Tex(n, n)
    blade_y = {}
    for y in range(n):
        for x in range(n):
            counts, ys, hits = {}, [], 0
            for sy in range(ss):
                for sx in range(ss):
                    c = buf[(y * ss + sy) * big + x * ss + sx]
                    if c is None:
                        continue
                    hits += 1
                    key = tuple(v // 6 * 6 for v in c[0][:3])
                    counts.setdefault(key, []).append(c[0])
                    if c[1] in ('blade', 'edge', 'point', 'fuller'):
                        ys.append(c[2])
            if hits * 3 < ss * ss:
                continue
            best = max(counts.values(), key=len)
            col = tuple(sum(cc[k] for cc in best) // len(best) for k in range(3)) + (255,)
            icon.set(x, y, col)
            if len(ys) * 2 >= hits:
                blade_y[(x, y)] = sum(ys) / len(ys)
    return icon, blade_y


def icon_solvane(with_meta=False):
    tex, model = build_solvane_3d()
    icon, blade_y = _icon_frames(model, tex)
    outline(icon, OUTLINE_ICON)
    return (icon, blade_y) if with_meta else icon


def solvane_shimmer(base, blade_y):
    """Dezenter Schimmer fürs GUI-Icon: ein Glanzband wandert die Klinge hinauf, dann lange Ruhe (~4 s)."""
    frames = [base]
    for k in range(7):
        f = base.copy()
        y0 = BLADE_Y0 + k * (BLADE_TIP - BLADE_Y0) / 7
        for (x, y), by in blade_y.items():
            d = abs(by - y0)
            c = base.get(x, y)
            if d < 1.1:
                f.set(x, y, mix(c, WHITE, 0.75))
            elif d < 2.4:
                f.set(x, y, mix(c, hexc('#e6f6ff'), 0.4))
        frames.append(f)
    n = base.w
    strip = Tex(n, n * len(frames))
    for i, f in enumerate(frames):
        for y in range(n):
            for x in range(n):
                strip.set(x, i * n + y, f.get(x, y))
    meta = {'animation': {'frametime': 2, 'frames': [{'index': 0, 'time': 70}] + list(range(1, len(frames)))}}
    return strip, meta


def preview_solvane(model, tex, icon):
    mid = (8, (BLADE_TIP - 7.6) / 2, 8)
    panels = [(300, 300, render_model(model, tex, 300, yaw, 8, scale=300 / 38.0, center=mid)) for yaw in (0, 35, 90, 180)]
    panels.append(upscale(tex['#0'], max(1, 300 // tex['#0'].w)))
    panels.append(upscale(icon, 300 // icon.w))
    sheet(panels, 3, os.path.join(PREVIEW, 'solvane_3d.png'))
    # Lichtfluss: Klinge frontal, jeder zweite Frame nebeneinander
    flow = []
    fs = FULLER_TEX
    for f in range(0, SOLVANE_FRAMES, 2):
        ft = dict(tex, **{'#1': Tex(fs, fs, tex['strip'].px[f * fs * fs:(f + 1) * fs * fs])})
        flow.append((120, 420, _crop(render_model(model, ft, 420, 0, 0, scale=420 / 34.0, center=(8, 12.5, 8)), 420, 150, 0, 120, 420)))
    sheet(flow, len(flow), os.path.join(PREVIEW, 'solvane_flow.png'))


def _crop(px, w, x0, y0, cw, ch):
    return [px[(y0 + y) * w + x0 + x] for y in range(ch) for x in range(cw)]


def icon_heartfrost():
    t = grid([
        '................',
        '................',
        '.......b........',
        '......bme.......',
        '......bmea......',
        '.....bmmeea.....',
        '.....bmhkea.....',
        '....bmhHhkea....',
        '....bmhHHkea....',
        '....bmmhkeea....',
        '.....bmmeea.....',
        '.....bmmeaa.....',
        '......bmea......',
        '......bea.......',
        '.......a........',
        '................',
    ], dict(a=hexc('#4f9fd4'), b=hexc('#e6f8ff'), m=hexc('#9fe6ff'), e=hexc('#6fc0ea'),
            h=hexc('#ffd98a'), H=hexc('#fff4d2'), k=hexc('#e8a94a')))
    return outline(t, hexc('#14304a'))


def icon_nivaris_scale():
    # Gewölbte Drachenschuppe mit grauem Altersrand, Mittelkiel und Wachstumsringen (kein Edelstein-Schliff).
    t = grid([
        '................',
        '................',
        '.....GGGGGG.....',
        '...GGgggggggG...',
        '..GgWWWkwwwwgG..',
        '..gWWWWkwwwwpg..',
        '..gWwwwkwwwppg..',
        '..grrrrkrrrrrg..',
        '..gwwwwkwwwwpg..',
        '..Ggwwwkwwwpg...',
        '...grrrkrrrpg...',
        '....gwwkwwpg....',
        '.....ggkpgg.....',
        '......GgG.......',
        '................',
        '................',
    ], dict(W=WHITE, w=hexc('#e6ebf2'), p=hexc('#b9c7da'), r=hexc('#cfd8e4'), k=hexc('#f7fbff'),
            g=hexc('#9097a6'), G=hexc('#6b7282')))
    return outline(t, hexc('#23262f'))


def icon_wingscale():
    """Schwingenfächer: drei helle Knochenspreizen, membranblaue Haut mit eingezogenem, zerfranstem Rand, Goldfassung."""
    t = Tex(16, 16)
    base = (3.0, 12.5)
    tips = [(4.6, 0.6), (10.8, 1.6), (15.2, 8.6)]
    angs = [math.atan2(ty - base[1], tx - base[0]) for tx, ty in tips]
    lens = [math.hypot(tx - base[0], ty - base[1]) for tx, ty in tips]
    mem = [hexc('#4e72a6'), hexc('#7097c8'), hexc('#9dbde3'), hexc('#c4d8f0')]
    holes = {(12, 6), (8, 2)}
    for y in range(16):
        for x in range(16):
            dx, dy = x + 0.5 - base[0], y + 0.5 - base[1]
            r, a = math.hypot(dx, dy), math.atan2(dy, dx)
            if not angs[0] - 0.05 <= a <= angs[2] + 0.05 or (x, y) in holes:
                continue
            i = 0 if a <= angs[1] else 1
            f = (a - angs[i]) / (angs[i + 1] - angs[i])
            f = max(0.0, min(1.0, f))
            rmax = lens[i] + (lens[i + 1] - lens[i]) * f - 1.7 * math.sin(math.pi * f)
            if r > rmax:
                continue
            c = ramp(mem, 0.15 + 0.8 * r / rmax)
            if rmax - r < 1.0:
                c = mem[0]
            t.set(x, y, c)
    # Knochenspreizen
    for (tx, ty), ln in zip(tips, lens):
        for k in range(int(ln * 2) + 1):
            u = k / (ln * 2)
            x, y = int(base[0] + (tx - base[0]) * u), int(base[1] + (ty - base[1]) * u)
            t.set(x, y, hexc('#eef4fb') if u > 0.75 else hexc('#d2deec'))
    # Goldfassung mit Kern am Ansatz
    for x, y, c in [(2, 11, G3), (3, 11, G4), (4, 11, G2), (2, 12, G2), (3, 12, CORE_HI), (4, 12, G2),
                    (2, 13, G1), (3, 13, G2), (1, 14, G2), (2, 14, G1)]:
        t.set(x, y, c)
    return outline(t, hexc('#162034'))


def icon_seal():
    t = Tex(16, 16)
    for y in range(16):
        for x in range(16):
            dx, dy = x - 7.5, y - 7.5
            r = math.hypot(dx, dy)
            if r > 6.9:
                continue
            if r > 5.6:
                c = G3 if dx + dy < -1 else G2 if dx + dy < 3 else G1
            elif r > 4.8:
                c = G0
            else:
                c = eirholm_metal(0.35 - 0.04 * (dx + dy) / 5)
            t.set(x, y, c)
    for x, y, c in [(7, 3, G2), (8, 3, G2), (7, 4, G3), (8, 4, G3), (7, 5, G4), (8, 5, G3), (5, 7, G2), (6, 7, G3),
                    (7, 6, WHITE), (8, 6, G4), (7, 7, CORE_HI), (8, 7, ICE), (7, 8, ICE), (8, 8, CORE_LO),
                    (9, 7, G3), (10, 7, G2), (9, 8, G2), (10, 8, G1), (5, 8, G2), (6, 8, G3), (7, 9, G3), (8, 9, G2),
                    (7, 10, G3), (8, 10, G2), (7, 11, G1), (8, 11, G1)]:
        t.set(x, y, c)
    return outline(t, hexc('#1b130a'))


def icon_echo():
    t = Tex(16, 16)
    for y in range(16):
        for x in range(16):
            dx, dy = x - 7.5, y - 7.5
            r = math.hypot(dx, dy)
            if 5.2 < r <= 6.4:
                t.set(x, y, hexc('#9fe6ff', 230) if dx + dy < 0 else hexc('#6fb8e0', 210))
            elif r <= 5.2:
                t.set(x, y, hexc('#bfeaff', 120) if r > 3.6 else hexc('#d8f4ff', 150))
    pale, ice2 = hexc('#e6f8ff'), hexc('#cdefff')
    for x, y, c in [(7, 2, pale), (8, 2, ice2), (7, 3, WHITE), (8, 3, pale), (7, 4, WHITE), (8, 4, pale),
                    (5, 7, ice2), (6, 7, WHITE), (7, 5, WHITE), (8, 5, WHITE), (7, 6, WHITE), (8, 6, WHITE),
                    (7, 7, WHITE), (8, 7, WHITE), (9, 7, pale), (10, 7, ice2), (7, 8, WHITE), (8, 8, pale),
                    (5, 8, ice2), (6, 8, WHITE), (9, 8, ice2), (10, 8, ICE), (7, 9, WHITE), (8, 9, pale),
                    (7, 10, pale), (8, 10, ice2), (7, 11, ice2), (8, 11, ICE), (7, 12, ICE), (8, 12, hexc('#6fb8e0'))]:
        t.set(x, y, c)
    return outline(t, hexc('#123049', 220))


def icon_upgrade_template():
    """Silhouette wie die Netherite-Aufwertungsvorlage, Platte in Stahlanthrazit, Motiv: goldener Vierstern + Kern."""
    t = Tex.load('item/netherite_upgrade_smithing_template.png')
    cols = sorted({c for c in t.px if c[3] > 0 and not (c[2] > c[0] + 40)}, key=lum)
    lo, hi = lum(cols[0]), lum(cols[-1])
    slab = [hexc('#141a26'), hexc('#212b3e'), hexc('#2f3d57'), hexc('#3e5072'), hexc('#53698f')]
    for i, c in enumerate(t.px):
        if c[3] == 0:
            continue
        if c[2] > c[0] + 40:                                                   # Vanilla-Motiv (cyan) entfernen
            t.px[i] = slab[1]
            continue
        t.px[i] = ramp(slab, (lum(c) - lo) / max(1.0, hi - lo))
    for x, y, c in [(7, 3, G2), (8, 3, G1), (7, 4, G3), (8, 4, G2), (6, 5, G2), (7, 5, G4), (8, 5, G3), (9, 5, G1),
                    (5, 7, G2), (6, 7, G3), (7, 6, G4), (8, 6, G3), (7, 7, CORE_HI), (8, 7, CORE_LO),
                    (9, 7, G3), (10, 7, G1), (7, 8, G3), (8, 8, G2), (6, 9, G1), (7, 9, G3), (8, 9, G2), (9, 9, G1),
                    (7, 10, G2), (8, 10, G1), (7, 11, G1), (8, 11, G0)]:
        t.set(x, y, c)
    return t


def icon_trim_template():
    """Eigene Tafel (Frostgestein, Spitze unten) mit eingelassener weißer Klinge und goldener Parierstange."""
    t = grid([
        '................',
        '.....cbbbbc.....',
        '...cbaaaaaabc...',
        '..bbaaaaaaaabd..',
        '..baaaaaaaaabd..',
        '..baaaaaaaabbd..',
        '..baaaaaaabbbd..',
        '..bbaaaaabbbdd..',
        '..dbbaaabbbbd...',
        '...dbbbbbbbdd...',
        '...ddbbbbbbd....',
        '....ddbbbbdd....',
        '.....ddbbdd.....',
        '......dddd......',
        '.......dd.......',
        '................',
    ], dict(a=hexc('#6f8199'), b=hexc('#58687f'), c=hexc('#8fa1b9'), d=hexc('#414f63')))
    # Klinge nach unten (wie auf der Brust), Parierstange als Vierstern, zwei cyanfarbene Splitter wie bei Vanilla
    for x, y, c in [(7, 2, G2), (8, 2, G1), (7, 3, GRIP_H), (8, 3, GRIP_D), (7, 4, G3), (8, 4, G2),
                    (4, 5, G1), (5, 5, G2), (6, 5, G3), (7, 5, G4), (8, 5, G4), (9, 5, G3), (10, 5, G2), (11, 5, G1),
                    (7, 6, WHITE), (8, 6, BLADE_L), (7, 7, WHITE), (8, 7, BLADE_S), (7, 8, WHITE), (8, 8, BLADE_S),
                    (7, 9, WHITE), (8, 9, BLADE_S), (7, 10, BLADE_L), (8, 10, BLADE_S), (7, 11, BLADE_L),
                    (8, 11, BLADE_S), (7, 12, BLADE_S),
                    (4, 8, hexc('#4bc9c9')), (11, 9, hexc('#209ab8'))]:
        t.set(x, y, c)
    return outline(t, hexc('#1f2633'))


def _armor_icon(name):
    def make():
        import armor_preview                                         # rendert aus dem 3D-Modell (armor_model.py)
        return armor_preview.icon(name)
    return make


ICONS = {
    'eirholm_helmet': _armor_icon('eirholm_helmet'),
    'eirholm_chestplate': _armor_icon('eirholm_chestplate'),
    'eirholm_leggings': _armor_icon('eirholm_leggings'),
    'eirholm_boots': _armor_icon('eirholm_boots'),
    'solvane': icon_solvane,
    'nivaris_wingscale': icon_wingscale,
    'eirholm_seal': icon_seal,
    'echo_of_eirholm': icon_echo,
    'heartfrost': icon_heartfrost,
    'nivaris_scale': icon_nivaris_scale,
    'eirholm_upgrade_smithing_template': icon_upgrade_template,
    'white_blade_armor_trim_smithing_template': icon_trim_template,
}


def blend_onto(px, W, ox, oy, tex, s, bg_ok=True):
    for y in range(tex.h * s):
        for x in range(tex.w * s):
            c = tex.get(x // s, y // s)
            if c[3]:
                k = (oy + y) * W + ox + x
                a = c[3] / 255.0
                px[k] = tuple(int(o + (n - o) * a) for o, n in zip(px[k], c[:3]))


def preview_icons(icons):
    names = list(icons)
    sheet([upscale(icons[n], 10) for n in names], 6, os.path.join(PREVIEW, 'icons_big.png'))
    # Inventar-Größe 1:1 und 2:1 auf Slot-Grau (GUI-Skalierung 1 und 2)
    W, H = 36 * len(names) + 4, 58
    px = [(139, 139, 139)] * (W * H)
    for n, name in enumerate(names):
        ic = icons[name]
        small = ic if ic.w <= 16 else Tex(16, 16, [ic.get(x * ic.w // 16, y * ic.h // 16) for y in range(16) for x in range(16)])
        blend_onto(px, W, 4 + n * 36 + 8, 4, small, 1)                    # GUI-Skalierung 1 (32er-Icons verkleinert)
        blend_onto(px, W, 4 + n * 36, 22, ic, max(1, 32 // ic.w))         # GUI-Skalierung 2
    write_png(os.path.join(PREVIEW, 'icons_inventory.png'), W, H, px)


# ---------------------------------------------------------------------------------------------------------------------
# HUD-Sprites (16 x 16, helle Glyphe mit dunklem Umriss wie die HHA-HUD-Icons)
# ---------------------------------------------------------------------------------------------------------------------
HUD_OUT = hexc('#0d1220')


def hud_white_line():
    t = Tex(16, 16)
    for y in range(16):
        for x in range(16):
            q, p = x + y - 15, x - y
            if abs(p) > 12:
                continue
            if q == 0:
                t.set(x, y, WHITE if abs(p) < 9 else hexc('#d8f4ff'))
            elif q == -1 and abs(p) < 11:
                t.set(x, y, hexc('#9fe6ff'))
            elif q == 1 and abs(p) < 7:
                t.set(x, y, hexc('#5fb4e0'))
    # Glanzstern in der Mitte (Vierstern mit langen senkrechten Strahlen)
    for x, y, c in [(8, 4, hexc('#9fe6ff')), (8, 5, WHITE), (8, 6, WHITE), (8, 8, WHITE), (8, 9, hexc('#9fe6ff')),
                    (7, 7, WHITE), (9, 7, WHITE)]:
        t.set(x, y, c)
    return outline(t, HUD_OUT)


def hud_frost_step():
    t = grid([
        '................',
        '................',
        '.........kkkk...',
        '.........kssk...',
        '.........kssk...',
        '.ii......kssk...',
        '.........kGsk...',
        '..iiii...kssk...',
        '.........ksssk..',
        '...iiii.ksssssk.',
        '........kssssssk',
        '.ii....kGGGGGGGk',
        '.......kkkkkkkk.',
        '...ffffffffffff.',
        '................',
        '................',
    ], dict(k=hexc('#141a26'), s=hexc('#5a7fae'), G=hexc('#e0bd62'), i=hexc('#9fe6ff'), f=hexc('#d8f4ff')))
    for x, y in ((10, 3), (10, 4), (10, 5), (10, 7), (10, 8), (10, 9), (11, 9), (11, 10)):
        t.set(x, y, hexc('#8fb0d8'))
    return outline(t, HUD_OUT)


def hud_white_core():
    t = Tex(16, 16)
    for y in range(16):
        for x in range(16):
            dx, dy = x - 7.5, y - 7.5
            r = math.hypot(dx, dy)
            if r <= 2.4:
                t.set(x, y, WHITE)
            elif r <= 3.3:
                t.set(x, y, hexc('#d8f4ff'))
            elif 4.4 <= r <= 5.5:
                t.set(x, y, hexc('#9fe6ff') if dx + dy < 1 else hexc('#5fb4e0'))
    for x, y, c in [(7, 0, hexc('#9fe6ff')), (8, 0, hexc('#9fe6ff')), (7, 1, WHITE), (8, 1, hexc('#d8f4ff')),
                    (7, 14, hexc('#d8f4ff')), (8, 14, hexc('#9fe6ff')), (7, 15, hexc('#5fb4e0')), (8, 15, hexc('#5fb4e0')),
                    (1, 7, WHITE), (1, 8, hexc('#d8f4ff')), (14, 7, hexc('#d8f4ff')), (14, 8, hexc('#9fe6ff'))]:
        t.set(x, y, c)
    return outline(t, HUD_OUT)


def hud_counter():
    """Zwei gekreuzte Klingen mit goldenem Funken (Konterhaltung)."""
    t = Tex(16, 16)
    for y in range(16):
        for x in range(16):
            a, b = x + y - 15, x - y
            if a in (0, -1) and 1 <= x <= 13 and y >= 1:
                t.set(x, y, WHITE if a == -1 else BLADE_S)                     # Klinge A (unten links → oben rechts)
            if b in (0, 1) and 2 <= x <= 14 and y <= 13:
                t.set(x, y, WHITE if b == 1 else BLADE_S)                      # Klinge B (unten rechts → oben links)
    for x, y, c in [(1, 11, G2), (2, 12, G3), (3, 13, G2), (4, 14, G1), (2, 13, GRIP_D), (1, 14, GRIP_D),
                    (14, 11, G2), (13, 12, G3), (12, 13, G2), (11, 14, G1), (13, 13, GRIP_D), (14, 14, GRIP_D)]:
        t.set(x, y, c)
    for x, y, c in [(7, 4, G2), (8, 4, G2), (7, 5, G3), (8, 5, G3), (5, 7, G2), (6, 7, G4), (7, 6, G4),
                    (8, 6, G4), (9, 7, G4), (10, 7, G2), (7, 7, WHITE), (8, 7, WHITE), (7, 8, WHITE), (8, 8, WHITE),
                    (5, 8, G2), (6, 8, G4), (9, 8, G4), (10, 8, G2), (7, 9, G4), (8, 9, G4), (7, 10, G3), (8, 10, G3),
                    (7, 11, G2), (8, 11, G2)]:
        t.set(x, y, c)
    return outline(t, HUD_OUT)


HUD = {
    'white_line': hud_white_line,
    'frost_step': hud_frost_step,
    'white_core': hud_white_core,
    'wingscale': icon_wingscale,
    'counter': hud_counter,
}


def preview_hud(hud):
    sheet([upscale(hud[n], 10) for n in hud], 5, os.path.join(PREVIEW, 'hud_big.png'))
    W, H = 30 * len(hud) + 8, 26
    px = [(24, 26, 32)] * (W * H)
    for n, name in enumerate(hud):
        blend_onto(px, W, 6 + n * 30, 5, hud[name], 1)
    write_png(os.path.join(PREVIEW, 'hud_ingame_1x.png'), W, H, px)


# ---------------------------------------------------------------------------------------------------------------------
# Mod-Icon (128 x 128): Solvane vor dem Vierstern
# ---------------------------------------------------------------------------------------------------------------------


def mod_icon():
    N, SS = 128, 4

    def rrect(x, y, inset, r):
        ax, ay = abs(x - 64) - (64 - inset - r), abs(y - 64) - (64 - inset - r)
        return math.hypot(max(ax, 0), max(ay, 0)) + min(max(ax, ay), 0) - r

    def star(x, y):
        dx, dy = abs(x - 64) / 54.0, abs(y - 62) / 58.0
        return dx ** 0.6 + dy ** 0.6

    def sword(x, y):
        """Liefert Farbe (RGB) oder None."""
        dx = x - 64
        # Klinge
        if 9 <= y <= 80:
            half = 5.2 if y > 26 else 5.2 * max(0.0, (y - 9) / 17.0) ** 0.8
            if abs(dx) <= half:
                if abs(dx) < 1.0 and y > 56:
                    return GOLD[4] if y > 70 else GOLD[3]
                return mix(WHITE, hexc('#c3cfdc'), max(0.0, dx / half)) if dx > 0 else mix(WHITE, hexc('#e9eff6'), -dx / max(half, 1))
        # Rautenstern über der Parierstange
        if abs(dx) + abs(y - 83.5) * 0.8 <= 8.5:
            return GOLD[4] if (dx < 0) == (y < 83.5) else GOLD[2]
        # Parierstange mit ausgestellten Enden
        if 80 <= y <= 87 and abs(dx) <= 30:
            return GOLD[4] if y < 82.5 else GOLD[3] if y < 85 else GOLD[2]
        if abs(dx) >= 28 and abs(dx) <= 34 and 76 <= y <= 91 and abs(abs(dx) - 31) + abs(y - 83.5) * 0.45 <= 4.5:
            return GOLD[3] if y < 83.5 else GOLD[2]
        # Griff
        if 88 <= y <= 110 and abs(dx) <= 4.2:
            return GRIP_H if (y - dx) % 7 < 2 else GRIP_D
        if 110 <= y <= 113.5 and abs(dx) <= 5.5:
            return GOLD[3]
        if abs(dx) + abs(y - 119.5) <= 6.5:
            return GOLD[4] if (dx < 0) == (y < 119.5) else GOLD[2]
        return None

    px = []
    for y in range(N):
        for x in range(N):
            acc = [0.0, 0.0, 0.0, 0.0]
            for sy in range(SS):
                for sx in range(SS):
                    fx, fy = x + (sx + 0.5) / SS, y + (sy + 0.5) / SS
                    if rrect(fx, fy, 1, 20) > 0:
                        continue
                    t = fy / N
                    c = mix(hexc('#1f2738'), hexc('#0e121b'), t)
                    g = max(0.0, 1.0 - math.hypot(fx - 64, fy - 62) / 62.0)
                    c = mix(c, hexc('#2c4466'), g * 0.8)
                    st = star(fx, fy)
                    if st <= 1.0:
                        c = mix(c, hexc('#9fe6ff'), 0.12 + 0.55 * max(0.0, 1.0 - st) ** 1.6)
                    if 0.94 <= st <= 1.0:
                        c = GOLD[2]
                    if -3.2 <= rrect(fx, fy, 5, 16) <= -1.6:
                        c = GOLD[1]
                    sc = sword(fx, fy)
                    if sc is None:
                        # dunkler Umriss um das Schwert
                        if any(sword(fx + ox, fy + oy) for ox, oy in ((1.6, 0), (-1.6, 0), (0, 1.6), (0, -1.6))):
                            c = hexc('#0a0c12')
                    else:
                        c = sc
                    acc[0] += c[0]
                    acc[1] += c[1]
                    acc[2] += c[2]
                    acc[3] += 1
            n = SS * SS
            if acc[3] == 0:
                px.append((0, 0, 0, 0))
            else:
                k = acc[3]
                px.append((int(acc[0] / k), int(acc[1] / k), int(acc[2] / k), int(255 * k / n)))
    return Tex(N, N, px)


# ---------------------------------------------------------------------------------------------------------------------
# JSON: Item-Definitionen, Modelle, Equipment, Atlas
# ---------------------------------------------------------------------------------------------------------------------
TRIM_MATERIALS = ('quartz', 'iron', 'netherite', 'redstone', 'copper', 'gold', 'emerald', 'diamond', 'lapis', 'amethyst',
                  'resin')
ARMOR_PIECES = {'eirholm_helmet': 'helmet', 'eirholm_chestplate': 'chestplate', 'eirholm_leggings': 'leggings',
                'eirholm_boots': 'boots'}


def write_json(rel, data, base=None):
    path = os.path.join(base or ASSETS, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(data, f, indent=2, ensure_ascii=False)
        f.write('\n')


def write_item_jsons(solvane_model):
    for item in ICONS:
        if item in ARMOR_PIECES:
            # Eigenes 3D-Modell: der Trim ist auf dieser Rüstung nicht sichtbar, also auch keine Trim-Varianten im Icon
            # (das 16er-Trim-Overlay passte ohnehin nicht auf das 32er-Icon). Alte Varianten werden entfernt.
            write_json(f'models/item/{item}.json', {'parent': 'minecraft:item/generated',
                                                     'textures': {'layer0': f'hha_arius:item/{item}'}})
            for m in TRIM_MATERIALS:
                stale = os.path.join(ASSETS, f'models/item/{item}_{m}_trim.json')
                if os.path.exists(stale):
                    os.remove(stale)
            write_json(f'items/{item}.json', {'model': {'type': 'minecraft:model', 'model': f'hha_arius:item/{item}'}})
        elif item == 'solvane':
            write_json('models/item/solvane.json', {'parent': 'minecraft:item/handheld',
                                                    'textures': {'layer0': 'hha_arius:item/solvane'}})
            write_json('models/item/solvane_3d.json', solvane_model)
            write_json('items/solvane.json', {'model': {
                'type': 'minecraft:select', 'property': 'minecraft:display_context',
                'cases': [{'when': ['gui', 'fixed', 'on_shelf'],
                           'model': {'type': 'minecraft:model', 'model': 'hha_arius:item/solvane'}}],
                'fallback': {'type': 'minecraft:model', 'model': 'hha_arius:item/solvane_3d'}}})
        else:
            write_json(f'models/item/{item}.json', {'parent': 'minecraft:item/generated',
                                                     'textures': {'layer0': f'hha_arius:item/{item}'}})
            write_json(f'items/{item}.json', {'model': {'type': 'minecraft:model', 'model': f'hha_arius:item/{item}'}})
    # Keine equipment/eirholm.json: der Codec verlangt mindestens eine Ebene, und ohne Datei liefert Minecraft ein leeres
    # Equipment-Modell (nichts Vanilla-Seitiges unter dem 3D-Modell, auch kein Trim). Alte Stände aufräumen.
    for rel in ('equipment/eirholm.json', 'textures/entity/equipment/humanoid/eirholm.png',
                'textures/entity/equipment/humanoid_leggings/eirholm.png',
                'textures/entity/equipment/humanoid/eirholm_glow.png',
                'textures/entity/equipment/humanoid_leggings/eirholm_glow.png'):
        if os.path.exists(os.path.join(ASSETS, rel)):
            os.remove(os.path.join(ASSETS, rel))
    write_json('atlases/armor_trims.json', TRIM_ATLAS, base=os.path.join(ROOT, 'src', 'main', 'resources', 'assets', 'minecraft'))


# ---------------------------------------------------------------------------------------------------------------------


def save_textures(jobs):
    """jobs: Liste (Tex, Pfad relativ zu assets/hha_arius). texkit benennt Batch-Jobs nach dem Dateinamen, deshalb werden
    gleichnamige Dateien (z. B. humanoid/eirholm.png und humanoid_leggings/eirholm.png) auf mehrere Läufe verteilt."""
    pending = list(jobs)
    while pending:
        seen, rest = set(), []
        for tex, rel in pending:
            name = os.path.basename(rel)
            if name in seen:
                rest.append((tex, rel))
                continue
            seen.add(name)
            texkit.save(tex.to_image(), rel)
        texkit.flush()
        pending = rest


def main(argv):
    preview_only = '--preview' in argv
    os.makedirs(PREVIEW, exist_ok=True)
    trims = build_trims()
    preview_trims(trims)
    icons = {name: fn() for name, fn in ICONS.items() if name != 'solvane'}
    solvane_icon, solvane_blade = icon_solvane(with_meta=True)
    icons['solvane'] = solvane_icon
    preview_icons(icons)
    tex3d, model3d = build_solvane_3d()
    preview_solvane(model3d, tex3d, icons['solvane'])
    hud = {name: fn() for name, fn in HUD.items()}
    preview_hud(hud)
    icon = mod_icon()
    write_png(os.path.join(PREVIEW, 'mod_icon.png'), icon.w, icon.h, icon.px)
    if preview_only:
        return

    jobs = [(trims[0], 'textures/trims/entity/humanoid/white_blade.png'),
            (trims[1], 'textures/trims/entity/humanoid_leggings/white_blade.png')]
    for name, tex in icons.items():
        if name == 'solvane':
            strip, meta = solvane_shimmer(tex, solvane_blade)
            jobs.append((strip, 'textures/item/solvane.png'))
            write_json('textures/item/solvane.png.mcmeta', meta)
        else:
            jobs.append((tex, f'textures/item/{name}.png'))
    jobs.append((tex3d['#0'], 'textures/item/solvane_3d.png'))
    jobs.append((tex3d['strip'], 'textures/item/solvane_fuller.png'))
    write_json('textures/item/solvane_fuller.png.mcmeta', {'animation': {'frametime': SOLVANE_FRAMETIME, 'interpolate': True}})
    stale = os.path.join(ASSETS, 'textures/item/solvane_3d.png.mcmeta')
    if os.path.exists(stale):
        os.remove(stale)
    jobs += [(tex, f'textures/gui/sprites/hud/{name}.png') for name, tex in hud.items()]
    jobs.append((icon, 'icon.png'))
    save_textures(jobs)
    write_item_jsons(model3d)


if __name__ == '__main__':
    main(sys.argv[1:])
