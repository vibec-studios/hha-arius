package dev.henny.hha_arius.gear.client

import com.mojang.brigadier.arguments.StringArgumentType
import dev.henny.hha.client.vfx.VfxDraw
import dev.henny.hha.client.vfx.VfxLayers
import dev.henny.hha.client.vfx.withAlpha
import dev.henny.hha_arius.gear.art.BlueprintPartC2S
import dev.henny.hha_arius.gear.art.BlueprintStartC2S
import dev.henny.hha_arius.gear.art.Blueprints
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents
import net.minecraft.block.Block
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.client.MinecraftClient
import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.NbtSizeTracker
import net.minecraft.registry.Registries
import net.minecraft.state.property.Property
import net.minecraft.text.Text
import net.minecraft.util.BlockRotation
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import net.minecraft.util.math.Vec3d
import java.nio.file.Files
import java.nio.file.Path

/**
 * Client-Seite der Litematica-Baupläne ([Blueprints]):
 * * `/ariusc schematic list` – Dateien im Ordner `schematics` (Litematica-Standard),
 * * `/ariusc schematic build <name>` – liest die Datei, dreht sie in Blickrichtung, legt sie vor den Spieler und zeigt
 *   den Umriss als Lichtkasten samt Kosten,
 * * `/ariusc schematic confirm` – schickt den Bauplan an den Server (der zieht die Kosten aus dem Frostspeicher ab),
 * * `/ariusc schematic cancel` – verwirft die Vorschau.
 */
object BlueprintClient {

    private class Prepared(val name: String, val blocks: List<Pair<BlockPos, BlockState>>, val min: BlockPos, val max: BlockPos, val cost: Long)

    private var prepared: Prepared? = null

    private fun folder(): Path = MinecraftClient.getInstance().runDirectory.toPath().resolve("schematics")

    private fun files(): List<String> {
        val dir = folder()
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.walk(dir, 3).use { s ->
            s.filter { it.toString().endsWith(".litematic") }.map { dir.relativize(it).toString().replace('\\', '/').removeSuffix(".litematic") }.sorted().toList()
        }
    }

    fun init() {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(
                ClientCommandManager.literal("ariusc").then(
                    ClientCommandManager.literal("schematic")
                        .then(ClientCommandManager.literal("list").executes { ctx ->
                            val list = files()
                            if (list.isEmpty()) ctx.source.sendFeedback(Text.translatable("hha_arius.blueprint.none", folder().toString()).formatted(Formatting.GRAY))
                            else ctx.source.sendFeedback(Text.translatable("hha_arius.blueprint.list", list.joinToString(", ")).formatted(Formatting.AQUA))
                            1
                        })
                        .then(ClientCommandManager.literal("build").then(
                            ClientCommandManager.argument("name", StringArgumentType.greedyString())
                                .suggests { _, b -> files().forEach { b.suggest(it) }; b.buildFuture() }
                                .executes { ctx ->
                                    prepare(StringArgumentType.getString(ctx, "name"))
                                    1
                                },
                        ))
                        .then(ClientCommandManager.literal("confirm").executes { confirm(); 1 })
                        .then(ClientCommandManager.literal("cancel").executes {
                            prepared = null
                            msg(Text.translatable("hha_arius.blueprint.cancelled").formatted(Formatting.GRAY))
                            1
                        }),
                ),
            )
        }
        WorldRenderEvents.END_MAIN.register { context -> render(context.consumers()) }
    }

    private fun msg(t: Text) {
        MinecraftClient.getInstance().player?.sendMessage(t, false)
    }

    // --- Lesen ---------------------------------------------------------------------------------------------------------

    private fun prepare(name: String) {
        val client = MinecraftClient.getInstance()
        val player = client.player ?: return
        val file = folder().resolve("$name.litematic")
        if (!Files.isRegularFile(file)) {
            msg(Text.translatable("hha_arius.blueprint.missing", name).formatted(Formatting.RED))
            return
        }
        val raw = try {
            read(file)
        } catch (e: Exception) {
            msg(Text.translatable("hha_arius.blueprint.broken", e.message ?: e.javaClass.simpleName).formatted(Formatting.RED))
            return
        }
        if (raw.isEmpty()) {
            msg(Text.translatable("hha_arius.blueprint.empty_file").formatted(Formatting.GRAY))
            return
        }
        // Drehen in Blickrichtung (Bauplan-+z zeigt dorthin, wohin der Spieler schaut) und vor ihn legen.
        val facing = player.horizontalFacing
        val rot = when (facing) {
            Direction.SOUTH -> BlockRotation.NONE
            Direction.WEST -> BlockRotation.CLOCKWISE_90
            Direction.NORTH -> BlockRotation.CLOCKWISE_180
            else -> BlockRotation.COUNTERCLOCKWISE_90
        }
        val turned = raw.map { (p, s) -> rotate(p, rot) to s.rotate(rot) }
        var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var minZ = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE; var maxZ = Int.MIN_VALUE; var maxY = Int.MIN_VALUE
        for ((p, _) in turned) {
            minX = minOf(minX, p.x); minY = minOf(minY, p.y); minZ = minOf(minZ, p.z)
            maxX = maxOf(maxX, p.x); maxY = maxOf(maxY, p.y); maxZ = maxOf(maxZ, p.z)
        }
        val sizeAlong = if (facing.axis == Direction.Axis.Z) maxZ - minZ + 1 else maxX - minX + 1
        val feet = player.blockPos
        val center = Vec3d.ofCenter(feet).add(Vec3d.of(facing.vector).multiply(2.0 + sizeAlong / 2.0))
        val shift = BlockPos(
            kotlin.math.floor(center.x - (minX + maxX + 1) / 2.0).toInt(),
            feet.y - minY,
            kotlin.math.floor(center.z - (minZ + maxZ + 1) / 2.0).toInt(),
        )
        val placed = turned.map { (p, s) -> p.add(shift) to s }
        val max = 20000
        if (placed.size > max) {
            msg(Text.translatable("hha_arius.msg.blueprint_too_big", placed.size, max).formatted(Formatting.RED))
            return
        }
        val cost = kotlin.math.ceil(placed.sumOf { Blueprints.cost(it.second, 1.0) }).toLong()
        prepared = Prepared(name, placed, BlockPos(minX, minY, minZ).add(shift), BlockPos(maxX, maxY, maxZ).add(shift), cost)
        msg(Text.translatable("hha_arius.blueprint.ready", name, placed.size, cost).formatted(Formatting.AQUA))
    }

    /** Liest alle Regionen einer `.litematic`-Datei (gzip-NBT, gepackte Paletten-Indizes) als relative Positionen. */
    private fun read(file: Path): List<Pair<BlockPos, BlockState>> {
        val root = NbtIo.readCompressed(file, NbtSizeTracker.ofUnlimitedBytes())
        val regions = root.getCompoundOrEmpty("Regions")
        val out = ArrayList<Pair<BlockPos, BlockState>>()
        for (key in regions.keys) {
            val r = regions.getCompoundOrEmpty(key)
            val pos = r.getCompoundOrEmpty("Position")
            val size = r.getCompoundOrEmpty("Size")
            val sx = size.getInt("x", 0); val sy = size.getInt("y", 0); val sz = size.getInt("z", 0)
            val ax = kotlin.math.abs(sx); val ay = kotlin.math.abs(sy); val az = kotlin.math.abs(sz)
            if (ax == 0 || ay == 0 || az == 0) continue
            val ox = pos.getInt("x", 0) + if (sx < 0) sx + 1 else 0
            val oy = pos.getInt("y", 0) + if (sy < 0) sy + 1 else 0
            val oz = pos.getInt("z", 0) + if (sz < 0) sz + 1 else 0
            val paletteNbt = r.getList("BlockStatePalette").orElse(null) ?: continue
            val palette = (0 until paletteNbt.size).map { state(paletteNbt.getCompoundOrEmpty(it)) }
            val longs = r.getLongArray("BlockStates").orElse(null) ?: continue
            val bits = maxOf(2, 32 - Integer.numberOfLeadingZeros(palette.size - 1))
            val mask = (1L shl bits) - 1
            val volume = ax.toLong() * ay * az
            for (i in 0 until volume) {
                val start = i * bits
                val li = (start ushr 6).toInt()
                val off = (start and 63).toInt()
                if (li >= longs.size) break
                var v = longs[li] ushr off
                if (off + bits > 64 && li + 1 < longs.size) v = v or (longs[li + 1] shl (64 - off))
                val idx = (v and mask).toInt()
                val st = palette.getOrNull(idx) ?: continue
                if (st.isAir || st.isOf(Blocks.STRUCTURE_VOID)) continue
                val x = (i % ax).toInt()
                val z = ((i / ax) % az).toInt()
                val y = (i / (ax.toLong() * az)).toInt()
                out += BlockPos(ox + x, oy + y, oz + z) to st
            }
        }
        return out
    }

    private fun state(nbt: NbtCompound): BlockState {
        val id = Identifier.tryParse(nbt.getString("Name", "minecraft:air")) ?: return Blocks.AIR.defaultState
        val block = Registries.BLOCK.get(id)
        var st = block.defaultState
        val props = nbt.getCompoundOrEmpty("Properties")
        for (k in props.keys) {
            val prop = block.stateManager.getProperty(k) ?: continue
            st = with(st, prop, props.getString(k, ""))
        }
        return st
    }

    private fun <T : Comparable<T>> with(state: BlockState, prop: Property<T>, value: String): BlockState =
        prop.parse(value).map { state.with(prop, it) }.orElse(state)

    private fun rotate(p: BlockPos, r: BlockRotation): BlockPos = when (r) {
        BlockRotation.CLOCKWISE_90 -> BlockPos(-p.z, p.y, p.x)
        BlockRotation.CLOCKWISE_180 -> BlockPos(-p.x, p.y, -p.z)
        BlockRotation.COUNTERCLOCKWISE_90 -> BlockPos(p.z, p.y, -p.x)
        else -> p
    }

    // --- Senden --------------------------------------------------------------------------------------------------------

    private fun confirm() {
        val p = prepared ?: run {
            msg(Text.translatable("hha_arius.blueprint.nothing").formatted(Formatting.GRAY))
            return
        }
        if (!ClientPlayNetworking.canSend(BlueprintStartC2S.ID)) {
            msg(Text.translatable("hha_arius.blueprint.no_server").formatted(Formatting.RED))
            return
        }
        val palette = ArrayList<Int>()
        val index = HashMap<Int, Int>()
        val idx = IntArray(p.blocks.size)
        val pos = LongArray(p.blocks.size)
        for ((i, b) in p.blocks.withIndex()) {
            val raw = Block.getRawIdFromState(b.second)
            idx[i] = index.getOrPut(raw) { palette.add(raw); palette.size - 1 }
            pos[i] = b.first.asLong()
        }
        ClientPlayNetworking.send(BlueprintStartC2S(p.blocks.size, palette.toIntArray()))
        val chunk = 2400
        var from = 0
        while (from < pos.size) {
            val to = minOf(pos.size, from + chunk)
            ClientPlayNetworking.send(BlueprintPartC2S(pos.copyOfRange(from, to), idx.copyOfRange(from, to)))
            from = to
        }
        prepared = null
        msg(Text.translatable("hha_arius.blueprint.sent", p.name).formatted(Formatting.AQUA))
    }

    // --- Vorschau ------------------------------------------------------------------------------------------------------

    private fun render(consumers: net.minecraft.client.render.VertexConsumerProvider?) {
        val p = prepared ?: return
        if (consumers == null) return
        val client = MinecraftClient.getInstance()
        val d = VfxDraw(consumers, client.gameRenderer.camera, client.renderTickCounter.getTickProgress(true))
        val min = Vec3d.of(p.min)
        val max = Vec3d.of(p.max).add(1.0, 1.0, 1.0)
        val c = min.lerp(max, 0.5)
        val half = max.subtract(min).multiply(0.5)
        val pulse = 0.12f + 0.05f * kotlin.math.sin((client.world?.time ?: 0L) * 0.2f)
        d.box(VfxLayers.GLOW_ADD, c, Vec3d(half.x, 0.0, 0.0), Vec3d(0.0, half.y, 0.0), Vec3d(0.0, 0.0, half.z), withAlpha(0xFFEFF4FF.toInt(), pulse))
        // Kanten als Lichtlinien
        val xs = doubleArrayOf(min.x, max.x)
        val ys = doubleArrayOf(min.y, max.y)
        val zs = doubleArrayOf(min.z, max.z)
        val col = withAlpha(0xFFFFFFFF.toInt(), 0.8f)
        for (y in ys) for (z in zs) d.band(VfxLayers.GLOW_ADD, Vec3d(min.x, y, z), Vec3d(max.x, y, z), d.camUp, 0.05, col, col)
        for (x in xs) for (z in zs) d.band(VfxLayers.GLOW_ADD, Vec3d(x, min.y, z), Vec3d(x, max.y, z), d.camRight, 0.05, col, col)
        for (x in xs) for (y in ys) d.band(VfxLayers.GLOW_ADD, Vec3d(x, y, min.z), Vec3d(x, y, max.z), d.camUp, 0.05, col, col)
    }
}
