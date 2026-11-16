"""Die vier Musikstücke von HHA Arius als nahtlose Stereo-Schleifen.

Aufruf (aus dem Projektordner):
    PYTHONPATH=tmp/pylib python tools/assets/audio/arius_music.py [temple glacier heart finale]

Alle teilen das Leitmotiv „Arius“ (arius_kit.MOTIF): die aufsteigende Quarte B→E, die Antwort A→D und eine offene
Auflösung auf der Quinte über einem Akkord ohne Terz. Ruhig und kühl, nie pathetisch.
- temple:  72 bpm, Harfe und Glas über tiefen Streichern, sehr viel Raum, Spannung durch Zurückhaltung.
- glacier: 76 bpm, Nivaris: das Motiv zwei Oktaven tiefer im Blech, Taiko, weite Akkorde.
- heart:  112 bpm, Streicher-Ostinato, Chor, das Motiv in Moll, nach E-Dur gewendet und wieder zurück.
- finale:  60 bpm, fast leer: eine Harfe spielt das Motiv, darunter ein ruhiger, gedämpfter Puls.
Die Partitur liegt in einem Ringpuffer (synth.Mix): Nachhall und Ausklänge am Ende laufen in den Anfang weiter.
"""
import os
import sys

from arius_kit import (
    CH, HEAD, transpose, MOTIF, MOTIF_MAJOR, SOUNDS, WIDE, Score, brass, choir, encode, glass, harp, horn, hz, pizz, render,
    soft_drum, strings, taiko, tom,
)
import numpy as np

OUT = os.path.join(SOUNDS, 'music')


def low():
    return taiko(54.0, 1.4) * 0.62


def mid():
    return taiko(84.0, 0.8, 12) * 0.55


def small():
    return tom(132.0) * 0.6


def cello(f, d):
    return strings(f, d, 0.45)


def violins(f, d):
    return strings(f, d, 1.2)


def chor_a(f, d):
    return choir(f, d, 'a')


def chor_o(f, d):
    return choir(f, d, 'o')


def chor_u(f, d):
    return choir(f, d, 'u')


# --- I: Tempel ------------------------------------------------------------------------------------------------------

def temple():
    """Der Tempel von Eirholm: kalt und sparsam. Viel Hall, wenig Noten; das Motiv zeigt sich nur zweimal ganz."""
    s = Score(72, 96)
    a = ['Em', 'C', 'D', 'E5']
    prog = a * 3 + ['Am', 'C', 'Em', 'D'] + a + ['Am', 'C', 'D', 'E5']  # Einleitung, A, A', B, A'', Ausklang
    # Grund: tiefe Streicher, sehr leise, Akkord alle 4 Schläge (24 Felder = 96 Schläge).
    s.chords(0, prog, 4, cello, 0.075, send=0.5, octave=-1)
    s.bass(0, prog, 4, octave=2, gain=0.09, bright=0.3, send=0.3)

    # Einleitung: einzelne Harfentöne im Raum, eine ferne Glocke.
    for b, note, g in ((0, 'E5', 0.2), (3, 'B5', 0.14), (6.5, 'F#5', 0.13), (10, 'E6', 0.1), (13, 'B4', 0.16)):
        s.add(b, harp(hz(note), 3.0), g, 0.7)
    s.add(0, glass(hz('E6'), 4.0), 0.12, 0.8)

    # A: das Motiv auf der Harfe, ganz leise vom Glas eine Oktave höher verdoppelt.
    s.melody(16, MOTIF, lambda f: harp(f, 3.0), 0.26, send=0.62, plucked=True)
    s.melody(16, MOTIF, lambda f: glass(f, 3.6), 0.05, send=0.8, octave=1, plucked=True)

    # A': die tiefen Streicher übernehmen das Motiv (eine Oktave tiefer), die Harfe zupft gebrochen darunter.
    s.melody(32, MOTIF, cello, 0.13, send=0.5, octave=-1)
    s.arpeggio(32, prog[8:12], 4, step=1.0, gain=0.085, octave=1, send=0.6, order=(0, 2, 1, 2))

    # B: Am – C – Em – D, kein Motiv. Lange Glastöne, ein leiser Pizzicato-Puls: das Warten.
    for b, note in ((48, 'E6'), (52, 'C6'), (56, 'B5'), (60, 'A5')):
        s.add(b, glass(hz(note), 4.0), 0.1, 0.8)
    for b in range(48, 64, 2):
        root = CH[prog[b // 4]][0]
        s.add(b, pizz(hz(root), 0.6), 0.16 if b % 4 == 0 else 0.1, 0.4)
    s.add(48, strings(hz('B4'), 16 * s.beat, 0.6), 0.04, 0.6)

    # A'': Harfe stellt Phrase a, das Cello antwortet mit Phrase b (Kanon über den Raum).
    s.melody(64, MOTIF[:6], lambda f: harp(f, 3.0), 0.25, send=0.62, plucked=True, pan=-0.2)
    s.melody(64, MOTIF[6:], cello, 0.13, send=0.5, octave=-1, pan=0.2)
    s.add(64, glass(hz('B5'), 4.0), 0.08, 0.8)

    # Ausklang: wieder nur einzelne Töne, der offene E5-Akkord fällt in den Anfang zurück.
    for b, note, g in ((80, 'B4', 0.18), (82.5, 'E5', 0.15), (86, 'D5', 0.12), (89, 'F#5', 0.12), (92, 'B4', 0.14)):
        s.add(b, harp(hz(note), 3.0), g, 0.7)
    s.add(88, glass(hz('F#6'), 4.0), 0.06, 0.85)
    return render(s, size=1.9, hall_seconds=9.0, wet_gain=1.15, target_rms=0.085, drive=1.2, air=0.6)


# --- II: Gletscher --------------------------------------------------------------------------------------------------

def glacier():
    """Die Gletscherschwelle (Nivaris): tiefes Blech, Taiko, weite Akkorde, Gewicht. Das Motiv zwei Oktaven tiefer."""
    s = Score(76, 112)
    heavy = [(0, low, 1.0), (2.5, mid, 0.32), (3, low, 0.6)]
    stride = [(0, low, 1.0), (1, small, 0.22), (2, low, 0.55), (2.5, mid, 0.35), (3, low, 0.7), (3.5, small, 0.3)]
    a = ['Em', 'C', 'D', 'E5']

    # Einleitung: Taiko-Schritte und Hörner, die sich aufbauen.
    intro = ['Em', 'Em', 'C', 'D']
    s.chords(0, intro, 4, horn, 0.07, table=WIDE, send=0.4)
    s.bass(0, intro, 4, octave=1, gain=0.2)
    s.drums(0, 4, heavy, 0.9)
    s.melody(8, HEAD, horn, 0.12, octave=-1, send=0.45)

    # A: das Motiv im tiefen Blech (Posaunen/Tuben-Lage), erst allein, dann mit Hörnern in der Oktave darüber.
    s.chords(16, a * 2, 4, strings, 0.065, table=WIDE, send=0.35)
    s.bass(16, a * 2, 4, octave=1, gain=0.24)
    s.melody(16, MOTIF, brass, 0.3, octave=-2, send=0.32)
    s.melody(32, MOTIF, brass, 0.3, octave=-2, send=0.32)
    s.melody(32, MOTIF, horn, 0.14, octave=-1, send=0.4)
    s.drums(16, 8, heavy)
    s.crash(16, 0.22)

    # B: Am – C – Em – D – Am – C – D – B. Tiefer Chor, eine lange Gegenstimme in den Hörnern, schwererer Schritt.
    b = ['Am', 'C', 'Em', 'D', 'Am', 'C', 'D', 'B']
    s.chords(48, b, 4, chor_o, 0.05, send=0.5, octave=0)
    s.chords(48, b, 4, strings, 0.06, table=WIDE, send=0.35)
    s.bass(48, b, 4, octave=1, gain=0.24)
    counter = [(0, 'E4', 4), (4, 'G4', 4), (8, 'B4', 3), (11, 'A4', 1), (12, 'F#4', 4),
               (16, 'E4', 4), (20, 'E4', 2), (22, 'G4', 2), (24, 'A4', 4), (28, 'D#4', 4)]
    s.melody(48, counter, horn, 0.16, octave=-1, send=0.42)
    s.melody(56, [(0, 'A4', 1), (1, 'D5', 3)], brass, 0.22, octave=-2)
    s.melody(72, [(0, 'A4', 1), (1, 'D5', 3)], brass, 0.22, octave=-2)
    s.drums(48, 8, stride, 0.95)
    s.crash(48, 0.2)
    s.rise(80, 4, 0.28)

    # A': alles zusammen, aber nicht lauter als nötig: Blech tief + Hörner + Chor, dann dünnt es zur Schleife aus.
    s.chords(80, a * 2, 4, strings, 0.075, table=WIDE, send=0.35)
    s.chords(80, a, 4, chor_o, 0.055, send=0.5)
    s.bass(80, a * 2, 4, octave=1, gain=0.26)
    s.melody(80, MOTIF, brass, 0.33, octave=-2, send=0.32)
    s.melody(80, MOTIF, brass, 0.13, octave=-1, send=0.36)
    s.melody(80, MOTIF, horn, 0.12, octave=0, send=0.45)
    s.drums(80, 4, stride, 1.05)
    s.crash(80, 0.28)
    # Letzte acht Schläge: nur Phrase b leise in den Hörnern und der Schritt.
    s.melody(96, MOTIF[6:], horn, 0.13, octave=-1, send=0.45, stretch=1.0)
    s.drums(96, 4, heavy, 0.8)
    return render(s, size=1.4, hall_seconds=6.5, target_rms=0.115, drive=1.5, air=0.4)


# --- III: Herz -------------------------------------------------------------------------------------------------------

def heart():
    """Das Herzgewölbe: voller Satz. Ostinato treibt, das Motiv kippt nach E-Dur und kehrt nach e-Moll zurück."""
    s = Score(112, 160)
    drive = [(0, low, 0.8), (1.5, mid, 0.32), (2, low, 0.55), (2.75, small, 0.22), (3, mid, 0.42), (3.5, small, 0.28)]
    light = [(0, low, 0.6), (2, low, 0.4), (3, mid, 0.25)]
    a = ['Em', 'C', 'D', 'E5']
    major = ['E', 'C#m', 'A', 'B']

    # Einleitung: Ostinato allein, dann Trommeln.
    s.ostinato(0, a, 4, gain=0.2)
    s.bass(0, a, 4, octave=2, gain=0.14)
    s.drums(8, 2, light)

    # A: Motiv in Moll, Hörner; dann Streicher + Blech eine Oktave höher.
    s.ostinato(16, a * 2, 4, gain=0.22)
    s.chords(16, a * 2, 4, strings, 0.075, send=0.38)
    s.chords(16, a * 2, 4, chor_a, 0.04, send=0.55)
    s.bass(16, a * 2, 4, octave=2, gain=0.17)
    s.melody(16, MOTIF, horn, 0.2, octave=-1)
    s.melody(32, MOTIF, violins, 0.13, octave=0)
    s.melody(32, MOTIF, brass, 0.14, octave=-1)
    s.drums(16, 8, drive)
    s.crash(16, 0.22)

    # B: nach E-Dur gewendet. Heller, Chor trägt das Motiv, Harfe läuft darüber.
    s.ostinato(48, major * 2, 4, gain=0.22, pattern=(0, 1, 2, 1, 0, 1, 2, 1))
    s.chords(48, major * 2, 4, strings, 0.085, send=0.38)
    s.chords(48, major * 2, 4, chor_a, 0.05, send=0.55)
    s.bass(48, major * 2, 4, octave=2, gain=0.17)
    s.melody(48, MOTIF_MAJOR, chor_a, 0.13, octave=0, send=0.5)
    s.melody(48, MOTIF_MAJOR, horn, 0.14, octave=-1)
    s.melody(64, MOTIF_MAJOR, brass, 0.17, octave=-1)
    s.melody(64, MOTIF_MAJOR, violins, 0.12, octave=0)
    s.arpeggio(48, major * 2, 4, step=0.5, gain=0.07, octave=2, send=0.4, order=(0, 1, 2, 1))
    s.drums(48, 8, drive, 1.05)
    s.crash(48, 0.26)

    # Rückweg: C – D – Am – B, eine lange Chorlinie G–F#–E–D#, die nach e-Moll zurückzieht.
    back = ['C', 'D', 'Am', 'B']
    s.ostinato(80, back, 4, gain=0.21)
    s.chords(80, back, 4, strings, 0.08, send=0.4)
    s.bass(80, back, 4, octave=2, gain=0.18)
    s.melody(80, [(0, 'G5', 4), (4, 'F#5', 4), (8, 'E5', 4), (12, 'D#5', 4)], chor_a, 0.15, send=0.55)
    s.melody(80, [(0, 'E4', 4), (4, 'D4', 4), (8, 'C4', 4), (12, 'B3', 4)], horn, 0.12)
    s.drums(80, 4, light, 1.0)
    s.rise(96, 4, 0.3)

    # A'': wieder Moll, voller Satz. Dreimal das Motiv, jedes Mal eine Stimme mehr.
    s.ostinato(96, a * 3, 4, gain=0.24)
    s.ostinato(96, a * 3, 4, step=0.25, gain=0.07, octave=1, pattern=(0, 1, 2, 1))
    s.chords(96, a * 3, 4, strings, 0.085, send=0.38)
    s.chords(96, a * 3, 4, chor_a, 0.055, send=0.55)
    s.bass(96, a * 3, 4, octave=2, gain=0.19)
    s.melody(96, MOTIF, brass, 0.2, octave=-1)
    s.melody(112, MOTIF, brass, 0.2, octave=-1)
    s.melody(112, MOTIF, chor_a, 0.12, octave=0, send=0.5)
    s.melody(128, MOTIF, brass, 0.21, octave=-1)
    s.melody(128, MOTIF, violins, 0.13, octave=0)
    s.melody(128, MOTIF, chor_a, 0.12, octave=0, send=0.5)
    s.drums(96, 12, drive, 1.1)
    s.crash(96, 0.28)
    s.crash(128, 0.24)

    # Abbau zur Schleife: Ostinato, Bass, leiser werdende Trommeln.
    s.ostinato(144, a, 4, gain=0.2)
    s.bass(144, a, 4, octave=2, gain=0.15)
    s.drums(144, 2, light, 0.8)
    s.drums(152, 2, light, 0.6)
    return render(s, size=1.2, hall_seconds=5.0, target_rms=0.125, drive=1.5, air=0.45)


# --- Finale ----------------------------------------------------------------------------------------------------------

def finale():
    """Der letzte klare Moment: eine Harfe spielt das Motiv im großen Raum. Darunter nur ein ruhiger Puls."""
    s = Score(60, 80)
    # Puls: gedämpfter Doppelschlag alle zwei Schläge (ruhiger Herzschlag), sehr leise.
    for b in range(0, 80, 2):
        s.add(b, soft_drum(44.0), 0.32, 0.25)
        s.add(b + 0.32, soft_drum(41.0), 0.16, 0.25)
    # Ein kaum hörbarer Grund (leere Quinte E–B), damit der Raum nicht ganz trocken ist.
    s.add(0, strings(hz('E2'), 80 * s.beat - 1.0, 0.25), 0.035, 0.5)
    s.add(0, strings(hz('B2'), 80 * s.beat - 1.0, 0.25), 0.025, 0.5)

    play = lambda f: harp(f, 4.0)  # noqa: E731
    s.melody(8, MOTIF, play, 0.3, send=0.75, plucked=True)
    # Zweiter Durchgang: Phrase a gleich, die Antwort bleibt auf der None (F#) offen stehen.
    second = MOTIF[:6] + [(8, 'A4', 1), (9, 'D5', 2.5), (11.5, 'E5', .5), (12, 'F#5', 4)]
    s.melody(40, second, play, 0.28, send=0.75, plucked=True)
    # Nur die Kopfquarte, ganz leise, kurz vor der Schleife.
    s.melody(66, HEAD, play, 0.18, send=0.8, plucked=True)
    return render(s, size=2.2, hall_seconds=10.0, wet_gain=1.25, target_rms=0.06, drive=1.1, air=0.5)


# --- Auftritt an der Nadel ---------------------------------------------------------------------------------------------

def entrance():
    """Nivaris' Auftritt (ca. 33 s): Sturm-Spannung, Enthuellung, Abflug. Kein Takt am Anfang, nur Atem und Herzschlag.
    0-12 s: tiefer Liegeton, ganz leiser Herzschlag, Streicher-Tremolo steigt in Halbtoenen. 12-20 s: Taiko-Schlaege
    werden dichter, Hoerner kuendigen den Kopf des Motivs an. 20 s (Schlag 22): Enthuellung - volles Blech spielt das
    Motiv zwei Oktaven tief, Chor und weite Akkorde, Becken. Danach treibt der Schritt in den Gletscherkampf hinein."""
    s = Score(66, 36)
    # Grund: leere Quinte, sehr tief, und ein Herzschlag, der schneller wird.
    s.add(0, strings(hz('E1'), 22 * s.beat, 0.2), 0.07, 0.5)
    s.add(0, strings(hz('B1'), 22 * s.beat, 0.2), 0.05, 0.5)
    beat = 0.0
    gap = 2.0
    while beat < 21.0:
        s.add(beat, soft_drum(42.0), 0.28 + beat * 0.012, 0.25)
        s.add(beat + 0.3, soft_drum(39.0), 0.14 + beat * 0.006, 0.25)
        beat += gap
        gap = max(0.75, gap * 0.9)
    # Tremolo-Treppe: E - F - F# - G - G# ueber 20 Schlaege, jedes Mal etwas lauter.
    for i, note in enumerate(['E3', 'F3', 'F#3', 'G3', 'G#3']):
        s.add(2 + i * 4, strings(hz(note), 4.2 * s.beat, 1.1), 0.03 + i * 0.012, 0.45)
        s.add(2 + i * 4, strings(hz(transpose(note, 1)), 4.2 * s.beat, 1.3), 0.02 + i * 0.01, 0.5)
    # Ankuendigung: Kopf des Motivs leise im Horn, zweimal, dann Taiko-Wirbel.
    s.melody(10, HEAD, horn, 0.08, octave=-1, send=0.55)
    s.melody(16, HEAD, horn, 0.11, octave=-1, send=0.5)
    s.drums(18, 1, [(0, low, 0.6), (1, low, 0.7), (2, mid, 0.5), (2.5, mid, 0.6), (3, low, 0.85), (3.5, mid, 0.7)], 0.9)
    s.rise(22, 4, 0.4)
    # Enthuellung
    s.crash(22, 0.38)
    s.add(22, low(), 1.1, 0.3)
    s.chords(22, ['Em', 'C'], 4, strings, 0.09, table=WIDE, send=0.4)
    s.chords(22, ['Em', 'C'], 4, chor_a, 0.07, send=0.55)
    s.bass(22, ['Em', 'C', 'D'], 4, octave=1, gain=0.3)
    s.melody(22, MOTIF[:4], brass, 0.36, octave=-2, send=0.35)
    s.melody(22, MOTIF[:4], horn, 0.15, octave=-1, send=0.45)
    # Abflug: Schritt treibt, Akkord oeffnet sich auf D, dann leise in die Schleife.
    s.chords(30, ['D'], 6, strings, 0.06, table=WIDE, send=0.4)
    s.drums(30, 1, [(0, low, 1.0), (1, small, 0.3), (2, low, 0.6), (2.5, mid, 0.4), (3, low, 0.8), (3.5, small, 0.35)], 0.9)
    return render(s, size=1.6, hall_seconds=7.0, target_rms=0.11, drive=1.5, air=0.45)


# --- Gletschersturz und Verwandlung ---------------------------------------------------------------------------------

def transform():
    """Einsturz und Verwandlung (ca. 30 s): Fallen ins Gewoelbe, dann das Motiv in E-Dur, hell und gross, Chor in hoher
    Lage, Glas und Harfe wie Splitter. Endet offen auf der Quinte, damit das Herzgewoelbe anschliessen kann."""
    s = Score(72, 36)
    # Sturz: tiefe Taiko, Streicher stuerzen nach unten.
    s.add(0, low(), 1.0, 0.3)
    s.add(0.5, mid(), 0.6, 0.3)
    for i, note in enumerate(['B4', 'G4', 'E4', 'B3', 'G3', 'E3']):
        s.add(0.5 + i * 0.5, strings(hz(note), 0.6 * s.beat, 1.2), 0.06, 0.4)
    s.add(4, strings(hz('E1'), 8 * s.beat, 0.2), 0.08, 0.5)
    # Stille mit Glas-Splittern (Verwandlung beginnt)
    for b, note in [(5, 'E6'), (6.5, 'B5'), (7.25, 'G#6'), (9, 'F#6'), (10.5, 'B6')]:
        s.add(b, glass(hz(note)), 0.12, 0.7)
    s.rise(14, 4, 0.35)
    # Die weisse Form: Motiv in Dur, Chor hoch, weite Akkorde.
    s.crash(14, 0.3)
    s.chords(14, ['E', 'C#m', 'A', 'B'], 4, strings, 0.08, table=WIDE, send=0.45)
    s.chords(14, ['E', 'C#m', 'A', 'B'], 4, chor_a, 0.06, send=0.6, octave=1)
    s.bass(14, ['E', 'C#m', 'A', 'B'], 4, octave=1, gain=0.24)
    s.melody(14, MOTIF_MAJOR, horn, 0.2, octave=0, send=0.45)
    s.melody(14, MOTIF_MAJOR, glass, 0.1, octave=1, send=0.7, plucked=True)
    s.arpeggio(22, ['A', 'B'], 4, step=0.5, gain=0.1, octave=1)
    s.add(30, strings(hz('B2'), 6 * s.beat, 0.3), 0.06, 0.5)
    return render(s, size=1.9, hall_seconds=8.5, wet_gain=1.15, target_rms=0.1, drive=1.3, air=0.55)


THEMES = {'temple': temple, 'glacier': glacier, 'heart': heart, 'finale': finale, 'entrance': entrance, 'transform': transform}


def main():
    names = sys.argv[1:] or list(THEMES)
    for name in names:
        data = THEMES[name]()
        encode(os.path.join(OUT, f'{name}.ogg'), data, quality=4)
        rms = float(np.sqrt(np.mean(data ** 2)))
        print(f'music.{name}: {len(data) / 44100.0:.1f} s, RMS {rms:.3f}, Spitze {np.max(np.abs(data)):.3f}', flush=True)


if __name__ == '__main__':
    main()
