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
        dev.henny.hha_arius.gear.client.art.ArtAnims.init()
        dev.henny.hha_arius.gear.client.art.ArtClient.init()
        dev.henny.hha_arius.gear.client.BlueprintClient.init()
        dev.henny.hha_arius.gear.client.art.ArtRender.init()
        dev.henny.hha_arius.client.model.armor.ArmorRender.init()
        Preload.init()
        dev.henny.hha_arius.gear.client.FrostHud.init()
        EntityRendererRegistry.register(AriusEntities.NIVARIS_HEAD, ::EmptyEntityRenderer)
        dev.henny.hha_arius.client.fx.CineClient.init()
        dev.henny.hha_arius.client.fx.HuntClient.init()
    }
}
