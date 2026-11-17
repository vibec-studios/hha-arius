"""Trailer-Musik für HHA Arius (ca. 90 s, nicht als Schleife): Kälte, Herzschlag, Taiko, Stille vor dem Schnitt,
tiefes Blech bei Solvanes Enthüllung, das Motiv in Dur bei der Verwandlung, Schluss-Schlag zur Titelkarte.

Aufruf (aus dem Projektordner):
    PYTHONPATH=tmp/pylib python tools/assets/audio/trailer_music.py [ausgabe.wav]
    PYTHONPATH=tmp/pylib python tools/assets/audio/trailer_music.py --drums trommeln.wav   # nur die Trommelspur

Alles synthetisch mit arius_kit/synth (keine Samples). 96 bpm, ein Schlag = 0,625 s, ein Takt = 2,5 s. MARKS nennt die
Abschnittsgrenzen der eigenen Komposition. Der fertige Trailer (Schnitt: tmp/trailer/edit.py + cut_list.py) liegt auf
einem CC0-Track gleichen Tempos („Fall of Nightmares“, nene, OpenGameArt); von hier stammen dort nur die leisen Akzente
(Wind, Einschläge, Schluss-Schlag) und die Trommelspur aus drums_stem().
"""
import os
import sys
import wave

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import numpy as np  # noqa: E402

from arius_kit import (  # noqa: E402
    CH, HEAD, MOTIF, MOTIF_MAJOR, RATE, WIDE, Score, brass, choir, cymbal, glass, harp, horn, hz, render, samples,
    soft_drum, strings, swell, taiko, tom, transpose,
)

BPM = 96
BEAT = 60.0 / BPM

# Abschnitte in Schlägen (→ Sekunden über BEAT).
COLD, PULSE, FIGHT, RISE, SILENCE, CUT, REVEAL, TRANSFORM, GEAR, HOLE, FINALE, TITLE, END = (
    0, 16, 32, 60, 64, 66, 72, 88, 96, 116, 124, 136, 146)
MARKS = {name: beat * BEAT for name, beat in [
    ('cold', COLD), ('pulse', PULSE), ('fight', FIGHT), ('rise', RISE), ('silence', SILENCE), ('cut', CUT),
    ('reveal', REVEAL), ('transform', TRANSFORM), ('gear', GEAR), ('hole', HOLE), ('finale', FINALE),
    ('title', TITLE), ('end', END)]}


def low():
    return taiko(54.0, 1.4) * 0.62


def mid():
    return taiko(84.0, 0.8, 12) * 0.55


def small():
    return tom(132.0) * 0.6


def boom():
    """Sehr tiefer Einschlag: zwei Taiko übereinander plus Sub-Abfall."""
    t = np.arange(samples(2.4)) / RATE
    sub = np.sin(2 * np.pi * (38.0 * t + 30.0 * (1 - np.exp(-t * 9.0)) / 9.0)) * np.exp(-t * 1.6) * np.minimum(1.0, t / 0.004)
    body = taiko(46.0, 2.0, 5)
    n = max(len(body), len(sub))
    out = np.zeros((n, 2))
    out[:len(body)] += body * 0.9
    out[:len(sub)] += np.stack([sub, sub], axis=1) * 0.7
    return out


def wind(seconds, gust=0.18, seed=3):
    """Kalter Wind: gefiltertes Rauschen mit langsamen Böen (stereo)."""
    n = samples(seconds)
    t = np.arange(n) / RATE
    gen = np.random.default_rng(seed)
    out = np.zeros((n, 2))
    from arius_kit import bandpass
    for c in range(2):
        x = bandpass(gen.uniform(-1, 1, n), 900 + 300 * c, 1400)
        env = 0.55 + gust * np.sin(2 * np.pi * 0.11 * t + c) + 0.25 * np.sin(2 * np.pi * 0.047 * t + 2 * c)
        out[:, c] = x * env
    fade = np.minimum(1.0, t / 1.5) * np.minimum(1.0, (seconds - t) / 1.5)
    return out * fade[:, None] * 0.5


def fight_bar(s, bar, level=1.0):
    pattern = [(0, low, 1.0), (0.75, small, 0.35), (1, mid, 0.55), (1.5, small, 0.4), (2, low, 0.85),
               (2.5, mid, 0.5), (3, low, 0.7), (3.25, small, 0.35), (3.5, mid, 0.65), (3.75, small, 0.45)]
    s.drums(bar, 1, pattern, level)


def trailer():
    s = Score(BPM, END + 6)

    # --- Kälte (0–10 s): Wind, leere Quinte ganz tief, Glas spielt den Kopf des Motivs. -----------------------------
    s.add(COLD, wind(PULSE * BEAT + 2.0), 0.55, 0.3)
    s.add(COLD, strings(hz('E1'), (PULSE + 2) * BEAT, 0.2), 0.08, 0.5)
    s.add(COLD + 2, strings(hz('B1'), PULSE * BEAT, 0.2), 0.05, 0.5)
    s.melody(4, HEAD, glass, 0.16, octave=1, send=0.8, plucked=True)
    s.melody(10, HEAD, glass, 0.12, octave=1, send=0.85, plucked=True)
    s.rise(PULSE, 4, 0.25)

    # --- Herzschlag (10–20 s): Arius erhebt sich. Harfe spielt das Motiv, Puls wird dichter. -------------------------
    s.add(PULSE, wind(PULSE * BEAT), 0.3, 0.3)
    beat, gap = float(PULSE), 2.0
    while beat < FIGHT - 0.5:
        s.add(beat, soft_drum(42.0), 0.36 + (beat - PULSE) * 0.02, 0.25)
        s.add(beat + 0.28, soft_drum(39.0), 0.18 + (beat - PULSE) * 0.01, 0.25)
        beat += gap
        gap = max(1.0, gap * 0.86)
    s.melody(PULSE, MOTIF, harp, 0.2, octave=0, send=0.5, plucked=True)
    s.chords(PULSE, ['Em', 'C', 'D', 'E5'], 4, strings, 0.05, send=0.5)
    for i, note in enumerate(['E3', 'F3', 'F#3', 'G3']):
        s.add(PULSE + i * 4, strings(hz(note), 4.2 * BEAT, 1.1), 0.025 + i * 0.012, 0.45)
    s.rise(FIGHT, 4, 0.45)

    # --- Kampf im Tempel (20–37,5 s): Taiko, Streicher-Ostinato, das Motiv im Horn. ----------------------------------
    s.crash(FIGHT, 0.4)
    s.add(FIGHT, boom(), 0.9, 0.3)
    for bar in range(FIGHT, RISE, 4):
        fight_bar(s, bar, 0.95)
    names = ['Em', 'C', 'D', 'E5', 'Em', 'C', 'Am']
    s.ostinato(FIGHT, names, 4, step=0.5, gain=0.2, octave=1)
    s.bass(FIGHT, names, 4, octave=2, gain=0.24, bright=0.6)
    s.chords(FIGHT, names, 4, strings, 0.05, table=WIDE, send=0.35)
    s.melody(FIGHT + 4, MOTIF, horn, 0.2, octave=-1, send=0.4)
    s.melody(FIGHT + 20, MOTIF[:6], horn, 0.22, octave=0, send=0.4)

    # --- Aufbau und Stille (37,5–41,25 s): Riser, Wind dreht, dann nichts. -------------------------------------------
    s.add(RISE, strings(hz('B2'), 4 * BEAT, 1.4), 0.12, 0.4)
    s.add(RISE, strings(hz('C3'), 4 * BEAT, 1.4), 0.08, 0.4)
    s.drums(RISE, 1, [(0, low, 0.8), (1, low, 0.85), (2, mid, 0.7), (2.5, mid, 0.8), (3, low, 1.0), (3.25, mid, 0.8), (3.5, mid, 0.9), (3.75, small, 0.9)], 1.0)
    s.rise(SILENCE, 4, 0.55)
    # (SILENCE bis CUT bleibt leer.)

    # --- Der Schnitt (41,25 s): ein Einschlag, Becken, Blechstoß, dann Nachklang. -----------------------------------
    s.add(CUT, boom(), 1.25, 0.35)
    s.crash(CUT, 0.5)
    for note in ('E2', 'B2', 'E3', 'G3'):
        s.add(CUT, brass(hz(note), 2.5 * BEAT, 1.2), 0.18, 0.4)
    s.add(CUT, strings(hz('E1'), (REVEAL - CUT) * BEAT, 0.2), 0.1, 0.5)
    s.add(CUT + 1, glass(hz('B6'), 4.0), 0.12, 0.85)
    s.add(CUT + 2.5, glass(hz('E6'), 4.0), 0.08, 0.85)
    s.drums(REVEAL - 2, 1, [(0, mid, 0.5), (0.5, mid, 0.6), (1, low, 0.8), (1.5, low, 1.0)], 1.0, beats_per_bar=2)
    s.rise(REVEAL, 2, 0.4)

    # --- Solvane (45–55 s): tiefes Blech mit dem Motiv, Chor, weite Akkorde, Taiko. ---------------------------------
    s.crash(REVEAL, 0.45)
    s.add(REVEAL, boom(), 1.1, 0.3)
    rev = ['Em', 'C', 'D', 'E5']
    s.chords(REVEAL, rev, 4, strings, 0.1, table=WIDE, send=0.4)
    s.chords(REVEAL, rev, 4, choir, 0.07, send=0.6)
    s.bass(REVEAL, rev, 4, octave=1, gain=0.32)
    s.melody(REVEAL, MOTIF, brass, 0.4, octave=-2, send=0.35)
    s.melody(REVEAL, MOTIF, horn, 0.16, octave=-1, send=0.45)
    for bar in range(REVEAL + 4, TRANSFORM, 4):
        fight_bar(s, bar, 0.8)
    s.rise(TRANSFORM, 4, 0.4)

    # --- Verwandlung (55–60 s): Motiv in E-Dur, hell, Glas-Splitter. -------------------------------------------------
    s.crash(TRANSFORM, 0.35)
    s.add(TRANSFORM, boom(), 0.8, 0.3)
    s.chords(TRANSFORM, ['E', 'C#m'], 4, strings, 0.09, table=WIDE, send=0.45)
    s.chords(TRANSFORM, ['E', 'C#m'], 4, choir, 0.06, send=0.6, octave=1)
    s.bass(TRANSFORM, ['E', 'C#m'], 4, octave=1, gain=0.26)
    s.melody(TRANSFORM, MOTIF_MAJOR[:6], horn, 0.24, octave=0, send=0.45)
    s.melody(TRANSFORM, MOTIF_MAJOR[:6], glass, 0.11, octave=1, send=0.7, plucked=True)

    # --- Ausrüstung (60–72,5 s): schneller, Achtel-Taiko, Ostinato. --------------------------------------------------
    s.crash(GEAR, 0.35)
    s.add(GEAR, boom(), 0.85, 0.3)
    gear = ['A', 'B', 'E', 'C#m', 'A', 'B']
    s.ostinato(GEAR, gear, 4, step=0.25, gain=0.17, octave=1, pattern=(0, 1, 2, 1))
    s.bass(GEAR, gear, 4, octave=2, gain=0.24, bright=0.7)
    s.chords(GEAR, gear, 4, strings, 0.05, table=WIDE, send=0.35)
    for bar in range(GEAR, HOLE - 4, 4):
        fight_bar(s, bar, 1.0)
        s.drums(bar, 1, [(x * 0.5 + 0.25, small, 0.25) for x in range(8)], 1.0)
    s.melody(GEAR + 8, MOTIF_MAJOR[:4], brass, 0.26, octave=-1, send=0.35)
    s.drums(HOLE - 4, 1, [(i * 0.25, mid if i % 2 else low, 0.5 + i * 0.03) for i in range(16)], 1.0)
    s.rise(HOLE, 4, 0.6)

    # --- Weißes Loch (72,5–77,5 s): Einschlag, Sog, Chor wächst. -------------------------------------------------------
    s.add(HOLE, boom(), 1.2, 0.35)
    s.crash(HOLE, 0.45)
    s.chords(HOLE, ['Em', 'C'], 4, choir, 0.09, send=0.7, octave=1)
    s.chords(HOLE, ['Em', 'C'], 4, strings, 0.1, table=WIDE, send=0.5)
    s.add(HOLE, strings(hz('E1'), 8 * BEAT, 0.3), 0.12, 0.5)
    for i, note in enumerate(['E5', 'G5', 'B5', 'E6']):
        s.add(HOLE + 1 + i * 1.5, glass(hz(note), 3.0), 0.1, 0.8)
    s.drums(HOLE, 2, [(0, low, 0.9), (2, low, 0.7), (3, mid, 0.5)], 1.0)

    # --- Finale (77,5–85 s): fast leer, Harfe mit dem Motiv, Puls. Dann der Schluss-Schlag zum Titel. --------------
    s.add(FINALE, wind(10.0), 0.2, 0.3)
    s.melody(FINALE, MOTIF[:7], harp, 0.24, send=0.55, plucked=True)
    s.add(FINALE, strings(hz('E2'), 10 * BEAT, 0.3), 0.06, 0.5)
    s.add(FINALE, strings(hz('B2'), 10 * BEAT, 0.3), 0.05, 0.5)
    for b in (FINALE, FINALE + 2, FINALE + 4, FINALE + 6, FINALE + 8):
        s.add(b, soft_drum(41.0), 0.32, 0.25)
    s.rise(TITLE, 3, 0.5)
    s.add(TITLE, boom(), 1.3, 0.4)
    s.crash(TITLE, 0.5)
    for note in ('E2', 'B2', 'E3', 'B3', 'F#4'):
        s.add(TITLE, brass(hz(note), 5 * BEAT, 1.0), 0.14, 0.45)
    s.chords(TITLE, ['E5'], 8, choir, 0.08, send=0.7)
    s.add(TITLE, strings(hz('E1'), 9 * BEAT, 0.2), 0.1, 0.5)
    s.add(TITLE + 3, glass(hz('B5'), 5.0), 0.12, 0.85)

    data = render(s, size=1.6, hall_seconds=6.0, target_rms=0.12, drive=1.5, air=0.5)
    data = np.roll(data, -samples(0.03), axis=0)
    # Nicht als Schleife: Ende abblenden und abschneiden.
    end = samples(END * BEAT + 3.5)
    data = data[:end].copy()
    fade = samples(2.0)
    data[-fade:] *= np.linspace(1.0, 0.0, fade)[:, None]
    return data


def drums_stem(fight_bars=7, gear_bars=5, build_bars=4):
    """Nur Trommeln (96 bpm, Takt = 2,5 s), als leise Spur unter einem fremden Track desselben Tempos:
    Kampf-Taiko, dann dichter mit Achtel-Toms, dann ein Herzschlag, der schneller wird und in Stille endet."""
    bars = fight_bars + gear_bars + build_bars
    s = Score(BPM, bars * 4 + 8)
    for b in range(fight_bars):
        fight_bar(s, b * 4, 0.9)
    for b in range(fight_bars, fight_bars + gear_bars):
        fight_bar(s, b * 4, 1.0)
        s.drums(b * 4, 1, [(x * 0.5 + 0.25, small, 0.25) for x in range(8)], 1.0)
    start = (fight_bars + gear_bars) * 4
    beat, gap = float(start), 2.0
    while beat < start + build_bars * 4 - 1.0:
        s.add(beat, soft_drum(42.0), 0.5 + (beat - start) * 0.03, 0.25)
        s.add(beat + 0.28, soft_drum(39.0), 0.25 + (beat - start) * 0.015, 0.25)
        beat += gap
        gap = max(0.5, gap * 0.8)
    data = render(s, size=1.2, hall_seconds=4.0, target_rms=0.12, drive=1.3, air=0.3)
    data = np.roll(data, -samples(0.03), axis=0)
    end = samples(bars * 4 * BEAT + 1.0)
    data = data[:end].copy()
    fade = samples(0.8)
    data[-fade:] *= np.linspace(1.0, 0.0, fade)[:, None]
    return data


def write_wav(path, data):
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    pcm = (np.clip(data, -1.0, 1.0) * 32000).astype('<i2')
    with wave.open(path, 'wb') as w:
        w.setnchannels(2)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(pcm.tobytes())


def main():
    if len(sys.argv) > 2 and sys.argv[1] == '--drums':
        data = drums_stem()
        write_wav(sys.argv[2], data)
        print(f'drums: {len(data) / RATE:.1f} s -> {os.path.abspath(sys.argv[2])}')
        return
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, '..', '..', '..', 'tmp', 'trailer', 'music.wav')
    data = trailer()
    write_wav(out, data)
    print(f'trailer: {len(data) / RATE:.1f} s, Spitze {np.max(np.abs(data)):.3f} -> {os.path.abspath(out)}')
    for k, v in MARKS.items():
        print(f'  {k:10s} {v:6.2f} s')


if __name__ == '__main__':
    main()
