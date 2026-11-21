package dev.henny.hha_arius.boss

import dev.henny.hha.logic.Fx
import dev.henny.hha.logic.Vfx
import dev.henny.hha_arius.HhaArius
import dev.henny.hha_arius.arena.ArenaLayout
import dev.henny.hha_arius.arena.FarPlan
import dev.henny.hha_arius.arena.RideLandmarks
import dev.henny.hha_arius.net.FxKind
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.block.Blocks
import net.minecraft.entity.effect.StatusEffectInstance
import net.minecraft.entity.effect.StatusEffects
import net.minecraft.network.RegistryByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.minecraft.network.packet.s2c.play.EntityPassengersSetS2CPacket
import net.minecraft.particle.BlockStateParticleEffect
import net.minecraft.particle.ParticleTypes
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.math.Vec3d
import java.util.UUID
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Ausweich-/Reiterhaltung während eines Quick-Time-Events auf Solvane. */
object RideAction {
    const val NONE = 0
    const val DUCK = 1
    const val VAULT = 2
    const val BRACE = 3
    const val TUG = 4
}

/**
 * Kurzzeitige Ausweichhaltung eines Reiters (Ducken unter Bögen, Hochziehen über Eisgrate, Festhalten) an alle Clients.
 */
data class RideActionS2C(val entityId: Int, val action: Int, val ticks: Int) : CustomPayload {
    override fun getId(): CustomPayload.Id<out CustomPayload> = ID

    companion object {
        val ID = CustomPayload.Id<RideActionS2C>(HhaArius.id("ride_action"))
        val CODEC: PacketCodec<RegistryByteBuf, RideActionS2C> = PacketCodec.of(
            { v, buf -> buf.writeVarInt(v.entityId); buf.writeVarInt(v.action); buf.writeVarInt(v.ticks) },
            { buf -> RideActionS2C(buf.readVarInt(), buf.readVarInt(), buf.readVarInt()) },
        )
    }
}

/**
 * Griff eines Reiters auf Solvanes Rücken (seit 1.8.1) an alle Clients: [grip] 2 = beide Hände am Eisdorn, 1 = nur
 * noch eine Hand, 0 = kein Griff (abgestürzt oder abgestiegen). Die Darstellung (Haltung dritte/erste Person, Eisdorn)
 * steht in [dev.henny.hha_arius.client.fx.RideClient].
 */
data class RideGripS2C(val entityId: Int, val grip: Int) : CustomPayload {
    override fun getId(): CustomPayload.Id<out CustomPayload> = ID

    companion object {
        val ID = CustomPayload.Id<RideGripS2C>(HhaArius.id("ride_grip"))
        val CODEC: PacketCodec<RegistryByteBuf, RideGripS2C> = PacketCodec.of(
            { v, buf -> buf.writeVarInt(v.entityId); buf.writeVarInt(v.grip) },
            { buf -> RideGripS2C(buf.readVarInt(), buf.readVarInt()) },
        )

        fun register() {
            PayloadTypeRegistry.playS2C().register(ID, CODEC)
            PayloadTypeRegistry.playS2C().register(RideSeatS2C.ID, RideSeatS2C.CODEC)
            PayloadTypeRegistry.playS2C().register(RideActionS2C.ID, RideActionS2C.CODEC)
        }
    }
}

/**
 * Platz eines Reiters auf Solvane (seit 1.11.0) an alle Clients: [seat] 0..7 = Eisdorn von vorn nach hinten,
 * [RideSeats.HEAD] = Arius auf ihrem Kopf, [RideSeats.NONE] = kein Platz.
 */
data class RideSeatS2C(val entityId: Int, val seat: Int) : CustomPayload {
    override fun getId(): CustomPayload.Id<out CustomPayload> = ID

    companion object {
        val ID = CustomPayload.Id<RideSeatS2C>(HhaArius.id("ride_seat"))
        val CODEC: PacketCodec<RegistryByteBuf, RideSeatS2C> = PacketCodec.of(
            { v, buf -> buf.writeVarInt(v.entityId); buf.writeVarInt(v.seat + 2) },
            { buf -> RideSeatS2C(buf.readVarInt(), buf.readVarInt() - 2) },
        )
    }
}

/**
 * Feste Plätze auf ihrem Rücken (seit 1.11.0, Crix: „wenn ich mit 3 Freunden zocke, sollen die an den 3 Eisteilen hinter
 * mir sein“): Spieler 1 hält den ersten Dorn, die weiteren in Beitrittsreihenfolge die Dornen direkt dahinter. Der Platz
 * hängt am Spieler, nicht an der Reihenfolge des Aufsteigens: Wer kurz abrutscht oder neu verbindet, kommt an seinen Dorn
 * zurück, und niemand rückt auf.
 */
object RideSeats {
    const val HEAD = -2
    const val NONE = -1

    private val seats = HashMap<UUID, Int>()

    /** Vergibt freie Plätze in Beitrittsreihenfolge und meldet alle Plätze an alle Zuschauer. */
    fun assign(f: EirholmFight) {
        val online = f.fighters.keys.toList()
        for (id in online) {
            if (id in seats || f.server.playerManager.getPlayer(id) == null) continue
            // Wer die Begegnung verlassen hat, behält seinen Dorn: Niemand rückt auf, und er kommt dorthin zurück.
            val taken = online.mapNotNull { seats[it] }
            var free = 0
            while (free in taken) free++
            seats[id] = free
        }
        for (id in online) {
            val p = f.server.playerManager.getPlayer(id) ?: continue
            broadcast(f, p.id, seats[id] ?: NONE)
        }
    }

    /** Platz des Spielers [id] oder null. */
    fun of(id: UUID): Int? = seats[id]

    private fun broadcast(f: EirholmFight, entityId: Int, seat: Int) {
        val payload = RideSeatS2C(entityId, seat)
        for (p in f.audience()) if (ServerPlayNetworking.canSend(p, RideSeatS2C.ID)) ServerPlayNetworking.send(p, payload)
    }

    private fun broadcastGrip(f: EirholmFight, entityId: Int, grip: Int) {
        val payload = RideGripS2C(entityId, grip)
        for (p in f.audience()) if (ServerPlayNetworking.canSend(p, RideGripS2C.ID)) ServerPlayNetworking.send(p, payload)
    }

    /** Schickt Vanilla-Mitfahrerliste von Solvane explizit an alle Zuschauer (verhindert Mehrspieler-Desync). */
    fun syncPassengers(f: EirholmFight, d: NivarisEntity?) {
        if (d == null || d.isRemoved) return
        val pkt = EntityPassengersSetS2CPacket(d)
        for (p in f.audience()) p.networkHandler.sendPacket(pkt)
    }

    /** Arius setzt sich auf ihren Kopf: als Mitfahrer, damit er ohne Versatz mit ihr fliegt. */
    fun perch(f: EirholmFight, a: AriusEntity, d: NivarisEntity) {
        if (a.vehicle !== d) a.startRiding(d, true, true)
        a.yaw = d.yaw
        a.bodyYaw = d.bodyYaw
        a.headYaw = d.headYaw
        a.play(AriusPose.SEATED)
        broadcast(f, a.id, HEAD)
        syncPassengers(f, d)
    }

    fun unperch(f: EirholmFight, a: AriusEntity) {
        val d = a.vehicle as? NivarisEntity ?: f.nivaris
        if (a.hasVehicle()) a.stopRiding()
        broadcast(f, a.id, NONE)
        syncPassengers(f, d)
    }

    /** Ritt vorbei: alle Plätze und Griffe frei, Vanilla-Mitfahrerliste an alle Zuschauer synchronisieren. */
    fun clear(f: EirholmFight) {
        val d = f.nivaris
        for (id in f.fighters.keys) {
            f.server.playerManager.getPlayer(id)?.let { p ->
                if (d != null && p.vehicle === d) p.stopRiding()
                broadcast(f, p.id, NONE)
                broadcastGrip(f, p.id, 0)
            }
            seats.remove(id)
        }
        syncPassengers(f, d)
    }
}

/**
 * Flugbahn des Ritts: sauberer, weiter Ausflug durch die Ostöffnung des Horsts hinaus ins Hochland, in einem
 * weiten, flüssigen Bogen um das Massiv (West → Süd → Ost), dann in ruhiger Spirale durch den Sturm um das Felshorn
 * hinauf und im gestreckten Anflug genau über die Abwurfzone des Felds ([ArenaLayout.VAULT_LANDING]) bis zum
 * Liegeplatz. Alle Punkte halten großen Kurvenradius ([TURN] = 34), Geländefreiheit ([CLEAR]) und flache Steigung ein.
 */
object RideRoute {
    private const val CLEAR = 16.0

    /** Kleinster Kurvenradius der Route (weit genug für flüssige Bögen ohne 360°-Schleifen). */
    private const val TURN = 34.0
    private const val STEP = 0.5

    /** Abschnitte (Anteile der Bogenlänge), an denen Sturm und Wetter wechseln. */
    var stormFrom = 0.58
        private set
    var diveFrom = 0.84
        private set

    private var pts: List<Vec3d>? = null

    /** Gleichmäßig (alle [STEP] Blöcke) abgetastete Bahn, relativ. */
    fun points(): List<Vec3d> = pts ?: build().also { pts = it }

    val length: Double get() = (points().size - 1) * STEP

    /** Punkt bei Bogenlänge [s] (Blöcke ab dem Start), relativ. */
    fun at(s: Double): Vec3d {
        val p = points()
        val x = (s / STEP).coerceIn(0.0, p.size - 1.0001)
        val i = x.toInt()
        return p[i].lerp(p[i + 1], x - i)
    }

    private fun build(): List<Vec3d> {
        val ax = ArenaLayout.SPIRE.x + 0.5
        val az = ArenaLayout.SPIRE.z + 0.5
        fun around(a: Double, r: Double, up: Double) = Vec3d(ax + sin(a) * r, maxOf(FarPlan.massifH(r), 0.0) + up, az + cos(a) * r)
        val aerie = Vec3d(ArenaLayout.AERIE.x + 0.5, 0.0, ArenaLayout.AERIE.z + 0.5)
        val vault = Vec3d(ArenaLayout.VAULT.x + 0.5, ArenaLayout.VAULT.y.toDouble(), ArenaLayout.VAULT.z + 0.5)
        val rest = ArenaLayout.VAULT_REST
        val ctrl = ArrayList<Vec3d>()
        // Sauberer Ausflug nach Osten durch die Öffnung des Horsts (kein senkrechtes Hochreißen über die Felswand).
        ctrl += aerie.add(0.0, 10.0, 0.0)
        ctrl += aerie.add(54.0, 18.0, 4.0)
        ctrl += aerie.add(122.0, 28.0, 36.0)
        // Weiter Bogen um das Massiv (Westflanke → Süden → Osten).
        val exit = ctrl.last()
        val a0 = atan2(exit.x - ax, exit.z - az) + 0.30
        val r0 = FarPlan.MASSIF_R - 4.0
        val turns = 4.65
        val n = 11
        for (k in 0..n) {
            val u = k / n.toDouble()
            val a = a0 + turns * u
            val r = r0 - 40.0 * u
            ctrl += around(a, r, 24.0 + 5.0 * sin(u * PI * 2.5))
        }
        val stormIndex = ctrl.size
        // Durch den Sturm: weite Spirale um das Horn hinauf bis über die Schulter.
        val a1 = a0 + turns
        val m = 8
        for (k in 1..m) {
            val u = k / m.toDouble()
            val a = a1 + 1.65 * PI * u
            val r = (FarPlan.MASSIF_R - 44.0) + (FarPlan.SHOULDER_R + 34.0 - (FarPlan.MASSIF_R - 44.0)) * u
            ctrl += around(a, r, 26.0 + 9.0 * u)
        }
        val diveIndex = ctrl.size
        // Gestreckter Endanflug aufs Feld: flach genau über die Landeplätze (Abwurf per Fassrolle) zum Liegeplatz.
        val top = ctrl.last()
        ctrl += top.lerp(vault, 0.48).add(0.0, 14.0, 18.0)
        ctrl += vault.add(-4.0, 14.0, 56.0)
        ctrl += vault.add(-4.0, 8.2, 22.0)
        ctrl += vault.add(0.0, 6.5, -2.0)
        ctrl += rest.add(0.0, 2.5, 0.0)

        val fine = ArrayList(DragonRail.path(ctrl[0], ctrl[1].subtract(ctrl[0]), ctrl.drop(1), TURN))
        val tags = ArrayList<Int>(fine.size)
        run {
            var c = 0
            for (q in fine) {
                while (c < ctrl.size - 1 && q.squaredDistanceTo(ctrl[c + 1]) <= q.squaredDistanceTo(ctrl[c])) c++
                tags += c
            }
        }
        // Gelände: mindestens CLEAR über dem höchsten Punkt im Umfeld ±8 Blöcke (außer im Endanflug über dem ebenen Feld).
        val landing = (fine.size - 170).coerceAtLeast(1)
        val ys = DoubleArray(fine.size) { fine[it].y }
        for (i in fine.indices) {
            if (i >= landing) continue
            val q = fine[i]
            var top0 = -1000
            for (dx in intArrayOf(-8, 0, 8)) for (dz in intArrayOf(-8, 0, 8)) {
                top0 = maxOf(top0, FarPlan.top(Math.floor(q.x).toInt() + dx, Math.floor(q.z).toInt() + dz))
            }
            ys[i] = maxOf(ys[i], top0 + CLEAR)
        }
        // Sanfte Steigungsbegrenzung (vorwärts & rückwärts), damit sie nie jäh auf- und abwippt.
        val maxStepY = DragonRail.STEP * 0.28
        for (i in 1 until landing) ys[i] = maxOf(ys[i], ys[i - 1] - maxStepY)
        for (i in landing - 2 downTo 0) ys[i] = maxOf(ys[i], ys[i + 1] - maxStepY)
        // Höhe glätten (gleitender Mittelwert über ±25 Blöcke, nie unter das Gelände-Minimum zurück).
        val sm = DoubleArray(ys.size)
        for (i in ys.indices) {
            var s = 0.0
            var c = 0
            for (k in maxOf(0, i - 50)..minOf(ys.size - 1, i + 50)) { s += ys[k]; c++ }
            sm[i] = if (i >= landing) fine[i].y else maxOf(ys[i], s / c)
        }
        // Weicher Übergang in den Endanflug über dem Feld.
        if (landing < fine.size) {
            val lift = sm[landing - 1] - fine[landing - 1].y
            if (lift > 0.1) {
                val blend = min(40, fine.size - landing)
                for (j in 0 until blend) {
                    val u = 1.0 - (j + 1.0) / (blend + 1.0)
                    sm[landing + j] = fine[landing + j].y + lift * (u * u * (3.0 - 2.0 * u))
                }
            }
        }
        val lifted = fine.indices.map { Vec3d(fine[it].x, sm[it], fine[it].z) }
        // Gleichmäßig abtasten und Abschnittsgrenzen merken.
        val out = ArrayList<Vec3d>()
        out += lifted[0]
        var acc = 0.0
        var next = STEP
        var stormAt = -1.0
        var diveAt = -1.0
        for (i in 1 until lifted.size) {
            val a = lifted[i - 1]
            val b = lifted[i]
            val len = a.distanceTo(b)
            if (stormAt < 0 && tags[i] >= stormIndex) stormAt = acc
            if (diveAt < 0 && tags[i] >= diveIndex) diveAt = acc
            while (acc + len >= next && len > 1e-9) {
                out += a.lerp(b, (next - acc) / len)
                next += STEP
            }
            acc += len
        }
        stormFrom = (stormAt / acc).coerceIn(0.35, 0.85)
        diveFrom = (diveAt / acc).coerceIn(stormFrom + 0.08, 0.94)
        return out
    }
}

/**
 * Der Ritt auf Solvanes Rücken zwischen Abschnitt II und III:
 * * Sauberer Ausflug aus dem Horst (ohne 360°-Schleifen), ruhiger Flug entlang [RideRoute] vorbei an den
 *   **dauerhaften** Felsbögen und Eisgraten der Dimension ([RideLandmarks]).
 * * Faire Quick-Time-Events: Ducken (flach an Solvanes Rücken unter den festen Bögen hindurch), Hochziehen (Beine über
 *   die festen Eisgrate heben), Festhalten (max. 6 Klicks mit großzügigem Zeitfenster). Wer mit nur einer Hand
 *   1 QTE meistert, bekommt die zweite Hand sofort zurück!
 * * Am Ende über der Landezone des Felds: Solvane dreht ihren Körper per Fassrolle auf den Rücken, sodass die Reiter
 *   natürlich von ihr abrutschen und in den Schnee fallen, während sie sich wieder aufrichtet.
 */
class DragonRide(private val f: EirholmFight) {

    private class Event(val triggerS: Double, val kind: Qte.Kind, val count: Int, val ticks: Int, val text: String)

    private fun send(grip: Int, player: ServerPlayerEntity) {
        val payload = RideGripS2C(player.id, grip)
        for (p in f.audience()) if (ServerPlayNetworking.canSend(p, RideGripS2C.ID)) ServerPlayNetworking.send(p, payload)
    }

    private fun sendAction(player: ServerPlayerEntity, action: Int, ticks: Int) {
        val payload = RideActionS2C(player.id, action, ticks)
        for (p in f.audience()) if (ServerPlayNetworking.canSend(p, RideActionS2C.ID)) ServerPlayNetworking.send(p, payload)
    }

    private fun cue(id: String, at: Vec3d, volume: Float = 3f, pitch: Float = 1f) = f.ambient(id, at, volume, pitch)

    private fun actionFor(kind: Qte.Kind): Int = when (kind) {
        Qte.Kind.SNEAK -> RideAction.DUCK
        Qte.Kind.JUMP -> RideAction.VAULT
        Qte.Kind.USE -> RideAction.BRACE
        Qte.Kind.MASH, Qte.Kind.ATTACK -> RideAction.TUG
    }

    fun play(neckOf: (NivarisEntity) -> Vec3d): Script = script {
        val a = f.arius ?: return@script
        val d = f.nivaris ?: return@script
        RideSeats.assign(f)
        val length = RideRoute.length
        val dropStart = (length - 78.0).coerceAtLeast(length * 0.90)
        // Ruhigeres, flüssiges Tempo je Abschnitt; Gesamtdauer mindestens MIN_TICKS.
        fun pace(u: Double) = when {
            u < RideRoute.stormFrom -> 0.95
            u < RideRoute.diveFrom -> 0.82
            u < 0.94 -> 1.25
            else -> 0.68
        }
        var est = 0.0
        run {
            var s = 0.0
            while (s < dropStart) { s += pace(s / length); est++ }
        }
        val scale = if (est < MIN_TICKS) est / MIN_TICKS else 1.0
        val grips = HashMap<UUID, Int>()
        for (p in f.players()) if (p.vehicle === d) { grips[p.uuid] = 2; send(2, p) }

        // Einstieg in die Route weit außerhalb des Horsts (kein enges Wenden oder 360°-Schleifen im Kessel).
        val join = 115.0
        val events = buildEvents(length, join, dropStart, ::pace, scale)
        var nextEvent = 0
        val rounds = ArrayList<Triple<Qte.Round, Int, Qte.Kind>>()
        var mood = "ride"
        MusicCue.play(f, mood, 20)
        f.atmos(dev.henny.hha_arius.net.AtmosMode.SNOW, 40)
        d.flying = true
        d.bodyRoll = 0f
        d.play(NivarisPose.GLIDE)
        RideSeats.perch(f, a, d)

        // Sanfter, weiter Bogen aus dem Horst heraus auf die Route (Abstand > 2× Wendekreis: keine Extraschleife).
        val aerie = f.abs(Vec3d.of(ArenaLayout.AERIE)).add(0.5, 0.0, 0.5)
        val eastOut = aerie.add(56.0, 18.0, 4.0)
        val entryPre = f.abs(RideRoute.at(join - 28.0))
        val entryPt = f.abs(RideRoute.at(join))
        val leadPts = ArrayList<Vec3d>()
        if (d.entityPos.squaredDistanceTo(eastOut) > 30.0 * 30.0) leadPts += eastOut
        leadPts += entryPre
        leadPts += entryPt
        val leadRaw = DragonRail.path(d.entityPos, DragonRail.heading(f, d).let { Vec3d(it.x, it.y.coerceIn(-0.15, 0.28), it.z) }, leadPts, 28.0)
        val lead = DragonRail.clearTerrain(leadRaw, f.origin, f.floorY, clearance = 8.0)
        val leadLen = DragonRail.length(lead)
        var q = 0.0
        while (q < leadLen) {
            q = minOf(leadLen, q + 0.92)
            val at = DragonRail.at(lead, q)
            f.place(d, at)
            val next = DragonRail.at(lead, q + 3.5)
            if (next.squaredDistanceTo(at) > 0.01) f.face(d, next)
            lockArius(a, d)
            for (p in f.players()) if (p.uuid in grips && p.vehicle !== d) p.startRiding(d, true, true)
            yield(Unit)
        }
        var s = join
        var tick = 0
        var stormOn = false
        var diveOn = false
        var prev = f.abs(RideRoute.at(join))
        while (s < dropStart) {
            val u = s / length
            s = minOf(dropStart, s + pace(u) * scale)
            tick++
            val pos = f.abs(RideRoute.at(s))
            val ahead = f.abs(RideRoute.at(minOf(length, s + 6.0)))
            f.place(d, pos)
            val look = if (ahead.squaredDistanceTo(pos) > 1e-4) ahead else pos.add(pos.subtract(prev))
            f.face(d, look)
            prev = pos
            lockArius(a, d)
            // Wer noch hält, aber abgerutscht ist, sitzt wieder auf; Nachzügler bekommen ihren Platz.
            for (p in f.players()) if (p.uuid in grips && p.vehicle !== d) {
                RideSeats.assign(f)
                p.startRiding(d, true, true)
            }
            if (tick % 26 == 0) cue("nivaris.wing", pos, 4f, 0.75f + (tick % 52) * 0.002f)
            if (tick % 320 == 120) cue("nivaris.roar", pos, 5f, 0.85f)
            if (!stormOn && u >= RideRoute.stormFrom) {
                stormOn = true
                mood = "storm"
                MusicCue.play(f.audience().filter { p -> rounds.none { p.uuid in it.first.players } }, mood, 40)
                f.atmos(dev.henny.hha_arius.net.AtmosMode.AERIE, 30)
                cue("nivaris.roar", pos, 5f, 0.7f)
            }
            if (!diveOn && u >= RideRoute.diveFrom) {
                diveOn = true
                mood = "dive"
                MusicCue.play(f.audience().filter { p -> rounds.none { p.uuid in it.first.players } }, mood, 30)
                f.atmos(dev.henny.hha_arius.net.AtmosMode.VAULT, 60)
                d.play(NivarisPose.DIVE, 20)
            }
            // Quick-Time-Events: faire Fenster auch mit einer Hand; 1 Erfolg holt die zweite Hand zurück!
            while (nextEvent < events.size && s >= events[nextEvent].triggerS) {
                val ev = events[nextEvent++]
                val riders = f.players().filter { it.vehicle === d }
                for (grip in intArrayOf(2, 1)) {
                    val group = riders.filter { (grips[it.uuid] ?: 2) == grip }
                    if (group.isEmpty()) continue
                    val hard = grip == 1
                    val ticks = if (hard && ev.kind != Qte.Kind.MASH) (ev.ticks * 0.92).toInt().coerceAtLeast(42) else ev.ticks
                    val count = if (hard && ev.kind == Qte.Kind.MASH) minOf(ev.count + 1, 6) else ev.count
                    rounds += Triple(Qte.start(group, ev.kind, ticks, count, ev.text), tick + ticks, ev.kind)
                    MusicCue.play(group, "qte", 6)
                }
                if (riders.isNotEmpty()) f.calls.roomShake(pos, 2.2f, 40f)
            }
            val it = rounds.iterator()
            while (it.hasNext()) {
                val (r, end, kind) = it.next()
                if (tick < end && !r.done()) continue
                Qte.close(r)
                it.remove()
                for (p in f.players()) {
                    if (p.uuid !in r.players) continue
                    MusicCue.play(p, mood, 14)
                    if (r.success(p)) {
                        MusicCue.sting(listOf(p), "qte_ok")
                        sendAction(p, actionFor(kind), 28)
                        // Erholung auf beide Hände nach einem gemeisterten QTE mit nur einer Hand!
                        if ((grips[p.uuid] ?: 2) < 2) {
                            grips[p.uuid] = 2
                            send(2, p)
                            cue("resonance.chime", p.entityPos, 1.8f, 1.35f)
                            p.sendMessage(Text.translatable("hha_arius.msg.ride_recover").formatted(Formatting.AQUA), true)
                        }
                        continue
                    }
                    val first = (grips[p.uuid] ?: 2) >= 2
                    grips[p.uuid] = 1
                    send(1, p)
                    f.calls.hitShake(p.entityPos, 3f, 6f)
                    MusicCue.sting(listOf(p), "qte_fail")
                    slipDamage(p, d, first)
                    p.sendMessage(Text.translatable(if (first) "hha_arius.msg.ride_slip" else "hha_arius.msg.ride_slip_again").formatted(Formatting.GOLD), true)
                }
            }
            // Reitet niemand mehr: den Rest abkürzen.
            if (tick > 40 && f.players().none { it.vehicle === d }) {
                s = maxOf(s, dropStart)
            }
            yield(Unit)
        }
        for ((r, _, _) in rounds) Qte.close(r)
        rounds.clear()
        // Abwurf-Szene über dem Feld: Solvane dreht ihren Körper per Fassrolle, sodass alle Reiter natürlich abfallen!
        yieldAll(barrelRollDrop(d, a, dropStart, length))
        d.bodyRoll = 0f
        for (p in f.players()) send(0, p)
        RideSeats.unperch(f, a)
        RideSeats.clear(f)
    }

    /** Hält Arius während des gesamten Ritts exakt und nach vorn gerichtet auf Solvanes Kopf verankert. */
    private fun lockArius(a: AriusEntity, d: NivarisEntity) {
        if (a.vehicle !== d) RideSeats.perch(f, a, d)
        a.yaw = d.yaw
        a.bodyYaw = d.bodyYaw
        a.headYaw = d.headYaw
        if (a.pose != AriusPose.SEATED) a.play(AriusPose.SEATED)
    }

    /**
     * Erstellt die QTE-Folge: jedes dauerhafte Bauwerk ([RideLandmarks]) bekommt seinen exakt getimten Ausweich-Prompt
     * (Ducken unter Bögen, Hochziehen über Eisgrate), dazwischen faire Festhalte-/Klammer-Prompts (höchstens 6 Klicks).
     */
    private fun buildEvents(length: Double, join: Double, dropStart: Double, pace: (Double) -> Double, scale: Double): List<Event> {
        val out = ArrayList<Event>()
        val landmarks = RideLandmarks.all
        for (lm in landmarks) {
            val kind = if (lm.kind == RideLandmarks.Kind.ICE_RIDGE) Qte.Kind.JUMP else Qte.Kind.SNEAK
            val ticks = 46
            val leadDist = pace(lm.u) * scale * ticks * 0.88
            val trig = (lm.s - leadDist).coerceIn(join + 18.0, dropStart - 40.0)
            out += Event(trig, kind, 1, ticks, lm.qteKey)
        }
        val extraSpecs = listOf(
            Triple(0.11, Qte.Kind.MASH, 4 to "hha_arius.qte.hold_on"),
            Triple(0.28, Qte.Kind.MASH, 5 to "hha_arius.qte.hold_turn"),
            Triple(0.46, Qte.Kind.MASH, 5 to "hha_arius.qte.ride_ice"),
            Triple(RideRoute.stormFrom + 0.05, Qte.Kind.MASH, 5 to "hha_arius.qte.hold_storm"),
            Triple(RideRoute.diveFrom + 0.02, Qte.Kind.USE, 1 to "hha_arius.qte.ride_brace"),
        )
        for ((u, kind, pair) in extraSpecs) {
            val trig = (u * length).coerceIn(join + 14.0, dropStart - 44.0)
            // Genug Abstand zu den dauerhaften Hindernis-Prompts lassen, damit sich nichts überschneidet.
            if (out.any { kotlin.math.abs(it.triggerS - trig) < 55.0 }) continue
            val ticks = if (kind == Qte.Kind.MASH) 54 else 44
            out += Event(trig, kind, pair.first, ticks, pair.second)
        }
        out.sortBy { it.triggerS }
        return out
    }

    /**
     * Abwurf am Feld (Fassrolle): Solvane gleitet tief über die Landezone, dreht ihren ganzen Körper weich um 180° auf
     * den Rücken ([NivarisEntity.bodyRoll]), sodass die Reiter natürlich von den Dornen rutschen und in den Schnee fallen;
     * währenddessen richtet sie sich mit einer vollen 360°-Rolle wieder auf und gleitet zum Liegeplatz, Arius springt ab.
     */
    private fun barrelRollDrop(d: NivarisEntity, a: AriusEntity, dropStart: Double, length: Double): Script = script {
        val vault = f.abs(Vec3d.of(ArenaLayout.VAULT)).add(0.5, 0.0, 0.5)
        val finish = {
            d.bodyRoll = 0f
            for ((j, p) in f.players().withIndex()) {
                if (p.vehicle === d) p.stopRiding()
                p.removeStatusEffect(StatusEffects.SLOW_FALLING)
                send(0, p)
                val spot = f.abs(ArenaLayout.VAULT_LANDING[j % ArenaLayout.VAULT_LANDING.size])
                if (p.squaredDistanceTo(spot) > 12.0 * 12.0 || p.y > spot.y + 4.0) {
                    p.teleport(f.world, spot.x, spot.y, spot.z, emptySet(), 180f, 0f, false)
                }
                p.fallDistance = 0.0
                p.onLanding()
            }
            RideSeats.unperch(f, a)
            RideSeats.clear(f)
        }
        val scene = CineScene(f) { finish() }
        yieldAll(scene.play(script {
            val totalTicks = 48
            val dropTick = 20
            val ariusJumpTick = 30
            val startPos = f.abs(RideRoute.at(dropStart))
            val dropPos = f.abs(RideRoute.at(min(length, dropStart + (length - dropStart) * (dropTick / totalTicks.toDouble()))))
            val land0 = f.abs(ArenaLayout.VAULT_LANDING[0])
            val fwd = dropPos.subtract(startPos).let { Vec3d(it.x, 0.0, it.z) }.let { if (it.lengthSquared() < 1e-4) Vec3d(0.0, 0.0, -1.0) else it.normalize() }
            val side = Vec3d(-fwd.z, 0.0, fwd.x)
            // Kamera schräg seitlich-hinten über der Landezone: man sieht Solvanes Körper sich drehen und den eigenen Sturz in den Schnee.
            val camFrom = dropPos.subtract(fwd.multiply(16.0)).add(side.multiply(18.0)).add(0.0, 5.5, 0.0)
            val camTo = land0.subtract(fwd.multiply(10.0)).add(side.multiply(15.0)).add(0.0, 3.8, 0.0)
            scene.line(camFrom, camTo, dropPos.add(0.0, 1.5, 0.0), land0.add(0.0, 1.2, 0.0), totalTicks, 66.0, 58.0)
            d.play(NivarisPose.GLIDE)
            cue("nivaris.wing", startPos, 5f, 0.72f)
            var dropped = false
            var ariusFrom: Vec3d? = null
            val ariusTarget = vault.add(0.0, 7.0, 0.0)
            val landed = HashSet<UUID>()
            for (t in 1..totalTicks) {
                val u = t / totalTicks.toDouble()
                val curS = dropStart + (length - dropStart) * u
                val pos = f.abs(RideRoute.at(curS))
                val ahead = f.abs(RideRoute.at(min(length, curS + 5.0)))
                f.place(d, pos)
                if (ahead.squaredDistanceTo(pos) > 0.01) f.face(d, ahead)
                // Fassrolle: Ticks 5..25 dreht sie sich 0° -> 180° (Rücken nach unten -> Reiter fallen ab),
                // Ticks 25..45 dreht sie 180° -> 360° zurück in Normal-Lage.
                val roll = when {
                    t < 5 -> 0f
                    t <= 45 -> {
                        val r = ((t - 5) / 40.0).coerceIn(0.0, 1.0)
                        val eased = r * r * (3.0 - 2.0 * r)
                        (eased * 2.0 * PI).toFloat()
                    }
                    else -> 0f
                }
                d.bodyRoll = roll
                if (t == 12) {
                    cue("nivaris.roar", pos, 4.5f, 0.9f)
                    f.calls.roomShake(pos, 2.8f, 50f)
                }
                if (t < ariusJumpTick) {
                    lockArius(a, d)
                } else {
                    if (ariusFrom == null) {
                        RideSeats.unperch(f, a)
                        ariusFrom = a.entityPos
                        a.play(AriusPose.LAUNCH, 6)
                        cue("arius.wind_step", a.entityPos, 3f, 0.85f)
                    }
                    val ak = ((t - ariusJumpTick) / 14.0).coerceIn(0.0, 1.0)
                    f.place(a, ariusFrom!!.lerp(ariusTarget, ak).add(0.0, sin(ak * PI) * 2.8, 0.0))
                    f.face(a, land0)
                }
                // Abwurf genau beim Kippen auf den Rücken (ca. 135°..150°): Spieler gleiten natürlich herunter!
                if (!dropped && t == dropTick) {
                    dropped = true
                    cue("nivaris.wing", pos, 4.5f, 0.95f)
                    for ((j, p) in f.players().withIndex()) {
                        if (p.vehicle === d) p.stopRiding()
                        p.removeStatusEffect(StatusEffects.SLOW_FALLING)
                        send(0, p)
                        val spot = f.abs(ArenaLayout.VAULT_LANDING[j % ArenaLayout.VAULT_LANDING.size])
                        val dropOrigin = Vec3d(p.x, max(p.y, spot.y + 3.0), p.z)
                        val vel = Ballistic.velocity(dropOrigin, spot, 0.08)
                        Fx.launchPlayer(p, vel)
                        p.fallDistance = 0.0
                    }
                    RideSeats.clear(f)
                }
                // Aufprall im Schnee (Staub & Klang, ohne Fallschaden).
                if (dropped) {
                    for (p in f.players()) {
                        p.fallDistance = 0.0
                        if (p.uuid !in landed && (p.isOnGround || p.y <= land0.y + 0.35)) {
                            landed += p.uuid
                            p.onLanding()
                            f.world.spawnParticles(
                                BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.SNOW_BLOCK.defaultState),
                                p.x, p.y + 0.2, p.z, 22, 0.65, 0.2, 0.65, 0.14,
                            )
                            cue("hold.impact", p.entityPos, 1.4f, 0.85f)
                        }
                    }
                }
                yield(Unit)
            }
            d.bodyRoll = 0f
        }))
        finish()
    }

    /**
     * Schaden bei einem verpassten Prompt: Anteil der maximalen Gesundheit (die eigene Schadensart umgeht Rüstung,
     * Resistenz wirkt). Der erste Fehler lässt immer mindestens ein Herz übrig.
     */
    private fun slipDamage(p: ServerPlayerEntity, d: NivarisEntity, first: Boolean) {
        var amount = p.maxHealth * (if (first) 0.22f else 0.3f)
        if (first) amount = minOf(amount, p.health - 1f)
        if (amount <= 0.05f) return
        p.timeUntilRegen = 0
        p.damage(f.world, f.world.damageSources.create(Damage.Kind.IMPACT.key, d), amount)
    }

    companion object {
        /** Mindestdauer des Ritts in Ticks (65 s). */
        const val MIN_TICKS = 1300.0
    }
}


/**
 * Wo die Reiter auf Solvanes Rücken hängen (seit 1.10.0, Crix: „man soll sich am Eisdorn auf ihrem Rücken festhalten,
 * die Beine flattern im Wind“). Reiter `i` greift die Kristallplatte (Modellteil `crest`) von Glied [glied] und liegt
 * waagerecht im Fahrtwind dahinter. Der Client kennt den gezeichneten Körper und setzt die Reiter dorthin
 * ([riderPos]); der Server nimmt einen gestreckten Körper an ([serverOffset]).
 */
object RideHooks {
    /** Client-Haken: Position des Reiters (Füße der Entity) am echten Dorn, null = Server-Näherung. */
    @JvmField
    var riderPos: ((NivarisEntity, net.minecraft.entity.Entity) -> Vec3d?)? = null

    /** Rückenglied mit Kristallplatte für Platz [i] (0..7): 4, 6, 8 … 18, also der erste Dorn und die direkt dahinter. */
    fun glied(i: Int): Int = 4 + 2 * i.coerceIn(0, 7)

    /** Client-Haken: Platz eines Mitfahrers nach [RideSeatS2C] (null = unbekannt). */
    @JvmField
    var clientSeat: ((net.minecraft.entity.Entity) -> Int?)? = null

    /** Platz von [passenger]: fester Platz, sonst die Stelle unter den Spielern auf ihr. */
    fun seatOf(d: NivarisEntity, passenger: net.minecraft.entity.Entity): Int {
        val fixed = if (d.entityWorld.isClient) clientSeat?.invoke(passenger) else (passenger as? ServerPlayerEntity)?.let { RideSeats.of(it.uuid) }
        if (fixed != null) return fixed
        if (passenger is AriusEntity) return RideSeats.HEAD
        return d.passengerList.filter { it !is AriusEntity }.indexOf(passenger).coerceAtLeast(0)
    }

    /** Versatz der Mitfahrer auf dem Server (Arius über dem Kopfgelenk, Spieler an ihrem Dorn). */
    fun offsetFor(d: NivarisEntity, passenger: net.minecraft.entity.Entity): Vec3d {
        val seat = seatOf(d, passenger)
        if (seat == RideSeats.HEAD) {
            val h = NivarisShape.head(d.pose, d.flying)
            return Vec3d(0.0, h.up + 0.5, h.fwd)
        }
        return serverOffset(seat)
    }

    /** Augenhöhe eines stehenden Spielers (die Entity bleibt „stehend“, nur die Darstellung liegt). */
    const val EYE = 1.62

    /** Lokaler Versatz (x seitlich, y hoch, z vorn) der Reiter-Füße vom Entity-Punkt bei gestrecktem Körper. */
    fun serverOffset(i: Int): Vec3d {
        val g = glied(i)
        val back = (g - 3) * NivarisShape.SEGMENT + 2.2 + 0.4
        val up = NivarisShape.SPINE_UP + 2.3 * NivarisShape.taper(g) - 0.25 - EYE
        return Vec3d(0.0, up, -back)
    }
}
