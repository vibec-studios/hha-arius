package dev.henny.hha_arius.boss

import dev.henny.hha.logic.Fx
import dev.henny.hha_arius.net.AriusNet
import dev.henny.hha_arius.net.HoldS2C
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import java.util.UUID

/**
 * Inszenierte Treffer (Konzept 3.2): Der Spieler wird kurz **gehalten**. Der Server führt den Körper (Anker oder
 * Geschwindigkeit), der Client sperrt Bewegung, Angriff und Benutzen (Paket [HoldS2C] + Mixins). Die Kamera bleibt beim
 * Spieler. Freigabe spätestens nach [AriusTimings.HOLD_MAX] (bzw. der Obergrenze der Sequenz), bei Tod, Disconnect,
 * Weltwechsel oder Kampfende. Während des Haltens ignoriert der Server jeden anderen Schaden am Spieler.
 */
object Hold {

    enum class Mode { FREEZE, FLIGHT }

    private class State(
        val world: ServerWorld, val mode: Mode, val start: Long, val end: Long,
        var anchor: Vec3d?, val landing: Vec3d?, val fallback: Vec3d?,
    )

    private val held = HashMap<UUID, State>()
    private val graceUntil = HashMap<UUID, Long>()
    private val lockoutUntil = HashMap<UUID, Long>()

    /** Solange gesetzt, darf ein gehaltener Spieler Schaden nehmen (der Treffer der Sequenz selbst). */
    @JvmStatic
    var allowDamage = false
        private set

    fun isHeld(player: ServerPlayerEntity): Boolean = player.uuid in held

    /** Schonfrist nach einer Sequenz: keine Boss-Treffer, keine neuen Ansagen für ihn. */
    fun inGrace(player: ServerPlayerEntity): Boolean =
        (graceUntil[player.uuid] ?: Long.MIN_VALUE) > player.entityWorld.time

    /** Darf gegen diesen Spieler eine neue Sequenz starten (60 Ticks Sperre nach der letzten)? */
    fun canGrab(player: ServerPlayerEntity): Boolean =
        !isHeld(player) && (lockoutUntil[player.uuid] ?: Long.MIN_VALUE) <= player.entityWorld.time

    /** Treffer, der während des Haltens durchgehen soll. */
    fun <T> hitting(block: () -> T): T {
        val previous = allowDamage
        allowDamage = true
        try {
            return block()
        } finally {
            allowDamage = previous
        }
    }

    /** Hält den Spieler an [anchor] fest (eingefroren, Taumeln, Griff). */
    fun freeze(player: ServerPlayerEntity, ticks: Int, anchor: Vec3d = player.entityPos) {
        begin(player, Mode.FREEZE, ticks, anchor, null, null)
        Fx.launchPlayer(player, Vec3d.ZERO)
    }

    /**
     * Schleudert den Spieler mit [velocity] und hält ihn, bis er wieder steht (frühestens nach 5 Ticks) oder [maxTicks]
     * vorbei sind. Landet er weiter als 3,5 Blöcke von [landing] entfernt oder in einem Block, wird er dorthin gesetzt.
     */
    fun launch(player: ServerPlayerEntity, velocity: Vec3d, maxTicks: Int, landing: Vec3d? = null, fallback: Vec3d? = null) {
        begin(player, Mode.FLIGHT, maxTicks, null, landing, fallback)
        Fx.launchPlayer(player, velocity)
    }

    private fun begin(player: ServerPlayerEntity, mode: Mode, ticks: Int, anchor: Vec3d?, landing: Vec3d?, fallback: Vec3d?) {
        val world = player.entityWorld as? ServerWorld ?: return
        val limit = ticks.coerceIn(1, 60)
        held[player.uuid] = State(world, mode, world.time, world.time + limit, anchor, landing, fallback)
        AriusNet.send(player, HoldS2C(true, limit + 5))
        player.stopUsingItem()
    }

    /** Wechselt eine laufende Haltung in Flug (z. B. Einfrieren → Tritt). */
    fun switchToLaunch(player: ServerPlayerEntity, velocity: Vec3d, maxTicks: Int, landing: Vec3d? = null, fallback: Vec3d? = null) {
        held.remove(player.uuid)
        launch(player, velocity, maxTicks, landing, fallback)
    }

    fun release(player: ServerPlayerEntity, grace: Int = AriusTimings.HOLD_GRACE) {
        val state = held.remove(player.uuid) ?: return
        val now = state.world.time
        graceUntil[player.uuid] = now + grace
        lockoutUntil[player.uuid] = now + AriusTimings.HOLD_LOCKOUT
        AriusNet.send(player, HoldS2C(false, 0))
    }

    /** Sofort und ohne Schonfrist lösen (Tod, Disconnect, Kampfende). */
    fun drop(player: ServerPlayerEntity) {
        if (held.remove(player.uuid) != null) AriusNet.send(player, HoldS2C(false, 0))
    }

    fun forget(id: UUID) {
        held.remove(id)
        graceUntil.remove(id)
        lockoutUntil.remove(id)
    }

    fun tick(server: MinecraftServer) {
        if (held.isEmpty()) return
        for ((id, state) in held.entries.toList()) {
            val player = server.playerManager.getPlayer(id)
            if (player == null || player.isRemoved || !player.isAlive || player.entityWorld !== state.world) {
                held.remove(id)
                player?.let { AriusNet.send(it, HoldS2C(false, 0)) }
                continue
            }
            val now = state.world.time
            when (state.mode) {
                Mode.FREEZE -> {
                    val anchor = state.anchor ?: player.entityPos
                    if (player.entityPos.squaredDistanceTo(anchor) > 0.09) {
                        player.teleport(state.world, anchor.x, anchor.y, anchor.z, emptySet(), player.yaw, player.pitch, false)
                    }
                    if (player.velocity.lengthSquared() > 1e-4) Fx.launchPlayer(player, Vec3d.ZERO)
                    if (now >= state.end) release(player)
                }
                Mode.FLIGHT -> {
                    val landed = now - state.start >= 5 && player.isOnGround
                    if (landed || now >= state.end) {
                        settle(player, state)
                        release(player)
                    }
                }
            }
        }
    }

    /** Landeplatz absichern: nie in Blöcken, nie weit weg vom vorgesehenen Ort. */
    private fun settle(player: ServerPlayerEntity, state: State) {
        player.onLanding()
        val world = state.world
        val target = state.landing
        val stuck = !world.isSpaceEmpty(player)
        if (target != null && (stuck || player.entityPos.squaredDistanceTo(target) > 3.5 * 3.5)) {
            val safe = safeSpot(world, target) ?: state.fallback ?: target
            player.teleport(world, safe.x, safe.y, safe.z, emptySet(), player.yaw, player.pitch, false)
        } else if (stuck) {
            val safe = safeSpot(world, player.entityPos) ?: state.fallback ?: return
            player.teleport(world, safe.x, safe.y, safe.z, emptySet(), player.yaw, player.pitch, false)
        }
    }

    /** Nächster Punkt mit zwei Blöcken Luft über festem Boden (suchend nach oben, dann im kleinen Umkreis). */
    fun safeSpot(world: ServerWorld, near: Vec3d): Vec3d? {
        val base = BlockPos.ofFloored(near)
        for (r in 0..4) {
            for (dx in -r..r) for (dz in -r..r) {
                if (maxOf(kotlin.math.abs(dx), kotlin.math.abs(dz)) != r) continue
                for (dy in -2..6) {
                    val feet = base.add(dx, dy, dz)
                    if (world.getBlockState(feet).isAir && world.getBlockState(feet.up()).isAir &&
                        world.getBlockState(feet.down()).isSolidBlock(world, feet.down())
                    ) return Vec3d(feet.x + 0.5, feet.y.toDouble(), feet.z + 0.5)
                }
            }
        }
        return null
    }
}
