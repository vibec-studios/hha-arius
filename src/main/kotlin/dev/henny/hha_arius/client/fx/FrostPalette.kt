package dev.henny.hha_arius.client.fx

import dev.henny.hha.client.vfx.Palette
import dev.henny.hha.logic.Vfx
import dev.henny.hha_arius.arena.ArenaDimension
import net.minecraft.client.MinecraftClient

/**
 * Die Farben der Kampfeffekte in Eirholm. Die HHA-Stile mischen Gold, Eisblau und Violett in einer Spur, das ergibt auf
 * weißem Schnee einen Regenbogen. Hier gibt es für die Stile von Arius und Solvane nur Weiß, Eisblau, Tiefblau und Gold.
 * Andere Stile (zum Beispiel Fähigkeiten der Spieler) behalten ihre Farben.
 */
object FrostPalette {

    private val FROST = Palette(0xFFF4FCFF.toInt(), 0xFF8AD4F5.toInt(), 0xFF1C5A8A.toInt(), 0xFFCDEEFF.toInt())
    private val GOLD = Palette(0xFFFFF8E0.toInt(), 0xFFFFC84A.toInt(), 0xFF8A5A08.toInt(), 0xFFFFE9A8.toInt())

    private var stockTelegraph = false

    @JvmStatic
    fun withStockTelegraph(action: Runnable) {
        val previous = stockTelegraph
        stockTelegraph = true
        try {
            action.run()
        } finally {
            stockTelegraph = previous
        }
    }

    @JvmStatic
    fun forStyle(style: Vfx.Style): Palette? {
        if (stockTelegraph) return null
        val world = MinecraftClient.getInstance().world ?: return null
        if (world.registryKey != ArenaDimension.WORLD) return null
        return when (style) {
            Vfx.Style.HEAVEN, Vfx.Style.STORM, Vfx.Style.SOUL -> FROST
            Vfx.Style.GOLD -> GOLD
            else -> null
        }
    }
}
