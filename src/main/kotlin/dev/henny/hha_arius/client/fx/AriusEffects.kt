package dev.henny.hha_arius.client.fx

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
                FxKind.CUT_PLANE -> CutPlaneFx(pos, dir, p.a.toDouble(), p.b >= 0.5f, p.life)
                FxKind.PULL -> e1?.let { PullFx(it, p.a.toDouble(), p.life) }
                FxKind.GALE -> GaleFx(pos, dir, p.a, p.life)
                FxKind.WHITE_HOLE -> if (p.b >= 0f) WhiteHoleFx(pos, p.a.toDouble(), p.b.toInt(), p.life) else {
                    AriusFxClient.collapseWhiteHole(pos)
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
        d.band(VfxLayers.GLOW_ADD, from, to, UP, 0.045, withAlpha(WHITE, k), withAlpha(ICE, k))
        if (height > 0.0) {
            val mid = Vec3d(0.0, height / 2, 0.0)
            d.band(VfxLayers.GLOW_ADD, from.add(mid), to.add(mid), UP, height / 2, withAlpha(ICE, k * 0.12f), withAlpha(ICE, k * 0.06f))
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
        val glow = withAlpha(WHITE, 0.3f + 0.7f * k)
        d.tube(VfxLayers.GLOW_ADD, listOf(base, tip), { 0.04 + 0.06 * k }, { glow })
        d.billboard(VfxLayers.FLARE_ADD, tip.lerp(base, 0.3), 0.4 + 1.2 * k, withAlpha(ICE, 0.8f * k))
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
        val vc = d.buffer(VfxLayers.GLOW_ADD)
        for (i in 0 until segments) {
            val a0 = i * 2 * PI / segments
            val a1 = (i + 1) * 2 * PI / segments
            val mid = (a0 + a1) / 2
            if (inGap(mid, r)) continue
            val p0 = Vec3d(center.x + sin(a0) * r, center.y, center.z + cos(a0) * r)
            val p1 = Vec3d(center.x + sin(a1) * r, center.y, center.z + cos(a1) * r)
            val h = 2.6
            val top = withAlpha(ICE, 0f)
            val bottom = withAlpha(WHITE, 0.75f * k)
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

/** Goldener Resonanzfaden zwischen Arius' Brust und Nivaris' Herzkern. */
class Thread(private val a: Entity, private val b: Entity, life: Int) : AriusFx(life) {
    override fun render(d: VfxDraw, matrices: MatrixStack, t: Float) {
        val k = fadeInOut(t, 5f, 8f)
        val from = a.getLerpedPos(d.tickDelta).add(0.0, 1.35, 0.0)
        val yaw = Math.toRadians(b.yaw.toDouble())
        val fwd = Vec3d(-sin(yaw), 0.0, cos(yaw))
        val to = b.getLerpedPos(d.tickDelta).add(fwd.multiply(2.6)).add(0.0, 2.3, 0.0)
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
        d.ring(VfxLayers.GLOW_ADD, center.add(0.0, 0.08, 0.0), X, Z, max(0.0, r - 1.2), r, withAlpha(WHITE, 0f), withAlpha(ICE, 0.7f * k), segments = 64)
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

class TornadoFx(private val origin: Vec3d, private val vel: Vec3d, private val radius: Double, life: Int) : AriusFx(life) {
    private fun at(t: Float): Vec3d = origin.add(vel.multiply(t.toDouble()))

    override fun tick(world: ClientWorld) {
        val r = random()
        val c = at(age.toFloat())
        repeat((8 * particlesAllowed()).toInt()) {
            val h = r.nextDouble() * 6.0
            val rr = 0.4 + h / 6.0 * radius
            val a = r.nextDouble() * PI * 2
            val p = c.add(cos(a) * rr, h, sin(a) * rr)
            val v = Vec3d(-sin(a), 0.25, cos(a)).multiply(0.35)
            world.addParticleClient(if (h < 1.0) ParticleTypes.CLOUD else ParticleTypes.SNOWFLAKE, p.x, p.y, p.z, v.x, v.y, v.z)
        }
    }

    override fun render(d: VfxDraw, matrices: MatrixStack, t: Float) {
        val k = fadeInOut(t, 6f, 14f)
        val c = at(t)
        for (i in 0 until 9) {
            val h = i * 0.7
            val rr = 0.5 + i / 8.0 * radius
            val spin = t * (0.35 + i * 0.03) * (if (i % 2 == 0) 1 else -1)
            d.ring(VfxLayers.FLOW_ADD, c.add(0.0, h, 0.0), X, Z, rr * 0.75, rr, withAlpha(0xFFE8F6FF.toInt(), 0f), withAlpha(0xFFE8F6FF.toInt(), 0.35f * k), segments = 40)
            d.arc(VfxLayers.GLOW_ADD, c.add(0.0, h + 0.2, 0.0), X, Z, rr - 0.12, rr, spin.toDouble(), (spin + 2.4).toDouble(), withAlpha(0xFFFFFFFF.toInt(), 0f), withAlpha(0xFFFFFFFF.toInt(), 0.5f * k), segments = 16)
        }
    }
}
