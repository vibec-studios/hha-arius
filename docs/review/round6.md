# Review Runde 6 – HHA Arius

Stand: 16.11.2026, HEAD `3b55eee908da70d3348cc54e9af01a2759b5a225`. Geprüft wurden `git show 3b55eee`, die Berichte Runde 1–5, die aktuellen Einsatz-/Anspruchs-/Fach-/Quittungspfade und nochmals die bisherigen kritischen/hohen Stellen sowie ergänzende Server-/Client-Pfade. Datei- und Zeilenangaben beziehen sich auf diesen HEAD und sind relativ zum Addon.

**Ergebnis: kein offener kritischer oder hoher Befund statisch belegt. R5-1 ist geschlossen. R5-2 ist nur teilweise geschlossen und bleibt mittel offen. R5-3 bleibt als bewusst akzeptierte niedrige Grenze bestehen. Für die ausdrücklich auf kritisch/hoch beschränkte statische Freigabe: ja, jetzt confident, unter den unten beschriebenen Speicherannahmen.**

Kein Produktivcode geändert, kein Build ausgeführt, Minecraft nicht gestartet. HHA unter `C:\Users\henny\Desktop\HHA` ausschließlich gelesen. Die Vanilla-Prüfung erfolgte mit `javap -c -p` an der lokal vorhandenen Minecraft-1.21.11/Yarn-build.6-Jar; dabei wurden keine Minecraft-Klassen ausgeführt. Als dauerhaftes Review-Ergebnis wurde ausschließlich dieser Bericht geschrieben.

## R6-1 / verbleibender R5-2 – [mittel] Kreativ-Teilzustellung kann weiterhin den Rest eines Fachstapels löschen

**Datei:Zeile:** `src/main/kotlin/dev/henny/hha_arius/boss/Ledger.kt:92–101`, insbesondere `:95–97`. Automatischer Aufruf: `src/main/kotlin/dev/henny/hha_arius/boss/Encounters.kt:186–191`. Stapelbare Belohnungen: `src/main/kotlin/dev/henny/hha_arius/gear/Gear.kt:97–106`.

**Szenario:** Im Fach liegen beispielsweise 4 Heartfrost. Das Kreativinventar hat keinen freien Slot, aber einen komponentengleichen Heartfrost-Stapel mit 63 von 64 Items; alle anderen Slots bieten keinen Platz. Die neue Prüfung findet diesen passenden Slot und erlaubt `insertStack(stack)` mit Anzahl 4. Vanilla fügt zunächst 1 Item ein. Innerhalb desselben Aufrufs versucht die interne Schleife anschließend, die verbleibenden 3 Items einzufügen. Da nun kein Platz mehr existiert, macht dieser interne Versuch keinen Fortschritt. Der Kreativ-Fallback setzt den Reststack auf 0. `flush()` berechnet deshalb `moved = 4 - 0`, zieht alle 4 vom Fach ab und entfernt den Eintrag. Tatsächlich ist nur 1 Item angekommen, 3 sind verloren. Kein Absturz oder beschädigter Speicherstand erforderlich.

**Statischer Nachweis:** `PlayerInventory.insertStack(int, ItemStack)` setzt bei Bytecode-Offset 16 die lokale Vorher-Anzahl für **jeden internen Schleifendurchlauf** neu. Bei Fortschritt und verbleibendem Rest springt Offset 60 zurück nach 16. Nach dem folgenden erfolglosen Versuch sind Restanzahl und diese inzwischen aktualisierte Vorher-Anzahl identisch; Offset 75 prüft Kreativ, Offset 83 führt `setCount(0)` aus. Die äußere Platzprüfung des Addons wird zwischen diesen internen Versuchen nicht erneut aufgerufen. `getOccupiedSlotWithRoomForStack()` garantiert nur einen aufnahmefähigen Slot, keine Kapazität für den gesamten übergebenen Stack.

**Status des Fixes:** Der vollständig volle Fall ohne irgendeinen passenden/freien Slot ist geschlossen. Ebenso sind die derzeitigen unbeschädigten Einzelitems wie Siegel und Solvane durch diese Prüfung geschützt. Der regulär erreichbare Teilstapel-Fall bei Materialien bleibt offen. Dies ist ein Rest des bekannten mittleren Befunds, kein neuer hoher Fehler und keine vollständige Behebung von R5-2.

**Vorschlag:** In Kreativ das Fach unangetastet lassen, oder explizit höchstens die tatsächlich verfügbare Kapazität eines bestimmten Slots übertragen und nur die übertragene Menge abbuchen. Ein bloßer Kapazitätstest vor einem möglicherweise größeren `insertStack()`-Aufruf genügt nicht. Zur Laufzeit gezielt den Fall „Fach 4, Inventarstapel 63/64, sonst voll“ in Survival und Kreativ vergleichen: Erwartung jeweils 1 eingefügt, 3 im Fach.

## Verifikation des hohen R5-1-Fixes

**Belege:** `src/main/kotlin/dev/henny/hha_arius/boss/Ledger.kt:28`, `:48–55`; `src/main/kotlin/dev/henny/hha_arius/boss/Encounters.kt:150–171`.

`firstLootTaken` ist ein normales, nicht transientes Ledger-Feld. Es wird mit demselben Gson-/Attachment-Codec wie `paid`, `stake` und `inbox` in der Spielerdatei gespeichert. Die Zustellung prüft für jeden noch nicht quittierten Loot-Claim ausschließlich `!ledger.firstLootTaken`, setzt das Flag und legt die Beute im selben synchronen Ablauf ins Fach. Ein Siegel-Claim setzt es nicht. `Claim.firstWin` und das weltseitige `wins` autorisieren die Erstbeute nicht mehr.

Damit schließt der Fix den konkreten R5-Ablauf: Nach Auszahlung → Logout → Absturz vor Weltspeicherung enthält P weiterhin Erstbeute/Fach, Quittung und `firstLootTaken = true`, obwohl W wieder `wins = 0` enthalten kann. Ein neuer Claim mit neuer UUID wird weiterhin bezahlt, erhält aber Wiederholungsbeute. Auch mehrere bereits vorhandene Claims mit `firstWin = true` erzeugen pro geladenem Spielerstand nur einmal Erstbeute: Der erste nicht quittierte Loot-Claim setzt das Flag, die weiteren sehen es bereits. Ist P auf den Stand vor der Zustellung zurückgerollt, sind Flag und diese Zustellung gemeinsam zurückgerollt; eine erneute Erstzustellung ist dann korrekt.

**R5-1: geschlossen, keine hohe Regression durch diesen Fix belegt.** Fehlende neue Felder beginnen bei neuem/älterem Ledger mit `false`. Ein Upgrade bereits produktiv ausgezahlter Zwischenversionen ohne dieses Flag würde eine eigene Migration benötigen. Hier gilt weiterhin die in den Vorberichten dokumentierte Projektvoraussetzung, dass solche Review-Zwischenstände keine zu migrierenden produktiven Datenbestände bilden. Das ist keine Zusage einer automatischen Migration solcher Bestände.

## Gesamtsystem: Speicher- und Recovery-Matrix

P bezeichnet die vollständige Spielerdatei mit Inventar und Ledger einschließlich Einsatz, Quittungen, Fach und Erstbeuteflag; W bezeichnet den Arius-Weltzustand mit Claims, Siegen und Statistik. Alt/neu beziehen sich jeweils auf den angegebenen Übergang. Betrachtet werden intakte, vollständig geschriebene einzelne Dateien und Abstürze zwischen Speicheroperationen. `state.save()` ist `markDirty()` (`AriusState.kt:70`), keine sofortige gemeinsame Transaktion mit P.

### Einsatz und Rückgabe

| Übergang | P alt / W alt | P alt / W neu | P neu / W alt | P neu / W neu |
| --- | --- | --- | --- | --- |
| Start: Siegel entnehmen und `stake = E` setzen (`Encounters.kt:223–226`) | Originalsiegel vorhanden, kein E: keine Erstattung. | Ebenso; Dirty-Slot/Heimdaten allein erzeugen keine Erstattung. | Siegel fehlt, E gespeichert: ohne gespeicherten Sieg/Claim nach Neustart einmal erstatten und E löschen. | Dieselbe Recovery; Kämpfe selbst werden nicht aus W fortgesetzt. |
| Online-Niederlage/Abbruch: E löschen und Siegel ins Fach legen (`EirholmFight.kt:945–956`) | Alter E wird nach Neustart einmal erstattet. | Ebenso; Arena-Cleanup ist kein Auszahlungsnachweis. | Zurückgegebenes Siegel im Fach/Inventar und gelöschter E gemeinsam gespeichert: keine zweite Erstattung. | Ebenso. |
| Verwaisten Einsatz beim Login erstatten (`Encounters.kt:174–178`) | E bleibt als Nachweis: erneute Recovery zulässig. | Ebenso, wenn W keinen Sieg zu E kennt. | Siegel und E-Löschung gemeinsam in P: keine erneute Erstattung. | Ebenso. |

Bei Offline-Niederlage wird P nicht mutiert; der gespeicherte E bleibt der Rückgabenachweis bis zum Login. Ein noch nicht gespeicherter Start rollt auf das vorhandene Originalsiegel zurück. Online-Rückgabe, volle Inventare und Recovery erzeugen keinen neuen weltseitigen Ersatzanspruch als einzigen Nachweis; das Fach bleibt in P. Der niedrige Komponentenverlust ist separat unten dokumentiert.

### Sieg und Auszahlung

Bei „Sieg vor Zustellung“ ist der Ausgangsstand P der bereits gebuchte Einsatz E. Der Sieg selbst verändert dessen Ledger nicht.

| Übergang | P alt / W alt | P alt / W neu | P neu / W alt | P neu / W neu |
| --- | --- | --- | --- | --- |
| Sieg vor Zustellung (`EirholmFight.kt:893–900`) | E vorhanden, kein Sieg/Claim: ungespeicherter Sieg rollt zurück, Einsatz wird erstattet. | Sieg und Claim gespeichert: auszahlen, dabei E zusammen mit Quittung/Fach erledigen. | Sieg erzeugt keinen neuen P-Ledger-Stand; wie links. | Wie P alt / W neu. |
| Loot-Claim C zustellen (`Encounters.kt:150–171`) | War C schon im alten W, erneut zustellen, da P keine Quittung hat. War C ebenfalls ungespeichert, rollt Sieg samt Zustellung zurück; E bleibt erstattbar. | C in W, keine P-Quittung: zustellen. | Beute/Fach, Quittung C, Erstbeuteflag und ggf. E-Löschung gemeinsam in P. Keine zweite C-Zustellung; auch ein neuer Claim nach W-Rollback erhält nur Wiederholungsbeute. | Quittung verhindert Wiederholung, W-Statistik/Sieg sind ebenfalls vorhanden. |
| `seal`-Claim C zustellen | Vorhandener alter C wird erneut zugestellt; ein nicht gespeicherter C lässt sich nicht rekonstruieren. Aktuelle Niederlagen erzeugen solche W-Claims nicht mehr. | C wird zugestellt. | Siegel und Quittung gemeinsam in P: keine Wiederholung von C. | Ebenso. |

`markWon`, Claimanlage für `fighters.keys` und `wins++` laufen synchron im selben Weltzustand. Ein reguläres W-Snapshot mitten zwischen diesen Schritten wurde nicht belegt. Offline-Teilnehmer behalten ihren Claim. Vor Zustellung wird der passende Einsatz nicht gelöscht; nach Zustellung sind seine Löschung und der Gegenwert gemeinsam in P. Ein voller Inventarstand lässt die Beute im Fach. Der Kreativ-Teilzustellungsverlust aus R6-1 ist eine separate fehlerhafte Mengenbuchung innerhalb dieses P-Übergangs, kein wieder offener hoher P/W-Verlustpfad.

### Claim-Bereinigung, Fach, Erstbeute und Sitzungsgrenzen

| Übergang | P alt / W alt | P alt / W neu | P neu / W alt | P neu / W neu |
| --- | --- | --- | --- | --- |
| Claim nach dauerhaft geladener Quittung entfernen | C in W, Quittung bereits in P: überspringen und erneut bereinigen. | C weg, Quittung bereits in P: erledigt. | Zusätzliche P-Änderungen ändern die alte dauerhafte Quittung nicht: kein Replay. | Erledigt. |
| Fach → Inventar in Survival | Alte Fachmenge und altes Inventar gemeinsam vorhanden; später erneut versuchen. | W unerheblich, wie links. | Fach um tatsächliche Insert-Menge verringert, Inventar entsprechend erhöht. | W unerheblich, wie links. |
| Erstbeute samt Flag speichern | Falls Zustellung zurückrollt, rollen Beute/Fach und Flag gemeinsam zurück. | Gespeicherter Claim darf erneut Erstbeute zustellen. | Erstbeute und Flag bleiben auch ohne W-Sieg zusammen erhalten; neue Claims nur Wiederholungsbeute. | Erstbeute genau einmal im dauerhaft gespeicherten P-Verlauf. |

- **Frische Quittungen:** `sessionPaid` schützt alle in dieser Verbindungssitzung erzeugten Quittungen vor frühzeitiger W-Claimlöschung. Auch Autosave führt nicht zu vorzeitiger Bereinigung. Erst nach erneutem Laden von P gilt eine Quittung als bereits dauerhaft und erlaubt W-Bereinigung. Ein W-neu-ohne-C/P-alt-ohne-Quittung-Stand wird durch diesen Bereinigungspfad nicht erzeugt. W darf nach dem Löschen wieder auf einen älteren Claimstand zurückfallen: Die dauerhaft behaltene Quittung blockiert Replay.
- **Keine Mengenlimits:** Weder `paid` noch `wonEncounters` werden auf die früheren 300/500 Einträge gekürzt. Die konkreten Langzeit-Replays und falschen Einsatzrückgaben aus R4 bleiben geschlossen. Die Listen wachsen; kein neuer konkreter kritischer/hoher Ausfall daraus in normalen Datenmengen belegt.
- **Teilzustellung in Survival:** Der Input-Rest bleibt erhalten; nur `before - stack.count` wird abgebucht. Die neue Vorprüfung beendet erfolglose weitere Versuche. Große Fachmengen werden in Standardstapel zerlegt. In Kreativ gilt die Ausnahme R6-1; bei vollständig fehlendem Platz verhindert die Vorprüfung nun den Aufruf.
- **Logout/Rejoin:** Vor Auszahlung bleiben E oder W-Claim erhalten; danach speichert Logout Gegenwert, Quittung, Flag und E-Löschung gemeinsam. Rejoin lädt `sessionPaid` leer, was nach erfolgter Speicherung korrekt ist. Zustellung innerhalb der Arena oder an bereits getrennte Spieler ist gesperrt. Beim normalen Sieg kommt der Heimtransport vor Auszahlung.
- **Tod/Respawn:** `copyOnDeath()` ist unverändert registriert. Die in Runde 4 an Fabric geprüfte Übertragung derselben Ledger-Instanz erhält auch `sessionPaid`; der neue boolesche Wert wird ebenfalls mit übernommen. Im Addon wurde kein neuer Codec-Roundtrip oder Ledger-Reset bei Respawn eingeführt. Normale Todesdrops nach erfolgreicher Inventarzustellung unterliegen weiterhin Vanillas eigenen Welt-/Inventargrenzen.
- **Regulärer Stopp:** Laufende Kämpfe werden mit `finish(false)` beendet. Vor Sieg erfolgt online Rückgabe in P, offline bleibt E. Nach Sieg ist `sealStake` leer, also keine zusätzliche Rückgabe durch `finish(false)`; die Claims bleiben bestehen. Ein Neustart findet gespeicherte Claims bzw. den offenen E. Der frühere 400-Tick-Taskverlust löscht den Anspruch nicht mehr.

### Reichweite der Speicherkombinationen

Die Tabellen betrachten alle vier Kombinationen für die einzelnen Protokollübergänge, keine beliebige unabhängige Wiederherstellung historischer Dateien. Wie bereits in Runde 5 bleibt folgende Grenze: Ein P **von vor der Siegelentnahme** zusammen mit W **nach dem Sieg** hätte Originalsiegel plus Beuteanspruch. Das Protokoll prüft keinen Entnahmenachweis für einen Sieger-Claim; Gruppenmitglieder besitzen legitim keinen eigenen Einsatz. Es wäre deshalb falsch, Robustheit gegen beliebige selektive Backups zu behaupten.

Für den regulären untersuchten Ablauf ist dieser zusätzliche Zustand nicht konkret belegt: Der erneut geprüfte lokale Bytecode von `MinecraftServer.saveAll()` speichert `PlayerManager.saveAllPlayerData()` vor `save()`, `runAutosave()` verwendet `saveAll()`, und Logout speichert den bereits entnommenen Einsatz. Das Addon selbst erzwingt kein unabhängiges sofortiges W-Speichern nach dem Sieg. Die hohe R5-1-Lücke benötigte diese ausgeschlossene Kombination nicht und ist tatsächlich behoben. Schreibfehler, beschädigte Dateien, fremde Mods mit zusätzlicher Speicherreihenfolge und selektiv zurückgespielte Backups sind keine hier bestätigte Recovery-Garantie.

## Bekannte akzeptierte Grenze R5-3 – [niedrig]

**Datei:Zeile:** `src/main/kotlin/dev/henny/hha_arius/boss/Ledger.kt:30`, `:72`, `:92`; `src/main/kotlin/dev/henny/hha_arius/boss/Encounters.kt:177`, `:223`; `src/main/kotlin/dev/henny/hha_arius/boss/EirholmFight.kt:952`.

**Szenario:** Ein am Amboss individuell benanntes Siegel wird eingesetzt und bei Niederlage zurückgegeben. Fach und Recovery speichern nur Item-ID/Anzahl bzw. Begegnungs-ID. Das rekonstruierte Standardsiegel verliert den Namen und andere individuelle Komponenten. Der neue Fix ändert daran nichts; ein ganzes Siegel geht hierdurch nicht verloren.

**Vorschlag/Status:** Vollständigen Einsatzstack und Fachstack mit geeignetem ItemStack-Codec erhalten, falls diese Funktion später gewünscht ist. Gemäß Auftrag bewusst akzeptiert, als bekannte niedrige Grenze fortgeführt. Standardkomponenten der neu erzeugten Beute werden weiterhin über Item-Defaults rekonstruiert; daraus kein neuer hoher Verlust belegt.

## Status sämtlicher bisheriger Befunde

| Befund | Status nach Runde 6 |
| --- | --- |
| R1-1 – Task-Iterator/CME | Geschlossen; Snapshot-Ausführung, Entfernen aus Originalliste und Abbruch nach `ended` bestehen fort (`EirholmFight.kt:427–433`). |
| R1-2 – Sequenzen nach K. o./Kampfende | Geschlossen; `canSequence()`, `hurtHeld()` und Nachschadensprüfungen bleiben vorhanden. |
| R1-3 – Arenaabbau/Platzierung/Folgearena | Geschlossen für den gemeldeten Survival-Pfad; Sperren und Planprüfung bestehen fort. |
| R1-4 – Sieg ohne Siegelverbrauch | Geschlossen für Weitergabe und die konkret gemeldeten Persistenzpfade; R5-3 bleibt akzeptiert. |
| R1-5 – Beute im Cleanup gelöscht | Geschlossen; kein Beutedrop im aktuellen Zustellprotokoll, Fach außerhalb der Arena. |
| R1-6 – Dialog-Long-Überlauf | Geschlossen; endlicher Initialwert besteht fort. |
| R1-7 – Hold länger als 40 Ticks | Geschlossen; absolute Deadline bleibt beim Phasenwechsel erhalten. |
| R1-8 – Fremder perfekter Block verhindert Schaden | Geschlossen; Schaden an `lost` bleibt von fremdem perfektem Block unabhängig. |
| R1-9 – Nachzügler-Rückweg | Ursprünglicher Befund geschlossen; Spawn-Fallback besteht fort. |
| R2-1 – Beuteverlust bei Disconnect/Stopp nach Sieg | Geschlossen für die gemeldeten Pfade einschließlich der hohen Folgefehler. |
| R2-2 – Einsatzverlust beim Absturz | Geschlossen für die konkret belegten Entnahme-, Rückgabe- und Siegübergänge. |
| R2-3 – Netherdach-Rückweg | Geschlossen; unbekannte Herkunft nutzt den Server-Spawn. |
| R2-4 – Musik nach Weltwechsel/Rejoin | Geschlossen; Herkunftswelt-/Teilnehmersuche und Synchronisation bestehen fort. |
| R2-5 – HHA-Abilities während Hold | Geschlossen gemäß festgelegter Regel: Gate verhindert neue Casts, bereits aktiver Frostschritt bleibt erlaubt. |
| R3-1 – Replay ausgezahlter Beute/Siegel | Geschlossen für die gemeldeten Pfade; stabile Claims, dauerhafte Quittungen und Fach bestehen fort. |
| R3-2 – Einsatz ohne dauerhaften Nachweis | Geschlossen für die gemeldeten Pfade; Entnahme und Rückgabe bleiben gemeinsam in P erfasst. |
| R3-3 – Musik-Stopp findet Kampf nicht | Geschlossen; Suche über Ursprungswelt und Teilnahme bleibt vorhanden. |
| R4-1 – Rückgabe bei vollem Inventar | Hoher Survival-Persistenzbefund geschlossen; mittlerer Kreativ-Rest unter R6-1. |
| R4-2 – Quittierte Welt-Drops | Geschlossen; aktuelle Zustellstellen verwenden das Fach. Alte `GearRewards.returnSeal/consumeSeal` werden hier nicht aufgerufen. |
| R4-3 – Einsatz vor Siegzustellung gelöscht | Geschlossen; kein Ledger-Löschen beim Sieg, erst beim passenden Claim bzw. Recovery. |
| R4-4 – 300er-Quittungslimit | Geschlossen; keine Kürzung. |
| R4-5 – 500er-Sieglimit | Geschlossen; keine Kürzung. |
| R4-6 – R3-Altdatenmigration | Gemäß dokumentierter Voraussetzung ohne produktive Zwischenstände nicht anwendbar; keine allgemeine Migrationszusage. |
| R5-1 – Erstbeute nach W-Rollback | **Geschlossen** durch spielerseitiges Flag im selben Snapshot wie Gegenwert und Quittung. |
| R5-2 – Kreativ verwirft Fachinhalt | **Teilweise geschlossen, mittel offen als R6-1**: Teilstapel verlieren weiterhin Restmengen. |
| R5-3 – Individuelle Siegelkomponenten | **Niedrig, bewusst akzeptierte bekannte Grenze.** |

## Übriger Code und abschließende Einschätzung

Erneut nachverfolgt: Kampf-Task-/Director-Ausführung und Ende, finale Siegaufrufe, Training/Adminrechte, K.-o.- und Hold-Schaden mit Sequenzfortsetzung, Teilnehmer-/Nachzügler-/Weltwechselpfade, Arena-Aufbaubudget und Block-Restore, Kamm-/Wand-Tasks, Chunkfreigabe, Relikt-Ritual und Siegelübergabe, verzögerte Ausrüstungsfähigkeiten und Task-Mutation, Resonanz-/Solvane-Ereignisse, serverseitiges HHA-Ability-Gate sowie Paketregistrierung und Client-Effektbegrenzung. HHA bestätigt weiterhin das Gate vor `ability.cast()` (`../HHA/src/main/kotlin/dev/henny/hha/api/ability/HhaAbilities.kt:49–50`). Keine neue konkrete kritische/hohe Regression in diesen Pfaden belegt. Die unveränderten Ressourcen-/Modell- und Mixin-Details wurden nicht vollständig neu wie in Runde 1/2 geprüft; die vorliegenden Prüfungen bleiben ergänzende Evidenz, keine neue Ingame-Ladeprüfung.

**Bin ich jetzt „confident“, dass statisch nichts Kritisches/Hohes mehr offen ist? Ja, für den geprüften aktuellen Stand und die beschriebenen regulären Speicherabläufe.** Es gibt keinen verbleibenden belegten kritischen/hohen Befund. Das ist eine statische Einschätzung, keine Fehlerfreiheitsgarantie und keine vollständige Freigabe aller Schweregrade: R6-1 bleibt mittel offen, R5-3 ist niedrig akzeptiert. Ingame-Tests führt der Nutzer aus; besonders sinnvoll sind Teilstapelzustellung in beiden Spielmodi, Logout/Neustart vor und nach Siegzustellung, voller Fachbestand bei Niederlage und Respawn mit offener Zustellung.
