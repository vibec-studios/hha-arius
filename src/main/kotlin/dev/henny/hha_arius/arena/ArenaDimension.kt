package dev.henny.hha_arius.arena

import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.server.MinecraftServer
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.Identifier
import net.minecraft.world.World
import net.minecraft.world.dimension.DimensionType

/**
 * Die Arena-Dimension `hha_arius:eirholm` (Datenpaket: `data/hha_arius/dimension/eirholm.json`, leerer Flach-Generator,
 * Biom `hha_arius:eirholm`, feste Tageszeit). Wird von Minecraft beim Laden der Welt angelegt, kein Code-Register nötig.
 */
object ArenaDimension {
    val ID: Identifier = Identifier.of("hha_arius", "eirholm")
    val WORLD: RegistryKey<World> = RegistryKey.of(RegistryKeys.WORLD, ID)
    val TYPE: RegistryKey<DimensionType> = RegistryKey.of(RegistryKeys.DIMENSION_TYPE, ID)

    fun world(server: MinecraftServer): ServerWorld? = server.getWorld(WORLD)
}
