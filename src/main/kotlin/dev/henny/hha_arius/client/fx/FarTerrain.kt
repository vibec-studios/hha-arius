package dev.henny.hha_arius.client.fx

import dev.henny.hha.client.vfx.VfxDraw

/**
 * Replaced by [HighlandViewClient], which renders real server-streamed distant mountain blocks instead of a synthetic
 * heightmap fog silhouette. Retained as a no-op stub for binary compatibility.
 */
object FarTerrain {
    fun init() = Unit
    fun render(d: VfxDraw) = Unit
}
