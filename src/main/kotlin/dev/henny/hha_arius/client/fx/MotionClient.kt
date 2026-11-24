package dev.henny.hha_arius.client.fx

import dev.henny.hha_arius.boss.EirholmPuppet
import dev.henny.hha_arius.boss.MotionS2C
import dev.henny.hha_arius.boss.MotionSync
import dev.henny.hha_arius.boss.NivarisEntity
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.entity.LivingEntity
import net.minecraft.util.math.MathHelper
import net.minecraft.util.math.Vec3d

/**
 * Abspielen der Server-Lagen aus [MotionSync] (seit 1.10.0).
 *
 * * **Zeitachse:** Ein gemeinsamer Abspielzeitpunkt [play] (in Server-Ticks) läuft je Client-Welt-Tick um 1 weiter und
 *   wird sanft (höchstens ±30 %) auf „frühestes beobachtetes Eintreffen − [DELAY]“ nachgeregelt. Der Puffer von gut zwei
 *   Ticks schluckt gebündelte oder verspätete Pakete; schwankende TPS dehnen bzw. stauchen die Achse weich statt zu
 *   ruckeln. Läuft die Achse den Daten davon (Server hängt), hält die Lage an statt zu raten.
 * * **Kurve:** Zwischen den Stützpunkten kubisch (Hermite mit Tangenten aus den Nachbarn, auch bei Lücken im Takt);
 *   Sprünge über [JUMP] Blöcke (Teleport) werden nicht verschliffen.
 * * **Übernahme:** am Ende des Entity-Ticks ([MotionSync.clientHook]) – damit setzt Vanilla zwischen den Ticks wie immer
 *   mit tickDelta zwischen alter und neuer Lage, und Reiter (ihre Position folgt im selben Welt-Tick), Kamera-Blickziele
 *   und Effekte sehen genau diese Lage.
 */
object MotionClient {

    private class Sample(val tick: Int, val pos: Vec3d, val yaw: Float, val extra: Float)

    private class Track {
        val samples = ArrayDeque<Sample>()
        var lastSeen = 0
    }

    private val tracks = HashMap<Int, Track>()

    /** Client-Welt-Ticks seit Start (läuft nicht während Pause). */
    private var clientTick = 0

    /** Geschätzter Abstand Server-Tick − Client-Tick beim frühesten Eintreffen. */
    private var offset = Double.NaN

    /** Abspielzeitpunkt (Server-Ticks). */
    private var play = Double.NaN

    /** Abspielzeitpunkt des vorigen Client-Ticks (Vanilla zeichnet zwischen beiden mit tickDelta). */
    private var playPrev = Double.NaN

    private var newest = Int.MIN_VALUE

    private const val DELAY = 2.2
    private const val JUMP = 14.0
    private const val KEEP = 40

    fun init() {
        ClientPlayNetworking.registerGlobalReceiver(MotionS2C.ID) { p, _ -> receive(p) }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> reset() }
        ClientTickEvents.START_WORLD_TICK.register { _ -> advance() }
        MotionSync.clientHook = { e -> apply(e) }
    }

    private fun reset() {
        tracks.clear()
        offset = Double.NaN
        play = Double.NaN
        playPrev = Double.NaN
        newest = Int.MIN_VALUE
    }

    /**
     * Abspielzeitpunkt (Server-Ticks) zwischen zwei Client-Ticks: dieselbe Zeit, die die Darsteller gerade zeigen. Andere
     * Ströme mit Server-Tick-Stempel (Trage-Rahmen, [RideClient]) laufen auf dieser Achse und bleiben so flüssig und
     * deckungsgleich. NaN, solange noch keine Lage eingetroffen ist.
     */
    fun time(tickDelta: Float): Double {
        if (play.isNaN()) return Double.NaN
        val from = if (playPrev.isNaN()) play else playPrev
        return from + (play - from) * tickDelta.coerceIn(0f, 1f)
    }

    private fun receive(p: MotionS2C) {
        val t = tracks.getOrPut(p.entityId) { Track() }
        val last = t.samples.lastOrNull()
        if (last != null && p.tick <= last.tick) {
            // Server neu gestartet / Welt gewechselt: Achse neu aufsetzen.
            if (p.tick < last.tick - 200) reset() else return
        }
        tracks.getOrPut(p.entityId) { Track() }.let { tr ->
            tr.samples.addLast(Sample(p.tick, Vec3d(p.x, p.y, p.z), p.yaw, p.extra))
            while (tr.samples.size > KEEP) tr.samples.removeFirst()
            tr.lastSeen = clientTick
        }
        if (p.tick > newest) newest = p.tick
        val o = (p.tick - clientTick).toDouble()
        if (offset.isNaN() || o > offset) offset = o
    }

    private fun advance() {
        clientTick++
        if (offset.isNaN()) return
        val before = play
        // Langsam nach unten driften (frühestes Eintreffen kann sich verschlechtern, z. B. bei sinkenden TPS).
        offset -= 0.03
        val target = clientTick + offset - DELAY
        if (play.isNaN() || kotlin.math.abs(target - play) > 30.0) {
            play = target
        } else {
            val err = target - play
            play += 1.0 + MathHelper.clamp(err * 0.12, -0.3, 0.3)
        }
        // At a stalled stream's last sample hold playback there. Rewinding by DELAY
        // replayed the catch and rolled the entire spine backwards when packets resumed.
        if (newest != Int.MIN_VALUE && play > newest) {
            play = newest.toDouble()
            offset = newest.toDouble() - clientTick
        }
        // Neu aufgesetzte Achse: nicht über den Sprung gleiten.
        playPrev = if (before.isNaN() || kotlin.math.abs(play - before) > 3.0) play else before
        if (clientTick % 100 == 0) tracks.entries.removeIf { clientTick - it.value.lastSeen > 200 }
    }

    /** Lage für [e] am aktuellen Abspielzeitpunkt setzen (Ende des Entity-Ticks). */
    private fun apply(e: EirholmPuppet) {
        if (e.vehicle is NivarisEntity) {
            tracks.remove(e.id)
            return
        }
        val t = tracks[e.id] ?: return
        if (clientTick - t.lastSeen > 40 || play.isNaN()) return
        val s = t.samples
        if (s.isEmpty()) return
        val at = play
        val pos: Vec3d
        val yaw: Float
        val extra: Float
        if (at <= s.first().tick) {
            pos = s.first().pos; yaw = s.first().yaw; extra = s.first().extra
        } else if (at >= s.last().tick) {
            pos = s.last().pos; yaw = s.last().yaw; extra = s.last().extra
        } else {
            var k = 0
            while (k < s.size - 2 && s[k + 1].tick <= at) k++
            val a = s[k]
            val b = s[k + 1]
            val span = (b.tick - a.tick).toDouble().coerceAtLeast(1.0)
            val u = ((at - a.tick) / span).coerceIn(0.0, 1.0)
            if (a.pos.squaredDistanceTo(b.pos) > JUMP * JUMP) {
                val pick = if (u < 0.5) a else b
                pos = pick.pos; yaw = pick.yaw; extra = pick.extra
            } else {
                val pa = s.getOrNull(k - 1)?.takeIf { it.pos.squaredDistanceTo(a.pos) <= JUMP * JUMP }
                val pb = s.getOrNull(k + 2)?.takeIf { it.pos.squaredDistanceTo(b.pos) <= JUMP * JUMP }
                // Tangenten je Tick, auf die Spanne dieses Abschnitts skaliert.
                val ma = if (pa != null) b.pos.subtract(pa.pos).multiply(span / (b.tick - pa.tick)) else b.pos.subtract(a.pos)
                val mb = if (pb != null) pb.pos.subtract(a.pos).multiply(span / (pb.tick - a.tick)) else b.pos.subtract(a.pos)
                pos = hermite(a.pos, ma, b.pos, mb, u)
                yaw = a.yaw + MathHelper.wrapDegrees(b.yaw - a.yaw) * u.toFloat()
                extra = if (a.extra.isNaN() || b.extra.isNaN()) b.extra else a.extra + (b.extra - a.extra) * u.toFloat()
            }
        }
        e.setPosition(pos.x, pos.y, pos.z)
        e.yaw = yaw
        if (e is LivingEntity) {
            e.bodyYaw = yaw
            e.headYaw = yaw
        }
        if (e is NivarisEntity && !extra.isNaN()) e.smoothUncoil(extra)
    }

    private fun hermite(p0: Vec3d, m0: Vec3d, p1: Vec3d, m1: Vec3d, t: Double): Vec3d {
        val t2 = t * t
        val t3 = t2 * t
        val h00 = 2 * t3 - 3 * t2 + 1
        val h10 = t3 - 2 * t2 + t
        val h01 = -2 * t3 + 3 * t2
        val h11 = t3 - t2
        return Vec3d(
            p0.x * h00 + m0.x * h10 + p1.x * h01 + m1.x * h11,
            p0.y * h00 + m0.y * h10 + p1.y * h01 + m1.y * h11,
            p0.z * h00 + m0.z * h10 + p1.z * h01 + m1.z * h11,
        )
    }
}
