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
