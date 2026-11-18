package dev.henny.hha_arius.arena

import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.server.MinecraftServer
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.Identifier
import net.minecraft.world.World
import net.minecraft.world.dimension.DimensionType

/**
 * Die Arena-Dimension `hha_arius:highland` (seit 1.4.2; Datenpaket `data/hha_arius/dimension/highland.json`): ein
 * endloses Schneefeld aus dem Flach-Generator (Stein bis 154, Schnee bis 159 = Lauffläche − 1 jeder Instanz), Biom
 * `hha_arius:eirholm`, feste Tageszeit. Die alte leere Dimension `hha_arius:eirholm` bleibt nur für alte Welten
 * bestehen und wird nicht mehr benutzt. Wird von Minecraft beim Laden der Welt angelegt, kein Code-Register nötig.
 */
object ArenaDimension {
    val ID: Identifier = Identifier.of("hha_arius", "highland")
    val WORLD: RegistryKey<World> = RegistryKey.of(RegistryKeys.WORLD, ID)
    val TYPE: RegistryKey<DimensionType> = RegistryKey.of(RegistryKeys.DIMENSION_TYPE, Identifier.of("hha_arius", "eirholm"))

    fun world(server: MinecraftServer): ServerWorld? = server.getWorld(WORLD)
}
