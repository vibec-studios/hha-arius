package dev.henny.hha_arius.boss

import dev.henny.hha.logic.Fx
import dev.henny.hha.logic.Vfx
import dev.henny.hha.logic.boss.Cine
import dev.henny.hha.logic.boss.Telegraph
import dev.henny.hha_arius.net.FxKind
import net.minecraft.util.math.Vec3d
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Eine vorberechnete Flugbahn von Solvane über [DragonRail], Tick für Tick abfahrbar. Anders als [DragonRail.fly] kennt
 * sie ihre Ankunftszeiten im Voraus, damit Ansagen und Arius' Schläge genau auf ihren Durchflug fallen.
 */
class RailRun(
    private val f: EirholmFight, private val d: NivarisEntity, waypoints: List<Vec3d>,
    speed: Double = 1.0, radius: Double = DragonRail.MIN_RADIUS,
) {
    /** Ruhigeres, schwereres Flugtempo (höchstens 1,25 Blöcke/Tick) für flüssige Kurven ohne Ruckeln. */
    private val speed: Double = (speed * 0.72).coerceIn(0.75, 1.25)
    private val pts = DragonRail.clearTerrain(
        DragonRail.path(
            d.entityPos, DragonRail.heading(f, d), waypoints, radius,
            if (f.arenaRadius > 40.0) f.arenaCenter else null, (f.arenaRadius - 10.0).coerceAtLeast(32.0),
        ),
        f.origin, f.floorY, clearance = 4.5,
    )
    private val last = pts.lastIndex
    private var s = 0.0

    /** Gesamtdauer in Ticks. */
    val ticks: Int = ceil(last * DragonRail.STEP / speed).toInt().coerceAtLeast(1)

    val done: Boolean get() = s >= last

    /** Ticks vom Start bis zum Bahnpunkt, der [p] am nächsten liegt. */
    fun ticksTo(p: Vec3d): Int {
        var best = 0
        var bestD = Double.MAX_VALUE
        for (i in pts.indices) {
            val q = pts[i].squaredDistanceTo(p)
            if (q < bestD) {
                bestD = q
                best = i
            }
        }
        return ceil(best * DragonRail.STEP / speed).toInt()
    }

    /** Ein Tick Flug; die Blickrichtung folgt der Bahn. */
    fun step() {
        if (done || d.isRemoved || last < 1) return
        s = (s + speed / DragonRail.STEP).coerceAtMost(last.toDouble())
        val i = floor(s).toInt().coerceAtMost(last - 1)
        val pos = pts[i].lerp(pts[i + 1], s - i)
        f.place(d, pos)
        val ahead = pts[(i + 6).coerceAtMost(last)]
        if (ahead.squaredDistanceTo(pos) > 0.01) f.face(d, ahead)
        measure(pos)
    }

    private fun measure(pos: Vec3d) {
        val move = lastPos?.let { pos.subtract(it) }
        lastPos = pos
        if (move == null || move.length() !in 0.2..5.0) {
            lastDir = null
            return
        }
        val len = move.length()
        val dir = move.multiply(1.0 / len)
        lastDir?.let { old ->
            val angle = kotlin.math.acos(old.dotProduct(dir).coerceIn(-1.0, 1.0))
            if (angle > 1e-3) debugTightest = minOf(debugTightest, len / angle)
        }
        lastDir = dir
    }

    companion object {
        /** Für die Sichtprüfung: engster tatsächlich geflogener Kurvenradius, auch über Bahnwechsel hinweg. */
        @JvmField
        var debugTightest = Double.MAX_VALUE
        private var lastPos: Vec3d? = null
        private var lastDir: Vec3d? = null

        fun debugReset() {
            debugTightest = Double.MAX_VALUE
            lastPos = null
            lastDir = null
        }
    }
}

/**
 * Echte Duo-Angriffe von Arius und Solvane (seit 1.9.0). Seit 1.11.0 greift eins ins andere (Spieler: „schlägt dich
 * Arius mit dem Schwert, dann kommt Solvane hinterher und beißt dich, während sie ihn einsammelt, dann fliegt er kurz
 * auf ihr und dann hauen sie zusammen drauf“): Jede Kette endet damit, dass sie ihn aufnimmt und beide gemeinsam
 * zuschlagen ([together]). Jeder Flug läuft über [DragonRail]; Ansagen und goldene Felder bleiben, und jedes Glied lässt
 * sich seitlich ausweichen.
 */
class Duo(private val f: EirholmFight) {

    private val rnd = java.util.Random()

    private fun flatDir(from: Vec3d, to: Vec3d): Vec3d {
        val d = Vec3d(to.x - from.x, 0.0, to.z - from.z)
        return if (d.lengthSquared() < 1e-6) Vec3d(0.0, 0.0, 1.0) else d.normalize()
    }

    private fun floor(p: Vec3d) = Vec3d(p.x, f.floorY, p.z)

    private fun air(p: Vec3d, h: Double) = Vec3d(p.x, f.floorY + h, p.z)

    /** Titelkarte und gemeinsamer Klang: beide glühen, der Faden zwischen ihnen leuchtet. */
    private fun announce(key: String, a: AriusEntity, d: NivarisEntity) {
        for (p in f.audience()) Cine.title(listOf(p), "hha_arius.duo.$key", "hha_arius.duo.$key.sub", 45)
        a.eyeGlow = 100
        d.eyeGlow = 100
        d.resonance = true
        f.fx(FxKind.THREAD, a.entityPos, life = 70, e1 = a, e2 = d)
        f.ambient("lead.switch", a.entityPos, 3f, 0.6f)
        f.ambient("resonance.chime", d.entityPos, 3f, 1.2f)
    }

    /** Nacken der Drachin (Standplatz von Arius beim Ritt). */
    private fun neck(d: NivarisEntity): Vec3d = d.entityPos.add(f.facing(d).multiply(1.2)).add(0.0, NivarisShape.SPINE_UP + 1.0, 0.0)

    private fun ride(a: AriusEntity, d: NivarisEntity) {
        f.place(a, neck(d))
        a.yaw = d.yaw
        a.headYaw = d.yaw
        a.bodyYaw = d.yaw
    }

    /** Fliegt [run] [n] Ticks weiter; Arius reitet mit, wenn [rider] gesetzt ist. */
    private suspend fun SequenceScope<Unit>.fly(run: RailRun, n: Int, d: NivarisEntity, rider: AriusEntity? = null) {
        repeat(n.coerceAtLeast(0)) {
            run.step()
            rider?.let { ride(it, d) }
            yield(Unit)
        }
    }

    private suspend fun SequenceScope<Unit>.flyToEnd(run: RailRun, d: NivarisEntity, rider: AriusEntity? = null) {
        var guard = 0
        while (!run.done && guard++ < 400) {
            run.step()
            rider?.let { ride(it, d) }
            yield(Unit)
        }
    }

    /** Arius springt im Bogen auf ihren Nacken ([ticks] Ticks), während sie weiterfliegt. */
    private suspend fun SequenceScope<Unit>.hopOn(a: AriusEntity, d: NivarisEntity, run: RailRun, ticks: Int = 7) {
        val from = a.entityPos
        a.play(AriusPose.LAUNCH, 4)
        f.ambient("arius.wind_step", from, 2.4f, 1.0f)
        for (i in 1..ticks) {
            run.step()
            val k = i / ticks.toDouble()
            f.place(a, from.lerp(neck(d), k * k * (3 - 2 * k)).add(0.0, sin(k * PI) * 1.4, 0.0))
            yield(Unit)
        }
        ride(a, d)
        a.play(AriusPose.CUT_CHARGE)
    }

    // --- Der gemeinsame Abschluss ---------------------------------------------------------------------------------------

    /**
     * **Gemeinsamer Schlag**: Er reitet auf ihr, sie dreht auf das Ziel ein und stürzt tief über die Stelle. Kurz vorher
     * springt er ab, beide treffen im selben Tick: ihre Bahn und sein Ring. Danach stehen beide offen.
     */
    private suspend fun SequenceScope<Unit>.together(a: AriusEntity, d: NivarisEntity, base: Float = 12f) {
        val t = f.target()
        if (t == null) {
            f.place(a, floor(f.clampToArena(a.entityPos, 2.0)))
            bothOpen(a, 30, 24)
            return
        }
        val spot = floor(f.clampToArena(t.entityPos, 3.0))
        val dir = flatDir(d.entityPos, spot)
        val run = RailRun(f, d, listOf(air(spot.subtract(dir.multiply(30.0)), 12.0), air(spot, 6.0), air(spot.add(dir.multiply(36.0)), 16.0)), 1.5)
        val arrive = run.ticksTo(air(spot, 6.0))
        val lead = arrive.coerceAtLeast(26)
        val delay = lead - arrive
        val group = f.calls.boss(style = Vfx.Style.SOUL)
        val pass = group.line(floor(f.clampToArena(spot.subtract(dir.multiply(14.0)), 1.0)), floor(f.clampToArena(spot.add(dir.multiply(14.0)), 1.0)), 2.6f, lead, Telegraph.Pace.HEAVY)
        val ring = group.ring(spot, 4.6f, pass.ticks, pace = Telegraph.Pace.HEAVY)
        val side = Vec3d(-dir.z, 0.0, dir.x)
        group.safe(f.clampToArena(spot.add(side.multiply(7.5)), 1.5), 2.4f, pass.ticks)
        group.safe(f.clampToArena(spot.subtract(side.multiply(7.5)), 1.5), 2.4f, pass.ticks)
        f.ambient("nivaris.roar", d.entityPos, 4.5f, 1.05f)
        f.ambient("iai.glint", a.entityPos, 3f, 0.7f)
        d.play(NivarisPose.GLIDE)
        a.play(AriusPose.CUT_CHARGE)
        val strikeAt = pass.ticks
        val jumpAt = (strikeAt - 12).coerceAtLeast(1)
        var jumpFrom: Vec3d? = null
        for (i in 1..strikeAt) {
            if (i > delay) run.step()
            if (i < jumpAt) {
                ride(a, d)
            } else {
                val from = jumpFrom ?: a.entityPos.also {
                    jumpFrom = it
                    a.play(AriusPose.STAB_GROUND, strikeAt - jumpAt + 1)
                    d.play(NivarisPose.DIVE)
                    f.ambient("arius.wind_step", it, 2.6f, 0.8f)
                }
                val k = (i - jumpAt + 1) / (strikeAt - jumpAt + 1).toDouble()
                f.place(a, from.lerp(spot, k * k).add(0.0, sin(k * PI) * 1.6, 0.0))
                f.face(a, spot)
            }
            yield(Unit)
        }
        f.place(a, spot)
        f.ambient("arius.stab_ground", spot, 4f, 0.7f)
        f.ambient("nivaris.crash", spot, 4f, 1.1f)
        f.ambient("cut.strike", spot, 3.5f, 0.9f)
        f.calls.roomShake(spot, 5f, 34f)
        Vfx.shockwave(f.world, Vfx.Style.HEAVEN, spot, 7f, 14, 1.8f)
        f.fx(FxKind.CLEAR_WAVE, spot, a = 9f, life = 16)
        for (q in 0 until 12) f.shards.spike(spot.add(cos(q * PI / 6) * 4.2, 0.0, sin(q * PI / 6) * 4.2), 1.6 + rnd.nextDouble(), 40)
        for (p in (pass.hits(f.players()) + ring.hits(f.players())).distinct()) {
            if (group.isSafe(p)) continue
            f.hurt(p, base, Damage.Kind.NULL_CUT)
            Fx.launchPlayer(p, flatDir(spot, p.entityPos).multiply(0.8).add(0.0, 0.5, 0.0))
        }
        flyToEnd(run, d)
        bothOpen(a, 36, 30)
    }

    /** Sie fliegt tief an Arius vorbei und nimmt ihn auf; danach folgt [together]. */
    private suspend fun SequenceScope<Unit>.scoopAndStrike(a: AriusEntity, d: NivarisEntity) {
        val at = floor(a.entityPos)
        val dir = flatDir(d.entityPos, at)
        d.flying = true
        d.play(NivarisPose.GLIDE)
        val run = RailRun(f, d, listOf(air(at.subtract(dir.multiply(26.0)), 8.0), air(at, 4.2), air(at.add(dir.multiply(34.0)), 12.0)), 1.7)
        fly(run, (run.ticksTo(air(at, 4.2)) - 5), d)
        f.ambient("nivaris.wing", d.entityPos, 4f, 0.8f)
        hopOn(a, d, run)
        flyToEnd(run, d, a)
        together(a, d)
    }

    // --- Harmonie: die Kette aus dem Wunsch des Spielers -------------------------------------------------------------

    /**
     * **Harmonie**: Arius schlägt das Ziel mit dem Schwert und wirft es nach vorn. Solvane kommt von hinten genau durch
     * seine Schlagrichtung, beißt in die Bahn und nimmt ihn im selben Moment auf. Er fliegt kurz auf ihrem Nacken, dann
     * schlagen beide gemeinsam zu. Wer nach dem Schwertschlag seitlich aus der Bahn geht, entgeht dem Biss.
     */
    fun harmony(): Script = script {
        val a = f.arius ?: return@script
        val d = f.nivaris ?: return@script
        val t = f.target() ?: return@script
        announce("harmony", a, d)
        a.sheathed = false
        d.flying = true
        d.play(NivarisPose.GLIDE)
        // Er tritt im Windschritt an das Ziel heran.
        val toT = flatDir(a.entityPos, t.entityPos)
        val stand = f.dashEnd(a.entityPos, f.clampToArena(t.entityPos.subtract(toT.multiply(3.4)), 1.5))
        val from = a.entityPos
        a.play(AriusPose.SIDESTEP)
        f.ambient("arius.wind_step", from, 2.2f, 1.1f)
        for (i in 1..5) {
            f.place(a, from.lerp(stand, i / 5.0))
            f.face(a, t.entityPos)
            yield(Unit)
        }
        f.fx(FxKind.DASH_STREAK, from, stand, life = 8)
        val dir = flatDir(stand, t.entityPos)
        val scoop = air(stand, 4.2)
        val run = RailRun(f, d, listOf(air(stand.subtract(dir.multiply(34.0)), 7.0), scoop, air(stand.add(dir.multiply(46.0)), 13.0)), 1.6)
        val arrive = run.ticksTo(scoop)
        // Mindestens so viel Vorlauf, dass Schwerthieb und Biss einzeln lesbar bleiben.
        val biteAt = arrive.coerceAtLeast(44)
        val delay = biteAt - arrive
        val slashAt = biteAt - 18
        f.face(a, t.entityPos)
        val cone = f.calls.cone(stand, dir, 4.8f, 62f, slashAt, Telegraph.Pace.HEAVY)
        a.play(AriusPose.COMBO_1, cone.ticks)
        f.ambient("iai.glint", stand, 2.2f, 1.2f)
        var bite: Telegraph.Line? = null
        val biteTo = floor(f.clampToArena(stand.add(dir.multiply(15.0)), 1.0))
        for (i in 1..biteAt) {
            if (i > delay) run.step()
            if (i == cone.ticks) {
                f.ambient("arius.slash", stand, 2.8f, 1.0f)
                f.fx(FxKind.SLASH_ARC, stand.add(0.0, 1.1, 0.0), dir, a = 4.8f, b = 0.2f, life = 12)
                for (p in cone.hits(f.players())) {
                    f.hurt(p, 9f)
                    Fx.launchPlayer(p, dir.multiply(0.75).add(0.0, 0.32, 0.0))
                }
            }
            // Die Ansage des Bisses beginnt, sobald der Schwerthieb sitzt: Jetzt ist Zeit, zur Seite zu gehen.
            if (bite == null && i >= cone.ticks) {
                bite = f.calls.line(stand, biteTo, 3.0f, (biteAt - i).coerceAtLeast(10), Telegraph.Pace.PROJECTILE, style = Vfx.Style.SOUL)
                f.ambient("nivaris.roar", d.entityPos, 4.5f, 1.2f)
            }
            yield(Unit)
        }
        // Der Biss: Sie schnappt in die Bahn und nimmt Arius im selben Moment mit.
        d.play(NivarisPose.DIVE)
        f.ambient("nivaris.crash", stand, 3.5f, 1.3f)
        f.ambient("nivaris.claw", stand, 3.5f, 0.9f)
        f.calls.hitShake(stand, 3f, 24f)
        bite?.let { line ->
            for (p in line.hits(f.players())) {
                f.hurt(p, 11f, Damage.Kind.IMPACT, d)
                Fx.launchPlayer(p, dir.multiply(0.6).add(0.0, 0.5, 0.0))
            }
        }
        f.fx(FxKind.CLEAR_WAVE, stand.lerp(biteTo, 0.5), a = 5f, life = 14)
        hopOn(a, d, run, 5)
        // Kurzer Ritt.
        flyToEnd(run, d, a)
        together(a, d, 13f)
    }

    // --- Drachenritt ----------------------------------------------------------------------------------------------------

    /**
     * **Drachenritt**: Sie holt ihn ab, er steht auf ihrem Nacken. Zwei Überflüge quer über das Feld: Sie fegt die
     * Mittelbahn, er schlägt zu beiden Seiten Sicheln ab; dazwischen liegen goldene sichere Bahnen. Der zweite Flug kreuzt
     * den ersten. Zum Schluss der gemeinsame Schlag.
     */
    fun dragonRide(): Script = script {
        val a = f.arius ?: return@script
        val d = f.nivaris ?: return@script
        announce("dragon_ride", a, d)
        d.flying = true
        d.play(NivarisPose.GLIDE)
        val at = floor(a.entityPos)
        val toA = flatDir(d.entityPos, at)
        val pick = RailRun(f, d, listOf(air(at.subtract(toA.multiply(24.0)), 8.0), air(at, 4.2), air(at.add(toA.multiply(30.0)), 11.0)), 1.6)
        fly(pick, pick.ticksTo(air(at, 4.2)) - 6, d)
        hopOn(a, d, pick)
        f.ambient("nivaris.roar", d.entityPos, 4f, 1.1f)
        flyToEnd(pick, d, a)
        var lastDir: Vec3d? = null
        for (pass in 0 until 2) {
            val t = f.target() ?: break
            val center = floor(f.clampToArena(t.entityPos, 6.0))
            val dir = lastDir?.let { ld -> Vec3d(-ld.z, 0.0, ld.x).multiply(if (rnd.nextBoolean()) 1.0 else -1.0) } ?: flatDir(d.entityPos, center)
            lastDir = dir
            val side = Vec3d(-dir.z, 0.0, dir.x)
            val reach = (f.arenaRadius - 3.0).coerceIn(10.0, 26.0)
            val start = floor(f.clampToArena(center.subtract(dir.multiply(reach)), 2.0))
            val end = floor(f.clampToArena(center.add(dir.multiply(reach)), 2.0))
            val low = air(center, 5.0)
            val run = RailRun(f, d, listOf(air(center.subtract(dir.multiply(32.0)), 10.0), low, air(center.add(dir.multiply(36.0)), 13.0)), 1.6)
            val arrive = run.ticksTo(low)
            val lead = arrive.coerceAtLeast(if (pass == 0) 34 else 26)
            val delay = lead - arrive
            val group = f.calls.boss(style = Vfx.Style.SOUL)
            val main = group.line(start, end, 4.2f, lead, Telegraph.Pace.PROJECTILE)
            val left = group.line(start.add(side.multiply(8.0)), end.add(side.multiply(8.0)), 2.6f, main.ticks + 4, Telegraph.Pace.FOLLOW_UP)
            val right = group.line(start.subtract(side.multiply(8.0)), end.subtract(side.multiply(8.0)), 2.6f, main.ticks + 4, Telegraph.Pace.FOLLOW_UP)
            group.safeLine(start.add(side.multiply(4.8)), end.add(side.multiply(4.8)), 2.0f, main.ticks + 4)
            group.safeLine(start.subtract(side.multiply(4.8)), end.subtract(side.multiply(4.8)), 2.0f, main.ticks + 4)
            d.play(NivarisPose.GLIDE)
            a.play(AriusPose.CUT_CHARGE)
            for (i in 1..main.ticks + 4) {
                if (i > delay) run.step()
                ride(a, d)
                if (i == main.ticks) {
                    d.play(NivarisPose.DIVE)
                    f.ambient("nivaris.roar", d.entityPos, 5f, 0.95f)
                    f.ambient("nivaris.crash", center, 3f, 1.3f)
                    for (p in main.hits(f.players())) {
                        f.hurt(p, 11f, Damage.Kind.IMPACT, d)
                        val s = if ((p.x - start.x) * side.x + (p.z - start.z) * side.z > 0) 1.0 else -1.0
                        Fx.launchPlayer(p, side.multiply(0.9 * s).add(0.0, 0.5, 0.0))
                    }
                    for (q in 1..3) f.fx(FxKind.CLEAR_WAVE, start.lerp(end, q / 4.0), a = 5f, life = 16)
                }
                if (i == main.ticks + 4) {
                    a.play(AriusPose.SPIN_CUT, 1)
                    f.ambient("arius.slash", a.entityPos, 3.5f, 0.9f)
                    f.ambient("cut.strike", a.entityPos, 2.5f, 1.4f)
                    for (sd in listOf(side, side.multiply(-1.0))) {
                        f.fx(FxKind.SLASH_ARC, a.entityPos.add(0.0, 0.5, 0.0), sd, a = 8.5f, b = 0.15f, life = 14)
                        Vfx.crescent(f.world, Vfx.Style.HEAVEN, center.add(sd.multiply(8.0)).add(0.0, 0.6, 0.0), dir, reach.toFloat() * 2f, 2.2f, 10)
                    }
                    for (p in (left.hits(f.players()) + right.hits(f.players())).distinct()) f.hurt(p, 9f)
                }
                yield(Unit)
            }
            a.play(AriusPose.CUT_CHARGE)
            flyToEnd(run, d, a)
        }
        together(a, d)
    }

    // --- Kreuzschnitt ---------------------------------------------------------------------------------------------------

    /**
     * **Kreuzschnitt**: eine Zange von zwei Seiten. Sie überfliegt das Ziel längs, er schneidet im selben Tick quer: ein X
     * durch das Ziel, kurz danach bricht die Mitte auf. Sicher sind die vier goldenen Felder zwischen den Armen. Die zweite
     * Runde ist um 45° gedreht, danach nimmt sie ihn auf.
     */
    fun crossCut(): Script = script {
        val a = f.arius ?: return@script
        val d = f.nivaris ?: return@script
        announce("cross_cut", a, d)
        var ang = rnd.nextDouble() * PI
        d.flying = true
        for (round in 0 until 2) {
            val t = f.target() ?: break
            val center = floor(f.clampToArena(t.entityPos, 9.0))
            var axisA = Vec3d(sin(ang), 0.0, cos(ang))
            // Sie fliegt in die Richtung, in die sie ohnehin zeigt: keine Wende auf der Stelle.
            if (axisA.dotProduct(flatDir(d.entityPos, center)) < 0) axisA = axisA.multiply(-1.0)
            val axisB = Vec3d(-axisA.z, 0.0, axisA.x)
            val aFrom = f.dashEnd(center, f.clampToArena(center.subtract(axisB.multiply(13.0)), 1.0))
            val aTo = f.dashEnd(aFrom, f.clampToArena(center.add(axisB.multiply(13.0)), 1.0))
            val dFrom = floor(f.clampToArena(center.subtract(axisA.multiply(18.0)), 1.5))
            val dTo = floor(f.clampToArena(center.add(axisA.multiply(18.0)), 1.5))
            val low = air(center, 5.0)
            val run = RailRun(f, d, listOf(air(center.subtract(axisA.multiply(32.0)), 9.0), low, air(center.add(axisA.multiply(36.0)), 12.0)), 1.6)
            val arrive = run.ticksTo(low)
            val lead = arrive.coerceAtLeast(if (round == 0) 34 else 26)
            val delay = lead - arrive
            val aStart = a.entityPos
            a.play(AriusPose.LUNGE, lead)
            f.ambient("arius.wind_step", aStart, 2.4f, 1.1f)
            f.fx(FxKind.DASH_STREAK, aStart, aFrom, life = 12)
            d.play(NivarisPose.GLIDE)
            val group = f.calls.boss(style = Vfx.Style.SOUL)
            val charge = group.line(dFrom, dTo, 4.2f, lead, Telegraph.Pace.HEAVY)
            val cut = group.line(aFrom, aTo, 2.6f, charge.ticks, Telegraph.Pace.HEAVY)
            val core = group.ring(center, 3.4f, charge.ticks + 14, pace = Telegraph.Pace.AREA)
            for (qa in listOf(1.0, -1.0)) for (qb in listOf(1.0, -1.0)) {
                group.safe(center.add(axisA.multiply(qa * 6.5)).add(axisB.multiply(qb * 6.5)), 2.6f, charge.ticks + 14)
            }
            f.ambient("nivaris.inhale", d.entityPos, 3f, 1.1f)
            f.ambient("cut.charge", a.entityPos, 3f, 1.2f)
            for (i in 1..charge.ticks) {
                if (i > delay) run.step()
                if (i <= 6) f.place(a, aStart.lerp(aFrom, i / 6.0))
                f.face(a, center)
                if (i == 7) a.play(AriusPose.CUT_CHARGE)
                yield(Unit)
            }
            // Gleichzeitig: sie fegt, er schneidet.
            f.ambient("nivaris.roar", d.entityPos, 5f, 1.0f)
            f.ambient("cut.strike", a.entityPos, 4f, 1.15f)
            a.play(AriusPose.IAI_DRAW, 1)
            for (p in charge.hits(f.players())) {
                f.hurt(p, 11f, Damage.Kind.IMPACT, d)
                Fx.launchPlayer(p, axisB.multiply(if ((p.x - center.x) * axisB.x + (p.z - center.z) * axisB.z > 0) 0.8 else -0.8).add(0.0, 0.45, 0.0))
            }
            for (p in cut.hits(f.players())) f.hurt(p, 12f, Damage.Kind.NULL_CUT)
            f.fx(FxKind.SEAM, aFrom.add(0.0, 0.2, 0.0), aTo.add(0.0, 0.2, 0.0), a = 2f, life = 24)
            f.fx(FxKind.SEAM, dFrom.add(0.0, 0.2, 0.0), dTo.add(0.0, 0.2, 0.0), a = 3f, life = 24)
            f.fx(FxKind.DASH_STREAK, aFrom, aTo, life = 12)
            for (i in 1..10) {
                run.step()
                if (i <= 5) f.place(a, aFrom.lerp(aTo, i / 5.0))
                if (i == 5) {
                    Vfx.flare(f.world, Vfx.Style.HEAVEN, center.add(0.0, 1.5, 0.0), 3f, 8)
                    f.fx(FxKind.SLASH_ARC, center.add(0.0, 1.2, 0.0), axisB, a = 9f, b = 0f, life = 14)
                    f.fx(FxKind.SLASH_ARC, center.add(0.0, 1.0, 0.0), axisA, a = 9f, b = 0.2f, life = 14)
                    f.calls.roomShake(center, 4f, 30f)
                }
                yield(Unit)
            }
            f.face(a, center)
            fly(run, core.remaining.coerceAtLeast(0), d)
            f.ambient("core.collapse", center, 4f, 1.3f)
            Vfx.nova(f.world, Vfx.Style.HEAVEN, center.add(0.0, 0.3, 0.0), 3.8f, 12)
            f.fx(FxKind.CLEAR_WAVE, center, a = 7f, life = 14)
            for (p in core.hits(f.players())) {
                f.hurt(p, 10f, Damage.Kind.IMPACT)
                Fx.launchPlayer(p, flatDir(center, p.entityPos).multiply(0.6).add(0.0, 0.5, 0.0))
            }
            ang += PI / 4
            flyToEnd(run, d)
        }
        scoopAndStrike(a, d)
    }

    // --- Auswahl --------------------------------------------------------------------------------------------------------

    private var lastPick = -1
    private val order = ArrayList<Int>()

    /** Nächste Kette aus einer gemischten Folge (keine Wiederholung direkt hintereinander). */
    fun next(): Script {
        if (order.isEmpty()) {
            order.addAll(listOf(0, 1, 2, 3, 4, 5).shuffled(rnd))
            // Die Kette, die der Spieler sich gewünscht hat, kommt in jeder Runde zuerst.
            order.remove(5)
            order.add(0, 5)
            if (order.first() == lastPick) order.add(order.removeAt(0))
        }
        val pick = order.removeAt(0)
        lastPick = pick
        return when (pick) {
            0 -> dragonRide()
            1 -> twinFang()
            2 -> crossCut()
            3 -> galeCleave()
            4 -> frostPrison()
            else -> harmony()
        }
    }

    /** Beide offen; Solvane landet dabei (Arius' Fenster läuft schon, keine tote Zeit). */
    private suspend fun SequenceScope<Unit>.bothOpen(a: AriusEntity, ariusOpen: Int, dragonOpen: Int) {
        a.play(AriusPose.STAGGER)
        f.openArius(ariusOpen)
        val start = f.now
        yieldAll(f.dragon.land(f.target()))
        f.openDragon(dragonOpen)
        wait((dragonOpen - (f.now - start).toInt()).coerceIn(8, dragonOpen))
    }

    // --- Zwillingsfang --------------------------------------------------------------------------------------------------

    /**
     * **Zwillingsfang**: Arius zieht im Iai-Zug eine Bahn durch das Ziel; sobald er durch ist, setzt Solvane ihren Überflug
     * auf die Stelle, an der das Ziel JETZT steht. Wer ihm ausweicht, muss sofort ein zweites Mal ausweichen. Nach zwei
     * Runden nimmt sie ihn auf.
     */
    fun twinFang(): Script = script {
        val a = f.arius ?: return@script
        val d = f.nivaris ?: return@script
        announce("twin_fang", a, d)
        d.flying = true
        d.play(NivarisPose.GLIDE)
        for (round in 0 until 2) {
            val t = f.target() ?: break
            val lead = intArrayOf(20, 15)[round]
            val toT = flatDir(a.entityPos, t.entityPos)
            val from = floor(a.entityPos)
            val to = f.dashEnd(from, f.clampToArena(t.entityPos.add(toT.multiply(5.0)), 1.0))
            val cut = f.calls.line(from, to, 1.9f, lead, Telegraph.Pace.HEAVY)
            a.sheathed = false
            a.play(AriusPose.IAI_STANCE)
            f.ambient("iai.glint", from, 2.4f, 1.0f + round * 0.15f)
            wait(cut.ticks)
            a.play(AriusPose.IAI_DRAW, 1)
            f.ambient("iai.draw", from, 3f, 1.1f)
            val struck = cut.hits(f.players())
            for (i in 1..4) {
                f.place(a, from.lerp(to, i / 4.0))
                yield(Unit)
            }
            f.fx(FxKind.DASH_STREAK, from, to, life = 10)
            f.fx(FxKind.SEAM, from.add(0.0, 1.0, 0.0), to.add(0.0, 1.0, 0.0), a = 1.2f, life = 12)
            for (p in struck) f.hurt(p, 10f, Damage.Kind.NULL_CUT)
            // Ihr Überflug zielt dorthin, wo das Ziel jetzt steht.
            val now = f.target() ?: t
            val aim = floor(f.clampToArena(now.entityPos, 2.0))
            val dir = flatDir(d.entityPos, aim)
            val low = air(aim, 5.0)
            val run = RailRun(f, d, listOf(air(aim.subtract(dir.multiply(26.0)), 8.0), low, air(aim.add(dir.multiply(34.0)), 12.0)), 1.8)
            val arrive = run.ticksTo(low)
            val fangLead = arrive.coerceAtLeast(16)
            val delay = fangLead - arrive
            val fang = f.calls.line(floor(f.clampToArena(aim.subtract(dir.multiply(11.0)), 1.5)), floor(f.clampToArena(aim.add(dir.multiply(11.0)), 1.5)), 3.2f, fangLead, Telegraph.Pace.PROJECTILE, style = Vfx.Style.SOUL)
            f.ambient("nivaris.roar", d.entityPos, 4f, 1.15f + round * 0.1f)
            f.face(a, now.entityPos)
            a.play(AriusPose.IAI_STANCE)
            for (i in 1..fang.ticks) {
                if (i > delay) run.step()
                yield(Unit)
            }
            d.play(NivarisPose.DIVE)
            f.ambient("nivaris.crash", aim, 3f, 1.3f)
            for (p in fang.hits(f.players())) {
                f.hurt(p, 10f, Damage.Kind.IMPACT, d)
                Fx.launchPlayer(p, Vec3d(-dir.z, 0.0, dir.x).multiply(0.7).add(0.0, 0.45, 0.0))
            }
            f.fx(FxKind.CLEAR_WAVE, aim, a = 4f, life = 12)
            flyToEnd(run, d)
            d.play(NivarisPose.GLIDE)
        }
        scoopAndStrike(a, d)
    }

    // --- Sturmspalter ---------------------------------------------------------------------------------------------------

    /**
     * **Sturmspalter**: Solvane landet vor dem Ziel und schlägt mit beiden Schwingen einen Sturm nach vorn. Hinter dem Ziel
     * hat sich Arius quer aufgestellt und spaltet genau die Linie, in die der Sturm die Getroffenen wirft. Wer seitlich aus
     * dem Kegel geht, entgeht beidem. Danach hebt sie ab und holt ihn zum gemeinsamen Schlag.
     */
    fun galeCleave(): Script = script {
        val a = f.arius ?: return@script
        val d = f.nivaris ?: return@script
        val t = f.target() ?: return@script
        announce("gale_cleave", a, d)
        val center = floor(f.clampToArena(t.entityPos, 8.0))
        val axis = flatDir(f.arenaCenter, center).let { if (it.lengthSquared() < 0.5) Vec3d(0.0, 0.0, 1.0) else it }
        val dSpot = floor(f.clampToArena(center.subtract(axis.multiply(7.0)), 2.0))
        val lineMid = floor(f.clampToArena(center.add(axis.multiply(7.0)), 2.0))
        val across = Vec3d(-axis.z, 0.0, axis.x)
        val aSpot = f.dashEnd(lineMid, f.clampToArena(lineMid.add(across.multiply(9.0)), 1.0))
        val lineFrom = aSpot
        val lineTo = floor(f.clampToArena(lineMid.subtract(across.multiply(9.0)), 1.0))
        // Anflug über die Bahn; das letzte Stück schwebt sie auf den Boden.
        d.flying = true
        d.play(NivarisPose.GLIDE)
        val hover = air(dSpot, 5.0)
        val run = RailRun(f, d, listOf(air(dSpot.subtract(axis.multiply(28.0)), 10.0), hover), 1.6)
        val aFrom = a.entityPos
        a.play(AriusPose.LUNGE, 8)
        f.ambient("arius.wind_step", aFrom, 2.2f, 1.1f)
        f.fx(FxKind.DASH_STREAK, aFrom, aSpot, life = 10)
        var n = 0
        while (!run.done && n < 200) {
            run.step()
            n++
            if (n <= 6) f.place(a, aFrom.lerp(aSpot, n / 6.0))
            f.face(a, lineTo)
            yield(Unit)
        }
        f.place(a, aSpot)
        val group = f.calls.boss(style = Vfx.Style.SOUL)
        val gust = group.cone(dSpot, axis, 13f, 70f, 26, Telegraph.Pace.HEAVY)
        val cleave = group.line(lineFrom, lineTo, 3.4f, gust.ticks + 10, Telegraph.Pace.HEAVY)
        group.safe(center.add(across.multiply(8.5)), 2.4f, gust.ticks + 10)
        group.safe(center.subtract(across.multiply(8.5)), 2.4f, gust.ticks + 10)
        val top = d.entityPos
        for (i in 1..10) {
            val k = (i / 10.0).let { it * it * (3 - 2 * it) }
            f.place(d, top.lerp(dSpot, k))
            f.face(d, center)
            yield(Unit)
        }
        f.place(d, dSpot)
        d.flying = false
        d.play(NivarisPose.WING_GUST, gust.remaining.coerceAtLeast(1))
        a.play(AriusPose.CUT_CHARGE)
        f.ambient("cut.charge", aSpot, 2.6f, 1.2f)
        f.ambient("nivaris.inhale", dSpot, 3f, 1.3f)
        wait(gust.remaining.coerceAtLeast(0))
        f.ambient("nivaris.wing", dSpot, 5f, 0.7f)
        f.fx(FxKind.GALE, dSpot.add(0.0, 1.0, 0.0), axis.multiply(13.0), a = 1f, life = 14)
        Vfx.shockwave(f.world, Vfx.Style.STORM, dSpot.add(axis.multiply(3.0)), 6f, 12, 1.2f)
        for (p in gust.hits(f.players())) {
            f.hurt(p, 5f, Damage.Kind.PRESSURE, d)
            Fx.launchPlayer(p, axis.multiply(1.25).add(0.0, 0.35, 0.0))
        }
        wait((cleave.remaining - 6).coerceAtLeast(0))
        a.play(AriusPose.CUT_RELEASE, 6)
        wait(cleave.remaining.coerceAtLeast(0))
        f.ambient("cut.strike", lineMid, 3.5f, 1.1f)
        f.ambient("arius.stab_ground", lineMid, 2.5f, 0.7f)
        f.calls.roomShake(lineMid, 4f, 30f)
        f.fx(FxKind.SEAM, lineFrom.add(0.0, 0.15, 0.0), lineTo.add(0.0, 0.15, 0.0), a = 3f, life = 20)
        for (q in 1..4) f.fx(FxKind.CLEAR_WAVE, lineFrom.lerp(lineTo, q / 5.0), a = 2.5f, life = 12)
        for (p in cleave.hits(f.players())) if (!group.isSafe(p)) {
            f.hurt(p, 13f, Damage.Kind.IMPACT)
            dev.henny.hha_arius.gear.Frostbite.add(p, 15f)
        }
        // Sie hebt ab, zieht eine weite Schleife und nimmt ihn auf.
        d.flying = true
        d.play(NivarisPose.TAKEOFF, 8)
        f.ambient("nivaris.wing", dSpot, 4f, 0.8f)
        val climb = RailRun(f, d, listOf(air(dSpot.add(axis.multiply(30.0)), 12.0)), 1.3)
        flyToEnd(climb, d)
        scoopAndStrike(a, d)
    }

    // --- Frostkerker ----------------------------------------------------------------------------------------------------

    /**
     * **Frostkerker**: Solvane zieht in einem weiten Bogen um das Ziel und legt mit ihrem Atem einen Frostring (angesagtes
     * Band, eine goldene Lücke). Im Kerker zieht Arius drei Iai-Züge auf die Eingeschlossenen. Raus kommt man schadlos nur
     * durch die Lücke, oder man stellt sich ihm. Zum Schluss holt sie ihn ab.
     */
    fun frostPrison(): Script = script {
        val a = f.arius ?: return@script
        val d = f.nivaris ?: return@script
        val t = f.target() ?: return@script
        announce("frost_prison", a, d)
        val center = floor(f.clampToArena(t.entityPos, 11.0))
        val inner = 7.5f
        val outer = 10.0f
        val gapAng = rnd.nextDouble() * 2 * PI
        val gap = f.ringPoint(center, ((inner + outer) / 2).toDouble(), gapAng)
        d.flying = true
        d.play(NivarisPose.GLIDE)
        // Ein einziger weiter Bogen (Radius über dem Mindestkurvenradius), keine Kreise auf der Stelle.
        val orbit = (DragonRail.MIN_RADIUS + 5.0)
        val waypoints = (0..5).map { k -> air(f.ringPoint(center, orbit, gapAng + 0.4 + k * 2 * PI * 0.16), 7.0) }
        val run = RailRun(f, d, waypoints, 2.4)
        val group = f.calls.boss(style = Vfx.Style.SOUL)
        val band = group.ring(center, outer, run.ticks.coerceIn(34, 90), inner = inner, pace = Telegraph.Pace.AREA)
        group.safe(gap, 2.4f, band.ticks + 2)
        d.play(NivarisPose.BREATH)
        f.ambient("nivaris.inhale", d.entityPos, 3f, 1.0f)
        val aFrom = a.entityPos
        val inside = f.clampToArena(center.add(flatDir(center, aFrom).multiply(3.0)), 1.0)
        a.play(AriusPose.LAUNCH, 6)
        for (i in 1..band.ticks) {
            run.step()
            if (i % 3 == 0) {
                val toward = flatDir(center, d.entityPos)
                Vfx.beam(f.world, Vfx.Style.SOUL, d.entityPos.add(0.0, 2.0, 0.0), center.add(toward.multiply(((inner + outer) / 2).toDouble())), 1f, 6)
            }
            if (i <= 10) f.place(a, aFrom.lerp(inside, i / 10.0).add(0.0, sin(i / 10.0 * PI) * 2.0, 0.0))
            yield(Unit)
        }
        f.ambient("nivaris.breath", center, 4f, 1.1f)
        f.ambient("arius.ice_wall", center, 3f, 1.0f)
        for (p in band.hits(f.players())) if (!group.isSafe(p)) {
            f.hurt(p, 9f, Damage.Kind.FROST_BREATH, d)
            Fx.launchPlayer(p, flatDir(center, p.entityPos).multiply(-0.4).add(0.0, 0.3, 0.0))
        }
        for (q in 0 until 18) {
            val ang = q / 18.0 * 2 * PI
            var delta = ang - gapAng
            while (delta > PI) delta -= 2 * PI
            while (delta < -PI) delta += 2 * PI
            if (kotlin.math.abs(delta) < 0.32) continue
            f.shards.spike(f.ringPoint(center, ((inner + outer) / 2).toDouble(), ang), 1.4 + rnd.nextDouble() * 0.8, 110)
        }
        // Das Band bleibt eine Weile kalt (Frost beim Durchqueren, außer in der Lücke).
        f.task(script {
            repeat(11) {
                wait(10)
                for (p in f.players()) {
                    val r = sqrt((p.x - center.x) * (p.x - center.x) + (p.z - center.z) * (p.z - center.z))
                    if (r < inner || r > outer || p.squaredDistanceTo(gap) < 2.4 * 2.4) continue
                    f.hurt(p, 2f, Damage.Kind.FROST_BREATH, f.nivaris)
                    dev.henny.hha_arius.gear.Frostbite.add(p, 6f)
                }
            }
        })
        d.play(NivarisPose.GLIDE)
        for (k in 0 until 3) {
            val inPrison = f.players().filter { sqrt(it.squaredDistanceTo(center.x, it.y, center.z)) < outer }
            val v = (if (inPrison.isEmpty()) null else inPrison[rnd.nextInt(inPrison.size)]) ?: f.target() ?: break
            val from = floor(a.entityPos)
            val dir = flatDir(from, v.entityPos)
            val to = f.dashEnd(from, f.clampToArena(v.entityPos.add(dir.multiply(3.5)), 1.0))
            val line = f.calls.line(from, to, 1.8f, if (k == 0) 16 else 12, Telegraph.Pace.FOLLOW_UP)
            f.face(a, v.entityPos)
            a.play(AriusPose.IAI_STANCE)
            f.ambient("iai.glint", from, 2f, 1.2f + k * 0.1f)
            fly(run, line.ticks, d)
            a.play(AriusPose.IAI_DRAW, 1)
            f.ambient("iai.draw", from, 2.6f)
            val struck = line.hits(f.players())
            for (i in 1..4) {
                run.step()
                f.place(a, from.lerp(to, i / 4.0))
                yield(Unit)
            }
            f.fx(FxKind.DASH_STREAK, from, to, life = 10)
            for (p in struck) f.hurt(p, 10f, Damage.Kind.NULL_CUT)
            fly(run, 3, d)
        }
        flyToEnd(run, d)
        scoopAndStrike(a, d)
    }
}
