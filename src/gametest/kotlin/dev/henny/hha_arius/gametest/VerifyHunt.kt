package dev.henny.hha_arius.gametest

import dev.henny.hha.logic.boss.Cine
import dev.henny.hha_arius.arena.RouteCheck
import dev.henny.hha_arius.boss.Hunt
import dev.henny.hha_arius.net.HuntFx
import dev.henny.hha_arius.net.HuntNet
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.minecraft.block.Blocks
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Sichtprüfung "hunt": nur mit -Darius.verify=hunt (siehe [VerifyKit]). Fährt den echten Kampf bis in die Jagd und
 * schaut sich Reihenfolge entlang der Route an: Auge, Geröll, Hängebrücke, Seile kappen, Tunnel, Eis, zweite Brücke.
 * Teile über -Darius.verify.part=check,hud,eye,bridge,cut,tunnel,ice,fan.
 */
class VerifyHunt : FabricClientGameTest {

    private val parts = System.getProperty("arius.verify.part", "check,hud,eye,bridge,cut,tunnel,ice,fan").split(',').map { it.trim() }.toSet()

    override fun runTest(context: ClientGameTestContext) = VerifyKit.run(context, "hunt") {
        startFull()
        if ("far" in parts) {
            far()
            if (parts.size == 1) return@run
        }
        if ("moon" in parts) {
            moon()
            if (parts.size == 1) return@run
        }
        if ("mtn" in parts) {
            mountainCut()
            if (parts.size == 1) return@run
        }
        val h = reachHunt()
        if (h == null) {
            log("FEHLER: Jagd nicht erreicht, Phase ${phase()}")
            return@run
        }
        log("Jagd erreicht")
        server { h.debugQuiet(200000) }
        tick(40)
        if ("check" in parts) check(h)
        val caves = server { h.debugCaves() }
        val spans = server { h.debugSpans() }
        log("Höhlen: " + caves.joinToString { "%s %.0f-%.0f".format(it.first, it.second, it.third) })
        log("Brücken: " + spans.joinToString { "%.0f-%.0f".format(it.first, it.second) })
        if ("hud" in parts) hud(h)
        if ("eye" in parts) caves.firstOrNull { it.first == "DARK" }?.let { eyeAndSeal(h, it.second) }
        if ("bridge" in parts) bridge(h, spans[0], "b1")
        if ("cut" in parts) cut(h, spans[0])
        if ("solvane" in parts) {
            cut(h, spans[0], fullScene = true)
            caves.firstOrNull { it.first == "LAKE" }?.let { tunnel(h, it.second, it.third, fullScene = true) }
        }
        // Der Reihe nach die Route hinauf: Wer zurückspringt, wird von der Jagd auf den sicheren Punkt gerettet.
        if ("ice" in parts) caves.firstOrNull { it.first == "CRYSTAL" }?.let { ice(h, it.second) }
        if ("arius" in parts) ariusRoute(h, spans[0].second + 40.0)
        if ("tunnel" in parts) caves.firstOrNull { it.first == "LAKE" }?.let { tunnel(h, it.second, it.third) }
        if ("fan" in parts) bridge(h, spans[1], "b3")
        if ("seal" in parts) sealedCaves(h)
        log("fertig")
    }

    /** Fernlandschaft: Standbilder aus mehreren Blickwinkeln und Bildzeit mit und ohne die Fern-Berge. */
    private fun VerifyKit.far() {
        waitUntil(900, 2) {
            server { srv -> Cine.requestSkip(player(srv)) }
            !client { dev.henny.hha.client.cine.CinePlayer.active }
        }
        tick(20)
        cmd("gamemode creative @p")
        cmd("time set 6000")
        val spots = listOf(
            Triple("temple_south", Vec3d(0.5, 6.0, -44.0), Vec3d(0.0, 60.0, 250.0)),
            Triple("plateau_high", Vec3d(-30.0, 60.0, -120.0), Vec3d(0.0, 80.0, 250.0)),
            Triple("east_field", Vec3d(300.0, 12.0, 40.0), Vec3d(0.0, 90.0, 250.0)),
            Triple("west", Vec3d(-300.0, 30.0, 120.0), Vec3d(0.0, 90.0, 250.0)),
            Triple("summit", Vec3d(0.5, 190.0, 225.0), Vec3d(-260.0, 40.0, 0.0)),
        )
        for ((name, rel, look) in spots) {
            server { srv ->
                val p = player(srv)
                val f = fight() ?: return@server
                p.abilities.flying = true
                p.sendAbilitiesUpdate()
                val at = f.abs(rel)
                val d = f.abs(look).subtract(at)
                val yaw = (Math.toDegrees(atan2(d.z, d.x)) - 90.0).toFloat()
                val pitch = (-Math.toDegrees(atan2(d.y, sqrt(d.x * d.x + d.z * d.z)))).toFloat()
                p.teleport(p.entityWorld as ServerWorld, at.x, at.y, at.z, emptySet(), yaw, pitch, false)
                p.velocity = Vec3d.ZERO
            }
            tick(120)
            settle(400)
            tick(40)
            shot("far_$name")
            val on = frameMs()
            client { dev.henny.hha_arius.client.fx.HighlandViewClient.debugOff = true }
            val off = frameMs()
            client { dev.henny.hha_arius.client.fx.HighlandViewClient.debugOff = false }
            log("far $name: frame %.2f ms with mountains, %.2f ms without; %s".format(on, off, client { dev.henny.hha_arius.client.fx.HighlandViewClient.debugStats() }))
        }
        cmd("gamemode survival @p")
    }

    /** Der gefrorene Mond am echten Himmel: Entstehen, voll ausgebildet, kurz vor dem Sturz. */
    private fun VerifyKit.moon() {
        waitUntil(900, 2) {
            server { srv -> Cine.requestSkip(player(srv)) }
            !client { dev.henny.hha.client.cine.CinePlayer.active }
        }
        cmd("gamemode creative @p")
        val ticks = 200
        server { srv ->
            val p = player(srv)
            val f = fight() ?: return@server
            p.abilities.flying = true
            p.sendAbilitiesUpdate()
            val at = f.abs(Vec3d(0.5, 4.0, -60.0))
            p.teleport(p.entityWorld as ServerWorld, at.x, at.y, at.z, emptySet(), 0f, -32f, false)
            p.velocity = Vec3d.ZERO
            f.fxAll(dev.henny.hha_arius.net.FxKind.FROZEN_STAR, f.abs(Vec3d(0.5, 0.0, -34.0)), a = 20f, b = ticks.toFloat(), life = ticks + 40)
        }
        for ((k, wait) in listOf(50, 50, 40, 30).withIndex()) {
            tick(wait)
            shot("moon_%d".format(k))
        }
        cmd("gamemode survival @p")
        tick(60)
    }

    /** Bergschnitt und Tritt aus dem Tempel, ein Bild je 8 Ticks. */
    private fun VerifyKit.mountainCut() {
        server { fight()?.let { f -> f.startDirector(f.scenes.theCut()) } }
        var i = 0
        var t = 0
        while (t < 840 && phase() < dev.henny.hha_arius.boss.EirholmFight.Phase.GLACIER) {
            shot("mtn_%03d".format(i++))
            // Der Tritt selbst dichter: Treffer, Salto, Aufprall, Aufstehen.
            val step = if (t in 350..520) 3 else 8
            tick(step)
            t += step
        }
        log("mountain cut: $i frames, phase ${phase()}")
    }

    /** Mittlere Zeit eines echten Bildes (ohne Tick-Warten). */
    private fun VerifyKit.frameMs(n: Int = 40): Double = client { c ->
        val delta = net.fabricmc.fabric.mixin.client.gametest.screenshot.DeltaTrackerDefaultValueAccessor.create(1f)
        repeat(5) { c.gameRenderer.render(delta, true) }
        val t0 = System.nanoTime()
        repeat(n) { c.gameRenderer.render(delta, true) }
        (System.nanoTime() - t0) / (n * 1e6)
    }

    /** Nach dem Einsturz muss von vor dem Geröll ein begehbarer Weg (2 hoch, Stufen bis 1) hinter das Geröll führen. */
    private fun VerifyKit.sealedCaves(h: Hunt) {
        val pairs = server { h.debugSealAll() }
        tick(5)
        val results = server {
            val w = fight()!!.world
            fun low(p: BlockPos): Boolean { val s = w.getBlockState(p).getCollisionShape(w, p); return s.isEmpty || s.getMax(net.minecraft.util.math.Direction.Axis.Y) <= 0.5 }
            fun stand(p: BlockPos) = low(p) && w.getBlockState(p.up()).getCollisionShape(w, p.up()).isEmpty && !w.getBlockState(p.down()).getCollisionShape(w, p.down()).isEmpty
            pairs.map { (a, b) ->
                val goal = BlockPos.ofFloored(b.x, b.y + 0.05, b.z)
                val start = BlockPos.ofFloored(a.x, a.y + 0.05, a.z)
                val seen = hashSetOf(start)
                val queue = ArrayDeque(listOf(start))
                var found = false
                while (queue.isNotEmpty() && seen.size < 40000 && !found) {
                    val c = queue.removeFirst()
                    if (c.getSquaredDistance(goal) <= 4.0) found = true
                    for (dx in -1..1) for (dz in -1..1) for (dy in -1..1) {
                        if ((dx == 0) == (dz == 0)) continue
                        val q = c.add(dx, dy, dz)
                        if (kotlin.math.abs(q.x - start.x) > 34 || kotlin.math.abs(q.z - start.z) > 34 || q in seen || !stand(q)) continue
                        seen.add(q)
                        queue.addLast(q)
                    }
                }
                found
            }
        }
        log("Eingestürzte Höhlen mit freiem Weg: ${results.count { it }} von ${results.size}: $results")
    }

    private fun VerifyKit.reachHunt(): Hunt? {
        var n = 0
        while (n < 9000) {
            server<Hunt?> { Hunt.current }?.let { return it }
            server { srv -> Cine.requestSkip(player(srv)) }
            if (n % 20 == 0) cmd("execute as @p run hhaarius skip")
            tick(2)
            n += 2
        }
        return null
    }

    private fun VerifyKit.check(h: Hunt) {
        val rail = server {
            val f = fight()!!
            val pts = h.debugRail()
            var solid = 0
            var k = 0
            while (k < pts.size) {
                val spine = BlockPos.ofFloored(pts[k].add(0.0, 1.8, 0.0))
                if (!f.world.getBlockState(spine).getCollisionShape(f.world, spine).isEmpty) solid++
                k += 2
            }
            "%d Punkte, davon im Gelände: %d".format(pts.size, solid)
        }
        log("Begleitbahn: $rail")
        val plan = server { RouteCheck.plan(400) }
        log("RouteCheck Plan: ${plan.size}")
        plan.groupBy { it.what.substringBefore(':').take(24) }.forEach { (k, v) -> log("  $k: ${v.size}, z. B. ${v.first()}") }
        val live = server { val f = fight()!!; RouteCheck.world(f.world, f.origin, 400) }
        log("RouteCheck Welt: ${live.size}")
        live.groupBy { it.what.substringBefore(':').take(24) }.forEach { (k, v) -> log("  $k: ${v.size}, z. B. ${v.first()}") }
    }

    private fun VerifyKit.tp(h: Hunt, arc: Double, lookAt: Vec3d? = null, settleTicks: Int = 30) {
        server { srv ->
            val p = player(srv)
            val q = h.debugPoint(arc)
            val eye = q.add(0.0, 1.62, 0.0)
            val t = lookAt ?: h.debugPoint(arc + 6.0).add(0.0, 1.5, 0.0)
            val d = t.subtract(eye)
            val yaw = (Math.toDegrees(atan2(d.z, d.x)) - 90.0).toFloat()
            val pitch = (-Math.toDegrees(atan2(d.y, sqrt(d.x * d.x + d.z * d.z)))).toFloat()
            p.teleport(p.entityWorld as ServerWorld, q.x, q.y + 0.05, q.z, emptySet(), yaw, pitch, false)
            p.velocity = Vec3d.ZERO
        }
        tick(settleTicks)
    }

    private fun VerifyKit.waitScene(h: Hunt, max: Int = 200): Boolean = waitUntil(max, 1) { server { h.debugInScene() } }

    private fun VerifyKit.waitNoScene(h: Hunt, max: Int = 400) {
        waitUntil(max, 2) { server { !h.debugInScene() && !h.debugPending() } }
        tick(6)
    }

    private fun VerifyKit.hud(h: Hunt) {
        tp(h, 20.0)
        settle(200)
        shot("hud_plain")
        server { srv -> HuntNet.fx(player(srv), HuntFx.LETTERBOX, player(srv).entityPos, life = 80) }
        tick(30)
        shot("hud_letterbox")
        log("Leben: " + server { "%.0f / %.0f".format(fight()!!.ariusHp, fight()!!.ariusMax) })
        tick(60)
    }

    private fun VerifyKit.eyeAndSeal(h: Hunt, a0: Double) {
        tp(h, a0 + 12.0, settleTicks = 2)
        val eye = waitScene(h)
        log("Augenszene gestartet: $eye")
        tick(18)
        shot("eye_0")
        tick(16)
        shot("eye_1")
        tick(14)
        shot("eye_2")
        waitNoScene(h)
        shot("eye_after")
        val seal = waitScene(h, 260)
        log("Geröllszene gestartet: $seal")
        tick(6)
        shot("seal_0")
        tick(12)
        shot("seal_1")
        tick(14)
        shot("seal_2")
        waitNoScene(h)
        shot("seal_after")
    }

    private fun VerifyKit.bridge(h: Hunt, span: Pair<Double, Double>, name: String) {
        val mid = (span.first + span.second) / 2
        tp(h, mid, settleTicks = 20)
        settle(300)
        burst("${name}_along", 3, 8)
        val side = server {
            val a = h.debugPoint(mid)
            val b = h.debugPoint(mid + 3.0)
            val d = b.subtract(a).normalize()
            a.add(-d.z * 8.0, 0.2, d.x * 8.0)
        }
        tp(h, mid - 3.0, lookAt = side, settleTicks = 8)
        shot("${name}_side")
        tp(h, mid - 4.0, lookAt = h_down(h, mid), settleTicks = 8)
        shot("${name}_down")
    }

    private fun VerifyKit.h_down(h: Hunt, arc: Double): Vec3d = server { h.debugPoint(arc + 3.0).add(0.0, 0.0, 0.0) }

    private fun VerifyKit.cut(h: Hunt, span: Pair<Double, Double>, fullScene: Boolean = false) {
        val len = span.second - span.first
        val onBridge = span.first + len * 0.45
        val look = server { h.debugPoint(onBridge).add(0.0, 1.0, 0.0) }
        val head = server { h.debugPoint(span.second).add(0.0, 2.0, 0.0) }
        tp(h, span.second - 9.0, lookAt = head, settleTicks = 14)
        shot("cut_marker")
        tp(h, span.second + 0.5, lookAt = look, settleTicks = 20)
        server { h.debugArius(onBridge) }
        tick(12)
        shot("cut_prompt")
        val before = server { fight()!!.ariusHp }
        ctx.getInput().holdKey { it.sneakKey }
        tick(10)
        shot("cut_progress")
        val started = waitScene(h, 60)
        ctx.getInput().releaseKey { it.sneakKey }
        log("Seilszene gestartet: $started")
        if (fullScene && started) waitUntil(650, 2) { client { dev.henny.hha.client.cine.CinePlayer.active } }
        for (k in 0 until if (fullScene) 32 else 9) {
            shot("cut_scene_%02d".format(k))
            tick(if (fullScene) 4 else 9)
        }
        waitNoScene(h)
        shot("cut_after")
        log("Arius Leben vor/nach Sturz: %.0f / %.0f".format(before, server { fight()!!.ariusHp }))
        log("Arius danach: " + server { fight()!!.arius?.entityPos.toString() })
    }

    /** Lässt Arius mit echten Angriffen 30 s hinter dem Spieler herjagen und zählt Ticks, in denen er in Blöcken steckt. */
    private fun VerifyKit.ariusRoute(h: Hunt, from: Double) {
        val goal = from + 130.0
        tp(h, goal, settleTicks = 10)
        server { h.debugArius(from); h.debugQuiet(0) }
        var inside = 0
        var worst = ""
        var maxArc = 0.0
        val seen = HashSet<BlockPos>()
        repeat(600) {
            tick(1)
            server { srv ->
                val f = fight() ?: return@server
                val a = f.arius ?: return@server
                val w = f.world
                val feet = BlockPos.ofFloored(a.x, a.y + 0.3, a.z)
                // Dünne Schneeschichten zählen nicht: Er steckt nur fest, wenn die Oberkante wirklich über seinen Füßen liegt.
                val feetShape = w.getBlockState(feet).getCollisionShape(w, feet)
                val feetBlocked = !feetShape.isEmpty && feet.y + feetShape.getMax(net.minecraft.util.math.Direction.Axis.Y) > a.y + 0.3
                if (feetBlocked || !w.getBlockState(feet.up()).getCollisionShape(w, feet.up()).isEmpty) {
                    inside++
                    if (worst.isEmpty()) worst = "%.1f %.1f %.1f".format(a.x, a.y, a.z)
                    if (seen.size < 10 && seen.add(feet.toImmutable())) {
                        val arc = h.debugAriusArc()
                        val q = h.debugPoint(arc)
                        log("  in Block: pos %.2f %.2f %.2f, Füße %s, Kopf %s, Bogen %.1f, Abstand zur Mitte %.2f (dy %.2f), Pose %d".format(
                            a.x, a.y, a.z, w.getBlockState(feet).block.translationKey.substringAfterLast('.'), w.getBlockState(feet.up()).block.translationKey.substringAfterLast('.'),
                            arc, sqrt((a.x - q.x) * (a.x - q.x) + (a.z - q.z) * (a.z - q.z)), a.y - q.y, a.pose,
                        ))
                    }
                }
                maxArc = maxOf(maxArc, h.debugAriusArc())
            }
        }
        server { h.debugQuiet(200000) }
        log("Arius-Route: 600 Ticks von Bogen %.0f bis %.0f, in Blöcken: %d %s".format(from, maxArc, inside, worst))
        waitNoScene(h)
        tick(40)
    }

    private fun VerifyKit.tunnel(h: Hunt, a0: Double, a1: Double, fullScene: Boolean = false) {
        log("Tunnellänge %.1f".format(a1 - a0))
        tp(h, a0 + 5.0, settleTicks = 2)
        val started = waitScene(h)
        log("Tunnelszene gestartet: $started")
        if (fullScene && started) waitUntil(650, 2) { client { dev.henny.hha.client.cine.CinePlayer.active } }
        for (k in 0 until if (fullScene) 30 else 10) {
            shot("tunnel_%02d".format(k))
            tick(if (fullScene) 4 else 6)
        }
        waitNoScene(h)
        shot("tunnel_after")
    }

    private fun VerifyKit.ice(h: Hunt, a0: Double) {
        tp(h, a0 + 17.0, settleTicks = 10)
        val count = {
            server { srv ->
                val p = player(srv)
                val w = p.entityWorld
                var water = 0
                var ice = 0
                for (pos in BlockPos.iterate(p.blockPos.add(-22, -6, -22), p.blockPos.add(22, 12, 22))) {
                    val st = w.getBlockState(pos)
                    if (st.isOf(Blocks.WATER)) water++
                    if (st.isOf(Blocks.ICE)) ice++
                }
                water to ice
            }
        }
        val before = count()
        // Jeden Eisblock direkt zufällig ticken lassen (das ist der Weg, auf dem Vanilla-Eis neben Licht schmilzt).
        val lit = server { srv ->
            val p = player(srv)
            val w = p.entityWorld as ServerWorld
            var bright = 0
            val cells = BlockPos.iterate(p.blockPos.add(-22, -6, -22), p.blockPos.add(22, 12, 22)).map { it.toImmutable() }.filter { w.getBlockState(it).isOf(Blocks.ICE) }
            for (pos in cells) {
                if (w.getLightLevel(net.minecraft.world.LightType.BLOCK, pos) > 10) bright++
                repeat(64) { w.getBlockState(pos).randomTick(w, pos, w.random) }
            }
            bright
        }
        tick(10)
        val after = count()
        log("Eis in der Kristallgrotte: Wasser %d -> %d, Eis %d -> %d (davon %d hell genug zum Schmelzen)".format(before.first, after.first, before.second, after.second, lit))
        shot("ice_wall")
    }
}
