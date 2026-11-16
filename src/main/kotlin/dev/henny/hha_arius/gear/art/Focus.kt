package dev.henny.hha_arius.gear.art

import dev.henny.hha.api.event.HhaEvents
import dev.henny.hha_arius.boss.Hold
import dev.henny.hha_arius.gear.GearConfig
import dev.henny.hha_arius.gear.GearTasks
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.item.Item
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import java.util.UUID

/**
 * Gemeinsamer „Fokus“ von Rüstung, Handschuhen und Solvane: Ein Spieler tut immer nur EINE Sache (Schlag, Aufladen,
 * Zielen, Kanalisieren, Gleiten, Schwerttechnik, Rüstungs-Wirken). Damit lassen sich starke Fähigkeiten nicht stapeln.
 *
 * Abbruch (ohne Kosten, Animation endet) bei: Wechsel des Haupthand-Items (Slot oder Item), Tod, Weltwechsel,
 * gehaltener Treffer des Bosses ([Hold]), Verbindungsende. Nach jedem Wechsel des Haupthand-Items gilt eine kurze
 * Ziehzeit ([ArtTimings.DRAW]): So bringt das Tauschen zweier Exemplare desselben Items nichts.
 * Nach großen Techniken (Klarer Schnitt, Weißes Loch, Weißer Kern, volle Ladungen) folgt ein Nachhall, in dem keine
 * andere große Technik beginnen kann.
 */
object Focus {

    enum class Busy {
        NONE, MOVE, CHARGE, AIM, CHANNEL, GLIDE, ART, ABILITY;

        /** An das Haupthand-Item gebunden (endet beim Wechsel). */
        val itemBound: Boolean get() = this != ABILITY && this != NONE
    }

    private class State {
        var busy = Busy.NONE
        var until = 0L
        var onCancel: (() -> Unit)? = null
        var reverbUntil = 0L
        var drawUntil = 0L
        var slot = -1
        var item: Item? = null
        var world: ServerWorld? = null
    }

    private val states = HashMap<UUID, State>()

    private fun st(player: ServerPlayerEntity) = states.getOrPut(player.uuid) { State() }

    fun init() {
        HhaEvents.PLAYER_TICK.register { _, player -> tick(player) }
        ServerLivingEntityEvents.AFTER_DEATH.register { entity, _ -> if (entity is ServerPlayerEntity) cancel(entity) }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            cancel(handler.player)
            states.remove(handler.player.uuid)
        }
    }

    fun busy(player: ServerPlayerEntity): Busy {
        val s = states[player.uuid] ?: return Busy.NONE
        if (s.busy != Busy.NONE && s.until > 0 && GearTasks.now >= s.until) {
            s.busy = Busy.NONE
            s.onCancel = null
        }
        return s.busy
    }

    fun isFree(player: ServerPlayerEntity): Boolean = busy(player) == Busy.NONE

    /** Beginnt eine Tätigkeit. [until] = 0: offen bis [end]. [onCancel] läuft nur bei Abbruch von außen. */
    fun begin(player: ServerPlayerEntity, busy: Busy, until: Long, onCancel: (() -> Unit)? = null) {
        val s = st(player)
        s.busy = busy
        s.until = until
        s.onCancel = onCancel
        s.world = player.entityWorld
    }

    /** Beendet die laufende Tätigkeit regulär (ohne onCancel). */
    fun end(player: ServerPlayerEntity, busy: Busy? = null) {
        val s = states[player.uuid] ?: return
        if (busy != null && s.busy != busy) return
        s.busy = Busy.NONE
        s.until = 0
        s.onCancel = null
    }

    /** Bricht die laufende Tätigkeit ab (ruft onCancel). */
    fun cancel(player: ServerPlayerEntity) {
        val s = states[player.uuid] ?: return
        val action = s.onCancel
        s.busy = Busy.NONE
        s.until = 0
        s.onCancel = null
        if (action != null) runCatching(action)
    }

    fun reverb(player: ServerPlayerEntity) {
        st(player).reverbUntil = GearTasks.now + GearConfig.ticks("art_reverb_ticks")
    }

    fun inReverb(player: ServerPlayerEntity): Boolean = (states[player.uuid]?.reverbUntil ?: 0L) > GearTasks.now

    fun drawing(player: ServerPlayerEntity): Boolean = (states[player.uuid]?.drawUntil ?: 0L) > GearTasks.now

    private fun tick(player: ServerPlayerEntity) {
        val s = st(player)
        val slot = player.inventory.selectedSlot
        val item = player.mainHandStack.item
        if (s.slot != slot || s.item !== item) {
            val first = s.slot == -1
            s.slot = slot
            s.item = item
            if (!first) {
                s.drawUntil = GearTasks.now + ArtTimings.DRAW
                if (busy(player).itemBound) cancel(player)
            }
        }
        val b = busy(player)
        if (b == Busy.NONE) return
        if (s.world != null && s.world !== player.entityWorld) cancel(player)
        else if (!player.isAlive || player.isSpectator) cancel(player)
        else if (Hold.isHeld(player)) cancel(player)
    }
}
