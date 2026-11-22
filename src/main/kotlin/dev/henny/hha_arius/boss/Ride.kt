package dev.henny.hha_arius.boss

import dev.henny.hha.logic.Vfx
import dev.henny.hha.logic.boss.Cine
import dev.henny.hha_arius.HhaArius
import dev.henny.hha_arius.arena.ArenaLayout
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
import kotlin.math.abs
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

    /** Vom Hindernis getroffen: Der Körper wird zurückgerissen. */
    const val HIT = 5

    /** Beine und Körper zur linken bzw. rechten Seite geschwungen (an einer Felsnadel vorbei). */
    const val SWING_L = 6
    const val SWING_R = 7
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

/** Rider grip strength (2 secure, 1 loose, 0 dismounted), with the takeoff point for a scripted pickup. */
data class RideGripS2C(val entityId: Int, val grip: Int, val pickup: Vec3d? = null) : CustomPayload {
    override fun getId(): CustomPayload.Id<out CustomPayload> = ID

    companion object {
        val ID = CustomPayload.Id<RideGripS2C>(HhaArius.id("ride_grip"))
        val CODEC: PacketCodec<RegistryByteBuf, RideGripS2C> = PacketCodec.of(
            { v, buf ->
                buf.writeVarInt(v.entityId)
                buf.writeVarInt(v.grip)
                buf.writeBoolean(v.pickup != null)
                v.pickup?.let { buf.writeDouble(it.x); buf.writeDouble(it.y); buf.writeDouble(it.z) }
            },
            { buf ->
                val id = buf.readVarInt()
                val grip = buf.readVarInt()
                RideGripS2C(id, grip, if (buf.readBoolean()) Vec3d(buf.readDouble(), buf.readDouble(), buf.readDouble()) else null)
            },
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

    /** Nur Sichtprüfung (src/gametest): erster vergebener Dorn, um hintere Plätze mit einem Spieler zu prüfen. */
    @JvmField
    var testSeat: Int? = null

    /** Vergibt freie Plätze in Beitrittsreihenfolge und meldet alle Plätze an alle Zuschauer. */
    fun assign(f: EirholmFight) {
        val online = f.fighters.keys.toList()
        for (id in online) {
            if (id in seats || f.server.playerManager.getPlayer(id) == null) continue
            // Wer die Begegnung verlassen hat, behält seinen Dorn: Niemand rückt auf, und er kommt dorthin zurück.
            val taken = online.mapNotNull { seats[it] }
            var free = testSeat ?: 0
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
    /** Publish the visible takeoff point together with the passenger list. */
    fun pickUp(f: EirholmFight, d: NivarisEntity, p: ServerPlayerEntity) {
        val from = p.entityPos
        if (!p.startRiding(d, true, true)) return
        for (watcher in f.audience()) {
            if (ServerPlayNetworking.canSend(watcher, RideGripS2C.ID)) {
                ServerPlayNetworking.send(watcher, RideGripS2C(p.id, 2, from))
            }
        }
        syncPassengers(f, d)
    }

    /** Verzweifeltes Fangen im Fall: Spieler hakt sich mit kurzem Ruck (grip = 1) am Eisdorn ein. */
    fun catchSpike(f: EirholmFight, d: NivarisEntity, p: ServerPlayerEntity) {
        val from = p.entityPos
        Hold.drop(p)
        if (!p.startRiding(d, true, true)) return
        for (watcher in f.audience()) {
            if (ServerPlayNetworking.canSend(watcher, RideGripS2C.ID)) {
                ServerPlayNetworking.send(watcher, RideGripS2C(p.id, 1, from))
            }
            if (ServerPlayNetworking.canSend(watcher, RideActionS2C.ID)) {
                ServerPlayNetworking.send(watcher, RideActionS2C(p.id, RideAction.HIT, 16))
            }
        }
        syncPassengers(f, d)
    }

    /** Griff festigen (nach dem verzweifelten Fangen am Dorn). */
    fun secureGrip(f: EirholmFight, p: ServerPlayerEntity) {
        broadcastGrip(f, p.id, 2)
        for (watcher in f.audience()) {
            if (ServerPlayNetworking.canSend(watcher, RideActionS2C.ID)) {
                ServerPlayNetworking.send(watcher, RideActionS2C(p.id, RideAction.BRACE, 14))
            }
        }
    }

    /**
     * Abwurf bei der Fassrolle: setzt den Spieler an den abgerollten seitlichen Ablösepunkt (klar neben dem Körper)
     * und schleudert ihn im selben Tick frei von Solvane Richtung [target] (kein Durchfallen durch ihren Rumpf).
     */
    fun throwRider(f: EirholmFight, p: ServerPlayerEntity, releasePos: Vec3d, target: Vec3d, lift: Double) {
        Hold.drop(p)
        if (Cine.muted) {
            p.teleport(f.world, target.x, target.y, target.z, emptySet(), p.yaw, p.pitch, false)
            p.onLanding()
            return
        }
        p.teleport(f.world, releasePos.x, releasePos.y, releasePos.z, emptySet(), p.yaw, p.pitch, false)
        Hold.allowLong(p, 90)
        val vel = Ballistic.velocity(releasePos, target, lift)
        Hold.launch(p, vel, 80, landing = target, fallback = target)
        p.fallDistance = 0.0
        // Im Folgetick die Geschwindigkeit noch einmal bestätigen, falls Vanilla-StopRiding sie überschreibt.
        f.task(script {
            yield(Unit)
            if (!p.isAlive || p.entityWorld !== f.world || p.hasVehicle()) return@script
            dev.henny.hha.logic.Fx.launchPlayer(p, Ballistic.velocity(p.entityPos, target, lift * 0.92))
            p.fallDistance = 0.0
        })
    }

    /** Release the rider before sending launch velocity so the client no longer applies passenger movement. */
    fun landRider(f: EirholmFight, p: ServerPlayerEntity, target: Vec3d, lift: Double) {
        Hold.drop(p)
        if (Cine.muted) {
            p.teleport(f.world, target.x, target.y, target.z, emptySet(), p.yaw, p.pitch, false)
            p.onLanding()
            return
        }
        f.task(script {
            yield(Unit)
            if (!p.isAlive || p.entityWorld !== f.world || p.hasVehicle()) return@script
            Hold.allowLong(p, 80)
            Hold.launch(p, Ballistic.velocity(p.entityPos, target, lift), 70, fallback = target)
            p.fallDistance = 0.0
        })
    }

    fun roll(progress: Double): Float {
        val r = progress.coerceIn(0.0, 1.0)
        return (r * r * r * (r * (6.0 * r - 15.0) + 10.0) * 2.0 * PI).toFloat()
    }

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

    /** Ritt vorbei: alle steigen ab, Plätze und Griffe frei, und alle Clients sehen dieselbe Mitfahrerliste. */
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
 * Die Route des Ritts (seit 1.12.0 von Grund auf neu, Crix: „sie fliegt viel zu schnell, unter der Erde und macht
 * 360-Grad-Drehungen“): **eine** von Hand gelegte, glatte Linie. Sie verlässt den Horst geradeaus durch die Ostöffnung,
 * zieht in einem weiten Bogen an der Nordflanke des Massivs entlang, zwischen den Ostgipfeln hindurch und läuft von Süden
 * flach über das Feld aus. Keine Kreise, kein Überqueren und erneutes Unterfliegen einer Kante. Die Höhe folgt dem
 * Gelände mit fester Freiheit und begrenzter Steigung.
 */
object RideRoute {
    private const val STEP = 0.5

    /** Abstand über Gebirge bzw. über flachem Hochland. */
    private const val CLEAR_ROCK = 15.0
    private const val CLEAR_FLAT = 9.0

    /** Größte Steigung (Höhe je Block Weg). */
    private const val MAX_SLOPE = 0.17

    /** Ab hier (Bogenlänge vom Ende) gilt die gezeichnete Landehöhe unverändert. */
    private const val TAIL = 150.0

    /** Stützpunkte, relativ zum Instanz-Ursprung. */
    private val CTRL: List<Vec3d> = listOf(
        Vec3d(-268.0, 15.0, -60.0),
        Vec3d(-212.0, 31.0, -60.0),
        Vec3d(-158.0, 46.0, -47.0),
        Vec3d(-122.0, 56.0, 4.0),
        Vec3d(-104.0, 64.0, 62.0),
        Vec3d(-60.0, 72.0, 106.0),
        Vec3d(8.0, 78.0, 120.0),
        Vec3d(84.0, 76.0, 120.0),
        Vec3d(150.0, 70.0, 140.0),
        Vec3d(214.0, 62.0, 172.0),
        Vec3d(278.0, 54.0, 168.0),
        Vec3d(316.0, 44.0, 118.0),
        Vec3d(331.0, 33.0, 66.0),
        Vec3d(330.0, 23.0, 32.0),
        Vec3d(328.5, 16.0, 10.0),
        Vec3d(329.0, 11.0, -14.0),
        Vec3d(330.5, 5.0, -29.0),
        Vec3d(ArenaLayout.VAULT_REST.x, ArenaLayout.VAULT_REST.y, ArenaLayout.VAULT_REST.z),
    )

    /** Der Abwurfpunkt über den Landeplätzen des Felds. */
    private val DROP = Vec3d(328.5, 16.0, 10.0)

    /** Bogenlänge, an der die Route den Horst-Abflug übernimmt. */
    const val JOIN = 74.0

    /** Abschnitte (Anteile der Bogenlänge), an denen Sturm und Sturzflug beginnen. */
    const val stormFrom = 0.50
    const val diveFrom = 0.86

    private var pts: List<Vec3d>? = null

    /** Gleichmäßig (alle [STEP] Blöcke) abgetastete Bahn, relativ. */
    fun points(): List<Vec3d> = pts ?: build().also { pts = it }

    val length: Double get() = (points().size - 1) * STEP

    /** Bogenlänge des Abwurfpunkts. */
    val dropS: Double by lazy {
        var best = 0
        var bestD = Double.MAX_VALUE
        points().forEachIndexed { i, q ->
            val dist = q.squaredDistanceTo(DROP)
            if (dist < bestD) { bestD = dist; best = i }
        }
        best * STEP
    }

    /** Bogenlängen der festen Hindernisse (für die Sichtprüfung). */
    val obstacleArcs: List<Double> get() = RideLandmarks.all.map { it.s }

    /** Punkt bei Bogenlänge [s] (Blöcke ab dem Start), relativ. */
    fun at(s: Double): Vec3d {
        val p = points()
        val x = (s / STEP).coerceIn(0.0, p.lastIndex.toDouble())
        val i = x.toInt().coerceAtMost(p.lastIndex - 1)
        return p[i].lerp(p[i + 1], x - i)
    }

    private fun build(): List<Vec3d> {
        val raw = DragonRail.spline(CTRL).toMutableList().apply { this[lastIndex] = CTRL.last() }
        val n = raw.size
        val tailFrom = (n - (TAIL / STEP).toInt()).coerceAtLeast(1)
        // Nötige Höhe: Gelände im Umkreis ihres Körpers plus Freiheit; im Endanflug gilt die gezeichnete Höhe.
        val need = DoubleArray(n) { i ->
            val q = raw[i]
            if (i >= tailFrom) q.y else {
                val top = DragonRail.groundAt(q.x, q.z, 12)
                if (top == Int.MIN_VALUE) q.y else max(q.y, top + 1.0 + if (top > 6) CLEAR_ROCK else CLEAR_FLAT)
            }
        }
        // Erst weit nach außen anheben, dann glätten: Der Mittelwert bleibt so garantiert über der nötigen Höhe.
        val w1 = 56
        val w2 = 28
        val lifted = DoubleArray(n) { i ->
            var m = need[i]
            for (k in max(0, i - w1 - w2)..min(n - 1, i + w1 + w2)) m = max(m, need[k])
            if (i >= tailFrom) need[i] else m
        }
        val maxStep = STEP * MAX_SLOPE
        for (i in 1 until tailFrom) lifted[i] = max(lifted[i], lifted[i - 1] - maxStep)
        for (i in tailFrom - 2 downTo 0) lifted[i] = max(lifted[i], lifted[i + 1] - maxStep)
        val first = box(lifted, w1, tailFrom)
        val second = box(first, w2, tailFrom)
        // Weicher Übergang in den gezeichneten Endanflug.
        val blend = 90
        val ys = DoubleArray(n) { i ->
            when {
                i >= tailFrom -> raw[i].y
                i >= tailFrom - blend -> {
                    val u = (i - (tailFrom - blend)) / blend.toDouble()
                    second[i] + (raw[i].y - second[i]) * (u * u * (3.0 - 2.0 * u))
                }
                else -> second[i]
            }
        }
        return List(n) { Vec3d(raw[it].x, ys[it], raw[it].z) }
    }

    private fun box(v: DoubleArray, w: Int, until: Int): DoubleArray {
        val out = v.copyOf()
        for (i in 0 until until) {
            var sum = 0.0
            var c = 0
            for (k in max(0, i - w)..min(until - 1, i + w)) { sum += v[k]; c++ }
            out[i] = sum / c
        }
        return out
    }
}

/**
 * Der Ritt auf Solvanes Rücken zwischen Abschnitt II und III (seit 1.12.0 neu):
 * * **Aufsammeln:** Sie fliegt in einer einzigen Linie zu den Spielern, streicht in Fahrtrichtung über sie hinweg, ihr
 *   Lichtstrom zieht jeden im Bogen an seinen Dorn, und sie fliegt ohne Halt geradeaus durch die Ostöffnung weiter.
 * * **Hindernisse:** fest in der Dimension ([RideLandmarks]). Jeder Reiter bekommt seine Taste zu seinem eigenen Zeitpunkt
 *   (je nachdem, wie weit hinten er hängt), die Haltung bleibt, bis das Hindernis an ihm vorbei ist; wer es verpasst, wird
 *   sichtbar getroffen.
 * * **Eine Hand:** Wer getroffen wurde, kann mit einem Druck wieder zugreifen. Auch das nächste geschaffte Hindernis gibt
 *   die zweite Hand zurück, und Hacken braucht dann nur noch drei Schläge.
 * * **Abwurf:** Über den Landeplätzen rollt sie ihren ganzen Körper zur Seite. Darum verlieren die Reiter den Halt.
 */
class DragonRide(private val f: EirholmFight) {

    /** Ein Hindernis, das jeder Reiter einmal bekommt. */
    private class Obstacle(val lm: RideLandmarks.Landmark) {
        val asked = HashSet<UUID>()
    }

    /** Offene Taste eines Reiters. */
    private class Prompt(val player: UUID, val ob: Obstacle, val round: Qte.Round, val impactS: Double) {
        var holding = false
    }

    /** Bahn vom Abflug bis auf die Route (absolut) und der Stand darauf; läuft über Vorspann und Ritt hinweg. */
    private var lead: List<Vec3d> = emptyList()
    private var leadS = 0.0
    private var speed = 0.3

    private val grips = HashMap<UUID, Int>()

    private fun send(grip: Int, player: ServerPlayerEntity) {
        val payload = RideGripS2C(player.id, grip)
        for (p in f.audience()) if (ServerPlayNetworking.canSend(p, RideGripS2C.ID)) ServerPlayNetworking.send(p, payload)
    }

    private fun sendAction(player: ServerPlayerEntity, action: Int, ticks: Int) {
        val payload = RideActionS2C(player.id, action, ticks)
        for (p in f.audience()) if (ServerPlayNetworking.canSend(p, RideActionS2C.ID)) ServerPlayNetworking.send(p, payload)
    }

    private fun cue(id: String, at: Vec3d, volume: Float = 3f, pitch: Float = 1f) {
        if (!Cine.muted) f.ambient(id, at, volume, pitch)
    }

    private fun flat(from: Vec3d, to: Vec3d): Vec3d {
        val v = Vec3d(to.x - from.x, 0.0, to.z - from.z)
        return if (v.lengthSquared() < 1e-6) Vec3d(1.0, 0.0, 0.0) else v.normalize()
    }

    // --- Aufsammeln ---------------------------------------------------------------------------------------------------

    /**
     * Eine Linie aus ihrer jetzigen Lage und Flugrichtung: an den Spielern in Abflugrichtung vorbei, weiter durch die
     * Ostöffnung auf die Route. Innerhalb des Kessels hält sie Abstand zur Felswand. Liefert die Stelle über den Spielern.
     */
    private fun buildLead(d: NivarisEntity): Double {
        val center = f.abs(Vec3d.of(ArenaLayout.AERIE)).add(0.5, 0.0, 0.5)
        val players = f.players()
        val mid = if (players.isEmpty()) center else players.map { it.entityPos }.fold(Vec3d.ZERO) { acc, v -> acc.add(v) }.multiply(1.0 / players.size)
        val inner = ArenaLayout.AERIE_R - 20.0
        fun keep(v: Vec3d, r: Double): Vec3d {
            val o = Vec3d(v.x - center.x, 0.0, v.z - center.z)
            return if (o.length() <= r) v else Vec3d(center.x + o.x / o.length() * r, v.y, center.z + o.z / o.length() * r)
        }
        val routeIn = f.abs(RideRoute.at(0.0))
        val low = ArenaLayout.AERIE_PILLARS.any { p ->
            val c = f.abs(Vec3d.of(p))
            kotlin.math.hypot(c.x - mid.x, c.z - mid.z) < 13.0
        }
        val pickup = keep(Vec3d(mid.x, f.floorY + if (low) 17.0 else 10.0, mid.z), inner)
        val dir = flat(pickup, routeIn)
        val before = keep(pickup.subtract(dir.multiply(58.0)).let { Vec3d(it.x, f.floorY + 18.0, it.z) }, inner)
        val start = d.entityPos
        val heading = DragonRail.heading(f, d).let { Vec3d(it.x, it.y.coerceIn(-0.2, 0.25), it.z) }
        val toPickup = ArrayList<Vec3d>()
        if (start.squaredDistanceTo(before) > 34.0 * 34.0 && start.squaredDistanceTo(pickup) > 62.0 * 62.0) toPickup += before
        toPickup += pickup
        val first = DragonRail.path(start, heading, toPickup, 28.0, center, inner + 4.0)
        val endDir = first.last().subtract(first[(first.size - 8).coerceAtLeast(0)]).let { if (it.lengthSquared() < 1e-4) dir else it.normalize() }
        val second = DragonRail.path(first.last(), endDir, listOf(routeIn, f.abs(RideRoute.at(RideRoute.JOIN - 34.0)), f.abs(RideRoute.at(RideRoute.JOIN))), 30.0)
        val whole = ArrayList(first)
        whole.addAll(second.drop(1))
        lead = DragonRail.clearTerrain(whole, f.origin, f.floorY, clearance = 6.0)
        leadS = 0.0
        var best = 0
        var bestD = Double.MAX_VALUE
        lead.forEachIndexed { i, q ->
            val dist = q.squaredDistanceTo(pickup)
            if (dist < bestD) { bestD = dist; best = i }
        }
        return best * DragonRail.STEP
    }

    private fun flyLead(d: NivarisEntity, cruise: Double): Boolean {
        val len = DragonRail.length(lead)
        if (leadS >= len) return false
        speed += (cruise - speed).coerceIn(-0.012, 0.012)
        leadS = min(len, leadS + speed)
        val at = DragonRail.at(lead, leadS)
        f.place(d, at)
        val next = DragonRail.at(lead, leadS + 5.0)
        if (next.squaredDistanceTo(at) > 0.01) f.face(d, next)
        return true
    }

    private fun mount(d: NivarisEntity, p: ServerPlayerEntity, i: Int) {
        if (!p.isAlive || p.isRemoved || p.vehicle === d) return
        grips[p.uuid] = 2
        if (!Cine.muted) f.fx(FxKind.THREAD, p.entityPos, life = 26, e1 = p, e2 = d)
        cue("resonance.chime", p.entityPos, 1.6f, 1.3f + i * 0.05f)
        p.addStatusEffect(StatusEffectInstance(StatusEffects.SLOW_FALLING, 3000, 0, false, false, false))
        RideSeats.pickUp(f, d, p)
    }

    /** Wird die Szene abgebrochen, fehlt nichts für den Ritt: Die Bahn entsteht dann in [play] aus der aktuellen Lage. */
    fun settleIntro() {
        lead = emptyList()
    }

    /** Vorspann: Arius pfeift, sie kommt in einer Linie heran, sammelt alle auf und fliegt ohne Halt weiter. */
    fun intro(scene: CineScene): Script = script {
        val a = f.arius ?: return@script
        val d = f.nivaris ?: return@script
        f.setPhase(EirholmFight.Phase.COLLAPSE)
        f.calls.reset()
        f.music("music.transform", 10)
        MusicCue.play(f, "ride_intro", 10)
        d.mode = NivarisMode.NORMAL
        d.flying = true
        d.bodyRoll = 0f
        d.wingTuck = 0f
        d.play(NivarisPose.GLIDE)
        RideSeats.assign(f)
        val mountS = buildLead(d)
        val mountAt = DragonRail.at(lead, mountS)
        // Er pfeift auf seinem Pfeiler.
        a.sheathed = false
        a.play(AriusPose.WHISTLE)
        val face = a.entityPos.add(0.0, 1.8, 0.0)
        val toward = flat(a.entityPos, d.entityPos)
        val side = Vec3d(-toward.z, 0.0, toward.x)
        scene.line(
            face.add(toward.multiply(2.4)).add(side.multiply(1.0)).add(0.0, -0.4, 0.0), face.add(toward.multiply(1.8)).add(side.multiply(0.8)).add(0.0, -0.3, 0.0),
            face, d.entityPos.add(0.0, 4.0, 0.0), 40, 52.0, 60.0,
        )
        if (!Cine.muted) f.say("fall.together", 50)
        cue("resonance.chime", a.entityPos, 3f)
        a.eyeGlow = 100
        d.eyeGlow = 100
        var t = 0
        var mounted = false
        var after = 0
        var contactShot = false
        val jumpFrom = a.entityPos
        var jump = -1
        while (true) {
            t++
            flyLead(d, 0.88)
            if (t == 18) {
                f.fx(FxKind.THREAD, a.entityPos, life = 40, e1 = a, e2 = d)
                cue("nivaris.roar", d.entityPos, 5f, 0.9f)
            }
            if (t == 40 && !contactShot) {
                // Weite Seitenansicht auf die Spieler: Man sieht sie kommen.
                val look = mountAt
                val across = Vec3d(-flat(d.entityPos, mountAt).z, 0.0, flat(d.entityPos, mountAt).x)
                val remain = ((mountS - leadS) / 0.88).toInt().coerceAtLeast(24) + 34
                scene.line(
                    look.add(across.multiply(34.0)).add(0.0, 2.0, 0.0), look.add(across.multiply(26.0)).add(0.0, -1.0, 0.0),
                    d.entityPos.add(0.0, 3.0, 0.0), look, remain, 66.0, 62.0, lookAt = d, lookOffset = 3.0,
                )
            }
            // Arius springt ab, sobald sie an ihm vorbeistreicht, und sitzt auf ihrem Kopf, bevor die Spieler aufsteigen.
            if (a.vehicle !== d && leadS >= mountS - 16.0) {
                if (jump < 0) {
                    jump = 0
                    a.play(AriusPose.LAUNCH, 6)
                    cue("arius.wind_step", jumpFrom, 3f, 0.8f)
                }
                val k = (++jump / 18.0).coerceAtMost(1.0)
                val perch = d.entityPos.add(RideHooks.offsetFor(d, a).rotateY(-Math.toRadians(d.bodyYaw.toDouble()).toFloat()))
                f.place(a, jumpFrom.lerp(perch, k).add(0.0, sin(k * PI) * 4.5, 0.0))
                f.face(a, perch)
                if (k == 1.0) RideSeats.perch(f, a, d)
            }
            if (!contactShot && leadS >= mountS - 22.0) {
                contactShot = true
                val pathDir = flat(DragonRail.at(lead, mountS - 5.0), DragonRail.at(lead, mountS + 5.0))
                val across = Vec3d(-pathDir.z, 0.0, pathDir.x)
                val player = f.players().minByOrNull { it.entityPos.squaredDistanceTo(mountAt) }
                val launch = player?.entityPos ?: mountAt.subtract(0.0, 6.0, 0.0)
                val focus = launch.lerp(mountAt, 0.35)
                val to = DragonRail.at(lead, mountS + 19.0).add(0.0, 3.0, 0.0)
                scene.line(
                    focus.add(across.multiply(24.0)).add(0.0, 6.0, 0.0),
                    to.add(across.multiply(20.0)).subtract(pathDir.multiply(7.0)).add(0.0, 3.0, 0.0),
                    focus, to, 54, 62.0, 54.0,
                )
            }
            if (!mounted && leadS >= mountS - 4.0) {
                mounted = true
                f.calls.roomShake(mountAt, 3f, 40f)
                for ((i, p) in f.players().withIndex()) mount(d, p, i)
            }
            if (mounted) {
                after++
                if (after == 27) {
                    scene.once { Cine.title(it, "hha_arius.cine.hold_on", "hha_arius.cine.hold_on.sub", 50) }
                    scene.home(15)
                }
                if (after >= 42) break
            }
            if (leadS >= DragonRail.length(lead)) break
            yield(Unit)
        }
    }

    // --- Der Flug -----------------------------------------------------------------------------------------------------

    private fun lockArius(a: AriusEntity, d: NivarisEntity) {
        if (a.vehicle !== d) RideSeats.perch(f, a, d)
        a.yaw = d.yaw
        a.bodyYaw = d.bodyYaw
        a.headYaw = d.headYaw
        if (a.pose != AriusPose.SEATED) a.play(AriusPose.SEATED)
    }

    private fun holdRiders(d: NivarisEntity) {
        for (p in f.players()) if (p.uuid in grips && p.vehicle !== d && p.isAlive) {
            RideSeats.assign(f)
            p.startRiding(d, true, true)
        }
    }

    private fun qteKind(k: RideLandmarks.Kind) = when (k) {
        RideLandmarks.Kind.ROCK_ARCH, RideLandmarks.Kind.ICE_ARCH -> Qte.Kind.SNEAK
        RideLandmarks.Kind.FANGS -> Qte.Kind.JUMP
        RideLandmarks.Kind.NEEDLE_RIGHT -> Qte.Kind.LEFT
        RideLandmarks.Kind.NEEDLE_LEFT -> Qte.Kind.RIGHT
        RideLandmarks.Kind.ICEFALL -> Qte.Kind.MASH
    }

    private fun action(k: RideLandmarks.Kind) = when (k) {
        RideLandmarks.Kind.ROCK_ARCH, RideLandmarks.Kind.ICE_ARCH -> RideAction.DUCK
        RideLandmarks.Kind.FANGS -> RideAction.VAULT
        RideLandmarks.Kind.NEEDLE_RIGHT -> RideAction.SWING_L
        RideLandmarks.Kind.NEEDLE_LEFT -> RideAction.SWING_R
        RideLandmarks.Kind.ICEFALL -> RideAction.TUG
    }

    private fun promptKey(k: RideLandmarks.Kind) = when (k) {
        RideLandmarks.Kind.ROCK_ARCH -> "hha_arius.qte.r_arch"
        RideLandmarks.Kind.ICE_ARCH -> "hha_arius.qte.r_arch_ice"
        RideLandmarks.Kind.FANGS -> "hha_arius.qte.r_fangs"
        RideLandmarks.Kind.NEEDLE_RIGHT -> "hha_arius.qte.r_needle_right"
        RideLandmarks.Kind.NEEDLE_LEFT -> "hha_arius.qte.r_needle_left"
        RideLandmarks.Kind.ICEFALL -> "hha_arius.qte.r_ice"
    }

    private fun hitKey(k: RideLandmarks.Kind) = when (k) {
        RideLandmarks.Kind.ROCK_ARCH, RideLandmarks.Kind.ICE_ARCH -> "hha_arius.msg.ride_hit_arch"
        RideLandmarks.Kind.FANGS -> "hha_arius.msg.ride_hit_fangs"
        RideLandmarks.Kind.NEEDLE_RIGHT, RideLandmarks.Kind.NEEDLE_LEFT -> "hha_arius.msg.ride_hit_needle"
        RideLandmarks.Kind.ICEFALL -> "hha_arius.msg.ride_hit_ice"
    }

    /** So weit hängt der Reiter hinter dem Entity-Punkt (entlang der Bahn). */
    private fun seatBack(p: ServerPlayerEntity): Double {
        val seat = RideSeats.of(p.uuid)?.coerceAtLeast(0) ?: 0
        return -RideHooks.serverOffset(seat).z
    }

    private fun regain(p: ServerPlayerEntity) {
        if ((grips[p.uuid] ?: 2) >= 2) return
        grips[p.uuid] = 2
        send(2, p)
        cue("resonance.chime", p.entityPos, 1.8f, 1.35f)
        p.sendMessage(Text.translatable("hha_arius.msg.ride_recover").formatted(Formatting.AQUA), true)
    }

    /** Vom Hindernis getroffen: Körper reißt zurück, Splitter, eine Hand weg, Schaden. */
    private fun struck(p: ServerPlayerEntity, d: NivarisEntity, k: RideLandmarks.Kind) {
        val first = (grips[p.uuid] ?: 2) >= 2
        grips[p.uuid] = 1
        send(1, p)
        sendAction(p, RideAction.HIT, 24)
        f.calls.hitShake(p.entityPos, 3f, 6f)
        MusicCue.sting(listOf(p), "qte_fail")
        cue("hold.impact", p.entityPos, 2.2f, 0.8f)
        val block = if (k == RideLandmarks.Kind.ROCK_ARCH || k == RideLandmarks.Kind.NEEDLE_LEFT || k == RideLandmarks.Kind.NEEDLE_RIGHT) Blocks.DEEPSLATE else Blocks.PACKED_ICE
        f.world.spawnParticles(BlockStateParticleEffect(ParticleTypes.BLOCK, block.defaultState), p.x, p.y + 1.0, p.z, 26, 0.5, 0.4, 0.5, 0.22)
        slipDamage(p, d, first)
        p.sendMessage(Text.translatable(hitKey(k)).formatted(Formatting.GOLD), true)
    }

    fun play(): Script = script {
        val a = f.arius ?: return@script
        val d = f.nivaris ?: return@script
        RideSeats.assign(f)
        d.flying = true
        d.bodyRoll = 0f
        d.play(NivarisPose.GLIDE)
        RideSeats.perch(f, a, d)
        // Übersprungener Vorspann: Bahn aus der aktuellen Lage, alle steigen sofort auf.
        if (lead.isEmpty()) buildLead(d)
        for ((i, p) in f.players().withIndex()) if (p.vehicle !== d) mount(d, p, i)
        for (p in f.players()) if (p.vehicle === d) { grips.putIfAbsent(p.uuid, 2); send(grips[p.uuid] ?: 2, p) }
        var mood = "ride"
        MusicCue.play(f, mood, 20)
        f.atmos(dev.henny.hha_arius.net.AtmosMode.SNOW, 40)

        // Restliche Anflugbahn bis auf die Route.
        while (flyLead(d, 0.74)) {
            lockArius(a, d)
            holdRiders(d)
            yield(Unit)
        }
        val length = RideRoute.length
        val dropFrom = RideRoute.dropS - DROP_RUNUP
        fun pace(s: Double): Double {
            val near = RideLandmarks.all.any { abs(it.s - s) < 60.0 }
            return when {
                s > dropFrom - 40.0 -> 0.62
                near -> 0.64
                s / length > RideRoute.diveFrom -> 0.72
                else -> 0.70
            }
        }
        val obstacles = RideLandmarks.all.map { Obstacle(it) }
        val open = ArrayList<Prompt>()
        val regrips = HashMap<UUID, Qte.Round>()
        var s = RideRoute.JOIN
        var tick = 0
        var stormOn = false
        var diveOn = false
        // Der Anflug endet nie exakt auf der Route (Geländeanhebung): Der Rest läuft über 90 Blöcke weich aus, statt zu springen.
        val seam = d.entityPos.subtract(f.abs(RideRoute.at(RideRoute.JOIN)))
        fun routeAt(q: Double): Vec3d {
            val k = ((q - RideRoute.JOIN) / SEAM_BLEND).coerceIn(0.0, 1.0)
            return f.abs(RideRoute.at(q)).add(seam.multiply(1.0 - k * k * (3.0 - 2.0 * k)))
        }
        var prev = routeAt(s)
        while (s < dropFrom) {
            speed += (pace(s) - speed).coerceIn(-0.006, 0.006)
            s = min(dropFrom, s + speed)
            tick++
            val u = s / length
            val pos = routeAt(s)
            val ahead = routeAt(min(length, s + 6.0))
            f.place(d, pos)
            f.face(d, if (ahead.squaredDistanceTo(pos) > 1e-4) ahead else pos.add(pos.subtract(prev)))
            prev = pos
            lockArius(a, d)
            holdRiders(d)
            // Flügel an, solange sie zwischen Felsen hindurchschießt.
            val tight = RideLandmarks.all.any { s in (it.s - 30.0)..(it.s + 26.0) }
            d.wingTuck = (d.wingTuck + if (tight) 0.06f else -0.035f).coerceIn(0f, 1f)
            if (!tight && tick % 30 == 0) cue("nivaris.wing", pos, 3.4f, 0.74f + (tick % 60) * 0.002f)
            if (tick % 340 == 140) cue("nivaris.roar", pos, 4.5f, 0.85f)
            if (!stormOn && u >= RideRoute.stormFrom) {
                stormOn = true
                mood = "storm"
                MusicCue.play(f.audience().filter { p -> open.none { it.player == p.uuid } }, mood, 40)
                f.atmos(dev.henny.hha_arius.net.AtmosMode.AERIE, 30)
                cue("nivaris.roar", pos, 5f, 0.7f)
            }
            if (!diveOn && u >= RideRoute.diveFrom) {
                diveOn = true
                mood = "dive"
                MusicCue.play(f.audience().filter { p -> open.none { it.player == p.uuid } }, mood, 30)
                f.atmos(dev.henny.hha_arius.net.AtmosMode.VAULT, 60)
            }
            // Tasten je Reiter, passend dazu, wann das Hindernis genau seinen Platz erreicht.
            for (ob in obstacles) for (p in f.players()) {
                if (p.uuid in ob.asked || p.vehicle !== d) continue
                val impact = ob.lm.s + seatBack(p)
                val kind = qteKind(ob.lm.kind)
                val window = if (kind == Qte.Kind.MASH) WINDOW_MASH else WINDOW_KEY
                if (s < impact - window * CRUISE) continue
                ob.asked += p.uuid
                regrips.remove(p.uuid)?.let { Qte.close(it) }
                val count = if (kind == Qte.Kind.MASH) { if ((grips[p.uuid] ?: 2) >= 2) 5 else 3 } else 1
                open += Prompt(p.uuid, ob, Qte.start(listOf(p), kind, window, count, promptKey(ob.lm.kind)), impact)
                MusicCue.play(listOf(p), "qte", 6)
            }
            val it = open.iterator()
            while (it.hasNext()) {
                val q = it.next()
                val p = f.server.playerManager.getPlayer(q.player)
                if (p == null || p.vehicle !== d) {
                    Qte.close(q.round)
                    it.remove()
                    continue
                }
                // Geschafft: Die Haltung bleibt, bis das Hindernis am Reiter vorbei ist.
                if (!q.holding && q.round.results[q.player] == true) {
                    q.holding = true
                    val left = ((q.impactS - s) / speed.coerceAtLeast(0.2)).toInt().coerceAtLeast(0)
                    sendAction(p, action(q.ob.lm.kind), left + HOLD_PAST)
                    MusicCue.sting(listOf(p), "qte_ok")
                }
                if (s < q.impactS) continue
                Qte.close(q.round)
                it.remove()
                MusicCue.play(p, mood, 14)
                if (q.round.success(p)) {
                    cue("nivaris.wing", p.entityPos, 2f, 1.5f)
                    regain(p)
                } else {
                    struck(p, d, q.ob.lm.kind)
                    if (open.none { o -> o.player == p.uuid }) {
                        regrips[p.uuid] = Qte.start(listOf(p), Qte.Kind.USE, WINDOW_REGRIP, 1, "hha_arius.qte.r_regrip")
                    }
                }
            }
            // Wieder zugreifen nach einem Treffer.
            val rg = regrips.entries.iterator()
            while (rg.hasNext()) {
                val (id, round) = rg.next()
                val p = f.server.playerManager.getPlayer(id)
                if (p == null || p.vehicle !== d) {
                    Qte.close(round)
                    rg.remove()
                } else if (round.results[id] == true) {
                    Qte.close(round)
                    rg.remove()
                    sendAction(p, RideAction.BRACE, 16)
                    regain(p)
                } else if (round.results[id] == false) {
                    Qte.close(round)
                    rg.remove()
                }
            }
            if (tick > 40 && f.players().none { it.vehicle === d }) s = max(s, dropFrom)
            yield(Unit)
        }
        for (q in open) Qte.close(q.round)
        for (r in regrips.values) Qte.close(r)
        open.clear()
        regrips.clear()
        d.wingTuck = 0f
        yieldAll(dropOff(d, a, s))
        d.bodyRoll = 0f
        for (p in f.players()) send(0, p)
        RideSeats.unperch(f, a)
        RideSeats.clear(f)
    }

    // --- Abwurf -------------------------------------------------------------------------------------------------------

    /**
     * Über den Landeplätzen rollt sie ihren ganzen Körper einmal um die Längsachse. Bei etwa 110° können sich die Reiter
     * nicht mehr halten und fallen seitlich in den Schnee; sie dreht weiter, bis sie wieder aufrecht ist, und gleitet aus.
     * Kamera: neben ihr auf die Reiter, dann vom Boden hinauf zum Sturz, zuletzt nah an den Aufschlag.
     */
    private fun dropOff(d: NivarisEntity, a: AriusEntity, from: Double): Script = script {
        val length = RideRoute.length
        val vault = f.abs(Vec3d.of(ArenaLayout.VAULT)).add(0.5, 0.0, 0.5)
        val landed = HashSet<UUID>()
        var released = false
        fun release(pos: Vec3d) {
            released = true
            cue("nivaris.wing", pos, 4.5f, 0.95f)
            for ((j, p) in f.players().withIndex()) {
                if (p.vehicle !== d) continue
                p.stopRiding()
                p.removeStatusEffect(StatusEffects.SLOW_FALLING)
                send(0, p)
                if (p.isAlive) {
                    val spot = f.abs(ArenaLayout.VAULT_LANDING[j % ArenaLayout.VAULT_LANDING.size])
                    RideSeats.landRider(f, p, spot, 0.16)
                }
            }
            RideSeats.clear(f)
        }
        val finish = {
            d.bodyRoll = 0f
            if (!released) release(d.entityPos)
            RideSeats.unperch(f, a)
            RideSeats.clear(f)
            for (p in f.players()) {
                p.fallDistance = 0.0
                p.onLanding()
            }
        }
        val scene = CineScene(f) { finish() }
        yieldAll(scene.play(script {
            val fwd = flat(f.abs(RideRoute.at(from)), f.abs(RideRoute.at(RideRoute.dropS)))
            val side = Vec3d(-fwd.z, 0.0, fwd.x)
            val land0 = f.abs(ArenaLayout.VAULT_LANDING[0])
            val dropAt = f.abs(RideRoute.at(RideRoute.dropS))
            // A) Neben ihr: die Reiter am Dorn, während sie sich zu drehen beginnt.
            val startPos = f.abs(RideRoute.at(from))
            val rideBy = fwd.multiply(RELEASE_TICK * 0.62)
            scene.line(
                startPos.add(side.multiply(15.0)).add(fwd.multiply(-5.0)).add(0.0, 5.0, 0.0),
                startPos.add(rideBy).add(side.multiply(13.0)).add(fwd.multiply(-5.0)).add(0.0, 4.0, 0.0),
                startPos.add(0.0, 2.5, 0.0), startPos.add(rideBy).add(0.0, 2.5, 0.0), RELEASE_TICK + 4, 58.0, 54.0, lookAt = d, lookOffset = 2.5,
            )
            d.play(NivarisPose.GLIDE)
            var s = from
            var ariusFrom: Vec3d? = null
            val ariusTarget = vault
            var t = 0
            var settled = 0
            var landingShot = false
            while (true) {
                t++
                val pace = 0.35 + 0.25 * ((length - s) / 16.0).coerceIn(0.0, 1.0)
                speed += (pace - speed).coerceIn(-0.012, 0.012)
                s = min(length, s + speed)
                val pos = f.abs(RideRoute.at(s))
                val ahead = f.abs(RideRoute.at(min(length, s + 5.0)))
                f.place(d, pos)
                if (ahead.squaredDistanceTo(pos) > 0.01) f.face(d, ahead)
                // Eine einzige weiche Rolle um die Längsachse (zur rechten Seite, Rücken nach unten bei der Hälfte).
                val r = ((t - ROLL_FROM) / ROLL_TICKS.toDouble()).coerceIn(0.0, 1.0)
                d.bodyRoll = RideSeats.roll(r)
                if (t == ROLL_FROM + 8) {
                    cue("nivaris.roar", pos, 4.5f, 0.9f)
                    f.calls.roomShake(pos, 2.2f, 50f)
                }
                if (t < ARIUS_TICK) {
                    lockArius(a, d)
                } else {
                    if (ariusFrom == null) {
                        RideSeats.unperch(f, a)
                        ariusFrom = a.entityPos
                        a.play(AriusPose.LAUNCH, 6)
                        cue("arius.wind_step", a.entityPos, 3f, 0.85f)
                    }
                    val ak = ((t - ARIUS_TICK) / 26.0).coerceIn(0.0, 1.0)
                    f.place(a, ariusFrom!!.lerp(ariusTarget, ak).add(0.0, sin(ak * PI) * 3.2, 0.0))
                    f.face(a, land0)
                    if (t == ARIUS_TICK + 20) a.play(AriusPose.STAB_GROUND, 6)
                }
                if (!released) for (p in f.players()) if (p.vehicle !== d && p.uuid in grips && p.isAlive) p.startRiding(d, true, true)
                if (t == RELEASE_TICK - 6) for (p in f.players()) if (p.vehicle === d) sendAction(p, RideAction.HIT, 10)
                if (t == RELEASE_TICK && !released) {
                    release(pos)
                    val ground = land0.add(side.multiply(-12.0)).add(fwd.multiply(-12.0)).add(0.0, 4.0, 0.0)
                    scene.line(ground, ground.add(fwd.multiply(6.0)).add(0.0, 0.8, 0.0),
                        pos.lerp(land0, 0.35), ariusTarget.add(0.0, 1.2, 0.0), FALL_SHOT, 72.0, 58.0)
                }
                if (t == RELEASE_TICK + FALL_SHOT) {
                    val c = ariusTarget.add(side.multiply(-7.0)).add(fwd.multiply(-7.0)).add(0.0, 2.8, 0.0)
                    scene.line(c, c.add(fwd.multiply(3.0)), ariusTarget.add(0.0, 1.0, 0.0),
                        ariusTarget.add(0.0, 1.4, 0.0), 50, 58.0, 50.0)
                }
                if (!landingShot && length - s <= 21.0) {
                    landingShot = true
                    d.play(NivarisPose.LAND, 38)
                    val end = f.abs(RideRoute.at(length))
                    val cam = end.add(side.multiply(20.0)).add(fwd.multiply(10.0)).add(0.0, 4.8, 0.0)
                    scene.line(cam, cam.add(side.multiply(-5.0)).add(0.0, -1.0, 0.0),
                        pos.add(0.0, 3.0, 0.0), end.add(0.0, 2.0, 0.0), 62, 66.0, 55.0)
                }
                if (s >= length && d.flying) {
                    d.flying = false
                    d.play(NivarisPose.EXHAUSTED)
                    scene.home(18)
                }
                if (released) for (p in f.players()) {
                    p.fallDistance = 0.0
                    if (p.uuid !in landed && (p.isOnGround || p.y <= land0.y + 0.35)) {
                        landed += p.uuid
                        p.onLanding()
                        f.world.spawnParticles(BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.SNOW_BLOCK.defaultState), p.x, p.y + 0.2, p.z, 26, 0.7, 0.2, 0.7, 0.14)
                        cue("hold.impact", p.entityPos, 1.6f, 0.85f)
                    }
                }
                if (!d.flying && ++settled >= 18) break
                yield(Unit)
            }
            d.bodyRoll = 0f
        }))
        finish()
    }

    /** Schaden bei einem Treffer: Anteil der maximalen Gesundheit; der erste lässt immer mindestens ein Herz übrig. */
    private fun slipDamage(p: ServerPlayerEntity, d: NivarisEntity, first: Boolean) {
        var amount = p.maxHealth * (if (first) 0.2f else 0.28f)
        if (first) amount = minOf(amount, p.health - 1f)
        if (amount <= 0.05f) return
        p.timeUntilRegen = 0
        p.damage(f.world, f.world.damageSources.create(Damage.Kind.IMPACT.key, d), amount)
    }

    private companion object {
        /** Reisetempo, mit dem die Tasten vorab geplant werden (Blöcke je Tick). */
        const val CRUISE = 0.64

        /** Zeitfenster: Taste, Hacken, Wieder-Zugreifen (Ticks). */
        const val WINDOW_KEY = 58
        const val WINDOW_MASH = 76
        const val WINDOW_REGRIP = 70

        /** So lange bleibt die Haltung nach dem Hindernis. */
        const val HOLD_PAST = 16

        /** Strecke, auf der der Anflug in die Route übergeht (Blöcke). */
        const val SEAM_BLEND = 90.0

        /** Anlauf vor dem Abwurfpunkt (Blöcke). */
        const val DROP_RUNUP = 34.0

        const val ROLL_FROM = 16
        const val ROLL_TICKS = 96
        const val RELEASE_TICK = 54
        const val ARIUS_TICK = 34
        const val FALL_SHOT = 44
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
        val offset = serverOffset(seat)
        val up = offset.y + EYE - NivarisShape.SPINE_UP
        val roll = d.bodyRoll.toDouble()
        return Vec3d(up * sin(roll), NivarisShape.SPINE_UP + up * kotlin.math.cos(roll) - EYE, offset.z)
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
