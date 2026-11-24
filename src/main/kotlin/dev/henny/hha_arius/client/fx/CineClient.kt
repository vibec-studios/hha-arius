package dev.henny.hha_arius.client.fx

import dev.henny.hha.client.cine.CinePlayer
import dev.henny.hha_arius.arena.ArenaDimension
import dev.henny.hha_arius.arena.ArenaLayout
import dev.henny.hha_arius.arena.FarPlan
import dev.henny.hha_arius.boss.ChaseCamS2C
import dev.henny.hha_arius.boss.TrackCamS2C
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
        ClientPlayNetworking.registerGlobalReceiver(TrackCamS2C.ID) { p, _ ->
            // Neuer Strom (Lücke) oder Rücksprung: von vorn.
            val last = track.lastOrNull()
            if (last != null && (p.tick <= last.tick || p.tick - last.tick > 4)) track.clear()
            track.addLast(p)
            while (track.size > TRACK_KEEP) track.removeFirst()
            trackSeen = clientTick
        }
        ClientTickEvents.END_CLIENT_TICK.register { _ ->
            if (chaseLeft > 0) chaseLeft--
            clientTick++
            if (track.isNotEmpty() && clientTick - trackSeen > TRACK_STALE) track.clear()
        }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            chaseLeft = 0
            track.clear()
        }
    }

    /** Abtastpunkte der mitfahrenden Szenenkamera ([TrackCamS2C]), nach Server-Tick geordnet. */
    private val track = ArrayDeque<TrackCamS2C>()
    private var trackSeen = 0
    private var clientTick = 0
    private const val TRACK_KEEP = 24
    private const val TRACK_STALE = 12

    /**
     * Lage der mitfahrenden Kamera zur Abspielzeit der Darsteller ([MotionClient.time]): gleichmäßig zwischen den
     * Abtastpunkten, ohne das Anhalten von HHAs Ein-Tick-Einstellungen. Null, wenn kein Strom läuft oder er ausgespielt ist
     * (dann übernimmt wieder HHAs Kamera mit der folgenden Einstellung).
     */
    private fun tracked(tickDelta: Float): Pose? {
        if (track.isEmpty()) return null
        val time = MotionClient.time(tickDelta)
        if (time.isNaN()) return null
        val first = track.first()
        val last = track.last()
        if (time > last.tick + 0.999) return null
        val (a, b, k) = if (time <= first.tick || track.size == 1) Triple(first, first, 0.0) else {
            var i = 0
            while (i < track.size - 2 && track[i + 1].tick <= time) i++
            val a = track[i]
            val b = track[i + 1]
            Triple(a, b, ((time - a.tick) / (b.tick - a.tick).coerceAtLeast(1)).coerceIn(0.0, 1.0))
        }
        val cut = Vec3d(b.x - a.x, b.y - a.y, b.z - a.z).lengthSquared() > 12.0 * 12.0
        val frac = if (cut) 0.0 else k
        val cam = Vec3d(a.x + (b.x - a.x) * frac, a.y + (b.y - a.y) * frac, a.z + (b.z - a.z) * frac)
        val look = Vec3d(a.lx + (b.lx - a.lx) * frac, a.ly + (b.ly - a.ly) * frac, a.lz + (b.lz - a.lz) * frac).subtract(cam)
        val h = sqrt(look.x * look.x + look.z * look.z)
        if (h < 1e-6 && kotlin.math.abs(look.y) < 1e-6) return null
        val yaw = (Math.toDegrees(atan2(look.z, look.x)) - 90.0).toFloat()
        val pitch = (-Math.toDegrees(atan2(look.y, h))).toFloat()
        return Pose(cam, yaw, pitch)
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
        tracked(tickDelta)?.let { t -> return unstuck(world, t.pos, t.yaw, t.pitch) ?: t }
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
        while (up <= 32.0) {
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

    /**
     * Fest für die Szenenkamera: volle Blöcke, auch durchscheinende (das Eishorn und die Eisgrate waren bisher „Luft“,
     * die Kamera fuhr hindurch). Wo der Abschnitt nicht geladen ist, zeichnet [HighlandViewClient] den Berg nur als Netz
     * ohne Blöcke; dort zählt wie auf dem Server ([dev.henny.hha_arius.boss.CineSafety.solid]) die Höhenkarte der
     * Fernlandschaft (Crix: „die Kamera geht teilweise immer noch durch den Berg“).
     */
    private fun solid(world: World, p: Vec3d): Boolean {
        if (MathHelper.floor(p.y) < world.bottomY) return false
        val pos = BlockPos.ofFloored(p)
        if (!world.chunkManager.isChunkLoaded(pos.x shr 4, pos.z shr 4)) return farSolid(pos)
        val st = world.getBlockState(pos)
        if (st.isAir) return false
        return st.isOpaqueFullCube || (st.isFullCube(world, pos) && !st.getCollisionShape(world, pos).isEmpty)
    }

    private fun farSolid(pos: BlockPos): Boolean {
        val slot = Math.floorDiv(pos.x + ArenaLayout.SLOT_SPACING / 2, ArenaLayout.SLOT_SPACING)
        val origin = ArenaLayout.origin(slot)
        val rx = pos.x - origin.x
        val rz = pos.z - origin.z
        if (rx !in FarPlan.X_MIN..FarPlan.X_MAX || rz !in FarPlan.Z_MIN..FarPlan.Z_MAX) return false
        val top = runCatching { FarPlan.top(rx, rz) }.getOrDefault(-1)
        return top >= 0 && pos.y <= origin.y + top
    }
}
