package dev.henny.hha_arius.boss

import dev.henny.hha_arius.HhaArius
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.network.RegistryByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import java.util.WeakHashMap

/**
 * Musikregie-Befehl an den Client (seit 1.10.0): [kind] 0 = Zustand [name] mit Überblendung [ticks], 1 = Stinger
 * [name], 2 = Ducking auf [level] für [ticks].
 */
data class MusicCueS2C(val kind: Int, val name: String, val ticks: Int, val level: Float) : CustomPayload {
    override fun getId(): CustomPayload.Id<out CustomPayload> = ID

    companion object {
        val ID = CustomPayload.Id<MusicCueS2C>(HhaArius.id("music_cue"))
        val CODEC: PacketCodec<RegistryByteBuf, MusicCueS2C> = PacketCodec.of(
            { v, buf -> buf.writeVarInt(v.kind); buf.writeString(v.name); buf.writeVarInt(v.ticks); buf.writeFloat(v.level) },
            { buf -> MusicCueS2C(buf.readVarInt(), buf.readString(), buf.readVarInt(), buf.readFloat()) },
        )
    }
}

/**
 * Server-Seite der adaptiven Musik (seit 1.10.0, Crix: „die Musik soll sich je Phase, Szene und QTE ändern“). Der Client
 * (dev.henny.hha_arius.client.fx.AriusMusic) kennt je Zustand Stück, Lautstärke, Tonhöhe und eine optionale zweite
 * Schicht; gleiche Stücke laufen beim Wechsel weiter (nur Lautstärke/Tonhöhe gleiten), andere werden überblendet.
 *
 * **Zustände** ([STATES]): `silence`, `temple`, `temple_serious`, `cut`, `horizon`, `kick`, `entrance`, `reveal`,
 * `carry`, `glacier`, `glacier_open`, `ride_intro`, `ride`, `qte`, `storm`, `dive`, `transform`, `heart`, `white_hole`,
 * `finale`, `ending`, `victory`, `defeat`, `hunt`, `hunt_close`, `summit`, `summit_final`.
 *
 * **Stinger** ([STINGS]): `phase`, `impact`, `reveal`, `roar`, `qte_ok`, `qte_fail`, `parry`, `punish`, `fall`.
 *
 * Aufruf aus anderen Teilen (Jagd, Gipfel, Parade): `MusicCue.play(fight, "hunt")`, `MusicCue.play(world, "summit")`,
 * `MusicCue.play(player, "qte")`, `MusicCue.sting(fight, "parry")`, `MusicCue.duck(fight, 0.3f, 40)`.
 * Ältere Aufrufe von [EirholmFight.music] bleiben gültig: der Client ordnet das Stück dem passenden Zustand zu.
 */
object MusicCue {

    val STATES = setOf(
        "silence", "temple", "temple_serious", "cut", "horizon", "kick", "entrance", "reveal", "carry", "glacier",
        "glacier_open", "ride_intro", "ride", "qte", "storm", "dive", "transform", "heart", "white_hole", "finale",
        "ending", "victory", "defeat", "hunt", "hunt_close", "summit", "summit_final",
    )
    val STINGS = setOf("phase", "impact", "reveal", "roar", "qte_ok", "qte_fail", "parry", "punish", "fall")

    private const val STATE = 0
    private const val STING = 1
    private const val DUCK = 2

    /** Letzter Zustand je Kampf (für Wiedereinsteiger). */
    private val current = WeakHashMap<EirholmFight, String>()

    fun register() {
        PayloadTypeRegistry.playS2C().register(MusicCueS2C.ID, MusicCueS2C.CODEC)
    }

    private fun send(players: Collection<ServerPlayerEntity>, payload: MusicCueS2C) {
        for (p in players) if (!p.isDisconnected && ServerPlayNetworking.canSend(p, MusicCueS2C.ID)) ServerPlayNetworking.send(p, payload)
    }

    /** Zustand [state] für alle Teilnehmer des Kampfes; [fade] Ticks Überblendung. */
    fun play(f: EirholmFight, state: String, fade: Int = 30) {
        if (state !in STATES) HhaArius.LOGGER.warn("Unbekannter Musikzustand {}", state)
        current[f] = state
        send(f.audience(), MusicCueS2C(STATE, state, fade, 1f))
    }

    fun play(players: Collection<ServerPlayerEntity>, state: String, fade: Int = 30) = send(players, MusicCueS2C(STATE, state, fade, 1f))

    fun play(player: ServerPlayerEntity, state: String, fade: Int = 30) = play(listOf(player), state, fade)

    fun play(world: ServerWorld, state: String, fade: Int = 30) = play(world.players, state, fade)

    /** Kurzer Akzent über der Musik (duckt sie kurz). */
    fun sting(f: EirholmFight, name: String) = send(f.audience(), MusicCueS2C(STING, name, 0, 1f))

    fun sting(players: Collection<ServerPlayerEntity>, name: String) = send(players, MusicCueS2C(STING, name, 0, 1f))

    /** Musik für [ticks] auf [level] (0..1) absenken, danach weich zurück. */
    fun duck(f: EirholmFight, level: Float, ticks: Int) = send(f.audience(), MusicCueS2C(DUCK, "", ticks, level))

    fun duck(players: Collection<ServerPlayerEntity>, level: Float, ticks: Int) = send(players, MusicCueS2C(DUCK, "", ticks, level))

    /** Wiedereinsteiger bekommen den laufenden Zustand (nach dem Stück aus [EirholmFight.music]). */
    fun rejoin(f: EirholmFight, player: ServerPlayerEntity) {
        val s = current[f] ?: return
        send(listOf(player), MusicCueS2C(STATE, s, 20, 1f))
    }

    /** Kampf vorbei: Gedächtnis leeren. */
    fun forget(f: EirholmFight) {
        current.remove(f)
    }
}
