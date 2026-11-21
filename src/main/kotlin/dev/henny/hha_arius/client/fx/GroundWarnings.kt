package dev.henny.hha_arius.client.fx

import dev.henny.hha.client.vfx.VfxDraw
import dev.henny.hha.client.vfx.withAlpha
import dev.henny.hha.logic.Vfx
import dev.henny.hha.net.VfxPayload
import dev.henny.hha_arius.arena.ArenaDimension
import net.minecraft.client.world.ClientWorld
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import net.minecraft.util.math.Vec3d
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * High-contrast, fog-free ground telegraphs and combat impact overlays for Eirholm.
 *
 * Uses alpha-blended & fog-free [AriusLayers] so telegraphs and HHA attack impacts stay razor-sharp even in full
 * whiteout blizzards, under 1500% fullbright gamma mods, and when Sodium Extra disables particles.
 */
object GroundWarnings {
    private data class Stroke(val a: Vec3d, val b: Vec3d, val across: Vec3d)
    private data class Mark(
        val kind: Vfx.Kind,
        val origin: Vec3d,
        val vector: Vec3d,
        val radius: Double,
        val inner: Double,
        val safe: Boolean,
        val born: Long,
        val life: Int,
        val surfaceY: Double,
        val edges: List<Stroke>,
    )

    private data class Burst(
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
    private val UP = Vec3d(0.0, 1.0, 0.0)

    private var level: ClientWorld? = null
    private val marks = ArrayList<Mark>()
    private val bursts = ArrayList<Burst>()
    var nearWarning = false
        private set

    fun reset() {
        level = null
        nearWarning = false
        marks.clear()
        bursts.clear()
    }

    @JvmStatic
    fun receive(p: VfxPayload, world: ClientWorld) {
        if (world.registryKey != ArenaDimension.WORLD) return
        if (world !== level) {
            reset()
            level = world
        }
        val kind = Vfx.Kind.entries.getOrNull(p.kind) ?: return
        if (kind.name == "TELEGRAPH_CLEAR") {
            val origin = Vec3d(p.x, p.y, p.z)
            marks.removeIf { it.origin.squaredDistanceTo(origin) < 1e-4 }
            return
        }
        // Mirror HHA attack impacts onto fog-free high-contrast layers so they pop in blizzards & fullbright.
        if (kind == Vfx.Kind.CRESCENT || kind == Vfx.Kind.SHOCKWAVE || kind == Vfx.Kind.NOVA) {
            if (bursts.size >= 48) bursts.removeAt(0)
            bursts += Burst(
                kind = kind,
                style = p.style,
                pos = Vec3d(p.x, p.y, p.z),
                dir = Vec3d(p.dx, p.dy, p.dz),
                a = p.a.toDouble().coerceIn(0.4, 48.0),
                b = p.b.toDouble().coerceIn(0.2, 16.0),
                born = world.time,
                life = p.life.coerceIn(4, 30),
            )
            return
        }
        if (kind != Vfx.Kind.TELEGRAPH_LINE && kind != Vfx.Kind.TELEGRAPH_RING && kind != Vfx.Kind.TELEGRAPH_CONE) return
        val origin = Vec3d(p.x, p.y, p.z)
        val vector = Vec3d(p.dx, p.dy, p.dz)
        val radius = p.a.toDouble().coerceIn(0.01, 256.0)
        val inner = p.b.toDouble()
        val edges = ArrayList<Stroke>()
        fun edge(a: Vec3d, b: Vec3d) {
            val across = Vec3d(a.z - b.z, 0.0, b.x - a.x).normalize()
            if (across.lengthSquared() < 0.5) return
            val n = ceil(a.distanceTo(b) / 1.25).toInt().coerceIn(1, 256)
            var prev = surface(world, a)
            for (i in 1..n) {
                val next = surface(world, a.lerp(b, i.toDouble() / n))
                edges.add(Stroke(prev, next, across))
                prev = next
            }
        }
        fun arc(r: Double, heading: Double, half: Double) {
            val n = ceil(half * 2 * r / 1.1).toInt().coerceIn(12, 200)
            fun point(angle: Double) = origin.add(r * cos(angle), 0.0, r * sin(angle))
            var prev = point(heading - half)
            if (half < PI - 0.01) edge(origin, prev)
            for (i in 1..n) {
                val next = point(heading - half + 2 * half * i / n)
                edge(prev, next)
                prev = next
            }
            if (half < PI - 0.01) edge(prev, origin)
        }
        when (kind) {
            Vfx.Kind.TELEGRAPH_LINE -> {
                val side = Vec3d(origin.z - vector.z, 0.0, vector.x - origin.x).normalize().multiply(radius)
                edge(origin.add(side), vector.add(side))
                edge(vector.add(side), vector.subtract(side))
                edge(vector.subtract(side), origin.subtract(side))
                edge(origin.subtract(side), origin.add(side))
            }
            Vfx.Kind.TELEGRAPH_RING -> {
                arc(radius, 0.0, PI)
                if (inner > 0.0 && inner < radius) arc(inner, 0.0, PI)
            }
            Vfx.Kind.TELEGRAPH_CONE -> arc(radius, atan2(vector.z, vector.x), Math.toRadians(inner.coerceIn(1.0, 180.0)))
            else -> return
        }
        val surfaceOrigin = surface(world, origin)
        if (marks.size >= 128) marks.removeAt(0)
        marks += Mark(
            kind = kind,
            origin = origin,
            vector = vector,
            radius = radius,
            inner = inner,
            safe = kind != Vfx.Kind.TELEGRAPH_CONE && p.style == Vfx.Style.GOLD.ordinal,
            born = world.time,
            life = p.life.coerceAtLeast(1),
            surfaceY = surfaceOrigin.y,
            edges = edges,
        )
    }

    /** Use the closest walkable surface at the indicated elevation, including snow layers and bridge decks. */
    private fun surface(world: ClientWorld, at: Vec3d): Vec3d {
        val block = BlockPos.ofFloored(at)
        var height = at.y
        var best = 3.0
        for (dy in -3..2) {
            val pos = block.up(dy)
            val shape = world.getBlockState(pos).getCollisionShape(world, pos)
            if (shape.isEmpty) continue
            val y = pos.y + shape.getMax(Direction.Axis.Y)
            val distance = abs(y - at.y)
            if (distance < best) {
                best = distance
                height = y
            }
        }
        return Vec3d(at.x, height + 0.09, at.z)
    }

    fun render(world: ClientWorld, draw: VfxDraw) {
        if (level !== world) {
            reset()
            return
        }
        val time = world.time
        marks.removeIf { time - it.born >= it.life }
        bursts.removeIf { time - it.born >= it.life }
        nearWarning = false

        if (marks.isNotEmpty()) renderMarks(draw, time)
        if (bursts.isNotEmpty() && AriusClientConfig.fx > 0.01f) renderBursts(draw, time)
    }

    private fun renderMarks(draw: VfxDraw, time: Long) {
        // Pass 1: all INK geometry (interior fills/sweeps, dark-backed borders, rising curtains).
        // Never hold two custom-layer buffers from VertexConsumerProvider.Immediate at the same time.
        for (mark in marks) {
            val progress = ((time - mark.born + draw.tickDelta) / mark.life).coerceIn(0f, 1f)
            val pulse = 0.85f + 0.15f * sin((time - mark.born + draw.tickDelta) * 0.9f)
            val dark = if (mark.safe) 0xFA261D07.toInt() else 0xFA3B091B.toInt()
            val fillCol = if (mark.safe) 0xFFFFCF4A.toInt() else 0xFF8C1030.toInt()
            val sweepCol = if (mark.safe) 0xFFFFEE96.toInt() else 0xFFFF3A48.toInt()
            val bright = if (mark.safe) 0xFFFFE98A.toInt() else 0xFFFF4D58.toInt()
            val hot = if (mark.safe) 0xFFFFFFFF.toInt() else 0xFFFFC0B0.toInt()
            val width = 0.22 + progress * 0.075
            val curtainH = 0.55 + progress * 0.45

            // 1) High-contrast interior danger/safe fill + animated timing sweep (fog-free alpha blend!).
            if (mark.origin.squaredDistanceTo(draw.cam) < 128.0 * 128.0) {
                if (mark.origin.squaredDistanceTo(draw.cam) < 36.0 * 36.0) nearWarning = true
                drawInterior(draw, mark, progress, fillCol, sweepCol)
            }

            // 2) Bold dark-backed neon border + rising vertical perimeter curtain (visible above snow drifts/whiteout).
            val vc = draw.buffer(AriusLayers.INK)
            for ((a, b, across) in mark.edges) {
                if (a.squaredDistanceTo(draw.cam) > 128.0 * 128.0) continue
                if (a.squaredDistanceTo(draw.cam) < 36.0 * 36.0) nearWarning = true
                fun stripe(half: Double, lift: Double, color: Int) {
                    val x = across.x * half
                    val z = across.z * half
                    with(draw) {
                        vc.put(a.x - x, a.y + lift, a.z - z, 0f, 0f, color)
                        vc.put(a.x + x, a.y + lift, a.z + z, 1f, 0f, color)
                        vc.put(b.x + x, b.y + lift, b.z + z, 1f, 1f, color)
                        vc.put(b.x - x, b.y + lift, b.z - z, 0f, 1f, color)
                    }
                }
                stripe(width, 0.0, dark)
                stripe(width * 0.56, 0.008, withAlpha(bright, (0.90f + progress * 0.10f) * pulse))
                stripe(width * 0.22, 0.014, withAlpha(hot, 0.65f + progress * 0.35f))

                // Rising vertical perimeter curtain cuts cleanly through blizzard snow & snow layers.
                val baseCol = withAlpha(bright, (0.48f + 0.34f * progress) * pulse)
                val topCol = withAlpha(bright, 0f)
                with(draw) {
                    vc.put(a.x, a.y, a.z, 0f, 1f, baseCol)
                    vc.put(b.x, b.y, b.z, 1f, 1f, baseCol)
                    vc.put(b.x, b.y + curtainH, b.z, 1f, 0f, topCol)
                    vc.put(a.x, a.y + curtainH, a.z, 0f, 0f, topCol)
                    // Back-face so curtain is visible from both inside and outside the zone.
                    vc.put(b.x, b.y, b.z, 1f, 1f, baseCol)
                    vc.put(a.x, a.y, a.z, 0f, 1f, baseCol)
                    vc.put(a.x, a.y + curtainH, a.z, 0f, 0f, topCol)
                    vc.put(b.x, b.y + curtainH, b.z, 1f, 0f, topCol)
                }
            }
        }

        // Pass 2: luminous top halo along edges near impact (separate VFX_GLOW buffer).
        var glow: net.minecraft.client.render.VertexConsumer? = null
        for (mark in marks) {
            val progress = ((time - mark.born + draw.tickDelta) / mark.life).coerceIn(0f, 1f)
            if (progress <= 0.45f) continue
            val sweepCol = if (mark.safe) 0xFFFFEE96.toInt() else 0xFFFF3A48.toInt()
            val curtainH = 0.55 + progress * 0.45
            val gBase = withAlpha(sweepCol, (progress - 0.45f) * 1.4f)
            val gTop = withAlpha(sweepCol, 0f)
            val g = glow ?: draw.buffer(AriusLayers.VFX_GLOW).also { glow = it }
            for ((a, b, _) in mark.edges) {
                if (a.squaredDistanceTo(draw.cam) > 128.0 * 128.0) continue
                with(draw) {
                    g.put(a.x, a.y + 0.04, a.z, 0.5f, 1f, gBase)
                    g.put(b.x, b.y + 0.04, b.z, 0.5f, 1f, gBase)
                    g.put(b.x, b.y + curtainH * 1.15, b.z, 0.5f, 0f, gTop)
                    g.put(a.x, a.y + curtainH * 1.15, a.z, 0.5f, 0f, gTop)
                }
            }
        }
    }

    private fun drawInterior(draw: VfxDraw, mark: Mark, progress: Float, fillCol: Int, sweepCol: Int) {
        val y = mark.surfaceY + 0.004
        val ySweep = y + 0.006
        val baseAlpha = if (mark.safe) 0.24f + 0.16f * progress else 0.30f + 0.20f * progress
        val sweepAlpha = if (mark.safe) 0.38f + 0.25f * progress else 0.42f + 0.34f * progress

        when (mark.kind) {
            Vfx.Kind.TELEGRAPH_LINE -> {
                val a = Vec3d(mark.origin.x, y, mark.origin.z)
                val b = Vec3d(mark.vector.x, y, mark.vector.z)
                val side = Vec3d(a.z - b.z, 0.0, b.x - a.x).let {
                    if (it.lengthSquared() < 1e-6) return else it.normalize().multiply(mark.radius)
                }
                val vc = draw.buffer(AriusLayers.INK)
                val fc = withAlpha(fillCol, baseAlpha)
                with(draw) {
                    vc.put(a.x - side.x, y, a.z - side.z, 0f, 0f, fc)
                    vc.put(a.x + side.x, y, a.z + side.z, 1f, 0f, fc)
                    vc.put(b.x + side.x, y, b.z + side.z, 1f, 1f, fc)
                    vc.put(b.x - side.x, y, b.z - side.z, 0f, 1f, fc)
                }
                // Advancing timing fill from origin -> end + widening center bar.
                val head = a.lerp(b, progress.toDouble())
                val sc = withAlpha(sweepCol, sweepAlpha)
                with(draw) {
                    vc.put(a.x - side.x, ySweep, a.z - side.z, 0f, 0f, withAlpha(sweepCol, sweepAlpha * 0.45f))
                    vc.put(a.x + side.x, ySweep, a.z + side.z, 1f, 0f, withAlpha(sweepCol, sweepAlpha * 0.45f))
                    vc.put(head.x + side.x, ySweep, head.z + side.z, 1f, 1f, sc)
                    vc.put(head.x - side.x, ySweep, head.z - side.z, 0f, 1f, sc)
                }
            }
            Vfx.Kind.TELEGRAPH_RING -> {
                val c = Vec3d(mark.origin.x, y, mark.origin.z)
                val inner = mark.inner.coerceIn(0.0, mark.radius - 0.05)
                val segs = ceil(2 * PI * mark.radius / 1.4).toInt().coerceIn(24, 96)
                draw.ring(AriusLayers.INK, c, X, Z, inner, mark.radius, withAlpha(fillCol, baseAlpha * 0.85f), withAlpha(fillCol, baseAlpha), segments = segs)
                val sweepR = inner + (mark.radius - inner) * progress.toDouble()
                val bandInner = max(inner, sweepR - max(0.35, (mark.radius - inner) * 0.18))
                draw.ring(AriusLayers.INK, c.add(0.0, 0.006, 0.0), X, Z, inner, sweepR, withAlpha(sweepCol, sweepAlpha * 0.35f), withAlpha(sweepCol, sweepAlpha * 0.65f), segments = segs)
                draw.ring(AriusLayers.INK, c.add(0.0, 0.012, 0.0), X, Z, bandInner, sweepR, withAlpha(sweepCol, 0.1f), withAlpha(0xFFFFD8D0.toInt(), 0.85f), segments = segs)
            }
            Vfx.Kind.TELEGRAPH_CONE -> {
                val c = Vec3d(mark.origin.x, y, mark.origin.z)
                val heading = atan2(mark.vector.z, mark.vector.x)
                val half = Math.toRadians(mark.inner.coerceIn(1.0, 180.0))
                val segs = ceil(half * 2 * mark.radius / 1.2).toInt().coerceIn(12, 64)
                draw.arc(AriusLayers.INK, c, X, Z, 0.0, mark.radius, heading - half, heading + half, withAlpha(fillCol, baseAlpha * 0.7f), withAlpha(fillCol, baseAlpha), segments = segs)
                val sweepR = mark.radius * progress.toDouble()
                draw.arc(AriusLayers.INK, c.add(0.0, 0.006, 0.0), X, Z, 0.0, sweepR, heading - half, heading + half, withAlpha(sweepCol, sweepAlpha * 0.4f), withAlpha(sweepCol, sweepAlpha * 0.85f), segments = segs)
            }
            else -> Unit
        }
    }

    /** Fog-free, high-contrast mirrors of HHA CRESCENT / SHOCKWAVE / NOVA attacks so they pop in storms & fullbright. */
    private fun renderBursts(draw: VfxDraw, time: Long) {
        val fx = AriusClientConfig.fx.coerceIn(0f, 1f)
        for (b in bursts) {
            if (b.pos.squaredDistanceTo(draw.cam) > 96.0 * 96.0) continue
            val t = (time - b.born + draw.tickDelta).coerceAtLeast(0f)
            val u = (t / b.life).coerceIn(0f, 1f)
            val fade = (1f - u) * fx
            if (fade <= 0.01f) continue
            val deepCol = when (b.style) {
                Vfx.Style.GOLD.ordinal -> 0xFF8A5400.toInt()
                Vfx.Style.SOUL.ordinal -> 0xFF1A3A7A.toInt()
                Vfx.Style.STORM.ordinal -> 0xFF0E4570.toInt()
                else -> 0xFF154E7A.toInt()
            }
            val brightCol = when (b.style) {
                Vfx.Style.GOLD.ordinal -> 0xFFFFE58A.toInt()
                Vfx.Style.SOUL.ordinal -> 0xFFA8D8FF.toInt()
                Vfx.Style.STORM.ordinal -> 0xFF9BE6FF.toInt()
                else -> 0xFFDDF5FF.toInt()
            }
            when (b.kind) {
                Vfx.Kind.CRESCENT -> {
                    val fwd = Vec3d(b.dir.x, 0.0, b.dir.z).let { if (it.lengthSquared() < 1e-6) Z else it.normalize() }
                    val side = Vec3d(-fwd.z, 0.0, fwd.x)
                    val span = b.b.coerceIn(0.8, 3.0)
                    val reach = b.a * (0.85 + 0.22 * u)
                    draw.arc(AriusLayers.INK, b.pos, fwd, side, reach * 0.72, reach, -span * 0.5, span * 0.5,
                        withAlpha(deepCol, 0.72f * fade), withAlpha(brightCol, 0.92f * fade), segments = 24)
                    draw.arc(AriusLayers.VFX_GLOW, b.pos.add(0.0, 0.03, 0.0), fwd, side, reach * 0.84, reach * 1.08, -span * 0.5, span * 0.5,
                        withAlpha(brightCol, 0.95f * fade), withAlpha(0xFFFFFFFF.toInt(), 0f), segments = 24)
                }
                Vfx.Kind.SHOCKWAVE -> {
                    val grow = 1.0 - (1.0 - u) * (1.0 - u)
                    val r = max(0.3, b.a * grow)
                    val thick = max(0.45, b.b * 0.55)
                    val pos = b.pos.add(0.0, 0.12, 0.0)
                    draw.ring(AriusLayers.INK, pos, X, Z, max(0.0, r - thick), r,
                        withAlpha(deepCol, 0.78f * fade), withAlpha(brightCol, 0.95f * fade), segments = 48)
                    draw.ring(AriusLayers.VFX_GLOW, pos.add(0.0, 0.04, 0.0), X, Z, max(0.0, r - thick * 1.3), r + 0.25,
                        withAlpha(brightCol, 0f), withAlpha(0xFFFFFFFF.toInt(), 0.9f * fade), segments = 48)
                }
                Vfx.Kind.NOVA -> {
                    val grow = 1.0 - (1.0 - u) * (1.0 - u)
                    val r = max(0.4, b.a * (0.25 + 0.85 * grow))
                    draw.ring(AriusLayers.INK, b.pos, X, Z, max(0.0, r - 0.65), r,
                        withAlpha(deepCol, 0.75f * fade), withAlpha(brightCol, 0.9f * fade), segments = 40)
                    draw.billboard(AriusLayers.VFX_FLARE, b.pos, r * 1.4, withAlpha(brightCol, 0.85f * fade))
                }
                else -> Unit
            }
        }
    }
}
