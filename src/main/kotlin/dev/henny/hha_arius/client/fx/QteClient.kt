package dev.henny.hha_arius.client.fx

import dev.henny.hha.client.vfx.withAlpha
import dev.henny.hha_arius.boss.Qte
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.option.KeyBinding
import net.minecraft.sound.SoundEvents
import net.minecraft.text.Text

/**
 * Anzeige und Eingabe der Quick-Time-Events ([Qte]): große Taste in der Bildmitte unten, Aufforderung darüber, ein
 * ablaufender Balken; beim Hämmern füllt jeder Druck den goldenen Balken. Erfolg = goldener Blitz, Fehlschlag = rot.
 * Die Tasten werden über ihren Zustand gelesen (Flanken), damit es auch während einer Szene funktioniert.
 */
object QteClient {

    private var id = -1
    private var kind = Qte.Kind.ATTACK
    private var total = 1
    private var left = 0
    private var need = 1
    private var presses = 0
    private var text = ""
    private var wasDown = false
    private var result = 0      // 0 offen, 1 Erfolg, -1 Fehlschlag
    private var resultAge = 0

    fun init() {
        ClientPlayNetworking.registerGlobalReceiver(Qte.QteS2C.ID) { p, _ ->
            id = p.id
            kind = Qte.Kind.entries.getOrElse(p.kind) { Qte.Kind.ATTACK }
            total = p.ticks.coerceAtLeast(1)
            left = total
            need = p.count.coerceAtLeast(1)
            presses = 0
            text = p.text
            result = 0
            resultAge = 0
            wasDown = key()?.isPressed == true
        }
        ClientTickEvents.END_CLIENT_TICK.register { tick(it) }
        HudRenderCallback.EVENT.register { ctx, tc -> render(ctx, tc.getTickProgress(true)) }
    }

    private fun key(): KeyBinding? {
        val o = MinecraftClient.getInstance().options ?: return null
        return when (kind) {
            Qte.Kind.ATTACK, Qte.Kind.MASH -> o.attackKey
            Qte.Kind.JUMP -> o.jumpKey
            Qte.Kind.USE -> o.useKey
            Qte.Kind.SNEAK -> o.sneakKey
        }
    }

    private fun tick(client: MinecraftClient) {
        if (result != 0) {
            if (++resultAge > 24) { result = 0; id = -1 }
            return
        }
        if (id < 0) return
        val k = key() ?: return
        val down = k.isPressed
        if (down && !wasDown) {
            presses++
            client.player?.playSound(SoundEvents.BLOCK_NOTE_BLOCK_HAT.value(), 0.6f, 1.2f + 0.08f * presses)
            if (presses >= need) finish(true)
        }
        wasDown = down
        if (result == 0 && --left <= 0) finish(false)
    }

    private fun finish(ok: Boolean) {
        if (ClientPlayNetworking.canSend(Qte.QteC2S.ID)) ClientPlayNetworking.send(Qte.QteC2S(id, ok))
        result = if (ok) 1 else -1
        resultAge = 0
        MinecraftClient.getInstance().player?.playSound(if (ok) SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE else SoundEvents.BLOCK_GLASS_BREAK, 1f, if (ok) 1.4f else 0.8f)
    }

    private fun render(ctx: DrawContext, td: Float) {
        if (id < 0 && result == 0) return
        val client = MinecraftClient.getInstance()
        val tr = client.textRenderer
        val w = ctx.scaledWindowWidth
        val h = ctx.scaledWindowHeight
        val cx = w / 2
        val cy = (h * 0.68f).toInt()
        val fade = if (result != 0) (1f - resultAge / 24f).coerceIn(0f, 1f) else 1f
        // Taste
        val keyText = key()?.boundKeyLocalizedText?.copy() ?: Text.literal("?")
        val label = if (kind == Qte.Kind.MASH) Text.translatable("hha_arius.qte.mash", keyText) else keyText
        val m = ctx.matrices
        m.pushMatrix()
        m.translate(cx.toFloat(), (cy - 6).toFloat())
        val pulse = 2.6f + if (result == 0) 0.25f * kotlin.math.sin((total - left + td) * 0.6f) else 0f
        m.scale(pulse, pulse)
        val lw = tr.getWidth(label)
        val col = when (result) {
            1 -> withAlpha(0xFFFFD98A.toInt(), fade)
            -1 -> withAlpha(0xFFE07A6E.toInt(), fade)
            else -> 0xFFFFFFFF.toInt()
        }
        ctx.fill(-lw / 2 - 4, -3, lw / 2 + 4, 11, withAlpha(0xFF0B1020.toInt(), 0.75f * fade))
        ctx.drawText(tr, label, -lw / 2, 0, col, true)
        m.popMatrix()
        // Aufforderung
        if (text.isNotEmpty()) {
            val t = Text.translatable(text)
            ctx.drawText(tr, t, cx - tr.getWidth(t) / 2, cy - 34, withAlpha(0xFFE8F2FF.toInt(), fade), true)
        }
        // Zeitbalken und Fortschritt
        val bw = 120
        val x0 = cx - bw / 2
        val y0 = cy + 26
        ctx.fill(x0 - 1, y0 - 1, x0 + bw + 1, y0 + 4, withAlpha(0xFF000000.toInt(), 0.6f * fade))
        val time = if (result == 0) (left - td).coerceAtLeast(0f) / total else 0f
        ctx.fill(x0, y0, x0 + (bw * time).toInt(), y0 + 3, withAlpha(0xFF9FE6FF.toInt(), fade))
        if (kind == Qte.Kind.MASH) {
            val prog = (presses / need.toFloat()).coerceIn(0f, 1f)
            ctx.fill(x0, y0 + 5, x0 + (bw * prog).toInt(), y0 + 8, withAlpha(0xFFFFD98A.toInt(), fade))
        }
    }
}
