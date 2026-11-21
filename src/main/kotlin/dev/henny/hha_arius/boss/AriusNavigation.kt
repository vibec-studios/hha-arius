package dev.henny.hha_arius.boss

import net.minecraft.entity.ai.pathing.MobNavigation
import net.minecraft.entity.ai.pathing.Path
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Box
import net.minecraft.util.math.Vec3d
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Script-driven travel. Minecraft chooses the detour while the fight director keeps timing and facing. */
internal class AriusNavigation(private val fight: EirholmFight, private val actor: AriusEntity) {
    // Puppets are positioned by script, so vanilla's gravity-driven onGround flag is unreliable.
    private val planner = object : MobNavigation(actor, fight.world) {
        override fun isAtValidPosition() = true
    }
    private var route: Path? = null
    private var goal: BlockPos? = null
    private var retryAt = 0L

    fun step(to: Vec3d, speed: Double, faceTarget: Boolean): Boolean {
        val dest = fight.clampToArena(to, 0.6)
        val groundY = groundAt(dest.x, dest.z, actor.y) ?: actor.y
        val destBlock = BlockPos.ofFloored(dest.x, groundY, dest.z)
        if (goal?.isWithinDistance(destBlock, 2.5) != true) {
            route = null
            retryAt = 0L
            goal = destBlock
        }
        val start = actor.entityPos
        var path = route
        if (path == null || path.isFinished || !faceTarget) {
            val next = walk(start, dest, speed)
            if (next != null) {
                if (faceTarget) fight.face(actor, dest)
                fight.place(actor, next)
                return hypot(next.x - dest.x, next.z - dest.z) < 0.05
            }
            // Committed strikes stop at cover. Only pursuit may go around a corner.
            if (!faceTarget) return false
            if (fight.now >= retryAt) {
                path = planner.findPathTo(destBlock, 1)
                route = path
                retryAt = fight.now + 10
            }
            if (path == null) return steerAround(start, dest, speed, faceTarget)
        }
        while (!path.isFinished) {
            // Smooth cornering: if the next path node is already cleanly reachable, cut the grid corner.
            if (path.currentNodeIndex + 1 < path.length) {
                val ahead = path.getNodePos(path.currentNodeIndex + 1).toBottomCenterPos()
                if (hypot(start.x - ahead.x, start.z - ahead.z) < 2.6 && walk(start, ahead, speed) != null) {
                    path.next()
                    continue
                }
            }
            val node = path.getNodePosition(actor)
            val distance = hypot(start.x - node.x, start.z - node.z)
            if (distance < 0.28) {
                path.next()
                continue
            }
            val next = walk(start, node, speed)
            if (next == null || fight.clampToArena(next, 0.5).squaredDistanceTo(next) > 0.001) {
                route = null
                retryAt = fight.now + 6
                // Never freeze on a pillar corner: smoothly steer around it while the next A* path is planned.
                return steerAround(start, node, speed, faceTarget)
            }
            fight.face(actor, node)
            fight.place(actor, next)
            if (distance <= speed) path.next()
            return false
        }
        return false
    }

    /** Local angular steering fallback so Arius glides around pillar corners without stalling or clipping. */
    private fun steerAround(start: Vec3d, target: Vec3d, speed: Double, faceTarget: Boolean): Boolean {
        val base = atan2(target.z - start.z, target.x - start.x)
        for (off in STEER_ANGLES) {
            val ang = base + off
            val probe = fight.clampToArena(Vec3d(start.x + cos(ang) * speed, start.y, start.z + sin(ang) * speed), 0.5)
            val next = walk(start, probe, speed) ?: continue
            if (faceTarget) fight.face(actor, next)
            fight.place(actor, next)
            return false
        }
        return false
    }

    private fun groundAt(x: Double, z: Double, refY: Double): Double? {
        val feet = Box(x - 0.2, refY - 2.5, z - 0.2, x + 0.2, refY + 2.0, z + 0.2)
        return fight.world.getBlockCollisions(actor, feet).map { it.boundingBox.maxY }
            .filter { it <= refY + 1.5 }.maxOrNull()
    }

    /** Sweep the full body even on a fast dash; require a walkable surface under every sample. */
    private fun walk(from: Vec3d, to: Vec3d, speed: Double): Vec3d? {
        val dx = to.x - from.x
        val dz = to.z - from.z
        val distance = hypot(dx, dz)
        if (distance < 0.001) return from
        val step = minOf(distance, speed)
        val samples = ceil(step / 0.35).toInt().coerceAtLeast(1)
        var pos = from
        val radius = actor.width * 0.48
        for (i in 1..samples) {
            val t = i * step / (samples * distance)
            val x = from.x + dx * t
            val z = from.z + dz * t
            val feet = Box(x - 0.18, pos.y - 1.15, z - 0.18, x + 0.18, pos.y + 1.01, z + 0.18)
            val top = fight.world.getBlockCollisions(actor, feet).map { it.boundingBox.maxY }
                .filter { it <= pos.y + 1.01 }.maxOrNull() ?: return null
            val bounds = Box(x - radius, top + 0.01, z - radius, x + radius, top + actor.height, z + radius)
            if (fight.world.getBlockCollisions(actor, bounds).iterator().hasNext()) return null
            pos = Vec3d(x, top, z)
        }
        return pos
    }

    private companion object {
        val STEER_ANGLES = doubleArrayOf(0.45, -0.45, 0.95, -0.95, 1.48, -1.48, 2.0, -2.0)
    }
}
