package dev.henny.hha_arius.client.fx

import dev.henny.hha.client.vfx.VfxDraw
import dev.henny.hha.client.vfx.VfxLayers
import dev.henny.hha.client.vfx.withAlpha
import net.minecraft.client.MinecraftClient
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.client.world.ClientWorld
import net.minecraft.entity.Entity
import net.minecraft.particle.ParticleTypes
import net.minecraft.util.math.Vec3d
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * Solvane, die Klinge, die ein Drache war (Paket V5): Effekte der Verwandlungen.
 *
 * - [BladeStream]: Die Klinge löst sich in einen Strom aus weißem Licht und Eiskristallen auf und zieht zur Nadel
 *   (Auftritt) bzw. kehrt als Strom in die Scheide zurück (Finale).
 * - [BladeShatter] / [BladeForm]: Die Eisklinge zerfällt bzw. verdichtet sich aus Kristallen.
 * - [DragonDissolve]: Der Drache löst sich in Licht auf, bevor er als Strom zurückkehrt.
 *
 * Wo die echte Klinge und die Scheide gerade sind, schreibt der Arius-Renderer in [BladeAnchors]; ohne Daten (Entity
 * nicht geladen, nicht gezeichnet) nehmen die Effekte die Ersatzpunkte des Servers.
 */

/** Lage von Klinge und Scheide je Arius (Weltkoordinaten), vom Renderer jedes Bild geschrieben. */
object BladeAnchors {
    class Anchor(val base: Vec3d, val tip: Vec3d, val sheath: Vec3d, val time: Long)

    private val map = HashMap<Int, Anchor>()

    fun record(id: Int, base: Vec3d, tip: Vec3d, sheath: Vec3d) {
        val world = MinecraftClient.getInstance().world ?: return
        if (map.size > 32) map.clear()
        map[id] = Anchor(base, tip, sheath, world.time)
    }

    /** Frische Lage (höchstens eine halbe Sekunde alt) oder null. */
    fun of(e: Entity?): Anchor? {
        if (e == null) return null
        val world = MinecraftClient.getInstance().world ?: return null
        val a = map[e.id] ?: return null
        return if (world.time - a.time in 0..10) a else null
    }
}

/** Normalverteilt (Box-Muller); kotlin.random.Random hat keine eigene. */
private fun kotlin.random.Random.nextGaussian(): Double =
    kotlin.math.sqrt(-2.0 * kotlin.math.ln(nextDouble().coerceAtLeast(1e-9))) * cos(2.0 * PI * nextDouble())

private fun smoothstep(x: Float): Float = x.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }

/** Partikel nur in der Nähe der Kamera (weit weg sieht man sie nicht, sie kosten nur). */
private fun nearCamera(p: Vec3d, range: Double = 56.0): Boolean {
    val cam = MinecraftClient.getInstance().gameRenderer.camera.cameraPos
    return cam.squaredDistanceTo(p) < range * range
}

/**
 * Der Lichtstrom: Kopf eilt voraus (heller Fleck, Kristalle wirbeln um die Bahn), der Schweif folgt und zieht sich am
 * Ziel zusammen. Breite wächst mit dem Abstand zur Kamera, damit der Strom auch über die Schlucht hinweg lesbar bleibt.
 *
 * @param returning false = verlässt die Klinge von [owner] (Start an der echten Klinge), true = kehrt in die Scheide von
 *   [owner] zurück (Ziel folgt der Scheide).
 */
class BladeStream(
    from: Vec3d, private val fallbackTo: Vec3d, travel: Int, private val returning: Boolean, private val owner: Entity?, life: Int,
) : AriusFx(life) {

    private val travel = travel.coerceAtLeast(4).toFloat()
    private val start: Vec3d = if (returning) from else BladeAnchors.of(owner)?.let { it.base.lerp(it.tip, 0.65) } ?: from
    private var arrived = false

    private class Mote(val phase: Float, val angle: Float, val radius: Float, val size: Float, val spin: Float)

    private val motes = List(22) {
        val r = random()
        Mote(it / 22f, r.nextFloat() * 6.28f, 0.35f + r.nextFloat() * 0.6f, 0.6f + r.nextFloat() * 0.8f, 0.15f + r.nextFloat() * 0.25f)
    }

    private fun end(): Vec3d = if (returning) BladeAnchors.of(owner)?.sheath ?: fallbackTo else fallbackTo

    /** Punkt auf der Bahn bei s ∈ [0, 1] (kubische Bézierkurve mit Bogen nach oben). */
    private fun at(s: Float, e: Vec3d): Vec3d {
        val d = e.subtract(start)
        val len = d.length()
        val c1: Vec3d
        val c2: Vec3d
        if (returning) {
            // Aufsteigen über dem Drachen, oben herum, von oben in die Scheide.
            c1 = start.add(d.multiply(0.25)).add(0.0, min(len * 0.45, 14.0), 0.0)
            c2 = e.add(d.multiply(-0.08)).add(0.0, min(len * 0.35, 6.0), 0.0)
        } else {
            // Von der erhobenen Klinge steil in den Himmel, dann in weitem Bogen zum Ziel.
            c1 = start.add(0.0, min(len * 0.3, 40.0), 0.0).add(d.multiply(0.12))
            c2 = e.add(d.multiply(-0.3)).add(0.0, min(len * 0.12, 24.0), 0.0)
        }
        val u = s.toDouble()
        val v = 1 - u
        return start.multiply(v * v * v).add(c1.multiply(3 * v * v * u)).add(c2.multiply(3 * v * u * u)).add(e.multiply(u * u * u))
    }

    private fun head(t: Float): Float = smoothstep(t / travel)
    private fun tail(t: Float): Float = smoothstep((t - travel * 0.4f) / travel)

    override fun tick(world: ClientWorld) {
        val e = end()
        val r = random()
        val h = at(head(age.toFloat()), e)
        if (age < travel && nearCamera(h)) {
            repeat((3 * particlesAllowed()).toInt().coerceAtLeast(1)) {
                world.addParticleClient(if (r.nextInt(3) == 0) ParticleTypes.END_ROD else ParticleTypes.SNOWFLAKE,
                    h.x + r.nextGaussian() * 0.3, h.y + r.nextGaussian() * 0.3, h.z + r.nextGaussian() * 0.3,
                    r.nextGaussian() * 0.02, r.nextGaussian() * 0.02, r.nextGaussian() * 0.02)
            }
        }
        if (!arrived && age >= travel) {
            arrived = true
            if (nearCamera(e, 96.0)) {
                repeat(((if (returning) 14 else 30) * max(particlesAllowed(), 0.3f)).toInt()) {
                    val v = Vec3d(r.nextGaussian(), r.nextGaussian(), r.nextGaussian()).normalize().multiply(if (returning) 0.08 else 0.5)
                    world.addParticleClient(if (it % 3 == 0) ParticleTypes.END_ROD else ParticleTypes.SNOWFLAKE, e.x, e.y, e.z, v.x, v.y, v.z)
                }
            }
        }
    }

    override fun render(d: VfxDraw, matrices: MatrixStack, t: Float) {
        val e = end()
        val s1 = head(t)
        val s0 = tail(t)
        val fade = min(1f, (life - t) / 8f).coerceIn(0f, 1f) * min(1f, t / 2f)
        if (fade <= 0f) return
        if (s1 - s0 > 0.002f) {
            val n = 40
            val pts = List(n + 1) { at(s0 + (s1 - s0) * it / n, e) }
            // Breite wächst mit dem Abstand, seit 1.8.1 aber höchstens auf das 2,5-Fache: weit entfernt (Flug zum Horst)
            // wurden die flachen Bänder sonst zu großen durchscheinenden Flächen am Himmel.
            val scale = pts.map { max(1.0, it.distanceTo(d.cam) / 22.0).coerceAtMost(2.5) }
            fun idx(x: Float) = (x * n).toInt().coerceIn(0, n)
            // Schweif dünn und blass, zum Kopf hin dick und weiß.
            d.tube(VfxLayers.GLOW_ADD, pts, { 0.03 + 0.08 * it * scale[idx(it)] }, { withAlpha(WHITE, fade * (0.25f + 0.75f * it)) }, sides = 6)
            d.ribbon(VfxLayers.FLOW_ADD, pts, { (0.12 + 0.3 * it) * scale[idx(it)] }, { withAlpha(ICE, fade * 0.55f * it) }, uScale = 0.3f, uOffset = -t * 0.12f)
            d.ribbon(VfxLayers.GLOW_ADD, pts, { (0.3 + 0.5 * it) * scale[idx(it)] }, { withAlpha(ICE_DEEP, fade * 0.25f * it) })
            // Eiskristalle wirbeln um die Bahn, dichter am Kopf.
            for (m in motes) {
                val u = ((m.phase + t * 0.035f) % 1f).let { 1f - (1f - it) * (1f - it) }
                val s = s0 + (s1 - s0) * u
                val p = at(s, e)
                val tan = at(min(1f, s + 0.01f), e).subtract(at(max(0f, s - 0.01f), e)).let { if (it.lengthSquared() < 1e-8) UP else it.normalize() }
                var side = tan.crossProduct(UP)
                if (side.lengthSquared() < 1e-6) side = X
                side = side.normalize()
                val up = side.crossProduct(tan).normalize()
                val sc = max(1.0, p.distanceTo(d.cam) / 22.0).coerceAtMost(2.5)
                val ang = m.angle + t * m.spin
                val rad = m.radius * sc * (0.4 + 0.6 * u)
                val c = p.add(side.multiply(cos(ang) * rad)).add(up.multiply(sin(ang) * rad))
                val sz = 0.07 * m.size * sc
                val spin = t * 0.4 + m.angle
                val ax = tan.multiply(sz * 2.6)
                val ay = side.multiply(cos(spin) * sz).add(up.multiply(sin(spin) * sz))
                val az = ax.crossProduct(ay).normalize().multiply(sz * 0.6)
                val a = fade * (0.35f + 0.65f * u)
                d.box(VfxLayers.SHARD_ALPHA, c, ax, ay, az, withAlpha(0xFFD8F2FF.toInt(), a * 0.85f))
                d.billboard(VfxLayers.GLOW_ADD, c, sz * 4.0, withAlpha(ICE, a * 0.6f))
            }
            // Kopf: heller Fleck; in der Ferne zusätzlich ohne Nebel, damit er im Sturm nicht verschwindet.
            if (t < travel + 2f) {
                val h = pts.last()
                val sc = scale.last()
                d.billboard(VfxLayers.FLARE_ADD, h, 0.9 * sc, withAlpha(WHITE, fade))
                d.billboard(VfxLayers.GLOW_ADD, h, 1.8 * sc, withAlpha(ICE, fade * 0.7f))
                if (h.distanceTo(d.cam) > 40.0) d.billboard(VfxLayers.SKY_FLARE, h, 1.4 * sc, withAlpha(WHITE, fade * 0.8f))
            }
        }
        // Ankunft: kurzes Aufleuchten am Ziel (an der Nadel weit, an der Scheide klein).
        val since = t - travel
        if (since in 0f..16f) {
            val k = 1f - since / 16f
            val sc = max(1.0, e.distanceTo(d.cam) / 22.0)
            val size = (if (returning) 0.6 + since * 0.08 else 2.5 + since * 0.5) * sc
            d.billboard(VfxLayers.FLARE_ADD, e, size, withAlpha(WHITE, k * fade))
            d.billboard(VfxLayers.GLOW_ADD, e, size * 1.8, withAlpha(ICE, k * 0.6f * fade))
            if (!returning && e.distanceTo(d.cam) > 40.0) d.billboard(VfxLayers.SKY_GLOW, e, size * 2.2, withAlpha(ICE, k * 0.7f * fade))
        }
    }
}

/** Splitter entlang einer Klinge (gemeinsam für Zerfall und Bildung). */
private class Shard(val along: Float, val dir: Vec3d, val spin: Vec3d, val size: Double)

private fun shards(count: Int): List<Shard> {
    val r = AriusFx.random()
    return List(count) {
        Shard(r.nextFloat(), Vec3d(r.nextGaussian(), r.nextGaussian() * 0.6 + 0.3, r.nextGaussian()).normalize(),
            Vec3d(r.nextDouble(), r.nextDouble(), r.nextDouble()).multiply(0.35), 0.035 + r.nextDouble() * 0.05)
    }
}

private fun VfxDraw.shard(p: Vec3d, s: Shard, t: Float, alpha: Float) {
    val a = t * s.spin.x + s.along * 6.0
    val ax = Vec3d(cos(a), sin(a) * 0.5, sin(a)).multiply(s.size)
    val ay = Vec3d(-sin(a) * 0.4, cos(a * 0.7), cos(a) * 0.3).normalize().multiply(s.size * 2.2)
    val az = ax.crossProduct(ay).normalize().multiply(s.size * 0.4)
    box(VfxLayers.SHARD_ALPHA, p, ax, ay, az, withAlpha(0xFFDDF4FF.toInt(), alpha * 0.85f))
    billboard(VfxLayers.GLOW_ADD, p, s.size * 5.0, withAlpha(AriusFx.ICE, alpha * 0.7f))
}

/** Lage einer Klinge: echte Knochen, sonst senkrecht über dem Ersatzpunkt. */
private fun bladeOf(owner: Entity?, fallback: Vec3d): Pair<Vec3d, Vec3d> =
    BladeAnchors.of(owner)?.let { it.base to it.tip } ?: (fallback to fallback.add(0.0, 1.0, 0.0))

/** Die Eisklinge zerfällt: Splitter springen ab, ein kurzer Lichtblitz entlang der Klinge. */
class BladeShatter(private val owner: Entity?, fallback: Vec3d, life: Int) : AriusFx(life) {
    private val blade = bladeOf(owner, fallback)
    private val parts = shards(18)

    override fun tick(world: ClientWorld) {
        if (age != 1) return
        val (base, tip) = blade
        val r = random()
        repeat((10 * particlesAllowed()).toInt()) {
            val p = base.lerp(tip, r.nextDouble())
            world.addParticleClient(ParticleTypes.SNOWFLAKE, p.x, p.y, p.z, r.nextGaussian() * 0.05, 0.04, r.nextGaussian() * 0.05)
        }
    }

    override fun render(d: VfxDraw, matrices: MatrixStack, t: Float) {
        val (base, tip) = blade
        val k = (t / life).coerceIn(0f, 1f)
        if (t < 6f) {
            val f = 1f - t / 6f
            d.tube(VfxLayers.GLOW_ADD, listOf(base, tip), { 0.05 + 0.1 * (1 - f) }, { withAlpha(WHITE, f) })
            d.billboard(VfxLayers.FLARE_ADD, base.lerp(tip, 0.5), 0.8 + t * 0.15, withAlpha(ICE, f))
        }
        for (s in parts) {
            val p = base.lerp(tip, s.along.toDouble()).add(s.dir.multiply(0.05 + 0.9 * (1 - (1 - k) * (1 - k)))).add(0.0, -0.3 * k * k, 0.0)
            d.shard(p, s, t, (1f - k) * min(1f, t / 1.5f + 0.4f))
        }
    }
}

/** Die Eisklinge entsteht: Kristalle ziehen sich zur Klinge zusammen, zuletzt leuchtet sie einmal auf. */
class BladeForm(private val owner: Entity?, private val fallback: Vec3d, life: Int) : AriusFx(life) {
    private val parts = shards(22)
    private val gather = (life - 6).coerceAtLeast(4).toFloat()

    override fun tick(world: ClientWorld) {
        val (base, tip) = bladeOf(owner, fallback)
        val r = random()
        if (age < gather && r.nextFloat() < 0.7f * particlesAllowed()) {
            val p = base.lerp(tip, r.nextDouble())
            val from = p.add(r.nextGaussian() * 1.4, r.nextGaussian() * 1.0, r.nextGaussian() * 1.4)
            val v = p.subtract(from).multiply(0.1)
            world.addParticleClient(ParticleTypes.SNOWFLAKE, from.x, from.y, from.z, v.x, v.y, v.z)
        }
    }

    override fun render(d: VfxDraw, matrices: MatrixStack, t: Float) {
        val (base, tip) = bladeOf(owner, fallback)
        val k = smoothstep(t / gather)
        for (s in parts) {
            val target = base.lerp(tip, s.along.toDouble())
            val p = target.add(s.dir.multiply(1.6 * (1 - k)))
            d.shard(p, s, t, min(1f, t / 3f) * (1f - 0.7f * smoothstep((t - gather) / 4f)))
        }
        val since = t - gather
        if (since > -2f) {
            val f = (1f - since / (life - gather + 2f)).coerceIn(0f, 1f) * min(1f, (since + 2f) / 2f)
            d.tube(VfxLayers.GLOW_ADD, listOf(base, tip), { 0.04 + 0.05 * f }, { withAlpha(WHITE, f) })
            d.billboard(VfxLayers.FLARE_ADD, base.lerp(tip, 0.55), 0.6 + 0.8 * f, withAlpha(ICE, f))
        }
    }
}

/**
 * Der Drache löst sich in Licht auf: entlang des Körpers (Kopf → Rumpf → weiter nach hinten) blühen Lichtflecken auf,
 * Lichtsäulen steigen, Funken und Schnee steigen auf. Höhepunkt nach [PEAK] Ticks; der Server entfernt den Drachen dann.
 */
class DragonDissolve(private val head: Vec3d, body: Vec3d, private val size: Double, life: Int) : AriusFx(life) {
    private val points: List<Vec3d>

    init {
        val back = body.subtract(head).let { if (it.lengthSquared() < 1e-4) Vec3d(0.0, 0.0, 1.0) else it.normalize() }
        val len = max(head.distanceTo(body), 4.0)
        points = List(12) { head.add(back.multiply(len * it / 4.0)) }
    }

    private fun bloom(t: Float, i: Int): Float {
        val delay = i * 0.6f
        val up = smoothstep((t - delay) / (PEAK - delay * 0.5f).coerceAtLeast(2f))
        val down = 1f - smoothstep((t - PEAK - 4f) / (life - PEAK - 4f).coerceAtLeast(2f))
        return up * down * (1f - i * 0.05f)
    }

    override fun tick(world: ClientWorld) {
        val r = random()
        if (age > PEAK + 12) return
        for ((i, p) in points.withIndex()) {
            if (!nearCamera(p, 80.0) || r.nextFloat() > 0.8f * particlesAllowed()) continue
            val q = p.add(r.nextGaussian() * size * 0.4, r.nextDouble() * size * 0.5, r.nextGaussian() * size * 0.4)
            world.addParticleClient(if ((i + age) % 3 == 0) ParticleTypes.END_ROD else ParticleTypes.SNOWFLAKE, q.x, q.y, q.z, 0.0, 0.06 + r.nextDouble() * 0.08, 0.0)
        }
    }

    override fun render(d: VfxDraw, matrices: MatrixStack, t: Float) {
        for ((i, p) in points.withIndex()) {
            val b = bloom(t, i)
            if (b <= 0.01f) continue
            val c = p.add(0.0, size * 0.25, 0.0)
            d.billboard(VfxLayers.GLOW_ADD, c, size * (0.6 + 0.8 * b), withAlpha(ICE, b * 0.85f))
            d.billboard(VfxLayers.FLARE_ADD, c, size * 0.6 * b, withAlpha(WHITE, b))
            d.band(VfxLayers.FLOW_ADD, c, c.add(0.0, size * (1.0 + 2.0 * b), 0.0), d.camRight, size * 0.25 * b, withAlpha(WHITE, b * 0.7f), withAlpha(ICE, 0f), uScale = 0.2f, uOffset = -t * 0.08f)
        }
        val h = bloom(t, 0)
        if (h > 0.01f) d.billboard(VfxLayers.RAYS_ADD, head.add(0.0, size * 0.3, 0.0), size * 1.6 * h, withAlpha(WHITE, h * 0.6f), t * 0.03)
    }

    companion object {
        /** Ticks bis zur größten Helligkeit (dann ist der Drache fort). */
        const val PEAK = 14
    }
}
