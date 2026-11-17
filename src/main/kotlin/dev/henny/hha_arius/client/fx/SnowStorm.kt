package dev.henny.hha_arius.client.fx

import dev.henny.hha.client.vfx.VfxDraw
import dev.henny.hha.client.vfx.VfxLayers
import dev.henny.hha.client.vfx.withAlpha
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.world.ClientWorld
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.MathHelper
import net.minecraft.util.math.Vec3d
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Filmischer Schneesturm im Heiligtum (Crix 17.11.: „die ganze Zeit ein high-quality Schneesturm, ultra realistisch“).
 * Ersetzt das alte Vanilla-Flockentreiben durch ein eigenes Feld:
 * * **Flocken in zwei Tiefen** um die Kamera (umlaufende Kiste: kein Spawnen, gleichmäßige Dichte), jede Flocke als
 *   weicher Streifen entlang ihrer Bewegung (Bewegungsunschärfe wie bei einer Filmkamera), Turbulenz je Flocke,
 *   nah groß und schnell, fern fein. Unter einem Dach fällt fast nichts.
 * * **Böen:** Der Wind schwillt in Wellen an; gelegentlich kommt eine Sturmböe mit kurzem Whiteout (Nebel zieht zu,
 *   [whiteout] für Atmos) und dichterem Treiben.
 * * **Bodentreiben:** flache, schnelle Schneeschleier knapp über dem Boden.
 * * **Frostrand:** ein kalter, weicher Bildrand, stärker in Böen.
 * Stärke = Schnee der aktuellen Stimmung × `/ariusc fx`.
 */
object SnowStorm {

    private const val NEAR = 260
    private const val MID = 900
    private const val NEAR_R = 9.0
    private const val MID_R = 26.0

    private class Flake(var x: Double, var y: Double, var z: Double, val size: Float, val drift: Float, val phase: Float) {
        var px = x
        var py = y
        var pz = z
    }

    private val rnd = Random(4711)
    private val near = Array(NEAR) { spawn(NEAR_R, 0.035f, 0.07f) }
    private val mid = Array(MID) { spawn(MID_R, 0.04f, 0.085f) }

    private fun spawn(r: Double, s0: Float, s1: Float) = Flake(
        (rnd.nextDouble() * 2 - 1) * r, (rnd.nextDouble() * 2 - 1) * r * 0.6, (rnd.nextDouble() * 2 - 1) * r,
        s0 + (s1 - s0) * rnd.nextFloat(), 0.6f + 0.8f * rnd.nextFloat(), rnd.nextFloat() * 6.283f,
    )

    private var age = 0L
    private var wind = Vec3d.ZERO
    private var lastCam: Vec3d? = null
    private var roof = 0f
    private var gust = 0f
    private var gustTimer = 200

    /** 0..1: Whiteout einer Sturmböe (Atmos zieht den Nebel zu). */
    var whiteout = 0f
        private set

    /** Aktuelle Sturmstärke 0..~2 (für Frostrand und Atmos). */
    var strength = 0f
        private set

    private class Drift(var pos: Vec3d, val size: Double, val life: Int, val seed: Float) {
        var age = 0
    }

    private val drifts = ArrayList<Drift>()

    fun reset() {
        drifts.clear()
        lastCam = null
        whiteout = 0f
        strength = 0f
    }

    /** Je Tick: Wind, Böen, Flocken bewegen (relativ zur Kamera, umlaufend). */
    fun tick(world: ClientWorld, snow: Float, windBase: Float, windAngle: Double) {
        age++
        val client = MinecraftClient.getInstance()
        val cam = client.gameRenderer.camera.cameraPos
        val fx = AriusClientConfig.fx.coerceIn(0f, 1f)
        // Sturmböen: alle 10–25 s eine, 3–5 s lang; dazwischen Wellen.
        if (--gustTimer <= 0) {
            gustTimer = 200 + rnd.nextInt(300)
            gust = 1f
        }
        gust = (gust - 1f / 80f).coerceAtLeast(0f)
        val wave = 0.75 + 0.25 * sin(age * 0.045) + 0.15 * sin(age * 0.17 + 1.3)
        val g = kotlin.math.sin(gust * PI.toFloat()).coerceAtLeast(0f)
        whiteout = (g * (snow / 1.2f).coerceIn(0f, 1f)).coerceIn(0f, 1f)
        val speed = windBase * (wave + 1.4 * g)
        wind = Vec3d(cos(windAngle) * speed, 0.0, sin(windAngle) * speed)
        strength = snow * (1f + g) * fx
        // Dach über der Kamera: unter Dächern kaum Schnee (weich überblendet).
        val sky = world.isSkyVisible(BlockPos.ofFloored(cam.x, cam.y + 1.0, cam.z))
        roof += ((if (sky) 0f else 1f) - roof) * 0.1f
        // Kamera-Versatz seit dem letzten Tick: die Flocken liegen in Weltkoordinaten relativ zur Kamera.
        val dc = lastCam?.let { cam.subtract(it) } ?: Vec3d.ZERO
        lastCam = cam
        if (dc.lengthSquared() > 400.0) { /* Teleport: Feld einfach mitnehmen */ }
        else {
            step(near, NEAR_R, dc, 1.6)
            step(mid, MID_R, dc, 1.0)
        }
        // Bodentreiben
        drifts.removeIf { ++it.age >= it.life }
        if (strength > 0.1f && drifts.size < (18 * fx).toInt() && rnd.nextFloat() < 0.5f * strength) {
            val a = rnd.nextDouble() * 2 * PI
            val d = 3 + rnd.nextDouble() * 22
            val ground = groundBelow(world, cam.add(cos(a) * d, 0.0, sin(a) * d))
            if (ground != null) drifts.add(Drift(ground.add(0.0, 0.25 + rnd.nextDouble() * 0.6, 0.0), 2.5 + rnd.nextDouble() * 4, 30 + rnd.nextInt(30), rnd.nextFloat()))
        }
        for (d in drifts) d.pos = d.pos.add(wind.multiply(1.8))
    }

    private fun step(field: Array<Flake>, r: Double, dc: Vec3d, scale: Double) {
        val t = age * 0.08
        for (f in field) {
            f.px = f.x; f.py = f.y; f.pz = f.z
            // Fall, Wind, Turbulenz je Flocke; Kamerabewegung abziehen (Feld bleibt in der Welt).
            val turb = 0.05 * f.drift
            f.x += wind.x * scale * f.drift + sin(t + f.phase) * turb - dc.x
            f.y += -0.06 * f.drift - 0.02 + cos(t * 1.3 + f.phase) * turb * 0.6 - dc.y
            f.z += wind.z * scale * f.drift + cos(t * 0.9 + f.phase * 1.7) * turb - dc.z
            var wrapped = false
            if (f.x > r) { f.x -= 2 * r; wrapped = true } else if (f.x < -r) { f.x += 2 * r; wrapped = true }
            if (f.y > r * 0.6) { f.y -= 1.2 * r; wrapped = true } else if (f.y < -r * 0.6) { f.y += 1.2 * r; wrapped = true }
            if (f.z > r) { f.z -= 2 * r; wrapped = true } else if (f.z < -r) { f.z += 2 * r; wrapped = true }
            if (wrapped) { f.px = f.x; f.py = f.y; f.pz = f.z }
        }
    }

    private fun groundBelow(world: ClientWorld, p: Vec3d): Vec3d? {
        val start = BlockPos.ofFloored(p)
        for (dy in 0..10) {
            val q = start.down(dy)
            if (!world.getBlockState(q).isAir && world.getBlockState(q.up()).isAir) return Vec3d(p.x, q.y + 1.0, p.z)
        }
        return null
    }

    fun render(d: VfxDraw, snow: Float) {
        val fx = AriusClientConfig.fx.coerceIn(0f, 1f)
        val k = (snow * fx * (1f - 0.85f * roof)).coerceIn(0f, 2f)
        if (k <= 0.02f) return
        val cam = d.cam
        val td = d.tickDelta.toDouble()
        draw(d, near, (NEAR * (k / 2f).coerceAtMost(1f)).toInt().coerceAtMost(NEAR), cam, td, 0.75f, 2.4)
        draw(d, mid, (MID * (k / 1.6f).coerceAtMost(1f)).toInt().coerceAtMost(MID), cam, td, 0.55f, 1.6)
        for (dr in drifts) {
            val t = (dr.age + d.tickDelta) / dr.life
            val a = (sin(t * PI) * 0.18 * k).toFloat().coerceIn(0f, 0.3f)
            if (a <= 0.01f) continue
            val along = Vec3d(wind.x, 0.0, wind.z).let { if (it.lengthSquared() < 1e-6) Vec3d(1.0, 0.0, 0.0) else it.normalize() }
            val side = Vec3d(-along.z, 0.0, along.x)
            d.disc(VfxLayers.FLOW_ALPHA, dr.pos, along.multiply(1.8), side.multiply(0.55), dr.size, withAlpha(0xFFFFFFFF.toInt(), a), dr.seed * 6.0 + t)
        }
    }

    /** Flocken als weiche Streifen entlang der Bewegung (Länge ~ Tempo, wie Bewegungsunschärfe). */
    private fun draw(d: VfxDraw, field: Array<Flake>, count: Int, cam: Vec3d, td: Double, alpha: Float, blur: Double) {
        val vc = d.buffer(VfxLayers.GLOW_ADD)
        val fwd = d.camForward
        for (i in 0 until count) {
            val f = field[i]
            val x = f.px + (f.x - f.px) * td
            val y = f.py + (f.y - f.py) * td
            val z = f.pz + (f.z - f.pz) * td
            val dist2 = x * x + y * y + z * z
            if (dist2 < 0.25) continue
            // nicht hinter der Kamera zeichnen
            if (x * fwd.x + y * fwd.y + z * fwd.z < -0.5) continue
            val vx = f.x - f.px
            val vy = f.y - f.py
            val vz = f.z - f.pz
            val sp = sqrt(vx * vx + vy * vy + vz * vz)
            val len = f.size + sp * blur
            // Achse entlang der Bewegung, Breite quer dazu zur Kamera hin
            var ax = vx; var ay = vy; var az = vz
            if (sp < 1e-4) { ax = 0.0; ay = -1.0; az = 0.0 } else { ax /= sp; ay /= sp; az /= sp }
            // quer = Bewegung × Blickrichtung zur Flocke
            val dl = sqrt(dist2)
            val lx = x / dl; val ly = y / dl; val lz = z / dl
            var bx = ay * lz - az * ly; var by = az * lx - ax * lz; var bz = ax * ly - ay * lx
            val bl = sqrt(bx * bx + by * by + bz * bz).coerceAtLeast(1e-4)
            val w = f.size * 0.55
            bx = bx / bl * w; by = by / bl * w; bz = bz / bl * w
            val hx = ax * len * 0.5; val hy = ay * len * 0.5; val hz = az * len * 0.5
            // Nah heller, fern schwächer (Dunst übernimmt der Nebel)
            val fade = (1.0 - dl / 30.0).coerceIn(0.15, 1.0).toFloat()
            val col = withAlpha(0xFFF4F8FF.toInt(), alpha * fade)
            val cx = cam.x + x; val cy = cam.y + y; val cz = cam.z + z
            with(d) {
                vc.put(cx - hx - bx, cy - hy - by, cz - hz - bz, 0f, 1f, col)
                vc.put(cx - hx + bx, cy - hy + by, cz - hz + bz, 1f, 1f, col)
                vc.put(cx + hx + bx, cy + hy + by, cz + hz + bz, 1f, 0f, col)
                vc.put(cx + hx - bx, cy + hy - by, cz + hz - bz, 0f, 0f, col)
            }
        }
    }

    /** Frostrand: kalter, weicher Bildrand (stärker in Böen). Wird vom HUD gezeichnet. */
    fun renderVignette(ctx: DrawContext) {
        val s = (strength * 0.22f + whiteout * 0.35f).coerceIn(0f, 0.55f)
        if (s <= 0.01f) return
        val w = ctx.scaledWindowWidth
        val h = ctx.scaledWindowHeight
        val steps = 18
        val depth = (minOf(w, h) * 0.22f).toInt().coerceAtLeast(steps)
        for (i in 0 until steps) {
            val a = s * (1f - i / steps.toFloat()).let { it * it } * 0.35f
            val c = withAlpha(0xFFE8F2FF.toInt(), a)
            val o = depth * i / steps
            val o2 = depth * (i + 1) / steps
            ctx.fill(0, o, w, o2, c)
            ctx.fill(0, h - o2, w, h - o, c)
            ctx.fill(o, o2, o2, h - o2, c)
            ctx.fill(w - o2, o2, w - o, h - o2, c)
        }
    }
}
