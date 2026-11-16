package dev.henny.hha_arius.client.model.arius

import dev.henny.hha_arius.HhaArius
import dev.henny.hha_arius.boss.AriusEntity
import dev.henny.hha_arius.boss.AriusForm
import dev.henny.hha_arius.boss.AriusTimings
import net.minecraft.client.render.OverlayTexture
import net.minecraft.client.render.RenderLayers
import net.minecraft.client.render.command.OrderedRenderCommandQueue
import net.minecraft.client.render.entity.EntityRendererFactory
import net.minecraft.client.render.entity.LivingEntityRenderer
import net.minecraft.client.render.entity.feature.FeatureRenderer
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.util.Identifier
import net.minecraft.util.math.MathHelper
import kotlin.math.sin

/**
 * Arius: dunkler Atlas für DARK und SERIOUS, weißer Atlas für WHITE (gleiche UVs). Zwei Leuchtschichten wie bei Kronos
 * ([RenderLayers.eyes], Alpha moduliert): die dezente Leuchtmaske des Atlas (Klingenkante, Rüstungsnähte) und die Augen
 * aus einer eigenen Textur, deren Stärke [AriusEntity.eyeGlow] und [AriusEntity.lead] steuern.
 */
class AriusRenderer(context: EntityRendererFactory.Context) :
    LivingEntityRenderer<AriusEntity, AriusRenderState, AriusModel>(context, AriusModel(context.getPart(AriusRenderers.LAYER)), 0.45f) {

    private val dark = tex("arius_dark")
    private val white = tex("arius_white")
    private val darkGlow = RenderLayers.eyes(tex("arius_dark_glow"))
    private val whiteGlow = RenderLayers.eyes(tex("arius_white_glow"))
    private val eyes = RenderLayers.eyes(tex("arius_eyes"))

    init {
        addFeature(object : FeatureRenderer<AriusRenderState, AriusModel>(this) {
            override fun render(matrices: MatrixStack, queue: OrderedRenderCommandQueue, light: Int, state: AriusRenderState,
                                limbAngle: Float, limbDistance: Float) {
                if (state.invisibleToPlayer) return
                val batch = queue.getBatchingQueue(1)
                // Nähte und Klingenkante: dezent, atmen langsam mit.
                val seam = 0.72f + 0.1f * sin(state.age * 0.07f)
                val layer = if (state.form == AriusForm.WHITE) whiteGlow else darkGlow
                batch.submitModel(contextModel, state, matrices, layer, FULL_BRIGHT, OverlayTexture.DEFAULT_UV, tint(seam), null, 0, null)
                // Augen: stark; das goldene führt (Textur), lead macht beide heller.
                val base = MathHelper.clamp(state.eyeGlow / 100f, 0f, 1f)
                val eye = MathHelper.clamp(0.3f + 0.7f * base + (if (state.lead) 0.25f else 0f), 0f, 1f)
                batch.submitModel(contextModel, state, matrices, eyes, FULL_BRIGHT, OverlayTexture.DEFAULT_UV, tint(eye), null, 0, null)
                if (state.lead || base > 0.85f) {
                    val extra = if (state.lead) 0.7f + 0.2f * sin(state.age * 0.25f) else (base - 0.85f) * 4f
                    batch.submitModel(contextModel, state, matrices, eyes, FULL_BRIGHT, OverlayTexture.DEFAULT_UV, tint(extra), null, 0, null)
                }
            }
        })
    }

    private fun tint(alpha: Float): Int = ((MathHelper.clamp(alpha, 0f, 1f) * 255f).toInt() shl 24) or 0xFFFFFF

    override fun createRenderState() = AriusRenderState()

    override fun getTexture(state: AriusRenderState): Identifier = if (state.form == AriusForm.WHITE) white else dark

    override fun updateRenderState(entity: AriusEntity, state: AriusRenderState, tickDelta: Float) {
        super.updateRenderState(entity, state, tickDelta)
        state.entityId = entity.id
        state.bossPose = entity.pose
        state.previousPose = entity.previousPose
        state.poseChangedAge = entity.poseChangedAge
        state.poseTime = (entity.age - entity.poseChangedAge) + tickDelta
        val pose = entity.pose.coerceIn(0, AriusTimings.ARIUS_WINDUP.size - 1)
        state.windup = if (entity.windup > 0) entity.windup else AriusTimings.ARIUS_WINDUP[pose]
        state.form = entity.form
        state.sheathed = entity.sheathed
        state.eyeGlow = entity.eyeGlow
        state.lead = entity.lead
        state.open = entity.open
        state.sideSign = entity.sideSign
        state.walk = entity.walkAmount
        state.walkPhase = entity.walkPhase
    }

    override fun scale(state: AriusRenderState, matrices: MatrixStack) {
        matrices.scale(SCALE, SCALE, SCALE)
    }

    override fun canBeCulled(entity: AriusEntity): Boolean = false

    override fun hasLabel(entity: AriusEntity, squaredDistance: Double): Boolean = false

    private companion object {
        /** Sohle bis Scheitel (Haarkappe) 62 Einheiten × 0,49 = 1,90 Blöcke; Haarspitzen ≈ 1,97, Trefferbox 1,95. */
        const val SCALE = 0.49f
        const val FULL_BRIGHT = 0xF000F0

        fun tex(name: String): Identifier = HhaArius.id("textures/entity/$name.png")
    }
}
