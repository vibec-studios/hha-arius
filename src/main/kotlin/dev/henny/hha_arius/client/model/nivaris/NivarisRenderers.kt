package dev.henny.hha_arius.client.model.nivaris

import dev.henny.hha_arius.AriusEntities
import dev.henny.hha_arius.HhaArius
import net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry
import net.minecraft.client.render.entity.model.EntityModelLayer

/** Registriert Modell-Layer und Renderer der Drachin Nivaris ([AriusEntities.NIVARIS]). */
object NivarisRenderers {
    val LAYER = EntityModelLayer(HhaArius.id("nivaris"), "main")

    fun register() {
        EntityModelLayerRegistry.registerModelLayer(LAYER) { NivarisModelData.nivaris() }
        EntityRendererRegistry.register(AriusEntities.NIVARIS, ::NivarisRenderer)
    }
}
