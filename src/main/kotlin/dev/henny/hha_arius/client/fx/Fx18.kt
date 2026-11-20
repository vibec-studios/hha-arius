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
 * „Der gefrorene Stern“ als Mond (seit 1.8.0, Crix: „der Meteor/Mond braucht mehr Design und Animation“; seit 1.8.1
 * neu gebaut, Crix: „der Mond sieht immer noch schlecht aus“):
 * 1. Ein gleißender Punkt hoch über dem Ziel; Lichtfunken spiralen hinein.
 * 2. Er wächst zum Mond: eine fein unterteilte Eiskugel mit echter Beleuchtung (Licht, Schattengrenze, blaues
 *    Erdlicht auf der Nachtseite), Kratern mit hellen Rändern, dunkleren Eisebenen („Maria“) und Kornstruktur. Beim
 *    Gefrieren nimmt er zu – von der schmalen Sichel bis zum Vollmond –, leuchtende Eisrisse ziehen über die Tagseite,
 *    ein weicher Halo, eine Korona und ein dünner Eishalo umgeben ihn, Frostnebel kreist, ein schräger Ring aus
 *    Eissplittern dreht sich, von außen wachsen Kristallzacken.
 * 3. Sturz: Schweif aus Licht und Frostrauch, auf dem Boden wächst der Schatten.
 * 4. Einschlag: Blitz, Splitter fliegen auseinander, Druckwelle, Frostkrater, der noch kurz glimmt.
 * Körper und Leuchten liegen auf eigenen Ebenen ohne Vanilla-Nebel ([AriusLayers]): auch im Whiteout der Jagd sichtbar.
 */
class MoonFx(private val ground: Vec3d, private val radius: Double, private val impactAt: Int, life: Int) : AriusFx(life) {

    private class Crater(val dir: Vec3d, val size: Double, val depth: Float)

    private val craters: List<Crater> = (0 until 16).map { i ->
        val a = i * 2.39996 + 0.7
        val e = ((i * 0.618 + 0.31) % 1.0) * 2 - 1
        val s = sqrt(1 - e * e)
        Crater(Vec3d(cos(a) * s, e, sin(a) * s), 0.12 + ((i * 0.377) % 1.0) * 0.26, 0.55f + ((i * 0.53) % 1.0).toFloat() * 0.45f)
    }

    /** Eisrisse: Großkreisbögen (Achse, Startwinkel, Länge). */
    private val cracks: List<DoubleArray> = (0 until 6).map { i ->
        val a = i * 1.9 + 0.4
        val e = ((i * 0.41 + 0.2) % 1.0) * 2 - 1
        val s = sqrt(1 - e * e)
        doubleArrayOf(cos(a) * s, e, sin(a) * s, i * 1.3, 0.9 + (i % 3) * 0.4)
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
            if (age > impactAt * 0.4 && r.nextFloat() < 0.6f * particlesAllowed()) {
                val a = r.nextDouble() * PI * 2
                val rr = radius * 0.7 * (1.0 + r.nextDouble())
                world.addParticleClient(ParticleTypes.SNOWFLAKE, c.x + cos(a) * rr, c.y + (r.nextDouble() - 0.5) * rr, c.z + sin(a) * rr, 0.0, -0.05, 0.0)
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

    /** Drehung der Oberfläche: um eine leicht geneigte Achse. */
    private fun spin(n: Vec3d, ang: Double): Vec3d {
        val x = n.x * cos(ang) - n.z * sin(ang)
        val z = n.x * sin(ang) + n.z * cos(ang)
        val tilt = 0.41
        return Vec3d(x, n.y * cos(tilt) - z * sin(tilt), n.y * sin(tilt) + z * cos(tilt))
    }

    /** Helligkeit der Oberfläche (Albedo 0..1) in Richtung [s] (Oberflächenkoordinaten, nicht gedreht). */
    private fun albedo(s: Vec3d): Float {
        // Eisebenen: weiche, große Flecken
        val mare = (sin(s.x * 3.1 + 1.2) * sin(s.y * 2.7 + 0.4) * sin(s.z * 3.4 + 2.1)).toFloat()
        var a = 0.82f - (mare.coerceAtLeast(0f) * 0.55f)
        // Korn
        a += (sin(s.x * 23.0 + s.y * 17.0) * sin(s.z * 19.0 - s.x * 7.0)).toFloat() * 0.04f
        for (c in craters) {
            val dot = (s.x * c.dir.x + s.y * c.dir.y + s.z * c.dir.z).coerceIn(-1.0, 1.0)
            val d = kotlin.math.acos(dot) / c.size
            if (d > 1.25) continue
            a += when {
                d < 0.75 -> -0.32f * c.depth * (1f - (d / 0.75).toFloat() * 0.4f)
                d < 1.0 -> 0.22f * c.depth
                else -> 0.08f * c.depth * (1f - ((d - 1.0) / 0.25).toFloat())
            }
        }
        return a.coerceIn(0.05f, 1f)
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
        val view = d.cam.subtract(c).normalize()
        // 1) Lichtpunkt und Strahlen
        d.billboard(AriusLayers.MOON_FLARE, c, 3.0 + 4.0 * form, withAlpha(0xFFFFFFFF.toInt(), 0.9f * form * (1f - grow * 0.6f)))
        d.billboard(VfxLayers.RAYS_ADD, c, r * 6.0 + 10.0 * form, withAlpha(warm, 0.45f * form * (1f - freeze * 0.7f)), t * 0.8)
        if (grow > 0f) {
            // Licht: von schräg hinten (Sichel) nach vorn (Vollmond), während er gefriert
            val side = view.crossProduct(UPV).let { if (it.lengthSquared() < 1e-6) AriusFx.X else it.normalize() }
            val phi = (1.0 - freeze) * 2.3 + 0.15
            val light = view.multiply(cos(phi)).add(side.multiply(sin(phi))).add(0.0, 0.3, 0.0).normalize()
            val spinA = t * 0.012
            // 2a) Halo und Korona hinter dem Körper
            d.billboard(AriusLayers.MOON_GLOW, c, r * 5.5, withAlpha(lerpColor(col, 0xFF6FA8E8.toInt(), 0.4f), 0.28f * grow))
            d.billboard(AriusLayers.MOON_GLOW, c, r * 2.9, withAlpha(col, 0.55f * grow))
            // 2b) Körper
            sphere(d, c, r, light, spinA, grow, freeze)
            // 2c) Randlicht (Fresnel): heller Saum auf der beleuchteten Seite
            val (ra, rb) = dev.henny.hha.client.vfx.basisOf(view)
            d.ring(AriusLayers.MOON_GLOW, c, ra, rb, r * 0.97, r * 1.12, withAlpha(0xFFFFFFFF.toInt(), 0.35f * grow * freeze), withAlpha(cold, 0f), segments = 64)
            // 2d) Eisrisse auf der Tagseite
            if (freeze > 0.15f) for (cr in cracks) {
                val axis = Vec3d(cr[0], cr[1], cr[2])
                val (u, v) = dev.henny.hha.client.vfx.basisOf(axis)
                var prev: Vec3d? = null
                val n = 10
                for (k in 0..n) {
                    val a = cr[3] + cr[4] * k / n * freeze
                    val sDir = u.multiply(cos(a)).add(v.multiply(sin(a)))
                    val w = spin(sDir, spinA)
                    val p = c.add(w.multiply(r * 1.012))
                    val lit = w.dotProduct(light)
                    val front = w.dotProduct(view)
                    val o = prev
                    if (o != null && front > 0.12 && lit > -0.2) {
                        val pulse = (0.6 + 0.4 * sin(t * 0.3 + cr[3])).toFloat()
                        d.band(AriusLayers.MOON_GLOW, o, p, view, r * 0.035, withAlpha(0xFFDFFBFF.toInt(), 0.75f * freeze * pulse), withAlpha(0xFF7FD8FF.toInt(), 0.6f * freeze * pulse))
                    }
                    prev = p
                }
            }
            // 2e) Feiner Eishalo (dünner Ring wie ein 22°-Halo) und kreisender Frostnebel
            d.ring(AriusLayers.MOON_GLOW, c, ra, rb, r * 3.3, r * 3.45, withAlpha(0xFFE8F6FF.toInt(), 0.18f * grow * freeze), withAlpha(0xFFBFD8FF.toInt(), 0.05f * grow * freeze), segments = 72)
            for (i in 0 until 7) {
                val a = i / 7.0 * PI * 2 + t * 0.02
                val p = c.add(ra.multiply(cos(a) * r * 1.6)).add(rb.multiply(sin(a) * r * 1.6))
                d.billboard(AriusLayers.VEIL, p, r * 1.4, withAlpha(0xFFDCE8F5.toInt(), 0.22f * grow * freeze), a * 2.0)
            }
            // 2f) schräger Ring aus Eissplittern
            val ax = Vec3d(1.0, 0.35, 0.0).normalize()
            val az = Vec3d(0.0, -0.2, 1.0).normalize()
            d.ring(AriusLayers.MOON_GLOW, c, ax, az, r * 1.55, r * 1.62, withAlpha(0xFFFFFFFF.toInt(), 0.3f * grow), withAlpha(cold, 0.3f * grow), segments = 72)
            d.ring(AriusLayers.MOON_GLOW, c, ax, az, r * 1.7, r * 1.95, withAlpha(cold, 0.18f * grow), withAlpha(cold, 0f), segments = 72)
            for (i in 0 until 14) {
                val a = i / 14.0 * PI * 2 + t * 0.05
                val rr = r * (1.7 + 0.15 * sin(i * 2.3))
                val p = c.add(ax.multiply(cos(a) * rr)).add(az.multiply(sin(a) * rr))
                val s = 0.15 + 0.12 * ((i * 0.37) % 1.0)
                d.box(AriusLayers.MOON, p, AriusFx.X.multiply(s), UPV.multiply(s * 1.8), AriusFx.Z.multiply(s), withAlpha(0xFFDDF2FF.toInt(), 0.9f * grow))
            }
        }
        // Kristallzacken beim Gefrieren
        if (freeze > 0f) for (i in 0 until 16) {
            val a = i * 2.39996
            val e = (i * 0.618 % 1.0) * 2 - 1
            val dir = Vec3d(cos(a) * sqrt(1 - e * e), e, sin(a) * sqrt(1 - e * e))
            if (dir.dotProduct(view) < -0.2) continue
            d.band(AriusLayers.MOON_GLOW, c.add(dir.multiply(r * 0.95)), c.add(dir.multiply(r * (1.0 + 0.6 * freeze))), view, r * 0.08, withAlpha(0xFFFFFFFF.toInt(), 0.7f * freeze), withAlpha(cold, 0.1f * freeze))
        }
        // 3) Sturz: Schweif, Rauch, Schatten am Boden
        if (f > 0f) {
            d.band(VfxLayers.FLOW_ADD, c, c.add(0.0, 26.0 * f, 0.0), d.camRight, r * 1.1, withAlpha(cold, 0.7f), withAlpha(cold, 0f), uScale = 0.25f, uOffset = -t * 0.25f)
            d.band(AriusLayers.MOON_GLOW, c, c.add(0.0, 16.0 * f, 0.0), d.camRight, r * 0.55, withAlpha(0xFFFFFFFF.toInt(), 0.8f), withAlpha(0xFFFFFFFF.toInt(), 0f))
            for (i in 1..5) d.billboard(AriusLayers.VEIL, c.add(0.0, i * 4.0 * f, 0.0), r * (1.2 + i * 0.3), withAlpha(0xFFE6EEF6.toInt(), 0.3f * (1f - i / 6f)))
        }
        val shadow = (t / impactAt).coerceIn(0f, 1f)
        d.disc(VfxLayers.SMOKE_ALPHA, ground.add(0.0, 0.07, 0.0), AriusFx.X, AriusFx.Z, radius * (0.3 + 0.7 * shadow), withAlpha(0xFF1B2A44.toInt(), 0.35f * shadow))
        d.ring(VfxLayers.GLOW_ADD, ground.add(0.0, 0.08, 0.0), AriusFx.X, AriusFx.Z, radius - 0.35, radius, withAlpha(cold, 0f), withAlpha(cold, 0.6f * shadow), segments = 64)
    }

    /** Fein unterteilte, beleuchtete Mondkugel (Farbe je Ecke aus Albedo, Licht und Erdlicht). */
    private fun sphere(d: VfxDraw, c: Vec3d, r: Double, light: Vec3d, spinA: Double, grow: Float, freeze: Float) {
        val lat = 22
        val lon = 40
        val cols = IntArray((lat + 1) * (lon + 1))
        val pts = arrayOfNulls<Vec3d>((lat + 1) * (lon + 1))
        val night = 0xFF14233F.toInt()
        val shade = 0xFF4E6E96.toInt()
        val iceLo = lerpColor(0xFFC9B48A.toInt(), 0xFF8FB6DE.toInt(), freeze)
        val iceHi = lerpColor(0xFFFFF4DC.toInt(), 0xFFF4FBFF.toInt(), freeze)
        for (i in 0..lat) for (j in 0..lon) {
            val th = PI * i / lat
            val ph = 2 * PI * j / lon
            val s = Vec3d(sin(th) * cos(ph), cos(th), sin(th) * sin(ph))
            val w = spin(s, spinA)
            pts[i * (lon + 1) + j] = c.add(w.multiply(r))
            val alb = albedo(s)
            val ndl = w.dotProduct(light)
            val lit = (((ndl + 0.12) / 0.5).coerceIn(0.0, 1.0)).let { it * it * (3 - 2 * it) }.toFloat()
            val surf = lerpColor(iceLo, iceHi, alb)
            val dark = lerpColor(night, shade, alb * 0.6f)
            cols[i * (lon + 1) + j] = withAlpha(lerpColor(dark, surf, lit), 0.97f * grow)
        }
        val vc = d.buffer(AriusLayers.MOON)
        for (i in 0 until lat) for (j in 0 until lon) {
            val a = i * (lon + 1) + j
            val b = (i + 1) * (lon + 1) + j
            val u0 = j / lon.toFloat()
            val u1 = (j + 1) / lon.toFloat()
            val v0 = i / lat.toFloat()
            val v1 = (i + 1) / lat.toFloat()
            with(d) {
                vc.put(pts[a]!!, u0, v0, cols[a])
                vc.put(pts[b]!!, u0, v1, cols[b])
                vc.put(pts[b + 1]!!, u1, v1, cols[b + 1])
                vc.put(pts[a + 1]!!, u1, v0, cols[a + 1])
            }
        }
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
