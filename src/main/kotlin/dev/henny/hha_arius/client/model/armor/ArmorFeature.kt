package dev.henny.hha_arius.client.model.armor

import dev.henny.hha_arius.HhaArius
import dev.henny.hha_arius.client.fx.AriusClientConfig
import dev.henny.hha_arius.gear.art.GauntletsItem
import net.minecraft.client.gl.RenderPipelines
import net.minecraft.client.model.ModelPart
import net.minecraft.client.render.LayeringTransform
import net.minecraft.client.render.OverlayTexture
import net.minecraft.client.render.RenderLayer
import net.minecraft.client.render.RenderLayers
import net.minecraft.client.render.RenderSetup
import net.minecraft.client.render.command.OrderedRenderCommandQueue
import net.minecraft.client.render.entity.feature.FeatureRenderer
import net.minecraft.client.render.entity.feature.FeatureRendererContext
import net.minecraft.client.render.entity.model.BipedEntityModel
import net.minecraft.client.render.entity.state.BipedEntityRenderState
import net.minecraft.client.render.entity.state.PlayerEntityRenderState
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.entity.player.PlayerSkinType
import net.minecraft.item.ItemStack
import net.minecraft.util.Identifier
import net.minecraft.util.math.MathHelper
import org.joml.Quaternionf
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Zeichnet die getragenen Teile der Drachenherz-Wacht an den Knochen des Kontextmodells (Kopf, Rumpf, Arme, Beine).
 *
 * * Teile kommen aus [ArmorModelData] (generiert), Drehpunkt 0 im Knochenraum des Bipeds; die Teile selbst werden nie
 *   verändert (Befehle laufen verzögert), jede Lage kommt über die MatrixStack.
 * * Hängende Teile (Tassetten, Schwingenplatten) drehen sich um ihr Gelenk: Beinfolge + Federn je Entity
 *   ([ArmorMotion]; Spieler und Mannequins, andere Träger nur mit der Beinfolge).
 * * Ebenen: Grundtextur über `armorCutoutNoCull` (mit Rüstungs-Layering), Verzauberungsglanz wie Vanilla-Rüstung,
 *   Leuchtmaske über eine eigene Ebene = eyes-Pipeline + dasselbe Layering. `RenderLayers.eyes()` allein läge hinter der
 *   Rüstung und fiele durch den Tiefentest (siehe HHA_void/docs/ARMOR_NOTES.md).
 * * Schmale Arme: quer auf 3/4 gestaucht und mittig gesetzt (wie die Handschuhe). Unsichtbare Träger: nichts.
 * * Unterarmschiene nur ohne Handschuhe in der Haupthand (die Handschuhe bringen ihre eigene Schiene mit).
 */
internal class ArmorFeature(ctx: FeatureRendererContext<BipedEntityRenderState, BipedEntityModel<BipedEntityRenderState>>) :
    FeatureRenderer<BipedEntityRenderState, BipedEntityModel<BipedEntityRenderState>>(ctx) {

    override fun render(
        matrices: MatrixStack, queue: OrderedRenderCommandQueue, light: Int, state: BipedEntityRenderState,
        limbAngle: Float, limbDistance: Float,
    ) {
        val helm = ArmorRender.isHelmet(state.equippedHeadStack)
        val chest = ArmorRender.isChest(state.equippedChestStack)
        val legs = ArmorRender.isLegs(state.equippedLegsStack)
        val boots = ArmorRender.isBoots(state.equippedFeetStack)
        if (!helm && !chest && !legs && !boots) return
        if (state.invisible) return
        val player = state as? PlayerEntityRenderState
        if (player != null && player.spectator) return

        val model = contextModel
        val slim = player != null && player.skinTextures.model() == PlayerSkinType.SLIM
        val motion = if (player != null) ArmorMotion.update(player.id, state, model) else null
        val lineGlow = ArmorParts.lineGlow(state.age)
        val outline = state.outlineColor
        val p = ArmorParts.get()

        fun onBone(bone: ModelPart, arm: Int, draw: (MatrixStack) -> Unit) {
            if (!bone.visible) return
            matrices.push()
            model.rootPart.applyTransform(matrices)
            bone.applyTransform(matrices)
            if (slim && arm != 0) {
                matrices.translate((if (arm > 0) 0.25f else -0.25f) / 16f, 0f, 0f)
                matrices.scale(0.75f, 1f, 1f)
            }
            draw(matrices)
            matrices.pop()
        }

        if (helm) {
            val s = state.equippedHeadStack
            onBone(model.head, 0) { ArmorParts.submit(queue, it, p.helm, light, s, outline, lineGlow) }
        }
        if (chest || legs) {
            val cs = state.equippedChestStack
            val ls = state.equippedLegsStack
            onBone(model.body, 0) { m ->
                if (chest) {
                    ArmorParts.submit(queue, m, p.chestBody, light, cs, outline, lineGlow)
                    ArmorParts.submit(queue, m, p.heart, light, cs, outline, ArmorParts.heartGlow(state.age))
                }
                if (legs) {
                    ArmorParts.submit(queue, m, p.legsBody, light, ls, outline, lineGlow)
                    // Beinwinkel relativ zum Rumpf (beim Schleichen ist der Rumpf vorgeneigt)
                    val rl = model.rightLeg.pitch - model.body.pitch
                    val ll = model.leftLeg.pitch - model.body.pitch
                    val fr = tassetFront(rl) + (motion?.tasset(0, rl) ?: 0f)
                    val fl = tassetFront(ll) + (motion?.tasset(1, ll) ?: 0f)
                    val bk = tassetBack(rl, ll) + (motion?.tasset(2, max(rl, ll)) ?: 0f)
                    ArmorParts.hinged(queue, m, p.tassetFR, fr, light, ls, outline, lineGlow)
                    ArmorParts.hinged(queue, m, p.tassetFL, fl, light, ls, outline, lineGlow)
                    ArmorParts.hinged(queue, m, p.tassetB, bk, light, ls, outline, lineGlow)
                }
            }
        }
        if (chest) {
            val cs = state.equippedChestStack
            val fore = !GauntletsItem.isGauntlets(state.mainHandItemStack)
            for (right in booleanArrayOf(true, false)) {
                val bone = if (right) model.rightArm else model.leftArm
                val i = if (right) 0 else 1
                onBone(bone, if (right) 1 else -1) { m ->
                    ArmorParts.submit(queue, m, if (right) p.armR else p.armL, light, cs, outline, lineGlow)
                    ArmorParts.hinged(queue, m, if (right) p.wingR else p.wingL, motion?.wing(i) ?: 0f, light, cs, outline, lineGlow)
                    if (fore) ArmorParts.submit(queue, m, if (right) p.foreR else p.foreL, light, cs, outline, lineGlow)
                }
            }
        }
        if (legs || boots) {
            for (right in booleanArrayOf(true, false)) {
                val bone = if (right) model.rightLeg else model.leftLeg
                onBone(bone, 0) { m ->
                    if (legs) ArmorParts.submit(queue, m, if (right) p.legR else p.legL, light, state.equippedLegsStack, outline, lineGlow)
                    if (boots) ArmorParts.submit(queue, m, if (right) p.bootR else p.bootL, light, state.equippedFeetStack, outline, lineGlow)
                }
            }
        }
    }

    companion object {
        // Beinfolge der Tassetten; dieselben Werte stehen in tools/assets/armor_preview.py (nachgerechnet: bis 1,2 rad
        // Beinschwung sticht der Oberschenkel nicht durch die vorderen Tassetten).
        const val TASSET_FOLLOW = 1.08f
        const val TASSET_FOLLOW_BACK = 1.08f
        const val TASSET_REST_FRONT = -0.04f
        const val TASSET_REST_BACK = 0.05f

        /** Vordere Tassette folgt ihrem Bein nach vorn (negativer Pitch), bleibt beim Zurückschwingen hängen. */
        fun tassetFront(legPitch: Float): Float = min(0f, legPitch) * TASSET_FOLLOW + TASSET_REST_FRONT

        /** Hintere Tassette folgt dem weiter zurückgesetzten Bein. */
        fun tassetBack(right: Float, left: Float): Float = max(0f, max(right, left)) * TASSET_FOLLOW_BACK + TASSET_REST_BACK
    }
}

/** Modellteile, Ebenen und Leuchten. */
internal object ArmorParts {
    private val TEX: Identifier = HhaArius.id("textures/entity/eirholm_armor.png")
    private val GLOW_TEX: Identifier = HhaArius.id("textures/entity/eirholm_armor_glow.png")
    private const val FULL_BRIGHT = 0xF000F0

    private val layer: RenderLayer by lazy { RenderLayers.armorCutoutNoCull(TEX) }
    private val glowLayer: RenderLayer by lazy {
        RenderLayer.of(
            "hha_arius_armor_glow",
            RenderSetup.builder(RenderPipelines.ENTITY_EYES)
                .texture("Sampler0", GLOW_TEX)
                .layeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING)
                .translucent()
                .build(),
        )
    }

    class Parts(root: ModelPart) {
        val helm: ModelPart = root.getChild("helm_head")
        val chestBody: ModelPart = root.getChild("chest_body")
        val heart: ModelPart = root.getChild("chest_heart")
        val legsBody: ModelPart = root.getChild("legs_body")
        val tassetFR: ModelPart = root.getChild("legs_tasset_fr")
        val tassetFL: ModelPart = root.getChild("legs_tasset_fl")
        val tassetB: ModelPart = root.getChild("legs_tasset_b")
        val armR: ModelPart = root.getChild("chest_arm_r")
        val armL: ModelPart = root.getChild("chest_arm_l")
        val wingR: ModelPart = root.getChild("chest_wing_r")
        val wingL: ModelPart = root.getChild("chest_wing_l")
        val foreR: ModelPart = root.getChild("chest_forearm_r")
        val foreL: ModelPart = root.getChild("chest_forearm_l")
        val legR: ModelPart = root.getChild("legs_leg_r")
        val legL: ModelPart = root.getChild("legs_leg_l")
        val bootR: ModelPart = root.getChild("boots_leg_r")
        val bootL: ModelPart = root.getChild("boots_leg_l")
    }

    private var parts: Parts? = null

    fun get(): Parts = parts ?: Parts(ArmorModelData.armor().createModel()).also { parts = it }

    /** Leuchtlinien an den Schuppenkanten: ruhig, kaum merklich schimmernd; mit der Effektstärke gedämpft. */
    fun lineGlow(age: Float): Float = (0.72f + 0.06f * sin(age * 0.05f)) * scale()

    /** Herz-Juwel: leiser Atem, etwa 4 s je Zug (langsam ein, etwas schneller aus). */
    fun heartGlow(age: Float): Float {
        val u = (age % 80f) / 80f
        val breath = if (u < 0.6f) sin(u / 0.6f * Math.PI.toFloat() / 2f) else cos((u - 0.6f) / 0.4f * Math.PI.toFloat() / 2f)
        return (0.62f + 0.38f * breath * breath) * (0.75f + 0.25f * scale())
    }

    private fun scale(): Float = 0.55f + 0.45f * AriusClientConfig.fx.coerceIn(0f, 1f)

    fun submit(queue: OrderedRenderCommandQueue, m: MatrixStack, part: ModelPart, light: Int, stack: ItemStack, outline: Int, glow: Float) {
        queue.submitModelPart(part, m, layer, light, OverlayTexture.DEFAULT_UV, null, false, false, -1, null, outline)
        if (stack.hasGlint()) queue.submitModelPart(part, m, RenderLayers.armorEntityGlint(), light, OverlayTexture.DEFAULT_UV, null)
        if (glow > 0.01f) {
            val tint = ((MathHelper.clamp(glow, 0f, 1f) * 255f).toInt() shl 24) or 0xFFFFFF
            queue.submitModelPart(part, m, glowLayer, FULL_BRIGHT, OverlayTexture.DEFAULT_UV, null, tint, null)
        }
    }

    /** Teil um sein Gelenk (Drehpunkt des Teils) um [pitch] drehen und zeichnen. */
    fun hinged(queue: OrderedRenderCommandQueue, m: MatrixStack, part: ModelPart, pitch: Float, light: Int, stack: ItemStack, outline: Int, glow: Float) {
        if (abs(pitch) < 1e-4f) {
            submit(queue, m, part, light, stack, outline, glow)
            return
        }
        m.push()
        m.translate(part.originX / 16f, part.originY / 16f, part.originZ / 16f)
        m.multiply(Quaternionf().rotationX(pitch))
        m.translate(-part.originX / 16f, -part.originY / 16f, -part.originZ / 16f)
        submit(queue, m, part, light, stack, outline, glow)
        m.pop()
    }
}

/**
 * Sekundärbewegung je Entity (Spieler-ID): gedämpfte Federn für die drei Tassetten und die zwei Schwingenplatten.
 * Antrieb: Beschleunigung nach vorn (Platten bleiben zurück), Fahrtwind, senkrechte Beschleunigung (Sprung, Landung)
 * und die Winkelgeschwindigkeit der Arme. Zeitschritt aus `state.age`; ein zweites Zeichnen im selben Bild (Inventar)
 * schreitet nicht weiter.
 */
internal class ArmorMotion {
    private var age = Float.NaN
    private var x = 0.0
    private var y = 0.0
    private var z = 0.0
    private var vf = 0f
    private var vy = 0f
    private var af = 0f
    private var ay = 0f
    private var armR = 0f
    private var armL = 0f
    private var seen = 0L
    private val tx = FloatArray(3)
    private val tv = FloatArray(3)
    private val wx = FloatArray(2)
    private val wv = FloatArray(2)

    /** Federanteil einer Tassette; nie so weit nach hinten (vorn) gedrückt, dass das vorschwingende Bein durchsticht. */
    fun tasset(i: Int, legPitch: Float): Float {
        val s = tx[i]
        return if (i < 2) {
            val room = 0.3f * (1f - MathHelper.clamp(-legPitch / 0.35f, 0f, 1f))
            min(s, room)
        } else {
            val room = 0.3f * (1f - MathHelper.clamp(legPitch / 0.35f, 0f, 1f))
            max(s, -room)
        }
    }

    fun wing(i: Int): Float = wx[i]

    private fun step(state: BipedEntityRenderState, model: BipedEntityModel<*>) {
        val now = state.age
        if (age.isNaN()) {
            reset(state, model)
            return
        }
        val dt = now - age
        if (dt <= 1e-4f) return
        if (dt > 6f) {
            reset(state, model)
            return
        }
        val dx = state.x - x
        val dy = state.y - y
        val dz = state.z - z
        if (dx * dx + dy * dy + dz * dz > 9.0) {
            reset(state, model)
            return
        }
        val yaw = Math.toRadians(state.bodyYaw.toDouble())
        val fwd = ((-sin(yaw) * dx + cos(yaw) * dz) / dt).toFloat()
        val up = (dy / dt).toFloat()
        val k = min(1f, dt * 0.5f)
        af += ((fwd - vf) / dt - af) * k
        ay += ((up - vy) / dt - ay) * k
        vf = fwd
        vy = up
        val wr = (model.rightArm.pitch - armR) / dt
        val wl = (model.leftArm.pitch - armL) / dt
        armR = model.rightArm.pitch
        armL = model.leftArm.pitch
        age = now

        // Tassetten: ~1,2 Hz, schwach gedämpft. Vorwärtsbeschleunigung und Fahrtwind drücken sie nach hinten (+).
        val tForce = MathHelper.clamp(af * 6f, -0.06f, 0.06f)
        val drag = MathHelper.clamp(vf * 0.35f, -0.1f, 0.12f)
        for (i in 0 until 3) {
            val target = if (i < 2) drag else -drag * 0.5f
            spring(tx, tv, i, target, if (i < 2) tForce else tForce * 0.7f, dt, 0.142f, 0.26f, 0.45f)
        }
        // Schwingenplatten: ~2 Hz, folgen den Armen mit Verzögerung, Sprung/Landung lässt sie federn.
        val vertical = MathHelper.clamp(-ay * 6f, -0.1f, 0.1f)
        spring(wx, wv, 0, 0f, MathHelper.clamp(-wr * 0.35f, -0.12f, 0.12f) + vertical, dt, 0.39f, 0.5f, 0.28f)
        spring(wx, wv, 1, 0f, MathHelper.clamp(-wl * 0.35f, -0.12f, 0.12f) + vertical, dt, 0.39f, 0.5f, 0.28f)
    }

    private fun spring(x: FloatArray, v: FloatArray, i: Int, target: Float, force: Float, dt: Float, k: Float, c: Float, limit: Float) {
        var left = dt
        while (left > 0f) {
            val h = min(left, 0.25f)
            val a = -k * (x[i] - target) - c * v[i] + force
            v[i] += a * h
            x[i] = MathHelper.clamp(x[i] + v[i] * h, -limit, limit)
            left -= h
        }
    }

    private fun reset(state: BipedEntityRenderState, model: BipedEntityModel<*>) {
        age = state.age
        x = state.x; y = state.y; z = state.z
        vf = 0f; vy = 0f; af = 0f; ay = 0f
        armR = model.rightArm.pitch
        armL = model.leftArm.pitch
        tx.fill(0f); tv.fill(0f); wx.fill(0f); wv.fill(0f)
    }

    companion object {
        private val byId = HashMap<Int, ArmorMotion>()
        private var calls = 0

        fun update(id: Int, state: BipedEntityRenderState, model: BipedEntityModel<*>): ArmorMotion {
            val now = System.currentTimeMillis()
            if (++calls >= 600) {
                calls = 0
                byId.values.removeIf { now - it.seen > 10_000L }
            }
            val m = byId.getOrPut(id) { ArmorMotion() }
            m.seen = now
            m.step(state, model)
            return m
        }
    }
}
