package dev.henny.hha_arius.boss

import dev.henny.hha.logic.Fx
import dev.henny.hha.logic.Vfx
import dev.henny.hha.logic.boss.Cine
import dev.henny.hha.logic.boss.Telegraph
import dev.henny.hha_arius.arena.ArenaLayout
import dev.henny.hha_arius.arena.FarPlan
import dev.henny.hha_arius.net.AtmosMode
import dev.henny.hha_arius.net.FxKind
import net.minecraft.entity.effect.StatusEffectInstance
import net.minecraft.entity.effect.StatusEffects
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import net.minecraft.util.math.Vec3d
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Die Jagd (Zorn des Erben), seit 1.9.0 eine echte Verfolgung (Crix: „er teleportiert sich nur in dich rein“):
 * * Die Spieler fliehen über die Route ([FarPlan.huntWaypoints]: Wege, Kletterstufen, Brücken) den Berg hinauf. Der
 *   Sturm hält sie darauf; wer abseits klettert oder abstürzt, wird auf die Route zurückgeworfen.
 * * Arius **läuft** ihnen nach (schneller als Sprinten, er holt auf), **springt** über Hindernisse und, wenn er zu weit
 *   zurückliegt, quer über die Flanke auf die Windung, auf der sie sind. Kein Teleportieren.
 * * Nah: schnelle Kombo. Fern: Sichelwelle die Route hinauf, Eisspeere, Lawine aus der Flanke (er springt dafür über
 *   die Spieler an den Hang), Sturmstoß über Brücken. Selten der gefrorene Stern.
 * * Solvane begleitet die Route außen und speit Frost über den Weg vor dem Vordersten.
 * Die Jagd endet, wenn jemand das Tor zur Gipfelarena erreicht ([arrive]).
 */
class Hunt(private val f: EirholmFight) {

    private val rnd = java.util.Random()

    /** Polylinie der Route (absolut). */
    private class Route(val pts: List<Vec3d>) {
        val cum = DoubleArray(pts.size)

        init {
            for (i in 1 until pts.size) cum[i] = cum[i - 1] + pts[i].distanceTo(pts[i - 1])
        }

        val length: Double get() = cum.last()

        private fun seg(s: Double): Int {
            var lo = 0
            var hi = pts.size - 1
            while (hi - lo > 1) {
                val mid = (lo + hi) / 2
                if (cum[mid] <= s) lo = mid else hi = mid
            }
            return lo
        }

        fun at(s: Double): Vec3d {
            if (pts.size == 1) return pts[0]
            val c = s.coerceIn(0.0, length)
            val i = seg(c).coerceAtMost(pts.size - 2)
            val len = (cum[i + 1] - cum[i]).coerceAtLeast(1e-6)
            return pts[i].lerp(pts[i + 1], ((c - cum[i]) / len).coerceIn(0.0, 1.0))
        }

        /** Waagerechte Laufrichtung bei [s] (bergauf). */
        fun dir(s: Double): Vec3d {
            val d = at(s + 3.0).subtract(at(s - 3.0))
            val flat = Vec3d(d.x, 0.0, d.z)
            return if (flat.lengthSquared() < 1e-6) Vec3d(0.0, 0.0, 1.0) else flat.normalize()
        }

        /** Nächster Punkt der Route zu [p]: Weg [s] und Abstand (Höhe doppelt gewichtet: die Windungen liegen übereinander). */
        fun project(p: Vec3d): Pair<Double, Double> {
            var bestS = 0.0
            var best = Double.MAX_VALUE
            for (i in 0 until pts.size - 1) {
                val a = pts[i]
                val b = pts[i + 1]
                val v = b.subtract(a)
                val len2 = v.lengthSquared()
                val t = if (len2 < 1e-9) 0.0 else (p.subtract(a).dotProduct(v) / len2).coerceIn(0.0, 1.0)
                val q = a.add(v.multiply(t))
                val dx = p.x - q.x
                val dy = (p.y - q.y) * 2.0
                val dz = p.z - q.z
                val d2 = dx * dx + dy * dy + dz * dz
                if (d2 < best) {
                    best = d2
                    bestS = cum[i] + sqrt(len2) * t
                }
            }
            return bestS to sqrt(best)
        }
    }

    private lateinit var route: Route

    /** Weg, den Arius auf der Route zurückgelegt hat. */
    private var aS = 0.0
    private var busy = false
    private var dragonBusy = false
    private var nextAttack = 0L
    private var nextDragon = 0L
    private var nextStar = 0L
    private var nextLeap = 0L
    private var lastRanged = -1
    private var orbit = 0.0

    /** Wer zuerst am Tor ankam (für die Ankunftsszene). */
    var arrivedFirst: ServerPlayerEntity? = null
        private set

    private fun buildRoute() {
        val pts = ArrayList<Vec3d>()
        // Vom Feld (Abschnitt III) zum Fuß der Route, dann die Route selbst.
        pts += Vec3d(f.abs(Vec3d.of(ArenaLayout.VAULT)).x + 0.5, f.floorY, f.abs(Vec3d.of(ArenaLayout.VAULT)).z + 0.5)
        for (w in FarPlan.huntWaypoints()) pts += f.abs(Vec3d(w.x + 0.5, w.y.toDouble(), w.z + 0.5))
        route = Route(pts.distinct().ifEmpty { listOf(f.summit) })
    }

    /** Endpunkt der Route: das Tor zur Gipfelarena. */
    val gate: Vec3d get() = route.at(route.length)

    // --- Hilfen -----------------------------------------------------------------------------------------------------

    private fun flatDir(from: Vec3d, to: Vec3d): Vec3d {
        val d = Vec3d(to.x - from.x, 0.0, to.z - from.z)
        return if (d.lengthSquared() < 1e-6) Vec3d(0.0, 0.0, 1.0) else d.normalize()
    }

    private fun cue(id: String, at: Vec3d, volume: Float = 3f, pitch: Float = 1f) {
        if (!Cine.muted) f.ambient(id, at, volume, pitch)
    }

    /** Fester Schaden in Herzen × 2, unabhängig von Rüstung (wie seit 1.7.0 in der Jagd). */
    private fun exactHit(p: ServerPlayerEntity, amount: Float, kind: Damage.Kind = Damage.Kind.SOLVANE) {
        val a = f.arius ?: return
        val red = Damage.reduction(p)
        Damage.hit(p, a, kind, (amount / (1.0 - red).coerceAtLeast(0.05)).toFloat(), 1f)
    }

    /** Nur Spieler, die nicht mehr als [tol] Blöcke über/unter [y] stehen (Windungen liegen übereinander). */
    private fun sameLevel(list: List<ServerPlayerEntity>, y: Double, tol: Double = 3.0) = list.filter { abs(it.y - y) <= tol }

    /** Lauffläche nahe [p]: höchste feste Oberkante von 3 Blöcken darüber bis 8 darunter, sonst [p]. */
    private fun ground(p: Vec3d): Vec3d {
        val base = BlockPos.ofFloored(p.x, p.y + 3.0, p.z)
        for (dy in 0..11) {
            val q = base.down(dy)
            val shape = f.world.getBlockState(q).getCollisionShape(f.world, q)
            if (!shape.isEmpty) {
                val top = q.y + shape.getMax(Direction.Axis.Y)
                if (f.passable(Vec3d(p.x, top, p.z))) return Vec3d(p.x, top, p.z)
            }
        }
        return p
    }

    /** Steht der Spieler über einem Abgrund (Brücke, Schlucht)? */
    private fun overGap(p: ServerPlayerEntity): Boolean {
        val feet = p.blockPos
        var air = 0
        for (k in 2..8) if (f.world.getBlockState(feet.down(k)).isAir) air++
        return air >= 5
    }

    /** Seite der Route, auf der das Gelände tiefer liegt (dort fliegt Solvane, dort ist der Abhang). */
    private fun lowSide(s: Double): Vec3d {
        val p = route.at(s)
        val dir = route.dir(s)
        val side = Vec3d(-dir.z, 0.0, dir.x)
        fun h(v: Vec3d): Int = runCatching { FarPlan.top(kotlin.math.floor(v.x).toInt() - f.origin.x, kotlin.math.floor(v.z).toInt() - f.origin.z) }.getOrDefault(0)
        return if (h(p.add(side.multiply(12.0))) <= h(p.subtract(side.multiply(12.0)))) side else side.multiply(-1.0)
    }

    // --- Ablauf -----------------------------------------------------------------------------------------------------

    /** Die ganze Jagd: Zorn-Szene, Verfolgung bis zum Tor. Danach übernimmt [arrive]. */
    fun run(): Script = script {
        val a = f.arius ?: return@script
        buildRoute()
        val rage = CineScene(f) { f.atmos(AtmosMode.WHITEOUT, 20) }
        yieldAll(rage.play(script { yieldAll(rageScene(rage, a)) }))
        f.chasing = true
        a.eyeGlow = 100
        a.form = AriusForm.WHITE
        a.bladeState = AriusBlade.ICE
        a.sheathed = false
        f.music("music.transform", 30)
        f.atmos(AtmosMode.WHITEOUT, 40)
        f.fxAll(FxKind.RAGE_AURA, a.entityPos, life = 20 * 60 * 25, e1 = a)
        for (p in f.players()) p.sendMessage(Text.translatable("hha_arius.msg.chase").formatted(Formatting.AQUA, Formatting.BOLD), false)
        val d = f.nivaris?.takeIf { !f.dragonDowned }
        d?.let {
            it.flying = true
            it.resonance = true
            it.play(NivarisPose.GLIDE)
        }
        aS = route.project(a.entityPos).first
        nextAttack = f.now + 70
        nextDragon = f.now + 140
        nextStar = f.now + 600
        nextLeap = f.now + 40
        while (true) {
            val players = f.players()
            if (players.isEmpty()) return@script
            val arrived = players.firstOrNull { it.entityPos.distanceTo(gate) <= 4.5 || (it.isOnGround && route.project(it.entityPos).let { (s, dd) -> s >= route.length - 1.5 && dd < 5.0 }) }
            if (arrived != null) {
                arrivedFirst = arrived
                break
            }
            if (f.now % 10L == 0L) keepOnRoute(players)
            val progress = players.associateWith { route.project(it.entityPos).first }
            val leader = progress.maxByOrNull { it.value }!!.key
            val rear = progress.minByOrNull { it.value }!!.key
            if (d != null) tickDragon(d, leader, progress[leader] ?: 0.0)
            if (!busy) chaseTick(a, rear, progress[rear] ?: 0.0, leader, progress[leader] ?: 0.0)
            yield(Unit)
        }
        f.chasing = false
    }

    /** Ein Tick Verfolgung: laufen, springen oder angreifen. */
    private fun chaseTick(a: AriusEntity, t: ServerPlayerEntity, sT: Double, leader: ServerPlayerEntity, sL: Double) {
        val gap = sT - aS
        val d3 = a.entityPos.distanceTo(t.entityPos)
        val now = f.now
        // Nah dran: Kombo.
        if (d3 < 5.5 && now >= nextAttack) {
            start { closeCombo(a, t) }
            nextAttack = now + 34 + rnd.nextInt(20)
            return
        }
        // Weit zurück oder auf einer anderen Windung: Sprung (über die Flanke, wenn sie über ihm sind).
        if (now >= nextLeap && (gap > 30.0 || (d3 < 30.0 && gap > 12.0 && t.y - a.y > 5.0))) {
            val to = ground(route.at((sT - 5.0).coerceAtLeast(aS)))
            start { leap(a, to, t) }
            nextLeap = now + 60
            return
        }
        // Mittlere Entfernung: Fernangriff.
        if (now >= nextAttack && d3 in 7.0..34.0) {
            var pick = rnd.nextInt(4)
            if (pick == lastRanged) pick = (pick + 1) % 4
            val onBridge = f.players().firstOrNull { overGap(it) && abs(route.project(it.entityPos).first - aS) < 40.0 }
            lastRanged = pick
            when {
                now >= nextStar && rnd.nextFloat() < 0.4f -> { nextStar = now + 640; start { chaseStar(a, leader, sL) } }
                onBridge != null && rnd.nextFloat() < 0.6f -> start { bridgeGale(a, onBridge) }
                pick == 0 -> start { crescentWave(a, t) }
                pick == 1 && abs(t.y - a.y) < 4.0 -> start { javelins(a, t) }
                pick == 2 -> start { flankAvalanche(a, leader, sL) }
                else -> start { crescentWave(a, t) }
            }
            nextAttack = now + 46 + rnd.nextInt(30)
            return
        }
        // Laufen: schneller als Sprinten, bis kurz hinter das Ziel.
        val speed = when {
            gap > 14.0 -> 0.46
            gap > 6.0 -> 0.34
            else -> 0.0
        }
        if (speed > 0.0) {
            aS = (aS + speed).coerceAtMost(route.length)
            val want = route.at(aS)
            var pos = ground(want)
            if (pos.y - a.y > 1.2) {
                // Stufe oder Hindernis: kleiner Sprung
                pos = pos.add(0.0, 0.4, 0.0)
                if (f.now % 6L == 0L) cue("arius.wind_step", a.entityPos, 1.2f, 1.5f)
            }
            f.place(a, pos)
            f.face(a, a.entityPos.add(route.dir(aS)))
            if (a.pose != AriusPose.IDLE) a.play(AriusPose.IDLE)
        } else {
            f.face(a, t.entityPos)
        }
    }

    private fun start(attack: () -> Script) {
        busy = true
        f.task(script {
            try {
                yieldAll(attack())
            } finally {
                busy = false
                f.arius?.let { aS = maxOf(aS - 4.0, route.project(it.entityPos).first) }
            }
        })
    }

    /** Sprung im Bogen nach [to] (absolut, Boden), Landung mit kleinem Ring. */
    private fun leap(a: AriusEntity, to: Vec3d, t: ServerPlayerEntity): Script = script {
        val from = a.entityPos
        val dist = from.distanceTo(to)
        val ticks = (10 + dist / 2.2).toInt().coerceIn(12, 26)
        val ring = f.calls.ring(to, 3.0f, ticks, pace = Telegraph.Pace.QUICK)
        a.play(AriusPose.LAUNCH, 6)
        cue("arius.wind_step", from, 3f, 0.8f)
        if (!Cine.muted) f.fx(FxKind.DASH_STREAK, from, to, life = ticks + 4)
        val lift = 4.0 + dist * 0.12 + maxOf(0.0, to.y - from.y) * 0.3
        for (i in 1..ticks) {
            val k = i / ticks.toDouble()
            val xz = from.lerp(to, k)
            val y = from.y + (to.y - from.y) * k + sin(k * PI) * lift
            f.place(a, Vec3d(xz.x, y, xz.z))
            f.face(a, t.entityPos)
            if (i == ticks - 6) a.play(AriusPose.STAB_GROUND, 6)
            yield(Unit)
        }
        f.place(a, to)
        cue("arius.stab_ground", to, 3f, 0.9f)
        Vfx.shockwave(f.world, Vfx.Style.HEAVEN, to, 3.5f, 12, 1.4f)
        f.fx(FxKind.CLEAR_WAVE, to, a = 5f, life = 14)
        for (p in sameLevel(ring.hits(f.players()), to.y)) {
            exactHit(p, 6f, Damage.Kind.IMPACT)
            Fx.launchPlayer(p, flatDir(to, p.entityPos).multiply(0.6).add(0.0, 0.4, 0.0))
        }
        aS = route.project(to).first
        wait(6)
    }

    /** Nah: drei schnelle Hiebe (je 3 Herzen), der letzte als Aufwärtsschnitt. */
    private fun closeCombo(a: AriusEntity, t0: ServerPlayerEntity): Script = script {
        for (k in 0 until 3) {
            val t = t0.takeIf { it.isAlive && it in f.players() } ?: break
            if (a.entityPos.distanceTo(t.entityPos) > 4.5) {
                val from = a.entityPos
                val to = ground(t.entityPos.subtract(flatDir(from, t.entityPos).multiply(1.6)))
                for (i in 1..4) { f.place(a, from.lerp(to, i / 4.0)); yield(Unit) }
                if (!Cine.muted) f.fx(FxKind.DASH_STREAK, from, to, life = 8)
            }
            f.face(a, t.entityPos)
            val dir = f.facing(a)
            val cone = f.calls.cone(a.entityPos, dir, 4.4f, 70f, if (k == 0) 12 else 10, if (k == 0) Telegraph.Pace.HEAVY else Telegraph.Pace.FOLLOW_UP)
            a.play(listOf(AriusPose.COMBO_1, AriusPose.COMBO_2, AriusPose.LAUNCH)[k], cone.ticks)
            wait(cone.ticks)
            cue("arius.slash", a.entityPos, 3f, 1.0f + k * 0.1f)
            if (!Cine.muted) f.fx(FxKind.SLASH_ARC, a.entityPos.add(0.0, 1.1, 0.0), dir, a = 4.4f, b = if (k == 2) 1f else 0.2f, life = 10)
            for (p in sameLevel(cone.hits(f.players()), a.y)) {
                exactHit(p, 6f)
                if (k == 2) Fx.launchPlayer(p, dir.multiply(0.3).add(0.0, 0.7, 0.0))
            }
            wait(3)
        }
        a.play(AriusPose.IDLE)
        wait(10)
    }

    /** Sichelwelle: drei Sicheln die Route hinauf auf das Ziel (je 3 Herzen). */
    private fun crescentWave(a: AriusEntity, t: ServerPlayerEntity): Script = script {
        f.face(a, t.entityPos)
        val from = a.entityPos
        val dir = flatDir(from, t.entityPos)
        val dist = from.distanceTo(t.entityPos)
        val side = Vec3d(-dir.z, 0.0, dir.x)
        for (k in 0 until 3) {
            val off = side.multiply(listOf(0.0, -1.8, 1.8)[k])
            val l0 = from.add(off)
            val l1 = from.add(off).add(dir.multiply(dist + 8.0))
            val line = f.calls.line(l0, Vec3d(l1.x, t.y, l1.z), 2.2f, if (k == 0) 16 else 10, if (k == 0) Telegraph.Pace.PROJECTILE else Telegraph.Pace.FOLLOW_UP)
            a.play(if (k % 2 == 0) AriusPose.COMBO_1 else AriusPose.COMBO_2, line.ticks)
            wait(line.ticks)
            cue("arius.slash", from, 3f, 0.9f + k * 0.1f)
            if (!Cine.muted) {
                f.fx(FxKind.SLASH_ARC, from.add(0.0, 1.1, 0.0), dir, a = 5f, b = 0f, life = 12)
                Vfx.crescent(f.world, Vfx.Style.HEAVEN, from.add(off).add(0.0, 0.8, 0.0), dir, (dist + 8.0).toFloat(), 2f, 10)
            }
            for (p in line.hits(f.players()).filter { abs(it.y - t.y) <= 4.0 }) exactHit(p, 6f)
        }
        a.play(AriusPose.IDLE)
        wait(8)
    }

    /** Eisspeere: drei Speere im Fächer auf das Ziel (je 3 Herzen, Frost). */
    private fun javelins(a: AriusEntity, t: ServerPlayerEntity): Script = script {
        f.face(a, t.entityPos)
        val from = a.entityPos
        val dir = flatDir(from, t.entityPos)
        a.play(AriusPose.THROW_CRYSTALS, 20)
        cue("crystal.hang", from, 3f, 1.3f)
        val group = f.calls.boss()
        val lines = listOf(-0.28, 0.0, 0.28).map { off ->
            val dd = Vec3d(dir.x * kotlin.math.cos(off) - dir.z * sin(off), 0.0, dir.x * sin(off) + dir.z * kotlin.math.cos(off))
            val to = from.add(dd.multiply(from.distanceTo(t.entityPos) + 10.0))
            if (!Cine.muted) f.fx(FxKind.CRYSTAL, from.add(0.0, 2.6, 0.0).add(dd.multiply(0.8)), to.add(0.0, 1.0, 0.0), a = 20f, life = 28)
            group.line(from, to, 1.6f, 20, Telegraph.Pace.PROJECTILE)
        }
        wait(20)
        cue("arius.thrust", from, 3f, 1.2f)
        for (line in lines) for (p in sameLevel(line.hits(f.players()), from.y, 4.0)) {
            exactHit(p, 6f)
            dev.henny.hha_arius.gear.Frostbite.add(p, 18f)
        }
        a.play(AriusPose.IDLE)
        wait(8)
    }

    /**
     * Lawine aus der Flanke: Er springt über die Spieler hinweg an den Hang oberhalb der Route und schlägt in den Schnee;
     * drei Schneewalzen rollen quer über den Weg vor dem Vordersten (je 3 Herzen, wirft bergab).
     */
    private fun flankAvalanche(a: AriusEntity, leader: ServerPlayerEntity, sL: Double): Script = script {
        val up = lowSide(sL).multiply(-1.0)
        val perch = ground(route.at(sL + 6.0).add(up.multiply(9.0)).add(0.0, 6.0, 0.0))
        yieldAll(leap(a, perch, leader))
        f.face(a, route.at(sL))
        a.play(AriusPose.STAB_GROUND, 20)
        cue("glacier.collapse", perch, 5f, 0.8f)
        f.calls.roomShake(leader.entityPos, 4f, 40f)
        val waves = (0 until 3).map { k ->
            val sp = sL + 12.0 - k * 6.0
            val pt = route.at(sp)
            val dir = route.dir(sp)
            val side = Vec3d(-dir.z, 0.0, dir.x)
            val line = f.calls.line(pt.add(side.multiply(-6.0)), pt.add(side.multiply(6.0)), 3.2f, 24 + k * 9, Telegraph.Pace.HEAVY)
            Triple(line, pt, dir)
        }
        var elapsed = 0
        for ((line, pt, dir) in waves) {
            wait((line.ticks - elapsed).coerceAtLeast(0))
            elapsed = line.ticks
            cue("ridge.break", pt, 4f, 0.7f)
            if (!Cine.muted) {
                f.fx(FxKind.CLEAR_WAVE, pt, a = 7f, life = 16)
                f.fx(FxKind.DEBRIS_CLOUD, pt.add(0.0, 1.0, 0.0), a = 5f, life = 30)
                Vfx.shockwave(f.world, Vfx.Style.STORM, pt, 6f, 12, 1.4f)
            }
            for (p in sameLevel(line.hits(f.players()), pt.y)) {
                exactHit(p, 6f, Damage.Kind.IMPACT)
                Fx.launchPlayer(p, dir.multiply(-0.7).add(0.0, 0.4, 0.0))
            }
        }
        a.play(AriusPose.IDLE)
    }

    /** Sturmstoß über eine Brücke: ein Windstoß quer über die Brücke, der vom Steg fegen kann (2 Herzen). */
    private fun bridgeGale(a: AriusEntity, t: ServerPlayerEntity): Script = script {
        val (s, _) = route.project(t.entityPos)
        val dir = route.dir(s)
        val side = lowSide(s)
        val pt = Vec3d(t.x, t.y, t.z)
        f.face(a, pt)
        a.play(AriusPose.SPIN_CUT, 22)
        cue("cut.reverse_wind", a.entityPos, 3f, 1.2f)
        val line = f.calls.line(pt.subtract(dir.multiply(7.0)), pt.add(dir.multiply(7.0)), 3.4f, 22, Telegraph.Pace.HEAVY)
        wait(line.ticks)
        cue("cut.strike", pt, 3f, 1.3f)
        if (!Cine.muted) f.fx(FxKind.GALE, pt.subtract(side.multiply(4.0)).add(0.0, 1.0, 0.0), side.multiply(10.0), a = 1f, life = 14)
        for (p in sameLevel(line.hits(f.players()), pt.y)) {
            exactHit(p, 4f)
            Fx.launchPlayer(p, side.multiply(1.0).add(0.0, 0.45, 0.0))
        }
        a.play(AriusPose.IDLE)
        wait(8)
    }

    /** Der gefrorene Stern in der Jagd: über der Route vor dem Vordersten, kleiner, aber 5 Herzen. */
    private fun chaseStar(a: AriusEntity, leader: ServerPlayerEntity, sL: Double): Script = script {
        val spot = route.at(sL + 8.0)
        a.play(AriusPose.CORE_RAISE)
        val ticks = 54
        for (p in f.audience()) Cine.title(listOf(p), "hha_arius.feat.frozen_star", "hha_arius.feat.frozen_star.sub", 40)
        f.fxAll(FxKind.FROZEN_STAR, spot, a = 7f, b = ticks.toFloat(), life = ticks + 40)
        cue("core.form", spot.add(0.0, 30.0, 0.0), 5f, 0.6f)
        val ring = f.calls.ring(spot, 7f, ticks)
        wait(ring.ticks)
        cue("glacier.collapse", spot, 6f, 1.2f)
        f.calls.roomShake(spot, 6f, 40f)
        for (p in sameLevel(ring.hits(f.players()), spot.y)) {
            exactHit(p, 10f, Damage.Kind.IMPACT)
            dev.henny.hha_arius.gear.Frostbite.add(p, 40f)
        }
        @Suppress("UNUSED_VARIABLE") val unused = leader
        a.play(AriusPose.IDLE)
    }

    /** Solvane fliegt außen an der Route neben dem Vordersten und speit von Zeit zu Zeit Frost über den Weg. */
    private fun tickDragon(d: NivarisEntity, leader: ServerPlayerEntity, sL: Double) {
        if (dragonBusy) return
        orbit += 0.03
        val ahead = sL + 10.0
        val p = route.at(ahead)
        val out = lowSide(ahead)
        val want = p.add(out.multiply(24.0 + sin(orbit) * 6.0)).add(route.dir(ahead).multiply(kotlin.math.cos(orbit) * 10.0)).add(0.0, 14.0, 0.0)
        f.place(d, d.entityPos.lerp(want, 0.08))
        f.face(d, p)
        if (f.now >= nextDragon) {
            dragonBusy = true
            nextDragon = f.now + 170 + rnd.nextInt(60)
            f.task(script {
                try { yieldAll(dragonStrafe(d, sL)) } finally { dragonBusy = false }
            })
        }
        @Suppress("UNUSED_VARIABLE") val unused = leader
    }

    /** Anflug von außen, Frostatem längs über den Weg vor dem Vordersten (3,5 Herzen, Frost). */
    private fun dragonStrafe(d: NivarisEntity, sL: Double): Script = script {
        val p0 = route.at(sL + 4.0)
        val p1 = route.at(sL + 26.0)
        val out = lowSide(sL + 15.0)
        val perch = p0.lerp(p1, 0.5).add(out.multiply(16.0)).add(0.0, 10.0, 0.0)
        val from = d.entityPos
        for (i in 1..20) {
            f.place(d, from.lerp(perch, i / 20.0))
            f.face(d, p0.lerp(p1, 0.5))
            yield(Unit)
        }
        d.play(NivarisPose.INHALE, 26)
        cue("nivaris.inhale", perch, 4f)
        val line = f.calls.line(p0, p1, 3.4f, 26, Telegraph.Pace.HEAVY, style = Vfx.Style.SOUL)
        for (i in 0 until line.ticks) {
            d.throatGlow = i * 100 / line.ticks
            yield(Unit)
        }
        d.throatGlow = 0
        d.play(NivarisPose.BREATH)
        cue("nivaris.breath", perch, 5f)
        if (!Cine.muted) {
            Vfx.beam(f.world, Vfx.Style.SOUL, perch.add(0.0, 2.0, 0.0), p0.lerp(p1, 0.5).add(0.0, 0.5, 0.0), 2.4f, 16)
            f.fx(FxKind.DRAGON_BREATH, d.entityPos, a = 26f, life = 20, e1 = d)
        }
        for (p in f.players().filter { abs(it.y - p0.lerp(p1, 0.5).y) <= 6.0 && line.contains(it) }) {
            exactHit(p, 7f, Damage.Kind.FROST_BREATH)
            dev.henny.hha_arius.gear.Frostbite.add(p, 30f)
        }
        wait(16)
        d.play(NivarisPose.GLIDE)
    }

    /** Wer weit abseits der Route ist (Abkürzung über die Flanke, Sturz von einer Brücke), kommt auf die Route zurück. */
    private fun keepOnRoute(players: List<ServerPlayerEntity>) {
        val startY = route.at(0.0).y
        for (p in players) {
            if (Hold.isHeld(p)) continue
            val (s, dist) = route.project(p.entityPos)
            // Auf dem Feld vor dem Berg (erstes Stück der Route) darf man sich frei bewegen.
            if (s < route.cum.getOrElse(1) { 0.0 } + 4.0 && p.y < startY + 8.0) continue
            if (dist <= 11.0) continue
            val back = ground(route.at((s - 3.0).coerceAtLeast(0.0)).add(0.0, 1.0, 0.0))
            p.teleport(f.world, back.x, back.y + 0.1, back.z, emptySet(), p.yaw, p.pitch, false)
            p.onLanding()
            p.addStatusEffect(StatusEffectInstance(StatusEffects.SLOWNESS, 30, 1, false, false, true))
            exactHit(p, 2f)
            p.sendMessage(Text.translatable("hha_arius.msg.path_storm").formatted(Formatting.AQUA), true)
            if (!Cine.muted) Vfx.shockwave(f.world, Vfx.Style.STORM, back, 3f, 10, 1f)
        }
    }

    /** Zorn-Szene vor der Jagd (≈ 6 s, überspringbar). */
    private fun rageScene(scene: CineScene, a: AriusEntity): Script = script {
        val base = a.entityPos
        val front = f.facing(a)
        val side = Vec3d(-front.z, 0.0, front.x)
        val face = base.add(0.0, 1.95, 0.0)
        scene.line(face.add(front.multiply(2.2)).add(side.multiply(0.6)).add(0.0, -0.8, 0.0), face.add(front.multiply(1.6)).add(side.multiply(0.4)).add(0.0, -0.6, 0.0), face, face, 34, 46.0, 38.0)
        a.play(AriusPose.CUT_CHARGE)
        f.fx(FxKind.REVERSE_WIND, base.add(0.0, 1.0, 0.0), a = 18f, life = 60)
        cue("cut.reverse_wind", base, 4f, 0.7f)
        wait(16)
        if (!Cine.muted) f.say("rage", 40)
        a.eyeGlow = 100
        cue("transform.pulse", base, 5f, 0.6f)
        wait(18)
        f.fxAll(FxKind.RAGE_AURA, base, life = 160, e1 = a)
        f.atmos(AtmosMode.WHITEOUT, 30)
        if (!Cine.muted) {
            Vfx.shockwave(f.world, Vfx.Style.HEAVEN, base, 18f, 24, 2.6f)
            f.fx(FxKind.CLEAR_WAVE, base, a = 22f, life = 26)
            f.fx(FxKind.FLASH, base, a = 0.4f, life = 8)
        }
        f.calls.roomShake(base, 7f, 60f)
        cue("cut.strike", base, 5f, 0.6f)
        a.play(AriusPose.CORE_RAISE)
        val ang = Math.toDegrees(atan2(front.z, front.x))
        scene.orbit(base, 3.0, 1.2, ang - 20.0, ang + 70.0, 46, 50.0, 70.0, lookAt = a, lookOffset = 1.4, radiusTo = 11.0, heightTo = 5.0)
        scene.once { Cine.title(it, "hha_arius.cine.chase", "hha_arius.cine.chase.sub", 60) }
        wait(46)
        // Blick zum Berg: die Route verschwindet im Sturm.
        val foot = route.at(minOf(route.length, 30.0))
        scene.line(base.add(side.multiply(4.0)).add(0.0, 3.0, 0.0), base.add(side.multiply(5.0)).add(0.0, 6.0, 0.0), foot.add(0.0, 10.0, 0.0), foot.add(0.0, 40.0, 0.0), 30, 66.0, 60.0)
        wait(30)
        scene.home(14)
        wait(14)
    }

    /**
     * Ankunft auf dem Gipfel (seit 1.9.0 sauber, Crix: „man ist schon oben auf der Arena, bevor man richtig drin ist“):
     * Erst wenn jemand das Tor erreicht, endet die Jagd. Kamera vom Gipfel auf das Tor, der Erste tritt herauf; der Sturm
     * reißt auf. Im Weiß trägt das Licht die Übrigen hinauf an den Rand der Arena (nicht in die Mitte). Dann springt
     * Arius aus der Tiefe an ihnen vorbei in die Mitte, Titelkarte. Erst danach beginnt das Finale.
     */
    fun arrive(): Script = script {
        val a = f.arius ?: return@script
        f.chasing = false
        f.calls.reset()
        f.onSummit = true
        val center = f.arenaCenter.let { Vec3d(it.x, f.floorY, it.z) }
        val gatePos = gate
        val toCenter = flatDir(gatePos, center)
        val side = Vec3d(-toCenter.z, 0.0, toCenter.x)
        val spots = f.finaleSpots().map { f.abs(it) }
        val first = arrivedFirst
        val place = {
            for ((i, p) in f.players().withIndex()) {
                if (Hold.isHeld(p)) Hold.drop(p)
                val s = spots[i % spots.size]
                val yaw = (Math.toDegrees(atan2(center.z - s.z, center.x - s.x)) - 90.0).toFloat()
                p.teleport(f.world, s.x, s.y, s.z, emptySet(), yaw, 0f, false)
                p.onLanding()
            }
            f.place(a, center)
            f.face(a, gatePos)
            a.play(AriusPose.IDLE)
            a.eyeGlow = 60
        }
        val scene = CineScene(f) { place() }
        yieldAll(scene.play(script {
            // 1) Vom Gipfel auf das Tor: der Erste tritt herauf, der Sturm reißt auf.
            f.atmos(AtmosMode.REVEAL, 60)
            f.music(null, 40)
            val look = gatePos.add(0.0, 1.4, 0.0)
            val cam = gatePos.add(toCenter.multiply(9.0)).add(side.multiply(4.0)).add(0.0, 2.2, 0.0)
            scene.line(cam, cam.add(toCenter.multiply(-1.5)).add(0.0, -0.4, 0.0), look, look, 44, 58.0, 52.0, lookAt = first, lookOffset = 1.4)
            cue("resonance.chime", gatePos, 4f, 0.8f)
            wait(30)
            // 2) Im Weiß: alle stehen am Rand der Arena, Arius ist noch unten auf der Route.
            scene.once { Cine.fade(it, 0xF2F6FA, 0.0, 1.0, 10) }
            wait(12)
            for ((i, p) in f.players().withIndex()) {
                if (Hold.isHeld(p)) Hold.drop(p)
                val s = spots[i % spots.size]
                val yaw = (Math.toDegrees(atan2(center.z - s.z, center.x - s.x)) - 90.0).toFloat()
                p.teleport(f.world, s.x, s.y, s.z, emptySet(), yaw, 0f, false)
                p.onLanding()
            }
            val below = ground(route.at((route.length - 24.0).coerceAtLeast(0.0)))
            f.place(a, below)
            f.face(a, center)
            scene.once { Cine.fade(it, 0xF2F6FA, 1.0, 0.0, 20) }
            // 3) Er springt aus der Tiefe an ihnen vorbei in die Mitte.
            scene.orbit(center, 16.0, 5.0, Math.toDegrees(atan2(side.z, side.x)) - 20.0, Math.toDegrees(atan2(side.z, side.x)) + 40.0, 56, 66.0, 58.0, lookAt = a, lookOffset = 1.2, radiusTo = 12.0, heightTo = 3.0)
            wait(14)
            a.play(AriusPose.LAUNCH, 6)
            cue("arius.wind_step", below, 4f, 0.7f)
            val from = a.entityPos
            val ticks = 24
            val apex = maxOf(from.y, center.y) + 10.0
            for (i in 1..ticks) {
                val k = i / ticks.toDouble()
                val xz = from.lerp(center, k)
                val y = from.y + (center.y - from.y) * k + sin(k * PI) * (apex - maxOf(from.y, center.y) + 4.0)
                f.place(a, Vec3d(xz.x, y, xz.z))
                f.face(a, gatePos)
                if (i == ticks - 6) a.play(AriusPose.STAB_GROUND, 6)
                yield(Unit)
            }
            f.place(a, center)
            cue("arius.stab_ground", center, 5f, 0.7f)
            cue("nivaris.land", center, 4f, 1.3f)
            f.calls.roomShake(center, 5f, 40f)
            if (!Cine.muted) {
                Vfx.shockwave(f.world, Vfx.Style.HEAVEN, center, 10f, 18, 2f)
                f.fx(FxKind.CLEAR_WAVE, center, a = 14f, life = 22)
                f.fx(FxKind.DEBRIS_CLOUD, center.add(0.0, 1.0, 0.0), a = 6f, life = 30)
            }
            scene.once { Cine.title(it, "hha_arius.cine.summit", "hha_arius.cine.summit.sub", 70) }
            a.play(AriusPose.IDLE)
            wait(40)
            scene.home(16)
            wait(16)
        }))
        place()
    }
}
