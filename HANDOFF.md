# Übergabe: HHA Arius 1.14, Phase 1 bis Übergang in Phase 2

Dieses Dokument ist für einen Agenten ohne Kontext aus dem bisherigen Chat. Lies es komplett, danach `AGENTS.md`. Die Regeln dort gelten weiter.

## 1. Kurzfassung

- **Projekt:** HHA Arius, ein Fabric-Mod für Minecraft 1.21.11 in Kotlin mit Java-Mixins. Er ergänzt den Mod HHA um einen großen, filmischen Bosskampf gegen Arius und seinen Schwert-Drachen Solvane.
- **Stabil:** `main` ist Version **1.13.0** (`14ba623`). Fasse `main` nicht an, solange die Arbeit nicht abgenommen ist. Die Vibe-Website übernimmt `main` automatisch.
- **Laufende Arbeit:** Branch **`wip/1.14-phase1`**. Er enthält beide unfertigen Pakete, zusammengeführt. Er baut, der Standard-Clienttest läuft durch, aber **visuell ist er nicht abgenommen**.
- **Deine Aufgabe:** die offenen Punkte in Abschnitt 6 fertigstellen, im echten Client prüfen, dann 1.14.0 veröffentlichen (Abschnitt 8).
- **Nicht deine Aufgabe:** spätere Phasen umbauen. Der Besitzer gibt das Feedback jetzt **Phase für Phase** und meldet sich zur nächsten Phase selbst.

```bash
git fetch origin
git switch wip/1.14-phase1
./gradlew build runClientGameTest
```

## 2. Arbeitsweise und Wünsche des Besitzers

Der Besitzer spielt den Kampf selbst, nimmt Videos auf und schreibt kurzes, direktes Feedback auf Deutsch. Beurteilt wird das Ergebnis **im Spiel**, nicht anhand von Testberichten.

- **Heißt es im Feedback, etwas sieht komisch aus, stimmt das.** Suche die Ursache, statt es wegzuerklären. Die Beschreibungen sind meist sehr genau.
- **Cutscenes müssen physisch und erzählerisch Sinn ergeben.** Jede Bewegung braucht einen Grund. Ein SuperSprung ohne Motivation, Durch-den-Körper-Fallen oder „vom Wind draufgeweht werden“ fliegen sofort auf.
- **Gewünscht sind Wucht und Inszenierung:** Vorbereitung, harter Treffer, klare Folge. Der Maßstab sind große Action-Spiele wie Black Myth: Wukong. Übernimm die Wirkung, baue aber eigene Animationen.
- **Solvane darf komplett geskriptet sein.** Zitat: „Solvanes part in dem ganzem fight von anfang bis ende kann scripted seien solange er dann nicht mehr buggy ist.“ Saubere, geplante Bahnen sind wichtiger als dynamische KI.
- **Ehrlichkeit ist Pflicht.** Sag klar, was du gesehen hast und was nicht. Ein ehrliches „nicht fertig“ ist besser als ein falsches „fertig“.
- **Test-Clients laufen still.** Kein Test-Client darf Fenster oder Ton stören.

### Visuelle Richtung

- **Palette:** Frostweiß, Eisblau, tiefes Marineblau. Gold nur für heilige oder Yang-Momente.
- **Verboten:** rote Gefahrenzonen, Regenbogen- oder Farbwechsel-Effekte, Pink, Rohfarben-Rauschen, Blitz-Linien und Seifenblasen-Hüllen.
- **Angriffsmarkierungen:** das breite, weiche, frostige Eis-Design aus v1.11.0 mit dicken Ringen und kantigen Eissplittern. Der Besitzer hat blitzartige Versionen zweimal abgelehnt.
- **Effekte:** klare Silhouette, kurze Vorbereitung, starker Treffer, sauberes Ausblenden. Mehr Partikel sind nicht besser.
- **Figuren:** kein Teleportieren, Einrasten, Zittern, Einfrieren in der Luft und kein Clipping. Hände und Füße halten wirklich fest, was sie halten sollen.
- **Kameras:** nie in Terrain oder Körpern und nie ohne Grund springend.
- **Solvane:** ein durchgehender Körper von Schnauze bis Schwanz. Auch Kopf und Hals folgen der Bewegung.
- **Sichtbarkeit:** Der Besitzer nutzt ClearView-, NoFog- und Performance-Mods. Stürme und Effekte müssen trotzdem sichtbar sein.

## 3. Geschichte und Ablauf des Kampfes

Die Reihenfolge entspricht den Funktionen in `boss/Scenes.kt`:

1. `intro`, `temple`: Der Spieler trifft Arius in seinem Tempel. **Phase 1** ist ein Duell.
2. `theCut`, `cutAftermath`: Arius spaltet mit **White Horizon** den Berg samt Gipfelarena.
3. `kick`: Arius schlägt den Spieler weg.
4. `arrival`: **Beschwörung.** Sein Schwert erwacht zu Solvane, sie windet sich um die Eisspitze, dann landet sie.
5. `toAerie`, `carry`: Solvane sammelt den Spieler ein und fliegt zu ihrer eigenen Arena. Dort wirft sie ihn per Rolle ab. Arius kommt auf sein kleines Podest, Solvane landet.
6. `glacier`, `collapse`, `transform`, `vaultFight`: **Phase 2** mit Solvane. Weil Solvane sein echtes Schwert ist, kämpft Arius jetzt mit einem geschmiedeten **Eisschwert**.
7. Jagd (`boss/Hunt.kt`) mit Brücken, Höhlen und dem Spire-Massiv, außerdem der Drachenritt mit Quick-Time-Event.
8. `finale`, `lastHorizon`: drei eskalierende Schläge, Eisschwert zerbricht, Last Horizon.
9. `bladeReturn`, `ending`: Solvane windet sich wieder um die Spitze und wird zurück zum Schwert.

**Neue Festlegung:** Der Berg bleibt nach dem Schnitt **gespalten**. Arius repariert ihn erst mit goldenem Yang, kurz bevor der Spieler in der Jagd wirklich wieder über den Spalt laufen muss.

## 4. Technik, die du kennen musst

- Boss-Skripte sind Kotlin-Sequenzen (`Script = Sequence<Unit>`), jedes `yield` ist ein Tick.
- Solvanes Flug läuft über `boss/DragonRail.kt`, ihr Körper über `client/model/nivaris/NivarisModel.kt`, die Beschwörungsbahn über `boss/Entrance.kt`, Reiten und Festhalten über `boss/Ride.kt` und `client/fx/RideClient.kt`.
- Sprachdateien werden erzeugt. Ändere `tools/lang/<bereich>.<de_de|en_us>.json`, dann `python -I tools/lang/build_lang.py`. Jeder sichtbare Text braucht beide Sprachen.
- `boss/SplitRecovery.kt` sichert gespaltene Blöcke gegen Abstürze. NBT-Strings sind auf 65.535 Bytes begrenzt, deshalb wird der Snapshot in Stücke geteilt. Jeder entfernte Block muss gesichert und wiederhergestellt werden.
- Kompiliert wird gegen `libs/HHA-0.8.0.jar`. Der Besitzer spielt mit HHA 0.9.0, HHA_Blood 0.13.0, HHA_void 0.1.1 und Fabric Loader 0.20.1.

### Prüfen im echten Client

Die Prüfungen sind Fabric-Client-Gametests in `src/gametest/kotlin/.../gametest/`:

```bash
ALSOFT_DRIVERS=null ./gradlew runClientGameTest \
  -Darius.verify=<boss|dragon|hunt|portal> \
  -Darius.verify.out=<absoluter Ausgabeordner> \
  -Darius.verify.case=<fälle>
```

- **Boss-Fälle:** `stance, punish, parry, exact, classic, mace, macecatch, parrycue, forge, templehorizon, horizonviews, yanglater, vaultpace, fallrecover, kickpose, clash, laststand, climax, hole, holefail, duo, waves, shield, tele, path, ice, fx, pace`. Ohne Angabe läuft nur `punish`.
- **Solvane-Sequenz mit Messwerten:** `-Darius.verify=dragon -Darius.verify.scene=continuity -Darius.verify.split=both`. Das schreibt `continuity_open.txt`, `continuity_closed.txt` und Einzelbilder pro Punkt.
- **Berg-Schnitt:** `-Darius.verify=hunt -Darius.verify.part=mtn`.
- **Exit-Code 0 heißt hier nicht bestanden.** Der Continuity-Test meldet Grenzwertverletzungen nur im Bericht. Lies den Bericht und schau dir die Bilder an.
- Die Messwerte schwanken zwischen Läufen etwas. Prüfe Grenzwerte deshalb mehrfach.
- Es darf nur ein Test-Client gleichzeitig laufen. `ALSOFT_DRIVERS=null` und `-Dfabric.noGui=true` nie entfernen.
- Nach einem Lauf mit anderen Loader- oder HHA-Versionen immer `./gradlew processResources --rerun build runClientGameTest` ausführen, sonst bleiben falsche Metadaten im Jar. Fehlen Kotlin-Cache-Dateien, ergänze `-Pkotlin.incremental=false`.

**Echte Render-Mods:** Das Modpack liegt nur auf dem PC des Besitzers. Wenn du dort arbeitest, füge die Jars aus der Launcher-Instanz über `-Darius.verify.addmods=<jar;jar;...>` hinzu und nutze `-Ploader_version=0.20.1 -Phha_jar=<HHA-0.9.0.jar>`. Relevant sind Sodium, Sodium Extra, Reese's Sodium Options, EntityCulling, ImmediatelyFast, MoreCulling, Bobby, Gamma Utils, BactroMod, ClearViews, NoFog, Continuity, CullLeaves, BetterGrassify, YACL, Cloth Config und ModMenu. Läufst du ohne sie, sag im Bericht, dass die Prüfung ohne diese Render-Mods war.

## 5. Originalfeedback dieser Runde

Der Besitzer hat Videos und Screenshots geschickt. **Sie sind nicht im Repository.** Bitte den Besitzer, sie erneut anzuhängen, wenn du sie brauchst: `Aufzeichnung 2026-11-22 193056.mp4` (Beschwörung), `193737.mp4` (Einsammeln und Flug), `194026.mp4` (Abflug, Nahaufnahme).

> So... wir machen jetzt mal alle phasen einzelnt. Also cutscene vor phase 1 bis cutscene zu phase 2 usw.
>
> - Kamera wackelt immer noch sehr stark, wenn er mich in die kombo packt
> - Der "besondere effekt bei White horizon attacke ist immer noch kacke/nicht existierend
> - Er soll den berg erst später mit yang fixxen, wenn man da auch wieder lang gehen muss so
> - Zur cutscene wo er Solvane beschwört:
>   - Da ist so ein komisches modell was gar kein sinn ergibt [Bild: durchsichtige blaue diagonale Stäbe neben seinem Schwert]
>   - Der shader setzt viel zu spät ein. Man sieht den drachen schon, dannach setzt er ein sodass man nur seine Silhouette sieht
>   - In der cutscene, wo gezeigt wird wie er um diese eis spitze hängt:
>     - Seine beine/füße bewegen sich komisch und sind nicht an diesem eis teil drann
>     - Seine Flügel sind komisch gedreht
>     - Sein Kopf ist, glaube ich zumindest, falsch gedreht
>   - Da siehts leicht so aus, als würde er durch diese mauer oder was das ist durchfliegen
>   - Der kommt da auch von der seite angeflogen, sein körper ist eigentlich noch da, tped sich dann aber hierhin
> - Zu der Cutscene, wo er dich aufsammelt und zu seiner eigenen Arena fliegt:
>   - Man wird da irgendwie einfach drauf geweht vom wind, also an dieses eis teil... cooler wäre sowas wie, dass er volle kanne mit dem kopf gegen deinen körper fliegt, dich dann so hoch haut, und man während dem fall sich gerade so an diesem eis teil festhält (so in der art halt. Probier sowas in der richtung umzusetzen
>   - Wie er anfängt zu fliegen sieht weird aus und irgendwie macht sein körper da auch buckel
>   - Der fliegt durch die wand da einfach
>   - Verbessere den part in der cutscene, wo er sich so dreht und man dann runterfällt. Aktuell fällt mein charakter einfach durch den körper vom drachen lol
>   - Arius super spung vom drachen auf sein kleines podest macht überhaupt gar keinen sinn
>   - Bei seiner landung slidet Solvane irgendwie so komisch und bewegt seine beine/körper nicht

## 6. Aktueller Stand und offene Arbeit

Stand des Branches: Build und Standard-Clienttest bestanden. Der Continuity-Lauf mit echten Render-Mods wurde auf dem zusammengeführten Stand ausgeführt. **Es wurde kein kompletter Kampf von Hand gespielt.**

### A. Kampf

| Punkt | Stand | Noch zu tun |
| --- | --- | --- |
| **Kombo-Kamera** | Hauptursache behoben: `PunishCam.tick` pendelte jeden Tick zwischen zwei Blendwerten. Eine weichere Blende ist als WIP committet, **aber ungeprüft**. | Im Fall `punish` über `t=1..129` messen. Ziel: höchstens **8° Yaw** und **0,6 Blöcke pro Tick**, keine Richtungsumkehr. Vor der WIP-Blende waren es 27,8° und 1,89 Blöcke. |
| **White Horizon** | Aufladen ist gut. Die erste Version des Schlags war ein grauer Schleier mit dunklem Balken und wurde abgelehnt. Eine neue Version ist als WIP committet, **aber nicht abgenommen**. | Fall `horizonviews`, Peak-Tick plus zwei Folgeticks in 1st Person, 3rd Person und Sturm prüfen. Gewollt ist ein messerscharfer, spitz zulaufender, fast weißer Schnitt quer durchs Bild, 2 bis 3 Ticks lang das Hellste auf dem Schirm. Keine Rechtecke, keine breite dunkle Fläche, kein matschiger Grauton. |
| **Yang später** | Umgesetzt in `boss/Hunt.kt`, direkt vor der ersten echten Überquerung des Spalts im Spire-Massiv. Sichtbare Szene mit Arius, Sicherung gegen Reinfallen, Absturz-Wiederherstellung erhalten. | Fall `yanglater` und den angepassten Test `hunt`/`mtn` laufen lassen. `diffAfterYang` muss exakt 0 sein. |

### B. Solvane-Szenen

Die erste Runde ist committet. Die Prüfung der Bilder ergab diese sieben offenen Punkte. **An ihnen wurde noch nichts geändert.**

1. **Silhouette im Sturm:** Rund 18 Testbilder zeigen fast nur leeres Weiß. Der Sturm muss zuerst stehen, dann zieht **1,5 bis 2 Sekunden eine große, dunkle, klar drachenförmige Silhouette** hindurch, dann die Enthüllung. Leere Strecke unter 1 Sekunde.
2. **Eisspitze bei offenem Spalt:** Der Schnitt entfernt das schmale Eishorn oberhalb von etwa `y=195`, Solvane wickelt sich dann um nichts. Das Horn soll stehen bleiben, mit nur etwa **1 Block breiter sichtbarer Naht**. Der Schnitt muss trotzdem klar durch die Gipfelarena laufen, und `SplitRecovery` muss exakt bleiben.
3. **Spitzen-Shot:** Die Kamera steckt kurz in Flügel oder Körper, und einmal klebt sie am Eis. Ergänze eine Messung für **Kameraticks im Körper** und bringe sie auf 0. Im letzten Shot ist der Kopf nach hinten gekippt und hat einen weißen Block oben. Ergänze eine nähere Einstellung, in der die **Krallen sichtbar die Spitze greifen**.
4. **Kopframme:** Der Treffer ist nicht zu sehen. Gewollt: kurzer Anlauf, nahe lesbare Aufnahme, in der ihr Kopf den Spieler trifft, ein Hit-Stop, Flugbogen mit folgender Kamera, dann das **sichtbare, knappe Festklammern** am Eisstachel von oben oder von der Seite.
5. **Rolle und Fall:** Die Rolle ist zu klein im Bild, und der Spieler fällt auf oder in die blaue Eissäule in der Arenamitte. Näher inszenieren, auf **freiem Boden** landen. Messung für **Spielerticks in festen Blöcken** ergänzen und auf 0 bringen.
6. **Schwanz-Sprung beim Abflug:** Das Schlimmste in den Messwerten. Ziel: **höchstens 2,5 Blöcke pro Tick** für jedes Segment in der ganzen Sequenz. Suche beim Start der Flugkurve in `NivarisModel` (`trail`, `takeoffOdo`).
7. **Hinterfüße bei Landung 1:** Ziel: **höchstens 0,25 Blöcke Rutschen** bei beiden Landungen.

Aktuelle Messwerte auf `wip/1.14-phase1`:

| Messung | Spalt offen | Spalt geschlossen | Ziel |
| --- | ---: | ---: | ---: |
| Max. Segmentbewegung pro Tick | 7,14 (`t=923`) | **10,34** (`t=921`, Segment 36) | ≤ 2,5 |
| Körpersamples in festen Blöcken | 0 | **1** | 0 |
| Kameraticks in festen Blöcken | 0 | 0 | 0 |
| Spieler schneidet Solvanes Körper | 0 | 0 | 0 |
| Max. Fußrutschen (Landung 1, hinten) | **2,83** | **2,66** | ≤ 0,25 |

Der eine Körpersample in festen Blöcken ist erst nach dem Zusammenführen aufgetaucht und muss mit behoben werden.

Diese Punkte wurden in Runde 1 als erledigt gemeldet und nach den Bildern vorläufig akzeptiert. Prüfe sie beim Abschluss noch einmal mit: Schwertmodell ersetzt, Flugbahnen frei von Wänden, Übergang vom Anflug in die Landung, Arius' neuer Abstieg beim Tiefflug auf sein Podest, Bremsen mit den Flügeln bei Landung 2.

## 7. Bekannte Fallen

- Frühere Agenten haben „fertig“ gemeldet, obwohl ihre eigenen Bilder das Problem zeigten. **Schau dir jedes Kontaktblatt wirklich an.**
- Ein Agent hat seinen Turn beendet, während er auf einen Test „wartete“, und unfertige Änderungen liegen lassen. Beende deinen Turn erst, wenn alles committet ist.
- Reine Blockkollision reicht nicht. Kamera in Solvanes Körper wurde nur auf den Bildern sichtbar.
- Einzelbilder zeigen keine Sprünge zwischen Ticks. Nutze für Bewegung immer auch die Messwerte.
- Tests dürfen nicht abgeschwächt werden, damit sie bestehen.
- Teste Szenen bei Spalt **offen und geschlossen**, weil der Spalt jetzt lange offen bleibt.
- Szenen müssen für **1 bis 4 Spieler** funktionieren.

## 8. Abschluss und Veröffentlichung

Wenn alle Punkte aus Abschnitt 6 erfüllt sind oder der Besitzer ausdrücklich sagt, dass der Stand raus soll:

1. `mod_version` in `gradle.properties` auf `1.14.0` setzen und in `README.md` einen Abschnitt **„New in 1.14.0“** ergänzen.
2. Sauber bauen: `./gradlew processResources --rerun build runClientGameTest`. Prüfen, dass das Jar `fabricloader >=0.19.3` verlangt.
3. Direkt auf `main` bringen und pushen. Die Vibe-Website holt sich das automatisch, eine Nachricht ist nicht nötig.
4. Tag `v1.14.0` und **GitHub-Release** mit `HHA_Arius-1.14.0.jar` erstellen, als **Latest** markiert.
5. **Nur wenn du auf dem PC des Besitzers arbeitest:** Das Jar in die Vibe-Launcher-Instanz `1.21.11` legen. Vorher prüfen, dass Minecraft nicht läuft, und das alte Jar nach `minecraft/.hha-arius-backups/<yyyyMMdd-HHmmss>/` verschieben. Beim letzten Stand waren alle HHA-Mods dort `.disabled`. Schalte nichts ungefragt ein, sondern lege das neue Jar ebenfalls als `.disabled` ab und weise darauf hin, dass HHA, Arius, Blood und Void aktiviert werden müssen.

**Arbeitest du auf einem anderen PC**, kannst du Schritt 5 nicht ausführen. Gib dem Besitzer dann den Link zum Release. Prüfe vor Schritt 3 mit `gh auth status`, ob du Schreibrechte auf `vibec-studios/hha-arius` hast. Falls nicht, sag es sofort, statt auszuweichen.

### Harte Grenzen

- Die Login-Datei `accounts.json` des Vibe Launchers **niemals** lesen.
- Die globale Git-Konfiguration nicht ändern. Committe mit `git -c user.name="HHA Arius Agent" -c user.email="agent@users.noreply.github.com" commit`.
- Das Repository ist **öffentlich**: keine lokalen Pfade, E-Mail-Adressen oder privaten Daten in versionierte Dateien.
- Lösche `HANDOFF.md` im Release-Commit, damit sie nicht in `main` landet.

## 9. Abschlussbericht an den Besitzer

Nenne jeden Punkt aus Abschnitt 6 als erledigt, teilweise oder nicht erledigt, mit den Messwerten und dem, was noch falsch aussieht. Sag ausdrücklich, was du nicht prüfen konntest, zum Beispiel einen kompletten Kampf, echtes Multiplayer, Quick-Time-Tasten, Musik oder FPS. Bitte danach darum, im Spiel zu testen und Feedback zur nächsten Phase zu schicken.
