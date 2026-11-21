package dev.henny.hha_arius.arena

import dev.henny.hha_arius.HhaArius
import dev.henny.hha_arius.boss.Encounters
import io.netty.buffer.Unpooled
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.network.PacketByteBuf
import net.minecraft.network.RegistryByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.chunk.ChunkSection
import net.minecraft.world.chunk.SerializedChunk
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.math.hypot

/** Read-only block palettes for a distant chunk. No entities, block entity data, simulation tickets or client options. */
data class HighlandChunk(val pos: ChunkPos, val blocks: ByteArray) : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<HighlandChunk>(HhaArius.id("highland_blocks"))
        val CODEC: PacketCodec<RegistryByteBuf, HighlandChunk> = PacketCodec.of(
            { p, b -> b.writeLong(p.pos.toLong()); b.writeByteArray(p.blocks) },
            { b -> HighlandChunk(ChunkPos(b.readLong()), b.readByteArray(512 * 1024)) },
        )
    }
}

object HighlandView {
    private class Slot(val world: ServerWorld, val origin: BlockPos) {
        val ready = HashMap<ChunkPos, HighlandChunk>()
        val pending = LinkedHashSet<ChunkPos>()
        val reading = HashSet<ChunkPos>()
        val viewers = HashMap<UUID, LinkedHashSet<ChunkPos>>()

        init {
            val chunks = ArrayList<ChunkPos>()
            for (x in ((origin.x + FarPlan.X_MIN) shr 4)..((origin.x + FarPlan.X_MAX) shr 4))
                for (z in (FarPlan.Z_MIN shr 4)..(FarPlan.Z_MAX shr 4)) chunks.add(ChunkPos(x, z))
            // The spire and each peak get their summit first, then their flanks and the surrounding plain.
            pending.addAll(chunks.sortedBy { pos ->
                val x = pos.startX + 8.0 - origin.x
                val z = pos.startZ + 8.0
                minOf(hypot(x - ArenaLayout.SPIRE.x, z - ArenaLayout.SPIRE.z),
                    HuntRoute.MOUNTS.minOf { hypot(x - it.x, z - it.z) },
                    hypot(x - ArenaLayout.AERIE.x, z - ArenaLayout.AERIE.z))
            })
        }

        fun tick(players: List<ServerPlayerEntity>) {
            viewers.keys.retainAll(players.mapTo(HashSet()) { it.uuid })
            for (p in players) viewers.getOrPut(p.uuid) { LinkedHashSet(ready.keys) }
            repeat(minOf(6 - reading.size, pending.size)) {
                val pos = pending.first()
                pending.remove(pos)
                val loaded = world.chunkManager.getWorldChunk(pos.x, pos.z)
                if (loaded != null) {
                    // Palettes must be serialized on the server thread while the live chunk is stable.
                    val sections = loaded.sectionArray.mapIndexed { i, s -> loaded.sectionIndexToCoord(i) to s }
                    publish(pos, encode(sections))
                } else {
                    reading.add(pos)
                    val manager = world.chunkManager.chunkLoadingManager
                    manager.getNbt(pos).thenApplyAsync { data ->
                        val nbt = data.orElse(null) ?: return@thenApplyAsync null
                        val chunk = SerializedChunk.fromNbt(world, world.palettesFactory, nbt) ?: return@thenApplyAsync null
                        encode(chunk.sectionData().mapNotNull { s -> s.chunkSection()?.let { s.y() to it } })
                    }.whenComplete { blocks, error ->
                        world.server?.execute {
                            reading.remove(pos)
                            if (slots[origin.x] !== this) return@execute
                            if (error != null) {
                                if (!warned) HhaArius.LOGGER.warn("Cannot read a highland scenery chunk at {}", pos, error)
                                warned = true
                            } else if (blocks != null && pos !in pending) publish(pos, blocks)
                        }
                    }
                }
            }
            for (p in players) {
                val queue = viewers.getValue(p.uuid).iterator()
                var bytes = 0
                var count = 0
                while (queue.hasNext() && count < 8 && bytes < 96 * 1024) {
                    val packet = ready[queue.next()]
                    queue.remove()
                    if (packet == null) continue
                    ServerPlayNetworking.send(p, packet)
                    bytes += packet.blocks.size
                    count++
                }
            }
        }

        private fun publish(pos: ChunkPos, bytes: ByteArray) {
            ready[pos] = HighlandChunk(pos, bytes)
            for (queue in viewers.values) queue.add(pos)
        }
    }

    private val slots = HashMap<Int, Slot>()
    private var warned = false

    fun init() {
        PayloadTypeRegistry.playS2C().register(HighlandChunk.ID, HighlandChunk.CODEC)
        ServerTickEvents.END_SERVER_TICK.register { server ->
            val world = ArenaDimension.world(server) ?: return@register
            val groups = world.players.filter { ServerPlayNetworking.canSend(it, HighlandChunk.ID) }
                .groupBy { ArenaLayout.origin(Encounters.slotOf(it.x)).x }
            slots.keys.retainAll(groups.keys)
            for ((x, players) in groups) {
                slots.getOrPut(x) { Slot(world, BlockPos(x, ArenaLayout.BASE_Y, 0)) }.tick(players)
            }
        }
        ServerLifecycleEvents.SERVER_STOPPED.register { slots.clear(); warned = false }
    }

    /** Called after terrain construction or scripted edits. A cut replaces the same chunk the client already has. */
    fun changed(world: ServerWorld, x: Int, z: Int) {
        if (slots.isEmpty() || world.registryKey != ArenaDimension.WORLD) return
        val slot = slots[ArenaLayout.SLOT_SPACING * Encounters.slotOf(x.toDouble())] ?: return
        if (x - slot.origin.x !in FarPlan.X_MIN - 15..FarPlan.X_MAX + 15 || z !in FarPlan.Z_MIN - 15..FarPlan.Z_MAX + 15) return
        slot.pending.add(ChunkPos(x shr 4, z shr 4))
    }

    private fun encode(sections: List<Pair<Int, ChunkSection>>): ByteArray {
        val buf = PacketByteBuf(Unpooled.buffer())
        try {
            for ((sy, section) in sections) {
                if (sy < ((ArenaLayout.BASE_Y - 1) shr 4) || section.isEmpty) continue
                buf.writeByte(sy)
                section.blockStateContainer.writePacket(buf)
            }
            return ByteArray(buf.readableBytes()).also { buf.readBytes(it) }
        } finally {
            buf.release()
        }
    }
}
