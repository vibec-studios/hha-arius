# HHA Arius: Die weiße Klinge (Konzept)

> **Romanhintergrund und Mod-Adaption sind getrennt.** Abschnitte mit *(Roman)* geben nur wieder, was uns aus „The White
> Blade“ vorgegeben wurde. Alles mit *(Mod)* ist frei erfundene Minecraft-Adaption und legt nichts für die Geschichte
> fest: der Drache Nivaris, das Heiligtum von Eirholm als Arena, die Eirholm-Rüstung, die weiße Form, das weiße Loch,
> die Resonanz, das „Abbild“ von Solvane und alle Techniknamen außer *White Null*.
>
> **Herkunft, alle Zeilen, Untertitel und Szenentexte sind in [STIMME.md](STIMME.md) verbindlich**; zitierte Zeilen
> hier sind nur Beispiele.
>
> Alle Zahlen sind **Balancing-Startwerte** (Ticks: 20 = 1 s; Schaden in halben Herzen *vor* Rüstung, siehe 9.3).

---

## 0. Ist-Stand des Projekts (geprüft)

| Thema | Befund |
|---|---|
| Minecraft / Loader | 1.21.11, Fabric Loader 0.19.3, Fabric API 0.141.4, Kotlin 2.4 (FLK 1.13.12), Yarn build.6, Loom 1.17 |
| HHA-Stand | Lokal `Desktop\HHA`, Branch `new`, Version 0.8.0 **mit** Boss-Überarbeitung (nicht auf GitHub released; neuestes GitHub-Release ist 0.6.2) |
| Addon-API | `hha`/`hha_client`-Entrypoints, `HhaAddonContext` (Sets, Abilities 1–6, Lore, Config-Keys), `HhaEvents`, `HudCooldowns`, automatische Kits |
| Boss-Bausteine (öffentlich nutzbar) | `logic.boss.Telegraph` (Ansage = Trefferfläche, Mindestdauern, Budget, Gold = sicher/offen), `logic.Vfx` (3D-Effekte per Paket, Effekt-Budget, Rütteln/Blitz gedeckelt, Client-Schalter `/hhac vfx`), `logic.Fx`, `logic.Targeting`, `logic.Cooldowns` |
| Modelle | Vanilla-`ModelPart`-Modelle, in Python erzeugt (`modelkit`/`bosskit`/`texkit`, mctex schreibt PNGs), Schlüsselposen je Knochen, Zeitleisten im Kotlin-Modell |
| Audio | Synthetisiert (numpy + ffmpeg), keine fremden Samples; ElevenLabs-Stimmen nur mit Key des Nutzers (hier nicht verwendet) |
| Projektregeln | Welt/Arenen nur aus Vanilla-Blöcken; Duelle lesbar, keine Adds, höchstens eine Saal-Gefahr gleichzeitig; Effekte wenige, stark, glatt; jeder Boss eigenes Modell; Heaven-Assets gesperrt; HHA-Code nicht ändern; Nutzer testet selbst im Spiel |
| Addon-Vorbilder | `hha-blood` (Cerberus + Minotaurus, eigene Cine/Glow), `HHA_void` (Leviathan) – beide eigenständige Repos mit `libs/HHA-*.jar` |

**Lücke:** HHA 0.8.0 mit `Telegraph` gibt es nur lokal. Das Addon setzt genau diesen Stand voraus (Jar in `libs/`). Wer
das alte 0.8.0-Jar (ohne `Telegraph`) nutzt, bekommt beim Start eine klare Fehlermeldung statt eines Absturzes mitten im
Kampf.

---

## 1. Vision

**Arius kämpft nicht gegen dich – er prüft dich.** Ein ruhiger Neunzehnjähriger mit weißem Haar, einem goldenen und einem
eisblauen Auge, der jeden deiner Schritte schon gesehen hat. Am Anfang bewegt er sich kaum: ein halber Schritt aus deiner
Linie, eine Parade, ein Tritt. Je ernster er dich nimmt, desto mehr Welt verändert sich: Ein Schwerthieb teilt den
Tempel, der Gletscher bricht unter einem Drachen, und am Ende presst ein weißer Kern Luft, Schnee und Licht aus dem Herz
des Gletschers.

Danach wird es still. Kein Sturm, kein Drache, nur noch ein letzter, sauberer Austausch.

Drei Grundsätze:
1. **Macht zeigt sich an der Welt:** Jede Eskalation verändert Gelände, Sicht oder Wege. Mehr Leben und mehr Partikel
   allein reichen nicht.
2. **Jede Gefahr ist lesbar:** Jede Attacke kündigt sich auf dem Boden an (HHA-`Telegraph`). Gold heißt immer „sicher“
   oder „offen“. Danach folgt ein echtes Gegenangriffsfenster.
3. **Können schlägt Ausrüstung:** Den Kampf gewinnt man mit Vanilla-Bewegung, Schild und Bogen. Die Belohnungen machen
   stärker, sind aber keine Voraussetzung.

### 1.1 Begegnungsgrund *(Mod)*
Tief im Eis über dem Bergdorf Eirholm liegt ein Heiligtum. Darin schläft **Nivaris**, ein alter weißer Drache, der das
*Herzenserbe* hütet: eine Glut aus Frost, die in Arius und in ihr zugleich schlägt. Der **White Throne** hat Siegel
geschmiedet, mit denen man das Heiligtum öffnen kann. Wer mit einem solchen Siegel kommt, kommt für Arius vom White Throne.

Er hält dich also für eine Waffe. Der Kampf ist eine Prüfung, kein Herrschaftsanspruch. Im Finale erkennt er, dass du
keine bist, und gibt dir einen Teil dessen, was er schützt.

### 1.2 Arius als Figur
| | |
|---|---|
| *(Roman)* | 19, Mensch, ca. 185 cm; weißes, wirres Haar; ein goldenes und ein eisblaues Auge; anthrazitfarbener Reisemantel mit hohem Kragen, Goldakzente, stahlblaue Innenlage; schwarze Hose, Stiefel, verstärkte Handschuhe mit **07**; Solvane diagonal auf dem Rücken; Spell-Blade (Frost, Wind, Yang, Nahkampf) |
| *(Mod)* Stufe I | Eirholm-Rüstung (Netherite-Silhouette: Brust, Schultern, Beinschienen, Stiefel, **kein Helm**, damit Haar und Augen lesbar bleiben) **unter** dem offenen Mantel. Schwer, standfest, kontrolliert Flächen. |
| *(Mod)* Weiße Form | Die dunklen Schichten lösen sich in Frostsplitter auf. Darunter erscheint ein **weißes Gewand mit stahlblauer Schärpe und goldenen Säumen**. Er wirkt leicht, schnell und präzise. Unterscheidbarkeit: Haar = kühles Grauweiß mit dunklen Strähnenschatten; Gewand = warmes Kalkweiß mit blauen Faltenschatten und dunkler Hose/Stiefel (dunkle untere Hälfte = Silhouette vor Schnee); Energie = additiv leuchtendes Blauweiß mit goldenem Kern, nie flächig auf dem Körper. |
| Verhalten | Spricht wenig. Keine Angriffsnamen, keine Drohungen. Kurze, trockene Sätze an wenigen Stellen (insgesamt ~12 Zeilen). |

---

## 2. Ablauf der Begegnung

Zieldauer eines erfolgreichen Solo-Versuchs: **9–13 Minuten** (I ≈ 3:30, II ≈ 3:00, III ≈ 4:00, Finale ≈ 0:45).
Jeder Abschnitt bringt neue Entscheidungen, nicht nur mehr Leben.

```
Siegel ─► [I  Tempel von Eirholm] ──Der Schnitt──► Tritt durchs Tor ─► [II Gletscherschwelle]
          Arius (Rauhreif-Form)                                         Nivaris allein, Arius sieht zu
                                                                                 │
              ┌──── Nivaris stürzt in den Gletscher, der Boden bricht ◄──────────┘
              ▼
       Verwandlung + Resonanz ─► [III Herzgewölbe] ──weißes Loch──► [Finale: der letzte klare Moment]
                                  Arius (weiß) + Nivaris im Takt            drei Austausche, dann Sieg
```

### 2.1 Arena *(Mod)*: eigene Dimension `hha_arius:eirholm`
Eine leere, wetterlose Dimension mit eigenem Biom (Schneeflocken-Partikel, kalter Nebel, blassblauer Himmel, feste
Tageszeit). Jede Begegnung bekommt eine **Instanz** (Slot × 4096 Blöcke auf der X-Achse) und wird vor jedem Versuch
**komplett neu aus Vanilla-Blöcken gebaut**. Großflächige Zerstörung trifft so nie die Survival-Welt.

| Schauplatz | Aufbau | Was er am Kampf ändert |
|---|---|---|
| **I Tempel von Eirholm** | Runde Halle (R 18), 10 Eissäulen (zerbrechliche Deckung), Altarstufen im Norden, großes Tor im Süden, Kuppeldach mit Lichtschächten | Enge, Deckung gegen Fernangriffe, Säulen brechen unter Windschritt und Schnitt. Kurze Sichtlinien. |
| **II Gletscherschwelle** | Offenes Gletscherplateau (R 46) rund um den Tempel; im Süden die Gletscherwand, die der Schnitt spaltet; Arius sitzt auf der stehengebliebenen Westhälfte des Tempels | Weite, Fliegen, keine Deckung außer dem, was Nivaris selbst erschafft (Eiskämme). Die Kluft in der Gletscherwand ist eine Fluchtrinne vor Atem und Flügelstoß. |
| **III Herzgewölbe** | Kuppelhöhle 36 Blöcke unter dem Plateau (R 28), 8 Kristallsäulen, Lichtsäule aus dem Einsturzloch in der Mitte, Wände aus blauem Eis | Rundum geschlossen, Höhe für den Drachen begrenzt (sie muss oft landen), Säulen als Deckung gegen sie – aber nicht gegen den Null-Schnitt. |

Die Übergänge folgen aus dem Kampf: **Der Schnitt** spaltet den Tempel und reißt die Ostwand weg, also führt der Weg
hinaus aufs Plateau. **Nivaris' Sturz** bricht den Gletscher, also fällt man ins Herzgewölbe darunter.

### 2.2 Zugang, Vorbereitung, Wiederholung
- **Siegel von Eirholm** (herstellbar: Netherstern, 4 blaues Eis, 2 Echosplitter, 2 Goldbarren). Benutzbar nur in einem
  **kalten Biom** (Temperatur < 0,15). Benutzen löst einen kurzen, ungewöhnlichen Schneesturm aus (5 s, Schnee driftet
  *zu* dir hin, Wind verstummt). Danach werden alle Spieler im Umkreis von 12 Blöcken mitgenommen.
- **Erwartete Vorbereitung:** volle Netherite-Rüstung mit Schutz IV oder ein HHA-Set, Schild, Bogen, Goldäpfel und
  Heiltränke. Feuerresistenz bringt nichts. Ein Zugang zum Nether-Endgame (Netherstern) ist nötig.
- **Niederlage:** Das Siegel wird **nicht** verbraucht (es kehrt ins Inventar zurück). Man kann sofort neu beginnen,
  ohne Pflichtaufgaben.
- **Sieg:** Das Siegel zerbricht. Ein neues lässt sich herstellen; Wiederholungen geben reduzierte Beute (Abschnitt 8).
- **Training:** Nach dem ersten Sieg gibt es das **Echo von Eirholm**. Schleichen + Benutzen wählt den Abschnitt
  (Tempel, Gletscher, Herz, Finale), Benutzen startet ihn allein oder mit der Gruppe. Kein Loot, aber volle Mechaniken
  und **gekürzte Übergänge**.
- Admins: `/hhaarius start|stage <1-4>|stop|info`.

---

## 3. Abschnitt I: Der Tempel von Eirholm (Arius allein)

**Haltung:** Rauhreif-Form. Er kämpft schwer und ruhig, lässt dich kommen und kontrolliert den Boden. Zu Beginn nutzt
er nur A1, A2 und A4. Ab 70 % kommen A3, A5 und A6 dazu. Ab 40 % verkettet er die Grundlagen anspruchsvoller (siehe
3.3: „Er nimmt dich ernst“).

**Treffer am Boss:** Außerhalb eines Fensters zählen Treffer nur ×0,7 („Haltung“). In einem offenen Fenster (goldene
Rune unter ihm) zählen sie ×1,5. Er weicht nie unsichtbar aus. Jede Abwehr ist eine sichtbare Aktion (Parade, Schnitt
durch Pfeile).

### 3.1 Moveset Arius (Rauhreif-Form)

| # | Name *(Mod)* | Auslöser | Vorbereitung / Warnung | Trefferbereich (= Ansage) | Ausführung | Schaden | Gegenmaßnahme | Erholung / Fenster | Multiplayer |
|---|---|---|---|---|---|---|---|---|---|
| A1 | **Drei kalte Linien** (Kombo) | Ziel ≤ 5 Blöcke | Schwert sinkt in den Unterhau; Kegel am Boden | Hieb 1+2: Kegel R 4,5, ±55°; Hieb 3: Stoß-Linie 7 × 2 | Ausholen 14 → Hieb; Folgehieb 12; Stoß 16 | 10 / 10 / 14 | Seitlich aus dem Kegel, nach dem zweiten Hieb rückwärts aus der Linie | **26 Ticks offen**: Der Stoß überstreckt ihn, das Gewicht liegt auf dem vorderen Fuß | Gegen das Duellziel; andere können flankieren |
| A2 | **Windschritt** (Ausfall) | Ziel 6–16 Blöcke | Er senkt sich, Wind zieht an seinem Mantel; Linie von ihm durchs Ziel (+3) | Linie, Breite 2,2 | Ausholen 18 → Sprint 1,4 Bl./Tick | 16 + Rückstoß | Seitschritt aus der Linie | **30 Ticks offen**: rutscht auf dem Eis aus. Trifft er eine Säule, zerbricht sie und er taumelt 40 Ticks | Linie nur für das Duellziel |
| A3 | **Frostwand → Froststreifen** | alle ~25 s ab 70 % | Hand an den Boden; Linie für die Wand | Wand: Linie 14 × 1,6 (wer drinsteht: 6 Schaden + herausgedrückt) | Wand wächst in 20 Ticks (3 hoch, 9 s Bestand). 10 Ticks später erscheint ein dünner Froststreifen = Ansage für den Hieb | Wand 6; Streifenhieb 12 | Die Wand sperrt eine Fluchtseite. Man muss **vor** dem Streifenhieb auf die richtige Seite des Streifens | 20 Ticks offen nach dem Streifenhieb | Wand zwischen Arius und dem am weitesten entfernten Spieler |
| A4 | **Parierhaltung** | Ziel hat ihn ≥ 2× in 3 s getroffen, oder alle ~20 s | Solvane waagerecht vor dem Körper, blaue Rune, Klang wie gespanntes Eis | Kein Bodenbereich: Bedingung „Nahkampftreffer in den 30 Ticks“ | 30 Ticks Haltung. Pfeile werden in der Luft zerschnitten (White Null) | Nur über **Parade & Tritt** | **Nicht** zuschlagen; warten oder Abstand halten | Bleibt die Haltung unberührt, steht er **24 Ticks offen** (Geduld wird belohnt) | Löst bei jedem Nahkämpfer aus, aber nur einmal je Haltung |
| A5 | **Frostgriff** | Ziel 4–10 Blöcke und weicht seit 6 s aus | Er zeigt mit zwei Fingern auf dich; Kreis R 2,2 unter dem Ziel | Kreis | 22 Ticks | Bei Treffer: **Frostgriff**-Sequenz | Aus dem Kreis laufen | 20 Ticks offen (Fingerzauber, Schwert gesenkt) | Ein Kreis, nur das Ziel |
| A6 | **Gletscherstoß** | Ziel > 9 Blöcke (Fernkampf, Kiten) | Solvane über dem Kopf, Klinge nach unten | Drei Ringbänder (R 4 / 8 / 12, je 1,6 breit) im Abstand von 10 Ticks | Erstes Band nach 20 Ticks | 11 je Band | **Überspringen** (Band trifft nur am Boden) oder zwischen den Bändern stehen | **30 Ticks offen**: Klinge steckt im Eis | Trifft alle; eine Boss-Ansage-Gruppe |
| — | **Seitschritt** | Er wird außerhalb eines Fensters 3× in 2 s getroffen | sichtbarer kurzer Schritt (Animation, Staub) | — | 6 Ticks, 2 Blöcke | — | — | — | Höchstens alle 4 s; Schaden zählt trotzdem (×0,7) |

### 3.2 Inszenierte Treffer (Kontrollentzug, nur nach bestätigtem Treffer)

Technik *(geprüft)*: HHA sperrt Eingaben bereits per Mixin während Cutscenes (`KeyboardInputMixin`, `InputLockMixins`).
Das Addon bringt dieselbe Sperre mit eigenem Zustand („gehalten“): Server markiert den Spieler, ein S2C-Paket schaltet
Bewegung, Angreifen und Benutzen am Client ab, der Server führt den Körper per Geschwindigkeitspaketen und gibt ihn spätestens
nach einer harten Obergrenze frei. **Die Kamera bleibt beim Spieler**, es gibt keine Kamerafahrt und keine Abblende.

| Sequenz | Auslöser | Ablauf | Dauer | Schaden | Nachher |
|---|---|---|---|---|---|
| **Parade & Tritt** | Nahkampftreffer in A4 | Funke an Solvane, Spieler taumelt (gehalten), Arius dreht sich ein und tritt: Spieler fliegt 7 Blöcke zurück | 16 Ticks | 8 | 30 Ticks Schonfrist (keine Boss-Treffer, keine neue Ansage für ihn) |
| **Frostgriff** | im Kreis von A5 | Eis friert die Füße (Eisblock-Anzeige), Arius tritt heran, Knaufschlag | 18 Ticks | 10 | Schonfrist 30 Ticks, Arius 20 Ticks offen |
| **Luftkombination** *(weiße Form)* | Nahkampftreffer in der weißen Konterhaltung (W3) | Aufwärtsschnitt hebt dich 4 Blöcke, Arius springt nach, ein einziger Abwärtstritt bringt dich zurück auf sicheren Boden | 22 Ticks | 6 + 8 | Schonfrist 30 Ticks |
| **Tritt durchs Tor** (Übergang I → II) | einmalig | siehe 3.4 | 30 Ticks (gekürzt 16) | 0 | Landung auf sicheren Plattformen |

**Regeln für alle Sequenzen:** Während der Spieler gehalten wird, ignoriert der Server **jeden anderen Schaden** an ihm
(Drache, Gefahren, andere Spieler). Abbruch bei Tod, Disconnect, Dimensionswechsel, Kampfende oder ungültigem Zustand.
Obergrenze 40 Ticks, danach wird er immer freigegeben. Die Zielposition wird gegen Blöcke geprüft (kein Steckenbleiben;
notfalls auf die nächste freie Stelle in der Arena gesetzt). Nach der Freigabe gilt eine Schonfrist, damit keine
unvermeidbare Folgeattacke trifft.

### 3.3 Arius nimmt dich ernst (ab 40 % in Abschnitt I)
- Sichtbar: Er legt den Mantel ab (Mantelknochen ausgeblendet, Rüstung sichtbar), das goldene Auge leuchtet stärker, er
  sagt einmal „…Gut.“
- Neue Anschlüsse: A1 kann nach Hieb 2 in A2 übergehen (Windschritt statt Stoß). A3 kombiniert die Wand mit A6.
  A4 dauert kürzer (24 Ticks).
- **Begrenzte Anpassung („Er liest dich“):** Er zählt drei Gewohnheiten. Höchstens **eine** Anpassung ist gleichzeitig
  aktiv. Sie wird in der Aktionsleiste genannt, hält 30 s und ist dann für 60 s gesperrt.

| Gewohnheit | Schwelle | Antwort | Was belohnt wird |
|---|---|---|---|
| Fernkampf | ≥ 8 Projektil-Treffer in 20 s | **Pfeilschnitt**: Er geht 6 s lang auf dich zu und zerschneidet Pfeile von vorn | Flanken (Pfeile von der Seite/hinten treffen), Nahkampf |
| Rückenangriffe | ≥ 5 Treffer von hinten in 15 s | Die nächste Kombo beginnt mit einem Drehschnitt (Ring R 3,5, 14 Ticks) | Angriffe nur in offenen Fenstern |
| Schildmauer | ≥ 6 geblockte Treffer in 20 s | Der dritte Kombohieb wird ein Tritt, der den Schild 5 s sperrt (Vanilla-Schildsperre) | Ausweichen statt blocken |

### 3.4 Übergang I → II: Der Schnitt, der die Arena teilt
Siehe Abschnitt 6 (eigene Inszenierung). Danach folgt der **Tritt durchs Tor**: Arius steht wieder vor dem Duellziel,
pariert dessen nächsten Schlag (oder tritt nach 3 s von selbst an), dreht sich ein und tritt ihn durch das brechende
Südtor. Alle anderen trägt die Druckwelle mit.
- Unausweichlich, aber **0 Schaden**, keine Fallschäden (in der ganzen Arena), Flugbahn vorab berechnet und gegen Blöcke
  geprüft, Landung auf drei festen Plattformen vor dem Tempel.
- Während man fliegt, landet **Nivaris** mit gespreizten Schwingen über den Landeplätzen. Ihr Brüllen markiert den Start
  von Abschnitt II. Ihre erste Attacke folgt frühestens **40 Ticks** nach der Freigabe.
- Bei Wiederholungen (Flag pro Spieler) und im Training ist der Übergang verkürzt.

---

## 4. Abschnitt II: Die Gletscherschwelle (Nivaris allein)

### 4.1 Nivaris *(Mod)*
- **Name:** Nivaris, „die Alte vom Eirgletscher“.
- **Persönlichkeit:** uralt, geduldig, stolz. Sie spielt nicht mit Beute, sie *misst* sie. Sie beschützt Arius wie ein
  Elternteil, das ihm nichts mehr beibringen muss. Sie brüllt selten, und wenn, dann tief und kurz.
- **Silhouette** (kein Enderdrache): langer, gerader Hals mit einer **Krone aus nach hinten gekämmten Eishörnern wie ein
  gebrochener Heiligenschein**, schwere Vorderläufe mit hohen Schultern (Flügel sind eigene Glieder, keine Arme),
  **zerfranste Schwingen mit Membranlöchern** wie altes Segeltuch, ein Rückenkamm aus Kristallplatten und ein langer
  Schwanz, der in einem **Fächer aus Eisklingen** endet. Weiße Schuppen mit grauen Altersrändern, Bauch perlmuttgrau.
- **Resonanz-Merkmal:** Sie hat **dieselben Augen wie Arius**, eines golden, eines eisblau. In ihrer Brust liegt
  das *Herzenserbe* als goldener Kern. Er glüht beim Atemholen (Schwachpunkt) und flackert gleichzeitig mit Arius' Augen.

### 4.2 Kampfrhythmus
**Luft ≤ 9 s (1–2 Angriffe) → Landung → Boden 9–12 s (2–3 Angriffe) → Erschöpfung → Abflug.** Nahkämpfer bekommen also
mindestens alle 20 s ein langes Bodenfenster. Ihre Flughöhe bleibt ≤ 9 Blöcke, Bögen erreichen sie immer.

**Trefferzonen** (Treffer werden der Zone zugeordnet, die dem Blickstrahl des Angreifers bzw. dem Einschlagpunkt am
nächsten ist): **Kopf** ×1,5 (am Boden) / ×2 (zu Boden gebracht), **Kehle** beim Atemholen (zählt für den Erstickungs-
Konter), **Körper** ×1, **Schwanz** ×0,5.

### 4.3 Moveset Nivaris

| # | Name | Vorbereitung / Warnung | Trefferbereich | Dauer | Schaden | Gegenmaßnahme | Erholung / Fenster |
|---|---|---|---|---|---|---|---|
| D1 | **Frostodem-Bahn** | Sie gleitet seitlich heran, Kehle und Herzkern glühen golden-blau, tiefer Einatmer | Linie 34 × 3,5 | 30 Ticks Ansage, 14 Ticks Atem | 14 | Raus aus der Linie. **Konter:** 30 Schaden an Kehle/Kopf während der Ansage → sie erstickt am Atem und stürzt ab (70 Ticks offen) | Die Bahn wird zum **Eiskamm** (2 hoch, bleibt). Max. 3 Kämme; beim 4. bricht der älteste mit einer Riss-Ansage (Linie, 20 Ticks, 8 Schaden) in verzögerte Kristalle |
| D2 | **Sturzflug** | Sie steigt, faltet die Schwingen, Linie von ihr durchs Ziel | Linie 3 breit | 26 Ticks | 16 | Seitschritt. **Konter:** Steht ein Eiskamm in der Linie, kracht sie hinein, der Kamm bricht, **sie ist 90 Ticks zu Boden gebracht** (Kopf am Boden, ×2) | Ohne Kamm: Landung am Linienende → Bodenphase |
| D3 | **Landung** | Schatten + Kreis am Landeplatz | Kreis R 6 | 24 Ticks | 12 + Schnee wird nach außen geschoben (Druckwelle) | Raus aus dem Kreis | Beginnt die Bodenphase |
| D4 | **Klauenhieb** | Schulter hebt sich, Kegel | Kegel R 6, ±60° | 14 Ticks | 12 | seitlich/hinter sie | 10 Ticks |
| D5 | **Schwanzfeger** | Schwanzfächer hebt sich, Ringband | Ring zwischen R 3 und R 9 | 18 Ticks | 10 + Rückstoß | **Nah an ihren Körper (< 3)** oder weit weg (> 9) | 12 Ticks |
| D6 | **Flügelstoß** | Schwingen weit nach vorn, Kegel | Kegel R 14, ±50° | 20 Ticks | 2 + starker Schub nach hinten | Seitlich ausweichen oder hinter einem Eiskamm stehen (Kamm blockt den Schub) | Bereitet den Abflug vor |
| D7 | **Griff nach dem Eis** | Sie reißt einen Eiskamm-Brocken oder eine Säule aus und hebt ihn | Linie 2,5 zum Fernkämpfer | 24 Ticks | 14 | Seitschritt | 16 Ticks. Der Kamm/die Säule ist danach weg |
| — | **Erschöpfung** | nach 3 Bodenangriffen: schweres Atmen, Kopf tief | — | **40 Ticks offen** (Gold) | — | — | dann Abflug (Flügelschlag, Ringstoß R 5, kein Schaden) |

**Arius in Abschnitt II:** Er sitzt auf der stehengebliebenen Tempelhälfte, das Schwert über den Knien. Er greift
**nicht** an und ist hinter einer schwachen weißen Barriere (White Null: „die Verbindung zwischen dir und ihm ist
getrennt“). Treffer auf ihn bewirken nichts außer einem Hinweis. Einmal sagt er: „Sie war vor mir hier. Sei höflich.“

### 4.4 Übergang II → III: Der Gletscher bricht
Nivaris' Leiste ist leer, aber sie stirbt nicht. Sie steigt hoch, Arius pfeift einmal, ihre Augen flackern im Takt
mit seinen (**Resonanz**). Sie stürzt sich ins Zentrum des Plateaus.
1. Ringförmige Risse laufen über das Plateau (Ansage-Ringe ohne Schaden, nur Information), 40 Ticks.
2. Die Mitte (R 14) bricht in drei Ringen weg. Wer drauf steht, fällt mit **Sanftem Fall** ins Herzgewölbe. Wer
   außerhalb steht, wird nach 3 s an die Abbruchkante geführt und fällt ebenfalls. Es gibt keinen Fallschaden und
   keinen Schaden im Übergang.
3. **Verwandlung** (siehe 5.4): Arius springt hinterher und landet in der Mitte des Gewölbes. Die Spieler haben dabei
   die volle Kontrolle, sind aber unverwundbar, bis die Resonanz fertig ist und 40 Ticks vergangen sind.

---

## 5. Abschnitt III: Das Herzgewölbe (Arius weiß + Nivaris im Takt)

### 5.1 Wer führt? Der **Takt**
Immer genau **einer führt** (*Taktgeber*): Seine Augen leuchten hell, eine kleine Frostrune folgt ihm, und die
Bossleiste zeigt „▶“. Nur der Taktgeber benutzt volle Angriffe. Der Partner **stellt sich auf** (Position, Blickkontakt)
und darf nur einen kurzen Bodenangriff aus der Liste „Begleitung“ einsetzen, und auch nur dann, wenn der Spieler
gerade keine Boss-Ansage hat. Beide teilen sich **ein** Telegraph-Budget, deshalb gilt höchstens eine Boss-Ansage je
Spieler.
- Der Takt wechselt nach jedem abgeschlossenen Angriff oder Kombinationsangriff.
- **Taktbruch:** Wer den Taktgeber in seinem offenen Fenster trifft (≥ 25 Schaden) oder Nivaris' Atem erstickt, bricht
  den Takt. Der laufende Kombinationsangriff fällt aus, und **der Partner steht offen** (Gold, 50 Ticks).
- Etwa alle 20 s beginnt der Taktgeber einen **Resonanzangriff** (5.3).
- Ist Nivaris niedergerungen (ihre zweite Leiste leer), legt sie sich an die Wand. Arius kämpft dann allein mit dem
  weißen Moveset. Wer zuerst den Drachen besiegt, hat ab dann einen leichteren, aber längeren Kampf. Das ist eine
  bewusste taktische Wahl.

### 5.2 Arius, weiße Form (leichter, schneller, präziser)
Die gelernten Regeln gelten weiter (Kegel/Linie/Fenster), aber die Folgen ändern sich:

| # | Name | Änderung gegenüber I | Ansage | Schaden | Fenster |
|---|---|---|---|---|---|
| W1 | **Vier Linien** | Nach Hieb 3 folgt ein Windschritt *hinter* das Ziel und ein Rückwärtsschnitt (Kegel R 4, 12 Ticks) | Kegel/Linie wie A1, Folgehiebe 10–12 | 10/10/12/12 | 22 Ticks offen |
| W2 | **Windschritt-Kette** | Zwei Ausfälle. Der zweite richtet sich neu auf das Ziel aus (eigene Linie, 12 Ticks) | 16 + 12 Ticks | 14 + 14 | 26 Ticks offen (nach dem zweiten) |
| W3 | **Konterhaltung** | Haltung 22 Ticks. Nahkampftreffer → **Luftkombination** | blaue Rune, gesenkte Spitze | 6 + 8 | unberührt: 20 Ticks offen |
| W4 | **Null-Schnitt** (White Null) | Lange, dünne Linie 24 × 1,4, die **durch Säulen und Eiskämme hindurch** trifft („trennt die Verbindung zur Deckung“). Danach wechselt der Takt sofort | 16 Ticks | 16 | 16 Ticks |
| — | **Pfeilschnitt** | Projektile von vorn werden zerschnitten, solange er geht. Von der Seite und von hinten treffen sie | — | — | — |

**Begleitung Nivaris** (wenn Arius führt): D4 Klauenhieb oder D5 Schwanzfeger. **Begleitung Arius** (wenn Nivaris
führt): A1-Hieb 1 allein oder Seitschritt zur Flanke.
**Nivaris in III** fliegt kürzer (≤ 6 s, Decke!), hat D1 (max. 2 Kämme), D3, D4, D5. Ihre Leiste ist neu (Pool III).

### 5.3 Resonanzangriffe (gemeinsam, mit sicherem Weg und anschließender Chance)

| Name | Ablauf | Sicherer Weg | Danach | Taktbruch-Möglichkeit |
|---|---|---|---|---|
| **R1 Frostkorridor und Schnitt** | Nivaris zieht zwei parallele Atembahnen (Linien), daraus wachsen zwei Eiskämme mit je **zwei Lücken** (goldene Linien). Am Korridorende hebt Arius Solvane: Linie den Korridor entlang (5 breit), 40 Ticks nach den Kämmen | Seitlich durch eine goldene Lücke raus | Arius 40 Ticks offen, die Kämme zerbrechen | Atem ersticken → Kämme entstehen nicht, Arius steht offen |
| **R2 Kristallsturm** | Arius schleudert 6 Eiskristalle in die Luft, sie hängen glitzernd. Ihre Bahnen werden sofort als parallele Linien gezeigt (Richtung = Nivaris' Flügelschlag, sichtbar an ihrer Stellung). 36 Ticks später treibt der Flügelschlag sie über die Arena | Zwischen den Linien (Gassen ≥ 2 breit, golden markiert) | Nivaris landet, 40 Ticks offen | Arius während des Wurfs (offen) treffen → Kristalle fallen harmlos, Nivaris offen |
| **R3 Eisfeld spalten** | Nivaris friert einen großen Kreis (R 10) unter der Gruppe ein (Kreis, 30 Ticks, noch kein Schaden). Arius spaltet ihn mit einem Hieb: **eine Hälfte wird golden (sicher)**, die andere bricht 20 Ticks später (14 Schaden) | Auf die goldene Hälfte wechseln | Arius 30 Ticks offen | Nivaris beim Einfrieren an der Kehle treffen |
| **R4 Weißes Loch** | siehe Abschnitt 7 (einmal je Kampf) | Korridore | großes Doppelfenster | — |

Eine Resonanz-Animation (Augen beider blitzen gleichzeitig auf, ein feiner goldener Faden zwischen Arius' Brust und
Nivaris' Herzkern) kündigt **jeden** Resonanzangriff 20 Ticks vorher an. So wird die Zusammenarbeit lesbar.

### 5.4 Die Verwandlung zur weißen Form (einmalig, Übergang II → III)
1. **Landung** (0–10): Arius landet im Gewölbe, ein Knie am Boden, der Mantel bläht sich.
2. **Lösen** (10–40): Rüstungsplatten und Mantel lösen sich von innen nach außen in **schwebende Frostsplitter** auf
   (Client-Effekt: Splitter in Form der Rüstungsteile, die aufsteigen, sich drehen und in Licht zerfallen). Dabei leuchtet
   das goldene Auge.
3. **Weiß** (40): Das Modell wechselt auf die weiße Form. Ein Druckstoß (Ring R 12) **räumt den Schnee** (Schneeschichten
   im Umkreis werden entfernt, sichtbare Druckwelle, kein Schaden).
4. **Resonanz** (40–70): Nivaris landet hinter ihm. Ihre Augen und seine wechseln gleichzeitig: Gold und Blau tauschen
   für einen Moment die Seiten. Weiße Linien leuchten in ihren Schwingenmembranen auf (Leuchtmaske „Resonanz“).
5. Danach: 40 Ticks Ruhe, erst dann beginnt der Takt.

Spielerischer Zweck: Die weiße Form hat ein **anderes Moveset**, andere Rhythmen (kürzere Ansagen bis zur Mindestdauer,
mehr Folgehiebe, kürzere Fenster) und **erst mit ihr gibt es Resonanzangriffe**.

---

## 6. Inszenierung: Der Schnitt, der die Arena teilt

Feste Geometrie (lernbar): Der Schnitt läuft immer von den Altarstufen im Norden durch das Mittelschiff nach Süden
(senkrechte Schnittebene x = +1,5 relativ zur Tempelmitte).

| Zeit (Ticks) | Bild | Ton | Spiel |
|---|---|---|---|
| 0 | Arius springt auf die Altarstufe und dreht sich nach Süden. Er ist unverwundbar (weiße Haltung). | Musik bricht ab | — |
| 0–40 | **Der Wind kehrt sich um:** Schnee und Staub strömen nicht mehr durch die Halle, sondern **zu ihm hin**. Die Umgebung wird still. | Wind hörbar „rückwärts“ (umgekehrte Hüllkurve), dann nichts | Freie Bewegung |
| 20 | Weiße Energie verdichtet sich an der **Schneide**, nicht am Körper: ein schmaler Lichtstreifen entlang Solvane. | ein heller, hoher Ton, der ansteigt | — |
| 30 | **Lesbare Schnittlinie**: die Ansage vom Altar bis weit hinter das Südtor (Linie 3 breit, Typ ROOM, 60 Ticks) | — | Aus der Linie gehen |
| 90 | **Er schlägt zu**: Hieb von oben nach unten, eine Sichel läuft die Linie entlang | ein einziger trockener Schlag | 30 Schaden in der Linie (ausweichbar) |
| 90–104 | **Nichts passiert.** Nur eine haarfeine weiße Naht hängt in der Luft. | Stille | — |
| 104 | **Der Tempel trennt sich:** Die Osthälfte (Dach, Säulen, Mauer östlich der Ebene) rutscht entlang der Schnittebene nach außen, kippt und stürzt. In der Ferne öffnet sich die **Gletscherwand im Süden** entlang derselben Linie (Kluft). | tiefes Brechen, Eisregen | Spieler auf der Ostseite: Druckwelle schiebt sie nach Westen (kein Schaden) |
| 104–150 | Trümmer schlagen auf, Schneestaub. | | |
| 150 | Die Osthälfte ist ein Trümmerfeld, die Halle ist offen zum Gletscher. | | Tritt durchs Tor (3.4) |

**Technik:** Der Server nimmt eine Momentaufnahme aller Blöcke östlich der Ebene innerhalb der Tempelgrenzen (≤ 4.000
Blöcke, Palette + Positionen, ein Paket). Der Client rendert diese Momentaufnahme als **ein bewegtes Blockbündel**
(`BlockRenderManager.renderBlockAsEntity`, je Block eine Matrix). Der Server entfernt die echten Blöcke im selben Tick,
das Paket kommt vorher an, also ist kein Flackern zu sehen. Nach dem Sturz setzt der Server einen Trümmerhaufen aus
Vanilla-Blöcken und der Client verwirft die Momentaufnahme. **Keine einzige Physik-Entity.** Spätere Geometrie: Die
Westhälfte steht als Tribüne, auf der Arius in Abschnitt II sitzt. Die Kluft im Süden ist in II eine Deckungsrinne.

---

## 7. Signatur: Das weiße Loch („Weißer Kern“)

**Interpretation:** Ein schwarzes Loch saugt an. Der weiße Kern **gibt ab**: Er presst alles aus seinem Punkt heraus:
Luft, Schnee, Licht, Fragmente. Gefährlich ist nicht der Kern, sondern die **Druckfronten**, die rhythmisch aus ihm
laufen, und zwischen ihnen öffnen sich **Korridore**, in denen der Druck „im Windschatten“ abreißt.

| Zeit | Ablauf | Lesbarkeit | Spiel |
|---|---|---|---|
| 0–60 | Arius springt in die Mitte, hebt Solvane senkrecht. Nivaris kreist und atmet in den Punkt über ihm (Resonanzfaden). Ein **dichter weißer Kern** (Ø 2,4) entsteht 5 Blöcke über dem Boden. Schnee und Licht strömen sichtbar *nach außen*. Der Himmel im Gewölbe wird heller statt dunkler. | Partikel fliegen radial weg, sanfter Schub nach außen (kein Schaden) | Abstand gewinnen |
| je Puls (4×, alle 36 Ticks) | 30 Ticks vorher: **goldene Korridore** (3 radiale Bahnen, 3 breit) vom Kern bis zur Wand. Dann läuft eine **Druckfront** (Ring) mit 0,6 Bl./Tick nach außen | Gold = Windschatten | Front trifft alle **außerhalb** der Korridore: 10 Schaden + Stoß nach außen. Die Korridore drehen sich je Puls um 40° → man muss zwischen den Pulsen umsteigen |
| zwischen Puls 3 und 4 | **Die Lücke**: Der Kern atmet ein (20 Ticks Stille). Arius fällt aus dem Licht und führt **einen** extremen Schnitt durch den Kern auf das Duellziel (Linie 60 × 4, 26 Ticks) | Linie, Stille, Licht bündelt sich an der Klinge | 36 Schaden, ausweichbar. Wer in einem Korridor steht, muss trotzdem aus der Linie |
| Puls 4 | 4 Korridore (leichter) | | |
| Ende | Der Kern kollabiert zu einem Lichtpunkt und erlischt (Blitz, abschaltbar). | | **Arius und Nivaris sind 100 Ticks offen** (beide Gold), das größte Fenster des Kampfs |

Unterschied zu einem schwarz eingefärbten Sog: Richtung nach außen, Rhythmus, Korridore als Struktur, Stille als
Ankündigung des Schnitts, helleres statt dunkleres Licht.

---

## 8. Finale: Der letzte klare Moment

Wenn Arius' Leiste in III leer ist: Der Sturm lässt nach, die Musik schrumpft auf ein einzelnes Motiv, Nivaris legt
sich an die Wand. Arius steckt Solvane in die Scheide.

> „Kein Sturm mehr. Nur du und ich.“

**Drei Austausche.** Er steht in Iai-Haltung (Hand am Griff, Klinge auf dem Rücken). Eine **haarfeine Linie** zeigt auf
das Duellziel (30 → 24 → 18 Ticks). Dann zieht er und schneidet in einem Zug durch die Linie.
- **Gewonnen** wird ein Austausch, wenn man **aus der Linie tritt** oder **perfekt blockt** (Schild in den letzten 10
  Ticks vor dem Treffer erhoben). Er gleitet vorbei und steht **50 Ticks offen**: Ein Treffer gewinnt den Austausch.
- Trifft er, kostet das 12 Schaden. Er geht zurück in die Haltung (40 Ticks Pause, kein Folgeangriff).
- Nach drei gewonnenen Austauschen: Sieg. Im Multiplayer zielt die Linie auf das Duellziel, aber jeder darf kontern.

Warum kein größeres Finale? Nach Schnitt, Gletschersturz und weißem Loch wirkt eine weitere Explosion kleiner, nicht
größer. Der Kontrast macht die Eskalation sichtbar und prüft am Ende genau das, was Abschnitt I gelehrt hat.

**Ende:** Arius kniet kurz, steht auf, sieht Nivaris an.
> „Du bist keine Waffe.“ … „Nimm es. Und komm nicht noch einmal mit diesem Siegel.“

Die Belohnung erscheint als Frostkristall vor jedem Teilnehmer (eigene Beute je Spieler). Nach 20 s öffnet sich der Weg
zurück (Rückkehr-Punkt = Ort, an dem das Siegel benutzt wurde).

---

## 9. Fairness, Multiplayer, Technik

### 9.1 Grundregeln (verbindlich)
- Jede Attacke über HHA-`Telegraph`: Ansage = Trefferfläche, Mindestdauern (schnell 10, schwer 16, Geschoss 16, Fläche 20,
  saalweit 40, Folgehieb 10 Ticks), Weiß-Aufblitzen kurz vor dem Treffer, **Gold = sicher/offen**.
- Höchstens **eine Boss-Ansage je Spieler** gleichzeitig (gemeinsames Budget beider Bosse). Höchstens **eine Saal-Gefahr**
  (zerbrechender Eiskamm). Keine Adds.
- Keine Betäubungsschleifen: Gehalten wird man nur in den 4 Sequenzen. Danach gelten 30 Ticks Schonfrist und **60 Ticks
  Sperre** für jede weitere Sequenz gegen denselben Spieler.
- Kein Kamerarütteln während einer laufenden Ansage (von `Vfx` erzwungen). Rütteln und Bildblitze lassen sich am Client
  reduzieren (HHA-Einstellungen; das Addon liest sie für eigene Effekte mit und hat eigene Schalter: `/ariusc shake|flash|fx`).
- Ansagen werden immer gezeichnet, auch mit reduzierten Effekten. Eigene Effekte liegen **über** dem Boden (keine
  Bodenflächen außer Ansagen), damit nichts die Warnungen verdeckt.
- Keine Kamerafahrt während aktiver Ausweichsituationen. Kurze Kamera-Momente gibt es nur im Intro, und auch dort nur ohne
  laufende Gefahr.
- Grundmechanik: **Vanilla-Bewegung, Sprung, Schild und Bogen genügen.** Perfektes Blocken = Vanilla-Schild mit Timing,
  keine neue Taste. Die Belohnungen (Frostschritt, Konterhaltung) fühlen sich passend an, sind aber nicht nötig.

### 9.2 Kombinationsregeln
| Darf gleichzeitig laufen | Darf nie gleichzeitig laufen |
|---|---|
| Boss-Ansage + zerbrechender Eiskamm (Saal-Gefahr) | zwei Boss-Ansagen für denselben Spieler |
| Taktgeber-Angriff + Begleitung (nur gegen *andere* Spieler) | Sequenz (gehalten) + irgendein Treffer am gehaltenen Spieler |
| Resonanzangriff = eine Gruppe | Resonanzangriff + Begleitung |
| | Weißes Loch + irgendetwas anderes |

### 9.3 Schaden gegen Endgame-Rüstung
Eigene Schadensarten (`hha_arius:solvane`, `frost_breath`, `pressure`, `null_cut`) umgehen Rüstung und Verzauberungen.
Das Addon rechnet beides selbst und gedeckelt ein: **Rüstung höchstens 30 %**, **Schutz-Verzauberungen höchstens 20 %**,
Resistenz wirkt normal. Ein voll ausgerüsteter Spieler nimmt so etwa 56 % des Grundwerts: Ein Kombohieb (10) ≈ 2,8
Herzen, der Schnitt (30) ≈ 8,4 Herzen. Config `hha_arius.damage_scale` (Standard 1,0).

### 9.4 Multiplayer
- **Leben:** × (1 + 0,55·(n−1)), n = Spieler beim Start (max. 6 zählen). Druck: Ab 3 Spielern nutzt Nivaris in III ihre
  Begleitung auch gegen ein zweites Ziel. Ansagen werden nie kürzer.
- **Duellziel:** Arius wählt den Spieler mit dem meisten Schaden in den letzten 10 s (bei Gleichstand den nächsten).
  Gewechselt wird nur zwischen zwei Angriffen. Das Ziel sieht „Arius fixiert dich“ und eine dünne Blicklinie.
- **Tod:** Totems wirken normal. Ein tödlicher Treffer ohne Totem macht **k. o.** statt zu töten: Man behält alle Items,
  wird mit 1 Herz zum Zuschauerpunkt (Tempelrest bzw. Galerie) gebracht und steigt mit dem nächsten Abschnitt wieder ein.
  Sind alle k. o.: Niederlage, Rückkehr nach Hause, Siegel zurück.
- **Disconnect:** Der Spieler wird aus dem Kampf entfernt. Kommt er im selben Kampf zurück, steigt er am Zuschauerpunkt
  ein. Ist der Kampf vorbei, wird er beim Einloggen nach Hause gebracht.
- **Nachzügler:** Wer die Arena-Dimension betritt (z. B. per Echo eines Freundes), wartet am Zuschauerpunkt und steigt mit
  dem nächsten Abschnitt ein.
- **Niemand mehr da** (20 s): Abbruch, Arena wird beim nächsten Start neu gebaut.
- **Serverneustart:** Kämpfe leben nur im Speicher. Beim Stoppen werden sie ohne Beute beendet, Spieler bekommen ihr
  Siegel zurück, und wer beim nächsten Login in der Arena steht, wird nach Hause gebracht (Rückkehrpunkte persistent).

### 9.5 Leistung
- Arena-Chunks werden nur während des Kampfs zwangsgeladen.
- Zerstörung: Momentaufnahmen statt Entities; Trümmerhaufen sind echte Blöcke ohne Nachbar-Updates.
- Partikel: Client-Budget 600 eigene Partikel; der Schneesturm skaliert mit HHA `particle_effects`.
- Trefferprüfung nur gegen Teilnehmer (≤ 6) und nur zur Trefferzeit; die Druckfronten prüfen je Tick nur den
  Radialabstand.
- Kampfzustand, Treffer, Takt und Sequenzen sind serverseitig. Der Client zeigt nur an (Posen über `DataTracker`,
  Effekte über Pakete).

---

## 10. Rüstung: drei Richtungen und Empfehlung

**Feste Grenze:** Grundbausteine und Silhouette der Netherite-Rüstung bleiben erhalten (gleiche UV-Bereiche/Formen,
keine Zusatzgeometrie, die die Form ändert).

| Richtung | Kurzbeschreibung | Stärke | Schwäche |
|---|---|---|---|
| **A „Eirholm-Wacht“** | Netherite in **Anthrazit mit stahlblauem Unterton**, Trims aus **Altgold** im Motiv des **Viersterns** von Arius' Schulteremblem, **eisblaue Leuchtnähte** in den Fugen | Liest sich dunkel vor Schnee, gehört sichtbar zu Arius (Stufe-I-Bossrüstung), lässt Weiß für die Verwandlung frei | Wirkt erst aus der Nähe „frostig“ |
| **B „Nivaris-Schuppe“** | Perlweißes Netherite mit Schuppenrelief, blassgoldene Herzlinie, membranblaue Akzente | Sehr drachig | Verschwimmt im Schnee, macht Arius' Weiß beliebig |
| **C „Weiße Null“** | Invertiert: kalkweiße Platten, schwarze Fugen, Licht bricht von innen durch Risse | Spektakulär | Zu nah an der weißen Bossform, Leuchtrisse wirken schnell wie Partikelwolke |

**Empfehlung: A „Eirholm-Wacht“** mit einem Akzent aus B: Die Brustplatte trägt das **Herzenserbe** als kleinen goldenen
Rautenstein mit blauem Kern (Verbindung zu Nivaris). Begründung: Lesbarkeit aus Spielentfernung, klare Zugehörigkeit zu
Arius (Stufe I trägt dieselbe Rüstung) und Weiß bleibt der Verwandlung vorbehalten.

**Ausarbeitung:**
- **Farbpalette:** Anthrazit `#22232b → #4a4c5c`, Stahlblau `#2c4466 → #5a7fae`, Altgold `#7a5a1e → #d8b45a`, Eisblau
  (Leuchten) `#9fe6ff`, Herzkern `#ffd98a`.
- **Trim-Motiv:** Vierstern mit langen senkrechten Strahlen (wie Solvanes Parierstange von vorn). Am Helm Stirnstern und
  Wangenlinien, an der Brust Stern über dem Herzstein und Kragenlinien, an den Beinschienen Rautenfolge an der
  Außennaht, an den Stiefeln Spornlinie.
- **Technik:** *Fest integrierte* Verzierung (in die Equipment-Textur gemalt, Basis: Vanilla-Netherite-Textur
  umgefärbt), plus **eine zweite Equipment-Ebene nur mit den Leuchtnähten**, lichtunabhängig gerendert (sanfter Puls
  70–100 %, 3 s). Keine Partikelwolke. Bei voller Resonanz (≥ 80) zieht nur gelegentlich ein Frosthauch von den
  Schultern.
- **Echter Armor Trim** (getrennt davon): Vorlage **„Weiße Klinge“** als kosmetische Belohnung (Abschnitt 11). Sie ist ein
  echtes Vanilla-Trim-Muster für *jede* Rüstung, mit jedem Trim-Material färbbar.
- **Material:** Werte wie Netherite (3/8/6/3), Härte 3,5 (statt 3), Rückstoßresistenz 0,15 (statt 0,1), feuerfest.
  Herkunft: Netherite-Teil + **Eirholm-Schmiedevorlage** + **Herzfrost** am Schmiedetisch.

### 10.1 / 10.2 Fähigkeiten, Kampfhandschuhe und Solvane
**Ersetzt durch `docs/AUSRUESTUNG.md` (Stand 1.1.0).** Dort stehen die gemeinsame Ressource (Frostresonanz mit
Ruhepunkt), die Verteilung der Rüstungsfähigkeiten (Brust Ability 1, Beine Ability 2, volles Set Ability 3 = Weißes
Loch), Arius' Kampfhandschuhe (Nahkampf-Moveset, Magiemodus mit Auswahlrad, 13 Techniken aus Wind, Eis und Yang),
Solvane als mittellanges Bastardschwert mit der Waffentechnik „Klarer Schnitt“ sowie Kosten, Grenzen und Balancing.
Die frühere „Weiße Linie“ entfällt (sie hätte Solvanes Schnitt gedoppelt).

### 10.3 Drachenrelikt: **Nivaris' Schwingenschuppe** *(Mod)*
Eigener Zweck: **Bewegung und Gelände**, keine Resonanz, kein Schaden.
- Benutzen: **Schwingenschlag**: 6 Blöcke hoch, 4 nach vorn, 5 s Sanfter Fall. Bei der Landung schiebt ein Schneestoß
  Gegner zurück (kein Schaden). Abklingzeit 20 s. Funktioniert ohne Set.

---

## 11. Progression und Belohnungen

| Belohnung | Erster Sieg | Weitere Siege | Verwendung |
|---|---|---|---|
| **Solvane (Abbild)** | garantiert | 10 % | Waffe |
| **Arius' Kampfhandschuhe** | garantiert | 10 % | Waffe und Zauberwerkzeug |
| **Herzfrost** | 4 | 2 | Rüstungsaufwertung, Schwingenschuppe |
| **Eirholm-Schmiedevorlage** | 1 | 50 % | Netherite → Eirholm (vervielfältigen: Vorlage + 7 Diamanten + Herzfrost → 2) |
| **Nivaris-Schuppe** | 6 | 3 | Schwingenschuppe (3 Schuppen + 2 Phantomhaut + Herzfrost) |
| **Echo von Eirholm** | 1 | — | Training einzelner Abschnitte |
| **Trim-Vorlage „Weiße Klinge“** | nur bei Sieg **ohne k. o.** in der Gruppe | ebenso | kosmetisch, Vanilla-Trim für jede Rüstung |
| Fortschritte | „Das Siegel“, „Unter der Linie“ (Schnitt ohne Treffer überstehen), „Taktbruch“, „Die weiße Klinge“ (Sieg), „Kein Sturm mehr“ (Sieg ohne k. o.) | | Anerkennung |

Jeder Teilnehmer erhält eigene Beute. HHA erzeugt automatisch das Kit `/hha kit hha_arius.eirholm`.

---

## 12. Umsetzungsplan (passend zur Codebasis)

| Baustein | Umsetzung | Ort |
|---|---|---|
| Addon-Einstieg | `AriusAddon` (Entrypoint `hha`): Config-Keys, Items, Set, Abilities, Lore. `AriusAddonClient` (`hha_client`): HUD-Cooldowns | `AriusAddon.kt`, `gear/` |
| Bosse | `AriusEntity`, `NivarisEntity` (Puppets ohne KI, Posen über `DataTracker`), Kampf als Skript-Iteratoren wie bei HHA | `boss/` |
| Ansagen | ein `Telegraph.Fight` für beide Bosse (Stil HEAVEN = Himmelblau-Weiß für Arius, SOUL = Tiefcyan für Nivaris) | `boss/EirholmFight.kt` |
| Arena | Dimension + Biom per Datapack, Bau aus Vanilla-Blöcken, Instanz-Slots, Neubau je Versuch | `arena/`, `data/hha_arius/dimension*` |
| Schnitt/Kern/Splitter | eigene Client-Effekte (Blockbündel-Renderer, Kern, Druckfronten-Schleier, Frostsplitter) | `client/fx/` |
| Gehalten-Zustand | Server-Zustand + S2C-Paket + Client-Mixins (Bewegung/Aktionen) | `boss/Hold.kt`, `mixin/client/` |
| Modelle | Python-Werkzeuge aus HHA übernommen (`tools/assets`), Modelle Arius (dunkel/weiß) und Nivaris, Schlüsselposen → Kotlin | `tools/assets/`, `client/model/` |
| Rüstung/Items | Netherite-Basis umgefärbt (mctex), Leuchtebene, Item-Icons, Solvane als 3D-Handmodell | `assets/hha_arius/` |
| Audio | Synthetisierte Musik (3 Themen + Finale-Motiv) und Effektklänge (numpy + ffmpeg), keine fremden Samples | `tools/assets/audio`, `sounds/` |
| Tests | Build; Server-Selbsttest (Befehl `/hhaarius selftest`: Arena bauen, Abschnitte mit Fake-Ablauf durchschalten, Schnitt-Momentaufnahme zählen, Aufräumen prüfen) | `boss/SelfTest.kt` |
