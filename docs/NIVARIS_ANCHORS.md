# Nivaris: Anker der Trefferzonen (Schlangendrache, seit V2)

Nivaris ist ein 96 Blöcke langer Schlangendrache aus 40 Gliedern. Der lange Körper ist **nur Darstellung**; getroffen
wird über die Trefferbox der Entity (4,5 × 3,6, der **Rumpf vorn**: Glieder 2–4) und die unsichtbare Kopf-Trefferbox
(`NivarisHeadEntity`, 1,9 × 1,9), die der Kampf jeden Tick an die Kopfmitte setzt. Welche Zone ein Treffer auf die
Rumpfbox trifft, entscheidet der nächste Anker (`NivarisMoves.zoneOf`).

## Anker im Modellraum (Teile aus tools/assets/nivaris_model.py, `ANCHORS`)

Einheiten im Raum des Teils (1 Einheit = 2,4/16 = 0,15 Blöcke, +y nach unten, Kopf blickt nach −z):

| Zone | Teil | Punkt |
|---|---|---|
| Kopfmitte (zwischen den Augen) | `head` | (0, −2,5, −9) |
| Kehle (leuchtende Naht hinter dem Kiefergelenk) | `head` | (0, 4,5, −1) |
| Herzkern (Bauchseite) | `segment_heart`, Glied 3 | (0, 7,5, 8) |
| Schwanzspitze (Mitte des Klingenfächers) | `tail_fan` | (0, −1, 13) |

## Anker im Kampf (Server: `NivarisShape`/`NivarisMoves`, Renderer: `NivarisModel`)

Alle Werte in **Blöcken relativ zur Entity-Position** (Bodenpunkt unter dem Rumpf vorn); **vor** = entlang der
Blickrichtung (yaw), **hoch** = über dem Entity-Punkt. Die Rumpfachse liegt 1,8 darüber (`NivarisShape.SPINE_UP`).

Der Kopf sitzt je Pose an einem **Kopfgelenk** (`NivarisShape.head`, vor / hoch / Neigung in rad, + = Nase runter);
das Modell blendet weich dorthin, Hals und Körper folgen. Daraus:

* **Kopfmitte** = Gelenk + 1,35 entlang der Blickrichtung + 0,375 nach oben (Kopfrahmen) → Kopf-Trefferbox, Zone HEAD.
* **Kehle/Herz** (Zone THROAT) = Mitte aus Kehlnaht (0,675 unter dem Gelenk) und Herzkern (Glied 3 liegt etwa über
  dem Entity-Punkt, 1,24 unter der Rumpfachse).
* **Rumpf** (Zone BODY) = vor 0 / hoch 1,8.
* **Schwanz** (Zone TAIL): am Boden vor −8,0 / hoch 0,9 (erste Windung der Ruhe-Spirale hinter ihr), im Flug
  vor −14 / hoch 1,2 (der Körper zieht weit hinter ihr her; praktisch nie die nächste Zone).

### Am Boden (`flying = false`)

| Pose | Kopfgelenk vor / hoch / Neigung | Kopfmitte vor / hoch | Kehle/Herz vor / hoch |
|---|---|---|---|
| IDLE | +3.00 / +5.40 / +0.20 | +4.40 / +5.50 | +1.43 / +2.65 |
| INHALE | +1.60 / +6.80 / -0.45 | +2.65 / +7.72 | +0.95 / +3.38 |
| BREATH | +4.00 / +4.40 / +0.50 | +5.36 / +4.08 | +1.84 / +2.19 |
| LAND | +3.20 / +4.60 / +0.30 | +4.60 / +4.56 | +1.50 / +2.26 |
| TAIL | +2.80 / +5.00 / +0.20 | +4.20 / +5.10 | +1.33 / +2.45 |
| WING_GUST | +2.80 / +5.80 / +0.00 | +4.15 / +6.17 | +1.40 / +2.84 |
| GRAB | +4.60 / +1.60 / +1.00 | +5.64 / +0.67 | +2.02 / +0.90 |
| THROW | +3.20 / +5.60 / +0.10 | +4.58 / +5.84 | +1.57 / +2.75 |
| ROAR | +2.20 / +7.20 / -0.75 | +2.93 / +8.39 | +1.33 / +3.63 |
| DOWNED | +5.00 / +0.90 / +0.05 | +6.37 / +1.21 | +2.48 / +0.39 |
| EXHAUSTED | +4.60 / +1.20 / +0.25 | +6.00 / +1.23 | +2.22 / +0.55 |
| TAKEOFF | +3.40 / +5.60 / +0.05 | +4.77 / +5.91 | +1.68 / +2.74 |
| RESONANCE | +2.00 / +7.60 / -0.55 | +2.95 / +8.63 | +1.18 / +3.79 |
| LIE_DOWN | +3.60 / +1.10 / +0.10 | +4.98 / +1.34 | +1.77 / +0.50 |
| FREEZE_FIELD | +3.80 / +5.00 / +0.60 | +5.13 / +4.55 | +1.71 / +2.50 |

Nicht aufgeführte Posen (CLAW, HOVER, GLIDE, DIVE, CRASH_DIVE am Boden) halten das Gelenk von IDLE.

### In der Luft (`flying = true`)

| Pose | Kopfgelenk vor / hoch / Neigung | Kopfmitte vor / hoch | Kehle/Herz vor / hoch |
|---|---|---|---|
| HOVER | +5.60 / +2.60 / +0.15 | +6.99 / +2.77 | +2.75 / +1.25 |
| GLIDE | +6.40 / +1.90 / +0.05 | +7.77 / +2.21 | +3.18 / +0.89 |
| INHALE | +4.20 / +4.00 / -0.40 | +5.30 / +4.87 | +2.23 / +1.97 |
| BREATH | +6.20 / +1.40 / +0.60 | +7.53 / +0.95 | +2.91 / +0.70 |
| DIVE | +6.60 / +0.80 / +0.55 | +7.95 / +0.41 | +3.12 / +0.39 |
| CRASH_DIVE | +3.00 / -3.50 / +1.35 | +3.66 / -4.74 | +1.17 / -1.54 |
| LAND | +5.20 / +2.40 / +0.30 | +6.60 / +2.36 | +2.50 / +1.16 |
| DOWNED | +4.50 / +1.00 / +0.30 | +5.90 / +0.96 | +2.15 / +0.46 |
| RESONANCE | +4.00 / +5.50 / -0.50 | +5.00 / +6.48 | +2.16 / +2.74 |
| FREEZE_FIELD | +5.60 / +2.00 / +0.70 | +6.87 / +1.42 | +2.58 / +1.02 |
| WING_GUST | +5.00 / +3.00 / +0.00 | +6.35 / +3.38 | +2.50 / +1.44 |
| ROAR | +4.60 / +4.60 / -0.70 | +5.39 / +5.76 | +2.52 / +2.32 |

Nicht aufgeführte Posen halten das Gelenk von HOVER.

Hinweise:
* Die Werte sind gehaltene Endhaltungen. Ausholen und Schlag (z. B. Klaue: Kopf von vor 1,8 / hoch 6,0 nach
  vor 5,2 / hoch 3,0) laufen nur im Modell; Atmen, Flügelschlag und Nachfedern verschieben den Kopf um höchstens
  etwa ±0,3 Blöcke.
* An der Nadel (`NivarisMode.COIL`/`UNCOIL`, Auftritt) steht die Entity als Stellvertreter in Reichweite der Spieler
  (`EntrancePath.PROXY`); alle Anker liegen dann bei ihr (vor 0 / hoch 1,8), es gibt keine Treffer.
* Der Körper am Boden liegt in einer Spirale hinter ihr (Mitte etwa 10–11 Blöcke hinter dem Entity-Punkt, Radius bis
  7,6), im Herzgewölbe zur Mitte gedreht, damit nichts durch die Wand ragt.
