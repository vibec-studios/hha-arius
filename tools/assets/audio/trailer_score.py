"""Trailer-Musik nach Bild (Variante B): eigene Komposition auf dem Arius-Leitmotiv, deren Schläge genau auf den
Schnitten des Videos liegen. Statt eines festen Tempos gilt eine Beat-Karte (Liste der Schlagzeiten in Sekunden,
erzeugt von tmp/trailer/sound/tonkarte.py); jedes Segment des Schnitts ist in seine Schläge geteilt.

    PYTHONPATH=tmp/pylib python tools/assets/audio/trailer_score.py <beats.json> <ausgabe.wav> [stems-ordner]

Bogen: Kälte (fast leer) → Arius erwacht (tiefer Schlag, Harfe, Herzschlag) → Tempelkampf (Taiko, Ostinato, Horn) →
Ausrüstung (heller, E-Dur, Glas, dichte Toms) → Aufbau (Tremolo-Treppe, Herzschlag schneller, Wirbel) → Stille →
Höhepunkt (Blech mit dem Motiv tief, Chor, volle Taiko; Dur-Wendung bei der weißen Form) → Finale (Harfe, Chor) →
Titel (Blech-Akkord, Chor). Effekte (Whoosh, Einschläge, Riser) kommen getrennt aus trailer_sfx.py.
"""
import json
import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import numpy as np  # noqa: E402

from arius_kit import (  # noqa: E402
    HEAD, MOTIF, MOTIF_MAJOR, RATE, WIDE, Score, brass, choir, glass, harp, horn, hz, render, samples, soft_drum,
    strings, taiko, tom,
)
from trailer_music import write_wav  # noqa: E402


class MapScore(Score):
    """Score, deren Schläge über eine Beat-Karte auf echte Zeiten fallen (Bruchteile werden interpoliert)."""

    def __init__(self, beat_times, seconds):
        super().__init__(96, int(math.ceil(seconds / 0.625)) + 1)
        self.bt = list(beat_times)

    def at(self, beat):
        i = int(math.floor(beat))
        f = beat - i
        if i < 0:
            return beat * self.beat
        if i >= len(self.bt) - 1:
            return self.bt[-1] + (beat - (len(self.bt) - 1)) * self.beat
        return self.bt[i] + (self.bt[i + 1] - self.bt[i]) * f


def low():
    return taiko(54.0, 1.4) * 0.62


def mid():
    return taiko(84.0, 0.8, 12) * 0.55


def small():
    return tom(132.0) * 0.6


def fight_bar(s, bar, level=1.0, busy=False):
    pattern = [(0, low, 1.0), (0.75, small, 0.35), (1, mid, 0.55), (1.5, small, 0.4), (2, low, 0.85),
               (2.5, mid, 0.5), (3, low, 0.7), (3.25, small, 0.35), (3.5, mid, 0.65), (3.75, small, 0.45)]
    if busy:
        pattern += [(x * 0.5 + 0.25, small, 0.22) for x in range(8)]
    s.drums(bar, 1, pattern, level)


def roll(s, start, beats, level=1.0):
    n = int(beats * 4)
    for i in range(n):
        s.add(start + i * 0.25, (mid if i % 2 else low)(), level * (0.35 + 0.65 * i / n), 0.2)


def compose(beat_times, total=90.0):
    s = MapScore(beat_times, total + 10.0)
    idx = lambda t: int(np.argmin([abs(b - t) for b in beat_times]))  # noqa: E731
    B = {name: idx(t) for name, t in [
        ('arius', 12.367), ('card', 17.367), ('fight', 19.867), ('gear', 37.6), ('build', 50.267), ('silence', 60.333),
        ('cut', 62.833), ('solvane', 70.4), ('collapse', 77.967), ('white', 79.233), ('hole', 80.5), ('finale', 83.033),
        ('title', 86.8)]}

    # --- Kälte: tiefe leere Quinte, ganz leise; eine Glocke spielt den Kopf des Motivs. ------------------------------
    s.add(0, strings(hz('E1'), 13.0, 0.2), 0.05, 0.6)
    s.add(2, strings(hz('B1'), 11.0, 0.2), 0.035, 0.6)
    s.melody(6, HEAD, glass, 0.07, octave=1, send=0.85, plucked=True)
    s.add(12, choir(hz('E3'), 6.0, 'u'), 0.03, 0.7)

    # --- Arius erwacht: Harfe mit dem Motiv, Herzschlag, Streicher in Em. ---------------------------------------------
    a = B['arius']
    s.chords(a, ['Em', 'C'], 4, strings, 0.035, send=0.5)
    s.melody(a, MOTIF[:6], harp, 0.15, send=0.5, plucked=True)
    beat, gap = float(a), 2.0
    while beat < B['fight'] - 0.6:
        s.add(beat, soft_drum(42.0), 0.32 + (beat - a) * 0.015, 0.25)
        s.add(beat + 0.28, soft_drum(39.0), 0.16, 0.25)
        beat += gap
        gap = max(1.0, gap * 0.85)
    c = B['card']
    s.add(c, brass(hz('E2'), 3.0, 0.8), 0.12, 0.45)
    s.add(c, brass(hz('B2'), 3.0, 0.8), 0.08, 0.45)
    s.add(c, choir(hz('E4'), 3.5, 'o'), 0.05, 0.6)
    s.rise(B['fight'], 3, 0.3)

    # --- Tempelkampf: Taiko, Ostinato, Horn mit dem Motiv. -------------------------------------------------------------
    f = B['fight']
    names = ['Em', 'C', 'D', 'E5', 'Em', 'C', 'Am']
    s.crash(f, 0.35)
    for bar in range(f, B['gear'], 4):
        fight_bar(s, bar, 0.95)
    s.ostinato(f, names, 4, step=0.5, gain=0.2, octave=1)
    s.bass(f, names, 4, octave=2, gain=0.22, bright=0.6)
    s.chords(f, names, 4, strings, 0.045, table=WIDE, send=0.35)
    s.melody(f + 4, MOTIF, horn, 0.2, octave=-1, send=0.4)
    s.melody(f + 20, MOTIF[:6], horn, 0.22, octave=0, send=0.4)

    # --- Ausrüstung: heller, E-Dur-Wendung, Glas-Arpeggien, dichte Toms. ------------------------------------------------
    g = B['gear']
    gear = ['A', 'B', 'E', 'C#m', 'A']
    s.crash(g, 0.3)
    for bar in range(g, B['build'], 4):
        fight_bar(s, bar, 0.95, busy=True)
    s.ostinato(g, gear, 4, step=0.25, gain=0.15, octave=1, pattern=(0, 1, 2, 1))
    s.bass(g, gear, 4, octave=2, gain=0.22, bright=0.7)
    s.chords(g, gear, 4, strings, 0.045, table=WIDE, send=0.35)
    s.arpeggio(g, gear, 4, step=0.5, gain=0.07, octave=2, voice=glass, send=0.6)
    s.melody(g + 8, MOTIF_MAJOR[:4], brass, 0.22, octave=-1, send=0.35)

    # --- Aufbau: Tremolo-Treppe, Herzschlag schneller, Wirbel bis zur Karte, dann nichts. ----------------------------------
    b = B['build']
    cardb = idx(59.067)
    for i, note in enumerate(['E3', 'F3', 'F#3', 'G3', 'G#3']):
        st = b + i * (cardb - b) / 5.0
        s.add(st, strings(hz(note), 2.4, 1.3), 0.03 + i * 0.016, 0.45)
        s.add(st, strings(hz(note[:-1] + '2'), 2.4, 0.6), 0.03 + i * 0.012, 0.45)
    beat, gap = float(b), 2.0
    while beat < cardb - 0.5:
        s.add(beat, soft_drum(42.0), 0.45 + (beat - b) * 0.02, 0.25)
        s.add(beat, low(), 0.25 + (beat - b) * 0.03, 0.25)
        beat += gap
        gap = max(0.5, gap * 0.82)
    roll(s, cardb - 2, 2, 0.9)
    s.add(cardb, low(), 1.0, 0.3)
    s.add(cardb, brass(hz('E2'), 1.2, 1.2), 0.18, 0.4)
    # Stille: B['silence'] bis B['cut'] bleibt leer.

    # --- Höhepunkt -----------------------------------------------------------------------------------------------------------
    k = B['cut']
    rev = ['Em', 'C', 'D', 'E5', 'Em', 'C', 'D', 'Bm']
    s.crash(k, 0.5)
    for note in ('E2', 'B2', 'E3', 'G3'):
        s.add(k, brass(hz(note), 2.5, 1.2), 0.18, 0.4)
    for bar in range(k + 4, B['collapse'], 4):
        fight_bar(s, bar, 1.0)
    s.chords(k, rev, 4, strings, 0.09, table=WIDE, send=0.4)
    s.chords(k + 4, rev[1:], 4, choir, 0.06, send=0.6)
    s.bass(k, rev, 4, octave=1, gain=0.3)
    s.ostinato(k + 4, rev[1:], 4, step=0.5, gain=0.2, octave=1)
    so = B['solvane']
    s.crash(so, 0.45)
    s.melody(so, MOTIF, brass, 0.42, octave=-2, send=0.35)
    s.melody(so, MOTIF, horn, 0.16, octave=-1, send=0.45)
    s.chords(so, ['Em', 'C'], 4, choir, 0.08, send=0.6, octave=1)
    # Einsturz: alles bricht weg, nur Sub und Becken; weiße Form: E-Dur, hell.
    w = B['white']
    s.add(B['collapse'], low(), 1.0, 0.3)
    s.crash(w, 0.4)
    s.chords(w, ['E', 'C#m', 'A'], 2, strings, 0.09, table=WIDE, send=0.45)
    s.chords(w, ['E', 'C#m', 'A'], 2, choir, 0.07, send=0.6, octave=1)
    s.bass(w, ['E', 'C#m', 'A'], 2, octave=1, gain=0.26)
    s.melody(w, MOTIF_MAJOR[:5], horn, 0.24, send=0.45, stretch=0.5)
    s.melody(w, MOTIF_MAJOR[:5], glass, 0.11, octave=1, send=0.7, plucked=True, stretch=0.5)
    fight_bar(s, B['hole'], 1.0)

    # --- Finale: Harfe, Chor, liegende Quinte. ----------------------------------------------------------------------------
    fi = B['finale']
    s.melody(fi, MOTIF[:5], harp, 0.2, send=0.55, plucked=True, stretch=0.5)
    s.add(fi, strings(hz('E3'), 4.0, 0.4), 0.05, 0.55)
    s.add(fi, strings(hz('B3'), 4.0, 0.4), 0.04, 0.55)
    s.add(fi, choir(hz('E4'), 4.0, 'u'), 0.04, 0.65)

    # --- Titel: Blech-Akkord auf der offenen Quinte, Chor, Pauke. ----------------------------------------------------------
    t = B['title']
    for note in ('E2', 'B2', 'E3', 'B3', 'F#4'):
        s.add(t, brass(hz(note), 4.0, 1.0), 0.14, 0.5)
    s.add(t, choir(hz('E4'), 4.5, 'a'), 0.07, 0.7)
    s.add(t, choir(hz('B4'), 4.5, 'a'), 0.05, 0.7)
    s.add(t, low(), 1.0, 0.35)

    data = render(s, size=1.6, hall_seconds=6.0, target_rms=0.12, drive=1.5, air=0.5)
    data = np.roll(data, -samples(0.03), axis=0)[:samples(total + 0.5)]
    return data


def main():
    beats = json.load(open(sys.argv[1]))
    out = sys.argv[2]
    data = compose(beats)
    write_wav(out, data)
    print(f'Partitur B: {len(data) / RATE:.1f} s -> {out}')


if __name__ == '__main__':
    main()
