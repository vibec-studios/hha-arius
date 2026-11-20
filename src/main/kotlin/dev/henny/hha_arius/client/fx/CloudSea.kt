package dev.henny.hha_arius.client.fx

import dev.henny.hha.client.vfx.VfxDraw
import dev.henny.hha.client.vfx.VfxLayers
import dev.henny.hha.client.vfx.withAlpha
import net.minecraft.client.MinecraftClient
import net.minecraft.util.math.Vec3d
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Wolkenmeer um die Arena (seit 1.5.1, Crix: „die unendliche Weite ein bisschen wie bei Erlang Shen in seiner zweiten
 * Phase“): Am Horizont rings um den Spieler türmen sich weiche Wolkenbänke, die langsam ziehen, darüber warmes
 * Himmelslicht aus der Sonnenrichtung. Nur in der Arena-Dimension, skaliert mit `/ariusc fx`.
 *
 * Seit 1.8.1 (Crix: „im Flug große durchsichtige flache Flächen am Himmel“): Die Bänke waren kamerazugewandte Karten
 * bis 75 Blöcke groß mit fast harter Kante und ohne Nebel. Von oben (Ritt auf Solvane) standen sie als große
 * durchsichtige Flächen im Himmel. Jetzt
 * * weiche Rauchbänke (runder Rand) statt der Wolkenkarte,
 * * nur in Bodennähe: Sie blenden aus, sobald die Kamera mehr als ~14 Blöcke über der Lauffläche ist,
 * * und blenden aus, wenn die Kamera einer Bank nahe kommt (nie eine Fläche quer durchs Bild).
 */
object CloudSea {

    private val seeds = List(110) { i ->
        val r = java.util.Random(i * 7919L)
        doubleArrayOf(r.nextDouble() * 2 * PI, 140.0 + r.nextDouble() * 70.0, -4.0 + r.nextDouble() * 10.0, 26.0 + r.nextDouble() * 30.0, r.nextDouble())
    }

    fun render(d: VfxDraw) {
        val client = MinecraftClient.getInstance()
        val world = client.world ?: return
        if (world.registryKey != dev.henny.hha_arius.arena.ArenaDimension.WORLD) return
        val fx = AriusClientConfig.fx.coerceIn(0f, 1f)
        if (fx <= 0.05f) return
        val cam = d.cam
        // Bodenhöhe der Instanz: Lauffläche jeder Instanz liegt bei y 160 (BASE_Y).
        val ground = dev.henny.hha_arius.arena.ArenaLayout.BASE_Y.toDouble()
        val time = (world.time % 240000L) + d.tickDelta
        val low = (1.0 - (cam.y - ground - 14.0) / 16.0).coerceIn(0.0, 1.0).toFloat()
        if (low > 0.01f) {
            val n = (seeds.size * fx).toInt()
            for (i in 0 until n) {
                val s = seeds[i]
                val a = s[0] + time * 0.00006 * (1.0 + s[4])
                val p = Vec3d(cam.x + cos(a) * s[1], ground + s[2] + sin(time * 0.002 + i) * 1.5, cam.z + sin(a) * s[1])
                val dist = p.distanceTo(cam)
                val near = ((dist - s[3]) / (s[3] * 0.8)).coerceIn(0.0, 1.0).toFloat()
                val k = 0.42f * fx * low * near
                if (k <= 0.01f) continue
                val tone = if (s[4] < 0.3) 0xFFFFF4E0.toInt() else 0xFFF4F7FB.toInt()
                d.billboard(AriusLayers.VEIL, p, s[3], withAlpha(tone, k), (s[4] * 360.0))
            }
        }
        // Himmelslicht: warmes Leuchten aus der Sonnenrichtung (feste Tageszeit, Sonne im Osten, 32° hoch)
        val sunDir = Vec3d(1.0, 0.62, 0.15).normalize()
        val sun = cam.add(sunDir.multiply(220.0))
        d.billboard(VfxLayers.RAYS_ADD, sun, 200.0, withAlpha(0xFFFFE8B8.toInt(), 0.16f * fx), time * 0.02)
        d.billboard(VfxLayers.GLOW_ADD, sun, 120.0, withAlpha(0xFFFFF0D0.toInt(), 0.28f * fx))
    }
}
