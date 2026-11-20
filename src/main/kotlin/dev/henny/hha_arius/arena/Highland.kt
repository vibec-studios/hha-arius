package dev.henny.hha_arius.arena

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Das Hochland von Eirholm bekommt ein eigenes Gesicht (seit 1.8.1, Crix: „das Hochland braucht eigene Sachen“):
 * * **Frostwälder:** zusammenhängende Haine aus verschneiten Fichten (Schneebrocken in den Zweigen), am Rand tote,
 *   kahle Stämme; dort wachsen keine Eisspitzen.
 * * **Findlinge** aus geschichtetem Fels mit Schneekappen und **gefrorene Seen** aus festem Eis mit blauen Rissen.
 * * **Reste des Ordens der weißen Klinge:** riesige Steinklingen, die schräg mit der Spitze im Schnee stecken;
 *   eingestürzte Wachtürme mit Seelenlaternen; Schwurkreise aus Menhiren um ein Schwert im Stein; Klingengräber, ein
 *   Feld aus schimmernden Klingen.
 * Alles wird deterministisch aus der Lage berechnet (gleich in jeder Instanz), nur auf ebenem Hochland, nie auf den
 * Kampfflächen, am Weg der Jagd oder auf den Bergen.
 */
internal object Highland {

    const val TREE = 1
    const val DEAD = 2
    const val BOULDER = 3
    const val POND = 4
    const val BLADE = 5
    const val TOWER = 6
    const val CIRCLE = 7
    const val GRAVES = 8

    /** Ein Objekt im Hochland: Mitte (relativ), Grundhöhe (erste Luftschicht über dem Boden), Reichweite, Samen. */
    class Deco(val type: Int, val x: Int, val z: Int, val base: Int, val r: Int, val seed: Int)

    /** Anteil Frostwald an (x, z), 0..1 (zusammenhängende Gebiete). */
    fun forest(x: Int, z: Int): Double {
        val n = FarPlan.vnoise(x, z, 84, 1201) * 0.75 + FarPlan.vnoise(x, z, 23, 1203) * 0.25
        return FarPlan.smooth(0.57, 0.67, n)
    }

    private const val BUCKET = 32

    private val buckets = HashMap<Long, ArrayList<Deco>>()

    /** Wahrzeichen (Klingen, Türme, Schwurkreise, Klingengräber) für Szenen und Umgebungseffekte. */
    val landmarks: List<Deco>

    private fun bkey(bx: Int, bz: Int): Long = (bx.toLong() shl 32) or (bz.toLong() and 0xFFFFFFFFL)

    /** Ebener, freier Platz mit Radius r um (x, z)? */
    private fun free(x: Int, z: Int, r: Int, points: Int): Boolean {
        if (x - r < FarPlan.X_MIN + 4 || x + r > FarPlan.X_MAX - 4 || z - r < FarPlan.Z_MIN + 4 || z + r > FarPlan.Z_MAX - 4) return false
        for (k in 0..points) {
            val px: Int
            val pz: Int
            if (k == points) {
                px = x; pz = z
            } else {
                val a = k * 2 * PI / points
                px = x + Math.round(cos(a) * (r + 3)).toInt()
                pz = z + Math.round(sin(a) * (r + 3)).toInt()
            }
            if (FarPlan.keepClear(px, pz) || FarPlan.inCore(px, pz)) return false
            if (FarPlan.massifTop(px, pz) != Int.MIN_VALUE || FarPlan.peakTop(px, pz) != Int.MIN_VALUE) return false
            if (FarPlan.aerieRim(px, pz) != Int.MIN_VALUE || FarPlan.spireTop(px, pz) != Int.MIN_VALUE) return false
            if (FarPlan.ground(px, pz) > 5) return false
        }
        return true
    }

    init {
        val all = ArrayList<Deco>()
        val marks = ArrayList<Deco>()
        fun clash(x: Int, z: Int, r: Int, list: List<Deco>, gap: Int): Boolean =
            list.any { hypot((it.x - x).toDouble(), (it.z - z).toDouble()) < it.r + r + gap }

        fun scan(cell: Int, salt: Int, chance: (Int, Int) -> Double, make: (Int, Int, Int) -> Deco?) {
            for (cx in Math.floorDiv(FarPlan.X_MIN, cell)..Math.floorDiv(FarPlan.X_MAX, cell)) {
                for (cz in Math.floorDiv(FarPlan.Z_MIN, cell)..Math.floorDiv(FarPlan.Z_MAX, cell)) {
                    val x = cx * cell + 2 + (FarPlan.h32(cx, 1, cz, salt + 1) * (cell - 4)).toInt()
                    val z = cz * cell + 2 + (FarPlan.h32(cx, 2, cz, salt + 2) * (cell - 4)).toInt()
                    if (FarPlan.h32(cx, 0, cz, salt) >= chance(x, z)) continue
                    val seed = (FarPlan.h32(cx, 3, cz, salt + 3) * 1_000_000).toInt()
                    make(x, z, seed)?.let { all.add(it) }
                }
            }
        }

        fun base(x: Int, z: Int) = FarPlan.ground(x, z) + 1

        // Wahrzeichen zuerst (groß, selten), mit Abstand zueinander
        val big = listOf(
            Triple(BLADE, 96, 14), Triple(TOWER, 112, 7), Triple(CIRCLE, 150, 11), Triple(GRAVES, 128, 13),
        )
        for ((type, cell, r) in big) {
            scan(cell, 1300 + type * 17, { _, _ -> 0.5 }) { x, z, seed ->
                if (!free(x, z, r, 8) || clash(x, z, r, marks, 16)) null
                else Deco(type, x, z, base(x, z), r, seed).also { marks.add(it) }
            }
        }
        // Gefrorene Seen (nur auf ganz ebenem Grund)
        scan(70, 1500, { _, _ -> 0.35 }) { x, z, seed ->
            val r = 7 + seed % 10
            if (!free(x, z, r, 8) || clash(x, z, r, all, 6)) return@scan null
            for (k in 0 until 8) {
                val a = k * PI / 4
                if (FarPlan.ground(x + Math.round(cos(a) * r).toInt(), z + Math.round(sin(a) * r).toInt()) != -1) return@scan null
            }
            if (FarPlan.ground(x, z) != -1) null else Deco(POND, x, z, 0, r, seed)
        }
        // Findlinge
        scan(21, 1600, { x, z -> 0.18 + 0.15 * (1.0 - forest(x, z)) }) { x, z, seed ->
            val r = 2 + seed % 3
            if (!free(x, z, r, 4) || clash(x, z, r, all, 3)) null else Deco(BOULDER, x, z, base(x, z), r, seed)
        }
        // Frostwälder (dicht in den Waldgebieten, vereinzelt draußen; am Rand und draußen oft tote Stämme)
        scan(6, 1700, { x, z -> 0.78 * forest(x, z) + 0.012 }) { x, z, seed ->
            if (!free(x, z, 3, 4) || clash(x, z, 3, marks, 4)) return@scan null
            val f = forest(x, z)
            val dead = f < 0.35 && seed % 10 < 6 || seed % 10 == 0
            Deco(if (dead) DEAD else TREE, x, z, base(x, z), if (dead) 2 else 3, seed)
        }
        landmarks = marks
        for (d in all) {
            for (bx in Math.floorDiv(d.x - d.r, BUCKET)..Math.floorDiv(d.x + d.r, BUCKET)) {
                for (bz in Math.floorDiv(d.z - d.r, BUCKET)..Math.floorDiv(d.z + d.r, BUCKET)) {
                    buckets.getOrPut(bkey(bx, bz)) { ArrayList() }.add(d)
                }
            }
        }
    }

    /** Schreibt alle Hochland-Objekte, die die Spalte (x, z) berühren, in [plan]. [top] = Oberkante der Spalte. */
    fun write(x: Int, z: Int, top: Int, plan: ColumnPlan) {
        val list = buckets[bkey(Math.floorDiv(x, BUCKET), Math.floorDiv(z, BUCKET))] ?: return
        for (d in list) {
            if (abs(x - d.x) > d.r || abs(z - d.z) > d.r) continue
            when (d.type) {
                TREE -> tree(d, x, z, top, plan)
                DEAD -> deadTree(d, x, z, top, plan)
                BOULDER -> boulder(d, x, z, plan)
                POND -> pond(d, x, z, top, plan)
                BLADE -> blade(d, x, z, top, plan)
                TOWER -> tower(d, x, z, top, plan)
                CIRCLE -> circle(d, x, z, plan)
                GRAVES -> graves(d, x, z, plan)
            }
        }
    }

    private fun h(d: Deco, a: Int, b: Int = 0) = FarPlan.h32(d.seed, a, b, 1777)

    private fun tree(d: Deco, x: Int, z: Int, top: Int, p: ColumnPlan) {
        val dx = x - d.x
        val dz = z - d.z
        val dist = hypot(dx.toDouble(), dz.toDouble())
        val hgt = 7 + (h(d, 1) * 7).toInt()
        val b = d.base
        val trunk = dx == 0 && dz == 0
        if (trunk) {
            for (y in top + 1 until b) p.set(y, FarPlan.SNOW)
            for (y in b..b + hgt - 2) p.set(y, FarPlan.LOG)
        }
        var topLeaf = Int.MIN_VALUE
        for (hy in 2..hgt) {
            val t = (hy - 2) / (hgt - 2).toDouble()
            var rl = (1.0 - t) * 2.7 + 0.35
            if (hy % 2 == 1) rl *= 0.72
            if (hy == hgt) rl = 0.5
            if (dist > rl) continue
            if (trunk && hy <= hgt - 2) continue
            val y = b + hy
            p.set(y, if (FarPlan.h32(x, y, z, d.seed) < 0.2) FarPlan.SNOW else FarPlan.LEAVES)
            topLeaf = y
        }
        if (topLeaf != Int.MIN_VALUE) p.set(topLeaf + 1, FarPlan.SNOW_LAYER + 1)
    }

    private fun deadTree(d: Deco, x: Int, z: Int, top: Int, p: ColumnPlan) {
        val dx = x - d.x
        val dz = z - d.z
        val hgt = 4 + (h(d, 1) * 4).toInt()
        val b = d.base
        val s = if (h(d, 2) < 0.5) 1 else -1
        if (dx == 0 && dz == 0) {
            for (y in top + 1 until b) p.set(y, FarPlan.SNOW)
            for (y in b until b + hgt) p.set(y, FarPlan.LOG)
            p.set(b + hgt, FarPlan.SNOW_LAYER + 1)
        } else if (dx == s && dz == 0) {
            p.set(b + hgt - 2, FarPlan.LOG_X)
            p.set(b + hgt - 1, FarPlan.SNOW_LAYER)
        } else if (dx == 0 && dz == -s && hgt >= 6) {
            p.set(b + hgt - 4, FarPlan.LOG_Z)
            p.set(b + hgt - 3, FarPlan.SNOW_LAYER)
        }
    }

    private fun boulder(d: Deco, x: Int, z: Int, p: ColumnPlan) {
        val dist = hypot((x - d.x).toDouble(), (z - d.z).toDouble())
        val r = d.r + 0.4 + h(d, 1) * 0.8
        val rr = r * (0.85 + 0.3 * FarPlan.vnoise(x, z, 3, d.seed))
        if (dist > rr) return
        val hb = sqrt(rr * rr - dist * dist) * 0.8
        val b = d.base - 1
        val topY = b + floor(hb).toInt()
        for (y in b..topY) {
            val id = when (Math.floorMod(y + d.seed, 5)) {
                0 -> FarPlan.ANDESITE
                1 -> FarPlan.TUFF
                3 -> FarPlan.DEEP
                else -> FarPlan.STONE
            }
            p.set(y, id)
        }
        if (hb >= 1.2) p.set(topY + 1, FarPlan.SNOW_LAYER + 1 + (h(d, 2) * 3).toInt()) else p.set(topY, FarPlan.SNOW)
    }

    private fun pond(d: Deco, x: Int, z: Int, top: Int, p: ColumnPlan) {
        if (top != -1) return
        val dist = hypot((x - d.x).toDouble(), (z - d.z).toDouble())
        val dn = dist / (d.r * (0.8 + 0.35 * FarPlan.vnoise(x, z, 5, d.seed)))
        if (dn > 1.0) return
        val crack = abs(FarPlan.vnoise(x, z, 4, d.seed + 7) - 0.5) < 0.035
        p.set(-1, when {
            dn > 0.9 -> FarPlan.SNOW
            crack || dn < 0.18 -> FarPlan.BLUE
            else -> FarPlan.PACKED
        })
    }

    /** Eine Riesenklinge des Ordens steckt mit der Spitze schräg im Schnee (Parierstange, Griff und Knauf oben). */
    private fun blade(d: Deco, x: Int, z: Int, top: Int, p: ColumnPlan) {
        val dx = x - d.x
        val dz = z - d.z
        val alongX = h(d, 1) < 0.5
        val s = if (h(d, 2) < 0.5) 1 else -1
        val hgt = 18 + (h(d, 3) * 9).toInt()
        val u = if (alongX) dx else dz
        val w = if (alongX) dz else dx
        val b = d.base
        var any = false
        for (hy in -3..hgt) {
            val shift = floor(maxOf(hy, 0) * 0.3).toInt() * s
            if (u != shift) continue
            val aw = abs(w)
            val y = b + hy
            val id = when {
                hy <= hgt - 7 -> if (aw <= 2) (if (aw == 2) FarPlan.CALCITE else if (w == 0) FarPlan.BLUE else FarPlan.STONE) else -1
                hy == hgt - 6 -> if (aw <= 5) (if (aw == 5) FarPlan.GOLD else FarPlan.DEEP) else -1
                hy in hgt - 5..hgt - 2 -> if (w == 0) FarPlan.DEEPBRICK else -1
                hy == hgt - 1 -> if (w == 0) FarPlan.GOLD else -1
                else -> -1
            }
            if (id >= 0) {
                p.set(y, id)
                any = true
            }
        }
        // Schneewehe um den Fuß
        val dist = hypot(dx.toDouble(), dz.toDouble())
        if (!any && dist <= 4.0 && top == b - 1) p.set(b, FarPlan.SNOW_LAYER + (4.0 - dist).toInt().coerceIn(0, 3))
    }

    /** Eingestürzter Wachturm: Rundmauer mit gebrochener Krone, Tür und Fenster, innen eine Seelenlaterne. */
    private fun tower(d: Deco, x: Int, z: Int, top: Int, p: ColumnPlan) {
        val dx = x - d.x
        val dz = z - d.z
        val dist = hypot(dx.toDouble(), dz.toDouble())
        val b = d.base
        val seg = Math.floorMod(floor((atan2(dz.toDouble(), dx.toDouble()) + PI) / (PI / 4)).toInt(), 8)
        if (dist <= 4.6) for (y in top + 1 until b) p.set(y, FarPlan.STONEBRICK)
        if (dist > 3.3 && dist <= 4.6) {
            val hw = 5 + (FarPlan.h32(seg, 0, d.seed, 1801) * 12).toInt()
            val door = seg == d.seed % 8
            val window = seg == (d.seed + 4) % 8
            for (hy in 0 until hw) {
                if (door && hy <= 2) continue
                if (window && hy in 6..7) continue
                val y = b + hy
                p.set(y, if (hy == 4) FarPlan.CHISELED else if (FarPlan.h32(x, y, z, 1803) < 0.25) FarPlan.CRACKED else FarPlan.STONEBRICK)
            }
            p.set(b + hw, FarPlan.SNOW_LAYER + 1)
        } else if (dist <= 3.3) {
            p.set(b - 1, if (FarPlan.h32(x, 0, z, 1805) < 0.3) FarPlan.CRACKED else FarPlan.STONEBRICK)
            if (dx == 0 && dz == 0) p.set(b, FarPlan.SOUL_LANTERN)
            else if (FarPlan.h32(x, 1, z, 1807) < 0.15) p.set(b, FarPlan.CRACKED)
        } else if (dist <= 7.0 && FarPlan.h32(x, 2, z, 1809) < 0.06) {
            p.set(b, FarPlan.CRACKED)
        }
    }

    /** Schwurkreis: acht Menhire (zwei umgestürzt), Runenring im Boden, in der Mitte ein Schwert im Stein. */
    private fun circle(d: Deco, x: Int, z: Int, p: ColumnPlan) {
        val dx = x - d.x
        val dz = z - d.z
        val dist = hypot(dx.toDouble(), dz.toDouble())
        val b = d.base
        val a0 = h(d, 1) * PI
        for (k in 0 until 8) {
            val a = a0 + k * PI / 4
            val mx = cos(a) * 9.0
            val mz = sin(a) * 9.0
            val fallen = k == d.seed % 8 || k == (d.seed + 3) % 8
            if (fallen) {
                // liegt quer zum Kreis, 4 lang
                for (t in -1..2) {
                    if (Math.round(mx - sin(a) * t).toInt() == dx && Math.round(mz + cos(a) * t).toInt() == dz) p.set(b, FarPlan.STONE)
                }
            } else if (Math.round(mx).toInt() == dx && Math.round(mz).toInt() == dz) {
                val hgt = 3 + (FarPlan.h32(k, 0, d.seed, 1901) * 5).toInt()
                for (hy in 0 until hgt) p.set(b + hy, if (hy == hgt - 1) FarPlan.CALCITE else if (hy % 2 == 0) FarPlan.STONE else FarPlan.POLISHED)
                p.set(b + hgt, FarPlan.SNOW_LAYER + 1)
            }
        }
        val ang = atan2(dz.toDouble(), dx.toDouble())
        when {
            dist <= 1.5 -> p.set(b - 1, FarPlan.CHISELED)
            abs(dist - 6.0) < 0.5 -> p.set(b - 1, FarPlan.LBLUE)
            dist in 2.0..6.0 && abs(sin(ang * 4.0)) < 0.09 -> p.set(b - 1, FarPlan.CALCITE)
        }
        if (dx == 0 && dz == 0) {
            p.set(b, FarPlan.CHISELED)
            p.set(b + 1, FarPlan.END_ROD)
            p.set(b + 2, FarPlan.END_ROD)
            p.set(b + 3, FarPlan.CHAIN)
        }
    }

    /** Klingengräber: ein Feld schimmernder Klingen (Endstäbe mit Kettengriff) auf polierten Sockeln. */
    private fun graves(d: Deco, x: Int, z: Int, p: ColumnPlan) {
        val dx = x - d.x
        val dz = z - d.z
        val gx = Math.floorDiv(dx + 12, 3)
        val gz = Math.floorDiv(dz + 12, 3)
        val mx = gx * 3 - 12 + 1
        val mz = gz * 3 - 12 + 1
        if (dx != mx || dz != mz) return
        if (hypot(mx.toDouble(), mz.toDouble()) > 12.0) return
        if (FarPlan.h32(gx, gz, d.seed, 2001) >= 0.5) return
        val b = d.base
        p.set(b - 1, FarPlan.POLISHED)
        val tall = FarPlan.h32(gx, gz, d.seed, 2003) < 0.6
        p.set(b, FarPlan.END_ROD)
        if (tall) p.set(b + 1, FarPlan.END_ROD)
        p.set(b + if (tall) 2 else 1, FarPlan.CHAIN)
    }
}
