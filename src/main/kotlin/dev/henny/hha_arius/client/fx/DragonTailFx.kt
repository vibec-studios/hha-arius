package dev.henny.hha_arius.client.fx

import dev.henny.hha.client.vfx.VfxDraw
import dev.henny.hha.client.vfx.lerpColor
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
 * Drachenschweif der Beinschienen (1.13.0 neu gestaltet):
 * 1. **Auftakt & Peitschenkurve:** Ein geneigtes Frost-Siegel öffnet sich am Rücken; Solvanes geisterhafter Schweif
 *    (18 geschmeidig verjüngte Glieder + Fächer, zweischichtig in Gletscher-Marine und frostweißem Lichtsaum samt
 *    durchgehender Rücken-Lichtspur) schwingt in einer echten Peitschenkurve einmal rund um den Spieler.
 * 2. **Scharfe Frostsichel:** Statt grauer Rauchklumpen zieht die Schweifspitze eine rasiermesserscharfe Sichel aus
 *    tiefblauem Kontrastband, fließendem Eislicht und weißer Schneide über die gesamte Bahn, begleitet von feinen
 *    Eiskristallen und einem klaren Boden-Frostring.
 * 3. **Abschluss & Abklang:** Am Ende des Schwungs läuft ein klarer Frostring nach außen, der Schweif hebt sich leicht
 *    und löst sich sauber von der Wurzel zur Spitze auf.
 */
class DragonTailFx(private val caster: Entity, private val start: Double, private val radius: Double, life: Int) : AriusFx(life) {

    private fun sweep(t: Float): Double {
        val k = (t / SWEEP).coerceIn(0f, 1f).toDouble()
        // S-Kurve mit schnellem Peitschenhieb in der Mitte und weichem Nachschwingen am Ende
        val ease = 1.0 - (1.0 - k).pow(2.8)
        val overshoot = if (t <= SWEEP) 0.0 else ((t - SWEEP) / (life - SWEEP).coerceAtLeast(1f)).coerceIn(0f, 1f) * 0.28
        return start + 2 * PI * ease + overshoot
    }

    override fun tick(world: ClientWorld) {
        if (age > SWEEP + 2) return
        val fx = particlesAllowed()
        if (fx <= 0f) return
        val c = caster.entityPos
        val a = sweep(age.toFloat())
        val tip = c.add(sin(a) * radius, 0.65, cos(a) * radius)
        repeat((3 * fx).toInt().coerceAtLeast(1)) {
            world.addParticleClient(if (it == 0) ParticleTypes.END_ROD else ParticleTypes.SNOWFLAKE,
                tip.x, tip.y, tip.z, cos(a) * 0.16, 0.04, -sin(a) * 0.16)
        }
    }

    override fun render(d: VfxDraw, matrices: MatrixStack, t: Float) {
        if (caster.isRemoved) return
        val td = d.tickDelta
        val c = caster.getLerpedPos(td)
        val grow = smoothstep(t / 2.6f)
        val fade = if (t <= SWEEP) 1f else (1f - smoothstep((t - SWEEP) / (life - SWEEP).coerceAtLeast(1f)))
        if (fade <= 0.01f) return

        val lead = sweep(t)
        val sweepK = (t / SWEEP).coerceIn(0f, 1f)
        val liftAfter = if (t <= SWEEP) 0.0 else ((t - SWEEP) / (life - SWEEP).coerceAtLeast(1f)) * 0.65

        // Peitschenkurve: 18 Punkte vom unteren Rücken bis zur Spitze
        val n = 18
        val pts = Array(n) { k ->
            val s = k / (n - 1.0)
            // Peitsche: Wurzel läuft leicht voraus, Mitte biegt sich kraftvoll, Spitze schnappt am Ende durch
            val lagCurve = (1.0 - s).pow(1.25) * LAG * (1.0 - 0.35 * sweepK)
            val ang = lead - lagCurve
            val r = 0.38 + (radius - 0.38) * s.pow(0.82) * grow
            val wave = sin(t * 0.55 - s * 4.5) * 0.14 * s * (1.0 - 0.5 * (1.0 - fade))
            val y = 1.02 - 0.44 * s + wave + liftAfter * s
            c.add(sin(ang) * r, y, cos(ang) * r)
        }

        // 1) Geneigtes Frost-Siegel am Rücken (Wurzel des Schweifs)
        val rootDir = pts[1].subtract(pts[0]).let { if (it.lengthSquared() < 1e-6) Z else it.normalize() }
        val rSide = rootDir.crossProduct(UP).let { if (it.lengthSquared() < 1e-4) X else it.normalize() }
        val rUp = rSide.crossProduct(rootDir).normalize()
        val riftPos = pts[0].subtract(rootDir.multiply(0.15))
        val riftR = 0.85 * grow
        d.ring(AriusLayers.INK, riftPos, rSide, rUp, riftR * 0.78, riftR,
            withAlpha(NAVY_DEEP, 0.5f * fade), withAlpha(ICE_MID, 0.75f * fade), segments = 24)
        d.disc(AriusLayers.VFX_RUNE, riftPos, rSide, rUp, riftR * 0.92,
            withAlpha(ICE_BRIGHT, 0.55f * fade * grow), t * 0.09)

        // 2) Geisterhafter Drachenschweif (zweischichtig + durchgehende Rücken-Lichtbahn)
        renderTail(d, matrices, pts, t, fade * grow)

        // 3) Rasiermesserscharfe Frostsichel entlang der überstrichenen Bahn (auf Höhe der Schweifspitze & am Boden)
        val arcSpan = (lead - start).coerceAtMost(2.0 * PI)
        if (arcSpan > 0.04) {
            val trailStart = maxOf(start, lead - 2.65)
            val slashPos = c.add(0.0, 0.70 + liftAfter * 0.4, 0.0)
            // Kontraststarker tiefblauer Klingenrücken (auf weißem Schnee extrem sauber lesbar)
            d.arc(AriusLayers.INK, slashPos, Z, X, radius * 0.62, radius * 1.04, trailStart, lead,
                withAlpha(NAVY_DEEP, 0.08f * fade), withAlpha(NAVY_DEEP, 0.86f * fade), segments = 48)
            // Fließendes eisblaues Schwungband
            d.arc(AriusLayers.VFX_FLOW, slashPos.add(0.0, 0.03, 0.0), Z, X, radius * 0.46, radius * 1.07, trailStart, lead,
                withAlpha(ICE_MID, 0f), withAlpha(ICE_BRIGHT, 0.88f * fade), segments = 48, uTile = 2.5f, uOffset = -t * 0.25f)
            // Gleißende frostweiße Außenkante
            d.arc(AriusLayers.VFX_GLOW, slashPos.add(0.0, 0.06, 0.0), Z, X, radius * 0.86, radius * 1.05, trailStart, lead,
                withAlpha(ICE_BRIGHT, 0.20f * fade), withAlpha(WHITE, 1.0f * fade), segments = 48)

            // Sauberer Boden-Frostring entlang des gesamten überstrichenen Winkels
            val floorPos = c.add(0.0, 0.09, 0.0)
            d.arc(AriusLayers.INK, floorPos, Z, X, radius * 0.78, radius * 1.02, start, lead,
                withAlpha(NAVY_DEEP, 0.16f * fade), withAlpha(NAVY_MID, 0.64f * fade), segments = 48)
            d.arc(AriusLayers.VFX_GLOW, floorPos.add(0.0, 0.02, 0.0), Z, X, radius * 0.92, radius * 1.04, start, lead,
                withAlpha(ICE_MID, 0.15f * fade), withAlpha(WHITE, 0.82f * fade), segments = 48)
        }

        // 4) Scharfe Eiskristalle, die sich tangential von der Schweifspitze lösen
        val shardCount = (10 * AriusClientConfig.fx.coerceIn(0.3f, 1f)).toInt().coerceAtLeast(4)
        for (i in 0 until shardCount) {
            val phase = (i + 0.5) / shardCount
            val dt = t - SWEEP * phase
            if (dt !in 0.0..8.5) continue
            val a = start + 2.0 * PI * (1.0 - (1.0 - phase).pow(2.8))
            val radDir = Vec3d(sin(a), 0.0, cos(a))
            val tang = Vec3d(cos(a), 0.0, -sin(a))
            val p = c.add(radDir.multiply(radius * (0.90 + 0.08 * sin(i * 2.7)) + dt * 0.14))
                .add(tang.multiply(dt * 0.12))
                .add(0.0, 0.45 + 0.18 * sin(i * 1.7) + dt * 0.04, 0.0)
            val sa = (1.0 - dt / 8.5).toFloat().coerceIn(0f, 1f) * fade
            d.box(AriusLayers.INK, p, radDir.multiply(0.055), UP.multiply(0.055), tang.multiply(0.24),
                withAlpha(if (i % 2 == 0) ICE_BRIGHT else NAVY_MID, 0.88f * sa))
        }

        // 5) Lichtspitze am Schwanzfächer
        val tip = pts.last()
        d.billboard(AriusLayers.VFX_FLARE, tip, 1.35 * grow, withAlpha(WHITE, 0.95f * fade))
        d.billboard(AriusLayers.VFX_GLOW, tip, 2.4 * grow, withAlpha(ICE_BRIGHT, 0.45f * fade))

        // 6) Abschluss-Druckring nach Vollendung des 360°-Schwungs
        if (t > SWEEP - 1f) {
            val s = ((t - (SWEEP - 1f)) / (life - SWEEP + 1f).coerceAtLeast(1f)).coerceIn(0f, 1f)
            val ease = 1f - (1f - s) * (1f - s)
            val rOut = radius * (1.0 + 0.42 * ease)
            val rf = 1f - s
            d.ring(AriusLayers.INK, c.add(0.0, 0.10, 0.0), X, Z, rOut - 0.55, rOut,
                withAlpha(NAVY_DEEP, 0f), withAlpha(ICE_MID, 0.62f * rf), segments = 48)
            d.ring(AriusLayers.VFX_GLOW, c.add(0.0, 0.12, 0.0), X, Z, rOut - 0.35, rOut + 0.06,
                withAlpha(ICE_BRIGHT, 0f), withAlpha(WHITE, 0.85f * rf), segments = 48)
        }
    }

    private fun renderTail(d: VfxDraw, matrices: MatrixStack, pts: Array<Vec3d>, t: Float, alpha: Float) {
        if (alpha <= 0.02f) return
        val model = DragonBreathFx.model() ?: return
        val cam = d.cam
        val first = NivarisShape.SEGMENTS - (pts.size - 1)

        // Durchgehende Frost-Rückenlinie über den gesamten Schweif (vor den Model-Passes rendern)
        val spine = pts.map { it.add(0.0, 0.24, 0.0) }
        d.ribbon(AriusLayers.VFX_FLOW, spine, { q -> 0.28 * (1.0 - q * 0.55) },
            { q -> withAlpha(lerpColor(ICE_MID, WHITE, q), 0.72f * alpha) }, uScale = 0.35f, uOffset = -t * 0.22f)

        // Zwei getrennte Durchgänge (erst Körper, dann Lichtsaum), damit Immediate-Buffer nicht vorzeitig schließt
        for (pass in 0..1) {
            val buf = d.consumers.getBuffer(
                if (pass == 0) RenderLayers.entityTranslucent(DragonBreathFx.TEXTURE) else RenderLayers.eyes(DragonBreathFx.TEXTURE)
            )
            val segCol = if (pass == 0) GHOST_BODY else GHOST_RIM
            val segAlpha = if (pass == 0) 0.76f else 0.58f
            val rimScale = if (pass == 0) 1.0f else 1.06f

            for (k in 0 until pts.size - 1) {
                val a = pts[k]
                val b = pts[k + 1]
                val dir = b.subtract(a)
                val len = dir.length()
                if (len < 1e-4) continue
                val bz = dir.multiply(1.0 / len)
                val i = first + k
                // Sanftes Auflösen von Wurzel zur Spitze nach dem Schwung
                val segFade = if (t <= SWEEP) alpha else {
                    val dissolve = ((t - SWEEP) / (life - SWEEP).coerceAtLeast(1f))
                    val s = k / (pts.size - 1f)
                    (alpha * ((s + 0.35f - dissolve * 0.85f) / 0.35f).coerceIn(0f, 1f))
                }
                if (segFade <= 0.02f) continue

                matrices.push()
                matrices.translate(a.x - cam.x, a.y - cam.y, a.z - cam.z)
                orient(matrices, bz)
                val sFrac = k / (pts.size - 1f)
                val whipTaper = 1.0f - 0.38f * sFrac
                val g = NivarisShape.SCALE * TK * NivarisShape.taper(i) * whipTaper * rimScale
                val gz = NivarisShape.SCALE * (len / NivarisShape.SEGMENT).toFloat() * 1.12f
                matrices.scale(g, g, gz)
                model.glied(i).render(matrices, buf, LightmapTextureManager.MAX_LIGHT_COORDINATE, OverlayTexture.DEFAULT_UV, withAlpha(segCol, segAlpha * segFade))
                matrices.pop()
            }

            // Prominenter Schwanzfächer an der Spitze
            val tip = pts.last()
            val bz = tip.subtract(pts[pts.size - 2]).normalize()
            matrices.push()
            matrices.translate(tip.x - cam.x, tip.y - cam.y, tip.z - cam.z)
            orient(matrices, bz)
            val k = NivarisShape.SCALE * TK * 1.38f * rimScale
            matrices.scale(k, k, k)
            val fanCol = if (pass == 0) GHOST_BODY else WHITE
            val fanAlpha = if (pass == 0) 0.80f else 0.65f
            model.tailFan.render(matrices, buf, LightmapTextureManager.MAX_LIGHT_COORDINATE, OverlayTexture.DEFAULT_UV, withAlpha(fanCol, fanAlpha * alpha))
            matrices.pop()
        }
    }

    private fun smoothstep(x: Float): Float {
        val c = x.coerceIn(0f, 1f)
        return c * c * (3f - 2f * c)
    }

    private fun orient(matrices: MatrixStack, b: Vec3d) {
        var u = UP.subtract(b.multiply(UP.dotProduct(b)))
        u = if (u.lengthSquared() < 1e-6) X else u.normalize()
        val l = b.crossProduct(u)
        m3.set(l.x.toFloat(), l.y.toFloat(), l.z.toFloat(), (-u.x).toFloat(), (-u.y).toFloat(), (-u.z).toFloat(), b.x.toFloat(), b.y.toFloat(), b.z.toFloat())
        matrices.multiply(rot.setFromNormalized(m3))
    }

    companion object {
        const val SWEEP = 12f
        private const val LAG = 2.45
        private const val TK = 0.42f

        private const val NAVY_DEEP = 0xFF0E3458.toInt()
        private const val NAVY_MID = 0xFF1A5282.toInt()
        private const val ICE_MID = 0xFF6EC8F5.toInt()
        private const val ICE_BRIGHT = 0xFFC8EEFF.toInt()
        private const val GHOST_BODY = 0xFF286090.toInt()
        private const val GHOST_RIM = 0xFFB8ECFF.toInt()

        private val m3 = Matrix3f()
        private val rot = Quaternionf()
    }
}
