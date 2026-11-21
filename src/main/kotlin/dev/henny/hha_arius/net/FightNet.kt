package dev.henny.hha_arius.net

import dev.henny.hha_arius.HhaArius
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.minecraft.network.RegistryByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload

/**
 * Seit 1.10.0: zusätzliche Pakete (Server → Client) für Konter-Bestrafung, Stille-Duell und Paradeanzeige. Eigene
 * Typen statt neuer Felder in den alten Paketen; wer sie nicht kennt, verpasst nur Darstellung.
 */
object FightNet {
    fun init() {
        PayloadTypeRegistry.playS2C().register(CineCamS2C.ID, CineCamS2C.CODEC)
        PayloadTypeRegistry.playS2C().register(BeatS2C.ID, BeatS2C.CODEC)
        PayloadTypeRegistry.playS2C().register(SilenceS2C.ID, SilenceS2C.CODEC)
        PayloadTypeRegistry.playS2C().register(ParryCueS2C.ID, ParryCueS2C.CODEC)
    }
}

/**
 * Persönliche Kampfkamera: [on] = an für [ticks] (Client-Sicherung), [focus] = Entity, die mit dem eigenen Spieler im
 * Bild bleibt (Arius), [style] 0 = Konter-Bestrafung (Seitenansicht, langsamer Schwenk, Ruck bei Treffern).
 */
data class CineCamS2C(val on: Boolean, val focus: Int, val ticks: Int, val style: Int) : CustomPayload {
    override fun getId(): CustomPayload.Id<out CustomPayload> = ID

    companion object {
        val ID = CustomPayload.Id<CineCamS2C>(HhaArius.id("cine_cam"))
        val CODEC: PacketCodec<RegistryByteBuf, CineCamS2C> = PacketCodec.of(
            { v, buf -> buf.writeBoolean(v.on); buf.writeVarInt(v.focus + 1); buf.writeVarInt(v.ticks); buf.writeVarInt(v.style) },
            { buf -> CineCamS2C(buf.readBoolean(), buf.readVarInt() - 1, buf.readVarInt(), buf.readVarInt()) },
        )
    }
}

/**
 * Ein inszenierter Treffer („Impact Frame“): Trefferpause der Pose von [attacker] für [stop] Ticks, Funken bei
 * ([x],[y],[z]), Kamerarucken der Stärke [kick]; [frame] > 0 = kurzer Negativ-/Weißblitz in dieser Stärke.
 */
data class BeatS2C(val attacker: Int, val x: Double, val y: Double, val z: Double, val stop: Float, val kick: Float, val frame: Float) : CustomPayload {
    override fun getId(): CustomPayload.Id<out CustomPayload> = ID

    companion object {
        val ID = CustomPayload.Id<BeatS2C>(HhaArius.id("beat"))
        val CODEC: PacketCodec<RegistryByteBuf, BeatS2C> = PacketCodec.of(
            { v, buf ->
                buf.writeVarInt(v.attacker + 1); buf.writeDouble(v.x); buf.writeDouble(v.y); buf.writeDouble(v.z)
                buf.writeFloat(v.stop); buf.writeFloat(v.kick); buf.writeFloat(v.frame)
            },
            { buf -> BeatS2C(buf.readVarInt() - 1, buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readFloat(), buf.readFloat(), buf.readFloat()) },
        )
    }
}

/** Stille (Gipfel-Finale): Schwarz-Weiß-Bild der Stärke [strength] (0 = aus), Überblendung in [fade] Ticks. */
data class SilenceS2C(val strength: Float, val fade: Int) : CustomPayload {
    override fun getId(): CustomPayload.Id<out CustomPayload> = ID

    companion object {
        val ID = CustomPayload.Id<SilenceS2C>(HhaArius.id("silence"))
        val CODEC: PacketCodec<RegistryByteBuf, SilenceS2C> = PacketCodec.of(
            { v, buf -> buf.writeFloat(v.strength); buf.writeVarInt(v.fade) },
            { buf -> SilenceS2C(buf.readFloat(), buf.readVarInt()) },
        )
    }
}

/**
 * Paradeanzeige für Solvane: [attacker] trifft voraussichtlich in [ticks] Ticks (Server-Wissen: Ansage, Angriffs-
 * Abklingzeit). [color] ARGB der Markierung (nach Angreifer), [lead] = so viele Ticks vor dem Treffer ist das ideale
 * Rechtsklick-Fenster offen (= Länge der Konterhaltung). [ticks] < 0 = Angriff abgebrochen.
 */
data class ParryCueS2C(val attacker: Int, val ticks: Int, val color: Int, val lead: Int) : CustomPayload {
    override fun getId(): CustomPayload.Id<out CustomPayload> = ID

    companion object {
        val ID = CustomPayload.Id<ParryCueS2C>(HhaArius.id("parry_cue"))
        val CODEC: PacketCodec<RegistryByteBuf, ParryCueS2C> = PacketCodec.of(
            { v, buf -> buf.writeVarInt(v.attacker); buf.writeVarInt(v.ticks + 1); buf.writeInt(v.color); buf.writeVarInt(v.lead) },
            { buf -> ParryCueS2C(buf.readVarInt(), buf.readVarInt() - 1, buf.readInt(), buf.readVarInt()) },
        )
    }
}
