package dev.henny.hha_arius.boss

import dev.henny.hha.logic.Fx
import dev.henny.hha.logic.Vfx
import dev.henny.hha.logic.boss.Cine
import dev.henny.hha.logic.boss.Telegraph
import dev.henny.hha_arius.net.FxKind
import net.minecraft.util.math.Vec3d
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Echte Duo-Angriffe von Arius und Solvane (seit 1.9.0, Crix: „es gibt keine richtigen Duo-Angriffe“). Beide handeln
 * gemeinsam und gleichzeitig; jeder Angriff hat eine Titelkarte, eine lange Ansage und **goldene sichere Bahnen bzw.
 * Felder**. Solvanes eigene Angriffe stehen in [NivarisMoves]; hier liegt nur die Abstimmung und Arius' Teil (die
 * Drachin wird dafür direkt geführt und am Ende mit [NivarisMoves.land] an ihr normales Verhalten übergeben).
 */
class Duo(private val f: EirholmFight) {

    private val rnd = java.util.Random()

    private fun flatDir(from: Vec3d, to: Vec3d): Vec3d {
        val d = Vec3d(to.x - from.x, 0.0, to.z - from.z)
        return if (d.lengthSquared() < 1e-6) Vec3d(0.0, 0.0, 1.0) else d.normalize()
    }

    private fun floor(p: Vec3d) = Vec3d(p.x, f.floorY, p.z)

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

    /** Drachin weich von ihrer Lage nach [to] in [ticks] Ticks, Blick nach [look]; Arius reitet mit, wenn [rider]. */
    private suspend fun SequenceScope<Unit>.glide(d: NivarisEntity, to: Vec3d, ticks: Int, look: Vec3d, rider: AriusEntity?) {
        val from = d.entityPos
        for (i in 1..ticks.coerceAtLeast(1)) {
            val k = i / ticks.toDouble()
            val e = k * k * (3 - 2 * k)
            f.place(d, from.lerp(to, e))
            f.face(d, look)
            rider?.let { ride(it, d) }
            yield(Unit)
        }
    }

    // --- Drachenritt ----------------------------------------------------------------------------------------------------

    /**
     * **Drachenritt**: Solvane holt Arius ab, er steht auf ihrem Nacken. Zwei Sturzflüge quer über das Feld: Sie fegt die
     * breite Mittelbahn, er schlägt zu beiden Seiten Sicheln ab (Seitenbahnen); dazwischen liegen goldene sichere Bahnen.
     * Der zweite Flug kreuzt den ersten. Zum Schluss springt er ab und stürzt mit der Klinge auf das Ziel (Ring), sie
     * landet daneben. Danach stehen beide offen.
     */
    fun dragonRide(): Script = script {
        val a = f.arius ?: return@script
        val d = f.nivaris ?: return@script
        var t = f.target() ?: return@script
        announce("dragon_ride", a, d)
        // 1) Sie holt ihn ab: tief neben ihm, er springt auf.
        val toT = flatDir(a.entityPos, t.entityPos)
        val pick = a.entityPos.add(Vec3d(-toT.z, 0.0, toT.x).multiply(4.0)).add(0.0, 1.5, 0.0)
        d.flying = true
        d.play(NivarisPose.HOVER)
        yieldAll(script { glide(d, pick, 22, t.entityPos, null) })
        val jumpFrom = a.entityPos
        a.play(AriusPose.LAUNCH, 8)
        f.ambient("arius.wind_step", jumpFrom, 2.5f, 0.9f)
        for (i in 1..10) {
            val k = i / 10.0
            f.place(a, jumpFrom.lerp(neck(d), k).add(0.0, sin(k * PI) * 2.5, 0.0))
            yield(Unit)
        }
        a.play(AriusPose.CUT_CHARGE)
        f.ambient("nivaris.roar", d.entityPos, 4f, 1.1f)
        // 2) Zwei Sturzflüge.
        var lastDir: Vec3d? = null
        for (pass in 0 until 2) {
            t = f.target() ?: break
            val center = floor(f.clampToArena(t.entityPos, 6.0))
            val dir = lastDir?.let { ld -> Vec3d(-ld.z, 0.0, ld.x).multiply(if (rnd.nextBoolean()) 1.0 else -1.0) } ?: flatDir(d.entityPos, center)
            lastDir = dir
            val side = Vec3d(-dir.z, 0.0, dir.x)
            val reach = (f.arenaRadius - 3.0).coerceIn(14.0, 26.0)
            val start = floor(f.clampToArena(center.subtract(dir.multiply(reach)), 2.0))
            val end = floor(f.clampToArena(center.add(dir.multiply(reach)), 2.0))
            val lead = if (pass == 0) 44 else 30
            val group = f.calls.boss(style = Vfx.Style.SOUL)
            val main = group.line(start, end, 5f, lead, Telegraph.Pace.PROJECTILE)
            val left = group.line(start.add(side.multiply(8.0)), end.add(side.multiply(8.0)), 2.8f, lead + 4, Telegraph.Pace.FOLLOW_UP)
            val right = group.line(start.subtract(side.multiply(8.0)), end.subtract(side.multiply(8.0)), 2.8f, lead + 4, Telegraph.Pace.FOLLOW_UP)
            group.safeLine(start.add(side.multiply(4.6)), end.add(side.multiply(4.6)), 2.4f, lead + 4)
            group.safeLine(start.subtract(side.multiply(4.6)), end.subtract(side.multiply(4.6)), 2.4f, lead + 4)
            // Anflug an den Bahnanfang, hoch und hinter der Bahn.
            val air = start.subtract(dir.multiply(6.0)).add(0.0, 9.0, 0.0)
            d.play(NivarisPose.GLIDE)
            yieldAll(script { glide(d, air, (main.ticks - 6).coerceAtLeast(6), end, a) })
            // Sturzflug: tief über die Bahn, er schlägt nach beiden Seiten.
            d.play(NivarisPose.DIVE)
            f.ambient("nivaris.roar", d.entityPos, 5f, 0.95f)
            f.ambient("nivaris.wing", d.entityPos, 5f, 0.6f)
            val exit = end.add(dir.multiply(6.0)).add(0.0, 2.5, 0.0)
            val low = air
            for (i in 1..14) {
                val k = i / 14.0
                val pos = low.lerp(exit, k).let { Vec3d(it.x, f.floorY + 2.5 + (low.y - f.floorY - 2.5) * (1 - k) * (1 - k), it.z) }
                f.place(d, pos)
                f.face(d, exit.add(dir.multiply(10.0)))
                ride(a, d)
                if (i == 6) {
                    d.play(NivarisPose.GLIDE)
                    f.ambient("nivaris.crash", center, 3f, 1.3f)
                    for (p in main.hits(f.players())) {
                        f.hurt(p, 11f, Damage.Kind.IMPACT, d)
                        val s = if ((p.x - start.x) * side.x + (p.z - start.z) * side.z > 0) 1.0 else -1.0
                        Fx.launchPlayer(p, side.multiply(0.9 * s).add(0.0, 0.5, 0.0))
                    }
                    for (q in 1..3) f.fx(FxKind.CLEAR_WAVE, start.lerp(end, q / 4.0), a = 5f, life = 16)
                }
                if (i == 10) {
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
            // Hochziehen und wenden.
            yieldAll(script { glide(d, exit.add(dir.multiply(8.0)).add(0.0, 9.0, 0.0), 12, f.arenaCenter, a) })
        }
        // 3) Absprung und Sturz auf das Ziel; sie landet daneben.
        val target = f.target() ?: t
        val spot = floor(f.clampToArena(target.entityPos, 2.0))
        val ring = f.calls.ring(spot, 4.6f, 24, pace = Telegraph.Pace.HEAVY)
        f.ambient("iai.glint", a.entityPos, 3f, 0.7f)
        a.play(AriusPose.STAB_GROUND, ring.ticks)
        val launch = a.entityPos
        val top = launch.lerp(spot, 0.4).add(0.0, 7.0, 0.0)
        val leave = d.entityPos.add(flatDir(spot, d.entityPos).multiply(10.0)).add(0.0, 6.0, 0.0)
        val dFrom = d.entityPos
        for (i in 1..ring.ticks) {
            val k = i / ring.ticks.toDouble()
            f.place(a, if (k < 0.5) launch.lerp(top, k * 2) else top.lerp(spot, ((k - 0.5) * 2).let { it * it }))
            f.face(a, spot)
            f.place(d, dFrom.lerp(leave, k))
            yield(Unit)
        }
        f.place(a, spot)
        f.ambient("arius.stab_ground", spot, 4f, 0.7f)
        f.ambient("cut.strike", spot, 3f, 0.8f)
        f.calls.roomShake(spot, 5f, 30f)
        Vfx.shockwave(f.world, Vfx.Style.HEAVEN, spot, 7f, 14, 1.8f)
        f.fx(FxKind.CLEAR_WAVE, spot, a = 9f, life = 16)
        for (q in 0 until 12) f.shards.spike(spot.add(cos(q * PI / 6) * 4.2, 0.0, sin(q * PI / 6) * 4.2), 1.6 + rnd.nextDouble(), 40)
        for (p in ring.hits(f.players())) {
            f.hurt(p, 13f, Damage.Kind.NULL_CUT)
            Fx.launchPlayer(p, flatDir(spot, p.entityPos).multiply(0.8).add(0.0, 0.5, 0.0))
        }
        yieldAll(f.dragon.land(f.target()))
        a.play(AriusPose.STAGGER)
        f.openArius(34)
        f.openDragon(34)
        wait(34)
    }

    // --- Kreuzschnitt ---------------------------------------------------------------------------------------------------

    /**
     * **Kreuzschnitt**: eine Zange von zwei Seiten. Arius stellt sich auf der einen Seite des Ziels auf, Solvane auf der
     * anderen, quer dazu. Beide stürmen gleichzeitig: Sie fegt tief eine breite Bahn, er schneidet quer – ein X durch das
     * Ziel; kurz danach bricht die Mitte auf. Sicher sind die vier goldenen Felder zwischen den Armen. Die zweite Runde
     * ist um 45° gedreht und schneller.
     */
    fun crossCut(): Script = script {
        val a = f.arius ?: return@script
        val d = f.nivaris ?: return@script
        announce("cross_cut", a, d)
        var ang = rnd.nextDouble() * PI
        for (round in 0 until 2) {
            val t = f.target() ?: break
            val center = floor(f.clampToArena(t.entityPos, 9.0))
            val axisA = Vec3d(sin(ang), 0.0, cos(ang))
            val axisB = Vec3d(-axisA.z, 0.0, axisA.x)
            val sign = if (rnd.nextBoolean()) 1.0 else -1.0
            val dFrom = floor(f.clampToArena(center.subtract(axisA.multiply(18.0 * sign)), 1.5)).add(0.0, 3.5, 0.0)
            val dTo = floor(f.clampToArena(center.add(axisA.multiply(18.0 * sign)), 1.5)).add(0.0, 3.0, 0.0)
            val aFrom = floor(f.clampToArena(center.subtract(axisB.multiply(13.0 * sign)), 1.0))
            val aTo = floor(f.clampToArena(center.add(axisB.multiply(13.0 * sign)), 1.0))
            val lead = if (round == 0) 42 else 28
            // Aufstellen: er im Windschritt, sie im Bogen.
            val aStart = a.entityPos
            a.play(AriusPose.LUNGE, lead)
            f.ambient("arius.wind_step", aStart, 2.4f, 1.1f)
            f.fx(FxKind.DASH_STREAK, aStart, aFrom, life = 12)
            d.flying = true
            d.play(NivarisPose.GLIDE)
            val dStart = d.entityPos
            val group = f.calls.boss(style = Vfx.Style.SOUL)
            val charge = group.line(Vec3d(dFrom.x, f.floorY, dFrom.z), Vec3d(dTo.x, f.floorY, dTo.z), 4.2f, lead, Telegraph.Pace.HEAVY)
            val cut = group.line(aFrom, aTo, 2.6f, lead, Telegraph.Pace.HEAVY)
            val core = group.ring(center, 3.4f, lead + 14, pace = Telegraph.Pace.AREA)
            for (qa in listOf(1.0, -1.0)) for (qb in listOf(1.0, -1.0)) {
                group.safe(center.add(axisA.multiply(qa * 6.5)).add(axisB.multiply(qb * 6.5)), 2.6f, lead + 14)
            }
            f.ambient("nivaris.inhale", d.entityPos, 3f, 1.1f)
            f.ambient("cut.charge", a.entityPos, 3f, 1.2f)
            val setup = (lead - 4).coerceAtLeast(8)
            for (i in 1..setup) {
                val k = (i / setup.toDouble()).let { it * it * (3 - 2 * it) }
                if (i <= 6) f.place(a, aStart.lerp(aFrom, i / 6.0))
                f.face(a, center)
                f.place(d, dStart.lerp(dFrom, k).add(0.0, sin(k * PI) * 5.0, 0.0))
                f.face(d, center)
                if (i == setup / 2) f.fx(FxKind.THREAD, a.entityPos, life = setup, e1 = a, e2 = d)
                yield(Unit)
            }
            a.play(AriusPose.CUT_CHARGE)
            wait((charge.remaining).coerceAtLeast(0))
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
            f.fx(FxKind.SEAM, Vec3d(dFrom.x, f.floorY + 0.2, dFrom.z), Vec3d(dTo.x, f.floorY + 0.2, dTo.z), a = 3f, life = 24)
            f.fx(FxKind.DASH_STREAK, aFrom, aTo, life = 12)
            for (i in 1..10) {
                val k = i / 10.0
                f.place(d, dFrom.lerp(dTo, k).let { Vec3d(it.x, f.floorY + 2.2, it.z) })
                f.face(d, dTo.add(axisA.multiply(10.0 * sign)))
                if (i <= 5) f.place(a, aFrom.lerp(aTo, i / 5.0))
                if (i == 5) {
                    // Die Arme kreuzen sich: Blitz in der Mitte.
                    Vfx.flare(f.world, Vfx.Style.HEAVEN, center.add(0.0, 1.5, 0.0), 3f, 8)
                    f.fx(FxKind.SLASH_ARC, center.add(0.0, 1.2, 0.0), axisB, a = 9f, b = 0f, life = 14)
                    f.fx(FxKind.SLASH_ARC, center.add(0.0, 1.0, 0.0), axisA, a = 9f, b = 0.2f, life = 14)
                    f.calls.roomShake(center, 4f, 30f)
                }
                yield(Unit)
            }
            f.face(a, center)
            wait((core.remaining).coerceAtLeast(0))
            f.ambient("core.collapse", center, 4f, 1.3f)
            Vfx.nova(f.world, Vfx.Style.HEAVEN, center.add(0.0, 0.3, 0.0), 3.8f, 12)
            f.fx(FxKind.CLEAR_WAVE, center, a = 7f, life = 14)
            for (p in core.hits(f.players())) {
                f.hurt(p, 10f, Damage.Kind.IMPACT)
                Fx.launchPlayer(p, flatDir(center, p.entityPos).multiply(0.6).add(0.0, 0.5, 0.0))
            }
            ang += PI / 4
            wait(6)
        }
        d.play(NivarisPose.HOVER)
        yieldAll(f.dragon.land(f.target()))
        a.play(AriusPose.STAGGER)
        f.openArius(32)
        f.openDragon(26)
        wait(32)
    }

    @Suppress("unused")
    private fun dist(a: Vec3d, b: Vec3d) = sqrt(a.squaredDistanceTo(b))
}
