package dev.henny.hha_arius.boss

import net.minecraft.item.ItemStack
import net.minecraft.server.network.ServerPlayerEntity

/** Laufende Begegnungen (Vertrag für Siegel und Echo). */
object Encounters {
    fun init() {}

    /**
     * Vom Siegel aufgerufen, NACHDEM der 5-s-Schneesturm des Siegels gelaufen ist. Nimmt alle Spieler im Umkreis von
     * 12 Blöcken mit. Gibt false zurück, wenn kein Start möglich ist (Meldung bekommt der Spieler selbst); dann darf das
     * Siegel nicht verbraucht werden. Das Siegel wird erst beim Sieg verbraucht: Der Aufrufer zieht es NICHT ab.
     */
    fun requestStart(player: ServerPlayerEntity, seal: ItemStack): Boolean = false

    /** Vom Echo von Eirholm: Training eines Abschnitts (1 Tempel, 2 Gletscher, 3 Herz, 4 Finale). */
    fun requestTraining(player: ServerPlayerEntity, stage: Int): Boolean = false

    /** Ist der Spieler gerade Teilnehmer einer Begegnung (für Fähigkeiten, die in der Arena anders wirken sollen)? */
    fun isParticipant(player: ServerPlayerEntity): Boolean = false
}
