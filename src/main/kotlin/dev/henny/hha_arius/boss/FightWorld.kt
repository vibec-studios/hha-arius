package dev.henny.hha_arius.boss

import dev.henny.hha.logic.Vfx
import dev.henny.hha_arius.arena.ArenaBuilder
import dev.henny.hha_arius.arena.ArenaLayout
import net.minecraft.block.Blocks
import net.minecraft.particle.BlockStateParticleEffect
import net.minecraft.particle.ParticleTypes
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.util.hit.HitResult
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import net.minecraft.world.RaycastContext
import kotlin.math.max
import kotlin.math.sqrt

/*
 * Weltbausteine des Kampfes: Eiskämme, Eiswand, zerbrechliche Säulen, Deckung. Alle Blockänderungen über ArenaEdits,
 * also am Ende wieder wie vorher.
 */

/** Abstand eines Punktes zur Strecke a–b (waagerecht). */
private fun segDist(p: Vec3d, a: Vec3d, b: Vec3d): Pair<Double, Double> {
    val vx = b.x - a.x
    val vz = b.z - a.z
    val len2 = vx * vx + vz * vz
    val t = if (len2 < 1e-9) 0.0 else (((p.x - a.x) * vx + (p.z - a.z) * vz) / len2).coerceIn(0.0, 1.0)
    val dx = p.x - (a.x + vx * t)
    val dz = p.z - (a.z + vz * t)
    return sqrt(dx * dx + dz * dz) to t
}

/** Blöcke entlang der Linie (Mitte), eine Spalte je Block. */
private fun columns(from: Vec3d, to: Vec3d): List<BlockPos> {
    val out = LinkedHashSet<BlockPos>()
    val len = max(1.0, sqrt(from.squaredDistanceTo(to)))
    val steps = (len * 2).toInt()
    for (i in 0..steps) {
        val p = from.lerp(to, i / steps.toDouble())
        out.add(BlockPos.ofFloored(p.x, p.y, p.z))
    }
    return out.toList()
}

fun EirholmFight.ridgeCount(): Int = ridges.size

/**
 * Lässt entlang der Linie einen Eiskamm (2 hoch) wachsen. [gaps] = Lücken (Anteile 0..1 der Länge, je 3 Blöcke breit).
 * Steht ein Spieler im Weg, wird er seitlich weggeschoben. Ist die Höchstzahl erreicht, bricht der älteste Kamm.
 */
fun EirholmFight.addRidge(from: Vec3d, to: Vec3d, gaps: List<Double> = emptyList(), force: Boolean = false): EirholmFight.Ridge? {
    val max = if (phase >= EirholmFight.Phase.VAULT) AriusTimings.MAX_RIDGES_VAULT else AriusTimings.MAX_RIDGES
    if (!force) while (ridges.size >= max) breakOldestRidge()
    val len = sqrt(from.squaredDistanceTo(to))
    val placed = ArrayList<BlockPos>()
    val dir = Vec3d(to.x - from.x, 0.0, to.z - from.z).normalize()
    val side = Vec3d(-dir.z, 0.0, dir.x)
    for (col in columns(Vec3d(from.x, floorY, from.z), Vec3d(to.x, floorY, to.z))) {
        val (_, t) = segDist(Vec3d(col.x + 0.5, floorY, col.z + 0.5), from, to)
        if (gaps.any { kotlin.math.abs(t - it) * len < 1.6 }) continue
        val ground = BlockPos(col.x, floorY.toInt(), col.z)
        if (world.getBlockState(ground.down()).isAir) continue
        for (p in players()) {
            if (p.blockPos.x == col.x && p.blockPos.z == col.z) {
                val sign = if ((p.x - from.x) * side.x + (p.z - from.z) * side.z >= 0) 1.0 else -1.0
                dev.henny.hha.logic.Fx.launchPlayer(p, side.multiply(0.5 * sign).add(0.0, 0.25, 0.0))
            }
        }
        placed.addAll(edits.fill(listOf(ground to Blocks.PACKED_ICE.defaultState, ground.up() to Blocks.BLUE_ICE.defaultState)))
    }
    if (placed.isEmpty()) return null
    ambient("ridge.rise", from.lerp(to, 0.5), 3f, 0.9f)
    val ridge = EirholmFight.Ridge(placed, from, to)
    ridges.add(ridge)
    return ridge
}

/** Der älteste Kamm bricht: kurze Riss-Ansage (Saal-Gefahr, kann vom Budget verschoben werden), dann verzögerte Kristalle. */
fun EirholmFight.breakOldestRidge() {
    val ridge = ridges.removeFirstOrNull() ?: return
    val hazard = calls.hazard(style = Vfx.Style.SOUL)
    if (hazard == null) {
        removeRidgeBlocks(ridge)
        return
    }
    val line = hazard.line(Vec3d(ridge.from.x, floorY, ridge.from.z), Vec3d(ridge.to.x, floorY, ridge.to.z), 3f, 20)
    task(script {
        wait(line.ticks)
        for (p in hazard.hits(players())) hurt(p, 8f, Damage.Kind.IMPACT, nivaris)
        removeRidgeBlocks(ridge)
        hazard.end()
    })
}

fun EirholmFight.removeRidge(ridge: EirholmFight.Ridge?) {
    if (ridge == null) return
    ridges.remove(ridge)
    removeRidgeBlocks(ridge)
}

private fun EirholmFight.removeRidgeBlocks(ridge: EirholmFight.Ridge) {
    for ((i, pos) in ridge.blocks.withIndex()) {
        if (i % 3 == 0) world.spawnParticles(BlockStateParticleEffect(ParticleTypes.BLOCK, world.getBlockState(pos)), pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, 4, 0.3, 0.3, 0.3, 0.05)
    }
    edits.revert(ridge.blocks)
    ambient("ridge.break", ridge.from.lerp(ridge.to, 0.5), 3f, 1f)
}

/** Erster Punkt auf dem Weg, an dem ein Eiskamm steht (für den Sturzflug). */
fun EirholmFight.ridgeOnPath(from: Vec3d, to: Vec3d): Vec3d? {
    var best: Vec3d? = null
    var bestT = Double.MAX_VALUE
    for (ridge in ridges) for (pos in ridge.blocks) {
        val c = Vec3d(pos.x + 0.5, floorY, pos.z + 0.5)
        val (d, t) = segDist(c, from, to)
        if (d <= 1.3 && t < bestT && t > 0.05) {
            bestT = t
            best = c
        }
    }
    return best
}

/** Kämme in der Nähe brechen (Nivaris kracht hinein). */
fun EirholmFight.breakRidgeNear(at: Vec3d, radius: Double) {
    for (ridge in ridges.toList()) {
        val near = ridge.blocks.filter { Vec3d(it.x + 0.5, floorY, it.z + 0.5).squaredDistanceTo(at.x, floorY, at.z) <= radius * radius }
        if (near.isEmpty()) continue
        edits.revert(near)
        val rest = ridge.blocks - near.toSet()
        ridges.remove(ridge)
        if (rest.isNotEmpty()) ridges.add(EirholmFight.Ridge(rest, ridge.from, ridge.to))
    }
}

/** Steht zwischen [from] und den Augen des Spielers ein Block (Kamm, Säule)? */
fun EirholmFight.coveredFrom(from: Vec3d, player: ServerPlayerEntity): Boolean {
    val hit = world.raycast(RaycastContext(from, player.eyePos, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, player))
    if (hit.type == HitResult.Type.MISS) return false
    return hit.pos.squaredDistanceTo(player.eyePos) > 1.0
}

/** Mittelpunkte der Säulen des laufenden Schauplatzes (absolut), die noch stehen. */
fun EirholmFight.livePillars(): List<BlockPos> {
    val rel = if (phase >= EirholmFight.Phase.COLLAPSE) ArenaLayout.VAULT_PILLARS else ArenaLayout.TEMPLE_PILLARS
    return rel.map { abs(it) }.filter { it !in brokenPillars }
}

/** Säule auf dem Weg von [from] nach [to] (Abstand ≤ [width])? */
fun EirholmFight.pillarOnPath(from: Vec3d, to: Vec3d, width: Double): BlockPos? =
    livePillars().filter {
        val (d, t) = segDist(Vec3d(it.x + 0.5, floorY, it.z + 0.5), from, to)
        d <= width && t > 0.1
    }.minByOrNull { Vec3d(it.x + 0.5, floorY, it.z + 0.5).squaredDistanceTo(from) }

fun EirholmFight.shatterPillar(center: BlockPos) {
    if (!brokenPillars.add(center)) return
    val blocks = ArenaBuilder.pillarBlocks(world, origin, center)
    for ((i, pos) in blocks.withIndex()) {
        if (i % 4 == 0) world.spawnParticles(BlockStateParticleEffect(ParticleTypes.BLOCK, world.getBlockState(pos)), pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, 5, 0.4, 0.4, 0.4, 0.08)
        edits.clear(pos)
    }
    ambient("ridge.break", Vec3d.ofCenter(center).add(0.0, 2.0, 0.0), 4f, 0.7f)
    calls.hitShake(Vec3d.ofCenter(center), 3f, 20f)
}

/** Eiswand entlang der Linie, [height] hoch, verschwindet nach [life] Ticks wieder. */
fun EirholmFight.raiseWall(from: Vec3d, to: Vec3d, height: Int, life: Int) {
    val blocks = ArrayList<Pair<BlockPos, net.minecraft.block.BlockState>>()
    for (col in columns(Vec3d(from.x, floorY, from.z), Vec3d(to.x, floorY, to.z))) {
        for (h in 0 until height) blocks.add(BlockPos(col.x, floorY.toInt() + h, col.z) to (if (h == height - 1) Blocks.BLUE_ICE else Blocks.PACKED_ICE).defaultState)
    }
    val placed = edits.fill(blocks)
    task(script {
        wait(life)
        for ((i, pos) in placed.withIndex()) {
            if (i % 3 == 0) world.spawnParticles(BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.PACKED_ICE.defaultState), pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, 3, 0.3, 0.3, 0.3, 0.05)
        }
        edits.revert(placed)
        ambient("ridge.break", from.lerp(to, 0.5), 2.5f, 1.2f)
    })
}

/** Etwas zum Werfen für Nivaris: ein Kammstück oder eine Säule in ≤ 9 Blöcken. */
fun EirholmFight.grabSource(near: Vec3d): BlockPos? {
    val fromRidge = ridges.flatMap { it.blocks }.filter { it.y == floorY.toInt() }
        .minByOrNull { Vec3d(it.x + 0.5, floorY, it.z + 0.5).squaredDistanceTo(near) }
        ?.takeIf { Vec3d(it.x + 0.5, floorY, it.z + 0.5).squaredDistanceTo(near) < 81.0 }
    if (fromRidge != null) return fromRidge
    return livePillars().filter { Vec3d(it.x + 0.5, floorY, it.z + 0.5).squaredDistanceTo(near) < 81.0 }
        .minByOrNull { Vec3d(it.x + 0.5, floorY, it.z + 0.5).squaredDistanceTo(near) }
}

fun EirholmFight.removeGrabSource(pos: BlockPos) {
    if (livePillars().contains(pos)) {
        shatterPillar(pos)
        return
    }
    breakRidgeNear(Vec3d(pos.x + 0.5, floorY, pos.z + 0.5), 2.0)
}

/** Nivaris erstickt an ihrem Atem: In Abschnitt III bricht das den Takt (Arius steht offen). */
fun EirholmFight.onChoke() {
    for (p in players()) p.sendMessage(net.minecraft.text.Text.translatable("hha_arius.hint.choke").formatted(net.minecraft.util.Formatting.GOLD), true)
    if (phase == EirholmFight.Phase.VAULT && !ariusLeads && !dragonDowned) {
        taktBroken = true
        arius?.play(AriusPose.STAGGER)
        openArius(AriusTimings.TAKT_BREAK_OPEN)
        for (p in players()) Advancements.grant(p, "takt_break")
    }
}
