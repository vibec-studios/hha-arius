package dev.henny.hha_arius.gear

import dev.henny.hha.api.HhaAddonContext
import net.minecraft.server.network.ServerPlayerEntity

/**
 * PLATZHALTER (Vertrag) – wird vom Ausrüstungs-Teil vollständig ersetzt. Signaturen bleiben gleich, weil der Kampf
 * sie aufruft.
 */
object Gear {
    /** Registriert Items, Rüstung, Set, Abilities, Lore und Config über HHA. */
    fun register(context: HhaAddonContext) {}
}

object GearRewards {
    /**
     * Gibt einem Teilnehmer seine Beute (eigene Beute je Spieler; volles Inventar → vor ihm fallen lassen).
     * @param firstWin erster Sieg dieses Spielers
     * @param flawless niemand aus der Gruppe war k. o.
     */
    fun grant(player: ServerPlayerEntity, firstWin: Boolean, flawless: Boolean) {}

    /** Gibt dem Spieler das Siegel zurück (Niederlage/Abbruch). */
    fun returnSeal(player: ServerPlayerEntity) {}
}
