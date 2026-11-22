package dev.henny.hha_arius.client.fx

import dev.henny.hha.client.vfx.withAlpha
import dev.henny.hha_arius.gear.client.art.ArtAnims
import dev.henny.hha_arius.net.ClashCueS2C
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/** The shrinking cue marks when a melee strike can answer Arius's swing. The server judges the hit. */
object ClashHud {

    private const val ICE = 0xFFB9EBFF.toInt()

    /** Ein Klick wirkt auf dem Server frühestens im nächsten Tick (wie bei der Parade gemessen). */
    private const val PRESS_DELAY = 1f

    var active = false
        private set
    private var start = 0f
    private var strike = 0f
    private var before = 0
    private var after = 0

    private fun now(): Float = ArtAnims.ticks.toFloat()

    fun handle(p: ClashCueS2C) {
        if (!p.on) {
            active = false
            return
        }
        val client = MinecraftClient.getInstance()
        val player = client.player
        val ms = player?.let { client.networkHandler?.getPlayerListEntry(it.uuid)?.latency } ?: 0
        val rtt = (ms / 50f).coerceIn(0f, 10f)
        active = true
        start = now()
        strike = start + p.ticks - rtt - PRESS_DELAY
        before = p.before
        after = p.after
    }

    fun clear() {
        active = false
    }

    fun render(ctx: DrawContext) {
        if (!active) return
        val client = MinecraftClient.getInstance()
        val t = now() + client.renderTickCounter.getTickProgress(true)
        val opens = strike - before
        val closes = strike + after
        if (t > closes + 6f) {
            active = false
            return
        }
        val w = ctx.scaledWindowWidth
        val h = ctx.scaledWindowHeight
        val cx = w / 2
        val cy = h / 2
        when {
            t < opens -> {
                val span = (opens - start).coerceAtLeast(1f)
                val k = ((opens - t) / span).coerceIn(0f, 1f)
                ring(ctx, cx, cy, 18f, withAlpha(ICE, 0.6f), 1)
                ring(ctx, cx, cy, 18f + 40f * k, withAlpha(0xFFFFFFFF.toInt(), 0.9f), 2)
                val seconds = ceil((opens - t) / 20f).toInt().coerceAtLeast(1)
                // Die Zahl steht im Ring am Fadenkreuz, frei von Titelkarte und Untertiteln.
                label(ctx, Text.literal(seconds.toString()).formatted(Formatting.BOLD), cx, cy, 2.6f, withAlpha(0xFFFFFFFF.toInt(), 0.95f))
            }
            t <= closes -> {
                val k = ((t - opens) / (closes - opens).coerceAtLeast(1f)).coerceIn(0f, 1f)
                val half = (23f * (1f - k)).toInt()
                ctx.fill(cx - 24, cy + 24, cx + 24, cy + 28, 0xD0113558.toInt())
                ctx.fill(cx - half, cy + 25, cx + half, cy + 27, ICE)
                ring(ctx, cx, cy, 18f * (1f - k) + 3f, withAlpha(ICE, 1f), 3)
                label(ctx, Text.translatable("hha_arius.clash.now").formatted(Formatting.BOLD), cx, cy - 35, 2.3f - k * 0.3f, withAlpha(ICE, 1f))
            }
        }
    }

    private fun ring(ctx: DrawContext, cx: Int, cy: Int, r: Float, color: Int, size: Int) {
        val n = (r * 2.4f).toInt().coerceIn(16, 120)
        for (i in 0 until n) {
            val a = i * 2.0 * Math.PI / n
            val x = (cx + cos(a) * r).toInt()
            val y = (cy + sin(a) * r).toInt()
            ctx.fill(x, y, x + size, y + size, color)
        }
    }

    private fun label(ctx: DrawContext, text: Text, cx: Int, y: Int, scale: Float, color: Int) {
        val font = MinecraftClient.getInstance().textRenderer
        val m = ctx.matrices
        m.pushMatrix()
        m.translate(cx.toFloat(), y.toFloat())
        m.scale(scale, scale)
        ctx.drawTextWithShadow(font, text, -font.getWidth(text) / 2, -4, color)
        m.popMatrix()
    }
}
