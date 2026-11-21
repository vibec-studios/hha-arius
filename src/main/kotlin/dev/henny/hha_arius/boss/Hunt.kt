package dev.henny.hha_arius.boss

import dev.henny.hha.logic.Fx
import dev.henny.hha.logic.Vfx
import dev.henny.hha.logic.boss.Cine
import dev.henny.hha.logic.boss.Telegraph
import dev.henny.hha_arius.arena.ArenaLayout
import dev.henny.hha_arius.arena.FarPlan
import dev.henny.hha_arius.arena.HuntRoute
import dev.henny.hha_arius.net.AtmosMode
import dev.henny.hha_arius.net.FxKind
import dev.henny.hha_arius.net.HuntFx
import dev.henny.hha_arius.net.HuntNet
import net.minecraft.block.Blocks
import net.minecraft.entity.effect.StatusEffectInstance
import net.minecraft.entity.effect.StatusEffects
import net.minecraft.particle.BlockStateParticleEffect
import net.minecraft.particle.ParticleTypes
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvents
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Box
import net.minecraft.util.math.Direction
import net.minecraft.util.math.Vec3d
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Die Jagd (Zorn des Erben), seit 1.9.0 eine echte Verfolgung (Crix: „er teleportiert sich nur in dich rein“):
 * * Die Spieler fliehen über die Route ([FarPlan.huntWaypoints]: Wege, Hängebrücken, Steinbrücke, Höhlen) den Berg
 *   hinauf. Arius **läuft** ihnen nach (schneller als Sprinten), **springt** über Hindernisse, nah eine Kombo, fern
 *   Sichelwellen, Eisspeere, Lawine aus der Flanke, Sturmstoß, selten der gefrorene Stern. Solvane begleitet außen.
 *
 * Seit 1.10.0 (Welt & Jagd):
 * * **Rettung statt Rückwurf:** Jeder Spieler hat einen letzten sicheren Punkt (zuletzt auf der Route gestanden, dichte
 *   Punktfolge von [HuntRoute], zwei Ebenen). Wer fällt (8 unter diesem Punkt, auf einer tieferen Windung gelandet,
 *   lange abseits), wird mit kurzer Blende genau dorthin zurückgesetzt (zurückgeprüft: fester Boden, Kopffreiheit),
 *   Blick in Laufrichtung. Nie mehr an eine falsche Windung (früher: grobe Projektion, Höhe zählte zu wenig).
 * * **Nie heruntergestoßen:** Alle Stöße laufen über [HuntGuard] (entlang der Route, nicht zum Abhang).
 * * **Höhlen** ([HuntRoute.caves]): Betritt der Erste eine Höhle, wird der Berg unruhig (Grollen, Staub, Steinchen),
 *   nach 8 s verschließt Geröll den Hauptgang hinter dem Eingang. Wer zu spät kommt, nimmt den engen Nebengang.
 *   Arius schlägt den Pfropfen auf, wenn er ihn erreicht. In jeder Höhle wartet Solvane: Auge im Fenster, Atem durch
 *   Risse, ihr Leib hinter der Eiswand, Brüllen im Wasserfall, Atem, der die Seehöhle füllt; im Windtunnel drückt der
 *   Sturm entgegen.
 * * **Hängebrücken:** Auf B1 brechen angekündigt Planken heraus (Lücke zum Springen), B3 stürzt hinter der Gruppe ein.
 * * **Lawine** am Hang der Zweiten Wacht, **Arius schneidet durch den Fels** neben ihnen, viele Filmeffekte am Client
 *   (Herzschlag, Atmen, Vignette, Farbsäume, Zeitlupe, Balken, Kippen; [HuntFx]).
 * Die Jagd endet, wenn jemand das Tor zur Gipfelarena erreicht ([arrive]).
 */
class Hunt(private val f: EirholmFight) {

    private val rnd = java.util.Random()

    /** Polylinie der Route (absolut), für Arius' Lauf. */
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
    private var nextCut = 0L
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

    private fun vanilla(event: net.minecraft.sound.SoundEvent, at: Vec3d, volume: Float, pitch: Float) {
        if (!Cine.muted) f.world.playSound(null, at.x, at.y, at.z, event, SoundCategory.HOSTILE, volume, pitch)
    }

    /** Fester Schaden in Herzen × 2, unabhängig von Rüstung (wie seit 1.7.0 in der Jagd). */
    private fun exactHit(p: ServerPlayerEntity, amount: Float, kind: Damage.Kind = Damage.Kind.SOLVANE) {
        val a = f.arius ?: return
        val red = Damage.reduction(p)
        Damage.hit(p, a, kind, (amount / (1.0 - red).coerceAtLeast(0.05)).toFloat(), 1f)
        HuntNet.fx(p, HuntFx.IMPACT, p.entityPos, a = (amount / 10f).coerceIn(0.25f, 1f), life = 12)
    }

    /** Stoß entlang der Route (nie zum Abhang, siehe [HuntGuard]). */
    private fun launch(p: ServerPlayerEntity, v: Vec3d) {
        Fx.launchPlayer(p, HuntGuard.redirect(f, p, v))
    }

    /** Knapp verfehlt: wer nah an [at] war, aber nicht getroffen wurde. */
    private fun nearMiss(at: Vec3d, radius: Double, hit: Collection<ServerPlayerEntity>) {
        for (p in f.players()) if (p !in hit && p.entityPos.squaredDistanceTo(at) <= radius * radius) {
            HuntNet.fx(p, HuntFx.NEAR_MISS, at, life = 14)
        }
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
        fun h(v: Vec3d): Int = runCatching { FarPlan.top(floor(v.x).toInt() - f.origin.x, floor(v.z).toInt() - f.origin.z) }.getOrDefault(0)
        return if (h(p.add(side.multiply(12.0))) <= h(p.subtract(side.multiply(12.0)))) side else side.multiply(-1.0)
    }

    // --- dichte Route (HuntRoute), absolut ---------------------------------------------------------------------------

    private fun point(i: Int): Vec3d = f.abs(FarPlan.pathPoint(i))

    /** Punkt seitlich neben Wegpunkt [i]: [out] Blöcke zur Talseite (negativ = Bergseite), [up] über der Lauffläche. */
    private fun beside(i: Int, out: Double, up: Double = 0.0): Vec3d {
        val (tx, tz) = HuntRoute.tangent(i)
        var sx = -tz
        var sz = tx
        if (sx * HuntRoute.normalX(i) + sz * HuntRoute.normalZ(i) < 0) { sx = -sx; sz = -sz }
        val p = point(i)
        return Vec3d(p.x + sx * out, p.y + up, p.z + sz * out)
    }

    private fun tangentVec(i: Int): Vec3d = HuntRoute.tangent(i).let { Vec3d(it.first, 0.0, it.second) }

    private fun yawAlong(i: Int): Float {
        val (tx, tz) = HuntRoute.tangent(i)
        return (Math.toDegrees(atan2(tz, tx)) - 90.0).toFloat()
    }

    /** Dichter Wegindex unter dem Spieler (passende Höhe) oder −1. */
    private fun denseIndex(p: ServerPlayerEntity, slack: Double, tol: Double): Int =
        HuntRoute.indexAtHeight(floor(p.x).toInt() - f.origin.x, floor(p.z).toInt() - f.origin.z, p.y - f.origin.y, slack, tol)

    /** Fester Boden und Kopffreiheit über Wegpunkt [i] (gerade jetzt, Lücken und Geröll eingerechnet)? */
    private fun standable(i: Int): Boolean {
        val p = point(i)
        val below = BlockPos.ofFloored(p.x, p.y - 0.3, p.z)
        val st = f.world.getBlockState(below)
        if (st.getCollisionShape(f.world, below).isEmpty) return false
        return f.passable(Vec3d(p.x, p.y + 0.05, p.z))
    }

    // --- Sichere Punkte und Rettung ------------------------------------------------------------------------------------

    private class Safe(var index: Int, var tick: Long) {
        var offTicks = 0
        var pending = -1
        var pendingAt = 0L
    }

    private val safe = HashMap<UUID, Safe>()

    /** Bogenlänge des letzten sicheren Punkts (Fortschritt für Ereignisse). */
    private fun arcOf(p: ServerPlayerEntity): Double = safe[p.uuid]?.let { HuntRoute.arc(it.index) } ?: 0.0

    private fun guardPlayers(players: List<ServerPlayerEntity>) {
        val now = f.now
        for (p in players) {
            val s = safe.getOrPut(p.uuid) {
                val rel = p.entityPos.subtract(Vec3d.of(f.origin))
                Safe(HuntRoute.nearest(rel.x, rel.y, rel.z), now)
            }
            if (s.pending >= 0) {
                if (now >= s.pendingAt) {
                    doRescue(p, s.pending)
                    s.index = s.pending
                    s.pending = -1
                    s.offTicks = 0
                }
                continue
            }
            if (Hold.isHeld(p) || p.hasVehicle()) continue
            val safeY = f.origin.y + HuntRoute.standY(s.index)
            // Auf dem Feld vor dem Berg (erstes Stück der Route) darf man sich frei bewegen.
            val onField = HuntRoute.arc(s.index) < 50.0 && p.y < f.origin.y + 6.0
            // 1) Sturz: weit unter den letzten sicheren Punkt gefallen.
            if (!onField && p.y < safeY - 8.0) {
                rescue(p, s)
                continue
            }
            val lane = denseIndex(p, 0.6, 1.4)
            if (p.isOnGround) {
                if (lane >= 0) {
                    val drop = HuntRoute.standY(s.index) - HuntRoute.standY(lane)
                    // 2) Auf einer tieferen Windung gelandet (Höhensprung ohne Weg dazwischen).
                    if (!onField && drop > 5.0 && abs(HuntRoute.arc(lane) - HuntRoute.arc(s.index)) > 6.0) {
                        rescue(p, s)
                        continue
                    }
                    s.index = lane
                    s.tick = now
                    s.offTicks = 0
                } else if (!onField) {
                    // 3) Lange abseits auf festem Boden (Flanke, Talgrund): zurück.
                    val wide = denseIndex(p, HuntRoute.ENVELOPE - HuntRoute.HALF_W, 2.5)
                    s.offTicks = if (wide >= 0) 0 else s.offTicks + 1
                    if (s.offTicks > 50) rescue(p, s)
                }
            }
        }
    }

    /** Kündigt die Rettung an (Blende) und setzt sie nach 4 Ticks um. */
    private fun rescue(p: ServerPlayerEntity, s: Safe) {
        s.pending = rescueTarget(s.index)
        s.pendingAt = f.now + 4
        HuntNet.fx(p, HuntFx.FADE, p.entityPos, a = 4f, b = 14f, life = 22, color = 0xFFF2F6FA.toInt())
    }

    /** Zielpunkt: der sichere Punkt selbst, sonst rückwärts (bis 40 Blöcke), sonst vorwärts der nächste begehbare. */
    private fun rescueTarget(from: Int): Int {
        var i = from
        var n = 0
        while (i > 0 && n < 80) {
            if (standable(i) && !inHazard(i)) return i
            i -= 2
            n++
        }
        i = from
        n = 0
        while (i < HuntRoute.size - 1 && n < 200) {
            if (standable(i) && !inHazard(i)) return i
            i += 2
            n++
        }
        return from
    }

    private fun doRescue(p: ServerPlayerEntity, i: Int) {
        if (!p.isAlive || p.entityWorld !== f.world) return
        val pos = point(i)
        p.teleport(f.world, pos.x, pos.y + 0.05, pos.z, emptySet(), yawAlong(i), 0f, false)
        p.onLanding()
        p.velocity = Vec3d.ZERO
        p.velocityDirty = true
        p.addStatusEffect(StatusEffectInstance(StatusEffects.SLOWNESS, 20, 1, false, false, true))
        exactHit(p, 2f)
        p.sendMessage(Text.translatable("hha_arius.msg.path_storm").formatted(Formatting.AQUA), true)
        if (!Cine.muted) Vfx.shockwave(f.world, Vfx.Style.STORM, pos, 3f, 10, 1f)
    }

    // --- Höhlen -------------------------------------------------------------------------------------------------------

    private inner class CaveRun(val c: HuntRoute.Cave) {
        var armedAt = -1L
        var sealed = false
        var opened = false
        var scared = false
        var plug: List<BlockPos> = emptyList()
        var fillFront = -1.0
        var fillUntil = 0L
    }

    private val caves = HuntRoute.caves.map { CaveRun(it) }

    /** Zellen der Höhle [c] (absolut) mit u in [u0, u1], Seitenabstand ≤ [maxLat], Höhe Wegblock + [y0]..[y1]. */
    private fun caveCells(c: HuntRoute.Cave, u0: Double, u1: Double, maxLat: Double, y0: Int, y1: Int): List<BlockPos> {
        var minX = Int.MAX_VALUE; var maxX = Int.MIN_VALUE; var minZ = Int.MAX_VALUE; var maxZ = Int.MIN_VALUE
        for (j in c.i0..c.i1) {
            val u = c.u(j)
            if (u < u0 || u > u1) continue
            minX = minOf(minX, floor(HuntRoute.x(j)).toInt()); maxX = maxOf(maxX, floor(HuntRoute.x(j)).toInt())
            minZ = minOf(minZ, floor(HuntRoute.z(j)).toInt()); maxZ = maxOf(maxZ, floor(HuntRoute.z(j)).toInt())
        }
        if (minX > maxX) return emptyList()
        val r = kotlin.math.ceil(maxLat).toInt() + 1
        val out = ArrayList<BlockPos>()
        for (x in minX - r..maxX + r) for (z in minZ - r..maxZ + r) {
            val h = HuntRoute.hit(x, z)
            if (h < 0) continue
            val j = HuntRoute.hitIndex(h)
            if (j !in c.i0..c.i1 || HuntRoute.hitLateral(h) > maxLat) continue
            val u = c.u(j)
            if (u < u0 || u > u1) continue
            val fy = floor(HuntRoute.y(j)).toInt()
            for (y in fy + y0..fy + y1) out += f.abs(BlockPos(x, y, z))
        }
        return out
    }

    private fun tickCaves(players: List<ServerPlayerEntity>, leaderArc: Double, rearArc: Double) {
        val now = f.now
        for (run in caves) {
            val c = run.c
            if (run.armedAt < 0 && leaderArc >= c.a0 - 1.0 && leaderArc < c.a1) {
                run.armedAt = now
                for (p in players) if (abs(arcOf(p) - c.a0) < 40.0) p.sendMessage(Text.translatable("hha_arius.hunt.cave.${c.theme.name.lowercase()}").formatted(Formatting.GRAY, Formatting.ITALIC), true)
            }
            if (run.armedAt < 0) continue
            val age = now - run.armedAt
            // Grollen: Ansage des Geröllfalls (3 s): Zittern, Staub, Steinchen, Zone am Pfropfen.
            if (age == 100L && !run.sealed) {
                val mid = point(HuntRoute.indexOfArc(c.a0 + (c.plugFrom + c.plugTo) / 2))
                val near = players.filter { it.squaredDistanceTo(mid) < 60.0 * 60.0 }
                HuntNet.fx(near, HuntFx.RUMBLE, mid.add(0.0, 2.0, 0.0), a = 0.8f, life = 60)
                cue("glacier.crack", mid, 4f, 0.6f)
                vanilla(SoundEvents.AMBIENT_CAVE.value(), mid, 3f, 0.5f)
                val a = point(HuntRoute.indexOfArc(c.a0 + c.plugFrom - 0.5))
                val b = point(HuntRoute.indexOfArc(c.a0 + c.plugTo + 0.5))
                f.calls.line(a, b, (c.theme.lane * 2 + 0.6).toFloat(), 60, Telegraph.Pace.HEAVY)
                for (p in near) p.sendMessage(Text.translatable("hha_arius.hunt.rumble").formatted(Formatting.GOLD), true)
            }
            if (age in 100L..160L && age % 6L == 0L && !run.sealed) {
                val i = HuntRoute.indexOfArc(c.a0 + c.plugFrom + rnd.nextDouble() * (c.plugTo - c.plugFrom))
                val top = point(i).add((rnd.nextDouble() - 0.5) * 3.0, c.theme.height + 0.4, (rnd.nextDouble() - 0.5) * 3.0)
                f.world.spawnParticles(BlockStateParticleEffect(ParticleTypes.FALLING_DUST, Blocks.STONE.defaultState), top.x, top.y, top.z, 10, 1.2, 0.2, 1.2, 0.0)
                f.world.spawnParticles(BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.COBBLESTONE.defaultState), top.x, top.y, top.z, 6, 0.8, 0.1, 0.8, 0.05)
                vanilla(SoundEvents.BLOCK_POINTED_DRIPSTONE_FALL, top, 1.2f, 0.7f)
            }
            if (age == 160L && !run.sealed) seal(run, players)
            // Arius schlägt den Pfropfen auf, wenn er davorsteht.
            val a = f.arius
            if (run.sealed && !run.opened && a != null && !busy && a.entityPos.squaredDistanceTo(point(HuntRoute.indexOfArc(c.a0 + c.plugFrom))) < 7.0 * 7.0) {
                run.opened = true
                start { breakPlug(a, run) }
            }
            // Solvane in der Höhle
            if (!run.scared && !dragonBusy && f.nivaris != null && !f.dragonDowned) scare(run, players, leaderArc, rearArc)
            if (run.fillFront >= 0) tickFill(run, players)
        }
    }

    /** Geröll verschließt den Hauptgang (nicht den Nebengang). Wer darunter steht, wird nach vorn gestoßen. */
    private fun seal(run: CaveRun, players: List<ServerPlayerEntity>) {
        val c = run.c
        run.sealed = true
        val cells = caveCells(c, c.plugFrom, c.plugTo, c.theme.lane + 0.3, 1, c.theme.height)
        if (cells.isEmpty()) return
        val boxes = cells.map { Box(it) }
        val ahead = HuntRoute.indexOfArc(c.a0 + c.plugTo + 2.0)
        for (p in players) {
            if (boxes.none { it.intersects(p.boundingBox) }) continue
            val to = point(ahead)
            p.teleport(f.world, to.x, to.y + 0.05, to.z, emptySet(), yawAlong(ahead), p.pitch, false)
            p.onLanding()
            exactHit(p, 6f, Damage.Kind.IMPACT)
            launch(p, tangentVec(ahead).multiply(0.5).add(0.0, 0.2, 0.0))
        }
        val placed = ArrayList<BlockPos>()
        for (pos in cells) {
            if (!f.world.getBlockState(pos).getCollisionShape(f.world, pos).isEmpty) continue
            val r = FarPlan.h32(pos.x, pos.y, pos.z, 1501)
            val st = when {
                r < 0.35 -> Blocks.COBBLESTONE
                r < 0.6 -> Blocks.STONE
                r < 0.8 -> Blocks.ANDESITE
                else -> Blocks.TUFF
            }.defaultState
            f.edits.set(pos, st)
            placed += pos
        }
        run.plug = placed
        val mid = point(HuntRoute.indexOfArc(c.a0 + (c.plugFrom + c.plugTo) / 2))
        cue("glacier.collapse", mid, 5f, 0.7f)
        vanilla(SoundEvents.ENTITY_GENERIC_EXPLODE.value(), mid, 2f, 0.5f)
        f.calls.roomShake(mid, 5f, 40f)
        val near = players.filter { it.squaredDistanceTo(mid) < 70.0 * 70.0 }
        for (k in 0 until 7) {
            val target = Vec3d.ofCenter(placed[rnd.nextInt(placed.size)])
            HuntNet.fx(near, HuntFx.BOULDER, target.add(0.0, 3.5 + rnd.nextDouble() * 2.0, 0.0), target, a = 0.8f + rnd.nextFloat() * 0.7f, life = 6 + k)
        }
        HuntNet.fx(near, HuntFx.IMPACT, mid, a = 0.7f, life = 16)
        f.world.spawnParticles(BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.STONE.defaultState), mid.x, mid.y + 2.0, mid.z, 80, 2.0, 1.5, 2.0, 0.1)
        for (p in players) if (arcOf(p) < c.a0 + c.plugFrom) p.sendMessage(Text.translatable("hha_arius.hunt.sealed").formatted(Formatting.GOLD, Formatting.BOLD), true)
    }

    /** Arius schlägt den Geröllpfropfen auf (Schnitt, Splitter), dann läuft er weiter. */
    private fun breakPlug(a: AriusEntity, run: CaveRun): Script = script {
        val c = run.c
        val at = point(HuntRoute.indexOfArc(c.a0 + c.plugFrom))
        val dir = tangentVec(HuntRoute.indexOfArc(c.a0 + c.plugFrom))
        f.face(a, at.add(dir))
        a.play(AriusPose.CUT_CHARGE, 14)
        cue("cut.charge", at, 3f, 1.2f)
        val near = f.players().filter { it.squaredDistanceTo(at) < 50.0 * 50.0 }
        HuntNet.fx(near, HuntFx.ROCK_CUT, at.add(0.0, c.theme.height + 0.5, 0.0), at.add(dir.multiply(2.0)), life = 16)
        wait(14)
        a.play(AriusPose.SPIN_CUT, 10)
        cue("cut.strike", at, 4f, 0.9f)
        for ((i, pos) in run.plug.withIndex()) if (i % 3 == 0) f.world.spawnParticles(BlockStateParticleEffect(ParticleTypes.BLOCK, f.world.getBlockState(pos)), pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, 4, 0.4, 0.4, 0.4, 0.15)
        f.edits.revert(run.plug)
        run.plug = emptyList()
        if (!Cine.muted) f.fx(FxKind.SLASH_ARC, at.add(0.0, 1.4, 0.0), dir, a = 4f, b = 0.9f, life = 10)
        HuntNet.fx(near, HuntFx.IMPACT, at, a = 0.5f, life = 12)
        wait(6)
        a.play(AriusPose.IDLE)
    }

    /** Solvanes Auftritte in den Höhlen (je Höhle einmal). */
    private fun scare(run: CaveRun, players: List<ServerPlayerEntity>, leaderArc: Double, rearArc: Double) {
        val c = run.c
        val u = leaderArc - c.a0
        val d = f.nivaris ?: return
        val inCave = players.filter { val a = arcOf(it); a >= c.a0 - 6.0 && a <= c.a1 + 6.0 }
        if (inCave.isEmpty()) return
        when (c.theme) {
            HuntRoute.Theme.DARK -> if (u >= (c.windows.firstOrNull() ?: 10.0) - 7.0) {
                run.scared = true
                val w = c.windows.firstOrNull { it > u - 2.0 } ?: c.windows.last()
                if (c.i0 > HuntRoute.size / 2) crackBreath(c, w, inCave) else eye(c, w, inCave, 1.4f)
            }
            HuntRoute.Theme.CRAWL -> if (u >= 4.0) {
                run.scared = true
                for ((k, w) in c.windows.withIndex()) f.task(script { wait(k * 34); crackBreath(c, w, f.players()) })
            }
            HuntRoute.Theme.CRYSTAL -> if (u >= c.iceFrom - 3.0) {
                run.scared = true
                if (c.i0 < HuntRoute.size / 2) {
                    dragonBusy = true
                    f.task(script { try { yieldAll(slidePast(d, c)) } finally { dragonBusy = false } })
                } else {
                    // Am Massiv: das Auge direkt hinter der Eiswand, riesig.
                    eye(c, (c.iceFrom + c.iceTo) / 2, inCave, 2.6f, out = 4.9, ice = true)
                }
            }
            HuntRoute.Theme.FALL -> if (u >= 7.0) {
                run.scared = true
                fallRoar(c, inCave)
            }
            HuntRoute.Theme.LAKE -> if (rearArc >= c.a0 + 3.0 || u >= 22.0) {
                run.scared = true
                run.fillFront = c.a0
                run.fillUntil = f.now + ((c.length / FILL_SPEED).toLong() + 120L)
                val mouth = point(c.i0)
                cue("nivaris.roar", mouth, 6f, 0.7f)
                HuntNet.fx(f.players(), HuntFx.ROAR, mouth, a = 0.8f, life = 30)
                HuntNet.fx(f.players(), HuntFx.BREATH_FILL, mouth, Vec3d(c.a0, c.a1, 0.0), a = FILL_SPEED.toFloat(), life = (run.fillUntil - f.now).toInt())
                for (p in inCave) p.sendMessage(Text.translatable("hha_arius.hunt.fill").formatted(Formatting.AQUA, Formatting.BOLD), true)
            }
            HuntRoute.Theme.WIND -> if (u >= 2.0) {
                run.scared = true
                val mid = point(HuntRoute.indexOfArc(c.a0 + c.length * 0.5))
                cue("nivaris.roar", mid.add(0.0, 14.0, 0.0), 5f, 0.6f)
                HuntNet.fx(inCave, HuntFx.ROAR, mid, a = 0.5f, life = 24)
            }
        }
    }

    /** Ihr Auge in einem Fenster der Wand: starrt, folgt, blinzelt, ist weg. */
    private fun eye(c: HuntRoute.Cave, u: Double, viewers: List<ServerPlayerEntity>, size: Float, out: Double = 5.2, ice: Boolean = false) {
        val i = HuntRoute.indexOfArc(c.a0 + u)
        val at = beside(i, out, if (ice) 2.4 else 3.0)
        val into = beside(i, 0.0, 1.6).subtract(at).normalize()
        HuntNet.fx(f.players().filter { it.squaredDistanceTo(at) < 80.0 * 80.0 }, HuntFx.EYE, at, into, a = size, life = 76)
        vanilla(SoundEvents.ENTITY_WARDEN_NEARBY_CLOSEST, at, 2f, 0.5f)
        cue("nivaris.inhale", at, 2f, 0.45f)
        f.task(script {
            wait(62)
            cue("nivaris.wing", at, 2.5f, 0.6f)
        })
    }

    /** Frostatem durch einen Riss quer über den Gang: Leuchten und Zischen (30 Ticks), dann der Strahl. */
    private fun crackBreath(c: HuntRoute.Cave, u: Double, viewers: List<ServerPlayerEntity>) {
        val i = HuntRoute.indexOfArc(c.a0 + u)
        val crack = beside(i, c.theme.lane + 0.9, if (c.theme == HuntRoute.Theme.CRAWL) 2.5 else 3.0)
        val end = beside(i, -c.theme.lane - 0.5, 0.8)
        val near = f.players().filter { it.squaredDistanceTo(crack) < 70.0 * 70.0 }
        HuntNet.fx(near, HuntFx.CRACK_BREATH, crack, end, a = 30f, life = 50)
        cue("nivaris.inhale", crack, 3f, 0.8f)
        val line = f.calls.line(Vec3d(crack.x, point(i).y, crack.z), Vec3d(end.x, point(i).y, end.z), 2.6f, 30, Telegraph.Pace.HEAVY, style = Vfx.Style.SOUL)
        f.task(script {
            wait(line.ticks)
            cue("nivaris.breath", crack, 4f, 1.1f)
            val hit = sameLevel(line.hits(f.players()), point(i).y, 2.5)
            for (p in hit) {
                exactHit(p, 5f, Damage.Kind.FROST_BREATH)
                dev.henny.hha_arius.gear.Frostbite.add(p, 26f)
            }
            nearMiss(crack.lerp(end, 0.5), 4.5, hit)
        })
    }

    /** Ihr Leib gleitet draußen an der Eiswand der Kristallgrotte vorbei (echte Solvane, nur verschoben). */
    private fun slidePast(d: NivarisEntity, c: HuntRoute.Cave): Script = script {
        val i0 = HuntRoute.indexOfArc(c.a0 + c.iceFrom - 10.0)
        val i1 = HuntRoute.indexOfArc(c.a0 + c.iceTo + 10.0)
        val from = beside(i0, 10.5, 1.0)
        val to = beside(i1, 10.5, 1.5)
        d.flying = true
        d.play(NivarisPose.GLIDE)
        // erst unbemerkt an den Anfang
        val start = d.entityPos
        for (k in 1..16) { f.place(d, start.lerp(from, k / 16.0)); f.face(d, from.add(to.subtract(from))); yield(Unit) }
        val viewers = f.players().filter { it.squaredDistanceTo(from) < 60.0 * 60.0 }
        cue("nivaris.wing", from, 4f, 0.5f)
        vanilla(SoundEvents.ENTITY_ELDER_GUARDIAN_CURSE, from.lerp(to, 0.5), 1.2f, 0.6f)
        HuntNet.fx(viewers, HuntFx.LETTERBOX, from, life = 50)
        val n = 34
        for (k in 1..n) {
            val t = k / n.toDouble()
            val e = t * t * (3 - 2 * t)
            f.place(d, from.lerp(to, e).add(0.0, sin(t * PI) * 0.8, 0.0))
            f.face(d, to.add(to.subtract(from)))
            if (k == n / 2) {
                cue("nivaris.roar", d.entityPos, 3f, 0.8f)
                HuntNet.fx(viewers, HuntFx.IMPACT, d.entityPos, a = 0.4f, life = 14)
            }
            yield(Unit)
        }
        wait(4)
    }

    /** Brüllen in der Wasserfall-Kaverne: Bildschock, dann fallen Eiszapfen (drei angesagte Ringe). */
    private fun fallRoar(c: HuntRoute.Cave, viewers: List<ServerPlayerEntity>) {
        val mid = point(HuntRoute.indexOfArc(c.a0 + c.length * 0.5)).add(0.0, c.theme.height.toDouble(), 0.0)
        cue("nivaris.roar", mid, 6f, 0.75f)
        vanilla(SoundEvents.ENTITY_WARDEN_ROAR, mid, 2f, 0.6f)
        HuntNet.fx(f.players().filter { it.squaredDistanceTo(mid) < 80.0 * 80.0 }, HuntFx.ROAR, mid, a = 1f, life = 40)
        f.calls.roomShake(mid, 5f, 40f)
        f.task(script {
            wait(14)
            val targets = viewers.filter { it.isAlive }.map { it.entityPos }.ifEmpty { listOf(mid.add(0.0, -c.theme.height.toDouble(), 0.0)) }
            val rings = targets.take(3).map { f.calls.ring(it, 2.2f, 26, pace = Telegraph.Pace.QUICK) }
            for (t in targets.take(3)) HuntNet.fx(f.players().filter { it.squaredDistanceTo(t) < 50.0 * 50.0 }, HuntFx.BOULDER, t.add(0.0, c.theme.height - 1.0, 0.0), t, a = 0.5f, b = 1f, life = 26)
            wait(26)
            vanilla(SoundEvents.BLOCK_GLASS_BREAK, mid, 3f, 0.6f)
            val hit = HashSet<ServerPlayerEntity>()
            for (r in rings) for (p in r.hits(f.players())) if (hit.add(p)) {
                exactHit(p, 4f, Damage.Kind.IMPACT)
                dev.henny.hha_arius.gear.Frostbite.add(p, 14f)
            }
            for (t in targets.take(3)) nearMiss(t, 4.0, hit)
        })
    }

    private val FILL_SPEED = 0.24

    /** Ihr Atem füllt die Seehöhle vom Eingang her: wer im Nebel ist, friert (1 Herz je Sekunde). */
    private fun tickFill(run: CaveRun, players: List<ServerPlayerEntity>) {
        val c = run.c
        if (f.now > run.fillUntil) {
            run.fillFront = -1.0
            return
        }
        run.fillFront = (run.fillFront + FILL_SPEED).coerceAtMost(c.a1)
        if (f.now % 20L != 0L) return
        for (p in players) {
            val i = denseIndex(p, HuntRoute.ENVELOPE - HuntRoute.HALF_W, 3.0)
            if (i < 0 || !c.contains(i) || HuntRoute.arc(i) > run.fillFront) continue
            exactHit(p, 2f, Damage.Kind.FROST_BREATH)
            dev.henny.hha_arius.gear.Frostbite.add(p, 14f)
            p.sendMessage(Text.translatable("hha_arius.hunt.fill").formatted(Formatting.AQUA), true)
        }
    }

    private fun inHazard(i: Int): Boolean {
        for (run in caves) if (run.fillFront >= 0 && run.c.contains(i) && HuntRoute.arc(i) <= run.fillFront) return true
        if (avalancheFront >= 0 && HuntRoute.arc(i) in avalancheFront - 8.0..avalancheFront) return true
        return false
    }

    /** Windtunnel: Böen gegen die Laufrichtung (über [HuntGuard] entlang der Route, nie seitlich). */
    private fun tickWind(players: List<ServerPlayerEntity>) {
        if (f.now % 5L != 0L) return
        for (p in players) {
            val i = denseIndex(p, 0.6, 1.6)
            if (i < 0) continue
            val c = HuntRoute.caveOf(i) ?: continue
            if (c.theme != HuntRoute.Theme.WIND) continue
            val gust = 0.08 + 0.06 * sin(f.now * 0.07)
            val back = tangentVec(i).multiply(-gust * (if (p.isSneaking) 0.4 else 1.0))
            // Auf die tatsächliche Bewegung aufsetzen (die Server-Geschwindigkeit eines Spielers ist veraltet), sonst
            // würde jede Böe den Schwung abwürgen.
            val moving = Vec3d(p.x - p.lastX, 0.0, p.z - p.lastZ)
            p.velocity = Vec3d(moving.x + back.x, if (p.isOnGround) 0.0 else p.y - p.lastY, moving.z + back.z)
            p.velocityDirty = true
        }
    }

    // --- Hängebrücken ----------------------------------------------------------------------------------------------------

    /** Deckzellen einer Hängebrücke (absolut) mit Bogenlänge, nach Bogenlänge sortiert; Geländer-Barrieren dazu. */
    private class Deck(val arc: Double, val pos: BlockPos, val rails: List<BlockPos>)

    private fun deckOf(span: HuntRoute.Span): List<Deck> {
        var minX = Int.MAX_VALUE; var maxX = Int.MIN_VALUE; var minZ = Int.MAX_VALUE; var maxZ = Int.MIN_VALUE
        for (j in span.i0..span.i1) {
            minX = minOf(minX, floor(HuntRoute.x(j)).toInt()); maxX = maxOf(maxX, floor(HuntRoute.x(j)).toInt())
            minZ = minOf(minZ, floor(HuntRoute.z(j)).toInt()); maxZ = maxOf(maxZ, floor(HuntRoute.z(j)).toInt())
        }
        val out = ArrayList<Deck>()
        val railMap = HashMap<Int, ArrayList<BlockPos>>()
        val cells = ArrayList<Pair<Int, BlockPos>>()
        for (x in minX - 4..maxX + 4) for (z in minZ - 4..maxZ + 4) {
            val h = HuntRoute.bridgeEntry(x, z)
            if (h < 0) continue
            val j = HuntRoute.hitIndex(h)
            if (j !in span.i0..span.i1) continue
            val u = HuntRoute.arc(j) - span.a0
            if (u < 3.0 || span.a1 - HuntRoute.arc(j) < 3.0) continue
            val lat = HuntRoute.hitLateral(h)
            val db = HuntRoute.deckBlock(j)
            if (lat <= HuntRoute.ROPE_HALF) cells += j to f.abs(BlockPos(x, db, z))
            else if (lat <= HuntRoute.ROPE_RAIL) railMap.getOrPut(j / 2) { ArrayList() }.addAll(listOf(f.abs(BlockPos(x, db + 1, z)), f.abs(BlockPos(x, db + 2, z))))
        }
        for ((j, pos) in cells) out += Deck(HuntRoute.arc(j), pos, railMap.remove(j / 2) ?: emptyList())
        for ((k, list) in railMap) out += Deck(HuntRoute.arc(k * 2), list.first(), list)
        out.sortBy { it.arc }
        return out
    }

    private val spans = HuntRoute.ropeSpans
    private val decks = HashMap<Int, List<Deck>>()

    private fun deck(span: HuntRoute.Span) = decks.getOrPut(span.id) { deckOf(span) }

    private var planksBroken = 0
    private var nextPlanks = 0L
    private var collapseFront = -1.0
    private var collapseDone = false
    private var rearStill = 0
    private var lastRear = 0.0

    /** Entfernt Deck (und Geländer) zwischen Bogenlänge [a0] und [a1] auf [span]; true, wenn etwas fiel. */
    private fun dropDeck(span: HuntRoute.Span, a0: Double, a1: Double, rails: Boolean): Boolean {
        var any = false
        for (d in deck(span)) {
            if (d.arc < a0 || d.arc > a1) continue
            val st = f.world.getBlockState(d.pos)
            if (dev.henny.hha_arius.portal.PortalBlocks.isDeck(st)) {
                f.edits.clear(d.pos)
                any = true
            }
            if (rails) for (r in d.rails) if (f.world.getBlockState(r).isOf(Blocks.BARRIER)) f.edits.clear(r)
        }
        return any
    }

    private fun tickBridges(players: List<ServerPlayerEntity>, leaderArc: Double, rearArc: Double) {
        val now = f.now
        val b1 = spans.firstOrNull() ?: return
        // B1: angekündigte Plankenbrüche vor dem Vordersten (Lücke 1,5 Blöcke, man springt darüber).
        if (planksBroken < 2 && now >= nextPlanks && leaderArc in b1.a0 + 8.0..b1.a1 - 18.0) {
            val g = leaderArc + 10.0 + rnd.nextDouble() * 3.0
            val clear = players.none { abs(arcOf(it) - g) < 4.0 }
            if (clear) {
                planksBroken++
                nextPlanks = now + 140
                val at = point(HuntRoute.indexOfArc(g))
                val near = players.filter { it.squaredDistanceTo(at) < 60.0 * 60.0 }
                HuntNet.fx(near, HuntFx.PLANKS_SHAKE, at, a = 2.5f, life = 40)
                vanilla(SoundEvents.ENTITY_CREAKING_SWAY, at, 2f, 0.6f)
                vanilla(SoundEvents.BLOCK_SCAFFOLDING_STEP, at, 2f, 0.5f)
                val dir = tangentVec(HuntRoute.indexOfArc(g))
                f.calls.line(at.subtract(dir.multiply(1.0)), at.add(dir.multiply(1.5)), 3.4f, 40, Telegraph.Pace.HEAVY)
                f.task(script {
                    wait(40)
                    if (dropDeck(b1, g - 0.75, g + 0.75, false)) {
                        vanilla(SoundEvents.BLOCK_BAMBOO_WOOD_BREAK, at, 3f, 0.6f)
                        vanilla(SoundEvents.ITEM_LEAD_BREAK, at, 2f, 0.8f)
                        HuntNet.fx(f.players().filter { it.squaredDistanceTo(at) < 60.0 * 60.0 }, HuntFx.PLANKS_FALL, at, dir, a = 3f, life = 40)
                    }
                })
            }
        }
        // B3: Einsturz hinter der Gruppe, sobald alle auf der Brücke sind.
        val b3 = spans.getOrNull(1) ?: return
        if (collapseDone) return
        if (collapseFront < 0) {
            if (rearArc >= b3.a0 + 8.0 && rearArc < b3.a1) {
                collapseFront = b3.a0 + 3.0
                lastRear = rearArc
                val at = point(b3.i0)
                cue("ridge.break", at, 5f, 0.5f)
                vanilla(SoundEvents.ITEM_LEAD_BREAK, at, 4f, 0.5f)
                HuntNet.fx(players, HuntFx.LETTERBOX, at, life = 70)
                HuntNet.fx(players, HuntFx.IMPACT, at, a = 0.6f, life = 16)
                for (p in players) p.sendMessage(Text.translatable("hha_arius.hunt.collapse").formatted(Formatting.GOLD, Formatting.BOLD), true)
            }
            return
        }
        rearStill = if (abs(rearArc - lastRear) < 0.05) rearStill + 1 else 0
        lastRear = rearArc
        val keep = if (rearStill > 60) 2.0 else 5.0
        val want = (rearArc - keep).coerceAtMost(b3.a1 - 3.0)
        val step = (want - collapseFront).coerceIn(0.0, 0.25)
        val before = collapseFront
        collapseFront += step
        if (floor(collapseFront) > floor(before)) {
            if (dropDeck(b3, before - 0.5, collapseFront, true)) {
                val at = point(HuntRoute.indexOfArc(collapseFront))
                if (floor(collapseFront).toInt() % 2 == 0) vanilla(SoundEvents.BLOCK_BAMBOO_WOOD_BREAK, at, 2.5f, 0.6f + rnd.nextFloat() * 0.3f)
                HuntNet.fx(players.filter { it.squaredDistanceTo(at) < 70.0 * 70.0 }, HuntFx.PLANKS_FALL, at, tangentVec(HuntRoute.indexOfArc(collapseFront)), a = 2f, life = 40)
            }
        }
        if (collapseFront >= b3.a1 - 3.1 || rearArc > b3.a1 + 10.0) collapseDone = true
    }

    // --- Lawine ---------------------------------------------------------------------------------------------------

    private var avalancheFront = -1.0
    private var avalancheEnd = 0.0
    private var avalancheDone = false
    private val hitAt = HashMap<UUID, Long>()

    /** Am Hang der Zweiten Wacht zwischen den Höhlen: eine Lawine folgt der Route von hinten. */
    private fun tickAvalanche(players: List<ServerPlayerEntity>, leaderArc: Double, rearArc: Double) {
        val crystal = HuntRoute.caves.firstOrNull { it.theme == HuntRoute.Theme.CRYSTAL } ?: return
        val fall = HuntRoute.caves.firstOrNull { it.theme == HuntRoute.Theme.FALL } ?: return
        val trigger = crystal.a1 + 10.0
        if (!avalancheDone && avalancheFront < 0 && leaderArc >= trigger && leaderArc < fall.a0) {
            avalancheDone = true
            avalancheFront = (rearArc - 26.0).coerceAtLeast(crystal.a1 + 1.0)
            avalancheEnd = fall.a0 - 1.0
            val at = point(HuntRoute.indexOfArc(avalancheFront)).add(0.0, 10.0, 0.0)
            cue("glacier.collapse", at, 6f, 0.5f)
            HuntNet.fx(players, HuntFx.RUMBLE, at, a = 1f, life = 50)
            HuntNet.fx(players, HuntFx.LETTERBOX, at, life = 60)
            HuntNet.fx(players, HuntFx.AVALANCHE, at, Vec3d(avalancheFront, avalancheEnd, 0.0), a = AVA_SPEED.toFloat(), life = ((avalancheEnd - avalancheFront) / AVA_SPEED).toInt() + 30)
            for (p in players) p.sendMessage(Text.translatable("hha_arius.hunt.avalanche").formatted(Formatting.WHITE, Formatting.BOLD), true)
        }
        if (avalancheFront < 0) return
        avalancheFront += AVA_SPEED
        if (avalancheFront >= avalancheEnd) {
            avalancheFront = -1.0
            return
        }
        if (f.now % 10L == 0L) cue("ridge.break", point(HuntRoute.indexOfArc(avalancheFront)), 4f, 0.5f)
        for (p in players) {
            val a = arcOf(p)
            if (a !in avalancheFront - 7.0..avalancheFront) continue
            val i = denseIndex(p, 3.0, 3.0)
            if (i < 0) continue
            if ((hitAt[p.uuid] ?: 0L) > f.now - 20) continue
            hitAt[p.uuid] = f.now
            exactHit(p, 5f, Damage.Kind.IMPACT)
            launch(p, tangentVec(i).multiply(0.55).add(0.0, 0.3, 0.0))
            p.addStatusEffect(StatusEffectInstance(StatusEffects.SLOWNESS, 20, 1, false, false, true))
        }
    }

    private val AVA_SPEED = 0.26

    // --- Ablauf -----------------------------------------------------------------------------------------------------

    /** Die ganze Jagd: Zorn-Szene, Verfolgung bis zum Tor. Danach übernimmt [arrive]. */
    fun run(): Script = script {
        val a = f.arius ?: return@script
        buildRoute()
        val rage = CineScene(f) { f.atmos(HUNT_ATMOS, 20) }
        yieldAll(rage.play(script { yieldAll(rageScene(rage, a)) }))
        f.chasing = true
        a.eyeGlow = 100
        a.form = AriusForm.WHITE
        a.bladeState = AriusBlade.ICE
        a.sheathed = false
        f.music("music.transform", 30)
        f.atmos(HUNT_ATMOS, 40)
        HuntNet.fx(f.audience(), HuntFx.STATE, a.entityPos, a = 1f, e1 = a.id)
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
        nextCut = f.now + 400
        try {
            while (true) {
                val players = f.players()
                if (players.isEmpty()) return@script
                val arrived = players.firstOrNull { it.entityPos.distanceTo(gate) <= 4.5 || (it.isOnGround && route.project(it.entityPos).let { (s, dd) -> s >= route.length - 1.5 && dd < 5.0 }) }
                if (arrived != null) {
                    arrivedFirst = arrived
                    break
                }
                guardPlayers(players)
                val progress = players.associateWith { route.project(it.entityPos).first }
                val leader = progress.maxByOrNull { it.value }!!.key
                val rear = progress.minByOrNull { it.value }!!.key
                val leaderArc = players.maxOf { arcOf(it) }
                val rearArc = players.minOf { arcOf(it) }
                tickCaves(players, leaderArc, rearArc)
                tickBridges(players, leaderArc, rearArc)
                tickAvalanche(players, leaderArc, rearArc)
                tickWind(players)
                if (f.now % 20L == 0L) HuntNet.fx(f.audience(), HuntFx.STATE, a.entityPos, a = 1f, e1 = a.id, life = 40)
                if (d != null) tickDragon(d, leader, progress[leader] ?: 0.0)
                if (!busy) chaseTick(a, rear, progress[rear] ?: 0.0, leader, progress[leader] ?: 0.0)
                yield(Unit)
            }
        } finally {
            f.chasing = false
            HuntNet.fx(f.audience(), HuntFx.STATE, a.entityPos, a = 0f)
        }
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
        // In einer Höhle und er ist dicht dahinter: er schneidet sich neben ihnen durch den Fels.
        if (now >= nextCut && d3 in 8.0..28.0) {
            val ti = denseIndex(t, 0.6, 1.6)
            val cave = if (ti >= 0) HuntRoute.caveOf(ti) else null
            val outside = cave != null && (cave.theme == HuntRoute.Theme.DARK || cave.theme == HuntRoute.Theme.CRYSTAL || cave.theme == HuntRoute.Theme.FALL)
            if (cave != null && outside && cave.u(ti) in 8.0..cave.length - 6.0) {
                nextCut = now + 500
                start { cutIn(a, t, ti, cave) }
                return
            }
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
        val hit = sameLevel(ring.hits(f.players()), to.y)
        for (p in hit) {
            exactHit(p, 6f, Damage.Kind.IMPACT)
            launch(p, flatDir(to, p.entityPos).multiply(0.6).add(0.0, 0.4, 0.0))
        }
        nearMiss(to, 6.0, hit)
        aS = route.project(to).first
        wait(6)
    }

    /**
     * Er schneidet sich neben ihnen durch die Talwand der Höhle: Sprung nach draußen neben das Ziel, eine leuchtende Naht
     * im Fels (Ansage), der Schnitt reißt ein Loch, er tritt hinter ihnen in den Gang.
     */
    private fun cutIn(a: AriusEntity, t: ServerPlayerEntity, ti: Int, cave: HuntRoute.Cave): Script = script {
        val i = (ti - 10).coerceAtLeast(cave.i0 + 16)
        val outside = ground(beside(i, 8.5, 2.0))
        yieldAll(leap(a, outside, t))
        val wallIn = beside(i, cave.theme.lane + 0.3, 1.0)
        val wallOut = beside(i, 7.0, 1.0)
        f.face(a, wallIn)
        a.play(AriusPose.CUT_CHARGE, 20)
        cue("cut.charge", outside, 4f, 1.0f)
        val near = f.players().filter { it.squaredDistanceTo(wallIn) < 60.0 * 60.0 }
        HuntNet.fx(near, HuntFx.ROCK_CUT, wallOut.add(0.0, 2.0, 0.0), wallIn.add(0.0, 2.0, 0.0), life = 24)
        val zone = f.calls.line(Vec3d(wallIn.x, point(i).y, wallIn.z), Vec3d(beside(i, -cave.theme.lane, 0.0).x, point(i).y, beside(i, -cave.theme.lane, 0.0).z), 3.0f, 22, Telegraph.Pace.HEAVY)
        wait(zone.ticks)
        a.play(AriusPose.SPIN_CUT, 10)
        cue("cut.strike", wallIn, 5f, 0.8f)
        // Loch in die Wand: 2 breit (entlang), 3 hoch, von der Gangkante bis nach draußen.
        val (tx, tz) = HuntRoute.tangent(i)
        val holes = ArrayList<BlockPos>()
        for (along in -1..1) for (k in 0..((7.0 - cave.theme.lane) * 2).toInt()) {
            val off = cave.theme.lane + 0.2 + k * 0.5
            val c = beside(i, off).add(tx * along * 0.8, 0.0, tz * along * 0.8)
            for (dy in 1..3) holes += BlockPos.ofFloored(c.x, point(i).y + dy - 0.5, c.z)
        }
        for (pos in holes.distinct()) {
            val st = f.world.getBlockState(pos)
            if (st.isAir || st.isOf(Blocks.BARRIER)) continue
            f.world.spawnParticles(BlockStateParticleEffect(ParticleTypes.BLOCK, st), pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, 3, 0.4, 0.4, 0.4, 0.12)
            f.edits.clear(pos)
        }
        if (!Cine.muted) {
            f.fx(FxKind.SLASH_ARC, wallIn.add(0.0, 1.0, 0.0), flatDir(outside, wallIn), a = 5f, b = 1f, life = 12)
            f.fx(FxKind.DEBRIS_CLOUD, wallIn.add(0.0, 1.0, 0.0), a = 4f, life = 30)
        }
        f.calls.roomShake(wallIn, 4f, 30f)
        val hit = sameLevel(zone.hits(f.players()), point(i).y)
        for (p in hit) {
            exactHit(p, 5f)
            launch(p, tangentVec(i).multiply(0.5).add(0.0, 0.3, 0.0))
        }
        HuntNet.fx(near, HuntFx.IMPACT, wallIn, a = 0.8f, life = 18)
        nearMiss(wallIn, 7.0, hit)
        wait(6)
        // hinein in den Gang
        val inside = ground(point(i).add(0.0, 0.5, 0.0))
        for (k in 1..6) { f.place(a, outside.lerp(inside, k / 6.0)); yield(Unit) }
        aS = route.project(inside).first
        a.play(AriusPose.IDLE)
        wait(8)
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
            val hit = sameLevel(cone.hits(f.players()), a.y)
            for (p in hit) {
                exactHit(p, 6f)
                if (k == 2) launch(p, dir.multiply(0.3).add(0.0, 0.7, 0.0))
            }
            nearMiss(a.entityPos.add(dir.multiply(2.5)), 4.5, hit)
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
            val hit = line.hits(f.players()).filter { abs(it.y - t.y) <= 4.0 }
            for (p in hit) exactHit(p, 6f)
            for (p in f.players()) if (p !in hit && abs(p.y - t.y) <= 4.0) {
                val q = p.entityPos
                val v = l1.subtract(l0)
                val tt = ((q.subtract(l0)).dotProduct(v) / v.lengthSquared().coerceAtLeast(1e-6)).coerceIn(0.0, 1.0)
                val c = l0.add(v.multiply(tt))
                if (Vec3d(q.x - c.x, 0.0, q.z - c.z).length() < 3.4) HuntNet.fx(p, HuntFx.NEAR_MISS, c, life = 14)
            }
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
        val hit = HashSet<ServerPlayerEntity>()
        for (line in lines) for (p in sameLevel(line.hits(f.players()), from.y, 4.0)) if (hit.add(p)) {
            exactHit(p, 6f)
            dev.henny.hha_arius.gear.Frostbite.add(p, 18f)
        }
        nearMiss(t.entityPos, 3.5, hit)
        a.play(AriusPose.IDLE)
        wait(8)
    }

    /**
     * Lawine aus der Flanke: Er springt über die Spieler hinweg an den Hang oberhalb der Route und schlägt in den Schnee;
     * drei Schneewalzen rollen quer über den Weg vor dem Vordersten (je 3 Herzen, wirft entlang der Route zurück).
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
            val hit = sameLevel(line.hits(f.players()), pt.y)
            for (p in hit) {
                exactHit(p, 6f, Damage.Kind.IMPACT)
                launch(p, dir.multiply(-0.7).add(0.0, 0.4, 0.0))
            }
            nearMiss(pt, 5.0, hit)
        }
        a.play(AriusPose.IDLE)
    }

    /**
     * Sturmstoß über eine Brücke (2 Herzen). Seit 1.10.0 bläst er der Länge nach gegen die Laufrichtung, nicht mehr quer
     * vom Steg.
     */
    private fun bridgeGale(a: AriusEntity, t: ServerPlayerEntity): Script = script {
        val (s, _) = route.project(t.entityPos)
        val dir = route.dir(s)
        val pt = Vec3d(t.x, t.y, t.z)
        f.face(a, pt)
        a.play(AriusPose.SPIN_CUT, 22)
        cue("cut.reverse_wind", a.entityPos, 3f, 1.2f)
        val line = f.calls.line(pt.subtract(dir.multiply(7.0)), pt.add(dir.multiply(7.0)), 3.4f, 22, Telegraph.Pace.HEAVY)
        wait(line.ticks)
        cue("cut.strike", pt, 3f, 1.3f)
        vanilla(SoundEvents.ENTITY_BREEZE_WIND_BURST.value(), pt, 2f, 0.7f)
        if (!Cine.muted) f.fx(FxKind.GALE, pt.add(dir.multiply(5.0)).add(0.0, 1.0, 0.0), dir.multiply(-10.0), a = 1f, life = 14)
        val hit = sameLevel(line.hits(f.players()), pt.y)
        for (p in hit) {
            exactHit(p, 4f)
            launch(p, dir.multiply(-0.9).add(0.0, 0.3, 0.0))
        }
        nearMiss(pt, 5.0, hit)
        a.play(AriusPose.IDLE)
        wait(8)
    }

    /** Der gefrorene Stern in der Jagd: über der Route vor dem Vordersten, kleiner, aber 5 Herzen. */
    private fun chaseStar(a: AriusEntity, leader: ServerPlayerEntity, sL: Double): Script = script {
        val spot = route.at(sL + 8.0)
        a.play(AriusPose.CORE_RAISE)
        val ticks = 54
        for (p in f.audience()) Cine.title(listOf(p), "hha_arius.feat.frozen_star", "hha_arius.feat.frozen_star.sub", 40)
        HuntNet.fx(f.players(), HuntFx.LETTERBOX, spot, life = ticks + 10)
        f.fxAll(FxKind.FROZEN_STAR, spot, a = 7f, b = ticks.toFloat(), life = ticks + 40)
        cue("core.form", spot.add(0.0, 30.0, 0.0), 5f, 0.6f)
        val ring = f.calls.ring(spot, 7f, ticks)
        wait(ring.ticks)
        cue("glacier.collapse", spot, 6f, 1.2f)
        f.calls.roomShake(spot, 6f, 40f)
        val hit = sameLevel(ring.hits(f.players()), spot.y)
        for (p in hit) {
            exactHit(p, 10f, Damage.Kind.IMPACT)
            dev.henny.hha_arius.gear.Frostbite.add(p, 40f)
        }
        nearMiss(spot, 10.0, hit)
        for (p in f.players()) if (p.squaredDistanceTo(spot) < 30.0 * 30.0) HuntNet.fx(p, HuntFx.IMPACT, spot, a = 0.6f, life = 16)
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
        // In Höhlen sieht man sie nicht: dann kein Anflug mit Atem (die Höhle hat ihre eigenen Auftritte).
        val li = denseIndex(leader, 1.0, 2.0)
        val inCave = li >= 0 && HuntRoute.kind(li) == HuntRoute.TUNNEL
        if (f.now >= nextDragon && !inCave) {
            dragonBusy = true
            nextDragon = f.now + 170 + rnd.nextInt(60)
            f.task(script {
                try { yieldAll(dragonStrafe(d, sL)) } finally { dragonBusy = false }
            })
        }
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
        val hit = f.players().filter { abs(it.y - p0.lerp(p1, 0.5).y) <= 6.0 && line.contains(it) }
        for (p in hit) {
            exactHit(p, 7f, Damage.Kind.FROST_BREATH)
            dev.henny.hha_arius.gear.Frostbite.add(p, 30f)
        }
        nearMiss(p0.lerp(p1, 0.5), 9.0, hit)
        wait(16)
        d.play(NivarisPose.GLIDE)
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
        f.atmos(HUNT_ATMOS, 30)
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
        HuntNet.fx(f.audience(), HuntFx.STATE, a.entityPos, a = 0f)
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

    companion object {
        /**
         * Stimmung der Jagd (seit 1.10.0, nur am Client ausgewertet, siehe Atmos): Whiteout, aber nach Ort – leicht auf
         * dem Feld, voll ab dem Fuß der Berge, in Höhlen dunkel und still.
         */
        const val HUNT_ATMOS = 21
    }
}
