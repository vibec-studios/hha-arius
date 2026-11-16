package dev.henny.hha_arius.gear.art

import dev.henny.hha_arius.HhaArius
import net.minecraft.block.AbstractBlock
import net.minecraft.block.Block
import net.minecraft.block.BlockState
import net.minecraft.block.piston.PistonBehavior
import net.minecraft.registry.Registries
import net.minecraft.registry.Registry
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.BlockSoundGroup
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.random.Random

/**
 * Bauwerke der Zauber (Eiswand, Plattform, Brücke) als eigene Funktionsblöcke: kein Item, keine Drops, nicht
 * verschiebbar, und sie verschwinden von selbst über einen geplanten Block-Tick (der mit dem Chunk gespeichert wird,
 * also auch nach Neustart und in entladenen Chunks sicher). Gesetzt werden sie nur in Luft.
 */
class ConstructBlock(settings: Settings) : Block(settings) {

    override fun scheduledTick(state: BlockState, world: ServerWorld, pos: BlockPos, random: Random) {
        world.removeBlock(pos, false)
    }
}

object Constructs {

    lateinit var FROST: Block private set
    lateinit var YANG: Block private set

    private var registered = false

    fun registerBlocks() {
        if (registered) return
        registered = true
        FROST = register("frost_construct", 0, BlockSoundGroup.GLASS)
        YANG = register("yang_construct", 11, BlockSoundGroup.AMETHYST_BLOCK)
    }

    private fun register(name: String, light: Int, sounds: BlockSoundGroup): Block {
        val id = HhaArius.id(name)
        val settings = AbstractBlock.Settings.create()
            .registryKey(RegistryKey.of(RegistryKeys.BLOCK, id))
            .strength(0.6f, 1.0f)
            .dropsNothing()
            .nonOpaque()
            .pistonBehavior(PistonBehavior.BLOCK)
            .luminance { light }
            .sounds(sounds)
            .allowsSpawning { _, _, _, _ -> false }
            .solidBlock { _, _, _ -> false }
            .suffocates { _, _, _ -> false }
            .blockVision { _, _, _ -> false }
        return Registry.register(Registries.BLOCK, id, ConstructBlock(settings))
    }

    fun isConstruct(state: BlockState): Boolean = registered && (state.isOf(FROST) || state.isOf(YANG))
}
