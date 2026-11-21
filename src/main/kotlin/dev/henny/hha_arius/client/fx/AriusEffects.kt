package dev.henny.hha_arius.client.fx

import dev.henny.hha.client.vfx.lerpColor
import dev.henny.hha.client.vfx.VfxDraw
import dev.henny.hha.client.vfx.VfxLayers
import dev.henny.hha.client.vfx.withAlpha
import dev.henny.hha_arius.net.FxKind
import dev.henny.hha_arius.net.FxS2C
import net.minecraft.block.Blocks
import net.minecraft.client.MinecraftClient
import net.minecraft.client.render.OverlayTexture
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.client.world.ClientWorld
import net.minecraft.entity.Entity
import net.minecraft.particle.ParticleTypes
import net.minecraft.util.math.RotationAxis
import net.minecraft.util.math.Vec3d
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Eigener Effekt des Addons (Client). Logik liegt auf dem Server; hier wird nur gezeichnet. */
abstract class AriusFx(val life: Int) {
    var age = 0
        private set

    fun advance(world: ClientWorld): Boolean {
        age++
        tick(world)
        return age < life
    }

    protected open fun tick(world: ClientWorld) {}

    /** Lange Begleiteffekte (Auren) werden bei vollem Speicher nicht als Erste verdrängt. */
    open val persistent: Boolean get() = false

    /** [t] = Alter inkl. Teil-Tick. */
    abstract fun render(d: VfxDraw, matrices: MatrixStack, t: Float)

    protected fun fadeInOut(t: Float, fadeIn: Float = 4f, fadeOut: Float = 8f): Float =
        min(1f, t / fadeIn).coerceAtLeast(0f) * min(1f, (life - t) / fadeOut).coerceIn(0f, 1f)

    companion object {
        const val WHITE = 0xFFFFFFFF.toInt()
        const val ICE = 0xFFB8E8FF.toInt()
        const val ICE_DEEP = 0xFF5A9FD8.toInt()
        const val GOLD = 0xFFFFD98A.toInt()
        val UP = Vec3d(0.0, 1.0, 0.0)
        val X = Vec3d(1.0, 0.0, 0.0)
        val Z = Vec3d(0.0, 0.0, 1.0)
        private val rnd = Random(7)

        fun random(): Random = rnd

        fun create(p: FxS2C, world: ClientWorld): AriusFx? {
            val pos = Vec3d(p.x, p.y, p.z)
            val dir = Vec3d(p.dx, p.dy, p.dz)
            val e1 = if (p.e1 >= 0) world.getEntityById(p.e1) else null
            val e2 = if (p.e2 >= 0) world.getEntityById(p.e2) else null
            return when (FxKind.entries.getOrNull(p.kind)) {
                FxKind.REVERSE_WIND -> ReverseWind(pos, p.a.toDouble(), p.life)
                FxKind.SEAM -> Seam(pos, dir, p.a.toDouble(), p.life)
                FxKind.EDGE -> e1?.let { Edge(it, p.life) }
                FxKind.FRAGMENTS -> e1?.let { Fragments(it, p.a.toDouble(), p.life) }
                FxKind.CORE -> Core(pos, p.a.toDouble(), p.life)
                FxKind.FRONT -> Front(pos, p.a.toDouble(), p.b.toDouble(), dir.x, dir.y.toInt(), dir.z, p.life)
                FxKind.THREAD -> if (e1 != null && e2 != null) Thread(e1, e2, p.life) else null
                FxKind.FROST_SHELL -> e1?.let { FrostShell(it, p.life) }
                FxKind.ICE_CHUNK -> IceChunk(pos, dir, p.a.toDouble(), p.life)
                FxKind.CRYSTAL -> Crystal(pos, dir, p.a.toInt(), p.life)
                FxKind.BLIZZARD -> Blizzard(pos, p.a.toDouble(), p.life)
                FxKind.CLEAR_WAVE -> ClearWave(pos, p.a.toDouble(), p.life)
                FxKind.BOLT -> ArtBolt(p.e1, pos, dir, p.a.toDouble(), p.b.toInt(), p.life).also { ArtBolts.register(it) }
                FxKind.BOLT_END -> ArtBolts.end(p.e1, pos, p.b.toInt(), p.a.toDouble())
                FxKind.FIELD -> FieldFx(pos, p.a.toDouble(), p.life)
                FxKind.BRIDGE -> BridgeFx(pos, Vec3d(p.dx, p.dy, p.dz), p.a.toDouble(), p.life)
                FxKind.TORNADO -> TornadoFx(pos, Vec3d(p.dx, p.dy, p.dz), p.a.toDouble(), p.life)
                FxKind.DRAGON_BREATH -> e1?.let { DragonBreathFx(it, p.a.toDouble(), p.life) }
                FxKind.DRAGON_TAIL -> e1?.let { DragonTailFx(it, p.b.toDouble(), p.a.toDouble(), p.life) }
                FxKind.ICE_ARMOR -> e1?.let { IceArmorFx(it, p.life) }
                FxKind.GIANT_BLADE -> e1?.let { GiantBladeFx(it, p.a.toDouble(), p.b.toInt(), p.life) }
                FxKind.FROZEN_STAR -> FrozenMoonFx(pos, p.a.toDouble(), p.b.toInt(), p.life)
                FxKind.DRAGON_AURA -> e1?.let { DragonAuraFx(it, p.life) }
                FxKind.SLASH_ARC -> SlashArcFx(pos, dir, p.a.toDouble(), p.b.toDouble(), p.life)
                FxKind.RAGE_AURA -> e1?.let { RageAuraFx(it, p.life) }
                FxKind.DASH_STREAK -> DashStreakFx(pos, dir, p.life)
                FxKind.CUT_PLANE -> CutPlaneFx(pos, dir, p.a.toDouble(), p.b >= 0.5f, p.life)
                FxKind.PULL -> e1?.let { PullFx(it, p.a.toDouble(), p.life) }
                FxKind.GALE -> GaleFx(pos, dir, p.a, p.life)
                FxKind.WHITE_HOLE -> if (p.b >= 0f) WhiteHoleFx(pos, p.a.toDouble(), p.b.toInt(), p.life) else {
                    // b = -2: die Umkehr (seit 1.11.0), sonst der Kollaps.
                    if (p.b <= -1.5f) AriusFxClient.invertWhiteHole(pos) else AriusFxClient.collapseWhiteHole(pos)
                    null
                }
                FxKind.BLADE_STREAM -> BladeStream(pos, dir, p.a.toInt(), p.b >= 0.5f, e1, p.life)
                FxKind.BLADE_SHATTER -> BladeShatter(e1, pos, p.life)
                FxKind.BLADE_FORM -> BladeForm(e1, pos, p.life)
                FxKind.DRAGON_DISSOLVE -> DragonDissolve(pos, dir, p.a.toDouble().coerceAtLeast(1.0), p.life)
                FxKind.FLASH -> {
                    if (AriusClientConfig.flash) ScreenFlash.start(p.a, p.life)
                    null
                }
                FxKind.DEBRIS_CLOUD -> DebrisCloudFx(pos, p.a.toDouble().coerceIn(1.0, if (p.b > 0.5f) 68.0 else 40.0), p.life, cloudOnly = p.b > 0.5f)
                FxKind.TIME_SLOW -> {
                    CineGrade.slow(p.life, p.a)
                    null
                }
                FxKind.KICK_FLING -> {
                    KickAnim.start(p.e1, p.a, p.b, p.life)
                    null
                }
                null -> null
            }
        }
    }
}

internal fun particlesAllowed(): Float = AriusClientConfig.fx.coerceIn(0f, 1f)

/** Der Wind kehrt sich um: Schnee und Staub strömen zum Punkt hin, die Umgebung wird still. */
class ReverseWind(private val center: Vec3d, private val radius: Double, life: Int) : AriusFx(life) {
    override fun tick(world: ClientWorld) {
        val n = (10 * particlesAllowed()).toInt()
        val r = random()
        repeat(n) {
            val a = r.nextDouble() * PI * 2
            val y = r.nextDouble() * 6 - 2
            val dist = radius * (0.5 + r.nextDouble() * 0.5)
            val from = Vec3d(center.x + cos(a) * dist, center.y + y, center.z + sin(a) * dist)
            val v = center.subtract(from).multiply(0.045)
            world.addParticleClient(if (r.nextInt(4) == 0) ParticleTypes.WHITE_ASH else ParticleTypes.SNOWFLAKE, from.x, from.y, from.z, v.x, v.y, v.z)
        }
    }

    override fun render(d: VfxDraw, matrices: MatrixStack, t: Float) {}
}

/** Haarfeine weiße Naht entlang des Schnitts. [height] > 0 zeigt zusätzlich eine kaum sichtbare senkrechte Fläche. */
class Seam(private val from: Vec3d, private val to: Vec3d, private val height: Double, life: Int) : AriusFx(life) {
    override fun render(d: VfxDraw, matrices: MatrixStack, t: Float) {
        val k = fadeInOut(t, 2f, 6f)
        d.band(AriusLayers.INK, from, to, UP, 0.085, withAlpha(0xFF103F66.toInt(), 0.75f * k), withAlpha(0xFF56C8FF.toInt(), 0.85f * k))
        d.band(AriusLayers.VFX_GLOW, from, to, UP, 0.055, withAlpha(WHITE, k), withAlpha(ICE, k))
        if (height > 0.0) {
            val mid = Vec3d(0.0, height / 2, 0.0)
            d.band(AriusLayers.VFX_GLOW, from.add(mid), to.add(mid), UP, height / 2, withAlpha(ICE, k * 0.22f), withAlpha(ICE, k * 0.10f))
        }
    }
}

/** Weiße Energie verdichtet sich an der Schneide (nicht am Körper). */
class Edge(private val e: Entity, life: Int) : AriusFx(life) {
    private fun blade(tick: Float): Pair<Vec3d, Vec3d> {
        val pos = e.getLerpedPos(tick)
        val yaw = Math.toRadians(e.yaw.toDouble())
        val right = Vec3d(-cos(yaw), 0.0, -sin(yaw))
        val base = pos.add(0.0, 1.9, 0.0).add(right.multiply(0.35))
        val tip = base.add(0.0, 1.2, 0.0).add(right.multiply(0.5))
        return base to tip
    }

    override fun tick(world: ClientWorld) {
        val (base, tip) = blade(1f)
        val r = random()
        repeat((3 * particlesAllowed()).toInt()) {
            val target = base.lerp(tip, r.nextDouble())
            val from = target.add(r.nextDouble() * 4 - 2, r.nextDouble() * 3 - 1, r.nextDouble() * 4 - 2)
            val v = target.subtract(from).multiply(0.12)
            world.addParticleClient(ParticleTypes.END_ROD, from.x, from.y, from.z, v.x, v.y, v.z)
        }
    }

    override fun render(d: VfxDraw, matrices: MatrixStack, t: Float) {
        val k = (t / life).coerceIn(0f, 1f)
        val (base, tip) = blade(d.tickDelta)
        val glow = withAlpha(WHITE, 0.4f + 0.6f * k)
        d.tube(AriusLayers.INK, listOf(base, tip), { 0.05 + 0.05 * k }, { withAlpha(0xFF144B78.toInt(), 0.55f * k) })
        d.tube(AriusLayers.VFX_GLOW, listOf(base, tip), { 0.045 + 0.065 * k }, { glow })
        d.billboard(AriusLayers.VFX_FLARE, tip.lerp(base, 0.3), 0.5 + 1.3 * k, withAlpha(ICE, 0.9f * k))
    }
}

/** Die dunklen Schichten lösen sich in schwebende Frostsplitter auf, die in Licht zerfallen. */
class Fragments(private val e: Entity, private val scale: Double, life: Int) : AriusFx(life) {
    private class Shard(var pos: Vec3d, val vel: Vec3d, val spin: Vec3d, val size: Double, val dark: Boolean)

    private val shards = ArrayList<Shard>()

    init {
        val r = random()
        val count = (46 * scale).toInt().coerceAtLeast(10)
        val base = e.entityPos
        repeat(count) {
            val a = r.nextDouble() * PI * 2
            val h = 0.2 + r.nextDouble() * 1.7
            val p = base.add(cos(a) * 0.35, h, sin(a) * 0.35)
            val out = Vec3d(cos(a), 0.0, sin(a))
            shards.add(Shard(p, out.multiply(0.02 + r.nextDouble() * 0.04).add(0.0, 0.02 + r.nextDouble() * 0.04, 0.0),
                Vec3d(r.nextDouble(), r.nextDouble(), r.nextDouble()).multiply(0.3), 0.06 + r.nextDouble() * 0.1 * (0.6 + scale * 0.4), r.nextInt(3) != 0))
        }
    }

    override fun tick(world: ClientWorld) {
        for (s in shards) s.pos = s.pos.add(s.vel.multiply(1.0 + age * 0.04))
    }

    override fun render(d: VfxDraw, matrices: MatrixStack, t: Float) {
        val k = (t / life).coerceIn(0f, 1f)
        for (s in shards) {
            val a = t * s.spin.x
            val ax = Vec3d(cos(a), sin(a) * 0.5, sin(a)).multiply(s.size)
            val ay = Vec3d(-sin(a) * 0.4, cos(a * 0.7), cos(a) * 0.3).normalize().multiply(s.size * 1.6)
            val az = ax.crossProduct(ay).normalize().multiply(s.size * 0.35)
            if (k < 0.75f) {
                val c = if (s.dark) 0xFF2E3442.toInt() else 0xFF5A7FAE.toInt()
                d.box(VfxLayers.SHARD_ALPHA, s.pos, ax, ay, az, withAlpha(c, 1f - k * 0.6f))
            }
            d.billboard(VfxLayers.GLOW_ADD, s.pos, s.size * 3.0, withAlpha(ICE, (k * 1.4f).coerceAtMost(1f) * (1f - k).coerceAtLeast(0f) * 2f))
        }
    }
}

/** Der weiße Kern: ein dichter, heller Punkt, der Luft, Schnee und Licht nach außen drückt. */
class Core(private val pos: Vec3d, private val radius: Double, life: Int) : AriusFx(life) {
    override fun tick(world: ClientWorld) {
        val r = random()
        repeat((8 * particlesAllowed()).toInt()) {
            val dir = Vec3d(r.nextDouble() - 0.5, (r.nextDouble() - 0.5) * 0.5, r.nextDouble() - 0.5).normalize()
            val from = pos.add(dir.multiply(radius))
            val v = dir.multiply(0.6 + r.nextDouble() * 0.5)
            world.addParticleClient(if (r.nextBoolean()) ParticleTypes.SNOWFLAKE else ParticleTypes.END_ROD, from.x, from.y, from.z, v.x, v.y, v.z)
        }
    }

    override fun render(d: VfxDraw, matrices: MatrixStack, t: Float) {
        val k = fadeInOut(t, 20f, 10f)
        val pulse = 1.0 + 0.08 * sin(t * 0.6)
        d.sphere(VfxLayers.GLOW_ADD, pos, radius * pulse * k, { withAlpha(WHITE, k) })
        d.billboard(VfxLayers.FLARE_ADD, pos, radius * 4.5 * pulse, withAlpha(ICE, 0.8f * k))
        d.billboard(VfxLayers.GLOW_ADD, pos, radius * 9.0, withAlpha(ICE, 0.25f * k))
        // Strahlen nach außen (kein Sog: sie wandern vom Kern weg).
        val n = 10
        for (i in 0 until n) {
            val a = i * 2 * PI / n + t * 0.01
            val dir = Vec3d(cos(a), 0.15 * sin(t * 0.1 + i), sin(a))
            val phase = ((t * 0.06 + i * 0.37) % 1.0)
            val s = pos.add(dir.multiply(radius + phase * 10))
            val e = s.add(dir.multiply(2.5))
            d.band(VfxLayers.GLOW_ADD, s, e, UP, 0.08, withAlpha(WHITE, k * (1f - phase.toFloat())), withAlpha(ICE, 0f))
        }
    }
}

/** Druckfront: Ringwand, die vom Kern nach außen läuft, mit Lücken in den Korridoren. */
class Front(
    private val center: Vec3d, private val speed: Double, private val maxR: Double,
    private val base: Double, private val count: Int, private val half: Double, life: Int,
) : AriusFx(life) {
    override fun render(d: VfxDraw, matrices: MatrixStack, t: Float) {
        val r = min(maxR, 3.0 + speed * t)
        val k = min(1f, (life - t) / 6f).coerceIn(0f, 1f)
        val segments = 96
        val h = 2.8
        val top = withAlpha(ICE, 0f)
        val bottom = withAlpha(WHITE, 0.85f * k)
        val darkBot = withAlpha(0xFF144B78.toInt(), 0.55f * k)
        val ink = d.buffer(AriusLayers.INK)
        for (i in 0 until segments) {
            val a0 = i * 2 * PI / segments
            val a1 = (i + 1) * 2 * PI / segments
            val mid = (a0 + a1) / 2
            if (inGap(mid, r)) continue
            val p0 = Vec3d(center.x + sin(a0) * r, center.y, center.z + cos(a0) * r)
            val p1 = Vec3d(center.x + sin(a1) * r, center.y, center.z + cos(a1) * r)
            with(d) {
                ink.put(p0, 0f, 1f, darkBot)
                ink.put(p1, 1f, 1f, darkBot)
                ink.put(p1.add(0.0, h * 0.7, 0.0), 1f, 0f, top)
                ink.put(p0.add(0.0, h * 0.7, 0.0), 0f, 0f, top)
            }
        }
        val vc = d.buffer(AriusLayers.VFX_GLOW)
        for (i in 0 until segments) {
            val a0 = i * 2 * PI / segments
            val a1 = (i + 1) * 2 * PI / segments
            val mid = (a0 + a1) / 2
            if (inGap(mid, r)) continue
            val p0 = Vec3d(center.x + sin(a0) * r, center.y, center.z + cos(a0) * r)
            val p1 = Vec3d(center.x + sin(a1) * r, center.y, center.z + cos(a1) * r)
            with(d) {
                vc.put(p0, 0f, 1f, bottom)
                vc.put(p1, 1f, 1f, bottom)
                vc.put(p1.add(0.0, h, 0.0), 1f, 0f, top)
                vc.put(p0.add(0.0, h, 0.0), 0f, 0f, top)
            }
        }
    }

    private fun inGap(angle: Double, r: Double): Boolean {
        if (count <= 0) return false
        for (i in 0 until count) {
            val c = base + i * 2 * PI / count
            var delta = angle - c
            while (delta > PI) delta -= 2 * PI
            while (delta < -PI) delta += 2 * PI
            if (abs(delta) < PI / 2 && abs(r * sin(delta)) <= half) return true
        }
        return false
    }
}

/** Goldener Resonanzfaden zwischen Arius' Brust und Nivaris' Herzkern (bzw. Spitze der Eisnadel im Windungs-Modus). */
class Thread(private val a: Entity, private val b: Entity, life: Int) : AriusFx(life) {
    override fun render(d: VfxDraw, matrices: MatrixStack, t: Float) {
        val k = fadeInOut(t, 5f, 8f)
        val from = a.getLerpedPos(d.tickDelta).add(0.0, 1.35, 0.0)
        val to = if (b is dev.henny.hha_arius.boss.NivarisEntity && b.mode == dev.henny.hha_arius.boss.NivarisMode.COIL) {
            val slot = Math.floorDiv(kotlin.math.floor(b.x).toInt() + dev.henny.hha_arius.arena.ArenaLayout.SLOT_SPACING / 2, dev.henny.hha_arius.arena.ArenaLayout.SLOT_SPACING)
            val o = dev.henny.hha_arius.arena.ArenaLayout.origin(slot)
            Vec3d.of(o).add(dev.henny.hha_arius.boss.EntrancePath.HEAD_RAISED)
        } else {
            val yaw = Math.toRadians(b.yaw.toDouble())
            val fwd = Vec3d(-sin(yaw), 0.0, cos(yaw))
            b.getLerpedPos(d.tickDelta).add(fwd.multiply(2.6)).add(0.0, 2.3, 0.0)
        }
        val path = (0..16).map { i ->
            val s = i / 16.0
            from.lerp(to, s).add(0.0, sin(s * PI) * 0.6 + sin(t * 0.3 + s * 8) * 0.05, 0.0)
        }
        d.tube(VfxLayers.GLOW_ADD, path, { 0.035 }, { withAlpha(GOLD, k) })
        d.tube(VfxLayers.GLOW_ADD, path, { 0.12 }, { withAlpha(GOLD, k * 0.25f) })
    }
}

/** Eishülle um die Füße eines gehaltenen Spielers. */
class FrostShell(private val e: Entity, life: Int) : AriusFx(life) {
    override fun render(d: VfxDraw, matrices: MatrixStack, t: Float) {
        val k = fadeInOut(t, 2f, 5f)
        val p = e.getLerpedPos(d.tickDelta).add(0.0, 0.45, 0.0)
        d.box(VfxLayers.SHARD_ALPHA, p, X.multiply(0.5), UP.multiply(0.45), Z.multiply(0.5), withAlpha(0xFFB8E8FF.toInt(), 0.7f * k))
        d.billboard(VfxLayers.GLOW_ADD, p, 1.2, withAlpha(ICE, 0.4f * k))
    }
}

/** Geworfener Eisbrocken: echter Block, dreht sich im Bogen zum Ziel. */
class IceChunk(private val from: Vec3d, private val to: Vec3d, private val size: Double, life: Int) : AriusFx(life) {
    override fun render(d: VfxDraw, matrices: MatrixStack, t: Float) {
        val s = (t / life).coerceIn(0f, 1f).toDouble()
        val pos = from.lerp(to, s).add(0.0, sin(s * PI) * 3.0, 0.0)
        val cam = d.cam
        matrices.push()
        matrices.translate(pos.x - cam.x, pos.y - cam.y, pos.z - cam.z)
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(t * 23f))
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(t * 17f))
        matrices.scale(size.toFloat(), size.toFloat(), size.toFloat())
        matrices.translate(-0.5, -0.5, -0.5)
        MinecraftClient.getInstance().blockRenderManager.renderBlockAsEntity(Blocks.PACKED_ICE.defaultState, matrices, d.consumers, LIGHT, OverlayTexture.DEFAULT_UV)
        matrices.pop()
    }

    companion object {
        const val LIGHT = (12 shl 4) or (15 shl 20)
    }
}

/** Eiskristall: hängt glitzernd in der Luft, fliegt dann seine Bahn. */
class Crystal(private val pos: Vec3d, private val end: Vec3d, private val hang: Int, life: Int) : AriusFx(life) {
    override fun render(d: VfxDraw, matrices: MatrixStack, t: Float) {
        val fly = ((t - hang) / max(1f, (life - hang).toFloat())).coerceIn(0f, 1f).toDouble()
        val p = pos.lerp(end, fly * fly).add(0.0, sin(t * 0.2) * 0.1 * (1 - fly), 0.0)
        val cam = d.cam
        matrices.push()
        matrices.translate(p.x - cam.x, p.y - cam.y, p.z - cam.z)
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(t * 9f))
        matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(45f))
        matrices.scale(0.55f, 0.9f, 0.55f)
        matrices.translate(-0.5, -0.5, -0.5)
        MinecraftClient.getInstance().blockRenderManager.renderBlockAsEntity(Blocks.BLUE_ICE.defaultState, matrices, d.consumers, IceChunk.LIGHT, OverlayTexture.DEFAULT_UV)
        matrices.pop()
        val glint = if (fly > 0.0) 0.9f else 0.35f + 0.25f * sin(t * 0.5f)
        d.billboard(VfxLayers.FLARE_ADD, p, 0.9, withAlpha(ICE, glint))
    }
}

/** Schneesturm des Siegels: Schnee fällt nach oben. */
class Blizzard(private val center: Vec3d, private val radius: Double, life: Int) : AriusFx(life) {
    override fun tick(world: ClientWorld) {
        val r = random()
        repeat((14 * particlesAllowed()).toInt()) {
            val a = r.nextDouble() * PI * 2
            val dist = r.nextDouble() * radius
            world.addParticleClient(ParticleTypes.SNOWFLAKE, center.x + cos(a) * dist, center.y + r.nextDouble() * 2, center.z + sin(a) * dist, 0.0, 0.12 + r.nextDouble() * 0.1, 0.0)
        }
    }

    override fun render(d: VfxDraw, matrices: MatrixStack, t: Float) {}
}

/** Flacher heller Druckring, der Schnee wegräumt. */
class ClearWave(private val center: Vec3d, private val radius: Double, life: Int) : AriusFx(life) {
    override fun tick(world: ClientWorld) {
        if (age > 6) return
        val r = random()
        val ring = radius * age / 6.0
        repeat((16 * particlesAllowed()).toInt()) {
            val a = r.nextDouble() * PI * 2
            world.addParticleClient(ParticleTypes.SNOWFLAKE, center.x + cos(a) * ring, center.y + 0.2, center.z + sin(a) * ring, cos(a) * 0.4, 0.05, sin(a) * 0.4)
        }
    }

    override fun render(d: VfxDraw, matrices: MatrixStack, t: Float) {
        val s = (t / life).coerceIn(0f, 1f).toDouble()
        val r = radius * (1 - (1 - s) * (1 - s))
        val k = (1f - s.toFloat())
        d.ring(AriusLayers.INK, center.add(0.0, 0.08, 0.0), X, Z, max(0.0, r - 0.9), r, withAlpha(0xFF144C76.toInt(), 0f), withAlpha(0xFF4FB8EE.toInt(), 0.72f * k), segments = 64)
        d.ring(AriusLayers.VFX_GLOW, center.add(0.0, 0.11, 0.0), X, Z, max(0.0, r - 1.3), r + 0.1, withAlpha(WHITE, 0f), withAlpha(ICE, 0.85f * k), segments = 64)
    }
}

/** Weißer Bildblitz (HUD), gedeckelt und abschaltbar. */
object ScreenFlash {
    private var strength = 0f
    private var total = 1
    private var left = 0

    fun start(a: Float, ticks: Int) {
        strength = a.coerceIn(0f, 0.6f)
        total = ticks.coerceAtLeast(1)
        left = total
    }

    fun tick() {
        if (left > 0) left--
    }

    fun render(context: net.minecraft.client.gui.DrawContext) {
        if (left <= 0) return
        val alpha = (strength * left / total * 255).toInt().coerceIn(0, 255)
        context.fill(0, 0, context.scaledWindowWidth, context.scaledWindowHeight, (alpha shl 24) or 0xF4FAFF)
    }
}


/**
 * Yang-Brücke als Bauwerk aus Licht (seit 1.3.1): unter der Bahn ein leuchtender Bogen (Parabel) mit Rippen, an beiden
 * Seiten Geländer aus Lichtbändern, an den Enden Lichtsäulen, über die Bahn wandern Lichtfunken.
 */
/**
 * Entstehen der Yang-Brücke (seit 1.4.2 eine steinerne Bogenbrücke aus echten Bauwerk-Blöcken): eine Lichtfront läuft
 * über die ganze Breite vom Spieler weg mit, wie die Blöcke wachsen; Funken steigen von der Front auf. Nach ~2 s vorbei.
 */
class BridgeFx(private val start: Vec3d, private val span: Vec3d, private val width: Double, life: Int) : AriusFx(life) {
    override fun tick(world: ClientWorld) {
        if (age > SHOW) return
        val len = span.length().coerceAtLeast(1.0)
        val f = (age / (len / 2.0 + 4.0)).coerceIn(0.0, 1.0)
        val p = start.add(span.multiply(f))
        val r = random()
        repeat((4 * particlesAllowed()).toInt()) {
            world.addParticleClient(ParticleTypes.END_ROD, p.x + (r.nextDouble() - 0.5) * width, p.y + 0.2, p.z + (r.nextDouble() - 0.5) * width, 0.0, 0.05, 0.0)
        }
    }

    override fun render(d: VfxDraw, matrices: MatrixStack, t: Float) {
        if (t > SHOW) return
        val k = (min(1f, t / 3f) * ((SHOW - t) / 10f).coerceIn(0f, 1f))
        val len = span.length().coerceAtLeast(1.0)
        val fwd = span.multiply(1.0 / len)
        var side = fwd.crossProduct(UP)
        if (side.lengthSquared() < 1e-4) side = X
        side = side.normalize()
        // Die Blöcke wachsen mit 2 Blöcken je Tick (Reihenfolge = Abstand / 2); die Front läuft mit.
        val f = (t / (len / 2.0 + 0.5)).coerceIn(0.0, 1.0)
        val front = start.add(fwd.multiply(f * len)).add(0.0, -0.3, 0.0)
        val half = width * 0.5 + 0.3
        d.band(VfxLayers.GLOW_ADD, front.add(side.multiply(-half)), front.add(side.multiply(half)), UP, 0.9, withAlpha(0xFFFFFFFF.toInt(), 0.75f * k), withAlpha(0xFFFFFFFF.toInt(), 0.75f * k))
        d.billboard(VfxLayers.FLARE_ADD, front, width * 0.7, withAlpha(0xFFFFFFFF.toInt(), 0.6f * k))
        d.band(VfxLayers.FLOW_ADD, start.add(0.0, 0.55, 0.0), front.add(0.0, 0.85, 0.0), side, half, withAlpha(0xFFEFF4FF.toInt(), 0.05f * k), withAlpha(0xFFFFFFFF.toInt(), 0.35f * k), uScale = 0.3f, uOffset = -t * 0.1f)
    }

    private companion object {
        const val SHOW = 40f
    }
}

/**
 * Wirbelsturm (seit 1.5.1 krasser, Crix): ein 14 Blöcke hoher Trichter, unten schmal, oben weit ausladend, aus dichten
 * Wolkenschwaden, die spiralig aufsteigen, gestreiften Windbändern, Blitzen aus Eislicht und echten Schnee- und
 * Eisbrocken, die mitgerissen werden und oben herumgeschleudert werden; am Boden ein Staubring. Am Ende reißt er auf.
 */
class TornadoFx(private val origin: Vec3d, private val vel: Vec3d, private val radius: Double, life: Int) : AriusFx(life) {
    private fun at(t: Float): Vec3d = origin.add(vel.multiply(t.toDouble()))
    private val rng = java.util.Random(origin.hashCode().toLong())

    private class Debris(val state: net.minecraft.block.BlockState, val h0: Double, val a0: Double, val speed: Double, val size: Float)
    private val debris = List(14) { i ->
        Debris(if (i % 3 == 0) net.minecraft.block.Blocks.PACKED_ICE.defaultState else net.minecraft.block.Blocks.SNOW_BLOCK.defaultState,
            rng.nextDouble(), rng.nextDouble() * PI * 2, 0.08 + rng.nextDouble() * 0.06, 0.25f + rng.nextFloat() * 0.3f)
    }

    private fun funnelR(h: Double): Double = 0.6 + Math.pow(h / HEIGHT, 1.6) * radius * 1.9

    override fun tick(world: ClientWorld) {
        val r = random()
        val c = at(age.toFloat())
        repeat((16 * particlesAllowed()).toInt()) {
            val h = r.nextDouble() * HEIGHT
            val rr = funnelR(h)
            val a = r.nextDouble() * PI * 2
            val p = c.add(cos(a) * rr, h, sin(a) * rr)
            val v = Vec3d(-sin(a), 0.35, cos(a)).multiply(0.55)
            world.addParticleClient(if (h < 1.5) ParticleTypes.CLOUD else if (it % 3 == 0) ParticleTypes.WHITE_ASH else ParticleTypes.SNOWFLAKE, p.x, p.y, p.z, v.x, v.y, v.z)
        }
        repeat((4 * particlesAllowed()).toInt()) {
            val a = r.nextDouble() * PI * 2
            world.addParticleClient(ParticleTypes.CLOUD, c.x + cos(a) * radius, c.y + 0.1, c.z + sin(a) * radius, -sin(a) * 0.3, 0.02, cos(a) * 0.3)
        }
    }

    override fun render(d: VfxDraw, matrices: MatrixStack, t: Float) {
        val k = fadeInOut(t, 8f, 16f)
        val c = at(t)
        val burst = ((t - (life - 12)) / 12f).coerceIn(0f, 1f)
        // Wolkenschwaden, die spiralig aufsteigen
        val n = 70
        for (i in 0 until n) {
            val f = ((i / n.toDouble()) + t * 0.012) % 1.0
            val h = f * HEIGHT
            val rr = funnelR(h) * (1.0 + burst * 1.5)
            val a = i * 2.39996 + t * (0.42 - f * 0.15)
            val p = c.add(cos(a) * rr, h, sin(a) * rr)
            val size = 0.9 + f * 2.6
            d.billboard(VfxLayers.CLOUD_ALPHA, p, size, withAlpha(lerpColor(0xFFDDE8F2.toInt(), 0xFFFFFFFF.toInt(), f.toFloat()), 0.55f * k * (1f - burst)), (a * 40.0) % 360.0)
        }
        // Gestreifte Windbänder (Ringe, die gegeneinander laufen)
        for (i in 0 until 12) {
            val h = i * HEIGHT / 11.0
            val rr = funnelR(h)
            val spin = t * (0.5 + i * 0.03) * (if (i % 2 == 0) 1 else -1)
            d.ring(VfxLayers.FLOW_ADD, c.add(0.0, h, 0.0), X, Z, rr * 0.8, rr * 1.05, withAlpha(0xFFE8F6FF.toInt(), 0f), withAlpha(0xFFE8F6FF.toInt(), 0.4f * k), segments = 40, uTile = 3f, uOffset = spin.toFloat() * 0.1f)
            d.arc(VfxLayers.GLOW_ADD, c.add(0.0, h + 0.3, 0.0), X, Z, rr - 0.15, rr + 0.05, spin.toDouble(), (spin + 2.2).toDouble(), withAlpha(0xFFFFFFFF.toInt(), 0f), withAlpha(0xFFFFFFFF.toInt(), 0.6f * k), segments = 16)
        }
        // Eislicht-Blitze zucken im Trichter
        if ((t.toInt() / 3) % 4 == 0) {
            val path = ArrayList<Vec3d>()
            val seed = t.toInt() / 12
            for (j in 0..8) {
                val h = j * HEIGHT / 8.0
                val a = seed * 1.7 + j * 0.9
                path += c.add(cos(a) * funnelR(h) * 0.6, h, sin(a) * funnelR(h) * 0.6)
            }
            d.ribbon(VfxLayers.GLOW_ADD, path, { 0.12 }, { f -> withAlpha(0xFFBFE8FF.toInt(), 0.8f * k) }, uScale = 1f, uOffset = 0f)
        }
        // Mitgerissene Brocken
        for (b in debris) {
            val h = ((b.h0 + t * 0.012) % 1.0) * HEIGHT
            val rr = funnelR(h) * 0.85 * (1.0 + burst * 2.0)
            val a = b.a0 + t * b.speed * 3.0
            val pos = c.add(cos(a) * rr, h, sin(a) * rr)
            val cam = d.cam
            matrices.push()
            matrices.translate(pos.x - cam.x, pos.y - cam.y, pos.z - cam.z)
            matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(t * 21f))
            matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(t * 13f))
            matrices.scale(b.size, b.size, b.size)
            matrices.translate(-0.5, -0.5, -0.5)
            MinecraftClient.getInstance().blockRenderManager.renderBlockAsEntity(b.state, matrices, d.consumers, IceChunk.LIGHT, OverlayTexture.DEFAULT_UV)
            matrices.pop()
        }
        // Staubring am Boden
        d.ring(VfxLayers.FLOW_ADD, c.add(0.0, 0.1, 0.0), X, Z, radius * 0.6, radius * 1.6, withAlpha(0xFFFFFFFF.toInt(), 0.35f * k), withAlpha(0xFFFFFFFF.toInt(), 0f), segments = 48, uTile = 4f, uOffset = t * 0.08f)
    }

    private companion object {
        const val HEIGHT = 14.0
    }
}

/**
 * Eispanzer (seit 1.5.1, Crix: „funktioniert nicht richtig“ – vorher nur ein kurzer Eisklotz an den Füßen): Für die ganze
 * Dauer kreisen sechs Eisplatten um den Körper, Raureif glimmt, auf den Schultern sitzen Kristalle. Ist die Absorption
 * verbraucht, zerspringt der Panzer.
 */
class IceArmorFx(private val e: Entity, life: Int) : AriusFx(life) {
    private var broken = -1f

    override fun tick(world: ClientWorld) {
        val le = e as? net.minecraft.entity.LivingEntity ?: return
        if (broken < 0f && age > 3 && le.absorptionAmount <= 0.01f) {
            broken = age.toFloat()
            val p = e.entityPos.add(0.0, 1.0, 0.0)
            repeat(24) {
                val r = random()
                world.addParticleClient(net.minecraft.particle.BlockStateParticleEffect(ParticleTypes.BLOCK, net.minecraft.block.Blocks.PACKED_ICE.defaultState),
                    p.x + (r.nextDouble() - 0.5), p.y + (r.nextDouble() - 0.5) * 1.4, p.z + (r.nextDouble() - 0.5), (r.nextDouble() - 0.5) * 0.4, 0.2, (r.nextDouble() - 0.5) * 0.4)
            }
        }
        if (broken < 0f && age % 3 == 0 && random().nextFloat() < particlesAllowed()) {
            val p = e.entityPos
            world.addParticleClient(ParticleTypes.SNOWFLAKE, p.x + (random().nextDouble() - 0.5) * 1.2, p.y + random().nextDouble() * 1.8, p.z + (random().nextDouble() - 0.5) * 1.2, 0.0, -0.02, 0.0)
        }
    }

    override fun render(d: VfxDraw, matrices: MatrixStack, t: Float) {
        if (e.isRemoved) return
        val k = if (broken >= 0f) (1f - (t - broken) / 4f).coerceIn(0f, 1f) else fadeInOut(t, 4f, 10f)
        if (k <= 0.01f) return
        val base = e.getLerpedPos(d.tickDelta)
        val h = e.height.toDouble()
        val col = withAlpha(0xFFBFE8FF.toInt(), 0.55f * k)
        for (i in 0 until 6) {
            val a = t * 0.05 + i * PI / 3
            val y = base.y + h * (0.25 + 0.5 * ((i % 3) / 2.0)) + 0.05 * sin(t * 0.2 + i)
            val r = 0.62
            val c = Vec3d(base.x + cos(a) * r, y, base.z + sin(a) * r)
            val out = Vec3d(cos(a), 0.0, sin(a))
            val side = Vec3d(-sin(a), 0.0, cos(a))
            d.box(VfxLayers.SHARD_ALPHA, c, side.multiply(0.22), UP.multiply(0.3), out.multiply(0.04), col)
            d.billboard(VfxLayers.FLARE_ADD, c.add(out.multiply(0.06)).add(0.0, 0.25, 0.0), 0.18, withAlpha(0xFFFFFFFF.toInt(), 0.7f * k))
        }
        // Schulterkristalle und Raureif-Hof
        for (sg in intArrayOf(-1, 1)) {
            val yaw = Math.toRadians((e as? net.minecraft.entity.LivingEntity)?.bodyYaw?.toDouble() ?: 0.0)
            val right = Vec3d(-cos(yaw), 0.0, -sin(yaw)).multiply(sg * 0.42)
            val c = base.add(right).add(0.0, h * 0.82, 0.0)
            d.box(VfxLayers.SHARD_ALPHA, c, X.multiply(0.08), UP.multiply(0.22), Z.multiply(0.08), withAlpha(0xFFD6F2FF.toInt(), 0.75f * k))
        }
        d.billboard(VfxLayers.GLOW_ADD, base.add(0.0, h * 0.55, 0.0), h * 1.3, withAlpha(0xFFA8E2FF.toInt(), 0.22f * k))
    }
}

/**
 * Die Lichtklinge beim Schnitt (seit 1.5.1, nach Crix' Bild): Arius hält eine riesige Klinge aus weißem Licht hoch über
 * sich, sie wächst, um sie flimmern blaue Lichtfunken und der Himmel dahinter füllt sich mit Licht; im Schlag fährt sie
 * in einem Bogen nach vorn hinab und verglüht.
 */
class GiantBladeFx(private val arius: Entity, private val length: Double, private val strikeAt: Int, life: Int) : AriusFx(life) {
    override fun tick(world: ClientWorld) {
        if (age > strikeAt) return
        val r = random()
        val c = arius.entityPos.add(0.0, 2.0, 0.0)
        repeat((10 * particlesAllowed()).toInt()) {
            val a = r.nextDouble() * PI * 2
            val dist = 4.0 + r.nextDouble() * 10.0
            val p = c.add(cos(a) * dist, (r.nextDouble() - 0.3) * 8.0, sin(a) * dist)
            val v = c.subtract(p).multiply(0.04)
            world.addParticleClient(ParticleTypes.END_ROD, p.x, p.y, p.z, v.x, v.y, v.z)
        }
    }

    override fun render(d: VfxDraw, matrices: MatrixStack, t: Float) {
        if (arius.isRemoved) return
        val yaw = Math.toRadians((arius as? net.minecraft.entity.LivingEntity)?.bodyYaw?.toDouble() ?: 0.0)
        val fwd = Vec3d(-sin(yaw), 0.0, cos(yaw))
        val right = Vec3d(-cos(yaw), 0.0, -sin(yaw))
        val base = arius.getLerpedPos(d.tickDelta)
        val hilt = base.add(0.0, 2.3, 0.0).add(right.multiply(0.25))
        val grow = (t / (strikeAt * 0.6f)).coerceIn(0f, 1f)
        // Richtung: erhoben und leicht nach hinten geneigt; im Schlag in 5 Ticks nach vorn hinab.
        val s = ((t - strikeAt) / 5f).coerceIn(0f, 1f)
        val ang = Math.toRadians(-20.0 + s * 125.0)
        val dir = UP.multiply(cos(ang)).add(fwd.multiply(sin(ang))).normalize()
        val len = length * (0.15 + 0.85 * grow * grow)
        val fade = if (t > strikeAt + 5) (1f - (t - strikeAt - 5) / 10f).coerceIn(0f, 1f) else 1f
        val tip = hilt.add(dir.multiply(len))
        val across = right
        val pulse = 0.9f + 0.1f * sin(t * 1.3f)
        // Klingenkörper: breiter weißer Kern, blauer Saum, weicher Schein (nebelfrei für volle Sichtbarkeit im Sturm)
        d.band(AriusLayers.INK, hilt, tip, across, 1.3 + 0.65 * grow, withAlpha(0xFF124672.toInt(), 0.65f * fade), withAlpha(0xFF56C2F5.toInt(), 0.55f * fade))
        d.band(AriusLayers.VFX_GLOW, hilt, tip, across, 1.1 + 0.6 * grow, withAlpha(0xFFFFFFFF.toInt(), 0.95f * fade * pulse), withAlpha(0xFFE6F6FF.toInt(), 0.85f * fade))
        d.band(AriusLayers.VFX_GLOW, hilt, tip, across, 2.0 + 1.0 * grow, withAlpha(0xFF8FC8FF.toInt(), 0.52f * fade), withAlpha(0xFF6FB0FF.toInt(), 0.35f * fade))
        d.band(AriusLayers.VFX_FLOW, hilt, tip, across, 2.6 + 1.2 * grow, withAlpha(0xFFBFE6FF.toInt(), 0.38f * fade), withAlpha(0xFFBFE6FF.toInt(), 0.2f * fade), uScale = 0.2f, uOffset = -t * 0.1f)
        d.billboard(AriusLayers.VFX_FLARE, tip, 3.2 + 2.0 * grow, withAlpha(0xFFFFFFFF.toInt(), 0.9f * fade))
        d.billboard(AriusLayers.VFX_GLOW, hilt, 4.2, withAlpha(0xFFBFE6FF.toInt(), 0.65f * fade))
        // Der Himmel dahinter lädt sich auf: großer blauer Schein und Strahlen hinter ihm
        if (t < strikeAt + 8) {
            val sky = base.add(fwd.multiply(-20.0)).add(0.0, 18.0, 0.0)
            d.billboard(AriusLayers.VFX_GLOW, sky, 40.0 * grow, withAlpha(0xFF4F8FE0.toInt(), 0.38f * grow * fade))
            d.billboard(VfxLayers.RAYS_ADD, sky, 55.0 * grow, withAlpha(0xFFBFE6FF.toInt(), 0.3f * grow * fade), t * 0.4)
        }
        // Schweifbogen im Schlag
        if (s > 0f && fade > 0f) {
            d.arc(AriusLayers.INK, hilt, UP, fwd, len * 0.65, len, 0.0, Math.toRadians(125.0 * s), withAlpha(0xFF144B78.toInt(), 0f), withAlpha(0xFF70D0FF.toInt(), 0.72f * fade), segments = 24)
            d.arc(AriusLayers.VFX_GLOW, hilt, UP, fwd, len * 0.6, len, 0.0, Math.toRadians(125.0 * s), withAlpha(0xFFFFFFFF.toInt(), 0f), withAlpha(0xFFE6F6FF.toInt(), 0.85f * fade), segments = 24)
        }
    }
}

/**
 * „Der gefrorene Stern“ (Feat, seit 1.7.0): Hoch über dem Ziel entsteht ein gleißender Stern (gold-weiß, Strahlen),
 * gefriert von außen nach innen zu einem Eisstern mit Kristallzacken und stürzt dann herab.
 */
class FrozenStarFx(private val ground: Vec3d, private val radius: Double, private val impactAt: Int, life: Int) : AriusFx(life) {
    override fun render(d: VfxDraw, matrices: MatrixStack, t: Float) {
        if (t > impactAt + 2) return
        val form = (t / (impactAt * 0.35f)).coerceIn(0f, 1f)
        val freeze = ((t - impactAt * 0.35f) / (impactAt * 0.4f)).coerceIn(0f, 1f)
        val fall = ((t - impactAt * 0.75f) / (impactAt * 0.25f)).coerceIn(0f, 1f)
        val h = 34.0 * (1.0 - fall * fall)
        val c = ground.add(0.0, h + 1.0, 0.0)
        val r = radius * 0.55 * form
        val warm = 0xFFFFE8B0.toInt()
        val cold = 0xFFBFE8FF.toInt()
        val col = lerpColor(warm, cold, freeze)
        d.sphere(VfxLayers.SHARD_ALPHA, c, r, { up -> withAlpha(lerpColor(col, 0xFFFFFFFF.toInt(), (0.5f + 0.5f * up).coerceIn(0f, 1f)), form) }, lat = 12, lon = 20)
        d.billboard(VfxLayers.GLOW_ADD, c, r * 4.0, withAlpha(col, 0.5f * form))
        d.billboard(VfxLayers.RAYS_ADD, c, r * 7.0 * (1.0 - freeze * 0.6), withAlpha(warm, 0.5f * form * (1f - freeze)), t * 1.2)
        // Kristallzacken wachsen beim Gefrieren aus der Oberfläche
        if (freeze > 0f) for (i in 0 until 14) {
            val a = i * 2.39996
            val e = (i * 0.618 % 1.0) * 2 - 1
            val dir = Vec3d(cos(a) * kotlin.math.sqrt(1 - e * e), e, sin(a) * kotlin.math.sqrt(1 - e * e))
            d.band(VfxLayers.GLOW_ADD, c.add(dir.multiply(r * 0.9)), c.add(dir.multiply(r * (1.0 + 0.9 * freeze))), d.camUp, r * 0.18, withAlpha(0xFFFFFFFF.toInt(), 0.8f * freeze), withAlpha(cold, 0.2f * freeze))
        }
        // Fallspur
        if (fall > 0f) d.band(VfxLayers.FLOW_ADD, c, c.add(0.0, 18.0 * fall, 0.0), d.camRight, r * 0.8, withAlpha(cold, 0.6f), withAlpha(cold, 0f), uScale = 0.3f, uOffset = -t * 0.2f)
    }
}


/**
 * Staub- und Schneewolke: dicke Schneeschwaden quellen auf, wirbeln und teilen sich wieder. Bei [cloudOnly] = true
 * (Bergspaltung) entstehen nur weiche, dichte Wolken- und Nebelbänke ohne schwebende Quader-Splitter.
 */
class DebrisCloudFx(private val center: Vec3d, private val radius: Double, life: Int, private val cloudOnly: Boolean = false) : AriusFx(life) {
    private class Puff(val off: Vec3d, val drift: Vec3d, val rise: Double, val grow: Double, val spin: Double, val tint: Float)
    private class Bit(val vel: Vec3d, val size: Double, val spin: Double)

    private val rng = java.util.Random(center.hashCode().toLong())
    private val puffs = List(if (cloudOnly) 30 else 16) { i ->
        val a = rng.nextDouble() * PI * 2
        val r = rng.nextDouble() * radius * (if (cloudOnly) 0.72 else 0.55)
        val side = if (i % 2 == 0) 1.0 else -1.0
        val drift = if (cloudOnly) Vec3d(side * (0.4 + rng.nextDouble() * 0.7), 0.12 + rng.nextDouble() * 0.18, (rng.nextDouble() - 0.5) * 0.35) else Vec3d.ZERO
        Puff(
            Vec3d(cos(a) * r, (rng.nextDouble() - (if (cloudOnly) 0.2 else 0.0)) * radius * (if (cloudOnly) 0.55 else 0.3), sin(a) * r),
            drift, 0.04 + rng.nextDouble() * 0.08, 0.4 + rng.nextDouble() * 0.55, (rng.nextDouble() - 0.5) * 3.0, rng.nextFloat(),
        )
    }
    private val bits = if (cloudOnly) emptyList() else List(22) {
        val a = rng.nextDouble() * PI * 2
        val up = 0.25 + rng.nextDouble() * 0.5
        val out = 0.25 + rng.nextDouble() * 0.6
        Bit(Vec3d(cos(a) * out, up, sin(a) * out).multiply(radius / 10.0 + 0.4), 0.15 + rng.nextDouble() * radius * 0.04, rng.nextDouble() * 20.0)
    }

    override val persistent: Boolean get() = true

    override fun render(d: VfxDraw, matrices: MatrixStack, t: Float) {
        val k = fadeInOut(t, if (cloudOnly) 8f else 3f, life * (if (cloudOnly) 0.38f else 0.45f))
        val s = (t / life).coerceIn(0f, 1f).toDouble()
        val part = if (cloudOnly) ((s - 0.45) / 0.55).coerceIn(0.0, 1.0).let { it * it } else 0.0
        for ((idx, p) in puffs.withIndex()) {
            val pos = center
                .add(p.off.multiply(1.0 + s * (if (cloudOnly) 0.55 else 0.8)))
                .add(p.drift.multiply(part * radius * 1.15))
                .add(0.0, p.rise * t, 0.0)
            val size = radius * ((if (cloudOnly) 0.52 else 0.3) + p.grow * (1.0 - (1.0 - s) * (1.0 - s)))
            val col = lerpColor(0xFFD2DCE6.toInt(), 0xFFF7FAFD.toInt(), p.tint)
            val layer = if (cloudOnly && idx % 3 == 0) VfxLayers.SMOKE_ALPHA else VfxLayers.CLOUD_ALPHA
            val alpha = (if (cloudOnly) 0.78f else 0.62f) * k
            d.billboard(layer, pos, size, withAlpha(col, alpha), (p.spin * t) % 360.0)
        }
        for (b in bits) {
            if (t > life * 0.6f) continue
            val tt = t.toDouble()
            val pos = center.add(b.vel.x * tt, b.vel.y * tt - 0.03 * radius / 10.0 * tt * tt, b.vel.z * tt)
            val a = Math.toRadians(b.spin * tt)
            val ax = Vec3d(cos(a), 0.0, sin(a)).multiply(b.size)
            val ay = Vec3d(0.0, b.size * 1.6, 0.0)
            val az = Vec3d(-sin(a), 0.0, cos(a)).multiply(b.size * 0.6)
            d.box(VfxLayers.SHARD_ALPHA, pos, ax, ay, az, withAlpha(0xFFCFEFFF.toInt(), 0.85f * k))
        }
    }
}
