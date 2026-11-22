package dev.henny.hha_arius.boss

import dev.henny.hha_arius.HhaArius
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.network.RegistryByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.minecraft.server.network.ServerPlayerEntity
import java.util.UUID

/**
 * Quick-Time-Events in Szenen (Crix 17.11.: wie in Black Myth: Wukong). Der Server startet eine Runde für eine Gruppe
 * Spieler ([start]); der Client zeigt groß die Taste mit einem ablaufenden Balken ([QteS2C]) und meldet Erfolg oder
 * Fehlschlag ([QteC2S]). Arten: einmal drücken (Angriff, Springen, Benutzen, Schleichen) oder [Kind.MASH] (Angriff
 * [count]-mal im Fenster). Wer nicht antwortet, gilt nach dem Fenster als gescheitert.
 */
object Qte {

    enum class Kind { ATTACK, JUMP, USE, SNEAK, MASH, LEFT, RIGHT }

    data class QteS2C(val id: Int, val kind: Int, val ticks: Int, val count: Int, val text: String) : CustomPayload {
        override fun getId(): CustomPayload.Id<out CustomPayload> = ID

        companion object {
            val ID = CustomPayload.Id<QteS2C>(HhaArius.id("qte"))
            val CODEC: PacketCodec<RegistryByteBuf, QteS2C> = PacketCodec.of(
                { v, buf -> buf.writeVarInt(v.id); buf.writeVarInt(v.kind); buf.writeVarInt(v.ticks); buf.writeVarInt(v.count); buf.writeString(v.text) },
                { buf -> QteS2C(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readString()) },
            )
        }
    }

    data class QteC2S(val id: Int, val ok: Boolean) : CustomPayload {
        override fun getId(): CustomPayload.Id<out CustomPayload> = ID

        companion object {
            val ID = CustomPayload.Id<QteC2S>(HhaArius.id("qte_result"))
            val CODEC: PacketCodec<RegistryByteBuf, QteC2S> = PacketCodec.of(
                { v, buf -> buf.writeVarInt(v.id); buf.writeBoolean(v.ok) },
                { buf -> QteC2S(buf.readVarInt(), buf.readBoolean()) },
            )
        }
    }

    class Round(val id: Int, val players: Set<UUID>) {
        val results = HashMap<UUID, Boolean>()
        fun done(): Boolean = players.all { it in results }
        fun success(player: ServerPlayerEntity): Boolean = results[player.uuid] == true
        fun anySuccess(): Boolean = results.values.any { it }
    }

    /** Nur Sichtprüfung (src/gametest): beantwortet jede Runde sofort, sonst null. */
    @JvmField
    var testAnswer: ((Kind) -> Boolean)? = null

    private var nextId = 1
    private val rounds = HashMap<Int, Round>()

    fun init() {
        PayloadTypeRegistry.playS2C().register(QteS2C.ID, QteS2C.CODEC)
        PayloadTypeRegistry.playC2S().register(QteC2S.ID, QteC2S.CODEC)
        RideGripS2C.register()
        ServerPlayNetworking.registerGlobalReceiver(QteC2S.ID) { p, ctx ->
            val round = rounds[p.id] ?: return@registerGlobalReceiver
            val player = ctx.player()
            if (player.uuid in round.players && player.uuid !in round.results) round.results[player.uuid] = p.ok
        }
    }

    /** Startet eine Runde. [text] = Übersetzungsschlüssel der Aufforderung. */
    fun start(players: List<ServerPlayerEntity>, kind: Kind, ticks: Int, count: Int, text: String): Round {
        val round = Round(nextId++, players.map { it.uuid }.toSet())
        rounds[round.id] = round
        if (rounds.size > 32) rounds.keys.sorted().take(rounds.size - 32).forEach { rounds.remove(it) }
        val payload = QteS2C(round.id, kind.ordinal, ticks, count, text)
        for (p in players) if (ServerPlayNetworking.canSend(p, QteS2C.ID)) ServerPlayNetworking.send(p, payload) else round.results[p.uuid] = false
        testAnswer?.let { answer -> for (p in players) round.results[p.uuid] = answer(kind) }
        return round
    }

    /** Wer nach Ablauf nicht geantwortet hat, ist gescheitert. */
    fun close(round: Round) {
        for (u in round.players) round.results.putIfAbsent(u, false)
        rounds.remove(round.id)
    }
}
