package dev.henny.hha_arius.arena

import dev.henny.hha_arius.arena.ArenaLayout.COLLAPSE_RINGS
import dev.henny.hha_arius.arena.ArenaLayout.CUT_X
import dev.henny.hha_arius.arena.ArenaLayout.GATE_HALF_WIDTH
import dev.henny.hha_arius.arena.ArenaLayout.GATE_HEIGHT
import dev.henny.hha_arius.arena.ArenaLayout.PILLAR_HALF
import dev.henny.hha_arius.arena.ArenaLayout.PLATEAU_R
import dev.henny.hha_arius.arena.ArenaLayout.RIM_R
import dev.henny.hha_arius.arena.ArenaLayout.SOUTH_WALL_H
import dev.henny.hha_arius.arena.ArenaLayout.TEMPLE_R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Reine Geometrie des Heiligtums (ohne Minecraft-Klassen): Block-Id je relativer Position.
 * Seit 1.13.0 (Paket C): Das kleine Heiligtum (Klingengrab) ist eine würdige offene Säulen-Rotunde des Ordens der
 * Weißen Klinge mit zehn weit begehbaren Bogenjochen, monumentalem Südtor mit Klingenkrone, reich eingelegtem Boden,
 * erhabener Thron-Apsis samt dreidimensionaler Ordensklinge und skulpturalen inneren Klingen-Stelen. Alle Ankerpunkte
 * (`TEMPLE`, `SEAT`, `CUT_STAND`, `WATCH_SEAT`, `DAIS_FRONT`, `ARRIVAL`, `GATE`, `LANDING`, `WATCH_UPPER`,
 * `TEMPLE_PILLARS`) bleiben unverändert.
 */
internal object ArenaPlan {

    /** Fassung des Kernplans; bei Erhöhung wird das Bauvolumen in bestehenden Welten sauber neu gebaut. */
    const val VERSION = 2

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
    const val DBRICK_WALL: Byte = 22
    const val DTILE_WALL: Byte = 23
    const val SOUL_LAMP: Byte = 24
    const val SOUL_HANG: Byte = 25
    const val CHAIN: Byte = 26
    const val DTILE_SLAB: Byte = 27
    const val QSLAB: Byte = 28
    const val LBLUE: Byte = 29

    val NAMES = arrayOf(
        "air", "packed_ice", "blue_ice", "snow_block", "snow1", "snow2", "deepslate_tiles",
        "polished_deepslate", "chiseled_deepslate", "deepslate_bricks", "cobbled_deepslate", "deepslate", "tuff",
        "calcite", "quartz_bricks", "quartz_pillar", "chiseled_quartz_block", "gold_block", "sea_lantern",
        "light_blue_stained_glass", "smooth_quartz", "stone", "deepslate_brick_wall", "deepslate_tile_wall",
        "soul_lantern", "soul_lantern_hanging", "iron_chain", "deepslate_tile_slab", "quartz_slab",
        "light_blue_concrete",
    )

    const val X_MIN = -ArenaLayout.BOUND_XZ
    const val X_MAX = ArenaLayout.BOUND_XZ
    const val Z_MIN = ArenaLayout.BOUND_Z_MIN
    const val Z_MAX = ArenaLayout.BOUND_XZ
    const val Y_MIN = ArenaLayout.BOUND_Y_MIN
    const val Y_MAX = ArenaLayout.BOUND_Y_MAX
    const val NY = Y_MAX - Y_MIN + 1

    private const val TZ = -50 // ArenaLayout.TEMPLE.z
    private const val DEG = PI / 180.0
    private const val M32 = 0xFFFFFFFFL

    private val TEMPLE_P: List<IntArray> = ArenaLayout.TEMPLE_PILLARS.map { intArrayOf(it.x, it.z) }

    /** Zehn äußere Arkadenpfeiler auf Radius 19,6 (Winkel ±18°, ±54°, ±90°, ±126°, ±162°): lassen Tor und Apsis mittig frei. */
    private val COLONNADE_PIERS: List<IntArray> = (0 until 10).map { k ->
        val a = (k * 36.0 + 18.0) * DEG
        intArrayOf(kround(sin(a) * 19.6), TZ + kround(cos(a) * 19.6))
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

    private fun kround(v: Double): Int = floor(v + 0.5).toInt()
    private fun deg(x: Double, z: Double): Double = Math.toDegrees(atan2(x, z))

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

    private val FLOOR_LAMPS: List<IntArray> = intArrayOf(1, 2, 3, 4, 6, 7, 8, 9).map { k ->
        val ra = k * 36.0 * PI / 180.0
        intArrayOf(kround(sin(ra) * 15.5), TZ + kround(cos(ra) * 15.5))
    }

    /** Eingelegter Zeremonialboden der Rotunde: goldenes Zentrum, Frost-Kompass, Kalzitringe und Prozessionsgang. */
    private fun templeFloor(x: Int, z: Int, dt: Double, a: Double): Byte {
        if (dt < 0.7) return GOLD
        if (dt < 2.2) return CQUARTZ
        if (dt < 3.0) return if (abs(x) == 0 || abs(z - TZ) == 0) GOLD else BLUE
        if (dt < 3.9) return PDEEP
        for (p in FLOOR_LAMPS) if (x == p[0] && z == p[1]) return LAMP
        // Prozessionsgang vom Südtor zum Altar
        if (z in -61..-31) {
            if (abs(x) == 3) return CALCITE
            if (abs(x) == 2) return CDEEP
            if (abs(x) <= 1) {
                if (x == 0 && (z == -36 || z == -43 || z == -57)) return GOLD
                if (abs(x) == 0 && z % 2 == 0) return LBLUE
                return PDEEP
            }
        }
        // Konzentrische Ringe und 10-strahlige Frost-Rose
        if (dt in 5.5..6.4) return CALCITE
        if (dt in 9.6..10.5) return PDEEP
        if (dt in 15.2..16.1) return CDEEP
        if (dt in 16.1..17.1) return CALCITE
        if (dt >= 17.1) return PDEEP
        if (dt in 6.4..15.2 && nearStep(a, 36.0, 18.0) * DEG * dt < 0.55) return if (dt < 9.6) LBLUE else CALCITE
        if (dt in 10.5..15.2 && nearStep(a, 36.0, 0.0) * DEG * dt < 0.45) return CDEEP
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
        if (abs(x) <= 3 && z >= -30 && z <= -14) {
            if (z <= -22) return true
            return h32(x, 0, z, 7) < (-14 - z) / 8.0
        }
        return false
    }

    /**
     * Füllt [col] (Länge [NY], Index y − [Y_MIN]) mit den Block-Ids der Spalte (x, z).
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

        // 1) Lauffläche: Heiligtum-Rotunde, gestufte Außenterrasse, Torweg, Schneefeld mit Eisadern
        val surf: Byte = when {
            dt < TEMPLE_R + 0.5 -> templeFloor(x, z, dt, at)
            dt < 20.8 -> PDEEP
            dt < 21.7 -> CALCITE
            dt < 23.0 -> DTILES
            gatePath(x, z) -> if (abs(x) == 3 && z <= -22) CALCITE else if (abs(x) <= 1 && z <= -24) PDEEP else DTILES
            else -> plateauSurface(x, z, r)
        }
        col[-1 - Y_MIN] = surf
        if (dt >= 25.0 && !gatePath(x, z) && (surf == SNOWB || surf == PACKED)) {
            val n = vnoise(x, z, 5, 6)
            if (n > 0.74) col[-Y_MIN] = SNOW2 else if (n > 0.6) col[-Y_MIN] = SNOW1
        }

        // 2) Das Heiligtum (Abschnitt I): offene Säulen-Rotunde, Südtor, Thron-Apsis, Klingen-Stelen, Vorhof
        if (dt < 25.5 || (x in -27..-23 && z in -52..-48) || (abs(x) <= 12 && z in -74..-16)) {
            temple(x, z, dt, at, col)
        }

        // 3) Plateau-Gestaltung: Eis-Obelisken am Rand, Ruinenbögen, Dornenkristalle (Sichtachse nach Süden frei)
        if (ground && dt >= 25.5 && r in 24.0..40.0 && !gatePath(x, z)) dressing(x, z, r, col)
    }

    /** Obelisken (r 35), Ruinenbögen (r 31, Ost/West) und Kristallbüschel; die Sichtachse zur Nadel (Süden) bleibt frei. */
    private fun dressing(x: Int, z: Int, r: Double, col: ByteArray) {
        fun put(y: Int, b: Byte) { if (y - Y_MIN in col.indices) col[y - Y_MIN] = b }
        val a = deg(x.toDouble(), z.toDouble())                    // 0° = Süden (+z)
        if (abs(a) < 55.0) return                                   // Blick zur Nadel frei
        for (i in 0 until 9) {
            val ang = Math.toRadians(70.0 + i * 25.0)
            if (abs(Math.toDegrees(ang) - 180.0) < 22.0) continue
            for (sgn in intArrayOf(1, -1)) {
                val ox = kround(sin(ang) * 35.0) * sgn
                val oz = kround(cos(ang) * 35.0)
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
        val n = h32(x, 0, z, 95)
        if (n < 0.012 + (r - 24.0) / 16.0 * 0.03) {
            val h = 1 + (h32(x, 1, z, 97) * 3).toInt()
            for (y in 0 until h) put(y, if (y == h - 1) BLUE else PACKED)
        }
    }

    /**
     * Skulpturale innere Klingen-Stele auf den zehn [TEMPLE_PILLARS]-Plätzen:
     * Kompakter Sockel (±1), facettierter Quarz-/Eis-Kern mit Kalzitschneiden, schlanke Parierstange und goldener Knauf.
     * Zerbrechlich über [ArenaBuilder.pillarBlocks] (±4, y 0..14) und schlank genug für freie Wegfindung.
     */
    private fun bladeStela(u: Int, v: Int, blade: Int, put: (Int, Byte) -> Unit) {
        val au = abs(u)
        val av = abs(v)
        // Gestufter Sockel (y = 0)
        if (au <= 1 && av <= 1) {
            put(0, if (au == 1 && av == 1) DTILE_SLAB else if (au == 0 && av == 0) CDEEP else PDEEP)
        }
        // Facettierte Klinge (y = 1..blade-1): Kalzitschneiden an ±1, blauer Eiskern mittig, Quarzrippe in v=±1 unten
        if (av == 0 && au <= 1) {
            for (y in 1 until blade) {
                put(y, if (au == 0) (if (y % 3 == 0) BLUE else PACKED) else if (y == 1) QPILLAR else CALCITE)
            }
        } else if (av == 1 && au == 0) {
            for (y in 1 until min(blade - 2, 4)) put(y, DBRICK_WALL)
        }
        // Schlanke Parierstange (y = blade)
        if (av == 0 && au <= 2) {
            put(blade, when (au) {
                2 -> GOLD
                1 -> PDEEP
                else -> CQUARTZ
            })
        } else if (av == 1 && au == 0) {
            put(blade, CDEEP)
        }
        // Griff und goldener Knauf
        if (au == 0 && av == 0) {
            put(blade + 1, DBRICK)
            put(blade + 2, DBRICK_WALL)
            put(blade + 3, GOLD)
        }
    }

    /**
     * Die große 3D-Ordensklinge hinter dem Thron (Mitte x = 0, z = -70, Höhe bis y = 27):
     * Gestufter Altarsockel, 3 Blöcke tiefe facettierte Klinge mit blauem Eiskern und leuchtendem Herz,
     * breit geschwungene Parierstange mit goldenen Enden und aufragendem Knauf.
     */
    private fun greatBlade(x: Int, z: Int, put: (Int, Byte) -> Unit) {
        val u = x
        val v = z + 70 // -70 = Mitte
        val au = abs(u)
        val av = abs(v)
        if (au > 8 || av > 2) return
        // Gestufter Altarsockel im Boden (y 0..2)
        if (au <= 4 && av <= 2) {
            put(0, if (au == 4 || av == 2) PDEEP else CDEEP)
            if (au <= 3 && av <= 1) put(1, if (au == 3) CDEEP else PDEEP)
            if (au <= 2 && av <= 1) put(2, if (au == 0 && av == 0) BLUE else CQUARTZ)
        }
        // 3D-Klingenkörper (y 1..18): außen Kalzit, Mitte glatter Quarz, Kern blaues Eis mit Rückgrat in v=±1
        if (av == 0 && au <= 2) {
            for (y in 2..18) {
                if (y <= 3 && au == 2) continue
                put(y, when (au) {
                    2 -> CALCITE
                    1 -> SQUARTZ
                    else -> if (y % 4 == 0) LAMP else BLUE
                })
            }
        } else if (av == 1 && au <= 1) {
            for (y in 3..17) {
                put(y, if (au == 0) (if (y % 3 == 0) BLUE else PACKED) else if (y in 4..15) QPILLAR else CALCITE)
            }
        }
        // Geschwungene Parierstange (y 18..20)
        if (av == 0 && au <= 7) {
            val gy = if (au >= 5) 20 else 19
            put(gy, when {
                au == 7 -> GOLD
                au == 6 -> CQUARTZ
                au == 0 -> LAMP
                else -> PDEEP
            })
            if (au in 1..4) put(18, CDEEP)
            if (au == 7) put(21, QSLAB)
        }
        if (av == 1 && au <= 2) {
            put(19, if (au == 0) GOLD else CDEEP)
            if (au == 0) put(20, CQUARTZ)
        }
        // Griff und Knauf (y 20..27)
        if (au == 0 && av == 0) {
            for (y in 20..24) put(y, if (y == 22) CQUARTZ else DBRICK)
            put(25, GOLD)
            put(26, CQUARTZ)
            put(27, GOLD)
        } else if (au + av == 1) {
            put(25, CQUARTZ)
        }
    }

    /**
     * Offene Säulen-Rotunde des Ordens der Weißen Klinge:
     * 1) Erhabener Nord-Altar und geschnitzter Thron (exakte Ankerhöhen für SEAT, CUT_STAND, WATCH_SEAT).
     * 2) Monumentale 3D-Ordensklinge und flankierende Gletscherkristalle hinter dem Thron.
     * 3) Zehn äußere Arkadenpfeiler auf Radius 19,6 mit weit offenen Bogenjochen (y 0..7 frei begehbar!),
     *    verwittertem Kranzgesims oben und hängenden Seelenlaternen unter den Scheitelsteinen.
     * 4) Monumentales Südtor mit Zwillingstürmen und Klingen-Bekrönung (Öffnung |x| ≤ 3, y 0..8 komplett frei).
     * 5) Zehn innere Klingen-Stelen auf den [TEMPLE_PILLARS]-Plätzen.
     * 6) Gestufter Zuschauer-Ausguck im Westen (WATCH_UPPER, Lauffläche y 4) und Vorhof-Laternenpfosten.
     */
    private fun temple(x: Int, z: Int, dt: Double, at: Double, col: ByteArray) {
        fun put(y: Int, b: Byte) { if (y - Y_MIN in col.indices) col[y - Y_MIN] = b }
        val inside = dt < TEMPLE_R + 0.5

        // --- 1) Altarstufen und geschnitzter Thron (Nordteil, z <= -62) --------------------------------------------
        if (inside && z <= -62) {
            if (z >= -63) {
                val front = z == -62
                val lamp = front && (x == -9 || x == -5 || x == 5 || x == 9)
                put(0, when {
                    lamp -> LAMP
                    front && abs(x) <= 2 -> CALCITE
                    front -> CDEEP
                    abs(x) <= 2 -> PDEEP
                    else -> DTILES
                })
            } else {
                put(0, PDEEP)
                when {
                    z == -64 -> put(1, if (x == -7 || x == 7 || x == -2 || x == 2) GOLD else CQUARTZ)
                    abs(x) <= 2 && z >= -66 -> put(1, if (x == 0 && z == -65) CQUARTZ else CALCITE)
                    z == -67 && abs(x) <= 6 -> put(1, PDEEP)
                    else -> put(1, DTILES)
                }
            }
            // Geschnitzter Hochlehnen-Thron (SEAT = (0.5, 2.0, -64.5) bleibt frei!)
            if (x in -1..1 && z == -66) {
                for (y in 2..5) put(y, if (x == 0 && y == 4) BLUE else QPILLAR)
                put(6, if (x == 0) GOLD else CQUARTZ)
                if (x == 0) put(7, QSLAB)
            }
            if ((x == -1 || x == 1) && z == -65) put(2, PDEEP)
            if ((x == -2 || x == 2) && z == -66) {
                put(2, CDEEP)
                put(3, DBRICK_WALL)
                put(4, SOUL_LAMP)
            }
            // Flankierende Feuerschalen/Laternenpfeiler am Rand der untersten Stufe (weit abseits aller Anker)
            if ((x == -6 || x == 6) && z == -62) {
                put(1, CDEEP)
                put(2, DBRICK_WALL)
                put(3, SOUL_LAMP)
            }
        }

        // --- 2) Zehn innere Klingen-Stelen (TEMPLE_PILLARS) --------------------------------------------------------
        if (inside) {
            for (p in TEMPLE_P) {
                val rx = p[0]
                val rz = p[1] - TZ
                val alongX = abs(rz) >= abs(rx)
                val u = if (alongX) x - p[0] else z - p[1]
                val v = if (alongX) z - p[1] else x - p[0]
                if (abs(u) > 2 || abs(v) > 1) continue
                val blade = 7 + (h32(p[0], 0, p[1], 31) * 3).toInt()
                bladeStela(u, v, blade, ::put)
            }
        }

        // --- 3) Äußere offene Säulen-Rotunde (dt 18.2..21.6, alle Bogenjoche y 0..7 frei begehbar!) ----------------
        if (dt in 18.0..21.8 && !(abs(x) <= 6 && z >= -34)) {
            // Prüfen, ob Spalte zu einem der zehn Arkadenpfeiler gehört
            var nearPier = false
            var pierCore = false
            for (p in COLONNADE_PIERS) {
                val dx = abs(x - p[0])
                val dz = abs(z - p[1])
                if (dx <= 1 && dz <= 1) {
                    nearPier = true
                    pierCore = (dx == 0 && dz == 0)
                    break
                }
            }
            if (nearPier) {
                // Massiver, fein gegliederter Arkadenpfeiler (y 0..12)
                for (y in 0..11) {
                    val b = when {
                        y == 0 -> CDEEP
                        y == 1 -> PDEEP
                        y == 9 -> CQUARTZ
                        y == 10 -> PDEEP
                        y == 11 -> CALCITE
                        pierCore -> if (y % 3 == 0) BLUE else QPILLAR
                        else -> DBRICK
                    }
                    put(y, b)
                }
                if (pierCore) {
                    put(12, DBRICK_WALL)
                    put(13, SOUL_LAMP)
                }
            } else if (dt in 18.7..20.6) {
                // Hohes Bogen-Gebälk über den offenen Jochen (y 0..7 bleibt komplett offen!)
                val angFromPier = nearStep(at, 36.0, 18.0) // 0° am Pfeiler, 18° in Jochmitte
                // Zwei Joche im fernen Osten/Westen sind leicht verwittert (eingestürztes oberes Gesims)
                val bay = Math.floorMod(floor((at + 180.0) / 36.0).toInt(), 10)
                val weathered = (bay == 2 || bay == 7) && angFromPier > 10.0
                if (!weathered) {
                    // Gestufte Bogenlaibung an den Pfeilern (y 7..8), schlanker Sturz in der Mitte (y 9..11)
                    if (angFromPier < 7.0) put(8, DBRICK)
                    if (angFromPier < 4.5) put(7, CDEEP)
                    put(9, if (angFromPier > 15.0) CQUARTZ else DBRICK)
                    put(10, if (dt < 19.6) QBRICK else PDEEP)
                    if (angFromPier < 14.0 || bay != 3) {
                        put(11, if (dt < 19.6) CALCITE else DTILE_SLAB)
                        if (vnoise(x, z, 4, 71) > 0.65) put(12, SNOW1)
                    }
                    // Hängende Seelenlaterne im Bogenscheitel (über y = 7, freie Kopfhöhe!)
                    if (angFromPier > 16.5 && dt in 19.1..20.1 && abs(z - TZ) > 2) {
                        put(8, CHAIN)
                        put(7, SOUL_HANG)
                    }
                } else {
                    // Verwitterter Bruch: einzelne Eisspitze auf der Bruchkante
                    if (angFromPier < 12.0) put(9, if (h32(x, 9, z, 73) < 0.5) PACKED else DBRICK)
                }
            }
        }

        // --- 4) Monumentale 3D-Ordensklinge & flankierende Eiskristalle hinter dem Thron ---------------------------
        if (z in -72..-68 && abs(x) <= 8) greatBlade(x, z, ::put)
        // Asymmetrische schräge Gletscherkristalle, die die Nord-Apsis hinter den Pfeilern umrahmen
        for (sgn in intArrayOf(-1, 1)) {
            val cx = 10 * sgn
            val cz = -69
            val hgt = if (sgn > 0) 14 else 12
            for (y in 0 until hgt) {
                val leanX = cx + sgn * y * 0.16
                val leanZ = cz - y * 0.12
                val d = hypot(x + 0.5 - (leanX + 0.5), z + 0.5 - (leanZ + 0.5))
                val rad = 1.9 * (1.0 - y / hgt.toDouble()) + 0.45
                if (d <= rad) put(y, if (d < rad * 0.45) BLUE else if (y == 0) PDEEP else PACKED)
            }
        }

        // --- 5) Monumentales Südtor mit Zwillingstürmen und Klingen-Bekrönung (z -32..-28) -------------------------
        if (z in -32..-28 && abs(x) <= 6) {
            val ax = abs(x)
            // Zwillingstürme links und rechts (|x| = 4..6)
            if (ax in 4..6) {
                val towerH = if (ax == 4) 13 else if (ax == 5) 14 else 11
                for (y in 0 until towerH) {
                    val corner = (ax == 6 || z == -32 || z == -28)
                    val b = when {
                        y == 0 -> CDEEP
                        y == 1 -> PDEEP
                        y == towerH - 1 -> if (ax == 5 && z == -30) GOLD else CALCITE
                        y == towerH - 2 -> CQUARTZ
                        ax == 4 && z == -30 -> QPILLAR
                        ax == 5 && (z == -28 || z == -32) && y in 3..10 -> BLUE
                        ax == 5 && z == -30 -> QPILLAR
                        corner -> DBRICK
                        else -> PDEEP
                    }
                    put(y, b)
                }
                if (ax == 5 && z == -30) {
                    put(14, DBRICK_WALL)
                    put(15, SOUL_LAMP)
                }
                if (ax == 4 && z == -28) put(13, QSLAB)
                if (ax == 6 && z == -30) {
                    put(11, DBRICK_WALL)
                    put(12, SOUL_LAMP)
                }
            }
            // Torsturz & Bekrönung über der 7 × 9 Öffnung (y >= 9)
            if (ax <= 3 && z in -31..-29) {
                put(9, if (x == 0 && z == -29) GOLD else if (ax == 3) PDEEP else CDEEP)
                put(10, if (x == 0) CQUARTZ else QBRICK)
                put(11, PDEEP)
                if (ax <= 2 && z == -30) put(12, CALCITE)
                // Kleine weiße Klinge über dem Torbogen (echoes Summit Sword Gate)
                if (z == -30) {
                    if (ax <= 2) put(13, if (ax == 2) GOLD else PDEEP)
                    if (ax <= 1) {
                        for (y in 14..17) put(y, if (ax == 0) BLUE else CALCITE)
                    }
                    if (ax == 0) {
                        put(18, CALCITE)
                        put(19, QSLAB)
                    }
                }
            }
        }
        // Toröffnung garantiert frei halten (|x| <= 3, z -33..-28, y 0..8)
        if (abs(x) <= GATE_HALF_WIDTH && z in -33..-28) {
            for (y in 0 until GATE_HEIGHT) put(y, AIR)
        }

        // --- 6) Vorhof-Laternenpfeiler (weit abseits der LANDING-Plätze) -------------------------------------------
        if ((abs(x) == 8 && z == -25) || (abs(x) == 9 && z == -19)) {
            put(0, CDEEP)
            put(1, DBRICK_WALL)
            put(2, DBRICK_WALL)
            put(3, SOUL_LAMP)
        }

        // --- 7) Gestufter Zuschauer-Ausguck im Westen (WATCH_UPPER = (-24.5, 4.0, -49.5)) -------------------------
        if (x in -26..-24 && z in -51..-49) {
            for (y in 0 until 4) {
                put(y, when {
                    y == 3 -> if (x == -25 && z == -50) CQUARTZ else CALCITE
                    y == 0 -> CDEEP
                    else -> if (h32(x, y, z, 33) < 0.5) DBRICK else PDEEP
                })
            }
        }
    }

    // --- Ereignisse --------------------------------------------------------------------------------------------------

    @Suppress("UNUSED_PARAMETER")
    fun inCleft(x: Int, y: Int, z: Int): Boolean = false

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
