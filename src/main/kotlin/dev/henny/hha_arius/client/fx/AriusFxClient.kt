package dev.henny.hha_arius.client.fx

import com.mojang.brigadier.arguments.BoolArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import dev.henny.hha.client.vfx.VfxDraw
import dev.henny.hha_arius.net.FxS2C
import dev.henny.hha_arius.net.HoldS2C
import dev.henny.hha_arius.net.LineS2C
import dev.henny.hha_arius.net.MusicS2C
import dev.henny.hha_arius.net.SliceS2C
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.text.Text

/** Client-Seite des Kampfes: Pakete, Effekte, Blockbündel, Untertitel, Musik, Haltezustand, `/ariusc`. */
object AriusFxClient {

    private val effects = ArrayList<AriusFx>()
    private const val MAX_EFFECTS = 64

    fun init() {
        AriusClientConfig.load()
        ArtPipelines.init()
        ClientPlayNetworking.registerGlobalReceiver(HoldS2C.ID) { p, _ -> HoldClient.set(p.on, p.ticks) }
        ClientPlayNetworking.registerGlobalReceiver(SliceS2C.ID) { p, _ -> SliceRender.handle(p) }
        ClientPlayNetworking.registerGlobalReceiver(dev.henny.hha_arius.gear.art.ArtCutS2C.ID) { p, _ -> CutHalves.handle(p) }
        ClientPlayNetworking.registerGlobalReceiver(LineS2C.ID) { p, _ -> Subtitles.handle(p) }
        ClientPlayNetworking.registerGlobalReceiver(MusicS2C.ID) { p, _ -> AriusMusic.handle(p.event, p.fade) }
        ClientPlayNetworking.registerGlobalReceiver(dev.henny.hha_arius.net.AtmosS2C.ID) { p, _ -> Atmos.handle(p) }
        ClientPlayNetworking.registerGlobalReceiver(FxS2C.ID) { p, ctx ->
            val world = ctx.client().world ?: return@registerGlobalReceiver
            val fx = AriusFx.create(p, world) ?: return@registerGlobalReceiver
            if (effects.size >= MAX_EFFECTS) effects.removeAt(0)
            effects.add(fx)
        }
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            val world = client.world ?: return@register
            if (!client.isPaused) {
                effects.removeIf { !it.advance(world) || (it is WhiteHoleFx && it.finished) }
                SliceRender.tick()
                CutHalves.tick()
                Atmos.tick(world)
                Subtitles.tick()
                ScreenFlash.tick()
            }
            AriusMusic.tick()
        }
        WorldRenderEvents.END_MAIN.register { context ->
            val client = MinecraftClient.getInstance()
            val camera = client.gameRenderer.camera
            val tickDelta = client.renderTickCounter.getTickProgress(true)
            val consumers = context.consumers()
            val matrices = MatrixStack()
            SliceRender.render(matrices, consumers, camera.cameraPos, tickDelta)
            CutHalves.render(matrices, consumers, camera.cameraPos, tickDelta)
            val draw = VfxDraw(consumers, camera, tickDelta)
            Atmos.render(draw)
            if (effects.isEmpty()) return@register
            for (fx in effects) fx.render(draw, matrices, fx.age + tickDelta)
            for (fx in effects) if (fx is WhiteHoleFx) WhiteLens.render(fx, tickDelta)
        }
        HudRenderCallback.EVENT.register { context, _ ->
            ScreenFlash.render(context)
            Subtitles.render(context)
        }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> reset() }
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(
                ClientCommandManager.literal("ariusc")
                    .then(ClientCommandManager.literal("fx").then(ClientCommandManager.argument("percent", IntegerArgumentType.integer(0, 100)).executes { ctx ->
                        AriusClientConfig.fx = IntegerArgumentType.getInteger(ctx, "percent") / 100f
                        AriusClientConfig.save()
                        ctx.source.sendFeedback(Text.translatable("hha_arius.client.fx", IntegerArgumentType.getInteger(ctx, "percent")))
                        1
                    }))
                    .then(ClientCommandManager.literal("flash").then(ClientCommandManager.argument("on", BoolArgumentType.bool()).executes { ctx ->
                        AriusClientConfig.flash = BoolArgumentType.getBool(ctx, "on")
                        AriusClientConfig.save()
                        ctx.source.sendFeedback(Text.translatable(if (AriusClientConfig.flash) "hha_arius.client.flash_on" else "hha_arius.client.flash_off"))
                        1
                    })),
            )
        }
    }

    /** Bosskampf: das weiße Loch bei [pos] kollabiert jetzt. */
    fun collapseWhiteHole(pos: net.minecraft.util.math.Vec3d) {
        effects.filterIsInstance<WhiteHoleFx>().minByOrNull { it.center.squaredDistanceTo(pos) }?.takeIf { it.center.squaredDistanceTo(pos) < 64.0 }?.collapseNow()
    }

    fun reset() {
        effects.clear()
        SliceRender.clear()
        CutHalves.clear()
        Atmos.reset()
        Subtitles.clear()
        AriusMusic.clear()
        HoldClient.reset()
    }
}
