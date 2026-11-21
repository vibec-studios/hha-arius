package dev.henny.hha_arius.arena

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
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
 *   Feld (VAULT, Start) ──Pfad──▶ 1. Berg (Erste Wacht, 48 hoch, Spirale bis zum Gipfel, zwei Höhlen)
 *     ──Hängebrücke B1 (y 48)──▶ 2. Berg (Zweite Wacht, 88 hoch, Spirale, zwei Höhlen)
 *     ──Steinbrücke B2 (y 88)──▶ 3. Berg (Dritte Wacht, 132 hoch): Höhle mit gefrorenem See quer durch den Berg
 *     ──Hängebrücke B3 (y 100)──▶ Massiv der Nadel: eine Windung bergauf (Kristallgrotte, Windtunnel, Aderhöhle)
 *     ──▶ Tor der Klinge in der Gipfelmauer (Osten, y 172) ──▶ Gipfelarena
 * ```
 * Der Weg ist eine dichte Punktfolge (alle 0,5 Blöcke). Jede Spalte bis [ENVELOPE] neben der Mittellinie kennt ihren
 * nächsten Punkt (Index, Seitenabstand), daraus baut FarBuild Pfad, Brücken, Höhlen und Wände.
 *
 * Seit 1.10.0 hat jede Spalte **zwei Ebenen**: den Boden (Pfad, Höhle, Tor) und darüber eine Brücke. B1 und B2 verlassen
 * ihren Gipfel über die eigenen tieferen Windungen; bisher gehörte eine Spalte nur dem waagerecht nächsten Punkt, und die
 * Brücke übernahm Teile des Pfads darunter (Bögen und Pfeiler schnitten ihn ab). Liegt die Brücke mehr als [LAYER] über
 * dem Boden, sind es zwei getrennte Wege ([overhead]).
 */
internal object HuntRoute {

    const val TRAIL = 0
    const val BRIDGE = 1
    const val TUNNEL = 2
    const val GATE = 3

    /** Seit 1.10.0: Hängebrücke (Seile, Planken, schwingt; Kollision aus unsichtbaren Deckblöcken). */
    const val ROPE = 4

    /** Halbe Wegbreite: Lauffläche 7 Blöcke, auf Brücken 5 zwischen den Brüstungen. */
    const val HALF_W = 3.5

    /** Halbe Breite des Plankendecks einer Hängebrücke; bis [ROPE_RAIL] stehen die (unsichtbaren) Geländer. */
    const val ROPE_HALF = 1.6
    const val ROPE_RAIL = 2.6

    /** Bis hierhin (Seitenabstand) gehört eine Spalte zur Hülle des Wegs (Tunnelwände, Hangschnitt). */
    const val ENVELOPE = 7.0

    /** Höhenunterschied, ab dem eine Brücke über einem tieferen Weg ein eigener Weg ist. */
    const val LAYER = 7.0

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
        Peak(180.0, 250.0, 84.0, 132.0, 13.0, 2), // Dritte Wacht (mit Höhle)
    )

    /** Felssporn am Massiv, durch den der letzte Aufstieg als Windtunnel führt (wird beim Aufbau des Wegs gesetzt). */
    val BUTTRESS: Peak

    /** Winkel (x = sin, z = cos um die Achse der Nadel), unter dem das Tor der Klinge in der Gipfelmauer steht. */
    val GATE_THETA: Double

    // --- Höhlen ----------------------------------------------------------------------------------------------------

    /**
     * Art einer Höhle (seit 1.10.0, Crix: „mehr Höhlen, und nicht alle gleich“). [height] = freie Blöcke über der
     * Schneeschicht (Decke auf Wegblock + height + 1), [lane] = halbe begehbare Breite, [walk] = halbe Breite, die
     * garantiert frei bleibt (Selbsttest), [lamps] = Seelenlaternen an der Decke.
     */
    enum class Theme(val height: Int, val lane: Double, val walk: Double, val lamps: Boolean) {
        /** Dunkle Höhle, nur von Eisadern erhellt; Fenster nach außen (Solvanes Auge). */
        DARK(5, HALF_W, 2.6, false),

        /** Enge, niedrige Kriechpassage mit Eiszapfen und Rissen in der Wand (Frostatem). */
        CRAWL(3, 1.6, 0.85, false),

        /** Leuchtende Eiskristallgrotte, hoch, mit einer Eiswand nach außen (Solvane gleitet vorbei). */
        CRYSTAL(8, HALF_W, 2.2, true),

        /** Kaverne mit gefrorenem Wasserfall, sehr hoch. */
        FALL(9, HALF_W, 2.2, true),

        /** Unterirdischer gefrorener See unter einer Kuppel (quer durch die Dritte Wacht). */
        LAKE(9, HALF_W, 2.6, true),

        /** Windtunnel durch den Felssporn am Massiv: der Sturm drückt gegen die Laufrichtung. */
        WIND(6, HALF_W, 2.6, true),
    }

    /**
     * Eine Höhle von Wegpunkt [i0] bis [i1]. Neben dem Hauptgang liegt auf der Bergseite ein enger Nebengang (Umweg),
     * an beiden Enden mit dem Hauptgang verbunden. Fällt Geröll und versiegelt den Hauptgang ([plugFrom]..[plugTo] in
     * Bogenlänge ab dem Eingang), bleibt der Nebengang offen: nie eingesperrt.
     */
    class Cave(val id: Int, val theme: Theme, val i0: Int, val i1: Int) {
        val a0: Double get() = arc(i0)
        val a1: Double get() = arc(i1)
        val length: Double get() = a1 - a0

        /** Bogenlänge ab Eingang, in der der Nebengang mit dem Hauptgang verbunden ist. */
        val joint = 3.0

        val plugFrom = 4.5
        val plugTo = 6.5

        /** Fenster/Risse in der Talwand (Bogenlänge ab Eingang). */
        val windows: DoubleArray = when (theme) {
            Theme.DARK -> doubleArrayOf(11.0, 19.0)
            Theme.CRAWL -> doubleArrayOf(9.0, 14.0)
            Theme.FALL -> doubleArrayOf(9.0, 21.0)
            else -> DoubleArray(0)
        }

        /** Strecke (ab Eingang) mit Eiswand statt Fels auf der Talseite (Kristallgrotte). */
        val iceFrom = if (theme == Theme.CRYSTAL) 10.0 else -1.0
        val iceTo = if (theme == Theme.CRYSTAL) 24.0 else -1.0

        /** Kuppel mit See auf der Talseite (ab Eingang). */
        val lakeFrom = if (theme == Theme.LAKE) 12.0 else -1.0
        val lakeTo = if (theme == Theme.LAKE) length - 12.0 else -1.0

        fun contains(i: Int) = i in i0..i1
        fun u(i: Int) = arc(i) - a0
    }

    private val caveList = ArrayList<Cave>()
    val caves: List<Cave> get() = caveList

    /** Höhle des Wegpunkts [i] oder null. */
    fun caveOf(i: Int): Cave? {
        for (c in caveList) if (i in c.i0..c.i1) return c
        return null
    }

    // --- Hängebrücken -------------------------------------------------------------------------------------------------

    /** Eine Hängebrücke von Wegpunkt [i0] bis [i1] (Deck = belastete Lage, durchhängend um [sag]). */
    class Span(val id: Int, val i0: Int, val i1: Int, val sag: Double, val y0: Double) {
        val a0: Double get() = arc(i0)
        val a1: Double get() = arc(i1)
        val length: Double get() = a1 - a0
    }

    private val spanList = ArrayList<Span>()
    val ropeSpans: List<Span> get() = spanList

    fun spanOf(i: Int): Span? {
        for (s in spanList) if (i in s.i0..s.i1) return s
        return null
    }

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
        fun line(x0: Double, z0: Double, y0: Double, x1: Double, z1: Double, y1: Double, k: Int): Int {
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
            if (k == BRIDGE || k == ROPE) for (i in start until n) {
                span0[i] = arc[start]
                span1[i] = arc[n - 1]
            }
            return start
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

        /** Erster Index ab [from] mit Bogenlänge ≥ [a]. */
        fun at(from: Int, a: Double): Int {
            var i = from
            while (i < n - 1 && arc[i] < a) i++
            return i
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
        val sp1 = b.n
        val th1out = ang(m2.x - m1.x, m2.z - m1.z)
        b.spiral(m1.x, m1.z, m1::flankR, th1in, ccw(th1in, th1out), -1.0, m1.top, m1.plateau - 1.0, TRAIL)
        val sp1End = b.n - 1
        // 2) Hängebrücke B1 zur Zweiten Wacht
        val th2in = ang(m1.x - m2.x, m1.z - m2.z)
        val r2in = m2.flankR(m1.top) - INSET
        val b1 = b.line(b.x[b.n - 1], b.z[b.n - 1], m1.top, m2.x + sin(th2in) * r2in, m2.z + cos(th2in) * r2in, m1.top, ROPE)
        rope(b1, b.n - 1, m1.top)
        // 3) Spirale auf die Zweite Wacht
        val sp2 = b.n
        val th2out = ang(m3.x - m2.x, m3.z - m2.z)
        b.spiral(m2.x, m2.z, m2::flankR, th2in, ccw(th2in, th2out), m1.top, m2.top, m2.plateau - 1.0, TRAIL)
        val sp2End = b.n - 1
        // 4) Steinbrücke B2 zur Dritten Wacht
        val th3in = ang(m2.x - m3.x, m2.z - m3.z)
        val r3in = m3.flankR(m2.top) - INSET
        b.line(b.x[b.n - 1], b.z[b.n - 1], m2.top, m3.x + sin(th3in) * r3in, m3.z + cos(th3in) * r3in, m2.top, BRIDGE)
        // 5) Höhle quer durch die Dritte Wacht, leicht steigend
        val yExit = m2.top + 12.0
        val th3out = ang(SX - m3.x, SZ - m3.z)
        val r3out = m3.flankR(yExit) - INSET
        val tun = b.line(b.x[b.n - 1], b.z[b.n - 1], m2.top, m3.x + sin(th3out) * r3out, m3.z + cos(th3out) * r3out, yExit, TUNNEL)
        val tunEnd = b.n - 1
        // 6) Hängebrücke B3 zum Massiv der Nadel
        val thm = ang(b.x[b.n - 1] - SX, b.z[b.n - 1] - SZ)
        val rm = FarPlan.massifR(yExit) - INSET
        val b3 = b.line(b.x[b.n - 1], b.z[b.n - 1], yExit, SX + sin(thm) * rm, SZ + cos(thm) * rm, yExit, ROPE)
        rope(b3, b.n - 1, yExit)
        // 7) Eine Windung am Massiv bis unter die Gipfelmauer
        val spiralStart = b.n
        val rTop = FarPlan.SHOULDER_R + 6.0
        b.spiral(SX, SZ, FarPlan::massifR, thm, 2 * PI, yExit, FarPlan.SHOULDER_Y, rTop, TRAIL)
        val spMEnd = b.n - 1
        // Die letzten Blöcke vor der Geraden durchs Tor eben auslaufen lassen (die Gerade biegt im rechten Winkel ab;
        // ihre Spalten überdecken das Ende der Windung, das sonst bis zu 0,7 tiefer läge).
        run {
            val aEnd = b.arc[spMEnd]
            for (i in spiralStart..spMEnd) {
                val left = aEnd - b.arc[i]
                if (left > 24.0) continue
                val t = ((24.0 - left) / 20.0).coerceIn(0.0, 1.0)
                val w = t * t * (3 - 2 * t)
                b.y[i] = b.y[i] + (FarPlan.SHOULDER_Y - b.y[i]) * w
            }
        }
        // Felssporn im Nordwesten (ein gutes Drittel der Windung): der Weg führt als Windtunnel hindurch.
        var bi = spiralStart
        while (bi < b.n - 1 && (atan2(b.x[bi] - SX, b.z[bi] - SZ) - thm).let { var d = it % (2 * PI); if (d < 0) d += 2 * PI; d } < 2.17) bi++
        BUTTRESS = Peak(b.x[bi], b.z[bi], 22.0, b.y[bi] + 26.0, 3.0, 3)
        var w0 = -1
        var w1 = -1
        for (i in spiralStart until b.n) if (hypot(b.x[i] - BUTTRESS.x, b.z[i] - BUTTRESS.z) < 15.0) {
            if (w0 < 0) w0 = i
            w1 = i
        }
        // 8) Gerade durchs Tor auf die Gipfelschulter
        GATE_THETA = thm
        b.line(b.x[b.n - 1], b.z[b.n - 1], FarPlan.SHOULDER_Y, SX + sin(thm) * (FarPlan.SHOULDER_R - 2.0), SZ + cos(thm) * (FarPlan.SHOULDER_R - 2.0), FarPlan.SHOULDER_Y, GATE)

        // Höhlen (Bogenlänge ab Anfang des Abschnitts). Jede mindestens 18 lang (Nebengang, Geröllpfropfen, Fenster).
        fun cave(theme: Theme, from: Int, to: Int, a0: Double, len: Double) {
            val i0 = b.at(from, b.arc[from] + a0)
            val i1 = b.at(i0, b.arc[i0] + len).coerceAtMost(to)
            if (i1 - i0 < 36) return
            for (i in i0..i1) b.kind[i] = TUNNEL
            caveList += Cave(caveList.size, theme, i0, i1)
        }
        cave(Theme.DARK, sp1, sp1End, 50.0, 26.0)
        cave(Theme.CRAWL, sp1, sp1End, 118.0, 20.0)
        cave(Theme.CRYSTAL, sp2, sp2End, 24.0, 32.0)
        cave(Theme.FALL, sp2, sp2End, 100.0, 28.0)
        // Die Höhle der Dritten Wacht: ganzer Abschnitt (die Enden sind Hangschnitte, die Haube macht sie zu Mündungen).
        cave(Theme.LAKE, tun, tunEnd, 0.0, b.arc[tunEnd] - b.arc[tun])
        cave(Theme.CRYSTAL, spiralStart, spMEnd, 48.0, 30.0)
        if (w0 >= 0) cave(Theme.WIND, w0, w1, 0.0, b.arc[w1] - b.arc[w0])
        cave(Theme.DARK, spiralStart, spMEnd, 252.0, 24.0)
        caveList.sortBy { it.i0 }
    }

    /** Ebener Brückenkopf einer Hängebrücke (Blöcke je Ende). */
    const val HEAD = 4.0

    /** Macht aus der gerade gelegten Geraden eine durchhängende Hängebrücke. */
    private fun rope(i0: Int, i1: Int, y0: Double) {
        val len = b.arc[i1] - b.arc[i0]
        val sag = (len / 24.0).coerceIn(1.5, 3.4)
        // Die ersten und letzten 4 Blöcke liegen eben (Brückenkopf auf Höhe des Pfads), dazwischen hängt sie durch.
        for (i in i0..i1) {
            val u = ((b.arc[i] - b.arc[i0] - HEAD) / (len - 2 * HEAD)).coerceIn(0.0, 1.0)
            b.y[i] = y0 - sag * 4.0 * u * (1.0 - u)
        }
        // Brückenköpfe gehören zum Pfad davor/danach (fester Fels unter den Pfosten).
        spanList += Span(spanList.size, i0, i1, sag, y0)
    }

    // --- Arius' feste Route (seit 1.11.0) ----------------------------------------------------------------------------

    /** Abstand der Sprungpunkte entlang des Wegs. */
    private const val STATION_STEP = 7.0

    /**
     * Die festen Landepunkte von Arius: Wegmitte alle [STATION_STEP] Blöcke, ohne das freie Stück der Hängebrücken und
     * ohne die Stelle, an der Geröll den Hauptgang verschließt. [RouteCheck] prüft jeden gegen das gebaute Gelände.
     */
    val stations: IntArray by lazy {
        val out = ArrayList<Int>()
        var next = 0.0
        for (i in 0 until b.n) {
            if (b.kind[i] == GATE) break
            val a = b.arc[i]
            if (a < next) continue
            val span = spanOf(i)
            if (span != null && a > span.a0 + HEAD && a < span.a1 - HEAD) continue
            val cave = caveOf(i)
            if (cave != null && cave.u(i) in cave.plugFrom - 1.5..cave.plugTo + 1.5) continue
            out += i
            next = a + STATION_STEP
        }
        out.toIntArray()
    }

    /** Letzter Sprungpunkt mit Bogenlänge ≤ [a] (oder der erste). */
    fun stationBefore(a: Double): Int {
        val s = stations
        var lo = 0
        var hi = s.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (b.arc[s[mid]] <= a) lo = mid else hi = mid - 1
        }
        return s[lo]
    }

    /**
     * Sprunghöhe, die der Bauplan über Wegpunkt [i] immer frei hält (Arius ist 2 hoch; [RouteCheck] prüft sie). Im Spiel
     * misst [dev.henny.hha_arius.boss.Hunt] zusätzlich die echte Kopffreiheit und springt im Freien höher.
     */
    fun liftAt(i: Int): Double = when (kind(i)) {
        TUNNEL -> caveOf(i)?.theme.let { t -> ((t?.height ?: 4) - (if (t?.lamps == true) 4.0 else 3.2)).coerceAtLeast(0.0) } // Laternen hängen eine Stufe unter der Decke
        ROPE, BRIDGE -> 1.8
        else -> 1.0
    }

    /** Morsche Planken einer Hängebrücke (Bogenlänge ab Brückenanfang): sie brechen, wenn jemand darauftritt. */
    fun weakPlanks(span: Span): DoubleArray {
        val out = ArrayList<Double>()
        var s = HEAD + 5.0
        var n = 0
        while (s < span.length - HEAD - 5.0) {
            out += s + FarPlan.h32(span.id, n, 17, 1801) * 1.5
            s += 8.0 + FarPlan.h32(span.id, n, 31, 1803) * 5.0
            n++
        }
        return out.toDoubleArray()
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

    fun isBridge(i: Int) = kind(i).let { it == BRIDGE || it == ROPE }

    /** Index zur Bogenlänge [a] (nächster Punkt). */
    fun indexOfArc(a: Double): Int {
        var lo = 0
        var hi = b.n - 1
        if (a <= 0.0) return 0
        if (a >= b.arc[hi]) return hi
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (b.arc[mid] <= a) lo = mid else hi = mid
        }
        return if (a - b.arc[lo] < b.arc[hi] - a) lo else hi
    }

    /** Waagerechte Laufrichtung bei [i] (normiert) als (x, z). */
    fun tangent(i: Int): Pair<Double, Double> {
        val i0 = (i - 2).coerceAtLeast(0)
        val i1 = (i + 2).coerceAtMost(b.n - 1)
        val tx = b.x[i1] - b.x[i0]
        val tz = b.z[i1] - b.z[i0]
        val tl = hypot(tx, tz).coerceAtLeast(1e-6)
        return (tx / tl) to (tz / tl)
    }

    /** Liegt (x, z) (Spaltenmitte) auf der Talseite des Wegpunkts [i] (Normale zeigt von der Bergachse weg)? */
    fun outward(i: Int, x: Int, z: Int): Boolean =
        (x + 0.5 - x(i)) * normalX(i) + (z + 0.5 - z(i)) * normalZ(i) > 0.0

    /** Schneeschichten (1–8) auf dem Wegblock bei Höhe y (glatte Rampe statt Stufen). */
    fun snowLayers(y: Double): Int = 1 + ((y - floor(y)) * 8.0).toInt().coerceIn(0, 7)

    /** Deck einer Hängebrücke über Punkt i: Block (y) und Schichten (1–8) des unsichtbaren Deckblocks. */
    fun deckBlock(i: Int): Int = floor(deckTop(i) - 1e-6).toInt()
    fun deckLayers(i: Int): Int = ((deckTop(i) - deckBlock(i)) * 8.0 + 0.5).toInt().coerceIn(1, 8)
    private fun deckTop(i: Int): Double = ceil((y(i) + 1.0) * 8.0 - 1e-6) / 8.0

    /** Lauffläche über Punkt i (mit Schneeschicht). */
    fun standY(i: Int): Double {
        val y = y(i)
        return when (kind(i)) {
            BRIDGE -> floor(y) + 1.0
            ROPE -> deckBlock(i) + deckLayers(i) / 8.0
            else -> floor(y) + 1.0 + (snowLayers(y) - 1) / 8.0
        }
    }

    /** Halbe Breite, die an Punkt [i] sicher begehbar ist (Selbsttest, Rettung). */
    fun walkHalf(i: Int): Double {
        // Am Kopf einer Hängebrücke wird der Pfad auf Brückenbreite schmal.
        for (s in spanList) if (arc(i) in s.a0 - 4.0..s.a1 + 4.0) return ROPE_HALF - 0.75
        return when (kind(i)) {
            BRIDGE -> HALF_W - 1.0 - 0.8
            TUNNEL -> caveOf(i)?.theme?.walk ?: (HALF_W - 1.0)
            GATE -> 1.8
            else -> HALF_W - 0.9
        }
    }

    // --- Spaltenzuordnung -------------------------------------------------------------------------------------------

    private fun key(x: Int, z: Int): Long = (x.toLong() shl 32) or (z.toLong() and 0xFFFFFFFFL)

    /** Spalte → (Index shl 13) | (Seitenabstand × 64 shl 1) | Seite: Boden (Pfad, Höhle, Tor). */
    private val cols = HashMap<Long, Long>(1 shl 16)

    /** Zweite Boden-Ebene: nächster Punkt, dessen Höhe mehr als [LAYER] vom ersten Eintrag abweicht (Windungen übereinander). */
    private val cols2 = HashMap<Long, Long>(1 shl 14)

    /** Dasselbe für Brücken (Stein und Seil). */
    private val bcols = HashMap<Long, Long>(1 shl 14)

    /** Laternenpfosten (Spalte → Index des Wegpunkts). */
    private val posts = HashMap<Long, Int>()

    /** Hängelaternen an der Tunneldecke (Spalte → Index). */
    private val lamps = HashMap<Long, Int>()

    init {
        val reach = ceil(ENVELOPE).toInt() + 1
        for (i in 0 until b.n) {
            val map = if (b.kind[i] == BRIDGE || b.kind[i] == ROPE) bcols else cols
            val (tx, tz) = tangent(i)
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
                val old = map[k]
                val q = (d * 64.0).toLong()
                if (old != null && ((old shr 1) and 0xFFF) <= q) continue
                val side = if (tx * dz - tz * dx >= 0) 1L else 0L
                map[k] = (i.toLong() shl 13) or (q shl 1) or side
            }
        }
        // Zweite Ebene des Bodens (Höhlen-Nebengang unter der Talseite der Windung darüber und umgekehrt).
        for (i in 0 until b.n) {
            if (b.kind[i] == BRIDGE || b.kind[i] == ROPE) continue
            val (tx, tz) = tangent(i)
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
                val first = cols[k] ?: continue
                if (abs(b.y[hitIndex(first)] - b.y[i]) <= LAYER) continue
                val old = cols2[k]
                val q = (d * 64.0).toLong()
                if (old != null && ((old shr 1) and 0xFFF) <= q) continue
                val side = if (tx * dz - tz * dx >= 0) 1L else 0L
                cols2[k] = (i.toLong() shl 13) or (q shl 1) or side
            }
        }
        // Pfosten: am Weg alle 16 Blöcke auf der Talseite, auf Steinbrücken alle 9 Blöcke beidseitig; Lampen in Höhlen alle 10.
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
                ROPE -> Unit
                TUNNEL -> if (a >= nextLamp) {
                    nextLamp = a + 10.0
                    if (caveOf(i)?.theme?.lamps != false) lamps[key(floor(b.x[i]).toInt(), floor(b.z[i]).toInt())] = i
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
        val (tx, tz) = tangent(i)
        return (b.x[i] - tz * off) to (b.z[i] + tx * off)
    }

    /**
     * Gepackter Eintrag der Spalte oder −1: der Weg, dem die Spalte am Boden gehört. Liegt eine Brücke auf gleicher Höhe
     * (Brückenkopf), entscheidet wie bisher der kleinere Seitenabstand; liegt sie mehr als [LAYER] darüber, gehört die
     * Spalte dem Boden und die Brücke ist [overhead].
     */
    fun hit(x: Int, z: Int): Long {
        val k = key(x, z)
        val g = cols[k]
        val br = bcols[k] ?: return g ?: -1L
        if (g == null) return br
        val yg = b.y[hitIndex(g)]
        val yb = b.y[hitIndex(br)]
        if (yb - yg > LAYER || yg - yb > LAYER) return if (yg < yb) g else br
        // Brückenkopf: die Lauffläche des Pfads geht vor (sonst Stufen zwischen dem letzten Pfadstück und dem Deck).
        if (hitLateral(g) <= HALF_W) return g
        return if (hitLateral(br) < hitLateral(g)) br else g
    }

    /** Brücke, die hoch über dem Boden-Eintrag dieser Spalte verläuft (zweite Ebene), sonst −1. */
    fun overhead(x: Int, z: Int): Long {
        val k = key(x, z)
        val g = cols[k] ?: return -1L
        val br = bcols[k] ?: return -1L
        return if (b.y[hitIndex(br)] - b.y[hitIndex(g)] > LAYER) br else -1L
    }

    /** Zweiter Boden-Eintrag dieser Spalte (andere Windung, mehr als [LAYER] höher oder tiefer), sonst −1. */
    fun groundSecond(x: Int, z: Int): Long = cols2[key(x, z)] ?: -1L

    /** Eintrag der Brückenebene dieser Spalte (Stein- oder Hängebrücke), sonst −1. */
    fun bridgeEntry(x: Int, z: Int): Long = bcols[key(x, z)] ?: -1L

    fun hitIndex(h: Long): Int = (h shr 13).toInt()
    fun hitLateral(h: Long): Double = ((h shr 1) and 0xFFF) / 64.0

    /** Index des Wegpunkts, wenn (x, z) auf der Lauffläche liegt (Seitenabstand ≤ [HALF_W] + slack), sonst −1. */
    fun indexAt(x: Int, z: Int, slack: Double = 0.0): Int {
        val h = hit(x, z)
        if (h < 0) return -1
        return if (hitLateral(h) <= HALF_W + slack) hitIndex(h) else -1
    }

    /**
     * Wegpunkt unter (x, z), der zu einer Lauffläche bei Höhe [stand] (relativ) passt: Boden oder Brücke darüber, oder −1.
     * Für Rettung und Fortschritt (zwei Ebenen in einer Spalte).
     */
    fun indexAtHeight(x: Int, z: Int, stand: Double, slack: Double = 0.0, tol: Double = 2.5): Int {
        val k = key(x, z)
        var best = -1
        var bestD = Double.MAX_VALUE
        for (h in arrayOf(cols[k], cols2[k], bcols[k])) {
            if (h == null || hitLateral(h) > HALF_W + slack) continue
            val i = hitIndex(h)
            val d = abs(standY(i) - stand)
            if (d <= tol && d < bestD) {
                best = i
                bestD = d
            }
        }
        return best
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
