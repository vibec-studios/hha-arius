package dev.henny.hha_arius.client.fx

import dev.henny.hha.client.vfx.VfxDraw
import dev.henny.hha.client.vfx.VfxLayers
import dev.henny.hha.client.vfx.lerpColor
import dev.henny.hha.client.vfx.withAlpha
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.client.world.ClientWorld
import net.minecraft.entity.Entity
import net.minecraft.entity.LivingEntity
import net.minecraft.particle.ParticleTypes
import net.minecraft.util.math.Vec3d
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Effekte von 1.8.0 (Crix, 19.11.): Aura der Drachin, Hiebsicheln und Zorn-Aura für Arius, Nachbilder im Windschritt
 * und der neue gefrorene Stern als Mond. Nur Darstellung; Treffer rechnet der Server.
 */

private val UPV = Vec3d(0.0, 1.0, 0.0)

/** Waagerechte Richtung aus dem Körper-Gierwinkel. */
private fun facing(e: Entity, td: Float): Vec3d {
    val deg = (e as? LivingEntity)?.let { net.minecraft.util.math.MathHelper.lerpAngleDegrees(td, it.lastBodyYaw, it.bodyYaw) } ?: e.yaw
    val yaw = Math.toRadians(deg.toDouble())
    return Vec3d(-sin(yaw), 0.0, cos(yaw))
}

/**
 * „Gib ihm richtig viel Aura“: Um Solvanes ganzen Körper liegt ein Frostschleier – weicher Schein je Glied, eine
 * fließende Lichtbahn entlang des Rückgrats, zwei Frostbänder, die sich um den Körper winden, glühende Augen,
 * Schneefahnen und Eisfunken. Folgt dem berechneten Körper ([DragonHits.points]); endet mit der Entity.
 */
class DragonAuraFx(private val dragon: Entity, life: Int) : AriusFx(life) {
    override val persistent: Boolean get() = true
    override fun tick(world: ClientWorld) {
        if (dragon.isRemoved) return
        val pts = DragonHits.points(dragon.id) ?: return
        val r = random()
        val n = (6 * particlesAllowed()).toInt()
        repeat(n) {
            val p = pts[r.nextInt(pts.size)]
            val o = Vec3d(r.nextDouble() - 0.5, r.nextDouble() - 0.5, r.nextDouble() - 0.5).multiply(2.4)
            world.addParticleClient(if (r.nextInt(3) == 0) ParticleTypes.END_ROD else ParticleTypes.SNOWFLAKE, p.x + o.x, p.y + o.y, p.z + o.z, o.x * 0.02, 0.02 + r.nextDouble() * 0.03, o.z * 0.02)
        }
    }

    override fun render(d: VfxDraw, matrices: MatrixStack, t: Float) {
        if (dragon.isRemoved) return
        val pts = DragonHits.points(dragon.id) ?: return
        val radii = DragonHits.radii(dragon.id) ?: return
        val k = fadeInOut(t, 20f, 20f)
        if (k <= 0.01f) return
        val pulse = 0.85f + 0.15f * sin(t * 0.15f)
        // Schein je zweitem Glied, zum Schwanz hin schwächer
        for (i in pts.indices step 2) {
            val rad = radii.getOrElse(i.coerceAtMost(radii.size - 1)) { 1.0 }
            if (rad <= 0.0) continue
            val fall = 1f - i / pts.size.toFloat() * 0.6f
            d.billboard(VfxLayers.GLOW_ADD, pts[i], rad * 3.2, withAlpha(0xFF9FD4FF.toInt(), 0.10f * k * pulse * fall))
        }
        // Lichtbahn entlang des Rückgrats (fließt nach hinten)
        val spine = pts.map { it.add(0.0, 0.6, 0.0) }
        d.ribbon(VfxLayers.FLOW_ADD, spine, { f -> 0.5 * (1.0 - f * 0.7) }, { f -> withAlpha(0xFFE6F6FF.toInt(), 0.55f * k * (1f - f)) }, uScale = 0.08f, uOffset = -t * 0.06f)
        // Zwei Frostbänder winden sich um den Körper
        for (s in 0 until 2) {
            val path = ArrayList<Vec3d>(pts.size)
            for ((i, p) in pts.withIndex()) {
                val rad = radii.getOrElse(i.coerceAtMost(radii.size - 1)) { 1.0 }.coerceAtLeast(0.6) * 1.6
                val a = i * 0.55 - t * 0.12 + s * PI
                path.add(p.add(cos(a) * rad, sin(a) * rad, sin(a + 1.3) * rad * 0.6))
            }
            d.ribbon(VfxLayers.GLOW_ADD, path, { f -> 0.12 * (1.0 - f * 0.8) }, { f -> withAlpha(0xFFBFE8FF.toInt(), 0.6f * k * (1f - f)) }, uScale = 1f, uOffset = 0f)
        }
        // Augen
        val head = pts[0]
        val fwd = facing(dragon, d.tickDelta)
        val side = Vec3d(-fwd.z, 0.0, fwd.x)
        for (sgn in intArrayOf(-1, 1)) {
            val eye = head.add(side.multiply(0.55 * sgn)).add(fwd.multiply(0.9)).add(0.0, 0.35, 0.0)
            d.billboard(VfxLayers.FLARE_ADD, eye, 0.9, withAlpha(if (sgn > 0) 0xFFFFD27A.toInt() else 0xFF8FDCFF.toInt(), 0.9f * k))
        }
        d.billboard(VfxLayers.GLOW_ADD, head, 6.0, withAlpha(0xFFBFE8FF.toInt(), 0.12f * k * pulse))
    }
}

/**
 * Hiebsichel für Arius (seit 1.8.0, Crix: „gib seinen Attacken mehr Effekte“): Ein Bogen fegt in 4 Ticks durch die
 * Bahn – breiter weißer Schleier, gleißende Schneide, blauer Saum –, dahinter verweht er in Frostfunken. [tilt] neigt
 * die Ebene (0 = waagerecht, 1 = senkrecht von oben), [range] ist die Reichweite.
 */
class SlashArcFx(private val at: Vec3d, private val dir: Vec3d, private val range: Double, private val tilt: Double, life: Int) : AriusFx(life) {
    private val fwd = Vec3d(dir.x, 0.0, dir.z).let { if (it.lengthSquared() < 1e-6) Vec3d(0.0, 0.0, 1.0) else it.normalize() }
    private val right = Vec3d(-fwd.z, 0.0, fwd.x)
    private val axisB = right.multiply(cos(tilt * PI / 2)).add(UPV.multiply(sin(tilt * PI / 2))).normalize()

    override fun tick(world: ClientWorld) {
        if (age > 6) return
        val r = random()
        repeat((8 * particlesAllowed()).toInt()) {
            val a = (r.nextDouble() - 0.5) * 2.4
            val rr = range * (0.6 + r.nextDouble() * 0.45)
            val p = at.add(fwd.multiply(cos(a) * rr)).add(axisB.multiply(sin(a) * rr))
            world.addParticleClient(if (r.nextInt(3) == 0) ParticleTypes.END_ROD else ParticleTypes.SNOWFLAKE, p.x, p.y, p.z, fwd.x * 0.08, 0.02, fwd.z * 0.08)
        }
    }

    override fun render(d: VfxDraw, matrices: MatrixStack, t: Float) {
        val sweep = (t / 4f).coerceIn(0f, 1f).toDouble()
        val fade = (1f - (t - 4f) / (life - 4f)).coerceIn(0f, 1f)
        if (fade <= 0f) return
        val span = 2.4
        val a0 = -span / 2
        val a1 = a0 + span * sweep
        val inner = range * 0.35
        d.arc(VfxLayers.FLOW_ADD, at, fwd, axisB, inner, range * 1.05, a0, a1, withAlpha(0xFFFFFFFF.toInt(), 0f), withAlpha(0xFFE8F6FF.toInt(), 0.55f * fade), segments = 28, uTile = 2f, uOffset = -t * 0.3f)
        d.arc(VfxLayers.GLOW_ADD, at, fwd, axisB, range * 0.88, range * 1.0, a0, a1, withAlpha(0xFF8FC8FF.toInt(), 0.2f * fade), withAlpha(0xFFFFFFFF.toInt(), 0.95f * fade), segments = 28)
        d.arc(VfxLayers.GLOW_ADD, at, fwd, axisB, range * 1.0, range * 1.14, a0, a1, withAlpha(0xFF6FB0FF.toInt(), 0.5f * fade), withAlpha(0xFF6FB0FF.toInt(), 0f), segments = 28)
        // Schneidenspitze
        val tipA = a1
        val tip = at.add(fwd.multiply(cos(tipA) * range)).add(axisB.multiply(sin(tipA) * range))
        if (sweep < 1.0) d.billboard(VfxLayers.FLARE_ADD, tip, 1.4, withAlpha(0xFFFFFFFF.toInt(), 0.9f))
    }
}

/**
 * Zorn-Aura (Jagd, seit 1.8.0): Weiße Flammen züngeln von Arius auf, eine Lichtsäule steht über ihm, der Boden unter
 * ihm reißt in einem Ring aus Licht; die Augen glühen. Folgt der Entity.
 */
class RageAuraFx(private val e: Entity, life: Int) : AriusFx(life) {
    override val persistent: Boolean get() = true
    override fun tick(world: ClientWorld) {
        if (e.isRemoved) return
        val r = random()
        val c = e.entityPos
        repeat((5 * particlesAllowed()).toInt()) {
            val a = r.nextDouble() * PI * 2
            val rr = 0.4 + r.nextDouble() * 0.7
            world.addParticleClient(if (r.nextInt(2) == 0) ParticleTypes.END_ROD else ParticleTypes.SNOWFLAKE, c.x + cos(a) * rr, c.y + r.nextDouble() * 2.2, c.z + sin(a) * rr, 0.0, 0.06 + r.nextDouble() * 0.06, 0.0)
        }
    }

    override fun render(d: VfxDraw, matrices: MatrixStack, t: Float) {
        if (e.isRemoved) return
        val k = fadeInOut(t, 10f, 20f)
        val base = e.getLerpedPos(d.tickDelta)
        val flick = 0.8f + 0.2f * sin(t * 0.9f) * sin(t * 0.37f)
        // Flammen: drei Lagen aufsteigender Zungen
        for (i in 0 until 9) {
            val a = i / 9.0 * PI * 2 + t * 0.05
            val rr = 0.55 + 0.15 * sin(t * 0.3 + i)
            val h = 1.4 + 0.8 * abs(sin(t * 0.21 + i * 1.7))
            val p0 = base.add(cos(a) * rr, 0.1, sin(a) * rr)
            val p1 = base.add(cos(a) * rr * 0.4, h + 0.9, sin(a) * rr * 0.4)
            d.band(VfxLayers.FLOW_ADD, p0, p1, d.camRight, 0.28, withAlpha(0xFFE8F6FF.toInt(), 0.5f * k * flick), withAlpha(0xFFBFE8FF.toInt(), 0f), uScale = 0.6f, uOffset = -t * 0.12f)
        }
        // Lichtsäule
        d.band(VfxLayers.GLOW_ADD, base, base.add(0.0, 14.0, 0.0), d.camRight, 0.6, withAlpha(0xFFFFFFFF.toInt(), 0.35f * k), withAlpha(0xFFBFE8FF.toInt(), 0f))
        d.billboard(VfxLayers.GLOW_ADD, base.add(0.0, 1.2, 0.0), 5.0, withAlpha(0xFFBFE8FF.toInt(), 0.35f * k * flick))
        // Bodenring
        d.ring(VfxLayers.GLOW_ADD, base.add(0.0, 0.06, 0.0), AriusFx.X, AriusFx.Z, 1.5, 2.1, withAlpha(0xFFFFFFFF.toInt(), 0f), withAlpha(0xFFE6F6FF.toInt(), 0.55f * k), segments = 40)
        d.ring(VfxLayers.FLOW_ADD, base.add(0.0, 0.05, 0.0), AriusFx.X, AriusFx.Z, 1.0, 3.6, withAlpha(0xFFBFE8FF.toInt(), 0.3f * k), withAlpha(0xFFBFE8FF.toInt(), 0f), segments = 40, uTile = 3f, uOffset = t * 0.05f)
        // Augen
        val fwd = facing(e, d.tickDelta)
        val side = Vec3d(-fwd.z, 0.0, fwd.x)
        val eyes = base.add(0.0, 2.0, 0.0).add(fwd.multiply(0.22))
        d.billboard(VfxLayers.FLARE_ADD, eyes.add(side.multiply(0.1)), 0.35, withAlpha(0xFFFFD27A.toInt(), k))
        d.billboard(VfxLayers.FLARE_ADD, eyes.subtract(side.multiply(0.1)), 0.35, withAlpha(0xFF8FDCFF.toInt(), k))
    }
}

/**
 * Windschritt (seit 1.8.0): weiße Geschwindigkeitslinien entlang der Bahn und fünf verblassende Nachbilder als
 * leuchtende Schattenrisse (Kopf, Rumpf, Mantel), die von hinten nach vorn erlöschen.
 */
class DashStreakFx(private val from: Vec3d, private val to: Vec3d, life: Int) : AriusFx(life) {
    override fun render(d: VfxDraw, matrices: MatrixStack, t: Float) {
        val k = (1f - t / life).coerceIn(0f, 1f)
        if (k <= 0f) return
        val dir = to.subtract(from).let { if (it.lengthSquared() < 1e-6) Vec3d(0.0, 0.0, 1.0) else it.normalize() }
        val side = Vec3d(-dir.z, 0.0, dir.x)
        for (i in 0 until 7) {
            val o = side.multiply((i - 3) * 0.22).add(0.0, 0.3 + (i % 3) * 0.6, 0.0)
            d.band(VfxLayers.FLOW_ADD, from.add(o), to.add(o), UPV, 0.05, withAlpha(0xFFFFFFFF.toInt(), 0f), withAlpha(0xFFE8F6FF.toInt(), 0.6f * k), uScale = 0.4f, uOffset = -t * 0.4f)
        }
        for (i in 0 until 5) {
            val f = i / 4.0
            val p = from.lerp(to, f)
            val kk = (k * (0.3f + 0.7f * f.toFloat()) - (1f - f.toFloat()) * t / life).coerceIn(0f, 1f)
            if (kk <= 0.02f) continue
            val col = withAlpha(0xFFDDF2FF.toInt(), 0.35f * kk)
            d.box(VfxLayers.GLOW_ADD, p.add(0.0, 1.2, 0.0), side.multiply(0.3), UPV.multiply(0.45), dir.multiply(0.14), col)
            d.box(VfxLayers.GLOW_ADD, p.add(0.0, 1.9, 0.0), side.multiply(0.16), UPV.multiply(0.17), dir.multiply(0.16), col)
            d.box(VfxLayers.GLOW_ADD, p.add(0.0, 0.45, 0.0), side.multiply(0.24), UPV.multiply(0.42), dir.multiply(0.12), withAlpha(0xFFDDF2FF.toInt(), 0.2f * kk))
        }
    }
}

/**
 * „Der gefrorene Stern“ als Mond (seit 1.8.0, Crix: „der Meteor/Mond braucht mehr Design und Animation“):
 * 1. Ein gleißender Punkt hoch über dem Ziel; Lichtfunken spiralen hinein, Strahlen.
 * 2. Er wächst zum Mond: eisblaue Kugel mit dunkleren Kratern, die sich langsam dreht, ein schräger Lichtring und
 *    kreisende Eissplitter; von außen wachsen Kristallzacken.
 * 3. Sturz: Schweif aus Licht und Frostrauch, auf dem Boden wächst der Schatten, Luftflimmern.
 * 4. Einschlag: Blitz, Splitter fliegen auseinander, Druckwelle, Frostkrater, der noch kurz glimmt.
 */
class MoonFx(private val ground: Vec3d, private val radius: Double, private val impactAt: Int, life: Int) : AriusFx(life) {
    private val craters: List<Vec3d> = (0 until 9).map { i ->
        val a = i * 2.39996
        val e = ((i * 0.618) % 1.0) * 2 - 1
        Vec3d(cos(a) * sqrt(1 - e * e), e, sin(a) * sqrt(1 - e * e))
    }

    override fun tick(world: ClientWorld) {
        val r = random()
        if (age < impactAt) {
            val c = center(age.toFloat())
            repeat((4 * particlesAllowed()).toInt()) {
                val a = r.nextDouble() * PI * 2
                val dd = 6.0 + r.nextDouble() * 10.0
                val p = c.add(cos(a) * dd, (r.nextDouble() - 0.5) * 8.0, sin(a) * dd)
                val v = c.subtract(p).multiply(0.06)
                world.addParticleClient(ParticleTypes.END_ROD, p.x, p.y, p.z, v.x, v.y, v.z)
            }
        } else if (age < impactAt + 3) {
            repeat((60 * particlesAllowed()).toInt()) {
                val a = r.nextDouble() * PI * 2
                val v = Vec3d(cos(a), 0.4 + r.nextDouble() * 0.8, sin(a)).multiply(0.4 + r.nextDouble() * 0.6)
                world.addParticleClient(if (r.nextInt(2) == 0) ParticleTypes.SNOWFLAKE else ParticleTypes.END_ROD, ground.x, ground.y + 0.5, ground.z, v.x, v.y, v.z)
            }
        }
    }

    private fun fall(t: Float) = ((t - impactAt * 0.75f) / (impactAt * 0.25f)).coerceIn(0f, 1f)

    private fun center(t: Float): Vec3d {
        val f = fall(t)
        return ground.add(0.0, 40.0 * (1.0 - f * f) + 1.0, 0.0)
    }

    override fun render(d: VfxDraw, matrices: MatrixStack, t: Float) {
        if (t >= impactAt) {
            impact(d, t - impactAt)
            return
        }
        val form = (t / (impactAt * 0.35f)).coerceIn(0f, 1f)
        val grow = ((t - impactAt * 0.2f) / (impactAt * 0.45f)).coerceIn(0f, 1f)
        val freeze = ((t - impactAt * 0.35f) / (impactAt * 0.4f)).coerceIn(0f, 1f)
        val f = fall(t)
        val c = center(t)
        val r = radius * (0.12 + 0.55 * grow * grow)
        val cold = 0xFFBFE8FF.toInt()
        val warm = 0xFFFFF0C8.toInt()
        val col = lerpColor(warm, cold, freeze)
        // 1) Lichtpunkt und Strahlen
        d.billboard(VfxLayers.FLARE_ADD, c, 3.0 + 4.0 * form, withAlpha(0xFFFFFFFF.toInt(), 0.9f * form))
        d.billboard(VfxLayers.RAYS_ADD, c, r * 6.0 + 10.0 * form, withAlpha(warm, 0.45f * form * (1f - freeze * 0.7f)), t * 0.8)
        // 2) Mondkörper mit Kratern, dreht sich
        if (grow > 0f) {
            d.sphere(VfxLayers.SHARD_ALPHA, c, r, { up -> withAlpha(lerpColor(lerpColor(0xFF7FA8D0.toInt(), col, 0.6f), 0xFFFFFFFF.toInt(), (0.45f + 0.55f * up).coerceIn(0f, 1f)), 0.95f * grow) }, lat = 14, lon = 24)
            val spin = t * 0.03
            for (cr in craters) {
                val v = Vec3d(cr.x * cos(spin) - cr.z * sin(spin), cr.y, cr.x * sin(spin) + cr.z * cos(spin))
                val camDir = d.cam.subtract(c).normalize()
                if (v.dotProduct(camDir) < 0.2) continue
                d.billboard(VfxLayers.SMOKE_ALPHA, c.add(v.multiply(r * 1.01)), r * 0.38, withAlpha(0xFF46607E.toInt(), 0.35f * grow))
            }
            d.billboard(VfxLayers.GLOW_ADD, c, r * 3.6, withAlpha(col, 0.4f * grow))
            // schräger Lichtring
            val ax = Vec3d(1.0, 0.35, 0.0).normalize()
            val az = Vec3d(0.0, -0.2, 1.0).normalize()
            d.ring(VfxLayers.GLOW_ADD, c, ax, az, r * 1.5, r * 1.75, withAlpha(0xFFFFFFFF.toInt(), 0.1f * grow), withAlpha(cold, 0.55f * grow), segments = 48)
            // kreisende Eissplitter
            for (i in 0 until 10) {
                val a = i / 10.0 * PI * 2 + t * 0.06
                val p = c.add(ax.multiply(cos(a) * r * 2.1)).add(az.multiply(sin(a) * r * 2.1))
                d.box(VfxLayers.SHARD_ALPHA, p, AriusFx.X.multiply(0.25), UPV.multiply(0.4), AriusFx.Z.multiply(0.25), withAlpha(0xFFDDF2FF.toInt(), 0.8f * grow))
            }
        }
        // Kristallzacken beim Gefrieren
        if (freeze > 0f) for (i in 0 until 16) {
            val a = i * 2.39996
            val e = (i * 0.618 % 1.0) * 2 - 1
            val dir = Vec3d(cos(a) * sqrt(1 - e * e), e, sin(a) * sqrt(1 - e * e))
            d.band(VfxLayers.GLOW_ADD, c.add(dir.multiply(r * 0.95)), c.add(dir.multiply(r * (1.0 + 0.7 * freeze))), d.camUp, r * 0.12, withAlpha(0xFFFFFFFF.toInt(), 0.85f * freeze), withAlpha(cold, 0.15f * freeze))
        }
        // 3) Sturz: Schweif, Rauch, Schatten am Boden
        if (f > 0f) {
            d.band(VfxLayers.FLOW_ADD, c, c.add(0.0, 26.0 * f, 0.0), d.camRight, r * 1.1, withAlpha(cold, 0.7f), withAlpha(cold, 0f), uScale = 0.25f, uOffset = -t * 0.25f)
            d.band(VfxLayers.GLOW_ADD, c, c.add(0.0, 16.0 * f, 0.0), d.camRight, r * 0.55, withAlpha(0xFFFFFFFF.toInt(), 0.8f), withAlpha(0xFFFFFFFF.toInt(), 0f))
            for (i in 1..5) d.billboard(VfxLayers.SMOKE_ALPHA, c.add(0.0, i * 4.0 * f, 0.0), r * (1.2 + i * 0.3), withAlpha(0xFFE6EEF6.toInt(), 0.25f * (1f - i / 6f)))
        }
        val shadow = (t / impactAt).coerceIn(0f, 1f)
        d.disc(VfxLayers.SMOKE_ALPHA, ground.add(0.0, 0.07, 0.0), AriusFx.X, AriusFx.Z, radius * (0.3 + 0.7 * shadow), withAlpha(0xFF1B2A44.toInt(), 0.35f * shadow))
        d.ring(VfxLayers.GLOW_ADD, ground.add(0.0, 0.08, 0.0), AriusFx.X, AriusFx.Z, radius - 0.35, radius, withAlpha(cold, 0f), withAlpha(cold, 0.6f * shadow), segments = 64)
    }

    private fun impact(d: VfxDraw, s: Float) {
        val k = (1f - s / (life - impactAt).coerceAtLeast(1)).coerceIn(0f, 1f)
        if (k <= 0f) return
        val burst = (s / 6f).coerceIn(0f, 1f)
        d.billboard(VfxLayers.FLARE_ADD, ground.add(0.0, 1.5, 0.0), 14.0 * (1f - burst) + 2.0, withAlpha(0xFFFFFFFF.toInt(), (1f - burst) * 0.95f))
        d.ring(VfxLayers.GLOW_ADD, ground.add(0.0, 0.2, 0.0), AriusFx.X, AriusFx.Z, radius * (0.4 + 1.4 * burst), radius * (0.6 + 1.6 * burst), withAlpha(0xFFFFFFFF.toInt(), 0.8f * k), withAlpha(0xFFBFE8FF.toInt(), 0f), segments = 64)
        // Splitter fliegen auseinander und fallen
        for (i in 0 until 18) {
            val a = i / 18.0 * PI * 2 + i
            val sp = 0.5 + (i % 5) * 0.12
            val p = ground.add(cos(a) * s * sp, 0.6 + s * 0.5 - s * s * 0.02, sin(a) * s * sp)
            if (p.y < ground.y) continue
            d.box(VfxLayers.SHARD_ALPHA, p, AriusFx.X.multiply(0.3), UPV.multiply(0.5), AriusFx.Z.multiply(0.3), withAlpha(0xFFDDF2FF.toInt(), 0.85f * k))
        }
        // Frostkrater
        d.disc(VfxLayers.GLOW_ADD, ground.add(0.0, 0.06, 0.0), AriusFx.X, AriusFx.Z, radius * 0.9, withAlpha(0xFF8FC8FF.toInt(), 0.35f * k))
    }
}
