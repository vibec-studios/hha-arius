package dev.henny.hha_arius.boss

import dev.henny.hha.logic.Fx
import dev.henny.hha.logic.Vfx
import dev.henny.hha.logic.boss.Cine
import dev.henny.hha.logic.boss.Telegraph
import dev.henny.hha_arius.net.FxKind
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.util.Formatting
import net.minecraft.util.math.Vec3d
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Das weiße Loch im Herzgewölbe (seit 1.10.0 neu, Crix: „das weiße Loch zieht mich rein, statt mich wegzustoßen“). Ein
 * weißes Loch **stößt ab**: Arius springt in die Mitte und hebt die Klinge, über ihm entsteht der Kern, und ein immer
 * stärkerer Druck treibt alle nach außen gegen die Sturmwand am Rand (dort drückt sie zusammen: Schaden, Frost).
 *
 * Gegenspiel:
 * * **Windschatten**: drei goldene Felder auf halbem Weg zum Rand; darin ist der Druck gebrochen. Nach jeder Druckwelle
 *   wandern sie ein Stück weiter – man muss umziehen.
 * * **Das Auge**: Direkt an Arius (Radius [EYE]) ist es still. Wer sich gegen den Druck zu ihm durchkämpft, kann ihn
 *   schlagen; genug Schaden bricht seine Konzentration, das Loch fällt vorzeitig in sich zusammen.
 * * **Druckwellen** ([PULSES]) laufen angesagt über das Feld: getroffen wird, wer weder im Windschatten noch im Auge
 *   steht. Zum Schluss die **Eruption** über das ganze Feld (lange Ansage, gleiche Zuflucht).
 *
 * Danach stehen Arius und Solvane erschöpft und offen.
 */
class WhiteHoleBlast(private val f: EirholmFight) {

    private val rnd = java.util.Random()

    private fun flatDir(from: Vec3d, to: Vec3d): Vec3d {
        val d = Vec3d(to.x - from.x, 0.0, to.z - from.z)
        return if (d.lengthSquared() < 1e-6) Vec3d(0.0, 0.0, 1.0) else d.normalize()
    }

    private inline fun shown(block: () -> Unit) {
        if (!Cine.muted) block()
    }

    private fun firstTime(flag: String): List<ServerPlayerEntity> {
        val st = AriusState.get(f.server)
        val fresh = f.audience().filter { flag !in st.player(it.uuid).heard }
        for (p in f.audience()) st.player(p.uuid).heard.add(flag)
        st.save()
        return fresh
    }

    fun run(): Script = script {
        val a = f.arius ?: return@script
        val d = f.nivaris?.takeIf { !f.dragonDowned }
        f.calls.reset()
        f.ariusVulnerable = false
        f.dragonVulnerable = false
        val c = f.arenaCenter.let { Vec3d(it.x, f.floorY, it.z) }
        val core = c.add(0.0, 6.0, 0.0)
        val R = f.arenaRadius
        val fresh = firstTime("scene.white_hole")
        if (fresh.isNotEmpty()) f.sceneText("white_hole", 70, fresh)
        for (p in f.audience()) Cine.title(listOf(p), "hha_arius.feat.white_hole", "hha_arius.feat.white_hole.sub", 50)
        // Arius springt in die Mitte und hebt die Klinge.
        val from = a.entityPos
        a.play(AriusPose.LAUNCH, 6)
        f.ambient("arius.wind_step", from, 2.4f, 0.8f)
        for (i in 1..10) {
            f.place(a, from.lerp(c, i / 10.0).add(0.0, sin(i / 10.0 * PI) * 3.0, 0.0))
            yield(Unit)
        }
        f.place(a, c)
        a.play(AriusPose.CORE_RAISE)
        val total = BUILD + ERUPT
        f.fxAll(FxKind.WHITE_HOLE, core, a = 14f, b = total.toFloat(), life = total + 60)
        f.ambient("core.form", core, 5f)
        Vfx.skyMood(f.world, Vfx.Style.STORM, c, 0.6f, total, 60f)
        d?.let {
            it.flying = true
            it.resonance = true
            f.fx(FxKind.THREAD, core, life = BUILD, e1 = a, e2 = it)
        }
        f.ariusVulnerable = true
        val startHp = f.ariusHp
        val focus = 55f * (1f + 0.4f * (f.startCount - 1))
        f.hint("hha_arius.hint.white_hole", Formatting.GOLD)

        // Windschatten: drei Felder auf halbem Weg, nach jeder Welle um 50° weiter.
        var shelterAngle = rnd.nextDouble() * 2 * PI
        val shelterR = (R * 0.55).coerceIn(7.0, 16.0)
        fun shelters(): List<Vec3d> = (0 until 3).map { f.ringPoint(c, shelterR, shelterAngle + it * 2 * PI / 3) }
        var spots = shelters()
        var group = f.calls.boss(pace = Telegraph.Pace.AREA)
        for (s in spots) group.safe(s, SHELTER, PULSE_EVERY + 4)
        var wave = group.ring(c, R.toFloat() + 1f, PULSE_EVERY, inner = EYE.toFloat())
        var waveAt = f.now + wave.ticks
        var pulses = 0
        val last = HashMap<java.util.UUID, Vec3d>()
        var broken = false
        var i = 0
        while (i < BUILD) {
            val k = i / BUILD.toDouble()
            if (startHp - f.ariusHp >= focus) {
                broken = true
                break
            }
            // Solvane kreist hoch über dem Rand und speist den Kern.
            if (d != null) {
                val ang = i / 40.0 * PI
                f.place(d, f.ringPoint(c, R * 0.8, ang).add(0.0, 9.0, 0.0))
                f.face(d, core)
                if (i == 8) d.play(NivarisPose.BREATH)
                if (i % 12 == 0) shown { Vfx.beam(f.world, Vfx.Style.SOUL, d.entityPos.add(0.0, 3.0, 0.0), core, 1.2f, 10) }
            }
            // Wachsender Druck nach außen (alle 2 Ticks); im Windschatten und im Auge Ruhe, am Rand die Sturmwand.
            val push = 0.035 + 0.12 * k
            for (p in f.players()) {
                val prev = last.put(p.uuid, p.entityPos)
                if (Hold.isHeld(p)) continue
                val dist = flatDist(p.entityPos, c)
                val sheltered = spots.any { flatDist(p.entityPos, it) <= SHELTER }
                if (dist > R - 2.5 && i % 10 == 0) {
                    f.hurt(p, 2f, Damage.Kind.PRESSURE)
                    dev.henny.hha_arius.gear.Frostbite.add(p, 8f)
                    shown { f.world.spawnParticles(net.minecraft.particle.ParticleTypes.SNOWFLAKE, p.x, p.y + 1.0, p.z, 12, 0.4, 0.6, 0.4, 0.15) }
                }
                if (i % 2 != 0 || sheltered || dist < EYE || dist > R + 6.0) continue
                val est = prev?.let { p.entityPos.subtract(it) } ?: Vec3d.ZERO
                val out = flatDir(c, p.entityPos)
                // An der Sturmwand drückt es nicht weiter hinaus (man wird daran gepresst, nicht über den Rand geworfen).
                val wall = if (dist > R - 1.5) 0.0 else 1.0
                val near = (1.0 - dist / (R + 4.0)).coerceIn(0.35, 1.0)
                Fx.launchPlayer(p, Vec3d(est.x * 0.9 + out.x * push * near * wall, est.y.coerceIn(-0.6, 0.6), est.z * 0.9 + out.z * push * near * wall))
            }
            // Druckwelle (angesagt): trifft, wer weder im Windschatten noch im Auge steht.
            if (f.now >= waveAt) {
                pulses++
                f.ambient("core.pulse", core, 4f, 0.8f + 0.25f * pulses)
                f.ambient("cut.strike", c, 2.5f, 1.4f)
                shown {
                    Vfx.shockwave(f.world, Vfx.Style.HEAVEN, c, R.toFloat() + 1f, 18, 1.8f)
                    f.fx(FxKind.CLEAR_WAVE, c, a = R.toFloat() + 1f, life = 18)
                    f.fx(FxKind.REVERSE_WIND, core, a = 18f, life = 16)
                }
                f.calls.roomShake(c, 2.5f + pulses, 60f)
                for (p in wave.hits(f.players())) {
                    if (group.isSafe(p) || spots.any { flatDist(p.entityPos, it) <= SHELTER }) continue
                    f.hurt(p, 5f, Damage.Kind.PRESSURE)
                    Fx.launchPlayer(p, flatDir(c, p.entityPos).multiply(0.7).add(0.0, 0.35, 0.0))
                }
                if (i + PULSE_EVERY < BUILD) {
                    shelterAngle += Math.toRadians(50.0) * (if (pulses % 2 == 0) 1 else -1)
                    spots = shelters()
                    group = f.calls.boss(pace = Telegraph.Pace.AREA)
                    for (s in spots) group.safe(s, SHELTER, PULSE_EVERY + 4)
                    wave = group.ring(c, R.toFloat() + 1f, PULSE_EVERY, inner = EYE.toFloat())
                    waveAt = f.now + wave.ticks
                } else {
                    waveAt = Long.MAX_VALUE
                }
            }
            val every = (20 - (k * 12).toInt()).coerceAtLeast(6)
            if (i % every == 0) {
                f.ambient("core.pulse", core, 2.5f + 2f * k.toFloat(), 0.7f + 0.7f * k.toFloat())
                shown { Vfx.nova(f.world, Vfx.Style.HEAVEN, core, (2.0 + 6.0 * k).toFloat(), 10) }
            }
            i++
            yield(Unit)
        }
        if (broken) {
            // Konzentration gebrochen: Das Loch fällt vorzeitig in sich zusammen.
            f.calls.reset()
            f.fxAll(FxKind.WHITE_HOLE, core, b = -1f, life = 1)
            f.ambient("core.collapse", core, 5f, 1.4f)
            f.ambient("transform.shatter", core, 4f, 0.8f)
            shown {
                f.fxAll(FxKind.FLASH, core, a = 0.3f, life = 8)
                Vfx.shockwave(f.world, Vfx.Style.HEAVEN, c, 8f, 16, 2f)
            }
            for (p in f.players()) if (flatDist(p.entityPos, c) < 6.0) Fx.launchPlayer(p, flatDir(c, p.entityPos).multiply(0.8).add(0.0, 0.4, 0.0))
            f.hint("hha_arius.hint.white_hole_break", Formatting.GOLD)
            for (p in f.players()) Advancements.grant(p, "takt_break")
            exhausted(a, d, AriusTimings.CORE_END_OPEN + 20)
            return@script
        }
        // Eruption: ein letzter, langer Aufbau; sicher nur im Windschatten oder im Auge.
        f.calls.reset()
        shelterAngle += Math.toRadians(35.0)
        spots = shelters()
        val final = f.calls.boss(pace = Telegraph.Pace.ROOM)
        for (s in spots) final.safe(s, SHELTER, ERUPT + 2)
        val blast = final.ring(c, R.toFloat() + 1f, ERUPT, inner = EYE.toFloat())
        a.play(AriusPose.BREAKTHROUGH, blast.ticks)
        f.ambient("iai.glint", core, 4f, 0.6f)
        f.ambient("cut.charge", core, 4f, 0.5f)
        f.hint("hha_arius.hint.white_hole_erupt", Formatting.GOLD)
        for (j in 1..blast.ticks) {
            if (j % 6 == 0) f.ambient("core.pulse", core, 4f, 1.4f + j * 0.01f)
            // Der Druck bleibt, schwächer: man soll den Windschatten noch erreichen können.
            if (j % 2 == 0) for (p in f.players()) {
                if (Hold.isHeld(p)) continue
                val dist = flatDist(p.entityPos, c)
                if (dist < EYE || dist > R - 1.5 || spots.any { flatDist(p.entityPos, it) <= SHELTER }) continue
                val out = flatDir(c, p.entityPos)
                Fx.launchPlayer(p, p.velocity.multiply(0.9).add(out.multiply(0.05)))
            }
            yield(Unit)
        }
        f.fxAll(FxKind.WHITE_HOLE, core, b = -1f, life = 1)
        f.ambient("core.collapse", core, 6f)
        f.ambient("cut.strike", c, 5f, 0.6f)
        f.ambient("glacier.collapse", c, 5f, 0.7f)
        f.farVanilla(net.minecraft.sound.SoundEvents.ENTITY_GENERIC_EXPLODE.value(), c, 5f, 0.5f)
        shown {
            f.fxAll(FxKind.FLASH, core, a = 0.55f, life = 12)
            f.fxAll(FxKind.TIME_SLOW, core, a = 0.6f, life = 14)
            Vfx.shockwave(f.world, Vfx.Style.HEAVEN, c, R.toFloat() + 2f, 30, 3f)
            f.fx(FxKind.CLEAR_WAVE, c, a = R.toFloat() + 2f, life = 30)
            for (q in 0 until 6) f.fxAll(FxKind.DEBRIS_CLOUD, f.ringPoint(c, 8.0 + q * 3.0, q * 1.1), a = 6f, life = 40)
        }
        f.calls.roomShake(c, 8f, 80f)
        for (p in f.players()) {
            if (!blast.contains(p) || final.isSafe(p) || spots.any { flatDist(p.entityPos, it) <= SHELTER }) continue
            f.hurt(p, 16f, Damage.Kind.PRESSURE)
            Fx.launchPlayer(p, flatDir(c, p.entityPos).multiply(1.2).add(0.0, 0.55, 0.0))
        }
        exhausted(a, d, AriusTimings.CORE_END_OPEN)
    }

    /** Nach dem weißen Loch: er sinkt zu Boden und steht offen, Solvane landet erschöpft. */
    private suspend fun SequenceScope<Unit>.exhausted(a: AriusEntity, d: NivarisEntity?, ticks: Int) {
        val c = f.arenaCenter.let { Vec3d(it.x, f.floorY, it.z) }
        val top = a.entityPos
        for (i in 1..6) {
            f.place(a, top.lerp(c, i / 6.0))
            yield(Unit)
        }
        f.place(a, c)
        f.ariusVulnerable = true
        a.play(AriusPose.STAGGER)
        f.openArius(ticks)
        if (d != null && !f.dragonDowned) {
            val ground = f.clampToArena(Vec3d(d.x, f.floorY, d.z), 4.0)
            f.place(d, ground)
            d.flying = false
            d.play(NivarisPose.EXHAUSTED)
            f.openDragon(ticks)
        }
        f.dragonVulnerable = !f.dragonDowned
        wait(ticks)
    }

    private fun flatDist(a: Vec3d, b: Vec3d): Double {
        val dx = a.x - b.x
        val dz = a.z - b.z
        return sqrt(dx * dx + dz * dz)
    }

    companion object {
        /** Aufbau mit wachsendem Druck (Ticks). */
        const val BUILD = 108

        /** Abstand der Druckwellen (= ihre Ansage). */
        const val PULSE_EVERY = 34

        /** Ansage der Eruption. */
        const val ERUPT = 44

        /** Radius des stillen Auges um Arius. */
        const val EYE = 3.5

        /** Radius eines Windschattens. */
        const val SHELTER = 2.6f
    }
}
