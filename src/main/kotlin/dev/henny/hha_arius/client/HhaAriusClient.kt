package dev.henny.hha_arius.client

import net.fabricmc.api.ClientModInitializer

/** Fabric-Client-Einstieg: Renderer, Effekte, Untertitel, Musik, Haltezustand. */
object HhaAriusClient : ClientModInitializer {
    override fun onInitializeClient() {
        dev.henny.hha_arius.client.model.arius.AriusRenderers.register()
        dev.henny.hha_arius.client.model.nivaris.NivarisRenderers.register()
        dev.henny.hha_arius.client.fx.AriusFxClient.init()
    }
}
