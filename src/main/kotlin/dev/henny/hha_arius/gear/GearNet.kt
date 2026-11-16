package dev.henny.hha_arius.gear

import dev.henny.hha_arius.HhaArius
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload

/**
 * Server → Client: Frostresonanz des Spielers (0..max) für den Balken über der HHA-Cooldown-Leiste, dazu Ruhepunkt,
 * Kostenmarke der gerade bereiten Technik (-1 = keine) und ob der Spieler Träger ist (Balken sichtbar).
 */
data class ResonancePayload(val value: Int, val max: Int, val rest: Int, val mark: Int, val bearer: Boolean) : CustomPayload {
    override fun getId(): CustomPayload.Id<ResonancePayload> = ID

    companion object {
        val ID = CustomPayload.Id<ResonancePayload>(HhaArius.id("gear_resonance"))
        val CODEC: PacketCodec<PacketByteBuf, ResonancePayload> = CustomPayload.codecOf(
            { p, buf ->
                buf.writeVarInt(p.value)
                buf.writeVarInt(p.max)
                buf.writeVarInt(p.rest)
                buf.writeVarInt(p.mark + 1)
                buf.writeBoolean(p.bearer)
            },
            { buf -> ResonancePayload(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt() - 1, buf.readBoolean()) },
        )
    }
}

object GearNet {
    private var registered = false

    fun init() {
        if (registered) return
        registered = true
        PayloadTypeRegistry.playS2C().register(ResonancePayload.ID, ResonancePayload.CODEC)
    }
}
