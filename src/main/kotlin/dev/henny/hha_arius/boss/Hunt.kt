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
 *
 * Seit 1.11.0:
 * * **Arius hat eine feste Route:** Er läuft auf der Wegmitte der dichten Punktfolge und springt nur zwischen geprüften
 *   Landepunkten ([HuntRoute.stations], [dev.henny.hha_arius.arena.RouteCheck]), der Sprung folgt dem Weg. Früher lief er
 *   auf Sehnen der groben Wegpunkte und sprang frei an den Hang, dabei landete er im Berg.
 * * **Solvane fliegt auf einer festen Begleitbahn** ([DragonRail]) außen und über dem Gelände. Früher zog sie als
 *   gedämpfter Punkt neben der Route her, mitten durch die Dritte Wacht, während die Spieler im Tunnel waren.
 * * **Hängebrücken:** Morsche Planken brechen unter dem Fuß. Am fernen Kopf kann man die Seile kappen, sobald alle
 *   drüben sind; steht Arius auf der Brücke, stürzt er, nimmt echten Schaden, und Solvane trägt ihn wieder hinauf.
 * * **Kurze Szenen** (je einmal, überspringbar): ihr Auge im Schlitz, das Geröll, Solvane neben dir im Tunnel.
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

    /** Bogenlänge von Arius auf der dichten Route ([HuntRoute]). */
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

    /** Punkt der dichten Route zur Bogenlänge [a] (Wegmitte, Lauffläche), absolut. */
    private fun pathAt(a: Double): Vec3d {
        val i = HuntRoute.indexOfArc(a)
        val ai = HuntRoute.arc(i)
        val j = (if (ai <= a) i + 1 else i - 1).coerceIn(0, HuntRoute.size - 1)
        val aj = HuntRoute.arc(j)
        val k = if (abs(aj - ai) < 1e-6) 0.0 else ((a - ai) / (aj - ai)).coerceIn(0.0, 1.0)
        return point(i).lerp(point(j), k)
    }

    private fun tangentAt(a: Double): Vec3d = tangentVec(HuntRoute.indexOfArc(a))

    // --- Kurze Szenen -------------------------------------------------------------------------------------------------

    private var pendingScene: (() -> Script)? = null
    private var pendingUntil = 0L
    private var inScene = false
    private val sceneDone = HashSet<String>()

    /** Merkt eine einmalige kurze Szene vor; sie beginnt, sobald kein Angriff mehr läuft (spätestens nach 2,5 s). */
    private fun queueScene(id: String, wait: Int = 50, scene: () -> Script): Boolean {
        if (pendingScene != null || inScene || id in sceneDone) return false
        sceneDone += id
        pendingScene = scene
        pendingUntil = f.now + wait
        return true
    }

    /** Kurze Szene (überspringbar, Eingaben gesperrt, kein Schaden). */
    private fun mini(onAbort: () -> Unit = {}, body: CineScene.() -> Script): Script = script {
        val scene = CineScene(f, onAbort)
        yieldAll(scene.play(scene.body()))
    }

    /** Kamera ohne Anheben über das Gelände (für Einstellungen in Höhlen; [CineScene.line] würde sie aus dem Berg heben). */
    private fun CineScene.cam(
        from: Vec3d, to: Vec3d, lookFrom: Vec3d, lookTo: Vec3d, ticks: Int, fov0: Double, fov1: Double,
        lookAt: net.minecraft.entity.Entity? = null, lookOffset: Double = 1.6,
    ) = rawLine(from, to, lookFrom, lookTo, ticks, fov0, fov1, lookAt, lookOffset)

    private fun airAt(q: Vec3d): Boolean {
        val pos = BlockPos.ofFloored(q)
        val st = f.world.getBlockState(pos)
        return st.getCollisionShape(f.world, pos).isEmpty && !st.isOpaqueFullCube
    }

    /** Liegt ein Spieler auf der Sichtlinie von [q] nach [target]? */
    private fun playerInView(q: Vec3d, target: Vec3d): Boolean {
        val v = target.subtract(q)
        val len2 = v.lengthSquared().coerceAtLeast(1e-6)
        return f.players().any { p ->
            val c = p.entityPos.add(0.0, 1.0, 0.0)
            val k = c.subtract(q).dotProduct(v) / len2
            k in 0.0..1.0 && q.add(v.multiply(k)).squaredDistanceTo(c) < 1.6 * 1.6
        }
    }

    /**
     * Bester Kamerapunkt aus [spots]: in der Luft, mindestens 3 Blöcke von jedem Spieler (sonst schaut man in dessen Kopf),
     * freie Sicht auf [target] und kein Spieler davor. Sonst der erste, der wenigstens in der Luft liegt.
     */
    private fun freeCam(spots: List<Vec3d>, target: Vec3d): Vec3d = spots.firstOrNull { q ->
        airAt(q) && f.players().all { it.eyePos.squaredDistanceTo(q) > 9.0 && it.entityPos.squaredDistanceTo(q) > 9.0 } &&
            !playerInView(q, target) &&
            f.world.raycast(net.minecraft.world.RaycastContext(q, target, net.minecraft.world.RaycastContext.ShapeType.COLLIDER, net.minecraft.world.RaycastContext.FluidHandling.NONE, net.minecraft.block.ShapeContext.absent())).type == net.minecraft.util.hit.HitResult.Type.MISS
    } ?: spots.firstOrNull { airAt(it) } ?: spots.first()

    /** Prüft eine kleine 3D-Hülle um [q], damit Höhlenkameras nie in Wand, Decke oder Boden schneiden. */
    private fun clearAir(q: Vec3d, pad: Double = 0.7): Boolean {
        if (!airAt(q)) return false
        return airAt(q.add(pad, 0.0, 0.0)) && airAt(q.add(-pad, 0.0, 0.0)) &&
            airAt(q.add(0.0, pad, 0.0)) && airAt(q.add(0.0, -pad, 0.0)) &&
            airAt(q.add(0.0, 0.0, pad)) && airAt(q.add(0.0, 0.0, -pad))
    }

    /** Wählt eine garantiert wandfreie Kameraposition im Tunnel bei Bogenlänge [arc], bevorzugt auf Seite [preferOut] und Höhe [up]. */
    private fun safeTunnelCam(arc: Double, preferOut: Double, up: Double): Vec3d {
        val i = HuntRoute.indexOfArc(arc)
        val candidates = listOf(
            beside(i, preferOut, up),
            beside(i, preferOut * 0.65, up),
            beside(i, preferOut * 0.4, (up - 0.3).coerceAtLeast(1.3)),
            beside(i, 0.0, up.coerceIn(1.4, 3.0)),
            beside(i, -preferOut * 0.5, 1.8),
        )
        return candidates.firstOrNull { clearAir(it, 0.65) } ?: candidates.firstOrNull { airAt(it) } ?: beside(i, 0.0, 1.8)
    }

    /**
     * Ihr Auge im Felsspalt der dunklen Höhle: Die Kamera steht an der Bergwand genau gegenüber, der Spalt liegt von Anfang
     * an mitten im Bild und ist schwarz. Sie drückt langsam heran, während sich dahinter das Lid hebt.
     */
    private fun eyeScene(c: HuntRoute.Cave, u: Double): Script = mini {
        script {
            val i = HuntRoute.indexOfArc(c.a0 + u)
            val slit = beside(i, 6.2, 2.55)
            val camFrom = safeTunnelCam(c.a0 + u - 0.9, -2.3, 2.1)
            val camTo = safeTunnelCam(c.a0 + u - 0.5, -1.5, 2.3)
            HuntNet.fx(f.audience(), HuntFx.LETTERBOX, slit, life = 92)
            HuntNet.fx(f.audience(), HuntFx.RUMBLE, slit, a = 0.22f, life = 30)
            cam(camFrom, camTo, slit, slit, 84, 66.0, 56.0)
            wait(6)
            eye(c, u, f.players(), 1.0f, out = 6.2)
            wait(76)
            home(12)
            wait(12)
        }
    }

    /** Geröll verschließt den Gang: Blick von vorn auf die fallenden Steine. */
    private fun sealScene(run: CaveRun): Script = mini(onAbort = { if (!run.sealed) sealInstant(run, f.players()) }) {
        script {
            val c = run.c
            val mid = pathAt(c.a0 + (c.plugFrom + c.plugTo) / 2).add(0.0, 1.8, 0.0)
            val lat = (c.theme.lane - 0.7).coerceAtLeast(0.4)
            val spots = listOf(7.0, 10.0, 12.0, 5.0).filter { c.plugTo + it < c.length - 1.0 }.flatMap { du ->
                val q = pathAt(c.a0 + c.plugTo + du)
                val t = tangentAt(c.a0 + c.plugTo + du)
                val s = Vec3d(-t.z, 0.0, t.x).multiply(lat)
                listOf(q.add(s).add(0.0, 1.6, 0.0), q.subtract(s).add(0.0, 1.6, 0.0))
            }
            val from = freeCam(spots.ifEmpty { listOf(pathAt(c.a0 + c.plugTo + 3.5).add(0.0, 1.6, 0.0)) }, mid)
            cam(from, from.lerp(mid, 0.18), mid.add(0.0, 1.0, 0.0), mid, 46, 66.0, 54.0)
            cue("glacier.crack", mid, 4f, 0.6f)
            HuntNet.fx(f.audience(), HuntFx.RUMBLE, mid, a = 0.8f, life = 24)
            wait(8)
            if (!run.collapsing && !run.sealed) startCollapse(run)
            wait(26)
            home(12)
            wait(12)
        }
    }

    /**
     * Seit 1.12.0 fliegt sie nicht mehr durch den Gang (ihr Leib passt nicht hinein, dabei schnitt er durch Fels und Kamera):
     * Aus der Höhle sieht man durch den Eingang, wie sie draußen knapp vorbeischießt. Die Bahn liegt frei über dem Gelände.
     * Danach füllt ihr Atem die Höhle vom Eingang her ([scare]).
     */
    private fun tunnelScene(d: NivarisEntity, c: HuntRoute.Cave): Script = script {
        dragonBusy = true
        try {
            yieldAll(mini(onAbort = { f.atmos(HUNT_ATMOS, 16) }) {
                script {
                    f.atmos(AtmosMode.SNOW, 6)
                    val i0 = HuntRoute.indexOfArc(c.a0 + 1.0)
                    val mouth = pathAt(c.a0)
                    val valley = beside(i0, 1.0).subtract(point(i0)).let { Vec3d(it.x, 0.0, it.z) }
                    val out = tangentVec(i0).negate().add(valley.multiply(0.55)).normalize()
                    val side = Vec3d(-out.z, 0.0, out.x)
                    val raw = (-3..3).map { k ->
                        val e = k / 3.0
                        mouth.add(out.multiply(19.0 + 11.0 * e * e)).add(side.multiply(k * 17.0))
                    }
                    val y = maxOf(mouth.y + 7.0, raw.maxOf { terrainTop(it, 7) + 7.0 })
                    val wps = raw.map { Vec3d(it.x, y, it.z) }
                    f.place(d, wps.first())
                    f.face(d, wps[1])
                    d.flying = true
                    d.play(NivarisPose.GLIDE)
                    val speed = 1.7
                    val pass = (102.0 / speed).toInt() + 4
                    val camA = freeCam(listOf(safeTunnelCam(c.a0 + 1.6, 0.0, 1.7), mouth.add(out.multiply(1.5)).add(0.0, 1.8, 0.0), mouth.add(out.multiply(4.0)).add(0.0, 2.6, 0.0)), wps[3])
                    val camB = camA.lerp(mouth.add(out.multiply(3.0)).add(0.0, 2.2, 0.0), 0.35).takeIf { airAt(it) } ?: camA
                    HuntNet.fx(f.audience(), HuntFx.LETTERBOX, camA, life = pass + 24)
                    HuntNet.fx(f.audience(), HuntFx.RUMBLE, camA, a = 0.45f, life = pass)
                    cam(camA, camB, wps[2].add(0.0, 1.0, 0.0), wps[4].add(0.0, 1.0, 0.0), pass, 72.0, 62.0, lookAt = d, lookOffset = 2.0)
                    cue("nivaris.wing", mouth, 4f, 0.6f)
                    var n = 0
                    for (step in DragonRail.fly(f, d, wps, speed)) {
                        n++
                        if (n == pass / 2) {
                            cue("nivaris.roar", mouth, 5f, 0.8f)
                            HuntNet.fx(f.audience(), HuntFx.NEAR_MISS, mouth, life = 16)
                        }
                        yield(step)
                    }
                    f.atmos(HUNT_ATMOS, 16)
                    home(12)
                    wait(12)
                }
            })
            d.play(NivarisPose.GLIDE)
            yieldAll(rejoinRail(d))
        } finally {
            dragonBusy = false
        }
    }

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
        if (inScene) return
        val a = f.arius ?: return
        val red = Damage.reduction(p)
        Damage.hit(p, a, kind, (amount / (1.0 - red).coerceAtLeast(0.05)).toFloat(), 1f)
        HuntNet.fx(p, HuntFx.IMPACT, p.entityPos, a = (amount / 10f).coerceIn(0.25f, 1f), life = 12)
    }

    /** Stoß entlang der Route (nie zum Abhang, siehe [HuntGuard]). */
    private fun launch(p: ServerPlayerEntity, v: Vec3d) {
        if (inScene) return
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

    /** Seit 1.10.0: Ist Arius nah, legt sich für diesen Spieler die Spannungsspur über die Jagdmusik. */
    private val closeMusic = HashSet<UUID>()

    private fun huntMusic(players: List<ServerPlayerEntity>) {
        val a = f.arius ?: return
        for (p in players) {
            val d = p.squaredDistanceTo(a)
            if (p.uuid !in closeMusic && d < 11.0 * 11.0) {
                closeMusic.add(p.uuid)
                MusicCue.play(p, "hunt_close", 16)
            } else if (p.uuid in closeMusic && d > 18.0 * 18.0) {
                closeMusic.remove(p.uuid)
                MusicCue.play(p, "hunt", 40)
            }
        }
    }

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
        var warned = false
        var collapsing = false
        var sealed = false
        var opened = false
        var scared = false
        var eyeCueActive = false
        var eyeHinted = false
        var eyeSoundAt = 0L
        val eyeGaze = HashMap<UUID, Int>()
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
            // Jede Höhle wird zuverlässig scharf geschaltet, sobald der Vorderste sie betritt (auch wenn eine Szene dazwischen lief).
            if (run.armedAt < 0 && leaderArc >= c.a0 - 2.0) {
                run.armedAt = now
                for (p in players) if (abs(arcOf(p) - c.a0) < 42.0) p.sendMessage(Text.translatable("hha_arius.hunt.cave.${c.theme.name.lowercase()}").formatted(Formatting.GRAY, Formatting.ITALIC), true)
            }
            if (run.armedAt < 0) continue
            val age = now - run.armedAt
            // 1) Grollen & Vorwarnung (~4 s nach Betreten, nie über exact == übersprungen): Zittern, Staub, erste fallende Steine.
            if (!run.warned && age >= 80L && !run.collapsing && !run.sealed) {
                run.warned = true
                val mid = point(HuntRoute.indexOfArc(c.a0 + (c.plugFrom + c.plugTo) / 2))
                val near = players.filter { it.squaredDistanceTo(mid) < 70.0 * 70.0 }
                HuntNet.fx(near, HuntFx.RUMBLE, mid.add(0.0, 2.0, 0.0), a = 0.85f, life = 60)
                cue("glacier.crack", mid, 4f, 0.6f)
                vanilla(SoundEvents.AMBIENT_CAVE.value(), mid, 3f, 0.5f)
                val a = point(HuntRoute.indexOfArc(c.a0 + c.plugFrom - 0.5))
                val b = point(HuntRoute.indexOfArc(c.a0 + c.plugTo + 0.5))
                f.calls.line(a, b, (c.theme.lane * 2 + 0.6).toFloat(), 55, Telegraph.Pace.HEAVY)
                for (p in near) p.sendMessage(Text.translatable("hha_arius.hunt.rumble").formatted(Formatting.GOLD), true)
            }
            // Sichtbare Vorläufer-Steine rieseln von der Decke herab, bevor die Höhle komplett einstürzt.
            if (run.warned && !run.collapsing && !run.sealed && now % 6L == 0L) {
                val i = HuntRoute.indexOfArc(c.a0 + c.plugFrom + rnd.nextDouble() * (c.plugTo - c.plugFrom))
                val base = point(i)
                val ox = (rnd.nextDouble() - 0.5) * (c.theme.lane * 1.4)
                val oz = (rnd.nextDouble() - 0.5) * (c.theme.lane * 1.4)
                val top = base.add(ox, c.theme.height + 0.4, oz)
                val hit = base.add(ox, 0.2, oz)
                val near = players.filter { it.squaredDistanceTo(top) < 65.0 * 65.0 }
                HuntNet.fx(near, HuntFx.BOULDER, top, hit, a = 0.45f + rnd.nextFloat() * 0.35f, life = 9)
                f.world.spawnParticles(BlockStateParticleEffect(ParticleTypes.FALLING_DUST, Blocks.STONE.defaultState), top.x, top.y, top.z, 10, 1.2, 0.2, 1.2, 0.0)
                f.world.spawnParticles(BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.COBBLESTONE.defaultState), top.x, top.y, top.z, 6, 0.8, 0.1, 0.8, 0.05)
                vanilla(SoundEvents.BLOCK_POINTED_DRIPSTONE_FALL, top, 1.3f, 0.7f)
            }
            // Einmal als kurze Szene, sobald jemand in der Nähe ist.
            if (age >= 118L && !run.collapsing && !run.sealed && "seal" !in sceneDone) {
                val plug = pathAt(c.a0 + c.plugTo)
                if (players.any { it.squaredDistanceTo(plug) < 28.0 * 28.0 }) queueScene("seal") { sealScene(run) }
            }
            // 2) Jede Höhle schließt nach fester Zeit zuverlässig durch echte fallende Steine (auch wenn eine andere Szene vorgemerkt war).
            if (age >= 135L && !run.collapsing && !run.sealed) startCollapse(run)
            // Solvane fliegt einmal durch die lange Höhle der Dritten Wacht, neben den Spielern her.
            if (c.theme == HuntRoute.Theme.LAKE && "tunnel" !in sceneDone && !dragonBusy && leaderArc - c.a0 in 3.0..c.length * 0.6) {
                f.nivaris?.takeIf { !f.dragonDowned && !it.isRemoved }?.let { d -> queueScene("tunnel") { tunnelScene(d, c) } }
            }
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

    /**
     * Zellen des Geröllpfropfens. Bleibt mit ihnen kein begehbarer Umweg (Nebengang), bleibt in der Wegmitte eine schmale
     * Lücke im Geröll: Eine Höhle schließt immer, aber niemand wird eingesperrt.
     */
    private fun plugCells(c: HuntRoute.Cave): List<BlockPos> {
        val cells = caveCells(c, c.plugFrom, c.plugTo, c.theme.lane + 0.3, 1, c.theme.height)
        if (cells.isEmpty() || keepsWay(c, cells.toHashSet())) return cells
        val gap = caveCells(c, c.plugFrom - 0.5, c.plugTo + 0.5, 0.9, 1, 3).toHashSet()
        return cells.filter { it !in gap }
    }

    /** Führt trotz [plug] ein Weg (2 hoch, Stufen bis 1) von vor dem Geröll dahinter? */
    private fun keepsWay(c: HuntRoute.Cave, plug: Set<BlockPos>): Boolean {
        val w = f.world
        fun low(p: BlockPos): Boolean {
            if (p in plug) return false
            val shape = w.getBlockState(p).getCollisionShape(w, p)
            return shape.isEmpty || shape.getMax(Direction.Axis.Y) <= 0.5
        }
        fun stand(p: BlockPos) = low(p) && p.up() !in plug && w.getBlockState(p.up()).getCollisionShape(w, p.up()).isEmpty &&
            !w.getBlockState(p.down()).getCollisionShape(w, p.down()).isEmpty
        val a = pathAt(c.a0 + 1.0)
        val b = pathAt(c.a0 + c.plugTo + 2.0)
        val start = BlockPos.ofFloored(a.x, a.y + 0.05, a.z)
        val goal = BlockPos.ofFloored(b.x, b.y + 0.05, b.z)
        val seen = hashSetOf(start)
        val queue = ArrayDeque(listOf(start))
        while (queue.isNotEmpty() && seen.size < 20000) {
            val p = queue.removeFirst()
            if (p.getSquaredDistance(goal) <= 4.0) return true
            for (dx in -1..1) for (dz in -1..1) for (dy in -1..1) {
                if ((dx == 0) == (dz == 0)) continue
                val q = p.add(dx, dy, dz)
                if (abs(q.x - start.x) > 34 || abs(q.z - start.z) > 34 || q in seen || !stand(q)) continue
                seen.add(q)
                queue.addLast(q)
            }
        }
        return false
    }

    private fun rubbleState(pos: BlockPos): net.minecraft.block.BlockState {
        val r = FarPlan.h32(pos.x, pos.y, pos.z, 1501)
        return when {
            r < 0.35 -> Blocks.COBBLESTONE
            r < 0.6 -> Blocks.STONE
            r < 0.8 -> Blocks.ANDESITE
            else -> Blocks.TUFF
        }.defaultState
    }

    private fun pushOutOfPlug(c: HuntRoute.Cave, cells: List<BlockPos>, players: List<ServerPlayerEntity>) {
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
    }

    /**
     * Stufenweiser Höhleneinsturz mit sichtbaren fallenden Steinen: Große Felsbrocken stürzen aus der Decke herab und
     * türmen Schicht für Schicht von unten nach oben das Geröll auf, bis der Hauptgang komplett verschlossen ist.
     */
    private fun startCollapse(run: CaveRun) {
        if (run.collapsing || run.sealed) return
        run.collapsing = true
        val c = run.c
        val cells = plugCells(c)
        if (cells.isEmpty()) {
            run.sealed = true
            return
        }
        val mid = point(HuntRoute.indexOfArc(c.a0 + (c.plugFrom + c.plugTo) / 2))
        val byY = cells.groupBy { it.y }.toSortedMap()
        val ceilY = (byY.keys.maxOrNull() ?: floor(mid.y).toInt()) + 1.4
        val placed = ArrayList<BlockPos>()
        f.task(script {
            cue("glacier.collapse", mid, 5f, 0.68f)
            f.calls.roomShake(mid, 4.5f, 45f)
            val layers = byY.values.toList()
            for ((idx, layer) in layers.withIndex()) {
                if (run.sealed) return@script
                val players = f.players()
                val near = players.filter { it.squaredDistanceTo(mid) < 75.0 * 75.0 }
                // Vor jeder Schicht stürzen sichtbare Felsbrocken von der Höhlendecke genau auf die Zielblöcke herab.
                val drops = minOf(6, layer.size.coerceAtLeast(1))
                for (k in 0 until drops) {
                    val targetPos = layer[rnd.nextInt(layer.size)]
                    val target = Vec3d.ofCenter(targetPos)
                    val from = Vec3d(target.x + (rnd.nextDouble() - 0.5) * 0.6, maxOf(ceilY, target.y + 1.8), target.z + (rnd.nextDouble() - 0.5) * 0.6)
                    HuntNet.fx(near, HuntFx.BOULDER, from, target, a = 0.85f + rnd.nextFloat() * 0.55f, life = 5 + (k % 3))
                }
                wait(3)
                if (run.sealed) return@script
                pushOutOfPlug(c, layer, f.players())
                for (pos in layer) {
                    val cur = f.world.getBlockState(pos)
                    if (!cur.isOf(Blocks.SNOW) && !cur.getCollisionShape(f.world, pos).isEmpty) continue
                    f.edits.set(pos, rubbleState(pos))
                    placed += pos
                }
                run.plug = placed.toList()
                vanilla(SoundEvents.BLOCK_STONE_BREAK, mid.add(0.0, idx.toDouble(), 0.0), 2.4f, 0.55f + rnd.nextFloat() * 0.15f)
                f.world.spawnParticles(BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.COBBLESTONE.defaultState), mid.x, mid.y + 0.5 + idx, mid.z, 26, 1.8, 0.6, 1.8, 0.12)
            }
            run.sealed = true
            val near = f.players().filter { it.squaredDistanceTo(mid) < 75.0 * 75.0 }
            vanilla(SoundEvents.ENTITY_GENERIC_EXPLODE.value(), mid, 2.2f, 0.5f)
            HuntNet.fx(near, HuntFx.IMPACT, mid, a = 0.7f, life = 16)
            for (p in f.players()) if (arcOf(p) < c.a0 + c.plugFrom) p.sendMessage(Text.translatable("hha_arius.hunt.sealed").formatted(Formatting.GOLD, Formatting.BOLD), true)
        })
    }

    /** Sofortiger Verschluss (nur bei Abbruch/Überspringen der Geröllszene). */
    private fun sealInstant(run: CaveRun, players: List<ServerPlayerEntity>) {
        val c = run.c
        run.collapsing = true
        run.sealed = true
        val cells = plugCells(c)
        if (cells.isEmpty()) return
        pushOutOfPlug(c, cells, players)
        val placed = ArrayList<BlockPos>(run.plug)
        for (pos in cells) {
            val cur = f.world.getBlockState(pos)
            if (!cur.isOf(Blocks.SNOW) && !cur.getCollisionShape(f.world, pos).isEmpty) continue
            f.edits.set(pos, rubbleState(pos))
            placed += pos
        }
        run.plug = placed.distinct()
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
            HuntRoute.Theme.DARK -> {
                val w = c.windows.firstOrNull() ?: 11.0
                if (c.i0 > HuntRoute.size / 2) {
                    if (u >= w - 7.0) {
                        run.scared = true
                        val w2 = c.windows.firstOrNull { it > u - 2.0 } ?: c.windows.last()
                        crackBreath(c, w2, inCave)
                    }
                } else {
                    tickEyeLook(run, c, w, inCave, u)
                }
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
            HuntRoute.Theme.LAKE -> if ((rearArc >= c.a0 + 3.0 || u >= 22.0) && pendingScene == null && ("tunnel" in sceneDone || u > c.length * 0.6)) {
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

    /**
     * Blick-Auslöser für Solvanes Auge in der dunklen Höhle (1.13.0, Paket C):
     * 1. Sobald jemand die Höhle betritt, lenken deutliche eisblaue Lichtbahnen, Frostnebel und tiefes Atemgeräusch
     *    direkt aus dem Felsspalt ([HuntFx.EYE_CUE]) sowie ein dezenter Hinweis den Blick genau auf den Spalt.
     * 2. Die Szene startet **erst**, wenn ein Spieler im Blickbereich wirklich auf den Spalt schaut (Blickwinkel ≤ 24°
     *    und freie Sichtlinie über ~5 Ticks).
     * 3. **Fallback:** Läuft die Gruppe einfach weiter, ohne hinzuschauen (Bogenlänge deutlich hinter dem Spalt),
     *    erlischt der Hinweis geräuschlos und der Fortschritt hängt niemals von der Szene ab.
     */
    private fun tickEyeLook(run: CaveRun, c: HuntRoute.Cave, w: Double, inCave: List<ServerPlayerEntity>, leaderU: Double) {
        val i = HuntRoute.indexOfArc(c.a0 + w)
        val slitInner = beside(i, c.theme.lane + 0.35, 2.55)
        val slitDeep = beside(i, 6.2, 2.55)
        val into = beside(i, 0.0, 2.0).subtract(slitInner).normalize()

        // Fallback: Gruppe ist weit am Spalt vorbeigelaufen, ohne hinzuschauen -> nichts blockieren, Hinweis beenden.
        if (leaderU >= w + 8.5 || leaderU >= c.length - 1.5) {
            if (run.eyeCueActive) {
                HuntNet.fx(f.audience(), HuntFx.EYE_CUE, slitInner, into, life = 0)
                run.eyeCueActive = false
            }
            run.scared = true
            return
        }

        // In-World-Hinweis aktivieren und am Leben halten, solange jemand in der Höhle ist.
        if (leaderU >= 0.5) {
            if (!run.eyeCueActive || f.now % 50L == 0L) {
                run.eyeCueActive = true
                HuntNet.fx(f.audience(), HuntFx.EYE_CUE, slitInner, into, life = 75)
            }
            if (f.now >= run.eyeSoundAt) {
                run.eyeSoundAt = f.now + 38L
                cue("nivaris.inhale", slitInner, 2.4f, 0.55f)
                vanilla(SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, slitInner, 1.3f, 0.5f)
            }
            if (!run.eyeHinted && leaderU >= 3.5) {
                run.eyeHinted = true
                for (p in inCave) p.sendMessage(Text.translatable("hha_arius.hunt.eye_cue").formatted(Formatting.AQUA, Formatting.ITALIC), true)
            }
        }

        // Echte Blickprüfung: schaut ein Spieler im Bereich um das Fenster gezielt in den Felsspalt?
        val cosMin = kotlin.math.cos(Math.toRadians(24.0))
        for (p in inCave) {
            val pu = arcOf(p) - c.a0
            if (pu !in (w - 9.0)..(w + 5.5)) {
                run.eyeGaze.remove(p.uuid)
                continue
            }
            val eyePos = p.eyePos
            val look = p.rotationVector.normalize()
            val dInner = slitInner.subtract(eyePos)
            val dDeep = slitDeep.subtract(eyePos)
            val dotInner = if (dInner.lengthSquared() > 1e-4) look.dotProduct(dInner.normalize()) else 0.0
            val dotDeep = if (dDeep.lengthSquared() > 1e-4) look.dotProduct(dDeep.normalize()) else 0.0
            val looking = maxOf(dotInner, dotDeep) >= cosMin
            val clearLos = if (looking) {
                val hit = f.world.raycast(
                    net.minecraft.world.RaycastContext(
                        eyePos, slitInner.add(into.multiply(0.45)),
                        net.minecraft.world.RaycastContext.ShapeType.VISUAL,
                        net.minecraft.world.RaycastContext.FluidHandling.NONE,
                        p,
                    ),
                )
                hit.type == net.minecraft.util.hit.HitResult.Type.MISS
            } else {
                false
            }
            val held = if (looking && clearLos) (run.eyeGaze[p.uuid] ?: 0) + 1 else maxOf(0, (run.eyeGaze[p.uuid] ?: 0) - 1)
            run.eyeGaze[p.uuid] = held
            if (held >= 5) {
                run.scared = true
                run.eyeGaze.clear()
                if (run.eyeCueActive) {
                    HuntNet.fx(f.audience(), HuntFx.EYE_CUE, slitInner, into, life = 0)
                    run.eyeCueActive = false
                }
                if (!queueScene("eye") { eyeScene(c, w) }) eye(c, w, inCave, 1.4f)
                return
            }
        }
    }

    /** Ihr Auge in einem Fenster der Wand: starrt, folgt, blinzelt, ist weg. */
    private fun eye(c: HuntRoute.Cave, u: Double, viewers: List<ServerPlayerEntity>, size: Float, out: Double = 6.2, ice: Boolean = false) {
        val i = HuntRoute.indexOfArc(c.a0 + u)
        val at = beside(i, out, if (ice) 2.4 else 2.55)
        val into = beside(i, 0.0, 2.0).subtract(at).normalize()
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
        val line = f.calls.line(Vec3d(crack.x, point(i).y, crack.z), Vec3d(end.x, point(i).y, end.z), 1.9f, 30, Telegraph.Pace.HEAVY, style = Vfx.Style.SOUL)
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

    /**
     * Ihr Leib gleitet draußen an der Eiswand der Kristallgrotte vorbei. Seit 1.11.0 auf einer Bahn, die dem Bogen des
     * Wegs folgt (die frühere Gerade schnitt durch den Fels). Ist draußen kein Platz, zeigt sich nur ihr Auge.
     */
    private fun slidePast(d: NivarisEntity, c: HuntRoute.Cave): Script = script {
        val wps = ArrayList<Vec3d>()
        var u = c.iceFrom - 10.0
        var free = true
        while (u <= c.iceTo + 10.0) {
            val q = beside(HuntRoute.indexOfArc(c.a0 + u), 12.0, 2.5)
            val top = runCatching { FarPlan.top(floor(q.x).toInt() - f.origin.x, floor(q.z).toInt() - f.origin.z) }.getOrDefault(0)
            if (f.origin.y + top + 3.0 > q.y) free = false
            wps += q
            u += 5.0
        }
        if (!free) {
            eye(c, (c.iceFrom + c.iceTo) / 2, f.players(), 2.2f, out = 4.9, ice = true)
            return@script
        }
        d.flying = true
        d.play(NivarisPose.GLIDE)
        val first = wps.first()
        val viewers = f.players().filter { it.squaredDistanceTo(first) < 60.0 * 60.0 }
        cue("nivaris.wing", first, 4f, 0.5f)
        vanilla(SoundEvents.ENTITY_ELDER_GUARDIAN_CURSE, wps[wps.size / 2], 1.2f, 0.6f)
        HuntNet.fx(viewers, HuntFx.LETTERBOX, first, life = 50)
        yieldAll(DragonRail.fly(f, d, wps, 1.5))
        cue("nivaris.roar", d.entityPos, 3f, 0.8f)
        HuntNet.fx(viewers, HuntFx.IMPACT, d.entityPos, a = 0.4f, life = 14)
        yieldAll(rejoinRail(d))
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
            val rings = targets.take(3).map { f.calls.ring(it, 1.8f, 26, pace = Telegraph.Pace.QUICK) }
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

    private val weakHit = HashSet<Int>()
    private val weakCache = HashMap<Int, DoubleArray>()
    private val cutSpans = HashSet<Int>()
    private val ropeHold = HashMap<Pair<Int, UUID>, Int>()
    private var wound = 0f

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

    private fun tickBridges(players: List<ServerPlayerEntity>) {
        for (span in spans) {
            if (span.id in cutSpans) {
                ferryStragglers(span, players)
            } else {
                tickWeakPlanks(span, players)
                tickRopes(span, players)
            }
        }
    }

    /** Morsche Planken: Wer darauftritt, hört sie knacken; kurz darauf bricht die Planke (Lücke 1,5 Blöcke). */
    private fun tickWeakPlanks(span: HuntRoute.Span, players: List<ServerPlayerEntity>) {
        val weak = weakCache.getOrPut(span.id) { HuntRoute.weakPlanks(span) }
        for ((k, rel) in weak.withIndex()) {
            val key = span.id * 1000 + k
            if (key in weakHit) continue
            val g = span.a0 + rel
            val on = players.firstOrNull { p ->
                p.isOnGround && denseIndex(p, 1.0, 1.4).let { i -> i in span.i0..span.i1 && abs(HuntRoute.arc(i) - g) < 1.0 }
            } ?: continue
            weakHit += key
            val at = point(HuntRoute.indexOfArc(g))
            val dir = tangentVec(HuntRoute.indexOfArc(g))
            HuntNet.fx(players.filter { it.squaredDistanceTo(at) < 60.0 * 60.0 }, HuntFx.PLANKS_SHAKE, at, a = 2f, life = 14)
            vanilla(SoundEvents.ENTITY_CREAKING_SWAY, at, 2f, 0.7f)
            vanilla(SoundEvents.BLOCK_SCAFFOLDING_STEP, at, 2f, 0.5f)
            on.sendMessage(Text.translatable("hha_arius.hunt.plank").formatted(Formatting.GOLD), true)
            f.task(script {
                wait(14)
                if (span.id !in cutSpans && dropDeck(span, g - 0.75, g + 0.75, false)) {
                    vanilla(SoundEvents.BLOCK_BAMBOO_WOOD_BREAK, at, 3f, 0.6f)
                    vanilla(SoundEvents.BLOCK_WOOD_BREAK, at, 2.2f, 0.75f)
                    HuntNet.fx(f.players().filter { it.squaredDistanceTo(at) < 60.0 * 60.0 }, HuntFx.PLANKS_FALL, at, dir, a = 3f, life = 40)
                }
            })
        }
    }

    /**
     * Seile am fernen Kopf: Wer dort steht, sieht die Ansage und hält Schleichen. Gekappt wird erst, wenn **alle** Kämpfenden
     * drüben sind; so stürzt nie ein Mitspieler ab und niemand bleibt auf der falschen Seite.
     */
    private fun tickRopes(span: HuntRoute.Span, players: List<ServerPlayerEntity>) {
        if (pendingScene != null) return
        val far = point(span.i1)
        val across = span.a1 - 2.5
        val atPost = players.filter { arcOf(it) >= across && it.squaredDistanceTo(far) < 6.0 * 6.0 && abs(it.y - far.y) < 3.0 }
        ropeHold.keys.removeIf { k -> k.first == span.id && atPost.none { it.uuid == k.second } }
        if (atPost.isEmpty()) return
        val behind = players.firstOrNull { arcOf(it) < across }
        for (p in atPost) {
            if (f.now % 10L == 0L) {
                val msg = if (behind != null) Text.translatable("hha_arius.hunt.rope.wait", behind.displayName).formatted(Formatting.GRAY)
                else Text.translatable("hha_arius.hunt.rope.prompt", Text.keybind("key.sneak")).formatted(Formatting.GOLD, Formatting.BOLD)
                p.sendMessage(msg, true)
            }
            if (behind != null || !p.isSneaking) {
                ropeHold.remove(span.id to p.uuid)
                continue
            }
            val h = (ropeHold[span.id to p.uuid] ?: 0) + 1
            ropeHold[span.id to p.uuid] = h
            HuntNet.fx(p, HuntFx.ROPE_PROGRESS, far, a = h / ROPE_HOLD.toFloat(), life = 6)
            if (h >= ROPE_HOLD) {
                cutRopes(span)
                return
            }
        }
    }

    private fun cutRopes(span: HuntRoute.Span) {
        cutSpans += span.id
        ropeHold.clear()
        vanilla(SoundEvents.ITEM_SHEARS_SNIP, point(span.i1), 2f, 0.7f)
        val a = f.arius
        if (a != null && !a.isRemoved && aS in span.a0 + 1.0..span.a1 - 1.0) {
            pendingScene = { ropeScene(span) }
            pendingUntil = f.now + 120
        } else {
            dropBridge(span)
        }
    }

    /** Das Deck fällt: Kollision weg, am Client schwingt die Brücke am nahen Anker herab. */
    private fun dropBridge(span: HuntRoute.Span) {
        val at = point(span.i1)
        dropDeck(span, span.a0, span.a1, true)
        HuntNet.fx(f.audience(), HuntFx.BRIDGE_DROP, at, a = span.id.toFloat(), life = 200)
        val near = f.players().filter { it.squaredDistanceTo(at) < 60.0 * 60.0 }
        HuntNet.fx(near, HuntFx.IMPACT, at, a = 0.6f, life = 16)
        vanilla(SoundEvents.ITEM_LEAD_BREAK, at, 4f, 0.5f)
        cue("ridge.break", at, 5f, 0.5f)
        for (p in f.players()) p.sendMessage(Text.translatable("hha_arius.hunt.rope.cut").formatted(Formatting.GOLD, Formatting.BOLD), true)
    }

    /** Wer (nach k. o. oder Nachzug) noch vor einer gekappten Brücke steht, wird hinübergetragen: nie abgeschnitten. */
    private fun ferryStragglers(span: HuntRoute.Span, players: List<ServerPlayerEntity>) {
        for (p in players) {
            val s = safe[p.uuid] ?: continue
            if (s.pending >= 0 || Hold.isHeld(p) || HuntRoute.arc(s.index) >= span.a0) continue
            if (HuntRoute.arc(s.index) < span.a0 - 12.0) continue
            s.index = rescueTarget(HuntRoute.indexOfArc(span.a1 + 3.0))
            s.pending = s.index
            s.pendingAt = f.now + 4
            HuntNet.fx(p, HuntFx.FADE, p.entityPos, a = 4f, b = 14f, life = 22, color = 0xFFF2F6FA.toInt())
        }
    }

    /** Echter Schaden an Arius: sofort auf der Leiste, im Finale zieht er ihm denselben Anteil ab ([carryWound]). */
    private fun hurtArius(a: AriusEntity, frac: Float) {
        wound += frac
        f.ariusHp = (f.ariusHp - f.ariusMax * frac).coerceAtLeast(f.ariusMax * 0.05f)
        f.updateBars()
        a.play(AriusPose.STAGGER, 10)
        cue("nivaris.land", a.entityPos, 3f, 1.3f)
        if (!Cine.muted) {
            f.fx(FxKind.DEBRIS_CLOUD, a.entityPos.add(0.0, 1.0, 0.0), a = 3f, life = 20)
            Vfx.shockwave(f.world, Vfx.Style.HEAVEN, a.entityPos, 3f, 10, 1f)
        }
    }

    /** Die Wunde aus der Jagd bleibt: Sobald das Finale sein Leben gesetzt hat, wird ihr Anteil abgezogen. */
    private fun carryWound() {
        val w = wound
        if (w <= 0f) return
        wound = 0f
        f.task(script {
            while (!f.ended && !(f.phase == EirholmFight.Phase.FINALE && f.ariusVulnerable)) yield(Unit)
            if (!f.ended) {
                f.ariusHp = (f.ariusHp - f.ariusMax * w).coerceAtLeast(f.ariusMax * 0.5f)
                f.updateBars()
            }
        })
    }

    /**
     * Die Seile reißen, während Arius auf der Brücke steht:
     * 1) Klare Sicht (Sturm lichtet sich kurz), Blick am fernen Anker auf die gespannten Seile & Arius auf der Brücke; Schnitt!
     * 2) Weite Totale weit draußen über dem offenen Tal: Arius stürzt mit dem Deck in die Tiefe, Solvane stößt aus der Ferne
     *    im großen Bogen herab und fängt ihn mit hartem Aufprall (echter Schaden) – nie im Kamera-Objektiv.
     * 3) Geschwungene Aufstiegsfahrt: Solvane trägt ihn aus der Schlucht empor und setzt ihn hart am fernen Brückenkopf ab,
     *    während sie weiter über das Tal abdreht. Weicher Rückweg zur Spielerkamera.
     */
    private fun ropeScene(span: HuntRoute.Span): Script = script {
        val a = f.arius
        if (a == null || a.isRemoved || aS !in span.a0 + 1.0..span.a1 - 1.0) {
            dropBridge(span)
            return@script
        }
        val d = f.nivaris?.takeIf { !it.isRemoved && !f.dragonDowned }
        val landingArc = HuntRoute.arc(HuntRoute.stationBefore(span.a1 + 9.0)).coerceAtLeast(span.a1 + 1.0)
        val landing = pathAt(landingArc)
        val far = point(span.i1)
        val midIdx = HuntRoute.indexOfArc((span.a0 + span.a1) * 0.5)
        val dir = tangentAt(span.a1 - 2.0)
        // Offene Talseite (weg von der Bergflanke), damit Kamera und Solvane immer im freien Luftraum über der Schlucht bleiben.
        val valleyRaw = beside(midIdx, 1.0).subtract(point(midIdx)).let { Vec3d(it.x, 0.0, it.z) }
        val valley = if (valleyRaw.lengthSquared() < 1e-4) Vec3d(-dir.z, 0.0, dir.x) else valleyRaw.normalize()
        var dropped = false
        var hurt = false
        val finish = {
            if (!dropped) { dropped = true; dropBridge(span) }
            if (!hurt) { hurt = true; hurtArius(a, 0.08f) }
            f.place(a, landing)
            f.face(a, landing.add(tangentAt(landingArc)))
            aS = landingArc
            a.play(AriusPose.IDLE)
            f.atmos(HUNT_ATMOS, 16)
        }
        if (d != null) dragonBusy = true
        try {
            yieldAll(mini(onAbort = { finish() }) {
                script {
                    // Sicht über der Schlucht kurz aufklären, damit Sturz und Rettung nicht im Whiteout verschwinden.
                    f.atmos(AtmosMode.SNOW, 8)
                    HuntNet.fx(f.audience(), HuntFx.LETTERBOX, far, life = 112)

                    // 1) Am Anker: die Seile reißen im Blitz, Blick über die gespannte Brücke auf Arius (16 Ticks).
                    val knot = far.add(valley.multiply(1.9)).add(0.0, 1.6, 0.0)
                    val cam0 = freeCam(listOf(
                        far.add(dir.multiply(3.8)).add(valley.multiply(5.4)).add(0.0, 2.8, 0.0),
                        far.add(dir.multiply(4.2)).add(valley.multiply(-4.8)).add(0.0, 3.0, 0.0),
                        far.add(dir.multiply(5.2)).add(valley.multiply(2.8)).add(0.0, 3.2, 0.0),
                    ), a.entityPos.add(0.0, 1.2, 0.0))
                    val cam0To = freeCam(listOf(
                        far.add(dir.multiply(2.8)).add(valley.multiply(6.2)).add(0.0, 2.6, 0.0),
                        cam0.lerp(knot, 0.15),
                    ), a.entityPos.add(0.0, 1.2, 0.0))
                    cam(cam0, cam0To, knot.lerp(a.entityPos, 0.35), a.entityPos.add(0.0, 1.2, 0.0), 16, 58.0, 54.0)
                    f.face(a, far)
                    a.play(AriusPose.GUARD, 8)
                    wait(6)
                    if (!Cine.muted) {
                        f.fx(FxKind.SLASH_ARC, knot, valley.negate(), a = 4.2f, b = 0.2f, life = 10)
                        f.fx(FxKind.CLEAR_WAVE, knot, a = 4.5f, life = 12)
                    }
                    dropped = true
                    dropBridge(span)
                    a.play(AriusPose.STAGGER, 24)
                    wait(10)

                    // 2) Sturz in die Schlucht & Solvanes Sturzflug von der gegenüberliegenden Seite (32 Ticks).
                    val fallFrom = a.entityPos
                    val groundY = terrainTop(fallFrom, 4) + 3.0
                    val catchTicks = 32
                    val dropDepth = minOf(22.0, (fallFrom.y - (groundY + 5.0)).coerceAtLeast(10.0))
                    fun fallY(n: Int): Double {
                        val u = (n / catchTicks.toDouble()).coerceIn(0.0, 1.0)
                        return fallFrom.y - dropDepth * (u * u * 0.85 + u * 0.15)
                    }
                    val catchAt = Vec3d(fallFrom.x, fallY(catchTicks) - 2.2, fallFrom.z)
                    // Kamera weit draußen über dem Tal (24..26 Blöcke Abstand) – volle Übersicht, kein Drachen-Clipping im Bild!
                    val wide0 = freeCam(listOf(
                        fallFrom.add(valley.multiply(24.0)).add(dir.multiply(5.0)).add(0.0, 4.5, 0.0),
                        fallFrom.add(valley.multiply(-24.0)).add(dir.multiply(5.0)).add(0.0, 4.5, 0.0),
                        fallFrom.add(valley.multiply(18.0)).add(0.0, 8.0, 0.0),
                    ), fallFrom.add(0.0, 1.0, 0.0))
                    val wide1 = freeCam(listOf(
                        catchAt.add(valley.multiply(26.0)).add(dir.multiply(8.0)).add(0.0, 5.5, 0.0),
                        catchAt.add(valley.multiply(-26.0)).add(dir.multiply(8.0)).add(0.0, 5.5, 0.0),
                        wide0.add(0.0, -4.0, 0.0),
                    ), catchAt.add(0.0, 1.2, 0.0))
                    cam(wide0, wide1, fallFrom.add(0.0, 1.0, 0.0), catchAt.add(0.0, 1.2, 0.0), catchTicks + 4, 64.0, 58.0, lookAt = a, lookOffset = 1.1)

                    // Solvane stößt sauber aus dem offenen Luftraum (entgegen der Kamera-Seite bzw. hoch hinter der Brücke) herab.
                    val inbound = d?.let { dr ->
                        val startRaw = catchAt.subtract(dir.multiply(42.0)).add(valley.multiply(-10.0)).add(0.0, 26.0, 0.0)
                        val swoopStart = Vec3d(startRaw.x, maxOf(startRaw.y, terrainTop(startRaw, 8) + 12.0), startRaw.z)
                        val midRaw = catchAt.subtract(dir.multiply(18.0)).add(valley.multiply(-4.0)).add(0.0, 7.0, 0.0)
                        val swoopMid = Vec3d(midRaw.x, maxOf(midRaw.y, terrainTop(midRaw, 6) + 6.0), midRaw.z)
                        f.place(dr, swoopStart)
                        f.face(dr, swoopMid)
                        dr.flying = true
                        dr.play(NivarisPose.DIVE)
                        DragonRail.path(swoopStart, swoopMid.subtract(swoopStart).normalize(), listOf(swoopMid, catchAt))
                    }
                    for (n in 1..catchTicks) {
                        val ay = fallY(n)
                        f.place(a, Vec3d(fallFrom.x, ay, fallFrom.z))
                        if (d != null && inbound != null) {
                            val k = (n / catchTicks.toDouble()).let { it * it * (3.0 - 2.0 * it) }
                            val q = (inbound.lastIndex * k).toInt().coerceAtMost(inbound.lastIndex)
                            f.place(d, inbound[q])
                            val ahead = inbound[(q + 8).coerceAtMost(inbound.lastIndex)]
                            if (ahead.squaredDistanceTo(inbound[q]) > 0.01) f.face(d, ahead)
                        }
                        if (n == 12) cue("nivaris.roar", catchAt, 5f, 0.85f)
                        if (n == 22) cue("nivaris.wing", catchAt, 4f, 0.7f)
                        yield(Unit)
                    }

                    // 3) Aufprall auf Solvanes Rücken (echter Schaden) & kraftvoller Aufschwung zurück auf die andere Seite.
                    hurt = true
                    hurtArius(a, 0.08f)
                    if (!Cine.muted) {
                        f.fxAll(FxKind.FLASH, catchAt, a = 0.28f, life = 6)
                        Vfx.shockwave(f.world, Vfx.Style.SOUL, catchAt.add(0.0, 1.5, 0.0), 7f, 14, 1.8f)
                    }
                    // Die Rettung bleibt in derselben weiten Einstellung (früher ein Schnitt dicht an den Fels).
                    val climbCam0 = wide1
                    val climbCam1 = freeCam(listOf(
                        wide1.add(dir.multiply(6.0)).add(0.0, 9.0, 0.0),
                        wide1.add(0.0, 6.0, 0.0),
                        landing.add(valley.multiply(16.0)).add(dir.multiply(10.0)).add(0.0, 5.5, 0.0),
                        landing.add(valley.multiply(-15.0)).add(dir.multiply(10.0)).add(0.0, 6.5, 0.0),
                    ), landing.add(0.0, 1.4, 0.0))

                    if (d != null) {
                        for (p in f.audience()) p.sendMessage(Text.translatable("hha_arius.hunt.rope.caught").formatted(Formatting.AQUA, Formatting.BOLD), true)
                        d.play(NivarisPose.GLIDE)
                        val dropPoint = landing.add(0.0, 6.5, 0.0)
                        val exitPoint = landing.add(dir.multiply(22.0)).add(valley.multiply(14.0)).add(0.0, 18.0, 0.0)
                        val carry = DragonRail.path(
                            d.entityPos, dir.add(0.0, 0.35, 0.0).normalize(),
                            listOf(catchAt.lerp(dropPoint, 0.5).add(valley.multiply(4.0)).add(0.0, 5.0, 0.0), dropPoint, exitPoint),
                        )
                        // Punkt auf der Bahn, der dem Landepunkt am nächsten liegt (dort springt Arius ab).
                        var dropIdx = (carry.size * 0.65).toInt().coerceIn(4, carry.lastIndex)
                        var bestD = Double.MAX_VALUE
                        for (k in carry.indices) {
                            val dd = carry[k].squaredDistanceTo(dropPoint)
                            if (dd < bestD) { bestD = dd; dropIdx = k }
                        }
                        val rideTicks = 26
                        val leapTicks = 10
                        cam(climbCam0, climbCam1, catchAt.add(0.0, 2.0, 0.0), landing.add(0.0, 1.4, 0.0), rideTicks + leapTicks + 10, 64.0, 54.0, lookAt = a, lookOffset = 1.3)
                        for (n in 1..rideTicks) {
                            val q = (dropIdx * (n / rideTicks.toDouble())).toInt().coerceAtMost(dropIdx)
                            f.place(d, carry[q])
                            val ahead = carry[(q + 8).coerceAtMost(carry.lastIndex)]
                            if (ahead.squaredDistanceTo(carry[q]) > 0.01) f.face(d, ahead)
                            f.place(a, carry[q].add(0.0, NivarisShape.SPINE_UP + 1.0, 0.0))
                            f.face(a, landing)
                            if (n == 10) cue("nivaris.wing", carry[q], 5f, 0.72f)
                            yield(Unit)
                        }
                        // Arius springt wuchtig hinab auf den Weg, während Solvane über die Schlucht abdreht.
                        val rel = a.entityPos
                        a.play(AriusPose.STAB_GROUND, leapTicks)
                        cue("arius.wind_step", rel, 3f, 0.85f)
                        for (n in 1..leapTicks) {
                            val k = n / leapTicks.toDouble()
                            f.place(a, rel.lerp(landing, k).add(0.0, sin(k * PI) * 1.4, 0.0))
                            f.face(a, landing.add(dir))
                            val q = (dropIdx + ((carry.lastIndex - dropIdx) * k).toInt()).coerceAtMost(carry.lastIndex)
                            f.place(d, carry[q])
                            val ahead = carry[(q + 6).coerceAtMost(carry.lastIndex)]
                            if (ahead.squaredDistanceTo(carry[q]) > 0.01) f.face(d, ahead)
                            yield(Unit)
                        }
                    } else {
                        val rel = a.entityPos
                        a.play(AriusPose.LAUNCH, 6)
                        cam(climbCam0, climbCam1, rel, landing.add(0.0, 1.4, 0.0), 30, 64.0, 56.0, lookAt = a, lookOffset = 1.3)
                        for (n in 1..20) {
                            val k = n / 20.0
                            f.place(a, rel.lerp(landing, k).add(0.0, sin(k * PI) * 9.0, 0.0))
                            yield(Unit)
                        }
                    }
                    f.place(a, landing)
                    f.face(a, landing.add(tangentAt(landingArc)))
                    cue("arius.stab_ground", landing, 3.5f, 0.85f)
                    Vfx.shockwave(f.world, Vfx.Style.HEAVEN, landing, 4.0f, 14, 1.5f)
                    if (!Cine.muted) f.fx(FxKind.CLEAR_WAVE, landing, a = 6f, life = 14)
                    wait(8)
                    a.play(AriusPose.IDLE)
                    f.atmos(HUNT_ATMOS, 16)
                    home(12)
                    wait(12)
                }
            })
            finish()
        } finally {
            f.atmos(HUNT_ATMOS, 16)
            if (d != null) {
                f.task(script {
                    try { yieldAll(rejoinRail(d)) } finally { dragonBusy = false }
                })
            }
        }
        nextAttack = f.now + 60
        nextLeap = f.now + 60
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
        buildRail()
        val rage = CineScene(f) { f.atmos(HUNT_ATMOS, 20) }
        yieldAll(rage.play(script { yieldAll(rageScene(rage, a)) }))
        f.chasing = true
        a.eyeGlow = 100
        a.form = AriusForm.WHITE
        a.bladeState = AriusBlade.ICE
        a.sheathed = false
        f.music("music.hunt", 30)
        MusicCue.play(f, "hunt", 30)
        f.atmos(HUNT_ATMOS, 40)
        HuntNet.fx(f.audience(), HuntFx.STATE, a.entityPos, a = 1f, e1 = a.id)
        f.fxAll(FxKind.RAGE_AURA, a.entityPos, life = 20 * 60 * 25, e1 = a)
        for (p in f.players()) p.sendMessage(Text.translatable("hha_arius.msg.chase").formatted(Formatting.AQUA, Formatting.BOLD), false)
        // Am Ende der Schlacht steht seine Leiste auf null: In der Jagd zeigt sie, was ihm geblieben ist.
        if (f.ariusHp <= 0f) f.ariusHp = f.ariusMax * 0.4f
        f.updateBars()
        val d = f.nivaris?.takeIf { !f.dragonDowned }
        d?.let {
            it.flying = true
            it.resonance = true
            it.play(NivarisPose.GLIDE)
            dragonBusy = true
            f.task(script { try { yieldAll(rejoinRail(it)) } finally { dragonBusy = false } })
        }
        aS = 0.0
        current = this@Hunt
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
                huntMusic(players)
                val leader = players.maxByOrNull { arcOf(it) }!!
                val rear = players.minByOrNull { arcOf(it) }!!
                val leaderArc = arcOf(leader)
                val rearArc = arcOf(rear)
                lastLeaderArc = leaderArc
                // Eine vorgemerkte kurze Szene beginnt, sobald kein Angriff mehr läuft.
                val next = pendingScene
                if (next != null && ((!busy && !dragonBusy) || f.now >= pendingUntil)) {
                    pendingScene = null
                    inScene = true
                    try {
                        yieldAll(next())
                    } finally {
                        inScene = false
                    }
                    nextAttack = maxOf(nextAttack, f.now + 30)
                    nextLeap = maxOf(nextLeap, f.now + 30)
                    continue
                }
                tickCaves(players, leaderArc, rearArc)
                tickBridges(players)
                tickAvalanche(players, leaderArc, rearArc)
                tickWind(players)
                if (f.now % 20L == 0L) HuntNet.fx(f.audience(), HuntFx.STATE, a.entityPos, a = 1f, e1 = a.id, life = 40)
                if (d != null) tickDragon(d, leader, leaderArc)
                if (!busy && pendingScene == null) chaseTick(a, rear, rearArc, leader, leaderArc)
                yield(Unit)
            }
        } finally {
            f.chasing = false
            current = null
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
        // Eine gekappte Brücke liegt vor ihm: ein gewaltiger Sprung auf den Landepunkt drüben.
        val gone = spans.firstOrNull { it.id in cutSpans && aS >= it.a0 - 3.0 && aS < it.a1 + 1.0 }
        if (gone != null && now >= nextLeap) {
            val across = gone.a1 + 8.0
            start { leap(a, across, t) }
            nextLeap = now + 60
            return
        }
        // Weit zurück oder auf einer anderen Windung: Sprung von Landepunkt zu Landepunkt, immer den Weg entlang.
        if (now >= nextLeap && (gap > 30.0 || (d3 < 30.0 && gap > 12.0 && t.y - a.y > 5.0))) {
            val to = clearAhead(aS, minOf(sT - 5.0, aS + 44.0))
            if (to > aS + 4.0) {
                start { leap(a, to, t) }
                nextLeap = now + 60
                return
            }
        }
        // Mittlere Entfernung: Fernangriff.
        if (now >= nextAttack && d3 in 7.0..34.0) {
            var pick = rnd.nextInt(4)
            if (pick == lastRanged) pick = (pick + 1) % 4
            val onBridge = f.players().firstOrNull { overGap(it) && abs(arcOf(it) - aS) < 40.0 }
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
        if (speed > 0.0 && gone == null) {
            aS = clearAhead(aS, aS + speed).coerceIn(aS, HuntRoute.arc(HuntRoute.size - 1))
            val want = pathAt(aS)
            // Nach einem Angriff abseits der Mitte läuft er weich auf die Wegmitte zurück.
            val off = Vec3d(a.x - want.x, 0.0, a.z - want.z)
            val pos = if (off.lengthSquared() in 0.01..64.0) want.add(off.multiply(0.8)) else want
            f.place(a, pos)
            f.face(a, a.entityPos.add(tangentAt(aS)))
            if (a.pose != AriusPose.IDLE) a.play(AriusPose.IDLE)
        } else {
            f.face(a, t.entityPos)
        }
    }

    /** Wie hoch Arius über Wegpunkt [i] gerade springen kann: echte Kopffreiheit (bis 14) abzüglich seiner Größe. */
    private fun liftRoom(i: Int): Double {
        val p = point(i)
        val base = BlockPos.ofFloored(p.x, p.y + 0.05, p.z)
        var free = 0
        while (free < 14) {
            val q = base.up(free)
            if (!f.world.getBlockState(q).getCollisionShape(f.world, q).isEmpty) break
            free++
        }
        return maxOf(HuntRoute.liftAt(i), free - (p.y - base.y) - 2.2)
    }

    /** Bis wohin Arius von [from] nach [to] kommt, ohne in einen geschlossenen Geröllpfropfen zu laufen. */
    private fun clearAhead(from: Double, to: Double): Double {
        var end = to
        for (run in caves) {
            if (!run.sealed || run.opened) continue
            val plug = run.c.a0 + run.c.plugFrom
            if (from < plug && plug - 2.5 < end) end = maxOf(from, plug - 2.5)
        }
        return end
    }

    /** Ein sicherer Standplatz nahe [want]: auf der Lauffläche des Wegs, sonst die Wegmitte auf Höhe von Arius' Bogenlänge. */
    private fun safeNear(want: Vec3d): Vec3d {
        val i = HuntRoute.indexAtHeight(floor(want.x).toInt() - f.origin.x, floor(want.z).toInt() - f.origin.z, want.y - f.origin.y, -0.4, 1.6)
        if (i >= 0) {
            val g = ground(want)
            if (abs(g.y - point(i).y) <= 1.2 && f.passable(g)) return g
            return point(i)
        }
        return pathAt(aS)
    }

    private fun start(attack: () -> Script) {
        busy = true
        f.task(script {
            try {
                yieldAll(attack())
            } finally {
                busy = false
                f.arius?.let {
                    val i = HuntRoute.indexAtHeight(floor(it.x).toInt() - f.origin.x, floor(it.z).toInt() - f.origin.z, it.y - f.origin.y, 2.0, 2.0)
                    if (i >= 0) aS = maxOf(aS - 4.0, HuntRoute.arc(i))
                }
            }
        })
    }

    /**
     * Sprung den Weg entlang auf den letzten Landepunkt vor Bogenlänge [toArc]: Die Bahn folgt der Wegmitte und bleibt
     * unter der erlaubten Höhe ([HuntRoute.liftAt]), er kann also weder in den Hang noch durch eine Decke geraten.
     */
    private fun leap(a: AriusEntity, toArc: Double, t: ServerPlayerEntity): Script = script {
        val station = HuntRoute.stationBefore(toArc)
        val target = HuntRoute.arc(station)
        val fromArc = aS
        val from = a.entityPos
        val to = point(station)
        val startOff = from.subtract(pathAt(fromArc))
        val dist = abs(target - fromArc) + Vec3d(startOff.x, 0.0, startOff.z).length()
        val ticks = (10 + dist / 2.2).toInt().coerceIn(12, 28)
        val ring = f.calls.ring(to, 2.4f, ticks, pace = Telegraph.Pace.QUICK)
        a.play(AriusPose.LAUNCH, 6)
        cue("arius.wind_step", from, 3f, 0.8f)
        if (!Cine.muted) f.fx(FxKind.DASH_STREAK, from, to, life = ticks + 4)
        var cap = 12.0
        var scan = minOf(fromArc, target)
        while (scan <= maxOf(fromArc, target)) {
            cap = minOf(cap, liftRoom(HuntRoute.indexOfArc(scan)))
            scan += 1.0
        }
        val lift = minOf(2.0 + dist * 0.1, cap)
        for (i in 1..ticks) {
            val k = i / ticks.toDouble()
            val base = pathAt(fromArc + (target - fromArc) * k)
            // Stand er neben der Wegmitte (Feld, nach einem Hieb), läuft der Versatz in der ersten Hälfte aus.
            val fade = (1.0 - k / 0.45).coerceIn(0.0, 1.0).let { it * it }
            f.place(a, Vec3d(base.x + startOff.x * fade, base.y + startOff.y * fade + sin(k * PI) * lift, base.z + startOff.z * fade))
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
        nearMiss(to, 5.0, hit)
        aS = target
        wait(6)
    }

    /**
     * Er schneidet sich neben ihnen durch die Talwand der Höhle: Sprung den Gang entlang hinter das Ziel, eine leuchtende
     * Naht im Fels (Ansage), der Schnitt reißt ein Loch in die Wand und fegt quer über den Gang.
     */
    private fun cutIn(a: AriusEntity, t: ServerPlayerEntity, ti: Int, cave: HuntRoute.Cave): Script = script {
        val i = (ti - 10).coerceAtLeast(cave.i0 + 16)
        val back = clearAhead(aS, HuntRoute.arc(i))
        if (back > aS + 4.0) yieldAll(leap(a, back, t))
        val stand = a.entityPos
        val wallIn = beside(i, cave.theme.lane + 0.3, 1.0)
        val wallOut = beside(i, 7.0, 1.0)
        f.face(a, wallIn)
        a.play(AriusPose.CUT_CHARGE, 20)
        cue("cut.charge", stand, 4f, 1.0f)
        val near = f.players().filter { it.squaredDistanceTo(wallIn) < 60.0 * 60.0 }
        HuntNet.fx(near, HuntFx.ROCK_CUT, wallOut.add(0.0, 2.0, 0.0), wallIn.add(0.0, 2.0, 0.0), life = 24)
        val zone = f.calls.line(Vec3d(wallIn.x, point(i).y, wallIn.z), Vec3d(beside(i, -cave.theme.lane, 0.0).x, point(i).y, beside(i, -cave.theme.lane, 0.0).z), 2.2f, 22, Telegraph.Pace.HEAVY)
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
            f.fx(FxKind.SLASH_ARC, wallIn.add(0.0, 1.0, 0.0), flatDir(stand, wallIn), a = 5f, b = 1f, life = 12)
            f.fx(FxKind.DEBRIS_CLOUD, wallIn.add(0.0, 1.0, 0.0), a = 4f, life = 30)
        }
        f.calls.roomShake(wallIn, 4f, 30f)
        val hit = sameLevel(zone.hits(f.players()), point(i).y)
        for (p in hit) {
            exactHit(p, 5f)
            launch(p, tangentVec(i).multiply(0.5).add(0.0, 0.3, 0.0))
        }
        HuntNet.fx(near, HuntFx.IMPACT, wallIn, a = 0.8f, life = 18)
        nearMiss(wallIn, 5.5, hit)
        wait(6)
        a.play(AriusPose.IDLE)
        wait(8)
    }

    /** Nah: drei schnelle Hiebe (je 3 Herzen), der letzte als Aufwärtsschnitt. */
    private fun closeCombo(a: AriusEntity, t0: ServerPlayerEntity): Script = script {
        for (k in 0 until 3) {
            val t = t0.takeIf { it.isAlive && it in f.players() } ?: break
            if (a.entityPos.distanceTo(t.entityPos) > 4.5) {
                val from = a.entityPos
                val to = safeNear(t.entityPos.subtract(flatDir(from, t.entityPos).multiply(1.6)))
                for (i in 1..4) { f.place(a, from.lerp(to, i / 4.0)); yield(Unit) }
                if (!Cine.muted) f.fx(FxKind.DASH_STREAK, from, to, life = 8)
            }
            f.face(a, t.entityPos)
            val dir = f.facing(a)
            val cone = f.calls.cone(a.entityPos, dir, COMBO_RANGE, 70f, if (k == 0) 12 else 10, if (k == 0) Telegraph.Pace.HEAVY else Telegraph.Pace.FOLLOW_UP)
            a.play(listOf(AriusPose.COMBO_1, AriusPose.COMBO_2, AriusPose.LAUNCH)[k], cone.ticks)
            wait(cone.ticks)
            cue("arius.slash", a.entityPos, 3f, 1.0f + k * 0.1f)
            if (!Cine.muted) f.fx(FxKind.SLASH_ARC, a.entityPos.add(0.0, 1.1, 0.0), dir, a = COMBO_RANGE, b = if (k == 2) 1f else 0.2f, life = 10)
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
        val reach = minOf(from.distanceTo(t.entityPos) + WAVE_OVERSHOOT, WAVE_MAX)
        val side = Vec3d(-dir.z, 0.0, dir.x)
        for (k in 0 until 3) {
            val off = side.multiply(listOf(0.0, -1.8, 1.8)[k])
            val l0 = from.add(off)
            val l1 = from.add(off).add(dir.multiply(reach))
            val line = f.calls.line(l0, Vec3d(l1.x, t.y, l1.z), 1.7f, if (k == 0) 16 else 10, if (k == 0) Telegraph.Pace.PROJECTILE else Telegraph.Pace.FOLLOW_UP)
            a.play(if (k % 2 == 0) AriusPose.COMBO_1 else AriusPose.COMBO_2, line.ticks)
            wait(line.ticks)
            cue("arius.slash", from, 3f, 0.9f + k * 0.1f)
            if (!Cine.muted) {
                f.fx(FxKind.SLASH_ARC, from.add(0.0, 1.1, 0.0), dir, a = 5f, b = 0f, life = 12)
                Vfx.crescent(f.world, Vfx.Style.HEAVEN, from.add(off).add(0.0, 0.8, 0.0), dir, reach.toFloat(), 1.7f, 10)
            }
            val hit = line.hits(f.players()).filter { abs(it.y - t.y) <= 4.0 }
            for (p in hit) exactHit(p, 6f)
            for (p in f.players()) if (p !in hit && abs(p.y - t.y) <= 4.0) {
                val q = p.entityPos
                val v = l1.subtract(l0)
                val tt = ((q.subtract(l0)).dotProduct(v) / v.lengthSquared().coerceAtLeast(1e-6)).coerceIn(0.0, 1.0)
                val c = l0.add(v.multiply(tt))
                if (Vec3d(q.x - c.x, 0.0, q.z - c.z).length() < 2.9) HuntNet.fx(p, HuntFx.NEAR_MISS, c, life = 14)
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
            val to = from.add(dd.multiply(minOf(from.distanceTo(t.entityPos) + SPEAR_OVERSHOOT, SPEAR_MAX)))
            if (!Cine.muted) f.fx(FxKind.CRYSTAL, from.add(0.0, 2.6, 0.0).add(dd.multiply(0.8)), to.add(0.0, 1.0, 0.0), a = 20f, life = 28)
            group.line(from, to, 1.25f, 20, Telegraph.Pace.PROJECTILE)
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
     * Lawine aus der Flanke: Er springt auf einen Landepunkt hinter dem Vordersten und schlägt in den Schnee; drei
     * Schneewalzen rollen quer über den Weg vor dem Vordersten (je 3 Herzen, wirft entlang der Route zurück). Seit 1.11.0
     * decken sie nicht mehr die ganze Wegbreite: Man kann seitlich ausweichen.
     */
    private fun flankAvalanche(a: AriusEntity, leader: ServerPlayerEntity, sL: Double): Script = script {
        val perch = clearAhead(aS, sL - 4.0)
        if (perch > aS + 4.0) yieldAll(leap(a, perch, leader))
        f.face(a, pathAt(sL))
        a.play(AriusPose.STAB_GROUND, 20)
        cue("glacier.collapse", a.entityPos, 5f, 0.8f)
        f.calls.roomShake(leader.entityPos, 4f, 40f)
        val waves = (0 until 3).map { k ->
            val sp = sL + 12.0 - k * 6.0
            val pt = pathAt(sp)
            val dir = tangentAt(sp)
            val side = Vec3d(-dir.z, 0.0, dir.x)
            // Versetzt: jede Walze lässt eine Wegseite frei.
            val shift = side.multiply(listOf(1.3, -1.3, 1.3)[k])
            val line = f.calls.line(pt.add(shift).add(side.multiply(-AVA_HALF)), pt.add(shift).add(side.multiply(AVA_HALF)), 2.4f, 24 + k * 9, Telegraph.Pace.HEAVY)
            Triple(line, pt, dir)
        }
        var elapsed = 0
        for ((line, pt, dir) in waves) {
            wait((line.ticks - elapsed).coerceAtLeast(0))
            elapsed = line.ticks
            cue("ridge.break", pt, 4f, 0.7f)
            if (!Cine.muted) {
                f.fx(FxKind.CLEAR_WAVE, pt, a = 5f, life = 16)
                f.fx(FxKind.DEBRIS_CLOUD, pt.add(0.0, 1.0, 0.0), a = 4f, life = 30)
                Vfx.shockwave(f.world, Vfx.Style.STORM, pt, 4.5f, 12, 1.4f)
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
        val dir = tangentAt(arcOf(t))
        val pt = Vec3d(t.x, t.y, t.z)
        f.face(a, pt)
        a.play(AriusPose.SPIN_CUT, 22)
        cue("cut.reverse_wind", a.entityPos, 3f, 1.2f)
        val line = f.calls.line(pt.subtract(dir.multiply(GALE_HALF)), pt.add(dir.multiply(GALE_HALF)), 2.6f, 22, Telegraph.Pace.HEAVY)
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
        val spot = pathAt(sL + 8.0)
        a.play(AriusPose.CORE_RAISE)
        val ticks = 54
        for (p in f.audience()) Cine.title(listOf(p), "hha_arius.feat.frozen_star", "hha_arius.feat.frozen_star.sub", 40)
        HuntNet.fx(f.players(), HuntFx.LETTERBOX, spot, life = ticks + 10)
        f.fxAll(FxKind.FROZEN_STAR, spot, a = STAR_RADIUS, b = ticks.toFloat(), life = ticks + 40)
        cue("core.form", spot.add(0.0, 30.0, 0.0), 5f, 0.6f)
        val ring = f.calls.ring(spot, STAR_RADIUS, ticks)
        wait(ring.ticks)
        cue("glacier.collapse", spot, 6f, 1.2f)
        f.calls.roomShake(spot, 6f, 40f)
        val hit = sameLevel(ring.hits(f.players()), spot.y)
        for (p in hit) {
            exactHit(p, 10f, Damage.Kind.IMPACT)
            dev.henny.hha_arius.gear.Frostbite.add(p, 40f)
        }
        nearMiss(spot, STAR_RADIUS + 2.5, hit)
        for (p in f.players()) if (p.squaredDistanceTo(spot) < 30.0 * 30.0) HuntNet.fx(p, HuntFx.IMPACT, spot, a = 0.6f, life = 16)
        @Suppress("UNUSED_VARIABLE") val unused = leader
        a.play(AriusPose.IDLE)
    }

    // --- Solvane: feste Begleitbahn --------------------------------------------------------------------------------------

    /** Dichte Flugbahn neben und über dem Weg; [arcs] = Bogenlänge des Wegs, den jeder Bahnpunkt begleitet (monoton). */
    private class EscortRail(val pts: List<Vec3d>, val arcs: DoubleArray) {
        fun indexFor(a: Double): Int {
            var lo = 0
            var hi = arcs.size - 1
            while (lo < hi) {
                val mid = (lo + hi) / 2
                if (arcs[mid] < a) lo = mid + 1 else hi = mid
            }
            return lo
        }
    }

    private lateinit var rail: EscortRail
    private var railS = 0.0
    private var lastLeaderArc = 0.0

    /** Höchste Geländekante um [v] (Radius [r]), absolut. */
    private fun terrainTop(v: Vec3d, r: Int): Double {
        var top = Int.MIN_VALUE
        val x = floor(v.x).toInt() - f.origin.x
        val z = floor(v.z).toInt() - f.origin.z
        for (dx in intArrayOf(-r, 0, r)) for (dz in intArrayOf(-r, 0, r)) {
            val h = runCatching { FarPlan.top(x + dx, z + dz) }.getOrDefault(Int.MIN_VALUE)
            if (h > top) top = h
        }
        // Die festen Hindernisse des Ritts stehen nicht in der Höhenkarte.
        top = maxOf(top, dev.henny.hha_arius.arena.RideLandmarks.topNear(x, z, r))
        return if (top == Int.MIN_VALUE) v.y else f.origin.y + top + 1.0
    }

    /**
     * Baut die Begleitbahn einmal vorab: alle 22 Blöcke ein Punkt zur Talseite und über dem Weg, daraus [DragonRail.path]
     * (Mindestradius, keine Schleifen). Die Höhe wird danach über das Gelände gehoben und geglättet.
     */
    private fun buildRail() {
        val wps = ArrayList<Vec3d>()
        val wpArc = ArrayList<Double>()
        val end = HuntRoute.arc(HuntRoute.size - 1)
        var s = 0.0
        while (s < end) {
            val q = beside(HuntRoute.indexOfArc(s), 30.0, 18.0)
            wps += Vec3d(q.x, maxOf(q.y, terrainTop(q, 12) + 12.0), q.z)
            wpArc += s
            s += 22.0
        }
        val raw = DragonRail.path(wps[0], wps[1].subtract(wps[0]), wps.drop(1))
        val arcs = DoubleArray(raw.size)
        var w = 0
        for ((k, p) in raw.withIndex()) {
            while (w < wps.lastIndex && p.squaredDistanceTo(wps[w + 1]) <= p.squaredDistanceTo(wps[w])) w++
            arcs[k] = wpArc[w]
        }
        val floorY = DoubleArray(raw.size)
        var last = 0.0
        for (k in raw.indices) {
            if (k % 4 == 0) last = terrainTop(raw[k], 9) + 9.0
            floorY[k] = last
        }
        var ys = DoubleArray(raw.size) { maxOf(raw[it].y, floorY[it]) }
        repeat(4) {
            val n = DoubleArray(ys.size)
            var sum = 0.0
            val half = 24
            for (k in 0..minOf(half, ys.lastIndex)) sum += ys[k]
            for (k in ys.indices) {
                val lo = k - half
                val hi = k + half
                n[k] = sum / (minOf(hi, ys.lastIndex) - maxOf(lo, 0) + 1)
                if (hi + 1 <= ys.lastIndex) sum += ys[hi + 1]
                if (lo >= 0) sum -= ys[lo]
            }
            ys = DoubleArray(n.size) { maxOf(n[it], floorY[it]) }
        }
        rail = EscortRail(raw.indices.map { Vec3d(raw[it].x, ys[it], raw[it].z) }, arcs)
    }

    /** Ein Tick auf der Bahn: holt zum Vordersten auf, wartet, wenn sie vorn ist; nie rückwärts. */
    private fun stepRail(d: NivarisEntity) {
        val last = rail.pts.lastIndex
        val err = rail.indexFor(lastLeaderArc + 16.0) - railS
        val v = when {
            err < -90.0 -> 0.0
            err < 0.0 -> 0.25
            else -> (err * 0.02).coerceIn(0.5, 3.4)
        }
        railS = (railS + v).coerceAtMost(last.toDouble())
        val k = floor(railS).toInt().coerceAtMost(last - 1)
        val pos = rail.pts[k].lerp(rail.pts[k + 1], railS - k)
        f.place(d, pos)
        f.face(d, rail.pts[(k + 10).coerceAtMost(last)])
    }

    /** Von wo auch immer zurück auf die Bahn, ein Stück vor dem Vordersten. */
    private fun rejoinRail(d: NivarisEntity): Script = script {
        val k = (rail.indexFor(lastLeaderArc + 16.0) + 40).coerceAtMost(rail.pts.lastIndex)
        d.flying = true
        d.play(NivarisPose.GLIDE)
        yieldAll(DragonRail.fly(f, d, listOf(rail.pts[k]), 1.6))
        railS = k.toDouble()
    }

    /** Solvane begleitet die Spieler auf der Bahn und speit von Zeit zu Zeit Frost über den Weg vor dem Vordersten. */
    private fun tickDragon(d: NivarisEntity, leader: ServerPlayerEntity, sL: Double) {
        if (dragonBusy) return
        stepRail(d)
        // In Höhlen sieht man sie nicht: dann kein Anflug mit Atem (die Höhle hat ihre eigenen Auftritte).
        val li = denseIndex(leader, 1.0, 2.0)
        val inCave = li >= 0 && HuntRoute.kind(li) == HuntRoute.TUNNEL
        if (f.now >= nextDragon && !inCave && pendingScene == null) {
            dragonBusy = true
            nextDragon = f.now + 170 + rnd.nextInt(60)
            f.task(script {
                try { yieldAll(dragonStrafe(d, sL)) } finally { dragonBusy = false }
            })
        }
    }

    /** Frostatem von der Bahn längs über den Weg vor dem Vordersten (3,5 Herzen, Frost); sie fliegt dabei weiter. */
    private fun dragonStrafe(d: NivarisEntity, sL: Double): Script = script {
        val p0 = pathAt(sL + 8.0)
        val p1 = pathAt(sL + 8.0 + STRAFE_LEN)
        val mid = p0.lerp(p1, 0.5)
        d.play(NivarisPose.INHALE, 26)
        cue("nivaris.inhale", d.entityPos, 4f)
        val line = f.calls.line(p0, p1, 2.6f, 26, Telegraph.Pace.HEAVY, style = Vfx.Style.SOUL)
        for (i in 0 until line.ticks) {
            d.throatGlow = i * 100 / line.ticks
            stepRail(d)
            yield(Unit)
        }
        d.throatGlow = 0
        d.play(NivarisPose.BREATH)
        cue("nivaris.breath", d.entityPos, 5f)
        if (!Cine.muted) {
            Vfx.beam(f.world, Vfx.Style.SOUL, d.entityPos.add(0.0, 2.0, 0.0), mid.add(0.0, 0.5, 0.0), 2.4f, 16)
            f.fx(FxKind.DRAGON_BREATH, d.entityPos, a = 26f, life = 20, e1 = d)
        }
        val hit = f.players().filter { abs(it.y - mid.y) <= 6.0 && line.contains(it) }
        for (p in hit) {
            exactHit(p, 7f, Damage.Kind.FROST_BREATH)
            dev.henny.hha_arius.gear.Frostbite.add(p, 30f)
        }
        nearMiss(mid, 6.5, hit)
        for (i in 0 until 16) {
            stepRail(d)
            yield(Unit)
        }
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
        carryWound()
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
            MusicCue.play(f, "silence", 40)
            closeMusic.clear()
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
            val below = point(HuntRoute.stationBefore(HuntRoute.arc(HuntRoute.stations.last()) - 20.0))
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

        /** Ticks, die man Schleichen halten muss, um die Seile zu kappen. */
        private const val ROPE_HOLD = 16

        // Reichweiten (seit 1.11.0 kürzer, damit man ausweichen kann; alt in Klammern).
        private const val COMBO_RANGE = 3.8f // (4,4)
        private const val WAVE_OVERSHOOT = 2.5 // Sicheln über das Ziel hinaus (8)
        private const val WAVE_MAX = 22.0 // (unbegrenzt)
        private const val SPEAR_OVERSHOOT = 3.0 // Speere über das Ziel hinaus (10)
        private const val SPEAR_MAX = 24.0 // (unbegrenzt)
        private const val AVA_HALF = 3.0 // halbe Breite der Schneewalze (6)
        private const val GALE_HALF = 4.5 // halbe Länge des Sturmstoßes (7)
        private const val STAR_RADIUS = 5f // gefrorener Stern (7)
        private const val STRAFE_LEN = 14.0 // Frostatem-Streifen (22)

        /** Laufende Jagd (nur für die Sichtprüfung). */
        @JvmStatic
        var current: Hunt? = null
            private set
    }

    // --- Nur für die Sichtprüfung (VerifyHunt) ---------------------------------------------------------------------------

    fun debugPoint(arc: Double): Vec3d = pathAt(arc)

    /** Hängebrücken als (Anfang, Ende) in Bogenlänge. */
    fun debugSpans(): List<Pair<Double, Double>> = spans.map { it.a0 to it.a1 }

    /** Höhlen als (Art, Anfang, Ende). */
    fun debugCaves(): List<Triple<String, Double, Double>> = HuntRoute.caves.map { Triple(it.theme.name, it.a0, it.a1) }

    /** Position des ersten Felsspalts in der dunklen Höhle (für die Blickprüfung in VerifyHunt). */
    fun debugEyeSlit(): Vec3d? {
        val c = HuntRoute.caves.firstOrNull { it.theme == HuntRoute.Theme.DARK && it.i0 <= HuntRoute.size / 2 } ?: return null
        val w = c.windows.firstOrNull() ?: 11.0
        val i = HuntRoute.indexOfArc(c.a0 + w)
        return beside(i, c.theme.lane + 0.35, 2.55)
    }

    /** Stellt Arius auf Bogenlänge [arc]. */
    fun debugArius(arc: Double) {
        aS = arc
        f.arius?.let { f.place(it, pathAt(arc)) }
    }

    /** Hält die Angriffe an oder gibt sie wieder frei. */
    fun debugQuiet(ticks: Int) {
        nextAttack = f.now + ticks
        nextDragon = f.now + ticks
        nextLeap = f.now + ticks
        nextCut = f.now + ticks
        nextStar = f.now + ticks
    }

    fun debugRail(): List<Vec3d> = rail.pts

    fun debugAriusArc(): Double = aS

    fun debugInScene(): Boolean = inScene

    fun debugPending(): Boolean = pendingScene != null

    /** Verschließt alle Höhlen sofort; liefert je Höhle Punkt davor und Punkt dahinter (Prüfung auf Sackgassen). */
    fun debugSealAll(): List<Pair<Vec3d, Vec3d>> = caves.map { run ->
        if (!run.sealed) sealInstant(run, emptyList())
        pathAt(run.c.a0 + 1.0) to pathAt(run.c.a0 + run.c.plugTo + 2.0)
    }
}
