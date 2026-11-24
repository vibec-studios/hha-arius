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

You can also build a **Gate to Eirholm**: a Nether-style frame of **Rime Gate Stone** (4 per craft from 4 polished deepslate,
4 blue ice and 1 diamond; inside 2–21 wide, 3–21 tall). Light it with the Seal: the gate now holds it. Stand in it for 2 seconds
to start the encounter. A lost fight relights the gate, a victory spends the Seal, and breaking the frame gives it back.

1. **The Temple of the White Blade**: Arius alone, in three stances (flowing, overhead, sheathed iai). Long combo chains
   with delayed strikes and feints, and a perfect counter: his red stance says *don't strike*. Hit him anyway and he parries, locks you in
   and beats you down with seven kicks, punches and cuts. Allies can break him off you. He also breaks off your healing. Below 40 % he takes you seriously and adapts to repeated
   tactics, within limits.
2. **The Cut**: the wind turns, the hall goes silent, a single line crosses the temple. He strikes, nothing happens for
   a moment, then the temple splits along the line and slides apart. He knocks you out through the breaking gate.
3. **The Aerie**: Solvane alone in her own arena far to the west, while Arius watches. She circles in wide loops,
   dives along lit lanes and slams the ground, rains ice spears, coils around you and, once per air phase, draws in
   *Winter's Wrath* – find the gold zone or choke her breath with hits to the throat. After each big attack she is
   open.
4. **The ride**: for over a minute you cling to an ice spike on her back, one spike per player, while Arius sits on her
   head. Duck under arches, pull up over ridges and hack ice off your hands. A missed prompt costs health, but you never
   fall off.
5. **The Heart of the Glacier**: Arius sheds his dark armour and fights in white on an open field far to the east,
   **in rhythm** with Solvane: connected duo chains flow from his sword into her bite and end in a joint strike, with
   clear safe paths. Once per fight: **the white hole** drives you out, then reverses and drags you in. Feed it light
   shards to turn it on them, or it erupts.
6. **The Heir's Wrath**: he chases you over three ever higher mountains, across swaying rope bridges (cut the ropes while he
   is on one) and through eight different caves that collapse if you are too slow, while Solvane stalks you through the
   walls, up to the Gate of the Blade.
7. **The summit**: a last real duel. Blade echoes fight beside him, below half health he draws fully for *The Last
   Horizon*, then he throws everything at you: clones, dragon breath, falling moons, the White Horizon and the white hole.
   At the very end comes **The Storm Breaks**: three announced cuts aimed at the players, then the storm clears and he
   stands open.

Every attack is announced on the ground through HHA's telegraph system. **Gold always means safe or open.** Vanilla
movement, shield and bow are enough to win. Dying in the sanctum knocks you out instead: you keep everything and rejoin
in the next part. After the victory you stay in Eirholm and leave through a return gate whenever you want.

Boss health scales with the number of players (+60 % per extra player), boss damage much more gently (+15 %).

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

**Arius' Gauntlets**: one main-hand item that shows both hands. *Melee mode*: a fighting stance and combos entered
only as left/right-click sequences (e.g. L-L-R, R-L-R), each with its own strikes and finisher; sneak + right-click
parries – real animations in first and third person. *Magic mode*: a two-step spell wheel
(technique key, default R) with 13 techniques of Wind, Ice and Yang (attack, defence, movement, constructs such as
platform, bridge and ice wall, and a light glider). Switch modes with the mode key (default V).

**Solvane** was forged from the heart of an ancient white dragon. In the boss fight the blade takes on that dragon
form: the white dragon you fight *is* Solvane. **Clear Cut**: hold the technique key to charge, aim the visible cutting plane (sneak = horizontal), release
for a limited planar cut that can also split wood and stone (configurable, never containers, protected areas or the
arena).

Armour, gauntlets and Solvane share one resource, **frost resonance**. It recharges passively (faster out of combat and
with the full set) and grows through skilled hits. `/hhaarius test` (OP) gives infinite resonance and short cooldowns to
try every technique without enemies. Full design and numbers: `docs/AUSRUESTUNG.md` (German).

### New in 1.15.5

- **Solvane's reveal (exact 1.11.0 posture & framing):** restored `v1.11.0`'s wide 1.5-turn spire coil (`EntrancePath.coil` + `clearRadius`), raised/resting head anchors (`HEAD_RAISED`/`HEAD_REST`), graceful S-curve neck and upward-angled crest (`NivarisModel.coilPoly`), un-clamped wings and legs (removed `spireGrip` and reveal `wingTuck`), and `v1.11.0`'s wide massif reveal and close roar shots — while keeping the early blizzard onset before the cut and mountain-safe uncoil cameras.
- **Head-ram pickup (Arius boarding):** replaced Arius's 9-tick cross-colonnade warp with a dedicated, unobstructed pickup shot over the open temple rotunda: Arius stands ready on the altar dais, launches with a frost wind-step burst and high parabolic arc cleanly over the northwest cornice, tucks into his seated pose on descent, and lands smoothly between Solvane's horns facing along her flight path.

### New in 1.15.4

- **Solvane's reveal (1.11.0 style):** restored the 1.11.0 reveal framing (`outside` flank glide, crystal crest from diagonally above, wide cloud-break pullback, close roar) with Solvane's natural white dragon silhouette (removed the artificial navy-blue shade tint), early blizzard onset before the cut, and mountain-safe cameras.
- **Mountain split & summit aftermath:** fixed the summit aftermath camera that previously flew into the central ice spire/crown pillars, replaced the floating mountain ridge bar with true mountain-profile geometry, and added soft rising frost mist inside the freshly cleaved chasm.
- **Head-ram pickup & Arius' head perch:** eliminated the 2.2-tick motion rewind jitter and catch-tick foot drift during the ice-spike catch, and aligned Arius' seated perch squarely behind Solvane's horn roots across carry, pickup, and ride scenes.
- **Temple throne:** built a carved frost-and-deepslate throne with gold crown and quartz armrests directly under Arius' seat in the temple apse, with clean step-forward clearance when he stands up.

### New in 1.15.3

- **Solvane's reveal:** restored the 1.7.0 reveal pacing and framing (flank and crystal-crest teaser close-ups in dense snow, then the wide cloud break and close roar), with the blizzard ramping in before the cut and mid-dive camera/entity tracking kept continuously visible down to the landing.
- **Vertical White Horizon & mountain split:** reworked the vertical charge (fixed radial UV artifacts, dais-grounded ring, clean converging ribbons, vertical tension arcs, and continuous overhead raise through the silence beat) and synchronized the physical mountain carve 1:1 with the travelling light slash.
- **Solvane ground & paw clearance:** body and leg contact now runs after flight/landing/rest pose blending, checking rotated head, neck, chest plate, belly, tail-fan, and per-paw claw bounds against real snow layers, stairs, and slabs so her body and feet stay cleanly above the snow in cutscenes and after attacks.

### New in 1.15.2

- **Solvane's reveal:** the storm, the climb along her coils and the parting clouds from 1.13 are back. The blizzard now stands before she takes shape, and she forms as a dark silhouette in the snow.
- **Smooth flight shots:** Solvane's body and the cameras that follow her no longer stutter during cutscene flights.
- **White Horizon charge:** no endless sky beam any more. Light and frost are drawn from all around into the raised blade, with a quiet aura and contracting rings at Arius' feet, one unbroken charge up to the strike.
- **Cameras:** cutscene cameras no longer pass through the far mountain or the ice horn.
- **Landings:** after Solvane lands you face her instead of having to turn around, and her legs and belly stay on top of the snow.
- **Sound:** the mountain split, the kick out of the temple, Solvane's roll and the landings in the snow are audible again.

### New in 1.15.1

- **Launcher fix:** the Vibe Launcher no longer mistakes this mod for an unrelated CurseForge mod. Gameplay is unchanged from 1.15.0.

### New in 1.15.0

- **Test command:** `/arius test <target>` starts any phase, cutscene, quick-time event or hunt section on its own (operators only). `/arius test list` shows every target and `/arius test stop` ends the test.
- **White Horizon cutscene:** Arius raises the blade straight overhead, charges one vertical line of frost, and cuts down through the mountain.
- **Mountain split audio:** a new sound sequence, and the achievement fanfare is gone.
- **Temple kick:** a clear kick wind-up replaces the pose that looked like a sword charge.
- **Wind Burst mace:** Arius no longer gets stuck floating in mid-air during his attacks.
- **Solvane's reveal:** re-staged from the storm silhouette to her landing in front of you.
- **Pickup and drop-off:** she rams you into the air, you catch her ice spike as you fall, and a close barrel roll throws you clear onto open ground.

### New in 1.14.0

- **Combo camera:** the punish camera glides smoothly and never ends up inside walls or posts.
- **White Horizon:** a new sharp, near-white tapered cut that stays readable in first person, third person and in the storm.
- **Golden yang** now restores the split mountain later, when you cross into the hunt.
- **Solvane's summon:** a dark dragon silhouette inside the storm, a claw shot on the ice spike, and a clean seam through the horn when the mountain is split.
- **Head ram and drop-off:** a side run-up with a hit stop, a high arc over her head, a close barrel roll and a safe landing on open ground.
- **Smoother body:** no more tail jumps during takeoff, and her feet stay planted when she lands.

### New in 1.13.0

- **Combat:** the frosted attack markers are back, the parry indicator works again, and Arius stays active while Solvane leads.
- **Mace counter:** repeated mace smashes get caught one-handed, and Arius slams the attacker into the floor.
- **Finale:** a new climax for 1–4 players. Answer three strikes to shatter the ice blade, then land the final blow.
- **Ice blade and Last Horizon:** a forge scene shows why Arius needs a blade of ice, and the Last Horizon has a new cut.
- **Solvane:** reworked summon, pickup, barrel-roll drop-off, ride poses, landing, bridge rescue, tunnel flyby and return to the sword.
- **World:** a new portal block, a redesigned temple, and a mountain split that cuts the arena before golden yang restores every block.
- **Hunt and armor:** Solvane's eye now waits for you to look at it, and the armor's breath and tail have new effects.

### New in 1.12.0

- **Combat:** thin frost markers instead of red floors, no rainbow effects, a counter stance you read from Arius alone, one-hit vanilla shields, real pathfinding, a visible ice sword and the new *The Storm Breaks* ending.
- **Solvane:** slower, cleaner flight clear of walls and ground, a stable reveal, and a coherent pick-up and return to the sword.
- **Ride:** permanent obstacles, inputs that make sense while hanging on, legs streaming in the wind, an easier one-hand phase and a new drop-off.
- **World:** real background mountains, a new gate that holds the Seal, a return gate that rises after the win, and reworked mountain-split, kick, eye, cave and bridge scenes.

### New in 1.11.0

- **Arius:** an unmistakable red counter stance, a smoother and harder parry punishment, a White Hole that reverses and can be turned against the bosses, and protection against mace burst.
- **Finale:** four escalating waves with everything he has, and a Last Clash built for 1–4 players with an exact "NOW!".
- **Duo attacks:** connected chains: sword, bite and scoop, a short ride, then a joint strike.
- **Solvane:** fixed flight routes with a minimum turn radius, authored ground shapes, and no more loose head.
- **Cutscenes:** distant mountains render, cameras stay out of the rock, and the kick and throw-off really launch you.
- **Ride:** new clinging pose, one ice spike per player, Arius seated on her head, and prompts that match what is happening.
- **Hunt:** believable rope bridges with breaking planks, cuttable ropes, forced mini-cutscenes, a fixed route for Arius, shorter attack ranges and ice that stays frozen.
- **Gear:** abilities deal ×1.7 and gauntlets ×1.3 against these bosses, and the Solvane parry window is exact.
- **Music:** clean transitions without pitch warping or double starts.

### New in 1.10.0

- **Parry punishment:** when Arius parries you, you are locked into a short cinematic beating of seven hits. Allies can free you.
- **Faster fights:** far less waiting before and between Arius' and Solvane's attacks.
- **Player scaling:** boss health +60 % and boss damage +15 % per extra player, updated live.
- **Duo attacks:** three new ones (*Twin Fang*, *Gale Cleaver*, *Frost Prison*), used much more often.
- **White hole:** it now pushes you away instead of pulling you in, for bosses and for your own ability.
- **New summit finale:** Blade Intent echoes, *Silence* and the *Last Clash*.
- **Solvane:** smooth flight at any frame rate, a body that no longer folds through itself, and a coherent pick-up scene.
- **Ride:** you cling to her ice spike with both arms; a missed prompt costs health instead of dropping you.
- **Cutscenes:** new shots for the mountain split and the temple kick, and cameras that stay out of rock.
- **The hunt:** eight varied caves with rockfalls, rope bridges, Solvane jumpscares, film effects, no more knock-offs, safer rescues.
- **Gate to Eirholm:** a buildable portal, with return gates; victory no longer sends you home.
- **Adaptive music** for every phase, cutscene and quick-time event; a new **Frozen Star** moon; heavy fog only at the mountains.
- **Solvane parry cue:** a ring shows when to right-click against HHA, HHA Blood and regular melee enemies (`/ariusc parry`).
- **Gauntlets** no longer break blocks and chain more fluidly. Damage of bosses, abilities, armour and weapons rebalanced.

### New in 1.9.0
- **Arius, in the style of a final duel:**
  - three stances;
  - a perfect counter: hit his counter stance and he parries, then punishes with a full combo (replaces the feint-and-kick);
  - long chains with delayed strikes and feints;
  - he breaks off your potions and food.
- **Solvane:**
  - a new loong head (snout, antlers, mane, whiskers);
  - a smooth, serpentine body that no longer clips through itself, drops segments or snaps around;
  - a new moveset: wide circling, glacier dives with ground slams, ice hail, coil crush, *Winter's Wrath*.
- **The ride:** over a minute long. You hold on to an ice spike on her back instead of sitting; after one slip you
  hang on with one hand.
- **Real duo attacks:** *Dragon Ride* and *Cross Cut*.
- **White Hole:** a short, escalating charge that you can break with damage.
- **The Heir's Wrath:**
  - a real chase, without teleports, over three ever higher mountains, real bridges and open tunnels;
  - you only enter the summit through the Gate of the Blade.
- **The summit:** the last quick-time event and the exchanges are replaced by a real duel.
- **The world:**
  - the Aerie and the white-form field are much larger and much farther away;
  - the highland has frost forests, frozen lakes and ruins of the White Blade order;
  - the Frozen Star is a proper ice moon.
- **Cutscenes:** reworked flight to the Aerie (no more teleporting), the White Horizon as a collapsing glacier, the
  knock-out through the gate, and a visible mountain when Solvane awakens.
- **Snowstorm:** clearly visible flakes and gusts. It also shows with fog mods such as NoFog, ClearViews, bactromod
  and Sodium Extra.
- **No more ice walls** from either boss.
- **Gauntlets:** a new fighting stance; combos are only left/right-click sequences (no extra key bindings).
- **Clear Cut:** a ghostly Solvane bursts out of the blade, and the cut splits open a second time.

### New in 1.8.0
- **Bigger Arius:** taller and broader, with a blockier head. Phase I lasts longer and has four new attacks.
- **The Aerie:** Solvane fights in her own arena in the west.
  - A rock basin with ancient dragon ribs and crystal pillars.
  - She flies everyone there after the entrance scene.
  - She has a glowing aura, real roars and four new attacks.
- **The ride:** between phases II and III you ride on Solvane's back.
  - Three hold-on QTEs; failing makes you slip, failing twice makes you fall.
  - She flies along the mountain and through the storm to the field far away.
- **Duo attacks:** Dragon Ride and Cross Cut.
- **The Heir's Wrath:** the hunt was rebuilt.
  - It opens with a rage scene and a whiteout.
  - Five attack types, and Solvane strafes the path.
  - The storm keeps you on the spiral path.
- **The mountain:**
  - side peaks with tunnels and ravines with bridges;
  - a crown of ice spires and a bigger summit arena;
  - a crystal horn.
- **Visuals:**
  - the Frozen Star is now a moon;
  - the White Hole was reworked;
  - new slash arcs and afterimages;
  - the Cut scene chases the seam to the mountain;
  - auroras and drifting sparks in the highland.
- **No more ice walls:** breath and frost walls leave small ice spikes instead.
- **Readable subtitles:** they now show in cutscenes too, with a typewriter effect and enough time to read.
- **Gauntlets:**
  - a real fighting stance, a kick chain, knee and leg sweep;
  - selectable combos with key prompts and finishers;
  - spell sigils when casting;
  - the potions moved to a bar above the wheel and use vanilla durations.
- **Solvane's active:** the Clear Cut swings a giant blade of light.
- **Dragon fixes:**
  - no more stutter;
  - Dragon Breath and Dragon Tail got rune portals and frost spirals.

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
| **H&H Armors 0.8.0 with the boss overhaul, or 0.9.0** | uses HHA's addon API plus `Telegraph`/`Vfx`. The matching build is in `libs/` of this repository. Older 0.8.0 jars without `Telegraph` stop the game at start-up with a clear message. |

## Commands

| Command | |
|---|---|
| `/hhaarius start` / `stage <1-4>` / `stop` / `skip` / `info` | admin tools (OP 2) |
| `/ariusc parry <true/false>` | client: Solvane parry timing ring on or off |
| `/ariusc fx <0-100>` / `flash <true/false>` | client: own particles and screen flashes. Camera shake and 3D effects follow HHA's settings (`K` menu) |
| `/ariusroute` | checks the hunt route for gaps (admin) |
| `/hha set hha_arius.boss_damage_scale <x>` | all values via HHA's config, also `boss_health_per_player` and `boss_damage_per_player` |

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
