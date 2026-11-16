package dev.henny.hha_arius.client.model.arius

import dev.henny.hha_arius.AriusEntities
import dev.henny.hha_arius.HhaArius
import net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry
import net.minecraft.client.render.entity.model.EntityModelLayer

/** Registriert Modell-Layer und Renderer von Arius (aufgerufen aus HhaAriusClient). */
object AriusRenderers {
    val LAYER = EntityModelLayer(HhaArius.id("arius"), "main")

    fun register() {
        EntityModelLayerRegistry.registerModelLayer(LAYER) { AriusModelData.arius() }
        EntityRendererRegistry.register(AriusEntities.ARIUS, ::AriusRenderer)
    }
}
