# Was im Spiel geprüft werden muss (Stand 1.0.0)

Gebaut und statisch geprüft (6 Review-Runden, Berichte in `docs/review/`). **Nichts davon wurde im laufenden Minecraft
gesehen.** Diese Punkte bitte im Spiel testen:

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
- [ ] Nivaris: Landung, Atem → Eiskämme, Sturzflug in einen Kamm → zu Boden, Ersticken durch Kehlentreffer
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

## Bekannte Grenzen
- Keine Voicelines (nur Untertitel), bewusst.
- Ein zurückgegebenes Siegel verliert individuelle Daten (z. B. Ambossname).
- Balancing-Werte sind Startwerte (alle über `/hha set hha_arius.*`).
