# Review Runde 3 – HHA Arius

Stand: 16.11.2026, HEAD `c2438b0d0388ef116022aa74684ade47b3d9bb94`. Geprüft wurden die Berichte aus Runde 1/2, `git show -w c2438b0`, die aktuellen betroffenen Lebenszyklen und erneut die übrigen sicherheitsrelevanten Kampf-, Arena-, Ausrüstungs- und Clientpfade. Zeilenangaben beziehen sich auf diesen HEAD; Dateipfade sind relativ zum Addon. HHA unter `C:\Users\henny\Desktop\HHA` wurde ausschließlich gelesen. Kein Produktivcode geändert, kein Build ausgeführt, Minecraft nicht gestartet.

**Ergebnis: zwei hohe Befunde zur Persistenz und ein mittlerer Restfehler beim Musik-Stopp. Kein neu belegter kritischer Befund. Statische Freigabe: nein.** Die normalen Disconnect-/Stopp-Fälle sind wesentlich verbessert; die ausdrücklich geforderte Sicherheit über Absturz- und Speichergrenzen ist noch nicht erreicht.

## R3-1 – [hoch] Bereits ausgezahlte Beute und zurückerstattete Siegel können nach einem Absturz erneut ausgezahlt werden

**Beleg:** `src/main/kotlin/dev/henny/hha_arius/boss/Encounters.kt:137–149`, insbesondere `:144–146`; `src/main/kotlin/dev/henny/hha_arius/boss/AriusState.kt:64`; für die direkte Einsatzrückgabe zusätzlich `src/main/kotlin/dev/henny/hha_arius/boss/EirholmFight.kt:945–951`.

`deliverPending()` entfernt Ansprüche vor der Vergabe nur aus dem **Arbeitsspeicher**. `state.save()` ruft ausschließlich `markDirty()` auf. Das schreibt weder die Zustandsdatei noch das Spielerinventar. Damit verhindert die Reihenfolge wiederholte Aufrufe innerhalb desselben laufenden Servers, aber keine Wiederholung nach einem Neustart.

**Konkretes Szenario:** Ein Offline-Spieler hat einen bereits auf Platte gespeicherten Beuteanspruch. Er loggt ein, wird nötigenfalls heimgebracht und erhält die Beute. Die Liste im Arbeitsspeicher ist jetzt leer. Vor dem nächsten Speichern des Weltzustands loggt er wieder aus: Vanilla speichert sein Inventar einschließlich der Beute. Danach stürzt der Server ab. Auf Platte steht weiterhin der alte, nicht erledigte Anspruch. Beim nächsten Login bekommt der Spieler zusätzlich zum gespeicherten Inventar dieselbe Erstbeute erneut, einschließlich garantierter Solvane und Echo. `wins` verhindert das nicht: Der alte `Claim.firstWin` bleibt wahr. Derselbe Ablauf dupliziert `pendingSeals`.

Auch `finish(false)` kann einen bereits gespeicherten Einsatz direkt ins Inventar zurückgeben, anschließend dessen Entfernung nur als dirty markieren und nach Logout plus Absturz den alten Einsatz über `refundOrphanStakes()` nochmals zurückerstatten.

**Zusätzliche Verifikation ohne Spielstart:** Der Bytecode der lokal remappten Minecraft-1.21.11/Yarn-build.6-Jar bestätigt: `PersistentState.markDirty()` setzt nur ein Boolean; `PlayerManager.remove()` ruft `savePlayerData()` auf. Auch ein vollständiges `MinecraftServer.saveAll()` speichert zunächst Spieler und danach Welten. Es gibt hier keine gemeinsame Transaktion beider Datensätze. Ein Absturz zwischen diesen Speicherschritten kann dieselbe Inkonsistenz erzeugen.

**Vorschlag:** Zustellung und Inventarpersistenz als wiederaufnehmbare, idempotente Operation gestalten. Ansprüche benötigen eindeutige IDs, konkrete festgelegte Beute und eine dauerhaft nachvollziehbare Erledigung. Beispielsweise Zustellquittung und Inventar gemeinsam im selben Spieler-NBT-Snapshot speichern und beim Wiederanlauf vor einer erneuten Vergabe abgleichen; Drops brauchen eine entsprechende eigene Zustellstrategie. Journal und Recovery müssen die möglichen Teilstände ausdrücklich behandeln. Nur `markDirty()` durch ein sofortiges Speichern der Anspruchsdatei zu ersetzen genügt nicht: „Anspruch zuerst löschen“ kann beim Absturz vor dem Inventarspeichern stattdessen Beute verlieren. Auch die direkte Einsatzrückgabe muss denselben Mechanismus verwenden.

## R3-2 – [hoch] Siegeleinsatz kann weiterhin vor seiner dauerhaften Erfassung verloren gehen

**Beleg:** `src/main/kotlin/dev/henny/hha_arius/boss/Encounters.kt:180–183`; `src/main/kotlin/dev/henny/hha_arius/boss/EirholmFight.kt:259–264`; `src/main/kotlin/dev/henny/hha_arius/boss/AriusState.kt:64`, `:67–75`.

Der Einsatz wird jetzt in `Data.stakes` erfasst, doch auch diese Erfassung wird nur dirty markiert. Inventarentnahme und Einsatzbuchung sind weiterhin nicht gemeinsam dauerhaft. Der neue Start-Hook kann ausschließlich Einsätze erstatten, die tatsächlich auf Platte angekommen sind.

**Konkretes Szenario:** Nach einem vollständigen Speichern startet ein Spieler einen Kampf und das Siegel wird entnommen. Noch während des Arenaaufbaus trennt er die Verbindung. Sein nun siegelloses Inventar wird gespeichert. Vor dem nächsten Weltspeichern stürzt der Server ab. Beim Neustart enthält die Zustandsdatei keinen Einsatz dieser Begegnung; `refundOrphanStakes()` hat nichts zurückzugeben. Das Siegel ist dauerhaft verloren. Eine lange Begegnung oder ein bereits gespeicherter Dirty-Slot ist für diesen Verlust nicht mehr nötig.

Der umgekehrte Speicherstand muss ebenfalls berücksichtigt werden: Ein dauerhaft vorhandener Einsatz zusammen mit einem älteren Inventar, das das Siegel noch enthält, darf bei Recovery keine zusätzliche Rückerstattung erzeugen. Die neue Map allein beweist diese Abstimmung nicht.

**Vorschlag:** Reservierung, Inventarentnahme und Recovery mit einer eindeutigen Transaktions-/Begegnungs-ID koordinieren. Ein dauerhaft gespeichertes Journal muss unterscheiden können, ob die Entnahme im gespeicherten Spielerzustand bereits enthalten ist. Recovery darf weder blind erstatten noch ausschließlich auf die zuletzt gespeicherte Einsatz-Map vertrauen. R3-1 und R3-2 sollten gemeinsam entworfen werden: Sie betreffen dieselbe fehlende Abstimmung zwischen Weltzustand und Spielerdatei, aber unterschiedliche Übergänge und unterschiedliche Folgen.

## R3-3 – [mittel] Weltwechsel findet den verlassenen Kampf nicht zuverlässig; Musik-Stopp wird übersprungen

**Beleg:** `src/main/kotlin/dev/henny/hha_arius/boss/Encounters.kt:77–79`; `src/main/kotlin/dev/henny/hha_arius/boss/EirholmFight.kt:856–860`; `src/main/kotlin/dev/henny/hha_arius/client/fx/AriusMusic.kt:38–59`; `src/main/kotlin/dev/henny/hha_arius/client/fx/AriusFxClient.kt:65`.

`onLeave()` sendet jetzt korrekt einen Stopp, wird bei Dimensionswechseln aber über `fights[slotOf(player.lastX)]` gesucht. `lastX` ist kein verlässlicher Speicher für die Herkunftskoordinate: Vanillas Teleportpfad ruft `ServerPlayNetworkHandler.requestTeleport()` → `Entity.setPosition(...)` → `resetPosition()` → `updateLastPosition()` auf, bevor Fabric das AFTER_PLAYER_CHANGE_WORLD-Ereignis auslöst. Dann bezeichnet `lastX` bereits die Zielposition.

**Konkretes Szenario:** Ein Spieler verlässt eine Begegnung in Slot 1 per fremdem Teleport zu einem Oberweltziel mit X nahe 0. Der Handler sucht Slot 0. Ist dort kein Kampf oder gehört der Spieler nicht dazu, wird `onLeave()` vollständig übersprungen. Der neue Musik-Stopp geht nicht hinaus; `finish()` erreicht den Spieler außerhalb der Arenawelt ebenfalls nicht. Der Client hält weiterhin `current` und stoppt die Vanilla-Musik. Der ursprüngliche R2-4-Pfad ist damit weiterhin erreichbar. In Slot 0 fällt dies bei einem Ziel nahe X=0 zufällig nicht auf.

**Vorschlag:** Den verlassenen Kampf über Teilnehmer-ID und die übergebene Ursprungswelt ermitteln, beispielsweise aus `fights.values` mit `isParticipant(player)` und `world === origin`. Nicht nach einer bereits veränderten Spielerkoordinate suchen; `fightOf(player)` ist hier wegen seiner Prüfung der aktuellen Spielerwelt ebenfalls ungeeignet. Der neue Musik-Resync in `onJoin()` kann bestehen bleiben.

## Prüfung der übrigen Fix-Annahmen

- **JOIN → Heimtransport → Auszahlung:** `Encounters.kt:70–75` ruft im selben Serverablauf zuerst `onArrive()` und danach `deliverPending()` auf. Gibt es nach Neustart keine Begegnung mehr, schickt `onArrive()` normale Survival-Spieler zuerst heim. `deliverPending()` lehnt die Arenawelt und getrennte Verbindungen ab. Der normale Siegespfad ruft ebenfalls zuerst `finish(true)`, dann `sendHome()`, dann `deliverPending()` auf (`EirholmFight.kt:907–914`). Kein belegter einfacher Doppelaufruf im laufenden Prozess; die Absturzlücke ist R3-1. Kreativ-/Zuschauerspieler werden absichtlich nicht automatisch aus einer verwaisten Arena entfernt; ihre Ansprüche bleiben dort unangetastet.
- **Sieg, Offline-Teilnehmer, regulärer Stopp:** `victory()` legt Ansprüche für `fighters.keys` sofort fest und erhöht `wins` im gleichen `AriusState`-Objekt. Der Einsatz wird aus derselben Datenstruktur entfernt. Ein Offline-Teilnehmer fehlt damit nicht mehr im Empfängerkreis. `finish(false)` beim regulären Stoppen löscht die gespeicherten Beuteansprüche nicht und erstattet nach einem Sieg wegen des leeren `sealStake` kein Siegel. Das schließt die ursprünglichen normalen Verlustfälle aus R2-1. Ein unmittelbar folgender harter Absturz kann noch nicht gespeicherte Siegänderungen weiterhin zurückrollen; „sofort gespeichert“ darf für `markDirty()` nicht behauptet werden.
- **Gson und alte Speicherstände:** Mit `javap` wurden die vorhandenen kompilierten Klassen `AriusState$Data` und `$PlayerData` geprüft: Beide besitzen öffentliche parameterlose Konstruktoren. Ein isolierter JShell-Aufruf mit Gson 2.13.2, Kotlin-Stdlib und diesen Datenklassen, ohne Minecraft zu starten, deserialisierte einen alten Datensatz ohne `stakes` und `pendingRewards`: `stakes={}`, `pendingRewards=[]`, vorhandene `wins=2` und `pendingSeals=1` blieben erhalten. Auch `{}` ergibt leere `players`, `dirtySlots` und `stakes`. Fehlende neue Felder erzeugen hier keinen belegten NPE. Die zusätzlichen Reparaturen in `player()`/`refundOrphanStakes()` schaden nicht. Explizit beschädigte JSONs oder absichtliche `null`-Einträge sind damit nicht als unterstützt nachgewiesen.
- **Notfall-Rückweg:** Der ursprüngliche Netherdach-Pfad über die Höhenkarte der Herkunftswelt ist entfernt. Unbekannte Herkunft führt nun zum Server-Spawn und wird zunächst mit `Hold.safeSpot()` geprüft. Der konkrete hohe R2-3-Befund ist geschlossen. Wenn diese Suche scheitert, bleibt allerdings ein ungeprüfter Höhenkarten-Fallback (`AriusState.kt:107`); außerdem verwendet `sendHome()` ohne gespeichertes Home weiterhin X/Z 0 (`:120`). Das ist keine allgemeine Garantie für beliebige zerstörte oder administrativ ungewöhnliche Spawnorte, aber kein zusätzlich belegter hoher Fehler unter dem normalen Welt-Spawn-Szenario.
- **Ability-Gate:** `Encounters.kt:94` wird vor `ability.cast()` im HHA-Dispatcher ausgewertet (`../HHA/src/main/kotlin/dev/henny/hha/api/ability/HhaAbilities.kt:48–49`). Neue aktive Fähigkeiten während Hold werden damit serverseitig abgewiesen. Bereits vorher gestartete Frostschritt-Schadensabwehr bleibt gemäß ausdrücklicher Regel zulässig. Bereits geplante Releases/Bewegungsschritte anderer Fähigkeiten erhalten dadurch keine neue Hold-Prüfung; aus diesen kurzen Restabläufen wurde kein zusätzlicher kritischer/hoher Fehler belegt.

## Status aller R1-/R2-Befunde

| Befund | Status nach Runde 3 | Begründung |
| --- | --- | --- |
| R1-1 – Task-Iterator/CME | **geschlossen** | Snapshot-Iteration und Abbruch nach `ended` bestehen fort (`EirholmFight.kt:429–437`). |
| R1-2 – Sequenzen nach K. o./Ende | **geschlossen** | Erneute Gültigkeits-/Hold-Prüfungen nach Schaden und `canSequence()` bestehen fort. Kein neuer hoher Fortsetzungspfad belegt. |
| R1-3 – Abbau/Platzierung/Folgearena | **geschlossen im beschriebenen normalen Survival-Pfad** | Blockabbau, BlockItem-Platzierung und Eimer bleiben gesperrt; jeder Versuch prüft den Plan. Beliebige fremde Weltänderungen sind weiterhin kein zugesicherter vollständiger Reset. |
| R1-4 – Sieg ohne Siegelverbrauch | **teilweise geschlossen, hohe Persistenzprobleme offen** | Weitergabe-/Offline-Verbrauchsexploit geschlossen; R3-1/R3-2 verhindern die vollständige Freigabe der Einsatzverwaltung. |
| R1-5 – Beute wird beim Arena-Cleanup gelöscht | **geschlossen** | Vergabe erfolgt außerhalb der Arena nach Cleanup/Heimtransport; R2-1 normal behoben, hoher Folgefehler R3-1 separat. |
| R1-6 – Dialog-Long-Überlauf | **geschlossen** | Endlicher negativer Initialwert besteht fort. |
| R1-7 – Hold länger als 40 Ticks | **geschlossen** | Absolute Deadline bleibt bei Freeze-/Flight-Wechseln erhalten; Client hat zusätzlich eine endliche Sicherung. |
| R1-8 – Fremder perfekter Block verhindert Schaden | **geschlossen** | Schaden an `lost` ist unabhängig von `perfect`. |
| R1-9 – Nachzügler-Rückweg | **geschlossen für den ursprünglichen Befund** | Echter externer Eintritt überschreibt alte Heimatdaten mit dem neuen Spawn-Rückweg; kein pauschaler Netherdach-Rückweg mehr. |
| R2-1 – Beuteverlust bei Disconnect/Stopp nach Sieg | **ursprüngliche Abläufe geschlossen; hoher Folgefehler offen** | Ansprüche entstehen beim Sieg für alle Fighters und überleben reguläres Stoppen. Crash-/Zustellungsrobustheit fehlt, siehe R3-1. |
| R2-2 – Einsatzverlust beim Absturz | **teilweise behoben, weiterhin hoch offen** | Gespeicherte Einsätze können rekonstruiert werden; Inventar und Einsatz sind nicht abgestimmt dauerhaft, siehe R3-2 und Rückgabe-Replay R3-1. |
| R2-3 – Notfall-Rückweg aufs Netherdach | **geschlossen** | Herkunfts-Nether wird für den unbekannten Rückweg nicht mehr verwendet. Grenzen des letzten Fallbacks siehe oben. |
| R2-4 – Musik nach Weltwechsel/Rejoin | **teilweise behoben, mittel offen** | `onJoin()` synchronisiert; `onLeave()` sendet Stopp, wird aber beim Weltwechsel nicht zuverlässig aufgerufen, siehe R3-3. |
| R2-5 – HHA-Abilities während Hold | **geschlossen gemäß festgelegter Regel** | Gate verhindert neue Casts; vorher aktiver Frostschritt darf weiter abwehren. |

## Erneute Prüfung des übrigen Codes und Grenzen

Zusätzlich erneut verfolgt: Kampfabschluss und Finale samt Aufrufstellen von `victory()`, K.-o.- und Sequenzschaden, Task-Ausführung und verzögerte Kämme/Wände, Arenaaufbau/-Restore/-Cleanup, Chunkfreigabe, Teilnehmer-/Nachzüglerverwaltung, Trainings-/Adminpfade, Ausrüstungs-Tasks und Relikte, Resonanz und Konter, Paketdefinitionen sowie Client-Effektanlage, Hold und Musik. In diesen erneut geprüften Pfaden wurde neben den drei Befunden oben kein weiterer konkreter kritischer/hoher Fehler belegt. Die strukturellen Modell-/Ressourcenprüfungen aus Runde 2 wurden nicht vollständig wiederholt; der Fix-Commit ändert diese Dateien nicht.

Die Bytecode- und isolierte Gson-Prüfung sind keine Ingame-Tests. Es wurden keine Server-/Client-Welten geladen und keine echten Welt- oder Spielerdateien verändert. Geometrie, Kampftiming, Rendering und tatsächliches Verhalten unter Last bleiben den Laufzeittests des Nutzers vorbehalten. Für die Persistenzkorrektur sind gezielte Recovery-Prüfungen mit getrennt betrachteten Welt- und Spieler-Snapshots nötig, insbesondere Entnahme → Logout → Absturz sowie Auszahlung → Logout → Absturz.

**Bin ich jetzt confident, dass statisch nichts Kritisches/Hohes mehr offen ist? Nein.** R3-1 und R3-2 sind konkrete hohe Fehler mit belegten Speicherabläufen. Diese Einschätzung beruht auf den noch offenen statischen Befunden, nicht auf der bloßen Abwesenheit von Ingame-Tests.
