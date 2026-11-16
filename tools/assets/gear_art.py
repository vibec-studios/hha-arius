"""Grafiken für Rüstung und Items von H&H Armors: Arius (Eirholm-Wacht).

Aufruf (aus dem Projektordner):
    python tools/assets/gear_art.py            # alles erzeugen (Texturen per mctex batch, JSON direkt) + Vorschauen
    python tools/assets/gear_art.py --preview  # nur Vorschauen nach tmp/preview/gear/ (schreibt keine Assets)

Grundlage sind die Vanilla-Netherite-Texturen aus dem Loom-Cache (werden nach tmp/vanilla/ entpackt). Alles andere
wird hier in Python gerechnet. Kein PIL: PNGs werden mit zlib gelesen und für Vorschauen geschrieben.

Inhalt:
  * Eirholm-Rüstung (humanoid / humanoid_leggings) + Leuchtnähte (*_glow.png, nur Nahtpixel #9fe6ff)
  * Trim-Muster „Weiße Klinge“ (Graustufen nach Vanilla-trim_palette) + Atlas-Ergänzung armor_trims.json
  * Item-Icons, Solvane-Handmodell (3D), HUD-Sprites, Mod-Icon
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
# Rüstung
# ---------------------------------------------------------------------------------------------------------------------


def recolor_netherite(tex):
    """Bildet die 9 Netherite-Töne (nach Helligkeit sortiert) auf die Eirholm-Rampe ab."""
    cols = sorted({c for c in tex.px if c[3] > 0}, key=lum)
    lo, hi = lum(cols[0]), lum(cols[-1])
    out = tex.copy()
    for i, c in enumerate(tex.px):
        if c[3] == 0:
            continue
        t = (lum(c) - lo) / max(1.0, hi - lo)
        out.px[i] = eirholm_metal(0.08 + 0.92 * t)
    return out


def paint(tex, pts, color_fn):
    """pts: Liste (x, y, level); color_fn(level) → Farbe. Nur auf deckende Pixel (Form bleibt Netherite)."""
    for x, y, lv in pts:
        if tex.get(x, y)[3] > 0:
            tex.set(x, y, color_fn(lv))


# Verzierung (Altgold) im Layout humanoid (64 x 32). Stufen 0..4 wie gold().
HELMET_GOLD = [
    # Stirnstern: lange senkrechte Strahlen (über den Scheitel und den Nasenschutz), kurze Querstrahlen.
    (11, 5, 1), (12, 5, 1), (11, 6, 2), (12, 6, 2), (11, 7, 3), (12, 7, 2),          # Scheitel (Oberseite)
    (11, 8, 3), (12, 8, 3),
    (10, 9, 2), (11, 9, 4), (12, 9, 4), (13, 9, 2),
    (11, 10, 3), (12, 10, 3),
    (11, 11, 3), (12, 11, 2), (11, 12, 2), (12, 12, 1),                                # Nasenschutz
    # Wangenlinien an den Wangenschützern
    (8, 13, 3), (8, 14, 3), (8, 15, 2), (15, 13, 3), (15, 14, 3), (15, 15, 2),
]
CHEST_GOLD = [
    # Kragenlinien am V-Ausschnitt
    (21, 20, 3), (21, 21, 3), (22, 22, 3), (26, 20, 3), (26, 21, 3), (25, 22, 3),
    # Vierstern: oberer Strahl, Mitte mit Querstrahlen, unterer langer Strahl bis zum Herzstein
    (23, 23, 3), (24, 23, 3),
    (21, 24, 1), (22, 24, 3), (23, 24, 4), (24, 24, 4), (25, 24, 3), (26, 24, 1),
    (23, 25, 3), (24, 25, 3),
    (23, 26, 2), (24, 26, 2),
    # Herzstein (Raute) mit blauem Kern
    (23, 27, 4), (24, 27, 3),
    (22, 28, 3), (25, 28, 2),
    (23, 29, 3), (24, 29, 2),
    (23, 30, 1), (24, 30, 1),
    # Rücken: kleiner Vierstern zwischen den Schulterblättern
    (35, 22, 2), (36, 22, 2), (34, 23, 1), (35, 23, 3), (36, 23, 3), (37, 23, 1), (35, 24, 2), (36, 24, 2),
    (35, 25, 1), (36, 25, 1),
    # Schulteremblem (Außenseite des Arms, wird für den linken Arm gespiegelt)
    (41, 20, 1), (42, 20, 1), (41, 21, 3), (42, 21, 2), (40, 22, 2), (41, 22, 4), (42, 22, 4), (43, 22, 2),
    (41, 23, 3), (42, 23, 2), (41, 24, 1), (42, 24, 1),
]
CHEST_CORE = [(23, 28, CORE_HI), (24, 28, CORE_LO)]
BOOTS_GOLD = [
    # Spornlinie um die Ferse, Sporn hinten
    (0, 29, 3), (1, 29, 3), (2, 29, 2), (3, 29, 2),
    (12, 29, 3), (13, 29, 4), (14, 29, 4), (15, 29, 3), (13, 30, 3), (14, 30, 2),
]
LEGS_GOLD = [
    # Rautenfolge an der Außennaht (Außenseite x 0..3): drei kleine Rauten an einer feinen Linie
    (1, 20, 2), (2, 20, 1), (0, 21, 1), (1, 21, 4), (2, 21, 3), (3, 21, 1), (1, 22, 2), (2, 22, 1),
    (1, 23, 2), (2, 23, 1), (0, 24, 1), (1, 24, 4), (2, 24, 3), (3, 24, 1), (1, 25, 2), (2, 25, 1),
    (1, 26, 2), (2, 26, 1), (0, 27, 1), (1, 27, 4), (2, 27, 3), (3, 27, 1), (1, 28, 1), (2, 28, 0),
    # Gürtelschnalle vorn: kleine Raute
    (23, 28, 4), (24, 28, 3), (22, 29, 3), (25, 29, 2), (23, 30, 3), (24, 30, 2),
]
LEGS_CORE = [(23, 29, CORE_HI), (24, 29, CORE_LO)]

# Leuchtnähte (Eisblau). Liegen in Fugen; darunter wird die Grundtextur zur dunklen Fuge.
HUMANOID_GLOW = (
    # Helm: der Sehschlitz läuft als feine Naht um die Seiten weiter
    [(5, 11), (6, 11), (7, 11), (16, 11), (17, 11), (18, 11)]
    # Brust: untere Plattenfuge links und rechts vom Herzstein (das Licht des Kerns läuft in die Fuge)
    + [(20, 28), (21, 28), (26, 28), (27, 28)]
    # Schulterstück: Fuge unter dem Schulteremblem
    + [(41, 25), (42, 25)]
)
LEGGINGS_GLOW = (
    [(5, 23), (6, 23)]                       # Kniefuge vorn (Mitte)
)


def build_armor():
    hum = recolor_netherite(Tex.load('entity/equipment/humanoid/netherite.png'))
    leg = recolor_netherite(Tex.load('entity/equipment/humanoid_leggings/netherite.png'))
    glow_h = Tex(64, 32)
    glow_l = Tex(64, 32)
    for tex, glow, pts in ((hum, glow_h, HUMANOID_GLOW), (leg, glow_l, LEGGINGS_GLOW)):
        for x, y in pts:
            if tex.get(x, y)[3] > 0:
                tex.set(x, y, SEAM)
                glow.set(x, y, ICE)
    paint(hum, HELMET_GOLD + CHEST_GOLD + BOOTS_GOLD, gold)
    paint(leg, LEGS_GOLD, gold)
    for x, y, c in CHEST_CORE:
        hum.set(x, y, c)
    for x, y, c in LEGS_CORE:
        leg.set(x, y, c)
    return hum, leg, glow_h, glow_l


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


def preview_armor(hum, leg, glow_h, glow_l, trims):
    _, _, skin_px = vanilla('entity/player/wide/steve.png')
    skin = Tex(64, 64, skin_px)
    boxes = player_boxes(skin, hum, leg, glow_h, glow_l)
    big = [(360, 360, render_boxes(boxes, 360, yaw)) for yaw in (0, 35, 90, 180, 215)]
    sheet(big, 5, os.path.join(PREVIEW, 'armor_views.png'))
    # Spielentfernung: klein rendern und pixelig vergrößern (so wirkt es auf ~8-12 Blöcke).
    small = []
    for yaw in (0, 35, 180):
        p = render_boxes(boxes, 72, yaw, scale=72 / 40.0)
        t = Tex(72, 72, [c + (255,) for c in p])
        small.append(upscale(t, 3, checker=False))
    dark = render_boxes(boxes, 120, 20, scale=3, bg=((200, 210, 225), (235, 240, 248)))
    small.append(upscale(Tex(120, 120, [c + (255,) for c in dark]), 2, checker=False))
    sheet(small, 4, os.path.join(PREVIEW, 'armor_distance.png'))
    # Trim „Weiße Klinge“ auf Eisen- und Netherite-Rüstung (Gold, Diamant, Amethyst) und auf Eirholm (Quarz)
    panels = []
    for base_h, base_l, mat in ((Tex.load('entity/equipment/humanoid/netherite.png'), Tex.load('entity/equipment/humanoid_leggings/netherite.png'), 'gold'),
                                (Tex.load('entity/equipment/humanoid/netherite.png'), Tex.load('entity/equipment/humanoid_leggings/netherite.png'), 'diamond'),
                                (hum, leg, 'quartz'), (hum, leg, 'redstone')):
        th = apply_trim(base_h, trims[0], mat)
        tl = apply_trim(base_l, trims[1], mat)
        b = player_boxes(skin, th, tl)
        panels.append((300, 300, render_boxes(b, 300, 25, scale=300 / 40.0)))
    sheet(panels, 4, os.path.join(PREVIEW, 'trim_white_blade.png'))
    # Texturen flach
    flat = [upscale(t, 8) for t in (hum, leg, glow_h, glow_l)] + [upscale(t, 8) for t in trims]
    sheet(flat, 2, os.path.join(PREVIEW, 'armor_flat.png'))


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


def recolor_icon(rel, extra=()):
    """Netherite-Icon → Eirholm-Rampe. extra: (x, y, Farbe)."""
    t = Tex.load(rel)
    cols = sorted({c for c in t.px if c[3] > 0}, key=lum)
    lo, hi = lum(cols[0]), lum(cols[-1])
    for i, c in enumerate(t.px):
        if c[3]:
            k = (lum(c) - lo) / max(1.0, hi - lo)
            t.px[i] = eirholm_metal(0.05 + 0.95 * k) if k > 0.02 else hexc('#0c0d13')
    for x, y, c in extra:
        t.set(x, y, c)
    return t


G0, G1, G2, G3, G4 = GOLD
WHITE = hexc('#ffffff')
BLADE_L, BLADE_S = hexc('#dfe6ef'), hexc('#a9b6c8')
GRIP_D, GRIP_H = hexc('#141419'), hexc('#3a3b48')


def icon_helmet():
    return recolor_icon('item/netherite_helmet.png', [
        (7, 3, G2), (8, 3, G1), (7, 4, G3), (8, 4, G2), (6, 5, G2), (7, 5, G4), (8, 5, G3), (9, 5, G1),
        (7, 6, G3), (8, 6, G2), (7, 7, G2), (8, 7, G1), (7, 8, G1), (8, 8, G0),
        (5, 9, G3), (5, 10, G2), (10, 9, G2), (10, 10, G1),
    ])


def icon_chestplate():
    return recolor_icon('item/netherite_chestplate.png', [
        (5, 4, G3), (6, 5, G3), (10, 4, G2), (9, 5, G2),
        (3, 3, G2), (12, 3, G1),
        (7, 6, G3), (8, 6, G2),
        (6, 7, G2), (7, 7, G4), (8, 7, G3), (9, 7, G1),
        (7, 8, G3), (8, 8, G2),
        (7, 9, G4), (8, 9, G3), (6, 10, G3), (7, 10, CORE_HI), (8, 10, CORE_LO), (9, 10, G2), (7, 11, G3), (8, 11, G2),
        (7, 12, G1), (8, 12, G1),
        (5, 10, ICE), (10, 10, ICE),
    ])


def icon_leggings():
    return recolor_icon('item/netherite_leggings.png', [
        (7, 2, G3), (8, 2, G2), (7, 3, CORE_HI), (8, 3, CORE_LO), (6, 3, G2), (9, 3, G1), (7, 4, G2), (8, 4, G1),
        (4, 5, G1), (4, 6, G3), (4, 7, G1), (4, 8, G1), (4, 9, G3), (4, 10, G1),
        (11, 5, G1), (11, 6, G2), (11, 7, G1), (11, 8, G1), (11, 9, G2), (11, 10, G1),
        (5, 8, ICE), (10, 8, ICE),
    ])


def icon_boots():
    return recolor_icon('item/netherite_boots.png', [
        (3, 4, G3), (3, 5, G2), (12, 4, G2), (12, 5, G1),
        (2, 10, G3), (3, 10, G2), (1, 11, G4), (2, 11, G2),
        (13, 10, G2), (12, 10, G1), (14, 11, G3), (13, 11, G2),
    ])


def icon_solvane():
    """Diagonal (Knauf unten links, Spitze oben rechts). p = x - y (Längsachse), q = x + y - 15 (Querachse)."""
    t = Tex(16, 16)
    for y in range(16):
        for x in range(16):
            p, q = x - y, x + y - 15
            c = None
            if abs(q) <= 1 and p >= -3 and p + abs(q) <= 13:                  # Klinge
                c = {-1: WHITE, 0: BLADE_L, 1: BLADE_S}[q]
                if q == 0 and p <= 1:
                    c = G4 if p <= -1 else G3                                 # goldener Strahl an der Klingenwurzel
            if p in (-4, -5) and abs(q) <= 5:                                  # Parierstange
                c = G3 if p == -4 else G1
                if abs(q) == 5:
                    c = G2                                                     # Enden
            if (p, q) in ((-4, -1), (-3, 0), (-5, 0), (-4, 1), (-6, 1), (-6, -1)):
                c = {(-5, 0): G4, (-4, -1): G4, (-3, 0): G3}.get((p, q), G2)   # Rautenstern
            if -11 <= p <= -7 and abs(q) <= 1 and c is None:                  # Griff, schwarz gewickelt
                c = GRIP_H if (q == 0 and p % 4 == 1) or (q == -1 and p % 4 == 2) else GRIP_D
            if (p, q) in ((-13, 0), (-12, -1), (-12, 1), (-14, -1), (-14, 1), (-15, 0)):
                c = {(-13, 0): G4, (-12, -1): G3, (-14, -1): G3, (-15, 0): G1}.get((p, q), G2)  # Rautenknauf
            if c is not None:
                t.set(x, y, c)

    def oc(src, x, y):
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            n = src.get(x + dx, y + dy)
            if n[3] and n[:3] in (WHITE[:3], BLADE_L[:3], BLADE_S[:3]):
                return hexc('#2b3346')
        return hexc('#1a140c')
    return outline(t, oc)


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


ICONS = {
    'eirholm_helmet': icon_helmet,
    'eirholm_chestplate': icon_chestplate,
    'eirholm_leggings': icon_leggings,
    'eirholm_boots': icon_boots,
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
        blend_onto(px, W, 4 + n * 36 + 8, 4, icons[name], 1)
        blend_onto(px, W, 4 + n * 36, 22, icons[name], 2)
    write_png(os.path.join(PREVIEW, 'icons_inventory.png'), W, H, px)


def solvane_shimmer(base):
    """Dezenter Schimmer fürs GUI-Icon: ein Glanzpunkt wandert die Klinge hinauf, dann lange Ruhe (~4 s)."""
    frames = [base]
    for k in range(6):
        f = base.copy()
        p0 = -1 + k * 2.6
        for y in range(16):
            for x in range(16):
                p, q = x - y, x + y - 15
                c = base.get(x, y)
                if not c[3] or abs(q) > 1 or c[:3] not in (WHITE[:3], BLADE_L[:3], BLADE_S[:3]):
                    continue
                d = abs(p - p0)
                if d < 1.5:
                    f.set(x, y, WHITE if q < 1 else hexc('#e4f6ff'))
                elif d < 3 and q == 1:
                    f.set(x, y, hexc('#cfe0ee'))
        frames.append(f)
    strip = Tex(16, 16 * len(frames))
    for n, f in enumerate(frames):
        for y in range(16):
            for x in range(16):
                strip.set(x, n * 16 + y, f.get(x, y))
    meta = {'animation': {'frametime': 2, 'frames': [{'index': 0, 'time': 70}] + list(range(1, len(frames)))}}
    return strip, meta


# ---------------------------------------------------------------------------------------------------------------------
# Solvane als Elemente-Modell (Hand, Boden, Kopf). GUI/Rahmen/Regal zeigen das flache Icon.
# ---------------------------------------------------------------------------------------------------------------------
# Schwert-Rahmen: senkrecht entlang y, Mitte x = z = 8. Alle Elemente werden um -45° (z) um (8, 8, 8) gedreht und liegen
# dann diagonal wie das Icon (Knauf unten links, Spitze oben rechts). Die beiden Rauten (Parierstange, Knauf) sind im
# Schwert-Rahmen um 45° gedreht, also im Modell achsparallel: sie werden direkt an der gedrehten Stelle gesetzt.
SOLVANE_PARTS = [
    # name, from, to, gedreht?
    ('blade', (6.5, 4, 7.5), (9.5, 14, 8.5), True),
    ('blade_mid', (7, 14, 7.5), (9, 17, 8.5), True),
    ('blade_tip', (7.5, 17, 7.5), (8.5, 19, 8.5), True),
    ('guard', (3, 3, 7), (13, 4, 9), True),
    ('guard_end', (2, 2, 7), (3, 5, 9), True),
    ('guard_end', (13, 2, 7), (14, 5, 9), True),
    ('grip', (7, -2, 7), (9, 3, 9), True),
    ('collar', (6.5, -3, 6.5), (9.5, -2, 9.5), True),
    ('guard_star', 'diamond', (8, 3.5), (2, 2, 3)),
    ('pommel', 'diamond', (8, -4.0), (2, 2, 2)),
]
TEX3D = 32           # Texturgröße; 1 Pixel = 1 Modelleinheit → UV = Pixel / 2
COS45 = math.sqrt(0.5)


def sword_to_model(x, y):
    dx, dy = x - 8.0, y - 8.0
    return 8 + COS45 * (dx + dy), 8 + COS45 * (dy - dx)


def solvane_elements():
    out = []
    for part in SOLVANE_PARTS:
        name = part[0]
        if part[1] == 'diamond':
            (cx, cy), (w, h, d) = part[2], part[3]
            mx, my = sword_to_model(cx, cy)
            fr = (round(mx - w / 2, 4), round(my - h / 2, 4), 8 - d / 2)
            to = (round(mx + w / 2, 4), round(my + h / 2, 4), 8 + d / 2)
            out.append((name, fr, to, False))
        else:
            out.append((name, part[1], part[2], part[3]))
    return out


def face_dims(fr, to, face):
    w, h, d = (to[i] - fr[i] for i in range(3))
    return {'north': (w, h), 'south': (w, h), 'east': (d, h), 'west': (d, h), 'up': (w, d), 'down': (w, d)}[face]


def paint_solvane(name, face, i, j, fw, fh):
    """Farbe eines Texels; i nach rechts, j nach unten (bei Seitenflächen j = 0 oben)."""
    side = face in ('east', 'west')
    if name.startswith('blade'):
        if face == 'up':
            return WHITE
        if side:
            return WHITE if j % 4 else hexc('#eef4fa')                      # Schneide
        col = [hexc('#f7fafd'), BLADE_L, hexc('#c3cfdc')]
        c = col[min(2, i)] if fw == 3 else (hexc('#f2f6fb') if i == 0 else hexc('#cfd9e5'))
        if fw == 1:
            c = BLADE_L
        if name == 'blade' and fw == 3 and i == 1:
            if j >= fh - 2:
                c = G4 if j == fh - 1 else G3                                # goldener Strahl an der Klingenwurzel
            elif j >= fh - 4:
                c = G2
        if name == 'blade' and j < 3 and fw == 3:
            c = mix(c, WHITE, 0.4)
        return c
    if name in ('guard', 'guard_end'):
        if face == 'up':
            return G4
        if face == 'down':
            return G1
        if side:
            return G2
        if name == 'guard_end':
            return G3 if j == 1 else G2
        return G2 if i in (0, fw - 1) else G3
    if name in ('guard_star', 'pommel'):
        if face == 'up':
            return G4
        if face == 'down':
            return G1
        if side:
            return G2 if (i + j) % 2 else G3
        return [[G4, G3], [G3, G2]][min(1, j)][min(1, i)]
    if name == 'grip':
        if face in ('up', 'down'):
            return G2
        if j == 0:
            return G3                                                        # Zunge der Parierstange (Vierstern)
        return GRIP_H if (i + j) % 3 == 0 else GRIP_D
    if name == 'collar':
        return G3 if face == 'up' else G2 if face != 'down' else G1
    return hexc('#ff00ff')


def build_solvane_3d():
    """Packt alle Flächen in eine 32 x 32-Textur und liefert (Tex, Modell-JSON)."""
    tex = Tex(TEX3D, TEX3D)
    elements = []
    cx = cy = row_h = 0
    for name, fr, to, rotated in solvane_elements():
        faces = {}
        for face in ('north', 'east', 'south', 'west', 'up', 'down'):
            fw, fh = face_dims(fr, to, face)
            pw, ph = int(math.ceil(fw - 1e-6)), int(math.ceil(fh - 1e-6))
            if cx + pw > TEX3D:
                cx, cy, row_h = 0, cy + row_h, 0
            assert cy + ph <= TEX3D, 'Solvane-Textur zu klein'
            for j in range(ph):
                for i in range(pw):
                    tex.set(cx + i, cy + j, paint_solvane(name, face, i, j, pw, ph))
            s = 16.0 / TEX3D
            faces[face] = {'uv': [cx * s, cy * s, (cx + fw) * s, (cy + fh) * s], 'texture': '#0'}
            cx += pw
            row_h = max(row_h, ph)
        el = {'name': name, 'from': list(fr), 'to': list(to), 'faces': faces}
        if rotated:
            el['rotation'] = {'angle': -45, 'axis': 'z', 'origin': [8, 8, 8]}
        elements.append(el)
    model = {
        'textures': {'0': 'hha_arius:item/solvane_3d', 'particle': 'hha_arius:item/solvane'},
        'elements': elements,
        # Haltung wie das Blood-Schwert (gleiche Griffhöhe im Schwert-Rahmen: Griff y -2..3)
        'display': {
            'thirdperson_righthand': {'rotation': [0, -90, 25], 'translation': [0, 5.08, 4.17], 'scale': [0.95, 0.95, 0.95]},
            'thirdperson_lefthand': {'rotation': [0, 90, -25], 'translation': [0, 5.08, 4.17], 'scale': [0.95, 0.95, 0.95]},
            'firstperson_righthand': {'rotation': [0, -90, 25], 'translation': [1.13, 3.2, 1.13], 'scale': [0.616, 0.616, 0.616]},
            'firstperson_lefthand': {'rotation': [0, 90, -25], 'translation': [1.13, 3.2, 1.13], 'scale': [0.616, 0.616, 0.616]},
            'ground': {'rotation': [0, 0, 0], 'translation': [0, 2, 0], 'scale': [0.42, 0.42, 0.42]},
            'head': {'rotation': [0, 180, 0], 'translation': [0, 13, 7], 'scale': [0.9, 0.9, 0.9]},
            'gui': {'rotation': [0, 0, 0], 'translation': [0, 0, 0], 'scale': [0.62, 0.62, 0.62]},
            'fixed': {'rotation': [0, 180, 0], 'scale': [0.62, 0.62, 0.62]},
        },
    }
    return tex, model


def render_model(model, tex, size, yaw_deg, pitch_deg=0.0, scale=None, center=(8, 8, 8)):
    """Vorschau eines Elemente-Modells (Modellraum: y oben). Kamera blickt von Süden (+z) nach Norden."""
    ya, pa = math.radians(yaw_deg), math.radians(pitch_deg)
    W = H = size
    pix = []
    for y in range(H):
        t = y / (H - 1)
        pix.extend([(int(36 + 30 * t), int(40 + 32 * t), int(52 + 36 * t))] * W)
    depth = [1e9] * (W * H)
    scale = scale or size / 34.0
    light = (-0.4, 0.75, 0.55)
    ln = math.sqrt(sum(c * c for c in light))
    light = tuple(c / ln for c in light)

    def rot_el(p, el):
        r = el.get('rotation')
        if not r:
            return p
        a = math.radians(r['angle'])
        ox, oy, oz = r['origin']
        x, y, z = p[0] - ox, p[1] - oy, p[2] - oz
        if r['axis'] == 'z':
            x, y = x * math.cos(a) - y * math.sin(a), x * math.sin(a) + y * math.cos(a)
        return x + ox, y + oy, z + oz

    def view(p):
        x, y, z = p[0] - center[0], p[1] - center[1], p[2] - center[2]
        x, z = x * math.cos(ya) + z * math.sin(ya), -x * math.sin(ya) + z * math.cos(ya)
        y, z = y * math.cos(pa) - z * math.sin(pa), y * math.sin(pa) + z * math.cos(pa)
        return x, y, z

    for el in model['elements']:
        fr, to = el['from'], el['to']
        for face, f in el['faces'].items():
            u0, v0, u1, v1 = (c * TEX3D / 16.0 for c in f['uv'])
            fw, fh = face_dims(fr, to, face)
            # (Ursprung oben links aus Sicht der Fläche, Richtung i, Richtung j, Normale)
            x0, y0, z0 = fr
            x1, y1, z1 = to
            spec = {
                'south': ((x0, y1, z1), (1, 0, 0), (0, -1, 0), (0, 0, 1)),
                'north': ((x1, y1, z0), (-1, 0, 0), (0, -1, 0), (0, 0, -1)),
                'east': ((x1, y1, z1), (0, 0, -1), (0, -1, 0), (1, 0, 0)),
                'west': ((x0, y1, z0), (0, 0, 1), (0, -1, 0), (-1, 0, 0)),
                'up': ((x0, y1, z0), (1, 0, 0), (0, 0, 1), (0, 1, 0)),
                'down': ((x0, y0, z1), (1, 0, 0), (0, 0, -1), (0, -1, 0)),
            }[face]
            o, du, dv, n = spec
            nn = rot_el((n[0] + 8, n[1] + 8, n[2] + 8), el)
            nr = view((nn[0] - 8 + center[0], nn[1] - 8 + center[1], nn[2] - 8 + center[2]))
            # nur zur Kamera gewandte Flächen (z der Normalen nach Ansicht > 0 → zeigt zur Kamera)
            shade = 0.6 + 0.4 * max(0.0, sum(a * b for a, b in zip((nn[0] - 8, nn[1] - 8, nn[2] - 8), light)))
            steps = max(2, int(scale * 1.6))
            for j in range(int(math.ceil(fh * steps))):
                for i in range(int(math.ceil(fw * steps))):
                    fi, fj = (i + 0.5) / steps, (j + 0.5) / steps
                    if fi > fw or fj > fh:
                        continue
                    c = tex.get(int(u0 + fi * (u1 - u0) / fw), int(v0 + fj * (v1 - v0) / fh))
                    if not c[3]:
                        continue
                    p = (o[0] + du[0] * fi + dv[0] * fj, o[1] + du[1] * fi + dv[1] * fj, o[2] + du[2] * fi + dv[2] * fj)
                    xr, yr, zr = view(rot_el(p, el))
                    sx, sy = int(W / 2 + xr * scale), int(H / 2 - yr * scale)
                    if 0 <= sx < W and 0 <= sy < H and -zr < depth[sy * W + sx]:
                        depth[sy * W + sx] = -zr
                        pix[sy * W + sx] = tuple(int(ch * shade) for ch in c[:3])
    return pix


def preview_solvane(model, tex, icon):
    panels = [(300, 300, render_model(model, tex, 300, yaw)) for yaw in (0, 40, 90, 160)]
    panels.append(upscale(tex, 9))
    panels.append(upscale(icon, 18))
    sheet(panels, 3, os.path.join(PREVIEW, 'solvane_3d.png'))


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
            piece = ARMOR_PIECES[item]
            write_json(f'models/item/{item}.json', {'parent': 'minecraft:item/generated',
                                                     'textures': {'layer0': f'hha_arius:item/{item}'}})
            for m in TRIM_MATERIALS:
                write_json(f'models/item/{item}_{m}_trim.json', {
                    'parent': 'minecraft:item/generated',
                    'textures': {'layer0': f'hha_arius:item/{item}', 'layer1': f'minecraft:trims/items/{piece}_trim_{m}'}})
            write_json(f'items/{item}.json', {'model': {
                'type': 'minecraft:select', 'property': 'minecraft:trim_material',
                'cases': [{'when': f'minecraft:{m}', 'model': {'type': 'minecraft:model', 'model': f'hha_arius:item/{item}_{m}_trim'}}
                          for m in TRIM_MATERIALS],
                'fallback': {'type': 'minecraft:model', 'model': f'hha_arius:item/{item}'}}})
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
    write_json('equipment/eirholm.json', {'layers': {
        'humanoid': [{'texture': 'hha_arius:eirholm'}],
        'humanoid_leggings': [{'texture': 'hha_arius:eirholm'}]}})
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
    hum, leg, glow_h, glow_l = build_armor()
    trims = build_trims()
    preview_armor(hum, leg, glow_h, glow_l, trims)
    icons = {name: fn() for name, fn in ICONS.items()}
    preview_icons(icons)
    tex3d, model3d = build_solvane_3d()
    preview_solvane(model3d, tex3d, icons['solvane'])
    hud = {name: fn() for name, fn in HUD.items()}
    preview_hud(hud)
    icon = mod_icon()
    write_png(os.path.join(PREVIEW, 'mod_icon.png'), icon.w, icon.h, icon.px)
    if preview_only:
        return

    jobs = [(hum, 'textures/entity/equipment/humanoid/eirholm.png'),
            (leg, 'textures/entity/equipment/humanoid_leggings/eirholm.png'),
            (glow_h, 'textures/entity/equipment/humanoid/eirholm_glow.png'),
            (glow_l, 'textures/entity/equipment/humanoid_leggings/eirholm_glow.png'),
            (trims[0], 'textures/trims/entity/humanoid/white_blade.png'),
            (trims[1], 'textures/trims/entity/humanoid_leggings/white_blade.png')]
    for name, tex in icons.items():
        if name == 'solvane':
            strip, meta = solvane_shimmer(tex)
            jobs.append((strip, 'textures/item/solvane.png'))
            write_json('textures/item/solvane.png.mcmeta', meta)
        else:
            jobs.append((tex, f'textures/item/{name}.png'))
    jobs.append((tex3d, 'textures/item/solvane_3d.png'))
    jobs += [(tex, f'textures/gui/sprites/hud/{name}.png') for name, tex in hud.items()]
    jobs.append((icon, 'icon.png'))
    save_textures(jobs)
    write_item_jsons(model3d)


if __name__ == '__main__':
    main(sys.argv[1:])
