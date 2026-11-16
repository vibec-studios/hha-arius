package dev.henny.hha_arius.gear.client.art

import dev.henny.hha_arius.gear.art.GauntletsItem
import net.minecraft.client.render.command.OrderedRenderCommandQueue
import net.minecraft.client.render.entity.LivingEntityRenderer
import net.minecraft.client.render.entity.feature.FeatureRenderer
import net.minecraft.client.render.entity.feature.FeatureRendererContext
import net.minecraft.client.render.entity.model.PlayerEntityModel
import net.minecraft.client.render.entity.state.PlayerEntityRenderState
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.entity.player.PlayerSkinType
import kotlin.math.floor

/**
 * Dritte Person: Handschuhe auf beiden Armen (Stulpe/Schiene + Faust oder offene Hand je Modus, Leuchtmaske nur im
 * Magiemodus) und die an Animationen gebundene Energie an Händen bzw. Solvanes Schneide.
 */
internal class GauntletFeature(ctx: FeatureRendererContext<PlayerEntityRenderState, PlayerEntityModel>) :
    FeatureRenderer<PlayerEntityRenderState, PlayerEntityModel>(ctx) {

    override fun render(
        matrices: MatrixStack, queue: OrderedRenderCommandQueue, light: Int, state: PlayerEntityRenderState,
        limbAngle: Float, limbDistance: Float,
    ) {
        if (state.invisible || state.spectator) return
        val model = contextModel
        val stack = state.mainHandItemStack
        val td = state.age - floor(state.age)
        val anim = ArtAnims.get(state.id)
        val slim = state.skinTextures.model() == PlayerSkinType.SLIM
        if (GauntletsItem.isGauntlets(stack)) {
            val open = GauntletLook.open(stack, anim, td)
            val glow = GauntletLook.glow(stack, anim, td, ArtAnims.ticks + td)
            val overlay = LivingEntityRenderer.getOverlay(state, 0f)
            for (right in booleanArrayOf(true, false)) {
                val arm = if (right) model.rightArm else model.leftArm
                if (!arm.visible) continue
                matrices.push()
                model.rootPart.applyTransform(matrices)
                arm.applyTransform(matrices)
                GauntletParts.submit(queue, matrices, right, slim, open, glow, light, overlay)
                matrices.pop()
            }
        }
        if (anim != null) ArtVisuals.handFx(matrices, queue, state, model, anim, td, slim)
    }
}
