package dev.henny.hha_arius.gear.client

import dev.henny.hha.client.vfx.withAlpha
import dev.henny.hha_arius.gear.FrostS2C
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.text.Text

/**
 * Frostbalken wie die Statusleisten in Elden Ring: erscheint über der Hotbar, sobald Frost aufgebaut wird, füllt sich
 * eisblau, blitzt beim Ausbruch weiß auf; während der Erfrierung zeigt er „Erfrierung“ und die verbleibende Zeit.
 */
object FrostHud {

    private var value = 0f
    private var shown = 0f
    private var bite = 0
    private var flash = 0
    private var visible = 0f

    fun init() {
        ClientPlayNetworking.registerGlobalReceiver(FrostS2C.ID) { p, _ ->
            if (p.frostbite > 0 && bite == 0) flash = 16
            value = p.value.toFloat()
            bite = p.frostbite
        }
        ClientTickEvents.END_CLIENT_TICK.register {
            if (bite > 0) bite--
            if (flash > 0) flash--
            shown += (value - shown) * 0.35f
            val want = if (value > 0.5f || bite > 0 || flash > 0) 1f else 0f
            visible += (want - visible) * 0.2f
        }
        HudRenderCallback.EVENT.register { ctx, _ -> render(ctx) }
    }

    private fun render(ctx: DrawContext) {
        if (visible < 0.02f) return
        val client = MinecraftClient.getInstance()
        if (client.options.hudHidden) return
        val w = ctx.scaledWindowWidth
        val h = ctx.scaledWindowHeight
        val bw = 82
        val x = w / 2 - bw / 2
        val y = h - 66
        val a = visible
        // Rahmen
        ctx.fill(x - 2, y - 2, x + bw + 2, y + 5, withAlpha(0xFF0B1020.toInt(), 0.8f * a))
        ctx.fill(x - 1, y - 1, x + bw + 1, y + 4, withAlpha(0xFF6F8CB0.toInt(), 0.9f * a))
        ctx.fill(x, y, x + bw, y + 3, withAlpha(0xFF101826.toInt(), 0.95f * a))
        if (bite > 0) {
            val k = (bite / 600f).coerceIn(0f, 1f)
            ctx.fill(x, y, x + (bw * k).toInt(), y + 3, withAlpha(0xFF5A9FD8.toInt(), a))
        } else {
            val k = (shown / 100f).coerceIn(0f, 1f)
            ctx.fill(x, y, x + (bw * k).toInt(), y + 3, withAlpha(0xFFBFE8FF.toInt(), a))
            ctx.fill(x, y, x + (bw * k).toInt(), y + 1, withAlpha(0xFFFFFFFF.toInt(), 0.8f * a))
        }
        if (flash > 0) ctx.fill(x - 2, y - 2, x + bw + 2, y + 5, withAlpha(0xFFFFFFFF.toInt(), flash / 16f * 0.8f))
        // Schneeflocke links
        val tr = client.textRenderer
        ctx.drawText(tr, Text.literal("❄"), x - 11, y - 3, withAlpha(0xFFBFE8FF.toInt(), a), true)
        if (bite > 0) {
            val t = Text.translatable("hha_arius.hud.frostbite", (bite + 19) / 20)
            ctx.drawText(tr, t, w / 2 - tr.getWidth(t) / 2, y - 11, withAlpha(0xFF9FD8FF.toInt(), a), true)
        }
    }
}
