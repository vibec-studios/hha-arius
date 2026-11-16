package dev.henny.hha_arius.boss

import dev.henny.hha.logic.Fx
import dev.henny.hha.logic.Vfx
import dev.henny.hha.logic.boss.Telegraph
import dev.henny.hha_arius.net.FxKind
import net.minecraft.entity.damage.DamageSource
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import net.minecraft.world.RaycastContext
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Nivaris (Konzept 4.3, 5.2): Luft ≤ 9 s → Landung → Boden mit 2–3 Angriffen → Erschöpfung (offen) → Abflug. Ihr Atem
 * hinterlässt Eiskämme; ein Sturzflug durch einen Kamm bringt sie zu Boden. Ansagen in der Gefahrenfarbe SOUL.
 */
class NivarisMoves(private val f: EirholmFight) {

    enum class Zone { HEAD, THROAT, BODY, TAIL }

    private val rnd = java.util.Random()
    private val style = Vfx.Style.SOUL

    var acting = false
        private set

    /** Flughöhe über der Lauffläche. */
    private val cruise: Double get() = if (f.phase >= EirholmFight.Phase.VAULT) 6.0 else 7.0

    // --- Trefferzonen ----------------------------------------------------------------------------------------------------

    /** Anker relativ zur Entity: vorwärts entlang der Blickrichtung, hoch. Werte aus docs/NIVARIS_ANCHORS.md. */
    private fun anchor(zone: Zone): Pair<Double, Double> {
        val d = f.nivaris ?: return 0.0 to 0.0
        return when (zone) {
            Zone.HEAD -> when (d.pose) {
                NivarisPose.DOWNED -> NivarisAnchors.HEAD_DOWNED
                NivarisPose.EXHAUSTED -> NivarisAnchors.HEAD_LOW
                NivarisPose.INHALE -> if (d.flying) 3.17 to 7.81 else NivarisAnchors.HEAD_INHALE
                else -> if (d.flying) NivarisAnchors.HEAD_HOVER else NivarisAnchors.HEAD
            }
            Zone.THROAT -> if (d.flying) NivarisAnchors.THROAT_HOVER else NivarisAnchors.THROAT
            Zone.BODY -> NivarisAnchors.BODY
            Zone.TAIL -> NivarisAnchors.TAIL
        }
    }

    fun anchorPos(zone: Zone): Vec3d {
        val d = f.nivaris ?: return Vec3d.ZERO
        val (fwd, up) = anchor(zone)
        val dir = f.facing(d)
        return Vec3d(d.x + dir.x * fwd, d.y + up, d.z + dir.z * fwd)
    }

    fun zoneOf(player: ServerPlayerEntity, source: DamageSource, projectile: Boolean): Zone {
        val hit: Vec3d = if (projectile) (source.position ?: source.source?.entityPos ?: player.eyePos) else run {
            // Nahkampf: Punkt auf dem Blickstrahl, der dem Drachen am nächsten liegt.
            val eye = player.eyePos
            val look = player.rotationVector
            val center = anchorPos(Zone.BODY)
            val t = max(0.0, center.subtract(eye).dotProduct(look)).coerceAtMost(7.0)
            eye.add(look.multiply(t))
        }
        return Zone.entries.minBy { anchorPos(it).squaredDistanceTo(hit) * (if (it == Zone.THROAT && !f.inhaling) 4.0 else 1.0) }
    }

    // --- Bewegung --------------------------------------------------------------------------------------------------------

    private fun flatDir(from: Vec3d, to: Vec3d): Vec3d {
        val d = Vec3d(to.x - from.x, 0.0, to.z - from.z)
        return if (d.lengthSquared() < 1e-6) Vec3d(0.0, 0.0, 1.0) else d.normalize()
    }

    /** Fliegt geradlinig zu [to] (inkl. Höhe), höchstens [maxTicks]. */
    private suspend fun SequenceScope<Unit>.flyTo(to: Vec3d, speed: Double, maxTicks: Int, faceTarget: Vec3d? = null) {
        val d = f.nivaris ?: return
        d.flying = true
        var n = 0
        while (n++ < maxTicks) {
            val delta = to.subtract(d.entityPos)
            val len = delta.length()
            if (len <= speed) {
                f.place(d, to)
                break
            }
            f.place(d, d.entityPos.add(delta.multiply(speed / len)))
            f.face(d, faceTarget ?: to)
            yield(Unit)
        }
        f.face(d, faceTarget ?: to)
    }

    private fun airPoint(xz: Vec3d, height: Double = cruise): Vec3d = Vec3d(xz.x, f.floorY + height, xz.z)

    private suspend fun SequenceScope<Unit>.takeoff() {
        val d = f.nivaris ?: return
        if (d.flying) return
        d.play(NivarisPose.TAKEOFF)
        wait(AriusTimings.NIVARIS_WINDUP[NivarisPose.TAKEOFF])
        f.sound("nivaris.wing", d.entityPos, 3f, 0.8f)
        Vfx.shockwave(f.world, style, Vec3d(d.x, f.floorY, d.z), 5f, 12, 1f)
        for (p in f.players()) {
            if (p.squaredDistanceTo(d) < 25.0) Fx.launchPlayer(p, flatDir(d.entityPos, p.entityPos).multiply(0.6).add(0.0, 0.25, 0.0))
        }
        d.flying = true
        d.play(NivarisPose.HOVER)
        val up = airPoint(d.entityPos)
        flyTo(up, 0.5, 20)
    }

    // --- Phasen ----------------------------------------------------------------------------------------------------------

    /** Abschnitt II: Luftphase, Landung, Bodenphase, Erschöpfung. */
    fun cycle(): Script = script {
        acting = true
        try {
            yieldAll(airPhase(if (f.ridgeCount() == 0) 2 else 1 + rnd.nextInt(2)))
            yieldAll(groundPhase(2 + rnd.nextInt(2), exhaust = true))
        } finally {
            acting = false
        }
    }

    /** Abschnitt III als Taktgeber: ein Luftangriff mit Landung oder zwei Bodenangriffe. */
    fun turnVault(): Script = script {
        acting = true
        try {
            val d = f.nivaris ?: return@script
            if (d.flying || rnd.nextFloat() < 0.45f) {
                yieldAll(airPhase(1))
                yieldAll(groundPhase(1, exhaust = false))
            } else {
                yieldAll(groundPhase(2, exhaust = rnd.nextFloat() < 0.5f))
            }
        } finally {
            acting = false
        }
    }

    /** Begleitung, während Arius führt: Klaue oder Schwanz gegen Spieler ohne laufende Boss-Ansage. */
    fun support(): Script = script {
        val d = f.nivaris ?: return@script
        if (d.flying) {
            yieldAll(land(null))
            return@script
        }
        val free = f.players().filter { !f.calls.hasBossTelegraph(it) && !Hold.isHeld(it) && !Hold.inGrace(it) }
        val near = free.minByOrNull { it.squaredDistanceTo(d) } ?: return@script wait(20)
        acting = true
        try {
            if (near.squaredDistanceTo(d) < 49.0) yieldAll(claw(near, listOf(near))) else yieldAll(tail(listOf(near)))
        } finally {
            acting = false
        }
        wait(30)
    }

    private fun airPhase(attacks: Int): Script = script {
        takeoff()
        val limit = f.now + (if (f.phase >= EirholmFight.Phase.VAULT) AriusTimings.AIR_MAX_VAULT else AriusTimings.AIR_MAX)
        var done = 0
        while (done < attacks && f.now < limit) {
            val t = f.target() ?: break
            val canDive = f.ridgeCount() > 0 || rnd.nextFloat() < 0.4f
            if (done > 0 && canDive) yieldAll(dive(t)) else yieldAll(breath(t))
            done++
            val d = f.nivaris ?: return@script
            if (!d.flying) return@script
        }
        yieldAll(land(f.target()))
    }

    private fun groundPhase(attacks: Int, exhaust: Boolean): Script = script {
        repeat(attacks) {
            val d = f.nivaris ?: return@script
            if (d.flying) return@script
            val t = f.target() ?: return@script
            val dist = sqrt(t.squaredDistanceTo(d))
            val nearAll = f.players().count { it.squaredDistanceTo(d) < 81.0 }
            when {
                dist > 11 && f.grabSource(d.entityPos) != null && rnd.nextFloat() < 0.6f -> yieldAll(grabThrow(t))
                nearAll >= 2 || (dist < 8 && rnd.nextFloat() < 0.35f) -> yieldAll(tail(null))
                dist < 7 -> yieldAll(claw(t, null))
                rnd.nextFloat() < 0.5f -> yieldAll(gust(t))
                else -> {
                    walkTo(t, 6.0, 40)
                    yieldAll(claw(t, null))
                }
            }
            wait(8)
        }
        if (exhaust) {
            val d = f.nivaris ?: return@script
            d.play(NivarisPose.EXHAUSTED)
            f.openDragon(AriusTimings.EXHAUSTED_TICKS)
            wait(AriusTimings.EXHAUSTED_TICKS)
            d.play(NivarisPose.IDLE)
        }
    }

    private suspend fun SequenceScope<Unit>.walkTo(t: ServerPlayerEntity, range: Double, maxTicks: Int) {
        val d = f.nivaris ?: return
        d.play(NivarisPose.IDLE)
        var n = 0
        while (n++ < maxTicks && t.squaredDistanceTo(d) > range * range) {
            f.stepToward(d, t.entityPos, 0.2)
            yield(Unit)
        }
        f.face(d, t.entityPos)
    }

    // --- D1: Frostodem-Bahn ----------------------------------------------------------------------------------------------

    fun breath(t: ServerPlayerEntity, forcedFrom: Vec3d? = null, forcedTo: Vec3d? = null, makeRidge: Boolean = true): Script = script {
        val d = f.nivaris ?: return@script
        val dir0 = flatDir(d.entityPos, t.entityPos)
        val across = Vec3d(-dir0.z, 0.0, dir0.x).multiply(if (rnd.nextBoolean()) 1.0 else -1.0)
        val a = forcedFrom ?: f.clampToArena(t.entityPos.subtract(across.multiply(17.0)), 1.5)
        val b = forcedTo ?: f.clampToArena(t.entityPos.add(across.multiply(17.0)), 1.5)
        val lineDir = flatDir(a, b)
        val startAir = airPoint(a.subtract(lineDir.multiply(3.0)), 6.0)
        flyTo(startAir, 0.8, 40, faceTarget = b)
        d.play(NivarisPose.INHALE, AriusTimings.NIVARIS_WINDUP[NivarisPose.INHALE])
        val group = f.calls.boss(style = style)
        val line = group.line(Vec3d(a.x, f.floorY, a.z), Vec3d(b.x, f.floorY, b.z), 3.5f, AriusTimings.NIVARIS_WINDUP[NivarisPose.INHALE])
        f.inhaling = true
        f.chokeDamage = 0f
        f.sound("nivaris.inhale", d.entityPos, 3f)
        val threshold = 30f * (1f + 0.3f * (f.startCount - 1))
        var choked = false
        for (i in 0 until line.ticks) {
            d.throatGlow = (i * 100 / line.ticks)
            if (f.chokeDamage >= threshold) {
                choked = true
                break
            }
            yield(Unit)
        }
        f.inhaling = false
        if (choked) {
            group.end()
            yieldAll(choke())
            return@script
        }
        d.throatGlow = 100
        d.play(NivarisPose.BREATH)
        f.sound("nivaris.breath", d.entityPos, 3.5f)
        for (p in line.hits(f.players())) if (!f.coveredFrom(d.entityPos.add(0.0, 2.0, 0.0), p)) f.hurt(p, 14f, Damage.Kind.FROST_BREATH, d)
        Vfx.beam(f.world, style, d.entityPos.add(lineDir.multiply(3.0)).add(0.0, 2.0, 0.0), Vec3d(b.x, f.floorY + 0.5, b.z), 2.2f, 16)
        // Sie gleitet die Bahn entlang.
        val endAir = airPoint(b.add(lineDir.multiply(3.0)), 6.0)
        flyTo(endAir, 1.6, 22, faceTarget = endAir.add(lineDir))
        d.throatGlow = 0
        d.play(NivarisPose.GLIDE)
        if (makeRidge) f.task(script {
            wait(6)
            f.addRidge(Vec3d(a.x, f.floorY, a.z), Vec3d(b.x, f.floorY, b.z))
        })
    }

    /** Erstickt am eigenen Atem: stürzt ab und liegt offen. */
    fun choke(): Script = script {
        val d = f.nivaris ?: return@script
        d.throatGlow = 0
        f.sound("nivaris.choke", d.entityPos, 3f)
        d.play(NivarisPose.DOWNED)
        val ground = Vec3d(d.x, f.floorY, d.z)
        flyTo(f.clampToArena(ground, 3.0), 0.9, 14)
        d.flying = false
        f.sound("nivaris.crash", d.entityPos, 3f)
        Vfx.shockwave(f.world, style, ground, 5f, 14, 1.4f)
        f.onChoke()
        f.openDragon(AriusTimings.CHOKE_STAGGER)
        wait(AriusTimings.CHOKE_STAGGER)
        d.play(NivarisPose.IDLE)
    }

    // --- D2: Sturzflug ---------------------------------------------------------------------------------------------------

    fun dive(t: ServerPlayerEntity): Script = script {
        val d = f.nivaris ?: return@script
        // Abstand gewinnen, damit der Sturz lesbar ist.
        val away = flatDir(t.entityPos, d.entityPos)
        val start = airPoint(f.clampToArena(t.entityPos.add(away.multiply(16.0)), 2.0), cruise + 1)
        flyTo(start, 0.9, 30, faceTarget = t.entityPos)
        val from = Vec3d(d.x, f.floorY, d.z)
        val dir = flatDir(from, t.entityPos)
        val to = f.clampToArena(t.entityPos.add(dir.multiply(4.0)), 2.0).let { Vec3d(it.x, f.floorY, it.z) }
        val line = f.calls.line(from, to, 3f, AriusTimings.NIVARIS_WINDUP[NivarisPose.DIVE], Telegraph.Pace.PROJECTILE, style = style)
        d.play(NivarisPose.DIVE, line.ticks)
        f.sound("nivaris.wing", d.entityPos, 3f, 0.7f)
        val crash = f.ridgeOnPath(from, to)
        wait(line.ticks - 6)
        val end = crash ?: to
        val path = Vec3d(end.x, f.floorY, end.z)
        flyTo(path, max(1.0, sqrt(d.entityPos.squaredDistanceTo(path)) / 6.0), 8, faceTarget = path.add(dir))
        d.flying = false
        if (crash != null) {
            // In den eigenen Eiskamm gekracht: zu Boden gebracht, Kopf am Boden.
            f.breakRidgeNear(crash, 3.0)
            f.sound("nivaris.crash", crash, 3.5f)
            Vfx.shockwave(f.world, style, crash, 6f, 16, 1.6f)
            f.calls.roomShake(crash, 4f, 30f)
            d.play(NivarisPose.DOWNED)
            f.hint("hha_arius.hint.downed")
            f.openDragon(AriusTimings.DOWNED_TICKS)
            wait(AriusTimings.DOWNED_TICKS)
            d.play(NivarisPose.IDLE)
            return@script
        }
        for (p in line.hits(f.players())) f.hurt(p, 16f, Damage.Kind.IMPACT, d)
        f.sound("nivaris.land", to, 3f)
        Vfx.shockwave(f.world, style, to, 4f, 12, 1.2f)
        d.play(NivarisPose.LAND, 1)
        wait(14)
    }

    // --- D3: Landung -----------------------------------------------------------------------------------------------------

    fun land(t: ServerPlayerEntity?): Script = script {
        val d = f.nivaris ?: return@script
        if (!d.flying) return@script
        val spot = landingSpotNear(t)
        val ring = f.calls.ring(spot, 6f, AriusTimings.NIVARIS_WINDUP[NivarisPose.LAND], style = style)
        d.play(NivarisPose.LAND, ring.ticks)
        val above = Vec3d(spot.x, d.y, spot.z)
        val travel = max(1, ring.ticks - 8)
        flyTo(above, max(0.6, sqrt(d.entityPos.squaredDistanceTo(above)) / travel), travel)
        val left = max(1, ring.remaining)
        for (i in 1..left) {
            f.place(d, above.lerp(spot, i / left.toDouble()))
            yield(Unit)
        }
        d.flying = false
        f.sound("nivaris.land", spot, 3.5f)
        for (p in ring.hits(f.players())) {
            f.hurt(p, 12f, Damage.Kind.IMPACT, d)
            Fx.launchPlayer(p, flatDir(spot, p.entityPos).multiply(0.7).add(0.0, 0.3, 0.0))
        }
        Vfx.shockwave(f.world, style, spot, 6f, 16, 1.5f)
        f.fx(FxKind.CLEAR_WAVE, spot, a = 9f, life = 18)
        f.calls.hitShake(spot, 3f, 24f)
        wait(AriusTimings.NIVARIS_RECOVER[NivarisPose.LAND])
    }

    private fun landingSpotNear(t: ServerPlayerEntity?): Vec3d {
        val center = f.arenaCenter
        if (t == null) return Vec3d(center.x, f.floorY, center.z)
        val spots = if (f.phase >= EirholmFight.Phase.VAULT) {
            (0 until 8).map { i -> f.ringPoint(center, 12.0, i / 8.0 * Math.PI * 2) }
        } else {
            dev.henny.hha_arius.arena.ArenaLayout.DRAGON_SPOTS.map { f.abs(it) }
        }
        val pick = spots.filter { it.squaredDistanceTo(t.entityPos) in 49.0..225.0 && f.passable(it) }
            .minByOrNull { it.squaredDistanceTo(t.entityPos) } ?: spots.minBy { it.squaredDistanceTo(t.entityPos) }
        return Vec3d(pick.x, f.floorY, pick.z)
    }

    // --- D4 / D5 / D6 / D7 -------------------------------------------------------------------------------------------------

    fun claw(t: ServerPlayerEntity, only: List<ServerPlayerEntity>?): Script = script {
        val d = f.nivaris ?: return@script
        f.face(d, t.entityPos)
        val dir = f.facing(d)
        val apex = d.entityPos.add(dir.multiply(1.5))
        val cone = f.calls.cone(apex, dir, 6f, 60f, AriusTimings.NIVARIS_WINDUP[NivarisPose.CLAW], players = only, style = style)
        d.play(NivarisPose.CLAW, cone.ticks)
        wait(cone.ticks)
        f.sound("nivaris.claw", apex, 3f)
        for (p in cone.hits(f.players())) f.hurt(p, 12f, Damage.Kind.IMPACT, d)
        Vfx.crescent(f.world, style, apex.add(0.0, 1.0, 0.0), dir, 6f, 4f, 8)
        wait(AriusTimings.NIVARIS_RECOVER[NivarisPose.CLAW])
    }

    fun tail(only: List<ServerPlayerEntity>?): Script = script {
        val d = f.nivaris ?: return@script
        val ring = f.calls.ring(d.entityPos, 9f, AriusTimings.NIVARIS_WINDUP[NivarisPose.TAIL], inner = 3f, players = only, style = style)
        d.play(NivarisPose.TAIL, ring.ticks)
        wait(ring.ticks)
        f.sound("nivaris.tail", d.entityPos, 3f)
        for (p in ring.hits(f.players())) {
            f.hurt(p, 10f, Damage.Kind.IMPACT, d)
            Fx.launchPlayer(p, flatDir(d.entityPos, p.entityPos).multiply(0.9).add(0.0, 0.35, 0.0))
        }
        Vfx.shockwave(f.world, style, d.entityPos, 9f, 12, 0.8f)
        wait(AriusTimings.NIVARIS_RECOVER[NivarisPose.TAIL])
    }

    fun gust(t: ServerPlayerEntity): Script = script {
        val d = f.nivaris ?: return@script
        f.face(d, t.entityPos)
        val dir = f.facing(d)
        val cone = f.calls.cone(d.entityPos, dir, 14f, 50f, AriusTimings.NIVARIS_WINDUP[NivarisPose.WING_GUST], style = style)
        d.play(NivarisPose.WING_GUST, cone.ticks)
        wait(cone.ticks)
        f.sound("nivaris.wing", d.entityPos, 3.5f, 0.6f)
        for (p in cone.hits(f.players())) {
            if (f.coveredFrom(d.entityPos.add(0.0, 1.5, 0.0), p)) continue
            f.hurt(p, 2f, Damage.Kind.PRESSURE, d)
            Fx.launchPlayer(p, flatDir(d.entityPos, p.entityPos).multiply(1.6).add(0.0, 0.3, 0.0))
        }
        f.fx(FxKind.CLEAR_WAVE, d.entityPos.add(dir.multiply(4.0)), dir, a = 14f, life = 16)
        wait(AriusTimings.NIVARIS_RECOVER[NivarisPose.WING_GUST])
    }

    fun grabThrow(t: ServerPlayerEntity): Script = script {
        val d = f.nivaris ?: return@script
        val src = f.grabSource(d.entityPos) ?: return@script
        val srcPos = Vec3d(src.x + 0.5, f.floorY, src.z + 0.5)
        var n = 0
        while (n++ < 30 && d.squaredDistanceTo(srcPos) > 16.0) {
            f.stepToward(d, srcPos, 0.25)
            yield(Unit)
        }
        f.face(d, srcPos)
        d.play(NivarisPose.GRAB)
        wait(AriusTimings.NIVARIS_WINDUP[NivarisPose.GRAB])
        f.removeGrabSource(src)
        f.sound("ridge.break", srcPos, 2.5f, 0.8f)
        val target = f.target() ?: t
        f.face(d, target.entityPos)
        val from = Vec3d(d.x, f.floorY, d.z)
        val to = f.clampToArena(target.entityPos.add(flatDir(from, target.entityPos).multiply(2.0)), 0.5)
        val line = f.calls.line(from, to, 2.5f, AriusTimings.NIVARIS_WINDUP[NivarisPose.THROW], Telegraph.Pace.PROJECTILE, style = style)
        d.play(NivarisPose.THROW, line.ticks)
        wait(line.ticks - 8)
        f.fx(FxKind.ICE_CHUNK, d.entityPos.add(0.0, 3.0, 0.0), to.add(0.0, 0.5, 0.0), a = 2f, life = 8)
        wait(8)
        f.sound("ridge.break", to, 3f, 0.6f)
        for (p in line.hits(f.players())) f.hurt(p, 14f, Damage.Kind.IMPACT, d)
        Vfx.shockwave(f.world, style, to, 2.5f, 10, 1f)
        wait(AriusTimings.NIVARIS_RECOVER[NivarisPose.THROW])
    }

    /** Eisfeld (R3): Atem schräg nach unten in einen Kreis. */
    fun freezeField(center: Vec3d, radius: Float, ticks: Int): Script = script {
        val d = f.nivaris ?: return@script
        f.face(d, center)
        d.play(NivarisPose.FREEZE_FIELD, ticks)
        f.sound("nivaris.inhale", d.entityPos, 3f, 1.1f)
        f.inhaling = true
        f.chokeDamage = 0f
        val threshold = 30f * (1f + 0.3f * (f.startCount - 1))
        for (i in 0 until ticks) {
            d.throatGlow = i * 100 / ticks
            if (f.chokeDamage >= threshold) {
                f.inhaling = false
                f.taktBroken = true
                yieldAll(choke())
                return@script
            }
            yield(Unit)
        }
        f.inhaling = false
        d.throatGlow = 0
        f.sound("nivaris.breath", d.entityPos, 3f, 1.2f)
        Vfx.nova(f.world, style, center.add(0.0, 0.2, 0.0), radius, 14)
    }

    /** Für Kombinationsangriffe: Position einnehmen (in der Luft, mit Blick auf [look]). */
    fun hoverAt(pos: Vec3d, look: Vec3d, ticks: Int): Script = script {
        val d = f.nivaris ?: return@script
        if (!d.flying) takeoff()
        d.play(NivarisPose.HOVER)
        flyTo(airPoint(pos, 6.0), 0.9, ticks, faceTarget = look)
    }

    @Suppress("unused")
    private fun blocked(from: Vec3d, to: Vec3d): Boolean =
        f.world.raycast(RaycastContext(from, to, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, f.nivaris!!)).type != net.minecraft.util.hit.HitResult.Type.MISS

    @Suppress("unused")
    private fun ignore(pos: BlockPos) = pos
}

/**
 * Trefferzonen-Anker von Nivaris (vorwärts, hoch) in Blöcken relativ zur Entity. Startwerte; nach dem Modell-Bericht
 * (docs/NIVARIS_ANCHORS.md) abgeglichen.
 */
object NivarisAnchors {
    val HEAD = 5.06 to 5.41
    val HEAD_INHALE = 3.58 to 6.87
    val HEAD_LOW = 5.48 to 0.68
    val HEAD_DOWNED = 5.81 to 0.71
    val HEAD_HOVER = 5.23 to 6.05

    /** Mitte zwischen Kehle und Herzkern (IDLE). */
    val THROAT = 2.75 to 2.65
    val THROAT_HOVER = 2.65 to 3.66
    val BODY = 0.0 to 1.8
    val TAIL = -8.28 to 1.11
}
