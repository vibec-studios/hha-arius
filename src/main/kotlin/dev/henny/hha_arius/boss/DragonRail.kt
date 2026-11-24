package dev.henny.hha_arius.boss

import dev.henny.hha_arius.arena.ArenaLayout
import dev.henny.hha_arius.arena.ArenaPlan
import dev.henny.hha_arius.arena.FarPlan
import dev.henny.hha_arius.arena.Highland
import dev.henny.hha_arius.arena.HuntRoute
import dev.henny.hha_arius.arena.RideLandmarks
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import kotlin.math.acos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Feste Flugbahnen für Solvane (seit 1.11.0). Jede Bahn wird vorab aus Wegpunkten berechnet und hält einen
 * Mindest-Kurvenradius ein: Liegt der nächste Punkt innerhalb ihres Wendekreises, fliegt sie erst geradeaus weiter und
 * dreht dann in **einem** Bogen ein (nur beim Endpunkt; Zwischenpunkte im Wendekreis lässt sie aus). Sie kreist also nie um einen Punkt und dreht sich nicht auf der Stelle.
 */
object DragonRail {

    /** Kleinster Kurvenradius in Blöcken; der Körper ist deutlich länger, enger würde er sich selbst kreuzen. */
    const val MIN_RADIUS = 24.0

    const val STEP = 0.5

    /** Kristallsäulen: Höhe samt Abstand über dem Boden und Umkreis, in dem sie zählen. */
    const val PILLAR_TOP = 16.5
    private const val PILLAR_REACH = 8.0

    /** Ein Wegpunkt gilt in diesem Abstand als erreicht. */
    private const val CAPTURE = 2.5

    /** Dichte Bahn (Punktabstand [STEP]) von [start] mit Flugrichtung [startDir] durch alle [waypoints]. */
    fun path(start: Vec3d, startDir: Vec3d, waypoints: List<Vec3d>, radius: Double = MIN_RADIUS): List<Vec3d> =
        path(start, startDir, waypoints, radius, null, 0.0)

    /**
     * Wie oben, bleibt aber im Kreis [keepRadius] um [center]: Fliegt sie auf den Rand zu, dreht sie rechtzeitig nach
     * innen ab (Arenen mit Felswand). Ohne [center] keine Grenze.
     */
    fun path(start: Vec3d, startDir: Vec3d, waypoints: List<Vec3d>, radius: Double, center: Vec3d?, keepRadius: Double): List<Vec3d> {
        val out = ArrayList<Vec3d>()
        var p = start
        var h = startDir.takeIf { it.lengthSquared() > 1e-6 }?.normalize()
            ?: waypoints.firstOrNull()?.subtract(start)?.takeIf { it.lengthSquared() > 1e-6 }?.normalize()
            ?: Vec3d(1.0, 0.0, 0.0)
        // Steile Ausgangsrichtung dämpfen, damit keine Bahn senkrecht in Boden oder Himmel schießt.
        if (kotlin.math.abs(h.y) > 0.42) h = Vec3d(h.x, h.y.coerceIn(-0.42, 0.42), h.z).normalize()
        out.add(p)
        val maxTurn = STEP / radius
        val turnBuffer = radius * 1.18
        for ((k, wp) in waypoints.withIndex()) {
            // Ein Zwischenpunkt im Wendekreis wird ausgelassen: Dafür müsste sie eine Extraschleife fliegen.
            if (k < waypoints.lastIndex) {
                val to = wp.subtract(p)
                val d = to.length()
                if (d <= CAPTURE || d < 1.96 * radius * sin(angleBetween(h, to.multiply(1.0 / d)).coerceAtMost(Math.PI / 2))) continue
            }
            var guard = 0
            var turned = 0.0
            var bestD = Double.MAX_VALUE
            var receding = 0
            while (guard++ < 1400) {
                val to = wp.subtract(p)
                val d = to.length()
                if (d <= CAPTURE) break
                if (d < bestD - 0.15) {
                    bestD = d
                    receding = 0
                } else {
                    receding++
                }
                // Verhindert 360°-Schleifen, wenn ein Ziel ungünstig nahe am Wendekreis liegt.
                if (turned > Math.PI * 1.35 && bestD < radius * 0.95) break
                if (receding > 22 && bestD < CAPTURE * 3.5) break
                var want = to.multiply(1.0 / d)
                if (kotlin.math.abs(want.y) > 0.42) want = Vec3d(want.x, want.y.coerceIn(-0.42, 0.42), want.z).normalize()
                var forced = false
                if (center != null && keepRadius > 4.0) {
                    val ox = p.x - center.x
                    val oz = p.z - center.z
                    val r = sqrt(ox * ox + oz * oz)
                    // Rechtzeitig vor der Felswand (mindestens ein voller Wendekreis Puffer) nach innen eindrehen.
                    if (r > keepRadius - turnBuffer && r > 1e-3 && h.x * ox + h.z * oz > -0.15 * r) {
                        val radialOut = Vec3d(ox / r, 0.0, oz / r)
                        val tang1 = Vec3d(-oz / r, 0.0, ox / r)
                        val tang = if (tang1.dotProduct(h) >= 0.0) tang1 else tang1.negate()
                        val pull = ((r - (keepRadius - turnBuffer)) / turnBuffer).coerceIn(0.35, 1.0)
                        want = tang.multiply(1.0 - pull * 0.55).add(radialOut.multiply(-pull)).add(0.0, want.y.coerceIn(-0.22, 0.22), 0.0).normalize()
                        forced = true
                    }
                }
                val angle = angleBetween(h, want)
                val prevH = h
                // Innerhalb des Wendekreises: geradeaus, bis der Punkt mit einem Bogen erreichbar ist.
                if (forced || d >= 1.96 * radius * sin(angle.coerceAtMost(Math.PI / 2))) {
                    h = turn(h, want, angle, maxTurn)
                    turned += angleBetween(prevH, h)
                }
                if (kotlin.math.abs(h.y) > 0.45) h = Vec3d(h.x, h.y.coerceIn(-0.45, 0.45), h.z).normalize()
                var nextP = p.add(h.multiply(STEP))
                // Harte Wand-Grenze: nie in die Arenawand hinausdriften.
                if (center != null && keepRadius > 4.0) {
                    val nx = nextP.x - center.x
                    val nz = nextP.z - center.z
                    val nr = sqrt(nx * nx + nz * nz)
                    if (nr > keepRadius) {
                        nextP = Vec3d(center.x + nx / nr * keepRadius, nextP.y, center.z + nz / nr * keepRadius)
                        val inward = Vec3d(-nx / nr, 0.0, -nz / nr)
                        val dotOut = h.x * (nx / nr) + h.z * (nz / nr)
                        if (dotOut > 0.0) {
                            h = Vec3d(h.x - (nx / nr) * dotOut, h.y, h.z - (nz / nr) * dotOut).add(inward.multiply(0.12)).normalize()
                        }
                    }
                }
                p = nextP
                out.add(p)
            }
        }
        // Die letzten Blöcke genau auf das Ziel.
        // Liegt das Ziel seitlich oder hinter ihr, endet die Bahn hier (höchstens CAPTURE daneben), statt zurückzuhaken.
        waypoints.lastOrNull()?.let { end ->
            val gap = end.subtract(p)
            val dist = gap.length()
            if (dist > 1e-3 && h.dotProduct(gap.multiply(1.0 / dist)) > 0.94) {
                val n = (dist / STEP).toInt()
                for (i in 1..n) out.add(p.lerp(end, i.toDouble() / n))
                if (out.last().squaredDistanceTo(end) > 1e-6) out.add(end)
            }
        }
        return out
    }

    /**
     * Hebt eine vorberechnete Bahn sanft über Gelände und Felswände (Umfeld ±8 Blöcke gegen [FarPlan.top] und
     * Mindesthöhe [floorY] + 2,5), begrenzt Steigung/Gefälle auf höchstens ~18° und hält die letzten [landingTail]
     * Punkte frei für den Landeanflug.
     */
    fun clearTerrain(
        pts: List<Vec3d>, origin: BlockPos, floorY: Double, clearance: Double = 5.0, landingTail: Int = 0,
        structures: Boolean = false, takeoffHead: Int = 0, structureClearance: Double = clearance,
    ): List<Vec3d> {
        if (pts.size < 2) return pts
        val startFrom = takeoffHead.coerceIn(0, pts.size - 2)
        val landFrom = (pts.size - landingTail.coerceAtLeast(0)).coerceAtLeast(startFrom + 1)
        val minYs = DoubleArray(pts.size) { pts[it].y }
        val col = if (structures) ByteArray(ArenaPlan.NY) else null
        for (i in startFrom until landFrom) {
            val q = pts[i]
            val rx = floor(q.x).toInt() - origin.x
            val rz = floor(q.z).toInt() - origin.z
            var maxTop = Int.MIN_VALUE
            // Bauwerke (Laternen, Tempel, Eisspitzen …) getrennt vom Gelände, mit eigenem Abstand [structureClearance].
            var structTop = Int.MIN_VALUE
            if (rx in (FarPlan.X_MIN + 10)..(FarPlan.X_MAX - 10) && rz in (FarPlan.Z_MIN + 10)..(FarPlan.Z_MAX - 10)) {
                // Im Höhlengang der Jagd ist der Fels darüber die Decke: nicht darüber heben (sonst fliegt sie durch den Berg).
                if (tunnel(rx, rz)) {
                    minYs[i] = max(pts[i].y, floorY + 2.5)
                    continue
                }
                for (dx in intArrayOf(-8, 0, 8)) for (dz in intArrayOf(-8, 0, 8)) {
                    if (!tunnel(rx + dx, rz + dz)) maxTop = max(maxTop, FarPlan.top(rx + dx, rz + dz))
                }
                if (structures) {
                    val lmTop = RideLandmarks.topNear(rx, rz, 6)
                    if (lmTop > structTop) structTop = lmTop
                    for (dx in intArrayOf(-5, 0, 5)) for (dz in intArrayOf(-5, 0, 5)) {
                        val sx = rx + dx
                        val sz = rz + dz
                        FarPlan.iceSpike(sx, sz)?.let { sp -> if (sp[0] > structTop) structTop = sp[0] }
                        if (col != null && FarPlan.inCore(sx, sz)) {
                            ArenaPlan.column(sx, sz, col)
                            for (y in ArenaPlan.Y_MAX downTo 0) {
                                if (col[y - ArenaPlan.Y_MIN] != ArenaPlan.AIR) {
                                    if (y > structTop) structTop = y
                                    break
                                }
                            }
                        }
                    }
                    for (lm in Highland.landmarks) {
                        if (kotlin.math.hypot((rx - lm.x).toDouble(), (rz - lm.z).toDouble()) <= lm.r + 6.0) {
                            val h = when (lm.type) {
                                Highland.BLADE -> lm.base + 27
                                Highland.TOWER -> lm.base + 18
                                else -> lm.base + 8
                            }
                            if (h > structTop) structTop = h
                        }
                    }
                }
            }
            var terrainMin = if (maxTop > Int.MIN_VALUE) origin.y + maxTop + 1.0 + clearance else floorY + 2.5
            if (structTop > Int.MIN_VALUE) terrainMin = max(terrainMin, origin.y + structTop + 1.0 + structureClearance)
            minYs[i] = max(pts[i].y, max(floorY + 2.5, terrainMin))
            if (overPillar(rx, rz)) minYs[i] = max(minYs[i], floorY + PILLAR_TOP)
        }
        // Sanfte Steigungsbegrenzung (vorwärts & rückwärts), damit Aufstiege über Klippen weich beginnen.
        val maxStepY = STEP * 0.30
        val ys = minYs.copyOf()
        for (i in startFrom + 1 until landFrom) ys[i] = max(ys[i], ys[i - 1] - maxStepY)
        for (i in landFrom - 2 downTo startFrom) ys[i] = max(ys[i], ys[i + 1] - maxStepY)
        // Gleitender Mittelwert (nie unter das Gelände-Minimum fallen).
        val smooth = DoubleArray(pts.size) { ys[it] }
        for (i in startFrom until landFrom) {
            var acc = 0.0
            var cnt = 0
            for (k in max(startFrom, i - 18)..min(landFrom - 1, i + 18)) { acc += ys[k]; cnt++ }
            smooth[i] = max(minYs[i], acc / cnt)
        }
        // Weicher Übergang aus der Startstrecke.
        if (startFrom > 0) {
            val blend = min(14, startFrom)
            val lift = smooth[startFrom] - pts[startFrom].y
            if (lift > 0.05) {
                for (j in 0 until blend) {
                    val u = 1.0 - (j + 1.0) / (blend + 1.0)
                    val s = u * u * (3.0 - 2.0 * u)
                    smooth[startFrom - 1 - j] = pts[startFrom - 1 - j].y + lift * s
                }
            }
        }
        // Weicher Übergang in den Landeanflug.
        if (landFrom < pts.size) {
            val blend = min(14, pts.size - landFrom)
            val lift = smooth[landFrom - 1] - pts[landFrom - 1].y
            if (lift > 0.05) {
                for (j in 0 until blend) {
                    val u = 1.0 - (j + 1.0) / (blend + 1.0)
                    val s = u * u * (3.0 - 2.0 * u)
                    smooth[landFrom + j] = pts[landFrom + j].y + lift * s
                }
            }
        }
        // Auch im Landeanflug nie unter die Lauffläche (eine steile Einflugrichtung schoss sonst bis zu 3 Blöcke in den Boden).
        return List(pts.size) { i -> Vec3d(pts[i].x, max(smooth[i], floorY), pts[i].z) }
    }

    /** Route from the plateau charge to the low pass on the north side of the Aerie. */
    fun carryRail(
        f: EirholmFight, start: Vec3d, hit: Vec3d, pickup: Vec3d, drop: Vec3d, pass: Vec3d, aerie: Vec3d,
    ): List<Vec3d> {
        val route = spline(listOf(
            start,
            start.lerp(hit, 0.50),
            hit,
            hit.add(-7.0, 3.5, -5.0),
            f.abs(Vec3d(-18.0, 7.0, -30.0)),
            f.abs(Vec3d(-24.5, 11.5, -46.0)),
            pickup,
            f.abs(Vec3d(-41.0, 28.0, -76.0)),
            f.abs(Vec3d(-95.0, 40.0, -77.0)),
            f.abs(Vec3d(-185.0, 40.0, -66.0)),
            aerie.add(117.0, 38.0, -12.0),
            aerie.add(93.0, 35.0, -13.0),
            drop,
            aerie.add(34.0, 22.0, -48.0),
            pass,
            aerie.add(-30.0, 18.0, -69.0),
            aerie.add(-58.0, 15.0, -48.0),
            aerie.add(-64.0, 12.0, -18.0),
            aerie.add(-58.0, 8.0, -4.0),
            aerie.add(-38.0, 4.0, 0.0),
            aerie.add(-14.0, 1.0, 0.0),
            aerie,
        ))
        val cleared = clearTerrain(
            route, f.origin, f.floorY, clearance = 8.0,
            takeoffHead = (57.0 / STEP).toInt(), landingTail = (201.0 / STEP).toInt(),
            structures = true, structureClearance = 5.0,
        )
        // Build a genuinely rounded C2 upper envelope above every wall/spike clearance point so the spine never forms
        // sharp V-kinks or bobs up and down over the Aerie rim wall or outside ice spires.
        val minY = DoubleArray(cleared.size) { cleared[it].y }
        val heights = minY.copyOf()
        val grade = 0.22 * STEP
        for (i in 1 until heights.size) heights[i] = max(heights[i], heights[i - 1] - grade)
        for (i in heights.lastIndex - 1 downTo 0) heights[i] = max(heights[i], heights[i + 1] - grade)
        fun boxSmooth(src: DoubleArray, radius: Int): DoubleArray = DoubleArray(src.size) { i ->
            val from = (i - radius).coerceAtLeast(0)
            val to = (i + radius).coerceAtMost(src.lastIndex)
            var sum = 0.0
            for (k in from..to) sum += src[k]
            sum / (to - from + 1)
        }
        var env = heights
        repeat(3) { env = boxSmooth(env, 18) }
        // Cover any smoothed-down peak with a broad rounded lift instead of pointwise max() (which leaves sharp corners).
        val deficit = DoubleArray(env.size) { i -> max(0.0, minY[i] - env[i]) }
        val lift = boxSmooth(boxSmooth(deficit, 14), 14)
        for (i in env.indices) env[i] += lift[i] * 1.45
        repeat(2) { env = boxSmooth(env, 12) }
        // Keep exact ground/takeoff height at both ends of the rail.
        val pinHead = (40.0 / STEP).toInt().coerceAtMost(env.size / 4)
        for (i in 0 until pinHead) {
            val u = i.toDouble() / pinHead
            val s = u * u * (3.0 - 2.0 * u)
            env[i] = minY[i] + (env[i] - minY[i]) * s
        }
        val pinTail = (48.0 / STEP).toInt().coerceAtMost(env.size / 4)
        for (j in 0 until pinTail) {
            val i = env.lastIndex - j
            val u = j.toDouble() / pinTail
            val s = u * u * (3.0 - 2.0 * u)
            env[i] = minY[i] + (env[i] - minY[i]) * s
        }
        val smooth = cleared.mapIndexed { i, p -> Vec3d(p.x, env[i], p.z) }
        return evenSpacing(smooth).toMutableList().apply {
            this[0] = start
            this[lastIndex] = aerie
        }
    }

    /** The resting tail runs back into the same western clearing used by the arrival. */
    fun carryRunIn(f: EirholmFight, start: Vec3d): List<Vec3d> {
        val trail = spline(listOf(
            f.abs(Vec3d(-66.0, 0.0, 74.0)),
            f.abs(Vec3d(-48.0, 0.0, 48.0)),
            f.abs(Vec3d(-27.0, 0.0, 29.0)),
            start.add(-8.0, 0.0, 14.0),
            start.add(0.0, 0.0, 6.0),
            start,
        )).toMutableList()
        // The sampled spline stops on its last full step. Close the gap to the
        // charge rail so each vertebra can cross distance zero continuously.
        if (trail.last().squaredDistanceTo(start) > 1e-6) trail.add(start)
        val heights = trail.map { q ->
            val rx = floor(q.x).toInt() - f.origin.x
            val rz = floor(q.z).toInt() - f.origin.z
            val top = FarPlan.top(rx, rz)
            max(start.y, f.origin.y + top + 1.0)
        }
        return trail.mapIndexed { i, q ->
            if (i == trail.lastIndex) return@mapIndexed start
            val spread = 8
            val local = (i - spread).coerceAtLeast(0)..(i + spread).coerceAtMost(trail.lastIndex)
            val y = local.maxOf { j -> heights[j] - kotlin.math.abs(i - j) * STEP * 0.18 }
            Vec3d(q.x, y, q.z)
        }
    }

    /** Every vertebra follows the same arc-length parametrization, all the way to the tail. */
    fun carrySpine(rail: List<Vec3d>, runIn: List<Vec3d>, distance: Double, settle: Double): List<Vec3d> {
        val end = length(rail)
        val shoulder = at(rail, distance).add(0.0, NivarisShape.SPINE_UP, 0.0)
        val charge = at(rail, 9.0).subtract(rail.first())
        val groundForward = Vec3d(charge.x, 0.0, charge.z).normalize()
        val flight = ((distance - 18.0) / 18.0).coerceIn(0.0, 1.0)
        val blend = flight * flight * (3.0 - 2.0 * flight)
        val finalTangent = rail.last().subtract(at(rail, end - 3.0)).normalize()
        val landY = rail.last().y + NivarisShape.SPINE_UP
        return List(NivarisShape.SEGMENTS + 1) { i ->
            val offset = (3 - i) * NivarisShape.SEGMENT
            val s = distance + offset
            val p = when {
                i < 3 -> {
                    val ground = shoulder.add(groundForward.multiply(offset)).add(0.0, -0.5 * (3 - i) / 3, 0.0)
                    val ahead = if (s <= end) at(rail, s) else rail.last().add(finalTangent.multiply(s - end))
                    ground.lerp(ahead.add(0.0, NivarisShape.SPINE_UP + 0.5 * (3 - i) / 3, 0.0), blend)
                }
                s < 0.0 -> at(runIn, length(runIn) + s).add(0.0, NivarisShape.SPINE_UP, 0.0)
                else -> at(rail, minOf(s, end)).add(0.0, NivarisShape.SPINE_UP, 0.0)
            }
            Vec3d(p.x, p.y + (landY - p.y) * settle, p.z)
        }
    }

    /**
     * Glatte Bahn durch [ctrl] (zentripetaler Catmull-Rom, gleichmäßig alle [STEP] Blöcke): keine Schleifen, keine Spitzen
     * an den Stützpunkten. Für lange, von Hand gelegte Routen wie den Ritt.
     */
    fun spline(ctrl: List<Vec3d>): List<Vec3d> {
        if (ctrl.size < 2) return ctrl
        val ext = ArrayList<Vec3d>(ctrl.size + 2)
        ext += ctrl[0].multiply(2.0).subtract(ctrl[1])
        ext += ctrl
        ext += ctrl.last().multiply(2.0).subtract(ctrl[ctrl.size - 2])
        val fine = ArrayList<Vec3d>()
        for (i in 1 until ext.size - 2) {
            val p0 = ext[i - 1]
            val p1 = ext[i]
            val p2 = ext[i + 1]
            val p3 = ext[i + 2]
            val t1 = sqrt(p0.distanceTo(p1)).coerceAtLeast(1e-3)
            val t2 = t1 + sqrt(p1.distanceTo(p2)).coerceAtLeast(1e-3)
            val t3 = t2 + sqrt(p2.distanceTo(p3)).coerceAtLeast(1e-3)
            val n = (p1.distanceTo(p2) * 4.0).toInt().coerceAtLeast(8)
            for (k in 0 until n) {
                val t = t1 + (t2 - t1) * k / n
                val a1 = p0.lerp(p1, t / t1)
                val a2 = p1.lerp(p2, (t - t1) / (t2 - t1))
                val a3 = p2.lerp(p3, (t - t2) / (t3 - t2))
                val b1 = a1.lerp(a2, t / t2)
                val b2 = a2.lerp(a3, (t - t1) / (t3 - t1))
                fine += b1.lerp(b2, (t - t1) / (t2 - t1))
            }
        }
        fine += ctrl.last()
        val out = ArrayList<Vec3d>()
        out += fine[0]
        var acc = 0.0
        var next = STEP
        for (i in 1 until fine.size) {
            val a = fine[i - 1]
            val b = fine[i]
            val len = a.distanceTo(b)
            while (acc + len >= next && len > 1e-9) {
                out += a.lerp(b, (next - acc) / len)
                next += STEP
            }
            acc += len
        }
        return out
    }

    /**
     * Gleiche Bogenlänge zwischen allen Punkten ([STEP] im Raum, nicht nur waagerecht). [clearTerrain] hebt Punkte nur an:
     * Ein steiler Anstieg über Bauwerke lag danach auf wenigen Punkten, und weil [at] nach Punktnummer läuft, schoss sie
     * dort mit 3 bis 4 Blöcken pro Tick hoch. Nach dem Umrechnen fliegt sie überall genau mit dem gewollten Tempo.
     */
    fun evenSpacing(pts: List<Vec3d>): List<Vec3d> {
        if (pts.size < 2) return pts
        val out = ArrayList<Vec3d>(pts.size + 16)
        out += pts[0]
        var acc = 0.0
        var next = STEP
        for (i in 1 until pts.size) {
            val a = pts[i - 1]
            val b = pts[i]
            val len = a.distanceTo(b)
            while (acc + len >= next && len > 1e-9) {
                out += a.lerp(b, (next - acc) / len)
                next += STEP
            }
            acc += len
        }
        if (out.last().squaredDistanceTo(pts.last()) > 1e-6) out += pts.last()
        return out
    }

    /** Höchste Geländekante (relativ) im Umkreis [reach] um (x, z); Gänge der Jagd zählen nicht als Fels darüber. */
    fun groundAt(x: Double, z: Double, reach: Int): Int {
        val rx = floor(x).toInt()
        val rz = floor(z).toInt()
        var best = Int.MIN_VALUE
        val half = reach / 2
        for (dx in intArrayOf(-reach, -half, 0, half, reach)) for (dz in intArrayOf(-reach, -half, 0, half, reach)) {
            val cx = rx + dx
            val cz = rz + dz
            if (cx !in FarPlan.X_MIN..FarPlan.X_MAX || cz !in FarPlan.Z_MIN..FarPlan.Z_MAX || tunnel(cx, cz)) continue
            best = max(best, FarPlan.top(cx, cz))
        }
        return best
    }

    /** Über einer Kristallsäule des Horsts? Sie steht in keiner Höhenkarte, Solvane fliegt darüber hinweg. */
    fun overPillar(x: Int, z: Int): Boolean {
        if (kotlin.math.hypot((x - ArenaLayout.AERIE.x).toDouble(), (z - ArenaLayout.AERIE.z).toDouble()) > 70.0) return false
        return ArenaLayout.AERIE_PILLARS.any { kotlin.math.hypot((x - it.x).toDouble(), (z - it.z).toDouble()) < PILLAR_REACH }
    }

    /** Liegt die Spalte (relativ) über einem Höhlengang der Jagd? Dort ist Fels Decke, nicht Hindernis. */
    private fun tunnel(x: Int, z: Int): Boolean {
        val h = HuntRoute.hit(x, z)
        return h >= 0 && HuntRoute.kind(HuntRoute.hitIndex(h)) == HuntRoute.TUNNEL
    }

    /** Punkt auf einer Bahn aus [path] bei Bogenlänge [s] (Blöcke, geklemmt). */
    fun at(pts: List<Vec3d>, s: Double): Vec3d {
        if (pts.size < 2) return pts.first()
        val x = (s / STEP).coerceIn(0.0, pts.size - 1.0001)
        val i = x.toInt()
        return pts[i].lerp(pts[i + 1], x - i)
    }

    /** Länge einer Bahn aus [path] in Blöcken. */
    fun length(pts: List<Vec3d>): Double = (pts.size - 1) * STEP

    /**
     * Ein gelenkter Schritt über [step] Blöcke: dreht die Flugrichtung [heading] höchstens so weit zu [want], wie der
     * Radius [radius] erlaubt (für Flüge, die ihr Ziel jeden Tick neu bestimmen).
     */
    fun steer(heading: Vec3d, want: Vec3d, step: Double, radius: Double = MIN_RADIUS): Vec3d {
        val h = heading.normalize()
        val w = want.normalize()
        return turn(h, w, angleBetween(h, w), step / radius)
    }

    /** Richtung, in der sie gerade wirklich fliegt; steht sie, die Blickrichtung. Damit beginnt jede Bahn ohne Knick. */
    fun heading(f: EirholmFight, d: NivarisEntity): Vec3d =
        d.travelDir?.takeIf { f.now - d.travelTick <= 2L } ?: f.facing(d)

    /** Fliegt [d] mit [speed] Blöcken je Tick über die Bahn durch [waypoints]; die Blickrichtung folgt der Bahn. */
    fun fly(f: EirholmFight, d: NivarisEntity, waypoints: List<Vec3d>, speed: Double = 0.9, radius: Double = MIN_RADIUS): Script = script {
        val pts = clearTerrain(path(d.entityPos, heading(f, d), waypoints, radius), f.origin, f.floorY)
        val last = pts.lastIndex
        var s = 0.0
        while (s < last) {
            if (d.isRemoved) return@script
            s = (s + speed / STEP).coerceAtMost(last.toDouble())
            val i = floor(s).toInt().coerceAtMost(last - 1)
            val pos = pts[i].lerp(pts[i + 1], s - i)
            f.place(d, pos)
            f.face(d, pts[(i + 6).coerceAtMost(last)].takeIf { it.squaredDistanceTo(pos) > 0.01 } ?: pos.add(f.facing(d)))
            yield(Unit)
        }
    }

    private fun angleBetween(a: Vec3d, b: Vec3d): Double = acos(a.dotProduct(b).coerceIn(-1.0, 1.0))

    private fun turn(h: Vec3d, want: Vec3d, angle: Double, maxTurn: Double): Vec3d {
        if (angle <= maxTurn) return want
        if (angle > Math.PI - 1e-3) {
            // Genau entgegengesetzt: über die Waagerechte seitlich abdrehen.
            val side = Vec3d(-h.z, 0.0, h.x).takeIf { it.lengthSquared() > 1e-6 }?.normalize() ?: Vec3d(1.0, 0.0, 0.0)
            return h.multiply(kotlin.math.cos(maxTurn)).add(side.multiply(sin(maxTurn))).normalize()
        }
        val s = max(sin(angle), 1e-6)
        return h.multiply(sin(angle - maxTurn) / s).add(want.multiply(sin(maxTurn) / s)).normalize()
    }
}
