package dev.henny.hha_arius.boss

import dev.henny.hha.logic.Vfx
import dev.henny.hha_arius.HhaArius
import dev.henny.hha_arius.arena.ArenaLayout
import dev.henny.hha_arius.arena.FarPlan
import dev.henny.hha_arius.net.FxKind
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.entity.effect.StatusEffectInstance
import net.minecraft.entity.effect.StatusEffects
import net.minecraft.network.RegistryByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.math.Vec3d
import java.util.UUID
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

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

    /** Arius setzt sich auf ihren Kopf: als Mitfahrer, damit er ohne Versatz mit ihr fliegt. */
    fun perch(f: EirholmFight, a: AriusEntity, d: NivarisEntity) {
        if (a.vehicle !== d) a.startRiding(d, true, true)
        a.play(AriusPose.SEATED)
        broadcast(f, a.id, HEAD)
    }

    fun unperch(f: EirholmFight, a: AriusEntity) {
        if (a.hasVehicle()) a.stopRiding()
        broadcast(f, a.id, NONE)
    }

    /** Ritt vorbei: alle Plätze frei. */
    fun clear(f: EirholmFight) {
        for (id in f.fighters.keys) {
            f.server.playerManager.getPlayer(id)?.let { broadcast(f, it.id, NONE) }
            seats.remove(id)
        }
    }
}

/**
 * Flugbahn des Ritts (seit 1.8.1, Crix: „der Ritt ist viel zu kurz, mindestens eine Minute“): aus dem Horst nach Süden
 * an die Westflanke des Massivs, in weitem Bogen um den ganzen Berg (West → Süd → Ost), dann in einer Spirale durch den
 * Sturm um das Felshorn hinauf und im Sturzflug nach Norden aufs Feld. Alle Punkte hängen an [ArenaLayout] bzw.
 * [FarPlan] (Bergachse = [ArenaLayout.SPIRE], Höhe der Flanke = [FarPlan.massifH]); das Gelände unter der Bahn wird
 * abgetastet, damit sie immer mindestens [CLEAR] Blöcke darüber bleibt. Koordinaten relativ zum Instanz-Ursprung.
 */
object RideRoute {
    private const val CLEAR = 16.0

    /** Kleinster Kurvenradius der Route. */
    private const val TURN = 30.0
    private const val STEP = 0.5

    /** Abschnitte (Anteile der Bogenlänge), an denen Sturm und Wetter wechseln. */
    var stormFrom = 0.6
        private set
    var diveFrom = 0.85
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
        ctrl += aerie.add(0.0, 6.0, 0.0)
        ctrl += aerie.add(14.0, 18.0, 22.0)
        // Westflanke → Süden → Osten: weiter Bogen um das Massiv, nah an der Flanke.
        val a0 = atan2(aerie.x - ax, aerie.z - az) + 0.55
        val r0 = FarPlan.MASSIF_R - 6.0
        val turns = 4.9
        val n = 11
        for (k in 0..n) {
            val u = k / n.toDouble()
            val a = a0 + turns * u
            val r = r0 - 42.0 * u
            ctrl += around(a, r, 24.0 + 6.0 * sin(u * PI * 3))
        }
        val stormIndex = ctrl.size
        // Durch den Sturm: Spirale um das Horn hinauf bis über die Schulter.
        val a1 = a0 + turns
        val m = 9
        for (k in 1..m) {
            val u = k / m.toDouble()
            val a = a1 + 1.75 * PI * u
            val r = (FarPlan.MASSIF_R - 48.0) + (FarPlan.SHOULDER_R + 30.0 - (FarPlan.MASSIF_R - 48.0)) * u
            ctrl += around(a, r, 26.0 + 10.0 * u)
        }
        val diveIndex = ctrl.size
        // Sturzflug nach Norden aufs Feld, flach über das Feld zum Liegeplatz.
        val top = ctrl.last()
        ctrl += top.lerp(vault, 0.45).add(0.0, 12.0, 0.0)
        ctrl += vault.add(0.0, 14.0, 34.0)
        ctrl += vault.add(0.0, 6.0, 8.0)
        ctrl += rest.add(0.0, 2.0, 0.0)

        // Seit 1.11.0 über [DragonRail]: Der Kurvenradius bleibt groß, kein Punkt zwingt sie in eine enge Schleife
        // (Crix: „der Körper dreht sich in 2 Sekunden fünfmal um 360 Grad“). Danach Gelände-Abstand, gleiche Bogenlänge.
        val fine = ArrayList(DragonRail.path(ctrl[0], ctrl[1].subtract(ctrl[0]), ctrl.drop(1), TURN))
        val tags = ArrayList<Int>(fine.size)
        run {
            var c = 0
            for (q in fine) {
                while (c < ctrl.size - 1 && q.squaredDistanceTo(ctrl[c + 1]) <= q.squaredDistanceTo(ctrl[c])) c++
                tags += c
            }
        }
        // Gelände: mindestens CLEAR über dem höchsten Punkt im Umkreis (außer im Endanflug aufs Feld).
        val landing = (fine.size - (ctrl.last().distanceTo(ctrl[ctrl.size - 2]) / DragonRail.STEP).toInt() - 1).coerceAtLeast(0)
        val ys = DoubleArray(fine.size) { fine[it].y }
        for (i in fine.indices) {
            if (i >= landing) continue
            val q = fine[i]
            var top0 = -1000
            for (dx in intArrayOf(-6, 0, 6)) for (dz in intArrayOf(-6, 0, 6)) {
                top0 = maxOf(top0, FarPlan.top(Math.floor(q.x).toInt() + dx, Math.floor(q.z).toInt() + dz))
            }
            ys[i] = maxOf(ys[i], top0 + CLEAR)
        }
        // Höhe glätten (gleitender Mittelwert über ±20 Blöcke, nie unter das Gelände-Minimum zurück).
        val sm = DoubleArray(ys.size)
        for (i in ys.indices) {
            var s = 0.0
            var c = 0
            for (k in maxOf(0, i - 40)..minOf(ys.size - 1, i + 40)) { s += ys[k]; c++ }
            sm[i] = if (i >= landing) fine[i].y else maxOf(ys[i], s / c)
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
        stormFrom = (stormAt / acc).coerceIn(0.3, 0.9)
        diveFrom = (diveAt / acc).coerceIn(stormFrom + 0.05, 0.97)
        return out
    }
}

/**
 * Der Ritt auf Solvanes Rücken zwischen Abschnitt II und III (seit 1.8.1 neu, Crix: „viel zu kurz, man soll nicht
 * sitzen, sondern sich an einem Eisdorn auf ihrem Rücken festhalten“):
 * * Mindestens [MIN_TICKS] (65 s) entlang [RideRoute], Tempo je Abschnitt (Sturm langsamer, Sturzflug schneller).
 * * Jeder Reiter hält sich mit **beiden Händen** an der Kristallplatte seines Rückenglieds fest und liegt im Fahrtwind
 *   (Haltung im Client). Etwa alle sieben Sekunden ein Quick-Time-Event (Hämmern, Ducken, Springen, Klick).
 * * Seit 1.10.0 (Crix: „bei einem Fehler soll man Schaden nehmen, statt dass nichts passiert“): Jeder Fehlschlag kostet
 *   Leben nach Anteil der maximalen Gesundheit (erst 22 %, danach 30 %), der erste nie tödlich. Nach dem ersten Fehler
 *   hält nur noch eine Hand, alle folgenden Prompts sind kürzer und haben mehr Anschläge. Niemand fällt mehr ab.
 * * Musik: Ritt, Sturm und Sturzflug je eigener Zustand; während eines Prompts duckt sich der Ritt unter die
 *   Spannungsschicht ([MusicCue] `qte`), Erfolg und Fehler bekommen einen Stinger.
 * * Reitet niemand mehr, kürzt sie den Rest ab.
 */
class DragonRide(private val f: EirholmFight) {

    /** Was bei einem Prompt sichtbar passiert. */
    private enum class Stage { NONE, ROCK_ARCH, ICE_ARCH, RIDGE }

    private class Event(val at: Double, val kind: Qte.Kind, val count: Int, val ticks: Int, val text: String, val stage: Stage = Stage.NONE)

    private val shards = Shards(f)

    /**
     * Baut das Hindernis zum Prompt auf die Bahn, genau dorthin, wo sie am Ende des Fensters ist (Seit 1.11.0, Crix: „warum
     * man sich ducken oder springen soll, soll zur Situation passen“): ein Felsbogen oder Eisbogen zum Ducken, ein Eisgrat
     * zum Hochziehen. Nur Block-Anzeigen ohne Kollision, sie zerfallen, nachdem sie vorbei ist.
     */
    private fun stage(kind: Stage, s: Double, step: Double, ticks: Int) {
        if (kind == Stage.NONE) return
        val length = RideRoute.length
        val reach = minOf(length, s + step * ticks)
        val at = f.abs(RideRoute.at(reach)).add(0.0, NivarisShape.SPINE_UP, 0.0)
        val next = f.abs(RideRoute.at(minOf(length, reach + 3.0))).add(0.0, NivarisShape.SPINE_UP, 0.0)
        val flat = Vec3d(next.x - at.x, 0.0, next.z - at.z).let { if (it.lengthSquared() < 1e-6) Vec3d(0.0, 0.0, 1.0) else it.normalize() }
        val side = Vec3d(flat.z, 0.0, -flat.x)
        val rot = org.joml.Quaternionf().rotateY(atan2(flat.x, flat.z).toFloat())
        val life = ticks + 46
        when (kind) {
            Stage.ROCK_ARCH, Stage.ICE_ARCH -> {
                val state = if (kind == Stage.ROCK_ARCH) net.minecraft.block.Blocks.DEEPSLATE.defaultState else net.minecraft.block.Blocks.PACKED_ICE.defaultState
                val cap = if (kind == Stage.ROCK_ARCH) net.minecraft.block.Blocks.SNOW_BLOCK.defaultState else net.minecraft.block.Blocks.BLUE_ICE.defaultState
                // Sturz knapp über den Reitern, Pfeiler außerhalb der Schwingen bis unter die Bahn.
                shards.chunk(state, at.add(0.0, 8.2, 0.0), org.joml.Vector3f(34f, 4.5f, 7f), rot, life, 6)
                shards.chunk(cap, at.add(0.0, 10.8, 0.0), org.joml.Vector3f(36f, 1.2f, 8f), rot, life, 6)
                for (sgn in intArrayOf(-1, 1)) shards.chunk(state, at.add(side.multiply(15.0 * sgn)).add(0.0, -7.0, 0.0), org.joml.Vector3f(6f, 34f, 6.5f), rot, life, 6)
            }
            Stage.RIDGE -> {
                val ice = net.minecraft.block.Blocks.PACKED_ICE.defaultState
                val blue = net.minecraft.block.Blocks.BLUE_ICE.defaultState
                // Ein Grat quer zur Bahn, dessen Kamm genau unter ihrem Bauch liegt; außen ragen die Zacken höher.
                for (k in -6..6) {
                    val off = k * 4.2
                    val top = -1.4 + (kotlin.math.abs(k) * 0.9).coerceAtMost(5.5)
                    val h = 16.0
                    val tilt = org.joml.Quaternionf(rot).rotateZ((k * 0.05).toFloat())
                    shards.chunk(if (k % 2 == 0) ice else blue, at.add(side.multiply(off)).add(0.0, top - h / 2.0, 0.0), org.joml.Vector3f(4.6f, h.toFloat(), 5f), tilt, life, 6)
                }
            }
            Stage.NONE -> Unit
        }
    }

    private fun send(grip: Int, player: ServerPlayerEntity) {
        val payload = RideGripS2C(player.id, grip)
        for (p in f.audience()) if (ServerPlayNetworking.canSend(p, RideGripS2C.ID)) ServerPlayNetworking.send(p, payload)
    }

    private fun cue(id: String, at: Vec3d, volume: Float = 3f, pitch: Float = 1f) = f.ambient(id, at, volume, pitch)

    fun play(neckOf: (NivarisEntity) -> Vec3d): Script = script {
        val a = f.arius ?: return@script
        val d = f.nivaris ?: return@script
        RideSeats.assign(f)
        val length = RideRoute.length
        // Tempo je Abschnitt; die Gesamtdauer wird auf mindestens MIN_TICKS gestreckt.
        fun pace(u: Double) = when {
            u < RideRoute.stormFrom -> 1.25
            u < RideRoute.diveFrom -> 1.0
            u < 0.97 -> 1.9
            else -> 0.7
        }
        var est = 0.0
        run {
            var s = 0.0
            while (s < length) { s += pace(s / length); est++ }
        }
        val scale = if (est < MIN_TICKS) est / MIN_TICKS else 1.0
        val grips = HashMap<UUID, Int>()
        val fallen = HashMap<UUID, Int>()
        for (p in f.players()) if (p.vehicle === d) { grips[p.uuid] = 2; send(2, p) }
        // Jeder Prompt hängt an etwas, das man in dem Moment sieht oder spürt.
        val events = listOf(
            Event(0.07, Qte.Kind.MASH, 6, 44, "hha_arius.qte.hold_on"),
            Event(0.17, Qte.Kind.SNEAK, 1, 36, "hha_arius.qte.ride_arch", Stage.ROCK_ARCH),
            Event(0.27, Qte.Kind.MASH, 7, 42, "hha_arius.qte.hold_turn"),
            Event(0.37, Qte.Kind.JUMP, 1, 36, "hha_arius.qte.ride_ridge", Stage.RIDGE),
            Event(0.47, Qte.Kind.MASH, 8, 46, "hha_arius.qte.ride_ice"),
            Event(RideRoute.stormFrom + 0.03, Qte.Kind.MASH, 9, 44, "hha_arius.qte.hold_storm"),
            Event((RideRoute.stormFrom + RideRoute.diveFrom) / 2, Qte.Kind.SNEAK, 1, 34, "hha_arius.qte.ride_arch_ice", Stage.ICE_ARCH),
            Event(RideRoute.diveFrom + 0.01, Qte.Kind.MASH, 10, 44, "hha_arius.qte.hold_dive"),
            Event(0.94, Qte.Kind.USE, 1, 30, "hha_arius.qte.ride_brace"),
        )
        var nextEvent = 0
        val rounds = ArrayList<Pair<Qte.Round, Int>>()
        var mood = "ride"
        MusicCue.play(f, mood, 20)
        f.atmos(dev.henny.hha_arius.net.AtmosMode.SNOW, 40)
        d.flying = true
        d.play(NivarisPose.GLIDE)
        RideSeats.perch(f, a, d)
        // Sie fliegt von dort, wo die Szene sie gelassen hat, über eine Bahn auf die Route (vorher sprang sie an den Start).
        val join = 30.0
        val lead = DragonRail.path(d.entityPos, DragonRail.heading(f, d).add(0.0, 0.2, 0.0), listOf(f.abs(RideRoute.at(join))), DragonRail.MIN_RADIUS)
        val leadLen = DragonRail.length(lead)
        var q = 0.0
        while (q < leadLen) {
            q = minOf(leadLen, q + 1.25)
            val at = DragonRail.at(lead, q)
            f.place(d, at)
            val next = DragonRail.at(lead, q + 3.0)
            if (next.squaredDistanceTo(at) > 0.01) f.face(d, next)
            for (p in f.players()) if (p.uuid in grips && p.vehicle !== d) p.startRiding(d, true, true)
            yield(Unit)
        }
        var s = join
        var tick = 0
        var stormOn = false
        var diveOn = false
        var prev = f.abs(RideRoute.at(join))
        while (s < length) {
            val u = s / length
            s = minOf(length, s + pace(u) * scale)
            tick++
            val pos = f.abs(RideRoute.at(s))
            val ahead = f.abs(RideRoute.at(minOf(length, s + 6.0)))
            f.place(d, pos)
            val look = if (ahead.squaredDistanceTo(pos) > 1e-4) ahead else pos.add(pos.subtract(prev))
            f.face(d, look)
            prev = pos
            shards.tick()
            if (a.vehicle !== d) RideSeats.perch(f, a, d)
            // Wer noch hält, aber abgerutscht ist (Absteigen), sitzt wieder auf; Nachzügler bekommen ihren Platz.
            for (p in f.players()) if (p.uuid in grips && p.uuid !in fallen && p.vehicle !== d) {
                RideSeats.assign(f)
                p.startRiding(d, true, true)
            }
            if (tick % 24 == 0) cue("nivaris.wing", pos, 4f, 0.75f + (tick % 48) * 0.002f)
            if (tick % 300 == 120) cue("nivaris.roar", pos, 5f, 0.85f)
            if (!stormOn && u >= RideRoute.stormFrom) {
                stormOn = true
                mood = "storm"
                MusicCue.play(f.audience().filter { p -> rounds.none { p.uuid in it.first.players } }, mood, 40)
                // Dunkler Sturmhimmel statt Whiteout: Im Blizzard endet die Sicht nach 30 Blöcken, die Hindernisse kämen
                // aus dem Nichts.
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
            // Quick-Time-Events: je Griffstufe eine eigene Runde (mit nur einer Hand kürzer und mehr Anschläge).
            while (nextEvent < events.size && u >= events[nextEvent].at) {
                val ev = events[nextEvent++]
                val riders = f.players().filter { it.vehicle === d && it.uuid !in fallen }
                for (grip in intArrayOf(2, 1)) {
                    val group = riders.filter { (grips[it.uuid] ?: 2) == grip }
                    if (group.isEmpty()) continue
                    val hard = grip == 1
                    val ticks = if (hard) (ev.ticks * 0.7).toInt() else ev.ticks
                    val count = if (hard && ev.kind == Qte.Kind.MASH) ev.count + 3 else ev.count
                    rounds += Qte.start(group, ev.kind, ticks, count, ev.text) to tick + ticks
                    MusicCue.play(group, "qte", 6)
                }
                if (riders.isNotEmpty()) {
                    f.calls.roomShake(pos, 3f, 40f)
                    stage(ev.stage, s, pace(u) * scale, ev.ticks)
                }
            }
            val it = rounds.iterator()
            while (it.hasNext()) {
                val (r, end) = it.next()
                if (tick < end && !r.done()) continue
                Qte.close(r)
                it.remove()
                for (p in f.players()) {
                    if (p.uuid !in r.players || p.uuid in fallen) continue
                    MusicCue.play(p, mood, 14)
                    if (r.success(p)) {
                        MusicCue.sting(listOf(p), "qte_ok")
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
            // Abgestürzte: nach kurzem Fall fängt sie der Lichtstrom auf und setzt sie am Feld ab.
            for ((j, p) in f.players().withIndex()) {
                val at = fallen[p.uuid] ?: continue
                if (tick - at != 30) continue
                val spot = f.abs(ArenaLayout.VAULT_LANDING[j % ArenaLayout.VAULT_LANDING.size])
                f.fx(FxKind.BLADE_STREAM, p.entityPos, spot, a = 20f, b = 0f, life = 30)
                p.teleport(f.world, spot.x, spot.y + 1.0, spot.z, emptySet(), 180f, 0f, false)
                p.onLanding()
            }
            // Reitet niemand mehr: den Rest abkürzen.
            if (tick > 40 && f.players().none { it.vehicle === d }) {
                s = maxOf(s, length * 0.97)
            }
            yield(Unit)
        }
        for ((r, _) in rounds) Qte.close(r)
        for (p in f.players()) send(0, p)
        shards.clear()
        RideSeats.unperch(f, a)
        RideSeats.clear(f)
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
