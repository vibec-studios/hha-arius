"""Synthetisiert alle Klangeffekte von HHA Arius und schreibt sounds.json. Die Musik liegt in arius_music.py.

Aufruf (aus dem Projektordner):
    PYTHONPATH=tmp/pylib python tools/assets/audio/arius_sounds.py [id ...]   (ohne id: alle + sounds.json)

Klangbild: präzise, trocken, kalt. Schwerthiebe = Luft + feines Metall, Paraden = heller Eisklang, Tritte = dumpfer
Körperschlag. Drachenklänge tief und groß, ohne Fauchen-Kitsch. Der weiße Kern drückt (tiefes Wummern, das nach außen
atmet), er saugt nicht. Positionsklänge sind mono (sonst kann Minecraft sie nicht im Raum platzieren).
Jede Variante ist ein eigener Seed/eine eigene Tonhöhe; alle Effekte werden auf gleiche Kurzzeit-Lautheit normiert.
"""
import json
import os
import sys

import numpy as np

from arius_kit import (
    ASSETS, RATE, SOUNDS, after, band_sweep, bandpass, crackle, distort, encode, fade, gate, glass, highpass, hz,
    ice_chirp, ice_ring, level, lowpass, noise, partials, phase_of, place, room, samples, short_rms_max,
    sweep, timeline, tinks, tone_sweep, trim_tail,
)

OUT = os.path.join(SOUNDS, 'sfx')
PITCH = (1.0, 0.93, 1.07)


def rng(name, v):
    return np.random.default_rng(abs(hash_name(name)) * 7 + v * 1013)


def hash_name(name):
    """Stabiler Hash (Pythons hash() ist je Lauf zufällig)."""
    h = 2166136261
    for c in name.encode():
        h = ((h ^ c) * 16777619) & 0xFFFFFFFF
    return h


def env_ad(t, attack, decay):
    """Schneller Anstieg, exponentielles Abklingen."""
    return np.minimum(1.0, t / max(attack, 1e-4)) * np.exp(-after(t, attack) * decay)


def env_hump(k, peak=0.35, sharp=1.6):
    """Buckel über k in 0..1 mit Gipfel bei `peak`."""
    rise = np.clip(k / peak, 0, 1) ** sharp
    fall = np.clip((1 - k) / (1 - peak), 0, 1) ** sharp
    return np.where(k < peak, rise, fall)


def blade_air(seconds, gen, c_lo=600.0, c_hi=3600.0, peak=0.3, width=1500.0):
    """Klingenluft: ein Rauschband fährt hoch und zurück, schmaler als ein Windstoß."""
    x = noise(seconds, int(gen.integers(1 << 30)))
    k = np.linspace(0, 1, len(x))
    y = band_sweep(x, lambda q: c_lo + (c_hi - c_lo) * np.sin(np.pi * min(1.0, q / (2 * peak))) ** 2 if q < 2 * peak else c_lo + (c_hi - c_lo) * 0.25 * (1 - q),
                   lambda q: width * (0.6 + 0.8 * q))
    return highpass(y, 320.0) * env_hump(k, peak, 1.5)  # Klingenluft ohne Tiefbass: präzise statt dick


def body_thump(seconds, gen, f0=160.0, f1=52.0, decay=14.0):
    t = np.arange(samples(seconds)) / RATE
    thump = tone_sweep(f0, f1, seconds, 2.5) * np.exp(-t * decay)
    punch = lowpass(noise(seconds, int(gen.integers(1 << 30))), 900) * np.exp(-t * 45.0) * 0.9
    return thump * 1.1 + punch


# --- Arius ------------------------------------------------------------------------------------------------------------

def arius_slash(v, gen, p):
    d = 0.55
    t = np.arange(samples(d)) / RATE
    air = blade_air(d, gen, 650 * p, 3600 * p, 0.32 + 0.04 * v, 1400)
    sing = partials(after(t, 0.11), [(2930 * p, .25, 9), (4180 * p, .18, 12), (6260 * p, .12, 17), (8870 * p, .06, 24)]) * gate(t, 0.11)
    return room(air * 1.2 + sing * 0.35, 0.4, 0.1)


def arius_thrust(v, gen, p):
    d = 0.42
    t = np.arange(samples(d)) / RATE
    x = noise(d, int(gen.integers(1 << 30)))
    air = highpass(band_sweep(x, lambda q: 900 * p + 3600 * p * min(1.0, q / 0.35), lambda q: 520.0), 400.0) * env_hump(t / d, 0.26, 1.2)
    tick = partials(after(t, 0.13), [(5200 * p, .3, 40), (7420 * p, .18, 52), (3310 * p, .12, 30)]) * gate(t, 0.13)
    push = tone_sweep(140, 70, d) * np.exp(-t * 20) * 0.3
    return room(air * 1.3 + tick * 0.5 + push, 0.35, 0.08)


def arius_parry(v, gen, p):
    d = 1.0
    t = np.arange(samples(d)) / RATE
    click = highpass(noise(d, int(gen.integers(1 << 30))), 3000) * np.exp(-t * 160) * 0.9
    ring = ice_ring(t, 1870 * p, gen, bright=1.1)
    shimmer = tinks(len(t), gen, 9, 0.03, 0.4, 6000, 10500, (0.02, 0.07), (40, 90), 1.6)
    return room(click + ring * 0.55 + shimmer, 0.6, 0.2, 3.5)


def arius_kick(v, gen, p):
    d = 0.45
    t = np.arange(samples(d)) / RATE
    hit = body_thump(d, gen, 170 * p, 52 * p, 14)
    cloth = bandpass(noise(d, int(gen.integers(1 << 30))), 1500, 1100) * np.exp(-t * 30) * 0.22
    return room(distort(hit + cloth, 1.3), 0.3, 0.08)


def arius_wind_step(v, gen, p):
    d = 0.9
    t = np.arange(samples(d)) / RATE
    k = t / d
    x = noise(d, int(gen.integers(1 << 30)))
    gust = band_sweep(x, lambda q: 380 * p + 2400 * p * np.sin(np.pi * min(q / 0.5, 1.0)) ** 2 * (1 - 0.6 * q), lambda q: 900 + 1600 * q) * env_hump(k, 0.12, 1.3)
    whump = tone_sweep(110, 40, d) * np.exp(-t * 7) * 0.55
    snow = highpass(noise(d, int(gen.integers(1 << 30))), 5000) * env_hump(k, 0.3, 1.2) * 0.25
    return room(gust * 1.4 + whump + snow, 0.6, 0.12)


def arius_guard(v, gen, p):
    """Haltung: Klang wie gespanntes Eis (dünner, gehaltener Ton mit Schwebung, ein leises Knarzen)."""
    d = 1.5
    t = np.arange(samples(d)) / RATE
    swell = np.minimum(1.0, t / 0.07) * np.exp(-t * 1.4)
    f = 2350 * p
    tone = (np.sin(2 * np.pi * f * t) + np.sin(2 * np.pi * (f + 6.5) * t) * 0.8 + 0.35 * np.sin(2 * np.pi * f * 1.5 * t)
            + 0.15 * np.sin(2 * np.pi * f * 2.24 * t)) * swell
    creak = crackle(len(t), gen, 22, np.exp(-t * 2.5), 700, 400, 0.006) * 0.9
    strike = ice_ring(t, 1240 * p, gen, decay=1.6) * 0.3
    return room(tone * 0.25 + creak + strike, 0.8, 0.22)


def arius_ice_wall(v, gen, p):
    d = 1.7
    t = np.arange(samples(d)) / RATE
    k = t / d
    grow = np.clip(k / 0.65, 0, 1) ** 0.6 * np.exp(-after(t, 1.1) * 7)
    grind = lowpass(noise(d, int(gen.integers(1 << 30))), 1500 * p) * grow * (0.6 + 0.4 * np.abs(np.sin(2 * np.pi * phase_of(14 + 10 * k))))
    crack = crackle(len(t), gen, 90, grow, 2600 * p, 1800)
    low = np.sin(2 * np.pi * phase_of(58 + 30 * k)) * grow * 0.45
    settle = place(len(t), 1.08, ice_chirp(0.5, 3600 * p, 300, 0.07, int(gen.integers(1 << 30)))) * 0.7
    ring = ice_ring(after(t, 1.1), 1420 * p, gen, decay=1.5) * gate(t, 1.1) * 0.25
    return room(grind * 0.6 + crack * 1.4 + low + settle + ring, 0.7, 0.18)


def arius_frost_strip(v, gen, p):
    d = 1.0
    t = np.arange(samples(d)) / RATE
    k = t / d
    envelope = env_hump(k, 0.4, 1.2)
    hiss = band_sweep(noise(d, int(gen.integers(1 << 30))), lambda q: 4200 * p + 2600 * q, lambda q: 2200.0) * envelope
    ticks = crackle(len(t), gen, 160, envelope, 5800 * p, 2400, 0.002) * 1.2
    return room(hiss * 0.6 + ticks, 0.5, 0.12)


def arius_stab_ground(v, gen, p):
    d = 1.3
    t = np.arange(samples(d)) / RATE
    shink = partials(t, [(1650 * p, .5, 7), (2480 * p, .4, 9), (3710 * p, .3, 12), (5530 * p, .18, 16)]) * np.minimum(1.0, t / 0.002)
    thump = tone_sweep(130, 42, d) * np.exp(-t * 8) * 1.2
    crunch = lowpass(noise(d, int(gen.integers(1 << 30))), 2600) * np.exp(-t * 26) * 0.6
    cracks = sum(place(len(t), at, ice_chirp(0.4, gen.uniform(2800, 4600) * p, 250, gen.uniform(0.05, 0.1), int(gen.integers(1 << 30)))) * g
                 for at, g in ((0.06, 0.5), (0.19, 0.35), (0.37, 0.25)))
    debris = tinks(len(t), gen, 14, 0.05, 0.7, 2500, 7000, (0.03, 0.12), (35, 80), 1.4)
    return room(shink * 0.45 + thump + crunch + cracks + debris, 0.9, 0.2)


def arius_grip(v, gen, p):
    d = 1.1
    t = np.arange(samples(d)) / RATE
    k = t / d
    freeze = np.clip(k / 0.45, 0, 1) ** 0.7 * np.exp(-after(t, 0.5) * 12)
    crunch = crackle(len(t), gen, 220, freeze, 2200 * p, 1600, 0.005) * 1.3
    creak = np.sin(2 * np.pi * phase_of(185 * p - 50 * k)) * (0.5 + 0.5 * np.sin(2 * np.pi * phase_of(23 + 0 * k))) * freeze * 0.25
    lock = ice_ring(after(t, 0.5), 1640 * p, gen, decay=1.8) * gate(t, 0.5) * 0.25
    return room(crunch + creak + lock, 0.5, 0.15)


def arius_arrow_cut(v, gen, p):
    d = 0.3
    t = np.arange(samples(d)) / RATE
    snip = highpass(band_sweep(noise(d, int(gen.integers(1 << 30))), lambda q: 2500 * p + 3000 * q, lambda q: 900.0), 600.0) * env_ad(t, 0.012, 32)
    tick = partials(after(t, 0.02), [(6200 * p, .35, 60), (8930 * p, .2, 75), (4410 * p, .15, 50)]) * gate(t, 0.02)
    wood = bandpass(noise(d, int(gen.integers(1 << 30))), 1150 * p, 500) * env_ad(after(t, 0.025), 0.001, 90) * gate(t, 0.025) * 1.4
    return room(snip * 1.1 + tick * 0.6 + wood, 0.3, 0.07)


def arius_null_cut(v, gen, p):
    """White Null: eine haarfeine, saubere Linie. Hoher, fast reiner Ton, schmales Luftband, kaum Raum."""
    d = 1.1
    t = np.arange(samples(d)) / RATE
    k = t / d
    line = (np.sin(2 * np.pi * phase_of(4700 * p - 380 * p * k)) + 0.18 * np.sin(2 * np.pi * phase_of(9400 * p - 760 * p * k))) * env_ad(t, 0.008, 3.2)
    air = highpass(band_sweep(noise(d, int(gen.integers(1 << 30))), lambda q: 2800 * p + 3600 * p * min(1.0, q / 0.25), lambda q: 420.0), 600.0) * env_ad(t, 0.02, 7)
    hollow = np.sin(2 * np.pi * 68 * t) * env_ad(t, 0.004, 16) * 0.5
    return room(line * 0.22 + air * 1.2 + hollow, 0.5, 0.07)


# --- Der Schnitt -------------------------------------------------------------------------------------------------------

def cut_reverse_wind(v, gen, p):
    """Der Wind kehrt sich um: ein Windstoß mit Raum, rückwärts abgespielt. Schwillt an und bricht ab, dann nichts."""
    d = 2.0
    t = np.arange(samples(d)) / RATE
    x = noise(d, int(gen.integers(1 << 30)))
    gust = band_sweep(x, lambda q: 3200 - 2700 * q ** 0.7, lambda q: 1400 + 600 * (1 - q)) * env_ad(t, 0.015, 2.1)
    body = lowpass(noise(d, int(gen.integers(1 << 30))), 420) * env_ad(t, 0.02, 2.6) * 0.8
    forward = room(gust * 1.2 + body, 0.9, 0.3, 3.0)[:len(t)]
    return fade(forward[::-1], 0.004, 0.006)


def cut_charge(v, gen, p):
    """Energie an der Schneide: heller, hoher Ton, der ansteigt; Flirren wird schneller."""
    d = 3.4
    t = np.arange(samples(d)) / RATE
    k = t / d
    f = 1400 * (3600 / 1400) ** (k ** 1.3)
    shimmer = 0.75 + 0.25 * np.sin(2 * np.pi * phase_of(6 + 18 * k ** 1.5))
    tone = (np.sin(2 * np.pi * phase_of(f)) + 0.28 * np.sin(2 * np.pi * phase_of(2 * f)) + 0.1 * np.sin(2 * np.pi * phase_of(3.003 * f))) * shimmer
    air = highpass(noise(d, int(gen.integers(1 << 30))), 5000) * k ** 2 * 0.35
    envelope = np.clip(k, 0, 1) ** 1.4 * np.minimum(1.0, (d - t) / 0.03)
    return room((tone * 0.5 + air) * envelope, 0.6, 0.14)


def cut_strike(v, gen, p):
    """Ein einziger trockener Schnitt: Riss durch die Luft, ein kurzer tiefer Hieb, fast kein Raum."""
    d = 0.7
    t = np.arange(samples(d)) / RATE
    crack = highpass(noise(d, int(gen.integers(1 << 30))), 2200) * np.exp(-t * 75) * 1.2
    air = highpass(band_sweep(noise(d, int(gen.integers(1 << 30))), lambda q: 1200 + 4200 * (1 - q) ** 6, lambda q: 1300.0), 300.0) * env_ad(t, 0.004, 12)
    chop = tone_sweep(95, 38, d) * np.exp(-t * 16) * 0.9
    edge = partials(t, [(3540, .14, 18), (5310, .08, 26)])
    return room(distort(crack + air * 1.3 + chop + edge, 1.25), 0.25, 0.05)


def cut_split(v, gen, p):
    """Der Tempel trennt sich: tiefes Brechen, Knarren, dann Eisregen."""
    d = 4.6
    n = samples(d)
    t = np.arange(n) / RATE
    rumble = lowpass(noise(d, int(gen.integers(1 << 30))), 170) * env_ad(t, 0.06, 1.0) * 2.4
    sub = tone_sweep(46, 28, d, 1.5) * env_ad(t, 0.03, 1.2) * 1.0
    groan = np.sin(2 * np.pi * phase_of(92 - 30 * t / d)) * (0.5 + 0.5 * np.sin(2 * np.pi * phase_of(7 + 0 * t))) * env_ad(after(t, 0.1), 0.2, 1.8) * gate(t, 0.1) * 0.3
    cracks = sum(place(n, at, ice_chirp(0.6, gen.uniform(2400, 4800), gen.uniform(180, 320), gen.uniform(0.07, 0.14), int(gen.integers(1 << 30)))) * g
                 for at, g in ((0.0, 1.0), (0.12, 0.7), (0.31, 0.55), (0.55, 0.45), (0.9, 0.3)))
    debris = lowpass(noise(d, int(gen.integers(1 << 30))), 2400) * env_ad(after(t, 0.05), 0.02, 3.0) * gate(t, 0.05) * 0.5
    rain = tinks(n, gen, 170, 0.3, 4.2, 2600, 9500, (0.02, 0.12), (30, 80), 1.8)
    return room(rumble + sub + groan + cracks + debris + rain, 1.6, 0.26, 2.4)


# --- Nivaris -----------------------------------------------------------------------------------------------------------

def dragon_voice(d, gen, base, swing, formants, rough=1.8, breath=0.5):
    """Tiefer Drachenhals: harmonische Stimme mit wanderndem Rachenraum, Atem, Subbass."""
    t = np.arange(samples(d)) / RATE
    k = t / d
    pitch = base + swing * np.sin(np.pi * np.clip(k, 0, 1) ** 0.7) + 2.5 * np.sin(t * 31.0) + 1.5 * np.sin(t * 83.0)
    phase = phase_of(pitch)
    raw = sum(np.sin(2 * np.pi * h * phase + h * 1.3) / h ** 0.75 for h in range(1, 44))
    grit = 0.75 + 0.25 * np.sin(2 * np.pi * phase_of(19 + 9 * np.sin(t * 2.3)))
    (f1, f2) = formants
    throat = sweep(raw * grit, lambda f, x: (np.exp(-(((f - (f1 + 220 * np.sin(np.pi * x))) / 200.0) ** 2))
                                            + 0.6 * np.exp(-(((f - (f2 + 380 * np.sin(np.pi * x))) / 380.0) ** 2))
                                            + 0.12 * np.exp(-(((f - 2600.0) / 900.0) ** 2))))
    air = bandpass(noise(d, int(gen.integers(1 << 30))), 1700, 1400) * breath
    sub = np.sin(2 * np.pi * phase_of(base * 0.5 + swing * 0.2 * np.sin(np.pi * k))) * 0.7
    return distort(throat * 1.2 + air + sub, rough)


def nivaris_roar(v, gen, p):
    """Tief und kurz: sie brüllt selten. Kein Kreischen, kein Fauchen."""
    d = 2.2
    t = np.arange(samples(d)) / RATE
    voice = dragon_voice(d, gen, 46 * p, 22 * p, (300 * p, 880 * p), 1.8, 0.55)
    envelope = np.minimum(1.0, t / 0.1) * np.clip((d - t) / 0.85, 0, 1) ** 1.3
    return room(voice * envelope, 1.3, 0.26, 2.6)


def nivaris_inhale(v, gen, p):
    d = 1.6
    t = np.arange(samples(d)) / RATE
    k = t / d
    intake = band_sweep(noise(d, int(gen.integers(1 << 30))), lambda q: 520 * p + 1900 * p * q ** 1.3, lambda q: 700 + 900 * q)
    throat = lowpass(noise(d, int(gen.integers(1 << 30))), 210) * 1.6
    whistle = np.sin(2 * np.pi * phase_of(1080 * p + 420 * p * k)) * 0.05
    envelope = np.clip(k / 0.9, 0, 1) ** 1.5 * np.clip((d - t) / 0.12, 0, 1)
    return room((intake * 1.3 + throat * k + whistle) * envelope, 0.8, 0.2)


def nivaris_breath(v, gen, p):
    """Frostodem: ein schwerer, kalter Strom mit Eiskristallen darin, eine tiefe Stimme darunter."""
    d = 1.9
    n = samples(d)
    t = np.arange(n) / RATE
    k = t / d
    envelope = np.minimum(1.0, t / 0.06) * np.clip((d - t) / 0.6, 0, 1) ** 1.2
    stream = (lowpass(noise(d, int(gen.integers(1 << 30))), 4800) * 0.7 + bandpass(noise(d, int(gen.integers(1 << 30))), 1100 * p, 700) * 0.8)
    stream *= 0.85 + 0.15 * np.sin(2 * np.pi * phase_of(11 + 4 * k))
    voice = dragon_voice(d, gen, 40 * p, 6, (260, 760), 1.4, 0.0) * 0.35
    ice = crackle(n, gen, 140, envelope, 6200, 2600, 0.002) * 0.9
    return room(distort((stream + voice) * envelope, 1.3) + ice, 1.0, 0.22, 3.0)


def nivaris_wing(v, gen, p):
    d = 0.85
    t = np.arange(samples(d)) / RATE
    k = t / d
    whomp = band_sweep(noise(d, int(gen.integers(1 << 30))), lambda q: 220 * p + 650 * p * np.sin(np.pi * min(q / 0.45, 1.0)), lambda q: 420.0) * env_hump(k, 0.2, 1.4) * 1.8
    flutter = bandpass(noise(d, int(gen.integers(1 << 30))), 1300 * p, 900) * (0.5 + 0.5 * np.sin(2 * np.pi * phase_of(np.full(len(t), 21.0 * p)))) * env_hump(k, 0.25, 1.4) * 0.35
    sub = tone_sweep(58, 34, d) * env_ad(t, 0.05, 6) * 0.7
    return room(whomp + flutter + sub, 0.8, 0.18)


def nivaris_land(v, gen, p):
    d = 1.9
    n = samples(d)
    t = np.arange(n) / RATE
    sub = tone_sweep(92 * p, 26, d, 3.0) * np.exp(-t * 3.0) * 1.4
    crunch = lowpass(noise(d, int(gen.integers(1 << 30))), 2500) * np.exp(-t * 13) * 1.0
    spray = bandpass(noise(d, int(gen.integers(1 << 30))), 3600, 2600) * env_ad(after(t, 0.03), 0.04, 3.0) * gate(t, 0.03) * 0.35
    shards = tinks(n, gen, 18, 0.05, 0.9, 2400, 7000, (0.03, 0.12), (30, 70), 1.5)
    return room(distort(sub + crunch + spray, 1.4) + shards, 1.3, 0.24, 2.6)


def nivaris_claw(v, gen, p):
    d = 0.75
    n = samples(d)
    t = np.arange(n) / RATE
    k = t / d
    swipe = band_sweep(noise(d, int(gen.integers(1 << 30))), lambda q: 300 * p + 1300 * p * np.sin(np.pi * min(q / 0.6, 1.0)), lambda q: 700.0) * env_hump(k, 0.25, 1.3) * 1.3
    window = np.clip((t - 0.14) / 0.03, 0, 1) * np.clip((0.48 - t) / 0.08, 0, 1)
    scrape = bandpass(noise(d, int(gen.integers(1 << 30))), 2300 * p, 900) * (0.55 + 0.45 * np.sign(np.sin(2 * np.pi * phase_of(np.full(n, 63.0 * p))))) * window * 0.7
    thud = tone_sweep(120, 48, d) * env_ad(after(t, 0.15), 0.004, 14) * gate(t, 0.15) * 0.7
    return room(swipe + scrape + thud, 0.6, 0.15)


def nivaris_tail(v, gen, p):
    d = 1.05
    n = samples(d)
    t = np.arange(n) / RATE
    k = t / d
    sweep_air = band_sweep(noise(d, int(gen.integers(1 << 30))), lambda q: 200 * p + 750 * p * np.sin(np.pi * q), lambda q: 500.0) * env_hump(k, 0.42, 1.2) * 1.8
    blades = sum(place(n, 0.33 + i * 0.035 + gen.uniform(0, 0.01), partials(timeline(0.2), [(gen.uniform(2900, 6200) * p, 1.0, 45), (gen.uniform(3500, 7000) * p, 0.5, 60)])) * 0.18
                 for i in range(6))
    thud = tone_sweep(110, 44, d) * env_ad(after(t, 0.5), 0.004, 10) * gate(t, 0.5) * 0.6
    return room(sweep_air + blades + thud, 0.7, 0.16)


def nivaris_choke(v, gen, p):
    """Sie erstickt am eigenen Atem: abgehackte Stöße, Gurgeln, dann ein tiefes, fallendes Stöhnen."""
    d = 1.8
    n = samples(d)
    t = np.arange(n) / RATE
    bursts = sum(env_ad(after(t, at), 0.015, 9) * gate(t, at) * g for at, g in ((0.0, 1.0), (0.32, 0.8), (0.6, 0.65)))
    cough = dragon_voice(d, gen, 62 * p, 10, (420, 1150), 2.2, 0.8) * bursts
    gurgle = bandpass(noise(d, int(gen.integers(1 << 30))), 480, 260) * (gen.uniform(0, 1, n) ** 0.1) * (0.5 + 0.5 * np.sin(2 * np.pi * phase_of(np.full(n, 13.0)))) * np.clip(t / 0.2, 0, 1) * 0.35
    groan_env = np.clip((t - 0.85) / 0.12, 0, 1) * np.clip((d - t) / 0.6, 0, 1)
    groan = sum(np.sin(2 * np.pi * h * phase_of(78 * p - 30 * p * np.clip((t - 0.85) / 0.95, 0, 1))) / h for h in range(1, 12)) * groan_env * 0.35
    return room(cough + gurgle * np.exp(-after(t, 1.0) * 3) + lowpass(groan, 900), 1.0, 0.2)


def nivaris_crash(v, gen, p):
    """Sie kracht in einen Eiskamm: schwerer Aufprall, der Kamm zerspringt."""
    d = 2.4
    n = samples(d)
    t = np.arange(n) / RATE
    sub = tone_sweep(85 * p, 24, d, 3.0) * np.exp(-t * 2.6) * 1.4
    crunch = lowpass(noise(d, int(gen.integers(1 << 30))), 3000) * np.exp(-t * 10) * 1.1
    cracks = sum(place(n, at, ice_chirp(0.5, gen.uniform(3000, 5200), 240, gen.uniform(0.05, 0.11), int(gen.integers(1 << 30)))) * g
                 for at, g in ((0.0, 0.9), (0.05, 0.6), (0.14, 0.5)))
    shards = tinks(n, gen, 110, 0.02, 1.6, 2200, 9000, (0.03, 0.16), (25, 75), 2.0)
    return room(distort(sub + crunch, 1.5) + cracks + shards, 1.4, 0.25, 2.4)


# --- Welt ----------------------------------------------------------------------------------------------------------------

def glacier_crack(v, gen, p):
    d = 1.6
    n = samples(d)
    t = np.arange(n) / RATE
    main = ice_chirp(d, 4200 * p, 260 * p, 0.11, int(gen.integers(1 << 30))) * 1.1
    echoes = sum(place(n, at, ice_chirp(0.4, gen.uniform(2600, 4000) * p, 220, 0.08, int(gen.integers(1 << 30)))) * g
                 for at, g in ((gen.uniform(0.12, 0.2), 0.45), (gen.uniform(0.3, 0.45), 0.3)))
    rumble = lowpass(noise(d, int(gen.integers(1 << 30))), 150) * env_ad(t, 0.02, 2.2) * 1.6
    return room(main + echoes + rumble, 1.4, 0.3, 2.6)


def glacier_collapse(v, gen, p):
    d = 5.6
    n = samples(d)
    t = np.arange(n) / RATE
    k = t / d
    envelope = np.clip(t / 0.35, 0, 1) * np.clip((d - t) / 2.2, 0, 1) ** 1.4
    rumble = lowpass(noise(d, int(gen.integers(1 << 30))), 160 * p) * envelope * 2.6
    sub = np.sin(2 * np.pi * phase_of(34 + 6 * np.sin(t * 1.3))) * envelope * 0.8
    cracks = np.zeros(n)
    for _ in range(14):
        at = gen.uniform(0, 3.2) ** 1.1
        cracks += place(n, at, ice_chirp(0.5, gen.uniform(2200, 4600) * p, gen.uniform(180, 320), gen.uniform(0.06, 0.13), int(gen.integers(1 << 30)))) * gen.uniform(0.25, 0.7)
    debris = lowpass(noise(d, int(gen.integers(1 << 30))), 2200) * envelope * (0.4 + 0.6 * (gen.uniform(0, 1, n) ** 30)) * 0.4
    rain = tinks(n, gen, 160, 0.6, 5.0, 2000, 8500, (0.02, 0.1), (25, 70), 1.2)
    return room(rumble + sub + cracks + debris + rain * (1 - 0.3 * k), 1.6, 0.28, 2.0)


def ridge_rise(v, gen, p):
    d = 1.5
    n = samples(d)
    t = np.arange(n) / RATE
    k = t / d
    grow = np.clip(k / 0.78, 0, 1) ** 1.2 * np.exp(-after(t, 1.18) * 10)
    grind = crackle(n, gen, 260, grow, 1900 * p, 1500, 0.006) * 1.3
    swell = np.sin(2 * np.pi * phase_of(52 * p + 26 * p * k)) * grow * 0.5
    body = lowpass(noise(d, int(gen.integers(1 << 30))), 700) * grow * 0.5
    stop = body_thump(0.4, gen, 120, 50, 12) * 0.5
    return room(grind + swell + body + place(n, 1.17, stop), 0.9, 0.2)


def ridge_break(v, gen, p):
    d = 1.3
    n = samples(d)
    t = np.arange(n) / RATE
    crack = ice_chirp(d, 3600 * p, 280, 0.07, int(gen.integers(1 << 30))) * 0.8
    crunch = lowpass(noise(d, int(gen.integers(1 << 30))), 2600) * np.exp(-t * 14) * 0.9
    shards = tinks(n, gen, 70, 0.01, 0.8, 2200 * p, 8800 * p, (0.04, 0.18), (25, 70), 2.2)
    thump = tone_sweep(110, 42, d) * np.exp(-t * 9) * 0.7
    return room(crack + crunch + shards + thump, 1.0, 0.22)


def crystal_hang(v, gen, p):
    """Schwebende Kristalle: leises Glitzern, ein paar kühle Glastöne mit langsamem Flimmern."""
    d = 2.6
    n = samples(d)
    t = np.arange(n) / RATE
    out = np.zeros(n)
    for i in range(7):
        f = gen.choice([2637.0, 3136.0, 3951.0, 4699.0, 5274.0, 6272.0]) * p
        at = gen.uniform(0, 1.3)
        tone = glass(f, 2.0)[:, 0] * (0.6 + 0.4 * np.sin(2 * np.pi * gen.uniform(3, 7) * timeline(2.0)))
        out += place(n, at, tone) * gen.uniform(0.4, 1.0)
    glitter = tinks(n, gen, 26, 0.0, 2.2, 6000, 11000, (0.01, 0.04), (40, 90))
    envelope = np.clip(t / 0.3, 0, 1) * np.clip((d - t) / 0.6, 0, 1)
    return room((out + glitter) * envelope, 1.3, 0.3, 2.4)


def seal_blizzard(v, gen, p):
    """Der Schnee driftet zu dir hin: Wind schwillt an, ein hohler Heulton, und dann verstummt alles."""
    d = 5.0
    n = samples(d)
    t = np.arange(n) / RATE
    k = t / d
    wind = band_sweep(noise(d, int(gen.integers(1 << 30))), lambda q: 450 + 1500 * q ** 1.4, lambda q: 700 + 1600 * q)
    gusts = 0.7 + 0.3 * np.sin(2 * np.pi * phase_of(0.6 + 0.9 * k)) * np.sin(2 * np.pi * phase_of(np.full(n, 0.37)))
    howl = bandpass(noise(d, int(gen.integers(1 << 30))), 640, 35) * 6.0 * (0.4 + 0.6 * k)
    body = lowpass(noise(d, int(gen.integers(1 << 30))), 300) * 0.9
    snow = highpass(noise(d, int(gen.integers(1 << 30))), 6000) * 0.25
    envelope = np.clip(k / 0.92, 0, 1) ** 1.8 * np.clip((d - t) / 0.05, 0, 1)
    return (wind * 1.3 * gusts + howl * 0.4 + body + snow) * envelope


# --- Signaturen ----------------------------------------------------------------------------------------------------------

def transform_shatter(v, gen, p):
    """Rüstung und Mantel lösen sich in Frostsplitter auf: Splitter steigen (Tonhöhe wandert nach oben), zerfallen in Licht."""
    d = 3.0
    n = samples(d)
    t = np.arange(n) / RATE
    k = t / d
    shards = np.zeros(n)
    for _ in range(140):
        at = gen.uniform(0.0, 2.2)
        f = 1800 + 6500 * (at / 2.2) ** 1.2 * gen.uniform(0.7, 1.1)
        length = samples(gen.uniform(0.05, 0.16))
        tt = np.arange(length) / RATE
        piece = (np.sin(2 * np.pi * f * tt) + 0.4 * np.sin(2 * np.pi * f * 1.49 * tt)) * np.exp(-tt * gen.uniform(25, 60))
        shards += place(n, at, piece * gen.uniform(0.04, 0.16))
    crack = ice_chirp(0.6, 3800, 300, 0.09, int(gen.integers(1 << 30))) * 0.7
    bed = lowpass(noise(d, int(gen.integers(1 << 30))), 380) * env_hump(k, 0.3, 1.2) * 0.6
    light = (glass(hz('E6'), 2.0)[:, 0] + glass(hz('B6'), 2.0)[:, 0] * 0.7)
    return room(shards + place(n, 0.0, crack) + bed + place(n, 2.05, light * 0.6), 1.6, 0.3, 2.2)


def transform_pulse(v, gen, p):
    """Druckstoß, der den Schnee räumt: tiefer Schlag, dann eine Luftfront, die nach außen wandert und heller wird."""
    d = 2.3
    n = samples(d)
    t = np.arange(n) / RATE
    sub = tone_sweep(58, 30, d, 2.0) * env_ad(t, 0.01, 2.8) * 1.3
    front = band_sweep(noise(d, int(gen.integers(1 << 30))), lambda q: 300 + 2800 * q ** 0.6, lambda q: 500 + 2500 * q) * env_ad(t, 0.03, 2.2) * 1.2
    snow = highpass(noise(d, int(gen.integers(1 << 30))), 4500) * env_ad(after(t, 0.05), 0.1, 2.5) * gate(t, 0.05) * 0.35
    return room(distort(sub + front, 1.3) + snow, 1.4, 0.25, 2.4)


def resonance_chime(v, gen, p):
    """Resonanz: zwei Glasglocken in der Kopfquarte des Leitmotivs (B→E), golden und eisblau."""
    d = 3.4
    n = samples(d)
    octave = 1 if v == 0 else 0
    first = glass(hz(f'B{4 + octave}'), 3.2)[:, 0]
    second = glass(hz(f'E{5 + octave}'), 3.2)[:, 0]
    shimmer = tinks(n, gen, 12, 0.15, 1.4, 6500, 10500, (0.01, 0.035), (20, 50))
    return room(place(n, 0.0, first) + place(n, 0.19, second) * 0.95 + shimmer, 1.8, 0.32, 2.0)


def core_form(v, gen, p):
    """Der weiße Kern entsteht: tiefes Wummern, das nach außen atmet. Jeder Atemzug setzt hart ein und läuft aus (Druck),
    nie umgekehrt (das wäre Sog)."""
    d = 3.2
    n = samples(d)
    t = np.arange(n) / RATE
    k = t / d
    build = np.clip(k / 0.85, 0, 1) ** 0.8 * np.clip((d - t) / 0.35, 0, 1)
    hum = (np.sin(2 * np.pi * 41 * t) + 0.7 * np.sin(2 * np.pi * 61.5 * t) + 0.5 * np.sin(2 * np.pi * 62.3 * t) + 0.25 * np.sin(2 * np.pi * 123 * t))
    breaths = sum(env_ad(after(t, at), 0.03, 2.6) * gate(t, at) for at in (0.0, 0.8, 1.55, 2.25, 2.85))
    hum = hum * build * (0.55 + 0.45 * breaths)
    exhale = band_sweep(noise(d, int(gen.integers(1 << 30))), lambda q: 900 + 1600 * q, lambda q: 900 + 1400 * q) * breaths * build * 0.6
    glow = np.sin(2 * np.pi * 1318.5 * t) * build ** 2 * 0.04
    return room(distort(hum * 0.9, 1.2) + exhale + glow, 1.4, 0.24, 2.4)


def core_pulse(v, gen, p):
    """Eine Druckfront: harter tiefer Stoß, dann ausatmende Luft, die sich entfernt (Band fällt, wird dunkler)."""
    d = 2.2
    n = samples(d)
    t = np.arange(n) / RATE
    sub = tone_sweep(54 * p, 31, d, 2.0) * env_ad(t, 0.006, 3.0) * 1.4
    punch = lowpass(noise(d, int(gen.integers(1 << 30))), 700) * env_ad(t, 0.002, 30) * 0.8
    exhale = band_sweep(noise(d, int(gen.integers(1 << 30))), lambda q: 2600 * p - 2100 * p * q ** 0.5, lambda q: 1600 - 900 * q) * env_ad(t, 0.025, 2.4) * 1.1
    return room(distort(sub + punch, 1.35) + exhale, 1.3, 0.24, 2.6)


def core_collapse(v, gen, p):
    """Der Kern fällt zu einem Lichtpunkt zusammen: das Wummern zieht sich in die Höhe und wird dünn, ein heller Punkt, Stille."""
    d = 2.8
    n = samples(d)
    t = np.arange(n) / RATE
    squeeze = np.clip(t / 1.45, 0, 1)
    f = 48 * (900 / 48) ** (squeeze ** 2.2)
    hum = (np.sin(2 * np.pi * phase_of(f)) + 0.5 * np.sin(2 * np.pi * phase_of(f * 1.5))) * (1 - squeeze) ** 0.8 * gate(-t, -1.45)
    air = highpass(noise(d, int(gen.integers(1 << 30))), 3000) * squeeze ** 2 * (1 - gate(t, 1.45)) * 0.2
    point = place(n, 1.47, glass(hz('B6'), 1.3)[:, 0] * 0.8 + glass(hz('E7'), 1.3)[:, 0] * 0.5)
    pop = place(n, 1.45, np.sin(2 * np.pi * 70 * timeline(0.3)) * np.exp(-timeline(0.3) * 18) * 0.6)
    return room(hum * 0.9 + air + point + pop, 1.4, 0.24, 2.4)


def iai_glint(v, gen, p):
    """Ein hoher Klingenton: Licht auf der Schneide."""
    d = 1.4
    t = np.arange(samples(d)) / RATE
    f = 4400 * p
    tone = (np.sin(2 * np.pi * f * t) + 0.45 * np.sin(2 * np.pi * (f * 1.5 + 3) * t) * np.exp(-t * 2) + 0.18 * np.sin(2 * np.pi * f * 2.01 * t) * np.exp(-t * 5))
    tone *= np.minimum(1.0, t / 0.005) * np.exp(-t * 2.4) * (0.85 + 0.15 * np.sin(2 * np.pi * 7.5 * t))
    tick = highpass(noise(d, int(gen.integers(1 << 30))), 7000) * np.exp(-t * 200) * 0.2
    return room(tone * 0.5 + tick, 0.8, 0.18, 3.0)


def iai_draw(v, gen, p):
    """Ziehen und Schneiden in einem Zug: Klinge gleitet aus der Scheide, dann ein glatter Schnitt."""
    d = 0.8
    n = samples(d)
    t = np.arange(n) / RATE
    slide_env = np.clip(t / 0.02, 0, 1) * np.clip((0.14 - t) / 0.02, 0, 1)
    slide = band_sweep(noise(d, int(gen.integers(1 << 30))), lambda q: 2800 * p + 2600 * p * min(1.0, q / 0.18), lambda q: 700.0) * slide_env * 0.9
    ring = partials(after(t, 0.13), [(3320 * p, .3, 10), (4980 * p, .2, 14), (7470 * p, .1, 20)]) * gate(t, 0.13)
    cut = place(n, 0.14, highpass(band_sweep(noise(0.5, int(gen.integers(1 << 30))), lambda q: 1300 + 3800 * (1 - q) ** 4, lambda q: 1200.0), 350.0) * env_ad(timeline(0.5), 0.004, 11))
    return room(slide + ring * 0.5 + cut * 1.3, 0.35, 0.07)


def hold_impact(v, gen, p):
    """Inszenierter Treffer: schwerer als ein Tritt, mit kurzem Metallklacken (Knauf/Rüstung)."""
    d = 0.6
    t = np.arange(samples(d)) / RATE
    hit = body_thump(d, gen, 150 * p, 44 * p, 10) * 1.2
    clank = partials(t, [(910 * p, .4, 18), (1480 * p, .3, 22), (2390 * p, .18, 30)])
    crunch = lowpass(noise(d, int(gen.integers(1 << 30))), 3400) * np.exp(-t * 50) * 0.5
    return room(distort(hit + clank * 0.5 + crunch, 1.45), 0.35, 0.1)


def lead_switch(v, gen, p):
    """Der Takt wechselt: ein leises Glas-Paar (Quarte) und ein trockenes Eisklicken."""
    d = 1.6
    n = samples(d)
    a, b = ('B5', 'E6') if v == 0 else ('E6', 'B5')
    tones = place(n, 0.0, glass(hz(a), 1.5)[:, 0]) + place(n, 0.1, glass(hz(b), 1.5)[:, 0]) * 0.9
    click = place(n, 0.0, highpass(noise(0.05, int(gen.integers(1 << 30))), 4000) * np.exp(-timeline(0.05) * 180)) * 0.4
    return room(tones + click, 1.0, 0.22, 2.6)


# --- Ausrüstung ----------------------------------------------------------------------------------------------------------

def solvane_parry(v, gen, p):
    d = 0.8
    t = np.arange(samples(d)) / RATE
    click = highpass(noise(d, int(gen.integers(1 << 30))), 3500) * np.exp(-t * 190) * 0.8
    ring = ice_ring(t, 2380 * p, gen, bright=1.15, decay=1.3)
    return room(click + ring * 0.5, 0.45, 0.15, 4.0)


def white_line_charge(v, gen, p):
    d = 0.65  # Fähigkeit lädt 12 Ticks (0,6 s)
    t = np.arange(samples(d)) / RATE
    k = t / d
    f = 900 * (2600 / 900) ** (k ** 1.2)
    tone = (np.sin(2 * np.pi * phase_of(f)) + 0.25 * np.sin(2 * np.pi * phase_of(2 * f))) * (0.8 + 0.2 * np.sin(2 * np.pi * phase_of(8 + 14 * k)))
    gather = band_sweep(noise(d, int(gen.integers(1 << 30))), lambda q: 1500 + 3000 * q, lambda q: 900.0) * k * 0.5
    envelope = np.clip(k / 0.7, 0, 1) ** 1.2 * np.clip((d - t) / 0.04, 0, 1)
    return room((tone * 0.45 + gather) * envelope, 0.5, 0.12)


def white_line_release(v, gen, p):
    d = 0.9
    n = samples(d)
    t = np.arange(n) / RATE
    air = highpass(band_sweep(noise(d, int(gen.integers(1 << 30))), lambda q: 1000 * p + 4800 * p * (1 - q) ** 3, lambda q: 1400.0), 350.0) * env_ad(t, 0.006, 6) * 1.2
    line = np.sin(2 * np.pi * phase_of(3100 * p - 900 * p * t / d)) * env_ad(t, 0.004, 5) * 0.25
    crack = highpass(noise(d, int(gen.integers(1 << 30))), 2000) * np.exp(-t * 90) * 0.7
    return room(air + line + crack, 0.5, 0.1)


def frost_step(v, gen, p):
    d = 0.5
    n = samples(d)
    t = np.arange(n) / RATE
    k = t / d
    crunch = crackle(n, gen, 900, np.clip(1 - t / 0.09, 0, 1), 3000 * p, 2000, 0.003) * 1.4
    whoosh = band_sweep(noise(d, int(gen.integers(1 << 30))), lambda q: 700 * p + 2600 * p * np.sin(np.pi * q), lambda q: 1100.0) * env_hump(k, 0.3, 1.4) * 0.9
    ice = tinks(n, gen, 6, 0.02, 0.3, 4500, 9000, (0.03, 0.1), (40, 90), 1.5)
    return room(crunch + whoosh + ice, 0.35, 0.1)


def white_core(v, gen, p):
    """Der weiße Kern der Rüstung: ein kleiner Druckstoß nach außen, dazu feiner Eisstaub."""
    d = 2.2
    n = samples(d)
    t = np.arange(n) / RATE
    sub = tone_sweep(66, 36, d, 2.0) * env_ad(t, 0.006, 4.0) * 1.1
    exhale = band_sweep(noise(d, int(gen.integers(1 << 30))), lambda q: 2800 - 2000 * q ** 0.5, lambda q: 1400 - 700 * q) * env_ad(t, 0.02, 3.0) * 1.0
    dust = tinks(n, gen, 30, 0.03, 1.2, 4000, 10000, (0.02, 0.08), (30, 80), 1.4)
    ring = glass(hz('E6'), 2.0)[:, 0] * 0.12
    return room(distort(sub, 1.3) + exhale + dust + place(n, 0.0, ring), 1.0, 0.2)


def wingscale_flap(v, gen, p):
    d = 0.6
    t = np.arange(samples(d)) / RATE
    k = t / d
    whomp = band_sweep(noise(d, int(gen.integers(1 << 30))), lambda q: 380 * p + 900 * p * np.sin(np.pi * min(q / 0.5, 1.0)), lambda q: 520.0) * env_hump(k, 0.22, 1.4) * 1.6
    flutter = bandpass(noise(d, int(gen.integers(1 << 30))), 1900 * p, 1000) * (0.5 + 0.5 * np.sin(2 * np.pi * 27 * p * t)) * env_hump(k, 0.25, 1.4) * 0.3
    return room(whomp + flutter, 0.5, 0.14)


# --- Tabelle: id -> (Funktion, Varianten, Lautheit in dBFS) ----------------------------------------------------------------

EFFECTS = {
    'arius.slash': (arius_slash, 3, -13), 'arius.thrust': (arius_thrust, 2, -13), 'arius.parry': (arius_parry, 3, -14),
    'arius.kick': (arius_kick, 2, -12), 'arius.wind_step': (arius_wind_step, 2, -13), 'arius.guard': (arius_guard, 2, -17),
    'arius.ice_wall': (arius_ice_wall, 2, -13), 'arius.frost_strip': (arius_frost_strip, 2, -16),
    'arius.stab_ground': (arius_stab_ground, 2, -12), 'arius.grip': (arius_grip, 2, -14),
    'arius.arrow_cut': (arius_arrow_cut, 3, -15), 'arius.null_cut': (arius_null_cut, 2, -13),
    'cut.reverse_wind': (cut_reverse_wind, 1, -13), 'cut.charge': (cut_charge, 1, -15), 'cut.strike': (cut_strike, 1, -11),
    'cut.split': (cut_split, 1, -10),
    'nivaris.roar': (nivaris_roar, 2, -10), 'nivaris.inhale': (nivaris_inhale, 2, -13), 'nivaris.breath': (nivaris_breath, 2, -11),
    'nivaris.wing': (nivaris_wing, 3, -12), 'nivaris.land': (nivaris_land, 2, -10), 'nivaris.claw': (nivaris_claw, 3, -12),
    'nivaris.tail': (nivaris_tail, 2, -12), 'nivaris.choke': (nivaris_choke, 2, -12), 'nivaris.crash': (nivaris_crash, 2, -10),
    'glacier.crack': (glacier_crack, 3, -12), 'glacier.collapse': (glacier_collapse, 2, -10), 'ridge.rise': (ridge_rise, 2, -13),
    'ridge.break': (ridge_break, 3, -12), 'crystal.hang': (crystal_hang, 2, -21), 'seal.blizzard': (seal_blizzard, 1, -13),
    'transform.shatter': (transform_shatter, 1, -13), 'transform.pulse': (transform_pulse, 1, -11),
    'resonance.chime': (resonance_chime, 2, -17), 'core.form': (core_form, 1, -12), 'core.pulse': (core_pulse, 2, -10),
    'core.collapse': (core_collapse, 1, -13), 'iai.glint': (iai_glint, 2, -18), 'iai.draw': (iai_draw, 2, -12),
    'hold.impact': (hold_impact, 2, -11), 'lead.switch': (lead_switch, 2, -20),
    'solvane.parry': (solvane_parry, 3, -15), 'white_line.charge': (white_line_charge, 1, -16),
    'white_line.release': (white_line_release, 2, -13), 'frost_step': (frost_step, 3, -15), 'white_core': (white_core, 1, -12),
    'wingscale.flap': (wingscale_flap, 3, -15),
}
MUSIC = ('temple', 'glacier', 'heart', 'finale')
# Klänge, deren Ende hart sein soll (rückwärts, abgerissener Wind): kein Abschneiden der Stille, kein langer Fade.
HARD_END = {'cut.reverse_wind', 'seal.blizzard', 'cut.charge'}


def file_name(sound_id, v):
    return f"{sound_id.replace('.', '_')}_{v + 1}"


def build(sound_id):
    make, variants, target = EFFECTS[sound_id]
    results = []
    for v in range(variants):
        gen = rng(sound_id, v)
        x = make(v, gen, PITCH[v])
        if sound_id not in HARD_END:
            x = trim_tail(x)
        x = level(x, target)
        # Ein- und Ausblenden nach dem Pegeln (das Pegeln zieht den Gleichanteil ab, sonst stünde Sample 0 nicht auf null).
        x = fade(x, 0.002, 0.006 if sound_id in HARD_END else 0.03)
        # 4 ms Stille vorweg: Vorbis verschmiert sehr harte Einsätze sonst bis ins erste Sample.
        x = np.concatenate([np.zeros(samples(0.004)), x])
        encode(os.path.join(OUT, file_name(sound_id, v) + '.ogg'), x, quality=5)
        results.append((file_name(sound_id, v), len(x) / RATE, float(np.max(np.abs(x))), short_rms_max(x)))
    return results


def write_sounds_json():
    sounds = {}
    for name in MUSIC:
        sounds[f'music.{name}'] = {'sounds': [{'name': f'hha_arius:music/{name}', 'stream': True}]}
    for sound_id, (_, variants, _) in EFFECTS.items():
        sounds[sound_id] = {'subtitle': f'subtitles.hha_arius.{sound_id}',
                            'sounds': [f'hha_arius:sfx/{file_name(sound_id, v)}' for v in range(variants)]}
    path = os.path.join(ASSETS, 'sounds.json')
    if os.path.exists(path):  # Sprachzeilen (voice.*) gehören einem anderen Generator; erhalten.
        with open(path, encoding='utf8') as f:
            sounds.update({k: v for k, v in json.load(f).items() if k.startswith('voice.')})
    with open(path, 'w', encoding='utf8') as f:
        json.dump(sounds, f, indent=2)
        f.write('\n')


def main():
    ids = sys.argv[1:] or list(EFFECTS)
    for sound_id in ids:
        for name, seconds, peak, loud in build(sound_id):
            print(f'{name:28s} {seconds:5.2f} s  Spitze {peak:.2f}  laut {20 * np.log10(loud):6.1f} dBFS', flush=True)
    if not sys.argv[1:]:
        write_sounds_json()
        print('sounds.json geschrieben')


if __name__ == '__main__':
    main()
