package dev.henny.hha_arius.arena

import dev.henny.hha_arius.arena.ArenaLayout.COLLAPSE_RINGS
import dev.henny.hha_arius.arena.ArenaLayout.CUT_X
import dev.henny.hha_arius.arena.ArenaLayout.GATE_HALF_WIDTH
import dev.henny.hha_arius.arena.ArenaLayout.GATE_HEIGHT
import dev.henny.hha_arius.arena.ArenaLayout.ICE_BOTTOM
import dev.henny.hha_arius.arena.ArenaLayout.PILLAR_H
import dev.henny.hha_arius.arena.ArenaLayout.PILLAR_HALF
import dev.henny.hha_arius.arena.ArenaLayout.PLATEAU_R
import dev.henny.hha_arius.arena.ArenaLayout.RIM_H
import dev.henny.hha_arius.arena.ArenaLayout.RIM_R
import dev.henny.hha_arius.arena.ArenaLayout.SOUTH_WALL_H
import dev.henny.hha_arius.arena.ArenaLayout.TEMPLE_R
import dev.henny.hha_arius.arena.ArenaLayout.TEMPLE_TOP
import dev.henny.hha_arius.arena.ArenaLayout.TEMPLE_WALL_H
import dev.henny.hha_arius.arena.ArenaLayout.VAULT_R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Reine Geometrie des Heiligtums (ohne Minecraft-Klassen): Block-Id je relativer Position. Gespiegelt in
 * `tools/assets/arena_preview.py` (gleiche Formeln, gleicher Hash) – wer hier ändert, ändert dort mit und schaut sich die
 * Vorschau an. Ids siehe [NAMES]; 0 = Luft (wird nur beim Leeren gesetzt).
 */
internal object ArenaPlan {

    const val AIR: Byte = 0
    const val PACKED: Byte = 1
    const val BLUE: Byte = 2
    const val SNOWB: Byte = 3
    const val SNOW1: Byte = 4
    const val SNOW2: Byte = 5
    const val DTILES: Byte = 6
    const val PDEEP: Byte = 7
    const val CDEEP: Byte = 8
    const val DBRICK: Byte = 9
    const val CDS: Byte = 10
    const val DEEP: Byte = 11
    const val TUFF: Byte = 12
    const val CALCITE: Byte = 13
    const val QBRICK: Byte = 14
    const val QPILLAR: Byte = 15
    const val CQUARTZ: Byte = 16
    const val GOLD: Byte = 17
    const val LAMP: Byte = 18
    const val GLASS: Byte = 19
    const val SQUARTZ: Byte = 20

    val NAMES = arrayOf("air", "packed_ice", "blue_ice", "snow_block", "snow1", "snow2", "deepslate_tiles",
        "polished_deepslate", "chiseled_deepslate", "deepslate_bricks", "cobbled_deepslate", "deepslate", "tuff", "calcite",
        "quartz_bricks", "quartz_pillar", "chiseled_quartz_block", "gold_block", "sea_lantern", "light_blue_stained_glass",
        "smooth_quartz")

    const val X_MIN = -ArenaLayout.BOUND_XZ
    const val X_MAX = ArenaLayout.BOUND_XZ
    const val Z_MIN = ArenaLayout.BOUND_Z_MIN
    const val Z_MAX = ArenaLayout.BOUND_XZ
    const val Y_MIN = ArenaLayout.BOUND_Y_MIN
    const val Y_MAX = ArenaLayout.BOUND_Y_MAX
    const val NY = Y_MAX - Y_MIN + 1

    private const val TZ = -50 // ArenaLayout.TEMPLE.z
    private const val VY = -36 // ArenaLayout.VAULT.y
    private const val DEG = PI / 180.0
    private const val M32 = 0xFFFFFFFFL

    private val TEMPLE_P: List<IntArray> = ArenaLayout.TEMPLE_PILLARS.map { intArrayOf(it.x, it.z) }
    // Altes Herzgewölbe unter dem Plateau (bleibt als Hohlraum, in den der Einsturz bricht): eigene Säulenlage.
    private val VAULT_P: List<IntArray> = (0 until 8).map { i ->
        val a = (i + 0.5) / 8.0 * 2.0 * PI
        intArrayOf(Math.round(kotlin.math.sin(a) * 17.0).toInt(), Math.round(kotlin.math.cos(a) * 17.0).toInt())
    }

    // --- Hash und Rauschen -------------------------------------------------------------------------------------------

    fun h32(x: Int, y: Int, z: Int, s: Int): Double {
        var h = ((x * 73856093) xor (y * 19349663) xor (z * 83492791) xor (s * -1640531535)).toLong() and M32
        h = h xor (h ushr 13)
        h = (h * 0x5BD1E995L) and M32
        h = h xor (h ushr 15)
        return h / 4294967296.0
    }

    fun vnoise(x: Int, z: Int, cell: Int, s: Int): Double {
        val fx = x.toDouble() / cell
        val fz = z.toDouble() / cell
        val ix = floor(fx).toInt()
        val iz = floor(fz).toInt()
        var tx = fx - ix
        var tz = fz - iz
        tx = tx * tx * (3.0 - 2.0 * tx)
        tz = tz * tz * (3.0 - 2.0 * tz)
        val a = h32(ix, 0, iz, s)
        val b = h32(ix + 1, 0, iz, s)
        val c = h32(ix, 0, iz + 1, s)
        val d = h32(ix + 1, 0, iz + 1, s)
        val top = a + (b - a) * tx
        val bot = c + (d - c) * tx
        return top + (bot - top) * tz
    }

    private fun clamp01(v: Double) = if (v < 0.0) 0.0 else if (v > 1.0) 1.0 else v
    private fun smooth(v0: Double): Double { val v = clamp01(v0); return v * v * (3.0 - 2.0 * v) }
    private fun kround(v: Double): Int = floor(v + 0.5).toInt()
    private fun deg(x: Double, z: Double): Double = Math.toDegrees(atan2(x, z))

    private fun wrapDeg(a0: Double): Double {
        var a = a0
        while (a > 180.0) a -= 360.0
        while (a < -180.0) a += 360.0
        return a
    }

    /** Winkelabstand (Grad) zum nächsten Vielfachen offset + k · step. */
    private fun nearStep(a: Double, step: Double, offset: Double): Double {
        val q = (a - offset) / step
        return abs(q - floor(q + 0.5)) * step
    }

    // --- Formen ------------------------------------------------------------------------------------------------------

    private fun ellipse(x: Int, z: Int): Double {
        val ex = x / 34.0
        val ez = (z - TZ) / 22.0
        return sqrt(ex * ex + ez * ez)
    }

    fun isGround(x: Int, z: Int): Boolean = sqrt((x * x + z * z).toDouble()) < PLATEAU_R + 0.5 || ellipse(x, z) <= 1.0

    /** (s, sPlateau, sVorplatz): ungefährer Abstand außerhalb der Lauffläche. */
    private fun outsideDist(x: Int, z: Int): DoubleArray {
        val sp = sqrt((x * x + z * z).toDouble()) - (PLATEAU_R + 0.5)
        val e = ellipse(x, z)
        val gx = x / (34.0 * 34.0)
        val gz = (z - TZ) / (22.0 * 22.0)
        val g = sqrt(gx * gx + gz * gz) / max(e, 1e-6)
        val se = (e - 1.0) / max(g, 1e-6)
        return doubleArrayOf(min(sp, se), sp, se)
    }

    /** Höchste Luftschicht des Gewölbes über Radius r. */
    fun vaultTop(r: Double): Double = -17.0 - 9.0 * (r / (VAULT_R + 0.5)) * (r / (VAULT_R + 0.5))

    fun inVaultAir(x: Int, y: Int, z: Int): Boolean {
        val r = sqrt((x * x + z * z).toDouble())
        if (r < VAULT_R + 0.5 && y >= VY && y <= vaultTop(r)) return true
        if (x in -31..-29 && z in -2..2 && y in -30..-27) return !(x == -29 && y == -30)
        return false
    }

    /** Index der Säule, deren 3 × 3-Fläche (x, z) enthält, sonst −1; Versatz in [off]. */
    private fun pillarAt(x: Int, z: Int, pillars: List<IntArray>, off: IntArray): Int {
        for (i in pillars.indices) {
            val p = pillars[i]
            if (abs(x - p[0]) <= PILLAR_HALF && abs(z - p[1]) <= PILLAR_HALF) {
                off[0] = x - p[0]; off[1] = z - p[1]
                return i
            }
        }
        return -1
    }

    private fun wallHeight(x: Int, z: Int): Int {
        val d = outsideDist(x, z)
        val s = d[0]; val sp = d[1]; val se = d[2]
        if (z < -78 || s <= 0.0) return Int.MIN_VALUE
        val thick = 8.0 + 1.5 * vnoise(x, z, 5, 21)
        if (s > thick) return Int.MIN_VALUE
        val a = deg(x.toDouble(), z.toDouble())
        val base: Double = if (se < sp) {
            val t = smooth((TZ - z) / 14.0)
            RIM_H + (28.0 - RIM_H) * t
        } else {
            val aa = abs(a)
            when {
                aa <= 42.0 -> SOUTH_WALL_H.toDouble()
                aa <= 50.0 -> SOUTH_WALL_H + (RIM_H - SOUTH_WALL_H) * smooth((aa - 42.0) / 8.0)
                else -> RIM_H.toDouble()
            }
        }
        val jag = (vnoise(x, z, 4, 22) - 0.5) * 5.0 + (vnoise(x, z, 11, 23) - 0.5) * 4.0
        var h = base + jag
        h = h * (0.25 + 0.75 * smooth(s / 2.5))
        val out = thick - s
        if (out < 2.0) h = h - (2.0 - out) * 2.5
        var top = floor(h).toInt()
        if (top < 2) top = 2
        if (top > Y_MAX - 1) top = Y_MAX - 1
        return top
    }

    private fun iceBody(x: Int, y: Int, z: Int): Byte {
        val band = Math.floorMod(y + floor(vnoise(x, z, 9, 31) * 5.0).toInt(), 6)
        if (band == 0 && vnoise(x, z, 7, 32) > 0.45) return BLUE
        return PACKED
    }

    private fun glacierBlock(x: Int, y: Int, z: Int, top: Int, cliff: Boolean): Byte {
        if (y == top - 1) return SNOWB
        if (cliff && y < 4 + floor(vnoise(x, z, 6, 41) * 6.0).toInt()) {
            val k = h32(x, y, z, 42)
            return if (k < 0.45) DEEP else if (k < 0.8) CDS else TUFF
        }
        val band = Math.floorMod(y + floor(vnoise(x, z, 8, 43) * 4.0).toInt(), 7)
        if (band <= 1) return BLUE
        if (band == 4 && h32(x, y, z, 44) < 0.25) return SNOWB
        return PACKED
    }

    private val FLOOR_LAMPS: List<IntArray> = intArrayOf(1, 2, 3, 4, 6, 7, 8, 9).map { k ->
        val ra = k * 36.0 * PI / 180.0
        intArrayOf(kround(kotlin.math.sin(ra) * 15.5), TZ + kround(kotlin.math.cos(ra) * 15.5))
    }

    private fun templeFloor(x: Int, z: Int, dt: Double, a: Double): Byte {
        if (dt < 0.5) return GOLD
        if (dt < 2.5) return CQUARTZ
        if (dt >= 5.5 && dt < 6.5) return CALCITE
        if (dt >= 9.8 && dt < 10.8) return PDEEP
        for (p in FLOOR_LAMPS) if (x == p[0] && z == p[1]) return LAMP
        if (z > -62) {
            if (abs(x) == 3) return CALCITE
            if (abs(x) <= 2) return PDEEP
        }
        if (dt >= 6.5 && dt < 9.8 && nearStep(a, 36.0, 18.0) * DEG * dt < 0.6) return CDEEP
        return DTILES
    }

    private fun plateauSurface(x: Int, z: Int, r: Double): Byte {
        val vein = abs(vnoise(x, z, 11, 4) - 0.5)
        if (vein < 0.03) return PACKED
        val patch = vnoise(x, z, 9, 5)
        if (r > 30.0 && patch > 0.6) return PACKED
        if (r <= 30.0 && patch > 0.8) return PACKED
        return SNOWB
    }

    fun gatePath(x: Int, z: Int): Boolean {
        if (abs(x) <= 3 && z >= -29 && z <= -14) {
            if (z <= -23) return true
            return h32(x, 0, z, 7) < (-14 - z) / 9.0
        }
        return false
    }

    /** Füllt [col] (Länge [NY], Index y − [Y_MIN]) mit den Block-Ids der Spalte (x, z). */
    fun column(x: Int, z: Int, col: ByteArray) {
        java.util.Arrays.fill(col, AIR)
        val r = sqrt((x * x + z * z).toDouble())
        val dz = z - TZ
        val dt = sqrt((x * x + dz * dz).toDouble())
        val at = deg(x.toDouble(), dz.toDouble())
        val ground = isGround(x, z)

        // 1) Boden / Eisplatte bzw. Gletscherwand
        if (ground) {
            for (y in ICE_BOTTOM until -1) col[y - Y_MIN] = iceBody(x, y, z)
            val surf: Byte = when {
                dt < TEMPLE_R + 0.5 -> templeFloor(x, z, dt, at)
                dt < 20.5 -> PDEEP
                dt < 21.5 -> DTILES
                dt < 22.5 -> PDEEP
                gatePath(x, z) -> DTILES
                else -> plateauSurface(x, z, r)
            }
            col[-1 - Y_MIN] = surf
            if (dt >= 25.0 && !gatePath(x, z) && (surf == SNOWB || surf == PACKED)) {
                val n = vnoise(x, z, 5, 6)
                if (n > 0.74) col[-Y_MIN] = SNOW2 else if (n > 0.6) col[-Y_MIN] = SNOW1
            }
        } else {
            val top = wallHeight(x, z)
            if (top != Int.MIN_VALUE) {
                val d = outsideDist(x, z)
                val cliff = d[2] < d[1] && z < -56
                for (y in ICE_BOTTOM until top) col[y - Y_MIN] = glacierBlock(x, y, z, top, cliff)
            }
        }

        // 2) Herzgewölbe
        if (r < 32.5) {
            for (y in Y_MIN until ICE_BOTTOM) {
                if (inVaultAir(x, y, z)) continue
                col[y - Y_MIN] = vaultBlock(x, y, z, r)
            }
            val off = IntArray(2)
            val p = pillarAt(x, z, VAULT_P, off)
            if (p >= 0) {
                val ox = off[0]; val oz = off[1]
                val ytop = floor(vaultTop(r)).toInt()
                for (y in VY..ytop) {
                    val b: Byte = when {
                        y <= VY + 1 -> if (y == VY) CDEEP else PDEEP
                        y == ytop -> if ((ox == 0) != (oz == 0)) LAMP else BLUE
                        ox != 0 && oz != 0 -> PACKED
                        else -> BLUE
                    }
                    col[y - Y_MIN] = b
                }
            }
            if (r <= 12.5 && p < 0) {
                val n = vnoise(x, z, 4, 61)
                if (n > 0.4) col[VY - Y_MIN] = if (n > 0.7) SNOW2 else SNOW1
            }
        }

        // 3) Tempel
        if (dt < 24.5 || (x in -24..-21 && z in -54..-48)) temple(x, z, dt, at, col)

        // 4) Plateau-Gestaltung (seit 1.2.2): Eis-Obelisken am Rand, Ruinenbögen, Dornenkristalle (Deckung und Bild)
        if (ground && dt >= 25.0 && r in 24.0..40.0 && !gatePath(x, z)) dressing(x, z, r, col)
    }

    /** Obelisken (r 35), Ruinenbögen (r 31, Ost/West) und Kristallbüschel; die Sichtachse zur Nadel (Süden) bleibt frei. */
    private fun dressing(x: Int, z: Int, r: Double, col: ByteArray) {
        fun put(y: Int, b: Byte) { if (y - Y_MIN in col.indices) col[y - Y_MIN] = b }
        val a = deg(x.toDouble(), z.toDouble())                    // 0° = Süden (+z)
        if (abs(a) < 55.0) return                                   // Blick zur Nadel frei
        // Obelisken: 9 Stück auf r 35, 3 × 3 unten, spitz zulaufend, blauer Kern
        for (i in 0 until 9) {
            val ang = Math.toRadians(70.0 + i * 25.0)
            if (abs(Math.toDegrees(ang) - 180.0) < 22.0) continue   // nicht direkt vor dem Tempel
            for (sgn in intArrayOf(1, -1)) {
                val ox = kround(kotlin.math.sin(ang) * 35.0) * sgn
                val oz = kround(kotlin.math.cos(ang) * 35.0)
                val dx = abs(x - ox)
                val dzz = abs(z - oz)
                if (dx > 1 || dzz > 1) continue
                val h = 9 + (h32(ox, 0, oz, 91) * 6).toInt()
                for (y in 0 until h) {
                    val half = if (y < h * 0.45) 1 else if (y < h - 2) (if (dx + dzz <= 1) 1 else -1) else 0
                    if (half < 0) continue
                    if (dx > half || dzz > half) continue
                    val core = dx == 0 && dzz == 0
                    put(y, if (y == h - 1) BLUE else if (core) BLUE else if (y == 0) PDEEP else PACKED)
                }
            }
        }
        // Ruinenbögen: je einer im Osten und Westen auf r 31 (zwei Pfeiler, halb eingestürzter Sturz)
        for (sgn in intArrayOf(1, -1)) {
            val cx = 31 * sgn
            val cz = -4
            val inPillarA = abs(x - cx) <= 1 && abs(z - (cz - 3)) <= 0
            val inPillarB = abs(x - cx) <= 1 && abs(z - (cz + 3)) <= 0
            if (inPillarA || inPillarB) {
                val h = if (inPillarA) 7 else 5
                for (y in 0 until h) put(y, if (y == 0) PDEEP else if (y == h - 1) CQUARTZ else QBRICK)
            }
            if (abs(x - cx) <= 1 && z in (cz - 3)..(cz + 0)) put(7, if (z == cz) CQUARTZ else QBRICK)
            if (x == cx && z == cz + 1 && h32(x, 7, z, 93) < 0.5) put(6, QBRICK)
        }
        // Dornenkristalle: verstreute blaue Spitzen (1–3 hoch), dichter zum Rand
        val n = h32(x, 0, z, 95)
        if (n < 0.012 + (r - 24.0) / 16.0 * 0.03) {
            val h = 1 + (h32(x, 1, z, 97) * 3).toInt()
            for (y in 0 until h) put(y, if (y == h - 1) BLUE else PACKED)
        }
    }

    private fun vaultBlock(x: Int, y: Int, z: Int, r: Double): Byte {
        val a = deg(x.toDouble(), z.toDouble())
        if (y == VY - 1 && r < VAULT_R + 0.5) return vaultFloor(x, z, r, a)
        if (y < VY - 1) return if (h32(x, y, z, 51) < 0.6) DEEP else CDS
        if (r >= VAULT_R + 0.5) {
            if (y == -32 && r < VAULT_R + 1.5 && nearStep(a, 22.5, 11.25) * DEG * r < 0.55 && !(abs(z) <= 4 && x < 0)) {
                return LAMP
            }
            if (y <= VY + 1) return PDEEP
            val band = Math.floorMod(y + floor(vnoise(x, z, 6, 52) * 3.0).toInt(), 5)
            return if (band == 0) PACKED else BLUE
        }
        val shell = y - vaultTop(r)
        if (shell < 1.5) {
            if (r < 26.0 && abs(r - 22.0) < 0.75 && nearStep(a, 30.0, 0.0) * DEG * r < 0.55) return LAMP
            if (nearStep(a, 45.0, 22.5) * DEG * max(r, 1.0) < 0.7 && r > 4.0) return BLUE
        }
        if (x == -31 && z == 0 && y == -26) return LAMP
        return if (shell < 3.0) iceBody(x, y, z) else PACKED
    }

    private val GLYPHS = intArrayOf(0b010111010, 0b101010101, 0b111101111, 0b100111001, 0b011010110, 0b110010011,
        0b010101010, 0b111010111, 0b001111100, 0b101111101, 0b010110011, 0b110101011)

    private fun vaultFloor(x: Int, z: Int, r: Double, a: Double): Byte {
        if (x == 0 && z == 0) return GOLD
        if (r < 2.5) return PDEEP
        if (r < 3.5) return CQUARTZ
        if (r >= 7.0 && r < 8.0) return CALCITE
        if (r >= 12.0 && r < 13.0) return CALCITE
        if (r >= 8.0 && r < 12.0) {
            val k = Math.floorMod(floor((a + 180.0) / 30.0).toInt(), 12)
            val center = -180.0 + (k + 0.5) * 30.0
            val u = floor(r - 10.0 + 0.5).toInt()
            val v = floor(wrapDeg(a - center) * DEG * r / 1.1 + 0.5).toInt()
            if (u in -1..1 && v in -1..1) {
                val bit = (u + 1) * 3 + (v + 1)
                if ((GLYPHS[k] shr (8 - bit)) and 1 == 1) return CQUARTZ
            }
            return DTILES
        }
        if (r >= 13.0 && r < 18.5 && nearStep(a, 45.0, 22.5) * DEG * r < 0.55) return CDEEP
        if (r >= VAULT_R - 1.5) return PDEEP
        return DTILES
    }

    private fun temple(x: Int, z: Int, dt: Double, at: Double, col: ByteArray) {
        fun put(y: Int, b: Byte) { col[y - Y_MIN] = b }
        val dz = z - TZ
        val inside = dt < TEMPLE_R + 0.5
        // Altarstufen
        if (inside && z <= -62) {
            if (z >= -63) {
                val front = z == -62
                val lamp = front && (x == -10 || x == -6 || x == 6 || x == 10)
                put(0, if (lamp) LAMP else if (front) CDEEP else PDEEP)
            } else {
                put(0, PDEEP)
                when {
                    z == -64 -> put(1, if (x == -8 || x == 8) GOLD else CQUARTZ)
                    abs(x) <= 2 && z >= -66 -> put(1, CALCITE)
                    else -> put(1, DTILES)
                }
            }
            if (x in -1..1 && z == -66) {
                for (y in 2 until 5) put(y, QPILLAR)
                put(5, if (x == 0) GOLD else CQUARTZ)
            }
            if ((x == -1 || x == 1) && z == -65) put(2, PDEEP)
        }
        // Säulen
        if (inside) {
            val off = IntArray(2)
            if (pillarAt(x, z, TEMPLE_P, off) >= 0) {
                val ox = off[0]; val oz = off[1]
                for (y in 0 until PILLAR_H) {
                    val b: Byte = when {
                        y == 0 -> PDEEP
                        y == PILLAR_H - 1 -> CQUARTZ
                        ox != 0 && oz != 0 -> BLUE
                        else -> PACKED
                    }
                    put(y, b)
                }
            }
        }
        // Mauer
        if (dt >= TEMPLE_R + 0.5 && dt < 20.5) {
            val inner = dt < 19.5
            for (y in 0 until TEMPLE_WALL_H) {
                var b: Byte
                if (y == 0 || y == TEMPLE_WALL_H - 1) b = PDEEP
                else if (y == TEMPLE_WALL_H - 2) b = CDEEP
                else if (inner) {
                    b = QBRICK
                    if (y == 7 && nearStep(at, 36.0, 18.0) * DEG * dt < 0.5) b = LAMP
                } else {
                    b = DBRICK
                    if (nearStep(at, 36.0, 18.0) * DEG * dt < 0.8) b = QPILLAR
                }
                if (y in 3..8 && nearStep(at, 36.0, 0.0) * DEG * dt < 0.6) {
                    val k = floor(at / 36.0 + 0.5).toInt()
                    val km = k % 10
                    if (km != 0 && km != 5 && km != -5) b = GLASS
                }
                put(y, b)
            }
            if (dz < 0 && abs(x) <= 3 && inner) {
                for (y in 2 until 11) {
                    if (abs(x) <= 2 && y <= 9) put(y, BLUE)
                    else if (abs(x) == 3 || y == 10) put(y, if (x == 0 && y == 10) GOLD else CQUARTZ)
                }
            }
            if (dt >= 19.5 && !(abs(x) <= 5 && dz > 0)) {
                if (Math.floorMod(floor((at + 180.0) * DEG * 20.0 / 2.0).toInt(), 2) == 0) put(TEMPLE_WALL_H, PDEEP)
            }
        }
        // Decke des Umgangs mit Lichtschächten, Westöffnung zur Galerie
        if (dt >= 10.5 && dt < TEMPLE_R + 0.5) {
            val shaft = dt >= 14.5 && dt < 17.5 && nearStep(at, 36.0, 0.0) * DEG * dt < 1.6
            val west = x < 0 && abs(z + 51) <= 3 && dt >= 13.5
            if (!shaft && !west) {
                put(TEMPLE_WALL_H - 1, DTILES)
                if (dt >= 12.0 && dt < 18.5 && vnoise(x, z, 4, 71) > 0.62) put(TEMPLE_WALL_H, SNOW1)
            }
        }
        // Tambour
        if (dt >= 10.5 && dt < 11.5) {
            for (y in TEMPLE_WALL_H until 15) {
                put(y, if (y == 13 && nearStep(at, 36.0, 18.0) * DEG * dt < 0.6) GLASS else QBRICK)
            }
        }
        // Kuppel
        if (dt >= 1.5 && dt < 11.5) {
            val yo = 14.0 + 11.0 * sqrt(max(0.0, 1.0 - (dt / 11.5) * (dt / 11.5)))
            val yi = if (dt < 10.5) 14.0 + 10.0 * sqrt(max(0.0, 1.0 - (dt / 10.5) * (dt / 10.5))) else 14.0
            val lo = max(15, kround(yi))
            val hi = min(TEMPLE_TOP, kround(yo))
            val rib = nearStep(at, 36.0, 18.0) * DEG * dt < 0.8
            for (y in lo..hi) {
                put(y, when {
                    dt < 2.5 -> GOLD
                    rib -> PDEEP
                    y < 21 -> CALCITE
                    else -> SQUARTZ
                })
            }
        }
        // Südtor mit Portal
        if (z in -31..-28 && abs(x) <= 5) {
            val ax = abs(x)
            if (ax >= 4) {
                for (y in 0 until 11) put(y, if (ax == 4) QPILLAR else PDEEP)
                if (ax == 5 && z == -28) put(7, LAMP)
            }
            for (y in 9..10) if (ax <= 3) put(y, if (x == 0 && y == 9 && z == -28) GOLD else CDEEP)
            put(11, PDEEP)
            if (ax <= 3) put(12, CQUARTZ)
            if (ax <= 1) put(13, PDEEP)
        }
        if (abs(x) <= GATE_HALF_WIDTH && z in -33..-28) {
            for (y in 0 until GATE_HEIGHT) put(y, AIR)
        }
        // Galerie an der Westseite
        if (x in -24..-21 && z in -54..-48) {
            put(TEMPLE_WALL_H, PDEEP)
            if (x == -24 || z == -54 || z == -48) put(TEMPLE_WALL_H + 1, CDEEP)
            if (x >= -22) put(TEMPLE_WALL_H - 1, DBRICK)
            if (x == -23 && (z == -53 || z == -49)) for (y in 0 until TEMPLE_WALL_H) put(y, DBRICK)
        }
        if (x < 0 && abs(z + 51) <= 2 && dt >= 10.5 && dt < 11.5) put(13, AIR)
    }

    // --- Ereignisse --------------------------------------------------------------------------------------------------

    fun inCleft(x: Int, y: Int, z: Int): Boolean {
        if (y < 0 || z <= 0) return false
        val r = sqrt((x * x + z * z).toDouble())
        if (r < PLATEAU_R + 0.5) return false
        if (abs(deg(x.toDouble(), z.toDouble())) > 50.0) return false
        val deep = if (y >= SOUTH_WALL_H - 8) RIM_R + 2 else RIM_R - 2
        if (r > deep) return false
        val hw = 1.5 + 2.0 * y / SOUTH_WALL_H
        return abs(x + 0.5 - CUT_X) <= hw
    }

    fun collapseRange(ring: Int): DoubleArray {
        val lo = if (ring <= 0) 0.0 else COLLAPSE_RINGS[ring - 1]
        return doubleArrayOf(lo, COLLAPSE_RINGS[ring.coerceIn(0, COLLAPSE_RINGS.size - 1)])
    }

    fun rubbleHeight(x: Int, z: Int): Int {
        if (x < 5) return 0
        val dz = z - TZ
        val dt = sqrt((x * x + dz * dz).toDouble())
        if (gatePath(x, z) || (abs(x) <= GATE_HALF_WIDTH + 1 && z > -34)) return 0
        val ex = (x - 27.0) / 9.0
        val ez = (z - TZ) / 13.0
        val mound = 6.5 * (1.0 - ex * ex - ez * ez) + (vnoise(x, z, 3, 81) - 0.5) * 3.0
        var h = if (mound > 0.0) floor(mound).toInt() else 0
        if (dt < TEMPLE_R + 2.5) {
            val k = h32(x, 0, z, 82)
            if (k > 0.93) h = max(h, 2) else if (k > 0.72) h = max(h, 1)
        }
        return min(h, 8)
    }

    private val RUBBLE = byteArrayOf(QBRICK, DTILES, PACKED, CALCITE, DBRICK, BLUE, PDEEP, SNOWB, PACKED, QBRICK)

    fun rubbleBlock(x: Int, y: Int, z: Int): Byte {
        if (h32(x, y, z, 84) < 0.18) return RUBBLE[(h32(x, y, z, 83) * RUBBLE.size).toInt()]
        return RUBBLE[(h32(x shr 1, y shr 1, z shr 1, 83) * RUBBLE.size).toInt()]
    }
}
