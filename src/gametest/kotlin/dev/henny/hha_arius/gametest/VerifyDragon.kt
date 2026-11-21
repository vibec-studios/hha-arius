package dev.henny.hha_arius.gametest

import dev.henny.hha.logic.boss.Cine
import dev.henny.hha_arius.boss.EirholmFight
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.minecraft.client.option.Perspective
import net.minecraft.util.math.MathHelper
import net.minecraft.util.math.Vec3d
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Sichtprüfung "dragon": nur mit -Darius.verify=dragon (siehe [VerifyKit]). Welche Abschnitte laufen, steht in
 * `-Darius.verify.scene=cut,air,ride` (Standard: alle). Bilder: `<szene>_<nnn>.png`.
 */
class VerifyDragon : FabricClientGameTest {
    override fun runTest(context: ClientGameTestContext) = VerifyKit.run(context, "dragon") {
        val scenes = System.getProperty("arius.verify.scene", "cut,air,ride").split(',').map { it.trim() }.toSet()
        if ("cut" in scenes) {
            cutToAerie()
            stopFight()
        }
        if ("air" in scenes) {
            airPhase()
            stopFight()
        }
        if ("coil" in scenes) {
            coilScene()
            stopFight()
        }
        if ("ride" in scenes) rideScene()
        if ("reveal" in scenes) {
            revealScene()
            stopFight()
        }
        if ("route" in scenes) routeScene()
    }

    /** Auftritt bis zur Landung und Aufsteigen der Spieler in dichten Bildern. */
    private fun VerifyKit.revealScene() {
        startFull()
        server { fight()?.let { f -> f.startDirector(dev.henny.hha_arius.boss.entrance(f)) } }
        film("reveal", 860, 12)
    }

    /** Vom Pfiff über den ganzen Ritt bis zum Abwurf: Bilder an jedem Hindernis, Tempo und Geländeabstand im Protokoll. */
    private fun VerifyKit.routeScene() {
        startStage(2)
        tick(60)
        var answers = 0
        val failing = System.getProperty("arius.verify.fail", "2,5").split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()
        dev.henny.hha_arius.boss.Qte.testAnswer = { kind ->
            val n = answers++
            log("qte #$n $kind -> ${n !in failing}")
            n !in failing
        }
        server { fight()?.let { f -> f.startDirector(f.scenes.collapse()) } }
        film("pick", 900, 10) { client { c -> c.player?.hasVehicle() != true } }
        film("pick2", 90, 6)
        val route = dev.henny.hha_arius.boss.RideRoute.points()
        val arcs = dev.henny.hha_arius.boss.RideRoute.obstacleArcs
        var last: Vec3d? = null
        var lastSpeed = 0.0
        var maxSpeed = 0.0
        var maxAccel = 0.0
        var minClear = Double.MAX_VALUE
        var idx = 0
        var n = 0
        val shots = IntArray(arcs.size)
        while (n++ < 2600 && client { c -> c.player?.hasVehicle() == true }) {
            val pos = server { fight()?.nivaris?.entityPos } ?: break
            val rel = server { fight()?.let { pos.subtract(it.origin.x.toDouble(), it.origin.y.toDouble(), it.origin.z.toDouble()) } } ?: break
            var best = Double.MAX_VALUE
            for (k in maxOf(0, idx - 8)..minOf(route.size - 1, idx + 40)) {
                val dq = route[k].squaredDistanceTo(rel)
                if (dq < best) { best = dq; idx = k }
            }
            val s = idx * 0.5
            last?.let {
                val v = it.distanceTo(pos)
                maxSpeed = maxOf(maxSpeed, v)
                if (n > 3) maxAccel = maxOf(maxAccel, kotlin.math.abs(v - lastSpeed))
                lastSpeed = v
            }
            last = pos
            val top = server { srv -> player(srv).entityWorld.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, kotlin.math.floor(pos.x).toInt(), kotlin.math.floor(pos.z).toInt()).toDouble() }
            val near = arcs.indexOfFirst { kotlin.math.abs(it - s) < 13.0 }
            if (top > -60.0 && near < 0) minClear = minOf(minClear, pos.y - top)
            if (near >= 0 && n % 3 == 0 && shots[near] < 10) {
                val third = near % 2 == 1
                client { c -> c.options.perspective = if (third) Perspective.THIRD_PERSON_BACK else Perspective.FIRST_PERSON }
                shot("ob%d_%02d".format(near, shots[near]++))
            } else if (n % 90 == 0) {
                client { c -> c.options.perspective = Perspective.FIRST_PERSON }
                shot("fly_%03d".format(n / 90))
            }
            tick()
        }
        client { c -> c.options.perspective = Perspective.FIRST_PERSON }
        log("ride: ticks=$n maxSpeed=%.3f maxAccel=%.3f minClear=%.1f".format(maxSpeed, maxAccel, minClear))
        film("drop", 260, 4) { phase() < EirholmFight.Phase.VAULT }
        film("after", 40, 10)
        val riders = server { fight()?.nivaris?.passengerList?.size }
        log("riders after drop on server: $riders, client vehicle: ${client { c -> c.player?.hasVehicle() }}")
        dev.henny.hha_arius.boss.Qte.testAnswer = null
    }

    private fun fmt(v: Vec3d) = "%.1f/%.1f/%.1f".format(v.x, v.y, v.z)

    private fun VerifyKit.stopFight() {
        cmd("hhaarius stop")
        cmd("effect clear @p")
        tick(60)
    }

    /** Standbilder alle [every] Ticks, solange [cond] gilt (höchstens [max] Ticks). */
    private fun VerifyKit.film(name: String, max: Int, every: Int = 12, before: () -> Unit = {}, cond: () -> Boolean = { true }) {
        var i = 0
        var t = 0
        while (t < max && cond()) {
            before()
            shot("${name}_%03d".format(i++))
            repeat(every) {
                before()
                tick()
            }
            t += every
        }
        log("$name: $i frames, phase=${phase()}")
    }

    /** Blick des Spielers auf die Drachin (damit Flug und Körper im Bild sind). */
    private fun VerifyKit.watchDragon() = client { c ->
        val p = c.player ?: return@client
        val d = c.world?.entities?.firstOrNull { it is dev.henny.hha_arius.boss.NivarisEntity } ?: return@client
        val dx = d.x - p.x
        val dy = d.y + 2.0 - p.eyeY
        val dz = d.z - p.z
        p.yaw = Math.toDegrees(atan2(-dx, dz)).toFloat()
        p.pitch = MathHelper.clamp((-Math.toDegrees(atan2(dy, hypot(dx, dz)))).toFloat(), -70f, 70f)
        p.headYaw = p.yaw
    }

    /** Blick in Flugrichtung der Drachin, leicht nach unten auf den Dorn. */
    private fun VerifyKit.lookAlong() = client { c ->
        val p = c.player ?: return@client
        val d = p.vehicle ?: return@client
        p.yaw = d.yaw
        p.pitch = 8f
        p.headYaw = d.yaw
    }

    /** Schnitt → Tritt aus dem Tempel → Auftritt an der Nadel → Flug in den Horst → Abwurf. */
    private fun VerifyKit.cutToAerie() {
        startFull()
        server { fight()?.let { f -> f.startDirector(f.scenes.theCut()) } }
        film("cut", 2800, 12) { phase() in 0 until EirholmFight.Phase.GLACIER && client { c -> c.player?.hasVehicle() != true } }
        // Ritt in den Horst: Tick für Tick bis zum Abwurf, dann dichte Bilder und die Lagen im Protokoll.
        var guard = 0
        while (guard++ < 1200 && client { c -> c.player?.hasVehicle() == true }) tick()
        for (i in 0 until 14) {
            val sv = server { srv -> val f = fight(); "arius=%s ride=%s player=%s".format(f?.arius?.entityPos?.let { fmt(it) }, f?.arius?.hasVehicle(), fmt(player(srv).entityPos)) }
            val cl = client { c -> "player=%s".format(c.player?.entityPos?.let { fmt(it) }) }
            log("throw $i server[$sv] client[$cl]")
            shot("throw_%02d".format(i))
            tick(2)
        }
        film("cut2", 200, 12) { phase() in 0 until EirholmFight.Phase.GLACIER }
        film("cut_after", 60, 12)
    }

    private fun VerifyKit.airPhase() {
        startStage(2)
        tick(60)
        film("air", 1000, 8, before = { watchDragon() })
    }

    /** Kamera fliegt seitlich neben Kopf und erstem Dorn mit (Haltung der Reiter, Arius, Hindernisse). */
    private fun VerifyKit.rideCam(side: Double, up: Double, back: Double) = server { srv ->
        val f = fight() ?: return@server
        val d = f.nivaris ?: return@server
        val fwd = f.facing(d)
        val s = Vec3d(-fwd.z, 0.0, fwd.x)
        val at = d.entityPos
        val cam = at.add(s.multiply(side)).add(0.0, up, 0.0).subtract(fwd.multiply(back))
        val look = at.add(0.0, 3.6, 0.0).subtract(fwd.multiply(2.0))
        val v = fwd.multiply(7.5)
        Cine.camera(listOf(player(srv)), cam, cam.add(v), look, look.add(v), 6, 52.0, 52.0, null, 0.0)
    }

    /** Kamera schräg über dem eigenen Reiter: Beine zur Seite, Arme am Dorn, nichts in der Hand. */
    private fun VerifyKit.poseCam() {
        val at = client { c -> c.player?.entityPos } ?: return
        server { srv ->
            val f = fight() ?: return@server
            val d = f.nivaris ?: return@server
            val fwd = f.facing(d)
            val eye = at.add(0.0, 1.2, 0.0)
            val cam = eye.add(0.0, 3.6, 0.0).subtract(fwd.multiply(2.2))
            val v = fwd.multiply(5.0)
            Cine.camera(listOf(player(srv)), cam, cam.add(v), eye, eye.add(v), 4, 62.0, 62.0, null, 0.0)
        }
    }

    /** Auftritt an der Nadel: Bilder und der Schleier von Server und Client. */
    private fun VerifyKit.coilScene() {
        startFull()
        server { fight()?.let { f -> f.startDirector(dev.henny.hha_arius.boss.entrance(f)) } }
        var n = 0
        film("coil", 560, 12, before = {
            if (n++ % 24 == 0) {
                val sv = server { fight()?.nivaris?.let { "veil=%.2f mode=%d pos=%s".format(it.veil, it.mode, it.blockPos.toShortString()) } }
                val cl = client { c -> c.world?.entities?.firstOrNull { it is dev.henny.hha_arius.boss.NivarisEntity }?.let { e -> e as dev.henny.hha_arius.boss.NivarisEntity; "veil=%.2f at=%.2f mode=%d".format(e.veil, e.veilAt(0f), e.mode) } }
                log("coil tick $n server[$sv] client[$cl]")
            }
        })
    }

    private fun VerifyKit.rideScene() {
        startStage(2)
        tick(40)
        server { fight()?.let { f -> f.startDirector(f.scenes.collapse()) } }
        film("ride_intro", 240, 12) { client { c -> c.player?.hasVehicle() != true } }
        film("ride1p", 400, 12, before = { lookAlong() })
        server { Cine.start(listOf(player(it))) }
        var n = 0
        film("ridetop", 96, 12, before = { if (n++ % 4 == 0) poseCam() })
        server { Cine.end(listOf(player(it))) }
        film("ride_end", 1500, 24) { phase() < EirholmFight.Phase.VAULT }
    }
}
