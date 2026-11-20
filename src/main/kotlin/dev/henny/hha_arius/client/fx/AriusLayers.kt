package dev.henny.hha_arius.client.fx

import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.RenderPipeline
import dev.henny.hha.client.vfx.VfxLayers
import dev.henny.hha_arius.HhaArius
import net.minecraft.client.gl.RenderPipelines
import net.minecraft.client.render.RenderLayer
import net.minecraft.client.render.RenderSetup
import net.minecraft.util.Identifier

/**
 * Eigene Ebenen auf dem HHA-Effektshader (seit 1.8.1), weil die vorhandenen für Sturm und Mond nicht taugen:
 * * [SNOW]: weiche runde Flocke, **alpha-gemischt** (nicht additiv) und ohne Vanilla-Nebel – Schnee bleibt vor weißem
 *   Himmel und im Whiteout sichtbar; die Entfernungsdämpfung rechnet [SnowStorm] selbst.
 * * [VEIL]: weiche Rauchbank, alpha-gemischt, ohne Nebel (Schneeschleier und Böen).
 * * [MOON]: deckender Körper des gefrorenen Sterns mit Tiefenschreiben, ohne Nebel (im Sturm sichtbar).
 * * [MOON_GLOW]: halb additives Leuchten ohne Nebel (Halo, Korona, Risse).
 * Die Pipelines teilen den Grundbaustein von HHA (Shader `hha:core/vfx`); fehlt er (andere HHA-Fassung), fallen die
 * Ebenen auf die nächstbesten HHA-Ebenen zurück.
 */
object AriusLayers {

    private val base: RenderPipeline.Snippet? = try {
        val f = VfxLayers::class.java.getDeclaredField("BASE")
        f.isAccessible = true
        f.get(null) as RenderPipeline.Snippet
    } catch (t: Throwable) {
        HhaArius.LOGGER.warn("HHA-Effektshader nicht gefunden, Sturm/Mond nutzen Ersatzebenen: {}", t.toString())
        null
    }

    private fun pipeline(name: String, blend: BlendFunction, depth: Boolean, glow: Float?): RenderPipeline? {
        val b = base ?: return null
        return try {
            var builder = RenderPipeline.builder(b)
                .withLocation(HhaArius.id("pipeline/$name"))
                .withBlend(blend)
                .withDepthWrite(depth)
                .withShaderDefine("VFX_NO_FOG")
            if (glow != null) builder = builder.withShaderDefine("VFX_GLOW", glow)
            RenderPipelines.register(builder.build())
        } catch (t: Throwable) {
            HhaArius.LOGGER.warn("Pipeline {} nicht angelegt: {}", name, t.toString())
            null
        }
    }

    private val snowPipe = pipeline("snow", BlendFunction.TRANSLUCENT, false, null)
    private val moonPipe = pipeline("moon", BlendFunction.TRANSLUCENT, true, null)
    private val glowPipe = pipeline("moon_glow", BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA, false, 0.5f)

    private fun layer(name: String, pipe: RenderPipeline?, tex: Identifier, fallback: () -> RenderLayer): RenderLayer {
        if (pipe == null) return fallback()
        return try {
            RenderLayer.of("hha_arius_$name", RenderSetup.builder(pipe).texture("Sampler0", tex).expectedBufferSize(65536).build())
        } catch (t: Throwable) {
            fallback()
        }
    }

    val SNOW: RenderLayer by lazy { layer("snow", snowPipe, VfxLayers.GLOW) { VfxLayers.SMOKE_ALPHA } }
    val VEIL: RenderLayer by lazy { layer("veil", snowPipe, VfxLayers.SMOKE) { VfxLayers.SMOKE_ALPHA } }
    val MOON: RenderLayer by lazy { layer("moon", moonPipe, VfxLayers.SHARD) { VfxLayers.SHARD_ALPHA } }
    val MOON_GLOW: RenderLayer by lazy { layer("moon_glow", glowPipe, VfxLayers.GLOW) { VfxLayers.GLOW_ADD } }
    val MOON_FLARE: RenderLayer by lazy { layer("moon_flare", glowPipe, VfxLayers.FLARE) { VfxLayers.FLARE_ADD } }

    /** Erzwingt die Registrierung der Pipelines vor dem ersten Shader-Reload. */
    fun init() = Unit
}
