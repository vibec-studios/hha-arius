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
    const val STONE: Byte = 21

    val NAMES = arrayOf("air", "packed_ice", "blue_ice", "snow_block", "snow1", "snow2", "deepslate_tiles",
        "polished_deepslate", "chiseled_deepslate", "deepslate_bricks", "cobbled_deepslate", "deepslate", "tuff", "calcite",
        "quartz_bricks", "quartz_pillar", "chiseled_quartz_block", "gold_block", "sea_lantern", "light_blue_stained_glass",
        "smooth_quartz", "stone")

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

    /**
     * Füllt [col] (Länge [NY], Index y − [Y_MIN]) mit den Block-Ids der Spalte (x, z). Seit 1.4.2 steht alles auf dem
     * endlosen Schneefeld des Weltgenerators (Stein bis y −6, Schnee bis −1): Der Plan füllt den Untergrund genauso, damit
     * das Leeren beim Neubau keine Löcher reißt. Keine Gletscherwände und kein Gewölbe mehr.
     */
    fun column(x: Int, z: Int, col: ByteArray) {
        java.util.Arrays.fill(col, AIR)
        for (y in Y_MIN until -5) col[y - Y_MIN] = STONE
        for (y in -5 until -1) col[y - Y_MIN] = SNOWB
        val r = sqrt((x * x + z * z).toDouble())
        val dz = z - TZ
        val dt = sqrt((x * x + dz * dz).toDouble())
        val at = deg(x.toDouble(), dz.toDouble())
        val ground = isGround(x, z)

        // 1) Lauffläche: Klingengrab, Ringwege, Torweg, sonst Schnee mit Eisadern
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

        // 2) Klingengrab (Abschnitt I): offene Terrasse mit Steinklingen statt Tempel
        if (dt < 24.5 || (x in -26..-24 && z in -51..-49)) temple(x, z, dt, at, col)

        // 3) Plateau-Gestaltung: Eis-Obelisken am Rand, Ruinenbögen, Dornenkristalle (Deckung und Bild)
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

    /**
     * Eine Steinklinge, mit der Spitze im Boden: [u] entlang der Schneide (Breite 2 · [half] + 1), [v] quer (Dicke 1),
     * Klinge bis [blade] hoch, darüber Parierstange, Griff und Knauf. Kanten weiß (Kalzit), Hohlkehle Eis.
     */
    private fun sword(u: Int, v: Int, half: Int, blade: Int, glow: Boolean, put: (Int, Byte) -> Unit) {
        val au = abs(u)
        val av = abs(v)
        if (av == 0 && au <= half) {
            for (y in 0 until blade) {
                val tipIn = y < 2 && au == half  // leichte Verjüngung am Boden
                if (tipIn) continue
                put(y, when {
                    au == half -> CALCITE
                    au == 0 -> if (glow) BLUE else PACKED
                    else -> SQUARTZ
                })
            }
        }
        val guard = half * 2 + 2
        if (av == 0 && au <= guard) put(blade, if (au == guard) GOLD else PDEEP)
        if (av == 1 && au <= 1) put(blade, CDEEP)
        if (av == 0 && au == 0) {
            if (glow) put(blade, LAMP)
            val grip = maxOf(2, half + 1)
            for (y in blade + 1..blade + grip) put(y, DBRICK)
            put(blade + grip + 1, GOLD)
        }
    }

    /**
     * Das Klingengrab (seit 1.4.2 statt des Kuppeltempels; Crix: „für die erste Phase denk dir was anderes aus“): eine
     * offene, runde Terrasse unter freiem Himmel, umstellt von zehn Steinklingen der gefallenen Weißen Klingen, die mit
     * der Spitze im Boden stecken. Hinter Arius' Thron ragt die große Klinge des Ordens auf (24 hoch); zwei Klingen
     * flankieren den Torweg im Süden. Der Schnitt teilt Terrasse und große Klinge.
     */
    private fun temple(x: Int, z: Int, dt: Double, at: Double, col: ByteArray) {
        fun put(y: Int, b: Byte) { if (y - Y_MIN in col.indices) col[y - Y_MIN] = b }
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
        // Die zehn Klingen des Rings (an den alten Säulenplätzen), Schneide quer zum Radius
        if (inside) {
            for (p in TEMPLE_P) {
                val rx = p[0]
                val rz = p[1] - TZ
                val alongX = abs(rz) >= abs(rx)
                val u = if (alongX) x - p[0] else z - p[1]
                val v = if (alongX) z - p[1] else x - p[0]
                if (abs(u) > 4 || abs(v) > 1) continue
                val blade = 8 + (h32(p[0], 0, p[1], 31) * 3).toInt()
                sword(u, v, 1, blade, false, ::put)
            }
        }
        // Die große Klinge hinter dem Thron (Schneide in x-Richtung)
        if (z in -71..-69 && abs(x) <= 7) sword(x, z + 70, 2, 18, true, ::put)
        // Torklingen links und rechts des Torwegs
        if (z in -32..-30) {
            for (cx in intArrayOf(-5, 5)) if (abs(x - cx) <= 4) sword(x - cx, z + 31, 1, 7, false, ::put)
        }
        if (abs(x) <= GATE_HALF_WIDTH && z in -33..-28) {
            for (y in 0 until GATE_HEIGHT) put(y, AIR)
        }
        // Zuschauerfels im Westen (Lauffläche y 4)
        if (x in -26..-24 && z in -51..-49) {
            for (y in 0 until 4) put(y, if (y == 3) SNOWB else if (h32(x, y, z, 33) < 0.5) STONE else PDEEP)
        }
    }

    // --- Ereignisse --------------------------------------------------------------------------------------------------

    /** Seit 1.4.2 gibt es keinen Südwall mehr, also auch keine Kluft. */
    @Suppress("UNUSED_PARAMETER")
    fun inCleft(x: Int, y: Int, z: Int): Boolean {
        return false
    }

    @Suppress("unused")
    private fun oldCleft(x: Int, y: Int, z: Int): Boolean {
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
