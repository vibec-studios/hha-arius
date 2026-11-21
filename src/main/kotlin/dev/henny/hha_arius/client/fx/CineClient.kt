package dev.henny.hha_arius.client.fx

import dev.henny.hha.client.cine.CinePlayer
import dev.henny.hha_arius.arena.ArenaDimension
import dev.henny.hha_arius.boss.ChaseCamS2C
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.MinecraftClient
import net.minecraft.entity.Entity
import net.minecraft.util.hit.HitResult
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.MathHelper
import net.minecraft.util.math.Vec3d
import net.minecraft.world.RaycastContext
import net.minecraft.world.World
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Client-Seite der Szenen von 1.10.0: Einstieg für Bewegungspuffer ([MotionClient]), Musikregie ([AriusMusic]),
 * Mond-Ebenen ([FrozenMoon]) – und die Kamera ([camera], aus dem Camera-Mixin nach HHAs Szenenkamera):
 * * **Verfolgerkamera** ([ChaseCamS2C]): folgt dem Ziel von hinten-oben entlang seiner Flugrichtung, gefedert über die
 *   echte Frame-Zeit (flüssig bei jeder FPS), mit Sichtprüfung (Strahl vom Ziel zur Kamera, davor mit Abstand).
 * * **Kollisionsschutz** für alle Szenenkameras in Eirholm: steht die Kamera in festem Block, rückt sie entlang der
 *   Blickrichtung vor, bis sie frei ist (höchstens 24 Blöcke) – „vor dem ersten Treffer“ von der Szene aus gesehen.
 */
object CineClient {

    class Pose(@JvmField val pos: Vec3d, @JvmField val yaw: Float, @JvmField val pitch: Float)

    private var chaseTarget = -1
    private var chaseLeft = 0
    private var chaseDist = 6.0
    private var chaseHeight = 2.0
    private var chaseSide = 0.0
    private var chasePos: Vec3d? = null
    private var chaseDir: Vec3d = Vec3d(0.0, 0.0, 1.0)
    private var lastNanos = 0L

    fun init() {
        MotionClient.init()
        AriusMusic.init()
        FrozenMoon.init()
        HighlandViewClient.init()
        ClientPlayNetworking.registerGlobalReceiver(ChaseCamS2C.ID) { p, _ ->
            chaseTarget = p.target
            chaseLeft = p.ticks
            chaseDist = p.dist.toDouble()
            chaseHeight = p.height.toDouble()
            chaseSide = p.side.toDouble()
            chasePos = null
        }
        ClientTickEvents.END_CLIENT_TICK.register { _ ->
            if (chaseLeft > 0) chaseLeft--
        }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> chaseLeft = 0 }
    }

    /**
     * Nebelwert [value] zum weiten Wert [wide] schieben, damit die Berge zu sehen sind (seit 1.12.0 immer, nicht nur in
     * Szenen). Stimmungen mit dichtem Sturm ([moodEnd] unter 40 Blöcken) bleiben, wie sie sind.
     */
    fun widenFog(value: Float, moodEnd: Float, wide: Float): Float {
        val k = ((moodEnd - 40f) / 90f).coerceIn(0f, 1f)
        return value + (maxOf(value, wide) - value) * k
    }

    @JvmStatic
    fun camera(world: World?, pos: Vec3d, yaw: Float, pitch: Float, tickDelta: Float): Pose? {
        world ?: return null
        val now = System.nanoTime()
        val dtTicks = if (lastNanos == 0L) 1.0 else ((now - lastNanos) / 5.0e7).coerceIn(0.0, 4.0)
        lastNanos = now
        if (chaseLeft > 0 && CinePlayer.active) chase(world, pos, tickDelta, dtTicks)?.let { return it }
        if (!CinePlayer.active) return RideClient.overTheSpike(pos)?.let { Pose(it, yaw, pitch) }
        if (world.registryKey != ArenaDimension.WORLD) return null
        return unstuck(world, pos, yaw, pitch)
    }

    private fun chase(world: World, camNow: Vec3d, tickDelta: Float, dt: Double): Pose? {
        val mc = MinecraftClient.getInstance()
        val target: Entity = (if (chaseTarget >= 0) world.getEntityById(chaseTarget) else mc.player) ?: return null
        val tp = target.getLerpedPos(tickDelta).add(0.0, target.height * 0.55, 0.0)
        val v = target.entityPos.subtract(target.lastX, target.lastY, target.lastZ)
        val flat = Vec3d(v.x, 0.0, v.z)
        if (flat.lengthSquared() > 0.0025) chaseDir = chaseDir.lerp(flat.normalize(), 1.0 - kotlin.math.exp(-dt * 0.25)).normalize()
        val side = Vec3d(-chaseDir.z, 0.0, chaseDir.x)
        var want = tp.subtract(chaseDir.multiply(chaseDist)).add(0.0, chaseHeight, 0.0).add(side.multiply(chaseSide))
        // Sichtprüfung: Strahl vom Ziel zur Wunschposition; bei Treffer davor mit Abstand.
        val hit = world.raycast(RaycastContext(tp, want, RaycastContext.ShapeType.VISUAL, RaycastContext.FluidHandling.NONE, target))
        if (hit.type != HitResult.Type.MISS) {
            val d = want.subtract(tp)
            val len = d.length()
            val to = hit.pos.distanceTo(tp) - 0.5
            want = tp.add(d.multiply((to / len).coerceIn(0.15, 1.0)))
        }
        val prev = chasePos ?: camNow
        // Gefedert (etwa 3 Ticks Zeitkonstante), über echte Frame-Zeit.
        val next = prev.lerp(want, 1.0 - kotlin.math.exp(-dt * 0.35))
        chasePos = next
        val look = tp.add(chaseDir.multiply(1.5)).subtract(next)
        val h = sqrt(look.x * look.x + look.z * look.z)
        val yaw = (Math.toDegrees(atan2(look.z, look.x)) - 90.0).toFloat()
        val pitch = (-Math.toDegrees(atan2(look.y, h))).toFloat()
        return Pose(next, yaw, pitch)
    }

    private fun unstuck(world: World, pos: Vec3d, yaw: Float, pitch: Float): Pose? {
        if (!blocked(world, pos)) return null
        val y = Math.toRadians(yaw.toDouble())
        val p = Math.toRadians(pitch.toDouble())
        val fwd = Vec3d(-kotlin.math.sin(y) * kotlin.math.cos(p), -kotlin.math.sin(p), kotlin.math.cos(y) * kotlin.math.cos(p))
        // Erst nach oben herausheben (außen am Hang/Fels), dann entlang der Blickrichtung vorrücken.
        var up = 0.5
        while (up <= 18.0) {
            val q = pos.add(0.0, up, 0.0)
            if (!blocked(world, q)) return Pose(q, yaw, pitch)
            up += 0.5
        }
        var s = 0.25
        while (s <= 24.0) {
            val q = pos.add(fwd.multiply(s))
            if (!blocked(world, q) && !solid(world, q.add(fwd.multiply(0.45)))) return Pose(q.add(fwd.multiply(0.3)), yaw, pitch)
            s += 0.25
        }
        return null
    }

    private fun blocked(world: World, p: Vec3d): Boolean {
        if (solid(world, p)) return true
        val m = 0.35
        return solid(world, p.add(m, 0.0, 0.0)) || solid(world, p.add(-m, 0.0, 0.0)) ||
            solid(world, p.add(0.0, m, 0.0)) || solid(world, p.add(0.0, -m, 0.0)) ||
            solid(world, p.add(0.0, 0.0, m)) || solid(world, p.add(0.0, 0.0, -m))
    }

    private fun solid(world: World, p: Vec3d): Boolean {
        val pos = BlockPos.ofFloored(p)
        val st = world.getBlockState(pos)
        return !st.isAir && st.isOpaqueFullCube && MathHelper.floor(p.y) >= world.bottomY
    }
}
