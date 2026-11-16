# Nivaris als Schlangendrache: Modell-Vertrag für den Renderer

Quelle: `tools/assets/nivaris_model.py` → `python tools/assets/boss_models.py --only nivaris` erzeugt
`client/model/nivaris/NivarisModelData.kt` (`nivaris()`) und die Texturen. Gesamtwirkung ohne Spiel:
`python tools/assets/nivaris_serpent_preview.py` (PNGs unter `tmp/preview/nivaris_serpent/`).

## Modellraum

* 1 Einheit = 1/16 Block, **+y nach unten**, Kopf blickt nach **−z**, Modell-+x = linke Seite der Drachin.
* Renderer-Faktor **SCALE = 2,4**: 1 Einheit = 0,15 Blöcke, ein Glied = 16 Einheiten = 2,4 Blöcke.
* Alle zwölf Teile hängen direkt unter `root`, Drehpunkt 0, keine Drehung. Kinder haben eigene Drehpunkte (siehe unten).
* Atlas: Kotlin meldet **256 × 256**, die PNGs sind **512 × 512** (2 Texel je Einheit, Minecraft normiert die UVs).
  Alle Maße liegen auf dem Halbeinheiten-Raster.

## Teile (Ausdehnung in Ruhe, Einheiten)

| Teil | x | y | z | Bemerkung |
|---|---|---|---|---|
| `head` | ±7 (Barteln ±17,8) | −8 … +4,5 (Krone bis −13) | −21 … +4,5 (Krone/Barteln bis +20) | Gelenk zum Hals bei 0. Schädel 12 breit, mit Wangen 14; ohne Krone 12,5 hoch. |
| `neck` | ±6 | −6 … +6 (Dornen bis −8,5) | −2,5 … +16,5 | Querschnitt 12 × 12, Kehlnaht leuchtet am Bauch |
| `segment` | ±7 | −7 … +7 (Dornen bis −9,5) | −3 … +16,5 | Querschnitt 14 × 14, gerundet in zwei Stufen |
| `segment_dorsal` | ±7 | bis −19 (Kristall) | −3 … +20 | wie `segment` + Kristallplatte `crest` und Splitter `crest_shard` |
| `segment_heart` | ±7 | bis +8 (Brustschilde) | −3 … +16,5 | wie `segment` + Herzenserbe auf der Bauchseite |
| `foreleg_l/_r` | außen bis ±5 | −3 … +16 | −12 … +4,5 | Drehpunkt = Schulter |
| `hindleg_l/_r` | außen bis ±5 | −4 … +18 | −3,5 … +9 | Drehpunkt = Hüfte |
| `wing_l/_r` | 0 … ±64 | ±2,5 | −2,5 … +40 | Drehpunkt = Schulter, Ruhe = gespannt |
| `tail_fan` | ±6 | ±11 | −1 … +28,5 | Gelenk bei 0, zeigt nach +z |

### Nahtlos auf der Kurve

Die Rumpfglieder reichen von z −0,5 bis +16,5 (1 Einheit Überlappung) und haben vorn einen **Gelenkfüller**
(10 × 10, z −3 … −0,5), der in das Vorderglied hineinragt. Biegt der Körper bis etwa 25° je Glied ab (enge Windung
oben an der Nadel), bleibt an der Außenseite nur eine flache Kerbe statt einer Lücke. Für engere Kurven (Herzgewölbe)
lieber mehr Glieder in den Boden tauchen lassen als stärker knicken.

Glieder werden **starr auf ihre Sehne** gesetzt: Ursprung = Kurvenpunkt am Gliedanfang, Modell-+z zeigt zum Punkt
2,4 Blöcke weiter hinten. So macht es auch die Vorschau.

### Kinder, die der Renderer bewegen darf

| Kind | Drehpunkt (im Elternteil) | Grunddrehung | Bewegung |
|---|---|---|---|
| `head/jaw` | (0, 1, −4) | 0 | pitch + öffnet; 20° = Grollen, 34–46° = Atem/Brüllen |
| `head/whisker_<s>` und `…_1`, `…_2`, `…_3` | Oberlippe (±3,5, −0,5, −18) | geschwungen | sanftes Schwingen: yaw ±8°, pitch ±6° je Glied, phasenversetzt |
| `head/jaw/beard` | (0, 3,5, −10) | pitch 38° | darf mit dem Kiefer leicht nachpendeln |
| `foreleg_<s>_lower` | Ellbogen (∓1,75, 7,5, 0) | pitch −40° | Unterarm; `foreleg_<s>_foot` am Handgelenk (0, 7, 0), pitch +40° |
| `hindleg_<s>_lower` | Knie (∓2, 8, 0,5) | pitch +30° | Schienbein; `hindleg_<s>_foot` (0, 8, 0), pitch −30° |
| `wing_<s>_fore` | Ellbogen (∓20, 0, 0) | yaw ±8° (nach hinten) | für Faltungen (optional) |
| `wing_<s>_tip` | Handgelenk (∓22, 0, 0) | yaw ±14° | Fingerknochen `wing_<s>_finger_0..2` hängen daran |

Ruhe der Läufe: Unterarm schräg nach vorn, Klauen nach vorn/unten. Im Flug anlegen: Wurzelteil pitch +60 … +70°
(nach hinten), `…_lower` −20°, `…_foot` +50°.

## Ansatzstellen (Renderer-Vertrag, im Gliedraum vor taper)

| Teil | Glied | Punkt | Hinweis |
|---|---|---|---|
| `wing_l` / `wing_r` | 2 | (+6, −6, 4) / (−6, −6, 4) | Punkt mit taper(2) skalieren, Schwinge selbst unverjüngt |
| `foreleg_l` / `foreleg_r` | 3 | (+7, 3, 8) / (−7, 3, 8) | dito mit taper(3) |
| `hindleg_l` / `hindleg_r` | 17 | (+6, 3, 8) / (−6, 3, 8) | dito mit taper(17) |
| `segment_heart` | 3 | – | Herzkern bei (0, 7,5, 8) |
| `tail_fan` | Ende von Glied 39 | (0, 0, 16) | **unverjüngt** ansetzen, er ist für den Endquerschnitt (≈ 4,5 × 4,5) gebaut |
| `head` | Anfang von Glied 0 | (0, 0, 0) | Kopfrahmen aus der Spur vor dem Gelenk |

## Glieder und Verjüngung

Glied 0 ist der Hals (`neck`), 1 … 39 Rumpf. Vorschlag `glied_part(i)`: 0 `neck`, 3 `segment_heart`, gerade
i ≥ 2 bis 37 `segment_dorsal` (jedes zweite Glied trägt eine Kristallplatte), sonst `segment`.

`taper(i)` skaliert **nur x und y** (Querschnitt); z bleibt 1, damit der Bogenabstand von 2,4 Blöcken stimmt.
Formel: i = 0 → 1,0 (der Hals ist schon schmaler gebaut), i = 1 … 4 → 0,96 / 1,04 / 1,10 / 1,12, ab i = 5:
`1,12 − 0,8 · ((i − 5) / 34)^1,25`.

| i | Teil | taper | i | Teil | taper | i | Teil | taper | i | Teil | taper |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 0 | neck | 1.000 | 10 | dorsal | 1.047 | 20 | dorsal | 0.832 | 30 | dorsal | 0.575 |
| 1 | segment | 0.960 | 11 | segment | 1.028 | 21 | segment | 0.808 | 31 | segment | 0.548 |
| 2 | dorsal | 1.040 | 12 | dorsal | 1.009 | 22 | dorsal | 0.784 | 32 | dorsal | 0.520 |
| 3 | heart | 1.100 | 13 | segment | 0.989 | 23 | segment | 0.759 | 33 | segment | 0.492 |
| 4 | dorsal | 1.120 | 14 | dorsal | 0.968 | 24 | dorsal | 0.733 | 34 | dorsal | 0.464 |
| 5 | segment | 1.120 | 15 | segment | 0.947 | 25 | segment | 0.708 | 35 | segment | 0.436 |
| 6 | dorsal | 1.110 | 16 | dorsal | 0.925 | 26 | dorsal | 0.682 | 36 | dorsal | 0.407 |
| 7 | segment | 1.097 | 17 | segment | 0.902 | 27 | segment | 0.656 | 37 | segment | 0.378 |
| 8 | dorsal | 1.082 | 18 | dorsal | 0.879 | 28 | dorsal | 0.629 | 38 | segment | 0.349 |
| 9 | segment | 1.065 | 19 | segment | 0.856 | 29 | segment | 0.602 | 39 | segment | 0.320 |

Rumpfradius in Blöcken: 7 · taper · 0,15 (Brust ≈ 1,18, Glied 17 ≈ 0,95, Schwanzende ≈ 0,34).
`python tools/assets/nivaris_model.py --taper` druckt die Tabelle neu.

## Schwingen

Ruhe (alle Winkel 0) = **gespannt**: Knochen nach außen, Haut nach hinten, Spannweite 64 Einheiten je Seite
(≈ 9,6 Blöcke). Empfohlene Haltungen für `wing_r` (bei `wing_l` yaw und roll mit umgekehrtem Vorzeichen), Grad:

| Haltung | pitch | yaw | roll | Wirkung |
|---|---|---|---|---|
| gespannt | 0 | 0 | 0 | Flug, Brüllen, Resonanz |
| angelegt (`fold`) | 180 | 84 | −45 | Knochen über die Schulter nach hinten, Haut hängt wie ein zerschlissener Umhang außen an der Flanke |
| eng angelegt (`fold_tight`) | 180 | 80 | −70 | Haut steiler an der Flanke (Herzgewölbe, enge Windung) |
| Schlag oben | 0 | 6 | 40 | |
| Schlag unten | 0 | 4 | −34 | |

Wichtig: Ohne pitch 180 zeigt die Haut beim Anlegen nach innen und verschwindet im Rumpf. Zwischen angelegt und
gespannt am besten per Quaternion überblenden (slerp), nicht die Euler-Winkel linear; dann dreht sich die Schwinge
beim Öffnen natürlich um den Knochen.

## Texturen und Leuchten

| Datei | Inhalt | Renderer |
|---|---|---|
| `nivaris.png` | Farbe; Membranlöcher und Fransen transparent (Cutout) | Grundschicht |
| `nivaris_glow.png` | alle Leuchttexel (Übersicht, nicht im Spiel nötig) | – |
| `nivaris_glow_eyes.png` | Augen (rechts golden, links eisblau), Hornspitzen, Bartelspitzen, Kristallspitzen des Kamms | eyeGlow / lead |
| `nivaris_glow_heart.png` | Kehlnaht (Hals-Bauch und unter dem Schädel), Maul innen, Herzkern + goldene Adern der Brustschilde | throatGlow |
| `nivaris_resonance.png` | Linie auf dem Rücken, Seitenlinien, Adern der Membranen, Mittelader und Kanten von Kamm, Fächer, Hörnern | nur bei resonance |

Alle mit denselben UVs. Die drei Rumpfglieder teilen sich die Haut-UVs (gleiche Form und gleicher Maler).

## Windung um die Nadel (Befund aus der Vorschau)

* Bauch zur Felswand, Rücken nach außen (Kamm zeigt vom Fels weg); am Kopf dreht der Rücken nach oben.
  So rechnet `nivaris_serpent_preview.py` (`path_coil`).
* `ArenaLayout.coil(s)` läuft 2¼-mal von y 70 bis 168 und ist dabei ≈ 202 Blöcke lang. Der 96-Block-Körper samt
  ≈ 27 Blöcken Halsanstieg über den Gipfel bedeckt davon nur das obere Stück, also **knapp einen Umlauf**
  (`nadel_schlucht.png`).
* Damit der Körper die Helix ganz füllt, reicht sie bei diesem Halsanstieg für etwa **1½ Umläufe zwischen y 144 und
  168** (Szene `nadel_vorschlag.png`, Abstand zur Felswand ≥ 1,6 Blöcke). Für 2¼ sichtbare Umläufe müsste der Hals
  kürzer über den Gipfel ragen oder die Helix enger am Fels liegen.
* Mit der aktuellen Helix bleibt die Körperoberfläche mindestens 0,7 Blöcke vor der Wand, kein Glied steckt im Fels.

## Anker

Siehe `docs/NIVARIS_ANCHORS.md` (Kopf, Kehle, Herz, Schwanzspitze im Raum ihres Teils).
