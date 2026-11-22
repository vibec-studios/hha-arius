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
import java.util.Random
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
 * Bodenmarker und Trefferbilder in Eirholm: eine einzige Frostsprache, gebaut für den hellsten Fall (Fullbright-Gamma auf
 * weißem Schnee, ohne Nebel und Partikel). Additives Licht verschwindet dort, also trägt dunkle Eistinte die Form:
 * * Der Rand ist eine haarfeine, dunkelblaue Linie mit hellem Eisgrat. Es gibt keine Bodenfläche und keine Wände.
 * * Risse und Raureif kriechen vom Rand zur Mitte (Ring) oder die Bahn entlang (Linie, Kegel). Sie kommen genau beim
 *   Treffer an, und eine feine Frostfront zeigt den Stand.
 * * Gold bedeutet sicher: gleich fein, aber ohne Risse.
 * Die Linienbreite wächst mit dem Abstand zur Kamera, damit auch ferne Marker lesbar bleiben.
 */
object GroundWarnings {

    private class Palette(val halo: Int, val ink: Int, val line: Int, val hot: Int)

    private val FROST = Palette(0xFF0C2C4C.toInt(), 0xFF103C66.toInt(), 0xFF57C6F2.toInt(), 0xFFF2FCFF.toInt())
    private val SAFE = Palette(0xFF3A2704.toInt(), 0xFF6B4708.toInt(), 0xFFFFC23A.toInt(), 0xFFFFF8E0.toInt())

    /** Punktfolge auf dem Boden, je Punkt die Normale zur Innenseite (auf Gehrung gestreckt). */
    private class Poly(val pts: Array<Vec3d>, val ns: Array<Vec3d>, val closed: Boolean) {
        val spans: Int get() = if (closed) pts.size else pts.size - 1
    }

    /** Ein Riss: läuft zwischen den Fortschritten [from] und [to] von seinem ersten zum letzten Punkt. */
    private class Crack(val pts: Array<Vec3d>, val from: Float, val to: Float, val width: Double)

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
        val cracks: List<Crack>,
        val heights: HashMap<Long, Double>,
    )

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
        val safe = kind != Vfx.Kind.TELEGRAPH_CONE && p.style == Vfx.Style.GOLD.ordinal
        val heights = HashMap<Long, Double>()
        val ground = { x: Double, z: Double -> Vec3d(x, heightAt(world, heights, x, origin.y, z), z) }
        val rng = Random(java.lang.Double.doubleToLongBits(origin.x * 31.0 + origin.z) xor world.time)
        val rims = ArrayList<Poly>(2)
        val cracks = ArrayList<Crack>()
        when (kind) {
            Vfx.Kind.TELEGRAPH_RING -> {
                rims += circle(origin, radius, ground)
                val hole = if (inner > 0.05 && inner < radius - 0.05) inner else 0.0
                if (hole > 0.0) rims += circle(origin, hole, ground, invert = true)
                if (!safe) ringCracks(origin, radius, hole, rng, ground, cracks)
            }
            Vfx.Kind.TELEGRAPH_LINE -> {
                val flat = Vec3d(vector.x - origin.x, 0.0, vector.z - origin.z)
                if (flat.lengthSquared() < 1e-6) return true
                val side = Vec3d(-flat.z, 0.0, flat.x).normalize().multiply(radius)
                rims += polygon(listOf(origin.add(side), vector.add(side), vector.subtract(side), origin.subtract(side)), ground)
                if (!safe) laneCracks(origin, flat, side, rng, ground, cracks)
            }
            else -> {
                val heading = atan2(vector.z, vector.x)
                val half = Math.toRadians(inner.coerceIn(1.0, 180.0))
                if (half >= PI - 0.01) {
                    rims += circle(origin, radius, ground)
                    ringCracks(origin, radius, 0.0, rng, ground, cracks)
                } else {
                    val n = ceil(half * 2 * radius / 0.8).toInt().coerceIn(8, 160)
                    val corners = ArrayList<Vec3d>(n + 2)
                    corners += origin
                    for (i in 0..n) {
                        val a = heading - half + 2 * half * i / n
                        corners += origin.add(radius * cos(a), 0.0, radius * sin(a))
                    }
                    rims += polygon(corners, ground)
                    coneCracks(origin, radius, heading, half, rng, ground, cracks)
                }
            }
        }
        if (marks.size >= 96) marks.removeAt(0)
        marks += Mark(kind, origin, vector, radius, inner, safe, world.time, p.life.coerceAtLeast(1), rims, cracks, heights)
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
        val n = ceil(2 * PI * r / 0.6).toInt().coerceIn(32, 240)
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
            val n = ceil(sqrt((b.x - a.x) * (b.x - a.x) + (b.z - a.z) * (b.z - a.z)) / 0.7).toInt().coerceIn(1, 220)
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

    /** Gezackter Riss von [a] nach [b]; die Auslenkung läuft zu beiden Enden auf null. */
    private fun jagged(a: Vec3d, b: Vec3d, amp: Double, rng: Random, ground: (Double, Double) -> Vec3d): Array<Vec3d> {
        val dx = b.x - a.x
        val dz = b.z - a.z
        val len = sqrt(dx * dx + dz * dz)
        val n = ceil(len / 0.62).toInt().coerceIn(2, 64)
        val nx = if (len < 1e-6) 0.0 else -dz / len
        val nz = if (len < 1e-6) 0.0 else dx / len
        var off = 0.0
        return Array(n + 1) { i ->
            val s = i.toDouble() / n
            off = (off + (rng.nextDouble() - 0.5) * amp * 1.1) * 0.78
            val env = sin(s * PI).coerceAtLeast(0.0)
            ground(a.x + dx * s + nx * off * env, a.z + dz * s + nz * off * env)
        }
    }

    /** Seitliche Raureif-Äste an einem Riss, die später loslaufen und früher enden als der Hauptriss. */
    private fun branches(main: Array<Vec3d>, from: Float, to: Float, reach: Double, rng: Random, ground: (Double, Double) -> Vec3d, out: ArrayList<Crack>) {
        val count = 1 + rng.nextInt(2)
        for (b in 0 until count) {
            val s = 0.18 + rng.nextDouble() * 0.55
            val i = (s * (main.size - 2)).toInt().coerceIn(0, main.size - 2)
            val at = main[i]
            val next = main[i + 1]
            val base = atan2(next.z - at.z, next.x - at.x)
            val ang = base + (if (rng.nextBoolean()) 1 else -1) * (0.45 + rng.nextDouble() * 0.5)
            val len = reach * (0.5 + rng.nextDouble() * 0.5)
            val tip = Vec3d(at.x + cos(ang) * len, at.y, at.z + sin(ang) * len)
            val start = from + (to - from) * s.toFloat()
            out += Crack(jagged(at, tip, 0.22, rng, ground), start, min(to, start + (to - from) * 0.3f), 0.62)
        }
    }

    private fun ringCracks(c: Vec3d, r: Double, hole: Double, rng: Random, ground: (Double, Double) -> Vec3d, out: ArrayList<Crack>) {
        val n = ceil(r * 1.7).toInt().coerceIn(6, 26)
        val turn = rng.nextDouble() * PI * 2
        for (i in 0 until n) {
            val a = turn + 2 * PI * (i + (rng.nextDouble() - 0.5) * 0.45) / n
            val from = Vec3d(c.x + r * cos(a), c.y, c.z + r * sin(a))
            val end = if (hole > 0.0) hole else r * 0.02
            val to = Vec3d(c.x + end * cos(a), c.y, c.z + end * sin(a))
            val main = jagged(from, to, min(0.42, r * 0.09), rng, ground)
            out += Crack(main, 0f, 1f, 1.0)
            branches(main, 0f, 1f, min(1.5, r * 0.3), rng, ground, out)
        }
    }

    private fun laneCracks(origin: Vec3d, flat: Vec3d, side: Vec3d, rng: Random, ground: (Double, Double) -> Vec3d, out: ArrayList<Crack>) {
        val count = ceil(side.length() * 1.6).toInt().coerceIn(2, 7)
        for (i in 0 until count) {
            val o = if (count == 1) 0.0 else -0.72 + 1.44 * i / (count - 1)
            val a = origin.add(side.multiply(o))
            val main = jagged(a, a.add(flat), 0.3, rng, ground)
            out += Crack(main, 0f, 1f, 1.0)
            branches(main, 0f, 1f, min(1.2, side.length() * 0.6), rng, ground, out)
        }
    }

    private fun coneCracks(c: Vec3d, r: Double, heading: Double, half: Double, rng: Random, ground: (Double, Double) -> Vec3d, out: ArrayList<Crack>) {
        val n = ceil(half * 2 * r / 1.5).toInt().coerceIn(3, 18)
        for (i in 0 until n) {
            val a = heading - half * 0.86 + 2 * half * 0.86 * (i + 0.5) / n
            val to = Vec3d(c.x + r * 0.98 * cos(a), c.y, c.z + r * 0.98 * sin(a))
            val main = jagged(c, to, min(0.4, r * 0.08), rng, ground)
            out += Crack(main, 0f, 1f, 1.0)
            branches(main, 0f, 1f, min(1.3, r * 0.26), rng, ground, out)
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
        val late = ((p - 0.7f) / 0.3f).coerceIn(0f, 1f)
        val storm = SnowStorm.whiteout.coerceIn(0f, 1f)
        val pal = if (m.safe) SAFE else FROST
        val grow = 1.0 + 0.32 * storm

        for (rim in m.rims) {
            val hof = 0.17 * grow
            val hofA = (0.2f + 0.2f * storm) * appear
            band(d, vc, rim, -hof, 0.0, 0.012, withAlpha(pal.halo, 0f), withAlpha(pal.halo, hofA))
            band(d, vc, rim, 0.0, hof, 0.012, withAlpha(pal.halo, hofA), withAlpha(pal.halo, 0f))
            val ink = 0.062 * grow
            band(d, vc, rim, -ink, ink, 0.018, withAlpha(pal.ink, 0.95f * appear), withAlpha(pal.ink, 0.95f * appear))
            val core = 0.024 * grow * (1.0 + 0.5 * late)
            val edge = if (late > 0f) lerpColor(pal.line, pal.hot, late) else pal.line
            band(d, vc, rim, -core, core, 0.024, withAlpha(edge, appear), withAlpha(edge, appear))
        }
        if (m.origin.squaredDistanceTo(d.cam) < 80.0 * 80.0) for (c in m.cracks) crack(d, vc, c, p, pal, appear, late, grow)
        val ground = { x: Double, z: Double -> Vec3d(x, heightAt(world, m.heights, x, m.origin.y, z), z) }
        front(m, p, ground)?.let { f ->
            val trail = min(0.48, m.radius * 0.14)
            band(d, vc, f, 0.0, trail, 0.028, withAlpha(pal.line, 0.34f * appear), withAlpha(pal.line, 0f))
            val w = 0.05 * grow
            band(d, vc, f, -w, w, 0.032, withAlpha(pal.ink, 0.9f * appear), withAlpha(pal.ink, 0.9f * appear))
            val h = 0.022 * grow
            band(d, vc, f, -h, h, 0.036, withAlpha(pal.hot, appear), withAlpha(pal.hot, appear))
        }
    }

    /** Zeichnet einen Riss bis zum aktuellen Fortschritt: spitz auslaufend, dunkler Grund, heller Grat. */
    private fun crack(d: VfxDraw, vc: VertexConsumer, c: Crack, p: Float, pal: Palette, appear: Float, late: Float, grow: Double) {
        if (p <= c.from) return
        val u = ((p - c.from) / (c.to - c.from)).coerceIn(0f, 1f)
        val segs = c.pts.size - 1
        val reach = u * segs
        val whole = floor(reach).toInt().coerceAtMost(segs)
        val frac = reach - whole
        val ink = withAlpha(pal.ink, 0.86f * appear)
        val glint = withAlpha(if (late > 0f) lerpColor(pal.line, pal.hot, late) else pal.line, 0.95f * appear)
        with(d) {
            for (i in 0 until min(whole + 1, segs)) {
                val a = c.pts[i]
                val full = c.pts[i + 1]
                val b = if (i == whole) a.lerp(full, frac.toDouble()) else full
                val dx = b.x - a.x
                val dz = b.z - a.z
                val len = sqrt(dx * dx + dz * dz)
                if (len < 1e-3) continue
                val nx = -dz / len
                val nz = dx / len
                val sa = (1.0 - i.toDouble() / segs * 0.62) * c.width * grow * widen(a, d.cam)
                val sb = (1.0 - (i + 1.0) / segs * 0.62) * c.width * grow * widen(b, d.cam) * (if (i == whole) 0.3 else 1.0)
                for (layer in 0..1) {
                    val wa = (if (layer == 0) 0.05 else 0.017) * sa
                    val wb = (if (layer == 0) 0.05 else 0.017) * sb
                    val col = if (layer == 0) ink else glint
                    val y = 0.02 + layer * 0.004
                    vc.put(a.x - nx * wa, a.y + y, a.z - nz * wa, 0f, 0f, col)
                    vc.put(a.x + nx * wa, a.y + y, a.z + nz * wa, 1f, 0f, col)
                    vc.put(b.x + nx * wb, b.y + y, b.z + nz * wb, 1f, 1f, col)
                    vc.put(b.x - nx * wb, b.y + y, b.z - nz * wb, 0f, 1f, col)
                }
            }
        }
    }

    private fun lerpColor(a: Int, b: Int, k: Float): Int {
        fun ch(shift: Int) = (((a ushr shift) and 255) + (((b ushr shift) and 255) - ((a ushr shift) and 255)) * k).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** Ein Streifen entlang [poly] zwischen den Versätzen [from] und [to] (nach innen positiv). */
    private fun band(d: VfxDraw, vc: VertexConsumer, poly: Poly, from: Double, to: Double, lift: Double, cFrom: Int, cTo: Int) {
        val pts = poly.pts
        val ns = poly.ns
        val size = pts.size
        with(d) {
            for (i in 0 until poly.spans) {
                val j = (i + 1) % size
                val a = pts[i]
                val b = pts[j]
                val sa = widen(a, d.cam)
                val sb = widen(b, d.cam)
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
        return (sqrt(dx * dx + dy * dy + dz * dz) / 9.0).coerceIn(0.7, 3.2)
    }

    /**
     * Die Frostfront zeigt den Stand: beim Ring vom Rand zur Mitte, bei Linie und Kegel die Bahn entlang. Sie kommt genau
     * beim Treffer an. Die Normalen zeigen zu der Seite, von der sie kommt.
     */
    private fun front(m: Mark, p: Float, ground: (Double, Double) -> Vec3d): Poly? {
        val pts = ArrayList<Vec3d>()
        var closed = false
        var toward = m.origin
        when (m.kind) {
            Vfx.Kind.TELEGRAPH_RING -> {
                val hole = if (m.inner > 0.05 && m.inner < m.radius) m.inner else 0.0
                val r = m.radius + (hole - m.radius) * p
                if (r < 0.18) return null
                val n = ceil(2 * PI * r / 0.7).toInt().coerceIn(14, 150)
                for (i in 0 until n) {
                    val a = 2 * PI * i / n
                    pts += ground(m.origin.x + r * cos(a), m.origin.z + r * sin(a))
                }
                closed = true
                val probe = pts[0]
                toward = probe.add(probe.subtract(m.origin))
            }
            Vfx.Kind.TELEGRAPH_CONE -> {
                val r = m.radius * p
                if (r < 0.25) return null
                val heading = atan2(m.vector.z, m.vector.x)
                val half = Math.toRadians(m.inner.coerceIn(1.0, 180.0))
                val n = ceil(half * 2 * r / 0.7).toInt().coerceIn(4, 130)
                for (i in 0..n) {
                    val a = heading - half + 2 * half * i / n
                    pts += ground(m.origin.x + r * cos(a), m.origin.z + r * sin(a))
                }
            }
            else -> {
                val flat = Vec3d(m.vector.x - m.origin.x, 0.0, m.vector.z - m.origin.z)
                if (flat.lengthSquared() < 1e-6) return null
                val side = Vec3d(-flat.z, 0.0, flat.x).normalize().multiply(m.radius)
                val c = m.origin.add(flat.multiply(p.toDouble()))
                val n = ceil(m.radius * 2 / 0.7).toInt().coerceIn(2, 40)
                for (i in 0..n) {
                    val s = -1.0 + 2.0 * i / n
                    pts += ground(c.x + side.x * s, c.z + side.z * s)
                }
            }
        }
        if (pts.size < 2) return null
        var ns = miter(pts, closed, 1.0)
        val mid = pts.size / 2
        val probe = pts[if (closed) 0 else mid]
        val back = Vec3d(toward.x - probe.x, 0.0, toward.z - probe.z)
        if (ns[if (closed) 0 else mid].dotProduct(back) < 0.0) ns = Array(ns.size) { ns[it].multiply(-1.0) }
        return Poly(pts.toTypedArray(), ns, closed)
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
            val deep = if (gold) 0xFF6B4708.toInt() else 0xFF103C66.toInt()
            val mid = if (gold) 0xFFFFC23A.toInt() else 0xFF57C6F2.toInt()
            val hot = if (gold) 0xFFFFF8E0.toInt() else 0xFFF2FCFF.toInt()
            when (b.kind) {
                Vfx.Kind.CRESCENT -> crescent(draw, vc, b, u, fade, deep, mid, hot)
                Vfx.Kind.SHOCKWAVE -> {
                    val grow = 1.0 - (1.0 - u) * (1.0 - u) * (1.0 - u)
                    val r = max(0.3, b.a * grow)
                    val thick = max(0.14, min(0.4, b.b * 0.22)) * (1.0 - u * 0.6)
                    flatRing(draw, vc, b.pos.add(0.0, 0.12, 0.0), r - thick * 2.2, r - thick * 0.5, withAlpha(mid, 0f), withAlpha(mid, 0.42f * fade))
                    flatRing(draw, vc, b.pos.add(0.0, 0.13, 0.0), r - thick * 0.5, r, withAlpha(deep, 0.9f * fade), withAlpha(deep, 0.9f * fade))
                    flatRing(draw, vc, b.pos.add(0.0, 0.14, 0.0), r - thick * 0.34, r - thick * 0.12, withAlpha(hot, fade), withAlpha(hot, fade))
                }
                Vfx.Kind.NOVA -> {
                    val grow = 1.0 - (1.0 - u) * (1.0 - u)
                    val r = max(0.3, b.a * (0.3 + 0.8 * grow))
                    val thick = max(0.07, r * 0.06)
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
        val thick = min(b.a * 0.14, 0.7) * (1.0 - u * 0.45)
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
        strip(0.0, thick, withAlpha(deep, 0.95f * fade), withAlpha(deep, 0.4f * fade), 0.0)
        strip(thick * 0.08, thick * 0.72, withAlpha(mid, 0.9f * fade), withAlpha(mid, 0f), 0.004)
        strip(thick * 0.12, thick * 0.3, withAlpha(hot, fade), withAlpha(hot, 0.3f * fade), 0.008)
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
