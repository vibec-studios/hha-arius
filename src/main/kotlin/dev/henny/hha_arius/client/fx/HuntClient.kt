package dev.henny.hha_arius.client.fx

import dev.henny.hha.client.vfx.VfxDraw
import dev.henny.hha_arius.net.HuntFxS2C
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents
import net.minecraft.client.MinecraftClient

/**
 * Client-Einstieg der Welt-Überarbeitung 1.10.0: Effektpaket der Jagd ([HuntFxS2C]), Filmsprache ([HuntCine],
 * [HuntLens]), Hängebrücken ([RopeBridges]), Schreckmomente und Höhlen ([HuntScares]).
 */
object HuntClient {
    fun init() {
        HuntLens.init()
        ClientPlayNetworking.registerGlobalReceiver(HuntFxS2C.ID) { p, _ -> HuntScares.handle(p) }
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            val world = client.world ?: return@register
            if (client.isPaused) return@register
            HuntCine.tick()
            RopeBridges.tick()
            HuntScares.tick(world)
        }
        WorldRenderEvents.END_MAIN.register { context ->
            val client = MinecraftClient.getInstance()
            val camera = client.gameRenderer.camera
            val draw = VfxDraw(context.consumers(), camera, client.renderTickCounter.getTickProgress(true))
            try {
                RopeBridges.render(draw)
                HuntScares.render(draw)
            } catch (e: Exception) {
                if (!warned) {
                    warned = true
                    dev.henny.hha_arius.HhaArius.LOGGER.error("Effekte der Jagd fehlgeschlagen; weitere Meldungen werden unterdrückt", e)
                }
            }
        }
        // Die Filmbalken liegen unter allen Vanilla-Ebenen (Bossleiste, Herzen, Hotbar bleiben sichtbar).
        net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.attachElementBefore(
            net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.MISC_OVERLAYS,
            dev.henny.hha_arius.HhaArius.id("hunt_letterbox"),
        ) { ctx, _ -> HuntCine.renderBars(ctx) }
        HudRenderCallback.EVENT.register { ctx, counter -> HuntCine.renderHud(ctx, counter.getTickProgress(true)) }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> reset() }
    }

    private var warned = false

    fun reset() {
        HuntCine.reset()
        RopeBridges.reset()
        HuntScares.reset()
        KickAnim.reset()
        MaceSwingClient.reset()
    }
}
