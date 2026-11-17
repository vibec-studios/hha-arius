package dev.henny.hha_arius.boss

import dev.henny.hha_arius.HhaArius
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.entity.LivingEntity
import net.minecraft.network.RegistryByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.minecraft.server.network.ServerPlayerEntity
import java.util.UUID

/**
 * Treffer am langen Körper der Drachin. Der 96 Blöcke lange Leib existiert nur in der Darstellung (der Server kennt
 * Rumpf-Box und Kopf-Box). Der Client prüft beim Angriff den Blickstrahl gegen die gezeichneten Glieder und meldet das
 * getroffene Glied ([DragonAimC2S]):
 * * Normale Waffen und Solvane (`attack = true`): der Server führt einen echten Spielerangriff auf die Drachin aus
 *   (Schaden, Verzauberungen, Solvanes Treffereffekte, Abklingzeit wie beim Vanilla-Schlag).
 * * Handschuhe (`attack = false`): Das Glied wird nur gemerkt; der nächste Schlag der Handschuhfolge nimmt die Drachin
 *   als Ziel ([gauntletTarget]).
 * Der Treffer zählt dann als Körper- bzw. Schwanztreffer ([takeZone]) statt als Treffer auf die Rumpf-Box.
 */
object DragonAim {

    data class DragonAimC2S(val entityId: Int, val segment: Int, val attack: Boolean) : CustomPayload {
        override fun getId(): CustomPayload.Id<out CustomPayload> = ID

        companion object {
            val ID = CustomPayload.Id<DragonAimC2S>(HhaArius.id("dragon_aim"))
            val CODEC: PacketCodec<RegistryByteBuf, DragonAimC2S> = PacketCodec.of(
                { v, buf -> buf.writeVarInt(v.entityId); buf.writeVarInt(v.segment); buf.writeBoolean(v.attack) },
                { buf -> DragonAimC2S(buf.readVarInt(), buf.readVarInt(), buf.readBoolean()) },
            )
        }
    }

    /** Ab diesem Glied zählt ein Treffer als Schwanztreffer. */
    private const val TAIL_FROM = 28

    /** Größter Abstand Spieler ↔ Drachin (Körperlänge plus Reichweite). */
    private const val MAX_DIST = 110.0

    /** So lange (Ticks) gilt ein gemerktes Glied für die Handschuhe. */
    private const val GAUNTLET_WINDOW = 14L

    private class Aim(val segment: Int, val tick: Long)

    private val aims = HashMap<UUID, Aim>()

    fun init() {
        PayloadTypeRegistry.playC2S().register(DragonAimC2S.ID, DragonAimC2S.CODEC)
        ServerPlayNetworking.registerGlobalReceiver(DragonAimC2S.ID) { payload, context -> handle(context.player(), payload) }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ -> aims.remove(handler.player.uuid) }
    }

    private fun now(player: ServerPlayerEntity): Long = player.entityWorld.time

    private fun handle(player: ServerPlayerEntity, p: DragonAimC2S) {
        if (!player.isAlive || player.isSpectator) return
        val fight = Encounters.fightOf(player) ?: return
        val dragon = fight.nivaris ?: return
        if (dragon.id != p.entityId || dragon.isRemoved || player.squaredDistanceTo(dragon) > MAX_DIST * MAX_DIST) return
        aims[player.uuid] = Aim(p.segment.coerceIn(0, NivarisShape.SEGMENTS), now(player))
        if (p.attack && !dev.henny.hha_arius.gear.art.GauntletsItem.isGauntlets(player.mainHandStack)) {
            // Echter Spielerangriff: Schaden, Krit, Verzauberungen, Item-Effekte; die Zone liefert takeZone.
            player.attack(dragon)
        }
    }

    /** Zone eines frisch gemeldeten Glieds (verbraucht die Meldung), sonst null. */
    fun takeZone(player: ServerPlayerEntity): NivarisMoves.Zone? {
        val aim = aims[player.uuid] ?: return null
        if (now(player) - aim.tick > GAUNTLET_WINDOW) {
            aims.remove(player.uuid)
            return null
        }
        aims.remove(player.uuid)
        return if (aim.segment >= TAIL_FROM) NivarisMoves.Zone.TAIL else NivarisMoves.Zone.BODY
    }

    /** Für die Handschuhe: die Drachin als zusätzliches Ziel, wenn gerade ein Glied anvisiert wurde. */
    fun gauntletTarget(player: ServerPlayerEntity): LivingEntity? {
        val aim = aims[player.uuid] ?: return null
        if (now(player) - aim.tick > GAUNTLET_WINDOW) return null
        return Encounters.fightOf(player)?.nivaris?.takeIf { !it.isRemoved }
    }
}
