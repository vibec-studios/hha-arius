# HHA Arius – The White Blade

**An endgame addon for [H&H Armors (HHA)](https://github.com/vibec-studios/HHA): the duel against Arius, the old white
dragon Solvane, and the Eirholm frost armour.**

Fabric · Minecraft 1.21.11 · Kotlin · MIT

> Arius is the protagonist of our own light novel *The White Blade*. This mod is a **free adaptation**: the dragon
> Solvane, the Sanctum of the White Blade, the white form, the white hole, the Eirholm armour and every technique name except
> *White Null* are mod inventions and set nothing for the story. Other HHA bosses are not changed and never mention him.

## The encounter

Craft the **Seal of the White Blade** (nether star, 4 blue ice, 2 echo shards, 2 gold ingots) and use it in a cold biome. Snow
starts to fall upward, and everyone within 12 blocks is taken into the Sanctum of the White Blade, a separate dimension that is
rebuilt from vanilla blocks for every attempt. Nothing in your survival world is ever damaged.

1. **The Temple of the White Blade**: Arius alone. Readable stances, sword combos, wind steps, a parry that punishes greedy
   attacks, frost walls that close escape routes. Below 40 % he takes you seriously and adapts to repeated tactics,
   within limits.
2. **The Cut**: the wind turns, the hall goes silent, a single line crosses the temple. He strikes, nothing happens for
   a moment, then the temple splits along the line and slides apart. He kicks you through the breaking gate.
3. **The Glacier Threshold**: Solvane alone while Arius watches. Her breath leaves ice ridges; lure her dive into a
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
moods per section, Solvane as a 96-block serpent dragon with an entrance coiled around the spire, a white hole rendered
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
| Solvane scales | 6 | 3 |
| Echo of the Sanctum (train single parts) | yes | – |
| "White Blade" armour trim template | only if nobody was knocked out | same |

**Arius armour** (item IDs `eirholm_*`): netherite + template + heartfrost at the smithing table. Since 1.2.0 it is a
netherite-shaped armour in pearl white, ice blue and old gold (since 1.2.2), with 3D accents of the *Dragonheart Watch*:
a crown of ice horns, a pulsing heart gem and wing plates. Following the
other HHA sets: chestplate → *White Core* (Ability 1), leggings → *Frost Step* (Ability 2), full set → *White Hole*
(Ability 3, replaces Ultra), plus passives on helmet, chestplate and boots. The kit is available via
`/hha kit hha_arius.eirholm`.

**Arius' Gauntlets**: one main-hand item that shows both hands. *Melee mode*: jab, cross, body hook, side kick, spin
kick, palm parry with counter – real animations in first and third person. *Magic mode*: a two-step spell wheel
(technique key, default R) with 13 techniques of Wind, Ice and Yang (attack, defence, movement, constructs such as
platform, bridge and ice wall, and a light glider). Switch modes with the mode key (default V).

**Solvane** was forged from the heart of an ancient white dragon. In the boss fight the blade takes on that dragon
form: the white dragon you fight *is* Solvane. **Clear Cut**: hold the technique key to charge, aim the visible cutting plane (sneak = horizontal), release
for a limited planar cut that can also split wood and stone (configurable, never containers, protected areas or the
arena).

Armour, gauntlets and Solvane share one resource, **frost resonance**. It recharges passively (faster out of combat and
with the full set) and grows through skilled hits. `/hhaarius test` (OP) gives infinite resonance and short cooldowns to
try every technique without enemies. Full design and numbers: `docs/AUSRUESTUNG.md` (German).

### New in 1.7.1
- **Fix:** an ender pearl landing in the highland after the fight no longer freezes the server.
  - It used to trigger an endless teleport loop.
  - Stray pearls are now removed when you leave the highland.

### New in 1.7.0
- **The Hunt:** after phase III, a powered-up Arius chases you up the spiral mountain.
  - Each of his hits deals exactly three hearts, and ender pearls are blocked.
  - The finale takes place on the summit.
- **Arius' feats** appear as attacks and title cards:
  - **The White Horizon:** the Cut.
  - **The Frozen Star:** a star appears above the target, freezes and falls.
  - **Absolute Zero: Soul:** a cone that freezes your soul and ignores shields.
- **Blocking:**
  - Vanilla shields can block Arius and Solvane. Heavy hits knock the shield away for 5 s.
  - Solvane's counter works against bosses and mobs and strikes back.
- **New gauntlet techniques:**
  - Blade Storm, which replaces Wind Pull;
  - Ice Prison;
  - Full-Pot (Strength II, Speed II, Fire Resistance, Regeneration II);
  - Tank Mode (Resistance IV).
- **Improved techniques:** a stronger Frost Field and a new Glacier Lance visual.
- **Constructs:** bridges last 30 s and platforms 45 s.
- **Melee:** new flurry and palm blast moves.
- **Fixes:**
  - calmer casting hands;
  - action bar texts are no longer covered by the resonance bar and cooldown icons.

### New in 1.6.5
- **Elden Ring-style Frostbite:** a frost bar builds up from frost attacks. When it fills, it deals burst damage and inflicts Frostbite for 30 s: +20 % damage taken and slower attacks.
- **World:**
  - The mountain is no longer hollow.
  - An ice-spike forest fills the endless plain.
  - The phase III arena was redesigned: a frozen lake pattern, giant leaning ice crystals, a broken pillar ring and a great stone blade.
- **Fight:**
  - Dragon damage is reduced to about two thirds.
  - The Yang Shield blocks everything from the front, including the Warden's sonic boom. Arius and Solvane break it the way an axe breaks a shield.
  - Ground attacks no longer hit players high above the arena.
  - QTEs last much longer and show the key and the action in plain text.
- **Gear:**
  - The Yang Bridge can slope up or down by looking up or down, its pillars always reach the ground, and an on-screen hint shows how to set length and width.
  - New glider pose and effects, and a round Yang Platform.
  - Dryleaf Dane-style melee: a flying kick while sprinting, a dive kick in the air and an airborne whirlwind kick.

### New in 1.6.4
Animated gauntlet fingers in spell mode:
- **Idle:** the fingers ripple slowly.
- **Charging:** the hand curls into a trembling claw.
- **Casting:** the fingers flick open.
- **Aiming a construct:** the index finger points.
- **Shield:** the hand opens flat.

The palm is now light with a glowing ice rune, and the fingertips glow.

### New in 1.6.3
- New camera angles after the Cut and in the shot towards the summit. They no longer look like the camera is inside the mountain.
- Vines only hang as a narrow strip at real rock steps.

### New in 1.6.2
Fixed flickering, striped textures on the gauntlets in first person. Overlapping plates and the skin sleeve no longer z-fight.
The leggings cooldown icon now really shows Dragon Tail; it was never written in 1.5.0 and 1.6.0.

### New in 1.6.1
No more stutter and choppy sounds when you receive the armour kit or first equip or hold the gear. Models, textures and
classes are warmed up when you join a world, and all sound effects are preloaded.

### New in 1.6.0
- **The Cut:** Arius charges a giant blade of light while the sky behind him fills with light, then cleaves the
  mountain in the south lengthwise (no more splitting the sanctum).
- **Mountain:** much bigger (foot 170, shoulder at 172), with a climbing path: about three turns, 27 rock steps with
  vines to climb, lanterns.
- **Arena:** a sea of clouds on the horizon with warm sky light, like a battlefield above the clouds.
- **Solvane:** smooth body motion (no more stepping), stronger self-separation, larger hit areas.
  - Kang-Jin-Loong-style air phase: longer flights, Sky Plunge with ice thorn rings, circling ice volley, low body sweep.
- Cutscene cameras never end up inside the mountain.
- **Gear:**
  - Ice Armor works properly (visible for its whole duration, 5 hearts, shatters when used up).
  - Much bigger Tornado that lifts and flings.
  - White Hole in the style of a white Gargantua.
  - New Dragon Tail cooldown icon.

### New in 1.5.0
- **World:**
  - The arena is an endless snow plain with no rising edges (a new dimension, `hha_arius:highland`).
  - A giant mountain massif with a 650-block spiral path up to its shoulder, lit by lanterns.
  - Phase I takes place in the **Blade Grave**, an open terrace ringed by stone swords, instead of the domed temple.
  - From phase II to III, Solvane carries everyone to the field instead of breaking a hole in the ground.
- **Fight:**
  - The QTE prompt now shows during cutscenes. Before, HHA hid the whole HUD, so the QTE could not work.
  - Dragon body with a bend limit and self-separation, so no more folding or 90° kinks.
  - Reworked hair and head for Arius.
- **Gear:**
  - Elden Ring–style **Dragon Breath**: a ghostly Solvane head breathes rolling frost.
  - **Dragon Tail** replaces Wing Leap.
  - Rebuilt **White Hole** and a lively white **Yang Orb**.
  - **Yang Bridge** as a glowing arch bridge: length (up to 50) and width by mouse wheel, lasts 8 s.
  - New Solvane and gauntlet icons and a slimmer gauntlet model.
- **Frost Reservoir (mana bank):** stores resonance from every kill and from overflow. It pays for missing resonance and for
  **Litematica blueprints** (`/ariusc schematic list|build|confirm|cancel`), which are built from real blocks for resonance.

### New in 1.4.1
The final stage now takes place on an open field in the highland east of the plateau, in a snowstorm with the rock horn
behind it. The Sky Balcony is gone. Yang magic is pure white: the orb is a shaded white sphere with small orbiting
spheres and a curved white "fish" tail, and the shield, bridge, platform and icons are white as well.

### New in 1.4.0
An alpine rock horn and an open highland arena from the dragon fight on, a slow-motion QTE action scene in the finale,
a White Hole that rips up and hurls real blocks, new armour abilities (Dragon Breath, Wing Leap), Tornado and Glacier
Lance for the gauntlets, a real Yang bridge, a screen-space snowstorm that also works with shader packs, a cinematic
camera look and rebalanced damage.

### New in 1.3.0
A permanent cinematic snowstorm, a new final stage high on the spire (the Sky Balcony) instead of the glacier vault,
Arius' flying blade and frost thorns, a blade-lock quick-time event in the finale, a redesigned plateau, new gauntlet
model and item icons, stronger armour ability effects and precise ability descriptions.

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
