package dev.henny.hha_arius.gear.client.art

import dev.henny.hha_arius.HhaArius
import dev.henny.hha_arius.client.fx.AriusClientConfig
import dev.henny.hha_arius.client.model.gauntlets.GauntletsModelData
import dev.henny.hha_arius.gear.art.Anim
import dev.henny.hha_arius.gear.art.ArtTimings
import dev.henny.hha_arius.gear.art.GauntletMode
import dev.henny.hha_arius.gear.art.GauntletsItem
import dev.henny.hha_arius.gear.art.School
import net.minecraft.client.model.ModelPart
import net.minecraft.client.render.OverlayTexture
import net.minecraft.client.render.RenderLayer
import net.minecraft.client.render.RenderLayers
import net.minecraft.client.render.command.OrderedRenderCommandQueue
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.item.ItemStack
import net.minecraft.util.Identifier
import net.minecraft.util.math.MathHelper
import kotlin.math.sin

/**
 * Was die Handschuhe gerade zeigen: Faust oder offene Hand, Modus, Stärke der Leuchtmaske. Gemeinsam für erste und
 * dritte Person, damit beide Ansichten immer übereinstimmen.
 */
internal object GauntletLook {

    /** Angezeigter Modus; beim Moduswechsel schaltet er genau in der Mitte um. */
    fun displayMode(stack: ItemStack, anim: ArtAnims.State?, tickDelta: Float): GauntletMode {
        if (anim != null && anim.anim == Anim.MODE_SWITCH) {
            val newMode = GauntletMode.of(anim.param)
            val old = if (newMode == GauntletMode.MAGIC) GauntletMode.MELEE else GauntletMode.MAGIC
            return if (anim.age(tickDelta) < ArtTimings.MODE_SWITCH / 2f) old else newMode
        }
        return GauntletsItem.mode(stack)
    }

    /** Offene Hand? Magiemodus immer; im Nahkampf nur für Handflächen-Parade und Konterstoß. */
    fun open(stack: ItemStack, anim: ArtAnims.State?, tickDelta: Float): Boolean {
        when (anim?.anim) {
            Anim.PARRY, Anim.COUNTER, Anim.CAST_TAP, Anim.CAST_CHARGE, Anim.CAST_RELEASE, Anim.PLACE_AIM,
            Anim.SHIELD, Anim.GLIDE, Anim.WHITE_HOLE -> return true
            else -> {}
        }
        return displayMode(stack, anim, tickDelta) == GauntletMode.MAGIC
    }

    /** Leuchtmaske 0..1: nur im Magiemodus; stärker beim Wirken, weich beim Umschalten. */
    fun glow(stack: ItemStack, anim: ArtAnims.State?, tickDelta: Float, time: Float): Float {
        val a = anim?.age(tickDelta) ?: 0f
        val base = 0.5f + 0.06f * sin(time * 0.06f)
        val k = when (anim?.anim) {
            Anim.MODE_SWITCH -> {
                val u = (a / ArtTimings.MODE_SWITCH).coerceIn(0f, 1f)
                val toMagic = anim.param == 1
                val f = MathHelper.clamp((u - 0.35f) / 0.3f, 0f, 1f)
                return (if (toMagic) f else 1f - f) * base * scale()
            }
            Anim.CAST_CHARGE -> 0.75f + 0.25f * dev.henny.hha_arius.gear.art.ArtParam.castCharge(anim.param, a)
            Anim.SHIELD, Anim.PLACE_AIM, Anim.WHITE_HOLE, Anim.GLIDE -> 0.85f
            Anim.CAST_TAP -> 0.55f + 0.45f * (1f - (a / ArtTimings.CAST_TAP).coerceIn(0f, 1f))
            Anim.CAST_RELEASE -> 0.55f + 0.45f * (1f - (a / ArtTimings.CAST_RELEASE).coerceIn(0f, 1f))
            else -> if (displayMode(stack, anim, tickDelta) == GauntletMode.MAGIC) base else 0f
        }
        if (displayMode(stack, anim, tickDelta) != GauntletMode.MAGIC && anim?.anim != Anim.WHITE_HOLE) return 0f
        return k * scale()
    }

    /** Die Leuchtmaske zeigt den Modus an: nie ganz aus, aber mit der Effektstärke gedämpft. */
    private fun scale(): Float = 0.45f + 0.55f * AriusClientConfig.fx.coerceIn(0f, 1f)

    fun schoolColor(school: School): Int = when (school) {
        School.WIND -> 0xFFC9D6E8.toInt()
        School.ICE -> 0xFFB8E8FF.toInt()
        School.YANG -> 0xFFFFE3A0.toInt()
    }

    fun schoolCore(school: School): Int = when (school) {
        School.WIND -> 0xFFF1F5FB.toInt()
        School.ICE -> 0xFFEAF8FF.toInt()
        School.YANG -> 0xFFFFF8E6.toInt()
    }
}

/**
 * Die Modellteile der Handschuhe (aus [GauntletsModelData], Drehpunkt 0, Knochenraum des Spielerarms). Die Teile
 * werden nie verändert – Befehle laufen verzögert, darum kommt jede Lage allein über die MatrixStack.
 */
internal object GauntletParts {
    private val TEX: Identifier = HhaArius.id("textures/entity/gauntlets.png")
    private val GLOW_TEX: Identifier = HhaArius.id("textures/entity/gauntlets_glow.png")
    private const val FULL_BRIGHT = 0xF000F0

    private var root: ModelPart? = null
    private lateinit var rBase: ModelPart
    private lateinit var rFist: ModelPart
    private lateinit var rOpen: ModelPart
    private lateinit var lBase: ModelPart
    private lateinit var lFist: ModelPart
    private lateinit var lOpen: ModelPart

    /** Fingerglieder je Seite: [finger][glied], Daumen [2]. */
    private lateinit var rFingers: Array<Array<ModelPart>>
    private lateinit var lFingers: Array<Array<ModelPart>>
    private lateinit var rThumb: Array<ModelPart>
    private lateinit var lThumb: Array<ModelPart>

    /** Gelenke (Armraum px, rechter Arm): je Finger y der drei Glieder; x −1,25, z = Fingermitte (wie gauntlets_model.py). */
    private val JOINT_Y = arrayOf(floatArrayOf(10f, 11f, 12f), floatArrayOf(10f, 11f, 12f), floatArrayOf(10f, 11f, 11.5f), floatArrayOf(10f, 10.5f, 11f))
    private val FINGER_Z = floatArrayOf(-1.5f, -0.5f, 0.5f, 1.5f)

    private val layer: RenderLayer by lazy { RenderLayers.entityCutoutNoCull(TEX) }
    private val glowLayer: RenderLayer by lazy { RenderLayers.eyes(GLOW_TEX) }

    /** Modell vorab bauen (siehe [dev.henny.hha_arius.client.Preload]). */
    fun warm() = ensure()

    private fun ensure() {
        if (root != null) return
        val r = GauntletsModelData.gauntlets().createModel()
        rBase = r.getChild("r_base"); rFist = r.getChild("r_fist"); rOpen = r.getChild("r_open")
        lBase = r.getChild("l_base"); lFist = r.getChild("l_fist"); lOpen = r.getChild("l_open")
        rFingers = Array(4) { i -> Array(3) { k -> r.getChild("r_f${i}_$k") } }
        lFingers = Array(4) { i -> Array(3) { k -> r.getChild("l_f${i}_$k") } }
        rThumb = Array(2) { k -> r.getChild("r_t$k") }
        lThumb = Array(2) { k -> r.getChild("l_t$k") }
        root = r
    }

    /**
     * Zeichnet einen Handschuh. [matrices] steht im Knochenraum des Spielerarms (wie ModelPart.applyTransform des
     * Arms). Schmale Arme (3 statt 4 breit) werden quer gestaucht und mittig gesetzt.
     */
    fun submit(
        queue: OrderedRenderCommandQueue, matrices: MatrixStack, right: Boolean, slim: Boolean, open: Boolean,
        glow: Float, light: Int, overlay: Int = OverlayTexture.DEFAULT_UV,
        anim: ArtAnims.State? = null, td: Float = 0f, time: Float = 0f, canonRight: Boolean = right,
    ) {
        ensure()
        matrices.push()
        if (slim) {
            matrices.translate((if (right) 0.25f else -0.25f) / 16f, 0f, 0f)
            matrices.scale(0.75f, 1f, 1f)
        }
        val base = if (right) rBase else lBase
        val hand = if (right) (if (open) rOpen else rFist) else (if (open) lOpen else lFist)
        queue.submitModelPart(base, matrices, layer, light, overlay, null)
        queue.submitModelPart(hand, matrices, layer, light, overlay, null)
        val tint = ((MathHelper.clamp(glow, 0f, 1f) * 255f).toInt() shl 24) or 0xFFFFFF
        if (glow > 0.01f) {
            queue.submitModelPart(base, matrices, glowLayer, FULL_BRIGHT, OverlayTexture.DEFAULT_UV, null, tint, null)
            queue.submitModelPart(hand, matrices, glowLayer, FULL_BRIGHT, OverlayTexture.DEFAULT_UV, null, tint, null)
        }
        if (open) fingers(queue, matrices, right, glow, tint, light, overlay, anim, td, time, canonRight)
        matrices.pop()
    }

    private val pose = FloatArray(6)
    private val rot = org.joml.Quaternionf()

    /** Dreht [m] um das Gelenk [jx], [jy], [jz] (px): Krümmung um z, Spreizung um x. */
    private fun hinge(m: MatrixStack, jx: Float, jy: Float, jz: Float, curl: Float, splay: Float) {
        m.translate(jx / 16f, jy / 16f, jz / 16f)
        m.multiply(rot.identity().rotateZ(curl).rotateX(splay))
        m.translate(-jx / 16f, -jy / 16f, -jz / 16f)
    }

    /**
     * Bewegliche Finger (seit 1.6.4): Jedes Glied dreht sich um sein Gelenk. Krümmung zur Handfläche hin (+x am
     * rechten Arm), Spreizung entlang z. Die Haltung kommt aus [FingerAnim].
     */
    private fun fingers(
        queue: OrderedRenderCommandQueue, m: MatrixStack, right: Boolean, glow: Float, tint: Int, light: Int, overlay: Int,
        anim: ArtAnims.State?, td: Float, time: Float, canonRight: Boolean,
    ) {
        FingerAnim.pose(anim, td, time, canonRight, pose)
        val sx = if (right) 1f else -1f
        val parts = if (right) rFingers else lFingers
        fun draw(p: ModelPart) {
            queue.submitModelPart(p, m, layer, light, overlay, null)
            if (glow > 0.01f) queue.submitModelPart(p, m, glowLayer, FULL_BRIGHT, OverlayTexture.DEFAULT_UV, null, tint, null)
        }
        for (i in 0 until 4) {
            val curl = pose[i]
            val splay = pose[4] * (FINGER_Z[i] / 1.5f)
            m.push()
            val jy = JOINT_Y[i]
            hinge(m, -1.25f * sx, jy[0], FINGER_Z[i], -sx * curl * 0.85f, splay)
            draw(parts[i][0])
            hinge(m, -1.25f * sx, jy[1], FINGER_Z[i], -sx * curl * 1.1f, 0f)
            draw(parts[i][1])
            hinge(m, -1.25f * sx, jy[2], FINGER_Z[i], -sx * curl * 0.8f, 0f)
            draw(parts[i][2])
            m.pop()
        }
        // Daumen: klappt über die Handfläche (um die y-Achse am Daumenballen), Glied zwei krümmt mit.
        val th = pose[5]
        val thumb = if (right) rThumb else lThumb
        m.push()
        m.translate(-1.25f * sx / 16f, 10f / 16f, -3f / 16f)
        m.multiply(rot.identity().rotateZ(-sx * th * 0.7f).rotateX(th * 0.4f))
        m.translate(1.25f * sx / 16f, -10f / 16f, 3f / 16f)
        draw(thumb[0])
        hinge(m, -1.25f * sx, 11f, -3f, -sx * th * 0.8f, 0f)
        draw(thumb[1])
        m.pop()
    }

    /** Mitte der Handfläche im Armraum (px), etwas vor der Fläche – dort sitzt die Energie beim Zaubern. */
    fun palmPoint(right: Boolean, slim: Boolean, out: org.joml.Vector3f): org.joml.Vector3f {
        val inner = if (slim) 1.1f else 1.15f
        return if (right) out.set(inner + 0.9f, 10.2f, -0.6f) else out.set(-inner - 0.9f, 10.2f, -0.6f)
    }

    /** Fingerspitzen (offene Hand), Armraum px. */
    fun tipPoint(right: Boolean, slim: Boolean, out: org.joml.Vector3f): org.joml.Vector3f {
        val cx = if (slim) 0.69f else 1.25f
        return if (right) out.set(-cx, 13.2f, -0.5f) else out.set(cx, 13.2f, -0.5f)
    }
}
