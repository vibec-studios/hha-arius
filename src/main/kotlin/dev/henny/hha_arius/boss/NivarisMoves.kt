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
    // Seit 1.6.5 alle Treffer der Drachin auf etwa zwei Drittel gesenkt (Crix: „fixe den Schaden des Drachen“).

    enum class Zone { HEAD, THROAT, BODY, TAIL }

    private val rnd = java.util.Random()
    private val style = Vfx.Style.SOUL

    var acting = false
        private set

    /** Zustand zurücksetzen, wenn ein laufendes Zug- oder Begleitskript vorzeitig abgebrochen wird. */
    fun clearActing() {
        acting = false
    }

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

    /** Sicherer Flugradius in der aktuellen Arena (hält Kopf, Schwingen und langen Leib von den Felswänden fern). */
    private fun flyKeepRadius(): Double =
        if (f.phase == EirholmFight.Phase.GLACIER) AERIE_FLIGHT_R
        else (f.arenaRadius - 6.0).coerceAtLeast(28.0)

    /**
     * Mitte ihres Flugraums. Seit 1.12.0 (Crix: „der fliegt durch die Wand“): Im Norden des Horsts ragen die Rippen des alten
     * Drachen bis 54 Blöcke an die Mitte heran und 53 hoch, sie stehen in keiner Höhenkarte. Der Flugkreis liegt darum nach
     * Süden versetzt und hält Rippen wie Felswand mitsamt den Flügeln frei.
     */
    private fun flyCenter(): Vec3d =
        if (f.phase == EirholmFight.Phase.GLACIER) f.arenaCenter.add(0.0, 0.0, AERIE_FLIGHT_SOUTH) else f.arenaCenter

    /** Hält einen Flugpunkt im Flugraum ([flyCenter], [flyKeepRadius]), [margin] Blöcke vom Rand. */
    private fun keepInFlight(p: Vec3d, margin: Double = 0.0): Vec3d {
        val c = flyCenter()
        val r = (flyKeepRadius() - margin).coerceAtLeast(6.0)
        val dx = p.x - c.x
        val dz = p.z - c.z
        val d = sqrt(dx * dx + dz * dz)
        return if (d <= r || d < 1e-6) p else Vec3d(c.x + dx / d * r, p.y, c.z + dz / d * r)
    }

    /** Geländehöhe unter [pos] (Fernlandschaft ±6 Blöcke) plus [clearance], mindestens [f.floorY] + 3. */
    private fun terrainMinY(pos: Vec3d, clearance: Double = 4.0): Double {
        val rx = kotlin.math.floor(pos.x).toInt() - f.origin.x
        val rz = kotlin.math.floor(pos.z).toInt() - f.origin.z
        var top = Int.MIN_VALUE
        if (rx in (dev.henny.hha_arius.arena.FarPlan.X_MIN + 8)..(dev.henny.hha_arius.arena.FarPlan.X_MAX - 8) &&
            rz in (dev.henny.hha_arius.arena.FarPlan.Z_MIN + 8)..(dev.henny.hha_arius.arena.FarPlan.Z_MAX - 8)
        ) {
            for (dx in intArrayOf(-6, 0, 6)) for (dz in intArrayOf(-6, 0, 6)) {
                top = maxOf(top, dev.henny.hha_arius.arena.FarPlan.top(rx + dx, rz + dz))
            }
        }
        val tMin = if (top > Int.MIN_VALUE) f.origin.y + top + 1.0 + clearance else f.floorY + 3.0
        val pillar = if (f.phase == EirholmFight.Phase.GLACIER && DragonRail.overPillar(rx, rz)) f.floorY + DragonRail.PILLAR_TOP else 0.0
        return maxOf(f.floorY + 3.0, tMin, pillar)
    }

    /**
     * Ein gelenkter Flugschritt (seit 1.8.1 für jede Bewegung in der Luft): Die Richtung dreht höchstens um [maxTurn]
     * Grad je Tick zu [toward], das Tempo gleitet zu [speed]; Höhe zwischen [floorLimit] und [ceil]. Sie blickt entlang
     * der Flugrichtung (oder auf [faceTarget]). Daraus entstehen weite Bögen statt Haken, und der Körper (Wegspur auf
     * dem Client) fließt als Kurve.
     */
    private fun steer(d: NivarisEntity, toward: Vec3d, speed: Double, maxTurn: Double, faceTarget: Vec3d? = null,
                      ceil: Double = Double.MAX_VALUE, floorLimit: Double = f.floorY + 3.0) {
        val pos = d.entityPos
        val cappedSpeed = speed.coerceAtMost(MAX_FLIGHT)
        // Seit 1.11.0 aus der echten Flugrichtung: `vel` kann nach einer Bahn aus einem anderen Ablauf veraltet sein.
        var v = DragonRail.heading(f, d).normalize().multiply(vel?.length()?.takeIf { it > 1e-3 } ?: maxOf(0.25, cappedSpeed * 0.5))
        val sp = (v.length() + (cappedSpeed - v.length()).coerceIn(-0.05, 0.05)).coerceAtLeast(0.05)
        val delta = toward.subtract(pos)
        var want = if (delta.lengthSquared() < 1e-6) v.normalize() else delta.normalize()
        // Arenawand-Abstand beim freien Steuern (Horst-Felswand & Rippen).
        val keepR = flyKeepRadius()
        val c = flyCenter()
        val ox = pos.x - c.x
        val oz = pos.z - c.z
        val distR = sqrt(ox * ox + oz * oz)
        if (f.arenaRadius > 40.0 && distR > keepR - 18.0 && distR > 1e-3 && v.x * ox + v.z * oz > 0.0) {
            val inward = Vec3d(-ox / distR, want.y.coerceIn(-0.2, 0.2), -oz / distR).normalize()
            val pull = ((distR - (keepR - 18.0)) / 18.0).coerceIn(0.25, 1.0)
            want = want.lerp(inward, pull).normalize()
        }
        // Seit 1.11.0 nie enger als der Mindestradius der Flugbahnen ([DragonRail]), egal was der Aufrufer erlaubt.
        val limit = minOf(Math.toRadians(maxTurn), sp / DragonRail.MIN_RADIUS)
        v = turnToward(v.normalize(), want, limit).multiply(sp)
        var next = pos.add(v)
        val minY = maxOf(floorLimit, terrainMinY(next, 3.8))
        if (next.y > ceil || next.y < minY) {
            next = Vec3d(next.x, next.y.coerceIn(minY, ceil), next.z)
            v = Vec3d(v.x, next.y - pos.y, v.z)
        }
        val clamped = keepInFlight(next)
        f.place(d, Vec3d(clamped.x, next.y, clamped.z))
        f.face(d, faceTarget ?: next.add(v.multiply(4.0)))
        vel = v
    }

    /**
     * Fliegt über eine feste Bahn ([DragonRail], Radius mindestens 24) zu [to]. Ruhiges, gewichtiges Flugtempo
     * (höchstens 1,20 Blöcke je Tick) mit Geländefreiheit und Abstand zu Arenawänden. [arrive] = gewünschte
     * Flugrichtung am Ziel (sie setzt vorher dazu an). Auf den letzten Ticks dreht sich der Blick zu [faceTarget].
     */
    private suspend fun SequenceScope<Unit>.flyTo(to: Vec3d, speed: Double, maxTicks: Int, faceTarget: Vec3d? = null, arrive: Vec3d? = null) {
        val d = f.nivaris ?: return
        d.flying = true
        val heading = DragonRail.heading(f, d)
        val keepR = flyKeepRadius()
        val safeTo = keepInFlight(to).let { Vec3d(it.x, maxOf(to.y, f.floorY + 3.0), it.z) }
        val points = if (arrive != null && arrive.lengthSquared() > 1e-6) {
            val before = keepInFlight(safeTo.subtract(arrive.normalize().multiply(26.0)), 2.0)
            listOf(Vec3d(before.x, safeTo.y + 2.0, before.z), safeTo)
        } else listOf(safeTo)
        val open = f.arenaRadius > 40.0
        val raw = DragonRail.path(d.entityPos, heading, points, DragonRail.MIN_RADIUS, if (open) flyCenter() else null, keepR)
        val rail = DragonRail.clearTerrain(raw, f.origin, f.floorY, clearance = 4.5)
        val len = DragonRail.length(rail)
        // Seit 1.12.0 ruhiger (Crix: „viel zu schnell“) und mit sanfter Beschleunigung statt Ruck.
        val cruise = maxOf(speed.coerceAtMost(MAX_FLIGHT), (len / maxTicks.coerceAtLeast(1)) * 0.85).coerceIn(0.5, MAX_FLIGHT)
        var cur = (vel?.length() ?: (cruise * 0.65)).coerceIn(0.25, MAX_FLIGHT)
        var s = 0.0
        while (s < len) {
            cur += (cruise - cur).coerceIn(-0.03, 0.03)
            s = minOf(len, s + cur)
            val before = d.entityPos
            val pos = DragonRail.at(rail, s)
            f.place(d, pos)
            val ahead = DragonRail.at(rail, s + 3.0)
            val last = (len - s) / cur.coerceAtLeast(0.2) < 8.0
            f.face(d, if (last && faceTarget != null) faceTarget else if (ahead.squaredDistanceTo(pos) > 0.01) ahead else pos.add(f.facing(d)))
            vel = pos.subtract(before)
            yield(Unit)
        }
        if (faceTarget != null) f.face(d, faceTarget)
    }

    /** Senkt sich fast senkrecht auf [to] (Erschöpfung, Absturz): kein Flug, daher keine Bahn. */
    private suspend fun SequenceScope<Unit>.sinkTo(to: Vec3d, ticks: Int, faceTarget: Vec3d? = null) {
        val d = f.nivaris ?: return
        val from = d.entityPos
        for (i in 1..ticks.coerceAtLeast(1)) {
            val k = i / ticks.coerceAtLeast(1).toDouble()
            f.place(d, from.lerp(to, k * k * (3 - 2 * k)))
            if (faceTarget != null) f.face(d, faceTarget)
            yield(Unit)
        }
        vel = null
        // Nach dem senkrechten Absinken zählt ihre Blickrichtung, sonst startet der nächste Flug steil nach unten.
        d.travelDir = null
    }

    /**
     * Kreisen (Kang-Jin Loong): weite Runden um die Arenamitte in Radius [radius] und Höhe [height], [ticks] lang, die
     * Richtung bleibt; der Körper legt sich als Bogen um das Feld. Nie enger als der Körper lang ist (sonst flöge sie
     * durch sich selbst).
     */
    internal suspend fun SequenceScope<Unit>.circle(ticks: Int, radius: Double, height: Double, speed: Double = 0.78, sign: Double = circleSign) {
        val d = f.nivaris ?: return
        d.flying = true
        val c = flyCenter()
        val maxR = (flyKeepRadius() - 6.0).coerceAtLeast(DragonRail.MIN_RADIUS + 4.0)
        val r = radius.coerceIn(DragonRail.MIN_RADIUS + 4.0, maxR)
        var a = kotlin.math.atan2(d.z - c.z, d.x - c.x)
        repeat(ticks) {
            a += sign * speed / r
            val ahead = a + sign * 0.55
            val target = Vec3d(c.x + kotlin.math.cos(ahead) * r, f.floorY + height + kotlin.math.sin(f.now * 0.05) * 1.5, c.z + kotlin.math.sin(ahead) * r)
            steer(d, target, speed, 5.5)
            yield(Unit)
        }
    }

    /** Drehsinn der Runden in dieser Luftphase (wechselt je Phase). */
    private var circleSign = 1.0

    /** Dreht die Einheitsrichtung [a] höchstens um [maxAngle] (Bogenmaß) zu [b]. */
    private fun turnToward(a: Vec3d, b: Vec3d, maxAngle: Double): Vec3d {
        val dot = a.dotProduct(b).coerceIn(-1.0, 1.0)
        val angle = kotlin.math.acos(dot)
        if (angle <= maxAngle || angle < 1e-6) return b
        // Gegenrichtung: über die Seite (waagerecht) drehen.
        val axisHint = if (dot < -0.999) Vec3d(-a.z, 0.0, a.x).normalize() else b.subtract(a.multiply(dot)).normalize()
        return a.multiply(kotlin.math.cos(maxAngle)).add(axisHint.multiply(kotlin.math.sin(maxAngle))).normalize()
    }

    private fun airPoint(xz: Vec3d, height: Double = cruise): Vec3d = keepInFlight(xz).let { Vec3d(it.x, f.floorY + height, it.z) }

    internal suspend fun SequenceScope<Unit>.takeoff() {
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
        vel = f.facing(d).multiply(0.35).add(0.0, 0.2, 0.0)
        val up = airPoint(f.clampToArena(d.entityPos.add(f.facing(d).multiply(18.0)), 10.0), cruise + 4)
        flyTo(up, 0.72, 26)
    }

    /**
     * Kleine Eisdornen an [points] (meist 1 hoch, selten 2), die nach [life] Ticks zerspringen. Seit 1.8.1 bleibt vom
     * Drachen nichts Wandartiges mehr stehen (Crix: „keine Eiswände mehr“): höchstens einzelne, kurz lebende Dornen.
     * Wo ein Spieler steht, wächst keiner.
     */
    private fun spikes(points: List<Vec3d>, life: Int) {
        val cells = ArrayList<Pair<BlockPos, net.minecraft.block.BlockState>>()
        val used = HashSet<Long>()
        val taken = f.players().map { it.blockPos.x.toLong() shl 32 or (it.blockPos.z.toLong() and 0xFFFFFFFFL) }.toSet()
        for (p in points) {
            val base = BlockPos.ofFloored(p.x, f.floorY, p.z)
            val key = base.x.toLong() shl 32 or (base.z.toLong() and 0xFFFFFFFFL)
            if (key in taken || !used.add(key)) continue
            if (f.world.getBlockState(base.down()).isAir) continue
            val h = if (rnd.nextInt(5) == 0) 2 else 1
            for (y in 0 until h) cells += base.up(y) to (if (y == h - 1) net.minecraft.block.Blocks.BLUE_ICE else net.minecraft.block.Blocks.PACKED_ICE).defaultState
        }
        if (cells.isEmpty()) return
        val placed = f.edits.fill(cells)
        f.task(script {
            wait(life)
            for ((i, pos) in placed.withIndex()) {
                if (i % 2 == 0) f.world.spawnParticles(net.minecraft.particle.BlockStateParticleEffect(net.minecraft.particle.ParticleTypes.BLOCK, net.minecraft.block.Blocks.PACKED_ICE.defaultState),
                    pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, 4, 0.25, 0.25, 0.25, 0.05)
            }
            f.edits.revert(placed)
        })
    }

    // --- Phasen ----------------------------------------------------------------------------------------------------------

    /** Abschnitt II: Luftphase, Landung, Bodenphase, Erschöpfung. */
    fun cycle(): Script = script {
        acting = true
        try {
            yieldAll(airPhase(2 + rnd.nextInt(2)))
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
        wait(18)
    }

    /** Ob in dieser Luftphase der Winterzorn schon kam (höchstens einmal je Phase, nicht als erster Angriff). */
    private var roared = false

    /**
     * Luftphase nach Kang-Jin Loong (Black Myth: Wukong, Kapitel 3), seit 1.8.1 überarbeitet: Zwischen den Angriffen
     * kreist sie in weiten Runden um den Horst, der Körper legt sich als Bogen ums Feld. Angriffe: Frostatem-Bahn,
     * Gletschersturz (fegender Sturzflug mit Aufprall, danach offen), Himmelssturz, Hagel (Eisspeere fallen auf
     * angesagte Stellen), Eiskäfig, Sturmspirale, Tiefflug und einmal je Phase der Winterzorn (aufgeladener Frostschlag
     * über das ganze Feld mit sicherer Zone). Landet sie (Sturz, Zorn), endet die Luftphase.
     */
    private fun airPhase(attacks0: Int): Script = script {
        takeoff()
        circleSign = if (rnd.nextBoolean()) 1.0 else -1.0
        roared = false
        val attacks = attacks0 + 1
        val vault = f.phase >= EirholmFight.Phase.VAULT
        val limit = f.now + (if (vault) AriusTimings.AIR_MAX_VAULT else AriusTimings.AIR_MAX) + 200
        var done = 0
        var last = -1
        // Seit 1.10.0 kürzer gekreist (Crix: „manche Angriffe dauern ewig, bevor sie kommen“).
        circle(14 + rnd.nextInt(12), 26.0, 12.0)
        while (done < attacks && f.now < limit) {
            val t = f.target() ?: break
            var pick = rnd.nextInt(8)
            if (pick == last) pick = (pick + 1) % 8
            if (pick == 7 && (roared || done == 0 || vault)) pick = 3
            last = pick
            when (pick) {
                0 -> yieldAll(breath(t))
                1 -> yieldAll(dive(t))
                2 -> yieldAll(skyPlunge(t))
                3 -> yieldAll(iceVolley())
                4 -> yieldAll(bodySweep(t))
                5 -> yieldAll(iceCage(t))
                6 -> yieldAll(stormSpiral())
                else -> {
                    roared = true
                    yieldAll(wintersRoar())
                }
            }
            done++
            val d = f.nivaris ?: return@script
            if (!d.flying) return@script
            if (done < attacks) circle(10 + rnd.nextInt(12), 22.0 + rnd.nextDouble() * 10.0, 10.0 + rnd.nextDouble() * 6.0)
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
                dist < 9 && rnd.nextFloat() < 0.28f -> yieldAll(frostRoar())
                dist in 6.0..20.0 && rnd.nextFloat() < 0.3f -> yieldAll(coilCrush(t))
                dist in 8.0..22.0 && rnd.nextFloat() < 0.3f -> yieldAll(glacierCharge(t))
                dist > 11 && f.grabSource(d.entityPos) != null && rnd.nextFloat() < 0.5f -> yieldAll(grabThrow(t))
                nearAll >= 2 || (dist < 8 && rnd.nextFloat() < 0.35f) -> yieldAll(tail(null))
                dist < 7 -> yieldAll(claw(t, null))
                rnd.nextFloat() < 0.5f -> yieldAll(gust(t))
                else -> {
                    walkTo(t, 6.0, 26)
                    yieldAll(claw(t, null))
                }
            }
            wait(6)
        }
        if (exhaust) {
            val d = f.nivaris ?: return@script
            d.play(NivarisPose.EXHAUSTED)
            f.openDragon(AriusTimings.EXHAUSTED_TICKS)
            openMusic(true)
            wait(AriusTimings.EXHAUSTED_TICKS)
            openMusic(false)
            d.play(NivarisPose.IDLE)
        }
    }

    private suspend fun SequenceScope<Unit>.walkTo(t: ServerPlayerEntity, range: Double, maxTicks: Int) {
        val d = f.nivaris ?: return
        d.play(NivarisPose.IDLE)
        var n = 0
        while (n++ < maxTicks && t.squaredDistanceTo(d) > range * range) {
            f.stepToward(d, t.entityPos, 0.3)
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
        flyTo(startAir, 0.85, 34, faceTarget = b, arrive = lineDir)
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
        for (p in line.hits(f.players())) if (!f.coveredFrom(d.entityPos.add(0.0, 2.0, 0.0), p)) f.hurt(p, 9f, Damage.Kind.FROST_BREATH, d)
        // Sichtbar aus dem Maul (Kopf des Schlangenkörpers); Treffer und Deckung wie bisher über die Bahn.
        Vfx.beam(f.world, style, mouth(), Vec3d(b.x, f.floorY + 0.5, b.z), 2.2f, 16)
        // Sie gleitet die Bahn entlang.
        val endAir = airPoint(b.add(lineDir.multiply(3.0)), 6.0)
        flyTo(endAir, 1.05, 28, faceTarget = endAir.add(lineDir))
        d.throatGlow = 0
        d.play(NivarisPose.GLIDE)
        // Seit 1.8.1 kein Eiskamm mehr: nur eine lockere Spur kleiner Frostdornen, die nach 2,5 s zerspringt.
        if (makeRidge) f.task(script {
            wait(6)
            val len = sqrt(a.squaredDistanceTo(b))
            val side = Vec3d(-lineDir.z, 0.0, lineDir.x)
            val n = (len / 3.2).toInt()
            spikes((0..n).map { k -> a.lerp(b, k / n.coerceAtLeast(1).toDouble()).add(side.multiply((rnd.nextDouble() - 0.5) * 2.4)) }, 50)
        })
    }

    /** Erstickt am eigenen Atem: stürzt ab und liegt offen. */
    fun choke(): Script = script {
        val d = f.nivaris ?: return@script
        d.throatGlow = 0
        f.inhaling = false
        f.sound("nivaris.choke", d.entityPos, 3f)
        d.play(NivarisPose.DOWNED)
        val ground = Vec3d(d.x, f.floorY, d.z)
        sinkTo(f.clampToArena(ground, 3.0), 14)
        d.flying = false
        f.sound("nivaris.crash", d.entityPos, 3f)
        Vfx.shockwave(f.world, style, ground, 5f, 14, 1.4f)
        f.onChoke()
        f.openDragon(AriusTimings.CHOKE_STAGGER)
        MusicCue.sting(f, "punish")
        openMusic(true)
        wait(AriusTimings.CHOKE_STAGGER)
        openMusic(false)
        d.play(NivarisPose.IDLE)
    }

    // --- D2: Gletschersturz (seit 1.8.1) -----------------------------------------------------------------------------------

    /**
     * Gletschersturz (Kang-Jin Loong): Sie steigt hoch über den Rand, die Bahn leuchtet auf (5 breit), dann stürzt sie
     * schräg herab, fegt mit dem ganzen Körper dicht über die Bahn und schlägt am Ende auf (Ring). Danach liegt sie
     * benommen am Boden: das Strafefenster. Ersetzt den alten Sturzflug in den Eiskamm (es gibt keine Kämme mehr).
     */
    fun dive(t: ServerPlayerEntity): Script = script {
        val d = f.nivaris ?: return@script
        val away = flatDir(t.entityPos, d.entityPos)
        val start = airPoint(f.clampToArena(t.entityPos.add(away.multiply(24.0)), 12.0), cruise + 10)
        flyTo(start, 0.9, 34, faceTarget = t.entityPos)
        val tt = f.target() ?: t
        val from = Vec3d(d.x, f.floorY, d.z)
        val dir = flatDir(from, tt.entityPos)
        val to = f.clampToArena(tt.entityPos.add(dir.multiply(9.0)), 10.0).let { Vec3d(it.x, f.floorY, it.z) }
        val group = f.calls.boss(style = style)
        val line = group.line(from, to, 5f, DragonTimings.DIVE_WINDUP, Telegraph.Pace.PROJECTILE)
        val slam = group.ring(to, 6.5f, line.ticks + 8)
        d.play(NivarisPose.DIVE, line.ticks)
        f.sound("nivaris.roar", d.entityPos, 4f, 0.85f)
        f.sound("nivaris.wing", d.entityPos, 3f, 0.7f)
        // Ausholen: in der Luft stehen, Schwingen hoch, Blick aufs Ziel.
        val hold = (line.ticks - 12).coerceAtLeast(2)
        repeat(hold) {
            f.face(d, to)
            yield(Unit)
        }
        // Sturz über eine Bahn: weich schräg herab bis dicht über die Mitte, dann flach bis zum Aufprall.
        val top = d.entityPos
        val low = from.lerp(to, 0.45).add(0.0, 2.8, 0.0)
        val end = to.add(0.0, 1.0, 0.0)
        val rail = DragonRail.clearTerrain(
            DragonRail.path(top, low.subtract(top), listOf(low, end), 24.0, if (f.arenaRadius > 40.0) flyCenter() else null, flyKeepRadius()),
            f.origin, f.floorY, clearance = 3.5, landingTail = 22,
        )
        val railLen = DragonRail.length(rail)
        val left = (slam.ticks - line.ticks).coerceIn(6, 14)
        val total = 18 + left
        val hitTick = 16
        for (i in 1..total) {
            val k = i / total.toDouble()
            val before = d.entityPos
            val pos = DragonRail.at(rail, railLen * (k * k * 0.45 + k * 0.55))
            vel = pos.subtract(before)
            f.place(d, pos)
            f.face(d, DragonRail.at(rail, railLen * (k * k * 0.45 + k * 0.55) + 3.0).let { if (it.squaredDistanceTo(pos) > 0.01) it else to.add(dir) })
            if (i <= hitTick && i % 4 == 0) Vfx.shockwave(f.world, style, Vec3d(pos.x, f.floorY, pos.z), 2.5f, 8, 0.6f)
            if (i == hitTick) for (p in line.hits(f.players())) {
                f.hurt(p, 10f, Damage.Kind.IMPACT, d)
                Fx.launchPlayer(p, Vec3d(-dir.z, 0.0, dir.x).multiply(if (rnd.nextBoolean()) 0.8 else -0.8).add(0.0, 0.5, 0.0))
            }
            yield(Unit)
        }
        vel = null
        d.flying = false
        f.place(d, to)
        d.play(NivarisPose.LAND, 1)
        f.sound("nivaris.crash", to, 4f, 0.8f)
        f.calls.roomShake(to, 5f, 36f)
        Vfx.shockwave(f.world, style, to, 7f, 16, 1.6f)
        f.fx(FxKind.CLEAR_WAVE, to, a = 10f, life = 18)
        for (p in slam.hits(f.players())) {
            f.hurt(p, 9f, Damage.Kind.IMPACT, d)
            Fx.launchPlayer(p, flatDir(to, p.entityPos).multiply(0.9).add(0.0, 0.4, 0.0))
        }
        group.end()
        // Benommen: Kopf am Boden, das Strafefenster.
        wait(6)
        d.play(NivarisPose.DOWNED)
        f.hint("hha_arius.hint.slam_open")
        f.openDragon(DragonTimings.DIVE_OPEN)
        wait(DragonTimings.DIVE_OPEN)
        d.play(NivarisPose.IDLE)
    }

    // --- Kang-Jin-Loong-Angriffe (seit 1.5.1) -------------------------------------------------------------------------

    /** Himmelssturz: im weiten Bogen hoch hinauf, über dem Ziel senkrecht herab; Aufprall mit Eisdornenringen, danach offen. */
    fun skyPlunge(t: ServerPlayerEntity): Script = script {
        val d = f.nivaris ?: return@script
        f.sound("nivaris.wing", d.entityPos, 3f, 0.6f)
        // Aufsteigen in einer weiten Spirale (gleicher Drehsinn wie die Runden).
        val c = flyCenter()
        val r = minOf(28.0, flyKeepRadius() - 8.0).coerceAtLeast(DragonRail.MIN_RADIUS + 2.0)
        var a = kotlin.math.atan2(d.z - c.z, d.x - c.x)
        repeat(28) { k ->
            a += circleSign * 0.95 / r
            val target = Vec3d(c.x + kotlin.math.cos(a + circleSign * 0.5) * r, f.floorY + 13.0 + k * 0.65, c.z + kotlin.math.sin(a + circleSign * 0.5) * r)
            steer(d, target, 0.95, 6.0, ceil = f.floorY + 38.0)
            yield(Unit)
        }
        f.sound("nivaris.roar", d.entityPos, 4f, 0.9f)
        val tt = f.target() ?: t
        val spot = f.clampToArena(Vec3d(tt.x, f.floorY, tt.z), 10.0).let { Vec3d(it.x, f.floorY, it.z) }
        val ring = f.calls.ring(spot, 7f, 34, style = style)
        flyTo(spot.add(flatDir(spot, d.entityPos).multiply(8.0)).add(0.0, 28.0, 0.0), 0.95, (ring.ticks - 14).coerceAtLeast(8), faceTarget = spot)
        d.play(NivarisPose.DIVE, 12)
        val from = d.entityPos
        val fall = ring.remaining.coerceAtLeast(12)
        val plunge = DragonRail.clearTerrain(
            DragonRail.path(from, DragonRail.heading(f, d).add(0.0, -0.35, 0.0), listOf(spot.add(0.0, 1.0, 0.0)), 24.0, if (f.arenaRadius > 40.0) flyCenter() else null, flyKeepRadius()),
            f.origin, f.floorY, clearance = 3.0, landingTail = 20,
        )
        val plungeLen = DragonRail.length(plunge)
        for (i in 1..fall) {
            val k = i / fall.toDouble()
            f.place(d, DragonRail.at(plunge, plungeLen * k * k))
            f.face(d, spot.add(flatDir(from, spot)))
            yield(Unit)
        }
        vel = null
        d.flying = false
        for (p in ring.hits(f.players())) {
            f.hurt(p, 12f, Damage.Kind.IMPACT, d)
            Fx.launchPlayer(p, flatDir(spot, p.entityPos).multiply(0.9).add(0.0, 0.45, 0.0))
        }
        f.sound("nivaris.crash", spot, 4f, 0.9f)
        Vfx.shockwave(f.world, style, spot, 10f, 18, 2f)
        f.fx(FxKind.CLEAR_WAVE, spot, a = 14f, life = 20)
        f.calls.roomShake(spot, 6f, 40f)
        d.play(NivarisPose.LAND, 1)
        yieldAll(thornRings(spot, doubleArrayOf(6.0, 11.0)))
        // Der Kopf steckt im Eis: offen.
        d.play(NivarisPose.EXHAUSTED)
        f.openDragon(DragonTimings.PLUNGE_OPEN)
        wait(DragonTimings.PLUNGE_OPEN)
        d.play(NivarisPose.IDLE)
    }

    /**
     * Eisdornen wachsen ringförmig nach außen (Welle für Welle), treffen, wer im Ring steht, und zerspringen nach 2 s.
     * Seit 1.8.1 locker gesetzt und meist nur 1 hoch (vorher dichte Ringe bis 3 hoch: wirkte wie eine Wand).
     */
    private fun thornRings(center: Vec3d, radii: DoubleArray): Script = script {
        val hit = HashSet<java.util.UUID>()
        for (rr in radii) {
            val n = (rr * 1.1).toInt().coerceAtLeast(4)
            spikes((0 until n).map { i ->
                val a = i / n.toDouble() * Math.PI * 2 + rr
                Vec3d(center.x + kotlin.math.cos(a) * rr, f.floorY, center.z + kotlin.math.sin(a) * rr)
            }, 40)
            f.sound("glacier.crack", center, 3f, 1.0f + rr.toFloat() * 0.03f)
            Vfx.shockwave(f.world, style, center, rr.toFloat(), 8, 0.6f)
            for (p in f.players()) {
                val dd = sqrt((p.x - center.x) * (p.x - center.x) + (p.z - center.z) * (p.z - center.z))
                if (kotlin.math.abs(dd - rr) < 1.6 && hit.add(p.uuid)) {
                    f.hurt(p, 5f, Damage.Kind.IMPACT, f.nivaris)
                    Fx.launchPlayer(p, Vec3d(0.0, 0.6, 0.0))
                }
            }
            wait(4)
        }
    }

    /**
     * Hagel (seit 1.8.1, wie Kang-Jin Loongs Blitze, nur aus Eis): Sie zieht weite Runden hoch über dem Horst und
     * brüllt; in vier Wellen leuchten Kreise am Boden auf – auf jedem Spieler einer, dazu Streuung –, und 26 Ticks
     * später schlagen Eisspeere aus dem Himmel ein. Danach ein einzelner kurzer Dorn am Einschlag.
     */
    fun iceVolley(): Script = script {
        val d = f.nivaris ?: return@script
        val c = f.arenaCenter
        val r = minOf(28.0, f.arenaRadius - 6.0)
        var a = kotlin.math.atan2(d.z - c.z, d.x - c.x)
        f.sound("nivaris.roar", d.entityPos, 4f, 0.75f)
        d.play(NivarisPose.ROAR, 10)
        f.fx(FxKind.BLIZZARD, Vec3d(c.x, f.floorY, c.z), a = r.toFloat(), life = 150)
        for (wave in 0 until 3) {
            val group = f.calls.boss(style = style)
            val spots = ArrayList<Vec3d>()
            for (p in f.players()) spots += f.clampToArena(Vec3d(p.x + rnd.nextGaussian() * 1.2, f.floorY, p.z + rnd.nextGaussian() * 1.2), 2.0)
            while (spots.size < 3 + wave) {
                val ang = rnd.nextDouble() * Math.PI * 2
                val rr = 4.0 + rnd.nextDouble() * (r - 6.0)
                spots += Vec3d(c.x + kotlin.math.cos(ang) * rr, f.floorY, c.z + kotlin.math.sin(ang) * rr)
            }
            val rings = spots.map { group.ring(it, 3.2f, 22) to it }
            val ticks = rings.maxOf { it.first.ticks }
            for (s in spots) f.fx(FxKind.ICE_CHUNK, s.add(0.0, 44.0, 0.0), s, a = 2.2f, life = ticks)
            if (wave > 0) d.play(NivarisPose.HOVER)
            repeat(ticks) {
                a += circleSign * 0.78 / r
                val ahead = a + circleSign * 0.55
                steer(d, Vec3d(c.x + kotlin.math.cos(ahead) * r, f.floorY + 18.0, c.z + kotlin.math.sin(ahead) * r), 0.78, 5.5)
                yield(Unit)
            }
            val hit = HashSet<java.util.UUID>()
            for ((ring, at) in rings) {
                for (p in ring.hits(f.players())) if (hit.add(p.uuid)) f.hurt(p, 6f, Damage.Kind.IMPACT, d)
                Vfx.shockwave(f.world, style, at, 3.2f, 10, 1f)
                f.sound("glacier.crack", at, 2.2f, 1.2f + rnd.nextFloat() * 0.2f)
            }
            spikes(spots.filter { rnd.nextBoolean() }, 40)
            group.end()
            wait(2)
        }
    }

    /** Tiefflug: dicht über dem Boden fegt ihr ganzer Körper in gerader Bahn über das Feld (breite Ansage). */
    fun bodySweep(t: ServerPlayerEntity): Script = script {
        val d = f.nivaris ?: return@script
        val dir = flatDir(d.entityPos, t.entityPos).let { if (it.lengthSquared() < 1e-6) Vec3d(0.0, 0.0, 1.0) else it }
        val start = airPoint(f.clampToArena(t.entityPos.subtract(dir.multiply(26.0)), 10.0), 3.5)
        val end = airPoint(f.clampToArena(t.entityPos.add(dir.multiply(26.0)), 10.0), 3.5)
        flyTo(start, 0.95, 32, faceTarget = end, arrive = dir)
        val line = f.calls.line(Vec3d(start.x, f.floorY, start.z), Vec3d(end.x, f.floorY, end.z), 6f, 22, Telegraph.Pace.PROJECTILE, style = style)
        d.play(NivarisPose.GLIDE)
        f.sound("nivaris.roar", d.entityPos, 3f, 1.1f)
        wait((line.ticks - 8).coerceAtLeast(1))
        flyTo(end, 1.20, 32, faceTarget = end.add(dir.multiply(10.0)))
        for (p in line.hits(f.players())) {
            f.hurt(p, 9f, Damage.Kind.IMPACT, d)
            Fx.launchPlayer(p, Vec3d(-dir.z, 0.0, dir.x).multiply(if (rnd.nextBoolean()) 0.8 else -0.8).add(0.0, 0.5, 0.0))
        }
        Vfx.shockwave(f.world, style, Vec3d(end.x, f.floorY, end.z), 5f, 12, 1f)
    }

    /**
     * Winterzorn (seit 1.8.1, Kang-Jin Loongs aufgeladener Donnerschlag, hier aus Frost): Sie schwebt über einem Punkt am
     * Rand, holt lange Luft (Kehle glüht), das ganze Feld wird angesagt – nur im goldenen Auge des Sturms unter ihr ist
     * man sicher. Wer ihr in der Zeit genug an die Kehle schlägt, lässt sie ersticken. Danach sinkt sie erschöpft
     * herab: ein langes Strafefenster.
     */
    fun wintersRoar(): Script = script {
        val d = f.nivaris ?: return@script
        val c = Vec3d(f.arenaCenter.x, f.floorY, f.arenaCenter.z)
        val players = f.players()
        val mid = if (players.isEmpty()) c else players.fold(Vec3d.ZERO) { acc, p -> acc.add(p.entityPos) }.multiply(1.0 / players.size)
        val ang = kotlin.math.atan2(mid.z - c.z, mid.x - c.x) + Math.PI + (rnd.nextDouble() - 0.5) * 1.2
        val rr = minOf(18.0, flyKeepRadius() - 10.0).coerceAtLeast(8.0)
        val eye = f.clampToArena(Vec3d(c.x + kotlin.math.cos(ang) * rr, f.floorY, c.z + kotlin.math.sin(ang) * rr), 10.0).let { Vec3d(it.x, f.floorY, it.z) }
        flyTo(eye.add(flatDir(c, eye).multiply(3.0)).add(0.0, 13.0, 0.0), 0.9, 34, faceTarget = c)
        val ticks = DragonTimings.WINTER_ROAR_CHARGE
        val group = f.calls.boss(style = style)
        val field = group.ring(c, (f.arenaRadius + 4.0).toFloat(), ticks)
        group.safe(eye, 5f, ticks)
        d.play(NivarisPose.INHALE, ticks)
        f.sound("nivaris.inhale", d.entityPos, 4f, 0.6f)
        f.hint("hha_arius.hint.winter_roar")
        f.inhaling = true
        f.chokeDamage = 0f
        val threshold = 36f * (1f + 0.3f * (f.startCount - 1))
        for (i in 0 until field.ticks) {
            d.throatGlow = i * 100 / field.ticks
            f.face(d, c)
            if (i % 12 == 0) f.fx(FxKind.PULL, d.entityPos.add(0.0, 2.0, 0.0), a = 10f, life = 14, e1 = d)
            if (f.chokeDamage >= threshold) {
                f.inhaling = false
                group.end()
                yieldAll(choke())
                return@script
            }
            yield(Unit)
        }
        f.inhaling = false
        d.throatGlow = 100
        d.play(NivarisPose.ROAR, 1)
        f.sound("nivaris.roar", d.entityPos, 6f, 0.6f)
        f.sound("nivaris.breath", c, 5f, 0.6f)
        f.calls.roomShake(c, 7f, 70f)
        f.fxAll(FxKind.FLASH, c, a = 0.35f, life = 8)
        f.fx(FxKind.CLEAR_WAVE, eye, a = f.arenaRadius.toFloat(), life = 26)
        Vfx.nova(f.world, style, c.add(0.0, 0.3, 0.0), f.arenaRadius.toFloat(), 18)
        for (p in field.hits(f.players())) {
            if (group.isSafe(p)) continue
            f.hurt(p, 14f, Damage.Kind.FROST_BREATH, d)
            dev.henny.hha_arius.gear.Frostbite.add(p, 60f)
            Fx.launchPlayer(p, flatDir(eye, p.entityPos).multiply(0.7).add(0.0, 0.35, 0.0))
        }
        group.end()
        d.throatGlow = 0
        wait(10)
        // Erschöpft: sie sinkt neben das Auge herab und liegt offen.
        d.play(NivarisPose.LAND, 18)
        val down = f.clampToArena(eye.add(flatDir(eye, c).multiply(4.0)), 4.0).let { Vec3d(it.x, f.floorY, it.z) }
        sinkTo(down, 18, faceTarget = c)
        d.flying = false
        f.sound("nivaris.land", down, 3.5f, 0.8f)
        Vfx.shockwave(f.world, style, down, 5f, 12, 1f)
        d.play(NivarisPose.EXHAUSTED)
        f.openDragon(DragonTimings.WINTER_ROAR_OPEN)
        wait(DragonTimings.WINTER_ROAR_OPEN)
        d.play(NivarisPose.IDLE)
    }

    // --- Seit 1.8.0: neue Angriffe (Crix: „der Drache braucht mehr coolere Angriffe“) ---------------------------------

    /** Frostbrüllen (Boden): Sie bäumt sich auf und brüllt; eine Frostdruckwelle friert den Ring um sie (Frost, Rückstoß). */
    fun frostRoar(): Script = script {
        val d = f.nivaris ?: return@script
        val c = Vec3d(d.x, f.floorY, d.z)
        val ring = f.calls.ring(c, 11f, 26, inner = 2.5f, style = style)
        d.play(NivarisPose.ROAR, ring.ticks)
        f.sound("nivaris.inhale", d.entityPos, 3f, 0.7f)
        wait(ring.ticks)
        f.sound("nivaris.roar", d.entityPos, 5f, 0.8f)
        f.calls.roomShake(c, 5f, 40f)
        Vfx.shockwave(f.world, style, c, 11f, 18, 2f)
        f.fx(FxKind.CLEAR_WAVE, c, a = 14f, life = 20)
        for (p in ring.hits(f.players())) {
            f.hurt(p, 7f, Damage.Kind.FROST_BREATH, d)
            dev.henny.hha_arius.gear.Frostbite.add(p, 30f)
            Fx.launchPlayer(p, flatDir(c, p.entityPos).multiply(1.0).add(0.0, 0.4, 0.0))
        }
        yieldAll(thornRings(c, doubleArrayOf(7.0)))
        wait(8)
    }

    /** Gletscherwalze (Boden): Sie duckt sich und schießt in gerader Bahn über den Boden auf das Ziel zu. */
    fun glacierCharge(t: ServerPlayerEntity): Script = script {
        val d = f.nivaris ?: return@script
        f.face(d, t.entityPos)
        val from = Vec3d(d.x, f.floorY, d.z)
        val dir = flatDir(from, t.entityPos)
        val to = f.clampToArena(from.add(dir.multiply(24.0)), 3.0).let { Vec3d(it.x, f.floorY, it.z) }
        val line = f.calls.line(from, to, 5f, 24, Telegraph.Pace.HEAVY, style = style)
        d.play(NivarisPose.CLAW, line.ticks)
        f.sound("nivaris.roar", d.entityPos, 3f, 1.15f)
        wait(line.ticks)
        f.sound("nivaris.wing", d.entityPos, 4f, 0.6f)
        for (i in 1..12) {
            val k = i / 12.0
            f.place(d, from.lerp(to, k * (2 - k)))
            f.face(d, to.add(dir))
            if (i % 3 == 0) Vfx.shockwave(f.world, style, Vec3d(d.x, f.floorY, d.z), 3f, 8, 0.8f)
            yield(Unit)
        }
        for (p in line.hits(f.players())) {
            f.hurt(p, 9f, Damage.Kind.IMPACT, d)
            Fx.launchPlayer(p, Vec3d(-dir.z, 0.0, dir.x).multiply(if (rnd.nextBoolean()) 1.0 else -1.0).add(dir.multiply(0.4)).add(0.0, 0.5, 0.0))
        }
        f.sound("nivaris.land", to, 3f, 1.1f)
        f.calls.hitShake(to, 3f, 20f)
        wait(AriusTimings.NIVARIS_RECOVER[NivarisPose.CLAW])
    }

    /**
     * Würgende Windung (Boden, seit 1.8.1): Sie gleitet in einer engen Spirale um das Ziel, ihr Körper legt sich als
     * Ring darum (das vordere Stück über das hintere) und zieht sich zusammen. Innen und außen ist man sicher, der Ring
     * trifft. Danach liegt sie kurz verknotet und offen.
     */
    fun coilCrush(t: ServerPlayerEntity): Script = script {
        val d = f.nivaris ?: return@script
        val c = f.clampToArena(Vec3d(t.x, f.floorY, t.z), 14.0).let { Vec3d(it.x, f.floorY, it.z) }
        val ring = f.calls.ring(c, 12.5f, DragonTimings.COIL_CRUSH_WINDUP, inner = 6f, style = style)
        d.play(NivarisPose.IDLE)
        f.sound("nivaris.tail", d.entityPos, 3f, 0.7f)
        val start = d.entityPos
        var a = kotlin.math.atan2(d.z - c.z, d.x - c.x)
        val sign = if (rnd.nextBoolean()) 1.0 else -1.0
        val total = ring.ticks
        for (i in 0 until total) {
            val k = i / total.toDouble()
            // Seit 1.11.0 eine Spirale von außen nach innen: Die Windungen liegen nebeneinander, nie durcheinander.
            val rr = 11.0 + 9.0 * (1.0 - k)
            a += sign * Math.PI * 2.4 / total
            val target = Vec3d(c.x + kotlin.math.cos(a) * rr, f.floorY, c.z + kotlin.math.sin(a) * rr)
            val pos = if (i < 8) start.lerp(target, (i + 1) / 8.0) else target
            f.place(d, pos)
            f.face(d, Vec3d(c.x + kotlin.math.cos(a + sign * 0.4) * rr, f.floorY, c.z + kotlin.math.sin(a + sign * 0.4) * rr))
            if (i % 6 == 0) Vfx.shockwave(f.world, style, pos, 2f, 6, 0.5f)
            yield(Unit)
        }
        f.sound("nivaris.tail", c, 4f, 0.6f)
        f.sound("glacier.crack", c, 3f, 0.8f)
        f.calls.hitShake(c, 4f, 26f)
        Vfx.shockwave(f.world, style, c, 12.5f, 14, 1.2f)
        for (p in ring.hits(f.players())) {
            f.hurt(p, 9f, Damage.Kind.IMPACT, d)
            Fx.launchPlayer(p, flatDir(c, p.entityPos).multiply(0.8).add(0.0, 0.45, 0.0))
        }
        d.play(NivarisPose.EXHAUSTED)
        f.openDragon(DragonTimings.COIL_CRUSH_OPEN)
        wait(DragonTimings.COIL_CRUSH_OPEN)
        d.play(NivarisPose.IDLE)
    }

    /** Eiskäfig (Luft): Sie kreist über dem Ziel und lässt Eisbrocken in einem Ring um es fallen; die Mitte ist sicher. */
    fun iceCage(t: ServerPlayerEntity): Script = script {
        val d = f.nivaris ?: return@script
        val c = Vec3d(t.x, f.floorY, t.z)
        // Weite Bahn: Enger überschnitte sich der 96-Block-Körper selbst.
        val orbit = DragonRail.MIN_RADIUS + 4.0
        flyTo(airPoint(c.add(orbit, 0.0, 0.0), 16.0), 0.95, 28, faceTarget = c)
        f.sound("nivaris.inhale", d.entityPos, 3f, 1.2f)
        val group = f.calls.boss(style = style)
        group.safe(c, 2.2f, 40)
        val rings = (0 until 9).map { k ->
            val a = k / 9.0 * Math.PI * 2
            val at = c.add(kotlin.math.cos(a) * 5.0, 0.0, kotlin.math.sin(a) * 5.0)
            group.ring(at, 2.4f, 22 + k * 2) to at
        }
        var ang = kotlin.math.atan2(d.z - c.z, d.x - c.x)
        var elapsed = 0
        for ((ring, at) in rings) {
            while (elapsed < ring.ticks) {
                ang += circleSign * 0.82 / orbit
                val ahead = ang + circleSign * 0.5
                steer(d, c.add(kotlin.math.cos(ahead) * orbit, 16.0, kotlin.math.sin(ahead) * orbit), 0.82, 6.0)
                if (elapsed % 3 == 0 && elapsed < 24) f.fx(FxKind.ICE_CHUNK, mouth(), at, a = 1.5f, life = (ring.ticks - elapsed).coerceAtLeast(4))
                elapsed++
                yield(Unit)
            }
            f.sound("glacier.crack", at, 2.5f, 1.3f)
            Vfx.shockwave(f.world, style, at, 2.6f, 8, 0.8f)
            for (p in ring.hits(f.players())) f.hurt(p, 6f, Damage.Kind.IMPACT, d)
        }
        group.end()
        wait(8)
    }

    /** Sturmspirale (Luft): Sie umkreist die Arena, ein Sturmwall schließt sich von außen; nur die Mitte bleibt frei. */
    fun stormSpiral(): Script = script {
        val d = f.nivaris ?: return@script
        val c = Vec3d(f.arenaCenter.x, f.floorY, f.arenaCenter.z)
        val inner = 12f
        val outer = (f.arenaRadius - 1.0).toFloat()
        val ring = f.calls.ring(c, outer, 50, inner = inner, style = style)
        f.sound("nivaris.roar", d.entityPos, 4f, 0.95f)
        f.fx(FxKind.BLIZZARD, c, a = outer, life = 70)
        var ang = kotlin.math.atan2(d.z - c.z, d.x - c.x)
        val r = minOf(flyKeepRadius() - 8.0, 56.0).coerceAtLeast(DragonRail.MIN_RADIUS + 4.0)
        for (i in 0 until ring.ticks) {
            ang += circleSign * 0.95 / r
            val ahead = ang + circleSign * 0.45
            steer(d, c.add(kotlin.math.cos(ahead) * r, 10.0 + kotlin.math.sin(i * 0.16) * 2.0, kotlin.math.sin(ahead) * r), 0.95, 6.5)
            if (i % 12 == 0) f.sound("nivaris.wing", d.entityPos, 3f, 0.8f)
            yield(Unit)
        }
        f.sound("nivaris.breath", c, 4f, 0.7f)
        for (p in ring.hits(f.players())) {
            f.hurt(p, 8f, Damage.Kind.PRESSURE, d)
            Fx.launchPlayer(p, flatDir(p.entityPos, c).multiply(0.9).add(0.0, 0.4, 0.0))
        }
        Vfx.shockwave(f.world, style, c, outer, 20, 1.6f)
    }

    /** Strafefenster im Horst: die Musik duckt sich unter eine Spannungsschicht (nur Abschnitt II, siehe [MusicCue]). */
    private fun openMusic(on: Boolean) {
        if (f.phase != EirholmFight.Phase.GLACIER) return
        MusicCue.play(f, if (on) "glacier_open" else "glacier", if (on) 10 else 30)
    }

    // --- D3: Landung -----------------------------------------------------------------------------------------------------

    fun land(t: ServerPlayerEntity?): Script = script {
        val d = f.nivaris ?: return@script
        if (!d.flying) return@script
        val spot = landingSpotNear(t)
        // Seit 1.11.0 über eine Bahn: Sie setzt weit hinter dem Landeplatz an und gleitet flach und gerade ein.
        val from = flatDir(spot, d.entityPos)
        val keepR = flyKeepRadius()
        val approach = keepInFlight(Vec3d(spot.x, f.floorY + 7.0, spot.z).add(from.multiply(28.0)), 6.0).let { Vec3d(it.x, f.floorY + 7.0, it.z) }
        flyTo(approach, 0.85, 38, arrive = flatDir(approach, spot))
        val ring = f.calls.ring(spot, 6f, AriusTimings.NIVARIS_WINDUP[NivarisPose.LAND], style = style)
        d.play(NivarisPose.LAND, ring.ticks)
        val left = max(18, ring.ticks)
        val start = d.entityPos
        val dir = flatDir(start, spot)
        val glide = DragonRail.clearTerrain(
            DragonRail.path(start, DragonRail.heading(f, d), listOf(spot), DragonRail.MIN_RADIUS, if (f.arenaRadius > 40.0) flyCenter() else null, keepR),
            f.origin, f.floorY, clearance = 3.0, landingTail = 22,
        )
        val glideLen = DragonRail.length(glide)
        for (i in 1..left) {
            val k = i / left.toDouble()
            val pos = DragonRail.at(glide, glideLen * (k * (2 - k)))
            f.place(d, pos)
            f.face(d, DragonRail.at(glide, glideLen * (k * (2 - k)) + 3.0).let { if (it.squaredDistanceTo(pos) > 0.01) it else spot.add(dir.multiply(4.0)) })
            if (i == left - 6) d.flying = false
            yield(Unit)
        }
        vel = null
        d.flying = false
        f.sound("nivaris.land", spot, 3.5f)
        for (p in ring.hits(f.players())) {
            f.hurt(p, 8f, Damage.Kind.IMPACT, d)
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
            dev.henny.hha_arius.arena.ArenaLayout.AERIE_SPOTS.map { f.abs(it) }
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
        val targets = if (only == null) f.players() else f.players().filter { it in only }
        for (p in cone.hits(targets)) f.hurt(p, 8f, Damage.Kind.IMPACT, d)
        Vfx.crescent(f.world, style, apex.add(0.0, 1.0, 0.0), dir, 6f, 4f, 8)
        wait(AriusTimings.NIVARIS_RECOVER[NivarisPose.CLAW])
    }

    fun tail(only: List<ServerPlayerEntity>?): Script = script {
        val d = f.nivaris ?: return@script
        val ring = f.calls.ring(d.entityPos, 9f, AriusTimings.NIVARIS_WINDUP[NivarisPose.TAIL], inner = 3f, players = only, style = style)
        d.play(NivarisPose.TAIL, ring.ticks)
        wait(ring.ticks)
        f.sound("nivaris.tail", d.entityPos, 3f)
        val targets = if (only == null) f.players() else f.players().filter { it in only }
        for (p in ring.hits(targets)) {
            f.hurt(p, 6f, Damage.Kind.IMPACT, d)
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
        while (n++ < 20 && d.squaredDistanceTo(srcPos) > 16.0) {
            f.stepToward(d, srcPos, 0.35)
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
        for (p in line.hits(f.players())) f.hurt(p, 9f, Damage.Kind.IMPACT, d)
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
        flyTo(airPoint(pos, 6.0), 0.75, max(ticks, 28), faceTarget = look)
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

/**
 * Zeiten der Kang-Jin-Angriffe (seit 1.8.1), in Ticks. Die gemeinsamen Pose-Zeiten stehen weiter in [AriusTimings].
 */
object DragonTimings {
    /** Benommen am Boden nach dem Gletschersturz (Strafefenster). */
    const val DIVE_OPEN = 55

    /** Kopf im Eis nach dem Himmelssturz. */
    const val PLUNGE_OPEN = 60

    /** Gletschersturz: Ansage der Bahn bis zum Aufprall (seit 1.10.0 kürzer, vorher 34). */
    const val DIVE_WINDUP = 26

    /** Winterzorn: Luftholen (Ansage über das ganze Feld) und Erschöpfung danach (Laden seit 1.10.0 60 statt 70). */
    const val WINTER_ROAR_CHARGE = 60
    const val WINTER_ROAR_OPEN = 80

    /** Würgende Windung: Umkreisen bis zum Zusammenziehen, danach offen. */
    const val COIL_CRUSH_WINDUP = 40
    const val COIL_CRUSH_OPEN = 30
}

/** Höchstes Flugtempo im Kampf (Blöcke je Tick): groß und ruhig statt hektisch. */
private const val MAX_FLIGHT = 0.92

/** Flugkreis im Horst: Radius und Versatz nach Süden, weg von den Rippen im Norden. */
private const val AERIE_FLIGHT_R = 60.0
private const val AERIE_FLIGHT_SOUTH = 15.0
