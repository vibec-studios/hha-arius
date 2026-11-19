package dev.henny.hha_arius.client.fx

import dev.henny.hha.client.vfx.VfxDraw
import dev.henny.hha.client.vfx.VfxLayers
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
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Drachenatem der Brustplatte (seit 1.4.2, nach den Drachen-Anrufungen aus Elden Ring): Vor dem Wirker erscheint
 * Solvanes Kopf als Geisterlicht, öffnet den Kiefer und speit einen breiten, rollenden Frostodem – weiche, sich
 * aufblähende Schwaden, die der Kopfbewegung mit Verzögerung folgen (jede Schwade behält die Richtung ihres Ausstoßes),
 * ein gleißender Kern direkt am Maul, Eisglitzern und Raureif-Partikel. Am Ende schließt sich das Maul und der Kopf
 * zerfällt zu Licht.
 *
 * [e1] = Wirker, [range] = Reichweite (Blöcke), [life] = Dauer in Ticks (Atem + Ein-/Ausblenden).
 */
class DragonBreathFx(private val caster: Entity, private val range: Double, life: Int) : AriusFx(life) {

    private val dirs = ArrayList<Vec3d>()
    private val mouths = ArrayList<Vec3d>()

    /** Der Kopf erscheint in [APPEAR] Ticks, der Atem läuft von [BREATH_FROM] bis life − [BREATH_TAIL]. */
    private val breathTo get() = life - BREATH_TAIL

    private fun look(td: Float): Vec3d = caster.getRotationVec(td).normalize()

    private fun headJoint(td: Float): Vec3d {
        val f = look(td)
        val eye = caster.getCameraPosVec(td)
        return eye.add(f.multiply(1.1)).add(0.0, if (firstPerson()) 0.95 else 0.35, 0.0)
    }

    /** Eigene Ego-Sicht: Kopf etwas höher und durchscheinender, damit er den Blick nicht verdeckt. */
    private fun firstPerson(): Boolean {
        val c = MinecraftClient.getInstance()
        return caster === c.player && c.options.perspective.isFirstPerson
    }

    private fun mouth(td: Float): Vec3d = headJoint(td).add(look(td).multiply(HEAD_LEN))

    override fun tick(world: ClientWorld) {
        dirs.add(look(1f))
        mouths.add(mouth(1f))
        if (age !in BREATH_FROM until breathTo) return
        val r = random()
        val f = look(1f)
        val m = mouth(1f)
        repeat((5 * particlesAllowed()).toInt()) {
            val spread = Vec3d((r.nextDouble() - 0.5) * 0.3, (r.nextDouble() - 0.5) * 0.2, (r.nextDouble() - 0.5) * 0.3)
            val v = f.add(spread).normalize().multiply(0.55 + r.nextDouble() * 0.5)
            world.addParticleClient(if (it % 2 == 0) ParticleTypes.SNOWFLAKE else ParticleTypes.WHITE_ASH, m.x, m.y, m.z, v.x, v.y, v.z)
        }
    }

    override fun render(d: VfxDraw, matrices: MatrixStack, t: Float) {
        if (caster.isRemoved) return
        val td = d.tickDelta
        val appear = (t / APPEAR).coerceIn(0f, 1f)
        val vanish = ((life - t) / 8f).coerceIn(0f, 1f)
        val headAlpha = appear * vanish
        val jaw = when {
            t < BREATH_FROM - 2 -> 0f
            t < BREATH_FROM + 3 -> ((t - (BREATH_FROM - 2)) / 5f).coerceIn(0f, 1f)
            t < breathTo -> 1f
            else -> (1f - (t - breathTo) / 5f).coerceIn(0f, 1f)
        }
        renderHead(d, matrices, td, headAlpha, jaw)
        renderBreath(d, t, td)
    }

    // --- Geisterkopf ---------------------------------------------------------------------------------------------------

    private fun renderHead(d: VfxDraw, matrices: MatrixStack, td: Float, alpha: Float, jaw: Float) {
        if (alpha <= 0.02f) return
        val model = model() ?: return
        val f = look(td)
        val up = Vec3d(0.0, 1.0, 0.0)
        val hu = up.subtract(f.multiply(f.dotProduct(up))).let { if (it.lengthSquared() < 1e-6) Vec3d(0.0, 0.0, 1.0) else it.normalize() }
        val hl = f.negate().crossProduct(hu)
        val y = hu.negate()
        val z = f.negate()
        // Seit 1.8.0 (Crix: Atem und Drachenkopf schöner): Der Kopf taucht aus einem Runentor hinter dem Wirker auf,
        // schiebt sich vor und zuckt beim Ausstoß; drei Halsglieder folgen ihm aus dem Tor, ein Lichtsaum umgibt ihn.
        val now = (MinecraftClient.getInstance().world?.time ?: 0L).toDouble()
        val emerge = (1f - alpha).toDouble()
        val recoil = if (jaw > 0.9f) 0.18 * sin(now * 1.7) else 0.0
        val joint = headJoint(td).subtract(f.multiply(emerge * 1.4 - recoil))
        val cam = d.cam
        val portal = joint.subtract(f.multiply(2.2))
        d.disc(VfxLayers.RUNE_ADD, portal, hl, hu, 1.6 + 0.4 * alpha, withAlpha(0xFF9FD8FF.toInt(), 0.7f * alpha), now * 0.04)
        d.billboard(VfxLayers.GLOW_ADD, portal, 3.2, withAlpha(0xFFBFE6FF.toInt(), 0.3f * alpha))
        renderNeck(d, matrices, joint, portal, alpha)
        matrices.push()
        matrices.translate(joint.x - cam.x, joint.y - cam.y, joint.z - cam.z)
        m3.set(hl.x.toFloat(), hl.y.toFloat(), hl.z.toFloat(), y.x.toFloat(), y.y.toFloat(), y.z.toFloat(), z.x.toFloat(), z.y.toFloat(), z.z.toFloat())
        matrices.multiply(rot.setFromNormalized(m3))
        // Leichtes Atmen und Aufbäumen beim Ausstoß.
        val k = NivarisShape.SCALE * HEAD_SCALE * (0.92f + 0.08f * alpha)
        matrices.scale(k, k, k)
        model.openJaw(jaw * 0.85f)
        val consumers = d.consumers
        val ghost = consumers.getBuffer(RenderLayers.eyes(TEXTURE))
        val col = withAlpha(lerpColor(0xFF9FD8FF.toInt(), 0xFFFFFFFF.toInt(), 0.35f), (if (firstPerson()) 0.4f else 0.75f) * alpha)
        model.head.render(matrices, ghost, LightmapTextureManager.MAX_LIGHT_COORDINATE, OverlayTexture.DEFAULT_UV, col)
        // Lichtsaum: derselbe Kopf etwas größer, schwach und blau
        matrices.push()
        matrices.scale(1.07f, 1.07f, 1.07f)
        model.head.render(matrices, ghost, LightmapTextureManager.MAX_LIGHT_COORDINATE, OverlayTexture.DEFAULT_UV, withAlpha(0xFF6FB0FF.toInt(), 0.22f * alpha))
        matrices.pop()
        val eyes = consumers.getBuffer(RenderLayers.eyes(EYES))
        model.head.render(matrices, eyes, LightmapTextureManager.MAX_LIGHT_COORDINATE, OverlayTexture.DEFAULT_UV, withAlpha(0xFFFFFFFF.toInt(), alpha))
        model.openJaw(0f)
        matrices.pop()
        // Lichtschein um den Kopf und Funken an den Hornspitzen.
        d.billboard(VfxLayers.GLOW_ADD, joint.add(f.multiply(HEAD_LEN * 0.5)), 2.6, withAlpha(0xFFBFE6FF.toInt(), 0.35f * alpha))
    }

    /** Drei Halsglieder vom Runentor bis zum Kopf (Geisterlicht wie der Kopf). */
    private fun renderNeck(d: VfxDraw, matrices: MatrixStack, joint: Vec3d, portal: Vec3d, alpha: Float) {
        val model = model() ?: return
        val buf = d.consumers.getBuffer(RenderLayers.eyes(TEXTURE))
        val col = withAlpha(0xFFBFE6FF.toInt(), (if (firstPerson()) 0.25f else 0.6f) * alpha)
        val cam = d.cam
        val n = 3
        for (k in 0 until n) {
            val a = joint.lerp(portal, k / n.toDouble()).add(0.0, -0.15 * k, 0.0)
            val b = joint.lerp(portal, (k + 1) / n.toDouble()).add(0.0, -0.15 * (k + 1), 0.0)
            val dir = b.subtract(a)
            val len = dir.length()
            if (len < 1e-4) continue
            val bz = dir.multiply(1.0 / len)
            var u = UP.subtract(bz.multiply(UP.dotProduct(bz)))
            u = if (u.lengthSquared() < 1e-6) X else u.normalize()
            val l = bz.crossProduct(u)
            matrices.push()
            matrices.translate(a.x - cam.x, a.y - cam.y, a.z - cam.z)
            m3.set(l.x.toFloat(), l.y.toFloat(), l.z.toFloat(), (-u.x).toFloat(), (-u.y).toFloat(), (-u.z).toFloat(), bz.x.toFloat(), bz.y.toFloat(), bz.z.toFloat())
            matrices.multiply(rot.setFromNormalized(m3))
            val g = NivarisShape.SCALE * HEAD_SCALE * NivarisShape.taper(k) * 0.85f
            matrices.scale(g, g, NivarisShape.SCALE * HEAD_SCALE * (len / NivarisShape.SEGMENT).toFloat() * 1.05f)
            model.glied(k).render(matrices, buf, LightmapTextureManager.MAX_LIGHT_COORDINATE, OverlayTexture.DEFAULT_UV, col)
            matrices.pop()
        }
    }

    // --- Atem ----------------------------------------------------------------------------------------------------------

    private fun dirAt(i: Int, td: Float): Vec3d = dirs.getOrNull(i) ?: look(td)
    private fun mouthAt(i: Int, td: Float): Vec3d = mouths.getOrNull(i) ?: mouth(td)

    private fun renderBreath(d: VfxDraw, t: Float, td: Float) {
        val speed = range / FLIGHT * 1.05
        val fx = AriusClientConfig.fx.coerceIn(0.25f, 1f)
        // Schwaden: alle 0,5 Ticks eine, jede fliegt FLIGHT Ticks in die Richtung ihres Ausstoßes und bläht sich auf.
        val newest = kotlin.math.floor(t / STEP).toInt()
        val count = (FLIGHT / STEP).toInt()
        val floorY = caster.y - 0.2
        for (j in 0 until count) {
            val n = newest - j
            val birth = n * STEP
            if (birth < BREATH_FROM || birth >= breathTo) continue
            val a = t - birth
            if (a < 0f || a > FLIGHT) continue
            val bi = birth.toInt().coerceAtLeast(0)
            val dir = dirAt(bi, td)
            val m0 = mouthAt(bi, td)
            val side = dir.crossProduct(UP).let { if (it.lengthSquared() < 1e-4) X else it.normalize() }
            val upv = side.crossProduct(dir).normalize()
            val seed = n * 1.618
            val dist = a * speed * (1.0 - a / FLIGHT * 0.25)
            val curl = a / FLIGHT
            var p = m0.add(dir.multiply(dist))
                .add(side.multiply(sin(seed * 3.1 + a * 0.45) * curl * 1.6))
                .add(upv.multiply(cos(seed * 2.3 + a * 0.38) * curl * 1.1))
                .add(0.0, -0.012 * a * a, 0.0)
            val size = 0.45 + a * 0.2 * (0.8 + 0.4 * ((seed * 7.0) % 1.0))
            if (p.y < floorY + size * 0.3) p = Vec3d(p.x, floorY + size * 0.3, p.z)
            val life01 = 1f - (a / FLIGHT).toFloat()
            val fade = min(1f, a / 1.5f) * life01
            // Rollende Schwade (Alpha), darüber ein heller Kern solange sie jung ist.
            val tone = lerpColor(0xFFDDF2FF.toInt(), 0xFFFFFFFF.toInt(), life01)
            d.billboard(VfxLayers.CLOUD_ALPHA, p, size, withAlpha(tone, 0.55f * fade * fx), (seed * 40.0 + a * 6.0) % 360.0)
            if (a < 7f) d.billboard(VfxLayers.GLOW_ADD, p, size * 0.8, withAlpha(0xFFB8E8FF.toInt(), 0.5f * (1f - a / 7f) * fade))
            if (n % 3 == 0) {
                val g = p.add(side.multiply(sin(seed * 5.0) * size * 0.6)).add(upv.multiply(cos(seed * 4.0) * size * 0.5))
                d.billboard(VfxLayers.FLARE_ADD, g, 0.25 + 0.1 * sin(t * 0.8 + seed), withAlpha(0xFFFFFFFF.toInt(), 0.8f * fade))
            }
        }
        // Gleißender Kern und Strahlband direkt am Maul, solange der Atem fließt.
        if (t >= BREATH_FROM && t < breathTo + 2) {
            val k = (min(1f, (t - BREATH_FROM) / 3f) * ((breathTo + 2 - t) / 4f).coerceIn(0f, 1f))
            val f = look(td)
            val m = mouth(td)
            d.billboard(VfxLayers.FLARE_ADD, m, 1.4 + 0.2 * sin(t * 1.3), withAlpha(0xFFFFFFFF.toInt(), 0.95f * k))
            d.billboard(VfxLayers.GLOW_ADD, m.add(f.multiply(1.2)), 2.4, withAlpha(0xFF9FD8FF.toInt(), 0.5f * k))
            d.band(VfxLayers.FLOW_ADD, m, m.add(f.multiply(range * 0.8)), d.camUp, 0.9, withAlpha(0xFFFFFFFF.toInt(), 0.55f * k), withAlpha(0xFFB8E8FF.toInt(), 0f), uScale = 0.25f, uOffset = -t * 0.12f)
            // Seit 1.8.0: zwei Frostspiralen winden sich um den Strahl, Strahlenkranz am Maul, Eissplitter fliegen mit.
            val side = f.crossProduct(UP).let { if (it.lengthSquared() < 1e-4) X else it.normalize() }
            val upv = side.crossProduct(f).normalize()
            for (s in 0 until 2) {
                val path = ArrayList<Vec3d>(24)
                for (j in 0 until 24) {
                    val q = j / 23.0
                    val rr = 0.3 + q * range * 0.18
                    val ang = q * 9.0 - t * 0.5 + s * kotlin.math.PI
                    path.add(m.add(f.multiply(q * range * 0.85)).add(side.multiply(cos(ang) * rr)).add(upv.multiply(sin(ang) * rr)))
                }
                d.ribbon(VfxLayers.GLOW_ADD, path, { q -> 0.08 + 0.1 * q }, { q -> withAlpha(0xFFE6F6FF.toInt(), 0.7f * k * (1f - q)) }, uScale = 1f, uOffset = 0f)
            }
            d.billboard(VfxLayers.RAYS_ADD, m, 3.2, withAlpha(0xFFBFE6FF.toInt(), 0.45f * k), t * 2.0)
            for (j in 0 until 8) {
                val q = ((t * 0.07 + j / 8.0) % 1.0)
                val p = m.add(f.multiply(q * range * 0.9)).add(side.multiply(sin(j * 2.3 + t * 0.2) * q * 2.0)).add(upv.multiply(cos(j * 1.7) * q * 1.4))
                d.box(VfxLayers.SHARD_ALPHA, p, side.multiply(0.14), upv.multiply(0.22), f.multiply(0.14), withAlpha(0xFFDDF2FF.toInt(), 0.8f * k * (1f - q.toFloat())))
            }
        }
    }

    companion object {
        private const val APPEAR = 5f
        private const val BREATH_FROM = 6
        private const val BREATH_TAIL = 8
        private const val FLIGHT = 18.0
        private const val STEP = 0.5f
        private const val HEAD_SCALE = 0.8f
        private val HEAD_LEN = 15.0 / 16.0 * NivarisShape.SCALE * HEAD_SCALE * 0.75
        internal val TEXTURE = HhaArius.id("textures/entity/nivaris.png")
        private val EYES = HhaArius.id("textures/entity/nivaris_glow_eyes.png")
        private val m3 = Matrix3f()
        private val rot = Quaternionf()
        private var cached: NivarisModel? = null

        /** Gesamtdauer für eine Atemdauer in Ticks (Ein- und Ausblenden dazu). */
        fun lifeFor(breathTicks: Int): Int = BREATH_FROM + breathTicks + BREATH_TAIL

        internal fun model(): NivarisModel? {
            cached?.let { return it }
            val part = runCatching { MinecraftClient.getInstance().loadedEntityModels.getModelPart(NivarisRenderers.LAYER) }.getOrNull() ?: return null
            return NivarisModel(part).also { cached = it }
        }

        /** Nach einem Ressourcen-Neuladen neu holen. */
        fun reset() {
            cached = null
        }
    }
}
