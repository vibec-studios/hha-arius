# Review Runde 2 – HHA Arius

Stand: 16.11.2026, HEAD `852c4b893c2f2931cdd215b0e344ff07b0d4ff70`. Statisches Review des Fix-Diffs (ohne Whitespace) und der betroffenen Lebenszyklen sowie der zusätzlich angeforderten Bereiche. HHA unter `../HHA` ausschließlich gelesen. Kein Code geändert, Minecraft nicht gestartet; kein Build und keine Ingame-Tests ausgeführt. Zeilenangaben beziehen sich auf diesen HEAD, Pfade auf das Addon, sofern nicht anders angegeben.

Ergebnis: **3 hohe und 2 mittlere Befunde, kein neu belegter kritischer Befund. Keine Freigabe.** Zwei der neun Runde-1-Befunde sind noch nicht vollständig geschlossen; die Behebung des Beute-Cleanups hat zusätzlich einen neuen Verlustpfad eingeführt.

## R2-1 – [hoch] Verbindungsabbruch nach dem Sieg löscht den Beuteanspruch

**Beleg:** `src/main/kotlin/dev/henny/hha_arius/boss/EirholmFight.kt:878`, `:884–897`; `:104–106` (`audience()`); `src/main/kotlin/dev/henny/hha_arius/boss/Encounters.kt:91–94` (Server-Stopp).

**Szenario:** Ein Spieler besiegt Arius und erhält den Siegfortschritt. Während der anschließenden 400 Ticks verliert er die Verbindung. Erst am Ende dieser Wartezeit wird die Empfängerliste aus `audience()` gebildet. Offline-Spieler und Spieler außerhalb der Arenawelt fehlen darin. Nur diese neue Liste erhält Beute und `wins++`; ein gespeicherter Anspruch existiert nicht. Beim nächsten Login wird der Spieler gegebenenfalls heimgebracht, aber nicht belohnt. Das bereits geleerte `sealStake` wird ebenfalls nicht zurückgegeben. Auch ein regulärer Server-Stopp in diesen 20 Sekunden verwirft den Belohnungs-Task durch `finish(false)` und verliert damit die komplette noch ausstehende Beute.

Das ist eine Regression gegenüber der sofortigen Vergabe: Der alte Verlust bei vollem Inventar und Arena-Cleanup ist im normalen Online-Abschluss behoben, dafür genügt nun ein gewöhnlicher Disconnect für vollständigen Beuteverlust. Ein neuer Sieg kann weiterhin Erstbeute gewähren, weil `wins` unverändert blieb; den gewonnenen Kampf und seinen Einsatz erhält der Spieler dadurch nicht zurück.

**Vorschlag:** Beim Sieg Empfänger und konkreten Beuteanspruch einschließlich Erst-/Wiederholungssieg und Flawless-Zustand persistent festlegen. Auszahlung nach erfolgreichem Heimtransport bzw. beim nächsten Login. Auszahlung und Siegverbuchung je Anspruch genau einmal ausführen. Server-Stopp muss bereits gewonnene Kämpfe als ausstehende Auszahlung erhalten. Den Kreis der berechtigten Spieler beim Sieg fixieren, nicht erst nach 20 Sekunden neu bestimmen.

## R2-2 – [hoch] Der neue Siegeleinsatz ist nicht persistent und geht beim Absturz verloren

**Beleg:** `src/main/kotlin/dev/henny/hha_arius/boss/Encounters.kt:168–171`; `src/main/kotlin/dev/henny/hha_arius/boss/EirholmFight.kt:62`, `:262–266`, `:923–931`; `src/main/kotlin/dev/henny/hha_arius/boss/AriusState.kt:25–44`.

**Szenario:** Der Start entnimmt das echte Siegel sofort aus dem Inventar. Anschließend bleibt dessen einziger Gegenwert im Feld `EirholmFight.sealStake`. Angenommen, Inventar und Dirty-Slot wurden während eines längeren Kampfes gespeichert und der Prozess bricht danach ab: Der nächste Serverstart kennt nur den schmutzigen Arena-Slot, weder Einsatz noch Besitzer sind als offener Anspruch gespeichert. `pendingSeals` wird ausschließlich durch `finish(false)` erhöht; bei einem Prozessabsturz läuft dieser Pfad nicht. Das Siegel bleibt verloren, obwohl kein Sieg vorliegt. Dasselbe ist bereits während des Arenaaufbaus möglich.

Der ursprüngliche Weitergabe-/Offline-Verbrauchsexploit ist geschlossen. Die in Runde 1 vorgeschlagene persistente Einsatzverwaltung fehlt jedoch weiterhin. Bei normalem Abschluss ist kein einfacher Doppelrückgabeweg erkennbar: `finish()` ist gegen Wiederholung geschützt, und das Siegel hat maximale Stapelgröße 1, sodass hier kein Teil-Insert mit zusätzlichem vollständigem Ersatz entsteht.

**Vorschlag:** Einsatz mit Besitzer und eindeutiger Begegnungskennung vor dem weiteren Startablauf in `AriusState` verwalten. Beim Start verwaiste Einsätze ohne gespeicherten Sieg zurückerstatten; gewonnene Begegnungen mit dem persistenten Beuteanspruch aus R2-1 verbinden. Rückerstattung bzw. Verbrauch muss den Anspruch eindeutig erledigen. Die Abstimmung mit dem gespeicherten Spielerinventar muss auch Crash-/Save-Grenzen berücksichtigen.

## R2-3 – [hoch] Der neue Notfall-Rückweg kann Nachzügler auf dem Netherdach stranden lassen

**Beleg:** `src/main/kotlin/dev/henny/hha_arius/boss/AriusState.kt:76–81`, `:85–95`; `src/main/kotlin/dev/henny/hha_arius/boss/Encounters.kt:98–104`.

**Szenario:** Ein neuer Teilnehmer kommt durch einen fremden Teleport aus dem Nether in einen laufenden Arenaslot. `rememberFallbackHome()` verwendet in der Herkunftswelt die oberste `MOTION_BLOCKING`-Position bei X/Z 0. Im normalen Nether ist das die Oberseite der Bedrockdecke, nicht der begehbare Bereich unter ihr. Beim Heimtransport steht der Survival-Spieler auf dem Netherdach und hat je nach Ausrüstung keinen Rückweg. In anderen Herkunftswelten wird ebenfalls weder trockener tragfähiger Boden noch ein sinnvoller Ausgang geprüft; die Höhenkarte allein garantiert keinen sicheren Rückkehrort.

Alte Home-Daten werden für diesen Eintritt zwar überschrieben. Der ausdrücklich vorgesehene sichere Notfall-Rückweg ist damit aber nicht allgemein gewährleistet. Der Fall benötigt einen fremden Eintritt; der normale Siegeltransport mit vorab gespeicherter exakter Heimatposition ist hiervon nicht betroffen.

**Vorschlag:** Für unbekannte Herkunft eine ausdrücklich sichere Rückkehrstrategie verwenden, etwa einen geprüften Oberwelt-Spawn oder eine kollisions- und bodengeprüfte Stelle innerhalb des begehbaren Bereichs der Herkunftsdimension. Im Nether niemals pauschal die oberste Höhenkartenposition verwenden. Wenn der Teleportpfad kontrolliert wird, weiterhin die tatsächliche Position vor dem Eintritt erfassen.

## R2-4 – [mittel] Kampfmusik läuft nach einem Weltwechsel dauerhaft weiter

**Beleg:** `src/main/kotlin/dev/henny/hha_arius/boss/EirholmFight.kt:843–850`, `:761–764`, `:904–905`; `src/main/kotlin/dev/henny/hha_arius/client/fx/AriusMusic.kt:18–25`, `:38–59`; `src/main/kotlin/dev/henny/hha_arius/client/fx/AriusFxClient.kt:65`.

**Szenario:** Ein Teilnehmer verlässt die Arenawelt über einen externen Teleport, bleibt aber mit dem Server verbunden. Selbst wenn `onLeave()` korrekt erreicht wird, entfernt es nur Hold und Bossleisten. Es sendet keinen Musik-Stopp. `finish()` sendet diesen später ausschließlich an die aktuelle `audience()`, also nicht an den Spieler in der anderen Welt. Die Client-Musik wiederholt unbegrenzt; solange `current` gesetzt bleibt, stoppt `AriusMusic.tick()` zusätzlich die Vanilla-Musik. Der Reset hängt am Disconnect, nicht am Dimensionswechsel. Der Spieler kann somit lange nach Kampfende dieselbe Bossmusik hören. Umgekehrt synchronisiert `onJoin()` nach einem Reconnect die aktuelle Kampfmusik nicht erneut.

**Vorschlag:** Beim Verlassen explizit `MusicS2C("", fade)` an diesen Spieler senden und beim Wiedereintritt den aktuellen Musikzustand synchronisieren. Zusätzlich Client-Zustände beim Wechsel aus der Arenawelt bereinigen. Bereits ausblendende Loops bei einem vollständigen Reset ebenfalls zuverlässig stoppen.

## R2-5 – [mittel] Hold sperrt HHA-Ability-Tasten nicht; Frostschritt kann Sequenzschaden abwehren

**Beleg:** `src/main/kotlin/dev/henny/hha_arius/gear/EirholmAbilities.kt:51–68`, `:75–80`, `:145–158`; `src/main/java/dev/henny/hha_arius/mixin/client/HoldActionsMixin.java:15–20`; HHA: `src/main/kotlin/dev/henny/hha/client/HhaClient.kt:113–120`, `src/main/kotlin/dev/henny/hha/api/ability/HhaAbilities.kt:38–50`, `src/main/kotlin/dev/henny/hha/net/HhaNetworking.kt:177–188`.

**Szenario:** Ein Spieler mit vollem Eirholm-Set und ausreichender Resonanz drückt während einer Halte-Sequenz Ability 2. Der Client-Mixin verhindert Vanillas `handleInputEvents`, aber HHA verarbeitet seine eigenen Ability-Keybinds im Client-Tick und sendet weiterhin Ability-Pakete. Serverseitig prüfen weder `ready()` noch ein registriertes Addon-`ABILITY_CAST`-Gate den Hold. Frostschritt setzt deshalb seine Schadenssperre und startet Bewegungs-Tasks trotz Hold. Seine `ALLOW_DAMAGE`-Prüfung behandelt auch einen direkten Arius-Sequenztreffer als Nahkampf; `Hold.hitting` hebt nur die eigene Hold-Schadenssperre auf, nicht diese zusätzliche Sperre. Bei passendem Timing kann der gehaltene Spieler dadurch den vorgesehenen Treffer abwehren. Bei Freeze wird seine Bewegung anschließend wieder zum Anker korrigiert – sichtbar widersprüchliches Verhalten statt konsistenter Eingabesperre.

**Vorschlag:** Den bestehenden HHA-`ABILITY_CAST`-Hook verwenden, um aktive Fähigkeiten während Hold serverseitig zu sperren; die Prüfung ebenfalls bei verzögerten Releases/Bewegungsschritten berücksichtigen. Festlegen, ob bereits vorher aktivierter Frostschritt Sequenzschaden abwehren darf, und diese Regel ausdrücklich umsetzen. HHA selbst braucht dafür nicht geändert zu werden.

## Status der neun Runde-1-Befunde

| Nr. | Status | Begründung |
| --- | --- | --- |
| 1 – Task-Iterator/CME | **geschlossen** | `EirholmFight.kt:422–431`: stabile Momentaufnahme; neue Tasks verändern den iterierten Snapshot nicht. Nach `ended` wird abgebrochen. `finish()` im laufenden Task und `addRidge()` → neuer Task verursachen damit keine Iteratorinvalidierung mehr. |
| 2 – Sequenzen nach K. o./Kampfende | **geschlossen** | `AriusMoves.kt:448–454`, `:470–492`, `:531–537`; `EirholmFight.kt:656–664`: nach Schaden erneut Hold und Teilnahme/Lebendigkeit prüfen; `hurtHeld()` lehnt K. o. und beendete Begegnungen ab. Training löst Hold beim verhinderten Tod ebenfalls auf. R2-5 betrifft einen anderen Eingabepfad. |
| 3 – Abbau/Platzierung und kaputte Folgearena | **geschlossen** | `Encounters.kt:80–90`, `:163–166`: normale Survival-Abbau-, BlockItem-Platzier- und Eimerpfade gesperrt; jeder Versuch prüft die Planblöcke, Dirty-Slots werden geleert. Modus 1 setzt absichtlich keine Plan-Luft: Er ist kein vollständiger Volumenreset für beliebige Fremdmod-/Adminänderungen. Der ursprüngliche normale Abbau-/Goldfarm-Pfad ist geschlossen. |
| 4 – Sieg ohne Siegelverbrauch | **offen (teilweise behoben)** | Der konkrete Weitergabe-/Offline-Exploit ist durch Entnahme beim Start geschlossen; reguläre Niederlage/Abbruch erstattet online oder über `pendingSeals`. Die geforderte robuste Einsatzverwaltung ist wegen R2-2 nicht vollständig. |
| 5 – Beute wird beim Arena-Cleanup gelöscht | **geschlossen; neue Regression R2-1** | `EirholmFight.kt:886–897`: erst Cleanup und Heimtransport, dann `grant()`. Nicht einfügbare Beute fällt am Heimatort statt in der aufgeräumten Arena. Der neue Verlust bei Disconnect/Server-Stopp wird separat als hoher Befund geführt. |
| 6 – Kampfdialog-Long-Überlauf | **geschlossen** | `EirholmFight.kt:217`, `:772–776`: `-100_000L` ermöglicht den ersten Dialog bei normaler Weltzeit; der selbstverstärkende Sentinel-Überlauf ist entfernt. |
| 7 – Hold länger als 40 Ticks | **geschlossen** | `Hold.kt:79–84`, `:89–91`: absolute Deadline bleibt bei erneutem Freeze und Flight-Wechsel erhalten. Die Torsequenz kann die Gesamtdauer damit nicht mehr auf 54 Ticks verlängern; Release sendet die Freigabe. |
| 8 – Fremder perfekter Block verhindert eigenen Schaden | **geschlossen** | `Scenes.kt:933–936`: Schaden an `lost` wird unabhängig von `perfect` angewendet; das gemeinsame Öffnen des Konterfensters bleibt getrennt. |
| 9 – Nachzügler ohne passenden Rückweg | **offen (teilweise behoben)** | `Encounters.kt:103`: echter externer Neueintritt überschreibt alte Heimatdaten. Der neue Fallback ist aber nicht dimensionsübergreifend sicher, siehe R2-3. |

## Zusätzliche Prüfung und Grenzen

- `client/fx/*`: Paket-Dispatch, fehlende Effekt-Entities, Slice-Daten, begrenzte Effektliste, Musik und Untertitel geprüft. Fehlende `e1`/`e2` werden bei Effektanlage abgefangen. Der normale Slice-Encoder erzeugt gleich lange Positions-/State-Arrays. Kein weiterer konkreter kritischer/hoher Client-Absturzpfad belegt; keine Framerate- oder Audiowiedergabeprüfung im Spiel.
- `client/model/*`: Renderer-/RenderState-Anbindung und statische Modellreferenzen geprüft. Automatischer Datenabgleich: Arius 30 Animationsknochen/87 Posen, Nivaris 34/35; alle Posen haben exakt `COUNT * 6` Werte. Sämtliche referenzierten Bone-, Form-Hide-, Solvane- und Scheidenpfade existieren im jeweiligen ModelData-Baum. Diese Prüfung lief ohne Minecraft. Fehlende Texturdateien wurden nicht als zwangsläufiger Modellabsturz unterstellt.
- `gear/*`: Abilities, verzögerte Tasks, Resonanzgewinn/-verbrauch, Solvane-Konter, Relikte, Netzwerk und Mixins verfolgt. HHA-Ability-Dispatch und das vorhandene Cast-Gate anhand der lokalen Quellen gegengeprüft. Keine zusätzlichen C2S-Pakete im Addon, die Resonanz direkt vom Client setzen.
- `arena/*`, `Scenes.kt`, `NivarisMoves.kt`: Aufbau-/Edit-/Restore-Pfade, Schnitt, Einsturz, Abschnittswechsel, Training 1–4, Finale und Drachenangriffe statisch verfolgt. Geometrie, tatsächliche Bewegungsphysik, Telegraph-Lesbarkeit und Rendering unter Last bleiben Ingame-Prüfungen. Kein weiterer eindeutig belegter kritischer/hoher Befund aus diesen Pfaden.
- Reguläre Online-Rückgabe eines einzelnen Siegels und reguläre Online-Beutevergabe enthalten im geprüften Pfad keine belegte einfache Doppelvergabe. Das ersetzt keine Crash-/Speichertransaktionsprüfung nach einer späteren Persistenzkorrektur.

**Abschluss: Bin ich jetzt confident, dass statisch nichts Kritisches/Hohes mehr offen ist? Nein.** R2-1 bis R2-3 sind konkrete offene hohe Fehler. Diese Einschätzung beruht auf belegten Codepfaden, nicht bloß auf den absichtlich fehlenden Ingame-Tests. Nach ihrer Behebung brauchen insbesondere die Persistenz- und Zustellpfade erneut eine statische Prüfung; Laufzeittests im Spiel führt der Nutzer aus.

