package dev.henny.hha_arius.arena

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * Der Weg der Jagd (seit 1.8.1, Crix: „statt Nebengipfeln eigene Berge, die immer größer werden, mit Brücken, die es
 * wirklich gibt, und Höhlen, durch die man auch durchkommt“). Relativ zum Instanz-Ursprung, y = Oberkante des
 * Wegblocks (Lauffläche = y + 1).
 *
 * ```
 *   Feld (VAULT, Start) ──Pfad──▶ 1. Berg (Erste Wacht, 48 hoch, Spirale bis zum Gipfel)
 *     ──Brücke B1 (y 48)──▶ 2. Berg (Zweite Wacht, 88 hoch, Spirale bis zum Gipfel)
 *     ──Brücke B2 (y 88)──▶ 3. Berg (Dritte Wacht, 132 hoch): Tunnel quer durch den Berg, y 88 → 100
 *     ──Brücke B3 (y 100)──▶ Massif der Nadel: eine Windung bergauf (Tunnel durch einen Felssporn im Nordwesten)
 *     ──▶ Tor der Klinge in der Gipfelmauer (Osten, y 172) ──▶ Gipfelarena
 * ```
 * Der Weg ist eine dichte Punktfolge (alle 0,5 Blöcke). Jede Spalte bis [ENVELOPE] neben der Mittellinie kennt ihren
 * nächsten Punkt (Index, Seitenabstand), daraus baut FarBuild Pfad, Brücken, Tunnel und Wände.
 */
internal object HuntRoute {

    const val TRAIL = 0
    const val BRIDGE = 1
    const val TUNNEL = 2
    const val GATE = 3

    /** Halbe Wegbreite: Lauffläche 7 Blöcke, auf Brücken 5 zwischen den Brüstungen. */
    const val HALF_W = 3.5

    /** Bis hierhin (Seitenabstand) gehört eine Spalte zur Hülle des Wegs (Tunnelwände, Hangschnitt). */
    const val ENVELOPE = 7.0

    private const val STEP = 0.5
    private const val INSET = 2.5

    /** Abstand der Brückenpfeiler (entlang des Wegs). */
    const val SPAN = 18.0

    /**
     * Ein einzelner Berg: Kegel mit Gipfelplateau, Flanken mit Gratrauschen (ohne Rauschen am Fuß und auf dem Plateau,
     * damit Weg und Brückenköpfe passen).
     */
    class Peak(val x: Double, val z: Double, val r: Double, val top: Double, val plateau: Double, val seed: Int) {
        fun base(d: Double): Double {
            if (d >= r) return -1.0
            val u = ((r - d) / (r - plateau)).coerceAtMost(1.0)
            return -1.0 + (top + 1.0) * u.pow(1.3)
        }

        /** Abstand zur Achse, auf dem die (glatte) Flanke die Höhe y hat. */
        fun flankR(y: Double): Double = r - (r - plateau) * ((y + 1.0) / (top + 1.0)).coerceIn(0.0, 1.0).pow(1.0 / 1.3)

        /** Oberkante über der Spalte (x, z) oder [Int.MIN_VALUE]. */
        fun topAt(bx: Int, bz: Int): Int {
            val d = hypot(bx + 0.5 - x, bz + 0.5 - z)
            if (d >= r) return Int.MIN_VALUE
            val u = ((r - d) / (r - plateau)).coerceIn(0.0, 1.0)
            val rough = (FarPlan.ridged(bx, bz, 9, 611 + seed * 13) - 0.5) * 9.0 * 4.0 * u * (1.0 - u)
            val h = base(d) + rough
            return if (h < -1.0) Int.MIN_VALUE else floor(h).toInt()
        }
    }

    private val SX = ArenaLayout.SPIRE.x + 0.5
    private val SZ = ArenaLayout.SPIRE.z + 0.5

    /** Die drei Berge der Jagd, jeder größer als der vorige (danach das Massiv der Nadel). */
    val MOUNTS: Array<Peak> = arrayOf(
        Peak(352.0, 150.0, 46.0, 48.0, 11.0, 0), // Erste Wacht
        Peak(290.0, 236.0, 68.0, 88.0, 12.0, 1), // Zweite Wacht
        Peak(180.0, 250.0, 84.0, 132.0, 13.0, 2), // Dritte Wacht (mit Tunnel)
    )

    /** Felssporn am Massiv, durch den der letzte Aufstieg als Tunnel führt (wird beim Aufbau des Wegs gesetzt). */
    val BUTTRESS: Peak

    /** Winkel (x = sin, z = cos um die Achse der Nadel), unter dem das Tor der Klinge in der Gipfelmauer steht. */
    val GATE_THETA: Double

    // --- Punktfolge -------------------------------------------------------------------------------------------------

    private class Buf {
        var n = 0
        var x = DoubleArray(4096); var z = DoubleArray(4096); var y = DoubleArray(4096)
        var arc = DoubleArray(4096); var nx = DoubleArray(4096); var nz = DoubleArray(4096)
        var kind = IntArray(4096); var span0 = DoubleArray(4096); var span1 = DoubleArray(4096)

        fun add(px: Double, pz: Double, py: Double, k: Int, ox: Double, oz: Double) {
            if (n == x.size) {
                val m = n * 2
                x = x.copyOf(m); z = z.copyOf(m); y = y.copyOf(m); arc = arc.copyOf(m); nx = nx.copyOf(m); nz = nz.copyOf(m)
                kind = kind.copyOf(m); span0 = span0.copyOf(m); span1 = span1.copyOf(m)
            }
            arc[n] = if (n == 0) 0.0 else arc[n - 1] + hypot(px - x[n - 1], pz - z[n - 1])
            x[n] = px; z[n] = pz; y[n] = py; kind[n] = k; nx[n] = ox; nz[n] = oz
            n++
        }

        /** Gerade von (x0, z0, y0) nach (x1, z1, y1); der Startpunkt ist schon der letzte Punkt der Folge (außer am Anfang). */
        fun line(x0: Double, z0: Double, y0: Double, x1: Double, z1: Double, y1: Double, k: Int) {
            val len = hypot(x1 - x0, z1 - z0)
            val steps = max(1, (len / STEP).toInt())
            val ox = -(z1 - z0) / len
            val oz = (x1 - x0) / len
            val first = if (n == 0) 0 else 1
            val start = n
            for (i in first..steps) {
                val t = i / steps.toDouble()
                add(x0 + (x1 - x0) * t, z0 + (z1 - z0) * t, y0 + (y1 - y0) * t, k, ox, oz)
            }
            if (k == BRIDGE) for (i in start until n) {
                span0[i] = arc[start]
                span1[i] = arc[n - 1]
            }
        }

        /**
         * Spirale um (cx, cz) an der Flanke: Radius aus [flank] (Höhe → Abstand) minus [INSET], nie kleiner als [rMin];
         * gleichmäßige Steigung so gewählt (Bisektion), dass nach [sweep] (Bogenmaß, > 0 = Winkel wächst) die Höhe y1
         * erreicht ist.
         */
        fun spiral(cx: Double, cz: Double, flank: (Double) -> Double, th0: Double, sweep: Double, y0: Double, y1: Double, rMin: Double, k: Int) {
            val steps = 6000
            fun run(g: Double, emit: Boolean): Double {
                var th = th0
                var y = y0
                var arcAcc = 0.0
                var next = STEP
                val dth = sweep / steps
                for (i in 0..steps) {
                    val r = max(flank(y) - INSET, rMin)
                    if (emit && i > 0 && arcAcc >= next) {
                        next += STEP
                        add(cx + sin(th) * r, cz + cos(th) * r, y, k, sin(th), cos(th))
                    }
                    val ds = abs(dth) * r
                    arcAcc += ds
                    y += g * ds
                    th += dth
                }
                return y
            }
            var lo = 0.0
            var hi = 3.0
            repeat(60) {
                val m = (lo + hi) / 2
                if (run(m, false) < y1) lo = m else hi = m
            }
            run(lo, true)
            // exakt auf dem Endpunkt schließen
            val th1 = th0 + sweep
            val r1 = max(flank(y1) - INSET, rMin)
            add(cx + sin(th1) * r1, cz + cos(th1) * r1, y1, k, sin(th1), cos(th1))
        }
    }

    private val b = Buf()

    init {
        val m1 = MOUNTS[0]
        val m2 = MOUNTS[1]
        val m3 = MOUNTS[2]
        fun ang(dx: Double, dz: Double) = atan2(dx, dz)
        fun ccw(from: Double, to: Double): Double {
            var d = (to - from) % (2 * PI)
            if (d < 0) d += 2 * PI
            return d + 2 * PI
        }
        // 0) Vom Feld (Südrand) zum Fuß der Ersten Wacht
        val sx0 = ArenaLayout.VAULT.x + 4.5
        val sz0 = ArenaLayout.VAULT.z + 44.5
        val th1in = ang(sx0 - m1.x, sz0 - m1.z)
        val r1in = m1.flankR(-1.0) - INSET
        b.line(sx0, sz0, -1.0, m1.x + sin(th1in) * r1in, m1.z + cos(th1in) * r1in, -1.0, TRAIL)
        // 1) Spirale auf die Erste Wacht
        val th1out = ang(m2.x - m1.x, m2.z - m1.z)
        b.spiral(m1.x, m1.z, m1::flankR, th1in, ccw(th1in, th1out), -1.0, m1.top, m1.plateau - 1.0, TRAIL)
        // 2) Brücke B1 zur Zweiten Wacht
        val th2in = ang(m1.x - m2.x, m1.z - m2.z)
        val r2in = m2.flankR(m1.top) - INSET
        b.line(b.x[b.n - 1], b.z[b.n - 1], m1.top, m2.x + sin(th2in) * r2in, m2.z + cos(th2in) * r2in, m1.top, BRIDGE)
        // 3) Spirale auf die Zweite Wacht
        val th2out = ang(m3.x - m2.x, m3.z - m2.z)
        b.spiral(m2.x, m2.z, m2::flankR, th2in, ccw(th2in, th2out), m1.top, m2.top, m2.plateau - 1.0, TRAIL)
        // 4) Brücke B2 zur Dritten Wacht
        val th3in = ang(m2.x - m3.x, m2.z - m3.z)
        val r3in = m3.flankR(m2.top) - INSET
        b.line(b.x[b.n - 1], b.z[b.n - 1], m2.top, m3.x + sin(th3in) * r3in, m3.z + cos(th3in) * r3in, m2.top, BRIDGE)
        // 5) Tunnel quer durch die Dritte Wacht, leicht steigend
        val yExit = m2.top + 12.0
        val th3out = ang(SX - m3.x, SZ - m3.z)
        val r3out = m3.flankR(yExit) - INSET
        b.line(b.x[b.n - 1], b.z[b.n - 1], m2.top, m3.x + sin(th3out) * r3out, m3.z + cos(th3out) * r3out, yExit, TUNNEL)
        // 6) Brücke B3 zum Massiv der Nadel
        val thm = ang(b.x[b.n - 1] - SX, b.z[b.n - 1] - SZ)
        val rm = FarPlan.massifR(yExit) - INSET
        b.line(b.x[b.n - 1], b.z[b.n - 1], yExit, SX + sin(thm) * rm, SZ + cos(thm) * rm, yExit, BRIDGE)
        // 7) Eine Windung am Massiv bis unter die Gipfelmauer
        val spiralStart = b.n
        val rTop = FarPlan.SHOULDER_R + 6.0
        b.spiral(SX, SZ, FarPlan::massifR, thm, 2 * PI, yExit, FarPlan.SHOULDER_Y, rTop, TRAIL)
        // Felssporn im Nordwesten (ein gutes Drittel der Windung): der Weg führt als Tunnel hindurch.
        var bi = spiralStart
        while (bi < b.n - 1 && (atan2(b.x[bi] - SX, b.z[bi] - SZ) - thm).let { var d = it % (2 * PI); if (d < 0) d += 2 * PI; d } < 2.17) bi++
        BUTTRESS = Peak(b.x[bi], b.z[bi], 22.0, b.y[bi] + 26.0, 3.0, 3)
        for (i in spiralStart until b.n) if (hypot(b.x[i] - BUTTRESS.x, b.z[i] - BUTTRESS.z) < 15.0) b.kind[i] = TUNNEL
        // 8) Gerade durchs Tor auf die Gipfelschulter
        GATE_THETA = thm
        b.line(b.x[b.n - 1], b.z[b.n - 1], FarPlan.SHOULDER_Y, SX + sin(thm) * (FarPlan.SHOULDER_R - 2.0), SZ + cos(thm) * (FarPlan.SHOULDER_R - 2.0), FarPlan.SHOULDER_Y, GATE)
    }

    val size: Int get() = b.n
    fun x(i: Int) = b.x[i.coerceIn(0, b.n - 1)]
    fun z(i: Int) = b.z[i.coerceIn(0, b.n - 1)]
    fun y(i: Int) = b.y[i.coerceIn(0, b.n - 1)]
    fun arc(i: Int) = b.arc[i.coerceIn(0, b.n - 1)]
    fun kind(i: Int) = b.kind[i.coerceIn(0, b.n - 1)]
    fun normalX(i: Int) = b.nx[i.coerceIn(0, b.n - 1)]
    fun normalZ(i: Int) = b.nz[i.coerceIn(0, b.n - 1)]

    /** Bogenlänge der laufenden Brücke: Anfang/Ende (nur für Brückenpunkte). */
    fun spanStart(i: Int) = b.span0[i.coerceIn(0, b.n - 1)]
    fun spanEnd(i: Int) = b.span1[i.coerceIn(0, b.n - 1)]

    /** Schneeschichten (1–8) auf dem Wegblock bei Höhe y (glatte Rampe statt Stufen). */
    fun snowLayers(y: Double): Int = 1 + ((y - floor(y)) * 8.0).toInt().coerceIn(0, 7)

    /** Lauffläche über Punkt i (mit Schneeschicht). */
    fun standY(i: Int): Double {
        val y = y(i)
        return if (kind(i) == BRIDGE) floor(y) + 1.0 else floor(y) + 1.0 + (snowLayers(y) - 1) / 8.0
    }

    // --- Spaltenzuordnung -------------------------------------------------------------------------------------------

    private fun key(x: Int, z: Int): Long = (x.toLong() shl 32) or (z.toLong() and 0xFFFFFFFFL)

    /** Spalte → (Index shl 13) | (Seitenabstand × 64 shl 1) | Seite. */
    private val cols = HashMap<Long, Long>(1 shl 16)

    /** Laternenpfosten (Spalte → Index des Wegpunkts). */
    private val posts = HashMap<Long, Int>()

    /** Hängelaternen an der Tunneldecke (Spalte → Index). */
    private val lamps = HashMap<Long, Int>()

    init {
        val reach = kotlin.math.ceil(ENVELOPE).toInt() + 1
        for (i in 0 until b.n) {
            val i0 = (i - 2).coerceAtLeast(0)
            val i1 = (i + 2).coerceAtMost(b.n - 1)
            var tx = b.x[i1] - b.x[i0]
            var tz = b.z[i1] - b.z[i0]
            val tl = hypot(tx, tz).coerceAtLeast(1e-6)
            tx /= tl; tz /= tl
            val cx = floor(b.x[i]).toInt()
            val cz = floor(b.z[i]).toInt()
            for (ox in -reach..reach) for (oz in -reach..reach) {
                val x = cx + ox
                val z = cz + oz
                val dx = x + 0.5 - b.x[i]
                val dz = z + 0.5 - b.z[i]
                val d = hypot(dx, dz)
                if (d > ENVELOPE) continue
                val k = key(x, z)
                val old = cols[k]
                val q = (d * 64.0).toLong()
                if (old != null && ((old shr 1) and 0xFFF) <= q) continue
                val side = if (tx * dz - tz * dx >= 0) 1L else 0L
                cols[k] = (i.toLong() shl 13) or (q shl 1) or side
            }
        }
        // Pfosten: am Weg alle 16 Blöcke auf der Talseite, auf Brücken alle 9 Blöcke beidseitig; Lampen im Tunnel alle 10.
        var nextPost = 6.0
        var nextBridge = 4.0
        var nextLamp = 3.0
        for (i in 0 until b.n) {
            val a = b.arc[i]
            when (b.kind[i]) {
                BRIDGE -> if (a >= nextBridge) {
                    nextBridge = a + 9.0
                    for (s in intArrayOf(-1, 1)) {
                        val (px, pz) = sideOffset(i, s * (HALF_W - 0.6))
                        posts[key(floor(px).toInt(), floor(pz).toInt())] = i
                    }
                }
                TUNNEL -> if (a >= nextLamp) {
                    nextLamp = a + 10.0
                    lamps[key(floor(b.x[i]).toInt(), floor(b.z[i]).toInt())] = i
                }
                else -> if (a >= nextPost) {
                    nextPost = a + 16.0
                    // Talseite: für Spiralen außen (Normale zeigt von der Achse weg), auf Geraden links.
                    val px = b.x[i] + b.nx[i] * (HALF_W - 0.6)
                    val pz = b.z[i] + b.nz[i] * (HALF_W - 0.6)
                    posts[key(floor(px).toInt(), floor(pz).toInt())] = i
                }
            }
            if (b.kind[i] != BRIDGE) nextBridge = a + 4.0
        }
    }

    private fun sideOffset(i: Int, off: Double): Pair<Double, Double> {
        val i0 = (i - 2).coerceAtLeast(0)
        val i1 = (i + 2).coerceAtMost(b.n - 1)
        var tx = b.x[i1] - b.x[i0]
        var tz = b.z[i1] - b.z[i0]
        val tl = hypot(tx, tz).coerceAtLeast(1e-6)
        tx /= tl; tz /= tl
        return (b.x[i] - tz * off) to (b.z[i] + tx * off)
    }

    /** Gepackter Eintrag der Spalte oder −1. */
    fun hit(x: Int, z: Int): Long = cols[key(x, z)] ?: -1L

    fun hitIndex(h: Long): Int = (h shr 13).toInt()
    fun hitLateral(h: Long): Double = ((h shr 1) and 0xFFF) / 64.0

    /** Index des Wegpunkts, wenn (x, z) auf der Lauffläche liegt (Seitenabstand ≤ [HALF_W] + slack), sonst −1. */
    fun indexAt(x: Int, z: Int, slack: Double = 0.0): Int {
        val h = hit(x, z)
        if (h < 0) return -1
        return if (hitLateral(h) <= HALF_W + slack) hitIndex(h) else -1
    }

    fun postAt(x: Int, z: Int): Int = posts[key(x, z)] ?: -1
    fun lampAt(x: Int, z: Int): Int = lamps[key(x, z)] ?: -1

    /** Nächster Wegpunkt zu einem Punkt abseits des Wegs; lieber eine Stelle knapp unter dem Punkt (zurückfallen statt hochheben). */
    fun nearest(x: Double, y: Double, z: Double): Int {
        var best = 0
        var bestScore = Double.MAX_VALUE
        var i = 0
        while (i < b.n) {
            val d = hypot(b.x[i] - x, b.z[i] - z)
            val dy = y - (b.y[i] + 1.0)
            val score = d + if (dy >= -2.0) dy * 0.5 else 30.0 - dy * 2.0
            if (score < bestScore) {
                bestScore = score
                best = i
            }
            i += 2
        }
        return best
    }
}
