package dev.henny.hha_arius.gametest

import dev.henny.hha_arius.boss.AriusBlade
import dev.henny.hha_arius.boss.AriusPose
import dev.henny.hha_arius.boss.CineScene
import dev.henny.hha_arius.boss.Hold
import dev.henny.hha_arius.boss.Scenes
import dev.henny.hha_arius.boss.Script
import dev.henny.hha_arius.boss.script
import dev.henny.hha_arius.client.fx.ParryHud
import com.mojang.authlib.GameProfile
import dev.henny.hha_arius.boss.Damage
import dev.henny.hha_arius.boss.EirholmFight
import dev.henny.hha_arius.boss.PlayerHit
import dev.henny.hha_arius.boss.Summit
import io.netty.channel.embedded.EmbeddedChannel
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.minecraft.block.Blocks
import net.minecraft.entity.decoration.DisplayEntity
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.network.ClientConnection
import net.minecraft.network.NetworkSide
import net.minecraft.network.packet.c2s.common.SyncedClientOptions
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ConnectedClientData
import net.minecraft.server.network.ServerCommonNetworkHandler
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.util.Hand
import net.minecraft.util.math.Box
import net.minecraft.util.math.Vec3d
import java.util.UUID
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Sichtprüfung "boss": nur mit -Darius.verify=boss (siehe [VerifyKit]). Welche Fälle laufen, bestimmt
 * -Darius.verify.case=a,b,c.
 */
class VerifyBoss : FabricClientGameTest {
    override fun runTest(context: ClientGameTestContext) = VerifyKit.run(context, "boss") {
        val cases = System.getProperty("arius.verify.case", "punish").split(',').map { it.trim() }
        val failures = ArrayList<String>()
        for (c in cases) {
            log("=== case $c")
            try {
                when (c) {
                    "stance" -> stance()
                    "punish" -> punish()
                    "parry" -> parry()
                    "exact" -> parryExact()
                    "classic" -> classic()
                    "mace" -> mace()
                    "macecatch" -> maceCatch("temple")
                    "macecatchvault" -> maceCatch("vault")
                    "macecatchfinale" -> maceCatch("finale")
                    "macefloat" -> maceFloat()
                    "parrycue" -> parryCue()
                    "forge" -> forge()
                    "transformreal" -> transformReal()
                    "templehorizon" -> templeHorizon()
                    "horizonviews" -> horizonViews()
                    "yanglater" -> yangLater()
                    "vaultpace" -> vaultPace()
                    "fallrecover" -> fallRecovery()
                    "kickpose" -> kickPose()
                    "clash", "laststand", "climax" -> climax()
                    "hole" -> hole(feed = true)
                    "holefail" -> hole(feed = false)
                    "duo" -> duo()
                    "waves" -> waves()
                    "shield" -> shield()
                    "tele" -> teleGallery()
                    "path" -> pathing()
                    "ice" -> iceBlade()
                    "fx" -> fxGallery()
                    "pace" -> pace()
                    else -> log("unknown case $c")
                }
            } catch (e: IllegalStateException) {
                log("$c failed: ${e.stackTraceToString()}")
                failures.add(c)
            } finally {
                cmd("hhaarius stop")
                tick(40)
            }
        }
        check(failures.isEmpty()) { "Failed verify cases: $failures" }
    }

    private fun VerifyKit.parryCue() {
        temple()
        faceArius(4.5)
        server { srv ->
            val f = checkNotNull(fight())
            f.calls.reset()
            f.startDirector(script { while (true) yield(Unit) })
            f.calls.ring(player(srv).entityPos, 2.8f, 32)
        }
        check(waitUntil(18, 1) { client { ParryHud.debugEdges() != null } })
        shot("parry_closing")
        check(waitUntil(35, 1) { client { ParryHud.debugEdges()?.let { it.first > 1 && it.second < -1 } == true } })
        shot("parry_now")
        log("parry cue: ring and open window reached through Eirholm ground warning")
    }

    private fun VerifyKit.maceCatch(stage: String) {
        when (stage) {
            "vault" -> vault()
            "finale" -> finaleFight()
            else -> temple()
        }
        // Replace the director only between attacks: an attack dropped mid-way never clears AriusMoves.acting.
        waitUntil(400, 1) { server { fight()?.moves?.acting == false } }
        server {
            val f = checkNotNull(fight())
            f.startDirector(script { while (true) yield(Unit) })
            f.calls.reset()
            f.guardUntil = 0
            f.ariusOpenUntil = 0
            f.ariusVulnerable = true
            f.arius!!.play(AriusPose.IDLE)
            // Reproducible: open floor at the arena centre. Next to a pillar the catch is refused on purpose.
            val c = f.arenaCenter
            f.place(f.arius!!, Vec3d(c.x, f.floorAt(f.arius!!), c.z))
        }
        faceArius(1.45)
        addBots(1)
        godMode = false
        fun smash(bot: Boolean): Boolean = server { srv ->
            val f = checkNotNull(fight())
            val p = if (bot) checkNotNull(srv.playerManager.getPlayer(bots.single().id)) else player(srv)
            val a = f.arius!!
            val spot = a.entityPos.add(f.facing(a).multiply(1.45))
            p.teleport(f.world, spot.x, spot.y + 1.35, spot.z, emptySet(), p.yaw, 12f, false)
            p.setStackInHand(Hand.MAIN_HAND, ItemStack(Items.MACE))
            p.fallDistance = 6.0
            p.isOnGround = false
            fullCharge(p)
            p.attack(a)
            Hold.isHeld(p)
        }
        try {
            repeat(2) {
                check(!smash(bot = false))
                tick(12)
            }
            check(!smash(bot = true))
            keepBots(2) { srv ->
                val f = checkNotNull(fight())
                val bot = srv.playerManager.getPlayer(bots.single().id)!!
                val pos = f.arius!!.entityPos.add(f.facing(f.arius!!).multiply(7.0))
                bot.teleport(f.world, pos.x, pos.y, pos.z, emptySet(), bot.yaw, bot.pitch, false)
            }
            if (!smash(bot = false)) {
                server { srv ->
                    val f = checkNotNull(fight())
                    val a = f.arius!!
                    val p = player(srv)
                    val mc = EirholmFight::class.java.getDeclaredField("maceCatch").apply { isAccessible = true }.get(f)
                    val plan = mc.javaClass.getDeclaredMethod("plan", dev.henny.hha_arius.boss.AriusEntity::class.java, ServerPlayerEntity::class.java)
                        .apply { isAccessible = true }.invoke(mc, a, p)
                    // Mirror of MaceCatch.clear for the first axis: report which part is blocked.
                    val c = Vec3d(a.x, f.floorAt(a), a.z)
                    val d0 = Vec3d(p.x - a.x, 0.0, p.z - a.z).normalize()
                    fun blocked(b: Box) = f.world.getBlockCollisions(null, b).iterator().hasNext()
                    log("below arius blocked=${blocked(Box(a.x - 0.3, a.y - 0.3, a.z - 0.3, a.x + 0.3, a.y - 0.01, a.z + 0.3))}")
                    for (deg in intArrayOf(0, 90)) {
                        val r = Math.toRadians(deg.toDouble())
                        val ax = Vec3d(d0.x * kotlin.math.cos(r) - d0.z * kotlin.math.sin(r), 0.0, d0.x * kotlin.math.sin(r) + d0.z * kotlin.math.cos(r))
                        var th = -1.95
                        val bad = ArrayList<String>()
                        while (th <= 1.95) {
                            val rad = ax.multiply(kotlin.math.sin(th)).add(0.0, kotlin.math.cos(th), 0.0)
                            val cen = c.add(0.0, 1.55, 0.0).add(rad.multiply(2.3))
                            val anc = Vec3d(cen.x, maxOf(c.y, cen.y - 0.9), cen.z)
                            if (!f.world.isSpaceEmpty(p, p.boundingBox.offset(anc.subtract(p.entityPos)))) bad.add("anchor%.2f".format(th))
                            for (rho in doubleArrayOf(1.2, 2.2, 3.2)) {
                                val q = c.add(0.0, 1.55, 0.0).add(rad.multiply(rho))
                                val y = maxOf(q.y, c.y + 0.45)
                                if (blocked(Box(q.x - 0.3, y - 0.3, q.z - 0.3, q.x + 0.3, y + 0.3, q.z + 0.3))) bad.add("fig%.2f/%.1f".format(th, rho))
                            }
                            th += 0.12
                        }
                        for (side in intArrayOf(1, -1)) for (dist in doubleArrayOf(1.2, 2.0, 2.8, 3.6)) {
                            val q = c.add(ax.multiply(side * dist))
                            if (blocked(Box(q.x - 0.3, c.y + 0.05, q.z - 0.3, q.x + 0.3, c.y + 0.8, q.z + 0.3))) bad.add("body$side/$dist")
                            if (!blocked(Box(q.x - 0.2, c.y - 0.3, q.z - 0.2, q.x + 0.2, c.y - 0.02, q.z + 0.2))) bad.add("nofloor$side/$dist")
                        }
                        log("axis $deg: ${bad.take(12)}")
                    }
                    log("mace catch refused: acting=${f.moves.acting} countering=${f.moves.countering} grab=${Hold.canGrab(p)} " +
                        "arius=${a.entityPos} floor=${f.floorAt(a)} player=${p.entityPos} plan=${plan != null} " +
                        "telegraph=${f.players().any { f.calls.hasBossTelegraph(it) }}")
                }
                error("third smash was not caught")
            }
            // Frames along the whole scene: catch, heave, each slam, lying, getting up.
            val frames = mapOf(3 to "catch", 7 to "grip", 11 to "hold", 16 to "heave", 20 to "overhead", 23 to "down1",
                31 to "up2", 34 to "over2", 72 to "lying", 82 to "getup", 88 to "kneel")
            var health = server { player(it).health }
            var strikes = 0
            for (t in 1..100) {
                tick(1)
                frames[t]?.let { shot("mace_${stage}_${t}_$it") }
                if (System.getProperty("arius.verify.part") == "every" && t % 2 == 0) shot("mace_${stage}_seq_%03d".format(t))
                val current = server { player(it).health }
                if (current < health) {
                    strikes++
                    shot("mace_${stage}_${t}_slam$strikes")
                }
                health = current
                if (t > 20 && server { !Hold.isHeld(player(it)) }) break
            }
            tick(4)
            shot("mace_${stage}_after")
            check(strikes == 4 && health in 1f..<20f) { "strikes $strikes health $health" }
            check(server { !Hold.isHeld(player(it)) })
            log("mace catch ($stage): counters stayed per fighter, four slams, released at health $health")
        } finally {
            godMode = true
            server {
                player(it).setStackInHand(Hand.MAIN_HAND, ItemStack(dev.henny.hha_arius.gear.GearItems.SOLVANE))
                releaseBots(it)
            }
        }
    }

    /** Like [forge], but in the phase the real fight uses (COLLAPSE), so the scene's camera clamping is the real one. */
    private fun VerifyKit.transformReal() {
        vault()
        server {
            val f = checkNotNull(fight())
            f.setPhase(EirholmFight.Phase.COLLAPSE)
            val scene = CineScene(f) {}
            val method = Scenes::class.java.getDeclaredMethod("transformBody", CineScene::class.java).apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val body = method.invoke(f.scenes, scene) as Script
            f.calls.reset()
            f.ariusVulnerable = false
            f.arius!!.bladeState = AriusBlade.NONE
            f.startDirector(scene.play(body))
        }
        tick(20)
        shot("transform_shatter")
        tick(25)
        shot("transform_shatter_late")
        check(waitUntil(135, 1) { server { fight()?.arius?.pose == AriusPose.FORGE_ICE } })
        tick(18)
        shot("transform_hand")
        check(waitUntil(25, 1) { server { fight()?.arius?.bladeState == AriusBlade.ICE } })
        tick(10)
        shot("transform_ice_blade")
    }

    private fun VerifyKit.forge() {
        vault()
        server {
            val f = checkNotNull(fight())
            val scene = CineScene(f) {}
            val method = Scenes::class.java.getDeclaredMethod("transformBody", CineScene::class.java).apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val body = method.invoke(f.scenes, scene) as Script
            f.calls.reset()
            f.ariusVulnerable = false
            f.arius!!.bladeState = AriusBlade.NONE
            f.startDirector(scene.play(body))
        }
        check(waitUntil(135, 1) { server { fight()?.arius?.pose == AriusPose.FORGE_ICE } })
        tick(18)
        shot("forge_empty_hand")
        tick(8)
        shot("forge_forming")
        check(waitUntil(25, 1) { server { fight()?.arius?.bladeState == AriusBlade.ICE } })
        tick(10)
        shot("forge_ice_blade")
    }

    private fun VerifyKit.kickPose() {
        temple()
        server {
            val f = checkNotNull(fight())
            val scene = CineScene(f) { }
            val method = Scenes::class.java.getDeclaredMethod("kick", CineScene::class.java).apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val body = method.invoke(f.scenes, scene) as Script
            f.calls.reset()
            f.ariusVulnerable = false
            f.place(f.arius!!, f.abs(dev.henny.hha_arius.arena.ArenaLayout.CUT_STAND))
            f.startDirector(scene.play(body))
        }
        // Capture every beat of the kick anticipation + strike + launch:
        // calm opening -> weight shift/plant foot -> knee chambered -> kick snap -> extended kick hitstop -> flight.
        tick(16)
        shot("kick_opening")
        check(waitUntil(80, 1) { server { fight()?.arius?.pose == AriusPose.KICK_PREP } })
        tick(5)
        shot("kick_plant")
        tick(4)
        shot("kick_knee_up")
        tick(4)
        shot("kick_knee_peak")
        tick(3)
        shot("kick_extend")
        tick(2)
        shot("kick_impact")
        tick(16)
        shot("kick_flight")
    }

    private fun VerifyKit.templeHorizon() {
        temple()
        server { srv ->
            val f = checkNotNull(fight())
            val c = f.abs(dev.henny.hha_arius.arena.ArenaLayout.CUT_STAND)
            val p = player(srv)
            p.teleport(f.world, c.x + 5.2, f.floorY, c.z + 11.5, emptySet(), 155f, -6f, false)
            f.calls.reset()
            f.startDirector(f.scenes.theCut())
        }
        // 0) Close-up cinematic shot of Arius raising Solvane overhead in the vertical plane (before title).
        check(waitUntil(60, 1) { server { fight()?.arius?.pose == AriusPose.HORIZON_RAISE } })
        tick(16)
        shot("temple_horizon_pose")
        // 1) Overhead pose + vertical frost line forming (during HORIZON_RAISE telegraph).
        check(waitUntil(120, 1) { server { fight()?.arius?.pose == AriusPose.HORIZON_RAISE && fight()?.calls?.hasBossTelegraph(player(it)) == true } })
        tick(18)
        shot("temple_horizon_charge")
        tick(24)
        shot("temple_horizon_silence")
        // 2) Vertical downward cut release (CUT_RELEASE = 6 ticks before impact).
        check(waitUntil(60, 1) { server { fight()?.arius?.pose == AriusPose.CUT_RELEASE } })
        shot("temple_horizon_anticipation")
        tick(6)
        shot("temple_horizon_release")
        tick(4)
        shot("temple_horizon_travel")
        // 3) Mountain split frames during cutAftermath (splitFull runs across 24 ticks starting ~44 ticks after release).
        tick(46)
        shot("temple_horizon_mountain_1")
        tick(10)
        shot("temple_horizon_mountain_2")
        tick(28)
        shot("temple_horizon_summit_split")
    }

    /** White Horizon peak release + 2 consecutive follow-up ticks in 1st person, 3rd person, and blizzard on white snow. */
    private fun VerifyKit.horizonViews() {
        try {
            // 1) First-person view during live White Horizon in the temple (CUT_RELEASE is 6 ticks before release)
            client { it.options.perspective = net.minecraft.client.option.Perspective.FIRST_PERSON }
            temple()
            server { srv ->
                val f = checkNotNull(fight())
                val c = f.abs(dev.henny.hha_arius.arena.ArenaLayout.CUT_STAND)
                val p = player(srv)
                p.teleport(f.world, c.x + 4.0, f.floorY, c.z + 10.0, emptySet(), 155f, -4f, false)
                f.calls.reset()
                f.startDirector(f.scenes.theCut())
            }
            check(waitUntil(180, 1) { server { fight()?.arius?.pose == AriusPose.CUT_RELEASE } })
            tick(6)
            shot("horizon_1p_peak")
            shot("horizon_1p_p1")
            shot("horizon_1p_p2")
            cmd("hhaarius stop")
            tick(35)

            // 2) Third-person view during live White Horizon in the temple
            temple()
            client { it.options.perspective = net.minecraft.client.option.Perspective.THIRD_PERSON_BACK }
            server { srv ->
                val f = checkNotNull(fight())
                val c = f.abs(dev.henny.hha_arius.arena.ArenaLayout.CUT_STAND)
                val p = player(srv)
                p.teleport(f.world, c.x + 5.0, f.floorY, c.z + 12.0, emptySet(), 158f, 4f, false)
                f.calls.reset()
                f.startDirector(f.scenes.theCut())
            }
            check(waitUntil(180, 1) { server { fight()?.arius?.pose == AriusPose.CUT_RELEASE } })
            tick(6)
            shot("horizon_3p_peak")
            shot("horizon_3p_p1")
            shot("horizon_3p_p2")
            cmd("hhaarius stop")
            tick(35)

            // 3) First-person view on white snow inside a blizzard storm (lastHorizon: SPIN_CUT is 6 ticks before release)
            client { it.options.perspective = net.minecraft.client.option.Perspective.FIRST_PERSON }
            vault()
            // Pause the vault AI and let any in-flight tasks/telegraphs settle before starting an isolated lastHorizon.
            server {
                val f = checkNotNull(fight())
                f.startDirector(dev.henny.hha_arius.boss.script { while (true) yield(Unit) })
                f.calls.reset()
            }
            tick(40)
            server { srv ->
                val f = checkNotNull(fight())
                val a = checkNotNull(f.arius)
                val center = f.arenaCenter.let { Vec3d(it.x, f.floorY, it.z) }
                f.place(a, center.add(0.0, 0.0, -9.0))
                val p = player(srv)
                p.teleport(f.world, center.x + 1.5, f.floorY, center.z + 7.5, emptySet(), 174f, 1f, false)
                f.calls.reset()
                f.atmos(dev.henny.hha_arius.net.AtmosMode.BLIZZARD, 4)
                // Solvane aus dem Bild nehmen: Ihr Leib stand sonst direkt vor der Spielerkamera und verdeckte den Schnitt.
                f.nivaris?.let { d -> f.place(d, center.add(-40.0, 0.0, 40.0)) }
                f.startDirector(f.scenes.lastHorizon(a))
            }
            // Wait for charge pose first, then SPIN_CUT (6 ticks before horizonImpact).
            check(waitUntil(40, 1) { server { fight()?.arius?.pose in setOf(AriusPose.FORGE_ICE, AriusPose.CUT_CHARGE) } })
            check(waitUntil(90, 1) { server { fight()?.arius?.pose == AriusPose.SPIN_CUT } })
            tick(6)
            log("horizon_storm release: " + server {
                val f = fight()
                val a = f?.arius
                "now=${f?.now} pose=${a?.pose} aPos=${a?.entityPos} center=${f?.arenaCenter} floorY=${f?.floorY}"
            })
            shot("horizon_storm_peak")
            shot("horizon_storm_p1")
            shot("horizon_storm_p2")
        } finally {
            client { it.options.perspective = net.minecraft.client.option.Perspective.FIRST_PERSON }
        }
    }

    /** Delayed Yang repair: mountain stays split after theCut & in trainingVault, Arius restores it just before Hunt Crossing 1, and SplitRecovery stays crash-safe. */
    private fun VerifyKit.yangLater() {
        // 1) Verify training stage 3 (Vault -> Hunt) starts with the mountain already split & SplitRecovery snapshot active.
        check(startStage(3)) { "stage 3 did not reach far ready" }
        val (splitAtStage3, snapAtStage3) = server { srv ->
            val f = checkNotNull(fight())
            f.scenes.isSplitActive to dev.henny.hha_arius.boss.SplitRecovery.hasSnapshot(srv, f.slot)
        }
        log("yangLater: stage3 splitActive=$splitAtStage3 snapshot=$snapAtStage3")
        check(splitAtStage3 && snapAtStage3) { "Expected mountain split & snapshot active in stage 3 ($splitAtStage3, $snapAtStage3)" }

        // Sample the split zone across flank + Crossing 1 + summit before starting Hunt.
        val splitAirBeforeHunt = server {
            val f = checkNotNull(fight())
            val w = f.world
            val cutX = kotlin.math.floor(dev.henny.hha_arius.arena.ArenaLayout.CUT_X).toInt()
            val sz = dev.henny.hha_arius.arena.ArenaLayout.SPIRE.z
            var air = 0
            for (z in (sz - 90)..(sz + 20) step 2) {
                for (y in intArrayOf(60, 110, 123, 172)) {
                    if (w.getBlockState(net.minecraft.util.math.BlockPos(f.origin.x + cutX, f.origin.y + y, f.origin.z + z)).isAir) air++
                }
            }
            air
        }
        log("yangLater: splitAirBeforeHunt=$splitAirBeforeHunt")
        check(splitAirBeforeHunt > 40) { "Mountain was not carved in stage 3 ($splitAirBeforeHunt)" }

        // 2) Launch Hunt directly (skipping vault combat) and approach Crossing 1 on the massif spiral.
        server {
            val f = checkNotNull(fight())
            f.calls.reset()
            f.startDirector(dev.henny.hha_arius.boss.Hunt(f).run())
        }
        check(waitUntil(220, 4) { server { dev.henny.hha_arius.boss.Hunt.current != null } }) { "Hunt did not start" }
        val crossArc = server { checkNotNull(dev.henny.hha_arius.boss.Hunt.current).debugCrossingArc() }
        log("yangLater: firstSplitCrossingArc=%.1f".format(crossArc))

        // First look at the open chasm from just outside the trigger range (~26 blocks before Crossing 1)
        server { srv ->
            val f = checkNotNull(fight())
            val h = checkNotNull(dev.henny.hha_arius.boss.Hunt.current)
            h.debugQuiet(400)
            h.debugArius((crossArc - 42.0).coerceAtLeast(0.0))
            val p = player(srv)
            val lookSpot = h.debugPoint(crossArc - 27.0)
            val target = h.debugPoint(crossArc + 2.0)
            val yaw = (Math.toDegrees(atan2(target.z - lookSpot.z, target.x - lookSpot.x)) - 90.0).toFloat()
            p.teleport(f.world, lookSpot.x, lookSpot.y + 0.05, lookSpot.z, emptySet(), yaw, 0f, false)
            f.atmos(dev.henny.hha_arius.net.AtmosMode.REVEAL, 4)
        }
        settle(160)
        tick(10)
        shot("yang_01_still_split_in_hunt")
        val stillSplit = server { srv ->
            val f = checkNotNull(fight())
            f.scenes.isSplitActive && dev.henny.hha_arius.boss.SplitRecovery.hasSnapshot(srv, f.slot)
        }
        check(stillSplit) { "Mountain should still be split before Crossing 1!" }

        // Step inside the trigger zone (crossArc - 19.0) -> Arius triggers the golden Yang repair beat!
        server { srv ->
            val f = checkNotNull(fight())
            val h = checkNotNull(dev.henny.hha_arius.boss.Hunt.current)
            val p = player(srv)
            val spot = h.debugPoint(crossArc - 19.0)
            p.teleport(f.world, spot.x, spot.y + 0.1, spot.z, emptySet(), 90f, 0f, false)
            p.isOnGround = true
        }
        check(waitUntil(60, 1) { server { dev.henny.hha_arius.boss.Hunt.current?.debugInScene() == true } }) {
            "Arius Yang repair scene did not trigger before Crossing 1"
        }
        tick(8)
        shot("yang_02_arius_invokes")
        tick(24)
        shot("yang_03_wave_restoring")
        tick(28)
        shot("yang_04_restored")
        check(waitUntil(120, 2) { server { dev.henny.hha_arius.boss.Hunt.current?.debugInScene() == false } }) {
            "Yang repair scene did not finish"
        }

        val (splitAfterYang, snapAfterYang, crossingSolid) = server { srv ->
            val f = checkNotNull(fight())
            val h = checkNotNull(dev.henny.hha_arius.boss.Hunt.current)
            val mid = h.debugPoint(crossArc + 4.0)
            val below = net.minecraft.util.math.BlockPos.ofFloored(mid.x, mid.y - 0.5, mid.z)
            val solid = !f.world.getBlockState(below).getCollisionShape(f.world, below).isEmpty
            Triple(f.scenes.isSplitActive, dev.henny.hha_arius.boss.SplitRecovery.hasSnapshot(srv, f.slot), solid)
        }
        log("yangLater: afterYang splitActive=$splitAfterYang snapshot=$snapAfterYang crossingSolid=$crossingSolid")
        check(!splitAfterYang && !snapAfterYang && crossingSolid) {
            "Yang repair did not cleanly restore crossing ($splitAfterYang, $snapAfterYang, $crossingSolid)"
        }

        // 3) Crash safety check: save snapshot, carve 250 blocks, call SplitRecovery.recoverAll -> 0 remaining diff.
        val recovered = server { srv ->
            val f = checkNotNull(fight())
            val w = f.world
            val cutX = kotlin.math.floor(dev.henny.hha_arius.arena.ArenaLayout.CUT_X).toInt()
            val sz = dev.henny.hha_arius.arena.ArenaLayout.SPIRE.z
            val victim = ArrayList<Pair<net.minecraft.util.math.BlockPos, net.minecraft.block.BlockState>>()
            for (z in (sz - 60)..(sz + 20)) for (y in 90..175) {
                if (victim.size >= 250) break
                val p = net.minecraft.util.math.BlockPos(f.origin.x + cutX, f.origin.y + y, f.origin.z + z)
                val st = w.getBlockState(p)
                if (!st.isAir) victim.add(p to st)
            }
            dev.henny.hha_arius.boss.SplitRecovery.saveSnapshot(w, f.slot, sz - 60, sz + 20, victim)
            for ((p, _) in victim) w.setBlockState(p, Blocks.AIR.defaultState, dev.henny.hha_arius.boss.ArenaEdits.FLAGS)
            val n = dev.henny.hha_arius.boss.SplitRecovery.recoverAll(srv)
            val left = victim.count { (p, orig) -> w.getBlockState(p) != orig }
            n to left
        }
        log("yangLater: crash-recovery restored=${recovered.first} remainingDiff=${recovered.second}")
        check(recovered.first >= 250 && recovered.second == 0) { "SplitRecovery failed: $recovered" }
    }

    private fun VerifyKit.vaultPace() {
        vault()
        var moves = 0
        var active = 0
        var previous = -1
        for (t in 0 until 260) {
            val a = server { fight()?.arius?.let { it.pose to it.entityPos } } ?: break
            if (a.first != previous && a.first != AriusPose.IDLE) moves++
            if (a.first != AriusPose.IDLE) active++
            previous = a.first
            if (t % 25 == 0) {
                lookAtArius()
                shot("vault_activity_%03d".format(t))
            } else tick(1)
        }
        log("vault: $moves non-idle pose changes, $active/260 ticks in non-idle poses")
    }

    private fun VerifyKit.fallRecovery() {
        finaleFight()
        server { srv ->
            val f = checkNotNull(fight())
            f.startDirector(script { while (true) yield(Unit) })
            f.calls.reset()
            f.onSummit = true
            val center = f.arenaCenter
            val p = player(srv)
            p.teleport(f.world, center.x, f.floorY - 14.0, center.z, emptySet(), 0f, 0f, false)
        }
        check(waitUntil(60, 1) {
            server { srv ->
                val f = checkNotNull(fight())
                val p = player(srv)
                p.y >= f.floorY - 1.0 && p.entityPos.distanceTo(f.arenaCenter) <= f.arenaRadius
            }
        })
        log("summit fall: fighter was returned to the summit arena")
    }

    private fun VerifyKit.climax() {
        finaleFight()
        addBots(3)
        godMode = false
        try {
            server {
                val f = checkNotNull(fight())
                f.moves.summit.debugJumpToLastStand()
            }
            check(waitUntil(1200, 1) { server { fight()?.moves?.summit?.stage == 2 } })
            server { srv ->
                val f = checkNotNull(fight())
                val a = checkNotNull(f.arius)
                val c = f.arenaCenter
                val delta = Vec3d(c.x - a.x, 0.0, c.z - a.z)
                val axis = if (delta.lengthSquared() > 1e-6) delta.normalize() else Vec3d(0.0, 0.0, 1.0)
                val offset = Vec3d(-axis.z, 0.0, axis.x).multiply(7.8)
                val at = Vec3d(c.x, f.floorY, c.z).add(offset).add(axis.multiply(4.5))
                val yaw = (Math.toDegrees(atan2(c.z - at.z, c.x - at.x)) - 90.0).toFloat()
                player(srv).teleport(f.world, at.x, at.y, at.z, emptySet(), yaw, 8f, false)
                for ((i, b) in bots.withIndex()) {
                    val bot = srv.playerManager.getPlayer(b.id) ?: continue
                    val spot = f.clampToArena(at.add(offset.multiply(1.0 + i * 0.4)), 1.0)
                    bot.teleport(f.world, spot.x, spot.y, spot.z, emptySet(), yaw, 0f, false)
                }
            }
            burst("climax_horizon", 12, 6)
            faceArius(2.8)
            val answerFrom = Summit::class.java.getDeclaredField("answerFrom").apply { isAccessible = true }
            val answerUntil = Summit::class.java.getDeclaredField("answerUntil").apply { isAccessible = true }
            fun ready(): Boolean = server {
                val f = checkNotNull(fight())
                val summit = f.moves.summit
                f.now in answerFrom.getLong(summit)..answerUntil.getLong(summit)
            }
            check(waitUntil(170, 1) { ready() })
            faceArius(2.4)
            val health = server { player(it).health }
            shot("climax_retry_window")
            check(waitUntil(60, 1) { server { player(it).health < health } })
            log("climax: missed first window, took damage")
            for ((i, state) in listOf(AriusBlade.ICE_CRACKED, AriusBlade.ICE_BROKEN, AriusBlade.SHEATH_ONLY).withIndex()) {
                check(waitUntil(170, 1) { ready() })
                lookAtArius()
                shot("climax_answer_${i + 1}")
                server { srv ->
                    val f = checkNotNull(fight())
                    val me = player(srv)
                    val p = if (i == 1) checkNotNull(srv.playerManager.getPlayer(bots[0].id)) else me
                    val previous = p.entityPos
                    if (p !== me) {
                        val at = me.entityPos
                        p.teleport(f.world, at.x, at.y, at.z, emptySet(), me.yaw, me.pitch, false)
                    }
                    fullCharge(p)
                    p.attack(f.arius!!)
                    if (p !== me) p.teleport(f.world, previous.x, previous.y, previous.z, emptySet(), p.yaw, p.pitch, false)
                }
                check(waitUntil(22, 1) { server { fight()?.arius?.bladeState == state } })
                tick(5)
                lookAtArius()
                shot("climax_blade_${i + 1}")
            }
            check(waitUntil(32, 1) { server { fight()?.moves?.summit?.stage == 3 } })
            tick(18)
            lookAtArius()
            shot("climax_kneel")
            server { srv ->
                val f = checkNotNull(fight())
                val p = player(srv)
                fullCharge(p)
                p.attack(f.arius!!)
                check(f.ariusHp <= 0f)
            }
            log("climax: all three answers accepted, blade shattered, finishing blow won")
        } finally {
            godMode = true
            server { releaseBots(it) }
        }
    }

    private fun releaseBots(srv: net.minecraft.server.MinecraftServer) {
        for (b in bots) srv.playerManager.getPlayer(b.id)?.networkHandler?.onDisconnected(
            net.minecraft.network.DisconnectionInfo(net.minecraft.text.Text.literal("verify end")))
        bots.clear()
    }

    // --- Hilfen -----------------------------------------------------------------------------------------------------

    private val attackTicks by lazy { net.minecraft.entity.LivingEntity::class.java.getDeclaredField("ticksSinceLastAttack").apply { isAccessible = true } }

    /** Volle Schlagaufladung (der Treffer wird direkt ausgelöst, ohne die Abklingzeit abzuwarten). */
    private fun fullCharge(p: ServerPlayerEntity) = attackTicks.setInt(p, 1000)

    private fun VerifyKit.temple() {
        startStage(1)
        waitUntil(1200, 5) { phase() >= EirholmFight.Phase.TEMPLE }
        tick(30)
    }

    /** Stellt den Spieler [dist] Blöcke vor Arius und richtet den Blick auf ihn. */
    private fun VerifyKit.faceArius(dist: Double) {
        server { srv ->
            val p = player(srv)
            val f = fight() ?: return@server
            val a = f.arius ?: return@server
            val dir = f.facing(a)
            val at = a.entityPos.add(dir.multiply(dist))
            val yaw = (Math.toDegrees(atan2(a.z - at.z, a.x - at.x)) - 90.0).toFloat()
            p.teleport(f.world, at.x, a.y, at.z, emptySet(), yaw, 8f, false)
            p.velocity = Vec3d.ZERO
        }
        tick(3)
    }

    private fun VerifyKit.lookAtArius() {
        val spot = server { fight()?.arius?.entityPos } ?: return
        client { c ->
            val p = c.player ?: return@client
            p.yaw = (Math.toDegrees(atan2(spot.z - p.z, spot.x - p.x)) - 90.0).toFloat()
            p.pitch = 6f
        }
    }

    // --- Konterhaltung ----------------------------------------------------------------------------------------------

    private fun VerifyKit.stance() {
        temple()
        server { fight()?.let { it.calls.reset() } }
        faceArius(5.0)
        server { fight()?.let { f -> f.task(f.moves.guard(false, false)) } }
        var seen = 0
        for (t in 0 until 40) {
            val on = server { fight()?.arius?.counterStance == true }
            if (on) seen++
            lookAtArius()
            if (t == 3 || t == 14 || t == 26) shot("stance_%02d".format(t))
            tick(1)
        }
        log("stance: flag on for $seen ticks (window ${dev.henny.hha_arius.boss.AriusTimings.GUARD_TICKS + dev.henny.hha_arius.boss.AriusTimings.COUNTER_GRACE})")
        shot("stance_after")
        // Treffer in der Haltung löst die Bestrafung aus; in den ersten Ticks (Schonung) noch nicht.
        tick(60)
        faceArius(2.5)
        val graceHit = server { srv ->
            val f = fight() ?: return@server "no fight"
            val a = f.arius ?: return@server "no arius"
            f.task(f.moves.guard(false, false))
            "armed"
        }
        tick(1)
        val inGrace = server { srv ->
            val f = fight() ?: return@server false
            val p = player(srv)
            fullCharge(p)
            p.attack(f.arius!!)
            f.guardTriggered
        }
        log("stance: $graceHit, hit during grace triggered counter=$inGrace (want false)")
        tick(8)
        val inStance = server { srv ->
            val f = fight() ?: return@server false
            val p = player(srv)
            fullCharge(p)
            p.attack(f.arius!!)
            f.guardTriggered
        }
        log("stance: hit inside stance triggered counter=$inStance (want true)")
        tick(10)
        shot("stance_punished")
        tick(170)
    }

    // --- Bestrafung -------------------------------------------------------------------------------------------------

    /** Echte Lebensverluste und Ruhe der Kamera (zweite Differenz der Kameraposition je Tick). */
    private fun VerifyKit.punish() {
        temple()
        godMode = false
        faceArius(2.0)
        server { srv -> player(srv).let { it.health = it.maxHealth } }
        tick(4)
        val start = server { player(it).health }
        log("punish: start health=$start armor=${server { player(it).armor }}")
        server { srv -> fight()?.moves?.counter(player(srv), false) }
        var last = start
        var lost = 0f
        val cam = ArrayList<Vec3d>()
        val look = ArrayList<Float>()
        val raw = ArrayList<Vec3d>()
        val realPos = ArrayList<Vec3d>()
        val realYaw = ArrayList<Float>()
        val realPitch = ArrayList<Float>()
        val pPos = ArrayList<Vec3d>()
        val tickNotes = ArrayList<String>()
        for (t in 0 until 130) {
            val hp = server { player(it).health + player(it).absorptionAmount }
            if (hp < last) lost += last - hp
            if (hp != last) log("punish t=$t health=$hp (${hp - last})")
            last = hp
            client { c ->
                val ca = c.gameRenderer.camera
                val world = c.world
                val ce = c.cameraEntity
                if (world != null && ce != null) {
                    ca.update(world, ce, !c.options.perspective.isFirstPerson, c.options.perspective.isFrontView, 1f)
                }
                realPos.add(ca.cameraPos)
                realYaw.add(ca.yaw)
                realPitch.add(ca.pitch)
                dev.henny.hha_arius.client.fx.PunishCam.debugRig()?.let { (pos, at) ->
                    cam.add(pos)
                    look.add(Math.toDegrees(atan2(-(at.x - pos.x), at.z - pos.z)).toFloat())
                }
                val me = c.player
                val other = c.world?.entities?.firstOrNull { it is dev.henny.hha_arius.boss.AriusEntity }
                if (me != null) {
                    pPos.add(me.entityPos)
                    val kick = dev.henny.hha_arius.client.fx.CameraKick.offset(1f)
                    val kickStr = if (kick != null) "kick=(%.2f,%.2f)".format(kick[0], kick[1]) else ""
                    tickNotes.add("t=%03d pos=(%.2f,%.2f,%.2f) y=%.2f p=%.2f pY=%.2f pVy=%.2f hurt=%d held=%b rig=%b %s".format(
                        t, ca.cameraPos.x, ca.cameraPos.y, ca.cameraPos.z, ca.yaw, ca.pitch,
                        me.y, me.velocity.y, me.hurtTime,
                        dev.henny.hha_arius.client.fx.HoldClient.isHeld(),
                        dev.henny.hha_arius.client.fx.PunishCam.hidesHands(), kickStr,
                    ))
                }
                if (me != null && other != null) raw.add(me.entityPos.lerp(other.entityPos, 0.5))
            }
            if (t in 16..100 && t % 14 == 2) shot("punish_%03d".format(t))
            tick(1)
        }
        godMode = true
        log("punish: total lost=$lost (hearts ${lost / 2})")
        fun stats(from: Int, to: Int, label: String) {
            val hi = minOf(to, realPos.size - 1)
            val lo = maxOf(from, 1)
            if (hi <= lo) return
            var maxDp = 0.0; var sumDp = 0.0; var maxDpT = -1
            var maxDy = 0f; var sumDy = 0.0; var maxDyT = -1
            var maxDpi = 0f; var sumDpi = 0.0; var maxDpiT = -1
            var maxJ = 0.0; var maxJy = 0f; var maxJpi = 0f
            val n = hi - lo + 1
            for (i in lo..hi) {
                val dp = realPos[i].distanceTo(realPos[i - 1])
                val dy = abs(wrap(realYaw[i] - realYaw[i - 1]))
                val dpi = abs(realPitch[i] - realPitch[i - 1])
                sumDp += dp; sumDy += dy; sumDpi += dpi
                if (dp > maxDp) { maxDp = dp; maxDpT = i }
                if (dy > maxDy) { maxDy = dy; maxDyT = i }
                if (dpi > maxDpi) { maxDpi = dpi; maxDpiT = i }
                if (i >= lo + 1) {
                    val j = realPos[i].subtract(realPos[i - 1]).subtract(realPos[i - 1].subtract(realPos[i - 2])).length()
                    val jy = abs(wrap(realYaw[i] - realYaw[i - 1]) - wrap(realYaw[i - 1] - realYaw[i - 2]))
                    val jpi = abs((realPitch[i] - realPitch[i - 1]) - (realPitch[i - 1] - realPitch[i - 2]))
                    if (j > maxJ) maxJ = j
                    if (jy > maxJy) maxJy = jy
                    if (jpi > maxJpi) maxJpi = jpi
                }
            }
            log("punish [$label t=$lo..$hi]: posDelta max=%.4f(t=%d) mean=%.4f jerk=%.4f | yawDelta max=%.3f(t=%d) mean=%.3f jerk=%.3f | pitchDelta max=%.3f(t=%d) mean=%.3f jerk=%.3f".format(
                maxDp, maxDpT, sumDp / n, maxJ, maxDy, maxDyT, sumDy / n, maxJy, maxDpi, maxDpiT, sumDpi / n, maxJpi,
            ))
        }
        stats(1, 129, "full")
        stats(8, 100, "combo")
        var maxPyDelta = 0.0; var maxPyT = -1
        for (i in 1 until pPos.size) {
            val d = abs(pPos[i].y - pPos[i - 1].y)
            if (d > maxPyDelta) { maxPyDelta = d; maxPyT = i }
        }
        log("punish: player Y maxDelta=%.4f(t=%d)".format(maxPyDelta, maxPyT))
        // Log ticks with largest deltas for root-cause analysis
        for (i in 1 until minOf(realPos.size, tickNotes.size)) {
            val dp = realPos[i].distanceTo(realPos[i - 1])
            val dy = abs(wrap(realYaw[i] - realYaw[i - 1]))
            val dpi = abs(realPitch[i] - realPitch[i - 1])
            if (dp > 0.15 || dy > 1.0f || dpi > 0.8f || i in listOf(0, 1, 2, 5, 10, 50, 60, 70, 80, 90, 100, 105, 110, 115)) {
                log("  ${tickNotes[i]} | dPos=%.3f dYaw=%.2f dPitch=%.2f".format(dp, dy, dpi))
            }
        }
        var maxAccel = 0.0
        var maxYaw = 0f
        var rawJerk = 0.0
        for (i in 14 until raw.size - 24) {
            val a = raw[i + 1].subtract(raw[i]).subtract(raw[i].subtract(raw[i - 1])).length()
            if (a > rawJerk) rawJerk = a
        }
        log("punish: raw follow point (what the old camera tracked) max jerk %.3f".format(rawJerk))
        for (i in 14 until cam.size - 2) {
            val a = cam[i + 1].subtract(cam[i]).subtract(cam[i].subtract(cam[i - 1])).length()
            if (a > maxAccel) maxAccel = a
            val dy = abs(wrap(look[i + 1] - look[i]) - wrap(look[i] - look[i - 1]))
            if (dy > maxYaw) maxYaw = dy
        }
        log("punish: camera max jerk %.3f blocks/tick², max yaw jerk %.2f deg/tick²".format(maxAccel, maxYaw))
        tick(60)
    }

    private fun wrap(d: Float): Float {
        var x = d % 360f
        if (x > 180f) x -= 360f
        if (x < -180f) x += 360f
        return x
    }

    // --- Parade -----------------------------------------------------------------------------------------------------

    /**
     * Echtes Parade-Fenster der Solvane: Rechtsklick über die echte Eingabe, [d] Ticks vor einem Boss-Treffer, einmal als
     * kurzes Tippen und einmal gehalten. Ausgabe: bei welchem Abstand der Treffer abgefangen wurde.
     */
    private fun VerifyKit.parry() {
        cmd("hha set hha_arius.solvane_counter_cooldown_success 0")
        cmd("hha set hha_arius.solvane_counter_cooldown_fail 0")
        cmd("effect clear @p")
        tick(10)
        val useKey = client { it.options.useKey }
        for (hold in listOf(false, true)) {
            val row = StringBuilder()
            for (d in 0..20) {
                ctx.getInput().holdKey(useKey)
                if (d == 0) {
                    tick(1)
                } else {
                    tick(1)
                    if (!hold) ctx.getInput().releaseKey(useKey)
                    tick(d - 1)
                }
                val blocked = server { srv ->
                    val p = player(srv)
                    p.health = p.maxHealth
                    Damage.hit(p, null, Damage.Kind.SOLVANE, 1f, 1f) <= 0f
                }
                ctx.getInput().releaseKey(useKey)
                row.append(if (blocked) "$d:Y " else "$d:n ")
                tick(24)
            }
            log("parry ${if (hold) "hold" else "tap "}: $row")
        }
        // Zu spät geklickt (Treffer zuerst, Klick im nächsten Tick): gilt als Parade, Schaden kommt zurück.
        val late = StringBuilder()
        godMode = false
        for (after in 0..4) {
            server { srv ->
                val p = player(srv)
                p.health = p.maxHealth
                Damage.hit(p, null, Damage.Kind.SOLVANE, 4f, 1f)
            }
            val hurt = server { player(it).health }
            tick(after)
            ctx.getInput().holdKey(useKey)
            tick(2)
            ctx.getInput().releaseKey(useKey)
            val back = server { player(it).health }
            late.append("+$after:%.1f->%.1f ".format(hurt, back))
            tick(24)
        }
        godMode = true
        log("parry late click (health after hit -> after click): $late")
        cmd("hha set hha_arius.solvane_counter_cooldown_success 40")
        cmd("hha set hha_arius.solvane_counter_cooldown_fail 60")
        // Die Anzeige im Kampf.
        temple()
        faceArius(3.0)
        for (t in 0 until 200) {
            lookAtArius()
            if (t % 6 == 0) shot("parry_hud_%03d".format(t))
            tick(1)
        }
    }

    /**
     * Stimmt die Anzeige auf den Tick? Echte Ansage, echter Treffer im Kampf-Tick, echter Klick genau am Rand des Fensters,
     * das die Anzeige zeigt: einen Tick davor und danach darf es nicht klappen, auf dem Rand muss es klappen.
     */
    private fun VerifyKit.parryExact() {
        cmd("hha set hha_arius.solvane_counter_cooldown_success 0")
        cmd("hha set hha_arius.solvane_counter_cooldown_fail 0")
        temple()
        server { srv ->
            val f = fight() ?: return@server
            f.startDirector(dev.henny.hha_arius.boss.script { while (true) yield(Unit) })
            f.calls.reset()
            f.arius?.let { a -> f.place(a, a.entityPos.add(12.0, 0.0, 0.0)) }
            player(srv).setStackInHand(Hand.MAIN_HAND, ItemStack(dev.henny.hha_arius.gear.GearItems.SOLVANE))
        }
        tick(30)
        val useKey = client { it.options.useKey }
        val out = StringBuilder()
        // g Ticks vor dem Ende des angezeigten Fensters klicken (-1 = einen Tick danach).
        for (g in -1..20) {
            var result = false
            var done = false
            var gap = -1L
            server { srv ->
                val f = fight() ?: return@server
                val p = player(srv)
                p.health = p.maxHealth
                val ring = f.calls.ring(p.entityPos, 4f, 44, pace = dev.henny.hha.logic.boss.Telegraph.Pace.HEAVY)
                f.task(dev.henny.hha_arius.boss.script {
                    var started = -1L
                    repeat(ring.ticks) {
                        if (started < 0 && dev.henny.hha_arius.gear.SolvaneCounter.active(p)) started = dev.henny.hha_arius.gear.GearTasks.now
                        yield(Unit)
                    }
                    if (started >= 0) gap = dev.henny.hha_arius.gear.GearTasks.now - started
                    result = f.hurt(p, 1f) <= 0f
                    done = true
                })
            }
            var pressed = false
            var guard = 0
            var shownOpen = false
            while (!done && guard++ < 80) {
                val edges = client { dev.henny.hha_arius.client.fx.ParryHud.debugEdges() }
                if (!pressed && edges != null && edges.second >= -g) {
                    shownOpen = edges.first >= 0f
                    ctx.getInput().holdKey(useKey)
                    pressed = true
                    tick(1)
                    ctx.getInput().releaseKey(useKey)
                    continue
                }
                tick(1)
            }
            out.append("$g:${if (result) "Y" else "n"}${if (shownOpen) "*" else ""}(gap $gap) ")
            tick(40)
        }
        log("parry exact, ticks before shown close (* = HUD showed open when clicked): $out")
        cmd("hha set hha_arius.solvane_counter_cooldown_success 40")
        cmd("hha set hha_arius.solvane_counter_cooldown_fail 60")
    }

    /** Der Rückfallweg der Bestrafung (kurz nach einem anderen gehaltenen Moment): trifft er wirklich? */
    private fun VerifyKit.classic() {
        temple()
        godMode = false
        faceArius(2.0)
        server { srv ->
            val p = player(srv)
            p.health = p.maxHealth
            dev.henny.hha_arius.boss.Hold.freeze(p, 2)
        }
        tick(5)
        val start = server { player(it).health }
        server { srv -> fight()?.moves?.counter(player(srv), false) }
        var last = start
        var lost = 0f
        for (t in 0 until 110) {
            val hp = server { player(it).health + player(it).absorptionAmount }
            if (hp < last) lost += last - hp
            last = hp
            tick(1)
        }
        godMode = true
        log("classic punish: lost %.2f health".format(lost))
    }

    // --- Streitkolben -------------------------------------------------------------------------------------------------

    private fun VerifyKit.mace() {
        temple()
        fun strike(item: ItemStack, fall: Double): Float = server { srv ->
            val f = fight() ?: return@server 0f
            val p = player(srv)
            val a = f.arius ?: return@server 0f
            f.guardUntil = 0
            f.ariusOpenUntil = 0
            p.teleport(f.world, a.x + 1.5, a.y, a.z, emptySet(), 90f, 0f, false)
            p.setStackInHand(Hand.MAIN_HAND, item.copy())
            p.fallDistance = fall
            p.isOnGround = fall <= 0.0
            fullCharge(p)
            val before = f.ariusHp
            p.attack(a)
            p.fallDistance = 0.0
            before - f.ariusHp
        }
        val sword = ItemStack(Items.NETHERITE_SWORD)
        val mace = ItemStack(Items.MACE)
        log("mace: sword hit (after x0.7 outside a window) = %.2f".format(strike(sword, 0.0)))
        tick(70)
        log("mace: plain mace hit = %.2f".format(strike(mace, 0.0)))
        tick(70)
        for (h in listOf(5.0, 20.0, 60.0)) {
            log("mace: smash from $h blocks = %.2f".format(strike(mace, h)))
            tick(70)
        }
        val burst = (0 until 5).map { val d = strike(mace, 40.0); tick(8); d }
        log("mace: five smashes in 40 ticks = ${burst.joinToString { "%.1f".format(it) }}, total %.1f".format(burst.sum()))
        // Fähigkeiten gegen den Boss.
        tick(70)
        val spell = server { srv ->
            val f = fight() ?: return@server 0f
            val p = player(srv)
            val a = f.arius ?: return@server 0f
            f.guardUntil = 0
            f.ariusOpenUntil = 0
            val before = f.ariusHp
            a.damage(f.world, f.world.damageSources.indirectMagic(p, p), 10f)
            before - f.ariusHp
        }
        val ability = server { srv ->
            val f = fight() ?: return@server 0f
            val p = player(srv)
            val a = f.arius ?: return@server 0f
            val before = f.ariusHp
            PlayerHit.marked(PlayerHit.Kind.ABILITY) { a.damage(f.world, f.world.damageSources.playerAttack(p), 10f) }
            before - f.ariusHp
        }
        log("ability: 10 raw magic = %.2f, 10 raw armour ability = %.2f (window factor 0.7)".format(spell, ability))
        server { srv -> player(srv).setStackInHand(Hand.MAIN_HAND, ItemStack(dev.henny.hha_arius.gear.GearItems.SOLVANE)) }
    }

    /**
     * Prüft den Windstoß-Streitkolben-Schwebefehler über mehrere Attackentypen hinweg:
     * Schlägt Arius mitten in verschiedenen Angriffen mit einem Wind-Burst-III-Mace (Sturzschlag),
     * misst seine maximale Höhe über `floorY` während und nach jedem Angriff und prüft, dass er nie in der Luft
     * hängen bleibt (und nach beabsichtigtem `skyLeap` sauber auf Bodenhöhe landet).
     */
    private fun VerifyKit.maceFloat() {
        temple()
        val attacks: List<Pair<String, (EirholmFight, ServerPlayerEntity) -> Script>> = listOf(
            "combo" to { f, _ -> f.moves.combo(white = false, serious = false) },
            "lunge" to { f, p -> f.moves.lunge(p, white = false) },
            "heavenCleave" to { f, _ -> f.moves.heavenCleave(white = false, serious = false) },
            "guard" to { f, _ -> f.moves.guard(white = false, serious = false) },
            "frostLotus" to { f, _ -> f.moves.frostLotus(white = false) },
            "stabGround" to { f, _ -> f.moves.stabGround() },
            "nullCut" to { f, p -> f.moves.nullCut(p) },
            "skyLeap" to { f, _ -> f.moves.skyLeap(white = false) },
        )
        val summary = ArrayList<String>()
        try {
            for ((name, factory) in attacks) {
                var finished = false
                server { srv ->
                    val f = checkNotNull(fight())
                    val a = checkNotNull(f.arius)
                    val p = player(srv)
                    val c = f.arenaCenter
                    f.calls.reset()
                    f.guardUntil = 0
                    f.ariusOpenUntil = 0
                    f.ariusVulnerable = true
                    f.ariusHp = f.ariusMax
                    f.place(a, Vec3d(c.x, f.floorY, c.z))
                    a.play(AriusPose.IDLE)
                    p.teleport(f.world, c.x + 2.4, f.floorY, c.z, emptySet(), 90f, 0f, false)
                    p.onLanding()
                    val inner = factory(f, p)
                    f.startDirector(script {
                        yieldAll(inner)
                        finished = true
                        while (true) yield(Unit)
                    })
                }
                // Kurz anlaufen lassen, damit Arius mitten in der Attacke steckt.
                tick(6)
                var maxHeight = 0.0
                var playerBounced = false
                var hitCount = 0
                // Zweimal während derselben Attacke mit Wind Burst III Mace treffen (simuliert Bounce-Ketten).
                for (t in 0 until 75) {
                    if (t == 0 || t == 14) {
                        val hitBounce = server { srv ->
                            val f = checkNotNull(fight())
                            val a = checkNotNull(f.arius)
                            val p = player(srv)
                            val windBurst = f.world.registryManager
                                .getOrThrow(net.minecraft.registry.RegistryKeys.ENCHANTMENT)
                                .getOrThrow(net.minecraft.enchantment.Enchantments.WIND_BURST)
                            val mace = ItemStack(Items.MACE).apply { addEnchantment(windBurst, 3) }
                            f.guardUntil = 0
                            f.ariusHp = f.ariusMax
                            p.setStackInHand(Hand.MAIN_HAND, mace)
                            p.teleport(f.world, a.x + 1.4, a.y + 1.6, a.z, emptySet(), 90f, 25f, false)
                            p.fallDistance = 6.0
                            p.isOnGround = false
                            p.velocity = Vec3d.ZERO
                            fullCharge(p)
                            p.attack(a)
                            p.velocity.y
                        }
                        hitCount++
                        if (hitBounce > 0.3) playerBounced = true
                    }
                    tick(1)
                    val h = server {
                        val f = checkNotNull(fight())
                        val a = checkNotNull(f.arius)
                        a.y - f.floorY
                    }
                    if (h > maxHeight) maxHeight = h
                    if (finished && t >= 22) break
                }
                // Attacke vollständig auslaufen lassen und Endhöhe messen.
                waitUntil(120, 1) { finished }
                tick(6)
                val endHeight = server {
                    val f = checkNotNull(fight())
                    val a = checkNotNull(f.arius)
                    a.y - f.floorY
                }
                val line = "$name: hits=$hitCount maxH=%.3f endH=%.3f playerWindBounce=$playerBounced".format(maxHeight, endHeight)
                log("macefloat $line")
                summary += line
                if (name != "skyLeap") {
                    check(maxHeight <= 0.10) { "Arius floated during grounded attack '$name': maxH=$maxHeight endH=$endHeight" }
                } else {
                    check(maxHeight >= 5.0) { "skyLeap should still jump intentionally: maxH=$maxHeight" }
                }
                check(abs(endHeight) <= 0.05) { "Arius did not end cleanly on ground after '$name': endH=$endHeight" }
            }
            // Zusätzlich prüfen: Eine echte Explosion verursacht weiter normalen Schaden, bewegt Arius aber um 0 Blöcke.
            val (hpBefore, posBefore) = server { srv ->
                val f = checkNotNull(fight())
                val a = checkNotNull(f.arius)
                val p = player(srv)
                val c = f.arenaCenter
                f.calls.reset()
                f.guardUntil = 0
                f.ariusOpenUntil = 0
                f.ariusVulnerable = true
                f.ariusHp = f.ariusMax
                f.startDirector(script { while (true) yield(Unit) })
                val base = Vec3d(c.x, f.floorY, c.z)
                f.place(a, base)
                a.play(AriusPose.IDLE)
                p.teleport(f.world, c.x + 5.0, f.floorY, c.z, emptySet(), 90f, 0f, false)
                f.ariusHp to a.entityPos
            }
            tick(2)
            server { srv ->
                val f = checkNotNull(fight())
                val a = checkNotNull(f.arius)
                val p = player(srv)
                f.world.createExplosion(p, a.x + 1.5, a.y + 0.5, a.z, 3.0f, net.minecraft.world.World.ExplosionSourceType.NONE)
            }
            var expMaxDrift = 0.0
            var expMaxH = 0.0
            for (t in 0 until 6) {
                tick(1)
                val (drift, h) = server {
                    val f = checkNotNull(fight())
                    val a = checkNotNull(f.arius)
                    a.entityPos.distanceTo(posBefore) to (a.y - f.floorY)
                }
                if (drift > expMaxDrift) expMaxDrift = drift
                if (abs(h) > abs(expMaxH)) expMaxH = h
            }
            val expDamage = server { hpBefore - checkNotNull(fight()).ariusHp }
            val expLine = "explosion: dmg=%.3f maxDrift=%.4f maxH=%.4f".format(expDamage, expMaxDrift, expMaxH)
            log("macefloat $expLine")
            check(expDamage > 0.5f) { "Explosion dealt no damage to Arius: dmg=$expDamage" }
            check(expMaxDrift <= 0.01 && abs(expMaxH) <= 0.01) { "Explosion moved Arius: maxDrift=$expMaxDrift maxH=$expMaxH" }
            log("macefloat PASS (${summary.size} attacks + explosion): ${summary.joinToString(" | ")} | $expLine")
        } finally {
            server { srv -> player(srv).setStackInHand(Hand.MAIN_HAND, ItemStack(dev.henny.hha_arius.gear.GearItems.SOLVANE)) }
        }
    }

    // --- Weißes Loch --------------------------------------------------------------------------------------------------

    private fun VerifyKit.vault() {
        startStage(3)
        waitUntil(2400, 5) { phase() >= EirholmFight.Phase.VAULT }
        waitUntil(400, 2) { server { fight()?.ariusVulnerable == true } }
        tick(20)
    }

    private fun splinters(f: EirholmFight): List<Vec3d> {
        val c = f.arenaCenter
        return f.world.getEntitiesByClass(DisplayEntity.BlockDisplayEntity::class.java, Box.of(c, 90.0, 40.0, 90.0)) {
            it.blockState.isOf(Blocks.SEA_LANTERN)
        }.map { it.entityPos }
    }

    private fun VerifyKit.hole(feed: Boolean) {
        vault()
        val tag = if (feed) "hole" else "holefail"
        server { srv ->
            val f = fight() ?: return@server
            val p = player(srv)
            f.ariusHp = f.ariusMax * 0.49f
            val c = f.arenaCenter
            p.teleport(f.world, c.x + 9.0, f.floorY, c.z, emptySet(), 90f, -12f, false)
        }
        val hpStart = server { fight()?.let { it.ariusHp / it.ariusMax } ?: 0f }
        val dragonStart = server { fight()?.let { it.dragonHp / it.dragonMax } ?: 0f }
        var twistAt = -1
        var endAt = -1
        for (t in 0 until 520) {
            val list = server { fight()?.let { splinters(it) } ?: emptyList() }
            if (list.isNotEmpty() && twistAt < 0) {
                twistAt = t
                log("$tag: reversal at t=$t, ${list.size} shards")
            }
            if (twistAt >= 0 && list.isEmpty() && endAt < 0) {
                endAt = t
                log("$tag: shards gone at t=$t")
            }
            if (feed && list.isNotEmpty()) {
                val at = list.first()
                server { srv ->
                    val f = fight() ?: return@server
                    val p = player(srv)
                    p.teleport(f.world, at.x, f.floorY, at.z, emptySet(), p.yaw, p.pitch, false)
                }
            }
            lookCore()
            val shoot = t in listOf(30, 60) || (twistAt >= 0 && t - twistAt in listOf(2, 10, 24, 50)) || (endAt >= 0 && t - endAt in listOf(4, 12, 24, 40))
            if (shoot) shot("${tag}_%03d".format(t))
            tick(1)
        }
        val hpEnd = server { fight()?.let { it.ariusHp / it.ariusMax } ?: 0f }
        val dragonEnd = server { fight()?.let { it.dragonHp / it.dragonMax } ?: 0f }
        log("$tag: Arius %.3f -> %.3f, Solvane %.3f -> %.3f".format(hpStart, hpEnd, dragonStart, dragonEnd))
    }

    private fun VerifyKit.lookCore() {
        val c = server { fight()?.let { it.arenaCenter.add(0.0, 6.0, 0.0).let { v -> Vec3d(v.x, it.floorY + 6.0, v.z) } } } ?: return
        client { m ->
            val p = m.player ?: return@client
            val dx = c.x - p.x
            val dz = c.z - p.z
            p.yaw = (Math.toDegrees(atan2(dz, dx)) - 90.0).toFloat()
            p.pitch = (-Math.toDegrees(atan2(c.y - p.eyeY, sqrt(dx * dx + dz * dz)))).toFloat()
        }
    }

    // --- Duo ----------------------------------------------------------------------------------------------------------

    /** Die Kette „Einklang“: misst den Flug (Kurvenradius) und wann Arius auf ihr sitzt, und macht Bilder. */
    private fun VerifyKit.duo() {
        vault()
        server { dev.henny.hha_arius.boss.RailRun.debugReset() }
        var prev: Vec3d? = null
        var prevDir: Vec3d? = null
        var minRadius = Double.MAX_VALUE
        var ridingTicks = 0
        var flights = 0
        for (t in 0 until 900) {
            val sample = server {
                val f = fight() ?: return@server null
                val d = f.nivaris ?: return@server null
                val a = f.arius ?: return@server null
                Triple(d.entityPos, a.entityPos, d.flying)
            }
            if (sample != null) {
                val (dp, ap, flying) = sample
                val last = prev
                if (last != null && flying) {
                    val step = dp.subtract(last)
                    val len = step.length()
                    if (len in 0.2..4.0) {
                        val dir = step.multiply(1.0 / len)
                        prevDir?.let { pd ->
                            val ang = acos(pd.dotProduct(dir).coerceIn(-1.0, 1.0))
                            if (ang > 1e-3) minRadius = minOf(minRadius, len / ang)
                            if (ang > 1e-3 && len / ang < 12.0) log("duo kink t=$t radius=%.1f deg=%.0f len=%.2f y=%.1f".format(len / ang, Math.toDegrees(ang), len, dp.y))
                        }
                        prevDir = dir
                    } else {
                        prevDir = null
                    }
                } else if (!flying) {
                    // Am Boden zählt keine Flugrichtung, sonst misst der Start den Winkel zur letzten Landung.
                    prevDir = null
                }
                prev = dp
                if (dp.squaredDistanceTo(ap) < 16.0 && ap.y > (server { fight()?.floorY } ?: 0.0) + 2.0) ridingTicks++
                if (flying) flights++
            }
            if (t in 240..760 && t % 16 == 0) {
                val look = server { fight()?.nivaris?.entityPos } ?: Vec3d.ZERO
                client { m ->
                    val p = m.player ?: return@client
                    val dx = look.x - p.x
                    val dz = look.z - p.z
                    p.yaw = (Math.toDegrees(atan2(dz, dx)) - 90.0).toFloat()
                    p.pitch = (-Math.toDegrees(atan2(look.y - p.eyeY, sqrt(dx * dx + dz * dz)))).toFloat().coerceIn(-50f, 40f)
                }
                shot("duo_%03d".format(t))
            }
            tick(1)
        }
        log("duo: tightest rail turn %.1f blocks (all flight incl. landing %.1f) over $flights flying ticks, Arius riding for $ridingTicks ticks".format(server { dev.henny.hha_arius.boss.RailRun.debugTightest }, minRadius))
    }

    // --- Wellen und Zusammenprall -------------------------------------------------------------------------------------

    private fun VerifyKit.finaleFight() {
        startStage(4)
        waitUntil(2400, 5) { phase() >= EirholmFight.Phase.FINALE }
        waitUntil(400, 5) { server { fight()?.ariusVulnerable == true } }
        tick(10)
    }

    /** Spielt die vier Wellen über ihre Lebensmarken und schreibt, was wann läuft. */
    private fun VerifyKit.waves() {
        finaleFight()
        server { fight()?.let { it.ariusHp = it.ariusMax * 0.74f } }
        waitUntil(400, 5) { server { fight()?.moves?.summit?.stage ?: 0 } >= 1 }
        log("waves: stage=${server { fight()?.moves?.summit?.stage }}")
        for ((i, mark) in Summit.WAVES.withIndex()) {
            server { fight()?.let { it.ariusHp = it.ariusMax * mark } }
            val startHp = server { fight()?.ariusHp ?: 0f }
            var shots = 0
            for (t in 0 until (if (i == 3) 640 else 300)) {
                lookAtArius()
                if (t % 44 == 20 && shots < 6) shot("wave${i}_%02d".format(shots++))
                tick(1)
            }
            log("wave $i at ${mark}: hp ${"%.2f".format(startHp)} -> ${"%.2f".format(server { fight()?.ariusHp ?: 0f })}, alive=${server { fight() != null }}")
        }
    }

    private class Bot(val id: UUID, val conn: ClientConnection, val channel: EmbeddedChannel)

    private val bots = ArrayList<Bot>()

    /** Echte zusätzliche Spieler auf dem Server (ohne Client), damit die Mehrspieler-Pfade wirklich laufen. */
    private fun VerifyKit.addBots(n: Int) {
        server { srv ->
            val f = fight() ?: return@server
            repeat(n) { i ->
                val id = UUID.nameUUIDFromBytes("arius-bot-$i".toByteArray())
                val profile = GameProfile(id, "Bot$i")
                val conn = ClientConnection(NetworkSide.SERVERBOUND)
                val channel = EmbeddedChannel(conn)
                val p = ServerPlayerEntity(srv, srv.overworld, profile, SyncedClientOptions.createDefault())
                srv.playerManager.onPlayerConnect(conn, p, ConnectedClientData.createDefault(profile, false))
                f.fighters[id] = EirholmFight.Fighter(id)
                bots.add(Bot(id, conn, channel))
            }
            val me = player(srv)
            for ((i, b) in bots.withIndex()) {
                val p = srv.playerManager.getPlayer(b.id) ?: continue
                p.teleport(f.world, me.x + 2.0 + i * 2.0, me.y, me.z + 2.0, emptySet(), 0f, 0f, false)
            }
        }
        keepBots(4)
        server { srv ->
            val f = fight() ?: return@server
            val me = player(srv)
            for ((i, b) in bots.withIndex()) {
                f.fighters[b.id]?.ko = false
                val p = srv.playerManager.getPlayer(b.id) ?: continue
                p.teleport(f.world, me.x + 2.0 + i * 2.0, me.y, me.z + 2.0, emptySet(), 0f, 0f, false)
            }
        }
        keepBots(4)
    }

    private val waitField by lazy { ServerCommonNetworkHandler::class.java.getDeclaredField("waitingForKeepAlive").apply { isAccessible = true } }
    private val aliveField by lazy { ServerCommonNetworkHandler::class.java.getDeclaredField("lastKeepAliveTime").apply { isAccessible = true } }

    private fun VerifyKit.keepBots(n: Int, each: (MinecraftServer) -> Unit = {}) {
        repeat(n) {
            server { srv ->
                for (b in bots) {
                    val p = srv.playerManager.getPlayer(b.id) ?: continue
                    waitField.setBoolean(p.networkHandler, false)
                    aliveField.setLong(p.networkHandler, net.minecraft.util.Util.getMeasuringTimeMs())
                    b.channel.releaseOutbound()
                    if (p.isAlive) p.health = p.maxHealth
                }
                each(srv)
            }
            tick(1)
        }
    }

    /** Alle Markerformen im echten Sturm auf dem Gipfel, aus Spielerhöhe: früh, mitten im Laden, kurz vor dem Treffer, Treffer. */
    private fun VerifyKit.teleGallery() {
        finaleFight()
        server { srv ->
            val f = checkNotNull(fight())
            f.startDirector(dev.henny.hha_arius.boss.script { while (true) yield(Unit) })
            f.calls.reset()
            f.atmos(dev.henny.hha_arius.net.AtmosMode.BLIZZARD, 1)
            val c = f.arenaCenter
            f.arius?.let { a -> f.place(a, Vec3d(c.x + 3.0, f.floorY, c.z + 26.0)) }
            player(srv).teleport(f.world, c.x, f.floorY, c.z - 8.0, emptySet(), 0f, 24f, false)
        }
        tick(70)
        for (round in 0 until 2) {
            val ticks = 60
            server { srv ->
                val f = checkNotNull(fight())
                val c = Vec3d(f.arenaCenter.x, f.floorY + 0.05, f.arenaCenter.z - 8.0)
                val pace = dev.henny.hha.logic.boss.Telegraph.Pace.HEAVY
                f.calls.ring(c.add(0.0, 0.0, 11.0), 4.5f, ticks, pace = pace)
                f.calls.ring(c.add(-9.0, 0.0, 7.0), 3f, ticks, pace = pace, style = dev.henny.hha.logic.Vfx.Style.GOLD)
                f.calls.line(c.add(5.0, 0.0, 3.0), c.add(10.0, 0.0, 18.0), 1.4f, ticks, pace)
                f.calls.cone(c.add(-3.0, 0.0, 2.0), Vec3d(-0.3, 0.0, 1.0).normalize(), 7f, 40f, ticks, pace)
            }
            for (t in 0 until ticks) {
                if (t == 10 || t == 34 || t == 56) shot("tele${round}_%02d".format(t))
                tick(1)
            }
            server { srv ->
                val f = checkNotNull(fight())
                val c = Vec3d(f.arenaCenter.x, f.floorY + 0.05, f.arenaCenter.z - 8.0)
                dev.henny.hha.logic.Vfx.shockwave(f.world, dev.henny.hha.logic.Vfx.Style.HEAVEN, c.add(0.0, 0.0, 11.0), 4.5f, 12, 1.2f)
                dev.henny.hha.logic.Vfx.crescent(f.world, dev.henny.hha.logic.Vfx.Style.HEAVEN, c.add(-3.0, 1.1, 2.0), Vec3d(-0.3, 0.0, 1.0).normalize(), 7f, 1f, 10)
                f.fx(dev.henny.hha_arius.net.FxKind.SLASH_ARC, c.add(5.0, 1.1, 3.0), Vec3d(0.3, 0.0, 1.0).normalize(), a = 6f, b = 0.2f, life = 12)
            }
            tick(3)
            shot("tele${round}_hit")
            tick(30)
        }
    }

    /** Jeder Angriffseffekt einzeln auf dem Gipfel, mit Vergleichsbild ohne Effekt (für die Farbprüfung). */
    private fun VerifyKit.fxGallery() {
        finaleFight()
        server { srv ->
            val f = checkNotNull(fight())
            f.startDirector(dev.henny.hha_arius.boss.script { while (true) yield(Unit) })
            f.calls.reset()
            val c = f.arenaCenter
            f.arius?.let { a -> f.place(a, Vec3d(c.x + 3.5, f.floorY, c.z + 9.0)); f.face(a, Vec3d(c.x, f.floorY, c.z - 8.0)) }
            player(srv).teleport(f.world, c.x, f.floorY, c.z - 8.0, emptySet(), 0f, 16f, false)
        }
        tick(60)
        shot("fx_base")
        val V = dev.henny.hha.logic.Vfx
        val H = dev.henny.hha.logic.Vfx.Style.HEAVEN
        val St = dev.henny.hha.logic.Vfx.Style.STORM
        val So = dev.henny.hha.logic.Vfx.Style.SOUL
        val G = dev.henny.hha.logic.Vfx.Style.GOLD
        val K = dev.henny.hha_arius.net.FxKind.entries
        fun kind(n: String) = K.first { it.name == n }
        val fwd = Vec3d(0.0, 0.0, 1.0)
        val list: List<Pair<String, (EirholmFight, Vec3d, dev.henny.hha_arius.boss.AriusEntity) -> Unit>> = listOf(
            "crescent_h" to { f, p, _ -> V.crescent(f.world, H, p.add(0.0, 1.1, 0.0), fwd, 7f, 2.6f, 10) },
            "crescent_s" to { f, p, _ -> V.crescent(f.world, St, p.add(0.0, 1.3, 0.0), fwd, 8f, 1.1f, 10) },
            "wave_h" to { f, p, _ -> V.shockwave(f.world, H, p, 6f, 14, 1.4f) },
            "wave_so" to { f, p, _ -> V.shockwave(f.world, So, p, 6f, 14, 1.4f) },
            "wave_g" to { f, p, _ -> V.shockwave(f.world, G, p, 6f, 14, 1.4f) },
            "nova" to { f, p, _ -> V.nova(f.world, H, p.add(0.0, 1.2, 0.0), 3f, 12) },
            "flare" to { f, p, _ -> V.flare(f.world, St, p.add(0.0, 1.3, 0.0), 2f, 10) },
            "beam" to { f, p, _ -> V.beam(f.world, H, p, p.add(0.0, 8.0, 0.0), 1f, 12) },
            "slash" to { f, p, _ -> f.fx(kind("SLASH_ARC"), p.add(0.0, 1.1, 0.0), fwd, a = 5f, b = 0.3f, life = 14) },
            "dash" to { f, p, _ -> f.fx(kind("DASH_STREAK"), p.add(-5.0, 0.0, 0.0), p.add(5.0, 0.0, 0.0), life = 12) },
            "clear" to { f, p, _ -> f.fx(kind("CLEAR_WAVE"), p, a = 7f, life = 16) },
            "seam" to { f, p, _ -> f.fx(kind("SEAM"), p.add(-6.0, 0.2, 0.0), p.add(6.0, 0.2, 0.0), a = 3f, life = 18) },
            "cut" to { f, p, _ -> f.fx(kind("CUT_PLANE"), p.add(-5.0, 1.0, 0.0), Vec3d(10.0, 0.0, 0.0), a = 2f, b = 1f, life = 14) },
            "breath" to { f, p, a -> f.fx(kind("DRAGON_BREATH"), a.entityPos, a = 10f, life = 18, e1 = a) },
            "blade" to { f, p, a -> f.fxAll(kind("GIANT_BLADE"), a.entityPos, a = 14f, b = 8f, life = 24, e1 = a) },
            "shatter" to { f, p, a -> f.fx(kind("BLADE_SHATTER"), a.entityPos.add(0.0, 1.0, 0.0), life = 18, e1 = a) },
            "debris" to { f, p, _ -> f.fx(kind("DEBRIS_CLOUD"), p, a = 4f, life = 18) },
        )
        for ((name, fire) in list) {
            server {
                val f = checkNotNull(fight())
                fire(f, Vec3d(f.arenaCenter.x, f.floorY + 0.05, f.arenaCenter.z - 1.0), f.arius!!)
            }
            tick(3)
            shot("fx_$name")
            tick(30)
        }
    }

    /** Die Eisklinge, nah von vorn und von der Seite. */
    private fun VerifyKit.iceBlade() {
        vault()
        server {
            val f = checkNotNull(fight())
            f.startDirector(dev.henny.hha_arius.boss.script { while (true) yield(Unit) })
            f.calls.reset()
            f.arius?.let { a ->
                a.bladeState = dev.henny.hha_arius.boss.AriusBlade.ICE
                a.sheathed = false
                a.play(dev.henny.hha_arius.boss.AriusPose.IDLE)
            }
        }
        tick(12)
        client { it.options.hudHidden = true }
        faceArius(3.6)
        lookAtArius()
        tick(6)
        shot("ice_front")
        server { srv ->
            val f = checkNotNull(fight())
            val a = checkNotNull(f.arius)
            val d = f.facing(a)
            val at = a.entityPos.add(-d.z * 3.0, 0.0, d.x * 3.0).add(d.multiply(1.0))
            player(srv).teleport(f.world, at.x, a.y, at.z, emptySet(), 0f, 4f, false)
        }
        tick(3)
        lookAtArius()
        tick(4)
        shot("ice_side")
        client { it.options.hudHidden = false }
    }

    /** Arius muss um die Säulen des Tempels zum Spieler finden, ohne Festhängen und ohne Laufen auf der Stelle. */
    private fun VerifyKit.pathing() {
        temple()
        server { fight()?.let { f -> f.startDirector(dev.henny.hha_arius.boss.script { while (true) yield(Unit) }) } }
        tick(5)
        val c = server { fight()!!.arenaCenter.let { Vec3d(it.x, fight()!!.floorY, it.z) } }
        val r = server { fight()!!.arenaRadius }
        server {
            val f = fight()!!
            val y = f.floorY
            val sb = StringBuilder("floor map (center ${c.x},${c.z} r=$r)\n")
            for (z in (c.z - r).toInt()..(c.z + r).toInt()) {
                for (x in (c.x - r).toInt()..(c.x + r).toInt()) {
                    val v = Vec3d(x + 0.5, y, z + 0.5)
                    val inside = Math.hypot(v.x - c.x, v.z - c.z) <= r - 0.6
                    val under = !f.world.getBlockState(net.minecraft.util.math.BlockPos.ofFloored(v).down()).isAir
                    sb.append(if (!inside) ' ' else if (f.passable(v) && under) '.' else '#')
                }
                sb.append('\n')
            }
            log(sb.toString())
        }
        val rnd = java.util.Random(7)
        var worst = 0
        var failed = 0
        var totalRatio = 0.0
        var runs = 0
        for (i in 0 until 14) {
            fun free(): Vec3d {
                repeat(200) {
                    val ang = rnd.nextDouble() * Math.PI * 2
                    val rr = (r - 3.0) * Math.sqrt(rnd.nextDouble())
                    val p = c.add(Math.cos(ang) * rr, 0.0, Math.sin(ang) * rr)
                    if (server { fight()!!.passable(p) && !fight()!!.world.getBlockState(net.minecraft.util.math.BlockPos.ofFloored(p).down()).isAir }) return p
                }
                return c
            }
            val from = free()
            val to = free()
            if (from.distanceTo(to) < 10.0) continue
            server { srv ->
                val f = fight()!!
                f.place(f.arius!!, from)
                player(srv).teleport(f.world, to.x, to.y, to.z, emptySet(), 0f, 0f, false)
            }
            tick(2)
            var stuck = 0
            var maxStuck = 0
            var walked = 0.0
            var last = from
            var t = 0
            var reached = false
            while (t < 260) {
                val now = server { srv ->
                    val f = fight()!!
                    val a = f.arius!!
                    f.stepToward(a, player(srv).entityPos, 0.3)
                    a.entityPos
                }
                val step = now.distanceTo(last)
                walked += step
                if (step < 0.02) stuck++ else stuck = 0
                maxStuck = maxOf(maxStuck, stuck)
                last = now
                tick(1)
                t++
                if (Math.hypot(now.x - to.x, now.z - to.z) < 1.2) {
                    reached = true
                    break
                }
            }
            val straight = Math.hypot(from.x - to.x, from.z - to.z)
            if (!reached) log("path $i nav: " + server { fight()!!.debugNav() })
            if (!reached) failed++ else {
                totalRatio += walked / straight
                runs++
            }
            worst = maxOf(worst, maxStuck)
            log("path %d: straight %.1f walked %.1f ticks %d reached=%s longest stall %d, ends %.1f from goal, from=%s to=%s".format(i, straight, walked, t, reached, maxStuck, Math.hypot(last.x - to.x, last.z - to.z), from, to))
        }
        log("path: failed=$failed, longest stall=$worst ticks, average detour x%.2f over $runs runs".format(if (runs > 0) totalRatio / runs else 0.0))
    }

    /** Abstände der Züge in der letzten Phase, mit einem Spieler, der ausweicht. */
    private fun VerifyKit.pace() {
        finaleFight()
        val log = ArrayList<Long>()
        server { srv ->
            val f = fight()!!
            f.moves.debugTurns = log
            f.ariusHp = f.ariusMax * 0.62f
            val a = f.arius!!
            val at = a.entityPos.add(6.0, 0.0, 0.0)
            player(srv).teleport(f.world, at.x, at.y, at.z, emptySet(), 90f, 0f, false)
        }
        val rnd = java.util.Random(11)
        for (t in 0 until 1500) {
            if (t % 25 == 0) server { srv ->
                val f = fight() ?: return@server
                val p = player(srv)
                val a = f.arius ?: return@server
                val ang = rnd.nextDouble() * Math.PI * 2
                val rr = 5.0 + rnd.nextDouble() * 6.0
                val at = f.clampToArena(a.entityPos.add(Math.cos(ang) * rr, 0.0, Math.sin(ang) * rr), 2.0)
                if (f.passable(Vec3d(at.x, f.floorY, at.z))) p.teleport(f.world, at.x, f.floorY, at.z, emptySet(), p.yaw, p.pitch, false)
                f.ariusHp = maxOf(f.ariusHp, f.ariusMax * 0.55f)
            }
            tick(1)
        }
        val gaps = log.zipWithNext { x, y -> y - x }
        log("pace: ${log.size} turns in 1500 ticks, gaps min=${gaps.minOrNull()} median=${gaps.sorted().getOrNull(gaps.size / 2)} max=${gaps.maxOrNull()}")
        server { fight()?.moves?.debugTurns = null }
    }

    private fun VerifyKit.shield() {
        temple()
        faceArius(3.0)
        server { srv ->
            val p = player(srv)
            p.setStackInHand(Hand.OFF_HAND, ItemStack(Items.SHIELD))
            p.setCurrentHand(Hand.OFF_HAND)
        }
        tick(6)
        server { srv ->
            val p = player(srv)
            val f = checkNotNull(fight())
            val shield = p.offHandStack
            val a = checkNotNull(f.arius)
            f.place(a, p.entityPos.add(p.rotationVector.x * 2, 0.0, p.rotationVector.z * 2))
            val before = p.health
            check(p.isBlocking)
            Damage.hit(p, a, Damage.Kind.SOLVANE, 3f, 1f)
            check(p.health == before && p.itemCooldownManager.isCoolingDown(shield) && !p.isBlocking)
            log("shield: cooldown after one block = ${p.itemCooldownManager.getCooldownProgress(shield, 0f)}")
            Damage.hit(p, a, Damage.Kind.SOLVANE, 3f, 1f)
            check(p.health < before)
        }
        log("shield: light hit blocked, shield disabled, follow-up dealt damage")
    }
}
