"""Zusätzliche Musik für die adaptive Regie von 1.10.0 (client/fx/AriusMusic.kt) als nahtlose Stereo-Schleifen.

Aufruf (aus dem Projektordner, ffmpeg im PATH):
    PYTHONPATH=tools/assets python tools/assets/audio/arius_music_cues.py [hunt ride summit qte]

Alle teilen das Leitmotiv „Arius“ (arius_kit.MOTIF) und die Tonart e-Moll der übrigen Stücke:
- hunt:   88 bpm, Jagd im Whiteout: tiefer Liegeton, Herzschlag, Streicher-Tremolo in Halbtönen, das Motiv nur als
          ferner Kopf im Horn. Spannung, kaum Melodie.
- ride:  132 bpm, Ritt auf Solvane: treibende Taiko, Streicher-Ostinato in Achteln, das Motiv im Blech, Chor im
          Mittelteil. Gleiche Tonart wie glacier, damit Übergänge sitzen.
- summit: 100 bpm, Gipfelduell: Harfe und Streicher wie Klingen, schweres Schlagwerk, das Motiv wechselt zwischen Moll
          und Dur (Arius gegen den Spieler), Chor hoch.
- qte:   132 bpm, 8 Schläge: Spannungsschicht über dem Ritt (tickender Puls, Tremolo, steigende Quinte).
"""
import os
import sys

from arius_kit import (
    HEAD, MOTIF, MOTIF_MAJOR, SOUNDS, WIDE, Score, brass, choir, encode, glass, harp, horn, hz, pizz, render, soft_drum,
    strings, taiko, tom, transpose,
)
import numpy as np

OUT = os.path.join(SOUNDS, 'music')


def low():
    return taiko(54.0, 1.4) * 0.62


def mid():
    return taiko(84.0, 0.8, 12) * 0.55


def small():
    return tom(132.0) * 0.6


def chor_a(f, d):
    return choir(f, d, 'a')


def chor_o(f, d):
    return choir(f, d, 'o')


def hunt():
    """Die Jagd: Atem, Herzschlag, Tremolo. Das Motiv zeigt nur seinen Kopf, weit weg im Horn."""
    s = Score(88, 64)
    # Grund: leere Quinte E1/B1 über die ganze Schleife (in vier Bögen, damit sie atmet).
    for b in range(0, 64, 16):
        s.add(b, strings(hz('E1'), 16.4 * s.beat, 0.2), 0.075, 0.5)
        s.add(b, strings(hz('B1'), 16.4 * s.beat, 0.25), 0.05, 0.5)
    # Herzschlag: doppelt, jede zweite Zählzeit, im B-Teil dichter.
    for b in range(0, 64, 2):
        g = 0.24 if b < 32 else 0.3
        s.add(b, soft_drum(42.0), g, 0.25)
        s.add(b + 0.32, soft_drum(39.0), g * 0.5, 0.25)
        if b >= 32:
            s.add(b + 1, soft_drum(40.0), g * 0.35, 0.25)
    # Tremolo-Treppe in Halbtönen, zweimal (E – F – F# – G), leise und hoch.
    for rep in (0, 32):
        for i, note in enumerate(['E3', 'F3', 'F#3', 'G3']):
            s.add(rep + 4 + i * 6, strings(hz(note), 6.3 * s.beat, 1.15), 0.028 + i * 0.008, 0.5)
            s.add(rep + 4 + i * 6, strings(hz(transpose(note, 1)), 6.3 * s.beat, 1.3), 0.018 + i * 0.006, 0.55)
    # Gezupfte Unruhe: tiefe Pizzicati auf E und F, unregelmäßig.
    for b, note in ((3, 'E2'), (5.5, 'F2'), (11, 'E2'), (13.5, 'G2'), (19, 'E2'), (27, 'F2'), (35, 'E2'), (37.5, 'F2'),
                    (43, 'E2'), (45.5, 'A#2'), (51, 'E2'), (59, 'F2')):
        s.add(b, pizz(hz(note)), 0.11, 0.3)
    # Der Kopf des Motivs, ganz fern.
    s.melody(16, HEAD, horn, 0.07, octave=-1, send=0.65)
    s.melody(48, HEAD, horn, 0.08, octave=-1, send=0.65)
    # Eissplitter im Wind.
    for b, note in ((7, 'B6'), (22, 'F#6'), (30.5, 'E7'), (41, 'C7'), (55, 'B6'), (61.5, 'G6')):
        s.add(b, glass(hz(note), 2.6), 0.06, 0.85)
    return render(s, size=1.7, hall_seconds=7.5, target_rms=0.095, drive=1.3, air=0.5)


def ride():
    """Der Ritt: Taiko und Streicher treiben, das Motiv im Blech, im Mittelteil Chor und Hörner."""
    s = Score(132, 96)
    drive = [(0, low, 1.0), (0.75, small, 0.28), (1, mid, 0.45), (1.5, small, 0.3), (2, low, 0.75), (2.5, mid, 0.5),
             (3, low, 0.6), (3.5, small, 0.35)]
    gallop = [(0, low, 1.0), (0.5, small, 0.3), (1, mid, 0.55), (2, low, 0.8), (2.5, small, 0.35), (2.75, small, 0.3),
              (3, mid, 0.6), (3.5, mid, 0.5)]
    a = ['Em', 'C', 'D', 'E5']
    b = ['Am', 'C', 'Em', 'D', 'Am', 'C', 'D', 'B']
    # Einleitung (16): Ostinato und Schritt allein.
    s.ostinato(0, a * 2, 2, step=0.5, gain=0.14, octave=0)
    s.bass(0, a * 2, 2, octave=1, gain=0.2)
    s.drums(0, 4, drive, 0.9)
    s.melody(8, HEAD, horn, 0.12, octave=-1, send=0.4)
    # A (32): das Motiv im tiefen Blech, Hörner darüber, weite Streicherakkorde.
    s.crash(16, 0.28)
    s.chords(16, a * 2, 4, strings, 0.07, table=WIDE, send=0.35)
    s.ostinato(16, a * 4, 2, step=0.5, gain=0.15, octave=0)
    s.bass(16, a * 4, 2, octave=1, gain=0.24)
    s.melody(16, MOTIF, brass, 0.3, octave=-1, send=0.32)
    s.melody(32, MOTIF, brass, 0.26, octave=-1, send=0.32)
    s.melody(32, MOTIF, horn, 0.13, octave=0, send=0.4)
    s.drums(16, 8, drive, 1.0)
    # B (32): Chor und Gegenstimme, galoppierender Schritt, Aufschwung.
    s.crash(48, 0.26)
    s.chords(48, b, 4, chor_o, 0.055, send=0.5)
    s.chords(48, b, 4, strings, 0.065, table=WIDE, send=0.35)
    s.ostinato(48, b, 4, step=0.5, gain=0.14, octave=0)
    s.bass(48, b, 4, octave=1, gain=0.24)
    counter = [(0, 'E5', 4), (4, 'G5', 4), (8, 'B5', 3), (11, 'A5', 1), (12, 'F#5', 4),
               (16, 'E5', 4), (20, 'E5', 2), (22, 'G5', 2), (24, 'A5', 4), (28, 'D#5', 4)]
    s.melody(48, counter, horn, 0.15, octave=-1, send=0.42)
    s.drums(48, 8, gallop, 1.0)
    s.rise(76, 4, 0.3)
    # A' (16): Motiv in voller Besetzung, dann zur Schleife zurück.
    s.crash(80, 0.3)
    s.chords(80, a, 4, chor_a, 0.06, send=0.5)
    s.chords(80, a, 4, strings, 0.075, table=WIDE, send=0.35)
    s.ostinato(80, a * 2, 2, step=0.5, gain=0.15, octave=0)
    s.bass(80, a * 2, 2, octave=1, gain=0.26)
    s.melody(80, MOTIF, brass, 0.32, octave=-1, send=0.32)
    s.melody(80, MOTIF, horn, 0.13, octave=0, send=0.42)
    s.drums(80, 4, drive, 1.05)
    return render(s, size=1.3, hall_seconds=5.0, target_rms=0.12, drive=1.6, air=0.4)


def summit():
    """Das Gipfelduell: Klingen aus Harfe und Streichern, Motiv in Moll gegen Dur, schweres Schlagwerk, hoher Chor."""
    s = Score(100, 96)
    heavy = [(0, low, 1.0), (1, small, 0.25), (1.5, mid, 0.4), (2, low, 0.7), (3, mid, 0.55), (3.5, small, 0.3)]
    duel = [(0, low, 1.0), (0.5, mid, 0.35), (1, small, 0.3), (1.5, mid, 0.45), (2, low, 0.85), (2.75, small, 0.3),
            (3, low, 0.6), (3.5, mid, 0.5)]
    a = ['Em', 'C', 'D', 'B']
    maj = ['E', 'C#m', 'A', 'B']
    # Einleitung: Harfe wie Klingenschläge, darunter tiefe Streicher.
    s.chords(0, a, 4, strings, 0.06, send=0.45, octave=-1)
    s.arpeggio(0, a, 4, step=0.5, gain=0.11, octave=1, order=(0, 2, 1, 2))
    s.bass(0, a, 4, octave=2, gain=0.16)
    s.drums(8, 2, heavy, 0.8)
    # A: Motiv in Moll im Blech, Harfe treibt.
    s.crash(16, 0.3)
    s.chords(16, a * 2, 4, strings, 0.075, table=WIDE, send=0.35)
    s.arpeggio(16, a * 2, 4, step=0.5, gain=0.1, octave=1, order=(0, 2, 1, 2))
    s.bass(16, a * 2, 4, octave=1, gain=0.24)
    s.melody(16, MOTIF, brass, 0.3, octave=-1, send=0.32)
    s.melody(32, MOTIF, horn, 0.18, octave=0, send=0.4)
    s.drums(16, 8, heavy, 1.0)
    # B: dasselbe Motiv in Dur, Chor hoch – die Antwort des Spielers.
    s.crash(48, 0.3)
    s.chords(48, maj * 2, 4, strings, 0.075, table=WIDE, send=0.38)
    s.chords(48, maj * 2, 4, chor_a, 0.055, send=0.55, octave=1)
    s.bass(48, maj * 2, 4, octave=1, gain=0.24)
    s.melody(48, MOTIF_MAJOR, horn, 0.2, octave=0, send=0.42)
    s.melody(48, MOTIF_MAJOR, glass, 0.08, octave=1, send=0.7, plucked=True)
    s.melody(64, MOTIF_MAJOR, brass, 0.26, octave=-1, send=0.34)
    s.drums(48, 8, duel, 1.05)
    s.rise(76, 4, 0.3)
    # A': Moll kehrt zurück, alles zusammen, dann zur Schleife.
    s.crash(80, 0.32)
    s.chords(80, a, 4, chor_o, 0.06, send=0.5)
    s.chords(80, a, 4, strings, 0.08, table=WIDE, send=0.35)
    s.arpeggio(80, a, 4, step=0.5, gain=0.11, octave=1, order=(0, 2, 1, 2))
    s.bass(80, a, 4, octave=1, gain=0.26)
    s.melody(80, MOTIF, brass, 0.33, octave=-1, send=0.32)
    s.drums(80, 4, duel, 1.05)
    return render(s, size=1.5, hall_seconds=6.0, target_rms=0.115, drive=1.5, air=0.45)


def qte():
    """Spannungsschicht (8 Schläge im Tempo des Ritts): tickender Puls, Tremolo auf der Quinte, ein steigender Ruf."""
    s = Score(132, 8)
    for i in range(16):
        b = i * 0.5
        s.add(b, small(), 0.22 if i % 2 == 0 else 0.13, 0.15)
    s.add(0, strings(hz('B3'), 8.2 * s.beat, 1.25), 0.05, 0.4)
    s.add(0, strings(hz('B4'), 8.2 * s.beat, 1.35), 0.035, 0.45)
    s.melody(0, [(0, 'B4', 2), (2, 'C5', 2), (4, 'C#5', 2), (6, 'D5', 2)], horn, 0.08, octave=0, send=0.4)
    return render(s, size=0.9, hall_seconds=2.5, target_rms=0.09, drive=1.4, air=0.4)


THEMES = {'hunt': hunt, 'ride': ride, 'summit': summit, 'qte': qte}


def main():
    names = sys.argv[1:] or list(THEMES)
    for name in names:
        data = THEMES[name]()
        encode(os.path.join(OUT, f'{name}.ogg'), data, quality=4)
        rms = float(np.sqrt(np.mean(data ** 2)))
        print(f'music.{name}: {len(data) / 44100.0:.1f} s, RMS {rms:.3f}, Spitze {np.max(np.abs(data)):.3f}', flush=True)


if __name__ == '__main__':
    main()
