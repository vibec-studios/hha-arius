"""Sounddesign für den Arius-Trailer: synthetische Effekte (keine Samples) plus die eigenen Klangeffekte der Mod
(assets/hha_arius/sounds/sfx, ebenfalls von arius_sounds.py synthetisiert). Keine Minecraft-/Mojang-Klänge.

    from trailer_sfx import make
    stereo = make('whoosh', seed=3)       # numpy (n, 2), 44,1 kHz, Spitze ~0,9

Namen siehe SFX unten. Jeder Effekt ist auf eine ähnliche Spitze normiert; die Lautstärke regelt der Mischer.
"""
import os
import subprocess

import numpy as np

from arius_kit import (
    RATE, SOUNDS, bandpass, crackle, cymbal, glass, highpass, hz, ice_ring, lowpass, noise, partials, samples, sweep,
    taiko, timeline, tinks,
)

SFX_DIR = os.path.join(SOUNDS, 'sfx')


def _st(mono, width=0.0, seed=0):
    """Mono → Stereo, optional mit leichter Breite (kurz verzögerte, gefilterte Kopie)."""
    if width <= 0:
        return np.stack([mono, mono], axis=1)
    d = samples(0.011 + 0.006 * (seed % 3))
    side = np.concatenate([np.zeros(d), mono[:-d]]) if d < len(mono) else mono
    return np.stack([mono + side * width * 0.5, mono - side * width * 0.5 + mono * 0.0], axis=1)


def _norm(x, peak=0.9):
    m = np.max(np.abs(x))
    return x * (peak / m) if m > 0 else x


def mod_sfx(name, v=0):
    """Eigener Spiel-Klang der Mod, z. B. mod_sfx('arius_slash', 1)."""
    variants = sorted(f for f in os.listdir(SFX_DIR) if f.startswith(name + '_') and f[len(name) + 1:-4].isdigit())
    if not variants:
        raise FileNotFoundError(name)
    path = os.path.join(SFX_DIR, variants[v % len(variants)])
    raw = subprocess.run(['ffmpeg', '-v', 'error', '-i', path, '-ac', '1', '-ar', str(RATE), '-f', 'f32le', '-'],
                         capture_output=True).stdout
    return _st(np.frombuffer(raw, dtype='<f4').astype(np.float64), 0.6)


# --- Bausteine -----------------------------------------------------------------------------------------------------

def whoosh(seconds=0.45, lo=500.0, hi=4200.0, seed=1, peak_at=0.6, bright=1.0):
    """Luftzug: Rauschband, das hochzieht und mit dem Höhepunkt kurz vor dem Ende abreißt (Schnitt-Whoosh)."""
    n = samples(seconds)
    k = np.linspace(0, 1, n)
    x = noise(seconds, seed)
    x = sweep(x, lambda f, kk: np.exp(-(((f - (lo + (hi - lo) * kk ** 1.4)) / (500 + 1400 * kk * bright)) ** 2)))
    env = np.where(k < peak_at, (k / peak_at) ** 2.2, np.exp(-(k - peak_at) / max(1e-3, (1 - peak_at)) * 4.5))
    return _norm(_st(x * env, 0.8, seed))


def thump(seconds=1.2, f0=120.0, f1=38.0, decay=4.0, click=0.3, seed=2):
    """Tiefer Körperschlag mit Pitch-Fall und etwas Anschlag."""
    t = timeline(seconds)
    f = f1 + (f0 - f1) * np.exp(-t * 14.0)
    phase = 2 * np.pi * np.cumsum(f) / RATE
    body = np.sin(phase) * np.exp(-t * decay) * np.minimum(1.0, t / 0.002)
    hit = highpass(noise(seconds, seed), 1200) * np.exp(-t * 60.0) * click
    return _norm(_st(body + hit))


def boom(seconds=3.0, sub=34.0, seed=5):
    """Kino-Einschlag: Taiko-Doppelschlag + Sub-Fall + Rauschkörper, langer Ausklang."""
    t = timeline(seconds)
    f = sub + 50.0 * np.exp(-t * 7.0)
    subw = np.sin(2 * np.pi * np.cumsum(f) / RATE) * np.exp(-t * 1.3) * np.minimum(1.0, t / 0.003)
    body = lowpass(noise(seconds, seed), 900) * np.exp(-t * 3.5) * 0.6
    out = _st(subw * 0.9 + body)
    tk = taiko(46.0, min(seconds, 2.0), seed)
    out[:len(tk)] += tk * 0.8
    return _norm(out)


def crash(seconds=3.5, seed=0):
    return _norm(cymbal(seconds) * 1.0)


def reverse_cymbal(seconds=1.4):
    """Rückwärts-Becken, endet genau am Ende des Puffers (also VOR dem Ereignis platzieren)."""
    c = cymbal(seconds + 0.6)[::-1]
    c = c[-samples(seconds):]
    k = np.linspace(0, 1, len(c))[:, None]
    return _norm(c * k ** 1.5)


def riser(seconds=8.0, f0=110.0, octaves=2.0, seed=7):
    """Shepard-artiger Riser: gestapelte Sägezahn-Töne gleiten nach oben, Rauschen wird heller, wird lauter."""
    n = samples(seconds)
    t = np.arange(n) / RATE
    k = t / seconds
    out = np.zeros(n)
    for layer in range(4):
        base = f0 * (2 ** layer) * 2 ** (octaves * k)
        ph = 2 * np.pi * np.cumsum(base) / RATE
        saw = sum(np.sin(ph * h) / h for h in range(1, 7))
        w = np.exp(-((np.log2(base / 440.0)) ** 2) / 2.0)  # Glocke über der Tonhöhe (Shepard)
        out += saw * w * 0.25
    hiss = sweep(noise(seconds, seed), lambda f, kk: np.exp(-(((f - (800 + 7000 * kk ** 2)) / (600 + 3000 * kk)) ** 2)))
    out = out * 0.6 + hiss * 0.8
    trem = 1.0 + 0.25 * np.sin(2 * np.pi * (4 + 12 * k ** 2) * t)  # Tremolo wird schneller
    return _norm(_st(out * trem * k ** 2.0, 0.9, seed))


def suck(seconds=1.2, seed=9):
    """Sog vor dem Schlag: Rauschen + tiefer Ton, rückwärts anschwellend, endet abrupt."""
    t = timeline(seconds)
    k = t / seconds
    x = sweep(noise(seconds, seed), lambda f, kk: np.exp(-(((f - (3000 * (1 - kk) + 300)) / (900 + 600 * kk)) ** 2)))
    low = np.sin(2 * np.pi * np.cumsum(55 + 40 * k) / RATE) * 0.6
    return _norm(_st((x + low) * k ** 3, 0.6, seed))


def sub_drop(seconds=3.0, f0=90.0, f1=28.0):
    t = timeline(seconds)
    f = f1 + (f0 - f1) * np.exp(-t * 2.2)
    x = np.sin(2 * np.pi * np.cumsum(f) / RATE) * np.exp(-t * 0.9) * np.minimum(1.0, t / 0.01)
    x += 0.15 * np.sin(2 * np.pi * np.cumsum(2 * f) / RATE) * np.exp(-t * 1.5)
    return _norm(_st(x))


def breath(seconds=2.4, seed=11):
    """Ruhiger Atemzug (ein, aus): gefiltertes Rauschen mit Formant."""
    t = timeline(seconds)
    k = t / seconds
    env = np.sin(np.pi * np.clip(k / 0.45, 0, 1)) * (k < 0.45) + 0.7 * np.sin(np.pi * np.clip((k - 0.5) / 0.5, 0, 1)) * (k >= 0.5)
    x = bandpass(noise(seconds, seed), 1100, 700) * 0.7 + bandpass(noise(seconds, seed + 1), 450, 250) * 0.5
    return _norm(_st(x * env, 0.4, seed))


def wind_bed(seconds=14.0, seed=13):
    """Kalter Wind mit Böen und hohem Pfeifen."""
    n = samples(seconds)
    t = np.arange(n) / RATE
    out = np.zeros((n, 2))
    for c in range(2):
        x = sweep(noise(seconds, seed + c), lambda f, kk: np.exp(-(((f - (700 + 500 * np.sin(6.0 * kk + c))) / 900) ** 2)))
        gust = 0.55 + 0.3 * np.sin(2 * np.pi * 0.13 * t + c) + 0.15 * np.sin(2 * np.pi * 0.31 * t + 2 * c)
        whistle = bandpass(noise(seconds, seed + 7 + c), 2300 + 200 * c, 60) * 1.2 * (0.5 + 0.5 * np.sin(2 * np.pi * 0.07 * t + c))
        out[:, c] = x * gust + whistle * 0.35
    fade = np.minimum(1.0, t / 2.0) * np.minimum(1.0, (seconds - t) / 2.0)
    return _norm(out * fade[:, None], 0.7)


def bell(freq=hz('B5'), seconds=5.0):
    """Eine kalte Glocke (Glas) mit zweiter, leiser Quinte."""
    g = glass(freq, seconds) + glass(freq * 1.5, seconds) * 0.25
    return _norm(g)


def glint(seconds=1.6, seed=3):
    gen = np.random.default_rng(seed)
    t = timeline(seconds)
    x = ice_ring(t, 2600.0, gen, bright=0.8, decay=0.6, count=6)
    return _norm(_st(x * np.minimum(1.0, t / 0.004), 0.7, seed))


def sword_ring(seconds=2.2, seed=4):
    """Klinge: scharfer Luftschnitt, dann metallisches Singen (unharmonische Teiltöne, langer Ausklang)."""
    t = timeline(seconds)
    ring = partials(t, [(1180, 1.0, 1.4), (1186, 0.6, 1.5), (2950, 0.5, 2.6), (4410, 0.35, 3.8), (6230, 0.2, 5.0),
                        (8030, 0.12, 7.0)], seed)
    air = highpass(noise(seconds, seed), 2500) * np.exp(-t * 40)
    x = ring * np.minimum(1.0, t / 0.002) * 0.7 + air
    out = _st(x, 0.7, seed)
    w = whoosh(0.22, 1500, 7000, seed, 0.95)
    lead = samples(0.18)
    full = np.zeros((len(out) + lead, 2))
    full[:len(w)] += w * 0.6
    full[lead:] += out
    return _norm(full)


def wood_split(seconds=1.4, seed=6):
    gen = np.random.default_rng(seed)
    n = samples(seconds)
    t = np.arange(n) / RATE
    env = np.exp(-t * 6.0)
    cr = crackle(n, gen, 900, env, 1800, 1500, 0.006)
    body = thump(seconds, 220, 70, 9.0, 0.6, seed)[:, 0]
    return _norm(_st(cr * 2.0 + body * 0.7, 0.6, seed))


def ice_break(seconds=1.8, seed=8):
    gen = np.random.default_rng(seed)
    n = samples(seconds)
    t = np.arange(n) / RATE
    x = tinks(n, gen, 60, 0.0, 0.9, 1800, 6500, (0.05, 0.3), (25, 70), 2.2)
    x += crackle(n, gen, 1200, np.exp(-t * 8), 3500, 2500, 0.003) * 1.5
    x += ice_ring(t, 1500.0, gen, 0.7, 1.4) * 0.4
    return _norm(_st(x, 0.8, seed))


def spell_wind(seconds=0.9, seed=21):
    a = whoosh(seconds, 300, 3000, seed, 0.35, 1.6)
    b = whoosh(seconds * 0.8, 900, 5200, seed + 1, 0.3, 1.2)
    a[:len(b)] += b * 0.6
    return _norm(a)


def spell_ice(seconds=1.2, seed=22):
    gen = np.random.default_rng(seed)
    n = samples(seconds)
    x = tinks(n, gen, 26, 0.0, 0.5, 2500, 7000, (0.08, 0.35), (20, 50), 1.5)
    w = whoosh(seconds, 1200, 6000, seed, 0.25)[:, 0] * 0.5
    return _norm(_st(x + w[:n], 0.8, seed))


def glow(seconds=1.6, f=hz('E5'), seed=23):
    """Yang-Glühen: warmer Akkord aus Glas und Sinus, schwillt an."""
    t = timeline(seconds)
    k = t / seconds
    tone = sum(np.sin(2 * np.pi * f * r * t) * g for r, g in ((1.0, 1.0), (1.5, 0.5), (2.0, 0.35), (3.0, 0.15)))
    shimmer = highpass(noise(seconds, seed), 6000) * 0.08
    return _norm(_st((tone * 0.4 + shimmer) * np.sin(np.pi * np.clip(k, 0, 1)) ** 0.7, 0.6, seed))


def light_build(seconds=1.6, seed=24):
    """Lichtbau: aufsteigendes Glas-Arpeggio plus Glühen (Brücke, Plattform)."""
    out = glow(seconds, hz('B4'), seed) * 0.5
    for i, note in enumerate(['E5', 'G#5', 'B5', 'E6', 'G#6']):
        g = glass(hz(note), 1.5) * 0.5
        at = samples(0.09 * i)
        end = min(len(out), at + len(g))
        out[at:end] += g[:end - at]
    return _norm(out)


def shimmer(seconds=1.8, seed=25):
    out = np.zeros((samples(seconds), 2))
    for i, note in enumerate(['B5', 'E6', 'F#6', 'B6']):
        g = glass(hz(note), 1.6) * (0.6 - i * 0.08)
        at = samples(0.05 * i)
        end = min(len(out), at + len(g))
        out[at:end] += g[:end - at]
    return _norm(out)


def ui_glass(seconds=0.6):
    g = glass(hz('E6'), seconds) + glass(hz('B6'), seconds) * 0.5
    return _norm(g)


def glide_wind(seconds=1.6, seed=26):
    a = whoosh(seconds, 250, 1800, seed, 0.5, 0.8)
    return _norm(a + glow(seconds, hz('E5'), seed) * 0.25)


def stream(seconds=1.4, seed=27):
    """Lichtstrom: heller Whoosh mit Glas-Glissando."""
    a = whoosh(seconds, 800, 6500, seed, 0.55, 1.3)
    return _norm(a + light_build(seconds, seed) * 0.5)


def title_hit(seconds=6.0, seed=31):
    out = boom(seconds, 30.0, seed)
    c = crash(seconds, seed)
    out[:len(c)] += c * 0.45
    tk = taiko(58.0, 1.5, seed + 1)
    out[:len(tk)] += tk * 0.5
    return _norm(out)


def hit_big(seconds=2.4, seed=33):
    out = boom(seconds, 36.0, seed) * 0.85
    c = crash(min(seconds, 2.4), seed)
    out[:len(c)] += c * 0.35
    s = whoosh(0.18, 2000, 8000, seed, 0.1)
    out[:len(s)] += s * 0.4
    return _norm(out)


def hit_small(seconds=0.9, seed=34):
    out = thump(seconds, 180, 60, 7.0, 0.8, seed)
    return _norm(out)


def collapse(seconds=3.2, seed=35):
    gen = np.random.default_rng(seed)
    n = samples(seconds)
    t = np.arange(n) / RATE
    rumble = lowpass(noise(seconds, seed), 220) * np.exp(-t * 0.9) * 2.0
    debris = crackle(n, gen, 500, np.exp(-t * 1.2), 1200, 1000, 0.01) * 2.0
    glassy = tinks(n, gen, 50, 0.1, 2.0, 1500, 5000, (0.05, 0.2), (15, 40), 1.4)
    out = _st(rumble + debris + glassy, 0.8, seed)
    b = boom(seconds, 32.0, seed)
    out[:len(b)] += b * 0.8
    return _norm(out)


def cut_massive(seconds=5.0, seed=37):
    """DER Schnitt: Klinge, dann ein riesiger Einschlag, Becken, Sub, Steinbruch."""
    out = boom(seconds, 28.0, seed) * 0.8
    c = crash(seconds, seed)
    out[:len(c)] += c * 0.9
    s = sub_drop(seconds, 70.0, 26.0)
    out[:len(s)] += s * 0.5
    r = sword_ring(2.0, seed)
    out[:len(r)] += r * 0.6
    h = hit_big(2.4, seed + 1)
    out[:len(h)] += h * 0.6
    try:
        for name, g in (('cut_strike', 1.0), ('cut_split', 0.9)):
            m = mod_sfx(name)
            out[:min(len(out), len(m))] += m[:len(out)] * g
    except Exception:
        pass
    return _norm(out)


SYNTH = {
    'whoosh': lambda s: whoosh(0.45, 500, 4200, s),
    'whoosh_soft': lambda s: whoosh(0.9, 250, 1800, s, 0.7, 0.7) * 0.7,
    'boom_low': lambda s: boom(3.5, 30.0, s),
    'boom_card': lambda s: boom(3.0, 34.0, s),
    'reverse_cymbal': lambda s: reverse_cymbal(1.4),
    'bell': lambda s: bell(),
    'glint': lambda s: glint(1.6, s),
    'glint_soft': lambda s: glint(2.0, s) * 0.5,
    'wind_bed': lambda s: wind_bed(14.0, s),
    'hit_big': lambda s: hit_big(2.4, s),
    'hit_small': lambda s: hit_small(0.9, s),
    'shimmer': lambda s: shimmer(),
    'ui_glass': lambda s: ui_glass(),
    'spell_wind': lambda s: spell_wind(0.9, s),
    'spell_ice': lambda s: spell_ice(1.2, s),
    'ice_break': lambda s: ice_break(1.8, s),
    'spell_yang_charge': lambda s: glow(0.9, hz('E5'), s),
    'spell_yang_release': lambda s: stream(1.0, s),
    'light_build': lambda s: light_build(1.6, s),
    'glide_wind': lambda s: glide_wind(1.6, s),
    'sword_ring': lambda s: sword_ring(2.2, s),
    'wood_split': lambda s: wood_split(1.4, s),
    'breath': lambda s: breath(2.4, s),
    'suck': lambda s: suck(1.0, s),
    'cut_massive': lambda s: cut_massive(5.0, s),
    'stream': lambda s: stream(1.4, s),
    'sub_drop': lambda s: sub_drop(3.5),
    'riser': lambda s: riser(8.0, 110.0, 2.0, s),
    'title_hit': lambda s: title_hit(6.0, s),
    'collapse_synth': lambda s: collapse(3.2, s),
    'punch_synth': lambda s: thump(0.5, 160, 70, 12.0, 0.9, s),
}

# Eigene Spielgeräusche der Mod (assets/hha_arius/sounds/sfx), teils mit Synthese geschichtet
MOD = {
    'slash': [('arius_slash', 1.0)],
    'parry': [('arius_parry', 0.9), ('solvane_parry', 0.5)],
    'punch': [('hold_impact', 0.7)],
    'kick': [('arius_kick', 1.0)],
    'kick_big': [('arius_kick', 1.0), ('hold_impact', 0.8)],
    'wind_step': [('arius_wind_step', 1.0)],
    'charge': [('cut_charge', 1.0), ('cut_reverse_wind', 0.6)],
    'line_charge': [('white_line_charge', 1.0)],
    'rumble': [('glacier_crack', 0.9), ('cut_split', 0.5)],
    'release': [('white_line_release', 1.0)],
    'blizzard': [('seal_blizzard', 0.9)],
    'roar': [('nivaris_roar', 1.0)],
    'wing': [('nivaris_wing', 1.0)],
    'breath_dragon': [('nivaris_breath', 1.0)],
    'collapse': [('glacier_collapse', 1.0)],
    'shatter': [('transform_shatter', 1.0)],
    'pulse': [('transform_pulse', 1.0)],
    'white_hole': [('core_form', 1.0), ('core_pulse', 0.8)],
    'white_hole_small': [('white_core', 1.0)],
    'chime': [('resonance_chime', 1.0)],
    'iai': [('iai_draw', 1.0)],
    'ice_wall': [('arius_ice_wall', 1.0)],
}

# Synthese-Schichten zusätzlich zu den Mod-Klängen (mehr Kino-Wucht)
LAYER = {
    'kick_big': ('hit_big', 0.5), 'collapse': ('collapse_synth', 0.7), 'shatter': ('ice_break', 0.6),
    'white_hole': ('sub_drop', 0.6), 'white_hole_small': ('sub_drop', 0.35), 'punch': ('punch_synth', 0.8),
    'roar': ('sub_drop', 0.3), 'release': ('stream', 0.4), 'ice_wall': ('ice_break', 0.4),
    'blizzard': ('blizzard_gust', 0.9), 'charge': ('spell_wind', 0.6),
}
SYNTH['blizzard_gust'] = lambda s: whoosh(1.4, 300, 2600, s, 0.45, 1.8)


def make(name, seed=0):
    """Effekt als Stereo-Array (Spitze ~0,9)."""
    if name in SYNTH and name not in MOD:
        return SYNTH[name](seed)
    parts = []
    for file, g in MOD.get(name, []):
        parts.append(mod_sfx(file, seed % 2) * g)
    if name in LAYER:
        other, g = LAYER[name]
        parts.append(make(other, seed) * g)
    if not parts:
        raise KeyError(name)
    n = max(len(p) for p in parts)
    out = np.zeros((n, 2))
    for p in parts:
        out[:len(p)] += p
    return _norm(out)
