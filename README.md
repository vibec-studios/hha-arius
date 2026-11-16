# HHA Arius – The White Blade

**An endgame addon for [H&H Armors (HHA)](https://github.com/vibec-studios/HHA): the duel against Arius, the old white
dragon Nivaris, and the Eirholm frost armour.**

Fabric · Minecraft 1.21.11 · Kotlin · MIT

> Arius is the protagonist of our own light novel *The White Blade*. This mod is a **free adaptation**: the dragon
> Nivaris, the Sanctum of Eirholm, the white form, the white hole, the Eirholm armour and every technique name except
> *White Null* are mod inventions and set nothing for the story. Other HHA bosses are not changed and never mention him.

## The encounter

Craft the **Seal of Eirholm** (nether star, 4 blue ice, 2 echo shards, 2 gold ingots) and use it in a cold biome. Snow
starts to fall upward, and everyone within 12 blocks is taken into the Sanctum of Eirholm, a separate dimension that is
rebuilt from vanilla blocks for every attempt. Nothing in your survival world is ever damaged.

1. **The Temple of Eirholm**: Arius alone. Readable stances, sword combos, wind steps, a parry that punishes greedy
   attacks, frost walls that close escape routes. Below 40 % he takes you seriously and adapts to repeated tactics,
   within limits.
2. **The Cut**: the wind turns, the hall goes silent, a single line crosses the temple. He strikes, nothing happens for
   a moment, then the temple splits along the line and slides apart. He kicks you through the breaking gate.
3. **The Glacier Threshold**: Nivaris alone while Arius watches. Her breath leaves ice ridges; lure her dive into a
   ridge to bring her down, choke her breath with hits to the throat, punish her landings.
4. **The Heart of the Glacier**: she crashes through the ice, Arius sheds his dark armour and fights in white. They fight
   **in rhythm**: one leads, the other supports, and resonance attacks have clear safe paths. Break the rhythm and the
   partner is open. Once per fight: **the white hole**, a core that pushes instead of pulls.
5. **The last clear moment**: no storm, no dragon. Three exchanges against his drawn-from-the-sheath cut.

Every attack is announced on the ground through HHA's telegraph system. **Gold always means safe or open.** Vanilla
movement, shield and bow are enough to win. Dying in the sanctum knocks you out instead: you keep everything and rejoin
in the next part.

## Visuals
Monumental polar landscape around the sanctuary (a 225-block ice spire, a deep canyon, glacier ranges), weather and light
moods per section, Nivaris as a 96-block serpent dragon with an entrance coiled around the spire, a white hole rendered
as a gravitational-lens shader, upgraded wind/ice/yang magic, hit-stop, sword trails, camera work for all cutscenes and
two new soundtracks. Effects scale with `/ariusc fx`; flashes and camera shake can be turned off (`/ariusc flash`,
`/ariusc shake`). A view distance of at least 10 chunks is recommended. Details: `docs/VISUELL.md` (German).

## Rewards

| | First win | Later wins |
|---|---|---|
| Solvane (bastard sword with counter stance and the *Clear Cut* weapon art) | yes | 10 % |
| Arius' Gauntlets (melee style and Wind/Ice/Yang magic) | yes | 10 % |
| Heartfrost | 4 | 2 |
| Eirholm upgrade template | 1 | 50 % |
| Nivaris scales | 6 | 3 |
| Echo of Eirholm (train single parts) | yes | – |
| "White Blade" armour trim template | only if nobody was knocked out | same |

**Arius armour** (item IDs `eirholm_*`): netherite + template + heartfrost at the smithing table. Netherite
silhouette in anthracite and steel blue with old-gold four-point-star trims and ice-blue glowing seams. Following the
other HHA sets: chestplate → *White Core* (Ability 1), leggings → *Frost Step* (Ability 2), full set → *White Hole*
(Ability 3, replaces Ultra), plus passives on helmet, chestplate and boots. The kit is available via
`/hha kit hha_arius.eirholm`.

**Arius' Gauntlets**: one main-hand item that shows both hands. *Melee mode*: jab, cross, body hook, side kick, spin
kick, palm parry with counter – real animations in first and third person. *Magic mode*: a two-step spell wheel
(technique key, default R) with 13 techniques of Wind, Ice and Yang (attack, defence, movement, constructs such as
platform, bridge and ice wall, and a light glider). Switch modes with the mode key (default V).

**Solvane – Clear Cut**: hold the technique key to charge, aim the visible cutting plane (sneak = horizontal), release
for a limited planar cut that can also split wood and stone (configurable, never containers, protected areas or the
arena).

Armour, gauntlets and Solvane share one resource, **frost resonance**, which rests at 30 (50 with the full set) out of
combat and grows only through skill. Full design and numbers: `docs/AUSRUESTUNG.md` (German).

## Requirements

| Dependency | Note |
|---|---|
| Fabric Loader ≥ 0.19.3, Fabric API, Fabric Language Kotlin | |
| **H&H Armors 0.8.0 with the boss overhaul** | uses HHA's addon API plus `Telegraph`/`Vfx`. The matching build is in `libs/` of this repository. Older 0.8.0 jars without `Telegraph` stop the game at start-up with a clear message. |

## Commands

| Command | |
|---|---|
| `/hhaarius start` / `stage <1-4>` / `stop` / `skip` / `info` | admin tools (OP 2) |
| `/ariusc fx <0-100>` / `flash <true/false>` | client: own particles and screen flashes. Camera shake and 3D effects follow HHA's settings (`K` menu) |
| `/hha set hha_arius.boss_damage_scale <x>` | all values via HHA's config |

## Building

```
./gradlew build
```

JDK 21 or newer (Java 21 bytecode). Run `./gradlew runClientGameTest` for the startup regression test: it initializes
the real client, loads a singleplayer world, and checks that the client world and player exist. This is not a boss
fight playtest. Version 1.0.1 fixes the startup crash by loading both Kotlin singleton entrypoints with Fabric's
Kotlin language adapter.

Design documents (German): [docs/KONZEPT.md](docs/KONZEPT.md), [docs/STIMME.md](docs/STIMME.md).

Voice lines are optional: the game shows subtitles and plays `hha_arius:voice.<de|en>.<line>` if such a sound exists
(`tools/assets/voice_lines.py`, ElevenLabs key via environment variable only).

## License

[MIT](LICENSE.txt). Music and sounds are synthesised from scratch (`tools/assets/audio`).
