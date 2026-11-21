package dev.henny.hha_arius.boss

import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Box
import net.minecraft.util.math.Direction
import net.minecraft.util.math.MathHelper
import net.minecraft.util.math.Vec3d
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Wegfindung für Arius. Er ist ein Darsteller ohne Physik, also plant sie selbst auf dem Blockraster der Arena:
 * * Freie Sicht zum Ziel: direkter Weg.
 * * Sonst A* (acht Richtungen, keine Ecken schneiden, Stufen bis einen Block). Ist das Ziel unerreichbar, führt der Weg
 *   an die nächste erreichbare Stelle, statt dass er auf der Stelle läuft.
 * * Der Weg wird zu wenigen Eckpunkten gestrafft, und er dreht sich mit begrenzter Geschwindigkeit in die Laufrichtung.
 * * Festgelegte Schläge ([step] mit `faceTarget = false`) nehmen keinen Umweg: Sie gleiten an Hindernissen entlang.
 */
internal class AriusNavigation(private val fight: EirholmFight, private val actor: AriusEntity) {

    private var route: List<Vec3d> = emptyList()
    private var next = 0
    private var goal: Vec3d? = null
    private var replanAt = 0L
    private var blocked = 0

    fun step(to: Vec3d, speed: Double, faceTarget: Boolean): Boolean {
        val dest = fight.clampToArena(to, 0.6)
        val start = actor.entityPos
        if (hypot(dest.x - start.x, dest.z - start.z) <= max(speed, 0.05)) {
            walk(start, dest, speed)?.let { fight.place(actor, it) }
            clearRoute()
            return true
        }
        if (!faceTarget) {
            clearRoute()
            val moved = walk(start, dest, speed) ?: slide(start, dest, speed)
            if (moved != null) fight.place(actor, moved)
            return false
        }
        val now = fight.now
        // Eine laufende Route wird zu Ende gegangen: Der Wechsel zwischen Direktweg und Route ließ ihn sonst pendeln.
        if (route.isEmpty()) {
            if (clearLine(start, dest)) return advance(start, dest, speed)
            if (now >= replanAt) plan(start, dest)
        } else if ((goal?.squaredDistanceTo(dest) ?: 99.0) > 4.0 && now >= replanAt) {
            plan(start, dest)
        }
        if (route.isEmpty()) {
            // Kein Weg bekannt: nicht auf der Stelle laufen, nur zum Ziel drehen.
            turn(dest)
            return false
        }
        // Liegt ein späterer Eckpunkt schon frei, wird die Ecke abgekürzt.
        while (next + 1 < route.size && clearLine(start, route[next + 1])) next++
        val point = route[next]
        if (hypot(point.x - start.x, point.z - start.z) <= max(speed, 0.3)) {
            if (next + 1 < route.size) {
                next++
            } else {
                clearRoute()
                replanAt = now + 8
                return if (clearLine(start, dest)) advance(start, dest, speed) else false
            }
        }
        return advance(start, route[next], speed).also { if (blocked >= STUCK) replan(start, dest) }
    }

    private fun advance(start: Vec3d, toward: Vec3d, speed: Double): Boolean {
        val moved = walk(start, toward, speed) ?: slide(start, toward, speed)
        if (moved == null || moved.squaredDistanceTo(start) < 1e-4) {
            blocked++
            turn(toward)
            return false
        }
        blocked = 0
        turn(moved.add(moved.subtract(start).multiply(4.0)))
        fight.place(actor, moved)
        return false
    }

    private fun replan(start: Vec3d, dest: Vec3d) {
        blocked = 0
        plan(start, dest)
    }

    private fun clearRoute() {
        route = emptyList()
        next = 0
        goal = null
        blocked = 0
    }

    /** Dreht ihn mit höchstens [TURN] Grad je Tick; im Stand bleibt der Blick ruhig. */
    private fun turn(at: Vec3d) {
        val dx = at.x - actor.x
        val dz = at.z - actor.z
        if (dx * dx + dz * dz < 1e-4) return
        val want = (Math.toDegrees(atan2(dz, dx)) - 90.0).toFloat()
        val yaw = actor.yaw + MathHelper.wrapDegrees(want - actor.yaw).coerceIn(-TURN, TURN)
        actor.yaw = yaw
        actor.headYaw = yaw
        actor.bodyYaw = yaw
    }

    // --- Laufen -----------------------------------------------------------------------------------------------------------

    private fun groundAt(x: Double, z: Double, refY: Double): Double? {
        val feet = Box(x - 0.18, refY - 1.15, z - 0.18, x + 0.18, refY + 1.01, z + 0.18)
        return fight.world.getBlockCollisions(actor, feet).map { it.boundingBox.maxY }
            .filter { it <= refY + 1.01 }.maxOrNull()
    }

    private fun bodyFree(x: Double, y: Double, z: Double): Boolean {
        val r = actor.width * 0.42
        return !fight.world.getBlockCollisions(actor, Box(x - r, y + 0.02, z - r, x + r, y + actor.height, z + r)).iterator().hasNext()
    }

    /** Ein Schritt mit durchgehender Körperprüfung auch bei schnellen Sätzen; null, wenn er nicht frei ist. */
    private fun walk(from: Vec3d, to: Vec3d, speed: Double): Vec3d? {
        val dx = to.x - from.x
        val dz = to.z - from.z
        val distance = hypot(dx, dz)
        if (distance < 1e-3) return from
        val stepLen = min(distance, speed)
        val samples = ceil(stepLen / 0.3).toInt().coerceAtLeast(1)
        // In der Luft (nach Sprüngen) gibt es keinen Boden unter ihm: dann auf der Laufhöhe der Arena weiter.
        var y = if (groundAt(from.x, from.z, from.y) != null) from.y else fight.floorY
        for (i in 1..samples) {
            val k = i * stepLen / (samples * distance)
            val x = from.x + dx * k
            val z = from.z + dz * k
            val top = groundAt(x, z, y) ?: return null
            if (!bodyFree(x, top, z)) return null
            y = top
        }
        val k = stepLen / distance
        return Vec3d(from.x + dx * k, y, from.z + dz * k)
    }

    /** An einer Wand entlang: nur der freie Anteil der Bewegung. */
    private fun slide(from: Vec3d, to: Vec3d, speed: Double): Vec3d? {
        val dx = to.x - from.x
        val dz = to.z - from.z
        val first = if (abs(dx) >= abs(dz)) Vec3d(to.x, from.y, from.z) else Vec3d(from.x, from.y, to.z)
        val second = if (abs(dx) >= abs(dz)) Vec3d(from.x, from.y, to.z) else Vec3d(to.x, from.y, from.z)
        for (axis in listOf(first, second)) {
            if (hypot(axis.x - from.x, axis.z - from.z) < 0.04) continue
            val moved = walk(from, axis, speed) ?: continue
            if (fight.clampToArena(moved, 0.5).squaredDistanceTo(moved) < 1e-3) return moved
        }
        return null
    }

    /** Durchgehend begehbare Gerade für seinen Körper? */
    private fun clearLine(from: Vec3d, to: Vec3d): Boolean {
        val distance = hypot(to.x - from.x, to.z - from.z)
        if (distance < 0.05) return true
        val samples = ceil(distance / 0.45).toInt().coerceIn(1, 96)
        var y = if (groundAt(from.x, from.z, from.y) != null) from.y else fight.floorY
        for (i in 1..samples) {
            val k = i.toDouble() / samples
            val x = from.x + (to.x - from.x) * k
            val z = from.z + (to.z - from.z) * k
            val top = groundAt(x, z, y) ?: return false
            if (!bodyFree(x, top, z)) return false
            y = top
        }
        return true
    }

    // --- Planen -----------------------------------------------------------------------------------------------------------

    private class Node(val x: Int, val z: Int, val top: Double, val g: Double, val f: Double, val parent: Node?)

    private fun key(x: Int, z: Int): Long = (x.toLong() shl 32) xor (z.toLong() and 0xFFFFFFFFL)

    /** Oberkante der begehbaren Fläche in dieser Säule nahe [refY], mit zwei freien Blöcken darüber. */
    private fun standAt(x: Int, z: Int, refY: Double): Double? {
        val world = fight.world
        val base = floor(refY).toInt()
        val pos = BlockPos.Mutable()
        var best: Double? = null
        for (y in base + 1 downTo base - 2) {
            pos.set(x, y, z)
            val shape = world.getBlockState(pos).getCollisionShape(world, pos)
            if (shape.isEmpty) continue
            val top = y + shape.getMax(Direction.Axis.Y)
            if (top > refY + 1.01 || top < refY - 1.6) continue
            val head = floor(top + 0.01).toInt()
            var free = true
            for (h in head..head + 1) {
                pos.set(x, h, z)
                if (!world.getBlockState(pos).getCollisionShape(world, pos).isEmpty) {
                    free = false
                    break
                }
            }
            if (free) best = top
            break
        }
        return best
    }

    private fun plan(start: Vec3d, dest: Vec3d) {
        goal = dest
        replanAt = fight.now + REPLAN
        next = 0
        val sx = floor(start.x).toInt()
        val sz = floor(start.z).toInt()
        val gx = floor(dest.x).toInt()
        val gz = floor(dest.z).toInt()
        fun h(x: Int, z: Int): Double {
            val dx = abs(x - gx)
            val dz = abs(z - gz)
            return max(dx, dz) + 0.4142 * min(dx, dz)
        }
        val startTop = standAt(sx, sz, start.y) ?: (if (groundAt(start.x, start.z, start.y) != null) start.y else fight.floorY)
        val open = PriorityQueue<Node>(64, compareBy { it.f })
        val done = HashSet<Long>()
        val bestG = HashMap<Long, Double>()
        val first = Node(sx, sz, startTop, 0.0, h(sx, sz), null)
        open.add(first)
        bestG[key(sx, sz)] = 0.0
        var closest = first
        var closestH = h(sx, sz)
        var visited = 0
        var found: Node? = null
        while (open.isNotEmpty() && visited < MAX_NODES) {
            val n = open.poll()
            val k = key(n.x, n.z)
            if (!done.add(k)) continue
            visited++
            val hn = h(n.x, n.z)
            if (hn < closestH) {
                closestH = hn
                closest = n
            }
            if (n.x == gx && n.z == gz) {
                found = n
                break
            }
            for ((ox, oz) in NEIGHBOURS) {
                val nx = n.x + ox
                val nz = n.z + oz
                val nk = key(nx, nz)
                if (nk in done) continue
                val center = Vec3d(nx + 0.5, n.top, nz + 0.5)
                if (fight.clampToArena(center, 0.8).squaredDistanceTo(center) > 1e-3) continue
                val top = standAt(nx, nz, n.top) ?: continue
                if (ox != 0 && oz != 0) {
                    // Keine Ecke schneiden: beide Nachbarn müssen frei sein.
                    if (standAt(n.x + ox, n.z, n.top) == null || standAt(n.x, n.z + oz, n.top) == null) continue
                }
                val g = n.g + (if (ox != 0 && oz != 0) 1.4142 else 1.0) + abs(top - n.top) * 0.6
                if (g >= (bestG[nk] ?: Double.MAX_VALUE)) continue
                bestG[nk] = g
                open.add(Node(nx, nz, top, g, g + h(nx, nz), n))
            }
        }
        val tail = found ?: closest
        if (tail === first) {
            route = emptyList()
            return
        }
        val raw = ArrayList<Vec3d>()
        var cur: Node? = tail
        while (cur != null) {
            raw.add(Vec3d(cur.x + 0.5, cur.top, cur.z + 0.5))
            cur = cur.parent
        }
        raw.reverse()
        if (found != null) raw[raw.lastIndex] = Vec3d(dest.x, raw.last().y, dest.z)
        // Straffen: von jedem Eckpunkt zum weitesten frei sichtbaren.
        val pulled = ArrayList<Vec3d>()
        var from = start
        var i = 0
        while (i < raw.size) {
            var j = raw.lastIndex
            while (j > i && !clearLine(from, raw[j])) j--
            pulled.add(raw[j])
            from = raw[j]
            i = j + 1
        }
        route = pulled
    }

    private companion object {
        const val MAX_NODES = 2600
        const val REPLAN = 12L
        const val STUCK = 6
        const val TURN = 24f
        val NEIGHBOURS = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1, 1 to 1, 1 to -1, -1 to 1, -1 to -1)
    }
}
