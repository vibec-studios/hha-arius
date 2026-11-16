package dev.henny.hha_arius.client

import dev.henny.hha_arius.AriusEntities
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry
import net.minecraft.client.render.entity.EmptyEntityRenderer

/** Fabric-Client-Einstieg: Renderer, Effekte, Untertitel, Musik, Haltezustand. */
object HhaAriusClient : ClientModInitializer {
    override fun onInitializeClient() {
        dev.henny.hha_arius.client.model.arius.AriusRenderers.register()
        dev.henny.hha_arius.client.model.nivaris.NivarisRenderers.register()
        dev.henny.hha_arius.client.fx.AriusFxClient.init()
        EntityRendererRegistry.register(AriusEntities.NIVARIS_HEAD, ::EmptyEntityRenderer)
    }
}
