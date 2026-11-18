package dev.henny.hha_arius.gear.art

import net.minecraft.entity.LivingEntity
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.util.hit.BlockHitResult
import net.minecraft.util.hit.HitResult
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Box
import net.minecraft.util.math.Direction
import net.minecraft.util.math.Vec3d
import net.minecraft.world.RaycastContext
import net.minecraft.world.World

/**
 * Wo ein Bauwerk entsteht. Server (setzen) und Client (Vorschau) rechnen mit derselben Funktion und denselben festen
 * Maßen, damit die Vorschau genau zeigt, was passiert. Blöcke entstehen nur in Luft und nie in Wesen.
 */
object Placement {

    const val WALL_RANGE = 14.0
    const val WALL_WIDTH = 5
    const val WALL_HEIGHT = 3
    const val FIELD_RANGE = 14.0
    const val FIELD_RADIUS = 4.0
    const val PLATFORM_RANGE = 10.0
    const val PLATFORM_AIR = 5.0

    /** Mindestzahl freier Zellen, sonst gilt der Ort als ungültig. */
    private const val MIN_WALL = 5
    private const val MIN_PLATFORM = 3
    private const val MIN_BRIDGE = 2

    /** Ergebnis: Zellen (Blöcke) oder ein Mittelpunkt (Frostfeld). null = ungültig. */
    class Target(val cells: List<BlockPos>, val center: Vec3d)

    fun target(world: World, player: PlayerEntity, spell: Spell): Target? = when (spell) {
        Spell.ICE_WALL -> wall(world, player)
        Spell.FROST_FIELD -> field(world, player)
        Spell.YANG_PLATFORM -> platform(world, player)
        Spell.YANG_BRIDGE -> bridge(world, player)
        else -> null
    }

    private fun ray(world: World, player: PlayerEntity, range: Double): BlockHitResult {
        val eye = player.eyePos
        val end = eye.add(player.rotationVector.multiply(range))
        return world.raycast(RaycastContext(eye, end, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, player))
    }

    /** Achse quer zum waagerechten Blick, auf x oder z eingerastet (saubere Blockreihen). */
    private fun horizontal(player: PlayerEntity): Direction = Direction.fromHorizontalDegrees(player.yaw.toDouble())

    private fun free(world: World, pos: BlockPos): Boolean {
        if (!world.isInBuildLimit(pos) || !world.getBlockState(pos).isAir) return false
        return world.getEntitiesByClass(LivingEntity::class.java, Box(pos)) { it.isAlive && !it.isSpectator }.isEmpty()
    }

    private fun wall(world: World, player: PlayerEntity): Target? {
        val hit = ray(world, player, WALL_RANGE)
        if (hit.type != HitResult.Type.BLOCK) return null
        val base = if (hit.side == Direction.UP) hit.blockPos.up() else hit.blockPos.offset(hit.side)
        val facing = horizontal(player)
        val across = facing.rotateYClockwise()
        val cells = ArrayList<BlockPos>()
        for (w in -(WALL_WIDTH / 2)..(WALL_WIDTH / 2)) for (h in 0 until WALL_HEIGHT) {
            val p = base.offset(across, w).up(h)
            if (free(world, p)) cells.add(p.toImmutable())
        }
        return if (cells.size >= MIN_WALL) Target(cells, Vec3d.ofBottomCenter(base)) else null
    }

    private fun field(world: World, player: PlayerEntity): Target? {
        val hit = ray(world, player, FIELD_RANGE)
        if (hit.type != HitResult.Type.BLOCK) return null
        val ground = if (hit.side == Direction.UP) hit.blockPos.up() else hit.blockPos.offset(hit.side)
        return Target(emptyList(), Vec3d.ofBottomCenter(ground))
    }

    private fun platform(world: World, player: PlayerEntity): Target? {
        val center: BlockPos
        if (!player.isOnGround && player.pitch > 60f) {
            center = player.blockPos.down()                                   // im Sprung nach unten schauen: unter die Füße
        } else {
            val hit = ray(world, player, PLATFORM_RANGE)
            center = if (hit.type == HitResult.Type.BLOCK) {
                hit.blockPos.offset(hit.side)
            } else {
                val p = player.eyePos.add(player.rotationVector.multiply(PLATFORM_AIR))
                BlockPos.ofFloored(p.x, p.y - 1.0, p.z)
            }
        }
        val cells = ArrayList<BlockPos>()
        for (dx in -1..1) for (dz in -1..1) {
            val p = center.add(dx, 0, dz)
            if (free(world, p)) cells.add(p.toImmutable())
        }
        return if (cells.size >= MIN_PLATFORM) Target(cells, Vec3d.ofCenter(center)) else null
    }

    /** Mittellinie der Brücke (ohne Breite); siehe [bridge]. */
    fun bridgePath(world: World, player: PlayerEntity): List<BlockPos>? {
        val dir = horizontal(player)
        // Unter den Füßen beginnend, ein Block vor dem Spieler; leicht nach oben/unten schauen ändert die Höhe nicht.
        val start = BlockPos.ofFloored(player.x, player.y - 0.5, player.z).offset(dir)
        val cells = ArrayList<BlockPos>()
        for (i in 0 until BridgeShape.length(player.uuid)) {
            val p = start.offset(dir, i)
            val state = world.getBlockState(p)
            if (!state.isAir) {
                if (cells.isEmpty() && i == 0) continue                       // direkt vor einem stehender Block: weiter
                break
            }
            if (!free(world, p)) break
            cells.add(p.toImmutable())
        }
        return if (cells.size >= MIN_BRIDGE) cells else null
    }

    /** Querrichtung der Brücke (Einheitsschritt in x oder z). */
    fun bridgeSide(player: PlayerEntity): BlockPos {
        val d = horizontal(player).rotateYClockwise()
        return BlockPos(d.offsetX, 0, d.offsetZ)
    }

    /** Fahrbahn über die eingestellte Breite (für die Vorschau und die Prüfung). */
    private fun bridge(world: World, player: PlayerEntity): Target? {
        val path = bridgePath(world, player) ?: return null
        val side = bridgeSide(player)
        val half = BridgeShape.width(player.uuid) / 2
        val cells = ArrayList<BlockPos>()
        for (c in path) for (w in -half..half) {
            val p = c.add(side.x * w, 0, side.z * w)
            if (w == 0 || free(world, p)) cells.add(p)
        }
        return Target(cells, Vec3d.ofCenter(path.first()))
    }
}
