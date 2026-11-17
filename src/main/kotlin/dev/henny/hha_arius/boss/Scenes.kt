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
                f.say("intro.seal", 70); wait(76)
                f.say("intro.seas", 70); wait(76)
                a.play(AriusPose.RISE); wait(30)
                f.say("intro.reason", 85); wait(90)
                f.say("intro.test", 85); wait(50)
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
        f.ariusMax = BossConfig.health(520f, f.startCount)
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
            // Stille nach dem Schnitt: nah an ihm, die Klinge ausgestreckt, die Naht leuchtet über den Boden.
            val face = stand.add(0.0, 1.5, 0.0)
            post.line(stand.add(2.6, 1.0, 3.2), stand.add(2.9, 1.3, 3.9), face, face.add(0.0, -0.3, 2.0), AriusTimings.CUT_SILENCE + 2, 48.0, 50.0)
            shown { Vfx.crescent(f.world, Vfx.Style.HEAVEN, stand.add(0.0, 1.5, 0.0), Vec3d(0.0, 0.0, 1.0), 110f, 5f, 12) }
            f.fx(FxKind.SEAM, f.abs(ArenaLayout.CUT_FROM).add(0.0, 0.2, 0.0), f.abs(ArenaLayout.CUT_TO).add(0.0, 0.2, 0.0), a = 14f, life = AriusTimings.CUT_SILENCE + 16)
            wait(AriusTimings.CUT_SILENCE)
            // Weite Totale: hoch im Südwesten über dem Plateau, die Osthälfte gleitet ab, die Kamera schwenkt langsam mit.
            val temple = f.abs(Vec3d.of(ArenaLayout.TEMPLE))
            post.line(rel(-44.0, 30.0, -12.0), rel(-50.0, 34.0, -20.0), temple.add(2.0, 8.0, 0.0), temple.add(12.0, 6.0, 0.0), AriusTimings.CUT_SLIDE + 20, 60.0, 66.0)
            split()
            f.task(script {
                wait(AriusTimings.CUT_SLIDE)
                for ((pos, state) in ArenaBuilder.cutRubble(f.world, f.origin)) {
                    if (f.world.getBlockState(pos).isAir) f.edits.set(pos, state)
                }
                cue("ridge.break", f.abs(Vec3d.of(ArenaLayout.TEMPLE)).add(18.0, 2.0, 0.0), 4f, 0.5f)
            })
            wait(40)
            shown { f.sceneText("cut_after") }
            for (id in f.cutSurvivors) f.server.playerManager.getPlayer(id)?.let { Advancements.grant(it, "under_the_line") }
            wait(AriusTimings.CUT_SLIDE - 30)
            yieldAll(kick(post))
        }))
        yieldAll(arrival())
    }

    /** Der Tempel trennt sich: Momentaufnahme an alle, echte Blöcke weg, Kluft im Süden. */
    fun split(animate: Boolean = true) {
        val blocks = ArenaBuilder.templeEastHalf(f.world, f.origin)
        if (animate && blocks.isNotEmpty()) {
            val center = f.abs(Vec3d.of(ArenaLayout.TEMPLE))
            val payload = SliceS2C(
                f.slot, f.origin.x + ArenaLayout.CUT_X, f.origin.y.toDouble(), center.z, 1.0, 0.0,
                0, AriusTimings.CUT_SLIDE,
                LongArray(blocks.size) { blocks[it].first.asLong() },
                IntArray(blocks.size) { Block.getRawIdFromState(blocks[it].second) },
            )
            AriusNet.near(f.world, center, payload)
        }
        for ((pos, _) in blocks) f.edits.clear(pos)
        val cleft = ArenaBuilder.southCleft(f.world, f.origin)
        for (pos in cleft) {
            val st = f.world.getBlockState(pos)
            if (animate && rnd.nextInt(6) == 0) {
                f.world.spawnParticles(BlockStateParticleEffect(ParticleTypes.BLOCK, st), pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, 4, 0.4, 0.4, 0.4, 0.05)
            }
            f.edits.clear(pos)
        }
        if (!animate) {
            for ((pos, state) in ArenaBuilder.cutRubble(f.world, f.origin)) if (f.world.getBlockState(pos).isAir) f.edits.set(pos, state)
            return
        }
        f.ambient("cut.split", f.abs(Vec3d.of(ArenaLayout.TEMPLE)), 6f)
        f.ambient("glacier.crack", f.abs(ArenaLayout.CUT_TO), 6f, 0.7f)
        f.calls.roomShake(f.abs(Vec3d.of(ArenaLayout.TEMPLE)), 6f, 80f)
        val cutX = f.origin.x + ArenaLayout.CUT_X
        for (p in f.players()) if (p.x > cutX) Fx.launchPlayer(p, Vec3d(-0.8, 0.3, 0.0))
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
        a.play(AriusPose.GATE_KICK, windup)
        cue("arius.parry", a.entityPos, 2.5f, 0.9f)
        wait(windup)
        cue("arius.kick", a.entityPos, 3.5f, 0.85f)
        cue("hold.impact", a.entityPos, 2.5f)
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
        yieldAll(glacier())
    }

    // --- II: Gletscherschwelle ----------------------------------------------------------------------------------------

    fun glacier(): Script = script {
        f.setPhase(P.GLACIER)
        f.reviveAll(ArenaLayout.LANDING)
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
            if (cycles == 1) {
                val fresh = newcomers("home.astoria")
                if (f.say(if (fresh.isNotEmpty()) "home.astoria" else "home.far", 110, combat = true)) markHeard("home.astoria")
            } else if (cycles == 2) {
                val s = state()
                val summoner = s.player(f.summonerId)
                val rumors = listOf("rumor.olympus", "rumor.messenger", "rumor.gate")
                if (f.say(rumors[summoner.rumorIndex % rumors.size], 120, combat = true)) {
                    summoner.rumorIndex++
                    s.save()
                }
            }
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
     * Gletschersturz und Verwandlung als eine Szene mit Kamera (überspringbar; keine Ansage, kein Schaden: die Risse sind
     * nur Information, die Spieler fallen mit Sanftem Fall). Ablauf der Kamera:
     * 1. Spannung: nah an Arius, der pfeift; hinter ihm steigt Nivaris auf, die Augen glühen beim Faden.
     * 2. Stille von oben: die Risse laufen über das Plateau.
     * 3. Der Sturzflug von unten, das Sichtfeld weitet sich.
     * 4. Weite Totale von außen, während die Ringe in die Tiefe brechen.
     * 5. Im Gewölbe, Blick nach oben durch das Loch: Trümmer und Fallende.
     * 6. Verwandlung: Detail, dann ein Seitenschwenk, der Nivaris hinter ihm zeigt; Rückweg zur Spielerkamera.
     */
    fun collapse(): Script = script {
        f.arius ?: return@script
        f.nivaris ?: return@script
        val scene = CineScene(f) {}
        yieldAll(scene.play(script {
            yieldAll(collapseBody(scene))
            yieldAll(transformBody(scene))
        }))
        yieldAll(vaultFight())
    }

    private fun collapseBody(scene: CineScene): Script = script {
        val a = f.arius ?: return@script
        val d = f.nivaris ?: return@script
        f.setPhase(P.COLLAPSE)
        f.calls.reset()
        f.music("music.transform", 10)
        f.atmos(dev.henny.hha_arius.net.AtmosMode.BLIZZARD, 30)
        val center = f.abs(Vec3d.of(ArenaLayout.PLATEAU)).add(0.5, 0.0, 0.5)
        // Sie steigt auf, Arius pfeift: Resonanz.
        d.play(NivarisPose.HOVER)
        d.flying = true
        val high = center.add(0.0, 16.0, 0.0)
        val from = d.entityPos
        a.sheathed = false
        a.play(AriusPose.WHISTLE)
        shown { f.say("fall.together", 50) }
        // 1) Nah an ihm, leicht von unten; der Blick hebt sich mit ihr.
        val face = a.entityPos.add(0.0, 1.6, 0.0)
        val toward = flatDir(a.entityPos, center).let { if (it.lengthSquared() < 0.5) f.facing(a) else it }
        val side = Vec3d(-toward.z, 0.0, toward.x)
        scene.line(face.add(toward.multiply(2.6)).add(side.multiply(0.9)).add(0.0, -0.5, 0.0), face.add(toward.multiply(2.0)).add(side.multiply(0.7)).add(0.0, -0.4, 0.0),
            face, face.add(toward.multiply(-6.0)).add(0.0, 5.0, 0.0), 40, 50.0, 56.0)
        for (i in 1..40) {
            f.place(d, from.lerp(high, i / 40.0))
            f.face(d, center)
            if (i == 20) {
                f.fx(FxKind.THREAD, a.entityPos, life = 40, e1 = a, e2 = d)
                cue("resonance.chime", a.entityPos, 3f)
                a.eyeGlow = 100
                d.eyeGlow = 100
            }
            yield(Unit)
        }
        // 2) Stille von oben: die Risse laufen über das Plateau (nur Information, kein Schaden).
        scene.line(center.add(-4.0, 15.0, 10.0), center.add(-3.0, 12.5, 8.0), center, center.add(0.0, 0.0, -1.0), 24 + 10, 62.0, 66.0)
        for ((i, r) in ArenaLayout.COLLAPSE_RINGS.withIndex()) {
            shown { Vfx.rune(f.world, Vfx.Style.STORM, center.add(0.0, 0.05, 0.0), r.toFloat(), 60 - i * 10) }
            cue("glacier.crack", center.add(r, 0.0, 0.0), 4f, 0.8f + i * 0.1f)
            wait(8)
        }
        for (p in f.players()) {
            p.addStatusEffect(StatusEffectInstance(StatusEffects.SLOW_FALLING, 200, 0, false, false, true))
        }
        // 3) Der Sturzflug von unten, das Sichtfeld weitet sich.
        scene.line(center.add(11.0, 1.2, 9.0), center.add(12.5, 1.0, 10.5), center.add(0.0, 14.0, 0.0), center, 24, 58.0, 76.0, lookAt = d, lookOffset = 1.5)
        d.play(NivarisPose.CRASH_DIVE, 24)
        val dive = d.entityPos
        for (i in 1..24) {
            f.place(d, dive.lerp(center, i / 24.0))
            yield(Unit)
        }
        // 4) Weite Totale von außen: der Sturm reißt im Aufprall auf, die Ringe brechen in die Tiefe.
        f.atmos(dev.henny.hha_arius.net.AtmosMode.SNOW, 12)
        scene.line(center.add(-24.0, 12.0, 20.0), center.add(-28.0, 16.0, 24.0), center.add(0.0, -2.0, 0.0), center.add(0.0, -8.0, 0.0), 40, 62.0, 68.0)
        shown { f.sceneText("collapse", 60) }
        cue("glacier.collapse", center, 6f)
        cue("nivaris.crash", center, 5f)
        f.calls.roomShake(center, 7f, 90f)
        for (ring in 0..2) {
            val falling = ArenaBuilder.collapseRing(f.world, f.origin, ring)
            // Sichtbarer Absturz: eine Auswahl der Blöcke stürzt taumelnd in die Tiefe (nur Darstellung).
            val sample = falling.filter { !f.world.getBlockState(it).isAir }.shuffled(java.util.Random(ring.toLong())).take(420)
            if (sample.isNotEmpty() && !Cine.muted) {
                val payload = dev.henny.hha_arius.gear.art.ArtCutS2C(
                    LongArray(sample.size) { sample[it].asLong() },
                    IntArray(sample.size) { net.minecraft.block.Block.getRawIdFromState(f.world.getBlockState(sample[it])) }, 3,
                )
                for (p in f.audience()) dev.henny.hha_arius.net.AriusNet.send(p, payload)
            }
            shown {
                val chunkFrom = center.add(rnd.nextGaussian() * 6, 1.0, rnd.nextGaussian() * 6)
                f.fx(FxKind.ICE_CHUNK, chunkFrom, chunkFrom.add(rnd.nextGaussian() * 4, -30.0, rnd.nextGaussian() * 4), a = 1.6f, life = 30)
            }
            for (pos in falling) {
                if (!Cine.muted && rnd.nextInt(40) == 0) f.world.spawnParticles(BlockStateParticleEffect(ParticleTypes.BLOCK, f.world.getBlockState(pos)), pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, 6, 0.5, 0.5, 0.5, 0.1)
                f.edits.clear(pos)
            }
            wait(10)
        }
        // Nivaris und Arius fallen ins Gewölbe.
        val vault = f.abs(Vec3d.of(ArenaLayout.VAULT)).add(0.5, 0.0, 0.5)
        f.place(d, f.abs(ArenaLayout.VAULT_REST))
        d.flying = false
        d.play(NivarisPose.EXHAUSTED)
        f.place(a, vault.add(0.0, 9.0, 0.0))
        // 5) Im Gewölbe, Blick nach oben durch das Loch in der Decke: Trümmer und Fallende.
        scene.line(vault.add(9.0, 2.0, 12.0), vault.add(7.0, 3.0, 10.0), vault.add(0.0, 18.0, 0.0), vault.add(0.0, 10.0, 0.0), 120, 72.0, 64.0)
        // Wer noch oben steht, wird an die Kante geführt und fällt ebenfalls.
        wait(60)
        for ((i, p) in f.players().withIndex()) {
            if (p.y > vault.y + 6) {
                val drop = f.ringPoint(center, 8.0, i * 1.3).add(0.0, -4.0, 0.0)
                p.addStatusEffect(StatusEffectInstance(StatusEffects.SLOW_FALLING, 200, 0, false, false, true))
                p.teleport(f.world, drop.x, drop.y, drop.z, emptySet(), p.yaw, p.pitch, false)
            }
        }
        waitUntil(140) { f.players().all { it.y < vault.y + 2.5 } }
        for ((i, p) in f.players().withIndex()) {
            if (p.y > vault.y + 2.5) {
                val spot = f.abs(ArenaLayout.VAULT_LANDING[i % ArenaLayout.VAULT_LANDING.size])
                p.teleport(f.world, spot.x, spot.y, spot.z, emptySet(), p.yaw, p.pitch, false)
            }
            p.onLanding()
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
                runWhile(resonance(resonanceIndex++ % 3)) { f.ariusHp > 0f }
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
        yieldAll(finale())
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
            1 -> yieldAll(crystalStorm())
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
        f.reviveAll(ArenaLayout.VAULT_LANDING)
        // Treffer zählen im Finale nur als Konter im offenen Fenster (siehe EirholmFight.hitArius).
        f.ariusVulnerable = true
        f.dragonVulnerable = false
        f.music("music.finale", 60)
        f.nivaris?.let { d ->
            d.flying = false
            d.resonance = false
            d.lead = false
            f.place(d, f.abs(ArenaLayout.VAULT_REST))
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
        while (f.exchangesWon < 3) {
            val t = f.target()
            if (t == null) {
                wait(20)
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
        val a = f.arius ?: return@script
        f.place(a, f.abs(ArenaLayout.WATCH_SEAT))
        a.play(AriusPose.SEATED)
        // Abschnitt II: Solvane ist der Drache, er sitzt ohne Schwert.
        a.bladeState = AriusBlade.NONE
        a.sheathed = false
        for ((i, p) in f.players().withIndex()) {
            val s = f.abs(ArenaLayout.LANDING[i % ArenaLayout.LANDING.size])
            p.teleport(f.world, s.x, s.y, s.z, emptySet(), 180f, 0f, false)
        }
        f.spawnNivaris(f.abs(Vec3d(0.5, 0.0, -4.5)), 180f)
        f.say("echo", 60)
        wait(60)
        yieldAll(glacier())
    }

    private fun prepareVault() {
        split(animate = false)
        for (ring in 0..2) for (pos in ArenaBuilder.collapseRing(f.world, f.origin, ring)) f.edits.clear(pos)
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
        prepareVault()
        f.say("echo", 60)
        wait(60)
        yieldAll(vaultFight())
    }

    fun trainingFinale(): Script = script {
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
