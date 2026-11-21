package dev.henny.hha_arius.client.fx

import dev.henny.hha.client.vfx.VfxDraw
import dev.henny.hha.client.vfx.lerpColor
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
 * Seit 1.8.1 (Crix: „den Schneesturm sieht man immer noch nicht richtig“) **alpha-gemischt** statt additiv – weißes
 * Licht auf weißem Himmel und Schnee war unsichtbar:
 * * **Flocken in drei Tiefen** um die Kamera (umlaufende Kiste, gleichmäßige Dichte): nah große, weiche Flocken, in der
 *   Mitte Streifen mit Bewegungsunschärfe, fern feine Schlieren. Farbe: kaltes Blaugrau bis Eisweiß, so heben sie sich
 *   vor hellem Sturmhimmel ab (wie echte Flocken) und leuchten vor dunklem Fels. Eigene Ebene ohne Vanilla-Nebel
 *   ([AriusLayers.SNOW]): Nebel-Mods (NoFog, ClearViews, Sodium Extra …) blenden den Schnee nicht aus; die
 *   Entfernungsdämpfung folgt der Sichtweite der Stimmung ([Atmos.visibility]).
 * * **Böen:** Der Wind schwillt in Wellen an; Sturmböen bringen Whiteout ([whiteout] für Atmos), dichteres Treiben und
 *   ziehende **Schneeschleier** (weiche Bänke mit runden Rändern, nah ausgeblendet).
 * * **Bodentreiben:** stehende, weiche Schneefahnen dicht über dem Boden, die mit dem Wind laufen.
 * * **Frostrand** und **Whiteout-Schleier** im Bild – auch ohne Shaderpack.
 * Stärke = Schnee der aktuellen Stimmung × `/ariusc fx`. Unter Dächern und im Tunnel fällt fast nichts.
 */
object SnowStorm {

    private const val NEAR = 160
    private const val MID = 760
    private const val FAR = 700
    private const val NEAR_R = 6.0
    private const val MID_R = 16.0
    private const val FAR_R = 34.0

    private class Flake(var x: Double, var y: Double, var z: Double, val size: Float, val drift: Float, val phase: Float, val tone: Float) {
        var px = x
        var py = y
        var pz = z
    }

    private val rnd = Random(4711)
    private val near = Array(NEAR) { spawn(NEAR_R, 0.09f, 0.2f) }
    private val mid = Array(MID) { spawn(MID_R, 0.05f, 0.1f) }
    private val far = Array(FAR) { spawn(FAR_R, 0.07f, 0.13f) }

    private fun spawn(r: Double, s0: Float, s1: Float) = Flake(
        (rnd.nextDouble() * 2 - 1) * r, (rnd.nextDouble() * 2 - 1) * r * 0.6, (rnd.nextDouble() * 2 - 1) * r,
        s0 + (s1 - s0) * rnd.nextFloat(), 0.6f + 0.8f * rnd.nextFloat(), rnd.nextFloat() * 6.283f, rnd.nextFloat(),
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

    private class Drift(var pos: Vec3d, val len: Double, val height: Double, val life: Int) {
        var age = 0
    }

    private val drifts = ArrayList<Drift>()

    /** Schneeschleier: weiche Bänke, die mit dem Wind an der Kamera vorbeiziehen (vor allem in Böen). */
    private class Sheet(var pos: Vec3d, val size: Double, val life: Int, val seed: Float) {
        var age = 0
    }

    private val sheets = ArrayList<Sheet>()

    fun reset() {
        drifts.clear()
        sheets.clear()
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
        // Sturmböen: alle 8–20 s eine, 3–5 s lang; dazwischen Wellen.
        if (--gustTimer <= 0) {
            gustTimer = 160 + rnd.nextInt(240)
            gust = 1f
        }
        gust = (gust - 1f / 80f).coerceAtLeast(0f)
        val silence = SilenceLens.activeStrength()
        val hush = 1f - silence * 0.90f
        val wave = 0.75 + 0.25 * sin(age * 0.045) + 0.15 * sin(age * 0.17 + 1.3)
        val g = kotlin.math.sin(gust * PI.toFloat()).coerceAtLeast(0f) * hush
        whiteout = (g * (snow / 1.1f).coerceIn(0f, 1f)).coerceIn(0f, 1f)
        val speed = windBase * (wave + 1.4 * g) * (0.12f + 0.88f * hush)
        wind = Vec3d(cos(windAngle) * speed, 0.0, sin(windAngle) * speed)
        strength = snow * (1f + g) * fx * hush
        // Dach über der Kamera: unter Dächern kaum Schnee (weich überblendet).
        val sky = world.isSkyVisible(BlockPos.ofFloored(cam.x, cam.y + 1.0, cam.z))
        roof += ((if (sky) 0f else 1f) - roof) * 0.1f
        // Kamera-Versatz seit dem letzten Tick: die Flocken liegen in Weltkoordinaten relativ zur Kamera.
        val dc = lastCam?.let { cam.subtract(it) } ?: Vec3d.ZERO
        lastCam = cam
        if (dc.lengthSquared() <= 400.0) {
            step(near, NEAR_R, dc, 1.7)
            step(mid, MID_R, dc, 1.2)
            step(far, FAR_R, dc, 1.0)
        }
        // Bodentreiben: stehende Schneefahnen dicht über dem Boden
        drifts.removeIf { ++it.age >= it.life }
        if (strength > 0.1f && drifts.size < (22 * fx).toInt() && rnd.nextFloat() < 0.6f * strength) {
            val a = rnd.nextDouble() * 2 * PI
            val d = 3 + rnd.nextDouble() * 20
            val ground = groundBelow(world, cam.add(cos(a) * d, 0.0, sin(a) * d))
            if (ground != null) drifts.add(Drift(ground.add(0.0, 0.05, 0.0), 3.0 + rnd.nextDouble() * 5.0, 0.5 + rnd.nextDouble() * 0.9, 30 + rnd.nextInt(30)))
        }
        for (d in drifts) d.pos = d.pos.add(wind.multiply(1.8))
        // Schneeschleier: in Böen dicht, sonst vereinzelt
        sheets.removeIf { ++it.age >= it.life }
        val want = ((6 + 30 * g) * (snow / 1.4f).coerceIn(0f, 1.5f) * fx * (1f - roof)).toInt()
        if (sheets.size < want && rnd.nextFloat() < 0.5f) {
            val up = if (wind.lengthSquared() < 1e-6) Vec3d(1.0, 0.0, 0.0) else wind.normalize()
            val a = rnd.nextDouble() * 2 * PI
            val d = 5 + rnd.nextDouble() * 18
            // gegen den Wind versetzt starten, damit sie heranziehen
            val start = cam.add(cos(a) * d - up.x * 12.0, -1.5 + rnd.nextDouble() * 5.0, sin(a) * d - up.z * 12.0)
            sheets.add(Sheet(start, 3.5 + rnd.nextDouble() * 5.5, 40 + rnd.nextInt(40), rnd.nextFloat()))
        }
        for (s in sheets) s.pos = s.pos.add(wind.multiply(1.3))
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
        for (dy in -4..10) {
            val q = start.down(dy)
            if (!world.getBlockState(q).isAir && world.getBlockState(q.up()).isAir) return Vec3d(p.x, q.y + 1.0, p.z)
        }
        return null
    }

    // Flockenfarben: kaltes Blaugrau (Kontrast vor weißem Himmel) bis helles Eisweiß (Kontrast vor Fels).
    private const val DARK = 0xFF8A9DB8.toInt()
    private const val LIGHT = 0xFFE4ECF6.toInt()

    fun render(d: VfxDraw, snow: Float) {
        if (!worldMode()) return
        val fx = AriusClientConfig.fx.coerceIn(0f, 1f)
        val hush = 1f - SilenceLens.activeStrength() * 0.92f
        val k = (snow * fx * hush * (1f - 0.85f * roof)).coerceIn(0f, 2.2f)
        if (k <= 0.02f) return
        val cam = d.cam
        val td = d.tickDelta.toDouble()
        val clarity = if (GroundWarnings.nearWarning) 0.42f else 1f
        // Sichtweite der Stimmung (Nebelende, in Böen kürzer): fernere Flocken verblassen darin.
        val vis = Atmos.visibility().toDouble().coerceIn(10.0, 80.0)
        draw(d, near, (NEAR * (k / 1.6f).coerceAtMost(1f)).toInt(), cam, td, 0.85f * (0.55f + 0.45f * clarity), 2.0, 2.2, vis)
        draw(d, mid, (MID * (k / 1.5f).coerceAtMost(1f)).toInt(), cam, td, 0.72f * (0.65f + 0.35f * clarity), 2.4, 1.8, vis)
        draw(d, far, (FAR * ((k - 0.4f) / 1.4f).coerceIn(0f, 1f)).toInt(), cam, td, 0.5f, 3.0, 2.6, vis)
        val along = Vec3d(wind.x, 0.0, wind.z).let { if (it.lengthSquared() < 1e-6) Vec3d(1.0, 0.0, 0.0) else it.normalize() }
        // Bodentreiben: stehende Fahnen entlang des Windes (weiche Ränder, keine harten Flächen)
        val veil = d.buffer(AriusLayers.VEIL)
        for (dr in drifts) {
            val t = (dr.age + d.tickDelta) / dr.life
            val a = (sin(t * PI) * 0.42 * k * clarity).toFloat().coerceIn(0f, 0.55f)
            if (a <= 0.01f) continue
            val c = withAlpha(0xFFD9E3EE.toInt(), a)
            val h = along.multiply(dr.len * 0.5)
            val p = dr.pos
            with(d) { veil.put(p.x - h.x, p.y, p.z - h.z, 0f, 1f, c) }
            with(d) { veil.put(p.x + h.x, p.y, p.z + h.z, 1f, 1f, c) }
            with(d) { veil.put(p.x + h.x, p.y + dr.height, p.z + h.z, 1f, 0f, c) }
            with(d) { veil.put(p.x - h.x, p.y + dr.height, p.z - h.z, 0f, 0f, c) }
        }
        // Schneeschleier (weiche Rauchbänke, kamerazugewandt; nah ausgeblendet, damit nie eine Fläche das Bild füllt)
        for (s in sheets) {
            val t = (s.age + d.tickDelta) / s.life
            val dist = s.pos.distanceTo(cam)
            val nearFade = ((dist - 2.5) / 4.0).coerceIn(0.0, 1.0)
            val farFade = (1.0 - dist / vis).coerceIn(0.0, 1.0)
            val a = (sin(t * PI) * 0.34 * k * clarity * nearFade * (0.4 + 0.6 * farFade)).toFloat().coerceIn(0f, 0.45f)
            if (a <= 0.01f) continue
            d.billboard(AriusLayers.VEIL, s.pos, s.size, withAlpha(0xFFCFDAE7.toInt(), a), s.seed * 6.0 + t * 0.8)
        }
    }

    /** Flocken als weiche Streifen entlang der Bewegung (Länge ~ Tempo, wie Bewegungsunschärfe). */
    private fun draw(d: VfxDraw, field: Array<Flake>, count: Int, cam: Vec3d, td: Double, alpha: Float, blur: Double, maxLen: Double, vis: Double) {
        if (count <= 0) return
        val vc = d.buffer(AriusLayers.SNOW)
        val fwd = d.camForward
        for (i in 0 until count.coerceAtMost(field.size)) {
            val f = field[i]
            val x = f.px + (f.x - f.px) * td
            val y = f.py + (f.y - f.py) * td
            val z = f.pz + (f.z - f.pz) * td
            val dist2 = x * x + y * y + z * z
            if (dist2 < 0.36) continue
            // nicht hinter der Kamera zeichnen
            if (x * fwd.x + y * fwd.y + z * fwd.z < -0.5) continue
            val vx = f.x - f.px
            val vy = f.y - f.py
            val vz = f.z - f.pz
            val sp = sqrt(vx * vx + vy * vy + vz * vz)
            val len = (f.size * 1.6 + sp * blur).coerceAtMost(maxLen)
            var ax = vx; var ay = vy; var az = vz
            if (sp < 1e-4) { ax = 0.0; ay = -1.0; az = 0.0 } else { ax /= sp; ay /= sp; az /= sp }
            val dl = sqrt(dist2)
            val fade = (1.0 - dl / vis).coerceIn(0.0, 1.0).toFloat()
            if (fade <= 0.02f) continue
            val lx = x / dl; val ly = y / dl; val lz = z / dl
            var bx = ay * lz - az * ly; var by = az * lx - ax * lz; var bz = ax * ly - ay * lx
            val bl = sqrt(bx * bx + by * by + bz * bz).coerceAtLeast(1e-4)
            val w = f.size.toDouble()
            bx = bx / bl * w; by = by / bl * w; bz = bz / bl * w
            val hx = ax * len * 0.5; val hy = ay * len * 0.5; val hz = az * len * 0.5
            val col = withAlpha(lerpColor(DARK, LIGHT, 0.15f + 0.55f * f.tone), alpha * (0.35f + 0.65f * fade))
            val cx = cam.x + x; val cy = cam.y + y; val cz = cam.z + z
            with(d) { vc.put(cx - hx - bx, cy - hy - by, cz - hz - bz, 0f, 1f, col) }
            with(d) { vc.put(cx - hx + bx, cy - hy + by, cz - hz + bz, 1f, 1f, col) }
            with(d) { vc.put(cx + hx + bx, cy + hy + by, cz + hz + bz, 1f, 0f, col) }
            with(d) { vc.put(cx + hx - bx, cy + hy - by, cz + hz - bz, 0f, 0f, col) }
        }
    }

    // --- Bildschirm-Schnee (unabhängig von Shadern und Render-Mods) -----------------------------------------------

    private class ScreenFlake(var x: Float, var y: Float, val z: Float, val seed: Float) {
        var px = x
        var py = y
    }

    private val screen = Array(420) { ScreenFlake(rnd.nextFloat(), rnd.nextFloat(), 0.25f + 0.75f * rnd.nextFloat(), rnd.nextFloat() * 6.283f) }
    private var lastYaw = Float.NaN
    private var lastPitch = Float.NaN
    private var irisCheckedAt = -1000L
    private var irisCache = false

    /** Läuft ein Iris-Shaderpack? Dann übergeht die Shader-Pipeline eigene Welt-Ebenen (Schnee unsichtbar). */
    private fun shadersActive(): Boolean {
        if (age - irisCheckedAt < 40) return irisCache
        irisCheckedAt = age
        irisCache = try {
            val c = Class.forName("net.irisshaders.iris.api.v0.IrisApi")
            val inst = c.getMethod("getInstance").invoke(null)
            c.getMethod("isShaderPackInUse").invoke(inst) as Boolean
        } catch (_: Throwable) {
            false
        }
        return irisCache
    }

    /** Welt-Schnee zeichnen? (aus bei "screen") */
    fun worldMode(): Boolean = AriusClientConfig.snow != "screen"

    /** Bildschirm-Schnee zeichnen? ("screen"/"both" immer, "auto" bei aktivem Shaderpack) */
    fun screenMode(): Boolean = when (AriusClientConfig.snow) {
        "screen", "both" -> true
        "world" -> false
        else -> shadersActive()
    }

    /** Je Tick: Bildschirmflocken mit Wind (relativ zur Blickrichtung), Fall und Kameradrehung (Parallaxe) bewegen. */
    fun tickScreen(yaw: Float, pitch: Float) {
        val dyaw = if (lastYaw.isNaN()) 0f else MathHelper.wrapDegrees(yaw - lastYaw)
        val dpitch = if (lastPitch.isNaN()) 0f else pitch - lastPitch
        lastYaw = yaw
        lastPitch = pitch
        val yr = Math.toRadians(yaw.toDouble())
        val right = Vec3d(-cos(yr), 0.0, -sin(yr))
        val side = (wind.x * right.x + wind.z * right.z).toFloat()
        for (f in screen) {
            f.px = f.x; f.py = f.y
            val k = f.z
            f.x += (side * 0.05f + kotlin.math.sin(age * 0.07f + f.seed) * 0.0015f) * k - dyaw / 90f * k
            f.y += (0.012f + 0.01f * k) * (1f + whiteout) + dpitch / 70f * k
            var wrapped = false
            if (f.x > 1.05f) { f.x -= 1.1f; wrapped = true } else if (f.x < -0.05f) { f.x += 1.1f; wrapped = true }
            if (f.y > 1.05f) { f.y -= 1.1f; wrapped = true } else if (f.y < -0.05f) { f.y += 1.1f; wrapped = true }
            if (wrapped) { f.px = f.x; f.py = f.y }
        }
    }

    /**
     * Bildschirmflocken als kurze Streifen (Bewegungsunschärfe). [share] = Anteil des Feldes: im Bildschirm-Modus alles,
     * im Welt-Modus nur in Böen ein paar Flocken direkt „ins Gesicht“.
     */
    fun renderScreen(ctx: DrawContext, snow: Float, td: Float, share: Float = 1f) {
        val fx = AriusClientConfig.fx.coerceIn(0f, 1f)
        val k = (snow * fx * (1f - 0.85f * roof)).coerceIn(0f, 2f)
        if (k <= 0.02f || share <= 0.01f) return
        val w = ctx.scaledWindowWidth.toFloat()
        val h = ctx.scaledWindowHeight.toFloat()
        val count = (screen.size * (k / 1.6f).coerceAtMost(1f) * share.coerceIn(0f, 1f)).toInt()
        for (i in 0 until count) {
            val f = screen[i]
            val x = (f.px + (f.x - f.px) * td) * w
            val y = (f.py + (f.y - f.py) * td) * h
            val vx = (f.x - f.px) * w * 2.2f
            val vy = (f.y - f.py) * h * 2.2f
            val len = kotlin.math.sqrt(vx * vx + vy * vy).coerceAtLeast(1f)
            val size = if (f.z > 0.8f) 2 else 1
            val a = (0.25f + 0.55f * f.z) * k.coerceAtMost(1f)
            val col = withAlpha(if (f.seed > 3.1f) 0xFF9FB1C8.toInt() else 0xFFF4F8FF.toInt(), a)
            val n = (len / 1.5f).toInt().coerceIn(1, 8)
            for (s in 0 until n) {
                val t = s / n.toFloat()
                val sx = (x - vx * t).toInt()
                val sy = (y - vy * t).toInt()
                ctx.fill(sx, sy, sx + size, sy + size, col)
            }
        }
    }

    /** Anteil der Bildschirmflocken im Welt-Modus (nur in Böen). */
    fun gustScreenShare(): Float = (whiteout - 0.15f).coerceAtLeast(0f) * 0.5f

    /**
     * Whiteout im Bild (immer, nicht nur mit Shaderpack): ein heller Schleier, der in Böen zuzieht, dazu ein leiser
     * Grundschleier bei sehr starkem Sturm.
     */
    fun renderWhiteout(ctx: DrawContext, snow: Float) {
        val fx = AriusClientConfig.fx.coerceIn(0f, 1f)
        val hush = 1f - SilenceLens.activeStrength() * 0.95f
        val maxWash = if (GroundWarnings.nearWarning) 0.09f else 0.4f
        val a = ((whiteout * 0.42f + (snow - 1.5f).coerceAtLeast(0f) * 0.08f) * fx * hush * (1f - 0.8f * roof)).coerceIn(0f, maxWash)
        if (a <= 0.01f) return
        ctx.fill(0, 0, ctx.scaledWindowWidth, ctx.scaledWindowHeight, withAlpha(0xFFE9F1FA.toInt(), a))
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
            val c = withAlpha(0xFFDCE7F4.toInt(), a)
            val o = depth * i / steps
            val o2 = depth * (i + 1) / steps
            ctx.fill(0, o, w, o2, c)
            ctx.fill(0, h - o2, w, h - o, c)
            ctx.fill(o, o2, o2, h - o2, c)
            ctx.fill(w - o2, o2, w - o, h - o2, c)
        }
    }
}
