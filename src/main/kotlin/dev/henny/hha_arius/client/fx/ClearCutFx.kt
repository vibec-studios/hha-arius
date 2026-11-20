package dev.henny.hha_arius.client.fx

import dev.henny.hha.client.vfx.VfxDraw
import dev.henny.hha.client.vfx.VfxLayers
import dev.henny.hha.client.vfx.withAlpha
import dev.henny.hha_arius.HhaArius
import dev.henny.hha_arius.boss.NivarisShape
import dev.henny.hha_arius.gear.art.ArtTimings
import net.minecraft.client.render.LightmapTextureManager
import net.minecraft.client.render.OverlayTexture
import net.minecraft.client.render.RenderLayers
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.client.world.ClientWorld
import net.minecraft.particle.ParticleTypes
import net.minecraft.util.math.Vec3d
import org.joml.Matrix3f
import org.joml.Quaternionf
import kotlin.math.cos
import kotlin.math.sin

/**
 * Solvanes Erwachen (seit 1.8.1, Crix: „die aktive Fähigkeit von Solvane ist zu uncool“): Der Klare Schnitt ist jetzt
 * ein Drachenschnitt in zwei Takten.
 * 1. **Der Drache:** Aus der Lichtklinge bricht Solvanes Geist hervor (echtes Kopfmodell mit acht Gliedern, Geisterlicht,
 *    Maul offen) und jagt in einer Schlangenlinie die Schnittebene entlang weit über ihr Ende hinaus; hinter ihm
 *    bleibt eine haarfeine weiße Naht in der Luft stehen.
 * 2. **Der Riss:** Einen Augenblick lang passiert nichts. Dann reißt die Naht auf ([SPLIT] Ticks nach dem Schnitt): ein
 *    Lichtblatt klappt nach beiden Seiten auf, Eissplitter schießen aus der Ebene, ein Frostring läuft am Ende aus. Der
 *    Server trifft im selben Moment ein zweites Mal ([dev.henny.hha_arius.gear.art.SolvaneArt]).
 * Gezeichnet aus [CutPlaneFx]; [origin], [fwd], [span] und [len] beschreiben die Ebene.
 */
class ClearCutFx(private val origin: Vec3d, private val fwd: Vec3d, private val span: Vec3d, private val len: Double, private val half: Double) {

    private val normal = fwd.crossProduct(span).normalize()
    private val shards = List(26) {
        val r = AriusFx.random()
        Triple(r.nextDouble(), r.nextDouble() * 2 - 1, (if (r.nextBoolean()) 1.0 else -1.0) * (0.5 + r.nextDouble()))
    }
    private var burst = false

    /** Schlangenlinie des Geists in der Ebene bei Bogenlänge [s]. */
    private fun path(s: Double): Vec3d = origin.add(fwd.multiply(s)).add(span.multiply(sin(s * 0.42) * 0.9)).add(normal.multiply(sin(s * 0.23 + 1.0) * 0.5))

    fun tick(world: ClientWorld, age: Int) {
        val fx = particlesAllowed()
        if (age <= RUN && fx > 0f) {
            val s = runS(age.toFloat())
            val p = path(s)
            repeat((4 * fx).toInt().coerceAtLeast(1)) {
                val r = AriusFx.random()
                world.addParticleClient(if (it % 2 == 0) ParticleTypes.END_ROD else ParticleTypes.SNOWFLAKE,
                    p.x + (r.nextDouble() - 0.5) * 0.8, p.y + (r.nextDouble() - 0.5) * 0.8, p.z + (r.nextDouble() - 0.5) * 0.8, 0.0, 0.02, 0.0)
            }
        }
        if (!burst && age >= SPLIT) {
            burst = true
            val n = (40 * fx).toInt()
            val r = AriusFx.random()
            repeat(n) {
                val p = origin.add(fwd.multiply(r.nextDouble() * len)).add(span.multiply((r.nextDouble() * 2 - 1) * half))
                val v = normal.multiply((if (r.nextBoolean()) 1.0 else -1.0) * (0.15 + r.nextDouble() * 0.3)).add(0.0, 0.05, 0.0)
                world.addParticleClient(if (it % 3 == 0) ParticleTypes.END_ROD else ParticleTypes.SNOWFLAKE, p.x, p.y, p.z, v.x, v.y, v.z)
            }
        }
    }

    private fun runS(t: Float): Double {
        val u = (t / RUN).coerceIn(0f, 1f)
        return (1.0 - (1.0 - u) * (1.0 - u)) * len * 1.7
    }

    fun render(d: VfxDraw, matrices: MatrixStack, t: Float) {
        spirit(d, matrices, t)
        seam(d, t)
        split(d, t)
    }

    // --- 1. Der Drache ---------------------------------------------------------------------------------------------

    private val rot = Quaternionf()
    private val m3 = Matrix3f()

    private fun orient(matrices: MatrixStack, l: Vec3d, u: Vec3d, b: Vec3d) {
        m3.set(l.x.toFloat(), l.y.toFloat(), l.z.toFloat(), (-u.x).toFloat(), (-u.y).toFloat(), (-u.z).toFloat(), b.x.toFloat(), b.y.toFloat(), b.z.toFloat())
        matrices.multiply(rot.setFromNormalized(m3))
    }

    private fun spirit(d: VfxDraw, matrices: MatrixStack, t: Float) {
        if (t > RUN + 6f) return
        val alpha = (t / 1.5f).coerceIn(0f, 1f) * (1f - ((t - RUN) / 6f).coerceIn(0f, 1f))
        if (alpha <= 0.02f) return
        val model = DragonBreathFx.model() ?: return
        val cam = d.cam
        val sHead = runS(t)
        val buf = d.consumers.getBuffer(RenderLayers.eyes(TEXTURE))
        val col = withAlpha(0xFFCFEFFF.toInt(), 0.7f * alpha)
        val k = NivarisShape.SCALE * SCALE
        val seg = NivarisShape.SEGMENT * SCALE
        // Körper: acht Glieder hinter dem Kopf auf der Schlangenlinie (nur, soweit er schon aus der Klinge ist).
        for (i in 0 until 8) {
            val s0 = sHead - i * seg
            val s1 = s0 - seg
            if (s1 < 0.0) break
            val a = path(s0)
            val b = path(s1)
            val bz = b.subtract(a).normalize()
            var u = UP.subtract(bz.multiply(UP.dotProduct(bz)))
            u = if (u.lengthSquared() < 1e-6) AriusFx.X else u.normalize()
            matrices.push()
            matrices.translate(a.x - cam.x, a.y - cam.y, a.z - cam.z)
            orient(matrices, bz.crossProduct(u), u, bz)
            val g = k * NivarisShape.taper(i + 1) * (1f - i * 0.06f)
            matrices.scale(g, g, k)
            model.glied(i + 1).render(matrices, buf, LightmapTextureManager.MAX_LIGHT_COORDINATE, OverlayTexture.DEFAULT_UV, withAlpha(0xFFBFE6FF.toInt(), 0.55f * alpha * (1f - i / 9f)))
            matrices.pop()
        }
        // Kopf: Blick entlang der Bahn, Maul weit offen.
        val h = path(sHead)
        val f = path(sHead + 0.5).subtract(h).normalize()
        var hu = UP.subtract(f.multiply(f.dotProduct(UP)))
        hu = if (hu.lengthSquared() < 1e-6) AriusFx.X else hu.normalize()
        matrices.push()
        matrices.translate(h.x - cam.x, h.y - cam.y, h.z - cam.z)
        orient(matrices, f.negate().crossProduct(hu), hu, f.negate())
        matrices.scale(k, k, k)
        model.openJaw(0.75f)
        model.head.render(matrices, buf, LightmapTextureManager.MAX_LIGHT_COORDINATE, OverlayTexture.DEFAULT_UV, col)
        matrices.push()
        matrices.scale(1.08f, 1.08f, 1.08f)
        model.head.render(matrices, buf, LightmapTextureManager.MAX_LIGHT_COORDINATE, OverlayTexture.DEFAULT_UV, withAlpha(0xFF6FB0FF.toInt(), 0.25f * alpha))
        matrices.pop()
        model.head.render(matrices, d.consumers.getBuffer(RenderLayers.eyes(EYES)), LightmapTextureManager.MAX_LIGHT_COORDINATE, OverlayTexture.DEFAULT_UV, withAlpha(0xFFFFFFFF.toInt(), alpha))
        model.openJaw(0f)
        matrices.pop()
        d.billboard(VfxLayers.GLOW_ADD, h, 3.2, withAlpha(0xFFBFE6FF.toInt(), 0.45f * alpha))
        d.billboard(VfxLayers.FLARE_ADD, h.add(f.multiply(1.2)), 1.4, withAlpha(0xFFFFFFFF.toInt(), 0.6f * alpha))
        // Lichtschweif hinter dem Körper
        val tail = List(12) { path((sHead - 8 * seg - it * 0.8).coerceAtLeast(0.0)) }
        d.ribbon(VfxLayers.FLOW_ADD, tail, { q -> 0.5 * (1.0 - q) }, { q -> withAlpha(0xFFE6F6FF.toInt(), 0.5f * alpha * (1f - q)) }, uScale = 0.3f, uOffset = -t * 0.3f)
    }

    // --- Naht und Riss ------------------------------------------------------------------------------------------------

    private fun seam(d: VfxDraw, t: Float) {
        if (t > SPLIT + 2f) return
        val k = (t / 2f).coerceIn(0f, 1f) * (0.75f + 0.25f * sin(t * 1.7f))
        val end = origin.add(fwd.multiply(len))
        d.band(VfxLayers.GLOW_ADD, origin, end, span, 0.04 + 0.03 * (t / SPLIT), withAlpha(0xFFFFFFFF.toInt(), k), withAlpha(0xFFFFFFFF.toInt(), 0.8f * k))
        d.band(VfxLayers.GLOW_ADD, origin, end, normal, 0.03, withAlpha(0xFFBFE6FF.toInt(), 0.6f * k), withAlpha(0xFFBFE6FF.toInt(), 0.4f * k))
    }

    private fun split(d: VfxDraw, t: Float) {
        val st = t - SPLIT
        if (st < 0f || st > 22f) return
        val open = 1f - (1f - (st / 6f).coerceIn(0f, 1f)).let { it * it }
        val fade = 1f - (st / 22f).coerceIn(0f, 1f)
        val end = origin.add(fwd.multiply(len))
        val mid = origin.add(fwd.multiply(len * 0.5))
        // Das Lichtblatt klappt nach beiden Seiten auf.
        for (sgn in intArrayOf(-1, 1)) {
            val off = normal.multiply(sgn * 0.6 * open)
            d.band(VfxLayers.GLOW_ADD, origin.add(off), end.add(off), span, half * (0.6 + 0.6 * open), withAlpha(0xFFFFFFFF.toInt(), 0.55f * fade), withAlpha(0xFF8FC8FF.toInt(), 0.25f * fade))
        }
        d.band(VfxLayers.FLOW_ADD, origin, end, span, half * 1.4, withAlpha(0xFFE6F6FF.toInt(), 0.4f * fade), withAlpha(0xFFE6F6FF.toInt(), 0.1f * fade), uScale = 0.25f, uOffset = -st * 0.4f)
        if (st < 4f) d.billboard(VfxLayers.FLARE_ADD, mid, len * 0.45, withAlpha(0xFFFFFFFF.toInt(), (1f - st / 4f) * 0.7f))
        // Frostring am Ende
        val r = 1.0 + 6.0 * open
        d.ring(VfxLayers.GLOW_ADD, end, span, fwd.crossProduct(span).let { if (it.lengthSquared() < 1e-6) AriusFx.X else it.normalize() }, r - 0.4, r, withAlpha(0xFF8FC8FF.toInt(), 0f), withAlpha(0xFFFFFFFF.toInt(), 0.6f * fade), segments = 48)
        // Eissplitter schießen aus der Ebene.
        for ((a, b, side) in shards) {
            val base = origin.add(fwd.multiply(a * len)).add(span.multiply(b * half))
            val dir = normal.multiply(side).add(UP.multiply(0.3)).normalize()
            val p = base.add(dir.multiply(open * 2.6 * kotlin.math.abs(side))).add(0.0, -0.04 * st * st * 0.1, 0.0)
            val sz = 0.12 + 0.08 * kotlin.math.abs(side)
            var sd = dir.crossProduct(UP)
            if (sd.lengthSquared() < 1e-6) sd = AriusFx.X
            sd = sd.normalize()
            val upv = sd.crossProduct(dir).normalize()
            val spin = st * 0.5 + a * 6.0
            val ax = dir.multiply(sz * 2.4)
            val ay = sd.multiply(cos(spin) * sz).add(upv.multiply(sin(spin) * sz))
            val az = ax.crossProduct(ay).let { if (it.lengthSquared() < 1e-9) upv else it.normalize() }.multiply(sz * 0.55)
            d.box(VfxLayers.SHARD_ALPHA, p, ax, ay, az, withAlpha(0xFFD8F2FF.toInt(), 0.85f * fade))
            d.billboard(VfxLayers.GLOW_ADD, p, sz * 4.0, withAlpha(0xFF9FD8FF.toInt(), 0.4f * fade))
        }
    }

    companion object {
        /** Lauf des Geists (Ticks) und Zeitpunkt des Risses nach dem Schnitt (gleich wie [ArtTimings.ART_SPLIT]). */
        const val RUN = 10f
        const val SPLIT = ArtTimings.ART_SPLIT.toFloat()

        /** Gesamtdauer des Effekts. */
        const val LIFE = ArtTimings.ART_SPLIT + 24

        private const val SCALE = 0.6f
        private val UP = Vec3d(0.0, 1.0, 0.0)
        private val TEXTURE = HhaArius.id("textures/entity/nivaris.png")
        private val EYES = HhaArius.id("textures/entity/nivaris_glow_eyes.png")
    }
}
