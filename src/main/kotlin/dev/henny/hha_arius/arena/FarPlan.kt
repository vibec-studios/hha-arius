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

    const val VERSION = 8 // 8: Massiv größer, Kletterweg mit Ranken; 7: 7: endloses Schneefeld, Riesenmassiv mit Spiralweg (neue Dimension); 6: 6: Feld im Hochland statt Balkon, kein Massiv; 5: Bergmassiv mit Terrasse statt schwebendem Balkon; 4: Balkon als Felsschelf mit Strebe, Schneebänder am Horn; 2: Himmelsbalkon an der Nadel; 3: Alpen (Felshorn, Fels/Schnee, offenes Hochland)

    const val X_MIN = -ArenaLayout.FAR_X
    const val X_MAX = ArenaLayout.FAR_X
    const val Z_MIN = -150
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

    // --- Das Bergmassiv mit Spiralweg (seit 1.4.2) ------------------------------------------------------------------

    /**
     * Crix (18.11.): „Der Berg soll riesig sein und man soll ihn wie eine Spirale hochgehen können.“ Um das Horn liegt
     * ein breites Massiv (Fuß [MASSIF_R] Blöcke um die Achse, Schulter auf y [SHOULDER_Y] mit [SHOULDER_R] Radius), aus
     * dessen Schulter das alte Felshorn aufragt. Ein Weg ([PATH_W] breit, Steigung 1 : 5) windet sich vom Nordfuß
     * (Richtung Plateau) hinauf bis auf die Schulter; Schneeschichten glätten ihn, Laternen stehen alle [POST_EVERY]
     * Blöcke am Außenrand.
     */
    // Seit 1.5.1 größer (Crix: „größer, und man soll ihn hochklettern müssen“): Fuß 170, Schulter auf y 172. Der Weg ist
    // fast eben, steigt aber alle CLIMB_EVERY Blöcke an einer Felsstufe um CLIMB_H Blöcke – dort hängen Ranken zum
    // Hochklettern; dazwischen einzelne Blockstufen (springen). Rund drei Umläufe, 27 Kletterstellen.
    const val MASSIF_R = 170.0
    const val SHOULDER_R = 26.0
    const val SHOULDER_Y = 172.0
    const val PATH_W = 6.0
    private const val GRADE = 0.05
    const val CLIMB_EVERY = 50.0
    const val CLIMB_H = 4.0
    private const val POST_EVERY = 22.0
    private const val PHI_STEP = 0.003

    /** Höhe des Massivs über dem Kreisabstand d zur Achse (ohne Rauschen). */
    fun massifH(d: Double): Double = when {
        d >= MASSIF_R -> -1.0
        d <= SHOULDER_R -> SHOULDER_Y
        else -> -1.0 + (SHOULDER_Y + 1.0) * Math.pow((MASSIF_R - d) / (MASSIF_R - SHOULDER_R), 1.15)
    }

    /** Kreisabstand zur Achse, auf dem das Massiv die Höhe y hat. */
    fun massifR(y: Double): Double =
        MASSIF_R - (MASSIF_R - SHOULDER_R) * Math.pow(((y + 1.0) / (SHOULDER_Y + 1.0)).coerceIn(0.0, 1.0), 1.0 / 1.15)

    private const val A0 = PI // Wegbeginn im Norden (Blick zum Plateau)
    private val pathY: DoubleArray
    private val pathR: DoubleArray
    private val pathArc: DoubleArray
    private val posts = HashMap<Long, Int>()

    init {
        val ys = ArrayList<Double>()
        val rs = ArrayList<Double>()
        val arcs = ArrayList<Double>()
        var y = -1.0
        var arc = 0.0
        var nextPost = 8.0
        var i = 0
        var climbs = 0
        while (y < SHOULDER_Y) {
            val r = massifR(y)
            ys.add(y)
            rs.add(r)
            arcs.add(arc)
            val ds = r * PHI_STEP
            arc += ds
            if (arc >= nextPost) {
                nextPost += POST_EVERY
                val a = A0 + i * PHI_STEP
                val ro = r + PATH_W / 2 - 0.6
                val px = floor(ArenaLayout.SPIRE.x + 0.5 + sin(a) * ro).toInt()
                val pz = floor(ArenaLayout.SPIRE.z + 0.5 + kotlin.math.cos(a) * ro).toInt()
                posts[key(px, pz)] = floor(y).toInt()
            }
            y += GRADE * ds
            val k = (arc / CLIMB_EVERY).toInt()
            if (k > climbs) {
                climbs = k
                y += CLIMB_H
            }
            i++
        }
        pathY = ys.toDoubleArray()
        pathR = rs.toDoubleArray()
        pathArc = arcs.toDoubleArray()
    }

    private fun key(x: Int, z: Int): Long = (x.toLong() shl 32) or (z.toLong() and 0xFFFFFFFFL)

    /** Höhe der Wegfläche über (x, z) (mit Bruchteil) oder NaN. */
    fun pathAt(x: Int, z: Int): Double {
        val i = pathIndex(x, z)
        return if (i < 0) Double.NaN else pathY[i]
    }

    /**
     * Kletterstelle: liegt (x, z) auf dem Weg direkt vor einer Felsstufe, die Richtung, in der die Wand steht (Ranken
     * hängen an ihr), sonst null.
     */
    fun climbFace(x: Int, z: Int): net.minecraft.util.math.Direction? {
        val i = pathIndex(x, z)
        if (i < 0) return null
        val frac = pathArc[i] % CLIMB_EVERY
        if (frac < CLIMB_EVERY - 1.6) return null
        val a = A0 + i * PHI_STEP
        val fx = kotlin.math.cos(a)
        val fz = -sin(a)
        return if (abs(fx) > abs(fz)) {
            if (fx > 0) net.minecraft.util.math.Direction.EAST else net.minecraft.util.math.Direction.WEST
        } else {
            if (fz > 0) net.minecraft.util.math.Direction.SOUTH else net.minecraft.util.math.Direction.NORTH
        }
    }

    private fun pathIndex(x: Int, z: Int): Int {
        val dx = x + 0.5 - (ArenaLayout.SPIRE.x + 0.5)
        val dz = z + 0.5 - (ArenaLayout.SPIRE.z + 0.5)
        val d = hypot(dx, dz)
        if (d > MASSIF_R + PATH_W || d < SHOULDER_R - 1.0) return -1
        var phi = atan2(dx, dz) - A0
        while (phi < 0.0) phi += 2 * PI
        while (phi >= 2 * PI) phi -= 2 * PI
        while (true) {
            val i = (phi / PHI_STEP).toInt()
            if (i >= pathY.size) return -1
            if (abs(d - pathR[i]) <= PATH_W / 2) return i
            phi += 2 * PI
        }
    }

    /** Laternenpfosten am Außenrand des Wegs: Wegoberkante darunter oder [Int.MIN_VALUE]. */
    fun postAt(x: Int, z: Int): Int = posts[key(x, z)] ?: Int.MIN_VALUE

    /** Massiv mit Gratrauschen über (x, z) oder [Int.MIN_VALUE]. */
    fun massifTop(x: Int, z: Int): Int {
        val dx = x + 0.5 - (ArenaLayout.SPIRE.x + 0.5)
        val dz = z + 0.5 - (ArenaLayout.SPIRE.z + 0.5)
        val d = hypot(dx, dz)
        if (d >= MASSIF_R + 4.0) return Int.MIN_VALUE
        val rough = (ridged(x, z, 22, 81) - 0.5) * 8.0 * smooth(MASSIF_R, MASSIF_R - 30.0, d) * smooth(SHOULDER_R, SHOULDER_R + 12.0, d)
        val h = massifH(d) + rough
        return if (h < -1.0) Int.MIN_VALUE else floor(h).toInt()
    }

    /**
     * Oberkante des Hochlands ohne Berg: seit 1.4.2 ein endloses Schneefeld auf Plateauhöhe (Crix: „die Arena soll
     * unendlich groß sein“, kein Rand, der ansteigt). Weit draußen nur sanfte Schneewellen, die zum Rand des Baubereichs
     * wieder auf die Ebene des Weltgenerators auslaufen.
     */
    fun ground(x: Int, z: Int): Int {
        val rc = hypot(x.toDouble(), z + 20.0)
        var w = smooth(80.0, 120.0, rc)
        val dv = hypot((x - ArenaLayout.VAULT.x).toDouble(), (z - ArenaLayout.VAULT.z).toDouble())
        w *= smooth(ArenaLayout.VAULT_R + 12.0, ArenaLayout.VAULT_R + 40.0, dv)
        w *= smooth(X_MAX.toDouble(), X_MAX - 24.0, abs(x).toDouble()) * smooth(Z_MAX.toDouble(), Z_MAX - 24.0, z.toDouble()) *
            smooth(Z_MIN.toDouble(), Z_MIN + 24.0, z.toDouble())
        if (w <= 0.0) return -1
        val m = max(0.0, (fbm(x, z, 44, 3) - 0.47) * 16.0)
        return -1 + floor(m * w).toInt()
    }

    /** Oberkante der Fernlandschaft: Gelände, Massiv (mit Weg), Felshorn. */
    fun top(x: Int, z: Int): Int {
        var t = ground(x, z)
        val m = massifTop(x, z)
        if (m > t) t = m
        val p = pathAt(x, z)
        if (!p.isNaN()) t = floor(p).toInt()
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
    const val STONE = 4
    const val ANDESITE = 5
    const val CALCITE = 6
    const val DEEP = 11
    const val TUFF = 12
    const val WALL = 13
    const val LANTERN = 14

    /**
     * Seit 1.3.1 alpin (Crix: „ein Berg wie in den Alpen, nicht aus Eis“): Fels in Schichten (Stein, Andesit, Tuff,
     * dunkler Tiefenschiefer), Schnee auf flachen Stellen und oberhalb der Schneegrenze, Gletschereis nur noch in Rinnen
     * und auf der Schluchtsohle.
     */
    fun material(x: Int, y: Int, z: Int, top: Int, slope: Int, spire: Boolean): Int {
        val depth = top - y
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

    /** Block des Feldes an (x, y, z) relativ, oder -1 (nichts): Kristallsäulen und Zuschauerhügel. */
    fun balcony(x: Int, y: Int, z: Int): Int {
        // Zuschauerhügel südlich des Feldes (Lauffläche 6 über dem Feld)
        val wx = ArenaLayout.WATCH_VAULT.x.toInt()
        val wz = ArenaLayout.WATCH_VAULT.z.toInt()
        if (abs(x - wx) <= 2 && abs(z - wz) <= 2 && y in VY until VY + 6) return if (y == VY + 5) SNOW else PACKED
        val dx = (x - VX).toDouble()
        val dz = (z - VZ).toDouble()
        if (hypot(dx, dz) > ArenaLayout.VAULT_R + 4.0) return -1
        // Säulen: Kristall, Höhe 6–9, Kern blau
        for (p in ArenaLayout.VAULT_PILLARS) {
            val pr = hypot((x - p.x).toDouble(), (z - p.z).toDouble())
            if (pr <= 1.6 && y >= VY) {
                val hgt = 6 + (h32(p.x, 0, p.z, 707) * 4).toInt()
                val taper = if (y - VY > hgt - 3) 1.6 - (y - VY - (hgt - 3)) * 0.45 else 1.6
                if (y < VY + hgt && pr <= taper) return if (pr < 0.8) BLUE else PACKED
            }
        }
        return -1
    }

    fun state(id: Int): BlockState = when (id) {
        WALL -> Blocks.STONE_BRICK_WALL.defaultState
        LANTERN -> Blocks.LANTERN.defaultState
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
        val chunk = world.getChunk(ax shr 4, az shr 4)
        var sets = 0
        fun put(y: Int, state: BlockState) {
            pos.set(ax, origin.y + y, az)
            if (chunk.getBlockState(pos) != state) {
                world.setBlockState(pos, state, ArenaBuilder.FLAGS)
                sets++
            }
        }
        // Das Schneefeld liefert der Weltgenerator (Oberkante y −1); gebaut wird nur, was darüber hinausragt.
        if (top > -1) {
            val n = intArrayOf(FarPlan.top(x + 1, z), FarPlan.top(x - 1, z), FarPlan.top(x, z + 1), FarPlan.top(x, z - 1))
            var lowest = top
            var slope = 0
            for (v in n) {
                val vv = min(v, limit)
                if (vv < lowest) lowest = vv
                slope = max(slope, abs(top - vv))
            }
            val path = FarPlan.pathAt(x, z)
            val bottom = max(-1, min(top - 4, lowest - 1))
            val spire = FarPlan.spireTop(x, z).let { it != Int.MIN_VALUE && it >= FarPlan.massifTop(x, z) }
            for (y in bottom..top) {
                val id = if (!path.isNaN() && y >= top - 1) FarPlan.SNOW else FarPlan.material(x, y, z, top, slope, spire)
                put(y, FarPlan.state(id))
            }
            // Weg: Schneeschichten glätten die Steigung; Laternenpfosten am Außenrand.
            if (!path.isNaN()) {
                val post = FarPlan.postAt(x, z)
                if (post != Int.MIN_VALUE) {
                    put(top + 1, FarPlan.state(FarPlan.WALL))
                    put(top + 2, FarPlan.state(FarPlan.WALL))
                    put(top + 3, FarPlan.state(FarPlan.LANTERN))
                } else {
                    // Kletterstelle: Ranken an der Felsstufe (bis zur nächsten Weghöhe).
                    val face = FarPlan.climbFace(x, z)
                    if (face != null) {
                        val vine = Blocks.VINE.defaultState.with(net.minecraft.block.VineBlock.getFacingProperty(face), true)
                        for (h in 1..FarPlan.CLIMB_H.toInt()) put(top + h, vine)
                    }
                }
            }
        }
        // Feld von Abschnitt III: Säulen und Zuschauerhügel.
        if (abs(x - ArenaLayout.VAULT.x) <= ArenaLayout.VAULT_R + 40 && abs(z - ArenaLayout.VAULT.z) <= ArenaLayout.VAULT_R + 40) {
            for (y in ArenaLayout.VAULT.y..ArenaLayout.VAULT.y + 12) {
                val id = FarPlan.balcony(x, y, z)
                if (id >= 0) put(y, FarPlan.state(id))
            }
        }
        return sets
    }
}
