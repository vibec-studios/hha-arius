package dev.henny.hha_arius.client.fx

import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.RenderPipeline
import dev.henny.hha.client.cine.CinePlayer
import dev.henny.hha.client.vfx.VfxDraw
import dev.henny.hha.client.vfx.VfxLayers
import dev.henny.hha_arius.HhaArius
import dev.henny.hha_arius.arena.ArenaDimension
import dev.henny.hha_arius.arena.ArenaLayout
import dev.henny.hha_arius.arena.FarPlan
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gl.RenderPipelines
import net.minecraft.client.render.RenderLayer
import net.minecraft.client.render.RenderSetup
import java.util.concurrent.CompletableFuture
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Ferne Landschaft in Szenen (seit 1.11.0, Crix: „der Berg rendert in den Cutscenes nicht“).
 *
 * Ursache: Der Client hat nur Chunks um den Spieler und zeichnet nur bis zur Sichtweite um die **Kamera**. Eine weite
 * Kamerafahrt steht aber 300 Blöcke vor dem Berg, dort ist er ungeladen oder hinter der Sichtgrenze. Mehr Chunks laden
 * hilft also nicht zuverlässig (Sichtweite des Spielers, Server-Einstellung, Sodium, Bobby).
 *
 * Darum zeichnet dieser Teil während einer Szene überall dort, wo keine echten Chunks zu sehen sind, ein grobes Gitter
 * aus derselben Höhenformel wie die gebaute Landschaft ([FarPlan.top]), mit Schnee, Fels an steilen Hängen und dem Nebel
 * der aktuellen Stimmung. Wo echte Chunks liegen, bleibt es weg. Nur Darstellung, ohne Kollision.
 */
object FarTerrain {

    /** Rasterweite in Blöcken. */
    private const val CELL = 8

    private val nx = (FarPlan.X_MAX - FarPlan.X_MIN) / CELL + 1
    private val nz = (FarPlan.Z_MAX - FarPlan.Z_MIN) / CELL + 1

    @Volatile
    private var heights: FloatArray? = null
    private var job: CompletableFuture<Void>? = null

    private val pipe: RenderPipeline? = try {
        val f = VfxLayers::class.java.getDeclaredField("BASE")
        f.isAccessible = true
        val base = f.get(null) as RenderPipeline.Snippet
        RenderPipelines.register(
            RenderPipeline.builder(base)
                .withLocation(HhaArius.id("pipeline/far_terrain"))
                .withBlend(BlendFunction.TRANSLUCENT)
                .withDepthWrite(true)
                .withShaderDefine("VFX_NO_FOG")
                .build(),
        )
    } catch (t: Throwable) {
        HhaArius.LOGGER.warn("Pipeline far_terrain nicht angelegt: {}", t.toString())
        null
    }

    private val layer: RenderLayer? by lazy {
        val p = pipe ?: return@lazy null
        try {
            RenderLayer.of("hha_arius_far_terrain", RenderSetup.builder(p).texture("Sampler0", HhaArius.id("textures/fx/white.png")).expectedBufferSize(1 shl 20).build())
        } catch (t: Throwable) {
            null
        }
    }

    /** Registriert die Pipeline vor dem ersten Shader-Reload (aus der Client-Initialisierung). */
    fun init() {
        pipe
    }

    /** Höhen einmal im Hintergrund berechnen (die Formel ist rein, das Spiel stockt so nicht). */
    private fun prepare() {
        if (heights != null || job != null) return
        job = CompletableFuture.runAsync {
            val out = FloatArray(nx * nz)
            for (j in 0 until nz) for (i in 0 until nx) {
                val x = FarPlan.X_MIN + i * CELL
                val z = FarPlan.Z_MIN + j * CELL
                out[j * nx + i] = runCatching { FarPlan.top(x, z) }.getOrDefault(-1).coerceAtLeast(-1).toFloat() + 1f
            }
            heights = out
        }
    }

    fun render(d: VfxDraw) {
        val client = MinecraftClient.getInstance()
        val world = client.world ?: return
        if (world.registryKey != ArenaDimension.WORLD) return
        prepare()
        if (!CinePlayer.active) return
        val h = heights ?: return
        val rl = layer ?: return
        val cam = d.cam
        val slot = Math.floorDiv(floor(cam.x).toInt() + ArenaLayout.SLOT_SPACING / 2, ArenaLayout.SLOT_SPACING)
        val o = ArenaLayout.origin(slot)
        val view = client.options.clampedViewDistance
        val camCx = floor(cam.x).toInt() shr 4
        val camCz = floor(cam.z).toInt() shr 4
        val fwd = d.camForward
        val (fs, fe) = Atmos.fogRange()
        // Dichter Sturm: Man sieht ohnehin nur wenige Blöcke weit.
        if (fe < 60f) return
        val fog = Atmos.fogColorNow()
        val fr = (fog shr 16 and 255) / 255f
        val fg = (fog shr 8 and 255) / 255f
        val fb = (fog and 255) / 255f
        val vc = d.buffer(rl)

        fun vertex(i: Int, j: Int) {
            val x = o.x + FarPlan.X_MIN + i * CELL + 0.5
            val y = o.y + h[j * nx + i].toDouble()
            val z = o.z + FarPlan.Z_MIN + j * CELL + 0.5
            // Hangneigung aus den Nachbarn.
            val hx = h[j * nx + (i + 1).coerceAtMost(nx - 1)] - h[j * nx + (i - 1).coerceAtLeast(0)]
            val hz = h[(j + 1).coerceAtMost(nz - 1) * nx + i] - h[(j - 1).coerceAtLeast(0) * nx + i]
            val slope = sqrt(hx * hx + hz * hz) / (2f * CELL)
            // Licht von Osten, leicht von oben.
            val nl = 1f / sqrt(hx * hx / (4f * CELL * CELL) + hz * hz / (4f * CELL * CELL) + 1f)
            val lit = (0.72f + 0.34f * (nl * (-hx / (2f * CELL)) * 0.8f + nl * 0.6f)).coerceIn(0.55f, 1.05f)
            val rock = ((slope - 0.85f) / 0.5f).coerceIn(0f, 1f)
            var r = (0.93f + (0.50f - 0.93f) * rock) * lit
            var g = (0.95f + (0.53f - 0.95f) * rock) * lit
            var b = (0.98f + (0.59f - 0.98f) * rock) * lit
            val dx = x - cam.x
            val dy = y - cam.y
            val dz = z - cam.z
            val dist = sqrt(dx * dx + dy * dy + dz * dz).toFloat()
            val k = ((dist - fs) / (fe - fs).coerceAtLeast(1f)).coerceIn(0f, 1f)
            r += (fr - r) * k
            g += (fg - g) * k
            b += (fb - b) * k
            val col = (255 shl 24) or ((r.coerceIn(0f, 1f) * 255f).toInt() shl 16) or ((g.coerceIn(0f, 1f) * 255f).toInt() shl 8) or (b.coerceIn(0f, 1f) * 255f).toInt()
            with(d) { vc.put(x, y, z, 0.5f, 0.5f, col) }
        }

        for (j in 0 until nz - 1) for (i in 0 until nx - 1) {
            val cx = o.x + FarPlan.X_MIN + i * CELL + CELL / 2
            val cz = o.z + FarPlan.Z_MIN + j * CELL + CELL / 2
            val ddx = cx - cam.x
            val ddz = cz - cam.z
            // Hinter der Kamera nichts zeichnen (großzügig wegen hoher Berge am Bildrand).
            if (ddx * fwd.x + ddz * fwd.z < -48.0 && ddx * ddx + ddz * ddz > 64.0 * 64.0) continue
            // Echte Chunks in Sichtweite zeigen die echte Landschaft.
            val ccx = cx shr 4
            val ccz = cz shr 4
            if (abs(ccx - camCx) < view - 1 && abs(ccz - camCz) < view - 1 && world.chunkManager.isChunkLoaded(ccx, ccz)) continue
            vertex(i, j + 1)
            vertex(i + 1, j + 1)
            vertex(i + 1, j)
            vertex(i, j)
        }

        // Rund um das Raster läuft das Schneefeld bis in den Nebel weiter (sonst sieht man von oben seine Kante).
        val x0 = o.x + FarPlan.X_MIN + 0.5
        val x1 = o.x + FarPlan.X_MIN + (nx - 1) * CELL + 0.5
        val z0 = o.z + FarPlan.Z_MIN + 0.5
        val z1 = o.z + FarPlan.Z_MIN + (nz - 1) * CELL + 0.5
        val y = o.y - 0.4
        val far = 2400.0
        fun edge(x: Double, z: Double) {
            val dist = sqrt((x - cam.x) * (x - cam.x) + (y - cam.y) * (y - cam.y) + (z - cam.z) * (z - cam.z)).toFloat()
            val k = ((dist - fs) / (fe - fs).coerceAtLeast(1f)).coerceIn(0f, 1f)
            val r = 0.93f + (fr - 0.93f) * k
            val g = 0.95f + (fg - 0.95f) * k
            val b = 0.98f + (fb - 0.98f) * k
            with(d) { vc.put(x, y, z, 0.5f, 0.5f, (255 shl 24) or ((r * 255f).toInt() shl 16) or ((g * 255f).toInt() shl 8) or (b * 255f).toInt()) }
        }
        fun band(ax: Double, az: Double, bx: Double, bz: Double, cx: Double, cz: Double, ex: Double, ez: Double) {
            edge(ax, az); edge(bx, bz); edge(cx, cz); edge(ex, ez)
        }
        band(x0, z1, x1, z1, x1 + far, z1 + far, x0 - far, z1 + far)
        band(x1, z1, x1, z0, x1 + far, z0 - far, x1 + far, z1 + far)
        band(x1, z0, x0, z0, x0 - far, z0 - far, x1 + far, z0 - far)
        band(x0, z0, x0, z1, x0 - far, z1 + far, x0 - far, z0 - far)
    }
}
