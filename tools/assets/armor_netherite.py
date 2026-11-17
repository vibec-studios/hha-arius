"""Arius-Rüstung auf Netherite-Basis (Crix 17.11.: „soll sich immer noch an der Netherite-Rüstung orientieren“).

Die Equipment-Texturen entstehen aus den Vanilla-Netherite-Texturen: Form, Plattenkanten und Nieten bleiben, die Farben
werden über die Helligkeitsstufen auf den Drachenherz-Verlauf gelegt (tiefes Navy → Eisblau → Perlweiß), die hellsten
Kantenpixel werden Altgold. Dazu kommen ein Rautenstern auf der Brust, eisblaue Fugen und eine Leuchtmaske (Fugen und
Stern). Über dieser Ebene zeichnet der 3D-Renderer nur noch Akzente (Hornkrone, Herz-Juwel, Schwingenplatten, siehe
armor_model.py ACCENTS).

Aufruf: python tools/assets/armor_netherite.py [--preview]
Schreibt equipment/eirholm.json, textures/entity/equipment/{humanoid,humanoid_leggings}/eirholm.png und die Vorschau
tmp/preview/armor/netherite_base.png.
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import gear_art as G  # noqa: E402

PREVIEW = os.path.join(G.ROOT, 'tmp', 'preview', 'armor')

RAMP = [G.hexc('#1c2538'), G.hexc('#33465f'), G.hexc('#5d7a9e'), G.hexc('#9db4cf'), G.hexc('#d3dfec'), G.hexc('#f1f6fb')]
GOLD = [G.hexc('#8a6428'), G.hexc('#c1913a'), G.hexc('#e6c46c')]
SEAM = G.hexc('#7fd3ff')


def lum(c):
    return 0.299 * c[0] + 0.587 * c[1] + 0.114 * c[2]


def recolor(src, cut_ps=(0.07, 0.16, 0.30, 0.52, 0.78), outline=None):
    """Helligkeitsränge der deckenden Pixel auf RAMP; die hellsten Pixel werden Gold (Plattenkanten von Netherite).
    outline: Farbe für die dunkelste Stufe von Vanilla (Umriss der Item-Icons)."""
    vals = sorted(lum(c) for c in src.px if c[3] > 0)
    if not vals:
        return src.copy()
    darkest = vals[0]

    def q(p):
        return vals[min(len(vals) - 1, int(p * len(vals)))]
    cuts = [q(p) for p in cut_ps]
    gold_cut = q(0.965)
    out = G.Tex(src.w, src.h)
    for y in range(src.h):
        for x in range(src.w):
            c = src.get(x, y)
            if c[3] == 0:
                continue
            v = lum(c)
            if outline is not None and v <= darkest + 22:
                out.set(x, y, outline)
                continue
            if v >= gold_cut:
                out.set(x, y, GOLD[2] if v >= q(0.99) else GOLD[1])
                continue
            i = sum(1 for k in cuts if v >= k)
            out.set(x, y, RAMP[i])
    return out


def star(t, cx, cy, glow=None):
    """Kleiner Rautenstern (Gold mit eisblauem Kern) auf der Vorderseite der Brust."""
    pts = [(0, -2, GOLD[2]), (0, -1, GOLD[1]), (-1, 0, GOLD[1]), (1, 0, GOLD[1]), (0, 1, GOLD[1]), (0, 2, GOLD[0]),
           (-2, 0, GOLD[0]), (2, 0, GOLD[0])]
    for dx, dy, c in pts:
        t.set(cx + dx, cy + dy, c)
    t.set(cx, cy, SEAM)
    if glow is not None:
        glow.set(cx, cy, SEAM)


def seams(t, glow, rects):
    """Eisblaue Fugen: in den Rechtecken (x0, y, x1) eine waagerechte Linie auf den dunkelsten Pixeln."""
    for x0, y, x1 in rects:
        for x in range(x0, x1):
            c = t.get(x, y)
            if c[3] and lum(c) < lum(RAMP[3]):
                t.set(x, y, SEAM)
                glow.set(x, y, SEAM)


def build():
    neth_h = G.Tex.load('entity/equipment/humanoid/netherite.png')
    neth_l = G.Tex.load('entity/equipment/humanoid_leggings/netherite.png')
    h = recolor(neth_h)
    l = recolor(neth_l)
    gh = G.Tex(h.w, h.h)
    gl = G.Tex(l.w, l.h)
    # Brust (Körperbox vorn: u 20..28, v 20..32): Stern unter dem Kragen, Fuge über dem Bauch.
    # Rautenstern und Herz-Juwel kommen als 3D-Akzent (armor_model.py), hier nur die Fuge.
    seams(h, gh, [(20, 27, 28)])
    # Helm vorn (u 8..16, v 8..16): Fuge als Stirnreif.
    seams(h, gh, [(8, 10, 16)])
    # Beinschienen: Fuge am Knie (Bein vorn u 4..8, v 20..32).
    seams(l, gl, [(4, 26, 8)])
    return h, l, gh, gl


def preview(h, l, gh, gl):
    os.makedirs(PREVIEW, exist_ok=True)
    _, _, skin_px = G.vanilla('entity/player/wide/steve.png')
    skin = G.Tex(64, 64, skin_px)
    panels = []
    for yaw in (25, 155, 300):
        b = G.player_boxes(skin, h, l, gh, gl)
        panels.append((300, 300, G.render_boxes(b, 300, yaw, scale=300 / 40.0)))
    neth = G.player_boxes(skin, G.Tex.load('entity/equipment/humanoid/netherite.png'), G.Tex.load('entity/equipment/humanoid_leggings/netherite.png'))
    panels.append((300, 300, G.render_boxes(neth, 300, 25, scale=300 / 40.0)))
    out = os.path.join(PREVIEW, 'netherite_base.png')
    G.sheet(panels, 4, out)
    ic = icons()
    G.sheet([G.upscale(ic[k], 10) for k in PIECES], 4, os.path.join(PREVIEW, 'icons.png'))
    print(out)


PIECES = ('helmet', 'chestplate', 'leggings', 'boots')


# Vanilla-Netherite-Töne (Item-Icons) → Drachenherz-Töne. Jede Stufe behält ihre Rolle (Umriss, Schatten, Körper,
# Licht, Kante); die hellste Kantenstufe wird Gold, damit die Icons Tiefe und Goldsaum haben statt flach weiß zu wirken.
ICON_MAP = {
    '171111': '#0f121c', '160814': '#0f121c',
    '241f20': '#3e4e6e', '271c1d': '#3e4e6e', '231012': '#3e4e6e',
    '312828': '#6a82a6', '352d2d': '#6a82a6', '373537': '#7890b2', '2f2122': '#6a82a6', '322727': '#6a82a6',
    '473e3f': '#a4b8d4', '3b393b': '#8ea5c4',
    '4d494d': '#d4e0ee', '4f3c3e': '#b4c6dd', '51444e': '#c3d2e6',
    '5d565d': '#f6f9fd', '706770': '#f6f9fd',
    '766a76': '#e6c06a', '867b86': '#f3dc9a',
}


def icons():
    """Item-Icons: die Vanilla-Netherite-Icons Ton für Ton auf den Drachenherz-Verlauf gelegt (volle Schattierung,
    goldene Kanten), dazu ein eisblaues Juwel mit Lichtpunkt."""
    out = {}
    jewel = {'helmet': [(7, 4), (8, 4)], 'chestplate': [(7, 5), (8, 5)], 'leggings': [(7, 3), (8, 3)], 'boots': []}
    for piece in PIECES:
        src = G.Tex.load('item/netherite_%s.png' % piece)
        t = G.Tex(src.w, src.h)
        for y in range(src.h):
            for x in range(src.w):
                c = src.get(x, y)
                if c[3] == 0:
                    continue
                key = '%02x%02x%02x' % c[:3]
                t.set(x, y, G.hexc(ICON_MAP.get(key, '#b4c6dd')))
        for i, (x, y) in enumerate(jewel[piece]):
            if t.get(x, y)[3]:
                t.set(x, y, G.hexc('#bfeaff') if i == 0 else G.hexc('#3e8fd0'))
        out[piece] = t
    return out


def write(h, l):
    G.save_textures([(h, 'textures/entity/equipment/humanoid/eirholm.png'),
                     (l, 'textures/entity/equipment/humanoid_leggings/eirholm.png')])
    G.save_textures([(t, 'textures/item/eirholm_%s.png' % k) for k, t in icons().items()])
    path = os.path.join(G.ASSETS, 'equipment', 'eirholm.json')
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf8', newline='\n') as f:
        json.dump({'layers': {'humanoid': [{'texture': 'hha_arius:eirholm'}],
                              'humanoid_leggings': [{'texture': 'hha_arius:eirholm'}]}}, f, indent=2)
        f.write('\n')


if __name__ == '__main__':
    h, l, gh, gl = build()
    preview(h, l, gh, gl)
    if '--preview' not in sys.argv:
        write(h, l)
