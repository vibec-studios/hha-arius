package dev.henny.hha_arius.boss

import dev.henny.hha_arius.net.AriusNet
import dev.henny.hha_arius.net.CineCamS2C
import dev.henny.hha_arius.net.FxKind
import dev.henny.hha_arius.net.FxS2C
import net.minecraft.entity.damage.DamageSource
import net.minecraft.entity.damage.DamageTypes
import net.minecraft.item.Items
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.math.Box
import net.minecraft.util.math.MathHelper
import net.minecraft.util.math.Vec3d
import java.util.UUID
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Arius remembers each fighter's falling smashes. After a warning he catches the third one by the mace shaft and, since
 * 1.15.13, hauls the fighter overhead and slams them from side to side before letting go. Works in every section where
 * he fights on foot (temple, vault, summit).
 *
 * The swing runs as a task while [AriusMoves] counts as busy, like the counter punish, so Solvane keeps flying in the
 * vault. Every tick the task pins Arius to his spot and facing. Clients draw the fighter hanging from Arius' rendered
 * left fist ([dev.henny.hha_arius.client.fx.MaceSwingClient]); the server anchor only approximates that figure.
 */
class MaceCatch(private val f: EirholmFight) {
    private class Attempts(var count: Int = 0, var last: Long = 0, var cooldown: Long = 0)

    /** [center]: Arius' feet on the floor; [axis]: horizontal direction of the first slam (his left side). */
    private class Plan(val center: Vec3d, val floor: Double, val axis: Vec3d, val start: Vec3d)

    private val attempts = HashMap<UUID, Attempts>()

    /** Is a catch running right now? */
    var running = false
        private set

    fun onHit(a: AriusEntity, p: ServerPlayerEntity, source: DamageSource): Boolean {
        if (source.source !== p || !p.mainHandStack.isOf(Items.MACE) ||
            !(source.isOf(DamageTypes.MACE_SMASH) || p.fallDistance > 1.5) ||
            PlayerHit.current != PlayerHit.Kind.NONE || f.chasing) return false
        val record = attempts.getOrPut(p.uuid) { Attempts() }
        if (f.now < record.cooldown) return false
        if (f.now - record.last > 200) record.count = 0
        record.last = f.now
        record.count = minOf(3, record.count + 1)
        if (record.count == 2) warn(a, p)
        if (record.count < 3 || running || f.moves.countering || f.moves.acting || !Hold.canGrab(p) ||
            f.now < f.ariusOpenUntil || f.players().any { f.calls.hasBossTelegraph(it) }) return false
        val plan = plan(a, p) ?: return false
        record.count = 0
        record.cooldown = f.now + 240
        begin(a, p, plan)
        return true
    }

    /** Second smash: the fighter is told to stay on the ground. */
    private fun warn(a: AriusEntity, p: ServerPlayerEntity) {
        p.sendMessage(Text.translatable("hha_arius.mace.warning").formatted(Formatting.AQUA), true)
        f.ambient("arius.guard", a.entityPos, 1.8f, 0.75f)
    }

    // --- Plan: a vertical swing plane that is free all the way round ------------------------------------------------------

    private fun pivot(c: Vec3d) = c.add(0.0, PIVOT, 0.0)

    private fun radial(axis: Vec3d, theta: Double) = axis.multiply(sin(theta)).add(0.0, cos(theta), 0.0)

    /** Approximate feet of the swung fighter at swing angle [theta] (0 = straight overhead, ± = towards ±[axis]). */
    private fun anchor(plan: Plan, theta: Double): Vec3d {
        val center = pivot(plan.center).add(radial(plan.axis, theta).multiply(REACH))
        return Vec3d(center.x, maxOf(plan.floor, center.y - 0.9), center.z)
    }

    /** Where the fighter lies after a slam towards [side] (body centre, on the floor). */
    private fun lyingSpot(plan: Plan, side: Int) = plan.center.add(plan.axis.multiply(side * LIE_DIST))

    private fun plan(a: AriusEntity, p: ServerPlayerEntity): Plan? {
        val floor = f.floorAt(a)
        // He must stand on solid floor himself (not mid-leap, not on a dragon).
        if (abs(a.y - floor) > 0.6 || !blocked(Box(a.x - 0.3, a.y - 0.3, a.z - 0.3, a.x + 0.3, a.y - 0.01, a.z + 0.3))) return null
        val c = Vec3d(a.x, floor, a.z)
        val flat = Vec3d(p.x - a.x, 0.0, p.z - a.z)
        val d0 = if (flat.lengthSquared() < 1e-4) f.facing(a) else flat.normalize()
        for (deg in intArrayOf(0, 30, -30, 60, -60, 90, -90, 120, -120, 150, -150, 180)) {
            val r = Math.toRadians(deg.toDouble())
            val axis = Vec3d(d0.x * cos(r) - d0.z * sin(r), 0.0, d0.x * sin(r) + d0.z * cos(r)).normalize()
            val plan = Plan(c, floor, axis, p.entityPos)
            if (clear(p, plan)) return plan
        }
        return null
    }

    private fun blocked(box: Box) = f.world.getBlockCollisions(null, box).iterator().hasNext()

    private fun clear(p: ServerPlayerEntity, plan: Plan): Boolean {
        val start = p.entityPos
        fun fits(at: Vec3d) = f.world.isSpaceEmpty(p, p.boundingBox.offset(at.subtract(start)))
        // Path of the held fighter: from the catch overhead, then the full arc between both slams.
        for (i in 0..6) if (!fits(start.lerp(anchor(plan, 0.0), i / 6.0))) return false
        var th = -SLAM_ANGLE
        while (th <= SLAM_ANGLE + 1e-6) {
            if (!fits(anchor(plan, th))) return false
            // The drawn figure reaches further out than the anchor box.
            for (rho in doubleArrayOf(1.2, 2.2, 3.2)) {
                val q = pivot(plan.center).add(radial(plan.axis, th).multiply(rho))
                val y = maxOf(q.y, plan.floor + 0.45)
                if (blocked(Box(q.x - 0.3, y - 0.3, q.z - 0.3, q.x + 0.3, y + 0.3, q.z + 0.3))) return false
            }
            th += 0.12
        }
        // Both slams need level floor under the whole body, at Arius' height.
        for (side in intArrayOf(1, -1)) {
            for (dist in doubleArrayOf(1.2, 2.0, 2.8, 3.6)) {
                val q = plan.center.add(plan.axis.multiply(side * dist))
                if (blocked(Box(q.x - 0.3, plan.floor + 0.05, q.z - 0.3, q.x + 0.3, plan.floor + 0.8, q.z + 0.3))) return false
                if (!blocked(Box(q.x - 0.2, plan.floor - 0.3, q.z - 0.2, q.x + 0.2, plan.floor - 0.02, q.z + 0.2))) return false
            }
        }
        return true
    }

    // --- Swing ------------------------------------------------------------------------------------------------------------

    private fun yawOf(dir: Vec3d) = (Math.toDegrees(kotlin.math.atan2(dir.z, dir.x)) - 90.0).toFloat()

    private fun setYaw(a: AriusEntity, yaw: Float) {
        a.yaw = yaw
        a.headYaw = yaw
        a.bodyYaw = yaw
    }

    private fun begin(a: AriusEntity, p: ServerPlayerEntity, plan: Plan) {
        running = true
        f.moves.hold(true)
        f.face(a, p.entityPos)
        a.play(AriusPose.MACE_CATCH, 3)
        p.sendMessage(Text.empty(), true)
        Hold.allowLong(p, TOTAL + 10)
        Hold.freeze(p, TOTAL + 4, plan.start)
        // The swing first, so the victim's camera already knows the swing plane on its first tick.
        f.fxAll(FxKind.MACE_GRIP, plan.center, plan.axis, a = RELEASE.toFloat(), life = TOTAL, e1 = p, e2 = a)
        AriusNet.send(p, CineCamS2C(true, a.id, TOTAL - 8, 1))
        // The catch itself: a hard parry ring and a brief slow-down for the caught fighter.
        f.ambient("arius.parry", plan.start, 2.8f, 0.8f)
        f.sound("solvane.parry", plan.start, 1.6f, 0.7f)
        AriusNet.send(p, FxS2C(FxKind.TIME_SLOW.ordinal, p.x, p.y, p.z, 0.0, 0.0, 0.0, 0.55f, 0f, 8, -1, -1))
        f.task(swing(a, p, plan))
    }

    private fun swing(a: AriusEntity, p: ServerPlayerEntity, plan: Plan): Script = script {
        var yaw = a.yaw
        var pose = AriusPose.MACE_CATCH
        fun on() = f.canSequence(p) && Hold.isHeld(p) && f.arius === a && !a.isRemoved
        // Tasks run after the director: whatever it did this tick, Arius stays on his spot, facing and pose.
        fun keep() {
            if (a.entityPos.squaredDistanceTo(plan.center.x, a.y, plan.center.z) > 1e-4) f.place(a, Vec3d(plan.center.x, a.y, plan.center.z))
            setYaw(a, yaw)
            if (a.pose != pose) a.play(pose, 2)
        }
        fun pose(next: Int, windup: Int) {
            pose = next
            a.play(next, windup)
        }
        try {
            for (i in 0 until CATCH) {
                if (!on()) return@script
                keep()
                yield(Unit)
            }
            // Heave overhead and turn side-on, so the swing plane runs through his left and right.
            val yaw0 = a.yaw
            val yaw1 = yawOf(Vec3d(-plan.axis.z, 0.0, plan.axis.x))
            pose(AriusPose.MACE_RAISE, RAISE)
            f.ambient("arius.grip", a.entityPos, 1.6f, 0.8f)
            for (i in 1..RAISE) {
                if (!on()) return@script
                val u = smooth(i / RAISE.toDouble())
                yaw = yaw0 + MathHelper.wrapDegrees(yaw1 - yaw0) * u.toFloat()
                keep()
                Hold.moveAnchor(p, plan.start.lerp(anchor(plan, 0.0), u))
                yield(Unit)
            }
            yaw = yaw1
            var last = 0.0
            for ((k, side) in SIDES.withIndex()) {
                if (k > 0) {
                    pose(AriusPose.MACE_RAISE, UP)
                    for (i in 1..UP) {
                        if (!on()) return@script
                        val u = i / UP.toDouble()
                        keep()
                        Hold.moveAnchor(p, anchor(plan, last * (1.0 - (1.0 - (1.0 - u) * (1.0 - u)))))
                        yield(Unit)
                    }
                }
                pose(if (side > 0) AriusPose.MACE_LEFT else AriusPose.MACE_RIGHT, DOWN)
                f.ambient("arius.slash", a.entityPos, 1.4f, 0.62f + k * 0.04f)
                for (i in 1..DOWN) {
                    if (!on()) return@script
                    val u = i / DOWN.toDouble()
                    keep()
                    Hold.moveAnchor(p, anchor(plan, side * SLAM_ANGLE * u * u))
                    yield(Unit)
                }
                last = side * SLAM_ANGLE
                impact(a, p, plan, side, k)
                val hold = if (k == SIDES.lastIndex) HOLD + FINAL_HOLD else HOLD
                for (i in 0 until hold) {
                    if (!on()) return@script
                    keep()
                    yield(Unit)
                }
            }
            // Let go: the fighter stays down on the far side, gets up while Arius straightens.
            val spot = lyingSpot(plan, SIDES.last())
            Hold.moveAnchor(p, spot)
            f.say("mace", 60, combat = true)
            for (i in 0 until GET_UP) {
                if (!on()) return@script
                if (i == LET_GO) pose(AriusPose.IDLE, 0)
                keep()
                yield(Unit)
            }
        } finally {
            if (Hold.isHeld(p)) Hold.release(p)
            AriusNet.send(p, CineCamS2C(false, a.id, 0, 0))
            if (a.pose == AriusPose.MACE_CATCH || a.pose == AriusPose.MACE_RAISE || a.pose == AriusPose.MACE_LEFT ||
                a.pose == AriusPose.MACE_RIGHT) a.play(AriusPose.IDLE)
            f.moves.hold(false)
            running = false
        }
    }

    private fun impact(a: AriusEntity, p: ServerPlayerEntity, plan: Plan, side: Int, k: Int) {
        val spot = lyingSpot(plan, side).add(0.0, 0.05, 0.0)
        // Preserve at least half a heart even with damage scaling or repeated slams.
        val damage = (p.health - 1f).coerceIn(0f, DAMAGE[k])
        if (f.canSequence(p) && Hold.isHeld(p) && damage > 0f) {
            Hold.hitting { Damage.hit(p, a, Damage.Kind.IMPACT, damage, 1f) }
            p.velocity = Vec3d.ZERO
            p.velocityDirty = true
        }
        val last = k == SIDES.lastIndex
        f.ambient("hold.impact", spot, if (last) 3f else 2.4f, 1.1f - k * 0.08f)
        f.fx(FxKind.CLEAR_WAVE, spot, a = if (last) 4.2f else 2.6f + k * 0.3f, life = if (last) 14 else 10)
        f.fx(FxKind.DEBRIS_CLOUD, spot, a = if (last) 2.2f else 1.4f, b = 1f, life = if (last) 22 else 14)
        f.calls.hitShake(spot, if (last) 1.4f else 0.7f, if (last) 16f else 10f)
    }

    private fun smooth(x: Double): Double = x.coerceIn(0.0, 1.0).let { it * it * (3.0 - 2.0 * it) }

    companion object {
        /** Shoulder height of the swing, how far the fighter's centre sits from it, and the slam angle from vertical. */
        private const val PIVOT = 1.55
        private const val REACH = 2.3
        private const val SLAM_ANGLE = 1.95
        private const val LIE_DIST = 2.3

        /** Slams alternate: first back to his left (where the fighter came from), then right, left, right. */
        private val SIDES = intArrayOf(1, -1, 1, -1)
        private val DAMAGE = floatArrayOf(2f, 2f, 2f, 3f)

        private const val CATCH = 12
        private const val RAISE = 8
        private const val UP = 6
        private const val DOWN = 5
        private const val HOLD = 3
        private const val FINAL_HOLD = 5

        /** Arius keeps his fist on the ground a few ticks after the release, so clients hand over without a jerk. */
        private const val LET_GO = 4
        private const val GET_UP = 20

        /** Tick at which the fighter leaves Arius' fist (clients switch to getting up), and the whole scene. */
        const val RELEASE = CATCH + RAISE + 4 * (DOWN + HOLD) + 3 * UP + FINAL_HOLD
        const val TOTAL = RELEASE + GET_UP
    }
}
