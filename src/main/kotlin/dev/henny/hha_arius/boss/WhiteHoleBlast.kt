package dev.henny.hha_arius.boss

import dev.henny.hha.logic.Fx
import dev.henny.hha.logic.Vfx
import dev.henny.hha.logic.boss.Cine
import dev.henny.hha.logic.boss.Telegraph
import dev.henny.hha_arius.net.FxKind
import net.minecraft.block.Blocks
import net.minecraft.entity.decoration.DisplayEntity
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Das weiße Loch mit Wendung (seit 1.11.0, Spieler: „Irgendwie passiert da nicht wirklich was“).
 *
 * 1. **Druck**: Arius hebt die Klinge, der Kern stößt alle nach außen. Goldene Windschatten schützen vor den Druckwellen.
 * 2. **Die Umkehr**: Mitten im Angriff kippt das Loch. Die Welt blitzt, der Boden reißt in Schollen auf, die um den Kern
 *    kreisen, und der Druck wird zum Sog. Der Kern selbst ist jetzt tödlich.
 * 3. **Gegenspiel**: Drei Lichtsplitter stehen im Feld. Wer kurz in einem steht, schleudert ihn in den Kern. Genug Splitter
 *    (allein zwei, in der Gruppe drei) und die Energie schlägt auf Arius zurück: großer Schaden, Solvane stürzt ab, beide
 *    liegen lange offen.
 * 4. **Eruption** sonst: Das Loch entlädt sich über das ganze Feld; Schutz bieten nur noch die Stellen der Splitter.
 *
 * Wer ihn schon in der Druckphase genug trifft, löst die Umkehr früher aus und braucht einen Splitter weniger.
 */
class WhiteHoleBlast(private val f: EirholmFight) {

    private val rnd = java.util.Random()

    private class Splinter(val pos: Vec3d, val e: DisplayEntity.BlockDisplayEntity?) {
        var charge = 0
        var fed = false
    }

    private class Slab(val e: DisplayEntity.BlockDisplayEntity, val angle: Double, val radius: Double, val height: Double, val size: Vector3f, val spin: Float)

    private fun flatDir(from: Vec3d, to: Vec3d): Vec3d {
        val d = Vec3d(to.x - from.x, 0.0, to.z - from.z)
        return if (d.lengthSquared() < 1e-6) Vec3d(0.0, 0.0, 1.0) else d.normalize()
    }

    private fun flatDist(a: Vec3d, b: Vec3d): Double {
        val dx = a.x - b.x
        val dz = a.z - b.z
        return sqrt(dx * dx + dz * dz)
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

    /** Solvane zieht lange Sternbahnen über das Feld (nie enge Kreise); hält immer eine laufende Bahn bereit. */
    private inner class StarFlight(private val d: NivarisEntity, private val c: Vec3d, private val radius: Double) {
        private var index = rnd.nextInt(5)
        private var run: RailRun? = null

        fun step() {
            val r = run
            if (r == null || r.done) {
                val pts = (1..5).map { k ->
                    val ang = (index + k * 2) % 5 * 2 * PI / 5
                    f.ringPoint(c, radius, ang).add(0.0, 12.0, 0.0)
                }
                index = (index + 2) % 5
                run = RailRun(f, d, pts, 1.15).also { it.step() }
                return
            }
            r.step()
        }
    }

    fun run(finale: Boolean = false): Script = script {
        val a = f.arius ?: return@script
        val d = f.nivaris?.takeIf { !f.dragonDowned && !finale }
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
        val total = PRESSURE + REVERSE + ERUPT
        f.fxAll(FxKind.WHITE_HOLE, core, a = 14f, b = total.toFloat(), life = total + 60)
        f.ambient("core.form", core, 5f)
        Vfx.skyMood(f.world, Vfx.Style.STORM, c, 0.6f, total, 60f)
        val star = d?.let {
            it.flying = true
            it.resonance = true
            it.play(NivarisPose.GLIDE)
            f.fx(FxKind.THREAD, core, life = PRESSURE, e1 = a, e2 = it)
            StarFlight(it, c, (R * 0.8).coerceAtLeast(DragonRail.MIN_RADIUS + 12.0))
        }
        f.ariusVulnerable = true
        val startHp = f.ariusHp
        val focus = 40f * (1f + 0.4f * (f.startCount - 1))
        f.hint("hha_arius.hint.white_hole", Formatting.GOLD)

        // --- 1) Druck ----------------------------------------------------------------------------------------------
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
        var early = false
        var i = 0
        while (i < PRESSURE) {
            val k = i / PRESSURE.toDouble()
            if (startHp - f.ariusHp >= focus) {
                early = true
                break
            }
            star?.step()
            if (d != null && i % 12 == 0) shown { Vfx.beam(f.world, Vfx.Style.SOUL, d.entityPos.add(0.0, 3.0, 0.0), core, 1.2f, 10) }
            val push = 0.04 + 0.11 * k
            for (p in f.players()) {
                val prev = last.put(p.uuid, p.entityPos)
                if (Hold.isHeld(p)) continue
                val dist = flatDist(p.entityPos, c)
                val sheltered = spots.any { flatDist(p.entityPos, it) <= SHELTER }
                if (dist > R - 2.5 && i % 10 == 0) {
                    f.hurt(p, 2f, Damage.Kind.PRESSURE)
                    dev.henny.hha_arius.gear.Frostbite.add(p, 8f)
                }
                if (i % 2 != 0 || sheltered || dist < EYE || dist > R + 6.0) continue
                val est = prev?.let { p.entityPos.subtract(it) } ?: Vec3d.ZERO
                val out = flatDir(c, p.entityPos)
                val wall = if (dist > R - 1.5) 0.0 else 1.0
                val near = (1.0 - dist / (R + 4.0)).coerceIn(0.35, 1.0)
                Fx.launchPlayer(p, Vec3d(est.x * 0.9 + out.x * push * near * wall, est.y.coerceIn(-0.6, 0.6), est.z * 0.9 + out.z * push * near * wall))
            }
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
                if (i + PULSE_EVERY < PRESSURE) {
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

        // --- 2) Die Umkehr -----------------------------------------------------------------------------------------
        f.calls.reset()
        f.fxAll(FxKind.WHITE_HOLE, core, b = -2f, life = 1)
        f.ambient("core.collapse", core, 6f, 0.5f)
        f.ambient("cut.reverse_wind", core, 5f, 0.7f)
        f.ambient("glacier.collapse", c, 5f, 0.6f)
        f.ambient("transform.pulse", core, 5f, 0.5f)
        shown {
            f.fxAll(FxKind.FLASH, core, a = 0.5f, life = 10)
            f.fxAll(FxKind.TIME_SLOW, core, a = 0.8f, life = 22)
            Vfx.shockwave(f.world, Vfx.Style.GOLD, c, R.toFloat() + 1f, 22, 2.4f)
        }
        f.calls.roomShake(c, 7f, 70f)
        for (p in f.audience()) Cine.title(listOf(p), "hha_arius.feat.white_hole_twist", "hha_arius.feat.white_hole_twist.sub", 50)
        val need = (if (f.players().size <= 1) 2 else 3) - (if (early) 1 else 0)
        val slabs = ripFloor(c, R)
        val splinterR = (R * 0.5).coerceIn(6.5, 15.0)
        val base = rnd.nextDouble() * 2 * PI
        val splinters = (0 until 3).map { k ->
            val at = f.ringPoint(c, splinterR, base + k * 2 * PI / 3)
            val e = f.shards.chunk(Blocks.SEA_LANTERN.defaultState, at.add(0.0, 1.5, 0.0), Vector3f(0.8f, 1.8f, 0.8f), Quaternionf(), REVERSE + 8, grow = 4)
            Splinter(at, e)
        }
        val marks = f.calls.boss(pace = Telegraph.Pace.ROOM, style = Vfx.Style.GOLD)
        for (s in splinters) marks.safe(s.pos, SPLINTER, REVERSE)
        val kill = marks.ring(c, KILL.toFloat(), REVERSE)
        fedHint(need, 0)
        var fed = 0
        var t = 0
        while (t < REVERSE && fed < need) {
            val k = t / REVERSE.toDouble()
            star?.step()
            orbit(slabs, c, t)
            for (s in splinters) {
                if (s.fed) continue
                s.e?.let { e ->
                    val bob = sin(t * 0.25) * 0.2
                    f.shards.move(e, s.pos.add(0.0, 1.5 + bob, 0.0), Quaternionf().rotateY(t * 0.18f), Vector3f(0.8f, 1.8f, 0.8f))
                }
                if (t % 8 == 0) shown { Vfx.beam(f.world, Vfx.Style.GOLD, s.pos.add(0.0, 0.2, 0.0), s.pos.add(0.0, 7.0, 0.0), 0.6f, 8) }
                val inside = f.players().count { flatDist(it.entityPos, s.pos) <= SPLINTER && !Hold.isHeld(it) }
                if (inside > 0) {
                    s.charge += inside
                    if (t % 4 == 0) f.ambient("resonance.chime", s.pos, 1.6f, 0.8f + s.charge * 0.05f)
                } else if (s.charge > 0 && t % 3 == 0) {
                    s.charge--
                }
                if (s.charge >= CHARGE) {
                    s.fed = true
                    fed++
                    s.e?.let { f.shards.remove(it) }
                    f.ambient("cut.strike", s.pos, 3.5f, 1.5f)
                    f.ambient("core.pulse", core, 5f, 1.6f)
                    shown {
                        Vfx.beam(f.world, Vfx.Style.GOLD, s.pos.add(0.0, 1.2, 0.0), core, 2.2f, 16)
                        Vfx.nova(f.world, Vfx.Style.GOLD, core, 3f + fed * 2f, 12)
                        f.fx(FxKind.CLEAR_WAVE, s.pos, a = 5f, life = 14)
                    }
                    f.calls.roomShake(core, 3f + fed, 60f)
                    fedHint(need, fed)
                }
            }
            val pull = 0.04 + 0.08 * k
            for (p in f.players()) {
                val prev = last.put(p.uuid, p.entityPos)
                if (Hold.isHeld(p)) continue
                val dist = flatDist(p.entityPos, c)
                if (dist < KILL && t % 10 == 0) {
                    f.hurt(p, 4f, Damage.Kind.PRESSURE)
                    dev.henny.hha_arius.gear.Frostbite.add(p, 10f)
                }
                val anchored = splinters.any { !it.fed && flatDist(p.entityPos, it.pos) <= SPLINTER }
                if (t % 2 != 0 || anchored || dist < 1.5 || dist > R + 6.0) continue
                val est = prev?.let { p.entityPos.subtract(it) } ?: Vec3d.ZERO
                val inward = flatDir(p.entityPos, c)
                Fx.launchPlayer(p, Vec3d(est.x * 0.9 + inward.x * pull, est.y.coerceIn(-0.6, 0.6), est.z * 0.9 + inward.z * pull))
            }
            if (t % 10 == 0) {
                f.ambient("core.pulse", core, 3f + 2f * k.toFloat(), 0.5f + 0.5f * k.toFloat())
                shown { f.fx(FxKind.REVERSE_WIND, core, a = (R * 0.7).toFloat(), life = 14) }
            }
            t++
            yield(Unit)
        }
        kill.let { marks.end() }
        for (s in splinters) if (!s.fed) s.e?.let { f.shards.remove(it) }

        if (fed >= need) {
            backlash(a, d, c, core, R, slabs)
            return@script
        }

        // --- 4) Eruption: die Splitterstellen sind die letzte Zuflucht ------------------------------------------------
        f.calls.reset()
        val refuge = splinters.map { it.pos }
        val final = f.calls.boss(pace = Telegraph.Pace.ROOM)
        for (s in refuge) final.safe(s, SHELTER + 0.6f, ERUPT + 2)
        val blast = final.ring(c, R.toFloat() + 1f, ERUPT)
        a.play(AriusPose.BREAKTHROUGH, blast.ticks)
        f.ambient("iai.glint", core, 4f, 0.6f)
        f.ambient("cut.charge", core, 4f, 0.5f)
        f.hint("hha_arius.hint.white_hole_erupt", Formatting.GOLD)
        for (j in 1..blast.ticks) {
            star?.step()
            orbit(slabs, c, REVERSE + j)
            if (j % 6 == 0) f.ambient("core.pulse", core, 4f, 1.4f + j * 0.01f)
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
        throwSlabs(slabs, c)
        for (p in f.players()) {
            if (!blast.contains(p) || final.isSafe(p) || refuge.any { flatDist(p.entityPos, it) <= SHELTER + 0.6f }) continue
            f.hurt(p, 16f, Damage.Kind.PRESSURE)
            Fx.launchPlayer(p, flatDir(c, p.entityPos).multiply(1.2).add(0.0, 0.55, 0.0))
        }
        exhausted(a, d, AriusTimings.CORE_END_OPEN)
    }

    private fun fedHint(need: Int, fed: Int) {
        for (p in f.players()) p.sendMessage(Text.translatable("hha_arius.hint.white_hole_feed", fed, need).formatted(Formatting.GOLD), true)
    }

    /** Die Spieler haben die Energie zurückgeworfen: Das Loch fällt auf Arius, Solvane stürzt ab. */
    private suspend fun SequenceScope<Unit>.backlash(a: AriusEntity, d: NivarisEntity?, c: Vec3d, core: Vec3d, R: Double, slabs: List<Slab>) {
        f.calls.reset()
        f.ariusVulnerable = false
        f.ambient("core.pulse", core, 6f, 2.0f)
        shown { f.fxAll(FxKind.TIME_SLOW, core, a = 1f, life = 26) }
        wait(10)
        f.fxAll(FxKind.WHITE_HOLE, core, b = -1f, life = 1)
        f.ambient("core.collapse", core, 6f, 0.6f)
        f.ambient("transform.shatter", core, 5f, 0.6f)
        f.ambient("cut.strike", c, 5f, 0.5f)
        f.farVanilla(net.minecraft.sound.SoundEvents.ENTITY_GENERIC_EXPLODE.value(), c, 5f, 0.6f)
        shown {
            f.fxAll(FxKind.FLASH, core, a = 0.7f, life = 14)
            Vfx.shockwave(f.world, Vfx.Style.GOLD, c, R.toFloat() + 2f, 30, 3.2f)
            f.fx(FxKind.CLEAR_WAVE, c, a = R.toFloat() + 2f, life = 30)
            for (q in 0 until 8) f.fxAll(FxKind.DEBRIS_CLOUD, f.ringPoint(c, 4.0 + q * 2.0, q * 0.9), a = 5f, life = 36)
        }
        f.calls.roomShake(c, 8f, 80f)
        throwSlabs(slabs, c)
        val wound = f.moves.summit.limit(f.ariusMax * BACKLASH)
        f.ariusHp = (f.ariusHp - wound).coerceAtLeast(1f)
        if (d != null) f.dragonHp = (f.dragonHp - f.dragonMax * BACKLASH).coerceAtLeast(0f)
        f.updateBars()
        for (p in f.players()) {
            p.sendMessage(Text.translatable("hha_arius.hint.white_hole_backlash").formatted(Formatting.GOLD), true)
            Advancements.grant(p, "takt_break")
        }
        exhausted(a, d, AriusTimings.CORE_END_OPEN + 40)
    }

    /** Schollen reißen aus dem Boden (nur sichtbar, keine Blockänderung). */
    private fun ripFloor(c: Vec3d, R: Double): List<Slab> {
        val out = ArrayList<Slab>()
        if (Cine.muted) return out
        val count = 16
        for (k in 0 until count) {
            val ang = k * 2 * PI / count + rnd.nextDouble() * 0.3
            val dist = 4.0 + rnd.nextDouble() * (R * 0.6).coerceAtMost(16.0)
            val at = f.ringPoint(c, dist, ang)
            val below = f.world.getBlockState(BlockPos.ofFloored(at.x, f.floorY - 0.5, at.z))
            val state = if (below.isAir || !below.isOpaqueFullCube) Blocks.PACKED_ICE.defaultState else below
            val size = Vector3f(1.2f + rnd.nextFloat() * 1.6f, 0.5f + rnd.nextFloat() * 0.5f, 1.2f + rnd.nextFloat() * 1.6f)
            val e = f.shards.chunk(state, at.add(0.0, 0.3, 0.0), size, Quaternionf(), REVERSE + ERUPT + 30) ?: continue
            out.add(Slab(e, ang, dist.coerceAtMost(11.0), 2.5 + rnd.nextDouble() * 5.0, size, 0.05f + rnd.nextFloat() * 0.1f))
            if (k % 3 == 0) f.world.spawnParticles(net.minecraft.particle.BlockStateParticleEffect(net.minecraft.particle.ParticleTypes.BLOCK, state), at.x, f.floorY + 0.2, at.z, 12, 0.6, 0.1, 0.6, 0.12)
        }
        return out
    }

    private fun orbit(slabs: List<Slab>, c: Vec3d, t: Int) {
        if (t % 2 != 0) return
        for (s in slabs) {
            val rise = (t / 30.0).coerceAtMost(1.0)
            val r = s.radius * (1.0 - 0.25 * (t / REVERSE.toDouble()).coerceAtMost(1.0))
            val ang = s.angle + t * 0.035
            val at = Vec3d(c.x + sin(ang) * r, f.floorY + 0.3 + s.height * rise + sin(t * 0.08 + s.angle) * 0.4, c.z + cos(ang) * r)
            f.shards.move(s.e, at, Quaternionf().rotateY(t * s.spin).rotateX(t * s.spin * 0.7f), s.size, 2)
        }
    }

    /** Beim Ende fliegen die Schollen nach außen und zerfallen. */
    private fun throwSlabs(slabs: List<Slab>, c: Vec3d) {
        if (slabs.isEmpty()) return
        f.task(script {
            val starts = slabs.map { it.e.entityPos }
            for (t in 1..14) {
                for ((n, s) in slabs.withIndex()) {
                    if (s.e.isRemoved) continue
                    val out = flatDir(c, starts[n])
                    val k = t / 14.0
                    f.shards.move(s.e, starts[n].add(out.multiply(16.0 * k)).add(0.0, 3.0 * sin(k * PI) - 4.0 * k * k, 0.0), Quaternionf().rotateY(t * 0.4f).rotateX(t * 0.3f), s.size, 2)
                }
                yield(Unit)
            }
            for (s in slabs) {
                if (s.e.isRemoved) continue
                val at = s.e.entityPos
                f.world.spawnParticles(net.minecraft.particle.BlockStateParticleEffect(net.minecraft.particle.ParticleTypes.BLOCK, s.e.blockState), at.x, at.y, at.z, 6, 0.3, 0.2, 0.3, 0.1)
                f.shards.remove(s.e)
            }
        })
    }

    /** Nach dem weißen Loch: er sinkt zu Boden und steht offen, Solvane kommt über die Bahn herunter und liegt erschöpft. */
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
        var used = 0
        if (d != null && !f.dragonDowned) {
            val toward = flatDir(c, d.entityPos)
            val ground = f.clampToArena(c.add(toward.multiply((f.arenaRadius * 0.35).coerceIn(10.0, 22.0))), 4.0).let { Vec3d(it.x, f.floorY, it.z) }
            d.play(NivarisPose.GLIDE)
            val run = RailRun(f, d, listOf(ground.add(0.0, 5.0, 0.0)), 1.7)
            var guard = 0
            while (!run.done && guard++ < 90) {
                run.step()
                used++
                yield(Unit)
            }
            val low = d.entityPos
            for (j in 1..8) {
                val k = j / 8.0
                f.place(d, low.lerp(ground, k * k))
                used++
                yield(Unit)
            }
            f.place(d, ground)
            d.flying = false
            d.play(NivarisPose.EXHAUSTED)
            f.ambient("nivaris.crash", ground, 4f, 0.8f)
            f.openDragon((ticks - used).coerceAtLeast(40))
        }
        f.dragonVulnerable = !f.dragonDowned && d != null
        wait((ticks - used).coerceAtLeast(20))
    }

    companion object {
        /** Druckphase bis zur Umkehr (Ticks). */
        const val PRESSURE = 76

        /** Abstand der Druckwellen (= ihre Ansage). */
        const val PULSE_EVERY = 34

        /** Dauer der Umkehr: so lange bleibt Zeit, die Splitter in den Kern zu werfen. */
        const val REVERSE = 170

        /** Ansage der Eruption. */
        const val ERUPT = 44

        /** Radius des stillen Auges um Arius (Druckphase). */
        const val EYE = 3.5

        /** Radius eines Windschattens. */
        const val SHELTER = 2.6f

        /** Radius eines Lichtsplitters. */
        const val SPLINTER = 2.6f

        /** So viele Spieler-Ticks im Splitter, bis er in den Kern fliegt. */
        const val CHARGE = 18

        /** In der Umkehr ist der Kern selbst gefährlich (Radius). */
        const val KILL = 4.0

        /** Rückschlag: Anteil des Lebens von Arius und Solvane. */
        const val BACKLASH = 0.12f
    }
}
