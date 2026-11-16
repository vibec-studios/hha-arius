package dev.henny.hha_arius.gear.client.art

import dev.henny.hha_arius.gear.GearItems
import dev.henny.hha_arius.gear.art.Anim
import dev.henny.hha_arius.gear.art.GauntletsItem
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityFeatureRendererRegistrationCallback
import net.minecraft.client.render.entity.PlayerEntityRenderer
import net.minecraft.client.render.entity.feature.FeatureRendererContext
import net.minecraft.client.render.entity.model.BipedEntityModel
import net.minecraft.client.render.entity.model.PlayerEntityModel
import net.minecraft.client.render.entity.state.PlayerEntityRenderState
import net.minecraft.item.ItemStack
import net.minecraft.util.Arm
import kotlin.math.floor

/**
 * Darstellung der Kampfhandschuhe und Posen (Render-Paket). Bestandteile:
 * - [ArtPose]: Ganzkörper-Haltungen der dritten Person (Mixins auf PlayerEntityModel/PlayerEntityRenderer),
 * - [GauntletFeature]: Handschuhe + Hand-/Klingenenergie in der dritten Person,
 * - [GauntletFirstPerson]: beide Arme in der ersten Person (Mixin auf HeldItemRenderer),
 * - [ArtVisuals]: Welt-Effekte (Schild, Gleiter, Schnitt, Zielvorschau).
 */
object ArtRender {
    private var registered = false

    fun init() {
        if (registered) return
        registered = true
        LivingEntityFeatureRendererRegistrationCallback.EVENT.register { _, renderer, helper, _ ->
            if (renderer is PlayerEntityRenderer<*>) {
                @Suppress("UNCHECKED_CAST")
                helper.register(GauntletFeature(renderer as FeatureRendererContext<PlayerEntityRenderState, PlayerEntityModel>))
            }
        }
        ArtVisuals.init()
    }

    /**
     * Ende von PlayerEntityRenderer.updateRenderState: Mit den Handschuhen in der Haupthand wird weder deren
     * Item-Modell noch ein Nebenhand-Gegenstand gezeichnet (nichts schneidet durch die Handschuhe); während des Klaren
     * Schnitts fehlt die Nebenhand ebenso (beidhändiger Griff). Der Stapel der Haupthand bleibt für die Posen erhalten.
     */
    @JvmStatic
    fun hideItems(state: PlayerEntityRenderState) {
        val main = state.mainHandItemStack
        val gauntlets = GauntletsItem.isGauntlets(main)
        val anim = ArtAnims.get(state.id)
        val art = main.isOf(GearItems.SOLVANE) && anim != null && (anim.anim == Anim.ART_CHARGE ||
            (anim.anim == Anim.ART_RELEASE && anim.age(state.age - floor(state.age)) < 12f))
        if (!gauntlets && !art) return
        val mainRight = state.mainArm != Arm.LEFT
        if (mainRight) {
            state.leftHandItemState.clear(); state.leftHandItem = ItemStack.EMPTY; state.leftArmPose = BipedEntityModel.ArmPose.EMPTY
        } else {
            state.rightHandItemState.clear(); state.rightHandItem = ItemStack.EMPTY; state.rightArmPose = BipedEntityModel.ArmPose.EMPTY
        }
        if (gauntlets) {
            state.mainHandItemState.clear()
            if (mainRight) state.rightArmPose = BipedEntityModel.ArmPose.EMPTY else state.leftArmPose = BipedEntityModel.ArmPose.EMPTY
        }
    }
}
