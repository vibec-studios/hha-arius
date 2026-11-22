package dev.henny.hha_arius.gametest

import dev.henny.hha.logic.boss.Cine
import dev.henny.hha_arius.boss.EirholmFight
import dev.henny.hha_arius.boss.EntrancePath
import dev.henny.hha_arius.boss.NivarisShape
import dev.henny.hha_arius.boss.ReturnCoil
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
        if ("return" in scenes) {
            summitReturn()
            stopFight()
        }
        if ("pickup" in scenes) {
            startStage(2)
            tick(60)
            server { fight()?.let { f -> f.startDirector(f.scenes.collapse()) } }
            film("pickup_before", 900, 8) { client { c -> c.player?.hasVehicle() != true } }
            film("pickup_contact", 48, 2)
            stopFight()
        }
        if ("route" in scenes) routeScene()
        if ("continuity" in scenes) {
            val splitProp = System.getProperty("arius.verify.split", "both").lowercase()
            if (splitProp == "open" || splitProp == "both") {
                continuityScene(splitOpen = true, captureFrames = true)
                stopFight()
            }
            if (splitProp == "closed" || splitProp == "both") {
                continuityScene(splitOpen = false, captureFrames = splitProp == "closed")
                stopFight()
            }
        }
    }

    /** Stage the actors on the summit immediately before filming the handoff to the blade. */
    private fun VerifyKit.summitReturn() {
        startFull()
        server { srv ->
            val f = fight() ?: return@server
            f.startDirector(emptySequence())
            Cine.end(f.audience())
            f.onSummit = true
            val c = f.abs(f.summitCenterRel)
            val p = player(srv)
            p.teleport(f.world, c.x + 3.0, c.y, c.z + 6.0, emptySet(), 180f, -10f, false)
            f.arius?.let { f.place(it, c) }
            if (f.nivaris == null) f.spawnNivaris(c.add(0.0, 2.0, 6.0), 0f)
        }
        settle(240)
        server { srv ->
            val f = fight() ?: return@server
            val c = f.abs(f.summitCenterRel)
            val p = player(srv)
            if (p.entityPos.squaredDistanceTo(c) > 18.0 * 18.0) {
                p.teleport(f.world, c.x + 3.0, c.y, c.z + 6.0, emptySet(), 180f, -10f, false)
            }
            f.arius?.let { f.place(it, c) }
            f.startDirector(f.scenes.finale())
        }
        val oldCoil = (0..500).map { EntrancePath.coil(1.0 - it / 500.0) }
            .zipWithNext().sumOf { (a, b) -> a.distanceTo(b) }
        val body = NivarisShape.SEGMENT * NivarisShape.SEGMENTS
        val returnPath = ReturnCoil.length
        check(returnPath >= body)
        log("Return coil: body=%.2f, old helix alone=%.2f, old with neck=%.2f..%.2f, new with neck=%.2f blocks"
            .format(body, oldCoil, oldCoil + legacyCoilNeck(0.0), oldCoil + legacyCoilNeck(1.0), returnPath))
        film("return", 190, 4)
    }

    /** Length of the previous return's neutral neck curve at the two ends of the raise blend. */
    private fun legacyCoilNeck(raise: Double): Double {
        val head = EntrancePath.HEAD_REST.lerp(EntrancePath.HEAD_RAISED, raise)
        val shoulder = EntrancePath.coil(1.0)
        val length = head.distanceTo(shoulder).coerceAtLeast(2.0)
        val direction = Vec3d(0.0, -0.20, 1.0).normalize()
            .lerp(EntrancePath.tangent(0.0).negate(), raise).normalize()
        val first = head.add(direction.multiply(0.37 * length))
        val second = shoulder.add(shoulder.subtract(EntrancePath.coil(0.995)).normalize().multiply(0.36 * length))
        return (0..24).map { i ->
            val t = i / 24.0
            head.lerp(first, t).lerp(first.lerp(second, t), t)
                .lerp(first.lerp(second, t).lerp(second.lerp(shoulder, t), t), t)
        }.zipWithNext().sumOf { (a, b) -> a.distanceTo(b) }
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
        while (guard++ < 1200 && client { c -> c.player?.hasVehicle() == true }) {
            if (guard <= 34 && guard % 3 == 1) shot("carry_pickup_%02d".format(guard / 3))
            if (guard % 20 == 1) shot("carry_%03d".format(guard / 20))
            tick()
        }
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

    /**
     * Durchgehende numerische Kontinuitätsprüfung + dichte Standbilder über die gesamte Solvane-Übergangssequenz
     * (Auftritt/Beschwörung → Nadel → Sturzflug → Landung → Rammstoß & Eisdorn-Fang → Flug in den Horst → Fassrolle
     * → Arius-Absprung → Bremslandung), wahlweise mit offenem oder geschlossenem Bergschnitt.
     */
    private fun VerifyKit.continuityScene(splitOpen: Boolean, captureFrames: Boolean) {
        val tag = if (splitOpen) "open" else "closed"
        startFull()
        server { srv ->
            val f = fight() ?: return@server
            f.startDirector(emptySequence())
            Cine.end(f.audience())
            if (splitOpen) f.scenes.split(animate = false) else f.scenes.restoreSplitInstant()
            val a = f.arius
            if (a != null) {
                f.place(a, f.abs(dev.henny.hha_arius.arena.ArenaLayout.WATCH_SEAT))
                a.yaw = 0f; a.headYaw = 0f; a.bodyYaw = 0f
                a.bladeState = dev.henny.hha_arius.boss.AriusBlade.HELD
                a.sheathed = false
                a.play(dev.henny.hha_arius.boss.AriusPose.IDLE)
            }
            val p = player(srv)
            val land = f.abs(dev.henny.hha_arius.arena.ArenaLayout.LANDING[0])
            p.teleport(f.world, land.x, land.y, land.z, emptySet(), 180f, -4f, false)
            p.onLanding()
        }
        tick(16)
        settle(240)
        server { fight()?.let { f -> f.startDirector(f.scenes.arrival()) } }

        // 5 Pflicht-Kennzahlen über die gesamte Sequenz Tick für Tick:
        var maxSegMove = 0.0
        var maxSegInfo = "none"
        val topMoves = ArrayList<Pair<Double, String>>()
        var segSolidSamples = 0
        var segSolidLogged = 0
        var camSolidTicks = 0
        var camSolidLogged = 0
        var playerBodyTicks = 0
        var playerBodyLogged = 0
        var maxFootSlide = 0.0
        var maxFootInfo = "none"

        var prevPts: Array<Vec3d>? = null
        var prevRadii: DoubleArray? = null
        var prevDragonId = -1
        var prevMode = -1
        var planted = false
        var landIdx = 0
        var foreAnchor: Vec3d? = null
        var hindAnchor: Vec3d? = null

        var inCarry = false
        var carryStartTick = -1
        var rollStartTick = -1
        var dismountSeenTick = -1
        var aerieNearTick = -1
        var aerieLandedTick = -1
        var wasAriusPerched = false
        val shotsTaken = HashMap<String, Int>()
        fun take(prefix: String): String {
            val n = shotsTaken.getOrDefault(prefix, 0)
            shotsTaken[prefix] = n + 1
            return "${prefix}_%03d".format(n)
        }

        fun distPointToSeg(p: Vec3d, a: Vec3d, b: Vec3d): Double {
            val ab = b.subtract(a)
            val len2 = ab.lengthSquared()
            val t = if (len2 < 1e-8) 0.0 else (p.subtract(a).dotProduct(ab) / len2).coerceIn(0.0, 1.0)
            return p.distanceTo(a.add(ab.multiply(t)))
        }

        fun isSolid(world: net.minecraft.world.World, pos: Vec3d): Pair<Boolean, String> {
            val bp = net.minecraft.util.math.BlockPos.ofFloored(pos)
            val st = world.getBlockState(bp)
            if (st.isAir) return false to ""
            val solid = !st.getCollisionShape(world, bp).isEmpty && st.isSolidBlock(world, bp)
            return solid to st.block.translationKey
        }

        var seqTick = 0
        val maxTicks = 1750
        while (seqTick < maxTicks) {
            val ph = phase()
            if (ph >= EirholmFight.Phase.GLACIER && aerieLandedTick >= 0 && seqTick > aerieLandedTick + 32) break
            // Aktuellen Server-Zustand (Entitäten, Welt, Herkunft) lesen.
            data class SSnap(
                val origin: net.minecraft.util.math.BlockPos,
                val aerie: Vec3d,
                val dId: Int,
                val dMode: Int,
                val dPose: Int,
                val dFlying: Boolean,
                val dRoll: Float,
                val dPos: Vec3d?,
                val pPos: Vec3d,
                val pMounted: Boolean,
                val pInvis: Boolean,
                val aMounted: Boolean,
            )
            val s = server { srv ->
                val f = fight() ?: return@server null
                val d = f.nivaris
                val p = player(srv)
                val a = f.arius
                SSnap(
                    f.origin,
                    f.abs(Vec3d.of(dev.henny.hha_arius.arena.ArenaLayout.AERIE)).add(0.5, 0.0, 0.5),
                    d?.id ?: -1,
                    d?.mode ?: -1,
                    d?.pose ?: -1,
                    d?.flying ?: false,
                    d?.bodyRoll ?: 0f,
                    d?.entityPos,
                    p.entityPos,
                    d != null && p.vehicle === d,
                    p.hasStatusEffect(net.minecraft.entity.effect.StatusEffects.INVISIBILITY),
                    d != null && a?.vehicle === d,
                )
            } ?: break

            if (s.aMounted) wasAriusPerched = true
            if (!inCarry && landIdx >= 1 && (s.dPose == dev.henny.hha_arius.boss.NivarisPose.TAKEOFF || s.dFlying)) {
                inCarry = true
                carryStartTick = seqTick
            }
            if (inCarry && rollStartTick < 0 && kotlin.math.abs(s.dRoll) > 0.08f) rollStartTick = seqTick
            if (inCarry && wasAriusPerched && !s.aMounted && dismountSeenTick < 0) dismountSeenTick = seqTick
            if (inCarry && aerieNearTick < 0 && s.dPos != null && s.dPos.distanceTo(s.aerie) < 55.0) aerieNearTick = seqTick
            if (inCarry && aerieLandedTick < 0 && !s.dFlying && s.dPos != null && s.dPos.distanceTo(s.aerie) < 6.0) aerieLandedTick = seqTick

            // Entscheiden, ob in diesem Tick ein Standbild für eine der 11 Prüfungen gemacht wird.
            var shotName: String? = null
            if (captureFrames) {
                val ct = if (carryStartTick >= 0) seqTick - carryStartTick else -1
                shotName = when {
                    // Item 1: Schwert-Erwachen (Arius hebt Solvane, Edge FX, Blade Stream)
                    seqTick in 44..136 && seqTick % 4 == 0 -> take("i01_sword")
                    // Item 2: Sturm/Whiteout-Aufbau + erste Silhouette + Enthüllung
                    seqTick in 118..226 && seqTick % 4 == 0 -> take("i02_storm")
                    // Item 3: Nadel-Hängeschüsse (Klauen, Flügel, Kopf, Brüllen)
                    seqTick in 228..444 && seqTick % 6 == 0 -> take("i03_spire")
                    // Item 4: Sturzflug & Wand-Freiheit beim Auftritt
                    seqTick in 446..636 && seqTick % 6 == 0 -> take("i04_dive")
                    // Item 5: Anflug → Landung ohne Teleport/Snap (Übergang UNCOIL → NORMAL + Aufsetzen)
                    !inCarry && seqTick in 620..735 && seqTick % 3 == 0 -> take("i05_land1")
                    // Item 6: Rammstoß + Hochschleudern + verzweifelter Fang am Eisdorn
                    ct in 0..54 && ct % 2 == 0 -> take("i06_ram")
                    // Item 7: Abheben mit glattem Rücken (keine Buckel)
                    ct in 16..88 && ct % 3 == 0 -> take("i07_takeoff")
                    // Item 8: Südtor-, Obelisk-, Tempel- und Hochland-Freiheit
                    ct in 36..156 && ct % 4 == 0 -> take("i08_wall")
                    // Item 9: Fassrolle & freier Abwurf im Horst
                    rollStartTick >= 0 && seqTick in (rollStartTick - 2)..(rollStartTick + 48) && (seqTick - rollStartTick) % 2 == 0 -> take("i09_roll")
                    // Item 10: Arius' tiefer Vorbeiflug & Absprung auf sein Podest
                    dismountSeenTick >= 0 && seqTick in (dismountSeenTick - 8)..(dismountSeenTick + 36) && (seqTick - dismountSeenTick) % 2 == 0 -> take("i10_arius")
                    // Item 11: Bremslandung im Horst ohne Fußrutschen
                    aerieNearTick >= 0 && (aerieLandedTick < 0 || seqTick <= aerieLandedTick + 26) && seqTick % 2 == 0 -> take("i11_land2")
                    else -> null
                }
            } else if (!splitOpen && seqTick in 228..444 && seqTick % 12 == 0) {
                // Auch bei geschlossenem Berg Nadel-Standbilder sichern (intakte Eisspitze + Klauengriff prüfen).
                shotName = take("closed_i03_spire")
            }

            // Ein Frame rendern (mit oder ohne Screenshot), damit DragonHits & Kamera genau diesem Tick entsprechen.
            if (shotName != null) {
                shot(shotName)
            } else {
                client { c ->
                    c.gameRenderer.render(net.fabricmc.fabric.mixin.client.gametest.screenshot.DeltaTrackerDefaultValueAccessor.create(1f), true)
                }
                tick()
            }

            // Jetzt Client-Daten (Kamera + gerenderte Körperglieder aus DragonHits) auslesen und prüfen.
            val camPos = client { c -> c.gameRenderer.camera.cameraPos }
            val bodyData = client { _ ->
                if (s.dId < 0) null else {
                    val pts = dev.henny.hha_arius.client.fx.DragonHits.points(s.dId)?.map { it }?.toTypedArray()
                    val rad = dev.henny.hha_arius.client.fx.DragonHits.radii(s.dId)?.copyOf()
                    if (pts != null && rad != null) pts to rad else null
                }
            }

            // (3) Kamera in soliden Blöcken prüfen (auf Server-Welt, die garantiert geladen ist).
            server { srv ->
                val f = fight() ?: return@server
                val w = f.world
                val (camHit, camBlk) = isSolid(w, camPos)
                if (camHit) {
                    camSolidTicks++
                    if (camSolidLogged++ < 20) {
                        log("[$tag] CAM_SOLID t=$seqTick rel=${fmt(camPos.subtract(s.origin.x.toDouble(), s.origin.y.toDouble(), s.origin.z.toDouble()))} blk=$camBlk")
                    }
                }

                if (bodyData != null) {
                    val (pts, rad) = bodyData
                    // (1) Größte Tick-Bewegung jedes Glieds 0..40 (Kopf bis Schwanzspitze) bei gleichem Drachen.
                    if (prevPts != null && prevRadii != null && prevDragonId == s.dId) {
                        for (i in pts.indices) {
                            val seg = i.coerceAtMost(rad.lastIndex)
                            if (rad[seg] <= 0.0 || prevRadii!![seg] <= 0.0) continue
                            val dMove = pts[i].distanceTo(prevPts!![i])
                            if (dMove > maxSegMove) {
                                maxSegMove = dMove
                                maxSegInfo = "t=$seqTick seg=$i mode=$prevMode->${s.dMode} pose=${s.dPose} fly=${s.dFlying} from=${fmt(prevPts!![i])} to=${fmt(pts[i])}"
                            }
                            if (dMove > 2.2) {
                                topMoves.add(dMove to "t=$seqTick seg=$i mode=$prevMode->${s.dMode} pose=${s.dPose} move=%.2f".format(dMove))
                            }
                        }
                    }

                    // (2) Glied-Proben in soliden Blöcken (alle sichtbaren Glieder + Mittelpunkte).
                    for (i in 0 until NivarisShape.SEGMENTS) {
                        if (rad[i] <= 0.0) continue
                        val a0 = pts[i]
                        val b0 = pts[i + 1]
                        for (q in arrayOf(a0, a0.lerp(b0, 0.5))) {
                            val (hit, blk) = isSolid(w, q)
                            if (hit) {
                                segSolidSamples++
                                if (segSolidLogged++ < 30) {
                                    log("[$tag] SEG_SOLID t=$seqTick seg=$i mode=${s.dMode} pose=${s.dPose} rel=${fmt(q.subtract(s.origin.x.toDouble(), s.origin.y.toDouble(), s.origin.z.toDouble()))} blk=$blk")
                                }
                            }
                        }
                    }

                    // (4) Spieler-Überschneidung mit Solvanes Körper (nur wenn Spieler sichtbar und nicht auf Dorn aufgesessen).
                    if (!s.pInvis && !s.pMounted) {
                        val probes = arrayOf(
                            s.pPos.add(0.0, 0.25, 0.0),
                            s.pPos.add(0.0, 0.9, 0.0),
                            s.pPos.add(0.0, 1.55, 0.0),
                        )
                        var hitBody = false
                        var hitDetail = ""
                        outer@ for (i in 0 until NivarisShape.SEGMENTS) {
                            if (rad[i] <= 0.0) continue
                            val bodyR = NivarisShape.taper(i) * 6.0 * NivarisShape.SCALE / 16.0
                            val thresh = bodyR + 0.25
                            for (pr in probes) {
                                val dist = distPointToSeg(pr, pts[i], pts[i + 1])
                                if (dist < thresh) {
                                    hitBody = true
                                    hitDetail = "seg=$i dist=%.2f<%.2f mode=${s.dMode} pose=${s.dPose} p=${fmt(s.pPos)} s=${fmt(pts[i])}".format(dist, thresh)
                                    break@outer
                                }
                            }
                        }
                        if (hitBody) {
                            playerBodyTicks++
                            if (playerBodyLogged++ < 25) log("[$tag] PLAYER_BODY t=$seqTick $hitDetail")
                        }
                    }

                    // (5) Größtes Fußrutschen nach dem Aufsetzen (vorn = FORE_GLIED, hinten = HIND_GLIED).
                    val grounded = s.dMode == dev.henny.hha_arius.boss.NivarisMode.NORMAL && !s.dFlying && rad[NivarisShape.FORE_GLIED] > 0.0
                    if (grounded) {
                        val fPos = pts[NivarisShape.FORE_GLIED]
                        val hPos = pts[NivarisShape.HIND_GLIED]
                        if (!planted) {
                            planted = true
                            landIdx++
                            foreAnchor = fPos
                            hindAnchor = hPos
                        } else {
                            val fs = hypot(fPos.x - foreAnchor!!.x, fPos.z - foreAnchor!!.z)
                            val hs = hypot(hPos.x - hindAnchor!!.x, hPos.z - hindAnchor!!.z)
                            val m = maxOf(fs, hs)
                            if (m > maxFootSlide) {
                                maxFootSlide = m
                                maxFootInfo = "land#$landIdx t=$seqTick fore=%.3f hind=%.3f pose=${s.dPose}".format(fs, hs)
                            }
                        }
                    } else {
                        planted = false
                    }

                    prevPts = pts
                    prevRadii = rad
                    prevDragonId = s.dId
                    prevMode = s.dMode
                }
            }
            seqTick++
        }

        val summary = buildString {
            appendLine("=== CONTINUITY REPORT (split=$tag, ticks=$seqTick) ===")
            appendLine("1. max_segment_move_per_tick = %.3f blocks (%s)".format(maxSegMove, maxSegInfo))
            appendLine("2. segment_samples_in_solid = $segSolidSamples")
            appendLine("3. camera_ticks_in_solid = $camSolidTicks")
            appendLine("4. player_ticks_intersecting_body = $playerBodyTicks")
            appendLine("5. max_foot_slide_planted = %.3f blocks (%s)".format(maxFootSlide, maxFootInfo))
            if (topMoves.isNotEmpty()) {
                appendLine("Top moves > 2.2 blocks:")
                topMoves.sortedByDescending { it.first }.take(10).forEach { appendLine("  ${it.second}") }
            }
            appendLine("Frames captured: $shotsTaken")
        }
        log(summary)
        java.io.File(out, "continuity_$tag.txt").writeText(summary)
    }
}
