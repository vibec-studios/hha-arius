package dev.henny.hha_arius.arena

import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.ChunkPos
import net.minecraft.util.math.Vec3d as Vec
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

    const val VERSION = 11 // 11: Drachenhorst, Gipfelkrone, Nebengipfel mit Tunneln, Schluchtbrücken; 10: Berg massiv/dicke Haut, Eisspitzen-Weite, Phase-III-Arena; 9: 9: Ranken nur als schmaler Streifen an echten Stufen; 8: 8: Massiv größer, Kletterweg mit Ranken; 7: 7: endloses Schneefeld, Riesenmassiv mit Spiralweg (neue Dimension); 6: 6: Feld im Hochland statt Balkon, kein Massiv; 5: Bergmassiv mit Terrasse statt schwebendem Balkon; 4: Balkon als Felsschelf mit Strebe, Schneebänder am Horn; 2: Himmelsbalkon an der Nadel; 3: Alpen (Felshorn, Fels/Schnee, offenes Hochland)

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
    const val SHOULDER_R = 34.0
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

    // --- Wegpunkte für den Kampf (seit 1.8.0: Jagd, Sturm hält auf dem Weg) ----------------------------------------

    val pathSize: Int get() = pathY.size

    /** Mitte des Wegs bei Index [i] (relativ, y = Lauffläche über dem obersten Wegblock). */
    fun pathPoint(i: Int): Vec {
        val k = i.coerceIn(0, pathY.size - 1)
        val a = A0 + k * PHI_STEP
        val r = pathR[k]
        return Vec(ArenaLayout.SPIRE.x + 0.5 + sin(a) * r, floor(pathY[k]) + 1.0, ArenaLayout.SPIRE.z + 0.5 + kotlin.math.cos(a) * r)
    }

    /**
     * Route der Jagd (relativ, Laufhöhe): vom Fuß des Bergs über Weg, Kletterstufen und Brücken hinauf; der letzte
     * Punkt ist das Tor zur Gipfelarena (dort endet die Jagd). Arius läuft sie entlang, der Sturm hält die Spieler darauf.
     */
    // placeholder – replaced by the world layout
    fun huntWaypoints(): List<BlockPos> {
        val out = ArrayList<BlockPos>()
        var lastArc = -1e9
        for (i in pathY.indices) {
            if (pathArc[i] - lastArc < 10.0 && i != pathY.size - 1) continue
            lastArc = pathArc[i]
            val p = pathPoint(i)
            out.add(BlockPos.ofFloored(p.x, p.y, p.z))
        }
        // Vom Wegende ein Stück nach innen auf die Gipfelschulter: das Tor zur Gipfelarena.
        val end = pathPoint(pathY.size - 1)
        val dx = ArenaLayout.SPIRE.x + 0.5 - end.x
        val dz = ArenaLayout.SPIRE.z + 0.5 - end.z
        val d = hypot(dx, dz).coerceAtLeast(1e-6)
        out.add(BlockPos.ofFloored(end.x + dx / d * 8.0, SHOULDER_Y + 1.0, end.z + dz / d * 8.0))
        return out
    }

    /** Wegindex unter (x, z) mit etwas Toleranz am Rand oder −1. */
    fun pathIndexNear(x: Double, z: Double, slack: Double): Int {
        val dx = x - (ArenaLayout.SPIRE.x + 0.5)
        val dz = z - (ArenaLayout.SPIRE.z + 0.5)
        val d = hypot(dx, dz)
        var phi = atan2(dx, dz) - A0
        while (phi < 0.0) phi += 2 * PI
        while (phi >= 2 * PI) phi -= 2 * PI
        while (true) {
            val i = (phi / PHI_STEP).toInt()
            if (i >= pathY.size) return -1
            if (abs(d - pathR[i]) <= PATH_W / 2 + slack) return i
            phi += 2 * PI
        }
    }

    /** Nächster Wegindex für einen Punkt abseits des Wegs: gleicher Winkel, die Windung mit der passendsten Höhe. */
    fun nearestPathIndex(x: Double, y: Double, z: Double): Int {
        val dx = x - (ArenaLayout.SPIRE.x + 0.5)
        val dz = z - (ArenaLayout.SPIRE.z + 0.5)
        var phi = atan2(dx, dz) - A0
        while (phi < 0.0) phi += 2 * PI
        while (phi >= 2 * PI) phi -= 2 * PI
        var best = 0
        var bestScore = Double.MAX_VALUE
        while (true) {
            val i = (phi / PHI_STEP).toInt()
            if (i >= pathY.size) break
            // lieber die Windung knapp unter dem Spieler (zurückfallen statt hochheben)
            val dy = y - pathY[i]
            val score = if (dy >= -2.0) dy else 40.0 - dy
            if (score < bestScore) {
                bestScore = score
                best = i
            }
            phi += 2 * PI
        }
        return best
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
        // Seit 1.6.3 nur ein schmaler Rankenstreifen in der Wegmitte (2 Blöcke), nicht über die ganze Breite.
        val dx = x + 0.5 - (ArenaLayout.SPIRE.x + 0.5)
        val dz = z + 0.5 - (ArenaLayout.SPIRE.z + 0.5)
        if (abs(hypot(dx, dz) - pathR[i]) > 1.0) return null
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
        val h = massifH(d) + rough - notchDepth(x, z)
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
        w *= smooth(ArenaLayout.AERIE_RIM + 2.0, ArenaLayout.AERIE_RIM + 30.0, aerieDist(x, z))
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
        val peak = subPeakTop(x, z)
        if (peak > t) t = peak
        val rim = aerieRim(x, z)
        if (rim > t) t = rim
        val p = pathAt(x, z)
        // Auf Brücken (über einer Schlucht) bleibt der abgesenkte Fels darunter; die Planken setzt FarBuild.
        if (!p.isNaN()) t = if (bridgeAt(x, z)) min(t, floor(p).toInt() - 3) else floor(p).toInt()
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
    const val STONEBRICK = 15
    const val GOLD = 16
    const val LBLUE = 17
    const val BONE = 18
    const val PLANKS = 19
    const val FENCE = 20
    const val HANG = 21
    const val WHITE = 22
    const val BRICKWALL = 23

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
        // Seit 1.8.0 (Crix: „die Spitze sieht komisch aus“): über der Gipfelschulter ist das Horn ein Eiskristall aus
        // Facetten (festes und blaues Eis), Schnee nur ganz oben – zusammen mit der Krone aus Eisspitzen ringsum.
        if (spire && y > SHOULDER_Y + 1) {
            if (depth == 0 && y > ArenaLayout.SPIRE_TOP - 10) return SNOW
            return if (vnoise(x + y, z - y, 5, 501) > 0.6) BLUE else PACKED
        }
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
    private val VZ0 = 0
    private val VZ = ArenaLayout.VAULT.z

    /**
     * Die Arena von Abschnitt III (seit 1.6.5 neu, Crix: „die dritte Phase ist immer noch tot“): ein zugefrorener See
     * mit Ringmuster (hellblauer Beton, Kalzitadern – nicht rutschig), am Rand Schneewehen; acht riesige, schräg wachsende Eiskristalle; ein
     * zerbrochener Kreis aus Steinpfeilern mit Bögen (r 37–41); im Norden steckt eine gewaltige Steinklinge im Eis, an
     * der Solvane ruht; der Zuschauerhügel im Süden. Lauffläche bleibt eben (y −1).
     */
    fun fieldFloor(x: Int, z: Int): Int {
        val dx = (x - VX).toDouble()
        val dz = (z - VZ).toDouble()
        val r = hypot(dx, dz)
        if (r > ArenaLayout.VAULT_R + 3.0) return -1
        val ring = (r / 3.0).toInt()
        val vein = abs(vnoise(x, z, 4, 801) - 0.5) < 0.05
        // Nicht rutschig (kein Eis als Lauffläche): Schnee mit Ringen aus hellblauem Beton und Kalzitadern.
        return when {
            r > ArenaLayout.VAULT_R - 1.5 -> SNOW
            vein -> CALCITE
            ring % 3 == 0 && r % 3.0 < 0.9 -> LBLUE
            r < 3.5 -> LBLUE
            else -> SNOW
        }
    }

    fun balcony(x: Int, y: Int, z: Int): Int {
        // Zuschauerhügel südlich des Feldes (Lauffläche 6 über dem Feld)
        val wx = ArenaLayout.WATCH_VAULT.x.toInt()
        val wz = ArenaLayout.WATCH_VAULT.z.toInt()
        if (abs(x - wx) <= 2 && abs(z - wz) <= 2 && y in VY until VY + 6) return if (y == VY + 5) SNOW else STONE
        val dx = (x - VX).toDouble()
        val dz = (z - VZ).toDouble()
        val r = hypot(dx, dz)
        if (r > ArenaLayout.VAULT_R + 10.0) return -1
        val hy = y - VY
        // Riesige Eiskristalle: schräg nach außen geneigt, 12–20 hoch, Kern blau
        for ((i, p) in ArenaLayout.VAULT_PILLARS.withIndex()) {
            val hgt = 12 + (h32(p.x, 0, p.z, 707) * 9).toInt()
            if (hy < 0 || hy >= hgt) continue
            val out = Vec(p.x - VX.toDouble(), 0.0, p.z - VZ.toDouble()).normalize()
            val lean = hy * 0.22
            val cx = p.x + out.x * lean
            val cz = p.z + out.z * lean
            val pr = hypot(x + 0.5 - cx - 0.5, z + 0.5 - cz - 0.5)
            val rad = 2.2 * (1.0 - hy / hgt.toDouble()) + 0.4 + (if (i % 2 == 0) 0.4 else 0.0)
            if (pr <= rad) return if (pr < rad * 0.45) BLUE else PACKED
        }
        // Zerbrochener Pfeilerkreis mit Bögen (r 39)
        if (r in 37.5..40.5) {
            val ang = Math.toDegrees(kotlin.math.atan2(dz, dx))
            val seg = Math.floorMod(Math.floorDiv((ang + 180.0).toInt(), 15), 24)
            val inSeg = Math.floorMod((ang + 180.0).toInt(), 15)
            val broken = h32(seg, 0, 0, 811) < 0.3
            val pillarH = if (broken) 3 + (h32(seg, 1, 0, 813) * 3).toInt() else 9
            if (inSeg < 3 && hy in 0 until pillarH) return if (hy == pillarH - 1 && !broken) CALCITE else STONEBRICK
            if (!broken && h32(seg + 1, 0, 0, 811) >= 0.3 && hy in 8..9 && r in 38.0..40.0) return if (hy == 9) CALCITE else STONEBRICK
            if (inSeg < 3 && hy == -1) return STONEBRICK
        }
        // Die große Steinklinge im Norden (bei VAULT_REST), Schneide in x-Richtung, 30 hoch
        val bx = ArenaLayout.VAULT_REST.x.toInt()
        val bz = ArenaLayout.VAULT_REST.z.toInt() - 6
        val u = x - bx
        val v = z - bz
        if (abs(v) <= 1 && abs(u) <= 9 && hy >= 0) {
            val bladeH = 22
            if (v == 0 && abs(u) <= 3 && hy < bladeH) {
                if (hy < 2 && abs(u) == 3) return -1
                return if (abs(u) == 3) CALCITE else if (u == 0) BLUE else STONE
            }
            if (hy == bladeH && v == 0 && abs(u) <= 8) return if (abs(u) == 8) GOLD else DEEP
            if (hy == bladeH && abs(v) == 1 && abs(u) <= 1) return DEEP
            if (v == 0 && u == 0 && hy in bladeH + 1..bladeH + 4) return DEEP
            if (v == 0 && u == 0 && hy == bladeH + 5) return GOLD
        }
        return -1
    }

    /**
     * Eisspitzen der endlosen Weite (seit 1.6.5, Crix: „diesen unendlichen Bereich hast du gar nicht visuell
     * verändert“): im ganzen Fernbereich außerhalb der Kampfflächen ein lichter Wald aus Eisnadeln (8–34 hoch, schmal,
     * blauer Kern). Weiter draußen setzt der Weltgenerator dasselbe Biom-Merkmal fort.
     * Liefert für (x, z) Oberkante und Radius-Anteil (Kern) oder null.
     */
    fun iceSpike(x: Int, z: Int): IntArray? {
        if (keepClear(x, z) || !massifTop(x, z).let { it == Int.MIN_VALUE }) return null
        val cell = 11
        val cx = Math.floorDiv(x, cell)
        val cz = Math.floorDiv(z, cell)
        for (ix in cx - 1..cx + 1) for (iz in cz - 1..cz + 1) {
            if (h32(ix, 0, iz, 901) > 0.42) continue
            val sx = ix * cell + (h32(ix, 1, iz, 903) * cell).toInt()
            val sz = iz * cell + (h32(ix, 2, iz, 905) * cell).toInt()
            if (keepClear(sx, sz)) continue
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

    /** Freizuhaltende Kampfflächen (Eisspitzen der endlosen Weite werden dort entfernt). */
    fun keepClear(x: Int, z: Int): Boolean {
        if (hypot(x.toDouble(), z.toDouble()) <= 112.0) return true
        if (hypot((x - VX).toDouble(), (z - VZ).toDouble()) <= ArenaLayout.VAULT_R + 26.0) return true
        if (aerieDist(x, z) <= ArenaLayout.AERIE_RIM + 6.0) return true
        return !pathAt(x, z).isNaN()
    }

    // --- Nebengipfel und Schluchten am Spiralweg (seit 1.8.0) -------------------------------------------------------

    /**
     * Crix: „mach, dass man teilweise durch Höhlen gehen muss, über Brücken, dass da mehrere Berge sind“. Drei
     * Nebengipfel wachsen aus der Flanke des Massivs; wo der Spiralweg sie kreuzt, führt er als Tunnel hindurch
     * (Decke über [tunnelRoof], Laternen). Drei Schluchten schneiden die Flanke ein; dort überspannt der Weg sie als
     * Holzbrücke mit Geländer ([bridgeAt]).
     */
    private val PEAKS = arrayOf(
        doubleArrayOf(102.0, 252.0, 30.0, 150.0), // x, z, Radius, Gipfelhöhe (relativ)
        doubleArrayOf(-108.0, 268.0, 28.0, 140.0),
        doubleArrayOf(-52.0, 352.0, 26.0, 128.0),
    )

    /** Oberkante eines Nebengipfels über (x, z) oder [Int.MIN_VALUE]. */
    fun subPeakTop(x: Int, z: Int): Int {
        var best = Int.MIN_VALUE
        for (pk in PEAKS) {
            val d = hypot(x + 0.5 - pk[0], z + 0.5 - pk[1])
            val rr = pk[2] * (1.0 + 0.12 * (vnoise(x, z, 9, 611) - 0.5))
            if (d >= rr) continue
            val base = massifH(hypot(pk[0] - (ArenaLayout.SPIRE.x + 0.5), pk[1] - (ArenaLayout.SPIRE.z + 0.5))).coerceAtLeast(0.0)
            var h = base + (pk[3] - base) * Math.pow(1.0 - d / rr, 1.25)
            h += (ridged(x, z, 7, 613) - 0.5) * 6.0 * (1.0 - d / rr)
            best = max(best, floor(h).toInt())
        }
        return best
    }

    /** Fels über einem Tunnel des Wegs: Oberkante des Gipfels, wenn er hoch genug über dem Weg liegt, sonst MIN. */
    fun tunnelRoof(x: Int, z: Int): Int {
        val p = pathAt(x, z)
        if (p.isNaN()) return Int.MIN_VALUE
        val peak = subPeakTop(x, z)
        return if (peak >= floor(p).toInt() + 8) peak else Int.MIN_VALUE
    }

    /** Winkel (wie der Weg, ab Norden) der drei Schluchten. */
    private val NOTCHES = doubleArrayOf(0.95, 2.6, 4.35)

    /** Tiefe der Schlucht an (x, z): V-förmig quer zur Flanke, bis 30 Blöcke. */
    fun notchDepth(x: Int, z: Int): Double {
        val dx = x + 0.5 - (ArenaLayout.SPIRE.x + 0.5)
        val dz = z + 0.5 - (ArenaLayout.SPIRE.z + 0.5)
        val d = hypot(dx, dz)
        if (d < SHOULDER_R + 22.0 || d > MASSIF_R - 14.0) return 0.0
        var phi = atan2(dx, dz) - A0
        while (phi < 0.0) phi += 2 * PI
        var depth = 0.0
        for (n in NOTCHES) {
            var dp = phi - n
            while (dp > PI) dp -= 2 * PI
            while (dp < -PI) dp += 2 * PI
            val lat = abs(sin(dp) * d)
            if (abs(dp) > PI / 2 || lat > 8.0) continue
            val k = 1.0 - (lat / 8.0) * (lat / 8.0)
            val along = smooth(SHOULDER_R + 22.0, SHOULDER_R + 34.0, d) * smooth(MASSIF_R - 14.0, MASSIF_R - 30.0, d)
            depth = max(depth, 30.0 * k * along)
        }
        return depth
    }

    /** Liegt der Weg hier über einer Schlucht (Brücke statt Fels)? */
    fun bridgeAt(x: Int, z: Int): Boolean = notchDepth(x, z) > 5.0 && !pathAt(x, z).isNaN()

    /** Am Rand der Brücke (Geländer)? */
    fun bridgeEdge(x: Int, z: Int): Boolean {
        val i = pathIndex(x, z)
        if (i < 0) return false
        val d = hypot(x + 0.5 - (ArenaLayout.SPIRE.x + 0.5), z + 0.5 - (ArenaLayout.SPIRE.z + 0.5))
        return abs(d - pathR[i]) > PATH_W / 2 - 1.0
    }

    /** Laterne an der Tunneldecke (Wegmitte, alle 14 Blöcke). */
    fun tunnelLamp(x: Int, z: Int): Boolean {
        val i = pathIndex(x, z)
        if (i < 0) return false
        val d = hypot(x + 0.5 - (ArenaLayout.SPIRE.x + 0.5), z + 0.5 - (ArenaLayout.SPIRE.z + 0.5))
        return abs(d - pathR[i]) < 0.6 && (pathArc[i] % 14.0) < 0.5
    }

    // --- Gipfelkrone (seit 1.8.0) -----------------------------------------------------------------------------------

    /** Winkel, unter dem der Weg auf der Gipfelschulter ankommt (Lücke in der Brüstung). */
    private val pathEndPhi: Double get() = A0 + (pathY.size - 1) * PHI_STEP

    /**
     * Krone auf der Gipfelschulter: acht schräg nach außen wachsende Eisspitzen (16–34 hoch) am Rand, eine Brüstung aus
     * Steinziegelmauern mit Laternen dazwischen (Lücke, wo der Weg ankommt). Liefert die Block-Id für (x, y, z) über
     * der Schulter oder −1.
     */
    fun crown(x: Int, y: Int, z: Int): Int {
        val dx = x + 0.5 - (ArenaLayout.SPIRE.x + 0.5)
        val dz = z + 0.5 - (ArenaLayout.SPIRE.z + 0.5)
        val d = hypot(dx, dz)
        if (d > SHOULDER_R + 14.0) return -1
        val hy = y - SHOULDER_Y.toInt()
        if (hy < 1) return -1
        val ang = atan2(dx, dz)
        for (k in 0 until 8) {
            val a = A0 + (k + 0.5) * PI / 4
            val hgt = 16 + (h32(k, 0, 0, 701) * 18).toInt()
            if (hy >= hgt) continue
            val lean = hy * 0.3
            val cx = sin(a) * (SHOULDER_R - 3.0 + lean)
            val cz = kotlin.math.cos(a) * (SHOULDER_R - 3.0 + lean)
            val pr = hypot(dx - cx, dz - cz)
            val rad = 3.2 * (1.0 - hy / hgt.toDouble()) + 0.5
            if (pr <= rad) return if (pr < rad * 0.45) BLUE else if (hy > hgt - 3) SNOW else PACKED
        }
        // Brüstung (Mauer, je Segment eine Laterne), Lücke am Weg
        if (abs(d - (SHOULDER_R - 0.5)) <= 0.6 && hy <= 2) {
            var dp = ang - pathEndPhi
            while (dp > PI) dp -= 2 * PI
            while (dp < -PI) dp += 2 * PI
            if (abs(dp) < 0.32) return -1
            val deg = Math.toDegrees(ang + PI).toInt()
            if (hy == 1) return BRICKWALL
            if (hy == 2 && Math.floorMod(deg, 30) == 0) return LANTERN
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
        return when {
            abs(d - 14.0) < 0.55 || abs(d - 26.0) < 0.55 -> CALCITE
            abs(d - 20.0) < 0.7 -> LBLUE
            d in 14.0..26.0 && abs(sin(ang * 6.0)) < 0.05 -> LBLUE
            else -> -1
        }
    }

    // --- Drachenhorst (seit 1.8.0) ----------------------------------------------------------------------------------

    fun aerieDist(x: Int, z: Int): Double = hypot(x + 0.5 - (ArenaLayout.AERIE.x + 0.5), z + 0.5 - (ArenaLayout.AERIE.z + 0.5))

    /** Felsrand des Horsts über (x, z) oder [Int.MIN_VALUE]: steil innen, flacher außen, Öffnung nach Osten. */
    fun aerieRim(x: Int, z: Int): Int {
        val d = aerieDist(x, z)
        val r0 = ArenaLayout.AERIE_R.toDouble()
        val r1 = ArenaLayout.AERIE_RIM.toDouble()
        if (d <= r0 + 0.5 || d >= r1) return Int.MIN_VALUE
        val ang = atan2(z + 0.5 - (ArenaLayout.AERIE.z + 0.5), x + 0.5 - (ArenaLayout.AERIE.x + 0.5))
        var rimH = 18.0 + 22.0 * ridged(x, z, 11, 721) + 6.0 * (vnoise(x, z, 4, 723) - 0.5)
        // Öffnung nach Osten (Einflug) und eine schmale Scharte im Süden
        if (abs(ang) < 0.26) rimH *= 0.08 + 0.92 * smooth(0.14, 0.26, abs(ang))
        if (abs(ang - PI / 2) < 0.12) rimH *= 0.3 + 0.7 * smooth(0.05, 0.12, abs(ang - PI / 2))
        val h = rimH * smooth(r0 + 0.5, r0 + 6.0, d) * (1.0 - smooth(r0 + 10.0, r1, d))
        return if (h < 1.0) Int.MIN_VALUE else floor(h).toInt()
    }

    /** Boden im Horst (y −1): Runenkreise, Strahlen und Adern; −1 = außerhalb. */
    fun aerieFloor(x: Int, z: Int): Int {
        val d = aerieDist(x, z)
        if (d > ArenaLayout.AERIE_R + 0.5) return -1
        val ang = atan2(z + 0.5 - (ArenaLayout.AERIE.z + 0.5), x + 0.5 - (ArenaLayout.AERIE.x + 0.5))
        val vein = abs(vnoise(x, z, 5, 731) - 0.5) < 0.04
        return when {
            d > ArenaLayout.AERIE_R - 1.2 -> LBLUE
            d < 3.0 -> LBLUE
            abs(d - 12.0) < 0.6 || abs(d - 21.0) < 0.6 || abs(d - 40.0) < 0.5 -> CALCITE
            d in 12.0..21.0 && abs(sin(ang * 4.0)) < 0.06 -> LBLUE
            vein -> WHITE
            else -> SNOW
        }
    }

    /** Gebeine eines alten Drachen im Norden des Horsts (Rippen über dem Boden, Wirbel im Rand). */
    private val bones: HashMap<Long, Int> by lazy { HashMap<Long, Int>().also { buildBones(it) } }

    private fun bkey(x: Int, y: Int, z: Int): Long =
        ((x.toLong() and 0x1FFFFFL) shl 42) or ((y.toLong() and 0x1FFFFFL) shl 21) or (z.toLong() and 0x1FFFFFL)

    private fun buildBones(bones: HashMap<Long, Int>) {
        val cx = ArenaLayout.AERIE.x + 0.5
        val cz = ArenaLayout.AERIE.z + 0.5
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
        // Wirbelsäule im Nordrand (Bogen r 58), dazu fünf Rippenbögen, die sich über den Boden neigen
        val north = -PI / 2 // Winkel atan2(dz, dx) nach Norden
        var a = north - 0.75
        while (a <= north + 0.75) {
            blob(cx + kotlin.math.cos(a) * 58.0, 1.0, cz + sin(a) * 58.0, 1.9)
            blob(cx + kotlin.math.cos(a) * 58.0, 3.2, cz + sin(a) * 58.0, 0.9)
            a += 0.045
        }
        for (k in -2..2) {
            val ra = north + k * 0.27
            var t = 0.0
            while (t <= 1.0) {
                val radial = 58.0 - 26.0 * Math.pow(t, 1.6)
                val hgt = 2.0 + 34.0 * sin(t * PI * 0.55)
                val th = 1.7 - 0.9 * t
                blob(cx + kotlin.math.cos(ra) * radial, hgt, cz + sin(ra) * radial, th)
                t += 0.012
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
            val out = Vec(p.x - ArenaLayout.AERIE.x.toDouble(), 0.0, p.z - ArenaLayout.AERIE.z.toDouble()).normalize()
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
            // Seit 1.6.5 nicht mehr hohl (Crix: „in den Cutscenes sehe ich den Berg von innen“): dickere Haut (12), und
            // entlang der Schnittebene (±14) ganz massiv bis zum Boden, damit die Kluft nach dem Schnitt Fels zeigt.
            val solidBand = abs(x + 0.5 - ArenaLayout.CUT_X) <= 14.0
            val bottom = if (solidBand) -1 else max(-1, min(top - 12, lowest - 1))
            val spire = FarPlan.spireTop(x, z).let { it != Int.MIN_VALUE && it >= FarPlan.massifTop(x, z) }
            for (y in bottom..top) {
                val id = if (!path.isNaN() && y >= top - 1) FarPlan.SNOW else FarPlan.material(x, y, z, top, slope, spire)
                put(y, FarPlan.state(id))
            }
            // Weg: Schneeschichten glätten die Steigung; Laternenpfosten am Außenrand.
            if (!path.isNaN() && FarPlan.bridgeAt(x, z)) {
                // Seit 1.8.0: Holzbrücke über einer Schlucht, Geländer an den Rändern.
                val deck = floor(path).toInt()
                for (y in top + 1..deck + 32) {
                    pos.set(ax, origin.y + y, az)
                    if (!chunk.getBlockState(pos).isAir) world.setBlockState(pos, Blocks.AIR.defaultState, ArenaBuilder.FLAGS)
                }
                put(deck, FarPlan.state(FarPlan.PLANKS))
                if (FarPlan.bridgeEdge(x, z)) put(deck + 1, FarPlan.state(FarPlan.FENCE))
            } else if (!path.isNaN()) {
                // Seit 1.8.0: Tunnel durch die Nebengipfel – Fels über dem Weg, Laternen an der Decke.
                val roof = FarPlan.tunnelRoof(x, z)
                if (roof != Int.MIN_VALUE) {
                    for (y in top + 6..roof) put(y, FarPlan.state(FarPlan.material(x, y, z, roof, 6, false)))
                    if (FarPlan.tunnelLamp(x, z)) put(top + 5, FarPlan.state(FarPlan.HANG))
                }
                val post = FarPlan.postAt(x, z)
                if (post != Int.MIN_VALUE) {
                    put(top + 1, FarPlan.state(FarPlan.WALL))
                    put(top + 2, FarPlan.state(FarPlan.WALL))
                    put(top + 3, FarPlan.state(FarPlan.LANTERN))
                } else {
                    // Kletterstelle: Ranken an der Felsstufe (bis zur nächsten Weghöhe).
                    val face = FarPlan.climbFace(x, z)
                    if (face != null) {
                        // Nur dort, wo wirklich eine Felsstufe steht: Ranken bis zur Oberkante der Nachbarsäule.
                        val wallTop = FarPlan.top(x + face.offsetX, z + face.offsetZ)
                        if (wallTop >= top + 2) {
                            val vine = Blocks.VINE.defaultState.with(net.minecraft.block.VineBlock.getFacingProperty(face), true)
                            for (h in 1..minOf(FarPlan.CLIMB_H.toInt(), wallTop - top)) put(top + h, vine)
                        }
                    }
                }
            }
        }
        // Schluchten (seit 1.8.0): Fels aus älteren Fassungen über der neuen Oberkante entfernen.
        if (FarPlan.notchDepth(x, z) > 0.5 && FarPlan.pathAt(x, z).isNaN()) {
            for (y in maxOf(top, -1) + 1..maxOf(top, -1) + 32) {
                pos.set(ax, origin.y + y, az)
                if (!chunk.getBlockState(pos).isAir) {
                    world.setBlockState(pos, Blocks.AIR.defaultState, ArenaBuilder.FLAGS)
                    sets++
                }
            }
        }
        // Gipfelschulter (seit 1.8.0): Runenboden, Krone aus Eisspitzen, Brüstung.
        if (abs(x - ArenaLayout.SPIRE.x) <= FarPlan.SHOULDER_R + 15 && abs(z - ArenaLayout.SPIRE.z) <= FarPlan.SHOULDER_R + 15) {
            if (top == FarPlan.SHOULDER_Y.toInt()) FarPlan.summitFloor(x, z).let { if (it >= 0) put(top, FarPlan.state(it)) }
            for (y in FarPlan.SHOULDER_Y.toInt() + 1..FarPlan.SHOULDER_Y.toInt() + 36) {
                val id = FarPlan.crown(x, y, z)
                if (id >= 0) put(y, FarPlan.state(id))
            }
        }
        // Drachenhorst (seit 1.8.0): Boden mit Runen, Gebeine, Kristallsäulen, Felspfeiler.
        if (abs(x - ArenaLayout.AERIE.x) <= ArenaLayout.AERIE_RIM + 2 && abs(z - ArenaLayout.AERIE.z) <= ArenaLayout.AERIE_RIM + 2) {
            FarPlan.aerieFloor(x, z).let { if (it >= 0) put(-1, FarPlan.state(it)) }
            for (y in 0..44) {
                val id = FarPlan.aerie(x, y, z)
                if (id >= 0) put(y, FarPlan.state(id))
            }
        }
        // Eisspitzen und Eisflecken der endlosen Weite (Biom-Merkmale) auf den Kampfflächen und am Weg entfernen.
        if (FarPlan.keepClear(x, z)) {
            val from = if (FarPlan.inCore(x, z)) ArenaPlan.Y_MAX + 1 else maxOf(top, -1) + 1
            for (y in from..from + 56) {
                pos.set(ax, origin.y + y, az)
                val st = chunk.getBlockState(pos)
                if (st.isOf(Blocks.PACKED_ICE) || st.isOf(Blocks.ICE) || st.isOf(Blocks.BLUE_ICE)) {
                    world.setBlockState(pos, Blocks.AIR.defaultState, ArenaBuilder.FLAGS)
                    sets++
                }
            }
            if (FarPlan.inCore(x, z)) {
                // Im Kern: alles, was der Bauplan als Luft vorsieht, aber Eis ist (Eisspitzen), wegnehmen.
                for (i in core.indices) {
                    if (core[i] != ArenaPlan.AIR) continue
                    val y = ArenaPlan.Y_MIN + i
                    if (y < 0) continue
                    pos.set(ax, origin.y + y, az)
                    val st = chunk.getBlockState(pos)
                    if (st.isOf(Blocks.PACKED_ICE) || st.isOf(Blocks.ICE)) {
                        world.setBlockState(pos, Blocks.AIR.defaultState, ArenaBuilder.FLAGS)
                        sets++
                    }
                }
            } else if (top <= -1) {
                // Eisflecken auf der Lauffläche wieder zu Schnee
                pos.set(ax, origin.y - 1, az)
                if (chunk.getBlockState(pos).isOf(Blocks.PACKED_ICE)) put(-1, Blocks.SNOW_BLOCK.defaultState)
            }
        }
        // Eisspitzen der endlosen Weite
        FarPlan.iceSpike(x, z)?.let { sp ->
            if (top <= -1) for (y in 0..sp[0]) put(y, FarPlan.state(if (sp[1] == 1 && y < sp[0] - 1) FarPlan.BLUE else FarPlan.PACKED))
        }
        // Arena von Abschnitt III: zugefrorener See, Kristalle, Pfeilerkreis, große Klinge, Zuschauerhügel.
        if (abs(x - ArenaLayout.VAULT.x) <= ArenaLayout.VAULT_R + 40 && abs(z - ArenaLayout.VAULT.z) <= ArenaLayout.VAULT_R + 40) {
            val floorId = FarPlan.fieldFloor(x, z)
            if (floorId >= 0) put(-1, FarPlan.state(floorId))
            for (y in ArenaLayout.VAULT.y - 1..ArenaLayout.VAULT.y + 28) {
                val id = FarPlan.balcony(x, y, z)
                if (id >= 0) put(y, FarPlan.state(id))
            }
        }
        return sets
    }
}
