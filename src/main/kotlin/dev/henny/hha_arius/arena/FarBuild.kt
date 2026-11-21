package dev.henny.hha_arius.arena

import net.minecraft.block.Blocks
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.Heightmap
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Bauplan einer Spalte der Fernlandschaft (relativ, y −1 .. [MAX_Y]): je Höhe eine Block-Id aus [FarPlan], [KEEP]
 * (nicht anfassen) oder [FarPlan.AIR].
 */
internal class ColumnPlan {
    val ids = IntArray(MAX_Y + 2)
    var hi = -2
        private set

    /** Tiefster gesetzter Block (darunter bleibt der Boden des Weltgenerators). */
    var lo = Int.MAX_VALUE
        private set

    fun reset() {
        java.util.Arrays.fill(ids, KEEP)
        hi = -2
        lo = Int.MAX_VALUE
    }

    fun set(y: Int, id: Int) {
        if (y < -1 || y > MAX_Y) return
        ids[y + 1] = id
        if (y > hi) hi = y
        if (y < lo) lo = y
    }

    fun get(y: Int): Int = if (y < -1 || y > MAX_Y) KEEP else ids[y + 1]

    companion object {
        const val KEEP = -1
        val MAX_Y = ArenaLayout.SPIRE_TOP + 40
    }
}

/**
 * Baut die Fernlandschaft einer Instanz Chunk für Chunk (Nadel und Sichtachse zuerst, dann Horst, Feld, Berge der Jagd
 * und das übrige Hochland), mit Budget je Tick, ohne Nachbar-Updates. Läuft nach dem Kernbau im Hintergrund weiter,
 * auch während des Tempelkampfs. Unter dem Kern (ArenaPlan-Bereich) wird nur unterhalb des tiefsten Planblocks gebaut.
 *
 * Seit 1.8.1 bekommt jede Spalte einen vollständigen Plan: Was ältere Fassungen über der neuen Oberkante hinterlassen
 * haben (alter Spiralweg, alte Nebengipfel, der alte Horst, das alte Feld), wird bis zur Höhenkarte abgeräumt; so ist
 * kein Tunnel und kein Weg mehr von alten Blöcken verstopft.
 */
class FarBuild(private val world: ServerWorld, private val origin: BlockPos) {

    private val chunks: List<ChunkPos>
    private var chunkIndex = 0
    private var columnInChunk = 0
    private val pos = BlockPos.Mutable()
    private val core = ByteArray(ArenaPlan.NY)
    private val plan = ColumnPlan()

    var isDone = false
        private set

    val progress: Float get() = if (isDone) 1f else chunkIndex.toFloat() / chunks.size.coerceAtLeast(1)

    init {
        val list = ArrayList<ChunkPos>()
        val minCx = (origin.x + FarPlan.X_MIN) shr 4
        val maxCx = (origin.x + FarPlan.X_MAX) shr 4
        val minCz = (origin.z + FarPlan.Z_MIN) shr 4
        val maxCz = (origin.z + FarPlan.Z_MAX) shr 4
        for (cx in minCx..maxCx) for (cz in minCz..maxCz) list.add(ChunkPos(cx, cz))
        // Reihenfolge nach Bedarf im Kampf: Nadel/Sichtachse (Auftritt), Horst (Abschnitt II), Feld (III), Berge (Jagd).
        fun near(c: ChunkPos, x: Int, z: Int, r: Int) = abs(c.startX + 8 - (origin.x + x)) <= r && abs(c.startZ + 8 - (origin.z + z)) <= r
        fun rank(c: ChunkPos): Double {
            val dx = (c.startX + 8 - origin.x).toDouble()
            val dz = (c.startZ + 8 - (origin.z + 90)).toDouble()
            val axis = abs(dx) * 1.6 + abs(dz) * 0.6
            return when {
                abs(dx) <= ArenaLayout.FAR_X && c.startZ + 8 - origin.z in -150..ArenaLayout.FAR_Z_MAX -> axis
                near(c, ArenaLayout.AERIE.x, ArenaLayout.AERIE.z, ArenaLayout.AERIE_RIM + 16) -> 100_000.0 + axis
                near(c, ArenaLayout.VAULT.x, ArenaLayout.VAULT.z, ArenaLayout.VAULT_R + 24) -> 200_000.0 + axis
                HuntRoute.MOUNTS.any { near(c, it.x.toInt(), it.z.toInt(), it.r.toInt() + 8) } -> 300_000.0 + axis
                else -> 400_000.0 + axis
            }
        }
        chunks = list.sortedBy { rank(it) }
    }

    /** Setzt höchstens [budget] Blöcke. true = fertig. */
    fun step(budget: Int): Boolean {
        if (isDone) return true
        var sets = 0
        var columns = 0
        while (sets < budget && columns < 2048) {
            if (chunkIndex >= chunks.size) {
                isDone = true
                return true
            }
            val c = chunks[chunkIndex]
            val lx = columnInChunk and 15
            val lz = columnInChunk shr 4
            val ax = c.startX + lx
            val az = c.startZ + lz
            val x = ax - origin.x
            val z = az - origin.z
            if (x in FarPlan.X_MIN..FarPlan.X_MAX && z in FarPlan.Z_MIN..FarPlan.Z_MAX) {
                sets += if (FarPlan.inCore(x, z)) coreColumn(x, z, ax, az) else column(x, z, ax, az)
            }
            columns++
            columnInChunk++
            if (columnInChunk >= 256) {
                columnInChunk = 0
                chunkIndex++
            }
        }
        return false
    }

    /** Unter dem Kern: nur Eisspitzen (Biom-Merkmal) entfernen, wo der Bauplan Luft vorsieht. */
    private fun coreColumn(x: Int, z: Int, ax: Int, az: Int): Int {
        val chunk = world.getChunk(ax shr 4, az shr 4)
        ArenaPlan.column(x, z, core)
        var sets = 0
        for (i in core.indices) {
            if (core[i] != ArenaPlan.AIR) continue
            val y = ArenaPlan.Y_MIN + i
            if (y < 0) continue
            pos.set(ax, origin.y + y, az)
            val st = chunk.getBlockState(pos)
            if (st.isOf(Blocks.PACKED_ICE) || st.isOf(Blocks.ICE)) {
                world.setBlockState(pos, Blocks.AIR.defaultState, ArenaBuilder.FLAGS)
                sets++
            }
        }
        // Über dem Kern (Bauvolumen bis Y_MAX) liegt nichts von der Fernlandschaft: Reste älterer Fassungen abräumen.
        val hm = chunk.sampleHeightmap(Heightmap.Type.WORLD_SURFACE, ax and 15, az and 15) - origin.y
        for (y in ArenaPlan.Y_MAX + 1..hm) {
            pos.set(ax, origin.y + y, az)
            if (!chunk.getBlockState(pos).isAir) {
                world.setBlockState(pos, Blocks.AIR.defaultState, ArenaBuilder.FLAGS)
                sets++
            }
        }
        return sets
    }

    private fun column(x: Int, z: Int, ax: Int, az: Int): Int {
        plan.reset()
        FarColumns.plan(x, z, plan)
        return apply(ax, az)
    }

    /** Plan in die Welt schreiben (nur Unterschiede); über dem Plan bis zur Höhenkarte alles abräumen. */
    private fun apply(ax: Int, az: Int): Int {
        val chunk = world.getChunk(ax shr 4, az shr 4)
        val p = plan
        var sets = 0
        for (y in -1..p.hi) {
            val id = p.get(y)
            if (id == ColumnPlan.KEEP) continue
            val state = FarPlan.state(id)
            pos.set(ax, origin.y + y, az)
            if (chunk.getBlockState(pos) != state) {
                world.setBlockState(pos, state, ArenaBuilder.FLAGS)
                sets++
            }
        }
        val hm = chunk.sampleHeightmap(Heightmap.Type.WORLD_SURFACE, ax and 15, az and 15) - origin.y
        for (y in max(p.hi + 1, 0)..min(hm, ColumnPlan.MAX_Y + 40)) {
            pos.set(ax, origin.y + y, az)
            if (!chunk.getBlockState(pos).isAir) {
                world.setBlockState(pos, Blocks.AIR.defaultState, ArenaBuilder.FLAGS)
                sets++
            }
        }
        return sets
    }
}
