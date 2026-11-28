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

    // --- Zeitlupe (seit 1.9.0): Farbentzug, schwere Vignette und Zeitwellen beim Schnitt, beim Aufprall des Tritts ---
    private var slowLeft = 0f
    private var slowTotal = 1f
    private var slowStrength = 0f

    /** Zeitlupen-Look für [ticks] Ticks mit Stärke [strength] (0..1); Server über FxKind.TIME_SLOW. */
    fun slow(ticks: Int, strength: Float) {
        slowTotal = (ticks / 20f).coerceAtLeast(0.1f)
        slowLeft = slowTotal
        slowStrength = strength.coerceIn(0f, 1f)
    }

    private fun renderSlow(ctx: DrawContext, dt: Float) {
        if (slowLeft <= 0f) return
        slowLeft -= dt
        val age = slowTotal - slowLeft
        val e = (minOf(age / 0.12f, 1f) * minOf(slowLeft / 0.45f, 1f)).coerceIn(0f, 1f) * slowStrength *
            (if (AriusClientConfig.flash) 1f else 0.6f)
        if (e <= 0.01f) return
        val w = ctx.scaledWindowWidth
        val h = ctx.scaledWindowHeight
        // Farbentzug: kalter, grauer Schleier
        ctx.fill(0, 0, w, h, withAlpha(0xFF8E9AA8.toInt(), 0.26f * e))
        // Schwere Vignette
        val steps = 14
        val depth = minOf(w, h) * 0.42f
        for (i in 0 until steps) {
            val a = 0.5f * e * (1f - i / steps.toFloat()).let { it * it }
            val o = (depth * i / steps).toInt()
            val o2 = (depth * (i + 1) / steps).toInt()
            val c = withAlpha(0xFF04070D.toInt(), a)
            ctx.fill(0, o, w, o2, c); ctx.fill(0, h - o2, w, h - o, c)
            ctx.fill(o, o2, o2, h - o2, c); ctx.fill(w - o2, o2, w - o, h - o2, c)
        }
        // Zeitwellen: helle Rahmen laufen von der Mitte nach außen
        for (j in 0 until 3) {
            val f = ((age * 0.9f + j / 3f) % 1f)
            val rx = (w * 0.5f * f).toInt()
            val ry = (h * 0.5f * f).toInt()
            val a = 0.18f * e * (1f - f)
            val c = withAlpha(0xFFFFFFFF.toInt(), a)
            val cx = w / 2
            val cy = h / 2
            ctx.fill(cx - rx, cy - ry, cx + rx, cy - ry + 1, c)
            ctx.fill(cx - rx, cy + ry - 1, cx + rx, cy + ry, c)
            ctx.fill(cx - rx, cy - ry, cx - rx + 1, cy + ry, c)
            ctx.fill(cx + rx - 1, cy - ry, cx + rx, cy + ry, c)
        }
    }

    fun render(ctx: DrawContext) {
        val client = MinecraftClient.getInstance()
        val inArena = client.world?.registryKey == dev.henny.hha_arius.arena.ArenaDimension.WORLD
        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) 0f else ((now - lastNanos) / 1.0e9f).coerceIn(0f, 0.1f)
        lastNanos = now
        if (inArena) renderSlow(ctx, dt) else slowLeft = 0f
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
        // Vignette: weicher Verlauf je Bildpunkt, Ränder über die volle Länge (die Ecken dunkeln sanft nach, keine Treppen)
        val depth = (minOf(w, h) * 0.3f).toInt().coerceAtLeast(1)
        for (i in 0 until depth) {
            val a = 0.2f * k * (1f - i / depth.toFloat()).let { it * it }
            if (a < 0.004f) break
            val c = withAlpha(0xFF02040A.toInt(), a)
            ctx.fill(0, i, w, i + 1, c); ctx.fill(0, h - i - 1, w, h - i, c)
            ctx.fill(i, 0, i + 1, h, c); ctx.fill(w - i - 1, 0, w - i, h, c)
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
