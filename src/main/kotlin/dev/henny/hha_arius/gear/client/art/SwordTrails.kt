package dev.henny.hha_arius.gear.client.art

import dev.henny.hha.client.vfx.VfxLayers
import net.minecraft.client.MinecraftClient
import net.minecraft.client.render.command.OrderedRenderCommandQueue
import net.minecraft.util.math.Vec3d
import org.joml.Vector3f
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Schwertspuren als weiche Lichtstreifen: Je Klinge werden Ansatz und Spitze jedes Bild mitgeschrieben; gezeichnet wird
 * die überstrichene Fläche des äußeren Klingenstücks (innen weich auslaufend, hell an der Bahn der Spitze, ein schmaler
 * Saum darüber), die mit dem Alter verblasst. Sichtbar nur, wenn die Spitze schnell ist (keine Spur beim Gehen).
 *
 * * Solvane beim Spieler: nur bei vollen Treffern ([confirm], vom Treffer-Erkenner bzw. beim eigenen Angriff mit voller
 *   Schlagkraft). Dritte Person in Weltkoordinaten, erste Person im Blickraum der Hand.
 * * Klarer Schnitt: immer, breit und hell ([Style.ART]); dazu die große Ebene in [ArtVisuals].
 * * Arius im Bosskampf: immer bei schnellen Schlägen, Weiß bzw. Eisblau je nach Form.
 */
internal object SwordTrails {

    enum class Style(val inner: Float, val outer: Float, val life: Float, val core: Int, val edge: Int, val alpha: Float, val minSpeed: Float, val always: Boolean = false) {
        /** Solvane, normaler Schlag. */
        SOLVANE(0.5f, 0.1f, 4.5f, 0xFFF4FAFF.toInt(), 0xFFB8E8FF.toInt(), 0.55f, 0.18f),

        /** Klarer Schnitt: fast die ganze Klinge, lang nachleuchtend. */
        ART(0.12f, 0.22f, 7f, 0xFFFFFFFF.toInt(), 0xFFB8E8FF.toInt(), 0.85f, 0.08f),

        /** Arius, dunkle und ernste Form: Eisblau. */
        ARIUS_ICE(0.4f, 0.12f, 5f, 0xFFD8F1FF.toInt(), 0xFF7FC4F0.toInt(), 0.5f, 0.25f, always = true),

        /** Arius, weiße Form: Weiß mit kaltem Saum. */
        ARIUS_WHITE(0.4f, 0.12f, 5f, 0xFFFFFFFF.toInt(), 0xFFDDF2FF.toInt(), 0.6f, 0.25f, always = true),
    }

    private class Sample(val bx: Double, val by: Double, val bz: Double, val tx: Double, val ty: Double, val tz: Double, val time: Float, val speed: Float)

    private class Trail {
        val samples = ArrayDeque<Sample>()
        var style = Style.SOLVANE
        var visibleFrom = Float.MAX_VALUE
        var visibleUntil = -1f
        var forced = false
        var touched = 0L
    }

    private val world = HashMap<Int, Trail>()
    private val view = Trail()
    private var sweepAt = 0L

    /** Schlagkraft beim letzten eigenen Linksklick mit Solvane (0..1), gesetzt in [ArtClient.onAttack]. */
    @JvmField
    var lastAttackStrength = 1f

    private fun now(td: Float): Float = ArtAnims.ticks + td

    private fun sweep() {
        if (ArtAnims.ticks - sweepAt < 100) return
        sweepAt = ArtAnims.ticks
        world.values.removeIf { ArtAnims.ticks - it.touched > 60 }
    }

    /** Ein voller Treffer: die laufende Schwungbahn (auch schon gesammelte Bilder) wird sichtbar. */
    fun confirm(entityId: Int) {
        val n = ArtAnims.ticks.toFloat()
        for (t in listOfNotNull(world[entityId], if (MinecraftClient.getInstance().player?.id == entityId) view else null)) {
            if (t.forced) continue
            t.visibleFrom = min(if (t.visibleUntil > n - 1f) t.visibleFrom else Float.MAX_VALUE, n - 5f)
            t.visibleUntil = n + 6f
        }
    }

    /** Eigener Spieler, Linksklick mit Solvane auf eine Entity mit voller Schlagkraft: sofort sichtbar (ohne Netzlaufzeit). */
    fun confirmLocal(entityId: Int) = confirm(entityId)

    private fun record(t: Trail, base: Vec3d, tip: Vec3d, now: Float, style: Style, forced: Boolean) {
        t.touched = ArtAnims.ticks
        if (t.style != style) t.samples.clear()
        t.style = style
        t.forced = forced
        if (forced) {
            if (t.visibleUntil < now) t.visibleFrom = now - 0.5f
            t.visibleUntil = now + 0.5f
        }
        val last = t.samples.lastOrNull()
        if (last != null && now - last.time < 0.02f) return
        // Schwunggeschwindigkeit: Bewegung der Spitze gegenüber dem Ansatz (Drehung der Klinge), Laufen und Sprünge des
        // Trägers zählen nicht.
        var jump = false
        val speed = if (last == null) 0f else {
            val dt = max(0.05f, now - last.time)
            val dx = (tip.x - base.x) - (last.tx - last.bx)
            val dy = (tip.y - base.y) - (last.ty - last.by)
            val dz = (tip.z - base.z) - (last.tz - last.bz)
            val mx = base.x - last.bx; val my = base.y - last.by; val mz = base.z - last.bz
            jump = (mx * mx + my * my + mz * mz) / (dt * dt) > 4.0 * 4.0
            (sqrt(dx * dx + dy * dy + dz * dz) / dt).toFloat()
        }
        // Sprung (Teleport, neue Szene): Bahn neu beginnen.
        if (jump) t.samples.clear()
        t.samples.addLast(Sample(base.x, base.y, base.z, tip.x, tip.y, tip.z, now, speed))
        while (t.samples.isNotEmpty() && now - t.samples.first().time > style.life) t.samples.removeFirst()
        while (t.samples.size > 96) t.samples.removeFirst()
    }

    /** Weltkoordinaten (dritte Person, Arius). */
    fun recordWorld(entityId: Int, base: Vec3d, tip: Vec3d, td: Float, style: Style, forced: Boolean = false) {
        sweep()
        record(world.getOrPut(entityId) { Trail() }, base, tip, now(td), style, forced)
    }

    /** Blickraum der Hand (erste Person, eigener Spieler). */
    fun recordView(base: Vector3f, tip: Vector3f, td: Float, style: Style, forced: Boolean = false) =
        record(view, Vec3d(base.x.toDouble(), base.y.toDouble(), base.z.toDouble()), Vec3d(tip.x.toDouble(), tip.y.toDouble(), tip.z.toDouble()), now(td), style, forced)

    /** Zeichnet die Spur von [entityId] (Welt) als eigener Befehl; Koordinaten kamerarelativ. */
    fun renderWorld(queue: OrderedRenderCommandQueue, entityId: Int, td: Float) {
        val t = world[entityId] ?: return
        val cam = MinecraftClient.getInstance().gameRenderer.camera.cameraPos
        draw(queue, t, cam.x, cam.y, cam.z, now(td))
    }

    /** Zeichnet die Spur der ersten Person (Blickraum). */
    fun renderView(queue: OrderedRenderCommandQueue, td: Float) = draw(queue, view, 0.0, 0.0, 0.0, now(td))

    private val pa = Vector3f()
    private val pb = Vector3f()
    private val pc = Vector3f()
    private val pd = Vector3f()
    private val pe = Vector3f()
    private val pf = Vector3f()

    private fun draw(queue: OrderedRenderCommandQueue, t: Trail, ox: Double, oy: Double, oz: Double, now: Float) {
        val fx = ArtVisualGlow.fx()
        if (fx <= 0f || t.samples.size < 2) return
        val s = t.style
        if (!s.always && !t.forced && t.visibleUntil < now - s.life) return
        val b = ArtVisualGlow.Builder(ArtVisualGlow.VIEW_RIGHT, ArtVisualGlow.VIEW_UP)
        var prev: Sample? = null
        var prevA = 0f
        for (cur in t.samples) {
            val age = now - cur.time
            val vis = s.always || (cur.time >= t.visibleFrom - 0.01f && cur.time <= t.visibleUntil + 0.01f)
            val fast = ArtPoseMath.smooth((cur.speed - s.minSpeed) / (s.minSpeed * 1.5f + 0.05f))
            val a = if (!vis || age < 0f) 0f else s.alpha * fx * fast * (1f - age / s.life).coerceIn(0f, 1f).let { it * sqrt(it) }
            val p = prev
            if (p != null && (a > 0.004f || prevA > 0.004f)) {
                point(p, s.inner, ox, oy, oz, pa); point(p, 1f, ox, oy, oz, pb); point(p, 1f + s.outer, ox, oy, oz, pc)
                point(cur, s.inner, ox, oy, oz, pd); point(cur, 1f, ox, oy, oz, pe); point(cur, 1f + s.outer, ox, oy, oz, pf)
                // Innen (v 0 → 0,5) und Saum (0,5 → 1) auf GLOW_ADD: hell an der Bahn der Spitze, beidseitig weich.
                b.strip(pa, pb, pd, pe, 0f, 0.5f, ArtVisualGlow.alpha(s.edge, prevA), ArtVisualGlow.alpha(s.edge, a))
                b.strip(pb, pc, pe, pf, 0.5f, 1f, ArtVisualGlow.alpha(s.core, prevA), ArtVisualGlow.alpha(s.core, a))
            }
            prev = cur
            prevA = a
        }
        b.submit(queue, VfxLayers.GLOW_ADD)
    }

    /** Punkt auf der Klinge: 0 = Ansatz, 1 = Spitze, > 1 über die Spitze hinaus; relativ zu (ox, oy, oz). */
    private fun point(s: Sample, k: Float, ox: Double, oy: Double, oz: Double, out: Vector3f): Vector3f =
        out.set((s.bx + (s.tx - s.bx) * k - ox).toFloat(), (s.by + (s.ty - s.by) * k - oy).toFloat(), (s.bz + (s.tz - s.bz) * k - oz).toFloat())

    fun clear() {
        world.clear()
        view.samples.clear()
    }
}
