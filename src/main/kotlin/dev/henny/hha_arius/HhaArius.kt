package dev.henny.hha_arius

import dev.henny.hha_arius.boss.AriusEntity
import dev.henny.hha_arius.boss.NivarisEntity
import dev.henny.hha_arius.boss.NivarisHeadEntity
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.`object`.builder.v1.entity.FabricDefaultAttributeRegistry
import net.minecraft.entity.Entity
import net.minecraft.entity.EntityType
import net.minecraft.entity.SpawnGroup
import net.minecraft.registry.Registries
import net.minecraft.registry.Registry
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.util.Identifier
import net.minecraft.world.World
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Fabric-Haupteinstieg: Dinge außerhalb der HHA-Addon-API (Entities, Netzwerk, Befehle, Kampf-Ereignisse). Items, Set,
 * Abilities und Config laufen über den HHA-Entrypoint [AriusAddon].
 */
object HhaArius : ModInitializer {

    const val MOD_ID = "hha_arius"

    @JvmField
    val LOGGER: Logger = LoggerFactory.getLogger(MOD_ID)

    fun id(path: String): Identifier = Identifier.of(MOD_ID, path)

    override fun onInitialize() {
        checkHhaBuild()
        AriusSounds.init()
        AriusEntities.init()
        dev.henny.hha_arius.boss.Ledgers.init()
        dev.henny.hha_arius.net.AriusNet.init()
        dev.henny.hha_arius.boss.Encounters.init()
        dev.henny.hha_arius.boss.AriusCommands.init()
        dev.henny.hha_arius.boss.DragonAim.init()
        dev.henny.hha_arius.boss.Qte.init()
        dev.henny.hha_arius.boss.BossGuard.init()
    }

    /**
     * Das Addon nutzt HHAs Ansage-System ([dev.henny.hha.logic.boss.Telegraph]) aus der Boss-Überarbeitung. Ältere Jars
     * mit derselben Versionsnummer haben es nicht: dann lieber sofort klar scheitern als mitten im Kampf.
     */
    private fun checkHhaBuild() {
        try {
            Class.forName("dev.henny.hha.logic.boss.Telegraph")
        } catch (e: ClassNotFoundException) {
            throw IllegalStateException(
                "HHA Arius braucht H&H Armors 0.8.0 mit der Boss-Überarbeitung (Telegraph). Das installierte HHA ist älter.", e,
            )
        }
    }
}

object AriusEntities {

    val ARIUS: EntityType<AriusEntity> = register("arius", 0.7f, 1.95f, ::AriusEntity)
    val NIVARIS: EntityType<NivarisEntity> = register("nivaris", 4.5f, 3.6f, ::NivarisEntity)
    val NIVARIS_HEAD: EntityType<dev.henny.hha_arius.boss.NivarisHeadEntity> = register("nivaris_head", 1.9f, 1.9f, ::NivarisHeadEntity)

    private fun <T : Entity> register(name: String, width: Float, height: Float, factory: (EntityType<T>, World) -> T): EntityType<T> {
        val key: RegistryKey<EntityType<*>> = RegistryKey.of(RegistryKeys.ENTITY_TYPE, HhaArius.id(name))
        return Registry.register(
            Registries.ENTITY_TYPE, key,
            EntityType.Builder.create({ type: EntityType<T>, world -> factory(type, world) }, SpawnGroup.MONSTER)
                .dimensions(width, height)
                .maxTrackingRange(16)
                .trackingTickInterval(1)
                .makeFireImmune()
                .build(key),
        )
    }

    fun init() {
        FabricDefaultAttributeRegistry.register(ARIUS, AriusEntity.attributes())
        FabricDefaultAttributeRegistry.register(NIVARIS, NivarisEntity.attributes())
        FabricDefaultAttributeRegistry.register(NIVARIS_HEAD, dev.henny.hha_arius.boss.NivarisHeadEntity.attributes())
    }
}
