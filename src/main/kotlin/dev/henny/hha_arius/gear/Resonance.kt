package dev.henny.hha_arius.gear

import dev.henny.hha.api.event.HhaEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.particle.ParticleTypes
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Frostresonanz (Konzept 10.1): 0..max je Spieler, nur serverseitig. Steigt durch Können (volle Treffer, Konter,
 * perfekte Schildblöcke, erlittene Treffer), nie durch Zeit, und zerfällt nach einer Weile ohne Kampf. Gewinne zählen
 * nur mit vollem Eirholm-Set, denn nur das Set kann die Resonanz ausgeben.
 */
object Resonance {

    private class State {
        var value = 0f
        var lastCombat = 0L
        var lastSentValue = -1
        var lastSentMax = -1
    }

    private val states = HashMap<UUID, State>()

    private fun state(player: ServerPlayerEntity): State = states.getOrPut(player.uuid) { State() }

    fun init() {
        HhaEvents.PLAYER_TICK.register { world, player -> tick(world, player) }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ -> states.remove(handler.player.uuid) }
        ServerPlayConnectionEvents.JOIN.register { handler, _, _ -> sync(handler.player, force = true) }
    }

    fun max(): Float = GearConfig.float("resonance_max").coerceAtLeast(1f)

    fun get(player: ServerPlayerEntity): Float = states[player.uuid]?.value ?: 0f

    /** Kampfkontakt: hält den Zerfall an, auch ohne Gewinn. */
    fun markCombat(player: ServerPlayerEntity) {
        state(player).lastCombat = GearTasks.now
    }

    /** Gewinn (nur mit vollem Set). Klingt beim Erreichen der Schwelle für den Frosthauch einmal leise an. */
    fun gain(player: ServerPlayerEntity, amount: Double) {
        markCombat(player)
        if (amount <= 0.0 || !Gear.hasFullSet(player)) return
        val s = state(player)
        val before = s.value
        s.value = (s.value + amount.toFloat()).coerceAtMost(max())
        val threshold = GearConfig.float("resonance_frost_breath_threshold")
        if (before < threshold && s.value >= threshold) {
            GearSounds.play(player.entityWorld, player.entityPos, "resonance.chime", null, 0.5f, 1.2f)
        }
        sync(player)
    }

    /** Zieht genau [amount] ab, wenn genug da ist. */
    fun spend(player: ServerPlayerEntity, amount: Float): Boolean {
        val s = state(player)
        if (s.value < amount) return false
        s.value -= amount
        markCombat(player)
        sync(player)
        return true
    }

    /** Gibt alles aus, was da ist, und liefert die Menge zurück. */
    fun spendAll(player: ServerPlayerEntity): Float {
        val s = state(player)
        val spent = s.value
        s.value = 0f
        markCombat(player)
        sync(player)
        return spent
    }

    fun reset(player: ServerPlayerEntity) {
        states[player.uuid]?.value = 0f
        sync(player)
    }

    private fun tick(world: ServerWorld, player: ServerPlayerEntity) {
        val s = states[player.uuid] ?: return
        if (s.value > 0f && GearTasks.now - s.lastCombat > GearConfig.ticks("resonance_decay_delay")) {
            s.value = (s.value - GearConfig.float("resonance_decay_per_second") / 20f).coerceAtLeast(0f)
        }
        // Bei voller Resonanz zieht nur gelegentlich ein Frosthauch von den Schultern (keine Partikelwolke).
        if (GearConfig.on("resonance_frost_breath") && s.value >= GearConfig.float("resonance_frost_breath_threshold") &&
            player.age % 50 == 0 && Gear.hasFullSet(player) && !player.isInvisible
        ) {
            val yaw = Math.toRadians(player.bodyYaw.toDouble())
            val sideX = Math.cos(yaw) * 0.32
            val sideZ = Math.sin(yaw) * 0.32
            val y = player.y + 1.42
            for (side in intArrayOf(-1, 1)) {
                world.spawnParticles(ParticleTypes.SNOWFLAKE, player.x + sideX * side, y, player.z + sideZ * side, 2, 0.05, 0.02, 0.05, 0.01)
            }
        }
        if (player.age % 10 == 0) sync(player)
    }

    /** Schickt den Wert nur, wenn sich die angezeigte Zahl geändert hat. */
    fun sync(player: ServerPlayerEntity, force: Boolean = false) {
        val s = state(player)
        val shown = s.value.roundToInt()
        val max = max().roundToInt()
        if (!force && shown == s.lastSentValue && max == s.lastSentMax) return
        if (!ServerPlayNetworking.canSend(player, ResonancePayload.ID)) return
        s.lastSentValue = shown
        s.lastSentMax = max
        ServerPlayNetworking.send(player, ResonancePayload(shown, max))
    }
}
