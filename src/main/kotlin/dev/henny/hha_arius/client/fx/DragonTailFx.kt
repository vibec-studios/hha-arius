package dev.henny.hha_arius.client.fx

import dev.henny.hha.client.vfx.VfxDraw
import dev.henny.hha.client.vfx.VfxLayers
import dev.henny.hha.client.vfx.withAlpha
import dev.henny.hha_arius.boss.NivarisShape
import net.minecraft.client.render.LightmapTextureManager
import net.minecraft.client.render.OverlayTexture
import net.minecraft.client.render.RenderLayers
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.client.world.ClientWorld
import net.minecraft.entity.Entity
import net.minecraft.particle.ParticleTypes
import net.minecraft.util.math.Vec3d
import org.joml.Matrix3f
import org.joml.Quaternionf
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Drachenschweif der Beinschienen (seit 1.4.2, ersetzt den Schwingensprung): Aus dem Rücken des Wirkers wächst
 * Solvanes Schwanz aus Geisterlicht und peitscht einmal ganz um ihn herum (Spitze voraus, der Körper zieht in einer
 * Spirale nach), auf dem Boden folgt ein Frostbogen. Danach zerfällt der Schwanz zu Licht.
 *
 * [e1] = Wirker (der Schweif folgt ihm), [start] = Startwinkel (atan2(x, z) der Blickrichtung), [radius] = Reichweite.
 */
class DragonTailFx(private val caster: Entity, private val start: Double, private val radius: Double, life: Int) : AriusFx(life) {

    private fun sweep(t: Float): Double {
        val k = (t / SWEEP).coerceIn(0f, 1f).toDouble()
        return start + 2 * PI * (1 - (1 - k).pow(3.0))
    }

    override fun tick(world: ClientWorld) {
        if (age > SWEEP + 1) return
        val c = caster.entityPos
        val a = sweep(age.toFloat())
        val tip = c.add(sin(a) * radius, 0.6, cos(a) * radius)
        repeat((4 * particlesAllowed()).toInt()) {
            world.addParticleClient(ParticleTypes.SNOWFLAKE, tip.x, tip.y, tip.z, cos(a) * 0.2, 0.05, -sin(a) * 0.2)
        }
        world.addParticleClient(ParticleTypes.CLOUD, tip.x, c.y + 0.1, tip.z, 0.0, 0.02, 0.0)
    }

    override fun render(d: VfxDraw, matrices: MatrixStack, t: Float) {
        if (caster.isRemoved) return
        val td = d.tickDelta
        val c = caster.getLerpedPos(td)
        val grow = (t / 3f).coerceIn(0f, 1f)
        val fade = if (t <= SWEEP) 1f else (1f - (t - SWEEP) / (life - SWEEP)).coerceIn(0f, 1f)
        val lead = sweep(t)
        // Punkte des Schweifs: Wurzel am Rücken (Hüfthöhe), Spitze voraus auf dem Radius; Spirale dazwischen.
        val n = 17
        val pts = Array(n) { k ->
            val s = k / (n - 1.0)
            val ang = lead - (1 - s) * LAG
            val r = 0.5 + (radius - 0.5) * s.pow(0.75) * grow
            val y = 1.05 - 0.55 * s + 0.18 * sin(t * 0.6 + s * 6.0) * s
            c.add(sin(ang) * r, y, cos(ang) * r)
        }
        renderTail(d, matrices, pts, fade * grow)
        // Frostbogen am Boden: vom Start bis zur Spitze, hinten ausgeblendet.
        val sweepK = ((t / SWEEP).coerceIn(0f, 1f))
        if (sweepK > 0f) {
            // Basis (Z, X): Punkt = Z·cos φ + X·sin φ, also x = sin φ, z = cos φ wie der Schweif.
            d.arc(VfxLayers.GLOW_ADD, c.add(0.0, 0.08, 0.0), Z, X, radius * 0.75, radius * 1.05, start, lead,
                withAlpha(0xFFB8E8FF.toInt(), 0.05f * fade), withAlpha(0xFFFFFFFF.toInt(), 0.6f * fade), segments = 48)
            d.ring(VfxLayers.FLOW_ADD, c.add(0.0, 0.12, 0.0), X, Z, radius * sweepK * 0.6, radius * sweepK + 0.6,
                withAlpha(0xFFDDF2FF.toInt(), 0f), withAlpha(0xFFDDF2FF.toInt(), 0.4f * fade), segments = 48)
        }
        val tip = pts.last()
        d.billboard(VfxLayers.FLARE_ADD, tip, 1.1, withAlpha(0xFFFFFFFF.toInt(), 0.8f * fade))
        d.billboard(VfxLayers.GLOW_ADD, tip, 2.4, withAlpha(0xFF9FD8FF.toInt(), 0.35f * fade))
    }

    private fun renderTail(d: VfxDraw, matrices: MatrixStack, pts: Array<Vec3d>, alpha: Float) {
        if (alpha <= 0.02f) return
        val model = DragonBreathFx.model() ?: return
        val buf = d.consumers.getBuffer(RenderLayers.eyes(DragonBreathFx.TEXTURE))
        val col = withAlpha(0xFFCDEBFF.toInt(), 0.7f * alpha)
        val cam = d.cam
        val first = NivarisShape.SEGMENTS - (pts.size - 1)
        for (k in 0 until pts.size - 1) {
            val a = pts[k]
            val b = pts[k + 1]
            val dir = b.subtract(a)
            val len = dir.length()
            if (len < 1e-4) continue
            val bz = dir.multiply(1.0 / len)
            val i = first + k
            matrices.push()
            matrices.translate(a.x - cam.x, a.y - cam.y, a.z - cam.z)
            orient(matrices, bz)
            val g = NivarisShape.SCALE * TK * NivarisShape.taper(i)
            matrices.scale(g, g, NivarisShape.SCALE * (len / NivarisShape.SEGMENT).toFloat() * 1.04f)
            model.glied(i).render(matrices, buf, LightmapTextureManager.MAX_LIGHT_COORDINATE, OverlayTexture.DEFAULT_UV, col)
            matrices.pop()
        }
        // Schwanzfächer an der Spitze.
        val tip = pts.last()
        val bz = tip.subtract(pts[pts.size - 2]).normalize()
        matrices.push()
        matrices.translate(tip.x - cam.x, tip.y - cam.y, tip.z - cam.z)
        orient(matrices, bz)
        val k = NivarisShape.SCALE * TK
        matrices.scale(k, k, k)
        model.tailFan.render(matrices, buf, LightmapTextureManager.MAX_LIGHT_COORDINATE, OverlayTexture.DEFAULT_UV, col)
        matrices.pop()
    }

    /** Spalten wie im Drachen-Renderer: Modell-x = links, Modell-y = −oben, Modell-z = nach hinten ([b]). */
    private fun orient(matrices: MatrixStack, b: Vec3d) {
        var u = UP.subtract(b.multiply(UP.dotProduct(b)))
        u = if (u.lengthSquared() < 1e-6) X else u.normalize()
        val l = b.crossProduct(u)
        m3.set(l.x.toFloat(), l.y.toFloat(), l.z.toFloat(), (-u.x).toFloat(), (-u.y).toFloat(), (-u.z).toFloat(), b.x.toFloat(), b.y.toFloat(), b.z.toFloat())
        matrices.multiply(rot.setFromNormalized(m3))
    }

    companion object {
        const val SWEEP = 12f
        private const val LAG = 2.4
        private const val TK = 0.55f
        private val m3 = Matrix3f()
        private val rot = Quaternionf()
    }
}
