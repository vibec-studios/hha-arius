package dev.henny.hha_arius.boss

import com.google.gson.Gson
import com.mojang.serialization.Codec
import dev.henny.hha_arius.HhaArius
import dev.henny.hha_arius.arena.ArenaDimension
import dev.henny.hha_arius.arena.ArenaLayout
import dev.henny.hha_arius.arena.ColumnPlan
import dev.henny.hha_arius.arena.FarColumns
import dev.henny.hha_arius.arena.FarPlan
import dev.henny.hha_arius.arena.HighlandView
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.minecraft.block.Block
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.datafixer.DataFixTypes
import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.NbtHelper
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.NbtList
import net.minecraft.nbt.NbtSizeTracker
import net.minecraft.registry.RegistryKeys
import net.minecraft.server.MinecraftServer
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockPos
import net.minecraft.world.PersistentState
import net.minecraft.world.PersistentStateType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.abs
import kotlin.math.floor

/**
 * Absturzsichere Wiederherstellung des Berg- und Gipfelarena-Schnitts (1.13.0, Paket C):
 * Bevor der Schnitt Blöcke entfernt, werden die exakten Originalzustände kompakt (Palette + GZIP + Base64) im
 * Weltzustand gespeichert. Bricht der Server mitten in der Szene ab oder startet neu, stellt [recoverAll] beim
 * Serverstart (und [ensureClean] vor einer neuen Begegnung) jeden einzelnen Block exakt wieder her — mit deterministischem
 * [FarColumns]-Rückfall, falls der Absturz sogar vor dem Sichern geschah.
 */
object SplitRecovery {

    class Entry(
        var slot: Int = 0,
        var z0: Int = 0,
        var z1: Int = 0,
        /** GZIP-komprimierte binäre Palette + (relX, relY, relZ, stateIdx), Base64-kodiert. */
        var payload: String = "",
    )

    class Data(var active: MutableMap<String, Entry> = HashMap())

    class State(var data: Data = Data()) : PersistentState() {
        fun entries(): MutableMap<String, Entry> {
            @Suppress("SENSELESS_COMPARISON")
            if (data.active == null) data.active = HashMap()
            return data.active
        }
    }

    private val GSON = Gson()
    private val CODEC: Codec<State> = Codec.STRING.xmap(
        { State(runCatching { GSON.fromJson(it, Data::class.java) }.getOrNull() ?: Data()) },
        { GSON.toJson(it.data) },
    )
    private val TYPE = PersistentStateType("hha_arius_split_recovery", { State() }, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE)

    private var initialized = false

    fun init() {
        if (initialized) return
        initialized = true
        ServerLifecycleEvents.SERVER_STARTED.register { server -> recoverAll(server) }
    }

    private fun state(server: MinecraftServer): State = server.overworld.persistentStateManager.getOrCreate(TYPE)

    /** Sichert alle zu spaltenden Originalblöcke dauerhaft auf Platte (bevor der erste Block entfernt wird). */
    fun saveSnapshot(world: ServerWorld, slot: Int, z0: Int, z1: Int, originals: List<Pair<BlockPos, BlockState>>) {
        val server = world.server ?: return
        val origin = ArenaLayout.origin(slot)
        val encoded = encode(origin, originals)
        val st = state(server)
        st.entries()[slot.toString()] = Entry(slot, z0, z1, encoded)
        st.markDirty()
        // Sofort auf die Platte schreiben, damit selbst ein harter Prozessabbruch mitten in der Szene das Backup behält.
        runCatching { server.overworld.persistentStateManager.save() }
    }

    /** Markiert den Slot als wiederhergestellt und löscht das gespeicherte Backup. */
    fun clearSnapshot(server: MinecraftServer, slot: Int) {
        val st = state(server)
        if (st.entries().remove(slot.toString()) != null) {
            st.markDirty()
        }
    }

    /** Prüft beim Serverstart alle offenen Schnitt-Backups und stellt jeden betroffenen Slot lückenlos wieder her. */
    fun recoverAll(server: MinecraftServer): Int {
        val st = state(server)
        if (st.entries().isEmpty()) return 0
        val world = ArenaDimension.world(server) ?: return 0
        var total = 0
        for (key in st.entries().keys.toList()) {
            val entry = st.entries()[key] ?: continue
            total += restoreEntry(world, entry)
            st.entries().remove(key)
        }
        st.markDirty()
        if (total > 0) HhaArius.LOGGER.info("SplitRecovery: restored {} blocks after restart", total)
        return total
    }

    /** Stellt einen bestimmten Slot sicher her, falls noch ein offenes Schnitt-Backup existiert. */
    fun ensureClean(server: MinecraftServer, slot: Int): Int {
        val st = state(server)
        val entry = st.entries()[slot.toString()] ?: return 0
        val world = ArenaDimension.world(server) ?: return 0
        val count = restoreEntry(world, entry)
        st.entries().remove(slot.toString())
        st.markDirty()
        return count
    }

    private fun restoreEntry(world: ServerWorld, entry: Entry): Int {
        val origin = ArenaLayout.origin(entry.slot)
        val decoded = if (entry.payload.isNotEmpty()) decode(world, origin, entry.payload) else emptyList()
        var restored = 0
        if (decoded.isNotEmpty()) {
            val touchedChunks = HashSet<Long>()
            for ((pos, state) in decoded) {
                if (world.getBlockState(pos) != state) {
                    world.setBlockState(pos, state, ArenaEdits.FLAGS)
                    restored++
                }
                val ck = ((pos.x shr 4).toLong() shl 32) or ((pos.z shr 4).toLong() and 0xFFFFFFFFL)
                if (touchedChunks.add(ck)) HighlandView.changed(world, pos.x, pos.z)
            }
            return restored
        }
        // Deterministischer Rückfall: gesamte Schnitt-Zone aus dem Bauplan (FarColumns) neu schreiben.
        val plan = ColumnPlan()
        val pos = BlockPos.Mutable()
        val cutX = floor(ArenaLayout.CUT_X).toInt()
        val z0 = entry.z0.coerceIn(FarPlan.Z_MIN, FarPlan.Z_MAX)
        val z1 = entry.z1.coerceIn(FarPlan.Z_MIN, FarPlan.Z_MAX)
        val touchedChunks = HashSet<Long>()
        for (z in z0..z1) {
            for (dx in -10..10) {
                val x = cutX + dx
                if (x !in FarPlan.X_MIN..FarPlan.X_MAX || FarPlan.inCore(x, z)) continue
                plan.reset()
                FarColumns.plan(x, z, plan)
                for (y in 0..plan.hi) {
                    val id = plan.get(y)
                    if (id == ColumnPlan.KEEP) continue
                    val target = FarPlan.state(id)
                    pos.set(origin.x + x, origin.y + y, origin.z + z)
                    if (world.getBlockState(pos) != target) {
                        world.setBlockState(pos, target, ArenaEdits.FLAGS)
                        restored++
                    }
                }
                val ck = (((origin.x + x) shr 4).toLong() shl 32) or (((origin.z + z) shr 4).toLong() and 0xFFFFFFFFL)
                if (touchedChunks.add(ck)) HighlandView.changed(world, origin.x + x, origin.z + z)
            }
        }
        return restored
    }

    private fun encode(origin: BlockPos, originals: List<Pair<BlockPos, BlockState>>): String {
        val palette = ArrayList<BlockState>()
        val index = HashMap<BlockState, Int>()
        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { gz ->
            DataOutputStream(gz).use { out ->
                // Palette aufbauen
                for ((_, s) in originals) index.getOrPut(s) { palette.add(s); palette.size - 1 }
                val root = NbtCompound()
                val palList = NbtList()
                for (s in palette) palList.add(NbtHelper.fromBlockState(s))
                root.put("palette", palList)
                NbtIo.writeCompound(root, out)
                out.writeInt(originals.size)
                for ((pos, s) in originals) {
                    out.writeShort(pos.x - origin.x)
                    out.writeShort(pos.y - origin.y)
                    out.writeShort(pos.z - origin.z)
                    out.writeShort(index.getValue(s))
                }
            }
        }
        return Base64.getEncoder().encodeToString(baos.toByteArray())
    }

    private fun decode(world: ServerWorld, origin: BlockPos, payload: String): List<Pair<BlockPos, BlockState>> =
        runCatching {
            val raw = Base64.getDecoder().decode(payload)
            val lookup = world.createCommandRegistryWrapper(RegistryKeys.BLOCK)
            GZIPInputStream(ByteArrayInputStream(raw)).use { gz ->
                DataInputStream(gz).use { inp ->
                    val root = NbtIo.readCompound(inp, NbtSizeTracker.ofUnlimitedBytes())
                    val palList = root.getListOrEmpty("palette")
                    val palette = ArrayList<BlockState>(palList.size)
                    for (i in 0 until palList.size) {
                        val c = palList.getCompound(i).orElse(null)
                        palette.add(if (c != null) NbtHelper.toBlockState(lookup, c) else Blocks.AIR.defaultState)
                    }
                    val count = inp.readInt().coerceIn(0, 500_000)
                    val out = ArrayList<Pair<BlockPos, BlockState>>(count)
                    for (i in 0 until count) {
                        val rx = inp.readShort().toInt()
                        val ry = inp.readShort().toInt()
                        val rz = inp.readShort().toInt()
                        val idx = inp.readUnsignedShort()
                        val st = palette.getOrElse(idx) { Blocks.AIR.defaultState }
                        out.add(BlockPos(origin.x + rx, origin.y + ry, origin.z + rz) to st)
                    }
                    out
                }
            }
        }.getOrElse { e ->
            HhaArius.LOGGER.warn("SplitRecovery decode failed, falling back to FarColumns: {}", e.toString())
            emptyList()
        }

    /** Hilfsfunktion für die Schnittbreite: V-förmig, unten ~3 Blöcke, oben bis ~10 Blöcke breit über die Gipfelarena. */
    fun cutHalfWidth(relY: Int): Double = 1.65 + relY / 46.0

    fun inCutPlane(relX: Int, relY: Int): Boolean = abs(relX + 0.5 - ArenaLayout.CUT_X) <= cutHalfWidth(relY)
}
