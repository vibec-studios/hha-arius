package dev.henny.hha_arius.gear.art

import dev.henny.hha_arius.HhaArius
import dev.henny.hha_arius.gear.FrostBank
import dev.henny.hha_arius.gear.GearConfig
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.block.Block
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.network.RegistryByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.minecraft.particle.ParticleTypes
import net.minecraft.registry.tag.BlockTags
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvents
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.math.BlockPos
import java.util.UUID

/** Beginn eines Bauplans: [palette] = rohe Block-Zustands-IDs, [count] = Zahl der Blöcke, die folgen. */
data class BlueprintStartC2S(val count: Int, val palette: IntArray) : CustomPayload {
    override fun getId(): CustomPayload.Id<out CustomPayload> = ID

    companion object {
        val ID = CustomPayload.Id<BlueprintStartC2S>(HhaArius.id("blueprint_start"))
        val CODEC: PacketCodec<RegistryByteBuf, BlueprintStartC2S> = PacketCodec.of(
            { v, buf -> buf.writeVarInt(v.count); buf.writeVarInt(v.palette.size); for (i in v.palette) buf.writeVarInt(i) },
            { buf -> BlueprintStartC2S(buf.readVarInt(), IntArray(buf.readVarInt().coerceIn(0, 65536)) { buf.readVarInt() }) },
        )
    }
}

/** Ein Teil der Blöcke: absolute Positionen und Index in die Palette. */
data class BlueprintPartC2S(val positions: LongArray, val indices: IntArray) : CustomPayload {
    override fun getId(): CustomPayload.Id<out CustomPayload> = ID

    companion object {
        val ID = CustomPayload.Id<BlueprintPartC2S>(HhaArius.id("blueprint_part"))
        val CODEC: PacketCodec<RegistryByteBuf, BlueprintPartC2S> = PacketCodec.of(
            { v, buf ->
                buf.writeVarInt(v.positions.size)
                for (i in v.positions.indices) {
                    buf.writeLong(v.positions[i])
                    buf.writeVarInt(v.indices[i])
                }
            },
            { buf ->
                val n = buf.readVarInt().coerceIn(0, 8192)
                val p = LongArray(n)
                val x = IntArray(n)
                for (i in 0 until n) {
                    p[i] = buf.readLong()
                    x[i] = buf.readVarInt()
                }
                BlueprintPartC2S(p, x)
            },
        )
    }
}

/**
 * Litematica-Baupläne mit Resonanz aus dem Frostspeicher bauen (seit 1.4.2, Crix: „mit dieser Manabank auch Litematica-
 * Dateien reinmachen; dafür braucht man unterschiedlich viel Mana, aber durchschnittlich schon mehr“). Der Client liest
 * die `.litematic`-Datei aus seinem `schematics`-Ordner, dreht sie in Blickrichtung und schickt die Blöcke; der Server
 * prüft, berechnet die Kosten ([cost]), zieht sie aus dem Speicher ab und setzt **echte Blöcke** Schicht für Schicht
 * von unten nach oben – nur in Luft oder ersetzbare Blöcke, nie über Bestehendes, nie in der Arena. Was nicht gesetzt
 * werden kann, wird erstattet. Verbotene Blöcke (Befehlsblöcke, Grundgestein, Portale, Spawner …) und Flüssigkeiten
 * werden übersprungen.
 */
object Blueprints {

    private class Upload(val palette: List<BlockState>, val count: Int) {
        val positions = ArrayList<Long>()
        val indices = ArrayList<Int>()
    }

    private class Job(val world: ServerWorld, val owner: UUID, val queue: ArrayDeque<Pair<BlockPos, BlockState>>) {
        var placed = 0
        var refund = 0.0
    }

    private val uploads = HashMap<UUID, Upload>()
    private val jobs = ArrayList<Job>()

    fun init() {
        PayloadTypeRegistry.playC2S().register(BlueprintStartC2S.ID, BlueprintStartC2S.CODEC)
        PayloadTypeRegistry.playC2S().register(BlueprintPartC2S.ID, BlueprintPartC2S.CODEC)
        ServerPlayNetworking.registerGlobalReceiver(BlueprintStartC2S.ID) { p, ctx ->
            val player = ctx.player()
            if (jobs.any { it.owner == player.uuid }) {
                player.sendMessage(Text.translatable("hha_arius.msg.blueprint_busy").formatted(Formatting.RED), false)
                return@registerGlobalReceiver
            }
            val max = GearConfig.num("blueprint_max_blocks").toInt()
            if (p.count <= 0 || p.count > max) {
                player.sendMessage(Text.translatable("hha_arius.msg.blueprint_too_big", p.count, max).formatted(Formatting.RED), false)
                return@registerGlobalReceiver
            }
            uploads[player.uuid] = Upload(p.palette.map { Block.getStateFromRawId(it) }, p.count)
        }
        ServerPlayNetworking.registerGlobalReceiver(BlueprintPartC2S.ID) { p, ctx ->
            val player = ctx.player()
            val up = uploads[player.uuid] ?: return@registerGlobalReceiver
            for (i in p.positions.indices) {
                if (up.positions.size >= up.count) break
                up.positions += p.positions[i]
                up.indices += p.indices[i]
            }
            if (up.positions.size >= up.count) {
                uploads.remove(player.uuid)
                finish(player, up)
            }
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ -> uploads.remove(handler.player.uuid) }
        ServerTickEvents.END_SERVER_TICK.register { tick(it) }
    }

    /** Darf dieser Zustand gebaut werden? */
    fun allowed(state: BlockState): Boolean {
        if (state.isAir || !state.fluidState.isEmpty && state.block is net.minecraft.block.FluidBlock) return false
        val b = state.block
        return b !in FORBIDDEN && !state.isIn(BlockTags.PORTALS)
    }

    private val FORBIDDEN: Set<Block> by lazy {
        setOf(
            Blocks.COMMAND_BLOCK, Blocks.CHAIN_COMMAND_BLOCK, Blocks.REPEATING_COMMAND_BLOCK, Blocks.STRUCTURE_BLOCK,
            Blocks.STRUCTURE_VOID, Blocks.JIGSAW, Blocks.BARRIER, Blocks.LIGHT, Blocks.BEDROCK, Blocks.END_PORTAL_FRAME,
            Blocks.END_PORTAL, Blocks.END_GATEWAY, Blocks.NETHER_PORTAL, Blocks.SPAWNER, Blocks.TRIAL_SPAWNER, Blocks.VAULT,
            Blocks.REINFORCED_DEEPSLATE, Blocks.DRAGON_EGG, Blocks.TEST_BLOCK, Blocks.TEST_INSTANCE_BLOCK,
        )
    }

    private val VALUABLE: Set<Block> by lazy {
        setOf(Blocks.ANCIENT_DEBRIS, Blocks.BEACON, Blocks.CONDUIT, Blocks.ENCHANTING_TABLE, Blocks.NETHERITE_BLOCK,
            Blocks.DIAMOND_BLOCK, Blocks.EMERALD_BLOCK, Blocks.GOLD_BLOCK, Blocks.IRON_BLOCK, Blocks.LAPIS_BLOCK,
            Blocks.REDSTONE_BLOCK, Blocks.HEAVY_CORE, Blocks.RESPAWN_ANCHOR, Blocks.ENDER_CHEST, Blocks.BUDDING_AMETHYST)
    }

    /**
     * Resonanz für einen Block: Grundwert 2 plus die halbe Härte (Stein ≈ 2,75, Tiefenschiefer ≈ 3,5, Obsidian ≈ 27),
     * Kostbares (Erze, Metall- und Edelsteinblöcke, Leuchtfeuer …) das 25-Fache, Shulkerkisten das 10-Fache; dazu der
     * Faktor `blueprint_cost_mult`. Ein Bauplan kostet so im Schnitt rund drei Resonanz je Block.
     */
    fun cost(state: BlockState, mult: Double): Double {
        if (!allowed(state)) return 0.0
        val b = state.block
        var c = 2.0 + b.hardness.coerceIn(0f, 50f) * 0.5
        if (b in VALUABLE || state.isIn(BlockTags.DIAMOND_ORES) || state.isIn(BlockTags.EMERALD_ORES) || state.isIn(BlockTags.GOLD_ORES) ||
            state.isIn(BlockTags.IRON_ORES) || state.isIn(BlockTags.LAPIS_ORES) || state.isIn(BlockTags.REDSTONE_ORES) ||
            state.isIn(BlockTags.COPPER_ORES) || state.isIn(BlockTags.COAL_ORES)) c *= 25.0
        else if (state.isIn(BlockTags.SHULKER_BOXES)) c *= 10.0
        return c * mult
    }

    private fun finish(player: ServerPlayerEntity, up: Upload) {
        val world = player.entityWorld
        if (world.registryKey == dev.henny.hha_arius.arena.ArenaDimension.WORLD) {
            player.sendMessage(Text.translatable("hha_arius.msg.blueprint_arena").formatted(Formatting.RED), false)
            return
        }
        val mult = GearConfig.num("blueprint_cost_mult")
        val list = ArrayList<Pair<BlockPos, BlockState>>(up.count)
        var total = 0.0
        val me = player.blockPos
        for (i in up.positions.indices) {
            val pos = BlockPos.fromLong(up.positions[i])
            if (pos.getManhattanDistance(me) > 160 || !world.isInBuildLimit(pos)) continue
            val state = up.palette.getOrNull(up.indices[i]) ?: continue
            if (!allowed(state)) continue
            list += pos to state
            total += cost(state, mult)
        }
        if (list.isEmpty()) {
            player.sendMessage(Text.translatable("hha_arius.msg.blueprint_empty").formatted(Formatting.GRAY), false)
            return
        }
        val price = kotlin.math.ceil(total).toLong()
        val have = FrostBank.total(player)
        if (!FrostBank.withdraw(player, price)) {
            player.sendMessage(Text.translatable("hha_arius.msg.blueprint_no_mana", price, have).formatted(Formatting.RED), false)
            return
        }
        list.sortWith(compareBy<Pair<BlockPos, BlockState>> { it.first.y }.thenBy { it.first.getSquaredDistance(me) })
        jobs += Job(world, player.uuid, ArrayDeque(list))
        player.sendMessage(Text.translatable("hha_arius.msg.blueprint_start", list.size, price).formatted(Formatting.AQUA), false)
        world.playSound(null, player.blockPos, SoundEvents.BLOCK_BEACON_ACTIVATE, SoundCategory.PLAYERS, 0.8f, 1.4f)
    }

    private fun tick(server: MinecraftServer) {
        if (jobs.isEmpty()) return
        val per = GearConfig.num("blueprint_blocks_per_tick").toInt().coerceAtLeast(1)
        val mult = GearConfig.num("blueprint_cost_mult")
        val it = jobs.iterator()
        while (it.hasNext()) {
            val job = it.next()
            val owner = server.playerManager.getPlayer(job.owner)
            var n = 0
            while (n < per && job.queue.isNotEmpty()) {
                val (pos, state) = job.queue.removeFirst()
                n++
                if (!job.world.isChunkLoaded(pos)) {
                    job.refund += cost(state, mult)
                    continue
                }
                val cur = job.world.getBlockState(pos)
                val mayEdit = owner == null || job.world.canEntityModifyAt(owner, pos)
                if (!cur.isReplaceable || !mayEdit) {
                    job.refund += cost(state, mult)
                    continue
                }
                job.world.setBlockState(pos, state, Block.NOTIFY_LISTENERS or Block.FORCE_STATE)
                job.placed++
                if (job.placed % 40 == 0) {
                    job.world.spawnParticles(ParticleTypes.END_ROD, pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, 3, 0.3, 0.3, 0.3, 0.02)
                    job.world.playSound(null, pos, SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.BLOCKS, 0.4f, 1.2f + (job.placed % 7) * 0.08f)
                }
            }
            if (job.queue.isEmpty()) {
                it.remove()
                val back = job.refund.toInt()
                if (owner != null) {
                    if (back > 0) FrostBank.deposit(owner, back)
                    owner.sendMessage(Text.translatable("hha_arius.msg.blueprint_done", job.placed, back).formatted(Formatting.AQUA), false)
                }
            }
        }
    }
}
