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
 * Phase, wo man als Riese kämpft“): Am Horizont rings um den Spieler türmen sich weiche Wolkenbänke, die langsam ziehen,
 * darüber warmes Himmelslicht mit Strahlen aus der Sonnenrichtung. So wirkt das endlose Schneefeld wie eine Ebene über
 * den Wolken. Nur in der Arena-Dimension, skaliert mit `/ariusc fx`.
 */
object CloudSea {

    private val seeds = List(110) { i ->
        val r = java.util.Random(i * 7919L)
        doubleArrayOf(r.nextDouble() * 2 * PI, 120.0 + r.nextDouble() * 70.0, -6.0 + r.nextDouble() * 18.0, 30.0 + r.nextDouble() * 45.0, r.nextDouble())
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
        val n = (seeds.size * fx).toInt()
        for (i in 0 until n) {
            val s = seeds[i]
            val a = s[0] + time * 0.00006 * (1.0 + s[4])
            val p = Vec3d(cam.x + cos(a) * s[1], ground + s[2] + sin(time * 0.002 + i) * 1.5, cam.z + sin(a) * s[1])
            val tone = if (s[4] < 0.3) 0xFFFFF4E0.toInt() else 0xFFFFFFFF.toInt()
            d.billboard(VfxLayers.CLOUD_ALPHA, p, s[3], withAlpha(tone, 0.5f * fx), (s[4] * 360.0))
        }
        // Himmelslicht: warme Strahlen aus der Sonnenrichtung (feste Tageszeit, Sonne im Osten, 32° hoch)
        val sunDir = Vec3d(1.0, 0.62, 0.15).normalize()
        val sun = cam.add(sunDir.multiply(220.0))
        d.billboard(VfxLayers.RAYS_ADD, sun, 260.0, withAlpha(0xFFFFE8B8.toInt(), 0.22f * fx), time * 0.02)
        d.billboard(VfxLayers.GLOW_ADD, sun, 140.0, withAlpha(0xFFFFF0D0.toInt(), 0.3f * fx))
    }
}
