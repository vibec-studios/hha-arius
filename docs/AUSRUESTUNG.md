# Arius' Ausrüstung: Rüstung, Kampfhandschuhe und Solvane (Stand 1.2.0)

Dieses Dokument ersetzt die Abschnitte 10.1 und 10.2 aus `KONZEPT.md` (Fähigkeiten der Rüstung, Solvane). Bosskampf,
Landschaften, Cutscenes, die Optik der Rüstung (10), die Schwingenschuppe (10.3) und die anderen Mods bleiben, wie sie
sind. Die geplante Arius-Mace entfällt; an ihre Stelle treten **Arius' Kampfhandschuhe**.

Alle Zahlen sind Startwerte und über `/hha set hha_arius.<schlüssel>` einstellbar (Zeiten in Ticks, 20 = 1 s).
Zahlen hier = Standardwerte im Code (`gear/GearConfig.kt`).

---

## 1. Drei Quellen, klar getrennt

| Quelle | Wofür | Tasten | Braucht |
|---|---|---|---|
| **Rüstung** (4 Teile) | Setbonus, Fähigkeiten einzelner Teile, Passive. Bewegung und Schutz sofort erreichbar, dazu die Signatur „Weißes Loch“ | HHA Ability 1/2/3 (Standard G/H/U) | nur die Rüstung |
| **Kampfhandschuhe** | Nahkampfmodus (eigener Kampfstil) und das zentrale Zaubersystem (Wind, Eis, Yang) | Links/Rechts, **Technik-Taste** (R), **Modus-Taste** (V) | nur die Handschuhe |
| **Solvane** | Schwertmechaniken (Klare Schneide, Konterhaltung) und die Waffentechnik **Klarer Schnitt** | Links/Rechts, **Technik-Taste** (R) | nur Solvane |

- Der **volle Setbonus braucht nur die vier Rüstungsteile**. Solvane und Handschuhe sind keine Voraussetzung.
- Jede Quelle funktioniert allein. Das volle Set ergänzt die beiden Waffen nur über die gemeinsame Ressource (höherer
  Ruhepunkt, +25 % Gewinn), nie über zusätzliche Kopien derselben Zauber.
- **Kein Zauber liegt doppelt:** Die Rüstung hat Kern (Druckstoß), Frostschritt (Dash) und Weißes Loch. Die Handschuhe
  haben andere Formen: Sichel, Sprung, Kegel, Sog, Splitter, Wand, Feld, Panzer, Kugel, Schild, Plattform, Brücke und
  Gleiter. Solvane hat den Schnitt. Die frühere „Weiße Linie“ der Brustplatte ist entfallen, weil sie mit Solvanes
  Schnitt doppelt gewesen wäre.

### Tasten (alle frei belegbar, Steuerung → Kategorie „Arius“ bzw. HHA)
| Taste | Standard | Mit Handschuhen | Mit Solvane |
|---|---|---|---|
| Technik | R | Zauberrad (halten, zeigen, loslassen); kurz tippen = vorige Technik | Klarer Schnitt (halten = laden, loslassen = Schnitt) |
| Modus | V | Nahkampf ↔ Magie | – |
| HHA Ability 1 / 2 / 3 | G / H / U | Rüstung: Weißer Kern / Frostschritt / Weißes Loch | dasselbe |

Die Handschuh-Tasten sind eigene Tasten und kollidieren nicht mit den HHA-Abilities 1–6 (Blood nutzt 4 und 5).

---

## 2. Gemeinsame Ressource: Frostresonanz (0–100)

Eine Energie für alles, damit Entscheidungen entstehen („Schild halten oder gleich schneiden?“).

- **Träger:** Wer ein Arius-Rüstungsteil trägt oder Handschuhe bzw. Solvane in der Haupthand hält. Der Balken über der
  HHA-Leiste zeigt den Wert, den **Ruhepunkt** (Goldstrich) und die **Kosten der bereiten Technik** (weißer Strich).
- **Ruhepunkt:** Nach 6 s ohne Kampf strebt die Resonanz mit 4/s zum Ruhepunkt, **von unten wie von oben**. Er liegt bei
  **30**, mit vollem Set bei **50**. Folgen:
  - Nutzzauber (Plattform 12, Brücke 20, Windsog 4) gehen auch außerhalb des Kampfs.
  - Große Techniken (Klarer Schnitt 50, Weißes Loch 100) brauchen Kampf.
  - **Horten ist unmöglich**, weil alles über dem Ruhepunkt nach dem Kampf wieder abfließt.
- **Gewinn im Kampf** (nur an lohnenden Zielen: Monster, Spieler, Wesen, die dich gerade angreifen, sehr zähe Wesen;
  **nicht** an Tieren, Rüstungsständern, Dorfbewohnern oder Verbündeten):

  | Quelle | Gewinn |
  |---|---|
  | Solvane, voll geladener Treffer | 10 |
  | andere Nahkampfwaffe, voll geladen | 4 |
  | Jab 3 · Cross 3 · Haken 4 · Seitentritt 5 · Drehtritt 8 | |
  | Parade der Handschuhe, Solvanes Konterhaltung, perfekter Schildblock | 20 / 25 / 25 |
  | erlittener Treffer durch ein Wesen | 3 |

  Mit vollem Set gibt es **+25 %** auf jeden Gewinn. Zauber erzeugen keine Resonanz; sie verbrauchen sie.
- **Fokus:** Ein Spieler macht immer nur **eine** Sache: Schlag, Aufladen, Zielen, Schild, Gleiten, Schwerttechnik oder
  Rüstungs-Wirken. Nach großen Techniken (Klarer Schnitt, Weißes Loch, Weißer Kern, voll geladene Zauber) folgt **1,5 s
  Nachhall**, in dem keine andere große Technik beginnen kann.

---

## 3. Rüstung (nach der Konvention der anderen Sets)

Anzeigenamen: **Arius' Helm, Arius' Brustpanzer, Arius' Beinschienen, Arius' Stiefel**. Die Item-IDs bleiben
`eirholm_*`, damit bestehende Welten ihre Rüstung behalten. Werte wie Netherite (3/8/6/3, Härte 3,5).

**Optik seit 1.2.0: „Drachenherz-Wacht“**, eine eigene 3D-Rüstung statt umgefärbtem Netherite (`docs/ARMOR_3D.md`).
- **Farben:** perlweiße Drachenschuppen mit eisblauen Schatten, Gold nur als Kante, Navy-Leder darunter.
- **Helm:** Krone aus Eishörnern wie bei Solvanes Drachenform; ein Horn ist abgebrochen.
- **Brust:** Herz-Juwel in der Mitte, Schulterstücke als gefaltete Schwingenplatten.
- **Beine:** Schuppen-Tassetten und Kniekacheln mit goldenen Krallen.
- **Stiefel:** mit Klauenspitzen.
- **Bewegung:** Tassetten und Schwingenplatten federn nach.
- **Leuchten:** Das Herz-Juwel pulsiert.
- **Trim:** Das Muster „Weiße Klinge“ ist auf dieser Rüstung nicht sichtbar, auf anderen Rüstungen schon.

| Teil | Aktiv | Passiv |
|---|---|---|
| **Helm** | – | kein Gefrieren |
| **Brustpanzer** | **Ability 1 – Weißer Kern** (Schutz): 40 Resonanz, 0,5 s Kern an der Brust, dann Druckstoß R 6, 6 Schaden, Gegner 3 Blöcke zurück, Geschosse zerstört, 3 s Resistenz I. Abklingzeit 25 s | **Frostpanzer:** Wer dich im Nahkampf trifft, ist 1 s verlangsamt (je Angreifer höchstens alle 3 s) |
| **Beinschienen** | **Ability 2 – Frostschritt** (Bewegung): 15 Resonanz, 6 Blöcke in Laufrichtung, 0,3 s keine Nahkampftreffer, über stillem Wasser brüchiges Eis (schmilzt selbst). Abklingzeit 2,5 s | – |
| **Stiefel** | – | Pulverschnee trägt; Stürze bis 2,5 Herzen Schaden werden abgefangen |
| **Volles Set** | **Ability 3 – Weißes Loch** (ersetzt bei vollem Set den HHA-Ultra-Modus, wie bei HHA Void) | Ruhepunkt 50, +25 % Resonanz, Frosthauch bei hoher Resonanz |

### Das weiße Loch: warum als Setfähigkeit
Es ist Arius' Erkennungszeichen und im Kampf seine „letzte Karte“. Als **Setfähigkeit** gehört es zur Rüstung, die man
sich im Bosskampf verdient. Es bleibt unabhängig von der gehaltenen Waffe und steht im selben Slot wie die großen
Setfähigkeiten der anderen Addons (Void Collapse). Als Yang-Technik der Handschuhe hätte es mit Yang-Kugel und Schild
konkurriert und die Handschuhe zur Pflicht gemacht.

- Kosten: **alle Resonanz, mindestens 100** (also nur bei vollem Balken). Abklingzeit **120 s**.
- 1 s Wirken (Hände führen zusammen, du bist verlangsamt).
- Danach steht ein weißer Kern bis zu 16 Blöcke vor dir.
- **2 s Sog:** Er zieht leichte Gegner heran. Bosse, Wächter und große Wesen bewegt er nicht, Spieler nur halb so stark.
  Feindliche Geschosse werden im Radius zerschnitten.
- **Kollaps:** 12 Schaden im Radius 6, 2 s Langsamkeit II, Druckwelle nach außen.
- Keine Blockschäden.

---

## 4. Arius' Kampfhandschuhe

### 4.1 Ausrüstungslösung
- **Ein Item** (`hha_arius:arius_gauntlets`) in der **Haupthand** stellt **beide Hände** dar. Es gibt keinen zusätzlichen
  Rüstungsslot.
- Der Renderer zeichnet die Handschuhe direkt auf beide Arme, in erster und dritter Person, für alle Spieler sichtbar.
  Das Item selbst erscheint in der Hand nicht.
- Solange die Handschuhe gehalten werden, **ruht die Nebenhand**: Ihr Item wird nicht gezeichnet und nicht benutzt. So
  schneidet nichts durch die Handschuhe, und der Rechtsklick gehört eindeutig den Handschuhen. Ein Totem wirkt
  weiterhin.
- Werte: Haltbarkeit 2031 (Netherite), mit Herzfrost reparierbar, Haltbarkeit/Reparatur verzauberbar (Unbreaking,
  Mending). Jeder Treffer im Nahkampf kostet 1 Haltbarkeit.
- **Modus am Stapel** (`gauntlet_mode`): Andere sehen ihn als **Faust** (Nahkampf) bzw. **offene, gegliederte Finger
  mit leuchtender Yang-Sonne** (Magie). Das HUD zeigt ihn dezent rechts unter dem Fadenkreuz.
- **Moduswechsel** (Modus-Taste): nur aus der Ruhe, nie mitten in einer Aktion. Er dauert 0,4 s, die Hände schließen
  bzw. öffnen sich sichtbar. Wer im Rad eine Technik wählt, wechselt automatisch in den Magiemodus.

### 4.2 Nahkampfmodus: Moveset
Kompakt und gut beherrschbar: **Linksklick = Schlagfolge, Rechtsklick = Tritt, Schleichen + Rechtsklick = Parade.**

| Aktion | Eingabe | Ausholen / Treffer / Erholung (Ticks) | Schaden | Reichweite, Winkel, Ziele | Wirkung |
|---|---|---|---|---|---|
| **Jab** (links) | Links | 2 / Tick 2 / 5 | 0,5 × G | 2,9, ±32°, 1 | kaum Rückstoß |
| **Cross** (rechts) | Links nach Jab | 3 / 3 / 6 | 0,65 × G | 3,0, ±32°, 1 | |
| **Körperhaken** (links) | Links nach Cross | 4 / 4 / 9 | 0,9 × G | 2,7, ±42°, 1 | leichter Stoß |
| **Seitentritt** | Rechts (aus der Folge: Ausholen 4 statt 6) | 6 / 6 / 10 | 1,0 × G | 3,4, ±30°, bis 2 | Rückstoß, schafft Raum |
| **Drehtritt** (Abschluss) | Rechts direkt nach dem Haken | 8 / 8 / 14 | 1,6 × G | 3,4, ±75°, bis 3 | ganze Drehung, Rückstoß im Bogen |
| **Parade** | Schleichen + Rechts | Fenster 6, Erholung 16 | – | Treffer von vorn | fängt einen Nahkampfschlag ab (Geschosse lösen sich auf), +20 Resonanz |
| **Konterstoß** | automatisch nach gelungener Parade | 2 / 2 / 8 | 1,2 × G | 3,0, ±40°, 1 | trifft nur, wenn der Angreifer noch vorn in Reichweite ist |

- **G = Grundschaden 6** plus Angriffsstärke über 1, z. B. Stärke: +3 je Stufe. Eine volle Folge Jab, Cross, Haken
  und Drehtritt macht etwa 22 Schaden in 2,6 s. Das liegt im Bereich der Endgame-Schwerter, aber mit Bewegung und
  Rückstoß.
- **Verbindungsfenster:** 8 Ticks nach Ende einer Aktion. Danach beginnt die Folge neu.
- **Kein Vorteil durch Dauerklicken:** Eingaben während einer Aktion werden höchstens einmal vorgemerkt, und nur in den
  letzten 4 Ticks der Erholung. Alles andere verfällt. Jede Aktion hat feste Ausholzeit und Erholung.
- **Bewegung:** Während der Aktionen bist du langsamer (Jab 75 %, Tritte 40–50 %), Sprinten endet. Es gibt keine
  Ausfallschritte, die dich an Gegner heranziehen.
- **Ausrichtung:** Treffer brauchen Reichweite, Blickwinkel und freie Sicht (kein Treffen durch Wände). Schläge zählen
  den vollen Blick, Tritte nur die Waagerechte.
- **Kein Heranziehen:** Kein Gegner wird in eine Trefferanimation gezogen oder festgehalten.
- **Keine Ketten:** Rückstoß von Tritten und Konter trifft dasselbe Ziel höchstens alle 1,5 s. Es gibt keine Betäubung.
- **Große Gegner:** Bosse, Wächter, Puppen des Eirholm-Kampfs und Wesen über 2,6 Blöcke Höhe oder mit ab 150 Leben
  werden nie bewegt.
- **Blöcke:** Linksklick auf einen Block in Reichweite baut normal ab. Rechtsklick auf einen Block in Reichweite
  benutzt ihn normal (Türen, Truhen).
- **Animation:** Jede Aktion ist eine eigene Körperanimation, die der Server an alle schickt. Der Treffer liegt genau
  auf dem Schlagmoment. In erster Person sind beide Hände sichtbar, die Tritte zeigen Gegenbewegung der Arme. In dritter
  Person laufen Tritte mit vollem Körper, der Drehtritt mit einer ganzen Drehung.

### 4.3 Magiemodus und Auswahlrad

**Rad (Technik-Taste halten):**
1. **Stufe 1:** drei Schulen, Wind oben, Eis rechts unten, Yang links unten.
2. **Stufe 2:** Maus weiter nach außen ziehen oder Linksklick auf eine Schule. Dann erscheinen deren Techniken als eigener
   Kreis, höchstens 5 Einträge gleichzeitig. Angezeigt werden Abklingzeit, Kosten, Art, Rolle und eine Kurzbeschreibung.

**Bedienung im Rad:**

| Aktion | Wirkung |
|---|---|
| Taste loslassen auf einer Technik (oder Linksklick) | wählt die Technik |
| Loslassen auf einer Schule | wählt deren zuletzt benutzte Technik |
| Kurz tippen ohne Mausbewegung | wechselt zur vorigen Technik |
| Loslassen in der Mitte oder Esc | bricht ab |
| Rechtsklick | in Stufe 2 zurück, sonst Abbruch |

- Die Wahl bleibt gespeichert, in der Spielerdatei auch über Tod und Neustart. **Häufiges Wirken braucht kein Rad.**
- Das Rad **pausiert nichts**, auch im Einzelspieler nicht. Nur du selbst stehst, solange es offen ist.

**Wirken (Rechtsklick außerhalb des Rads):**

| Art | Bedienung | Kosten | Ungültig |
|---|---|---|---|
| **Tippen** | drücken = sofort | beim Wirken | – |
| **Laden** | halten = laden, loslassen = wirken. HUD-Balken mit Marke bei Mindestladung, gold bei voll. Nach Volladung höchstens 2 s halten, dann löst es aus | erst beim Loslassen, steigt mit der Ladung | zu kurz = verpufft ohne Kosten, 0,5 s Sperre |
| **Zielen & setzen** | halten = **Vorschau** der Blöcke im Spiel (Zellen leuchten in Schulfarbe), loslassen = setzen | erst beim Setzen | kein gültiger Ort = Hinweis „Kein gültiger Ort“, nichts passiert, keine Kosten |
| **Halten** (Schild) | halten = aufrechterhalten, loslassen = Ende | 5 sofort, dann 8/s, dazu je abgefangenem Treffer 2 je Schadenspunkt | – |
| **An/aus** (Gleiter) | drücken = an; erneut drücken, Schleichen, Landen, Wasser = aus | 10 sofort, dann 4/s | – |

- **Abbruch:** Linksklick bricht Laden, Zielen, Schild und Gleiter ab, ohne Kosten.
- **Wechsel zurück zum Nahkampf:** Modus-Taste, aus der Ruhe.
- **Itemwechsel:** Wechsel des Haupthand-Items (anderer Slot oder anderes Item) bricht jede laufende Handschuh- oder
  Solvane-Aktion sofort ab, ohne Kosten. Der Gleiter setzt dich mit 2 s Sanftem Fall ab. Danach gilt 0,4 s
  **Ziehzeit**, in der keine Handschuh- oder Solvane-Aktion beginnt. Das gilt auch zwischen zwei Exemplaren desselben
  Items, und die Abklingzeiten hängen am Spieler, nicht am Stapel.
- **Bauwerke** entstehen aus eigenen Funktionsblöcken (Kristalleis, Yang-Licht):
  - nur in Luft, nie in Wesen;
  - kein Item, keine Drops, nicht verschiebbar;
  - sie verschwinden von selbst über einen gespeicherten Block-Tick, auch nach Neustart;
  - sie respektieren Abenteuermodus, Spawnschutz und Weltgrenze;
  - in der Eirholm-Arena sind sie aus, ebenso der Gleiter (Config `art_constructs_in_arena`).

### 4.4 Zauberliste

| Schule | Technik | Art | Rolle | Kosten | Abkl. | Wirkung |
|---|---|---|---|---|---|---|
| **Wind** | **Windklinge** | Tippen | Angriff | 8 | 0,75 s | Sichel fliegt 16 Blöcke, 5 Schaden, durchschlägt 1 Ziel, kleiner Stoß |
| | **Windsprung** | Tippen | Bewegung | 12 | 2 s | hoch (1,0) und nach vorn (0,7); nächster Sturz ohne Schaden; in der Luft einmal bis zur Landung |
| | **Sturmstoß** | Laden 0,3–1,2 s | Verteidigung | 15–30 | 4 s | Windkegel 7 Blöcke ±45°: 1,5–3 Schaden, Rückstoß nach Ladung (Spieler 60 %, große Gegner nie), feindliche Geschosse fort |
| | **Windsog** | Tippen | Nutzen | 4 | 2 s | zieht Gegenstände und Erfahrung aus 10 Blöcken heran, löscht eigenes Feuer |
| **Eis** | **Frostsplitter** | Tippen | Angriff | 10 | 1 s | 3 Splitter im Fächer (±7°), je 3 Schaden, 1,5 s Langsamkeit I |
| | **Eiswand** | Zielen & setzen | Verteidigung, Bauwerk | 20 | 8 s | 5 × 3 Wand aus Kristalleis quer zum Blick, bis 14 Blöcke entfernt, wächst von unten, 10 s |
| | **Frostfeld** | Zielen & setzen | Kontrolle | 20 | 10 s | Kreis R 4 bis 14 Blöcke entfernt, 5 s lang Langsamkeit II für Gegner darin, stilles Wasser gefriert zu brüchigem Eis |
| | **Eispanzer** | Tippen | Verteidigung | 25 | 20 s | Hülle fängt 6 Schaden (3 Herzen) ab, 8 s |
| **Yang** | **Yang-Kugel** | Laden 0,2–1,5 s | Angriff | 10–25 | 1,5 s | Lichtkugel 24 Blöcke, beim Aufprall Aufblühen R 2–3, 4–10 Schaden, keine Blockschäden |
| | **Yang-Schild** | Halten (max. 5 s) | Verteidigung | 5 + 8/s | 3 s | Lichtscheibe vorn (±60°): fängt Treffer und Geschosse ab, jeder kostet 2 Resonanz je Schadenspunkt; reicht es nicht, bricht der Schild. Explosionen gehen durch |
| | **Lichtplattform** | Zielen & setzen | Bauwerk | 12 | 1,5 s | 3 × 3 an einer Wand, in der Luft 5 Blöcke voraus, oder im Sprung beim Blick nach unten unter dir. 15 s, höchstens 3 gleichzeitig (die älteste verschwindet) |
| | **Lichtbrücke** | Zielen & setzen | Bauwerk | 20 | 5 s | 1 Block breit, bis 16 Blöcke geradeaus auf Fußhöhe, wächst 2 Blöcke je Tick, endet am ersten Hindernis, 15 s |
| | **Lichtgleiter** | An/aus (max. 8 s) | Transport | 10 + 4/s | 10 s | du gleitest auf einer Lichtscheibe entlang des Blicks (0,55 Blöcke/Tick), immer leicht sinkend. Steigen geht nicht, also ist es kein Fliegen |

**Handhaltungen:**
- Tippen: kurzer Stoß mit einer Hand.
- Laden: beide Hände sammeln die Energie, ihr Licht wächst.
- Zielen: eine Hand zeigt auf den Ort.
- Schild: beide Handflächen vorn.
- Gleiter: Arme zum Gleichgewicht.
- Im Magiemodus sind die Finger offen und die Yang-Sonne auf dem Handrücken leuchtet.

Kleine Zauber haben keine erzwungenen langen Animationen.

---

## 5. Solvane

Solvane wurde aus dem **Herzen eines uralten weißen Drachen** geschmiedet. Im Bosskampf zeigt die Klinge diese Drachenform;
der Spieler trägt ein Abbild davon.
- **Form:** ein **Langschwert** mit anderthalb Hand langem, dunkel umwickeltem Griff und langer, weißer Klinge.
- **Klinge:** Hohlkehle mit Lichtfluss, Schuppengravur an der Wurzel, Eisglanz an den Schneiden.
- **Parierstange:** symmetrisch, mit Rautenstern und Herz-Juwel.
- **Knauf:** Rautenknauf. Werte wie bisher (9 Schaden,
1,6 Angriffe/s). Die bisherigen Mechaniken bleiben:
- **Klare Schneide:** Nur volle Treffer zählen. Sie geben +2 Frostschaden, und jeder 3. volle Treffer am selben Ziel
  zerspringt.
- **Konterhaltung:** Rechtsklick, 8 Ticks.

### 5.1 Waffentechnik „Klarer Schnitt“ (Technik-Taste)
Eine begrenzte Spieler-Version von Arius' gewaltigem Schnitt:
1. **Haltung:** Taste halten. Du bist stark verlangsamt (−60 %), das Schwert ist beidhändig zurückgezogen.
2. **Sammeln:** Energie läuft an der Schneide entlang. Mindestladung 0,8 s, Volladung 2 s.
3. **Ausrichten:** Der Blick bestimmt die Ebene. Senkrecht ist Standard, **Schleichen beim Loslassen = waagerecht**. Du
   selbst siehst die Schnittebene als dünnen Rahmen mit Mittellinie, wachsend mit der Ladung. Unter der Mindestladung
   ist sie blass.
4. **Freigabe:** ein flacher, räumlich klar begrenzter Schnitt. Länge 6–12, Breite 3–5, Dicke etwa 1 Block. Er
   erscheint als helles Lichtblatt mit Mittellinie, ohne Explosion und ohne Kugel. Gegner in der Ebene erleiden
   **10–20 Schaden** und werden zur Seite gestoßen (große nie). Feindliche Geschosse in der Ebene werden zerschnitten.
5. **Erholung:** 1 s ohne Angriffe, verlangsamt. Danach Nachhall. Abklingzeit **12 s**.

- **Kosten:** 50 Resonanz, abgezogen erst bei der Freigabe.
- **Abbruch:** Linksklick, Itemwechsel, Tod oder Weltwechsel kosten nichts. Zu frühes Loslassen verpufft kostenlos.
- **Nach Volladung:** höchstens weitere 4 s halten, dann löst der Schnitt von selbst aus.
- **Vergleich:** Arius schneidet im Bosskampf einen ganzen Tempel in zwei Hälften. Der Spieler schneidet höchstens
  12 × 5 Blöcke und **24 Blöcke**.

### 5.2 Zerstörung (ehrlich definiert)
Es gibt drei verschiedene Dinge:

1. **Tatsächlicher Abbau** (Survival):
   - Getroffene Blöcke werden **vollständig** entfernt, wie beim Abbauen. Es entstehen keine halbierten Blöcke.
   - **Ebene:** Nur Blöcke, deren Mitte in der Schnittebene liegt (±0,55), höchstens `clear_cut_block_max` = 24, vom
     Spieler aus der Reihe nach.
   - **Materialien:** Härte 0 bis `clear_cut_max_hardness` = 3. Das sind Blätter, Holz, Erde, Sand, Stein,
     Tiefenschiefer und Erze. Obsidian, Grundgestein, Barrieren und alles Unzerstörbare nie, ebenso nichts aus
     `wither_immune` oder `dragon_immune`.
   - **Nie** Blöcke mit Block-Entity (Truhen, Fässer, Öfen, Schilder, Spawner …). Ein Behälter samt Inhalt kann also
     nicht vernichtet werden.
   - **Nie** zweiteilige Blöcke (Türen, Betten, hohe Pflanzen).
   - **Schutz:** Abenteuermodus, Spawnschutz und Weltgrenze werden geachtet. Fabrics `PlayerBlockBreakEvents.BEFORE`
     wird je Block gefragt, damit Schutz-Mods einzelne Blöcke verbieten können. In der Eirholm-Arena gibt es nie
     Blockschäden.
   - **Beute:** genau wie beim Abbau mit Solvane, mit Verzauberungen. Was ein Schwert nicht ernten kann (Stein, Erze,
     Tiefenschiefer), wird zerstört, **ohne** etwas fallen zu lassen. Holz, Blätter und Erde lassen ihre normalen Drops
     fallen. Jeder Block wird genau einmal entfernt, es gibt keine Duplikation.
   - **Bäume:** Die Ebene durchtrennt den getroffenen Stammabschnitt und die Blätter in der Ebene. Darüber
     liegendes Holz bleibt stehen, wie beim normalen Abbau. Es gibt kein automatisches Fällen.
   - **Einstellbar:** `clear_cut_blocks` (an/aus), `clear_cut_block_max`, `clear_cut_max_hardness`.
2. **Zeitweilige Darstellung:** Jeder entfernte Block erscheint 0,8 s lang als **zwei Hälften**, die entlang der
   Schnitt-Normalen auseinandergleiten, leicht kippen, absinken und schrumpfend verschwinden. Das ist **nur Grafik**;
   die Welt enthält bereits Luft.
3. **Vorbereitete Arena-Strukturen:** Der Tempel von Eirholm wird im Bosskampf von Arius geteilt (eigene Inszenierung,
   `SliceRender` und `ArenaEdits`, nach dem Kampf wiederhergestellt). Das hat mit der Spielertechnik nichts zu tun.

---

## 6. Gemeinsames Balancing

**Ressourcenfluss:** Ein typischer Kampf mit vollem Set beginnt bei 50 Resonanz.
- Mit Solvane bringen 4 volle Treffer etwa +50. Das reicht für einen Klaren Schnitt.
- Mit den Handschuhen bringt eine Folge aus Jab, Cross, Haken und Drehtritt etwa +23 (×1,25 = 29).
- Das Weiße Loch braucht 100, also etwa 6–8 gute Treffer ohne andere Ausgaben.

**Gleichzeitig starke Fähigkeiten:**
- Durch den Fokus geht nur eine Sache auf einmal.
- Der Nachhall verhindert „Kern + Schnitt + Loch“ in einer Sekunde.
- Die gemeinsame Ressource reicht nicht für alles: Schnitt und Loch zusammen kosten 150 bei 100 Maximum.

**Missbrauch, den das System abfängt:**

| Fall | Gegenmittel |
|---|---|
| Aufladung abbrechen | kostet nichts, gibt aber 0,5 s Sperre auf diese Technik. Spammen bringt also nichts |
| Waffen- oder Moduswechsel während einer Aktion | bricht ab, dann Ziehzeit 0,4 s. Abklingzeiten hängen am Spieler |
| Mehrere Exemplare desselben Items | Abklingzeiten liegen am Spieler bzw. in der Abklinggruppe des Items, die Ziehzeit gilt auch hier |
| Resonanz an harmlosen Zielen aufbauen | Tiere, Rüstungsständer und Verbündete geben nichts. Alles über dem Ruhepunkt fließt nach dem Kampf ab |
| Dauerhafte Schilde | max. 5 s, 8/s plus 2 je Schadenspunkt, 3 s Abklingzeit |
| Dauerhafte Plattformen | 15 s, max. 3, Abklingzeit, kosten Resonanz, die nur langsam nachkommt |
| Unbegrenzte Brücken | 16 Blöcke, 15 s, 5 s Abklingzeit, 20 Resonanz |
| Dauerflug | Der Gleiter sinkt immer, max. 8 s, 10 s Abklingzeit, in der Arena aus |
| Betäubungsketten im PvP | Es gibt keine Betäubung. Langsamkeit höchstens Stufe II und kurz. Rückstoß gegen Spieler gedämpft (Sturmstoß 60 %, Weißes Loch 50 %, Kollaps 0,5). Je Ziel höchstens ein starker Rückstoß pro 1,5 s |

**Vergleich mit den bestehenden Endgame-Sets** (Standardwerte):

| | Größte Einzeltechnik | Abklingzeit | Bemerkung |
|---|---|---|---|
| HHA Hell/Heaven | Strahl 60 Schaden | 50 s | dazu Ultra-Modus 60 s / 5 min |
| HHA Void | Void Collapse 15 + Sog | 120 s | Meteor 16 / 35 s |
| HHA Blood | Nihil 8 + 8 | 45 s | |
| **Arius** | **Weißes Loch 12 + Sog** | **120 s**, braucht volle Resonanz | Klarer Schnitt 10–20 alle 12 s, aber nur mit 50 Resonanz aus Treffern |

- **Schaden:** Spitzen unter Hell/Heaven, auf Void-Niveau. Die Stärke liegt im Rhythmus (Resonanz aus Können), nicht
  im Einzelschlag.
- **Überleben:** Der Weiße Kern (Resistenz I, 3 s) und der Frostschritt (0,3 s Nahkampfschutz) sind schwächer als
  Heavens Göttlicher Schild. Der Yang-Schild kostet viel Resonanz.
- **Bewegung:** Frostschritt, Windsprung und Gleiter sind besser als bei den anderen Sets, aber kurz und ohne echtes
  Fliegen.
- **Außerhalb des Kampfs:** stärkster Nutzen aller Sets (Plattform, Brücke, Windsog, Spalten von Holz und Stein), aber
  zeitlich begrenzt und ohne dauerhafte Blöcke.

---

## 7. Verbindung zum Fähigkeitssystem

- **Rüstung:** HHA-Addon-API.
  - Abilities: `white_core` (Ability 1, Brust), `frost_step` (Ability 2, Beine), `white_hole` (Ability 3, volles Set).
  - Der HHA-Ultra-Cast wird bei vollem Set über `HhaEvents.ABILITY_CAST` umgelenkt, wie bei Void.
  - Abklingzeiten zeigt die HHA-Leiste: Kern an der Brust, Frostschritt an den Beinen, Weißes Loch und Klarer Schnitt
    über `use_cooldown`-Gruppen.
  - Dynamische Lore und Config-Schlüssel kommen ebenfalls über HHA.
- **Handschuhe und Solvane:** eigene Pakete.
  - Eingaben: `art_input` (Client → Server), maßgeblich ist der Server.
  - Animationen: `art_anim` an alle in Sichtweite.
  - HUD und Rad: `art_self`.
  - Blockhälften: `art_cut`.
- **Code:**

  | Bereich | Dateien |
  |---|---|
  | Server | `gear/art/` mit `Focus`, `GauntletCombat`, `SpellCasting`, `Placement`, `Bolts`, `Constructs`, `SolvaneArt`, `WhiteHole` |
  | Client | `gear/client/art/` mit `ArtClient`, `SpellWheelScreen`, `ArtAnims`, Posen und Rendering |
  | Effekte | `client/fx/ArtEffects.kt` |

---

## 8. Im Spiel zu prüfen
Siehe `TESTEN.md`, Abschnitt „Ausrüstung 1.1“.
