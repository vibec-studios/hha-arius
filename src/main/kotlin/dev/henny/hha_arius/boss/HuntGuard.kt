package dev.henny.hha_arius.boss

import dev.henny.hha_arius.arena.HuntRoute
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.util.math.Vec3d
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sign

/**
 * Seit 1.10.0 (Crix: „man darf nie von der Brücke geworfen werden“): Während der Jagd wird jeder Rückstoß auf einen
 * Spieler entlang der Route umgelenkt, bevor er den Client erreicht. Am Anfang jedes Server-Ticks (vor dem Welt-Tick,
 * in dem die Bewegungspakete verschickt werden) prüft [clamp] alle Spieler mit frischem Rückstoß (`velocityDirty`):
 * * auf Brücken, Hängebrücken und in Höhlen: kein seitlicher Anteil, höchstens 0,42 nach oben (nicht über die Brüstung),
 * * auf dem Pfad: nichts in Richtung Abhang (Talseite), höchstens 0,6 nach oben,
 * * was seitlich weggenommen wird, wird zur Hälfte zum Stoß entlang des Wegs (der Treffer bleibt spürbar).
 * Die Angriffe der Jagd schicken ihre Stöße ebenfalls durch [redirect].
 */
object HuntGuard {

    fun init() {
        ServerTickEvents.START_SERVER_TICK.register { _ ->
            for (f in Encounters.all()) if (f.chasing && !f.ended) for (p in f.players()) if (p.velocityDirty) clamp(f, p)
        }
    }

    private fun clamp(f: EirholmFight, p: ServerPlayerEntity) {
        val v = redirect(f, p, p.velocity)
        if (v != p.velocity) {
            p.velocity = v
            p.velocityDirty = true
        }
    }

    /** Stoß [v] für [p] in der Jagd: entlang der Route umgelenkt (siehe Klasse). */
    fun redirect(f: EirholmFight, p: ServerPlayerEntity, v: Vec3d): Vec3d {
        val rx = p.x - f.origin.x
        val ry = p.y - f.origin.y
        val rz = p.z - f.origin.z
        val i = HuntRoute.indexAtHeight(floor(rx).toInt(), floor(rz).toInt(), ry, 1.5, 4.0)
        if (i < 0) {
            // Abseits (Feld, Nebengang): nur die Höhe begrenzen.
            return Vec3d(v.x, minOf(v.y, 0.6), v.z)
        }
        val (tx, tz) = HuntRoute.tangent(i)
        val nx = HuntRoute.normalX(i)
        val nz = HuntRoute.normalZ(i)
        val along = v.x * tx + v.z * tz
        // seitliche Achse senkrecht zur Laufrichtung, Vorzeichen so, dass + zur Talseite zeigt
        var sx = -tz
        var sz = tx
        if (sx * nx + sz * nz < 0) { sx = -sx; sz = -sz }
        val lat = v.x * sx + v.z * sz
        val kind = HuntRoute.kind(i)
        val narrow = kind == HuntRoute.BRIDGE || kind == HuntRoute.ROPE || kind == HuntRoute.TUNNEL || kind == HuntRoute.GATE
        val keepLat = if (narrow) 0.0 else minOf(lat, 0.0) // auf dem Pfad: zum Berg hin erlaubt
        val removed = abs(lat - keepLat)
        val dirSign = if (abs(along) > 1e-3) sign(along) else -1.0
        val newAlong = along + dirSign * removed * 0.5
        val vy = minOf(v.y, if (narrow) 0.42 else 0.6)
        return Vec3d(tx * newAlong + sx * keepLat, vy, tz * newAlong + sz * keepLat)
    }
}
