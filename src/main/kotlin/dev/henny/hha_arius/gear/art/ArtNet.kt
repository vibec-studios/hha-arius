package dev.henny.hha_arius.gear.art

import dev.henny.hha_arius.HhaArius
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.PlayerLookup
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.network.RegistryByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.minecraft.server.network.ServerPlayerEntity

/** Eingaben des Clients. Der Server prüft alles selbst (Item, Modus, Abklingzeiten, Ressource, Fokus). */
enum class ArtAction {
    /** Linksklick im Nahkampfmodus (nicht auf einen Block). */
    ATTACK,

    /** Rechtsklick im Nahkampfmodus. */
    KICK,

    /** Schleichen + Rechtsklick im Nahkampfmodus. */
    PARRY,

    /** Taste „Handschuhmodus“. */
    MODE_TOGGLE,

    /** Auswahl im Rad; arg = Spell-Ordinal. */
    SELECT,

    /** Rechtsklick gedrückt im Magiemodus. */
    CAST_PRESS,

    /** Rechtsklick losgelassen. */
    CAST_RELEASE,

    /** Linksklick während Aufladen/Zielen/Kanalisieren: abbrechen (keine Kosten). */
    CAST_CANCEL,

    /** Technik-Taste gedrückt mit Solvane; arg = 1 waagerecht (Schleichen). */
    ART_PRESS,

    /** Technik-Taste losgelassen; arg = 1 waagerecht. */
    ART_RELEASE,

    /** Abbruch der Haltung (Linksklick). */
    ART_CANCEL;

    companion object {
        fun of(raw: Int): ArtAction? = entries.getOrNull(raw)
    }
}

data class ArtInputC2S(val action: Int, val arg: Int) : CustomPayload {
    override fun getId(): CustomPayload.Id<out CustomPayload> = ID

    companion object {
        val ID = CustomPayload.Id<ArtInputC2S>(HhaArius.id("art_input"))
        val CODEC: PacketCodec<RegistryByteBuf, ArtInputC2S> = PacketCodec.of(
            { v, buf -> buf.writeVarInt(v.action); buf.writeVarInt(v.arg) },
            { buf -> ArtInputC2S(buf.readVarInt(), buf.readVarInt()) },
        )
    }
}

/** Animation eines Spielers für alle in Sichtweite (auch ihn selbst). duration = -1: Schleife. */
data class ArtAnimS2C(val entityId: Int, val anim: Int, val duration: Int, val param: Int) : CustomPayload {
    override fun getId(): CustomPayload.Id<out CustomPayload> = ID

    companion object {
        val ID = CustomPayload.Id<ArtAnimS2C>(HhaArius.id("art_anim"))
        val CODEC: PacketCodec<RegistryByteBuf, ArtAnimS2C> = PacketCodec.of(
            { v, buf -> buf.writeVarInt(v.entityId); buf.writeVarInt(v.anim); buf.writeVarInt(v.duration + 1); buf.writeVarInt(v.param + 1) },
            { buf -> ArtAnimS2C(buf.readVarInt(), buf.readVarInt(), buf.readVarInt() - 1, buf.readVarInt() - 1) },
        )
    }
}

/**
 * Eigener Zustand für HUD und Rad: gewählte und vorige Technik, verbleibende Abklingzeit je Technik (Ticks, Index =
 * Spell-Ordinal), Abklingzeit des Klaren Schnitts und des Weißen Lochs, Gleiter an/aus.
 */
data class ArtSelfS2C(
    val selected: Int, val previous: Int, val cooldowns: IntArray, val artCooldown: Int, val holeCooldown: Int, val gliding: Boolean,
    /** Grundkosten je Technik (aktuelle Config), für die Anzeige im Rad. */
    val costs: IntArray,
) : CustomPayload {
    override fun getId(): CustomPayload.Id<out CustomPayload> = ID

    companion object {
        val ID = CustomPayload.Id<ArtSelfS2C>(HhaArius.id("art_self"))
        val CODEC: PacketCodec<RegistryByteBuf, ArtSelfS2C> = PacketCodec.of(
            { v, buf ->
                buf.writeVarInt(v.selected); buf.writeVarInt(v.previous)
                buf.writeVarInt(v.cooldowns.size)
                for (c in v.cooldowns) buf.writeVarInt(c)
                buf.writeVarInt(v.artCooldown); buf.writeVarInt(v.holeCooldown); buf.writeBoolean(v.gliding)
                buf.writeVarInt(v.costs.size)
                for (c in v.costs) buf.writeVarInt(c)
            },
            { buf ->
                val sel = buf.readVarInt(); val prev = buf.readVarInt()
                val n = buf.readVarInt().coerceIn(0, 64)
                val cds = IntArray(n) { buf.readVarInt() }
                val art = buf.readVarInt(); val hole = buf.readVarInt(); val glide = buf.readBoolean()
                val m = buf.readVarInt().coerceIn(0, 64)
                ArtSelfS2C(sel, prev, cds, art, hole, glide, IntArray(m) { buf.readVarInt() })
            },
        )
    }
}

/**
 * Klarer Schnitt: Blöcke, die tatsächlich entfernt wurden, zeichnet der Client kurz als zwei auseinandergleitende
 * Hälften (nur Darstellung). [axis] = Hauptachse der Schnitt-Normalen (0 x, 1 y, 2 z), [sign] ihre Richtung.
 */
data class ArtCutS2C(val positions: LongArray, val states: IntArray, val axis: Int) : CustomPayload {
    override fun getId(): CustomPayload.Id<out CustomPayload> = ID

    companion object {
        val ID = CustomPayload.Id<ArtCutS2C>(HhaArius.id("art_cut"))
        val CODEC: PacketCodec<RegistryByteBuf, ArtCutS2C> = PacketCodec.of(
            { v, buf ->
                buf.writeVarInt(v.axis)
                buf.writeVarInt(v.positions.size)
                for (i in v.positions.indices) { buf.writeLong(v.positions[i]); buf.writeVarInt(v.states[i]) }
            },
            { buf ->
                val axis = buf.readVarInt()
                val n = buf.readVarInt().coerceIn(0, 1024)
                val pos = LongArray(n); val st = IntArray(n)
                for (i in 0 until n) { pos[i] = buf.readLong(); st[i] = buf.readVarInt() }
                ArtCutS2C(pos, st, axis)
            },
        )
    }
}

object ArtNet {
    private var registered = false

    fun init() {
        if (registered) return
        registered = true
        PayloadTypeRegistry.playC2S().register(ArtInputC2S.ID, ArtInputC2S.CODEC)
        PayloadTypeRegistry.playS2C().register(ArtAnimS2C.ID, ArtAnimS2C.CODEC)
        PayloadTypeRegistry.playS2C().register(ArtSelfS2C.ID, ArtSelfS2C.CODEC)
        PayloadTypeRegistry.playS2C().register(ArtCutS2C.ID, ArtCutS2C.CODEC)
    }

    /** Animation an den Spieler selbst und alle, die ihn sehen. */
    fun anim(player: ServerPlayerEntity, anim: Anim, duration: Int, param: Int = 0) {
        val payload = ArtAnimS2C(player.id, anim.ordinal, duration, param)
        if (ServerPlayNetworking.canSend(player, ArtAnimS2C.ID)) ServerPlayNetworking.send(player, payload)
        for (other in PlayerLookup.tracking(player)) {
            if (other !== player && ServerPlayNetworking.canSend(other, ArtAnimS2C.ID)) ServerPlayNetworking.send(other, payload)
        }
    }
}
