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
        if ("blade" in scenes) {
            bladeScene()
            stopFight()
        }
        if ("route" in scenes) routeScene()
    }

    /** Solvane wird wieder zur Klinge: oben um die Eisspitze wie beim Auftritt. */
    private fun VerifyKit.bladeScene() {
        startFull()
        server { srv ->
            val f = fight() ?: return@server
            f.onSummit = true
            val c = f.abs(f.summitCenterRel)
            player(srv).teleport(f.world, c.x, c.y + 1.0, c.z + 6.0, emptySet(), 180f, -10f, false)
            f.arius?.let { a -> f.place(a, c.add(0.0, 0.2, -5.0)) }
            if (f.nivaris == null) f.spawnNivaris(c.add(0.0, 2.0, 6.0), 0f)
        }
        tick(120)
        settle(300)
        server { fight()?.let { f -> f.startDirector(f.scenes.finale()) } }
        film("blade", 420, 8)
    }

    /** Auftritt bis zur Landung und Aufsteigen der Spieler in dichten Bildern. */
    private fun VerifyKit.revealScene() {
        startFull()
        server { fight()?.let { f -> f.startDirector(dev.henny.hha_arius.boss.entrance(f)) } }
        film("reveal", 860, 12)
    }

    /** Vom Pfiff über den ganzen Ritt bis zum Abwurf: Bilder an jedem Hindernis, Tempo und Geländeabstand im Protokoll. */
    private fun VerifyKit.routeScene() {
        dev.henny.hha_arius.boss.RideSeats.testSeat = System.getProperty("arius.verify.seat")?.toIntOrNull()
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
        // Gesamte Drehung der Flugrichtung beim Anflug (mehrere 360°-Schleifen fielen hier sofort auf).
        var turned = 0.0
        var lp: Vec3d? = null
        var lh: Vec3d? = null
        val measure = {
            server { fight()?.nivaris?.entityPos }?.let { q ->
                lp?.let { o ->
                    val m = Vec3d(q.x - o.x, 0.0, q.z - o.z)
                    if (m.lengthSquared() > 1e-3) {
                        val h = m.normalize()
                        lh?.let { b -> turned += Math.toDegrees(kotlin.math.acos(b.dotProduct(h).coerceIn(-1.0, 1.0))) }
                        lh = h
                    }
                }
                lp = q
            }
            Unit
        }
        film("pick", 900, 10, before = measure) { client { c -> c.player?.hasVehicle() != true } }
        film("pick2", 90, 6, before = measure)
        log("pick-up: total heading change %.0f deg".format(turned))
        val camMode = System.getProperty("arius.verify.cam", "")
        val sideCam = camMode == "side" || camMode == "rider"
        var straightDone = false
        var curveDone = false
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
        var rolls = 0
        var lastTick = 0
        val seatAt = 620
        var sideOn = false
        while (n++ < 2600 && client { c -> c.player?.hasVehicle() == true }) {
            val pos = server { fight()?.nivaris?.entityPos } ?: break
            val rel = server { fight()?.let { pos.subtract(it.origin.x.toDouble(), it.origin.y.toDouble(), it.origin.z.toDouble()) } } ?: break
            var best = Double.MAX_VALUE
            for (k in maxOf(0, idx - 8)..minOf(route.size - 1, idx + 40)) {
                val dq = route[k].squaredDistanceTo(rel)
                if (dq < best) { best = dq; idx = k }
            }
            val s = idx * 0.5
            val serverTick = server { it.ticks }
            last?.let {
                val dt = (serverTick - lastTick).coerceAtLeast(1)
                val v = it.distanceTo(pos) / dt
                maxSpeed = maxOf(maxSpeed, v)
                if (n > 3 && dt == 1) maxAccel = maxOf(maxAccel, kotlin.math.abs(v - lastSpeed))
                lastSpeed = v
            }
            lastTick = serverTick
            last = pos
            // Nahaufnahme von Arius auf ihrem Kopf: einmal im ruhigen Flug, einmal während eines Prompts.
            if (camMode == "rider" && !straightDone && s in 425.0..460.0) {
                straightDone = true
                poseBurst("straight")
            }
            if (camMode == "rider" && !curveDone && s in 655.0..690.0) {
                curveDone = true
                poseBurst("curve")
            }
            if (camMode != "rider" && (n == 240 || n == seatAt)) {
                server { Cine.start(listOf(player(it))) }
                repeat(5) { k ->
                    headCam()
                    tick(2)
                    shot("seat%d_%d".format(if (n == 240) 0 else 1, k))
                }
                server { Cine.end(listOf(player(it))) }
                tick(2)
            }
            val top = server { srv -> player(srv).entityWorld.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, kotlin.math.floor(pos.x).toInt(), kotlin.math.floor(pos.z).toInt()).toDouble() }
            val near = arcs.indexOfFirst { kotlin.math.abs(it - s) < 13.0 }
            if (top > -60.0 && near < 0) minClear = minOf(minClear, pos.y - top)
            val cine = client { dev.henny.hha.client.cine.CinePlayer.active }
            if (cine && !sideOn) {
                if (n % 3 == 0) shot("roll_%03d".format(rolls++))
            } else {
                lookAlong()
                if (sideCam) {
                    val want = arcs.any { kotlin.math.abs(it - s) < 34.0 }
                    if (want && !sideOn) {
                        sideOn = true
                        server { Cine.start(listOf(player(it))) }
                    } else if (!want && sideOn) {
                        sideOn = false
                        server { Cine.end(listOf(player(it))) }
                    }
                    if (want) { if (camMode == "rider") riderCam() else rideCam(15.0, 5.0, 8.0) }
                }
                if (near >= 0 && n % 3 == 0 && shots[near] < 10) {
                    val third = !sideCam && near % 2 == 1
                    client { c -> c.options.perspective = if (third) Perspective.THIRD_PERSON_BACK else Perspective.FIRST_PERSON }
                    shot("ob%d_%02d".format(near, shots[near]++))
                    client { c -> c.options.perspective = Perspective.FIRST_PERSON }
                } else if (n % 90 == 0) {
                    shot("fly_%03d".format(n / 90))
                }
            }
            tick()
        }
        if (sideOn) server { Cine.end(listOf(player(it))) }
        log("ride: ticks=$n maxSpeed=%.3f maxAccel=%.3f minClear=%.1f".format(maxSpeed, maxAccel, minClear))
        var fall = 0
        film("drop", 260, 4, before = {
            if (fall++ % 8 == 0) log("fall %d player=%s".format(fall, client { c -> c.player?.entityPos?.let { fmt(it) } }))
        }) { phase() < EirholmFight.Phase.VAULT }
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
        // Horst: Abstand zur Flugmitte und echte Blöcke auf der Flugbahn (Rippen, Felswand).
        var maxR = 0.0
        var solid = 0
        var samples = 0
        film("air", 1000, 8, before = {
            watchDragon()
            server { srv ->
                val f = fight() ?: return@server
                val d = f.nivaris ?: return@server
                if (!d.flying) return@server
                val c = f.arenaCenter.add(0.0, 0.0, 15.0)
                maxR = maxOf(maxR, kotlin.math.hypot(d.x - c.x, d.z - c.z))
                samples++
                val w = player(srv).entityWorld
                for (dx in intArrayOf(-2, 0, 2)) for (dz in intArrayOf(-2, 0, 2)) for (dy in intArrayOf(0, 2, 4)) {
                    val q = net.minecraft.util.math.BlockPos.ofFloored(d.x + dx, d.y + dy, d.z + dz)
                    if (!w.getBlockState(q).isAir && w.getBlockState(q).isSolidBlock(w, q)) {
                        if (solid++ < 12) log("air hit rel=%d/%d/%d %s pose=%d".format(q.x - f.origin.x, q.y - f.origin.y, q.z - f.origin.z, w.getBlockState(q).block.translationKey, d.pose))
                    }
                }
            }
        })
        log("air: flying samples=$samples maxR=%.1f solidHits=$solid".format(maxR))
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

    /** Nah neben dem Kopf, leicht von vorn: sitzt Arius wirklich auf dem Schädel? */
    private fun VerifyKit.headCam() = server { srv ->
        val f = fight() ?: return@server
        val d = f.nivaris ?: return@server
        val fwd = f.facing(d)
        val s = Vec3d(-fwd.z, 0.0, fwd.x)
        val head = d.entityPos.add(fwd.multiply(6.4)).add(0.0, 2.9, 0.0)
        val cam = head.add(s.multiply(6.0)).add(fwd.multiply(1.6)).add(0.0, 1.7, 0.0)
        val v = fwd.multiply(1.5)
        Cine.camera(listOf(player(srv)), cam, cam.add(v), head, head.add(v), 4, 42.0, 42.0, null, 0.0)
    }

    /** Fährt neben ([top] = false) oder über dem eigenen Reiter mit: Haltung und Beine im Fahrtwind. */
    private fun VerifyKit.riderCam(top: Boolean = false) {
        val at = client { c -> c.player?.getLerpedPos(1f) } ?: return
        server { srv ->
            val f = fight() ?: return@server
            val d = f.nivaris ?: return@server
            val fwd = f.facing(d)
            val side = Vec3d(-fwd.z, 0.0, fwd.x)
            val focus = at.add(0.0, 1.55, 0.0).subtract(fwd.multiply(0.8))
            val cam = if (top) focus.add(0.0, 5.0, 0.0).subtract(fwd.multiply(1.0)) else focus.add(side.multiply(5.0)).add(0.0, 1.0, 0.0)
            val v = fwd.multiply(2.8)
            Cine.camera(listOf(player(srv)), cam, cam.add(v), focus, focus.add(v), 4, 50.0, 50.0, null, 0.0)
        }
    }

    /** Aufeinanderfolgende Bilder der Haltung von der Seite und von oben. */
    private fun VerifyKit.poseBurst(name: String) {
        server { Cine.start(listOf(player(it))) }
        repeat(8) { k ->
            riderCam()
            tick(2)
            shot("pose_${name}_side_$k")
        }
        repeat(4) { k ->
            riderCam(top = true)
            tick(2)
            shot("pose_${name}_top_$k")
        }
        server { Cine.end(listOf(player(it))) }
        tick(2)
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
