package dev.henny.hha_arius.boss

import dev.henny.hha_arius.arena.ArenaLayout
import dev.henny.hha_arius.arena.RideLandmarks
import dev.henny.hha_arius.net.AtmosMode
import dev.henny.hha_arius.net.FxKind
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.block.Blocks
import net.minecraft.entity.effect.StatusEffectInstance
import net.minecraft.entity.effect.StatusEffects
import net.minecraft.particle.BlockStateParticleEffect
import net.minecraft.particle.ParticleTypes
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.math.Vec3d
import java.util.UUID
import kotlin.math.min

/**
 * Einzelne Testziele für `/arius test <target>` in exakter Kampfreihenfolge.
 * [trainingSlot] entspricht dem bisherigen Trainings-Code (1–4) wo wiederverwendet, sonst 0 (wird aber dank
 * [EirholmFight.isPractice] trotzdem immer als Übungs-/Testlauf ohne Siegelverbrauch oder Beute behandelt).
 */
enum class AriusTestTarget(val id: String, val trainingSlot: Int = 0) {
    INTRO("intro"),
    PHASE1("phase1", trainingSlot = 1),
    WHITE_HORIZON("white_horizon"),
    KICK("kick"),
    SOLVANE_REVEAL("solvane_reveal"),
    PICKUP_FLIGHT("pickup_flight"),
    GLACIER("glacier", trainingSlot = 2),
    RIDE_INTRO("ride_intro"),
    DRAGON_RIDE("dragon_ride"),
    QTE_ROCK_ARCH("qte_rock_arch"),
    QTE_FANGS("qte_fangs"),
    QTE_NEEDLE_RIGHT("qte_needle_right"),
    QTE_ICE_ARCH("qte_ice_arch"),
    QTE_ICEFALL("qte_icefall"),
    QTE_NEEDLE_LEFT("qte_needle_left"),
    QTE_FANGS_FINAL("qte_fangs_final"),
    TRANSFORM("transform"),
    VAULT("vault", trainingSlot = 3),
    WHITE_HOLE("white_hole"),
    HUNT("hunt"),
    HUNT_DARK_CAVE("hunt_dark_cave"),
    HUNT_CRAWL_CAVE("hunt_crawl_cave"),
    HUNT_BRIDGE1("hunt_bridge1"),
    HUNT_CRYSTAL_CAVE("hunt_crystal_cave"),
    HUNT_AVALANCHE("hunt_avalanche"),
    HUNT_FALL_CAVE("hunt_fall_cave"),
    HUNT_LAKE_CAVE("hunt_lake_cave"),
    HUNT_BRIDGE3("hunt_bridge3"),
    HUNT_YANG_REPAIR("hunt_yang_repair"),
    HUNT_WIND_TUNNEL("hunt_wind_tunnel"),
    HUNT_ARRIVE("hunt_arrive"),
    BLADE_RETURN("blade_return"),
    FINALE("finale", trainingSlot = 4),
    LAST_HORIZON("last_horizon"),
    CLIMAX("climax"),
    ENDING("ending"),
    ;

    val descKey: String get() = "hha_arius.test.target.$id"

    companion object {
        private val BY_ID = entries.associateBy { it.id }

        fun byId(id: String): AriusTestTarget? = BY_ID[id.lowercase()]

        fun ids(): List<String> = entries.map { it.id }
    }
}

/** Erzeugt das passende Regie-Skript für ein [AriusTestTarget]. */
object AriusTestRunner {

    fun scriptFor(f: EirholmFight, target: AriusTestTarget): Script = when (target) {
        AriusTestTarget.INTRO -> f.scenes.testIntro()
        AriusTestTarget.PHASE1 -> f.scenes.testPhase1()
        AriusTestTarget.WHITE_HORIZON -> f.scenes.testWhiteHorizon()
        AriusTestTarget.KICK -> f.scenes.testKick()
        AriusTestTarget.SOLVANE_REVEAL -> f.scenes.testSolvaneReveal()
        AriusTestTarget.PICKUP_FLIGHT -> f.scenes.testPickupFlight()
        AriusTestTarget.GLACIER -> f.scenes.trainingGlacier()
        AriusTestTarget.RIDE_INTRO -> f.scenes.testRideIntro()
        AriusTestTarget.DRAGON_RIDE -> f.scenes.testDragonRide()
        AriusTestTarget.QTE_ROCK_ARCH -> rideQte(f, 0)
        AriusTestTarget.QTE_FANGS -> rideQte(f, 1)
        AriusTestTarget.QTE_NEEDLE_RIGHT -> rideQte(f, 2)
        AriusTestTarget.QTE_ICE_ARCH -> rideQte(f, 3)
        AriusTestTarget.QTE_ICEFALL -> rideQte(f, 4)
        AriusTestTarget.QTE_NEEDLE_LEFT -> rideQte(f, 5)
        AriusTestTarget.QTE_FANGS_FINAL -> rideQte(f, 6)
        AriusTestTarget.TRANSFORM -> f.scenes.testTransform()
        AriusTestTarget.VAULT -> f.scenes.trainingVault()
        AriusTestTarget.WHITE_HOLE -> f.scenes.testWhiteHole()
        AriusTestTarget.HUNT -> huntSection(f, null, splitActive = true)
        AriusTestTarget.HUNT_DARK_CAVE -> huntSection(f, "dark_cave", splitActive = true)
        AriusTestTarget.HUNT_CRAWL_CAVE -> huntSection(f, "crawl_cave", splitActive = true)
        AriusTestTarget.HUNT_BRIDGE1 -> huntSection(f, "bridge1", splitActive = true)
        AriusTestTarget.HUNT_CRYSTAL_CAVE -> huntSection(f, "crystal_cave", splitActive = true)
        AriusTestTarget.HUNT_AVALANCHE -> huntSection(f, "avalanche", splitActive = true)
        AriusTestTarget.HUNT_FALL_CAVE -> huntSection(f, "fall_cave", splitActive = true)
        AriusTestTarget.HUNT_LAKE_CAVE -> huntSection(f, "lake_cave", splitActive = true)
        AriusTestTarget.HUNT_BRIDGE3 -> huntSection(f, "bridge3", splitActive = true)
        AriusTestTarget.HUNT_YANG_REPAIR -> huntSection(f, "yang_repair", splitActive = true)
        AriusTestTarget.HUNT_WIND_TUNNEL -> huntSection(f, "wind_tunnel", splitActive = false)
        AriusTestTarget.HUNT_ARRIVE -> f.scenes.testHuntArrive()
        AriusTestTarget.BLADE_RETURN -> f.scenes.testBladeReturn()
        AriusTestTarget.FINALE -> f.scenes.trainingFinale(summit = true)
        AriusTestTarget.LAST_HORIZON -> f.scenes.testLastHorizon()
        AriusTestTarget.CLIMAX -> f.scenes.testClimax()
        AriusTestTarget.ENDING -> f.scenes.testEnding()
    }

    private fun huntSection(f: EirholmFight, section: String?, splitActive: Boolean): Script = script {
        yieldAll(f.scenes.testPrepareForHunt(splitActive))
        val hunt = Hunt(f)
        if (section == null) {
            yieldAll(hunt.run())
        } else {
            val arc = Hunt.sectionArc(section)
            yieldAll(hunt.runFrom(arc, skipRage = true))
        }
        f.chasing = false
        if (f.players().isEmpty()) return@script
        yieldAll(hunt.arrive())
        f.victory()
    }

    /**
     * Isolierter Durchflug durch genau ein Hindernis von [RideLandmarks.all] mit echtem QTE, Reit-Haltung und Trefferlogik.
     */
    private fun rideQte(f: EirholmFight, landmarkIndex: Int): Script = script {
        yieldAll(f.scenes.testEnsureFarAndSplit(splitActive = true))
        val lm = RideLandmarks.all.getOrNull(landmarkIndex) ?: return@script
        val a = f.arius ?: return@script
        f.setPhase(EirholmFight.Phase.COLLAPSE)
        val cruise = 0.64
        val startS = (lm.s - 52.0).coerceAtLeast(RideRoute.JOIN)
        val endS = min(RideRoute.length, lm.s + 42.0)
        val startPos = f.abs(RideRoute.at(startS))
        val lookPos = f.abs(RideRoute.at(min(RideRoute.length, startS + 6.0)))
        val startYaw = (Math.toDegrees(kotlin.math.atan2(lookPos.z - startPos.z, lookPos.x - startPos.x)) - 90.0).toFloat()

        f.spawnNivaris(startPos, startYaw) { d ->
            d.flying = true
            d.bodyRoll = 0f
            d.wingTuck = 0f
            d.play(NivarisPose.GLIDE)
            d.eyeGlow = 100
        }
        val d = f.nivaris ?: return@script
        a.form = AriusForm.DARK
        a.bladeState = AriusBlade.NONE
        a.sheathed = false
        RideSeats.assign(f)
        RideSeats.perch(f, a, d)
        for (p in f.players()) {
            p.teleport(f.world, startPos.x, startPos.y + 3.0, startPos.z, emptySet(), startYaw, 0f, false)
            p.addStatusEffect(StatusEffectInstance(StatusEffects.SLOW_FALLING, 1200, 0, false, false, false))
            RideSeats.pickUp(f, d, p)
        }
        val u = lm.s / RideRoute.length
        f.atmos(if (u >= RideRoute.stormFrom) AtmosMode.AERIE else AtmosMode.SNOW, 20)
        f.music("music.transform", 10)
        MusicCue.play(f, if (u >= RideRoute.stormFrom) "storm" else "ride", 10)

        val kind = qteKind(lm.kind)
        val window = if (kind == Qte.Kind.MASH) 76 else 58
        val asked = HashSet<UUID>()
        val open = ArrayList<Pair<UUID, Qte.Round>>()
        val holding = HashSet<UUID>()
        var s = startS
        var tick = 0
        try {
            while (s < endS && !f.ended) {
                s = min(endS, s + cruise)
                tick++
                val pos = f.abs(RideRoute.at(s))
                val ahead = f.abs(RideRoute.at(min(RideRoute.length, s + 6.0)))
                f.place(d, pos)
                if (ahead.squaredDistanceTo(pos) > 1e-4) f.face(d, ahead)
                if (a.vehicle !== d) RideSeats.perch(f, a, d)
                a.yaw = d.yaw; a.bodyYaw = d.bodyYaw; a.headYaw = d.headYaw
                for (p in f.players()) {
                    if (p.vehicle !== d && p.isAlive) p.startRiding(d, true, true)
                    p.fallDistance = 0.0
                }
                val tight = s in (lm.s - 30.0)..(lm.s + 26.0)
                d.wingTuck = (d.wingTuck + if (tight) 0.06f else -0.035f).coerceIn(0f, 1f)
                if (!tight && tick % 30 == 0) f.ambient("nivaris.wing", pos, 3.2f, 0.76f)

                for (p in f.players()) {
                    if (p.uuid in asked || p.vehicle !== d) continue
                    val seat = RideSeats.of(p.uuid)?.coerceAtLeast(0) ?: 0
                    val impact = lm.s - RideHooks.serverOffset(seat).z
                    if (s < impact - window * cruise) continue
                    asked += p.uuid
                    val count = if (kind == Qte.Kind.MASH) 5 else 1
                    open += p.uuid to Qte.start(listOf(p), kind, window, count, promptKey(lm.kind))
                    MusicCue.play(listOf(p), "qte", 6)
                }

                val it = open.iterator()
                while (it.hasNext()) {
                    val (uuid, round) = it.next()
                    val p = f.server.playerManager.getPlayer(uuid)
                    if (p == null || p.vehicle !== d) {
                        Qte.close(round)
                        it.remove()
                        continue
                    }
                    val seat = RideSeats.of(uuid)?.coerceAtLeast(0) ?: 0
                    val impact = lm.s - RideHooks.serverOffset(seat).z
                    if (uuid !in holding && round.results[uuid] == true) {
                        holding += uuid
                        val left = ((impact - s) / cruise).toInt().coerceAtLeast(0)
                        sendAction(f, p, rideAction(lm.kind), left + 16)
                        MusicCue.sting(listOf(p), "qte_ok")
                    }
                    if (s >= impact) {
                        Qte.close(round)
                        it.remove()
                        if (round.results[uuid] != true) {
                            sendAction(f, p, RideAction.HIT, 24)
                            f.calls.hitShake(p.entityPos, 3f, 6f)
                            MusicCue.sting(listOf(p), "qte_fail")
                            f.ambient("hold.impact", p.entityPos, 2.2f, 0.8f)
                            val block = if (lm.kind == RideLandmarks.Kind.ROCK_ARCH || lm.kind == RideLandmarks.Kind.NEEDLE_LEFT || lm.kind == RideLandmarks.Kind.NEEDLE_RIGHT) Blocks.DEEPSLATE else Blocks.PACKED_ICE
                            f.world.spawnParticles(BlockStateParticleEffect(ParticleTypes.BLOCK, block.defaultState), p.x, p.y + 1.0, p.z, 24, 0.5, 0.4, 0.5, 0.2)
                            p.sendMessage(Text.translatable(hitKey(lm.kind)).formatted(Formatting.GOLD), true)
                        }
                    }
                }
                yield(Unit)
            }
        } finally {
            for ((_, round) in open) Qte.close(round)
            RideSeats.unperch(f, a)
            RideSeats.clear(f)
            for (p in f.players()) p.removeStatusEffect(StatusEffects.SLOW_FALLING)
        }
        val landing = f.abs(ArenaLayout.VAULT_LANDING[0])
        for ((i, p) in f.players().withIndex()) {
            val sp = f.abs(ArenaLayout.VAULT_LANDING[i % ArenaLayout.VAULT_LANDING.size])
            p.teleport(f.world, sp.x, sp.y, sp.z, emptySet(), 180f, 0f, false)
            p.onLanding()
        }
        f.fx(FxKind.CLEAR_WAVE, landing, a = 6f, life = 16)
        f.victory()
    }

    private fun qteKind(k: RideLandmarks.Kind) = when (k) {
        RideLandmarks.Kind.ROCK_ARCH, RideLandmarks.Kind.ICE_ARCH -> Qte.Kind.SNEAK
        RideLandmarks.Kind.FANGS -> Qte.Kind.JUMP
        RideLandmarks.Kind.NEEDLE_RIGHT -> Qte.Kind.LEFT
        RideLandmarks.Kind.NEEDLE_LEFT -> Qte.Kind.RIGHT
        RideLandmarks.Kind.ICEFALL -> Qte.Kind.MASH
    }

    private fun rideAction(k: RideLandmarks.Kind) = when (k) {
        RideLandmarks.Kind.ROCK_ARCH, RideLandmarks.Kind.ICE_ARCH -> RideAction.DUCK
        RideLandmarks.Kind.FANGS -> RideAction.VAULT
        RideLandmarks.Kind.NEEDLE_RIGHT -> RideAction.SWING_L
        RideLandmarks.Kind.NEEDLE_LEFT -> RideAction.SWING_R
        RideLandmarks.Kind.ICEFALL -> RideAction.TUG
    }

    private fun promptKey(k: RideLandmarks.Kind) = when (k) {
        RideLandmarks.Kind.ROCK_ARCH -> "hha_arius.qte.r_arch"
        RideLandmarks.Kind.ICE_ARCH -> "hha_arius.qte.r_arch_ice"
        RideLandmarks.Kind.FANGS -> "hha_arius.qte.r_fangs"
        RideLandmarks.Kind.NEEDLE_RIGHT -> "hha_arius.qte.r_needle_right"
        RideLandmarks.Kind.NEEDLE_LEFT -> "hha_arius.qte.r_needle_left"
        RideLandmarks.Kind.ICEFALL -> "hha_arius.qte.r_ice"
    }

    private fun hitKey(k: RideLandmarks.Kind) = when (k) {
        RideLandmarks.Kind.ROCK_ARCH, RideLandmarks.Kind.ICE_ARCH -> "hha_arius.msg.ride_hit_arch"
        RideLandmarks.Kind.FANGS -> "hha_arius.msg.ride_hit_fangs"
        RideLandmarks.Kind.NEEDLE_RIGHT, RideLandmarks.Kind.NEEDLE_LEFT -> "hha_arius.msg.ride_hit_needle"
        RideLandmarks.Kind.ICEFALL -> "hha_arius.msg.ride_hit_ice"
    }

    private fun sendAction(f: EirholmFight, player: ServerPlayerEntity, action: Int, ticks: Int) {
        val payload = RideActionS2C(player.id, action, ticks)
        for (p in f.audience()) if (ServerPlayNetworking.canSend(p, RideActionS2C.ID)) ServerPlayNetworking.send(p, payload)
    }
}
