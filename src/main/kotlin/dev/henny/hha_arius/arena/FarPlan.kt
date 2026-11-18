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

    const val VERSION = 6 // 6: Feld im Hochland statt Balkon, kein Massiv; 5: Bergmassiv mit Terrasse statt schwebendem Balkon; 4: Balkon als Felsschelf mit Strebe, Schneebänder am Horn; 2: Himmelsbalkon an der Nadel; 3: Alpen (Felshorn, Fels/Schnee, offenes Hochland)

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

    /**
     * Oberkante des Horns über (x, z) oder [Int.MIN_VALUE]. Seit 1.3.1 ein alpines Felshorn (wie das Matterhorn):
     * Pyramide mit vier Graten (Superellipse-Abstand, p 1,3), Flanken dazwischen eingezogen. Der Abstand ist nie kleiner
     * als der Kreisabstand, das Horn bleibt also innerhalb von [ArenaLayout.spireRadius] (Windung im Auftritt passt).
     */
    fun spireTop(x: Int, z: Int): Int {
        val dx = x + 0.5 - (ArenaLayout.SPIRE.x + 0.5)
        val dz = z + 0.5 - (ArenaLayout.SPIRE.z + 0.5)
        val d = hypot(dx, dz)
        if (d > ArenaLayout.SPIRE_BASE_R * 1.25) return Int.MIN_VALUE
        val ca = 0.866
        val sa = 0.5
        val u = abs(dx * ca - dz * sa)
        val v = abs(dx * sa + dz * ca)
        val m = Math.pow(Math.pow(u, 1.3) + Math.pow(v, 1.3), 1.0 / 1.3)
        val rough = 1.0 + 0.05 * (vnoise(x, z, 7, 93) - 0.5)
        val r = m / (ArenaLayout.SPIRE_BASE_R * rough)
        if (r >= 1.0) return Int.MIN_VALUE
        var h = ArenaLayout.SPIRE.y + (ArenaLayout.SPIRE_TOP - ArenaLayout.SPIRE.y) * Math.pow(1.0 - r, 1.35)
        h += (vnoise(x, z, 6, 91) - 0.5) * 4.0 * (1.0 - r)
        return floor(h).toInt()
    }

    /** Oberkante des Gletschergeländes ohne Nadel. */
    fun ground(x: Int, z: Int): Int {
        val ax = abs(x).toDouble()
        val zd = z.toDouble()
        // Seit 1.3.1: weites Hochland auf Plateauhöhe, die Alpenketten beginnen erst weit draußen.
        val base = -1.0 + (fbm(x, z, 40, 3) - 0.5) * 7.0
        val rise = smooth(92.0, 136.0, ax)
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

    /**
     * Breites Bergmassiv unter der Gipfelpyramide (seit 1.4.1): Grate wie die Pyramide (Superellipse), Flanken fallen
     * mit ~1,25 je Block nach außen, ein Gratrauschen bricht sie auf. Nur jenseits der Schlucht (z ≥ 60).
     */
    fun massifTop(x: Int, z: Int): Int {
        if (z < 60) return Int.MIN_VALUE
        val dx = x + 0.5 - (ArenaLayout.SPIRE.x + 0.5)
        val dz = z + 0.5 - (ArenaLayout.SPIRE.z + 0.5)
        val u = abs(dx * 0.866 - dz * 0.5)
        val v = abs(dx * 0.5 + dz * 0.866)
        val m = Math.pow(Math.pow(u, 1.3) + Math.pow(v, 1.3), 1.0 / 1.3)
        if (m > 120.0) return Int.MIN_VALUE
        val h = 112.0 - max(0.0, m - 20.0) * 1.25 + (ridged(x, z, 26, 81) - 0.5) * 14.0
        return floor(h).toInt()
    }

    /** Liegt (x, z) auf der Terrasse des Himmelsbalkons (zerklüfteter Rand)? */
    fun onTerrace(x: Int, z: Int): Boolean =
        hypot((x - ArenaLayout.VAULT.x).toDouble(), (z - ArenaLayout.VAULT.z).toDouble()) <= balconyEdge(x, z)

    /** Oberkante der Fernlandschaft (Gelände, Massiv, Gipfelpyramide; auf der Terrasse eben). */
    fun top(x: Int, z: Int): Int {
        var t = ground(x, z)
        val s = spireTop(x, z)
        if (s > t) t = s
        // Terrasse: flach auf Balkonhöhe (in den Berg geschnitten bzw. auf der Schulter aufgefüllt).
        if (onTerrace(x, z)) t = ArenaLayout.VAULT.y - 1
        return t.coerceIn(Y_MIN, ArenaLayout.SPIRE_TOP)
    }

    fun inCore(x: Int, z: Int): Boolean =
        x in ArenaPlan.X_MIN..ArenaPlan.X_MAX && z in ArenaPlan.Z_MIN..ArenaPlan.Z_MAX

    // Block-Ids
    const val PACKED = 1
    const val BLUE = 2
    const val SNOW = 3
    const val STONE = 4
    const val ANDESITE = 5
    const val CALCITE = 6
    const val DEEP = 11
    const val TUFF = 12

    /**
     * Seit 1.3.1 alpin (Crix: „ein Berg wie in den Alpen, nicht aus Eis“): Fels in Schichten (Stein, Andesit, Tuff,
     * dunkler Tiefenschiefer), Schnee auf flachen Stellen und oberhalb der Schneegrenze, Gletschereis nur noch in Rinnen
     * und auf der Schluchtsohle.
     */
    fun material(x: Int, y: Int, z: Int, top: Int, slope: Int, spire: Boolean): Int {
        val depth = top - y
        if (y < ArenaLayout.CANYON_FLOOR + 6 && depth > 0) return if (h32(x, y, z, 51) < 0.6) DEEP else TUFF
        val snowLine = 70 + (vnoise(x, z, 13, 65) * 20).toInt()
        // Schnee: flache Oberflächen immer, steilere oberhalb der Schneegrenze teilweise
        if (depth == 0 && (slope <= 2 || (y > snowLine && slope <= 5 && h32(x, y, z, 67) < 0.75))) return SNOW
        if (depth <= 1 && slope <= 1) return SNOW
        // Horn: Schneebänder auf Simsen und eine verschneite Gipfelkappe (wie das Matterhorn)
        if (spire && depth <= 1) {
            if (y > 150 && h32(x, y, z, 75) < 0.7) return SNOW
            if (Math.floorMod(y + (4 * vnoise(x, z, 9, 77)).toInt(), 14) < 2) return SNOW
        }
        // Eisrinnen (Couloirs) in steilen Flanken
        if (slope >= 4 && vnoise(x, z, 6, 69) > 0.78) return if (h32(x, y, z, 71) < 0.3) BLUE else PACKED
        // Felsschichten, leicht geneigt
        val strata = Math.floorMod(y + (6 * vnoise(x, z, 17, 73)).toInt() + (x + z) / 9, 11)
        return when {
            strata <= 1 -> DEEP
            strata <= 3 -> ANDESITE
            strata == 7 -> TUFF
            strata == 9 && spire -> CALCITE
            else -> STONE
        }
    }

    // --- Himmelsbalkon --------------------------------------------------------------------------------------------

    private val VX = ArenaLayout.VAULT.x
    private val VY = ArenaLayout.VAULT.y
    private val VZ = ArenaLayout.VAULT.z

    /** Zerklüfteter Rand: Radius je Richtung. */
    fun balconyEdge(x: Int, z: Int): Double = ArenaLayout.VAULT_R + 1.5 + (vnoise(x, z, 5, 701) - 0.5) * 4.0

    /**
     * Dicke des Schelfs: außen dünn (4), zur Bergflanke hin wächst die Unterseite als Strebe schräg in den Berg hinein
     * (bis ~60 tief). So wirkt der Balkon wie aus der Flanke gewachsen, nicht wie eine schwebende Scheibe (Pilzhut).
     */
    private fun balconyThick(x: Int, z: Int): Int {
        val ds = hypot((x - ArenaLayout.SPIRE.x).toDouble(), (z - ArenaLayout.SPIRE.z).toDouble())
        val face = ArenaLayout.spireRadius(VY.toDouble())
        val k = (1.0 - (ds - face) / 46.0).coerceIn(0.0, 1.0)
        return 4 + (k * k * 58.0 + vnoise(x, z, 4, 703) * 3.0).toInt()
    }

    /** Block des Balkons an (x, y, z) relativ, oder -1 (nichts). Säulen, Lauffläche, Unterseite, Eiszapfen, Zuschauervorsprung. */
    fun balcony(x: Int, y: Int, z: Int): Int {
        // Zuschauerhügel südlich des Feldes (Lauffläche 6 über dem Feld)
        val wx = ArenaLayout.WATCH_VAULT.x.toInt()
        val wz = ArenaLayout.WATCH_VAULT.z.toInt()
        if (abs(x - wx) <= 2 && abs(z - wz) <= 2 && y in VY until VY + 6) return if (y == VY + 5) SNOW else PACKED
        val dx = (x - VX).toDouble()
        val dz = (z - VZ).toDouble()
        val r = hypot(dx, dz)
        if (r > ArenaLayout.VAULT_R + 4.0) return -1
        // Säulen: Kristall, Höhe 6–9, Kern blau
        for (p in ArenaLayout.VAULT_PILLARS) {
            val pr = hypot((x - p.x).toDouble(), (z - p.z).toDouble())
            if (pr <= 1.6 && y >= VY) {
                val hgt = 6 + (h32(p.x, 0, p.z, 707) * 4).toInt()
                val taper = if (y - VY > hgt - 3) 1.6 - (y - VY - (hgt - 3)) * 0.45 else 1.6
                if (y < VY + hgt && pr <= taper) return if (pr < 0.8) BLUE else PACKED
            }
        }
        // Lauffläche und Unterbau liefert seit 1.4.1 das Gelände (Terrasse auf dem Massiv), hier nur Säulen.
        if (y >= VY || r >= 0.0) return -1
        val th = balconyThick(x, z)
        if (y == VY - 1) {
            val vein = vnoise(x, z, 3, 709)
            return if (r > balconyEdge(x, z) - 1.2) STONE else if (vein > 0.8) PACKED else SNOW
        }
        if (y >= VY - th) {
            val k = h32(x, y, z, 711)
            return if (k < 0.45) STONE else if (k < 0.75) ANDESITE else if (k < 0.9) TUFF else DEEP
        }
        // Wenige kurze Eiszapfen am dünnen Außenrand
        val icicle = h32(x, 0, z, 713)
        if (icicle < 0.04 && th <= 8) {
            val len = 1 + (h32(x, 1, z, 715) * 3).toInt()
            if (y >= VY - th - len) return if (y == VY - th - len) BLUE else PACKED
        }
        return -1
    }

    fun state(id: Int): BlockState = when (id) {
        BLUE -> Blocks.BLUE_ICE.defaultState
        SNOW -> Blocks.SNOW_BLOCK.defaultState
        DEEP -> Blocks.DEEPSLATE.defaultState
        TUFF -> Blocks.TUFF.defaultState
        STONE -> Blocks.STONE.defaultState
        ANDESITE -> Blocks.ANDESITE.defaultState
        CALCITE -> Blocks.CALCITE.defaultState
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
        // Himmelsbalkon (über der Schlucht an der Nadel): Lauffläche, Säulen, Unterseite, Eiszapfen.
        for (y in ArenaLayout.VAULT.y - 2..ArenaLayout.VAULT.y + 12) {
            val id = FarPlan.balcony(x, y, z)
            pos.set(ax, origin.y + y, az)
            if (id < 0) {
                // Luft über der Lauffläche freihalten (außer der Nadel selbst).
                if (y >= ArenaLayout.VAULT.y && y > top && !FarPlan.inCore(x, z) && !chunk.getBlockState(pos).isAir) {
                    world.setBlockState(pos, net.minecraft.block.Blocks.AIR.defaultState, ArenaBuilder.FLAGS)
                    sets++
                }
                continue
            }
            val state = FarPlan.state(id)
            if (chunk.getBlockState(pos) != state) {
                world.setBlockState(pos, state, ArenaBuilder.FLAGS)
                sets++
            }
        }
        return sets
    }
}
