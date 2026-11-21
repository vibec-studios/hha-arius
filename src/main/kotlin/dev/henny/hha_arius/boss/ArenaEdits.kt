package dev.henny.hha_arius.boss

import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockPos

/**
 * Jede Blockänderung des Kampfes läuft hierüber: Der Originalzustand wird beim ersten Ändern gemerkt, am Ende stellt
 * [restore] alles wieder her. Gesetzt wird ohne Nachbar-Updates (keine Kettenreaktionen, kein Abfallen).
 */
class ArenaEdits(private val world: ServerWorld) {

    private val originals = LinkedHashMap<BlockPos, BlockState>()

    val size: Int get() = originals.size

    fun set(pos: BlockPos, state: BlockState) {
        val key = pos.toImmutable()
        val current = world.getBlockState(key)
        if (current == state) return
        originals.putIfAbsent(key, current)
        world.setBlockState(key, state, FLAGS)
        dev.henny.hha_arius.arena.HighlandView.changed(world, key.x, key.z)
    }

    fun clear(pos: BlockPos) = set(pos, Blocks.AIR.defaultState)

    /** Setzt nur, wo gerade Luft ist (Eiskämme, Wände, Trümmer). Gibt zurück, was gesetzt wurde. */
    fun fill(blocks: Iterable<Pair<BlockPos, BlockState>>): List<BlockPos> {
        val placed = ArrayList<BlockPos>()
        for ((pos, state) in blocks) {
            if (!world.getBlockState(pos).isAir) continue
            set(pos, state)
            placed.add(pos.toImmutable())
        }
        return placed
    }

    /** Macht die Änderung an diesen Positionen wieder rückgängig (z. B. eine Eiswand verschwindet). */
    fun revert(positions: Iterable<BlockPos>) {
        for (pos in positions) {
            val original = originals[pos] ?: continue
            world.setBlockState(pos, original, FLAGS)
            dev.henny.hha_arius.arena.HighlandView.changed(world, pos.x, pos.z)
            originals.remove(pos)
        }
    }

    /** Alles wiederherstellen. */
    fun restore() {
        for ((pos, state) in originals) {
            world.setBlockState(pos, state, FLAGS)
            dev.henny.hha_arius.arena.HighlandView.changed(world, pos.x, pos.z)
        }
        originals.clear()
    }

    companion object {
        /** NOTIFY_LISTENERS | FORCE_STATE: Client bekommt die Änderung, Nachbarn nicht. */
        const val FLAGS = 2 or 16
    }
}
