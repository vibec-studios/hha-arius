package dev.henny.hha_arius.arena

import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d as Vec
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * Fernlandschaft um das Heiligtum: die Nadel (Felshorn im Süden) auf ihrem Massiv, die drei Berge der Jagd mit Brücken
 * und Tunneln ([HuntRoute]), der Drachenhorst im Westen, das Feld von Abschnitt III im Osten und das offene Hochland
 * dazwischen ([Highland]: Frostwälder, Ruinen des Ordens der weißen Klinge, Findlinge, gefrorene Seen, Eisspitzen).
 *
 * Gebaut wird nur eine **Haut**: je Säule von der Oberkante bis knapp unter die tiefste Nachbar-Oberkante. Nur
 * Vanilla-Blöcke. Die Fernlandschaft wird nie verändert (Spieler können in der Arena-Dimension nichts abbauen) und je
 * Instanz einmal gebaut ([FarBuild], Version [VERSION] in AriusState); dabei räumt der Bau alles ab, was ältere
 * Fassungen über der neuen Oberkante hinterlassen haben.
 */
internal object FarPlan {

    const val VERSION = 12 // 12: Jagdweg über drei eigene Berge mit echten Brücken und offenen Tunneln, Horst und Feld weit draußen und größer, Gipfelmauer mit Tor, Hochland mit Wäldern und Ruinen, Reste älterer Fassungen werden abgeräumt; 11: Drachenhorst, Gipfelkrone, Nebengipfel mit Tunneln, Schluchtbrücken; 10: Berg massiv/dicke Haut, Eisspitzen-Weite, Phase-III-Arena; 9: Ranken nur als schmaler Streifen an echten Stufen; 8: Massiv größer, Kletterweg mit Ranken; 7: endloses Schneefeld, Riesenmassiv mit Spiralweg (neue Dimension); 6: Feld im Hochland statt Balkon, kein Massiv; 5: Bergmassiv mit Terrasse statt schwebendem Balkon; 4: Balkon als Felsschelf mit Strebe, Schneebänder am Horn; 3: Alpen (Felshorn, Fels/Schnee, offenes Hochland); 2: Himmelsbalkon an der Nadel

    const val X_MIN = ArenaLayout.FAR_X_MIN
    const val X_MAX = ArenaLayout.FAR_X_MAX
    const val Z_MIN = ArenaLayout.FAR_Z_MIN
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

    fun fbm(x: Int, z: Int, cell: Int, s: Int, octaves: Int = 4): Double {
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

    fun ridged(x: Int, z: Int, cell: Int, s: Int, octaves: Int = 4): Double {
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

    fun smooth(e0: Double, e1: Double, v: Double): Double {
        val t = ((v - e0) / (e1 - e0)).coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }

    private fun wrap(a: Double): Double {
        var d = a % (2 * PI)
        if (d > PI) d -= 2 * PI
        if (d < -PI) d += 2 * PI
        return d
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

    // --- Das Bergmassiv der Nadel ----------------------------------------------------------------------------------

    /**
     * Crix (18.11.): „Der Berg soll riesig sein.“ Um das Horn liegt ein breites Massiv (Fuß [MASSIF_R] Blöcke um die
     * Achse, Schulter auf y [SHOULDER_Y] mit [SHOULDER_R] Radius), aus dessen Schulter das Felshorn aufragt. Seit 1.8.1
     * ist es der letzte und größte der Berge der Jagd: Brücke B3 kommt von Osten, eine Windung führt hinauf zum Tor der
     * Klinge in der Gipfelmauer ([HuntRoute]).
     */
    const val MASSIF_R = 170.0
    const val SHOULDER_R = 34.0
    const val SHOULDER_Y = 172.0

    /** Wegbreite (Lauffläche) der Jagd. */
    const val PATH_W = HuntRoute.HALF_W * 2

    /** Höhe des Massivs über dem Kreisabstand d zur Achse (ohne Rauschen). */
    fun massifH(d: Double): Double = when {
        d >= MASSIF_R -> -1.0
        d <= SHOULDER_R -> SHOULDER_Y
        else -> -1.0 + (SHOULDER_Y + 1.0) * Math.pow((MASSIF_R - d) / (MASSIF_R - SHOULDER_R), 1.15)
    }

    /** Kreisabstand zur Achse, auf dem das Massiv die Höhe y hat. */
    fun massifR(y: Double): Double =
        MASSIF_R - (MASSIF_R - SHOULDER_R) * Math.pow(((y + 1.0) / (SHOULDER_Y + 1.0)).coerceIn(0.0, 1.0), 1.0 / 1.15)

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

    /** Oberkante der Berge der Jagd (und des Felssporns am Massiv) über (x, z) oder [Int.MIN_VALUE]. */
    fun peakTop(x: Int, z: Int): Int {
        var best = Int.MIN_VALUE
        for (p in HuntRoute.MOUNTS) {
            if (abs(x + 0.5 - p.x) >= p.r || abs(z + 0.5 - p.z) >= p.r) continue
            best = max(best, p.topAt(x, z))
        }
        val b = HuntRoute.BUTTRESS
        if (abs(x + 0.5 - b.x) < b.r && abs(z + 0.5 - b.z) < b.r) best = max(best, b.topAt(x, z))
        return best
    }

    /**
     * Oberkante des Hochlands ohne Berge: seit 1.4.2 ein endloses Schneefeld auf Plateauhöhe (Crix: „die Arena soll
     * unendlich groß sein“). Weit draußen sanfte Schneewellen, die zum Rand des Baubereichs auf die Ebene des
     * Weltgenerators auslaufen; auf den Kampfflächen bleibt es eben.
     */
    fun ground(x: Int, z: Int): Int {
        val rc = hypot(x.toDouble(), z + 20.0)
        var w = smooth(80.0, 120.0, rc)
        if (w <= 0.0) return -1
        val dv = hypot((x - ArenaLayout.VAULT.x).toDouble(), (z - ArenaLayout.VAULT.z).toDouble())
        w *= smooth(ArenaLayout.VAULT_R + 12.0, ArenaLayout.VAULT_R + 40.0, dv)
        w *= smooth(ArenaLayout.AERIE_RIM + 2.0, ArenaLayout.AERIE_RIM + 30.0, aerieDist(x, z))
        w *= smooth(X_MAX.toDouble(), X_MAX - 24.0, x.toDouble()) * smooth(X_MIN.toDouble(), X_MIN + 24.0, x.toDouble()) *
            smooth(Z_MAX.toDouble(), Z_MAX - 24.0, z.toDouble()) * smooth(Z_MIN.toDouble(), Z_MIN + 24.0, z.toDouble())
        if (w <= 0.0) return -1
        val m = max(0.0, (fbm(x, z, 44, 3) - 0.47) * 16.0)
        return -1 + floor(m * w).toInt()
    }

    /** Natürliche Oberkante (ohne den Weg der Jagd): Hochland, Massiv, Berge, Horstrand, Horn. */
    fun terrain(x: Int, z: Int): Int {
        var t = ground(x, z)
        val m = massifTop(x, z)
        if (m > t) t = m
        val peak = peakTop(x, z)
        if (peak > t) t = peak
        val rim = aerieRim(x, z)
        if (rim > t) t = rim
        val s = spireTop(x, z)
        if (s > t) t = s
        return t.coerceIn(Y_MIN, ArenaLayout.SPIRE_TOP)
    }

    /**
     * Oberkante der Fernlandschaft mit dem Weg der Jagd: Auf dem Weg die Wegfläche (Hangschnitt oder Damm, Brückendeck),
     * im Tunnel der Fels darüber.
     */
    fun top(x: Int, z: Int): Int {
        val t = terrain(x, z)
        val h = HuntRoute.hit(x, z)
        if (h < 0 || HuntRoute.hitLateral(h) > HuntRoute.HALF_W) return t
        val i = HuntRoute.hitIndex(h)
        val f = floor(HuntRoute.y(i)).toInt()
        return if (HuntRoute.kind(i) == HuntRoute.TUNNEL && t > f + 5) t else f
    }

    fun inCore(x: Int, z: Int): Boolean =
        x in ArenaPlan.X_MIN..ArenaPlan.X_MAX && z in ArenaPlan.Z_MIN..ArenaPlan.Z_MAX

    // Block-Ids (für [state])
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
    const val STONEBRICK = 15
    const val GOLD = 16
    const val LBLUE = 17
    const val BONE = 18
    const val PLANKS = 19
    const val FENCE = 20
    const val HANG = 21
    const val WHITE = 22
    const val BRICKWALL = 23
    const val CRACKED = 24
    const val CHISELED = 25
    const val DEEPBRICK = 26
    const val LOG = 27
    const val LOG_X = 28
    const val LOG_Z = 29
    const val LEAVES = 30
    const val END_ROD = 31
    const val CHAIN = 32
    const val SOUL_LANTERN = 33
    const val POLISHED = 34
    const val SOUL_HANG = 35
    const val AIR = 40
    /** Schneeschichten 1..8: [SNOW_LAYER] + n − 1. */
    const val SNOW_LAYER = 41

    /**
     * Seit 1.3.1 alpin (Crix: „ein Berg wie in den Alpen, nicht aus Eis“): Fels in Schichten (Stein, Andesit, Tuff,
     * dunkler Tiefenschiefer), Schnee auf flachen Stellen und oberhalb der Schneegrenze, Gletschereis nur noch in Rinnen.
     */
    fun material(x: Int, y: Int, z: Int, top: Int, slope: Int, spire: Boolean): Int {
        val depth = top - y
        val snowLine = 70 + (vnoise(x, z, 13, 65) * 20).toInt()
        if (depth == 0 && (slope <= 2 || (y > snowLine && slope <= 5 && h32(x, y, z, 67) < 0.75))) return SNOW
        if (depth <= 1 && slope <= 1) return SNOW
        // Seit 1.8.0: über der Gipfelschulter ist das Horn ein Eiskristall aus Facetten (festes und blaues Eis).
        if (spire && y > SHOULDER_Y + 1) {
            if (depth == 0 && y > ArenaLayout.SPIRE_TOP - 10) return SNOW
            return if (vnoise(x + y, z - y, 5, 501) > 0.6) BLUE else PACKED
        }
        if (spire && depth <= 1) {
            if (y > 150 && h32(x, y, z, 75) < 0.7) return SNOW
            if (Math.floorMod(y + (4 * vnoise(x, z, 9, 77)).toInt(), 14) < 2) return SNOW
        }
        if (slope >= 4 && vnoise(x, z, 6, 69) > 0.78) return if (h32(x, y, z, 71) < 0.3) BLUE else PACKED
        val strata = Math.floorMod(y + (6 * vnoise(x, z, 17, 73)).toInt() + (x + z) / 9, 11)
        return when {
            strata <= 1 -> DEEP
            strata <= 3 -> ANDESITE
            strata == 7 -> TUFF
            strata == 9 && spire -> CALCITE
            else -> STONE
        }
    }

    // --- Feld von Abschnitt III (VAULT) ----------------------------------------------------------------------------

    private val VX = ArenaLayout.VAULT.x
    private val VY = ArenaLayout.VAULT.y
    private val VZ = ArenaLayout.VAULT.z
    private val VR = ArenaLayout.VAULT_R.toDouble()

    /**
     * Die Arena von Abschnitt III: ein zugefrorener See mit Ringmuster (hellblauer Beton, Kalzitadern – nicht rutschig),
     * am Rand Schneewehen; acht schräg wachsende Eiskristalle; ein zerbrochener Kreis aus Steinpfeilern mit Bögen; im
     * Norden steckt eine gewaltige Steinklinge im Eis, an der Solvane ruht; der Zuschauerhügel im Süden. Seit 1.8.1 weit
     * im Osten und größer (r 62).
     */
    fun fieldFloor(x: Int, z: Int): Int {
        val r = hypot((x - VX).toDouble(), (z - VZ).toDouble())
        if (r > VR + 3.0) return -1
        val ring = (r / 4.5).toInt()
        val vein = abs(vnoise(x, z, 5, 801) - 0.5) < 0.04
        return when {
            r > VR - 1.5 -> SNOW
            vein -> CALCITE
            ring % 3 == 0 && r % 4.5 < 1.0 -> LBLUE
            r < 5.0 -> LBLUE
            abs(r - 31.0) < 0.6 -> CALCITE
            else -> SNOW
        }
    }

    fun balcony(x: Int, y: Int, z: Int): Int {
        // Zuschauerhügel am Südrand (Lauffläche 6 über dem Feld)
        val wx = floor(ArenaLayout.WATCH_VAULT.x).toInt()
        val wz = floor(ArenaLayout.WATCH_VAULT.z).toInt()
        if (abs(x - wx) <= 2 && abs(z - wz) <= 2 && y in VY until VY + 6) return if (y == VY + 5) SNOW else STONE
        val dx = (x - VX).toDouble()
        val dz = (z - VZ).toDouble()
        val r = hypot(dx, dz)
        if (r > VR + 10.0) return -1
        val hy = y - VY
        // Riesige Eiskristalle: schräg nach außen geneigt, 12–20 hoch, Kern blau (zerbrechlich: ±4, ≤ 14 über dem Boden)
        for ((i, p) in ArenaLayout.VAULT_PILLARS.withIndex()) {
            val hgt = 12 + (h32(p.x, 0, p.z, 707) * 9).toInt()
            if (hy < 0 || hy >= hgt) continue
            val out = Vec(p.x - VX.toDouble(), 0.0, p.z - VZ.toDouble()).normalize()
            val lean = hy * 0.2
            val cx = p.x + out.x * lean
            val cz = p.z + out.z * lean
            val pr = hypot(x + 0.5 - cx - 0.5, z + 0.5 - cz - 0.5)
            val rad = 2.2 * (1.0 - hy / hgt.toDouble()) + 0.4 + (if (i % 2 == 0) 0.4 else 0.0)
            if (pr <= rad) return if (pr < rad * 0.45) BLUE else PACKED
        }
        // Zerbrochener Pfeilerkreis mit Bögen (knapp außerhalb des Feldes); Lücke, wo der Weg der Jagd hinausführt.
        if (r in VR + 3.5..VR + 6.5) {
            if (HuntRoute.hit(x, z) >= 0) return -1
            val ang = Math.toDegrees(atan2(dz, dx)) + 180.0
            val seg = Math.floorMod(Math.floorDiv(ang.toInt(), 10), 36)
            val inSeg = ang - Math.floorDiv(ang.toInt(), 10) * 10.0
            val broken = h32(seg, 0, 0, 811) < 0.3
            val pillarH = if (broken) 3 + (h32(seg, 1, 0, 813) * 4).toInt() else 12
            if (inSeg < 2.6 && hy in 0 until pillarH) return if (hy == pillarH - 1 && !broken) CALCITE else if (h32(x, y, z, 815) < 0.2) CRACKED else STONEBRICK
            if (!broken && h32(seg + 1, 0, 0, 811) >= 0.3 && hy in 10..11 && r in VR + 4.0..VR + 6.0) return if (hy == 11) CALCITE else STONEBRICK
            if (inSeg < 2.6 && hy == -1) return STONEBRICK
        }
        // Die große Steinklinge im Norden (bei VAULT_REST), Schneide in x-Richtung, 30 hoch
        val bx = floor(ArenaLayout.VAULT_REST.x).toInt()
        val bz = floor(ArenaLayout.VAULT_REST.z).toInt() - 8
        val u = x - bx
        val v = z - bz
        if (abs(v) <= 1 && abs(u) <= 12 && hy >= 0) {
            val bladeH = 30
            if (v == 0 && abs(u) <= 4 && hy < bladeH) {
                if (hy < 2 && abs(u) == 4) return -1
                return if (abs(u) == 4) CALCITE else if (u == 0) BLUE else STONE
            }
            if (hy == bladeH && v == 0 && abs(u) <= 11) return if (abs(u) == 11) GOLD else DEEP
            if (hy == bladeH && abs(v) == 1 && abs(u) <= 1) return DEEP
            if (v == 0 && u == 0 && hy in bladeH + 1..bladeH + 5) return DEEPBRICK
            if (v == 0 && u == 0 && hy == bladeH + 6) return GOLD
        }
        return -1
    }

    /**
     * Eisspitzen der endlosen Weite (seit 1.6.5): ein lichter Wald aus Eisnadeln (8–34 hoch, schmal, blauer Kern)
     * außerhalb der Kampfflächen, nicht in den Frostwäldern des Hochlands. Liefert für (x, z) Oberkante und Kern oder null.
     */
    fun iceSpike(x: Int, z: Int): IntArray? {
        if (keepClear(x, z) || massifTop(x, z) != Int.MIN_VALUE || peakTop(x, z) != Int.MIN_VALUE || aerieRim(x, z) != Int.MIN_VALUE) return null
        if (Highland.forest(x, z) > 0.0) return null
        val cell = 11
        val cx = Math.floorDiv(x, cell)
        val cz = Math.floorDiv(z, cell)
        for (ix in cx - 1..cx + 1) for (iz in cz - 1..cz + 1) {
            if (h32(ix, 0, iz, 901) > 0.42) continue
            val sx = ix * cell + (h32(ix, 1, iz, 903) * cell).toInt()
            val sz = iz * cell + (h32(ix, 2, iz, 905) * cell).toInt()
            if (keepClear(sx, sz) || Highland.forest(sx, sz) > 0.0) continue
            val hgt = 8 + (h32(ix, 3, iz, 907) * 26).toInt()
            val r0 = 1.4 + h32(ix, 4, iz, 909) * 2.2
            val d = hypot((x - sx).toDouble(), (z - sz).toDouble())
            if (d > r0) continue
            val h = (hgt * (1.0 - d / r0) * (1.0 - d / r0 * 0.3)).toInt()
            if (h < 1) continue
            return intArrayOf(h - 1, if (d < r0 * 0.4) 1 else 0)
        }
        return null
    }

    /** Freizuhaltende Kampfflächen und der Weg der Jagd (keine Eisspitzen, keine Ruinen). */
    fun keepClear(x: Int, z: Int): Boolean {
        if (hypot(x.toDouble(), z.toDouble()) <= 112.0) return true
        if (hypot((x - VX).toDouble(), (z - VZ).toDouble()) <= VR + 26.0) return true
        if (aerieDist(x, z) <= ArenaLayout.AERIE_RIM + 6.0) return true
        return HuntRoute.hit(x, z) >= 0
    }

    // --- Gipfel: Krone, Runenboden, Mauer mit dem Tor der Klinge --------------------------------------------------

    /**
     * Krone auf der Gipfelschulter: acht schräg nach außen wachsende Eisspitzen (16–34 hoch) am Rand. Liefert die
     * Block-Id für (x, y, z) über der Schulter oder −1.
     */
    fun crown(x: Int, y: Int, z: Int): Int {
        val dx = x + 0.5 - (ArenaLayout.SPIRE.x + 0.5)
        val dz = z + 0.5 - (ArenaLayout.SPIRE.z + 0.5)
        val d = hypot(dx, dz)
        if (d > SHOULDER_R + 14.0) return -1
        val hy = y - SHOULDER_Y.toInt()
        if (hy < 1) return -1
        for (k in 0 until 8) {
            val a = PI + (k + 0.5) * PI / 4
            // Keine Spitze vor dem Tor
            if (abs(wrap(a - HuntRoute.GATE_THETA)) < 0.3) continue
            val hgt = 16 + (h32(k, 0, 0, 701) * 18).toInt()
            if (hy >= hgt) continue
            val lean = hy * 0.3
            val cx = sin(a) * (SHOULDER_R - 3.0 + lean)
            val cz = cos(a) * (SHOULDER_R - 3.0 + lean)
            val pr = hypot(dx - cx, dz - cz)
            val rad = 3.2 * (1.0 - hy / hgt.toDouble()) + 0.5
            if (pr <= rad) return if (pr < rad * 0.45) BLUE else if (hy > hgt - 3) SNOW else PACKED
        }
        return -1
    }

    /**
     * Seit 1.8.1 (Crix: „am Ende steht man schon oben auf der Arena, bevor man sie richtig betreten hat“): Die
     * Gipfelschulter ist von einer 4 Blöcke hohen Mauer mit Zinnen umschlossen. Hinein kommt man nur durch das
     * **Tor der Klinge** im Osten ([SUMMIT_GATE]), wo der Weg der Jagd endet: zwei Tortürme mit Seelenlaternen, ein Bogen
     * und darüber eine steinerne Klinge mit der Spitze zum Himmel. Liefert die Block-Id für (x, y, z) oder −1.
     */
    fun summitWall(x: Int, y: Int, z: Int): Int {
        val dx = x + 0.5 - (ArenaLayout.SPIRE.x + 0.5)
        val dz = z + 0.5 - (ArenaLayout.SPIRE.z + 0.5)
        val d = hypot(dx, dz)
        if (d < SHOULDER_R - 3.5 || d > SHOULDER_R + 3.5) return -1
        val hy = y - SHOULDER_Y.toInt()
        if (hy < 1 || hy > 24) return -1
        val dp = wrap(atan2(dx, dz) - HuntRoute.GATE_THETA)
        val lat = abs(d * sin(dp))
        val rad = d * cos(dp)
        val wallR = SHOULDER_R - 0.5
        if (abs(dp) < 0.5 && lat <= 5.6) {
            // Tortürme
            if (lat > 2.6 && rad in wallR - 2.5..wallR + 2.5) {
                if (hy <= 9) return when {
                    hy == 9 -> CALCITE
                    hy == 1 -> DEEPBRICK
                    h32(x, y, z, 931) < 0.15 -> CRACKED
                    else -> STONEBRICK
                }
                if (hy == 10 && lat in 3.6..4.6 && abs(rad - wallR) < 0.8) return SOUL_LANTERN
                return -1
            }
            if (lat <= 2.6 && rad in wallR - 1.5..wallR + 1.5) {
                if (hy <= 5) return -1 // Durchgang: 5 breit, 5 hoch
                if (hy == 6) return if (lat > 1.6) STONEBRICK else -1
                if (hy == 7) return if (lat < 0.6) CHISELED else STONEBRICK
                if (hy == 8) return if (lat < 0.6) GOLD else STONEBRICK
                // Die Klinge über dem Tor (Spitze nach oben)
                if (abs(rad - wallR) > 0.6) return -1
                if (hy == 9) return if (lat <= 2.6) DEEP else -1
                if (hy in 10..21 && lat <= 1.1) return if (lat > 0.6) CALCITE else BLUE
                if (hy in 22..23 && lat <= 0.6) return CALCITE
                return -1
            }
            return -1
        }
        // Ringmauer mit Zinnen
        if (abs(d - wallR) <= 0.75) {
            val arcPos = floor((atan2(dx, dz) + PI) * wallR).toInt()
            return when {
                hy <= 3 -> if (h32(x, y, z, 933) < 0.12) CRACKED else STONEBRICK
                hy == 4 && Math.floorMod(arcPos, 4) < 2 -> STONEBRICK
                hy == 5 && Math.floorMod(arcPos, 24) == 0 -> SOUL_LANTERN
                else -> -1
            }
        }
        return -1
    }

    /** Boden der Gipfelschulter: Runenkreise und Strahlen aus Kalzit und hellblauem Beton; −1 = Schnee lassen. */
    fun summitFloor(x: Int, z: Int): Int {
        val dx = x + 0.5 - (ArenaLayout.SPIRE.x + 0.5)
        val dz = z + 0.5 - (ArenaLayout.SPIRE.z + 0.5)
        val d = hypot(dx, dz)
        if (d > SHOULDER_R - 1.5 || d < 9.0) return -1
        val ang = atan2(dx, dz)
        // Torweg: ein Kalzitband vom Tor bis zum innersten Ring
        val dp = wrap(ang - HuntRoute.GATE_THETA)
        if (abs(dp) < 0.5 && abs(d * sin(dp)) <= 1.0) return CALCITE
        return when {
            abs(d - 14.0) < 0.55 || abs(d - 26.0) < 0.55 -> CALCITE
            abs(d - 20.0) < 0.7 -> LBLUE
            d in 14.0..26.0 && abs(sin(ang * 6.0)) < 0.05 -> LBLUE
            else -> -1
        }
    }

    /**
     * Eingang der Gipfelarena (relativ, Lauffläche): Mitte des Tors der Klinge in der Gipfelmauer, im Osten der
     * Schulter. Der Weg der Jagd endet zwei Blöcke dahinter auf der Schulter.
     */
    val SUMMIT_GATE: BlockPos by lazy {
        BlockPos(
            floor(ArenaLayout.SPIRE.x + 0.5 + sin(HuntRoute.GATE_THETA) * (SHOULDER_R - 0.5)).toInt(),
            SHOULDER_Y.toInt() + 1,
            floor(ArenaLayout.SPIRE.z + 0.5 + cos(HuntRoute.GATE_THETA) * (SHOULDER_R - 0.5)).toInt(),
        )
    }

    /** Mitte der Gipfelarena (relativ, Lauffläche). */
    val SUMMIT_CENTER: Vec = Vec(ArenaLayout.SPIRE.x + 0.5, SHOULDER_Y + 1.0, ArenaLayout.SPIRE.z + 0.5)

    /**
     * Steht ein Punkt (relativ) wirklich in der Gipfelarena, also hinter der Mauer? Erst wer das Tor durchschritten hat
     * (oder anders über die Mauer kommt), ist drin; auf dem Weg unter der Mauer gilt man noch als draußen.
     */
    fun insideSummit(x: Double, y: Double, z: Double): Boolean =
        y >= SHOULDER_Y + 0.5 && y <= SHOULDER_Y + 40.0 &&
            hypot(x - (ArenaLayout.SPIRE.x + 0.5), z - (ArenaLayout.SPIRE.z + 0.5)) <= SHOULDER_R - 1.0

    // --- Weg der Jagd (API für Kampf und Szenen) -------------------------------------------------------------------

    /** Anzahl der Wegpunkte (Abstand 0,5 Blöcke). Index 0 = Start am Feld, letzter Index = hinter dem Tor der Klinge. */
    val pathSize: Int get() = HuntRoute.size

    /** Wegpunkt [i] (relativ, y = Lauffläche). */
    fun pathPoint(i: Int): Vec = Vec(HuntRoute.x(i), HuntRoute.standY(i), HuntRoute.z(i))

    /** Art des Wegabschnitts bei [i]: [HuntRoute.TRAIL], [HuntRoute.BRIDGE], [HuntRoute.TUNNEL] oder [HuntRoute.GATE]. */
    fun pathKind(i: Int): Int = HuntRoute.kind(i)

    /** Wegindex unter (x, z) (relativ) mit [slack] Blöcken Toleranz am Rand (höchstens 3,5) oder −1. */
    fun pathIndexNear(x: Double, z: Double, slack: Double): Int =
        HuntRoute.indexAt(floor(x).toInt(), floor(z).toInt(), slack.coerceIn(0.0, HuntRoute.ENVELOPE - HuntRoute.HALF_W))

    /** Nächster Wegindex für einen Punkt abseits des Wegs (lieber eine Stelle knapp unter dem Punkt). */
    fun nearestPathIndex(x: Double, y: Double, z: Double): Int = HuntRoute.nearest(x, y, z)

    /** Höhe des obersten Wegblocks über (x, z) (mit Bruchteil) oder NaN. */
    fun pathAt(x: Int, z: Int): Double {
        val i = HuntRoute.indexAt(x, z)
        return if (i < 0) Double.NaN else HuntRoute.y(i)
    }

    /** Liegt der Weg hier auf einer Brücke? */
    fun bridgeAt(x: Int, z: Int): Boolean {
        val i = HuntRoute.indexAt(x, z)
        return i >= 0 && HuntRoute.kind(i) == HuntRoute.BRIDGE
    }

    /** Startpunkt der Jagd (relativ, Lauffläche): am Südrand des Feldes von Abschnitt III. */
    val HUNT_START: Vec get() = pathPoint(0)

    /**
     * Die Route der Jagd als geordnete Wegpunkte (relativ, y = Lauffläche): vom Start am Feld über die Erste Wacht,
     * Brücke B1, die Zweite Wacht, Brücke B2, den Tunnel durch die Dritte Wacht, Brücke B3 und die Windung am Massiv bis
     * zum Eingang der Gipfelarena ([SUMMIT_GATE], letzter Punkt). Höchstens 8 Blöcke zwischen zwei Punkten; jeder Wechsel
     * zwischen Pfad, Brücke und Tunnel ist ein eigener Punkt.
     */
    fun huntWaypoints(): List<BlockPos> = waypoints

    private val waypoints: List<BlockPos> by lazy {
        val out = ArrayList<BlockPos>()
        fun add(i: Int) {
            val p = BlockPos(floor(HuntRoute.x(i)).toInt(), floor(HuntRoute.standY(i)).toInt(), floor(HuntRoute.z(i)).toInt())
            if (out.isEmpty() || out.last() != p) out.add(p)
        }
        var lastArc = -100.0
        val gateArc = run {
            var g = HuntRoute.size - 1
            for (i in 0 until HuntRoute.size) if (HuntRoute.kind(i) == HuntRoute.GATE) { g = i; break }
            g
        }
        for (i in 0 until HuntRoute.size) {
            val a = HuntRoute.arc(i)
            val change = i > 0 && HuntRoute.kind(i) != HuntRoute.kind(i - 1)
            if (a - lastArc >= 6.0 || change) {
                add(i)
                lastArc = a
            }
            if (i >= gateArc) break
        }
        // Vom Ende der Windung auf das Tor zu
        val gate = SUMMIT_GATE
        var i = gateArc
        while (i < HuntRoute.size) {
            val p = pathPoint(i)
            if (hypot(p.x - (ArenaLayout.SPIRE.x + 0.5), p.z - (ArenaLayout.SPIRE.z + 0.5)) <= SHOULDER_R + 0.5) break
            if (HuntRoute.arc(i) - lastArc >= 4.0) {
                add(i)
                lastArc = HuntRoute.arc(i)
            }
            i++
        }
        if (out.last() != gate) out.add(gate)
        out
    }

    // --- Drachenhorst ------------------------------------------------------------------------------------------------

    private val AX = ArenaLayout.AERIE.x
    private val AZ = ArenaLayout.AERIE.z
    private val AR = ArenaLayout.AERIE_R.toDouble()

    fun aerieDist(x: Int, z: Int): Double = hypot(x + 0.5 - (AX + 0.5), z + 0.5 - (AZ + 0.5))

    /** Felsrand des Horsts über (x, z) oder [Int.MIN_VALUE]: steil innen, flacher außen, Öffnung nach Osten. */
    fun aerieRim(x: Int, z: Int): Int {
        val d = aerieDist(x, z)
        val r0 = AR
        val r1 = ArenaLayout.AERIE_RIM.toDouble()
        if (d <= r0 + 0.5 || d >= r1) return Int.MIN_VALUE
        val ang = atan2(z + 0.5 - (AZ + 0.5), x + 0.5 - (AX + 0.5))
        var rimH = 26.0 + 34.0 * ridged(x, z, 14, 721) + 8.0 * (vnoise(x, z, 5, 723) - 0.5)
        // Öffnung nach Osten (Einflug) und eine schmale Scharte im Süden
        if (abs(ang) < 0.24) rimH *= 0.06 + 0.94 * smooth(0.13, 0.24, abs(ang))
        if (abs(ang - PI / 2) < 0.09) rimH *= 0.3 + 0.7 * smooth(0.04, 0.09, abs(ang - PI / 2))
        val h = rimH * smooth(r0 + 0.5, r0 + 8.0, d) * (1.0 - smooth(r0 + 14.0, r1, d))
        return if (h < 1.0) Int.MIN_VALUE else floor(h).toInt()
    }

    /** Boden im Horst (y −1): Runenkreise, Strahlen und Adern; −1 = außerhalb. */
    fun aerieFloor(x: Int, z: Int): Int {
        val d = aerieDist(x, z)
        if (d > AR + 0.5) return -1
        val ang = atan2(z + 0.5 - (AZ + 0.5), x + 0.5 - (AX + 0.5))
        val vein = abs(vnoise(x, z, 6, 731) - 0.5) < 0.035
        return when {
            d > AR - 1.2 -> LBLUE
            d < 5.0 -> LBLUE
            abs(d - 20.0) < 0.6 || abs(d - 36.0) < 0.6 || abs(d - 70.0) < 0.55 -> CALCITE
            d in 20.0..36.0 && abs(sin(ang * 4.0)) < 0.045 -> LBLUE
            d in 36.0..70.0 && abs(sin(ang * 8.0)) < 0.02 -> CALCITE
            vein -> WHITE
            else -> SNOW
        }
    }

    /** Gebeine eines alten Drachen im Norden des Horsts (Rippen über dem Boden, Wirbel im Rand). */
    private val bones: HashMap<Long, Int> by lazy { HashMap<Long, Int>().also { buildBones(it) } }

    private fun bkey(x: Int, y: Int, z: Int): Long =
        ((x.toLong() and 0x1FFFFFL) shl 42) or ((y.toLong() and 0x1FFFFFL) shl 21) or (z.toLong() and 0x1FFFFFL)

    private fun buildBones(bones: HashMap<Long, Int>) {
        val cx = AX + 0.5
        val cz = AZ + 0.5
        fun blob(px: Double, py: Double, pz: Double, r: Double) {
            val ri = kotlin.math.ceil(r).toInt()
            for (ox in -ri..ri) for (oy in -ri..ri) for (oz in -ri..ri) {
                val bx = floor(px).toInt() + ox
                val by = floor(py).toInt() + oy
                val bz = floor(pz).toInt() + oz
                if (by < 0) continue
                if (hypot(hypot(bx + 0.5 - px, by + 0.5 - py), bz + 0.5 - pz) <= r) bones[bkey(bx, by, bz)] = BONE
            }
        }
        // Wirbelsäule im Nordrand (Bogen r AR + 10), dazu sieben Rippenbögen, die sich über den Boden neigen
        val north = -PI / 2 // Winkel atan2(dz, dx) nach Norden
        val spine = AR + 10.0
        var a = north - 0.8
        while (a <= north + 0.8) {
            blob(cx + cos(a) * spine, 1.5, cz + sin(a) * spine, 2.6)
            blob(cx + cos(a) * spine, 4.6, cz + sin(a) * spine, 1.2)
            a += 0.03
        }
        for (k in -3..3) {
            val ra = north + k * 0.2
            var t = 0.0
            while (t <= 1.0) {
                val radial = spine - 44.0 * Math.pow(t, 1.6)
                val hgt = 2.0 + 52.0 * sin(t * PI * 0.55)
                val th = 2.4 - 1.2 * t
                blob(cx + cos(ra) * radial, hgt, cz + sin(ra) * radial, th)
                t += 0.01
            }
        }
    }

    /** Aufbauten im Horst für (x, y, z), y ≥ 0: Gebeine, Kristallsäulen, Felsvorsprünge; −1 = nichts. */
    fun aerie(x: Int, y: Int, z: Int): Int {
        bones[bkey(x, y, z)]?.let { return it }
        // Kristallsäulen (≤ 13 hoch, damit sie als Säulen zerbrechen können)
        for ((i, p) in ArenaLayout.AERIE_PILLARS.withIndex()) {
            val hgt = 10 + (h32(p.x, 0, p.z, 741) * 4).toInt()
            if (y >= hgt) continue
            val out = Vec(p.x - AX.toDouble(), 0.0, p.z - AZ.toDouble()).normalize()
            val lean = y * 0.12
            val pr = hypot(x + 0.5 - (p.x + 0.5 + out.x * lean), z + 0.5 - (p.z + 0.5 + out.z * lean))
            val rad = 2.0 * (1.0 - y / hgt.toDouble()) + 0.5 + (if (i % 2 == 0) 0.3 else 0.0)
            if (pr <= rad) return if (pr < rad * 0.45) BLUE else PACKED
        }
        // Felsvorsprung für Arius (Nordrand) und den Zuschauerpunkt: Steinziegel bis zur Lauffläche, Schnee oben
        for (ledge in LEDGES) {
            val top = ledge.y.toInt() - 1
            if (abs(x + 0.5 - ledge.x) <= 2.6 && abs(z + 0.5 - ledge.z) <= 2.6 && y in 0..top) return if (y == top) SNOW else STONEBRICK
        }
        return -1
    }

    private val LEDGES = listOf(ArenaLayout.AERIE_SEAT, ArenaLayout.AERIE_WATCH)

    private val LEAVES_STATE: BlockState by lazy {
        Blocks.SPRUCE_LEAVES.defaultState.with(net.minecraft.block.LeavesBlock.PERSISTENT, true)
    }

    fun state(id: Int): BlockState = when (id) {
        WALL -> Blocks.STONE_BRICK_WALL.defaultState
        STONEBRICK -> Blocks.STONE_BRICKS.defaultState
        LBLUE -> Blocks.LIGHT_BLUE_CONCRETE.defaultState
        GOLD -> Blocks.GOLD_BLOCK.defaultState
        LANTERN -> Blocks.LANTERN.defaultState
        BLUE -> Blocks.BLUE_ICE.defaultState
        SNOW -> Blocks.SNOW_BLOCK.defaultState
        DEEP -> Blocks.DEEPSLATE.defaultState
        TUFF -> Blocks.TUFF.defaultState
        STONE -> Blocks.STONE.defaultState
        ANDESITE -> Blocks.ANDESITE.defaultState
        CALCITE -> Blocks.CALCITE.defaultState
        BONE -> Blocks.BONE_BLOCK.defaultState
        PLANKS -> Blocks.SPRUCE_PLANKS.defaultState
        FENCE -> Blocks.SPRUCE_FENCE.defaultState
        HANG -> Blocks.LANTERN.defaultState.with(net.minecraft.block.LanternBlock.HANGING, true)
        WHITE -> Blocks.WHITE_CONCRETE.defaultState
        BRICKWALL -> Blocks.STONE_BRICK_WALL.defaultState
        CRACKED -> Blocks.CRACKED_STONE_BRICKS.defaultState
        CHISELED -> Blocks.CHISELED_STONE_BRICKS.defaultState
        DEEPBRICK -> Blocks.DEEPSLATE_BRICKS.defaultState
        LOG -> Blocks.SPRUCE_LOG.defaultState
        LOG_X -> Blocks.SPRUCE_LOG.defaultState.with(net.minecraft.block.PillarBlock.AXIS, net.minecraft.util.math.Direction.Axis.X)
        LOG_Z -> Blocks.SPRUCE_LOG.defaultState.with(net.minecraft.block.PillarBlock.AXIS, net.minecraft.util.math.Direction.Axis.Z)
        LEAVES -> LEAVES_STATE
        END_ROD -> Blocks.END_ROD.defaultState
        CHAIN -> Blocks.IRON_CHAIN.defaultState
        SOUL_LANTERN -> Blocks.SOUL_LANTERN.defaultState
        SOUL_HANG -> Blocks.SOUL_LANTERN.defaultState.with(net.minecraft.block.LanternBlock.HANGING, true)
        POLISHED -> Blocks.POLISHED_ANDESITE.defaultState
        AIR -> Blocks.AIR.defaultState
        in SNOW_LAYER..SNOW_LAYER + 7 -> Blocks.SNOW.defaultState.with(net.minecraft.block.SnowBlock.LAYERS, id - SNOW_LAYER + 1)
        else -> Blocks.PACKED_ICE.defaultState
    }
}
