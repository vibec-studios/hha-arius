package dev.henny.hha_arius.gear

import dev.henny.hha_arius.HhaArius
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload

/** Server → Client: aktuelle Frostresonanz des Spielers (0..max) für den Balken über der HHA-Cooldown-Leiste. */
data class ResonancePayload(val value: Int, val max: Int) : CustomPayload {
    override fun getId(): CustomPayload.Id<ResonancePayload> = ID

    companion object {
        val ID = CustomPayload.Id<ResonancePayload>(HhaArius.id("gear_resonance"))
        val CODEC: PacketCodec<PacketByteBuf, ResonancePayload> = CustomPayload.codecOf(
            { p, buf ->
                buf.writeVarInt(p.value)
                buf.writeVarInt(p.max)
            },
            { buf -> ResonancePayload(buf.readVarInt(), buf.readVarInt()) },
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
