package dev.henny.hha_arius.boss

import dev.henny.hha.api.set.HhaSets
import dev.henny.hha.logic.Fx
import dev.henny.hha.logic.Vfx
import dev.henny.hha.logic.boss.Cine
import dev.henny.hha.logic.boss.Telegraph
import dev.henny.hha_arius.arena.ArenaBuilder
import dev.henny.hha_arius.arena.ArenaLayout
import dev.henny.hha_arius.net.AriusNet
import dev.henny.hha_arius.net.FxKind
import dev.henny.hha_arius.net.SliceS2C
import net.minecraft.block.Block
import net.minecraft.entity.effect.StatusEffectInstance
import net.minecraft.entity.effect.StatusEffects
import net.minecraft.particle.BlockStateParticleEffect
import net.minecraft.particle.ParticleTypes
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.util.Identifier
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Abschnitte, Übergänge und Signaturmomente (Konzept 2–8). */
class Scenes(private val f: EirholmFight) {

    private val rnd = java.util.Random()
    private val P = EirholmFight.Phase

    private suspend fun SequenceScope<Unit>.runWhile(s: Script, condition: () -> Boolean) {
        val r = Runner(s)
        while (condition() && r.step()) yield(Unit)
        if (!condition()) f.calls.reset()
    }

    private fun state() = AriusState.get(f.server)

    private fun newcomers(flag: String): List<ServerPlayerEntity> =
        f.audience().filter { flag !in state().player(it.uuid).heard }

    private fun markHeard(flag: String, players: Collection<ServerPlayerEntity> = f.audience()) {
        val s = state()
        for (p in players) s.player(p.uuid).heard.add(flag)
        s.save()
    }

    // --- Intro -------------------------------------------------------------------------------------------------------------

    private fun rel(x: Double, y: Double, z: Double): Vec3d = f.abs(Vec3d(x, y, z))

    /** Nur wenn die Szene nicht gerade übersprungen (vorgespult) wird: Klänge, Texte und kurze Effekte. */
    private inline fun shown(block: () -> Unit) {
        if (!Cine.muted) block()
    }

    /** Szenenklang (still beim Vorspulen). */
    private fun cue(id: String, at: Vec3d, volume: Float = 3f, pitch: Float = 1f) = shown { f.ambient(id, at, volume, pitch) }

    fun intro(): Script = script {
        val a = f.arius ?: return@script
        f.ariusVulnerable = false
        val firstVisit = f.training == 0 && f.audience().any { !state().player(it.uuid).seenIntro }
        if (firstVisit) yieldAll(establish(a)) else f.music("music.temple", 40)
        waitUntil(160) { f.players().any { it.squaredDistanceTo(a) < 16.0 * 16.0 } }
        if (f.training == 1) {
            f.say("echo", 60)
            a.play(AriusPose.RISE)
            wait(40)
        } else {
            val s = state()
            val fresh = f.audience().filter { !s.player(it.uuid).seenIntro }
            if (fresh.isNotEmpty()) {
                // Seit 1.8.0 kürzer (Crix: Texte kann man nicht lesen): drei kurze Zeilen mit Zeit zum Lesen.
                f.say("intro.seal", 60); wait(66)
                a.play(AriusPose.RISE); wait(24)
                f.say("intro.reason", 70); wait(76)
                f.say("intro.test", 80); wait(50)
                for (p in f.audience()) s.player(p.uuid).seenIntro = true
                s.save()
            } else {
                a.play(AriusPose.RISE)
                val summoner = s.player(f.summonerId)
                val pool = if (summoner.wins > 0) listOf("again.1", "again.2", "again.3") else listOf("again.1", "again.2")
                f.say(pool[summoner.againIndex % pool.size], 50)
                summoner.againIndex++
                s.save()
                wait(40)
            }
        }
        yieldAll(temple())
    }

    /**
     * Etablierungsfahrt beim ersten Besuch (≈ 14 s, überspringbar, keine Gefahr: der Kampf hat noch nicht begonnen).
     * 1. Hoch im Norden hinter der Tempelkuppel: Die Kamera gleitet nach Süden, unten die Kuppel, dahinter das Plateau und
     *    in der Ferne die Nadel (oberes Bilddrittel). Nur Wind.
     * 2. Stille: über den Köpfen der Ankommenden durch das Tor in die Halle, langsame Annäherung an den Sitzenden.
     * 3. Detail: sein Gesicht, die Augen glimmen auf, das Tempelthema setzt ein. Rückweg zur Spielerkamera.
     */
    private fun establish(a: AriusEntity): Script {
        val scene = CineScene(f) { f.music("music.temple", 20) }
        return scene.play(script {
            val temple = f.abs(Vec3d.of(ArenaLayout.TEMPLE))
            scene.once { Cine.fade(it, 0x000000, 1.0, 0.0, 30) }
            // 1) Über das Heiligtum. Blickmitte knapp unter dem Horizont: Kuppel unten, Spitze der Nadel oben im Bild.
            scene.line(rel(-16.0, 64.0, -112.0), rel(-8.0, 56.0, -96.0), rel(0.0, 40.0, -20.0), rel(0.0, 54.0, 0.0), 120, 66.0, 62.0)
            shown { f.ambient("seal.blizzard", f.abs(ArenaLayout.ARRIVAL), 1.2f, 0.55f) }
            wait(120)
            // 2) Stille: über den Köpfen durch das Tor, langsam auf ihn zu.
            scene.line(rel(2.5, 3.1, -33.5), rel(1.6, 2.7, -50.0), temple, temple, 90, 52.0, 42.0, lookAt = a, lookOffset = 1.45)
            wait(90)
            // 3) Detail: das Gesicht, die Augen glimmen auf.
            val face = a.entityPos.add(0.0, 1.55, 0.0)
            val front = f.facing(a)
            scene.line(face.add(front.multiply(2.4)).add(0.6, -0.25, 0.0), face.add(front.multiply(1.7)).add(0.4, -0.15, 0.0), face, face.add(0.0, 0.05, 0.0), 62, 46.0, 38.0)
            wait(20)
            a.eyeGlow = 70
            f.music("music.temple", 40)
            shown { f.ambient("resonance.chime", face, 1.5f, 0.6f) }
            wait(26)
            scene.home(16)
            wait(16)
        })
    }

    // --- I: Tempel ----------------------------------------------------------------------------------------------------------

    fun temple(): Script = script {
        val a = f.arius ?: return@script
        // Vom Sitz auf den Altarstufen herunter: Die Laufschritte gehen nur auf Bodenhöhe, auf den Stufen gäbe es keinen
        // freien Schritt (Arius blieb sonst am Sitz stehen).
        if (a.y > f.floorY + 0.5) yieldAll(leapFromDais(a))
        f.setPhase(P.TEMPLE)
        f.reviveAll(listOf(ArenaLayout.ARRIVAL))
        // Seit 1.8.0 deutlich zäher (Crix: „stirbt echt schnell“): 820 statt 520.
        f.ariusMax = BossConfig.health(820f, f.startCount)
        f.ariusHp = f.ariusMax
        f.ariusVulnerable = true
        a.form = AriusForm.DARK
        a.eyeGlow = 45
        f.music("music.temple", 20)
        var serious = false
        while (f.ariusHp > 0f) {
            if (!serious && f.ariusHp <= f.ariusMax * 0.4f) {
                serious = true
                a.form = AriusForm.SERIOUS
                a.eyeGlow = 85
                f.fx(FxKind.FRAGMENTS, a.entityPos, a = 0.4f, life = 20, e1 = a)
                f.ambient("transform.shatter", a.entityPos, 1.4f, 1.3f)
                f.say("serious", 40)
            }
            runWhile(f.moves.turnTemple()) { f.ariusHp > 0f }
        }
        f.ariusVulnerable = false
        if (f.training == 1) {
            a.play(AriusPose.STAGGER)
            f.victory()
            return@script
        }
        yieldAll(theCut())
    }

    /** Sprung im Bogen vom Altar auf den Hallenboden vor den Stufen, mit Windschritt-Klang und Landestaub. */
    private fun leapFromDais(a: AriusEntity): Script = script {
        val start = a.entityPos
        val land = f.abs(ArenaLayout.DAIS_FRONT)
        f.face(a, land)
        cue("arius.wind_step", start, 2f, 0.8f)
        val steps = 12
        for (i in 1..steps) {
            val k = i / steps.toDouble()
            f.place(a, start.lerp(land, k).add(0.0, sin(k * PI) * 2.5, 0.0))
            yield(Unit)
        }
        f.place(a, land)
        f.fx(FxKind.FRAGMENTS, land, a = 0.25f, life = 14)
        cue("arius.stab_ground", land, 1.4f, 1.2f)
        wait(6)
    }

    // --- Der Schnitt -------------------------------------------------------------------------------------------------------

    /**
     * Der Schnitt mit Kamera in zwei Teilen, beide außerhalb jeder Gefahr:
     * * Spannungsaufbau (42 Ticks, vor der Ansage): Sprung auf die Altarstufe in der Halbtotalen, dann Stille und die
     *   Klinge im Detail, während der Wind sich umkehrt; Rückweg zur Spielerkamera **bevor** die Ansage beginnt. Die
     *   Ansage behält ihre vollen 60 Ticks, die Spieler haben die Steuerung.
     * * Nach dem Treffer (Schaden ist schon verrechnet): Stille auf Arius, dann die weite Totale, in der die Osthälfte des
     *   Tempels abgleitet, und ohne Schnitt weiter in den Tritt durchs Tor ([kick]), bis Nivaris' Auftritt übernimmt.
     */
    fun theCut(): Script = script {
        val a = f.arius ?: return@script
        f.setPhase(P.CUT)
        f.ariusVulnerable = false
        f.music(null, 30)
        val stand = f.abs(ArenaLayout.CUT_STAND)
        val pre = CineScene(f) {
            f.place(a, stand)
            a.play(AriusPose.CUT_CHARGE)
        }
        yieldAll(pre.play(script {
            // Sprung auf die Altarstufe: Halbtotale von der Westseite der Halle, der Blick folgt ihm.
            val start = a.entityPos
            pre.line(rel(-9.0, 1.4, -50.0), rel(-8.0, 2.2, -54.5), stand, stand, 14, 62.0, 56.0, lookAt = a, lookOffset = 1.3)
            cue("arius.wind_step", start, 2f, 0.7f)
            for (i in 1..12) {
                val k = i / 12.0
                f.place(a, start.lerp(stand, k).add(0.0, sin(k * PI) * 2.0, 0.0))
                yield(Unit)
            }
            a.yaw = 0f; a.headYaw = 0f; a.bodyYaw = 0f
            a.play(AriusPose.CUT_CHARGE)
            f.fx(FxKind.REVERSE_WIND, stand.add(0.0, 2.0, 0.0), a = 30f, life = AriusTimings.CUT_WIND + 50)
            cue("cut.reverse_wind", stand, 4f)
            // Stille, Detail: Hände und Klinge, an denen sich der Wind sammelt; ganz langsam näher.
            pre.line(stand.add(-2.2, 0.75, 2.6), stand.add(-1.6, 0.85, 2.0), stand, stand, 30, 44.0, 36.0, lookAt = a, lookOffset = 1.15)
            wait(20)
            f.fx(FxKind.EDGE, stand, life = 70, e1 = a)
            cue("cut.charge", stand, 3f)
            // Rückweg zur Spielerkamera, fertig bevor die Ansage beginnt.
            pre.home(10)
            wait(AriusTimings.CUT_LINE_AT - 20)
        }))
        f.cutSurvivors.clear()
        f.cutSurvivors.addAll(f.players().map { it.uuid })
        // Seit 1.5.1 (nach Crix' Bild): Er lädt eine riesige Klinge aus Licht auf, hoch über sich; der Himmel hinter ihm
        // füllt sich mit Licht. Mit dem Schlag spaltet sie den Berg im Süden.
        f.fx(FxKind.GIANT_BLADE, stand, a = 38f, b = AriusTimings.CUT_LINE_TICKS.toFloat(), life = AriusTimings.CUT_LINE_TICKS + 22, e1 = a)
        for (p in f.audience()) dev.henny.hha.logic.boss.Cine.title(listOf(p), "hha_arius.feat.white_horizon", "hha_arius.feat.white_horizon.sub", 60)
        cue("cut.charge", stand, 5f, 0.6f)
        val group = f.calls.boss(pace = Telegraph.Pace.ROOM)
        val line = group.line(f.abs(ArenaLayout.CUT_FROM), f.abs(ArenaLayout.CUT_TO), ArenaLayout.CUT_WIDTH.toFloat(), AriusTimings.CUT_LINE_TICKS)
        wait(line.ticks)
        a.play(AriusPose.CUT_RELEASE, 1)
        f.ambient("cut.strike", stand, 5f)
        for (p in line.hits(f.players())) {
            f.cutSurvivors.remove(p.uuid)
            f.hurt(p, 30f, Damage.Kind.NULL_CUT)
        }
        val post = CineScene(f) {}
        yieldAll(post.play(script {
            // Seit 1.8.0 (Crix: „die Cutscene, wie der Berg zerschnitten wird, soll cooler sein“): weißer Blitz im Schlag,
            // Stille auf ihm, dann jagt die Kamera der Naht nach Süden hinterher, bis der Berg vor ihr aufbricht.
            shown { f.fxAll(FxKind.FLASH, stand, a = 0.6f, life = 10) }
            f.calls.roomShake(stand, 6f, 80f)
            // Stille nach dem Schnitt: nah an ihm, die Klinge ausgestreckt, die Naht leuchtet über den Boden.
            val face = stand.add(0.0, 1.5, 0.0)
            post.line(stand.add(2.6, 1.0, 3.2), stand.add(2.9, 1.3, 3.9), face, face.add(0.0, -0.3, 2.0), AriusTimings.CUT_SILENCE + 2, 48.0, 50.0)
            shown { Vfx.crescent(f.world, Vfx.Style.HEAVEN, stand.add(0.0, 1.5, 0.0), Vec3d(0.0, 0.0, 1.0), 110f, 5f, 12) }
            f.fx(FxKind.SEAM, f.abs(ArenaLayout.CUT_FROM).add(0.0, 0.2, 0.0), f.abs(ArenaLayout.CUT_TO).add(0.0, 0.2, 0.0), a = 14f, life = AriusTimings.CUT_SILENCE + 16)
            wait(AriusTimings.CUT_SILENCE)
            // Jagd entlang der Naht: tief über dem Boden, schnell nach Süden, der Blick voraus zum Berg.
            val raceFrom = f.abs(Vec3d(ArenaLayout.CUT_X + 3.0, 4.0, ArenaLayout.GATE.z + 6.0))
            val raceTo = f.abs(Vec3d(ArenaLayout.CUT_X + 5.0, 9.0, 60.0))
            val peak = f.abs(Vec3d(ArenaLayout.CUT_X, 120.0, ArenaLayout.SPIRE.z.toDouble()))
            post.line(raceFrom, raceTo, peak, peak.add(0.0, 20.0, 0.0), 26, 78.0, 92.0)
            shown {
                for (k in 0 until 6) {
                    val at = f.abs(Vec3d(ArenaLayout.CUT_X, 0.0, ArenaLayout.GATE.z + 10.0 + k * 16.0))
                    f.fx(FxKind.CLEAR_WAVE, at, a = 5f, life = 14)
                }
            }
            cue("cut.split", raceTo, 5f, 1.2f)
            wait(26)
            // Weite Totale über das Plateau nach Süden: die Naht läuft bis zum Berg, das Massiv bricht der Länge nach auf.
            // Seit 1.6.3 von hoch oben und seitlich: die ganze Flanke mit der Kluft und Himmel darüber (vorher füllte die
            // Bergwand das Bild, es wirkte wie im Berg).
            val mountain = f.abs(Vec3d(ArenaLayout.CUT_X, 110.0, ArenaLayout.SPIRE.z - 70.0))
            post.line(rel(-95.0, 120.0, 30.0), rel(-105.0, 135.0, 45.0), mountain, mountain.add(0.0, 30.0, 0.0), AriusTimings.CUT_SLIDE + 20, 72.0, 76.0)
            split()
            shown {
                // Schneewolken und Druckwellen entlang der Kluft
                for (k in 0 until 8) {
                    val z = ArenaLayout.SPIRE.z - 120.0 + k * 30.0
                    val at = f.abs(Vec3d(ArenaLayout.CUT_X, dev.henny.hha_arius.arena.FarPlan.top(ArenaLayout.CUT_X.toInt(), z.toInt()).coerceAtLeast(0).toDouble(), z))
                    f.fxAll(FxKind.CLEAR_WAVE, at, a = 26f, life = 30)
                    Vfx.shockwave(f.world, Vfx.Style.HEAVEN, at, 20f, 24, 2.4f)
                }
            }
            wait(40)
            shown { f.sceneText("cut_after") }
            for (id in f.cutSurvivors) f.server.playerManager.getPlayer(id)?.let { Advancements.grant(it, "under_the_line") }
            wait(AriusTimings.CUT_SLIDE - 30)
            yieldAll(kick(post))
        }))
        yieldAll(arrival())
    }

    /**
     * Seit 1.5.1 (Crix: „den Tempel in zwei Hälften spalten macht keinen Sinn – wenn, dann wird ein Berg geteilt“): Der
     * Schnitt spaltet das Massiv im Süden der Länge nach. Entlang der Schnittebene (x ≈ [ArenaLayout.CUT_X]) entsteht
     * eine Kluft, oben breiter als unten, quer durch Berg und Horn; sichtbare Brocken stürzen hinein. Gebaut wird in
     * Scheiben über mehrere Ticks; am Kampfende stellt [ArenaEdits] alles wieder her.
     */
    fun split(animate: Boolean = true) {
        val z0 = (ArenaLayout.SPIRE.z - dev.henny.hha_arius.arena.FarPlan.MASSIF_R - 6).toInt()
        val z1 = (ArenaLayout.SPIRE.z + dev.henny.hha_arius.arena.FarPlan.MASSIF_R + 6).toInt()
        if (animate) {
            val from = f.abs(Vec3d(ArenaLayout.CUT_X, 0.2, ArenaLayout.CUT_TO.z))
            f.fx(FxKind.SEAM, from, f.abs(Vec3d(ArenaLayout.CUT_X, 0.2, z1.toDouble())), a = 220f, life = AriusTimings.CUT_SLIDE + 40)
            f.ambient("cut.split", f.abs(Vec3d(ArenaLayout.CUT_X, 40.0, ArenaLayout.SPIRE.z.toDouble())), 8f)
            f.ambient("glacier.collapse", from, 6f, 0.6f)
            f.calls.roomShake(from, 8f, 90f)
        }
        var z = z0
        f.task(script {
            while (z <= z1) {
                carve(z, minOf(z1, z + 5), animate)
                z += 6
                yield(Unit)
            }
        })
    }

    /** Kluft für die Scheibe z0..z1 (relativ): V-förmig, unten 3, oben bis 9 Blöcke breit. */
    private fun carve(z0: Int, z1: Int, animate: Boolean) {
        val pos = BlockPos.Mutable()
        val debris = ArrayList<BlockPos>()
        val debrisStates = ArrayList<Int>()
        for (z in z0..z1) {
            for (dx in -6..6) {
                val x = kotlin.math.floor(ArenaLayout.CUT_X).toInt() + dx
                val top = dev.henny.hha_arius.arena.FarPlan.top(x, z)
                if (top < 0) continue
                val pathY = dev.henny.hha_arius.arena.FarPlan.pathAt(x, z)
                val dAxis = kotlin.math.hypot(x + 0.5 - (ArenaLayout.SPIRE.x + 0.5), z + 0.5 - (ArenaLayout.SPIRE.z + 0.5))
                for (y in 0..top + 1) {
                    // Seit 1.7.0: Der Weg überspannt die Kluft als Felsbrücke, und der Gipfel bleibt heil (Finale oben).
                    if (!pathY.isNaN() && y >= pathY - 6) continue
                    if (dAxis <= dev.henny.hha_arius.arena.FarPlan.SHOULDER_R + 8.0 && y >= dev.henny.hha_arius.arena.FarPlan.SHOULDER_Y - 14) continue
                    val hw = 1.5 + y / 50.0
                    if (kotlin.math.abs(x + 0.5 - ArenaLayout.CUT_X) > hw) continue
                    pos.set(f.origin.x + x, f.origin.y + y, f.origin.z + z)
                    if (f.world.getBlockState(pos).isAir) continue
                    if (animate && debris.size < 40 && rnd.nextInt(30) == 0) {
                        debris.add(pos.toImmutable())
                        debrisStates.add(net.minecraft.block.Block.getRawIdFromState(f.world.getBlockState(pos)))
                    }
                    f.edits.clear(pos)
                }
            }
        }
        if (animate && debris.isNotEmpty() && !Cine.muted) {
            val payload = dev.henny.hha_arius.gear.art.ArtCutS2C(LongArray(debris.size) { debris[it].asLong() }, debrisStates.toIntArray(), 3)
            for (p in f.audience()) dev.henny.hha_arius.net.AriusNet.send(p, payload)
        }
    }

    /**
     * Übergang I → II, Teil 1: der Tritt durchs Tor (läuft in der Kamera-Szene von [theCut], keine Gefahr: die Spieler
     * werden gehalten und geworfen, kein Schaden). Seitliche Nahaufnahme, wie er heranstürmt und ausholt; im Tritt springt
     * die Kamera vor das Tor, die Spieler fliegen über sie hinweg, dann schwenkt sie weit über die Landeplätze.
     */
    private fun kick(scene: CineScene): Script = script {
        val a = f.arius ?: return@script
        val players = f.players()
        val kickTarget = f.target()
        val windup = AriusTimings.ARIUS_WINDUP[AriusPose.GATE_KICK]
        if (kickTarget != null) {
            val front = kickTarget.entityPos.add(0.0, 0.0, -1.8)
            val mid = front.lerp(kickTarget.entityPos, 0.5)
            // Seitlich, auf Hüfthöhe: das Heranstürmen und das Ausholen, enger werdend.
            scene.line(mid.add(5.2, 1.3, -1.5), mid.add(3.6, 1.0, -0.4), mid.add(0.0, 1.1, 0.0), mid.add(0.0, 0.9, 0.0), 10 + windup, 58.0, 48.0)
            val from = a.entityPos
            for (i in 1..10) {
                f.place(a, from.lerp(Vec3d(front.x, f.floorY, front.z), i / 10.0).add(0.0, sin(i / 10.0 * PI) * 1.2, 0.0))
                yield(Unit)
            }
            f.face(a, kickTarget.entityPos)
        }
        for (p in players) Hold.freeze(p, 20)
        // Seit 1.8.0 (Crix: „sein Kick, wenn er dich aus dem Tempel kickt, ist komisch“): kein Tritt mehr. Er dreht die
        // Klinge im Nachschwung des Schnitts, und eine Windsichel fegt alle durch das Tor hinaus.
        a.play(AriusPose.CUT_CHARGE)
        cue("cut.reverse_wind", a.entityPos, 3f, 1.3f)
        shown { f.fx(FxKind.REVERSE_WIND, a.entityPos.add(0.0, 1.0, 0.0), a = 8f, life = windup + 4) }
        wait(windup)
        a.play(AriusPose.CUT_RELEASE, 1)
        cue("cut.strike", a.entityPos, 4f, 1.4f)
        cue("hold.impact", a.entityPos, 2.5f)
        shown {
            val towardGate = flatDir(a.entityPos, f.abs(Vec3d.of(ArenaLayout.GATE)))
            f.fx(FxKind.SLASH_ARC, a.entityPos.add(0.0, 1.2, 0.0), towardGate, a = 6f, b = 0.1f, life = 14)
            f.fx(FxKind.GALE, a.entityPos.add(0.0, 1.0, 0.0), towardGate.multiply(14.0), a = 1f, life = 14)
            f.fxAll(FxKind.FLASH, a.entityPos, a = 0.25f, life = 6)
        }
        val gate = f.abs(Vec3d.of(ArenaLayout.GATE))
        // Vor dem Tor, tief: die Spieler brechen heraus und fliegen über die Kamera hinweg.
        scene.line(gate.add(4.0, 0.9, 7.0), gate.add(3.0, 1.3, 9.5), gate.add(0.5, 3.5, 0.5), gate.add(0.5, 6.0, 4.0), 12, 74.0, 78.0)
        shown { Vfx.shockwave(f.world, Vfx.Style.HEAVEN, gate.add(0.5, 0.0, 0.5), 6f, 16, 3f) }
        if (!Cine.muted) for (i in 0 until 40) {
            val x = gate.x + rnd.nextInt(7) - 3
            val y = gate.y + rnd.nextInt(9)
            f.world.spawnParticles(ParticleTypes.SNOWFLAKE, x + 0.5, y + 0.5, gate.z + 0.5, 3, 0.4, 0.4, 0.4, 0.15)
        }
        for ((i, p) in players.withIndex()) {
            if (!f.canSequence(p) || !Hold.isHeld(p)) continue
            val landing = f.abs(ArenaLayout.LANDING[i % ArenaLayout.LANDING.size])
            val d = Vec3d(landing.x - p.x, 0.0, landing.z - p.z)
            val dist = d.length()
            val h = if (dist < 1e-3) Vec3d.ZERO else d.normalize().multiply(dist / 9.25)
            Hold.switchToLaunch(p, Vec3d(h.x, 0.85, h.z), 40, landing, landing)
        }
        // Arius tritt auf die stehengebliebene Tempelhälfte; er bleibt stehen, Solvane in der Hand. Setzen wird er sich
        // erst, wenn er sie im Auftritt entlassen hat (Entrance.release).
        wait(6)
        val seat = f.abs(ArenaLayout.WATCH_SEAT)
        f.place(a, seat)
        a.yaw = 0f; a.headYaw = 0f; a.bodyYaw = 0f
        a.play(AriusPose.IDLE)
        // Weiter Schwenk über die Landeplätze, bis alle gelandet sind; danach übernimmt der Auftritt.
        val landing = f.abs(ArenaLayout.LANDING[0])
        scene.line(gate.add(7.0, 3.0, 11.0), rel(-12.0, 9.0, -6.0), gate.add(0.5, 4.0, 3.0), landing.add(0.0, 1.0, 0.0), 34, 72.0, 64.0)
        wait(34)
    }

    /**
     * Übergang I → II, Teil 2: Nivaris' Auftritt an der Nadel (Sturm, Enthüllung, Abflug, Landung vor den Landeplätzen,
     * eigene Szene, überspringbar), dann ihr Name und Arius' Zeilen.
     */
    private fun arrival(): Script = script {
        yieldAll(entrance(f))
        val d = f.nivaris ?: return@script
        val spot = d.entityPos
        d.play(NivarisPose.ROAR)
        f.ambient("nivaris.roar", spot, 5f, 0.85f)
        f.say("dragon.name", 50)
        val fresh = newcomers("scene.dragon")
        if (fresh.isNotEmpty()) f.sceneText("dragon", 70, fresh)
        markHeard("scene.dragon")
        wait(70)
        val politeFresh = newcomers("dragon.polite")
        f.say(if (politeFresh.isNotEmpty()) "dragon.polite" else "dragon.polite_short", 80)
        markHeard("dragon.polite")
        wait(40)
        yieldAll(waitForFar())
        yieldAll(toAerie())
        yieldAll(glacier())
    }

    /**
     * Seit 1.8.0 (Crix: „Gib dem Drachen eine eigene Arena … cinematischer, richtig viel Aura“): Nach ihrem Namen
     * breitet Solvane die Schwingen aus, ihr Lichtstrom hebt die Spieler an, und sie trägt alle in weitem Flug nach
     * Westen in ihren Horst. Dort landet sie in der Mitte des Kessels, die Druckwelle fegt den Schnee fort, Titelkarte.
     * Arius sitzt danach auf dem Felspfeiler am Nordrand. Kamera, überspringbar, kein Schaden.
     */
    private fun toAerie(): Script = script {
        val d = f.nivaris ?: return@script
        val a = f.arius
        val aerie = f.abs(Vec3d.of(ArenaLayout.AERIE)).add(0.5, 0.0, 0.5)
        val seat = f.abs(ArenaLayout.AERIE_SEAT)
        val finish = {
            d.flying = false
            f.place(d, aerie)
            f.face(d, aerie.add(10.0, 0.0, 0.0))
            d.play(NivarisPose.IDLE)
            a?.let { f.place(it, seat); f.face(it, aerie); it.play(AriusPose.SEATED); it.bladeState = AriusBlade.NONE }
            for ((i, p) in f.players().withIndex()) {
                if (p.squaredDistanceTo(aerie) > 70.0 * 70.0) {
                    val s = f.abs(ArenaLayout.AERIE_LANDING[i % ArenaLayout.AERIE_LANDING.size])
                    p.teleport(f.world, s.x, s.y, s.z, emptySet(), 90f, 0f, false)
                }
                p.onLanding()
            }
        }
        val scene = CineScene(f) { finish() }
        yieldAll(scene.play(script {
            val start = d.entityPos
            val toAerie = flatDir(start, aerie)
            val side = Vec3d(-toAerie.z, 0.0, toAerie.x)
            // 1) Tief vor ihr: die Schwingen gehen auf, Brüllen, Lichtfäden zu den Spielern.
            val eye = start.add(toAerie.multiply(-12.0)).add(side.multiply(6.0)).add(0.0, 1.2, 0.0)
            scene.line(eye, eye.add(toAerie.multiply(2.0)).add(0.0, -0.4, 0.0), start.add(0.0, 4.0, 0.0), start.add(0.0, 9.0, 0.0), 34, 64.0, 74.0, lookAt = d, lookOffset = 5.0)
            d.play(NivarisPose.ROAR, 10)
            d.eyeGlow = 100
            cue("nivaris.roar", start, 5f, 0.8f)
            f.calls.roomShake(start, 3f, 50f)
            wait(10)
            shown {
                Vfx.shockwave(f.world, Vfx.Style.SOUL, start, 16f, 22, 2.2f)
                f.fx(FxKind.CLEAR_WAVE, start, a = 18f, life = 24)
                for (p in f.players()) f.fx(FxKind.THREAD, p.entityPos, life = 60, e1 = p, e2 = d)
            }
            wait(16)
            d.play(NivarisPose.TAKEOFF, 6)
            cue("nivaris.wing", start, 5f, 0.7f)
            wait(8)
            for (p in f.players()) {
                p.addStatusEffect(StatusEffectInstance(StatusEffects.SLOW_FALLING, 260, 0, false, false, true))
                Fx.launchPlayer(p, Vec3d(0.0, 1.2, 0.0))
            }
            d.flying = true
            d.play(NivarisPose.HOVER)
            // 2) Der Flug nach Westen: weite Fahrt neben ihr her, der Lichtstrom trägt alle mit.
            val ticks = 110
            val high = start.add(0.0, 34.0, 0.0)
            val over = aerie.add(0.0, 30.0, 0.0)
            val camFrom = start.add(side.multiply(-28.0)).add(0.0, 26.0, 0.0)
            val camTo = aerie.add(toAerie.multiply(-40.0)).add(side.multiply(-30.0)).add(0.0, 30.0, 0.0)
            scene.line(camFrom, camTo, high, over, ticks, 70.0, 66.0, lookAt = d, lookOffset = 3.0)
            shown { Entrance.storyFx(f, FxKind.BLADE_STREAM, start.add(0.0, 2.0, 0.0), over, a = ticks.toFloat(), b = 0f, life = ticks + 10) }
            for (i in 1..ticks) {
                val k = i / ticks.toDouble()
                val sm = k * k * (3 - 2 * k)
                // Aufsteigen, dann im Bogen über den Horst.
                val pos = start.lerp(over, sm).add(side.multiply(sin(k * PI) * 18.0)).add(0.0, sin(k * PI) * 16.0 + minOf(1.0, k * 4.0) * 20.0 * (1.0 - k), 0.0)
                val next = start.lerp(over, minOf(1.0, sm + 0.02)).add(side.multiply(sin((k + 0.02) * PI) * 18.0))
                f.place(d, pos)
                f.face(d, Vec3d(next.x, pos.y, next.z).add(toAerie))
                if (i == 50) {
                    // Die Spieler steigen im Strom mit (ihr Blick ist die Fahrt): über dem Horst abgesetzt, sanft fallend.
                    for ((j, p) in f.players().withIndex()) {
                        val s = f.abs(ArenaLayout.AERIE_LANDING[j % ArenaLayout.AERIE_LANDING.size]).add(0.0, 6.0, 0.0)
                        p.teleport(f.world, s.x, s.y, s.z, emptySet(), 90f, 0f, false)
                    }
                    a?.let { f.place(it, seat); f.face(it, aerie); it.play(AriusPose.SEATED); it.bladeState = AriusBlade.NONE }
                }
                if (i % 22 == 0) cue("nivaris.wing", pos, 4f, 0.75f)
                yield(Unit)
            }
            // 3) Landung in der Mitte des Kessels: Kreisfahrt, Druckwelle, Brüllen, Titelkarte.
            scene.orbit(aerie, 34.0, 10.0, 200.0, 290.0, 70, 72.0, 62.0, lookAt = d, lookOffset = 3.0, radiusTo = 26.0, heightTo = 5.0)
            d.play(NivarisPose.LAND, 26)
            val from = d.entityPos
            for (i in 1..26) {
                val k = i / 26.0
                f.place(d, from.lerp(aerie, k * k))
                f.face(d, aerie.add(10.0, 0.0, 0.0))
                yield(Unit)
            }
            d.flying = false
            f.place(d, aerie)
            cue("nivaris.land", aerie, 5f, 0.8f)
            f.calls.roomShake(aerie, 6f, 80f)
            shown {
                Vfx.shockwave(f.world, Vfx.Style.SOUL, aerie, 30f, 26, 2.6f)
                f.fx(FxKind.CLEAR_WAVE, aerie, a = 34f, life = 28)
            }
            wait(10)
            d.play(NivarisPose.ROAR, 8)
            cue("nivaris.roar", aerie, 6f, 0.7f)
            scene.once { Cine.title(it, "hha_arius.cine.aerie", "hha_arius.cine.aerie.sub", 70) }
            wait(40)
            for (p in f.players()) p.onLanding()
            scene.home(18)
            wait(18)
        }))
        finish()
        f.atmos(dev.henny.hha_arius.net.AtmosMode.AERIE, 60)
    }

    // --- II: Drachenhorst (früher Gletscherschwelle) ------------------------------------------------------------------

    fun glacier(): Script = script {
        f.setPhase(P.GLACIER)
        f.reviveAll(ArenaLayout.AERIE_LANDING)
        f.nivaris?.let { f.fxAll(FxKind.DRAGON_AURA, it.entityPos, life = 20 * 60 * 30, e1 = it) }
        f.arius?.let { a ->
            if (a.entityPos.squaredDistanceTo(f.abs(ArenaLayout.AERIE_SEAT)) > 4.0) {
                f.place(a, f.abs(ArenaLayout.AERIE_SEAT))
                f.face(a, f.arenaCenter)
                a.play(AriusPose.SEATED)
            }
        }
        f.dragonMax = BossConfig.health(760f, f.startCount)
        f.dragonHp = f.dragonMax
        f.dragonVulnerable = true
        f.ariusVulnerable = false
        f.music("music.glacier", 20)
        barrier()
        wait(40)
        var cycles = 0
        while (f.dragonHp > 0f) {
            runWhile(f.dragon.cycle()) { f.dragonHp > 0f }
            cycles++
            if (f.phaseAge % 600 < 20) barrier()
            // Seit 1.8.0 kein Plaudern mehr mitten im Kampf (unlesbar zwischen Ansagen und Treffern).
        }
        f.dragonVulnerable = false
        if (f.training == 2) {
            f.nivaris?.play(NivarisPose.EXHAUSTED)
            f.victory()
            return@script
        }
        yieldAll(collapse())
    }

    /** White Null: die Verbindung zwischen dir und ihm ist getrennt (nur Anzeige). */
    private fun barrier() {
        val a = f.arius ?: return
        Vfx.rune(f.world, Vfx.Style.STORM, Vec3d(a.x, a.y + 0.05, a.z), 2.4f, 640, a)
    }

    // --- Übergang II → III: Der Gletscher bricht, Verwandlung ----------------------------------------------------------

    /**
     * Übergang II → III seit 1.8.0 (Crix: „Die Cutscene, wie der Drache dich in die neue Arena bringt, ist lame … cooler
     * wäre, wenn man auf seinem Rücken ist, sich mit aller Kraft festhält und er dich weiter weg absetzt“):
     * 1. Szene: Arius pfeift auf seinem Felspfeiler, Solvane hebt erschöpft den Kopf und antwortet; sie streicht tief über
     *    die Spieler, Arius springt auf ihren Nacken. Titel „Halt dich fest!“.
     * 2. Ritt ohne Kamera (eigene Sicht, frei umsehen): alle sitzen auf ihrem Rücken; sie steigt aus dem Horst, fliegt dicht
     *    an der Nordflanke des Bergs entlang, taucht durch Sturm und Wolken und gleitet weit nach Osten aufs Feld.
     *    Drei Mal heißt es festhalten (Quick-Time-Events); wer scheitert, rutscht ab (Schaden), beim zweiten Mal fällt er
     *    und wird vom Lichtstrom aufgefangen.
     * 3. Szene: Landung, Absprung, Verwandlung.
     */
    fun collapse(): Script = script {
        f.arius ?: return@script
        f.nivaris ?: return@script
        val intro = CineScene(f) {}
        yieldAll(intro.play(script { yieldAll(rideIntro(intro)) }))
        yieldAll(ride())
        val scene = CineScene(f) {}
        yieldAll(scene.play(script { yieldAll(transformBody(scene)) }))
        yieldAll(vaultFight())
    }

    private fun rideIntro(scene: CineScene): Script = script {
        val a = f.arius ?: return@script
        val d = f.nivaris ?: return@script
        f.setPhase(P.COLLAPSE)
        f.calls.reset()
        f.music("music.transform", 10)
        // 1) Er pfeift auf dem Pfeiler; sie hebt den Kopf.
        a.sheathed = false
        a.play(AriusPose.WHISTLE)
        val face = a.entityPos.add(0.0, 1.8, 0.0)
        val toward = flatDir(a.entityPos, d.entityPos)
        val side = Vec3d(-toward.z, 0.0, toward.x)
        scene.line(face.add(toward.multiply(2.4)).add(side.multiply(1.0)).add(0.0, -0.4, 0.0), face.add(toward.multiply(1.8)).add(side.multiply(0.8)).add(0.0, -0.3, 0.0),
            face, d.entityPos.add(0.0, 4.0, 0.0), 36, 52.0, 60.0)
        shown { f.say("fall.together", 50) }
        cue("resonance.chime", a.entityPos, 3f)
        wait(18)
        f.fx(FxKind.THREAD, a.entityPos, life = 40, e1 = a, e2 = d)
        d.play(NivarisPose.ROAR, 8)
        cue("nivaris.roar", d.entityPos, 5f, 0.9f)
        a.eyeGlow = 100
        d.eyeGlow = 100
        wait(18)
        // 2) Sie steigt auf und streicht tief über die Spieler; Arius springt ab und landet auf ihrem Nacken.
        d.flying = true
        d.play(NivarisPose.GLIDE)
        val mid = f.players().map { it.entityPos }.fold(Vec3d.ZERO) { acc, v -> acc.add(v) }.multiply(1.0 / f.players().size.coerceAtLeast(1))
        val low = Vec3d(mid.x, f.floorY + 3.5, mid.z)
        val from = d.entityPos
        scene.line(low.add(-12.0, 1.5, 10.0), low.add(-8.0, 2.5, 6.0), from.add(0.0, 4.0, 0.0), low, 34, 66.0, 80.0, lookAt = d, lookOffset = 3.0)
        cue("nivaris.wing", from, 5f, 0.8f)
        val seat = a.entityPos
        for (i in 1..34) {
            val k = i / 34.0
            f.place(d, from.lerp(low, k * k * (3 - 2 * k)).add(0.0, sin(k * PI) * 6.0, 0.0))
            f.face(d, low.add(flatDir(from, low).multiply(5.0)))
            if (i >= 18) {
                val kk = (i - 18) / 16.0
                f.place(a, seat.lerp(neckOf(d), kk).add(0.0, sin(kk * PI) * 3.0, 0.0))
            }
            yield(Unit)
        }
        f.calls.roomShake(low, 4f, 40f)
        shown {
            Vfx.shockwave(f.world, Vfx.Style.SOUL, Vec3d(low.x, f.floorY, low.z), 12f, 18, 1.6f)
            for (p in f.players()) f.fx(FxKind.THREAD, p.entityPos, life = 30, e1 = p, e2 = d)
        }
        scene.once { Cine.title(it, "hha_arius.cine.hold_on", "hha_arius.cine.hold_on.sub", 50) }
        wait(10)
        scene.home(12)
        wait(12)
        // Aufsteigen: alle sitzen hinter ihrem Nacken auf.
        for (p in f.players()) {
            p.addStatusEffect(StatusEffectInstance(StatusEffects.SLOW_FALLING, 600, 0, false, false, true))
            p.startRiding(d, true, true)
        }
    }

    /** Nackenansatz der Drachin (wo Arius steht), Weltkoordinaten. */
    private fun neckOf(d: NivarisEntity): Vec3d = d.entityPos.add(f.facing(d).multiply(1.2)).add(0.0, NivarisShape.SPINE_UP + 1.0, 0.0)

    /** Der Ritt selbst (Flugbahn, Griffe, Quick-Time-Events) steht seit 1.8.1 in [DragonRide] / [RideRoute]. */
    private fun ride(): Script = script {
        val a = f.arius ?: return@script
        val d = f.nivaris ?: return@script
        yieldAll(DragonRide(f).play(::neckOf))
        // Landung am Feld: absteigen, sanft fallen, die Gefallenen stehen schon dort.
        for ((j, p) in f.players().withIndex()) {
            if (p.vehicle === d) p.stopRiding()
            if (p.squaredDistanceTo(d) > 40.0 * 40.0) {
                val s = f.abs(ArenaLayout.VAULT_LANDING[j % ArenaLayout.VAULT_LANDING.size])
                p.teleport(f.world, s.x, s.y + 1.0, s.z, emptySet(), 180f, 0f, false)
            }
            p.onLanding()
        }
        val vault = f.abs(Vec3d.of(ArenaLayout.VAULT)).add(0.5, 0.0, 0.5)
        f.place(a, vault.add(0.0, 7.0, 0.0))
        f.place(d, f.abs(ArenaLayout.VAULT_REST))
        d.flying = false
        d.play(NivarisPose.EXHAUSTED)
        for (p in f.players()) p.onLanding()
    }

    /** Wartet, bis die Fernlandschaft (mit dem Himmelsbalkon) fertig gebaut ist, und zeigt den Fortschritt. */
    private fun waitForFar(): Script = script {
        while (!f.farReady) {
            if (f.world.time % 10L == 0L) {
                val pct = (f.farProgress * 100).toInt()
                for (p in f.audience()) p.sendMessage(net.minecraft.text.Text.translatable("hha_arius.msg.forming_pct", pct), true)
            }
            yield(Unit)
        }
    }

    /** Die Verwandlung zur weißen Form, dann die Resonanz mit Nivaris (Konzept 5.4). Ohne Szene: [transform]. */
    fun transform(): Script = script {
        yieldAll(transformBody(null))
        yieldAll(vaultFight())
    }

    private fun transformBody(scene: CineScene?): Script = script {
        val a = f.arius ?: return@script
        val d = f.nivaris ?: return@script
        val vault = f.abs(Vec3d.of(ArenaLayout.VAULT)).add(0.5, 0.0, 0.5)
        val top = a.entityPos
        // 6a) Detail: er schlägt im Gewölbe auf, die Rüstung zerspringt. Tief, nah, langsam heran.
        val front = f.facing(a)
        val chest = vault.add(0.0, 1.1, 0.0)
        scene?.line(f.clampToArena(chest.add(front.multiply(3.4)).add(-0.8, -0.4, 0.0), 2.0), f.clampToArena(chest.add(front.multiply(2.5)).add(-0.6, -0.3, 0.0), 2.0),
            chest, chest.add(0.0, 0.3, 0.0), 40, 50.0, 40.0)
        a.play(AriusPose.TRANSFORM, AriusTimings.ARIUS_WINDUP[AriusPose.TRANSFORM])
        for (i in 1..10) {
            f.place(a, top.lerp(vault, (i / 10.0) * (i / 10.0)))
            yield(Unit)
        }
        cue("nivaris.land", vault, 3f, 1.4f)
        shown { f.fx(FxKind.FRAGMENTS, vault, a = 1f, life = 34, e1 = a) }
        cue("transform.shatter", vault, 4f)
        f.atmos(dev.henny.hha_arius.net.AtmosMode.WHITE, 20)
        val fresh = newcomers("scene.transform")
        if (fresh.isNotEmpty()) shown { f.sceneText("transform", 70, fresh) }
        markHeard("scene.transform")
        wait(30)
        a.form = AriusForm.WHITE
        a.eyeGlow = 100
        f.updateBars()
        scene?.once { dev.henny.hha.logic.boss.Cine.title(it, "hha_arius.cine.heir", "hha_arius.cine.heir.sub", 70) }
        // Solvane ist der Drache: in seiner Hand verdichtet sich eine Klinge aus weißem Eislicht (V5).
        Entrance.storyFx(f, FxKind.BLADE_FORM, a.entityPos.add(0.0, 1.0, 0.0), life = 24, e1 = a)
        cue("core.form", vault, 2f, 1.5f)
        // 6b) Seitenschwenk: die Kamera weicht zurück und dreht um ihn, Nivaris richtet sich hinter ihm auf.
        val baseAng = Math.toDegrees(kotlin.math.atan2(front.z, front.x))
        scene?.orbit(vault, 3.2, 1.0, baseAng + 18.0, baseAng + 62.0, 40, 52.0, 66.0, lookAt = a, lookOffset = 1.3, radiusTo = 9.5, heightTo = 3.2)
        cue("transform.pulse", vault, 5f)
        shown { f.fx(FxKind.CLEAR_WAVE, vault, a = 12f, life = 22) }
        shown { Vfx.shockwave(f.world, Vfx.Style.HEAVEN, vault, 12f, 20, 1.5f) }
        for (pos in ArenaBuilder.snowAround(f.world, BlockPos.ofFloored(vault), 12)) f.edits.clear(pos)
        for (p in f.players()) if (p.squaredDistanceTo(vault) < 144.0) Fx.launchPlayer(p, Vec3d(p.x - vault.x, 0.0, p.z - vault.z).normalize().multiply(0.5).add(0.0, 0.25, 0.0))
        // Resonanz: Nivaris richtet sich hinter ihm auf.
        d.play(NivarisPose.RESONANCE)
        d.resonance = true
        d.eyeGlow = 100
        f.face(d, vault)
        shown { f.fx(FxKind.THREAD, vault, life = 40, e1 = a, e2 = d) }
        cue("resonance.chime", vault, 4f, 0.9f)
        wait(18)
        a.bladeState = AriusBlade.ICE
        wait(12)
        a.play(AriusPose.IDLE)
        d.play(NivarisPose.IDLE)
        // Rückweg zur Spielerkamera, bevor das Herzgewölbe beginnt.
        wait(22)
        scene?.home(18)
        wait(18)
    }

    // --- III: Herzgewölbe ---------------------------------------------------------------------------------------------------

    fun vaultFight(): Script = script {
        val a = f.arius ?: return@script
        f.setPhase(P.VAULT)
        f.reviveAll(ArenaLayout.VAULT_LANDING)
        f.ariusMax = BossConfig.health(640f, f.startCount)
        f.ariusHp = f.ariusMax
        f.dragonMax = BossConfig.health(500f, f.startCount)
        f.dragonHp = f.dragonMax
        f.dragonDowned = false
        f.ariusVulnerable = true
        f.dragonVulnerable = true
        a.form = AriusForm.WHITE
        // Er kämpft mit der Eisklinge; die echte Solvane ist der Drache (auch nach Überspringen, Abbruch, Training).
        a.bladeState = AriusBlade.ICE
        a.sheathed = false
        f.music("music.heart", 20)
        var whiteHoleDone = false
        var nextResonance = f.now + 300
        var resonanceIndex = 0
        f.ariusLeads = true
        markLead()
        while (f.ariusHp > 0f) {
            if (!f.dragonDowned && f.dragonHp <= 0f) downDragon()
            if (!whiteHoleDone && f.ariusHp <= f.ariusMax * 0.5f) {
                whiteHoleDone = true
                yieldAll(whiteHole())
                continue
            }
            if (!f.dragonDowned && f.now >= nextResonance) {
                runWhile(resonance(resonanceIndex++ % 5)) { f.ariusHp > 0f }
                nextResonance = f.now + AriusTimings.RESONANCE_EVERY
                switchLead()
                continue
            }
            val turn = when {
                f.dragonDowned -> f.moves.turnWhiteSolo()
                f.ariusLeads -> f.moves.turnWhite()
                else -> f.dragon.turnVault()
            }
            val leader = Runner(turn)
            var support: Runner? = null
            var supportAt = f.now + 40
            while (f.ariusHp > 0f && leader.step()) {
                if (!f.dragonDowned && f.dragonHp <= 0f) downDragon()
                if (!f.dragonDowned) {
                    if ((support == null || support.done) && f.now >= supportAt) {
                        support = Runner(if (f.ariusLeads) f.dragon.support() else f.moves.support())
                        supportAt = f.now + 120
                    }
                    support?.step()
                }
                checkTaktBreak()
                yield(Unit)
            }
            if (!f.dragonDowned) switchLead()
        }
        f.ariusVulnerable = false
        f.dragonVulnerable = false
        if (f.training == 3) {
            a.play(AriusPose.KNEEL)
            f.victory()
            return@script
        }
        yieldAll(chase())
        yieldAll(finale())
    }

    /**
     * Die Jagd (seit 1.7.0, überarbeitet 1.8.0 – Crix: „richtig stressig, schneller, tödlicher, nicht nur
     * reinteleportieren, man soll erkennen, dass er wütend ist, heftigerer Schneesturm, nur der Spiralweg“):
     * * Zorn-Szene: Er ballt die Faust um die Eisklinge, weiße Flammen schlagen aus ihm, der Sturm wird zum Whiteout,
     *   Titel. Danach trägt er die Zorn-Aura.
     * * Fünf Angriffe in schnellem Wechsel: Blitzschritt mit Hieb (4 Herzen), Sichelsalve den Weg hinab, Eisregen auf
     *   den Weg, Lawine quer über den Weg, selten der gefrorene Stern. Solvane fliegt um den Berg und speit Frost über
     *   den Weg vor dem Vordersten (wenn sie noch kämpft).
     * * Der Sturm hält alle auf dem Weg: Wer abseits klettert, wird zurückgeworfen (ein Herz).
     * * Enderperlen und Chorusfrüchte gehen nicht. Wer die Gipfelschulter erreicht, beendet die Jagd.
     */
    private fun chase(): Script = script {
        val a = f.arius ?: return@script
        f.ariusVulnerable = false
        f.dragonVulnerable = false
        a.form = AriusForm.WHITE
        a.bladeState = AriusBlade.ICE
        val rage = CineScene(f) { f.atmos(dev.henny.hha_arius.net.AtmosMode.WHITEOUT, 20) }
        yieldAll(rage.play(script { yieldAll(rageScene(rage, a)) }))
        f.chasing = true
        a.eyeGlow = 100
        f.music("music.transform", 30)
        f.atmos(dev.henny.hha_arius.net.AtmosMode.WHITEOUT, 40)
        f.fxAll(FxKind.RAGE_AURA, a.entityPos, life = 20 * 60 * 25, e1 = a)
        for (p in f.players()) {
            p.sendMessage(net.minecraft.text.Text.translatable("hha_arius.msg.chase").formatted(net.minecraft.util.Formatting.AQUA, net.minecraft.util.Formatting.BOLD), false)
        }
        val d = f.nivaris?.takeIf { !f.dragonDowned }
        d?.let {
            it.flying = true
            it.resonance = true
            it.play(NivarisPose.GLIDE)
        }
        val top = f.summit
        var next = f.now + 50
        var nextDragon = f.now + 140
        var nextStar = f.now + 500
        var busy = false
        var dragonBusy = false
        var orbit = 0.0
        var last = -1
        while (true) {
            val players = f.players()
            if (players.isEmpty()) return@script
            val up = players.firstOrNull { it.y >= top.y - 3.0 && sqrt((it.x - top.x) * (it.x - top.x) + (it.z - top.z) * (it.z - top.z)) <= dev.henny.hha_arius.arena.FarPlan.SHOULDER_R + 4.0 }
            if (up != null) break
            if (f.now % 10L == 0L) keepOnPath(players)
            val leader = players.maxByOrNull { pathIndexOf(it) } ?: break
            // Solvane kreist außen am Berg, nahe dem Vordersten
            if (d != null && !dragonBusy) {
                orbit += 0.02
                val li = pathIndexOf(leader).coerceAtLeast(0)
                val lp = f.abs(dev.henny.hha_arius.arena.FarPlan.pathPoint(li))
                val axis = f.abs(Vec3d(ArenaLayout.SPIRE.x + 0.5, lp.y - f.origin.y, ArenaLayout.SPIRE.z + 0.5))
                val out = flatDir(axis, lp)
                val want = lp.add(out.multiply(26.0 + sin(orbit) * 6.0)).add(Vec3d(-out.z, 0.0, out.x).multiply(cos(orbit) * 14.0)).add(0.0, 14.0, 0.0)
                f.place(d, d.entityPos.lerp(want, 0.08))
                f.face(d, lp)
            }
            if (d != null && !dragonBusy && f.now >= nextDragon) {
                dragonBusy = true
                nextDragon = f.now + 170 + rnd.nextInt(60)
                f.task(script {
                    try { yieldAll(dragonStrafe(d, leader)) } finally { dragonBusy = false }
                })
            }
            if (!busy && f.now >= next) {
                busy = true
                val t = players.minByOrNull { it.squaredDistanceTo(a) } ?: leader
                var pick = rnd.nextInt(4)
                if (pick == last) pick = (pick + 1) % 4
                last = pick
                val attack = when {
                    f.now >= nextStar && rnd.nextFloat() < 0.5f -> { nextStar = f.now + 600; chaseStar(a, leader) }
                    pick == 0 -> blinkStrike(a, t)
                    pick == 1 -> crescentBarrage(a, leader)
                    pick == 2 -> iceRain(a, leader)
                    else -> avalanche(a, leader)
                }
                f.task(script {
                    try { yieldAll(attack) } finally { busy = false; next = f.now + 22 + rnd.nextInt(26) }
                })
            }
            yield(Unit)
        }
        f.chasing = false
        // Oben angekommen: alle auf die Gipfelschulter, Arius vor ihnen, Solvane am Rand – das Finale beginnt.
        f.onSummit = true
        val spots = f.finaleSpots()
        for ((i, p) in f.players().withIndex()) {
            val sp = f.abs(spots[i % spots.size])
            p.teleport(f.world, sp.x, sp.y, sp.z, emptySet(), 0f, 0f, false)
            p.onLanding()
        }
        f.place(a, f.arenaCenter.add(0.0, 0.0, -8.0).let { Vec3d(it.x, f.floorY, it.z) })
        f.face(a, f.arenaCenter)
        a.eyeGlow = 60
        f.atmos(dev.henny.hha_arius.net.AtmosMode.REVEAL, 40)
        cue("resonance.chime", f.summit, 5f, 0.8f)
        wait(30)
    }

    /** Zorn-Szene vor der Jagd (≈ 6 s, überspringbar). */
    private fun rageScene(scene: CineScene, a: AriusEntity): Script = script {
        val base = a.entityPos
        val front = f.facing(a)
        val side = Vec3d(-front.z, 0.0, front.x)
        val face = base.add(0.0, 1.95, 0.0)
        // 1) Nah, von unten: er senkt den Kopf, die Faust schließt sich um die Klinge.
        scene.line(face.add(front.multiply(2.2)).add(side.multiply(0.6)).add(0.0, -0.8, 0.0), face.add(front.multiply(1.6)).add(side.multiply(0.4)).add(0.0, -0.6, 0.0), face, face, 34, 46.0, 38.0)
        a.play(AriusPose.CUT_CHARGE)
        f.fx(FxKind.REVERSE_WIND, base.add(0.0, 1.0, 0.0), a = 18f, life = 60)
        cue("cut.reverse_wind", base, 4f, 0.7f)
        wait(16)
        shown { f.say("rage", 40) }
        a.eyeGlow = 100
        cue("transform.pulse", base, 5f, 0.6f)
        wait(18)
        // 2) Ausbruch: weiße Flammen, Druckwelle, der Sturm schlägt zu. Die Kamera fliegt zurück und hoch.
        f.fxAll(FxKind.RAGE_AURA, base, life = 160, e1 = a)
        f.atmos(dev.henny.hha_arius.net.AtmosMode.WHITEOUT, 30)
        shown {
            Vfx.shockwave(f.world, Vfx.Style.HEAVEN, base, 18f, 24, 2.6f)
            f.fx(FxKind.CLEAR_WAVE, base, a = 22f, life = 26)
            f.fx(FxKind.FLASH, base, a = 0.4f, life = 8)
        }
        f.calls.roomShake(base, 7f, 60f)
        cue("cut.strike", base, 5f, 0.6f)
        a.play(AriusPose.CORE_RAISE)
        val ang = Math.toDegrees(atan2(front.z, front.x))
        scene.orbit(base, 3.0, 1.2, ang - 20.0, ang + 70.0, 46, 50.0, 70.0, lookAt = a, lookOffset = 1.4, radiusTo = 11.0, heightTo = 5.0)
        scene.once { Cine.title(it, "hha_arius.cine.chase", "hha_arius.cine.chase.sub", 60) }
        wait(46)
        // 3) Blick zum Berg: der Weg verschwindet im Sturm.
        val foot = f.abs(dev.henny.hha_arius.arena.FarPlan.pathPoint(0))
        scene.line(base.add(side.multiply(4.0)).add(0.0, 3.0, 0.0), base.add(side.multiply(5.0)).add(0.0, 6.0, 0.0), foot.add(0.0, 10.0, 0.0), foot.add(0.0, 40.0, 0.0), 30, 66.0, 60.0)
        wait(30)
        scene.home(14)
        wait(14)
    }

    /** Relativer Wegindex des Spielers (−1 = nicht auf dem Weg). */
    private fun pathIndexOf(p: ServerPlayerEntity): Int =
        dev.henny.hha_arius.arena.FarPlan.pathIndexNear(p.x - f.origin.x, p.z - f.origin.z, 2.0)

    /** Wegpunkt [blocks] Blöcke vor (positiv) bzw. hinter dem Spieler, absolut; dazu die Wegrichtung bergauf. */
    private fun pathAhead(p: ServerPlayerEntity, blocks: Double): Pair<Vec3d, Vec3d> {
        val fp = dev.henny.hha_arius.arena.FarPlan
        var i = pathIndexOf(p)
        if (i < 0) i = fp.nearestPathIndex(p.x - f.origin.x, p.y - f.origin.y, p.z - f.origin.z)
        var walked = 0.0
        val step = if (blocks >= 0) 3 else -3
        var prev = fp.pathPoint(i)
        while (kotlin.math.abs(walked) < kotlin.math.abs(blocks)) {
            val ni = (i + step).coerceIn(0, fp.pathSize - 1)
            if (ni == i) break
            val pt = fp.pathPoint(ni)
            walked += sqrt((pt.x - prev.x) * (pt.x - prev.x) + (pt.z - prev.z) * (pt.z - prev.z))
            prev = pt
            i = ni
        }
        val a0 = fp.pathPoint((i - 6).coerceAtLeast(0))
        val a1 = fp.pathPoint((i + 6).coerceAtMost(fp.pathSize - 1))
        return f.abs(fp.pathPoint(i)) to flatDir(a0, a1)
    }

    /** Wer abseits des Wegs klettert, wird vom Sturm zurückgeworfen (seit 1.8.0). */
    private fun keepOnPath(players: List<ServerPlayerEntity>) {
        val fp = dev.henny.hha_arius.arena.FarPlan
        for (p in players) {
            val rx = p.x - f.origin.x
            val ry = p.y - f.origin.y
            val rz = p.z - f.origin.z
            val d = sqrt((rx - (ArenaLayout.SPIRE.x + 0.5)) * (rx - (ArenaLayout.SPIRE.x + 0.5)) + (rz - (ArenaLayout.SPIRE.z + 0.5)) * (rz - (ArenaLayout.SPIRE.z + 0.5)))
            if (d > fp.MASSIF_R - 10.0 || ry < 6.0) continue
            if (d <= fp.SHOULDER_R + 2.0 && ry >= fp.SHOULDER_Y - 2.0) continue
            if (fp.pathIndexNear(rx, rz, 2.0) >= 0) continue
            val i = fp.nearestPathIndex(rx, ry, rz)
            val back = f.abs(fp.pathPoint(i))
            p.teleport(f.world, back.x, back.y + 0.2, back.z, emptySet(), p.yaw, p.pitch, false)
            p.onLanding()
            p.addStatusEffect(StatusEffectInstance(StatusEffects.SLOWNESS, 30, 1, false, false, true))
            exactHit(p, 2f)
            p.sendMessage(net.minecraft.text.Text.translatable("hha_arius.msg.path_storm").formatted(net.minecraft.util.Formatting.AQUA), true)
            shown { Vfx.shockwave(f.world, Vfx.Style.STORM, back, 3f, 10, 1f) }
        }
    }

    /** Fester Schaden in Herzen×2, unabhängig von Rüstung (wie seit 1.7.0 in der Jagd). */
    private fun exactHit(p: ServerPlayerEntity, amount: Float, kind: Damage.Kind = Damage.Kind.SOLVANE) {
        val a = f.arius ?: return
        val red = Damage.reduction(p)
        Damage.hit(p, a, kind, (amount / (1.0 - red).coerceAtLeast(0.05)).toFloat(), 1f)
    }

    /** Spieler in [shapeHits], die nicht mehr als 3 Blöcke über/unter [y] stehen (die Wegschleifen liegen übereinander). */
    private fun sameLevel(list: List<ServerPlayerEntity>, y: Double) = list.filter { kotlin.math.abs(it.y - y) <= 3.0 }

    /** Blitzschritt: er erscheint hinter dem Ziel, Ring, Hieb (4 Herzen); oft ein zweiter von der Seite. */
    private fun blinkStrike(a: AriusEntity, t: ServerPlayerEntity): Script = script {
        repeat(if (rnd.nextFloat() < 0.5f) 2 else 1) { k ->
            if (!t.isAlive) return@script
            val look = t.rotationVector.let { Vec3d(it.x, 0.0, it.z) }.let { if (it.lengthSquared() < 1e-4) Vec3d(0.0, 0.0, 1.0) else it.normalize() }
            val dir = if (k == 0) look.multiply(-1.0) else Vec3d(-look.z, 0.0, look.x).multiply(if (rnd.nextBoolean()) 1.0 else -1.0)
            val spot = t.entityPos.add(dir.multiply(2.6))
            val from = a.entityPos
            shown { f.fx(FxKind.DASH_STREAK, from, spot, life = 12) }
            f.place(a, spot)
            f.face(a, t.entityPos)
            a.play(AriusPose.IAI_DRAW, 6)
            cue("arius.wind_step", spot, 3f, 1.3f)
            val center = t.entityPos
            val ring = f.calls.ring(center, 2.8f, if (k == 0) 14 else 11)
            wait(ring.ticks)
            cue("arius.slash", center, 3f, 1.1f)
            shown { f.fx(FxKind.SLASH_ARC, center.add(0.0, 1.0, 0.0), flatDir(spot, center), a = 3.6f, b = 0.25f, life = 12) }
            for (p in sameLevel(ring.hits(f.players()), center.y)) exactHit(p, 8f)
            wait(4)
        }
    }

    /** Sichelsalve: er erscheint weiter oben auf dem Weg und schlägt drei Sicheln den Weg hinab (je 3 Herzen). */
    private fun crescentBarrage(a: AriusEntity, leader: ServerPlayerEntity): Script = script {
        val (stand, upDir) = pathAhead(leader, 22.0)
        val from = a.entityPos
        shown { f.fx(FxKind.DASH_STREAK, from, stand, life = 12) }
        f.place(a, stand)
        f.face(a, leader.entityPos)
        cue("arius.wind_step", stand, 3f, 1.1f)
        val down = upDir.multiply(-1.0)
        val side = Vec3d(-down.z, 0.0, down.x)
        for (k in 0 until 3) {
            val off = side.multiply(listOf(-1.6, 1.6, 0.0)[k])
            val l0 = stand.add(off)
            val l1 = stand.add(off).add(down.multiply(26.0))
            val line = f.calls.line(l0, l1, 2.2f, 16, Telegraph.Pace.PROJECTILE)
            a.play(if (k % 2 == 0) AriusPose.COMBO_1 else AriusPose.COMBO_2, line.ticks)
            wait(line.ticks)
            cue("arius.slash", stand, 3f, 0.9f + k * 0.1f)
            shown {
                f.fx(FxKind.SLASH_ARC, stand.add(0.0, 1.1, 0.0), down, a = 5f, b = 0f, life = 12)
                Vfx.crescent(f.world, Vfx.Style.HEAVEN, stand.add(0.0, 0.8, 0.0), down, 26f, 2f, 10)
            }
            for (p in sameLevel(line.hits(f.players()), stand.y)) exactHit(p, 6f)
            wait(4)
        }
        wait(8)
    }

    /** Eisregen: sieben Einschläge auf dem Weg rund um den Vordersten, gestaffelt (je 3 Herzen und Frost). */
    private fun iceRain(a: AriusEntity, leader: ServerPlayerEntity): Script = script {
        a.play(AriusPose.CORE_RAISE)
        cue("core.form", a.entityPos, 3f, 1.4f)
        val shapes = ArrayList<Pair<Telegraph.Shape, Vec3d>>()
        for (k in 0 until 7) {
            val (pt, dir) = pathAhead(leader, -6.0 + k * 6.0)
            val side = Vec3d(-dir.z, 0.0, dir.x).multiply((rnd.nextDouble() - 0.5) * 3.0)
            val c = pt.add(side)
            val ring = f.calls.ring(c, 2.8f, 20 + k * 5)
            shown { f.fx(FxKind.ICE_CHUNK, c.add(0.0, 24.0, 0.0), c, a = 1.8f, life = ring.ticks) }
            shapes.add(ring to c)
        }
        var elapsed = 0
        for ((ring, c) in shapes) {
            wait((ring.ticks - elapsed).coerceAtLeast(0))
            elapsed = ring.ticks
            cue("glacier.crack", c, 3f, 1.2f)
            shown { Vfx.shockwave(f.world, Vfx.Style.SOUL, c, 3.2f, 10, 1f) }
            for (p in sameLevel(ring.hits(f.players()), c.y)) {
                exactHit(p, 6f, Damage.Kind.IMPACT)
                dev.henny.hha_arius.gear.Frostbite.add(p, 20f)
            }
        }
        a.play(AriusPose.IDLE)
    }

    /** Lawine: drei Schneewalzen quer über den Weg rollen auf den Vordersten zu (je 3 Herzen, wirft bergab). */
    private fun avalanche(a: AriusEntity, leader: ServerPlayerEntity): Script = script {
        a.play(AriusPose.STAB_GROUND, 20)
        cue("glacier.collapse", leader.entityPos, 5f, 0.8f)
        f.calls.roomShake(leader.entityPos, 4f, 40f)
        val waves = (0 until 3).map { k ->
            val (pt, dir) = pathAhead(leader, 14.0 - k * 6.0)
            val side = Vec3d(-dir.z, 0.0, dir.x)
            val line = f.calls.line(pt.add(side.multiply(-6.0)), pt.add(side.multiply(6.0)), 3.2f, 24 + k * 9, Telegraph.Pace.HEAVY)
            Triple(line, pt, dir)
        }
        var elapsed = 0
        for ((line, pt, dir) in waves) {
            wait((line.ticks - elapsed).coerceAtLeast(0))
            elapsed = line.ticks
            cue("ridge.break", pt, 4f, 0.7f)
            shown {
                f.fx(FxKind.CLEAR_WAVE, pt, a = 7f, life = 16)
                Vfx.shockwave(f.world, Vfx.Style.STORM, pt, 6f, 12, 1.4f)
            }
            for (p in sameLevel(line.hits(f.players()), pt.y)) {
                exactHit(p, 6f, Damage.Kind.IMPACT)
                Fx.launchPlayer(p, dir.multiply(-0.7).add(0.0, 0.4, 0.0))
            }
        }
        a.play(AriusPose.IDLE)
    }

    /** Der gefrorene Stern in der Jagd: über dem Weg vor dem Vordersten, kleiner, aber 5 Herzen. */
    private fun chaseStar(a: AriusEntity, leader: ServerPlayerEntity): Script = script {
        val (spot, _) = pathAhead(leader, 8.0)
        a.play(AriusPose.CORE_RAISE)
        val ticks = 54
        for (p in f.audience()) Cine.title(listOf(p), "hha_arius.feat.frozen_star", "hha_arius.feat.frozen_star.sub", 40)
        f.fxAll(FxKind.FROZEN_STAR, spot, a = 7f, b = ticks.toFloat(), life = ticks + 40)
        cue("core.form", spot.add(0.0, 30.0, 0.0), 5f, 0.6f)
        val ring = f.calls.ring(spot, 7f, ticks)
        wait(ring.ticks)
        cue("glacier.collapse", spot, 6f, 1.2f)
        f.calls.roomShake(spot, 6f, 40f)
        for (p in sameLevel(ring.hits(f.players()), spot.y)) {
            exactHit(p, 10f, Damage.Kind.IMPACT)
            dev.henny.hha_arius.gear.Frostbite.add(p, 40f)
        }
        a.play(AriusPose.IDLE)
    }

    /** Solvane in der Jagd: Anflug von außen, Frostatem längs über den Weg vor dem Vordersten (3,5 Herzen, Frost). */
    private fun dragonStrafe(d: NivarisEntity, leader: ServerPlayerEntity): Script = script {
        val (p0, dir) = pathAhead(leader, 4.0)
        val (p1, _) = pathAhead(leader, 26.0)
        val axis = f.abs(Vec3d(ArenaLayout.SPIRE.x + 0.5, p0.y - f.origin.y, ArenaLayout.SPIRE.z + 0.5))
        val out = flatDir(axis, p0)
        val perch = p0.lerp(p1, 0.5).add(out.multiply(16.0)).add(0.0, 10.0, 0.0)
        val from = d.entityPos
        for (i in 1..20) {
            f.place(d, from.lerp(perch, i / 20.0))
            f.face(d, p0.lerp(p1, 0.5))
            yield(Unit)
        }
        d.play(NivarisPose.INHALE, 26)
        cue("nivaris.inhale", perch, 4f)
        val line = f.calls.line(p0, p1, 3.4f, 26, Telegraph.Pace.HEAVY, style = Vfx.Style.SOUL)
        for (i in 0 until line.ticks) {
            d.throatGlow = i * 100 / line.ticks
            yield(Unit)
        }
        d.throatGlow = 0
        d.play(NivarisPose.BREATH)
        cue("nivaris.breath", perch, 5f)
        shown {
            Vfx.beam(f.world, Vfx.Style.SOUL, perch.add(0.0, 2.0, 0.0), p0.lerp(p1, 0.5).add(0.0, 0.5, 0.0), 2.4f, 16)
            f.fx(FxKind.DRAGON_BREATH, d.entityPos, a = 26f, life = 20, e1 = d)
        }
        for (p in sameLevel(line.hits(f.players()), p0.y)) {
            exactHit(p, 7f, Damage.Kind.FROST_BREATH)
            dev.henny.hha_arius.gear.Frostbite.add(p, 30f)
        }
        wait(16)
        d.play(NivarisPose.GLIDE)
    }

    private fun checkTaktBreak() {
        val threshold = 25f * (1f + 0.4f * (f.startCount - 1))
        if (f.ariusLeads && f.now < f.ariusOpenUntil && f.ariusOpenDamage >= threshold) {
            f.ariusOpenDamage = 0f
            breakTakt(dragonOpens = true)
        } else if (!f.ariusLeads && f.now < f.dragonOpenUntil && f.dragonOpenDamage >= threshold) {
            f.dragonOpenDamage = 0f
            breakTakt(dragonOpens = false)
        }
    }

    private fun breakTakt(dragonOpens: Boolean) {
        if (f.dragonDowned) return
        f.taktBroken = true
        if (dragonOpens) {
            f.nivaris?.play(NivarisPose.EXHAUSTED)
            f.openDragon(AriusTimings.TAKT_BREAK_OPEN)
        } else {
            f.arius?.play(AriusPose.STAGGER)
            f.openArius(AriusTimings.TAKT_BREAK_OPEN)
        }
        f.hint("hha_arius.hint.takt_break")
        f.ambient("lead.switch", f.arenaCenter, 2f, 0.7f)
        for (p in f.players()) Advancements.grant(p, "takt_break")
    }

    private fun switchLead() {
        f.ariusLeads = !f.ariusLeads
        f.taktBroken = false
        markLead()
    }

    private fun markLead() {
        val a = f.arius ?: return
        val d = f.nivaris
        a.lead = f.ariusLeads && !f.dragonDowned
        d?.lead = !f.ariusLeads && !f.dragonDowned
        a.eyeGlow = if (a.lead) 100 else 60
        d?.eyeGlow = if (d?.lead == true) 100 else 55
        val leader = if (f.ariusLeads) a else d ?: return
        if (!f.dragonDowned) {
            Vfx.rune(f.world, Vfx.Style.STORM, Vec3d(leader.x, f.floorY + 0.05, leader.z), if (leader === a) 0.9f else 2.0f, 50, leader)
            f.ambient("lead.switch", leader.entityPos, 1.2f)
        }
        f.updateBars()
    }

    private fun downDragon() {
        val d = f.nivaris ?: return
        f.dragonDowned = true
        f.dragonVulnerable = false
        d.flying = false
        d.resonance = false
        d.lead = false
        f.place(d, f.abs(ArenaLayout.VAULT_REST))
        f.face(d, f.arenaCenter)
        d.play(NivarisPose.LIE_DOWN)
        f.ariusLeads = true
        markLead()
        f.hint("hha_arius.hint.dragon_down")
        f.updateBars()
    }

    // --- Resonanzangriffe -------------------------------------------------------------------------------------------------

    private suspend fun SequenceScope<Unit>.announceResonance() {
        val a = f.arius ?: return
        val d = f.nivaris ?: return
        f.fx(FxKind.THREAD, a.entityPos, life = AriusTimings.RESONANCE_WARN + 20, e1 = a, e2 = d)
        a.eyeGlow = 100
        d.eyeGlow = 100
        f.ambient("resonance.chime", a.entityPos, 3f)
        wait(AriusTimings.RESONANCE_WARN)
    }

    fun resonance(kind: Int): Script = script {
        announceResonance()
        when (kind) {
            0 -> yieldAll(corridor())
            1 -> yieldAll(dragonRide())
            2 -> yieldAll(crystalStorm())
            3 -> yieldAll(crossCut())
            else -> yieldAll(splitField())
        }
        markLead()
    }

    /** R1: Frostkorridor und Schnitt. */
    private fun corridor(): Script = script {
        val a = f.arius ?: return@script
        val d = f.nivaris ?: return@script
        val t = f.target() ?: return@script
        val c = f.arenaCenter
        val toward = flatDir(c, t.entityPos).let { if (it.lengthSquared() < 0.5) Vec3d(0.0, 0.0, 1.0) else it }
        val mid = f.clampToArena(t.entityPos, 6.0)
        val p0 = f.clampToArena(mid.subtract(toward.multiply(13.0)), 1.0)
        val p1 = f.clampToArena(mid.add(toward.multiply(13.0)), 1.0)
        val side = Vec3d(-toward.z, 0.0, toward.x)
        val left0 = p0.add(side.multiply(3.0)); val left1 = p1.add(side.multiply(3.0))
        val right0 = p0.subtract(side.multiply(3.0)); val right1 = p1.subtract(side.multiply(3.0))
        // Arius an das Korridorende.
        val stand = p0.subtract(toward.multiply(1.5))
        f.task(script { repeat(20) { if (!f.stepToward(a, stand, 0.6)) yield(Unit) }; f.face(a, p1) })
        yieldAll(f.dragon.hoverAt(mid.add(side.multiply(9.0)), mid, 24))
        // Atem zieht zwei Bahnen (eine Ansage-Gruppe).
        val breathGroup = f.calls.boss(style = Vfx.Style.SOUL)
        breathGroup.line(Vec3d(left0.x, f.floorY, left0.z), Vec3d(left1.x, f.floorY, left1.z), 2.4f, 30)
        val last = breathGroup.line(Vec3d(right0.x, f.floorY, right0.z), Vec3d(right1.x, f.floorY, right1.z), 2.4f, 30)
        d.play(NivarisPose.INHALE, last.ticks)
        f.inhaling = true
        f.chokeDamage = 0f
        f.ambient("nivaris.inhale", d.entityPos, 3f)
        val threshold = 30f * (1f + 0.3f * (f.startCount - 1))
        for (i in 0 until last.ticks) {
            d.throatGlow = i * 100 / last.ticks
            if (f.chokeDamage >= threshold) {
                f.inhaling = false
                breathGroup.end()
                yieldAll(f.dragon.choke())
                a.play(AriusPose.STAGGER)
                f.openArius(AriusTimings.TAKT_BREAK_OPEN)
                for (p in f.players()) Advancements.grant(p, "takt_break")
                wait(AriusTimings.TAKT_BREAK_OPEN)
                return@script
            }
            yield(Unit)
        }
        f.inhaling = false
        d.throatGlow = 0
        d.play(NivarisPose.BREATH)
        f.ambient("nivaris.breath", d.entityPos, 3.5f)
        for (p in breathGroup.hits(f.players())) f.hurt(p, 12f, Damage.Kind.FROST_BREATH, d)
        // Kämme mit je zwei Lücken; die Lücken sind golden.
        val gaps = listOf(0.33, 0.66)
        val ridgeA = f.addRidge(Vec3d(left0.x, f.floorY, left0.z), Vec3d(left1.x, f.floorY, left1.z), gaps, force = true)
        val ridgeB = f.addRidge(Vec3d(right0.x, f.floorY, right0.z), Vec3d(right1.x, f.floorY, right1.z), gaps, force = true)
        val cut = f.calls.boss()
        for (g in gaps) {
            val at = p0.lerp(p1, g)
            cut.safeLine(at.add(side.multiply(1.5)), at.add(side.multiply(7.0)), 2.6f, 40)
            cut.safeLine(at.subtract(side.multiply(1.5)), at.subtract(side.multiply(7.0)), 2.6f, 40)
        }
        val slash = cut.line(Vec3d(p0.x, f.floorY, p0.z), Vec3d(p1.x, f.floorY, p1.z), 5f, 40)
        f.face(a, p1)
        a.play(AriusPose.CUT_RELEASE, slash.ticks)
        f.fx(FxKind.EDGE, a.entityPos, life = slash.ticks, e1 = a)
        wait(slash.ticks)
        f.ambient("cut.strike", a.entityPos, 4f, 1.1f)
        for (p in f.players()) if (slash.contains(p) && !cut.isSafe(p)) f.hurt(p, 18f, Damage.Kind.NULL_CUT)
        Vfx.crescent(f.world, Vfx.Style.HEAVEN, a.entityPos.add(0.0, 1.2, 0.0), toward, 28f, 5f, 10)
        f.removeRidge(ridgeA)
        f.removeRidge(ridgeB)
        d.play(NivarisPose.HOVER)
        yieldAll(f.dragon.land(f.target()))
        a.play(AriusPose.STAGGER)
        f.openArius(40)
        wait(40)
    }

    /** R2: Kristallsturm. */
    private fun crystalStorm(): Script = script {
        val a = f.arius ?: return@script
        val d = f.nivaris ?: return@script
        val c = f.arenaCenter
        val angle = rnd.nextDouble() * PI * 2
        val s = Vec3d(sin(angle), 0.0, cos(angle))
        val across = Vec3d(-s.z, 0.0, s.x)
        yieldAll(f.dragon.hoverAt(c.subtract(s.multiply(f.arenaRadius - 3)), c, 24))
        f.face(a, c.add(s))
        val throwT = AriusTimings.ARIUS_WINDUP[AriusPose.THROW_CRYSTALS]
        a.play(AriusPose.THROW_CRYSTALS, throwT)
        f.openArius(throwT + 36)
        val group = f.calls.boss()
        val lanes = ArrayList<Telegraph.Line>()
        val offsets = (0 until 6).map { (it - 2.5) * 5.0 }
        for (o in offsets) {
            val base = c.add(across.multiply(o))
            val from = f.clampToArena(base.subtract(s.multiply(f.arenaRadius)), 0.5)
            val to = f.clampToArena(base.add(s.multiply(f.arenaRadius)), 0.5)
            lanes.add(group.line(Vec3d(from.x, f.floorY, from.z), Vec3d(to.x, f.floorY, to.z), 2f, throwT + 36, Telegraph.Pace.PROJECTILE))
            f.fx(FxKind.CRYSTAL, base.add(0.0, 3.0, 0.0).subtract(s.multiply(2.0)), to.add(0.0, 1.0, 0.0), a = (throwT + 30).toFloat(), life = throwT + 40)
        }
        for (o in offsets.dropLast(1)) {
            val base = c.add(across.multiply(o + 2.5))
            group.safeLine(Vec3d(base.x, f.floorY, base.z).subtract(s.multiply(f.arenaRadius - 4)), Vec3d(base.x, f.floorY, base.z).add(s.multiply(f.arenaRadius - 4)), 2.2f, throwT + 36)
        }
        f.ambient("crystal.hang", c, 3f)
        val start = f.now
        val threshold = 25f * (1f + 0.4f * (f.startCount - 1))
        while (f.now - start < throwT + 36) {
            if (f.ariusOpenDamage >= threshold) {
                group.end()
                f.ambient("ridge.break", c, 3f, 1.4f)
                d.play(NivarisPose.EXHAUSTED)
                f.openDragon(AriusTimings.TAKT_BREAK_OPEN)
                for (p in f.players()) Advancements.grant(p, "takt_break")
                wait(AriusTimings.TAKT_BREAK_OPEN)
                return@script
            }
            if (f.now - start == (throwT + 16).toLong()) d.play(NivarisPose.WING_GUST, 20)
            yield(Unit)
        }
        f.ambient("nivaris.wing", d.entityPos, 4f, 0.6f)
        for (p in group.hits(f.players())) f.hurt(p, 12f, Damage.Kind.IMPACT, d)
        yieldAll(f.dragon.land(f.target()))
        f.openDragon(40)
        wait(40)
    }

    /** R3: Eisfeld spalten. */
    private fun splitField(): Script = script {
        val a = f.arius ?: return@script
        val d = f.nivaris ?: return@script
        val t = f.target() ?: return@script
        val center = f.clampToArena(t.entityPos, 8.0).let { Vec3d(it.x, f.floorY, it.z) }
        yieldAll(f.dragon.hoverAt(center.add(8.0, 0.0, 0.0), center, 20))
        // Einfrieren: Ansage-Kreis (noch kein Schaden; die Fläche ist danach gefroren).
        Vfx.rune(f.world, Vfx.Style.SOUL, center.add(0.0, 0.05, 0.0), 10f, 90)
        val freeze = Runner(f.dragon.freezeField(center, 10f, 30))
        while (freeze.step()) yield(Unit)
        if (f.taktBroken) {
            a.play(AriusPose.STAGGER)
            f.openArius(AriusTimings.TAKT_BREAK_OPEN)
            for (p in f.players()) Advancements.grant(p, "takt_break")
            wait(AriusTimings.TAKT_BREAK_OPEN)
            return@script
        }
        // Arius spaltet das Feld: eine Hälfte bricht, die andere ist sicher.
        val angle = rnd.nextDouble() * PI
        val axis = Vec3d(sin(angle), 0.0, cos(angle))
        val normal = Vec3d(-axis.z, 0.0, axis.x).multiply(if (rnd.nextBoolean()) 1.0 else -1.0)
        f.face(a, center)
        val group = f.calls.boss()
        val seam = group.line(center.subtract(axis.multiply(10.0)), center.add(axis.multiply(10.0)), 1.4f, AriusTimings.ARIUS_WINDUP[AriusPose.SPLIT_FIELD], Telegraph.Pace.HEAVY)
        a.play(AriusPose.SPLIT_FIELD, seam.ticks)
        wait(seam.ticks)
        f.ambient("cut.strike", center, 3.5f, 1.2f)
        for (p in seam.hits(f.players())) f.hurt(p, 12f)
        val half = group.cone(center, normal, 10f, 90f, 20, Telegraph.Pace.AREA)
        group.safe(center.subtract(normal.multiply(5.0)), 4f, 20)
        wait(half.ticks)
        f.ambient("ridge.break", center, 4f, 0.7f)
        for (p in half.hits(f.players())) f.hurt(p, 14f, Damage.Kind.FROST_BREATH, d)
        Vfx.shockwave(f.world, Vfx.Style.SOUL, center.add(normal.multiply(5.0)), 6f, 14, 1.4f)
        yieldAll(f.dragon.land(f.target()))
        a.play(AriusPose.STAGGER)
        f.openArius(30)
        wait(30)
    }

    // --- Seit 1.8.0: Duo-Angriffe (Crix: „bessere Chemie, mehr Duo-Angriffe, besserer Duo-Kampfstil“) -------------------

    /**
     * R4 Drachenritt: Arius springt auf Solvanes Nacken, sie fegt tief über das Feld (breite Bahn), er schlägt links und
     * rechts Sicheln ab (Seitenbahnen), springt am Ende ab und stürzt mit der Klinge auf das Ziel (Ring).
     */
    private fun dragonRide(): Script = script {
        val a = f.arius ?: return@script
        val d = f.nivaris ?: return@script
        val t = f.target() ?: return@script
        val dir = flatDir(a.entityPos, t.entityPos).let { if (it.lengthSquared() < 0.5) Vec3d(0.0, 0.0, 1.0) else it }
        val side = Vec3d(-dir.z, 0.0, dir.x)
        val start = f.clampToArena(t.entityPos.subtract(dir.multiply(22.0)), 3.0).let { Vec3d(it.x, f.floorY, it.z) }
        val end = f.clampToArena(t.entityPos.add(dir.multiply(22.0)), 3.0).let { Vec3d(it.x, f.floorY, it.z) }
        // Sie holt ihn ab: schwebt neben ihm, er springt auf.
        yieldAll(f.dragon.hoverAt(a.entityPos.add(side.multiply(4.0)), t.entityPos, 20))
        val jumpFrom = a.entityPos
        a.play(AriusPose.LAUNCH, 8)
        f.ambient("arius.wind_step", jumpFrom, 2.5f, 0.9f)
        for (i in 1..10) {
            val k = i / 10.0
            f.place(a, jumpFrom.lerp(rideNeck(d), k).add(0.0, sin(k * PI) * 2.5, 0.0))
            yield(Unit)
        }
        f.fx(FxKind.THREAD, a.entityPos, life = 60, e1 = a, e2 = d)
        f.ambient("resonance.chime", a.entityPos, 3f, 1.3f)
        // Anflug an den Bahnanfang, dann Ansage.
        val group = f.calls.boss(style = Vfx.Style.SOUL)
        val main = group.line(start, end, 6f, 36, Telegraph.Pace.PROJECTILE)
        val left = group.line(start.add(side.multiply(6.5)), end.add(side.multiply(6.5)), 2.6f, 42, Telegraph.Pace.FOLLOW_UP)
        val right = group.line(start.subtract(side.multiply(6.5)), end.subtract(side.multiply(6.5)), 2.6f, 42, Telegraph.Pace.FOLLOW_UP)
        val from = d.entityPos
        val air = start.subtract(dir.multiply(4.0)).add(0.0, 3.0, 0.0)
        for (i in 1..(main.ticks - 10)) {
            f.place(d, from.lerp(air, i / (main.ticks - 10.0)))
            f.face(d, end)
            f.place(a, rideNeck(d))
            yield(Unit)
        }
        d.play(NivarisPose.GLIDE)
        f.ambient("nivaris.roar", d.entityPos, 4f, 1.05f)
        for (i in 1..14) {
            val k = i / 14.0
            f.place(d, air.lerp(end.add(dir.multiply(4.0)).add(0.0, 3.0, 0.0), k))
            f.face(d, end.add(dir.multiply(10.0)))
            f.place(a, rideNeck(d))
            f.face(a, end)
            if (i == 4) {
                for (p in main.hits(f.players())) {
                    f.hurt(p, 10f, Damage.Kind.IMPACT, d)
                    Fx.launchPlayer(p, side.multiply(if ((p.x - start.x) * side.x + (p.z - start.z) * side.z > 0) 0.9 else -0.9).add(0.0, 0.5, 0.0))
                }
            }
            if (i == 8) {
                a.play(AriusPose.COMBO_1, 1)
                f.ambient("arius.slash", a.entityPos, 3f, 1.0f)
                f.fx(FxKind.SLASH_ARC, a.entityPos.add(0.0, 1.0, 0.0), side, a = 7f, b = 0.2f, life = 12)
                f.fx(FxKind.SLASH_ARC, a.entityPos.add(0.0, 1.0, 0.0), side.multiply(-1.0), a = 7f, b = 0.2f, life = 12)
                for (p in left.hits(f.players()) + right.hits(f.players())) f.hurt(p, 9f)
            }
            yield(Unit)
        }
        // Absprung und Sturz auf das Ziel.
        val target = f.target() ?: t
        val spot = Vec3d(target.x, f.floorY, target.z)
        val ring = f.calls.ring(spot, 4f, 18, pace = Telegraph.Pace.HEAVY)
        val top = a.entityPos.add(0.0, 6.0, 0.0)
        a.play(AriusPose.STAB_GROUND, ring.ticks)
        val launch = a.entityPos
        for (i in 1..ring.ticks) {
            val k = i / ring.ticks.toDouble()
            val p = if (k < 0.5) launch.lerp(top, k * 2) else top.lerp(spot, (k - 0.5) * 2 * ((k - 0.5) * 2))
            f.place(a, p)
            yield(Unit)
        }
        f.place(a, spot)
        f.ambient("arius.stab_ground", spot, 4f, 0.8f)
        f.calls.roomShake(spot, 5f, 30f)
        Vfx.shockwave(f.world, Vfx.Style.HEAVEN, spot, 6f, 14, 1.8f)
        f.fx(FxKind.CLEAR_WAVE, spot, a = 8f, life = 16)
        for (p in ring.hits(f.players())) f.hurt(p, 12f, Damage.Kind.NULL_CUT)
        yieldAll(f.dragon.land(f.target()))
        a.play(AriusPose.STAGGER)
        f.openArius(30)
        f.openDragon(30)
        wait(30)
    }

    /** Nacken der Drachin (Standplatz von Arius beim Ritt). */
    private fun rideNeck(d: NivarisEntity): Vec3d = d.entityPos.add(f.facing(d).multiply(1.2)).add(0.0, NivarisShape.SPINE_UP + 1.0, 0.0)

    /**
     * R5 Kreuzschnitt: Solvane atmet längs einer Achse durch das Ziel, Arius schneidet quer dazu – ein Kreuz; Sekunden
     * später bricht die Mitte auf. Sicher sind die vier Viertel abseits der Linien und außerhalb der Mitte.
     */
    private fun crossCut(): Script = script {
        val a = f.arius ?: return@script
        val d = f.nivaris ?: return@script
        val t = f.target() ?: return@script
        val center = f.clampToArena(t.entityPos, 10.0).let { Vec3d(it.x, f.floorY, it.z) }
        val ang = rnd.nextDouble() * PI
        val axisA = Vec3d(sin(ang), 0.0, cos(ang))
        val axisB = Vec3d(-axisA.z, 0.0, axisA.x)
        val stand = f.clampToArena(center.subtract(axisB.multiply(14.0)), 1.5)
        f.task(script { repeat(20) { if (!f.stepToward(a, stand, 0.7)) yield(Unit) }; f.face(a, center) })
        yieldAll(f.dragon.hoverAt(center.subtract(axisA.multiply(18.0)), center, 22))
        val group = f.calls.boss(style = Vfx.Style.SOUL)
        val breath = group.line(f.clampToArena(center.subtract(axisA.multiply(16.0)), 0.5), f.clampToArena(center.add(axisA.multiply(16.0)), 0.5), 3.4f, 40)
        val cut = group.line(f.clampToArena(center.subtract(axisB.multiply(15.0)), 0.5), f.clampToArena(center.add(axisB.multiply(15.0)), 0.5), 2.4f, 40, Telegraph.Pace.HEAVY)
        val core = group.ring(center, 3.4f, 56)
        f.fx(FxKind.THREAD, a.entityPos, life = 56, e1 = a, e2 = d)
        d.play(NivarisPose.INHALE, 40)
        a.play(AriusPose.CUT_CHARGE)
        f.ambient("nivaris.inhale", d.entityPos, 3f)
        f.ambient("cut.charge", a.entityPos, 3f, 1.2f)
        for (i in 0 until 40) {
            d.throatGlow = i * 100 / 40
            yield(Unit)
        }
        d.throatGlow = 0
        d.play(NivarisPose.BREATH)
        a.play(AriusPose.CUT_RELEASE, 1)
        f.ambient("nivaris.breath", d.entityPos, 4f)
        f.ambient("cut.strike", a.entityPos, 4f, 1.1f)
        Vfx.beam(f.world, Vfx.Style.SOUL, d.entityPos.add(0.0, 2.0, 0.0), center.add(axisA.multiply(16.0)).add(0.0, 0.5, 0.0), 2.4f, 16)
        f.fx(FxKind.SEAM, center.subtract(axisB.multiply(15.0)).add(0.0, 0.2, 0.0), center.add(axisB.multiply(15.0)).add(0.0, 0.2, 0.0), a = 3f, life = 24)
        f.fx(FxKind.SLASH_ARC, a.entityPos.add(0.0, 1.1, 0.0), axisB, a = 9f, b = 0f, life = 14)
        for (p in breath.hits(f.players())) f.hurt(p, 10f, Damage.Kind.FROST_BREATH, d)
        for (p in cut.hits(f.players())) f.hurt(p, 12f, Damage.Kind.NULL_CUT)
        wait(16)
        f.ambient("core.collapse", center, 4f, 1.3f)
        Vfx.nova(f.world, Vfx.Style.HEAVEN, center.add(0.0, 0.3, 0.0), 3.6f, 12)
        f.fx(FxKind.CLEAR_WAVE, center, a = 6f, life = 14)
        for (p in core.hits(f.players())) f.hurt(p, 10f, Damage.Kind.IMPACT)
        d.play(NivarisPose.HOVER)
        yieldAll(f.dragon.land(f.target()))
        a.play(AriusPose.STAGGER)
        f.openArius(30)
        wait(30)
    }

    // --- Das weiße Loch ----------------------------------------------------------------------------------------------------

    fun whiteHole(): Script = script {
        val a = f.arius ?: return@script
        val d = f.nivaris
        f.calls.reset()
        f.ariusVulnerable = false
        f.dragonVulnerable = false
        val c = f.arenaCenter.let { Vec3d(it.x, f.floorY, it.z) }
        val core = c.add(0.0, 5.0, 0.0)
        val fresh = newcomers("scene.white_hole")
        if (fresh.isNotEmpty()) f.sceneText("white_hole", 70, fresh)
        markHeard("scene.white_hole")
        // Arius springt in die Mitte und hebt Solvane.
        val from = a.entityPos
        for (i in 1..12) {
            f.place(a, from.lerp(c, i / 12.0).add(0.0, sin(i / 12.0 * PI) * 2.0, 0.0))
            yield(Unit)
        }
        a.play(AriusPose.CORE_RAISE)
        val pulses = 4
        val warn = 40
        val travel = ((f.arenaRadius - 3.0) / 0.8).toInt() + 2
        val total = AriusTimings.CORE_BUILD + pulses * (warn + travel) + AriusTimings.CORE_LULL + AriusTimings.CORE_STRIKE + 40
        f.fx(FxKind.WHITE_HOLE, core, a = 14f, b = (total + 600).toFloat(), life = total + 640)
        f.ambient("core.form", core, 5f)
        Vfx.skyMood(f.world, Vfx.Style.STORM, c, 0.5f, total, 60f)
        if (d != null && !f.dragonDowned) {
            d.flying = true
            f.fx(FxKind.THREAD, core, life = AriusTimings.CORE_BUILD, e1 = a, e2 = d)
        }
        // Aufbau: Nivaris kreist und atmet in den Kern; sanfter Schub nach außen.
        for (i in 0 until AriusTimings.CORE_BUILD) {
            if (d != null && !f.dragonDowned) {
                val ang = i / 60.0 * PI
                f.place(d, f.ringPoint(c, 11.0, ang).add(0.0, 6.0, 0.0))
                f.face(d, core)
                if (i == 10) d.play(NivarisPose.BREATH)
            }
            if (i % 5 == 0) for (p in f.players()) if (p.squaredDistanceTo(c) < 25.0) Fx.launchPlayer(p, flatDir(c, p.entityPos).multiply(0.5).add(0.0, 0.1, 0.0))
            yield(Unit)
        }
        d?.takeIf { !f.dragonDowned }?.play(NivarisPose.HOVER)
        var base = rnd.nextDouble() * PI * 2
        for (k in 0 until pulses) {
            if (k == pulses - 1) {
                // Die Lücke: Stille, dann ein einziger extremer Schnitt durch den Kern.
                f.ambient("core.form", core, 3f, 0.6f)
                wait(AriusTimings.CORE_LULL)
                val t = f.target()
                if (t != null) {
                    val dir = flatDir(c, t.entityPos)
                    val from2 = f.clampToArena(c.subtract(dir.multiply(30.0)), 0.5)
                    val to2 = f.clampToArena(c.add(dir.multiply(30.0)), 0.5)
                    f.place(a, core)
                    a.play(AriusPose.BREAKTHROUGH, AriusTimings.CORE_STRIKE)
                    val line = f.calls.line(from2, to2, 4f, AriusTimings.CORE_STRIKE, Telegraph.Pace.HEAVY)
                    f.ambient("iai.glint", core, 3f, 0.8f)
                    for (i in 1..line.ticks) {
                        f.place(a, core.lerp(c, (i / line.ticks.toDouble()).let { it * it }))
                        yield(Unit)
                    }
                    f.ambient("cut.strike", c, 5f, 0.8f)
                    for (p in line.hits(f.players())) f.hurt(p, 36f, Damage.Kind.NULL_CUT)
                    Vfx.crescent(f.world, Vfx.Style.HEAVEN, c.add(0.0, 1.0, 0.0), dir, 56f, 4f, 10)
                    f.place(a, c)
                    a.play(AriusPose.CORE_RAISE)
                }
            }
            val n = if (k == pulses - 1) 4 else 3
            val group = f.calls.boss()
            val corridors = (0 until n).map { base + it * 2 * PI / n }
            for (ang in corridors) {
                group.safeLine(f.ringPoint(c, 3.0, ang), f.ringPoint(c, f.arenaRadius + 1.0, ang), 3f, warn + travel)
            }
            group.ring(c, f.arenaRadius.toFloat() + 1f, warn, inner = 3f)
            wait(warn)
            f.fx(FxKind.FRONT, c.add(0.0, 0.1, 0.0), Vec3d(base, n.toDouble(), 1.5), a = 0.8f, b = f.arenaRadius.toFloat() + 1f, life = travel)
            f.ambient("core.pulse", core, 5f, 0.9f + k * 0.05f)
            val hit = HashSet<java.util.UUID>()
            for (i in 0 until travel) {
                val r = 3.0 + 0.8 * i
                for (p in f.players()) {
                    if (p.uuid in hit) continue
                    val dx = p.x - c.x
                    val dz = p.z - c.z
                    val dist = sqrt(dx * dx + dz * dz)
                    if (abs(dist - r) > 0.9) continue
                    if (inCorridor(dx, dz, corridors, 1.5)) continue
                    hit.add(p.uuid)
                    f.hurt(p, 10f, Damage.Kind.PRESSURE)
                    Fx.launchPlayer(p, Vec3d(dx, 0.0, dz).normalize().multiply(1.0).add(0.0, 0.3, 0.0))
                }
                yield(Unit)
            }
            base += Math.toRadians(40.0)
        }
        // Der Kern kollabiert: großes Doppelfenster.
        f.ambient("core.collapse", core, 6f)
        f.fx(FxKind.FLASH, core, a = 0.35f, life = 10)
        f.fx(FxKind.WHITE_HOLE, core, b = -1f, life = 1)
        f.ariusVulnerable = true
        a.play(AriusPose.STAGGER)
        f.openArius(AriusTimings.CORE_END_OPEN)
        if (d != null && !f.dragonDowned) {
            f.dragonVulnerable = true
            val ground = f.clampToArena(Vec3d(d.x, f.floorY, d.z), 4.0)
            f.place(d, ground)
            d.flying = false
            d.play(NivarisPose.EXHAUSTED)
            f.openDragon(AriusTimings.CORE_END_OPEN)
        }
        wait(AriusTimings.CORE_END_OPEN)
    }

    private fun inCorridor(dx: Double, dz: Double, corridors: List<Double>, half: Double): Boolean {
        val dist = sqrt(dx * dx + dz * dz)
        val ang = atan2(dx, dz)
        for (c in corridors) {
            var delta = ang - c
            while (delta > PI) delta -= 2 * PI
            while (delta < -PI) delta += 2 * PI
            if (abs(delta) < PI / 2 && abs(dist * sin(delta)) <= half) return true
        }
        return false
    }

    // --- Finale ------------------------------------------------------------------------------------------------------------

    fun finale(): Script = script {
        val a = f.arius ?: return@script
        f.setPhase(P.FINALE)
        f.reviveAll(f.finaleSpots())
        // Treffer zählen im Finale nur als Konter im offenen Fenster (siehe EirholmFight.hitArius).
        f.ariusVulnerable = true
        f.dragonVulnerable = false
        f.music("music.finale", 60)
        f.nivaris?.let { d ->
            d.flying = false
            d.resonance = false
            d.lead = false
            f.place(d, if (f.onSummit) f.summit.add(14.0, 0.0, -4.0) else f.abs(ArenaLayout.VAULT_REST))
            f.face(d, f.arenaCenter)
            d.play(NivarisPose.LIE_DOWN)
        }
        a.lead = false
        // Solvane kehrt zurück: der Drache wird wieder zur Klinge in der Scheide (V5). Danach gibt es keinen Drachen mehr.
        yieldAll(bladeReturn(a))
        Vfx.skyMood(f.world, Vfx.Style.GOLD, f.arenaCenter, 0.25f, 400, 60f)
        a.sheathed = true
        a.play(AriusPose.IAI_STANCE)
        f.say("finale", 70)
        f.exchangesWon = 0
        wait(70)
        var clashTried = false
        while (f.exchangesWon < 3) {
            val t = f.target()
            if (t == null) {
                wait(20)
                continue
            }
            // Letzter Austausch: Klingenbindung als Szene mit Quick-Time-Event (einmal; danach wie gewohnt).
            if (f.exchangesWon == 2 && !clashTried) {
                clashTried = true
                yieldAll(clashScene(a, t))
                if (clashWon) break
                continue
            }
            f.face(a, t.entityPos)
            val dir = flatDir(a.entityPos, t.entityPos)
            val from = a.entityPos
            val dist = sqrt(from.squaredDistanceTo(t.entityPos))
            val to = f.clampToArena(from.add(dir.multiply(dist + 4.0)), 1.0)
            val ticks = AriusTimings.IAI_LINE[f.exchangesWon.coerceIn(0, 2)]
            val line = f.calls.line(from, to, 1.6f, ticks, Telegraph.Pace.HEAVY)
            f.ambient("iai.glint", a.entityPos, 2.5f, 1.2f)
            a.play(AriusPose.IAI_STANCE)
            wait(line.ticks)
            a.sheathed = false
            a.play(AriusPose.IAI_DRAW, 1)
            f.ambient("iai.draw", a.entityPos, 4f)
            val struck = line.hits(f.players())
            val perfect = struck.filter { perfectBlock(it) }
            val lost = struck.filter { it !in perfect }
            for (i in 1..4) {
                f.place(a, from.lerp(to, i / 4.0))
                yield(Unit)
            }
            Vfx.crescent(f.world, Vfx.Style.HEAVEN, from.add(0.0, 1.1, 0.0), dir, sqrt(from.squaredDistanceTo(to)).toFloat(), 1.2f, 6)
            // Wer getroffen wurde, ohne perfekt zu blocken, nimmt Schaden, egal was andere tun.
            for (p in lost) f.hurt(p, 12f, Damage.Kind.NULL_CUT)
            if (lost.isNotEmpty() && perfect.isEmpty()) {
                wait(AriusTimings.IAI_RESET)
                a.sheathed = true
                a.play(AriusPose.IAI_STANCE)
                continue
            }
            for (p in perfect) {
                f.ambient("arius.parry", p.entityPos, 3f, 1.2f)
                Vfx.flare(f.world, Vfx.Style.GOLD, p.entityPos.add(0.0, 1.2, 0.0), 1.2f, 6)
            }
            f.exchangeOpen = true
            f.openArius(AriusTimings.IAI_OPEN)
            f.hint("hha_arius.hint.exchange")
            waitUntil(AriusTimings.IAI_OPEN) { !f.exchangeOpen }
            f.exchangeOpen = false
            wait(20)
            if (f.exchangesWon < 3) {
                a.sheathed = true
                a.play(AriusPose.IAI_STANCE)
                wait(20)
            }
        }
        yieldAll(ending())
    }

    /**
     * Auftakt des Finales (V5): Solvane kehrt zurück. Er ruft sie („Komm zurück.“), der Drache löst sich in Licht auf und
     * zieht als Strom über ihn hinweg in die Scheide auf seinem Rücken, die Eisklinge in seiner Hand zerfällt. Kurze,
     * überspringbare Kamera (≈ 5 s, zwei Einstellungen), kein Schaden, keine Ansage. Endzustand: kein Drache,
     * [AriusBlade.HELD], Solvane in der Scheide ([settleReturn]). Im Training ohne Kamera.
     */
    private fun bladeReturn(a: AriusEntity): Script = script {
        val d = f.nivaris
        if (d == null || f.training != 0) {
            settleReturn(a)
            return@script
        }
        val scene = CineScene(f) { settleReturn(a) }
        yieldAll(scene.play(script {
            val head = f.dragon.anchorPos(NivarisMoves.Zone.HEAD)
            val body = d.entityPos.add(0.0, 1.2, 0.0)
            f.face(a, head)
            a.sheathed = false
            a.play(AriusPose.IDLE)
            val toDragon = flatDir(a.entityPos, head)
            val side = Vec3d(-toDragon.z, 0.0, toDragon.x)
            val eye = a.entityPos.add(0.0, 1.7, 0.0)
            // A) Über seine Schulter zum Drachen; er ruft sie, der Faden zwischen Träger und Klinge leuchtet (34 Ticks).
            scene.line(f.clampToArena(eye.add(toDragon.multiply(-2.6)).add(side.multiply(1.1)).add(0.0, 0.3, 0.0), 2.0),
                f.clampToArena(eye.add(toDragon.multiply(-2.0)).add(side.multiply(0.9)).add(0.0, 0.4, 0.0), 2.0),
                head.lerp(eye, 0.3), head, 34 + RETURN_PEAK, 56.0, 50.0)
            wait(8)
            shown { f.say("dragon.return", 50) }
            // Von der Schwanzspitze zum Kopf löst sie sich auf; der Kopf ist auf dem Höhepunkt fort (vor dismissNivaris).
            d.fadeOut(26 + RETURN_PEAK - 1)
            d.resonance = true
            d.eyeGlow = 100
            a.eyeGlow = 100
            Entrance.storyFx(f, FxKind.THREAD, a.entityPos, life = 26 + RETURN_PEAK, e1 = a, e2 = d)
            cue("resonance.chime", a.entityPos, 3f, 0.8f)
            wait(26)
            // Sie löst sich in Licht auf; auf dem Höhepunkt ist sie fort.
            Entrance.storyFx(f, FxKind.DRAGON_DISSOLVE, head, body, a = 5f, life = RETURN_PEAK + 30)
            cue("transform.pulse", head, 4f, 0.7f)
            cue("seal.blizzard", head, 2f, 1.4f)
            wait(RETURN_PEAK)
            f.dismissNivaris()
            // B) Seitlich auf Brusthöhe: der Strom kommt in hohem Bogen über ihn in die Scheide, die Eisklinge zerfällt.
            val chest = a.entityPos.add(0.0, 1.4, 0.0)
            scene.line(f.clampToArena(chest.add(side.multiply(3.4)).add(toDragon.multiply(0.8)).add(0.0, 0.2, 0.0), 2.0),
                f.clampToArena(chest.add(side.multiply(2.8)).add(toDragon.multiply(0.1)).add(0.0, 0.4, 0.0), 2.0),
                chest.add(toDragon.multiply(1.6)).add(0.0, 0.8, 0.0), chest.add(toDragon.multiply(-0.2)), 56, 58.0, 50.0)
            val sheath = a.entityPos.add(0.0, 1.55, 0.0).subtract(toDragon.multiply(0.25))
            Entrance.storyFx(f, FxKind.BLADE_STREAM, head.add(0.0, 1.5, 0.0), sheath, a = RETURN_TRAVEL.toFloat(), b = 1f, life = RETURN_TRAVEL + 14, e1 = a)
            cue("nivaris.wing", head, 2f, 1.6f)
            wait(4)
            Entrance.storyFx(f, FxKind.BLADE_SHATTER, a.entityPos.add(0.0, 1.0, 0.0), life = 24, e1 = a)
            a.bladeState = AriusBlade.NONE
            cue("transform.shatter", a.entityPos, 2f, 1.5f)
            wait(RETURN_TRAVEL - 4)
            // Die Scheide ist wieder gefüllt; die Hand geht an den Griff.
            a.bladeState = AriusBlade.HELD
            a.sheathed = true
            cue("iai.glint", a.entityPos, 2.5f, 0.9f)
            cue("resonance.chime", a.entityPos, 2f, 1.3f)
            wait(6)
            a.play(AriusPose.IAI_STANCE)
            wait(56 - RETURN_TRAVEL - 6 - 18)
            scene.home(18)
            wait(18)
        }))
        settleReturn(a)
    }

    /** Endzustand der Rückkehr (auch nach Überspringen, Abbruch oder im Training). */
    private fun settleReturn(a: AriusEntity) {
        f.dismissNivaris()
        a.bladeState = AriusBlade.HELD
        a.sheathed = true
        a.eyeGlow = 60
    }

    /**
     * QTE-Szene des letzten Austauschs (Crix: wie die Quick-Time-Event-Szenen in Spider-Man): drei Takte in Zeitlupe mit
     * eigenen Kamerabildern, jeder mit Prompt (schließender Kreis um die Taste):
     * 1. **Ausweichen** (Springen): Arius stürmt aus dem Iai heran, wer springt, setzt über ihn hinweg.
     * 2. **Klingenbindung** (Angriff hämmern): die Klingen binden sich, Funken.
     * 3. **Konter** (Benutzen): Wer es trifft, schlägt ihm die Klinge aus der Hand – der Austausch ist gewonnen.
     * Scheitert ein Takt (niemand trifft ihn), wirft Arius alle zurück (Schaden), und der letzte Austausch läuft normal.
     * Die Prompts laufen auch, wenn jemand die Kamera überspringt.
     */
    private var clashWon = false

    private fun clashScene(a: AriusEntity, t: ServerPlayerEntity): Script = script {
        clashWon = false
        val players = f.players()
        val dir = flatDir(a.entityPos, t.entityPos)
        val side = Vec3d(-dir.z, 0.0, dir.x)
        val start = a.entityPos
        var ok = false
        val scene = CineScene(f) {}
        fun beat(kind: Qte.Kind, ticks: Int, count: Int, text: String): Qte.Round = Qte.start(f.players(), kind, ticks, count, text)
        yieldAll(scene.play(script {
            // --- Takt 1: Ausweichen. Weit, tief, leicht seitlich: Arius im Iai, der Sturm kommt langsam auf das Ziel zu.
            a.sheathed = true
            a.play(AriusPose.IAI_STANCE)
            val eye = t.entityPos.add(0.0, 1.2, 0.0)
            scene.line(eye.add(side.multiply(5.0)).add(dir.multiply(-2.0)).add(0.0, -0.4, 0.0), eye.add(side.multiply(4.0)).add(dir.multiply(-1.0)),
                start.add(0.0, 1.2, 0.0), eye, 30, 62.0, 54.0)
            f.ambient("iai.glint", start, 3f, 0.7f)
            val r1 = beat(Qte.Kind.JUMP, 70, 1, "hha_arius.qte.dodge")
            a.sheathed = false
            a.play(AriusPose.IAI_DRAW, 1)
            val rushTo = t.entityPos.add(dir.multiply(3.0)).let { Vec3d(it.x, f.floorY, it.z) }
            for (i in 1..70) {
                f.place(a, start.lerp(rushTo, (i / 70.0) * (i / 70.0) * 0.55))   // Zeitlupe: langsam heran
                if (r1.done()) break
                yield(Unit)
            }
            waitUntil(6) { r1.done() }
            Qte.close(r1)
            if (!r1.anySuccess()) return@script
            for (p in f.players()) if (r1.success(p)) dev.henny.hha.logic.Fx.launchPlayer(p, Vec3d(0.0, 0.75, 0.0).add(dir.multiply(-0.15)))
            f.ambient("iai.draw", a.entityPos, 4f, 1.1f)
            for (i in 1..6) {
                f.place(a, a.entityPos.lerp(rushTo, i / 6.0))
                yield(Unit)
            }
            Vfx.crescent(f.world, Vfx.Style.HEAVEN, a.entityPos.add(0.0, 1.0, 0.0), dir, 5f, 1.4f, 6)
            wait(8)
            // --- Takt 2: Klingenbindung. Dicht an den beiden, Funken.
            val tgt = f.players().minByOrNull { it.squaredDistanceTo(a) } ?: return@script
            f.face(a, tgt.entityPos)
            a.play(AriusPose.GUARD)
            val mid = a.entityPos.lerp(tgt.entityPos, 0.5).add(0.0, 1.3, 0.0)
            val d2 = flatDir(a.entityPos, tgt.entityPos)
            val s2 = Vec3d(-d2.z, 0.0, d2.x)
            scene.line(mid.add(s2.multiply(3.2)).add(0.0, 0.2, 0.0), mid.add(s2.multiply(2.2)).add(0.0, 0.1, 0.0), mid, mid, 64, 44.0, 32.0)
            f.ambient("arius.parry", mid, 3.5f, 0.6f)
            Vfx.flare(f.world, Vfx.Style.GOLD, mid, 2.2f, 10)
            val r2 = beat(Qte.Kind.MASH, 120, 8, "hha_arius.qte.clash")
            var n = 0
            while (!r2.done() && n++ < 124) {
                if (n % 3 == 0) f.world.spawnParticles(net.minecraft.particle.ParticleTypes.ELECTRIC_SPARK, mid.x, mid.y, mid.z, 6, 0.15, 0.15, 0.15, 0.25)
                if (n % 8 == 0) f.ambient("arius.parry", mid, 1.6f, 0.9f + n * 0.01f)
                yield(Unit)
            }
            Qte.close(r2)
            if (!r2.anySuccess()) return@script
            f.ambient("arius.parry", mid, 4f, 1.3f)
            Vfx.flare(f.world, Vfx.Style.GOLD, mid, 3.0f, 10)
            a.play(AriusPose.STAGGER)
            // --- Takt 3: Konter. Über die Schulter des Spielers auf Arius, der taumelt.
            val over = tgt.entityPos.add(0.0, 1.7, 0.0).subtract(d2.multiply(-1.6)).add(s2.multiply(0.8))
            scene.line(over, over.add(d2.multiply(0.6)), a.entityPos.add(0.0, 1.3, 0.0), a.entityPos.add(0.0, 1.1, 0.0), 30, 50.0, 40.0)
            val r3 = beat(Qte.Kind.USE, 60, 1, "hha_arius.qte.counter")
            waitUntil(62) { r3.done() }
            Qte.close(r3)
            if (!r3.anySuccess()) return@script
            ok = true
            // Entwaffnet: die Klinge fliegt wirbelnd davon (Lichtspur), goldener Blitz, Druckwelle.
            val hand = a.entityPos.add(0.0, 1.4, 0.0)
            Entrance.storyFx(f, FxKind.BLADE_STREAM, hand, hand.add(d2.multiply(-6.0)).add(0.0, 4.0, 0.0), a = 14f, b = 0f, life = 30, e1 = a)
            a.bladeState = AriusBlade.NONE
            f.ambient("iai.draw", hand, 4f, 1.5f)
            Vfx.flare(f.world, Vfx.Style.GOLD, hand, 3.6f, 14)
            Vfx.shockwave(f.world, Vfx.Style.GOLD, Vec3d(hand.x, f.floorY, hand.z), 6f, 14, 1.2f)
            a.play(AriusPose.KNEEL)
            wait(30)
            scene.home(16)
            wait(16)
        }))
        if (ok) {
            clashWon = true
            f.exchangesWon = 3
            f.ariusOpenUntil = f.now
            a.open = false
            f.updateBars()
            a.bladeState = AriusBlade.HELD
            for (p in f.players()) p.sendMessage(net.minecraft.text.Text.translatable("hha_arius.qte.clash_won_other").formatted(net.minecraft.util.Formatting.GOLD), true)
            wait(10)
        } else {
            val at = a.entityPos.add(0.0, 1.2, 0.0)
            f.ambient("iai.draw", at, 4f, 0.8f)
            Vfx.crescent(f.world, Vfx.Style.HEAVEN, at, dir, 7f, 1.6f, 6)
            for (p in f.players()) {
                if (p.squaredDistanceTo(at) < 12.0 * 12.0) {
                    f.hurt(p, 6f, Damage.Kind.NULL_CUT)
                    dev.henny.hha.logic.Fx.launchPlayer(p, flatDir(a.entityPos, p.entityPos).multiply(1.0).add(0.0, 0.4, 0.0))
                }
                p.sendMessage(net.minecraft.text.Text.translatable("hha_arius.qte.clash_lost").formatted(net.minecraft.util.Formatting.RED), true)
            }
            a.bladeState = AriusBlade.HELD
            wait(24)
            a.sheathed = true
            a.play(AriusPose.IAI_STANCE)
            wait(20)
        }
        if (players.isEmpty()) return@script
    }

    /** Perfekter Block: Schild höchstens [AriusTimings.PERFECT_BLOCK] Ticks vor dem Treffer erhoben. */
    private fun perfectBlock(p: ServerPlayerEntity): Boolean =
        p.isBlocking && p.itemUseTime in 0..(AriusTimings.PERFECT_BLOCK + 5)

    /** Vom Treffer-Handler: ein Konter im offenen Fenster gewinnt den Austausch. */
    fun exchangeWon(player: ServerPlayerEntity) {
        val a = f.arius ?: return
        f.exchangesWon++
        f.ariusOpenUntil = f.now
        a.open = false
        f.ambient("arius.parry", a.entityPos, 3f, 0.8f)
        Vfx.flare(f.world, Vfx.Style.GOLD, a.entityPos.add(0.0, 1.2, 0.0), 1.6f, 8)
        a.play(AriusPose.STAGGER)
        f.updateBars()
        player.sendMessage(net.minecraft.text.Text.translatable("hha_arius.hint.exchange_won", f.exchangesWon).formatted(net.minecraft.util.Formatting.GOLD), true)
    }

    /**
     * Das Ende mit Kamera (überspringbar, kein Kampf mehr): Detail, wie er niederkniet; langsamer Halbkreis um ihn, während
     * er von der Waffe spricht; die weite Einstellung des (seit [bladeReturn] drachenlosen) Gewölbes, als er aufsteht;
     * Rückweg zur Spielerkamera. Die persönlichen Zeilen danach laufen ohne Kamera.
     */
    private fun ending(): Script = script {
        val a = f.arius ?: return@script
        f.music("music.finale", 0)
        val scene = CineScene(f) {
            a.play(AriusPose.IDLE)
            a.sheathed = true
        }
        yieldAll(scene.play(script {
            val base = a.entityPos
            val front = f.facing(a)
            val chest = base.add(0.0, 0.9, 0.0)
            // Detail: tief und nah, er sinkt aufs Knie.
            scene.line(chest.add(front.multiply(2.6)).add(-0.9, -0.3, 0.0), chest.add(front.multiply(2.0)).add(-0.7, -0.2, 0.0), chest, chest.add(0.0, -0.2, 0.0), 32, 48.0, 42.0)
            a.play(AriusPose.KNEEL)
            a.sheathed = false
            wait(30)
            // Halbkreis um ihn, während er spricht.
            val ang = Math.toDegrees(kotlin.math.atan2(front.z, front.x))
            scene.orbit(base, 3.4, 1.1, ang - 25.0, ang + 35.0, 96, 50.0, 46.0, lookAt = a, lookOffset = 1.0, radiusTo = 3.0, heightTo = 1.3)
            shown { f.say("end.weapon", 90) }
            wait(95)
            // Weite Einstellung: das leere Gewölbe, er steht auf; Solvane ist wieder Klinge (kein Drache mehr, V5).
            val rest = f.arenaCenter
            val focus = base.lerp(rest, 0.35).add(0.0, 1.5, 0.0)
            val away = flatDir(rest, base).let { if (it.lengthSquared() < 0.5) front else it }
            scene.line(f.clampToArena(base.add(away.multiply(9.0)).add(3.0, 3.0, 0.0), 2.0), f.clampToArena(base.add(away.multiply(12.0)).add(4.0, 6.5, 0.0), 2.0),
                focus, focus.add(0.0, 0.5, 0.0), 85, 60.0, 64.0)
            a.play(AriusPose.IDLE)
            a.sheathed = true
            shown { f.say("end.take", 80) }
            f.victory()
            wait(67)
            scene.home(18)
            wait(18)
        }))
        val s = state()
        val partner = f.target()
        if (partner != null) {
            val data = s.player(partner.uuid)
            val heaven = HhaSets.get(Identifier.of("hha", "heaven"))
            val hell = HhaSets.get(Identifier.of("hha", "hell"))
            when {
                heaven != null && heaven.state(partner).anyPiece && "end.heaven" !in data.heard -> {
                    f.say("end.heaven", 90, to = listOf(partner)); data.heard.add("end.heaven"); wait(95)
                }
                hell != null && hell.state(partner).anyPiece && "end.hell" !in data.heard -> {
                    f.say("end.hell", 80, to = listOf(partner)); data.heard.add("end.hell"); wait(85)
                }
            }
            s.save()
        }
        f.nivaris?.let { f.face(a, it.entityPos) }
        val firstTimers = f.audience().filter { "end.nivaris" !in s.player(it.uuid).heard }
        if (firstTimers.isNotEmpty()) {
            f.say("end.nivaris", 70, to = firstTimers)
            markHeard("end.nivaris", firstTimers)
        }
    }

    // --- Training ------------------------------------------------------------------------------------------------------

    fun trainingGlacier(): Script = script {
        split(animate = false)
        yieldAll(waitForFar())
        val a = f.arius ?: return@script
        f.place(a, f.abs(ArenaLayout.AERIE_SEAT))
        a.play(AriusPose.SEATED)
        // Abschnitt II: Solvane ist der Drache, er sitzt ohne Schwert.
        a.bladeState = AriusBlade.NONE
        a.sheathed = false
        for ((i, p) in f.players().withIndex()) {
            val s = f.abs(ArenaLayout.AERIE_LANDING[i % ArenaLayout.AERIE_LANDING.size])
            p.teleport(f.world, s.x, s.y, s.z, emptySet(), 90f, 0f, false)
        }
        f.spawnNivaris(f.abs(Vec3d.of(ArenaLayout.AERIE)).add(0.5, 0.0, 0.5), 90f)
        f.say("echo", 60)
        wait(60)
        yieldAll(glacier())
    }

    private fun prepareVault() {
        split(animate = false)
        val a = f.arius ?: return
        f.setPhase(P.VAULT)
        val vault = f.abs(Vec3d.of(ArenaLayout.VAULT)).add(0.5, 0.0, 0.5)
        f.place(a, vault)
        a.form = AriusForm.WHITE
        a.bladeState = AriusBlade.ICE
        a.sheathed = false
        a.play(AriusPose.IDLE)
        f.spawnNivaris(f.abs(ArenaLayout.VAULT_REST), 180f)
        f.nivaris?.resonance = true
        for ((i, p) in f.players().withIndex()) {
            val s = f.abs(ArenaLayout.VAULT_LANDING[i % ArenaLayout.VAULT_LANDING.size])
            p.teleport(f.world, s.x, s.y, s.z, emptySet(), 180f, 0f, false)
        }
    }

    fun trainingVault(): Script = script {
        yieldAll(waitForFar())
        prepareVault()
        f.say("echo", 60)
        wait(60)
        yieldAll(vaultFight())
    }

    fun trainingFinale(): Script = script {
        yieldAll(waitForFar())
        prepareVault()
        f.dragonDowned = true
        f.say("echo", 60)
        wait(60)
        yieldAll(finale())
    }

    private companion object {
        /** Ticks von der Auflösung des Drachen bis zu seinem Verschwinden (Höhepunkt des Lichts, wie DragonDissolve.PEAK). */
        const val RETURN_PEAK = 14

        /** Flugzeit des Lichtstroms vom Drachen in die Scheide. */
        const val RETURN_TRAVEL = 28
    }

    private fun flatDir(from: Vec3d, to: Vec3d): Vec3d {
        val d = Vec3d(to.x - from.x, 0.0, to.z - from.z)
        return if (d.lengthSquared() < 1e-6) Vec3d(0.0, 0.0, 1.0) else d.normalize()
    }
}
