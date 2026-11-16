package dev.henny.hha_arius.client.fx

import dev.henny.hha.client.vfx.VfxDraw
import dev.henny.hha.client.vfx.VfxLayers
import dev.henny.hha.client.vfx.withAlpha
import dev.henny.hha_arius.gear.art.ArtCutS2C
import net.minecraft.block.Block
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.client.MinecraftClient
import net.minecraft.client.render.OverlayTexture
import net.minecraft.client.render.VertexConsumerProvider
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.client.world.ClientWorld
import net.minecraft.entity.Entity
import net.minecraft.particle.ParticleTypes
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.RotationAxis
import net.minecraft.util.math.Vec3d
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Farben der drei Schulen (Wind, Eis, Yang). */
object ArtColors {
    const val WIND = 0xFFD8E4F0.toInt()
    const val ICE = 0xFFA8E2FF.toInt()
    const val YANG = 0xFFFFE6A8.toInt()
    fun of(school: Int): Int = when (school) {
        0 -> WIND
        1 -> ICE
        else -> YANG
    }
}

/** Fliegende Zauber-Geschosse; der Server meldet das Ende über die ID. */
object ArtBolts {
    private val live = HashMap<Int, ArtBolt>()

    fun register(bolt: ArtBolt) {
        live[bolt.boltId] = bolt
        if (live.size > 64) live.entries.removeIf { it.value.age >= it.value.life }
    }

    /** Ende melden: Geschoss verschwindet, Aufprall-Effekt entsteht. */
    fun end(id: Int, at: Vec3d, school: Int, radius: Double): AriusFx {
        live.remove(id)?.kill()
        return ArtImpact(at, school, radius)
    }
}

class ArtBolt(val boltId: Int, private val start: Vec3d, private val vel: Vec3d, private val radius: Double, private val school: Int, life: Int) : AriusFx(life) {
    private var dead = false

    fun kill() {
        dead = true
    }

    private fun pos(t: Float): Vec3d = start.add(vel.multiply(t.toDouble()))

    override fun tick(world: ClientWorld) {
        if (dead) return
        val p = pos(age.toFloat())
        val r = random()
        if (r.nextFloat() < 0.6f * particlesAllowed()) {
            val particle = when (school) {
                0 -> ParticleTypes.CLOUD
                1 -> ParticleTypes.SNOWFLAKE
                else -> ParticleTypes.END_ROD
            }
            world.addParticleClient(particle, p.x, p.y, p.z, 0.0, 0.0, 0.0)
        }
    }

    override fun render(d: VfxDraw, matrices: MatrixStack, t: Float) {
        if (dead) return
        val p = pos(t)
        val fwd = vel.normalize()
        val k = min(1f, t / 2f)
        var side = fwd.crossProduct(UP)
        if (side.lengthSquared() < 1e-4) side = X
        side = side.normalize()
        val up = side.crossProduct(fwd).normalize()
        when (school) {
            0 -> {
                // Windklinge: drei ineinanderliegende Sicheln aus fliessendem Rauschen, die leicht taumeln.
                val wobble = sin(t * 0.9) * 0.25
                val axisB = side.multiply(cos(wobble)).add(up.multiply(sin(wobble)))
                for (i in 0..2) {
                    val back = 0.5 + i * 0.35
                    val r0 = 0.45 + i * 0.12
                    d.arc(VfxLayers.FLOW_ADD, p.subtract(fwd.multiply(back)), fwd, axisB, r0, r0 + 0.28, -1.2 + i * 0.1, 1.2 - i * 0.1,
                        withAlpha(ArtColors.WIND, 0f), withAlpha(0xFFFFFFFF.toInt(), (0.75f - i * 0.2f) * k), segments = 18, uTile = 2f, uOffset = t * 0.08f)
                }
                d.arc(VfxLayers.GLOW_ADD, p.subtract(fwd.multiply(0.5)), fwd, axisB, 0.66, 0.74, -1.1, 1.1, withAlpha(0xFFFFFFFF.toInt(), 0.9f * k), withAlpha(ArtColors.WIND, 0f), segments = 18)
                d.band(VfxLayers.FLOW_ADD, p.subtract(fwd.multiply(2.2)), p, axisB, 0.35, withAlpha(ArtColors.WIND, 0f), withAlpha(ArtColors.WIND, 0.35f * k), uScale = 0.4f, uOffset = t * 0.1f)
            }
            1 -> {
                // Eissplitter: langer Kristall entlang der Flugbahn, Reifspur dahinter.
                val cam = d.cam
                matrices.push()
                matrices.translate(p.x - cam.x, p.y - cam.y, p.z - cam.z)
                val yaw = Math.toDegrees(Math.atan2(fwd.x, fwd.z)).toFloat()
                val pitch = Math.toDegrees(Math.asin(-fwd.y.coerceIn(-1.0, 1.0))).toFloat()
                matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(yaw))
                matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(pitch))
                matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(t * 30f + 45f))
                matrices.scale(0.16f, 0.16f, 0.7f)
                matrices.translate(-0.5, -0.5, -0.5)
                MinecraftClient.getInstance().blockRenderManager.renderBlockAsEntity(Blocks.BLUE_ICE.defaultState, matrices, d.consumers, IceChunk.LIGHT, OverlayTexture.DEFAULT_UV)
                matrices.pop()
                d.billboard(VfxLayers.FLARE_ADD, p.add(fwd.multiply(0.3)), 0.55, withAlpha(ArtColors.ICE, 0.75f * k))
                d.band(VfxLayers.GLOW_ADD, p.subtract(fwd.multiply(2.4)), p, d.camUp, 0.09, withAlpha(ArtColors.ICE, 0f), withAlpha(0xFFFFFFFF.toInt(), 0.6f * k))
            }
            else -> {
                // Yang-Kugel: gleissender Kern, drehender Runenring, Strahlen, Lichtschweif.
                val r = radius * 0.7
                d.sphere(VfxLayers.GLOW_ADD, p, r, { withAlpha(0xFFFFFFFF.toInt(), k) })
                d.disc(VfxLayers.RING_ADD, p, d.camRight, d.camUp, r * 2.6, withAlpha(ArtColors.YANG, 0.8f * k), t * 0.12)
                d.billboard(VfxLayers.RAYS_ADD, p, r * 4.0, withAlpha(ArtColors.YANG, 0.45f * k), -t * 0.05)
                d.billboard(VfxLayers.FLARE_ADD, p, r * 5.0, withAlpha(ArtColors.YANG, 0.6f * k))
                d.band(VfxLayers.FLOW_ADD, p.subtract(fwd.multiply(2.6)), p, d.camUp, r * 0.8, withAlpha(ArtColors.YANG, 0f), withAlpha(ArtColors.YANG, 0.5f * k), uScale = 0.5f, uOffset = t * 0.12f)
            }
        }
    }
}

/** Aufprall je Schule: Windwirbel, splitterndes Eis (kleine Eisstuecke fliegen), aufbluehende Sonnenscheibe. */
class ArtImpact(private val at: Vec3d, private val school: Int, private val radius: Double) : AriusFx(if (school == 1) 16 else 12) {
    private val shards = if (school == 1) List(7) {
        Vec3d(random().nextDouble() - 0.5, random().nextDouble() * 0.6 + 0.1, random().nextDouble() - 0.5).normalize().multiply(0.18 + random().nextDouble() * 0.12)
    } else emptyList()

    override fun tick(world: ClientWorld) {
        if (age != 1) return
        val r = random()
        val particle = when (school) {
            0 -> ParticleTypes.CLOUD
            1 -> ParticleTypes.SNOWFLAKE
            else -> ParticleTypes.END_ROD
        }
        repeat((12 * particlesAllowed()).toInt()) {
            world.addParticleClient(particle, at.x, at.y, at.z, (r.nextDouble() - 0.5) * 0.35, r.nextDouble() * 0.2, (r.nextDouble() - 0.5) * 0.35)
        }
    }

    override fun render(d: VfxDraw, matrices: MatrixStack, t: Float) {
        val s = (t / life).coerceIn(0f, 1f)
        val k = 1f - s
        when (school) {
            0 -> d.ring(VfxLayers.FLOW_ADD, at, d.camRight, d.camUp, radius * (0.4 + 2.2 * s), radius * (0.9 + 2.6 * s), withAlpha(ArtColors.WIND, 0f), withAlpha(0xFFFFFFFF.toInt(), 0.5f * k), segments = 32, uTile = 3f, uOffset = t * 0.1f)
            1 -> {
                val cam = d.cam
                for (v in shards) {
                    val p = at.add(v.multiply(t.toDouble())).add(0.0, -0.012 * t * t, 0.0)
                    matrices.push()
                    matrices.translate(p.x - cam.x, p.y - cam.y, p.z - cam.z)
                    matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(t * 40f + v.x.toFloat() * 300f))
                    matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(t * 33f))
                    val sc = 0.14f * (1f - s * 0.6f)
                    matrices.scale(sc, sc, sc)
                    matrices.translate(-0.5, -0.5, -0.5)
                    MinecraftClient.getInstance().blockRenderManager.renderBlockAsEntity(Blocks.PACKED_ICE.defaultState, matrices, d.consumers, IceChunk.LIGHT, OverlayTexture.DEFAULT_UV)
                    matrices.pop()
                }
                d.billboard(VfxLayers.FLARE_ADD, at, radius * 3.0 * (1.0 + s), withAlpha(ArtColors.ICE, 0.7f * k))
            }
            else -> {
                d.disc(VfxLayers.RUNE_ADD, at, d.camRight, d.camUp, radius * (1.0 + 3.0 * s), withAlpha(ArtColors.YANG, 0.8f * k), t * 0.05)
                d.billboard(VfxLayers.RAYS_ADD, at, radius * (2.0 + 5.0 * s), withAlpha(0xFFFFFFFF.toInt(), 0.6f * k))
                d.billboard(VfxLayers.FLARE_ADD, at, radius * 4.0, withAlpha(ArtColors.YANG, 0.8f * k * k))
            }
        }
    }
}

/** Sturmstoss: mehrere Boegen aus fliessendem Wind laufen kegelfoermig nach vorn auseinander. */
class GaleFx(private val origin: Vec3d, private val reach: Vec3d, private val charge: Float, life: Int) : AriusFx(life) {
    override fun tick(world: ClientWorld) {
        if (age > 3) return
        val r = random()
        val fwd = reach.normalize()
        repeat((10 * particlesAllowed()).toInt()) {
            val v = fwd.add((r.nextDouble() - 0.5) * 0.8, (r.nextDouble() - 0.5) * 0.4, (r.nextDouble() - 0.5) * 0.8).normalize().multiply(0.6 + 0.6 * charge)
            world.addParticleClient(ParticleTypes.CLOUD, origin.x, origin.y, origin.z, v.x, v.y, v.z)
        }
    }

    override fun render(d: VfxDraw, matrices: MatrixStack, t: Float) {
        val fwd = reach.normalize()
        var side = fwd.crossProduct(UP)
        if (side.lengthSquared() < 1e-4) side = X
        side = side.normalize()
        val len = reach.length()
        for (i in 0..2) {
            val s = ((t - i * 1.5f) / (life - 2f)).coerceIn(0f, 1f)
            if (s <= 0f) continue
            val dist = len * s
            val r = 0.6 + dist * 0.85
            val k = (1f - s) * (0.55f + 0.35f * charge)
            d.arc(VfxLayers.FLOW_ADD, origin.add(fwd.multiply(dist * 0.35)), fwd, side, r * 0.7, r, -0.8, 0.8,
                withAlpha(ArtColors.WIND, 0f), withAlpha(0xFFFFFFFF.toInt(), k), segments = 22, uTile = 3f, uOffset = t * 0.15f + i)
        }
    }
}

/** Frostfeld: Ring am Boden, feiner Reif, fallender Schnee. */
class FieldFx(private val center: Vec3d, private val radius: Double, life: Int) : AriusFx(life) {
    override fun tick(world: ClientWorld) {
        val r = random()
        repeat((3 * particlesAllowed()).toInt().coerceAtLeast(1)) {
            val a = r.nextDouble() * PI * 2
            val dist = r.nextDouble() * radius
            world.addParticleClient(ParticleTypes.SNOWFLAKE, center.x + cos(a) * dist, center.y + 0.1 + r.nextDouble() * 1.5, center.z + sin(a) * dist, 0.0, -0.02, 0.0)
        }
    }

    override fun render(d: VfxDraw, matrices: MatrixStack, t: Float) {
        val k = fadeInOut(t, 4f, 12f)
        val c = center.add(0.0, 0.06, 0.0)
        d.disc(VfxLayers.GLOW_ADD, c, X, Z, radius, withAlpha(ArtColors.ICE, 0.18f * k))
        val pulse = 0.85 + 0.15 * sin(t * 0.2)
        d.ring(VfxLayers.GLOW_ADD, c, X, Z, radius - 0.35, radius * pulse.coerceAtMost(1.0) + 0.05, withAlpha(ArtColors.ICE, 0f), withAlpha(0xFFFFFFFF.toInt(), 0.65f * k), segments = 64)
    }
}

/**
 * Klarer Schnitt: dünnes, helles Lichtblatt in der Schnittebene, das schnell verblasst, und eine Mittellinie, die
 * etwas länger nachglimmt. Keine Explosion, keine Kugel – nur die Ebene.
 */
class CutPlaneFx(private val origin: Vec3d, private val vec: Vec3d, private val half: Double, private val horizontal: Boolean, life: Int) : AriusFx(life) {
    private val fwd = vec.normalize()
    private val span: Vec3d

    init {
        var right = fwd.crossProduct(UP)
        if (right.lengthSquared() < 1e-4) right = X
        right = right.normalize()
        val up = right.crossProduct(fwd).normalize()
        span = if (horizontal) right else up
    }

    override fun render(d: VfxDraw, matrices: MatrixStack, t: Float) {
        val end = origin.add(vec)
        val sheet = (1f - t / 7f).coerceIn(0f, 1f)
        if (sheet > 0f) d.band(VfxLayers.GLOW_ADD, origin, end, span, half, withAlpha(0xFFFFFFFF.toInt(), 0.55f * sheet), withAlpha(ArtColors.ICE, 0.25f * sheet))
        val line = fadeInOut(t, 1f, 10f)
        d.band(VfxLayers.GLOW_ADD, origin, end, span, 0.07, withAlpha(0xFFFFFFFF.toInt(), line), withAlpha(0xFFFFFFFF.toInt(), 0.6f * line))
        d.band(VfxLayers.GLOW_ADD, origin.add(span.multiply(half)), end.add(span.multiply(half)), fwd.crossProduct(span).normalize(), 0.05, withAlpha(ArtColors.YANG, 0.4f * line), withAlpha(ArtColors.YANG, 0f))
        d.band(VfxLayers.GLOW_ADD, origin.subtract(span.multiply(half)), end.subtract(span.multiply(half)), fwd.crossProduct(span).normalize(), 0.05, withAlpha(ArtColors.YANG, 0.4f * line), withAlpha(ArtColors.YANG, 0f))
    }
}

/** Windsog: Luft strömt spiralförmig zum Spieler. */
class PullFx(private val e: Entity, private val radius: Double, life: Int) : AriusFx(life) {
    override fun tick(world: ClientWorld) {
        val r = random()
        val c = e.entityPos.add(0.0, 0.6, 0.0)
        repeat((6 * particlesAllowed()).toInt().coerceAtLeast(1)) {
            val a = r.nextDouble() * PI * 2
            val dist = radius * (0.5 + 0.5 * r.nextDouble())
            val p = c.add(cos(a) * dist, r.nextDouble() * 1.2 - 0.3, sin(a) * dist)
            val v = c.subtract(p).multiply(0.12).add(-sin(a) * 0.15, 0.0, cos(a) * 0.15)
            world.addParticleClient(ParticleTypes.CLOUD, p.x, p.y, p.z, v.x, v.y, v.z)
        }
    }

    override fun render(d: VfxDraw, matrices: MatrixStack, t: Float) {}
}

/**
 * Klarer Schnitt an Blöcken: Jeder tatsächlich entfernte Block erscheint kurz als zwei Hälften, die entlang der
 * Schnitt-Normalen auseinandergleiten, leicht absinken und schrumpfend verschwinden. Nur Darstellung (die Welt enthält
 * bereits Luft).
 */
object CutHalves {
    private class Piece(val pos: BlockPos, val state: BlockState, val axis: Int) {
        var age = 0

        /** Absturz (axis 3): Verzögerung, Taumel-Richtung. */
        val delay = if (axis == 3) (Math.floorMod(pos.asLong() * 2654435761L, 9L)).toInt() else 0
        val spin = if (axis == 3) ((Math.floorMod(pos.asLong(), 360L)).toFloat()) else 0f
    }

    private const val LIFE = 16
    private const val FALL_LIFE = 46
    private val pieces = ArrayList<Piece>()

    fun handle(p: ArtCutS2C) {
        for (i in p.positions.indices) pieces.add(Piece(BlockPos.fromLong(p.positions[i]), Block.getStateFromRawId(p.states[i]), p.axis))
        while (pieces.size > 1400) pieces.removeAt(0)
    }

    fun tick() {
        pieces.removeIf { ++it.age > (if (it.axis == 3) FALL_LIFE else LIFE) }
    }

    fun clear() = pieces.clear()

    fun render(matrices: MatrixStack, consumers: VertexConsumerProvider, cam: Vec3d, tickDelta: Float) {
        if (pieces.isEmpty()) return
        val brm = MinecraftClient.getInstance().blockRenderManager
        for (p in pieces) {
            if (p.axis == 3) {
                // Absturz: erst ein kurzes Zittern, dann fallen, taumeln, schrumpfen.
                val ft = (p.age + tickDelta - p.delay).coerceAtLeast(0f)
                val shake = if (ft < 4f) (Math.sin((p.age + tickDelta) * 3.0 + p.spin) * 0.06) else 0.0
                val drop = if (ft < 4f) 0.0 else 0.035 * (ft - 4f) * (ft - 4f)
                val shrink = (1f - ((ft - 30f) / 12f).coerceIn(0f, 1f))
                if (shrink <= 0f) continue
                matrices.push()
                matrices.translate(p.pos.x + 0.5 - cam.x + shake, p.pos.y + 0.5 - cam.y - drop, p.pos.z + 0.5 - cam.z)
                val rot = (ft - 4f).coerceAtLeast(0f) * 7f
                matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(rot * kotlin.math.cos(p.spin.toDouble()).toFloat()))
                matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(rot * kotlin.math.sin(p.spin.toDouble()).toFloat()))
                matrices.scale(shrink, shrink, shrink)
                matrices.translate(-0.5, -0.5, -0.5)
                brm.renderBlockAsEntity(p.state, matrices, consumers, LIGHT, OverlayTexture.DEFAULT_UV)
                matrices.pop()
                continue
            }
            val t = ((p.age + tickDelta) / LIFE).coerceIn(0f, 1f)
            val ease = 1f - (1f - t) * (1f - t)
            val shrink = if (t > 0.7f) 1f - (t - 0.7f) / 0.3f else 1f
            for (side in intArrayOf(-1, 1)) {
                matrices.push()
                val off = side * (0.25 + 0.45 * ease)
                val ox = if (p.axis == 0) off else 0.0
                val oy = if (p.axis == 1) off else -0.35 * ease * ease
                val oz = if (p.axis == 2) off else 0.0
                matrices.translate(p.pos.x + 0.5 - cam.x + ox, p.pos.y + 0.5 - cam.y + oy, p.pos.z + 0.5 - cam.z + oz)
                val tilt = 14f * ease * side
                when (p.axis) {
                    0 -> matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(tilt))
                    2 -> matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-tilt))
                }
                val sx = (if (p.axis == 0) 0.5f else 1f) * shrink
                val sy = (if (p.axis == 1) 0.5f else 1f) * shrink
                val sz = (if (p.axis == 2) 0.5f else 1f) * shrink
                matrices.scale(sx, sy, sz)
                matrices.translate(-0.5, -0.5, -0.5)
                brm.renderBlockAsEntity(p.state, matrices, consumers, LIGHT, OverlayTexture.DEFAULT_UV)
                matrices.pop()
            }
        }
    }

    private const val LIGHT = (12 shl 4) or (15 shl 20)
}
