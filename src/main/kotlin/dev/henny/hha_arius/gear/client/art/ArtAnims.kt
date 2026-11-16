package dev.henny.hha_arius.gear.client.art

import dev.henny.hha_arius.gear.art.Anim
import dev.henny.hha_arius.gear.art.ArtAnimS2C
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking

/**
 * Laufende Handschuh-/Solvane-Animation je Entity-ID (Client). Quelle ist allein der Server ([ArtAnimS2C]); Posen,
 * erste Person und Effekte lesen hier. Zeit läuft in Client-Ticks (steht im Pausenmenü still).
 */
object ArtAnims {

    class State(val entityId: Int, val anim: Anim, val start: Long, val duration: Int, val param: Int) {
        /**
         * Alter in Ticks inkl. Teil-Tick, für die Darstellung: ohne die Trefferpause ([dev.henny.hha_arius.client.fx.HitStop],
         * nur Darstellung, holt danach auf). Zeitpunkte für Logik: [rawAge].
         */
        fun age(tickDelta: Float): Float = rawAge(tickDelta) - dev.henny.hha_arius.client.fx.HitStop.offset(entityId, ticks + tickDelta)

        /** Alter ohne Trefferpause. */
        fun rawAge(tickDelta: Float): Float = (ticks - start) + tickDelta

        /** Fortschritt 0..1 (Schleifen: immer 0). */
        fun progress(tickDelta: Float): Float = if (duration <= 0) 0f else (age(tickDelta) / duration).coerceIn(0f, 1f)
    }

    private val states = HashMap<Int, State>()

    /** Client-Ticks seit Start (nur wenn nicht pausiert). */
    var ticks = 0L
        private set

    fun init() {
        ClientPlayNetworking.registerGlobalReceiver(ArtAnimS2C.ID) { p, _ -> set(p.entityId, Anim.of(p.anim), p.duration, p.param) }
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (client.isPaused) return@register
            ticks++
            if (states.isNotEmpty()) states.values.removeIf { !it.anim.loop && it.duration >= 0 && ticks - it.start > it.duration + 2 }
        }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> states.clear() }
    }

    fun set(entityId: Int, anim: Anim, duration: Int, param: Int) {
        if (anim == Anim.NONE) states.remove(entityId) else states[entityId] = State(entityId, anim, ticks, duration, param)
    }

    /** Aktuelle Animation oder null. Abgelaufene Einmal-Animationen gelten als vorbei. */
    fun get(entityId: Int): State? {
        val s = states[entityId] ?: return null
        if (!s.anim.loop && s.duration >= 0 && ticks - s.start > s.duration) return null
        return s
    }
}
