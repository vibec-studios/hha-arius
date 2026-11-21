package dev.henny.hha_arius.boss

import dev.henny.hha_arius.arena.FarPlan
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
    ): List<Vec3d> {
        if (pts.size < 2) return pts
        val landFrom = (pts.size - landingTail.coerceAtLeast(0)).coerceAtLeast(1)
        val minYs = DoubleArray(pts.size) { pts[it].y }
        for (i in 0 until landFrom) {
            val q = pts[i]
            val rx = floor(q.x).toInt() - origin.x
            val rz = floor(q.z).toInt() - origin.z
            var maxTop = Int.MIN_VALUE
            if (rx in (FarPlan.X_MIN + 10)..(FarPlan.X_MAX - 10) && rz in (FarPlan.Z_MIN + 10)..(FarPlan.Z_MAX - 10)) {
                for (dx in intArrayOf(-8, 0, 8)) for (dz in intArrayOf(-8, 0, 8)) {
                    maxTop = max(maxTop, FarPlan.top(rx + dx, rz + dz))
                }
            }
            val terrainMin = if (maxTop > Int.MIN_VALUE) origin.y + maxTop + 1.0 + clearance else floorY + 2.5
            minYs[i] = max(pts[i].y, max(floorY + 2.5, terrainMin))
        }
        // Sanfte Steigungsbegrenzung (vorwärts & rückwärts), damit Aufstiege über Klippen weich beginnen.
        val maxStepY = STEP * 0.30
        val ys = minYs.copyOf()
        for (i in 1 until landFrom) ys[i] = max(ys[i], ys[i - 1] - maxStepY)
        for (i in landFrom - 2 downTo 0) ys[i] = max(ys[i], ys[i + 1] - maxStepY)
        // Gleitender Mittelwert (nie unter das Gelände-Minimum fallen).
        val smooth = DoubleArray(pts.size) { ys[it] }
        for (i in 0 until landFrom) {
            var acc = 0.0
            var cnt = 0
            for (k in max(0, i - 18)..min(landFrom - 1, i + 18)) { acc += ys[k]; cnt++ }
            smooth[i] = max(minYs[i], acc / cnt)
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
        return List(pts.size) { i -> Vec3d(pts[i].x, smooth[i], pts[i].z) }
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
