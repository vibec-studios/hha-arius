# Was im Spiel geprüft werden muss (Stand 1.1.0)

Gebaut und statisch geprüft (6 Review-Runden, Berichte in `docs/review/`). Seit 1.0.1 besteht zusätzlich ein echter
Client-Starttest (`./gradlew runClientGameTest`): Haupt- und Client-Entrypoints laden, eine Einzelspielerwelt startet,
Client-Welt und Spieler sind vorhanden. Geprüft mit Minecraft 1.21.11, Fabric Loader 0.19.3, Fabric API
0.141.4+1.21.11, Fabric Language Kotlin 1.13.12+kotlin.2.4.0 und dem mitgelieferten HHA 0.8.0 unter Java 25.
**Der Bosskampf und das vollständige Modpack sind damit noch nicht getestet.** Diese Punkte bitte im Spiel testen:

## Start und Arena
- [ ] Siegel herstellen und in einem kalten Biom benutzen: Schneesturm, Aufbau-Fortschritt, Ankunft im Tempel
- [ ] Erster Aufbau einer Instanz: Dauer/Ruckler (Budget `hha_arius.boss_build_budget`)
- [ ] Dimension: Himmel, Nebel, Licht, Schneeflocken; keine schmelzenden Blöcke
- [ ] Abbauen/Bauen in der Arena gesperrt (außer Kreativ)

## Abschnitt I – Tempel
- [ ] Intro-Untertitel, Aufstehen, Posen von Arius (Kombo, Windschritt, Wand + Streifen, Parierhaltung, Frostgriff, Gletscherstoß)
- [ ] Ansagen passen zu den Treffern; goldene Rune = offen
- [ ] Parade & Tritt, Frostgriff: Steuerung kurz gesperrt, Kamera bleibt, sichere Landung, keine Folgetreffer
- [ ] Unter 40 %: Mantel weg, „…Gut.“, Anpassungen (Pfeile, Rücken, Schild)

## Der Schnitt und Abschnitt II
- [ ] Wind kehrt sich um, Linie, Schlag, Stille, Tempelhälfte rutscht (Blockbündel), Kluft im Süden, Trümmer
- [ ] Tritt durchs Tor: alle landen auf den Plätzen, kein Fallschaden
- [ ] Solvane: Landung, Atem → Eiskämme, Sturzflug in einen Kamm → zu Boden, Ersticken durch Kehlentreffer
- [ ] Kopftreffer am Boden (unsichtbare Kopf-Trefferbox)

## Gletschersturz und Abschnitt III
- [ ] Einsturz, sanftes Fallen ins Gewölbe, Verwandlung (Splitter, weiße Form, Druckwelle), Resonanzfaden
- [ ] Takt: Taktgeber-Anzeige, Begleitangriffe, Taktbruch
- [ ] Resonanzangriffe (Korridor, Kristallsturm, Eisfeld spalten) mit goldenen Wegen
- [ ] Weißes Loch: Kern, Korridore, Druckfronten, Lücke + Durchbruch, Doppelfenster

## Finale und Ende
- [ ] Drei Austausche (Ausweichen oder perfekter Schildblock), Sieg, Endzeilen
- [ ] Heimtransport, Beute im Inventar (volles Inventar: Meldung „Fach“, kommt nach)
- [ ] Niederlage: Siegel zurück; k. o. statt Tod; Disconnect/Rejoin; Serverneustart mitten im Kampf

## Ausrüstung
- [ ] Eirholm-Rüstung (Aussehen, Leuchtnähte), Schmiedetisch, Trim „Weiße Klinge“
- [ ] Solvane (3D in der Hand, Konterhaltung), Resonanzbalken, Weiße Linie / Frostschritt / Weißer Kern, Schwingenschuppe
- [ ] Echo von Eirholm: Abschnitte 1–4 trainieren

## Ausrüstung 1.1 (Handschuhe, Solvane, Rüstung neu verteilt)
- [ ] Volles Rüstungsset **ohne** Waffen: Ability 1 Weißer Kern (Brust), Ability 2 Frostschritt (Beine), Ability 3 Weißes Loch (ersetzt Ultra), Resonanzbalken mit Ruhepunkt 50
- [ ] Einzelteile: Helm kein Gefrieren, Stiefel Pulverschnee + kleine Stürze, Brust Frostpanzer
- [ ] Handschuhe in der Hand: beide Arme mit Handschuhen in erster und dritter Person, Nebenhand-Item ausgeblendet, slim-Skin
- [ ] Nahkampf: Jab → Cross → Haken (Linksklick), Seitentritt, Drehtritt nach dem Haken, Parade (Schleichen + Rechts) + Konterstoß; aus erster und dritter Person
- [ ] Dauerklicken bringt nichts (max. eine Vormerkung), Tritte schieben Bosse nicht
- [ ] Linksklick auf Block baut ab, Rechtsklick auf Truhe/Tür öffnet (Nahkampfmodus)
- [ ] Modus-Taste (V): Faust ↔ offene Hand, Yang-Sonne leuchtet nur im Magiemodus; andere Spieler sehen es
- [ ] Rad (R halten): Schulen → Techniken, Loslassen wählt, kurz tippen = vorige Technik, Esc/Mitte bricht ab, Spiel läuft weiter
- [ ] Alle 13 Techniken einmal: Laden-Balken, Bauvorschau (Wand, Plattform, Brücke, Feld), „Kein gültiger Ort“, Abbruch mit Linksklick
- [ ] Yang-Schild kostet je Treffer Resonanz und bricht; Gleiter sinkt, Schleichen steigt ab, in der Arena gesperrt
- [ ] Bauwerke verschwinden von selbst (auch nach Neustart), droppen nichts, lassen sich nicht verschieben
- [ ] Solvane-Modell (Bastardschwert) in Hand, Inventar, am Boden
- [ ] Klarer Schnitt: Haltung, Energie an der Schneide, Vorschau der Ebene (Schleichen = waagerecht), Schnitt gegen Gegner, Baumstamm und Stein; Hälften gleiten auseinander; Truhen/Türen/Obsidian bleiben; kein Abbau im Spawnschutz/Abenteuermodus/Arena
- [ ] Itemwechsel während Laden/Haltung bricht ohne Kosten ab
- [ ] Kombination aller Teile: Fokus (nur eine Sache gleichzeitig), Nachhall nach großen Techniken
- [ ] Mehrspieler: Animationen, Modus, Schild, Gleiter, Schnitt sind für andere sichtbar

## Kamera, Spannung und Wucht (Paket V3)
- [ ] Erster Besuch: Etablierungsfahrt (Kuppel unten, Nadel oben im Bild, Stille, Gesicht von Arius), Rückweg in die eigene Kamera ohne Sprung; Überspringen per Taste halten (Mehrspieler: alle stimmen ab)
- [ ] Der Schnitt: Sprung + Detail **vor** der Ansage, Steuerung kommt zurück, bevor die Linie erscheint (volle Ansagezeit); danach Stille, weite Totale beim Abgleiten, Tritt durchs Tor (Seitenaufnahme, Flug über die Kamera), dann Solvane' Auftritt
- [ ] Auftritt: weite Totale füllt das obere Drittel der Nadel; Nadel ist von den Landeplätzen aus ab ~10 Chunks Sichtweite sichtbar
- [ ] Gletschersturz/Verwandlung: Kamera durch Sturm, Totale beim Einbruch, Blick aus dem Gewölbe nach oben, Seitenschwenk bei der Verwandlung; danach normale Steuerung vor dem ersten Angriff
- [ ] Ende: Knien, Halbkreis, Totale mit Solvane, Rückweg
- [ ] Nachzügler während einer Szene bekommen die laufende Einstellung (und eigenen Rückweg)
- [ ] Trefferpause (Haken, Seiten-/Drehtritt, Klarer Schnitt, Arius' Schläge auf Spieler): kurz, nur im Bild; Funken/Eissplitter, Schockring bei Tritten
- [ ] Kamerarucken dezent; `/ariusc shake false` schaltet es ab (ebenso HHAs Kameraschütteln)
- [ ] Schwertspuren: Solvane nur bei vollen Treffern (erste und dritte Person), Klarer Schnitt immer (breiter Streifen + kurze Bildbrechung entlang der Naht), Arius' Klinge eisblau (dunkle/ernste Form) bzw. weiß (weiße Form), keine Spur beim Gehen
- [ ] Solvane aufrecht: Haltungen des Klaren Schnitts (Hasso bzw. Hüfte), linke Hand am Griff, Klinge zeigt in Schnittrichtung; Energie an der Schneide sitzt auf der Klinge (erste und dritte Person, Linkshänder)
- [ ] Moveset: Ausholen, Gewicht, Nachschwingen, Landen nach Tritten; Zauber-Ladebalken richtet sich nach der Höchstladung der Technik

## Landschaft, Wetter und Solvane (visuelles Upgrade)
- [ ] Fernlandschaft baut sich im Hintergrund auf (Nadel, Schlucht, Gletscherzüge), keine Ruckler im Tempelkampf; Sichtweite ≥ 10 Chunks
- [ ] Wetterstimmungen: Tempel klar, Gletscher Schneetreiben, Whiteout vor dem Auftritt, Enthüllung mit blauem Himmel, Gewölbe blau, Verwandlung weiß; Schneeschleier und Eisglitzern
- [ ] Auftritt: Windungen im Sturm, Totale an der Nadel (Kopf über dem Gipfel), Lösen vom Fels, Gleitflug, Landung; überspringbar; Nachzügler
- [ ] Solvane als Schlange im Kampf: Körper folgt fließend, am Boden in Bögen, im Herzgewölbe ohne Wände zu durchdringen; Kopf-Trefferbox sitzt
- [ ] Weißes Loch (Boss und Set): Linse, weißer Kern, Ausfluss-Scheibe, Kollaps mit Schockwelle; Leistung ok
- [ ] Musik „Auftritt“ und „Verwandlung“
- [ ] Arius' neue Animationen (Ausholen, Treffer-Stoß, Nachschwingen, Mantel und Haar)

## Bekannte Grenzen
- Keine Voicelines (nur Untertitel), bewusst.
- Ein zurückgegebenes Siegel verliert individuelle Daten (z. B. Ambossname).
- Balancing-Werte sind Startwerte (alle über `/hha set hha_arius.*`).
- Nadel versetzt (z 150 → 128): Instanzen, deren Fernlandschaft schon gebaut ist, behalten die alte Nadel, bis die Fernlandschaft neu gebaut wird (FarPlan-Version).
