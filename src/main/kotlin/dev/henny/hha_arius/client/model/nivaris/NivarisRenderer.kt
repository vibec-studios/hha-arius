package dev.henny.hha_arius.client.model.nivaris

import dev.henny.hha_arius.HhaArius
import dev.henny.hha_arius.boss.AriusTimings
import dev.henny.hha_arius.boss.NivarisEntity
import net.minecraft.client.render.OverlayTexture
import net.minecraft.client.render.RenderLayers
import net.minecraft.client.render.command.OrderedRenderCommandQueue
import net.minecraft.client.render.command.RenderCommandQueue
import net.minecraft.client.render.entity.EntityRendererFactory
import net.minecraft.client.render.entity.LivingEntityRenderer
import net.minecraft.client.render.entity.feature.FeatureRenderer
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.util.Identifier
import net.minecraft.util.math.MathHelper
import java.util.WeakHashMap
import kotlin.math.sin

/**
 * Renderer der Drachin: Farbtextur (Cutout, Membranlöcher), dazu drei Leuchtschichten mit denselben UVs:
 * Augen + Hornspitzen ([NivarisEntity.eyeGlow], heller bei [NivarisEntity.lead]), Kehle + Herzkern
 * ([NivarisEntity.throatGlow]) und die Resonanzlinien in Membranen und Kamm (nur bei [NivarisEntity.resonance]).
 */
class NivarisRenderer(context: EntityRendererFactory.Context) :
    LivingEntityRenderer<NivarisEntity, NivarisRenderState, NivarisModel>(context, NivarisModel(context.getPart(NivarisRenderers.LAYER)), 3.2f) {

    private val texture = HhaArius.id("textures/entity/nivaris.png")
    private val eyesLayer = RenderLayers.eyes(HhaArius.id("textures/entity/nivaris_glow_eyes.png"))
    private val heartLayer = RenderLayers.eyes(HhaArius.id("textures/entity/nivaris_glow_heart.png"))
    private val resonanceLayer = RenderLayers.eyes(HhaArius.id("textures/entity/nivaris_resonance.png"))

    /** Weiche Überblendung Boden ↔ Flug je Entity (Flag wechselt sofort, das Modell nicht). */
    private val flyBlend = WeakHashMap<NivarisEntity, FloatArray>()

    init {
        addFeature(object : FeatureRenderer<NivarisRenderState, NivarisModel>(this) {
            override fun render(matrices: MatrixStack, queue: OrderedRenderCommandQueue, light: Int, state: NivarisRenderState, limbAngle: Float, limbDistance: Float) {
                val batch = queue.getBatchingQueue(1)
                // Augen: Grundglimmen, eyeGlow hebt; führt sie den Takt, leuchten sie heller und pulsieren ruhig.
                var eyes = 0.3f + state.eyeGlow / 100f * 0.7f
                if (state.lead) eyes = 0.85f + 0.15f * sin(state.age * 0.18f)
                submit(batch, state, matrices, eyesLayer, eyes)
                // Kehle und Herzkern: kaum sichtbar in Ruhe, beim Atemholen hell mit leichtem Flackern.
                val throat = state.throatGlow / 100f
                val heart = 0.1f + throat * 0.9f * (0.92f + 0.08f * sin(state.age * 0.9f))
                submit(batch, state, matrices, heartLayer, heart)
                if (state.resonance) {
                    submit(batch, state, matrices, resonanceLayer, 0.8f + 0.2f * sin(state.age * 0.25f))
                }
            }
        })
    }

    private fun submit(batch: RenderCommandQueue, state: NivarisRenderState, matrices: MatrixStack, layer: net.minecraft.client.render.RenderLayer, alpha: Float) {
        val a = (MathHelper.clamp(alpha, 0f, 1f) * 255f).toInt()
        if (a <= 2) return
        batch.submitModel(model, state, matrices, layer, 0xF000F0, OverlayTexture.DEFAULT_UV, (a shl 24) or 0xFFFFFF, null, 0, null)
    }

    override fun createRenderState() = NivarisRenderState()
    override fun getTexture(state: NivarisRenderState): Identifier = texture

    override fun updateRenderState(entity: NivarisEntity, state: NivarisRenderState, tickDelta: Float) {
        super.updateRenderState(entity, state, tickDelta)
        val pose = entity.pose
        state.bossPose = pose
        state.previousPose = entity.previousPose
        state.poseTime = (entity.age - entity.poseChangedAge) + tickDelta
        state.windup = (if (entity.windup > 0) entity.windup else AriusTimings.NIVARIS_WINDUP.getOrElse(pose) { 0 }).toFloat()
        state.walk = entity.walkAmount
        state.walkPhase = entity.walkPhase
        state.throatGlow = entity.throatGlow
        state.eyeGlow = entity.eyeGlow
        state.lead = entity.lead
        state.resonance = entity.resonance
        // Flug-Überblendung: etwa 10 Ticks von Boden zu Luft und zurück.
        val now = entity.age + tickDelta
        val blend = flyBlend.getOrPut(entity) { floatArrayOf(if (entity.flying) 1f else 0f, now) }
        val dt = MathHelper.clamp(now - blend[1], 0f, 5f)
        val target = if (entity.flying) 1f else 0f
        val step = dt * 0.1f
        blend[0] = if (blend[0] < target) minOf(target, blend[0] + step) else maxOf(target, blend[0] - step)
        blend[1] = now
        state.fly = blend[0]
    }

    override fun scale(state: NivarisRenderState, matrices: MatrixStack) {
        matrices.scale(SCALE, SCALE, SCALE)
    }

    override fun canBeCulled(entity: NivarisEntity): Boolean = false

    override fun hasLabel(entity: NivarisEntity, squaredDistance: Double): Boolean = false

    private companion object {
        /** Modell in 1/10 Block gebaut (tools/assets/nivaris_model.py: SCALE). */
        const val SCALE = 1.6f
    }
}
