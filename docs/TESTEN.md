# Was im Spiel geprüft werden muss (Stand 1.5.0)

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
- [ ] Übergang zum Feld im Lichtstrom (seit 1.5.0 ohne Einsturz), Verwandlung (Splitter, weiße Form, Druckwelle), Resonanzfaden
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
- [ ] Volles Rüstungsset **ohne** Waffen: Ability 1 Weißer Kern (Brust), Ability 2 Frostschritt (Beine), Ability 3 Weißes Loch (ersetzt Ultra), Resonanzbalken lädt passiv bis 100
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

## Solvane, Drachenherz-Wacht (1.2.0)
- [ ] Tritt durchs Tor: Arius hebt Solvane, „Solvane. Zeig ihnen, was du warst.“, Lichtstrom zur Nadel, der Drache entsteht Glied für Glied
- [ ] Abschnitt II: Arius sitzt ohne Schwert, Scheide leer; Abschnitt III: Klinge aus Eislicht
- [ ] Finale: „Komm zurück.“, Drache zerfällt Glied für Glied zu Licht, Strom in die Scheide, Austausche wie gewohnt
- [ ] Texte: überall „Solvane“ statt „Nivaris“ (Bossleiste, Hinweise, Untertitel, Items)
- [ ] Rüstung „Drachenherz-Wacht“: an Spieler, Rüstungsständer, Mannequin, Zombie; Leuchten Tag/Nacht, Verzauberungsglanz, Umhang, Federn beim Laufen/Springen, schmale Arme, mit und ohne Handschuhe
- [ ] Solvane-Item: länger, Lichtfluss in der Hohlkehle, Sitz in erster und dritter Person

## 1.6.3
- [ ] Nach dem Schnitt: Totale von hoch oben seitlich auf die Bergflanke mit Kluft und Himmel, nicht „im Berg“
- [ ] Lichtstrom zum Gipfel / Sturm-Einstellung: Kamera höher, Blick auf den Gipfel statt auf die Flanke
- [ ] Ranken nur als schmaler Streifen (2 breit) an echten Felsstufen, nicht über die ganze Wegbreite (Fernlandschaft baut sich einmal neu, Version 9)

## 1.6.2
- [ ] Abklingzeit-Icon der Hose (HHA-Leiste) zeigt den Drachenschweif, nicht mehr Schwingen
- [ ] Handschuhe in der Ego-Sicht (Nahkampf und Magie, beide Arme, auch beim Schlagen und Zaubern): keine flimmernden Streifen/Muster auf den Platten; in der dritten Person ebenso

## 1.6.1
- [ ] Kit holen (`/hha kit hha_arius.eirholm`) bzw. Rüstung anziehen, Handschuhe/Solvane in die Hand nehmen: kein Ruckler, Sounds haken nicht (Modelle, Texturen und Klassen werden beim Betreten der Welt vorgewärmt, Log: „HHA Arius vorgewärmt in … ms“; alle Klangeffekte mit `preload`)

## 1.6.0
- [ ] Der Schnitt: riesige Lichtklinge über Arius während der Ansage, blauer Schein hinter ihm, Schlag nach vorn; Naht bis zum Berg, der Berg bricht der Länge nach auf (Kluft, Brocken), Klingengrab bleibt ganz; am Kampfende ist der Berg wieder heil
- [ ] Auftritt Solvane: Kamera nie im Berg, Windung über der Schulter, Kopf über dem Gipfel (y 220)
- [ ] Massiv: Fuß 170, Schulter y 172; Weg mit 27 Felsstufen (4 hoch) und Ranken zum Klettern, dazwischen einzelne Blockstufen; Laternen
- [ ] Wolkenmeer am Horizont und warmes Himmelslicht (nur in der Arena, `/ariusc fx` skaliert)
- [ ] Solvane: Körper fließt ohne Stufen, keine Durchdringung, Treffer am Körper aus etwas größerer Reichweite
- [ ] Luftphase: Himmelssturz (kreist hoch, senkrecht herab, drei Eisdornenringe), Eisregen im Kreis (angesagte Kreise), Tiefflug quer über das Feld (breite Linie)
- [ ] Eispanzer: kreisende Eisplatten die ganze Dauer, 5 Herzen Absorption, zerspringt bei 0
- [ ] Wirbelsturm: 14 hoch, Wolken, Bänder, Blitze, Brocken; hebt Ziele hoch und schleudert sie am Ende weg
- [ ] Weißes Loch wie Gargantua in Weiß (weiße Scheibe, dunkler Ring, heller Lichtring, flache wolkige Scheibe, Sterne)
- [ ] Abklingzeit-Icon der Hose: Drachenschweif

## 1.5.0
- [ ] Neue Dimension `hha_arius:highland`: endloses Schneefeld (auch weit draußen), nirgends ansteigende Ränder; alte Welten bauen alles neu
- [ ] Riesenmassiv im Süden mit Spiralweg (Start im Norden am Fuß, Steigung 1 : 5, Schneeschichten, Laternen alle 22 Blöcke) bis zur Schulter (y 128); das Felshorn ragt darüber
- [ ] Phase I im **Klingengrab**: offene Terrasse, zehn Steinklingen, große Klinge hinter dem Thron, Torklingen; Arius steht auf und kämpft; der Schnitt teilt Terrasse und große Klinge
- [ ] Übergang II → III ohne Loch: Solvane kreist, streicht tief über die Spieler, Lichtstrom trägt alle aufs Feld, Verwandlung
- [ ] QTE im Finale: Prompt sichtbar trotz Szene, Springen / Angriff hämmern / Benutzen werden erkannt; längere Zeitfenster
- [ ] Drache: kein Körperteil durchdringt ein anderes, keine 90°-Knicke, Kopf dreht weich, Schräglage begrenzt
- [ ] Arius: Kopf schmaler, Haar anliegend mit Pony und Seitensträhnen (kein runder Puschel), Sitzhaltung in Phase II blickt zum Kampf
- [ ] Drachenatem: Geisterkopf erscheint vor dir (Ego-Sicht höher und blasser), Maul öffnet, rollende Frostschwaden folgen dem Blick verzögert, Schaden im Kegel
- [ ] Drachenschweif (Beine, Ability 2): Geisterschwanz peitscht in 0,6 s einmal um dich, trifft jedes Ziel einmal, schleudert weg, vereist
- [ ] Weißes Loch: harte weiße Kugel, Photonenring, gebänderte Scheibe, Spiralarme, Jets, Lichtfesseln, Kuppel beim Kollaps
- [ ] Yang-Kugel: atmet, Lichtbänder, Begleitkugeln mit Spuren, schwimmender weißer Fisch
- [ ] Yang-Brücke: Bogenbrücke aus leuchtendem Yang-Licht; beim Zielen Mausrad = Länge (4–50), Schleichen + Mausrad = Breite (3–9); Pfeiler bis zum Boden; Kosten wachsen mit der Größe; verschwindet nach 8 s
- [ ] Icons Solvane und Handschuhe neu; Handschuhmodell schlanker (keine Hörner), richtige Faust
- [ ] Frostspeicher (Rezept: Glas, Amethyst, Herzfrost): füllt sich durch Kills (auch Mobfarm, ohne Rüstung) und Überschuss; Balken im Inventar; Rechtsklick füllt Resonanz; fehlende Resonanz wird automatisch gedeckt
- [ ] Litematica: `.litematic` in `.minecraft/schematics`; `/ariusc schematic list`, `build <name>` (Umriss vor dir, Kosten), `confirm` baut echte Blöcke aus dem Speicher, von unten nach oben, nur in Luft; verbotene Blöcke übersprungen; nicht in der Arena; Erstattung für Nicht-Gesetztes

## 1.4.1
- [ ] Abschnitt III und Finale auf dem offenen Feld im Hochland östlich des Plateaus (kein Himmelsbalkon mehr): flacher Grund, Säulen am Rand, Zuschauerhügel im Süden, Felshorn im Hintergrund; Gletschersturz/Lichtstrom landet dort
- [ ] Fernlandschaft baut sich einmal neu (Version 6); Feld eben
- [ ] Yang weiß: Kugel als schattierte weiße Sphäre mit kreisenden Kugeln und weißem Fischschweif; Schild, Brücke, Plattform, Gleiter, Brückenlicht und Icons weiß/perlmutt

## 1.4.0
- [ ] Landschaft: Felshorn statt Eisnadel (Grate, Fels, Schneebänder, Eisrinnen), Fels/Schnee statt Eis; Auftritt: Solvane windet sich weiter frei ums Horn
- [ ] Arena ab dem Drachenkampf offen: keine Gletscherwände mehr ums Plateau (außer hinter dem Tempel), weites Hochland, Kampfbereich Radius 100
- [ ] Schneesturm mit Shaderpack (Iris): Bildschirm-Schnee erscheint automatisch; `/ariusc snow screen|both|world|auto`
- [ ] Finale-QTE als Szene: Ausweichen (Springen) → Klingenbindung (Angriff hämmern) → Konter (Benutzen); Zeitlupen-Schleier, schließender Kreis; Erfolg entwaffnet Arius
- [ ] Weißes Loch: herausgerissene Blöcke kreisen und fliegen beim Kollaps davon, Eiskristalle, Lichtarme, Jets, Fesseln, Runenkreis
- [ ] Brust: Drachenatem (Frostkegel 1,5 s); Beine: Schwingensprung mit Aufprall-Schockwelle; neue HUD-Icons
- [ ] Handschuhe: Wirbelsturm (Wind) und Gletscherlanze (Eis, aufladen); Yang-Brücke 3 breit mit Geländerpfosten, Leuchtbogen und Lichtgeländer
- [ ] Szenen: Handkamera-Schwanken und Farbstimmung/Vignette/Korn in Arius' Cutscenes (Schalter: `/ariusc shake`)
- [ ] Balance: Boss-Treffer der neuen Angriffe, Axttritt, Drachenatem, Weißes Loch (16)
- [ ] Item-Icons Solvane und Handschuhe

## 1.3.0
- [ ] Schneesturm: Flockenstreifen mit Bewegungsunschärfe, Böen mit kurzem Whiteout, Bodentreiben, Frostrand; im Tempel unter dem Dach kaum Schnee; `/ariusc fx` skaliert
- [ ] Training III/IV wartet, bis die Fernlandschaft fertig ist
- [ ] Plateau: Obelisken, Ruinenbögen, Dornenkristalle; Blick zur Nadel frei; Landungen und Drachenplätze frei
- [ ] Arius: Klingenflug (Schwert wirbelt allein auf angesagten Bahnen, er tritt dabei), Frostdornen (Dornenreihe, weiße Form mit Dornenkranz), neue Hiebspuren
- [ ] Finale: letzter Austausch als Klingenbindung mit QTE (9× Angriff in 3 s), Erfolg gewinnt, Fehlschlag wirft zurück
- [ ] Handschuhe: neues Modell (Schwingen, Dorne, Eisband), neues Item-Icon; alle Item-Icons neu
- [ ] Weißer Kern und Frostschritt: neue Effekte; Beschreibungen in Tooltip und Zauberrad stimmen mit dem Spiel überein

## 1.2.2
- [ ] Tasten: Steuerung → HHA, Ability 4 = Technik (Zauberrad / Klarer Schnitt halten), Ability 5 = Modus; neu installiert einmal mit R/V vorbelegt; unbelegt → Chat-Hinweis
- [ ] Ego-Sicht: Solvane sitzt wie ein Netherite-Schwert in der Hand; Schwertspur und Klarer Schnitt passen dazu
- [ ] Drache im Kampf: Körper folgt fließend der Flugbahn, keine eckigen Brücken, keine senkrechten Säulen, keine Spirale; am Boden liegt er in Bögen; Abheben nach vorn, Landung im Gleitbogen; Schwanzfeger schwingt den hinteren Körper aus
- [ ] Treffer auf jedes sichtbare Körperglied zählen (Schwert, Solvane, Handschuhe); Schwanzglieder als Schwanztreffer
- [ ] Handschuhe: Deckung auf Brusthöhe (Gesicht frei), Folge Gerade · Gerade · Haken · Aufwärtshaken, Tritt danach = Axttritt mit Frostwelle; Vorrücken zum Ziel
- [ ] Rüstung: Netherite-Form in Perlweiß/Gold mit Hornkrone, Herz-Juwel und Schwingenplatten; Trims sichtbar; neue Item-Icons
- [ ] HUD: animierte Abklingzeit-Icons (Weißer Kern, Frostschritt, Klarer Schnitt, Weißes Loch, Konter, Flügelschuppe)
- [ ] Namen: „Heiligtum der Weißen Klinge“, Phase III „Arius, Erbe des Weißen Drachen“ (Titelkarte, Boss-Leiste)

## Fehlerbehebungen 1.2.1
- [ ] Windklinge, Frostsplitter, Yang-Kugel feuern; voll geladene Yang-Kugel ohne Serverabsturz
- [ ] Resonanz lädt passiv (2/s, ohne Kampf +6/s, volles Set +9/s) bis 100; Klarer Schnitt und Weißes Loch außerhalb des Kampfs
- [ ] `/hhaarius test`: Balken voll, nichts kostet, Abklingzeiten 0,5 s, Bauwerke auch in der Arena; `off` und Neuverbinden beenden ihn
- [ ] Handschuhe/Solvane ziehen: Aktionsleiste zeigt die eigenen Tasten; R oder V doppelt belegt (z. B. Sprach-Chat) → einmal Chat-Hinweis
- [ ] Abschnitt I: Arius springt nach dem Aufstehen von den Altarstufen auf den Hallenboden und kämpft (vorher blieb er am Sitz)

## Bekannte Grenzen
- Keine Voicelines (nur Untertitel), bewusst.
- Ein zurückgegebenes Siegel verliert individuelle Daten (z. B. Ambossname).
- Balancing-Werte sind Startwerte (alle über `/hha set hha_arius.*`).
- Nadel versetzt (z 150 → 128): Instanzen, deren Fernlandschaft schon gebaut ist, behalten die alte Nadel, bis die Fernlandschaft neu gebaut wird (FarPlan-Version).
