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
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Nivaris (Konzept 4.3, 5.2): Luft ≤ 9 s → Landung → Boden mit 2–3 Angriffen → Erschöpfung (offen) → Abflug. Ihr Atem
 * hinterlässt Eiskämme; ein Sturzflug durch einen Kamm bringt sie zu Boden. Ansagen in der Gefahrenfarbe SOUL.
 *
 * Seit V2 ein 96 Blöcke langer Schlangendrache: Mechanik, Zeiten und Ansagen sind unverändert; der Server bewegt nur den
 * Rumpf vorn (Entity, Trefferbox) und setzt die Pose, Kopf und Körper ergeben sich daraus ([NivarisShape], Renderer).
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

    /**
     * Anker relativ zur Entity: vorwärts entlang der Blickrichtung, hoch (siehe docs/NIVARIS_ANCHORS.md). Kopf und
     * Kehle folgen dem Kopfgelenk der laufenden Pose ([NivarisShape]); der lange Körper ist nur Darstellung.
     */
    private fun anchor(zone: Zone): Pair<Double, Double> {
        val d = f.nivaris ?: return 0.0 to 0.0
        // An der Nadel (Auftritt) steht die Entity als Stellvertreter in Reichweite; keine Zonen, Kopfbox bleibt bei ihr.
        if (d.mode != NivarisMode.NORMAL) return 0.0 to NivarisShape.SPINE_UP
        return when (zone) {
            Zone.HEAD -> NivarisShape.headCenter(d.pose, d.flying)
            Zone.THROAT -> NivarisShape.throat(d.pose, d.flying)
            Zone.BODY -> NivarisAnchors.BODY
            Zone.TAIL -> if (d.flying) NivarisAnchors.TAIL_AIR else NivarisAnchors.TAIL
        }
    }

    fun anchorPos(zone: Zone): Vec3d {
        val d = f.nivaris ?: return Vec3d.ZERO
        val (fwd, up) = anchor(zone)
        val dir = f.facing(d)
        return Vec3d(d.x + dir.x * fwd, d.y + up, d.z + dir.z * fwd)
    }

    /** Maul (vor der Schädelmitte), für sichtbare Effekte. */
    private fun mouth(): Vec3d {
        val d = f.nivaris ?: return Vec3d.ZERO
        val k = NivarisShape.head(d.pose, d.flying)
        val dir = f.facing(d)
        val c = anchorPos(Zone.HEAD)
        return c.add(dir.multiply(kotlin.math.cos(k.pitch) * 1.4)).add(0.0, -kotlin.math.sin(k.pitch) * 1.4 - 0.3, 0.0)
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

    /** Flugrichtung und -tempo (Blöcke je Tick) aus dem letzten Flug, damit Kurven ohne Knick ineinander übergehen. */
    private var vel: Vec3d? = null

    /**
     * Fliegt **gelenkt** zu [to] (wie die Drachen in Black Myth: Wukong): Die Richtung dreht je Tick höchstens um einen
     * Winkel (nah am Ziel mehr, damit es sicher ankommt), das Tempo gleitet zu [speed]. So entstehen weite Bögen statt
     * gerader Linien, und der Körper (Spur auf dem Client) fließt als Kurve. Höchstens [maxTicks]; nicht angekommen
     * wird am Ende das letzte Stück eingezogen, damit Ansagen und Treffer am geplanten Ort bleiben.
     */
    private suspend fun SequenceScope<Unit>.flyTo(to: Vec3d, speed: Double, maxTicks: Int, faceTarget: Vec3d? = null) {
        val d = f.nivaris ?: return
        d.flying = true
        var v = vel?.takeIf { it.lengthSquared() > 1e-6 } ?: f.facing(d).multiply(speed * 0.6)
        var n = 0
        var arrived = false
        // Höhe im Band zwischen Start und Ziel (+3), damit gelenkte Kurven nicht hoch hinaus schießen.
        val ceil = maxOf(d.y, to.y) + 3.0
        val floorLimit = minOf(d.y, to.y, f.floorY + 1.0)
        while (n++ < maxTicks) {
            val pos = d.entityPos
            val delta = to.subtract(pos)
            val len = delta.length()
            val sp = v.length() + (speed - v.length()).coerceIn(-0.08, 0.06)
            if (len <= maxOf(sp, 0.3) * 1.1) {
                f.place(d, to)
                arrived = true
                break
            }
            val want = delta.multiply(1.0 / len)
            val left = (maxTicks - n + 1).coerceAtLeast(1)
            // Drehrate: 6° je Tick, nah am Ziel bzw. knapp an Zeit bis zu 40°.
            val urgency = maxOf(0.0, 1.0 - len / (sp * 14.0)) + maxOf(0.0, 1.0 - left / 8.0)
            val dir = turnToward(v.normalize(), want, Math.toRadians(6.0 + 34.0 * urgency.coerceAtMost(1.0)))
            v = dir.multiply(maxOf(sp, len / left))
            var next = pos.add(v)
            if (next.y > ceil || next.y < floorLimit) {
                next = Vec3d(next.x, next.y.coerceIn(floorLimit, ceil), next.z)
                v = Vec3d(v.x, next.y - pos.y, v.z)
            }
            val clamped = f.clampToArena(next, 2.0)
            f.place(d, Vec3d(clamped.x, next.y, clamped.z))
            f.face(d, faceTarget ?: next.add(v))
            yield(Unit)
        }
        if (!arrived) f.place(d, to)
        vel = v
        f.face(d, faceTarget ?: to.add(v))
    }

    /** Dreht die Einheitsrichtung [a] höchstens um [maxAngle] (Bogenmaß) zu [b]. */
    private fun turnToward(a: Vec3d, b: Vec3d, maxAngle: Double): Vec3d {
        val dot = a.dotProduct(b).coerceIn(-1.0, 1.0)
        val angle = kotlin.math.acos(dot)
        if (angle <= maxAngle || angle < 1e-6) return b
        // Gegenrichtung: über die Seite (waagerecht) drehen.
        val axisHint = if (dot < -0.999) Vec3d(-a.z, 0.0, a.x).normalize() else b.subtract(a.multiply(dot)).normalize()
        return a.multiply(kotlin.math.cos(maxAngle)).add(axisHint.multiply(kotlin.math.sin(maxAngle))).normalize()
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
        // Steigend nach vorn (kein senkrechter Aufstieg: der Körper stünde sonst als Säule unter dem Kopf).
        vel = f.facing(d).multiply(0.35).add(0.0, 0.25, 0.0)
        val up = airPoint(f.clampToArena(d.entityPos.add(f.facing(d).multiply(14.0)), 4.0))
        flyTo(up, 0.6, 30)
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
        // Sichtbar aus dem Maul (Kopf des Schlangenkörpers); Treffer und Deckung wie bisher über die Bahn.
        Vfx.beam(f.world, style, mouth(), Vec3d(b.x, f.floorY + 0.5, b.z), 2.2f, 16)
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
        // Anflug im Bogen: zuerst ein Punkt schräg hinter und über dem Landeplatz, dann flach auf den Platz gleiten.
        val from = flatDir(spot, d.entityPos)
        val approach = Vec3d(spot.x, f.floorY + 3.0, spot.z).add(from.multiply(9.0))
        val travel = max(1, ring.ticks - 8)
        flyTo(approach, max(0.7, sqrt(d.entityPos.squaredDistanceTo(approach)) / travel), travel)
        val left = max(1, ring.remaining)
        val start = d.entityPos
        for (i in 1..left) {
            val k = i / left.toDouble()
            f.place(d, start.lerp(spot, k).add(0.0, sin(k * Math.PI) * 0.8, 0.0))
            f.face(d, spot.add(flatDir(start, spot)))
            yield(Unit)
        }
        vel = null
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
        f.fx(FxKind.ICE_CHUNK, mouth(), to.add(0.0, 0.5, 0.0), a = 2f, life = 8)
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
 * Trefferzonen-Anker von Nivaris (vorwärts, hoch) in Blöcken relativ zur Entity, die nicht am Kopf hängen. Kopf und
 * Kehle kommen je Pose aus [NivarisShape.headCenter] bzw. [NivarisShape.throat] (Tabelle in docs/NIVARIS_ANCHORS.md).
 */
object NivarisAnchors {
    /** Rumpf vorn: Mitte der Trefferbox (Glieder 2–4). */
    val BODY = 0.0 to NivarisShape.SPINE_UP

    /**
     * Schwanz am Boden: Die Schwanzspitze liegt innen in der Ruhe-Spirale hinter ihr (siehe NivarisModel.restCurve);
     * als Trefferzone zählt die erste Windung hinter der Trefferbox.
     */
    val TAIL = -8.0 to 0.9

    /** Im Flug zieht der Körper weit hinter ihr her; nur der Anfang ist in Reichweite der Trefferbox. */
    val TAIL_AIR = -14.0 to 1.2

    // Gehaltene Endhaltungen für Verweise in Szenen (vorher Konstanten): jetzt aus der Formtabelle.
    val HEAD: Pair<Double, Double> get() = NivarisShape.headCenter(NivarisPose.IDLE, false)
    val HEAD_HOVER: Pair<Double, Double> get() = NivarisShape.headCenter(NivarisPose.HOVER, true)
    val THROAT: Pair<Double, Double> get() = NivarisShape.throat(NivarisPose.IDLE, false)
    val THROAT_HOVER: Pair<Double, Double> get() = NivarisShape.throat(NivarisPose.HOVER, true)
}
