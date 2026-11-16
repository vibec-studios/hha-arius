package dev.henny.hha_arius.gear.art

import dev.henny.hha_arius.gear.GearCombat
import dev.henny.hha_arius.gear.GearTasks
import dev.henny.hha_arius.net.AriusNet
import dev.henny.hha_arius.net.FxKind
import dev.henny.hha_arius.net.FxS2C
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.minecraft.entity.Entity
import net.minecraft.entity.LivingEntity
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.hit.HitResult
import net.minecraft.util.math.Box
import net.minecraft.util.math.Vec3d
import net.minecraft.world.RaycastContext
import java.util.UUID

/** Server-Effekt-Helfer der Ausrüstung (eigene Effektarten aus [FxKind], gezeichnet in client/fx/AriusEffects.kt). */
object ArtFx {
    fun send(world: ServerWorld, kind: FxKind, pos: Vec3d, dir: Vec3d = Vec3d.ZERO, a: Float = 0f, b: Float = 0f, life: Int = 20, e1: Int = -1, e2: Int = -1) {
        AriusNet.near(world, pos, FxS2C(kind.ordinal, pos.x, pos.y, pos.z, dir.x, dir.y, dir.z, a, b, life, e1, e2))
    }
}

/**
 * Zauber-Geschosse ohne Entity: gerade Bahn, auf dem Server Tick für Tick geprüft (Blöcke, Wesen), im Client als
 * Effekt mit derselben Bahn gezeichnet ([FxKind.BOLT]; Ende über [FxKind.BOLT_END]). Wird der Server gestoppt, verfallen
 * sie einfach.
 */
object Bolts {

    class Bolt(
        val owner: UUID, val world: ServerWorld, var pos: Vec3d, val velocity: Vec3d, val maxDistance: Double,
        val radius: Double, var pierce: Int, val school: School, val onHit: (ServerPlayerEntity, LivingEntity, Vec3d) -> Unit,
        val onEnd: ((ServerPlayerEntity?, Vec3d) -> Unit)? = null,
    ) {
        val id = nextId++
        var travelled = 0.0
        val hit = HashSet<Int>()
    }

    private var nextId = 1
    private val bolts = ArrayList<Bolt>()
    private var initialized = false

    fun init() {
        if (initialized) return
        initialized = true
        GearTasks.everyTick { if (bolts.isNotEmpty()) tick() }
        ServerLifecycleEvents.SERVER_STOPPED.register { bolts.clear() }
    }

    fun launch(bolt: Bolt) {
        // Länge der freien Bahn vorab, damit der Client die richtige Lebensdauer kennt.
        val end = bolt.pos.add(bolt.velocity.normalize().multiply(bolt.maxDistance))
        val ray = bolt.world.raycast(RaycastContext(bolt.pos, end, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, null as Entity?))
        val free = if (ray.type == HitResult.Type.BLOCK) ray.pos.distanceTo(bolt.pos) else bolt.maxDistance
        val life = (free / bolt.velocity.length()).toInt() + 1
        ArtFx.send(bolt.world, FxKind.BOLT, bolt.pos, bolt.velocity, bolt.radius.toFloat(), bolt.school.ordinal.toFloat(), life, bolt.id)
        bolts.add(bolt)
    }

    private fun tick() {
        val it = bolts.iterator()
        while (it.hasNext()) {
            val b = it.next()
            if (!step(b)) {
                it.remove()
            }
        }
    }

    /** false = Geschoss endet. */
    private fun step(b: Bolt): Boolean {
        val owner = GearCombat.player(b.owner)
        val from = b.pos
        var to = from.add(b.velocity)
        val ray = b.world.raycast(RaycastContext(from, to, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, null as Entity?))
        val blocked = ray.type == HitResult.Type.BLOCK
        if (blocked) to = ray.pos
        if (owner != null) {
            val area = Box(from, to).expand(b.radius + 0.5)
            val candidates = b.world.getOtherEntities(owner, area).filterIsInstance<LivingEntity>()
                .filter { it.id !in b.hit && GearCombat.canHit(owner, it) && it.boundingBox.expand(b.radius).raycast(from, to).isPresent }
                .sortedBy { it.squaredDistanceTo(from) }
            for (target in candidates) {
                b.hit.add(target.id)
                b.onHit(owner, target, target.boundingBox.center)
                b.pierce--
                if (b.pierce < 0) {
                    end(b, owner, target.boundingBox.center)
                    return false
                }
            }
        }
        b.travelled += from.distanceTo(to)
        b.pos = to
        if (blocked || b.travelled >= b.maxDistance) {
            end(b, owner, to)
            return false
        }
        return true
    }

    private fun end(b: Bolt, owner: ServerPlayerEntity?, at: Vec3d) {
        ArtFx.send(b.world, FxKind.BOLT_END, at, a = b.radius.toFloat(), b = b.school.ordinal.toFloat(), life = 10, e1 = b.id)
        b.onEnd?.invoke(owner, at)
    }
}
