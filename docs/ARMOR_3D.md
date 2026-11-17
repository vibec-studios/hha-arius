# Drachenherz-Wacht (3D-Rüstung) und Solvane V6

Paket V6 aus `tmp/visual/V6_ruestung_schwert.md`. Die Eirholm-Rüstung ist kein umgefärbtes Netherite mehr, sondern ein
eigenes 3D-Modell an den Knochen des Trägers (wie die Handschuhe). Solvane ist deutlich länger und lebendiger.
Nichts davon ist im Spiel gesehen; geprüft wurde mit Vorschaubildern und `compileKotlin`/`compileJava`.

## Gestaltung

Lore: Solvane wurde aus dem Herzen eines uralten weißen Drachen geschmiedet und kann dessen Drachenform imitieren. Die
Rüstung greift diese Form auf: Perlweiß mit eisblauen Schatten, Gold nur als Kante (gedecktes Altgold), Navy-Leder darunter.

| Teil | Inhalt |
|---|---|
| Helm | offener Helm, Krone aus drei nach hinten gekämmten Eishörnern je Seite (links das große abgebrochen), Wangenschilde als drei Schuppenplatten, goldener Vierstern auf der Stirn, Stirnreif mit Eislinie, Nackenlamellen. Krone ≤ 6,4 Einheiten über dem Kopf |
| Brust | Pektoralschilde, Bauchschilde (quer liegende Ventralplatten des Drachen, schuppenartig vorgekippt), Herz-Juwel im Rautenstern (goldener Kern, blauer Rand, atmet), Schuppen-Rückenplatte mit flachem Rückgrat (Umhang), Schulterschalen mit Lamellen, gefaltete Schwingenplatten hinten an den Schultern (Feder), Oberarmschiene. Unterarmschiene nur ohne Handschuhe in der Hand |
| Beine | Gürtel mit Schnalle, Schuppen-Tassetten vorn (je Bein) und hinten (mit Schwanzschuppe), Schenkelplatten, Kniekacheln wie Drachenpranken mit goldenen Krallen, Schienbeinschiene |
| Stiefel | Beinröhre aus Lamellen, Goldstulpe, Fuß mit Klauenspitze (drei Krallen), Fersensporn |
| Leuchten | Herz-Juwel (Puls ~4 s), feine Eislinien an Schuppenkanten, Hornspitzen kaum. Kein leuchtendes Gold |

## Dateien

- `tools/assets/armor_model.py`: Modell und Malerei (HD, 2 Texel je Einheit, Atlas 128 × 128 Einheiten = 256 px).
  Registriert in `boss_models.py` als `armor`. Erzeugen: `python tools/assets/boss_models.py --only armor`
  → `client/model/armor/ArmorModelData.kt`, `textures/entity/eirholm_armor.png`, `eirholm_armor_glow.png`.
  `_check` prüft: Raster, Knochenzuordnung, nichts in der Handschuh-Stulpe, Rücken flach genug für den Umhang,
  Kronenhöhe, keine Fläche in derselben Ebene wie Steves Haut- oder Überzugsschicht (Flimmern).
- `tools/assets/armor_preview.py`: Vorschau ohne Spiel (Rasterer mit der echten UV-Abbildung von
  `ModelPart.Cuboid`, Entity-Licht, eyes-Mischung) und die 32er-Icons. `python tools/assets/armor_preview.py`.
- `client/model/armor/ArmorRender.kt`: Registrierung für alle Renderer mit `BipedEntityModel`
  (Spieler, Mannequin, Rüstungsständer, Zombies, Skelette, Piglins …), Item-Prüfungen.
- `client/model/armor/ArmorFeature.kt`: Feature-Renderer, Ebenen, Beinfolge, Federn.
- `mixin/gear/client/CapeFeatureArmorMixin.java`: Umhang bekommt über dem Kürass denselben Abstand wie über
  Vanilla-Rüstung (Vanilla fragt dafür die `humanoid`-Ebene ab, die es nicht mehr gibt).
- Entfernt: `EquipmentRendererGlowMixin` (samt Eintrag in `hha_arius.mixins.json`), `EirholmGlow` in `GearClient.kt`,
  `equipment/eirholm.json`, die vier Equipment-Texturen und die 44 Trim-Varianten der Item-Modelle.

## Technik

- **Knochenraum**: Jedes Teil hat Drehpunkt 0 im Raum seines Biped-Knochens (`head`, `body`, `rightArm` …).
  Der Renderer wendet `root` und den Knochen an und zeichnet das Teil. Schmale Arme: quer 0,75 und 0,25 versetzt.
- **Ebenen**: Grundtextur über `RenderLayers.armorCutoutNoCull` (Rüstungs-Layering), Verzauberungsglanz über
  `armorEntityGlint`, Leuchtmaske über eine eigene `RenderLayer`: `ENTITY_EYES` + `VIEW_OFFSET_Z_LAYERING` +
  translucent. `RenderLayers.eyes()` allein läge hinter der Rüstung und fiele durch den Tiefentest.
- **Vanilla-Ebene aus**: Die Datei `equipment/eirholm.json` ist gelöscht statt `"layers": {}` – der Codec von
  `EquipmentModel` verlangt eine nicht leere Ebenen-Liste (`Codecs.nonEmptyMap`), die leere Datei würde beim Laden als
  Fehler gemeldet. Ohne Datei liefert `EquipmentModelLoader` ein leeres Modell: Vanilla zeichnet nichts, auch keinen Trim.
- **Trim**: Auf dieser Rüstung nicht mehr sichtbar. Das Trim-Muster „Weiße Klinge“ selbst bleibt (für andere Rüstungen).
  Die Icons zeigen keine Trim-Varianten mehr.
- **Unsichtbarkeit**: unsichtbarer Träger → nichts. Achtung, Vanilla zeichnet Rüstung auch an unsichtbaren Trägern;
  das ist hier bewusst anders (Vorgabe). Umstellen: in `ArmorFeature.render` die Zeile `if (state.invisible) return`.
- **Ego-Sicht**: unverändert; Vanilla zeichnet den Arm ohne Rüstung, die Handschuhe ihre eigenen Arme.
- **Beinfolge der Tassetten**: Gelenk an der Gürteloberkante (y 10, z −3,5). Vordere Tassette folgt ihrem Bein nach vorn
  (`TASSET_FOLLOW` 1,08 × Beinwinkel relativ zum Rumpf), hintere dem weiter zurückgesetzten Bein. Nachgerechnet: bis
  1,2 rad Beinschwung sticht der Oberschenkel nicht durch. Darüber (Sitzen ~1,4 rad) kann es am Gelenk leicht schneiden.
  Dieselben Werte stehen in `armor_preview.py`.
- **Federn** (`ArmorMotion`, je Spieler-ID, also Spieler und Mannequins): Tassetten ~1,2 Hz, Schwingenplatten ~2 Hz.
  Antrieb aus Vorwärtsbeschleunigung, Fahrtwind, Sprung/Landung und Armschwung; begrenzt und so geklemmt, dass sie nie in
  das vorschwingende Bein gedrückt werden. Zeitschritt aus `state.age`; ein zweites Zeichnen im selben Bild (Inventar)
  schreitet nicht weiter. Rüstungsständer und Mobs haben keine ID im Renderzustand und bekommen nur die Beinfolge.
- **Unterarmschiene**: nur ohne Handschuhe in der Haupthand (mit Handschuhen bleibt am Arm nur die Oberarmschiene).

## Solvane

- Klinge 25 statt 18,5 lang (+35 %), Spitze bei y 28, Wurzel breiter (Schulter) mit Drachenschuppen-Gravur.
- Hohlkehle als eigenes Element mit `light_emission` 10 und eigener animierter Textur `item/solvane_fuller.png`
  (72 × 72 je Frame, 16 Frames, frametime 4, interpoliert): ein weiches Lichtband wandert in ~3 s die Klinge hinauf.
  Der Rest ist statisch in `item/solvane_3d.png` (jetzt 4 Texel je Einheit, 256 × 256).
- Herz-Juwel im Rautenstern (Element `heart`, `light_emission` 13, goldener Kern, blauer Rand).
- Eisglanz an den Schneiden, vereinzelte Frostfunken, kühler Widerschein zur Spitze.
- Ausrichtung und Griffpunkt unverändert (aufrecht, Hand bei y −0,5). Anzeige: 3. Person 0,9 statt 0,95 (Spitze bei
  hängendem Arm ~1,6 Blöcke vor dem Körper, nie unter Fußhöhe), Ego-Sicht 1,02 statt 1,15, Boden 0,36.
  `ArtPose.GRIP/BLADE_FROM/BLADE_TO` (Schwertspur) sind nachgezogen.

## Vorschauen

- `tmp/preview/armor/armor_figure.png` – ganze Figur vorn, schräg, seitlich, hinten; oben ohne, unten mit Handschuhen
- `tmp/preview/armor/armor_motion.png` – Gehen (Tassetten folgen) und erhobene Arme
- `tmp/preview/armor/armor_night_silhouette.png` – Nacht (nur Leuchten) und Schattenriss
- `tmp/preview/armor/armor_pieces.png` – jedes Teil einzeln, `armor_distance.png` – Spielentfernung
- `tmp/preview/armor/armor_icons.png`, `tmp/preview/gear/icons_inventory.png` – Icons
- `tmp/preview/gear/solvane_3d.png`, `tmp/preview/gear/solvane_flow.png` – Schwert und Lichtfluss

## Offen / im Spiel prüfen

- Leuchtebene (eigene RenderLayer) bei Tag/Nacht, Glanz verzauberter Teile, Umhang über dem Kürass.
- Federn: Stärke beim Laufen, Springen, Landen; Rüstungsständer und Zombies (nur Beinfolge).
- Solvane in der Hand (3. Person, Ego-Sicht), Lichtfluss der Hohlkehle, Leuchten von Hohlkehle und Juwel.
