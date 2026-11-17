# Arbeitsvorlage für alle Teilaufgaben (verbindlich)

> **Stand 1.2.0:** Die Rüstung ist keine Equipment-Textur mehr, sondern eine eigene 3D-Rüstung (`docs/ARMOR_3D.md`). Der Drache heißt Solvane (Code: `nivaris`).


Projekt: `C:\Users\henny\Desktop\HHA_Arius`, Fabric-Addon (Kotlin, Minecraft 1.21.11, Yarn build.6) für H&H Armors.
HHA selbst liegt unter `C:\Users\henny\Desktop\HHA` und ist **nur Referenz**: nie ändern, nie dort bauen.

## Erst lesen
1. `docs/KONZEPT.md` (Gesamtkonzept), `docs/STIMME.md` (Texte/Herkunft)
2. Verträge: `src/main/kotlin/dev/henny/hha_arius/boss/Poses.kt`, `boss/AriusTimings.kt`, `boss/BossEntities.kt`,
   `arena/ArenaLayout.kt`, `gear/Gear.kt`, `boss/Encounters.kt`
3. Charakterbogen von Arius (Bild): `C:\Users\henny\.t3\userdata\attachments\d1e7f525-3478-44d9-938e-a7253840ca9c-8cec1ae7-9ad6-403a-b0a2-17a29cbd4a3d.png`

## Regeln für alle
- **Parallelbetrieb im selben Ordner.** Fünf Teilaufgaben laufen gleichzeitig. Ändere **nur deine eigenen Dateien**
  (siehe deine Aufgabe). Kein `git commit/stash/checkout/reset/clean`, das macht der Hauptagent.
- **Bauen nur mit Sperre:** `python tools/assets/gradle_locked.py compileKotlin` (bzw. `build`). Scheitert der Build an
  Dateien, die nicht dir gehören, warte ein paar Minuten und versuche es erneut. Repariere sie nicht.
- **Minecraft nie starten** (`runClient`, `runClientGameTest`, `runServer` sind verboten). Der Nutzer testet selbst.
- **Sprachdateien:** Schreibe deine Schlüssel nach `tools/lang/<bereich>.en_us.json` und `tools/lang/<bereich>.de_de.json`
  (flaches JSON). `assets/hha_arius/lang/*.json` erzeugt der Hauptagent daraus. Deutsch mit echten Umlauten.
- **API-Prüfung ohne Build:** Yarn-Mappings `C:\Users\henny\.gradle\caches\fabric-loom\1.21.11\net.fabricmc.yarn.1_21_11.1.21.11+build.6-v2\mappings.tiny`.
  Bekannte Umbenennungen seit 1.21.9: `getEntityWorld()`, `getEntityPos()`, `velocityDirty`, `World.getServer()` nullable,
  Items über `Item.Settings().registryKey(key)`. Minecraft-Quellen: nach dem ersten Build unter `.gradle/loom-cache`.
  HHA-Quellen sind die beste Vorlage für Muster in dieser Version (z. B. `HHA/src/main/kotlin/dev/henny/hha/client/model/KronosModel.kt`).
- **Python:** 3.13 ohne PIL/numpy. Texturen über `tools/assets/texkit.py` (rechnet Pixel in Python, schreibt per `mctex batch`).
  `mctex` liegt im PATH. Vorschau-PNGs per `tools/assets/preview.py` und Read-Tool ansehen.
- **Stil (Nutzervorgaben):** Effekte stark und glatt, kein Pixel-Look bei Effekten; Welt nur aus Vanilla-Blöcken;
  eigene Modelle mit klarer Silhouette (keine Vanilla-Mob-Optik); Rüstung bleibt Netherite-Silhouette.
- **Ehrlich berichten:** Am Ende kurz auflisten: erstellte Dateien, was gebaut/geprüft wurde, was offen ist. Nichts als
  „im Spiel getestet“ bezeichnen.

## Namen (verbindlich)

### Items (`hha_arius:<id>`)
| id | Was |
|---|---|
| `eirholm_helmet`, `eirholm_chestplate`, `eirholm_leggings`, `eirholm_boots` | Eirholm-Rüstung, Equipment-Asset `hha_arius:eirholm` |
| `solvane` | Solvane (Abbild), Schwert mit Konterhaltung |
| `nivaris_wingscale` | Nivaris' Schwingenschuppe (Relikt) |
| `eirholm_seal` | Siegel von Eirholm (Beschwörung) |
| `echo_of_eirholm` | Echo von Eirholm (Training) |
| `heartfrost` | Herzfrost (Material) |
| `nivaris_scale` | Nivaris-Schuppe (Material) |
| `eirholm_upgrade_smithing_template` | Schmiedevorlage Netherite → Eirholm |
| `white_blade_armor_trim_smithing_template` | Echte Trim-Vorlage „Weiße Klinge“, Trim-Muster `hha_arius:white_blade` |

### Texturpfade (unter `assets/hha_arius/textures/`)
- Items: `item/<id>.png`; Solvane zusätzlich 3D-Handmodell `models/item/solvane_3d.json` (Hand/Boden 3D, GUI/Rahmen flach
  über `items/solvane.json` mit `select` auf `display_context`, Vorbild: `hha-blood` Blood Sword)
- Rüstung: `entity/equipment/humanoid/eirholm.png`, `entity/equipment/humanoid_leggings/eirholm.png`,
  Leuchtnähte `entity/equipment/humanoid/eirholm_glow.png`, `entity/equipment/humanoid_leggings/eirholm_glow.png`
- Trim: `trims/entity/humanoid/white_blade.png`, `trims/entity/humanoid_leggings/white_blade.png` (Graustufen-Palette
  wie Vanilla, damit Trim-Materialien färben)
- HUD-Sprites: `gui/sprites/hud/white_line.png`, `frost_step.png`, `white_core.png`, `wingscale.png`, `counter.png`
- Bosse: `entity/arius_dark.png`, `entity/arius_white.png` (+ `_glow.png`), `entity/nivaris.png` (+ `_glow.png`,
  `nivaris_resonance.png` = zusätzliche Leuchtmaske der Membranlinien)
- Mod-Icon: `assets/hha_arius/icon.png` (128 × 128)

### Sounds (`hha_arius:<id>`, Kategorie in Klammern)
Musik (record/music, Schleife): `music.temple`, `music.glacier`, `music.heart`, `music.finale`
Arius: `arius.slash`, `arius.thrust`, `arius.parry`, `arius.kick`, `arius.wind_step`, `arius.guard`, `arius.ice_wall`,
`arius.frost_strip`, `arius.stab_ground`, `arius.grip`, `arius.arrow_cut`, `arius.null_cut`
Schnitt: `cut.reverse_wind`, `cut.charge`, `cut.strike`, `cut.split`
Nivaris: `nivaris.roar`, `nivaris.inhale`, `nivaris.breath`, `nivaris.wing`, `nivaris.land`, `nivaris.claw`,
`nivaris.tail`, `nivaris.choke`, `nivaris.crash`
Welt: `glacier.crack`, `glacier.collapse`, `ridge.rise`, `ridge.break`, `crystal.hang`, `seal.blizzard`
Signatur: `transform.shatter`, `transform.pulse`, `resonance.chime`, `core.form`, `core.pulse`, `core.collapse`,
`iai.glint`, `iai.draw`, `hold.impact`, `lead.switch`
Ausrüstung: `solvane.parry`, `white_line.charge`, `white_line.release`, `frost_step`, `white_core`, `wingscale.flap`

Registrierung: `src/main/kotlin/dev/henny/hha_arius/AriusSounds.kt` mit `object AriusSounds { val ARIUS_SLASH: SoundEvent … ; fun init() }`
(Feldname = id in GROSS mit `_`, Punkte → `_`, z. B. `MUSIC_TEMPLE`, `CUT_STRIKE`). Gehört der Audio-Teilaufgabe.

### Modell-Verträge
- Modell-Teile liefern je `client/model/<name>/`: `<Name>ModelData.kt` (generiert), `<Name>Model.kt` (Zeitleisten),
  `<Name>Renderer.kt`, und füllen `<Name>Renderers.register()` (Model-Layer + `EntityRendererRegistry`).
- Renderer lesen nur die Felder der Entity aus `BossEntities.kt` (Pose, windup, Formen, Glühwerte, previousPose,
  poseChangedAge, walkAmount, walkPhase). `canBeCulled = false`, kein Namensschild.
- Die Ausrichtung: Blickrichtung des Modells = Blickrichtung der Entity (yaw), wie bei HHA-Bossen.
