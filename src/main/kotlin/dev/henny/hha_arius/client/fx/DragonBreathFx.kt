package dev.henny.hha_arius.client.fx

import dev.henny.hha.client.vfx.VfxDraw
import dev.henny.hha.client.vfx.lerpColor
import dev.henny.hha.client.vfx.withAlpha
import dev.henny.hha_arius.HhaArius
import dev.henny.hha_arius.boss.NivarisShape
import dev.henny.hha_arius.client.model.nivaris.NivarisModel
import dev.henny.hha_arius.client.model.nivaris.NivarisRenderers
import net.minecraft.client.MinecraftClient
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
import kotlin.math.min
import kotlin.math.sin

/**
 * Drachenatem der Brustplatte (1.13.0 neu gestaltet):
 * 1. **Vorlauf (0..6 Ticks):** Ein ruhiges, klar gezeichnetes Frost-Siegel öffnet sich über der Schulter; Solvanes
 *    geisterhafter Kopf und geschwungener 5-Glieder-Hals gleiten heraus (zweischichtig: tiefes Gletscher-Marine für
 *    klare Silhouette auf weißem Schnee, darüber eisblauer Lichtsaum und schimmernde Rückenmähne), während sich im
 *    Rachen Frostlicht sammelt.
 * 2. **Atemstrom:** Ein fokussierter, hochkontrastiger Kegel aus tiefblauer Randzone, gleißendem frostweißem Kern,
 *    zwei schraubenförmigen Frostbändern, rhythmischen Druckringen entlang der Bahn und feinen Eiskristallen — ohne
 *    aufgeblähte graue Wolkenklumpen.
 * 3. **Abklang:** Der Kiefer schließt, der Strom löst sich sauber ab, Kopf und Hals sinken ins schließende Siegel.
 */
class DragonBreathFx(private val caster: Entity, private val range: Double, life: Int) : AriusFx(life) {

    private val dirs = ArrayList<Vec3d>()
    private val mouths = ArrayList<Vec3d>()

    private val breathTo get() = life - BREATH_TAIL

    private fun look(td: Float): Vec3d = caster.getRotationVec(td).normalize()

    private fun headJoint(td: Float): Vec3d {
        val f = look(td)
        val eye = caster.getCameraPosVec(td)
        if (firstPerson()) return eye.add(f.multiply(1.15)).add(0.0, 0.92, 0.0)
        // Leicht über der rechten Schulter: im Third-Person-View bleibt Sicht auf den Spieler UND den vollen Atemkegel frei!
        val right = f.crossProduct(UP).let { if (it.lengthSquared() < 1e-4) X else it.normalize() }
        return eye.add(f.multiply(1.30)).add(right.multiply(0.58)).add(0.0, 1.05, 0.0)
    }

    private fun firstPerson(): Boolean {
        val c = MinecraftClient.getInstance()
        return caster === c.player && c.options.perspective.isFirstPerson
    }

    private fun mouth(td: Float): Vec3d = headJoint(td).add(look(td).multiply(HEAD_LEN))

    override fun tick(world: ClientWorld) {
        dirs.add(look(1f))
        mouths.add(mouth(1f))
        val fx = particlesAllowed()
        if (fx <= 0f) return
        val r = random()
        val f = look(1f)
        val m = mouth(1f)
        if (age < BREATH_FROM) {
            // Vorlauf: wenige feine Frostfunken ziehen sich ins Maul zusammen
            repeat((2 * fx).toInt().coerceAtLeast(1)) {
                val off = Vec3d((r.nextDouble() - 0.5) * 1.6, (r.nextDouble() - 0.5) * 1.2, (r.nextDouble() - 0.5) * 1.6)
                val from = m.add(off)
                val v = m.subtract(from).multiply(0.16)
                world.addParticleClient(ParticleTypes.END_ROD, from.x, from.y, from.z, v.x, v.y, v.z)
            }
            return
        }
        if (age >= breathTo) return
        repeat((3 * fx).toInt().coerceAtLeast(1)) {
            val spread = Vec3d((r.nextDouble() - 0.5) * 0.24, (r.nextDouble() - 0.5) * 0.16, (r.nextDouble() - 0.5) * 0.24)
            val v = f.add(spread).normalize().multiply(0.68 + r.nextDouble() * 0.45)
            world.addParticleClient(if (it % 2 == 0) ParticleTypes.SNOWFLAKE else ParticleTypes.END_ROD, m.x, m.y, m.z, v.x, v.y, v.z)
        }
    }

    override fun render(d: VfxDraw, matrices: MatrixStack, t: Float) {
        if (caster.isRemoved) return
        val td = d.tickDelta
        val appear = smoothstep(t / APPEAR)
        val vanish = smoothstep((life - t) / 7f)
        val headAlpha = appear * vanish
        val jaw = when {
            t < BREATH_FROM - 2.5f -> smoothstep(t / (BREATH_FROM - 2.5f)) * 0.22f
            t < BREATH_FROM + 2.5f -> 0.22f + 0.78f * smoothstep((t - (BREATH_FROM - 2.5f)) / 5f)
            t < breathTo -> 1f
            else -> 1f - smoothstep((t - breathTo) / 5f)
        }
        renderHeadAndNeck(d, matrices, t, td, headAlpha, jaw)
        if (t < BREATH_FROM + 1f) renderCharge(d, t, td, headAlpha)
        renderBreath(d, t, td)
    }

    private fun smoothstep(x: Float): Float {
        val c = x.coerceIn(0f, 1f)
        return c * c * (3f - 2f * c)
    }

    // --- Geisterkopf, geschwungener Hals & Frost-Siegel ------------------------------------------------------------

    private fun renderHeadAndNeck(d: VfxDraw, matrices: MatrixStack, t: Float, td: Float, alpha: Float, jaw: Float) {
        if (alpha <= 0.02f) return
        val model = model() ?: return
        val fp = firstPerson()
        val f = look(td)
        val hu = UP.subtract(f.multiply(f.dotProduct(UP))).let { if (it.lengthSquared() < 1e-6) Z else it.normalize() }
        val hl = f.negate().crossProduct(hu)
        val y = hu.negate()
        val z = f.negate()

        val emerge = (1f - alpha).toDouble()
        val recoil = if (jaw > 0.85f) 0.09 * sin(t * 1.45) else 0.0
        val joint = headJoint(td).subtract(f.multiply(emerge * 1.05 - recoil))
        // Siegel schwebt schlank über dem Rücken des Spielers (nie direkt vor der Third-Person-Kamera)
        val portal = joint.subtract(f.multiply(1.55)).add(hu.multiply(0.32))
        val cam = d.cam

        // Klares, schlankes Frost-Siegel am Halsansatz (Tiefblau-Kontrast + scharfer Eisring)
        val ringR = 0.68 + 0.16 * alpha
        d.ring(AriusLayers.INK, portal, hl, hu, ringR * 0.80, ringR * 1.04,
            withAlpha(NAVY_DEEP, 0.48f * alpha), withAlpha(ICE_MID, 0.72f * alpha), segments = 32)
        d.ring(AriusLayers.VFX_GLOW, portal, hl, hu, ringR * 0.90, ringR * 1.02,
            withAlpha(ICE_MID, 0.25f * alpha), withAlpha(WHITE, 0.82f * alpha), segments = 32)
        d.disc(AriusLayers.VFX_RUNE, portal, hl, hu, ringR * 0.90,
            withAlpha(ICE_MID, (if (fp) 0.24f else 0.42f) * alpha), t * 0.04)

        // Geschwungener 5-Glieder-Hals mit durchgehendem Licht-Rückgrat
        renderNeck(d, matrices, joint, portal, f, hu, hl, t, alpha, fp)

        // Zweischichtiger Geisterkopf: erst kontraststarkes Gletscher-Marine, darüber heller Eis-Saum + Augen
        matrices.push()
        matrices.translate(joint.x - cam.x, joint.y - cam.y, joint.z - cam.z)
        m3.set(hl.x.toFloat(), hl.y.toFloat(), hl.z.toFloat(), y.x.toFloat(), y.y.toFloat(), y.z.toFloat(), z.x.toFloat(), z.y.toFloat(), z.z.toFloat())
        matrices.multiply(rot.setFromNormalized(m3))
        val k = NivarisShape.SCALE * HEAD_SCALE * (0.90f + 0.10f * alpha)
        matrices.scale(k, k, k)
        model.openJaw(jaw * 0.88f)

        val consumers = d.consumers
        val bodyAlpha = (if (fp) 0.36f else 0.64f) * alpha
        val rimAlpha = (if (fp) 0.24f else 0.56f) * alpha

        // 1) Tiefblau-silbrige Silhouette (bleibt auf weißem Schnee gestochen scharf)
        // Jeder RenderLayer muss vor dem nächsten getBuffer()-Aufruf fertig gerendert sein!
        val bodyBuf = consumers.getBuffer(RenderLayers.entityTranslucent(TEXTURE))
        model.head.render(matrices, bodyBuf, LightmapTextureManager.MAX_LIGHT_COORDINATE, OverlayTexture.DEFAULT_UV, withAlpha(GHOST_BODY, bodyAlpha))
        // 2) Leuchtender Eis-Saum
        val rimBuf = consumers.getBuffer(RenderLayers.eyes(TEXTURE))
        matrices.push()
        matrices.scale(1.045f, 1.045f, 1.045f)
        model.head.render(matrices, rimBuf, LightmapTextureManager.MAX_LIGHT_COORDINATE, OverlayTexture.DEFAULT_UV, withAlpha(GHOST_RIM, rimAlpha))
        matrices.pop()
        // 3) Klare leuchtende Augen
        val eyesBuf = consumers.getBuffer(RenderLayers.eyes(EYES))
        model.head.render(matrices, eyesBuf, LightmapTextureManager.MAX_LIGHT_COORDINATE, OverlayTexture.DEFAULT_UV, withAlpha(WHITE, alpha))
        model.openJaw(0f)
        matrices.pop()
    }

    private fun renderNeck(
        d: VfxDraw, matrices: MatrixStack, joint: Vec3d, portal: Vec3d,
        fwd: Vec3d, hu: Vec3d, hl: Vec3d, t: Float, alpha: Float, fp: Boolean,
    ) {
        val model = model() ?: return
        val cam = d.cam
        val n = 5
        val spine = ArrayList<Vec3d>(n + 1)
        for (i in 0..n) {
            val s = i / n.toDouble()
            // Sanfter S-Bogen aus dem Siegel nach vorn zum Kopf
            val arch = sin(s * PI) * 0.34
            val sway = sin(t * 0.22 - s * 2.2) * 0.08 * sin(s * PI)
            spine += joint.lerp(portal, s).add(hu.multiply(arch)).add(hl.multiply(sway))
        }

        // Zwei getrennte Durchgänge (erst Körper, dann Lichtsaum), damit Immediate-Buffer nicht vorzeitig schließt
        for (pass in 0..1) {
            val buf = d.consumers.getBuffer(if (pass == 0) RenderLayers.entityTranslucent(TEXTURE) else RenderLayers.eyes(TEXTURE))
            val baseCol = if (pass == 0) GHOST_BODY else GHOST_RIM
            val baseAlpha = if (pass == 0) (if (fp) 0.32f else 0.72f) else (if (fp) 0.20f else 0.52f)
            val rimScale = if (pass == 0) 1.0f else 1.05f
            for (k in 0 until n) {
                val a = spine[k]
                val b = spine[k + 1]
                val dir = b.subtract(a)
                val len = dir.length()
                if (len < 1e-4) continue
                val bz = dir.multiply(1.0 / len)
                var u = UP.subtract(bz.multiply(UP.dotProduct(bz)))
                u = if (u.lengthSquared() < 1e-6) X else u.normalize()
                val l = bz.crossProduct(u)
                val fadeSeg = (1f - k / (n + 0.5f)) * alpha
                matrices.push()
                matrices.translate(a.x - cam.x, a.y - cam.y, a.z - cam.z)
                m3.set(l.x.toFloat(), l.y.toFloat(), l.z.toFloat(), (-u.x).toFloat(), (-u.y).toFloat(), (-u.z).toFloat(), bz.x.toFloat(), bz.y.toFloat(), bz.z.toFloat())
                matrices.multiply(rot.setFromNormalized(m3))
                val g = NivarisShape.SCALE * HEAD_SCALE * NivarisShape.taper(k) * 0.86f * rimScale
                val gz = NivarisShape.SCALE * HEAD_SCALE * (len / NivarisShape.SEGMENT).toFloat() * 1.12f
                matrices.scale(g, g, gz)
                model.glied(k).render(matrices, buf, LightmapTextureManager.MAX_LIGHT_COORDINATE, OverlayTexture.DEFAULT_UV, withAlpha(baseCol, baseAlpha * fadeSeg))
                matrices.pop()
            }
        }
        // Schimmernde Frostmähne entlang des Halsrückens
        val mane = spine.map { it.add(hu.multiply(0.32)) }
        d.ribbon(AriusLayers.VFX_FLOW, mane, { s -> 0.24 * (1.0 - s * 0.65) },
            { s -> withAlpha(ICE_BRIGHT, (if (fp) 0.30f else 0.65f) * alpha * (1f - s)) }, uScale = 0.45f, uOffset = -t * 0.14f)
    }

    /** Vorlauf-Sog im Rachen (0..BREATH_FROM): klarer Aufbau vor dem Ausstoß. */
    private fun renderCharge(d: VfxDraw, t: Float, td: Float, alpha: Float) {
        val k = smoothstep(t / BREATH_FROM) * alpha
        val m = mouth(td)
        val f = look(td)
        val side = f.crossProduct(UP).let { if (it.lengthSquared() < 1e-4) X else it.normalize() }
        val upv = side.crossProduct(f).normalize()
        val r = 1.15 * (1.0 - 0.65 * k)
        d.ring(AriusLayers.INK, m, side, upv, r * 0.75, r, withAlpha(NAVY_DEEP, 0f), withAlpha(ICE_MID, 0.72f * k), segments = 28)
        d.billboard(AriusLayers.VFX_FLARE, m, 0.55 + 0.85 * k, withAlpha(WHITE, 0.9f * k))
        d.billboard(AriusLayers.VFX_GLOW, m, 1.4 + 0.8 * k, withAlpha(ICE_BRIGHT, 0.45f * k))
    }

    // --- Fokussierter, mehrschichtiger Frostodem -------------------------------------------------------------------

    private fun dirAt(i: Int, td: Float): Vec3d = dirs.getOrNull(i) ?: look(td)
    private fun mouthAt(i: Int, td: Float): Vec3d = mouths.getOrNull(i) ?: mouth(td)

    private fun renderBreath(d: VfxDraw, t: Float, td: Float) {
        if (t < BREATH_FROM) return
        val fx = AriusClientConfig.fx.coerceIn(0.3f, 1f)
        val activeT = t - BREATH_FROM
        val activeSpan = (breathTo - BREATH_FROM).coerceAtLeast(1)
        val extend = smoothstep(activeT / 4.5f)
        val release = if (t <= breathTo) 0f else smoothstep((t - breathTo) / BREATH_TAIL.toFloat())
        val fade = (1f - release) * smoothstep(activeT / 2.2f)
        if (fade <= 0.01f) return

        // Verzögerte Leitlinie des Strahls (16 Stützpunkte von Maul bis Reichweite, folgt Kopfbewegungen geschmeidig)
        val segs = 16
        val pts = ArrayList<Vec3d>(segs + 1)
        val sides = ArrayList<Vec3d>(segs + 1)
        val ups = ArrayList<Vec3d>(segs + 1)
        val startFrac = release.toDouble() * 0.72
        val endFrac = (extend.toDouble() * (1.0 + release * 0.15)).coerceAtMost(1.08)
        val floorY = caster.y + 0.12

        for (i in 0..segs) {
            val q = startFrac + (endFrac - startFrac) * (i / segs.toDouble())
            val lag = (q * 7.5).toInt()
            val sampleTick = (t.toInt() - lag).coerceIn(BREATH_FROM, breathTo)
            val dir = dirAt(sampleTick, td)
            val m0 = mouthAt(sampleTick, td)
            val dist = q * range
            var p = m0.add(dir.multiply(dist)).add(0.0, -0.008 * dist * dist, 0.0)
            if (p.y < floorY + q * 0.45) p = Vec3d(p.x, floorY + q * 0.45, p.z)
            pts += p
            val s = dir.crossProduct(UP).let { if (it.lengthSquared() < 1e-4) X else it.normalize() }
            sides += s
            ups += s.crossProduct(dir).normalize()
        }

        // 1) Tiefblauer Kontrastmantel (hält den Atem auf Schnee und im Sturm gestochen scharf)
        d.ribbon(
            AriusLayers.INK, pts,
            { q -> 0.34 + q * 1.95 },
            { q ->
                val edgeFade = sin(q * PI).toFloat().coerceIn(0f, 1f)
                withAlpha(lerpColor(NAVY_DEEP, ICE_MID, 1f - q), 0.56f * fade * edgeFade)
            },
            uScale = 0.22f, uOffset = -t * 0.18f,
        )

        // 2) Fließender eisblauer Hauptstrom (VFX_FLOW)
        d.ribbon(
            AriusLayers.VFX_FLOW, pts,
            { q -> 0.38 + q * 2.25 },
            { q -> withAlpha(lerpColor(ICE_MID, WHITE, (1f - q * 0.65f).coerceIn(0f, 1f)), 0.86f * fade * (1f - q * 0.45f)) },
            uScale = 0.28f, uOffset = -t * 0.24f,
        )

        // 3) Gleißender frostweißer Innenkern (verjüngt sich elegant)
        d.ribbon(
            AriusLayers.VFX_GLOW, pts,
            { q -> 0.18 + q * 0.88 },
            { q -> withAlpha(lerpColor(ICE_BRIGHT, WHITE, (1f - q).coerceIn(0f, 1f)), 0.95f * fade * (1f - q * 0.40f)) },
            uScale = 0.2f, uOffset = -t * 0.20f,
        )

        // 4) Zwei enge, geschwungene Frost-Spiralen um den Kegel
        for (strand in 0 until 2) {
            val spiral = ArrayList<Vec3d>(segs + 1)
            for (i in 0..segs) {
                val q = i / segs.toDouble()
                val rad = 0.26 + q * range * 0.18
                val ang = q * 8.5 - t * 0.58 + strand * PI
                spiral += pts[i].add(sides[i].multiply(cos(ang) * rad)).add(ups[i].multiply(sin(ang) * rad))
            }
            d.ribbon(
                AriusLayers.VFX_GLOW, spiral,
                { q -> 0.08 + q * 0.12 },
                { q -> withAlpha(if (strand == 0) WHITE else ICE_BRIGHT, 0.85f * fade * (1f - q * 0.80f)) },
                uScale = 1f, uOffset = 0f,
            )
        }

        // 5) Rhythmische Frost-Druckringe, die mit dem Strahl vorwärts rasen (auch von hinten sofort als Kegel lesbar!)
        val ringCount = 6
        for (rIdx in 0 until ringCount) {
            val phase = ((activeT * 0.095f + rIdx.toFloat() / ringCount) % 1f)
            if (phase > extend) continue
            val fIdx = (phase * segs).coerceIn(0f, (segs - 1).toFloat())
            val i0 = fIdx.toInt()
            val frac = (fIdx - i0).toDouble()
            val pos = pts[i0].lerp(pts[i0 + 1], frac)
            val rad = 0.44 + phase * (range * 0.21)
            val rf = sin(phase * PI).toFloat() * fade
            d.ring(AriusLayers.INK, pos, sides[i0], ups[i0], rad * 0.78, rad * 1.06,
                withAlpha(NAVY_DEEP, 0f), withAlpha(NAVY_MID, 0.65f * rf), segments = 28)
            d.ring(AriusLayers.VFX_GLOW, pos, sides[i0], ups[i0], rad * 0.86, rad * 1.04,
                withAlpha(ICE_BRIGHT, 0f), withAlpha(WHITE, 0.88f * rf), segments = 28)
        }

        // 6) Facettierte Eiskristalle entlang der Stromkanten (schlank, in Flugrichtung gestreckt)
        val shardCount = (10 * fx).toInt().coerceAtLeast(4)
        for (j in 0 until shardCount) {
            val q = ((activeT * 0.085 + j.toDouble() / shardCount) % 1.0)
            if (q > extend) continue
            val i0 = (q * (segs - 1)).toInt().coerceIn(0, segs - 1)
            val fwd = pts[i0 + 1].subtract(pts[i0]).let { if (it.lengthSquared() < 1e-6) look(td) else it.normalize() }
            val ang = j * 2.39996 + t * 0.15
            val rad = (0.3 + q * range * 0.15) * (0.65 + 0.35 * sin(j * 1.9))
            val pos = pts[i0].add(sides[i0].multiply(cos(ang) * rad)).add(ups[i0].multiply(sin(ang) * rad))
            val sf = sin(q * PI).toFloat() * fade
            val sz = 0.09 + q * 0.08
            d.box(AriusLayers.INK, pos, sides[i0].multiply(sz * 0.65), ups[i0].multiply(sz * 0.65), fwd.multiply(sz * 2.6),
                withAlpha(if (j % 2 == 0) ICE_BRIGHT else NAVY_MID, 0.82f * sf))
        }

        // 7) Scharfer Mündungsblitz am geöffneten Kiefer
        if (t < breathTo + 1f) {
            val m = mouth(td)
            val f = look(td)
            val pulse = 0.9f + 0.12f * sin(t * 1.5f)
            d.billboard(AriusLayers.VFX_FLARE, m, 1.35 * pulse, withAlpha(WHITE, 0.92f * fade))
            d.billboard(AriusLayers.VFX_GLOW, m.add(f.multiply(0.8)), 2.2 * pulse, withAlpha(ICE_BRIGHT, 0.45f * fade))
        }

        // 8) Leichte Boden-Reifwelle dort, wo der Strahl nahe am Boden entlangstreicht
        val endPt = pts.last()
        if (endPt.y - caster.y < 1.8 && activeSpan > 0) {
            val gPos = Vec3d(endPt.x, caster.y + 0.09, endPt.z)
            val gr = 1.2 + 0.6 * sin(t * 0.4)
            d.ring(AriusLayers.VFX_GLOW, gPos, X, Z, gr * 0.55, gr, withAlpha(ICE_MID, 0f), withAlpha(ICE_BRIGHT, 0.45f * fade), segments = 24)
        }
    }

    companion object {
        private const val APPEAR = 5f
        private const val BREATH_FROM = 6
        private const val BREATH_TAIL = 8
        private const val HEAD_SCALE = 0.66f
        private val HEAD_LEN = 15.0 / 16.0 * NivarisShape.SCALE * HEAD_SCALE * 0.78
        internal val TEXTURE = HhaArius.id("textures/entity/nivaris.png")
        private val EYES = HhaArius.id("textures/entity/nivaris_glow_eyes.png")

        private const val NAVY_DEEP = 0xFF0E3458.toInt()
        private const val NAVY_MID = 0xFF1A5282.toInt()
        private const val ICE_MID = 0xFF6EC8F5.toInt()
        private const val ICE_BRIGHT = 0xFFC8EEFF.toInt()
        private const val GHOST_BODY = 0xFF3474A8.toInt()
        private const val GHOST_RIM = 0xFFC8F2FF.toInt()

        private val m3 = Matrix3f()
        private val rot = Quaternionf()
        private var cached: NivarisModel? = null

        fun lifeFor(breathTicks: Int): Int = BREATH_FROM + breathTicks + BREATH_TAIL

        internal fun model(): NivarisModel? {
            cached?.let { return it }
            val part = runCatching { MinecraftClient.getInstance().loadedEntityModels.getModelPart(NivarisRenderers.LAYER) }.getOrNull() ?: return null
            return NivarisModel(part).also { cached = it }
        }

        fun reset() {
            cached = null
        }
    }
}
