package dev.henny.hha_arius.arena

import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.ChunkPos
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Fernlandschaft um das Heiligtum: die Nadel (riesiger, spitzer Eisberg im Süden), die Schlucht davor und zwei
 * Gletscherzüge in Ost und West, die eine Sichtachse vom Plateau zur Nadel rahmen; im Norden Klippen hinter dem Tempel.
 * Gespiegelt in `tools/assets/far_preview.py` (gleiche Formeln, gleicher Hash) – Vorschau-PNGs dort.
 *
 * Gebaut wird nur eine **Haut**: je Säule von der Oberkante bis knapp unter die tiefste Nachbar-Oberkante (mindestens
 * 4 Blöcke). So wirken Flanken und Schluchtwände massiv, kosten aber nur ~0,25 Mio. Blöcke. Nur Vanilla-Blöcke.
 * Die Fernlandschaft wird nie verändert (Spieler können in der Arena-Dimension nichts abbauen) und je Instanz einmal
 * gebaut ([FarBuild], Version [VERSION] in AriusState).
 */
internal object FarPlan {

    const val VERSION = 1

    const val X_MIN = -ArenaLayout.FAR_X
    const val X_MAX = ArenaLayout.FAR_X
    const val Z_MIN = -130
    const val Z_MAX = ArenaLayout.FAR_Z_MAX
    const val Y_MIN = ArenaLayout.FAR_Y_MIN

    private const val M32 = 0xFFFFFFFFL

    fun h32(x: Int, y: Int, z: Int, s: Int): Double {
        var h = ((x * 73856093) xor (y * 19349663) xor (z * 83492791) xor (s * -1640531535)).toLong() and M32
        h = h xor (h ushr 13)
        h = (h * 0x5bd1e995L) and M32
        h = h xor (h ushr 15)
        return (h and 0xFFFFFF).toDouble() / 0x1000000.toDouble()
    }

    fun vnoise(x: Int, z: Int, cell: Int, s: Int): Double {
        val fx = x.toDouble() / cell
        val fz = z.toDouble() / cell
        val x0 = floor(fx).toInt()
        val z0 = floor(fz).toInt()
        var tx = fx - x0
        var tz = fz - z0
        tx = tx * tx * (3 - 2 * tx)
        tz = tz * tz * (3 - 2 * tz)
        val a = h32(x0, 0, z0, s)
        val b = h32(x0 + 1, 0, z0, s)
        val c = h32(x0, 0, z0 + 1, s)
        val d = h32(x0 + 1, 0, z0 + 1, s)
        val ab = a + (b - a) * tx
        val cd = c + (d - c) * tx
        return ab + (cd - ab) * tz
    }

    private fun fbm(x: Int, z: Int, cell: Int, s: Int, octaves: Int = 4): Double {
        var v = 0.0
        var amp = 1.0
        var tot = 0.0
        for (o in 0 until octaves) {
            v += vnoise(x, z, max(1, cell shr o), s + o * 31) * amp
            tot += amp
            amp *= 0.5
        }
        return v / tot
    }

    private fun ridged(x: Int, z: Int, cell: Int, s: Int, octaves: Int = 4): Double {
        var v = 0.0
        var amp = 1.0
        var tot = 0.0
        for (o in 0 until octaves) {
            val n = 1.0 - abs(vnoise(x, z, max(1, cell shr o), s + o * 17) * 2.0 - 1.0)
            v += n * n * amp
            tot += amp
            amp *= 0.5
        }
        return v / tot
    }

    private fun smooth(e0: Double, e1: Double, v: Double): Double {
        val t = ((v - e0) / (e1 - e0)).coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }

    fun canyonCenter(z: Double): Double = 9.0 * sin(z / 19.0) + 4.0 * sin(z / 7.3)

    private fun canyonHalf(z: Double): Double {
        val t = ((z - ArenaLayout.CANYON_Z_FROM) / (ArenaLayout.CANYON_Z_TO - ArenaLayout.CANYON_Z_FROM)).coerceIn(0.0, 1.0)
        return 14.0 + 22.0 * sin(PI * t)
    }

    /** Oberkante der Nadel über (x, z) oder [Int.MIN_VALUE]. Kannelierte Flanken, leicht gedreht. */
    fun spireTop(x: Int, z: Int): Int {
        val dx = x + 0.5 - (ArenaLayout.SPIRE.x + 0.5)
        val dz = z + 0.5 - (ArenaLayout.SPIRE.z + 0.5)
        val d = hypot(dx, dz)
        if (d > ArenaLayout.SPIRE_BASE_R * 1.25) return Int.MIN_VALUE
        val a = atan2(dx, dz)
        val flute = 1.0 + 0.13 * sin(5 * a + d * 0.08) + 0.06 * sin(11 * a - 1.3)
        val r = d / (ArenaLayout.SPIRE_BASE_R * flute)
        if (r >= 1.0) return Int.MIN_VALUE
        var h = ArenaLayout.SPIRE.y + (ArenaLayout.SPIRE_TOP - ArenaLayout.SPIRE.y) * Math.pow(1.0 - r, 1.35)
        h += (vnoise(x, z, 6, 91) - 0.5) * 4.0 * (1.0 - r)
        return floor(h).toInt()
    }

    /** Oberkante des Gletschergeländes ohne Nadel. */
    fun ground(x: Int, z: Int): Int {
        val ax = abs(x).toDouble()
        val zd = z.toDouble()
        val base = -14.0 + (fbm(x, z, 32, 3) - 0.5) * 8.0
        val rise = smooth(54.0, 118.0, ax)
        val ridge = ridged(x + 400, z, 48, 7)
        var h = base + rise * (70.0 + 55.0 * ridge) + rise * (vnoise(x, z, 5, 13) - 0.5) * 7.0
        val north = smooth(-92.0, -128.0, zd)
        h = max(h, base + north * (60.0 + 30.0 * ridged(x, z + 300, 40, 11)))
        val sb = smooth(ArenaLayout.CANYON_Z_TO - 16.0, ArenaLayout.CANYON_Z_TO + 10.0, zd)
        if (sb > 0.0) {
            val shelf = -38.0 + (fbm(x, z, 20, 5) - 0.5) * 6.0
            val sh = max(shelf + rise * 40.0, min(h, shelf + rise * 120.0))
            h += (sh - h) * sb
        }
        val from = ArenaLayout.CANYON_Z_FROM
        val to = ArenaLayout.CANYON_Z_TO
        if (z in (from - 10)..(to + 10)) {
            val cz = minOf(1.0, (z - (from - 10)) / 10.0, ((to + 10) - z) / 10.0)
            val dxc = abs(x - canyonCenter(zd))
            val half = canyonHalf(zd.coerceIn(from.toDouble(), to.toDouble()))
            val inside = 1.0 - smooth(half - 1.0, half + 5.0 + 3.0 * vnoise(x, z, 4, 21), dxc)
            val floorY = ArenaLayout.CANYON_FLOOR + (vnoise(x, z, 9, 23) - 0.5) * 6.0
            h += (floorY - h) * inside * max(0.0, cz)
        }
        return floor(h).toInt()
    }

    /** Oberkante der Fernlandschaft (Gelände oder Nadel). */
    fun top(x: Int, z: Int): Int {
        var t = ground(x, z)
        val s = spireTop(x, z)
        if (s > t) t = s
        return t.coerceIn(Y_MIN, ArenaLayout.SPIRE_TOP)
    }

    fun inCore(x: Int, z: Int): Boolean =
        x in ArenaPlan.X_MIN..ArenaPlan.X_MAX && z in ArenaPlan.Z_MIN..ArenaPlan.Z_MAX

    // Block-Ids
    const val PACKED = 1
    const val BLUE = 2
    const val SNOW = 3
    const val DEEP = 11
    const val TUFF = 12

    fun material(x: Int, y: Int, z: Int, top: Int, slope: Int, spire: Boolean): Int {
        val depth = top - y
        if (spire) {
            if (depth == 0 && slope <= 2) return SNOW
            val band = Math.floorMod(y + (6 * vnoise(x, z, 7, 41)).toInt(), 9)
            return if (band < 2 || h32(x, y, z, 43) < 0.08) BLUE else PACKED
        }
        if (y < ArenaLayout.CANYON_FLOOR + 6 && depth > 0) return if (h32(x, y, z, 51) < 0.6) DEEP else TUFF
        if (depth == 0 && slope <= 2) return SNOW
        if (depth <= 1 && slope <= 1) return SNOW
        val band = Math.floorMod(y + (5 * vnoise(x, z, 11, 61)).toInt(), 7)
        if (band == 0) return BLUE
        if (slope >= 6 && h32(x, y, z, 63) < 0.2) return BLUE
        return PACKED
    }

    fun state(id: Int): BlockState = when (id) {
        BLUE -> Blocks.BLUE_ICE.defaultState
        SNOW -> Blocks.SNOW_BLOCK.defaultState
        DEEP -> Blocks.DEEPSLATE.defaultState
        TUFF -> Blocks.TUFF.defaultState
        else -> Blocks.PACKED_ICE.defaultState
    }
}

/**
 * Baut die Fernlandschaft einer Instanz Chunk für Chunk (Sichtachse und Nadel zuerst), mit Budget je Tick, ohne
 * Nachbar-Updates. Läuft nach dem Kernbau im Hintergrund weiter, auch während des Tempelkampfs. Unter dem Kern
 * (ArenaPlan-Bereich) wird nur unterhalb des tiefsten Planblocks gebaut, damit Kern und Ferne nie überlappen.
 */
class FarBuild(private val world: ServerWorld, private val origin: BlockPos) {

    private val chunks: List<ChunkPos>
    private var chunkIndex = 0
    private var columnInChunk = 0
    private val pos = BlockPos.Mutable()
    private val core = ByteArray(ArenaPlan.NY)

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
        // Zuerst die Nadel und die Sichtachse, dann der Rest nach Abstand zur Achse.
        val ax = origin.x
        val az = origin.z + 90
        chunks = list.sortedBy { c ->
            val dx = (c.startX + 8 - ax).toDouble()
            val dz = (c.startZ + 8 - az).toDouble()
            abs(dx) * 1.6 + abs(dz) * 0.6
        }
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
                sets += column(x, z, ax, az)
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

    private fun column(x: Int, z: Int, ax: Int, az: Int): Int {
        var top = FarPlan.top(x, z)
        var limit = Int.MAX_VALUE
        if (FarPlan.inCore(x, z)) {
            ArenaPlan.column(x, z, core)
            var low = -1
            for (i in core.indices) if (core[i] != ArenaPlan.AIR) {
                low = i
                break
            }
            if (low >= 0) limit = ArenaPlan.Y_MIN + low - 1
            top = min(top, limit)
        }
        val n = intArrayOf(FarPlan.top(x + 1, z), FarPlan.top(x - 1, z), FarPlan.top(x, z + 1), FarPlan.top(x, z - 1))
        var lowest = top
        var slope = 0
        for (v in n) {
            val vv = min(v, limit)
            if (vv < lowest) lowest = vv
            slope = max(slope, abs(top - vv))
        }
        val bottom = max(FarPlan.Y_MIN, min(top - 4, lowest - 1))
        val spire = FarPlan.spireTop(x, z).let { it != Int.MIN_VALUE && it >= FarPlan.ground(x, z) }
        val chunk = world.getChunk(ax shr 4, az shr 4)
        var sets = 0
        for (y in bottom..top) {
            val state = FarPlan.state(FarPlan.material(x, y, z, top, slope, spire))
            pos.set(ax, origin.y + y, az)
            if (chunk.getBlockState(pos) != state) {
                world.setBlockState(pos, state, ArenaBuilder.FLAGS)
                sets++
            }
        }
        return sets
    }
}
