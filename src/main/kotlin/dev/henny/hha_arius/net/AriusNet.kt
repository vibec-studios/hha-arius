package dev.henny.hha_arius.net

import dev.henny.hha_arius.HhaArius
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.network.RegistryByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.Vec3d

/**
 * Pakete des Kampfes (alle Server → Client). Spiellogik bleibt auf dem Server; der Client zeigt nur an. Geht ein
 * Paket verloren, ändert sich am Ergebnis nichts.
 */
object AriusNet {

    fun init() {
        PayloadTypeRegistry.playS2C().register(HoldS2C.ID, HoldS2C.CODEC)
        PayloadTypeRegistry.playS2C().register(SliceS2C.ID, SliceS2C.CODEC)
        PayloadTypeRegistry.playS2C().register(FxS2C.ID, FxS2C.CODEC)
        PayloadTypeRegistry.playS2C().register(LineS2C.ID, LineS2C.CODEC)
        PayloadTypeRegistry.playS2C().register(MusicS2C.ID, MusicS2C.CODEC)
        PayloadTypeRegistry.playS2C().register(AtmosS2C.ID, AtmosS2C.CODEC)
    }

    private const val RANGE = 192.0

    fun send(player: ServerPlayerEntity, payload: CustomPayload) {
        if (!player.isDisconnected) ServerPlayNetworking.send(player, payload)
    }

    /** An alle Spieler der Welt im Umkreis von [RANGE] um [at]. */
    fun near(world: ServerWorld, at: Vec3d, payload: CustomPayload) {
        for (player in world.players) if (player.squaredDistanceTo(at) <= RANGE * RANGE) send(player, payload)
    }
}

/** Stimmungen von Wetter und Licht in Eirholm (Client blendet über [AtmosS2C.ticks] über). */
object AtmosMode {
    /** Tempel: klare, kalte Luft, weite Sicht. */
    const val CLEAR = 0

    /** Gletscherkampf: Schneetreiben, Wind. */
    const val SNOW = 1

    /** Schneesturm/Whiteout: Sicht wenige Blöcke (Spannungsaufbau vor dem Auftritt, Gletschersturz). */
    const val BLIZZARD = 2

    /** Wolken reißen auf: tiefblauer Himmel, maximale Sichtweite (Enthüllung der Nadel). */
    const val REVEAL = 3

    /** Unter dem Eis: Herzgewölbe, blau und gedämpft. */
    const val VAULT = 4

    /** Weißes Licht (Verwandlung, weißes Loch, Finale). */
    const val WHITE = 5
}

/** Wetter-/Lichtstimmung [mode] (siehe [AtmosMode]), Überblendung in [ticks]; mode < 0 = zurück auf Standard. */
data class AtmosS2C(val mode: Int, val ticks: Int) : CustomPayload {
    override fun getId(): CustomPayload.Id<out CustomPayload> = ID

    companion object {
        val ID = CustomPayload.Id<AtmosS2C>(HhaArius.id("atmos"))
        val CODEC: PacketCodec<RegistryByteBuf, AtmosS2C> = PacketCodec.of(
            { v, buf -> buf.writeVarInt(v.mode + 1); buf.writeVarInt(v.ticks) },
            { buf -> AtmosS2C(buf.readVarInt() - 1, buf.readVarInt()) },
        )
    }
}

/** Haltezustand: Bewegung, Angriff und Benutzen gesperrt ([on]), höchstens [ticks] lang (Client-Sicherung). */
data class HoldS2C(val on: Boolean, val ticks: Int) : CustomPayload {
    override fun getId(): CustomPayload.Id<out CustomPayload> = ID

    companion object {
        val ID = CustomPayload.Id<HoldS2C>(HhaArius.id("hold"))
        val CODEC: PacketCodec<RegistryByteBuf, HoldS2C> = PacketCodec.of(
            { v, buf -> buf.writeBoolean(v.on); buf.writeVarInt(v.ticks) },
            { buf -> HoldS2C(buf.readBoolean(), buf.readVarInt()) },
        )
    }
}

/**
 * Momentaufnahme für den Schnitt: Blöcke ([positions] als `BlockPos.asLong`, [states] als Raw-IDs), die der Client
 * als ein bewegtes Bündel zeichnet. [pivot] liegt auf der Schnittebene am Boden; das Bündel bleibt [delay] Ticks stehen
 * („nichts passiert“), rutscht dann [slide] Ticks lang in Richtung [dirX]/[dirZ] ab und kippt. [id] < 0 = Bündel weg.
 */
data class SliceS2C(
    val id: Int, val pivotX: Double, val pivotY: Double, val pivotZ: Double, val dirX: Double, val dirZ: Double,
    val delay: Int, val slide: Int, val positions: LongArray, val states: IntArray,
) : CustomPayload {
    override fun getId(): CustomPayload.Id<out CustomPayload> = ID

    companion object {
        val ID = CustomPayload.Id<SliceS2C>(HhaArius.id("slice"))
        val CODEC: PacketCodec<RegistryByteBuf, SliceS2C> = PacketCodec.of(
            { v, buf ->
                buf.writeVarInt(v.id + 1)
                buf.writeDouble(v.pivotX); buf.writeDouble(v.pivotY); buf.writeDouble(v.pivotZ)
                buf.writeDouble(v.dirX); buf.writeDouble(v.dirZ)
                buf.writeVarInt(v.delay); buf.writeVarInt(v.slide)
                buf.writeVarInt(v.positions.size)
                for (i in v.positions.indices) {
                    buf.writeLong(v.positions[i])
                    buf.writeVarInt(v.states[i])
                }
            },
            { buf ->
                val id = buf.readVarInt() - 1
                val px = buf.readDouble(); val py = buf.readDouble(); val pz = buf.readDouble()
                val dx = buf.readDouble(); val dz = buf.readDouble()
                val delay = buf.readVarInt(); val slide = buf.readVarInt()
                val n = buf.readVarInt().coerceIn(0, 20000)
                val pos = LongArray(n)
                val st = IntArray(n)
                for (i in 0 until n) {
                    pos[i] = buf.readLong()
                    st[i] = buf.readVarInt()
                }
                SliceS2C(id, px, py, pz, dx, dz, delay, slide, pos, st)
            },
        )
    }

    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = id
}

/** Eigene Effekte des Addons (siehe [FxKind]); Bedeutung von [a]/[b]/Richtung je Art. */
data class FxS2C(
    val kind: Int, val x: Double, val y: Double, val z: Double, val dx: Double, val dy: Double, val dz: Double,
    val a: Float, val b: Float, val life: Int, val e1: Int = -1, val e2: Int = -1,
) : CustomPayload {
    override fun getId(): CustomPayload.Id<out CustomPayload> = ID

    companion object {
        val ID = CustomPayload.Id<FxS2C>(HhaArius.id("fx"))
        val CODEC: PacketCodec<RegistryByteBuf, FxS2C> = PacketCodec.of(
            { v, buf ->
                buf.writeVarInt(v.kind)
                buf.writeDouble(v.x); buf.writeDouble(v.y); buf.writeDouble(v.z)
                buf.writeDouble(v.dx); buf.writeDouble(v.dy); buf.writeDouble(v.dz)
                buf.writeFloat(v.a); buf.writeFloat(v.b)
                buf.writeVarInt(v.life); buf.writeVarInt(v.e1 + 1); buf.writeVarInt(v.e2 + 1)
            },
            { buf ->
                FxS2C(
                    buf.readVarInt(), buf.readDouble(), buf.readDouble(), buf.readDouble(),
                    buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readFloat(), buf.readFloat(),
                    buf.readVarInt(), buf.readVarInt() - 1, buf.readVarInt() - 1,
                )
            },
        )
    }
}

/** Art eines [FxS2C]. Ordinal = Leitungs-ID, nur hinten anfügen. */
enum class FxKind {
    /** Wind kehrt sich um: Schnee strömt [life] Ticks lang zu pos; a = Radius. */
    REVERSE_WIND,

    /** Haarfeine Naht von pos nach (dx,dy,dz) als Punkt; a = Höhe. */
    SEAM,

    /** Energie verdichtet sich an der Schneide von e1. */
    EDGE,

    /** Rüstung zerfällt in schwebende Frostsplitter um e1. */
    FRAGMENTS,

    /** Weißer Kern an pos, a = Radius. */
    CORE,

    /** Druckfront: Ring um pos, a = Geschwindigkeit (Bl./Tick), b = Endradius; dx = Korridor-Startwinkel, dy = Anzahl, dz = halbe Korridorbreite (Blöcke). */
    FRONT,

    /** Goldener Resonanzfaden zwischen e1 und e2. */
    THREAD,

    /** Eishülle um die Füße von e1. */
    FROST_SHELL,

    /** Geworfener Eisbrocken von pos nach (dx,dy,dz), a = Größe. */
    ICE_CHUNK,

    /** Eiskristall hängt an pos, a = Hängezeit; fliegt danach nach (dx,dy,dz). */
    CRYSTAL,

    /** Schneesturm: Schnee fällt nach oben um pos, a = Radius. */
    BLIZZARD,

    /** Druckstoß, der Schnee wegräumt: flacher heller Ring um pos bis a. */
    CLEAR_WAVE,

    /** Weißer Bildblitz, Stärke a (abschaltbar über /ariusc flash). */
    FLASH,

    /** Zauber-Geschoss: Start pos, Geschwindigkeit (dx,dy,dz je Tick), a = Radius, b = Schule, e1 = Geschoss-ID. */
    BOLT,

    /** Ende eines Geschosses e1 bei pos (Aufprall), a = Radius, b = Schule. */
    BOLT_END,

    /** Frostfeld am Boden: Mitte pos, a = Radius, life = Dauer. */
    FIELD,

    /** Klarer Schnitt: Ursprung pos, Richtung × Länge (dx,dy,dz), a = halbe Breite, b = 1 waagerecht. */
    CUT_PLANE,

    /** Windsog: Spirale zu e1 hin, a = Radius. */
    PULL,

    /** Weißes Loch (Linsen-Shader): Mitte pos, a = Sog-Radius, b = Ticks bis zum Kollaps (b < 0: das nächste Loch bei pos kollabiert jetzt). */
    WHITE_HOLE,
}

/**
 * Untertitel/Szenentext. [speaker] 0 = Szenentext (kursiv, grau), 1 = Arius. [key] ist der Sprachschlüssel, [voice]
 * die Zeilen-ID für die optionale Stimme (`hha_arius:voice.<lang>.<voice>`), leer = keine.
 */
data class LineS2C(val speaker: Int, val key: String, val voice: String, val ticks: Int) : CustomPayload {
    override fun getId(): CustomPayload.Id<out CustomPayload> = ID

    companion object {
        val ID = CustomPayload.Id<LineS2C>(HhaArius.id("line"))
        val CODEC: PacketCodec<RegistryByteBuf, LineS2C> = PacketCodec.of(
            { v, buf -> buf.writeVarInt(v.speaker); buf.writeString(v.key); buf.writeString(v.voice); buf.writeVarInt(v.ticks) },
            { buf -> LineS2C(buf.readVarInt(), buf.readString(), buf.readString(), buf.readVarInt()) },
        )
    }
}

/** Musik in Dauerschleife ([event] als ID), leer = Stille (mit kurzem Ausblenden über [fade] Ticks). */
data class MusicS2C(val event: String, val fade: Int) : CustomPayload {
    override fun getId(): CustomPayload.Id<out CustomPayload> = ID

    companion object {
        val ID = CustomPayload.Id<MusicS2C>(HhaArius.id("music"))
        val CODEC: PacketCodec<RegistryByteBuf, MusicS2C> = PacketCodec.of(
            { v, buf -> buf.writeString(v.event); buf.writeVarInt(v.fade) },
            { buf -> MusicS2C(buf.readString(), buf.readVarInt()) },
        )
    }
}
