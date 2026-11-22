package dev.henny.hha_arius.net

import dev.henny.hha_arius.HhaArius
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.minecraft.network.RegistryByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.util.math.Vec3d

/**
 * Effekte der Jagd und des Tors (seit 1.10.0): eigenes Paket, damit [FxKind] unberührt bleibt. Nur Darstellung; geht
 * eins verloren, ändert sich am Spiel nichts.
 */
object HuntNet {
    fun init() {
        PayloadTypeRegistry.playS2C().register(HuntFxS2C.ID, HuntFxS2C.CODEC)
    }

    fun fx(
        player: ServerPlayerEntity, kind: HuntFx, pos: Vec3d, dir: Vec3d = Vec3d.ZERO, a: Float = 0f, b: Float = 0f,
        life: Int = 20, color: Int = 0, e1: Int = -1,
    ) {
        AriusNet.send(player, HuntFxS2C(kind.ordinal, pos.x, pos.y, pos.z, dir.x, dir.y, dir.z, a, b, life, color, e1))
    }

    fun fx(players: Iterable<ServerPlayerEntity>, kind: HuntFx, pos: Vec3d, dir: Vec3d = Vec3d.ZERO, a: Float = 0f, b: Float = 0f, life: Int = 20, color: Int = 0, e1: Int = -1) {
        val payload = HuntFxS2C(kind.ordinal, pos.x, pos.y, pos.z, dir.x, dir.y, dir.z, a, b, life, color, e1)
        for (p in players) AriusNet.send(p, payload)
    }
}

/** Art eines [HuntFxS2C]. Ordinal = Leitungs-ID, nur hinten anfügen. */
enum class HuntFx {
    /** Jagd läuft (a = 1) oder ist vorbei (a = 0); e1 = Arius (Herzschlag, Atem, Nähe). */
    STATE,

    /** Knapp verfehlt: Farbsäume, kurze Zeitlupe, Luftzug; pos = Treffpunkt. */
    NEAR_MISS,

    /** Schwerer Einschlag nahe dem Spieler: Ruck, Kippen (Dutch), Farbsäume; a = Stärke 0..1. */
    IMPACT,

    /** Filmbalken für life Ticks (Szenen der Jagd). */
    LETTERBOX,

    /** Blende: a = Einblenden (Ticks), b = Ausblenden, life = gesamt, color = Farbe. */
    FADE,

    /** Grollen im Berg: Zittern, Staub, Steinchen um pos; a = Stärke, life = Dauer. */
    RUMBLE,

    /** Fallender Felsbrocken von pos nach dir (Ziel), a = Größe, life = Fallzeit. */
    BOULDER,

    /** Planken einer Hängebrücke zittern um pos (a = Radius), life = Dauer. */
    PLANKS_SHAKE,

    /** Planken stürzen bei pos (dir = Laufrichtung), a = Anzahl. */
    PLANKS_FALL,

    /** Solvanes Auge in einem Loch der Wand: pos = Mitte, dir = Blickrichtung (in den Gang), a = Größe, life = Dauer. */
    EYE,

    /** Frostatem durch einen Riss: pos = Riss, dir = Ende des Strahls, a = Ansage-Ticks, life = gesamt. */
    CRACK_BREATH,

    /** Die Höhle füllt sich mit ihrem Atem: dir.x = Start-Bogenlänge, dir.y = Ende, a = Geschwindigkeit (Bl./Tick), life. */
    BREATH_FILL,

    /** Brüllen mit Bildwirkung (Schock, Farbsäume, Balken); pos = Quelle. */
    ROAR,

    /** Lawine aus dem Hang folgt der Route: dir.x = Start-Bogenlänge, dir.y = Ende, a = Geschwindigkeit, life. */
    AVALANCHE,

    /** Arius schneidet durch den Fels: Naht von pos nach dir, Splitter, Licht. */
    ROCK_CUT,

    /** Das Tor lädt (Wirbel, Vignette): a = Ticks bis zum Aufbruch. */
    PORTAL_CHARGE,

    /** Seit 1.11.0: Die Seile einer Hängebrücke sind gekappt, das Deck fällt; a = Brücken-Id. */
    BRIDGE_DROP,

    /** Seit 1.11.0: Fortschritt beim Kappen der Seile (a = 0..1), life = Anzeigedauer. */
    ROPE_PROGRESS,

    /** Seit 1.13.0: Deutlicher Hinweis am Felsspalt in der dunklen Höhle (pos = Spalt, dir = in den Gang, life = Dauer). */
    EYE_CUE,
}

/** Paket zu [HuntFx]. Ursprung der Instanz ergibt der Client aus der Position (wie die Landschaftseffekte). */
data class HuntFxS2C(
    val kind: Int, val x: Double, val y: Double, val z: Double, val dx: Double, val dy: Double, val dz: Double,
    val a: Float, val b: Float, val life: Int, val color: Int, val e1: Int,
) : CustomPayload {
    override fun getId(): CustomPayload.Id<out CustomPayload> = ID

    companion object {
        val ID = CustomPayload.Id<HuntFxS2C>(HhaArius.id("hunt_fx"))
        val CODEC: PacketCodec<RegistryByteBuf, HuntFxS2C> = PacketCodec.of(
            { v, buf ->
                buf.writeVarInt(v.kind)
                buf.writeDouble(v.x); buf.writeDouble(v.y); buf.writeDouble(v.z)
                buf.writeDouble(v.dx); buf.writeDouble(v.dy); buf.writeDouble(v.dz)
                buf.writeFloat(v.a); buf.writeFloat(v.b)
                buf.writeVarInt(v.life); buf.writeInt(v.color); buf.writeVarInt(v.e1 + 1)
            },
            { buf ->
                HuntFxS2C(
                    buf.readVarInt(), buf.readDouble(), buf.readDouble(), buf.readDouble(),
                    buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readFloat(), buf.readFloat(),
                    buf.readVarInt(), buf.readInt(), buf.readVarInt() - 1,
                )
            },
        )
    }
}
