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
        when (school) {
            0 -> {
                // Windklinge: flache Sichel quer zur Flugbahn.
                var side = fwd.crossProduct(UP)
                if (side.lengthSquared() < 1e-4) side = X
                side = side.normalize()
                d.arc(VfxLayers.GLOW_ADD, p.subtract(fwd.multiply(0.55)), fwd, side, 0.42, 0.7, -1.15, 1.15, withAlpha(ArtColors.WIND, 0f), withAlpha(0xFFFFFFFF.toInt(), 0.85f * k), segments = 16)
                d.band(VfxLayers.GLOW_ADD, p.subtract(fwd.multiply(1.6)), p, side, 0.18, withAlpha(ArtColors.WIND, 0f), withAlpha(ArtColors.WIND, 0.4f * k))
            }
            1 -> {
                val cam = d.cam
                matrices.push()
                matrices.translate(p.x - cam.x, p.y - cam.y, p.z - cam.z)
                matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(t * 25f))
                matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(t * 31f))
                matrices.scale(0.22f, 0.42f, 0.22f)
                matrices.translate(-0.5, -0.5, -0.5)
                MinecraftClient.getInstance().blockRenderManager.renderBlockAsEntity(Blocks.BLUE_ICE.defaultState, matrices, d.consumers, IceChunk.LIGHT, OverlayTexture.DEFAULT_UV)
                matrices.pop()
                d.billboard(VfxLayers.FLARE_ADD, p, 0.5, withAlpha(ArtColors.ICE, 0.7f * k))
            }
            else -> {
                d.sphere(VfxLayers.GLOW_ADD, p, radius * 0.7, { withAlpha(0xFFFFFFFF.toInt(), k) })
                d.billboard(VfxLayers.FLARE_ADD, p, radius * 3.2, withAlpha(ArtColors.YANG, 0.75f * k))
                d.band(VfxLayers.GLOW_ADD, p.subtract(fwd.multiply(1.4)), p, d.camUp, radius * 0.5, withAlpha(ArtColors.YANG, 0f), withAlpha(ArtColors.YANG, 0.35f * k))
            }
        }
    }
}

/** Kleiner Aufprall: Funken/Splitter je Schule. */
class ArtImpact(private val at: Vec3d, private val school: Int, private val radius: Double) : AriusFx(8) {
    override fun tick(world: ClientWorld) {
        if (age != 1) return
        val r = random()
        val particle = when (school) {
            0 -> ParticleTypes.CLOUD
            1 -> ParticleTypes.SNOWFLAKE
            else -> ParticleTypes.END_ROD
        }
        repeat((10 * particlesAllowed()).toInt()) {
            world.addParticleClient(particle, at.x, at.y, at.z, (r.nextDouble() - 0.5) * 0.3, r.nextDouble() * 0.2, (r.nextDouble() - 0.5) * 0.3)
        }
    }

    override fun render(d: VfxDraw, matrices: MatrixStack, t: Float) {
        val k = (1f - t / life).coerceIn(0f, 1f)
        d.billboard(VfxLayers.FLARE_ADD, at, radius * 2.5 * (1.0 + t * 0.15), withAlpha(ArtColors.of(school), 0.7f * k))
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
    }

    private const val LIFE = 16
    private val pieces = ArrayList<Piece>()

    fun handle(p: ArtCutS2C) {
        for (i in p.positions.indices) pieces.add(Piece(BlockPos.fromLong(p.positions[i]), Block.getStateFromRawId(p.states[i]), p.axis))
        while (pieces.size > 256) pieces.removeAt(0)
    }

    fun tick() {
        pieces.removeIf { ++it.age > LIFE }
    }

    fun clear() = pieces.clear()

    fun render(matrices: MatrixStack, consumers: VertexConsumerProvider, cam: Vec3d, tickDelta: Float) {
        if (pieces.isEmpty()) return
        val brm = MinecraftClient.getInstance().blockRenderManager
        for (p in pieces) {
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
