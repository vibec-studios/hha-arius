# Review Runde 1 – HHA Arius

Stand: 16.11.2026. Statisches Review gegen `docs/KONZEPT.md`, `docs/SPEC_TEAM.md` und `docs/STIMME.md`; HHA unter `../HHA` nur gelesen. Kein Produktivcode geändert, Minecraft nicht gestartet. Der erfolgreiche Build ist Vorgabe des Auftraggebers und wurde nicht erneut ausgeführt.

## 1. [kritisch] Kampf-Tasks verändern ihre gerade iterierte ArrayList

**Beleg:** `src/main/kotlin/dev/henny/hha_arius/boss/EirholmFight.kt:421–422`, `:858–861`, `:877–880`, `:898`; außerdem `boss/NivarisMoves.kt:257–260` und `boss/FightWorld.kt:53`, `:79–87`.

`tickInner()` führt Tasks mit einem normalen ArrayList-Iterator aus. Der verzögerte Task nach Sieg bzw. Trainingsende ruft `finish()` auf, das `tasks.clear()` ausführt. Anschließend wird derselbe Iterator weiterverwendet: `ConcurrentModificationException`. Es gibt um diesen Tickpfad keinen Exception-Handler. Das kann den Server beim regulären Kampfabschluss zum Absturz bringen; die Heimteleports innerhalb des Tasks erfolgen zwar noch, aber der Tick läuft anschließend in die ungültige Iteration.

Ein zweiter regulärer Auslöser liegt schon im Drachenkampf: Der verzögerte Atem-Task ruft `addRidge()` auf. Ist die maximale Kammzahl erreicht und das Hazard-Budget frei, ruft dieser `breakOldestRidge()` auf, das einen neuen Task in dieselbe Liste einfügt. Bereits der nächste Iteratorzugriff scheitert, gegebenenfalls unmittelbar vor der Trefferprüfung anderer Tasks.

**Vorschlag:** Ausführung und strukturelle Änderungen trennen: aus einer stabilen Momentaufnahme ausführen, neue Tasks puffern und erledigte anschließend entfernen; nach `ended` sofort abbrechen. Abschluss, Niederlage innerhalb eines Tasks und Kammüberschreitung gezielt prüfen.

## 2. [hoch] Halte-Sequenzen laufen nach tödlichem Sequenzschaden und Kampfende weiter

**Beleg:** `src/main/kotlin/dev/henny/hha_arius/boss/AriusMoves.kt:450–455`, `:470–475`, `:488–493`, `:529–532`; `boss/EirholmFight.kt:649–651`, `:782–800`.

`hurtHeld()` kann synchron `tryKnockOut()` auslösen. Dieses löst Hold und teleportiert den Spieler zur Zuschauerposition; beim letzten aktiven Teilnehmer beendet es den Kampf und schickt alle nach Hause. Die aufrufende Sequenz prüft anschließend weder K.-o.-Status noch `ended`, Welt oder Teilnahme erneut.

Konkretes Multiplayer-Szenario: Der erste Treffer der Luftkombination macht einen Spieler k. o. Er landet am Zuschauerpunkt. Direkt danach berechnet `airCombo()` von dieser neuen Position aus einen Apex, hält ihn erneut und teleportiert ihn vier Blöcke hoch. Später kann `hurtHeld()` ihn erneut verletzen, denn auch dort fehlt die K.-o.-Prüfung. Bei Solo-Niederlage kann dieselbe Sequenz nach dem Heimtransport erneut Hold beginnen bzw. in die Arena teleportieren. Bei Parade/Tritt und Frostgriff kann ein neuer Flugzustand sogar die Heimatwelt mit Arena-Landezielen kombinieren.

**Vorschlag:** Nach jedem potenziell tödlichen Schaden sofort Sequenzgültigkeit prüfen und bei K. o., Kampfende oder Weltwechsel abbrechen. `hurtHeld()` muss k. o. gesetzte Spieler ablehnen. Hold an die konkrete Begegnung binden.

## 3. [hoch] Spieler können die Arena abbauen/verbauen; Änderungen bleiben für folgende Versuche erhalten

**Beleg:** `src/main/kotlin/dev/henny/hha_arius/boss/Encounters.kt:126–132`; `boss/EirholmFight.kt:256`, `:900`, `:910`; `boss/ArenaEdits.kt:22–28`, `:56–58`; `arena/ArenaPlan.kt:218`, `:372`, `:379`, `:454`; `arena/ArenaBuilder.kt:116` (Goldblock-Zustand).

Teilnehmer behalten ihren Spielmodus. Im Addon existiert keine Sperre für Blockabbau/-platzierung. `ArenaEdits` merkt ausschließlich eigene Änderungen, nicht normale Spieleraktionen. Nach einem normalen Abschluss wird der Dirty-Marker entfernt; solange der einzelne Prüfblock `origin.down()` noch steht, startet der nächste Versuch mit Build-Modus 0 und repariert die Arena nicht.

Spieler können somit Säulen, Wände oder Böden dauerhaft entfernen bzw. Fluchtwege mit eigenen Blöcken versperren. Außerdem sind echte Goldblöcke abbaubar. Wird zusätzlich der Prüfblock in der Plateaumitte entfernt, erzwingt der folgende Versuch einen Neubau und stellt abbaubare Ressourcen wieder her: Ressourcenfarm ohne Sieg. Das widerspricht dem zugesagten vollständigen Neubau je Versuch und gefährdet die Geometrie-/Fairnessannahmen des Kampfes.

**Vorschlag:** Arena gegen normale Spieleränderungen absichern und/oder jeden Versuch vollständig aus dem Plan rekonstruieren. Abgebaute Arenamaterialien dürfen nicht als Survival-Ressourcen ausgeführt werden können.

## 4. [hoch] Siegelverbrauch beim Sieg lässt sich umgehen

**Beleg:** `src/main/kotlin/dev/henny/hha_arius/boss/Encounters.kt:99`; `boss/EirholmFight.kt:870–876`; `gear/Gear.kt:119–123`.

Das beim Start übergebene `ItemStack` wird ignoriert. Es gibt keine Reservierung des Siegels; erst nach der Beutevergabe wird irgendein Siegel im aktuellen Inventar des Beschwörers gesucht. Fehlt es oder ist der Beschwörer offline, erfolgt kein Verbrauch, ohne dass dies die Belohnung verhindert.

Konkreter Exploit: Nach dem Start übergibt der Beschwörer das Siegel einem anderen Teilnehmer. Die Gruppe gewinnt, alle anwesenden Teilnehmer bekommen Beute, `consumeSeal()` findet beim Beschwörer nichts und liefert unbeachtet `false`. Dasselbe Siegel kann anschließend den nächsten belohnten Kampf starten. Dafür sind keine Rechte oder manipulierten Pakete nötig.

**Vorschlag:** Siegel beim Start entnehmen und persistent als Einsatz verwalten; bei Niederlage/Abbruch zurückgeben, bei Sieg verbrauchen. Offline-Fälle und volle Inventare dabei berücksichtigen.

## 5. [mittel] Nicht aufgenommene Siegbeute wird beim Rücktransport gelöscht

**Beleg:** `src/main/kotlin/dev/henny/hha_arius/gear/Gear.kt:103`; `boss/EirholmFight.kt:877–881`, `:906`.

Bei vollem Inventar fällt die persönliche Belohnung durch `giveOrDropStack()` auf den Arenaboden. Nach 400 Ticks ruft der Rücktransport `finish()` auf, das pauschal alle Nichtspieler-Entities im Bauvolumen verwirft – einschließlich dieser ItemEntities. Wer bis dahin keinen Platz geschaffen hat, verliert beispielsweise seine garantierte erste Solvane bzw. sein Echo dauerhaft. `wins` wurde bereits erhöht; der nächste Sieg gewährt nur Wiederholungsbeute.

**Vorschlag:** Nicht zustellbare Belohnungen persistent vormerken und am Heimatort oder beim nächsten Login zustellen. Alternativ Belohnungsitems vor dem Entity-Cleanup zuverlässig zuordnen und mitnehmen.

## 6. [mittel] Alle als Kampfdialog markierten Zeilen werden durch Long-Überlauf unterdrückt

**Beleg:** `src/main/kotlin/dev/henny/hha_arius/boss/EirholmFight.kt:215`, `:759–763`; `boss/Scenes.kt:294–304`.

`lastCombatLine` beginnt mit `Long.MIN_VALUE`. Bei normaler nichtnegativer Weltzeit läuft `now - lastCombatLine` in den negativen Bereich über. Dadurch ist die Bedingung `< 900` schon beim allerersten Kampfdialog wahr. `say()` kehrt zurück, bevor der Zeitstempel aktualisiert wird; der Zustand korrigiert sich nicht. Die Eirholm-/Astoria-Zeile und die Gerüchte im Drachenkampf werden so nie gesendet, ihre Fortschrittsmarker ebenfalls nicht aktualisiert.

**Vorschlag:** Den ungesetzten Sentinel gesondert behandeln oder einen überlaufsicheren Initialwert/Zeitvergleich verwenden.

## 7. [mittel] Die zugesagte harte Hold-Obergrenze von 40 Ticks gilt nicht

**Beleg:** `src/main/kotlin/dev/henny/hha_arius/boss/Hold.kt:73–84`; `boss/Scenes.kt:219–237` (Tor-Sequenz); `boss/AriusTimings.kt:29`, `:120` (`HOLD_MAX`).

`Hold.begin()` begrenzt jede einzelne Teilhaltung auf 60 Ticks statt auf `HOLD_MAX`. Jeder Wechsel ersetzt zudem den Startzeitpunkt. Beim Tritt durchs Tor hält die Freeze-Phase 14 Ticks an, danach kann die Flugphase weitere 40 Ticks dauern: insgesamt 54 Ticks ununterbrochene Eingabesperre. Der Pfad ist bei blockiertem Flug bzw. ausbleibender früher Landung erreichbar. Es gibt keinen übergeordneten 40-Tick-Wächter.

**Vorschlag:** Beginn und absolute Deadline einer gesamten Sequenz erhalten und bei Freeze-/Flight-Wechseln nicht erneuern. Den verbleibenden Zeitraum aus `AriusTimings.HOLD_MAX` berechnen.

## 8. [mittel] Ein perfekter Block macht im Multiplayer auch Fehlversuche anderer Spieler schadlos

**Beleg:** `src/main/kotlin/dev/henny/hha_arius/boss/Scenes.kt:924–937`.

Im Finale wird `lost` für tatsächlich in der Linie stehende Spieler ohne perfekten Block berechnet. Schaden an diese Spieler wird aber nur angewendet, wenn gleichzeitig `perfect.isEmpty()` ist. Stehen zwei Spieler in der Linie und einer blockt perfekt, erhält auch der zweite ungeblockt keinen Schaden; zugleich öffnet sich das Konterfenster für die Gruppe. Der Treffer des zweiten Spielers wird damit von der Eingabe des ersten aufgehoben.

**Vorschlag:** Schaden an `lost` unabhängig davon anwenden, ob andere Teilnehmer perfekt geblockt haben. Das gemeinsame Öffnen des Konterfensters getrennt entscheiden.

## 9. [mittel] Für echte Nachzügler wird kein aktueller Rückkehrpunkt gespeichert

**Beleg:** `src/main/kotlin/dev/henny/hha_arius/boss/Encounters.kt:64–66`, `:75–79`; `boss/EirholmFight.kt:291`, `:822–829`; `boss/AriusState.kt:62–79`.

Nur die Startgruppe durchläuft `rememberHome()` in `enter()`. Ein neuer Teilnehmer, der während des laufenden Kampfes aus einer anderen Dimension in den Arenaslot gelangt, wird dagegen über `onArrive()`/`onJoin()` aufgenommen, ohne seine Herkunft zu speichern. Beim späteren Heimtransport verwendet `sendHome()` einen alten Rückkehrpunkt aus einem früheren Kampf oder den Fallback bei X/Z 0 der Oberwelt. Das ist nicht der Ort dieses Eintritts. Die Zielwelt ist im World-Change-Callback bereits gewechselt; dort nachträglich nur `rememberHome(player)` aufzurufen würde fälschlich die Arena speichern.

**Vorschlag:** Für neue Teilnehmer vor dem Eintritt Welt und Position erfassen, z. B. im eigenen Teleportpfad. Bei fremd ausgelösten Teleports einen definierten sicheren Rückweg verwenden und alte Kampf-Heimatdaten nicht ungeprüft übernehmen.

## Prüfgrenzen und Gesamteinschätzung

Alle 109 Ressourcen-JSONs wurden syntaktisch geparst. Dimension/Dimension-Type/Biom, Schadenstags, Rezepte, Fortschritte, Trim-/Atlas- und Itemdefinitionen wurden gelesen. Die geprüften Mixin-Ziele und der EquipmentRenderer-Deskriptor wurden mit `javap` an der tatsächlich lokal remappten Minecraft-1.21.11-Jar abgeglichen; dabei kein belegter fehlender Zielname/Deskriptor. Auch die Vanilla-Cooldownprüfung vor Item-`use()` wurde bestätigt; deshalb wurde das Fehlen lokaler Cooldownprüfungen in Relikt-`use()` nicht als Fehler gewertet. HHA-Telegraph-Treffersemantik und Ability-Dispatch wurden anhand der lokalen Quellen gegengeprüft. Ein JSON-Syntaxcheck ersetzt keine vollständige Registry-/Codec-Ladeprüfung.

**Freigabe: derzeit nein. Confident, dass nach Behebung nur der kritischen/hohen Befunde nichts Grobes mehr offen ist: nein.** Dafür fehlen insbesondere ausgeführte Lebenszyklusprüfungen mit mehreren Spielern (K. o. mitten in Sequenzen, Disconnect/Rejoin, Rücktransport, Serverneustart) und ein tatsächlicher Daten-/Client-Ladelauf. Diese wurden entsprechend Projektvorgabe nicht gestartet. Die Befunde oben sind aus konkreten Codepfaden belegt; der Bericht behauptet keine Ingame-Verifikation.
