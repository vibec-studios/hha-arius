# Working on HHA Arius

Rules for every coding agent. Read this before changing anything.

## Project

Fabric mod for Minecraft 1.21.11 in Kotlin, with Java mixins. It is an addon for the HHA mod and adds the two-phase boss fight against Arius and his dragon-sword Solvane.

- Boss scripts are Kotlin sequences (`Script = Sequence<Unit>`). Each `yield` is one tick.
- Solvane's flight goes through `boss/DragonRail.kt`.
- Language files are generated. Edit `tools/lang/<area>.<de_de|en_us>.json`, then run `python -I tools/lang/build_lang.py`. Every player-facing string needs both languages.
- Build and test with `./gradlew build runClientGameTest`.
- Visual checks use the client gametests in `src/gametest/kotlin/.../gametest/` (`VerifyBoss`, `VerifyDragon`, `VerifyHunt`, `VerifyPortal`) with `-Darius.verify=<name>`, `-Darius.verify.out=<dir>` and `.case`, `.scene`, `.part` or `.view`.
- Test clients must stay silent. Never remove `ALSOFT_DRIVERS=null` or `-Dfabric.noGui=true`.

## Hard rules

These come from real playtest failures. Breaking one means the work gets rejected.

1. **Do not push, merge, tag, release or install.** Commit to your own branch only, unless the person running you explicitly says otherwise.
2. **Stay in your scope.** Do not refactor, rename or "clean up" code outside the assigned work.
3. **Never claim something you did not see.** A passing build proves nothing about an animation. For every visual change, capture frames from the real client and look at them. If you could not check something, say so.
4. **Do not change a test to make it pass.** Fix the cause, or report the failure.
5. **Never add debug, placeholder or test-only behaviour to the shipped game path.**

## Visual direction

- **Palette:** frost white, ice blue and deep navy, with gold only for sacred or yang moments. No red danger zones, no rainbow or hue-shifting effects, no pink, no raw RGB noise.
- **Attack telegraphs** use the frosted ice look of v1.11.0: broad, soft ice-blue bands and thick rings with chunky ice-shard edges. No lightning, no thin crack lines or scribbles. They must stay readable on white snow and in a storm.
- **Effects** should look authored: clear silhouette, a beat of anticipation, a strong hit, a clean fade. More particles is not better.
- **Characters** never teleport, snap, jitter, freeze in mid-air or clip through blocks during a cutscene. Limbs hold what they are supposed to hold.
- **Cameras** never enter terrain and never cut to an unmotivated angle.
- **Solvane** moves as one continuous body from snout to tail. Every segment follows, including head and neck. She flies on planned rails, clears terrain, and may be fully scripted.

## Definition of done

Report each item as done, partly done or not done, with the evidence you checked: the frames or test output, and what still looks wrong. An honest "not done" is more useful than a false "done".
