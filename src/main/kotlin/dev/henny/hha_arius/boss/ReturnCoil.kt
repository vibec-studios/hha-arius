package dev.henny.hha_arius.boss

import dev.henny.hha_arius.arena.ArenaLayout
import dev.henny.hha_arius.arena.FarPlan
import net.minecraft.util.math.Vec3d
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** The perch used while Solvane returns to the blade. Coordinates are relative to the arena origin. */
object ReturnCoil {
    private val axis = Vec3d(ArenaLayout.SPIRE.x + 0.5, 0.0, ArenaLayout.SPIRE.z + 0.5)
    private const val TOP = 211.0
    private const val DESCENT_PER_TURN = 9.5
    private const val STEPS_PER_TURN = 240
    private const val START = PI + 0.65

    val head = axis.add(-2.0, TOP + 2.0, -7.1)
    val look = axis.add(0.0, FarPlan.SHOULDER_Y + 2.4, -19.0)
    val facing: Vec3d = look.subtract(head).normalize()

    val points: List<Vec3d> by lazy { build() }
    val length: Double by lazy { points.zipWithNext().sumOf { (a, b) -> a.distanceTo(b) } }

    fun outward(p: Vec3d): Vec3d = Vec3d(p.x - axis.x, 0.0, p.z - axis.z).normalize()

    private fun direction(angle: Double) = Vec3d(sin(angle), 0.0, cos(angle))

    private fun surface(angle: Double, y: Double): Double {
        val ray = direction(angle)
        var r = 20.0
        while (r > 0.0) {
            val q = axis.add(ray.multiply(r))
            if (FarPlan.spireTop(floor(q.x).toInt(), floor(q.z).toInt()) + 1 >= y - 0.5) return r
            r -= 0.125
        }
        return 0.0
    }

    private fun build(): List<Vec3d> {
        val lastStep = ((TOP - FarPlan.SHOULDER_Y - 3.0) / DESCENT_PER_TURN * STEPS_PER_TURN).toInt()
        val angles = DoubleArray(lastStep + 1) { START + 2 * PI * it / STEPS_PER_TURN }
        val stepY = DESCENT_PER_TURN / STEPS_PER_TURN
        val radii = DoubleArray(angles.size) { i -> surface(angles[i], TOP - stepY * i) }
        fun onSpire(i: Int, distance: Double): Vec3d {
            // Average the stepped voxel silhouette, keeping the belly outside its corners.
            val span = (i - 6).coerceAtLeast(0)..(i + 6).coerceAtMost(angles.lastIndex)
            val rim = maxOf(span.map { radii[it] }.average(), radii[i] - 0.15)
            val segment = (distance / NivarisShape.SEGMENT).toInt().coerceAtMost(NivarisShape.SEGMENTS - 1)
            val clearance = 1.2 * NivarisShape.taper(segment) + 0.22
            return axis.add(direction(angles[i]).multiply(rim + clearance)).add(0.0, TOP - stepY * i, 0.0)
        }

        val shoulder = onSpire(0, 7.2)
        val tangent = onSpire(12, 7.2).subtract(shoulder).normalize()
        val behindHead = head.subtract(facing.multiply(2.3))
        val beforeShoulder = shoulder.subtract(tangent.multiply(2.8))
        val body = (0..32).mapTo(ArrayList<Vec3d>()) { i ->
            val t = i / 32.0
            val r = 1.0 - t
            head.multiply(r * r * r).add(behindHead.multiply(3 * r * r * t))
                .add(beforeShoulder.multiply(3 * r * t * t)).add(shoulder.multiply(t * t * t))
        }
        var arc = body.zipWithNext().sumOf { (a, b) -> a.distanceTo(b) }
        val required = NivarisShape.SEGMENTS * NivarisShape.SEGMENT
        for (i in 1..angles.lastIndex) {
            val point = onSpire(i, arc)
            arc += body.last().distanceTo(point)
            body += point
            if (arc >= required + 0.5) break
        }
        check(arc >= required) { "Return coil must contain every body segment" }
        return body
    }
}
