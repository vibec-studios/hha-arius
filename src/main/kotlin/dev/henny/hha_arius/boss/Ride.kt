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

        // Catmull-Rom, fein abgetastet, dann Gelände-Abstand und gleichmäßige Bogenlänge.
        val fine = ArrayList<Vec3d>()
        val tags = ArrayList<Int>()
        for (i in 0 until ctrl.size - 1) {
            val p0 = ctrl[maxOf(0, i - 1)]
            val p1 = ctrl[i]
            val p2 = ctrl[i + 1]
            val p3 = ctrl[minOf(ctrl.size - 1, i + 2)]
            val steps = (p1.distanceTo(p2) / 1.0).toInt().coerceAtLeast(4)
            for (k in 0 until steps) {
                fine += catmull(p0, p1, p2, p3, k / steps.toDouble())
                tags += i
            }
        }
        fine += ctrl.last()
        tags += ctrl.size - 1
        // Gelände: mindestens CLEAR über dem höchsten Punkt im Umkreis (außer im Endanflug aufs Feld).
        val landing = fine.size - (ctrl.last().distanceTo(ctrl[ctrl.size - 2]) / 1.0).toInt() - 1
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
        // Höhe glätten (gleitender Mittelwert über ±20 Punkte, nie unter das Gelände-Minimum zurück).
        val sm = DoubleArray(ys.size)
        for (i in ys.indices) {
            var s = 0.0
            var c = 0
            for (k in maxOf(0, i - 20)..minOf(ys.size - 1, i + 20)) { s += ys[k]; c++ }
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

    private fun catmull(p0: Vec3d, p1: Vec3d, p2: Vec3d, p3: Vec3d, t: Double): Vec3d {
        val t2 = t * t
        val t3 = t2 * t
        fun c(a: Double, b: Double, cc: Double, dd: Double) = 0.5 * (2 * b + (-a + cc) * t + (2 * a - 5 * b + 4 * cc - dd) * t2 + (-a + 3 * b - 3 * cc + dd) * t3)
        return Vec3d(c(p0.x, p1.x, p2.x, p3.x), c(p0.y, p1.y, p2.y, p3.y), c(p0.z, p1.z, p2.z, p3.z))
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

    private class Event(val at: Double, val kind: Qte.Kind, val count: Int, val ticks: Int, val text: String)

    private fun send(grip: Int, player: ServerPlayerEntity) {
        val payload = RideGripS2C(player.id, grip)
        for (p in f.audience()) if (ServerPlayNetworking.canSend(p, RideGripS2C.ID)) ServerPlayNetworking.send(p, payload)
    }

    private fun cue(id: String, at: Vec3d, volume: Float = 3f, pitch: Float = 1f) = f.ambient(id, at, volume, pitch)

    fun play(neckOf: (NivarisEntity) -> Vec3d): Script = script {
        val a = f.arius ?: return@script
        val d = f.nivaris ?: return@script
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
        val events = listOf(
            Event(0.07, Qte.Kind.MASH, 6, 44, "hha_arius.qte.hold_on"),
            Event(0.17, Qte.Kind.SNEAK, 1, 32, "hha_arius.qte.duck"),
            Event(0.27, Qte.Kind.MASH, 7, 42, "hha_arius.qte.hold_turn"),
            Event(0.37, Qte.Kind.JUMP, 1, 30, "hha_arius.qte.ride_jump"),
            Event(0.47, Qte.Kind.ATTACK, 1, 28, "hha_arius.qte.ride_ice"),
            Event(RideRoute.stormFrom + 0.03, Qte.Kind.MASH, 9, 44, "hha_arius.qte.hold_storm"),
            Event((RideRoute.stormFrom + RideRoute.diveFrom) / 2, Qte.Kind.SNEAK, 1, 28, "hha_arius.qte.duck"),
            Event(RideRoute.diveFrom + 0.01, Qte.Kind.MASH, 10, 44, "hha_arius.qte.hold_dive"),
            Event(0.94, Qte.Kind.USE, 1, 26, "hha_arius.qte.ride_brace"),
        )
        var nextEvent = 0
        val rounds = ArrayList<Pair<Qte.Round, Int>>()
        var mood = "ride"
        MusicCue.play(f, mood, 20)
        f.atmos(dev.henny.hha_arius.net.AtmosMode.SNOW, 40)
        d.flying = true
        d.play(NivarisPose.GLIDE)
        a.play(AriusPose.IDLE)
        var s = 0.0
        var tick = 0
        var stormOn = false
        var diveOn = false
        var prev = f.abs(RideRoute.at(0.0))
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
            f.place(a, neckOf(d))
            a.yaw = d.yaw; a.headYaw = d.yaw; a.bodyYaw = d.yaw
            // Wer noch hält, aber abgerutscht ist (Absteigen), sitzt wieder auf.
            for (p in f.players()) if (p.uuid in grips && p.uuid !in fallen && p.vehicle !== d) p.startRiding(d, true, true)
            if (tick % 24 == 0) cue("nivaris.wing", pos, 4f, 0.75f + (tick % 48) * 0.002f)
            if (tick % 300 == 120) cue("nivaris.roar", pos, 5f, 0.85f)
            if (!stormOn && u >= RideRoute.stormFrom) {
                stormOn = true
                mood = "storm"
                MusicCue.play(f.audience().filter { p -> rounds.none { p.uuid in it.first.players } }, mood, 40)
                f.atmos(dev.henny.hha_arius.net.AtmosMode.BLIZZARD, 30)
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
                    f.calls.roomShake(pos, 4f, 40f)
                    if (ev.kind == Qte.Kind.SNEAK) Vfx.shockwave(f.world, Vfx.Style.STORM, pos.add(0.0, 4.0, 0.0), 8f, 14, 1.2f)
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

    /** Rückenglied mit Kristallplatte für Reiter [i] (0..7): 4, 6, 8 … 18. */
    fun glied(i: Int): Int = 4 + 2 * i.coerceIn(0, 7)

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
