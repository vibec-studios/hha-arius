package dev.henny.hha_arius.boss

import net.minecraft.util.math.Vec3d
import kotlin.math.acos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin

/**
 * Feste Flugbahnen für Solvane (seit 1.11.0). Jede Bahn wird vorab aus Wegpunkten berechnet und hält einen
 * Mindest-Kurvenradius ein: Liegt der nächste Punkt innerhalb ihres Wendekreises, fliegt sie erst geradeaus weiter und
 * dreht dann in **einem** Bogen ein. Sie kreist also nie um einen Punkt und dreht sich nicht auf der Stelle.
 */
object DragonRail {

    /** Kleinster Kurvenradius in Blöcken; der Körper ist deutlich länger, enger würde er sich selbst kreuzen. */
    const val MIN_RADIUS = 22.0

    const val STEP = 0.5

    /** Ein Wegpunkt gilt in diesem Abstand als erreicht. */
    private const val CAPTURE = 2.0

    /** Dichte Bahn (Punktabstand [STEP]) von [start] mit Flugrichtung [startDir] durch alle [waypoints]. */
    fun path(start: Vec3d, startDir: Vec3d, waypoints: List<Vec3d>, radius: Double = MIN_RADIUS): List<Vec3d> {
        val out = ArrayList<Vec3d>()
        var p = start
        var h = startDir.takeIf { it.lengthSquared() > 1e-6 }?.normalize()
            ?: waypoints.firstOrNull()?.subtract(start)?.takeIf { it.lengthSquared() > 1e-6 }?.normalize()
            ?: Vec3d(1.0, 0.0, 0.0)
        out.add(p)
        val maxTurn = STEP / radius
        for (wp in waypoints) {
            var guard = 0
            while (guard++ < 8000) {
                val to = wp.subtract(p)
                val d = to.length()
                if (d <= CAPTURE) break
                val want = to.multiply(1.0 / d)
                val angle = angleBetween(h, want)
                // Innerhalb des Wendekreises: geradeaus, bis der Punkt mit einem Bogen erreichbar ist.
                if (d >= 1.96 * radius * sin(angle.coerceAtMost(Math.PI / 2))) h = turn(h, want, angle, maxTurn)
                p = p.add(h.multiply(STEP))
                out.add(p)
            }
        }
        // Die letzten Blöcke genau auf das Ziel.
        waypoints.lastOrNull()?.let { end ->
            val n = (p.distanceTo(end) / STEP).toInt()
            for (i in 1..n) out.add(p.lerp(end, i.toDouble() / n))
        }
        return out
    }

    /** Fliegt [d] mit [speed] Blöcken je Tick über die Bahn durch [waypoints]; die Blickrichtung folgt der Bahn. */
    fun fly(f: EirholmFight, d: NivarisEntity, waypoints: List<Vec3d>, speed: Double = 1.2, radius: Double = MIN_RADIUS): Script = script {
        val pts = path(d.entityPos, f.facing(d), waypoints, radius)
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
