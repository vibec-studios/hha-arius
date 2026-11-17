package dev.henny.hha_arius.client.fx

import dev.henny.hha.client.cine.CinePlayer
import dev.henny.hha.client.vfx.withAlpha
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext

/**
 * Filmischer Look für Arius' Szenen (seit 1.3.1): solange eine Cutscene im Heiligtum läuft, legt sich eine kühle
 * Farbstimmung über das Bild (oben stahlblau, unten wärmer), eine weiche Vignette und feines Filmkorn. Blendet weich
 * ein und aus. Unter HHAs Letterbox-Balken.
 */
object CineGrade {
    private var k = 0f
    private var lastNanos = 0L
    private val rnd = java.util.Random()

    fun render(ctx: DrawContext) {
        val client = MinecraftClient.getInstance()
        val inArena = client.world?.registryKey == dev.henny.hha_arius.arena.ArenaDimension.WORLD
        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) 0f else ((now - lastNanos) / 1.0e9f).coerceIn(0f, 0.1f)
        lastNanos = now
        val want = if (CinePlayer.active && inArena) 1f else 0f
        k += (want - k) * (dt * 3f).coerceAtMost(1f)
        if (k < 0.01f) return
        val w = ctx.scaledWindowWidth
        val h = ctx.scaledWindowHeight
        // Farbstimmung: oben kühl, unten etwas wärmer (Verlauf in Streifen)
        val bands = 24
        for (i in 0 until bands) {
            val f = i / (bands - 1f)
            val cool = withAlpha(0xFF1B3354.toInt(), 0.16f * k * (1f - f))
            val warm = withAlpha(0xFF3A2A18.toInt(), 0.08f * k * f)
            val y0 = h * i / bands
            val y1 = h * (i + 1) / bands
            ctx.fill(0, y0, w, y1, cool)
            ctx.fill(0, y0, w, y1, warm)
        }
        // Vignette
        val steps = 16
        val depth = (minOf(w, h) * 0.3f)
        for (i in 0 until steps) {
            val a = 0.32f * k * (1f - i / steps.toFloat()).let { it * it }
            val o = (depth * i / steps).toInt()
            val o2 = (depth * (i + 1) / steps).toInt()
            val c = withAlpha(0xFF02040A.toInt(), a)
            ctx.fill(0, o, w, o2, c); ctx.fill(0, h - o2, w, h - o, c)
            ctx.fill(o, o2, o2, h - o2, c); ctx.fill(w - o2, o2, w - o, h - o2, c)
        }
        // Filmkorn
        val grains = (w * h / 900f * k).toInt().coerceAtMost(900)
        for (i in 0 until grains) {
            val x = rnd.nextInt(w)
            val y = rnd.nextInt(h)
            ctx.fill(x, y, x + 1, y + 1, withAlpha(if (rnd.nextBoolean()) 0xFFFFFFFF.toInt() else 0xFF000000.toInt(), 0.06f * k))
        }
    }
}
