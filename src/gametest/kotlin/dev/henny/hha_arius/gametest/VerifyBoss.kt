package dev.henny.hha_arius.gametest

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
        for (c in cases) {
            log("=== case $c")
            when (c) {
                "stance" -> stance()
                "punish" -> punish()
                "parry" -> parry()
                "exact" -> parryExact()
                "classic" -> classic()
                "mace" -> mace()
                "hole" -> hole(feed = true)
                "holefail" -> hole(feed = false)
                "duo" -> duo()
                "waves" -> waves()
                "clash", "laststand" -> lastStand()
                "shield" -> shield()
                "tele" -> teleGallery()
                "path" -> pathing()
                "pace" -> pace()
                else -> log("unknown case $c")
            }
            cmd("hhaarius stop")
            tick(40)
        }
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
        for (t in 0 until 130) {
            val hp = server { player(it).health + player(it).absorptionAmount }
            if (hp < last) lost += last - hp
            if (hp != last) log("punish t=$t health=$hp (${hp - last})")
            last = hp
            client { c ->
                dev.henny.hha_arius.client.fx.PunishCam.debugRig()?.let { (pos, at) ->
                    cam.add(pos)
                    look.add(Math.toDegrees(atan2(-(at.x - pos.x), at.z - pos.z)).toFloat())
                }
                val me = c.player
                val other = c.world?.entities?.firstOrNull { it is dev.henny.hha_arius.boss.AriusEntity }
                if (me != null && other != null) raw.add(me.entityPos.lerp(other.entityPos, 0.5))
            }
            if (t in 16..100 && t % 14 == 2) shot("punish_%03d".format(t))
            tick(1)
        }
        godMode = true
        log("punish: total lost=$lost (hearts ${lost / 2})")
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

    private fun VerifyKit.lastStand() {
        finaleFight()
        addBots(2)
        try {
            server { srv ->
                val f = checkNotNull(fight())
                f.fighters[bots[0].id]?.ko = true
                srv.playerManager.getPlayer(bots[1].id)?.networkHandler?.onDisconnected(
                    net.minecraft.network.DisconnectionInfo(net.minecraft.text.Text.literal("verify leave")))
                f.moves.summit.debugJumpToLastStand()
            }
            check(waitUntil(1200, 1) { server { fight()?.moves?.summit?.stage == 2 } })
            lookAtArius()
            shot("last_stand_windup")
            check(waitUntil(400, 1) { server { fight()?.moves?.summit?.stage == 3 } })
            lookAtArius()
            shot("last_stand_open")
            check(server { srv ->
                val f = checkNotNull(fight())
                val a = checkNotNull(f.arius)
                val before = f.ariusHp
                a.damage(f.world, f.world.damageSources.playerAttack(player(srv)), 8f)
                f.ariusHp in 0.01f..<before && a.open
            })
            server { srv ->
                val f = checkNotNull(fight())
                f.arius!!.damage(f.world, f.world.damageSources.playerAttack(player(srv)), f.ariusMax)
                check(f.ariusHp <= 0f)
            }
            log("storm breaks: three cuts, opening and lethal player hit passed with a knocked-out and disconnected ally")
        } finally {
            server { srv ->
                for (b in bots) srv.playerManager.getPlayer(b.id)?.networkHandler?.onDisconnected(
                    net.minecraft.network.DisconnectionInfo(net.minecraft.text.Text.literal("verify end")))
            }
            bots.clear()
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

    /** Arius muss um die Säulen des Tempels zum Spieler finden, ohne Festhängen und ohne Laufen auf der Stelle. */
    private fun VerifyKit.pathing() {
        temple()
        server { fight()?.let { f -> f.startDirector(dev.henny.hha_arius.boss.script { while (true) yield(Unit) }) } }
        tick(5)
        val c = server { fight()!!.arenaCenter.let { Vec3d(it.x, fight()!!.floorY, it.z) } }
        val r = server { fight()!!.arenaRadius }
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
