package dev.henny.hha_arius.boss

import dev.henny.hha.api.set.HhaSets
import dev.henny.hha.logic.Fx
import dev.henny.hha.logic.Vfx
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

    fun intro(): Script = script {
        val a = f.arius ?: return@script
        f.ariusVulnerable = false
        f.music("music.temple", 40)
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

    // --- I: Tempel ----------------------------------------------------------------------------------------------------------

    fun temple(): Script = script {
        val a = f.arius ?: return@script
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

    // --- Der Schnitt -------------------------------------------------------------------------------------------------------

    fun theCut(): Script = script {
        val a = f.arius ?: return@script
        f.setPhase(P.CUT)
        f.ariusVulnerable = false
        f.music(null, 30)
        // Sprung auf die Altarstufe.
        val stand = f.abs(ArenaLayout.CUT_STAND)
        val start = a.entityPos
        f.ambient("arius.wind_step", start, 2f, 0.7f)
        for (i in 1..12) {
            val k = i / 12.0
            f.place(a, start.lerp(stand, k).add(0.0, sin(k * PI) * 2.0, 0.0))
            yield(Unit)
        }
        a.yaw = 0f; a.headYaw = 0f; a.bodyYaw = 0f
        a.play(AriusPose.CUT_CHARGE)
        f.fx(FxKind.REVERSE_WIND, stand.add(0.0, 2.0, 0.0), a = 30f, life = AriusTimings.CUT_WIND + 50)
        f.ambient("cut.reverse_wind", stand, 4f)
        wait(20)
        f.fx(FxKind.EDGE, stand, life = 70, e1 = a)
        f.ambient("cut.charge", stand, 3f)
        wait(AriusTimings.CUT_LINE_AT - 20)
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
        Vfx.crescent(f.world, Vfx.Style.HEAVEN, stand.add(0.0, 1.5, 0.0), Vec3d(0.0, 0.0, 1.0), 110f, 5f, 12)
        f.fx(FxKind.SEAM, f.abs(ArenaLayout.CUT_FROM).add(0.0, 0.2, 0.0), f.abs(ArenaLayout.CUT_TO).add(0.0, 0.2, 0.0), a = 14f, life = AriusTimings.CUT_SILENCE + 16)
        wait(AriusTimings.CUT_SILENCE)
        split()
        f.task(script {
            wait(AriusTimings.CUT_SLIDE)
            for ((pos, state) in ArenaBuilder.cutRubble(f.world, f.origin)) {
                if (f.world.getBlockState(pos).isAir) f.edits.set(pos, state)
            }
            f.ambient("ridge.break", f.abs(Vec3d.of(ArenaLayout.TEMPLE)).add(18.0, 2.0, 0.0), 4f, 0.5f)
        })
        wait(40)
        f.sceneText("cut_after")
        for (id in f.cutSurvivors) f.server.playerManager.getPlayer(id)?.let { Advancements.grant(it, "under_the_line") }
        wait(AriusTimings.CUT_SLIDE - 30)
        yieldAll(gateKick())
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

    /** Übergang I → II: Tritt durchs Tor, Nivaris erscheint über den Landeplätzen. */
    fun gateKick(): Script = script {
        val a = f.arius ?: return@script
        val players = f.players()
        val kickTarget = f.target()
        if (kickTarget != null) {
            val front = kickTarget.entityPos.add(0.0, 0.0, -1.8)
            val from = a.entityPos
            for (i in 1..10) {
                f.place(a, from.lerp(Vec3d(front.x, f.floorY, front.z), i / 10.0).add(0.0, sin(i / 10.0 * PI) * 1.2, 0.0))
                yield(Unit)
            }
            f.face(a, kickTarget.entityPos)
        }
        for (p in players) Hold.freeze(p, 20)
        a.play(AriusPose.GATE_KICK, AriusTimings.ARIUS_WINDUP[AriusPose.GATE_KICK])
        f.ambient("arius.parry", a.entityPos, 2.5f, 0.9f)
        wait(AriusTimings.ARIUS_WINDUP[AriusPose.GATE_KICK])
        f.ambient("arius.kick", a.entityPos, 3.5f, 0.85f)
        f.ambient("hold.impact", a.entityPos, 2.5f)
        val gate = f.abs(Vec3d.of(ArenaLayout.GATE))
        Vfx.shockwave(f.world, Vfx.Style.HEAVEN, gate.add(0.5, 0.0, 0.5), 6f, 16, 3f)
        for (i in 0 until 40) {
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
        // Nivaris erscheint: gleitet über das Plateau heran und landet vor den Landeplätzen.
        val spot = f.abs(Vec3d(0.5, 0.0, -4.5))
        f.spawnNivaris(spot.add(0.0, 18.0, 30.0), 180f)
        val d = f.nivaris ?: return@script
        d.flying = true
        d.play(NivarisPose.GLIDE)
        f.sound("nivaris.wing", d.entityPos, 4f, 0.7f)
        // Arius setzt sich auf die stehengebliebene Tempelhälfte.
        wait(6)
        val seat = f.abs(ArenaLayout.WATCH_SEAT)
        f.place(a, seat)
        a.yaw = 0f; a.headYaw = 0f; a.bodyYaw = 0f
        a.play(AriusPose.SEATED)
        for (i in 1..24) {
            f.place(d, d.entityPos.lerp(spot.add(0.0, 1.0, 0.0), i / 24.0 * 0.35))
            f.face(d, f.abs(Vec3d(0.5, 0.0, -20.5)))
            yield(Unit)
        }
        f.place(d, spot)
        d.flying = false
        d.play(NivarisPose.LAND, 1)
        f.ambient("nivaris.land", spot, 4f)
        Vfx.shockwave(f.world, Vfx.Style.SOUL, spot, 7f, 18, 1.2f)
        f.fx(FxKind.CLEAR_WAVE, spot, a = 10f, life = 18)
        wait(12)
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

    fun collapse(): Script = script {
        val a = f.arius ?: return@script
        val d = f.nivaris ?: return@script
        f.setPhase(P.COLLAPSE)
        f.calls.reset()
        f.music(null, 40)
        val center = f.abs(Vec3d.of(ArenaLayout.PLATEAU)).add(0.5, 0.0, 0.5)
        // Sie steigt auf, Arius pfeift: Resonanz.
        d.play(NivarisPose.HOVER)
        d.flying = true
        val high = center.add(0.0, 16.0, 0.0)
        val from = d.entityPos
        a.sheathed = false
        a.play(AriusPose.WHISTLE)
        f.say("fall.together", 50)
        for (i in 1..40) {
            f.place(d, from.lerp(high, i / 40.0))
            f.face(d, center)
            if (i == 20) {
                f.fx(FxKind.THREAD, a.entityPos, life = 40, e1 = a, e2 = d)
                f.ambient("resonance.chime", a.entityPos, 3f)
                a.eyeGlow = 100
                d.eyeGlow = 100
            }
            yield(Unit)
        }
        // Risse laufen über das Plateau (nur Information, kein Schaden).
        for ((i, r) in ArenaLayout.COLLAPSE_RINGS.withIndex()) {
            Vfx.rune(f.world, Vfx.Style.STORM, center.add(0.0, 0.05, 0.0), r.toFloat(), 60 - i * 10)
            f.ambient("glacier.crack", center.add(r, 0.0, 0.0), 4f, 0.8f + i * 0.1f)
            wait(8)
        }
        for (p in f.players()) {
            p.addStatusEffect(StatusEffectInstance(StatusEffects.SLOW_FALLING, 200, 0, false, false, true))
        }
        d.play(NivarisPose.CRASH_DIVE, 24)
        val dive = d.entityPos
        for (i in 1..24) {
            f.place(d, dive.lerp(center, i / 24.0))
            yield(Unit)
        }
        f.sceneText("collapse", 60)
        f.ambient("glacier.collapse", center, 6f)
        f.ambient("nivaris.crash", center, 5f)
        f.calls.roomShake(center, 7f, 90f)
        for (ring in 0..2) {
            for (pos in ArenaBuilder.collapseRing(f.world, f.origin, ring)) {
                if (rnd.nextInt(40) == 0) f.world.spawnParticles(BlockStateParticleEffect(ParticleTypes.BLOCK, f.world.getBlockState(pos)), pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, 6, 0.5, 0.5, 0.5, 0.1)
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
        yieldAll(transform())
    }

    /** Die Verwandlung zur weißen Form, dann die Resonanz mit Nivaris (Konzept 5.4). */
    fun transform(): Script = script {
        val a = f.arius ?: return@script
        val d = f.nivaris ?: return@script
        val vault = f.abs(Vec3d.of(ArenaLayout.VAULT)).add(0.5, 0.0, 0.5)
        val top = a.entityPos
        a.play(AriusPose.TRANSFORM, AriusTimings.ARIUS_WINDUP[AriusPose.TRANSFORM])
        for (i in 1..10) {
            f.place(a, top.lerp(vault, (i / 10.0) * (i / 10.0)))
            yield(Unit)
        }
        f.ambient("nivaris.land", vault, 3f, 1.4f)
        f.fx(FxKind.FRAGMENTS, vault, a = 1f, life = 34, e1 = a)
        f.ambient("transform.shatter", vault, 4f)
        val fresh = newcomers("scene.transform")
        if (fresh.isNotEmpty()) f.sceneText("transform", 70, fresh)
        markHeard("scene.transform")
        wait(30)
        a.form = AriusForm.WHITE
        a.eyeGlow = 100
        f.ambient("transform.pulse", vault, 5f)
        f.fx(FxKind.CLEAR_WAVE, vault, a = 12f, life = 22)
        Vfx.shockwave(f.world, Vfx.Style.HEAVEN, vault, 12f, 20, 1.5f)
        for (pos in ArenaBuilder.snowAround(f.world, BlockPos.ofFloored(vault), 12)) f.edits.clear(pos)
        for (p in f.players()) if (p.squaredDistanceTo(vault) < 144.0) Fx.launchPlayer(p, Vec3d(p.x - vault.x, 0.0, p.z - vault.z).normalize().multiply(0.5).add(0.0, 0.25, 0.0))
        // Resonanz: Nivaris richtet sich hinter ihm auf.
        d.play(NivarisPose.RESONANCE)
        d.resonance = true
        d.eyeGlow = 100
        f.face(d, vault)
        f.fx(FxKind.THREAD, vault, life = 40, e1 = a, e2 = d)
        f.ambient("resonance.chime", vault, 4f, 0.9f)
        wait(30)
        a.play(AriusPose.IDLE)
        d.play(NivarisPose.IDLE)
        wait(40)
        yieldAll(vaultFight())
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

    private fun ending(): Script = script {
        val a = f.arius ?: return@script
        f.music("music.finale", 0)
        a.play(AriusPose.KNEEL)
        a.sheathed = false
        wait(30)
        f.say("end.weapon", 90)
        wait(95)
        a.play(AriusPose.IDLE)
        a.sheathed = true
        f.say("end.take", 80)
        f.victory()
        wait(85)
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

    private fun flatDir(from: Vec3d, to: Vec3d): Vec3d {
        val d = Vec3d(to.x - from.x, 0.0, to.z - from.z)
        return if (d.lengthSquared() < 1e-6) Vec3d(0.0, 0.0, 1.0) else d.normalize()
    }
}
