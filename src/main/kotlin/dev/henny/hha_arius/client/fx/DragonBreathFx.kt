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
        val joint = headJoint(td)
        val cam = d.cam
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
        val eyes = consumers.getBuffer(RenderLayers.eyes(EYES))
        model.head.render(matrices, eyes, LightmapTextureManager.MAX_LIGHT_COORDINATE, OverlayTexture.DEFAULT_UV, withAlpha(0xFFFFFFFF.toInt(), alpha))
        model.openJaw(0f)
        matrices.pop()
        // Lichtschein um den Kopf und Funken an den Hornspitzen.
        d.billboard(VfxLayers.GLOW_ADD, joint.add(f.multiply(HEAD_LEN * 0.5)), 2.6, withAlpha(0xFFBFE6FF.toInt(), 0.35f * alpha))
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
