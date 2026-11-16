# Review Runde 4 – HHA Arius

Stand: 16.11.2026, HEAD `fdb589688b171e7ece93449d0e65366a9311e390`. Geprüft: Berichte Runde 1–3, `git show -w fdb5896`, aktuelle Einsatz-/Sieg-/Zustellpfade, Attachment-Implementierung der tatsächlich verwendeten Fabric-Abhängigkeit und ergänzend die bisherigen kritischen/hohen Kampfpfade. Pfade und Zeilen beziehen sich auf diesen HEAD und das Addon.

**Ergebnis: vier hohe und zwei mittlere Befunde. Kein neuer kritischer Fehler belegt. Statische Freigabe: nein.** Die gemeinsame Speicherung von Inventar und Ledger behebt wichtige Teilfälle, genügt aber noch nicht für die behauptete verlust- und doppelfreie Zustellung über alle Speichergrenzen.

Nur dieser Bericht wurde neu geschrieben. Kein Produktivcode geändert, HHA unter `C:\Users\henny\Desktop\HHA` nur gelesen, kein Build ausgeführt und Minecraft nicht gestartet. Die unten erwähnte Modellrechnung führt ausschließlich eine kleine Nachbildung der Listenlogik im Arbeitsspeicher aus, keine Minecraft-Klassen und keine Welt-/Spielerdateien.

## R4-1 – [hoch] Rückgabe bei vollem Inventar verschiebt den Einsatz wieder ungesichert in die Weltdatei

**Datei:Zeile:** `src/main/kotlin/dev/henny/hha_arius/boss/EirholmFight.kt:955–957`; ergänzend `boss/AriusState.kt:68` und `boss/Encounters.kt:146–149`.

**Szenario:** Der Spielerzustand aus einem laufenden Kampf ist gespeichert: Siegel entnommen, `ledger.stake = E`. Vor der Niederlage füllt der Beschwörer den frei gewordenen Inventarplatz. `finish(false)` löscht seinen Einsatzvermerk, `insertStack()` scheitert und erzeugt stattdessen einen neuen `seal`-Claim ausschließlich im Weltzustand. Der Beschwörer loggt aus, bevor dieser Weltzustand gespeichert wurde; danach stürzt der Prozess ab. Auf Platte enthält seine Spielerdatei weder Siegel noch Einsatzvermerk, und der alte Weltzustand enthält den neuen Claim nicht. Recovery hat keinen Anhaltspunkt mehr: das Siegel ist dauerhaft verloren.

Das ist derselbe relevante Speicherablauf wie bei R3-2, jetzt auf dem Rückweg. Bei freiem Inventar sind Rückgabe und Vermerklöschung dagegen tatsächlich gemeinsam in der Spielerdatei.

**Vorschlag:** Bei fehlendem Platz den Einsatzvermerk als weiterhin offenen Rückgabeanspruch im Spieler-Ledger behalten. Erst bei tatsächlicher Inventarzustellung gemeinsam mit dem Inventar erledigen. Alternativ eine spielerseitig persistente Warteschlange verwenden; den einzigen Nachweis nicht gegen einen noch ungespeicherten Welt-Claim austauschen.

## R4-2 – [hoch] Quittierte Drops liegen außerhalb der gemeinsamen Inventar-/Ledger-Speicherung

**Datei:Zeile:** `src/main/kotlin/dev/henny/hha_arius/boss/Encounters.kt:148–149`, `:162–165`; `src/main/kotlin/dev/henny/hha_arius/gear/Gear.kt:103`.

**Szenario:** Ein bereits auf Platte vorhandener Erstbeute-Claim wird bei vollem Inventar ausgezahlt. `ledger.paid` und `sessionPaid` werden gesetzt; `GearRewards.grant()` lässt beispielsweise Solvane und Echo über `giveOrDropStack()` fallen. Der Spieler loggt aus, ohne die Items aufzunehmen. Seine Quittung ist jetzt gespeichert, die neuen ItemEntities wurden noch nicht gespeichert. Ein Absturz vor deren Weltspeicherung verliert die Drops. Beim nächsten Login verhindert `ledger.paid` eine Ersatzzustellung; der alte Claim wird sogar entfernt, weil die Quittung nun aus einer früheren Sitzung stammt.

Dasselbe betrifft `seal`-Claims sowie den verwaisten Einsatz: Dort wird `ledger.stake` vor `giveOrDropStack()` gelöscht. Bei vollem Inventar kann somit auch die Recovery selbst den einzigen dauerhaft nachvollziehbaren Anspruch verlieren. Bei teilweise freiem Inventar kann nur ein Teil der Beute verloren gehen, während der gesamte Claim quittiert ist.

**Verifikation:** Der Bytecode von `ServerPlayerEntity.giveOrDropStack()` in der lokalen 1.21.11/Yarn-build.6-Jar ruft bei erfolglosem `inventory.insertStack()` unmittelbar `dropItem()` auf. Das ist kein zweiter Inventar-Speicherpfad. `PlayerManager.remove()` speichert den Spieler, ohne damit die neu erzeugten ItemEntities gemeinsam zu speichern.

**Vorschlag:** Nicht zustellbare konkrete ItemStacks im Spieler-Ledger aufbewahren und erst nach erfolgreichem Insert quittieren; Teilzustellungen ausdrücklich abbilden. Für Drops wäre ein eigener, belastbarer Transaktions-/Recovery-Mechanismus nötig. Eine Quittung in der Spielerdatei kann die Persistenz eines Welt-Drops nicht beweisen.

## R4-3 – [hoch] Sieg löscht den letzten spielerseitigen Einsatznachweis vor dauerhafter Sicherung des Beuteanspruchs

**Datei:Zeile:** `src/main/kotlin/dev/henny/hha_arius/boss/EirholmFight.kt:893–905`, `:910–916`; `src/main/kotlin/dev/henny/hha_arius/boss/AriusState.kt:68`.

**Szenario:** Während des Kampfs wurden Inventarentnahme und `stake = E` korrekt gespeichert. Beim Sieg werden `markWon(E)` und die Beuteansprüche nur dirty markiert; gleichzeitig löscht der Code beim online befindlichen Beschwörer sofort `ledger.stake`. Der Beschwörer loggt innerhalb der folgenden 400 Ticks aus, noch vor dem nächsten Weltspeichern und vor der Auszahlung. Die Spielerdatei enthält jetzt kein Siegel und keinen Einsatzvermerk. Danach Absturz. Der ältere Weltzustand enthält weder den Sieg noch die Claims. Beim nächsten Login wird weder das Siegel erstattet noch Beute ausgezahlt. Im Gegensatz zu einem vollständigen Zurückrollen des ungespeicherten Siegs bleibt hier der Einsatz unwiderruflich verbraucht.

Ein Neustart muss hierfür weder eine beschädigte Datei noch einen Absturz mitten in einer einzelnen NBT-Schreiboperation voraussetzen. Logout und die ausdrücklich getrennte Speicherung genügen. Auch `MinecraftServer.saveAll()` speichert laut lokalem Bytecode zuerst die Spieler und anschließend die Welten; ein gemeinsamer Tick schafft keine gemeinsame Transaktion.

**Vorschlag:** Den Einsatznachweis bis zu einem dauerhaft nachvollziehbaren Ausgang behalten. Ein offener Einsatz darf bei zurückgerolltem Sieg wieder erstattet werden. Den endgültigen Verbrauch an einen stabilen, wiederaufnehmbaren Sieg-/Beutevorgang binden, etwa an einen spielerseitig gespeicherten Anspruch beziehungsweise eine quittierte Zustellung mit Begegnungs-ID. Nur die Löschung hinter `markWon()` zu verschieben genügt nicht, solange dieses lediglich `markDirty()` ausführt.

## R4-4 – [hoch] Das 300er-Limit entfernt noch benötigte Quittungen und ermöglicht wiederholte Massenauszahlung

**Datei:Zeile:** `src/main/kotlin/dev/henny/hha_arius/boss/Encounters.kt:155–165`; `src/main/kotlin/dev/henny/hha_arius/boss/Ledger.kt:20–21`.

**Szenario:** Ein Spieler bleibt über mehr als 300 belohnte Kämpfe in derselben Verbindungssitzung online. Auch zwischenzeitliche automatische Speicherungen ändern `sessionPaid` nicht. Bereits ausgezahlte Claims werden in dieser Sitzung absichtlich nicht aus `pendingRewards` entfernt. Bei Auszahlung Nummer 301 entfernt das FIFO-Limit aber die Quittung von Claim 1 aus `ledger.paid`, obwohl Claim 1 weiterhin in der Anspruchsliste steht.

Beim nächsten `deliverPending()` fehlt Claim 1 in `paid`: Er wird erneut bezahlt. Dass er weiterhin in `sessionPaid` steht, wird im Auszahlungspfad nicht geprüft. Das erneute Anhängen verdrängt die nächste Quittung. Bei 301 aufeinanderfolgenden Claims kann so die ganze Liste erneut ausgezahlt werden, einschließlich Erstbeute. Ein weiterer Aufruf wiederholt dies; ein Absturz ist nicht nötig. Der lange Vorlauf ist eine Voraussetzung, keine Absicherung durch das Protokoll. Ein dauerhaft laufender Server und eine lange Spielersitzung sind ausreichend.

**Zusätzliche Prüfung:** Eine reine Modellrechnung der vorhandenen Schleife mit 301 Claims ergab beim ersten Aufruf 301 Auszahlungen und 301 verbliebene Claims, beim unmittelbar zweiten Aufruf erneut 301 Auszahlungen und 301 verbliebene Claims. Dies ist eine Nachbildung der Listenlogik, kein Ingame-Test.

**Vorschlag:** Quittungen nicht aufgrund eines Mengenlimits löschen, solange Ansprüche mit diesen IDs noch wiederauftauchen können. Eine belastbare Bereinigung braucht eine bestätigte dauerhafte Entfernung auf der Anspruchsseite. Als zusätzliche Sitzungssicherung auch `sessionPaid` vor erneuter Auszahlung berücksichtigen; das allein löst den dauerhaften Quittungsabbau über Neustarts nicht.

## R4-5 – [mittel] Das 500er-Limit kann einen gewonnenen Offline-Einsatz nachträglich erstatten

**Datei:Zeile:** `src/main/kotlin/dev/henny/hha_arius/boss/AriusState.kt:76–79`; `src/main/kotlin/dev/henny/hha_arius/boss/Encounters.kt:146–149`; `src/main/kotlin/dev/henny/hha_arius/boss/EirholmFight.kt:893–896`.

**Szenario:** Der Beschwörer ist beim Gruppensieg offline. Seine gespeicherte Spielerdatei behält `stake = E`; der Weltzustand merkt sich korrekt `E` als gewonnen und den Beuteanspruch. Vor seinem nächsten Login gewinnen andere Spieler mehr als 500 weitere Begegnungen mit Einsatz. `markWon()` entfernt E aus der Liste. Beim späteren Login hält die Recovery E für eine abgebrochene Begegnung und erstattet das Siegel zusätzlich zur weiterhin vorhandenen Siegbeute. Dieser Pfad funktioniert auch bei vollständig erfolgreichen Speicherungen.

**Schweregrad:** Mittel wegen der langen Offline-/Serverhistorie und der einmaligen unberechtigten Rückgabe pro betroffenem Einsatz; er widerlegt dennoch die allgemeine Verbrauchsgarantie.

**Vorschlag:** Siegergebnisse behalten, solange noch ein Offline-Ledger darauf verweisen kann. Bereinigung an die dauerhaft bestätigte Erledigung aller betroffenen Einsätze koppeln, nicht an die Zahl jüngerer Siege.

## R4-6 – [mittel, bei Übernahme vorhandener R3-Daten] Alte offene Einsätze und Siegelansprüche werden nicht migriert

**Datei:Zeile:** `src/main/kotlin/dev/henny/hha_arius/boss/AriusState.kt:28–57`, `:85–87`; `src/main/kotlin/dev/henny/hha_arius/boss/Encounters.kt:144–165`.

**Szenario:** Eine mit Runde 3 betriebene Welt enthält `data.stakes` oder `PlayerData.pendingSeals`. Der neue Gson-Zieltyp besitzt diese Felder nicht mehr; sie werden ignoriert. Ein Spieler mit altem entnommenem Einsatz besitzt noch kein neues Attachment mit `ledger.stake`. Weder dieser Einsatz noch ein alter `pendingSeals`-Anspruch wird zugestellt. Beim nächsten Speichern gehen die ignorierten Felder auch aus der serialisierten Datenstruktur verloren.

Alte `pendingRewards` besitzen zudem keine Claim-ID. Der parameterlose Claim-Konstruktor erzeugt beim Einlesen eine neue UUID. Solange diese ID noch nicht in der Weltdatei gespeichert wurde, kann ein erneutes Laden derselben Altdatei eine andere ID erzeugen. Eine inzwischen gespeicherte Spielerquittung erkennt diesen alten Claim dann nicht wieder. Das ist ein zusätzlicher bedingter Upgrade-Replaypfad.

**Schweregrad:** Mittel als bedingtes Migrationsrisiko. Der Bericht unterstellt nicht, dass diese Zwischenversion schon produktiv eingesetzt wurde. Bei ausschließlich frischen Weltdaten entfällt dieser Befund.

**Vorschlag:** Vorhandene Altbestände ausdrücklich und wiederaufnehmbar migrieren; stabile IDs vergeben und die Übernahme mit den Spielerdateien abstimmen. Alte offene Einsätze ohne neue Ledger müssen gesondert behandelt werden. Falls solche Daten absichtlich nicht unterstützt werden, eine eindeutige Upgrade-Voraussetzung und einen Umgang mit offenen Beständen festlegen.

## Attachment-Prüfung

- **Registrierung/Zugriff:** `HhaArius.kt:37` ruft `Ledgers.init()` vor `Encounters.init()` auf. Das initialisiert das Kotlin-Objekt samt `TYPE`; `AttachmentRegistry.create()` registriert den Typ unmittelbar. Keine weitere Ledger-Registrierung und kein belegter Zugriff vor dieser Registrierung gefunden. Die Spieler werden erst später beim Serverlauf geladen. Der HHA-Addon-Entrypoint verwendet Ledger nicht vorab.
- **Persistenz:** Die lokalen Quellen von `fabric-data-attachment-api-v1 1.8.48+eed0806f3e` zeigen: `EntityMixin` schreibt persistente Attachments im Entity-NBT-Schreibpfad vor den zusätzlichen Entity-/Spielerdaten. `AttachmentSerializingImpl` serialisiert die aktuellen Attachment-Werte über deren Codec. Die direkte Mutation dieses nicht synchronisierten Spieler-Attachments benötigt für die hier geprüfte Spielerdatei keinen zusätzlichen Welt-`markDirty()`-Aufruf. Inventar und Ledger werden in demselben Spieler-Snapshot erfasst. Diese Aussage umfasst keine Drops und keine Welt-Claims.
- **Codec:** `Codec.STRING.xmap` plus Gson ist als symmetrischer persistenter String-Codec zulässig. Der vorhandene kompilierte `Ledger` hat laut `javap` einen öffentlichen parameterlosen Konstruktor; `sessionPaid` ist tatsächlich `transient`. Ein neuer/deserialisierter Ledger beginnt daher mit leerem Sitzungsset. `fix()` repariert fehlende Felder. Kein konkreter hoher Ladefehler für reguläre, von diesem Codec erzeugte Daten belegt. Das stille Zurückfallen auf ein leeres Ledger bei beschädigtem JSON ist keine Recovery-Garantie für beschädigte Dateien.
- **copyOnDeath:** Fabrics `AttachmentEntrypoint` überträgt beim `AFTER_RESPAWN` die entsprechend markierten Attachments. `AttachmentTargetImpl.transfer()` übernimmt hier dieselbe Ledger-Instanz; es findet kein Codec-Roundtrip statt. Dadurch bleibt auch `sessionPaid` bei einem Tod in derselben Sitzung erhalten. Es wird nicht fälschlich als vorher gespeicherte Quittung gewertet. Bei Spieler-Dimensionswechsel wird laut Fabric keine neue Spielerinstanz erzeugt. Kein zusätzlicher hoher Fehler aus diesem Mechanismus belegt.
- **Sitzungsquittungen ohne Größenlimit/Drops:** Für einen bereits dauerhaft vorhandenen Claim mit stabiler ID und vollständig ins Inventar gegebener Beute ist der Ansatz plausibel: alter Spieler-Snapshot → erneut zustellen; neuer Snapshot → Quittung verhindert Wiederholung; erst nach Neuladen entfernen. Dieser Teil schließt den ursprünglichen einfachen R3-1-Replaypfad. R4-1 bis R4-4 betreffen andere Übergänge bzw. verletzte Voraussetzungen.

Die allgemeine Bedeutung von `persistent` und `copyOnDeath` entspricht auch der [offiziellen Fabric-Dokumentation für 1.21.11](https://github.com/FabricMC/fabric-docs/blob/main/versions/1.21.11/develop/data-attachments.md). Für die konkreten Aussagen zur Implementierung waren die lokal vorhandenen versionierten Fabric-Quellen maßgeblich.

## Status der bisherigen Befunde

| Befund | Status nach Runde 4 |
| --- | --- |
| R1-1 – Task-Iterator/CME | **Geschlossen.** Snapshot-Iteration und Abbruch nach `ended` bestehen fort (`EirholmFight.kt:427–433`). |
| R1-2 – Sequenzen nach K. o./Ende | **Geschlossen.** `canSequence()`, `hurtHeld()` und Prüfungen nach Sequenzschaden bestehen fort. |
| R1-3 – Arenaabbau/-platzierung/Folgearena | **Geschlossen für den beschriebenen Survival-Pfad.** Sperren und Planprüfung bestehen fort; kein neuer hoher Pfad belegt. |
| R1-4 – Sieg ohne Siegelverbrauch | **Ursprünglicher Weitergabe-Exploit geschlossen; Persistenzverwaltung weiterhin offen**, insbesondere R4-1/R4-3 und mittel R4-5. |
| R1-5 – Beute wird beim Arena-Cleanup gelöscht | **Ursprünglicher Cleanup-Pfad geschlossen.** Crash-Verlust bei Drops separat R4-2. |
| R1-6 – Dialog-Long-Überlauf | **Geschlossen**, unverändert behoben. |
| R1-7 – Hold länger als 40 Ticks | **Geschlossen**, absolute Deadline besteht fort. |
| R1-8 – Fremder perfekter Block verhindert Schaden | **Geschlossen**, Schaden an `lost` weiterhin getrennt. |
| R1-9 – Nachzügler ohne passenden Rückweg | **Ursprünglicher Befund geschlossen**, bestehender Spawn-Fallback; keine neue hohe Regression belegt. |
| R2-1 – Beuteverlust bei Disconnect/Stopp nach Sieg | **Normale Abläufe geschlossen; Crash-Verlust weiterhin offen**, siehe R4-2/R4-3. |
| R2-2 – Einsatzverlust beim Absturz | **Teilweise geschlossen.** Entnahme plus Ledger gemeinsam gespeichert; Rückgabe bei vollem Inventar und Siegübergang bleiben hoch offen, R4-1/R4-3. |
| R2-3 – Netherdach-Rückweg | **Geschlossen**, keine entsprechende Regression. |
| R2-4 – Musik nach Weltwechsel/Rejoin | **Geschlossen für die gemeldeten Pfade**, siehe R3-3. |
| R2-5 – HHA-Abilities während Hold | **Geschlossen gemäß festgelegter Regel.** HHA-Gate weiterhin vor `ability.cast()`. |
| R3-1 – Replay ausgezahlter Beute/Siegel | **Teilweise geschlossen, weiterhin hoch offen.** Stabile Claims plus Inventarquittung funktionieren unter den genannten Voraussetzungen; R4-4 ermöglicht erneut Replay, R4-2 verliert quittierte Drops. |
| R3-2 – Einsatz ohne dauerhaften Nachweis | **Entnahmepfad geschlossen, verwandte Verlustpfade hoch offen.** Die Entnahme ist jetzt gemeinsam erfasst; R4-1/R4-3 löschen den Nachweis später zu früh. |
| R3-3 – Musik-Stopp findet verlassenen Kampf nicht | **Geschlossen.** `Encounters.kt:78` sucht über `world === origin`, Teilnahme und aktiven Kampf statt über bereits veränderte Koordinaten. `onLeave()` sendet Stopp; `onJoin()` synchronisiert weiterhin. |

Damit sind R4-1 bis R4-4 **hoch offen**, R4-5 **mittel offen** und R4-6 **mittel bedingt offen**. Die alten offenen Punkte sind oben ausdrücklich auf ihre verbliebenen bzw. neuen Teilpfade abgebildet.

## Übriger Code und Prüfgrenzen

Außerhalb des Persistenzfokus wurden die bisherigen kritischen/hohen Stellen erneut gezielt gelesen: Task-Ausführung und Kampfende, Sequenzschaden/K. o., Siegaufrufe und Trainings-/Adminpfade, Teilnehmer-/Heimtransport, Arena-Aufbauschleife und Änderungsverfolgung, Relikt-/Ability-Tasks, HHA-Ability-Gate sowie Client-Paket-/Effektverwaltung. Dabei kein zusätzlicher konkreter kritischer/hoher Fehler belegt. Dies ist keine vollständige Wiederholung aller Ressourcen-/Modellprüfungen aus Runde 1/2; deren Dateien wurden vom Fix nicht geändert.

Die Bytecodeprüfung bestätigt erneut die getrennten Speichergrenzen: `PlayerManager.remove()` speichert Spieler; `MinecraftServer.saveAll()` speichert Spieler vor Welten; `giveOrDropStack()` kann Welt-Entities erzeugen. Die Attachment-Quellen bestätigen den gemeinsamen Spieler-Snapshot, keine Transaktion mit Weltzustand oder ItemEntities. Für die Laufzeittests sind insbesondere voller Inventarstand bei Niederlage, Erstbeute als Drop mit Logout/Absturz und Logout während der 20 Sekunden nach Sieg relevant. Die Quittungsgrenze lässt sich zusätzlich ohne 301 reale Kämpfe an vorbereiteten Zustandsdaten prüfen.

**Bin ich jetzt „confident“, dass statisch nichts Kritisches/Hohes mehr offen ist? Nein.** Vier konkret belegte hohe Pfade bleiben offen. Diese Einschätzung folgt aus dem Code und den Speicherabläufen, nicht allein aus den absichtlich ausstehenden Laufzeittests des Nutzers.
