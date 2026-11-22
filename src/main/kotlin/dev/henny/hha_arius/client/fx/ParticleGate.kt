package dev.henny.hha_arius.client.fx

import dev.henny.hha_arius.HhaArius
import dev.henny.hha_arius.arena.ArenaDimension
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.MinecraftClient
import net.minecraft.particle.ParticleEffect
import net.minecraft.particle.ParticleTypes
import net.minecraft.registry.Registries
import net.minecraft.util.Identifier

/**
 * Welche Partikel im Kampf trotz des Sodium-Extra-Hauptschalters entstehen: nur in Eirholm, nur HHA, HHA Arius und die
 * Arten, die der Kampf selbst benutzt. Was der Spieler in Sodium Extra einzeln abgewählt hat, bleibt aus. `/ariusc fx 0`
 * schaltet den Umweg ab, und je Tick gibt es ein festes Budget, weil der Spieler Partikel meist für FPS abschaltet.
 */
object ParticleGate {

    private const val MAX_PER_TICK = 48

    private val FIGHT_TYPES: Set<Identifier> = listOf(
        ParticleTypes.SNOWFLAKE, ParticleTypes.END_ROD, ParticleTypes.BLOCK, ParticleTypes.CLOUD,
        ParticleTypes.WHITE_ASH, ParticleTypes.FALLING_DUST, ParticleTypes.GUST, ParticleTypes.CRIT,
    ).mapNotNull { Registries.PARTICLE_TYPE.getId(it) }.toSet()

    private val present = FabricLoader.getInstance().isModLoaded("sodium-extra")

    private var checked = false
    private var settings: Any? = null
    private var otherMap: java.lang.reflect.Field? = null

    private var tick = -1L
    private var spent = 0

    @JvmStatic
    fun bypass(parameters: ParticleEffect): Boolean {
        if (!present || AriusClientConfig.fx <= 0.01f) return false
        val world = MinecraftClient.getInstance().world ?: return false
        if (world.registryKey != ArenaDimension.WORLD) return false
        val id = Registries.PARTICLE_TYPE.getId(parameters.type) ?: return false
        if (id.namespace != "hha" && id.namespace != HhaArius.MOD_ID && id !in FIGHT_TYPES) return false
        if (switchedOff(id)) return false
        if (world.time != tick) {
            tick = world.time
            spent = 0
        }
        return spent++ < MAX_PER_TICK
    }

    /** Einzelschalter von Sodium Extra. Ändert sich dessen Aufbau, gilt nur noch die feste Liste. */
    private fun switchedOff(id: Identifier): Boolean {
        if (!checked) {
            checked = true
            runCatching {
                val mod = Class.forName("me.flashyreese.mods.sodiumextra.client.SodiumExtraClientMod")
                val options = mod.getMethod("options").invoke(null)
                settings = options.javaClass.getField("particleSettings").get(options)
                otherMap = settings!!.javaClass.getDeclaredField("otherMap").apply { isAccessible = true }
            }.onFailure { HhaArius.LOGGER.debug("Sodium Extra particle switches unavailable: {}", it.toString()) }
        }
        val field = otherMap ?: return false
        val map = runCatching { field.get(settings) as? Map<*, *> }.getOrNull() ?: return false
        return map[id] == false
    }
}
