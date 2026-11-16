# Nivaris: Anker der Trefferzonen

Erzeugt von `python tools/assets/nivaris_model.py --anchors` aus denselben Schlüsselposen, die das Kotlin-Modell
(`client/model/nivaris/NivarisModel.kt`, Block `Keys`) benutzt. Alle Werte in **Blöcken relativ zur Entity-Position**
(Bodenpunkt unter der Rumpfmitte), Renderer-Faktor 1.6 schon eingerechnet:

* **vor** = entlang der Blickrichtung (yaw) der Entity, negativ = hinter der Mitte
* **hoch** = über der Entity-Position (im Flug hebt der Server die Entity an; der Wert gilt relativ dazu)
* **rechts** = zur rechten Seite der Drachin (bei symmetrischen Posen 0)

| Pose | Zone | vor | hoch | rechts |
|---|---|---|---|---|
| IDLE | Kopfmitte | +5.06 | +5.41 | -0.00 |
| IDLE | Kehle | +3.20 | +3.51 | -0.00 |
| IDLE | Herzkern | +2.30 | +1.80 | -0.00 |
| IDLE | Schwanzspitze | -8.28 | +1.11 | -1.44 |
| INHALE | Kopfmitte | +3.58 | +6.87 | -0.00 |
| INHALE | Kehle | +2.55 | +4.55 | -0.00 |
| INHALE | Herzkern | +2.29 | +2.48 | -0.00 |
| INHALE | Schwanzspitze | -8.56 | +1.02 | -0.00 |
| DOWNED | Kopfmitte | +5.81 | +0.71 | +0.01 |
| DOWNED | Kehle | +3.07 | +0.92 | -0.00 |
| DOWNED | Herzkern | +2.27 | +0.63 | +0.01 |
| DOWNED | Schwanzspitze | -7.22 | +1.80 | -3.73 |
| EXHAUSTED | Kopfmitte | +5.48 | +0.68 | -0.00 |
| EXHAUSTED | Kehle | +3.02 | +1.43 | -0.00 |
| EXHAUSTED | Herzkern | +2.16 | +1.23 | -0.00 |
| EXHAUSTED | Schwanzspitze | -8.17 | +1.20 | -1.66 |
| HOVER | Kopfmitte | +5.23 | +6.05 | -0.00 |
| HOVER | Kehle | +3.03 | +4.36 | -0.00 |
| HOVER | Herzkern | +2.28 | +2.96 | -0.00 |
| HOVER | Schwanzspitze | -8.69 | +1.77 | -0.00 |

Hinweise:
* Die Werte sind die gehaltenen Endhaltungen. Atmen, Gehen und Flügelschlag verschieben sie um höchstens etwa
  ±0,3 Blöcke (HOVER: Körper hebt und senkt sich mit dem Flügelschlag um etwa ±0,25).
* INHALE gilt am Boden. Atmet sie im Flug ein (`flying = true`), nimmt das Modell die Flughaltung: Kopf
  vor +3.17 / hoch +7.81, Kehle vor +2.26 / hoch +5.42.
* HOVER: Rumpf, Beine und Schwanz bleiben mindestens 0.6 Blöcke über dem Entity-Punkt (innerhalb der
  Trefferbox 4,5 × 3,6); nur die Schwingen reichen beim Abschlag bis etwa 2.6 Blöcke darunter.
* Kehle = Unterseite des zweiten Halsglieds (dort, wo die Naht beim Atemholen glüht); Herzkern = Mitte des goldenen
  Kerns zwischen den Brustschuppen. Für eine einzelne Zone „Kehle/Herzkern“ eignet sich der Mittelpunkt beider.
* Kopfmitte = Mitte des Schädels (zwischen den Augen), Schwanzspitze = Mitte des Eisklingen-Fächers.
