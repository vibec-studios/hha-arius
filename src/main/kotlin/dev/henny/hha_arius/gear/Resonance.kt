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
 * Frostresonanz: die EINE gemeinsame Energie von Rüstung, Handschuhen und Solvane (docs/AUSRUESTUNG.md), 0..max je
 * Spieler, nur serverseitig. Träger ist, wer ein Arius-Rüstungsteil trägt oder Handschuhe/Solvane in der Haupthand hält.
 *
 * * Im Kampf steigt sie durch Können (volle Treffer, Schlagfolgen, Konter, perfekte Blöcke, erlittene Treffer) und
 *   nur an lohnenden Zielen ([GearCombat.worthy]): Tiere, Rüstungsständer und Verbündete bringen nichts.
 * * Außerhalb des Kampfs strebt sie langsam zum Ruhepunkt (30, mit vollem Set 50), von unten wie von oben. So gehen
 *   Nutzzauber auch ohne Kampf, aber große Techniken (ab 50) brauchen Kampf, und Horten ist unmöglich.
 * * Volles Set: Ruhepunkt 50 und +25 % auf alle Gewinne.
 */
object Resonance {

    private class State {
        var value = 0f
        var lastCombat = 0L
        var lastSentValue = -1
        var lastSentMax = -1
        var lastRest = -1
        var lastMark = -1
        var lastBearer = false
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

    /** Trägt der Spieler etwas, das die Resonanz nutzt? */
    fun isBearer(player: ServerPlayerEntity): Boolean {
        val main = player.mainHandStack
        if (main.isOf(GearItems.SOLVANE) || main.isOf(GearItems.ARIUS_GAUNTLETS)) return true
        for (slot in ARMOR_SLOTS) {
            val stack = player.getEquippedStack(slot)
            if (GearItems.armorPieces.any { stack.isOf(it) }) return true
        }
        return false
    }

    private val ARMOR_SLOTS = arrayOf(
        net.minecraft.entity.EquipmentSlot.HEAD, net.minecraft.entity.EquipmentSlot.CHEST,
        net.minecraft.entity.EquipmentSlot.LEGS, net.minecraft.entity.EquipmentSlot.FEET,
    )

    /** Ruhepunkt (Ziel außerhalb des Kampfs). */
    fun rest(player: ServerPlayerEntity): Float = when {
        Gear.hasFullSet(player) -> GearConfig.float("resonance_rest_set")
        isBearer(player) -> GearConfig.float("resonance_rest")
        else -> 0f
    }

    /** Gewinn (nur für Träger, mit vollem Set +25 %). Klingt beim Erreichen der Frosthauch-Schwelle einmal leise an. */
    fun gain(player: ServerPlayerEntity, amount: Double) {
        markCombat(player)
        if (amount <= 0.0 || !isBearer(player)) return
        val mult = if (Gear.hasFullSet(player)) GearConfig.num("resonance_set_gain_mult") else 1.0
        val s = state(player)
        val before = s.value
        s.value = (s.value + (amount * mult).toFloat()).coerceAtMost(max())
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
        val bearer = isBearer(player)
        val s = if (bearer) state(player) else states[player.uuid] ?: return
        if (GearTasks.now - s.lastCombat > GearConfig.ticks("resonance_decay_delay")) {
            val rest = rest(player)
            val step = GearConfig.float(if (bearer) "resonance_rest_per_second" else "resonance_decay_per_second") / 20f
            if (s.value > rest) s.value = (s.value - step).coerceAtLeast(rest)
            else if (s.value < rest) s.value = (s.value + step).coerceAtMost(rest)
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
        if (player.age % 5 == 0) sync(player)
    }

    /** Schickt Wert, Ruhepunkt und Kostenmarke nur, wenn sich etwas Sichtbares geändert hat. */
    fun sync(player: ServerPlayerEntity, force: Boolean = false) {
        val s = state(player)
        val shown = s.value.roundToInt()
        val max = max().roundToInt()
        val rest = rest(player).roundToInt()
        val mark = dev.henny.hha_arius.gear.art.SpellCasting.costMark(player)
        val bearer = isBearer(player)
        if (!force && shown == s.lastSentValue && max == s.lastSentMax && rest == s.lastRest && mark == s.lastMark && bearer == s.lastBearer) return
        if (!ServerPlayNetworking.canSend(player, ResonancePayload.ID)) return
        s.lastSentValue = shown
        s.lastSentMax = max
        s.lastRest = rest
        s.lastMark = mark
        s.lastBearer = bearer
        ServerPlayNetworking.send(player, ResonancePayload(shown, max, rest, mark, bearer))
    }
}
