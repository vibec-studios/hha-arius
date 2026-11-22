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

/** Eirholm attack footprints. The pale fill reaches the end at the same tick as the strike. */
object GroundWarnings {
    private class Palette(val edge: Int, val resting: Int, val queued: Int)

    private val FROST = Palette(0xFF102E50.toInt(), 0xFF285581.toInt(), 0xFF8ED0EC.toInt())
    // Gold is the ward color called out by the White Hole and duo instructions.
    private val SAFE = Palette(0xFF4D3211.toInt(), 0xFF9B6C26.toInt(), 0xFFFFDE8D.toInt())

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
        val fill: List<Cell>,
    )

    private class Cell(val a: Vec3d, val b: Vec3d, val c: Vec3d, val d: Vec3d, val from: Double, val until: Double)

    /** The same received geometry and deadline drive both the ground and Solvane's parry cue. */
    fun parryWindows(world: ClientWorld, at: Vec3d, add: (Vec3d, Int) -> Unit) {
        if (world !== level) return
        for (mark in marks) {
            val left = mark.life - (world.time - mark.born)
            if (!mark.safe && left in 0..60 && contains(mark, at)) add(mark.origin, left.toInt())
        }
    }

    private fun contains(m: Mark, at: Vec3d): Boolean {
        if (abs(at.y - m.origin.y) > 4.0) return false
        val delta = Vec3d(at.x - m.origin.x, 0.0, at.z - m.origin.z)
        val length = delta.length()
        return when (m.kind) {
            Vfx.Kind.TELEGRAPH_LINE -> {
                val axis = Vec3d(m.vector.x - m.origin.x, 0.0, m.vector.z - m.origin.z)
                val along = (delta.dotProduct(axis) / axis.lengthSquared()).coerceIn(0.0, 1.0)
                delta.subtract(axis.multiply(along)).length() <= m.radius
            }
            Vfx.Kind.TELEGRAPH_RING -> length <= m.radius && length >= m.inner
            else -> length <= m.radius && (length < 1e-6 ||
                delta.normalize().dotProduct(Vec3d(m.vector.x, 0.0, m.vector.z).normalize()) >= cos(Math.toRadians(m.inner)))
        }
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
        val rims = ArrayList<Poly>(2)
        when (kind) {
            Vfx.Kind.TELEGRAPH_RING -> {
                rims += circle(origin, radius, ground)
                val hole = if (inner > 0.05 && inner < radius - 0.05) inner else 0.0
                if (hole > 0.0) rims += circle(origin, hole, ground, invert = true)
            }
            Vfx.Kind.TELEGRAPH_LINE -> {
                val flat = Vec3d(vector.x - origin.x, 0.0, vector.z - origin.z)
                if (flat.lengthSquared() < 1e-6) return true
                val angle = atan2(flat.z, flat.x)
                val segments = ceil(radius * PI / 0.55).toInt().coerceIn(12, 90)
                val corners = buildList {
                    for ((center, start) in listOf(vector to angle - PI / 2.0, origin to angle + PI / 2.0)) {
                        for (i in 0..segments) {
                            val a = start + PI * i / segments
                            add(center.add(radius * cos(a), 0.0, radius * sin(a)))
                        }
                    }
                }
                rims += polygon(corners, ground)
            }
            else -> {
                val heading = atan2(vector.z, vector.x)
                val half = Math.toRadians(inner.coerceIn(1.0, 180.0))
                if (half >= PI - 0.01) {
                    rims += circle(origin, radius, ground)
                } else {
                    val n = ceil(half * 2 * radius / 0.8).toInt().coerceIn(8, 160)
                    val corners = ArrayList<Vec3d>(n + 2)
                    corners += origin
                    for (i in 0..n) {
                        val a = heading - half + 2 * half * i / n
                        corners += origin.add(radius * cos(a), 0.0, radius * sin(a))
                    }
                    rims += polygon(corners, ground)
                }
            }
        }
        if (marks.size >= 96) marks.removeAt(0)
        marks += Mark(kind, origin, vector, radius, inner, safe, world.time, p.life.coerceAtLeast(1), rims, mesh(kind, origin, vector, radius, inner, ground))
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

    private fun mesh(kind: Vfx.Kind, origin: Vec3d, end: Vec3d, radius: Double, inner: Double,
                     ground: (Double, Double) -> Vec3d): List<Cell> {
        val line = kind == Vfx.Kind.TELEGRAPH_LINE
        val ring = kind == Vfx.Kind.TELEGRAPH_RING
        val axis = if (line) Vec3d(end.x - origin.x, 0.0, end.z - origin.z) else end
        val side = Vec3d(-axis.z, 0.0, axis.x).normalize()
        val heading = atan2(axis.z, axis.x)
        val half = if (ring) PI else Math.toRadians(inner.coerceIn(1.0, 180.0))
        val hole = if (ring) inner.coerceIn(0.0, radius) else 0.0
        val length = if (line) axis.length() else radius - hole
        val width = if (line) radius * 2.0 else radius * half * 2.0
        val rows = ceil((length + if (line) radius * 2.0 else 0.0) / 0.85).toInt().coerceIn(1, 100)
        val cols = ceil(width / 0.85).toInt().coerceIn(12, 180)
        fun point(i: Int, j: Int): Vec3d {
            val u = i.toDouble() / rows
            val v = j.toDouble() / cols
            val pos = if (line) {
                val across = (v * 2.0 - 1.0) * radius
                val cap = sqrt((radius * radius - across * across).coerceAtLeast(0.0))
                val along = (length + 2.0 * cap) * u - cap
                origin.add(axis.multiply(along / length)).add(side.multiply(across))
            } else {
                val r = if (ring) radius - length * u else length * u
                val a = heading + (v * 2.0 - 1.0) * half
                origin.add(r * cos(a), 0.0, r * sin(a))
            }
            return ground(pos.x, pos.z)
        }
        var first = Array(cols + 1) { point(0, it) }
        return buildList {
            for (i in 0 until rows) {
                val next = Array(cols + 1) { point(i + 1, it) }
                for (j in 0 until cols) add(Cell(first[j], first[j + 1], next[j + 1], next[j],
                    i.toDouble() / rows, (i + 1.0) / rows))
                first = next
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
        nearWarning = marks.any { distanceTo(it, draw.cam) < it.radius + 30.0 }
        if (marks.isNotEmpty()) {
            // Alles in einem Puffer: Ein zweiter Ebenen-Puffer würde den ersten vorzeitig abschließen.
            val vc = draw.buffer(AriusLayers.INK)
            for (m in marks) {
                if (distanceTo(m, draw.cam) > m.radius + 110.0) continue
                drawMark(draw, vc, m, time)
            }
        }
        if (bursts.isNotEmpty() && AriusClientConfig.fx > 0.01f) renderBursts(draw, time)
    }

    private fun distanceTo(m: Mark, p: Vec3d): Double {
        if (m.kind != Vfx.Kind.TELEGRAPH_LINE) return p.distanceTo(m.origin)
        val delta = m.vector.subtract(m.origin)
        val u = (p.subtract(m.origin).dotProduct(delta) / delta.lengthSquared()).coerceIn(0.0, 1.0)
        return p.distanceTo(m.origin.add(delta.multiply(u)))
    }

    private fun drawMark(d: VfxDraw, vc: VertexConsumer, m: Mark, time: Long) {
        val t = time - m.born + d.tickDelta
        val progress = (t / m.life).toDouble().coerceIn(0.0, 1.0)
        val appear = (t / 3f).coerceIn(0f, 1f)
        val storm = SnowStorm.whiteout.coerceIn(0f, 1f)
        val pal = if (m.safe) SAFE else FROST
        val rest = withAlpha(pal.resting, (0.53f + 0.18f * storm) * appear)
        val fill = withAlpha(pal.queued, (0.72f + 0.13f * storm) * appear)
        fun quad(a: Vec3d, b: Vec3d, c: Vec3d, e: Vec3d, color: Int) {
            with(d) {
                vc.put(a, 0f, 0f, color)
                vc.put(b, 0f, 1f, color)
                vc.put(c, 1f, 1f, color)
                vc.put(e, 1f, 0f, color)
            }
        }
        for (cell in m.fill) {
            when {
                progress >= cell.until -> quad(cell.a, cell.b, cell.c, cell.d, fill)
                progress <= cell.from -> quad(cell.a, cell.b, cell.c, cell.d, rest)
                else -> {
                    val u = (progress - cell.from) / (cell.until - cell.from)
                    val left = cell.a.lerp(cell.d, u)
                    val right = cell.b.lerp(cell.c, u)
                    quad(cell.a, cell.b, right, left, fill)
                    quad(left, right, cell.c, cell.d, rest)
                }
            }
        }
        val edge = withAlpha(pal.edge, 0.96f * appear)
        for (rim in m.rims) band(d, vc, rim, -0.045, 0.085, 0.018, edge, edge)
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
        return (sqrt(dx * dx + dy * dy + dz * dz) / 9.0).coerceIn(0.42, 3.2)
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
