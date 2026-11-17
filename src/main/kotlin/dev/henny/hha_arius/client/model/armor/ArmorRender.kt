package dev.henny.hha_arius.client.model.armor

import dev.henny.hha_arius.gear.GearItems
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityFeatureRendererRegistrationCallback
import net.minecraft.client.render.entity.feature.FeatureRendererContext
import net.minecraft.client.render.entity.model.BipedEntityModel
import net.minecraft.client.render.entity.state.BipedEntityRenderState
import net.minecraft.item.ItemStack

/**
 * Drachenherz-Wacht (Eirholm-Rüstung) als eigenes 3D-Modell. Vanilla zeichnet von der Rüstung nichts mehr
 * (`equipment/eirholm.json` gibt es nicht, also keine Ebenen); stattdessen hängt [ArmorFeature] an jedem Renderer mit
 * Biped-Modell (Spieler, Mannequin, Rüstungsständer, Zombies, Skelette …) und zeichnet die getragenen Teile an dessen
 * Knochen. Modell und Textur: `tools/assets/armor_model.py`, Notiz `docs/ARMOR_3D.md`.
 */
object ArmorRender {
    private var registered = false

    fun init() {
        if (registered) return
        registered = true
        LivingEntityFeatureRendererRegistrationCallback.EVENT.register { _, renderer, helper, _ ->
            if (renderer.model is BipedEntityModel<*>) {
                @Suppress("UNCHECKED_CAST")
                helper.register(
                    ArmorFeature(renderer as FeatureRendererContext<BipedEntityRenderState, BipedEntityModel<BipedEntityRenderState>>),
                )
            }
        }
    }

    fun isHelmet(stack: ItemStack): Boolean = stack.isOf(GearItems.EIRHOLM_HELMET)
    fun isChest(stack: ItemStack): Boolean = stack.isOf(GearItems.EIRHOLM_CHESTPLATE)
    fun isLegs(stack: ItemStack): Boolean = stack.isOf(GearItems.EIRHOLM_LEGGINGS)
    fun isBoots(stack: ItemStack): Boolean = stack.isOf(GearItems.EIRHOLM_BOOTS)

    /**
     * Für den Umhang (CapeFeatureArmorMixin): Der Kürass steht hinten wie eine Vanilla-Brustplatte ab, also soll der
     * Umhang denselben Abstand bekommen wie über Vanilla-Rüstung.
     */
    @JvmStatic
    fun pushesCape(stack: ItemStack): Boolean = isChest(stack)
}
