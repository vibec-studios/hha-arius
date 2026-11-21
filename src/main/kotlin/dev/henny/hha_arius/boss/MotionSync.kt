package dev.henny.hha_arius.boss

import dev.henny.hha_arius.HhaArius
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.entity.Entity
import net.minecraft.network.RegistryByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.minecraft.server.world.ServerWorld
import java.util.Collections
import java.util.WeakHashMap

/**
 * Lage eines Darstellers in einem Server-Tick (seit 1.10.0): Position, Blickrichtung und je Typ ein Zusatzwert
 * (Nivaris: Fortschritt beim Lösen von der Nadel, sonst NaN).
 */
data class MotionS2C(val entityId: Int, val tick: Int, val x: Double, val y: Double, val z: Double, val yaw: Float, val extra: Float) : CustomPayload {
    override fun getId(): CustomPayload.Id<out CustomPayload> = ID

    companion object {
        val ID = CustomPayload.Id<MotionS2C>(HhaArius.id("motion"))
        val CODEC: PacketCodec<RegistryByteBuf, MotionS2C> = PacketCodec.of(
            { v, buf ->
                buf.writeVarInt(v.entityId); buf.writeVarInt(v.tick)
                buf.writeDouble(v.x); buf.writeDouble(v.y); buf.writeDouble(v.z)
                buf.writeFloat(v.yaw); buf.writeFloat(v.extra)
            },
            { buf -> MotionS2C(buf.readVarInt(), buf.readVarInt(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readFloat(), buf.readFloat()) },
        )
    }
}

/**
 * Flüssige Bewegung der Bosse auf dem Client (seit 1.10.0, Crix: „der Drache fliegt immer noch in 16 fps“).
 *
 * Ursache: Der Server setzt Arius und Solvane in jedem Tick per Position. Vanilla schickt dafür Bewegungspakete, und der
 * Client schiebt die Entity mit einem 3-Schritt-Interpolator hinterher. Kommen Pakete gebündelt oder versetzt zum
 * Client-Tick an (Netz, schwankende TPS, Tick-Sync-Mods), gibt es Ticks ohne und Ticks mit doppeltem Schritt – die
 * Drachin ruckelt im Takt der Ticks, und eine Vorhersage auf dem Client hat das noch zu Rückwärtsschritten verstärkt.
 *
 * Jetzt sendet der Server je Tick die exakte Lage mit **Server-Tick-Stempel**; der Client hält einen kleinen Puffer und
 * spielt ihn auf einer eigenen, weich nachgeregelten Zeitachse ab (Hermite-Kurve zwischen den Stützpunkten, siehe
 * dev.henny.hha_arius.client.fx.MotionClient). Daraus wird je Client-Tick die Position gesetzt; zwischen den Ticks
 * interpoliert Vanilla wie gewohnt mit tickDelta. Kamera-Blickziele (getLerpedPos), Reiter und Effekte sehen dieselbe
 * Lage – nichts zittert gegeneinander.
 */
object MotionSync {

    /** Client-Haken (vom Client gesetzt): nach dem Tick eines Darstellers die gepufferte Lage übernehmen. */
    @JvmField
    var clientHook: ((EirholmPuppet) -> Unit)? = null

    private val tracked: MutableSet<EirholmPuppet> = Collections.newSetFromMap(WeakHashMap())

    /** Reichweite, in der Spieler die Lage bekommen (Körper bis 96 Blöcke lang, Kamera weit weg). */
    private const val RANGE = 320.0

    fun init() {
        PayloadTypeRegistry.playS2C().register(MotionS2C.ID, MotionS2C.CODEC)
        ServerTickEvents.END_SERVER_TICK.register { server ->
            if (tracked.isEmpty()) return@register
            val tick = server.ticks
            val it = tracked.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (e.isRemoved) {
                    it.remove()
                    continue
                }
                send(e, tick)
            }
        }
    }

    /** Server: der Darsteller lebt (aus seinem Tick). */
    fun track(e: EirholmPuppet) {
        if (e !is NivarisEntity && e !is AriusEntity) return
        tracked.add(e)
    }

    private fun send(e: Entity, tick: Int) {
        val world = e.entityWorld as? ServerWorld ?: return
        if (e.vehicle is NivarisEntity) return
        val extra = if (e is NivarisEntity) e.uncoil else Float.NaN
        val yaw = if (e is EirholmPuppet) e.bodyYaw else e.yaw
        val payload = MotionS2C(e.id, tick, e.x, e.y, e.z, yaw, extra)
        for (p in world.players) {
            if (p.squaredDistanceTo(e) > RANGE * RANGE && p.vehicle !== e) continue
            if (p.isDisconnected || !ServerPlayNetworking.canSend(p, MotionS2C.ID)) continue
            ServerPlayNetworking.send(p, payload)
        }
    }
}
