package dev.henny.hha_arius.gear.art

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.server.network.ServerPlayerEntity
import java.util.UUID

/**
 * Testmodus (`/hhaarius test`, OP): unendliche Frostresonanz, kaum Abklingzeit, kein Nachhall, Bauwerke überall erlaubt.
 * Damit lassen sich alle Techniken von Rüstung, Handschuhen und Solvane ohne Gegner ausprobieren. Gilt je Spieler bis
 * zum Abschalten oder Verlassen des Servers.
 */
object ArtTest {

    /** Abklingzeit im Testmodus (Ticks), damit Animationen und Effekte nicht ineinander laufen. */
    private const val TEST_COOLDOWN = 10

    private val active = HashSet<UUID>()

    fun init() {
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ -> active.remove(handler.player.uuid) }
    }

    fun on(player: ServerPlayerEntity): Boolean = player.uuid in active

    fun set(player: ServerPlayerEntity, on: Boolean) {
        if (on) active.add(player.uuid) else active.remove(player.uuid)
        dev.henny.hha_arius.gear.Resonance.sync(player, force = true)
        SpellCasting.markDirty(player)
    }

    /** Abklingzeit in Ticks, im Testmodus gekürzt. */
    fun cd(player: ServerPlayerEntity, ticks: Int): Int = if (on(player)) ticks.coerceAtMost(TEST_COOLDOWN) else ticks
}
