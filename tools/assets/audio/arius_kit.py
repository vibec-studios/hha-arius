"""Gemeinsame Bausteine für Musik und Klangeffekte von HHA Arius (alles synthetisiert, keine Samples).

Baut auf tools/assets/synth.py auf (HHAs additiver Synthesizer) und ergänzt:
- das Leitmotiv „Arius“ (aufsteigende Quarte, offene Auflösung) in Moll, Dur und tiefer Lage,
- eine Partitur in Schlägen mit frei setzbaren Akkord-Lagen,
- einen langen, kalten Hall und ein Mischpult-Render mit wählbarer Raumgröße,
- Pegel-Normierung für Effekte (gleiche Kurzzeit-Lautheit, weiche Spitzenbegrenzung).

numpy: pip install numpy --target <projekt>/tmp/pylib, dann PYTHONPATH darauf setzen.
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))  # tools/assets (synth.py)

import numpy as np  # noqa: E402

from synth import (  # noqa: E402,F401
    RATE, Mix, adsr, bandpass, bell, brass, choir, cymbal, distort, encode, harp, highpass, horn, hz, lowpass, noise,
    phase_of, pizz, reed, samples, spectral, staccato, strings, sweep, swell, taiko, timeline, tom,
)

ROOT = os.path.abspath(os.path.join(HERE, '..', '..', '..'))
ASSETS = os.path.join(ROOT, 'src', 'main', 'resources', 'assets', 'hha_arius')
SOUNDS = os.path.join(ASSETS, 'sounds')


# --- Leitmotiv „Arius“ -------------------------------------------------------------------------------------------
# (Schlag, Ton, Länge in Schlägen). 16 Schläge, Harmonie je 4 Schläge: Em | C | D | E5 (Quinte + None, keine Terz = offen).
# Phrase a beginnt mit der Quarte B→E, Phrase b antwortet mit der Quarte A→D und endet offen auf der Quinte (B).
MOTIF = [(0, 'B4', 1), (1, 'E5', 2.5), (3.5, 'F#5', .5), (4, 'G5', 2), (6, 'F#5', 1), (7, 'D5', 1),
         (8, 'A4', 1), (9, 'D5', 2.5), (11.5, 'E5', .5), (12, 'B4', 4)]
# Dur-Wendung (E-Dur): dieselbe Quarte, die Antwort steigt und bleibt offen auf der hohen Quinte stehen.
MOTIF_MAJOR = [(0, 'B4', 1), (1, 'E5', 2.5), (3.5, 'F#5', .5), (4, 'G#5', 2), (6, 'F#5', 1), (7, 'E5', 1),
               (8, 'C#5', 1), (9, 'F#5', 2.5), (11.5, 'G#5', .5), (12, 'B5', 4)]
# Nur die Kopfquarte, für Signale und Ausklänge.
HEAD = [(0, 'B4', 1), (1, 'E5', 3)]

# Akkorde als konkrete Lagen (Tonnamen mit Oktave). Eng = für Flächen, weit = für Blech/Gewicht.
CH = {
    'Em': ('E3', 'B3', 'G4'), 'C': ('C3', 'G3', 'E4'), 'D': ('D3', 'A3', 'F#4'), 'E5': ('E3', 'B3', 'F#4'),
    'Am': ('A2', 'E3', 'C4'), 'Bm': ('B2', 'F#3', 'D4'), 'B': ('B2', 'F#3', 'D#4'), 'G': ('G2', 'D3', 'B3'),
    'Cmaj7': ('C3', 'G3', 'B3', 'E4'),
    'E': ('E3', 'B3', 'G#4'), 'C#m': ('C#3', 'G#3', 'E4'), 'A': ('A2', 'E3', 'C#4'), 'F#m': ('F#2', 'C#3', 'A3'),
}
WIDE = {
    'Em': ('E2', 'B2', 'G3', 'E4'), 'C': ('C2', 'G2', 'E3', 'C4'), 'D': ('D2', 'A2', 'F#3', 'D4'),
    'E5': ('E2', 'B2', 'F#3', 'B3'), 'Am': ('A1', 'E2', 'C3', 'A3'), 'B': ('B1', 'F#2', 'D#3', 'B3'),
    'G': ('G1', 'D2', 'B2', 'G3'), 'Bm': ('B1', 'F#2', 'D3', 'B3'),
}
ROOTS = {'Em': 'E', 'C': 'C', 'D': 'D', 'E5': 'E', 'Am': 'A', 'Bm': 'B', 'B': 'B', 'G': 'G', 'Cmaj7': 'C',
         'E': 'E', 'C#m': 'C#', 'A': 'A', 'F#m': 'F#'}


def transpose(note, octaves):
    """Ton um ganze Oktaven verschieben: 'B4', -2 -> 'B2'."""
    return note[:-1] + str(int(note[-1]) + octaves)


# --- eigene Instrumente -----------------------------------------------------------------------------------------

_cache = {}


def glass(freq, seconds=3.2):
    """Kühle Glasglocke (Celesta-artig): wenige, leicht unharmonische Teiltöne, weicher Anschlag."""
    key = ('glass', round(freq, 2), seconds)
    if key not in _cache:
        n = samples(seconds)
        t = np.arange(n) / RATE
        out = np.zeros(n)
        for ratio, gain, decay in ((1.0, 1.0, 1.3), (2.0, 0.22, 2.4), (3.01, 0.16, 3.6), (4.17, 0.08, 5.5), (5.43, 0.04, 8.0)):
            if freq * ratio < 16000:
                out += gain * np.exp(-t * decay) * np.sin(2 * np.pi * freq * ratio * t)
        out *= np.minimum(1.0, t / 0.004)
        left = out * (1.0 + 0.04 * np.sin(2 * np.pi * 0.9 * t))
        right = out * (1.0 - 0.04 * np.sin(2 * np.pi * 0.9 * t))
        _cache[key] = np.stack([left, right], axis=1) * 0.45
    return _cache[key]


def soft_drum(pitch=44.0, seconds=1.6):
    """Gedämpfte tiefe Trommel für den ruhigen Puls im Finale: kein Fell-Klatschen, nur Druck."""
    key = ('soft_drum', pitch, seconds)
    if key not in _cache:
        t = timeline(seconds)
        f = pitch * (1.0 + 0.5 * np.exp(-t * 18.0))
        body = np.sin(2 * np.pi * phase_of(f)) * np.exp(-t * 3.2) * np.minimum(1.0, t / 0.006)
        # Ein schwacher Oberton, damit der Puls auch auf kleinen Lautsprechern zu ahnen ist.
        body += 0.22 * np.sin(2 * np.pi * phase_of(f * 2.62)) * np.exp(-t * 7.0) * np.minimum(1.0, t / 0.006)
        _cache[key] = np.stack([body, body], axis=1) * 0.8
    return _cache[key]


# --- Partitur -----------------------------------------------------------------------------------------------------


class Score:
    """Schreibt in Schlägen. Alles landet in synth.Mix (Ringpuffer), Ausklänge laufen also nahtlos in den Anfang."""

    def __init__(self, bpm, beats):
        self.beat = 60.0 / bpm
        self.beats = beats
        self.mix = Mix(beats * self.beat)

    def at(self, beat):
        return beat * self.beat

    def add(self, beat, sound, gain, send=0.3, pan=0.0):
        self.mix.add(self.at(beat), sound, gain, send, pan)

    def melody(self, start, notes, voice, gain, send=0.32, octave=0, pan=0.0, stretch=1.0, plucked=False):
        """voice(freq, sekunden). plucked=True: Länge egal (Harfe/Glas), sonst Note so lang wie notiert."""
        for b, name, length in notes:
            f = hz(transpose(name, octave))
            sound = voice(f) if plucked else voice(f, length * stretch * self.beat)
            self.add(start + b * stretch, sound, gain, send, pan)

    def chords(self, start, names, each, voice, gain, send=0.36, table=None, octave=0):
        """Liegende Akkorde: voice(freq, sekunden). table = CH (eng) oder WIDE (weit)."""
        table = table or CH
        for i, name in enumerate(names):
            for note in table[name]:
                self.add(start + i * each, voice(hz(transpose(note, octave)), each * self.beat), gain, send)

    def bass(self, start, names, each, octave=2, gain=0.2, bright=0.4, send=0.15):
        for i, name in enumerate(names):
            self.add(start + i * each, strings(hz(f'{ROOTS[name]}{octave}'), each * self.beat, bright), gain, send)

    def ostinato(self, start, names, each, step=0.5, gain=0.25, pattern=(0, 0, 1, 0, 2, 0, 1, 0), octave=0, send=0.12):
        for i, name in enumerate(names):
            notes = CH[name]
            for s in range(int(round(each / step))):
                note = transpose(notes[pattern[s % len(pattern)] % len(notes)], octave)
                accent = 1.0 if s % 4 == 0 else (0.78 if s % 2 == 0 else 0.6)
                self.add(start + i * each + s * step, staccato(hz(note), min(0.24, step * self.beat * 0.85)), gain * accent, send)

    def arpeggio(self, start, names, each, step=1.0, gain=0.12, octave=1, voice=harp, send=0.45, order=(0, 1, 2, 1)):
        for i, name in enumerate(names):
            notes = CH[name]
            for s in range(int(round(each / step))):
                note = transpose(notes[order[s % len(order)] % len(notes)], octave)
                self.add(start + i * each + s * step, voice(hz(note)), gain, send)

    def drums(self, start, bars, pattern, gain=1.0, beats_per_bar=4, send=0.22):
        for bar in range(bars):
            for pos, sound, level in pattern:
                self.add(start + bar * beats_per_bar + pos, sound() if callable(sound) else sound, level * gain, send)

    def crash(self, beat, gain=0.35):
        self.add(beat, cymbal(), gain, 0.35)

    def rise(self, beat, beats=4, gain=0.35):
        seconds = beats * self.beat
        self.mix.add(self.at(beat) - seconds, swell(seconds), gain, 0.2)


# --- Hall und Render -----------------------------------------------------------------------------------------------


def hall(seconds=4.0, size=1.0, seed=41, predelay=0.02):
    """Stereo-Raum: frühe Reflexionen, darunter ein Nachhall. size > 1 = längerer Ausklang (Eishalle)."""
    n = samples(seconds)
    t = np.arange(n) / RATE
    out = np.zeros((n, 2))
    gen = np.random.default_rng(seed)
    for channel in range(2):
        bright = highpass(gen.uniform(-1, 1, n), 2200) * np.exp(-t * 4.0 / size)
        mid = lowpass(gen.uniform(-1, 1, n), 4200) * np.exp(-t * 2.3 / size)
        dark = lowpass(gen.uniform(-1, 1, n), 800) * np.exp(-t * 1.5 / size) * 1.2
        tail = (bright * 0.6 + mid * 0.75 + dark) * np.minimum(1.0, np.maximum(t - predelay, 0) / 0.03)
        for _ in range(11):
            at = gen.integers(int((predelay + 0.006) * RATE), int((predelay + 0.09) * RATE))
            tail[at] += gen.uniform(-1.3, 1.3)
        out[:, channel] = tail
    return out / np.sqrt(np.sum(out * out, axis=0))


def render(score, size=1.0, hall_seconds=None, wet_gain=1.0, target_rms=0.11, peak=0.89, drive=1.4, air=0.5):
    """Wie synth.Mix.render, aber mit wählbarer Raumgröße und etwas mehr Luft in den Höhen (kalt, klar)."""
    mix = score.mix
    room = hall(hall_seconds or min(10.0, 3.2 * size + 1.0), size)
    wet = np.stack([np.fft.irfft(np.fft.rfft(mix.wet[:, c]) * np.fft.rfft(room[:, c], mix.n), mix.n) for c in range(2)], axis=1)
    out = mix.dry + wet * wet_gain
    out -= np.mean(out, axis=0)

    def tilt(f):
        low = 0.6 + 0.4 * np.clip((f - 80.0) / 240.0, 0.0, 1.0)
        high = 1.0 + air * np.clip((f - 2000.0) / 4000.0, 0.0, 1.0)
        # Unter 35 Hz nur Rumpeln, über 11 kHz nur Rauschen von Becken und Hall: beides sanft abschneiden.
        edges = 1.0 / (1.0 + (35.0 / np.maximum(f, 1.0)) ** 4) / (1.0 + (f / 11000.0) ** 2)
        return low * high * edges
    out = np.stack([spectral(out[:, 0], tilt), spectral(out[:, 1], tilt)], axis=1)
    out = np.tanh(out / np.max(np.abs(out)) * drive) / np.tanh(drive)
    rms = float(np.sqrt(np.mean(out * out)))
    # Ringpuffer drehen: die Naht liegt dann 30 ms vor dem ersten Schlag im Ausklang statt genau auf einem Einsatz.
    out = np.roll(out, samples(0.03), axis=0)
    return out * min(peak, target_rms / rms)


# --- Effekt-Werkzeuge ------------------------------------------------------------------------------------------------


def room(x, seconds=0.8, wet=0.2, decay=4.0, seed=3, tone=4500):
    """Kurzer Faltungshall (mono), damit Effekte im Raum stehen statt am Ohr kleben. Kleines wet = trocken."""
    t = np.arange(samples(seconds)) / RATE
    tail = lowpass(np.random.default_rng(seed).uniform(-1, 1, len(t)), tone) * np.exp(-t * decay)
    tail /= np.sqrt(np.sum(tail * tail))
    n = len(x) + len(t)
    size = n + len(t)
    wet_signal = np.fft.irfft(np.fft.rfft(x, size) * np.fft.rfft(tail, size), size)[:n]
    dry = np.zeros(n)
    dry[:len(x)] = x
    return dry + wet_signal * wet * 6.0


def layer(*parts):
    n = max(len(p) for p in parts)
    out = np.zeros(n)
    for p in parts:
        out[:len(p)] += p
    return out


def place(n, at, part):
    """part ab Sekunde `at` in einen Puffer der Länge n legen (abgeschnitten, wenn zu lang)."""
    out = np.zeros(n)
    start = int(at * RATE)
    if start < n:
        end = min(n, start + len(part))
        out[start:end] = part[:end - start]
    return out


def after(t, t0):
    return np.maximum(t - t0, 0.0)


def gate(t, t0):
    return (t >= t0).astype(float)


def tone_sweep(start, end, seconds, power=2.0):
    k = np.linspace(0.0, 1.0, samples(seconds))
    return np.sin(2 * np.pi * phase_of(end + (start - end) * (1.0 - k) ** power))


def band_sweep(x, center, width):
    """Rauschband, dessen Mitte und Breite sich über die Zeit ändern; center/width: Funktionen von k in 0..1."""
    return sweep(x, lambda f, k: np.exp(-(((f - center(k)) / max(width(k), 30.0)) ** 2)))


def partials(t, spec, phase_seed=0):
    """Summe gedämpfter Sinustöne: spec = [(frequenz, pegel, abklingrate), ...]."""
    gen = np.random.default_rng(phase_seed)
    out = np.zeros(len(t))
    for f, g, d in spec:
        if f < 17000:
            out += g * np.exp(-t * d) * np.sin(2 * np.pi * f * t + gen.uniform(0, 6.28))
    return out


def ice_ring(t, base, gen, bright=1.0, decay=1.0, count=7):
    """Heller Eisklang: unharmonische Teiltöne über `base`, je höher desto schneller verklingend."""
    ratios = (1.0, 1.493, 2.236, 3.19, 4.45, 5.83, 7.61)[:count]
    spec = []
    for i, r in enumerate(ratios):
        f = base * r * gen.uniform(0.993, 1.007)
        spec.append((f, (0.82 ** i) * (bright if i > 2 else 1.0), (5.0 + i * 3.2) * decay))
        spec.append((f * 1.0035, (0.82 ** i) * 0.35, (5.5 + i * 3.2) * decay))  # leichte Schwebung
    return partials(t, spec, int(gen.integers(1 << 30)))


def tinks(n, gen, count, start, end, f_lo, f_hi, gain=(0.05, 0.25), decay=(30, 90), curve=1.0):
    """Viele winzige Eis-Splitter (Eisregen). curve > 1 ballt sie am Anfang."""
    out = np.zeros(n)
    for _ in range(count):
        at = start + (end - start) * gen.uniform(0, 1) ** curve
        length = samples(gen.uniform(0.03, 0.12))
        tt = np.arange(length) / RATE
        f = gen.uniform(f_lo, f_hi)
        d = gen.uniform(*decay)
        piece = (np.sin(2 * np.pi * f * tt) + 0.5 * np.sin(2 * np.pi * f * 1.51 * tt) + 0.25 * np.sin(2 * np.pi * f * 2.33 * tt)) * np.exp(-tt * d)
        piece *= np.minimum(1.0, tt / 0.0008)
        out += place(n, at, piece * gen.uniform(*gain))
    return out


def crackle(n, gen, rate, envelope, center=2600, width=1800, grain=0.004):
    """Knistern: dünne Impulse (Poisson), gefiltert. envelope: Array der Länge n (Dichte und Pegel)."""
    probability = rate / RATE * envelope
    clicks = (gen.uniform(0, 1, n) < probability) * gen.uniform(-1, 1, n)
    kernel_t = np.arange(samples(grain)) / RATE
    kernel = gen.uniform(-1, 1, len(kernel_t)) * np.exp(-kernel_t / (grain / 4))
    grains = np.convolve(clicks, kernel)[:n]
    return bandpass(grains, center, width) * np.sqrt(np.maximum(envelope, 0))


def ice_chirp(seconds, f0=4200.0, f1=260.0, length=0.09, seed=0):
    """Dispersiver Riss-Laut von gespanntem Eis („piu“): sehr schneller Abwärtsglissando-Ton."""
    t = timeline(seconds)
    k = np.clip(t / length, 0, 1)
    f = f1 + (f0 - f1) * (1 - k) ** 3
    body = np.sin(2 * np.pi * phase_of(f)) * np.exp(-t / (length * 1.4))
    click = highpass(noise(seconds, seed), 1500) * np.exp(-t * 260.0)
    return body * 0.9 + click * 0.7


def fade(x, fade_in=0.002, fade_out=0.02):
    x = x.copy()
    a = min(len(x), samples(fade_in))
    b = min(len(x), samples(fade_out))
    x[:a] *= np.linspace(0, 1, a)
    x[len(x) - b:] *= np.linspace(1, 0, b)
    return x


def trim_tail(x, threshold_db=-62.0, keep=0.03):
    """Schneidet stillen Nachlauf ab (unter threshold relativ zur Spitze), lässt `keep` Sekunden stehen."""
    peak = float(np.max(np.abs(x))) or 1.0
    level = 10 ** (threshold_db / 20.0) * peak
    above = np.nonzero(np.abs(x) > level)[0]
    end = min(len(x), (int(above[-1]) if len(above) else len(x)) + samples(keep))
    return x[:end]


def short_rms_max(x, window=0.1, hop=0.01):
    """Lautester 100-ms-Abschnitt (RMS) als Lautheitsmaß für kurze Effekte."""
    w, h = samples(window), samples(hop)
    if len(x) <= w:
        return float(np.sqrt(np.mean(x * x)))
    sq = np.concatenate([[0.0], np.cumsum(x * x)])
    starts = np.arange(0, len(x) - w, h)
    return float(np.sqrt(np.max((sq[starts + w] - sq[starts]) / w)))


def soft_limit(x, threshold=0.6, ceiling=0.92):
    """Unter threshold linear, darüber weich gegen ceiling (keine harte Übersteuerung)."""
    a = np.abs(x)
    span = ceiling - threshold
    over = threshold + span * np.tanh((a - threshold) / span)
    return np.sign(x) * np.where(a <= threshold, a, over)


def level(x, target_db=-13.0):
    """Normiert auf gleiche Kurzzeit-Lautheit (lautester 100-ms-RMS = target_db dBFS) und begrenzt weich."""
    x = x - np.mean(x)
    x = x * (10 ** (target_db / 20.0) / max(short_rms_max(x), 1e-9))
    return soft_limit(x)
