# Review Runde 5 – HHA Arius

Stand: 16.11.2026, HEAD `c1549d42b2d4f1a5cdc90a9b060ede34eae9bae0`. Geprüft: Berichte Runde 1–4, `git show -w c1549d4`, aktuelle Einsatz-/Sieg-/Anspruchs-/Fachpfade und gezielt die bisherigen kritischen/hohen Kampfstellen. Alle folgenden Datei- und Zeilenangaben beziehen sich auf diesen HEAD; Pfade sind relativ zum Addon.

**Ergebnis: ein hoher, ein mittlerer und ein niedriger Befund. Kein kritischer Fehler belegt. Die konkreten hohen Befunde R4-1 bis R4-4 sind behoben. Eine vollständige statische Freigabe ist wegen R5-1 dennoch nicht möglich.**

Nur dieser Bericht wurde geschrieben. Kein Produktivcode geändert, kein Build ausgeführt, Minecraft nicht gestartet. HHA unter `C:\Users\henny\Desktop\HHA` wurde ausschließlich gelesen. Zusätzlich zum Quellcode wurde mit `javap -c -p` der Bytecode der lokal vorhandenen Minecraft-1.21.11/Yarn-build.6-Jar geprüft; dabei werden keine Minecraft-Klassen ausgeführt.

## R5-1 – [hoch] Gespeicherte Erstbeute überlebt den Rollback ihrer ausschließlich weltseitigen Erstgewinn-Markierung

**Datei:Zeile:** `src/main/kotlin/dev/henny/hha_arius/boss/EirholmFight.kt:897–898`; `src/main/kotlin/dev/henny/hha_arius/boss/Encounters.kt:159–168`; `src/main/kotlin/dev/henny/hha_arius/boss/AriusState.kt:30`, `:70`. Beuteunterschied: `src/main/kotlin/dev/henny/hha_arius/gear/Gear.kt:96–106`.

**Szenario:**

1. Auf Platte hat der Spieler `wins = 0`. Er gewinnt erstmals. `victory()` erstellt Claim C mit `firstWin = true` und erhöht `wins` auf 1, jeweils nur im dirty markierten Weltzustand.
2. Nach dem Heimtransport stellt `deliverPending()` C zu: Quittung C, Erstbeute im Fach/Inventar und Einsatzlöschung werden gemeinsam in der Spielerdatei vorbereitet. Der Spieler loggt aus, bevor der Weltzustand mit diesem Sieg gespeichert wurde. Damit sind Erstbeute und Quittung gespeichert, `wins = 1` aber nicht.
3. Absturz. Nach Neustart besitzt der Spieler seine Erstbeute, der Weltzustand dagegen wieder `wins = 0`, keinen C und keinen entsprechenden Sieg. `ledger.paid` enthält zwar C, aber weder Erstgewinn-Status noch einen zugehörigen Beutetyp; die Recovery rekonstruiert daraus keinen Erstgewinn-Status.
4. Beim nächsten regulär gewonnenen Kampf erzeugt `victory()` einen **neuen** Claim D mit neuer UUID und erneut `firstWin = true`. Die Quittung C verhindert seine Zustellung nicht. Solvane, Echo und die größeren Erstbeutemengen werden nochmals vergeben.

Das ist keine erneute Auszahlung derselben Claim-ID und erfordert einen weiteren Sieg. Es ist aber eine konkrete Doppelvergabe der ausdrücklich einmaligen Erstbeute über genau die angefragte Spieler-neu/Welt-alt-Speichergrenze. Sie benötigt weder beschädigte Dateien noch R3-Altdaten noch eine angenommene Welt-vor-Spieler-Speicherung. Logout vor Weltspeicherung genügt. Auch ein Absturz innerhalb von `saveAll()` nach dem Spieler- und vor dem Weltschritt kann denselben Stand erzeugen.

**Schweregrad:** Hoch wegen des verbleibenden, wiederholbaren Persistenzfehlers der wertvollen Erstbeute. Der zusätzliche Kampf und der erforderliche Absturz begrenzen den Pfad; es handelt sich ausdrücklich nicht um beliebig viele kostenlose Auszahlungen beim bloßen Wiederanmelden.

**Vorschlag:** Einen dauerhaften Erstbeute-Status in dieselbe Spielerdatei wie Quittung und Fach aufnehmen und bei der Zustellung atomar mit diesen setzen. Bei weiteren Claims muss die Vergabe dieses Status berücksichtigen, einschließlich bereits mit `firstWin = true` angelegter, aber noch nicht ausgezahlter Claims. Weltseitiges `wins` kann für Statistik bestehen bleiben, darf aber nach Rollback keine bereits dauerhaft zugestellte Erstbeute erneut autorisieren. Alternativ pro Quittung dauerhaft genug Metadaten speichern, um diese Entscheidung beim Wiederanlauf rekonstruieren zu können. Nur `wonEncounters` unbegrenzt zu behalten hilft hier nicht: Auch diese Liste rollt mit der Weltdatei zurück.

## R5-2 – [mittel] Volles Kreativinventar löscht den Fachinhalt statt ihn aufzubewahren

**Datei:Zeile:** `src/main/kotlin/dev/henny/hha_arius/boss/Ledger.kt:91–98`; automatische Auslösung: `src/main/kotlin/dev/henny/hha_arius/boss/Encounters.kt:184–188`.

**Szenario:** Ein Spieler besitzt noch Beute oder ein zurückgegebenes Siegel im Fach, wechselt in Kreativ und hat keinen geeigneten Inventarplatz. Spätestens beim nächsten 40-Tick-Durchlauf ruft `flush()` `inventory.insertStack(stack)` auf. Vanillas Kreativ-Sonderfall setzt bei fehlendem Einfügefortschritt den verbleibenden Stack auf Anzahl 0 und liefert Erfolg zurück, ohne ihn einem Inventarslot zuzuweisen. `before - stack.count` ist deshalb positiv; der Code zieht die gesamte Menge vom Facheintrag ab und entfernt diesen. Bei teilweisem Einfügen kann der nächste Schleifendurchlauf entsprechend den Rest verwerfen. Das kann sämtliche Fach-Einträge betreffen. Kein Absturz erforderlich.

**Verifikation:** Bytecode von `PlayerInventory.insertStack(int, ItemStack)`: Im unbeschädigten Zweig wird nach unverändertem Restbestand `player.isInCreativeMode()` geprüft und anschließend `stack.setCount(0)` ausgeführt. Der beschädigte Zweig besitzt denselben Kreativ-Fallback. Der Rückgabewert allein wäre deshalb ebenfalls kein ausreichender Zustellnachweis.

**Schweregrad:** Mittel, weil ein Kreativwechsel erforderlich ist und die aktuellen Fachitems in diesem Modus grundsätzlich ersetzbar sind. Dennoch verletzt dies die Fachgarantie für ganz regulär von dieser Version erzeugte Ansprüche; nach Rückkehr in Survival sind die ursprünglich erspielten Items weg.

**Vorschlag:** Als einfache Korrektur in Kreativ nicht automatisch aus dem Fach entnehmen. Falls Kreativzustellung gewünscht ist, einen Einfügepfad ohne Vanillas Verwerfungs-Fallback verwenden und ausschließlich tatsächlich in Slots übertragene Mengen abziehen. Nicht nur den Boolean-Rückgabewert prüfen.

## R5-3 – [niedrig] Rückgabe rekonstruiert Siegel ohne die Komponenten des eingesetzten Stacks

**Datei:Zeile:** `src/main/kotlin/dev/henny/hha_arius/boss/Ledger.kt:28`, `:70`, `:91`; `src/main/kotlin/dev/henny/hha_arius/boss/Encounters.kt:174`, `:220`; `src/main/kotlin/dev/henny/hha_arius/boss/EirholmFight.kt:952`.

**Szenario:** Der Spieler benennt ein Siegel am Amboss um und setzt es ein. Die Entnahme bewahrt zunächst mit `copyWithCount(1)` seine Komponenten. Bei normaler Online-Niederlage reicht `finish(false)` sogar eine Kopie dieses Stacks an `deposit()` weiter. Dort werden aber ausschließlich Item-ID und Anzahl gespeichert. `flush()` erzeugt ein frisches Standardsiegel; der eigene Name ist verloren. Offline-/Crash-Recovery kann Sonderdaten ebenfalls nicht rekonstruieren, weil `stake` ausschließlich die Begegnungs-ID enthält. Auch ein sofortiges `deposit()` plus `flush()` ohne zwischenzeitlichen Speichervorgang verliert die Daten.

**Schweregrad:** Niedrig für den konkret regulär erreichbaren Namensverlust. Kein Verlust eines ganzen Siegels und kein belegter hoher Verlust von Beutekomponenten. Es werden keine administrativ erzeugten Spezialitems als Voraussetzung unterstellt.

**Vorschlag:** Den vollständigen eingesetzten Einzelstack mit seinem Component-Patch im spielerseitigen Einsatzdatensatz und im Fach über einen geeigneten ItemStack-Codec sichern. Falls die Rückgabe absichtlich nur einen Standardersatz liefern soll, diese Einschränkung ausdrücklich festlegen; die derzeitige Aussage „nur eigene Items ohne Sonderdaten“ ist für ein vom Spieler eingesetztes Siegel zu weitgehend.

## Speicher- und Recovery-Matrix

Annahmen: intakte Dateien; Absturz zwischen abgeschlossenen Speicheroperationen; kein manuelles Zurückspielen einzelner Backups. „P“ bezeichnet Spielerdatei einschließlich Inventar, Attachment, Fach und Quittungen, „W“ den Arius-Weltzustand. „Alt/neu“ beziehen sich jeweils auf den genannten Übergang. Ein ungespeicherter Sieg darf als solcher zurückrollen; bereits dauerhaft ausgezahlte Erstbeute darf dadurch allerdings nicht wieder zur Erstbeute werden (R5-1). Neue Item-Drops außerhalb dieses Protokolls, etwa absichtliches Wegwerfen oder normale Todesdrops, haben Vanillas eigene Speichergrenzen.

### Start und Niederlage

| Übergang | P alt / W alt | P alt / W neu | P neu / W alt | P neu / W neu |
| --- | --- | --- | --- | --- |
| Start: Siegel entnehmen, `stake = E` setzen | Altes Siegel vorhanden, kein Einsatz: keine Erstattung. | Ebenso; `dirtySlots` oder Heimdaten erzeugen keinen zusätzlichen Siegelanspruch. | Siegel fehlt, E steht im Ledger. Nach Neustart kein Kampf/Sieg/Claim: genau ein Siegel ins Fach, E löschen. | Dieselbe Recovery. Laufender Kampf wird nicht aus W rekonstruiert. |
| Online-Niederlage: E löschen, Siegel ins Fach/Inventar | Alter E wird nach Neustart genau einmal erstattet. | Ebenso; Welt-Cleanup ist kein Zustellnachweis. | Siegel und gelöschter E gemeinsam vorhanden: keine erneute Erstattung. | Ebenso. |
| Offline-Niederlage/Abbruch | Gespeicherter Einsatz bleibt bis Login erhalten. | Ebenso; keine weltseitige Ersatzbuchung erforderlich. | Spieler wird offline nicht mutiert; dieser Übergang erzeugt keinen neuen P-Stand. | Ebenso. |

Entnahme und Einsatzbuchung finden synchron im selben Serverablauf statt (`Encounters.kt:220–223`). Online-Rückgabe schreibt beide Seiten derselben Spielerbuchung (`EirholmFight.kt:949–953`). Ein Speicherstand mit entnommenem Siegel ohne Einsatz kann durch diese normalen synchronen Abschnitte nicht entstehen. Sonderdaten des Siegels bleiben separat R5-3.

### Sieg und erstmalige Zustellung

Für die Siegzeile ist der vorher gespeicherte Startstand P = „Siegel entnommen, Einsatz E“ die Ausgangslage. Der Sieg selbst ändert P jetzt nicht mehr.

| Übergang | P alt / W alt | P alt / W neu | P neu / W alt | P neu / W neu |
| --- | --- | --- | --- | --- |
| Sieg vor Zustellung | E vorhanden, kein Sieg/Claim: Sieg rollt zurück, Siegel wird erstattet. | E vorhanden, Sieg und Claim gespeichert: Claim zustellen und E zusammen mit Quittung/Fach löschen. | Der Sieg allein erzeugt keinen neuen Ledger-Stand; wie links. | Wie P alt / W neu. |
| Zustellung eines schon vorab gespeicherten Claims C | C bleibt in W, alte P-Quittung fehlt: erneut zustellen. | C darf in dieser Sitzung noch nicht gelöscht sein; ebenfalls erneut zustellen. | Beute/Fach, C-Quittung und ggf. E-Löschung vorhanden: keine zweite Auszahlung, C darf nach Login entfernt werden. | Ebenso. |
| Zustellung eines erst seit dem letzten W-Snapshot erzeugten Claims C | Keine gespeicherte Zustellung und kein gespeicherter Sieg: Rollback; E ggf. zurück. | Gespeicherter Claim, keine gespeicherte Auszahlung: zustellen. | Auszahlung dauerhaft, Sieg und `wins` zurückgerollt: C wird nicht erneut ausgezahlt, aber **R5-1** beim nächsten Sieg. | Quittung verhindert Wiederholung; Sieg-/Erstgewinn-Status ebenfalls vorhanden. |

Wichtig: „W neu“ bei Sieg umfasst `markWon`, Claimanlage und `wins++` aus einem gemeinsamen Welt-Snapshot. `markDirty()` schreibt nicht mitten im synchronen `victory()` auf Platte. Ein regulärer Snapshot „Sieg gespeichert, aber Claims desselben Aufrufs noch nicht angelegt“ wurde nicht belegt.

Ein noch älteres P von **vor dem Start**, kombiniert mit W **nach dem Sieg**, hätte bereits das Originalsiegel und bekäme trotzdem Beute: Der Claim verlangt keinen spielerseitigen Entnahmenachweis (Gruppenmitglieder haben legitimerweise keinen). Dieser abstrakte Zustand ist daher nicht durch das Protokoll selbst abgesichert. Unter dem hier geprüften normalen Vanilla-Speicherablauf ist aber kein konkreter zusätzlicher Pfad dafür belegt: `saveAll()` speichert zuerst die Spieler, danach die Welten; ein vorheriger Logout des Beschwörers speichert bereits seinen Einsatz. Ohne weiteren unabhängigen Welt-Speicheraufruf, Schreibfehler oder selektive Wiederherstellung wird dieser hypothetische Zustand deshalb nicht als weiterer hoher Befund ausgegeben. R5-1 benötigt diese Annahme ausdrücklich nicht.

### Quittungsbereinigung, Fach und Logout

- **Claimbereinigung:** Eine in der aktuellen Sitzung erzeugte Quittung steht in `sessionPaid`. Selbst nach Autosave bleibt der Claim deshalb in W. Damit kann W nicht die letzte Anspruchskopie löschen, während die Auszahlung noch ausschließlich im RAM liegt. Erst eine aus der geladenen Spielerdatei stammende Quittung erlaubt das Entfernen. Ob diese Entfernung anschließend gespeichert wird oder nicht, ist unkritisch: C fehlt oder wird beim nächsten Login anhand derselben dauerhaft erhaltenen Quittung wieder entfernt. R4-4 ist geschlossen, weil `paid` nicht mehr gekürzt wird.
- **Fach → Inventar in Survival:** P alt enthält die alte Fachmenge und den alten Inventarstand; P neu enthält den entsprechend verminderten Fachbestand und die eingefügte Menge. W ist für beide Varianten unerheblich. Teil-Einfügen nutzt die tatsächliche Verringerung des Eingabestacks, nicht den Boolean. Beispiel: vorher 64, nach Einfügen Rest 61 → nur 3 vom Fach abziehen; beim nächsten erfolglosen Versuch bleiben 61 erhalten. Kein belegter normaler Survival-Doppel-/Verlustpfad.
- **Stapelgrößen:** `minOf(entry.count, item.maxCount)` teilt große Fachmengen in gültige Standardstapel. Bei Solvane, Echo und Siegel entstehen Einzelstapel, Materialien werden in normale Stapel geteilt. Der nächste Einfügeversuch ohne Fortschritt beendet die jeweilige Eintragsschleife, sodass ein volles Inventar keine Endlosschleife erzeugt. Der Kreativ-Sonderfall ist R5-2.
- **Standardkomponenten der Beute:** `GearRewards.loot()` erzeugt derzeit neue Standardstacks, ohne nachträglich stackeigene Komponenten zu setzen. `ItemStack(item, count)` rekonstruiert die Item-Defaults; beispielsweise stammt `ECHO_STAGE = 1` aus der Itemregistrierung (`GearItems.kt:98–99`), Solvanes Eigenschaften aus dessen Settings (`:86–90`). Diese Defaults gehen durch ID/Anzahl nicht verloren. Ein bereits verändertes Einsatzsiegel ist dagegen kein Standardstack, siehe R5-3.
- **Logout vor/nach Zustellung:** Vor der Zustellung bleibt E oder der Welt-Claim als Nachweis. Nach der Zustellung speichert Logout Quittung/Fach/Inventar und Einsatzlöschung gemeinsam. W alt/neuer Claim erzeugt keinen Replay derselben ID. W vor dem Sieg erzeugt jedoch R5-1. Rejoin in derselben Serverinstanz lädt die gespeicherte Spielerdatei erneut; die vorher nicht serialisierte Sitzungsmenge gilt dann zurecht nicht mehr als ungespeicherte Zustellung.
- **Regulärer Stopp:** `SERVER_STOPPING` beendet Kämpfe. Vor dem Sieg wird online ins Fach erstattet, offline bleibt E stehen. Nach dem Sieg ist `sealStake` leer; `finish(false)` erstattet deshalb kein zusätzliches Siegel. Welt-Claims bleiben bestehen und werden bei späterem Login zugestellt. Der frühere Verlust durch verzögerten Sieg-Task entsteht dadurch nicht wieder.
- **Tod/Respawn:** Die zuvor geprüfte Fabric-`copyOnDeath`-Übertragung des Ledgers einschließlich der transienten Sitzungsmenge ist weiterhin relevant und wurde durch den Fix nicht verändert. Der neue Fachinhalt liegt im selben übertragenen Objekt. Kein neuer Codec-Roundtrip bei diesem Übergang im Addon. Ein Tod macht eine in dieser Sitzung erstellte Quittung daher nicht fälschlich zu einer aus der Datei geladenen Quittung.

## Status aller bisherigen Befunde

| Befund | Status nach Runde 5 |
| --- | --- |
| R1-1 – Task-Iterator/CME | Geschlossen; Snapshot-Iteration und Abbruch nach `ended` bestehen fort (`EirholmFight.kt:428–433`). |
| R1-2 – Sequenzen nach K. o./Ende | Geschlossen; `canSequence()` und Prüfungen nach `hurtHeld()` bestehen fort. |
| R1-3 – Arenaabbau/-platzierung/Folgearena | Geschlossen für den beschriebenen Survival-Pfad; Sperren und Planprüfung bestehen fort. |
| R1-4 – Sieg ohne Siegelverbrauch | Ursprünglicher Weitergabe-Exploit und konkret belegte R4-Einsatzpfade geschlossen. Keine neue hohe Einsatz-Regression belegt; Komponentenverlust R5-3 separat. |
| R1-5 – Beute beim Cleanup gelöscht | Geschlossen; Zustellung außerhalb der Arena, Fach statt neuer Beutedrops. |
| R1-6 – Dialog-Long-Überlauf | Geschlossen. |
| R1-7 – Hold länger als 40 Ticks | Geschlossen; absolute Deadline besteht fort. |
| R1-8 – Fremder perfekter Block verhindert Schaden | Geschlossen; getrennte Schadensanwendung besteht fort. |
| R1-9 – Nachzügler-Rückweg | Ursprünglicher Befund geschlossen; Spawn-Fallback besteht fort. |
| R2-1 – Beuteverlust bei Disconnect/Stopp nach Sieg | Ursprünglicher Verlustpfad und R4-Drop-Verlust geschlossen. Erstgewinn-Konsistenz bleibt als neuer R5-1 offen. |
| R2-2 – Einsatzverlust beim Absturz | Konkret gemeldete Verlustpfade geschlossen; Entnahme, Rückgabe und Zustellabschluss jetzt spielerseitig nachvollziehbar. |
| R2-3 – Netherdach-Rückweg | Geschlossen. |
| R2-4 – Musik nach Weltwechsel/Rejoin | Geschlossen; R3-3-Fix besteht fort. |
| R2-5 – HHA-Abilities während Hold | Geschlossen gemäß festgelegter Regel; Gate vor neuem Cast besteht fort. |
| R3-1 – Replay ausgezahlter Beute/Siegel | Replay derselben stabilen Claim-ID und normale Rückgabe geschlossen. Erneute Erstbeute unter neuer Claim-ID ist der separate R5-1. |
| R3-2 – Einsatz ohne dauerhaften Nachweis | Geschlossen für alle konkret gemeldeten Teilpfade; kein neuer hoher Verlustpfad belegt. |
| R3-3 – Musik-Stopp findet Kampf nicht | Geschlossen; Suche über Herkunftswelt und Teilnehmer bleibt bestehen (`Encounters.kt:79`). |
| R4-1 – Rückgabe bei vollem Inventar | Geschlossen für Survival: Fach und Einsatzlöschung gemeinsam in P. Kreativabweichung R5-2 separat. |
| R4-2 – Quittierte Welt-Drops | Geschlossen im Kampf-/Recovery-Protokoll: alle aktiven Zustellstellen verwenden `deposit()`; kein Aufruf der alten `GearRewards.grant/returnSeal/consumeSeal` aus diesen Pfaden. |
| R4-3 – Einsatz vor Siegzustellung gelöscht | Geschlossen: beim Sieg kein Ledger-Löschen mehr; Löschung beim passenden Claim in P. |
| R4-4 – 300er-Quittungslimit | Geschlossen: keine Kürzung mehr. |
| R4-5 – 500er-Sieglimit | Geschlossen: keine Kürzung mehr. |
| R4-6 – R3-Altdatenmigration | Nicht anwendbar gemäß Auftrag: keine veröffentlichten/produktiven Zwischenstände. Kein weiterer Verlust aktueller selbst erzeugter Daten durch genau diese entfernten Alt-Felder belegt. |
| R5-1 – Erstgewinn-Rollback | **Hoch offen.** |
| R5-2 – Kreativ-Verwerfen | **Mittel offen.** |
| R5-3 – Siegelkomponenten | **Niedrig offen.** |

## Übriger Code und Prüfgrenzen

Gezielt nachverfolgt wurden erneut die Task-Schleife samt Ende, Sieg-/Trainingsaufrufe und Adminbefehle, Sequenzfortsetzung nach Schaden, Teilnehmer-/Heimtransport, Hold-Grenze und HHA-Ability-Gate sowie die geänderten GearRewards-Aufrufstellen. Die früheren kritischen/hohen Fixes sind in diesen Stellen weiterhin vorhanden. Kein weiterer konkreter kritischer/hoher Fehler außerhalb des Persistenzfokus belegt. Dies ist keine vollständige Wiederholung der unveränderten Ressourcen-/Modell- und sämtlichen Arena-/Abilityprüfungen aus Runde 1/2.

Der lokale Bytecode bestätigt `saveAllPlayerData()` vor `save()` in `MinecraftServer.saveAll()` und den beschriebenen Kreativ-Verwerfungszweig. Die gemeinsame Attachment-/Inventarspeicherung und Fabric-Übertragung beim Respawn waren in Runde 4 anhand der konkreten Fabric-Implementierung geprüft worden; der aktuelle Fix ändert die Registrierung und Übertragung nicht. Die Aussage gemeinsamer Spieler-Snapshots ist keine Zusage gegen Dateikorruption, selektiv restaurierte Backups oder absichtliche nachträgliche Item-Drops.

Für die Laufzeitprüfung des Nutzers sind besonders relevant: Erstbeute zustellen → Logout → Weltdatei noch vor dem Sieg → Neustart → zweiter Sieg; teilweise freier Materialstapel; volle Inventare mit mehreren Einzelstapeln; Kreativwechsel bei befülltem Fach; benanntes Einsatzsiegel bei Online-Niederlage und bei Offline-/Crash-Recovery. Diese Szenarien wurden hier nicht im Spiel ausgeführt.

**Bin ich jetzt „confident“, dass statisch nichts Kritisches/Hohes mehr offen ist? Nein.** Die bisherigen hohen R4-Befunde sind konkret behoben, aber R5-1 bleibt als hoher Fehler der Erstbeute-Entscheidung offen. Diese Einschätzung beruht auf einem konkreten Speicherablauf und nicht allein auf fehlenden Laufzeittests.
