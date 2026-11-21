package dev.henny.hha_arius.client.fx

import dev.henny.hha.client.vfx.VfxDraw
import dev.henny.hha.client.vfx.withAlpha
import dev.henny.hha.logic.Vfx
import dev.henny.hha.net.VfxPayload
import dev.henny.hha_arius.arena.ArenaDimension
import net.minecraft.client.render.VertexConsumer
import net.minecraft.client.world.ClientWorld
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import net.minecraft.util.math.Vec3d
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Bodenmarker und Trefferbilder in Eirholm, gebaut für den hellsten Fall: Fullbright-Gamma auf weißem Schnee, ohne Nebel
 * und ohne Partikel. Additives Licht verschwindet dort, also trägt dunkle Tinte die Form und die Farbe liegt darauf:
 * * Rot ist Gefahr, Gold ist sicher.
 * * Der Rand ist eine scharfe Linie mit Gravur, das Innere bleibt frei. Es gibt keine Bodenfläche und keine Wände.
 * * Eine schmale Front läuft zum Rand: Erreicht sie ihn, schlägt der Angriff ein.
 * * Im letzten Drittel stellen sich kleine Dornen am Rand auf, damit der Marker im Sturm über den Schnee ragt.
 * Die Linienbreite wächst mit dem Abstand zur Kamera, damit auch ferne Marker lesbar bleiben.
 */
object GroundWarnings {

    private class Palette(val shadow: Int, val ink: Int, val line: Int, val hot: Int, val shade: Int)

    private val DANGER = Palette(0xFF22040A.toInt(), 0xFF4A0712.toInt(), 0xFFE5122C.toInt(), 0xFFFFD2C4.toInt(), 0xFF9E1026.toInt())
    private val SAFE = Palette(0xFF2B1C03.toInt(), 0xFF5A3A04.toInt(), 0xFFFFB400.toInt(), 0xFFFFF6D2.toInt(), 0xFFE89A00.toInt())

    /** Punktfolge auf dem Boden, je Punkt die Normale zur Innenseite (auf Gehrung gestreckt). */
    private class Poly(val pts: Array<Vec3d>, val ns: Array<Vec3d>, val closed: Boolean) {
        val spans: Int get() = if (closed) pts.size else pts.size - 1
    }

    private class Mark(
        val kind: Vfx.Kind,
        val origin: Vec3d,
        val vector: Vec3d,
        val radius: Double,
        val inner: Double,
        val safe: Boolean,
        val born: Long,
        val life: Int,
        val rims: List<Poly>,
    ) {
        val heights = HashMap<Long, Double>()
    }

    private class Burst(
        val kind: Vfx.Kind,
        val style: Int,
        val pos: Vec3d,
        val dir: Vec3d,
        val a: Double,
        val b: Double,
        val born: Long,
        val life: Int,
    )

    private val X = Vec3d(1.0, 0.0, 0.0)
    private val Z = Vec3d(0.0, 0.0, 1.0)

    private var level: ClientWorld? = null
    private val marks = ArrayList<Mark>()
    private val bursts = ArrayList<Burst>()

    /** Liegt ein Marker nahe der Kamera? Dann nimmt der Sturm sein Schneetreiben und die Weißblende zurück. */
    var nearWarning = false
        private set

    fun reset() {
        level = null
        nearWarning = false
        marks.clear()
        bursts.clear()
    }

    /** @return true, wenn dieser Marker den Marker von HHA ersetzt (dann zeichnet HHA ihn nicht zusätzlich). */
    @JvmStatic
    fun receive(p: VfxPayload, world: ClientWorld): Boolean {
        if (world.registryKey != ArenaDimension.WORLD) return false
        if (world !== level) {
            reset()
            level = world
        }
        val kind = Vfx.Kind.entries.getOrNull(p.kind) ?: return false
        if (kind.name == "TELEGRAPH_CLEAR") {
            val origin = Vec3d(p.x, p.y, p.z)
            marks.removeIf { it.origin.squaredDistanceTo(origin) < 1e-4 }
            return false
        }
        if (kind == Vfx.Kind.CRESCENT || kind == Vfx.Kind.SHOCKWAVE || kind == Vfx.Kind.NOVA) {
            if (bursts.size >= 32) bursts.removeAt(0)
            bursts += Burst(
                kind, p.style, Vec3d(p.x, p.y, p.z), Vec3d(p.dx, p.dy, p.dz),
                p.a.toDouble().coerceIn(0.4, 48.0), p.b.toDouble().coerceIn(0.2, 16.0), world.time, p.life.coerceIn(5, 26),
            )
            return false
        }
        if (kind != Vfx.Kind.TELEGRAPH_LINE && kind != Vfx.Kind.TELEGRAPH_RING && kind != Vfx.Kind.TELEGRAPH_CONE) return false
        val origin = Vec3d(p.x, p.y, p.z)
        val vector = Vec3d(p.dx, p.dy, p.dz)
        val radius = p.a.toDouble().coerceIn(0.05, 256.0)
        val inner = p.b.toDouble()
        val heights = HashMap<Long, Double>()
        fun ground(x: Double, z: Double) = Vec3d(x, heightAt(world, heights, x, origin.y, z), z)
        val rims = ArrayList<Poly>(2)
        when (kind) {
            Vfx.Kind.TELEGRAPH_RING -> {
                rims += circle(origin, radius, ::ground)
                if (inner > 0.05 && inner < radius - 0.05) rims += circle(origin, inner, ::ground, invert = true)
            }
            Vfx.Kind.TELEGRAPH_LINE -> {
                val flat = Vec3d(vector.x - origin.x, 0.0, vector.z - origin.z)
                if (flat.lengthSquared() < 1e-6) return true
                val side = Vec3d(-flat.z, 0.0, flat.x).normalize().multiply(radius)
                rims += polygon(listOf(origin.add(side), vector.add(side), vector.subtract(side), origin.subtract(side)), ::ground)
            }
            else -> {
                val heading = atan2(vector.z, vector.x)
                val half = Math.toRadians(inner.coerceIn(1.0, 180.0))
                if (half >= PI - 0.01) {
                    rims += circle(origin, radius, ::ground)
                } else {
                    val n = ceil(half * 2 * radius / 0.8).toInt().coerceIn(8, 160)
                    val corners = ArrayList<Vec3d>(n + 2)
                    corners += origin
                    for (i in 0..n) {
                        val a = heading - half + 2 * half * i / n
                        corners += origin.add(radius * cos(a), 0.0, radius * sin(a))
                    }
                    rims += polygon(corners, ::ground)
                }
            }
        }
        if (marks.size >= 96) marks.removeAt(0)
        val safe = kind != Vfx.Kind.TELEGRAPH_CONE && p.style == Vfx.Style.GOLD.ordinal
        marks += Mark(kind, origin, vector, radius, inner, safe, world.time, p.life.coerceAtLeast(1), rims).also { it.heights.putAll(heights) }
        return true
    }

    // --- Geometrie -----------------------------------------------------------------------------------------------------

    /** Höhe der begehbaren Fläche nahe [refY] (Schneeschichten, Brücken), je Blocksäule gemerkt. */
    private fun heightAt(world: ClientWorld, cache: HashMap<Long, Double>, x: Double, refY: Double, z: Double): Double {
        val bx = floor(x).toInt()
        val bz = floor(z).toInt()
        val key = (bx.toLong() shl 32) xor (bz.toLong() and 0xFFFFFFFFL)
        return cache.getOrPut(key) {
            val base = BlockPos.ofFloored(x, refY, z)
            var best = 3.5
            var top = refY
            for (dy in -3..2) {
                val pos = base.up(dy)
                val shape = world.getBlockState(pos).getCollisionShape(world, pos)
                if (shape.isEmpty) continue
                val y = pos.y + shape.getMax(Direction.Axis.Y)
                val off = abs(y - refY)
                if (off < best) {
                    best = off
                    top = y
                }
            }
            top + 0.07
        }
    }

    private fun circle(c: Vec3d, r: Double, ground: (Double, Double) -> Vec3d, invert: Boolean = false): Poly {
        val n = ceil(2 * PI * r / 0.7).toInt().coerceIn(28, 220)
        val pts = Array(n) { i ->
            val a = 2 * PI * i / n
            ground(c.x + r * cos(a), c.z + r * sin(a))
        }
        val sign = if (invert) -1.0 else 1.0
        val ns = Array(n) { i ->
            val a = 2 * PI * i / n
            Vec3d(-cos(a) * sign, 0.0, -sin(a) * sign)
        }
        return Poly(pts, ns, true)
    }

    /** Geschlossene Figur aus [corners], fein auf den Boden gelegt; die Normalen zeigen nach innen. */
    private fun polygon(corners: List<Vec3d>, ground: (Double, Double) -> Vec3d): Poly {
        val pts = ArrayList<Vec3d>()
        for (i in corners.indices) {
            val a = corners[i]
            val b = corners[(i + 1) % corners.size]
            val n = ceil(sqrt((b.x - a.x) * (b.x - a.x) + (b.z - a.z) * (b.z - a.z)) / 0.8).toInt().coerceIn(1, 200)
            for (k in 0 until n) {
                val s = k.toDouble() / n
                pts += ground(a.x + (b.x - a.x) * s, a.z + (b.z - a.z) * s)
            }
        }
        var area = 0.0
        for (i in pts.indices) {
            val a = pts[i]
            val b = pts[(i + 1) % pts.size]
            area += a.x * b.z - b.x * a.z
        }
        return Poly(pts.toTypedArray(), miter(pts, true, if (area > 0) 1.0 else -1.0), true)
    }

    /** Normalen einer Punktfolge, an Ecken auf Gehrung gestreckt. [turn] wählt die Seite. */
    private fun miter(pts: List<Vec3d>, closed: Boolean, turn: Double): Array<Vec3d> {
        val n = pts.size
        fun edge(i: Int): Vec3d {
            val a = pts[i]
            val b = pts[(i + 1) % n]
            val dx = b.x - a.x
            val dz = b.z - a.z
            val len = sqrt(dx * dx + dz * dz)
            return if (len < 1e-6) Vec3d(0.0, 0.0, 0.0) else Vec3d(-dz / len * turn, 0.0, dx / len * turn)
        }
        return Array(n) { i ->
            val before = if (closed) edge((i - 1 + n) % n) else edge(max(i - 1, 0))
            val after = if (closed) edge(i) else edge(min(i, n - 2))
            val sum = before.add(after)
            val len = sum.length()
            if (len < 1e-4) {
                after
            } else {
                val m = sum.multiply(1.0 / len)
                m.multiply(1.0 / max(m.dotProduct(after), 0.42))
            }
        }
    }

    // --- Zeichnen ------------------------------------------------------------------------------------------------------

    fun render(world: ClientWorld, draw: VfxDraw) {
        if (level !== world) {
            if (level != null) reset()
            return
        }
        val time = world.time
        marks.removeIf { time - it.born >= it.life }
        bursts.removeIf { time - it.born >= it.life }
        nearWarning = marks.any { it.origin.squaredDistanceTo(draw.cam) < (it.radius + 30.0) * (it.radius + 30.0) }
        if (marks.isNotEmpty()) {
            // Alles in einem Puffer: Ein zweiter Ebenen-Puffer würde den ersten vorzeitig abschließen.
            val vc = draw.buffer(AriusLayers.INK)
            for (m in marks) {
                if (m.origin.squaredDistanceTo(draw.cam) > (m.radius + 110.0) * (m.radius + 110.0)) continue
                drawMark(world, draw, vc, m, time)
            }
        }
        if (bursts.isNotEmpty() && AriusClientConfig.fx > 0.01f) renderBursts(draw, time)
    }

    private fun drawMark(world: ClientWorld, d: VfxDraw, vc: VertexConsumer, m: Mark, time: Long) {
        val t = time - m.born + d.tickDelta
        val p = (t / m.life).coerceIn(0f, 1f)
        val appear = (t / 4f).coerceIn(0f, 1f)
        val late = ((p - 0.62f) / 0.38f).coerceIn(0f, 1f)
        val beat = 0.5f + 0.5f * sin(t * (0.5f + 0.7f * late))
        val storm = SnowStorm.whiteout.coerceIn(0f, 1f)
        val pal = if (m.safe) SAFE else DANGER
        val grow = 1.0 + 0.3 * storm + 0.22 * late * beat
        val near = m.origin.squaredDistanceTo(d.cam) < 70.0 * 70.0

        for (rim in m.rims) {
            // Innenschatten: nur eine schmale Kante, damit das Feld als Fläche lesbar ist, ohne den Boden zu fluten.
            val inside = min(1.25, m.radius * 0.42)
            band(d, vc, rim, 0.0, inside, 0.012, withAlpha(pal.shade, (0.17f + 0.15f * late + 0.1f * storm) * appear), withAlpha(pal.shade, 0f), false)
            // Weicher dunkler Hof, trägt die Linie auf weißem Schnee.
            val hof = 0.42 * grow
            band(d, vc, rim, -hof, 0.0, 0.016, withAlpha(pal.shadow, 0f), withAlpha(pal.shadow, (0.3f + 0.2f * storm) * appear), true)
            band(d, vc, rim, 0.0, hof, 0.016, withAlpha(pal.shadow, (0.3f + 0.2f * storm) * appear), withAlpha(pal.shadow, 0f), true)
            // Schnittkante.
            val ink = 0.15 * grow
            band(d, vc, rim, -ink, ink, 0.02, withAlpha(pal.ink, 0.96f * appear), withAlpha(pal.ink, 0.96f * appear), true)
            val line = 0.088 * grow
            band(d, vc, rim, -line, line, 0.024, withAlpha(pal.line, appear), withAlpha(pal.line, appear), true)
            val core = 0.026 * grow * (1.0 + late)
            band(d, vc, rim, -core, core, 0.028, withAlpha(pal.hot, (0.28f + 0.72f * late) * appear), withAlpha(pal.hot, (0.28f + 0.72f * late) * appear), true)
            if (near) engrave(d, vc, rim, pal, m.safe, appear, late, storm, t)
        }

        val heights = m.heights
        val ground = { x: Double, z: Double -> Vec3d(x, heightAt(world, heights, x, m.origin.y, z), z) }
        front(m, p, ground)?.let { f ->
            val trail = min(0.95, m.radius * 0.26)
            band(d, vc, f, 0.0, trail, 0.03, withAlpha(pal.line, (0.5f + 0.35f * late) * appear), withAlpha(pal.line, 0f), false)
            val w = 0.1 * grow
            band(d, vc, f, -w, w, 0.034, withAlpha(pal.ink, 0.92f * appear), withAlpha(pal.ink, 0.92f * appear), true)
            val h = 0.046 * grow
            band(d, vc, f, -h, h, 0.038, withAlpha(pal.hot, appear), withAlpha(pal.hot, appear), true)
        }
        if (near && (m.kind == Vfx.Kind.TELEGRAPH_LINE || m.kind == Vfx.Kind.TELEGRAPH_CONE)) chevrons(d, vc, m, pal, appear, t, ground)
    }

    /** Ein Streifen entlang [poly] zwischen den Versätzen [from] und [to] (nach innen positiv). */
    private fun band(d: VfxDraw, vc: VertexConsumer, poly: Poly, from: Double, to: Double, lift: Double, cFrom: Int, cTo: Int, scaled: Boolean) {
        val pts = poly.pts
        val ns = poly.ns
        val size = pts.size
        with(d) {
            for (i in 0 until poly.spans) {
                val j = (i + 1) % size
                val a = pts[i]
                val b = pts[j]
                val sa = if (scaled) widen(a, d.cam) else 1.0
                val sb = if (scaled) widen(b, d.cam) else 1.0
                val na = ns[i]
                val nb = ns[j]
                vc.put(a.x + na.x * from * sa, a.y + lift, a.z + na.z * from * sa, 0f, 0f, cFrom)
                vc.put(a.x + na.x * to * sa, a.y + lift, a.z + na.z * to * sa, 1f, 0f, cTo)
                vc.put(b.x + nb.x * to * sb, b.y + lift, b.z + nb.z * to * sb, 1f, 1f, cTo)
                vc.put(b.x + nb.x * from * sb, b.y + lift, b.z + nb.z * from * sb, 0f, 1f, cFrom)
            }
        }
    }

    private fun widen(p: Vec3d, cam: Vec3d): Double {
        val dx = p.x - cam.x
        val dy = p.y - cam.y
        val dz = p.z - cam.z
        return (sqrt(dx * dx + dy * dy + dz * dz) / 11.0).coerceIn(1.0, 2.8)
    }

    /** Gravur am Rand: feine Kerben nach innen (Gefahr) oder Rauten (sicher), im letzten Drittel kleine Dornen. */
    private fun engrave(d: VfxDraw, vc: VertexConsumer, rim: Poly, pal: Palette, safe: Boolean, appear: Float, late: Float, storm: Float, t: Float) {
        val every = 2
        val strong = withAlpha(pal.ink, 0.88f * appear)
        val bright = withAlpha(pal.line, 0.95f * appear)
        val rise = max(late, storm * 0.55f)
        with(d) {
            var i = 0
            var n = 0
            while (i < rim.pts.size) {
                val a = rim.pts[i]
                val nn = rim.ns[i]
                val s = widen(a, d.cam)
                val major = n % 4 == 0
                val along = Vec3d(-nn.z, 0.0, nn.x)
                val y = a.y + 0.03
                if (safe) {
                    val c = a.add(nn.multiply(0.42 * s))
                    val r = (if (major) 0.15 else 0.09) * s
                    vc.put(c.x + nn.x * r, y, c.z + nn.z * r, 0f, 0f, bright)
                    vc.put(c.x + along.x * r, y, c.z + along.z * r, 1f, 0f, bright)
                    vc.put(c.x - nn.x * r, y, c.z - nn.z * r, 1f, 1f, strong)
                    vc.put(c.x - along.x * r, y, c.z - along.z * r, 0f, 1f, strong)
                } else {
                    val w = 0.04 * s
                    val len = (if (major) 0.72 else 0.36) * s
                    vc.put(a.x + along.x * w, y, a.z + along.z * w, 0f, 0f, bright)
                    vc.put(a.x + nn.x * len, y, a.z + nn.z * len, 1f, 0f, withAlpha(pal.ink, 0f))
                    vc.put(a.x + nn.x * len, y, a.z + nn.z * len, 1f, 1f, withAlpha(pal.ink, 0f))
                    vc.put(a.x - along.x * w, y, a.z - along.z * w, 0f, 1f, bright)
                }
                if (rise > 0.01f && n % 3 == 0) {
                    val wob = 0.82 + 0.18 * sin(t * 0.55 + n * 1.7)
                    val h = (if (major) 0.92 else 0.6) * rise * wob * (0.8 + 0.2 * s)
                    val base = 0.075 * s
                    val tip = a.add(0.0, h, 0.0)
                    val foot = withAlpha(pal.line, (0.8f + 0.2f * late) * appear)
                    val top = withAlpha(pal.hot, 0.25f * appear)
                    for (side in 0..1) {
                        val w = if (side == 0) along.multiply(base) else nn.multiply(base)
                        vc.put(a.x - w.x, a.y, a.z - w.z, 0f, 1f, foot)
                        vc.put(a.x + w.x, a.y, a.z + w.z, 1f, 1f, foot)
                        vc.put(tip.x, tip.y, tip.z, 1f, 0f, top)
                        vc.put(tip.x, tip.y, tip.z, 0f, 0f, top)
                    }
                }
                i += every
                n++
            }
        }
    }

    /** Die laufende Front: erreicht sie den Rand, schlägt der Angriff ein. Die Normalen zeigen zum Ursprung. */
    private fun front(m: Mark, p: Float, ground: (Double, Double) -> Vec3d): Poly? {
        val k = p * p * (3f - 2f * p) * 0.35 + p * 0.65
        val pts = ArrayList<Vec3d>()
        var closed = false
        when (m.kind) {
            Vfx.Kind.TELEGRAPH_RING -> {
                val from = if (m.inner > 0.05 && m.inner < m.radius) m.inner else 0.0
                val r = from + (m.radius - from) * k
                if (r < 0.2) return null
                val n = ceil(2 * PI * r / 0.8).toInt().coerceIn(14, 140)
                for (i in 0 until n) {
                    val a = 2 * PI * i / n
                    pts += ground(m.origin.x + r * cos(a), m.origin.z + r * sin(a))
                }
                closed = true
            }
            Vfx.Kind.TELEGRAPH_CONE -> {
                val r = m.radius * k
                if (r < 0.25) return null
                val heading = atan2(m.vector.z, m.vector.x)
                val half = Math.toRadians(m.inner.coerceIn(1.0, 180.0))
                val n = ceil(half * 2 * r / 0.8).toInt().coerceIn(4, 120)
                for (i in 0..n) {
                    val a = heading - half + 2 * half * i / n
                    pts += ground(m.origin.x + r * cos(a), m.origin.z + r * sin(a))
                }
            }
            else -> {
                val flat = Vec3d(m.vector.x - m.origin.x, 0.0, m.vector.z - m.origin.z)
                if (flat.lengthSquared() < 1e-6) return null
                val side = Vec3d(-flat.z, 0.0, flat.x).normalize().multiply(m.radius)
                val c = m.origin.add(flat.multiply(k))
                val n = ceil(m.radius * 2 / 0.8).toInt().coerceIn(2, 40)
                for (i in 0..n) {
                    val s = -1.0 + 2.0 * i / n
                    pts += ground(c.x + side.x * s, c.z + side.z * s)
                }
            }
        }
        if (pts.size < 2) return null
        var ns = miter(pts, closed, 1.0)
        val probe = pts[pts.size / 2]
        val toOrigin = Vec3d(m.origin.x - probe.x, 0.0, m.origin.z - probe.z)
        if (ns[pts.size / 2].dotProduct(toOrigin) < 0.0) ns = Array(ns.size) { ns[it].multiply(-1.0) }
        return Poly(pts.toTypedArray(), ns, closed)
    }

    /** Laufende Winkel entlang der Stoßrichtung von Linie und Kegel. */
    private fun chevrons(d: VfxDraw, vc: VertexConsumer, m: Mark, pal: Palette, appear: Float, t: Float, ground: (Double, Double) -> Vec3d) {
        val dir: Vec3d
        val length: Double
        val half: Double
        if (m.kind == Vfx.Kind.TELEGRAPH_LINE) {
            val flat = Vec3d(m.vector.x - m.origin.x, 0.0, m.vector.z - m.origin.z)
            length = flat.length()
            if (length < 1.5) return
            dir = flat.multiply(1.0 / length)
            half = min(m.radius * 0.6, 0.95)
        } else {
            val flat = Vec3d(m.vector.x, 0.0, m.vector.z)
            if (flat.lengthSquared() < 1e-6) return
            dir = flat.normalize()
            length = m.radius
            half = min(m.radius * 0.16, 0.8)
        }
        val side = Vec3d(-dir.z, 0.0, dir.x)
        val count = ceil(length / 3.4).toInt().coerceIn(2, 7)
        with(d) {
            for (i in 0 until count) {
                val u = ((t * 0.035f + i.toFloat() / count) % 1f)
                val fade = (min(u, 1f - u) * 6f).coerceIn(0f, 1f) * 0.8f * appear
                if (fade < 0.02f) continue
                val at = m.origin.add(dir.multiply(0.8 + (length - 1.2) * u))
                val tip = ground(at.x, at.z)
                val wide = half * (if (m.kind == Vfx.Kind.TELEGRAPH_CONE) (0.35 + 0.65 * u) else 1.0)
                val back = dir.multiply(-wide * 0.72)
                val c = withAlpha(pal.line, fade)
                val soft = withAlpha(pal.ink, fade * 0.7f)
                for (s in intArrayOf(-1, 1)) {
                    val w = side.multiply(wide * s)
                    val thick = dir.multiply(0.13)
                    val y = tip.y + 0.04
                    vc.put(tip.x, y, tip.z, 0f, 0f, c)
                    vc.put(tip.x + thick.x, y, tip.z + thick.z, 1f, 0f, c)
                    vc.put(tip.x + w.x + back.x + thick.x, y, tip.z + w.z + back.z + thick.z, 1f, 1f, soft)
                    vc.put(tip.x + w.x + back.x, y, tip.z + w.z + back.z, 0f, 1f, soft)
                }
            }
        }
    }

    // --- Trefferbilder ---------------------------------------------------------------------------------------------------

    /** Kalte, klare Einschläge: dunkle Kante, heller Kern. Sie liegen über den Lichteffekten von HHA. */
    private fun renderBursts(draw: VfxDraw, time: Long) {
        val fx = AriusClientConfig.fx.coerceIn(0f, 1f)
        val vc = draw.buffer(AriusLayers.INK)
        for (b in bursts) {
            if (b.pos.squaredDistanceTo(draw.cam) > 90.0 * 90.0) continue
            val t = (time - b.born + draw.tickDelta).coerceAtLeast(0f)
            val u = (t / b.life).coerceIn(0f, 1f)
            val fade = (1f - u) * (1f - u) * fx
            if (fade <= 0.01f) continue
            val gold = b.style == Vfx.Style.GOLD.ordinal
            val deep = if (gold) 0xFF5A3A04.toInt() else 0xFF12324F.toInt()
            val mid = if (gold) 0xFFFFB400.toInt() else 0xFF58BDF0.toInt()
            val hot = if (gold) 0xFFFFF6D2.toInt() else 0xFFF2FCFF.toInt()
            when (b.kind) {
                Vfx.Kind.CRESCENT -> crescent(draw, vc, b, u, fade, deep, mid, hot)
                Vfx.Kind.SHOCKWAVE -> {
                    val grow = 1.0 - (1.0 - u) * (1.0 - u) * (1.0 - u)
                    val r = max(0.3, b.a * grow)
                    val thick = max(0.2, min(0.62, b.b * 0.34)) * (1.0 - u * 0.6)
                    flatRing(draw, vc, b.pos.add(0.0, 0.12, 0.0), r - thick * 1.7, r - thick * 0.5, withAlpha(mid, 0f), withAlpha(mid, 0.5f * fade))
                    flatRing(draw, vc, b.pos.add(0.0, 0.13, 0.0), r - thick * 0.5, r, withAlpha(deep, 0.9f * fade), withAlpha(deep, 0.9f * fade))
                    flatRing(draw, vc, b.pos.add(0.0, 0.14, 0.0), r - thick * 0.34, r - thick * 0.1, withAlpha(hot, fade), withAlpha(hot, fade))
                }
                Vfx.Kind.NOVA -> {
                    val grow = 1.0 - (1.0 - u) * (1.0 - u)
                    val r = max(0.3, b.a * (0.3 + 0.8 * grow))
                    val thick = max(0.1, r * 0.09)
                    camRing(draw, vc, b.pos, r - thick, r, withAlpha(deep, 0.8f * fade), withAlpha(mid, 0.95f * fade))
                    camRing(draw, vc, b.pos, r - thick * 0.45, r - thick * 0.12, withAlpha(hot, fade), withAlpha(hot, fade))
                }
                else -> Unit
            }
        }
    }

    /** Sichelklinge: an den Enden spitz, außen dunkle Kante, innen weißer Schnitt. */
    private fun crescent(d: VfxDraw, vc: VertexConsumer, b: Burst, u: Float, fade: Float, deep: Int, mid: Int, hot: Int) {
        val fwd = Vec3d(b.dir.x, 0.0, b.dir.z).let { if (it.lengthSquared() < 1e-6) Z else it.normalize() }
        val side = Vec3d(-fwd.z, 0.0, fwd.x)
        val span = b.b.coerceIn(0.8, 2.6)
        val reach = b.a * (0.82 + 0.26 * u)
        val thick = min(b.a * 0.2, 1.05) * (1.0 - u * 0.45)
        val n = 26
        fun point(s: Double, off: Double): Vec3d {
            val a = (s - 0.5) * span
            val r = reach - off
            return b.pos.add(fwd.multiply(cos(a) * r)).add(side.multiply(sin(a) * r))
        }
        fun taper(s: Double) = sin(s * PI).coerceAtLeast(0.0)
        fun strip(o0: Double, o1: Double, c0: Int, c1: Int, y: Double) {
            with(d) {
                for (i in 0 until n) {
                    val s0 = i.toDouble() / n
                    val s1 = (i + 1.0) / n
                    val a0 = point(s0, o0 * taper(s0))
                    val a1 = point(s0, o1 * taper(s0))
                    val b1 = point(s1, o1 * taper(s1))
                    val b0 = point(s1, o0 * taper(s1))
                    vc.put(a0.x, a0.y + y, a0.z, 0f, 0f, c0)
                    vc.put(a1.x, a1.y + y, a1.z, 1f, 0f, c1)
                    vc.put(b1.x, b1.y + y, b1.z, 1f, 1f, c1)
                    vc.put(b0.x, b0.y + y, b0.z, 0f, 1f, c0)
                }
            }
        }
        strip(0.0, thick, withAlpha(deep, 0.95f * fade), withAlpha(deep, 0.5f * fade), 0.0)
        strip(thick * 0.08, thick * 0.72, withAlpha(mid, 0.92f * fade), withAlpha(mid, 0f), 0.004)
        strip(thick * 0.12, thick * 0.34, withAlpha(hot, fade), withAlpha(hot, 0.35f * fade), 0.008)
    }

    private fun flatRing(d: VfxDraw, vc: VertexConsumer, c: Vec3d, r0: Double, r1: Double, c0: Int, c1: Int) {
        if (r1 <= 0.0) return
        val inner = max(0.0, r0)
        val n = ceil(2 * PI * r1 / 0.9).toInt().coerceIn(24, 120)
        with(d) {
            for (i in 0 until n) {
                val a0 = 2 * PI * i / n
                val a1 = 2 * PI * (i + 1) / n
                vc.put(c.x + inner * cos(a0), c.y, c.z + inner * sin(a0), 0f, 0f, c0)
                vc.put(c.x + r1 * cos(a0), c.y, c.z + r1 * sin(a0), 1f, 0f, c1)
                vc.put(c.x + r1 * cos(a1), c.y, c.z + r1 * sin(a1), 1f, 1f, c1)
                vc.put(c.x + inner * cos(a1), c.y, c.z + inner * sin(a1), 0f, 1f, c0)
            }
        }
    }

    private fun camRing(d: VfxDraw, vc: VertexConsumer, c: Vec3d, r0: Double, r1: Double, c0: Int, c1: Int) {
        if (r1 <= 0.0) return
        val inner = max(0.0, r0)
        val right = d.camRight
        val up = d.camUp
        val n = ceil(2 * PI * r1 / 0.5).toInt().coerceIn(20, 72)
        fun at(a: Double, r: Double) = c.add(right.multiply(cos(a) * r)).add(up.multiply(sin(a) * r))
        with(d) {
            for (i in 0 until n) {
                val a0 = 2 * PI * i / n
                val a1 = 2 * PI * (i + 1) / n
                vc.put(at(a0, inner), 0f, 0f, c0)
                vc.put(at(a0, r1), 1f, 0f, c1)
                vc.put(at(a1, r1), 1f, 1f, c1)
                vc.put(at(a1, inner), 0f, 1f, c0)
            }
        }
    }
}
