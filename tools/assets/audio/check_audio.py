"""Prüft die erzeugten OGGs rechnerisch (nicht durch Hören!).

Aufruf: PYTHONPATH=tmp/pylib python tools/assets/audio/check_audio.py
- alle Dateien: Länge, Kanäle, Spitze, lautester 100-ms-RMS, Clipping, Gleichanteil, Klick am Anfang/Ende,
  spektraler Schwerpunkt (grobe Klangfarbe)
- Musik: Lautheit (ffmpeg ebur128, LUFS integriert), Pegelverlauf in 8-s-Fenstern und die Schleifennaht
  (Sprung zwischen letztem und erstem Sample im Verhältnis zur typischen Sample-Änderung, RMS beider Ränder)
- sounds.json: jede referenzierte Datei existiert, jede Datei ist referenziert.
"""
import json
import os
import re
import shutil
import subprocess
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, '..', '..', '..'))
ASSETS = os.path.join(ROOT, 'src', 'main', 'resources', 'assets', 'hha_arius')
SOUNDS = os.path.join(ASSETS, 'sounds')
FFMPEG = shutil.which('ffmpeg')
RATE = 44100


def decode(path):
    probe = subprocess.run([FFMPEG, '-hide_banner', '-i', path], capture_output=True, text=True).stderr
    channels = 2 if 'stereo' in probe else 1
    raw = subprocess.run([FFMPEG, '-hide_banner', '-loglevel', 'error', '-i', path, '-f', 'f32le', '-ac', str(channels), '-ar', str(RATE), '-'],
                         capture_output=True, check=True).stdout
    data = np.frombuffer(raw, dtype=np.float32)
    return data.reshape(-1, channels) if channels == 2 else data[:, None]


def loudness(path):
    err = subprocess.run([FFMPEG, '-hide_banner', '-nostats', '-i', path, '-af', 'ebur128=peak=true', '-f', 'null', '-'],
                         capture_output=True, text=True).stderr
    lufs = re.findall(r'I:\s+(-?[\d.]+) LUFS', err)
    peak = re.findall(r'Peak:\s+(-?[\d.]+) dBFS', err)
    return (float(lufs[-1]) if lufs else float('nan')), (float(peak[-1]) if peak else float('nan'))


def short_rms_max(x, w=4410, h=441):
    sq = np.concatenate([[0.0], np.cumsum(x.astype(np.float64) ** 2)])
    if len(x) <= w:
        return float(np.sqrt(sq[-1] / max(len(x), 1)))
    starts = np.arange(0, len(x) - w, h)
    return float(np.sqrt(np.max((sq[starts + w] - sq[starts]) / w)))


def centroid(x):
    spec = np.abs(np.fft.rfft(x * np.hanning(len(x))))
    f = np.fft.rfftfreq(len(x), 1.0 / RATE)
    return float(np.sum(f * spec) / max(np.sum(spec), 1e-12))


def db(v):
    return 20 * np.log10(max(v, 1e-9))


def main():
    problems = []
    rows = []
    for folder in ('music', 'sfx'):
        directory = os.path.join(SOUNDS, folder)
        for name in sorted(os.listdir(directory)):
            path = os.path.join(directory, name)
            data = decode(path)
            mono = data.mean(axis=1)
            seconds = len(data) / RATE
            peak = float(np.max(np.abs(data)))
            clipped = int(np.sum(np.abs(data) >= 0.999))
            dc = float(np.mean(mono))
            edge_in = float(np.max(np.abs(data[0])))  # erstes Sample: Sprung aus der Stille?
            edge_out = float(np.max(np.abs(data[-16:])))
            info = dict(file=f'{folder}/{name}', seconds=seconds, channels=data.shape[1], peak_db=db(peak), loud_db=db(short_rms_max(mono)),
                        centroid=centroid(mono), clipped=clipped, dc=dc)
            if clipped:
                problems.append(f'{folder}/{name}: {clipped} Samples an der Grenze')
            if abs(dc) > 0.005:
                problems.append(f'{folder}/{name}: Gleichanteil {dc:.4f}')
            if folder == 'sfx':
                if data.shape[1] != 1:
                    problems.append(f'{folder}/{name}: Effekt ist nicht mono')
                if edge_in > 0.01:
                    problems.append(f'{folder}/{name}: Sprung aus der Stille am Anfang ({edge_in:.3f})')
                if edge_out > 0.05 and not name.startswith(('cut_reverse_wind', 'seal_blizzard', 'cut_charge')):
                    problems.append(f'{folder}/{name}: Klick am Ende ({edge_out:.3f})')
            else:
                lufs, true_peak = loudness(path)
                diffs = np.abs(np.diff(data, axis=0))
                typical = float(np.percentile(diffs, 99.0))
                jump = float(np.max(np.abs(data[0] - data[-1])))
                edge = int(0.02 * RATE)  # je 20 ms links und rechts der Naht
                rms_end = float(np.sqrt(np.mean(data[-edge:] ** 2)))
                rms_start = float(np.sqrt(np.mean(data[:edge] ** 2)))
                windows = [db(float(np.sqrt(np.mean(data[i:i + 8 * RATE] ** 2)))) for i in range(0, len(data) - RATE, 8 * RATE)]
                info.update(lufs=lufs, true_peak=true_peak, seam_jump=jump, seam_ratio=jump / max(typical, 1e-9),
                            seam_rms=(db(rms_end), db(rms_start)), arc=windows)
                if not 70 <= seconds <= 100:
                    problems.append(f'{folder}/{name}: Länge {seconds:.1f} s außerhalb 70–100 s')
                if jump > typical:
                    problems.append(f'{folder}/{name}: Schleifennaht springt ({jump:.4f} > 99-%-Schritt {typical:.4f})')
                if abs(db(rms_end) - db(rms_start)) > 3:
                    problems.append(f'{folder}/{name}: Pegel an der Naht ungleich ({db(rms_end):.1f} / {db(rms_start):.1f} dB)')
            rows.append(info)

    for r in rows:
        line = f"{r['file']:36s} {r['seconds']:6.2f} s  {r['channels']}ch  Spitze {r['peak_db']:6.1f} dB  laut {r['loud_db']:6.1f} dB  Schwerpunkt {r['centroid']:6.0f} Hz"
        if 'lufs' in r:
            line += (f"\n    {r['lufs']:.1f} LUFS, True Peak {r['true_peak']:.1f} dBFS, Naht: Sprung {r['seam_jump']:.4f} (x{r['seam_ratio']:.2f} des 99-%-Schritts),"
                     f" RMS Ende/Anfang {r['seam_rms'][0]:.1f}/{r['seam_rms'][1]:.1f} dB\n    Verlauf (8 s): " + ' '.join(f'{w:.0f}' for w in r['arc']))
        print(line)

    with open(os.path.join(ASSETS, 'sounds.json'), encoding='utf8') as f:
        sounds = json.load(f)
    referenced = set()
    for event, entry in sounds.items():
        for s in entry['sounds']:
            name = s['name'] if isinstance(s, dict) else s
            rel = name.split(':', 1)[1] + '.ogg'
            referenced.add(rel)
            if not os.path.exists(os.path.join(SOUNDS, rel)):
                problems.append(f'sounds.json: {event} verweist auf fehlende Datei {rel}')
    on_disk = {f'{d}/{n}' for d in ('music', 'sfx') for n in os.listdir(os.path.join(SOUNDS, d))}
    for orphan in sorted(on_disk - referenced):
        problems.append(f'nicht referenziert: {orphan}')
    print(f'\n{len(rows)} Dateien, {len(sounds)} Events in sounds.json')
    print('Probleme:' if problems else 'Keine Probleme gefunden.')
    for p in problems:
        print('  -', p)
    return 1 if problems else 0


if __name__ == '__main__':
    sys.exit(main())
